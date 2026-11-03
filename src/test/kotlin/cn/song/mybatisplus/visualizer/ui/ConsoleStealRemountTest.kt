package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel

/**
 * 2026-11-03 真机「点击执行 SQL 后预览窗口毫无变化、结果只出现在 IDEA 控制台」的**无头复现**。
 *
 * 现场：平台把**同一个**控制台组件重挂到 `ServiceViewComponentWrapper`（Database 工具窗侧），
 * 实例不变、父容器变了；`embedConsole` 旧实现只比较实例身份就早退，于是永远不再挂回面板。
 * 日志签名：`[A49] submit diag: ... viewShowing=false` + `mySplitBottom=null`。
 *
 * 本用例不碰 GUI、不连数据库：只驱动「嵌入 → 被外部容器抢走 → 再次嵌入」这条纯 Swing 路径。
 */
class ConsoleStealRemountTest : BasePlatformTestCase() {

    fun test被外部容器抢走后再次嵌入必须挂回面板() {
        val panel = SqlPreviewPanel(project)
        try {
            val console = JPanel()
            panel.embedConsoleForTest(console)
            assertSame(
                "首次嵌入后控制台应挂在面板 consoleHost 下",
                panel.consoleHostForTest(), console.parent,
            )

            // 模拟平台行为：同一个实例被另一个容器 add() 走（AWT 会自动从旧父摘除）
            val thief = JPanel()
            thief.add(console)
            assertSame("夹具前提：控制台已被抢走", thief, console.parent)
            assertNotSame("夹具前提：已不在面板 host 下", panel.consoleHostForTest(), console.parent)

            // 再次嵌入同一实例——旧实现会因 sameInstance 早退，修复后必须重挂
            panel.embedConsoleForTest(console)
            assertSame(
                "同一实例被平台摘走后，再次 embedConsole 必须重新挂回 consoleHost",
                panel.consoleHostForTest(), console.parent,
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test仍在面板内时同实例嵌入保持不动() {
        val panel = SqlPreviewPanel(project)
        try {
            val console = JPanel()
            panel.embedConsoleForTest(console)
            val host = panel.consoleHostForTest()
            panel.embedConsoleForTest(console)
            assertSame("未被打扰时同实例嵌入不应重挂", host, console.parent)
            assertSame("未被打扰时 embeddedConsole 不变", console, panel.embeddedConsoleForTest())
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /**
     * 2026-11-03 真机的**真实形态**：split 本体还在面板里，被抢走的是**下半区**（结果组件）。
     *
     * DBN 把结果组件重挂回 `ServiceViewComponentWrapper` 时，`JSplitPane.remove()` 会把
     * rightComponent 置 null —— 面板下半区空白、结果只在 IDEA 控制台可见。旧实现同实例
     * 早退只看「整块是否还在」，从不补下半区，所以这条形态一直没被用例覆盖。
     */
    fun test下半区被抢走后同实例嵌入必须补挂() {
        val panel = SqlPreviewPanel(project)
        try {
            val top = JPanel()
            val result = JPanel()
            val split = javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, top, result)
            // 补挂函数按这个缓存槽取结果组件（生产由 prepareAfterEditorResolved 填充）
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.preparedResultComponent = result

            panel.embedConsoleForTest(split)
            assertSame("夹具前提：下半区已挂上", result, split.bottomComponent)

            // 模拟平台：同一个结果组件被另一个容器 add() 走（JSplitPane 会把 rightComponent 置 null）
            val thief = JPanel()
            thief.add(result)
            assertNull("夹具前提：split 下半区已被掏空", split.bottomComponent)

            // 同实例再次嵌入——旧实现只重挂整块，下半区仍是 null
            panel.embedConsoleForTest(split)
            assertSame(
                "下半区被抢走后，同实例嵌入必须把它补挂回 split",
                result, split.bottomComponent,
            )
        } finally {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.preparedResultComponent = null
            panel.clear()
            panel.dispose()
        }
    }
}
