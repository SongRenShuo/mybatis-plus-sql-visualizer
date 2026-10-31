package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R45（EXPLAIN 执行计划体检，转正）：结构化 EXPLAIN 通道从「默认关、保留一个版本周期观察」
 * 转为**正式能力、默认开**。
 *
 * 沿革：T-ExplainConsoleFirst W6（2026-10-24）因「点执行计划却弹结构化弹窗、用户无从归因」
 * 立过一条「默认必须关」的守卫（本文件前身 ExplainStructuredDefaultOffGuardTest）——当时
 * 产品决定是保留通道、默认走原生控制台，并把「摘除」留到下个版本再定。R45 定案：观察期已过，
 * 通道转正为默认能力；用户的显式关闭仍然有效（持久化值优先），设置页开关保留。
 *
 * 本守卫钉三件事（源码扫描，照 ExplainPanelWiringGuardTest 先例）：
 *  1. `explainStructuredRender` 的声明默认值必须是 `true`（转正后不得再退回默认关）；
 *  2. 「启用结构化并重试」推荐通知仍受 `explainStructuredOfferedShown` 一次性标记约束
 *     （用户显式关掉后，推荐最多出现一次——这是 W6 事故的直接教训，转正不撤销该护栏）；
 *  3. 设置页仍保留显式开关（默认开 ≠ 取消用户的关闭权）。
 */
class ExplainStructuredDefaultOnGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录")
    }

    private fun read(path: String): String = File(repoRoot(), path).readText()

    @Test
    fun `结构化渲染默认必须开启`() {
        val settings = read("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt")
        val decl = Regex("var explainStructuredRender: Boolean = (\\w+)").find(settings)
        assertTrue("必须能找到 explainStructuredRender 的声明（找不到说明字段被重命名/搬迁，守卫已失效）", decl != null)
        assertTrue(
            "explainStructuredRender 默认值必须是 true（R45 定案：观察期结束、转正为正式能力默认开）；" +
                "当前实际为 ${decl!!.groupValues[1]}",
            decl.groupValues[1] == "true",
        )
    }

    @Test
    fun `启用结构化推荐通知仍只提示一次`() {
        val execution = read("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt")
        assertTrue(
            "推荐通知的条件必须含 explainStructuredOfferedShown 一次性标记（否则用户关掉后每次点执行计划都弹）",
            execution.contains("explainStructuredOfferedShown"),
        )
        // 标记必须真的被置位，否则「一次性」只是读了个恒 false 的字段（假一次性）
        assertTrue(
            "拿到推荐资格后必须置位 explainStructuredOfferedShown（否则永远重复弹）",
            Regex("settings\\.explainStructuredOfferedShown = true").containsMatchIn(execution),
        )
        val settings = read("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt")
        assertTrue(
            "一次性标记必须是持久化设置字段（重开 IDE 后仍生效），默认 false",
            Regex("var explainStructuredOfferedShown: Boolean = false").containsMatchIn(settings),
        )
    }

    /**
     * 护栏的另一半：设置页开关必须仍在（用户想关时仍有显式入口）。
     * 「默认开」不等于「拆掉关闭权」——两者产品语义不同，别在转正时误删。
     */
    @Test
    fun `设置页仍保留显式开关`() {
        val sections = read("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanelSections.kt")
        assertTrue(
            "设置页必须仍挂着 explainStructuredCheck（默认开 ≠ 拆掉关闭权）",
            sections.contains("explainStructuredCheck"),
        )
        assertTrue(
            "开关标签走 bundle 键 settings.explain.structured（不得硬编码中文）",
            sections.contains("settings.explain.structured"),
        )
    }
}
