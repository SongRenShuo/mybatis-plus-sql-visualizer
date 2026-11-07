package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-ConsoleBind263 P0-2（2026-11-07 用户实测）：**请求了 B 源、平台可选集合里没有它**时不得静默跑 A 源。
 *
 * 现场（`idea.1.log:27900-28040`）：工具栏挑 `mpj_demo@localhost`，因 per-file 关联未清掉，
 * runner 枚举被短路成 `runners=1`（只有被钉住的 Oracle 源）→ 旧链落到「single runner, run
 * directly」，会话建在 Oracle 上（`target=.../Oracle`），用户以为切到了 MySQL。
 *
 * 判据收在 [blocksPreferredRunnerMiss]（纯函数，准备链直接用它做分支条件）；纯函数绿证明不了
 * 生产链接上了，故同批带**源码扫描接线守卫**（沿用 [PreviewPreparePickTest] 的范式）。
 */
class DataSourceSelectorPreferredMissTest {

    // ------------------------------------------------------------------
    // 纯函数
    // ------------------------------------------------------------------

    /** ★ 验收主用例：偏好 mpj_demo，可选集合只有被钉住的 Oracle 源 → 必须阻断。 */
    @Test
    fun `偏好源不在 runner 集合里_必须阻断`() {
        assertTrue(
            "偏好源不在 runner 集合里 → 阻断（否则静默跑在被钉住的源上）",
            blocksPreferredRunnerMiss(
                preferredRunnerName = "mpj_demo@localhost",
                runnerNames = listOf("FREEPDB1@103.36.220.245"),
            ),
        )
    }

    /** 反向（不得回归）：偏好命中哪怕是多源集合，也照常放行——一次选择即生效。 */
    @Test
    fun `偏好命中_不阻断`() {
        assertFalse(
            "偏好命中 → 不拦（既有「一次选择即生效」语义不变）",
            blocksPreferredRunnerMiss(
                preferredRunnerName = "mpj_demo@localhost",
                runnerNames = listOf("FREEPDB1@103.36.220.245", "mpj_demo@localhost"),
            ),
        )
    }

    /** 同名多条 = 歧义，不是「找不到」：交弹窗让用户亲手挑，不算静默错源。 */
    @Test
    fun `同名多条_不阻断`() {
        assertFalse(
            "同名多条交给弹窗（歧义形态）",
            blocksPreferredRunnerMiss(
                preferredRunnerName = "mpj_demo@localhost",
                runnerNames = listOf("mpj_demo@localhost", "mpj_demo@localhost"),
            ),
        )
    }

    /** 没有偏好（状态行/更多菜单 → 必弹列表）、空白名、空集合：一律不拦，各由既有分支归因。 */
    @Test
    fun `无偏好与空集合_不阻断`() {
        assertFalse(blocksPreferredRunnerMiss(null, listOf("A")))
        assertFalse(blocksPreferredRunnerMiss("", listOf("A")))
        assertFalse(blocksPreferredRunnerMiss("   ", listOf("A")))
        // 空 runner 集合（真无源/方言不匹配）由 runners.isEmpty() 分支做归因，不在这里拦。
        assertFalse(blocksPreferredRunnerMiss("mpj_demo@localhost", emptyList()))
    }

    // ------------------------------------------------------------------
    // 生产接线守卫（源码扫描）
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

    /** 守卫必须排在「单 runner 直跑」与「弹窗」之前——排后面等于没接（现场正是单 runner 分支吞掉的）。 */
    @Test
    fun `偏好未命中守卫必须先于单runner直跑与弹窗`() {
        val source = selectorSource()
        // 定位**调用点**而不是函数声明（`internal fun blocksPreferredRunnerMiss(` 在文件更早处）。
        val guard = source.indexOf("blocksPreferredRunnerMiss(preferredRunnerName, runners")
        val singleRunner = source.indexOf("runners.size == 1 && shouldBeChosenIfAlone")
        val popup = source.indexOf("showRunnerPopup(project, runners)")
        assertTrue("准备链未接 blocksPreferredRunnerMiss 守卫", guard > 0)
        assertTrue("守卫必须早于「single runner, run directly」分支（否则静默跑被钉住的源）", guard < singleRunner)
        assertTrue("守卫必须早于弹窗分支", guard < popup)
    }

    /** 阻断分支必须收尾：给文案 + 结算单飞门 + 回报失败（否则面板卡「执行中」/下一次执行排队到超时）。 */
    @Test
    fun `阻断分支必须给文案并结算单飞门`() {
        val source = selectorSource()
        val guard = source.indexOf("blocksPreferredRunnerMiss(preferredRunnerName, runners")
        val nextBranch = source.indexOf("preferredRunnerName != null && runners.count", guard)
        val block = source.substring(guard, nextBranch)
        assertTrue("阻断必须给可行动文案", block.contains("execute.datasource.preferredNotFound"))
        assertTrue("阻断必须结算单飞门", block.contains("prepareGate.abort(prepareOwner, ExecutionSubmission.BLOCKED)"))
        assertTrue("阻断必须回报失败", block.contains("callback?.invoke(false)"))
        assertTrue("阻断不得建链", !block.contains("runRunner("))
    }
}
