package cn.song.mybatisplus.visualizer.execute

/**
 * UI-003：预览默认准备阶段的数据源选择分派（纯函数，零平台依赖，可进快测桶）。
 *
 * 现场（2026-11-04 真机）：打开 XML 预览（`<select id="selectUserPageXml">`）即自动弹出
 * 「选择数据源」模态列表（7 个源），面板右上仍是「未绑定数据源」。机制在 `chooseAndPrepare`
 * 的 `else` 分支：该分支只负责「多源 → 弹原生选择器」，**不区分** [executeAfterPrepare]——
 * 预览的被动准备（false）与用户点「执行 SQL」（true）走了同一条弹窗路径，与同函数内
 * `T-DsCover` 注释声明的意图（「预览默认准备静默」）相反。
 *
 * 本对象只回答一个问题：**走到 `else` 分支时该不该弹选择器**。
 *
 *  - 显式执行（`executeAfterPrepare=true`）→ 弹窗（用户主动动作，选择器是主入口）；
 *  - 用户点「切换数据源」（[forceChoose] / [forceChoosePopup]）→ 弹窗（否则点了等于没点）；
 *  - 预览默认准备 → **不弹窗、不建链**，面板保持「未绑定」灰字，等用户显式执行时再弹。
 *
 * 与 dev 侧 `0a2cd8b3` 的差异（**master 只实施 A 方案**）：dev 的 `PreviewPreparePick.decide`
 * 还有一个 `Silent` 结局——先按「学习绑定 > last-used > 单源」求唯一目标源后静默建链。
 * 该结局依赖 `execute/ExecBindFallback.kt`（S2-12 学习绑定回落，**master 没有这个文件**），
 * 属功能移植而非缺陷修复，故不在本次回 port 范围。master 侧「last-used 命中」与「单源直选」
 * 本来就在本分支之前各自静默建链（`allowsSilentLastUsed` / `shouldBeChosenIfAlone`），
 * 走到 `else` 的预览准备必然是「多源且无记忆」——按 A 方案静默留空即可。
 */
internal object PreviewPreparePick {

    /**
     * `chooseAndPrepare` 的 `else` 分支是否仍然弹原生选择器。
     *
     * @param executeAfterPrepare true = 用户显式执行；false = 预览默认准备
     * @param forceChoose 用户点「切换数据源」（跳过共享复用与 last-used 静默命中）
     * @param forceChoosePopup 切换入口要求「即便只剩一条源也弹列表」
     */
    fun shouldPopup(
        executeAfterPrepare: Boolean,
        forceChoose: Boolean,
        forceChoosePopup: Boolean,
    ): Boolean = executeAfterPrepare || forceChoose || forceChoosePopup
}
