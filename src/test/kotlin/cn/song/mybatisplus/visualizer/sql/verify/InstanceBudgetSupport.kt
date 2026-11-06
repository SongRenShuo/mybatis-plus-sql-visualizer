package cn.song.mybatisplus.visualizer.sql.verify

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 外部实例矩阵的**实例级预算与看门狗**（T-MatrixInstanceTimeout 券 A，2026-11-06）。
 *
 * 为什么单独立一个类（而不是塞进 [DialectVerifySupport]）：
 *  - [DialectVerifySupport] 的职责是「插件渲染 SQL 的构造与执行」，本类的职责是「一个实例跑多久算没进展、
 *    到点怎么收尾、结局怎么归档」——两者生命周期不同（前者可被 H2/SQLite/Docker 各入口复用，后者只服务
 *    真机外部矩阵）；
 *  - 本类必须能在**零真机、零 JDBC**的条件下被单测覆盖（注入永久阻塞的假任务即可），独立的类顺带把
 *    「可测面」与「真机面」切开：`InstanceBudgetSupportTest` 进快桶，真机矩阵仍只在 `dialectExternalVerify`。
 *
 * 背景（本轮实测，不是推断）：`DialectExternalMatrixVerificationTest` 原来是「串行 for + try/catch(Throwable)」，
 * 唯一的超时是 `DriverManager.setLoginTimeout(20)`——它只管**登录**。实例侧一旦挂在 socket 读里
 * （`PGStream.receiveChar` → `SocketDispatcher.read0`，vastbase / panwei_a 两次实证；SQL Server 是
 * `TDSChannel.read`），既不抛也不返回，`catch` 永不进入，整轮无限期停在该实例上，只能人工杀进程；
 * 而强杀又曾把共享 Gradle transform 解包窗口打断，连带损坏 `idea64.exe.caches`（见计划册 §一）。
 *
 * 本类的语义钉子（对应计划册 §五）：
 *  1. 预算取值优先级：[resolveBudgetSeconds]——实例条目 `timeoutSeconds` → `-Dmpvs.external.instanceTimeout`
 *     （秒）→ 默认 [DEFAULT_BUDGET_SECONDS]；非正数/非数字一律视为「没给」，绝不静默成 0（0 会把每个实例
 *     都当成超时）。
 *  2. 每实例一个**独立单线程 daemon executor**；`future.get(budget)` 超时即判 [Outcome.TIMEOUT]。
 *     独立 executor 是「坏实例不影响下一个」的实现基础，daemon 是「被放弃的线程不阻止 JVM 退出」的实现基础。
 *  3. 超时处置按序执行，**每步自带上限**（[CLEANUP_STEP_TIMEOUT_MS]）：`statement.cancel()` →
 *     `connection.close()` → `thread.interrupt()`。步内上限不可省——`cancel()` 在部分驱动里要新开一条连接
 *     等服务端回应，真挂住时会把看门狗线程自己也搭进去（那就等于没有看门狗）。
 *  4. 处置后按 [ABANDON_GRACE_MS] 宽限等一次；仍未结束即标 [InstanceResult.abandoned] 放弃（线程是 daemon，
 *     留一个挂起的 daemon 线程换整轮能收口）。
 *  5. 结局四桶由 [MatrixSummary.bucketOf] 归档：`PASS / SKIP / TIMEOUT / FAIL`。**超时不计 PASS**，
 *     也**不静默跳过**——只有矩阵条目显式 `optional=true` 才降级，且降级行写作 `SKIP(超时)`。
 */
object InstanceBudgetSupport {

    /** 预算兜底（秒）：实例条目与系统属性都没给时用这个。 */
    const val DEFAULT_BUDGET_SECONDS = 300L

    /** 全局预算系统属性名（秒）；实例条目 `timeoutSeconds` 优先于它。 */
    const val TIMEOUT_PROPERTY = "mpvs.external.instanceTimeout"

    /** 超时处置每一步的上限（毫秒）。 */
    const val CLEANUP_STEP_TIMEOUT_MS = 2_000L

    /** 处置后等待被放弃线程自己退出的宽限（毫秒）。 */
    const val ABANDON_GRACE_MS = 1_000L

    /** 每实例工作线程名前缀（报告里取证线程名与 daemon 标记用）。 */
    const val THREAD_NAME_PREFIX = "mpv-matrix-"

