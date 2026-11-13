package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.MockObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-StartupReadLockFreeze S1/S2：预取闸门与预算（纯逻辑，无需平台环境）。
 *
 * 被守的行为是「什么情况下**不**跑索引检索」（`searchRuns` 不涨）：dumb 态、项目未就绪、冷却窗口、
 * Registry 关闭、预算耗尽。反向用例（[`smart且就绪时恰好检索一次`]）保证闸门不是「全关」——
 * 少了它，前面几条在「预取整体被删掉」时也会全绿（空绿，规范 S2）。
 *
 * 为什么走 `prefetchForTest` 而不是 `prefetch`：`prefetch` 需要真 `Project` 且单元测试模式下直接早退；
 * 该入口与生产共用 [MbgExampleCallResolver.decidePrefetch] / `searchOnce` / `onPrefetchDone`，
 * 只有「平台调度」那一段（`ReadAction.nonBlocking` + 预算计时器）被同步执行替代。
 */
class MbgExamplePrefetchGateTest {

    @After
    fun cleanup() {
        MbgExampleCallResolver.resetCacheForTest()
    }

    private fun entry() = MbgExampleCallResolver.CachedResolution(
        elements = mapOf("criteria.criteria" to listOf(MockObject(mapOf("condition" to "cust_id =")))),
        stamps = emptyMap(),
    )

    /** 冷却窗口之后的时间点（第一次调用在 t=0 建立 firstSeen）。 */
    private val afterCooldown = MbgExampleCallResolver.PREFETCH_COOLDOWN_MS + 1

    /** 先落一次「冷却期跳过」，把项目的 firstSeen 钉在 0，后续用例只受被测那条闸影响。 */
    private fun establishFirstSeen(projectKey: String = "p") {
        val reason = MbgExampleCallResolver.prefetchForTest(
            projectKey = projectKey,
            namespace = "ns",
            statementId = "warmup",
            nowMs = 0L,
        )
        assertEquals("冷却窗口内必须跳过（建立 firstSeen 的锚点）", "cooldown", reason)
    }

    /** a) dumb=true（索引构建中）→ 一次检索都不跑。 */
    @Test
    fun `dumb态不预取`() {
        val reason = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "s1",
            dumb = true,
            nowMs = afterCooldown,
            search = { entry() },
        )
        assertEquals("dumb-mode", reason)
        assertEquals("dumb 态不得执行检索", 0, MbgExampleCallResolver.searchRuns)
    }

    /** b) 项目未初始化（打开早期）→ 不预取。 */
    @Test
    fun `项目未初始化不预取`() {
        val reason = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "s1",
            initialized = false,
            nowMs = afterCooldown,
            search = { entry() },
        )
        assertEquals("project-not-initialized", reason)
        assertEquals("项目未就绪不得执行检索", 0, MbgExampleCallResolver.searchRuns)
    }

    /** c) 项目打开后的冷却窗口内（避开索引构建与数据源加载）→ 不预取。 */
    @Test
    fun `冷却期内不预取`() {
        val reason = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "s1",
            nowMs = 0L,
            search = { entry() },
        )
        assertEquals("cooldown", reason)
        assertEquals(0, MbgExampleCallResolver.searchRuns)

        // 窗口内第二拍（t=COOLDOWN-1）仍不放行
        val stillCooling = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "s1",
            nowMs = MbgExampleCallResolver.PREFETCH_COOLDOWN_MS - 1,
            search = { entry() },
        )
        assertEquals("cooldown", stillCooling)
        assertEquals(0, MbgExampleCallResolver.searchRuns)
    }

    /** d) Registry 开关关闭（用户自救 / 线上分诊）→ 整段预取早退。 */
    @Test
    fun `Registry关闭不预取`() {
        val reason = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "s1",
            enabled = false,
            nowMs = afterCooldown,
            search = { entry() },
        )
        assertEquals("registry-off", reason)
        assertEquals(0, MbgExampleCallResolver.searchRuns)
    }

    /** e) smart + 已初始化 + 开关开 → 恰好一次检索；同 key 再来一次只命中缓存。 */
    @Test
    fun `smart且就绪时恰好检索一次`() {
        establishFirstSeen()

        val first = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "s1",
            nowMs = afterCooldown,
            search = { entry() },
        )
        assertNull("闸门全开时必须放行", first)
        assertEquals("放行后必须且只执行一次检索", 1, MbgExampleCallResolver.searchRuns)

        val second = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "s1",
            nowMs = afterCooldown + 1,
            search = { entry() },
        )
        assertEquals("缓存新鲜后不得重复检索", "cache-fresh", second)
        assertEquals(1, MbgExampleCallResolver.searchRuns)
    }

    /** f) 预算耗尽 → 不再预取（并且已经用掉的次数不再增加）。 */
    @Test
    fun `预算耗尽后不再预取`() {
        establishFirstSeen()

        repeat(MbgExampleCallResolver.PREFETCH_BUDGET_PER_PROJECT) { i ->
            val reason = MbgExampleCallResolver.prefetchForTest(
                projectKey = "p",
                namespace = "ns",
                statementId = "s$i",
                nowMs = afterCooldown + i,
                search = { entry() },
            )
            assertNull("预算内应放行（第 ${i + 1} 次）", reason)
        }
        assertEquals(MbgExampleCallResolver.PREFETCH_BUDGET_PER_PROJECT, MbgExampleCallResolver.searchRuns)

        val exhausted = MbgExampleCallResolver.prefetchForTest(
            projectKey = "p",
            namespace = "ns",
            statementId = "over",
            nowMs = afterCooldown + MbgExampleCallResolver.PREFETCH_BUDGET_PER_PROJECT,
            search = { entry() },
        )
        assertEquals("budget-exhausted", exhausted)
        assertEquals(
            "预算耗尽后不得再执行检索",
            MbgExampleCallResolver.PREFETCH_BUDGET_PER_PROJECT,
            MbgExampleCallResolver.searchRuns,
        )
    }
}