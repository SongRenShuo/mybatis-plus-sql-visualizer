package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code LogEntryPreviewRecognizer.kt} 顶层扩展函数（{@code LogSqlEntry.toRecognizedPreview()}）的 Java 承载类。
 */
public final class LogEntryPreviewRecognizerKt {
    private LogEntryPreviewRecognizerKt() {
    }

    /**
     * 识别日志条目为预览弹窗参数形态。
     *
 * 参数命名：日志无参数名，用序号 {@code param1..N}（对齐 MP 生成器 {@code param1} 风格）；
 * 值预填 {@link LogSqlParam#value()}（null → 空串，回填显示 {@code ?}——与还原 {@code NULL} 不一致的边界见开发计划 §7）。
 *
 * T-ListParamCollect：命名参数条目（{@code :paramN}）无 {@code ?} 占位符，{@code recognized=false} 走回退分支——
 * 但其 {@code initialValues} 提供参数名 → 日志值映射（集合值为元素清单），供预览面板按名回填。
 */
public static RecognizedLogPreview toRecognizedPreview(LogSqlEntry entry) {
    long placeholderCount = countPlaceholders(entry.rawSql());
    boolean recognized = placeholderCount > 0 && placeholderCount == entry.params().size();
    // T-ListParamCollect：命名参数条目（`sql = … :paramN` + 日志集合值）无 `?` 占位符，
    // 不进参数行通道；其 SQL 由解析器按同口径还原，此处只负责把集合值还原为元素清单，
    // 供参数行预填（面板既有 inList 逗号分支渲染出与还原 SQL 一致的形态）。
    java.util.Set<String> namedInClause = entry.rawSql().contains(":")
            ? ListParamRenderer.inClauseParamNames(entry.rawSql()) : java.util.Set.of();
    // R90 附注：ShardingSphere 路由日志原文（actualSql）不作识别通道输入——物理 SQLUnit 的绑定
    // 证据在应用侧，IDE 侧不猜。原文原样透传，零参数行。
    if (entry.source() == SqlSource.SHARDINGSPHERE) {
        return new RecognizedLogPreview(
                entry.rawSql(),
                entry.rawSql(),
                List.of(),
                java.util.Map.of(),
                List.of(),
                false);
    }
    if (!recognized) {
        return new RecognizedLogPreview(
                entry.rawSql(),
                entry.rawSql(),
                List.of(),
                namedInitialValues(entry, namedInClause),
                List.of(),
                false);
    }
        List<String> sources = new ArrayList<>();
        for (int i = 1; i <= placeholderCount; i++) {
            sources.add("param" + i);
        }
        List<String> values = entry.params().stream().map(p -> p.value() != null ? p.value() : "").toList();
        Map<String, String> initialValues = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            initialValues.put(sources.get(i), values.get(i));
        }
        List<ParamHint> paramHints = entry.params().stream().map(LogSqlRestorerKt::toHint).toList();
        return new RecognizedLogPreview(
                entry.rawSql(),
                entry.rawSql(),
                sources,
                initialValues,
                paramHints,
                true);
    }

    /**
     * T-ListParamCollect：命名参数条目的参数名 → 预填值（集合值剥括号为逗号分隔元素清单）。
     *
     * 顺序口径与 {@link JdbcTemplateLogParser#extractNamedParamEntry} 收集 {@code params} 时<b>逐字一致</b>：
     * 都是按 SQL 中 {@code :name} 的出现顺序（重复出现则都收，位置一一对应）。两侧必须同源，
     * 否则参数值会整体错位（{@code param3} 拿到 {@code param1} 的值）。
     *
     * 仅当 SQL 含命名参数（{@code :name}）时产出；普通 {@code ?} 条目与裸 SQL 返回空表（零行为变化）。
     */
    private static java.util.Map<String, String> namedInitialValues(LogSqlEntry entry, java.util.Set<String> inClauseNames) {
        if (entry.params().isEmpty()) return Map.of();
        java.util.List<String> refs = new ArrayList<>();
        java.util.regex.Matcher m = NAMED_PARAM_REF.matcher(entry.rawSql());
        while (m.find()) {
            refs.add(m.group(1));
        }
        if (refs.isEmpty() || refs.size() != entry.params().size()) return Map.of();
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            String name = refs.get(i);
            LogSqlParam param = entry.params().get(i);
            String value = param.value();
            if (value == null) continue;
            // splitListElements 对非方括号形态返 null（master 语义）→ 回落原始值
            java.util.List<String> elements = inClauseNames.contains(name)
                    ? ListParamRenderer.splitListElements(value)
                    : null;
            values.put(name, elements != null ? String.join(", ", elements) : value);
        }
        return values;
    }
    /** 命名参数引用模式：{@code :name}（与 {@link JdbcTemplateLogParser} 的收集口径一致）。 */
    private static final java.util.regex.Pattern NAMED_PARAM_REF = java.util.regex.Pattern.compile(":(\\w+)");

    /**
     * T-HibDualCapture：string-aware `?` 占位符计数——口径与 {@link PlaceholderPositionsKt#placeholderPositions(String)}
     * （还原回填）完全一致，字符串字面量与注释内的 {@code ?} 不计数，识别数量与回填位置不再错位。
     *
     * <p>T-PSG-01：位点实现已上提至 {@code sql} 包，本函数保留在 {@code log} 包作为薄封装（本包多个消费方与
     * 测试按本名可达，搬迁会破坏这些符号依赖）。</p>
     */
    public static long countPlaceholders(String sql) {
        return PlaceholderPositionsKt.placeholderPositions(sql).size();
    }

    /**
     * 日志条目 → 预览结果（T-HistParam F3：SQL 日志 tab 与 SQL 历史 tab 的统一识别通道）。
     * T-M55-02：识别结果统一并入执行报错警示（见 {@link #withExecutionError}）。
     *
     * 三级分流：
     * 1. 占位符匹配识别（toRecognizedPreview，`?` 数 == 参数数）→ 参数行预填日志值；
     * 2. 字面量参数恢复（recoverLiteralParams，rawSql 零 `?` 且 literalRecovery 开启，
     *    运行期逐字符一致性熔断 verifyLiteralRoundTrip）→ 字面量改写为 `?` 复用参数管线；
     * 3. 回退现状（restoredSql + 零参数/数量不匹配警告分流，零 `?` 无警告）。
     *
     * 纯函数无平台依赖（开关由调用方读设置传入）：SqlLogPanel 与 HistorySqlLogPanel 均传
     * formatSql=设置值（T-ListPreviewCopy：两页预览统一跟随「预览时格式化还原 SQL」，默认开）。
     */
    public static PreviewResult toPreviewResult(LogSqlEntry entry, boolean formatSql, boolean literalRecovery) {
        return toPreviewResult(entry, formatSql, literalRecovery, null);
    }

    /** capturedDialect 形态（master 默认参数 null 的 Java 重载等价）。 */
    public static PreviewResult toPreviewResult(LogSqlEntry entry, boolean formatSql, boolean literalRecovery,
                                                String capturedDialect) {
        // T-DialectInferFix：本次执行连接的方言是本条预览最强证据（O-145 方案第 2 条）。
        // 优先用 pd 载荷里的注册方言名（dl 键）；旧条目退用持久化的目标库角标（`db: PostgreSQL · …`）。
        String dialect = cn.song.mybatisplus.visualizer.sql.PreviewDialectResolver.knownDialect(capturedDialect);
        if (dialect == null) {
            dialect = cn.song.mybatisplus.visualizer.sql.PreviewDialectResolver.dialectFromBadge(entry.databaseName());
        }
        cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision decision = dialect != null
                ? cn.song.mybatisplus.visualizer.sql.PreviewDialectResolver.capturedDecision(dialect)
                : null;
        PreviewResult base = toPreviewResultInternal(entry, formatSql, literalRecovery);
        // R90：ShardingSphere 路由日志原文，只读附注，不参与 SQL 编辑、复制或执行
        if (entry.source() == SqlSource.SHARDINGSPHERE
                && entry.actualSql() != null && !entry.actualSql().isBlank()) {
            base.setActualSql(entry.actualSql());
        }
        base = withExecutionError(base, entry.errorText());
        if (dialect == null) {
            return base;
        }
        return copyPreviewResult(base, base.getThreadName(),
                cn.song.mybatisplus.visualizer.sql.DialectRegistry.INSTANCE.byName(dialect), decision,
                base.getWarning());
    }

    /**
     * T-M55-02：执行报错全文并入预览警示槽——列表行⛔片段之外，预览详情承载完整错误文本：
     * meta 行红色警示（宽度预算截断展示）+ 横幅 + 「复制错误」按钮一键复制全文（copyError 复制
     * currentWarning，即本拼接结果）。既有参数未还原警示保留（报错在前，换行分隔）。
     */
    private static PreviewResult withExecutionError(PreviewResult result, String errorText) {
        if (errorText == null || errorText.isBlank()) return result;
        String prefix = MpSqlVisualizerBundle.message("log.preview.executionError");
        List<String> parts = new ArrayList<>();
        parts.add(prefix + errorText);
        if (result.getWarning() != null) {
            parts.add(result.getWarning());
        }
        String joined = String.join("\n", parts);
        // master 为 copy(warning=…)（Kotlin copy 保留全部未触及字段）。k2j PreviewResult 的历史
        // 20 参 copy 重载会把 threadName/renderedDialect/dialectDecision/actualSql 归零——照用会让
        // T-StackBizFrame S3/T-DialectInferFix 的透传在报错条目上丢失，改走全参重建。
        return copyPreviewResult(result, result.getThreadName(), result.getRenderedDialect(),
                result.getDialectDecision(), joined);
    }

    /**
     * 全参重建拷贝（master Kotlin {@code copy} 语义的 Java 等价）：按 35 参规范构造器逐组件透传，
     * 仅覆写调用方显式给出的 threadName/renderedDialect/dialectDecision/warning。
     */
    private static PreviewResult copyPreviewResult(PreviewResult r, String threadName,
                                                   cn.song.mybatisplus.visualizer.sql.Dialect renderedDialect,
                                                   cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision dialectDecision,
                                                   String warning) {
        return new PreviewResult(
                r.getSql(), r.getCompactSql(), r.getUnresolvedParams(), r.getParamTable(),
                r.getWrapperType(), r.getTableName(), warning, r.getColumns(),
                r.getColumnComments(), r.getTableComment(), r.getPreviewModel(),
                r.getParamHints(), r.getCandidateTables(), r.getBatchModel(),
                r.getXmlModel(), r.getInitialParamValues(), r.getPerfWarnings(),
                r.getFillClauses(), r.getPreviousSql(), r.getPagesColumns(),
                r.getNamedParamHints(), r.getStackDigest(), threadName, r.isHasDollarRawSegments(),
                r.getCountSql(), r.getPaginationLimitLine(), r.getPlaceholderParamNames(),
                r.getDuplicateParamNames(), r.getDiagramStatement(), r.getHistoryFingerprintSeed(),
                r.getPaginationParamSources(), renderedDialect, dialectDecision,
                r.getHistoryEntryId(), r.getActualSql());
    }

    /** T-StackBizFrame S3：内部构造结果补线程名（PreviewResult 历史 copy 重载不携带该参）。 */
    private static PreviewResult withThreadName(PreviewResult r, String threadName) {
        return copyPreviewResult(r, threadName, r.getRenderedDialect(), r.getDialectDecision(), r.getWarning());
    }

    private static PreviewResult toPreviewResultInternal(LogSqlEntry entry, boolean formatSql, boolean literalRecovery) {
        RecognizedLogPreview recognized = toRecognizedPreview(entry);
        if (recognized.recognized()) {
            // T-StackHoverUi：识别分支与兜底分支同权携带调用栈快照——带参数条目（占位符==参数数的
            // 主路径）此前在此丢快照，meta 行摘要与悬停弹层对最常见的带参数条目不可达
            return withThreadName(new PreviewResult(
                formatIfNeeded(SqlLogPanelKt.withTerminator(recognized.sql()), formatSql),
                SqlLogPanelKt.withTerminator(recognized.compactSql()),
                recognized.unresolvedParams(),
                null,
                null,
                null,
                null,
                List.of(),
                Map.of(),
                null,
                null,
                recognized.paramHints(),
                List.of(),
                null,
                null,
                recognized.initialValues(),
                null,
                null,
                null,
                null,
                Map.of(),
                entry.stackDigest(),
                false,
                List.of(),
                Set.of()), entry.threadName());
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
        SqlTypeClassifier.SqlType statementType;
        try {
            statementType = SqlTypeClassifier.classify(entry.rawSql());
        } catch (Throwable t) {
            statementType = SqlTypeClassifier.SqlType.UNKNOWN;
        }
        boolean literalRecoverable = statementType == SqlTypeClassifier.SqlType.QUERY
            || statementType == SqlTypeClassifier.SqlType.DML;
        if (literalRecovery && literalRecoverable && countPlaceholders(entry.rawSql()) == 0) {
            LiteralParamRecovery recovery = LiteralParamRecoveryKt.recoverLiteralParams(entry.rawSql());
            if (recovery != null && LiteralParamRecoveryKt.verifyLiteralRoundTrip(entry.rawSql(), recovery)) {
                // T-StackHoverUi：同上，字面量恢复分支同权携带快照
                return withThreadName(new PreviewResult(
                    formatIfNeeded(SqlLogPanelKt.withTerminator(recovery.placeholderSql()), formatSql),
                    SqlLogPanelKt.withTerminator(recovery.placeholderSql()),
                    recovery.unresolvedParams(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    recovery.paramHints(),
                    List.of(),
                    null,
                    null,
                    recovery.initialValues(),
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    entry.stackDigest(),
                    false,
                    List.of(),
                    Set.of()), entry.threadName());
            }
        }
        // Actual 是物理 SQLUnit，不是 Logic 的绑定证据。旧缓存中曾猜出的参数也不预填。
        if (entry.source() == SqlSource.SHARDINGSPHERE) {
            return withThreadName(new PreviewResult(
                formatIfNeeded(SqlLogPanelKt.withTerminator(entry.rawSql()), formatSql),   // sql
                entry.rawSql(),                    // compactSql
                List.of(),                         // unresolvedParams
                null,                              // paramTable
                null,                              // wrapperType
                null,                              // tableName
                MpSqlVisualizerBundle.message("log.preview.unresolvedNoBinding.sharding"),
                List.of(),                         // columns
                Map.of(),                          // columnComments
                null,                              // tableComment
                null,                              // previewModel
                List.of(),                         // paramHints
                List.of(),                         // candidateTables
                null,                              // batchModel
                null,                              // xmlModel
                Map.of(),                          // initialParamValues
                List.of(),                         // perfWarnings
                null,                              // fillClauses
                null,                              // previousSql
                List.of(),                         // pagesColumns
                Map.of(),                          // namedParamHints
                entry.stackDigest(),               // stackDigest
                false,                             // hasDollarRawSegments
                null, null,                        // countSql / paginationLimitLine
                List.of(), Set.of(),               // placeholderParamNames / duplicateParamNames
                null, null), entry.threadName());  // diagramStatement / historyFingerprintSeed
        }
        String base = SqlLogPanelKt.withTerminator(entry.restoredSql());
        // T-HibDualCapture：文案分流——零参数是「绑定行没抓到」（引导开 TRACE），
        // 有参数但数量不齐才是「数量不匹配」；`?` 计数 string-aware
        // T-JdbcThreadBucket F2：零参数时按来源分流——JdbcTemplate 引导开 org.springframework.jdbc.core，
        // Hibernate 引导开 type.descriptor.sql（避免把 JDBC 条目误导向 Hibernate 配置）
        String warning = countPlaceholders(entry.restoredSql()) > 0
            ? MpSqlVisualizerBundle.message(entry.params().isEmpty()
                ? (entry.source() == SqlSource.JDBC
                    ? "log.preview.unresolvedNoBinding.jdbc"
                    : entry.source() == SqlSource.SHARDINGSPHERE
                        ? "log.preview.unresolvedNoBinding.sharding"
                        : "log.preview.unresolvedNoBinding")
                : "log.preview.unresolvedWarning")
            : null;
        // master 具名参数新增 stackDigest = stackDigest；Java 按全参构造位置传入
        // （T-SourceJumpFunnel：条目调用栈快照透传预览详情——meta 行栈节点展示）
        return withThreadName(new PreviewResult(
            formatIfNeeded(base, formatSql),   // sql
            entry.restoredSql(),               // compactSql
            List.of(),                         // unresolvedParams
            null,                              // paramTable
            null,                              // wrapperType
            null,                              // tableName
            warning,
            List.of(),                         // columns
            Map.of(),                          // columnComments
            null,                              // tableComment
            null,                              // previewModel
            List.of(),                         // paramHints
            List.of(),                         // candidateTables
            null,                              // batchModel
            null,                              // xmlModel
            Map.of(),                          // initialParamValues
            null,                              // perfWarnings
            null,                              // fillClauses
            null,                              // previousSql
            null,                              // pagesColumns
            Map.of(),                          // namedParamHints
            entry.stackDigest(),
            false,
            List.of(),                         // placeholderParamNames
            Set.of()), entry.threadName());    // duplicateParamNames
    }

    private static String formatIfNeeded(String sql, boolean formatSql) {
        return formatSql ? SqlFormatter.format(sql) : sql;
    }
}
