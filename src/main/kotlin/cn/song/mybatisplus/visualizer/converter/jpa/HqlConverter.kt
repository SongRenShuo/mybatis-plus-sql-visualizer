package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor
import com.intellij.openapi.project.Project

/**
 * HQL → SQL 转换器（F-JPA-02 / Wave2-P1）。
 *
 * 核心设计：**保留原始 HQL 结构**，仅做安全的字符串级变换：
 *  1. 命名参数 `:param` → `?`
 *  2. 位置参数 `?1` → `?`
 *  3. IN 子句修正：`IN ?` → `IN (?)`
 *  4. `new Xxx(...)` 动态实例化表达式 → 提取构造参数（关键字大小写不敏感，见 [NewExpressionStripper]）
 *  5. JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件经 [JpaJoinOnResolver]
 *     按「PSI 注解 > 关联元数据 > 猜测降级」三级优先真实化）
 *  6. 实体名 → 表名映射（仅当有有效 TableInfo 时）
 *  6.5 别名作用域解析（O-57）：`FROM/JOIN 别名 → 该实体的 TableInfo`，供第 9 步按作用域分派；
 *     必须在实体名→表名替换**之前**扫描（那时实体名还在 SQL 里），见 [JpaAliasTableResolver]
 *  7. 关联字段路径映射：`alias.field.id` → 外键列名（仅当有有效 TableInfo 时）
 *  8. 属性名 → 列名映射（仅当有有效 TableInfo 时；O-57 起按别名作用域分派，
 *     副表别名的属性同样映射——此前只映射根实体列清单，`detail.pickTicketId` 原样透传
 *     导致预览 SQL 报 Unknown column）
 *  8.5 子查询 FROM/JOIN 中的其他实体名 → 表名（J13，需 Project；无 Project 跳过）
 *  9. SELECT 子句展开：`SELECT <别名>` → 列清单（T-HQL-SELECT-01）
 *     HQL 语义 `SELECT u` = 取整个实体，须展开为全部映射列，否则 MySQL 报 1054；
 *     无 SELECT 子句（裸 `from 实体`）补 SELECT 列清单（T-BareFromSelect）
 *     ——展开逻辑见 [SelectClauseExpander]，与 JpqlConverter 共用
 * 10. UPDATE 语句（J2）：`UPDATE 实体 [别名] SET 列 = 值[, ...] [WHERE ...]` →
 *     [UpdateStatement]（SET 对 / WHERE 解析见 [JpaUpdateParser]，不可精细解析的
 *     WHERE 整体作 Raw 条件透传）
 * 11. DELETE 语句 → [DeleteStatement]（既有行为不变）
 *
 * J19 边界（「识别 + 不破坏」）：HQL 高级特性——`INSERT INTO ... SELECT`、`WITH ... SELECT`
 * （CTE）、UNION/INTERSECT/EXCEPT、窗口函数 `OVER (...)`——在字符串管道中天然透传
 * （参数替换/实体替换按文本命中，SELECT 展开器对上述形态已短路不误改，见
 * [SelectClauseExpander]），检测到时仅在 statement 上附降级注释，不新增完整建模。
 *
 * 关键：返回的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
 * 转换器不解析条件树（conditions 为空），因此必须使用转换后的 SQL 字符串。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
object HqlConverter {
    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private val HQL_NAMED_PARAM_TO_Q = Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)")
    private val HQL_POSITIONAL_PARAM_TO_Q = Regex("\\?\\d+")
    private val HQL_IN_SPACE_Q = Regex("(?i)\\bIN\\s+\\?")
    private val HQL_CAMEL_SPLIT_PATTERN = Regex("([a-z])([A-Z])")
    private val HQL_MEMBER_OF_PATTERN = Regex("(.+)\\s+MEMBER OF\\s+(.+)", RegexOption.IGNORE_CASE)


    /**
     * HQL 转换产物。
     *
     * @param sql        转换后的 SQL 字符串（命名参数已替换为 ?）
     * @param statement  语句模型（SelectStatement / UpdateStatement / DeleteStatement）
     * @param inClauseParams 使用 IN 子句的参数名集合
     * @param placeholderParamNames T-JpaParamOrder F3：HQL 命名参数名序（首现序，与替换后
     *         SQL 中 `?` 一一对应）；位置参数 `?N` 不在内。调用方据此做按名回填——
     *         仅当 SQL 全部 `?` 均来自命名参数时可用（位置参数混入时数量不齐）。
     * @param degradations T-PagesHql：转换降级清单（关联路径需 JOIN / 推断 ON 条件 /
     *         实体不可解析 / 高级构造透传等）。空表 = 无降级。pages 链把它并入 warning 展示；
     *         其它调用方可忽略（默认空，零行为变化）。
     */
    data class Result(
        val sql: String,
        val statement: SqlStatement,
        val inClauseParams: Set<String> = emptySet(),
        val placeholderParamNames: List<String> = emptyList(),
        val degradations: List<String> = emptyList(),
    )

    /**
     * 转换 HQL 字符串为 Result。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做参数替换
     * @param project   项目（J7 JOIN ON 注解级解析 / J13 子查询实体映射用）；
     *                  null 时两处降级为关联元数据 / 跳过，行为与历史版本一致
     * @param entityAlias @Entity(name=...) 实体别名（T-HqlInhCols）；HQL `FROM Alias`
     *                  的 Alias 是实体名而非类名，传入后 mapEntityToTable 同样按别名替换表名。
     *                  null 时行为与历史版本一致
     * @return Result（转换后 SQL + 语句模型）
     */
    fun convert(
        hql: String,
        tableInfo: TableInfo? = null,
        project: Project? = null,
        entityAlias: String? = null,
        context: com.intellij.psi.PsiElement? = null,
    ): Result {
        return convertInternal(hql, tableInfo, project, entityAlias, null, context)
    }

    /**
     * 实体解析函数注入形态（O-57）：调用方已有 PsiClass / 解析结果时直入，
     * 免按名 PSI 检索（LightPlatform 测试环境无需依赖索引；生产入口见 [convert] 的 project 形态）。
     *
     * [resolveEntity] 收到 FROM/JOIN 中的实体标识符，返回其表信息；返回 null 表示无元数据，
     * 该别名回落既有根实体列清单行为（与无 Project 时一致）。
     */
    internal fun convertWithEntityResolver(
        hql: String,
        tableInfo: TableInfo?,
        entityAlias: String? = null,
        resolveEntity: (String) -> TableInfo?,
    ): Result {
        return convertInternal(hql, tableInfo, null, entityAlias, resolveEntity, null)
    }

    private fun convertInternal(
        hql: String,
        tableInfo: TableInfo?,
        project: Project?,
        entityAlias: String?,
        resolveEntity: ((String) -> TableInfo?)?,
        context: com.intellij.psi.PsiElement?,
    ): Result {
        val lookup = JpaQueryEntityLookup(project, context)
        val rootName = JpaQueryEntityLookup.rootEntity(hql)
        val table = tableInfo ?: rootName?.let { lookup.resolveTable(it) } ?: createFallbackTable()
        val mappedRootName = rootName?.takeIf {
            tableInfo == null || it.substringAfterLast('.') == table.entityClassName || it == entityAlias ||
                lookup.resolveClass(it)?.name == table.entityClassName
        }
        val resolveTable: (String) -> TableInfo? = { name ->
            if (name == mappedRootName && table.entityClassName.isNotBlank()) table
            else resolveEntity?.invoke(name) ?: lookup.resolveTable(name)
        }

        // 0. 检测 IN 子句参数（在命名参数替换前检测）
        // T-HiddenBugPerfScan 券 B：命名/位置参数替换先掩码字面量——字面量内的 `:word`
        // （'HH:mm'、消息文本 '订单:status'）此前会被全文正则换成 `?` 且计入名序表，
        // 产物字面量被改写、按位回填从该处起整体错位。与后续步骤的 JpaSqlLiteralMasking
        // 同款「掩码→替换→还原」两段式（哨兵非标识符字符，不影响下游词边界语义）。
        val (paramMaskedHql, paramLiterals) = JpaSqlLiteralMasking.mask(hql)
        val inClauseParams = findInClauseParams(paramMaskedHql)

        // 1. 替换命名参数 :param → ?
        // T-JpaParamOrder F3：替换前用同一正则收集名序（findAll 顺序=首现序=替换后 `?` 序），
        // 供渲染期按名回填；`::` 双冒号由正则负向断言排除，与替换语义一致。
        // 名序收集同在掩码文本上做（字面量内冒号不得计入参数名）。
        val placeholderParamNames = HQL_NAMED_PARAM_TO_Q.findAll(paramMaskedHql).map { it.groupValues[1] }.toList()
        var sql = replaceNamedParameters(paramMaskedHql)

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql)
        // 还原字面量（后续步骤各自再做掩码周期，行为不变）
        sql = JpaSqlLiteralMasking.unmask(sql, paramLiterals)

        // 3. 处理 IN 子句：IN ? → IN (?)
        sql = handleInClause(sql)

        // 4. 处理 `new Xxx(...)` 动态实例化表达式（关键字大小写不敏感）
        sql = NewExpressionStripper.strip(sql)

        // 5. 处理 JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件真实化）
        // 复用同一次查询的实体解析（含 @Entity(name)）；不可改用根实体文件作为 context。
        sql = JpaJoinRewriter.rewriteWithLookup(sql, table, lookup)

        // 6. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql)

        // 6.5 别名作用域解析（O-57）：须在实体名→表名替换之前扫描——那时 FROM/JOIN 后的
        // 实体标识符还在 SQL 里，替换成表名后 PSI 短名检索不再命中；
        // 又须在第 5 步 JOIN 改写之后——改写新增的副表别名只有那时才存在
        val scope = JpaAliasTableResolver.resolveAliases(sql, table, resolveTable)

        // 7. 实体名 → 表名映射
        if (!table.entityClassName.isBlank()) {
            sql = mapEntityToTable(sql, table, entityAlias ?: mappedRootName)
            // 8. 关联字段路径映射：alias.field.id → 外键列名（须在属性映射之前）
            sql = mapAssociationPath(sql, table)
            // 9. 属性名 → 列名映射（按别名作用域分派，副表别名同样映射）
            sql = mapPropertyToColumn(sql, table, scope)
        } else {
            // Fallback：实体不可解析时，从 FROM 子句提取实体名替换为 unknown_table
            sql = mapFallbackEntityToTable(sql, table.tableName)
        }

        // 8.5 子查询 FROM/JOIN 中其他实体名 → 表名（J13；需 Project，无则跳过）。
        // 别名作用域已解析出实体→表名，透传给扫描器复用，免二次 PSI 检索
        sql = JpaFromEntityMapper.mapSubqueryEntities(sql, table) { name ->
            scope.entityTableNames[name]
        }

        // 9. SELECT 子句展开（T-HQL-SELECT-01 / T-BareFromSelect）
        // 必须排在步骤 5（JOIN 改写）之后——JOIN 别名只有改写后才存在，
        // 早于该步解析会取不到副表别名。DELETE/UPDATE/INSERT/CTE/UNION 内部已短路。
        sql = SelectClauseExpander.expand(sql, table)

        // 10. 检测 DELETE HQL → DeleteStatement
        if (sql.trimStart().uppercase().startsWith("DELETE")) {
            // T-JpaDeleteWhereGuard：WHERE 接进条件树（与 UPDATE 同一套白名单 + Raw 兜底）——
            // 此前 conditions 恒空，buildJpaWarning 对带 WHERE 的 DELETE 误报 BlockAttack 全表删除
            val parsedDelete = JpaUpdateParser.parseDelete(sql)
            return Result(
                sql = sql,
                statement = DeleteStatement(
                    table = table,
                    conditions = parsedDelete?.conditions ?: emptyList(),
                ),
                inClauseParams = inClauseParams,
                placeholderParamNames = placeholderParamNames,
                degradations = degradationsOf(sql, null),
            )
        }

        // 10.5 检测 UPDATE HQL → UpdateStatement（J2）
        if (sql.trimStart().uppercase().startsWith("UPDATE")) {
            val parsed = JpaUpdateParser.parse(sql)
            if (parsed != null) {
                return Result(
                    sql = sql,
                    statement = UpdateStatement(
                        table = table,
                        sets = parsed.sets,
                        conditions = parsed.conditions,
                        tableAlias = parsed.tableAlias,
                    ),
                    inClauseParams = inClauseParams,
                    placeholderParamNames = placeholderParamNames,
                    degradations = degradationsOf(sql, null),
                )
            }
            // 头部结构异常（非法 HQL）→ 维持历史 SelectStatement 透传，不抛异常
        }

        val statementComment = advancedConstructComment(sql) ?: inheritanceBoundaryComment(table)
        return Result(
            sql = sql,
            statement = SelectStatement(
                table = table,
                comment = statementComment,
            ),
            inClauseParams = inClauseParams,
            placeholderParamNames = placeholderParamNames,
            degradations = degradationsOf(sql, statementComment),
        )
    }

    /**
     * T-PagesHql：从转换产物里收集降级说明。
     *
     * 转换器的降级信息以 SQL 注释/语句 comment 形式落地（「关联字段，需 JOIN …」等注释），
     * 这里按已知文案标记归一成人话清单——不改写任何转换行为，只让调用方能把「产物不可直接执行
     * 的原因」展示到面板上（用户不看 SQL 注释就不知道）。
     */
    private fun degradationsOf(sql: String, statementComment: String?): List<String> {
        val notes = LinkedHashSet<String>()
        if (!statementComment.isNullOrBlank()) {
            val mapped = Degradations.DEGRADATION_MARKERS.firstOrNull { statementComment.contains(it.first) }
            if (mapped != null) {
                notes += mapped.second
            } else {
                notes += statementComment.removeSurrounding("/* ", " */").take(120)
            }
        }
        Degradations.DEGRADATION_MARKERS.forEach { (marker, note) ->
            if (sql.contains(marker)) notes += note
        }
        return notes.toList()
    }

    private object Degradations {
        /** 标记 → 展示文案（顺序即输出顺序，保证产物稳定可断言）。 */
        val DEGRADATION_MARKERS: List<Pair<String, String>> = listOf(
            "关联字段，需 JOIN" to "关联字段路径需 JOIN（已保留原文）",
            "推断的ON条件，请验证" to "JOIN ON 条件为推断值，需人工验证",
            "实体不可解析" to "存在无法解析的实体（列清单未展开）",
            "ON 条件中的" to "ON 条件含未映射限定符",
            "HQL 高级特性" to "HQL 高级特性（CTE/UNION/INSERT-SELECT）仅透传",
            "@Inheritance(" to "继承策略与单表列口径不一致",
            "MEMBER OF" to "MEMBER OF 未转成 SQL",
            "副表" to "副表列元数据缺失，已按别名.* 降级",
        )
    }

    // ------------------------------------------------------------------
    // 参数替换
    // ------------------------------------------------------------------

    /**
     * 替换命名参数 `:paramName` → `?`（排除 `::` 类型转换运算符）。
     */
    private fun replaceNamedParameters(hql: String): String {
        return hql.replace(HQL_NAMED_PARAM_TO_Q, "?")
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     */
    private fun replacePositionalParameters(hql: String): String {
        return hql.replace(HQL_POSITIONAL_PARAM_TO_Q, "?")
    }

    // ------------------------------------------------------------------
    // IN 子句
    // ------------------------------------------------------------------

    /**
     * 检测 HQL 中使用 IN 子句的命名参数。
     */
    private fun findInClauseParams(hql: String): Set<String> {
        val pattern = Regex("(?i)\\bIN\\s+:([a-zA-Z_][a-zA-Z0-9_]*)")
        return pattern.findAll(hql).map { it.groupValues[1] }.toSet()
    }

    /**
     * 处理 `IN ?` → `IN (?)`。
     */
    private fun handleInClause(hql: String): String {
        return hql.replace(HQL_IN_SPACE_Q, "IN (?)")
    }

    // ------------------------------------------------------------------
    // JOIN FETCH / 关联 JOIN（J7：改写逻辑统一在 JpaJoinRewriter，ON 条件真实化）
    // ------------------------------------------------------------------

    /**
     * J19 高级特性降级注释：检测 HQL `WITH ... SELECT`（CTE）、UNION/INTERSECT/EXCEPT、
     * `INSERT INTO ... SELECT`。这些形态在字符串管道中透传（参数替换/实体替换仍生效，
     * SELECT 展开器已短路不误改），不新增完整建模，仅附注释说明边界。
     */
    private fun advancedConstructComment(sql: String): String? {
        val head = sql.trimStart().uppercase()
        val isInsertSelect = head.startsWith("INSERT")
        val hasCte = SelectClauseExpander.hasTopLevelKeyword(sql, "WITH")
        val hasSetOp = SelectClauseExpander.hasTopLevelKeyword(sql, "UNION") ||
            SelectClauseExpander.hasTopLevelKeyword(sql, "INTERSECT") ||
            SelectClauseExpander.hasTopLevelKeyword(sql, "EXCEPT")
        return if (isInsertSelect || hasCte || hasSetOp) {
            "/* HQL 高级特性（INSERT-SELECT / CTE / UNION 等），仅做参数与实体替换透传 */"
        } else {
            null
        }
    }

    /**
     * T-HIC-04：@Inheritance(JOINED/TABLE_PER_CLASS) 边界标注——只注释不改行为。
     * JOINED 把父类字段存于父表（SELECT 需跨表连接），TABLE_PER_CLASS 每子类独立表；
     * 本插件列清单按「父类字段合并进子表」的单表口径渲染，与实际建表不符时
     * 预览产物需自行改写。SINGLE_TABLE（默认）合并语义正确，不标注。
     */
    private fun inheritanceBoundaryComment(table: TableInfo): String? = when (table.inheritanceStrategy) {
        "JOINED", "TABLE_PER_CLASS" ->
            "/* @Inheritance(${table.inheritanceStrategy})：父类字段实际存于" +
                (if (table.inheritanceStrategy == "JOINED") "父表（JOIN 连接获取）" else "各子类独立表") +
                "，列清单按单表口径合并，执行前请按实际建表改写 */"
        else -> null
    }

    // ------------------------------------------------------------------
    // MEMBER OF
    // ------------------------------------------------------------------

    /**
     * 处理 `MEMBER OF` 语法（降级为注释提示）。
     */
    private fun handleMemberOf(hql: String): String {
        if (hql.contains("MEMBER OF", ignoreCase = true)) {
            return hql.replace(
                HQL_MEMBER_OF_PATTERN,
                "$1 /* MEMBER OF → 建议使用 IN/EXISTS 重写 */ $2",
            )
        }
        return hql
    }

    // ------------------------------------------------------------------
    // 实体名 → 表名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的实体类名映射为数据库表名。
     * 仅在 TableInfo 有效时调用。
     * T-HqlInhCols：`@Entity(name=...)` 实体别名同样命中替换（HQL `FROM Alias` 的
     * Alias 是实体名而非类名，JPA 语义）；别名经 entityAlias 参数传入，
     * resolve 链由 JpaMetadataReader.entityNameOf 读取。
     */
    private fun mapEntityToTable(hql: String, table: TableInfo, entityAlias: String? = null): String {
        if (table.entityClassName.isBlank()) return hql
        // 普查批：字面量掩码——`like '%UserEntity%'` 等单引号字面量内的实体名不得被替换
        val (masked, literals) = JpaSqlLiteralMasking.mask(hql)
        var result = masked
        // 根实体写 FQN 时不能顺带替换同名简单标识符（它仍可能有歧义）。
        if (entityAlias?.contains('.') != true) {
            entityPattern(table.entityClassName)?.let { result = it.replace(result, table.tableName) }
        }
        val alias = entityAlias?.takeIf { it.isNotBlank() && it != table.entityClassName }
        if (alias != null) {
            entityPattern(alias)?.let { result = it.replace(result, table.tableName) }
        }
        return JpaSqlLiteralMasking.unmask(result, literals)
    }

    /** 实体标识符全词匹配 Pattern（词边界防子串误伤；构造失败容错返回 null）。 */
    private fun entityPattern(identifier: String): Regex? =
        Regex("(?<![\\w.$])${Regex.escape(identifier)}\\b")

    // ------------------------------------------------------------------
    // 关联字段路径映射
    // ------------------------------------------------------------------

    /**
     * 处理关联字段路径：`alias.field.id` → `alias.joinColumn`。
     *
     * HQL 中关联字段路径的语义：
     * - `rti.ruleTable.id` → 关联字段 `ruleTable` 的主键，等价于外键列 `RULE_TABLE_ID`
     * - `rti.ruleTable.name` → 关联实体的 `name` 属性（需要 JOIN，暂用注释提示）
     *
     * 解析策略：
     * 1. 匹配 `alias.field.id` 三段式路径（两段式关联）
     * 2. 查找 `field` 对应的 `AssociationInfo`
     * 3. 将 `alias.field.id` 替换为 `alias.joinColumn`
     * 4. 对于 `alias.field.property`（非 id），添加注释提示需要 JOIN
     */
    private fun mapAssociationPath(hql: String, table: TableInfo): String {
        if (table.associations.isEmpty()) return hql

        // 普查批：字面量掩码——字面量内的 `x.<关联字段名>.y` 点号串不得被改写或注入注释
        val (masked, literals) = JpaSqlLiteralMasking.mask(hql)
        var result = masked
        // 匹配三段式路径：alias.fieldName.propertyName
        val threeLevelPattern = Regex(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b"
        )

        result = threeLevelPattern.replace(result) { match ->
            val alias = match.groupValues[1]
            val fieldName = match.groupValues[2]
            val propertyName = match.groupValues[3]

            val association = table.associationByField(fieldName)
            if (association != null) {
                if (propertyName.equals("id", ignoreCase = true)) {
                    // alias.field.id → alias.joinColumn（外键列）
                    "$alias.${association.joinColumn}"
                } else {
                    // alias.field.property → 需要 JOIN，添加注释提示
                    "$alias.$fieldName.$propertyName /* 关联字段，需 JOIN ${association.targetTable} */"
                }
            } else {
                // 非关联字段，保持原样
                match.value
            }
        }

        return JpaSqlLiteralMasking.unmask(result, literals)
    }

    // ------------------------------------------------------------------
    // 属性名 → 列名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的属性名映射为数据库列名（O-57 起按别名作用域分派）。
     *
     * 替换核心见 [JpaAliasPropertyMapper]（与 [JpqlConverter] 共用同一份实现）。
     * 仅在 TableInfo 有效时调用。
     */
    private fun mapPropertyToColumn(hql: String, table: TableInfo, scope: JpaAliasScope): String =
        JpaAliasPropertyMapper.mapProperties(hql, table, scope)

    /**
     * Fallback：实体不可解析时，从语句提取实体名替换为 unknown_table。
     * 仅替换第一个匹配（主表），避免重复替换导致 SQL 异常。
     * T-HqlUpdateEntityMap S1：实体名提取走共享提取器（语句头优先）——
     * UPDATE/DELETE 头同样命中，不再只认 FROM 子句。
     */
    private fun mapFallbackEntityToTable(hql: String, tableName: String): String {
        val entityName = HqlEntityNameExtractor.extract(hql) ?: return hql
        // 普查批：掩码 + 词边界——裸 replaceFirst 会命中更长标识符的前缀子串
        // （`OwnerInfo` 命中 `OwnerInfoHelper` → `unknown_tableHelper`）或字面量内容
        val (masked, literals) = JpaSqlLiteralMasking.mask(hql)
        val replaced = entityPattern(entityName)?.let { it.replaceFirst(masked, tableName) } ?: masked
        return JpaSqlLiteralMasking.unmask(replaced, literals)
    }

    /**
     * 创建兜底 TableInfo（当实体类不可解析时使用）。
     */
    private fun createFallbackTable(): TableInfo {
        return TableInfo(
            entityClassName = "",
            tableName = "unknown_table",
            columns = emptyList(),
        )
    }
}
