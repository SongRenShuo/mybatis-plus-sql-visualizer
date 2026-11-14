package cn.song.mybatisplus.visualizer.script

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * T-RollbackSnapshotPreExec P6：前置/后置脚本模板与「空脚本」判定。
 *
 * 空脚本判定是 CoolRequest PSI 判空（`EmptyCodeJavaRecursiveElementVisitor.java:35-56`）的
 * **文本级**落地——本类纯逻辑零 IDE 依赖（可进 testFast）：剥注释与空白后与模板骨架逐字节
 * 等价即视为「没写代码」，跳过编译与执行。判空结果按源码 hash 缓存（LRU 64）。
 *
 * 差异原因：PSI 判空要开解析（EDT 断言面 + 平台依赖），文本级等价判定对「模板没动过」
 * 这一主场景语义相同，代价小一个数量级；用户改过模板任何字符都会进编译器，不会误跳过。
 */
object SqlScriptTemplates {

    enum class Kind { PRE, POST }

    /** 前置脚本模板（用户看到的初始内容）。 */
    val PRE_TEMPLATE: String = """
        import cn.song.mybatisplus.visualizer.script.api.IEnv;
        import cn.song.mybatisplus.visualizer.script.api.SqlExecContext;
        import cn.song.mybatisplus.visualizer.script.api.SqlPreScript;
        import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog;

        public class SqlPreScriptImpl extends SqlPreScript {
            @Override
            public boolean handlerSql(SqlScriptLog log, SqlExecContext ctx, IEnv env) {
                log.println("pre script: " + ctx.getStatementKind() + " on " + ctx.getTargetTable());
                // return false 可否决本次执行；返回 true 放行
                return true;
            }
        }
    """.trimIndent()

    /** 后置脚本模板。 */
    val POST_TEMPLATE: String = """
        import cn.song.mybatisplus.visualizer.script.api.IEnv;
        import cn.song.mybatisplus.visualizer.script.api.SqlExecResult;
        import cn.song.mybatisplus.visualizer.script.api.SqlPostScript;
        import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog;

        public class SqlPostScriptImpl extends SqlPostScript {
            @Override
            public void handlerResult(SqlScriptLog log, SqlExecResult result, IEnv env) {
                log.println("affected rows: " + result.getAffectedRows());
                // env.setVar("lastCount", String.valueOf(result.getAffectedRows()));
            }
        }
    """.trimIndent()

    fun templateFor(kind: Kind): String = when (kind) {
        Kind.PRE -> PRE_TEMPLATE
        Kind.POST -> POST_TEMPLATE
    }

    private val cache = ConcurrentHashMap<Int, Boolean>()

    /** 源码是否仍是空模板（剥注释/空白后与模板骨架等价）。 */
    fun isEmptyScript(source: String, kind: Kind): Boolean {
        val key = hash(source)
        cache[key]?.let { return it }
        val result = normalize(source) == normalize(templateFor(kind))
        if (cache.size > 64) cache.clear()
        cache[key] = result
        return result
    }

    /**
     * 归一化：剥行注释（`//` 与 `--`）、块注释与全部空白，仅保留有效字符序列。
     * 保守近似：字符串字面量内的注释符会被误剥——只影响「是否为空模板」的判定方向
     * （误判成空=跳过编译），模板自身没有含注释符的字符串字面量，主场景语义不变。
     */
    internal fun normalize(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(source.length)
                }
                c == '-' && i + 1 < source.length && source[i + 1] == '-' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c.isWhitespace() -> i++
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun hash(text: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest[0].toInt() or (digest[1].toInt() shl 8) or (digest[2].toInt() shl 16) or (digest[3].toInt() shl 24)
    }
}
