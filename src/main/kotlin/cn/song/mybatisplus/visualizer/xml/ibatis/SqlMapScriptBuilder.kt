package cn.song.mybatisplus.visualizer.xml.ibatis

import org.w3c.dom.CharacterData
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * `#PCDATA` + 动态标签 → iBatis 语义节点树（复刻 `XMLSqlSource.parseDynamicTags`）。
 *
 * 三条硬规格：
 * 1. **`<include>` 在配置期内联展开**：先按 `refid` 原样找，找不到再按 `namespace + "." + refid` 找
 *    （仅当 `refid` 不含 `.`——引擎 `XmlParserState.applyNamespace`）；
 * 2. **`postParseRequired` 的传播**：`parent.isPostParseRequired() || handler is IterateTagHandler`；
 *    而 **`<include>` 边界会把 `postParseRequired` 复位为 false**（引擎里 include 走形参 `false`）——
 *    即「被 include 进的片段即使位于 iterate 内，其 `#` 也在配置期解析」；
 * 3. **未知标签降级为「原文保留 + 告警」**，绝不抛异常（本链与 `xml/XmlScriptBuilder.kt:42` 口径相反）。
 *
 * `<selectKey>` 是**必须整体跳过**的元素：引擎的 `SqlTagHandlerFactory` 不认它，其内容不会进入宿主
 * 语句的 SQL（它由 `SqlStatementParser.findAndParseSelectKey` 单独装配成 `<insertId>-SelectKey` 语句）。
 * 若按通用「未知标签保留原文」处理，selectKey 的 SQL 会被错误拼进 insert 主体。
 */
internal class SqlMapScriptBuilder(
    private val fragments: Map<String, Element>,
    private val namespace: String?,
    private val warn: (String) -> Unit,
) {

    private val tokenParser = SqlMapTokenParser()

    /** 本次构建展开过的 `<sql id>`（去重，保持首次出现顺序）。 */
    val usedFragmentIds = LinkedHashSet<String>()

    fun build(element: Element): List<IbatisSqlNode> = buildChildren(element, false)

    private fun buildChildren(element: Element, postParseRequired: Boolean): List<IbatisSqlNode> {
        val result = ArrayList<IbatisSqlNode>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) ?: continue
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> result.add(textNode(child, postParseRequired))
                Node.ELEMENT_NODE -> buildElement(child as Element, postParseRequired, result)
                else -> Unit
            }
        }
        return result
    }

    private fun textNode(child: Node, postParseRequired: Boolean): IbatisTextNode {
        val data = (child as CharacterData).data ?: ""
        if (data.contains("\${")) {
            // 引擎在配置期用 NodeletUtils.parsePropertyTokens 把 ${key} 换成全局属性值
            //（无 <properties> 时退化为 key 本身，如 ${driver} -> driver）；本链不做该替换。
            warn("语句文本含 \${...} 属性占位（引擎会按全局属性替换，本链按原文保留）：${snippet(data)}")
        }
        if (postParseRequired) return IbatisTextNode(data, postParseRequired = true)
        val parsed = tokenParser.parseInlineParameterMap(sanitize(data))
        parsed.warnings.forEach(warn)
        return IbatisTextNode(data, postParseRequired = false, parsedText = parsed.text, mappings = parsed.mappings)
    }

    private fun buildElement(element: Element, postParseRequired: Boolean, result: MutableList<IbatisSqlNode>) {
        val name = element.nodeName
        when {
            name == "include" -> result.addAll(buildInclude(element, postParseRequired))
            // selectKey 由宿主语句的解析流程单独装配，绝不能落入宿主体内（见类 KDoc 第 3 点）
            name == "selectKey" -> Unit
            else -> {
                val kind = IbatisTagKind.byTagName(name)
                if (kind == null) {
                    warn("未识别的标签 <$name>（按原文保留，引擎会静默丢弃该子树）")
                    result.addAll(degradedChildren(element, postParseRequired))
                    return
                }
                result.add(buildTag(element, kind, postParseRequired))
            }
        }
    }

    private fun buildTag(element: Element, kind: IbatisTagKind, parentPostParse: Boolean): IbatisTagNode {
        val tagPostParse = parentPostParse || kind == IbatisTagKind.ITERATE
        val childNodes = buildChildren(element, tagPostParse)
        val node = IbatisTagNode(
            kind = kind,
            prepend = attribute(element, "prepend"),
            property = attribute(element, "property"),
            removeFirstPrepend = attribute(element, "removeFirstPrepend"),
            open = attribute(element, "open"),
            close = attribute(element, "close"),
            conjunction = attribute(element, "conjunction"),
            compareProperty = attribute(element, "compareProperty"),
            compareValue = attribute(element, "compareValue"),
            postParseRequired = tagPostParse,
            children = childNodes,
        )
        childNodes.forEach { if (it is IbatisTagNode) it.attachParent(node) }
        return node
    }

    /** 未知标签降级：把它的**子内容**按文本节点收下（保持可渲染），而不是丢整棵子树。 */
    private fun degradedChildren(element: Element, postParseRequired: Boolean): List<IbatisSqlNode> =
        buildChildren(element, postParseRequired)

    private fun buildInclude(element: Element, postParseRequired: Boolean): List<IbatisSqlNode> {
        val refid = attribute(element, "refid")
        if (refid == null) {
            warn("<include> 缺少 refid 属性（忽略该 include）")
            return emptyList()
        }
        val direct = fragments[refid]
        val namespaced = if (direct == null) fragments[applyNamespace(refid)] else null
        val fragment = direct ?: namespaced
        if (fragment == null) {
            // 引擎此处抛 RuntimeException("Could not find SQL statement to include with refid '...'")
            warn("找不到 <include refid=\"$refid\"> 对应的 <sql> 片段（原文不保留，语句内容可能不完整）")
            return emptyList()
        }
        usedFragmentIds.add(if (direct != null) refid else applyNamespace(refid))
        // 引擎：include 边界 postParseRequired 复位为 false（见类 KDoc 第 2 点）
        return buildChildren(fragment, false)
    }

    /** 引擎 `XmlParserState.applyNamespace`：仅当 namespace 非空且 id 不含 `.` 时加前缀。 */
    private fun applyNamespace(id: String): String =
        if (!namespace.isNullOrEmpty() && id.indexOf('.') < 0) "$namespace.$id" else id

    private fun sanitize(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')

    private fun snippet(text: String): String =
        if (text.length <= 40) text else text.substring(0, 40) + "…"
}

/** 取属性原值：**缺失返回 null**（`Element.getAttribute` 对缺失属性返回空串，语义不同）。 */
internal fun attribute(element: Element, name: String): String? =
    if (element.hasAttribute(name)) element.getAttribute(name) else null
