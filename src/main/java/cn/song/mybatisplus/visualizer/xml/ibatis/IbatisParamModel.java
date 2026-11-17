package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 iBatis 语句的参数/条件行清单（{@link IbatisParamModelBuilder#build} 产物）。
 *
 * <p>纯数据（无 PSI、无引擎引用），随 {@code cn.song.mybatisplus.visualizer.model.PreviewResult}
 * 进面板。
 */
public final class IbatisParamModel {

    private final List<IbatisPlaceholderRow> placeholderRows;
    private final List<IbatisConditionRow> conditionRows;
    private final List<IbatisIterateRow> iterateRows;
    private final List<IbatisDollarRow> dollarRows;

    public IbatisParamModel(@NotNull List<IbatisPlaceholderRow> placeholderRows,
                            @NotNull List<IbatisConditionRow> conditionRows,
                            @NotNull List<IbatisIterateRow> iterateRows,
                            @NotNull List<IbatisDollarRow> dollarRows) {
        this.placeholderRows = List.copyOf(placeholderRows);
        this.conditionRows = List.copyOf(conditionRows);
        this.iterateRows = List.copyOf(iterateRows);
        this.dollarRows = List.copyOf(dollarRows);
    }

    public List<IbatisPlaceholderRow> getPlaceholderRows() {
        return placeholderRows;
    }

    public List<IbatisConditionRow> getConditionRows() {
        return conditionRows;
    }

    public List<IbatisIterateRow> getIterateRows() {
        return iterateRows;
    }

    public List<IbatisDollarRow> getDollarRows() {
        return dollarRows;
    }

    /** 面板是否有任何可交互控件（决定 rebuildLeftPanel 是否早退为「无参数」）。 */
    public boolean hasControls() {
        return !placeholderRows.isEmpty() || !conditionRows.isEmpty()
                || !iterateRows.isEmpty() || !dollarRows.isEmpty();
    }

    /** 条件行摘要（面板复选框标签；标签名是引擎标识符，保持原文不本地化）。 */
    public String labelOf(IbatisConditionRow row) {
        if (row.compareProperty() != null) {
            return row.kindTagName() + ": " + (row.property() != null ? row.property() : "parameter")
                    + " == " + row.compareProperty();
        }
        if (row.compareValue() != null && row.property() != null) {
            return row.kindTagName() + ": " + row.property() + " " + compareOpOf(row.kindTagName())
                    + " " + row.compareValue();
        }
        if (row.property() != null) {
            return row.kindTagName() + ": " + row.property();
        }
        return row.kindTagName() + ":";
    }

    /** compare 族标签里的比较符号（仅展示用，不影响求值）。 */
    private static String compareOpOf(String tagName) {
        if (IbatisTagKind.IS_EQUAL.getTagName().equals(tagName)) {
            return "==";
        }
        if (IbatisTagKind.IS_NOT_EQUAL.getTagName().equals(tagName)) {
            return "!=";
        }
        if (IbatisTagKind.IS_GREATER_THAN.getTagName().equals(tagName)) {
            return ">";
        }
        if (IbatisTagKind.IS_GREATER_EQUAL.getTagName().equals(tagName)) {
            return ">=";
        }
        if (IbatisTagKind.IS_LESS_THAN.getTagName().equals(tagName)) {
            return "<";
        }
        if (IbatisTagKind.IS_LESS_EQUAL.getTagName().equals(tagName)) {
            return "<=";
        }
        return "cmp";
    }
}
