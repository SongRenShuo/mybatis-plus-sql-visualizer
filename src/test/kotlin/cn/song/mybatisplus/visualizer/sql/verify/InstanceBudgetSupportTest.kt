package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.verify.InstanceBudgetSupport.Bucket
import cn.song.mybatisplus.visualizer.sql.verify.InstanceBudgetSupport.MatrixSummary
import cn.song.mybatisplus.visualizer.sql.verify.InstanceBudgetSupport.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [InstanceBudgetSupport] 的定向单测（T-MatrixInstanceTimeout 券 A）。
 *
 * **不摸真机、不碰 JDBC**：用「永久阻塞的假任务」把挂起形态搬进纯逻辑层——这是 P1（close/cancel 能否解开
 * socket 读）不成立时唯一仍然站得住的证据层，因此必须独立存在、不能寄生在真机矩阵里。
 *
 * 覆盖三件事：①到点判 TIMEOUT 且秒级收口；②坏实例不拖累后续实例；③TIMEOUT **不计入** PASS（只有矩阵里
 * 显式 `optional=true` 才降级，且降级行写作 `SKIP(超时)`）。
 */
class InstanceBudgetSupportTest {

    /** 反向验证兜底：看门狗若被摘掉，最迟这么久放行阻塞任务，避免整轮永久挂住（正常路径 1s 就返回）。 */
    private val safetyReleaseMs = 12_000L

    @Test
    fun `预算解析_实例条目优先于系统属性_兜底300s_非法值不取零`() {
        val byInstance = InstanceBudgetSupport.resolveBudgetSeconds("11", "22")
        assertEquals(11L, byInstance.seconds)
        assertTrue(byInstance.source, byInstance.source.contains("timeoutSeconds"))

        val bySystem = InstanceBudgetSupport.resolveBudgetSeconds(null, "22")
        assertEquals(22L, bySystem.seconds)
        assertTrue(bySystem.source, bySystem.source.contains(InstanceBudgetSupport.TIMEOUT_PROPERTY))

        val byDefault = InstanceBudgetSupport.resolveBudgetSeconds(null, null)
        assertEquals(InstanceBudgetSupport.DEFAULT_BUDGET_SECONDS, byDefault.seconds)
        assertEquals("默认", byDefault.source)

        // 空白容忍
        assertEquals(7L, InstanceBudgetSupport.resolveBudgetSeconds(" 7 ", null).seconds)

        // 非法值不生效、更不取 0（0 会把每条实例都判成超时，等于把配置错误伪装成全轮标红）
        listOf("0", "-5", "abc", "", "  ", "1.5").forEach { bad ->
            assertEquals(
                "实例条目 [$bad] 不该生效",
                InstanceBudgetSupport.DEFAULT_BUDGET_SECONDS,
                InstanceBudgetSupport.resolveBudgetSeconds(bad, null).seconds,
            )
        }
        // 实例条目非法时让位给系统属性，而不是直接兜底
        assertEquals(22L, InstanceBudgetSupport.resolveBudgetSeconds("0", "22").seconds)
        // 系统属性非法时兜底
        assertEquals(
            InstanceBudgetSupport.DEFAULT_BUDGET_SECONDS,
            InstanceBudgetSupport.resolveBudgetSeconds(null, "0").seconds,
        )
    }

    @Test
    fun `阻塞实例到点判TIMEOUT_后续实例照常执行_超时不进PASS桶`() {
        val latch = CountDownLatch(1)
        // 兜底放行线程：看门狗被摘掉时最迟 safetyReleaseMs 后解开阻塞，把「无限挂起」变成一次可判红的读数
        Thread {
            Thread.sleep(safetyReleaseMs)
            latch.countDown()
        }.apply { isDaemon = true }.start()

        val summary = MatrixSummary()
        val results = mutableListOf<InstanceBudgetSupport.InstanceResult>()
        for (key in listOf("blocked_instance", "fast_a", "fast_b")) {
            val result = if (key == "blocked_instance") {
                // 「永久阻塞」的假任务：只看门狗能把它切断
                InstanceBudgetSupport.runWithBudget(key, budgetSeconds = 1) {
                    latch.await()
                    "不该走到这里（看门狗缺席）"
                }
            } else {
                InstanceBudgetSupport.runWithBudget(key, budgetSeconds = 60) { "words=100" }
            }
            results += result
            summary.record(result, optional = false)
            latch.countDown()
        }

        // ① 到点判 TIMEOUT，且秒级收口
        assertEquals(Outcome.TIMEOUT, results[0].outcome)
        assertTrue("超时明细必须带预算值：${results[0].detail}", results[0].detail.contains("预算 1s"))
        assertTrue(
            "超时应秒级收口，实测 ${results[0].elapsedMs}ms（看门狗被摘掉时这里会退化到兜底放行）",
            results[0].elapsedMs < 5_000,
        )
        assertEquals(1L, results[0].budgetSeconds)

        // ② 坏实例不拖累后续实例
        assertEquals(Outcome.PASS, results[1].outcome)
        assertEquals(Outcome.PASS, results[2].outcome)

        // ③ TIMEOUT 不计入 PASS，也不静默跳过
        assertEquals(2, summary.passed.size)
        assertEquals(1, summary.timedOut.size)
        assertEquals(0, summary.skipped.size)
        assertEquals(0, summary.failed.size)
        assertTrue(summary.hasPass)
        assertFalse(
            "超时实例不得进 PASS 桶：${summary.passed}",
            summary.passed.any { it.contains("blocked_instance") },
        )
        assertTrue(summary.timedOut[0].startsWith("TIMEOUT  blocked_instance  "))
        assertEquals("通过 2 / 跳过 0 / 超时 1 / 失败 0", summary.countsLine())
    }

