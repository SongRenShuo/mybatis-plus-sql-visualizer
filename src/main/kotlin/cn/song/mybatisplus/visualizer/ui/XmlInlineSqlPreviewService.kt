package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SourceLanguage
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.PagesSourceLanguage
import cn.song.mybatisplus.visualizer.xml.PagesSourceLanguageDetector
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlTag

/**
 * 通用 XML 内嵌 SQL 预览服务（O-72 T-XmlInlineSql）：载体标签 → [PreviewResult]。
 *
 * - 源语言判定（T-PagesHql 泛化）：`type` ∈ {hql, jpql}（就近祖先优先）为显式 HQL；
 *   无 `type` 时按 [PagesSourceLanguageDetector] 自动推断（载体标签可带 `entityClass` 属性，
 *   也可完全不带——靠「根标识符能解析为持久化实体」反查，如 `<dataValue>`）；
 *   券 0 起该探针为**零扫描**形态（直查 + 只读别名缓存，见 [PagesEntityProbeResolver]），
 *   弹窗不再等全项目别名反查；结论与改动前逐值一致（反查触顶本来就按未解析返回）；
 * - 判为 HQL → 复用 hbm 命名查询管道（[SqlPreviewService.previewHbmNamedQuery]，
 *   name 传空即走 FROM 子句实体提取）；**管道异常 / 实体不可解析 → 回退原文直显 + warning，
 *   不抛错**（验收底线：至少展示原文）；
 * - 其余 → 原生 SQL 直显（`?` 占位符 → param 表，string-aware 计数）。
 *
 * 输入是已定位的载体 XmlTag（gutter / Action 负责定位），本服务在后台线程调用。
 */
object XmlInlineSqlPreviewService {

    fun preview(project: Project?, tag: XmlTag): PreviewResult {
        val sql = XmlInlineSqlDetector.extractSqlText(tag)
        if (sql.isBlank()) {
            return emptyResult(MpSqlVisualizerBundle.message("xmlInlineSql.empty.warning"))
        }
        if (project == null) {
            // 无工程：能展示但不能判/转 HQL，保持原文直显（历史行为）
            return plainResult(sql)
        }
        // 与 <pages> 链共用同一档位：仅 SQL 时关闭自动 HQL（逃生舱对两条链都生效）
        val mode = runCatching { PluginSettings.getInstance().state.pagesXmlSourceMode }
            .getOrDefault(PluginSettings.PAGES_SOURCE_AUTO)
        if (mode == PluginSettings.PAGES_SOURCE_SQL) {
            return plainResult(sql)
        }
        val declaredType = if (mode == PluginSettings.PAGES_SOURCE_HQL) {
            "hql"
        } else {
            XmlInlineSqlDetector.queryTypeOf(tag)
        }
        val detection = PagesSourceLanguageDetector.detect(
            sql = sql,
            entityClassName = tag.getAttributeValue("entityClass"),
            declaredType = declaredType,
            rootIdentifierOf = HqlEntityNameExtractor::extract,
            // 券 0（T-Ibatis2SlowFix）：同步路径只走零扫描探针（直查 + 只读别名缓存）。
            // 全项目短名扫描形态的 `@Entity(name)` 反查会让弹窗等约 1s（历史同形态 70s 冻结 EDT），
            // 而本链的根标识符多是物理表名、扫描必然打空——见 PagesEntityProbeResolver 类注释。
            probeEntity = { name -> PagesEntityProbeResolver.probeCheap(project, name, tag) },
        )
        if (detection.language != PagesSourceLanguage.HQL) {
            return plainResult(sql)
        }
        val converted = runCatching {
            project.getService(SqlPreviewService::class.java)
                .previewHbmNamedQuery(
                    HbmXmlMapping.NamedQuery(
                        name = "",
                        kind = HbmXmlMapping.QueryKind.HQL,
                        queryText = sql,
                    ),
                    definitionTag = tag,
                    context = tag,
                )
        }.getOrNull()
        if (converted != null && !converted.sql.contains(UNRESOLVED_TABLE)) {
            return converted.copy(
                sourceLanguage = SourceLanguage.HQL,
                originalSql = sql,
            )
        }
        // 管道异常 / 根实体不可解析 → 回退原文直显（不抛错）
        return plainResult(sql).copy(
            sourceLanguage = SourceLanguage.HQL,
            originalSql = sql,
            warning = MpSqlVisualizerBundle.message(
                if (converted == null) "xmlInlineSql.hqlFallback.warning" else "pages.hql.entityUnresolved",
            ),
        )
    }

    /** 原生 SQL 直显（`?` 占位符 → param1..N，string-aware 计数）。 */
    private fun plainResult(sql: String): PreviewResult {
        val params = extractPlaceholders(sql)
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(WHITESPACE_REGEX, " ").trim(),
            unresolvedParams = params,
            paramTable = ParameterReplacer.buildParamTable(params),
            wrapperType = null,
            tableName = null,
        )
    }

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
    private val WHITESPACE_REGEX = Regex("\\s+")
}
