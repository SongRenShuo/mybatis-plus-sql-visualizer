package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.psi.PsiSnapshot
import cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlFile
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * T-MapperPreviewOrder（2026-11-26 用户报障「预览图标首次在右、过一会到左」）：
 * Mapper 接口方法行的 **SQL 预览**图标单独成 provider，与跳转箭头分开。
 *
 * ## 为什么拆
 *
 * 平台慢趟按 **provider 顺序**收集（`LineMarkersPass.queryProviders` 外层遍历 providers、
 * 每个 provider 的 `collectSlowLineMarkers` 结果按 add 顺序消费），同一行两枚图标的左右序
 * 因此由「谁先被收集」决定。此前预览与跳转都在 [MapperJavaToXmlLineMarkerProvider] 一个
 * provider 里，首次 daemon pass 与后续重算的收集/合并差异会让顺序翻转（用户实测右→左）。
 * 本类在 `plugin.xml` 里注册在跳转 provider **之前**，顺序恒为「预览在左、跳转在右」，
 * 与 XML 语句行（`XmlSqlGutterLineMarkerProvider` 在前、`MapperXmlToJavaLineMarkerProvider`
 * 在后）完全同构。
 *
 * ## 判定
 *
 * 用 [MapperXmlLocator.existsOrUnknown]（索引候选 + PSI 子树匹配，**不建 document**）——
 * 与跳转 provider 的 `locateOrUnknown`（要行号、要 document）各付一次，但本类不碰 document，
 * 成本低于直接复用。三态语义与跳转同口径：dumb 乐观产出、索引临界命中共享正缓存仍产出、
 * 确定没有收回缓存并摘图标（不出点了必空的死图标）。
 *
 * ## 让位
 *
 * 预览是我方独有能力（三家竞品 Java 侧 provider 均为跳转箭头），**不参与**
 * `MyBatisCompetitorGate` 让位；让位开着时该行仍保留这一枚。
 */
class MapperMethodPreviewLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return
        elements.mapNotNullTo(result, ::createPreviewMarker)
    }

    private fun createPreviewMarker(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        if (method.nameIdentifier !== element) return null
        val owner = method.containingClass ?: return null
        if (!owner.isInterface) return null
        val namespace = owner.qualifiedName ?: return null
        val project = element.project
        val key = MapperXmlToJavaLineMarkerProvider.cacheKey(project, namespace, method.name)
        // 五段式（与跳转 provider 同构）：dumb 乐观 → Found 入正缓存 → Unknown 命中缓存仍出 →
        // Absent 收回缓存并摘图标 → 其余不出。注意不 catch ProcessCanceledException（平台契约禁吞 PCE）。
        if (MapperXmlToJavaLineMarkerProvider.isDumbProvider(project)) return buildPreviewMarker(element)
        return when (MapperXmlLocator.existsOrUnknown(project, "$namespace.${method.name}")) {
            is MapperXmlLocator.Lookup.Found -> {
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key)
                buildPreviewMarker(element)
            }
            MapperXmlLocator.Lookup.Unknown ->
                if (MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)) buildPreviewMarker(element) else null
            MapperXmlLocator.Lookup.Absent -> {
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.remove(key)
                null
            }
        }
    }

    private fun buildPreviewMarker(element: PsiElement): LineMarkerInfo<*> = LineMarkerInfo(
        element, element.textRange, SQL_PREVIEW_ICON,
        { MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip") },
        MapperMethodPreviewNavigationHandler,
        com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.CENTER,
        { MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.accessible.name") },
    )

    /**
     * 点击（T-MapperMethodSqlPreview）：方法名 → 同名 XML 语句 → 复用
     * [XmlSqlGutterLineMarkerProvider.previewStatementTag] 渲染，与 XML 语句行点击逐字节一致。
     *
     * EDT 上只做锚点快照与来源行号；方法 → 语句标签的 PSI 解析放进后台 ReadAction，
     * 读取前用 [PsiSnapshot] 校验（T-PCP-02 跨线程 PSI 纪律）。索引未就绪时延后到 smart
     * 再执行一次——`MapperNavigationTargets.statementTagsFor` 在 dumb 期返回空，直接点会误报「未找到」。
     */
    private object MapperMethodPreviewNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val method = elt.parent as? PsiMethod ?: return
            val project = elt.project
            if (runCatching { DumbService.isDumb(project) }.getOrDefault(false)) {
                DumbService.getInstance(project).runWhenSmart { navigate(e, elt) }
                return
            }
            val snapshot = ReadAction.compute<PsiSnapshot?, RuntimeException> { PsiSnapshot.capture(method) }
            val sourceLocation = runCatching {
                val document = method.containingFile.viewProvider.document
                val line = document?.getLineNumber(method.textRange.startOffset)?.plus(1)
                if (line != null) "${method.containingFile.name}:$line" else null
            }.getOrNull()
            XmlSqlGutterLineMarkerProvider.previewStatementTag(project, sourceLocation) {
                if (snapshot?.validate() is PsiSnapshot.Validity.Invalid) return@previewStatementTag null
                MapperNavigationTargets.statementTagsFor(method).firstOrNull()?.let { tag ->
                    (tag.containingFile as? XmlFile)?.let { file -> file to tag }
                }
            }
        }
    }

    private companion object {
        val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", MapperMethodPreviewLineMarkerProvider::class.java)
    }
}