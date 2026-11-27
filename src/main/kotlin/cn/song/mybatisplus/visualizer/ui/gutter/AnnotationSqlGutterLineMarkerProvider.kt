package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.util.PsiTreeUtil
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * MyBatis SQL 注解预览图标（T-M25-03）：`@Select/@Insert/@Update/@Delete` 及 Provider 族注解
 * 所在行显示预览图标，点击弹出动态 SQL 预览（复用 [SqlPreviewService.showResult] 链路）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——leaf 锚点为注解的 `@` token（[JavaTokenType.AT]），
 * 父链是 PsiAnnotation 且 qualifiedName 末段 ∈ SQL 注解集合（纯文本匹配，零 resolve）；
 * tooltip 静态文案；点击转换走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`（与 Wrapper gutter 共存）。
 */
class AnnotationSqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：注解的 `@` token。
        // T-PerfSweep34 券04：最便宜的 O(1) 类型早退提到**设置读取之前**——Java 文件里绝大多数
        // leaf 不是 `@` token，原顺序让它们每个都先付一次 `PluginSettings` 应用级服务查找 +
        // runCatching。组显隐门仍前置于重活（isSqlAnnotation）之前，F36 语义不变。
        val token = element as? PsiJavaToken ?: return null
        if (token.tokenType != JavaTokenType.AT) return null
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        val annotation = PsiTreeUtil.getParentOfType(token, PsiAnnotation::class.java, false) ?: return null
        if (!AnnotationSqlPreviewService.isSqlAnnotation(annotation)) return null
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.annotation.tooltip") },
            AnnotationGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.annotation.accessible.name") },
        )
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private object AnnotationGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val annotation = PsiTreeUtil.getParentOfType(elt, PsiAnnotation::class.java, false) ?: return
            val contextRef = java.util.concurrent.atomic.AtomicReference<cn.song.mybatisplus.visualizer.context.PreviewSourceContext?>()
            SqlPreviewService.previewAsync(project, {
                val context = AnnotationSqlPreviewService.resolveSourceContext(annotation, project)
                contextRef.set(context)
                AnnotationSqlPreviewService.preview(annotation, project, context)
            }) { result ->
                SqlPreviewService.showResult(project, result, sourceContext = contextRef.get())
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", AnnotationSqlGutterLineMarkerProvider::class.java)
    }
}
