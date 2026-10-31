// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/MapperXmlLocator.kt 移植
package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * T-M55-01 跳转双目标：mapper 语句 id → 项目内 mapper XML 语句节点的定位器。
 *
 * <p>条目 {@code LogSqlEntry.mapperStatement}（拦截器 {@code ms.getId()}，形如 {@code com.example.mapper.UserMapper.selectById}）
 * 拆 namespace + id 后，经 {@link FileTypeIndex} 按文件类型扫描项目内 XML（仿 psi/jpa/HbmXmlEntityResolver.kt
 * 的 FileTypeIndex 先例），PSI 校验 {@code <mapper namespace>} 匹配后定位同 id 的语句标签
 * （select/insert/update/delete，直接子级——沿 ui/gutter/XmlSqlGutterLineMarkerProvider.kt 范式），
 * 换算语句起始行号供 {@code com.intellij.openapi.fileEditor.OpenFileDescriptor} 直跳。
 *
 * <p>已知边界（首批记录，不弹选择器）：多模块同名 mapper（同 namespace 同 id）取索引序第一个并 LOG.info 留痕。
 * MP 内置注入语句（Wrapper CRUD）XML 通常不存在 → locate 返回 null，调用方回退 Java 跳转，无劣化。
 *
 * <p>线程约定：{@link #locate} 自带 {@link ReadAction} 包裹，EDT（右键菜单）与后台线程均可直调；
 * 内部为索引查询 + PSI 只读，无写操作。
 */
public final class MapperXmlLocator {

    public static final MapperXmlLocator INSTANCE = new MapperXmlLocator();

    private MapperXmlLocator() {
    }

    private static final Logger LOG = Logger.getInstance(MapperXmlLocator.class);

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    /** 定位结果：mapper XML 文件 + 语句标签起始行（0-based，OpenFileDescriptor 直用）。 */
    public record Location(
        /** mapper XML 虚拟文件。 */
        @NotNull VirtualFile virtualFile,
        /** 语句标签起始行（0-based）。 */
        int line
    ) {
    }

    /**
     * 定位语句在 mapper XML 中的位置；任一环失败返回 null（调用方回退 Java 跳转）。
     * @param statementId 完整语句 id（{@code namespace.id}，如 {@code com.example.mapper.UserMapper.selectById}）
     */
    @Nullable
    public static Location locate(@NotNull Project project, @NotNull String statementId) {
        kotlin.Pair<String, String> parts = splitStatementId(statementId);
        if (parts == null) return null;
        String namespace = parts.getFirst();
        String id = parts.getSecond();
        return ReadAction.compute(() -> locateUnderRead(project, namespace, id));
    }

    /** 拆 {@code namespace.id}（按最后一个 {@code .} 分段——namespace 本身含点，语句 id 惯例不含）；形态不符返回 null。 */
    @Nullable
    public static kotlin.Pair<String, String> splitStatementId(@NotNull String statementId) {
        String s = statementId.trim();
        if (s.isEmpty()) return null;
        int idx = s.lastIndexOf('.');
        if (idx <= 0 || idx == s.length() - 1) return null;
        return new kotlin.Pair<>(s.substring(0, idx), s.substring(idx + 1));
    }

    /** 读上下文内的定位主体（清单槽新鲜度 → memo → 逐文件 namespace/id 校验 → 行号换算）。 */
    @Nullable
    private static Location locateUnderRead(@NotNull Project project, @NotNull String namespace, @NotNull String id) {
        // 先取**新鲜**清单槽：TTL 过期 / 任一文件失效 / 项目不匹配都会换代（既有 T-PS5-05 语义）。
        // memo 挂在槽实例上，换代即整体失效——命中路径与清单新鲜度严格同代，不会让过期结果
        // 跳过重扫（既有 TTL 用例钉死该语义）。
        FileListSlot slot = currentFileListSlot(project);
        kotlin.Pair<String, String> memoKey = new kotlin.Pair<>(namespace, id);
        Object cached = slot.memo.get(memoKey);
        if (cached != null) {
            return cached instanceof Location location ? location : null;
        }
        XmlFile firstFile = null;
        XmlTag firstTag = null;
        int extraHits = 0;
        for (VirtualFile vf : slot.files) {
            statementScanCount.incrementAndGet();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof XmlFile file)) continue;
            XmlTag tag = findStatementTag(file, namespace, id);
            if (tag == null) continue;
            if (firstFile == null) {
                firstFile = file;
                firstTag = tag;
            } else {
                extraHits++;
            }
        }
        if (extraHits > 0) {
            String firstPath = (firstFile != null && firstFile.getVirtualFile() != null)
                ? firstFile.getVirtualFile().getPath() : null;
            LOG.info("MapperXmlLocator: 语句 " + namespace + "." + id + " 命中 " + (extraHits + 1)
                + " 个 mapper XML（多模块同名），取第一个：" + firstPath);
        }
        Location location = null;
        if (firstFile != null && firstTag != null) {
            VirtualFile vf = firstFile.getVirtualFile();
            if (vf != null) {
                com.intellij.openapi.editor.Document document =
                    PsiDocumentManager.getInstance(project).getDocument(firstFile);
                if (document != null) {
                    location = new Location(vf, document.getLineNumber(firstTag.getTextRange().getStartOffset()));
                }
            }
        }
        // 负结果以 MISS 哨兵承载（ConcurrentHashMap 不接受 null 值）
        slot.memo.put(memoKey, location != null ? location : MISS);
        return location;
    }

    /**
     * T-PS5-05：mapper XML 文件列表单槽缓存条目（项目弱引用 + 文件列表 + 时间戳 +
     * 券01 语句定位结果 memo）。
     *
     * <p>memo 与清单<b>同生共死</b>：条目每次换代即新建空 memo（对照 MybatisXmlTargets F96
     * namespaceSlot 的 listingGeneration 同代失效先例）。memo 键为 {@code namespace to id}，
     * 值 {@link Location} 或 {@code MISS} 哨兵（负结果一并缓存——非 Mapper 方法必然落空，修前每趟重付全程）。</p>
     */
    private static final class FileListSlot {

        private final java.lang.ref.WeakReference<Project> project;
        private final List<VirtualFile> files;
        private final long atMs;
        final java.util.concurrent.ConcurrentHashMap<kotlin.Pair<String, String>, Object> memo =
            new java.util.concurrent.ConcurrentHashMap<>();

        private FileListSlot(java.lang.ref.WeakReference<Project> project, List<VirtualFile> files, long atMs) {
            this.project = project;
            this.files = files;
            this.atMs = atMs;
        }
    }

    /**
     * T-PS5-05：缓存 TTL 上界（毫秒）。新增 mapper XML 后至多一个窗口内跳转仍按旧列表
     * （删除由 {@link VirtualFile#isValid()} 即时兜底：任一缓存文件失效即刻重扫）。
     * 测试注入短窗驱动过期语义。
     */
    public static long fileListTtlMs = 5_000L;

    /** T-PS5-05 测试缝：时间源（默认系统时钟）。 */
    public static java.util.function.LongSupplier clockMs = System::currentTimeMillis;

    /** T-PS5-05 测试缝：索引全扫计数（断言命中路径零遍历）。 */
    public static final java.util.concurrent.atomic.AtomicInteger indexScanCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /** 券01（T-PerfSweep28-01）测试缝：语句定位的逐文件根标签访问计数（断言 memo 命中零遍历）。 */
    public static final java.util.concurrent.atomic.AtomicInteger statementScanCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private static volatile FileListSlot fileListSlot = null;

    /** memo 负结果哨兵（{@code namespace.id} 在当前清单代内不存在对应语句）。 */
    private static final Object MISS = new Object();

    /** T-PS5-05 测试缝：清空文件列表缓存（用例隔离；生产链路无调用点）。 */
    public static void clearFileListCacheForTest() {
        fileListSlot = null;
    }

    /**
     * T-PS5-05：mapper XML 的 VirtualFile 列表（索引 + PSI 根元素预筛）——<b>单槽缓存</b>。
     *
     * <p>为什么要缓存：大项目（数千 XML）每次跳转都全项目扫描索引 + 逐个 PSI 解析根元素判
     * {@code <mapper>}，首跳百毫秒级且发生在 EDT ReadAction 内。缓存只存<b>已筛出的 mapper XML</b>
     * （VirtualFile 而非 PsiFile：PSI 可能被平台失效，取用前统一 {@link VirtualFile#isValid()} 校验）。
     *
     * <p>失效纪律（<b>禁引 PsiTreeChangeListener/EditorFactoryListener</b>——红线族维持 0）：
     * <ul>
     * <li>TTL 上界（默认 5s，新增文件最多滞后一个窗口）；</li>
     * <li>取用时 {@code isValid} 全检，任一失效（删除/改名）即重扫一次；</li>
     * <li>项目身份不匹配（多项目同开）不命中，重扫。</li>
     * </ul>
     *
     * <p>单槽 + 项目弱引用（不挂项目 Disposable、不加监听器）：无跨项目泄漏，项目关闭后条目随
     * GC 回收；多项目交替访问退化为重扫（与无缓存等价，无正确性风险）。
     */
    @NotNull
    private static FileListSlot currentFileListSlot(@NotNull Project project) {
        long now = clockMs.getAsLong();
        FileListSlot slot = fileListSlot;
        if (slot != null) {
            if (slot.project.get() == project
                    && now - slot.atMs <= fileListTtlMs
                    && allValid(slot.files)) {
                return slot;
            }
        }
        indexScanCount.incrementAndGet();
        List<VirtualFile> files;
        try {
            com.intellij.openapi.fileTypes.FileType xmlFileType =
                FileTypeManager.getInstance().getStdFileType("XML");
            List<VirtualFile> result = new ArrayList<>();
            for (VirtualFile vf : FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (!(psiFile instanceof XmlFile xmlFile)) continue;
                if (xmlFile.getRootTag() == null || !"mapper".equals(xmlFile.getRootTag().getName())) continue;
                VirtualFile file = xmlFile.getVirtualFile();
                if (file != null) {
                    result.add(file);
                }
            }
            files = result;
        } catch (Throwable ignored) {
            // runCatching 等价：索引异常按空列表处理（优雅降级，绝不抛出）
            files = List.of();
        }
        FileListSlot fresh = new FileListSlot(new java.lang.ref.WeakReference<>(project), files, now);
        fileListSlot = fresh;
        return fresh;
    }

    /** Kotlin {@code files.all { it.isValid }} 等价：空列表为 true。 */
    private static boolean allValid(List<VirtualFile> files) {
        for (VirtualFile file : files) {
            if (!file.isValid()) return false;
        }
        return true;
    }

    /**
     * 单文件内定位语句标签：namespace 属性精确匹配（{@link #splitStatementId} 的 namespace 段），
     * 语句标签（{@code XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS} 同款集合）为 {@code <mapper>} 直接子级且 id 相等。
     */
    @Nullable
    private static XmlTag findStatementTag(@NotNull XmlFile file, @NotNull String namespace, @NotNull String id) {
        XmlTag root = file.getRootTag();
        if (root == null) return null;
        if (!namespace.equals(root.getAttributeValue("namespace"))) return null;
        for (XmlTag subTag : root.getSubTags()) {
            if (STATEMENT_TAGS.contains(subTag.getName()) && id.equals(subTag.getAttributeValue("id"))) {
                return subTag;
            }
        }
        return null;
    }
}
