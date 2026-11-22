package cn.song.mybatisplus.visualizer.ui

/**
 * T-DegradedPreviewUx P0：预览降级原因。
 *
 * 此前「预热超时 / 项目内文件建不出来 / VFS 刷新失败 / PSI 解析失败 / 产物已被消费」全部压成
 * 同一条 `panel.banner.degradedPsi`，横幅把「慢」和「故障」说成同一句话（用户 2026-10-20 截图：
 * 首开预热超预算即挂红色「本次项目内临时文件不可用」）。分型后由 [bannerKey]/[causeKey] 选文案，
 * 并且 [TIMEOUT] 是**瞬时态**——产物仍在途，走宽限期延迟披露（见 [SqlPreviewPanel.showDegradedBanner]）。
 *
 * 纯枚举、零平台依赖（便于 headless 断言）。
 */
internal enum class PreviewDegradationReason {
    /** 预热超预算、产物仍在途（宽限期内到达即静默换装，横幅不出现）。 */
    TIMEOUT,

    /** `projectPreviewFile()` 返回 null：项目/`basePath` 为空或 `.idea/sql-visualizer/preview` 建不出来。 */
    FILE_UNAVAILABLE,

    /** `LocalFileSystem.refreshAndFindFileByIoFile` 重试后仍为 null。 */
    VFS_UNAVAILABLE,

    /** `PsiManager.findFile` 返回 null。 */
    PSI_UNAVAILABLE,

    /** 预热线程内未归类的异常（方言映射/读动作等服务调用抛出）。 */
    PREWARM_ERROR,

    /** 预热产物已被上一次装配消费（同面板二次装配、未重新预热）。 */
    PRODUCT_CONSUMED,

    /** 第二级「内存 PSI」自身创建失败，落到第三级裸文档。 */
    MEMORY_PSI_FAILED,
    ;

    /** 横幅正文键：超时是「正在恢复」，其余是「已失败」；第三级连 PSI 载体都没有，沿用裸文档文案。 */
    internal fun bannerKey(): String = when (this) {
        TIMEOUT -> "panel.banner.degradedTimeout"
        MEMORY_PSI_FAILED -> "panel.banner.degradedPlain"
        else -> "panel.banner.degradedFailed"
    }

    /** 失败原因短句键（null = 该原因的正文已是完整句子，不需要 {0} 占位）。 */
    internal fun causeKey(): String? = when (this) {
        TIMEOUT, MEMORY_PSI_FAILED -> null
        FILE_UNAVAILABLE -> "panel.banner.degradedCause.file"
        VFS_UNAVAILABLE -> "panel.banner.degradedCause.vfs"
        PSI_UNAVAILABLE -> "panel.banner.degradedCause.psi"
        PREWARM_ERROR -> "panel.banner.degradedCause.prewarm"
        PRODUCT_CONSUMED -> "panel.banner.degradedCause.consumed"
    }
}

/**
 * T-DegradedPreviewUx P0：带原因的预热失败。
 *
 * 只在 `prewarmPreviewTempFile` 的三条已知失败分支使用，让消费侧（`resolvePreviewEditorSource`
 * 的 onFallback / 热态取产物）不必再靠字符串猜原因；未归类的异常仍按 [PreviewDegradationReason.PREWARM_ERROR] 兜底。
 */
internal class PreviewPrewarmException(
    val reason: PreviewDegradationReason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * T-DegradedPreviewUx P0：把 [PrewarmAwait] 的终态翻译成降级原因。
 *
 * 判定顺序：先看是否超时（超时是「产物还在路上」），再看失败对象是否携带原因，
 * 最后把「已完成且成功但没有可交付产物」视为 [PreviewDegradationReason.PRODUCT_CONSUMED]
 * （与 `assemblePreviewEditor` 的「引用相等 + 随即置 null」一次性消费语义一致）。
 */
internal fun PrewarmAwait<*>.degradationReasonOrNull(): PreviewDegradationReason? {
    // 硬失败优先于超时：预热可能在超时之后才走完 VFS 重试并最终失败，此时「稍后会自动切换」
    // 已是假话——必须报真实原因（flushDegradedGrace 到点时也会重新取一次）。
    val failure = failureOrNull()
    if (failure != null) {
        return (failure as? PreviewPrewarmException)?.reason ?: PreviewDegradationReason.PREWARM_ERROR
    }
    if (isTimedOut()) return PreviewDegradationReason.TIMEOUT
    return null
}

/**
 * T-FirstOpenDegradedLabel：装配降级时的**原因决策**（纯函数，便于单测钉住优先级）。
 *
 * 顺序：显式原因 > 预热终态（失败 / 超时） > **仍在途 → [TIMEOUT]** > 已消费。
 *
 * 最后一步为什么不能一律 PRODUCT_CONSUMED：那个原因属硬失败档（**立即**弹横幅 + 重试），
 * 而首次打开时预热通常刚发起、产物仍在路上——旧实现因此在每次首开都给用户看一条假失败提示
 *（2026-11-18 用户截图）。在途的正确语义是 TIMEOUT：宽限期静默，超时才以「仍在准备中」披露，
 * 产物到达后由迟到产物换装静默切换。
 */
internal fun fallbackDegradationReason(
    explicit: PreviewDegradationReason?,
    await: PrewarmAwait<*>?,
): PreviewDegradationReason = explicit
    ?: await?.degradationReasonOrNull()
    ?: if (await != null && !await.isDone()) {
        PreviewDegradationReason.TIMEOUT
    } else {
        PreviewDegradationReason.PRODUCT_CONSUMED
    }
