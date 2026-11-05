package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SourceLanguage
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.PagesEntityProbe
import cn.song.mybatisplus.visualizer.xml.PagesFrameworkPlaceholder
import cn.song.mybatisplus.visualizer.xml.PagesSourceDetection
import cn.song.mybatisplus.visualizer.xml.PagesSourceEvidence
import cn.song.mybatisplus.visualizer.xml.PagesSourceLanguage
import cn.song.mybatisplus.visualizer.xml.PagesSourceLanguageDetector
import cn.song.mybatisplus.visualizer.xml.PagesStatement
import cn.song.mybatisplus.visualizer.xml.PagesXmlParseException
import cn.song.mybatisplus.visualizer.xml.PagesXmlParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlTag

/**
 * maintainPage 预览服务（F-45）：XML 文本 → 语句 → 预览模型 → [PreviewResult]。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位 maintainPage id；
 * 弹窗与面板完全复用 [SqlPreviewService.showResult] 链路。
 *
 * - [maintainPageId] 为 null 或未命中 → 取文件首个 maintainPage；
 * - 解析失败（非 <pages> / 非良构）→ 返回带 warning 的空结果（面板红色提示，不抛异常）；
 * - 参数对照表复用 [ParameterReplacer.buildParamTable]；
 * - [project] 非 null 时解析 entityClass → 补全上下文（表名/列名/注释）。
 *
 * T-PagesHql（2026-11-05）：`<datasource>` 的内容既可能是原生 SQL，也可能是 HQL。
 * 判定见 [PagesSourceLanguageDetector]（`type` 显式 > 根标识符与 entityClass 的关系 > 根标识符
 * 可解析为实体；默认 SQL 保守）。命中 HQL 时复用 [SqlPreviewService.previewHbmNamedQuery] 的
 * 既有管道转换，不另造一条；转换失败/实体不可解析一律回退原文直显 + warning，绝不空白。
 */
object PagesXmlPreviewService {

    private val LOG = Logger.getInstance(PagesXmlPreviewService::class.java)

    fun preview(
        fileText: String,
        maintainPageId: String?,
        project: Project? = null,
        /**
         * T-PagesHql：光标/图标所在的 `<maintainPage>` 标签（可空）。传入后 HQL 转换会以该标签
         * 作为 PSI 宿主锚点，按**该 XML 所属模块**收窄实体检索范围；null 时退化为全工程范围。
         */
        contextTag: XmlTag? = null,
    ): PreviewResult {
        val pagesModel = try {
            PagesXmlParser().parse(fileText)
        } catch (e: PagesXmlParseException) {
            return emptyResult(e.message ?: "XML 解析失败。")
        }

        val statement = pagesModel.statements.firstOrNull { it.id == maintainPageId }
            ?: pagesModel.statements.firstOrNull()
            ?: return emptyResult("未找到可预览的 SQL 语句（<pages> 中无 <maintainPage>）。")

        val detection = detect(statement, project, contextTag)
        if (detection.language == PagesSourceLanguage.HQL) {
            LOG.info(
                "[T-PagesHql] page=${statement.id} evidence=${detection.evidence.id} " +
                    "root=${detection.rootIdentifier}",
            )
            return previewAsHql(statement, project, contextTag, detection)
        }
        return toResult(statement, project)
    }

    // ------------------------------------------------------------------
    // T-PagesHql：语言判定
    // ------------------------------------------------------------------

