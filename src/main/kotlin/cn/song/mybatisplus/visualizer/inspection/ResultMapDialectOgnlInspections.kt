package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.xml.XmlTag

/**
 * 规则 3：**`<resultMap>` 属性在实体类里不存在**（NEW-26）。
 *
 * 只对「`type` 能解析到类」的 resultMap 判定（[MapperSqlElementSupport.typeMemberNames] 返回 null 即沉默）；
 * `<id>` / `<result>` / `<arg>` 的 `property` 取首段与类的字段/getter 派生属性名比对。
 * 属性链（`property="a.b"`）只判首段——深层要逐段解析类型，静态不可靠，判了会误报。
 */
class ResultMapPropertyInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.RESULT_MAP_PROPERTY

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        if (tag.name != "resultMap") return
        val project = tag.project
        if (project.isDefault || DumbService.isDumb(project)) return
        val typeAttribute = tag.getAttribute("type") ?: return
        val members = MapperSqlElementSupport.typeMemberNames(project, typeAttribute.valueElement) ?: return
        val typeName = (typeAttribute.value ?: "").substringAfterLast('.')
        for (child in tag.subTags) {
            if (child.name !in PROPERTY_HOLDERS) continue
            val propertyAttribute = child.getAttribute("property") ?: continue
            val property = propertyAttribute.value ?: continue
            val unknown = SqlInspectionText.unknownResultMapProperty(property, members) ?: continue
            val anchor = propertyAttribute.valueElement ?: propertyAttribute
            holder.registerProblem(
                anchor,
                messageOf(
                    SqlInspectionFinding(
                        rule = rule,
                        messageKey = "inspection.sqlv.resultMap.problem",
                        messageArgs = listOf(unknown, typeName),
                    ),
                ),
                com.intellij.codeInspection.ProblemHighlightType.WARNING,
            )
        }
    }

    private companion object {
        val PROPERTY_HOLDERS = setOf("id", "result", "arg", "association", "collection")
    }
}

/**
 * 规则 4：**方言不兼容的标识符引号**（NEW-26）。
 *
 * 判据在 [SqlInspectionText.dialectQuoteFindings]：反引号 / 方括号标识符不在当前方言
 * `Dialect.identifierQuotes` 里即报（双引号有字符串/标识符歧义，不判）。方言取
 * `DialectRegistry.effective(project, file)`（与预览/渲染同一事实源）。
 *
 * 方言推断不出（无项目 / 无数据源候选 / dumb）→ 回退 MySQL，而 MySQL 收反引号，故不会误报；
 * 但如果把方括号（SQL Server 风格）写进推断为 MySQL 的文件，会报——这正是本规则要抓的形态。
 */
class DialectQuoteInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.DIALECT_QUOTE

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        if (!MapperSqlElementSupport.isScannedTag(tag)) return
        val dialect = dialectOf(tag) ?: return
        runnableQuotes(dialect)?.let { quotes ->
            for (slice in MapperSqlElementSupport.textSlices(tag)) {
                SqlInspectionText.dialectQuoteFindings(slice.text, quotes, dialect.name).forEach { finding ->
                    reportInText(holder, slice, finding)
                }
            }
        }
    }

    override fun inspectAnnotation(annotation: PsiAnnotation, holder: ProblemsHolder) {
        val dialect = dialectOf(annotation) ?: return
        val quotes = runnableQuotes(dialect) ?: return
        val text = MapperSqlElementSupport.annotationSqlText(annotation, annotation.project) ?: return
        SqlInspectionText.dialectQuoteFindings(text, quotes, dialect.name).forEach { finding ->
            reportOnAnnotation(holder, annotation, finding)
        }
    }

    private fun dialectOf(element: com.intellij.psi.PsiElement): Dialect? {
        val project = element.project
        if (project.isDefault || DumbService.isDumb(project)) return null
        return runCatching {
            DialectRegistry.effective(project, element.containingFile?.virtualFile)
        }.getOrNull()
    }

    /** 方言接受的标识符引号；缺失（异常/未知方言）→ null = 不判。 */
    private fun runnableQuotes(dialect: Dialect): Set<Char>? =
        runCatching { dialect.identifierQuotes }.getOrNull()
}

/**
 * 规则 5：**OGNL `test` 表达式恒真 / 恒假**（NEW-26）。
 *
 * 判据在 [SqlInspectionText.constantTestTruth]：表达式不含根变量、不含静态字段引用时才求值——
 * `test="true"` / `test="1==1"`（恒真，条件永不生效）与 `test="1 != 1"`（恒假，分支永不展开）命中；
 * 任何引用参数/属性的表达式（`test="p.x != null"`）不判。求值走 [cn.song.mybatisplus.visualizer.xml.OgnlEvaluator]
 * （与预览渲染同一套），不另写解析。
 */
class OgnlTautologyInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.OGNL_TAUTOLOGY

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        if (!MapperSqlElementSupport.isScannedTag(tag)) return
        for (testTag in MapperSqlElementSupport.testTags(tag)) {
            val testAttribute = testTag.getAttribute("test") ?: continue
            val test = testAttribute.value ?: continue
            val truth = SqlInspectionText.constantTestTruth(test) ?: continue
            val anchor = testAttribute.valueElement ?: testAttribute
            holder.registerProblem(
                anchor,
                messageOf(
                    SqlInspectionFinding(
                        rule = rule,
                        // 恒真 = 条件永远成立（等于没写条件）；恒假 = 分支永远不展开
                        messageKey = if (truth) "inspection.sqlv.ognl.alwaysTrue" else "inspection.sqlv.ognl.alwaysFalse",
                        messageArgs = listOf(test),
                    ),
                ),
                com.intellij.codeInspection.ProblemHighlightType.WARNING,
            )
        }
    }
}