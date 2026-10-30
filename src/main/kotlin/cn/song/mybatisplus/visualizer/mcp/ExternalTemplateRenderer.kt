package cn.song.mybatisplus.visualizer.mcp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * T-TemplateHook（FR-0045 第 3 项 MVP）：外部模板渲染器——把「模板文件路径 + sqlId + 参数 JSON」
 * 交给用户自己配置的外部命令，取回 stdout 作为 SQL。
 *
 * **纯逻辑层**：不 import 任何 `com.intellij` 类型，普通 JUnit 可直测（与应用内 headless 约束同款，
 * 见 [McpToolErrors] 头注）。进程启动经 [run] 的 `start` 参数注入，单测用假 [Process] 覆盖
 * 超时/非零退出/输出超限三条分支；另有一条真实进程用例（各平台自带 echo）保证默认启动路径可用。
 *
 * 执行纪律（与计划 §2.3 的进程条款一一对应）：
 *  - `ProcessBuilder` **列表形态**，绝不 `sh -c` / `cmd /c` 包一层——命令模板先经 [tokenize] 切分，
 *    再由 [substitute] 逐 token 替换占位符，永不拼成一段 shell 文本；
 *  - stdout/stderr 按 UTF-8 **分别**读取（两个后台读线程，避免大输出把管道写满后互相死锁）；
 *  - 超时即 `destroyForcibly()`，返回超时失败结果；
 *  - 非零退出码返回失败，stderr 只取**首个非空白行**并截断到 [MAX_STDERR_LINE_CHARS] 字符；
 *  - stdout 总长上限 [MAX_OUTPUT_BYTES]（1 MiB），超限截断并在 [RenderResult.truncated] 标注。
 *
 * **安全红线**（写进用户可见的设置页说明）：
 *  - 命令内容由用户自己配置、由用户在**自己的机器**上执行，插件不校验其内容、不负责其副作用
 *    （等价于用户手敲一条命令）；
 *  - `paramsJson` 可能含业务参数值，**不得**写入任何日志或错误文本——本类只在 [substitute] 里
 *    把它填进命令行，不参与错误消息构造；stderr 只回显截断首行（由用户程序自己打印，插件不加工）。
 */
object ExternalTemplateRenderer {

    /** 模板文件绝对路径占位符。 */
    const val PLACEHOLDER_TEMPLATE = "{template}"

    /** 语句 id 占位符（tag 无 `id` 属性时替换为空串）。 */
    const val PLACEHOLDER_SQL_ID = "{sqlId}"

    /** 参数 JSON 占位符（调用方未传时替换为空串，不塞 `null` 字面量）。 */
    const val PLACEHOLDER_PARAMS_JSON = "{paramsJson}"

    /** 超时下限（秒）；设置值读取时钳在此区间。 */
    const val MIN_TIMEOUT_SECONDS = 1

    /** 超时上限（秒）。 */
    const val MAX_TIMEOUT_SECONDS = 120

    /** stdout 长度上限（1 MiB）。 */
    const val MAX_OUTPUT_BYTES = 1 shl 20

    /** stderr 回显首行的字符上限。 */
    const val MAX_STDERR_LINE_CHARS = 500

    /**
     * 切分结果：成功时 [error] 为 null；失败时 [tokens] 为空且 [error] 非空
     * （空模板 / 只有空白 / 引号未闭合都走这一条，调用方据 [error] 直接报结构化失败）。
     */
    data class Tokenized(val tokens: List<String>, val error: String? = null)

    /**
     * 渲染结果：[sql] 与 [error] 互斥；[truncated] 表示 stdout 撞到 [MAX_OUTPUT_BYTES] 被截断。
     * [error] 是面向 agent 的英文短语（不含 [PLACEHOLDER_PARAMS_JSON] 的实参内容）。
     */
    data class RenderResult(
        val sql: String?,
        val error: String? = null,
        val truncated: Boolean = false,
        val exitCode: Int? = null,
    )

