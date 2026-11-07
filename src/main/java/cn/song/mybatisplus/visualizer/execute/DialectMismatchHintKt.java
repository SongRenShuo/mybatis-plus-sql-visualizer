package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UI-049（2026-11-04 用户真机）：空 Runner「方言不匹配」态的**呈现层**提示构造（顶层函数门面）。
 *
 * <p>本文件只做呈现层的两件事，不碰 Runner 归因：</p>
 * <ol>
 *   <li>用 JDBC URL 子协议（{@link UrlSubprotocolDialects}，与 T-DialectAutoInfer 同源）挑出「看起来就是
 *       目标方言」的源，供文案点名；挑不出就诚实说挑不出。</li>
 *   <li>把可见行（一句话）与悬停详文（折叠其余源）拆成两个 bundle 模板。</li>
 * </ol>
 */
final class DialectMismatchHintKt {

    private DialectMismatchHintKt() {
    }

    /**
     * 平台 SQL language id / 产品别名 → 统一比较面。
     *
     * <p>{@code renderedDialectName} 有渲染事实时是 {@code cn.song.mybatisplus.visualizer.sql.Dialect.name}
     * （{@code SQL Server}），回退路径却是平台 language id（{@code TSQL}）——两者必须能对上，否则 URL 推断
     * 会对 SQL Server 源全部漏点名。</p>
     */
    private static final Map<String, String> DIALECT_NAME_ALIASES = Map.of(
            "tsql", "sql server",
            "mssql", "sql server",
            "dameng", "dm",
            "kingbasees", "kingbase"
    );

    /** 文案里最多逐个点名的推断源数量（其余折叠，避免又出现一长串）。 */
    static final int MAX_NAMED_SOURCES = 5;

    /**
     * 方言名归一化：剥掉中文/英文括号后缀、转小写，并把平台的 language id 别名对齐到
     * {@link UrlSubprotocolDialects} 的产品名（{@code TSQL}/{@code mssql} → {@code sql server}）。
     *
     * <p>目的只有一个：让 {@link UrlSubprotocolDialects} 的值（如 {@code DM（达梦）}、{@code SQL Server}）与运行时
     * 渲染方言名（可能只是 {@code DM}，也可能是平台 language id {@code TSQL}）能落在同一比较面。不做方言家族
     * 合并——MariaDB 不等于 MySQL，归因准确性优先。</p>
     */
    static String normalizeDialectNameForMatch(@NotNull String name) {
        String stripped = substringBefore(substringBefore(name, '（'), '(')
                .trim()
                .toLowerCase(Locale.ROOT);
        return DIALECT_NAME_ALIASES.getOrDefault(stripped, stripped);
    }

