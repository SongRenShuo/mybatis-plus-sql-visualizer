package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * O-136 T-PreviewExecDsDialect：预览执行链「数据源方言 → 草稿文件 SQL 方言 → Runner 集合」路由核心。
 *
 * <p>根因（计划 §二，243 平台 jar 字节码实证）：平台
 * {@code DatabaseRunners.getAttachDataSourceRunners → DbImplUtil.getAppropriateDataSourcesForExec(psiFile)}
 * 按 <b>psiFile 的 SQL 方言</b>（SqlDialectMappings 的文件级映射）取 {@code DatabaseDialect}，再以
 * {@code ds.getDatabaseDialect().similarTo(文件方言)} 过滤数据源——插件此前把 exec 草稿恒映射 MySQL，
 * DM 等非 MySQL 数据源的 Runner 全被排除。</p>
 *
 * <p>反射隔离约定：平台类一律 {@code Class.forName} 反射 + try/catch，Community/无 Database 插件下降级
 * null（调用方保持 MySQL 现状），与 {@link DataSourceSettings} 同款口径。</p>
 */
final class ExecDialectRouting {

    private static final Logger LOG = Logger.getInstance(ExecDialectRouting.class);

    private ExecDialectRouting() {
    }

    /** 组 Runner 展开最大轮数：平台当前最多两级（{@code DatabaseRunners$4 → $4$1}）。 */
    private static final int MAX_GROUP_DEPTH = 3;

    /** 平台 MySQL SQL 方言 language id（{@code DialectPreMapping.findMySql} 同款判定口径）。 */
    static final String MYSQL_LANGUAGE_ID = "MySQL";
    static final String GENERIC_LANGUAGE_ID = "GenericSQL";

    /**
     * 插件方言名（DialectRegistry 命名空间，键须与 {@code Dialect.getName()} 逐字一致）→
     * 平台 SQL 方言 language id 候选清单（按序尝试，命中平台可用清单为止）。
     *
     * <p>id 依据：243 平台 com/intellij/sql/dialects 目录下各方言 xml 的
     * {@code lang.parserDefinition language} 逐文件核对——MySQL / PostgreSQL / Oracle / MariaDB /
     * TSQL(mssql) / H2 为 243 在册实锤；DM / KingBase / OceanBase / openGauss / SQLite 在 243 方言集缺位，
     * 候选 id 取产品名常见注册形态，真平台缺位时 {@link #urlFallbackId} 自然落 null（不猜近似方言）。
     * 家族别名仅作平台缺位时的尽力兜底（如 OceanBase MySQL 模式 → MySQL），别名错误最多
     * 令 Runner 枚举落空（与现状同态），不产生错误执行。</p>
     */
    static final Map<String, List<String>> PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS = createDialectIds();

