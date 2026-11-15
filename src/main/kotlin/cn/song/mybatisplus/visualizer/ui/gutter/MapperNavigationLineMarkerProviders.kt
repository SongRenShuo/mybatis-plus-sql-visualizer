package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.xml.util.XmlTagUtil

/** Mapper XML statement 与 Mapper 接口方法之间的双向 gutter 导航。 */
class MapperXmlToJavaLineMarkerProvider : LineMarkerProvider {
    // F1（T-GutterFirstOpenDelay，治 S2「MP 图标消失 3s 重现」）：
    // createMarker 原本把 resolveInterfaceMethod 是否解析到当图标唯一开关——冷打开窗口 Java
    // 索引未就绪/重建中时 findClass(allScope) 瞬时返回 null，该趟慢 pass 整趟换装把先前已
    // 渲染的图标摘掉，索引就绪后下一趟才恢复（用户实测「消失 3s 重现」）。判定顺序改为：
    //  ① 解析成功 → 产出并记入会话级正缓存 [resolvedStatementKeys]；
    //  ② 解析失败 + 正缓存命中 → 照常产出（图标不摘；点击导航 handler 本就再解析一次且
    //     null-safe，resolve 语义与旧行为一致）；
    //  ③ 解析失败 + dumb 窗（F1-b）→ 乐观产出且不入缓存：dumb 期失败只是「还没查到」，
    //     dumb 结束后下一趟重新判定，可自愈；成功路径照常入缓存（findClass 命中即真实存在）。
    //  ④ 解析失败 + 其余 → 不产出（「真没有」语义与旧行为一致，负例不回归）。
    //  不变式＝图标只在「确认真没有」时缺席，不在「还没查到」时缺席。
    //  正缓存取舍（无失效）：会话级正缓存不设任何失效——接口方法删除等真变更依赖「文件改动
    //  → daemon 重跑 → 解析成功路径刷新」覆盖不了的场景极少，且 XML 侧误留图标的代价
    //  （点击后静默无跳转，handler null-safe）远小于冷窗误摘整批图标的代价，不对称偏向
    //  「宁多不缺」。
    companion object {
        /** F1-a 会话级正缓存：本会话解析成功过的 `namespace#id`。无失效，取舍见类头注释。 */
        internal val resolvedStatementKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** 测试缝：dumb 判定（生产＝`DumbService.isDumb`，F1-b 乐观产出用），形态承 MbgExampleCallResolver。
         *  F1' 起双侧共用（Java 侧 `MapperJavaToXmlLineMarkerProvider` 的失败分支同读此缝）。 */
        internal var isDumbProvider: (Project) -> Boolean = { project ->
            runCatching { DumbService.isDumb(project) }.getOrDefault(false)
        }

        internal fun resetResolvedCacheForTest() = resolvedStatementKeys.clear()
    }

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
                MyBatisCompetitorGate.isPeerPluginActive(),
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
        // F1 判定顺序（①成功入缓存 ②失败+缓存命中 ③失败+dumb 乐观 ④失败+其余不产出）见类头注释。
        // 注意不 catch ProcessCanceledException（平台契约禁吞 PCE；BannedApiScanRuleTest 会拦）。
        val key = "$namespace#$id"
        val project = element.project
        val resolved = MybatisXmlTargets.resolveInterfaceMethod(project, namespace, id)
        if (resolved != null) {
            resolvedStatementKeys.add(key)
            return buildStatementMarker(element)
        }
        // —— 解析失败分支（F1-a/F1-b 全部落在这里，缺一即回退成旧「解析失败即摘图标」形态）——
        if (resolvedStatementKeys.contains(key)) return buildStatementMarker(element) // F1-a
        if (isDumbProvider(project)) return buildStatementMarker(element) // F1-b
        return null
    }

    private fun buildStatementMarker(element: XmlToken): LineMarkerInfo<*> = LineMarkerInfo(
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
                MyBatisCompetitorGate.isPeerPluginActive(),
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
        // F1 同构（T-GutterFirstOpenDelay，治 Java 侧 S2'：冷窗/索引临界期 MapperXmlLocator.locate
        // 瞬时落空（XML 清单未就绪）→ 慢趟结果固化 → Java→XML 图标长期缺席，直到文件改动才恢复——
        // 真机 2026-11-15 实证：CustChannelMapper/CustAddressMapper 全部方法行无图标，daemon 重跑即回。
        // 与 XML 侧同不变式：图标只在「确认真没有」时缺席，不在「还没查到」时缺席。
        // 注意不 catch ProcessCanceledException（平台契约禁吞 PCE；BannedApiScanRuleTest 会拦）。
        val key = "$namespace#${method.name}"
        val project = element.project
        val resolved = MapperXmlLocator.locate(project, "$namespace.${method.name}")
        if (resolved != null) {
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key)
            return buildMethodMarker(element)
        }
        // —— 解析失败分支（与 XML 侧同构：缓存命中/dumb 乐观产出，缺一即回退成旧「解析失败即缺席」形态）——
        if (MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)) {
            return buildMethodMarker(element) // F1-a：XML 侧确认过的语句双向受益
        }
        if (MapperXmlToJavaLineMarkerProvider.isDumbProvider(project)) {
            return buildMethodMarker(element) // F1-b：dumb 窗乐观产出，smart 后重跑自愈
        }
        return null
    }

    private fun buildMethodMarker(element: PsiElement): LineMarkerInfo<*> = LineMarkerInfo(
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
