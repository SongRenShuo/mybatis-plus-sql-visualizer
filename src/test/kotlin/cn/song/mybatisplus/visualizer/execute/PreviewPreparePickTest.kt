package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
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
 *  - 纯函数表驱动：预览 + 多源 + 唯一目标 → 静默；预览 + 歧义/未命中 → 不弹窗不建链；
 *    显式执行 / 切换数据源 → 仍弹窗；
 *  - 生产接线守卫（源码扫描）：else 分支必须经 [PreviewPreparePick.decide]，且
 *    `showRunnerPopup` 只出现在 Popup 分支内（不能退回无条件弹窗）。
 *
 * 纯逻辑层零平台依赖，可进 testFast 快桶。
 */
class PreviewPreparePickTest {

    private val runners = listOf("alpha@localhost", "beta@localhost", "gamma@localhost")

    private fun decide(
        executeAfterPrepare: Boolean = false,
        forceChoose: Boolean = false,
        forceChoosePopup: Boolean = false,
        fallback: String? = null,
        names: List<String> = runners,
    ) = PreviewPreparePick.decide(
        executeAfterPrepare = executeAfterPrepare,
        forceChoose = forceChoose,
        forceChoosePopup = forceChoosePopup,
        fallbackTargetName = fallback,
        runnerNames = names,
    )

    // ------------------------------------------------------------------
    // 纯函数：预览默认准备
    // ------------------------------------------------------------------

    @Test
    fun `预览多源_学习绑定或单源唯一命中_静默建链`() {
        assertEquals(
            PreviewPreparePick.Decision.Silent("beta@localhost"),
            decide(fallback = "beta@localhost"),
        )
    }

    @Test
    fun `预览多源_目标不唯一_不弹窗不建链`() {
        assertEquals(PreviewPreparePick.Decision.SilentNoop, decide(fallback = null))
    }

    @Test
    fun `预览多源_目标名不在候选内_不弹窗不建链`() {
        assertEquals(
            PreviewPreparePick.Decision.SilentNoop,
            decide(fallback = "not-registered@localhost"),
        )
    }

    @Test
    fun `预览_候选内同名多条_判歧义不猜`() {
        assertEquals(
            PreviewPreparePick.Decision.SilentNoop,
            decide(
                fallback = "dup@localhost",
                names = listOf("dup@localhost", "dup@localhost", "other@localhost"),
            ),
        )
    }

    @Test
    fun `预览_空白目标名视为未命中`() {
        assertEquals(PreviewPreparePick.Decision.SilentNoop, decide(fallback = "   "))
    }

    @Test
    fun `预览单源且名字命中_静默建链`() {
        assertEquals(
            PreviewPreparePick.Decision.Silent("only@localhost"),
            decide(fallback = "only@localhost", names = listOf("only@localhost")),
        )
    }

    // ------------------------------------------------------------------
    // 纯函数：主动动作仍弹窗（不得被静默化）
    // ------------------------------------------------------------------

    @Test
    fun `显式执行多源_仍弹选择器`() {
        assertEquals(
            PreviewPreparePick.Decision.Popup,
            decide(executeAfterPrepare = true, fallback = "beta@localhost"),
        )
    }

    @Test
    fun `显式执行_目标不唯一也弹选择器`() {
        assertEquals(PreviewPreparePick.Decision.Popup, decide(executeAfterPrepare = true))
    }

    @Test
    fun `切换数据源_forceChoose_仍弹选择器`() {
        assertEquals(
            PreviewPreparePick.Decision.Popup,
            decide(forceChoose = true, fallback = "beta@localhost"),
        )
    }

    @Test
    fun `切换数据源_forceChoosePopup_即便单源也弹选择器`() {
        assertEquals(
            PreviewPreparePick.Decision.Popup,
            decide(forceChoosePopup = true, fallback = "only@localhost", names = listOf("only@localhost")),
        )
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

    private fun selectorSource(): String =
        File(
            repoRoot(),
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt",
        ).readText()

    @Test
    fun `else 分支必须经纯函数分派`() {
        val source = selectorSource()
        val decide = source.indexOf("PreviewPreparePick.decide(")
        val popupBranch = source.indexOf("PreviewPreparePick.Decision.Popup ->")
        val silentBranch = source.indexOf("is PreviewPreparePick.Decision.Silent ->")
        val noopBranch = source.indexOf("PreviewPreparePick.Decision.SilentNoop ->")
        assertTrue("else 分支未接 PreviewPreparePick.decide（预览会照旧弹窗）", decide > 0)
        assertTrue("缺静默建链分支", silentBranch > decide)
        assertTrue("缺静默不建链分支（方案 A 兜底）", noopBranch > decide)
        assertTrue("缺弹窗分支", popupBranch > decide)
    }

    @Test
    fun `showRunnerPopup 只允许出现在 Popup 分支内`() {
        val source = selectorSource()
        val popupBranch = source.indexOf("PreviewPreparePick.Decision.Popup ->")
        val popupCall = source.indexOf("showRunnerPopup(project, runners)")
        assertTrue("选择器弹窗必须收在 Popup 分支里（否则预览准备会无条件弹窗）",
            popupBranch > 0 && popupCall > popupBranch)
        // 全仓只应有一处多源弹窗调用：预览默认准备不得再走它。
        val occurrences = Regex("""showRunnerPopup\(project, runners""").findAll(source).count()
        assertEquals("多源弹窗调用点应唯一（收口在 Popup 分支）", 1, occurrences)
    }
}
