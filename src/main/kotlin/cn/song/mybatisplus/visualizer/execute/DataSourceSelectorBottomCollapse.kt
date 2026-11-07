package cn.song.mybatisplus.visualizer.execute

/**
 * 整控台 SplitPane 的结果区折叠与底部回收守卫（[DataSourceSelector] 家族独立件）。
 *
 * 为什么单独成文件：master 的 T-PreviewBottomCollapse / T-ResultReclaimGuard 两块随合流带进 dev 后，
 * `DataSourceSelectorResults.kt` 从 1987 涨到 2110，越过 `GiantFileBudgetTest` 的 2000 行红线。
 * 按仓内定例「合流推翻行数预算的正解是继续拆，不是提额」，把「折叠态标记 + 折叠/展开 + 底部回收」
 * 这一整块独立出来——它们只操作 JSplitPane 与客户端属性，不依赖准备链、执行链与归因逻辑。
 *
 * `BOTTOM_COLLAPSED_MARK` 保持 `internal`：`realignDividerOnce`、`buildConsoleSplitPane`（仍在
 * Results 文件）与预览面板侧都读它，同包可见。
 */

/**
 * T-ResultReclaimGuard（2026-11-02 真机「执行后下半区空白」）：持久整控台 SplitPane 的
 * **底部回收守卫**。
 *
 * 根因链（代理逐段实证）：DBN 在新建结果页签时会把同一个结果组件重新挂回
 * `ServiceViewComponentWrapper`（Database 工具窗侧宿主）；AWT 重挂父时从旧容器摘除，
 * 而 `JSplitPane.remove(Component)` 会把 `rightComponent` 置 null —— 于是
 * `[A49] result host at submit` 里出现 `mySplitBottom=null`。既有兜底只有
 * `refreshResultsComponent` 的 600ms×8 拍轮询，且**首次检测到控制台错误就 stop**，
 * 失败语句场景下最后一拍之后被抽走的部分无人修复 → 下半区永久空白。
 *
 * 本守卫把「轮询抢回」升级为**事件驱动抢回**：底部被摘即下一拍尝试重挂；带节流
 * （[RESULT_RECLAIM_MIN_INTERVAL_MS]）、上限（[RESULT_RECLAIM_MAX]）与 `isShowing`
 * 判据（面板关闭后不再抢，让 DBN 拿回控制台）。
 */
private const val RESULT_RECLAIM_MARK = "mpsql.resultReclaimGuard"
private const val RESULT_RECLAIM_MIN_INTERVAL_MS = 300L
private const val RESULT_RECLAIM_MAX = 60L

internal fun DataSourceSelector.installResultReclaimGuard(
    sp: javax.swing.JSplitPane,
    log: com.intellij.openapi.diagnostic.Logger,
) {
    if (sp.getClientProperty(RESULT_RECLAIM_MARK) != null) return
    sp.putClientProperty(RESULT_RECLAIM_MARK, true)
    val state = longArrayOf(0L, 0L)
    sp.addContainerListener(object : java.awt.event.ContainerAdapter() {
        override fun componentRemoved(e: java.awt.event.ContainerEvent) {
            val removed = e.child as? javax.swing.JComponent ?: return
            if (removed === sp.topComponent) return
            if (sp.bottomComponent != null) return
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                reclaimResultBottom(sp, removed, state, log)
            }, com.intellij.openapi.application.ModalityState.any())
        }
    })
}

