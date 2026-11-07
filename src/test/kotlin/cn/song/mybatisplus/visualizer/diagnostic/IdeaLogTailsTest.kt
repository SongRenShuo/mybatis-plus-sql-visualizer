package cn.song.mybatisplus.visualizer.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 档 2：`idea.log` 尾部读取测试（testFast 桶，非沙盒）。
 *
 * 守卫目标（对齐计划 §五）：
 *  - 正常尾部：小文件整读，大文件只读尾部窗口（前半段不得出现在结果里）；
 *  - 文件不存在 / 路径是目录 → 降级为 [IdeaLogTails.Tail.Missing]，不抛异常；
 *  - **截断落在多字节字符中间**：跳过被截断的首行，解码结果不含替换字符（U+FFFD）；
 *  - **超长单行**（整窗没有换行）：不空手而归，也不抛异常；
 *  - 大文件下尾部读取耗时可控（计划 §四-4：预期 <10ms，这里留足余量只钉「不是几分钟」）。
 */
class IdeaLogTailsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, content: String): java.nio.file.Path {
        val file = tmp.newFile(name)
        Files.write(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
        return file.toPath()
    }

    private fun ok(tail: IdeaLogTails.Tail): IdeaLogTails.Tail.Ok {
        assertTrue("应读到尾部，实际 $tail", tail is IdeaLogTails.Tail.Ok)
        return tail as IdeaLogTails.Tail.Ok
    }

    @Test
    fun test文件不存在降级为Missing() {
        val missing = tmp.root.toPath().resolve("no-such-idea.log")
        assertTrue(IdeaLogTails.readTail(missing) is IdeaLogTails.Tail.Missing)
    }

    @Test
    fun test路径是目录也降级为Missing() {
        val dir = tmp.newFolder("logs")
        assertTrue(IdeaLogTails.readTail(dir.toPath()) is IdeaLogTails.Tail.Missing)
    }

    @Test
    fun test空文件返回空文本() {
        val path = write("empty.log", "")
        val read = ok(IdeaLogTails.readTail(path))
        assertEquals("", read.text)
        assertEquals(0L, read.bytes)
    }

    @Test
    fun test文件小于窗口时整读且不丢首行() {
        val content = "line-1\nline-2\nline-3\n"
        val read = ok(IdeaLogTails.readTail(write("small.log", content)))
        assertEquals("整文件读取时首行是完整的，不得跳过", content, read.text)
        assertEquals(content.toByteArray(StandardCharsets.UTF_8).size.toLong(), read.bytes)
    }

    @Test
    fun test只读尾部窗口且前半段不出现() {
        val head = "HEAD-MARKER-" + "a".repeat(4096) + "\n"
        val tail = "TAIL-MARKER-first\nTAIL-MARKER-second\n"
        val path = write("big.log", head + tail)
        val read = ok(IdeaLogTails.readTail(path, maxBytes = 256))
        assertFalse("窗口外的前半段不得出现：${read.text}", read.text.contains("HEAD-MARKER"))
        assertTrue("尾部内容必须在：${read.text}", read.text.contains("TAIL-MARKER-second"))
        assertTrue("bytes 应等于实际参与判定的字节数（略小于窗口）", read.bytes in 1L..256L)
    }

    @Test
    fun test截断落在多字节字符中间时跳过半行且无替换字符() {
        // 每行「中文第一行内容\n」= 7 个 3 字节汉字 + 1 个换行 = 22 字节。
        val content = "HEADER-LINE-" + "x".repeat(50) + "\n" + "中文第一行内容\n".repeat(3)
        val path = write("cjk.log", content)
        // 30 字节窗口的起点落在倒数第二行的第 5 个汉字中间（14 = 4 个整字 + 2 字节残片）。
        val read = ok(IdeaLogTails.readTail(path, maxBytes = 30))
        assertEquals("跳过被截断的首行后只应剩最后一行", "中文第一行内容\n", read.text)
        assertFalse("不得出现 UTF-8 替换字符（说明多字节截断未被处理）: ${read.text}", read.text.contains('\uFFFD'))
    }

    @Test
    fun test超长单行没有换行时不空手而归() {
        val path = write("oneline.log", "X".repeat(5000))
        val read = ok(IdeaLogTails.readTail(path, maxBytes = 1000))
        assertEquals("保留窗口内全部内容", 1000, read.text.length)
        assertFalse("不得含替换字符", read.text.contains('\uFFFD'))
    }

    @Test
    fun test大文件尾部读取耗时可接受() {
        val sb = StringBuilder()
        repeat(80_000) { sb.append("2026-11-04 12:00:00,000 [  1] INFO - padding line ").append(it).append('\n') }
        sb.append("2026-11-04 12:00:09,999 [  1] ERROR - Unable to resolve table 'orders'\n")
        val path = write("large.log", sb.toString())
        val startedNs = System.nanoTime()
        val read = ok(IdeaLogTails.readTail(path))
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        assertTrue("尾部必须含最后一行：${read.text.takeLast(80)}", read.text.contains("Unable to resolve table"))
        assertFalse("不得把整个文件都读进来", read.text.contains("padding line 0\n"))
        assertTrue("尾部 256KB 读取耗时应在百毫秒级（实测 ${elapsedMs}ms）", elapsedMs < 2_000)
        println("[IdeaLogTailsTest] 读取 ${read.bytes} 字节尾部耗时 ${elapsedMs}ms")
    }
}
