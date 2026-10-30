package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethodCallExpression

/**
 * 禁用 API 检查（FR-0045 第 2 项 T-BannedApiCheck S3）——**本插件首个 LocalInspectionTool**。
 *
 * 作用面：Java 方法调用表达式（`PsiMethodCallExpression`）。判据 = 调用点 `resolve()` 到的
 * [com.intellij.psi.PsiMethod] 的**声明类全限定名 + 方法名**，交给纯逻辑
 * [BannedApiMatcher]（与全项目存量扫描共用同一份规则表与语义）。
 *
 * 设计要点：
 *  - **薄壳**：本类只做 PSI 取数（resolve / containingClass）与 report，匹配规则全在
 *    [CompiledRules]——纯 JUnit 可测，不在编辑器里依赖平台才能验证；
 *  - **零规则零行为**：规则表为空时 `buildVisitor` 直接返回 `PsiElementVisitor.EMPTY_VISITOR`
 *    （daemon 连走访都不进入，不打扰日常编码）；
 *  - **先廉价短路再 resolve**：方法名文本可能命中时才 `resolveMethod()`——全项目 Java 调用的
 *    resolve 都不便宜，方法名正则已能筛掉绝大多数调用点；
 *  - **级别逐规则**：`registerProblem` 用 `ProblemHighlightType.ERROR` / `WARNING` 表达规则级别
 *    （不靠 inspection 的全局 level；EP 的 `level="WARNING"` 只是默认档，用户仍可在平台的检查
 *    设置里整体关掉本检查）；
 *  - **提示文案**取规则说明（团队规范里「为什么禁」比「你用了禁用 API」有用得多）。
 *
 * 注册见 `src/main/resources/META-INF/plugin.xml` 的 `<localInspection>`（groupKey/groupBundle
 * 指向本插件在检查设置树里的分组）。
 */
class BannedApiInspection : LocalInspectionTool() {

    /**
     * 测试缝：替换规则来源（headless 测试不必注册 Application 级服务）。
     * 生产路径恒为 null，走 [BannedApiRuleManager] 单例。
     */
    internal var rulesProviderForTest: (() -> CompiledRules)? = null

    /**
     * 检查短名（与 plugin.xml `shortName="BannedApi"` 一致）：平台的 suppression id（
     * `@SuppressWarnings("BannedApi")`）与设置持久化键都用它，两处必须一致。
     */
    override fun getShortName(): String = SHORT_NAME

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val rules = rulesProviderForTest?.invoke() ?: BannedApiRuleManager.getInstance().compiledRules
        if (rules.isEmpty) return PsiElementVisitor.EMPTY_VISITOR
        return object : JavaElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                checkCall(expression, rules, holder)
            }
        }
    }

    private fun checkCall(
        expression: PsiMethodCallExpression,
        rules: CompiledRules,
        holder: ProblemsHolder,
    ) {
        // 廉价短路：方法名文本不可能是任何规则的目标就不再 resolve
        val methodName = expression.methodExpression.referenceName ?: return
        if (!rules.mayMatchMethodName(methodName)) return

        val method = expression.resolveMethod() ?: return
        val classFqn = method.containingClass?.qualifiedName ?: return
        val rule = rules.match(classFqn, method.name) ?: return

        // 显式声明为 PsiElement：methodExpression 同时实现 PsiReference，registerProblem 有
        // PsiElement/PsiReference 两个重载，直接传会因重载歧义编译不过
        val anchor: PsiElement = expression.methodExpression
        holder.registerProblem(
            anchor,
            MpSqlVisualizerBundle.message(
                "inspection.bannedApi.problem",
                classFqn,
                method.name,
                rule.description,
            ),
            if (rule.isError()) ProblemHighlightType.ERROR else ProblemHighlightType.WARNING,
        )
    }

    private companion object {
        /** 检查短名（plugin.xml `shortName` 同值；`@SuppressWarnings` 走它）。 */
        const val SHORT_NAME = "BannedApi"
    }
}