    /**
     * 引号感知切分：空格外按 token 切分，支持 `"..."`、`'...'` 分组与 `\` 转义
     * （引号内外的反斜杠都按「下一个字符取字面值」处理，双引号内 `\"` 因此可写）。
     *
     * 失败语义（[Tokenized.error] 非空、tokens 为空）：模板为空/纯空白、引号未闭合。
     * 不做任何 shell 解释（无通配、无变量展开、无管道）——这正是本 MVP 的安全边界。
     */
    fun tokenize(commandTemplate: String): Tokenized {
        if (commandTemplate.isBlank()) return Tokenized(emptyList(), "command template is empty")
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var tokenStarted = false
        var inDouble = false
        var inSingle = false
        var i = 0
        while (i < commandTemplate.length) {
            val ch = commandTemplate[i]
            when {
                ch == '\\' -> {
                    if (i + 1 >= commandTemplate.length) {
                        // 末尾孤立反斜杠：按字面值收下（Windows 路径常见），不当失败
                        current.append(ch)
                        tokenStarted = true
                    } else {
                        current.append(commandTemplate[i + 1])
                        tokenStarted = true
                        i++
                    }
                }
                inDouble -> {
                    if (ch == '"') inDouble = false else current.append(ch)
                    tokenStarted = true
                }
                inSingle -> {
                    if (ch == '\'') inSingle = false else current.append(ch)
                    tokenStarted = true
                }
                ch == '"' -> {
                    inDouble = true
                    tokenStarted = true
                }
                ch == '\'' -> {
                    inSingle = true
                    tokenStarted = true
                }
                ch.isWhitespace() -> {
                    if (tokenStarted) {
                        tokens += current.toString()
                        current.setLength(0)
                        tokenStarted = false
                    }
                }
                else -> {
                    current.append(ch)
                    tokenStarted = true
                }
            }
            i++
        }
        if (inDouble) return Tokenized(emptyList(), "unclosed double quote in command template")
        if (inSingle) return Tokenized(emptyList(), "unclosed single quote in command template")
        if (tokenStarted) tokens += current.toString()
        if (tokens.isEmpty()) return Tokenized(emptyList(), "command template produced no tokens")
        return Tokenized(tokens)
    }

    /**
     * 占位符替换（**整串或子串**都替）：`--out={template}` 与 `{template}` 同效。
     * [sqlId] / [paramsJson] 为 null 时替换为空串——不把 `null` 字面量塞进命令行。
     */
    fun substitute(
        tokens: List<String>,
        templatePath: String,
        sqlId: String?,
        paramsJson: String?,
    ): List<String> = tokens.map { raw ->
        raw.replace(PLACEHOLDER_TEMPLATE, templatePath)
            .replace(PLACEHOLDER_SQL_ID, sqlId ?: "")
            .replace(PLACEHOLDER_PARAMS_JSON, paramsJson ?: "")
    }

    /**
     * 执行外部命令并捕获结果。
     *
     * @param command 已切分/替换好的 argv（[tokenize] + [substitute] 的产物）
     * @param workingDir 工作目录（null = 继承当前进程目录；调用方通常传项目根）
     * @param timeoutSeconds 超时秒数（读时钳 [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]）
     * @param start 进程启动器（测试注入点，默认 [startProcess]）
     */
    fun run(
        command: List<String>,
        workingDir: File?,
        timeoutSeconds: Int,
        start: (List<String>, File?) -> Process = ::startProcess,
    ): RenderResult {
        if (command.isEmpty() || command.first().isBlank()) {
            return RenderResult(
                sql = null,
                error = "external renderer command is empty (configure a command template in " +
                    "Settings | Tools | SQL Visualizer | External SQL Template).",
            )
        }
        val timeout = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        val process = try {
            start(argvForLaunch(command), workingDir)
        } catch (e: Exception) {
            return RenderResult(
                sql = null,
                error = "failed to start external renderer (${e.javaClass.simpleName}: " +
                    truncate(e.message.orEmpty(), MAX_STDERR_LINE_CHARS) + ")",
            )
        }
        val stdout = Capture()
        val stderr = Capture()
        val outThread = stdout.startReading(process.inputStream)
        val errThread = stderr.startReading(process.errorStream)

        val finished = try {
            process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { process.destroyForcibly() }
            return RenderResult(sql = null, error = "external renderer was interrupted")
        }
        if (!finished) {
            runCatching { process.destroyForcibly() }
            outThread.join(JOIN_AFTER_KILL_MS)
            errThread.join(JOIN_AFTER_KILL_MS)
            return RenderResult(sql = null, error = "external renderer timed out after ${timeout}s")
        }
        outThread.join(READER_JOIN_MS)
        errThread.join(READER_JOIN_MS)

        val exitCode = runCatching { process.exitValue() }.getOrNull()
        val truncated = stdout.isTruncated || stderr.isTruncated
        if (exitCode != null && exitCode != 0) {
            val firstLine = stderr.firstNonBlankLine()
            val suffix = if (firstLine != null) ": $firstLine" else ""
            return RenderResult(
                sql = null,
                error = "external renderer exited with code $exitCode$suffix",
                truncated = truncated,
                exitCode = exitCode,
            )
        }
        val sql = stdout.text().trim()
        if (sql.isEmpty()) {
            val firstLine = stderr.firstNonBlankLine()
            val suffix = if (firstLine != null) " (stderr: $firstLine)" else ""
            return RenderResult(
                sql = null,
                error = "external renderer produced no SQL on stdout$suffix",
                truncated = truncated,
                exitCode = exitCode,
            )
        }
        return RenderResult(sql = sql, error = null, truncated = truncated, exitCode = exitCode)
    }

