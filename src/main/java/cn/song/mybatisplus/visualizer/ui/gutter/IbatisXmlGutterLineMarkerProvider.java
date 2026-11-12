// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisXmlGutterLineMarkerProvider.kt 移植
package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.IbatisStatementPreview;
import cn.song.mybatisplus.visualizer.ui.IbatisXmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatementKind;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.Icon;

/**
 * iBatis 2.x {@code <sqlMap>} 语句预览图标（T-Ibatis2Support 券 A 接管面）。
 *
 * <h2>锚定条件（与让位方同源，见 {@code cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector}）</h2>
 *
 * <p>{@link #isStatementAnchor}：锚 {@code XML_NAME} token，<b>标签名 ∈ STATEMENT_TAGS 且父标签为 {@code <sqlMap>}</b>。</p>
 *
 * <h2>与既有 XML provider 的两处关键差异（都是用户报障的一半）</h2>
 *
 * <ol>
 * <li><b>不要求叶子标签</b>：iBatis 语句带 {@code <dynamic>} / {@code <isNotNull>} 子标签时<b>也必须出图标</b>。
 *     通用兜底通道（{@code XmlInlineSqlDetector.isSqlCarrierTag}）的叶子判定让这类语句一个图标都没有
 *     （计划册 §券 A 动因第 3 条），接管面不得抄那条限制。</li>
 * <li><b>不做「首词是 SQL 关键字」判定</b>：{@code <statement>} / {@code <selectKey>} 体常是手写 {@code ?} 或
 *     非 SELECT 开头（{@code INSERT}/{@code BEGIN}…），首词启发式会漏；本 provider 只认标签结构。</li>
 * </ol>
 *
 * <h2>性能红线</h2>
 *
 * <p>{@code getLineMarkerInfo} <b>严格 O(1)</b>：只看标签名 + 父标签名，零 resolve、零索引查询、
 * 零 {@code IbatisXmlRecognizer.sqlMapClientOnClasspath}（classpath 探针是 resolve 级操作）。
 * 完整识别留给冷路径（{@link IbatisXmlPreviewService} 预览时按根元素结构判定）。
 * 设置读取按 F36/F51 定例<b>方法头单次取 state</b>（{@code GutterProviderSingleLookupGuardTest} 守卫）。</p>
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="XML" .../>}（该平台
 * EP 本身是 dynamic 点位，热更新不受影响；守卫 {@code PluginXmlExtensionPointResolutionTest} 会抓）。</p>
 */
public class IbatisXmlGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 必须注册在 leaf 元素：只接受语句标签的 name token（O(1) 判定，零 resolve）
        if (!(element instanceof XmlToken token)) {
            return null;
        }
        if (token.getTokenType() != XmlTokenType.XML_NAME) {
            return null;
        }
        XmlTag tag = token.getParent() instanceof XmlTag parent ? parent : null;
        if (tag == null || !isStatementAnchor(tag)) {
            return null;
        }
        // F36/F51 定例：方法头单次取 state，总闸与组显隐共用（每到达元素 app 级服务查找 1 次）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) {
            return null;
        }
        if (gutterState != null && !gutterState.getIbatisXmlEnabled()) {
            return null;
        }
        if (!GutterGroups.enabledFor(getClass(), gutterState)) {
            return null; // R2 分组显隐（拍板 D-8＝mapperXml 组）
        }
        return new LineMarkerInfo<>(
            element,
            element.getTextRange(),
            SQL_PREVIEW_ICON,
            elt -> MpSqlVisualizerBundle.message("gutter.ibatis.tooltip"),
            IbatisGutterNavigationHandler.INSTANCE,
            GutterIconRenderer.Alignment.CENTER,
            () -> MpSqlVisualizerBundle.message("gutter.ibatis.accessible.name"),
        );
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private static final class IbatisGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final IbatisGutterNavigationHandler INSTANCE = new IbatisGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            XmlTag tag = elt.getParent() instanceof XmlTag parent ? parent : null;
            if (tag == null) {
                return;
            }
            SqlPreviewService.previewAsync(
                project,
                // T-IbatisCallPreview：装配收口到 IbatisStatementPreview.previewTag——Java 调用行
                // 预览图标与快捷键/MCP 走同一份装配，产物逐字节同源（原实现内联了 file.text 与 ordinal）
                () -> IbatisStatementPreview.previewTag(tag),
                result -> {
                    SqlPreviewService.showResult(project, result);
                    return kotlin.Unit.INSTANCE;
                },
            );
        }
    }

    /**
     * 预览图标（Java DAO 调用行预览图标复用同一实例——同一动作同一图标，避免两枚样式漂移）。
     *
     * <p>T-IbatisCallPreview：本 provider 原为 {@code private}，调用行预览 marker 需要同一个图标实例。</p>
     */
    public static final Icon SQL_PREVIEW_ICON =
        IconLoader.getIcon("/icons/sqlGutter.svg", IbatisXmlGutterLineMarkerProvider.class);

    /**
     * iBatis 2.x 语句标签集（单一事实源）：直接取自解析模型的 {@link IbatisStatementKind} 6 种语句标签，
     * 避免与 {@code xml/ibatis/} 的标签表产生第二份拷贝。
     */
    public static final Set<String> STATEMENT_TAGS = buildStatementTags();

    /** iBatis 语句映射文件的根标签。 */
    public static final String SQL_MAP_TAG = "sqlMap";

    /**
     * 语句锚定判定（<b>O(1)</b>，零 resolve）：标签名 ∈ {@link #STATEMENT_TAGS} 且父标签为 {@code <sqlMap>}。
     *
     * <p>同时被 {@code cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector.isClaimedByKnownStructure}
     * 复用为让位判据——两处<b>必须是同一个函数</b>，否则会出现图标空洞或双图标（见该类 KDoc）。</p>
     *
     * <p><b>不得</b>在此调用 {@code IbatisXmlRecognizer} / classpath 查询 / 任何 PSI resolve：
     * 本函数跑在 gutter 热路径（每到达一个 XML_NAME token）。</p>
     */
    public static boolean isStatementAnchor(@NotNull XmlTag tag) {
        XmlTag parentTag = tag.getParentTag();
        return STATEMENT_TAGS.contains(tag.getName())
            && parentTag != null && SQL_MAP_TAG.equals(parentTag.getName());
    }

    /** 枚举产出写限定形态（EnumReachabilityGuardTest 硬要求，不退 values()）。 */
    @NotNull
    private static Set<String> buildStatementTags() {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(IbatisStatementKind.SELECT.getTagName());
        tags.add(IbatisStatementKind.INSERT.getTagName());
        tags.add(IbatisStatementKind.UPDATE.getTagName());
        tags.add(IbatisStatementKind.DELETE.getTagName());
        tags.add(IbatisStatementKind.STATEMENT.getTagName());
        tags.add(IbatisStatementKind.PROCEDURE.getTagName());
        return tags;
    }
}
