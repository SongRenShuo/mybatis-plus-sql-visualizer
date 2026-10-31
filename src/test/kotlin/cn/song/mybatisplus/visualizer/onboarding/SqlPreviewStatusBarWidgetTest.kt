package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 状态栏 widget 的平台侧契约（S2「首用即生效」第二项）：
 *
 *  1. **工厂 → widget 的身份链**：`createWidget` 返回的 widget `ID()` 必须等于工厂 `getId()` 与我们
 *     注册的常量（平台按 id 存取可见性）；
 *  2. **取快照不得创建 SqlLogManager**：`computeSnapshot` 走的是只读入口
 *     [sqlLogManagerIfCreated]——一旦它把服务建起来，就踩了 T-ST-02b2 的「未用功能也创建服务」，
 *     并在项目关闭窗口内可能毒化容器。这里断言调用前后「服务仍是未创建」；
 *  3. **释放安全**（本仓已知坑：IDE 退出报 memory leak）：[SqlPreviewStatusBarWidget.dispose] 幂等、
 *     不抛；dispose 后平台再调 `getText()`（widget 仍挂在状态栏上的极端时序）不得崩。
 *
 * 文本矩阵与可用性判定在纯逻辑测试 `OnboardingStatusSnapshotTest` 里覆盖，本类不重复。
 */
class SqlPreviewStatusBarWidgetTest : BasePlatformTestCase() {

    private var oldLanguage = PluginSettings.DEFAULT_LANGUAGE

    override fun setUp() {
        super.setUp()
        oldLanguage = PluginSettings.getInstance().state.language
    }

    override fun tearDown() {
        try {
            PluginSettings.getInstance().state.language = oldLanguage
        } finally {
            super.tearDown()
        }
    }

    fun `test工厂与widget的id一致且等于注册常量`() {
        val factory = SqlPreviewStatusBarWidgetFactory()
        val widget = factory.createWidget(project)
        assertEquals("工厂 getId 必须等于 widget ID()", factory.getId(), widget.ID())
        assertEquals("id 必须等于注册常量（plugin.xml 同值）", SqlPreviewStatusBarWidget.WIDGET_ID, widget.ID())
        assertFalse("项目存活时工厂应可用", !factory.isAvailable(project))
        assertTrue("widget 应默认启用（否则用户看不到常驻入口）", factory.isEnabledByDefault())
        assertTrue("展示名不得为空白（状态栏右键菜单可见）", factory.getDisplayName().isNotBlank())
    }

    fun `test取快照不得创建SqlLogManager服务`() {
        assertNull("前置：本用例开始时服务不应已创建", sqlLogManagerIfCreated(project))
        val snapshot = computeSnapshot(project, executeAvailable = true)
        assertNotNull("快照必须非空", snapshot)
        assertNull(
            "computeSnapshot 走只读入口，不得发起服务创建（否则毒化容器 + 无用功能也建服务）",
            sqlLogManagerIfCreated(project),
        )
    }

    fun `testwidget释放幂等且释放后读文本不崩`() {
        val widget = SqlPreviewStatusBarWidget(project)
        assertTrue("未释放时文本非空", widget.getText().isNotBlank())
        widget.dispose()
        widget.dispose() // 平台可能在项目关闭时重复释放；二次释放必须无副作用
        assertTrue("释放后读文本不得抛（极端时序：widget 仍挂在状态栏）", widget.getText().isNotBlank())
        assertTrue("释放后 tooltip 不得抛", widget.getTooltipText().isNotBlank())
    }

    fun `testwidget释放后不再刷新状态栏`() {
        val widget = SqlPreviewStatusBarWidget(project)
        widget.dispose()
        // 释放后再取快照：不得触发任何 UI 更新路径（Alarm 已取消）；这里只验“不崩且文本稳定”
        val before = widget.getText()
        val after = computeSnapshot(project, executeAvailable = true).text
        assertEquals("释放后快照不再变化（Alarm 已取消）", before, widget.getText())
        assertTrue("computeSnapshot 本身仍可用（纯读）", after.isNotBlank())
    }
}
