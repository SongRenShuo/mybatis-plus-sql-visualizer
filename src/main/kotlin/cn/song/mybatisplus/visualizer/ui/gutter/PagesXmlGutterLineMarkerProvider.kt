package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.PagesXmlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * maintainPage 预览图标（F-45）：`<maintainPage>` 行显示预览图标，
 * 点击弹出 SQL 预览（复用 [SqlPreviewService.showResult] 链路）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定（tag 名 + 父标签名），零 resolve、零遍历、
 * 零转换；tooltip 静态文案；点击转换走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="XML" .../>`。
 */
class PagesXmlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：只接受 maintainPage 标签的 name token（O(1) 判定，零 resolve）
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        // maintainPage 标签 + 直接父级是 <pages>（避免非 pages XML 误显）
        if (tag.name != "maintainPage") return null
        if (tag.parentTag?.name != "pages") return null
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（原 gutterEnabled + enabledFor
        // 双路径每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.pages.tooltip") },
            PagesGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.pages.accessible.name") },
        )
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private object PagesGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val tag = elt.parent as? XmlTag ?: return
            val file = tag.containingFile as? XmlFile ?: return
            SqlPreviewService.previewAsync(project, {
                // T-PagesHql：传 PSI 宿主锚点，HQL 转换按该 XML 所属模块收窄实体检索范围
                PagesXmlPreviewService.preview(file.text, tag.getAttributeValue("id"), project, tag)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", PagesXmlGutterLineMarkerProvider::class.java)

        /**
         * 选择区内 maintainPage 候选（tag 起始 offset 在选区范围内，按 offset 排序）——
         * ConvertToSqlAction 选择区候选交互复用（与 Java/mapper 选择区候选语义一致）；
         * 纯 PSI 遍历零 resolve，internal 供测试。
         */
        internal fun statementsInRange(file: XmlFile, start: Int, end: Int): List<XmlTag> =
            PsiTreeUtil.collectElementsOfType(file, XmlTag::class.java)
                .filter { it.name == "maintainPage" && it.textRange.startOffset in start until end }
                .sortedBy { it.textRange.startOffset }
    }
}
