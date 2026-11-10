package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.share.ui.ShareDialog
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.NOTIFICATION_GROUP_ID
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.WHITESPACE_REGEX
// T-M53-II：扩展函数看不见接收者类的**嵌套类型**（与 companion 同规则），须显式导入。
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.PreviewDisplayMode

// T-M53-II（2026-10-10）：以下为 SqlPreviewPanel 的「SQL 文本展示、格式化/显示模式、复制导出与补全上下文」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

internal fun SqlPreviewPanel.refreshCompletionContext() {
    val ctx = buildCompletionContext()
    editor?.putUserData(SqlCompletionContext.KEY, ctx)
    psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
    editor?.document?.let { SqlCompletionContributor.registerContext(it, ctx) }
    // T-InsertColumnInlay O-44b：预览语句类型载体随上下文一同刷新（同一收口，两处渲染路径都过这里）——
    // INSERT 目标列 inlay 的语句类型闸门据此判定；非 XML 预览（xmlModel 为 null）写 null 即清除，
    // 防复用编辑器上残留上一次预览的语句类型
    attachPreviewStatementKind()
    // T-DbSchemaCompletion：预览实际输入面是原生控制台（Ultimate），它的上下文只在
    // onNativeEditorReady 挂过一次——不在此同步，schema 预热完成后控制台仍读旧上下文，
    // 库里独有的列照旧补不出来（且控制台端编辑器 userData 优先级高于 document 兜底）。
    nativeConsoleEditor?.let { console ->
        console.putUserData(SqlCompletionContext.KEY, ctx)
        SqlCompletionContributor.registerContext(console.document, ctx)
    }
    // UI025：schema 预热完成会经本函数刷新上下文——同时给两条错列标注通道补一拍，
    // 让「缓存刚变热」无需用户输入即可出波浪线（无目标/缓存冷时通道内部自行清空）。
    cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter.refresh(editor)
    cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter.refresh(nativeConsoleEditor)
}

/**
 * T-InsertColumnInlay O-44b：把当前预览的语句类型写到预览编辑器（Editor + PSI 文件双载体）。
 *
 * 这是 `xmlModel.kind` 的**首个读点**（此前零消费者）——INSERT 目标列 inlay 的语句类型闸门
 * 由 provider 读该载体判定，`kind != INSERT` 即零提示。取 `xmlModel` 而非 `SqlTypeClassifier`：
 * 语句类型是解析期事实（`XmlMapperParser.kindOf`），非 XML 预览链无此模型（写 null = 不提示，
 * 见计划 §二 非目标与 provider KDoc 的边界登记）。
 *
 * 写 null 而非「不写」：预览编辑器在预览切换间被复用（`showSql` 的 existing 分支），
 * 残留上一次预览的语句类型会让复用编辑器继续按旧类型产提示。
 */

internal fun SqlPreviewPanel.attachPreviewStatementKind(target: Editor? = editor) {
    val kind = current?.xmlModel?.kind
    target?.let { PreviewInlayScope.attach(it, kind) }
    psiFile?.let { PreviewInlayScope.attach(it, kind) }
}

/**
 * T-DbSchemaCompletion：补全 schema 预热涉及的表——SQL 里出现的表（含 JOIN）+ 预览主表。
 *
 * 表名取自 [TableExtractor]（纯词法，无 PSI）；解析不到时只剩主表兜底。带别名/括号的误提取
 * 会按「确认没有此表」记入负缓存（TTL 30s，DDL/提交后随 MetadataInvalidator 一起失效），
 * 代价只是 30s 内同表不再重扫、少几个补全候选，无害（「查不了」的情形不写负缓存，见
 * [DatabaseSchemaResolver.resolveFromSources] 的 scanBailed 分支）。
 */

