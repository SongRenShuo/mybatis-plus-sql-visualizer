package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * T-GutterFirstOpenDelay F2：gutter 首渲染三条预热链（MBG Example 预取 / 补全上下文预热 /
 * 装配预热）的<b>统一延迟调度点</b>。
 *
 * <h2>为什么（计划 §二/§三 P-4）</h2>
 *
 * <p>冷打开窗口里首渲染同步踢出的三条预热与快/慢趟 pass、索引重建抢 BGT——实测首开重活：
 * 反查索引 3337ms + 跨 ORM 回补 15017ms + assembly warm 17095ms。把「踢的时刻」后移到
 * 「工程 smart 后 N 毫秒再踢一次」，缩小冷窗 BGT 竞争面（同时降低 S2 撞窗概率）。</p>
 *
 * <h2>调度语义（选定口径，边角见各条）</h2>
 *
 * <ul>
 * <li><b>延迟锚点＝本会话内最近一次观察到工程 smart 的时刻</b>（per project）。渲染时观察到 dumb
 *     ⇒ 清锚点并放弃本轮（等下次渲染）——锚点重置意味着索引重建窗后延迟重新起算。锚点不早于
 *     真实 smart 进入时刻，故实际延迟 ≥ 配置值，竞争收窄方向保守。</li>
 * <li>渲染时工程已 smart 且距锚点 ≥ 延迟 ⇒ <b>立即踢</b>（暖重开等场景行为与现状一致）。</li>
 * <li>还没到延迟窗 ⇒ 用 {@code AppExecutorUtil.getAppScheduledExecutorService().schedule} 安排
 *     一次性延迟派发（243 平台 API，MbgExampleCallResolver 超时取消同款通路）；到点后复查
 *     「工程未关、已 smart、文件仍在」再踢，<b>到点仍 dumb 就放弃本轮等下次渲染</b>——
 *     与三链既有 dumb 跳过口径一致。</li>
 * <li><b>每文件一次的调度去重</b>：延迟窗内同一文件的重复渲染只排一次（按 VirtualFile.url）；
 *     到点踢出/放弃后去重即撤销。三链各自的每文件去重（warmedContextFiles、
 *     PreviewAssemblyWarm.warmedFiles）不动——延迟派发只把「踢的时刻」后移，不产生重复踢。</li>
 * <li><b>去重标记的时机随踢出后移</b>（到点才记）：延迟窗内文件被关闭 ⇒ 到点 tag.isValid=false
 *     放弃本轮且三链未记去重 ⇒ 重新打开时重新调度，等价「没预热过」；预热已落地的文件关闭重开
 *     ⇒ 三链按 URL 去重早退，不会重复预热。</li>
 * <li><b>{@code sqlvis.gutter.warmDelayMs} = 0 ＝ 旧行为</b>：首渲染立即踢（回退通道），且不经
 *     dumb 预检——三链自带的 dumb 检查保持原语义。</li>
 * <li><b>延迟派发体在读动作内执行</b>（T-GutterWarmReadAction，2026-11-16 真机 SEVERE 实证）：
 *     AppScheduledExecutorService 的池线程<b>不持读动作</b>，而「到点复检」的 tag.isValid 与三链
 *     同步段的 tag.containingFile/getAttributeValue 都是 PSI 读——不包读动作会抛
 *     {@code Read access is allowed from inside read-action only}（沙盒一次连出 3 条 SEVERE）。
 *     三链内部重活各自开 ReadAction.nonBlocking，此处只需把「复检 + 踢出」整体包一次读动作。</li>
 * </ul>
 *
 * <p>调度失败（极端场景 executor 不可用）退化成立即踢：预热是有价值的，失败不该把它丢掉。</p>
 */
public final class GutterWarmScheduler {

    private static final Logger LOG = Logger.getInstance(GutterWarmScheduler.class);

    /** Registry 键（plugin.xml registryKey 登记）；0 = 回退到「首渲染立即踢」旧行为。 */
    public static final String REGISTRY_KEY = "sqlvis.gutter.warmDelayMs";

    /** 默认延迟：把首渲染踢出的预热推迟 5s，让冷打开窗口期的快/慢趟 pass 与索引先跑。 */
    public static final int DEFAULT_DELAY_MS = 5000;

    /** 测试缝：延迟值读取（生产＝Registry；键未登记等异常兜底回默认值）。 */
    public static volatile LongSupplier delayMsProvider = GutterWarmScheduler::readDelayMs;

    /** 测试缝：时钟（生产＝System.currentTimeMillis）。 */
    public static volatile LongSupplier nowMs = System::currentTimeMillis;

    /** 测试缝：延迟派发同步执行（真实环境走调度线程池）；null＝按单元测试模式判定。 */
    public static volatile Boolean syncDispatchForTest = null;

    /** 已排定待踢的文件（按 VirtualFile.url；踢出/放弃即移除，防延迟窗内重复排）。 */
    private static final Set<String> SCHEDULED_FILES = ConcurrentHashMap.newKeySet();

    /** 每项目的 smart 锚点（project.locationHash → 最近一次观察到 smart 的时刻）。 */
    private static final Map<String, Long> SMART_SINCE_PER_PROJECT = new ConcurrentHashMap<>();

    /** Kotlin object 语义兼容实例。 */
    public static final GutterWarmScheduler INSTANCE = new GutterWarmScheduler();

    private GutterWarmScheduler() {
    }

