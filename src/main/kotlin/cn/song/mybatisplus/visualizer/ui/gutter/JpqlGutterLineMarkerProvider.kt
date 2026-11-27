package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator
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
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * JPA JPQL/@Query 注解 gutter 预览图标（波次一）：Repository 接口中标注 `@Query` 的方法
 * 行显示预览图标，点击弹出 SQL 预览（原生 SQL 直接展示；JPQL 经 [cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter] 转换）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——leaf 锚点为注解的 `@` token（[JavaTokenType.AT]），
 * 父链是 PsiAnnotation 且 qualifiedName 末段 = `Query`，再校验所属方法含 @Query 注解；
 * 完整解析（JPQL 转换）仅在点击时走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class JpqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：注解的 `@` token。
        // T-PerfSweep34 券04：最便宜的 O(1) 类型早退提到**设置读取之前**——Java 文件里绝大多数
        // leaf 不是 `@` token，原顺序让它们每个都先付一次 `PluginSettings` 应用级服务查找 +
        // runCatching。组显隐门仍前置于重活（isDerivedQuery / hasQueryAnnotation）之前，
        // F36「组关时重活零触达」的语义不变。
        val token = element as? PsiJavaToken ?: return null
        if (token.tokenType != JavaTokenType.AT) return null
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        val annotation = PsiTreeUtil.getParentOfType(token, PsiAnnotation::class.java, false) ?: return null
        // 注解名末段 = Query（Spring Data @Query / 短名兜底）
        val annotationName = annotation.qualifiedName?.substringAfterLast('.')
            ?: annotation.nameReferenceElement?.referenceName
        if (annotationName != "Query") return null
        // 校验所属方法含 @Query 注解（排除普通 import 的 Query 类型引用等误判）
        val method = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java, false) ?: return null
        // 【去重关键】派生查询（findByXxx）已由 JpaGutterLineMarkerProvider 负责图标；
        // 本 provider 仅处理非派生查询的 @Query 方法，避免同行双图标。
        // 注意：isDerivedQuery 必须在 hasQueryAnnotation 之前检查——某些方法可能同时满足两者。
        if (DerivedMethodLocator.isDerivedQuery(method)) return null
        if (!JpqlQueryLocator.hasQueryAnnotation(method)) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            JPQL_SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.jpql.tooltip") },
            JpqlGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.jpql.accessible.name") },
        )
    }

    private object JpqlGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val method = PsiTreeUtil.getParentOfType(elt, PsiMethod::class.java, false) ?: return
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewJpql(method)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val JPQL_SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", JpqlGutterLineMarkerProvider::class.java)
    }
}
