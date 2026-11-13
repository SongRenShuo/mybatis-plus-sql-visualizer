package cn.song.mybatisplus.visualizer.model

/**
 * Wrapper 调用链模型（PSI 解析产物 → converter 输入）。
 *
 * 从源码中提取出"Wrapper 类型 + 实体类型 + 有序的方法调用列表"，
 * converter 层据此构建 [SqlStatement]。
 *
 * @param wrapperType     Wrapper 类型
 * @param entityClass     泛型实体类名（如 DeviceInfoEntity），可能为 null（无法解析时）
 * @param calls           按源码顺序的方法调用（wrapper.eq(...) 等）
 * @param constructorAlias 表别名构造（`new MPJLambdaWrapper<>(Class, "apply_info")` 第二参，清单 C4/P11）；null 表示默认
 * @param deleteConsumed  wrapper 作为实参传给删除类消费方法（`service.remove(wrapper)` / `mapper.delete(wrapper)`，
 *                        MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造）；true 时即使类型为查询类也应转 DELETE
 * @param countConsumed   wrapper 作为实参传给计数消费方法（`service.count(wrapper)` / `selectJoinCount(wrapper)`）；
 *                        true 时查询类 wrapper 转 `SELECT COUNT(*) FROM ...`
 * @param existsConsumed  链式终结点消费（`service.lambdaQuery()...exists()`，Q14-③，T-M15-01）；
 *                        true 时查询类链转 `SELECT EXISTS (子查询)`
     * @param updateEntityConsumed 实体更新消费（`service.update(entity, wrapper)` 双参，U07-②，T-M15-04）；
     *                        true 时用实体字段展开 SET 列（值占位）
     * @param pageLimit       分页消费解析产物（`service.page(Page, wrapper)` / `selectPage` / 链式 `page()`，
     *                        T-M29-02/03）；null 表示无分页消费或 Page 实参不可解析（不渲染 LIMIT）
     * @param pageSearchCount 分页 Page 实参的 searchCount 标志（R12 T-PaginationSim D3=A，MP 3.5.17
     *                        `Page()` 默认 true 反编译实证；`new Page<>(c, s, false)` → false 时
     *                        PaginationInnerInterceptor 不执行 COUNT → 预览不产出 COUNT 包裹；
     *                        标志不可静态判定时保守取 true）
     * @param decidingConsumption T-PreviewStmtTypeScope：本次判型命中的那次消费调用——上面四个标志由它**单点**决定；
     *                        null = 该链上没有消费，按默认 SELECT 渲染。面板据此显示「类型 ← 第 N 行的 xxx() 消费」。
     * @param allConsumptions T-PreviewStmtTypeScope：该 wrapper 上识别到的**全部**消费调用（D-2 作用域收窄前的候选集）。
     *                        长度 > 1 意味「同一个 wrapper 有多种消费写法」，面板在悬停里列出，用户不必猜。
     */
data class WrapperChain(
    val wrapperType: WrapperType,
    val entityClass: String?,
    val calls: List<WrapperCall>,
    val constructorAlias: String? = null,
    val deleteConsumed: Boolean = false,
    val countConsumed: Boolean = false,
    val existsConsumed: Boolean = false,
    val updateEntityConsumed: Boolean = false,
    val pageLimit: LimitClause? = null,
    val pageSearchCount: Boolean = true,
    val decidingConsumption: WrapperConsumption? = null,
    val allConsumptions: List<WrapperConsumption> = emptyList(),
)

