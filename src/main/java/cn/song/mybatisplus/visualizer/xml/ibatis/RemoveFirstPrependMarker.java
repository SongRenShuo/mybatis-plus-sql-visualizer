package cn.song.mybatisplus.visualizer.xml.ibatis;

/**
 * {@code removeFirstPrepend} marker（引擎 {@code RemoveFirstPrependMarker}）。
 *
 * <p><b>本机制是 iBatis 2 独有的文本拼装逻辑，MyBatis 3 没有对应物</b>：
 * {@code <dynamic prepend="...">} 会「吃掉」第一个产出内容的子标签的 prepend；
 * 吃掉动作通过 {@link IbatisTagContext#disableParentRemoveFirstPrepend()} 操作
 * <b>marker 栈的第 1 层（父层）</b>实现——引擎源码 {@code peekRemoveFirstPrependMarker} /
 * {@code disableRemoveFirstPrependMarker} 都是 {@code stack.get(1)}，这是<b>刻意如此</b>
 * （子标签的 doStartFragment 已把自己压在第 0 层）。
 */
final class RemoveFirstPrependMarker {

    final IbatisTagNode tag;
    boolean removeFirstPrepend;

    RemoveFirstPrependMarker(IbatisTagNode tag, boolean removeFirstPrepend) {
        this.tag = tag;
        this.removeFirstPrepend = removeFirstPrepend;
    }
}
