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
 * ## 左右序（T-MapperPreviewOrder）
 *
 * 平台 `EditorGutterComponentImpl.processIconsRowForY` 按 `Alignment` **分列**：先从左依次排
 * 所有 `LEFT` 图标，再把 `CENTER` 图标整体排在 `LEFT` 右侧（居中），`RIGHT` 从右侧倒排。
 * 因此预览固定用 `Alignment.LEFT`、跳转箭头保持 `CENTER` ⇒ 预览在几何上恒在跳转左侧，
 * 与 marker 收集顺序、daemon 增量复用、provider 注册顺序**全部无关**。
 * （历史两版修法——同 provider 双 marker、锚点 offset 小一档——都只是让收集顺序偏向自己，
 * 真机多轮 daemon 复用下仍会翻；用户三次报障后改为 alignment 分列。）
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
        // T-MapperPreviewOrder：判定用方法名 leaf，**图标锚点取同行前一叶**——gutter 缓存按
        // highlighter 的 startOffset 归行并排序（EditorGutterComponentImpl.buildGutterRenderersCache
        // 走 processRangeHighlighters 顺序），同 offset 的两枚顺序受 daemon 增量复用影响会翻转
        // （用户实测「首次在右、过一会到左」）。offset 小一档即恒排跳转箭头左侧。
        val anchor = previewAnchor(method, element)
        // 五段式（与跳转 provider 同构）：dumb 乐观 → Found 入正缓存 → Unknown 命中缓存仍出 →
        // Absent 收回缓存并摘图标 → 其余不出。注意不 catch ProcessCanceledException（平台契约禁吞 PCE）。
        if (MapperXmlToJavaLineMarkerProvider.isDumbProvider(project)) return buildPreviewMarker(anchor)
        return when (MapperXmlLocator.existsOrUnknown(project, "$namespace.${method.name}")) {
            is MapperXmlLocator.Lookup.Found -> {
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key)
                buildPreviewMarker(anchor)
            }
            MapperXmlLocator.Lookup.Unknown ->
                if (MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)) buildPreviewMarker(anchor) else null
            MapperXmlLocator.Lookup.Absent -> {
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.remove(key)
                null
            }
        }
    }

    /**
     * 预览图标的锚点：方法名**同行的前一个非空白 leaf**（startOffset 更小 → gutter 排序恒在左）。
     * 取不到（方法名是该行首叶、或前叶在上一行）时退回方法名本身。
     */
    private fun previewAnchor(method: PsiMethod, nameLeaf: PsiElement): PsiElement {
        val document = method.containingFile.viewProvider.document ?: return nameLeaf
        val nameLine = document.getLineNumber(nameLeaf.textRange.startOffset)
        var prev: PsiElement? = com.intellij.psi.util.PsiTreeUtil.prevLeaf(nameLeaf)
        while (prev != null && (prev.textLength == 0 || prev is com.intellij.psi.PsiWhiteSpace)) {
            prev = com.intellij.psi.util.PsiTreeUtil.prevLeaf(prev)
        }
        if (prev == null) return nameLeaf
        if (document.getLineNumber(prev.textRange.startOffset) != nameLine) return nameLeaf
        return prev
    }

    private fun buildPreviewMarker(element: PsiElement): LineMarkerInfo<*> = LineMarkerInfo(
        element, element.textRange, SQL_PREVIEW_ICON,
        { MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip") },
        MapperMethodPreviewNavigationHandler,
        // T-MapperPreviewOrder（2026-11-26 用户第三次报障）：用 **LEFT** 而不是 CENTER。
        // 平台 `EditorGutterComponentImpl.processIconsRowForY` 按 Alignment 分列：先从左依次排
        // 所有 LEFT 图标，再把 CENTER 图标整体排在 LEFT 之后（居中），RIGHT 从右侧倒排。
        // 跳转箭头保持 CENTER ⇒ 预览（LEFT）在几何上恒在跳转左侧，与 marker 收集顺序、
        // daemon 增量复用、provider 注册顺序全部无关。此前的「锚点 offset 小一档」只是让排序
        // 恰好偏向自己，在真机多轮 daemon 复用下仍会翻。
        com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LEFT,
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
            // 锚点是方法名同行前一叶（返回类型/修饰符），不能再用 elt.parent 直取 PsiMethod。
            val method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(elt, PsiMethod::class.java, false) ?: return
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