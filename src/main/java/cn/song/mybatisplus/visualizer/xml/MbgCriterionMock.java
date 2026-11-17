package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 Criterion 的模拟值（T-MbgExamplePreview M2）。
 *
 * @param condition {@code addCriterion} 的第一个实参常量（如 {@code cust_id =} / {@code cust_id in}），已含操作符
 * @param values    调用点实参原文（{@code IsNull} 类为空；{@code Between} 两个；其余一个）
 * @param kind      由方法名后缀判定（见 {@link #kindOf}）
 */
public record MbgCriterionMock(
        @NotNull String condition,
        @NotNull List<String> values,
        @NotNull MbgCriterionKind kind) {

    public MbgCriterionMock {
        values = List.copyOf(values);
    }

    public MbgCriterionMock(@NotNull String condition) {
        this(condition, List.of(), MbgCriterionKind.SINGLE_VALUE);
    }

    public MbgCriterionMock(@NotNull String condition, @NotNull List<String> values) {
        this(condition, values, MbgCriterionKind.SINGLE_VALUE);
    }

    /** 转成 OGNL 可读的模拟对象（{@code ${criterion.condition}} / {@code criterion.value} / 四个布尔分支）。 */
    public MockObject toMockObject() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("condition", condition);
        props.put("value", valueForKind());
        props.put("secondValue", values.size() > 1 ? values.get(1) : null);
        props.put("noValue", kind == MbgCriterionKind.NO_VALUE);
        props.put("singleValue", kind == MbgCriterionKind.SINGLE_VALUE);
        props.put("betweenValue", kind == MbgCriterionKind.BETWEEN_VALUE);
        props.put("listValue", kind == MbgCriterionKind.LIST_VALUE);
        return new MockObject(props);
    }

    private Object valueForKind() {
        return switch (kind) {
            case NO_VALUE -> null;
            case LIST_VALUE -> values;
            default -> values.isEmpty() ? "1" : values.get(0);
        };
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
        List<MockObject> elements = new ArrayList<>(criteria.size());
        for (MbgCriterionMock criterion : criteria) {
            elements.add(criterion.toMockObject());
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("valid", true);
        props.put("criteria", elements);
        return new MockObject(props);
    }
}
