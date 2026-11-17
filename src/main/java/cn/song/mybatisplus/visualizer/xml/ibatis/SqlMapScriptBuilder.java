package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code #PCDATA} + 动态标签 → iBatis 语义节点树（复刻 {@code XMLSqlSource.parseDynamicTags}）。
 *
 * <h2>三条硬规格</h2>
 * <ol>
 * <li><b>{@code <include>} 在配置期内联展开</b>：先按 {@code refid} 原样找，找不到再按
 *     {@code namespace + "." + refid} 找（仅当 refid 不含 {@code .}——引擎 {@code XmlParserState.applyNamespace}）；</li>
 * <li><b>{@code postParseRequired} 的传播</b>：{@code parent.isPostParseRequired() || kind == ITERATE}；
 *     而 <b>{@code <include>} 边界会把 postParseRequired 复位为 false</b>（引擎里 include 走形参 false）——
 *     即「被 include 进的片段即使位于 iterate 内，其 {@code #} 也在配置期解析」；</li>
 * <li><b>未知标签降级为「原文保留 + 告警」</b>，绝不抛异常（本链与 {@code xml/XmlScriptBuilder} 口径相反）。</li>
 * </ol>
 *
 * <p>{@code <selectKey>} 是<b>必须整体跳过</b>的元素：引擎的 {@code SqlTagHandlerFactory} 不认它，
 * 其内容不会进入宿主语句的 SQL（它由 {@code SqlStatementParser.findAndParseSelectKey} 单独装配成
 * {@code <insertId>-SelectKey} 语句）。若按通用「未知标签保留原文」处理，selectKey 的 SQL 会被
 * 错误拼进 insert 主体。
 */
public final class SqlMapScriptBuilder {

    private final Map<String, Element> fragments;
    @Nullable
    private final String namespace;
    private final Consumer<String> warn;

    private final SqlMapTokenParser tokenParser = new SqlMapTokenParser();

    /** 本次构建展开过的 {@code <sql id>}（去重，保持首次出现顺序）。 */
    public final LinkedHashSet<String> usedFragmentIds = new LinkedHashSet<>();

    public SqlMapScriptBuilder(Map<String, Element> fragments,
                               @Nullable String namespace,
                               Consumer<String> warn) {
        this.fragments = fragments;
        this.namespace = namespace;
        this.warn = warn;
    }

    public List<IbatisSqlNode> build(Element element) {
        return buildChildren(element, false);
    }

    private List<IbatisSqlNode> buildChildren(Element element, boolean postParseRequired) {
        List<IbatisSqlNode> result = new ArrayList<>();
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child == null) {
                continue;
            }
            short nodeType = child.getNodeType();
            if (nodeType == Node.TEXT_NODE || nodeType == Node.CDATA_SECTION_NODE) {
                result.add(textNode(child, postParseRequired));
            } else if (nodeType == Node.ELEMENT_NODE) {
                buildElement((Element) child, postParseRequired, result);
            }
        }
        return result;
    }

    private IbatisTextNode textNode(Node child, boolean postParseRequired) {
        String data = ((CharacterData) child).getData() != null ? ((CharacterData) child).getData() : "";
        if (data.contains("${")) {
            // 引擎在配置期用 NodeletUtils.parsePropertyTokens 把 ${key} 换成全局属性值
            //（无 <properties> 时退化为 key 本身，如 ${driver} -> driver）；本链不做该替换。
            warn.accept("语句文本含 ${...} 属性占位（引擎会按全局属性替换，本链按原文保留）：" + snippet(data));
        }
        if (postParseRequired) {
            return new IbatisTextNode(data, true);
        }
        SqlMapTokenParser.InlineParameterMapResult parsed = tokenParser.parseInlineParameterMap(sanitize(data));
        for (String warning : parsed.warnings()) {
            warn.accept(warning);
        }
        return new IbatisTextNode(data, false, parsed.text(), parsed.mappings());
    }

    private void buildElement(Element element, boolean postParseRequired, List<IbatisSqlNode> result) {
        String name = element.getNodeName();
        if ("include".equals(name)) {
            result.addAll(buildInclude(element, postParseRequired));
        } else if ("selectKey".equals(name)) {
            // selectKey 由宿主语句的解析流程单独装配，绝不能落入宿主体内（见类 KDoc 第 3 点）
        } else {
            IbatisTagKind kind = IbatisTagKind.byTagName(name);
            if (kind == null) {
                warn.accept("未识别的标签 <" + name + ">（按原文保留，引擎会静默丢弃该子树）");
                result.addAll(degradedChildren(element, postParseRequired));
                return;
            }
            result.add(buildTag(element, kind, postParseRequired));
        }
    }

    private IbatisTagNode buildTag(Element element, IbatisTagKind kind, boolean parentPostParse) {
        boolean tagPostParse = parentPostParse || kind == IbatisTagKind.ITERATE;
        List<IbatisSqlNode> childNodes = buildChildren(element, tagPostParse);
        IbatisTagNode node = new IbatisTagNode(
                kind,
                attribute(element, "prepend"),
                attribute(element, "property"),
                attribute(element, "removeFirstPrepend"),
                attribute(element, "open"),
                attribute(element, "close"),
                attribute(element, "conjunction"),
                attribute(element, "compareProperty"),
                attribute(element, "compareValue"),
                tagPostParse,
                childNodes);
        for (IbatisSqlNode child : childNodes) {
            if (child instanceof IbatisTagNode tagNode) {
                tagNode.attachParent(node);
            }
        }
        return node;
    }

    /** 未知标签降级：把它的<b>子内容</b>按文本节点收下（保持可渲染），而不是丢整棵子树。 */
    private List<IbatisSqlNode> degradedChildren(Element element, boolean postParseRequired) {
        return buildChildren(element, postParseRequired);
    }

    private List<IbatisSqlNode> buildInclude(Element element, boolean postParseRequired) {
        String refid = attribute(element, "refid");
        if (refid == null) {
            warn.accept("<include> 缺少 refid 属性（忽略该 include）");
            return List.of();
        }
        Element direct = fragments.get(refid);
        Element namespaced = direct == null ? fragments.get(applyNamespace(refid)) : null;
        Element fragment = direct != null ? direct : namespaced;
        if (fragment == null) {
            // 引擎此处抛 RuntimeException("Could not find SQL statement to include with refid '...'")
            warn.accept("找不到 <include refid=\"" + refid + "\"> 对应的 <sql> 片段（原文不保留，语句内容可能不完整）");
            return List.of();
        }
        usedFragmentIds.add(direct != null ? refid : applyNamespace(refid));
        // 引擎：include 边界 postParseRequired 复位为 false（见类 KDoc 第 2 点）
        return buildChildren(fragment, false);
    }

    /** 引擎 {@code XmlParserState.applyNamespace}：仅当 namespace 非空且 id 不含 {@code .} 时加前缀。 */
    private String applyNamespace(String id) {
        return namespace != null && !namespace.isEmpty() && id.indexOf('.') < 0
                ? namespace + "." + id : id;
    }

    private static String sanitize(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private static String snippet(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /**
     * 取属性原值：<b>缺失返回 null</b>（{@code Element.getAttribute} 对缺失属性返回空串，语义不同）。
     *
     * <p>master 侧为本包顶层函数，Java 侧并入本类静态方法（同包调用方一致走这里）。
     */
    @Nullable
    public static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }
}
