package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

/**
 * 2026-11-03 真机第二轮反馈的两处修复：
 *
 *  1. **预览未执行就先出现结果栏 / 换一条 SQL 结果栏不变**——DBN 对同一个 exec 文件复用同一个
 *     `ContentManagerImpl$MyNonOpaquePanel`（真机日志两次预览 `resultComponent` 同为 id=839493854、
 *     首次取到就带 `889x248` 已布局尺寸），新建 JdbcConsole 时旧 `Result 1` 页签原样留着：
 *     `resultAreaHasContent` 被旧页签喂成 true → PREVIEW 折叠判据失效 → 结果栏常驻；
 *     换预览 SQL 也看不到隔离。新建控制台路径此前缺清场（复用路径早就有）。
 *  2. **控制台工具栏最右侧只剩数据源图标、名称只进 tooltip**——平台按
 *     `AnAction.displayTextInToolbar()` 决定写不写 `SHOW_TEXT_IN_TOOLBAR`，工具栏再据此选
 *     `ActionButtonWithText` 还是 `ActionButton`（243 `app-client.jar` 字节码实证）。原生
 *     `ChooseDataSourceAction` 返回 true，接管组默认继承 false → 退化成纯图标。
 *     `isPopup=false` 不是正解（会把子项摊平、把整条 header 工具栏挤没，`31d6db38` 已回退）。
 */
class PreviewResultIsolationTest : BasePlatformTestCase() {

    /** 读源码做接线断言：统一换行（工作树按 core.autocrlf 可能是 CRLF，断言只关心形态）。 */
    private fun sourceOf(path: String): String =
        java.io.File(path).readText().replace("\r\n", "\n")

    /** 结果清场只摘结果页签，Output 保留（否则执行回显无处可去）。 */
    fun test清场只移除结果页签保留Output() {
        val output = ContentFactory.getInstance().createContent(JPanel(), "Output", false)
        val result1 = ContentFactory.getInstance().createContent(JPanel(), "Result 1", false)
        val result2 = ContentFactory.getInstance().createContent(JPanel(), "Result 2", false)
        val removed = mutableListOf<String>()
        val fakeUi = object {
            @Suppress("unused")
            fun getContents(): Array<Content> = arrayOf(output, result1, result2)
            @Suppress("unused")
            fun removeContent(content: Content, dispose: Boolean) {
                removed += content.displayName
            }
        }

        DataSourceSelector.clearResultContents(fakeUi, Logger.getInstance(PreviewResultIsolationTest::class.java))

        assertEquals("只摘结果页签，Output 必须保留", listOf("Result 1", "Result 2"), removed)
    }