/**
 * T-PreviewStmtTypeScope：一次被识别到的「消费调用」——wrapper 作为实参传给某个方法，方法名落在消费集合内
 * （见 [ConsumptionKind]），语句类型据此从默认 SELECT 改判。
 *
 * 存在的意义是把「为什么是 UPDATE/DELETE」从隐式判型变成可展示的事实：用户点图标时看的是某一条链，
 * 而判型依据可能来自同一个 wrapper 变量在**同方法另一条语句**上的消费。面板把「方法名 + 行号」摆出来，
 * 用户一眼就能对上（T-PreviewStmtTypeScope M1）。
 *
 * @param kind            判型档位（DELETE / COUNT / EXISTS / UPDATE_ENTITY / PAGE）
 * @param methodName      消费方法名（`service.remove(w)` 的 `remove`、`mapper.update(entity, w)` 的 `update` …）
 * @param referenceOffset wrapper 作为实参的那处引用在文件中的偏移（排序、「锚点之后第一个」判定用）
 * @param statementRange  该消费调用所在**语句**的偏移区间；[IntRange.EMPTY] = 不可得（拿不到语句祖先）
 * @param line            1-based 行号；<= 0 = 不可得（文档不可用）
 * @param fileName        消费调用所在文件名（wrapper 为字段时可跨文件）
 * @param detail          档位相关的补充事实：实体更新档位下是**实体实参的源码文本**（`update(patch, w)` 的
 *                        `patch`），供 SET 占位符逐列生成参数来源（`patch.order_no`）——否则整列 SET 共用
 *                         同一个来源，面板只建一行、填一个值就把所有列填成同一个数。
 */
data class WrapperConsumption(
    val kind: ConsumptionKind,
    val methodName: String,
    val referenceOffset: Int,
    val statementRange: IntRange = IntRange.EMPTY,
    val line: Int = 0,
    val fileName: String = "",
    val detail: String = "",
)

/** T-PreviewStmtTypeScope：消费档位——决定预览语句类型的那一档。 */
enum class ConsumptionKind {
    /** `remove` / `delete` / `deleteJoin` → `DELETE FROM`。 */
    DELETE,

    /** `count` / `selectCount` / `selectJoinCount` → `SELECT COUNT(*)`。 */
    COUNT,

    /** `exists`（链终结点 / 单参消费）→ `SELECT EXISTS (…)`。 */
    EXISTS,

    /** `update` / `updateJoin` / `updateJoinAndNull` / `saveOrUpdate` 双参 → 实体列展开的 `UPDATE`。 */
    UPDATE_ENTITY,

    /** 分页消费（`page` / `selectPage` 等）→ LIMIT 子句；不改语句类型，仅在证据清单里留痕。 */
    PAGE,
}

/**
 * Wrapper 上的一次方法调用。
 *
 * @param methodName 方法名（如 eq、select、orderByDesc）
 * @param args       实参（psi 层已解析为 [WrapperArg]）
 */
data class WrapperCall(
    val methodName: String,
    val args: List<WrapperArg>,
)

/**
 * Wrapper 方法实参（psi 层解析产物）。
 *
 * 区分四类：
 *  - [ColumnArg]：列引用实参（`Entity::getXxx` 方法引用或 MPJ 的字符串字段）
 *  - [ValueArg]：值实参（字面量 / 运行时变量 / 表达式）
 *  - [NestedArg]：嵌套调用链实参（`and(consumer)` / `and(wrapper)` 的 lambda 体或子 wrapper）
 *  - [OnConditionArg]：MPJ `on(col1, col2)` 两列比较实参（F-11 联表 ON 条件）
 */
sealed class WrapperArg {

    /** 列引用实参。 */
    data class ColumnArg(val column: ColumnRef) : WrapperArg()

    /** 值实参。 */
    data class ValueArg(val value: SqlValue) : WrapperArg()

    /** 嵌套调用链实参（and/or 的 lambda 体）。 */
    data class NestedArg(val chain: WrapperChain) : WrapperArg()

    /** MPJ selectAs 目标 VO 字段引用（`selectAs(Entity::getX, VO::getY)` 第二参）：别名 = VO 属性名（保留驼峰）。 */
    data class AliasArg(val propertyName: String, val returnType: String? = null) : WrapperArg()

    /** MPJ selectFunc 聚合函数实参（`selectFunc("COUNT(%s)", func -> func.accept(列).values(值), "alias")`）。 */
    data class FuncArg(
        val columns: List<ColumnRef>,
        val values: List<SqlValue>,
    ) : WrapperArg()

    /** MPJ join 的关联实体引用（`leftJoin(XxxEntity.class, ...)` 第一参）：携带实体解析的表信息（F-13 扩展）。 */
    data class EntityRefArg(val tableInfo: TableInfo) : WrapperArg()

    /** MPJ on(col1, col2) 两列比较（left 主表列，right 关联表列，别名由 converter 补充）。 */
    data class OnConditionArg(val left: ColumnRef, val right: ColumnRef) : WrapperArg()

