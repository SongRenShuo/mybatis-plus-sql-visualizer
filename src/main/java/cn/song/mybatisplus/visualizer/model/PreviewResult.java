package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.xml.PagesColumn;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/**
 * 预览结果（M3-01 整合层产物）：一次 Wrapper 转换的完整展示数据。
 *
 * @param sql              多行格式化 SQL（主展示）
 * @param compactSql       压缩 SQL（F-03 视图切换备用）
 * @param unresolvedParams 未解析参数来源（F-02 对照表，顺序对应 SQL 中 ?）
 * @param paramTable       参数对照表文本（-- 参数对照: [...]，无参数为 null）
 * @param wrapperType      Wrapper 类型（UI 可展示类型标签）
 * @param tableName        目标表名（UI 可展示）
 * @param warning          风险警示（如 UPDATE/DELETE 无 WHERE 条件），null 表示无
 * @param columns          实体列名（snake_case，M9 补全上下文用）
 * @param columnComments   列注释（columnName → comment，M16：补全 tailText 与关键词检索）
 * @param tableComment     表注释（M16：表名补全 tailText 与关键词检索）
 * @param previewModel     预览模型（M12）：语句模型 + 动态条件行，供面板勾选时模型级重生成；
 *                         null 表示无可勾选动态条件（批量操作等走旧文本渲染路径）
 * @param paramHints       与 unresolvedParams 并行的回填提示（M13）：引号/LIKE 百分号，
 *                         旧文本路径（previewModel=null）回填用
 * @param candidateTables  联表候选表（@TableName 实体；预览补全表名候选来源）
 * @param batchModel       批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 的列级勾选结构
 * @param xmlModel         XML 动态 SQL 预览模型（T-M24-04）
 * @param initialParamValues 参数行初始值预填（source → 初始值）
 * @param perfWarnings     SQL 性能分析警告列表（面板展示用）；null 表示未分析/无警告
 * @param fillClauses      fill 附加查询子句列表（MPJ 1.5.1+）；null 表示无 fill 调用
 * @param previousSql      会话级缓存的上一次预览 SQL（null = 首次预览，不显示差异）
 * @param pagesColumns     maintainPage 列元数据（F-45）
 * @param stackDigest      调用栈快照「类#方法:行 ← …」（栈顶在左；T-SourceJumpFunnel P2-2）；
 *                         null = 无（桥通道 / 旧条目），面板不显示栈节点
 * @param threadName       T-StackBizFrame S3：产生该 SQL 的线程名（agent 通道透传）。调用栈弹层标题展示，
 *                         让「调度线程 / 异步池 / 请求线程」一眼可辨。null = 未知（桥通道 / 旧条目），
 *                         标题退回纯「调用栈」
 * @param hasDollarRawSegments 本次预览语句含 ${} 原文拼接段（T-InjectionGate T-IG-01，解析期 token 事实）；
 *                         非 XML 链恒 false
 * @param placeholderParamNames JPA 命名参数占位符名序（T-JpaParamOrder F3，首现序与 SQL `?` 一一对应）；
 *                         空表 = 按序回填（既有语义）
 * @param duplicateParamNames  同名命名参数多次 setParameter 的参数名集合（T-JpaParamOrder F5，覆盖语义）；
 *                         空集 = 无重复
 * @param diagramStatement F-1 修复：JOIN 图标签页的<b>独立</b>语句载体（仅含 JOIN 的 SELECT 非 null）。
 *                         不能复用 {@code previewModel}——后者只在有动态条件视图时构造，无条件的联表链
 *                         为 null，而 {@code sql} 是纯文本、面板无从判断 JOIN；本字段把「是否画图」与
 *                         「是否走模型级重渲染」解耦。默认 null，既有构造点零影响
 * @param historyFingerprintSeed 参数历史缓存键的<b>稳定指纹种子</b>（注入拦截器子句之前的 SQL 文本）。
 *                         不能直接用 {@code sql} 当指纹——逻辑删除/多租户等拦截器模拟会在渲染前改写
 *                         SQL 文本，用户一开这些开关键就变、回填好的参数全部召回 miss；null = 回落
 *                         {@code sql}（存量行为零变化）
 */
