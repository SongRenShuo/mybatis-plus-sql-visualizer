// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/SqlMapParser.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.xml.SecureXml;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParameterDef;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParameterMap;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParseException;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSelectKey;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSqlMap;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSqlNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatement;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatementKind;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagNode;
import kotlin.jvm.functions.Function1;

import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code <sqlMap>} 文件解析器（T-Ibatis2Support 券 B）。
 *
 * <p>解析入口<b>强制复用</b> {@code xml/SecureXml}（全仓唯一 {@code DocumentBuilder} 工厂，含膨胀预扫与
 * 外部 DTD 拦空）——不自建 {@code DocumentBuilderFactory}，与 {@code xml/XmlMapperParser} /
 * {@code xml/PagesXmlParser} 同口径。</p>
 *
 * <p><b>错误分级</b>（券 B 的健壮性口径）：</p>
 * <ul>
 * <li><b>结构级</b>（XML 语法错、根元素不是 {@code <sqlMap>}）→ 抛 {@link IbatisParseException}
 * （调用方按「不是 iBatis 文件」处理）；</li>
 * <li><b>语句级</b>（未知标签、占位符格式错、{@code parameterMap} 声明冲突、{@code include} 找不到片段等）
 * → <b>降级 + 收集 warning</b>，绝不打断整篇；warning 文本一律带语句 id 便于回溯。</li>
 * </ul>
 *
 * <p>不做的事（有意）：不解析 {@code sqlMapConfig} / {@code useStatementNamespaces}（本链是<b>单文件</b>解析器，
 * 与 engine 默认 {@code false} 同口径——{@code <sql id>} 按原样 id 注册，查找时按
 * 「原样 → {@code namespace + "." + refid}」两级回落，见 {@link SqlMapScriptBuilder}）。</p>
 */
public final class SqlMapParser {

    private static final String SQL_MAP_TAG = "sqlMap";

    public SqlMapParser() {
    }

    /**
     * 解析一份 {@code <sqlMap>} 文本。
     *
     * @throws IbatisParseException XML 语法错、根元素不是 {@code <sqlMap>}、文档为空
     */
    public IbatisSqlMap parse(String fileText) {
        Document document = parseDocument(fileText);
        Element root = document.getDocumentElement();
        if (root == null) {
            throw new IbatisParseException("XML 文档为空（没有根元素）。");
        }
        if (!SQL_MAP_TAG.equals(root.getTagName())) {
            throw new IbatisParseException("不是 iBatis sqlMap 文件：根元素为 <" + root.getTagName()
                    + ">（期望 <" + SQL_MAP_TAG + ">）。");
        }
        List<String> warnings = new ArrayList<>();
        String namespace = SqlMapScriptBuilder.attribute(root, "namespace");

        Map<String, Element> fragments = collectSqlFragments(root, warnings);
        List<IbatisParameterMap> parameterMaps = collectParameterMaps(root, warnings);
        List<IbatisStatement> statements = collectStatements(root, namespace, fragments, parameterMaps, warnings);

        return new IbatisSqlMap(
                namespace,
                statements,
                parameterMaps,
                List.copyOf(fragments.keySet()),
                warnings
        );
    }

    // ------------------------------------------------------------------ 片段与参数图

    /** {@code <sql id>} 收集：id 原样注册（engine 默认 {@code useStatementNamespaces=false} 口径）。 */
    private Map<String, Element> collectSqlFragments(Element root, List<String> warnings) {
        Map<String, Element> fragments = new LinkedHashMap<>();
        for (Element element : childElements(root)) {
            if (!"sql".equals(element.getTagName())) {
                continue;
            }
            String id = SqlMapScriptBuilder.attribute(element, "id");
            if (id == null) {
                warnings.add("文件级：<sql> 缺少 id 属性，已忽略（引擎会抛 Duplicate/NullPointer 类错误）。");
                continue;
            }
            if (fragments.containsKey(id)) {
                // 引擎：`Duplicate <sql>-include 'x' found.`
                warnings.add("文件级：<sql id=\"" + id + "\"> 重复声明，保留首个（引擎会抛 Duplicate <sql>-include）。");
                continue;
            }
            fragments.put(id, element);
        }
        return fragments;
    }

