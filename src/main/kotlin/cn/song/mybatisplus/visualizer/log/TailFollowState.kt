package cn.song.mybatisplus.visualizer.log

internal enum class TailFollowMode { FOLLOWING, PAUSED }

internal class TailFollowState {
    var mode: TailFollowMode = TailFollowMode.FOLLOWING
        private set

    fun onUserScroll() {
        mode = TailFollowMode.PAUSED
    }

    fun onScrollToEnd() {
        mode = TailFollowMode.FOLLOWING
    }

    fun onClear() {
        mode = TailFollowMode.FOLLOWING
    }

    val shouldFollow: Boolean get() = mode == TailFollowMode.FOLLOWING
}

/** 「已滚到底」判据的像素容差（滚动条取整误差）。 */
internal const val SCROLL_BOTTOM_TOLERANCE_PX = 2

/**
 * 这次滚动条调整事件是否等于「用户把列表滚到底」（T-LogListAnchor 券03）。
 *
 * 调整事件本身**不带手势来源**：贴底、锚点复原、模型增删引起的 clamp 都会走到同一个监听器。
 * 旧实现只看「位置到底」，于是停在贴底一行内时，头部淘汰让内容变矮、滚动条被 clamp 到底
 * （位置**变小**）也会被当成用户手势，暂停态被静默改回跟随。三条否决：
 *
 *  1. [programmatic]：贴底/锚点复原是我们自己发起的滚动；
 *  2. `currentValue < previousValue`：用户往底部滚只会让位置变大，变小只能是内容变短后的 clamp；
 *  3. 未到可滚最大值。判据是 `maximum - visibleAmount`——Swing 的 `maximum` 是内容总高语义，
 *     直接比 `maximum` 永远不成立。
 */
internal fun isUserScrollToBottom(
    previousValue: Int,
    currentValue: Int,
    maxScrollable: Int,
    programmatic: Boolean,
): Boolean {
    if (programmatic) return false
    if (previousValue >= 0 && currentValue < previousValue) return false
    return maxScrollable > 0 && currentValue >= maxScrollable - SCROLL_BOTTOM_TOLERANCE_PX
}
