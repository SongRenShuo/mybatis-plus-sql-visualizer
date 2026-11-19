package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * iBatis Java DAO 调用行的 gutter 图标（两枚）。
 *
 * <h2>券 B（T-Ibatis2Gap）：跳转箭头</h2>
 *
 * <p>锚 = 调用表达式的<b>方法名 identifier</b>（queryForObject / insert / … 的名字 leaf），
 * 图标 {@link MpvsIcons#MapperMethod}，点击落 iBatis {@code <sqlMap>} 语句的 {@code id} 属性值元素
 * （与 Ctrl+B 的 L8 handler 同落点）。</p>
 *
 * <h2>T-IbatisCallPreview：预览图标</h2>
 *
 * <p>锚 = <b>首参 String 字面量</b>，图标 {@code /icons/sqlGutter.svg}（与 XML 语句行预览同一枚图标），
 * 点击直接渲染该语句的 SQL——参数/条件/{@code <iterate>}/{@code <selectKey>} 与 XML 语句行预览
 * 逐字节同源（装配唯一入口 {@link IbatisStatementPreview#previewTag}）。两枚图标<b>同生共死</b>
 * （共用同一份 resolveStatementAnchors 结果）：目标不存在时都不出，不制造「点开必空」的假图标。</p>
 *
 * <p>为什么两枚由同一个 provider 产出而不是新开一个：两枚 marker 需要同一份语句解析结果，
 * 同 provider 只做一次项目级语句扫描；新开 provider 会让每个调用点每趟多扫一遍全项目 sqlMap
 * 文件。T-IbatisGutterPerf 起解析改走自定义索引（SqlMapNamespaceIndex），这条「同一次解析供两枚」
 * 的理由依然成立（每趟仍是每调用点一次索引查询）。</p>
 *
 * <h2>锚与门（两枚共用）</h2>
 *
 * <p>门控 <b>O(1) 语法级</b>，判定口径照 L8 handler（IbatisDaoStatementGotoDeclarationHandler）：
 * 方法名 ∈ {@link IbatisXmlStatementTargets#STATEMENT_ENTRY_METHODS}（javap 实测清单的单一事实源）
 * 且<b>首参是 String 字面量</b>——不做接收者类型解析（真实工程 SqlMapClient 常经 Spring 代理注入、
 * 类型不可解析）。</p>
 *
 * <p>目标存在性在慢趟收口：{@link IbatisXmlStatementTargets#resolveStatementAnchors}（ns.id / 裸 id，
 * 索引查询）为空即两枚都不出——普通 Java 类上的同名调用因此零影响（负例判据：{@code <mapper>}
 * 文件与普通 Java 文件零新图标）。误报面 = 「首参字面量恰好等于某条 sqlMap 语句 id 的任意方法名
 * 调用」，与 L8 handler 的接管口径一致，由目标存在性兜底。</p>
 *
 * <h2>T-IbatisGutterPerf P2：失败分支兜底（照 MyBatis 侧 F1'/F1' 口径）</h2>
 *
 * <p>慢趟结果<b>按趟整体替换</b>：一趟没产出就等于「这个元素没有图标」，而 daemon 不会因为
 * 「上次空」自己再跑一遍——所以「瞬时解析失败」会被固化成「图标粘性缺席」（用户 2026-11-16 真机
 * 报障：图标出现过一次后无故不再显示）。三道兜底：</p>
 *
 * <ul>
 * <li><b>正缓存</b>（{@link #resolvedIbatisKeys}）：本会话解析成功过一次的语句，<b>暂时</b>查不到
 *     也不摘图标；</li>
 * <li><b>dumb 乐观产出</b>：索引不可查的窗口（工程打开后头几秒 / 重建索引）照出两枚，<b>不入缓存</b>
 *     ——宁可短暂多一枚点了会提示「语句未找到」的图标，也不让已确认过的图标消失；</li>
 * <li><b>确定不存在</b>（P2.1，2026-11-16 用户报障「改 namespace 图标不消失」）：查询<b>成功执行</b>
 *     且确认无目标时，收回正缓存并摘图标——缓存只能扛「暂时查不了」，不能给已删除/改名的目标续命。
 *     三态由 {@link IbatisXmlStatementTargets#lookupStatementAnchors} 提供，不靠「返回是否为空」猜。</li>
 * </ul>
 *
 * <h2>开关</h2>
 *
 * <p>总闸 {@code showGutterIcon}、分组 mapperXml 两枚同灭；{@code ibatisXmlEnabled} <b>只灭预览</b>、
 * 箭头保留（该开关的文案是「预览 iBatis 语句」，跳转不是预览）。</p>
 *
 * <h2>与 MyBatis 侧的互斥</h2>
 *
 * <p>MyBatis 的 MapperJavaToXmlLineMarkerProvider 锚<b>接口方法声明行</b>（isInterface 门控）；
 * 本 provider 锚<b>调用行</b>——两种锚形态天然不相交；xml 侧再由根标签 sqlMap vs mapper 互斥。
 * 两套互不重复注册同一锚点（用例钉住）。</p>
 *
 * <h2>让位门（T-Ibatis2Gap 收尾：与 MyBatis 那对<b>刻意解耦</b>，不让位）</h2>
 *
 * <p>竞品 MyBatisCodeHelperPro <b>没有 iBatis 能力</b>（其发行包解包扫描 iBatis 命中为 0），
 * SqlMapExecutor 调用行上不存在图标冲突——竞品在位时本 provider <b>照常产出</b>，不读
 * {@code mapperNavYieldToCodeHelper}、不走 {@code MyBatisCompetitorGate}。共用让位门只会把竞品
 * 根本不画的 iBatis 图标白白藏掉（本插件典型用户 IDE 里就装着 CodeHelperPro，等于功能白做）。
 * 反向锚：IbatisNavigationLineMarkerProvidersTest 的「竞品在位」用例。</p>
 */
public class IbatisJavaToXmlLineMarkerProvider implements LineMarkerProvider {

    private static final Logger LOG = Logger.getInstance(IbatisJavaToXmlLineMarkerProvider.class);

    /**
     * T-IbatisGutterPerf P2-a：会话级正缓存——本会话解析成功过的语句键（见 {@link #cacheKey}）。
     *
     * <p><b>失效口径（P2.1 起）</b>：只在「确定不存在」时收回（{@link IbatisXmlStatementTargets.Absent}）。
     * 语义边界写死在这里：缓存扛的是「暂时查不了不摘图标」，<b>不扛</b>「目标已经没了」——早期版本
     * 无失效，导致改 namespace 后图标靠缓存续命不消失（2026-11-16 用户报障）。</p>
     */
    public static final Set<String> RESOLVED_IBATIS_KEYS = ConcurrentHashMap.newKeySet();

    /** 乐观产出的日志去重集（每条语句只记一次，避免 dumb 窗每趟刷屏）。 */
    private static final Set<String> OPTIMISTIC_LOGGED_KEYS = ConcurrentHashMap.newKeySet();

    /** 测试缝：dumb 判定（生产＝DumbService.isDumb），形态承 MapperXmlToJavaLineMarkerProvider。 */
    public static volatile Function<Project, Boolean> isDumbProvider = project -> {
        try {
            return DumbService.isDumb(project);
        } catch (Throwable t) {
            return false;
        }
    };

    /** 测试缝：清空正缓存与乐观日志去重集（对应 master resetResolvedCacheForTest）。 */
    public static void resetResolvedCacheForTest() {
        RESOLVED_IBATIS_KEYS.clear();
        OPTIMISTIC_LOGGED_KEYS.clear();
        IbatisXmlStatementTargets.lookupOverrideForTest = null;
    }

    /** Kotlin object 语义兼容实例（供外部 IbatisJavaToXmlLineMarkerProvider.INSTANCE 路由）。 */
    public static final IbatisJavaToXmlLineMarkerProvider INSTANCE = new IbatisJavaToXmlLineMarkerProvider();

    /**
     * 正缓存键：{@code ns.id} 形态直接用语句 id；裸 id 带上所属类限定名——同一个裸 id 在不同 DAO
     * 上可能偏好不同的 sqlMap 文件，键必须把命名空间带进去，否则一个 DAO 的成功会替另一个
     * 造出「点了可能落到别家文件」的图标。
     *
     * <p>T-PerfSweep31 券04：键再带 {@code project.locationHash} 前缀——RESOLVED_IBATIS_KEYS 是应用级
     * 静态容器、项目关闭不清理，裸 {@code ns.id} 作键会让两个工程对同一条语句互踩正缓存
     * （A 工程确认过调用点，B 工程直接信 → Absent 也照样画）。跨项目撞键面消除；
     * 会话内增长仍以语句数为上界。</p>
     */
    static String cacheKey(@NotNull Project project, @NotNull String statementId, @Nullable String ownerNamespace) {
        return project.getLocationHash() + "|"
            + (statementId.indexOf('.') >= 0 ? statementId : statementId + "@" + (ownerNamespace != null ? ownerNamespace : ""));
    }

    /** P2 兜底链路日志锚点：{@code [gutter-perf] ibatis resolve: source=optimistic …}（每条一次）。 */
    private static void logOptimisticOnce(@NotNull Project project, @NotNull String statementId) {
        if (!OPTIMISTIC_LOGGED_KEYS.add(project.getLocationHash() + "|" + statementId)) {
            return;
        }
        LOG.info("[gutter-perf] ibatis resolve: source=optimistic statementId=" + statementId + " dumb=true");
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super LineMarkerInfo<?>> result) {
        // 券01（T-PerfSweep28-01）同款门形：方法头单次取 state，总闸与组显隐共用
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) {
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return;
        // T-IbatisCallPreview 拍板 D-4：预览图标受 iBatis 预览开关约束，跳转箭头不受
        boolean previewEnabled = gutterState == null || gutterState.getIbatisXmlEnabled();
        // T-Ibatis2Gap 收尾：竞品无 iBatis 能力、无图标冲突，刻意不让位（理由见类 KDoc；
        // 恢复让位的反证锚 = IbatisNavigationLineMarkerProvidersTest 的竞品在位用例）
        for (PsiElement element : elements) {
            List<LineMarkerInfo<?>> markers = createMarkers(element, previewEnabled);
            if (markers != null) {
                result.addAll(markers);
            }
        }
    }

    /**
     * 一个调用点 → 两枚 marker（跳转 + 预览）。返回 null 表示该元素不是 iBatis 调用点
     * 或目标语句确实不存在（两枚同生共死）。
     *
     * <p>五段判定（T-IbatisGutterPerf P2 / P2.1）：dumb 窗乐观产出 → 确定存在入正缓存 →
     * 暂时查不了且缓存命中仍产出 → 确定不存在收回缓存并摘图标 → 其余不产出。
     * <b>不 catch 任何异常</b>（ProcessCanceledException 必须上抛让本趟作废，吞掉等于把
     * 「没算完」写成「没有」）。</p>
     *
     * <p>「存在形态」与「消失形态」各由一个真机报障定义：S2＝冷窗瞬时失败把图标摘掉再不回来；
     * P2.1＝User.xml 改掉 namespace 后图标靠正缓存续命不消失。两者只能靠三态区分
     * （见 {@link IbatisXmlStatementTargets#lookupStatementAnchors}），不能靠「返回空不空」。</p>
     */
    @Nullable
    private List<LineMarkerInfo<?>> createMarkers(@NotNull PsiElement element, boolean previewEnabled) {
        if (!(element instanceof PsiIdentifier identifier)) {
            return null;
        }
        if (!(identifier.getParent() instanceof PsiReferenceExpression ref)) {
            return null;
        }
        if (ref.getReferenceNameElement() != identifier) {
            return null;
        }
        if (!(ref.getParent() instanceof PsiMethodCallExpression call)) {
            return null;
        }
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || !IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS.contains(methodName)) {
            return null;
        }
        var expressions = call.getArgumentList().getExpressions();
        if (expressions.length == 0 || !(expressions[0] instanceof PsiLiteralExpression first)) {
            return null;
        }
        Object rawValue = first.getValue();
        String statementId = rawValue instanceof String s ? s.trim() : null;
        if (statementId == null || statementId.isEmpty()) {
            return null;
        }
        // ownerNamespace 必须从**调用表达式**起爬（identifier 到 PsiClass 多一跳，超出
        // IbatisCallTargets.ownerNamespace 的 6 跳上限会静默取到 null——裸 id 的排序偏好与
        // 正缓存键都靠它，测试 testF1i_裸id缓存键带所属类限定名 钉住）
        String ownerNamespace = IbatisCallTargets.ownerNamespace(call);
        Project project = identifier.getProject();
        String key = cacheKey(project, statementId, ownerNamespace);

        // ① dumb 窗（工程打开头几秒 / 重建索引）：索引不可查，按「未知」乐观产出两枚、不入缓存。
        if (isDumbProvider.apply(project)) {
            logOptimisticOnce(project, statementId);
            return buildMarkers(identifier, first, previewEnabled);
        }
        IbatisXmlStatementTargets.Lookup lookup =
                IbatisXmlStatementTargets.lookupStatementAnchors(project, statementId, ownerNamespace);
        if (lookup instanceof IbatisXmlStatementTargets.Found found) {
            // ② 确定存在：入正缓存并产出。
            RESOLVED_IBATIS_KEYS.add(key);
            return buildMarkers(identifier, first, previewEnabled);
        }
        if (lookup instanceof IbatisXmlStatementTargets.Unknown) {
            // ③ 暂时查不了（索引重建中 / 候选 PSI 瞬时取不到）：本会话确认过的语句继续显示，
            //    不摘图标、不动缓存（S2 旧报障「图标无故消失」的兜底）。
            return RESOLVED_IBATIS_KEYS.contains(key) ? buildMarkers(identifier, first, previewEnabled) : null;
        }
        // ④ 确定不存在（语句被删/改名、namespace 改了）：收回缓存并摘图标——缓存不得给
        //    不存在的目标续命（2026-11-16 用户报障「改 namespace 图标不消失」）。
        RESOLVED_IBATIS_KEYS.remove(key);
        return null;
    }

    /** 两枚 marker 的构造（三条产出路径共用，避免形态漂移）。 */
    @NotNull
    private List<LineMarkerInfo<?>> buildMarkers(@NotNull PsiIdentifier element,
                                                 @NotNull PsiLiteralExpression first,
                                                 boolean previewEnabled) {
        List<LineMarkerInfo<?>> markers = new ArrayList<>(2);
        // ① 跳转箭头（券 B 现状，锚方法名 identifier）
        markers.add(new LineMarkerInfo<>(
                element, element.getTextRange(), MpvsIcons.MapperMethod,
                elt -> MpSqlVisualizerBundle.message("gutter.ibatisCallNav.tooltip"),
                IbatisToXmlNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.ibatisCallNav.accessible.name")));
        // ② 预览图标（T-IbatisCallPreview，锚首参字面量的**叶子 token**——平台要求 LineMarker 锚叶子，
        // 直接锚 PsiLiteralExpression 会触发 "LineMarker is supposed to be registered for leaf elements"；
        // 与 XML 语句行预览同一枚图标）
        if (previewEnabled) {
            PsiElement literalLeaf = first.getFirstChild() != null ? first.getFirstChild() : first;
            markers.add(new LineMarkerInfo<>(
                    literalLeaf, first.getTextRange(), IbatisXmlGutterLineMarkerProvider.SQL_PREVIEW_ICON,
                    elt -> MpSqlVisualizerBundle.message("gutter.ibatisCallPreview.tooltip"),
                    IbatisCallPreviewHandler.INSTANCE,
                    GutterIconRenderer.Alignment.CENTER,
                    () -> MpSqlVisualizerBundle.message("gutter.ibatisCallPreview.accessible.name")));
        }
        return markers;
    }

    /** 点击处理：重新解析（索引命中）→ 落到语句 id 属性值元素；目标缺失无动作。 */
    private enum IbatisToXmlNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        INSTANCE;

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            if (!(elt.getParent() instanceof PsiReferenceExpression ref)) {
                return;
            }
            if (!(ref.getParent() instanceof PsiMethodCallExpression call)) {
                return;
            }
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null || !IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS.contains(methodName)) {
                return;
            }
            var expressions = call.getArgumentList().getExpressions();
            if (expressions.length == 0 || !(expressions[0] instanceof PsiLiteralExpression first)) {
                return;
            }
            Object rawValue = first.getValue();
            String statementId = rawValue instanceof String s ? s.trim() : null;
            if (statementId == null || statementId.isEmpty()) {
                return;
            }
            Project project = elt.getProject();
            XmlAttributeValue target;
            try {
                target = ReadAction.compute(() -> {
                    if (DumbService.isDumb(project)) {
                        return null;
                    }
                    List<XmlAttributeValue> anchors = IbatisXmlStatementTargets.resolveStatementAnchors(
                            project, statementId, IbatisCallTargets.ownerNamespace(call));
                    return anchors.isEmpty() ? null : anchors.get(0);
                });
            } catch (Throwable t) {
                target = null;
            }
            if (target == null) {
                return;
            }
            // 落点 = 语句 id 属性值元素（OpenFileDescriptor 与 MapperJavaToXml 同款；XmlAttributeValue
            // 不实现 Navigatable，无 navigate(true) 直调形态）
            if (target.getContainingFile() == null || target.getContainingFile().getVirtualFile() == null
                    || !target.getContainingFile().getVirtualFile().isValid()) {
                return;
            }
            new OpenFileDescriptor(project, target.getContainingFile().getVirtualFile(),
                    target.getTextOffset()).navigate(true);
        }
    }

    /**
     * 预览点击处理（T-IbatisCallPreview）：EDT 只读字面量值与类限定名（O(1)），
     * 目标解析与文件文本读取全部落在 previewAsync 的 ReadAction 内。
     */
    private enum IbatisCallPreviewHandler implements GutterIconNavigationHandler<PsiElement> {
        INSTANCE;

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            // 锚是字面量的叶子 token，宿主是 PsiLiteralExpression
            PsiLiteralExpression literal = elt instanceof PsiLiteralExpression direct
                    ? direct : elt.getParent() instanceof PsiLiteralExpression parent ? parent : null;
            if (literal == null) {
                return;
            }
            Object rawValue = literal.getValue();
            String statementId = rawValue instanceof String s ? s.trim() : null;
            if (statementId == null || statementId.isEmpty()) {
                return;
            }
            // 与 gutter/跳转同口径：所属类限定名从调用表达式起爬（见 createMarkers 同名注释）
            IbatisCallTarget callTarget = IbatisCallTargets.resolve(literal);
            String ownerNamespace = callTarget != null ? callTarget.ownerNamespace() : null;
            Project project = elt.getProject();
            SqlPreviewService.previewAsync(project,
                    () -> IbatisStatementPreview.previewResolved(project, statementId, ownerNamespace),
                    result -> {
                        SqlPreviewService.showResult(project, result);
                        return kotlin.Unit.INSTANCE;
                    });
        }
    }
}
