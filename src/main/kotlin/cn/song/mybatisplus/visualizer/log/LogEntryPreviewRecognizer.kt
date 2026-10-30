package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * 日志条目 → 预览弹窗参数识别（T-M42）：双击日志 SQL 时把 `?` 占位符自动识别为可编辑参数行。
 *
 * 匹配判定与 [LogSqlRestorer] 的裸 `indexOf('?')` 替换语义自洽：
 * `rawSql` 中 `?` 数 == `params.size` ⇔ `restoredSql` 无残留 `?`（等价于弹窗现有未解析警告条件）。
 * 识别成功时参数行预填日志参数值，SQL 初始显示与现状（值内联）逐字符一致，用户改值才生效。
 *
 * 未识别（params 空 / 数量不匹配 / 无 `?`）：`recognized=false`，`sql`/`compactSql` 仍为
 * `rawSql`——由调用方（openPreview）决定回退 `restoredSql`（现状行为）。
 */
data class RecognizedLogPreview(
    /** rawSql + ';'（格式化由 openPreview 按设置做）；未识别时仍为 rawSql。 */
    val sql: String,
    /** rawSql + ';'；未识别时仍为 rawSql。 */
    val compactSql: String,
    /** 按 `?` 出现顺序的参数来源（"param1"..."paramN"）；未识别为空。 */
    val unresolvedParams: List<String>,
    /** "paramN" → 日志参数值（null 参数预填空串）；未识别为空。 */
    val initialValues: Map<String, String>,
    /** 与 [unresolvedParams] 并行的回填提示（类型 → 引号，见 [LogSqlParam.toHint]）；未识别为空。 */
    val paramHints: List<ParamHint>,
    /** true=匹配识别（`?` 数 == 参数数且 > 0）；false=回退现状。 */
    val recognized: Boolean,
)

/**
 * 识别日志条目为预览弹窗参数形态。
 *
 * 参数命名：日志无参数名，用序号 `param1..N`（对齐 MP 生成器 `param1` 风格）；
 * 值预填 `LogSqlParam.value`（null → 空串，回填显示 `?`——与还原 `NULL` 不一致的边界见开发计划 §7）。
 *
 * T-ListParamCollect：命名参数条目（`:paramN`）无 `?` 占位符，`recognized=false` 走回退分支——
 * 但其 `initialValues` 提供参数名 → 日志值映射（集合值为元素清单），供预览面板按名回填。
 */
fun LogSqlEntry.toRecognizedPreview(): RecognizedLogPreview {
    if (source == SqlSource.SHARDINGSPHERE) {
        return RecognizedLogPreview(rawSql, rawSql, emptyList(), emptyMap(), emptyList(), false)
    }
    val placeholderCount = countPlaceholders(rawSql)
    val recognized = placeholderCount > 0 && placeholderCount == params.size
    // T-ListParamCollect：命名参数条目（`sql = … :paramN` + 日志集合值）无 `?` 占位符，
    // 不进参数行通道；其 SQL 由解析器按同口径还原，此处只负责把集合值还原为元素清单，
    // 供参数行预填（面板既有 inList 逗号分支渲染出与还原 SQL 一致的形态）。
    val namedInClause = if (rawSql.contains(':')) ListParamRenderer.inClauseParamNames(rawSql) else emptySet()
    if (!recognized) {
        return RecognizedLogPreview(
            sql = rawSql,
            compactSql = rawSql,
            unresolvedParams = emptyList(),
            initialValues = namedInitialValues(namedInClause),
            paramHints = emptyList(),
            recognized = false,
        )
    }
    val sources = (1..placeholderCount).map { "param$it" }
    val initialValues = sources.zip(params.map { it.value ?: "" }).toMap()
    val paramHints = params.map { it.toHint() }
    return RecognizedLogPreview(
        sql = rawSql,
        compactSql = rawSql,
        unresolvedParams = sources,
        initialValues = initialValues,
        paramHints = paramHints,
        recognized = true,
    )
}

/**
 * T-ListParamCollect：命名参数条目的参数名 → 预填值（集合值剥括号为逗号分隔元素清单）。
 *
 * 顺序口径与 [JdbcTemplateLogParser.extractNamedParamEntry] 收集 `params` 时**逐字一致**：
 * 都是按 SQL 中 `:name` 的出现顺序（重复出现则都收，位置一一对应）。两侧必须同源，
 * 否则参数值会整体错位（`param3` 拿到 `param1` 的值）。
 *
 * 仅当 SQL 含命名参数（`:name`）时产出；普通 `?` 条目与裸 SQL 返回空表（零行为变化）。
 */
