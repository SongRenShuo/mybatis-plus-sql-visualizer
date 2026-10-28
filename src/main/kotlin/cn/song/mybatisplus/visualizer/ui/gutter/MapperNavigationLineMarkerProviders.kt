package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.xml.util.XmlTagUtil

/** Mapper XML statement 与 Mapper 接口方法之间的双向 gutter 导航。 */
class MapperXmlToJavaLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        elements.mapNotNullTo(result, ::createMarker)
    }

    private fun createMarker(element: PsiElement): LineMarkerInfo<*>? {
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        if (tag.name !in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) return null
        if (XmlTagUtil.getStartTagNameElement(tag) !== token) return null
        if (!MybatisXmlTargets.isMapperFile(tag.containingFile) || tag.parentTag?.parentTag != null) return null
        if (tag.parentTag?.name != "mapper") return null
        val namespace = MybatisXmlTargets.rootNamespace(tag.containingFile) ?: return null
        val id = tag.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: return null
        if (MybatisXmlTargets.resolveInterfaceMethod(element.project, namespace, id) == null) return null
        return LineMarkerInfo(
            element, element.textRange, MpvsIcons.MapperStatement,
            { MpSqlVisualizerBundle.message("gutter.mapperStatement.tooltip") },
            { _, elt ->
                val currentTag = elt.parent as? XmlTag ?: return@LineMarkerInfo
                val currentNamespace = MybatisXmlTargets.rootNamespace(currentTag.containingFile) ?: return@LineMarkerInfo
                val currentId = currentTag.getAttributeValue("id") ?: return@LineMarkerInfo
                MybatisXmlTargets.resolveInterfaceMethod(elt.project, currentNamespace, currentId)?.navigate(true)
            },
            com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.mapperStatement.accessible.name") },
        )
    }
}

/** Mapper 接口方法跳转到 namespace 对应 XML 中的同名 statement。 */
class MapperJavaToXmlLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        elements.mapNotNullTo(result, ::createMarker)
    }

    private fun createMarker(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is com.intellij.psi.PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        if (method.nameIdentifier !== element) return null
        val namespace = method.containingClass?.qualifiedName ?: return null
        if (MapperXmlLocator.locate(element.project, "$namespace.${method.name}") == null) return null
        return LineMarkerInfo(
            element, element.textRange, MpvsIcons.MapperMethod,
            { MpSqlVisualizerBundle.message("gutter.mapperMethod.tooltip") },
            { _, elt ->
                val currentMethod = elt.parent as? PsiMethod ?: return@LineMarkerInfo
                val currentNamespace = currentMethod.containingClass?.qualifiedName ?: return@LineMarkerInfo
                val target = MapperXmlLocator.locate(elt.project, "$currentNamespace.${currentMethod.name}") ?: return@LineMarkerInfo
                val file = target.virtualFile.takeIf { it.isValid } ?: return@LineMarkerInfo
                OpenFileDescriptor(elt.project, file, target.line, 0).navigate(true)
            },
            com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.mapperMethod.accessible.name") },
        )
    }
}
