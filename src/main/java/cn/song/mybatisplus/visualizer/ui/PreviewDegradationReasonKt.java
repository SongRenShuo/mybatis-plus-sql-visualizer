// 自 master ui/PreviewDegradationReason.kt 顶层扩展函数移植（T-DegradedPreviewUx P0）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Kotlin 顶层扩展函数承载（{@code PreviewDegradationReason.kt} → Java facade）。 */
public final class PreviewDegradationReasonKt {

    private PreviewDegradationReasonKt() {
    }

    /**
     * T-DegradedPreviewUx P0：把 {@link PrewarmAwait} 的终态翻译成降级原因。
     *
     * <p>判定顺序：先看是否超时（超时是「产物还在路上」），再看失败对象是否携带原因，
     * 最后把「已完成且成功但没有可交付产物」视为 {@link PreviewDegradationReason#PRODUCT_CONSUMED}
     * （与 {@code assemblePreviewEditor} 的「引用相等 + 随即置 null」一次性消费语义一致）。</p>
     */
    @Nullable
    public static PreviewDegradationReason degradationReasonOrNull(@NotNull PrewarmAwait<?> await) {
        // 硬失败优先于超时：预热可能在超时之后才走完 VFS 重试并最终失败，此时「稍后会自动切换」
        // 已是假话——必须报真实原因（flushDegradedGrace 到点时也会重新取一次）。
        Throwable failure = await.failureOrNull();
        if (failure != null) {
            return failure instanceof PreviewPrewarmException prewarm
                    ? prewarm.getReason()
                    : PreviewDegradationReason.PREWARM_ERROR;
        }
        if (await.isTimedOut()) {
            return PreviewDegradationReason.TIMEOUT;
        }
        return null;
    }

    /**
     * T-FirstOpenDegradedLabel：装配降级时的<b>原因决策</b>（纯函数，便于单测钉住优先级）。
     *
     * <p>顺序：显式原因 &gt; 预热终态（失败 / 超时）&gt; <b>仍在途 → {@link PreviewDegradationReason#TIMEOUT}</b> &gt; 已消费。</p>
     *
     * <p>最后一步为什么不能一律 PRODUCT_CONSUMED：那个原因属硬失败档（<b>立即</b>弹横幅 + 重试），
     * 而首次打开时预热通常刚发起、产物仍在路上——旧实现因此在每次首开都给用户看一条假失败提示
     * （2026-11-18 用户截图）。在途的正确语义是 TIMEOUT：宽限期静默，超时才以「仍在准备中」披露，
     * 产物到达后由迟到产物换装静默切换。</p>
     */
    @NotNull
    public static PreviewDegradationReason fallbackDegradationReason(
            @Nullable PreviewDegradationReason explicit,
            @Nullable PrewarmAwait<?> await) {
        if (explicit != null) {
            return explicit;
        }
        PreviewDegradationReason fromAwait = await != null ? degradationReasonOrNull(await) : null;
        if (fromAwait != null) {
            return fromAwait;
        }
        return await != null && !await.isDone()
                ? PreviewDegradationReason.TIMEOUT
                : PreviewDegradationReason.PRODUCT_CONSUMED;
    }
}
