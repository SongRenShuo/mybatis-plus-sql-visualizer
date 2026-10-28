package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 券03（T-PerfSweep27-03）静检守卫：两处机械改动的结构钉。
 *
 * 行为级断言不可行（LOG 为平台静态门面无法注入；关闸 resolve 零调用需全 PSI 夹具，
 * 收益不抵成本），故按仓内 SettingsTruthTest 的 `File("src/main/kotlin/$rel")` 先例做
 * 源码结构静检——断言被破坏即说明优化回退（探针态两断言均红，读数见计划实施记录）。
 */
class PerfSweep27StaticGuardTest {

    private fun mainSrc(rel: String) = File("src/main/kotlin/$rel").readText()

    /**
     * 券03-1：addCompletions 的调试读取/插值必须包在 isDebugEnabled 门控内。
     * 断言 = 门控块存在，且 editor/position 声明（原门控外的急切读取）落在门控行之后。
     */
    @Test
    fun test补全调试日志已门控() {
        val src = mainSrc("cn/song/mybatisplus/visualizer/ui/highlight/SqlCompletionContributor.kt")
        val gate = src.indexOf("if (LOG.isDebugEnabled) {")
        val eagerRead = src.indexOf("val editor = parameters.editor")
        assertTrue(
            "addCompletions 应存在 isDebugEnabled 块门控（T-PerfSweep27-03）",
            gate >= 0,
        )
        assertTrue(
            "editor 等急切读取应移入门控块内（门控行 $gate 应先于声明行 $eagerRead）",
            gate in 0 until eagerRead,
        )
    }

    /**
     * 券03-2：HqlGutter 开关判定必须先于 hasStringFirstArgument（其变量分支含 resolve）。
     */
    @Test
    fun testHqlGutter开关判定前置() {
        val src = mainSrc("cn/song/mybatisplus/visualizer/ui/gutter/HqlGutterLineMarkerProvider.kt")
        val switchIdx = src.indexOf("!gutterState.showGutterIcon")
        val resolveIdx = src.indexOf("hasStringFirstArgument(grandParent)")
        assertTrue("开关判定缺失（showGutterIcon 检查应存在）", switchIdx >= 0)
        assertTrue(
            "开关判定（$switchIdx）应先于 hasStringFirstArgument（$resolveIdx）——总闸关闭时不得付 resolve",
            switchIdx < resolveIdx,
        )
    }
}
