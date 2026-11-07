package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.w3c.dom.Element
import org.w3c.dom.Node

/** 语句类型（mapper 文件中的 select/insert/update/delete 元素）。 */
enum class XmlStatementKind { SELECT, INSERT, UPDATE, DELETE }

/** 解析出的语句（id + SqlNode 树 + parameterType 元信息）。 */
data class XmlStatement(
    val id: String,
    val kind: XmlStatementKind,
    val node: SqlNode,
    val parameterType: String?,
    /**
     * O-77 T-EwStatementScope：语句原文（include 展开后的 element.textContent，实体已解码，
     * 不含标签/属性/注释）——MP 占位按语句域扫描用。null＝非 mapper 解析链构造（注解 `<script>` 等），
     * 调用方回落文件全文。
     */
    val rawText: String? = null,
)

/** mapper 文件解析结果（语句清单 + 解析期错误）。 */
data class XmlMapperModel(
    val namespace: String?,
    val statements: List<XmlStatement>,
    val errors: List<String>,
    /**
     * F-06③：预算超限降级——本次**没有建语句树**，[errors] 只有「为什么没解析」与语句 id 清单，
     * [statements] 恒为空。调用方按既有 errors 通道展示即可，不要当成「mapper 里没有语句」。
     */
    val degraded: Boolean = false,
    /** F-06③：降级时文本扫描出的语句 id 清单（文档顺序，上限 [XmlParseBudget.MAX_ID_SCAN]）。 */
    val statementIds: List<String> = emptyList(),
)

/** 解析失败（非良构 XML / 根元素不是 mapper）。 */
class XmlParseException(message: String) : RuntimeException(message)

/** mapper 文件轻量片段扫描结果（T-IC-01）：根 namespace + 顶层 `<sql id>` 片段内部 XML 文本。 */
data class XmlMapperFragments(
    val namespace: String?,
    val fragments: Map<String, String>,
)

/** 当前 mapper 文本的 include 扫描结果（T-IC-02）：根 namespace + 本文件片段 id + 待跨命名空间解析的 refid。 */
data class XmlIncludeScan(
    val namespace: String?,
    val localFragmentIds: Set<String>,
    val dottedRefIds: Set<String>,
)

/**
 * mapper 文件解析器（T-M24-01）：JDK DOM 解析（安全配置唯一落点 [SecureXml]——官方 DTD 放行、
 * 外部实体拦死、内联子集膨胀预扫），提取 `<mapper>` 下的 select/insert/update/delete 语句。
 *
 * - 先收集 `<sql id>` 片段供 include 展开（[XmlIncludeExpander]，同文件）；
 * - 语句解析前展开 include（就地修改 DOM）；
 * - 根元素非 `<mapper>` → [XmlParseException]；非良构 XML → 异常带解析器错误信息。
 *
 * T-IC-02：入参 [CrossNamespaceFragments] 为跨命名空间片段表（由 psi 层
 * [cn.song.mybatisplus.visualizer.psi.XmlCrossNsFragmentResolver] 提供；无 project 的纯文本链传空表，
 * 行为与改动前逐字节一致）。
 */
class XmlMapperParser {