    /** 接线：新建控制台（prepareAfterEditorResolved）预览路径必须清场，且执行路径不清。 */
    fun test接线_预览准备链清场且执行路径不清() {
        val console = sourceOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt",
        )
        assertTrue(
            "预览准备链必须清结果页签（否则「未执行先出现结果栏」「换 SQL 结果不隔离」复发）",
            console.contains("if (!executeNow) {\n            clearResultContents(ui, LOG)"),
        )
        assertTrue(
            "历史回显同步清空（Output 页签里上一轮 SQL 的回显）",
            console.contains("clearConsoleHistory(project, console, LOG)"),
        )
    }

    /** 接线：接管组必须打开「工具栏显示文案」开关（原生为 true）。 */
    fun test接线_接管组声明工具栏显示文案() {
        val group = ChooseDataSourceTakeoverGroup(com.intellij.openapi.actionSystem.DefaultActionGroup())
        assertTrue("接管组必须让工具栏渲染文案（原生 ChooseDataSourceAction 返回 true）", group.displayTextInToolbar())
        assertEquals(
            "平台按该客户端属性选 ActionButtonWithText（243 app-client.jar 实证）",
            java.lang.Boolean.TRUE,
            group.templatePresentation.getClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR),
        )
    }

    /**
     * 2026-11-03 真机：预览态「结果栏一直占着下半屏」。
     *
     * `setDividerLocation(1.0)` 被结果区的 160px 最小高度兜底钳住（M36.5.64 为「执行后不被压回
     * preferred 84px」设的），只推 divider 时底部仍留 160px（截图实证 bottom≈165px、divider≈0.67）。
     * 折叠必须连最小高度一起放开；展开时恢复兜底。
     */
    fun test折叠结果区必须同时放开最小高度兜底() {
        val top = JPanel()
        val bottom = JPanel()
        val sp = javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, top, bottom)
        sp.setSize(400, 600)
        bottom.minimumSize = java.awt.Dimension(400, 160)

        DataSourceSelector.applyConsoleBottomCollapse(sp, true)
        assertEquals("折叠必须把 160px 兜底让出来", 0, bottom.minimumSize.height)
        assertTrue(
            "折叠后 divider 必须落到底（旧实现被钳在 ~0.67），实际 ${sp.dividerLocation}/${sp.height}",
            sp.dividerLocation >= sp.height * 95 / 100,
        )

        // 折叠态下非 force 的自动校正必须让路，否则任何 resize 都把结果栏弹回来
        val collapsedAt = sp.dividerLocation
        DataSourceSelector.realignDividerOnce(sp, force = false)
        assertEquals("折叠态不得被自动校正展开", collapsedAt, sp.dividerLocation)

        DataSourceSelector.applyConsoleBottomCollapse(sp, false)
        assertEquals("展开时恢复最小高度兜底", 160, bottom.minimumSize.height)
        assertTrue("展开后 divider 回到 ~0.6", sp.dividerLocation in (sp.height * 55 / 100)..(sp.height * 65 / 100))
    }

    /**
     * 2026-11-03 真机：折叠在「split 还没有真实高度」的那一拍生效后，被后续放大按
     * `resizeWeight=0.6` 又弹回 ~0.67。折叠必须同时把 resizeWeight 改成 1.0（新增高度全给顶部）。
     */
    fun test折叠后放大不得把结果区按比例弹开() {
        val top = JPanel()
        val bottom = JPanel()
        val sp = javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, top, bottom)
        sp.resizeWeight = 0.6
        sp.setSize(400, 150)
        bottom.minimumSize = java.awt.Dimension(400, 160)

        // 小高度时折叠（真机时序：布局未完成先折一次）
        DataSourceSelector.applyConsoleBottomCollapse(sp, true)
        assertEquals("折叠时 resizeWeight 必须置 1.0", 1.0, sp.resizeWeight, 1e-9)

        // 随后对话框完成布局：高度从 150 涨到 600
        sp.setSize(400, 600)
        // headless 下 setSize 不会给「未显示」组件派发 COMPONENT_RESIZED（AWT 只在 isShowing 时派），
        // 这里手动派发一次，等价真机的布局回调
        sp.dispatchEvent(
            java.awt.event.ComponentEvent(sp, java.awt.event.ComponentEvent.COMPONENT_RESIZED),
        )
        sp.doLayout()

        assertTrue(
            "放大后 divider 必须仍贴底（resizeWeight=0.6 会按比例把结果区弹开），实际 ${sp.dividerLocation}/${sp.height}",
            sp.dividerLocation >= sp.height * 95 / 100,
        )
    }

    /**
     * 2026-11-03 真机铁证：折叠成功后 2.5 秒，`result reclaim guard` 把 DBN 抢走的结果组件挂回时
     * **顺手把 160px 最小高度写回去**，divider 立刻被钳回 ~0.68——折叠白做。
     * 折叠态下回收必须保持 0 兜底并重新贴底。
     */
    fun test折叠态下回收守卫不得把最小高度写回去() {
        val top = JPanel()
        val bottom = JPanel()
        val sp = javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, top, bottom)
        sp.setSize(400, 600)
        bottom.minimumSize = java.awt.Dimension(400, 160)
        DataSourceSelector.applyConsoleBottomCollapse(sp, true)

        // 模拟 DBN 把结果组件抢走：split 底部被置空
        sp.bottomComponent = null
        val reclaimed = DataSourceSelector.reclaimResultBottom(
            sp,
            bottom,
            longArrayOf(0L, 0L),
            Logger.getInstance(PreviewResultIsolationTest::class.java),
            force = true,
        )

        assertTrue("夹具前提：回收守卫确实抢回了结果组件", reclaimed)
        assertEquals("折叠态下回收不得恢复 160px 兜底", 0, bottom.minimumSize.height)
        assertTrue(
            "折叠态下回收后 divider 必须仍贴底，实际 ${sp.dividerLocation}/${sp.height}",
            sp.dividerLocation >= sp.height * 95 / 100,
        )
    }

}
