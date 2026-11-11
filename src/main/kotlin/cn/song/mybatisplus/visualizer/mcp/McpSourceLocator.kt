package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-McpToolset sqlv_render_sql 定位与渲染适配层。
 *
 * 复刻 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 光标分支的服务层等价物：
 * 「文件 + 行号」→ PSI 锚点 → 触发面定位链（Wrapper 链 / MyBatis 注解 / mapper XML / HQL /
 * 自定义 DAO HQL / JdbcTemplate / @Query / 派生查询 / CriteriaQuery / 方法体字符串 SQL /
 * iBatis sqlMap 语句 / iBatis DAO 调用行）。iBatis 两档（T-IbatisCallPreview）与 Action 同序同判据
 * ——`JdbcTemplateSqlLocator` 不含 iBatis 让位门，若不在它之前拦下，sqlMap 会被 MyBatis 渲染器
 * 错渲、DAO 调用会被 JDBC 通道认领后拒绝。
 * 与 Action 的差异（MCP 约束）：
 *  - 全程 [ReadAction]（MCP 调用在平台协程线程，读 PSI 必须持读锁）；
 *  - 零弹窗：多候选不弹选择框，取首个候选并在结果中说明（方法体多 SQL / 文件级注解候选
 *    场景由 agent 换行号重试）；
 *  - Repository 调用点回落（Action 的 tryRepositoryCallSiteFallback）与文件级注解候选弹窗
 *    一期不带，命中失败即降级文案（写进实施记录）。
 */
object McpSourceLocator {

    /** 单次渲染结果（SQL 缺失时 [error] 非空——适配层转 McpExpectedError）。 */
    data class Outcome(
        val sql: String?,
        val sourceType: String?,
        val tableName: String?,
        val warning: String?,
        val paramTable: String?,
        val degraded: String? = null,
        val error: String? = null,
    )

    private fun fail(code: String, message: String) = Outcome(
        sql = null, sourceType = null, tableName = null, warning = null, paramTable = null,
        error = McpToolErrors.code(code, message),
    )

