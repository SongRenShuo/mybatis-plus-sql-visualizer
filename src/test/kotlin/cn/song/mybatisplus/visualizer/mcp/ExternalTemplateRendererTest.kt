package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * T-TemplateHook S2/S5：外部模板渲染器（[ExternalTemplateRenderer]）纯逻辑用例。
 *
 * 覆盖计划 §2.3 的进程条款：切分（引号/空格/转义/未闭合）、占位符替换（含缺省 paramsJson）、
 * 超时、非零退出、输出超限；外加一条**真实进程**用例（各平台自带 echo）验证默认启动路径。
 * 超时/非零/超限三分支用假 [Process] 注入（[ExternalTemplateRenderer.run] 的 `start` 参数），
 * 不起真进程——真进程在 CI/沙盒里不可控。
 */
class ExternalTemplateRendererTest {

    // ------------------------------------------------------------------
    // 切分
    // ------------------------------------------------------------------

    @Test
    fun `切分_空格分隔与占位符token`() {
        val tokenized = ExternalTemplateRenderer.tokenize("python render.py --template={template} --id {sqlId}")
        assertNull("正常模板不应报错: ${tokenized.error}", tokenized.error)
        assertEquals(
            listOf("python", "render.py", "--template={template}", "--id", "{sqlId}"),
            tokenized.tokens,
        )
    }

    @Test
    fun `切分_双引号与单引号分组保留引号内空格`() {
        val tokenized = ExternalTemplateRenderer.tokenize("""renderer --sql "select * from t where a = 'x'" -v 'a b'""")
        assertNull(tokenized.error)
        assertEquals(
            listOf("renderer", "--sql", "select * from t where a = 'x'", "-v", "a b"),
            tokenized.tokens,
        )
    }

    @Test
    fun `切分_反斜杠转义可打出空格与引号`() {
        val tokenized = ExternalTemplateRenderer.tokenize("""prog a\ b "c\"d" e\\f""")
        assertNull(tokenized.error)
        assertEquals(listOf("prog", "a b", "c\"d", "e\\f"), tokenized.tokens)
    }

    @Test
    fun `切分_未闭合引号与空模板是明确失败`() {
        val unclosedDouble = ExternalTemplateRenderer.tokenize("prog \"abc")
        assertNotNull("未闭合双引号必须报错", unclosedDouble.error)
        assertTrue("失败时不得产出 token: ${unclosedDouble.tokens}", unclosedDouble.tokens.isEmpty())
        assertTrue("文案应指明双引号: ${unclosedDouble.error}", unclosedDouble.error!!.contains("double quote"))

        val unclosedSingle = ExternalTemplateRenderer.tokenize("prog 'abc")
        assertNotNull(unclosedSingle.error)
        assertTrue(unclosedSingle.error!!.contains("single quote"))

        val blank = ExternalTemplateRenderer.tokenize("   ")
        assertNotNull("纯空白模板必须报错", blank.error)
        assertTrue(blank.tokens.isEmpty())
    }

    // ------------------------------------------------------------------
    // 占位符替换
    // ------------------------------------------------------------------

    @Test
    fun `替换_整串与子串都替_缺省参数替换为空串`() {
        val tokens = listOf("--tpl={template}", "{sqlId}", "json:{paramsJson}", "--no-param={paramsJson}")
        val replaced = ExternalTemplateRenderer.substitute(tokens, "/tmp/x.xml", "planQry", null)
        assertEquals(
            listOf("--tpl=/tmp/x.xml", "planQry", "json:", "--no-param="),
            replaced,
        )
        assertTrue("未给 paramsJson 时不得出现 null 字面量", replaced.none { it.contains("null") })
    }

    @Test
    fun `替换_给全参数`() {
        val replaced = ExternalTemplateRenderer.substitute(
            listOf("{template}", "{sqlId}", "{paramsJson}"),
            "/p/a.xml",
            "s1",
            """{"planYear":2026}""",
        )
        assertEquals(listOf("/p/a.xml", "s1", """{"planYear":2026}"""), replaced)
    }

    // ------------------------------------------------------------------
    // 进程分支（假 Process 注入）
    // ------------------------------------------------------------------

