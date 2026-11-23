package cn.song.mybatisplus.visualizer.xml

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * include 展开器（T-M24-03）：复刻 MyBatis 3.5.19 `XMLIncludeTransformer.applyIncludes`（同文件形态）。
 *
 * - `<include refid="...">` 在**加载期**内联展开：深拷贝 `<sql id>` 片段的子节点到 include 位置；
 * - `<property name value>` 为**静态字符串替换**：只作用于片段中的 `${name}`（`#{}` 不替换），
 *   嵌套 include 继承外层 property（子 property 覆盖同名）；
 * - refid 支持 `${name}`（property 解析后查表）；
 * - 跨命名空间 refid（含 `.` 且本文件无此片段）→ 查 [CrossNamespaceFragments]（T-IC-02）：
 *   命中则按目标文件片段文本展开（跨文件），未命中或解析失败 → 错误记录并移除 include（预览不中断）；
 * - 自引用/过深嵌套 → 错误记录（深度上限 [MAX_DEPTH]）。
 *
 * MyBatis 语义对齐：片段表键为全限定 `namespace.id`，而 refid 不含 `.` 时补当前 namespace——
 * 本展开器以「本文件片段表键为裸 id + 跨命名空间表键为全限定 refid」等价表达：
 * 裸 refid 命中本文件，含 `.` 的 refid 命中跨文件表；跨文件片段内的**裸** refid 仍按引用方本文件查表
 * （对齐 MyBatis 给裸 refid 补当前 namespace 的语义；该片段只存在于同 namespace 的**其它**文件时降级，
 * v1 边界，见 `psi/XmlCrossNsFragmentResolver` KDoc）。
 */
object XmlIncludeExpander {

    const val MAX_DEPTH = 20

    /** 跨命名空间片段文本重新解析用的包装根元素名（片段文本可能含多个顶层节点）。 */
    private const val FRAGMENT_WRAPPER = "mpv-cross-ns-fragment"

    /** 展开语句元素中的全部 include（就地修改 DOM）。 */
    fun expand(
        statement: Element,
        fragments: Map<String, Element>,
        errors: MutableList<String>,
        crossNamespace: CrossNamespaceFragments = CrossNamespaceFragments.EMPTY,
    ) {
        expandRecursive(statement, fragments, errors, 0, emptySet(), crossNamespace)
    }

    /**
     * 递归展开：入口先检查自身是否 include（嵌套 include 的拷贝体会以 include 元素身份进入）；
     * 同一 refid 在当前展开链上重复出现 → 循环截断（移除节点 + 错误记录，不残留）。
     */
    private fun expandRecursive(
        element: Element,
        fragments: Map<String, Element>,
        errors: MutableList<String>,
        depth: Int,
        chain: Set<String>,
        crossNamespace: CrossNamespaceFragments,
    ) {
        if (element.tagName == "include") {
            if (depth > MAX_DEPTH) {
                errors += "Include nesting too deep (possible include cycle)."
                element.parentNode?.removeChild(element)
                return
            }
            val props = propertyMap(element)
            val refid = resolveRefid(element.getAttribute("refid"), props)
            if (refid in chain) {
                errors += "Include cycle detected: '$refid'."
                element.parentNode?.removeChild(element)
                return
            }
            val target = fragments[refid]
            if (target == null) {
                val external = crossNamespace.fragments[refid]
                if (external != null) {
                    val copies = externalChildren(element, external, props, errors)
                    if (copies == null) {
                        replaceWithMissingPlaceholder(element, refid)
                        return
                    }
                    replaceNode(element, copies)
                    copies.filterIsInstance<Element>().forEach {
                        expandRecursive(it, fragments, errors, depth + 1, chain + refid, crossNamespace)
                    }
                    return
                }
                val reason = crossNamespace.failures[refid]
                errors += if (reason != null) {
                    "Include refid '$refid' not found in this mapper（跨命名空间片段解析失败：$reason）。"
                } else {
                    "Include refid '$refid' not found in this mapper (cross-namespace include is not supported)."
                }
                // T-IncludePlaceholder（MyBatis 侧对齐 iBatis）：整段抹掉会渲染出「SELECT 后面直接接
                // FROM」的残句。改留一条注释占位——结构完整可读、复制出去也能看出缺哪一段；
                // 注释不含 `#{}`，占位符位点与参数映射均不受影响。
                replaceWithMissingPlaceholder(element, refid)
                return
            }
            val copies = copyChildren(target, props)
            replaceNode(element, copies)
            copies.filterIsInstance<Element>()
                .forEach { expandRecursive(it, fragments, errors, depth + 1, chain + refid, crossNamespace) }
            return
        }
        // 快照遍历（展开会修改子节点集合）
        val children = element.childNodes
        val snapshot = (0 until children.length).map { children.item(it) }
        for (child in snapshot) {
            if (child is Element) expandRecursive(child, fragments, errors, depth + 1, chain, crossNamespace)
        }
    }