    private static String substringBefore(String s, char delimiter) {
        int idx = s.indexOf(delimiter);
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    /** 纯函数：从在册源里挑出 URL 子协议映射到 {@code dialectDisplay} 的源（保持原顺序）。 */
    static List<RegisteredJdbcSource> inferLikelyDialectSources(
            @NotNull String dialectDisplay,
            @NotNull List<RegisteredJdbcSource> sources
    ) {
        String target = normalizeDialectNameForMatch(dialectDisplay);
        if (target.isEmpty()) return List.of();
        return sources.stream()
                .filter(source -> {
                    String mapped = UrlSubprotocolDialects.dialectName(source.url());
                    if (mapped == null) return false;
                    return normalizeDialectNameForMatch(mapped).equals(target);
                })
                .collect(Collectors.toList());
    }

    /**
     * 平台侧适配：在册 {@code LocalDataSource} 列表 → {@link DialectMismatchHint}。
     *
     * <p>取名/取 URL 全部走既有反射入口（{@code DataSourceSettings.getName} / {@code ExecDialectRouting.dataSourceUrl}），
     * Community 或平台异常时退化为「无名字/无 URL」，不抛。</p>
     */
    static DialectMismatchHint buildDialectMismatchHint(
            @NotNull String dialectDisplay,
            @NotNull List<Object> registered
    ) {
        List<RegisteredJdbcSource> sources = registered.stream()
                .map(ds -> new RegisteredJdbcSource(
                        DataSourceSettings.getName(ds),
                        ExecDialectRouting.dataSourceUrl(ds),
                        ds))
                .collect(Collectors.toList());
        return new DialectMismatchHint(
                dialectDisplay,
                sources.size(),
                inferLikelyDialectSources(dialectDisplay, sources));
    }

    /**
     * 状态行可见文案（一句话，过长由 JLabel 省略、悬停看 {@link #detailMessage}）。
     *
     * <p>标题写「下一步」而不是「执行失败」：这是缺配置，不是插件坏掉。</p>
     *
     * <p>master 侧为 Kotlin 顶层 internal fun（字节码 public，ui 包可直呼）；Java 承载对齐 public
     * ——A4c 波次 SqlPreviewPanelExecution 的 showDialectMismatchHint 跨包调用（写集外最小可见性补齐）。</p>
     */
    public static String statusMessage(@NotNull DialectMismatchHint hint) {
        // 先取值再判空：uniqueLikely 是自定义访问器，无智能转换可依赖。
        RegisteredJdbcSource unique = hint.uniqueLikely();
        if (unique != null) {
            return MpSqlVisualizerBundle.message(
                    "execute.datasource.dialectMismatch.status.unique",
                    unique.name(), hint.dialectDisplay());
        }
        if (hint.likely().size() > 1) {
            return MpSqlVisualizerBundle.message(
                    "execute.datasource.dialectMismatch.status.multiple",
                    hint.likely().size(), hint.dialectDisplay());
        }
        return MpSqlVisualizerBundle.message(
                "execute.datasource.dialectMismatch.status.unknown",
                hint.dialectDisplay());
    }

    /**
     * 悬停详文：解释归因（缺配置）、点名推断源、折叠其余源，并说明属性页底部 Dialect 下拉的位置。
     * 可见性对齐 master internal（字节码 public），见 {@link #statusMessage} 注。
     */
    public static String detailMessage(@NotNull DialectMismatchHint hint) {
        RegisteredJdbcSource unique = hint.uniqueLikely();
        if (unique != null) {
            return MpSqlVisualizerBundle.message(
                    "execute.datasource.dialectMismatch.detail.unique",
                    hint.totalRegistered(), hint.dialectDisplay(), unique.name(),
                    unique.url() == null ? "" : unique.url())
                    + foldSuffix(hint);
        }
        if (hint.likely().size() > 1) {
            return MpSqlVisualizerBundle.message(
                    "execute.datasource.dialectMismatch.detail.multiple",
                    hint.totalRegistered(), hint.dialectDisplay(), hint.likely().size(),
                    likelyNamesSummary(hint))
                    + foldSuffix(hint);
        }
        return MpSqlVisualizerBundle.message(
                "execute.datasource.dialectMismatch.detail.unknown",
                hint.totalRegistered(), hint.dialectDisplay());
    }

    /** 其余源折叠说明：0 个时不追加，避免「另有 0 个源」这类噪音。 */
    private static String foldSuffix(@NotNull DialectMismatchHint hint) {
        if (hint.otherCount() > 0) {
            return "\n\n" + MpSqlVisualizerBundle.message(
                    "execute.datasource.dialectMismatch.detail.fold",
                    hint.otherCount(), hint.dialectDisplay());
        }
        return "";
    }

    /** 推断源名字摘要：最多点名 5 个，其余折叠为「等 N 个」。 */
    private static String likelyNamesSummary(@NotNull DialectMismatchHint hint) {
        String shown = hint.likely().stream()
                .limit(MAX_NAMED_SOURCES)
                .map(RegisteredJdbcSource::name)
                .collect(Collectors.joining(", "));
        int hidden = hint.likely().size() - MAX_NAMED_SOURCES;
        if (hidden > 0) {
            return shown + " " + MpSqlVisualizerBundle.message(
                    "execute.datasource.dialectMismatch.names.more", hidden);
        }
        return shown;
    }
}
