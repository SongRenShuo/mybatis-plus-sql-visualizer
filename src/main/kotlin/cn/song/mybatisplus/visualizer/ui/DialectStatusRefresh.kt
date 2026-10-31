package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer

/**
 * T-DialectStatusLiveRefresh 方案 A/B：方言状态行随执行/绑定即时刷新。
 *
 * 背景：方言决策在预览生成时算一次并固化进 `PreviewResult.dialectDecision`，执行 SQL 虽已通过
 * 提交闸写入模块数据源学习（`DataSourceSelectorConsole` 的 `remember`），但面板没有重算入口——
 * 头部停留在「候选方言不一致」，须关开预览才更新（真机实锤）。
 *
 * 本文件只做两件事：
 * 1. [DialectStatusRefreshPolicy.pickStronger]：纯函数，决定新决策能否替换旧决策（仅升级、
 *    手动/冲突不被覆盖）；
 * 2. [refreshDialectStatusRow]：池线程重算决策（含绑定源信号），EDT 轻量重组方言段。
 *
 * 不新增监听器（循 R30 零监听器口径），挂点复用 [embedConsole] 既有单点钩子。
 */
internal object DialectStatusRefreshPolicy {
    private fun rank(decision: DialectDecisionEngine.Decision): Int = when (decision.status) {
        DialectDecisionEngine.Status.MANUAL,
        DialectDecisionEngine.Status.CONFLICT -> 3
        DialectDecisionEngine.Status.RESOLVED -> 2
        DialectDecisionEngine.Status.AMBIGUOUS -> 1
        DialectDecisionEngine.Status.UNAVAILABLE -> 0
    }

    /**
     * 新决策可替换旧决策时返回新值；null＝保持旧值。
     *
     * 规则：手动与冲突是终态（用户选择/安全阻断），刷新永不覆盖；其余按等级只升不降，
     * 同级且内容不同视为更新（如从模块学习升级到当前绑定源，证据更新鲜）。
     */
    fun pickStronger(
        old: DialectDecisionEngine.Decision?,
        fresh: DialectDecisionEngine.Decision,
    ): DialectDecisionEngine.Decision? {
        // 刷新链不产出手动/冲突态——手动=用户选择、冲突=安全阻断，只能由完整决策链生成，
        // 重算路径即便算出也绝不冒充（顺序在 old==null 短路之前，防 null 旧值时终态混入）。
        if (fresh.status == DialectDecisionEngine.Status.MANUAL ||
            fresh.status == DialectDecisionEngine.Status.CONFLICT
        ) return null
        if (old == null) return fresh
        if (old.status == DialectDecisionEngine.Status.MANUAL ||
            old.status == DialectDecisionEngine.Status.CONFLICT
        ) return null
        val oldRank = rank(old)
        val freshRank = rank(fresh)
        return when {
            freshRank > oldRank -> fresh
            freshRank == oldRank && fresh != old -> fresh
            else -> null
        }
    }
}

/**
 * 面板已绑定/上次使用数据源的方言（方案 B 信号）。只在池线程调用：活控制台取名走反射 +
 * ReadAction，记忆回退走 URL 探测（带 TTL 缓存），均不可在 EDT 直调。
 */
internal fun SqlPreviewPanel.boundDataSourceDialectForDecision(): String? {
    val consoleDataSource = runCatching { DataSourceSelector.boundConsoleDataSource() }.getOrNull()
    if (consoleDataSource != null) {
        val liveDialect = runCatching {
            ExecutionDialectGate.fromDataSource(consoleDataSource).dialectName
        }.getOrNull()
        if (!liveDialect.isNullOrBlank()) return liveDialect
    }
    val remembered = rememberedDataSourceNameForDisplay() ?: return null
    val url = runCatching { DataSourceUrlProbe.urlByName(project, remembered) }.getOrNull()
    return UrlSubprotocolDialects.dialectName(url)
}

/**
 * 执行/绑定后的方言状态刷新：池线程重算 → EDT 仅升级替换 → 只重组方言段。
 *
 * 幂等且防抖：决策未变（或更弱）时不触碰 UI；面板已 dispose 时丢弃晚到结果。
 *
 * @param rerenderSql false = 只刷新头部方言徽章，不按新方言重写 SQL 文本（UX 换源重渲染：
 *        last-used 变更只需头部跟上，不得改写用户正在编辑的文本）。
 */
internal fun SqlPreviewPanel.refreshDialectStatusRow(rerenderSql: Boolean = true) {
    val proj = project ?: return
    if (current == null) return
    val moduleKey = sourceModuleKey
    ApplicationManager.getApplication().executeOnPooledThread {
        val fresh = runCatching {
            DialectRegistry.decision(
                project = proj,
                moduleKey = moduleKey,
                boundDataSourceDialect = boundDataSourceDialectForDecision(),
            )
        }.onFailure { e ->
            Logger.getInstance(SqlPreviewPanel::class.java)
                .warn("[T-DialectStatusLiveRefresh] re-decide FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }.getOrNull() ?: return@executeOnPooledThread
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(this)) return@invokeLater
            val chosen = DialectStatusRefreshPolicy.pickStronger(currentDialectDecision, fresh)
                ?: return@invokeLater
            currentDialectDecision = chosen
            applyDialectStatusSegment()
            // T-DialectGateExit P1：绑定源给出更强方言时，把 SQL 也重渲染到该方言
            if (rerenderSql) {
                rerenderIfBoundDialectDiffers(chosen)
            }
        }, ModalityState.any())
    }
}