    @Test
    fun `执行_成功返回stdout去首尾空白`() {
        val fake = FakeProcess(stdout = "  select 1 from dual \n".toByteArray())
        val result = ExternalTemplateRenderer.run(listOf("prog"), null, 10) { _, _ -> fake }
        assertEquals("select 1 from dual", result.sql)
        assertNull(result.error)
        assertEquals(0, result.exitCode ?: -1)
    }

    @Test
    fun `执行_非零退出返回失败_stderr只取首行且截断500字符`() {
        val longFirstLine = "boom " + "x".repeat(900)
        val stderr = "$longFirstLine\nsecond-line-that-must-not-appear\n"
        val fake = FakeProcess(stdout = ByteArray(0), stderr = stderr.toByteArray(), exit = 3)
        val result = ExternalTemplateRenderer.run(listOf("prog"), null, 10) { _, _ -> fake }
        assertNull(result.sql)
        assertEquals(3, result.exitCode ?: -1)
        val error = result.error.orEmpty()
        assertTrue("应带退出码: $error", error.contains("code 3"))
        assertTrue("应带 stderr 首行前缀: $error", error.contains("boom "))
        assertTrue("不得把第二行带进错误文本: $error", !error.contains("second-line-that-must-not-appear"))
        assertEquals(
            "stderr 首行须截断到 500 字符",
            500,
            error.substringAfter("code 3: ").length,
        )
    }

    @Test
    fun `执行_超时强制杀进程并返回超时失败`() {
        val fake = FakeProcess(hang = true)
        val result = ExternalTemplateRenderer.run(listOf("prog"), null, 1) { _, _ -> fake }
        assertNull(result.sql)
        assertTrue("应报超时: ${result.error}", result.error!!.contains("timed out after 1s"))
        assertTrue("超时必须 destroyForcibly", fake.destroyForciblyCalled)
    }

    @Test
    fun `执行_stdout超1MiB截断并标注`() {
        val bytes = ByteArray(ExternalTemplateRenderer.MAX_OUTPUT_BYTES + 4096) { 'a'.code.toByte() }
        val fake = FakeProcess(stdout = bytes)
        val result = ExternalTemplateRenderer.run(listOf("prog"), null, 10) { _, _ -> fake }
        assertNotNull("截断后仍应返回 SQL", result.sql)
        assertTrue("必须标注截断", result.truncated)
        assertEquals(
            "SQL 长度须等于 1 MiB 上限",
            ExternalTemplateRenderer.MAX_OUTPUT_BYTES,
            result.sql!!.length,
        )
    }

    @Test
    fun `执行_空输出是失败而不是空SQL`() {
        val fake = FakeProcess(stdout = "  \n\t ".toByteArray())
        val result = ExternalTemplateRenderer.run(listOf("prog"), null, 10) { _, _ -> fake }
        assertNull(result.sql)
        assertTrue("应报空输出: ${result.error}", result.error!!.contains("no SQL on stdout"))
    }

    @Test
    fun `执行_启动失败返回结构化失败`() {
        val result = ExternalTemplateRenderer.run(listOf("no-such-binary"), null, 10) { _, _ ->
            throw java.io.IOException("cannot run program")
        }
        assertNull(result.sql)
        assertTrue("应报启动失败: ${result.error}", result.error!!.contains("failed to start external renderer"))
    }

    @Test
    fun `执行_空命令直接失败`() {
        val result = ExternalTemplateRenderer.run(emptyList(), null, 10)
        assertNull(result.sql)
        assertTrue("应报空命令: ${result.error}", result.error!!.contains("command is empty"))
    }

    // ------------------------------------------------------------------
    // 真实进程（默认启动路径）
    // ------------------------------------------------------------------

    @Test
    fun `真实进程_echo取回stdout`() {
        assumeTrue("仅在 Windows 与非 Windows 两平台各有一条 echo 路径时运行", SystemInfo.isWindows || java.io.File("/bin/echo").canExecute())
        val command = if (SystemInfo.isWindows) listOf("cmd", "/c", "echo", "select 1")
        else listOf("/bin/echo", "select 1")
        val result = ExternalTemplateRenderer.run(command, null, 30)
        assertNull("真实进程不应失败: ${result.error}", result.error)
        assertTrue("应取回 stdout: ${result.sql}", result.sql.orEmpty().contains("select 1"))
    }

