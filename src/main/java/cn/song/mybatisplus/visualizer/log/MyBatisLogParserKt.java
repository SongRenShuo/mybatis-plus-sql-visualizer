package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code MyBatisLogParser.kt} 顶层函数（{@code parseParams(String)}）的 Java 承载类。
 */
public final class MyBatisLogParserKt {
    private MyBatisLogParserKt() {
    }

    /**
     * `null` 分支在前（见 {@link #parseParams(String)} KDoc）；`\A`/`\z` 锚定避免跨参数误配。
     *
     * <p>T-CrossChannelResidual（20261105）：类型分组由 {@code [A-Za-z_$][\w$]*} 放宽为 {@code [A-Za-z_$][\w$.]*}，
     * 允许全限定类型名（{@code 42(java.lang.Long)}）。原口径匹配不上 → 回落逗号粗分 → {@code value} 里残留
     * {@code (java.lang.Long)}、{@code type=null}，与控制台还原后的字面量形态分叉（{@code LogSqlRestorer.PLAIN_TYPES}
     * 本就有 {@code java.lang.*} 条目，但解析器从不产出这种形态，那段映射等于死代码）。
     * 真实 MyBatis 日志打简单类名，故现场不可达；放宽只为关掉这个边界并让既有映射真正生效。</p>
     */
    private static final Pattern PARAM_REGEX = Pattern.compile(
            "(?:\\A|,\\s*)(null)(?=,\\s*|\\z)|(?:\\A|,\\s*)(.+?)\\(([A-Za-z_$][\\w$.]*(?:\\[])*)\\)(?=,\\s*|\\z)");

    /**
     * 解析 `Parameters:` 行参数文本（T-M31-02 依赖）：`1(Long), abc(String), null` →
     * [(1, Long), (abc, String), (null, null)]。
     *
     * 非贪婪正则 + 右边界断言：值可含逗号/括号（`a, b(String)`、`a(x)(String)`）；
     * `null` 字面量无类型标注（须先于一般分支匹配，防 `null, 1(Integer)` 被吞成 `null, 1`）。
     * 全无 `(类型)` 标注时（旧版/第三方日志）回落逗号粗分，类型 null（还原器启发式判定引号）。
     */
    public static List<LogSqlParam> parseParams(String text) {
        List<LogSqlParam> result = new ArrayList<>();
        Matcher m = PARAM_REGEX.matcher(text);
        while (m.find()) {
            String nullLiteral = m.group(1);
            if (nullLiteral != null) {
                result.add(new LogSqlParam(null, null));
                continue;
            }
            String v = m.group(2);
            String t = m.group(3);
            result.add(new LogSqlParam(v != null ? v.trim() : null, t));
        }
        if (result.isEmpty() && !text.isBlank()) {
            for (String part : text.split(",")) {
                String trimmed = part.trim();
                result.add(new LogSqlParam(trimmed.isEmpty() ? null : trimmed, null));
            }
        }
        return result;
    }

    /** iBatis 列表里的 null 字面量（Java {@code List.toString()} 对 null 元素打印 {@code null}）。 */
    private static final String IBATIS_NULL_LITERAL = "null";

    /**
     * iBatis 2.x {@code Parameters:} 行（{@code [v1, null, v2]}，Java 集合 {@code toString} 形态）→ 参数表。
     *
     * <p>外层 {@code [ ]} 是<b>结构</b>不是值的一部分，剥一层后按顶层逗号切分；裸 {@code null} 记为 null 值
     * （其余元素原样作为值文本，类型留空等 {@code Types:} 行并入）。</p>
     *
     * <p>切分口径复用 {@code ListParamRenderer.splitListElements}（与 IN 子句集合展开同一份实现）。
     * 已知边界（{@code toString} 不可逆，与 {@code ListParamRenderer} 同口径登记）：元素内含逗号/嵌套方括号
     * 按逗号切分；空串元素会被 {@code splitListElements} 滤掉，此形态下与 {@code Types:} 的位置对齐可能偏移。</p>
     *
     * <p>非列表形态（首尾不是 {@code [} {@code ]}）返回空表——iBatis 恒打列表形态，不猜其它形状。</p>
     */
    public static List<LogSqlParam> parseIbatisParams(String text) {
        List<String> elements = ListParamRenderer.splitListElements(text);
        if (elements == null) return List.of();
        List<LogSqlParam> result = new ArrayList<>(elements.size());
        for (String element : elements) {
            result.add(IBATIS_NULL_LITERAL.equals(element) ? new LogSqlParam(null, null) : new LogSqlParam(element, null));
        }
        return result;
    }

    /**
     * iBatis 2.x {@code Types:} 行（{@code [java.lang.String, null, java.lang.Long]}）→ 类型表（按位置对应
     * {@code Parameters:} 行）。裸 {@code null} 表示该位置参数为 null（iBatis 对 null 参数打 {@code null} 类型），
     * 记为 null 表示「无类型」——不伪造类型。
     */
    public static List<String> parseIbatisTypes(String text) {
        List<String> elements = ListParamRenderer.splitListElements(text);
        if (elements == null) return List.of();
        List<String> result = new ArrayList<>(elements.size());
        for (String element : elements) {
            result.add(IBATIS_NULL_LITERAL.equals(element) ? null : element);
        }
        return result;
    }

    /**
     * 按位置把 {@code Types:} 并入参数（越界位置保持无类型；types 为空表时原样返回）。
     * 类型来自 iBatis 的<b>全限定名</b>（{@code java.lang.String}），{@code LogSqlRestorer} 的 PLAIN_TYPES 与
     * 日期族匹配都已覆盖这类形态（{@code java.lang.Long} 归一后原样直出、{@code java.lang.String} 走引号）。
     */
    public static List<LogSqlParam> applyIbatisTypes(List<LogSqlParam> params, List<String> types) {
        if (types.isEmpty()) return params;
        List<LogSqlParam> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            LogSqlParam param = params.get(i);
            if (i < types.size() && types.get(i) != null) {
                result.add(new LogSqlParam(param.value(), types.get(i)));
            } else {
                result.add(param);
            }
        }
        return result;
    }
}
