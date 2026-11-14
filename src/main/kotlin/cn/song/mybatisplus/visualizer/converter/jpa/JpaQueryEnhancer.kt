package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * JPA 查询方法增强支撑（T-J10 / T-J15 / T-J16 / T-J22）。
 *
 * 供 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 的 previewDerivedQuery / previewJpql /
 * previewHql / previewCriteriaQuery / previewProcedure 各路径共用：
 *
 *  - **T-J10 Pageable/Sort/Limit 全路径**：[evaluatePageSort]——方法参数含 `Pageable`/`Page`/`Sort`/`Limit`
 *    类型时产出 LIMIT/OFFSET 占位与 ORDER BY；调用点（J9 动作路径）传 [callArgs] 时对
 *    `PageRequest.of(page, size)` / `Sort.by(...)` 做静态求值，有字面量就内联，无则 `?` 占位；
 *  - **T-J15 锁与抓取注解**：[lockClause]（@Lock → FOR UPDATE / 共享锁方言差异注释）、
 *    [queryHintNotes]（@QueryHints 逐条注释）、[entityGraph]/[resolveEntityGraphJoins]
 *    （@EntityGraph attributePaths → LEFT JOIN，关联元数据来自 [TableInfo.associations]，解析失败降级注释）；
 *  - **T-J16 存储过程**：[procedureInfo]——@Procedure 的 procedureName/value 优先、方法名兜底、
 *    outputParameterName 标注；
 *  - **T-J22 返回类型与投影**：[returnTypeNotes]（Page/Slice/Stream/Streamable/异步/接口投影 notes 级说明）、
 *    [countQueryAttribute]（@Query.countQuery 原文提取，转换由调用方完成）。
 *
 * 边界（文档化，不实现）：嵌套关联路径（`a.b`）静态降级注释；`Sort.by` 关联属性、
 * `ExampleMatcher` 形态归各转换器管；运行时回调（queryRewriter 等）不解析。
 * 全程不抛异常（runCatching 包裹 PSI 解析），解析失败一律降级为 null/注释。
 */
object JpaQueryEnhancer {

    // ------------------------------------------------------------------
    // T-J10：Pageable / Sort / Limit 参数分析
    // ------------------------------------------------------------------

    /**
     * 分页/排序参数分析结果。
     *
     * @param limit           LIMIT/OFFSET 子句模型（null 表示方法无分页参数）
     * @param orderBy         静态可求值的 ORDER BY 项（Sort.by 字面量）
     * @param extraParamNames 需要追加到参数行的参数名（pageOffset/pageSize/limit 变量名）
     * @param notes           语义注释（Sort 不可静态求值等）
     */
    data class PageSortEval(
        val limit: LimitClause? = null,
        val orderBy: List<OrderByItem> = emptyList(),
        val extraParamNames: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
    )

