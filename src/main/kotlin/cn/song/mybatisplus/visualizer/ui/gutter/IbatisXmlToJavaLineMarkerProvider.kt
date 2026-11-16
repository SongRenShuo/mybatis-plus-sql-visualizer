package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.xml.util.XmlTagUtil
import java.awt.event.MouseEvent

/**
 * T-Ibatis2Gap 券 B：iBatis `<sqlMap>` 语句行 → Java 侧 `SqlMapExecutor` 调用点的跳转箭头
 * （观感对齐 MyBatis 的 [MapperXmlToJavaLineMarkerProvider]，图标复用 `MpvsIcons.MapperStatement`）。
 *
 * ## 锚与门（热路径形态）
 *
 * 锚 = 语句标签的**起始名 token**（`getLineMarkerInfo` 永远返回 null，全部走慢趟
 * `collectSlowLineMarkers`——与 MyBatis 那对同形）。判定全部 **O(1) 语法级**：
 *  1. 根标签名 == `sqlMap`（[IbatisXmlStatementTargets.isSqlMapFile]，与券 F 的 L8 handler 同门）；
 *  2. 标签名 ∈ [IbatisXmlGutterLineMarkerProvider.STATEMENT_TAGS]（券 A 的单一事实源）；
 *  3. 该 token 是 `XmlTagUtil.getStartTagNameElement`（一语句一锚，闭合名不重复出图标）；
 *  4. 语句标签是 `<sqlMap>` 直接子级且 `id` 非空。
 *
 * ## 目标存在性（T-IbatisXmlArrowCallerGate，2026-11-16 口径变更）
 *
 * 原口径＝**无条件出箭头**，目标是否存在留到点击时查（点击找不到就无动作）。用户 2026-11-16
 * 报障：把 XML 语句 `id` 改名（`selectAll` → `selectAll02`）后，**语句行仍挂着箭头**、点下去
 * 没反应——即「点开必空」的假图标，与 MyBatis 侧（`MapperXmlToJavaLineMarkerProvider` 必须先
 * 解析到接口方法才画）和本插件自己的「同生共死」原则都不一致，故本刀改成**按调用点存在性出箭头**。
 *
 * 代价与护栏（本次是有意的取舍，不是回归）：
 *  - 底层是词索引查询（[IbatisJavaCallSites.lookupCallSite]），**常见词命中面大**（`selectAll`
 *    这类 id 在 Java 侧也是常见方法名）；因此按 F1/P2.1 同款「命中入缓存 / 确认缺失收回缓存」
 *    处理——同一语句只在判定变化时付费，稳态每趟零查询；
 *  - 单词条非法标识符形态（含 `.`/`-` 等）判不了 → [IbatisJavaCallSites.CallLookup.Unknown]，
 *    保留既有缓存判定，不因此隐藏箭头；
 *  - 回退通道：Registry `sqlvis.gutter.ibatisXmlArrowRequiresCaller=false` 回到「无条件出箭头」
 *    旧行为（真机 A/B 用；下一版删除）。
 *
 * 仍**禁止**在本 provider 内直接写项目级搜索 API（`GlobalSearchScope`/`PsiSearchHelper`/
 * `PsiTreeUtil` 等）——判定只能经 `IbatisJavaCallSites` 这一个收口（`IbatisGutterHotPathTest`
 * 的禁用词表原样保留，扫面含本文件的 `createMarker`）。
 *
 * 与 MyBatis 侧的分工：`MapperXmlToJavaLineMarkerProvider` 只认根标签 `mapper`（`isMapperFile`
 * 门控），本 provider 只认 `sqlMap`——两套根标签天然互斥，同一行不会双箭头（用例钉住）。
 *
 * ## 让位门（T-Ibatis2Gap 收尾：与 MyBatis 那对**刻意解耦**，不让位）
 *
 * 竞品 MyBatisCodeHelperPro **没有 iBatis 能力**（其发行包解包扫描 iBatis 命中为 0），sqlMap
 * 文件与 `SqlMapExecutor` 调用行上不存在图标冲突——竞品在位时本 provider **照常产出箭头**，
 * 不读 `mapperNavYieldToCodeHelper`、不走 [MyBatisCompetitorGate]。共用让位门只会把竞品根本
 * 不画的 iBatis 箭头白白藏掉（本插件典型用户 IDE 里就装着 CodeHelperPro，等于功能白做）。
 * 反向锚：`IbatisNavigationLineMarkerProvidersTest` 的「竞品在位」用例——恢复让位必恰它变红；
 * MyBatis 那对（[MapperXmlToJavaLineMarkerProvider]）的让位行为不变，由其自身守卫与对照用例钉住。
 *
 * ## 预览图标共存
 *
 * iBatis 语句行同时有券 A 的预览图标（[IbatisXmlGutterLineMarkerProvider]，快路径锚）与本
 * provider 的跳转箭头（慢趟锚）——两者**共存是预期**（MyBatis 文件同样预览 + 跳转并存）。
 */
