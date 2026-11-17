package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * iBatis 预览的参数/条件行清单（T-Ibatis2Gap 券 A）：{@code IbatisStatement} → 面板可交互行 + Map 装配。
 *
 * <h2>为什么 Map 装配就够（本券的钥匙）</h2>
 *
 * <p>{@link IbatisProbe} 原生支持 {@code Map} 参数：parameterObject is Map 时取值走 {@code map[name]}、
 * 可用性走 {@code containsKey}。因此<b>条件开关 = Map 里放不放这个 key</b>
 * （{@code <isNotNull property="id">} 在 map 含非 null id 时成立、不含时不成立），
 * <b>值语义 = value 的类型与内容</b>。渲染入口不变：{@link SqlMapSqlRenderer#render}——渲染器零改动。
 *
 * <h2>装配语义（拍板 D-1「条件勾选 + 值可填」）</h2>
 *
 * <ul>
 * <li><b>勾选 = 放 key</b>（值为用户输入，缺省回落 compareValue），<b>取消 = 移除 key</b>；</li>
 * <li>占位符行（{@code #x#}）：填了值才放 key；与条件行同名的 key 由<b>条件行的开关主导</b>
 *     （勾上 isNotNull(id) 时 {@code #id#} 的值进 map，取消时整段跳过、key 不放）；</li>
 * <li>{@code $x$} 行：填了值才放 key（直替照常触发既有注入闸门，<b>不得绕过确认</b>）；</li>
 * <li>{@code <iterate>}：值列表非空才放 key（{@code List<String>}），按列表长度由渲染器生成
 *     {@code ids[0..n-1]}；留空 = key 不放 = 引擎语义「取值不是集合」→ 渲染降级（与无参基线现状一致）；</li>
 * <li><b>空串值对 isNotEmpty 天然为 false 是引擎语义，不做「帮用户转 null」的伪装</b>；</li>
 * <li>装配结果为空 map 时回退 {@code null}（无参基线，与现状逐字节一致）——唯一例外是勾选了
 *     {@code <isParameterPresent>}：它判 {@code params != null}，此时传空 map 才能让条件成立。</li>
 * </ul>
 */
public final class IbatisParamModelBuilder {

    private IbatisParamModelBuilder() {
    }

    // ------------------------------------------------------------------
    // 行清单
    // ------------------------------------------------------------------

    /**
     * 从语句体收集面板行（树序 DFS）。
     *
     * <ul>
     * <li>占位符行：非 postParse 文本节点的配置期解析产物（{@code #x#} / {@code #x:TYPE#} /
     *     {@code #x:TYPE:null#} / {@code #x,jdbcType=?#}）+ 声明 parameterMap 的语句参数（按声明序）；</li>
     * <li>条件行：全部 isXxx 标签（含嵌套在 {@code <dynamic>}/条件内的）；<b>iterate 子树内的条件不建行</b>
     *     （它们的求值随迭代逐轮走带下标的属性路径，开关语义不独立）；</li>
     * <li>iterate 行：每个 {@code <iterate>}（含嵌套）；</li>
     * <li>{@code $x$} 行：文本节点里的直替站点（{@code $$} 转义与 {@code ${prop}} 全局属性 token 不算）。</li>
     * </ul>
     */
    public static IbatisParamModel build(IbatisStatement statement) {
        Map<String, IbatisPlaceholderRow> placeholders = new LinkedHashMap<>();
        List<IbatisConditionRow> conditions = new ArrayList<>();
        List<IbatisIterateRow> iterates = new ArrayList<>();
        Map<String, IbatisDollarRow> dollars = new LinkedHashMap<>();

        // 声明 parameterMap 的静态语句：参数按声明序进占位符行（值只进 map，渲染静态路径本就不吃参数）
        IbatisParameterMap declared = statement.getParameterMap();
        if (declared != null) {
            for (IbatisParameterDef p : declared.parameters()) {
                placeholders.putIfAbsent(p.property(),
                        new IbatisPlaceholderRow(p.property(), p.jdbcType(), p.nullValue()));
            }
        }

        walk(statement.getBody(), false, placeholders, conditions, iterates, dollars);

        return new IbatisParamModel(
                List.copyOf(placeholders.values()),
                List.copyOf(conditions),
                List.copyOf(iterates),
                List.copyOf(dollars.values()));
    }

    private static void walk(List<IbatisSqlNode> nodes,
                             boolean insideIterate,
                             Map<String, IbatisPlaceholderRow> placeholders,
                             List<IbatisConditionRow> conditions,
                             List<IbatisIterateRow> iterates,
                             Map<String, IbatisDollarRow> dollars) {
        for (IbatisSqlNode node : nodes) {
            if (node instanceof IbatisTextNode textNode) {
                for (IbatisParamMapping m : textNode.getMappings()) {
                    placeholders.putIfAbsent(m.propertyName(),
                            new IbatisPlaceholderRow(m.propertyName(), m.jdbcTypeName(), m.nullValue()));
                }
                for (String name : dollarSiteNames(textNode.getText())) {
                    dollars.putIfAbsent(name, new IbatisDollarRow(name));
                }
            } else if (node instanceof IbatisTagNode tagNode) {
                switch (tagNode.getKind()) {
                    case ITERATE -> {
                        iterates.add(new IbatisIterateRow(
                                "ibatis-iter-" + iterates.size(),
                                tagNode.getProperty() != null ? tagNode.getProperty() : "",
                                tagNode.getConjunction()));
                        walk(tagNode.getChildren(), true, placeholders, conditions, iterates, dollars);
                    }
                    case DYNAMIC ->
                            walk(tagNode.getChildren(), insideIterate, placeholders, conditions, iterates, dollars);
                    default -> {
                        if (!insideIterate) {
                            String property = tagNode.getProperty();
                            conditions.add(new IbatisConditionRow(
                                    "ibatis-cond-" + conditions.size(),
                                    tagNode.getKind().getTagName(),
                                    property != null && !property.isEmpty() ? property : null,
                                    tagNode.getCompareValue(),
                                    tagNode.getCompareProperty()));
                        }
                        walk(tagNode.getChildren(), insideIterate, placeholders, conditions, iterates, dollars);
                    }
                }
            }
        }
    }

    /**
     * {@code $x$} 站点名提取（与引擎 {@code SimpleDynamicSql} 的 token 化同口径：
     * {@code StringTokenizer(text, "$", true)}——{@code $$} 是字面量 {@code $}，不是站点；
     * {@code ${prop}} 是全局属性 token（首字符 {@code {}），本链不做替换，也不算直替参数行）。
     */
    private static List<String> dollarSiteNames(String text) {
        List<String> tokens = new ArrayList<>();
        StringTokenizer parser = new StringTokenizer(text, "$", true);
        while (parser.hasMoreTokens()) {
            tokens.add(parser.nextToken());
        }
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            if (!"$".equals(tokens.get(i))) {
                i++;
                continue;
            }
            if (i + 1 < tokens.size() && "$".equals(tokens.get(i + 1))) {
                // $$ → 字面量 $
                i += 2;
                continue;
            }
            int j = i + 1;
            while (j < tokens.size() && !"$".equals(tokens.get(j))) {
                j++;
            }
            if (j >= tokens.size()) {
                break;
            }
            StringBuilder nameBuilder = new StringBuilder();
            for (String part : tokens.subList(i + 1, j)) {
                nameBuilder.append(part);
            }
            String name = nameBuilder.toString();
            if (!name.isEmpty() && !name.startsWith("{")) {
                names.add(name);
            }
            i = j + 1;
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Map 装配
    // ------------------------------------------------------------------

    /** 面板状态 → 渲染参数（{@code Map} 或 null；null = 无参基线，与现状一致）。 */
    @Nullable
    public static Object assembleParams(IbatisParamModel model, PreviewRerenderInput input) {
        Map<String, Object> map = new LinkedHashMap<>();

        // 条件行：勾选 = 放 key。值优先取用户输入（与同名占位符行共享一个输入框），缺省回落
        // compareValue（isEqual 的常见形态：勾上即按比较值参与 WHERE），再缺省空串。
        for (IbatisConditionRow row : model.getConditionRows()) {
            if (!input.enabledConditionIds().contains(row.id())) {
                continue;
            }
            String property = row.property();
            if (property == null) {
                // isParameterPresent 族无属性可放——是否成立只看 map 是否为 null（见函数尾）
                continue;
            }
            String value = input.params().get(property);
            map.put(property, value != null && !value.isEmpty() ? value
                    : row.compareValue() != null ? row.compareValue() : "");
        }

        // 条件行「接管」的属性名：勾选时 key 已由条件行放入；**取消勾选时同名占位符/`$` 行的值
        // 也不放**——取消 = 模拟「该属性不存在」（引擎 Map 语义 containsKey 决定一切），用户填的
        // 值一并隐藏，语义自洽（否则「条件关了、占位符还绑上值」是自相矛盾的模拟）。
        Set<String> conditionControlled = new HashSet<>();
        for (IbatisConditionRow row : model.getConditionRows()) {
            if (row.property() != null) {
                conditionControlled.add(row.property());
            }
        }

        // 占位符行：没被条件行接管的 key，填了值才放（空串 = 不放 = `?` 处 null 绑定，引擎语义）
        for (IbatisPlaceholderRow row : model.getPlaceholderRows()) {
            if (map.containsKey(row.name())) {
                continue;
            }
            if (conditionControlled.contains(row.name())) {
                continue;
            }
            String value = input.params().get(row.name());
            if (value != null && !value.isEmpty()) {
                map.put(row.name(), value);
            }
        }

        // $x$ 行：填了值才放（直替；注入闸门由渲染产物的 dollarRawCount 照常置位，不得绕过）
        for (IbatisDollarRow row : model.getDollarRows()) {
            if (conditionControlled.contains(row.name())) {
                continue;
            }
            String value = input.params().get(row.name());
            if (value != null && !value.isEmpty()) {
                map.put(row.name(), value);
            }
        }

        // iterate 行：值列表非空才放（List<String> → 渲染器生成 ids[0..n-1]）；留空 = 引擎降级（现状）
        for (IbatisIterateRow row : model.getIterateRows()) {
            List<String> values = input.collections().getOrDefault(row.property(), List.of());
            if (!values.isEmpty()) {
                map.put(row.property(), values);
            }
        }

        if (map.isEmpty()) {
            // 空装配 = 无参基线（null，与现状逐字节一致）；唯一例外是 isParameterPresent 被勾选——
            // 它判 params != null，传空 map 才成立（isNotParameterPresent 反之，null 天然成立）
            boolean parameterPresentChecked = false;
            for (IbatisConditionRow row : model.getConditionRows()) {
                if (input.enabledConditionIds().contains(row.id())
                        && IbatisTagKind.IS_PARAMETER_PRESENT.getTagName().equals(row.kindTagName())) {
                    parameterPresentChecked = true;
                    break;
                }
            }
            return parameterPresentChecked ? Map.of() : null;
        }
        return map;
    }
}
