package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator
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
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.util.PsiTreeUtil
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * JPA @NamedQuery 实体级 gutter 预览图标（T-PreviewShortcut）。
 *
 * 在每个实体类的 `@NamedQuery(...)` 注解（含 `@NamedQueries({...})` 容器内的子注解）行显示预览图标，
 * 点击弹出该命名查询 JPQL 转换后的 SQL。
 *
 * **锚点策略**：与 [JpqlGutterLineMarkerProvider] 一致——leaf 锚点为注解的 `@` token（[JavaTokenType.AT]）。
 * 父链是 [PsiAnnotation] 且短名 = `NamedQuery`；`@NamedQueries` 容器注解的 `@` token 短名为
 * `NamedQueries` 被跳过（容器内每个 `@NamedQuery` 子注解自带的 `@` token 会各自锚定，逐个预览）。
 *
 * **去重保证**：命名查询名默认 `<实体简单名>.<方法名>`，与 Jpql/Hql/CustomDao 等方法名锚点天然区分，
 * 不与既有其它 provider 冲突。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class NamedQueryGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：注解的 `@` token。
        // T-PerfSweep34 券04：最便宜的 O(1) 类型早退提到**设置读取之前**——Java 文件里绝大多数
        // leaf 不是 `@` token，原顺序让它们每个都先付一次 `PluginSettings` 应用级服务查找 +
        // runCatching。组显隐门仍前置于重活（NamedQueryLocator.locate / annotation 解析）之前，
        // F36「组关时重活零触达」的语义不变。
        val token = element as? PsiJavaToken ?: return null
        if (token.tokenType != JavaTokenType.AT) return null
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        val annotation = PsiTreeUtil.getParentOfType(token, PsiAnnotation::class.java, false) ?: return null
        // 注解短名 = NamedQuery（@NamedQueries 容器本身跳过——其内部 @NamedQuery 子注解自带 @ token）
        val simpleName = annotation.qualifiedName?.substringAfterLast('.')
            ?: annotation.nameReferenceElement?.referenceName
        if (simpleName != "NamedQuery") return null
        // 校验确属实体类上的命名查询（NamedQueryLocator 能定位到非空）
        val cls = PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java, false) ?: return null
        if (NamedQueryLocator.locate(cls).none { it.annotation === annotation }) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            NAMED_QUERY_SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.namedQuery.tooltip") },
            NamedQueryGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.namedQuery.accessible.name") },
        )
    }

    private object NamedQueryGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val annotation = PsiTreeUtil.getParentOfType(elt, PsiAnnotation::class.java, false) ?: return
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewNamedQuery(annotation)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val NAMED_QUERY_SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", NamedQueryGutterLineMarkerProvider::class.java)
    }
}