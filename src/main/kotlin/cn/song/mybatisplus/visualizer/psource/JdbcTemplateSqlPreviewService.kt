package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * JDBC 模板 SQL 字符串预览服务（正向入口）：
 * JdbcTemplateSqlLocator + SqlStringConcatenator + NamedParamParser → [PreviewResult]。
 *
 * 组装规则（对齐 [cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService.buildResult]）：
 *  - SQL 文本经 [SqlFormatter.format] 格式化（多行关键字换行），末尾加 `;`（与注解预览一致）
 *  - 命名参数（`:CUR_USER` / `:flowKey`）→ `unresolvedParams`（面板参数行）+ `namedParamHints`
 *  - 可执行 SQL 生成时走 [ParameterReplacer.replaceNamedParams]（按名回填）+ 残留 `?` 顺序回填
 *  - 含运行时拼片段 → `warning` 降级提示
 *
 * 入口：gutter 点击 / ConvertToSqlAction 光标定位 → [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.preview]
 */
object JdbcTemplateSqlPreviewService {

    /** SQL 末尾分隔符（与注解预览 / 日志还原一致）。 */
    private const val SQL_TERMINATOR = ";"

    /**
     * 预览 JDBC 模板 SQL 字符串（gutter / action 通用入口）。
     *
     * @param anchor 光标所在 PSI 元素
     * @param project 当前项目（保留扩展点，暂未使用）
     * @return 预览结果；未命中返回 null
     */
    fun preview(anchor: PsiElement, project: Project? = null): PreviewResult? {
        val call = JdbcTemplateSqlLocator.locate(anchor) ?: return null
        return preview(call, anchor)
    }

    /**
     * 收集方法体内全部可静态定位的字符串 SQL 调用候选（T-DaoSql-04，单一来源）。
     *
     * 供方法定义 gutter 点击（[cn.song.mybatisplus.visualizer.ui.gutter.StringSqlMethodGutterLineMarkerProvider]）
     * 与 action 方法定义 fallback 共用；须在后台线程 ReadAction 内调用。
     *
     * 候选 = 方法体内「方法名 ∈ 字符串 SQL 方法候选」且 [JdbcTemplateSqlLocator.locateFromCall]
     * 聚合成功的调用（按文件偏移升序，与方法内出现顺序一致）。聚合失败的调用
     * （SQL 为参数传递 / 运行时构造形态）不在候选内——MVP 边界，见开发计划「已知边界」。
     *
     * @param method 目标方法
     * @return 候选调用列表（可能为空）
     */
    fun collectMethodCandidates(method: PsiMethod): List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall> {
        val body = method.body ?: return emptyList()
        return PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .filter { JdbcTemplateMethodDetector.isStringSqlMethodCandidate(it.methodExpression.referenceName) }
            .mapNotNull { JdbcTemplateSqlLocator.locateFromCall(it) }
            .sortedBy { it.callExpression.textRange.startOffset }
    }

    /**
     * 预览从已定位的 JdbcTemplate 调用组装结果。
     *
     * @param call 已定位的 JdbcTemplate SQL 调用
     * @param anchor 原始锚点元素（用于错误信息）
     */
    fun preview(call: JdbcTemplateSqlLocator.JdbcTemplateSqlCall, anchor: PsiElement): PreviewResult? {
        val sqlText = call.sqlString
        if (sqlText.isBlank()) return null
        // SQL 形态校验：误将通用字符串方法（list.insert("x")、log.delete("...") 等）当 SQL 预览——
        // 仅在聚合文本像是可执行 SQL 时才接住，否则返回 null（对齐 T-M20-08 防误伤语义）
        if (!isLikelySql(sqlText)) return null

        val warnings = mutableListOf<String>()
        if (!call.isLiteral) {
            warnings += "SQL 含运行时拼接片段（方法返回值 / 外部字段等），完整 SQL 在运行期才确定；仅预览静态片段。"
        }

        // 收集命名参数
        val namedParams = NamedParamParser.collectNamedParams(sqlText)
        val namedSources = namedParams.map { it.name }
        val namedHints = namedParams.associate { it.name to NamedParamParser.quoteHint(it) }

        // `?` 占位符来源（按出现顺序，`?` 数量）——用 string-aware 位点扫描：块注释里的
        // 三元问号（`/* 运行时值: status == null ? … */`）与字符串字面量里的 `?` 都不是 JDBC 占位符。
        // 朴素 count 会造出没有落点的 `#param1`（T-RuntimeSlotOptionalFill）。
        val placeholderCount = placeholderPositions(sqlText).size
        val placeholderSources = if (placeholderCount > 0) {
            (1..placeholderCount).map { "param$it" }
        } else {
            emptyList()
        }

        // 合并参数来源：命名参数在前（按名回填），`?` 占位符在后（按序回填）
        val unresolvedParams = namedSources + placeholderSources

        // 合并引号提示（与 unresolvedParams 按序对应）
        val namedParamHintsList = namedParams.map { NamedParamParser.quoteHint(it) }
        val placeholderHints = placeholderSources.map { ParamHint() } // 无类型标注走面板启发式
        val paramHints = namedParamHintsList + placeholderHints

        // 格式化 SQL（多行关键字换行），末尾加分隔符
        val formattedSql = try {
            SqlFormatter.format(sqlText).trimEnd() + SQL_TERMINATOR
        } catch (e: Throwable) {
            sqlText.trimEnd() + SQL_TERMINATOR
        }
        val compactSql = sqlText.trimEnd() + SQL_TERMINATOR

        // 参数对照表（供 UI 展示）
        val paramTable = ParameterReplacer.buildParamTable(unresolvedParams)

        return PreviewResult(
            sql = formattedSql,
            compactSql = compactSql,
            unresolvedParams = unresolvedParams,
            paramTable = paramTable,
            wrapperType = null,
            tableName = extractFirstTableName(sqlText),
            warning = warnings.firstOrNull(),
            paramHints = paramHints,
            namedParamHints = namedHints,
            initialParamValues = emptyMap(),
            runtimeSlots = collectRuntimeSlots(call),
        )
    }

