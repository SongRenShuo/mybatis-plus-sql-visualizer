package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.xml.PagesColumn
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModel

/**
 * 预览结果（M3-01 整合层产物）：一次 Wrapper 转换的完整展示数据。
 *
 * @param sql              多行格式化 SQL（主展示）
 * @param compactSql       压缩 SQL（F-03 视图切换备用）
 * @param unresolvedParams 未解析参数来源（F-02 对照表，顺序对应 SQL 中 `?`）
 * @param paramTable       参数对照表文本（`-- 参数对照: [...]`，无参数为 null）
 * @param wrapperType      Wrapper 类型（UI 可展示类型标签）
 * @param tableName        目标表名（UI 可展示）
 * @param warning          风险警示（如 UPDATE/DELETE 无 WHERE 条件），null 表示无
 * @param columns          实体列名（snake_case，M9 补全上下文用）
 * @param columnComments   列注释（columnName → comment，M16：补全 tailText 与关键词检索）
 * @param tableComment     表注释（M16：表名补全 tailText 与关键词检索）
 * @param previewModel     预览模型（M12）：语句模型 + 动态条件行，供面板勾选时模型级重生成；
 *                         null 表示无可勾选动态条件（批量操作等走旧文本渲染路径）
 * @param paramHints       与 [unresolvedParams] 并行的回填提示（M13）：引号/LIKE 百分号，
 *                         旧文本路径（previewModel=null）回填用
 * @param batchModel       批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 的列级勾选结构，
 *                         面板勾选重建 SQL；无列级选择（Wrapper 预览 / SELECT·DELETE 批量）为 null
 * @param xmlModel         XML 动态 SQL 预览模型（T-M24-04）：mapper 语句的条件开关/foreach/bind
 *                         面板勾选重建；非 XML 预览为 null（与 previewModel/batchModel 互斥）
 * @param fillClauses      fill 附加查询子句列表（MPJ 1.5.1+，T-MpjFill）：按调用顺序；
 *                         null 表示无 fill 调用。面板在主 SQL 之后追加渲染 fill 附加 SELECT 段。
 * @param pagesColumns     maintainPage 列元数据（F-45）：<pages> 预览非 null，面板「列信息」tab 展示源。
 */
