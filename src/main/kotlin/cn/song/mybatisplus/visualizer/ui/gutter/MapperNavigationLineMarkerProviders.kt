package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
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
        // 券01（T-PerfSweep28-01，R28-2）：组显隐门前置——本 Provider 此前未接任何组闸，组关时
        // 语句标签的接口解析重活照付。批级单次取 state，总闸与组显隐共用（CriteriaQueryGutter 门形）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return
        // 第三道门（T-MapperNavCoexistYield）：gutter 面的共存让位——MyBatisCodeHelperPro 启用时
        // 它自己也会给同一行出跳转箭头，本插件让位。与 XmlSynHiGate 的注入区间互补是两件事：
        // 那边判「对方是否真给这段文本上色」，这边判「对方是否在位」（同行两个图标都合规，故让位）。
        // 探测结论已缓存，此处为 O(1) 布尔，不引入按元素开销。
        if (gutterState != null &&
            MyBatisCompetitorGate.shouldYield(
                gutterState.mapperNavYieldToCodeHelper,
                MyBatisCompetitorGate.isCodeHelperProActive(),
            )
        ) {
            return
        }
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
        // 券01（T-PerfSweep28-01，R28-2）：组显隐门前置（与 XML 侧同门形）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return
        // 第三道门（T-MapperNavCoexistYield）：与 XML 侧同门形——竞品在位且用户未关闭让位即不产出图标
        if (gutterState != null &&
            MyBatisCompetitorGate.shouldYield(
                gutterState.mapperNavYieldToCodeHelper,
                MyBatisCompetitorGate.isCodeHelperProActive(),
            )
        ) {
            return
        }
        elements.mapNotNullTo(result, ::createMarker)
    }

    private fun createMarker(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is com.intellij.psi.PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        if (method.nameIdentifier !== element) return null
        // 券01（T-PerfSweep28-01，R28-1/P-1）：接口前置——mapper namespace 语义必为接口
        // （方法绑定经 JDK 动态代理），此前 service/util 等普通类的每个方法声明都在慢趟付一次
        // 全 mapper 文件定位。isInterface 为 O(1) 属性读，开闸用户可见行为不变。
        val containingClass = method.containingClass ?: return null
        if (!containingClass.isInterface) return null
        val namespace = containingClass.qualifiedName ?: return null
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
