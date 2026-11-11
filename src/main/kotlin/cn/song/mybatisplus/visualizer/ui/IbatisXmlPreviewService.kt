package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModel
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModelBuilder
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamTypeHints
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParseException
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisRenderResult
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisSelectKey
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisSqlMap
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatement
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapParser
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapSqlRenderer
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * iBatis 2.x 语句预览服务（T-Ibatis2Support 券 A 接管面）：`<sqlMap>` 文本 → 语句 → [PreviewResult]。
 *
 * 与 `<pages>` 链同款**纯文本入口**（fileText 输入，无 PSI 依赖）：gutter / Action 负责取文件文本
 * 与锚定标签信息，弹窗与面板完全复用 [SqlPreviewService.showResult] 链路。
 *
 * ## 走的是哪条链（与 §券 0 的关系）
 *
 * iBatis 2.x **无 HQL 概念**，语言判定对它整体是无效计算。本服务**完全绕开**
 * `PagesSourceLanguageDetector` / `PagesEntityProbeResolver`——这同时消掉「XML 内表名反查实体」
 * 那条慢路径（计划册 §券 0 的 CD-3 修法在 pages 链落地后，本链连判定入口都不进）。
 *
 * ## 三条对齐计划册 §1.10 的口径
 *
 * 1. **`#` 不转 `?` 的分支只在渲染器里**（声明 `parameterMap` 且非动态语句）——本服务只做
 *    `?` 位点数与参数名序的对齐核对，不自行改写 SQL；
 * 2. **`$x$` 直替接既有注入闸门**：`hasDollarRawSegments`（[PreviewResult.hasDollarRawSegments]）
 *    由渲染器事实决定，不新开无闸门路径；iBatis `${prop}`（引擎的全局属性 token，本链不做替换）
 *    同样计入——它在 SQL 文本里就是 `${}` 原文拼接段；
 * 3. **渲染告警落到面板可见区**：复用既有 `warning` 载体（面板 `currentWarning` → 降级横幅 +
 *    错误复制按钮），不新造 UI 形态。告警取自渲染器的降级清单（未知标签 / 声明 parameterMap
 *    导致 `#` 未转 `?` / `${...}` 未支持 / `#` 格式错 等）。UI-001 起告警分档：parameterMap 的
 *    「引擎行为」说明与 iterate 未供值的「待填值」提示标 `informationalWarning`，面板用 `ℹ`
 *    信息级前缀呈现，不再统一冒充「⚠ 预览未完整还原」。
 *
 * ## 参数名口径（拍板 D-7）
 *
 * `IbatisRenderResult.paramNames` 按 `?` 出现顺序给出参数名（iBatis `#name#` 语义）。三种不齐形态：
 * - **数量相符** → 直接用参数名（面板列出 `name` 而不是 `param1`）；
 * - **数量不符**（如 `<statement>` 手写 `?`、`#` 保留未转、iterate 内重解析）→ 按 `param1..N` 兜底
 *   + 告警说明；
 * - **参数名非空但无 `?`**（声明 parameterMap 且体内 `#` 未转 `?`）→ 参数行留空 + 告警说明
 *   「参数名不可知」，与通用兜底通道同口径（不显示假的空参数表）。
 *
 * ## T-IbatisFix2（2026-11-10 用户 2026.3 验收反馈）
 *
 * 1. **`<selectKey>` 子语句展示**：渲染器 `renderSelectKey`（保真对照 4/4）此前服务层从未调用，
 *    `insertUser` 的 `SELECT SEQ_USER_ID.NEXTVAL FROM DUAL` 完全不可见。现按引擎语义组装——
 *    `type=pre`（含未写 type 时按位置推断的 pre）在主语句之前、`post` 在后，块间用注释分隔
 *    （`-- [selectKey type=pre → id]` / `-- [语句id]`）。条件行/参数行仍以主语句为准：
 *    selectKey 的 `keyProperty` 回写目标不是 `?` 参数，不进参数面板（keyProperty 只进块注释）；
 *    selectKey 自身若含 `#x#`，其参数名按 pre 前 / post 后拼进参数名序，保证 `?` 位点对齐。
 * 2. **重渲染失败可见**（用户机器独有、本地探针 probe1 不可复现）：推断 263 平台上 rerender
 *    回调内部抛异常被面板吞掉、静默保留基线。整条回调 catch（[FatalErrors] 豁免）：失败时
 *    `idea.log` 记带堆栈的 warn（取证）＋返回可见失败结果（sql 保留无参基线降级文本、
 *    warning 失败档明确写「重渲染失败：<原因>」），禁止吞掉后返回旧结果。
 * 3. **告警分档过滤**：信息级说明（「（引擎行为）」等）不再混入 ⚠ 失败档合并行——混合时
 *    ⚠ 行只保留真失败（此前信息级说明与失败说明拼同一行、ℹ 行又重复同内容）；纯信息级整行走 ℹ。
 */
