package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.util.FatalErrors
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * UI-005：体检取数的**有界执行器**（纯并发原语，零平台依赖）。
 *
 * 立项动因（真机缺陷）：旧实现把五项采集串成一条无上界的同步链，只要其中任一项在池线程上不返回，
 * 整张体检单就永不产出，设置页按钮与文案永久停在「体检中…」。这里给每一项加时间上界，并给整张
 * 单加总预算——超时项降级成「未采集（超时）」继续出单，绝不让一项拖死整单。
 *
 * 执行模型（顺序提交 + 足量线程池）：
 *  - 任务**逐个提交**（保持采集顺序、避免平台缓存互相竞争），但线程池按任务数建——某一项卡住被放弃后，
 *    池里仍有空闲线程接下一项，**不会级联超时**；
 *  - 单项预算取 `min(单项超时, 总预算剩余)`；总预算耗尽时后续项直接标「整体超时」不再提交；
 *  - 超时放弃 = `Future.cancel(true)`（只发中断），**不用 `Thread.stop`**。被放弃的任务可能仍在跑：
 *    本套采集全是只读取数（读设置/内存计数/平台缓存），不碰 UI、不写文档、不改设置，最坏是它稍后
 *    把结果写进自己的只读探测缓存（幂等、有 TTL），故放弃是安全的；[collect] 的返回值不受它影响。
 *
 * 可测性：时间预算与线程池都可注入——测试用注入的小预算 + 注入的池，在非沙盒 JVM 里跑全部超时分支。
 */
internal object EnvironmentCheckBounded {

    /** 单项采集预算（UI-005：3s 足够读完设置与平台缓存，又不会让用户盯着转圈）。 */
    const val ITEM_TIMEOUT_MS = 3_000L

    /** 整张体检单预算（5 项顺序采集的兜底上限；正常情况下总耗时远小于它）。 */
    const val TOTAL_TIMEOUT_MS = 9_000L

    /** 线程池上限：本套固定 5 项，池按任务数建即可；封顶防将来任务数膨胀时线程暴涨。 */
    private const val MAX_POOL_THREADS = 8

    /**
     * 逐项采集并施加时间上界。
     *
     * @param tasks          采集任务（标题键 + 取数函数），顺序即展示顺序
     * @param itemTimeoutMs  单项预算
     * @param totalTimeoutMs 总预算（从本函数进入时刻起算）
     * @param executorFactory 线程池工厂（测试注入；默认自建守护线程池并在返回时 shutdownNow）
     */
    fun run(
        tasks: List<EnvironmentCheckTask>,
        itemTimeoutMs: Long = ITEM_TIMEOUT_MS,
        totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
        executorFactory: ((Int) -> ExecutorService)? = null,
    ): List<EnvironmentCheckItem> {
        if (tasks.isEmpty()) return emptyList()
        val startedNs = System.nanoTime()
        val pool = executorFactory?.invoke(tasks.size)
            ?: newPool(minOf(tasks.size, MAX_POOL_THREADS))
        val ownsPool = executorFactory == null
        try {
            return tasks.map { task ->
                runOne(task, pool, startedNs, itemTimeoutMs, totalTimeoutMs)
            }
        } finally {
            // 放弃仍在跑的任务：只发中断；任务本身只读取数，不会碰 UI/文档/设置。
            if (ownsPool) runCatching { pool.shutdownNow() }
        }
    }

    /** 守护线程池（IDE 退出不被体检线程拖住）。 */
    internal fun newPool(threads: Int): ExecutorService {
        val seq = AtomicInteger()
        return Executors.newFixedThreadPool(maxOf(1, threads), ThreadFactory { r ->
            Thread(r, "mps-env-check-${seq.incrementAndGet()}").apply { isDaemon = true }
        })
    }

