package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * R2（W2 体验包）：gutter 按来源分组显隐——组 → provider 映射单一事实源。
 *
 * <p>动机＝MyBatisX「图标太大太醒目」差评多轮；此前仅全局布尔 showGutterIcon，
 * 15 个 lineMarkerProvider 无分组。组口径（默认全开=现行为不变）：
 * <ul>
 *  <li>MAPPER_XML：mapper XML 语句（select/insert/update/delete 文本 + pages/hbm 命名查询）</li>
 *  <li>ANNOTATION：注解 SQL（{@code @Select} 族 + {@code @NamedQuery}）</li>
 *  <li>JPA_HQL：JPA/HQL 族（JPA 仓储 / JPQL / Criteria / HQL / 自研 DAO HQL）</li>
 *  <li>JDBC：JdbcTemplate 字符串 SQL</li>
 *  <li>INLINE_FALLBACK：通用 XML 内嵌兜底（第四通道）</li>
 *  <li>OTHER：MP Wrapper 链 / 方法名字符串 SQL 等其余</li>
 * </ul>
 *
 * <p>{@link #enabledFor(Class, boolean)} 为平台包装（读设置，测试环境兜底全开）；
 * {@link #groupOf(String)}/{@link #groupEnabled(String, PluginSettings.State)} 纯逻辑面独立可测。
 *
 * <p>（Kotlin {@code object GutterGroups} 翻译：成员全静态——无实例状态，且既有 Kotlin 测试
 * 以 {@code GutterGroups.groupOf(...)} 对象成员形态调用，静态承载可互操作。）
 */
public final class GutterGroups {

    /** 组枚举（设置页每组一个开关）。 */
    public static final List<String> GROUPS =
            List.of("mapperXml", "annotation", "jpaHql", "jdbc", "inlineFallback", "other");

    /** provider 类 simpleName → 组（未登记 = other）。 */
    private static final Map<String, String> PROVIDER_GROUP = Map.ofEntries(
            Map.entry("XmlSqlGutterLineMarkerProvider", "mapperXml"),
            Map.entry("PagesXmlGutterLineMarkerProvider", "mapperXml"),
            Map.entry("HbmNamedQueryGutterLineMarkerProvider", "mapperXml"),
            Map.entry("MapperXmlToJavaLineMarkerProvider", "mapperXml"),
            Map.entry("MapperJavaToXmlLineMarkerProvider", "mapperXml"),
            Map.entry("AnnotationSqlGutterLineMarkerProvider", "annotation"),
            Map.entry("NamedQueryGutterLineMarkerProvider", "annotation"),
            Map.entry("JpaGutterLineMarkerProvider", "jpaHql"),
            Map.entry("JpqlGutterLineMarkerProvider", "jpaHql"),
            Map.entry("CriteriaQueryGutterLineMarkerProvider", "jpaHql"),
            Map.entry("HqlGutterLineMarkerProvider", "jpaHql"),
            Map.entry("CustomDaoHqlGutterLineMarkerProvider", "jpaHql"),
            Map.entry("JdbcTemplateSqlGutterLineMarkerProvider", "jdbc"),
            Map.entry("XmlInlineSqlGutterLineMarkerProvider", "inlineFallback"),
            Map.entry("SqlGutterLineMarkerProvider", "other"),
            Map.entry("StringSqlMethodGutterLineMarkerProvider", "other"));

    private GutterGroups() {
    }

    /** provider 类所属组（未登记 = other）。 */
    @NotNull
    public static String groupOf(@NotNull String providerSimpleName) {
        String group = PROVIDER_GROUP.get(providerSimpleName);
        return group != null ? group : "other";
    }

    /** 该 provider 的组开关是否开启（全局 showGutterIcon 总闸在前；测试环境兜底全开）。 */
    public static boolean enabledFor(@NotNull Class<?> providerClass, boolean showGutterIcon) {
        if (!showGutterIcon) return false;
        try { // Kotlin runCatching { … }.getOrDefault(true) 语义
            return groupAllowed(providerClass, PluginSettings.getInstance().getState());
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * F36（T-PerfSweep16）：调用方已持有 State 的重载——provider 在 getLineMarkerInfo 方法头
     * 单次取 state 后传入，消掉原 {@code (Class, Boolean)} 路径里包装层与本函数内部的重复
     * app 级服务查找（每到达元素 3 次 → 1 次）。
     *
     * <p>语义与 {@code (Class, Boolean)} 版逐值等价：state 非空时总闸（{@code showGutterIcon}）与组开关判定
     * 同现状；state 为 null（设置服务不可读）承接失败回退语义返回 true（全开）。
     * 组判定共用 {@link #groupAllowed(Class, PluginSettings.State)} 单一事实。
     */
    public static boolean enabledFor(@NotNull Class<?> providerClass, @Nullable PluginSettings.State state) {
        if (state == null) return true; // 设置读取失败回退：同 runCatching { … }.getOrDefault(true)
        if (!state.getShowGutterIcon()) return false;
        return groupAllowed(providerClass, state);
    }

    /** 组判定私有实现（两个重载共用）：组解析 + 组开关读取的单一事实源。 */
    private static boolean groupAllowed(@NotNull Class<?> providerClass, @NotNull PluginSettings.State state) {
        return groupEnabled(groupOf(providerClass.getSimpleName()), state);
    }

    /** 纯逻辑面（可测）：组开关读取（组 → State 字段映射单一事实）。 */
    public static boolean groupEnabled(@NotNull String group, @NotNull PluginSettings.State s) {
        return switch (group) {
            case "mapperXml" -> s.getGutterGroupMapperXml();
            case "annotation" -> s.getGutterGroupAnnotation();
            case "jpaHql" -> s.getGutterGroupJpaHql();
            case "jdbc" -> s.getGutterGroupJdbc();
            case "inlineFallback" -> s.getGutterGroupInlineFallback();
            default -> s.getGutterGroupOther();
        };
    }
}