    /**
     * 分析方法的 Pageable/Page/Sort/Limit 参数（T-J10）。
     *
     * @param method   宿主方法（Repository 声明方法或 HQL/Criteria 所在方法）
     * @param callArgs 调用点实参（J9 动作路径反查到声明方法后可传；按位置对齐方法参数）。
     *                 非 null 时对 PageRequest.of / Sort.by 做静态求值；null 时占位。
     * @param dialect  方言（LIMIT 形态由调用方 [Dialect.renderLimit] 渲染，此处只建模型）
     * @param table    实体表信息（Sort 属性名 → 列名映射用；null 时按驼峰转下划线）
     */
    fun evaluatePageSort(
        method: PsiMethod?,
        callArgs: List<PsiExpression>? = null,
        dialect: Dialect? = null,
        table: TableInfo? = null,
    ): PageSortEval? = runCatching {
        if (method == null) return@runCatching null
        val params = method.parameterList.parameters
        if (params.isEmpty()) return@runCatching null

        var limit: LimitClause? = null
        val orderBys = mutableListOf<OrderByItem>()
        val extraParams = mutableListOf<String>()
        val notes = mutableListOf<String>()

        params.forEachIndexed { index, param ->
            // 去泛型后缀（org.springframework.data.domain.Page<UserEntity> → ...Page）
            val typeName = param.type.canonicalText.substringBefore('<')
            val argExpr = callArgs?.getOrNull(index)
            when {
                isPageableType(typeName) -> {
                    val pageEval = argExpr?.let { evalPageRequest(it) }
                    // 双字面量（page/size 均可求值）才内联；否则占位 + 参数行
                    // T-HiddenBugPerfScan 券 X：双字面量才内联（KDoc 声明的判据）——曾只判
                    // size：PageRequest.of(运行时page, 字面量size) 时 offset=null（渲染语义
                    // =无 OFFSET）、参数行缺 pageOffset、note 谎报 offset=0，预览变首页语义。
                    val sizeLiteral = pageEval?.second as? SqlValue.Literal
                    val offsetLiteral = pageEval?.first as? SqlValue.Literal
                    if (offsetLiteral != null && sizeLiteral != null) {
                        notes += "PageRequest.of 静态求值：offset=${offsetLiteral.unquoted}，size=${sizeLiteral.unquoted}"
                        limit = LimitClause(offset = offsetLiteral, count = sizeLiteral)
                    } else if (sizeLiteral != null) {
                        // page 运行时变量 + size 字面量：LIMIT 内联、OFFSET 占位 + 参数行
                        notes += "PageRequest.of 静态求值：offset=运行时变量（占位 pageOffset），size=${sizeLiteral.unquoted}（LIMIT 内联）"
                        limit = LimitClause(offset = SqlValue.Placeholder("pageOffset"), count = sizeLiteral)
                        extraParams += listOf("pageOffset")
                    } else {
                        limit = LimitClause(
                            offset = SqlValue.Placeholder("pageOffset"),
                            count = SqlValue.Placeholder("pageSize"),
                        )
                        extraParams += listOf("pageSize", "pageOffset")
                    }
                }
                isLimitType(typeName) -> {
                    // Spring Data 3.2 Limit 参数：仅行数限制、无 OFFSET
                    if (limit == null) {
                        limit = LimitClause(count = SqlValue.Placeholder(param.name ?: "limit"))
                        extraParams += param.name ?: "limit"
                    }
                }
                isSortType(typeName) -> {
                    val sortItems = argExpr?.let { evalSortBy(it, table) }
                    if (sortItems != null) {
                        if (sortItems.isEmpty()) {
                            notes += "Sort.unsorted()：无动态排序"
                        } else {
                            orderBys += sortItems
                        }
                    } else {
                        notes += "Sort 参数：运行时动态 ORDER BY（静态不可解析，预览不含排序子句）"
                    }
                }
            }
        }

        if (limit == null && orderBys.isEmpty() && notes.isEmpty()) return@runCatching null
        PageSortEval(limit = limit, orderBy = orderBys.toList(), extraParamNames = extraParams, notes = notes)
    }.getOrNull()

    /** 类型名是否为 Pageable/Page 家族（org.springframework.data.domain.Pageable / Page 子形态；简名兜底）。 */
    fun isPageableType(typeName: String): Boolean =
        typeName == "Pageable" || typeName.endsWith(".Pageable") ||
            (typeName.endsWith(".Page") && typeName.contains("domain"))

    /** 类型名是否为 Spring Data 3.2 Limit 接口（简名兜底）。 */
    fun isLimitType(typeName: String): Boolean =
        typeName == "Limit" || (typeName.endsWith(".Limit") && typeName.contains("domain"))

    /** 类型名是否为 Sort（简名兜底）。 */
    fun isSortType(typeName: String): Boolean =
        typeName == "Sort" || (typeName.endsWith(".Sort") && typeName.contains("domain"))

