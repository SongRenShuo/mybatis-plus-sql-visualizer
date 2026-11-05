package cn.song.mybatisplus.visualizer.ui.nav

import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.xml.util.XmlTagUtil

/**
 * T-MapperNavShortcut：Mapper 接口方法与 XML 语句之间的**双向解析单一事实源**。
 *
 * 供 [MapperNavigationGotoDeclarationHandler]（Ctrl+B / Ctrl+Click）与
 * [MapperNavigationGotoRelatedProvider]（Ctrl+Alt+Home）共用，避免两处各写一套判定。
 *
 * 判定面全部是 PSI 结构读（O(1) 属性 + 有限层祖先爬升），唯一的索引访问集中在
 * [MybatisXmlTargets] 既有的「mapper XML 清单 + namespace 单槽 memo」缓存里，本文件不新建缓存、
 * 不引监听器（红线维持）。
 *
 * dumb 态一律返回空：`MybatisXmlTargets.resolveInterfaceMethod` 走 `JavaPsiFacade.findClass`，
 * 索引未就绪时抛 `IndexNotReadyException`——调用方按键应安静回落原生行为，而不是弹异常。
 */
internal object MapperNavigationTargets {

    /** 祖先爬升上限（方法名 identifier → PsiMethod 只需 1 层；留余量给 reference 表达式）。 */
    private const val MAX_UP = 4

    /** XML 侧祖先爬升上限（属性值 token → 语句标签最多 3 层；留余量给嵌套动态标签）。 */
    private const val MAX_XML_UP = 12

    /** 接口方法 → 同名 XML 语句标签（含 `databaseId` 多方言变体；非接口/无命中返回空）。 */
    fun statementTagsFor(method: PsiMethod): List<XmlTag> {
        val owner = method.containingClass ?: return emptyList()
        // mapper 方法绑定经 JDK 动态代理，namespace 语义必为接口（与 gutter 同口径）
        if (!owner.isInterface) return emptyList()
        val namespace = owner.qualifiedName ?: return emptyList()
        val project = method.project
        if (DumbService.isDumb(project)) return emptyList()
        return MybatisXmlTargets.resolveStatementTags(project, namespace, method.name)
    }

    /** XML 语句标签 → namespace 接口的同名方法（非 mapper 文件/非语句标签/无命中返回 null）。 */
    fun methodFor(tag: XmlTag): PsiMethod? {
        if (!isTopLevelStatement(tag)) return null
        val file = tag.containingFile
        if (!MybatisXmlTargets.isMapperFile(file)) return null
        val namespace = MybatisXmlTargets.rootNamespace(file) ?: return null
        val id = tag.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: return null
        if (DumbService.isDumb(tag.project)) return null
        return MybatisXmlTargets.resolveInterfaceMethod(tag.project, namespace, id)
    }

    /**
     * Java → XML 的落点：优先语句 `id` 的**值 token**（光标精确落在 id 文本上；`XmlAttributeValue`
     * 的首个子节点是开引号，直接用会落在 `"` 上），取不到退属性值元素再退标签。
     */
    fun statementAnchor(tag: XmlTag): PsiElement {
        val value = tag.getAttribute("id")?.valueElement ?: return tag
        return PsiTreeUtil.findChildrenOfType(value, XmlToken::class.java)
            .firstOrNull { it.tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN }
            ?: value
    }

    /** 语句标签判定：四类语句名 + `<mapper>` 直接子级（片段 `<sql>`、嵌套动态标签一律排除）。 */
    fun isTopLevelStatement(tag: XmlTag): Boolean =
        tag.name in MybatisXmlTargets.STATEMENT_TAGS &&
            tag.parentTag?.name == "mapper" &&
            tag.parentTag?.parentTag == null

    /** 光标元素向上找到的 mapper 接口方法（普通类方法、方法体内引用均返回 null）。 */
    fun nearestMapperMethod(element: PsiElement): PsiMethod? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth++ < MAX_UP) {
            if (current is PsiMethod) {
                val owner = current.containingClass ?: return null
                return current.takeIf { owner.isInterface }
            }
            if (current is PsiFile) return null
            current = current.parent
        }
        return null
    }

    /** 光标元素向上找到的 mapper 语句标签（语句体内任意位置均可，供「关联符号」用）。 */
    fun nearestStatementTag(element: PsiElement): XmlTag? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth++ < MAX_XML_UP) {
            if (current is XmlTag && isTopLevelStatement(current)) return current
            if (current is PsiFile) return null
            current = current.parent
        }
        return null
    }

    /**
     * XML token → 所属语句标签，**只认三个落点**：起始标签名、`id` 属性名、`id` 属性值。
     * 其余位置（标签体内的 SQL 文本、其它属性、闭标签名）返回 null，避免劫持标签内 `#{}`/`property`
     * 等引用位置的 Ctrl+B。
     */
    fun ownerStatementTag(token: XmlToken): XmlTag? {
        val parent = token.parent
        val attribute: XmlAttribute? = when (parent) {
            is XmlAttribute -> parent
            is XmlAttributeValue -> parent.parent as? XmlAttribute
            else -> null
        }
        if (attribute != null) {
            if (attribute.name != "id") return null
            val tag = attribute.parent as? XmlTag ?: return null
            return tag.takeIf { isTopLevelStatement(it) }
        }
        val tag = parent as? XmlTag ?: return null
        // 闭标签名 token 的父也是同一个 XmlTag，用起始标签名元素排除
        if (XmlTagUtil.getStartTagNameElement(tag) !== token) return null
        return tag.takeIf { isTopLevelStatement(it) }
    }
}