internal fun SqlPreviewPanel.completionTables(result: PreviewResult?): List<String> {
    if (result == null) return emptyList()
    return (cn.song.mybatisplus.visualizer.sql.extract.TableExtractor.extract(result.sql) +
        listOfNotNull(result.tableName))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

/**
 * T-DbSchemaCompletion：后台预热「本文档涉及的表」的数据源 schema，完成后刷新补全上下文。
 *
 * 快照判据与 T-Perf-09 的 JOIN 富化同款（`current === result` 才应用）——新预览打开即作废迟到结果。
 * 数据源未连接时 `getModel()` 为 null → 解析 0 张且不留负缓存，控制台就绪（数据源已连）时会经
 * [onNativeEditorReady] 再触发一次，覆盖「预览早于建连」的窗口（真机日志实证该窗口存在）。
 *
 * 补全热路径不做任何遍历：本方法只在后台把结果填进 [DatabaseSchemaResolver] 缓存，
 * 之后 [buildCompletionContext] 只读缓存（T-Perf-09 红线）。
 */

internal fun SqlPreviewPanel.prefetchSchemaForCompletion(result: PreviewResult) {
    val proj = project ?: return
    val tables = completionTables(result)
    if (tables.isEmpty()) return
    ApplicationManager.getApplication().executeOnPooledThread {
        val resolved = runCatching {
            cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.prefetchTables(proj, tables)
        }.getOrDefault(0)
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[T-DbSchemaCompletion] schema 预热: tables=${tables.size}, resolved=$resolved, list=$tables")
        if (resolved == 0) return@executeOnPooledThread
        ApplicationManager.getApplication().invokeLater {
            if (current !== result || project?.isDisposed == true) return@invokeLater
            refreshCompletionContext()
        }
    }
}

/** 由当前预览结果构造补全上下文（表名/列名/注释；M16.2 抽为复用函数，Editor 与 PSI 同源）。 */

internal fun SqlPreviewPanel.buildCompletionContext(): SqlCompletionContext = SqlCompletionContext(
    tableName = current?.tableName,
    columns = current?.columns ?: emptyList(),
    tableAlias = null,
    columnComments = current?.columnComments ?: emptyMap(),
    tableComment = current?.tableComment,
    // T-M27.4：联表候选表（XML 预览经 resolveXml 注入；预览补全表名候选）
    candidateTables = current?.candidateTables.orEmpty(),
    // T-DbSchemaCompletion：数据源 schema 列（库里存在而实体类没有的列也是候选）。
    // 只读预热缓存（columnsCached 不触发遍历）；未预热/无数据源时空 Map = 改动前语义。
    schemaColumns = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
        .columnsCached(project, completionTables(current)),
)

/**
 * JDBC 命名参数（NamedParameterJdbcTemplate `:name`）按名回填。
 *
 * 当 [PreviewResult.namedParamHints] 非空时，把 SQL 中 `:name` 按用户输入的参数值整体替换
 * （同名参数所有出现共用一值，对齐 NamedParameterJdbcTemplate 语义）；`?` 占位符不受影响，
 * 由调用方继续走 [backfillParamsBySource] 按序回填。
 * 命名参数提示为空时原样返回（兼容既有 `?` 顺序回填路径）。
 */

internal fun SqlPreviewPanel.applyNamedParams(sql: String): String {
    val result = current ?: return sql
    if (result.namedParamHints.isEmpty()) return sql
    return ParameterReplacer.replaceNamedParams(
        sql,
        paramValues,
        result.namedParamHints,
        dialectName = result.effectiveDialect?.name,
    )
}

/**
 * T-RuntimeSlotFill 券 C：预览渲染的**唯一**回填入口——运行期占位与 `:name` 一次扫描
 * （[ParameterReplacer.buildExecutableSqlWithSlots]），`?` 仍由既有 [backfillParamsBySource]
 * 按序回填（它的 LIKE/空值策略/模拟字面量口径本券不动）。
 *
 * 为什么占位与 `:name` 必须同遍：用户填的占位值里可能带 `:nm` 形态，分两步做会被
 * `NAMED_PARAM_PATTERN` 二次当命名参数替换（券 B 的 R2 反向验证实测过）。同遍扫描下每种替换
 * 整段产出、产出不再被扫描，二次误伤在结构上不会发生。
 *
 * 零占位时**直接走既有 [applyNamedParams]**：没有运行期占位的预览（Wrapper/XML/批量/JPA 全链）
 * 产物与改动前逐字节一致。
 */
internal fun SqlPreviewPanel.applyRuntimeSlotsAndNamedParams(sql: String): String {
    val result = current ?: return sql
    if (result.runtimeSlots.isEmpty()) return applyNamedParams(sql)
    val render = ParameterReplacer.buildExecutableSqlWithSlots(
        sql = sql,
        slots = result.runtimeSlots,
        slotValues = slotValuesSnapshot(),
        paramValues = paramValues,
        namedHints = result.namedParamHints,
        dialectName = result.effectiveDialect?.name,
    )
    // 券 B 的拒绝/风险必须可见（不许静默），展示位在参数区「运行时值」分区下方
    publishRuntimeSlotWarnings(render.warnings)
    return render.sql
}

/**
 * T-RuntimeSlotFill 券 C：当前占位值的不可变快照（回填引擎唯一值载体）。
 *
 * 复制 / 导出 / 执行三处与预览渲染共用同一份——避免「预览填了、复制/导出没填」。
 */
internal fun SqlPreviewPanel.slotValuesSnapshot(): RuntimeSlotValues =
    RuntimeSlotValues(slotValues.toMap())

/**
 * T-RuntimeSlotFill 券 C：把当前占位值填进一段**已经参数回填过**的 SQL（导出 / 执行等取词口）。
 *
 * 只填占位（[ParameterReplacer.replaceRuntimeSlots]），不碰 `?` / `:name`——那些文本在编辑器里
 * 已经是回填后的形态。占位值全为空时逐字节原样返回（D-5）。
 */
internal fun SqlPreviewPanel.fillRuntimeSlots(sql: String): String {
    val slots = current?.runtimeSlots.orEmpty()
    if (slots.isEmpty()) return sql
    return ParameterReplacer.replaceRuntimeSlots(
        sql = sql,
        slots = slots,
        values = slotValuesSnapshot(),
        dialectName = current?.effectiveDialect?.name,
    ).sql
}

/** 按当前压缩/多行模式 + 动态条件启用集合 + 参数回填重渲染 SQL 编辑器。 */
/**
 * R12 T-PaginationSim：模型级重渲染后把分页 LIMIT 行带回主 SQL 尾部。
 *
 * 面板勾选/参数变更走 XmlPreviewModel.renderSql / SqlGenerator 重建——模型层 SQL 是分页后处理
 * **之前**的形态（服务端 applyPaginationShared 只作用于整条 PreviewResult），LIMIT 行会在此丢失
 * （20261008 dev-bak 验收实测：分页参数行与 COUNT 段都在、主 SQL 尾部无 LIMIT）。服务端把 LIMIT
 * 行存进 [PreviewResult.paginationLimitLine]，此处原样带回；主 SQL 已含 LIMIT（手写语句）时跳过。
 */

internal fun SqlPreviewPanel.withPaginationLimitLine(baseSql: String): String =
    appendPaginationLimitLine(baseSql, current?.paginationLimitLine)


internal fun SqlPreviewPanel.renderSql() {        // T-WSC-01：写安全自愈闸——renderSql 是面板全部文档写入的唯一上游（showSql 三条分支
    // 都写文档：nativeDoc / 自建 editor / createEditor），而写入合法性取决于「这次 EDT 活动
    // 是否 write-safe」（见 DocWriteContext KDoc）。非 write-safe（如 invokeLater(any) 排出的
    // 活动、Timer 拍）时重投递到 nonModal 的下一拍；renderSql 幂等重算，不会写出过期文本
    // ——这正是闸门放在 renderSql 而非 showSql(text) 的原因（后者收的是调用时刻的快照）。
    if (!DocWriteContext.isWriteAllowedNow()) {
        DocWriteContext.invokeLaterWriteSafe(this, "SqlPreviewPanel.renderSql") { renderSql() }
        return
    }
    val result = current ?: return
    val model = result.previewModel
    val batch = result.batchModel
    val xml = result.xmlModel
    val ibatisRerender = result.rerender
    when {
        // T-Ibatis2Gap 券 A：iBatis 参数/条件可交互——面板状态（值/勾选/迭代列表）交给预览服务
        // 闭包重渲染（渲染接缝 A：PreviewResult.rerender 回调，MyBatis 链零感知）。
        ibatisRerender != null -> {
            val input = PreviewRerenderInput(
                params = paramValues.toMap(),
                enabledConditionIds = enabledIds.toSet(),
                collections = ibatisIterateValues.mapValues { (_, text) ->
                    text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                },
            )
            val fresh = runCatching { ibatisRerender(input) }.getOrElse { e ->
                ibatisRenderWarnings = listOf(e.message ?: e.javaClass.simpleName)
                showSql(if (compactMode) result.compactSql else result.sql)
                updateMeta(result)
                return
            }
            ibatisRenderWarnings = fresh.warning?.let { listOf(it) } ?: emptyList()
            // UI-001：分档随本轮结果整体替换（信息级说明走 ℹ 前缀，不冒充失败）
            ibatisRenderWarningIsNote = fresh.informationalWarning
            // iterate 值播种：渲染器按列表长度产出 `ids[0..n-1]` 参数名——按当前值序回填（与
            // xml 的 syncForEachItemValues 同语义：状态 → 渲染单向流动，不留旧键）
            result.ibatisParamModel?.iterateRows?.forEach { row ->
                val values = input.collections[row.property].orEmpty()
                values.forEachIndexed { i, v -> paramValues["${row.property}[$i]"] = v }
            }
            val base = if (compactMode) fresh.compactSql else fresh.sql
            val sources = fresh.placeholderParamNames.ifEmpty { fresh.unresolvedParams }
            showSql(backfillParamsBySource(base, sources, fresh.paramHints))
            // 警示行与横幅随本轮刷新（iterate 留空等降级形态必须可见，不得静默）
            updateMeta(result)
        }
        // T-M24-04：XML 动态 SQL——渲染单源 XmlPreviewModel.renderSql(state)，勾选/次数/覆盖变更重渲染
        xml != null -> {
            val state = XmlPreviewState(
                enabledIds = enabledIds.toSet(),
                forEachCounts = xmlForEachCounts.toMap(),
                forEachNulls = xmlForEachNulls.toSet(),
                // T-M26.4：bind 覆盖已由「参数」区输入承担（回填层覆盖），渲染走表达式自动求值
                bindOverrides = emptyMap(),
            )
            val rendered = xml.renderSql(state)
            xmlErrors = rendered.errors
            xmlMappings = rendered.mappings
            // O-78：foreach 值按本帧新鲜映射清单播种（写安全闸重投递时与映射刷新同帧延迟，
            // 值状态→渲染单向流动，不再有「渲染后同步读键清单」的错位窗口）
            syncForEachItemValues(xml)
            xmlDollarSubstitutions = rendered.dollarSubstitutions
            // R30 探镜：本轮条件判定结果刷新条件行尾三态标注（只读观测，不参与渲染文本）
            syncXmlConditionOutcomeLabels(xml, rendered)
            // T-M25.5：bind 自动求值结果并入参数回填（用户手动输入优先，清空则恢复自动值）
            rendered.bindValues.forEach { (name, value) ->
                if (paramValues[name].isNullOrEmpty()) paramValues[name] = value
            }
            val sources = rendered.mappings.map { it.property }
            // T-M29.5：参数回填引号按 mapper 方法参数 Java 类型判定（XmlParamTypeResolver 注入
            // model.paramQuotes：DTO 字段 LocalDate→引号/Integer→裸渲染，贴近真实绑定效果）；
            // 未命中（Map/无类型信息）→ 兜底引号（T-M29.4：日期/字符串列安全，数值列隐式转换等价）
            // O-81 T-XmlListParamSplit：提示组装抽为 xmlBackfillHints——签名集合参数
            // （List<String> 等）无条件按逗号多值渲染 + 引号按元素类别派生，
            // 形态探测（detectInClauseParams）降级为无签名信息参数的兜底通道
            val base = if (compactMode) rendered.sql.replace(WHITESPACE_REGEX, " ").trim() else rendered.sql
            val inListIndices = detectInClauseParams(base)
            val xmlHints = xmlBackfillHints(xml, sources, inListIndices, xmlMappings)
            // UI022：先回填本轮未分页语句，COUNT 与主查询共享此结果；不能追加旧 COUNT 模板。
            val filledBase = backfillParamsBySource(base, sources, xmlHints)
            val count = result.countSql?.let {
                PaginationSimulator.wrapCountSql(filledBase)
            }
            val withLimit = withPaginationLimitLine(base)
            val addedSources = if (withLimit != base) result.paginationParamSources else emptyList()
            val filledMain = backfillParamsBySource(
                withLimit, sources + addedSources,
                xmlHints + addedSources.map { ParamHint(quote = false) },
            )
            showSql(filledMain, count)
        }
        // T-M23-04：批量列级勾选路径——BatchModel.renderSql 按活跃列重建 + 按列序回填
        batch != null -> {
            val active = enabledIds.toSet()
            val sql = batch.renderSql(active)
            val overlay = fieldFillOverlay()
            showSql(backfillParamsBySource(sql, batch.orderedSources(active), batch.orderedHints(active), overlay.literals))
        }
        model != null -> {
            // 模型级重生成：按动态条件启用集合重新生成 SQL（含回填提示），再按 source 回填参数
            val generated = SqlGenerator.generateWithParams(
                statement = model.statement,
                compact = compactMode,
                dialect = model.dialect,
                enabledConditionIds = enabledIds.toSet(),
            )
            // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
            // T-RuntimeSlotFill 券 C：命名参数与运行期占位同遍（无占位时逐字节等于旧路径）
            showSql(backfillParamsBySource(applyRuntimeSlotsAndNamedParams(withPaginationLimitLine(generated.sql)), generated.unresolvedParams, generated.paramHints))
        }
        else -> {
            // 旧文本路径（无可勾选动态条件，如批量操作）
            val base = if (compactMode) result.compactSql else result.sql
            // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
            // T-JpaParamOrder F3：JPA 占位符名序非空时按名回填（同名占位复用同值，
            // 与 setParameter 书写顺序解耦）；hints 在服务端已与该 sources 序平行
            val sources = result.placeholderParamNames.ifEmpty { result.unresolvedParams }
            showSql(backfillParamsBySource(applyRuntimeSlotsAndNamedParams(base), sources, result.paramHints))
        }
    }
    // T-SelToggle：勾选态一切变更均以 renderSql 收尾——切换按钮文案在此单点同步
    syncToggleSelectLabels()
    // O-95 S3：绑定数据源指示行同帧刷新（控制台 attach/reuse/clear 链的既有渲染路径即刷新通道）
    refreshBindStateRow()
}

/**
 * 刷新视图（M19.3）：模式**复位为与设置「SQL 格式化输出」对齐**（默认多行）后重渲染编辑器——
 * 用户实测：点刷新后 SQL 仍停留在压缩模式（此前手动切换/设置快照的压缩被保留）；
 * 刷新 = 回到默认状态（参数与回填保留，仅模式复位）。
 *
 * T-PreviewDisplayMode：显示模式一并复位为 NORMAL（即回到设置派生形态——设置开启为格式化、
 * 关闭为原样），与「刷新=回到默认状态」语义一致，竖排/关键字换行不跨刷新残留。
 */

internal fun SqlPreviewPanel.refreshView() {
    // T-RefreshDbSteal：刷新是唯一常规触达共享控制台文档的按钮（WriteCommandAction），
    // 入口记 4 态快照——真机复现时对照判归属
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M36] refreshView entry: compactMode=$compactMode, nativeDoc=${nativeEditorDoc != null}, editor=${editor != null}, embedded=${System.identityHashCode(embeddedConsole)}, attachedToMe=${consoleAttachedToMe()}")
    compactMode = !formatSqlSetting()
    displayMode = PreviewDisplayMode.NORMAL
    modeButton.text = modeButtonText()
    renderSql()
    // UI-004（2026-11-04 真机）：刷新必须**重算方言证据**。用户改 IDEA 的 SQL 方言不会发任何
    // 事件，旧实现只重渲染模型文本 → 顶部/底部方言停在旧值（「改了方言并刷新，底部仍报旧方言」）。
    // includeSourceFile = true：按预览生成时的同一份证据（源文件 → IDEA 方言映射）重算；
    // 重算在池线程，EDT 仅做仅升级替换 + 方言无关载体的证据对齐 + 底部提示重判。
    refreshDialectStatusRow(includeSourceFile = true)
}

