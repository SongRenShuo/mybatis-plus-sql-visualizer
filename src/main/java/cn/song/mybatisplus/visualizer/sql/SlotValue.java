// T-SyncK2JMaster177 95e107b 自 master sql/RuntimeSlotValues.kt（SlotValue data class，新增）移植
// （master 单 .kt 文件多顶层类；Java 侧 public 顶层类须独立文件，沿用 k2j 既有拆分布局）
package cn.song.mybatisplus.visualizer.sql;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 一个运行期占位的用户输入（面板 ↔ 回填引擎之间的唯一值载体）。
 *
 * <p>语义分工：</p>
 * <ul>
 *  <li>{@code type} 决定字面量形态（见 {@link ValueType}）；</li>
 *  <li>{@code text} 用户输入的原文。{@code LOOP} 按逗号 / 换行拆成多值；{@code LIST} 同；其余按单值；</li>
 *  <li>{@code branch} 仅 {@code TERNARY} 用：{@code 0} = 真分支（{@code RuntimeSlot.firstTemplate}），
 *      {@code 1} = 假分支；</li>
 *  <li>{@code include} 仅 {@code OPTIONAL} 用：{@code false} = 这段删掉。</li>
 * </ul>
 *
 * <h2>「未动作」与「填了空」的区分</h2>
 *
 * <p>{@link #isUnset()} 为 true 表示<b>用户什么都没做</b>，回填引擎必须原样保留占位（D-5：留空 = 保留占位，
 * 不是 NULL）。判断依据是四个字段都停在默认值上。</p>
 *
 * <p>由此带来一处<b>有意的</b>取舍：{@code OPTIONAL} 的「包含这段」复选框默认是勾上的，靠
 * {@code include == true} 无法区分「用户勾了」与「用户没动」——因此 {@code OPTIONAL} 必须<b>带值</b>
 * （或显式取消勾选）才算一次动作；纯字面量片段（模板里没有 {@code %s}）请在值里填任意非空文本，
 * 引擎只用它满足「已动作」判定，不影响产物。这条限制写进面板文案（券 C）。</p>
 */
public final class SlotValue {

    private final ValueType type;
    private final String text;
    private final Integer branch;
    private final boolean include;

    public SlotValue(ValueType type, String text, Integer branch, boolean include) {
        this.type = type;
        this.text = text;
        this.branch = branch;
        this.include = include;
    }

    /** Kotlin 默认参数（include=true）等价重载。 */
    public SlotValue(ValueType type, String text, Integer branch) {
        this(type, text, branch, true);
    }

    /** Kotlin 默认参数（branch=null, include=true）等价重载。 */
    public SlotValue(ValueType type, String text) {
        this(type, text, null, true);
    }

    /** Kotlin 默认参数（text="", branch=null, include=true）等价重载。 */
    public SlotValue(ValueType type) {
        this(type, "", null, true);
    }

    /** Kotlin 默认参数全缺省（AUTO / "" / null / true）等价重载。 */
    public SlotValue() {
        this(ValueType.AUTO, "", null, true);
    }

    public ValueType getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public Integer getBranch() {
        return branch;
    }

    public boolean isInclude() {
        return include;
    }

    /** 兼容 Kotlin data class 装箱 Boolean 属性的 getter 命名（getInclude）。 */
    public boolean getInclude() {
        return include;
    }

    /** 用户什么都没做（引擎据此保留占位原文）。 */
    public boolean isUnset() {
        return type == ValueType.AUTO && text.isBlank() && branch == null && include;
    }

    /** Kotlin data class {@code copy} 的手工重建（全参形态）。 */
    public SlotValue copy(ValueType type, String text, Integer branch, boolean include) {
        return new SlotValue(type, text, branch, include);
    }

    /** 未动作。 */
    public static final SlotValue UNSET = new SlotValue();

    /** 单值（默认按上下文推断类型）。 */
    public static SlotValue text(String value, ValueType type) {
        return new SlotValue(type, value, null, true);
    }

    /** Kotlin 默认参数（type=AUTO）等价重载。 */
    public static SlotValue text(String value) {
        return text(value, ValueType.AUTO);
    }

    /**
     * 多值列表（<b>一项一行</b>拼成 {@code text}，面板「每行一个」形态）。
     *
     * <p>注意：回填引擎拆分时逗号与换行<b>都是</b>分隔符（{@link RuntimeSlotValues#splitList}，与用户手输
     * {@code 1,2,3} 同口径），因此项本身含逗号的值会被拆成两项——这种值请用 {@link #string} 走单值
     * （{@code IN} 列表里只能放互不包含逗号的原子值）。</p>
     */
    public static SlotValue list(List<String> values) {
        return new SlotValue(ValueType.LIST, String.join("\n", values), null, true);
    }

    public static SlotValue list(String... values) {
        return list(List.of(values));
    }

    /** 数值。 */
    public static SlotValue number(String value) {
        return new SlotValue(ValueType.NUMBER, value, null, true);
    }

    /** 字符串。 */
    public static SlotValue string(String value) {
        return new SlotValue(ValueType.STRING, value, null, true);
    }

    /** SQL NULL。 */
    public static SlotValue sqlNull() {
        return new SlotValue(ValueType.NULL);
    }

    /**
     * 布尔（面板类型下拉选「布尔」时走这里）。
     *
     * <p>值按 {@code true}/{@code false} 渲染；Oracle / 达梦没有布尔字面量，渲染层会给「改用 1/0」的提示
     * （见 {@code ParameterReplacer} 的 {@code NO_BOOLEAN_LITERAL_DIALECTS}）。这里只做归一化（去空白 +
     * 小写），非 {@code true}/{@code false} 的文本原样保留——不猜用户想表达什么。</p>
     */
    public static SlotValue bool(String value) {
        return new SlotValue(ValueType.BOOLEAN, value.trim().toLowerCase(Locale.ROOT), null, true);
    }

    /** 三元挑支。 */
    public static SlotValue branch(int index, String value) {
        return new SlotValue(ValueType.AUTO, value, index, true);
    }

    /** Kotlin 默认参数（value=""）等价重载。 */
    public static SlotValue branch(int index) {
        return branch(index, "");
    }

    /** 可选片段：不包含。 */
    public static SlotValue excluded() {
        return new SlotValue(ValueType.AUTO, "", null, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SlotValue)) return false;
        SlotValue other = (SlotValue) o;
        return include == other.include
                && type == other.type
                && Objects.equals(text, other.text)
                && Objects.equals(branch, other.branch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, text, branch, include);
    }

    @Override
    public String toString() {
        return "SlotValue(type=" + type + ", text=" + text + ", branch=" + branch + ", include=" + include + ")";
    }
}