public final class PreviewResult {
    private final String sql;
    private final String compactSql;
    private final List<String> unresolvedParams;
    private final String paramTable;
    private final WrapperType wrapperType;
    private final String tableName;
    private final String warning;
    private final List<String> columns;
    private final Map<String, String> columnComments;
    private final String tableComment;
    private final SqlPreviewModel previewModel;
    private final List<ParamHint> paramHints;
    private final List<TableInfo> candidateTables;
    private final BatchModel batchModel;
    private final XmlPreviewModel xmlModel;
    private final Map<String, String> initialParamValues;
    private final List<PerfWarning> perfWarnings;
    private final List<FillClause> fillClauses;
    private final String previousSql;
    private final List<PagesColumn> pagesColumns;
    /** JDBC 命名参数（NamedParameterJdbcTemplate `:name`）：参数名 → 引号提示；空 map = 纯 `?` 顺序回填。 */
    private final Map<String, ParamHint> namedParamHints;
    /**
     * T-SourceJumpFunnel P2-2：调用栈快照「类#方法:行 ← …」（栈顶在左；agent 通道产出，
     * 日志/历史条目双击预览透传）。null = 无（桥通道 / 旧条目），面板不显示栈节点。
     */
    private final String stackDigest;
    /**
     * T-StackBizFrame S3：产生该 SQL 的线程名（agent 通道透传）。调用栈弹层标题展示，
     * 让「调度线程 / 异步池 / 请求线程」一眼可辨。null = 未知（桥通道 / 旧条目），标题退回纯「调用栈」。
     */
    private final String threadName;
    /**
     * T-InjectionGate T-IG-01：本次预览语句含 `${}` 原文拼接段（XML/注解预览链，解析期 `${` token 事实，
     * 与替换是否成功无关——成功替换为模拟值后文本里已无 `${}` 痕迹）。面板执行/EXPLAIN 前据此弹确认闸门，
     * 复制可执行 SQL 据此给产物加标记注释。非 XML 链（Wrapper/JPA/日志还原）无 `${}` 语义恒 false。
     */
    private final boolean hasDollarRawSegments;
    /**
     * R12 T-PaginationSim：分页 COUNT 包裹 SQL（D1=A 预览附第二条语句，面板在主 SQL 后并列渲染；
     * D2=A 基础形态 {@code SELECT COUNT(*) FROM (原SQL) TOTAL}，MP 3.5.17 反编译逐字节形态）。
     * null = 无分页 COUNT（未分页 / count·exists 消费 / searchCount=false / 开关关闭）。
     */
    private final String countSql;
    /**
     * R12 T-PaginationSim：分页 LIMIT 行（{@code LIMIT ?, ?} 等方言形态，
     * {@link cn.song.mybatisplus.visualizer.sql.Dialect#renderLimit} 渲染产物）。面板勾选/参数变更走模型级
     * 重渲染（XmlPreviewModel.renderSql / SqlGenerator）时模型层 SQL 不含分页后处理，面板据此把 LIMIT 行
     * 带回主 SQL 尾部——服务端 sql/compactSql 已含该行，此字段仅供面板重渲染路径复用。
     * null = 无分页 LIMIT（未分页 / count·exists 消费 / 已含手写 LIMIT / 开关关闭）。
     */
    private final String paginationLimitLine;
    /**
     * T-JpaParamOrder F3：JPA 命名参数占位符名序（HQL {@code :name} 首现序，与转换后 SQL 中
     * {@code ?} 一一对应）。非空时渲染/复制按名回填（同名占位复用同值，JPA 命名参数语义），
     * 与 setParameter 书写顺序解耦；空表 = 维持按序回填（既有语义）。仅当全部 {@code ?}
     * 均来自命名参数替换时才置非空（位置参数混入时数量不齐，服务端置空回退按序），
     * 由 SqlPreviewService 出口保证。
     */
    private final List<String> placeholderParamNames;
    /**
     * T-JpaParamOrder F5：同名命名参数多次 setParameter 的参数名集合（JPA 覆盖语义，运行时以
     * 最后一次为准）。面板据此给「写重」轻提示；空集 = 无重复。
     */
    private final Set<String> duplicateParamNames;
    /**
     * F-1 修复：JOIN 图标签页的<b>独立</b>语句载体（仅含 JOIN 的 SELECT 非 null）。
     *
     * <p>为什么不能复用 {@code previewModel}：{@code previewModel} 只在有动态条件视图
     * （{@code collectConditionViews} 非空，即链里有 eq/like 等条件调用）时才构造——无条件的联表链
     * （如 MPJ 的 {@code selectAll + leftJoin + fill}）{@code previewModel} 为 null，而 {@code sql}
     * 是纯文本、面板无从判断 JOIN，导致这类链拿不到「JOIN 图」标签页。本字段把「是否画图」的判据与
     * 「是否走模型级重渲染」的判据解耦：有 JOIN 即非 null，与 {@code previewModel} 空否无关。
     * 默认 null，既有构造点零影响（向后兼容）。</p>
     */
    private final SelectStatement diagramStatement;
    /**
     * 参数历史缓存键的<b>稳定指纹种子</b>（注入拦截器子句之前的 SQL 文本）。
     *
     * <p>为什么不能直接用 {@code sql} 当指纹：历史键 = {@code 表名:SHA-256(SQL 全文)}，而逻辑删除 /
     * 多租户等拦截器模拟会在渲染前改写 SQL 文本（追加 {@code AND deleted = 0} / {@code AND tenant_id = ?}）——
     * 用户一开这些开关，键就变，此前回填好的参数全部召回 miss、退回占位符（且 500ms 防抖后
     * 新键还会以空值覆盖落库）。本字段承载「注入前」的语句文本，供面板算键时优先使用，
     * 使开关切换不再影响参数历史归属。null = 回落 {@code sql}（存量行为零变化）。</p>
     */
    private final String historyFingerprintSeed;
    /** 分页后处理新增占位的来源序列；与原 SQL 的位置映射分开保存，重渲染不得按名称去重。 */
    private final List<String> paginationParamSources;
    private final Dialect renderedDialect;
    private final DialectDecisionEngine.Decision dialectDecision;
    /** R90：本次预览实际入库条目，recordHistory 在返回结果前设置；副本保留，未知为空。 */
    private String historyEntryId = "";
    /** ShardingSphere 路由日志原文，只读附注，不参与 SQL 编辑、复制或执行。 */
    private String actualSql;
    /**
     * T-PresetMethodScope：参数预设/历史缓存键的<b>声明作用域</b>
     * （{@code cn.song.mybatisplus.visualizer.psi.PreviewOriginScope.of}）。
     *
     * <p>空串 = 无方法上下文（日志/历史双击、纯文本预览等），键退回「表名 + SQL 指纹」旧格式。
     * 非空时键尾追加 {@code |@<scope>}，同一段 SQL 写在两个方法里不再共享预设与参数历史。
     * 可变字段供 {@code cn.song.mybatisplus.visualizer.ui.SqlPreviewService} 的 recordHistory
     * 回写（同 {@link #historyEntryId}）。</p>
     */
    private String originScopeKey = "";

    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns, Map.of(), null, false,
                null, null, List.of(), Set.of());
    }

    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns,
                         Map<String, ParamHint> namedParamHints) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns,
                namedParamHints, null, false, null, null, List.of(), Set.of());
    }

    /**
     * K2J 互操作：master 侧 {@code placeholderParamNames}/{@code duplicateParamNames} 为带默认值的
     * 后置字段（Kotlin 默认参），历史 23 参构造形态在 Kotlin 调用方（测试树位置实参）天然合法——
     * Java 侧保留同形重载，新两参取默认空值（与 25 参规范构造器同为 {@code null} 归一语义）。
     */
    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns,
                         Map<String, ParamHint> namedParamHints, String stackDigest, boolean hasDollarRawSegments) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns,
                namedParamHints, stackDigest, hasDollarRawSegments, null, null, List.of(), Set.of());
    }

    /**
     * K2J 互操作：master 新增 {@code threadName}（T-StackBizFrame S3，Kotlin 声明序插在 stackDigest 与
     * hasDollarRawSegments 之间）取默认 null 的历史 34 参形态，委托规范构造器。
     */
    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns,
                         Map<String, ParamHint> namedParamHints, String stackDigest, boolean hasDollarRawSegments,
                         String countSql, String paginationLimitLine,
                         List<String> placeholderParamNames, Set<String> duplicateParamNames,
                         SelectStatement diagramStatement, String historyFingerprintSeed,
                         List<String> paginationParamSources, Dialect renderedDialect,
                         DialectDecisionEngine.Decision dialectDecision, String historyEntryId, String actualSql) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns,
                namedParamHints, stackDigest, null, hasDollarRawSegments, countSql, paginationLimitLine,
                placeholderParamNames, duplicateParamNames, diagramStatement, historyFingerprintSeed,
                paginationParamSources, renderedDialect, dialectDecision, historyEntryId, actualSql);
    }

    /**
     * 规范构造器（35 参）：K2J 侧 {@code threadName}/{@code countSql}/{@code paginationLimitLine}/
     * {@code diagramStatement}/{@code historyFingerprintSeed} 为 master 新增的带默认值后置字段
     * （Kotlin 默认参），历史短参形态在 Kotlin 调用方天然合法——Java 侧保留对应重载（见上/下）委托
     * 本构造器、新参取默认值（null，与 master 默认值等价）。
     */
    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns,
                         Map<String, ParamHint> namedParamHints, String stackDigest, String threadName,
                         boolean hasDollarRawSegments,
                         String countSql, String paginationLimitLine,
                         List<String> placeholderParamNames, Set<String> duplicateParamNames,
                         SelectStatement diagramStatement, String historyFingerprintSeed,
                         List<String> paginationParamSources, Dialect renderedDialect,
                         DialectDecisionEngine.Decision dialectDecision, String historyEntryId, String actualSql) {
        this.sql = sql;
        this.compactSql = compactSql;
        this.unresolvedParams = unresolvedParams;
        this.paramTable = paramTable;
        this.wrapperType = wrapperType;
        this.tableName = tableName;
        this.warning = warning;
        this.columns = columns;
        this.columnComments = columnComments;
        this.tableComment = tableComment;
        this.previewModel = previewModel;
        this.paramHints = paramHints;
        this.candidateTables = candidateTables;
        this.batchModel = batchModel;
        this.xmlModel = xmlModel;
        this.initialParamValues = initialParamValues;
        this.perfWarnings = perfWarnings;
        this.fillClauses = fillClauses;
        this.previousSql = previousSql;
        this.pagesColumns = pagesColumns;
        this.namedParamHints = namedParamHints != null ? namedParamHints : Map.of();
        this.stackDigest = stackDigest;
        this.threadName = threadName;
        this.hasDollarRawSegments = hasDollarRawSegments;
        this.countSql = countSql;
        this.paginationLimitLine = paginationLimitLine;
        this.placeholderParamNames = placeholderParamNames != null ? placeholderParamNames : List.of();
        this.duplicateParamNames = duplicateParamNames != null ? duplicateParamNames : Set.of();
        this.diagramStatement = diagramStatement;
        this.historyFingerprintSeed = historyFingerprintSeed;
        this.paginationParamSources = paginationParamSources != null ? paginationParamSources : List.of();
        this.renderedDialect = renderedDialect;
        this.dialectDecision = dialectDecision;
        this.historyEntryId = historyEntryId != null ? historyEntryId : "";
        this.actualSql = actualSql;
    }

    /** K2J 互操作：master 新五参取默认值的 29 参形态（既有调用点零改动）。 */
    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns,
                         Map<String, ParamHint> namedParamHints, String stackDigest, boolean hasDollarRawSegments,
                         String countSql, String paginationLimitLine,
                         List<String> placeholderParamNames, Set<String> duplicateParamNames,
                         SelectStatement diagramStatement, String historyFingerprintSeed) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns,
                namedParamHints, stackDigest, hasDollarRawSegments, countSql, paginationLimitLine,
                placeholderParamNames, duplicateParamNames, diagramStatement, historyFingerprintSeed,
                null, null, null, "", null);
    }

    /** K2J 互操作：master 新两参（diagramStatement/historyFingerprintSeed）取默认 null 的历史 27 参形态。 */
    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns,
                         Map<String, ParamHint> namedParamHints, String stackDigest, boolean hasDollarRawSegments,
                         String countSql, String paginationLimitLine,
                         List<String> placeholderParamNames, Set<String> duplicateParamNames) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns,
                namedParamHints, stackDigest, hasDollarRawSegments, countSql, paginationLimitLine,
                placeholderParamNames, duplicateParamNames, null, null);
    }

    /** K2J 互操作：master 新两参（countSql/paginationLimitLine）取默认 null 的历史 25 参形态。 */
    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns,
                         Map<String, ParamHint> namedParamHints, String stackDigest, boolean hasDollarRawSegments,
                         List<String> placeholderParamNames, Set<String> duplicateParamNames) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns,
                namedParamHints, stackDigest, hasDollarRawSegments, null, null,
                placeholderParamNames, duplicateParamNames);
    }

    public PreviewResult(String sql, String compactSql,
                         List<String> unresolvedParams,
                         String paramTable, WrapperType wrapperType,
                         String tableName, List<String> columns) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, null, columns,
                Map.of(), null, null, List.of(), List.of(), null, null, Map.of(), null, null, null, null);
    }

    /** 日志识别通道便捷构造（T-HistParam F3 toPreviewResult：非默认字段显式传参，其余同 Kotlin data class 默认值）。 */
    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams,
                         String paramTable, WrapperType wrapperType, String tableName, String warning,
                         List<ParamHint> paramHints, Map<String, String> initialParamValues) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning,
                List.of(), Map.of(), null, null, paramHints, List.of(), null, null,
                initialParamValues, null, null, null, null);
    }

    public String getSql() {
        return sql;
    }

    public String getCompactSql() {
        return compactSql;
    }

    public List<String> getUnresolvedParams() {
        return unresolvedParams;
    }

    public String getParamTable() {
        return paramTable;
    }

    public WrapperType getWrapperType() {
        return wrapperType;
    }

    public String getTableName() {
        return tableName;
    }

    public String getWarning() {
        return warning;
    }

    public List<String> getColumns() {
        return columns;
    }

    public Map<String, String> getColumnComments() {
        return columnComments;
    }

    public String getTableComment() {
        return tableComment;
    }

    public SqlPreviewModel getPreviewModel() {
        return previewModel;
    }

    public List<ParamHint> getParamHints() {
        return paramHints;
    }

    public List<TableInfo> getCandidateTables() {
        return candidateTables;
    }

    public BatchModel getBatchModel() {
        return batchModel;
    }

    public XmlPreviewModel getXmlModel() {
        return xmlModel;
    }

    public Map<String, String> getInitialParamValues() {
        return initialParamValues;
    }

    public List<PerfWarning> getPerfWarnings() {
        return perfWarnings;
    }

    public List<FillClause> getFillClauses() {
        return fillClauses;
    }

    public String getPreviousSql() {
        return previousSql;
    }

    public List<PagesColumn> getPagesColumns() {
        return pagesColumns;
    }

    public Map<String, ParamHint> getNamedParamHints() {
        return namedParamHints;
    }

    public String getStackDigest() {
        return stackDigest;
    }

    /** T-StackBizFrame S3：产生该 SQL 的线程名（agent 通道透传；null = 未知，标题退回纯「调用栈」）。 */
    public String getThreadName() {
        return threadName;
    }

    public boolean isHasDollarRawSegments() {
        return hasDollarRawSegments;
    }

    public List<String> getPlaceholderParamNames() {
        return placeholderParamNames;
    }

    public Set<String> getDuplicateParamNames() {
        return duplicateParamNames;
    }

    /** R12 T-PaginationSim：分页 COUNT 包裹 SQL（null = 无）。 */
    public String getCountSql() {
        return countSql;
    }

    /** R12 T-PaginationSim：分页 LIMIT 行（null = 无；面板重渲染路径复用）。 */
    public String getPaginationLimitLine() {
        return paginationLimitLine;
    }

    /** F-1 修复：JOIN 图标签页的独立语句载体（null = 无 JOIN / 不画图）。 */
    public SelectStatement getDiagramStatement() {
        return diagramStatement;
    }

    /** 参数历史缓存键的稳定指纹种子（null = 回落 sql）。 */
    public String getHistoryFingerprintSeed() {
        return historyFingerprintSeed;
    }

    public List<String> getPaginationParamSources() {
        return paginationParamSources;
    }

    public Dialect getRenderedDialect() {
        return renderedDialect;
    }

    public DialectDecisionEngine.Decision getDialectDecision() {
        return dialectDecision;
    }

    public String getHistoryEntryId() {
        return historyEntryId;
    }

    public void setHistoryEntryId(String historyEntryId) {
        this.historyEntryId = historyEntryId != null ? historyEntryId : "";
    }

    public String getActualSql() {
        return actualSql;
    }

    public void setActualSql(String actualSql) {
        this.actualSql = actualSql;
    }

    public String getOriginScopeKey() {
        return originScopeKey;
    }

    public void setOriginScopeKey(String originScopeKey) {
        this.originScopeKey = originScopeKey != null ? originScopeKey : "";
    }

    /** effectiveDialect：previewModel/batchModel 方言优先，缺省取本次渲染瞬态方言。 */
    public Dialect getEffectiveDialect() {
        if (previewModel != null && previewModel.getDialect() != null) {
            return previewModel.getDialect();
        }
        if (batchModel != null && batchModel.getDialect() != null) {
            return batchModel.getDialect();
        }
        return renderedDialect;
    }

    public PreviewResult copy(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                              WrapperType wrapperType, String tableName, String warning, List<String> columns,
                              Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                              List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                              XmlPreviewModel xmlModel, Map<String, String> initialParamValues,
                              List<PerfWarning> perfWarnings, List<FillClause> fillClauses, String previousSql,
                              List<PagesColumn> pagesColumns) {
        return new PreviewResult(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns, namedParamHints,
                stackDigest, hasDollarRawSegments, countSql, paginationLimitLine, placeholderParamNames,
                duplicateParamNames, diagramStatement, historyFingerprintSeed);
    }

    public PreviewResult copy(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                              WrapperType wrapperType, String tableName, String warning, List<String> columns,
                              Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                              List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                              XmlPreviewModel xmlModel, Map<String, String> initialParamValues,
                              List<PerfWarning> perfWarnings, List<FillClause> fillClauses, String previousSql,
                              List<PagesColumn> pagesColumns, Map<String, ParamHint> namedParamHints) {
        return new PreviewResult(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns, namedParamHints,
                stackDigest, hasDollarRawSegments, countSql, paginationLimitLine, placeholderParamNames,
                duplicateParamNames, diagramStatement, historyFingerprintSeed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PreviewResult)) {
            return false;
        }
        PreviewResult that = (PreviewResult) o;
        return Objects.equals(sql, that.sql)
                && Objects.equals(compactSql, that.compactSql)
                && Objects.equals(unresolvedParams, that.unresolvedParams)
                && Objects.equals(paramTable, that.paramTable)
                && wrapperType == that.wrapperType
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(warning, that.warning)
                && Objects.equals(columns, that.columns)
                && Objects.equals(columnComments, that.columnComments)
                && Objects.equals(tableComment, that.tableComment)
                && Objects.equals(previewModel, that.previewModel)
                && Objects.equals(paramHints, that.paramHints)
                && Objects.equals(candidateTables, that.candidateTables)
                && Objects.equals(batchModel, that.batchModel)
                && Objects.equals(xmlModel, that.xmlModel)
                && Objects.equals(initialParamValues, that.initialParamValues)
                && Objects.equals(perfWarnings, that.perfWarnings)
                && Objects.equals(fillClauses, that.fillClauses)
                && Objects.equals(previousSql, that.previousSql)
                && Objects.equals(pagesColumns, that.pagesColumns)
                && Objects.equals(namedParamHints, that.namedParamHints)
                && Objects.equals(stackDigest, that.stackDigest)
                && Objects.equals(threadName, that.threadName)
                && hasDollarRawSegments == that.hasDollarRawSegments
                && Objects.equals(countSql, that.countSql)
                && Objects.equals(paginationLimitLine, that.paginationLimitLine)
                && Objects.equals(placeholderParamNames, that.placeholderParamNames)
                && Objects.equals(duplicateParamNames, that.duplicateParamNames)
                && Objects.equals(diagramStatement, that.diagramStatement)
                && Objects.equals(historyFingerprintSeed, that.historyFingerprintSeed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns, namedParamHints,
                stackDigest, threadName, hasDollarRawSegments, countSql, paginationLimitLine, placeholderParamNames,
                duplicateParamNames, diagramStatement, historyFingerprintSeed);
    }

    @Override
    public String toString() {
        return "PreviewResult(sql=" + sql + ", compactSql=" + compactSql + ", unresolvedParams=" + unresolvedParams
                + ", paramTable=" + paramTable + ", wrapperType=" + wrapperType + ", tableName=" + tableName
                + ", warning=" + warning + ", columns=" + columns + ", columnComments=" + columnComments
                + ", tableComment=" + tableComment + ", previewModel=" + previewModel + ", paramHints=" + paramHints
                + ", candidateTables=" + candidateTables + ", batchModel=" + batchModel + ", xmlModel=" + xmlModel
                + ", initialParamValues=" + initialParamValues + ", perfWarnings=" + perfWarnings + ", fillClauses="
                + fillClauses + ", previousSql=" + previousSql + ", pagesColumns=" + pagesColumns
                + ", namedParamHints=" + namedParamHints + ", stackDigest=" + stackDigest
                + ", threadName=" + threadName
                + ", hasDollarRawSegments=" + hasDollarRawSegments
                + ", countSql=" + countSql
                + ", paginationLimitLine=" + paginationLimitLine
                + ", placeholderParamNames=" + placeholderParamNames
                + ", duplicateParamNames=" + duplicateParamNames
                + ", diagramStatement=" + diagramStatement
                + ", historyFingerprintSeed=" + historyFingerprintSeed + ")";
    }
}
