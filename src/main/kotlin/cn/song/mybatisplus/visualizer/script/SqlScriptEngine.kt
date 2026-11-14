package cn.song.mybatisplus.visualizer.script

import cn.song.mybatisplus.visualizer.script.api.IEnv
import cn.song.mybatisplus.visualizer.script.api.SqlExecContext
import cn.song.mybatisplus.visualizer.script.api.SqlExecResult
import cn.song.mybatisplus.visualizer.script.api.SqlPostScript
import cn.song.mybatisplus.visualizer.script.api.SqlPreScript
import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/**
 * T-RollbackSnapshotPreExec P6：SQL 前置/后置脚本引擎内核（照 CoolRequest 形态：
 * 纯 Java 源码 + javac 内存编译 + MethodHandle 精确签名调用，`JavaCodeEngine.java:69-241`）。
 *
 * 与 CoolRequest 的三个刻意差异（计划 §3.9 必补项）：
 * 1. **编译产物按源码 hash 缓存**（CoolRequest 每次全量重编译，SQL 脚本触发频率高不学它）；
 * 2. **执行超时 + 线程中断**（CoolRequest 无 timeout，死循环卡死整链——我们默认 5s，可传参）；
 * 3. **classpath 只给插件自身类 + JDK**（不继承用户项目 classpath——CoolRequest 把项目全部
 *    库塞进编译面，SQL 场景不需要且扩攻击面）。诚实边界：插件 jar 是单 jar，脚本可引用插件内
 *    任意公开类，「只给 API」在单 jar 打包形态下无法物理收窄；沙箱仍无（红线三道闸由 P7
 *    编排层执行：独立开关默认关 + 文案明示 + 全留痕）。
 *
 * 线程模型：编译在调用线程（**编排层保证不在 EDT 调本引擎**）；脚本执行在独立 daemon 线程，
 * 超时 interrupt；编译/加载在调用线程做（加载父委派到插件 classloader，V10 的测试侧覆盖）。
 */