    /**
     * 静态求值 `PageRequest.of(page, size)` / `PageRequest.ofSize(size)`（含变量初始化式回溯一次）。
     *
     * @return (offset, count)；字面量可求值时为 [SqlValue.Literal]，否则 null（调用方降级占位）
     */
    fun evalPageRequest(expr: PsiExpression): Pair<SqlValue?, SqlValue?>? = runCatching {
        val call = expr as? PsiMethodCallExpression ?: expr.traceInitializer() as? PsiMethodCallExpression ?: return@runCatching null
        val name = call.methodExpression.referenceName ?: return@runCatching null
        val qualifier = call.methodExpression.qualifierExpression?.text.orEmpty()
        if (!qualifier.endsWith("PageRequest")) return@runCatching null
        val args = call.argumentList.expressions
        when (name) {
            "of" -> {
                if (args.size < 2) return@runCatching null
                val page = intValue(args[0])
                val size = intValue(args[1])
                val offset = if (page != null && size != null) SqlValue.Literal((page * size).toString()) else null
                offset to size?.let { SqlValue.Literal(it.toString()) }
            }
            "ofSize" -> {
                val size = intValue(args.firstOrNull() ?: return@runCatching null) ?: return@runCatching null
                SqlValue.Literal("0") to SqlValue.Literal(size.toString())
            }
            else -> null
        }
    }.getOrNull()

    /**
     * 静态求值 `Sort.by(...)`（含变量初始化式回溯一次）。
     *
     * 支持：`Sort.by("a", "b")`、`Sort.by(Sort.Direction.DESC, "a")`、
     * `Sort.by(Sort.Order.desc("a"), Sort.Order.asc("b"))`、`Sort.unsorted()`（空列表）。
     * 属性名经 [mapColumn] 映射列名；关联属性（含 `.`）跳过（静态降级）。
     */
    fun evalSortBy(expr: PsiExpression, table: TableInfo?): List<OrderByItem>? = runCatching {
        val call = expr as? PsiMethodCallExpression ?: expr.traceInitializer() as? PsiMethodCallExpression ?: return@runCatching null
        val name = call.methodExpression.referenceName ?: return@runCatching null
        val qualifier = call.methodExpression.qualifierExpression?.text.orEmpty()
        if (!qualifier.endsWith("Sort")) return@runCatching null
        val args = call.argumentList.expressions
        when (name) {
            "unsorted" -> emptyList()
            "by" -> {
                val items = mutableListOf<OrderByItem>()
                var desc = false
                for (arg in args) {
                    when {
                        // Sort.Direction.ASC/DESC 前缀参数，作用于其后的字符串实参
                        arg is PsiReferenceExpression && arg.referenceName == "DESC" -> desc = true
                        arg is PsiReferenceExpression && arg.referenceName == "ASC" -> desc = false
                        // Sort.Order.desc("x") / Sort.Order.asc("x")
                        arg is PsiMethodCallExpression && arg.methodExpression.referenceName in setOf("asc", "desc") -> {
                            val prop = (arg.argumentList.expressions.firstOrNull() as? PsiLiteralExpression)?.value as? String
                            if (prop != null && !prop.contains('.')) {
                                items += OrderByItem(ColumnRef(columnName = mapColumn(prop, table)), asc = arg.methodExpression.referenceName == "asc")
                            }
                        }
                        // 字符串字面量属性
                        arg is PsiLiteralExpression -> {
                            val prop = arg.value as? String
                            if (prop != null && !prop.contains('.')) {
                                items += OrderByItem(ColumnRef(columnName = mapColumn(prop, table)), asc = !desc)
                            }
                        }
                    }
                }
                items
            }
            else -> null
        }
    }.getOrNull()

    /** 实体字段名 → 列名（表元数据匹配优先，T-Embedded 嵌入段驼峰拼接路径次之，驼峰转下划线兜底）。 */
    private fun mapColumn(fieldName: String, table: TableInfo?): String {
        val matched = table?.columnByField(fieldName)
            ?: table?.columnByEmbeddedPath(fieldName)
        return matched?.columnName ?: NamingStrategy.toSnakeCase(fieldName)
    }

    // ------------------------------------------------------------------
    // T-J15：@Lock / @QueryHints / @EntityGraph
    // ------------------------------------------------------------------

