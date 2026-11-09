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
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.xml.XmlAttributeValue
import java.awt.event.MouseEvent

/**
 * T-Ibatis2Gap 券 B：Java 侧 `SqlMapExecutor` 调用行 → iBatis `<sqlMap>` 语句的跳转箭头
 * （观感对齐 MyBatis 的 [MapperJavaToXmlLineMarkerProvider]，图标复用 `MpvsIcons.MapperMethod`）。
 *
 * ## 锚与门
 *
 * 锚 = 调用表达式的**方法名 identifier**（`queryForObject` / `insert` / … 的名字 leaf）。
 * 门控 **O(1) 语法级**，判定口径照 L8 handler（`ui/nav/IbatisDaoStatementGotoDeclarationHandler`）：
 * 方法名 ∈ [IbatisJavaCallSites.STATEMENT_ENTRY_METHODS]（`javap` 实测清单）且**首参是 String
 * 字面量**——不做接收者类型解析（真实工程 `SqlMapClient` 常经 Spring 代理注入、类型不可解析）。
 *
 * 目标存在性在慢趟收口：[IbatisXmlStatementTargets.resolveStatementAnchors]（`ns.id` /
 * 裸 id，TTL + 结果级 memo 缓存）为空即不出图标——普通 Java 类上的同名调用因此零影响
 * （负例判据：`<mapper>` 文件与普通 Java 文件零新图标）。误报面 = 「首参字面量恰好等于某条
 * sqlMap 语句 id 的任意方法名调用」，与 L8 handler 的接管口径一致，由目标存在性兜底。
 *
 * 点击时经同一解析重新定位（缓存命中，逐位等价），落点 = 语句 **`id` 属性值元素**（与
 * Ctrl+B 的 L8 handler 同落点）；目标缺失无动作、不报错。
 *
 * ## 与 MyBatis 侧的互斥
 *
 * MyBatis 的 `MapperJavaToXmlLineMarkerProvider` 锚**接口方法声明行**（`containingClass.isInterface`
 * 门控）；本 provider 锚**调用行**——两种锚形态天然不相交；xml 侧再由根标签 `sqlMap` vs
 * `mapper` 互斥。两套互不重复注册同一锚点（用例钉住）。
 *
 * 提示文案暂复用 `gutter.mapperMethod.*`（iBatis 专属文案键待主控补入 bundle，见实施报告）。
 */
class IbatisJavaToXmlLineMarkerProvider : LineMarkerProvider {

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
        if (element !is PsiIdentifier) return null
        val ref = element.parent as? PsiReferenceExpression ?: return null
        if (ref.referenceNameElement !== element) return null
        val call = ref.parent as? PsiMethodCallExpression ?: return null
        if (call.methodExpression.referenceName !in IbatisJavaCallSites.STATEMENT_ENTRY_METHODS) return null
        val first = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return null
        val statementId = (first.value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val project = element.project
        if (DumbService.isDumb(project)) return null
        val anchors = IbatisXmlStatementTargets.resolveStatementAnchors(project, statementId, ownerNamespace(element))
        if (anchors.isEmpty()) return null
        return LineMarkerInfo(
            element, element.textRange, MpvsIcons.MapperMethod,
            { MpSqlVisualizerBundle.message("gutter.mapperMethod.tooltip") },
            IbatisToXmlNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.mapperMethod.accessible.name") },
        )
    }

    /**
     * 调用点所属类的限定名（裸 id 的「同 namespace 优先」排序偏好，与 L8 handler 同语义）。
     * 手写祖先爬升（与 L8 的 nearestCall 同形态），不走全文件遍历助手。
     */
    private fun ownerNamespace(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth++ < 6) {
            if (current is PsiClass) return current.qualifiedName
            current = current.parent
        }
        return null
    }

    /** 点击处理：重新解析（memo 命中）→ 落到语句 `id` 属性值元素；目标缺失无动作。 */
    private object IbatisToXmlNavigationHandler : GutterIconNavigationHandler<PsiElement> {

        @Suppress("DEPRECATION") // T-M52 同款：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val ref = elt.parent as? PsiReferenceExpression ?: return
            val call = ref.parent as? PsiMethodCallExpression ?: return
            if (call.methodExpression.referenceName !in IbatisJavaCallSites.STATEMENT_ENTRY_METHODS) return
            val first = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return
            val statementId = (first.value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val project = elt.project
            val target = runCatching {
                ReadAction.compute<XmlAttributeValue?, Throwable> {
                    if (DumbService.isDumb(project)) return@compute null
                    IbatisXmlStatementTargets.resolveStatementAnchors(
                        project,
                        statementId,
                        ownerNamespaceOf(elt),
                    ).firstOrNull()
                }
            }.getOrNull() ?: return
            // 落点 = 语句 id 属性值元素（OpenFileDescriptor 与 MapperJavaToXml 同款；XmlAttributeValue
            // 不实现 Navigatable，无 navigate(true) 直调形态）
            val file = target.containingFile?.virtualFile?.takeIf { it.isValid } ?: return
            OpenFileDescriptor(project, file, target.textOffset).navigate(true)
        }

        private fun ownerNamespaceOf(elt: PsiElement): String? {
            var current: PsiElement? = elt
            var depth = 0
            while (current != null && depth++ < 6) {
                if (current is PsiClass) return current.qualifiedName
                current = current.parent
            }
            return null
        }
    }
}