private fun LogSqlEntry.namedInitialValues(inClauseNames: Set<String>): Map<String, String> {
    if (params.isEmpty()) return emptyMap()
    val refs = NAMED_PARAM_REF.findAll(rawSql).map { it.groupValues[1] }.toList()
    if (refs.isEmpty() || refs.size != params.size) return emptyMap()
    val values = refs.zip(params).mapNotNull { (name, param) ->
        val value = param.value ?: return@mapNotNull null
        val elements = if (name in inClauseNames) ListParamRenderer.splitListElements(value) else null
        name to (elements?.joinToString(", ") ?: value)
    }
    return values.toMap()
}

/** 命名参数引用模式：`:name`（与 [JdbcTemplateLogParser] 的收集口径一致）。 */
private val NAMED_PARAM_REF = Regex(""":(\w+)""")

/**
 * T-HibDualCapture：string-aware `?` 占位符计数——口径与 [placeholderPositions]（还原回填）
 * 完全一致，字符串字面量与注释内的 `?` 不计数，识别数量与回填位置不再错位。
 *
 * T-PSG-01：位点实现已上提至 `sql` 包，本函数保留在 `log` 包作为薄封装（本包多个消费方与
 * 测试按本名可达，搬迁会破坏这些符号依赖）。
 */
internal fun countPlaceholders(sql: String): Int = placeholderPositions(sql).size

/**
 * 日志条目 → 预览结果（T-HistParam F3：SQL 日志 tab 与 SQL 历史 tab 的统一识别通道）。
 *
 * 三级分流：
 * 1. 占位符匹配识别（[toRecognizedPreview]，`?` 数 == 参数数）→ 参数行预填日志值；
 * 2. 字面量参数恢复（[recoverLiteralParams]，rawSql 零 `?` 且 [literalRecovery] 开启，
 *    运行期逐字符一致性熔断 [verifyLiteralRoundTrip]）→ 字面量改写为 `?` 复用参数管线；
 * 3. 回退现状（restoredSql + 零参数/数量不匹配警告分流，零 `?` 无警告）。
 *
 * 纯函数无平台依赖（开关由调用方读设置传入）：SqlLogPanel 与 HistorySqlLogPanel 均传
 * formatSql=设置值（T-ListPreviewCopy：两页预览统一跟随「预览时格式化还原 SQL」，默认开）。
 */
internal fun LogSqlEntry.toPreviewResult(
    formatSql: Boolean,
    literalRecovery: Boolean,
    capturedDialect: String? = null,
): PreviewResult {
    // T-DialectInferFix：本次执行连接的方言是本条预览最强证据（O-145 方案第 2 条）。
    // 优先用 pd 载荷里的注册方言名（dl 键）；旧条目退用持久化的目标库角标（`db: PostgreSQL · …`）。
    val dialect = cn.song.mybatisplus.visualizer.sql.PreviewDialectResolver.knownDialect(capturedDialect)
        ?: cn.song.mybatisplus.visualizer.sql.PreviewDialectResolver.dialectFromBadge(databaseName)
    val decision = dialect?.let {
        cn.song.mybatisplus.visualizer.sql.PreviewDialectResolver.capturedDecision(it)
    }
    val base = toPreviewResultInternal(formatSql, literalRecovery)
        .copy(actualSql = actualSql?.takeIf { source == SqlSource.SHARDINGSPHERE && it.isNotBlank() })
        .withExecutionError(errorText)
    if (dialect == null) return base
    return base.copy(
        renderedDialect = cn.song.mybatisplus.visualizer.sql.DialectRegistry.byName(dialect),
        dialectDecision = decision,
    )
}

/**
 * T-M55-02：执行报错全文并入预览警示槽——列表行⛔片段之外，预览详情承载完整错误文本：
 * meta 行红色警示（宽度预算截断展示）+ 横幅 + 「复制错误」按钮一键复制全文（copyError 复制
 * currentWarning，即本拼接结果）。既有参数未还原警示保留（报错在前，换行分隔）。
 */
private fun PreviewResult.withExecutionError(errorText: String?): PreviewResult {
    val err = errorText?.takeIf { it.isNotBlank() } ?: return this
    val prefix = MpSqlVisualizerBundle.message("log.preview.executionError")
    val joined = listOfNotNull(prefix + err, warning).joinToString("\n")
    return copy(warning = joined)
}

