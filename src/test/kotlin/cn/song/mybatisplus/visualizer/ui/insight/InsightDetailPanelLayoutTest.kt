package cn.song.mybatisplus.visualizer.ui.insight

import junit.framework.TestCase

/**
 * UI-001 回归守卫（2026-10-31 GUI 走查发现）：洞察面板操作行在**窄宽度**下必须报出多行高度。
 *
 * 缺陷形态：原生 `FlowLayout` 的 `preferredSize` 按单行估算 → 外层 `BorderLayout.NORTH` 高度偏小 →
 * 第二行按钮被状态文案压住，屏幕不可见且点不动（4.5 的「反查对象引用/打开选中引用/取消/关闭分析」
 * 四个按钮、4.8 的第三个勾选实测如此，robot 有 bounds 但合成点击无效）。
 *
 * 三条判据各自钉一个方向：窄宽度必须多行、宽宽度必须单行（防过度撑高）、宽度未知时不得抛异常。
 */
class InsightDetailPanelLayoutTest : TestCase() {

    private fun panelWithFourButtons(): InsightDetailPanel =
        InsightDetailPanel(listOf("对象", "角色", "位置"), "notice").apply {
            repeat(4) { i -> button("反查对象引用$i") {} }
        }

    private fun singleButtonHeight(panel: InsightDetailPanel): Int =
        panel.actions.components.first().preferredSize.height

    /** 窄宽度：四个长标签按钮必须排成两行以上，高度至少 1.5 倍单行高。 */
    fun test窄宽度下操作行报多行高度() {
        val panel = panelWithFourButtons()
        panel.actions.preferredWidthHint = 260
        val single = singleButtonHeight(panel)
        val actual = panel.actions.preferredSize.height
        assertTrue(
            "窄宽度下操作行应报多行高度（单行高=$single，实报=$actual）——报单行即 UI-001 回归",
            actual > single * 1.5,
        )
    }

    /** 宽宽度：必须仍报单行，防「为了不被裁而恒撑两行」的过度修正。 */
    fun test宽宽度下操作行报单行高度() {
        val panel = panelWithFourButtons()
        panel.actions.preferredWidthHint = 4000
        val single = singleButtonHeight(panel)
        val actual = panel.actions.preferredSize.height
        assertTrue(
            "宽宽度下应报单行高度（单行高=$single，实报=$actual）",
            actual <= single * 1.5,
        )
    }

    /** 宽度未知（首次布局前）：退回原生估算，不得抛异常也不得返回 0 高。 */
    fun test宽度未知时退回原生估算() {
        val panel = panelWithFourButtons()
        panel.actions.preferredWidthHint = 0
        val actual = panel.actions.preferredSize.height
        assertTrue("宽度未知时应退回原生估算且高度为正（实报=$actual）", actual > 0)
    }

    /** doLayout 注入宽度：父容器布局后，操作行拿到的 hint 应等于容器可用宽度。 */
    fun test布局时向操作行注入可用宽度() {
        val panel = panelWithFourButtons()
        panel.setSize(420, 600)
        panel.doLayout()
        val expected = 420 - panel.insets.left - panel.insets.right
        assertEquals("doLayout 应把容器可用宽度注入操作行", expected, panel.actions.preferredWidthHint)
    }
}