    fun parse(fileText: String, crossNamespace: CrossNamespaceFragments = CrossNamespaceFragments.EMPTY): XmlMapperModel {
        // F-06③：前置预算闸——超限不建树、不抛异常，直接给降级模型（调用方按 errors 通道展示）。
        // 判定只用文本扫描（字节数 + 开标签计数），成本远低于 DOM 建树。
        XmlParseBudget.check(fileText)?.let { return degradedModel(fileText, it) }
        val document = parseDocument(fileText)
        val root: Element = document.documentElement ?: throw XmlParseException("XML 文档为空。")
        if (root.tagName != "mapper") {
            throw XmlParseException("不是 MyBatis mapper 文件：根元素为 <${root.tagName}>（期望 <mapper>）。")
        }
        val errors = mutableListOf<String>()
        val namespaceRaw: String = root.getAttribute("namespace")
        val namespace = namespaceRaw.ifEmpty { null }

        val fragments: Map<String, Element> = childElements(root)
            .filter { it.tagName == "sql" && it.getAttribute("id").isNotEmpty() }
            .associateBy { it.getAttribute("id") }

        val statements = childElements(root)
            .filter { it.tagName in STATEMENT_TAGS }
            .map { element ->
                XmlIncludeExpander.expand(element, fragments, errors, crossNamespace)
                XmlStatement(
                    id = element.getAttribute("id"),
                    kind = kindOf(element.tagName),
                    node = XmlScriptBuilder().parse(element),
                    parameterType = element.getAttribute("parameterType").ifEmpty { null },
                    rawText = element.textContent,
                )
            }
        return XmlMapperModel(namespace, statements, errors)
    }

    /**
     * F-06③：预算超限的降级模型。
     *
     * 只做两件事：说清「为什么没解析」，列出文本扫描到的语句 id（截断到
     * [XmlParseBudget.MAX_ID_SCAN] 条）。不解析 DOM、不展开 include、不建语句树。
     */
    private fun degradedModel(fileText: String, check: XmlParseBudget.BudgetCheck): XmlMapperModel {
        val reason = when (check.violation) {
            XmlParseBudget.Violation.TEXT_TOO_LARGE -> MpSqlVisualizerBundle.message(
                "xml.parse.budget.text",
                megabytes(check.actual),
                megabytes(check.limit),
            )
            XmlParseBudget.Violation.TOO_MANY_NODES -> MpSqlVisualizerBundle.message(
                "xml.parse.budget.nodes",
                // 以字符串传参：MessageFormat 会给数字加千分位（5001 → 5,001），文案里要的是原值
                check.actual.toString(),
                check.limit.toString(),
            )
        }
        val ids = XmlParseBudget.scanStatementIds(fileText)
        val errors = if (ids.isEmpty()) {
            listOf(reason)
        } else {
            listOf(reason, MpSqlVisualizerBundle.message("xml.parse.budget.ids", ids.joinToString(", ")))
        }
        return XmlMapperModel(
            namespace = null,
            statements = emptyList(),
            errors = errors,
            degraded = true,
            statementIds = ids,
        )
    }

