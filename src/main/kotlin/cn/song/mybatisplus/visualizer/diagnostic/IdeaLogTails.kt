package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.application.PathManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 档 2：IDE 日志尾部读取（平台依赖集中在此，判定全在 [KnownIssueCatalog]）。
 *
 * 只做一件事：把 `idea.log` **最后 [TAIL_BYTES] 字节**读成一段文本，供已知问题表匹配。
 *
 * 四条硬约束：
 *  - **只读当前文件**：不碰 `idea.log.1` 等轮转文件——跨会话/上一进程的异常会污染本次诊断；
 *  - **跳掉可能被截断的首行**：按字节 seek 会落在行中间（也可能落在某个多字节字符中间），
 *    从该位置找第一个 `\n`、**其后**才开始解码，半行与半个汉字一起丢掉；
 *  - **单次读取**：一次 `seek` + 一次 `readFully`，不在 IDE 正写日志时反复读；
 *  - **任何读取失败都降级**（[Tail.Missing]/[Tail.Failed]），绝不让体检单因为读日志而抛异常。
 *
 * 隐私：[Tail.Ok.text] 只用于匹配，报告里绝不落地；[Tail.Failed] 只保留异常**类名**——
 * 异常 message 里常带绝对路径，进报告就违反隐私边界。
 */
internal object IdeaLogTails {

    /** 尾部窗口（256 KB）：足够覆盖近期故障记录，又能稳定在毫秒级读完。 */
    const val TAIL_BYTES = 256 * 1024

    const val LOG_FILE_NAME = "idea.log"

    /** 读取结果三态（缺失/失败都是降级，由调用方映射成 UNAVAILABLE）。 */
    sealed class Tail {
        /**
         * 读到一段尾部文本。
         *
         * [text] 已去掉被截断的首行；[bytes] 为实际参与判定的字节数（用于报告里的「尾部 N KB」）。
         */
        data class Ok(val text: String, val bytes: Long) : Tail()

        /** 文件不存在：日志被策略关闭，或日志目录不可读。 */
        object Missing : Tail()

        /** 读取失败；[errorType] 只是异常类名（不含 message，避免路径泄漏）。 */
        data class Failed(val errorType: String) : Tail()
    }

    /** 当前 IDE 日志文件路径（`PathManager.getLogPath()` + `idea.log`）。 */
    fun logFilePath(): Path = Paths.get(PathManager.getLogPath(), LOG_FILE_NAME)

    /**
     * 读 [path] 的最后 [maxBytes] 字节。
     *
     * @param path     日志文件（默认走 [logFilePath]；测试注入临时文件）
     * @param maxBytes 窗口大小；文件更短时读全文（此时没有半行可跳）
     */
    fun readTail(path: Path = logFilePath(), maxBytes: Int = TAIL_BYTES): Tail {
        val file: File = path.toFile()
        return try {
            // isFile：目录同名/不存在都按「缺失」降级，别让 RandomAccessFile 抛 FileNotFoundException。
            if (!file.isFile) return Tail.Missing
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length <= 0L) return Tail.Ok("", 0L)
                val take = minOf(length, maxBytes.coerceAtLeast(1).toLong())
                val start = length - take
                raf.seek(start)
                val window = ByteArray(take.toInt())
                raf.readFully(window)
                // 只有真正截断过才跳首行；整文件读完时第一行就是完整的。
                val offset = if (start == 0L) 0 else firstNewlineOffset(window)
                val text = String(window, offset, window.size - offset, StandardCharsets.UTF_8)
                Tail.Ok(text, (window.size - offset).toLong())
            }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e
            Tail.Failed(e.javaClass.simpleName.ifBlank { e.javaClass.name })
        }
    }

    /**
     * 窗口内第一个 `\n` 之后的位置；整窗没有换行（超长单行）时返回 0。
     *
     * 超长单行宁可保留半行也不空手而归：单行特征仍在窗口尾部，能匹配到就比什么都不报强。
     */
    private fun firstNewlineOffset(window: ByteArray): Int {
        for (i in window.indices) {
            if (window[i] == '\n'.code.toByte()) return i + 1
        }
        return 0
    }
}