    /**
     * 「文件路径 + 行号」渲染（内部 ReadAction；行号 1-based，null = 首个可预览语句）。
     *
     * 路径形态经 [McpFilePathResolver] 统一（T-McpFilePathUnify，O-70）：项目相对路径与项目内
     * 绝对路径都收，项目外拒绝（安全边界，见该对象头注）。
     */
    fun renderAtLine(project: Project, filePath: String, line: Int?): Outcome {
        val outcome = ReadAction.compute<Outcome, Throwable> {
            val vFile = McpFilePathResolver.resolveFile(project, filePath)
                ?: return@compute fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "file not found in project: $filePath (pass a project-relative path, " +
                        "or an absolute path inside the project).",
                )
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                ?: return@compute fail(McpToolErrors.SOURCE_NOT_FOUND, "cannot parse file: $filePath")
            val document = psiFile.viewProvider.document
            val offset = when {
                document == null -> 0
                line == null -> 0
                else -> {
                    val lastLine = document.getLineNumber(document.textLength)
                    if (line - 1 > lastLine) return@compute fail(
                        McpToolErrors.SOURCE_NOT_FOUND,
                        "line $line is beyond the end of $filePath (${lastLine + 1} lines).",
                    )
                    // T-McpWalkFindings S1：定位锚取**行内首个非空白字符**——gutter/编辑器标注的
                    // 行号是语句标签行，而标签行行首是缩进空白 leaf（XML PSI 里挂父级 content），
                    // findElementAt 沿 parentOf 爬到的是 mapper 根而非语句 tag（真机 20261005 实测：
                    // line=标签行 SOURCE_NOT_FOUND、line=语句体首行命中）。行内首非空白直接落在
                    // 标签/语句文本上；纯空白行顺延下一行，全落空退回行首旧行为（少新增失败模式，
                    // 定位链自行报错）。
                    firstNonBlankOffset(document, line - 1) ?: document.getLineStartOffset(line - 1)
                }
            }
            renderAtOffset(project, psiFile, offset, filePath, line)
        }
        return outcome
    }

    /** 纯空白行顺延上限（起始行之外最多再试 2 行：标签行与语句体之间夹空行/注释行的容错边界）。 */
    private const val MAX_BLANK_LINE_SKIP = 2

    /**
     * 行内首个非空白字符偏移（[startLine] 0-based）；该行纯空白时顺延下一行，
     * 最多越界 [MAX_BLANK_LINE_SKIP] 行，全部落空返回 null。
     */
    private fun firstNonBlankOffset(document: com.intellij.openapi.editor.Document, startLine: Int): Int? {
        val textLength = document.textLength
        val lastLine = document.getLineNumber(textLength)
        val chars = document.charsSequence
        var line = startLine
        var skips = 0
        while (line <= lastLine && skips <= MAX_BLANK_LINE_SKIP) {
            var offset = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line).coerceAtMost(textLength)
            while (offset < end && Character.isWhitespace(chars[offset])) offset++
            if (offset < end) return offset
            line++
            skips++
        }
        return null
    }

    /**
     * mapper XML 片段直渲（XmlPreviewService 为 text 形态入口，零 PSI 锚点需求）。
     *
     * T-McpRenderDialectFix：「零 PSI 锚点」不等于零 PSI 读——text 链传 project 后会走跨 ns
     * include（T-IC-02）/静态常量求值（T-M25.2）/mapper 参数类型（M29.5）三处 PSI 读，
     * 平台协程线程（MCP 通道）无读锁即硬抛 read access（2026-09-23 真机实测 + 夹具回归
     * McpRenderSnippetReadActionTest 双证）。与 [renderAtLine] 同口径整链包 ReadAction。
     */
    fun renderXmlSnippet(project: Project, snippet: String): Outcome =
        ReadAction.compute<Outcome, Throwable> {
            val result = XmlPreviewService.preview(snippet, statementId = null, project = project)
            toOutcome(result, sourceType = "mapper XML (snippet)")
        }

    /** 统一入口：在 [psiFile] 的 [offset] 处按 13 类触发面定位渲染。 */
    private fun renderAtOffset(
        project: Project,
        psiFile: com.intellij.psi.PsiFile,
        offset: Int,
        filePath: String,
        line: Int?,
    ): Outcome {
        val location = "$filePath${line?.let { ":$it" } ?: ""}"
        val element = psiFile.findElementAt(offset.coerceAtMost(psiFile.textLength - 1).coerceAtLeast(0))
            ?: return fail(McpToolErrors.SOURCE_NOT_FOUND, "no code element at the given location ($location).")

        // mapper XML：光标所在语句 tag → 语句渲染（同 ConvertToSqlAction 分发语义）
        if (psiFile is XmlFile) {
            if (psiFile.rootTag?.name == "pages") {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "<pages> XML preview is not exposed via MCP; open it in the IDE.",
                )
            }
            var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
            while (tag != null && !isXmlStatementTag(tag)) {
                tag = tag.parentTag
            }
            if (tag == null) {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "no mapper statement (select/insert/update/delete) or iBatis sqlMap statement " +
                        "at the given location ($location)." +
                        " Pass the line of a statement tag or any line inside it.",
                )
            }
            // T-IbatisCallPreview：iBatis <sqlMap> 语句走自己的渲染链——`#name#` 语义与 MyBatis 的
            // `#{name}` 不同，交给 XmlPreviewService 会原样保留 # 而渲染成空参数。
            if (IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)) {
                val ibatisId = tag.getAttributeValue("id")
                return toOutcome(
                    IbatisStatementPreview.previewTag(tag),
                    sourceType = "iBatis sqlMap${ibatisId?.let { " ($it)" } ?: ""}",
                )
            }
            val statementId = tag.getAttributeValue("id")
            // T-M27.3/4 实体上下文注入（ConvertToSqlAction.previewXml 同链）：SourceSqlContext →
            // SqlCompletionContext（namespace → Mapper 接口 → 主实体 + @TableName 联表候选）
            val src = runCatching { XmlSqlCompletionContextResolver.resolveXml(project, tag) }.getOrNull()
            val entity = src?.main?.let { m ->
                SqlCompletionContext(m.tableName, m.columns, m.tableAlias, m.columnComments, m.tableComment, src.candidates)
            }
            val result = XmlPreviewService.preview(psiFile.text, statementId, project, entity)
            return toOutcome(result, sourceType = "mapper XML${statementId?.let { " ($it)" } ?: ""}")
        }

        // MyBatis SQL 注解（@Select/@Update/Provider 族）——先于 Wrapper 链（注解内光标无链可定位）
        val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java, false)
        if (annotation != null && AnnotationSqlPreviewService.isSqlAnnotation(annotation)) {
            return toOutcome(
                AnnotationSqlPreviewService.preview(annotation, project),
                sourceType = "annotation SQL",
            )
        }

        val service = project.getService(SqlPreviewService::class.java)
        service.preview(element)?.let { return toOutcome(it, sourceType = it.wrapperType?.name?.lowercase()?.replace("_", " ") ?: "Wrapper chain") }

        // iBatis DAO 调用行（T-IbatisCallPreview）：与 ConvertToSqlAction.tryIbatisCallFallback 同序
        // （先于 JDBC/HQL）——`JdbcTemplateSqlLocator` 不含 iBatis 让位门，晚一步就会被它认领后拒绝。
        IbatisCallTargets.resolve(element)?.let { target ->
            val tag = IbatisStatementPreview.resolveStatementTag(project, target.statementId, target.ownerNamespace)
                ?: return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "iBatis statement \"${target.statementId}\" was not found in any <sqlMap> file; " +
                        "check the statement id or the sqlMap file that declares it.",
                )
            return toOutcome(
                IbatisStatementPreview.previewTag(tag),
                sourceType = "iBatis (${target.statementId})",
            )
        }

        // HQL / 自定义 DAO HQL（同 Action 的 tryHqlFallback 顺序）
        HqlQueryLocator.locate(element)?.let {
            return toOutcome(service.previewHql(element), sourceType = "HQL")
        }
        CustomDaoHqlLocator.locate(element)?.let {
            return toOutcome(service.previewCustomDaoHql(element), sourceType = "custom DAO HQL")
        }

        // JdbcTemplate 字符串 SQL
        JdbcTemplateSqlLocator.locate(element)?.let {
            return toOutcome(JdbcTemplateSqlPreviewService.preview(element, project), sourceType = "JdbcTemplate")
        }

        // JPA：@Query → 派生查询 → CriteriaQuery（同 Action 优先序）
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null && JpqlQueryLocator.hasQueryAnnotation(method)) {
            return toOutcome(service.previewJpql(method), sourceType = "JPA @Query")
        }
        if (method != null && DerivedMethodLocator.isDerivedQuery(method)) {
            return toOutcome(service.previewDerivedQuery(method), sourceType = "JPA derived query")
        }
        CriteriaQueryLocator.locate(element)?.let {
            return toOutcome(service.previewCriteriaQuery(element), sourceType = "CriteriaQuery")
        }

        // 方法体 iBatis 候选（T-IbatisCallPreview）：与 ConvertToSqlAction.tryIbatisMethodFallback 同序，
        // 先于方法体字符串 SQL（后者同样没有 iBatis 让位门）。零弹窗取首个可解析目标。
        if (method != null) {
            val ibatisTargets = IbatisCallTargets.callsInMethod(method)
            for (target in ibatisTargets) {
                val tag = IbatisStatementPreview.resolveStatementTag(project, target.statementId, target.ownerNamespace)
                    ?: continue
                val degraded = if (ibatisTargets.size > 1) {
                    "method body has ${ibatisTargets.size} iBatis calls; rendering the first resolvable one — " +
                        "pass a line number inside another call to pick it."
                } else null
                return toOutcome(
                    IbatisStatementPreview.previewTag(tag),
                    sourceType = "iBatis (${target.statementId})",
                    degraded = degraded,
                )
            }
        }

        // 方法体字符串 SQL 多候选：零弹窗取首个（Action 弹选择框；agent 可换行号取其他候选）
        if (method != null && method.body != null) {
            val candidates = JdbcTemplateSqlPreviewService.collectMethodCandidates(method)
            if (candidates.isNotEmpty()) {
                val result = JdbcTemplateSqlPreviewService.preview(candidates.first(), method)
                val degraded = if (candidates.size > 1) {
                    "method body has ${candidates.size} string-SQL candidates; rendering the first one — " +
                        "pass a line number inside another call to pick it."
                } else null
                return toOutcome(result, sourceType = "string SQL in method body", degraded = degraded)
            }
        }

        // 兜底线索：该行是不是 Wrapper 链候选（给 agent 一个可操作的提示）
        val chainHint = WrapperLocator.sqlCandidatesInRange(psiFile, offset, offset).isNotEmpty()
        return fail(
            McpToolErrors.SOURCE_NOT_FOUND,
            "no renderable SQL source at $location." + if (chainHint) {
                " A Wrapper chain was detected nearby; retry with the exact line of the chain head call."
            } else {
                " Supported: MyBatis-Plus Wrapper chains, mapper XML statements, MyBatis annotation SQL, " +
                    "JPA (@Query/derived/CriteriaQuery), HQL, JdbcTemplate string SQL, " +
                    "iBatis <sqlMap> statements, iBatis DAO calls (SqlMapExecutor entry names with a statement id)."
            },
        )
    }

    /**
     * XML 语句判定：MyBatis mapper 4 类标签 ∪ iBatis `<sqlMap>` 6 类语句（T-IbatisCallPreview）。
     * 两族标签名有交集（select/insert/update/delete），按父标签分流——sqlMap 直接子级走 iBatis 渲染。
     */
    private fun isXmlStatementTag(tag: XmlTag): Boolean =
        tag.name in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS ||
            IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)

    /** PsiElement 锚点直渲（Wrapper 链头已定位场景——供 toolset 内部复用）。 */
    fun previewAnchor(project: Project, anchor: PsiElement): Outcome {
        val service = project.getService(SqlPreviewService::class.java)
        val result = service.preview(anchor) ?: return fail(
            McpToolErrors.SOURCE_NOT_FOUND,
            "the given location does not resolve to a renderable SQL source.",
        )
        return toOutcome(result, sourceType = result.wrapperType?.name?.lowercase()?.replace("_", " ") ?: "Wrapper chain")
    }

    private fun toOutcome(result: PreviewResult?, sourceType: String, degraded: String? = null): Outcome {
        if (result == null) {
            return fail(
                McpToolErrors.SOURCE_NOT_FOUND,
                "the source at the given location resolved to no SQL (unsupported shape).",
            )
        }
        return Outcome(
            sql = result.sql,
            sourceType = sourceType,
            tableName = result.tableName,
            warning = result.warning,
            paramTable = result.paramTable,
            degraded = degraded,
        )
    }
}
