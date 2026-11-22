package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.util.registry.Registry

/**
 * T-HdAbsorb D216（吸收自 Hibernate Debugger 的 `NPlusOneTransactionClassifier` +
 * `RepeatedParameterizedSelectCriterion`「事务分组 + 同参数化 SQL ≥N 判嫌疑」思路）：
 * **N+1 嫌疑判定（第一刀）**。
 *
 * 与对方的差异化定位（红线不变）：对方在调试断点现场做实时分类；本插件走**捕获后离线聚合**——
 * 计数源就是既有同模板聚合 [structureKeyCounts]（字面量参数化同形状同键，T-SqlListCatchup
 * 增量维护），本对象只负责「把计数升级为嫌疑判定」：
 *  - **阈值**：同模板条数 ≥ [DEFAULT_MIN_COUNT]（Registry `sqlvis.log.nplusOneSuspect.minCount`
 *    可调）判嫌疑——比「×N 同模板」展示徽章（[TEMPLATE_BADGE_MIN_COUNT]，≥3 纯提示）更强的
 *    行动信号；
 *  - **回退通道**：Registry `sqlvis.log.nplusOneSuspect`（默认开）关掉即回到「只有同模板徽章」
 *    的旧行为；
 *  - **已知边界（如实登记）**：第一刀是「会话级」口径——同一模板跨事务/跨线程累计也计入
 *    （结构指纹不含事务维度）。第二刀（事务标识）单独立项，见计划册 D216。
 */
internal object NplusOneSuspect {

    /** Registry 总开关（plugin.xml `<registryKey>` 登记）；关闭＝不显示嫌疑徽章（旧行为）。 */
    internal const val REGISTRY_KEY = "sqlvis.log.nplusOneSuspect"

    /** 阈值 Registry 键（int，默认 5）。 */
    internal const val THRESHOLD_KEY = "sqlvis.log.nplusOneSuspect.minCount"

    /** 嫌疑判定默认阈值（吸收源 ≥2 过敏——循环外两次偶发同模板不算；5 取「明显批量形态」）。 */
    internal const val DEFAULT_MIN_COUNT = 5

    /** 测试缝：总开关读取（生产＝`Registry.is(REGISTRY_KEY, true)`）。 */
    internal var enabledProvider: () -> Boolean = {
        runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true)
    }

    /** 测试缝：阈值读取（生产＝`Registry.get(THRESHOLD_KEY).asInteger()`，键已在 plugin.xml 声明默认 5）。 */
    internal var thresholdProvider: () -> Int = {
        runCatching { Registry.get(THRESHOLD_KEY).asInteger() }
            .getOrNull()?.takeIf { it >= 2 } ?: DEFAULT_MIN_COUNT
    }

    /**
     * 嫌疑判定（纯函数）：给定同模板计数与可选显式阈值（测试注入），是否判 N+1 嫌疑。
     * 总开关关闭恒 false（回退通道）；计数 null/未达阈值 false。
     */
    fun isSuspect(count: Int?, explicitThreshold: Int? = null): Boolean {
        if (!enabledProvider()) return false
        val c = count ?: return false
        val threshold = explicitThreshold ?: thresholdProvider()
        return c >= threshold
    }

    // ------------------------------------------------------------------
    // 第二刀（事务维度的退化口径，D216 决策点推荐「调用栈事务帧优先、退化线程+时间窗」）：
    // agent 连接级事务边界织入（commit/rollback 拦截）超出本车道最小语义，登记延后；
    // 这里落**线程 + 滑动窗**退化判定为纯函数 API（接线落点 = SqlLogEntryBuffer 的
    // structureKey 增量计数链，接线时机随缓冲层下一批改动）。
    // ------------------------------------------------------------------

    /** 第二刀默认滑动窗宽（毫秒）：同一事务的 N+1 批量典型在秒级完成。 */
    internal const val DEFAULT_WINDOW_MS = 60_000L

    /** 窗口判定的条目切片（调用方从缓冲层快照投影；故意不带完整 LogSqlEntry——纯函数零耦合）。 */
    data class WindowEntry(
        val timestampMs: Long,
        val threadName: String?,
        val structureKey: String,
    )

    /**
     * 窗口判定（纯函数）：目标条目在**同线程 + 滑动窗** [windowMs] 内的同模板条数 ≥ 阈值
     * 判嫌疑（事务维度的退化代理——同线程短窗重复远比全窗累计更接近「循环内查询」形态）。
     * 开关门与 [isSuspect] 共用。
     */
    fun windowSuspect(
        target: WindowEntry,
        siblings: List<WindowEntry>,
        explicitThreshold: Int? = null,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Boolean {
        if (!enabledProvider()) return false
        val threshold = explicitThreshold ?: thresholdProvider()
        val lo = target.timestampMs - windowMs
        val hi = target.timestampMs + windowMs
        val count = siblings.count {
            it.structureKey == target.structureKey &&
                it.threadName != null && it.threadName == target.threadName &&
                it.timestampMs in lo..hi
        }
        return count >= threshold
    }
}
