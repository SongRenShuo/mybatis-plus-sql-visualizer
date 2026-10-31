// 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt
// （F4 48a8f191：Mapper 双向跳转——Java→XML 方）新译。
package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Mapper 接口方法跳转到 namespace 对应 XML 中的同名 statement（Java → XML 方）。
 *
 * <p>锚定在方法名 {@link PsiIdentifier} 上（LineMarker 框架只传递 leaf 元素）；
 * {@code namespace.methodName} 在 mapper XML 中存在对应语句时才显示标记，点击跳到语句标签起始行。
 * slow pass（{@link #collectSlowLineMarkers}）——含 XML 定位（文件扫描），不进快通道。</p>
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。</p>
 */
public class MapperJavaToXmlLineMarkerProvider implements LineMarkerProvider {

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
        // 第三道门（T-MapperNavCoexistYield）：与 XML 侧同门形——竞品在位且用户未关闭让位即不产出图标
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
        if (!(element instanceof PsiIdentifier)) return null;
        PsiMethod method = element.getParent() instanceof PsiMethod ? (PsiMethod) element.getParent() : null;
        if (method == null) return null;
        if (method.getNameIdentifier() != element) return null;
        // 券01（T-PerfSweep28-01，R28-1/P-1）：接口前置——mapper namespace 语义必为接口
        // （方法绑定经 JDK 动态代理），此前 service/util 等普通类的每个方法声明都在慢趟付一次
        // 全 mapper 文件定位。isInterface 为 O(1) 属性读，开闸用户可见行为不变。
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return null;
        if (!containingClass.isInterface()) return null;
        String namespace = containingClass.getQualifiedName();
        if (namespace == null) return null;
        if (MapperXmlLocator.locate(element.getProject(), namespace + "." + method.getName()) == null) return null;
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
}
