// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisJavaToXmlLineMarkerProvider.kt 移植
package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.IbatisStatementPreview;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.xml.XmlAttributeValue;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * iBatis Java DAO 调用行的 gutter 图标（两枚）。
 *
 * <h2>券 B（T-Ibatis2Gap）：跳转箭头</h2>
 *
 * <p>锚 = 调用表达式的<b>方法名 identifier</b>（{@code queryForObject} / {@code insert} / … 的名字 leaf），
 * 图标 {@link MpvsIcons#MapperMethod}，点击落 iBatis {@code <sqlMap>} 语句的 {@code id} 属性值元素
 * （与 Ctrl+B 的 L8 handler 同落点）。</p>
 *
 * <h2>T-IbatisCallPreview：预览图标</h2>
 *
 * <p>锚 = <b>首参 String 字面量</b>，图标 {@code /icons/sqlGutter.svg}（与 XML 语句行预览同一枚图标），
 * 点击直接渲染该语句的 SQL——参数/条件/{@code <iterate>}/{@code <selectKey>} 与 XML 语句行预览逐字节同源
 * （装配唯一入口 {@link IbatisStatementPreview#previewTag}）。两枚图标<b>同生共死</b>（共用同一份
 * {@code resolveStatementAnchors} 结果）：目标不存在时都不出，不制造「点开必空」的假图标。</p>
 *
 * <p>为什么两枚由同一个 provider 产出而不是新开一个：两枚 marker 需要同一份语句解析结果，
 * 同 provider 只做一次项目级语句扫描；新开 provider 会让每个调用点每趟多扫一遍全项目 sqlMap
 * 文件（{@code IbatisXmlStatementTargets} 的结果级 memo 是单槽，两个 provider 会互踢）。</p>
 *
 * <h2>锚与门（两枚共用）</h2>
 *
 * <p>门控 <b>O(1) 语法级</b>，判定口径照 L8 handler（{@code ui/nav/IbatisDaoStatementGotoDeclarationHandler}）：
 * 方法名 ∈ {@link IbatisXmlStatementTargets#STATEMENT_ENTRY_METHODS}（{@code javap} 实测清单的单一事实源）
 * 且<b>首参是 String 字面量</b>——不做接收者类型解析（真实工程 {@code SqlMapClient} 常经 Spring 代理
 * 注入、类型不可解析）。</p>
 *
 * <p>目标存在性在慢趟收口：{@link IbatisXmlStatementTargets#resolveStatementAnchors}（{@code ns.id} /
 * 裸 id，TTL + 结果级 memo 缓存）为空即两枚都不出——普通 Java 类上的同名调用因此零影响
 * （负例判据：{@code <mapper>} 文件与普通 Java 文件零新图标）。误报面 = 「首参字面量恰好等于某条
 * sqlMap 语句 id 的任意方法名调用」，与 L8 handler 的接管口径一致，由目标存在性兜底。</p>
 *
 * <h2>开关</h2>
 *
 * <p>总闸 {@code showGutterIcon}、分组 {@code mapperXml} 两枚同灭；{@code ibatisXmlEnabled} <b>只灭预览</b>、
 * 箭头保留（该开关的文案是「预览 iBatis 语句」，跳转不是预览）。</p>
 *
 * <h2>与 MyBatis 侧的互斥</h2>
 *
 * <p>MyBatis 的 {@code MapperJavaToXmlLineMarkerProvider} 锚<b>接口方法声明行</b>（{@code containingClass.isInterface}
 * 门控）；本 provider 锚<b>调用行</b>——两种锚形态天然不相交；xml 侧再由根标签 {@code sqlMap} vs
 * {@code mapper} 互斥。两套互不重复注册同一锚点（用例钉住）。</p>
 *
 * <h2>让位门（T-Ibatis2Gap 收尾：与 MyBatis 那对<b>刻意解耦</b>，不让位）</h2>
 *
 * <p>竞品 MyBatisCodeHelperPro <b>没有 iBatis 能力</b>（其发行包解包扫描 iBatis 命中为 0），{@code SqlMapExecutor}
 * 调用行上不存在图标冲突——竞品在位时本 provider <b>照常产出</b>，不读
 * {@code mapperNavYieldToCodeHelper}、不走 {@link MyBatisCompetitorGate}。共用让位门只会把竞品根本不画的
 * iBatis 图标白白藏掉（本插件典型用户 IDE 里就装着 CodeHelperPro，等于功能白做）。
 * 反向锚：{@code IbatisNavigationLineMarkerProvidersTest} 的「竞品在位」用例。</p>
 */
public class IbatisJavaToXmlLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(
        @NotNull List<? extends PsiElement> elements,
        @NotNull Collection<? super LineMarkerInfo<?>> result) {
        // 券01（T-PerfSweep28-01）同款门形：方法头单次取 state，总闸与组显隐共用
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) {
            return;
        }
        if (!GutterGroups.enabledFor(getClass(), gutterState)) {
            return;
        }
        // T-IbatisCallPreview 拍板 D-4：预览图标受 iBatis 预览开关约束，跳转箭头不受
        boolean previewEnabled = gutterState == null || gutterState.getIbatisXmlEnabled();
        // T-Ibatis2Gap 收尾：竞品无 iBatis 能力、无图标冲突，刻意不让位（理由见类 KDoc；
        // 恢复让位的反证锚 = IbatisNavigationLineMarkerProvidersTest 的竞品在位用例）
        for (PsiElement element : elements) {
            List<LineMarkerInfo<PsiElement>> markers = createMarkers(element, previewEnabled);
            if (markers != null) {
                result.addAll(markers);
            }
        }
    }

    /**
     * 一个调用点 → 两枚 marker（跳转 + 预览）。返回 null 表示该元素不是 iBatis 调用点
     * 或目标语句不存在（两枚同生共死）。
     */
    @Nullable
    private static List<LineMarkerInfo<PsiElement>> createMarkers(@NotNull PsiElement element, boolean previewEnabled) {
        if (!(element instanceof PsiIdentifier)) {
            return null;
        }
        PsiReferenceExpression ref = element.getParent() instanceof PsiReferenceExpression parent ? parent : null;
        if (ref == null || ref.getReferenceNameElement() != element) {
            return null;
        }
        PsiMethodCallExpression call = ref.getParent() instanceof PsiMethodCallExpression parent ? parent : null;
        if (call == null) {
            return null;
        }
        String callName = call.getMethodExpression().getReferenceName();
        if (callName == null || !IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS.contains(callName)) {
            return null;
        }
        com.intellij.psi.PsiExpression[] expressions = call.getArgumentList().getExpressions();
        if (expressions.length == 0 || !(expressions[0] instanceof PsiLiteralExpression first)) {
            return null;
        }
        Object literalValue = first.getValue();
        String statementId = literalValue instanceof String s ? s.trim() : "";
        if (statementId.isEmpty()) {
            return null;
        }
        Project project = element.getProject();
        if (DumbService.isDumb(project)) {
            return null;
        }
        List<XmlAttributeValue> anchors = IbatisXmlStatementTargets.resolveStatementAnchors(
            project,
            statementId,
            IbatisCallTargets.ownerNamespace(element),
        );
        if (anchors.isEmpty()) {
            return null;
        }
        List<LineMarkerInfo<PsiElement>> markers = new ArrayList<>(2);
        // ① 跳转箭头（券 B 现状，锚方法名 identifier）
        markers.add(new LineMarkerInfo<>(
            element, element.getTextRange(), MpvsIcons.MapperMethod,
            elt -> MpSqlVisualizerBundle.message("gutter.ibatisCallNav.tooltip"),
            IbatisToXmlNavigationHandler.INSTANCE,
            GutterIconRenderer.Alignment.CENTER,
            () -> MpSqlVisualizerBundle.message("gutter.ibatisCallNav.accessible.name"),
        ));
        // ② 预览图标（T-IbatisCallPreview，锚首参字面量的叶子 token——平台要求 LineMarker 锚叶子，
        // 直接锚 PsiLiteralExpression 会触发 "LineMarker is supposed to be registered for leaf elements"；
        // 与 XML 语句行预览同一枚图标）
        if (previewEnabled) {
            PsiElement literalLeaf = first.getFirstChild() != null ? first.getFirstChild() : first;
            markers.add(new LineMarkerInfo<>(
                literalLeaf, first.getTextRange(), IbatisXmlGutterLineMarkerProvider.SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.ibatisCallPreview.tooltip"),
                IbatisCallPreviewHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.ibatisCallPreview.accessible.name"),
            ));
        }
        return markers;
    }

    /** 点击处理：重新解析（memo 命中）→ 落到语句 {@code id} 属性值元素；目标缺失无动作。 */
    private static final class IbatisToXmlNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final IbatisToXmlNavigationHandler INSTANCE = new IbatisToXmlNavigationHandler();

        /**
         * T-M52 同款注记：master 侧 {@code @Suppress("DEPRECATION")}——{@code ReadAction.compute(ThrowableComputable)}
         * 在 262 标 deprecated；243 编译目标仅此重载可用。Java 侧沿用该重载。
         */
        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            PsiReferenceExpression ref = elt.getParent() instanceof PsiReferenceExpression parent ? parent : null;
            if (ref == null) {
                return;
            }
            PsiMethodCallExpression call = ref.getParent() instanceof PsiMethodCallExpression parent ? parent : null;
            if (call == null) {
                return;
            }
            String callName = call.getMethodExpression().getReferenceName();
            if (callName == null || !IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS.contains(callName)) {
                return;
            }
            com.intellij.psi.PsiExpression[] expressions = call.getArgumentList().getExpressions();
            if (expressions.length == 0 || !(expressions[0] instanceof PsiLiteralExpression first)) {
                return;
            }
            Object literalValue = first.getValue();
            String statementId = literalValue instanceof String s ? s.trim() : "";
            if (statementId.isEmpty()) {
                return;
            }
            Project project = elt.getProject();
            XmlAttributeValue target = null;
            try {
                // master 侧 runCatching 降级 → try/catch 等义
                target = ReadAction.compute(() -> {
                    if (DumbService.isDumb(project)) {
                        return null;
                    }
                    List<XmlAttributeValue> anchors = IbatisXmlStatementTargets.resolveStatementAnchors(
                        project,
                        statementId,
                        IbatisCallTargets.ownerNamespace(elt),
                    );
                    return anchors.isEmpty() ? null : anchors.get(0);
                });
            } catch (Throwable ex) {
                return;
            }
            if (target == null) {
                return;
            }
            // 落点 = 语句 id 属性值元素（OpenFileDescriptor 与 MapperJavaToXml 同款；XmlAttributeValue
            // 不实现 Navigatable，无 navigate(true) 直调形态）
            VirtualFile file = target.getContainingFile() != null ? target.getContainingFile().getVirtualFile() : null;
            if (file == null || !file.isValid()) {
                return;
            }
            new OpenFileDescriptor(project, file, target.getTextOffset()).navigate(true);
        }
    }

    /**
     * 预览点击处理（T-IbatisCallPreview）：EDT 只读字面量值与类限定名（O(1)），
     * 目标解析与文件文本读取全部落在 {@link SqlPreviewService#previewAsync} 的 ReadAction 内。
     */
    private static final class IbatisCallPreviewHandler implements GutterIconNavigationHandler<PsiElement> {
        static final IbatisCallPreviewHandler INSTANCE = new IbatisCallPreviewHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            // 锚是字面量的叶子 token，宿主是 PsiLiteralExpression
            PsiLiteralExpression literal = elt instanceof PsiLiteralExpression lit
                ? lit
                : elt.getParent() instanceof PsiLiteralExpression litParent ? litParent : null;
            if (literal == null) {
                return;
            }
            Object literalValue = literal.getValue();
            String statementId = literalValue instanceof String s ? s.trim() : "";
            if (statementId.isEmpty()) {
                return;
            }
            String ownerNamespace = IbatisCallTargets.ownerNamespace(elt);
            Project project = elt.getProject();
            SqlPreviewService.previewAsync(
                project,
                () -> IbatisStatementPreview.previewResolved(project, statementId, ownerNamespace),
                result -> {
                    SqlPreviewService.showResult(project, result);
                    return kotlin.Unit.INSTANCE;
                },
            );
        }
    }
}
