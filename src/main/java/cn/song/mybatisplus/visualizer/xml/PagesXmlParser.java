package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * {@code <pages>} XML 解析器：解析根元素下的页面语句清单。
 *
 * <ul>
 * <li>根元素必须是 {@code <pages>}（否则抛 {@link PagesXmlParseException}）；</li>
 * <li><b>页面标签名不固定</b>（自研框架各家不同：maintainPage / listPage / queryPage …）：
 *     判定口径 = 根的直接子元素中「带 {@code <datasource>} 子元素」者；历史上没有 datasource 的
 *     {@code <maintainPage/>} 仍按兼容口径收下（sql 为空）；</li>
 * <li>提取 id/title/autoQuery/entityClass 属性（id 缺省回落标签名）；</li>
 * <li>{@code <datasource>} CDATA → sql（trim）；{@code <columns>/<column>} → 列元数据列表。</li>
 * </ul>
 */
public class PagesXmlParser {

    public PagesModel parse(String fileText) {
        Document document = parseDocument(fileText);
        Element root = document.getDocumentElement();
        if (root == null) {
            throw new PagesXmlParseException("XML 文档为空。");
        }
        if (!root.getTagName().equals("pages")) {
            throw new PagesXmlParseException("不是 pages 文件：根元素为 <" + root.getTagName() + ">（期望 <pages>）。");
        }
        List<PagesStatement> statements = childElements(root).stream()
                .filter(PagesXmlParser::isStatementElement)
                .map(PagesXmlParser::parseStatement)
                .toList();
        return new PagesModel(statements);
    }

    /** 页面语句判定：带 {@code <datasource>} 子元素，或历史兼容的 {@code <maintainPage/>}（无 datasource）。 */
    private static boolean isStatementElement(Element element) {
        if (element.getTagName().equals("maintainPage")) {
            return true;
        }
        return childElements(element).stream().anyMatch(it -> it.getTagName().equals("datasource"));
    }

    private static PagesStatement parseStatement(Element element) {
        // id 缺省回落标签名：gutter/Action 对无 id 的页面仍能按「同标签首个」定位
        String id = element.getAttribute("id").isBlank() ? element.getTagName() : element.getAttribute("id");
        String title = element.getAttribute("title").isBlank() ? element.getTagName() : element.getAttribute("title");
        boolean autoQuery = "true".equals(element.getAttribute("autoQuery"));
        String entityClass = element.getAttribute("entityClass");
        // <datasource> CDATA → sql
        Element datasourceTag = childElements(element).stream()
                .filter(it -> it.getTagName().equals("datasource"))
                .findFirst()
                .orElse(null);
        String sql = datasourceTag != null && datasourceTag.getTextContent() != null
                ? datasourceTag.getTextContent().trim() : "";
        // T-PagesHql：可选 type 属性（hql / jpql / sql）
        String declaredType = null;
        if (datasourceTag != null) {
            String raw = datasourceTag.getAttribute("type").trim();
            declaredType = raw.isEmpty() ? null : raw;
        }
        // <columns>/<column> → 列元数据
        Element columnsTag = childElements(element).stream()
                .filter(it -> it.getTagName().equals("columns"))
                .findFirst()
                .orElse(null);
        List<PagesColumn> columns = columnsTag != null ? parseColumns(columnsTag) : List.of();
        return new PagesStatement(id, title, autoQuery, entityClass, sql, columns, declaredType);
    }

    private static List<PagesColumn> parseColumns(Element columnsTag) {
        return childElements(columnsTag).stream()
                .filter(it -> it.getTagName().equals("column"))
                .map(element -> new PagesColumn(
                        element.getAttribute("id"),
                        element.getAttribute("title"),
                        element.getAttribute("dataType"),
                        "true".equals(element.getAttribute("visible")),
                        "true".equals(element.getAttribute("sortable"))
                ))
                .toList();
    }

    /** 解析 XML 文本为 DOM（安全配置见 {@link SecureXml}：与 mapper 解析器同一实现，含膨胀预扫）。 */
    Document parseDocument(String fileText) {
        try {
            return SecureXml.parse(fileText);
        } catch (Throwable t) {
            throw new PagesXmlParseException("XML 解析失败：" + t.getMessage());
        }
    }

    private static List<Element> childElements(Element element) {
        List<Element> result = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e) {
                result.add(e);
            }
        }
        return result;
    }
}