object IbatisXmlPreviewService {

    private val LOG = Logger.getInstance(IbatisXmlPreviewService::class.java)

    /**
     * 预览一份 `<sqlMap>` 文本里的语句。
     *
     * @param statementId 锚定标签的 `id` 属性（首选定位键；null 时按 [statementTagName] + [statementOrdinal] 定位）。
     * @param statementTagName 锚定标签名（`select`/`insert`/…；null 时退化为「取文件首条语句」）。
     * @param statementOrdinal 同名标签中的序号（0 起；仅无 id 的非法文件用得上）。
     */
    fun preview(
        fileText: String,
        statementId: String?,
        statementTagName: String? = null,
        statementOrdinal: Int = 0,
        /** T-IbatisParamClassQuote：解析 `parameterClass` 属性类型用（null = 纯文本测试链，维持现状）。 */
        project: Project? = null,
        /** T-IbatisParamClassQuote：语句的 `parameterClass` 原值；null 时回落到 XML 属性里的同名项。 */
        parameterClass: String? = null,
    ): PreviewResult {
        val model: IbatisSqlMap = try {
            SqlMapParser().parse(fileText)
        } catch (e: IbatisParseException) {
            return emptyResult(e.message ?: MpSqlVisualizerBundle.message("ibatis.preview.notSqlMap.warning"))
        }
        val statement = locateStatement(model, statementId, statementTagName, statementOrdinal)
            ?: return emptyResult(MpSqlVisualizerBundle.message("ibatis.preview.statementNotFound.warning"))
        val rendered = try {
            SqlMapSqlRenderer().render(statement)
        } catch (e: RuntimeException) {
            // 渲染器本身把语句级问题降级为 warning（不抛穿）；能到这里是意外形态，仍不打断预览
            return emptyResult(e.message ?: MpSqlVisualizerBundle.message("ibatis.preview.renderFailed.warning"))
        }
        // T-Ibatis2Gap 券 A：把「无参基线」从唯一输出变成初始态——行清单 + 重渲染回调随结果进面板。
        // 回调闭包复用同一 statement（纯数据，无 PSI/引擎引用），面板每次交互重新装配 Map 并渲染。
        val paramModel = IbatisParamModelBuilder.build(statement)
        val selectKeyRenders = renderSelectKeys(statement, params = null)
        // T-IbatisParamClassQuote：裸 `#name#` 的类型来源——parameterClass 的属性类型。
        // 只按首屏实际出现的属性名解析一次，重渲染复用（新分支带出的属性再查不到就维持启发式）。
        val propertyHints = IbatisParameterClassResolver.resolve(
            project = project,
            parameterClass = parameterClass ?: statement.attributes["parameterClass"],
            propertyNames = rendered.paramNames + selectKeyRenders.flatMap { it.second.paramNames },
        )
        val baseline = toPreviewResult(
            rendered,
            statementHasRawSubstitutionSites = paramModel.dollarRows.isNotEmpty(),
            // 无参基线：iterate 行全部未供值（待填值态）
            iteratePending = paramModel.iterateRows.isNotEmpty(),
            selectKeyRenders = selectKeyRenders,
            propertyHints = propertyHints,
        )
        return baseline.copy(
            ibatisParamModel = paramModel,
            // T-IbatisFix2 修复三：闭包捕获基线结果——重渲染失败时回落到它（sql 保留降级文本），
            // 不再静默吞掉异常
            rerender = { input -> rerender(statement, paramModel, baseline, input, propertyHints) },
        )
    }

