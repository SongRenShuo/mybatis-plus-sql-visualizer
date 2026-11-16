package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.xml.XmlTag
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * T-GutterFirstOpenDelay F2：gutter 首渲染三条预热链（MBG Example 预取 / 补全上下文预热 /
 * 装配预热）的**统一延迟调度点**。
 *
 * ## 为什么（计划 §二/§三 P-4）
 *
 * 冷打开窗口里首渲染同步踢出的三条预热与快/慢趟 pass、索引重建抢 BGT——实测首开重活：
 * 反查索引 3337ms + 跨 ORM 回补 15017ms + assembly warm 17095ms。把「踢的时刻」后移到
 * 「工程 smart 后 N 毫秒再踢一次」，缩小冷窗 BGT 竞争面（同时降低 S2 撞窗概率）。
 *
 * ## 调度语义（选定口径，边角见各条）
 *
 * - **延迟锚点＝本会话内最近一次观察到工程 smart 的时刻**（per project）。渲染时观察到 dumb
 *   ⇒ 清锚点并放弃本轮（等下次渲染）——锚点重置意味着索引重建窗后延迟重新起算。锚点不早于
 *   真实 smart 进入时刻，故实际延迟 ≥ 配置值，竞争收窄方向保守。
 * - 渲染时工程已 smart 且距锚点 ≥ 延迟 ⇒ **立即踢**（暖重开等场景行为与现状一致）。
 * - 还没到延迟窗 ⇒ 用 `AppExecutorUtil.getAppScheduledExecutorService().schedule` 安排一次性
 *   延迟派发（243 平台 API，`MbgExampleCallResolver` 超时取消同款通路）；到点后复查「工程未关、
 *   已 smart、文件仍在」再踢，**到点仍 dumb 就放弃本轮等下次渲染**——与三链既有 dumb 跳过口径一致。
 * - **每文件一次的调度去重**：延迟窗内同一文件的重复渲染只排一次（按 VirtualFile.url）；
 *   到点踢出/放弃后去重即撤销。三链各自的每文件去重（`warmedContextFiles`、
 *   `PreviewAssemblyWarm.warmedFiles`）不动——延迟派发只把「踢的时刻」后移，不产生重复踢。
 * - **`warmedContextFiles.add` 等去重标记的时机随踢出后移**（到点才记）：延迟窗内文件被关闭
 *   ⇒ 到点 `tag.isValid=false` 放弃本轮且三链未记去重 ⇒ 重新打开时重新调度，等价「没预热过」；
 *   预热已落地的文件关闭重开 ⇒ 三链按 URL 去重早退，不会重复预热。
 * - **`sqlvis.gutter.warmDelayMs` = 0 ＝ 旧行为**：首渲染立即踢（回退通道），且不经 dumb
 *   预检——三链自带的 dumb 检查保持原语义。
 * - **延迟派发体在读动作内执行**（T-GutterWarmReadAction，2026-11-16 真机 SEVERE 实证）：
 *   `AppScheduledExecutorService` 的池线程**不持读动作**，而「到点复检」的 `tag.isValid` 与三链
 *   同步段的 `tag.containingFile`/`getAttributeValue` 都是 PSI 读——不包读动作会抛
 *   `Read access is allowed from inside read-action only`（沙盒一次连出 3 条 SEVERE）。三链内部
 *   重活各自开 `ReadAction.nonBlocking`，此处只需把「复检 + 踢出」整体包一次读动作。
 *
 * 调度失败（极端场景 executor 不可用）退化成立即踢：预热是有价值的，失败不该把它丢掉。
 */
internal object GutterWarmScheduler {

    private val LOG = Logger.getInstance(GutterWarmScheduler::class.java)

    /** Registry 键（plugin.xml `<registryKey>` 登记）；0 = 回退到「首渲染立即踢」旧行为。 */
    internal const val REGISTRY_KEY = "sqlvis.gutter.warmDelayMs"

    /** 默认延迟：把首渲染踢出的预热推迟 5s，让冷打开窗口期的快/慢趟 pass 与索引先跑。 */
    internal const val DEFAULT_DELAY_MS = 5000

    /** 测试缝：延迟值读取（生产＝Registry；键未登记等异常兜底回默认值）。 */
    internal var delayMsProvider: () -> Long = { readDelayMs() }

    /** 测试缝：时钟（生产＝System.currentTimeMillis）。 */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** 测试缝：延迟派发同步执行（真实环境走调度线程池）；null＝按单元测试模式判定。 */
    internal var syncDispatchForTest: Boolean? = null

