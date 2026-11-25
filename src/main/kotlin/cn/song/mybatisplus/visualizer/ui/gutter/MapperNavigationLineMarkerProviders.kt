package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets
import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
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
        /** F1-a 会话级正缓存：本会话解析成功过的语句键（形态见 [cacheKey]）。无失效，取舍见类头注释。 */
        internal val resolvedStatementKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * T-MapperNavNamespaceJump：根标签 → 接口类的会话级正缓存（键见 [classCacheKey]）。
         * 语义与 [resolvedStatementKeys] 同口径：只在「确认真没有」时缺席，不在「还没查到」时缺席。
         */
        internal val resolvedClassKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * T-PerfSweep32 券02：缓存键带 `project.locationHash` 前缀——[resolvedStatementKeys] 是
         * 应用级容器、项目关闭不清理，键不含项目身份时两工程同名 namespace+statement 会跨项目串号
         * （A 工程确认过，B 工程直接信）。与 [IbatisJavaToXmlLineMarkerProvider.cacheKey]
         * （T-PerfSweep31 券04）同口径；locationHash 会话内稳定，作键仅限会话级去重语义。
         */
        internal fun cacheKey(project: Project, namespace: String, statementId: String): String =
            "${project.locationHash}|$namespace|$statementId"

        /** T-MapperNavNamespaceJump：根标签类缓存键（同工程同 namespace 复用；`#mapper` 与语句 id 不会撞）。 */
        internal fun classCacheKey(project: Project, namespace: String): String =
            "${project.locationHash}|$namespace|#mapper"

        /** 测试缝：dumb 判定（生产＝`DumbService.isDumb`，F1-b 乐观产出用），形态承 MbgExampleCallResolver。
         *  F1' 起双侧共用（Java 侧 `MapperJavaToXmlLineMarkerProvider` 的失败分支同读此缝）。 */
        internal var isDumbProvider: (Project) -> Boolean = { project ->
            runCatching { DumbService.isDumb(project) }.getOrDefault(false)
        }

        internal fun resetResolvedCacheForTest() {
            resolvedStatementKeys.clear()
            resolvedClassKeys.clear()
            // P2.1 三态测试缝一并复位，防跨用例泄漏（用例用它们驱动 Unknown/Absent 分支）
            MybatisXmlTargets.methodLookupOverrideForTest = null
            MapperXmlLocator.lookupOverrideForTest = null
        }
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
        // 第三道门（T-MapperNavCoexistYield / T-MapperNavYieldJavaSideKeep）：**仅 XML 语句行**的共存
        // 让位——同类插件启用时它自己也会给同一行出跳转箭头，本插件让位。Java 侧（Mapper 接口方法行）
        // 不再让位：对方 Java 箭头与我方同款，让位会让该行只剩对方一枚、用户无法分辨（见
        // MapperJavaToXmlLineMarkerProvider 的 KDoc）。与 XmlSynHiGate 的注入区间互补是两件事：
        // 那边判「对方是否真给这段文本上色」，这边判「对方是否在位」。
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
        // T-MapperNavNamespaceJump：`<mapper namespace="…">` 根标签 → 接口类。
        // 竞品（MPJ view.I / MyBatisX / MyBatisPlus）都在这里给「跳 mapper class」，接口里没有方法也能跳；
        // 本插件此前缺这一格，用户感知为「mapper 没有方法就没法跳转」。锚点＝根标签名 token（与语句锚同形）。
        if (tag.name == "mapper" && tag.parentTag == null &&
            XmlTagUtil.getStartTagNameElement(tag) === token &&
            MybatisXmlTargets.isMapperFile(tag.containingFile)
        ) {
            return createRootMapperMarker(element, tag)
        }
        if (tag.name !in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) return null
        if (XmlTagUtil.getStartTagNameElement(tag) !== token) return null
        if (!MybatisXmlTargets.isMapperFile(tag.containingFile) || tag.parentTag?.parentTag != null) return null
        if (tag.parentTag?.name != "mapper") return null
        val namespace = MybatisXmlTargets.rootNamespace(tag.containingFile) ?: return null
        val id = tag.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: return null
        // T-MapperGutterEvict P2.1（2026-11-16）：五段判定——
        // ① dumb 窗乐观产出（不查 Java 索引，不入缓存）
        // ② 确定存在 → 入正缓存并产出
        // ③ 暂时查不了（索引重建中）→ 缓存命中仍产出、不动缓存（F1-a/S2 兜底）
        // ④ 确定不存在（接口方法删了 / namespace 改了）→ 收回缓存并摘图标（P2.1）
        // ⑤ 其余不产出。注意不 catch ProcessCanceledException（平台契约禁吞 PCE；BannedApiScanRuleTest 会拦）。
        val project = element.project
        val key = cacheKey(project, namespace, id)
        if (isDumbProvider(project)) return buildStatementMarker(element)
        return when (MybatisXmlTargets.lookupInterfaceMethod(project, namespace, id)) {
            is MybatisXmlTargets.MethodLookup.Found -> {
                resolvedStatementKeys.add(key)
                buildStatementMarker(element)
            }
            MybatisXmlTargets.MethodLookup.Unknown ->
                if (resolvedStatementKeys.contains(key)) buildStatementMarker(element) else null
            MybatisXmlTargets.MethodLookup.Absent -> {
                resolvedStatementKeys.remove(key)
                null
            }
        }
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

    /**
     * 根标签 → 接口类图标的五段式（与 [createMarker] 语句侧同不变式）：
     * ① dumb 乐观产出（不查索引、不入缓存）② 确定存在 → 入缓存并产出
     * ③ 索引临界（`IndexNotReadyException`）→ 缓存命中仍产出 ④ 确定不存在 → 收回缓存并摘图标
     * ⑤ 其余不产出。namespace 为空 → 不产出（不出死图标）。
     */
    private fun createRootMapperMarker(element: XmlToken, tag: XmlTag): LineMarkerInfo<*>? {
        val namespace = tag.getAttributeValue("namespace")?.takeIf { it.isNotBlank() } ?: return null
        val project = element.project
        val key = classCacheKey(project, namespace)
        if (isDumbProvider(project)) return buildRootMapperMarker(element)
        val cls = try {
            JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project))
        } catch (e: IndexNotReadyException) {
            return if (resolvedClassKeys.contains(key)) buildRootMapperMarker(element) else null
        }
        return if (cls != null) {
            resolvedClassKeys.add(key)
            buildRootMapperMarker(element)
        } else {
            resolvedClassKeys.remove(key)
            null
        }
    }

    private fun buildRootMapperMarker(element: XmlToken): LineMarkerInfo<*> = LineMarkerInfo(
        element, element.textRange, MpvsIcons.MapperStatement,
        { MpSqlVisualizerBundle.message("gutter.mapperClass.tooltip") },
        { _, elt ->
            val currentTag = elt.parent as? XmlTag ?: return@LineMarkerInfo
            MapperNavigationTargets.mapperClassOfRootTag(currentTag)?.navigate(true)
        },
        com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.CENTER,
        { MpSqlVisualizerBundle.message("gutter.mapperClass.accessible.name") },
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
        // T-MapperNavYieldJavaSideKeep（2026-11-24 用户真机报障）：**Java 侧不再让位**。
        // 实测（fernflower 反编译 MPJ 3.7.0 + 平台 Layer）：同类插件的「接口方法 → XML」箭头注册在
        // language="UAST" 上，而 UAST 是 MetaLanguage（UastMetaLanguage extends MetaLanguage，super("UAST")），
        // LanguageExtension.buildExtensions 会把 meta language 的 key 并入 Java 文件的 provider 收集集合 ⇒
        // 对方在该行**正常出图标**（真机勾选让位后 Java 行 1 枚、取消后 2 枚，均可复现）。
        // 而两家图形同款（我方 mapperMethod.svg 与对方 icon/mybatis_java.svg 同 11 条 path、同三色），
        // 若本侧也让位，该行只剩对方那枚同款图标——用户无法确认本插件是否在工作，读到的就是
        // 「mapper 中的图标没显示」。故 Java 侧恒保留本插件图标；让位只作用于 XML 语句行
        // （对方 XML 箭头与我方跳转同款，且同行已有我方预览图标，避免双箭头）。
        // 让位判据仍是插件级（MyBatisCompetitorGate），只在 XML 侧生效。
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
        val project = element.project
        val key = MapperXmlToJavaLineMarkerProvider.cacheKey(project, namespace, method.name)
        // T-MapperGutterEvict P2.1（2026-11-16）：与 XML 侧同构五段式——dumb 乐观 / 确定存在入缓存 /
        // 暂时查不了保留缓存命中 / 确定不存在收回缓存并摘图标。dumb 门前移，避免在索引重建期查索引。
        if (MapperXmlToJavaLineMarkerProvider.isDumbProvider(project)) return buildMethodMarker(element)
        return when (MapperXmlLocator.locateOrUnknown(project, "$namespace.${method.name}")) {
            is MapperXmlLocator.Lookup.Found -> {
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key)
                buildMethodMarker(element)
            }
            MapperXmlLocator.Lookup.Unknown ->
                if (MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)) buildMethodMarker(element) else null
            MapperXmlLocator.Lookup.Absent -> {
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.remove(key)
                null
            }
        }
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