    /**
     * 面板状态 → Map 装配 → 既有渲染器（空装配回退 null = 无参基线，与现状一致）。
     *
     * T-IbatisFix2 修复三：整条回调 catch（[FatalErrors.isFatal] 豁免直传）——用户 2026.3 上
     * 「全勾全填后 SQL 保持在无参基线」本地不可复现（探针 probe1 重渲染正常），推断平台上回调
     * 内部抛异常被吞。失败不再静默：`idea.log` 记带堆栈 warn + 返回可见失败结果（sql 保留
     * 基线降级文本、warning 失败档「重渲染失败：<原因>」）。同时是取证手段：真机再触发时面板
     * 会直接给出异常内容。
     */
    private fun rerender(
        statement: IbatisStatement,
        paramModel: IbatisParamModel,
        baseline: PreviewResult,
        input: PreviewRerenderInput,
        /** T-IbatisParamClassQuote：首屏按 parameterClass 解析好的属性 → 引号提示。 */
        propertyHints: Map<String, ParamHint> = emptyMap(),
    ): PreviewResult = try {
        val params = IbatisParamModelBuilder.assembleParams(paramModel, input)
        val rendered = SqlMapSqlRenderer().render(statement, params)
        toPreviewResult(
            rendered,
            statementHasRawSubstitutionSites = paramModel.dollarRows.isNotEmpty(),
            iteratePending = paramModel.iterateRows.any { input.collections[it.property].orEmpty().isEmpty() },
            selectKeyRenders = renderSelectKeys(statement, params),
            propertyHints = propertyHints,
        )
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e
        LOG.warn("iBatis 预览重渲染失败（statement=${statement.id}）：${e.message}", e)
        rerenderFailedResult(baseline, e)
    }

    /**
     * T-IbatisFix2 修复三的失败档产物（internal＝测试缝）：sql/compactSql/参数模型保留**基线降级
     * 文本**（不是空结果），warning 换成失败档「重渲染失败：<原因>」，横幅以 ⚠ 呈现——禁止吞掉
     * 后静默返回旧结果。`rerender` 闭包随 copy 保留，用户修正参数后可立即重试。
     */
    internal fun rerenderFailedResult(baseline: PreviewResult, error: Throwable): PreviewResult = baseline.copy(
        warning = MpSqlVisualizerBundle.message(
            "ibatis.preview.rerenderFailed.warning",
            error.message ?: error.javaClass.simpleName,
        ),
        informationalWarning = false,
    )

    // ------------------------------------------------------------------
    // T-IbatisFix2 修复一：<selectKey> 子语句展示
    // ------------------------------------------------------------------

