// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/SqlMapTokenParser.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParamMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * {@code #} / {@code $} 两套占位符的解析器——逐行复刻 iBatis 2.3.4.726 的
 * {@code InlineParameterMapParser}（{@code #}）与 {@code SimpleDynamicSql}（{@code $}）。
 *
 * <p>必须照抄的坑（全部来自 jar 内源码实测）：</p>
 * <ol>
 * <li><b>分派</b>：token 含 {@code :} → 简单语法；否则高级语法（{@code InlineParameterMapParser:47-51}）；</li>
 * <li><b>简单语法按 token 数分档</b>（{@code oldParseMapping}）：3 → {@code name:jdbcType}；≥5 →
 * {@code name:jdbcType:nullValue}（多余 token 拼回 nullValue）；<b>其他一律报错</b>——
 * 所以 {@code #name:nullValue#} 的第二段永远是 {@code jdbcType}，<b>不是</b> nullValue；</li>
 * <li><b>高级语法</b> {@code #p,javaType=?,jdbcType=?,mode=?,nullValue=?,handler=?,numericScale=?#}
 * （{@code newParseMapping}）：{@code StringTokenizer(token, "=,", false)}，首 token 是 propertyName，
 * 其后成对 field/value；落单的 field 与未知 field 都报错；</li>
 * <li><b>转义</b>：{@code ##} → 字面量 {@code #}；{@code $$} → 字面量 {@code $}；未闭合的定界符在引擎里抛异常，
 * 本链按券 B 的降级口径<b>原文保留 + 告警</b>（见 {@link InlineParseOutcome}）；</li>
 * <li>{@code $x$} 的取值：参数是标量（{@code hasTypeHandler} 命中）时取<b>整个对象</b>，否则按属性名 probe；
 * <b>值为 null 时静默丢弃</b>（什么都不输出）。</li>
 * </ol>
 *
 * <p>token 化一律走 {@link StringTokenizer}（单字符定界符、保留定界符、不产生空 token）——
 * 手写扫描器与它在合法输入上等价，但直接用 JDK 类可以省掉一类「换行/连续定界符」的边界争议。</p>
 */
public final class SqlMapTokenParser {

    /** 引擎 {@code InlineParameterMapParser.PARAMETER_TOKEN}。 */
    private static final String PARAMETER_TOKEN = "#";

    /** 引擎 {@code InlineParameterMapParser.PARAM_DELIM}。 */
    private static final String PARAM_DELIM = ":";

    /** 引擎 {@code SimpleDynamicSql.ELEMENT_TOKEN}。 */
    private static final String ELEMENT_TOKEN = "$";

    /** {@code #...#} 解析产物（含降级告警）。 */
    public record InlineParseOutcome(
            String text,
            List<IbatisParamMapping> mappings,
            List<String> warnings
    ) {
    }

    /** {@code $...$} 替换产物。 */
    public record DollarOutcome(
            String text,
            /** {@code $x$} 站点数（含取值为 null、被静默丢弃的站点）。 */
            int siteCount,
            /** 实际写入 SQL 的取值（注入闸门用）。 */
            List<String> renderedValues,
            List<String> warnings
    ) {
    }

    public SqlMapTokenParser() {
    }

    // ------------------------------------------------------------------ # 分支

    /**
     * {@code #...#} → {@code ?}，并<b>按出现顺序</b>记录参数名与 jdbcType
     * （引擎 {@code InlineParameterMapParser.parseInlineParameterMap}）。
     */
    public InlineParseOutcome parseInlineParameterMap(String sqlStatement) {
        List<String> tokens = tokenize(sqlStatement, PARAMETER_TOKEN);
        StringBuilder out = new StringBuilder();
        List<IbatisParamMapping> mappings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String last = null;
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (PARAMETER_TOKEN.equals(last)) {
                if (PARAMETER_TOKEN.equals(token)) {
                    // `##` → 字面量 `#`
                    out.append(PARAMETER_TOKEN);
                    last = null;
                    index++;
                    continue;
                }
                boolean closed = index + 1 < tokens.size() && PARAMETER_TOKEN.equals(tokens.get(index + 1));
                if (closed) {
                    IbatisParamMapping mapping = parseMapping(token, warnings);
                    if (mapping != null) {
                        mappings.add(mapping);
                        out.append('?');
                    } else {
                        out.append('#').append(token).append('#');
                    }
                    index += 2;
                } else {
                    warnings.add("未闭合的内联参数（原文保留）：#" + token);
                    out.append('#').append(token);
                    index++;
                }
                last = null;
                continue;
            }
            if (!PARAMETER_TOKEN.equals(token)) {
                out.append(token);
            }
            last = token;
            index++;
        }
        return new InlineParseOutcome(out.toString(), mappings, warnings);
    }

    /** 分派：含 {@code :} 走简单语法，否则走高级语法。 */
    private IbatisParamMapping parseMapping(String token, List<String> warnings) {
        if (token.indexOf(PARAM_DELIM) > -1) {
            return oldParseMapping(token, warnings);
        }
        return newParseMapping(token, warnings);
    }

    /**
     * 高级语法：{@code #propertyName,javaType=string,jdbcType=VARCHAR,mode=IN,nullValue=N/A,#}
     * （无冒号时走这条——<b>注意 {@code handler=} 的取值在本链不实例化任何类</b>，只记名）。
     */
    private IbatisParamMapping newParseMapping(String token, List<String> warnings) {
        List<String> fields = tokenize(token, "=,", false);
        if (fields.isEmpty()) {
            warnings.add("内联参数格式错误（没有属性名）：#" + token + "#");
            return null;
        }
        String propertyName = null;
        String javaType = null;
        String jdbcType = null;
        String mode = null;
        String nullValue = null;
        Integer numericScale = null;
        int index = 0;
        while (index < fields.size()) {
            String field = fields.get(index);
            if (propertyName == null) {
                propertyName = field;
                index++;
                continue;
            }
            if (index + 1 >= fields.size()) {
                warnings.add("内联参数格式错误（name=value 不成对）：#" + token + "#");
                return null;
            }
            String value = fields.get(index + 1);
            if ("javaType".equals(field)) {
                javaType = value;
            } else if ("jdbcType".equals(field)) {
                jdbcType = value;
            } else if ("mode".equals(field)) {
                mode = value;
            } else if ("nullValue".equals(field)) {
                nullValue = value;
            } else if ("numericScale".equals(field)) {
                Integer scale = toIntOrNull(value);
                if (scale == null || scale < 0) {
                    warnings.add("numericScale 不是有效的非负整数：#" + token + "#");
                    return null;
                }
                numericScale = scale;
            } else if ("handler".equals(field)) {
                // handler= 只记名不实例化（引擎会 Resources.instantiate，静态渲染无此需要）
            } else {
                warnings.add("未识别的内联参数字段 '" + field + "'：#" + token + "#");
                return null;
            }
            index += 2;
        }
        if (propertyName == null) {
            return null;
        }
        return new IbatisParamMapping(propertyName, jdbcType, nullValue, javaType, mode, numericScale);
    }

    /**
     * 简单语法（含 {@code :}）：按 {@code StringTokenizer(token, ":", true)} 的 <b>token 数</b>分档。
     *
     * <p>3 → 两段；≥5 → 三段（多余的 token 逐个拼回 nullValue，故 nullValue 内可以再含 {@code :}）；
     * 其他 → 格式错误。<b>{@code #p:nullValue#} 在这里是 {@code jdbcType=nullValue}</b>。</p>
     */
    private IbatisParamMapping oldParseMapping(String token, List<String> warnings) {
        List<String> fields = tokenize(token, PARAM_DELIM);
        if (fields.size() == 3) {
            return new IbatisParamMapping(fields.get(0), fields.get(2));
        }
        if (fields.size() >= 5) {
            StringBuilder nullValue = new StringBuilder(fields.get(4));
            int index = 5;
            while (index < fields.size()) {
                nullValue.append(fields.get(index));
                index++;
            }
            return new IbatisParamMapping(fields.get(0), fields.get(2), nullValue.toString(), null, null, null);
        }
        warnings.add("内联参数格式错误（冒号分段数必须是 3 或 >=5）：#" + token + "#");
        return null;
    }

    // ------------------------------------------------------------------ $ 分支

    /**
     * {@code $x$} 直替（引擎 {@code SimpleDynamicSql.processDynamicElements}）。
     *
     * <p><b>永远在动态标签求值之后执行</b>（{@code DynamicSql.process} 末尾才 new {@code SimpleDynamicSql}），
     * 所以调用方只能在整篇 SQL 组装完成后调用本方法。</p>
     */
    public DollarOutcome processDollarSubstitution(String sql, Object params) {
        List<String> tokens = tokenize(sql, ELEMENT_TOKEN);
        StringBuilder out = new StringBuilder();
        List<String> rendered = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int siteCount = 0;
        String last = null;
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (ELEMENT_TOKEN.equals(last)) {
                if (ELEMENT_TOKEN.equals(token)) {
                    // `$$` → 字面量 `$`
                    out.append(ELEMENT_TOKEN);
                    last = null;
                    index++;
                    continue;
                }
                boolean closed = index + 1 < tokens.size() && ELEMENT_TOKEN.equals(tokens.get(index + 1));
                if (!closed) {
                    warnings.add("未闭合的 $ 动态元素（原文保留）：$" + token);
                    out.append('$').append(token);
                    index++;
                    last = null;
                    continue;
                }
                siteCount++;
                Object value = dollarValue(params, token);
                if (value != null) {
                    String text = value.toString();
                    out.append(text);
                    rendered.add(text);
                }
                index += 2;
                last = null;
                continue;
            }
            if (!ELEMENT_TOKEN.equals(token)) {
                out.append(token);
            }
            last = token;
            index++;
        }
        return new DollarOutcome(out.toString(), siteCount, rendered, warnings);
    }

    /** {@code $x$} 取值：标量参数取整个对象（{@code hasTypeHandler} 分支），否则按属性名 probe。 */
    private Object dollarValue(Object params, String name) {
        if (params == null) {
            return null;
        }
        if (IbatisProbe.hasTypeHandler(params.getClass())) {
            return params;
        }
        return IbatisProbe.getObject(params, name);
    }

    // ------------------------------------------------------------------ token 化

    /** master 侧默认参量便捷重载（{@code returnDelims = true}）。 */
    private List<String> tokenize(String text, String delims) {
        return tokenize(text, delims, true);
    }

    /**
     * {@link StringTokenizer} 语义化的 token 列表（单字符定界符）：
     * {@code returnDelims} 为 true 时定界符各成一个 token；<b>不产生空 token</b>。
     */
    private List<String> tokenize(String text, String delims, boolean returnDelims) {
        List<String> result = new ArrayList<>();
        StringTokenizer parser = new StringTokenizer(text, delims, returnDelims);
        while (parser.hasMoreTokens()) {
            result.add(parser.nextToken());
        }
        return result;
    }

    /** Kotlin {@code String.toIntOrNull()} 等价（解析失败返回 null）。 */
    private static Integer toIntOrNull(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Throwable t) {
            return null;
        }
    }
}
