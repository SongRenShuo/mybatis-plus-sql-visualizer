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
        // F2（T-GutterFirstOpenDelay）：三条预热链合并为一个调度点——工程 smart 后延迟
        // `sqlvis.gutter.warmDelayMs`（默认 5000ms；0 = 旧行为立即踢）再踢一次，避免冷打开窗口里
        // 与快/慢趟 pass、索引重建抢 BGT（实测首开重活：反查索引 3337ms + 跨 ORM 回补 15017ms +
        // assembly warm 17095ms）。调度语义、边角与回退通道见 GutterWarmScheduler 的 KDoc。
        try {
            GutterWarmScheduler.onGutterFirstRender(tag);
        } catch (Throwable ignored) {
            // 预热是有价值非必需：调度链任何失败都不影响图标本体
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
                long t0 = System.currentTimeMillis();
                // T-M25.2：传 project 解析 @class@field 静态常量（用户项目常量类字段值）
                // T-M27.3/4：注入实体上下文（namespace → Mapper 接口 → 主实体 + @TableName 联表候选）——
                // 预览弹窗补全表/列来源；候选表名全位置提示（主表去重）
                long t1 = System.currentTimeMillis();
                // T-StartupReadLockFreeze §十八（2026-11-13 14:48 真机）：索引期**不做**实体/表上下文解析。
                // 它依赖索引，冷启动时会跟着 JavaBuilder/索引一起等——实测「点击 → compute 开始」11s，
                // 其中 8s 是 IDE 在建索引/编译，而我们这段闭包就排在哪里等读锁；
                // 实体上下文只影响补全提示（表/列来源），缺席不影响 SQL 预览本身。
                boolean dumb;
                try {
                    dumb = com.intellij.openapi.project.DumbService.isDumb(project);
                } catch (Throwable t) {
                    dumb = false;
                }
                // §二十：预览打开不解析联表候选（实测 2~3s、最终只得 1 个候选），候选留给补全触发时再算
                var src = dumb ? null
                        : XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag, false);
                long t2 = System.currentTimeMillis();
                com.intellij.openapi.diagnostic.Logger.getInstance(XmlSqlGutterLineMarkerProvider.class).info(
                        "[open-perf] pre-assembly: sourceCtx=" + (t1 - t0) + "ms completion=" + (t2 - t1)
                                + "ms dumb=" + dumb
                                + (dumb ? "（索引未就绪，已跳过实体上下文）" : ""));
                SqlCompletionContext entity = null;
                var main = src != null ? src.getMain() : null;
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

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(XmlSqlGutterLineMarkerProvider.class);

    /**
     * F2（T-GutterFirstOpenDelay）：三条预热链的统一踢出点，{@link GutterWarmScheduler} 到点
     * （或立即踢回退通道）时调用，顺序与原「首渲染直踢」一致；链内部各自去重、失败静默。
     */
    public static void kickFileWarmers(@NotNull XmlTag tag) {
        // T-PreviewOpenPerfM2 M11：MBG Example 语句的首次预览慢在 M2 引用检索（真机 1628~2042ms，
        // 弹窗 2.7~4.6s），第二次起只有 200ms 级。渲染 gutter 时顺手在后台预取一次——用户从
        // 「看到图标」到「点开」通常有秒级延迟，届时缓存已热；不改变任何交互，失败静默。
        try {
            prefetchMbgExample(tag);
        } catch (Throwable ignored) {
        }
        // §二十七：文件首次渲染 gutter 时后台预热一次「补全上下文」（实体解析冷态实测 13.3s）。
        // 原来这笔成本压在点击预览里（pre-assembly:completion=13280ms）；挪到文件打开时，
        // 用户真去点预览/补全时已经是热态。每个文件只做一次，dumb 期跳过（等下次渲染再试）。
        try {
            warmCompletionContextOnce(tag);
        } catch (Throwable ignored) {
        }
        // T-PreviewAssemblyWarm：同一信号再预热一次**装配**（只算不建 UI，结果丢弃）——把首次预览里
        // 那 351ms 的冷类加载/JIT 提前消化掉；与上面 ctx 预热不同文件级去重、互不替代。
        // 开关：Registry `sqlvis.preview.assemblyWarm`（默认开）；dumb 期跳过；失败静默。
        try {
            cn.song.mybatisplus.visualizer.ui.PreviewAssemblyWarm.warmOnce(tag);
        } catch (Throwable ignored) {
        }
    }

    /**
     * M11 预取入口：只对 MBG Example 形态（parameterType 以 Example 结尾）的语句触发，
     * 其余语句（普通 mapper SQL）M2 根本不参与，预取纯属白烧。
     * （F2 起从实例方法挪进 companion——统一踢出点 kickFileWarmers 在 companion，三条链须同域。）
     */
    private static void prefetchMbgExample(@NotNull XmlTag tag) {
        // 只预取 select：真机上 countByExample / deleteByExample 这些语句多数没有调用点，
        // 预取它们只会白烧一轮检索（实测 6 秒内同一语句重跑 4 轮）。
        if (!"select".equals(tag.getName())) {
            return;
        }
        String paramType = tag.getAttributeValue("parameterType");
        if (paramType == null || !paramType.endsWith("Example")) {
            return;
        }
        String id = tag.getAttributeValue("id");
        if (id == null) {
            return;
        }
        String namespace = tag.getParentTag() != null ? tag.getParentTag().getAttributeValue("namespace") : null;
        if (namespace == null) {
            return;
        }
        cn.song.mybatisplus.visualizer.ui.MbgExampleCallResolver.prefetch(tag.getProject(), namespace, id);
    }

    /** §二十七：已预热过补全上下文的文件（按 VirtualFile.url，每文件一次）。 */
    private static final java.util.Set<String> WARMED_CONTEXT_FILES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 后台预热补全上下文（实体解析 + 参数名）。**不解析联表候选**（那条另有预热链）。
     * 读动作走 nonBlocking + inSmartMode：等索引不持读锁，可取消。
     */
    private static void warmCompletionContextOnce(@NotNull XmlTag tag) {
        Project project = tag.getProject();
        String fileUrl = tag.getContainingFile() != null && tag.getContainingFile().getVirtualFile() != null
                ? tag.getContainingFile().getVirtualFile().getUrl() : null;
        if (fileUrl == null) {
            return;
        }
        if (!WARMED_CONTEXT_FILES.add(fileUrl)) {
            return;
        }
        boolean dumb;
        try {
            dumb = com.intellij.openapi.project.DumbService.isDumb(project);
        } catch (Throwable t) {
            dumb = false;
        }
        if (dumb) {
            WARMED_CONTEXT_FILES.remove(fileUrl); // 索引期不预热，等下一次渲染再试
            return;
        }
        long started = System.currentTimeMillis();
        com.intellij.openapi.application.ReadAction.nonBlocking(() -> {
            XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag, false);
            return kotlin.Unit.INSTANCE;
        })
                .inSmartMode(project)
                .expireWith(project)
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
                .onSuccess(r ->
                        LOG.info("[open-perf] ctx warm done in " + (System.currentTimeMillis() - started)
                                + "ms file=" + fileUrl))
                .onError(t -> {
                    WARMED_CONTEXT_FILES.remove(fileUrl);
                    LOG.info("[open-perf] ctx warm fail " + t.getClass().getSimpleName() + ": " + t.getMessage());
                });
    }

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