    // ------------------------------------------------------------------
    // Windows argv 引号修正（UI-045-01：走查发现 paramsJson 的双引号被 CRT 吃掉）
    // ------------------------------------------------------------------

    @Test
    fun `Windows_含双引号的参数补引号并转义_不含引号的逐字节不变`() {
        val argv = ExternalTemplateRenderer.argvForLaunch(
            listOf("node", "render.cjs", "{template}", """{"planYear":"2026"}""", "plain"),
            windows = true,
        )
        assertEquals("node", argv[0])
        assertEquals("render.cjs", argv[1])
        assertEquals("不含双引号的参数必须原样（逐字节不变）", "{template}", argv[2])
        assertEquals(
            "含双引号的参数须补外层引号并转义内部引号（否则 CRT 会把引号吃掉）",
            """"{\"planYear\":\"2026\"}"""",
            argv[3],
        )
        assertEquals("plain", argv[4])
    }

    @Test
    fun `POSIX_argv必须原样不动`() {
        val input = listOf("node", "render.cjs", """{"planYear":"2026"}""")
        assertEquals("POSIX 由 execve 直接传 argv，插引号会破坏参数", input, ExternalTemplateRenderer.argvForLaunch(input, windows = false))
    }

    @Test
    fun `Windows_尾随反斜杠与内嵌反斜杠按CRT规则加倍`() {
        val plain = "d\\"   // d 后跟一个反斜杠（不含引号）
        val argv = ExternalTemplateRenderer.argvForLaunch(listOf("""a\"b""", """c\""", plain), windows = true)
        // 输入 a\"b（1 个反斜杠紧邻引号）→ 输出 2N+1=3 个反斜杠再跟引号，整体再包一层引号
        assertEquals("""a\\\"b 需按 CRT 规则转义""", """"a\\\"b"""", argv[0])
        // 不含引号的参数（含尾随反斜杠）一律原样，逐字节不变
        assertEquals("无引号参数不得改动", plain, argv[2])
    }

    @Test
    fun `真实进程_Windows下JSON参数的双引号必须原样到达子进程`() {
        assumeTrue("仅 Windows 需要这条命令行引号契约", SystemInfo.isWindows)
        val node = findNode()
        assumeTrue("本机无 node，跳过（沙盒走查已覆盖同一路径）", node != null)
        val echo = java.io.File.createTempFile("mpvs-argv-echo", ".cjs")
        try {
            echo.writeText("process.stdout.write(JSON.stringify(process.argv.slice(2)))")
            val json = """{"planYear":"2026"}"""
            val result = ExternalTemplateRenderer.run(listOf(node!!, echo.absolutePath, json), null, 30)
            assertNull("真实进程不应失败: ${result.error}", result.error)
            assertEquals("子进程收到的 JSON 必须保留双引号", """["{\"planYear\":\"2026\"}"]""", result.sql)
        } finally {
            echo.delete()
        }
    }

    /** 找本机 node（PATH 里第一个可执行的 node/node.exe）。 */
    private fun findNode(): String? {
        val exts = listOf("", ".exe", ".cmd")
        for (dir in System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)) {
            for (ext in exts) {
                val f = java.io.File(dir, "node$ext")
                if (f.isFile) return f.absolutePath
            }
        }
        return null
    }

    /**
     * 假 [Process]：各流由构造参数给定；[hang] = true 时 `waitFor(timeout)` 直接返回 false
     * （模拟超时），并要求 `destroyForcibly` 被调用。
     *
     * 注：不覆写 `waitFor()` 的无参版本为「立即返回 exit」——生产实现用的是带超时重载。
     */
    private class FakeProcess(
        private val stdout: ByteArray = ByteArray(0),
        private val stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        private val hang: Boolean = false,
    ) : Process() {

        var destroyForciblyCalled = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout)

        override fun getErrorStream(): InputStream = ByteArrayInputStream(stderr)

        override fun waitFor(): Int = exit

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !hang

        override fun exitValue(): Int = exit

        override fun destroy() {
            destroyForciblyCalled = true
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            return this
        }
    }
}
