// T-SyncK2JMaster177 95e107b 自 master sql/RuntimeSlot.kt（SlotKind 枚举，新增）移植
// （master 单 .kt 文件双顶层类；Java 侧 public 顶层类须独立文件，沿用 k2j 既有拆分布局）
package cn.song.mybatisplus.visualizer.sql;

/**
 * 运行期占位的语义类别（T-RuntimeSlotFill 券 A）。
 *
 * <p>「运行期占位」= 预览 SQL 里那条以 {@code 运行时值: } 开头、整条被块注释符包住的占位：它代表一段只有到运行期
 * 才能确定内容的 Java 拼接片段（见 {@code cn.song.mybatisplus.visualizer.psource.SqlStringConcatenator}）。
 * 类别决定面板给它什么控件、回填时按什么形态展开：</p>
 * <ul>
 *  <li>{@link #VALUE} 单值——渲染一个字面量；</li>
 *  <li>{@link #LOOP} 循环体内追加——运行期重复 N 次，用户给一组值，按模板首项/其余拼接；</li>
 *  <li>{@link #TERNARY} 三元两支——运行期只走一支，用户挑一支；</li>
 *  <li>{@link #OPTIONAL} 可选片段（{@code if} / {@code switch} / 三元 / 回调内追加）——用户决定这段在不在。</li>
 * </ul>
 *
 * <p>类别只表达<b>形态</b>，不含任何回填逻辑：渲染与替换属于券 B（{@code ParameterReplacer}），
 * 本模型仅承载「这个占位是什么、展开成什么样」的事实。</p>
 */
public enum SlotKind {
    VALUE,
    LOOP,
    TERNARY,
    OPTIONAL
}