    /** 渲染 `<selectKey>` 子语句（渲染器 `renderSelectKey` 已过保真对照，服务层此前漏调用）。 */
    private fun renderSelectKeys(
        statement: IbatisStatement,
        params: Any?,
    ): List<Pair<IbatisSelectKey, IbatisRenderResult>> {
        if (statement.selectKeys.isEmpty()) return emptyList()
        val renderer = SqlMapSqlRenderer()
        return statement.selectKeys.map { selectKey ->
            selectKey to runCatching { renderer.renderSelectKey(selectKey, params) }.getOrElse { error ->
                if (FatalErrors.isFatal(error)) throw error
                // selectKey 渲染失败不拖垮主语句：空块 + 失败档告警（可见失败，不静默丢块）
                IbatisRenderResult(
                    sql = "",
                    compactSql = "",
                    paramNames = emptyList(),
                    paramJdbcTypes = emptyList(),
                    paramNullValues = emptyList(),
                    nullBindFlags = emptyList(),
                    warnings = listOf(
                        MpSqlVisualizerBundle.message(
                            "ibatis.preview.selectKeyRenderFailed.warning",
                            selectKey.id,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    ),
                    dollarRawCount = 0,
                    dollarRenderedValues = emptyList(),
                    usedStatementIds = listOf(selectKey.id),
                    usedSqlFragmentIds = emptyList(),
                    degraded = true,
                )
            }
        }
    }

    /**
     * 多语句组装：**主语句恒在最前**，selectKey 块紧随其后，块间空行 + 注释分隔。
     *
     * ## 为什么主语句必须在最前（安全面，不许按引擎时序排）
     *
     * 预览产出的 `sql` 会流进执行链，而执行链**只认首条语句**：
     * - `execute/SqlTypeClassifier.getFirstKeyword` = `splitStatements(sql).firstOrNull()`，
     *   类型判定（QUERY/DML/DDL）据此决定是否放行只读路径；
     * - `execute/ExecutionScriptScope.shouldExecWholeScript` 在语句数 > 1 时返回 false，
     *   「执行 SQL」退化为「当前游标所在语句」。
     *
     * 若按引擎时序把 `type=pre` 的 selectKey 拼在最前，`insertUser` 预览的首条语句就是
     * `SELECT SEQ_USER_ID.NEXTVAL FROM DUAL` ⇒ 类型判成 QUERY（只读）⇒ **写操作被写安全闸
     * 当只读放过**，且用户点「执行 SQL」先跑的是序列查询而不是 INSERT。故物理顺序固定为
     * 主语句 → selectKey 块，真实时序只由块注释标明（`type=pre` + 「在主语句之前执行」）。
     *
     * ## 形态说明
     * 本仓 MyBatis 链的多语句展示走独立字段（`countSql`/`fillClauses` 由面板并列渲染），
     * **没有文本级多语句先例可抄**——iBatis 链不动面板，故用注释标注 + 分号的纯文本形态。
     */
    private fun assembleMultiStatementSql(
        main: IbatisRenderResult,
        selectKeyRenders: List<Pair<IbatisSelectKey, IbatisRenderResult>>,
    ): String? {
        if (selectKeyRenders.isEmpty()) return null
        val blocks = ArrayList<String>()
        fun appendBlock(comment: String, result: IbatisRenderResult) {
            val sql = result.sql.trim()
            blocks.add(
                when {
                    sql.isEmpty() -> comment
                    comment.isEmpty() -> sql + ";"
                    else -> comment + "\n" + sql + ";"
                },
            )
        }
        // 主语句恒在最前，且**不加块注释**——首字符必须是语句关键字本身：执行链按首条语句判类型
        // （见 KDoc），且用户复制出去的第一段就该是可直接执行的主语句，不能被 `--` 注释行打头。
        appendBlock("", main)
        selectKeyRenders.forEach { (sk, r) -> appendBlock(selectKeyComment(sk), r) }
        return blocks.joinToString(separator = "\n\n")
    }

    /**
     * selectKey 块注释：`type` 按 [IbatisSelectKey.runAfterSql]（引擎的显式 type + 位置推断合并口径），
     * 并**用文字标明真实执行时机**——物理上它排在主语句之后（执行链安全面要求），时序不能靠位置读。
     */
    private fun selectKeyComment(selectKey: IbatisSelectKey): String {
        val type = if (selectKey.runAfterSql) "post" else "pre"
        val timing = MpSqlVisualizerBundle.message(
            if (selectKey.runAfterSql) "ibatis.preview.selectKey.timing.post" else "ibatis.preview.selectKey.timing.pre",
        )
        val keyProperty = selectKey.attributes["keyProperty"]
        return if (keyProperty.isNullOrBlank()) {
            MpSqlVisualizerBundle.message("ibatis.preview.selectKey.blockCommentNoKey", type, timing)
        } else {
            MpSqlVisualizerBundle.message("ibatis.preview.selectKey.blockComment", type, keyProperty, timing)
        }
    }

    /** 多语句的紧凑形态（与 [assembleMultiStatementSql] 同序：主语句在前，分号收尾）。 */
    private fun assembleMultiStatementCompact(
        main: IbatisRenderResult,
        selectKeyRenders: List<Pair<IbatisSelectKey, IbatisRenderResult>>,
    ): String {
        val parts = ArrayList<String>()
        fun appendPart(result: IbatisRenderResult) {
            val compact = result.compactSql.trim()
            if (compact.isNotEmpty()) parts.add(compact + ";")
        }
        appendPart(main)
        selectKeyRenders.forEach { appendPart(it.second) }
        return parts.joinToString(separator = " ")
    }

    // ------------------------------------------------------------------
    // 语句定位
    // ------------------------------------------------------------------

    private fun locateStatement(
        model: IbatisSqlMap,
        statementId: String?,
        statementTagName: String?,
        statementOrdinal: Int,
    ): IbatisStatement? {
        if (!statementId.isNullOrBlank()) {
            model.statementById(statementId)?.let { return it }
        }
        val sameKind = model.statements.filter { statementTagName == null || it.kindTagName == statementTagName }
        val byOrdinal = sameKind.getOrNull(statementOrdinal)
        if (byOrdinal != null) return byOrdinal
        return model.statements.firstOrNull()
    }

    // ------------------------------------------------------------------
    // 产物转换（IbatisRenderResult → PreviewResult）
    // ------------------------------------------------------------------

    private fun toPreviewResult(
        rendered: IbatisRenderResult,
        statementHasRawSubstitutionSites: Boolean = false,
        iteratePending: Boolean = false,
        selectKeyRenders: List<Pair<IbatisSelectKey, IbatisRenderResult>> = emptyList(),
        /** T-IbatisParamClassQuote：属性名 → 引号提示（来自 parameterClass 的字段类型）。 */
        propertyHints: Map<String, ParamHint> = emptyMap(),
    ): PreviewResult {
        // T-IbatisFix2 修复一：selectKey 块参与 sql/compactSql 组装与参数名序（pre 前 / post 后）
        val assembledSql = assembleMultiStatementSql(rendered, selectKeyRenders)
        val sql = assembledSql ?: rendered.sql
        val compactSql = if (assembledSql != null) assembleMultiStatementCompact(rendered, selectKeyRenders) else rendered.compactSql
        val placeholders = placeholderPositions(sql).size
        // 参数名序 = SQL 文本里 `?` 的出现序（主语句块在前，selectKey 块按声明序在其后）——
        // 与拼装顺序严格同源，否则位点错位会让参数名贴到别的占位符上。
        val names = rendered.paramNames + selectKeyRenders.flatMap { it.second.paramNames }
        val unresolved = when {
            placeholders == 0 -> emptyList()
            names.size == placeholders -> names
            else -> (1..placeholders).map { "param$it" }
        }

        // T-IbatisFix3 缺陷三：`#x:VARCHAR#` 的 jdbcType 必须传到面板的回填提示链——否则
        // `WHERE EMAIL = #email:VARCHAR:0#` 填 11212 会按「纯数字」启发式裸渲染成 `= 11212`。
        // 只在**位点与参数名数量相符**时装配（数量不符走 paramN 兜底，名字与 jdbcType 已无法对齐，
        // 强行贴类型会把 A 列的类型挂到 B 位点上）；映射不到 jdbcType 的位点用默认 [ParamHint]
        // （quote=null = 面板启发式，与修复前逐字节一致）。
        val jdbcTypes = rendered.paramJdbcTypes + selectKeyRenders.flatMap { it.second.paramJdbcTypes }
        val paramHints = if (names.size == placeholders && jdbcTypes.size == placeholders) {
            // T-IbatisParamClassQuote：jdbcType 仍是首位（既有口径不动）；裸 `#name#`（jdbcType 缺省）
            // 才按 parameterClass 的属性类型补位——iBatis 引擎本身就是拿它反射决定 setString/setInt
            jdbcTypes.mapIndexed { index, jdbcType ->
                IbatisParamTypeHints.hintOf(jdbcType)
                    ?: propertyHints[names[index]]
                    ?: ParamHint()
            }
        } else {
            emptyList()
        }

        // UI-001：告警分档——「待填值」降级与「引擎行为说明」是信息级说明，不得包装成渲染失败。
        // 判据按来源自证，不猜文案全量：解析器对 parameterMap 自标「（引擎行为）」；iterate 未供值
        // 的降级 = 行清单里该行就在、填值即补齐。
        // T-IbatisFix2 附带小修：分档**过滤**——信息级说明与真失败分开收集，混合时 ⚠ 失败档合并行
        // 只保留真失败（信息级说明此前会混进 ⚠ 行、ℹ 行又重复同内容）；纯信息级整行走 ℹ。
        val failureWarnings = ArrayList<String>()
        val noteWarnings = ArrayList<String>()
        fun classify(warning: String) {
            if (isInformationalWarning(warning, iteratePending)) noteWarnings.add(warning) else failureWarnings.add(warning)
        }
        rendered.warnings.forEach(::classify)
        selectKeyRenders.forEach { (_, r) -> r.warnings.forEach(::classify) }

        if (placeholders > 0 && names.size != placeholders) {
            if (names.isEmpty()) {
                // 手写 `?` 的 `<statement>` / `#` 未转 `?` 的配置错配：参数名不可知——引擎行为说明（信息级）
                noteWarnings.add(MpSqlVisualizerBundle.message("ibatis.preview.paramNamesUnavailable.warning"))
            } else {
                // 数量不符是真错配（paramN 兜底），保持失败档
                failureWarnings.add(
                    MpSqlVisualizerBundle.message(
                        "ibatis.preview.paramCountMismatch.warning",
                        names.size,
                        placeholders,
                    ),
                )
            }
        }
        if (placeholders == 0 && names.isNotEmpty()) {
            // <parameterMap> 声明了参数、体内却是手写 ? 或 # 未转 ? ——参数行留空但要明说原因（信息级）
            noteWarnings.add(MpSqlVisualizerBundle.message("ibatis.preview.paramNamesUnavailable.warning"))
        }
        val warningsForLine = if (failureWarnings.isEmpty()) noteWarnings else failureWarnings

        return PreviewResult(
            sql = sql,
            compactSql = compactSql,
            unresolvedParams = unresolved,
            paramTable = ParameterReplacer.buildParamTable(unresolved),
            wrapperType = null,
            tableName = null,
            warning = joinWarnings(warningsForLine),
            // T-IbatisFix3 缺陷三：jdbcType → ParamKind → 引号提示（与 unresolvedParams 并行）
            paramHints = paramHints,
            // 安全面（计划册 §六.4「不得新开无闸门路径」）：
            // ① `$x$` **站点存在**即置闸——即使无参基线下取值为 null 被引擎静默丢弃（预览 SQL 里看不到
            //    那段文本），运行期它仍是原文直拼面，预览必须走既有 `${}` 确认通道而不是伪装成安全 SQL；
            //    T-Ibatis2Gap 券 A 补强：站点在**条件子树内**时无参基线连那段都渲染不出来（条件整段
            //    跳过），dollarRawCount=0 会漏闸——行清单里的 `$` 站点（statementHasRawSubstitutionSites）
            //    一并置闸，否则「勾上 isNotEmpty 填值后执行」绕过注入确认；
            // ② 未替换的 `${prop}`（引擎按全局属性替换、本链保留原文）在 SQL 文本里就是 `${}` 原文段；
            // ③ T-IbatisFix2 修复一：selectKey 块内的 `$` 站点同样计闸。
            hasDollarRawSegments =
                rendered.dollarRawCount > 0 || selectKeyRenders.any { it.second.dollarRawCount > 0 } ||
                    statementHasRawSubstitutionSites || sql.contains("\${"),
            informationalWarning = failureWarnings.isEmpty() && warningsForLine.isNotEmpty(),
        )
    }

    /** UI-001：信息性告警判据（按产出点自带标记，见 [toPreviewResult] 内注释）。 */
    private fun isInformationalWarning(warning: String, iteratePending: Boolean): Boolean = when {
        warning.contains("（引擎行为）") -> true
        iteratePending && warning.contains(DEGRADE_MARK) && warning.contains("iterate") -> true
        else -> false
    }

    /** 告警清单压成一行（面板 meta 单行渲染；超长截断，完整信息在 SQL 注释与日志里）。 */
    private fun joinWarnings(warnings: List<String>): String? =
        warnings.filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator = "；")
            .let { if (it.length > MAX_WARNING_CHARS) it.take(MAX_WARNING_CHARS) + "…" else it }
            .ifEmpty { null }

    private fun emptyResult(message: String): PreviewResult = PreviewResult(
        sql = "",
        compactSql = "",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = message,
    )

    private const val MAX_WARNING_CHARS = 300

    /** 渲染器降级告警的固定措辞（渲染器 catch 点 `渲染降级（按原文保留 # 直替…）`）。 */
    private const val DEGRADE_MARK = "渲染降级"
}
