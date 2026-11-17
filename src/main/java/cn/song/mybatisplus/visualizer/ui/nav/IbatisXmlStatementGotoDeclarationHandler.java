package cn.song.mybatisplus.visualizer.ui.nav;

import cn.song.mybatisplus.visualizer.ui.gutter.IbatisJavaCallSites;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.util.XmlTagUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-IbatisFix4 缺陷一：<b>iBatis XML 语句 → Java DAO 调用点</b>的 Ctrl+B / Ctrl+Click 接入。
 *
 * <h2>为什么之前只有单向</h2>
 *
 * <p>券 F 的 {@link IbatisDaoStatementGotoDeclarationHandler} 只做 Java → XML；XML → Java 当时被判
 * 「代价高（需全项目 Java 调用点索引）」而未做。券 B 收尾时 {@link IbatisJavaCallSites} 已经用
 * <b>词索引</b>（PsiSearchHelper.processElementsWithWord）实现了这条反查——但它只接了 gutter 箭头
 * （IbatisXmlToJavaLineMarkerProvider 的点击处理），没接平台的 GTD 管线。于是真机上
 * 「DAO 里 Ctrl+Click 能跳 XML、XML 里 Ctrl+Click 毫无反应」（用户 2026.3 报障）。</p>
 *
 * <h2>为什么是 GotoDeclarationHandler 而不是给 id 属性造 PsiReference</h2>
 *
 * <p>与同包两个 handler 同款理由：{@code PsiReference.resolve()} 会在高亮期被反复调用，而本方向的
 * 解析是一次<b>词索引全项目搜索</b>——造引用等于把索引查询搬进 daemon 热路径。GTD handler 只在
 * 用户按键/悬停时被调用，且「不是 iBatis 语句位置」一律返回 null（平台首个非空 handler 即返回，
 * 其余位置的原生跳转零变化）。</p>
 *
 * <h2>认领面（三条，全部 O(1) 语法判定）</h2>
 *
 * <ol>
 * <li>光标元素上爬到 {@code XmlAttributeValue}，其属性名必须是 id，宿主标签的父标签是 {@code <sqlMap>}；
 *     或光标落在<b>语句起始标签名 token</b> 上（{@code <select id=…} 的 select）。</li>
 * <li>宿主标签名 ∈ {@link IbatisXmlGutterLineMarkerProvider#STATEMENT_TAGS}（券 A 的单一事实源），
 *     与 gutter 图标、箭头用<b>同一个</b> isStatementAnchor 判据。</li>
 * <li>根标签名 sqlMap（{@link IbatisXmlStatementTargets#isSqlMapFile}，与 MyBatis 的 mapper 天然互斥）。</li>
 * </ol>
 *
 * <p>目标集 = 词索引找到的<b>全部</b>调用点首参字面量（通常 1 个，多个时平台弹候选列表——一条语句
 * 被多个 DAO 方法调用是常态）。找不到就是 null（不接管、不弹提示、不报错）。</p>
 *
 * <h2>线程</h2>
 *
 * <p>getGotoDeclarationTargets 在 EDT 上被调用，按仓内红线把 PSI 读取整体包进 ReadAction.compute
 * （EDT 内联执行，零额外成本）；dumb 态（索引不可用）直接不接管。</p>
 */
public class IbatisXmlStatementGotoDeclarationHandler implements GotoDeclarationHandler {

    private static final String ID_ATTRIBUTE = "id";

    /** 目标表上限：一条语句被大量 DAO 方法调用时，平台候选列表只列前 N 个。 */
    private static final int MAX_TARGETS = 20;

    @Override
    public @Nullable PsiElement[] getGotoDeclarationTargets(@Nullable PsiElement sourceElement,
                                                            int offset,
                                                            @Nullable Editor editor) {
        if (sourceElement == null) {
            return null;
        }
        PsiElement element = sourceElement;
        try {
            return ReadAction.compute(() -> targetsFor(element));
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private PsiElement[] targetsFor(@NotNull PsiElement element) {
        XmlTag tag = statementTagOf(element);
        if (tag == null) {
            return null;
        }
        if (!IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)) {
            return null;
        }
        var file = tag.getContainingFile();
        if (!IbatisXmlStatementTargets.isSqlMapFile(file)) {
            return null;
        }
        String rawId = tag.getAttributeValue(ID_ATTRIBUTE);
        String statementId = rawId != null ? rawId.trim() : null;
        if (statementId == null || statementId.isEmpty()) {
            return null;
        }
        var project = element.getProject();
        if (DumbService.isDumb(project)) {
            return null;
        }
        var literals = IbatisJavaCallSites.findAllCallLiterals(
                project,
                IbatisXmlStatementTargets.rootNamespace(file),
                statementId,
                MAX_TARGETS);
        return literals.isEmpty() ? null : literals.toArray(new PsiElement[0]);
    }

    /**
     * 光标元素 → 语句标签（两条锚形态）：
     * <ul>
     * <li>id 属性值（值内任意 leaf 都上爬到 XmlAttributeValue）——用户最自然的点击点；</li>
     * <li>起始标签名 token（{@code <select} 的 select）——与 gutter 箭头的锚一致，顺手支持。</li>
     * </ul>
     *
     * <p>其它属性（resultMap / parameterMap / refid）返回 null：那些有各自的引用/落点口径，
     * 不在这里抢。</p>
     */
    @Nullable
    private static XmlTag statementTagOf(@NotNull PsiElement element) {
        XmlAttributeValue attributeValue =
                PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false);
        if (attributeValue != null) {
            if (!(attributeValue.getParent() instanceof XmlAttribute attribute)) {
                return null;
            }
            if (!ID_ATTRIBUTE.equals(attribute.getName())) {
                return null;
            }
            return attribute.getParent();
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class, false);
        if (tag == null) {
            return null;
        }
        return XmlTagUtil.getStartTagNameElement(tag) == element ? tag : null;
    }
}