    /**
     * @Lock 注解 → (FOR UPDATE 子句, 注释说明)。
     *
     * PESSIMISTIC_WRITE/WRITE/PESSIMISTIC_FORCE_INCREMENT → SELECT 尾部 `FOR UPDATE`；
     * READ/PESSIMISTIC_READ/OPTIMISTIC 等共享/乐观锁形态 → 仅注释说明方言差异（Oracle 无
     * FOR SHARE、MySQL 8 才有等），不追加 SQL 子句。无注解返回 null。
     */
    fun lockClause(method: PsiMethod): Pair<String?, String>? = runCatching {
        val annotation = findAnnotation(method, "Lock") ?: return@runCatching null
        val mode = (annotation.findAttributeValue("value") as? PsiReferenceExpression)?.referenceName ?: "unspecified"
        when {
            mode in setOf("PESSIMISTIC_WRITE", "WRITE", "PESSIMISTIC_FORCE_INCREMENT") ->
                "FOR UPDATE" to "@Lock($mode)：悲观写锁，SELECT 追加 FOR UPDATE（锁至事务结束）"
            mode in setOf("PESSIMISTIC_READ", "READ") ->
                null to "@Lock($mode)：共享锁——方言差异大（PostgreSQL FOR SHARE / MySQL 8 FOR SHARE / Oracle 需以写锁子句近似），预览不追加子句"
            mode in setOf("OPTIMISTIC", "OPTIMISTIC_FORCE_INCREMENT") ->
                null to "@Lock($mode)：乐观锁（版本列校验，无锁子句）"
            else -> null to "@Lock($mode)：锁模式无法静态映射为 SQL 子句"
        }
    }.getOrNull()

