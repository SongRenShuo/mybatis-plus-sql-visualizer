package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 装配降级原因决策测试（T-FirstOpenDegradedLabel）。
 *
 * 缺陷背景：`assemblePreviewEditor` 把「预热产物**仍在途**」兜底成 `PRODUCT_CONSUMED`
 * （「完整编辑器尚未就绪」，属硬失败档 → 立即弹横幅 + 重试），而首开时预热通常刚发起、
 * 产物还在路上，于是每次首开都给用户看一条假失败提示，随后产物到达又静默换装。
 *
 * 纯逻辑（`PrewarmAwait` 只依赖 JDK），自动进 testFast 快桶；不给 await 挂真实预热，
 * 只看它对外暴露的终态。
 *
 * k2j 移植注：顶层函数 `fallbackDegradationReason` 落 `PreviewDegradationReasonKt`
 * 门面静态方法（master 为 ui 包顶层函数直调）；`PrewarmAwait` 具名实参改位置实参
 * （Java 构造 `PrewarmAwait(long, Consumer<Runnable>)`，lambda 走 SAM 转换）。
 */
class PreviewFallbackReasonTest {

    private fun pendingAwait() = PrewarmAwait<String>(60_000L, { it.run() })

    @Test
    fun `产物在途时判超时语义而不是已消费`() {
        val await = pendingAwait()
        assertEquals(
            "在途 → TIMEOUT（宽限期静默，超时才以「仍在准备中」披露）",
            PreviewDegradationReason.TIMEOUT,
            PreviewDegradationReasonKt.fallbackDegradationReason(null, await),
        )
    }

    @Test
    fun `预热硬失败时用失败原因`() {
        val await = pendingAwait()
        await.fail(PreviewPrewarmException(PreviewDegradationReason.VFS_UNAVAILABLE, "refresh null"))
        assertEquals(
            PreviewDegradationReason.VFS_UNAVAILABLE,
            PreviewDegradationReasonKt.fallbackDegradationReason(null, await),
        )
    }

    @Test
    fun `产物已交付但本次装配拿不到时判已消费`() {
        val await = pendingAwait()
        await.complete("product")
        assertEquals(
            "已完成但产物不在本次手里 → PRODUCT_CONSUMED（「完整编辑器尚未就绪」+ 重试）",
            PreviewDegradationReason.PRODUCT_CONSUMED,
            PreviewDegradationReasonKt.fallbackDegradationReason(null, await),
        )
    }

    @Test
    fun `没有预热载体时判已消费`() {
        assertEquals(
            PreviewDegradationReason.PRODUCT_CONSUMED,
            PreviewDegradationReasonKt.fallbackDegradationReason(null, null),
        )
    }

    @Test
    fun `显式原因优先于预热终态`() {
        val await = pendingAwait()
        assertEquals(
            PreviewDegradationReason.PSI_UNAVAILABLE,
            PreviewDegradationReasonKt.fallbackDegradationReason(PreviewDegradationReason.PSI_UNAVAILABLE, await),
        )
    }
}
