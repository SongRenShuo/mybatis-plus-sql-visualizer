// T-SyncK2JMaster177 95e107b 自 master sql/RuntimeSlotValues.kt（ValueType 枚举，新增）移植
// （master 单 .kt 文件多顶层类；Java 侧 public 顶层类须独立文件，沿用 k2j 既有拆分布局）
package cn.song.mybatisplus.visualizer.sql;

/**
 * 运行期占位回填值的<b>类型档</b>（T-RuntimeSlotFill 券 B）。
 *
 * <p>面板让用户为某个占位选一种类型，渲染时按类型决定字面量形态（引号 / 裸数字 / NULL / 布尔）。
 * {@link #AUTO} 表示「让引擎按上下文推断」（{@code RuntimeSlot.hint} 的 {@code quote} / {@code inList} 语义），
 * 推不出按字符串。</p>
 */
public enum ValueType {
    /** 按上下文推断（默认）。 */
    AUTO,
    /** 字符串字面量：{@code '…'}，值内 {@code '} → {@code ''}。 */
    STRING,
    /** 数值：裸出；输入不是数字时<b>按字符串渲染</b>并给提示（绝不拼裸文本）。 */
    NUMBER,
    /** 布尔：按方言渲染；无布尔字面量的方言（Oracle / 达梦）拒绝并提示改 {@code 1} / {@code 0}。 */
    BOOLEAN,
    /** SQL {@code NULL}（裸出）；面板应同时提示「{@code = NULL} 恒不成立，判空请用 {@code IS NULL}」。 */
    NULL,
    /** 多值列表（按逗号 / 换行拆分），只在 {@code IN (...)} 或循环模板里展开。 */
    LIST
}
