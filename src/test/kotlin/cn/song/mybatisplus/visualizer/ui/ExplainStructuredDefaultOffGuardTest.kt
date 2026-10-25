package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-ExplainConsoleFirst W6（2026-10-24 用户实测「为什么我点击执行计划还是弹窗了」）：
 * 结构化 EXPLAIN 通道**保留，但默认必须关闭**，且「启用结构化并重试」推荐通知**只提示一次**。
 *
 * 事故链（沙盒实证）：用户点执行计划看到弹窗 → 查持久化配置
 * `config/options/mp-sql-visualizer.xml` 得 `<option name="explainStructuredRender" value="true" />`
 * → 代码默认值是 false，差异只可能来自通知里的「启用结构化并重试」动作被顺手点过
 * （[cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelExecution.enableStructuredExplainAndRetry]
 * 会把该字段永久写成 true）→ 而界面上再没有任何「现在是实验形态」的提示，用户下次看到弹窗
 * 完全无从归因。
 *
 * 本守卫钉两件事（源码扫描，照 ExplainPanelWiringGuardTest 先例）：
 *  1. `explainStructuredRender` 的声明默认值必须是 `false`（产品决定：保留通道、默认关）；
 *  2. 推荐通知受 `explainStructuredOfferedShown` 一次性标记约束（不得无条件每次弹）。
 */
class ExplainStructuredDefaultOffGuardTest {

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
    fun `结构化渲染默认必须关闭`() {
        val settings = read("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt")
        val decl = Regex("var explainStructuredRender: Boolean = (\\w+)").find(settings)
        assertTrue("必须能找到 explainStructuredRender 的声明（找不到说明字段被重命名/搬迁，守卫已失效）", decl != null)
        assertFalse(
            "explainStructuredRender 默认值必须是 false（产品决定 2026-10-24：保留通道、默认走原生控制台）；" +
                "当前实际为 ${decl!!.groupValues[1]}",
            decl.groupValues[1] == "true",
        )
    }

    @Test
    fun `启用结构化推荐通知必须只提示一次`() {
        val execution = read("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt")
        assertTrue(
            "推荐通知的条件必须含 explainStructuredOfferedShown 一次性标记（否则每次点执行计划都弹）",
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
     * 护栏的另一半：设置页开关必须仍在（用户想开时仍有显式入口）。
     * 「默认关」不等于「拆掉通道」——两者产品语义不同，别在清理时误删。
     */
    @Test
    fun `设置页仍保留显式开关`() {
        val sections = read("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanelSections.kt")
        assertTrue(
            "设置页必须仍挂着 explainStructuredCheck（默认关 ≠ 拆掉通道）",
            sections.contains("explainStructuredCheck"),
        )
        assertTrue(
            "开关标签走 bundle 键 settings.explain.structured（不得硬编码中文）",
            sections.contains("settings.explain.structured"),
        )
    }
}