    internal fun propertyMap(include: Element): Map<String, String> {
        val props = mutableMapOf<String, String>()
        val children = include.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == "property") {
                val name = child.getAttribute("name")
                if (name.isNotEmpty()) props[name] = child.getAttribute("value")
            }
        }
        return props
    }

    /** refid 中的 `${name}` 按 property 静态替换（不在 property 中的保留原样，后续查表必然失败）。 */
    internal fun resolveRefid(refid: String, props: Map<String, String>): String =
        TokenParser.parse(refid, "\${", "}") { props[it] ?: "\${$it}" }

    /**
     * 跨命名空间片段（T-IC-02）：目标 mapper 片段内部 XML 文本 → 当前语句所在文档的节点。
     *
     * 片段文本来自其它文件的 DOM 序列化，属**另一文档**，必须经 [Document.importNode] 才能插入本文档；
     * 解析失败（片段文本非良构等）→ 错误记录并返回 null（调用方移除 include，预览不中断）。
     * property 静态替换在拷贝体上施加，语义与同文件展开一致。
     */
    private fun externalChildren(
        include: Element,
        innerXml: String,
        props: Map<String, String>,
        errors: MutableList<String>,
    ): List<Node>? {
        val owner = include.ownerDocument ?: return null
        val wrapper = "<$FRAGMENT_WRAPPER>$innerXml</$FRAGMENT_WRAPPER>"
        val root = runCatching { XmlMapperParser().parseDocument(wrapper).documentElement }.getOrNull()
        if (root == null) {
            errors += "Include refid '${include.getAttribute("refid")}' 跨命名空间片段解析失败：片段文本无法解析为 XML。"
            return null
        }
        val result = mutableListOf<Node>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val copy = owner.importNode(children.item(i), true)
            applyProperties(copy, props)
            result += copy
        }
        return result
    }

    /** 深拷贝片段子节点：文本节点与属性值中的 `${name}` 按 property 替换（不污染原始片段）。 */
    private fun copyChildren(target: Element, props: Map<String, String>): List<Node> {
        val result = mutableListOf<Node>()
        val children = target.childNodes
        for (i in 0 until children.length) {
            val copy = children.item(i).cloneNode(true)
            applyProperties(copy, props)
            result += copy
        }
        return result
    }

    private fun applyProperties(node: Node, props: Map<String, String>) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                node.nodeValue = replaceProps(node.nodeValue, props)
            Node.ELEMENT_NODE -> {
                val element = node as Element
                val attributes = element.attributes
                for (j in 0 until attributes.length) {
                    val attr = attributes.item(j)
                    attr.nodeValue = replaceProps(attr.nodeValue, props)
                }
                val children = element.childNodes
                for (k in 0 until children.length) {
                    applyProperties(children.item(k), props)
                }
            }
        }
    }

    private fun replaceProps(text: String?, props: Map<String, String>): String =
        if (text == null) ""
        else TokenParser.parse(text, "\${", "}") { props[it] ?: "\${$it}" }

    /** 用拷贝节点替换 include 节点（保持顺序；空片段 → 直接移除 include）。 */
    private fun replaceNode(include: Element, copies: List<Node>) {
        val parent = include.parentNode ?: return
        var anchor: Node = include
        copies.forEachIndexed { i, copy ->
            if (i == 0) parent.replaceChild(copy, anchor)
            else parent.insertBefore(copy, anchor.nextSibling)
            anchor = copy
        }
        if (copies.isEmpty()) parent.removeChild(include)
    }

    /**
     * T-IncludePlaceholder：缺失片段处留注释占位（与 iBatis 侧同形）。
     *
     * 为什么不是整段移除：移除后渲染出来是「SELECT 后面直接接 FROM」的残句，用户看不出缺什么；
     * 留注释既保住结构，也让「复制 SQL」的产物自带缺口标记。注释里不含 `#{}`，占位符位点与
     * 参数映射完全不受影响（缺的片段本来就不参与本次渲染）。
     */
    private fun replaceWithMissingPlaceholder(include: Element, refid: String) {
        val parent = include.parentNode ?: return
        val text = include.ownerDocument.createTextNode("/* TODO: missing <sql id=\"$refid\"> */")
        parent.replaceChild(text, include)
    }
}
