package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel

/**
 * 方案 A 控制台宿主测试（M36.5.39，见「20260830-1334-原生控制台嵌入方案AB-开发计划」A6）：
 *
 *  1. 嵌入必须走持久 consoleHost（容器稳定挂在 editorHolder）——DBN results refreshed
 *     拿走组件时只清空 host 子层，editorHolder 显示链不断裂；
 *  2. DBN 完全释放（parent==null）后 rehost 条件成立即重挂；
 *  3. 重复执行回调同一实例幂等（防闪烁）；
 *  4. 换入新组件时旧组件摘除；
 *  5. 清空预览清空 host 子层但容器保留（再次嵌入复用同一容器）。
 */
class ConsoleHostTest : BasePlatformTestCase() {

    private fun newPanel() = SqlPreviewPanel(project)

    // ======================== A2 持久宿主 ========================

    /** 嵌入走 consoleHost，容器稳定在 editorHolder（显示链宿主不变）。 */
    @Test
    fun testEmbedGoesThroughPersistentHost() {
        val panel = newPanel()
        val console = JPanel()

        panel.embedConsoleForTest(console)

        assertSame("console should be child of persistent host", panel.consoleHostForTest(), console.parent)
        assertSame(
            "host should stay attached to editorHolder (display chain intact)",
            panel.editorHolderForTest(),
            panel.consoleHostForTest().parent,
        )
    }

    /** DBN 拿走 console → host 容器与显示链不断；rehost 条件成立时重挂成功。 */
    @Test
    fun testDbnTakingChildKeepsHostAndRehosts() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)
        val host = panel.consoleHostForTest()

        // 模拟 DBN results refreshed 拿走组件（完全释放：parent == null）
        host.remove(console)
        assertNull("simulated DBN take-away should leave console parentless", console.parent)
        assertSame(
            "display chain (host in editorHolder) must survive the take-away",
            panel.editorHolderForTest(),
            host.parent,
        )

        // A3：释放后重挂条件成立（同一实例、尚未挂回）。hierarchy 监听可能已抢先调度重挂
        //（invokeLater 排队），故接受"显式重挂成功"或"已被监听重挂"两种结果。
        val rehosted = SqlPreviewPanelExecutionKt.rehostConsoleForTest(panel, console) || console.parent === host
        assertTrue("console should be re-hosted after release", rehosted)
        assertSame("console should be back in host", host, console.parent)
    }

    /** rehost 只接受当前嵌入实例；已挂回的组件不重复处理。 */
    @Test
    fun testRehostRefusesForeignOrAlreadyHosted() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)

        val foreign = JPanel()
        assertFalse("foreign component must be refused", SqlPreviewPanelExecutionKt.rehostConsoleForTest(panel, foreign))
        assertNull(foreign.parent)
        assertFalse("already-hosted console must not be re-processed", SqlPreviewPanelExecutionKt.rehostConsoleForTest(panel, console))
        assertSame(panel.consoleHostForTest(), console.parent)
    }

    // ======================== 幂等与替换 ========================

    /** 同一实例重复回调（重复执行触发 onResultsReady）→ 幂等，不摘挂不重建。 */
    @Test
    fun testSameInstanceReembedIsIdempotent() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)
        val parentBefore = console.parent

        panel.embedConsoleForTest(console)

        assertSame("same instance re-embed should keep parent", parentBefore, console.parent)
        assertSame("same instance re-embed should keep current", console, panel.embeddedConsoleForTest())
    }

    /** 换入新控制台组件：旧组件摘除、host 子层替换为新实例。 */
    @Test
    fun testReembedReplacesOldChild() {
        val panel = newPanel()
        val first = JPanel()
        val second = JPanel()

        panel.embedConsoleForTest(first)
        panel.embedConsoleForTest(second)

        assertSame(
            "host should hold the new console",
            second,
            panel.consoleHostForTest().components.first { it === second },
        )
        assertEquals(
            "host should hold the execute-view header + console",
            2,
            panel.consoleHostForTest().componentCount,
        )
        assertNull("old console should be detached", first.parent)
    }

    /** P3a：执行视图必须提供显式「返回预览」，不再只能靠刷新按钮。 */
    @Test
    fun testExecuteEmbedShowsBackToPreviewHeader() {
        val panel = newPanel()
        panel.embedConsoleForTest(JPanel())
        val button = panel.consoleHostForTest().components
            .flatMap { buttons(it) }
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("panel.backToPreview") }
        assertNotNull("执行视图必须提供返回预览入口", button)
    }

    /** P3a：返回预览必须移除执行视图头部（不能只重渲染 SQL 留着「执行结果」标识）。 */
    @Test
    fun testBackToPreviewRemovesExecuteHeader() {
        val panel = newPanel()
        panel.embedConsoleForTest(JPanel())
        assertTrue(
            "执行视图应显示返回预览",
            buttons(panel.consoleHostForTest()).any { it.text == MpSqlVisualizerBundle.message("panel.backToPreview") },
        )
        SqlPreviewPanelExecutionKt.backToPreview(panel)
        assertTrue(
            "返回预览后头部必须移除",
            buttons(panel.consoleHostForTest()).none { it.text == MpSqlVisualizerBundle.message("panel.backToPreview") },
        )
    }

    private fun buttons(component: java.awt.Component): List<javax.swing.AbstractButton> =
        (if (component is javax.swing.AbstractButton) listOf(component) else emptyList()) +
            (if (component is java.awt.Container) component.components.flatMap { buttons(it) } else emptyList())

    // ======================== A2 清空语义 ========================

    /** 清空预览：host 子层清空、容器保留（再次嵌入复用同一容器实例）。 */
    @Test
    fun testDetachClearsHostButKeepsContainer() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)
        val hostBefore = panel.consoleHostForTest()

        SqlPreviewPanelSqlViewKt.clear(panel)
        assertEquals("clear should empty host children", 0, panel.consoleHostForTest().componentCount)
        assertNull(panel.embeddedConsoleForTest())

        // 容器保留：再次嵌入复用同一 host 实例
        val next = JPanel()
        panel.embedConsoleForTest(next)
        assertSame("container must be reused across clear/embed cycles", hostBefore, panel.consoleHostForTest())
        assertSame("next console should live in the same host", hostBefore, next.parent)
    }

    // ======================== A1 身份探针 ========================

    /** 身份探针基线：首次嵌入记录 id，换入新实例后 id 更新，同实例幂等不改变。 */
    @Test
    fun testIdentityProbeTracksEmbeddedConsole() {
        val panel = newPanel()
        val first = JPanel()
        val second = JPanel()

        panel.embedConsoleForTest(first)
        val firstId = System.identityHashCode(first)
        assertEquals("first embed should record its identity", firstId, panel.lastConsoleEmbeddedIdForTest())

        panel.embedConsoleForTest(first)
        assertEquals(
            "idempotent re-embed should keep identity",
            firstId,
            panel.lastConsoleEmbeddedIdForTest(),
        )

        panel.embedConsoleForTest(second)
        assertEquals(
            "replacing embed should update identity",
            System.identityHashCode(second),
            panel.lastConsoleEmbeddedIdForTest(),
        )
        assertTrue(
            "identity log basis: sameAsPrev should be computable (prev != null after first embed)",
            panel.lastConsoleEmbeddedIdForTest() != null,
        )
    }
}