/** 单次回收尝试（[force]=true 跳过 `isShowing`，供 headless 测试直测）。 */
internal fun DataSourceSelector.reclaimResultBottom(
    sp: javax.swing.JSplitPane,
    candidate: javax.swing.JComponent,
    state: LongArray,
    log: com.intellij.openapi.diagnostic.Logger,
    force: Boolean = false,
): Boolean {
    if (sp.bottomComponent != null) return false
    if (!force && !sp.isShowing) return false
    if (state[0] >= RESULT_RECLAIM_MAX) return false
    val now = System.currentTimeMillis()
    if (now - state[1] < RESULT_RECLAIM_MIN_INTERVAL_MS) return false
    state[0]++
    state[1] = now
    (candidate.parent)?.takeIf { it !== sp }?.remove(candidate)
    sp.bottomComponent = candidate
    // T-PreviewBottomCollapse：预览折叠态下回收**不得**把 160px 兜底写回去——否则 DBN 每次把
    // 结果组件抢走、守卫抢回的这 2.5 秒后，divider 立刻被钳回 ~0.68，折叠白做（真机 2026-11-03
    // 日志实证：`collapse=true divider=304→508` 之后紧跟 `result reclaim guard: bottom re-mounted`）。
    if (sp.getClientProperty(BOTTOM_COLLAPSED_MARK) == true) {
        candidate.minimumSize = java.awt.Dimension(0, 0)
        sp.setDividerLocation(1.0)
    } else {
        candidate.minimumSize = java.awt.Dimension(candidate.minimumSize?.width ?: 400, 160)
    }
    sp.revalidate()
    sp.repaint()
    log.info(
        "[M36] result reclaim guard: bottom re-mounted " +
            "(id=${System.identityHashCode(candidate)}, n=${state[0]}, " +
            "collapsed=${sp.getClientProperty(BOTTOM_COLLAPSED_MARK) == true})",
    )
    return true
}

/**
 * T-PreviewBottomCollapse：整控台 SplitPane 的「结果区折叠态」标记（客户端属性）。
 * `true` = 底部已压到 0 高（预览态），[realignDividerOnce] 的非 force 校正必须让路。
 */
internal const val BOTTOM_COLLAPSED_MARK = "mpsql.bottomCollapsed"

/** T-PreviewBottomCollapse：折叠态常驻 resize 监听（见 [applyConsoleBottomCollapse]）。 */
private const val BOTTOM_COLLAPSE_LISTENER = "mpsql.bottomCollapseListener"

/**
 * T-PreviewBottomCollapse：折叠/展开结果区——**连最小高度与后续放大一起管**。
 *
 * 两个坑叠在一起（真机 2026-11-03 截图实证：bottom≈165px、divider≈0.67）：
 *  1. `setDividerLocation(1.0)` 被子组件 `minimumSize` 钳制——结果区为「执行后不被压回
 *     preferred 84px」设了 160px 兜底（M36.5.64），折叠必须同时把兜底让出来；
 *  2. 折叠往往发生在**对话框还没布局完**的那一拍（split 只有几十像素高），此后 split 长高时
 *     divider 停在旧的**绝对**位置，新增高度全分给结果区——于是折叠又"长"回来了。
 *     故折叠态挂常驻 resize 监听，每次放大重新贴底；展开时摘掉。
 */
internal fun DataSourceSelector.applyConsoleBottomCollapse(
    sp: javax.swing.JSplitPane,
    collapse: Boolean,
    tag: String = "",
) {
    val bottom = sp.bottomComponent
    val minBefore = bottom?.minimumSize
    val dividerBefore = sp.dividerLocation
    bottom?.minimumSize = if (collapse) {
        java.awt.Dimension(0, 0)
    } else {
        java.awt.Dimension(bottom.minimumSize?.width ?: 400, 160)
    }
    sp.putClientProperty(BOTTOM_COLLAPSED_MARK, collapse)
    sp.resizeWeight = if (collapse) 1.0 else 0.6
    val existing = sp.getClientProperty(BOTTOM_COLLAPSE_LISTENER) as? java.awt.event.ComponentAdapter
    if (collapse) {
        if (existing == null) {
            val listener = object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (sp.getClientProperty(BOTTOM_COLLAPSED_MARK) != true) return
                    sp.setDividerLocation(1.0)
                }
            }
            sp.addComponentListener(listener)
            sp.putClientProperty(BOTTOM_COLLAPSE_LISTENER, listener)
        }
    } else if (existing != null) {
        sp.removeComponentListener(existing)
        sp.putClientProperty(BOTTOM_COLLAPSE_LISTENER, null)
    }
    sp.setDividerLocation(if (collapse) 1.0 else 0.6)
    sp.revalidate()
    sp.repaint()
    com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java).info(
        "[M36][Collapse] $tag collapse=$collapse h=${sp.height} " +
            "divider=$dividerBefore→${sp.dividerLocation} bottomMin=$minBefore→${bottom?.minimumSize}",
    )
}