/**
 * UX 换源重渲染：last-used 变更后的头部刷新（防抖 + 线程归位）。
 *
 * 广播可能来自执行链的任意线程，也可能是连写模块级/项目级两次；这里统一收口：非 EDT 先投递
 * 回 EDT，再经 120ms Timer 合并成一拍。单测模式同步执行（headless 无 EDT 泵，既有测试依赖
 * 「改记忆 → 立刻可读」的同步语义，与 [SqlPreviewPanel.scheduleRender] 同款）。
 *
 * 只刷新头部（绑定行 + 方言徽章），**不**重渲染 SQL：换源事件不是用户编辑，不得改写编辑器文本。
 */
internal fun SqlPreviewPanel.scheduleHeaderSourceRefresh() {
    if (previewDisposed || Disposer.isDisposed(this)) return
    val app = ApplicationManager.getApplication() ?: return
    if (app.isUnitTestMode) {
        refreshHeaderSourceNow()
        return
    }
    if (!app.isDispatchThread) {
        app.invokeLater({ scheduleHeaderSourceRefresh() }, ModalityState.any())
        return
    }
    headerSourceRefreshTimer.restart()
}

/** 头部刷新单拍（EDT）：绑定行 + 方言徽章。 */
internal fun SqlPreviewPanel.refreshHeaderSourceNow() {
    if (previewDisposed || Disposer.isDisposed(this)) return
    refreshBindStateRow()
    refreshDialectStatusRow(rerenderSql = false)
}

/**
 * T-DialectGateExit P1：绑定源给出更强方言且与当前渲染不同 → 按目标方言重渲染。
 *
 * 不这么做的话「状态行说 A、SQL 文本是 B、执行目标是 C」可以并存（UI-040 现场）：
 * 用户绑了 MySQL 源，但 SQL 仍按模块学到的 PostgreSQL 渲染，执行/回滚全被方言闸挡住。
 *
 * 护栏：只在用户没改过编辑器文本时做（`lastRenderedSql == 当前文本`）——重渲染整篇重写，
 * 不能吞掉用户的编辑；模型不支持无损重渲染时 [rerenderForExecutionTarget] 自己返回 false。
 */
private fun SqlPreviewPanel.rerenderIfBoundDialectDiffers(
    decision: cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision,
) {
    val targetName = decision.dialectName ?: return
    val rendered = current?.effectiveDialect?.name ?: return
    val text = currentEditorSql()
    val untouched = text != null && text == lastRenderedSql
    if (!shouldRerenderForBoundDialect(decision.source, targetName, rendered, untouched)) return
    rerenderForExecutionTarget(targetName)
}

/**
 * T-DialectGateExit P1 判据（纯函数，便于单测）：
 * 绑定源给出方言、与当前渲染不同、且用户没改过编辑器文本时，才按目标方言重渲染。
 *
 * - 只有 [DialectDecisionEngine.Source.BOUND_DATASOURCE] 生效：模块学习/项目共识都不是「执行目标」，
 *   不该因为一条弱证据就改写 SQL；
 * - 文本被编辑过时不重渲染（重渲染整篇重写，会吞掉用户改动）。
 */
internal fun shouldRerenderForBoundDialect(
    source: cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Source?,
    targetDialectName: String?,
    renderedDialectName: String?,
    textUntouched: Boolean,
): Boolean {
    if (source != cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Source.BOUND_DATASOURCE) return false
    if (targetDialectName.isNullOrBlank() || renderedDialectName.isNullOrBlank()) return false
    if (targetDialectName.equals(renderedDialectName, ignoreCase = true)) return false
    return textUntouched
}

/**
 * 按当前 [SqlPreviewPanel.currentDialectDecision] 重组 meta 行方言段与 tooltip。
 *
 * 与 [SqlPreviewPanel.updateMeta] 的拼装语义逐字对齐（含 T-SourceJumpFunnel P5 的
 * 「主体恒优先、摘要先截」形态与 T-StackHoverUi 的 tooltip 三段顺序：meta/警示/perf →
 * 方言详情 → 能力矩阵）；无决策时可见行回落纯 meta 主体、tooltip 无方言段。
 * 尾部 [SqlPreviewPanel.applyMetaTruncation] 内部按 lastMetaBudget 短路——段落变化必须
 * 复位预算后重截，否则窄窗下截断点停在旧文本上。
 */
internal fun SqlPreviewPanel.applyDialectStatusSegment() {
    val dialectStatus = currentDialectDecision?.let { DialectPreviewStatusText.describe(it) }
    metaLineHead = if (dialectStatus == null) metaCoreText
    else dialectStatus.visible + "   " + metaCoreText
    metaLineText = metaLineHead + metaLineDigest
    metaTooltipBase = metaTooltipCore +
        dialectStatus?.let { "\n\n${it.details}" }.orEmpty() +
        metaTooltipSuffix
    metaLabel.toolTipText = metaTooltipBase
    lastMetaBudget = -1
    applyMetaTruncation()
}

