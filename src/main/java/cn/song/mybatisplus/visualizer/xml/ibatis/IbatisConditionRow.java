package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * 条件行（{@code isXxx}）：开关 + 摘要 + 引入的参数名。
 *
 * <p>{@code id} 是面板勾选键（{@code ibatis-cond-N}，单次 build 内稳定）；label 由
 * {@link IbatisParamModel#labelOf} 生成（{@code isNotNull: id} / {@code isEqual: status == "1"} 形态）。
 */
public record IbatisConditionRow(
        String id,
        String kindTagName,
        @Nullable String property,
        @Nullable String compareValue,
        @Nullable String compareProperty) {

    public IbatisConditionRow {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(kindTagName, "kindTagName");
    }

    /** 只带必填三项的形态。 */
    public IbatisConditionRow(String id, String kindTagName, @Nullable String property) {
        this(id, kindTagName, property, null, null);
    }
}
