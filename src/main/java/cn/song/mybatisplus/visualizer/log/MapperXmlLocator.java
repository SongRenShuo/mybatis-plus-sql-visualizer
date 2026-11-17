package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * T-M55-01 跳转双目标：mapper 语句 id → 项目内 mapper XML 语句节点的定位器。
 *
 * <h2>定位链（F1''' 起走平台索引，2026-11-15 精华采纳刀）</h2>
 *
 * <p>拆 {@code namespace.id} → {@link MapperNamespaceIndex#getMapperXmlFiles}（自定义 fileBasedIndex，
 * namespace 在索引期已摊平，查询毫秒级、失效全交平台增量）→ PSI 校验 {@code <mapper namespace>}
 * 匹配后定位同 id 的语句标签（select/insert/update/delete，直接子级——沿
 * {@code ui/gutter/XmlSqlGutterLineMarkerProvider} 范式），换算语句起始行号供
 * {@code OpenFileDescriptor} 直跳。</p>
 *
 * <p>历史（三轮演进，均见 {@code docs/开发计划/20261114-2131-T-GutterFirstOpenDelay-*.md}）：
 * T-PS5-05 FileTypeIndex 全扫 + 单槽 TTL 缓存 + 语句 memo；F1'' 清单构建异步化 + serve stale +
 * 头扫化；F1''' MPJ 精华采纳——自定义索引替换整套清单状态机（头扫挪进索引器、TTL/异步/重跑
 * 机制整体退役；「清单未就绪」窗口由平台增量管理，provider 层 F1'/F1-b 失败分支兜底）。</p>
 *
 * <p>已知边界（首批记录，不弹选择器）：多模块同名 mapper（同 namespace 同 id）取索引序第一个并
 * LOG.info 留痕。MP 内置注入语句（Wrapper CRUD）XML 通常不存在 → locate 返回 null，
 * 调用方回退 Java 跳转，无劣化。dumb/索引未就绪期可能返回 null——provider 层 F1' 失败分支
 * （正缓存/dumb 乐观产出）兜底，「还没查到」不会固化为「真没有」。</p>
 *
 * <p>线程约定：{@link #locate} 自带 {@link ReadAction} 包裹，EDT（右键菜单）与后台线程均可直调；
 * FileBasedIndex 查询 + PSI 只读，无写操作。</p>
 */
public final class MapperXmlLocator {

    public static final MapperXmlLocator INSTANCE = new MapperXmlLocator();

    private static final Logger LOG = Logger.getInstance(MapperXmlLocator.class);

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private MapperXmlLocator() {
    }

    /** 定位三态（T-MapperGutterEvict P2.1 同构，2026-11-16）：确定命中 / 确定没有 / 暂时查不了。 */
    public sealed interface Lookup permits Found, Absent, Unknown {
    }

    /** 确定命中。 */
    public record Found(@NotNull Location location) implements Lookup {
    }

    /** 确定没有——调用方据此收回正缓存并摘图标。 */
    public enum Absent implements Lookup {
        ABSENT
    }

    /** 暂时查不了（索引重建中）——调用方保留已确认的图标、不动缓存。 */
    public enum Unknown implements Lookup {
        UNKNOWN
    }

    /** 测试缝：替换三态定位（生产＝null）。矩阵用例用它驱动 Unknown / Absent 分支。 */
    @Nullable
    public static volatile BiFunction<Project, String, Lookup> lookupOverrideForTest = null;

    /** 定位结果：mapper XML 文件 + 语句标签起始行（0-based，OpenFileDescriptor 直用）。 */
    public record Location(
            /** mapper XML 虚拟文件。 */
            @NotNull VirtualFile virtualFile,
            /** 语句标签起始行（0-based）。 */
            int line
    ) {
    }

    /**
     * 三态定位——供 gutter provider 区分「暂时查不了」与「确定没有」：
     * 前者保留已确认的图标（F1' 的 S2 兜底），后者<b>收回正缓存并摘图标</b>（P2.1）。
     * {@code IndexNotReadyException}（索引重建中）归 {@link Unknown}；ProcessCanceledException 仍上抛。
     */
    @NotNull
    public static Lookup locateOrUnknown(@NotNull Project project, @NotNull String statementId) {
        BiFunction<Project, String, Lookup> override = lookupOverrideForTest;
        if (override != null) {
            return override.apply(project, statementId);
        }
        Location location;
        try {
            location = locate(project, statementId);
        } catch (IndexNotReadyException e) {
            return Unknown.UNKNOWN;
        }
        return location != null ? new Found(location) : Absent.ABSENT;
    }

    /**
     * 定位语句在 mapper XML 中的位置；任一环失败返回 null（调用方回退 Java 跳转）。
     *
     * @param statementId 完整语句 id（{@code namespace.id}，如 {@code com.example.mapper.UserMapper.selectById}）
     */
    @Nullable
    public static Location locate(@NotNull Project project, @NotNull String statementId) {
        kotlin.Pair<String, String> parts = splitStatementId(statementId);
        if (parts == null) {
            return null;
        }
        String namespace = parts.getFirst();
        String id = parts.getSecond();
        return ReadAction.compute(() -> {
            List<VirtualFile> candidates = MapperNamespaceIndex.getMapperXmlFiles(project, namespace);
            XmlFile firstFile = null;
            XmlTag firstTag = null;
            int extraHits = 0;
            for (VirtualFile vf : candidates) {
                var psiFile = PsiManager.getInstance(project).findFile(vf);
                if (!(psiFile instanceof XmlFile file)) {
                    continue;
                }
                XmlTag tag = findStatementTag(file, namespace, id);
                if (tag == null) {
                    continue;
                }
                if (firstFile == null) {
                    firstFile = file;
                    firstTag = tag;
                } else {
                    extraHits++;
                }
            }
            if (extraHits > 0 && firstFile != null && firstFile.getVirtualFile() != null) {
                LOG.info("MapperXmlLocator: 语句 " + namespace + "." + id + " 命中 " + (extraHits + 1)
                        + " 个 mapper XML（多模块同名），取第一个：" + firstFile.getVirtualFile().getPath());
            }
            if (firstFile == null || firstTag == null) {
                return null;
            }
            VirtualFile vf = firstFile.getVirtualFile();
            if (vf == null) {
                return null;
            }
            var document = PsiDocumentManager.getInstance(project).getDocument(firstFile);
            if (document == null) {
                return null;
            }
            return new Location(vf, document.getLineNumber(firstTag.getTextRange().getStartOffset()));
        });
    }

    /** 拆 {@code namespace.id}（按最后一个 {@code .} 分段——namespace 本身含点，语句 id 惯例不含）；形态不符返回 null。 */
    @Nullable
    public static kotlin.Pair<String, String> splitStatementId(@NotNull String statementId) {
        String s = statementId.trim();
        if (s.isEmpty()) {
            return null;
        }
        int idx = s.lastIndexOf('.');
        if (idx <= 0 || idx == s.length() - 1) {
            return null;
        }
        return new kotlin.Pair<>(s.substring(0, idx), s.substring(idx + 1));
    }

    /**
     * 单文件内定位语句标签：namespace 属性精确匹配（{@link #splitStatementId} 的 namespace 段），
     * 语句标签（同款集合）为 {@code <mapper>} 直接子级且 id 相等。
     */
    @Nullable
    static XmlTag findStatementTag(@NotNull XmlFile file, @NotNull String namespace, @NotNull String id) {
        XmlTag root = file.getRootTag();
        if (root == null || !namespace.equals(root.getAttributeValue("namespace"))) {
            return null;
        }
        for (XmlTag tag : root.getSubTags()) {
            if (STATEMENT_TAGS.contains(tag.getName()) && id.equals(tag.getAttributeValue("id"))) {
                return tag;
            }
        }
        return null;
    }
}