    /** 测试缝：清空全部状态并复位两个缝。 */
    public static void resetForTest() {
        SCHEDULED_FILES.clear();
        SMART_SINCE_PER_PROJECT.clear();
        delayMsProvider = GutterWarmScheduler::readDelayMs;
        nowMs = System::currentTimeMillis;
        syncDispatchForTest = null;
    }

    /**
     * 判定内核（纯函数，单测矩阵钉死）：dumb ⇒ 不踢（等下次渲染，与三链既有 dumb 跳过口径一致）；
     * 延迟 ≤ 0 ⇒ 立即踢（旧行为回退通道）；smart 后经过时长 ≥ 延迟 ⇒ 立即踢；其余 ⇒ 还没到延迟窗，
     * 走延迟派发。
     */
    public static boolean shouldKickNow(boolean dumb, long msSinceProjectSmart, long delayMs) {
        return !dumb && (delayMs <= 0L || msSinceProjectSmart >= delayMs);
    }

    /**
     * gutter 首渲染预热入口：替代原先在 getLineMarkerInfo 里的三条直踢。
     * 幂等——调用多少次都行，调度去重 + 三链自身去重兜底。
     */
    public static void onGutterFirstRender(@NotNull XmlTag tag) {
        long delayMs = delayMsProvider.getAsLong();
        if (delayMs <= 0L) {
            // 0 = 旧行为：立即踢，不做 dumb 预检（三链自带的 dumb 检查保持原语义）
            XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag);
            return;
        }
        Project project = tag.getProject();
        if (project.isDisposed()) {
            return;
        }
        boolean dumb;
        try {
            dumb = DumbService.isDumb(project);
        } catch (Throwable t) {
            dumb = true;
        }
        String fileUrl = tag.getContainingFile() != null && tag.getContainingFile().getVirtualFile() != null
                ? tag.getContainingFile().getVirtualFile().getUrl() : null;
        if (fileUrl == null) {
            return;
        }
        if (dumb) {
            // 索引/重建窗：清锚点（smart 后重新起算）并放弃本轮，等下次渲染
            SMART_SINCE_PER_PROJECT.remove(project.getLocationHash());
            return;
        }
        SMART_SINCE_PER_PROJECT.putIfAbsent(project.getLocationHash(), nowMs.getAsLong());
        Long anchor = SMART_SINCE_PER_PROJECT.get(project.getLocationHash());
        long msSinceSmart = anchor == null ? 0 : nowMs.getAsLong() - anchor;
        if (shouldKickNow(false, msSinceSmart, delayMs)) {
            XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag);
            return;
        }
        if (!SCHEDULED_FILES.add(fileUrl)) {
            return; // 延迟窗内重复渲染：已排定，不重复排
        }
        scheduleWarmIfDue(project, tag, fileUrl, Math.max(delayMs - msSinceSmart, 0L));
    }

    /**
     * 一次性延迟派发：到点复查「工程未关、已 smart、文件仍在」，通过才踢；
     * 任何复查不过都放弃本轮（去重已撤，下次渲染重新走判定）。
     */
    private static void scheduleWarmIfDue(@NotNull Project project,
                                          @NotNull XmlTag tag,
                                          @NotNull String fileUrl,
                                          long remainingMs) {
        Runnable fire = () -> {
            SCHEDULED_FILES.remove(fileUrl);
            com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
            if (app == null) {
                return;
            }
            // T-GutterWarmReadAction（2026-11-16 真机 SEVERE 实证）：本 Runnable 跑在
            // AppScheduledExecutorService 的池线程上，**不持读动作**，而「到点复检」的 tag.isValid
            // 与三条预热链同步段的 tag.containingFile / getAttributeValue 都是 PSI 读——不包读动作
            // 即抛 `Read access is allowed from inside read-action only`（沙盒 12:35:42 一次连出 3 条）。
            // 三链内部的重活各自开 ReadAction.nonBlocking，这里只需把「复检 + 踢出」包成一次读动作。
            try {
                app.runReadAction((Computable<Void>) () -> {
                    boolean due = !project.isDisposed() && !isDumbSafe(project) && tag.isValid();
                    if (due) {
                        XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag);
                    }
                    return null;
                });
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) {
                    throw t;
                }
            }
        };
        Boolean sync = syncDispatchForTest;
        boolean dispatchSync;
        if (sync != null) {
            dispatchSync = sync;
        } else {
            boolean unitTest;
            try {
                unitTest = ApplicationManager.getApplication() != null
                        && ApplicationManager.getApplication().isUnitTestMode();
            } catch (Throwable t) {
                unitTest = false;
            }
            dispatchSync = unitTest;
        }
        if (dispatchSync) {
            fire.run();
            return;
        }
        try {
            AppExecutorUtil.getAppScheduledExecutorService()
                    .schedule(fire, remainingMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LOG.info("[open-perf] gutter warm schedule failed " + t.getClass().getSimpleName() + "，退化为立即踢");
            SCHEDULED_FILES.remove(fileUrl);
            XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag);
        }
    }

    private static boolean isDumbSafe(@NotNull Project project) {
        try {
            return DumbService.isDumb(project);
        } catch (Throwable t) {
            return true;
        }
    }

    private static long readDelayMs() {
        try {
            return Registry.intValue(REGISTRY_KEY, DEFAULT_DELAY_MS);
        } catch (Throwable t) {
            return DEFAULT_DELAY_MS;
        }
    }
}
