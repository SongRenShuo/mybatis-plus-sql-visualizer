// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlInlineSqlDetector.kt 移植
package cn.song.mybatisplus.visualizer.xml;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 通用 XML 内嵌 SQL 检测（O-72 T-XmlInlineSql）。
 *
 * <p>背景：RFListPage 等自研框架 XML 把 SQL/HQL 写在自定义标签的 CDATA/文本里，
 * 根元素名不固定（用户实证），mapper/pages/hbm 三套「根元素+标签名」白名单全部
 * 不认领。本检测<b>锚定内容不锚定标签名</b>：叶子标签 + 文本首词命中 SQL 关键字
 * 即视为 SQL 载体，对任何框架形态生效。</p>
 *
 * <ul>
 *  <li>认领条件（{@link #isSqlCarrierTag}）：无子标签 + 首段非空文本的前缀词 ∈ {@link #SQL_KEYWORDS}；</li>
 *  <li>排除既有认领（{@link #isClaimedByKnownStructure}）：mapper 语句 / hbm 命名查询 /
 *      pages 的 {@code <datasource>}（图标在 maintainPage 上）——防止既有文件双图标；</li>
 *  <li>HQL 判别（{@link #queryTypeOf}）：自身或祖先标签的 {@code type} 属性 ∈ {hql, jpql}
 *      （就近优先），仅预览链（后台线程）调用，不进 gutter 快速路径。</li>
 * </ul>
 *
 * <p><b>性能红线</b>：gutter 快速路径只做局部判定——叶子判定 + 首段非空文本截断前缀
 * （{@link #PREFIX_PROBE_LENGTH} 字符），零 resolve、零全文件遍历、零转换。</p>
 */
public final class XmlInlineSqlDetector {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final XmlInlineSqlDetector INSTANCE = new XmlInlineSqlDetector();

    private XmlInlineSqlDetector() {
    }

    /** 首词命中即认领（宽口径，决策 D2）：SQL 关键字集，大小写不敏感。 */
    public static final Set<String> SQL_KEYWORDS =
            Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE");

    /** type 属性取这些值时按 HQL 走 {@link cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter} 管道。 */
    private static final Set<String> HQL_TYPES = Set.of("hql", "jpql");

    /** gutter 快速路径的文本探测上限（只影响首词判定，不影响预览时的完整提取）。 */
    public static final int PREFIX_PROBE_LENGTH = 64;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 与 XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS 同源（引用而非复写，防两处漂移）。 */
    private static final Set<String> MAPPER_STATEMENT_TAGS = XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS;

    private static final Set<String> HBM_QUERY_TAGS = HbmNamedQueryGutterLineMarkerProvider.QUERY_TAGS;

    /** 判定 tag 是否为通用 SQL 载体标签（叶子 + 文本像 SQL + 未被既有结构认领）。 */
    public static boolean isSqlCarrierTag(@NotNull XmlTag tag) {
        if (tag.getSubTags().length > 0) return false;
        if (isClaimedByKnownStructure(tag)) return false;
        String firstWord = firstTextWord(tag);
        if (firstWord == null) return false;
        return SQL_KEYWORDS.contains(firstWord);
    }

    /** 该标签是否已被 mapper / hbm / pages 三套既有白名单认领（通用检测必须让位，防双图标）。 */
    public static boolean isClaimedByKnownStructure(@NotNull XmlTag tag) {
        String name = tag.getName();
        XmlTag parentTag = tag.getParentTag();
        String parentName = parentTag != null ? parentTag.getName() : null;
        // mapper 语句（XmlSqlGutterLineMarkerProvider：STATEMENT_TAGS 且父标签 mapper）
        if (MAPPER_STATEMENT_TAGS.contains(name) && "mapper".equals(parentName)) return true;
        // hbm 命名查询（HbmNamedQueryGutterLineMarkerProvider：query/sql-query 且父标签 hibernate-mapping）
        if (HBM_QUERY_TAGS.contains(name) && "hibernate-mapping".equals(parentName)) return true;
        // pages 的 SQL 载体（PagesXmlGutterLineMarkerProvider 图标锚在页面标签上）。
        // T-PagesHql：页面标签名不固定（maintainPage / listPage / …），统一按「<pages> 的直接子级」
        // 判定；`<maintainPage>` 兼容旧形态（历史文件可能不在 <pages> 下）。
        if ("datasource".equals(name)
                && ("maintainPage".equals(parentName)
                    || (parentTag != null && parentTag.getParentTag() != null
                        && "pages".equals(parentTag.getParentTag().getName())))) {
            return true;
        }
        return false;
    }

    /** 首段非空文本的截断前缀首词（大写；无文本 → null）。 */
    @Nullable
    public static String firstTextWord(@NotNull XmlTag tag) {
        String prefix = firstNonBlankTextPrefix(tag);
        if (prefix == null) return null;
        String[] parts = WHITESPACE.split(prefix.trim());
        String word = parts.length > 0 ? parts[0] : "";
        String upper = word.toUpperCase(Locale.ROOT);
        return upper.isEmpty() ? null : upper;
    }

    /** 完整提取载体标签内 SQL 文本（直属文本段拼接，CDATA 剥包裹，trim；口径见 {@link XmlDirectText#directTextOf}）。 */
    public static String extractSqlText(@NotNull XmlTag tag) {
        return XmlDirectText.directTextOf(tag);
    }

    /** 自身或祖先标签的 {@code type} 属性值（就近优先，小写；无 → null）。 */
    @Nullable
    public static String queryTypeOf(@NotNull XmlTag tag) {
        XmlTag cur = tag;
        while (cur != null) {
            String type = cur.getAttributeValue("type");
            if (type != null && !type.isBlank()) {
                return type.trim().toLowerCase(Locale.ROOT);
            }
            cur = cur.getParentTag();
        }
        return null;
    }

    /** {@link #queryTypeOf} 是否判为 HQL 族（hql/jpql）——预览链据此走 HqlConverter。 */
    public static boolean isHqlType(@NotNull XmlTag tag) {
        // queryTypeOf 可空，Set.of.contains(null) 会 NPE，须先判空
        String type = queryTypeOf(tag);
        return type != null && HQL_TYPES.contains(type);
    }

    /** 通用检测总开关（设置页可关；测试环境 service 未注册时按默认开）。 */
    public static boolean detectionEnabled() {
        try { // Kotlin runCatching.getOrDefault(true) 语义
            return PluginSettings.getInstance().getState().getXmlInlineSqlEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * gutter 快速路径合并开关（F19：一次设置服务查找取两布尔；master 语义锚点，
     * F51 起生产路径不再调用，函数保留供既有测试使用）。
     */
    public static boolean gutterFastPathEnabled() {
        try { // Kotlin runCatching.getOrDefault(true) 语义
            PluginSettings.State s = PluginSettings.getInstance().getState();
            return s.getXmlInlineSqlEnabled() && s.getShowGutterIcon();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 首段非空文本的截断前缀（遍历直属文本段，剥 CDATA 包裹、跳过纯空白段；无 → null）。
     *  master 对应成员为 internal（Kotlin 同模块测试可访问），Java 侧按惯例放宽为 public。 */
    @Nullable
    public static String firstNonBlankTextPrefix(@NotNull XmlTag tag) {
        for (PsiElement child : tag.getChildren()) {
            if (!(child instanceof XmlText text)) continue;
            String trimmed = XmlDirectText.unwrapCdata(text.getText()).trim();
            if (!trimmed.isEmpty()) {
                return trimmed.substring(0, Math.min(PREFIX_PROBE_LENGTH, trimmed.length()));
            }
        }
        return null;
    }
}
