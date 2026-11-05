package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import javax.swing.JPanel

/**
 * ★A48：结果换入判定 [DataSourceSelector.resultSwapNeeded] 测试。
 *
 * 背景（用户实证 + 沙盒日志两次执行对照）：同一会话**第一次**点「执行 SQL」时预览窗内看不到
 * 结果（结果只落在 IDE 底部原生控制台），再点一次才有；差别落在 newTab=true（本次提交新建结果
 * 页签）/ newTab=false（复用旧页签）这条路径上。
 *
 * 根因：DBN 新建结果页签时会把结果区组件重新挂回原生宿主 `ServiceViewComponentWrapper`
 * ——**同一实例、仅 parent 变**。既有换入判据只比较组件身份与页签名称集合，re-parent 两者都
 * 不变，判据恒 false，SplitPane 底部滞留空组件。本测试锁定第四判据（组件已不在本 SplitPane
 * 底部 → 幂等回收重挂），并锁定既有三条判据与稳态不抖动。
 */
class ResultSwapNeededTest : BasePlatformTestCase() {

    /** 换入目标（持久整控台 SplitPane 的替身——判定只读 parent/底部子组件）。 */
    private fun newHost() = JPanel()

    /** 稳态：已换入且仍在位——不得再换（防每拍重建导致闪烁）。 */
    fun testSteadyStateNeedsNoSwap() {
        val host = newHost()
        val live = JPanel()
        host.add(live, java.awt.BorderLayout.CENTER)

        assertFalse(
            "已换入且 parent 与底部都指向本控台时不得再换（去重稳态）",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = live,
                live = live,
                lastSwapped = live,
                namesChanged = false,
                forceSwapIn = false,
                alreadySwapped = true,
            ),
        )
    }

    /**
     * ★A48 核心回归：DBN 把同一实例重新挂回原生宿主（ServiceView）——身份与名称都没变，
     * 仅 parent 变了，必须判为需要回收重挂。
     */
    fun testReparentedComponentNeedsReclaim() {
        val host = newHost()
        val live = JPanel()
        host.add(live, java.awt.BorderLayout.CENTER)
        // DBN 新建结果页签时把结果组件挂回原生宿主（同实例，parent 变）
        val dbnServiceView = JPanel()
        dbnServiceView.add(live, java.awt.BorderLayout.CENTER)

        assertTrue(
            "组件被 DBN 挂回原生宿主（parent 不再是本控台）时必须回收重挂",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = live,
                live = live,
                lastSwapped = live,
                namesChanged = false,
                forceSwapIn = false,
                alreadySwapped = true,
            ),
        )
    }

    /** DBN 完全释放（parent=null）同样须回收重挂。 */
    fun testParentlessComponentNeedsReclaim() {
        val host = newHost()
        val live = JPanel()

        assertTrue(
            "组件无父（DBN 释放）时必须挂回",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = live,
                live = live,
                lastSwapped = live,
                namesChanged = false,
                forceSwapIn = false,
                alreadySwapped = true,
            ),
        )
    }

    /** 底部被换成别的组件（hostBottom 不再指向 live）也要重挂。 */
    fun testReplacedBottomNeedsReclaim() {
        val host = newHost()
        val live = JPanel()
        val other = JPanel()
        host.add(live, java.awt.BorderLayout.CENTER)

        assertTrue(
            "底部组件已被换掉（hostBottom != live）时须重挂",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = other,
                live = live,
                lastSwapped = live,
                namesChanged = false,
                forceSwapIn = false,
                alreadySwapped = true,
            ),
        )
    }

    // ==================== 既有三条判据不回退 ====================

    /** 判据一：found 结果页签后的强制首换（本拍尚未换过）。 */
    fun testForcedFirstSwap() {
        val host = newHost()
        val live = JPanel()
        // 尚未换入：parent 为原生宿主
        JPanel().add(live)

        assertTrue(
            "forceSwapIn 且本拍尚未换过 → 必换",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = null,
                live = live,
                lastSwapped = null,
                namesChanged = false,
                forceSwapIn = true,
                alreadySwapped = false,
            ),
        )
    }

    /** 判据一不得退化成每拍强制（已在位 + 换过 + forceSwapIn）→ 不换。 */
    fun testForceSwapInDoesNotChurnAfterFirstSwapped() {
        val host = newHost()
        val live = JPanel()
        host.add(live, java.awt.BorderLayout.CENTER)

        assertFalse(
            "已换过（swapped=true）时 forceSwapIn 不得每拍强制",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = live,
                live = live,
                lastSwapped = live,
                namesChanged = false,
                forceSwapIn = true,
                alreadySwapped = true,
            ),
        )
    }

    /** 判据二：组件实例变化（DBN 重建结果组件）→ 换。 */
    fun testInstanceChangedSwaps() {
        val host = newHost()
        val previous = JPanel()
        val live = JPanel()
        host.add(live, java.awt.BorderLayout.CENTER)

        assertTrue(
            "结果组件实例变化（DBN 重建）必须换入",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = previous,
                live = live,
                lastSwapped = previous,
                namesChanged = false,
                forceSwapIn = false,
                alreadySwapped = true,
            ),
        )
    }

    /** 判据三：结果页签名称集合变化（空名占位改名/新页签）→ 换。 */
    fun testNamesChangedSwaps() {
        val host = newHost()
        val live = JPanel()
        host.add(live, java.awt.BorderLayout.CENTER)

        assertTrue(
            "页签名称集合变化必须换入（首点网格晚于换入构建的兜底）",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = live,
                live = live,
                lastSwapped = live,
                namesChanged = true,
                forceSwapIn = true,
                alreadySwapped = true,
            ),
        )
    }

    // ==================== 边界 ====================

    /** live 取不到（反射失败）→ 不换（与既有 getOrNull 语义一致）。 */
    fun testNullLiveNeedsNoSwap() {
        val host = newHost()

        assertFalse(
            "live 为 null 时不得换入",
            DataSourceSelector.resultSwapNeeded(
                host = host,
                hostBottom = null,
                live = null,
                lastSwapped = null,
                namesChanged = true,
                forceSwapIn = true,
                alreadySwapped = false,
            ),
        )
    }

    /** 非分栏形态（host=null）时第四判据不生效——避免回退路径每拍空换。 */
    fun testNullHostDoesNotTriggerReclaim() {
        val live = JPanel()
        JPanel().add(live)

        assertFalse(
            "host 非分栏（null）时第四判据不得触发",
            DataSourceSelector.resultSwapNeeded(
                host = null,
                hostBottom = null,
                live = live,
                lastSwapped = live,
                namesChanged = false,
                forceSwapIn = false,
                alreadySwapped = true,
            ),
        )
    }
}
