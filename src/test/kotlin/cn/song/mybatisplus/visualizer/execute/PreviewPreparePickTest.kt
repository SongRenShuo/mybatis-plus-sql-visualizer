package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI-003：预览默认准备的数据源选择分派。
 *
 * 现场：打开 XML 预览即弹「选择数据源」模态列表（7 个源），面板右上仍「未绑定数据源」。
 * 根因是 `chooseAndPrepare` 的 else 分支只按「多源 → 弹窗」处置，不区分预览准备与显式执行。
 *
 * 判定面：
 *  - 纯函数表驱动：预览准备 → 不弹；显式执行 / 切换数据源 → 仍弹；
 *  - 生产接线守卫（源码扫描）：else 分支必须先过 [PreviewPreparePick.shouldPopup]，
 *    且 `showRunnerPopup` 只允许出现在该判据之后（不能退回无条件弹窗）。
 *
 * master 侧只实施 A 方案（不弹窗不建链）；dev 的「学习绑定静默选源」依赖 ExecBindFallback，
 * master 无该文件，故 `shouldPopup` 只有两种结局。
 */
class PreviewPreparePickTest {

    // ------------------------------------------------------------------
    // 纯函数
    // ------------------------------------------------------------------

    @Test
    fun `预览默认准备多源_不弹选择器`() {
        assertFalse(PreviewPreparePick.shouldPopup(executeAfterPrepare = false, forceChoose = false, forceChoosePopup = false))
    }

    @Test
    fun `显式执行_仍弹选择器`() {
        assertTrue(PreviewPreparePick.shouldPopup(executeAfterPrepare = true, forceChoose = false, forceChoosePopup = false))
    }

    @Test
    fun `切换数据源_forceChoose_仍弹选择器`() {
        assertTrue(PreviewPreparePick.shouldPopup(executeAfterPrepare = false, forceChoose = true, forceChoosePopup = false))
    }

    @Test
    fun `切换入口要求弹列表_forceChoosePopup_仍弹选择器`() {
        assertTrue(PreviewPreparePick.shouldPopup(executeAfterPrepare = false, forceChoose = false, forceChoosePopup = true))
    }

    /** 预览准备下 forceChoose 与 forceChoosePopup 同时为真也只是「弹」——不得因组合而回落静默。 */
    @Test
    fun `预览准备_两个切换标志同时在场_仍弹选择器`() {
        assertTrue(PreviewPreparePick.shouldPopup(executeAfterPrepare = false, forceChoose = true, forceChoosePopup = true))
    }

    // ------------------------------------------------------------------
    // 生产接线守卫（源码扫描）——纯函数绿证明不了生产链真的接上了
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    private fun selectorSource(): String = File(
        repoRoot(),
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt",
    ).readText()

    @Test
    fun `else 分支必须先过 shouldPopup 判据`() {
        val source = selectorSource()
        val decide = source.indexOf("PreviewPreparePick.shouldPopup(")
        val popupCall = source.indexOf("showRunnerPopup(project, runners)")
        assertTrue("else 分支未接 PreviewPreparePick.shouldPopup（预览会照旧弹窗）", decide > 0)
        assertTrue("showRunnerPopup 必须在 shouldPopup 判据之后", popupCall > decide)
    }

    @Test
    fun `静默兜底必须结算单飞门`() {
        val source = selectorSource()
        val decide = source.indexOf("PreviewPreparePick.shouldPopup(")
        val popupCall = source.indexOf("showRunnerPopup(project, runners)")
        val noopBlock = source.substring(decide, popupCall)
        assertTrue(
            "静默兜底未结算单飞门（下一次执行会排队到 60s 超时）",
            noopBlock.contains("prepareGate.finishPrepare(prepareOwner)"),
        )
        assertTrue("静默兜底必须回报 callback(false)", noopBlock.contains("callback?.invoke(false)"))
    }

    @Test
    fun `多源弹窗调用点应唯一`() {
        val occurrences = Regex("""showRunnerPopup\(project, runners""").findAll(selectorSource()).count()
        assertEquals("多源弹窗调用点应唯一（预览默认准备不得再走它）", 1, occurrences)
    }
}