class IbatisXmlToJavaLineMarkerProvider : LineMarkerProvider {

    companion object {

        /** 回退通道：false＝回到「无条件出箭头」旧行为（真机 A/B 用，下一版删除）。 */
        internal const val REQUIRES_CALLER_REGISTRY_KEY = "sqlvis.gutter.ibatisXmlArrowRequiresCaller"

        /**
         * 本会话确认过「Java 侧存在调用点」的语句键（`namespace|statementId`）。
         * 与 `IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys` 同口径：命中入缓存、
         * **确认缺失即收回**（P2.1 教训：只进不出的缓存会给已删除的目标续命）。
         */
        internal val resolvedIbatisCallKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** 测试缝：dumb 判定（生产＝`DumbService.isDumb`）。 */
        internal var isDumbProvider: (Project) -> Boolean = { project ->
            runCatching { DumbService.isDumb(project) }.getOrDefault(false)
        }

        /** 测试缝：回退开关（生产＝`Registry.is(key, true)`；键未登记时直接返回默认值）。 */
        internal var requiresCallerProvider: () -> Boolean = {
            // `is` 是 Kotlin 关键字，调 Java 侧 `Registry.is(String, boolean)` 必须加反引号。
            runCatching { com.intellij.openapi.util.registry.Registry.`is`(REQUIRES_CALLER_REGISTRY_KEY, true) }
                .getOrDefault(true)
        }

        internal fun resetCallKeysCacheForTest() {
            resolvedIbatisCallKeys.clear()
            IbatisJavaCallSites.lookupOverrideForTest = null
        }
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        // 券01（T-PerfSweep28-01）同款门形：方法头单次取 state，总闸与组显隐共用
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return
        // T-Ibatis2Gap 收尾：竞品无 iBatis 能力、无图标冲突，刻意不让位（理由见类 KDoc；
        // 恢复让位的反证锚 = IbatisNavigationLineMarkerProvidersTest 的竞品在位用例）
        elements.mapNotNullTo(result, ::createMarker)
    }