class SqlScriptEngine(private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {

    sealed interface Outcome {
        /** 前置脚本正常返回。[vetoed]=true 表示脚本否决本次执行。 */
        data class Pre(val vetoed: Boolean, val logText: String) : Outcome

        /** 后置脚本正常返回。 */
        data class Post(val logText: String) : Outcome

        /** 空模板脚本：跳过编译与执行（文本级判空，见 [SqlScriptTemplates.isEmptyScript]）。 */
        object Empty : Outcome

        /** 编译失败（含行号/原因，可操作文案）。 */
        data class CompileError(val message: String, val logText: String) : Outcome

        /** 脚本运行期异常。 */
        data class ScriptError(val message: String, val logText: String) : Outcome

        /** 执行超时（已 interrupt）。 */
        data class Timeout(val timeoutMillis: Long, val logText: String) : Outcome

        /** 脚本形态不合规（没继承基类 / 缺无参构造 / 签名不符）。 */
        data class BadScript(val message: String, val logText: String) : Outcome
    }

    /** 测试缝：真实编译次数（缓存命中不计数）——反向验证「缓存生效」用。 */
    internal val compileCount = AtomicLong(0)

    private class Compiled(val classes: Map<String, ByteArray>)

    private sealed interface CompileResult {
        data class Ok(val compiled: Compiled) : CompileResult
        data class Err(val outcome: Outcome.CompileError) : CompileResult
    }

    // 源码 hash → 编译产物；LRU 32，synchronized（编译本就低频）
    private val cache = object : LinkedHashMap<String, Compiled>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Compiled>): Boolean = size > CACHE_LIMIT
    }

    fun runPre(
        source: String,
        context: SqlExecContext,
        env: IEnv,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): Outcome {
        if (SqlScriptTemplates.isEmptyScript(source, SqlScriptTemplates.Kind.PRE)) return Outcome.Empty
        return runScript(source, timeoutMillis) { cls, instance, log ->
            val mh = findHandler(
                cls, "handlerSql", Boolean::class.javaPrimitiveType!!,
                arrayOf(SqlScriptLog::class.java, SqlExecContext::class.java, IEnv::class.java),
            ) ?: return@runScript Outcome.BadScript(
                "脚本未定义可调用的 handlerSql(SqlScriptLog, SqlExecContext, IEnv)——请勿修改模板方法签名",
                log.content(),
            )
            // 语义：脚本返回 true = 放行；Outcome.Pre.vetoed = 是否否决 = 取反
            val proceed = mh.invoke(instance, log, context, env) as Boolean
            Outcome.Pre(vetoed = !proceed, logText = log.content())
        }
    }

    fun runPost(
        source: String,
        result: SqlExecResult,
        env: IEnv,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): Outcome {
        if (SqlScriptTemplates.isEmptyScript(source, SqlScriptTemplates.Kind.POST)) return Outcome.Empty
        return runScript(source, timeoutMillis) { cls, instance, log ->
            val mh = findHandler(
                cls, "handlerResult", Void.TYPE,
                arrayOf(SqlScriptLog::class.java, SqlExecResult::class.java, IEnv::class.java),
            ) ?: return@runScript Outcome.BadScript(
                "脚本未定义可调用的 handlerResult(SqlScriptLog, SqlExecResult, IEnv)——请勿修改模板方法签名",
                log.content(),
            )
            mh.invoke(instance, log, result, env)
            Outcome.Post(log.content())
        }
    }

    private inline fun runScript(
        source: String,
        timeoutMillis: Long,
        // crossinline：invokeBlock 在 worker Thread 的普通 lambda 里调用（非内联上下文）；
        // 调用方 lambda 只用带标签的局部返回（return@runScript），无非局部 return，故安全
        crossinline invokeBlock: (Class<*>, Any, SqlScriptLog) -> Outcome,
    ): Outcome {
        val log = SqlScriptLog()
        val compiled = when (val cr = compile(source)) {
            is CompileResult.Err -> return cr.outcome
            is CompileResult.Ok -> cr.compiled
        }

        // 加载在调用线程（父委派 → 插件 classloader 能看到 API 类）；脚本执行放独立线程
        val loader = object : ClassLoader(SqlPreScript::class.java.classLoader) {
            private val resolved = HashMap<String, Class<*>>()
            fun loadLocal(name: String): Class<*> {
                val bytes = compiled.classes[name] ?: throw ClassNotFoundException(name)
                return resolved.getOrPut(name) { defineClass(name, bytes, 0, bytes.size) }
            }
        }
        val scriptCls = runCatching {
            compiled.classes.keys.mapNotNull { key ->
                runCatching { loader.loadLocal(key) }.getOrNull()
            }.firstOrNull { SqlPreScript::class.java.isAssignableFrom(it) || SqlPostScript::class.java.isAssignableFrom(it) }
        }.getOrNull() ?: return Outcome.BadScript(
            "编译产物中没有继承 SqlPreScript/SqlPostScript 的类——脚本必须 extends 模板基类",
            log.content(),
        )

        val instance = runCatching { scriptCls.getDeclaredConstructor().newInstance() }.getOrElse {
            return Outcome.BadScript(
                "脚本类缺少 public 无参构造——模板类不要加带参构造",
                log.content(),
            )
        }

        val holder = AtomicReference<Outcome?>(null)
        val worker = Thread {
            holder.set(
                runCatching { invokeBlock(scriptCls, instance, log) }.getOrElse { e ->
                    Outcome.ScriptError(e.message ?: e.javaClass.name, log.content())
                },
            )
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMillis)
        if (worker.isAlive) {
            worker.interrupt()
            worker.join(500)
            return Outcome.Timeout(timeoutMillis, log.content())
        }
        return holder.get() ?: Outcome.ScriptError("script thread ended without outcome", log.content())
    }

    /** 编译（带缓存，命中不重编译）。 */
    private fun compile(source: String): CompileResult {
        val key = sha256(source)
        synchronized(cache) {
            cache[key]?.let { return CompileResult.Ok(it) }
        }

        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: return CompileResult.Err(
                Outcome.CompileError(
                    "当前运行环境没有 Java 编译器（javac），脚本功能不可用。请使用完整 JDK/JBR 运行 IDE。",
                    "",
                ),
            )
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)
        // javac 的解析 classpath = 插件自身类（脚本 import 基类与其它插件公开类）；JDK 类由
        // 标准 FileManager 兜底。用户项目库不进来（计划 §3.9 第 2 条）。
        // setLocationFromPaths：JDK17 起 setLocation 有 File/Path 两个重载，Kotlin 调用歧义
        standard.setLocationFromPaths(StandardLocation.CLASS_PATH, listOf(pluginClassesLocation().toPath()))
        val outputs = HashMap<String, ByteArray>()
        val manager = MemFileManager(standard, outputs)
        // javac 规则：public 类必须落在同名 .java——内存源码也要遵守（URI 按源码里声明的类名定；
        // CoolRequest 用「固定类名 + 正则补 public」绕同一限制，我们支持任意类名所以动态取）
        val declaredClassName = Regex("""\bclass\s+(\w+)""").find(source)?.groupValues?.get(1) ?: "SqlScript"
        val sourceObject = object : SimpleJavaFileObject(
            URI.create("memory:///$declaredClassName.java"), JavaFileObject.Kind.SOURCE,
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(sourceObject))
        val ok = task.call()
        if (!ok || outputs.isEmpty()) {
            val text = diagnostics.diagnostics.joinToString("\n") { d ->
                val line = (d as? Diagnostic<*>)?.lineNumber ?: -1L
                "[line $line] ${d.getMessage(null)}"
            }
            return CompileResult.Err(Outcome.CompileError(text.ifBlank { "compile failed without diagnostics" }, ""))
        }
        val compiled = Compiled(outputs.toMap())
        synchronized(cache) {
            cache[key] = compiled
            compileCount.incrementAndGet()
        }
        return CompileResult.Ok(compiled)
    }

    /** 插件类所在位置（生产 = 插件 jar；测试 = build/classes/java/main）。 */
    private fun pluginClassesLocation(): File =
        File(SqlPreScript::class.java.protectionDomain.codeSource.location.toURI())

    private fun findHandler(
        cls: Class<*>,
        name: String,
        returnType: Class<*>,
        // 显式数组而非 vararg：vararg 形参带 out 投影（Array<out Class<*>>）无法被展开传给 methodType
        paramTypes: Array<Class<*>>,
    ): java.lang.invoke.MethodHandle? = runCatching {
        MethodHandles.publicLookup().findVirtual(cls, name, MethodType.methodType(returnType, paramTypes))
    }.getOrNull()

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class MemFileManager(
        delegate: JavaFileManager,
        private val outputs: MutableMap<String, ByteArray>,
    ) : ForwardingJavaFileManager<JavaFileManager>(delegate) {
        override fun getJavaFileForOutput(
            location: JavaFileManager.Location,
            className: String,
            kind: JavaFileObject.Kind,
            sibling: FileObject?,
        ): JavaFileObject = object : SimpleJavaFileObject(URI.create("memory:///$className"), kind) {
            override fun openOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                override fun close() {
                    outputs[className] = toByteArray()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val CACHE_LIMIT = 32
    }
}
