package cn.song.mybatisplus.visualizer.ui.nav;

import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.xml.util.XmlTagUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * T-MapperNavShortcut：Mapper 接口方法与 XML 语句之间的<b>双向解析单一事实源</b>。
 *
 * <p>供 {@link MapperNavigationGotoDeclarationHandler}（Ctrl+B / Ctrl+Click）与
 * {@link MapperNavigationGotoRelatedProvider}（Ctrl+Alt+Home）共用，避免两处各写一套判定。</p>
 *
 * <p>判定面全部是 PSI 结构读（O(1) 属性 + 有限层祖先爬升），唯一的索引访问集中在
 * {@link MybatisXmlTargets} 既有的「mapper XML 清单 + namespace 单槽 memo」缓存里，本文件不新建缓存、
 * 不引监听器（红线维持）。</p>
 *
 * <p>dumb 态一律返回空：{@code MybatisXmlTargets.resolveInterfaceMethod} 走 {@code JavaPsiFacade.findClass}，
 * 索引未就绪时抛 {@code IndexNotReadyException}——调用方按键应安静回落原生行为，而不是弹异常。</p>
 */
public final class MapperNavigationTargets {

    private MapperNavigationTargets() {
    }

    /** 祖先爬升上限（方法名 identifier → PsiMethod 只需 1 层；留余量给 reference 表达式）。 */
    private static final int MAX_UP = 4;

    /** XML 侧祖先爬升上限（属性值 token → 语句标签最多 3 层；留余量给嵌套动态标签）。 */
    private static final int MAX_XML_UP = 12;

    /** 接口方法 → 同名 XML 语句标签（含 {@code databaseId} 多方言变体；非接口/无命中返回空）。 */
    public static List<XmlTag> statementTagsFor(PsiMethod method) {
        PsiElement owner = method.getContainingClass();
        if (owner == null) return List.of();
        // mapper 方法绑定经 JDK 动态代理，namespace 语义必为接口（与 gutter 同口径）
        if (!((com.intellij.psi.PsiClass) owner).isInterface()) return List.of();
        String namespace = ((com.intellij.psi.PsiClass) owner).getQualifiedName();
        if (namespace == null) return List.of();
        if (DumbService.isDumb(method.getProject())) return List.of();
        return MybatisXmlTargets.resolveStatementTags(method.getProject(), namespace, method.getName());
    }

    /** XML 语句标签 → namespace 接口的同名方法（非 mapper 文件/非语句标签/无命中返回 null）。 */
    @Nullable
    public static PsiMethod methodFor(XmlTag tag) {
        if (!isTopLevelStatement(tag)) return null;
        PsiFile file = tag.getContainingFile();
        if (!MybatisXmlTargets.isMapperFile(file)) return null;
        String namespace = MybatisXmlTargets.rootNamespace(file);
        if (namespace == null) return null;
        String id = tag.getAttributeValue("id");
        if (id == null || id.isBlank()) return null;
        if (DumbService.isDumb(tag.getProject())) return null;
        return MybatisXmlTargets.resolveInterfaceMethod(tag.getProject(), namespace, id);
    }

    /**
     * Java → XML 的落点：优先语句 {@code id} 的<b>值 token</b>（光标精确落在 id 文本上；{@code XmlAttributeValue}
     * 的首个子节点是开引号，直接用会落在 {@code "} 上），取不到退属性值元素再退标签。
     */
    public static PsiElement statementAnchor(XmlTag tag) {
        XmlAttribute attr = tag.getAttribute("id");
        XmlAttributeValue value = attr != null ? attr.getValueElement() : null;
        if (value == null) return tag;
        for (XmlToken token : PsiTreeUtil.findChildrenOfType(value, XmlToken.class)) {
            if (token.getTokenType() == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) return token;
        }
        return value;
    }

    /** 语句标签判定：四类语句名 + {@code <mapper>} 直接子级（片段 {@code <sql>}、嵌套动态标签一律排除）。 */
    public static boolean isTopLevelStatement(XmlTag tag) {
        XmlTag parent = tag.getParentTag();
        return MybatisXmlTargets.STATEMENT_TAGS.contains(tag.getName())
                && parent != null && "mapper".equals(parent.getName())
                && parent.getParentTag() == null;
    }

    /** 光标元素向上找到的 mapper 接口方法（普通类方法、方法体内引用均返回 null）。 */
    @Nullable
    public static PsiMethod nearestMapperMethod(PsiElement element) {
        PsiElement current = element;
        int depth = 0;
        while (current != null && depth++ < MAX_UP) {
            if (current instanceof PsiMethod) {
                com.intellij.psi.PsiClass owner = ((PsiMethod) current).getContainingClass();
                if (owner == null) return null;
                return owner.isInterface() ? (PsiMethod) current : null;
            }
            if (current instanceof PsiFile) return null;
            current = current.getParent();
        }
        return null;
    }

    /** 光标元素向上找到的 mapper 语句标签（语句体内任意位置均可，供「关联符号」用）。 */
    @Nullable
    public static XmlTag nearestStatementTag(PsiElement element) {
        PsiElement current = element;
        int depth = 0;
        while (current != null && depth++ < MAX_XML_UP) {
            if (current instanceof XmlTag && isTopLevelStatement((XmlTag) current)) return (XmlTag) current;
            if (current instanceof PsiFile) return null;
            current = current.getParent();
        }
        return null;
    }

    /**
     * XML token → 所属语句标签，<b>只认三个落点</b>：起始标签名、{@code id} 属性名、{@code id} 属性值。
     * 其余位置（标签体内的 SQL 文本、其它属性、闭标签名）返回 null，避免劫持标签内 {@code #{}}/{@code property}
     * 等引用位置的 Ctrl+B。
     */
    @Nullable
    public static XmlTag ownerStatementTag(XmlToken token) {
        PsiElement parent = token.getParent();
        XmlAttribute attribute = null;
        if (parent instanceof XmlAttribute) {
            attribute = (XmlAttribute) parent;
        } else if (parent instanceof XmlAttributeValue
                && ((XmlAttributeValue) parent).getParent() instanceof XmlAttribute) {
            attribute = (XmlAttribute) ((XmlAttributeValue) parent).getParent();
        }
        if (attribute != null) {
            if (!"id".equals(attribute.getName())) return null;
            if (!(attribute.getParent() instanceof XmlTag)) return null;
            XmlTag tag = (XmlTag) attribute.getParent();
            return isTopLevelStatement(tag) ? tag : null;
        }
        if (!(parent instanceof XmlTag)) return null;
        XmlTag tag = (XmlTag) parent;
        // 闭标签名 token 的父也是同一个 XmlTag，用起始标签名元素排除
        if (XmlTagUtil.getStartTagNameElement(tag) != token) return null;
        return isTopLevelStatement(tag) ? tag : null;
    }
}