/** 设置「SQL 格式化输出」（false=压缩）；环境异常兜底 true（多行）。 */

internal fun SqlPreviewPanel.formatSqlSetting(): Boolean = runCatching {
    PluginSettings.getInstance().state.formatSql
}.getOrDefault(true)

/**
 * T-VPairAnno：竖向排列 VALUES 行尾配对列名标注开关；环境异常兜底 true（保持默认标注）。
 * 与 [formatSqlSetting] 同构——保存后的下一次渲染生效，不做已开弹窗即时推送。
 */

internal fun SqlPreviewPanel.verticalPairAnnotationSetting(): Boolean = runCatching {
    PluginSettings.getInstance().state.verticalPairAnnotation
}.getOrDefault(true)

/**
 * T-PreviewDisplayMode：按当前显示模式变换渲染文本（[showSql] 的唯一收敛点调用）。
 *
 * 优先级：压缩模式最高——压缩语义是"全部挤一行"，而模式变换会引入换行，二者相抵故短路；
 * 其余按模式分派：NORMAL 交「SQL 格式化输出」设置决定（保持设置关闭时不格式化的既有语义）、
 * FORMAT 强制格式化、KEYWORD_WRAP / VERTICAL 走对应变换（二者对非 INSERT 已内含 format，
 * 无需叠加）。
 */