    private fun createMarker(element: PsiElement): LineMarkerInfo<*>? {
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        if (tag.name !in IbatisXmlGutterLineMarkerProvider.STATEMENT_TAGS) return null
        if (XmlTagUtil.getStartTagNameElement(tag) !== token) return null
        val file = tag.containingFile
        if (!IbatisXmlStatementTargets.isSqlMapFile(file)) return null
        if (tag.parentTag?.name != IbatisXmlStatementTargets.SQL_MAP_ROOT_TAG) return null
        val statementId = tag.getAttributeValue("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 回退通道：旧行为＝无条件出箭头（目标存在性留到点击时查）
        if (!requiresCallerProvider()) return buildMarker(element)
        val project = element.project
        // dumb 窗：词索引不可查，乐观产出（不入缓存）——与 Java→XML 侧 F1-b 同口径
        if (isDumbProvider(project)) return buildMarker(element)
        val namespace = IbatisXmlStatementTargets.rootNamespace(file)
        val key = "${namespace.orEmpty()}|$statementId"
        return when (val lookup = IbatisJavaCallSites.lookupCallSite(project, namespace, statementId)) {
            is IbatisJavaCallSites.CallLookup.Found -> {
                resolvedIbatisCallKeys.add(key)
                buildMarker(element)
            }
            // 暂时判不了（词形态不支持等）：保留已确认过的箭头，不新画
            IbatisJavaCallSites.CallLookup.Unknown ->
                if (resolvedIbatisCallKeys.contains(key)) buildMarker(element) else null
            // 确定没有调用点（语句改名/删了调用）：收回缓存并摘箭头——不制造「点开必空」的假图标
            IbatisJavaCallSites.CallLookup.Absent -> {
                resolvedIbatisCallKeys.remove(key)
                null
            }
        }
    }

    private fun buildMarker(element: PsiElement): LineMarkerInfo<*> = LineMarkerInfo(
        element, element.textRange, MpvsIcons.MapperStatement,
        { MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.tooltip") },
        IbatisToJavaNavigationHandler,
        GutterIconRenderer.Alignment.CENTER,
        { MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.accessible.name") },
    )

    /** 点击处理：EDT 内联 ReadAction 里走词索引找调用点（找不到 = 无动作，不弹提示不报错）。 */
    private object IbatisToJavaNavigationHandler : GutterIconNavigationHandler<PsiElement> {

        @Suppress("DEPRECATION") // T-M52 同款：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val tag = elt.parent as? XmlTag ?: return
            val project = elt.project
            val statementId = tag.getAttributeValue("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val namespace = IbatisXmlStatementTargets.rootNamespace(tag.containingFile)
            val target = runCatching {
                ReadAction.compute<PsiLiteralExpression?, Throwable> {
                    IbatisJavaCallSites.findFirstCallLiteral(project, namespace, statementId)
                }
            }.getOrNull() ?: return
            // 落点 = 调用首参字面量（OpenFileDescriptor 与 MapperJavaToXml 同款；PsiLiteralExpression
            // 不实现 Navigatable，无 navigate(true) 直调形态）
            val file = target.containingFile?.virtualFile?.takeIf { it.isValid } ?: return
            OpenFileDescriptor(project, file, target.textOffset).navigate(true)
        }
    }
}

/**
 * XML → Java 方向的**点击期**目标解析（T-Ibatis2Gap 券 B）。
 *
 * 在项目里找「`SqlMapExecutor` 入口方法调用 + 首参 String 字面量值 == `ns.id` 或裸 `id`」的
 * 调用点字面量。只服务点击/跳转（不在 daemon 任何路径调用），但实现仍取**词索引**
 * （[PsiSearchHelper.processElementsWithWord]）而不是全项目字面量遍历——真实工程 Java 文件量
 * 远大于 XML，点击延迟也要可接受。词取 **statementId 的最后一段**（点号前缀/无点号两种形态
 * 的字面量都含该词）；dumb 态索引不可用 → 返回 null（无动作）。
 *
 * 判定语义与 L8 handler（[cn.song.mybatisplus.visualizer.ui.nav.IbatisDaoStatementGotoDeclarationHandler]）
 * 反向对齐：入口方法集同一批（`javap` 实测 statementId 都在第一参数位）；只认**首参**，
 * 不在实参表里搜字面量。找不到返回 null，绝不抛穿。
 */
internal object IbatisJavaCallSites {

    /** 首个命中字面量（`ns.id` / 裸 `id` / 任意 namespace 前缀的 `.id` 尾缀形态）。 */
    fun findFirstCallLiteral(project: Project, namespace: String?, statementId: String): PsiLiteralExpression? =
        findAllCallLiterals(project, namespace, statementId, 1).firstOrNull()

    /**
     * 全部命中字面量（T-IbatisFix4：XML 语句 `id` 的 Ctrl+Click 目标集）。
     *
     * 判定与 [findFirstCallLiteral] 完全同源（同一个词索引 + 同一套 [matches] 口径），差别只是
     * 不提前收工。`limit` 防「一条语句几百个调用点」把 GTD 目标表撑爆——平台拿到多目标会弹
     * 候选列表，超出上限的尾部调用点不列。
     */
    fun findAllCallLiterals(
        project: Project,
        namespace: String?,
        statementId: String,
        limit: Int,
    ): List<PsiLiteralExpression> {
        val sid = statementId.trim()
        if (sid.isEmpty() || limit <= 0) return emptyList()
        val word = sid.substringAfterLast('.')
        if (word.isEmpty() || !word.all { Character.isJavaIdentifierPart(it) }) return emptyList()
        if (DumbService.isDumb(project)) return emptyList()
        val found = ArrayList<PsiLiteralExpression>(minOf(limit, 4))
        // TextOccurenceProcessor.execute(element, offsetInElement)：element = 词命中的宿主元素
        // （多为字面量本身或其内部 leaf），手工上爬找宿主字面量
        val processor = TextOccurenceProcessor { element, _ ->
            var cur: PsiElement? = element
            var literal: PsiLiteralExpression? = null
            while (cur != null) {
                if (cur is PsiLiteralExpression) {
                    literal = cur
                    break
                }
                cur = cur.parent
            }
            val hit = literal ?: return@TextOccurenceProcessor true
            if (!matches(hit, namespace, sid)) return@TextOccurenceProcessor true
            val call = PsiTreeUtil.getParentOfType(hit, PsiMethodCallExpression::class.java) ?: return@TextOccurenceProcessor true
            if (call.methodExpression.referenceName !in IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS) return@TextOccurenceProcessor true
            if (call.argumentList?.expressions?.firstOrNull() !== hit) return@TextOccurenceProcessor true
            // 同一字面量的多个词命中（字面量内含点号分段）只记一次
            if (found.any { it === hit }) return@TextOccurenceProcessor true
            found.add(hit)
            found.size < limit
        }
        runCatching {
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                processor,
                GlobalSearchScope.projectScope(project),
                word,
                UsageSearchContext.IN_STRINGS,
                true,
            )
        }
        return found
    }

    /** 字面量值命中：裸 `id`、`ns.id`（本文件 namespace）、或任意前缀的尾段 `… . id`。 */
    private fun matches(literal: PsiLiteralExpression, namespace: String?, statementId: String): Boolean {
        val value = (literal.value as? String)?.trim() ?: return false
        if (value == statementId) return true
        if (namespace != null && value == "$namespace.$statementId") return true
        return value.endsWith(".$statementId") && value.length > statementId.length + 1
    }

    /**
     * 「Java 侧是否存在调用点」**三态**（T-IbatisXmlArrowCallerGate，2026-11-16 用户报障）：
     *
     * - [CallLookup.Found]：找到调用点字面量（箭头该出）；
     * - [CallLookup.Absent]：词索引查询成功执行、确认没有调用点（箭头不该出——用户把 XML 语句
     *   `id` 改名后，XML 行仍挂着「点了没反应」的箭头，正是缺这一态）；
     * - [CallLookup.Unknown]：暂时判不了（dumb / 词不是合法 Java 标识符形态）——调用方保留既有判定。
     */
    sealed interface CallLookup {
        class Found(val literal: PsiLiteralExpression) : CallLookup
        object Absent : CallLookup
        object Unknown : CallLookup
    }

    /** 测试缝：替换三态查询（生产＝null）。矩阵用例用它驱动 Unknown / Absent 分支。 */
    internal var lookupOverrideForTest: ((Project, String?, String) -> CallLookup)? = null

    /**
     * 调用点存在性三态（供 gutter 判定；点击期仍走 [findFirstCallLiteral]，两者同源同一个词索引查询）。
     *
     * 成本提示：底层是 `processElementsWithWord` 词索引查询，**常见词（如 `selectAll`）命中面较大**，
     * 故调用方（`IbatisXmlToJavaLineMarkerProvider`）按「命中入缓存 / 确认缺失收回缓存」处理，
     * 避免每趟慢 pass 对每条语句重复付费；另有 Registry 回退通道可整项关掉该判定。
     */
    fun lookupCallSite(project: Project, namespace: String?, statementId: String): CallLookup {
        val sid = statementId.trim()
        if (sid.isEmpty()) return CallLookup.Absent
        lookupOverrideForTest?.let { override -> return override(project, namespace, sid) }
        val word = sid.substringAfterLast('.')
        if (word.isEmpty() || !word.all { Character.isJavaIdentifierPart(it) }) return CallLookup.Unknown
        if (DumbService.isDumb(project)) return CallLookup.Unknown
        val found = findAllCallLiterals(project, namespace, sid, 1).firstOrNull()
        return if (found != null) CallLookup.Found(found) else CallLookup.Absent
    }
}
