package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
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
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.xml.util.XmlTagUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * T-Ibatis2Gap 券 B：iBatis {@code <sqlMap>} 语句行 → Java 侧 {@code SqlMapExecutor} 调用点的跳转箭头
 * （观感对齐 MyBatis 的 MapperXmlToJavaLineMarkerProvider，图标复用 {@link MpvsIcons#MapperStatement}）。
 *
 * <h2>锚与门（热路径形态）</h2>
 *
 * <p>锚 = 语句标签的<b>起始名 token</b>（getLineMarkerInfo 永远返回 null，全部走慢趟
 * collectSlowLineMarkers——与 MyBatis 那对同形）。判定全部 <b>O(1) 语法级</b>：</p>
 *
 * <ol>
 * <li>根标签名 == sqlMap（{@link IbatisXmlStatementTargets#isSqlMapFile}，与券 F 的 L8 handler 同门）；</li>
 * <li>标签名 ∈ {@link IbatisXmlGutterLineMarkerProvider#STATEMENT_TAGS}（券 A 的单一事实源）；</li>
 * <li>该 token 是 {@code XmlTagUtil.getStartTagNameElement}（一语句一锚，闭合名不重复出图标）；</li>
 * <li>语句标签是 {@code <sqlMap>} 直接子级且 {@code id} 非空。</li>
 * </ol>
 *
 * <h2>目标存在性（T-IbatisXmlArrowCallerGate，2026-11-16 口径变更）</h2>
 *
 * <p>原口径＝<b>无条件出箭头</b>，目标是否存在留到点击时查（点击找不到就无动作）。用户 2026-11-16
 * 报障：把 XML 语句 {@code id} 改名（selectAll → selectAll02）后，<b>语句行仍挂着箭头</b>、点下去
 * 没反应——即「点开必空」的假图标，与 MyBatis 侧（MapperXmlToJavaLineMarkerProvider 必须先解析到
 * 接口方法才画）和本插件自己的「同生共死」原则都不一致，故本刀改成<b>按调用点存在性出箭头</b>。</p>
 *
 * <p>代价与护栏（本次是有意的取舍，不是回归）：底层是词索引查询
 * （{@link IbatisJavaCallSites#lookupCallSite}），<b>常见词命中面大</b>（selectAll 这类 id 在 Java
 * 侧也是常见方法名）；因此按 F1/P2.1 同款「命中入缓存 / 确认缺失收回缓存」处理——同一语句只在
 * 判定变化时付费，稳态每趟零查询；单词条非法标识符形态（含 {@code .}/{@code -} 等）判不了 →
 * {@link IbatisJavaCallSites.CallLookup} 的 UNKNOWN，保留既有缓存判定，不因此隐藏箭头；
 * 回退通道：Registry {@code sqlvis.gutter.ibatisXmlArrowRequiresCaller=false} 回到「无条件出箭头」
 * 旧行为（真机 A/B 用；下一版删除）。</p>
 *
 * <p>仍<b>禁止</b>在本 provider 内直接写项目级搜索 API（GlobalSearchScope/PsiSearchHelper/
 * PsiTreeUtil 等）——判定只能经 {@link IbatisJavaCallSites} 这一个收口（IbatisGutterHotPathTest
 * 的禁用词表原样保留，扫面含本文件的 createMarker）。</p>
 *
 * <p>与 MyBatis 侧的分工：MapperXmlToJavaLineMarkerProvider 只认根标签 mapper（isMapperFile 门控），
 * 本 provider 只认 sqlMap——两套根标签天然互斥，同一行不会双箭头（用例钉住）。</p>
 *
 * <h2>让位门（T-Ibatis2Gap 收尾：与 MyBatis 那对<b>刻意解耦</b>，不让位）</h2>
 *
 * <p>竞品 MyBatisCodeHelperPro <b>没有 iBatis 能力</b>（其发行包解包扫描 iBatis 命中为 0），sqlMap
 * 文件与 SqlMapExecutor 调用行上不存在图标冲突——竞品在位时本 provider <b>照常产出箭头</b>，
 * 不读 {@code mapperNavYieldToCodeHelper}、不走 {@code MyBatisCompetitorGate}。共用让位门只会把
 * 竞品根本不画的 iBatis 箭头白白藏掉（本插件典型用户 IDE 里就装着 CodeHelperPro，等于功能白做）。
 * 反向锚：IbatisNavigationLineMarkerProvidersTest 的「竞品在位」用例——恢复让位必恰它变红；
 * MyBatis 那对（MapperXmlToJavaLineMarkerProvider）的让位行为不变，由其自身守卫与对照用例钉住。</p>
 *
 * <h2>预览图标共存</h2>
 *
 * <p>iBatis 语句行同时有券 A 的预览图标（IbatisXmlGutterLineMarkerProvider，快路径锚）与本 provider
 * 的跳转箭头（慢趟锚）——两者<b>共存是预期</b>（MyBatis 文件同样预览 + 跳转并存）。</p>
 */
public class IbatisXmlToJavaLineMarkerProvider implements LineMarkerProvider {

    /** 回退通道：false＝回到「无条件出箭头」旧行为（真机 A/B 用，下一版删除）。 */
    public static final String REQUIRES_CALLER_REGISTRY_KEY = "sqlvis.gutter.ibatisXmlArrowRequiresCaller";

    /**
     * 本会话确认过「Java 侧存在调用点」的语句键（{@code namespace|statementId}）。
     * 与 IbatisJavaToXmlLineMarkerProvider 的正缓存同口径：命中入缓存、<b>确认缺失即收回</b>
     * （P2.1 教训：只进不出的缓存会给已删除的目标续命）。
     */
    public static final Set<String> RESOLVED_IBATIS_CALL_KEYS = ConcurrentHashMap.newKeySet();

    /** 测试缝：dumb 判定（生产＝DumbService.isDumb）。 */
    public static volatile Function<Project, Boolean> isDumbProvider = project -> {
        try {
            return DumbService.isDumb(project);
        } catch (Throwable t) {
            return false;
        }
    };

    /** 测试缝：回退开关（生产＝{@code Registry.is(key, true)}；键未登记时直接返回默认值）。 */
    public static volatile BooleanSupplier requiresCallerProvider = () -> {
        try {
            return Registry.is(REQUIRES_CALLER_REGISTRY_KEY, true);
        } catch (Throwable t) {
            return true;
        }
    };

    /** Kotlin object 语义兼容实例。 */
    public static final IbatisXmlToJavaLineMarkerProvider INSTANCE = new IbatisXmlToJavaLineMarkerProvider();

    /** 测试缝：清空调用键缓存与词索引替换缝。 */
    public static void resetCallKeysCacheForTest() {
        RESOLVED_IBATIS_CALL_KEYS.clear();
        IbatisJavaCallSites.lookupOverrideForTest = null;
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
        // T-Ibatis2Gap 收尾：竞品无 iBatis 能力、无图标冲突，刻意不让位（理由见类 KDoc；
        // 恢复让位的反证锚 = IbatisNavigationLineMarkerProvidersTest 的竞品在位用例）
        for (PsiElement element : elements) {
            LineMarkerInfo<?> marker = createMarker(element);
            if (marker != null) {
                result.add(marker);
            }
        }
    }

    @Nullable
    private LineMarkerInfo<?> createMarker(@NotNull PsiElement element) {
        if (!(element instanceof XmlToken token)) {
            return null;
        }
        if (token.getTokenType() != XmlTokenType.XML_NAME) {
            return null;
        }
        XmlTag tag = token.getParent() instanceof XmlTag parent ? parent : null;
        if (tag == null || !IbatisXmlGutterLineMarkerProvider.STATEMENT_TAGS.contains(tag.getName())) {
            return null;
        }
        if (XmlTagUtil.getStartTagNameElement(tag) != token) {
            return null;
        }
        var file = tag.getContainingFile();
        if (!IbatisXmlStatementTargets.isSqlMapFile(file)) {
            return null;
        }
        if (tag.getParentTag() == null
                || !IbatisXmlStatementTargets.SQL_MAP_ROOT_TAG.equals(tag.getParentTag().getName())) {
            return null;
        }
        String rawId = tag.getAttributeValue("id");
        String statementId = rawId != null ? rawId.trim() : null;
        if (statementId == null || statementId.isEmpty()) {
            return null;
        }
        // 回退通道：旧行为＝无条件出箭头（目标存在性留到点击时查）
        if (!requiresCallerProvider.getAsBoolean()) {
            return buildMarker(element);
        }
        Project project = element.getProject();
        // dumb 窗：词索引不可查，乐观产出（不入缓存）——与 Java→XML 侧 F1-b 同口径
        if (isDumbProvider.apply(project)) {
            return buildMarker(element);
        }
        String namespace = IbatisXmlStatementTargets.rootNamespace(file);
        String key = (namespace != null ? namespace : "") + "|" + statementId;
        IbatisJavaCallSites.CallLookup lookup =
                IbatisJavaCallSites.lookupCallSite(project, namespace, statementId);
        if (lookup instanceof IbatisJavaCallSites.Found found) {
            RESOLVED_IBATIS_CALL_KEYS.add(key);
            return buildMarker(element);
        }
        // 暂时判不了（词形态不支持等）：保留已确认过的箭头，不新画
        if (lookup instanceof IbatisJavaCallSites.CallUnknown) {
            return RESOLVED_IBATIS_CALL_KEYS.contains(key) ? buildMarker(element) : null;
        }
        // 确定没有调用点（语句改名/删了调用）：收回缓存并摘箭头——不制造「点开必空」的假图标
        RESOLVED_IBATIS_CALL_KEYS.remove(key);
        return null;
    }

    @NotNull
    private LineMarkerInfo<?> buildMarker(@NotNull PsiElement element) {
        return new LineMarkerInfo<>(
                element, element.getTextRange(), MpvsIcons.MapperStatement,
                elt -> MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.tooltip"),
                IbatisToJavaNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.accessible.name"));
    }

    /** 点击处理：EDT 内联 ReadAction 里走词索引找调用点（找不到 = 无动作，不弹提示不报错）。 */
    private enum IbatisToJavaNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        INSTANCE;

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            if (!(elt.getParent() instanceof XmlTag tag)) {
                return;
            }
            Project project = elt.getProject();
            String rawId = tag.getAttributeValue("id");
            String statementId = rawId != null ? rawId.trim() : null;
            if (statementId == null || statementId.isEmpty()) {
                return;
            }
            String namespace = IbatisXmlStatementTargets.rootNamespace(tag.getContainingFile());
            PsiLiteralExpression target;
            try {
                target = ReadAction.compute(() ->
                        IbatisJavaCallSites.findFirstCallLiteral(project, namespace, statementId));
            } catch (Throwable t) {
                target = null;
            }
            if (target == null) {
                return;
            }
            // 落点 = 调用首参字面量（OpenFileDescriptor 与 MapperJavaToXml 同款；PsiLiteralExpression
            // 不实现 Navigatable，无 navigate(true) 直调形态）
            if (target.getContainingFile() == null || target.getContainingFile().getVirtualFile() == null
                    || !target.getContainingFile().getVirtualFile().isValid()) {
                return;
            }
            new OpenFileDescriptor(project, target.getContainingFile().getVirtualFile(),
                    target.getTextOffset()).navigate(true);
        }
    }
}