private fun LogSqlEntry.toPreviewResultInternal(
    formatSql: Boolean,
    literalRecovery: Boolean,
): PreviewResult {
    // Actual 是物理 SQLUnit，不是 Logic 的绑定证据。旧缓存中曾猜出的参数也不预填。
    if (source == SqlSource.SHARDINGSPHERE) {
        return PreviewResult(
            sql = formatIfNeeded(withTerminator(rawSql), formatSql),
            compactSql = rawSql,
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = null,
            stackDigest = stackDigest,
            threadName = threadName,
            warning = MpSqlVisualizerBundle.message("log.preview.unresolvedNoBinding.sharding"),
        )
    }
    val recognized = toRecognizedPreview()
    if (recognized.recognized) {
        return PreviewResult(
            sql = formatIfNeeded(withTerminator(recognized.sql), formatSql),
            compactSql = withTerminator(recognized.compactSql),
            // T-StackHoverUi：识别分支与兜底分支同权携带调用栈快照——带参数条目（占位符==参数数的
            // 主路径）此前在此丢快照，meta 行摘要与悬停弹层对最常见的带参数条目不可达
            stackDigest = stackDigest,
            threadName = threadName,
            unresolvedParams = recognized.unresolvedParams,
            paramTable = null,
            wrapperType = null,
            tableName = null,
            warning = null,
            paramHints = recognized.paramHints,
            initialParamValues = recognized.initialValues,
        )
    }
    // 字面量恢复只接「零占位符」分支（裸 SQL / ${} / 上游日志已内联值）；数量不匹配
    // （残留 `?`）维持现状警告，不做两套占位符的混合恢复
    //
    // T-DdlLogPreview S1（20261006 第三轮走查，用户实测 DDL 误伤）：**语句类型门禁**——
    // 字面量恢复的前提是「这些字面量是有值可编辑的参数」，只对 QUERY/DML 成立。DDL
    // （CREATE/ALTER/DROP/TRUNCATE）的字符串是**注释/默认值**：`CREATE TABLE t (id BIGINT
    // COMMENT '主键', …)` 曾被恢复出 param1..paramN（值＝注释文本），更严重的是恢复命中时
    // `sql/compactSql` 用**占位符化后的文本**（`COMMENT ?`）——该 SQL 语法不合法，预览看着
    // 怪、执行必报错，用户改参数行等于在改注释。UNKNOWN（判不出类型）同样不猜，fail-closed。
    // 分类器复用 execute/SqlTypeClassifier（QUERY/DML/DDL/UNKNOWN），不自写关键字表。
    val statementType = runCatching { SqlTypeClassifier.classify(rawSql) }
        .getOrDefault(SqlTypeClassifier.SqlType.UNKNOWN)
    val literalRecoverable = statementType == SqlTypeClassifier.SqlType.QUERY ||
        statementType == SqlTypeClassifier.SqlType.DML
    if (literalRecovery && literalRecoverable && countPlaceholders(rawSql) == 0) {
        recoverLiteralParams(rawSql)?.let { recovery ->
            if (verifyLiteralRoundTrip(rawSql, recovery)) {
                return PreviewResult(
                    sql = formatIfNeeded(withTerminator(recovery.placeholderSql), formatSql),
                    compactSql = withTerminator(recovery.placeholderSql),
                    // T-StackHoverUi：同上，字面量恢复分支同权携带快照
                    stackDigest = stackDigest,
                    threadName = threadName,
                    unresolvedParams = recovery.unresolvedParams,
                    paramTable = null,
                    wrapperType = null,
                    tableName = null,
                    warning = null,
                    paramHints = recovery.paramHints,
                    initialParamValues = recovery.initialValues,
                )
            }
        }
    }
    val base = withTerminator(restoredSql)
    return PreviewResult(
        sql = formatIfNeeded(base, formatSql),
        compactSql = restoredSql,
        stackDigest = stackDigest,
        threadName = threadName,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        // T-HibDualCapture：文案分流——零参数是「绑定行没抓到」（引导开 TRACE），
        // 有参数但数量不齐才是「数量不匹配」；`?` 计数 string-aware
        // T-JdbcThreadBucket F2：零参数时按来源分流——JdbcTemplate 引导开 org.springframework.jdbc.core，
        // Hibernate 引导开 type.descriptor.sql（避免把 JDBC 条目误导向 Hibernate 配置）
        warning = if (countPlaceholders(restoredSql) > 0) {
            val noBindingKey = when (source) {
                SqlSource.JDBC -> "log.preview.unresolvedNoBinding.jdbc"
                SqlSource.SHARDINGSPHERE -> "log.preview.unresolvedNoBinding.sharding"
                else -> "log.preview.unresolvedNoBinding"
            }
            MpSqlVisualizerBundle.message(
                if (params.isEmpty()) noBindingKey
                else "log.preview.unresolvedWarning",
            )
        } else {
            null
        },
    )
}

private fun formatIfNeeded(sql: String, formatSql: Boolean): String =
    if (formatSql) SqlFormatter.format(sql) else sql
