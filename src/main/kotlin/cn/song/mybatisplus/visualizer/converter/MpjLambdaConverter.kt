package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SqlFragment
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UnionClause
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.openapi.project.Project

/**
 * MPJLambdaWrapper 转换器（F-11）：联表 SELECT（清单 P01~P20 的 MPJLambdaWrapper 形态）。
 *
 * 支持：
 *  - `selectAs(Entity::getX, VO::getY)` / `selectAs("alias.col", VO::getY)` → SELECT 列（T-M7-01）
 *  - `selectAll(Class)`、`distinct`、`selectMax/selectCount/selectFunc`（T-M7-02/03）
 *  - `selectCollection`（子查询 JOIN）、`selectAssociation`（T-M7-04）
 *  - `leftJoin/innerJoin/rightJoin`：三参 `(Class, 关联表字段, 主表字段)`、四参带别名、
 *    `on(col1, col2)`、lambda ON `on -> on.eq(...)`（T-M7-05）
 *  - 联表扩展重载（T-M21-02）：4/5 参右别名 `leftJoin(Class, [别名,] 左列, "右别名", 右列)`、
 *    ext 函数 `leftJoin(Class, 左列, 右列, ext -> ext.eq(...))`、子表 MConsumer
 *    `leftJoin(Class, t -> t.setAlias("tt").selectAll()..., 左列, 右列)`（子查询 join）
 *  - `selectCollection` prefix 形态（T-M21-03）：`selectCollection("别名", Child.class, VO::getX[, map])`
 *  - `from(子查询主表)`（T-M21-07）、`around`/`setTableName`/`pageByMain`（T-M21-09 部分）
 *  - 表别名构造 `new MPJLambdaWrapper<>(Class, "alias")` → 主表别名可配置（C4/P11）
 *  - `eq` 等条件 / `orderByAsc` 等结构 → 委托 [QueryWrapperParser]（主表别名前缀）
 *
 * 已知简化（R7 待 MPJ 官方输出对照）：
 *  - 关联表别名默认 = 实体类名首字母小写（四参显式别名优先）
 *  - selectCollection 的 ON 复用同子表 leftJoin 的 on 条件；嵌套 collection 外键按约定推断
 *  - selectCollection free 形态（首参 VO 方法引用、无子表类）不产生子查询（结果映射标记，T-M21-03）
 *  - setTableName（动态表名函数）静态不可求值 → no-op（T-M21-09）
 */
object MpjLambdaConverter : WrapperConverter {

