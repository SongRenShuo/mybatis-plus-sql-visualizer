package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * T-PreviewAssemblyWarm：文件打开时<b>后台预热一次预览装配</b>，只算不建 UI，结果丢弃。
 *
 * <h2>为什么需要（读数，非推测）</h2>
 *
 * <p>沙盒 P8 埋点（2026-11-13 21:44，cust-big-demo）：本会话<b>第一次</b>预览
 * {@code request→dialog 351ms + construct 256ms + show 88ms ≈ 695ms}，而之后每一次只有 55~75ms。
 * 其中 construct 那 256ms（热态 15~20ms）是编辑器/控制台/Laf 的首次初始化——属面板面，本类
 * <b>不碰</b>；request→dialog 那 351ms 里除装配（热态 47~62ms）之外主要是<b>首次类加载与 JIT</b>，
 * 这部分可以提前在用户点击之前消化掉。</p>
 *
 * <h2>边界（与计划 W1-b 一致，越线即错）</h2>
 *
 * <ul>
 * <li><b>不建 UI</b>：不创建 Editor、不建面板、不碰 Disposable 树——只调
 *     {@link XmlPreviewService#preview} 这种纯装配入口，返回的 PreviewResult 直接丢弃
 *     （它只写既有缓存：实体缓存 / 候选缓存 / 解析器缓存）；</li>
 * <li><b>不写执行文件</b>：mpsql-exec.sql 的写盘属于 previewAsync 的预热分支，本类不调用；</li>
 * <li><b>不触发同步检索</b>：preview 内部对 M2 一律 deferred（首开骨架语义不变）；</li>
 * <li><b>dumb 态跳过</b>：索引期预热只会跟着索引一起等，跳过等价于现状；</li>
 * <li><b>每文件一次 + 每项目 {@link #WARM_FILES_PER_PROJECT} 个文件</b>：coalesceBy 单飞 +
 *     文件级去重；失败/取消会把名额与去重标记放回去，等下次渲染重试。</li>
 * </ul>
 *
 * <h2>开关与回退</h2>
 *
 * <p>Registry {@code sqlvis.preview.assemblyWarm}（默认开，plugin.xml registryKey 登记）；关掉即
 * 完全回到「点击时才装配」的旧行为，无任何残留。线程口径照 S1 预取链 / warmCandidates：
 * {@code ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project).coalesceBy(...)}——
 * 等索引不持读锁、可撤销、随工程关闭撤掉。</p>
 */
public final class PreviewAssemblyWarm {

    /** Registry 总开关（plugin.xml registryKey）；关闭＝整段预热早退。 */
    public static final String REGISTRY_KEY = "sqlvis.preview.assemblyWarm";

    /**
     * 每个项目<b>每会话</b>允许预热的文件数上限。
     *
     * <p>真机教训（2026-11-13 22:20 用户沙盒日志）：一个 mapper 文件有 <b>11 条语句</b>，原实现按
     * {@code 文件#语句} 去重 ⇒ 一次文件渲染预热了 11 次（日志 assembly warm done in 9.8~10.9s × 11，
     * 那 10 秒几乎全在等 smart mode，但落地时 11 份装配挤在同一刻跑）。预热只为「首次点击」服务，
     * 一个文件一次就够，且整会话不该无限制地铺开。</p>
     */
    public static final int WARM_FILES_PER_PROJECT = 2;

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(PreviewAssemblyWarm.class);

    /** 已预热过的文件（按 VirtualFile.url，每文件一次；取消/失败会移除以便下次重试）。 */
    private static final Set<String> WARMED_FILES = ConcurrentHashMap.newKeySet();

    /** 每项目已用掉的预热额度（会话级）。 */
    private static final Map<String, AtomicInteger> USED_PER_PROJECT = new ConcurrentHashMap<>();

    /** 实际跑完的预热次数（守卫读数；不代表用户可见行为）。 */
    public static final AtomicInteger RUN_COUNT = new AtomicInteger();

    /** 测试缝：Registry 开关（生产＝Registry.is(REGISTRY_KEY, true)）。 */
    public static volatile BooleanSupplier enabledProvider = () -> {
        try {
            return Registry.is(REGISTRY_KEY, true);
        } catch (Throwable t) {
            return true;
        }
    };

    /** 测试缝：单测走同步执行（真实环境走应用线程池）；null＝按环境判定。 */
    public static volatile Boolean syncForTest = null;

    /** Kotlin object 语义兼容实例。 */
    public static final PreviewAssemblyWarm INSTANCE = new PreviewAssemblyWarm();

    private PreviewAssemblyWarm() {
    }

    /** 测试缝：清空全部状态并复位两个缝。 */
    public static void resetForTest() {
        WARMED_FILES.clear();
        USED_PER_PROJECT.clear();
        RUN_COUNT.set(0);
        enabledProvider = () -> {
            try {
                return Registry.is(REGISTRY_KEY, true);
            } catch (Throwable t) {
                return true;
            }
        };
        syncForTest = null;
    }

    /**
     * 预热一次（幂等）。由 gutter 的文件打开预热信号调用——与 ctx 预热同一个信号，
     * 便于统一预算与去重。
     *
     * @param tag 光标附近（或首个）语句标签；id 取不到就不预热（无 id 无法定位装配对象）
     */
    public static void warmOnce(@Nullable XmlTag tag) {
        if (tag == null || !enabledProvider.getAsBoolean()) {
            return;
        }
        Project project = tag.getProject();
        if (project.isDisposed()) {
            return;
        }
        if (tag.getContainingFile() == null) {
            return;
        }
        String statementIdRaw;
        try {
            statementIdRaw = tag.getAttributeValue("id");
        } catch (Throwable t) {
            statementIdRaw = null;
        }
        final String statementId = statementIdRaw;
        if (statementId == null || statementId.isBlank()) {
            return;
        }
        var file = tag.getContainingFile();
        final String fileKey = file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : file.getName();
        // ① 每文件一次（不是每语句——一个 mapper 常有十几条语句，见 WARM_FILES_PER_PROJECT 的实测教训）
        if (!WARMED_FILES.add(fileKey)) {
            return;
        }
        // ② 每项目每会话的额度（预热只服务「首次点击」，不该无限铺开）
        final AtomicInteger used = USED_PER_PROJECT.computeIfAbsent(project.getLocationHash(), k -> new AtomicInteger());
        if (used.incrementAndGet() > WARM_FILES_PER_PROJECT) {
            WARMED_FILES.remove(fileKey);
            return;
        }
        boolean dumb;
        try {
            dumb = DumbService.isDumb(project);
        } catch (Throwable t) {
            dumb = false;
        }
        if (dumb) {
            WARMED_FILES.remove(fileKey); // 索引期不预热，等下一次渲染再试
            used.decrementAndGet();
            return;
        }
        final long started = System.currentTimeMillis();
        Boolean sync = syncForTest;
        boolean dispatchSync;
        if (sync != null) {
            dispatchSync = sync;
        } else {
            try {
                dispatchSync = ApplicationManager.getApplication() != null
                        && ApplicationManager.getApplication().isUnitTestMode();
            } catch (Throwable t) {
                dispatchSync = false;
            }
        }
        if (dispatchSync) {
            runWarm(project, tag, statementId, started, fileKey + "#" + statementId);
            return;
        }
        try {
            com.intellij.openapi.application.ReadAction.nonBlocking(() -> {
                runWarm(project, tag, statementId, started, fileKey + "#" + statementId);
                return kotlin.Unit.INSTANCE;
            })
                    .inSmartMode(project)
                    .expireWith(project)
                    .coalesceBy(PreviewAssemblyWarm.class, "assemblyWarm:" + fileKey + "#" + statementId)
                    .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
                    .onError(t -> {
                        WARMED_FILES.remove(fileKey);
                        used.decrementAndGet();
                        LOG.info("[open-perf] assembly warm 未完成：" + t.getClass().getSimpleName()
                                + "（下次渲染重试）");
                    });
        } catch (RuntimeException e) {
            WARMED_FILES.remove(fileKey);
            used.decrementAndGet();
        }
    }

    /** 装配本体：<b>只算不建 UI</b>，产物丢弃（只留缓存）。异常不外抛（预热失败等价于没预热）。 */
    private static void runWarm(@NotNull Project project,
                                @NotNull XmlTag tag,
                                @NotNull String statementId,
                                long startedMs,
                                @NotNull String key) {
        try {
            if (!(tag.getContainingFile() instanceof XmlFile file)) {
                return;
            }
            // 实体/表上下文：与点击路径同一来源（includeCandidates=false，不在预热里解析联表候选）
            SourceSqlContext src = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag, false);
            SqlCompletionContext entity = null;
            if (src != null && src.getMain() != null) {
                SqlCompletionContext main = src.getMain();
                entity = new SqlCompletionContext(main.tableName, main.columns, main.tableAlias,
                        main.columnComments, main.tableComment, src.getCandidates());
            }
            // 装配（纯数据）：结果直接丢弃——目的是把类加载/解析/JIT 提前做掉，缓存随解析器自身留在项目里
            XmlPreviewService.INSTANCE.preview(file.getText(), statementId, project, entity, null);
            RUN_COUNT.incrementAndGet();
            LOG.info("[open-perf] assembly warm done in " + (System.currentTimeMillis() - startedMs)
                    + "ms key=" + key);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) {
                throw t;
            }
            WARMED_FILES.remove(key.substring(0, key.indexOf('#')));
            LOG.info("[open-perf] assembly warm fail " + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "（等价于没预热）");
        }
    }
}
