// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/nav/IbatisXmlStatementGotoDeclarationHandler.kt 移植
package cn.song.mybatisplus.visualizer.ui.nav;

import cn.song.mybatisplus.visualizer.ui.gutter.IbatisJavaCallSites;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.util.XmlTagUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * T-IbatisFix4 缺陷一：<b>iBatis XML 语句 → Java DAO 调用点</b>的 Ctrl+B / Ctrl+Click 接入。
 *
 * <h2>为什么之前只有单向</h2>
 *
 * <p>券 F 的 {@link IbatisDaoStatementGotoDeclarationHandler} 只做 Java → XML；XML → Java 当时被判
 * 「代价高（需全项目 Java 调用点索引）」而未做。券 B 收尾时 {@link IbatisJavaCallSites} 已经用
 * <b>词索引</b>（{@code PsiSearchHelper.processElementsWithWord}）实现了这条反查——但它只接了 gutter
 * 箭头（{@code IbatisXmlToJavaLineMarkerProvider} 的点击处理），没接平台的 GTD 管线。于是真机上
 * 「DAO 里 Ctrl+Click 能跳 XML、XML 里 Ctrl+Click 毫无反应」（用户 2026.3 报障）。</p>
 *
 * <h2>为什么是 {@code GotoDeclarationHandler} 而不是给 {@code id} 属性造 {@code PsiReference}</h2>
 *
 * <p>与同包两个 handler 同款理由：{@code PsiReference.resolve()} 会在高亮期被反复调用，而本方向的解析
 * 是一次<b>词索引全项目搜索</b>——造引用等于把索引查询搬进 daemon 热路径。GTD handler 只在用户
 * 按键/悬停时被调用，且「不是 iBatis 语句位置」一律返回 null（平台首个非空 handler 即返回，
 * 其余位置的原生跳转零变化）。</p>
 *
 * <h2>认领面（三条，全部 O(1) 语法判定）</h2>
 *
 * <ol>
 * <li>光标元素上爬到 {@code XmlAttributeValue}，其属性名必须是 {@code id}，宿主标签的父标签是 {@code <sqlMap>}；
 *     或光标落在<b>语句起始标签名 token</b> 上（{@code <select id=…} 的 {@code select}）。</li>
 * <li>宿主标签名 ∈ {@link IbatisXmlGutterLineMarkerProvider#STATEMENT_TAGS}（券 A 的单一事实源），
 *     与 gutter 图标、箭头用<b>同一个</b> {@code isStatementAnchor} 判据。</li>
 * <li>根标签名 {@code sqlMap}（{@link IbatisXmlStatementTargets#isSqlMapFile}，与 MyBatis 的 {@code mapper} 天然互斥）。</li>
 * </ol>
 *
 * <p>目标集 = 词索引找到的<b>全部</b>调用点首参字面量（通常 1 个，多个时平台弹候选列表——一条语句
 * 被多个 DAO 方法调用是常态）。找不到就是 null（不接管、不弹提示、不报错）。</p>
 *
 * <h2>线程</h2>
 *
 * <p>{@code getGotoDeclarationTargets} 在 EDT 上被调用，按仓内红线把 PSI 读取整体包进 {@link ReadAction#compute}
 * （EDT 内联执行，零额外成本）；dumb 态（索引不可用）直接不接管。</p>
 */
public class IbatisXmlStatementGotoDeclarationHandler implements GotoDeclarationHandler {

    private static final String ID_ATTRIBUTE = "id";

    /** 目标表上限：一条语句被大量 DAO 方法调用时，平台候选列表只列前 N 个。 */
    private static final int MAX_TARGETS = 20;

    /**
     * T-M52 同款注记：master 侧 {@code @Suppress("DEPRECATION")}——{@code ReadAction.compute(ThrowableComputable)}
     * 在 262 标 deprecated；243 编译目标仅此重载可用。Java 侧沿用该重载。
     */
    @Override
    @Nullable
    public PsiElement[] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            @Nullable Editor editor) {
        PsiElement element = sourceElement;
        if (element == null) {
            return null;
        }
        try {
            // master 侧 runCatching 降级（任何异常按「本次不接管」处理）→ try/catch 等义
            return ReadAction.compute(() -> targetsFor(element));
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private static PsiElement[] targetsFor(@NotNull PsiElement element) {
        XmlTag tag = statementTagOf(element);
        if (tag == null) {
            return null;
        }
        if (!IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)) {
            return null;
        }
        if (!IbatisXmlStatementTargets.isSqlMapFile(tag.getContainingFile())) {
            return null;
        }
        String idValue = tag.getAttributeValue(ID_ATTRIBUTE);
        String statementId = idValue != null ? idValue.trim() : "";
        if (statementId.isEmpty()) {
            return null;
        }
        if (DumbService.isDumb(element.getProject())) {
            return null;
        }
        List<PsiLiteralExpression> literals = IbatisJavaCallSites.findAllCallLiterals(
            element.getProject(),
            IbatisXmlStatementTargets.rootNamespace(tag.getContainingFile()),
            statementId,
            MAX_TARGETS
        );
        return literals.isEmpty() ? null : literals.toArray(new PsiElement[0]);
    }

    /**
     * 光标元素 → 语句标签（两条锚形态）：
     * <ul>
     * <li>{@code id} 属性值（值内任意 leaf 都上爬到 {@link XmlAttributeValue}）——用户最自然的点击点；</li>
     * <li>起始标签名 token（{@code <select} 的 {@code select}）——与 gutter 箭头的锚一致，顺手支持。</li>
     * </ul>
     *
     * <p>其它属性（{@code resultMap} / {@code parameterMap} / {@code refid}）返回 null：那些有各自的引用/落点口径，
     * 不在这里抢。</p>
     */
    @Nullable
    private static XmlTag statementTagOf(@NotNull PsiElement element) {
        XmlAttributeValue attributeValue = PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false);
        if (attributeValue != null) {
            XmlAttribute attribute = attributeValue.getParent() instanceof XmlAttribute attr ? attr : null;
            if (attribute == null || !ID_ATTRIBUTE.equals(attribute.getName())) {
                return null;
            }
            return attribute.getParent() instanceof XmlTag ? (XmlTag) attribute.getParent() : null;
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class, false);
        if (tag == null) {
            return null;
        }
        return XmlTagUtil.getStartTagNameElement(tag) == element ? tag : null;
    }
}
