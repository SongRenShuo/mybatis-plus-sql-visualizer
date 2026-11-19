package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * include 展开器（T-M24-03）：复刻 MyBatis 3.5.19 XMLIncludeTransformer.applyIncludes（同文件形态）。
 *
 * - <include refid="..."> 在加载期内联展开：深拷贝 <sql id> 片段的子节点到 include 位置；
 * - <property name value> 为静态字符串替换：只作用于片段中的 ${name}（#{} 不替换），
 *   嵌套 include 继承外层 property（子 property 覆盖同名）；
 * - refid 支持 ${name}（property 解析后查表）；
 * - 跨命名空间 refid（含 {@code .} 且本文件无此片段）→ 查 {@link CrossNamespaceFragments}（T-IC-02）：
 *   命中则按目标文件片段文本展开（跨文件），未命中或解析失败 → 错误记录并移除 include（预览不中断）；
 * - 自引用/过深嵌套 → 错误记录（深度上限 {@link #MAX_DEPTH}）。
 *
 * MyBatis 语义对齐：片段表键为全限定 {@code namespace.id}，而 refid 不含 {@code .} 时补当前 namespace——
 * 本展开器以「本文件片段表键为裸 id + 跨命名空间表键为全限定 refid」等价表达：
 * 裸 refid 命中本文件，含 {@code .} 的 refid 命中跨文件表；跨文件片段内的<b>裸</b> refid 仍按引用方本文件查表
 * （对齐 MyBatis 给裸 refid 补当前 namespace 的语义；该片段只存在于同 namespace 的<b>其它</b>文件时降级，
 * v1 边界，见 {@code psi/XmlCrossNsFragmentResolver} KDoc）。
 */
public final class XmlIncludeExpander {

    public static final XmlIncludeExpander INSTANCE = new XmlIncludeExpander();

    /** 嵌套深度上限。 */
    public static final int MAX_DEPTH = 20;

    /** 跨命名空间片段文本重新解析用的包装根元素名（片段文本可能含多个顶层节点）。 */
    private static final String FRAGMENT_WRAPPER = "mpv-cross-ns-fragment";

    private XmlIncludeExpander() {
    }

    /** 展开语句元素中的全部 include（就地修改 DOM）。 */
    public static void expand(
            Element statement,
            Map<String, Element> fragments,
            List<String> errors,
            CrossNamespaceFragments crossNamespace
    ) {
        expandRecursive(statement, fragments, errors, 0, Set.of(), crossNamespace);
    }

    /** 展开语句元素中的全部 include（就地修改 DOM；crossNamespace 缺省为空表 = 不做跨命名空间解析）。 */
    public static void expand(Element statement, Map<String, Element> fragments, List<String> errors) {
        expand(statement, fragments, errors, CrossNamespaceFragments.EMPTY);
    }

    private static void expandRecursive(
            Element element,
            Map<String, Element> fragments,
            List<String> errors,
            int depth,
            Set<String> chain,
            CrossNamespaceFragments crossNamespace
    ) {
        if (element.getTagName().equals("include")) {
            if (depth > MAX_DEPTH) {
                errors.add("Include nesting too deep (possible include cycle).");
                if (element.getParentNode() != null) {
                    element.getParentNode().removeChild(element);
                }
                return;
            }
            Map<String, String> props = propertyMap(element);
            String refid = resolveRefid(element.getAttribute("refid"), props);
            if (chain.contains(refid)) {
                errors.add("Include cycle detected: '" + refid + "'.");
                if (element.getParentNode() != null) {
                    element.getParentNode().removeChild(element);
                }
                return;
            }
            Element target = fragments.get(refid);
            if (target == null) {
                String external = crossNamespace.fragments().get(refid);
                if (external != null) {
                    List<Node> copies = externalChildren(element, external, props, errors);
                    if (copies == null) {
                        replaceWithMissingPlaceholder(element, refid);
                        return;
                    }
                    replaceNode(element, copies);
                    for (Node copy : copies) {
                        if (copy instanceof Element e) {
                            expandRecursive(e, fragments, errors, depth + 1, addToSet(chain, refid), crossNamespace);
                        }
                    }
                    return;
                }
                String reason = crossNamespace.failures().get(refid);
                errors.add(reason != null
                        ? "Include refid '" + refid + "' not found in this mapper（跨命名空间片段解析失败：" + reason + "）。"
                        : "Include refid '" + refid + "' not found in this mapper (cross-namespace include is not supported).");
                // T-IncludePlaceholder（MyBatis 侧对齐 iBatis）：整段抹掉会渲染出「SELECT 后面直接接
                // FROM」的残句。改留一条注释占位——结构完整可读、复制出去也能看出缺哪一段；
                // 注释不含 `#{}`，占位符位点与参数映射均不受影响。
                replaceWithMissingPlaceholder(element, refid);
                return;
            }
            List<Node> copies = copyChildren(target, props);
            replaceNode(element, copies);
            for (Node copy : copies) {
                if (copy instanceof Element e) {
                    expandRecursive(e, fragments, errors, depth + 1, addToSet(chain, refid), crossNamespace);
                }
            }
            return;
        }
        // 快照遍历（展开会修改子节点集合）
        NodeList children = element.getChildNodes();
        List<Node> snapshot = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            snapshot.add(children.item(i));
        }
        for (Node child : snapshot) {
            if (child instanceof Element e) {
                expandRecursive(e, fragments, errors, depth + 1, chain, crossNamespace);
            }
        }
    }

    public static Map<String, String> propertyMap(Element include) {
        Map<String, String> props = new HashMap<>();
        NodeList children = include.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element e && e.getTagName().equals("property")) {
                String name = e.getAttribute("name");
                if (!name.isEmpty()) {
                    props.put(name, e.getAttribute("value"));
                }
            }
        }
        return props;
    }

    /** refid 中的 ${name} 按 property 静态替换（不在 property 中的保留原样，后续查表必然失败）。 */
    public static String resolveRefid(String refid, Map<String, String> props) {
        return TokenParser.parse(refid, "${", "}", s -> props.getOrDefault(s, "${" + s + "}"));
    }

    /**
     * 跨命名空间片段（T-IC-02）：目标 mapper 片段内部 XML 文本 → 当前语句所在文档的节点。
     *
     * 片段文本来自其它文件的 DOM 序列化，属<b>另一文档</b>，必须经 {@link Document#importNode} 才能插入本文档；
     * 解析失败（片段文本非良构等）→ 错误记录并返回 null（调用方移除 include，预览不中断）。
     * property 静态替换在拷贝体上施加，语义与同文件展开一致。
     */
    private static List<Node> externalChildren(
            Element include,
            String innerXml,
            Map<String, String> props,
            List<String> errors
    ) {
        Document owner = include.getOwnerDocument();
        if (owner == null) {
            return null;
        }
        String wrapper = "<" + FRAGMENT_WRAPPER + ">" + innerXml + "</" + FRAGMENT_WRAPPER + ">";
        Element root;
        try {
            root = new XmlMapperParser().parseDocument(wrapper).getDocumentElement();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            root = null;
        }
        if (root == null) {
            errors.add("Include refid '" + include.getAttribute("refid") + "' 跨命名空间片段解析失败：片段文本无法解析为 XML。");
            return null;
        }
        List<Node> result = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node copy = owner.importNode(children.item(i), true);
            applyProperties(copy, props);
            result.add(copy);
        }
        return result;
    }

    /** 深拷贝片段子节点：文本节点与属性值中的 ${name} 按 property 替换（不污染原始片段）。 */
    private static List<Node> copyChildren(Element target, Map<String, String> props) {
        List<Node> result = new ArrayList<>();
        NodeList children = target.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node copy = children.item(i).cloneNode(true);
            applyProperties(copy, props);
            result.add(copy);
        }
        return result;
    }

    private static void applyProperties(Node node, Map<String, String> props) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            node.setNodeValue(replaceProps(node.getNodeValue(), props));
        } else if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element element = (Element) node;
            NamedNodeMap attributes = element.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Node attr = attributes.item(j);
                attr.setNodeValue(replaceProps(attr.getNodeValue(), props));
            }
            NodeList children = element.getChildNodes();
            for (int k = 0; k < children.getLength(); k++) {
                applyProperties(children.item(k), props);
            }
        }
    }

    private static String replaceProps(String text, Map<String, String> props) {
        if (text == null) {
            return "";
        }
        return TokenParser.parse(text, "${", "}", s -> props.getOrDefault(s, "${" + s + "}"));
    }

    /** 用拷贝节点替换 include 节点（保持顺序；空片段 → 直接移除 include）。 */
    private static void replaceNode(Element include, List<Node> copies) {
        Node parent = include.getParentNode();
        if (parent == null) {
            return;
        }
        Node anchor = include;
        for (int i = 0; i < copies.size(); i++) {
            Node copy = copies.get(i);
            if (i == 0) {
                parent.replaceChild(copy, anchor);
            } else {
                parent.insertBefore(copy, anchor.getNextSibling());
            }
            anchor = copy;
        }
        if (copies.isEmpty()) {
            parent.removeChild(include);
        }
    }

    /**
     * T-IncludePlaceholder：缺失片段处留注释占位（与 iBatis 侧同形）。
     *
     * <p>为什么不是整段移除：移除后渲染出来是「SELECT 后面直接接 FROM」的残句，用户看不出缺什么；
     * 留注释既保住结构，也让「复制 SQL」的产物自带缺口标记。注释里不含 {@code #{} }，占位符位点与
     * 参数映射完全不受影响（缺的片段本来就不参与本次渲染）。
     */
    private static void replaceWithMissingPlaceholder(Element include, String refid) {
        Node parent = include.getParentNode();
        if (parent == null) {
            return;
        }
        String text = "/* TODO: missing <sql id=\"" + refid + "\"> */";
        parent.replaceChild(include.getOwnerDocument().createTextNode(text), include);
    }

    private static Set<String> addToSet(Set<String> chain, String refid) {
        Set<String> next = new HashSet<>(chain);
        next.add(refid);
        return next;
    }
}
