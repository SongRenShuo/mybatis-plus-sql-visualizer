package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.xml.XmlAttributeValue
import java.awt.event.MouseEvent

/**
 * iBatis Java DAO 调用行的 gutter 图标（两枚）。
 *
 * ## 券 B（T-Ibatis2Gap）：跳转箭头
 *
 * 锚 = 调用表达式的**方法名 identifier**（`queryForObject` / `insert` / … 的名字 leaf），
 * 图标 `MpvsIcons.MapperMethod`，点击落 iBatis `<sqlMap>` 语句的 `id` 属性值元素
 * （与 Ctrl+B 的 L8 handler 同落点）。
 *
 * ## T-IbatisCallPreview：预览图标
 *
 * 锚 = **首参 String 字面量**，图标 `/icons/sqlGutter.svg`（与 XML 语句行预览同一枚图标），
 * 点击直接渲染该语句的 SQL——参数/条件/`<iterate>`/`<selectKey>` 与 XML 语句行预览逐字节同源
 * （装配唯一入口 [IbatisStatementPreview.previewTag]）。两枚图标**同生共死**（共用同一份
 * `resolveStatementAnchors` 结果）：目标不存在时都不出，不制造「点开必空」的假图标。
 *
 * 为什么两枚由同一个 provider 产出而不是新开一个：两枚 marker 需要同一份语句解析结果，
 * 同 provider 只做一次项目级语句扫描；新开 provider 会让每个调用点每趟多扫一遍全项目 sqlMap
 * 文件（`IbatisXmlStatementTargets` 的结果级 memo 是单槽，两个 provider 会互踢）。
 *
 * ## 锚与门（两枚共用）
 *
 * 门控 **O(1) 语法级**，判定口径照 L8 handler（`ui/nav/IbatisDaoStatementGotoDeclarationHandler`）：
 * 方法名 ∈ [IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS]（`javap` 实测清单的单一事实源）
 * 且**首参是 String 字面量**——不做接收者类型解析（真实工程 `SqlMapClient` 常经 Spring 代理
 * 注入、类型不可解析）。
 *
 * 目标存在性在慢趟收口：[IbatisXmlStatementTargets.resolveStatementAnchors]（`ns.id` /
 * 裸 id，TTL + 结果级 memo 缓存）为空即两枚都不出——普通 Java 类上的同名调用因此零影响
 * （负例判据：`<mapper>` 文件与普通 Java 文件零新图标）。误报面 = 「首参字面量恰好等于某条
 * sqlMap 语句 id 的任意方法名调用」，与 L8 handler 的接管口径一致，由目标存在性兜底。
 *
 * ## 开关
 *
 * 总闸 `showGutterIcon`、分组 `mapperXml` 两枚同灭；`ibatisXmlEnabled` **只灭预览**、
 * 箭头保留（该开关的文案是「预览 iBatis 语句」，跳转不是预览）。
 *
 * ## 与 MyBatis 侧的互斥
 *
 * MyBatis 的 `MapperJavaToXmlLineMarkerProvider` 锚**接口方法声明行**（`containingClass.isInterface`
 * 门控）；本 provider 锚**调用行**——两种锚形态天然不相交；xml 侧再由根标签 `sqlMap` vs
 * `mapper` 互斥。两套互不重复注册同一锚点（用例钉住）。
 *
 * ## 让位门（T-Ibatis2Gap 收尾：与 MyBatis 那对**刻意解耦**，不让位）
 *
 * 竞品 MyBatisCodeHelperPro **没有 iBatis 能力**（其发行包解包扫描 iBatis 命中为 0），`SqlMapExecutor`
 * 调用行上不存在图标冲突——竞品在位时本 provider **照常产出**，不读
 * `mapperNavYieldToCodeHelper`、不走 [MyBatisCompetitorGate]。共用让位门只会把竞品根本不画的
 * iBatis 图标白白藏掉（本插件典型用户 IDE 里就装着 CodeHelperPro，等于功能白做）。
 * 反向锚：`IbatisNavigationLineMarkerProvidersTest` 的「竞品在位」用例。
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
        // T-IbatisCallPreview 拍板 D-4：预览图标受 iBatis 预览开关约束，跳转箭头不受
        val previewEnabled = gutterState == null || gutterState.ibatisXmlEnabled
        // T-Ibatis2Gap 收尾：竞品无 iBatis 能力、无图标冲突，刻意不让位（理由见类 KDoc；
        // 恢复让位的反证锚 = IbatisNavigationLineMarkerProvidersTest 的竞品在位用例）
        for (element in elements) {
            createMarkers(element, previewEnabled)?.let(result::addAll)
        }
    }

    /**
     * 一个调用点 → 两枚 marker（跳转 + 预览）。返回 null 表示该元素不是 iBatis 调用点
     * 或目标语句不存在（两枚同生共死）。
     */
    private fun createMarkers(element: PsiElement, previewEnabled: Boolean): List<LineMarkerInfo<*>>? {
        if (element !is PsiIdentifier) return null
        val ref = element.parent as? PsiReferenceExpression ?: return null
        if (ref.referenceNameElement !== element) return null
        val call = ref.parent as? PsiMethodCallExpression ?: return null
        if (call.methodExpression.referenceName !in IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS) return null
        val first = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return null
        val statementId = (first.value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val project = element.project
        if (DumbService.isDumb(project)) return null
        val anchors = IbatisXmlStatementTargets.resolveStatementAnchors(
            project,
            statementId,
            IbatisCallTargets.ownerNamespace(element),
        )
        if (anchors.isEmpty()) return null
        val markers = ArrayList<LineMarkerInfo<*>>(2)
        // ① 跳转箭头（券 B 现状，锚方法名 identifier）
        markers.add(
            LineMarkerInfo(
                element, element.textRange, MpvsIcons.MapperMethod,
                { MpSqlVisualizerBundle.message("gutter.ibatisCallNav.tooltip") },
                IbatisToXmlNavigationHandler,
                GutterIconRenderer.Alignment.CENTER,
                { MpSqlVisualizerBundle.message("gutter.ibatisCallNav.accessible.name") },
            ),
        )
        // ② 预览图标（T-IbatisCallPreview，锚首参字面量的**叶子 token**——平台要求 LineMarker 锚叶子，
        // 直接锚 PsiLiteralExpression 会触发 "LineMarker is supposed to be registered for leaf elements"；
        // 与 XML 语句行预览同一枚图标）
        if (previewEnabled) {
            val literalLeaf = first.firstChild ?: first
            markers.add(
                LineMarkerInfo(
                    literalLeaf, first.textRange, IbatisXmlGutterLineMarkerProvider.SQL_PREVIEW_ICON,
                    { MpSqlVisualizerBundle.message("gutter.ibatisCallPreview.tooltip") },
                    IbatisCallPreviewHandler,
                    GutterIconRenderer.Alignment.CENTER,
                    { MpSqlVisualizerBundle.message("gutter.ibatisCallPreview.accessible.name") },
                ),
            )
        }
        return markers
    }

    /** 点击处理：重新解析（memo 命中）→ 落到语句 `id` 属性值元素；目标缺失无动作。 */
    private object IbatisToXmlNavigationHandler : GutterIconNavigationHandler<PsiElement> {

        @Suppress("DEPRECATION") // T-M52 同款：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val ref = elt.parent as? PsiReferenceExpression ?: return
            val call = ref.parent as? PsiMethodCallExpression ?: return
            if (call.methodExpression.referenceName !in IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS) return
            val first = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return
            val statementId = (first.value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val project = elt.project
            val target = runCatching {
                ReadAction.compute<XmlAttributeValue?, Throwable> {
                    if (DumbService.isDumb(project)) return@compute null
                    IbatisXmlStatementTargets.resolveStatementAnchors(
                        project,
                        statementId,
                        IbatisCallTargets.ownerNamespace(elt),
                    ).firstOrNull()
                }
            }.getOrNull() ?: return
            // 落点 = 语句 id 属性值元素（OpenFileDescriptor 与 MapperJavaToXml 同款；XmlAttributeValue
            // 不实现 Navigatable，无 navigate(true) 直调形态）
            val file = target.containingFile?.virtualFile?.takeIf { it.isValid } ?: return
            OpenFileDescriptor(project, file, target.textOffset).navigate(true)
        }
    }

    /**
     * 预览点击处理（T-IbatisCallPreview）：EDT 只读字面量值与类限定名（O(1)），
     * 目标解析与文件文本读取全部落在 [SqlPreviewService.previewAsync] 的 ReadAction 内。
     */
    private object IbatisCallPreviewHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            // 锚是字面量的叶子 token，宿主是 PsiLiteralExpression
            val literal = elt as? PsiLiteralExpression ?: elt.parent as? PsiLiteralExpression ?: return
            val statementId = (literal.value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val ownerNamespace = IbatisCallTargets.ownerNamespace(elt)
            val project = elt.project
            SqlPreviewService.previewAsync(project, {
                IbatisStatementPreview.previewResolved(project, statementId, ownerNamespace)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }
}
