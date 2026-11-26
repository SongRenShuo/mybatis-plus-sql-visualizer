// 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperMethodPreviewLineMarkerProvider.kt
// （T-MapperPreviewOrder / T-MapperMethodSqlPreview，冻结 tip f06828c2d）新译。
package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator;
import cn.song.mybatisplus.visualizer.psi.PsiSnapshot;
import cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import javax.swing.Icon;

/**
 * T-MapperPreviewOrder（2026-11-26 用户报障「预览图标首次在右、过一会到左」）：
 * Mapper 接口方法行的 <b>SQL 预览</b>图标单独成 provider，与跳转箭头分开。
 *
 * <h2>为什么拆</h2>
 *
 * <p>平台慢趟按 <b>provider 顺序</b>收集（{@code LineMarkersPass.queryProviders} 外层遍历 providers、
 * 每个 provider 的 {@code collectSlowLineMarkers} 结果按 add 顺序消费），同一行两枚图标的左右序
 * 因此由「谁先被收集」决定。此前预览与跳转都在 {@link MapperJavaToXmlLineMarkerProvider} 一个
 * provider 里，首次 daemon pass 与后续重算的收集/合并差异会让顺序翻转（用户实测右→左）。
 * 本类在 {@code plugin.xml} 里注册在跳转 provider <b>之前</b>，顺序恒为「预览在左、跳转在右」，
 * 与 XML 语句行（{@code XmlSqlGutterLineMarkerProvider} 在前、{@code MapperXmlToJavaLineMarkerProvider}
 * 在后）完全同构。</p>
 *
 * <h2>判定</h2>
 *
 * <p>用 {@link MapperXmlLocator#existsOrUnknown}（索引候选 + PSI 子树匹配，<b>不建 document</b>）——
 * 与跳转 provider 的 {@code locateOrUnknown}（要行号、要 document）各付一次，但本类不碰 document，
 * 成本低于直接复用。三态语义与跳转同口径：dumb 乐观产出、索引临界命中共享正缓存仍产出、
 * 确定没有收回缓存并摘图标（不出点了必空的死图标）。</p>
 *
 * <h2>让位</h2>
 *
 * <p>预览是我方独有能力（三家竞品 Java 侧 provider 均为跳转箭头），<b>不参与</b>
 * {@link MyBatisCompetitorGate} 让位；让位开着时该行仍保留这一枚。</p>
 */
public class MapperMethodPreviewLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
            @NotNull Collection<? super LineMarkerInfo<?>> result) {
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return;
        for (PsiElement element : elements) {
            LineMarkerInfo<PsiElement> marker = createPreviewMarker(element);
            if (marker != null) result.add(marker);
        }
    }

    @Nullable
    private static LineMarkerInfo<PsiElement> createPreviewMarker(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier)) return null;
        PsiMethod method = element.getParent() instanceof PsiMethod ? (PsiMethod) element.getParent() : null;
        if (method == null) return null;
        if (method.getNameIdentifier() != element) return null;
        PsiClass owner = method.getContainingClass();
        if (owner == null) return null;
        if (!owner.isInterface()) return null;
        String namespace = owner.getQualifiedName();
        if (namespace == null) return null;
        var project = element.getProject();
        String key = MapperXmlToJavaLineMarkerProvider.cacheKey(project, namespace, method.getName());
        // 五段式（与跳转 provider 同构）：dumb 乐观 → Found 入正缓存 → Unknown 命中缓存仍出 →
        // Absent 收回缓存并摘图标 → 其余不出。注意不 catch ProcessCanceledException（平台契约禁吞 PCE）。
        if (MapperXmlToJavaLineMarkerProvider.isDumbProvider.apply(project)) return buildPreviewMarker(element);
        MapperXmlLocator.Lookup lookup =
                MapperXmlLocator.existsOrUnknown(project, namespace + "." + method.getName());
        if (lookup instanceof MapperXmlLocator.Found) {
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key);
            return buildPreviewMarker(element);
        }
        if (lookup instanceof MapperXmlLocator.Unknown) {
            return MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)
                    ? buildPreviewMarker(element) : null;
        }
        // Absent：确定不存在 → 收回正缓存、摘图标
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.remove(key);
        return null;
    }

    @NotNull
    private static LineMarkerInfo<PsiElement> buildPreviewMarker(@NotNull PsiElement element) {
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip"),
                MapperMethodPreviewNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.accessible.name")
        );
    }

    /**
     * 点击（T-MapperMethodSqlPreview）：方法名 → 同名 XML 语句 → 复用
     * {@link XmlSqlGutterLineMarkerProvider#previewStatementTag} 渲染，与 XML 语句行点击逐字节一致。
     *
     * <p>EDT 上只做锚点快照与来源行号；方法 → 语句标签的 PSI 解析放进后台 ReadAction，
     * 读取前用 {@link PsiSnapshot} 校验（T-PCP-02 跨线程 PSI 纪律）。索引未就绪时延后到 smart
     * 再执行一次——{@code MapperNavigationTargets.statementTagsFor} 在 dumb 期返回空，直接点会误报「未找到」。</p>
     */
    private static final class MapperMethodPreviewNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final MapperMethodPreviewNavigationHandler INSTANCE = new MapperMethodPreviewNavigationHandler();

        @Override
        public void navigate(@Nullable MouseEvent e, PsiElement elt) {
            PsiMethod method = elt.getParent() instanceof PsiMethod ? (PsiMethod) elt.getParent() : null;
            if (method == null) return;
            var project = elt.getProject();
            boolean dumb;
            try {
                dumb = DumbService.isDumb(project);
            } catch (Throwable t) {
                dumb = false;
            }
            if (dumb) {
                DumbService.getInstance(project).runWhenSmart(() -> navigate(e, elt));
                return;
            }
            // T-M52（本仓既有口径）：不加显式类型参数——`ReadAction.<X>compute(...)` 会把重载
            // 锁定到 compute(Computable)，而 243 编译目标只有 compute(ThrowableComputable)。
            PsiSnapshot snapshot = ReadAction.compute(() -> PsiSnapshot.capture(method));
            String sourceLocation;
            try {
                PsiFile containingFile = method.getContainingFile();
                var document = containingFile != null ? containingFile.getViewProvider().getDocument() : null;
                Integer line = document != null
                        ? document.getLineNumber(method.getTextRange().getStartOffset()) + 1 : null;
                sourceLocation = line != null && containingFile != null
                        ? containingFile.getName() + ":" + line : null;
            } catch (Throwable t) {
                sourceLocation = null;
            }
            String finalSourceLocation = sourceLocation;
            XmlSqlGutterLineMarkerProvider.previewStatementTag(project, finalSourceLocation, () -> {
                if (snapshot != null && snapshot.validate() instanceof PsiSnapshot.Validity.Invalid) return null;
                List<XmlTag> tags = MapperNavigationTargets.statementTagsFor(method);
                if (tags.isEmpty()) return null;
                XmlTag tag = tags.get(0);
                return tag.getContainingFile() instanceof XmlFile
                        ? new kotlin.Pair<>((XmlFile) tag.getContainingFile(), tag) : null;
            });
        }
    }

    /** 方法行 SQL 预览图标（与 XML 语句行同款 {@code sqlGutter.svg}）。 */
    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", MapperMethodPreviewLineMarkerProvider.class);
}
