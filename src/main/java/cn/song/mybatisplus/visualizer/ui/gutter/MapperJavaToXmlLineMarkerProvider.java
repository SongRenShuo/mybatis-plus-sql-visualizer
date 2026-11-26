// 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt
// （F4 48a8f191：Mapper 双向跳转——Java→XML 方；T-SyncK2JMaster181 合并 T-MapperClassJump /
//  T-MapperPreviewOrder / 两侧让位口径增量）新译。
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
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mapper 接口方法跳转到 namespace 对应 XML 中的同名 statement（Java → XML 方）。
 *
 * <p>锚定在方法名 {@link PsiIdentifier} 上（LineMarker 框架只传递 leaf 元素）；
 * {@code namespace.methodName} 在 mapper XML 中存在对应语句时才显示标记，点击跳到语句标签起始行。
 * slow pass（{@link #collectSlowLineMarkers}）——含 XML 定位（文件扫描），不进快通道。</p>
 *
 * <h2>T-MapperClassJump（2026-11-25 用户反馈「mapper.java 少一个类跳转图标」）</h2>
 * <p>Mapper 接口<b>类名</b> → 对应 XML 根标签。对齐竞品：MPJ {@code view.A} 的 UClass 分支、
 * MyBatisX 的 {@code PsiClassAbstractElementFilter} 都在接口声明行出这一枚；我方此前只锚方法名。</p>
 *
 * <h2>T-MapperPreviewOrder（2026-11-26）</h2>
 * <p>方法行的 SQL <b>预览</b>图标已拆到 {@link MapperMethodPreviewLineMarkerProvider}（另一 provider、
 * plugin.xml 注册在前），本类只产跳转箭头——同行左右序因此确定（预览恒左），不再随 daemon
 * 首次/重算翻转。</p>
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。</p>
 */
public class MapperJavaToXmlLineMarkerProvider implements LineMarkerProvider {

    /**
     * T-MapperClassJump：接口类名 → XML 文件的会话级正缓存。
     * 语义与语句侧同不变式：dumb 乐观产出、确定存在入缓存、暂时查不到且缓存命中仍产出、
     * 确定不存在收回缓存并摘图标（不出点开必空的死图标）。
     */
    public static final Set<String> resolvedMapperClassKeys = ConcurrentHashMap.newKeySet();

    static String classToXmlKey(@NotNull Project project, @NotNull String namespace) {
        return project.getLocationHash() + "|" + namespace + "|#class";
    }

    /** 测试缝：清空类名→XML 的会话级正缓存。 */
    public static void resetClassCacheForTest() {
        resolvedMapperClassKeys.clear();
    }

    @Override
    @Nullable
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
            @NotNull Collection<? super LineMarkerInfo<?>> result) {
        // 券01（T-PerfSweep28-01，R28-2）：组显隐门前置（与 XML 侧同门形）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return;
        // 第三道门（T-MapperNavCoexistYield，2026-11-25 两侧让位口径）：XML 语句行与 Mapper 接口
        // 方法行都不出本插件**跳转**箭头，同一位置只留同类插件那一套。
        // T-MapperPreviewOrder（2026-11-26）：方法行的 SQL 预览图标已拆到
        // MapperMethodPreviewLineMarkerProvider（另一 provider、plugin.xml 注册在前），
        // 本类只产跳转箭头——同行左右序因此确定（预览恒左），不再随 daemon 首次/重算翻转。
        boolean yieldNavigation = gutterState != null
                && MyBatisCompetitorGate.shouldYield(
                        gutterState.getMapperNavYieldToCodeHelper(),
                        MyBatisCompetitorGate.isPeerPluginActive());
        for (PsiElement element : elements) {
            List<LineMarkerInfo<PsiElement>> markers = createMarker(element, yieldNavigation);
            if (markers != null) result.addAll(markers);
        }
    }

    @Nullable
    private static List<LineMarkerInfo<PsiElement>> createMarker(@NotNull PsiElement element, boolean yieldNavigation) {
        if (!(element instanceof PsiIdentifier)) return null;
        // T-MapperClassJump（2026-11-25 用户反馈「mapper.java 少一个类跳转图标」）：
        // Mapper 接口**类名** → 对应 XML 根标签。对齐竞品：MPJ view.A 的 UClass 分支、
        // MyBatisX 的 PsiClassAbstractElementFilter 都在接口声明行出这一枚；我方此前只锚方法名。
        if (element.getParent() instanceof PsiClass) {
            PsiClass owner = (PsiClass) element.getParent();
            if (owner.getNameIdentifier() == element) {
                // 类名不是语句，没有可预览的 SQL；类跳转属「跳转」，随让位一并让掉。
                if (yieldNavigation) return null;
                LineMarkerInfo<PsiElement> classMarker = createClassMarker((PsiIdentifier) element, owner);
                return classMarker != null ? List.of(classMarker) : null;
            }
        }
        PsiMethod method = element.getParent() instanceof PsiMethod ? (PsiMethod) element.getParent() : null;
        if (method == null) return null;
        if (method.getNameIdentifier() != element) return null;
        // 券01（T-PerfSweep28-01，R28-1/P-1）：接口前置——mapper namespace 语义必为接口
        // （方法绑定经 JDK 动态代理），此前 service/util 等普通类的每个方法声明都在慢趟付一次
        // 全 mapper 文件定位。isInterface 为 O(1) 属性读，开闸用户可见行为不变。
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return null;
        if (!containingClass.isInterface()) return null;
        // T-MapperPreviewOrder：跳转让位时本类不产出；预览由另一个 provider 照出。
        if (yieldNavigation) return null;
        String namespace = containingClass.getQualifiedName();
        if (namespace == null) return null;
        // F1 同构（T-GutterFirstOpenDelay Java 侧）/ P2.1 五段式：与 XML 侧同不变式——图标只在
        // 「确认真没有」时缺席，不在「还没查到」时缺席。复用 XML 侧共享的正缓存与 dumb 测试缝。
        // 不 catch ProcessCanceledException（平台契约禁吞 PCE）。
        Project project = element.getProject();
        String key = MapperXmlToJavaLineMarkerProvider.cacheKey(project, namespace, method.getName());
        // ① dumb 窗乐观产出（不查索引、不入缓存）
        if (MapperXmlToJavaLineMarkerProvider.isDumbProvider.apply(project)) {
            return List.of(buildMethodMarker(element));
        }
        // ② 确定存在入共享正缓存 / ③ Unknown 保留缓存命中 / ④ Absent 收回缓存并摘图标
        MapperXmlLocator.Lookup lookup =
                MapperXmlLocator.locateOrUnknown(project, namespace + "." + method.getName());
        if (lookup instanceof MapperXmlLocator.Found) {
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key);
            return List.of(buildMethodMarker(element));
        }
        if (lookup instanceof MapperXmlLocator.Unknown) {
            return MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)
                    ? List.of(buildMethodMarker(element)) : null;
        }
        // Absent：确定不存在 → 收回正缓存、摘图标
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.remove(key);
        return null;
    }

    @NotNull
    private static LineMarkerInfo<PsiElement> buildMethodMarker(@NotNull PsiElement element) {
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                MpvsIcons.MapperMethod,
                elt -> MpSqlVisualizerBundle.message("gutter.mapperMethod.tooltip"),
                (mouseEvent, elt) -> {
                    // 跳转时按当前元素重新定位（不缓存 marker 创建时的结果——代码可能已被编辑）
                    PsiMethod currentMethod = elt.getParent() instanceof PsiMethod
                            ? (PsiMethod) elt.getParent() : null;
                    if (currentMethod == null) return;
                    PsiClass currentClass = currentMethod.getContainingClass();
                    String currentNamespace = currentClass != null ? currentClass.getQualifiedName() : null;
                    if (currentNamespace == null) return;
                    MapperXmlLocator.Location target = MapperXmlLocator.locate(
                            elt.getProject(), currentNamespace + "." + currentMethod.getName());
                    if (target == null || !target.virtualFile().isValid()) return;
                    new OpenFileDescriptor(elt.getProject(), target.virtualFile(), target.line(), 0).navigate(true);
                },
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.mapperMethod.accessible.name")
        );
    }

    @Nullable
    private static LineMarkerInfo<PsiElement> createClassMarker(@NotNull PsiIdentifier element, @NotNull PsiClass owner) {
        if (!owner.isInterface()) return null;
        String namespace = owner.getQualifiedName();
        if (namespace == null) return null;
        Project project = element.getProject();
        String key = classToXmlKey(project, namespace);
        if (MapperXmlToJavaLineMarkerProvider.isDumbProvider.apply(project)) return buildClassMarker(element);
        XmlFile xml = MybatisXmlTargets.findMapperFileByNamespace(project, namespace);
        if (xml != null) {
            resolvedMapperClassKeys.add(key);
            return buildClassMarker(element);
        }
        if (resolvedMapperClassKeys.contains(key)) {
            return buildClassMarker(element);
        }
        resolvedMapperClassKeys.remove(key);
        return null;
    }

    @NotNull
    private static LineMarkerInfo<PsiElement> buildClassMarker(@NotNull PsiElement element) {
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                MpvsIcons.MapperMethod,
                elt -> MpSqlVisualizerBundle.message("gutter.mapperClassToXml.tooltip"),
                (mouseEvent, elt) -> {
                    PsiClass cls = elt.getParent() instanceof PsiClass ? (PsiClass) elt.getParent() : null;
                    if (cls == null) return;
                    XmlFile xml = MapperNavigationTargets.xmlFileForMapperInterface(cls);
                    if (xml == null) return;
                    var vf = xml.getVirtualFile();
                    if (vf == null || !vf.isValid()) return;
                    com.intellij.psi.xml.XmlTag rootTag = xml.getRootTag();
                    new OpenFileDescriptor(elt.getProject(), vf,
                            rootTag != null ? rootTag.getTextOffset() : 0, 0).navigate(true);
                },
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.mapperClassToXml.accessible.name")
        );
    }
}
