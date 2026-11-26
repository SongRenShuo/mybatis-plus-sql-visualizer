// 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt
// （F4 48a8f191：Mapper 双向跳转——XML→Java 方；T-SyncK2JMaster181 合并 T-MapperNavNamespaceJump 增量）新译。
package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets;
import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.xml.util.XmlTagUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Mapper XML statement 与 Mapper 接口方法之间的双向 gutter 导航（XML → Java 方）。
 *
 * <p>语句标签（{@code select/insert/update/delete}，{@link XmlSqlGutterLineMarkerProvider#STATEMENT_TAGS}
 * 同口径）行显示配对跳转标记，点击跳到 namespace 对应接口的 {@code id} 方法。
 * slow pass（{@link #collectSlowLineMarkers}）——含接口方法 resolve，不进快通道。</p>
 *
 * <p>T-MapperNavNamespaceJump（2026-11-24）起 {@code <mapper namespace="…">} <b>根标签</b>另出一枚
 * 「跳 mapper 接口类」图标：竞品（MPJ {@code view.I} / MyBatisX / MyBatisPlus）都有这一格，接口里
 * 没有方法时也能跳；本插件此前缺，用户感知为「mapper 没有方法就没法跳转」。</p>
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="XML" .../>}。</p>
 */
public class MapperXmlToJavaLineMarkerProvider implements LineMarkerProvider {

    // F1（T-GutterFirstOpenDelay，治 S2「MP 图标消失 3s 重现」）：见 master KDoc 的 ①②③④ 四段判定。
    /** F1-a 会话级正缓存：本会话解析成功过的语句键（形态见 {@link #cacheKey}）。无失效，取舍见类头注释。 */
    public static final Set<String> resolvedStatementKeys = ConcurrentHashMap.newKeySet();

    /**
     * T-MapperNavNamespaceJump：根标签 → 接口类的会话级正缓存（键见 {@link #classCacheKey}）。
     * 语义与 {@link #resolvedStatementKeys} 同口径：只在「确认真没有」时缺席，不在「还没查到」时缺席。
     */
    public static final Set<String> resolvedClassKeys = ConcurrentHashMap.newKeySet();

    /**
     * T-PerfSweep32 券02：缓存键带 {@code project.locationHash} 前缀——resolvedStatementKeys 是
     * 应用级容器、项目关闭不清理，键不含项目身份时两工程同名 namespace+statement 会跨项目串号
     * （A 工程确认过，B 工程直接信）。与 {@link IbatisJavaToXmlLineMarkerProvider#cacheKey}
     * （T-PerfSweep31 券04）同口径；locationHash 会话内稳定，作键仅限会话级去重语义。
     */
    static String cacheKey(@NotNull Project project, @NotNull String namespace, @NotNull String statementId) {
        return project.getLocationHash() + "|" + namespace + "|" + statementId;
    }

    /** T-MapperNavNamespaceJump：根标签类缓存键（同工程同 namespace 复用；{@code #mapper} 与语句 id 不会撞）。 */
    static String classCacheKey(@NotNull Project project, @NotNull String namespace) {
        return project.getLocationHash() + "|" + namespace + "|#mapper";
    }

    /** 测试缝：dumb 判定（生产＝DumbService.isDumb）。双侧共用。 */
    public static volatile Function<Project, Boolean> isDumbProvider = project -> {
        try {
            return DumbService.isDumb(project);
        } catch (Throwable t) {
            return false;
        }
    };

    /** 测试缝：清空正缓存并复位三态定位缝，防跨用例泄漏。 */
    public static void resetResolvedCacheForTest() {
        resolvedStatementKeys.clear();
        resolvedClassKeys.clear();
        MybatisXmlTargets.methodLookupOverrideForTest = null;
        cn.song.mybatisplus.visualizer.log.MapperXmlLocator.lookupOverrideForTest = null;
    }

    @Override
    @Nullable
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
            @NotNull Collection<? super LineMarkerInfo<?>> result) {
        // 券01（T-PerfSweep28-01，R28-2）：组显隐门前置——本 Provider 此前未接任何组闸，组关时
        // 语句标签的接口解析重活照付。批级单次取 state，总闸与组显隐共用（CriteriaQueryGutter 门形）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return;
        // 第三道门（T-MapperNavCoexistYield / T-MapperNavYieldJavaSideKeep）：**仅 XML 语句行**的共存
        // 让位——同类插件启用时它自己也会给同一行出跳转箭头，本插件让位。Java 侧（Mapper 接口方法行）
        // 不再让位：对方 Java 箭头与我方同款，让位会让该行只剩对方一枚、用户无法分辨（见
        // MapperJavaToXmlLineMarkerProvider 的 KDoc）。与 XmlSynHiGate 的注入区间互补是两件事：
        // 那边判「对方是否真给这段文本上色」，这边判「对方是否在位」。
        // 探测结论已缓存，此处为 O(1) 布尔，不引入按元素开销。
        if (gutterState != null
                && MyBatisCompetitorGate.shouldYield(
                        gutterState.getMapperNavYieldToCodeHelper(),
                        MyBatisCompetitorGate.isPeerPluginActive())) {
            return;
        }
        for (PsiElement element : elements) {
            LineMarkerInfo<PsiElement> marker = createMarker(element);
            if (marker != null) result.add(marker);
        }
    }

    @Nullable
    private static LineMarkerInfo<PsiElement> createMarker(@NotNull PsiElement element) {
        if (!(element instanceof XmlToken token)) return null;
        if (token.getTokenType() != XmlTokenType.XML_NAME) return null;
        XmlTag tag = token.getParent() instanceof XmlTag ? (XmlTag) token.getParent() : null;
        if (tag == null) return null;
        // T-MapperNavNamespaceJump：`<mapper namespace="…">` 根标签 → 接口类。
        // 竞品（MPJ view.I / MyBatisX / MyBatisPlus）都在这里给「跳 mapper class」，接口里没有方法也能跳；
        // 本插件此前缺这一格，用户感知为「mapper 没有方法就没法跳转」。锚点＝根标签名 token（与语句锚同形）。
        if ("mapper".equals(tag.getName()) && tag.getParentTag() == null
                && XmlTagUtil.getStartTagNameElement(tag) == token
                && MybatisXmlTargets.isMapperFile(tag.getContainingFile())) {
            return createRootMapperMarker(element, tag);
        }
        if (!XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(tag.getName())) return null;
        if (XmlTagUtil.getStartTagNameElement(tag) != token) return null;
        // mapper 根的直接子语句标签（嵌套 <include> 内的不算）；非 mapper XML 不误显
        if (!MybatisXmlTargets.isMapperFile(tag.getContainingFile())
                || (tag.getParentTag() != null && tag.getParentTag().getParentTag() != null)) return null;
        if (tag.getParentTag() == null || !"mapper".equals(tag.getParentTag().getName())) return null;
        String namespace = MybatisXmlTargets.rootNamespace(tag.getContainingFile());
        if (namespace == null) return null;
        String id = tag.getAttributeValue("id");
        if (id == null || id.isBlank()) return null;
        // T-MapperGutterEvict P2.1：dumb 门前置 → 三态定位 → Found 入缓存 / Unknown 留缓存命中 /
        // Absent 收回缓存。禁吞 ProcessCanceledException。
        Project project = element.getProject();
        String key = cacheKey(project, namespace, id);
        if (isDumbProvider.apply(project)) return buildStatementMarker(element);
        MybatisXmlTargets.MethodLookup lookup = MybatisXmlTargets.lookupInterfaceMethod(project, namespace, id);
        if (lookup instanceof MybatisXmlTargets.Found) {
            resolvedStatementKeys.add(key);
            return buildStatementMarker(element);
        }
        if (lookup instanceof MybatisXmlTargets.Unknown) {
            return resolvedStatementKeys.contains(key) ? buildStatementMarker(element) : null;
        }
        // Absent：确定不存在 → 收回正缓存、摘图标
        resolvedStatementKeys.remove(key);
        return null;
    }

    @NotNull
    private static LineMarkerInfo<PsiElement> buildStatementMarker(@NotNull PsiElement element) {
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                MpvsIcons.MapperStatement,
                elt -> MpSqlVisualizerBundle.message("gutter.mapperStatement.tooltip"),
                (mouseEvent, elt) -> {
                    // 跳转时按当前元素重新解析（不缓存 marker 创建时的 PSI——文件可能已被编辑）
                    XmlTag currentTag = elt.getParent() instanceof XmlTag ? (XmlTag) elt.getParent() : null;
                    if (currentTag == null) return;
                    String currentNamespace = MybatisXmlTargets.rootNamespace(currentTag.getContainingFile());
                    if (currentNamespace == null) return;
                    String currentId = currentTag.getAttributeValue("id");
                    if (currentId == null) return;
                    PsiMethod target = MybatisXmlTargets.resolveInterfaceMethod(
                            elt.getProject(), currentNamespace, currentId);
                    if (target != null) target.navigate(true);
                },
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.mapperStatement.accessible.name")
        );
    }

    /**
     * 根标签 → 接口类图标的五段式（与 {@link #createMarker} 语句侧同不变式）：
     * ① dumb 乐观产出（不查索引、不入缓存）② 确定存在 → 入缓存并产出
     * ③ 索引临界（{@link IndexNotReadyException}）→ 缓存命中仍产出 ④ 确定不存在 → 收回缓存并摘图标
     * ⑤ 其余不产出。namespace 为空 → 不产出（不出死图标）。
     */
    @Nullable
    private static LineMarkerInfo<PsiElement> createRootMapperMarker(@NotNull PsiElement element, @NotNull XmlTag tag) {
        String rawNamespace = tag.getAttributeValue("namespace");
        if (rawNamespace == null || rawNamespace.isBlank()) return null;
        String namespace = rawNamespace;
        Project project = element.getProject();
        String key = classCacheKey(project, namespace);
        if (isDumbProvider.apply(project)) return buildRootMapperMarker(element);
        PsiClass cls;
        try {
            cls = JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project));
        } catch (IndexNotReadyException e) {
            return resolvedClassKeys.contains(key) ? buildRootMapperMarker(element) : null;
        }
        if (cls != null) {
            resolvedClassKeys.add(key);
            return buildRootMapperMarker(element);
        }
        resolvedClassKeys.remove(key);
        return null;
    }

    @NotNull
    private static LineMarkerInfo<PsiElement> buildRootMapperMarker(@NotNull PsiElement element) {
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                MpvsIcons.MapperStatement,
                elt -> MpSqlVisualizerBundle.message("gutter.mapperClass.tooltip"),
                (mouseEvent, elt) -> {
                    XmlTag currentTag = elt.getParent() instanceof XmlTag ? (XmlTag) elt.getParent() : null;
                    if (currentTag == null) return;
                    PsiClass target = MapperNavigationTargets.mapperClassOfRootTag(currentTag);
                    if (target != null) target.navigate(true);
                },
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.mapperClass.accessible.name")
        );
    }
}
