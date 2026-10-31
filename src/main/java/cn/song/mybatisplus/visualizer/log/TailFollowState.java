package cn.song.mybatisplus.visualizer.log;

/** 用户滚动暂停尾随，只有明确回到底部或清空才恢复。 */
public final class TailFollowState {
    private TailFollowMode mode = TailFollowMode.FOLLOWING;

    public TailFollowMode getMode() {
        return mode;
    }

    public void onUserScroll() {
        mode = TailFollowMode.PAUSED;
    }

    public void onScrollToEnd() {
        mode = TailFollowMode.FOLLOWING;
    }

    public void onClear() {
        mode = TailFollowMode.FOLLOWING;
    }

    public boolean getShouldFollow() {
        return mode == TailFollowMode.FOLLOWING;
    }

    /** 「已滚到底」判据的像素容差（滚动条取整误差）。 */
    public static final int SCROLL_BOTTOM_TOLERANCE_PX = 2;

    /**
     * 这次滚动条调整事件是否等于「用户把列表滚到底」（T-LogListAnchor 券03）。
     *
     * <p>调整事件本身<b>不带手势来源</b>：贴底、锚点复原、模型增删引起的 clamp 都会走到同一个监听器。
     * 旧实现只看「位置到底」，于是停在贴底一行内时，头部淘汰让内容变矮、滚动条被 clamp 到底
     * （位置<b>变小</b>）也会被当成用户手势，暂停态被静默改回跟随。三条否决：
     * <ol>
     *  <li>{@code programmatic}：贴底/锚点复原是我们自己发起的滚动；</li>
     *  <li>{@code currentValue < previousValue}：用户往底部滚只会让位置变大，变小只能是内容变短后的 clamp；</li>
     *  <li>未到可滚最大值。判据是 {@code maximum - visibleAmount}——Swing 的 {@code maximum} 是内容总高语义，
     *      直接比 {@code maximum} 永远不成立。</li>
     * </ol></p>
     */
    public static boolean isUserScrollToBottom(
            int previousValue,
            int currentValue,
            int maxScrollable,
            boolean programmatic
    ) {
        if (programmatic) return false;
        if (previousValue >= 0 && currentValue < previousValue) return false;
        return maxScrollable > 0 && currentValue >= maxScrollable - SCROLL_BOTTOM_TOLERANCE_PX;
    }
}