    /** 单实例结局。`SKIP` 只由 `optional=true` 降级产生，看门狗自身不产生它。 */
    enum class Outcome { PASS, SKIP, TIMEOUT, FAIL }

    /** 归档桶（[Outcome] 经 `optional` 折算后的落桶）。 */
    enum class Bucket { PASS, SKIP, TIMEOUT, FAIL }

    /** 预算解析结果：值 + 来源（报告里点名，避免「预算到底生效没有」只能靠猜）。 */
    data class BudgetResolution(val seconds: Long, val source: String)

    /** 单实例结局 + 取证（耗时、预算、处置步骤、是否放弃线程）。 */
    data class InstanceResult(
        val key: String,
        val outcome: Outcome,
        val detail: String,
        val elapsedMs: Long,
        val budgetSeconds: Long,
        val cleanupSteps: List<String> = emptyList(),
        val abandoned: Boolean = false,
    )

    /**
     * 预算解析（实例条目优先于全局）。
     *
     * 解析不出来（null / 空白 / 非数字 / ≤ 0）一律视为「没给」并落到下一档——**不取 0**：
     * 0 会让每条实例都「立刻超时」，那是把配置写错伪装成全轮标红。
     */
    fun resolveBudgetSeconds(instanceValue: String?, systemValue: String?): BudgetResolution {
        parsePositive(instanceValue)?.let { return BudgetResolution(it, "实例条目 timeoutSeconds") }
        parsePositive(systemValue)?.let { return BudgetResolution(it, "系统属性 -D$TIMEOUT_PROPERTY") }
        return BudgetResolution(DEFAULT_BUDGET_SECONDS, "默认")
    }