data class PreviewResult(
    val sql: String,
    val compactSql: String,
    val unresolvedParams: List<String>,
    val paramTable: String?,
    val wrapperType: WrapperType?,
    val tableName: String?,
    val warning: String? = null,
    val columns: List<String> = emptyList(),
    val columnComments: Map<String, String> = emptyMap(),
    val tableComment: String? = null,
    val previewModel: SqlPreviewModel? = null,
    val paramHints: List<ParamHint> = emptyList(),
    /** T-M27.4：联表候选表（@TableName 实体；预览补全表名候选来源）。 */
    val candidateTables: List<TableInfo> = emptyList(),
    val batchModel: BatchModel? = null,
    val xmlModel: XmlPreviewModel? = null,
    /** T-M42：参数行初始值预填（source → 初始值，如日志识别 `param1` → `admin`）；
     *  面板初始化参数输入框时优先取本表，缺失回落空串。默认空 map，既有调用点零影响。 */
    val initialParamValues: Map<String, String> = emptyMap(),
    /** F-2：SQL 性能分析警告列表（面板展示用）；null 表示未分析/无警告。 */
    val perfWarnings: List<PerfWarning>? = null,
    /** T-MpjFill：fill 附加查询子句列表（MPJ 1.5.1+）；null 表示无 fill 调用。 */
    val fillClauses: List<FillClause>? = null,
    /** O-4：会话级缓存的上一次预览 SQL（null = 首次预览，不显示差异）。 */
    val previousSql: String? = null,
    /** F-45：maintainPage 列元数据（仅 pages XML 预览非 null；面板「列信息」tab 展示源）。 */
    val pagesColumns: List<PagesColumn>? = null,
    /**
     * JDBC 命名参数（NamedParameterJdbcTemplate `:name`）：参数名 → 引号提示。
     *
     * 非空时面板按名回填（同名参数全部出现共用输入值）；空表示纯 `?` 顺序回填（既有语义）。
     * 默认空 map，既有构造点零影响（向后兼容）。
     */
    val namedParamHints: Map<String, ParamHint> = emptyMap(),
    /**
     * T-SourceJumpFunnel P2-2：调用栈快照「类#方法:行 ← …」（栈顶在左；agent 通道产出，
     * 日志/历史条目双击预览透传）。null = 无（桥通道 / 旧条目），面板不显示栈节点。
     */
    val stackDigest: String? = null,
    /**
     * T-StackBizFrame S3：产生该 SQL 的线程名（agent 通道透传）。调用栈弹层标题展示，
     * 让「调度线程 / 异步池 / 请求线程」一眼可辨。null = 未知（桥通道 / 旧条目），标题退回纯「调用栈」。
     */
    val threadName: String? = null,
    /**
     * T-InjectionGate T-IG-01：本次预览语句含 `${}` 原文拼接段（XML/注解预览链，解析期 `${` token 事实，
     * 与替换是否成功无关——成功替换为模拟值后文本里已无 `${}` 痕迹）。面板执行/EXPLAIN 前据此弹确认闸门，
     * 复制可执行 SQL 据此给产物加标记注释。非 XML 链（Wrapper/JPA/日志还原）无 `${}` 语义恒 false。
     */
    val hasDollarRawSegments: Boolean = false,
    /**
     * T-PreviewOpenPerfM2 M9：本次预览因**索引未就绪**（dumb mode）跳过了 M2 调用点检索，
     * 这里是「索引就绪后重算一次」的闭包；面板据此挂 `DumbService.runWhenSmart` 自动刷新，
     * 让冷态首次打开既秒出骨架、又能在索引就绪后补上真实 Criterion。
     * null = 无需重试（未跳过 / 非 XML 链）。
     */
    val retryWhenIndexReady: (() -> PreviewResult?)? = null,
    /**
     * UI-001：[warning] 是否为**信息性说明**（非渲染失败）。
     *
     * true 时面板横幅/元信息行用 `ℹ` 信息级前缀（`panel.banner.renderNote`），不再冒充
     * 「⚠ 预览未完整还原」。判据由产出链自证（iBatis 链：parameterMap 的「（引擎行为）」说明、
     * iterate 未供值的「待填值」降级提示）；其余链路默认 false，横幅语义零变化。
     */
    val informationalWarning: Boolean = false,
    /**
     * R12 T-PaginationSim：分页 COUNT 包裹 SQL（D1=A 预览附第二条语句，面板在主 SQL 后并列渲染；
     * D2=A 基础形态 `SELECT COUNT(*) FROM (原SQL) TOTAL`，MP 3.5.17 反编译逐字节形态）。
     * null = 无分页 COUNT（未分页 / count·exists 消费 / searchCount=false / 开关关闭）。
     */
    val countSql: String? = null,
    /**
     * R12 T-PaginationSim：分页 LIMIT 行（`LIMIT ?, ?` 等方言形态，[cn.song.mybatisplus.visualizer.sql.Dialect.renderLimit]
     * 渲染产物）。面板勾选/参数变更走模型级重渲染（XmlPreviewModel.renderSql / SqlGenerator）时模型层
     * SQL 不含分页后处理，面板据此把 LIMIT 行带回主 SQL 尾部——服务端 sql/compactSql 已含该行，此字段
     * 仅供面板重渲染路径复用。null = 无分页 LIMIT（未分页 / count·exists 消费 / 已含手写 LIMIT / 开关关闭）。
     */
    val paginationLimitLine: String? = null,
    /** 分页后处理新增占位的来源序列；与原 SQL 的位置映射分开保存，重渲染不得按名称去重。 */
    val paginationParamSources: List<String> = emptyList(),
    /**
     * T-JpaParamOrder F3：JPA 命名参数占位符名序（HQL `:name` 首现序，与转换后 SQL 中 `?` 一一对应）。
     * 非空时渲染/复制按名回填（同名占位复用同值，JPA 命名参数语义），与 setParameter 书写顺序解耦；
     * 空表 = 维持按序回填（既有语义）。仅当全部 `?` 均来自命名参数替换时才置非空（位置参数混入时
     * 数量不齐，服务端置空回退按序），由 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 出口保证。
     */
    val placeholderParamNames: List<String> = emptyList(),
    /**
     * T-JpaParamOrder F5：同名命名参数多次 setParameter 的参数名集合（JPA 覆盖语义，运行时以
     * 最后一次为准）。面板据此给「写重」轻提示；空集 = 无重复。
     */
    val duplicateParamNames: Set<String> = emptySet(),
    /**
     * F-1 修复：JOIN 图标签页的**独立**语句载体（仅含 JOIN 的 SELECT 非 null）。
     *
     * 为什么不能复用 [previewModel]：`previewModel` 只在有动态条件视图（`collectConditionViews` 非空，
     * 即链里有 eq/like 等条件调用）时才构造——无条件的联表链（如 MPJ 的 `selectAll + leftJoin + fill`）
     * `previewModel` 为 null，而 `result.sql` 是纯文本、面板无从判断 JOIN，导致这类链拿不到「JOIN 图」
     * 标签页。本字段把「是否画图」的判据与「是否走模型级重渲染」的判据解耦：有 JOIN 即非 null，与
     * `previewModel` 空否无关。默认 null，既有构造点零影响（向后兼容）。
     */
    val diagramStatement: SelectStatement? = null,
    /**
     * 参数历史缓存键的**稳定指纹种子**（注入拦截器子句之前的 SQL 文本）。
     *
     * 为什么不能直接用 [sql] 当指纹：历史键 = `表名:SHA-256(SQL 全文)`，而逻辑删除 / 多租户等
     * 拦截器模拟会在渲染前改写 SQL 文本（追加 `AND deleted = 0` / `AND tenant_id = ?`）——
     * 用户一开这些开关，键就变，此前回填好的参数全部召回 miss、退回占位符（且 500ms 防抖后
     * 新键还会以空值覆盖落库）。本字段承载「注入前」的语句文本，供面板算键时优先使用，
     * 使开关切换不再影响参数历史归属。null = 回落 [sql]（存量行为零变化）。
     */
    val historyFingerprintSeed: String? = null,
    val renderedDialect: Dialect? = null,
    /** 本次渲染瞬态证据快照，仅携带方言名/来源/候选 UUID；不保存 JDBC URL 或 PSI。 */
    val dialectDecision: DialectDecisionEngine.Decision? = null,
    /** R90：本次预览实际入库条目，recordHistory 在返回结果前设置；副本保留，未知为空。 */
    var historyEntryId: String = "",
    /** ShardingSphere 路由日志原文，只读附注，不参与 SQL 编辑、复制或执行。 */
    val actualSql: String? = null,
    /**
     * T-PresetMethodScope：参数预设/历史缓存键的**声明作用域**（[cn.song.mybatisplus.visualizer.psi.PreviewOriginScope.of]）。
     *
     * 空串 = 无方法上下文（日志/历史双击、纯文本预览等），键退回「表名 + SQL 指纹」旧格式。
     * 非空时键尾追加 `|@<scope>`，同一段 SQL 写在两个方法里不再共享预设与参数历史。
     * `var` 供 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 的 recordHistory 回写（同 [historyEntryId]）。
     */
    var originScopeKey: String = "",
    /**
     * T-PagesHql：源语言（默认 SQL = 未发生语言转换）。
     *
     * 仅 `<pages>` 的 HQL 转换链会置 [SourceLanguage.HQL]；面板 meta 行据此展示
     * 「来源：HQL（已转换）」，让「插件到底转没转」可核对（用户投诉的一半是「不知道它做了什么」）。
     */
    val sourceLanguage: SourceLanguage = SourceLanguage.SQL,
    /**
     * T-PagesHql：转换前的原文（仅 [sourceLanguage] 非 SQL 时非空）；面板「原文」只读页签展示。
     */
    val originalSql: String? = null,
    /**
     * T-PagesHql：转换降级清单（关联路径未映射 / 推断的 ON 条件 / 实体不可解析等）。
     * 空表 = 无降级。pages 链会把它压成一行并入 [warning]；其它 JPA 链路只携带不展示（惰性字段）。
     */
    val degradations: List<String> = emptyList(),
    /**
     * T-RuntimeSlotFill 券 A：本次预览里的**可回填运行期占位**（[RuntimeSlot]）。
     *
     * 每个元素对应 SQL 文本里一条带 id 的占位注释（`运行时值: … · rtN`），承载类别、表达式摘要、
     * 展开模板与回填语义；面板据此建输入行（券 C），回填时按 [RuntimeSlot.token] 定位并整体替换
     * 那段注释（券 B）。空表 = 无运行期占位，或没有可填的值（纯说明性注释放不进本表）。
     *
     * 默认空表，既有构造点零影响（向后兼容）。
     */
    val runtimeSlots: List<RuntimeSlot> = emptyList(),
    /**
     * T-Ibatis2Gap 券 A：iBatis 语句的参数/条件行清单（[cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModelBuilder]
     * 产物）。面板据此建条件开关行 / 参数值行 / iterate 值列表行 / `$x$` 风险行。
     * null = 非 iBatis 预览（MyBatis/Wrapper/JPA 链零感知）。
     */
    val ibatisParamModel: IbatisParamModel? = null,
    /**
     * T-Ibatis2Gap 券 A：模型级重渲染回调（渲染接缝选型 A——面板参数/勾选变化时调用它拿新结果，
     * 不把 `xmlModel` 抽成接口）。闭包由 `ui/IbatisXmlPreviewService` 用**同一 statement** 创建，
     * 装配 Map 后走既有 `SqlMapSqlRenderer.render(statement, params)`——渲染器与 MyBatis 链零改动。
     * null = 无模型级重渲染能力（既有构造点零影响）。
     */
    val rerender: ((PreviewRerenderInput) -> PreviewResult)? = null,
    /**
     * T-PreviewStmtTypeScope：本次判型命中的消费调用——语句类型为什么是 UPDATE/DELETE/COUNT/EXISTS 的来历。
     *
     * null = 该链上没有消费调用，按默认 SELECT 渲染。面板 meta 行据此显示「类型依据: `remove()` 第 120 行」，
     * 悬停列全部候选（[otherConsumptions]），用户不必猜「为什么查询链被判成 update」。
     */
    val decidingConsumption: WrapperConsumption? = null,
    /** T-PreviewStmtTypeScope：该 wrapper 上识别到的**全部**消费调用（D-2 作用域收窄前的候选集）。 */
    val otherConsumptions: List<WrapperConsumption> = emptyList(),
    /**
     * T-PreviewStmtTypeScope D-3：可切换的预览目标——该 wrapper 上出现过的消费档位（按分派优先级去重排序）。
     *
     * 长度 ≥ 2 表示「同一段代码在运行时可能产出多种语句」（典型：同一 wrapper 变量先交给
     * `update(entity, w)`、重新赋值后交给 `delete(w)`）。此时面板不替用户拍板：给出切换入口，
     * 默认落在判型依据命中的那一档（D-2），其余由用户按需切换。
     */
    val statementTypeOptions: List<ConsumptionKind> = emptyList(),
    /**
     * T-PreviewStmtTypeScope D-3：按指定档位重算本次预览（同一锚点、强制判型）。
     *
     * 只有 [statementTypeOptions] 长度 ≥ 2 时才挂（单档场景不持有闭包，也不留 PSI 引用）。
     * 闭包内做 PSI 读取，调用方必须经 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.previewAsync]
     * 在后台线程执行。返回 null = 该档位不可用（链/表不可解析）。
     */
    val statementTypeSwitch: ((ConsumptionKind) -> PreviewResult?)? = null,
) {
    val effectiveDialect: Dialect? get() = previewModel?.dialect ?: batchModel?.dialect ?: renderedDialect
}

/**
 * T-Ibatis2Gap 券 A：模型级重渲染的面板状态输入（渲染接缝 A 的回调实参）。
 *
 * 券 A 原案是 `((Map<String, String>) -> PreviewResult)?`；实现时条件勾选（iBatis 的开关 = Map 里
 * 放不放 key）与 iterate 值列表没法折进一个 `Map<String, String>`，故泛化为三段输入——仍是
 * model 层的**通用**纯数据（不依赖 ibatis/面板类型），`params` 键 = 参数名（含条件值输入），
 * `enabledConditionIds` 键 = 条件行 id，`collections` 键 = 集合属性名。默认值齐备，非 iBatis 链不构造。
 */
data class PreviewRerenderInput(
    val params: Map<String, String> = emptyMap(),
    val enabledConditionIds: Set<String> = emptySet(),
    val collections: Map<String, List<String>> = emptyMap(),
)
