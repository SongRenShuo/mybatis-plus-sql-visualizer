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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.xml.XmlAttributeValue
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap

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
 * 文件。T-IbatisGutterPerf 起解析改走自定义索引（`SqlMapNamespaceIndex`），这条「同一次解析
 * 供两枚」的理由依然成立（每趟仍是每调用点一次索引查询）。
 *
 * ## 锚与门（两枚共用）
 *
 * 门控 **O(1) 语法级**，判定口径照 L8 handler（`ui/nav/IbatisDaoStatementGotoDeclarationHandler`）：
 * 方法名 ∈ [IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS]（`javap` 实测清单的单一事实源）
 * 且**首参是 String 字面量**——不做接收者类型解析（真实工程 `SqlMapClient` 常经 Spring 代理
 * 注入、类型不可解析）。
 *
 * 目标存在性在慢趟收口：[IbatisXmlStatementTargets.resolveStatementAnchors]（`ns.id` / 裸 id，
 * 索引查询）为空即两枚都不出——普通 Java 类上的同名调用因此零影响（负例判据：`<mapper>` 文件与
 * 普通 Java 文件零新图标）。误报面 = 「首参字面量恰好等于某条 sqlMap 语句 id 的任意方法名调用」，
 * 与 L8 handler 的接管口径一致，由目标存在性兜底。
 *
 * ## T-IbatisGutterPerf P2：失败分支兜底（照 MyBatis 侧 F1'/F1' 口径）
 *
 * 慢趟结果**按趟整体替换**：一趟没产出就等于「这个元素没有图标」，而 daemon 不会因为「上次空」
 * 自己再跑一遍——所以「瞬时解析失败」会被固化成「图标粘性缺席」（用户 2026-11-16 真机报障：
 * 图标出现过一次后无故不再显示）。三道兜底：
 *
 * - **正缓存**（[resolvedIbatisKeys]）：本会话解析成功过一次的语句，**暂时**查不到也不摘图标；
 * - **dumb 乐观产出**：索引不可查的窗口（工程打开后头几秒 / 重建索引）照出两枚，**不入缓存**
 *   ——宁可短暂多一枚点了会提示「语句未找到」的图标，也不让已确认过的图标消失；
 * - **确定不存在**（P2.1，2026-11-16 用户报障「改 `namespace` 图标不消失」）：查询**成功执行**
 *   且确认无目标时，收回正缓存并摘图标——缓存只能扛「暂时查不了」，不能给已删除/改名的目标续命。
 *   三态由 [IbatisXmlStatementTargets.lookupStatementAnchors] 提供，不靠「返回是否为空」猜。
 *
 * ## 开关
 *
 * 总闸 `showGutterIcon`、分组 `mapperXml` 两枚同灭；`ibatisXmlEnabled` **只灭预览**、箭头保留
 * （该开关的文案是「预览 iBatis 语句」，跳转不是预览）。
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

    companion object {

        /**
         * T-IbatisGutterPerf P2-a：会话级正缓存——本会话解析成功过的语句键（见 [cacheKey]）。
         *
         * **失效口径（P2.1 起）**：只在「确定不存在」时收回（[IbatisXmlStatementTargets.Lookup.Absent]，
         * 见 `createMarkers` ④）。语义边界写死在这里：缓存扛的是「暂时查不了不摘图标」，
         * **不扛**「目标已经没了」——早期版本无失效，导致改 `namespace` 后图标靠缓存续命不消失
         * （2026-11-16 用户报障）。
         */
        internal val resolvedIbatisKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** 测试缝：dumb 判定（生产＝`DumbService.isDumb`），形态承 `MapperXmlToJavaLineMarkerProvider`。 */
        internal var isDumbProvider: (Project) -> Boolean = { project ->
            runCatching { DumbService.isDumb(project) }.getOrDefault(false)
        }

        private val LOG = Logger.getInstance(IbatisJavaToXmlLineMarkerProvider::class.java)

        /** 乐观产出的日志去重集（每条语句只记一次，避免 dumb 窗每趟刷屏）。 */
        private val optimisticLoggedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

        internal fun resetResolvedCacheForTest() {
            resolvedIbatisKeys.clear()
            optimisticLoggedKeys.clear()
            IbatisXmlStatementTargets.lookupOverrideForTest = null
        }

        /**
         * 正缓存键：`ns.id` 形态直接用语句 id；裸 id 带上所属类限定名——同一个裸 id 在不同 DAO
         * 上可能偏好不同的 sqlMap 文件，键必须把命名空间带进去，否则一个 DAO 的成功会替另一个
         * 造出「点了可能落到别家文件」的图标。
         *
         * T-PerfSweep31 券04：键再带 `project.locationHash` 前缀——[resolvedIbatisKeys] 是应用级
         * 静态容器、项目关闭不清理，裸 `ns.id` 作键会让两个工程对同一条语句互踩正缓存
         * （A 工程确认过调用点，B 工程直接信 → Absent 也照样画）。跨项目撞键面消除；
         * 会话内增长仍以语句数为上界。
         */
        private fun cacheKey(project: Project, statementId: String, ownerNamespace: String?): String =
            "${project.locationHash}|" +
                if (statementId.contains('.')) statementId else "$statementId@${ownerNamespace ?: ""}"

        /** P2 兜底链路日志锚点：`[gutter-perf] ibatis resolve: source=optimistic …`（每条一次）。 */
        private fun logOptimisticOnce(project: Project, statementId: String) {
            if (!optimisticLoggedKeys.add("${project.locationHash}|$statementId")) return
            LOG.info("[gutter-perf] ibatis resolve: source=optimistic statementId=$statementId dumb=true")
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
     * 或目标语句确实不存在（两枚同生共死）。
     *
     * 五段判定（T-IbatisGutterPerf P2 / P2.1）：dumb 窗乐观产出 → 确定存在入正缓存 → 暂时查不了
     * 且缓存命中仍产出 → 确定不存在收回缓存并摘图标 → 其余不产出。**不 catch 任何异常**
     * （`ProcessCanceledException` 必须上抛让本趟作废，吞掉等于把「没算完」写成「没有」）。
     *
     * 「存在形态」与「消失形态」各由一个真机报障定义：S2＝冷窗瞬时失败把图标摘掉再不回来；
     * P2.1＝`User.xml` 改掉 `namespace` 后图标靠正缓存续命不消失。两者只能靠三态区分
     * （见 [IbatisXmlStatementTargets.lookupStatementAnchors]），不能靠「返回空不空」。
     */
    private fun createMarkers(element: PsiElement, previewEnabled: Boolean): List<LineMarkerInfo<*>>? {
        if (element !is PsiIdentifier) return null
        val ref = element.parent as? PsiReferenceExpression ?: return null
        if (ref.referenceNameElement !== element) return null
        val call = ref.parent as? PsiMethodCallExpression ?: return null
        if (call.methodExpression.referenceName !in IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS) return null
        val first = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return null
        val statementId = (first.value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // ownerNamespace 必须从**调用表达式**起爬（identifier 到 PsiClass 多一跳，超出
        // IbatisCallTargets.ownerNamespace 的 6 跳上限会静默取到 null——裸 id 的排序偏好与
        // 正缓存键都靠它，测试 `testF1i_裸id缓存键带所属类限定名` 钉住）
        val ownerNamespace = IbatisCallTargets.ownerNamespace(call)
        val project = element.project
        val key = cacheKey(project, statementId, ownerNamespace)

        // ① dumb 窗（工程打开头几秒 / 重建索引）：索引不可查，按「未知」乐观产出两枚、不入缓存。
        if (isDumbProvider(project)) {
            logOptimisticOnce(project, statementId)
            return buildMarkers(element, first, previewEnabled)
        }
        return when (val lookup = IbatisXmlStatementTargets.lookupStatementAnchors(project, statementId, ownerNamespace)) {
            // ② 确定存在：入正缓存并产出。
            is IbatisXmlStatementTargets.Lookup.Found -> {
                resolvedIbatisKeys.add(key)
                buildMarkers(element, first, previewEnabled)
            }
            // ③ 暂时查不了（索引重建中 / 候选 PSI 瞬时取不到）：本会话确认过的语句继续显示，
            //    不摘图标、不动缓存（S2 旧报障「图标无故消失」的兜底）。
            IbatisXmlStatementTargets.Lookup.Unknown ->
                if (resolvedIbatisKeys.contains(key)) buildMarkers(element, first, previewEnabled) else null
            // ④ 确定不存在（语句被删/改名、namespace 改了）：收回缓存并摘图标——缓存不得给
            //    不存在的目标续命（2026-11-16 用户报障「改 namespace 图标不消失」）。
            IbatisXmlStatementTargets.Lookup.Absent -> {
                resolvedIbatisKeys.remove(key)
                null
            }
        }
    }

    /** 两枚 marker 的构造（三条产出路径共用，避免形态漂移）。 */
    private fun buildMarkers(
        element: PsiIdentifier,
        first: PsiLiteralExpression,
        previewEnabled: Boolean,
    ): List<LineMarkerInfo<*>> {
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

    /** 点击处理：重新解析（索引命中）→ 落到语句 `id` 属性值元素；目标缺失无动作。 */
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
                        IbatisCallTargets.ownerNamespace(call),
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
            // 与 gutter/跳转同口径：所属类限定名从调用表达式起爬（见 createMarkers 同名注释）
            val ownerNamespace = IbatisCallTargets.resolve(literal)?.ownerNamespace
            val project = elt.project
            SqlPreviewService.previewAsync(project, {
                IbatisStatementPreview.previewResolved(project, statementId, ownerNamespace)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }
}