package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Hibernate HQL gutter 预览图标：在每个 `createQuery(...)` / `createNamedQuery(...)`
 * 调用的方法名行显示预览图标，点击弹出 SQL 预览。
 *
 * **锚点策略**：与 [CustomDaoHqlGutterLineMarkerProvider] 一致——锚定在方法调用的方法名 PsiIdentifier 上。
 * 一个方法体内可能有多个 createQuery 调用，每个调用各有独立图标，点击展示对应 SQL。
 *
 * **去重保证**：同一个 PsiMethodCallExpression 有且仅有一个方法名 identifier。
 * 其他 provider（CustomDao / Jpql / Jpa / CriteriaQuery）的锚点策略与此不冲突。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class HqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier。
        if (element !is PsiIdentifier) return null
        // 锚定在方法调用的方法名 PsiIdentifier 上：
        // element = "createQuery" 等方法名 identifier
        // element.parent = PsiReferenceExpression（方法调用的方法名引用）
        // element.parent.parent = PsiMethodCallExpression
        val parent = element.parent as? PsiReferenceExpression ?: return null
        val grandParent = parent.parent as? PsiMethodCallExpression ?: return null
        // 确认 element 是方法名而非 qualifier
        if (grandParent.methodExpression.referenceName != element.text) return null
        // 仅处理 createQuery / createNamedQuery / createNativeQuery 调用
        if (element.text !in STANDARD_JPA_QUERY_METHODS) return null // T-Perf-23：Set 提常量
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（原 gutterEnabled + enabledFor
        // 双路径每到达元素 3 次 app 级查找收敛为 1 次）
        // 券03（T-PerfSweep27-03）：开关判定前移至 hasStringFirstArgument 之前——其变量引用
        // 分支含 resolve()，总闸关闭时用户不该为永不显示的图标付 resolve。
        // 两判定纯只读相互独立，次序交换不改变开闸用户结果（P-3 已证）。
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        // 【关键过滤】确保第一个参数是字符串字面量（HQL/JPQL/SQL），排除 cb.createQuery(Class)
        if (!hasStringFirstArgument(grandParent)) return null
        // 复用 HqlQueryLocator.locate 非 null 判定（精确匹配该调用）
        if (HqlQueryLocator.locate(grandParent) == null) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            HQL_SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.hql.tooltip") },
            HqlGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.hql.accessible.name") },
        )
    }

    /**
     * 检查方法调用的第一个参数是否为字符串（HQL/JPQL）。
     * 排除 cb.createQuery(Class) 等 CriteriaBuilder 调用。
     *
     * M36.5.79：Java 链式 `+`（"a" + "b" + "c"）在 PSI 中为 PsiPolyadicExpression，
     * 与 PsiBinaryExpression 互不继承——漏判会导致多行拼接 HQL 无预览图标（用户实测）。
     */
    private fun hasStringFirstArgument(call: PsiMethodCallExpression): Boolean {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return false
        val firstArg = args[0]
        if (firstArg is com.intellij.psi.PsiLiteralExpression) {
            return firstArg.value is String
        }
        if (firstArg is com.intellij.psi.PsiBinaryExpression || firstArg is com.intellij.psi.PsiPolyadicExpression) {
            return true // 字符串拼接（含链式 `+`）
        }
        if (firstArg is PsiReferenceExpression) {
            // 变量引用：仅 String 类型视为有效 HQL 参数（排除 cq 等 CriteriaQuery 变量）
            val resolved = firstArg.resolve()
            if (resolved is com.intellij.psi.PsiVariable) {
                val typeName = resolved.type.canonicalText
                return typeName == "java.lang.String" || typeName == "String"
            }
            return false
        }
        return false // Class 字面量等
    }

    private object HqlGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            // 直接从锚点向上查找 PsiMethodCallExpression（不依赖 locate() 反查）
            val parent = elt.parent as? PsiReferenceExpression ?: return
            val call = parent.parent as? PsiMethodCallExpression ?: return
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewHql(call)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val STANDARD_JPA_QUERY_METHODS = setOf("createQuery", "createNamedQuery", "createNativeQuery")
        private val HQL_SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", HqlGutterLineMarkerProvider::class.java)
    }
}
