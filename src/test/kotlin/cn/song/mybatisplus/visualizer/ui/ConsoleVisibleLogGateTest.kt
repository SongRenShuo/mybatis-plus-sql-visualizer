package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PerfSweep30 01b：`request→console-visible` 打点判据（性能分析 §4.1 账本③）。
 *
 * 缺陷形态：打点在 embedConsole 挂载回调里，同实例回调重复触发会重复打；失败请求复用上一次
 * `lastPreviewRequestMs` 打出跨请求失真读数（真机 10151ms）。
 *
 * 收口形态：只在首次嵌入（嵌入**前** `lastConsoleEmbeddedId == null`）且请求起点有效时打点。
 *
 * 反向验证（撤闸必红）：
 *  - 判据改回「事后比较 lastConsoleEmbeddedId」→ [test重复嵌入不再打点] 红；
 *  - 去掉 requestMs 闸 → [test失败请求清零后不打点] 红。
 *
 * k2j 移植注：master ui 包顶层 `internal fun shouldLogConsoleVisible` 落
 * `SqlPreviewPanelExecutionKt` 门面静态方法（public，k2j:3058 行），
 * 具名实参改位置实参（首参 @Nullable Integer 可传 null）。
 */
class ConsoleVisibleLogGateTest : BasePlatformTestCase() {

    /** 首次嵌入（此前无嵌入 id）且请求起点有效 → 打点。 */
    fun test首次嵌入打点() {
        assertTrue(
            "首次嵌入 + 有效请求起点应打点",
            SqlPreviewPanelExecutionKt.shouldLogConsoleVisible(null, 1_700_000_000_000L),
        )
    }

    /** 重复嵌入（本面板已嵌过组件）→ 不打点（同一次预览不得打出多条读数）。 */
    fun test重复嵌入不再打点() {
        assertFalse(
            "已嵌入过（prevEmbeddedId 非 null）不得再打点",
            SqlPreviewPanelExecutionKt.shouldLogConsoleVisible(42, 1_700_000_000_000L),
        )
    }

    /** 失败请求已把起点清零 → 不打点（否则会打出跨请求失真读数）。 */
    fun test失败请求清零后不打点() {
        assertFalse(
            "请求起点为 0（失败已清零）不得打点",
            SqlPreviewPanelExecutionKt.shouldLogConsoleVisible(null, 0L),
        )
        assertFalse(
            "负起点同样不打点",
            SqlPreviewPanelExecutionKt.shouldLogConsoleVisible(null, -1L),
        )
    }
}