    /** @QueryHints → 逐条注释行（`@QueryHint(name=.., value=..)`）；无注解返回空列表。 */
    fun queryHintNotes(method: PsiMethod): List<String> = runCatching {
        val annotation = findAnnotation(method, "QueryHints") ?: return@runCatching emptyList()
        val notes = mutableListOf<String>()
        val hintsValue = annotation.findAttributeValue("value")
        collectHintAnnotations(hintsValue).forEachIndexed { index, hint ->
            val name = (hint.findAttributeValue("name") as? PsiLiteralExpression)?.value as? String ?: "?"
            val value = (hint.findAttributeValue("value") as? PsiLiteralExpression)?.value as? String ?: "?"
            notes += "@QueryHints[$index]：$name = $value（运行时作为持久化提示下发，不改变 SQL 文本）"
        }
        val forCounting = (annotation.findAttributeValue("forCounting") as? PsiLiteralExpression)?.value as? Boolean
        if (forCounting == false) notes += "@QueryHints(forCounting=false)：hint 不应用于 count 查询"
        notes
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // T-J16：@Procedure
    // ------------------------------------------------------------------

    /**
     * @Procedure 注解信息。
     *
     * @param procName            过程名（procedureName/value 优先，方法名兜底）
     * @param outputParameterName 输出参数名（null 表示未指定）
     * @param nameRef             @Procedure.name（引用元数据中具名存储过程查询，静态不可解析时提示）
     * @param paramCount          方法参数个数（? 占位个数）
     */
    data class ProcedureInfo(
        val procName: String,
        val outputParameterName: String? = null,
        val nameRef: String? = null,
        val paramCount: Int = 0,
    )

    /** 提取方法上的 @Procedure 注解信息（FQN org.springframework.data.jpa.repository.query.Procedure，短名兜底）。 */
    fun procedureInfo(method: PsiMethod): ProcedureInfo? = runCatching {
        val annotation = findAnnotation(method, "Procedure") ?: return@runCatching null
        val fromValue = stringValue(annotation.findAttributeValue("value"))
        val fromProcedureName = stringValue(annotation.findAttributeValue("procedureName"))
        val procName = fromProcedureName?.takeIf { it.isNotBlank() }
            ?: fromValue?.takeIf { it.isNotBlank() }
            ?: method.name
        ProcedureInfo(
            procName = procName,
            // 注解缺省（空串）时收窄为 null——不挂「输出参数」注释行
            outputParameterName = stringValue(annotation.findAttributeValue("outputParameterName"))?.takeIf { it.isNotBlank() },
            nameRef = stringValue(annotation.findAttributeValue("name"))?.takeIf { it.isNotBlank() },
            paramCount = method.parameterList.parameters.size,
        )
    }.getOrNull()

    // ------------------------------------------------------------------
    // T-J22：返回类型与投影
    // ------------------------------------------------------------------

    /**
     * 返回类型 notes 级标注（T-J22，只说明不收敛 SELECT 列）：
     *  - `Page<T>` → 运行时附加 count 查询（countQuery SQL 由调用方按 @Query.countQuery 补充展示）；
     *  - `Slice<T>` → 多取一条判断是否有下一页；
     *  - `Stream<T>` → 须在事务中消费并关闭；
     *  - `Streamable<T>` → 可流式聚合的结果包装；
     *  - `CompletableFuture`/`Future` → 异步执行；
     *  - 接口投影（返回类型为非注解接口）→ 按 getter 名映射列（SELECT 列不收敛——文档化边界）；
     *  - 方法参数含 `Class<T>` 动态投影 → 说明。
     *
     * 返回类型基名按「去泛型 + 简名」匹配（FQN `org.springframework.data.domain.Page<..>` 同样命中）。
     */
    fun returnTypeNotes(method: PsiMethod): List<String> = runCatching {
        val notes = mutableListOf<String>()
        val returnTypeText = method.returnType?.canonicalText ?: return@runCatching notes
        when (returnTypeText.substringBefore('<').substringAfterLast('.')) {
            "Page" -> notes += "返回 Page：运行时附加 COUNT 查询统计总数（本预览仅数据查询本身）"
            "Slice" -> notes += "返回 Slice：运行时多取一条判断是否还有下一页（预览不含该探测查询）"
            "Stream" -> notes += "返回 Stream：须在事务中消费并 close()（SQL 本身与 List 形态一致）"
            "Streamable" -> notes += "返回 Streamable：可流式聚合的结果包装（SQL 本身与 List 形态一致）"
            "CompletableFuture", "Future", "ListenableFuture" ->
                notes += "返回 Future 家族：查询在线程池异步执行（SQL 不变）"
            else -> projectionNote(method)?.let { notes += it }
        }
        val hasDynamicProjection = method.parameterList.parameters.any { param ->
            param.type.canonicalText.substringBefore('<') == "java.lang.Class" ||
                param.type.canonicalText.substringBefore('<') == "Class"
        }
        if (hasDynamicProjection) notes += "Class<T> 动态投影参数：运行时按投影类型收敛/转换结果列"
        notes
    }.getOrDefault(emptyList())

    /**
     * 接口投影说明（返回类型为接口且含 getter → 列出投影属性；
     * `List<Projection>` 等集合包装先解包一层元素类型）。
     *
     * 注意顺序：先取泛型参数（仅需 PSI 结构，无需解析外层 `java.util.List`——测试模块/无 JDK
     * 解析态下外层 `resolve()` 可能为 null，不能作为前置条件），元素类型不可解析时才回退
     * 返回类型本身（裸接口返回形态）。
     */
    private fun projectionNote(method: PsiMethod): String? = runCatching {
        val classType = method.returnType as? com.intellij.psi.PsiClassType ?: return@runCatching null
        val targetType = classType.parameters.firstOrNull() as? com.intellij.psi.PsiClassType ?: classType
        val resolved = targetType.resolve() ?: return@runCatching null
        if (!resolved.isInterface || resolved.isAnnotationType) return@runCatching null
        val qualifiedName = resolved.qualifiedName.orEmpty()
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("org.springframework.")) return@runCatching null
        // 自身 methods 优先（接口声明 getter），allMethods 兜底（继承投影形态）；distinct 去重
        val properties = (resolved.methods + resolved.allMethods)
            .distinct()
            .filter { it.parameterList.parameters.isEmpty() }
            // java.lang.Object 公共方法（getClass 等）不视为投影属性
            .filter { it.containingClass?.qualifiedName != "java.lang.Object" }
            .mapNotNull { m ->
                val name = m.name
                when {
                    name.startsWith("get") && name.length > 3 -> name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
                    name.startsWith("is") && name.length > 2 -> name.removePrefix("is").replaceFirstChar { it.lowercaseChar() }
                    else -> null
                }
            }
            .distinct()
        if (properties.isEmpty()) return@runCatching null
        "接口投影 [${properties.joinToString(", ")}]：运行时按 getter 名映射列（AS 别名）；预览保持全列 SELECT 不收敛（文档化边界）"
    }.getOrNull()