    /** 已排定待踢的文件（按 VirtualFile.url；踢出/放弃即移除，防延迟窗内重复排）。 */
    private val scheduledFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 每项目的 smart 锚点（project.locationHash → 最近一次观察到 smart 的时刻）。 */
    private val smartSincePerProject: MutableMap<String, Long> = ConcurrentHashMap()

    internal fun resetForTest() {
        scheduledFiles.clear()
        smartSincePerProject.clear()
        delayMsProvider = { readDelayMs() }
        nowMs = System::currentTimeMillis
        syncDispatchForTest = null
    }

    /**
     * 判定内核（纯函数，单测矩阵钉死）：
     * - dumb ⇒ 不踢（等下次渲染，与三链既有 dumb 跳过口径一致）；
     * - 延迟 ≤ 0 ⇒ 立即踢（旧行为回退通道）；
     * - smart 后经过时长 ≥ 延迟 ⇒ 立即踢；
     * - 其余 ⇒ 还没到延迟窗，走延迟派发。
     */
    internal fun shouldKickNow(dumb: Boolean, msSinceProjectSmart: Long, delayMs: Long): Boolean =
        !dumb && (delayMs <= 0L || msSinceProjectSmart >= delayMs)

    /**
     * gutter 首渲染预热入口：替代原先在 `getLineMarkerInfo` 里的三条直踢。
     * 幂等——调用多少次都行，调度去重 + 三链自身去重兜底。
     */
    internal fun onGutterFirstRender(tag: XmlTag) {
        val delayMs = delayMsProvider()
        if (delayMs <= 0L) {
            // 0 = 旧行为：立即踢，不做 dumb 预检（三链自带的 dumb 检查保持原语义）
            XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag)
            return
        }
        val project = tag.project
        if (project.isDisposed) return
        val dumb = runCatching { DumbService.isDumb(project) }.getOrDefault(true)
        val fileUrl = tag.containingFile?.virtualFile?.url ?: return
        if (dumb) {
            // 索引/重建窗：清锚点（smart 后重新起算）并放弃本轮，等下次渲染
            smartSincePerProject.remove(project.locationHash)
            return
        }
        smartSincePerProject.putIfAbsent(project.locationHash, nowMs())
        val msSinceSmart = nowMs() - smartSincePerProject.getValue(project.locationHash)
        if (shouldKickNow(dumb = false, msSinceProjectSmart = msSinceSmart, delayMs = delayMs)) {
            XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag)
            return
        }
        if (!scheduledFiles.add(fileUrl)) return // 延迟窗内重复渲染：已排定，不重复排
        scheduleWarmIfDue(project, tag, fileUrl, delayMs - msSinceSmart)
    }

    /**
     * 一次性延迟派发：到点复查「工程未关、已 smart、文件仍在」，通过才踢；
     * 任何复查不过都放弃本轮（去重已撤，下次渲染重新走判定）。
     */
    private fun scheduleWarmIfDue(project: Project, tag: XmlTag, fileUrl: String, remainingMs: Long) {
        val fire = Runnable {
            scheduledFiles.remove(fileUrl)
            val app = ApplicationManager.getApplication() ?: return@Runnable
            // T-GutterWarmReadAction（2026-11-16 真机 SEVERE 实证）：本 Runnable 跑在
            // `AppScheduledExecutorService` 的池线程上，**不持读动作**，而「到点复检」的
            // `tag.isValid` 与三条预热链同步段的 `tag.containingFile` / `getAttributeValue`
            // 都是 PSI 读——不包读动作即抛
            // `Read access is allowed from inside read-action only`（沙盒 12:35:42 一次连出 3 条）。
            // 三链内部的重活各自开 `ReadAction.nonBlocking`，这里只需把「复检 + 踢出」包成一次读动作。
            runCatching {
                app.runReadAction(
                    Computable {
                        val due = !project.isDisposed &&
                            !runCatching { DumbService.isDumb(project) }.getOrDefault(true) &&
                            tag.isValid
                        if (due) XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag)
                    },
                )
            }.onFailure { t ->
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t
            }
        }
        val sync = syncDispatchForTest
            ?: (runCatching { ApplicationManager.getApplication()?.isUnitTestMode }.getOrDefault(false) == true)
        if (sync) {
            fire.run()
            return
        }
        runCatching {
            AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(fire, remainingMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }.onFailure { t ->
            LOG.info("[open-perf] gutter warm schedule failed ${t.javaClass.simpleName}，退化为立即踢")
            scheduledFiles.remove(fileUrl)
            XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag)
        }
    }

    private fun readDelayMs(): Long =
        runCatching { Registry.intValue(REGISTRY_KEY, DEFAULT_DELAY_MS) }
            .getOrDefault(DEFAULT_DELAY_MS)
            .toLong()
}