    private fun runOne(
        task: EnvironmentCheckTask,
        pool: ExecutorService,
        startedNs: Long,
        itemTimeoutMs: Long,
        totalTimeoutMs: Long,
    ): EnvironmentCheckItem {
        val remainingTotalMs = totalTimeoutMs - elapsedMs(startedNs)
        if (remainingTotalMs <= 0L) {
            // 总预算已在前面几项耗尽：不再提交，直接标整体超时（elapsed 记 0，未真正采集）。
            return EnvironmentCheckRules.degraded(
                titleKey = task.titleKey,
                outcome = CheckOutcome.TOTAL_TIMEOUT,
                elapsedMs = 0L,
                budgetMs = totalTimeoutMs,
            )
        }
        val budgetMs = minOf(itemTimeoutMs, remainingTotalMs)
        val itemStartNs = System.nanoTime()
        val future: Future<EnvironmentCheckItem> = try {
            pool.submit(Callable { task.collect() })
        } catch (t: Throwable) {
            // 池已关闭/拒绝提交：降级而非抛出，体检单必须照常产出。JVM 级致命错误照旧透传。
            if (FatalErrors.isFatal(t)) throw t
            return EnvironmentCheckRules.degraded(
                titleKey = task.titleKey,
                outcome = CheckOutcome.FAILED,
                elapsedMs = elapsedMs(itemStartNs),
                budgetMs = budgetMs,
                reason = briefReason(t),
            )
        }
        return try {
            future.get(budgetMs, TimeUnit.MILLISECONDS).copy(
                elapsedMs = elapsedMs(itemStartNs),
                outcome = CheckOutcome.COLLECTED,
            )
        } catch (e: TimeoutException) {
            future.cancel(true)
            // 单项预算没被总预算截断 ⇒ 单项超时；被截断 ⇒ 整体超时（文案要能区分）。
            val truncatedByTotal = budgetMs < itemTimeoutMs
            EnvironmentCheckRules.degraded(
                titleKey = task.titleKey,
                outcome = if (truncatedByTotal) CheckOutcome.TOTAL_TIMEOUT else CheckOutcome.ITEM_TIMEOUT,
                elapsedMs = elapsedMs(itemStartNs),
                budgetMs = if (truncatedByTotal) totalTimeoutMs else itemTimeoutMs,
            )
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            // 任务内抛出的 JVM 级致命错误不降级：照旧透传给上层（避免「带病继续跑整单」）。
            if (FatalErrors.isFatal(cause)) throw cause
            EnvironmentCheckRules.degraded(
                titleKey = task.titleKey,
                outcome = CheckOutcome.FAILED,
                elapsedMs = elapsedMs(itemStartNs),
                budgetMs = budgetMs,
                reason = briefReason(cause),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            EnvironmentCheckRules.degraded(
                titleKey = task.titleKey,
                outcome = CheckOutcome.FAILED,
                elapsedMs = elapsedMs(itemStartNs),
                budgetMs = budgetMs,
                reason = briefReason(e),
            )
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e
            EnvironmentCheckRules.degraded(
                titleKey = task.titleKey,
                outcome = CheckOutcome.FAILED,
                elapsedMs = elapsedMs(itemStartNs),
                budgetMs = budgetMs,
                reason = briefReason(e),
            )
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)

    /**
     * 失败原因摘要：异常类名 + 消息首行。
     *
     * 取首行是因为平台异常常把整段栈/多行上下文塞进 message——体检单要塞得进工单，一行就够定位。
     */
    internal fun briefReason(t: Throwable): String {
        val name = t.javaClass.simpleName.ifBlank { t.javaClass.name }
        val firstLine = t.message?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
        return if (firstLine == null) name else "$name: $firstLine"
    }
}

/**
 * 一项体检采集：标题键（降级时也要有标题）+ 取数函数。
 *
 * [titleKey] 与 [EnvironmentCheckRules] 里各规则使用的键同源（见 `TITLE_KEY_*` 常量），保证降级项
 * 与正常项的标题逐字一致——否则用户看到的「第 2 项叫什么」会随成败而变。
 */
internal class EnvironmentCheckTask(
    val titleKey: String,
    val collect: () -> EnvironmentCheckItem,
)
