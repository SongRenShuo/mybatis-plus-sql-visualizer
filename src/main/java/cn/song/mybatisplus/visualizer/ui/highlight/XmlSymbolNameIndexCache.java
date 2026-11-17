package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.log.MapperNamespaceIndex;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * T-IndexScanSweep2 P1（2026-11-16）：补全候选「项目级名称清单」的<b>一次构建</b>服务。
 *
 * <h2>为什么</h2>
 *
 * <p>改造前两处清单<b>每次补全</b>都重算，且成本主因是「对最多 500 个 XML 建 PSI」：
 * {@code XmlCompletionSymbols.crossFileSqlIds}（跨文件 {@code <sql id>} → refid 候选）走
 * {@code FileTypeIndex(XML)} → 最多 500 文件逐个 {@code PsiManager.findFile} + rootTag + 遍历
 * {@code <sql>}；{@code XmlCompletionSymbols.configPropertyKeys}（mybatis-config
 * {@code <properties>} key）同形态。实测（P-1/P-2 探针，Light 沙箱，N=100/200/400）：同一份清单
 * 连续两次调用各 2.6~4.1ms，<b>第二次与第一次同量级（无缓存）、耗时随 N 增长</b>——即每次补全全付一次。
 *
 * <h2>做法</h2>
 *
 * <p>本服务维护两份<b>不可变快照</b>：crossFileSqlIds（{@code ns.sqlId}，无 namespace 时裸 id）与
 * configPropertyKeys（{@code <property name>} 的 name 属性值）。构建来源<b>只查自定义索引</b>
 * {@link MapperNamespaceIndex} 的两类文件级标记键（{@code #mapper} / {@code #config}），
 * 不再 FileTypeIndex 全量；建好前返回空（与既有 dumb 闸同语义），<b>不在 EDT 上建</b>。
 *
 * <h2>失效口径（关键）</h2>
 *
 * <p>按<b>索引状态</b>失效，不是 PsiModificationTracker（后者击键即失效＝等于没有）：
 * 代戳 = {@link MapperNamespaceIndex#getIndexStamp}（索引内容代）⊕ 来源文件集合的
 * {@code VirtualFile.modificationStamp}（路径 + 逐文件代戳）。
 *
 * <p>两条代戳<b>缺一不可</b>（2026-11-16 探针实测）：索引代戳只随「索引<b>产出</b>变化」递增——
 * 新增/删除 XML 文件 = 变化；但「改已存在 mapper 的 {@code <sql id>}」（namespace/根标签没变 ⇒
 * 索引键不变）索引代戳<b>恒不变</b>（实测 before=401 after=401）→ 只能靠该文件的
 * {@code VirtualFile.modificationStamp}（实测 14 → 2112）捕捉。故两者合用，才能满足
 * 「改 {@code <sql id>}/properties 后立即生效」。
 *
 * <p>边界（如实登记）：{@code VirtualFile.modificationStamp} 只随<b>落盘</b>变化，故<b>别的文件</b>里
 * 未保存的编辑要等保存才进清单（当前文件自身的 {@code <sql id>} 不走本清单——refid 同文件片段由
 * localIds 走活 PSI 补齐，不受影响）。
 *
 * <h2>回退通道</h2>
 *
 * <p>Registry {@code sqlvis.completion.nameIndexCache}（默认 true，plugin.xml registryKey 登记）
 * → false 时 {@code XmlCompletionSymbols} 走<b>现扫实现</b>（legacyCrossFileSqlIds /
 * legacyConfigPropertyKeys），供真机 A/B；下一版删除（承 T-IbatisGutterPerf P5 口径：不承诺长期保留）。
 *
 * <h2>线程与红线</h2>
 *
 * <p>懒建（首次调用触发）；生产走 {@code ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project)}，
 * 等索引不持读锁、可撤销、随工程回收；单测/unit-test 模式同步构建以保证确定性。
 * <b>不引</b> PsiTreeChangeListener / EditorFactoryListener（仓内红线族维持 0）。
 */
@Service(Service.Level.PROJECT)
public final class XmlSymbolNameIndexCache {

    private static final Logger LOG = Logger.getInstance(XmlSymbolNameIndexCache.class);

    /** 逐文件代戳合成用的质数乘子（把 path 哈希与 modificationStamp 混进同一项）。 */
    private static final long PART_FILE = 1_000_003L;

    private final Project project;

    private volatile List<String> sqlIdsSnapshot = List.of();

    private volatile List<String> propsSnapshot = List.of();

    /** 已构建快照对应的代戳；{@code Long.MIN_VALUE} = 从未构建。 */
    private volatile long builtSignature = Long.MIN_VALUE;

    /** 已提交异步构建的代戳（同代戳只提交一次）。 */
    private volatile long pendingSignature = Long.MIN_VALUE;

    private final Object buildLock = new Object();

    /** 真实构建次数（守卫读数；不代表用户可见行为）。 */
    public final AtomicInteger buildCount = new AtomicInteger(0);

    /** 测试缝：强制同步构建（生产＝false，走后台线程池）。 */
    public volatile boolean syncForTest = false;

    /** 测试缝：Registry 开关（生产＝{@code Registry.is(REGISTRY_KEY, true)}）。 */
    public volatile BooleanSupplier enabledProvider = () -> {
        try {
            return Registry.is(REGISTRY_KEY, true);
        } catch (Throwable t) {
            return true;
        }
    };

    public XmlSymbolNameIndexCache(@NotNull Project project) {
        this.project = project;
    }

    /**
     * 测试缝：清空全部状态（快照/代戳/构建计数）并复位两个开关。
     *
     * <p>需要它是因为单测的 project 在<b>同一测试类内可能是共享的</b>（BasePlatformTestCase 的 project
     * 复用），而本服务挂在 project 上 → 计数与开关会跨用例污染（2026-11-16 实测：某用例把
     * enabledProvider 置 false 后，后续用例静默走回退链、buildCount 恒为历史累计值）。
     */
    public void resetForTest() {
        synchronized (buildLock) {
            sqlIdsSnapshot = List.of();
            propsSnapshot = List.of();
            builtSignature = Long.MIN_VALUE;
            pendingSignature = Long.MIN_VALUE;
            buildCount.set(0);
        }
        syncForTest = false;
        enabledProvider = () -> {
            try {
                return Registry.is(REGISTRY_KEY, true);
            } catch (Throwable t) {
                return true;
            }
        };
    }

    public boolean isCacheEnabled() {
        return enabledProvider.getAsBoolean();
    }

    /** 跨文件 {@code <sql id>} 候选（{@code ns.sqlId} / 无 ns 时裸 id）。建好前 / dumb 态 → 空。 */
    public List<String> crossFileSqlIds() {
        ensureBuilt();
        return sqlIdsSnapshot;
    }

    /** mybatis-config {@code <properties>} key 清单。建好前 / dumb 态 → 空。 */
    public List<String> configPropertyKeys() {
        ensureBuilt();
        return propsSnapshot;
    }

    // ------------------------------------------------------------------
    // 失效与构建
    // ------------------------------------------------------------------

    private void ensureBuilt() {
        if (!enabledProvider.getAsBoolean()) {
            return;
        }
        try {
            if (DumbService.isDumb(project)) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        Long sig = signature();
        if (sig == null) {
            return;
        }
        if (sig == builtSignature) {
            return;
        }
        if (syncForTest || ApplicationManager.getApplication().isUnitTestMode()) {
            synchronized (buildLock) {
                if (sig == builtSignature) {
                    return;
                }
                doBuild(sig);
            }
            return;
        }
        if (pendingSignature == sig) {
            return;
        }
        scheduleBuild(sig);
    }

    /** 来源文件集合 + 索引代的合成代戳（任一失败 → null，本次不重建）。 */
    private Long signature() {
        try {
            // 逐文件代戳按 XOR 合成：**与文件枚举顺序无关**（getContainingFiles 的迭代顺序不承诺稳定，
            // 用顺序相关的滚动哈希会在顺序变化时误判「变了」→ 每次补全重建；XOR 消除这类假失效）。
            long h = 0L;
            for (var vf : MapperNamespaceIndex.getMapperFiles(project)) {
                h = h ^ (vf.getPath().hashCode() * (long) PART_FILE + vf.getModificationStamp());
            }
            for (var vf : MapperNamespaceIndex.getConfigFiles(project)) {
                h = h ^ (vf.getPath().hashCode() * (long) PART_FILE + vf.getModificationStamp());
            }
            return h * 31 + MapperNamespaceIndex.getIndexStamp(project);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void scheduleBuild(long sig) {
        pendingSignature = sig;
        try {
            ReadAction.nonBlocking(() -> doBuild(sig))
                    .inSmartMode(project)
                    .expireWith(project)
                    .coalesceBy(this, "nameIndex:" + sig)
                    .submit(AppExecutorUtil.getAppExecutorService())
                    .onError(t -> {
                        if (pendingSignature == sig) {
                            pendingSignature = Long.MIN_VALUE;
                        }
                        LOG.info("[completion-perf] name index build 未完成：" + t.getClass().getSimpleName()
                                + "（下次补全重试）");
                    });
        } catch (RuntimeException e) {
            if (pendingSignature == sig) {
                pendingSignature = Long.MIN_VALUE;
            }
        }
    }

    /** 构建本体（须在读动作内）：只查索引取文件，再按文件建 PSI 抽名称。异常不外抛（保留旧快照）。 */
    private void doBuild(long sig) {
        long started = System.currentTimeMillis();
        Built built;
        try {
            built = ReadAction.compute(() -> buildSnapshot());
        } catch (RuntimeException e) {
            return;
        }
        if (built == null) {
            return;
        }
        sqlIdsSnapshot = built.sqlIds();
        propsSnapshot = built.props();
        builtSignature = sig;
        buildCount.incrementAndGet();
        if (pendingSignature == sig) {
            pendingSignature = Long.MIN_VALUE;
        }
        LOG.info("[completion-perf] name index built: sqlIds=" + built.sqlIds().size()
                + " props=" + built.props().size() + " files=" + built.files()
                + " ms=" + (System.currentTimeMillis() - started));
    }

    private Built buildSnapshot() {
        PsiManager psiManager = PsiManager.getInstance(project);
        List<com.intellij.openapi.vfs.VirtualFile> mapperFiles = MapperNamespaceIndex.getMapperFiles(project);
        List<com.intellij.openapi.vfs.VirtualFile> configFiles = MapperNamespaceIndex.getConfigFiles(project);

        LinkedHashSet<String> sqlIds = new LinkedHashSet<>();
        for (var vf : mapperFiles) {
            com.intellij.psi.PsiFile psiFile = psiManager.findFile(vf);
            if (!(psiFile instanceof XmlFile file)) {
                continue;
            }
            var root = file.getRootTag();
            if (root == null || !"mapper".equals(root.getName())) {
                continue;
            }
            String ns = root.getAttributeValue("namespace");
            if (ns != null && ns.isBlank()) {
                ns = null;
            }
            for (var sql : root.findSubTags("sql")) {
                String id = sql.getAttributeValue("id");
                if (id == null || id.isBlank()) {
                    continue;
                }
                sqlIds.add(ns != null ? ns + "." + id : id);
            }
        }

        LinkedHashSet<String> props = new LinkedHashSet<>();
        for (var vf : configFiles) {
            com.intellij.psi.PsiFile psiFile = psiManager.findFile(vf);
            if (!(psiFile instanceof XmlFile file)) {
                continue;
            }
            props.addAll(XmlCompletionSymbols.propertyKeysOf(file));
        }

        return new Built(List.copyOf(sqlIds), List.copyOf(props), mapperFiles.size() + configFiles.size());
    }

    private record Built(List<String> sqlIds, List<String> props, int files) {
    }

    /** Registry 回退开关（plugin.xml registryKey）；false → XmlCompletionSymbols 走现扫实现。 */
    public static final String REGISTRY_KEY = "sqlvis.completion.nameIndexCache";

    public static XmlSymbolNameIndexCache getInstance(Project project) {
        return project.getService(XmlSymbolNameIndexCache.class);
    }
}