    private static Map<String, List<String>> createDialectIds() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("MySQL", List.of("MySQL"));
        map.put("MariaDB", List.of("MariaDB", "MySQL"));
        map.put("PostgreSQL", List.of("PostgreSQL"));
        map.put("Oracle", List.of("Oracle"));
        map.put("SQL Server", List.of("TSQL"));
        map.put("H2", List.of("H2"));
        map.put("SQLite", List.of("SQLite"));
        map.put("DM（达梦）", List.of("DM", "Dameng"));
        map.put("KingBase（人大金仓）", List.of("KingBase", "KingbaseES", "PostgreSQL"));
        map.put("OceanBase", List.of("OceanBase", "MySQL"));
        map.put("openGauss", List.of("openGauss", "GaussDB", "PostgreSQL"));
        return Map.copyOf(map);
    }

    /** 目标数据源解析测试缝（headless 无 DataSourceStorage；生产恒 null 走真链）。 */
    public interface TargetDataSourceProvider {
        @Nullable Object provide(Project project);
    }

    public static volatile TargetDataSourceProvider targetDataSourceForTest = null;

    /** last-used 记忆的轻量投影（与 DataSourceSelector.LastUsedMemory 同构，测试面免平台类型）。 */
    record MemoryRef(@Nullable String dataSourceId, @Nullable String displayName) {
    }

    // ------------------------------------------------------------------
    // 纯逻辑（表驱动单测）
    // ------------------------------------------------------------------

    /** 目标方言 id 决策：平台 DAS 方言优先，URL 推断兜底；皆无（或空白）→ null = 保持 MySQL 映射。 */
    static String targetLanguageId(@Nullable String platformDialectId, @Nullable String urlFallbackId) {
        if (platformDialectId != null && !platformDialectId.isBlank()) {
            return platformDialectId;
        }
        if (urlFallbackId != null && !urlFallbackId.isBlank()) {
            return urlFallbackId;
        }
        return null;
    }

    /** 目标数据源解析（纯函数）：记忆 UUID 精确命中 → 显示名唯一命中 → 插件默认数据源 → null。 */
    static Object resolveTargetDataSourceFromInputs(
            @Nullable MemoryRef memory,
            Function<String, Object> dataSourceById,
            List<Object> registeredDataSources,
            Function<Object, String> dataSourceName,
            java.util.function.Supplier<Object> defaultDataSource) {
        if (memory != null) {
            if (memory.dataSourceId() != null && !memory.dataSourceId().isBlank()) {
                Object byId = dataSourceById.apply(memory.dataSourceId());
                if (byId != null) {
                    return byId;
                }
            }
            if (memory.displayName() != null && !memory.displayName().isBlank()) {
                for (Object ds : registeredDataSources) {
                    if (dataSourceName.apply(ds).equals(memory.displayName())) {
                        return ds;
                    }
                }
            }
        }
        return defaultDataSource.get();
    }

    /**
     * URL 兜底：数据源 URL → 插件子协议推断 → 平台 SQL 方言 language id 候选 → 平台可用清单命中。
     * 任何一步不可得 → null（调用方保持 MySQL，不猜）。
     */
    static String urlFallbackId(@Nullable Object ds, List<String> availableIds) {
        if (ds == null) {
            return null;
        }
        String url = dataSourceUrl(ds);
        if (url == null) {
            return null;
        }
        String pluginDialect = UrlSubprotocolDialects.dialectName(url);
        if (pluginDialect == null) {
            return null;
        }
        for (String candidate : PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS.getOrDefault(pluginDialect, List.of())) {
            for (String available : availableIds) {
                if (available.equalsIgnoreCase(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 组 Runner 展开判定（纯函数）：该 runner 是否携带非空子 Runner 列表。
     *
     * <p>空子项组<b>保留原项</b>（subs 为空与「非组项」同支）：平台「All」组在数据源
     * introspect 完成前子 Runner 为空，此时组项仍是唯一执行句柄——按空展开剔除会把 Runner
     * 列表清空、命中空 Runner 归因文案并终结执行链（20261014 testDbConsole 集成回归实证）。</p>
     */
    static <T> List<T> expandGroupRunners(List<T> runners, Function<T, String> nameOf,
                                          Function<T, List<T>> subRunnersOf) {
        List<T> out = new ArrayList<>(runners.size());
        Set<String> seen = new HashSet<>();
        for (T r : runners) {
            List<T> subs = subRunnersOf.apply(r);
            if (subs == null || subs.isEmpty()) {
                if (seen.add(nameOf.apply(r))) {
                    out.add(r);
                }
                continue;
            }
            // 组项本体剔除（有子项可浮出时组项不再承载可选项）；子 Runner 浮出顶层、按显示名去重
            for (T sub : subs) {
                if (seen.add(nameOf.apply(sub))) {
                    out.add(sub);
                }
            }
        }
        return out;
    }

    /** 空 Runner 文案选择（D3）：项目存在已配置数据源 → 方言不匹配可行动文案；否则既有空数据源引导。 */
    static String emptyRunnersHintKey(boolean hasRegisteredDataSources) {
        return hasRegisteredDataSources ? "execute.datasource.dialectMismatch" : "execute.datasource.empty";
    }

    // ------------------------------------------------------------------
    // 反射段（平台不可用一律降级 null / 空清单）
    // ------------------------------------------------------------------

    /** 数据源 JDBC URL（LocalDataSource.getUrl() 反射；取不到 null）。 */
    static String dataSourceUrl(@Nullable Object ds) {
        if (ds == null) {
            return null;
        }
        try {
            Object value = ds.getClass().getMethod("getUrl").invoke(ds);
            return value instanceof String s ? s : null;
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return null;
        }
    }

    /**
     * 平台方言探测：{@code DbSqlUtilCore.findSqlDialect(LocalDataSource)} 的 language id——
     * 数据源自身方言设置 → 平台按连接配置推断，null = 平台不可用/数据源无方言。
     * 刻意用可空的 findSqlDialect 而非 getSqlDialect（后者兜底 Generic 方言，会把枚举引向空集）。
     */
    static String platformDialectId(@Nullable Object ds) {
        if (ds == null) {
            return null;
        }
        try {
            Class<?> core = Class.forName("com.intellij.database.util.DbSqlUtilCore");
            Method method = core.getMethod("findSqlDialect",
                    Class.forName("com.intellij.database.dataSource.LocalDataSource"));
            Object dialect = method.invoke(null, ds);
            if (dialect == null) {
                return null;
            }
            return dialect instanceof com.intellij.lang.Language language ? language.getID() : null;
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return null;
        }
    }

    /** 平台可用 SQL 方言 language id 清单（SqlDialectMappings.getAvailableValues 投影；失败空清单）。 */
    static List<String> availableLanguageIds(Project project) {
        try {
            Object mappings = Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
                    .getMethod("getInstance", Project.class).invoke(null, project);
            Object values = mappings.getClass().getMethod("getAvailableValues").invoke(mappings);
            if (!(values instanceof List<?> list)) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (Object value : list) {
                if (value instanceof com.intellij.lang.Language language) {
                    ids.add(language.getID());
                }
            }
            return ids;
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return List.of();
        }
    }

    /**
     * 目标 SQL Language 实例（DialectPreMapping.setMapping 消费形态）：
     * 平台方言 id → URL 兜底 id → 平台可用清单内取 Language；null = 保持 MySQL 映射（零回归）。
     */
    static Object resolveTargetLanguage(Project project, @Nullable Object ds) {
        if (ds == null) {
            return null;
        }
        String platform;
        try {
            platform = platformDialectId(ds);
        } catch (Throwable t) {
            platform = null;
        }
        String url;
        try {
            url = urlFallbackId(ds, availableLanguageIds(project));
        } catch (Throwable t) {
            url = null;
        }
        String targetId = targetLanguageId(platform, url);
        if (targetId == null) {
            return null;
        }
        try {
            return DialectPreMapping.languageById(project, targetId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 插件渲染方言 → 平台 SQL Language。按候选顺序命中；平台无对应方言时诚实返回 null。 */
    static Object resolvePluginDialectLanguage(Project project, @Nullable String dialectName) {
        if (dialectName == null || dialectName.isBlank()) {
            return null;
        }
        for (String id : PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS.getOrDefault(dialectName, List.of())) {
            try {
                Object language = DialectPreMapping.languageById(project, id);
                if (language != null) {
                    return language;
                }
            } catch (Throwable ignored) {
                // Kotlin runCatching 等价：单候选失败继续下一候选
            }
        }
        return null;
    }

    /** 目标数据源解析（生产链）：last-used 记忆（UUID→显示名）→ 插件默认数据源 → null。 */
    static Object resolveTargetDataSource(Project project) {
        TargetDataSourceProvider testProvider = targetDataSourceForTest;
        if (testProvider != null) {
            return testProvider.provide(project);
        }
        MemoryRef memory = null;
        try {
            DataSourceSelector.LastUsedMemory lastUsed = DataSourceSelector.lastUsedRunner(project);
            if (lastUsed != null) {
                memory = new MemoryRef(lastUsed.dataSourceId(), lastUsed.displayName());
            }
        } catch (Throwable ignored) {
            // runCatching 等价：记忆不可得即 null 走默认链
        }
        return resolveTargetDataSourceFromInputs(
                memory,
                id -> {
                    try {
                        return DataSourceSettings.getDataSourceById(project, id);
                    } catch (Throwable t) {
                        return null;
                    }
                },
                safeAllDataSources(project),
                ds -> DataSourceSettings.getName(ds),
                () -> {
                    try {
                        return DataSourceSettings.getDefaultDataSource(project);
                    } catch (Throwable t) {
                        return null;
                    }
                });
    }

    private static List<Object> safeAllDataSources(Project project) {
        try {
            return DataSourceSettings.getAllDataSources(project);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 方言名归一（大小写与首尾空白不参与比对；空值返回 null）。 */
    private static String knownDialectName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }

    /**
     * 把 exec 文件重映射到指定平台 SQL 方言（runner 枚举为空时的兜底）。
     *
     * <p>用途：平台 {@code getAttachDataSourceRunners} 按 psiFile 已映射方言筛 runner，数据源无平台
     * Dialects 映射时该方言筛不出 runner。重映射到<b>目标数据源方言</b>后再枚举即可命中，
     * 后续控制台/结果表/审计全部复用既有链路（不新建执行通道）。</p>
     *
     * @return true = 映射已就位（可重试枚举）；false = 平台无该方言或写映射失败
     */
    static boolean remapExecFileDialect(Project project, VirtualFile vf, Object language) {
        try {
            return DialectPreMapping.ensureMapped(project, vf, language, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 组 Runner 展开生产链：反射 {@code getSubRunners()}（平台组 Runner 标志方法）。
     *
     * <p>可访问性（2026-10-19 真机回归）：{@code DatabaseRunners$3} / {@code $4$1} 是包私有类，
     * {@code Method.invoke} 前必须 {@code isAccessible = true}。缺这一行时 {@code IllegalAccessException}
     * 被 runCatching 吞掉，组项被当成普通项保留；其 {@code run()} 绑定 EmptyRunnable，
     * 于是「runRunner invoked OK」后永远等不到 session，直到准备门 60s 超时。</p>
     *
     * <p>展开最多 {@link #MAX_GROUP_DEPTH} 轮（平台存在两级组）；超过深度仍是组项（含自引用）时
     * 剔除并记录——保留空操作组只会把 60s 悬挂留回给用户，剔除后上层走空 Runner 归因文案。</p>
     */
    static List<Object> expandGroupRunners(List<Object> runners) {
        List<Object> current = runners;
        for (int pass = 0; pass < MAX_GROUP_DEPTH; pass++) {
            current = expandGroupRunners(current, DataSourceSelector::getRunnerName,
                    ExecDialectRouting::subRunnersOf);
            boolean anyGroup = false;
            for (Object runner : current) {
                if (isGroupRunner(runner)) {
                    anyGroup = true;
                    break;
                }
            }
            if (!anyGroup) {
                return current;
            }
        }
        for (Object runner : current) {
            if (isGroupRunner(runner)) {
                List<Object> subs = subRunnersOf(runner);
                LOG.warn("[M36] unexpanded group runner dropped after " + MAX_GROUP_DEPTH + " passes: "
                        + runner.getClass().getName() + " (subs=" + (subs == null ? null : subs.size()) + ")");
            }
        }
        List<Object> out = new ArrayList<>();
        for (Object runner : current) {
            if (!isGroupRunner(runner)) {
                out.add(runner);
            }
        }
        return out;
    }

    /** 组 Runner 判定：{@code getSubRunners()} 返回非空列表；反射失败按非组项处理（保持旧行为）。 */
    private static boolean isGroupRunner(Object runner) {
        List<Object> subs = subRunnersOf(runner);
        return subs != null && !subs.isEmpty();
    }

    private static List<Object> subRunnersOf(Object runner) {
        // 桩对象/非平台 Runner 没有该方法：属正常形态，不记日志（生产 Runner 继承基类方法）。
        Method method;
        try {
            method = runner.getClass().getMethod("getSubRunners");
        } catch (Throwable t) {
            return null;
        }
        try {
            // 平台组 Runner（DatabaseRunners$3/$4$1）是包私有类：不设 accessible 会抛
            // IllegalAccessException，展开整链静默降级成「保留组项 → 执行空操作 → 60s 超时」。
            method.setAccessible(true);
            Object value = method.invoke(runner);
            return value instanceof List<?> list ? (List<Object>) list : null;
        } catch (Throwable e) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(e)) {
                throw (Error) e;
            }
            LOG.warn("[M36] getSubRunners reflection failed: " + runner.getClass().getName()
                    + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 空操作 Runner 识别：平台组项（"All"）的 runnable 恒为 {@code EmptyRunnable}。
     * 选中它执行等于什么都不做，必须立即报错，而不是等准备门 60s 超时。
     * 字段声明在 {@code DatabaseRunners$MyRunner}（父类），按继承链查找。
     */
    static boolean isNoOpRunner(Object runner) {
        try {
            Class<?> type = runner.getClass();
            while (type != null) {
                java.lang.reflect.Field field;
                try {
                    field = type.getDeclaredField("runnable");
                } catch (NoSuchFieldException ignored) {
                    field = null;
                }
                if (field != null) {
                    field.setAccessible(true);
                    return field.get(runner) instanceof com.intellij.openapi.util.EmptyRunnable;
                }
                type = type.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return false;
        }
    }
}
