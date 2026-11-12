package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * XML mapper 语句预览图标（T-M24-04）：`<select|insert|update|delete>` 语句行显示预览图标，
 * 点击弹出动态 SQL 预览（复用 {@link SqlPreviewService#showResult} 链路）。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定（tag 名 + 父标签名），零 resolve、零遍历、
 * 零转换；tooltip 静态文案；点击转换走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="XML" .../>}。
 */
public class XmlSqlGutterLineMarkerProvider implements LineMarkerProvider {

    /** 语句标签集合（select/insert/update/delete）。 */
    public static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 必须注册在 leaf 元素：只接受语句标签的 name token（O(1) 判定，零 resolve）
        if (!(element instanceof XmlToken)) return null;
        XmlToken token = (XmlToken) element;
        if (token.getTokenType() != XmlTokenType.XML_NAME) return null;
        XmlTag tag = token.getParent() instanceof XmlTag ? (XmlTag) token.getParent() : null;
        if (tag == null) return null;
        // 语句标签 + 直接父级是 <mapper>（避免非 mapper XML 误显）
        if (!STATEMENT_TAGS.contains(tag.getName())) return null;
        if (tag.getParentTag() == null || !"mapper".equals(tag.getParentTag().getName())) return null;
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（原 gutterEnabled + enabledFor
        // 双路径每到达元素 3 次 app 级查找收敛为 1 次）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return null;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return null; // R2 分组显隐
        // T-PreviewOpenPerfM2 M11：MBG Example 语句的首次预览慢在 M2 引用检索（真机 1628~2042ms，
        // 弹窗 2.7~4.6s），第二次起只有 200ms 级。渲染 gutter 时顺手在后台预取一次——用户从
        // 「看到图标」到「点开」通常有秒级延迟，届时缓存已热；不改变任何交互，失败静默。
        try {
            prefetchMbgExample(tag);
        } catch (Throwable ignored) { // Kotlin runCatching 等义：预取失败静默
        }
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.xml.tooltip"),
                XmlGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.xml.accessible.name")
        );
    }

    /**
     * M11 预取入口：只对 MBG Example 形态（{@code parameterType} 以 {@code Example} 结尾）的语句触发，
     * 其余语句（普通 mapper SQL）M2 根本不参与，预取纯属白烧。
     */
    private static void prefetchMbgExample(XmlTag tag) {
        // 只预取 select：真机上 countByExample / deleteByExample 这些语句多数没有调用点，
        // 预取它们只会白烧一轮检索（实测 6 秒内同一语句重跑 4 轮）。
        if (!"select".equals(tag.getName())) return;
        String paramType = tag.getAttributeValue("parameterType");
        if (paramType == null || !paramType.endsWith("Example")) return;
        String id = tag.getAttributeValue("id");
        if (id == null) return;
        XmlTag parentTag = tag.getParentTag();
        String namespace = parentTag != null ? parentTag.getAttributeValue("namespace") : null;
        if (namespace == null) return;
        cn.song.mybatisplus.visualizer.ui.MbgExampleCallResolver.prefetch(tag.getProject(), namespace, id);
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private static final class XmlGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final XmlGutterNavigationHandler INSTANCE = new XmlGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            XmlTag tag = elt.getParent() instanceof XmlTag ? (XmlTag) elt.getParent() : null;
            if (tag == null) return;
            XmlFile file = tag.getContainingFile() instanceof XmlFile ? (XmlFile) tag.getContainingFile() : null;
            if (file == null) return;
            SqlPreviewService.previewAsync(project, () -> {
                // T-M25.2：传 project 解析 @class@field 静态常量（用户项目常量类字段值）
                // T-M27.3/4：注入实体上下文（namespace → Mapper 接口 → 主实体 + @TableName 联表候选）——
                // 预览弹窗补全表/列来源；候选表名全位置提示（主表去重）
                var src = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag);
                SqlCompletionContext entity = null;
                var main = src.getMain();
                if (main != null) {
                entity = new SqlCompletionContext(main.tableName, main.columns, main.tableAlias,
                        main.columnComments, main.tableComment, src.getCandidates());
                }
                return XmlPreviewService.INSTANCE.preview(file.getText(), tag.getAttributeValue("id"), project, entity);
            }, result -> { SqlPreviewService.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", XmlSqlGutterLineMarkerProvider.class);

    /**
     * T-M29.6：选择区内 mapper 语句候选（tag 起始 offset 在选区范围内，按 offset 排序）——
     * ConvertToSqlAction 选择区候选交互复用（与 Java 选择区候选语义一致：语句开头在选择区内才计入）；
     * 纯 PSI 遍历零 resolve。
     *
     * @param file  待扫描的 XML 文件
     * @param start 选区起始 offset（含）
     * @param end   选区结束 offset（不含）
     * @return 命中的 mapper 语句标签列表（按起始 offset 升序）
     */
    public static List<XmlTag> statementsInRange(XmlFile file, int start, int end) {
        List<XmlTag> all = new ArrayList<>(PsiTreeUtil.collectElementsOfType(file, XmlTag.class));
        List<XmlTag> result = new ArrayList<>();
        for (XmlTag it : all) {
            if (STATEMENT_TAGS.contains(it.getName())
                    && it.getTextRange().getStartOffset() >= start
                    && it.getTextRange().getStartOffset() < end) {
                result.add(it);
            }
        }
        result.sort(Comparator.comparingInt(t -> t.getTextRange().getStartOffset()));
        return result;
    }
}