    /**
     * allEq 的 Map 实参（T-M21-04，MP 官方 API）：
     * `allEq(Map.of(k1, v1, ...))` / `Map.ofEntries(...)` 调用静态展开的键值对
     * （String 键 → [ColumnRef]，columnName = 键）；非 Map.of 的 Map 变量 → 空 [entries]（converter 渲染可读占位）。
     *
     * @param entries     键（列名）→ 值（字面量/占位符）对
     * @param null2IsNull allEq(Map, null2IsNull) 语义：true 且值静态可判 null 时渲染 `col IS NULL`
     */
    data class MapArg(
        val entries: List<Pair<ColumnRef, SqlValue>>,
        val null2IsNull: Boolean,
    ) : WrapperArg()

    /**
     * selectFilter / select(Class, Predicate) 的列过滤谓词（T-M21-01，静态白/黑名单近似）：
     * lambda 体内方法调用实参的字符串字面量（`!t.getProperty().equals("xxx")` 的 "xxx"、
     * `t.getColumn().equals("col_name")` 等）→ 候选字段名；lambda 体含 `!` 否定 → 黑名单 [exclude]，
     * 否则白名单 [include]。两者均空 = 谓词无法静态分析（converter 回退全列）。
     */
    data class PredicateArg(
        val include: List<String>,
        val exclude: List<String>,
    ) : WrapperArg()
}

/**
 * 支持的 Wrapper 类型（F-01/09/10/11/12/17）。
 *
 * M1 仅实现 [LAMBDA_QUERY]；[NESTED] 用于 and/or 嵌套 lambda 的内部链标记。
 * T-M21-08：MPJ 官方 [UPDATE_JOIN]/[DELETE_JOIN]（UpdateJoinWrapper / DeleteJoinWrapper，
 * 1.4.5+ 联表更新/删除，原 P3-01 提前）——`UPDATE t LEFT JOIN ... ON ... SET ... WHERE ...` /
 * `DELETE t,t1 FROM ... LEFT JOIN ... WHERE ...` 形态，由 UpdateJoin/DeleteJoinConverter 转换。
 */
enum class WrapperType {
    LAMBDA_QUERY,
    LAMBDA_UPDATE,
    LAMBDA_DELETE,
    MPJ_LAMBDA,
    MPJ_QUERY,
    MPJ_LAMBDA_QUERY,
    QUERY,
    UPDATE,
    /** T-M21-08：UpdateJoinWrapper（MPJ 官方联表更新，1.4.5+）。 */
    UPDATE_JOIN,
    /** T-M21-08：DeleteJoinWrapper（MPJ 官方联表删除，1.4.5+）。 */
    DELETE_JOIN,
    NESTED,
    /**
     * XB（M44 其他 ORM 第一批）：xbatis（原 mybatis-mp）QueryChain/UpdateChain/DeleteChain 同构链。
     * 条件方法名与 MP lambda 高度重合（eq/ne/gt/gte/lt/lte/like/in/orderBy…），由
     * [cn.song.mybatisplus.visualizer.converter.XbatisConverter] 镜像 QueryWrapperParser 委派转换。
     */
    XBATIS,
    /**
     * EQ（M44）：Easy-Query proxy 风格（官方主推 sql-api-proxy APT 代理），
     * `easyEntityQuery.queryable(Class).where(lambda)` 语句块式条件——由
     * [cn.song.mybatisplus.visualizer.converter.EasyQueryConverter] 直解析 PSI 转换。
     */
    EQ_PROXY,
    /**
     * DS（M44）：MyBatis Dynamic SQL（org.mybatis.dynamic-sql 2.0.0 官方 DSL），
     * `SelectDSL.select(...).from(table).where(col, isEqualTo(...))` 手写 SqlTable 常量链——
     * 由 [cn.song.mybatisplus.visualizer.converter.MdsConverter] 直解析 PSI 转换。
     */
    MDS,
    /**
     * QD（M44）：Querydsl（JPAQueryFactory + Q 类表达式树），由
     * [cn.song.mybatisplus.visualizer.converter.QuerydslConverter] 表达式树 walker 转换。
     */
    QD,
}