    @Test
    fun `超时处置按序执行并逐步骤取证_线程被中断即不标放弃`() {
        val latch = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val result = InstanceBudgetSupport.runWithBudget(
            key = "cleanup",
            budgetSeconds = 1,
            cleanupSteps = listOf(
                "statement.cancel" to { order += "statement.cancel" },
                "connection.close" to { order += "connection.close" },
            ),
        ) {
            // 可中断的阻塞（与挂 Socket 读同形：看门狗不切，它就不返回）
            latch.await(10, TimeUnit.SECONDS)
            "不该走到这里"
        }

        assertEquals(Outcome.TIMEOUT, result.outcome)
        assertEquals(
            "处置顺序必须是 cancel → close，再交给 thread.interrupt",
            listOf("statement.cancel", "connection.close"),
            order.toList(),
        )
        assertEquals(3, result.cleanupSteps.size)
        assertTrue(result.cleanupSteps[0], result.cleanupSteps[0].startsWith("statement.cancel✓"))
        assertTrue(result.cleanupSteps[1], result.cleanupSteps[1].startsWith("connection.close✓"))
        assertTrue(result.cleanupSteps[2], result.cleanupSteps[2].startsWith("thread.interrupt✓"))
        assertFalse("interrupt 生效后线程应已退出，不该标放弃", result.abandoned)
        assertTrue(result.detail.contains("线程已退出"))
        latch.countDown()
    }

    @Test
    fun `处置步骤抛错只记该步失败_不中断后续步骤`() {
        val latch = CountDownLatch(1)
        val result = InstanceBudgetSupport.runWithBudget(
            key = "cleanup_error",
            budgetSeconds = 1,
            cleanupSteps = listOf(
                // 真机现状：驱动在挂起态调 cancel 常常自己抛（或自己卡住），不能因此吞掉后面的 close
                "statement.cancel" to { throw IllegalStateException("cancel 自身不可用") },
                "connection.close" to { },
            ),
        ) {
            latch.await(10, TimeUnit.SECONDS)
            "不该走到这里"
        }

        assertEquals(Outcome.TIMEOUT, result.outcome)
        assertTrue(result.cleanupSteps[0], result.cleanupSteps[0].startsWith("statement.cancel✗"))
        assertTrue(result.cleanupSteps[0], result.cleanupSteps[0].contains("cancel 自身不可用"))
        assertTrue(result.cleanupSteps[1], result.cleanupSteps[1].startsWith("connection.close✓"))
        latch.countDown()
    }

    @Test
    fun `超时默认落TIMEOUT桶_只有optional降级且写作SKIP带超时标注`() {
        val timeout = InstanceBudgetSupport.InstanceResult(
            key = "panwei_a",
            outcome = Outcome.TIMEOUT,
            detail = "预算 1s  处置=[statement.cancel✓ connection.close✓ thread.interrupt✓]  线程已退出  耗时 1200ms",
            elapsedMs = 1_200,
            budgetSeconds = 1,
        )

        val strict = MatrixSummary().apply { record(timeout, optional = false) }
        assertEquals(1, strict.timedOut.size)
        assertEquals(0, strict.passed.size)
        assertEquals(0, strict.skipped.size)
        assertEquals(0, strict.failed.size)
        assertTrue(strict.timedOut[0].startsWith("TIMEOUT  panwei_a  "))
        assertTrue("超时行必须带预算值", strict.timedOut[0].contains("预算 1s"))
        assertEquals(Bucket.TIMEOUT, MatrixSummary.bucketOf(timeout, optional = false))

        val lenient = MatrixSummary().apply { record(timeout, optional = true) }
        assertEquals(0, lenient.timedOut.size)
        assertEquals(1, lenient.skipped.size)
        assertTrue(lenient.skipped[0].startsWith("SKIP(超时)  panwei_a  "))
        assertFalse("降级后的超时不得混进 PASS", lenient.passed.any { it.contains("panwei_a") })
        assertEquals(Bucket.SKIP, MatrixSummary.bucketOf(timeout, optional = true))

        // PASS 恒 PASS：optional 不改判已经通过的实例
        val pass = InstanceBudgetSupport.InstanceResult(
            key = "pg16",
            outcome = Outcome.PASS,
            detail = "PostgreSQL  words=86",
            elapsedMs = 1_000,
            budgetSeconds = 300,
        )
        val passSummary = MatrixSummary().apply { record(pass, optional = true) }
        assertEquals(1, passSummary.passed.size)
        assertTrue(passSummary.passed[0].startsWith("PASS  pg16  "))
        assertTrue("PASS 行尾保留耗时读数", passSummary.passed[0].endsWith("1000ms"))
        assertEquals(Bucket.PASS, MatrixSummary.bucketOf(pass, optional = true))

        // FAIL 的常规落桶与 optional 降级
        val fail = InstanceBudgetSupport.InstanceResult("dm8", Outcome.FAIL, "ORA-00001", 900, 300)
        assertEquals(Bucket.FAIL, MatrixSummary.bucketOf(fail, optional = false))
        assertEquals(Bucket.SKIP, MatrixSummary.bucketOf(fail, optional = true))
    }
}