    private List<IbatisParameterMap> collectParameterMaps(Element root, List<String> warnings) {
        List<IbatisParameterMap> maps = new ArrayList<>();
        for (Element element : childElements(root)) {
            if (!"parameterMap".equals(element.getTagName())) {
                continue;
            }
            String id = SqlMapScriptBuilder.attribute(element, "id");
            if (id == null) {
                warnings.add("文件级：<parameterMap> 缺少 id 属性（DTD 为 #REQUIRED），已忽略。");
                continue;
            }
            List<IbatisParameterDef> parameters = new ArrayList<>();
            for (Element parameter : childElements(element)) {
                if (!"parameter".equals(parameter.getTagName())) {
                    continue;
                }
                String property = SqlMapScriptBuilder.attribute(parameter, "property");
                parameters.add(new IbatisParameterDef(
                        property != null ? property : "",
                        SqlMapScriptBuilder.attribute(parameter, "javaType"),
                        SqlMapScriptBuilder.attribute(parameter, "jdbcType"),
                        SqlMapScriptBuilder.attribute(parameter, "nullValue"),
                        SqlMapScriptBuilder.attribute(parameter, "mode"),
                        SqlMapScriptBuilder.attribute(parameter, "typeHandler")
                ));
            }
            String className = SqlMapScriptBuilder.attribute(element, "class");
            if (className == null) {
                // DTD 里 class 是 #REQUIRED（官方指南说可选，DTD 不是）——缺失按告警处理
                warnings.add("文件级：<parameterMap id=\"" + id + "\"> 缺少 class 属性（DTD 为 #REQUIRED）。");
            }
            maps.add(new IbatisParameterMap(id, className, parameters));
        }
        return maps;
    }

    // ------------------------------------------------------------------ 语句

    private List<IbatisStatement> collectStatements(
            Element root,
            String namespace,
            Map<String, Element> fragments,
            List<IbatisParameterMap> parameterMaps,
            List<String> warnings
    ) {
        Map<String, IbatisParameterMap> declaredParameterMaps = new HashMap<>();
        for (IbatisParameterMap parameterMap : parameterMaps) {
            declaredParameterMaps.put(applyNamespace(parameterMap.id(), namespace), parameterMap);
        }
        List<IbatisStatement> statements = new ArrayList<>();
        for (Element element : childElements(root)) {
            IbatisStatementKind kind = IbatisStatementKind.byTagName(element.getTagName());
            if (kind == null) {
                continue;
            }
            statements.add(buildStatement(element, kind, namespace, fragments, declaredParameterMaps, warnings));
        }
        return statements;
    }

    private IbatisStatement buildStatement(
            Element element,
            IbatisStatementKind kind,
            String namespace,
            Map<String, Element> fragments,
            Map<String, IbatisParameterMap> declaredParameterMaps,
            List<String> fileWarnings
    ) {
        String tagName = element.getTagName();
        String rawId = SqlMapScriptBuilder.attribute(element, "id");
        String id = rawId;
        if (id == null) {
            fileWarnings.add("文件级：<" + tagName + "> 缺少 id 属性（DTD 为 #REQUIRED），已按 <" + tagName + "@行?> 占位。");
            id = "<无 id 的 " + tagName + ">";
        }
        List<String> warnings = new ArrayList<>();
        final String statementId = id;
        Function1<String, kotlin.Unit> warn = message -> { warnings.add("语句 [" + statementId + "]：" + message); return kotlin.Unit.INSTANCE; };

        // 语句级属性（`insert`/`update`/`delete` 的 DTD 无 resultMap/resultClass——这里只做提示，不阻断解析）
        if (kind != IbatisStatementKind.SELECT && kind != IbatisStatementKind.STATEMENT
                && kind != IbatisStatementKind.PROCEDURE) {
            if (element.hasAttribute("resultMap") || element.hasAttribute("resultClass")) {
                warn.invoke("<" + tagName + "> 按 DTD 不接受 resultMap/resultClass 属性（引擎侧结果装配失败）");
            }
        }

        String parameterMapRef = SqlMapScriptBuilder.attribute(element, "parameterMap");
        IbatisParameterMap resolvedParameterMap = null;
        if (parameterMapRef != null) {
            resolvedParameterMap = declaredParameterMaps.get(applyNamespace(parameterMapRef, namespace));
        }
        if (parameterMapRef != null && resolvedParameterMap == null) {
            // 引擎在此处 `getParameterMap(id)` 直接抛「There is no parameter map named ...」
            warn.invoke("parameterMap=\"" + parameterMapRef + "\" 在本文件内找不到对应 <parameterMap>（引擎会抛异常）");
        }

        SqlMapScriptBuilder builder = new SqlMapScriptBuilder(fragments, namespace, warn);
        List<IbatisSqlNode> body;
        try {
            body = builder.build(element);
        } catch (Throwable error) {
            warn.invoke("语句体解析失败（按原文保留）：" + error.getMessage());
            body = List.of();
        }
        boolean hasDynamicTag = false;
        for (IbatisSqlNode node : body) {
            if (node instanceof IbatisTagNode) {
                hasDynamicTag = true;
                break;
            }
        }
        if (parameterMapRef != null && !hasDynamicTag) {
            // 券 B 第 5 条：**非动态**语句声明 parameterMap 时 `#` 不转 `?`（引擎 applyInlineParameterMap 的
            // `if (parameterMap == null)` 守卫）；这类语句真正生效的是手写 `?`，是配置错误的高发形态。
            warn.invoke("声明了 parameterMap 且不含动态标签：体内 # 不转 ?（引擎行为），请确认语句使用手写 ? 占位");
        }

        List<IbatisSelectKey> selectKeys;
        if (kind == IbatisStatementKind.INSERT) {
            selectKeys = buildSelectKeys(element, id, namespace, fragments, warn);
        } else {
            for (Element child : childElements(element)) {
                if ("selectKey".equals(child.getTagName())) {
                    warn.invoke("<selectKey> 只允许挂在 <insert> 下（引擎会抛 You cant set a select key ...），已忽略");
                }
            }
            selectKeys = List.of();
        }

        Map<String, String> attributes = collectAttributes(element);

        return new IbatisStatement(
                id,
                kind,
                tagName,
                attributes,
                parameterMapRef,
                parameterMapRef != null,
                body,
                resolvedParameterMap,
                List.copyOf(builder.getUsedFragmentIds()),
                selectKeys,
                warnings
        );
    }