/**
 * T-PreviewFast P6：显示模式变换 **单槽缓存**——同一 (SQL, 模式, 压缩态, 格式化设置) 组合
 * 只做一次 SqlFormatter 变换。命中场景：挂载后回填渲染与紧随其后的参数/勾选重渲染
 * （内容未变时反复走格式化管线是纯浪费，大 SQL 上尤甚）。
 */

internal fun SqlPreviewPanel.applyDisplayMode(sql: String): String {
    if (compactMode) return sql
    val key = "$displayMode|$compactMode|${formatSqlSetting()}|$sql"
    formatCacheValue?.let { cached -> if (formatCacheKey == key) return cached }
    val out = when (displayMode) {
        PreviewDisplayMode.NORMAL -> if (formatSqlSetting()) SqlFormatter.format(sql) else sql
        PreviewDisplayMode.FORMAT -> SqlFormatter.format(sql)
        PreviewDisplayMode.KEYWORD_WRAP -> SqlFormatter.formatClauseLine(sql)
        PreviewDisplayMode.VERTICAL -> SqlFormatter.formatVertical(sql, verticalPairAnnotationSetting())
    }
    formatCacheKey = key
    formatCacheValue = out
    return out
}

/**
 * T-PreviewDisplayMode：切换编辑器显示模式并重渲染（格式族按钮与 Ctrl+Alt+L 快捷键的统一入口）。
 *
 * 与旧实现的区别：不再直接对文档做一次性变换，而是记录模式后交渲染管线产出——这样参数输入、
 * 条件勾选、foreach 次数增减等任何重建都不会再把竖排/关键字换行形态抹掉。
 *
 * 压缩模式下切换格式族模式会一并退出压缩：否则模式被压缩短路、点击无可见效果（且"压缩态 +
 * 已格式化文档"是自相矛盾的中间态）。
 */

