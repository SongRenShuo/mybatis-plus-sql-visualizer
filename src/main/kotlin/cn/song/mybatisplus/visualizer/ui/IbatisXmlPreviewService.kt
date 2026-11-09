package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModel
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModelBuilder
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParseException
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisRenderResult
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisSqlMap
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatement
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapParser
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapSqlRenderer

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
 *    导致 `#` 未转 `?` / `${...}` 未支持 / `#` 格式错 等）。
 *
 * ## 参数名口径（拍板 D-7）
 *
 * `IbatisRenderResult.paramNames` 按 `?` 出现顺序给出参数名（iBatis `#name#` 语义）。三种不齐形态：
 * - **数量相符** → 直接用参数名（面板列出 `name` 而不是 `param1`）；
 * - **数量不符**（如 `<statement>` 手写 `?`、`#` 保留未转、iterate 内重解析）→ 按 `param1..N` 兜底
 *   + 告警说明；
 * - **参数名非空但无 `?`**（声明 parameterMap 且体内 `#` 未转 `?`）→ 参数行留空 + 告警说明
 *   「参数名不可知」，与通用兜底通道同口径（不显示假的空参数表）。
 */
object IbatisXmlPreviewService {

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
        return toPreviewResult(rendered, statementHasRawSubstitutionSites = paramModel.dollarRows.isNotEmpty()).copy(
            ibatisParamModel = paramModel,
            rerender = { input -> rerender(statement, paramModel, input) },
        )
    }

    /** 面板状态 → Map 装配 → 既有渲染器（渲染器零改动；空装配回退 null = 无参基线，与现状一致）。 */
    private fun rerender(statement: IbatisStatement, paramModel: IbatisParamModel, input: PreviewRerenderInput): PreviewResult {
        val params = IbatisParamModelBuilder.assembleParams(paramModel, input)
        val rendered = try {
            SqlMapSqlRenderer().render(statement, params)
        } catch (e: RuntimeException) {
            return emptyResult(e.message ?: MpSqlVisualizerBundle.message("ibatis.preview.renderFailed.warning"))
        }
        return toPreviewResult(rendered, statementHasRawSubstitutionSites = paramModel.dollarRows.isNotEmpty())
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

    private fun toPreviewResult(rendered: IbatisRenderResult, statementHasRawSubstitutionSites: Boolean = false): PreviewResult {
        val sql = rendered.sql
        val placeholders = placeholderPositions(sql).size
        val names = rendered.paramNames
        val unresolved = when {
            placeholders == 0 -> emptyList()
            names.size == placeholders -> names
            else -> (1..placeholders).map { "param$it" }
        }

        val warnings = ArrayList(rendered.warnings)
        if (placeholders > 0 && names.size != placeholders) {
            warnings.add(
                if (names.isEmpty()) {
                    // 手写 `?` 的 `<statement>` / `#` 未转 `?` 的配置错配：参数名不可知
                    MpSqlVisualizerBundle.message("ibatis.preview.paramNamesUnavailable.warning")
                } else {
                    MpSqlVisualizerBundle.message(
                        "ibatis.preview.paramCountMismatch.warning",
                        names.size,
                        placeholders,
                    )
                },
            )
        }
        if (placeholders == 0 && names.isNotEmpty()) {
            // <parameterMap> 声明了参数、体内却是手写 ? 或 # 未转 ? ——参数行留空但要明说原因
            warnings.add(MpSqlVisualizerBundle.message("ibatis.preview.paramNamesUnavailable.warning"))
        }

        return PreviewResult(
            sql = sql,
            compactSql = rendered.compactSql,
            unresolvedParams = unresolved,
            paramTable = ParameterReplacer.buildParamTable(unresolved),
            wrapperType = null,
            tableName = null,
            warning = joinWarnings(warnings),
            // 安全面（计划册 §六.4「不得新开无闸门路径」）：
            // ① `$x$` **站点存在**即置闸——即使无参基线下取值为 null 被引擎静默丢弃（预览 SQL 里看不到
            //    那段文本），运行期它仍是原文直拼面，预览必须走既有 `${}` 确认通道而不是伪装成安全 SQL；
            //    T-Ibatis2Gap 券 A 补强：站点在**条件子树内**时无参基线连那段都渲染不出来（条件整段
            //    跳过），dollarRawCount=0 会漏闸——行清单里的 `$` 站点（statementHasRawSubstitutionSites）
            //    一并置闸，否则「勾上 isNotEmpty 填值后执行」绕过注入确认；
            // ② 未替换的 `${prop}`（引擎按全局属性替换、本链保留原文）在 SQL 文本里就是 `${}` 原文段。
            hasDollarRawSegments =
                rendered.dollarRawCount > 0 || statementHasRawSubstitutionSites || sql.contains("\${"),
        )
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
}
