package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.ExternalTemplateSettings
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
import java.io.File

/**
 * T-McpToolset sqlv_render_sql 定位与渲染适配层。
 *
 * 复刻 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 光标分支的服务层等价物：
 * 「文件 + 行号」→ PSI 锚点 → 13 类触发面定位链（Wrapper 链 / MyBatis 注解 / mapper XML /
 * HQL / 自定义 DAO HQL / JdbcTemplate / @Query / 派生查询 / CriteriaQuery / 方法体字符串 SQL）。
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
     *
     * T-TemplateHook：`paramsJson` 是可选入参（默认 null）——只在外部模板渲染分支里作为
     * `{paramsJson}` 替身交给用户命令，**不参与任何日志/错误文本**。读锁只包 PSI 定位；
     * 外部命令在锁外执行（见 [RenderStep]）。
     */
    fun renderAtLine(project: Project, filePath: String, line: Int?, paramsJson: String? = null): Outcome {
        val step = ReadAction.compute<RenderStep, Throwable> {
            val vFile = McpFilePathResolver.resolveFile(project, filePath)
                ?: return@compute RenderStep.Direct(
                    fail(
                        McpToolErrors.SOURCE_NOT_FOUND,
                        "file not found in project: $filePath (pass a project-relative path, " +
                            "or an absolute path inside the project).",
                    ),
                )
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                ?: return@compute RenderStep.Direct(
                    fail(McpToolErrors.SOURCE_NOT_FOUND, "cannot parse file: $filePath"),
                )
            val document = psiFile.viewProvider.document
            val offset = when {
                document == null -> 0
                line == null -> 0
                else -> {
                    val lastLine = document.getLineNumber(document.textLength)
                    if (line - 1 > lastLine) return@compute RenderStep.Direct(
                        fail(
                            McpToolErrors.SOURCE_NOT_FOUND,
                            "line $line is beyond the end of $filePath (${lastLine + 1} lines).",
                        ),
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
            renderAtOffset(project, psiFile, offset, filePath, line, paramsJson)
        }
        return step.resolve()
    }

    /**
     * 读锁内产出的渲染步骤：外部命令必须在读锁**外**执行——进程可能跑满超时（默认 10s、上限 120s），
     * 持读锁等它会把写动作（用户键入等）一起卡住。PSI 侧只做「找到 tag + 快照请求参数」。
     */
    private sealed class RenderStep {
        /** 锁内直接得出结果（原生 13 类触发面链）。 */
        class Direct(val outcome: Outcome) : RenderStep()

        /** 需要跑一次外部命令（锁外执行，见 [runExternalRender]）。 */
        class External(val request: ExternalRequest) : RenderStep()

        fun resolve(): Outcome = when (this) {
            is Direct -> outcome
            is External -> runExternalRender(request)
        }
    }

    /**
     * 一次外部渲染请求（读锁内快照，锁外不再碰 PSI）。
     *
     * 不为参数 JSON 做任何日志——它可能含业务参数值（安全红线见 [ExternalTemplateRenderer] 头注）。
     */
    private data class ExternalRequest(
        val templatePath: String,
        val tagName: String,
        val sqlId: String?,
        val paramsJson: String?,
        val commandTemplate: String,
        val workingDirectory: File?,
        val timeoutSeconds: Int,
    )

    /** 锁外执行外部命令并把结果映射为 [Outcome]（失败一律结构化 [McpToolErrors.EXTERNAL_RENDER_FAILED]）。 */
    private fun runExternalRender(request: ExternalRequest): Outcome {
        val tokenized = ExternalTemplateRenderer.tokenize(request.commandTemplate)
        if (tokenized.error != null) {
            return fail(
                McpToolErrors.EXTERNAL_RENDER_FAILED,
                "external template command template is invalid: ${tokenized.error}",
            )
        }
        val command = ExternalTemplateRenderer.substitute(
            tokenized.tokens,
            request.templatePath,
            request.sqlId,
            request.paramsJson,
        )
        val result = ExternalTemplateRenderer.run(command, request.workingDirectory, request.timeoutSeconds)
        if (result.error != null) {
            return fail(McpToolErrors.EXTERNAL_RENDER_FAILED, result.error)
        }
        val sql = result.sql
            ?: return fail(McpToolErrors.EXTERNAL_RENDER_FAILED, "external renderer produced no SQL.")
        val id = request.sqlId?.takeIf { it.isNotBlank() }
        val sourceType = if (id != null) {
            "external template (${request.tagName}#$id)"
        } else {
            "external template (${request.tagName})"
        }
        return Outcome(
            sql = sql,
            sourceType = sourceType,
            tableName = null,
            warning = null,
            paramTable = null,
            degraded = if (result.truncated) {
                "external renderer output exceeded ${ExternalTemplateRenderer.MAX_OUTPUT_BYTES} bytes and was truncated."
            } else {
                null
            },
        )
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
        paramsJson: String?,
    ): RenderStep {
        val location = "$filePath${line?.let { ":$it" } ?: ""}"
        val element = psiFile.findElementAt(offset.coerceAtMost(psiFile.textLength - 1).coerceAtLeast(0))
            ?: return RenderStep.Direct(
                fail(McpToolErrors.SOURCE_NOT_FOUND, "no code element at the given location ($location)."),
            )

        // mapper XML：光标所在语句 tag → 语句渲染（同 ConvertToSqlAction 分发语义）
        if (psiFile is XmlFile) {
            if (psiFile.rootTag?.name == "pages") {
                return RenderStep.Direct(
                    fail(
                        McpToolErrors.SOURCE_NOT_FOUND,
                        "<pages> XML preview is not exposed via MCP; open it in the IDE.",
                    ),
                )
            }

            // T-TemplateHook S3 挂点①：外部模板渲染（自研格式如 <sqls>/<sql id>）。必须在
            // STATEMENT_TAGS 爬升**之前**判定——`<sql>` 不在 mapper 语句名单里，先爬升就直接
            // 落到 SOURCE_NOT_FOUND（FR-0045 用户报障点）。默认关闭时 `isActive()` 恒 false，
            // 整块不执行，行为与接线前逐字节一致。
            val externalSettings = ExternalTemplateSettings.getInstanceOrNull()
            if (externalSettings != null && externalSettings.isActive()) {
                val tagAtCursor = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
                if (tagAtCursor != null && externalSettings.isStatementTag(tagAtCursor.name)) {
                    externalStep(project, psiFile, tagAtCursor, externalSettings, paramsJson)
                        ?.let { return it }
                }
            }

            var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
            while (tag != null && tag.name !in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) {
                tag = tag.parentTag
            }
            if (tag == null) {
                // 挂点②：链尾兜底——向上找任一带 id 属性的 tag（<sql id> 的子节点/嵌套 FreeMarker
                // 标签场景）再试一次外部渲染；仍不命中则保留原错误文案（一字不改）。
                if (externalSettings != null && externalSettings.isActive()) {
                    val fallbackTag = nearestAncestorWithId(element)
                    if (fallbackTag != null) {
                        externalStep(project, psiFile, fallbackTag, externalSettings, paramsJson)?.let { return it }
                    }
                }
                return RenderStep.Direct(
                    fail(
                        McpToolErrors.SOURCE_NOT_FOUND,
                        "no mapper statement (select/insert/update/delete) at the given location ($location)." +
                            " Pass the line of a statement tag or any line inside it.",
                    ),
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
            return RenderStep.Direct(
                toOutcome(result, sourceType = "mapper XML${statementId?.let { " ($it)" } ?: ""}"),
            )
        }

        // MyBatis SQL 注解（@Select/@Update/Provider 族）——先于 Wrapper 链（注解内光标无链可定位）
        val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java, false)
        if (annotation != null && AnnotationSqlPreviewService.isSqlAnnotation(annotation)) {
            return RenderStep.Direct(
                toOutcome(
                    AnnotationSqlPreviewService.preview(annotation, project),
                    sourceType = "annotation SQL",
                ),
            )
        }

        val service = project.getService(SqlPreviewService::class.java)
        service.preview(element)?.let {
            return RenderStep.Direct(
                toOutcome(it, sourceType = it.wrapperType?.name?.lowercase()?.replace("_", " ") ?: "Wrapper chain"),
            )
        }

        // HQL / 自定义 DAO HQL（同 Action 的 tryHqlFallback 顺序）
        HqlQueryLocator.locate(element)?.let {
            return RenderStep.Direct(toOutcome(service.previewHql(element), sourceType = "HQL"))
        }
        CustomDaoHqlLocator.locate(element)?.let {
            return RenderStep.Direct(toOutcome(service.previewCustomDaoHql(element), sourceType = "custom DAO HQL"))
        }

        // JdbcTemplate 字符串 SQL
        JdbcTemplateSqlLocator.locate(element)?.let {
            return RenderStep.Direct(
                toOutcome(JdbcTemplateSqlPreviewService.preview(element, project), sourceType = "JdbcTemplate"),
            )
        }

        // JPA：@Query → 派生查询 → CriteriaQuery（同 Action 优先序）
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null && JpqlQueryLocator.hasQueryAnnotation(method)) {
            return RenderStep.Direct(toOutcome(service.previewJpql(method), sourceType = "JPA @Query"))
        }
        if (method != null && DerivedMethodLocator.isDerivedQuery(method)) {
            return RenderStep.Direct(toOutcome(service.previewDerivedQuery(method), sourceType = "JPA derived query"))
        }
        CriteriaQueryLocator.locate(element)?.let {
            return RenderStep.Direct(toOutcome(service.previewCriteriaQuery(element), sourceType = "CriteriaQuery"))
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
                return RenderStep.Direct(
                    toOutcome(result, sourceType = "string SQL in method body", degraded = degraded),
                )
            }
        }

        // 兜底线索：该行是不是 Wrapper 链候选（给 agent 一个可操作的提示）
        val chainHint = WrapperLocator.sqlCandidatesInRange(psiFile, offset, offset).isNotEmpty()
        return RenderStep.Direct(
            fail(
                McpToolErrors.SOURCE_NOT_FOUND,
                "no renderable SQL source at $location." + if (chainHint) {
                    " A Wrapper chain was detected nearby; retry with the exact line of the chain head call."
                } else {
                    " Supported: MyBatis-Plus Wrapper chains, mapper XML statements, MyBatis annotation SQL, " +
                        "JPA (@Query/derived/CriteriaQuery), HQL, JdbcTemplate string SQL."
                },
            ),
        )
    }

    /**
     * 构造一次外部模板渲染步骤（读锁内只做快照：模板绝对路径 / tag 名 / id / 命令面）。
     *
     * 返回 null 表示「无法构造」（无虚拟文件路径）——调用方与未命中同路，继续原定位链/原错误文案。
     * 工作目录优先级：设置里的工作目录（须存在）> 项目根（`project.basePath`）> null（继承进程目录）。
     */
    private fun externalStep(
        project: Project,
        psiFile: com.intellij.psi.PsiFile,
        tag: XmlTag,
        settings: ExternalTemplateSettings,
        paramsJson: String?,
    ): RenderStep? {
        val templatePath = psiFile.virtualFile?.path ?: return null
        val workingDir = settings.workingDirectoryOrNull()
            ?: project.basePath?.let { File(it) }
        return RenderStep.External(
            ExternalRequest(
                templatePath = templatePath,
                tagName = tag.name,
                sqlId = tag.getAttributeValue("id"),
                paramsJson = paramsJson,
                commandTemplate = settings.commandTemplate,
                workingDirectory = workingDir,
                timeoutSeconds = settings.effectiveTimeoutSeconds(),
            ),
        )
    }

    /** 光标元素的最近「带非空 id 属性」祖先 tag（含自身）——挂点②的 `{sqlId}` 来源。 */
    private fun nearestAncestorWithId(element: PsiElement): XmlTag? {
        var tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        while (tag != null) {
            if (!tag.getAttributeValue("id").isNullOrBlank()) return tag
            tag = tag.parentTag
        }
        return null
    }

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