    private fun megabytes(bytes: Int): String =
        String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0)

    /**
     * T-IC-01：轻量片段收集——只扫顶层 `<sql id>` 片段（id → 片段内部 XML 文本），
     * **不建语句树、不展开 include、零 PSI 依赖**（跨命名空间解析的目标文件侧入口）。
     *
     * 根元素非 `<mapper>` 或文本非良构 → 空表（调用方按「未找到」降级，绝不抛出）。
     * 同名 id 取后声明者（对齐 MyBatis `configuration.sqlFragments.put` 覆盖语义）。
     */
    fun collectFragments(fileText: String): Map<String, String> =
        collectMapperFragments(fileText)?.fragments ?: emptyMap()

    /** 与 [collectFragments] 同一次解析：额外返回根 namespace（跨命名空间按 namespace 匹配目标文件）。 */
    internal fun collectMapperFragments(fileText: String): XmlMapperFragments? {
        val root = runCatching { parseDocument(fileText).documentElement }.getOrNull() ?: return null
        if (root.tagName != "mapper") return null
        val fragments = childElements(root)
            .filter { it.tagName == "sql" && it.getAttribute("id").isNotEmpty() }
            .associate { it.getAttribute("id") to serializeChildren(it) }
        return XmlMapperFragments(root.getAttribute("namespace").ifEmpty { null }, fragments)
    }

    /**
     * T-IC-02：扫描当前 mapper 文本——根 namespace、顶层片段 id 集、**含 `.` 且本文件无同名片段的**
     * include refid 集（refid 中的 `${name}` 已按 include 自带 property 静态替换，与展开期同语义）。
     *
     * 根元素非 `<mapper>` 或文本非良构 → null（调用方不做跨命名空间收集，沿用既有降级）。
     */
    internal fun scanIncludes(fileText: String): XmlIncludeScan? {
        val root = runCatching { parseDocument(fileText).documentElement }.getOrNull() ?: return null
        if (root.tagName != "mapper") return null
        val localIds = childElements(root)
            .filter { it.tagName == "sql" && it.getAttribute("id").isNotEmpty() }
            .mapTo(mutableSetOf()) { it.getAttribute("id") }
        val dotted = mutableSetOf<String>()
        collectDottedRefIds(root, localIds, dotted)
        return XmlIncludeScan(root.getAttribute("namespace").ifEmpty { null }, localIds, dotted)
    }

    private fun collectDottedRefIds(element: Element, localIds: Set<String>, out: MutableSet<String>) {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.tagName == "include") {
                val refid = XmlIncludeExpander.resolveRefid(
                    child.getAttribute("refid"),
                    XmlIncludeExpander.propertyMap(child),
                )
                if (refid.contains('.') && refid !in localIds) out += refid
            }
            collectDottedRefIds(child, localIds, out)
        }
    }

    /**
     * 片段内部 XML 文本序列化（T-IC-01）：保留子标签/属性/CDATA/注释，文本与属性值按 XML 规则转义，
     * 供跨命名空间传递后重新解析（平台 LSSerializer 对独立 CDATA 节点不写闭合 `]]>`，故手写）。
     */
    private fun serializeChildren(element: Element): String {
        val sb = StringBuilder()
        val children = element.childNodes
        for (i in 0 until children.length) appendNode(sb, children.item(i))
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: Node) {
        when (node.nodeType) {
            Node.ELEMENT_NODE -> {
                val element = node as Element
                sb.append('<').append(element.tagName)
                val attrs = element.attributes
                for (i in 0 until attrs.length) {
                    val attr = attrs.item(i)
                    sb.append(' ').append(attr.nodeName).append("=\"").append(escapeAttr(attr.nodeValue)).append('"')
                }
                val children = element.childNodes
                if (children.length == 0) {
                    sb.append("/>")
                } else {
                    sb.append('>')
                    for (i in 0 until children.length) appendNode(sb, children.item(i))
                    sb.append("</").append(element.tagName).append('>')
                }
            }
            Node.TEXT_NODE -> sb.append(escapeText(node.nodeValue))
            Node.CDATA_SECTION_NODE ->
                sb.append("<![CDATA[").append(node.nodeValue.orEmpty().replace("]]>", "]]]]><![CDATA[>")).append("]]>")
            Node.COMMENT_NODE -> sb.append("<!--").append(node.nodeValue.orEmpty()).append("-->")
            else -> Unit // 处理指令等 mapper 片段不出现的节点类型：忽略
        }
    }

    private fun escapeText(text: String?): String = text.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(text: String?): String = text.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        .replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;")

    /**
     * 解析 XML 文本为 DOM（安全配置见 [SecureXml]：T-XDH-01 配置收敛 + T-XDH-03 内联 DTD 膨胀预扫）。
     * 注解 `<script>` 等片段解析复用（T-M25-02）。
     */
    internal fun parseDocument(fileText: String): org.w3c.dom.Document =
        runCatching { SecureXml.parse(fileText) }
            .getOrElse { throw XmlParseException("XML 解析失败：${it.message}") }

    private fun childElements(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (i in 0 until children.length) {
            (children.item(i) as? Element)?.let { result += it }
        }
        return result
    }

    private fun kindOf(tagName: String): XmlStatementKind = when (tagName) {
        "select" -> XmlStatementKind.SELECT
        "insert" -> XmlStatementKind.INSERT
        "update" -> XmlStatementKind.UPDATE
        else -> XmlStatementKind.DELETE
    }

    companion object {
        val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")
    }
}
