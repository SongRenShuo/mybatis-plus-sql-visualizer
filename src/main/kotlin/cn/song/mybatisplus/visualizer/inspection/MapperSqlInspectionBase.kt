package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * NEW-26 编辑器内 SQL 检查的**薄壳基类**：一条规则一个 inspection 子类。
 *
 * 分层与 [BannedApiInspection] 完全同构：
 *  - 本类只做 PSI 走查 + `registerProblem`；判定全在 [SqlInspectionText]（纯 JUnit 可测）；
 *  - **零命中零行为**：文件既不是 mapper XML 也不是 Java 时 `buildVisitor` 返回
 *    `EMPTY_VISITOR`，daemon 连走访都不进；
 *  - 级别统一 `WARNING`（`GENERIC_ERROR_OR_WARNING` 会被 profile 覆盖，故显式声明）。
 *
 * 覆盖面（立项册 §二 经核实后的范围）：
 *  - mapper XML 的 `<select|insert|update|delete>` 与 `<sql>` 片段；
 *  - Java 注解 SQL（`@Select`/`@Insert`/`@Update`/`@Delete`）。
 *  **Kotlin 注解 SQL 不在覆盖面内**：K2 下注解节点是 `KtAnnotationEntry`，要读它就得引 Kotlin 插件
 *  类型，而 Kotlin 在本插件是 optional 依赖——可空依赖下的强引用会在未装 Kotlin 的环境里炸类加载。
 *  Kotlin mapper 的注解 SQL 继续由预览面板覆盖（本检查判不了就沉默，不发虚假告警）。
 *
 * 线程纪律（P-3）：visitor 在 daemon 线程、已持读锁下运行；本类不做任何跨线程/写操作，
 * 需要索引的取数（`findClass`）一律先过 `DumbService.isDumb`，取不到就沉默——不发虚假告警。
 */
abstract class MapperSqlInspectionBase : LocalInspectionTool() {

    /** 本 inspection 实现的规则（子类声明，用于取 shortName）。 */
    protected abstract val rule: SqlInspectionRule

    /** 检查短名（plugin.xml `shortName` 同值；`@SuppressWarnings` / XML suppress id 都用它）。 */
    override fun getShortName(): String = rule.shortName

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        val isXmlMapper = file is XmlFile && file.rootTag?.name == "mapper"
        // 注解 SQL 只覆盖 Java 文件：Kotlin PSI 的注解节点不实现 PsiAnnotation（K2 下是 KtAnnotationEntry），
        // 要用它就得引 Kotlin 插件类型——而 Kotlin 依赖在本插件是 optional 的。故 Kotlin mapper 的注解 SQL
        // 不在覆盖面内（判不了就沉默，不发虚假告警；见类头「覆盖面」）。
        val isJava = file is PsiJavaFile
        if (!isXmlMapper && !isJava) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                when (element) {
                    // mapper 文件里的每个标签都交给子类；子类按自己的作用标签（语句/sql 片段/resultMap）过滤。
                    // 文件级守卫（非 mapper 直接 EMPTY_VISITOR）已挡掉绝大多数文件，标签级过滤成本可忽略。
                    is XmlTag -> if (isXmlMapper) runCatching { inspectMapperTag(element, holder) }
                    is PsiAnnotation -> if (isJava && element.qualifiedName?.substringAfterLast('.') in SQL_ANNOTATIONS) {
                        runCatching { inspectAnnotation(element, holder) }
                    }
                }
            }
        }
    }

    /** 子类实现：单个 mapper 标签的结论（默认不判——只作用注解的规则可以留空）。 */
    protected open fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) = Unit

    /** 子类实现：单个注解 SQL 的结论（默认不判）。 */
    protected open fun inspectAnnotation(annotation: PsiAnnotation, holder: ProblemsHolder) = Unit

    // ------------------------------------------------------------------
    // 公共上报工具
    // ------------------------------------------------------------------

    /** XML 文本节点 + 相对偏移 → problem（偏移有效时精确锚定 token，否则锚整个文本节点）。 */
    protected fun reportInText(
        holder: ProblemsHolder,
        slice: MapperSqlElementSupport.SqlTextSlice,
        finding: SqlInspectionFinding,
        fixes: Array<LocalQuickFix> = LocalQuickFix.EMPTY_ARRAY,
    ) {
        val text = messageOf(finding)
        val range = finding.toRangeIn(slice.text)
        if (range != null) {
            holder.registerProblem(slice.node, text, ProblemHighlightType.WARNING, range, *fixes)
        } else {
            holder.registerProblem(slice.node, text, ProblemHighlightType.WARNING, *fixes)
        }
    }

    /**
     * 注解上的 problem。**不做子范围锚定**：`annotationSqlText` 取的是 `String.join(" ", …)` 归一化后的
     * 文本，其偏移与注解属性原文（含引号、`+` 拼接、数组元素）对不上——强行套偏移会标错位置。
     * 故整体锚在注解的 value 元素上。
     */
    protected fun reportOnAnnotation(
        holder: ProblemsHolder,
        annotation: PsiAnnotation,
        finding: SqlInspectionFinding,
        fixes: Array<LocalQuickFix> = LocalQuickFix.EMPTY_ARRAY,
    ) {
        val anchor = MapperSqlElementSupport.annotationValueElement(annotation)
        holder.registerProblem(anchor, messageOf(finding), ProblemHighlightType.WARNING, *fixes)
    }

    protected fun messageOf(finding: SqlInspectionFinding): String =
        MpSqlVisualizerBundle.message(finding.messageKey, *finding.messageArgs.toTypedArray())

    private fun SqlInspectionFinding.toRangeIn(hostText: String): TextRange? =
        if (start >= 0 && length > 0 && start + length <= hostText.length) {
            TextRange(start, start + length)
        } else {
            null
        }

    companion object {
        /** 字面量 SQL 注解（Provider 类注解的 SQL 由 Java 生成，静态不可判定——不检查）。 */
        val SQL_ANNOTATIONS: Set<String> = setOf("Select", "Insert", "Update", "Delete")
    }
}