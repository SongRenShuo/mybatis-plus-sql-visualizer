package cn.song.mybatisplus.visualizer.log

import com.intellij.execution.filters.Filter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * T-SqlListCatchup 券 G2：重放守卫大额丢弃的分流核验（真机 2026-11-07 读数「重放守卫已丢弃
 * 138,243 行 + 控制台 产出 0 条」的定性复现实验）。
 *
 * 经真实桥链路（[SqlLogConsoleBridge.attachFilter] 挂出的 filter 代理 → handleConsoleLine →
 * 守卫判定 → 丢弃计数/条目产出）构造两类输入，断言守卫只丢真重放：
 *
 * - **真重放**（rehighlight 全文档重跑：文档与 stamp 都未变、同 endOffset 重喂）→ 全部判
 *   Replay 丢弃，条目零重复产出（平台契约要求的幂等，无害）；
 * - **流式新行**（console.clear() 后的重印：243 平台实证 `doClear` 是同一文档实例
 *   `deleteString(0, textLength)`——偏移从头重计、stamp 变、反射句柄持续有效）→ 修复前
 *   旧判据把重印首行（必然等于行 0）连同后续新行判成 Replay 丢到旧水位之外＝真丢数据，
 *   修复后（券 G2 文档缩水分支）全部 Process、控制台通道恢复产出。
 *
 * 反向验证（S2）：摘掉 [ConsoleReplayGuard.decide] 的文档缩水分支重跑，clear 用例必红
 * （重印行全部计 Replay、产出 0 条）；真重放用例在旧机制下同样成立保持绿（语义不受修复影响）。
 */
class ReplayGuardStormVerdictTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
    }

    override fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        super.tearDown()
    }

    // ---------- 反射面桩：方法名对齐 attachReplayGuard 的反射解析面 ----------

    private class StubDoc {
        var text: String = ""
        var stamp: Long = 1L

        @Suppress("unused")
        fun getModificationStamp(): Long = stamp

        @Suppress("unused")
        fun getCharsSequence(): CharSequence = text
    }

    private class StubEditor(private val doc: StubDoc) {
        @Suppress("unused")
        fun getDocument(): Any? = doc
    }

    private class StubConsole(private val editor: StubEditor) {
        val attached = mutableListOf<Any>()
        fun addMessageFilter(filter: Filter) {
            attached += filter
        }

        @Suppress("unused")
        fun getEditor(): Any? = editor
    }

    private fun newArmedFilter(): Triple<SqlLogConsoleBridge, Filter, StubDoc> {
        val bridge = SqlLogConsoleBridge(SqlLogEntryBuffer())
        val doc = StubDoc()
        val console = StubConsole(StubEditor(doc))
        bridge.attachFilter(console)
        val filter = bridge.attachedConsoles[console]!!.first as Filter
        return Triple(bridge, filter, doc)
    }

    /** 行文本照抄真机现场（Spring Boot 默认日志格式 + JdbcTemplate 语句行）。
     *  线程名逐语句错开（解析器按线程分桶、同桶后语覆盖前语——ConsoleSettleFlushTest 同款口径）。 */
    private fun stmt(sql: String, thread: String = "nio-8086-exec-1"): String =
        "2026-11-07 15:36:09.000 DEBUG 72660 --- [$thread] o.s.jdbc.core.JdbcTemplate               : " +
            "Executing prepared SQL statement [$sql]"

    /** 流式打印：逐行追加进桩文档并按平台口径喂 filter（entireLength = 行尾偏移，单调递增）。 */
    private fun feedStream(filter: Filter, doc: StubDoc, lines: List<String>) {
        var end = doc.text.length
        for (l in lines) {
            doc.text += l + "\n"
            end += l.length
            filter.applyFilter(l, end)
            end += 1 // 行分隔符占位（下一行起点）
        }
    }

    /** rehighlight 重放：文档与 stamp 都不变，同 endOffset 重喂（全文档从行 0 重跑 filter）。 */
    private fun feedReplay(filter: Filter, doc: StubDoc, lines: List<String>) {
        var end = 0
        for (l in lines) {
            end += l.length
            filter.applyFilter(l, end)
            end += 1
        }
    }

    private fun settle(bridge: SqlLogConsoleBridge): Int =
        bridge.flushSettledEntries(System.currentTimeMillis() + 60_000)

    // ---------- 真重放：按设计丢弃（无害） ----------

    fun test真重放_全文档重跑被守卫丢弃且不重复产出() {
        val (bridge, filter, doc) = newArmedFilter()
        val lines = listOf(stmt("SELECT a FROM t1", "exec-1"), stmt("SELECT b FROM t2", "exec-2"))

        feedStream(filter, doc, lines)
        assertEquals("流式两条语句正常产出", 2, settle(bridge))

        feedReplay(filter, doc, lines)
        assertEquals("重放行全部计丢弃（守卫按设计工作）", lines.size.toLong(), bridge.replayDropCount)
        assertEquals("重放不得重复产出条目", 0, settle(bridge))
    }

    // ---------- 流式新行：clear 后重印不得判 Replay（券 G2 缺陷修复） ----------

    fun testclear后重印_流式新行不判Replay且回到产出() {
        val (bridge, filter, doc) = newArmedFilter()
        val lines1 = listOf(stmt("SELECT a FROM t1", "exec-1"), stmt("SELECT b FROM t2", "exec-2"))
        feedStream(filter, doc, lines1)
        settle(bridge)

        // console.clear()（243 平台实证：同一文档实例 deleteString(0, textLength)，stamp 变）
        doc.text = ""
        doc.stamp = 2L

        // clear 后重印两条新语句：新行偏移远小于旧水位，修复前全部判 Replay（丢数据）
        val lines2 = listOf(stmt("SELECT c FROM t3", "exec-1"), stmt("SELECT d FROM t4", "exec-2"))
        feedStream(filter, doc, lines2)
        assertEquals("clear 后重印的流式新行不得计 Replay", 0L, bridge.replayDropCount)
        assertEquals("clear 后控制台通道必须恢复产出", 2, settle(bridge))
    }
}
