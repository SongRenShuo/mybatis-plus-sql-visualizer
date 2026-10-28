package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-SqlDialectPrewarm：预热助手的**短路契约**。
 *
 * 行为面（真实预热把 `SqlDialectMappings` 的一次性加载做掉、此后 EDT 首查不再触发平台慢操作断言）
 * 只能在 runIde 沙盒验：测试基座没有 Database 插件，`getStdFileType("SQL")` 为 null，且单测模式会先
 * 短路。本类只钉三件事，防「预热把测试搞红」与「EDT 侧被无界等待拖住」：
 *
 *  1. 单测模式下 `prewarmNow` / `prewarmAndAwait` / `prewarm` 全部短路且不抛；
 *  2. 有界等待在短路路径上不消耗预算；
 *  3. 短路不得被记成「已预热」（否则真机上的一处失败会被永久掩盖）。
 */
class SqlDialectMappingPrewarmTest : BasePlatformTestCase() {

    fun test单测模式全部短路且不抛() {
        SqlDialectMappingPrewarm.resetForTest(project)
        assertFalse("prewarmNow 在单测模式应短路", SqlDialectMappingPrewarm.prewarmNow(project))
        assertFalse("prewarmAndAwait 在单测模式应短路", SqlDialectMappingPrewarm.prewarmAndAwait(project, 50L))
        SqlDialectMappingPrewarm.prewarm(project)
        assertFalse("短路不得记为已预热", SqlDialectMappingPrewarm.isWarmedForTest(project))
    }

    fun test有界等待在短路路径不消耗预算() {
        SqlDialectMappingPrewarm.resetForTest(project)
        val startedAt = System.nanoTime()
        SqlDialectMappingPrewarm.prewarmAndAwait(project, 5_000L)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("短路应立即返回，实测 ${elapsedMs}ms（上限 5000ms）", elapsedMs < 500L)
    }
}