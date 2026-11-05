package cn.song.mybatisplus.visualizer.xml

import org.w3c.dom.Element

/** maintainPage 语句（id + 元数据 + SQL + 列信息） */
data class PagesStatement(
    val id: String,
    val title: String,
    val autoQuery: Boolean,
    val entityClass: String,
    val sql: String,
    val columns: List<PagesColumn>,
    /**
     * T-PagesHql：`<datasource type="...">` 显式声明（hql / jpql / sql）；缺省 null。
     * 部分框架版本用它标注源语言，是最权威的判定证据。
     */
    val declaredType: String? = null,
)

/** 列元数据 */
data class PagesColumn(
    val id: String,
    val title: String,
    val dataType: String,
    val visible: Boolean,
    val sortable: Boolean,
)

/** <pages> 文件解析结果 */
data class PagesModel(
    val statements: List<PagesStatement>,
)

/** 非 <pages> 根元素或解析失败 */
class PagesXmlParseException(message: String) : RuntimeException(message)

/**
 * `<pages>` XML 解析器：解析根元素下的页面语句清单。
 *
 * - 根元素必须是 `<pages>`（否则抛 [PagesXmlParseException]）；
 * - **页面标签名不固定**（自研框架各家不同：maintainPage / listPage / queryPage …）：
 *   判定口径 = 根的直接子元素中「带 `<datasource>` 子元素」者；历史上没有 datasource 的
 *   `<maintainPage/>` 仍按兼容口径收下（sql 为空）；
 * - 提取 id/title/autoQuery/entityClass 属性（id 缺省回落标签名）；
 * - `<datasource>` CDATA → sql（trim）；`<columns>/<column>` → 列元数据列表。
 */
class PagesXmlParser {

    fun parse(fileText: String): PagesModel {
        val document = parseDocument(fileText)
        val root: Element = document.documentElement ?: throw PagesXmlParseException("XML 文档为空。")
        if (root.tagName != "pages") {
            throw PagesXmlParseException("不是 pages 文件：根元素为 <${root.tagName}>（期望 <pages>）。")
        }
        val statements = childElements(root)
            .filter { isStatementElement(it) }
            .map { parseStatement(it) }
        return PagesModel(statements)
    }

    /** 页面语句判定：带 `<datasource>` 子元素，或历史兼容的 `<maintainPage/>`（无 datasource）。 */
    private fun isStatementElement(element: Element): Boolean =
        element.tagName == "maintainPage" || childElements(element).any { it.tagName == "datasource" }

    private fun parseStatement(element: Element): PagesStatement {
        // id 缺省回落标签名：gutter/Action 对无 id 的页面仍能按「同标签首个」定位
        val id = element.getAttribute("id").ifBlank { element.tagName }
        val title = element.getAttribute("title").ifBlank { element.tagName }
        val autoQuery = element.getAttribute("autoQuery") == "true"
        val entityClass = element.getAttribute("entityClass")
        // <datasource> CDATA → sql
        val datasourceTag = childElements(element).firstOrNull { it.tagName == "datasource" }
        val sql = datasourceTag?.textContent?.trim().orEmpty()
        // T-PagesHql：可选 type 属性（hql / jpql / sql）
        val declaredType = datasourceTag?.getAttribute("type")?.trim()?.takeIf { it.isNotEmpty() }
        // <columns>/<column> → 列元数据
        val columnsTag = childElements(element).firstOrNull { it.tagName == "columns" }
        val columns = columnsTag?.let { parseColumns(it) } ?: emptyList()
        return PagesStatement(id, title, autoQuery, entityClass, sql, columns, declaredType)
    }

    private fun parseColumns(columnsTag: Element): List<PagesColumn> =
        childElements(columnsTag)
            .filter { it.tagName == "column" }
            .map { element ->
                PagesColumn(
                    id = element.getAttribute("id"),
                    title = element.getAttribute("title"),
                    dataType = element.getAttribute("dataType"),
                    visible = element.getAttribute("visible") == "true",
                    sortable = element.getAttribute("sortable") == "true",
                )
            }

    /** 解析 XML 文本为 DOM（安全配置见 [SecureXml]：与 mapper 解析器同一实现，含膨胀预扫）。 */
    internal fun parseDocument(fileText: String): org.w3c.dom.Document =
        runCatching { SecureXml.parse(fileText) }
            .getOrElse { throw PagesXmlParseException("XML 解析失败：${it.message}") }

    private fun childElements(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (i in 0 until children.length) {
            (children.item(i) as? Element)?.let { result += it }
        }
        return result
    }
}
