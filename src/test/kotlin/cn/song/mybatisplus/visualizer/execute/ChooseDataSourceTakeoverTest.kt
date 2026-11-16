package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-NativeSwitchTakeover：控制台工具栏原生数据源下拉的接管边界。
 *
 * 接管只在「事件文件是本插件 exec 文件」时替换子项；其它一切场景必须**原样委托**原生组——
 * 否则会改掉 DataGrip/IDE 其它控制台的行为。
 *
 * 走 `BasePlatformTestCase`（平台 `ActionGroup`/`AnActionEvent` 构造要 Application 在场；
 * 纯 JUnit 下 `ActionManager.getApplication()` 为 null 直接 NPE）。
 */
class ChooseDataSourceTakeoverTest : BasePlatformTestCase() {

    /** 可辨识子项（AnAction 为抽象类，桩需显式实现 actionPerformed）。 */
    private class NamedAction(text: String) : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) {}
    }

    /** 原生组桩：记录被委托次数并返回可辨识子项。 */
    private class StubGroup : com.intellij.openapi.actionSystem.DefaultActionGroup() {
        var childrenCalls = 0
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            childrenCalls++
            return arrayOf(NamedAction("native"))
        }
    }

    private fun emptyEvent(): AnActionEvent =
        AnActionEvent.createFromDataContext("test", Presentation(), DataContext { _ -> null })

    fun test接管只认预览exec文件() {
        assertTrue(
            "exec 目录下的执行文件必须接管",
            takeoverAppliesTo("F:/proj/.idea/sql-visualizer/exec/mpsql-exec.sql"),
        )
        assertFalse("普通 SQL 文件不得被接管", takeoverAppliesTo("F:/proj/src/Foo.sql"))
        assertFalse("同名但不在 exec 目录不得被接管", takeoverAppliesTo("F:/proj/src/mpsql-exec.sql"))
        assertFalse("无文件（菜单触发）不得被接管", takeoverAppliesTo(null))
    }

    fun test无Project时委托原生组() {
        val original = StubGroup()
        val children = ChooseDataSourceTakeoverGroup(original).getChildren(emptyEvent())
        assertEquals("无 Project 时必须委托原生组", 1, original.childrenCalls)
        assertEquals("native", children.single().templateText)
    }

    fun test空事件委托原生组() {
        val original = StubGroup()
        val children = ChooseDataSourceTakeoverGroup(original).getChildren(null)
        assertEquals("空事件必须委托原生组（不猜目标）", 1, original.childrenCalls)
        assertEquals("native", children.single().templateText)
    }

    /**
     * T-TemplatePresentationWarn：模板镜像走平台 `copyFrom`（合规通道）。
     *
     * 243 字节码实证：`templatePresentation.setEnabled/setVisible` 开头就 `assertNotTemplatePresentation`
     * （查 TEMPLATE 位后 `warnInProduction`，真机日志每次 update 两条 WARN 带全栈）；而
     * `copyFrom(Presentation)` 的私有实现直接 `BitUtil.set(myFlags, …)` 写 enabled/visible 位——
     * 不经断言、还补发属性变化事件。本用例钉住「update 后模板呈现确实镜像到了文本/可用性」，
     * 防止有人把 copyFrom 拆掉后按钮退化回纯图标（真机 2026-11-03 现象）。
     */
    fun testupdate把原生呈现镜像进模板() {
        val group = ChooseDataSourceTakeoverGroup(StubGroup())
        val e = emptyEvent()
        e.presentation.setText("MySQL", false)
        e.presentation.isEnabled = false
        group.update(e)
        assertEquals("数据源名必须镜像进模板呈现", "MySQL", group.templatePresentation.text)
        assertEquals("可用性必须镜像进模板呈现（走 copyFrom 的旗标写入）", false, group.templatePresentation.isEnabled)
    }
}
