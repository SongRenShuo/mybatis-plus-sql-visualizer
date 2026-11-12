// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/nav/IbatisXmlStatementTargets.kt 移植
// （Kotlin internal object 翻译：成员全静态——缓存槽/测试缝计数均为静态字段。）
package cn.song.mybatisplus.visualizer.ui.nav;

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatementKind;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T-Ibatis2Support 券 F（车道 L8）：iBatis 2.x {@code <sqlMap>} 的 id → 标签定位<b>单一事实源</b>。
 *
 * <p>供 {@link IbatisDaoStatementGotoDeclarationHandler}（Java DAO → XML 语句）与
 * {@code cn.song.mybatisplus.visualizer.ui.ref.IbatisXmlIdReferenceContributor}（XML 内部 id 引用）共用，
 * 避免两处各写一份标签名表与落点口径。</p>
 *
 * <h2>门控成本（性能红线）</h2>
 *
 * <p>热路径（reference contributor 的每个 {@code XmlAttributeValue}、每次 {@code resolve()}）<b>只走 O(1) 判定</b>：
 * {@link #isSqlMapFile} 是一次 {@code rootTag?.name} 字符串比较。{@code xml/ibatis/IbatisXmlRecognizer.recognize(file)}
 * 内含 {@code SqlMapClient} 的 classpath 索引查询（{@code JavaPsiFacade.findClass}），<b>严禁</b>在本类或调用方
 * 热路径上调用——iBatis 与 MyBatis 由根标签名天然互斥（{@code sqlMap} vs {@code mapper}），无需三选二识别。
 * 同文件 id 查找（{@link #findIdAttributeInFile}）是文件内标签遍历，与 {@code ui/ref/MybatisXmlTargets} 同口径。</p>
 *
 * <p>跨文件（{@code ns.id} / 裸 id）查找只发生在用户按键触发的 goto 路径（不进 daemon/索引热路径，见
 * {@link IbatisDaoStatementGotoDeclarationHandler} 类注释），靠<b>文件清单单槽 TTL 缓存</b> +
 * <b>结果级单槽 memo</b> 把重复实查压成查表。</p>
 *
 * <h2>缓存失效口径（全部廉价路径，不引监听器）</h2>
 *
 * <ol>
 * <li>项目身份不一致（{@code WeakReference<Project>} 比较）→ 重建；</li>
 * <li>文件清单 TTL（默认 5s，{@link #fileListTtlMs}）过期 → 重扫；</li>
 * <li>参与上次扫描的文件某个 {@code isValid} 变假（被删/移动）→ 重扫；</li>
 * <li>清单换代（{@code Listing.generation} 递增）→ 结果级 memo（namespace→文件、裸 id→落点）一并失效。</li>
 * </ol>
 *
 * <p>与 {@code log/MapperXmlLocator}、{@code psi/XmlCrossNsFragmentResolver} 同款形态：<b>失败退化为「本次不接管」</b>
 * （返回空/ null），绝不抛穿。</p>
 *
 * <h2>落点口径</h2>
 *
 * <p>一律返回<b>语句 {@code id} 的属性值元素</b>（{@link XmlAttributeValue}）——与
 * {@code ui/ref/MybatisXmlTargets.findIdAttributeInFile} 一致（Ctrl+B / 引用落点都落在值上）。</p>
 */
public final class IbatisXmlStatementTargets {

    private IbatisXmlStatementTargets() {
    }

    /** iBatis 语句映射文件根标签名（{@code sql-map-2.dtd}）。MyBatis 3 的根是 {@code mapper}——两者互斥。 */
    public static final String SQL_MAP_ROOT_TAG = "sqlMap";

    /**
     * {@code com.ibatis.sqlmap.client.SqlMapExecutor} 里吃 statementId 的入口方法（jar
     * {@code org.apache.ibatis:ibatis-sqlmap:2.3.4.726} 的 {@code javap} 实测清单，见
     * {@link IbatisDaoStatementGotoDeclarationHandler} 类注释：全部重载的 statementId 都在第一参数位；
     * {@code startBatch}/{@code executeBatch} 族不吃 statementId 故不在表内）。
     *
     * <p>T-Ibatis2Gap 券 B 收尾：迁入本类作<b>单一事实源</b>——券 B 的 gutter 跳转
     * （{@code ui/gutter/IbatisJavaToXmlLineMarkerProvider} 与 {@code IbatisJavaCallSites}）与 L8 的
     * goto handler 共用同一批入口判定。注意：L8 handler 内部仍是 private 副本，待主控
     * 合并后统一改引（本分支不动 L8 文件）。</p>
     */
    public static final Set<String> STATEMENT_ENTRY_METHODS = Set.of(
        "insert",
        "update",
        "delete",
        "queryForObject",
        "queryForList",
        "queryWithRowHandler",
        "queryForPaginatedList",
        "queryForMap"
    );

    /** 根标签名 == {@code sqlMap}（热路径唯一门控；O(1)，零索引、零识别器调用）。 */
    public static boolean isSqlMapFile(@Nullable PsiFile file) {
        return file instanceof XmlFile xmlFile
            && xmlFile.getRootTag() != null
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

    /** 是否 6 类可执行语句标签之一（{@code select}/{@code insert}/{@code update}/{@code delete}/{@code statement}/{@code procedure}）。 */
    public static boolean isStatementTag(@NotNull String tagName) {
        return IbatisStatementKind.byTagName(tagName) != null;
    }

    /** 6 类语句标签名（顺序 = {@code IbatisStatementKind} 声明序；测试与诊断用）。 */
    public static final List<String> STATEMENT_TAG_NAMES = List.of(
        IbatisStatementKind.SELECT.getTagName(),
        IbatisStatementKind.INSERT.getTagName(),
        IbatisStatementKind.UPDATE.getTagName(),
        IbatisStatementKind.DELETE.getTagName(),
        IbatisStatementKind.STATEMENT.getTagName(),
        IbatisStatementKind.PROCEDURE.getTagName()
    );

    /**
     * 同文件内按 {@code id} <b>字面值</b>找标签的 {@code id} 属性值元素（引用/跳转落点）。
     *
     * @param tagName {@code sql} / {@code resultMap} / {@code parameterMap} / 6 类语句标签名
     */
    @Nullable
    public static XmlAttributeValue findIdAttributeInFile(@Nullable PsiFile file,
                                                          @NotNull String tagName,
                                                          @NotNull String id) {
        if (!(file instanceof XmlFile xmlFile)) {
            return null;
        }
        XmlTag root = xmlFile.getRootTag();
        if (root == null) {
            return null;
        }
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(root, XmlTag.class)) {
            if (tagName.equals(tag.getName()) && id.equals(tag.getAttributeValue("id"))) {
                return tag.getAttribute("id") != null ? tag.getAttribute("id").getValueElement() : null;
            }
        }
        return null;
    }

    /**
     * XML 内部 id 引用 → <b>同文件</b>目标（{@code include@refid} / {@code resultMap=} / {@code parameterMap=} /
     * {@code resultMap@extends} 四类共用）。
     *
     * <p>两段式查找（复刻引擎 {@code XmlParserState.applyNamespace} 的查找序，与
     * {@code xml/ibatis/SqlMapScriptBuilder} 的 {@code direct → applyNamespace} 一致）：</p>
     * <ol>
     * <li>先按 refId <b>原样</b>找同文件标签；</li>
     * <li>找不到、且 refId <b>不含 {@code .}</b>、文件 namespace 非空时，再按 {@code namespace + "." + refId} 找。</li>
     * </ol>
     *
     * <p>目标缺失一律返回 null —— 引用是 <b>soft</b>，平台不标红（红线：不做校验器形态）。</p>
     *
     * <p>跨文件（经 {@code sqlMapConfig.xml} 的 {@code <sqlMap resource=…>} 清单）本期不做，见计划册券 F。</p>
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
        if (refId.contains(".")) {
            return null;
        }
        return findIdAttributeInFile(contextFile, tagName, namespace + "." + refId);
    }

    /**
     * {@code ns.id} / 裸 {@code id} → 语句标签的 {@code id} 属性值元素（Java DAO → XML 语句跳转用）。
     *
     * <ul>
     * <li>{@code ns.id}：按<b>最后一个</b> {@code .} 切 namespace / statementId，namespace 匹配项目内 sqlMap 文件的
     *     根 {@code namespace} 属性（{@link #findSqlMapFileByNamespace}）；</li>
     * <li>裸 {@code id}：在全部 sqlMap 文件里找语句标签（iBatis {@code useStatementNamespaces} 默认 false →
     *     statement id 全局唯一）。命中多文件时<b>与 preferredNamespace 相同的文件优先</b>，其余按
     *     清单序（多目标交平台弹选择器）。</li>
     * </ul>
     *
     * @param preferredNamespace 引用点所属类的限定名（无则 null；仅用于裸 id 的排序偏好）
     */
    @NotNull
    public static List<XmlAttributeValue> resolveStatementAnchors(@Nullable Project project,
                                                                  @NotNull String rawStatementId,
                                                                  @Nullable String preferredNamespace) {
        String statementId = rawStatementId.trim();
        if (project == null || statementId.isEmpty()) {
            return List.of();
        }
        int dot = statementId.lastIndexOf('.');
        if (dot > 0 && dot < statementId.length() - 1) {
            String namespace = statementId.substring(0, dot).trim();
            String id = statementId.substring(dot + 1).trim();
            if (namespace.isEmpty() || id.isEmpty()) {
                return List.of();
            }
            XmlFile file = findSqlMapFileByNamespace(project, namespace);
            if (file == null) {
                return List.of();
            }
            return statementAnchorsIn(file, id);
        }
        return bareStatementAnchors(project, statementId, preferredNamespace);
    }

    /** Kotlin 默认参数等价重载：{@code preferredNamespace} 缺省为 null。 */
    @NotNull
    public static List<XmlAttributeValue> resolveStatementAnchors(@Nullable Project project,
                                                                  @NotNull String rawStatementId) {
        return resolveStatementAnchors(project, rawStatementId, null);
    }

    /**
     * 按 namespace 找 sqlMap 文件（文件清单 TTL 缓存 + 单槽结果级 memo）。
     *
     * <p>memo 以<b>清单代</b>为失效戳（清单重建即换代），负结果一并缓存——与
     * {@code ui/ref/MybatisXmlTargets.findMapperFileByNamespace} 同形态，只是根标签判据换成 {@code sqlMap}。</p>
     */
    @Nullable
    public static XmlFile findSqlMapFileByNamespace(@NotNull Project project, @NotNull String namespace) {
        String ns = namespace.trim();
        if (ns.isEmpty()) {
            return null;
        }
        Listing listing = sqlMapListing(project);
        NamespaceSlot slot = NAMESPACE_SLOT;
        if (slot != null
            && slot.project.get() == project
            && slot.listingGeneration == listing.generation
            && slot.namespace.equals(ns)) {
            return slot.file != null && slot.file.isValid() ? slot.file : null;
        }
        NAMESPACE_SCAN_COUNT.incrementAndGet();
        XmlFile found = null;
        for (VirtualFile vf : listing.files) {
            if (!vf.isValid()) {
                continue;
            }
            if (!(PsiManager.getInstance(project).findFile(vf) instanceof XmlFile xmlFile)) {
                continue;
            }
            XmlTag rootTag = xmlFile.getRootTag();
            String rootNamespace = rootTag != null ? rootTag.getAttributeValue("namespace") : null;
            if (rootNamespace != null && ns.equals(rootNamespace.trim())) {
                found = xmlFile;
                break;
            }
        }
        NAMESPACE_SLOT = new NamespaceSlot(new WeakReference<>(project), listing.generation, ns, found);
        return found;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** {@code <sqlMap>} 直接子级里的语句标签（{@code id} 字面值命中全部返回，含 {@code <selectKey>} 之外的重复 id 变体）。 */
    @NotNull
    private static List<XmlAttributeValue> statementAnchorsIn(@NotNull XmlFile file, @NotNull String id) {
        XmlTag root = file.getRootTag();
        if (root == null) {
            return List.of();
        }
        List<XmlAttributeValue> anchors = new ArrayList<>();
        for (XmlTag tag : root.getSubTags()) {
            if (isStatementTag(tag.getName()) && id.equals(tag.getAttributeValue("id"))) {
                XmlAttributeValue valueElement = tag.getAttribute("id") != null
                    ? tag.getAttribute("id").getValueElement()
                    : null;
                if (valueElement != null) {
                    anchors.add(valueElement);
                }
            }
        }
        return anchors;
    }

    /** 裸 statement id：全项目 sqlMap 扫一遍（结果级单槽 memo），同 namespace 的文件优先。 */
    @NotNull
    private static List<XmlAttributeValue> bareStatementAnchors(
        @NotNull Project project,
        @NotNull String id,
        @Nullable String preferredNamespace) {
        Listing listing = sqlMapListing(project);
        StatementIdSlot cached = STATEMENT_ID_SLOT;
        List<kotlin.Pair<String, XmlAttributeValue>> hits;
        boolean cacheUsable = cached != null
            && cached.project.get() == project
            && cached.listingGeneration == listing.generation
            && cached.statementId.equals(id);
        if (cacheUsable) {
            for (kotlin.Pair<String, XmlAttributeValue> hit : cached.hits) {
                if (!hit.getSecond().isValid()) {
                    cacheUsable = false;
                    break;
                }
            }
        }
        if (cacheUsable) {
            hits = cached.hits;
        } else {
            STATEMENT_SCAN_COUNT.incrementAndGet();
            List<kotlin.Pair<String, XmlAttributeValue>> collected = new ArrayList<>();
            for (VirtualFile vf : listing.files) {
                if (!vf.isValid()) {
                    continue;
                }
                if (!(PsiManager.getInstance(project).findFile(vf) instanceof XmlFile xmlFile)) {
                    continue;
                }
                List<XmlAttributeValue> anchors = statementAnchorsIn(xmlFile, id);
                if (anchors.isEmpty()) {
                    continue;
                }
                XmlTag rootTag = xmlFile.getRootTag();
                String ns = rootTag != null && rootTag.getAttributeValue("namespace") != null
                    ? rootTag.getAttributeValue("namespace").trim()
                    : null;
                for (XmlAttributeValue anchor : anchors) {
                    collected.add(new kotlin.Pair<>(ns, anchor));
                }
            }
            STATEMENT_ID_SLOT = new StatementIdSlot(new WeakReference<>(project), listing.generation, id, collected);
            hits = collected;
        }
        if (preferredNamespace == null || preferredNamespace.isEmpty()) {
            List<XmlAttributeValue> plain = new ArrayList<>();
            for (kotlin.Pair<String, XmlAttributeValue> hit : hits) {
                plain.add(hit.getSecond());
            }
            return plain;
        }
        // sortedBy { if (it.first == preferredNamespace) 0 else 1 }：稳定排序，同键保持清单序
        List<XmlAttributeValue> preferred = new ArrayList<>();
        List<XmlAttributeValue> others = new ArrayList<>();
        for (kotlin.Pair<String, XmlAttributeValue> hit : hits) {
            if (preferredNamespace.equals(hit.getFirst())) {
                preferred.add(hit.getSecond());
            } else {
                others.add(hit.getSecond());
            }
        }
        List<XmlAttributeValue> ordered = new ArrayList<>(preferred);
        ordered.addAll(others);
        return ordered;
    }

    // ------------------------------------------------------------------
    // 文件清单与结果级 memo（单槽 + TTL；失效口径见类注释）
    // ------------------------------------------------------------------

    private static final class FileListSlot {
        final WeakReference<Project> project;
        final List<VirtualFile> files;
        final long atMs;
        final long generation;

        FileListSlot(WeakReference<Project> project, List<VirtualFile> files, long atMs, long generation) {
            this.project = project;
            this.files = files;
            this.atMs = atMs;
            this.generation = generation;
        }
    }

    private static final class NamespaceSlot {
        final WeakReference<Project> project;
        final long listingGeneration;
        final String namespace;
        final XmlFile file;

        NamespaceSlot(WeakReference<Project> project, long listingGeneration, String namespace, XmlFile file) {
            this.project = project;
            this.listingGeneration = listingGeneration;
            this.namespace = namespace;
            this.file = file;
        }
    }

    private static final class StatementIdSlot {
        final WeakReference<Project> project;
        final long listingGeneration;
        final String statementId;
        final List<kotlin.Pair<String, XmlAttributeValue>> hits;

        StatementIdSlot(WeakReference<Project> project, long listingGeneration, String statementId,
                        List<kotlin.Pair<String, XmlAttributeValue>> hits) {
            this.project = project;
            this.listingGeneration = listingGeneration;
            this.statementId = statementId;
            this.hits = hits;
        }
    }

    private static final class Listing {
        final List<VirtualFile> files;
        final long generation;

        Listing(List<VirtualFile> files, long generation) {
            this.files = files;
            this.generation = generation;
        }
    }

    /** 清单 TTL 上界（毫秒）：新增/删除 sqlMap XML 后至多一个窗口内解析不到（过期即重扫）。 */
    public static volatile long fileListTtlMs = 5_000L;

    /** 测试缝时钟（master {@code internal var clockMs: () -> Long}）。 */
    public static volatile Function0<Long> clockMs = System::currentTimeMillis;

    /** 测试缝：文件清单实扫次数（memo/TTL 命中不计数）。 */
    public static final AtomicInteger INDEX_SCAN_COUNT = new AtomicInteger(0);

    /** 测试缝：namespace → 文件实查次数。 */
    public static final AtomicInteger NAMESPACE_SCAN_COUNT = new AtomicInteger(0);

    /** 测试缝：裸 statement id 实扫次数。 */
    public static final AtomicInteger STATEMENT_SCAN_COUNT = new AtomicInteger(0);

    private static volatile FileListSlot FILE_LIST_SLOT = null;

    private static volatile NamespaceSlot NAMESPACE_SLOT = null;

    private static volatile StatementIdSlot STATEMENT_ID_SLOT = null;

    private static long listingGenerationCounter = 0L;

    /** 测试缝：清空全部缓存（用例隔离；生产链路无调用点）。 */
    public static void clearCachesForTest() {
        FILE_LIST_SLOT = null;
        NAMESPACE_SLOT = null;
        STATEMENT_ID_SLOT = null;
    }

    @NotNull
    private static Listing sqlMapListing(@NotNull Project project) {
        long now = clockMs.invoke();
        FileListSlot slot = FILE_LIST_SLOT;
        if (slot != null
            && slot.project.get() == project
            && now - slot.atMs <= fileListTtlMs
            && allValid(slot.files)) {
            return new Listing(slot.files, slot.generation);
        }
        INDEX_SCAN_COUNT.incrementAndGet();
        List<VirtualFile> files;
        try {
            com.intellij.openapi.fileTypes.FileType xmlFileType = FileTypeManager.getInstance().getStdFileType("XML");
            List<VirtualFile> collected = new ArrayList<>();
            for (VirtualFile vf : FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))) {
                if (!(PsiManager.getInstance(project).findFile(vf) instanceof XmlFile xmlFile)) {
                    continue;
                }
                XmlTag rootTag = xmlFile.getRootTag();
                if (rootTag != null && SQL_MAP_ROOT_TAG.equals(rootTag.getName()) && xmlFile.getVirtualFile() != null) {
                    collected.add(xmlFile.getVirtualFile());
                }
            }
            files = collected;
        } catch (Throwable e) {
            // runCatching 等义降级：清单失败按空表处理（失败退化为「本次不接管」）
            files = Collections.emptyList();
        }
        long generation = ++listingGenerationCounter;
        FILE_LIST_SLOT = new FileListSlot(new WeakReference<>(project), files, now, generation);
        return new Listing(files, generation);
    }

    private static boolean allValid(@NotNull List<VirtualFile> files) {
        for (VirtualFile vf : files) {
            if (!vf.isValid()) {
                return false;
            }
        }
        return true;
    }
}