internal fun SqlPreviewPanel.setDisplayMode(mode: PreviewDisplayMode) {
    displayMode = mode
    if (compactMode) {
        compactMode = false
        // T-M53-II：`::modeButton.isInitialized` 读的是**后备字段**，该类引用只在声明类内可用
        // （搬为同包扩展函数后编译器红：backing field not accessible）。语义不变地改走类内
        // 小组件 `modeButtonOrNull()`（同样的 isInitialized 判定，同文件同包）。
        modeButtonOrNull()?.text = modeButtonText()
    }
    renderSql()
}

/**
 * 格式化当前编辑器 SQL（关键字大写 / 子句换行 / 条件缩进，[SqlFormatter]）。
 * T-PreviewDisplayMode：等价于「格式化」按钮——设 FORMAT 模式后由渲染管线套用（形态在参数
 * 变更后保持）；不再需要直接改文档，故改走状态路径。
 */

internal fun SqlPreviewPanel.formatSql() {
    val before = lastRenderedSql
    setDisplayMode(PreviewDisplayMode.FORMAT)
    notifyIfFormatUnchanged(before)
}

/**
 * T-DdlLogPreview S3（20261006 第三轮走查，D76 用户裁决「可以给」）：格式化后文本**零变化**时
 * 给一条轻提示。
 *
 * 立项动因：`DROP TABLE IF EXISTS x ;` 这类**本就单行**（无换行点）的语句点「格式化」看不到任何
 * 变化，用户读成「格式化坏了」——实测即由此报障。判据 = 渲染文本前后一致（[lastRenderedSql]
 * 是渲染管线唯一产物，含格式化结果）；同文本往返说明「已是最可读形态」，明确告知即可。
 * 与既有轻提示同规格：[NOTIFICATION_GROUP_ID] + INFORMATION，非模态；单测环境静默（不产生通知）。
 */

internal fun SqlPreviewPanel.notifyIfFormatUnchanged(before: String?) {
    val after = lastRenderedSql
    if (before == null || after == null || before != after) return
    if (ApplicationManager.getApplication().isUnitTestMode) return
    runCatching {
        Notification(
            NOTIFICATION_GROUP_ID,
            MpSqlVisualizerBundle.message("panel.format"),
            MpSqlVisualizerBundle.message("panel.format.alreadyFormatted"),
            NotificationType.INFORMATION,
        ).notify(project)
    }
}

/** 关键字换行格式化：子句顶格换行、同行内容压一行（[SqlFormatter.formatClauseLine]）。 */

internal fun SqlPreviewPanel.formatClauseLine() {
    setDisplayMode(PreviewDisplayMode.KEYWORD_WRAP)
}

/**
 * 竖向排列格式化：INSERT 列清单/VALUES 逐行竖排、值行尾标注配对列名（[SqlFormatter.formatVertical]）。
 * T-VPairAnno：标注是否追加由设置 verticalPairAnnotation 控制（默认开）。
 */

internal fun SqlPreviewPanel.formatVertical() {
    setDisplayMode(PreviewDisplayMode.VERTICAL)
}

/**
 * T-M26.6：格式化快捷键集——当前激活 keymap 中 Reformat Code（Ctrl+Alt+L）的绑定
 * （跟随用户自定义键位）；keymap 无绑定时兜底默认 Ctrl+Alt+L。
 * 注：2025.3 平台 IdeActions 无 ReformatCode 常量，用动作 id 字符串。
 */

internal fun SqlPreviewPanel.reformatShortcuts(): Array<Shortcut> {
    val fromKeymap = KeymapManager.getInstance().activeKeymap.getShortcuts("ReformatCode")
    if (fromKeymap.isNotEmpty()) return fromKeymap
    return arrayOf(
        KeyboardShortcut(
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_MASK or InputEvent.ALT_MASK),
            null,
        ),
    )
}

/** 压缩 / 多行切换。T-PreviewDisplayMode：不改显示模式——切回多行时既有模式（如竖排）恢复。 */

internal fun SqlPreviewPanel.toggleCompact(): Boolean {
    compactMode = !compactMode
    renderSql()
    return compactMode
}

/**
 * 一键复制当前 SQL（取编辑器当前内容，含手动编辑与参数回填）。
 *
 * @param offerClosePanelAction 复制成功通知里是否附带「关闭 SQL 面板」动作。仅当调用方**不**关闭
 *   面板时才有意义——「复制 SQL 并关闭」本身就会关掉弹窗，再挂一个「关闭 SQL 面板」既冗余、
 *   点击也无目标可关（UI-044：用户复制后弹窗已关，通知里还留着这个链接）。
 */

internal fun SqlPreviewPanel.copySql(offerClosePanelAction: Boolean = true): Boolean {
    // M36.4：优先取原生编辑器文档，兜底自研编辑器
    val raw = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
        ?: editor?.document?.text?.takeIf { it.isNotBlank() }
        ?: return false
    // 复制时剥离 SQL 注释（-- 行注释 + /* */ 块注释），与 Java 代码复制对齐
    val text = SqlCommentStripper.strip(raw)
    UiTextClipboard.copy(text)
    // UI-044 测试缝：非 null 时记录「本次通知会挂哪些动作」，绕开单测模式下不弹通知的分支
    val actionSink = copyNotificationActionsForTest
    if (actionSink != null) {
        if (offerClosePanelAction) actionSink += MpSqlVisualizerBundle.message("dialog.closeSqlPanel")
    }
    project?.let {
        // T-UiNative T-UN-02：复制成功提示改轻通知 + 「关闭 SQL 面板」动作（T-M23.6 原双按钮
        // 模态改为非模态——点动作等价原 index==1 分支，回调关闭预览弹窗）；
        // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            runCatching {
                val notification = Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("plugin.name"),
                    MpSqlVisualizerBundle.message("dialog.copy.done"),
                    NotificationType.INFORMATION,
                )
                if (offerClosePanelAction) {
                    notification.addAction(
                        NotificationAction.createSimpleExpiring(
                            MpSqlVisualizerBundle.message("dialog.closeSqlPanel"),
                        ) { onClosePanelRequested?.invoke() },
                    )
                }
                notification.notify(it)
            }
        }
    }
    return true
}

