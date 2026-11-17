package cn.song.mybatisplus.visualizer.ui.nav;

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatementKind;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapNamespaceIndex;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T-Ibatis2Support 券 F（车道 L8）：iBatis 2.x {@code <sqlMap>} 的 id → 标签定位<b>单一事实源</b>。
 *
 * <p>供 {@link IbatisDaoStatementGotoDeclarationHandler}（Java DAO → XML 语句）与
 * {@code cn.song.mybatisplus.visualizer.ui.ref.IbatisXmlIdReferenceContributor}（XML 内部 id 引用）
 * 共用，避免两处各写一份标签名表与落点口径。</p>
 *
 * <h2>定位链（T-IbatisGutterPerf P1 起走自定义索引，2026-11-16）</h2>
 *
 * <p>{@code ns.id} / 裸 {@code id} → {@link SqlMapNamespaceIndex} 一次索引查询拿到<b>候选文件</b>
 * （{@code ns:} 键 / {@code stmt:} 键）→ PSI 侧校验根 namespace + 语句 id 精确匹配 →
 * 返回 {@code id} 属性值元素。</p>
 *
 * <p>历史（三轮演进）：券 F 全项目 XML 逐个 PSI 解析筛 sqlMap 文件 + 文件清单单槽 TTL(5s) +
 * 结果级单槽 memo；T-IbatisGutterPerf P1 清单与语句两层都换成自定义索引（all / ns: / stmt: 三类键），
 * TTL / 清单代 / 单槽 memo 整套状态机退役——失效全交平台增量，不再有「清单未就绪」窗口，
 * 也不会出现多工程同开互相踢槽。</p>
 *
 * <h2>门控成本（性能红线）</h2>
 *
 * <p>热路径（reference contributor 的每个 {@code XmlAttributeValue}、每次 resolve()）<b>只走 O(1)
 * 判定</b>：{@link #isSqlMapFile} 是一次 {@code rootTag?.name} 字符串比较；同文件 id 查找
 * （{@link #findIdAttributeInFile}）是文件内标签遍历。
 * {@code xml/ibatis/IbatisXmlRecognizer.recognize(file)} 内含 SqlMapClient 的 classpath 索引查询
 * （{@code JavaPsiFacade.findClass}），<b>严禁</b>在本对象或调用方热路径上调用——iBatis 与 MyBatis
 * 由根标签名天然互斥（sqlMap vs mapper），无需三选二识别。</p>
 *
 * <h2>失败语义（T-IbatisGutterPerf P3）</h2>
 *
 * <ul>
 * <li>dumb / 索引未就绪：<b>不查索引</b>，返回 {@link Lookup#UNKNOWN}（「本次没有」），但不写任何
 *     负缓存——就绪后下次照常解析；</li>
 * <li>读操作被写操作撤销（ProcessCanceledException）：<b>不吞</b>，直接上抛让本趟作废、平台自行重跑。
 *     旧实现用 runCatching{}.getOrDefault(emptyList()) 把取消吞成「空清单」并当正常结果缓存，
 *     慢趟换装后图标粘性缺席（用户 2026-11-16 真机报障：图标出现过一次后无故不再显示）。</li>
 * </ul>
 *
 * <p>索引化只有一条路（T-MechanismSweep，2026-11-16 删掉 P5 的 Registry 回退通道）：定位链不再有
 * 「旧的全项目扫描」分支，回退只能靠版本回滚；日志锚点
 * {@code [gutter-perf] ibatis resolve: state=found|absent|unknown hits=… ms=…}。</p>
 *
 * <h2>落点口径</h2>
 *
 * <p>一律返回<b>语句 {@code id} 的属性值元素</b>（{@code XmlAttributeValue}）——与
 * {@code ui/ref/MybatisXmlTargets.findIdAttributeInFile} 一致（Ctrl+B / 引用落点都落在值上）。</p>
 */
public final class IbatisXmlStatementTargets {

    /** iBatis 语句映射文件根标签名（{@code sql-map-2.dtd}）。MyBatis 3 的根是 mapper——两者互斥。 */
    public static final String SQL_MAP_ROOT_TAG = "sqlMap";

    private static final Logger LOG = Logger.getInstance(IbatisXmlStatementTargets.class);

    /**
     * iBatis 语句 {@code SqlMapExecutor} 里吃 statementId 的入口方法（jar
     * {@code org.apache.ibatis:ibatis-sqlmap:2.3.4.726} 的 javap 实测清单，见
     * {@link IbatisDaoStatementGotoDeclarationHandler} 类注释：全部重载的 statementId 都在第一参数位；
     * startBatch/executeBatch 族不吃 statementId 故不在表内）。
     *
     * <p>T-Ibatis2Gap 券 B 收尾：迁入本对象作<b>单一事实源</b>——券 B 的 gutter 跳转
     * （IbatisJavaToXmlLineMarkerProvider 与 IbatisJavaCallSites）与 L8 的 goto handler 共用
     * 同一批入口判定。</p>
     */
    public static final Set<String> STATEMENT_ENTRY_METHODS = Set.of(
            "insert",
            "update",
            "delete",
            "queryForObject",
            "queryForList",
            "queryWithRowHandler",
            "queryForPaginatedList",
            "queryForMap");

    /** 6 类语句标签名（顺序 = {@link IbatisStatementKind} 声明序；测试与诊断用）。 */
    public static final List<String> STATEMENT_TAG_NAMES = buildStatementTagNames();

    /** 测试缝：替换三态查询（生产＝null）。矩阵用例用它驱动 UNKNOWN / ABSENT 两个分支。 */
    @Nullable
    public static volatile TestLookup lookupOverrideForTest;

    /** 三态查询的可替换形态（测试缝专用；生产恒走索引链）。 */
    @FunctionalInterface
    public interface TestLookup {
        Lookup apply(@NotNull Project project, @NotNull String statementId, @Nullable String preferredNamespace);
    }

    private static final Set<String> PERF_LOGGED_KEYS = ConcurrentHashMap.newKeySet();

    private IbatisXmlStatementTargets() {
    }

    private static List<String> buildStatementTagNames() {
        List<String> names = new ArrayList<>();
        for (IbatisStatementKind kind : IbatisStatementKind.values()) {
            names.add(kind.getTagName());
        }
        return List.copyOf(names);
    }

    /** 定位三态（语义见 {@link #lookupStatementAnchors}）：确定存在 / 确定不存在 / 暂时查不了。 */
    public sealed interface Lookup permits Found, Absent, Unknown {
    }

    /** 确定存在（候选命中）。 */
    public record Found(@NotNull List<XmlAttributeValue> anchors) implements Lookup {

        public Found {
            anchors = List.copyOf(anchors);
        }
    }

    /** 确定不存在——索引查询已成功执行、候选面没有该 namespace / 语句；调用方据此摘图标并收回正缓存。 */
    public enum Absent implements Lookup {
        ABSENT
    }

    /** 暂时查不了（dumb / 索引重建中 / 候选文件 PSI 瞬时取不到）；调用方据此保留已确认的图标、且不动缓存。 */
    public enum Unknown implements Lookup {
        UNKNOWN
    }

    /** 根标签名 == {@code sqlMap}（热路径唯一门控；O(1)，零索引、零识别器调用）。 */
    public static boolean isSqlMapFile(@Nullable PsiFile file) {
        return file instanceof XmlFile xmlFile && xmlFile.getRootTag() != null
                && SQL_MAP_ROOT_TAG.equals(xmlFile.getRootTag().getName());
    }

    /** 根 {@code <sqlMap>} 的 namespace（DTD 里 {@code #IMPLIED}，可为 null / 空串）。 */
    @Nullable
    public static String rootNamespace(@Nullable PsiFile file) {
        if (!(file instanceof XmlFile xmlFile) || xmlFile.getRootTag() == null) {
            return null;
        }
        String namespace = xmlFile.getRootTag().getAttributeValue("namespace");
        if (namespace == null) {
            return null;
        }
        String trimmed = namespace.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 是否 6 类可执行语句标签之一（select/insert/update/delete/statement/procedure）。 */
    public static boolean isStatementTag(@NotNull String tagName) {
        return IbatisStatementKind.byTagName(tagName) != null;
    }

    /**
     * 同文件内按 {@code id} <b>字面值</b>找标签的 {@code id} 属性值元素（引用/跳转落点）。
     *
     * @param tagName {@code sql} / {@code resultMap} / {@code parameterMap} / 6 类语句标签名
     */
    @Nullable
    public static XmlAttributeValue findIdAttributeInFile(@Nullable PsiFile file,
                                                          @NotNull String tagName,
                                                          @NotNull String id) {
        if (!(file instanceof XmlFile xmlFile) || xmlFile.getRootTag() == null) {
            return null;
        }
        XmlTag root = xmlFile.getRootTag();
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(root, XmlTag.class)) {
            if (tag.getName().equals(tagName) && id.equals(tag.getAttributeValue("id"))) {
                return tag.getAttribute("id") != null ? tag.getAttribute("id").getValueElement() : null;
            }
        }
        return null;
    }

    /**
     * XML 内部 id 引用 → <b>同文件</b>目标（{@code include@refid} / {@code resultMap=} /
     * {@code parameterMap=} / {@code resultMap@extends} 四类共用）。
     *
     * <p>两段式查找（复刻引擎 {@code XmlParserState.applyNamespace} 的查找序，与
     * {@code xml/ibatis/SqlMapScriptBuilder} 的 direct → applyNamespace 一致）：
     * ① 先按 refId <b>原样</b>找同文件标签；② 找不到、且 refId <b>不含 {@code .}</b>、文件 namespace
     * 非空时，再按 {@code namespace + "." + refId} 找。</p>
     *
     * <p>目标缺失一律返回 null——引用是 <b>soft</b>，平台不标红（红线：不做校验器形态）。
     * 跨文件（经 {@code sqlMapConfig.xml} 的 {@code <sqlMap resource=…>} 清单）本期不做，见计划册券 F。</p>
     */
    @Nullable
    public static XmlAttributeValue resolveSameFileIdReference(@Nullable PsiFile contextFile,
                                                               @NotNull String tagName,
                                                               @NotNull String rawRefId) {
        String refId = rawRefId.trim();
        if (refId.isEmpty()) {
            return null;
        }
        XmlAttributeValue direct = findIdAttributeInFile(contextFile, tagName, refId);
        if (direct != null) {
            return direct;
        }
        String namespace = rootNamespace(contextFile);
        if (namespace == null) {
            return null;
        }
        if (refId.indexOf('.') >= 0) {
            return null;
        }
        return findIdAttributeInFile(contextFile, tagName, namespace + "." + refId);
    }

    /**
     * {@code ns.id} / 裸 {@code id} → 语句标签的 {@code id} 属性值元素（Java DAO → XML 语句跳转用）。
     *
     * <p>三态语义见 {@link #lookupStatementAnchors}；本重载只取「找到」的锚，把「确定不存在」与
     * 「暂时查不了」都折叠成空列表（预览 / Ctrl+B / MCP 的口径：查不到就回落，不区分原因）。</p>
     *
     * @param preferredNamespace 引用点所属类的限定名（无则 null；仅用于裸 id 的排序偏好）
     */
    @NotNull
    public static List<XmlAttributeValue> resolveStatementAnchors(@Nullable Project project,
                                                                  @NotNull String rawStatementId,
                                                                  @Nullable String preferredNamespace) {
        Lookup lookup = lookupStatementAnchors(project, rawStatementId, preferredNamespace);
        return lookup instanceof Found found ? found.anchors() : List.of();
    }

    /** 无偏好 namespace 的简化形态（既有调用方）。 */
    @NotNull
    public static List<XmlAttributeValue> resolveStatementAnchors(@Nullable Project project,
                                                                  @NotNull String rawStatementId) {
        return resolveStatementAnchors(project, rawStatementId, null);
    }

    /**
     * 定位<b>三态</b>（T-IbatisGutterPerf P2.1，2026-11-16 用户报障「改了 namespace 图标不消失」）：
     *
     * <ul>
     * <li>{@link Found}：确定存在；</li>
     * <li>{@link Absent}：<b>确定</b>不存在——索引查询已成功执行、候选面没有该 namespace / 语句；
     *     调用方据此摘图标并<b>收回正缓存</b>（缓存不能给已删除/改名的目标续命）；</li>
     * <li>{@link Unknown}：暂时查不了（dumb / 索引重建中 IndexNotReadyException / 候选文件 PSI
     *     瞬时取不到）——调用方据此<b>保留</b>已确认的图标、且不动缓存。</li>
     * </ul>
     *
     * <p>为什么必须分三态：慢趟结果按趟整体替换，把「暂时查不了」当「不存在」会造成图标粘性缺席
     * （S2）；反过来把「确定不存在」当「暂时查不了」会让图标永不过期（P2.1 报障）。两者只能靠
     * 「查询是否成功执行」区分，不能靠返回值是否为空。</p>
     *
     * @param preferredNamespace 引用点所属类的限定名（无则 null；仅用于裸 id 的排序偏好）
     */
    @NotNull
    public static Lookup lookupStatementAnchors(@Nullable Project project,
                                                @NotNull String rawStatementId,
                                                @Nullable String preferredNamespace) {
        String statementId = rawStatementId.trim();
        if (project == null || statementId.isEmpty()) {
            return Absent.ABSENT;
        }
        TestLookup override = lookupOverrideForTest;
        if (override != null) {
            return override.apply(project, statementId, preferredNamespace);
        }
        // dumb / 索引未就绪：不查索引（查询会抛 IndexNotReadyException），按「未知」返回，
        // 且**不留任何负缓存**——这是旧链「空清单被固化」的反面（见类头「失败语义」）。
        if (DumbService.isDumb(project)) {
            return Unknown.UNKNOWN;
        }
        long startedNs = System.nanoTime();
        int dot = statementId.lastIndexOf('.');
        Lookup lookup;
        try {
            if (dot > 0 && dot < statementId.length() - 1) {
                String namespace = statementId.substring(0, dot).trim();
                String id = statementId.substring(dot + 1).trim();
                lookup = namespace.isEmpty() || id.isEmpty() ? Absent.ABSENT
                        : lookupByNamespace(project, namespace, id);
            } else {
                lookup = lookupByStatementId(project, statementId, preferredNamespace);
            }
        } catch (IndexNotReadyException e) {
            // 索引重建中：这是「未知」不是「没有」（PCE 不在此列，它必须上抛让本趟作废）。
            lookup = Unknown.UNKNOWN;
        }
        logPerf(statementId, lookup, startedNs);
        return lookup;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** {@code ns.id}：索引只给候选文件，namespace 与 id 都由 PSI 精确校验（索引键偏差不会错跳）。 */
    private static Lookup lookupByNamespace(@NotNull Project project,
                                            @NotNull String namespace,
                                            @NotNull String id) {
        List<com.intellij.openapi.vfs.VirtualFile> files =
                SqlMapNamespaceIndex.getSqlMapFilesByNamespace(project, namespace);
        boolean psiUnavailable = false;
        for (com.intellij.openapi.vfs.VirtualFile vf : files) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof XmlFile file)) {
                psiUnavailable = true;
                continue;
            }
            String rootNamespace = file.getRootTag() != null
                    ? (file.getRootTag().getAttributeValue("namespace") != null
                        ? file.getRootTag().getAttributeValue("namespace").trim() : null)
                    : null;
            if (!namespace.equals(rootNamespace)) {
                continue;
            }
            List<XmlAttributeValue> anchors = statementAnchorsIn(file, id);
            if (!anchors.isEmpty()) {
                return new Found(anchors);
            }
        }
        return psiUnavailable ? Unknown.UNKNOWN : Absent.ABSENT;
    }

    /** 裸 statement id：索引 {@code stmt:} 键取候选文件；同 namespace 的文件排首位。 */
    private static Lookup lookupByStatementId(@NotNull Project project,
                                              @NotNull String id,
                                              @Nullable String preferredNamespace) {
        List<com.intellij.openapi.vfs.VirtualFile> files =
                SqlMapNamespaceIndex.getSqlMapFilesByStatementId(project, id);
        record Hit(String namespace, XmlAttributeValue anchor) {
        }
        List<Hit> hits = new ArrayList<>();
        boolean psiUnavailable = false;
        for (com.intellij.openapi.vfs.VirtualFile vf : files) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof XmlFile file)) {
                psiUnavailable = true;
                continue;
            }
            List<XmlAttributeValue> anchors = statementAnchorsIn(file, id);
            if (anchors.isEmpty()) {
                continue;
            }
            String ns = file.getRootTag() != null && file.getRootTag().getAttributeValue("namespace") != null
                    ? file.getRootTag().getAttributeValue("namespace").trim() : null;
            for (XmlAttributeValue anchor : anchors) {
                hits.add(new Hit(ns, anchor));
            }
        }
        if (hits.isEmpty()) {
            return psiUnavailable ? Unknown.UNKNOWN : Absent.ABSENT;
        }
        List<XmlAttributeValue> ordered;
        if (preferredNamespace == null || preferredNamespace.isEmpty()) {
            ordered = hits.stream().map(Hit::anchor).toList();
        } else {
            List<Hit> sorted = new ArrayList<>(hits);
            sorted.sort((a, b) -> Boolean.compare(!a.namespace().equals(preferredNamespace),
                    !b.namespace().equals(preferredNamespace)));
            ordered = sorted.stream().map(Hit::anchor).toList();
        }
        return new Found(ordered);
    }

    /** {@code <sqlMap>} 直接子级里的语句标签（id 字面值命中全部返回，含 {@code <selectKey>} 之外的重复 id 变体）。 */
    private static List<XmlAttributeValue> statementAnchorsIn(@NotNull XmlFile file, @NotNull String id) {
        XmlTag root = file.getRootTag();
        if (root == null) {
            return List.of();
        }
        List<XmlAttributeValue> anchors = new ArrayList<>();
        for (XmlTag tag : root.getSubTags()) {
            if (isStatementTag(tag.getName()) && id.equals(tag.getAttributeValue("id"))
                    && tag.getAttribute("id") != null && tag.getAttribute("id").getValueElement() != null) {
                anchors.add(tag.getAttribute("id").getValueElement());
            }
        }
        return anchors;
    }

    /** 每条语句只记一次定位读数（候选命中数 / 首个落点文件 / 耗时 / 三态）。 */
    private static void logPerf(@NotNull String statementId, @NotNull Lookup lookup, long startedNs) {
        if (!PERF_LOGGED_KEYS.add(statementId)) {
            return;
        }
        long ms = (System.nanoTime() - startedNs) / 1_000_000;
        List<XmlAttributeValue> anchors = lookup instanceof Found found ? found.anchors() : null;
        String state = lookup instanceof Found ? "found" : lookup instanceof Absent ? "absent" : "unknown";
        String filePath = anchors != null && !anchors.isEmpty()
                && anchors.get(0).getContainingFile() != null
                && anchors.get(0).getContainingFile().getVirtualFile() != null
                ? anchors.get(0).getContainingFile().getVirtualFile().getPath() : "-";
        LOG.info("[gutter-perf] ibatis resolve: statementId=" + statementId + " state=" + state
                + " hits=" + (anchors != null ? anchors.size() : 0) + " file=" + filePath + " ms=" + ms);
    }

    /** 测试缝：清空「每条语句只记一次」的日志去重集（对应旧 clearCachesForTest 的调用位）。 */
    public static void resetPerfLogForTest() {
        PERF_LOGGED_KEYS.clear();
    }
}