    /** MPJ 主表别名默认值（表别名构造未指定时）。 */
    private const val MAIN_ALIAS_DEFAULT = "t"

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.MPJ_LAMBDA)

    override fun convert(chain: WrapperChain, table: TableInfo, project: Project?): SqlStatement {
        // 主表别名：构造别名 > setAlias("x") 方法（官方 API，M15 增补）> 默认 t
        val mainAlias = chain.constructorAlias
            ?: chain.calls.firstOrNull { it.methodName == SET_ALIAS }
                ?.args?.firstOrNull()?.asValue()?.let { (it as? SqlValue.Literal)?.unquoted }
            ?: MAIN_ALIAS_DEFAULT
        val selectAsCalls = chain.calls.filter { it.methodName == SELECT_AS || it.methodName == SELECT_AS_CLASS || it.methodName == SELECT_SUB }
        val joinCalls = chain.calls.filter { it.methodName in JOIN_METHODS }
        // T-M21-03：selectCollection free 形态（首参 VO 方法引用 ColumnArg/AliasArg、无子表类）→
        // 结果映射标记不产生 SQL（子表不可知，文档化边界），从 collectionCalls 剔除（与 selectAssociation 同策略）
        val collectionCalls = chain.calls.filter { it.methodName == SELECT_COLLECTION }
            .filterNot { it.args.getOrNull(0) is WrapperArg.ColumnArg || it.args.getOrNull(0) is WrapperArg.AliasArg }
        val selectAllCalls = chain.calls.filter { it.methodName == SELECT_ALL }
        // T-MpjFill：fill 调用收集（MPJ 1.5.1+）——独立于其余管线，不介入主查询形态
        val fillCalls = chain.calls.filter { it.methodName == FILL_METHOD }

        // 其余调用（eq/select/orderBy/distinct/selectMax 等）委托 Query 解析，列自动带主表别名。
        // selectAssociation 不产生额外 SQL（leftJoin 已体现关联，仅作结果映射标记），从解析链中剔除。
        // T-M21-07/09：from（子查询主表）、around（rawAround）、setTableName/pageByMain（运行时语义
        // no-op）均从解析链剔除（QueryWrapperParser 分派表无对应方法，剔除避免误解析）。
        val restCalls = chain.calls.filterNot {
            it.methodName == SELECT_AS || it.methodName == SELECT_AS_CLASS || it.methodName == SELECT_SUB
                || it.methodName in JOIN_METHODS || it.methodName in UNION_METHODS
                || it.methodName == SELECT_ALL || it.methodName == SELECT_COLLECTION
                || it.methodName == SELECT_ASSOCIATION || it.methodName == SET_ALIAS
                || it.methodName == FROM_METHOD
                || it.methodName == AROUND_METHOD
                || it.methodName in NOOP_METHODS
        }

        // Build alias → table and class → alias mappings (needed for selectAs column attribution)
        val aliasToTable = buildAliasToTable(joinCalls)
        val classToAlias = aliasToTable.entries.associate { (alias, ti) -> ti.entityClassName to alias }

        // T-MpjColAlias：MPJ 联表列归属——lambda 列接收者是关联实体（如
        // select(StationInfoEntity::getStationName)）时，列按 join 解析出的关联别名归属
        // （st.station_name）而非主表别名。QueryWrapperParser 的 columnPrefix 无条件补主表
        // 别名（withTableAlias 仅空别名时补），不改解析器（单表/MPJ 共用语义不变），
        // 在委托前重写调用树 ColumnArg 的 tableAlias，覆盖 select/条件/orderBy 全部列入口。
        val attributedRest = restCalls.map { attributeJoinColumns(it, classToAlias, chain.entityClass) }
        val base = QueryWrapperParser(table, mainAlias)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, attributedRest))

        // selectAll(Class[, prefix][, exclude...])：主表全列（带主表别名前缀）+ 聚合列（selectSum 等，委托 base）
        // + selectAs/selectAsClass/selectSub 列追加；exclude 排除字段（官方 API）、prefix 列前缀（官方 API）
        val selectAsColumns = selectAsCalls.flatMap { call ->
            when (call.methodName) {
                SELECT_AS_CLASS -> parseSelectAsClass(call, mainAlias)
                SELECT_SUB -> parseSelectSub(call)
                else -> listOfNotNull(parseSelectAs(call, mainAlias, classToAlias))
            }
        }
        val selectColumns = if (selectAllCalls.isNotEmpty()) {
            selectAllCalls.flatMap { parseSelectAll(it, mainAlias, table) } + base.selectColumns + selectAsColumns
        } else {
            base.selectColumns + selectAsColumns
        }
        // join + selectCollection 子查询 JOIN（复用同子表 leftJoin 的 ON）；按源码调用顺序合并。
        // T-M15-06：① 此前 join 全在 collection 前，与源码顺序不符；
        //           ② selectCollection 子查询已替代同子表普通 join（P06 两写并存），普通 join 不重复渲染
        // T-1800（Bug2 修复）：去重比较改为类名直接比较（不再比较推断表名 vs 真实表名，避免地址_d_o ≠ user_address 不匹配）。
        val collectionChildClasses = collectionCalls.mapNotNull { collectionChildClassOf(it) }
        val joins = mutableListOf<JoinClause>()
        for (call in chain.calls) {
            when (call.methodName) {
                in JOIN_METHODS -> {
                    // T-1800：先按类名比较去重（可靠），再 fallback 到表名比较（兼容旧逻辑）
                    if (collectionChildClasses.any { cls -> joinEntityClassName(call) == cls }) continue
                    val join = parseJoin(call, mainAlias, project, classToAlias) ?: continue
                    joins += join
                }
                SELECT_COLLECTION -> parseCollection(call, joinCalls, mainAlias, project, classToAlias)?.let { joins += it }
            }
        }

        // UNION / UNION ALL 子查询（官方 API，M15 增补）：渲染子查询后追加
        val unions = chain.calls
            .filter { it.methodName in UNION_METHODS }
            .mapNotNull { parseUnion(it) }

        // T-M21-07：from(子查询主表)（官方 API）→ 主表替换为 (子查询) 外层别名
        val fromSubQuery = chain.calls.firstOrNull { it.methodName == FROM_METHOD }
            ?.let { parseFrom(it, table, mainAlias) }

        // T-M21-09：around(firstSql, lastSql)（MPJ 1.4.11+）→ firstSql 并入 SQL 起始、lastSql 渲染于
        // ORDER BY 后、LIMIT 前（rawAround）
        val around = chain.calls.firstOrNull { it.methodName == AROUND_METHOD }?.let { parseAround(it) }

        // T-MpjFill：fill 调用解析（MPJ 1.5.1+）→ FillClause 列表（按调用顺序）；解析失败返回 null 不崩溃
        // aliasToTable already built above for selectAs attribution
        val fillClauses = fillCalls.mapNotNull { parseFill(it, aliasToTable) }.ifEmpty { null }

        return base.copy(
            tableAlias = mainAlias,
            selectColumns = selectColumns,
            joins = joins,
            unions = unions,
            fromSubQuery = fromSubQuery,
            rawAround = around?.last,
            rawAroundParams = around?.lastParams.orEmpty(),
            rawFirst = around?.first?.let { listOfNotNull(base.rawFirst, it).joinToString(" ") } ?: base.rawFirst,
            // T-UpdateSetSqlConcat：first 片段合并后来源同序拼接（base 的在前）
            rawFirstParams = if (around?.first != null) base.rawFirstParams + around.firstParams else base.rawFirstParams,
            fillClauses = fillClauses,
        )
    }

    /**
     * 解析 union / unionAll（官方 API）：`union(SubClass.class, w -> w.selectAll().eq(...))`
     * → `UNION (SELECT ... FROM sub_entity WHERE ...)`。子查询独立解析（不继承主表别名），compact 单行渲染。
     */
    private fun parseUnion(call: WrapperCall): UnionClause? {
        val subTable = (call.args.getOrNull(0) as? WrapperArg.EntityRefArg)?.tableInfo ?: return null
        val nested = call.args.getOrNull(1) as? WrapperArg.NestedArg ?: return null
        val subStatement = QueryWrapperParser(subTable).parse(nested.chain)
        val keyword = if (call.methodName == "unionAll") "UNION ALL" else "UNION"
        // T-UpdateSetSqlConcat：子语句的占位符来源随子句一起带出（渲染点按序登记，否则主语句后面的参数错位）
        val generated = SqlGenerator.generateWithParams(subStatement, compact = true)
        return UnionClause(keyword, generated.sql, generated.unresolvedParams)
    }

    /**
     * T-M21-07：from(子查询主表)（MPJ 官方 API，JoinAbstractLambdaWrapper.from(MFunction)）：
     * `from(w -> w.setAlias("tb").selectAll().ge(User::getId, 0).last("LIMIT 10"))`
     * → 主表替换为子查询，渲染 `FROM (SELECT tb.id, ... FROM user tb WHERE tb.id >= 0 LIMIT 10) t`
     * （外层别名 = 主表别名 [outerAlias]）。
     *
     * 子查询链经 [QueryWrapperParser] 解析（子查询主表别名取链内 setAlias 值，无则 "tb"）：
     * setAlias 不在 parser 分派表（else 忽略），但子查询主表别名由 statement.tableAlias 输出——
     * 解析后 copy(tableAlias) 注入，并带子别名前缀解析列与条件（对齐官方 `WHERE tb.id >= 0`）。
     *
     * @return Pair(子查询 SQL, 外层别名)——SelectStatement.fromSubQuery
     */
    private fun parseFrom(call: WrapperCall, table: TableInfo, outerAlias: String): Pair<String, String>? {
        val nested = call.args.getOrNull(0) as? WrapperArg.NestedArg ?: return null
        val subAlias = nested.chain.calls.firstOrNull { it.methodName == SET_ALIAS }
            ?.args?.firstOrNull()?.asValue()?.let { (it as? SqlValue.Literal)?.unquoted }
            ?: "tb"
        val subStatement = QueryWrapperParser(table, subAlias).parse(nested.chain)
            .copy(tableAlias = subAlias)
        return SqlGenerator.generate(subStatement, compact = true) to outerAlias
    }

    /**
     * T-M21-09：around 解析（MPJ 1.4.11+，Join.around(firstSql, lastSql)——firstSql 拼接 SQL 起始
     * （等价 first(...)），lastSql 拼接语句末尾；MP 内部 sqlSegments 中 LAST 段位于 ORDER BY 之后、
     * 分页 LIMIT 之前，本实现据此将 lastSql 渲染于 ORDER BY 后、LIMIT 前（rawAround））。
     *
     * 形态：
     *  - 双字符串字面量 `around("first", "last")`（官方签名）：first=首参（并入 SQL 起始 rawFirst）、
     *    second=第二参（rawAround）；
     *  - 模板形态 `around("sql {0}", v, ...)`（任务规约兼容）：模板 + `{i}` 绑定参数替换
     *    （复用 [renderJoinSql] 思路），first=null、second=替换结果。
     *
     * @return [AroundFragment]（first 并入 rawFirst、last 为 rawAround，各带占位符来源）
     */
    private fun parseAround(call: WrapperCall): AroundFragment? {
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment；来源随片段带出（渲染点按序登记）
        val first = call.args.getOrNull(0).asSqlFragment() ?: return null
        // 双字符串字面量形态：模板不含 {i} 占位、双参且第二参为 SQL 片段
        if (!first.text.contains("{") && call.args.size == 2) {
            call.args.getOrNull(1).asSqlFragment()?.let {
                return AroundFragment(first.text, first.params, it.text, it.params)
            }
        }
        // 模板形态：模板 + {i} 绑定参数替换
        val rendered = renderJoinSql(first, call.args.drop(1))
        return AroundFragment(null, emptyList(), rendered.text, rendered.params)
    }

    /** `around` 解析产物（T-UpdateSetSqlConcat：文本与占位符来源成对携带）。 */
    private data class AroundFragment(
        val first: String?,
        val firstParams: List<String>,
        val last: String,
        val lastParams: List<String>,
    )

    /**
     * T-MpjFill：解析 fill 调用 → FillClause（MPJ 1.5.1+）。
     *
     * 6 种形态分派（按参数量与末参类型）：
     *  1. fill(field, tagField) — 2 参：主表字段 lambda + 关联表字段 lambda
     *  2. fill(field, tagField, mapperTagField) — 3 参：第 3 参为字符串列名（关联表字段名）
     *  3. fill(field, tagField, mapperTagField, limit) — 4 参：第 4 参整数 LIMIT
     *  4. fill(field, tagField, mapperTagField, condition) — 4 参：第 4 参布尔条件
     *  5. fill(field, tagField, mapperTagField, data) — 4 参：第 4 参数据源对象
     *  6. fill(field, tagField, mapperTagField, limit, data) — 5 参：LIMIT + 数据源
     *
     * 关联表推断：第 2 参为 lambda 字段引用（ColumnArg）→ 取其 tableAlias 作为关联表别名，
     * 从 [aliasToTable] 映射查找 TableInfo（由 join 调用的 EntityRefArg 构建）；
     * 第 2 参为字符串值时无实体上下文 → 返回 null（容错，文档化边界）。
     *
     * @param call         fill 调用
     * @param aliasToTable 关联表别名 → TableInfo 映射（来自 join 调用的 EntityRefArg）
     * @return FillClause；解析失败返回 null（容错，不中断主查询渲染）
     */
    private fun parseFill(call: WrapperCall, aliasToTable: Map<String, TableInfo>): FillClause? {
        val args = call.args
        if (args.size < 2) return null

        // 第 1 参：主表字段（MFunction<T>）→ 关联键来源列（仅用于语义理解，不参与 SQL 生成）
        val fieldColumn = (args.getOrNull(0) as? WrapperArg.ColumnArg)?.column ?: return null

        // 第 2 参：关联表字段（SFunction<R, Object>）→ 推断关联表 + 关联键
        val tagFieldArg = args.getOrNull(1)
        val childTable: TableInfo
        val childAlias: String
        val tagField: String
        when (tagFieldArg) {
            is WrapperArg.ColumnArg -> {
                // lambda 字段引用 → 取列的 tableAlias 作为关联表别名，从映射查找 TableInfo
                val alias = tagFieldArg.column.tableAlias ?: return null
                childTable = aliasToTable[alias] ?: return null
                childAlias = alias
                tagField = tagFieldArg.column.columnName
            }
            is WrapperArg.ValueArg -> {
                // 字符串列名形态（2/3/4/5/6）→ 无实体上下文，无法推断关联表（文档化边界）
                return null
            }
            else -> return null
        }

        // 可选参数解析：limit / condition / data（第 3 参在形态 2~6 为 mapperTagField 字符串，此处跳过）
        var limit: Int? = null
        var condition: Boolean? = null
        var data: SqlValue? = null

        when (args.size) {
            2 -> { /* 形态 1：无可选参数 */ }
            3 -> { /* 形态 2：第 3 参为 mapperTagField 字符串列名（仅覆盖 tagField，无需处理） */ }
            4 -> {
                // 形态 3/4/5：第 4 参为 limit(整型) / condition(布尔) / data(值)
                val arg3 = args.getOrNull(3)
                val value3 = (arg3 as? WrapperArg.ValueArg)?.value
                when {
                    value3 is SqlValue.Literal && value3.unquoted.toIntOrNull() != null ->
                        limit = value3.unquoted.toIntOrNull()
                    value3 is SqlValue.Literal && (value3.unquoted == "true" || value3.unquoted == "false") ->
                        condition = value3.unquoted.toBooleanStrictOrNull()
                    else -> data = arg3.asValue()
                }
            }
            5 -> {
                // 形态 6：第 4 参 limit + 第 5 参 data
                val value3 = (args.getOrNull(3) as? WrapperArg.ValueArg)?.value
                if (value3 is SqlValue.Literal && value3.unquoted.toIntOrNull() != null) {
                    limit = value3.unquoted.toIntOrNull()
                }
                data = args.getOrNull(4).asValue()
            }
        }

        // condition = false → fill 被条件禁用，不生成 FillClause
        if (condition == false) return null

        // 关联表 SELECT 列：默认全列（带关联表别名前缀）
        val selectColumns = childTable.columns.map { col ->
            ColumnRef(tableAlias = childAlias, columnName = col.columnName)
        }

        return FillClause(
            childTable = childTable,
            childAlias = childAlias,
            selectColumns = selectColumns,
            tagField = tagField,
            limit = limit,
            condition = condition,
            data = data,
        )
    }

    /**
     * T-MpjFill：构建关联表别名 → TableInfo 映射。
     *
     * 从 join 调用的 EntityRefArg（psi 解析实体）提取别名与 TableInfo 对应关系，
     * 供 [parseFill] 按 tagField 的 tableAlias 查找关联表。
     * 四参显式别名（`leftJoin(Class, "st", ...)`）以显式别名为准，否则取实体类名首字母小写。
     *
     * @param joinCalls join 调用列表
     * @return 别名 → TableInfo 映射
     */
    private fun buildAliasToTable(joinCalls: List<WrapperCall>): Map<String, TableInfo> {
        val map = mutableMapOf<String, TableInfo>()
        for (call in joinCalls) {
            val tableInfo = (call.args.getOrNull(0) as? WrapperArg.EntityRefArg)?.tableInfo ?: continue
            // 别名：四参显式别名（第 2 参为 ValueArg）> 实体类名首字母小写
            val alias = (call.args.getOrNull(1) as? WrapperArg.ValueArg)?.value.let { value ->
                (value as? SqlValue.Literal)?.unquoted?.takeIf { it.isNotEmpty() }
            } ?: tableInfo.entityClassName.replaceFirstChar { it.lowercaseChar() }
            map[alias] = tableInfo
        }
        return map
    }

    /**
     * T-MpjColAlias：委托 [QueryWrapperParser] 前重写调用树中的列别名归属——lambda 列接收者
     * 实体类命中 join 关联映射（[classToAlias]）且非主实体类时，把 ColumnArg 列的 tableAlias
     * 预置为 join 别名；解析器 columnPrefix（[cn.song.mybatisplus.visualizer.model.withTableAlias]
     * 仅空别名补前缀）保留既有归属，select/条件/orderBy/聚合全部列入口一并修正。
     *
     * 根因场景：`select(StationInfoEntity::getStationName)` 原被无条件补主表别名 →
     * `t.station_name`（car_device_info 无此列，执行报 1054 Unknown column）。
     * NestedArg 递归处理（and/or 嵌套 lambda 体内的关联列）；字符串列（ValueArg）已含
     * 别名原样，不触碰。
     */
    private fun attributeJoinColumns(
        call: WrapperCall,
        classToAlias: Map<String, String>,
        mainEntityClass: String?,
    ): WrapperCall {
        if (classToAlias.isEmpty()) return call
        var changed = false
        val newArgs = call.args.map { arg ->
            when (arg) {
                is WrapperArg.ColumnArg -> {
                    val attributed = attributeColumn(arg.column, classToAlias, mainEntityClass)
                    if (attributed === arg.column) arg else { changed = true; WrapperArg.ColumnArg(attributed) }
                }
                is WrapperArg.NestedArg -> {
                    val newChain = arg.chain.copy(
                        calls = arg.chain.calls.map { attributeJoinColumns(it, classToAlias, mainEntityClass) },
                    )
                    if (newChain.calls == arg.chain.calls) arg else { changed = true; WrapperArg.NestedArg(newChain) }
                }
                is WrapperArg.FuncArg -> {
                    val cols = arg.columns.map { attributeColumn(it, classToAlias, mainEntityClass) }
                    if (cols == arg.columns) arg else { changed = true; arg.copy(columns = cols) }
                }
                else -> arg
            }
        }
        return if (changed) call.copy(args = newArgs) else call
    }

    /** 单列归属：仅无别名 lambda 列、接收者实体类 ≠ 主实体类、且命中 join 映射时改写（self-join 主列保主表）。 */
    private fun attributeColumn(column: ColumnRef, classToAlias: Map<String, String>, mainEntityClass: String?): ColumnRef {
        val receiver = column.entityClass ?: return column
        if (column.tableAlias != null) return column
        if (receiver == mainEntityClass) return column
        val alias = classToAlias[receiver] ?: return column
        return column.copy(tableAlias = alias)
    }

    /**
     * 解析 selectSub（官方 API）：`selectSub(SubClass.class, w -> w.select(...).eq(...), alias)`
     * → `(SELECT ... FROM sub_entity WHERE ...) AS alias` 子查询列。
     */
    private fun parseSelectSub(call: WrapperCall): List<ColumnRef> {
        val subTable = (call.args.getOrNull(0) as? WrapperArg.EntityRefArg)?.tableInfo ?: return emptyList()
        val nested = call.args.getOrNull(1) as? WrapperArg.NestedArg ?: return emptyList()
        val alias = when (val arg = call.args.getOrNull(2)) {
            is WrapperArg.AliasArg -> arg.propertyName
            is WrapperArg.ColumnArg -> arg.column.columnName
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted
            else -> null
        } ?: return emptyList()
        val subStatement = QueryWrapperParser(subTable).parse(nested.chain)
        val subSql = SqlGenerator.generate(subStatement, compact = true)
        return listOf(ColumnRef(columnName = subSql, rawExpr = "($subSql)", alias = alias))
    }

    /**
     * 解析 selectAs → 带别名的列（清单 P01/P02/P04/P11）。
     *
     * 支持三种实参形态：
     *  - `selectAs(Entity::getX, VO::getY)`：第二参方法引用 → 别名 = VO 属性名（getter 反解）
     *  - `selectAs(Entity::getX, "alias")`：第二参字符串 → 别名原样
     *  - `selectAs("alias.col", VO::getY)`：第一参字符串列（已含表别名限定）→ 不补主表前缀
     */
    private fun parseSelectAs(call: WrapperCall, mainAlias: String, classToAlias: Map<String, String> = emptyMap()): ColumnRef? {
        val column = when (val arg = call.args.getOrNull(0)) {
            is WrapperArg.ColumnArg -> arg.column
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
            else -> null
        } ?: return null

        // 别名：第二参可为 VO::getY 方法引用（AliasArg 保留属性名 + 返回类型）、lambda 列或字符串字面量
        var colType: String? = null
        val alias = when (val arg = call.args.getOrNull(1)) {
            is WrapperArg.AliasArg -> { colType = arg.returnType; arg.propertyName }
            is WrapperArg.ColumnArg -> arg.column.columnName
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted
            else -> null
        } ?: return null

        // 第一参为已带表别名限定的字符串列（含 '.'）时原样渲染，否则根据 entityClass 推断
        val tableAlias = if (call.args.getOrNull(0) is WrapperArg.ValueArg && column.columnName.contains('.')) {
            null
        } else if (column.entityClass != null && classToAlias.containsKey(column.entityClass)) {
            classToAlias[column.entityClass]!!
        } else {
            mainAlias
        }
        return ColumnRef(tableAlias = tableAlias, columnName = column.columnName, alias = alias, type = colType)
    }

    /**
     * 解析 join → 联表子句（清单 P01/P05/P06/P11/P13/P17/P18，官方 API 全覆盖）。
     *
     * 支持六种 ON 形态：
     *  1. 三参 `leftJoin(Class, 关联表字段, 主表字段)` → ON alias.左 = mainAlias.右
     *  2. 四参带别名 `leftJoin(Class, "rel", 关联表字段, 主表字段)` → 别名显式指定
     *  3. `leftJoin(Class, on(col1, col2))` → ON mainAlias.col1 = alias.col2
     *  4. lambda ON `leftJoin(Class, on -> on.eq(...).eq(...))` → on 内多条件
     *  5. 字符串 SQL join `leftJoin("address t1 on t1.user_id = t.id"[, args])` → 原样透传（{i} 参数替换）
     *  6. 自定义关键字 `join("FULL JOIN", condition, "sql"[, args])` → 关键字 + 原样透传
     *
     * ⚠️ T-A47（2026-10-12）：上面写的「关联表字段 / 主表字段」只是**常见写法**，不是 MPJ 语义——
     * MPJ 对 ON 两侧的列**各自按列自己的实体类**定别名。凡能识别出去重实体类的列，别名一律经
     * [onColumnAlias] 解析（查不到才回落下面的默认值）。显式右别名（形态 2 的 `"rel"`）不受影响。
     *
     * 关联表信息：优先 psi 层解析的 [WrapperArg.EntityRefArg]（F-13 注解），否则按类名 snake_case 兜底。
     * T-M20-05：EntityRefArg 形态额外携带关联表逻辑删除列（@TableLogic，见 [JoinClause.logicDeleteColumn]），
     * 供 SqlPreviewService 注入副表 deleted 条件；类名兜底 / 字符串 join 无 TableInfo → null（不注入）。
     */
    /**
     * T-A47：ON 条件里列的表别名——**优先按列自己的实体类**在链内别名表里查，查不到再回落 [fallback]。
     *
     * 对齐 MPJ 真实行为（1.5.8 `JoinAbstractLambdaWrapper.columnToString`）：先
     * `LambdaUtils.getEntityClass(column)`，再按该类查前缀（`tableList.getPrefix(index, clazz, false)`）——
     * **与「是不是主表」无关**。
     *
     * 修复前的写法把 ON 右侧列一律按主表别名渲染，于是
     * `rightJoin(AreaDO.class, AreaDO::getId, AddressDO::getAreaId)` 渲染成 `areaDO.id = t.area_id`，
     * 而 MPJ 真实执行的是 `areaDO.id = addressDO.area_id`——预览 SQL 与执行 SQL 不一致，该句还直接报
     * `[42S22][1054] Unknown column 't.area_id' in 'on clause'`（2026-10-12 录制 A13 时实测）。
     *
     * 兼容性：[ColumnRef.entityClass] 为空（PSI 未解析出接收者，例如手工构造的链）时逐字节回落到
     * 修复前行为；实体类就是主实体（不在 join 别名表内）时同样回落 [fallback]，故常见写法结果不变。
     * 该值可能是简单类名或全限定名，两种形状都查一遍。
     */
    private fun onColumnAlias(column: ColumnRef, classToAlias: Map<String, String>, fallback: String): String {
        val raw = column.entityClass?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        return classToAlias[raw] ?: classToAlias[raw.substringAfterLast('.')] ?: fallback
    }

    private fun parseJoin(
        call: WrapperCall,
        mainAlias: String,
        project: Project?,
        classToAlias: Map<String, String>,
    ): JoinClause? {
        val type = when (call.methodName) {
            "leftJoin" -> JoinType.LEFT
            "innerJoin" -> JoinType.INNER
            "rightJoin" -> JoinType.RIGHT
            "fullJoin" -> JoinType.FULL
            else -> JoinType.INNER // join(自定义关键字)
        }
        val args = call.args

        // 6. 自定义关键字 join：join("FULL JOIN", condition, "address t1 on ..."[, args])
        if (call.methodName == "join") {
            val keyword = (args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return null
            // T-UpdateSetSqlConcat：片段位取 asSqlFragment 并带来源（join 里的 `?` 须按序登记）
            val joinSql = args.getOrNull(2).asSqlFragment() ?: return null
            val rendered = renderJoinSql(joinSql, args.drop(3))
            return JoinClause(
                type, "", "", emptyList(),
                rawSql = rendered.text, rawParams = rendered.params, customKeyword = keyword,
            )
        }

        // 5. 字符串 SQL join：leftJoin("address t1 on t1.user_id = t.id"[, args]) /
        //    leftJoin(condition, "sql"[, args])——首参为含 SQL 结构（空格/括号/{i} 占位）的 SQL 片段；
        //    纯类名（CamelCase，无空格）走下方类名降级（converter 测试手工构造形态）
        val first = args.getOrNull(0)
        if (first is WrapperArg.ValueArg) {
            // T-UpdateSetSqlConcat：字符串 join 片段位取 asSqlFragment——首参含运行期段时
            // 静态结构（空格/括号/{i}）仍在，不再整段丢弃
            val text = first.asSqlFragment()?.text ?: return null
            if (!text.trimEnd().endsWith(".class") &&
                (text.contains(' ') || text.contains('(') || text.contains('{'))
            ) {
                val startIndex = if (args.size >= 2 && isFlagArgument(args[0])) 1 else 0
                val sql = args.getOrNull(startIndex).asSqlFragment() ?: return null
                val rendered = renderJoinSql(sql, args.drop(startIndex + 1))
                return JoinClause(
                    type, "", "", emptyList(),
                    rawSql = rendered.text, rawParams = rendered.params,
                )
            }
        }

        val (tableInfo, className) = when (val arg = call.args.getOrNull(0)) {
            is WrapperArg.EntityRefArg -> arg.tableInfo to arg.tableInfo.entityClassName
            is WrapperArg.ValueArg -> {
                val cn = (arg.value as? SqlValue.Literal)?.raw?.removeSuffix(".class")?.trim() ?: return null
                null to cn
            }
            else -> return null
        }
        if (className.isEmpty()) return null

        // T-M19.4：主表兜底推断走统一入口（策略 + 修饰链，全局语义）
        // T-FS-04：类名兜底推断显式传调用方 project——多项目同开且项目级表配置不同时，
        // 关联表名修饰不再受「首个打开项目」影响（null 时行为与改动前逐字节一致）
        var tableName = tableInfo?.tableName ?: EntityResolver.inferDecoratedTableName(className, project)
        var alias = className.replaceFirstChar { it.lowercaseChar() }
        val onConditions = mutableListOf<Condition>()
        // T-M21-02：子表 MConsumer 子查询形态不携带逻辑删除列（与 selectCollection 子查询族一致——
        // 子查询内部已含自身过滤条件，外层不注入副表 deleted）
        var logicDelete = tableInfo?.logicDeleteColumn

        val rest = call.args.drop(1)
        val firstRest = rest.getOrNull(0)
        when {
            // ================= T-M21-02：MPJ 官方重载扩展（QueryJoin.java 每关键字 18 重载中的新形状） =================
            // 按实参形状优先判定（先于既有形态分派）；不匹配回落既有逻辑。
            // 5 参右别名：leftJoin(Class, "别名", 左列, "右别名", 右列) → ON 别名.左列 = 右别名.右列
            // （右别名作用于右列所在表——主表，通常 "t"）
            args.size == 5 && args[1] is WrapperArg.ValueArg && args[2] is WrapperArg.ColumnArg &&
                args[3] is WrapperArg.ValueArg && args[4] is WrapperArg.ColumnArg -> {
                alias = (args[1].asValue() as? SqlValue.Literal)?.unquoted ?: alias
                onConditions += Condition.ColumnComparison(
                    left = (args[2] as WrapperArg.ColumnArg).column.withTableAlias(alias),
                    right = (args[4] as WrapperArg.ColumnArg).column.withTableAlias(
                        (args[3].asValue() as? SqlValue.Literal)?.unquoted ?: mainAlias,
                    ),
                )
            }
            // 4 参右别名：leftJoin(Class, 左列, "右别名", 右列) → ON 关联别名.左列 = 右别名.右列
            // （与既有「四参别名」[EntityRefArg, ValueArg, ColumnArg, ColumnArg] 按第 2 参类型区分：
            //  ColumnArg=右别名形态，ValueArg=显式别名形态）
            args.size == 4 && args[1] is WrapperArg.ColumnArg && args[2] is WrapperArg.ValueArg &&
                args[3] is WrapperArg.ColumnArg -> {
                onConditions += Condition.ColumnComparison(
                    left = (args[1] as WrapperArg.ColumnArg).column.withTableAlias(alias),
                    right = (args[3] as WrapperArg.ColumnArg).column.withTableAlias(
                        (args[2].asValue() as? SqlValue.Literal)?.unquoted ?: mainAlias,
                    ),
                )
            }
            // ext 函数：leftJoin(Class, 左列, 右列, ext -> ext.eq(...)) → ON 关联别名.左列 = 主别名.右列
            // + ext 内条件追加到 ON 条件组（复用 parseOnLambda）
            args.size == 4 && args[1] is WrapperArg.ColumnArg && args[2] is WrapperArg.ColumnArg &&
                args[3] is WrapperArg.NestedArg -> {
                onConditions += Condition.ColumnComparison(
                    left = (args[1] as WrapperArg.ColumnArg).column
                        .withTableAlias(onColumnAlias((args[1] as WrapperArg.ColumnArg).column, classToAlias, alias)),
                    right = (args[2] as WrapperArg.ColumnArg).column
                        .withTableAlias(onColumnAlias((args[2] as WrapperArg.ColumnArg).column, classToAlias, mainAlias)),
                )
                onConditions += parseOnLambda((args[3] as WrapperArg.NestedArg).chain, alias, mainAlias, classToAlias)
            }
            // 别名 + ext：leftJoin(Class, "别名", 左列, 右列, ext -> ...)（QueryJoin 重载族补齐）
            args.size == 5 && args[1] is WrapperArg.ValueArg && args[2] is WrapperArg.ColumnArg &&
                args[3] is WrapperArg.ColumnArg && args[4] is WrapperArg.NestedArg -> {
                alias = (args[1].asValue() as? SqlValue.Literal)?.unquoted ?: alias
                onConditions += Condition.ColumnComparison(
                    left = (args[2] as WrapperArg.ColumnArg).column
                        .withTableAlias(onColumnAlias((args[2] as WrapperArg.ColumnArg).column, classToAlias, alias)),
                    right = (args[3] as WrapperArg.ColumnArg).column
                        .withTableAlias(onColumnAlias((args[3] as WrapperArg.ColumnArg).column, classToAlias, mainAlias)),
                )
                onConditions += parseOnLambda((args[4] as WrapperArg.NestedArg).chain, alias, mainAlias, classToAlias)
            }
            // 子表 MConsumer：leftJoin(Class, t -> t.setAlias("tt").selectAll().ge(...), 左列, 右列)
            // → LEFT JOIN (SELECT ... FROM 子表 tt WHERE ...) 子别名 ON 子别名.左列 = 主别名.右列
            // 子查询用 QueryWrapperParser(子表 TableInfo) 解析嵌套链 compact 单行渲染；
            // 子别名取 lambda 内 setAlias 值，无则类名首字母小写
            args.size == 4 && args[1] is WrapperArg.NestedArg && args[2] is WrapperArg.ColumnArg &&
                args[3] is WrapperArg.ColumnArg -> {
                val childTable = tableInfo ?: return null // 子查询渲染需子表 TableInfo（列展开/条件前缀）
                val subChain = (args[1] as WrapperArg.NestedArg).chain
                val subAlias = subChain.calls.firstOrNull { it.methodName == SET_ALIAS }
                    ?.args?.firstOrNull()?.asValue()?.let { (it as? SqlValue.Literal)?.unquoted }
                    ?: className.replaceFirstChar { it.lowercaseChar() }
                val subStatement = QueryWrapperParser(childTable, subAlias).parse(subChain)
                    .copy(tableAlias = subAlias)
                tableName = "(" + SqlGenerator.generate(subStatement, compact = true) + ")"
                alias = subAlias
                logicDelete = null
                onConditions += Condition.ColumnComparison(
                    left = (args[2] as WrapperArg.ColumnArg).column.withTableAlias(subAlias),
                    right = (args[3] as WrapperArg.ColumnArg).column
                        .withTableAlias(onColumnAlias((args[3] as WrapperArg.ColumnArg).column, classToAlias, mainAlias)),
                )
            }
            // ================= 既有形态（保持原分派） =================
            // 4. lambda ON：leftJoin(Class, on -> on.eq(...).eq(...))
            firstRest is WrapperArg.NestedArg ->
                onConditions += parseOnLambda(firstRest.chain, alias, mainAlias, classToAlias)

            else -> {
                var index = 0
                // 2. 四参带别名：leftJoin(Class, "rel", 关联表字段, 主表字段)
                if (firstRest is WrapperArg.ValueArg) {
                    val aliasText = (firstRest.value as? SqlValue.Literal)?.unquoted
                    if (!aliasText.isNullOrEmpty()) {
                        alias = aliasText
                        index = 1
                    }
                }
                val aliasNext = rest.getOrNull(index)
                when {
                    // 4b. 四参别名 + lambda ON：leftJoin(Class, "t1", on -> ...)（2026-08-13 用户实测）
                    aliasNext is WrapperArg.NestedArg ->
                        onConditions += parseOnLambda(aliasNext.chain, alias, mainAlias, classToAlias)
                    // 3. on(col1, col2)：left 主表列、right 关联表列
                    aliasNext is WrapperArg.OnConditionArg ->
                        onConditions += rest.drop(index).mapNotNull { arg ->
                            (arg as? WrapperArg.OnConditionArg)?.let { oc ->
                                Condition.ColumnComparison(
                                    left = oc.left.withTableAlias(onColumnAlias(oc.left, classToAlias, mainAlias)),
                                    right = oc.right.withTableAlias(onColumnAlias(oc.right, classToAlias, alias)),
                                )
                            }
                        }
                    else -> {
                        // 1. 三参：leftJoin(Class, 关联表字段, 主表字段) → ON alias.左 = mainAlias.右
                        val left = rest.getOrNull(index) as? WrapperArg.ColumnArg
                        val right = rest.getOrNull(index + 1) as? WrapperArg.ColumnArg
                        if (left != null && right != null) {
                            onConditions += Condition.ColumnComparison(
                                left = left.column.withTableAlias(onColumnAlias(left.column, classToAlias, alias)),
                                right = right.column.withTableAlias(onColumnAlias(right.column, classToAlias, mainAlias)),
                            )
                        }
                    }
                }
            }
        }

        // T-M20-05：仅 EntityRefArg（psi 解析实体）携带逻辑删除列；类名兜底/字符串 join/selectCollection
        // 子查询形态 tableInfo 为 null → 不填充（SqlPreviewService 不注入副表 deleted）
        return JoinClause(type, tableName, alias, onConditions, logicDeleteColumn = logicDelete, tableInfo = tableInfo)
    }

    /**
     * 字符串 join 的 `{i}` 绑定参数替换（官方示例：`leftJoin("(select ... where addr.id = {0})", 1)`）。
     *
     * T-UpdateSetSqlConcat：统一走 [renderSqlFragment]——替换 `{i}` 的同时按序登记占位符来源，
     * 否则 join 里的 `?` 会占用后面条件的参数位。
     */
    private fun renderJoinSql(template: SqlFragment, values: List<WrapperArg>): SqlFragment =
        renderSqlFragment(template, values.map { it.asValue() })

    /** 首参是否为布尔条件前缀（true/false 字面量或运行时布尔表达式占位符）；列引用（ColumnArg）恒为列。 */
    private fun isFlagArgument(arg: WrapperArg): Boolean {
        if (arg is WrapperArg.ColumnArg) return false
        val value = arg.asValue()
        // T-UpdateSetSqlConcat：带片段形态的运行期实参是 SQL 片段（`leftJoin("... " + x, ...)`），
        // 不是布尔前缀——否则片段会被当 flag 吞掉，真正的片段位被错取成第二参
        if (value is SqlValue.Placeholder && arg.asSqlFragment() != null) return false
        return when (value) {
            is SqlValue.Literal -> value.unquoted == "true" || value.unquoted == "false"
            is SqlValue.Placeholder -> true
            else -> false
        }
    }

    /**
     * 解析 leftJoin(Class, on -> on.eq(...)) 的 lambda ON（清单 P13）。
     *
     * on 内条件：
     *  - `eq(关联表字段, 主表字段)` → 列对列比较（alias.左 = mainAlias.右）
     *  - `eq(关联表字段, 值)` → 简单等值条件（alias.列 = 值）
     */
    /**
     * lambda ON 条件解析（P13 + 2026-08-13 用户实测扩展）：
     *  - `eq/ne(col, 列|值)`：列可为**方法引用**（按左右归属加别名）或**字符串列/表达式**（原样透传）；
     *  - 右参为字符串且**含 `.`**（`eq("t1.workflow_id", "t.id")`，Scratch 用例）→ 字符串列引用，
     *    生成列对列比较（不能加引号当值）；否则为字符串值（`eq("t2.script_status", "1")` → `= '1'`）；
     *  - `apply(sql)`：Raw 条件原样透传（含子查询，如 `t1.version = (SELECT MAX(version) ...)`）。
     */
    private fun parseOnLambda(
        chain: WrapperChain,
        alias: String,
        mainAlias: String,
        classToAlias: Map<String, String>,
    ): List<Condition> =
        chain.calls.mapNotNull { call ->
            when (call.methodName) {
                "eq", "ne" -> {
                    val left = call.args.getOrNull(0)
                    val leftCol = when (left) {
                        // T-A47：ON 内的列一律**按列自己的实体类**定别名（MPJ 真实行为），
                        // 查不到才回落关联表别名 / 主表别名
                        is WrapperArg.ColumnArg ->
                            left.column.withTableAlias(onColumnAlias(left.column, classToAlias, alias))
                        is WrapperArg.ValueArg ->
                            (left.value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
                        else -> null
                    } ?: return@mapNotNull null
                    val right = call.args.getOrNull(1)
                    when (right) {
                        is WrapperArg.ColumnArg ->
                            Condition.ColumnComparison(
                                left = leftCol,
                                right = right.column.withTableAlias(onColumnAlias(right.column, classToAlias, mainAlias)),
                            )
                        is WrapperArg.ValueArg -> {
                            val literal = right.value as? SqlValue.Literal
                            if (literal != null && literal.unquoted.contains('.')) {
                                // 字符串列引用形态（"t.id"）→ 列对列比较
                                Condition.ColumnComparison(
                                    left = leftCol,
                                    right = ColumnRef(columnName = literal.unquoted),
                                )
                            } else {
                                val value = right.asValue() ?: return@mapNotNull null
                                Condition.Simple(leftCol, if (call.methodName == "ne") "<>" else "=", value)
                            }
                        }
                        else -> {
                            val value = right?.asValue() ?: return@mapNotNull null
                            Condition.Simple(leftCol, if (call.methodName == "ne") "<>" else "=", value)
                        }
                    }
                }
                "apply" -> {
                    // T-HiddenBugPerfScan 券 Y4：与 MpjJoinSupport.parseOnLambda 统一片段通道
                    // （asSqlFragment）——曾只认纯字面量：运行期拼接段（apply("t1.v = " + x)）
                    // 被静默丢弃；纯字面量自带 ? 时不带 params，该 ? 无来源致后续回填整体
                    // 错位（T-UpdateSetSqlConcat 同族）。
                    call.args.firstOrNull().asSqlFragment()?.let { frag ->
                        if (frag.text.isNotBlank()) Condition.Raw(frag.text, params = frag.params) else null
                    }
                }
                else -> null
            }
        }

    private const val SELECT_AS = "selectAs"
    private const val SELECT_AS_CLASS = "selectAsClass"
    private const val SELECT_ALL = "selectAll"
    private const val SELECT_SUB = "selectSub"
    private const val SET_ALIAS = "setAlias"

    /** T-MpjFill：fill 关联填充方法名（MPJ 1.5.1+）。 */
    private const val FILL_METHOD = "fill"

    /** T-M21-07：from 子查询主表（MPJ 官方 API，JoinAbstractLambdaWrapper.from(MFunction)）。 */
    private const val FROM_METHOD = "from"

    /** T-M21-09：around（MPJ 1.4.11+，Join.around(firstSql, lastSql)——firstSql 拼 SQL 起始、lastSql 拼末尾）。 */
    private const val AROUND_METHOD = "around"

    /**
     * T-M21-09：无 SQL 形态的 MPJ 官方方法（运行时语义，静态渲染 no-op，从解析链过滤）：
     *  - `setTableName(Function)`（1.4.4+）：动态表名函数 lambda 静态不可求值 → no-op（文档化边界）；
     *  - `pageByMain()`：主表分页标记（影响分页 COUNT 逻辑）→ 不影响 SELECT 形态 → no-op。
     */
    private val NOOP_METHODS = setOf("setTableName", "pageByMain")

    /** UNION 方法（union / unionAll）。 */
    private val UNION_METHODS = setOf("union", "unionAll")
    private const val SELECT_COLLECTION = "selectCollection"
    private const val SELECT_ASSOCIATION = "selectAssociation"

    /** 联表方法全集（leftJoin/innerJoin/rightJoin/fullJoin + join 自定义关键字，官方 API）。 */
    private val JOIN_METHODS = setOf("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join")

    /**
     * 解析 selectAsClass（MPJ 官方 API）：`selectAsClass(源类, 目标类)` —— 按属性名取两表交集字段，
     * 生成 `主表列 AS 属性名` 列（目标类同名属性即映射列）。
     */
    private fun parseSelectAsClass(call: WrapperCall, mainAlias: String): List<ColumnRef> {
        val source = (call.args.getOrNull(0) as? WrapperArg.EntityRefArg)?.tableInfo ?: return emptyList()
        val target = (call.args.getOrNull(1) as? WrapperArg.EntityRefArg)?.tableInfo ?: return emptyList()
        val targetFields = target.columns.map { it.fieldName }.toSet()
        return source.columns
            .filter { it.fieldName in targetFields }
            .map { ColumnRef(tableAlias = mainAlias, columnName = it.columnName, alias = it.fieldName) }
    }

    /**
     * 解析 selectAll 调用（官方 API 全覆盖）：
     *  - `selectAll(Class)` → 主表全列
     *  - `selectAll(Class, "prefix")` → 列名加前缀
     *  - `selectAll(Class, Entity::getX, ...)` → 排除字段（exclude）
     *  - `selectAll(Class, "prefix", Entity::getX, ...)` → 前缀 + 排除
     */
    private fun parseSelectAll(call: WrapperCall, mainAlias: String, table: TableInfo): List<ColumnRef> {
        val args = call.args
        // 第二参为字符串字面量（非 .class）→ 列前缀
        val second = args.getOrNull(1)
        val prefix = (second.asValue() as? SqlValue.Literal)
            ?.takeIf { !it.unquoted.trimEnd().endsWith(".class") }
            ?.unquoted
        val excludeStart = if (prefix != null) 2 else 1
        val exclude = args.drop(excludeStart).mapNotNull { it.asColumn()?.columnName }.toSet()
        return table.allColumnNames
            .filter { it !in exclude }
            .map { ColumnRef(tableAlias = mainAlias, columnName = if (prefix != null) prefix + it else it) }
    }

    /**
     * 实参是否为类名字面量（psi 层 `XxxEntity.class` 经 ExpressionEvaluator 求值为简单类名字面量——
     * 驼峰首字母大写、无空格/括号；`.class` 后缀形态兼容）。
     * T-M21-03：selectCollection prefix 形态判定（第 2 参为类名 ValueArg）；普通形态首参同理。
     */
    private fun isClassNameArg(arg: WrapperArg?): Boolean {
        val text = (arg.asValue() as? SqlValue.Literal)?.unquoted ?: return false
        val trimmed = text.trim()
        return trimmed.endsWith(".class") ||
            (trimmed.isNotEmpty() && trimmed[0].isUpperCase() && !trimmed.any { it == ' ' || it == '(' })
    }

    /**
     * T-1800：判断 selectCollection 调用是否为 prefix 形态（第 1 参为字符串 prefix，第 2 参为类参）。
     * 兼容 EntityRefArg 与 ValueArg 两种类参形态：
     *  - EntityRefArg 在第 2 参 → prefix 形态
     *  - ValueArg 在第 2 参且为类名字面量 → prefix 形态（旧逻辑）
     *  - 否则 → 普通形态（第 1 参为类参）
     */
    private fun isPrefixForm(call: WrapperCall): Boolean {
        val arg1 = call.args.getOrNull(1)
        if (arg1 is WrapperArg.EntityRefArg) return true
        return isClassNameArg(arg1)
    }

    /**
     * selectCollection 调用对应的子表类名（T-M21-03：prefix 形态子表类在第 2 参，普通形态在第 1 参）。
     * T-1800：EntityRefArg 优先取 entityClassName，ValueArg 取类名字符串。
     * 用于「selectCollection 子查询已替代同子表普通 join」的去重判定。
     */
    private fun collectionChildClassOf(call: WrapperCall): String? {
        val childArg = if (isPrefixForm(call)) call.args.getOrNull(1) else call.args.getOrNull(0)
        return when (childArg) {
            is WrapperArg.EntityRefArg -> childArg.tableInfo.entityClassName
            else -> (childArg?.asValue() as? SqlValue.Literal)?.unquoted
        }
    }

    /**
     * T-1800：从 selectCollection / selectAssociation 调用提取子表 TableInfo（EntityRefArg 优先）。
     * prefix 形态在第 2 参，普通形态在第 1 参。无 EntityRefArg 时返回 null。
     */
    private fun collectionTableInfo(call: WrapperCall): TableInfo? {
        val entityRef = if (isPrefixForm(call)) {
            call.args.getOrNull(1) as? WrapperArg.EntityRefArg
        } else {
            call.args.getOrNull(0) as? WrapperArg.EntityRefArg
        }
        return entityRef?.tableInfo
    }

    /**
     * T-1800：从 join 调用提取关联实体类名（用于去重比较）。
     * EntityRefArg 取 entityClassName，ValueArg 取类名字符串。
     */
    private fun joinEntityClassName(call: WrapperCall): String? {
        val first = call.args.getOrNull(0)
        return when (first) {
            is WrapperArg.EntityRefArg -> first.tableInfo.entityClassName
            else -> (first.asValue() as? SqlValue.Literal)?.unquoted
        }
    }

    /**
     * 解析 selectCollection(子表类, VO::get集合[, map -> map.id(...).result(...)]) → LEFT JOIN 子查询（清单 P06/P07）。
     *
     * 形态（对齐 MPJ 一对多子查询语义）：
     * ```
     * LEFT JOIN (
     *   SELECT <子映射列> FROM <子表> <alias> [LEFT JOIN (孙表子查询) ...]
     * ) <alias> ON <同子表 leftJoin 的 ON 条件>
     * ```
     * - 子映射 `id/result` → 子查询 SELECT 列；嵌套 `collection` → 子查询内递归 LEFT JOIN（P07 三层关联）
     * - ON 条件复用同链中同子表的 leftJoin（P06 的既有写法），避免重复指定外键
     *
     * T-M21-03 prefix 形态（QueryLabel.java）：`selectCollection("prefix", Child.class, VO::getX[, map])`
     * ——第 2 参为类名 ValueArg（第 3 参为 VO 集合 getter）即判定为 prefix 形态（实现歧义以第 2 参为准）；
     * 子表类取第 2 参、别名用 prefix（子查询表别名）、列映射 map 位置顺延（索引 3）。
     * free 形态（首参 VO 方法引用、无子表类）已在 convert 从 collectionCalls 剔除，不产生子查询。
     */
    private fun parseCollection(
        call: WrapperCall,
        joinCalls: List<WrapperCall>,
        mainAlias: String,
        project: Project?,
        classToAlias: Map<String, String>,
    ): JoinClause? {
        // T-M21-03：prefix 形态判定——第 2 参为类参（EntityRefArg 或类名字面量）
        // T-1800：兼容 EntityRefArg 形态（isPrefixForm 内部处理）
        val prefixForm = isPrefixForm(call)
        val childClass: String
        val alias: String
        val mapIndex: Int
        if (prefixForm) {
            // prefix 形态：第 2 参为类参，第 1 参为 prefix 别名
            val classArg = call.args.getOrNull(1)
            childClass = when (classArg) {
                is WrapperArg.EntityRefArg -> classArg.tableInfo.entityClassName
                else -> (classArg?.asValue() as? SqlValue.Literal)?.unquoted ?: return null
            }
            alias = (call.args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return null
            mapIndex = 3
        } else {
            // 普通形态：第 1 参为类参
            val classArg = call.args.getOrNull(0)
            childClass = when (classArg) {
                is WrapperArg.EntityRefArg -> classArg.tableInfo.entityClassName
                else -> (classArg?.asValue() as? SqlValue.Literal)?.unquoted ?: return null
            }
            alias = childClass.replaceFirstChar { it.lowercaseChar() }
            mapIndex = 2
        }
        // T-1800（Bug1 修复）：优先从 EntityRefArg 取真实 @TableName，不再仅靠 snake_case 推断。
        // 例：AddressDO(@TableName("user_address")) → EntityRefArg 直接得 user_address，避免 address_d_o。
        // T-FS-04：类名兜底推断显式传调用方 project（实体类 PSI 不可解析时按该项目读项目级表配置）
        val childTable = collectionTableInfo(call)?.tableName
            ?: EntityResolver.inferDecoratedTableName(childClass, project)

        val subSelect = renderCollectionSubSelect(call, childTable, alias, mapIndex, project)

        // ON 条件：复用同链中同子表 leftJoin 的 ON（T-M15-06 修复——四种 join 形态全覆盖）：
        // 三参（ColumnArg 对）/ 四参带别名 / on(col1, col2) / lambda ON，统一经 parseJoin 提取，
        // 并交换左右使主表列在左（与 on(col1, col2) 形态一致；等值比较交换无副作用）。
        // 修复前仅匹配 on(col1, col2)（OnConditionArg），P06 主形态三参 leftJoin 下子查询无 ON（笛卡尔积）。
        // T-1800（Bug3 修复）：ON 条件中的 leftJoin 别名替换为 collection 别名（prefix 形态别名可能不同）。
        val onConditions = joinCalls
            .filter { joinCall ->
                val first = joinCall.args.getOrNull(0)
                when (first) {
                    is WrapperArg.EntityRefArg -> first.tableInfo.entityClassName == childClass
                    else -> (first.asValue() as? SqlValue.Literal)?.unquoted == childClass
                }
            }
            .flatMap { joinCall ->
                val joinClause = parseJoin(joinCall, mainAlias, project, classToAlias) ?: return@flatMap emptyList<Condition>()
                val joinAlias = joinClause.alias  // leftJoin 的默认别名（如 addressDO）
                joinClause.onConditions.map { cond ->
                    (cond as? Condition.ColumnComparison)?.let { cc ->
                        // 替换 leftJoin 别名为 collection 别名，使 ON 条件与子查询别名一致
                        val newLeft = if (cc.left.tableAlias == joinAlias) cc.left.copy(tableAlias = alias) else cc.left
                        val newRight = if (cc.right.tableAlias == joinAlias) cc.right.copy(tableAlias = alias) else cc.right
                        // 交换左右使主表列在左
                        Condition.ColumnComparison(left = newRight, right = newLeft)
                    } ?: cond
                }
            }

        return JoinClause(JoinType.LEFT, "($subSelect)", alias, onConditions)
    }

    /**
     * 渲染 selectCollection 的子查询文本（递归支持嵌套 collection）。
     *
     * @param call        selectCollection 调用（或嵌套 collection 调用）
     * @param childTable  子表名
     * @param alias       子表别名
     * @param mapIndex    列映射 map 实参索引（T-M21-03 prefix 形态为 3，普通形态为 2；嵌套 collection 无 map）
     * @param project     调用方项目（T-FS-04）：孙表类名兜底推断按该项目读项目级表配置；null = 未提供
     */
    private fun renderCollectionSubSelect(
        call: WrapperCall,
        childTable: String,
        alias: String,
        mapIndex: Int = 2,
        project: Project? = null,
    ): String {
        val subColumns = mutableListOf<String>()
        val nestedJoins = mutableListOf<String>()
        val mapping = call.args.getOrNull(mapIndex) as? WrapperArg.NestedArg
        mapping?.chain?.calls?.forEach { c ->
            when (c.methodName) {
                // 子映射 id/result：子查询 SELECT 列
                "id", "result" -> c.args.firstOrNull().asColumn()?.let { subColumns += it.columnName }
                // 嵌套 collection（P07 三层关联）：子查询内递归 LEFT JOIN；外键按「孙表.父表名_id = 父表.id」约定（R7 待 MPJ 对照）
                "collection" -> {
                    val grandChild = (c.args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return@forEach
                    // T-M19.4：孙表推断走统一入口（策略 + 修饰链，全局语义）
                    // T-FS-04：类名兜底推断显式传调用方 project（同 parseJoin）
                    val grandTable = EntityResolver.inferDecoratedTableName(grandChild, project)
                    val grandAlias = grandChild.replaceFirstChar { it.lowercaseChar() }
                    val grandSub = renderCollectionSubSelect(c, grandTable, grandAlias, project = project)
                    nestedJoins += "LEFT JOIN ($grandSub) $grandAlias ON $grandAlias.${alias}_id = $alias.id"
                }
            }
        }
        if (subColumns.isEmpty()) subColumns += "*"
        val select = "SELECT ${subColumns.joinToString(", ")} FROM $childTable $alias"
        return if (nestedJoins.isEmpty()) select else "$select ${nestedJoins.joinToString(" ")}"
    }
}
