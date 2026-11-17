// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/DatabaseSchemaResolver.kt 移植
package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.execute.JdbcUrlDatabase;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 数据源 schema 解析（M35，方案 B：反射隔离，Community 兼容）。
 *
 * <p>当 IntelliJ IDEA Ultimate / DataGrip 加载了 `com.intellij.database` 插件时，
 * 从已配置的数据源读取指定表的真实字段注释（DB COMMENT），供补全合并——
 * 数据源注释比实体类 `@TableName/@ApiModelProperty` 注释更权威（贴近线上 DDL）。
 *
 * <p>全反射访问，不 import `com.intellij.database` 任何类——Community 运行时该插件不存在
 * 也能编译/加载（与 ExecuteSqlSupport 同一隔离策略）。插件可用性经 `Class.forName`
 * 探测标记类判定，不可用时空转（补全不展示数据源注释，仍用实体注释）。
 *
 * <p>遍历路径：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个数据源的
 * `DasDataSource.getModel()` → `DasModel.traverser()` 迭代筛 `DasTable` → 按名匹配（P7 起追加
 * 「表祖先链含本数据源 URL 的库名」限定，见 {@link #collectFromDataSource}）→
 * `DasObject.getDasChildren(ObjectKind.COLUMN)` → 每列 `getName()/getComment()/getDataType()`。
 *
 * <p>消费面：JOIN 图富化（{@link cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher}）、
 * EXPLAIN 目标表结构、MCP schema 工具，以及 <b>T-DbSchemaCompletion 的补全候选</b>
 * （{@link #columnsCached} 只读缓存 + {@link #prefetchTables} 后台预热——数据源列不再只当注释用，
 * 库里存在而实体类没有的列也进补全候选）。
 */
public final class DatabaseSchemaResolver {

    public static final DatabaseSchemaResolver INSTANCE = new DatabaseSchemaResolver();

    private static final Logger LOG = Logger.getInstance(DatabaseSchemaResolver.class);

    /** database 插件标记类（探测用，M17 同 ExecuteSqlSupport）。 */
    private static final String MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";

    /** DasTable / DasColumn / DasObject 的二进制名（ObjectKind 判类型用）。 */
    private static final String DAS_TABLE = "com.intellij.database.model.DasTable";
    private static final String DAS_COLUMN = "com.intellij.database.model.DasColumn";

    /**
     * O-59 T-IIR-02：{@code DasColumn.Attribute} 里的自增/生成标记名。
     * javap 实证该枚举常量存在（{@code DasColumn$Attribute.AUTO_GENERATED}），故按枚举 {@code toString()} 比对。
     */
    private static final String AUTO_GENERATED_ATTR = "AUTO_GENERATED";
    private static final String DAS_OBJECT = "com.intellij.database.model.DasObject";
    private static final String OBJECT_KIND = "com.intellij.database.model.ObjectKind";

    // T-Embed-10 真机二轮：Method 一律从「声明接口/类」获取，禁止 obj.javaClass.getMethod——
    // 实现类（LocalDataSource 等）在 database-plugin 内部包（非导出），跨插件 classloader 反射
    // invoke 抛 IllegalAccessException（15:54 沙盒日志实证）。getManagers 从接口拿所以一直成功。
    private static final String DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource";
    private static final String DAS_MODEL = "com.intellij.database.model.DasModel";
    private static final String DAS_TYPED_OBJECT = "com.intellij.database.model.DasTypedObject";
    private static final String DATA_TYPE = "com.intellij.database.model.DataType";
    private static final String RAW_CONNECTION_CONFIG = "com.intellij.database.model.RawConnectionConfig";

    /** P7 祖先链（库 → 模式 → 表）的最大回溯层数；超出即视为取不到，不做无界遍历。 */
    private static final int MAX_ANCESTOR_DEPTH = 4;

    /** 列名 → 数据源注释（表名匹配不到返回空 Map，调用方回落实体注释）。 */
    public static Map<String, String> columnComments(Project project, String tableName) {
        Map<String, ColumnMeta> columns = columnMeta(project, tableName);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ColumnMeta> e : columns.entrySet()) {
            out.put(e.getKey(), e.getValue().comment() != null ? e.getValue().comment() : "");
        }
        return out;
    }

    /**
     * T-DbSchemaCompletion：多表 schema 列并集（<b>只读缓存，不触发遍历</b>）。
     *
     * <p>补全 autopopup 热路径专用（T-Perf-09 红线：候选生成不做数据源遍历）——命中返回缓存值，
     * 未命中（或数据源未连接/未预热）返回空 Map，调用方回落到实体列。缓存由 {@link #prefetchTables}
     * 在预览打开、控制台就绪时后台填充（JOIN 富化路径经 {@link #tableMeta} 同源填充）。
     *
     * <p>多表并集按列名去重（同名跨表列取首次命中）：补全上下文是「本文档涉及的表」的扁平视图，
     * 跨表同名列不细分（别名限定补全见计划 §六「明确不做」）。
     *
     * <p>P7：同一表名<b>只认首个持有它的数据源</b>（不再跨数据源并集，见 {@link #mergeCachedColumns}）；
     * 该数据源内的缓存条目本身也带库名维度，不会把 A 库那张表的列喂给 B 库。
     */
    public static Map<String, ColumnMeta> columnsCached(Project project, java.util.Collection<String> tableNames) {
        if (project == null || tableNames.isEmpty()) return Map.of();
        if (!isDatabaseAvailable()) return Map.of();
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) return Map.of();
        synchronized (metaCache) {
            // P7：按数据源顺序给出「表名 → 该源缓存里的列」查询器，由 mergeCachedColumns 取首个持有者
            List<Function<String, Map<String, ColumnMeta>>> sources = new ArrayList<>();
            for (Object ds : dataSources) {
                String db = databaseNameOf(ds);
                sources.add(name -> {
                    Map<String, TableMeta> perDs = metaCache.get(ds);
                    if (perDs == null) return null;
                    TableMeta meta = perDs.get(cacheKeyOf(db, name));
                    return (meta != null) ? meta.columns() : null;
                });
            }
            return mergeCachedColumns(tableNames, sources);
        }
    }

    /**
     * P7：补全候选的多表列并集（纯逻辑，单测直测）。
     *
     * <p>{@code sources} 按数据源顺序排列，每项是「表名 → 该数据源缓存里的列」（该源未持有返回 null）。
     * <b>每个表名只认首个持有它的数据源</b>——旧实现把全部数据源的同名表列 putIfAbsent 并成一个 Map，
     * 跨库同名表（业务表 + `mysql.user` 之流）的列互相污染。不同表名之间仍按列名去重
     * （同名跨表列取首次命中，一期口径不变）。
     */
    public static Map<String, ColumnMeta> mergeCachedColumns(
            Collection<String> tableNames,
            List<Function<String, Map<String, ColumnMeta>>> sources) {
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        for (String name : tableNames) {
            if (name.isBlank()) continue;
            for (Function<String, Map<String, ColumnMeta>> lookup : sources) {
                Map<String, ColumnMeta> columns = lookup.apply(name);
                if (columns == null) continue;
                for (Map.Entry<String, ColumnMeta> e : columns.entrySet()) {
                    out.putIfAbsent(e.getKey(), e.getValue());
                }
                break;
            }
        }
        return out;
    }

    /**
     * T-DialectInferPollution W10（2026-10-24）：<b>表归属路由</b>——返回「缓存里持有<b>全部</b>指定
     * 表」的数据源下标清单（与 {@link #columnNameSources} 的源顺序一致；只读缓存，零内省零连接）。
     *
     * <p>动机：EXPLAIN 取源回落（模块学习/last-used）可能命中与预览表不符的源（跨模块错配，
     * 用户实测 {@code mp_demo.wms_inventory_jar_demo doesn't exist}）；而「表在哪个库」是比模块绑定
     * 更强的归属事实，且结构缓存在 Database 视图展开/预览链预热时已就位。命中唯一 → 直接路由；
     * 零命中（缓存未热/表不存在）与多命中 → 调用方保持现行为/引导选择，不猜。</p>
     */
    public static List<Integer> cachedOwnerIndexes(@Nullable Project project, java.util.Collection<String> tableNames) {
        if (project == null || tableNames.isEmpty()) return List.of();
        if (!isDatabaseAvailable()) return List.of();
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) return List.of();
        synchronized (metaCache) {
            return ownerIndexesForSources(columnNameSources(dataSources), tableNames);
        }
    }

    /** 纯判定核心（headless 直测）：源查询器清单 + 表集 → 持有全部表的源下标。 */
    public static List<Integer> ownerIndexesForSources(
            List<Function<String, Set<String>>> sources,
            java.util.Collection<String> tableNames) {
        List<String> names = new ArrayList<>();
        for (String name : tableNames) {
            if (!name.isBlank()) names.add(name);
        }
        if (names.isEmpty() || sources.isEmpty()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Function<String, Set<String>> source = sources.get(i);
            boolean holdsAll = true;
            for (String name : names) {
                Set<String> columns = source.apply(name);
                if (columns == null || columns.isEmpty()) {
                    holdsAll = false;
                    break;
                }
            }
            if (holdsAll) out.add(i);
        }
        return out;
    }

    /**
     * T-DialectInferPollution W12：结构缓存是否有<b>任何</b>内容（区分「缓存冷 = 无法证明」与
     * 「缓存热但表不在 = 确证不在」）——前者调用方保持候选（诚实降级），后者调用方引导选择。
     * 只读 metaCache，零内省零连接。
     */
    public static boolean cacheHasAnyTable(@Nullable Project project) {
        if (project == null || !isDatabaseAvailable()) return false;
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) return false;
        synchronized (metaCache) {
            for (Object ds : dataSources) {
                Map<String, TableMeta> perDs = metaCache.get(ds);
                if (perDs != null && !perDs.isEmpty()) return true;
            }
            return false;
        }
    }

    /**
     * UI023：只读精确对象与库的既有缓存；不遍历、不跨源回退、不取未知库条目。
     */
    public static Map<String, Set<String>> columnNamesCachedForTarget(Object dataSource, String database,
                                                                     Collection<String> tableNames) {
        if (database == null || database.isBlank()) {
            return Map.of();
        }
        synchronized (metaCache) {
            // 用身份而非 equals 匹配：目标对象更换后宁可冷缓存，不借旧连接的结构。
            Map<String, TableMeta> cached = null;
            for (Map.Entry<Object, Map<String, TableMeta>> entry : metaCache.entrySet()) {
                if (entry.getKey() == dataSource) {
                    cached = entry.getValue();
                    break;
                }
            }
            if (cached == null) {
                return Map.of();
            }
            Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
            for (String name : tableNames) {
                TableMeta meta = cached.get(cacheKeyOf(database, name));
                if (meta != null && meta.allColumnNames() != null && !meta.allColumnNames().isEmpty()) {
                    out.put(name, meta.allColumnNames());
                }
            }
            return out;
        }
    }

    /**
     * O-58：多表的<b>全列名</b>集合（<b>只读缓存，不触发遍历</b>）。
     *
     * <p>与 {@link #columnsCached} 同源同口径——同一 {@code metaCache}、同 P7 库名维度、同「首个持有者」
     * 语义，差别只在返回维度：{@link #columnsCached} 给「列名 → 列元数据」（因此天然只含带元数据的子集，
     * 见 {@link TableMeta#allColumnNames} 的说明），这里给「表名 → 该表全部列名」。单开入口而不是复用
     * {@link #columnsCached}，既让两处语义各自明确，也让 {@link #columnsCached} 的既有消费方（补全候选）
     * 零改动。未预热 / 数据源未连 / 社区版一律返回空 Map，调用方回落。
     */
    public static Map<String, Set<String>> columnNamesCached(Project project, Collection<String> tableNames) {
        if (project == null || tableNames.isEmpty()) return Map.of();
        if (!isDatabaseAvailable()) return Map.of();
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) return Map.of();
        synchronized (metaCache) {
            return mergeCachedColumnNames(tableNames, columnNameSources(dataSources));
        }
    }

    /**
     * P7：按数据源顺序给出「表名 → 该源缓存里的全列名」查询器（该源未持有该表返回 null）。
     * 与 {@link #columnsCached} 内部的查询器同构；抽出来是为了让 {@link #mergeCachedColumnNames}
     * 的合并语义能在无 database 插件的单测环境里直测（同 {@link #mergeCachedColumns} 的既有做法）。
     */
    public static List<Function<String, Set<String>>> columnNameSources(List<Object> dataSources) {
        List<Function<String, Set<String>>> out = new ArrayList<>();
        for (Object ds : dataSources) {
            String db = databaseNameOf(ds);
            out.add(name -> {
                Map<String, TableMeta> perDs = metaCache.get(ds);
                if (perDs == null) return null;
                TableMeta meta = perDs.get(cacheKeyOf(db, name));
                return (meta != null) ? meta.allColumnNames() : null;
            });
        }
        return out;
    }

    /**
     * T-ExplainConsoleFirst W7（2026-10-24）：{@code Unknown column} 跨源升格的证据入口。
     *
     * <p>与 {@link #cachedOwnerIndexes} 同口径的只读枚举（零内省零连接），返回<b>在籍数据源清单</b>供调用方
     * 按身份比对「当前控制台用的那条源」并排除它。Database 插件不在场或枚举失败一律 null
     * （调用方据此不升格——诚实降级，不猜）。</p>
     */
    @Nullable
    public static List<Object> allDataSourcesForEvidence(@Nullable Project project) {
        if (project == null) return null;
        if (!isDatabaseAvailable()) return null;
        List<Object> dataSources = dataSources(project);
        return (dataSources != null && !dataSources.isEmpty()) ? dataSources : null;
    }

    /**
     * T-ExplainConsoleFirst W7：加锁版的 {@link #columnNameSources}——升格判定要跨多条源连续读同一份
     * {@code metaCache}，逐条各自加锁会让「读源 A 与读源 B 之间缓存被清」的窗口混入不一致视图。
     */
    public static List<Function<String, Set<String>>> columnNameSourcesCached(List<Object> dataSources) {
        synchronized (metaCache) {
            return columnNameSources(dataSources);
        }
    }

    /**
     * P7：「首个持有者」合并（口径与 {@link #mergeCachedColumns} 逐条一致）——每个表名只认首个持有它的
     * 数据源，避免跨库同名表互相污染；不同表名之间各取各的。
     */
    public static Map<String, Set<String>> mergeCachedColumnNames(
            Collection<String> tableNames,
            List<Function<String, Set<String>>> sources) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String name : tableNames) {
            if (name.isBlank()) continue;
            for (Function<String, Set<String>> lookup : sources) {
                Set<String> names = lookup.apply(name);
                if (names == null) continue;
                out.put(name, names);
                break;
            }
        }
        return out;
    }

    /**
     * T-DbSchemaCompletion：预热若干表的 schema 缓存（供 {@link #columnsCached} 零遍历读取）。
     *
     * <p><b>调用方负责线程</b>（BGT 执行；内部 {@link #tableMeta} 自带 ReadAction）；Community、无数据源、
     * 表名为空时立即返回 0。返回真正解析到内容的表数——调用方据此判定是否有必要刷新补全上下文。
     *
     * <p>数据源晚连接（`getModel()` 为 null）或内省未完成（模型非 null 但遍历 0 张表）时本方法解析 0 张
     * 且<b>不留负缓存</b>（见 {@link #resolveFromSources} 的 bail 语义与 {@link #bailAfterTraversal}），
     * 控制台就绪后重新触发即可命中。
     */
    public static int prefetchTables(Project project, Collection<String> tableNames) {
        if (project == null) return 0;
        if (!isDatabaseAvailable()) return 0;
        int resolved = 0;
        for (String name : tableNames) {
            if (name.isBlank()) continue;
            TableMeta meta;
            try {
                meta = tableMeta(project, name);
            } catch (Throwable e) {
                continue;
            }
            if (!meta.columns().isEmpty() || meta.comment() != null) resolved++;
        }
        return resolved;
    }

    /**
     * 列名 → 列元数据（注释 + DB 真实类型串，T-Embed-02）。
     *
     * <p>类型串 = `DasTypedObject.getDataType().getSpecification()`（如 `int(11)` / `varchar(64)`，
     * javap 实证 DataType.getSpecification 即 Database「显示图」右对齐类型的渲染来源）。
     * 表名匹配不到返回空 Map，调用方回落实体推断值。
     */
    public static Map<String, ColumnMeta> columnMeta(Project project, String tableName) {
        return tableMeta(project, tableName).columns();
    }

    /**
     * T-Perf-09：TableMeta 缓存——键 = (dataSource 对象弱引用, {@link #cacheKeyOf} 产出的库名+表名键)。
     * 数据源全量遍历（DasModel.traverser().toList()）代价随 schema 规模线性，JOIN 预览
     * 每打开一次、每个 JOIN 表各触发一次；命中后零遍历。数据源删除后条目随弱键回收；
     * 表匹配不到（通常=未连接/introspection 未完成）走 {@link #negativeCache} 负命中缓存
     * （T-MetaCheapSource：TTL + DDL 失效双保险，见下）。
     *
     * <p>P7（缓存维度）：内层键含库名——<b>命中发生在匹配逻辑之前</b>（`resolveFromSources` 先查缓存再
     * 决定扫不扫），只改匹配对已缓存条目完全无效；库名进键后，同名表在不同库/不同连接 URL 下的
     * 结论各自成条目，不会互相喂（库名取不到时键退回纯表名，与修复前逐字一致）。
     *
     * <p>T-DdlCacheInvalidate：DDL/事务提交语句执行提交前经 execute/MetadataInvalidator 全量失效
     * （本缓存与负命中缓存同为此前盘点出的自持 schema 面——ALTER TABLE 后补全/JOIN 富化拿旧结构
     * 是正确性破口，失效走 O(1) 引用清空）。
     */
    private static final Map<Object, Map<String, TableMeta>> metaCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** T-DdlCacheInvalidate：正缓存全量失效（引用置空语义，下次取数零遍历回填；库名记忆一并清）。 */
    public static void invalidateAll() {
        synchronized (metaCache) {
            metaCache.clear();
        }
        synchronized (dsDatabaseCache) {
            dsDatabaseCache.clear();
        }
    }

    // ----------
    // P7：数据源库名维度（连接 URL 里的库名）。
    // 一个数据源可以同时看见多个库/模式（MySQL 的 `mpj_demo` 与 `mysql`、PG 的多 schema），
    // 同名表的解析结论按库不同——故缓存键与匹配条件都要带库名。口径复用 execute/JdbcUrlDatabase
    // （T-ConsoleDbContext 同源）与 McpSchemaContextService 的 P4 实现：`getConnectionConfig()
    // .getUrl()` → nameFromUrl；取不到（URL 无库段 / Oracle SID 形态 / 无 database 插件）→ null，
    // 调用方**退回按名匹配**，不引入新失败模式。
    // ----------

    /** 数据源 → 连接 URL 库名（null 也缓存，用哨兵区分「未解析」与「解析为 null」）。 */
    private static final Map<Object, Object> dsDatabaseCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Object NO_DATABASE = new Object();

    /** 测试缝：数据源库名替身（默认 null = 走 DBN 反射链；单测环境无 database 插件）。 */
    public static Function<Object, String> dataSourceDatabaseForTest = null;

    /** 数据源连接 URL 里的库名（取不到 → null，调用方退回按名匹配）。 */
    public static String databaseNameOf(Object ds) {
        if (dataSourceDatabaseForTest != null) {
            return dataSourceDatabaseForTest.apply(ds);
        }
        Object cached;
        synchronized (dsDatabaseCache) {
            cached = dsDatabaseCache.get(ds);
        }
        if (cached != null) {
            return (cached == NO_DATABASE) ? null : (String) cached;
        }
        String resolved = null;
        try {
            Object config = Class.forName(DAS_DATA_SOURCE).getMethod("getConnectionConfig").invoke(ds);
            Object url = (config != null)
                    ? Class.forName(RAW_CONNECTION_CONFIG).getMethod("getUrl").invoke(config)
                    : null;
            resolved = JdbcUrlDatabase.nameFromUrl((url instanceof String) ? (String) url : null);
        } catch (Throwable ignored) {
            // runCatching{...}.getOrNull() 等价：解析失败 → null（调用方退回按名匹配）
        }
        synchronized (dsDatabaseCache) {
            dsDatabaseCache.put(ds, resolved != null ? resolved : NO_DATABASE);
        }
        return resolved;
    }

    /**
     * 缓存键（正/负缓存同口径）：库名 + 表名小写；库名取不到时<b>退回纯表名</b>（与修复前逐字一致）。
     *
     * <p>分隔符用 NUL——标识符里不可能出现，杜绝「库名或表名自带分隔符」造成的键碰撞。
     */
    private static String cacheKeyOf(String database, String tableName) {
        String table = tableName.toLowerCase(Locale.ROOT);
        String db = (database != null) ? database.trim().toLowerCase(Locale.ROOT) : "";
        return db.isEmpty() ? table : db + "\u0000" + table;
    }

    // ----------
    // T-MetaCheapSource（O-21 T-MC-01）：负命中缓存。
    // agent 场景同一幻觉表名会在一次会话内反复尝试，此前每次未命中都全量遍历 DasModel
    // （多数据源 × 大模型时一次调用可到秒级）。键 = (dataSource 弱引用, cacheKeyOf 键：库名+表名)，
    // 值 = 记入时间戳；
    // TTL 30s 用时间戳惰性判定（不用定时器）防长会话陈旧——建表后 30s 内的误报由 MetadataInvalidator
    // 失效兜底（登记 label 见 execute/MetadataInvalidator init 块，DDL/事务后与正缓存一起清）。
    // 弱键同 metaCache 口径：数据源删除后条目随弱键回收。
    //
    // T-MC-02（表名索引：一次遍历建 name → DasTable 索引，把「逐名全扫」变「一次扫描多次查询」）
    // 为可选件，按计划 P3 自律**默认不启用**——真机数据显示 T-MC-01 后仍慢才引入，避免过度工程。
    // ----------

    /** 负缓存 TTL（毫秒）：时间戳惰性过期，过期即重扫，无常驻定时器。 */
    public static final long NEGATIVE_TTL_MS = 30_000L;

    /** 测试专用 TTL 旋钮（生产恒等于 {@link #NEGATIVE_TTL_MS}；调短/归零验证过期语义）。 */
    public static volatile long negativeTtlMs = NEGATIVE_TTL_MS;

    private static final Map<Object, Map<String, Long>> negativeCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 负缓存清空（MetadataInvalidator 登记入口；引用置空语义，幂等无害）。 */
    public static void clearNegativeCache() {
        synchronized (negativeCache) {
            negativeCache.clear();
        }
    }

    /**
     * T-PS5-13：负缓存容量上界（照 {@code Ognl.kt} CACHE_MAX / StackLocator 惯例）——超上限<b>整体清空</b>。
     * 键含表名，长会话里 agent 幻觉表名可以无界累积（TTL 只做惰性过期、不做容量约束）；
     * 清空代价 = 若干次重扫（fail safe 方向，与负缓存语义一致）。TTL 惰性过期语义不变。
     */
    public static final int NEGATIVE_CACHE_MAX = 512;

    /** T-PS5-13：负缓存当前条目数（跨数据源求和；测试缝与容量判定共用）。 */
    public static int negativeCacheSize() {
        synchronized (negativeCache) {
            int sum = 0;
            for (Map<String, Long> perDs : negativeCache.values()) {
                sum += perDs.size();
            }
            return sum;
        }
    }

    // ----------
    // T-PerfSweep6 T-PS6-08（性能报告发现 11）：**本轮未实施，留档**。
    //
    // 计划要求「给 bailAfterTraversal 的『查不了』加短 TTL 标记（而非完全不缓存）」，理由是
    // 该环境下每次调用都重跑一遍 DasModel 全遍历。**按字面实施会与 P7b 的既有护栏直接冲突**：
    // 「查不了不写负缓存 → 下次调用仍重扫」是 P7b 刻意的语义，被 4 处既有断言钉住——
    //   - `DatabaseSchemaResolverBailTest.kt:55`（scans 期望 2）
    //   - `DatabaseSchemaResolverNegativeCacheTest.kt:209`（`查不了不记账_下次调用仍重试`，scans 期望 3）
    //   - `DatabaseSchemaResolverSchemaScopeTest.kt:105/118`（calls 期望 2，两处）
    // 任何「跨调用去重窗口」（哪怕 200ms）都会让这些读数变成 1，即把护栏改软；而它们的判别力
    // 正是「数据源晚连接/内省完成后自愈」这条正确性语义。
    //
    // 取舍：**低**级性能收益 < 正确性护栏强度，故本轮不实施（不削弱既有断言）。若日后要做，
    // 须与那 4 处断言的作者一起把契约改写成「窗口内不重扫 + 过期后重扫」两条（TTL 必须
    // < DDL 生效探测首拍 500ms，见 bailAfterTraversal 的 KDoc）。
    // ----------

    // ----------
    // T-PS5-06：单调用全扫限额（MCP schema_context 一次调用最多 20 表）。
    // 此前每个 miss 表都对每个数据源重跑一遍 DasModel 全遍历；而 {@link #collectFromDataSource} 的
    // 遍历是**完整**的（命中后不 break，仍走完所有元素；只有「查不了」才提前 bail）——因此同一
    // 数据源在**同一次调用内**的第二张 miss 表，其遍历结论与第一张完全相同，重扫纯属白付
    // （多数据源 × 20 表时一次调用最多 k 次全扫，且全程在 ReadAction 内挡写动作）。
    // 账本按「数据源身份」记账（IdentityHashMap 语义）：同一数据源在一次调用内全扫至多一次；
    // 已全扫过的数据源上的后续 miss 表直接按未找到处理，负缓存照记（结论与全扫等价，跨调用
    // 负缓存语义不变）。**查不了（scanBailed）不记账**——模型未加载/反射链失败时下次必须重试。
    // 作用域仅由 {@link #withScanBudget} 显式开启；**当前无生产调用方**（原唯一入口
    // McpSchemaContextService 的 schema_context 已改自建索引，见其 P4 注释）。
    // T-PerfSweep6 T-PS6-08（性能报告发现 12）：定性由「死代码」订正为「**仅测试消费**」——
    // 实测消费方 = `src/test/kotlin/.../ui/highlight/DatabaseSchemaResolverNegativeCacheTest.kt`
    // 六处（含专供测试的 {@link #inScanBudgetForTest}），故**不删**：删掉要连带改那个不在本任务写集的
    // 测试文件，且账本语义本身是 P7b「查不了 vs 确认没有」的回归护栏。
    // 新接生产调用方前先确认是否真需要（跨调用面已有负缓存 + McpSchemaContextService 自建索引）。
    // ----------

    /** 调用内账本（已完整全扫过的数据源身份集）。 */
    public static final class ScanBudget {
        public final Set<Object> scannedSources =
                Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static final ThreadLocal<ScanBudget> scanBudget = new ThreadLocal<>();

    /**
     * T-PS5-06：在 {@code block} 内启用单调用全扫限额（MCP {@code schema_context} 一次调用包一层）。
     * 嵌套调用保留外层账本（内层不重置）；退出恢复原作用域。
     *
     * <p><b>当前无生产调用方，仅测试使用</b>——原唯一入口（MCP {@code schema_context}）已改为自建调用内索引
     * （McpSchemaContextService P4），本方法与其账本语义保留作回归护栏，新接调用方前先确认是否真需要。
     */
    public static <T> T withScanBudget(java.util.function.Supplier<T> block) {
        ScanBudget prev = scanBudget.get();
        if (prev == null) scanBudget.set(new ScanBudget());
        try {
            return block.get();
        } finally {
            scanBudget.set(prev);
        }
    }

    /** T-PS5-06 测试缝：当前是否处于限额作用域内。 */
    public static boolean inScanBudgetForTest() {
        return scanBudget.get() != null;
    }

    /** 负命中是否在 TTL 内（过期条目惰性移除，不留定时器）。 */
    private static boolean isNegativeCached(Object ds, String cacheKey) {
        Long stamped;
        synchronized (negativeCache) {
            Map<String, Long> perDs = negativeCache.get(ds);
            stamped = (perDs != null) ? perDs.get(cacheKey) : null;
        }
        if (stamped == null) return false;
        // 严格小于：TTL=0 语义=立即过期（同一毫秒内也不新鲜）——负缓存 TTL 测试的确定性
        // 依赖此边界（<= 形态下同毫秒调用判新鲜，分片负载下曾致 flake expected 2 was 1）
        if (System.currentTimeMillis() - stamped < negativeTtlMs) return true;
        synchronized (negativeCache) {
            Map<String, Long> perDs = negativeCache.get(ds);
            if (perDs != null) perDs.remove(cacheKey);
        }
        return false;
    }

    /** T-PS5-06：记负缓存（时间戳，TTL 内同表同数据源查询不再全扫）。 */
    private static void stampNegative(Object ds, String cacheKey) {
        synchronized (negativeCache) {
            // T-PS5-13：容量上界——溢出整体清空（本键随后照常写入；清空只丢命中率不丢正确性）
            if (negativeCacheSize() >= NEGATIVE_CACHE_MAX) negativeCache.clear();
            negativeCache.computeIfAbsent(ds, k -> new HashMap<>()).put(cacheKey, System.currentTimeMillis());
        }
    }

    /**
     * 表级元数据（表注释 + 列元数据，T-Embed-09 一次遍历同时取）。
     *
     * @param comment 数据源表 COMMENT（`DasTable.getComment()`，null/空白 = 数据库没写，调用方回落实体注释）
     */
    public static TableMeta tableMeta(Project project, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return TableMeta.EMPTY;
        }
        if (!isDatabaseAvailable()) {
            return TableMeta.EMPTY;
        }
        return ReadAction.compute(() -> tryCompute(project, tableName));
    }

    /**
     * T-Dgm-StmtScope：<b>语句级</b>批量元数据（JOIN 图整图富化用）——同一句的表尽量取自<b>同一个库</b>。
     *
     * <p>逐表的 {@link #tableMeta} 在多数据源下是「注册序首个命中」，跨库同名表会被撞上：真机 A10
     * （2026-10-11 沙盒实测）{@code "user"} + user_address + area 同句，本地 {@code jpa_demo} 里也有张
     * {@code user}（9 列、<b>零 COMMENT</b>，注册序在 {@code mpj_demo} 之前）→ 主表落到那张表上：列注释空白、
     * 类型串变成那张表的、库里独有列还被当「实体未映射列」追加进节点；而同一句的 JOIN 表只有
     * {@code mpj_demo} 有、解析正确 → 图上「主表无注释、副表有注释」。沙盒日志三行可复现该判据：
     * {@code scan: table=user, ds=jpa_demo@localhost, hit=true, foundColumns=9} /
     * {@code warn … native Database diagram (B path) not taken} / {@code B path locateDbTables: wanted=["user", …]}。</p>
     *
     * <p>消歧规则（{@link #resolveBatchFromSources}）：先逐表按名解析（成本、正/负缓存、库名限定语义与
     * {@link #tableMeta} 逐字一致），再以「各来源持有本批表的数量」投票选主来源，少数来源持有的表若主来源
     * 也有则改判给主来源。主来源没有的表<b>保留原来源</b>——跨库联接仍逐表回落，不引入新失败模式。</p>
     *
     * @param tableNames 表名（可含引用符/大小写原样；键按<b>传入原样</b>返回，调用方按同名回查）
     * @return 表名 → 元数据（含解析不到的条目 = 空 TableMeta，调用方据此保留实体推断值）
     */
    public static Map<String, TableMeta> tableMetas(Project project, Collection<String> tableNames) {
        Set<String> rawSet = new LinkedHashSet<>();
        for (String name : tableNames) {
            if (name != null && !name.isBlank()) rawSet.add(name);
        }
        if (project == null || rawSet.isEmpty()) return Map.of();
        if (!isDatabaseAvailable()) return Map.of();
        List<Object> sources = dataSources(project);
        if (sources == null) return Map.of();
        if (sources.isEmpty()) return Map.of();
        List<String> raws = new ArrayList<>(rawSet);
        return ReadAction.compute(() -> resolveBatchFromSources(raws, sources, null));
    }

    /**
     * 数据源侧元数据（表注释为 null 时该维度缺失，调用方逐维度回落）。
     *
     * @param comment    数据源表 COMMENT（`DasTable.getComment()`，null/空白 = 数据库没写，调用方回落实体注释）
     * @param columns    列名 → 列元数据
     * @param scanBailed T-DbSchemaCompletion：本次<b>没能查</b>（数据源模型未加载 / ObjectKind 取不到 /
     *   反射链异常），区别于「查了、确认没有此表」。调用方据此决定是否记负缓存——把"查不了"当
     *   "不存在"会让数据源晚连接时 30s 内的补全与富化全被负缓存挡掉（T-MetaCheapSource 语义缺口）
     * @param allColumnNames O-58：该表的<b>全部</b>列名——含 {@code columns} 因「无注释 ∧ 无类型串 ∧
     *   notNull 不为 true」而未收录元数据的列。列名集合是独立维度：列名校正要在它上面做<b>唯一性</b>判定，
     *   而唯一性判定在不完整子集上会失真（同形两列里少了一列 → 歧义消失 → 选错列）。默认空集，
     *   既有构造点（测试替身 / bail 早退）语义逐字不变。
     */
    public record TableMeta(String comment, Map<String, ColumnMeta> columns, boolean scanBailed,
                            Set<String> allColumnNames) {
        public static final TableMeta EMPTY = new TableMeta(null, Map.of(), false);

        /** 旧形态：scanBailed 给出，allColumnNames 缺省空集。 */
        public TableMeta(String comment, Map<String, ColumnMeta> columns, boolean scanBailed) {
            this(comment, columns, scanBailed, Set.of());
        }

        /** 旧形态：scanBailed 缺省 false。 */
        public TableMeta(String comment, Map<String, ColumnMeta> columns) {
            this(comment, columns, false);
        }
    }

    /**
     * 数据源侧列元数据（均为 null 时表示该维度缺失，调用方逐维度回落）。
     *
     * @param comment  DB COMMENT（null/空白 = 数据库没写）
     * @param typeSpec DB 真实类型串（`getDataType().getSpecification()`；null = 无类型信息）
     * @param notNull  T-TestAssertReal：非空标记（`DasTypedObject.isNotNull()`；null = 反射失败/取不到）
     * @param autoGenerated O-59 T-IIR-02：该列是否数据库自增/生成
     *   （`DasColumn.Attribute.AUTO_GENERATED`）。null = 未取到（非 false——「取不到」与「明确不是」
     *   在回滚侧 identity 包装里同形降级，但保留 null 便于诊断区分）。
     */
    public record ColumnMeta(String comment, String typeSpec, Boolean notNull, Boolean autoGenerated) {
        /** 旧形态：notNull 给出，autoGenerated 缺省 null。 */
        public ColumnMeta(String comment, String typeSpec, Boolean notNull) {
            this(comment, typeSpec, notNull, null);
        }

        /** 旧形态：notNull 缺省 null。 */
        public ColumnMeta(String comment, String typeSpec) {
            this(comment, typeSpec, null);
        }
    }

    // T-TestAssertReal：notNull 取自 DasTypedObject.isNotNull()（javap 实证，DasColumn extends
    // DasTypedObject）。收录条件放宽为「comment/typeSpec 非空 或 notNull==true」后，仅非空标记
    // 列（comment/typeSpec 双空）也会进 Map——只消费 comment 的调用点自行过滤 blank 保持放宽前语义。
    private static TableMeta tryCompute(Project project, String tableName) {
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) {
            LOG.warn("DatabaseSchemaResolver: dataSources(project) reflection failed, table=" + tableName);
            return TableMeta.EMPTY;
        }
        if (dataSources.isEmpty()) {
            LOG.info("DatabaseSchemaResolver: no data sources configured, table=" + tableName);
            return TableMeta.EMPTY;
        }
        return resolveFromSources(tableName, dataSources);
    }

    /**
     * 剥掉标识符的<b>成对</b>引用符：{@code [user]} / {@code "user"} / {@code `user`} → {@code user}；
     * 无包裹符原样返回。只剥首尾成对的一层，不动表名内部出现的引号/方括号（那些在真实对象名里是合法字符）。
     */
    private static String unquoteIdentifier(String raw) {
        String t = raw.trim();
        if (t.length() < 2) return t;
        char head = t.charAt(0);
        char tail = t.charAt(t.length() - 1);
        boolean paired = (head == '[' && tail == ']') || (head == '"' && tail == '"') || (head == '`' && tail == '`');
        return paired ? t.substring(1, t.length() - 1) : t;
    }

    /**
     * 按数据源逐个解析：正缓存命中 → 负缓存 TTL 内跳过 → 全扫（扫描探针可注入供表驱动测试，
     * 无 database 插件的单测环境走不了真实反射链；生产 scanProbe 传 null 走既有反射路径，
     * 正命中回填与返回行为与 T-Perf-09 原实现一致）。
     *
     * <p>T-DbSchemaCompletion：查不了（{@code scanBailed}）与"确认没有"分道——前者不写负缓存、不短路
     * 后续数据源、下次调用重试；聚合结果仅在<b>所有</b>数据源都查不了时才标 {@code scanBailed}。
     *
     * @param scanProbe 扫描探针（null = 走既有反射路径）
     */
    public static TableMeta resolveFromSources(String tableName, List<Object> sources,
                                               BiFunction<Object, String, TableMeta> scanProbe) {
        // 真机走查（2026-09-23）实测：SQL 文本里的表名可能<b>带引用符</b>（SQL Server `[user]`、PG `"user"`、
        // MySQL `` `user` ``），而 DAS 里存的是裸名——原样带进去必然解析不到（沙盒日志：
        // `scan: table=[user] … table=[user] 无法解析`）。后果不止补全：回滚链靠它取自增列，
        // 取不到 ⇒ 回填 INSERT 不带 `SET IDENTITY_INSERT` 包装 ⇒ 真机仍报 544。
        // 故入口先剥引用符（只剥「成对的包裹符」，表名内部的引号/方括号不受影响）。
        String name = unquoteIdentifier(tableName);
        BiFunction<Object, String, TableMeta> scan = (scanProbe != null) ? scanProbe : DatabaseSchemaResolver::collectFromDataSource;
        ScanBudget budget = scanBudget.get();
        int bailed = 0;
        for (Object ds : sources) {
            // P7：缓存键带库名维度（同一数据源可同时看见多个库/模式，同名表结论按库不同；
            // 库名取不到时键退回纯表名，与修复前逐字一致）
            String cacheKey = cacheKeyOf(databaseNameOf(ds), name);
            // T-Perf-09：缓存命中直接返回（同一数据源同一表零遍历）
            TableMeta cached;
            synchronized (metaCache) {
                Map<String, TableMeta> perDs = metaCache.get(ds);
                cached = (perDs != null) ? perDs.get(cacheKey) : null;
            }
            if (cached != null) return cached;
            // T-MetaCheapSource：TTL 内负命中跳过该数据源全扫（introspection 完成后由 DDL
            // 失效/TTL 过期两条路放开，见 negativeCache 注释）
            if (isNegativeCached(ds, cacheKey)) continue;
            // T-PS5-06：本次调用内该数据源已完整全扫过 → 不再重扫（结论等价；负缓存照记，
            // 跨调用语义不变）
            if (budget != null && budget.scannedSources.contains(ds)) {
                stampNegative(ds, cacheKey);
                continue;
            }
            TableMeta found = scan.apply(ds, name);
            if (!found.columns().isEmpty() || found.comment() != null) {
                synchronized (metaCache) {
                    metaCache.computeIfAbsent(ds, k -> new HashMap<>()).put(cacheKey, found);
                }
                // 命中即证明遍历完整（collectFromDataSource 命中后不 break）→ 记账
                if (budget != null) budget.scannedSources.add(ds);
                return found;
            }
            // T-DbSchemaCompletion：查不了的（模型未加载 / ObjectKind 取不到）**不写负缓存**——
            // 否则数据源晚连接时会把 30s 内的补全、JOIN 富化全挡掉，且负缓存"过期前不再重试"
            // 恰好卡住刚连上的那一分钟。继续看下一个数据源，都不行就留给下次调用重试。
            if (found.scanBailed()) {
                bailed++;
                continue;
            }
            // T-PS5-06：完整全扫且未命中 → 记账（本次调用内同数据源后续表不再重扫）
            if (budget != null) budget.scannedSources.add(ds);
            // 该数据源确认没有此表：记负缓存（时间戳，TTL 内同表查询不再全扫）
            stampNegative(ds, cacheKey);
        }
        // 表在所有数据源都没拿到（通常=未连接/introspection 未完成/表不在当前库）
        if (bailed > 0) {
            LOG.info("DatabaseSchemaResolver: table=" + name + " 无法解析——" + bailed + "/" + sources.size()
                    + " 个数据源查不了（模型未加载/ObjectKind 取不到，原因见上方 scan 行），未记负缓存、下次调用重试");
        } else {
            LOG.info("DatabaseSchemaResolver: table=" + name + " not found in " + sources.size() + " data sources");
        }
        return new TableMeta(null, Map.of(), bailed > 0 && bailed == sources.size());
    }

    /** {@link #resolveFromSources} 便捷形态：scanProbe 缺省 null（生产反射路径）。 */
    public static TableMeta resolveFromSources(String tableName, List<Object> sources) {
        return resolveFromSources(tableName, sources, null);
    }

    /**
     * T-Dgm-StmtScope：批量解析（{@link #tableMetas} 的实现，纯逻辑便于单测直测）。
     *
     * <p>阶段 1 逐表按名解析：对每个表名按数据源注册序逐个 probe，首个产出（列非空 ∨ 表注释非空）
     * 的来源即该表的来源——与单表 {@link #resolveFromSources} 的「首个命中」逐字同口径（含 hits/miss 时
     * 的正负缓存写入），故<b>单表批量与旧行为完全一致</b>。</p>
     *
     * <p>阶段 2 句内一致性：以「持有本批表的数量」投票选出主来源（{@link #pickStatementSource}），
     * 少数来源持有的表若在主来源里也存在则改判给主来源（表集越全的来源才可能是本句真正在用的库）；
     * 主来源没有的表保留阶段 1 的来源（跨库联接逐表回落）。</p>
     *
     * @param probe 测试缝：默认 {@code (ds, name) -> resolveFromSources(name, listOf(ds))}；替身只换取数，
     *   投票/改判逻辑仍是生产代码本体
     * @return 以<b>传入原样表名</b>为键（含引用符），解析不到的键也有条目（空 TableMeta）
     */
    public static Map<String, TableMeta> resolveBatchFromSources(List<String> rawNames, List<Object> sources) {
        return resolveBatchFromSources(rawNames, sources, null);
    }

    public static Map<String, TableMeta> resolveBatchFromSources(
            List<String> rawNames, List<Object> sources, BiFunction<Object, String, TableMeta> probe) {
        BiFunction<Object, String, TableMeta> fetch =
                probe != null ? probe : (ds, name) -> resolveFromSources(name, List.of(ds));

        int[] owners = new int[rawNames.size()];
        java.util.Arrays.fill(owners, -1);
        TableMeta[] metas = new TableMeta[rawNames.size()];
        for (int i = 0; i < rawNames.size(); i++) {
            for (int si = 0; si < sources.size(); si++) {
                TableMeta meta = fetch.apply(sources.get(si), rawNames.get(i));
                if (!meta.columns().isEmpty() || meta.comment() != null) {
                    owners[i] = si;
                    metas[i] = meta;
                    break;
                }
            }
        }

        Integer primary = pickStatementSource(java.util.Arrays.stream(owners).boxed().toList());
        if (primary != null) {
            for (int i = 0; i < rawNames.size(); i++) {
                // 未解析到的表不必再试：阶段 1 已扫过全部来源（包含主来源）
                if (owners[i] == primary || owners[i] < 0) continue;
                TableMeta meta = fetch.apply(sources.get(primary), rawNames.get(i));
                if (!meta.columns().isEmpty() || meta.comment() != null) {
                    owners[i] = primary;
                    metas[i] = meta;
                }
            }
        }
        Map<String, TableMeta> out = new LinkedHashMap<>();
        for (int i = 0; i < rawNames.size(); i++) {
            out.put(rawNames.get(i), metas[i] != null ? metas[i] : new TableMeta(null, Map.of()));
        }
        return out;
    }

    /**
     * T-Dgm-StmtScope：句内主来源选择（纯逻辑，单测直测）——返回持有本批表<b>最多</b>的数据源下标
     * （owners 逐表给出来源下标，-1 = 未解析到）。
     *
     * <p>同票取<b>下标最小者</b>（数据源注册序在前者），与单表解析的「注册序首个命中」同口径：
     * 单表批量、或全部表都在同一来源时，结论与改动前逐字一致。无来源持有时返回 null。</p>
     */
    public static Integer pickStatementSource(List<Integer> owners) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Integer owner : owners) {
            if (owner == null || owner < 0) continue;
            counts.merge(owner, 1, Integer::sum);
        }
        Integer best = null;
        int bestCount = 0;
        // 迭代序 = 首次出现序 = 来源注册序，故严格大于才换人（同票留住先出现的）
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /** database 插件是否可用（Ultimate/DataGrip）。 */
    private static boolean isDatabaseAvailable() {
        try {
            Class.forName(MANAGER_CLASS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 获取所有数据源（反射 `DataSourceManager.getManagers(project)` → `getDataSources()`）。 */
    private static List<Object> dataSources(Project project) {
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Method managersMethod = managerClass.getMethod("getManagers", Project.class);
            Object managers = managersMethod.invoke(null, project);
            if (!(managers instanceof List<?>)) {
                return List.of();
            }
            List<?> mgrList = (List<?>) managers;
            List<Object> dsList = new ArrayList<>();
            for (Object m : mgrList) {
                if (m == null) {
                    continue;
                }
                Method getDataSources = managerClass.getMethod("getDataSources");
                Object list = getDataSources.invoke(m);
                if (!(list instanceof List<?>)) {
                    continue;
                }
                for (Object it : (List<?>) list) {
                    if (it != null) {
                        dsList.add(it);
                    }
                }
            }
            return dsList;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 取 DBN `ObjectKind` 的具名常量（T-DbSchemaCompletion 根因修复）。
     *
     * <p><b>别再写 {@code enumConstants}</b>：`com.intellij.database.model.ObjectKind` 是 DBN 手写的
     * type-safe enum（`Object` 子类 implements `Comparable` + public static final 常量 +
     * `ourKinds` 注册表 + `values()`），<b>不是 {@code java.lang.Enum}</b>——262.10968.63 字节码实证：
     * 类文件中 {@code java/lang/Enum} 出现 0 次、无 {@code $VALUES}/{@code valueOf}/{@code ordinal}。
     * {@code Class.enumConstants} 对它恒返回 null，历史上本处正是这么写的，于是每次调用都在该行静默早退、
     * 列遍历一次都没执行过，而早退无日志，上层只打印误导性的 {@code not found in N data sources}
     * （真机 23 次调用 scan 行 0 次，同名表其实存在于 information_schema）。同仓非反射调用
     * （{@code DbTableSpecAdapter} 的 {@code ObjectKind.COLUMN}）一直正常，取静态字段即可。
     *
     * <p>取不到时返回 null 由调用方按「查不了」处理（不写负缓存 + 留日志），不做 API 形态猜测。
     */
    public static Object resolveObjectKind(Class<?> kindClass, String name) {
        try {
            return kindClass.getField(name).get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** ObjectKind 可用常量名（排障用：取不到 COLUMN 时把实际字段列进日志，一眼看出 API 漂移）。 */
    private static String kindConstantNames(Class<?> kindClass) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : kindClass.getFields()) {
                if (sb.length() > 0) sb.append('/');
                sb.append(f.getName());
            }
            return sb.toString();
        } catch (Throwable e) {
            return "?";
        }
    }

    /** 数据源显示名（日志用；取不到返回 `?`）。 */
    private static String dsName(Object ds) {
        try {
            Object name = Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds);
            return (name instanceof String) ? (String) name : "?";
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * 一次 DasModel 遍历的产物：元素列表 + 表/列访问器（bail 非空 = 查不了，元素无意义）。
     *
     * <p>生产由 {@link #reflectTraversal} 反射产出；单测经 {@link #traversalForTest} 注入替身——无
     * database 插件时 {@code Class.forName} 全链不可用，而 P7 的匹配/合并/判定语义（祖先链限库、
     * 首个匹配锁定、遍历 0 张表判「查不了」）全都落在下面这段循环上，不给替身就一条都测不到。
     * 替身只换「取数」，匹配与合并逻辑仍是生产代码本体。
     */
    public static final class ModelTraversal {
        public final List<Object> elements;
        public final DasAccessors accessors;

        public ModelTraversal(List<Object> elements, DasAccessors accessors) {
            this.elements = elements;
            this.accessors = accessors;
        }
    }

    /**
     * 表/列读取访问器（生产实现包的是 `Method.invoke`，反射链口径见 {@link #reflectTraversal}）。
     *
     * @param parent 表/命名空间的父节点（P7 祖先链用；取不到返回 null → 不限定库）
     * @param autoGenerated O-59 T-IIR-02：{@code DasTable.getColumnAttrs(DasColumn)} 是否含
     *   {@code AUTO_GENERATED}。取<b>表 + 列</b>两个实参——javap 实证该方法声明在 <b>DasTable</b> 上
     *   （{@code public abstract java.util.Set<DasColumn$Attribute> getColumnAttrs(DasColumn)}）。
     */
    public static final class DasAccessors {
        public final Function<Object, Boolean> isTable;
        public final Function<Object, Boolean> isColumn;
        public final Function<Object, String> name;
        public final Function<Object, String> comment;
        public final Function<Object, Iterable<?>> children;
        public final Function<Object, Object> parent;
        public final Function<Object, String> typeSpec;
        public final Function<Object, Boolean> notNull;
        public final BiFunction<Object, Object, Boolean> autoGenerated;

        public DasAccessors(
                Function<Object, Boolean> isTable,
                Function<Object, Boolean> isColumn,
                Function<Object, String> name,
                Function<Object, String> comment,
                Function<Object, Iterable<?>> children,
                Function<Object, Object> parent,
                Function<Object, String> typeSpec,
                Function<Object, Boolean> notNull,
                BiFunction<Object, Object, Boolean> autoGenerated) {
            this.isTable = isTable;
            this.isColumn = isColumn;
            this.name = name;
            this.comment = comment;
            this.children = children;
            this.parent = parent;
            this.typeSpec = typeSpec;
            this.notNull = notNull;
            this.autoGenerated = autoGenerated;
        }

        /** Kotlin 默认参数等价重载：autoGenerated 缺省 {@code { _, _ -> null }}。 */
        public DasAccessors(
                Function<Object, Boolean> isTable,
                Function<Object, Boolean> isColumn,
                Function<Object, String> name,
                Function<Object, String> comment,
                Function<Object, Iterable<?>> children,
                Function<Object, Object> parent,
                Function<Object, String> typeSpec,
                Function<Object, Boolean> notNull) {
            this(isTable, isColumn, name, comment, children, parent, typeSpec, notNull,
                    (table, col) -> null);
        }

        /** Kotlin 默认参数等价重载：parent 缺省 {@code { null }}。 */
        public DasAccessors(
                Function<Object, Boolean> isTable,
                Function<Object, Boolean> isColumn,
                Function<Object, String> name,
                Function<Object, String> comment,
                Function<Object, Iterable<?>> children) {
            this(isTable, isColumn, name, comment, children, el -> null, col -> null, col -> null);
        }

        /** Kotlin 默认参数等价重载：parent 给出，typeSpec/notNull 缺省。 */
        public DasAccessors(
                Function<Object, Boolean> isTable,
                Function<Object, Boolean> isColumn,
                Function<Object, String> name,
                Function<Object, String> comment,
                Function<Object, Iterable<?>> children,
                Function<Object, Object> parent) {
            this(isTable, isColumn, name, comment, children, parent, col -> null, col -> null);
        }

        /** Kotlin 默认参数等价重载：notNull 缺省 {@code { null }}。 */
        public DasAccessors(
                Function<Object, Boolean> isTable,
                Function<Object, Boolean> isColumn,
                Function<Object, String> name,
                Function<Object, String> comment,
                Function<Object, Iterable<?>> children,
                Function<Object, Object> parent,
                Function<Object, String> typeSpec) {
            this(isTable, isColumn, name, comment, children, parent, typeSpec, col -> null);
        }
    }

    /** 遍历替身（测试缝；默认 null = 真实反射链）。 */
    public static Function<Object, ModelTraversal> traversalForTest = null;

    /** P7b：一张 DasTable 都没扫到（模型非 null 但内省未完成）时的 bail 原因。 */
    public static final String TRAVERSED_ZERO_TABLES = "DasModel 遍历 0 张表（内省未完成）";

    /**
     * P7b：遍历结束后的「查不了」判定（纯逻辑，单测直测）。
     *
     * <p>模型非 null 却一张表都没扫到 = <b>没查</b>（内省未完成 / 模型只建了库节点），不是「没有此表」——
     * 判成后者会记负缓存，把补全与 JOIN 富化钉 30s；更直接的危害是 DDL 生效探测
     * （SqlPreviewPanel.maybeProbeDdlEffective 的 500/1500/3000ms 三次退避重试）首次那一记负缓存
     * 会把后两次全挡掉，误报「DDL 未生效」。
     *
     * <p>已有 bail（模型未加载 / 遍历抛错 / ObjectKind 取不到）时不覆盖——那是更精确的原因。
     */
    public static String bailAfterTraversal(boolean hit, int scannedTables, String existingBail) {
        if (existingBail != null) return existingBail;
        return (!hit && scannedTables == 0) ? TRAVERSED_ZERO_TABLES : null;
    }

    /**
     * P7：自表向上的命名空间名链（库 → 模式 → …）。口径与 McpSchemaContextService 的 P4 同源
     * （那边自建索引时用的是同一判定）；链上取不到父节点即自然终止，超过 {@link #MAX_ANCESTOR_DEPTH} 层
     * 视为取不到（不做无界回溯）。
     */
    private static List<String> ancestorChain(Object element, DasAccessors acc) {
        List<String> out = new ArrayList<>();
        Object current = acc.parent.apply(element);
        int depth = 0;
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            String n = acc.name.apply(current);
            if (n != null && !n.isBlank()) out.add(n);
            current = acc.parent.apply(current);
            depth++;
        }
        return out;
    }

    /**
     * {@link #reflectTraversal} 的产物：{@code traversal} 为 null 时 {@code bail} 是<b>查不了</b>的原因
     * （Kotlin `Pair<ModelTraversal?, String?>` 的 Java 形态——record 表达同语义，避免引 kotlin.Pair）。
     */
    private record TraversalOutcome(ModelTraversal traversal, String bail) {
    }

    /**
     * 真实反射链遍历（T-Embed-09/10 实测口径）。{@code TraversalOutcome.bail} 非空 = <b>查不了</b>的原因
     * （模型未加载 / traverser 为 null / 遍历抛错 / ObjectKind 取不到）。
     *
     * <p>反射链自身异常（类缺失等）不在此吞——由 {@link #collectFromDataSource} 外层 try/catch 收敛为
     * 「反射链异常」bail，保持 T-DbSchemaCompletion 的日志口径。
     */
    private static TraversalOutcome reflectTraversal(Object ds) throws Throwable {
        Class<?> dasObjClass = Class.forName(DAS_OBJECT);
        Class<?> dasColumnClass = Class.forName(DAS_COLUMN);
        // T-Embed-09 真机修复：243 字节码实证 RawDataSource/DbDataSource 均无 getDasObject()，
        // getModel() 声明在父接口 DasDataSource（B 路径 locateDbTables 直调 ds.model 同源）——
        // 旧链 getDasObject().getModel() 反射 NoSuchMethodException 被吞 → 全量静默回落
        Object model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds);
        if (model == null) {
            // 数据源未连接/未内省（预览打开常早于建连）：不是"没有此表"，不能记负缓存
            return new TraversalOutcome(null, "数据源模型未加载(getModel=null)");
        }
        Object traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model);
        if (traverser == null) {
            return new TraversalOutcome(null, "DasModel.traverser=null");
        }
        // 16:09 沙盒日志实证：DasModel.traverser() 返回 JBTreeTraverser（extends
        // FilteredTraverserBase，非 JBIterable 子类）——对 JBIterable 取 toList invoke 抛
        // IllegalArgumentException: object is not an instance of declaring class。
        // FilteredTraverserBase implements java.lang.Iterable，iterator() 为其 public final——
        // 用 Iterable/Iterator 接口方法手动收集（= Kotlin 直调 toList 扩展同语义）
        List<Object> elements;
        try {
            // java.util.Iterator 为 JDK 接口，cast 后 hasNext/next 直调（避免再反射一层）
            Iterator<?> iterator = (Iterator<?>) Iterable.class.getMethod("iterator").invoke(traverser);
            List<Object> collected = new ArrayList<>();
            while (iterator.hasNext()) {
                collected.add(iterator.next());
            }
            elements = collected;
        } catch (Throwable e) {
            LOG.warn("DatabaseSchemaResolver: traverse elements failed", e);
            return new TraversalOutcome(null, "遍历 DasModel 失败: " + e.getClass().getSimpleName());
        }
        Class<?> objectKindClass = Class.forName(OBJECT_KIND);
        // T-DbSchemaCompletion：ObjectKind 非 java.lang.Enum，不能走 enumConstants（恒 null）
        Object columnKind = resolveObjectKind(objectKindClass, "COLUMN");
        if (columnKind == null) {
            return new TraversalOutcome(
                    null, "ObjectKind.COLUMN 取不到（实际常量：" + kindConstantNames(objectKindClass) + "）");
        }
        Class<?> dasTableClass = Class.forName(DAS_TABLE);
        Method getNameM = dasObjClass.getMethod("getName");
        Method getCommentM = dasObjClass.getMethod("getComment");
        Method getChildrenM = dasObjClass.getMethod("getDasChildren", objectKindClass);
        // P7：祖先链（库/模式）判定用 DasObject.getDasParent。取不到 → parent 恒 null → 不限定库
        // （退回按名匹配，不得引入新失败模式）
        Method getDasParentM = null;
        try {
            getDasParentM = dasObjClass.getMethod("getDasParent");
        } catch (Throwable ignored) {
            // runCatching{...}.getOrNull() 等价：取不到 → parent 访问器恒 null
        }
        // DasTypedObject.getDataType().getSpecification()（反射；列无类型信息时为 null）
        Class<?> dasTypedObjectClass = Class.forName(DAS_TYPED_OBJECT);
        Method getDataTypeM = dasTypedObjectClass.getMethod("getDataType");
        Method getSpecificationM = Class.forName(DATA_TYPE).getMethod("getSpecification");
        // T-TestAssertReal：DasTypedObject.isNotNull()（javap 实证声明于 DasTypedObject 接口，
        // DasColumn extends DasTypedObject）——Method 一律从声明接口获取（T-Embed-10 跨
        // classloader 教训，禁止 obj.javaClass.getMethod）；反射失败为 null，不进非空清单
        Method isNotNullM = dasTypedObjectClass.getMethod("isNotNull");
        // O-59 T-IIR-02：DasTable.getColumnAttrs(DasColumn) → Set<DasColumn.Attribute>
        // （javap 实证声明于 DasTable：`public abstract java.util.Set<DasColumn$Attribute> getColumnAttrs(DasColumn)`；
        // 枚举常量名 `AUTO_GENERATED`）。同 T-Embed-10 教训，Method 从**声明类**取，不用 obj.javaClass。
        Method getColumnAttrsM = null;
        try {
            getColumnAttrsM = dasTableClass.getMethod("getColumnAttrs", dasColumnClass);
        } catch (Throwable ignored) {
            // runCatching{...}.getOrNull() 等价：反射失败为 null → autoGenerated 访问器恒 null
        }
        final Method parentM = getDasParentM;
        final Method columnAttrsM = getColumnAttrsM;
        return new TraversalOutcome(new ModelTraversal(
                elements,
                new DasAccessors(
                        dasTableClass::isInstance,
                        dasColumnClass::isInstance,
                        el -> {
                            // master 侧这三条访问器**不**各自 runCatching——异常原样冒到
                            // collectFromDataSource 的外层收敛为「反射链异常」bail。Java 的
                            // Function.apply 不能声明受检异常，故经 sneakyThrow 原样逃逸
                            // （运行时抛出的仍是原实例，bail 文案与 master 逐字一致）。
                            try {
                                Object v = getNameM.invoke(el);
                                return (v instanceof String) ? (String) v : null;
                            } catch (Throwable e) {
                                throw sneakyThrow(e);
                            }
                        },
                        el -> {
                            try {
                                Object v = getCommentM.invoke(el);
                                return (v instanceof String) ? (String) v : null;
                            } catch (Throwable e) {
                                throw sneakyThrow(e);
                            }
                        },
                        el -> {
                            try {
                                Object v = getChildrenM.invoke(el, columnKind);
                                return (v instanceof Iterable<?>) ? (Iterable<?>) v : null;
                            } catch (Throwable e) {
                                throw sneakyThrow(e);
                            }
                        },
                        el -> {
                            if (parentM == null) return null;
                            try {
                                return parentM.invoke(el);
                            } catch (Throwable e) {
                                return null;
                            }
                        },
                        col -> {
                            try {
                                Object dataType = getDataTypeM.invoke(col);
                                if (dataType == null) return null;
                                Object spec = getSpecificationM.invoke(dataType);
                                return (spec instanceof String) ? (String) spec : null;
                            } catch (Throwable e) {
                                return null;
                            }
                        },
                        col -> {
                            try {
                                Object flag = isNotNullM.invoke(col);
                                return (flag instanceof Boolean) ? (Boolean) flag : null;
                            } catch (Throwable e) {
                                return null;
                            }
                        },
                        (table, col) -> {
                            try {
                                // master：getColumnAttrsM?.invoke(table, col)?.let { attrs ->
                                //   (attrs as? Iterable<*>)?.any { a -> a?.toString() == AUTO_GENERATED_ATTR } == true }
                                // attrs 为 null → 返回 null；非 Iterable → `null == true` → FALSE（非 null）
                                Object attrs = (columnAttrsM != null) ? columnAttrsM.invoke(table, col) : null;
                                if (attrs == null) {
                                    return null;
                                }
                                boolean matched = false;
                                if (attrs instanceof Iterable<?>) {
                                    for (Object a : (Iterable<?>) attrs) {
                                        if (a != null && AUTO_GENERATED_ATTR.equals(a.toString())) {
                                            matched = true;
                                            break;
                                        }
                                    }
                                }
                                return matched;
                            } catch (Throwable e) {
                                return null;
                            }
                        })),
                null);
    }

    /**
     * 从单个数据源收集匹配表的表注释 + 列元数据（遍历 DasModel，命中即取列）。
     *
     * <p>T-DbSchemaCompletion：<b>任何"查不了"的情形都不再静默早退</b>——统一置 {@code bail} 原因，
     * 函数末尾那行 scan 日志必打（此前 model/traverser/columnKind 三处 null 直接 {@code return}，
     * 既无日志又丢掉了"没查"与"没有"的区别）。
     *
     * <p>P7（同名表污染）：命中条件由「表名相等」收紧为「表名相等 ∧ 表祖先链含本数据源 URL 的库名」，
     * 且<b>首个匹配即锁定</b>——旧实现只比表名、命中后继续遍历，把后续同名表（`mysql.user` /
     * `information_schema` 之流）的列并进同一个 Map，表注释也被最后一张覆盖（真机 60 列
     * = 业务表 9 列 + 系统表 50+）。库名取不到（URL 无库段 / Oracle SID 形态）或祖先链读不出时
     * <b>不限定</b>，退回按名匹配。
     *
     * <p>P7b：模型非 null 但一张表都没扫到 → 判「查不了」（见 {@link #bailAfterTraversal}）。
     */
    private static TableMeta collectFromDataSource(Object ds, String tableName) {
        String tableComment = null;
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        // O-58：列名**无条件**收录（下面的元数据过滤只作用于 out，不影响本集合）
        Set<String> allNames = new LinkedHashSet<>();
        // T-Embed-10.5 探针：B 路径 locateDbTables 直调同模型命中而本链 not found——
        // 分辨 elements 空遍历 / DasTable 筛不出 / 名称不匹配三种情形
        int scannedTables = 0;
        boolean hit = false;
        String bail = null;
        // P7：本数据源的库名维度（连接 URL 里的库名；取不到 → null，退回按名匹配）
        String targetDb = databaseNameOf(ds);
        // runCatching{...}.onFailure{...} 等价：try/catch(Throwable)；lambda 内 return@runCatching
        // = 跳出本块且不触发 onFailure → 用带标签块 + break 落到末尾 scan 日志
        scanAttempt:
        try {
            ModelTraversal stub = (traversalForTest != null) ? traversalForTest.apply(ds) : null;
            ModelTraversal traversal;
            if (stub != null) {
                traversal = stub;
            } else {
                TraversalOutcome reflected = reflectTraversal(ds);
                if (reflected.traversal() == null) {
                    bail = reflected.bail();
                    break scanAttempt;
                }
                traversal = reflected.traversal();
            }
            DasAccessors acc = traversal.accessors;
            for (Object element : traversal.elements) {
                if (element == null || !acc.isTable.apply(element)) {
                    continue;
                }
                scannedTables++;
                String tName = acc.name.apply(element);
                if (tName == null) {
                    continue;
                }
                if (!tName.equalsIgnoreCase(tableName)) {
                    continue;
                }
                // P7：首个匹配即锁定——同名表后续出现的一律跳过（旧行为=继续合并、注释被最后一张覆盖）
                if (hit) {
                    continue;
                }
                // P7：库维度限定——表祖先链须含本数据源 URL 的库名；链读不出（空）时不限定
                if (targetDb != null) {
                    List<String> chain = ancestorChain(element, acc);
                    if (!chain.isEmpty() && chain.stream().noneMatch(s -> s.equalsIgnoreCase(targetDb))) {
                        continue;
                    }
                }
                hit = true;
                // 表级 COMMENT 与列遍历同一元素读取（blank 视为数据库没写，保留 null 供调用方回落）
                String tc = acc.comment.apply(element);
                tableComment = (tc != null && !tc.isBlank()) ? tc : null;
                Iterable<?> columns = acc.children.apply(element);
                if (columns == null) {
                    continue;
                }
                for (Object col : columns) {
                    if (col == null || !acc.isColumn.apply(col)) {
                        continue;
                    }
                    String cName = acc.name.apply(col);
                    if (cName == null || cName.isBlank()) {
                        continue;
                    }
                    allNames.add(cName);
                    String comment = acc.comment.apply(col);
                    String typeSpec = acc.typeSpec.apply(col);
                    Boolean notNull = acc.notNull.apply(col);
                    Boolean autoGenerated = acc.autoGenerated.apply(element, col);
                    // 收录条件放宽：clean DDL（无注释无类型串）的 NOT NULL 列此前会被过滤掉；
                    // O-59 T-IIR-02 再放宽一项——**自增列即使三项皆空也必须收录**，否则回滚侧
                    // identity 包装拿不到列名（正是 blocker② 的形态：列在表里、但没进这份稀疏表）。
                    if ((comment != null && !comment.isBlank()) || (typeSpec != null && !typeSpec.isBlank())
                            || Boolean.TRUE.equals(notNull) || Boolean.TRUE.equals(autoGenerated)) {
                        out.put(cName, new ColumnMeta(
                                (comment != null && !comment.isBlank()) ? comment : null,
                                (typeSpec != null && !typeSpec.isBlank()) ? typeSpec : null,
                                notNull,
                                autoGenerated));
                    }
                }
            }
        } catch (Throwable e) {
            // 升 WARN+堆栈：真机排障依赖此行（仅消息无法定位炸点，16:09 日志教训）
            bail = "反射链异常: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warn("DatabaseSchemaResolver.collectFromDataSource failed", e);
        }
        // P7b：遍历 0 张表 = 没查（内省未完成），不是「没有此表」——不记负缓存、下次调用重试
        bail = bailAfterTraversal(hit, scannedTables, bail);
        LOG.info("DatabaseSchemaResolver scan: table=" + tableName
                + ", ds=" + dsName(ds)
                + ", bail=" + (bail != null ? bail : "-")
                + ", tablesScanned=" + scannedTables
                + ", hit=" + hit
                + ", foundColumns=" + out.size()
                + ", totalColumns=" + allNames.size()
                + ", tableComment=" + (tableComment != null));
        return new TableMeta(tableComment, out, bail != null, allNames);
    }

    /**
     * 受检异常原样逃逸（k2j 移植件：Kotlin 无受检异常，`Method.invoke` 的
     * {@code IllegalAccessException} 在 master 侧直接冒泡到外层 runCatching）。
     *
     * <p>运行时抛出的仍是传入的 {@code t} 本身——外层 {@code catch (Throwable)} 拿到的
     * {@code getClass().getSimpleName()} 与 master 的 bail 文案逐字一致。
     * 同仓同款用法见 {@code McpToolsetSupport.sneakyThrow}（跨包 protected，故此处自持一份）。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    private DatabaseSchemaResolver() {
    }
}