    /**
     * {@code <selectKey>} 装配（复刻 {@code SqlStatementParser.findAndParseSelectKey}）：
     * 顺序由<b>位置</b>推断（selectKey 之前是否已有非空文本），{@code type="pre|post"} 显式覆盖。
     */
    private List<IbatisSelectKey> buildSelectKeys(
            Element insert,
            String insertId,
            String namespace,
            Map<String, Element> fragments,
            Function1<String, kotlin.Unit> warn
    ) {
        boolean foundSqlFirst = false;
        org.w3c.dom.NodeList children = insert.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child == null) {
                continue;
            }
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String data = ((CharacterData) child).getData();
                if (data == null) {
                    data = "";
                }
                if (!data.trim().isEmpty()) {
                    foundSqlFirst = true;
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) child;
                if (!"selectKey".equals(element.getTagName())) {
                    continue;
                }
                String type = SqlMapScriptBuilder.attribute(element, "type");
                boolean runAfterSql = type != null ? "post".equals(type) : foundSqlFirst;
                List<String> warnings = new ArrayList<>();
                Function1<String, kotlin.Unit> innerWarn = message -> { warnings.add("语句 [" + insertId + "-SelectKey]：" + message); return kotlin.Unit.INSTANCE; };
                SqlMapScriptBuilder builder = new SqlMapScriptBuilder(fragments, namespace, innerWarn);
                List<IbatisSqlNode> body;
                try {
                    body = builder.build(element);
                } catch (Throwable error) {
                    innerWarn.invoke("selectKey 语句体解析失败（按原文保留）：" + error.getMessage());
                    body = List.of();
                }
                return List.of(new IbatisSelectKey(
                        insertId + "-SelectKey",
                        runAfterSql,
                        collectAttributes(element),
                        body,
                        warnings
                ));
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------ 工具

    /** 语句级属性全量采集（{@code attrs[i] nodeName → nodeValue}）。 */
    private static Map<String, String> collectAttributes(Element element) {
        NamedNodeMap attrs = element.getAttributes();
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < attrs.getLength(); index++) {
            Node item = attrs.item(index);
            result.put(item.getNodeName(), item.getNodeValue());
        }
        return result;
    }

    /** 解析 XML 文本为 DOM（安全配置见 {@code xml/SecureXml}：全仓唯一工厂，含膨胀预扫）。 */
    private Document parseDocument(String fileText) {
        try {
            return SecureXml.parse(fileText);
        } catch (Throwable t) {
            throw new IbatisParseException("XML 解析失败：" + t.getMessage());
        }
    }

    /** 引擎 {@code XmlParserState.applyNamespace}（只用于 {@code parameterMap} 这类<b>无条件</b>前缀的场景）。 */
    private static String applyNamespace(String id, String namespace) {
        if (namespace != null && !namespace.isEmpty() && id.indexOf('.') < 0) {
            return namespace + "." + id;
        }
        return id;
    }

    private List<Element> childElements(Element element) {
        List<Element> result = new ArrayList<>();
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node item = children.item(index);
            if (item instanceof Element child) {
                result.add(child);
            }
        }
        return result;
    }
}
