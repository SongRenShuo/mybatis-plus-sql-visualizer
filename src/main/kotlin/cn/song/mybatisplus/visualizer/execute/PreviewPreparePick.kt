package cn.song.mybatisplus.visualizer.execute

/**
 * UI-003：预览默认准备阶段的数据源选择分派（纯函数，零平台依赖，可进 testFast 快桶）。
 *
 * 现场：打开 XML 预览（`<select id="selectUserPageXml">`）即自动弹出「选择数据源」模态列表
 * （7 个源），面板右上仍是「未绑定数据源」。机制在 `chooseAndPrepare` 的 `else` 分支：
 * 该分支只负责「多源 → 弹原生选择器」，**不区分** [executeAfterPrepare]——预览的被动准备
 * （false）与用户点「执行 SQL」（true）走了同一条弹窗路径。
 *
 * 与同函数内 `T-DsCover` 注释声明的意图相反：「仅显式执行引导打开 Database 工具窗；预览默认
 * 准备静默——避免每次双击预览抢占侧边栏」。本对象把该意图落成可测的判据：
 *
 *  - 显式执行（`executeAfterPrepare=true`）→ 仍然弹窗（用户主动动作，选择器是主入口）；
 *  - 用户点「切换数据源」（[forceChoose] / [forceChoosePopup]）→ 仍然弹窗（否则点了等于没点）；
 *  - 预览默认准备 → 先按「学习绑定 > last-used > 单源」（[ExecBindFallback.pickTarget] 同一口径）
 *    求唯一目标源：在本次 runner 枚举里按名唯一命中 → 静默建链；否则**不弹窗、不建链**，
 *    面板保持「未绑定」灰字，等用户显式执行时再弹（方案 A 兜底）。
 */
internal object PreviewPreparePick {

    /** `chooseAndPrepare` else 分支的三种处置。 */
    sealed interface Decision {
        /** 预览准备：按名在 runners 里唯一命中，静默建链（不弹选择器）。 */
        data class Silent(val runnerName: String) : Decision

        /** 预览准备：目标不唯一/不在候选内——不弹窗也不建链，保持未绑定。 */
        data object SilentNoop : Decision

        /** 弹原生选择器（显式执行 / 切换数据源 / 目标不唯一但属主动动作）。 */
        data object Popup : Decision
    }

    /**
     * @param executeAfterPrepare true = 用户显式执行；false = 预览默认准备
     * @param forceChoose 用户点「切换数据源」（跳过共享复用与 last-used 静默命中）
     * @param forceChoosePopup 切换入口要求「即便只剩一条源也弹列表」
     * @param fallbackTargetName 「学习绑定 > last-used > 单源」解析出的唯一目标源名（null=不唯一/未命中）
     * @param runnerNames 本次平台 runner 枚举展开后的显示名
     */
    fun decide(
        executeAfterPrepare: Boolean,
        forceChoose: Boolean,
        forceChoosePopup: Boolean,
        fallbackTargetName: String?,
        runnerNames: List<String>,
    ): Decision {
        if (executeAfterPrepare || forceChoose || forceChoosePopup) return Decision.Popup
        val name = fallbackTargetName?.takeIf { it.isNotBlank() } ?: return Decision.SilentNoop
        // 同名多条 = 歧义，不猜（与 ExecBindFallback 的「歧义不猜」同口径）
        return if (runnerNames.count { it == name } == 1) Decision.Silent(name) else Decision.SilentNoop
    }
}
