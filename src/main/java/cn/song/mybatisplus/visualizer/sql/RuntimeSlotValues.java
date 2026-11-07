// T-SyncK2JMaster177 95e107b 自 master sql/RuntimeSlotValues.kt（RuntimeSlotValues data class，新增）移植
// （master 单 .kt 文件多顶层类；Java 侧 public 顶层类须独立文件，沿用 k2j 既有拆分布局）
package cn.song.mybatisplus.visualizer.sql;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一次预览内的<b>全部</b>占位值（{@code slotId -> SlotValue}）。
 *
 * <p>面板持有一份，应用/清空都是整表替换；回填引擎只读。默认 {@link #EMPTY} = 什么都没填
 * （产物必须与不回填逐字一致）。</p>
 */
public final class RuntimeSlotValues {

    /** LOOP / LIST 展开项数上限（D-7：防手滑粘贴一大坨把面板卡死）。 */
    public static final int MAX_LOOP_ITEMS = 200;

    /** 单值长度上限（字符）。 */
    public static final int MAX_VALUE_LENGTH = 2000;

    /** 数值形态（与 {@code ParameterReplacer} 的启发式同口径）。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final Map<String, SlotValue> values;

    public RuntimeSlotValues(Map<String, SlotValue> values) {
        this.values = values;
    }

    /** Kotlin 默认参数（emptyMap）等价重载。 */
    public RuntimeSlotValues() {
        this(Map.of());
    }

    /** 空表（默认行为：原样保留全部占位）。 */
    public static final RuntimeSlotValues EMPTY = new RuntimeSlotValues();

    /** 某个占位的值；null = 该占位没有任何输入。 */
    @Nullable
    public SlotValue get(String slotId) {
        return values.get(slotId);
    }

    /** 是否登记了「用户动作」（{@link SlotValue#isUnset()} 为 false）。 */
    public boolean isSet(String slotId) {
        SlotValue value = values.get(slotId);
        return value != null && !value.isUnset();
    }

    /** 已登记的占位 id（含「未动作」条目）。 */
    public java.util.Set<String> getIds() {
        return values.keySet();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** 只保留 {@link #isSet(String)} 的子集（引擎内部用）。 */
    public Map<String, SlotValue> effective() {
        Map<String, SlotValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, SlotValue> e : values.entrySet()) {
            if (!e.getValue().isUnset()) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /**
     * 保存前的<b>静态校验</b>（面板「应用」按钮用；不涉及方言，方言相关判定在渲染期给提示）。
     *
     * <p>只报问题，不修正：返回值非空时面板可以仍允许应用（渲染期会再校验一次并逐条提示），
     * 也可以直接展示给用户。</p>
     *
     * @param slots 当前预览的全部占位（用于识别未知 id 与类别相关的空值）
     * @return 问题清单（空表 = 无需提示）
     */
    public List<SlotWarning> validate(List<RuntimeSlot> slots) {
        if (values.isEmpty()) return List.of();
        Map<String, RuntimeSlot> known = new LinkedHashMap<>();
        for (RuntimeSlot slot : slots) {
            known.put(slot.getId(), slot);
        }
        List<SlotWarning> result = new ArrayList<>();
        for (Map.Entry<String, SlotValue> entry : values.entrySet()) {
            String id = entry.getKey();
            SlotValue value = entry.getValue();
            RuntimeSlot slot = known.get(id);
            if (slot == null) {
                result.add(new SlotWarning(id, "未知的运行期占位 id（当前预览里没有这个占位）"));
                continue;
            }
            if (value.isUnset()) continue;
            if (value.getText().length() > MAX_VALUE_LENGTH) {
                result.add(new SlotWarning(id, "值过长（" + value.getText().length() + " 字符，上限 " + MAX_VALUE_LENGTH + "）"));
            }
            switch (slot.getKind()) {
                case LOOP -> {
                    List<String> items = splitList(value.getText());
                    if (items.isEmpty()) {
                        result.add(new SlotWarning(id, "循环占位没有值（留空将保留占位原文）"));
                    } else if (items.size() > MAX_LOOP_ITEMS) {
                        result.add(new SlotWarning(id, "循环值过多（" + items.size() + " 项，上限 " + MAX_LOOP_ITEMS + "）"));
                    }
                }
                case TERNARY -> {
                    Integer branch = value.getBranch();
                    if (branch == null) {
                        result.add(new SlotWarning(id, "三元占位还没选分支（留空将保留占位原文）"));
                    } else if (branch != 0 && branch != 1) {
                        result.add(new SlotWarning(id, "三元分支只能是 0（真）或 1（假）"));
                    }
                }
                case OPTIONAL -> {
                    if (!value.isInclude() && !value.getText().isBlank()) {
                        result.add(new SlotWarning(id, "已取消「包含这段」，值不会生效"));
                    }
                }
                case VALUE -> {
                }
            }
            if (value.getType() == ValueType.NUMBER && !NUMBER_PATTERN.matcher(value.getText().trim()).matches()) {
                result.add(new SlotWarning(id, "选了「数字」，但输入不是数字 —— 将按字符串渲染"));
            }
        }
        return result;
    }

    /** 构造：{@code RuntimeSlotValues.of(Map.entry("rt1", SlotValue.string("a")))}（Kotlin Pair vararg 的 Java 等价形态）。 */
    @SafeVarargs
    public static RuntimeSlotValues of(Map.Entry<String, SlotValue>... pairs) {
        Map<String, SlotValue> map = new LinkedHashMap<>();
        for (Map.Entry<String, SlotValue> pair : pairs) {
            map.put(pair.getKey(), pair.getValue());
        }
        return new RuntimeSlotValues(map);
    }

    /**
     * 多值拆分（LOOP / LIST 共用）：逗号与换行，两端空白丢弃，空项丢弃。
     *
     * <p>值本身含逗号时请用换行分隔（{@link SlotValue#list} 就是这么编码的）。</p>
     */
    public static List<String> splitList(String text) {
        List<String> result = new ArrayList<>();
        for (String part : text.split("[,\\n\\r，]", -1)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RuntimeSlotValues)) return false;
        return Objects.equals(values, ((RuntimeSlotValues) o).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "RuntimeSlotValues(values=" + values + ")";
    }
}