/**
 * UI-044 测试缝：复制通知「动作文案」采集器（按面板实例挂，null/缺席 = 不采集）。
 *
 * 单测模式不弹真通知，若只断言点击行为就看不到「通知挂了哪些动作」——UI-044 正是这样漏掉的。
 * 置非 null 后 [copySql] 会把将要挂上的动作文案记进来，供用例断言「复制并关闭」不带关闭链接。
 * 用 WeakHashMap 而非扩展属性字段：扩展属性不能有 backing field（编译期硬拦）。
 */
private val copyNotificationSinks = java.util.WeakHashMap<SqlPreviewPanel, MutableList<String>>()

internal var SqlPreviewPanel.copyNotificationActionsForTest: MutableList<String>?
    get() = copyNotificationSinks[this]
    set(value) {
        if (value == null) copyNotificationSinks.remove(this) else copyNotificationSinks[this] = value
    }

/**
 * O-4：一键复制可执行 SQL（替换 `?` 为带类型处理的字面值，复制到剪贴板 + toast 提示）。
 * 委托 [CopyExecutableSqlAction] 处理替换逻辑与通知。
 */

internal fun SqlPreviewPanel.copyExecutableSql() {
    // T-RuntimeSlotFill 券 C：与预览渲染共用同一份占位值（否则会「预览填了、复制没填」）
    CopyExecutableSqlAction(project).execute(this, slotValuesSnapshot())
}

/**
 * F-8：显示分享选项对话框（Markdown / HTML / 可执行 SQL）。
 * 委托 [cn.song.mybatisplus.visualizer.share.ui.ShareDialog]。
 */

internal fun SqlPreviewPanel.showShareDialog() {
    ShareDialog(project, this).show()
}

// ------------------------------------------------------------------
// O-4：SQL 并排对比（IDEA 原生 Diff，SQL 语法高亮）
// ------------------------------------------------------------------

/**
 * O-4：使用 IDEA 原生 Diff 对比当前 SQL 与上次预览 SQL。
 * 无上次预览时 toast 提示。
 *
 * 接入方式：DiffContentFactory.createEditable() 创建可编辑内容 + SimpleDiffRequest 包装 → DiffManager.showDiff。
 * 原生 diff 支持 SQL 语法高亮、行级对比、同步滚动、可编辑、F7 跳转差异等 IDEA 标准能力。
 */

internal fun SqlPreviewPanel.showSqlDiff() {
    val proj = project ?: return
    // M36.4：优先取原生编辑器文档，兜底自研编辑器
    val currentSql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
        ?: editor?.document?.text?.takeIf { it.isNotBlank() }
        ?: return
    val oldSql = previousSql ?: run {
        showDiffToast(MpSqlVisualizerBundle.message("preview.diff.noPrevious"))
        return
    }
    // IDEA 原生 Diff（createEditable → 两侧均可编辑，SQL 文件类型 → 语法高亮）
    val diffContentFactory = com.intellij.diff.DiffContentFactory.getInstance()
    val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL")
    val leftContent: com.intellij.diff.contents.DocumentContent = diffContentFactory.createEditable(proj, oldSql, sqlFileType)
    val rightContent: com.intellij.diff.contents.DocumentContent = diffContentFactory.createEditable(proj, currentSql, sqlFileType)
    val request = com.intellij.diff.requests.SimpleDiffRequest(
        MpSqlVisualizerBundle.message("panel.diff.dialogTitle"),
        leftContent,
        rightContent,
        MpSqlVisualizerBundle.message("panel.diff.previous"),
        MpSqlVisualizerBundle.message("panel.diff.current"),
    )
    com.intellij.diff.DiffManager.getInstance().showDiff(proj, request)
}

/** O-4：显示 diff 相关 toast 通知。 */

internal fun SqlPreviewPanel.showDiffToast(message: String) {
    if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) return
    val proj = project ?: return
    runCatching {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, com.intellij.notification.NotificationType.INFORMATION)
            .notify(proj)
    }
}

/**
 * T-M23.6：提示「关闭 SQL 面板」按钮回调（由预览弹窗 [cn.song.mybatisplus.visualizer.ui.SqlPreviewDialog]
 * 注入 `dispose()`；null 时提示不出现关闭按钮链路，回调为空操作）。
 */