    /** 默认启动器：`ProcessBuilder` 列表形态（不经过任何 shell），非工作目录不设工作目录。 */
    private fun startProcess(command: List<String>, workingDir: File?): Process =
        ProcessBuilder(command).apply {
            if (workingDir != null && workingDir.isDirectory) directory(workingDir)
            redirectErrorStream(false)
        }.start()

    /** 被杀进程的读线程收尾等待（短）；读线程本身是 daemon，卡死也不阻塞 JVM 退出。 */
    private const val JOIN_AFTER_KILL_MS = 200L

    /** 正常结束后读线程收尾等待（进程已退出，管道 EOF 即刻到达）。 */
    private const val READER_JOIN_MS = 2000L

    private fun truncate(value: String, max: Int): String = if (value.length <= max) value else value.take(max)

    /**
     * 启动前按平台修正 argv（**真机走查抓到的缺陷**，UI-045-01）。
     *
     * Windows 上 `CreateProcess` 只拿一条命令行字符串，子进程运行时（CRT / Node / Python 都同款）
     * 会把命令行里**任何** `"` 当作分组符并**吃掉**——而 `ProcessBuilder` 只给「含空格/制表符」的
     * 参数补引号，`{"planYear":"2026"}` 这种没有空格的 JSON 会原样进入命令行，于是子进程收到的
     * 是 `{planYear:2026}`。表现是「参数 JSON 到了用户脚本里已经缺引号」，脚本 end-to-end 用不了。
     *
     * 修法＝按 CRT 规则给**含双引号**的参数补一层引号并转义内部引号/尾随反斜杠；不含双引号的参数
     * 一律原样（逐字节不变）。POSIX 上 `execve` 直接传 argv，无此解析，**必须原样不动**。
     *
     * @param windows 平台判定（测试注入点；默认按 [System.getProperty] 判 Windows）
     */
    internal fun argvForLaunch(command: List<String>, windows: Boolean = isWindows()): List<String> =
        if (!windows) command else command.map(::quoteForWindows)

    /** 单个参数的 Windows 引号包裹（只在含 `"` 时生效；算法与 CRT 解析互逆）。 */
    private fun quoteForWindows(arg: String): String {
        if (!arg.contains('"')) return arg
        val sb = StringBuilder(arg.length + 8)
        sb.append('"')
        var backslashes = 0
        for (ch in arg) {
            when (ch) {
                '\\' -> {
                    sb.append('\\')
                    backslashes++
                }
                '"' -> {
                    // CRT 规则：引号前 N 个反斜杠 → 输出 2N+1 个反斜杠再跟 `"`（2N 加倍 + 1 转义）。
                    // 其中 N 个已随 '\\' 分支 append 过，故此处补 N+1 个。
                    repeat(backslashes + 1) { sb.append('\\') }
                    sb.append('"')
                    backslashes = 0
                }
                else -> {
                    sb.append(ch)
                    backslashes = 0
                }
            }
        }
        // 收尾：尾随反斜杠加倍，避免与闭合引号拼成转义序列
        repeat(backslashes) { sb.append('\\') }
        sb.append('"')
        return sb.toString()
    }

    /** 平台判定（Windows 需要命令行引号修正，POSIX 不需要）。 */
    internal fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * 单流捕获器：后台线程按 UTF-8 累计到 [MAX_OUTPUT_BYTES]，超限后继续**排空**（防止子进程
     * 写满管道阻塞）但不再累积，并置 [_isTruncated]。
     */
    private class Capture {
        private val buffer = ByteArrayOutputStream()
        private val lock = Any()

        @Volatile
        private var _isTruncated = false

        val isTruncated: Boolean get() = _isTruncated

        fun startReading(stream: InputStream): Thread {
            val thread = Thread({ drain(stream) }, "mpvs-external-renderer-reader")
            thread.isDaemon = true
            thread.start()
            return thread
        }

        private fun drain(stream: InputStream) {
            val chunk = ByteArray(8192)
            try {
                stream.use { input ->
                    while (true) {
                        val read = try {
                            input.read(chunk)
                        } catch (e: IOException) {
                            -1
                        }
                        if (read < 0) break
                        synchronized(lock) {
                            val room = MAX_OUTPUT_BYTES - buffer.size()
                            when {
                                room <= 0 -> _isTruncated = true
                                read <= room -> buffer.write(chunk, 0, read)
                                else -> {
                                    buffer.write(chunk, 0, room)
                                    _isTruncated = true
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // 读失败（进程被杀/管道关闭）不改变语义：已读到的内容照常返回
            }
        }

        fun text(): String = synchronized(lock) { buffer.toByteArray().toString(Charsets.UTF_8) }

        fun firstNonBlankLine(): String? =
            text().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { truncate(it, MAX_STDERR_LINE_CHARS) }
    }
}
