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
 * **禁止**在本 provider 的判定路径上做任何项目级搜索 / 引用解析：目标是否存在到
 * **点击时**才查（[IbatisJavaCallSites] 走词索引，见其注释）——找不到就无动作、不报错。
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
        return LineMarkerInfo(
            element, element.textRange, MpvsIcons.MapperStatement,
            { MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.tooltip") },
            IbatisToJavaNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.accessible.name") },
        )
    }

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
}
