// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisXmlToJavaLineMarkerProvider.kt 移植
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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.xml.util.XmlTagUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-Ibatis2Gap 券 B：iBatis {@code <sqlMap>} 语句行 → Java 侧 {@code SqlMapExecutor} 调用点的跳转箭头
 * （观感对齐 MyBatis 的 {@code MapperXmlToJavaLineMarkerProvider}，图标复用 {@link MpvsIcons#MapperStatement}）。
 *
 * <h2>锚与门（热路径形态）</h2>
 *
 * <p>锚 = 语句标签的<b>起始名 token</b>（{@code getLineMarkerInfo} 永远返回 null，全部走慢趟
 * {@code collectSlowLineMarkers}——与 MyBatis 那对同形）。判定全部 <b>O(1) 语法级</b>：</p>
 * <ol>
 * <li>根标签名 == {@code sqlMap}（{@link IbatisXmlStatementTargets#isSqlMapFile}，与券 F 的 L8 handler 同门）；</li>
 * <li>标签名 ∈ {@link IbatisXmlGutterLineMarkerProvider#STATEMENT_TAGS}（券 A 的单一事实源）；</li>
 * <li>该 token 是 {@code XmlTagUtil.getStartTagNameElement}（一语句一锚，闭合名不重复出图标）；</li>
 * <li>语句标签是 {@code <sqlMap>} 直接子级且 {@code id} 非空。</li>
 * </ol>
 *
 * <p><b>禁止</b>在本 provider 的判定路径上做任何项目级搜索 / 引用解析：目标是否存在到
 * <b>点击时</b>才查（{@link IbatisJavaCallSites} 走词索引，见其注释）——找不到就无动作、不报错。
 * 与 MyBatis 侧的分工：{@code MapperXmlToJavaLineMarkerProvider} 只认根标签 {@code mapper}（{@code isMapperFile}
 * 门控），本 provider 只认 {@code sqlMap}——两套根标签天然互斥，同一行不会双箭头（用例钉住）。</p>
 *
 * <h2>让位门（T-Ibatis2Gap 收尾：与 MyBatis 那对<b>刻意解耦</b>，不让位）</h2>
 *
 * <p>竞品 MyBatisCodeHelperPro <b>没有 iBatis 能力</b>（其发行包解包扫描 iBatis 命中为 0），sqlMap
 * 文件与 {@code SqlMapExecutor} 调用行上不存在图标冲突——竞品在位时本 provider <b>照常产出箭头</b>，
 * 不读 {@code mapperNavYieldToCodeHelper}、不走 {@link MyBatisCompetitorGate}。共用让位门只会把竞品根本
 * 不画的 iBatis 箭头白白藏掉（本插件典型用户 IDE 里就装着 CodeHelperPro，等于功能白做）。
 * 反向锚：{@code IbatisNavigationLineMarkerProvidersTest} 的「竞品在位」用例——恢复让位必恰它变红；
 * MyBatis 那对（{@code MapperXmlToJavaLineMarkerProvider}）的让位行为不变，由其自身守卫与对照用例钉住。</p>
 *
 * <h2>预览图标共存</h2>
 *
 * <p>iBatis 语句行同时有券 A 的预览图标（{@link IbatisXmlGutterLineMarkerProvider}，快路径锚）与本
 * provider 的跳转箭头（慢趟锚）——两者<b>共存是预期</b>（MyBatis 文件同样预览 + 跳转并存）。</p>
 */
public class IbatisXmlToJavaLineMarkerProvider implements LineMarkerProvider {

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
        // T-Ibatis2Gap 收尾：竞品无 iBatis 能力、无图标冲突，刻意不让位（理由见类 KDoc；
        // 恢复让位的反证锚 = IbatisNavigationLineMarkerProvidersTest 的竞品在位用例）
        for (PsiElement element : elements) {
            LineMarkerInfo<PsiElement> marker = createMarker(element);
            if (marker != null) {
                result.add(marker);
            }
        }
    }

    @Nullable
    private static LineMarkerInfo<PsiElement> createMarker(@NotNull PsiElement element) {
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
        if (!IbatisXmlStatementTargets.isSqlMapFile(tag.getContainingFile())) {
            return null;
        }
        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null
            || !IbatisXmlStatementTargets.SQL_MAP_ROOT_TAG.equals(parentTag.getName())) {
            return null;
        }
        String idValue = tag.getAttributeValue("id");
        String statementId = idValue != null ? idValue.trim() : "";
        if (statementId.isEmpty()) {
            return null;
        }
        return new LineMarkerInfo<>(
            element, element.getTextRange(), MpvsIcons.MapperStatement,
            elt -> MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.tooltip"),
            IbatisToJavaNavigationHandler.INSTANCE,
            GutterIconRenderer.Alignment.CENTER,
            () -> MpSqlVisualizerBundle.message("gutter.ibatisStatementNav.accessible.name"),
        );
    }

    /** 点击处理：EDT 内联 ReadAction 里走词索引找调用点（找不到 = 无动作，不弹提示不报错）。 */
    private static final class IbatisToJavaNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final IbatisToJavaNavigationHandler INSTANCE = new IbatisToJavaNavigationHandler();

        /**
         * T-M52 同款注记：master 侧 {@code @Suppress("DEPRECATION")}——{@code ReadAction.compute(ThrowableComputable)}
         * 在 262 标 deprecated；243 编译目标仅此重载可用。Java 侧沿用该重载。
         */
        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            XmlTag tag = elt.getParent() instanceof XmlTag parent ? parent : null;
            if (tag == null) {
                return;
            }
            Project project = elt.getProject();
            String idValue = tag.getAttributeValue("id");
            String statementId = idValue != null ? idValue.trim() : "";
            if (statementId.isEmpty()) {
                return;
            }
            String namespace = IbatisXmlStatementTargets.rootNamespace(tag.getContainingFile());
            PsiLiteralExpression target = null;
            try {
                // master 侧 runCatching 降级 → try/catch 等义
                target = ReadAction.compute(
                    () -> IbatisJavaCallSites.findFirstCallLiteral(project, namespace, statementId));
            } catch (Throwable ex) {
                return;
            }
            if (target == null) {
                return;
            }
            // 落点 = 调用首参字面量（OpenFileDescriptor 与 MapperJavaToXml 同款；PsiLiteralExpression
            // 不实现 Navigatable，无 navigate(true) 直调形态）
            VirtualFile file = target.getContainingFile() != null ? target.getContainingFile().getVirtualFile() : null;
            if (file == null || !file.isValid()) {
                return;
            }
            new OpenFileDescriptor(project, file, target.getTextOffset()).navigate(true);
        }
    }
}