    private fun detect(
        statement: PagesStatement,
        project: Project?,
        contextTag: XmlTag?,
    ): PagesSourceDetection {
        val root = runCatching { HqlEntityNameExtractor.extract(statement.sql) }.getOrNull()
        when (sourceMode()) {
            PluginSettings.PAGES_SOURCE_SQL ->
                return PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.DEFAULT_SQL, root)
            PluginSettings.PAGES_SOURCE_HQL ->
                return PagesSourceDetection(PagesSourceLanguage.HQL, PagesSourceEvidence.EXPLICIT_TYPE, root)
        }
        val probe: (String) -> PagesEntityProbe? = if (project == null) {
            { null }
        } else {
            { name -> resolveProbe(project, name, contextTag) }
        }
        return PagesSourceLanguageDetector.detect(
            sql = statement.sql,
            entityClassName = statement.entityClass.takeIf { it.isNotBlank() },
            declaredType = statement.declaredType,
            rootIdentifierOf = HqlEntityNameExtractor::extract,
            probeEntity = probe,
        )
    }

    /** 设置读取兜底：测试/未注册 service 环境按 AUTO。 */
    private fun sourceMode(): String = runCatching {
        PluginSettings.getInstance().state.pagesXmlSourceMode
    }.getOrDefault(PluginSettings.PAGES_SOURCE_AUTO)

    /** 实体探针：名字 → (实体名, 表名)；解析不到返回 null。 */
    private fun resolveProbe(project: Project, name: String, context: PsiElement?): PagesEntityProbe? =
        runCatching {
            val clazz = resolveEntityClass(project, name, context) ?: return null
            val table = JpaEntityResolver().resolve(clazz, context)
            PagesEntityProbe(
                entityName = JpaMetadataReader.entityNameOf(clazz)?.takeIf { it.isNotBlank() } ?: clazz.name,
                tableName = table.tableName.takeIf { it.isNotBlank() },
            )
        }.getOrNull()

    private fun resolveEntityClass(project: Project, name: String, context: PsiElement?): PsiClass? {
        val byName = runCatching { EntityAliasSweep.resolve(project, name, context).psiClass }.getOrNull()
        if (byName != null) return byName
        // FQCN 兜底：别名扫描在 dumb 态直接 unresolved，限定名还能走 findClass
        if (!name.contains('.')) return null
        return runCatching {
            JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project))
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // T-PagesHql：HQL 转换（复用既有管道）
    // ------------------------------------------------------------------

    private fun previewAsHql(
        statement: PagesStatement,
        project: Project?,
        contextTag: XmlTag?,
        detection: PagesSourceDetection,
    ): PreviewResult {
        val raw = statement.sql
        if (project == null) {
            // 纯文本环境（单测/无工程）：能判定但不能转换——保留原文，明确告知原因
            return toResult(statement, project).copy(
                sourceLanguage = SourceLanguage.HQL,
                originalSql = raw,
                warning = MpSqlVisualizerBundle.message("pages.hql.needIndex"),
            )
        }
        val rewritten = PagesFrameworkPlaceholder.rewrite(raw)
        val anchor: XmlTag? = contextTag?.findFirstSubTag("datasource") ?: contextTag
        val converted = runCatching {
            project.getService(SqlPreviewService::class.java).previewHbmNamedQuery(
                HbmXmlMapping.NamedQuery(
                    name = "",
                    kind = HbmXmlMapping.QueryKind.HQL,
                    queryText = rewritten.text,
                ),
                definitionTag = anchor,
                context = anchor,
            )
        }.getOrNull()

        if (converted == null || converted.sql.contains(UNRESOLVED_TABLE)) {
            // 转换管道异常 / 根实体不可解析：回退原文直显（验收底线：至少能看到原文）
            return toResult(statement, project).copy(
                sourceLanguage = SourceLanguage.HQL,
                originalSql = raw,
                warning = MpSqlVisualizerBundle.message(
                    if (converted == null) "pages.hql.fallback" else "pages.hql.entityUnresolved",
                ),
            )
        }

        val unresolved = converted.unresolvedParams.map { rewritten.displayNames[it] ?: it }
        val placeholderNames = converted.placeholderParamNames.map { rewritten.displayNames[it] ?: it }
        val warning = mergeWarnings(converted.warning, converted.degradations)
        LOG.info(
            "[T-PagesHql] converted page=${statement.id} evidence=${detection.evidence.id} " +
                "params=${unresolved.size} degradations=${converted.degradations.size}",
        )
        return converted.copy(
            unresolvedParams = unresolved,
            paramTable = ParameterReplacer.buildParamTable(unresolved),
            placeholderParamNames = placeholderNames,
            pagesColumns = statement.columns.ifEmpty { null },
            sourceLanguage = SourceLanguage.HQL,
            originalSql = raw,
            warning = warning,
        )
    }

    /** 降级清单压成一行（面板 meta 单行渲染；超长截断，完整信息在日志与 SQL 注释里）。 */
    private fun mergeWarnings(base: String?, degradations: List<String>): String? =
        (listOfNotNull(base?.takeIf { it.isNotBlank() }) + degradations.filter { it.isNotBlank() })
            .distinct()
            .joinToString("；")
            .let { if (it.length > MAX_WARNING_CHARS) it.take(MAX_WARNING_CHARS) + "…" else it }
            .ifEmpty { null }

    // ------------------------------------------------------------------
    // 原生 SQL 路径（既有行为，零变化）
    // ------------------------------------------------------------------

    /** PagesStatement → PreviewResult（原生 SQL 路径）。 */
    private fun toResult(statement: PagesStatement, project: Project?): PreviewResult {
        val sql = statement.sql
        val params = extractPlaceholders(sql)
        val entityContext = resolveEntityContext(statement.entityClass, project)
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(WHITESPACE_REGEX, " ").trim(),
            unresolvedParams = params,
            paramTable = ParameterReplacer.buildParamTable(params),
            wrapperType = null,
            tableName = entityContext?.tableName,
            columns = entityContext?.columns.orEmpty(),
            columnComments = entityContext?.columnComments.orEmpty(),
            tableComment = entityContext?.tableComment,
            warning = null,
            xmlModel = null,
            previewModel = null,
            pagesColumns = statement.columns.ifEmpty { null },
        )
    }

    /** 实体类推断：全限定名 → PSI findClass → EntityResolver → SqlCompletionContext。 */
    private fun resolveEntityContext(entityClass: String?, project: Project?): SqlCompletionContext? {
        if (entityClass.isNullOrBlank() || project == null) return null
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(entityClass, GlobalSearchScope.allScope(project)) ?: return null
        val tableInfo = EntityResolver().resolve(psiClass) ?: return null
        return SqlCompletionContext(
            tableName = tableInfo.tableName,
            columns = tableInfo.allColumnNames,
            tableAlias = null,
            columnComments = tableInfo.columns.mapNotNull { col ->
                col.comment?.takeIf { it.isNotBlank() }?.let { col.columnName to it }
            }.toMap(),
            tableComment = tableInfo.comment,
        )
    }

    /**
     * 提取 `?` 占位符 → param1..N。
     *
     * T-PagesHql D-6：改用 string-aware 位点（[placeholderPositions]），与服务端面板回填
     * （`PlaceholderPositions` 同一份口径）对齐——旧实现 `sql.count { it == '?' }` 会把字符串
     * 字面量与注释里的 `?` 也算成参数，导致参数行条数与可填位点错配。
     */
    private fun extractPlaceholders(sql: String): List<String> {
        val count = placeholderPositions(sql).size
        return if (count == 0) emptyList() else (1..count).map { "param$it" }
    }

    private fun emptyResult(message: String): PreviewResult = PreviewResult(
        sql = "",
        compactSql = "",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = message,
    )

    private const val UNRESOLVED_TABLE = "unknown_table"
    private const val MAX_WARNING_CHARS = 300
    private val WHITESPACE_REGEX = Regex("\\s+")
}