internal fun SqlPreviewPanel.clear() {
    resetFieldFillSimulation()
    fieldFillPreview = null
    fieldFillFields.clear()
    fieldFillCheckBox = null
    fieldFillStatus = null
    current = null
    sourceModuleKey = null
    sourceVirtualFile = null
    lastDialectGateMessage = null
    // 下次预热在结果到达前使用兼容 MySQL；结果到达后再按实际生成方言重映射。
    previewDesiredDialectName = null
    enabledIds.clear()
    paramValues.clear()
    paramFields.clear()
    conditionCheckBoxes.clear()
        conditionOutcomeLabels.clear()
    xmlConditionOutcomeLabels.clear()
    conditionValueFields.clear()
    batchCheckBoxes.clear()
    xmlForEachNullBoxes.clear()
    xmlForEachValueFields.clear()
    xmlForEachCounts.clear()
    xmlForEachValues.clear()
    xmlForEachNulls.clear()
    xmlErrors = emptyList()
    xmlDollarSubstitutions = 0
    // T-Ibatis2Gap 券 A：iBatis 交互态随清空复位（不跨预览残留）
    ibatisIterateValues.clear()
    ibatisRenderWarnings = emptyList()
    ibatisRenderWarningIsNote = false
    currentWarning = null
    currentWarningIsNote = false
    errorCopyButton?.isVisible = false
    toggleSelectButton = null
    batchToggleSelectButton = null
    paramPanel.removeAll()
    // O-4：重置会话缓存
    previousSql = null
    // T-PreviewDisplayMode：显示模式随面板清空复位（与其余会话状态一致，不跨预览残留）
    displayMode = PreviewDisplayMode.NORMAL
    releaseEditor()
    editorHolder.removeAll()
    metaLabel.text = ""
    // T-PreviewTopOverlap F3：元信息原文缓存随清空复位（下次 updateMeta 重建）
    metaLineText = ""
    metaLineHead = ""
    metaLineDigest = ""
    metaPerfSegments = emptyList()
    lastMetaBudget = -1
    // T-DialectStatusLiveRefresh：方言决策与分段缓存同源复位，不跨预览残留
    currentDialectDecision = null
    metaCoreText = ""
    metaTooltipCore = ""
    metaTooltipSuffix = ""
    // T-DmlImpactProminence：影响行数试算横幅随清空复位（下次试算重新挂）
    clearDmlImpactBanner()
    // T-SourceJumpFunnel P6：tooltip 基线同步复位（行内提示态据此拼接，不复用上一份预览的残留）
    metaTooltipBase = ""
    metaLabel.toolTipText = null
    // T-StackHoverUi：调用栈悬停弹层随清空复位（旧弹层即关，下次预览按新数据重弹）
    stackHover.updateDigest(null)
    // F-1：清除图集 + 恢复纯 SQL 视图（整图卸载含虚拟外键回收）
    removeDiagramTab()
    unloadJoinDiagram()
    // M36：清空结果面板 + 摘除内嵌控制台
    resultPanel.clear()
    detachConsole()
    // R33：作废在途结构化 EXPLAIN 回调 + 清掉结构化视图残留（consoleHost 子层换空，
    // 下次预览重新走 prepareConsole 正常换入控制台；editorHolder 清空在本函数开头已做）
    explainStructuredGeneration++
    consoleHost.removeAll()
    // M36.4：重置原生编辑器文档引用
    nativeEditorDoc = null
    // T-AutoFold F5：控制台编辑器引用随清空复位
    // T-PreviewLineCommentDash O-134：复位先减计数（detachConsole 已覆盖常规路径，此处兜底直清分支）
    nativeConsoleEditor?.let(EditorCommentShortcuts::uninstall)
    nativeConsoleEditor = null
    // M36.5：释放已准备控制台引用（下次预览重新准备）
    SqlExecutor.clearPrepared()
    // T-M36.6-03：clear 释放了临时文件/预热产物——面板重用时重新预热（幂等，保持零慢操作路径）
    if (project != null) {
        prewarmPreviewTempFile()
    }
    revalidate()
    repaint()
}

// ------------------------------------------------------------------
// 编辑器（PSI 支撑 + 可编辑 + 高亮）
// ------------------------------------------------------------------

/** 渲染 SQL 到可编辑编辑器；已存在时更新文档（保留焦点/撤销）。 */