    /** @Query 的 countQuery 属性原文（T-J22：Page 返回时一并展示 count SQL）；无则 null。 */
    fun countQueryAttribute(method: PsiMethod): String? = runCatching {
        val annotation = findAnnotation(method, "Query") ?: return@runCatching null
        stringValue(annotation.findAttributeValue("countQuery"))?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ------------------------------------------------------------------
    // @EntityGraph → LEFT JOIN
    // ------------------------------------------------------------------

    /** @EntityGraph 注解信息（attributePaths + type）。 */
    data class EntityGraphInfo(val paths: List<String>, val type: String?)

    /** 提取方法上的 @EntityGraph（jakarta/javax @NamedEntityGraph 引用形态不在本方法——引用按名回查属 J15 远期）。 */
    fun entityGraph(method: PsiMethod): EntityGraphInfo? = runCatching {
        val annotation = findAnnotation(method, "EntityGraph") ?: return@runCatching null
        val attributePaths = annotation.findAttributeValue("attributePaths")
        val paths = collectStringLiterals(attributePaths)
        val type = (annotation.findAttributeValue("type") as? PsiReferenceExpression)?.referenceName
        if (paths.isEmpty()) null else EntityGraphInfo(paths, type)
    }.getOrNull()

    /**
     * attributePaths → LEFT JOIN 描述（按 [TableInfo.associations] 关联元数据）。
     *
     * @return (可解析的 join 描述列表, 降级注释列表——嵌套路径/无关联元数据)
     */
    data class GraphJoin(
        val targetTable: String,
        val targetAlias: String,
        /** 主表外键列（@JoinColumn(name)） */
        val mainColumn: String,
        /** 副表被引用列（referencedColumnName，缺省按主键 id 惯例） */
        val targetColumn: String,
        val path: String,
    )

    fun resolveEntityGraphJoins(info: EntityGraphInfo, table: TableInfo): Pair<List<GraphJoin>, List<String>> {
        val joins = mutableListOf<GraphJoin>()
        val notes = mutableListOf<String>()
        runCatching {
            for (path in info.paths) {
                if (path.contains('.')) {
                    notes += "@EntityGraph 嵌套关联路径 \"$path\"：需逐级实体元数据，静态降级（预览不含该 JOIN）"
                    continue
                }
                val association = table.associations.firstOrNull { it.fieldName == path }
                if (association == null) {
                    notes += "@EntityGraph 路径 \"$path\"：实体 ${table.entityClassName} 无关联元数据（@OneToMany mapped 等形态），静态降级"
                    continue
                }
                joins += GraphJoin(
                    targetTable = association.targetTable,
                    targetAlias = association.targetAlias,
                    mainColumn = association.joinColumn,
                    targetColumn = association.referencedColumnName ?: "id",
                    path = path,
                )
            }
        }
        return joins to notes
    }

    /** join 描述 → JoinClause（语句模型路径：previewDerivedQuery / previewCriteriaQuery）。 */
    fun toJoinClause(join: GraphJoin, mainAlias: String?, dialect: Dialect): JoinClause = JoinClause(
        type = JoinType.LEFT,
        tableName = join.targetTable,
        alias = join.targetAlias,
        onConditions = listOf(
            cn.song.mybatisplus.visualizer.model.Condition.ColumnComparison(
                left = ColumnRef(tableAlias = mainAlias, columnName = join.mainColumn),
                right = ColumnRef(tableAlias = join.targetAlias, columnName = join.targetColumn),
            ),
        ),
    )

    /** join 描述 → SQL 行（文本路径：previewJpql / previewHql）。 */
    fun renderJoinLine(join: GraphJoin, mainAlias: String, dialect: Dialect): String =
        "LEFT JOIN ${dialect.quoteIfKeyword(join.targetTable)} ${join.targetAlias} ON " +
            "${mainAlias}.${dialect.quoteIfKeyword(join.mainColumn)} = ${join.targetAlias}.${dialect.quoteIfKeyword(join.targetColumn)}"

    /**
     * 把 JOIN 行插入 SQL 文本的顶层 FROM 之后（首个顶层 WHERE/GROUP BY/ORDER BY/HAVING/LIMIT 之前）。
     * 找不到顶层插入点时降级为追加注释（不破坏原 SQL）。
     */
    fun insertJoinLines(sql: String, joinLines: List<String>): String {
        if (joinLines.isEmpty()) return sql
        val insertAt = topLevelClauseStart(sql) ?: return sql + joinLines.joinToString("") { "\n-- $it（未能定位 FROM 子句，注释呈现）" }
        return sql.substring(0, insertAt) + joinLines.joinToString("\n") + sql.substring(insertAt)
    }

    // ------------------------------------------------------------------
    // 内部：PSI 辅助
    // ------------------------------------------------------------------

    /**
     * SQL 文本是否在**顶层**（括号深度 0、非字符串字面量内）含指定子句关键字。
     * 供调用方判定「查询内已含 LIMIT/ORDER BY」防重复追加。
     */
    fun containsTopLevelClause(sql: String, keywordRegex: Regex): Boolean = runCatching {
        topLevelMatchIndex(sql, keywordRegex) != null
    }.getOrDefault(false)

    /**
     * SQL 文本首个「顶层子句关键字」起始偏移（括号深度 0 且不在字符串字面量内）；
     * 无顶层关键字返回 null。
     */
    private fun topLevelClauseStart(sql: String): Int? =
        topLevelMatchIndex(sql, Regex("""(?i)\b(WHERE|GROUP\s+BY|ORDER\s+BY|HAVING|LIMIT|OFFSET|FETCH|UNION)\b"""))

    /** 正则命中位置中第一个处于顶层（深度 0）者；无则 null。 */
    private fun topLevelMatchIndex(sql: String, keywordRegex: Regex): Int? {
        // 先按索引记录括号深度/字符串态，再从正则命中位置取深度（SQL 预览文本短，O(n) 两次扫描足够）
        val depths = IntArray(sql.length + 1)
        var depth = 0
        var inString = false
        for (i in sql.indices) {
            depths[i] = depth
            when {
                sql[i] == '\'' -> inString = !inString
                !inString && sql[i] == '(' -> depth++
                !inString && sql[i] == ')' -> depth--
            }
        }
        return keywordRegex.findAll(sql)
            .firstOrNull { depths[it.range.first] == 0 }
            ?.range?.first
    }

    /** 方法注解查找：FQN 未知时按短名兜底（LightPlatform/跨项目惯例）。 */
    private fun findAnnotation(method: PsiMethod, shortName: String): PsiAnnotation? =
        method.modifierList?.annotations?.firstOrNull { annotation ->
            annotation.qualifiedName?.substringAfterLast('.') == shortName ||
                annotation.nameReferenceElement?.referenceName == shortName
        }

    /** 注解属性字符串值（字面量直取；拼接表达式递归收集）。 */
    private fun stringValue(value: PsiAnnotationMemberValue?): String? {
        if (value == null) return null
        val literal = value as? PsiLiteralExpression
        if (literal != null) return literal.value as? String
        val parts = collectStringLiterals(value)
        return parts.joinToString("").ifBlank { null }
    }

    private fun collectStringLiterals(element: com.intellij.psi.PsiElement?): List<String> {
        if (element == null) return emptyList()
        if (element is PsiLiteralExpression) {
            val value = element.value as? String ?: return emptyList()
            return listOf(value)
        }
        return element.children.flatMap { collectStringLiterals(it) }
    }

    /** @QueryHints 的 value 属性 → @QueryHint 注解列表（数组/单值双形态）。 */
    private fun collectHintAnnotations(value: PsiAnnotationMemberValue?): List<PsiAnnotation> {
        if (value == null) return emptyList()
        return when (value) {
            is PsiAnnotation -> listOf(value)
            else -> value.children.filterIsInstance<PsiAnnotation>()
        }
    }

    /** 变量初始化式回溯一次（`Pageable p = PageRequest.of(...)` 的变量实参形态）。 */
    private fun PsiExpression.traceInitializer(): PsiExpression? {
        val ref = this as? PsiReferenceExpression ?: return null
        val variable = ref.resolve() as? PsiVariable ?: return null
        return variable.initializer
    }

    /** 整数字面量求值（含变量回溯）。 */
    private fun intValue(expr: PsiExpression?): Int? {
        if (expr == null) return null
        return when (val value = ExpressionEvaluator.evaluate(expr)) {
            is SqlValue.Literal -> value.unquoted.trimEnd('L', 'l').toIntOrNull()
            is SqlValue.Placeholder -> null
        }
    }
}
