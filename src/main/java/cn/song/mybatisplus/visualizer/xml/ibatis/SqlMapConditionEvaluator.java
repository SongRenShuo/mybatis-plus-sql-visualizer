// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/SqlMapConditionEvaluator.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagKind;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapPrependMarker.IbatisTagContext;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 条件标签求值器——逐行复刻 iBatis 2.3.4.726 的 {@code ConditionalTagHandler} 与 14 个 {@code isXxx} 处理器。
 *
 * <p><b>iBatis 2 没有表达式语言</b>（全树 grep {@code ognl} 零命中）：条件＝「属性取值（{@code Probe}）+
 * 类型换算后的比较」。</p>
 *
 * <p>三个必须照抄的实测细节：</p>
 * <ol>
 * <li>{@code compareValues} 的<b>类型换算顺序</b>：先按 {@code value1} 的声明类型把 {@code compareValue} 转过去；
 * 若转换结果是 String 而声明类型不是 String，则把 {@code value1} 也 {@code toString()}；
 * 任一非 {@code Comparable} 时<b>双方都</b>转 String 再比；</li>
 * <li>{@code NOT_COMPARABLE = Long.MIN_VALUE}：<b>它是负数</b>，所以「不可比」在
 * {@code isLessThan} / {@code isLessEqual} 上判<b>真</b>、在 {@code isGreaterThan} / {@code isGreaterEqual} 上判假；</li>
 * <li>{@code <isNull>}/{@code <isNotNull>}/{@code <isEmpty>}/{@code <isNotEmpty>} <b>不参与 compare</b>，
 * 各自实现 {@code isCondition}。</li>
 * </ol>
 */
public final class SqlMapConditionEvaluator {

    /** 引擎 {@code ConditionalTagHandler.NOT_COMPARABLE}。 */
    public static final long NOT_COMPARABLE = Long.MIN_VALUE;

    /** 引擎 {@code ConditionalTagHandler.DATE_MASK}（注意是 12 小时制 {@code hh}）。 */
    public static final String DATE_MASK = "yyyy/MM/dd hh:mm:ss";

    /** master 侧主构造函数参数 {@code warn: (String) -> Unit = {}} 的默认实现（静默吞告警）。 */
    private static final Function1<String, Unit> NOOP_WARN = s -> Unit.INSTANCE;

    /** 告警出口（降级路径的用户可见提示；{@code @Service} 接线侧注入面板收集器）。 */
    private final Function1<String, Unit> warn;

    /** master 侧默认参量便捷构造器（{@code warn = {}}——静默）。 */
    public SqlMapConditionEvaluator() {
        this(NOOP_WARN);
    }

    public SqlMapConditionEvaluator(Function1<String, Unit> warn) {
        this.warn = warn;
    }

    private void warn(String message) {
        warn.invoke(message);
    }

    /** 引擎 {@code ConditionalTagHandler.doStartFragment} 的条件判据。 */
    public boolean isCondition(IbatisTagKind kind, IbatisTagContext ctx, IbatisTagNode tag, Object params) {
        // if/else 链保持 master 侧 when 分支的限定形态引用（EnumReachabilityGuard 语义对齐）
        if (kind == IbatisTagKind.IS_NULL) {
            return isNullCondition(ctx, tag, params);
        }
        if (kind == IbatisTagKind.IS_NOT_NULL) {
            return !isNullCondition(ctx, tag, params);
        }
        if (kind == IbatisTagKind.IS_EMPTY) {
            return isEmptyCondition(ctx, tag, params);
        }
        if (kind == IbatisTagKind.IS_NOT_EMPTY) {
            return !isEmptyCondition(ctx, tag, params);
        }
        if (kind == IbatisTagKind.IS_PROPERTY_AVAILABLE) {
            return isPropertyAvailable(ctx, tag, params);
        }
        if (kind == IbatisTagKind.IS_NOT_PROPERTY_AVAILABLE) {
            return !isPropertyAvailable(ctx, tag, params);
        }
        if (kind == IbatisTagKind.IS_PARAMETER_PRESENT) {
            return params != null;
        }
        if (kind == IbatisTagKind.IS_NOT_PARAMETER_PRESENT) {
            return params == null;
        }
        if (kind == IbatisTagKind.IS_EQUAL) {
            return compare(ctx, tag, params) == 0L;
        }
        if (kind == IbatisTagKind.IS_NOT_EQUAL) {
            return compare(ctx, tag, params) != 0L;
        }
        if (kind == IbatisTagKind.IS_GREATER_THAN) {
            return compare(ctx, tag, params) > 0L;
        }
        if (kind == IbatisTagKind.IS_GREATER_EQUAL) {
            return compare(ctx, tag, params) >= 0L;
        }
        if (kind == IbatisTagKind.IS_LESS_THAN) {
            return compare(ctx, tag, params) < 0L;
        }
        if (kind == IbatisTagKind.IS_LESS_EQUAL) {
            return compare(ctx, tag, params) <= 0L;
        }
        // dynamic / iterate 不走条件求值（doStartFragment 不使用 isCondition）
        return true;
    }

