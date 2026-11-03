package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel

/**
 * 2026-11-03 真机「执行 SQL 后预览面板下半区空白、连原生工具栏都不出现」的**根因级无头复现**。
 *
 * 真机日志（IDEA 2026.2.4 / v2026.11.2，`idea.log`）每次都是同一序列：
 *
 * ```
 * 09:42:24.509  embedConsole identity: id=941301681 ... embedMode=PREVIEW   ← 控制台已嵌进 editorHolder
 * 09:42:24.811  in-memory PSI preview document created (len=204)            ← 降级装配在嵌入之后到达
 * 09:42:25.107  late prewarm product consumed: editor upgraded              ← 迟到产物再次装配
 * 09:42:27.171  [A49] result host at submit: ... mySplitBottom=null
 * ```
 *
 * 因果链：`editorHolder` 是「预览编辑器 / 原生控制台」二者唯一的显示槽，`assemblePreviewEditor`
 * （含降级装配与迟到换装）与 `showPreviewPreparingPlaceholder` 都无条件 `removeAll()` 后挂自己的
 * 编辑器——冷启预热超预算时，这两条装配都**晚于**控制台嵌入，于是把 `consoleHost` 整块挤下显示链。
 * 而 `consoleAttachedToMe()` 只看 `consoleHost.isAncestorOf(console)`，宿主被摘下也恒为 true，
 * `embedConsole` 的同实例早退又只看 `console.parent === consoleHost`，于是谁都不会把面板修回来：
 * 用户看到的始终是插件自己的编辑器（带行号、无原生工具栏），结果只落在 IDEA 的 Database 控制台。
 *
 * 本用例锁三件事：
 *  1. 控制台在位时，预览编辑器装配不得把控制台挤下显示链；
 *  2. 控制台在位时，「准备中」占位同样不得挤掉控制台；
 *  3. 一旦宿主真的被摘下（历史故障态/外部改动），同实例 `embedConsole` 必须重新挂回面板。
 */
class PreviewEditorAssemblyKeepsConsoleTest : BasePlatformTestCase() {

    private fun newConsoleSplit(): javax.swing.JSplitPane = javax.swing.JSplitPane(
        javax.swing.JSplitPane.VERTICAL_SPLIT,
        JPanel(),
        JPanel(),
    )

    /** 1. 降级装配（in-memory PSI）晚于控制台嵌入——真机 09:42:24.811 那一拍。 */
    fun test预览编辑器装配不得把已嵌入的控制台挤出显示链() {
        val panel = SqlPreviewPanel(project)
        try {
            val console = newConsoleSplit()
            panel.embedConsoleForTest(console)
            assertTrue(
                "夹具前提：控制台已在面板显示链里",
                panel.editorHolderForTest().isAncestorOf(console),
            )

            // 真机形态：预热超预算 → onFallback → assemblePreviewEditor(sql, null) → 内存 PSI 降级装配
            panel.assemblePreviewEditor("SELECT id FROM user WHERE id = ?", null)

            assertTrue(
                "控制台嵌入之后到达的预览编辑器装配，不得把 consoleHost 挤下 editorHolder" +
                    "（真机症状：预览面板只剩自建编辑器、原生工具栏与结果区全消失）",
                panel.editorHolderForTest().isAncestorOf(console),
            )
            assertTrue(
                "consoleAttachedToMe 必须如实反映「控制台仍在面板显示链里」",
                panel.consoleAttachedToMe(),
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /** 2. 迟到产物换装（真机 09:42:25.107 那一拍）同样不得挤掉控制台。 */
    fun test迟到产物换装不得把已嵌入的控制台挤出显示链() {
        val panel = SqlPreviewPanel(project)
        try {
            val console = newConsoleSplit()
            panel.embedConsoleForTest(console)

            // 与 tryUpgradeToLateProduct 同一条装配收口（区别只在 prewarmed 是否非空）
            panel.assemblePreviewEditor("SELECT id FROM user WHERE id = ?", null)

            assertTrue(
                "迟到换装后控制台仍须挂在面板显示链里",
                panel.editorHolderForTest().isAncestorOf(console),
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /** 3. 「准备中」占位是编辑器侧产物，控制台在位时不得顶掉它。 */
    fun test准备中占位不得把已嵌入的控制台挤出显示链() {
        val panel = SqlPreviewPanel(project)
        try {
            val console = newConsoleSplit()
            panel.embedConsoleForTest(console)

            panel.showPreparingPlaceholderForTest()

            assertTrue(
                "准备中占位不得把已嵌入的控制台挤下显示链",
                panel.editorHolderForTest().isAncestorOf(console),
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /** 4. 兜底自愈：宿主已被摘下（历史故障态）时，同实例 embedConsole 必须重挂回面板。 */
    fun test宿主被摘下后同实例嵌入必须重新挂回面板() {
        val panel = SqlPreviewPanel(project)
        try {
            val console = newConsoleSplit()
            panel.embedConsoleForTest(console)
            val host = panel.consoleHostForTest()

            // 模拟故障态：editorHolder 被别的装配接管，consoleHost 整块被摘下
            panel.editorHolderForTest().removeAll()
            assertFalse(
                "夹具前提：consoleHost 已被摘下显示链",
                panel.editorHolderForTest().isAncestorOf(console),
            )

            panel.embedConsoleForTest(console)

            assertSame(
                "同实例嵌入必须把 consoleHost 重新挂回 editorHolder",
                panel.editorHolderForTest(),
                host.parent,
            )
            assertTrue(
                "控制台必须重新回到面板显示链",
                panel.editorHolderForTest().isAncestorOf(console),
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }
}
