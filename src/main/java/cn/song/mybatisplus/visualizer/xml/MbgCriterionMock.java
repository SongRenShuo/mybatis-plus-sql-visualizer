// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/MbgCriterionMock.kt 移植
package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MBG Example 的 Criterion 模拟值（T-MbgExamplePreview M2）。
 *
 * <p>MBG 生成器的 {@code andXxxYyy(...)} 方法体固定是一句
 * {@code addCriterion("cust_id =", value, "custId")}——condition 是字符串常量、分支语义写在方法名后缀里。
 * 因此从调用点反推 Criterion 不需要猜列名，只需读那个常量。</p>
 *
 * <p>k2j 承载形态：master 侧同文件的 {@code MbgCriterionKind} 枚举落为本 record 的嵌套类型
 * （全仓消费点都在本类内）。</p>
 */
public record MbgCriterionMock(
        /** {@code addCriterion} 的第一个实参常量（如 {@code cust_id =} / {@code cust_id in}），已含操作符 */
        String condition,
        /** 调用点实参原文（{@code IsNull} 类为空；{@code Between} 两个；其余一个） */
        List<String> values,
        /** 由方法名后缀判定（见 {@link #kindOf(String)}） */
        MbgCriterionKind kind
) {

    /** master 侧默认参量便捷构造器（{@code values = List.of()}、{@code kind = SINGLE_VALUE}）。 */
    public MbgCriterionMock(String condition) {
        this(condition, List.of(), MbgCriterionKind.SINGLE_VALUE);
    }

    /** master 侧默认参量便捷构造器（{@code kind = SINGLE_VALUE}）。 */
    public MbgCriterionMock(String condition, List<String> values) {
        this(condition, values, MbgCriterionKind.SINGLE_VALUE);
    }

    /** 转成 OGNL 可读的模拟对象（{@code ${criterion.condition}} / {@code criterion.value} / 四个布尔分支）。 */
    public MockObject toMockObject() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("condition", condition);
        properties.put("value", valueForKind());
        properties.put("secondValue", values.size() > 1 ? values.get(1) : null);
        properties.put("noValue", kind == MbgCriterionKind.NO_VALUE);
        properties.put("singleValue", kind == MbgCriterionKind.SINGLE_VALUE);
        properties.put("betweenValue", kind == MbgCriterionKind.BETWEEN_VALUE);
        properties.put("listValue", kind == MbgCriterionKind.LIST_VALUE);
        return new MockObject(properties);
    }

    private Object valueForKind() {
        if (kind == MbgCriterionKind.NO_VALUE) {
            return null;
        }
        if (kind == MbgCriterionKind.LIST_VALUE) {
            return values;
        }
        return !values.isEmpty() ? values.get(0) : "1";
    }

    /**
     * 方法名 → 分支类别。MBG 的方法名后缀就是语义，比数 {@code addCriterion} 参数个数可靠：
     * {@code ...IsNull} / {@code ...IsNotNull} → 无值；{@code ...In} / {@code ...NotIn} → 列表；
     * {@code ...Between} → 区间；其余（{@code EqualTo} / {@code Like} / {@code GreaterThan} …）→ 单值。
     */
    public static MbgCriterionKind kindOf(String methodName) {
        if (methodName.endsWith("IsNull") || methodName.endsWith("IsNotNull")) {
            return MbgCriterionKind.NO_VALUE;
        }
        if (methodName.endsWith("NotIn") || methodName.endsWith("In")) {
            return MbgCriterionKind.LIST_VALUE;
        }
        if (methodName.endsWith("NotBetween") || methodName.endsWith("Between")) {
            return MbgCriterionKind.BETWEEN_VALUE;
        }
        return MbgCriterionKind.SINGLE_VALUE;
    }

    /** Criteria 元素（{@code Example_Where_Clause} 里 {@code criteria.valid} 与 {@code criteria.criteria} 的载体）。 */
    public static MockObject criteriaOf(List<MbgCriterionMock> criteria) {
        List<MockObject> mocks = new ArrayList<>();
        for (MbgCriterionMock criterion : criteria) {
            mocks.add(criterion.toMockObject());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("valid", true);
        properties.put("criteria", mocks);
        return new MockObject(properties);
    }

    /**
     * Criterion 分支类别（{@code NO_VALUE} / {@code SINGLE_VALUE} / {@code BETWEEN_VALUE} / {@code LIST_VALUE}）。
     */
    public enum MbgCriterionKind {
        NO_VALUE,
        SINGLE_VALUE,
        BETWEEN_VALUE,
        LIST_VALUE,
    }
}