    /** 引擎 {@code IsNullTagHandler.isCondition}。 */
    private boolean isNullCondition(IbatisTagContext ctx, IbatisTagNode tag, Object params) {
        if (params == null) {
            return true;
        }
        String prop = ctx.resolveProperty(tag);
        Object value = prop != null ? IbatisProbe.getObject(params, prop) : params;
        return value == null;
    }

    /** 引擎 {@code IsEmptyTagHandler.isCondition}（Collection {@code size()<1} / 数组 {@code length==0} / 其余 {@code ==null || ""}）。 */
    private boolean isEmptyCondition(IbatisTagContext ctx, IbatisTagNode tag, Object params) {
        if (params == null) {
            return true;
        }
        String prop = ctx.resolveProperty(tag);
        Object value = prop != null ? IbatisProbe.getObject(params, prop) : params;
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size() < 1;
        }
        if (value != null && value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) == 0;
        }
        return value == null || value.toString().isEmpty();
    }

    /**
     * 引擎 {@code IsPropertyAvailableTagHandler.isCondition}。
     *
     * <p><b>{@code Map} 分支刻意绕开 {@code Probe}</b>：{@code Probe} 对 {@code Map} 的任何属性名都返回 true，
     * 故对 {@code Map} 改用 {@code containsKey(propertyAttr)}（用<b>原始</b>属性名，不做下标解析）。</p>
     */
    private boolean isPropertyAvailable(IbatisTagContext ctx, IbatisTagNode tag, Object params) {
        if (params == null) {
            return false;
        }
        if (params instanceof java.util.Map<?, ?> map) {
            return map.containsKey(tag.getProperty());
        }
        String property = ctx.resolveProperty(tag);
        if (property == null) {
            warn("条件标签 <" + tag.getKind().getTagName() + "> 缺少 property 属性，按「属性不可用」处理");
            return false;
        }
        return IbatisProbe.hasReadablePropertyAfterLastDot(params, property);
    }

    /** 引擎 {@code ConditionalTagHandler.compare}：{@code compareProperty} 优先，其次 {@code compareValue}，都没有是配置错误。 */
    public long compare(IbatisTagContext ctx, IbatisTagNode tag, Object params) {
        String prop = ctx.resolveProperty(tag);
        Object value1;
        Class<?> type;
        if (prop != null) {
            value1 = IbatisProbe.getObject(params, prop);
            type = IbatisProbe.getPropertyTypeForGetter(params, prop);
        } else {
            value1 = params;
            type = params != null ? params.getClass() : Object.class;
        }
        String comparePropertyName = tag.getCompareProperty();
        String compareValue = tag.getCompareValue();
        if (comparePropertyName != null) {
            return compareValues(type, value1, IbatisProbe.getObject(params, comparePropertyName));
        }
        if (compareValue != null) {
            return compareValues(type, value1, compareValue);
        }
        warn("条件标签 <" + tag.getKind().getTagName() + "> 既没有 compareProperty 也没有 compareValue，按「不成立」处理");
        return NOT_COMPARABLE;
    }

    /**
     * 引擎 {@code ConditionalTagHandler.compareValues}（返回值语义见类 KDoc）。
     *
     * <p>非法字面量（如声明类型是 Integer 却比 {@code "abc"}）在引擎里抛 {@code NumberFormatException}；
     * 本链折成 {@code NOT_COMPARABLE} + 告警（畸形输入不打断整篇）。</p>
     */
    public long compareValues(Class<?> type, Object value1, Object value2) {
        if (value1 == null || value2 == null) {
            return value1 == value2 ? 0L : NOT_COMPARABLE;
        }
        Object left = value1;
        Object right = value2;
        if (right.getClass() != type) {
            try {
                right = convertValue(type, right.toString());
            } catch (Throwable t) {
                warn("比较值无法换算为 " + type.getName() + "：'" + right + "'");
                return NOT_COMPARABLE;
            }
        }
        if (right instanceof String && type != String.class) {
            left = left.toString();
        }
        if (!(left instanceof Comparable<?>) || !(right instanceof Comparable<?>)) {
            left = left.toString();
            right = right.toString();
        }
        // T-Ibatis2Gap 券 A 的超集增强：Map 参数下取到的值是 String，而引擎按 `value1` 的声明类型
        // （Map 值的运行时类 = String）换算后走**字典序**比较——`isGreaterThan("9", "18")` 会误判成立。
        // 两侧都能解析为数值时改按 `BigDecimal.compareTo` 比较（兼容整数/小数/负数）；任一侧解析
        // 失败（Date 字面量 / 布尔 / 非数字）→ 落回下方既有路径，既有非数值路径的结果零变化。
        Long numeric = numericComparison(left, right);
        if (numeric != null) {
            return numeric;
        }
        try {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparableLeft = (Comparable<Object>) left;
            return comparableLeft.compareTo(right);
        } catch (Throwable t) {
            warn("比较失败（不可比）：" + left.getClass().getName() + " vs "
                    + (right != null ? right.getClass().getName() : null));
            return NOT_COMPARABLE;
        }
    }

    /**
     * T-Ibatis2Gap 券 A：数值比较判定（null = 两侧不全是数值，走既有路径）。
     *
     * <p>判据是<b>两侧的字符串形态都能被 {@code BigDecimal} 解析</b>（先 trim，兼容整数/小数/负数/科学计数）；
     * 引擎语义保真说明：真实引擎在「String 属性 vs compareValue」下确实走字典序（本链照抄过），
     * 这是<b>预览交互层的有意超集</b>——Map 装配把用户输入一律放 String（{@code IbatisParamModelBuilder}），
     * 不做数值增强则 {@code age=9} 勾 {@code isGreaterThan(18)} 会误判成立，产出与真实执行相反的 SQL。
     * 保真对照集 {@code SqlMapFidelityTest} 的全部比较用例两侧同值或同为 Integer 类型，数值路径结果与
     * 既有路径一致（20/20 读数不变，见券 A 报告）。</p>
     */
    private Long numericComparison(Object left, Object right) {
        if (right == null) {
            return null;
        }
        BigDecimal leftNumber;
        try {
            leftNumber = new BigDecimal(left.toString().trim());
        } catch (Throwable t) {
            return null;
        }
        BigDecimal rightNumber;
        try {
            rightNumber = new BigDecimal(right.toString().trim());
        } catch (Throwable t) {
            return null;
        }
        return (long) leftNumber.compareTo(rightNumber);
    }

    /** 引擎 {@code ConditionalTagHandler.convertValue}（Date 掩码固定 {@code yyyy/MM/dd hh:mm:ss}）。 */
    public Object convertValue(Class<?> type, String value) {
        if (type == String.class) {
            return value;
        }
        if (type == Byte.class || type == Byte.TYPE) {
            return Byte.parseByte(value);
        }
        if (type == Short.class || type == Short.TYPE) {
            return Short.parseShort(value);
        }
        if (type == Character.class || type == Character.TYPE) {
            return value.charAt(0);
        }
        if (type == Integer.class || type == Integer.TYPE) {
            return Integer.parseInt(value);
        }
        if (type == Long.class || type == Long.TYPE) {
            return Long.parseLong(value);
        }
        if (type == Float.class || type == Float.TYPE) {
            return Float.parseFloat(value);
        }
        if (type == Double.class || type == Double.TYPE) {
            return Double.parseDouble(value);
        }
        if (type == Boolean.class || type == Boolean.TYPE) {
            return Boolean.parseBoolean(value);
        }
        if (type == Date.class) {
            try {
                return new SimpleDateFormat(DATE_MASK).parse(value);
            } catch (java.text.ParseException e) {
                throw new RuntimeException(e);
            }
        }
        if (type == BigInteger.class) {
            return new BigInteger(value);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(value);
        }
        return value;
    }
}