    /**
     * 重新聚合一次拿到结构化运行期占位（T-RuntimeSlotFill 券 A）。
     *
     * 为什么要重算：[JdbcTemplateSqlLocator.JdbcTemplateSqlCall] 只带聚合后的**文本**，而 slot 的
     * id、模板、类别只能由拼接器产出。重算对同一 PSI 是确定性的，且与 `call.sqlString` 逐字相同；
     * 一旦两者不一致（理论上不该发生——两边走的是同一个 [SqlStringConcatenator.concat]），
     * 就放弃 slot：宁可面板上没有可填值，也不能给出一份与 SQL 文本错位的身份。
     */
    private fun collectRuntimeSlots(
        call: JdbcTemplateSqlLocator.JdbcTemplateSqlCall,
    ): List<RuntimeSlot> {
        val aggregated = SqlStringConcatenator.concat(call.sqlExpr) ?: return emptyList()
        if (aggregated.sql != call.sqlString) return emptyList()
        return aggregated.runtimeSlots
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** SQL 语句特征关键字（转小写比对）。匹配任意即视为 SQL，避免把通用字符串方法当 SQL 预览。 */
    private val SQL_KEYWORDS = listOf(
        "select", "insert", "update", "delete", "with", "values", "create", "alter",
        "drop", "truncate", "merge", "replace", "grant", "revoke", "show", "describe",
        "explain", "call", "begin", "commit", "rollback",
    )

    /** 语句内部结构词（不限于开头，覆盖以 `(`/注释开头的 SQL、子查询等）。 */
    private val SQL_FRAGMENTS = listOf(
        " from ", " into ", " set ", " where ", " join ", " left join ", " right join ",
        " order by ", " group by ", " having ", " limit ", " union ", " values (",
        " select ", " insert into ", " update ", " delete from ",
    )

    /**
     * SQL 形态校验：聚合后的字符串是否**像可执行的 SQL**。
     *
     * 防误成分略（对齐 T-M20-08）：`insert`/`delete`/`update` 等是通用方法名，
     * `list.insert("x")`、`log.delete("...")` 这类第 1 实参为普通字符串的调用不应触发
     * SQL 预览。判定规则（大小写不敏感，去掉首尾空白后）：
     *  - 以 SQL 语句关键字开头（select/insert/update/delete/with...）
     *  - 或含语句内结构词（from/into/set/where/join...）——覆盖以 `(`/注释开头的情形
     * 两者皆不满足 → false（非 SQL，不预览）。
     */
    private fun isLikelySql(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val lower = t.lowercase()
        if (SQL_KEYWORDS.any { lower.startsWith(it) }) return true
        if (SQL_FRAGMENTS.any { lower.contains(it) }) return true
        return false
    }

    /** 启发式提取首个表名（FROM / JOIN 后首个标识符）；提取失败返回 null。 */
    private fun extractFirstTableName(sql: String): String? {
        val upper = sql.uppercase()
        val fromIdx = upper.indexOf("FROM")
        if (fromIdx < 0) return null
        val afterFrom = sql.substring(fromIdx + 4).trimStart()
        // 取首个非空白字符序列（表名或子查询 `(`）
        val tokens = afterFrom.split(Regex("\\s+"))
        val firstToken = tokens.firstOrNull()?.trim() ?: return null
        if (firstToken.startsWith("(")) return null // 子查询，暂不展开
        // 去除可能的别名（如 `WKFL_TASK WT` → `WKFL_TASK`）
        return firstToken.substringBefore('`').substringBefore('[').ifBlank { null }
    }
}