internal fun SqlPreviewPanel.showSql(sql: String, countSql: String? = current?.countSql) {
    // R33：新预览作废在途结构化 EXPLAIN 回调（慢计划不覆盖用户已切换的新预览）
    explainStructuredGeneration++
    // T-RefreshDbSteal：三分支走查埋点——真机故障期症状②（左参数不再同步右侧 SQL）的
    // 丢失路径必经此方法（三个静默分支之一吞掉了渲染），带分支标签定位
    val fLogger = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
    // T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）——在主 SQL 之后渲染 fill 附加 SELECT
    val withFill = appendFillSection(sql)
    // R12 T-PaginationSim：追加分页 COUNT 包裹段（D1=A 预览附第二条语句，对齐捕获侧 COUNT 独立条目口径）
    val withFillAndCount = appendCountSection(withFill, countSql)
    // T-PreviewDisplayMode：显示模式在渲染管线内统一套用（唯一收敛点——XML/批量/模型/旧文本
    // 四条重建路径都经此处，故参数输入/勾选/foreach 变更后形态保持）
    val text = applyDisplayMode(withFillAndCount)
    // M36.4：优先写入原生编辑器文档（内嵌后）
    val nativeDoc = nativeEditorDoc
    if (nativeDoc != null) {
        // T-RefreshDbSteal F4：控制台失联时不写"别人的文档"（症状②：参数改动同步进
        // 被抢走的控制台，本面板视觉零变化）——改走失联恢复把控制台要回来；恢复链
        // 内部会同步 SQL 进控制台文档（reuseSharedConsole setText），本次渲染不丢
        if (!consoleAttachedToMe()) {
            fLogger.warn("[M36] showSql nativeDoc detached (attachedToMe=false) → recoverConsole")
            recoverConsole(text)
            return
        }
        if (nativeDoc.text != text) {
            fLogger.info("[M36] showSql branch=nativeDoc: len=${text.length}, attachedToMe=${consoleAttachedToMe()}")
            suppressAutoPopup = true
            try {
                // T-PreviewFast P4：程序性写入开闸——否则本次写入会触发执行文件的 1.5s 防抖
                // 自动落盘（内容刚由本写入落盘，再写一遍纯属自己与自己抢）
                cn.song.mybatisplus.visualizer.execute.ExecDocAutosaveGate.suppressDuring {
                    // T-PreviewDocPersistLock：`setText` 与 `saveDocument` 必须落在**同一个写命令块**内。
                    // `FileDocumentManager.saveDocument` 首行即断言写环境（243 FileDocumentManagerImpl:327-329
                    // → TransactionGuardImpl.assertWriteActionAllowed；262 同形改名），而
                    // `runWriteCommandAction` 是一次性写动作——lambda 返回时写意图释放，
                    // 落盘留在块外就是「EDT + 无写意图」帧，硬抛
                    // 「Access is allowed from write thread only」（用户 2026-11-02 真机双栈）。
                    WriteCommandAction.runWriteCommandAction(project) {
                        nativeDoc.setText(text)
                        // T-PS4-12 定案①：同临界区落盘（照 DataSourceSelector.writeVfTextViaDocument
                        // 手法，isDocumentUnsaved → saveDocument）——闸门前提「内容由本写入落盘」
                        // 在此成真，formatted 文本不再滞留 modified-unsaved（第七轮审计发现本体）。
                        //
                        // 「磁盘存原文还是 formatted」核实结论（T-PreviewConsoleFmt F1/F2 +
                        // T-PreviewFast P1/P3 定例）：挂载后磁盘跟随 document 是既定语义——
                        //  1) ConsoleFmt F1 定例 = 控制台挂载后渲染链把 formatted 回填进控制台
                        //     文档，文档内容（=控制台所见）即权威；
                        //  2) T-PreviewFast P1 `execFileContentMatches` 定例 = 有已加载 document
                        //     时以 document 文本为准（注释原文「也是落盘会写入的内容」）；
                        //  3) 「磁盘存预览原文」仅是预热路径的写入，且只发生在文档未挂编辑器时
                        //     （ConsoleFmt F2 + PreviewFast P3：文档打开时预热跳过内容刷新）；
                        //  4) 落盘 formatted 不改变执行语义：执行链执行前同步的是控制台文档内容，
                        //     用户手改兜底（F5）落盘的同样是 document 内容。
                        // 故本写入落盘 formatted 与既有定例一致；孤儿文档（getFile null，测试缝
                        // createDocument 形态）无盘可落，自然跳过。
                        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        val vf = fdm.getFile(nativeDoc)
                        if (vf != null && fdm.isDocumentUnsaved(nativeDoc)) {
                            fdm.saveDocument(nativeDoc)
                        }
                    }
                }
            } finally {
                suppressAutoPopup = false
            }
        }
        // T-PrewarmLateRecover F1：记录插件最后一次写入的文本——迟到产物换装据此判「用户没动过」
        lastRenderedSql = text
        // T-AutoFold F4-a：nativeDoc 支链的程序性折叠（关闭态 applyTo 内部即清除短路）
        nativeConsoleEditor?.let { foldTarget ->
            if (!foldTarget.isDisposed) {
                cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(foldTarget, text)
            }
        }
        return
    }
    val existing = editor
    if (existing != null) {
        fLogger.info("[M36] showSql branch=editor: len=${text.length}, nativeDoc=null")
        if (existing.document.text != text) {
            suppressAutoPopup = true
            try {
                WriteCommandAction.runWriteCommandAction(project) { existing.document.setText(text) }
            } finally {
                suppressAutoPopup = false
            }
        }
        // T-PrewarmLateRecover F1：记录插件最后一次写入的文本（同上）
        lastRenderedSql = text
        // T-AutoFold F4-a：自建编辑器支链
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(existing, text)
        return
    }
    fLogger.info("[M36] showSql branch=createEditor: len=${text.length}, attachedToMe=${consoleAttachedToMe()}")
    // T-RefreshDbSteal F4：createEditor 是 UX 悬崖（无 DBN 工具栏、形态突变带行号）——
    // 控制台插件级就绪但已失联时，先走恢复链重嵌，恢复链自身按回退编辑器保显示
    if (!consoleAttachedToMe() && SqlExecutor.isPrepared()) {
        fLogger.warn("[M36] createEditor fallback while prepared console detached → recoverConsole")
        recoverConsole(text)
    }
    // T-PrewarmLateRecover F1：新建支链同样记录（装配后文档文本即 text）
    lastRenderedSql = text
    createEditor(text)
}

/**
 * T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）。
 *
 * 在主 SQL 之后追加 fill 附加 SELECT 渲染（每 fill 调用独立一行），
 * 以注释头 `-- fill additional query (MPJ 1.5.1+)` 标识。
 * 无 fill 调用时原样返回。
 */

internal fun SqlPreviewPanel.appendFillSection(sql: String): String {
    val result = current ?: return sql
    val fills = result.fillClauses ?: return sql
    if (fills.isEmpty()) return sql
        val dialect = result.effectiveDialect ?: MySqlDialect

    val sb = StringBuilder(sql)
    sb.append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.fill.section")).append("\n")
    fills.forEach { fill ->
        sb.append(SqlGenerator.generateFill(fill, dialect))
        sb.append("\n")
    }
    return sb.toString()
}

/**
 * R12 T-PaginationSim：追加分页 COUNT 包裹段（D1=A 预览附第二条语句）。
 *
 * 在主 SQL（含 fill 段）之后以注释头并列渲染 COUNT 包裹 SQL（对齐捕获侧 MP_PAGINATION_COUNT
 * 独立条目口径——运行时 COUNT 是分页查询之外的独立语句）。无 COUNT 产出时原样返回。
 *
 * T-DevAcceptFix ③（20261006 验收）：两条语句各自以 `;` 收尾——原实现主 SQL 尾直拼注释头，
 * 复制到控制台执行时分页语句与 COUNT 语句粘连（用户实验收实测）；已带分号不重复追加。
 */

internal fun SqlPreviewPanel.appendCountSection(sql: String, countSql: String? = current?.countSql): String {
    if (countSql == null) return sql
    val terminatedMain = sql.trimEnd().let { if (it.endsWith(";")) it else "$it;" }
    val terminatedCount = countSql.trimEnd().let { if (it.endsWith(";")) it else "$it;" }
    return StringBuilder(terminatedMain)
        .append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.pagination.count.section")).append("\n")
        .append(terminatedCount)
        .append("\n")
        .toString()
}

/** 首次创建：原生 SQL FileType PSI → 文档 → 可编辑编辑器，附补全上下文（Editor + PSI 双载体，M16.2）。 */
