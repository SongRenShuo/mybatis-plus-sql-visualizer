package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.standalone.SqlStandalonePreviewPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import javax.swing.KeyStroke

/**
 * 空态提示与错误信息可见性测试（T-StandaloneHintAndWarning）。
 *
 * 覆盖 2026-11-18 两条用户报障的**可测面**：
 *  1. 工作台空态提示不再沿用「选中代码按 Alt+Shift+Q」那套编辑器语义（`emptyHint` 覆盖生效）；
 *  2. 警告横幅的 tooltip 是**完整文案**（横幅正文单行必被裁，此前调用点漏传第三参 → tooltip 恒 null）；
 *  3. 工作台把 Alt+Shift+Q 接管为「解析预览」（键位定义正确）。
 *
 * 真机键盘事件与 tooltip 显示时机不在本类断言范围（not run，交 GUI 验收）。
 */
class WarningVisibilityTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    /** 1a：显式传入的 emptyHint 覆盖默认键文案（工作台依赖这条）。 */
    fun testEmptyHintOverrideWins() {
        val panel = SqlPreviewPanel(project, emptyHint = "工作台专用提示")
        try {
            assertEquals("工作台专用提示", panel.previewEmptyHintForTest())
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /** 1b：不传时沿用既有键（弹窗/编辑器链行为零变化）。 */
    fun testEmptyHintDefaultsToBundle() {
        val panel = SqlPreviewPanel(project)
        try {
            assertEquals(
                MpSqlVisualizerBundle.message("panel.preview.emptyHint"),
                panel.previewEmptyHintForTest(),
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /** 2：横幅正文被裁时，tooltip 必须是完整 warning。 */
    fun testBannerTooltipCarriesFullWarning() {
        val longWarning = "找不到 <include refid=\"userColumns\"> 对应的 <sql> 片段（原文不保留，语句内容可能不完整）；" +
            "请检查该 mapper 文件是否包含同名 <sql id=\"userColumns\"> 定义，或该片段是否被拆到了另一个文件里。"
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(
                PreviewResult(
                    sql = "SELECT 1",
                    compactSql = "SELECT 1",
                    unresolvedParams = emptyList(),
                    paramTable = null,
                    wrapperType = null,
                    tableName = null,
                    warning = longWarning,
                ),
            )
            waitForEdt()
            val tooltip = panel.degradationBannerForTest().tooltipTextForTest()
            assertNotNull("警告横幅必须有 tooltip（否则正文被裁后用户无路可看全文）", tooltip)
            assertTrue("tooltip 必须含完整 warning 正文：$tooltip", tooltip!!.contains(longWarning))
            assertEquals(
                "tooltip 与可见正文应同源（同一份 bannerText）",
                panel.degradationBannerForTest().textForTest(),
                tooltip,
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /** 3：工作台重新解析键位 = Alt+Shift+Q（非 mac；mac 走 meta shift Q）。 */
    fun testReparseShortcutIsAltShiftQ() {
        val shortcuts = SqlStandalonePreviewPanel.reparseShortcuts()
        assertEquals(1, shortcuts.size)
        val keyStroke = (shortcuts[0] as com.intellij.openapi.actionSystem.KeyboardShortcut).firstKeyStroke
        val expected = if (com.intellij.openapi.util.SystemInfo.isMac) {
            KeyStroke.getKeyStroke("meta shift Q")
        } else {
            KeyStroke.getKeyStroke("alt shift Q")
        }
        assertEquals("重新解析键位必须与 plugin.xml 的 ConvertToSql 同源", expected, keyStroke)
    }
}
