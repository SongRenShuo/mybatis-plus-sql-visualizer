package cn.song.mybatisplus.visualizer.xml;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.Set;

/**
 * 动态文本节点（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.TextSqlNode。
 *
 * - isDynamic：文本含 ${}（转义 \${ 除外）即动态；
 * - apply 时逐 token 求值：_parameter 为 null → value 绑定 null；
 *   简单类型 → value 绑定参数本身（${value} 可用）；其余走 OGNL 求值；
 *   求值结果 null → 输出空串（issue #274：不是 "null"）；
 * - 表达式解析失败 → 空串 + 上下文错误记录（面板标注「未解析变量」）。
 */
public final class TextSqlNode implements SqlNode {

    private final String text;
    private final boolean isDynamic;

    public TextSqlNode(String text) {
        this.text = text;
        this.isDynamic = isDynamic(text);
    }

    public String getText() {
        return text;
    }

    public boolean isDynamic() {
        return isDynamic;
    }

    @Override
    public boolean apply(DynamicContext context) {
        context.appendSql(applyTokens(context));
        return true;
    }

    private String applyTokens(DynamicContext context) {
        return TokenParser.parse(text, "${", "}", content -> {
            // T-DollarInjectMark：每次 ${} 直替求值计数（null→空串也算——token 已从产物移除；
            // 经 open 函数走 delegate 转发——foreach 过滤上下文内计数不丢失）
            context.countDollarSubstitution();
            // 复刻 BindingTokenParser.handleToken：先维护 value 便捷绑定，再 OGNL 求值
            Object parameter = context.getBindings().get(DynamicContext.PARAMETER_OBJECT_KEY);
            if (parameter == null) {
                context.bind("value", null);
            } else if (isSimpleType(parameter)) {
                context.bind("value", parameter);
            }
            EvalResult result = OgnlEvaluator.evaluate(content, context.getBindings(), context.getStaticFieldValues());
            if (result instanceof EvalResult.Ok ok) {
                if (ok.value() == null) {
                    // 求值为空提示：表达式含变量引用且模拟上下文求值为 null
                    Set<String> roots = OgnlEvaluator.rootVariables(content);
                    if (!roots.isEmpty()) {
                        context.getErrors().add("模拟参数下表达式 ${" + content + "} 求值为空（MP 占位 ${ew...} 等需真实参数值）");
                        // T-MbgExamplePreview R1：预览占位模式——把缺段显式写进产物。
                        // MBG 的 `and ${criterion.condition}` 在模拟参数下会退化成裸 `and`，
                        // 产物看着像插件识别错；占位后是 `( {{criterion.condition}} )`，缺什么一目了然。
                        if (context.isEmptyDollarPlaceholder()) {
                            return "{{" + content + "}}";
                        }
                    }
                }
                return ok.value() != null ? ok.value().toString() : "";
            } else {
                EvalResult.Err err = (EvalResult.Err) result;
                context.getErrors().add("无法解析表达式 ${" + content + "}（" + err.message() + "）");
                return "";
            }
        });
    }

    /** 文本是否含动态 ${}（转义 \${ 不算）。 */
    public static boolean isDynamic(String text) {
        boolean[] dynamic = {false};
        TokenParser.parse(text, "${", "}", s -> {
            dynamic[0] = true;
            return s;
        });
        return dynamic[0];
    }

    /**
     * 简单类型判定（对齐 MyBatis SimpleTypeRegistry）：字符串/包装类/日期/枚举等。
     */
    public static boolean isSimpleType(Object value) {
        return value instanceof String
                || value instanceof Byte || value instanceof Short || value instanceof Character
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Date || value instanceof Enum<?>
                || value instanceof java.time.LocalDate || value instanceof java.time.LocalTime
                || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetDateTime
                || value instanceof java.time.OffsetTime || value instanceof java.time.Instant
                || value instanceof java.time.ZonedDateTime || value instanceof java.time.Year
                || value instanceof java.time.YearMonth;
    }
}
