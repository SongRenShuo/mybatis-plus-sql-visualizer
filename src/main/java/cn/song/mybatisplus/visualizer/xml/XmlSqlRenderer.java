package cn.song.mybatisplus.visualizer.xml;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渲染器（T-M24-01）：复刻 MyBatis 3.5.19 DynamicSqlSource.getBoundSql 的前两遍——
 *
 *  1. SqlNode 树 apply（分支判定 / ${} 替换 / bind 累积，bindings 由调用方提供）；
 *  2. #{} → ? + ParameterMapping 清单（TokenParser 等价 GenericTokenParser；\#{ 转义；
 *     无 } 原样保留）。
 *
 * 第三遍（参数值填充 PreparedStatement）预览场景不需要——参数值由面板回填输入。
 */
public class XmlSqlRenderer {

    public RenderedXmlSql render(
            SqlNode node,
            Map<String, Object> bindings,
            Map<String, Boolean> conditionOverrides,
            Set<String> protectedNames,
            Map<String, Object> staticFieldValues,
            Set<String> bindNames
    ) {
        return render(node, bindings, conditionOverrides, protectedNames, staticFieldValues, bindNames, false);
    }

    /**
     * master 侧默认参量便捷重载后的全参入口。
     *
     * @param emptyDollarPlaceholder T-MbgExamplePreview R1：{@code ${}} 空值输出 {@code {{表达式}}}
     *                               占位（预览链开启，默认关）。
     */
    public RenderedXmlSql render(
            SqlNode node,
            Map<String, Object> bindings,
            Map<String, Boolean> conditionOverrides,
            Set<String> protectedNames,
            Map<String, Object> staticFieldValues,
            Set<String> bindNames,
            boolean emptyDollarPlaceholder
    ) {
        DynamicContext context = new DynamicContext(bindings, conditionOverrides, protectedNames, staticFieldValues, emptyDollarPlaceholder);
        node.apply(context);
        // T-M25.5：渲染完成后提取 bind 求值结果（含覆盖值）——参数行自动回填显示
        Map<String, String> bindValues = new HashMap<>();
        for (String name : bindNames) {
            Object v = context.getBindings().get(name);
            if (v != null) {
                bindValues.put(name, v.toString());
            }
        }
        Map.Entry<String, List<ParameterMapping>> pm = parseParameterMappings(context.getSql(), context.getErrors());
        // R30 探镜：条件判定结果随渲染产物出栈（只登记被走到的条件，见 DynamicContext.getConditionOutcomes）
        return new RenderedXmlSql(
                pm.getKey(),
                pm.getValue(),
                context.getErrors(),
                bindValues,
                context.getDollarSubstitutions(),
                new LinkedHashMap<>(context.getConditionOutcomes()));
    }

    public RenderedXmlSql render(SqlNode node, Map<String, Object> bindings, Map<String, Boolean> conditionOverrides, Set<String> protectedNames, Map<String, Object> staticFieldValues) {
        return render(node, bindings, conditionOverrides, protectedNames, staticFieldValues, Set.of());
    }

    public RenderedXmlSql render(SqlNode node, Map<String, Object> bindings, Map<String, Boolean> conditionOverrides, Set<String> protectedNames) {
        return render(node, bindings, conditionOverrides, protectedNames, Map.of(), Set.of());
    }

    public RenderedXmlSql render(SqlNode node, Map<String, Object> bindings, Map<String, Boolean> conditionOverrides) {
        return render(node, bindings, conditionOverrides, Set.of(), Map.of(), Set.of());
    }

    public RenderedXmlSql render(SqlNode node, Map<String, Object> bindings) {
        return render(node, bindings, Map.of(), Set.of(), Map.of(), Set.of());
    }

    private Map.Entry<String, List<ParameterMapping>> parseParameterMappings(String sql, List<String> errors) {
        List<ParameterMapping> mappings = new ArrayList<>();
        String rendered = TokenParser.parse(sql, "#{", "}", token -> {
            mappings.add(ParameterMapping.parse(token));
            return "?";
        });
        return new AbstractMap.SimpleEntry<>(rendered, mappings);
    }
}
