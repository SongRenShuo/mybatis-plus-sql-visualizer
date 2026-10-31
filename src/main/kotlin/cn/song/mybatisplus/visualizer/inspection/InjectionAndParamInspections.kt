package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.xml.XmlTag

/**
 * 规则 1：**`${}` 直接替换注入风险**（NEW-26，本批第一条）。
 *
 * 判据在 [SqlInspectionText.dollarFindings]（0 误报口径：静态常量绑定 / MP Wrapper 片段 / 静态字段引用
 * 三类合法用法放行）。本类只做两件事：取文本 → 交给内核 → 上报。
 *
 * 快速修复「改为 `#{}`」：`${x}` → `#{x}`。这是能机械改的唯一形态；语义不等价（`${}` 用于结构拼接、
 * `#{}` 用于值绑定），故文案里写明「仅当此处是值而非 SQL 结构时使用」——只报不改的边界交给用户。
 */
class DollarInjectionInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.DOLLAR_INJECTION

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        if (!MapperSqlElementSupport.isScannedTag(tag)) return
        val staticBindNames = MapperSqlElementSupport.staticBindNames(tag)
        for (slice in MapperSqlElementSupport.textSlices(tag)) {
            SqlInspectionText.dollarFindings(slice.text, staticBindNames).forEach { finding ->
                reportInText(holder, slice, finding, fixesFor(finding.token))
            }
        }
    }

    override fun inspectAnnotation(annotation: PsiAnnotation, holder: ProblemsHolder) {
        val text = MapperSqlElementSupport.annotationSqlText(annotation, annotation.project) ?: return
        SqlInspectionText.dollarFindings(text).forEach { finding ->
            reportOnAnnotation(holder, annotation, finding, fixesFor(finding.token))
        }
    }

    private fun fixesFor(token: String): Array<LocalQuickFix> =
        if (token.startsWith("\${") && token.endsWith("}")) {
            arrayOf(ConvertDollarPlaceholderToHashFix())
        } else {
            LocalQuickFix.EMPTY_ARRAY
        }

    /** `${expr}` → `#{expr}`（只改这一个字符位；表达式内容原样保留）。 */
    class ConvertDollarPlaceholderToHashFix : LocalQuickFix {
        override fun getFamilyName(): String = MpSqlVisualizerBundle.message("inspection.sqlv.dollar.fix.family")

        override fun getName(): String = MpSqlVisualizerBundle.message("inspection.sqlv.dollar.fix.name")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val document = element.containingFile?.viewProvider?.document ?: return
            val range = descriptor.textRangeInElement ?: return
            val start = element.textRange.startOffset + range.startOffset
            if (start < 0 || start >= document.textLength) return
            if (document.immutableCharSequence[start] != '$') return
            // startInWriteAction() = true（LocalQuickFix 默认）→ applyFix 已在写命令内，直接改 document；
            // 再套一层 runWriteCommandAction 会在写锁内重入写命令（平台禁止）
            document.replaceString(start, start + 1, "#")
        }
    }
}

/**
 * 规则 2：**`#{}` 引用的参数不存在**（NEW-26）。
 *
 * 三个判定前提（任一不成立即沉默，这是 0 误报的边界）：
 *  1. 语句 `id` 能解析到 namespace 接口的方法（[MapperSqlElementSupport.parameterRoots]）；
 *  2. 方法参数表可判定——**单参且无 `@Param` 时不判**（MyBatis 允许 `#{任意实体属性}`）；
 *  3. 只判根名（`#{p.a}` 看 `p`），属性路径不判。
 *
 * `<bind name>` / `<foreach item|index>` 声明的名字算合法根名（不是方法参数，但 `#{}` 可以引用）。
 */
class MissingParameterInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.MISSING_PARAMETER

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        if (tag.name !in MapperSqlElementSupport.STATEMENT_TAGS) return
        // `<sql>` 片段没有自己的参数上下文（参数来自引用它的语句），不判
        val knownRoots = MapperSqlElementSupport.parameterRoots(tag) ?: return
        val declared = MapperSqlElementSupport.bindNames(tag) + MapperSqlElementSupport.foreachNames(tag)
        for (slice in MapperSqlElementSupport.textSlices(tag)) {
            SqlInspectionText.missingParameterFindings(slice.text, knownRoots, declared).forEach { finding ->
                reportInText(holder, slice, finding, NavigationFixes.forMissingParameter(tag, tag.getAttributeValue("id").orEmpty()))
            }
        }
    }

    /** 注解 SQL：参数来自所在方法签名。 */
    override fun inspectAnnotation(annotation: PsiAnnotation, holder: ProblemsHolder) {
        val method = MapperSqlElementSupport.enclosingMethod(annotation) ?: return
        val knownRoots = MapperSqlElementSupport.methodParamRoots(method) ?: return
        val text = MapperSqlElementSupport.annotationSqlText(annotation, annotation.project) ?: return
        SqlInspectionText.missingParameterFindings(text, knownRoots).forEach { finding ->
            reportOnAnnotation(holder, annotation, finding, NavigationFixes.forMethod(method))
        }
    }
}