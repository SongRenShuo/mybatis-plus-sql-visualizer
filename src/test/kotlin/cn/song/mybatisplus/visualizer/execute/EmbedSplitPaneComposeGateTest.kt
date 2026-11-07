package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * ★A49：整控台 SplitPane 组入判定 [shouldComposeEmbedSplitPane] 测试。
 *
 * 背景：SQL 快捷模板三动作 / 日志转 SQL 的「执行」都不传嵌入宿主（`SqlExecutor.execute(onConsole = null)`），
 * 只把语句送原生 Database 控制台执行，控制台由 DBN 在 IDE 底部 Services 视图展示。此前 prepare 链
 * `buildConsoleSplitPane` 与复用链 `swapResultIntoPersistentConsole` 都会把 DBN 结果区组件
 * （`ui.getComponent()`）从原生宿主 `ServiceViewComponentWrapper` 摘进一个**永不展示**的容器
 * （onConsole 为 null、pendingOnConsole 也为 null 故无人回收）——摘取无收益、只扰动 DBN 组件树
 * （★A48 同族现象）。本测试锁定「无宿主不组不摘」这一判据，并锁定有宿主（预览面板）时既有组入行为不回退。
 *
 * **归因更正（2026-10-13）**：本判据的立项依据「录制里原生控制台结果网格停在 0 rows」已查明真因是
 * **调用方传了不存在的表名**（录制脚本右键锚点按单宽估算 CJK 列宽、落到注释里的中文词上，动作据其拼出
 * `SELECT … FROM 对齐插件测试`，DBN 正确报 `[42S02][1146]`）——**与插件行为无关**。故本判据是排查该
 * 现象时顺带发现并收敛的隐患，不是该现象的修复。
 *
 * 该判据同时把守**两个组入点**：`prepareAfterEditorResolved`（全链首执，组 SplitPane）与
 * `reuseSharedConsole`（复用执行，swap 重摘）——两处都用 [shouldComposeEmbedSplitPane]。
 */
class EmbedSplitPaneComposeGateTest : BasePlatformTestCase() {

    /** 无嵌入宿主（onConsole=null）——不得组 SplitPane、不得摘 DBN 结果组件。 */
    fun testNoEmbedHostDoesNotCompose() {
        assertFalse(
            "无嵌入宿主（SQL 快捷模板 / 日志转 SQL 执行链）时不得组 SplitPane 摘走 DBN 结果组件",
            shouldComposeEmbedSplitPane(hasEmbedHost = false, hasResultComponent = true),
        )
    }

    /** 结果区组件取不到（DBN 尚未建出）——无从组入。 */
    fun testNoResultComponentDoesNotCompose() {
        assertFalse(
            "结果区组件缺席时无从组入",
            shouldComposeEmbedSplitPane(hasEmbedHost = true, hasResultComponent = false),
        )
    }

    /** 有嵌入宿主（预览面板）且结果区组件在场——必须组入（既有行为不回退）。 */
    fun testEmbedHostWithResultComposes() {
        assertTrue(
            "有嵌入宿主且结果区组件在场时必须组入（预览路径不回退）",
            shouldComposeEmbedSplitPane(hasEmbedHost = true, hasResultComponent = true),
        )
    }
}