    private fun parsePositive(value: String?): Long? =
        value?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * 在**独立**单线程 daemon executor 上跑一个实例，到点未完成即判 [Outcome.TIMEOUT]。
     *
     * @param cleanupSteps 超时处置步骤（按序），名字用于报告取证（例如 `statement.cancel` / `connection.close`）；
     *                     数据面由调用方提供（本类不认 JDBC 类型）。
     * @param task 实例工作；正常返回即 PASS（返回值进明细），抛异常即 FAIL（异常摘要进明细）。
     */
    fun runWithBudget(
        key: String,
        budgetSeconds: Long,
        cleanupSteps: List<Pair<String, () -> Unit>> = emptyList(),
        task: () -> String,
    ): InstanceResult {
        val finished = AtomicBoolean(false)
        val workerError = AtomicReference<Throwable?>(null)
        val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME_PREFIX + key).apply { isDaemon = true }
        }
        val startedAt = System.currentTimeMillis()
        try {
            val future = executor.submit(
                Callable {
                    try {
                        TaskOutcome(detail = task(), error = null)
                    } catch (t: Throwable) {
                        workerError.set(t)
                        TaskOutcome(detail = null, error = t)
                    } finally {
                        finished.set(true)
                    }
                },
            )
            return try {
                // 看门狗本体。反向验证「摘掉看门狗」＝把这一行换成无参的 future.get()（见计划册 §五）。
                val outcome = future.get(budgetSeconds, TimeUnit.SECONDS)
                val elapsed = System.currentTimeMillis() - startedAt
                if (outcome.error == null) {
                    InstanceResult(key, Outcome.PASS, outcome.detail.orEmpty(), elapsed, budgetSeconds)
                } else {
                    InstanceResult(key, Outcome.FAIL, brief(outcome.error), elapsed, budgetSeconds)
                }
            } catch (_: TimeoutException) {
                val steps = mutableListOf<String>()
                cleanupSteps.forEach { (name, action) -> steps += boundedStep(name, action) }
                steps += boundedStep("thread.interrupt") { future.cancel(true) }
                val abandoned = !awaitFinished(finished, ABANDON_GRACE_MS)
                val elapsed = System.currentTimeMillis() - startedAt
                // 线程退出原因是 P1（close/cancel 能否解开挂起的 socket 读）的现场证据：若退出原因是
                // 驱动抛出的「连接已关闭/socket closed」一类异常，说明处置真的把阻塞读打断了。
                val exitReason = if (abandoned) {
                    "线程未退出（daemon 放弃）"
                } else {
                    "线程已退出：${workerError.get()?.let { brief(it) } ?: "正常返回"}"
                }
                val detail = "预算 ${budgetSeconds}s  处置=[${steps.joinToString(" ")}]  $exitReason" +
                    "  耗时 ${elapsed}ms"
                InstanceResult(key, Outcome.TIMEOUT, detail, elapsed, budgetSeconds, steps, abandoned)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** 处置步骤带上限执行；返回取证串（完成 ✓ / 抛错 ✗(原因) / 到点未返回 ✗(超时)）。 */
    private fun boundedStep(name: String, action: () -> Unit): String {
        val done = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val thread = Thread(
            {
                try {
                    action()
                    done.set(true)
                } catch (t: Throwable) {
                    error.set(t)
                }
            },
            "mpv-matrix-cleanup-$name",
        ).apply { isDaemon = true }
        thread.start()
        thread.join(CLEANUP_STEP_TIMEOUT_MS)
        val failure = error.get()
        return when {
            done.get() -> "$name✓"
            failure != null -> "$name✗(${brief(failure)})"
            else -> "$name✗(超时未返回)"
        }
    }

    /** 宽限内等 `finished` 置位；返回是否已结束。 */
    private fun awaitFinished(finished: AtomicBoolean, graceMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < deadline) {
            if (finished.get()) return true
            Thread.sleep(20)
        }
        return finished.get()
    }

    /** 异常摘要：取前三行、限长（与矩阵原 FAIL 明细同口径）。 */
    private fun brief(t: Throwable): String =
        (t.message ?: t::class.java.simpleName)
            .lineSequence().take(3).joinToString(" / ")
            .take(600)

    private class TaskOutcome(val detail: String?, val error: Throwable?)

    /**
     * 整轮归档：四桶 + 汇总行 + 逐桶明细行。
     *
     * 汇总行口径固定为 `通过 X / 跳过 Y / 超时 Z / 失败 W`；`optional=true` 是 TIMEOUT/FAIL 的**唯一**降级通道，
     * 且超时降级行写成 `SKIP(超时)`（既保住 optional 的既有语义，又让「这里其实没验」在 summary 里一眼可见）。
     */
    class MatrixSummary {
        val passed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val timedOut = mutableListOf<String>()
        val failed = mutableListOf<String>()

        /**
         * 记账（可并发调用）。
         *
         * T-DialectSweepSpeed（2026-11-06）：实例并行后，若调用方选择「完成即记账」就会并发写这四个桶；
         * 加锁让本类自身线程安全，落桶口径不变。**输出顺序仍由调用方保证按 `instances` 声明顺序记账**
         * ——[allLines] 是「桶序 + 桶内记账序」，锁不会替它排序。
         */
        @Synchronized
        fun record(result: InstanceResult, optional: Boolean) {
            val line = lineOf(result, optional)
            when (bucketOf(result, optional)) {
                Bucket.PASS -> passed += line
                Bucket.SKIP -> skipped += line
                Bucket.TIMEOUT -> timedOut += line
                Bucket.FAIL -> failed += line
            }
        }

        /** 明细行顺序＝桶序（通过 / 跳过 / 超时 / 失败）。 */
        fun allLines(): List<String> = passed + skipped + timedOut + failed

        fun countsLine(): String =
            "通过 ${passed.size} / 跳过 ${skipped.size} / 超时 ${timedOut.size} / 失败 ${failed.size}"

        val hasPass: Boolean get() = passed.isNotEmpty()

        companion object {
            /** 落桶：PASS 恒 PASS（optional 不改判）；TIMEOUT/FAIL 在 optional 下降级为 SKIP。 */
            fun bucketOf(result: InstanceResult, optional: Boolean): Bucket = when {
                result.outcome == Outcome.PASS -> Bucket.PASS
                result.outcome == Outcome.SKIP -> Bucket.SKIP
                optional -> Bucket.SKIP
                result.outcome == Outcome.TIMEOUT -> Bucket.TIMEOUT
                else -> Bucket.FAIL
            }

            /** 明细行：`<桶名>  <key>  <明细>[  耗时]`。超时降级行写作 `SKIP(超时)`。 */
            fun lineOf(result: InstanceResult, optional: Boolean): String {
                val word = when {
                    result.outcome == Outcome.PASS -> "PASS"
                    result.outcome == Outcome.TIMEOUT && optional -> "SKIP(超时)"
                    optional -> "SKIP"
                    result.outcome == Outcome.TIMEOUT -> "TIMEOUT"
                    else -> "FAIL"
                }
                val tail = if (result.outcome == Outcome.PASS) "  ${result.elapsedMs}ms" else ""
                return "$word  ${result.key}  ${result.detail}$tail"
            }
        }
    }
}
