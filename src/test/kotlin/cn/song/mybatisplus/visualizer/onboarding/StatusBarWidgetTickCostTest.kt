package cn.song.mybatisplus.visualizer.onboarding

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PerfSweep30 券03：状态栏 widget 周期 tick 成本实测（性能分析 §4.2 的「先测后判」）。
 *
 * 背景：`SqlPreviewStatusBarWidget` 以 `isEnabledByDefault = true` 引入每项目 2 秒池化轮询，
 * 是本系列首例「默认开、永久周期」资产（破「新增常驻 0」不变式）。事件化（项目级 Topic）的
 * 收益取决于 tick 本身多贵——本用例给读数，作为「改」或「登记不动」的判据。
 *
 * 判据：单 tick < 100µs（2s 一次 → 占 CPU < 0.005%）。通过即判「事件化收益不成立」，按计划
 * 登记不动并把读数回填报告 §4.2；若哪天 tick 变贵（例如快照里加了统计聚合），本用例会红，
 * 那时再上 Topic。
 */
class StatusBarWidgetTickCostTest : BasePlatformTestCase() {

    fun testTickCostIsNegligible() {
        // 预热：类加载与首次调用（含 sqlLogManagerIfCreated 的容器查找）不计入读数
        repeat(200) { computeSnapshot(project, executeAvailable = true) }

        val iterations = 2_000
        val startNanos = System.nanoTime()
        repeat(iterations) { computeSnapshot(project, executeAvailable = true) }
        val avgMicros = (System.nanoTime() - startNanos) / 1_000.0 / iterations

        println("[PROBE-03] statusbar widget tick avg = %.2f µs (n=%d)".format(avgMicros, iterations))

        assertTrue(
            "2s 一次的池化 tick 应 < 100µs（实测 %.2fµs；超标说明快照里加了重活，该上事件化了）"
                .format(avgMicros),
            avgMicros < 100.0,
        )
    }

    /** 快照纯函数本身零平台依赖（不触服务创建）——与 widget KDoc 的只读入口契约对齐。 */
    fun testSnapshotDoesNotCreateLogManager() {
        val before = cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated(project)
        repeat(50) { computeSnapshot(project, executeAvailable = true) }
        val after = cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated(project)
        assertEquals(
            "快照读取不得创建 SqlLogManager（只读入口契约，否则 widget 会毒化容器）",
            before,
            after,
        )
        assertNull("夹具前提：本用例项目未启用捕获，服务应仍未创建", after)
    }
}
