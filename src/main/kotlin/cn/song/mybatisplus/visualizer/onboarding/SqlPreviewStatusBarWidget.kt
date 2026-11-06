package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Alarm
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * 状态栏常驻入口（S2「首用即生效」第二项）：常显本工程 SQL 预览/捕获状态，点击直达日志面板。
 *
 * ## P-8 查证结论：Community 可用
 *
 * 状态栏 widget 走平台核心 EP `com.intellij.statusBarWidgetFactory`（243 平台
 * `app-client.jar!/META-INF/PlatformExtensionPoints.xml` 原文 `dynamic="true"`），**不属于**
 * `com.intellij.database` 插件面——Community 版同样有这条 EP 与 `StatusBarWidgetFactory` 接口。
 * 因此 widget 在 Community 照常出现；能力差异只体现在 tooltip 文案里：`执行 SQL` 需 Database 插件，
 * 由 [ExecuteSqlSupport.isAvailable]（本仓既有探测范式：反射探标记类，不引用 database 类型）决定
 * 是否附「执行需 Ultimate」一句。预览/复制/捕获在 Community 不受影响，故 widget 本身不降级。
 *
 * ## 线程纪律（红线：不得在 EDT 做慢操作）
 *
 * 文本由池化线程（[Alarm.ThreadToUse.POOLED_THREAD]）周期取快照算出；状态未变则不调
 * `updateWidget`（不打扰平台）。EDT 上只做「读缓存字符串 / updateWidget」两件 O(1) 事。快照读的是
 * [sqlLogManagerIfCreated]（**只读入口**：不发起服务创建、不毒化容器，见其 KDoc）+ buffer 的
 * synchronized `size()` 计数。项目没用过捕获时快照恒为「预览就绪」。
 *
 * ## 释放（Disposer 树）
 *
 * [dispose] 取消 Alarm；Alarm 本身以本 widget 为 parentDisposable，二者同树。widget 由平台
 * `Disposer.dispose(widget)` 释放（`StatusBarWidgetFactory.disposeWidget` 默认实现即此），
 * 不持有任何项目级监听，IDE 退出不会留下泄漏对象树。
 *
 * ## 已知边界（如实登记）
 *
 * 周期轮询而非事件驱动：`SqlLogManager` 没有「条目数变化」的项目级 Topic，而 widget 是常驻面板、
 * 不能要求用户先打开工具窗（那边才挂 UI 监听）。代价是每项目每 [REFRESH_INTERVAL_MS] 一次池化
 * 线程 O(1) 读取，与工具窗内状态栏既有 2s 轮询（`log/CaptureStatusBar.kt`）同口径；文本未变时
 * 不触发任何 UI 更新。
 */
class SqlPreviewStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        /** widget id：与 plugin.xml `statusBarWidgetFactory id=` 必须一字不差（平台按 id 存取可见性）。 */
        const val WIDGET_ID: String = "cn.song.mybatisplus.visualizer.status"

        /** 快照轮询周期（与工具窗内状态栏的 2s 轮询同口径）。 */
        internal const val REFRESH_INTERVAL_MS: Int = 2_000

        /**
         * 执行 SQL 的可用性在进程内稳定（`Class.forName` 探测结果不随项目变化），取一次缓存，
         * 避免每 2s 轮询都做一次 classloader 查找。
         */
        private val executeAvailableOnce: Boolean by lazy { ExecuteSqlSupport.isAvailable() }
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var statusBar: StatusBar? = null

    /** EDT 与池化线程共享的展示快照（池化线程写、EDT 读）。 */
    @Volatile
    private var snapshot: StatusBarSnapshot = buildSnapshot(executeAvailableOnce, capturing = false, entryCount = 0)

    @Volatile
    private var disposed = false

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = snapshot.text

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = snapshot.tooltip

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { e ->
        // F-10：左键弹快捷菜单（原「直达日志面板」保留为首项，取消直达——取舍：常驻入口的
        // 三条动作都需要可达性，直达只是其中一条；tooltip 文案已同步）。
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) handleWidgetClick(e)
        }
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        scheduleNextRefresh()
    }

    private fun scheduleNextRefresh() {
        if (disposed) return
        runCatching { alarm.addRequest({ refresh() }, REFRESH_INTERVAL_MS) }
    }

    private fun refresh() {
        if (disposed || project.isDisposed) return
        val next = computeSnapshot(project, executeAvailableOnce)
        if (next != snapshot) {
            snapshot = next
            val bar = statusBar
            if (bar != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed) bar.updateWidget(WIDGET_ID)
                }
            }
        }
        scheduleNextRefresh()
    }

    private fun openLogPanel() {
        if (project.isDisposed) return
        runCatching {
            ToolWindowManager.getInstance(project).getToolWindow(SqlLogManager.TOOL_WINDOW_ID)?.show()
        }
    }

    /**
     * F-10：点击处理（内部缝，返回本次点击将弹出的菜单项供测试观察——摘除菜单构建即空集，
     * 测试恰红）。EDT 调用；弹出位置由 [e] 的组件决定。
     */
    internal fun handleWidgetClick(e: MouseEvent): List<WidgetMenuItem> {
        val items = buildWidgetMenuItems(currentMenuState())
        if (items.isNotEmpty()) showPopup(items, e)
        return items
    }

    private fun showPopup(items: List<WidgetMenuItem>, e: MouseEvent) {
        runCatching {
            val step = object : BaseListPopupStep<WidgetMenuItem>(null, items) {
                override fun getTextFor(value: WidgetMenuItem): String = value.label
                override fun onChosen(value: WidgetMenuItem, finalChoice: Boolean): PopupStep<*>? {
                    return doFinalStep { executeWidgetMenuAction(project, value.action) }
                }
            }
            val anchor = e.component
            val popup = JBPopupFactory.getInstance().createListPopup(step)
            if (anchor != null && anchor.isShowing) popup.showInCenterOf(anchor) else popup.showInFocusCenter()
        }
    }

    /** 菜单状态取数（EDT 调用，只读内存态；与工具窗内状态栏同口径）。 */
    private fun currentMenuState(): WidgetMenuState {
        val summary = runCatching { AgentManager.getInstance(project).statusSummary() }.getOrNull()
        return WidgetMenuState(
            enhancedEnabled = summary != null,
            paused = runCatching { AgentManager.getInstance(project).userPaused }.getOrDefault(false),
        )
    }

    override fun dispose() {
        disposed = true
        statusBar = null
        alarm.cancelAllRequests()
    }
}

/**
 * 展示快照（纯数据）：状态栏文本 + tooltip。相等性参与「变没变」判定（不相等才刷新 UI）。
 */
internal data class StatusBarSnapshot(
    val text: String,
    val tooltip: String,
)

/**
 * 纯快照构造（零平台依赖，headless 直接覆盖矩阵）：
 *  - 有捕获条目 → 「SQL 预览 · N 条」，否则「SQL 预览」；
 *  - 正在捕获 → tooltip 追加「正在捕获」；
 *  - 执行不可用（Community）→ tooltip 追加 Database 插件说明。
 */
internal fun buildSnapshot(executeAvailable: Boolean, capturing: Boolean, entryCount: Int): StatusBarSnapshot {
    val text = if (entryCount > 0) {
        MpSqlVisualizerBundle.message("statusbar.sqlviz.count", entryCount)
    } else {
        MpSqlVisualizerBundle.message("statusbar.sqlviz.ready")
    }
    val tooltip = buildString {
        append(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip"))
        if (capturing) append(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip.capturing"))
        if (!executeAvailable) append(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip.community"))
    }
    return StatusBarSnapshot(text = text, tooltip = tooltip)
}

/**
 * 平台侧取快照（**可在池化线程调用**）：只读入口取 manager（不创建服务），读 capturing 与条目数。
 * 未用过捕获 → 视为 0 条 / 未捕获（与「服务未创建 = 功能未启用」的既有语义一致）。
 */
internal fun computeSnapshot(project: Project, executeAvailable: Boolean): StatusBarSnapshot {
    val manager = sqlLogManagerIfCreated(project)
    return buildSnapshot(
        executeAvailable = executeAvailable,
        capturing = manager?.capturing ?: false,
        entryCount = manager?.entries?.size() ?: 0,
    )
}

/**
 * 状态栏 widget 工厂。状态栏 widget **可能在 BGT 实例化**（平台 KDoc 原文），故工厂与 widget 构造器
 * 都不建 Swing 组件——本实现全程只用文本 presentation。
 */
class SqlPreviewStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = SqlPreviewStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("statusbar.sqlviz.widgetName")

    /**
     * 纯平台能力：Community/Ultimate 都返回 true（P-8：EP 属平台核心、不依赖 Database 插件；
     * 能力差异由 [buildSnapshot] 的 tooltip 文案表达）。项目关闭窗口内不创建。
     */
    override fun isAvailable(project: Project): Boolean = isWidgetAvailable(project.isDisposed)

    override fun createWidget(project: Project): StatusBarWidget = SqlPreviewStatusBarWidget(project)

    override fun isEnabledByDefault(): Boolean = true
}

/** [SqlPreviewStatusBarWidgetFactory.isAvailable] 的纯判定（测试缝：不依赖真实 Project）。 */
internal fun isWidgetAvailable(projectDisposed: Boolean): Boolean = !projectDisposed

// ---------------------------------------------------------------------------
// F-10（widget 点击菜单化）：菜单模型与纯构建
// ---------------------------------------------------------------------------

/** 菜单项动作（全部走既有入口，零新语义：工具窗/AgentManager 暂停位/设置页）。 */
internal enum class WidgetMenuAction {
    /** 打开 SQL 日志工具窗（原 widget 点击行为，保留为首项）。 */
    OPEN_LOG_PANEL,

    /** 暂停/恢复增强捕获（AgentManager.setUserPaused 翻转既有位）。 */
    TOGGLE_PAUSE,

    /** 打开插件设置页（既有 PluginSettingsFolderConfigurable 入口）。 */
    OPEN_SETTINGS,
}

/** 菜单项：标签（bundle 文案随语言）+ 动作。 */
internal data class WidgetMenuItem(val label: String, val action: WidgetMenuAction)

/** 菜单构建输入（只读内存态快照）。 */
internal data class WidgetMenuState(
    /** 增强捕获总开关是否开启（[AgentManager.statusSummary] 非 null = 开）。 */
    val enhancedEnabled: Boolean,
    /** 增强捕获当前是否已暂停（决定 TOGGLE_PAUSE 项的标签）。 */
    val paused: Boolean,
)

/**
 * 菜单项纯构建（headless 覆盖矩阵）：
 * - 首项恒为「打开 SQL 日志面板」（原点击行为的落位）；
 * - 增强通道开启时第二项为「暂停捕获/恢复捕获」（标签随 [WidgetMenuState.paused] 取反语义）；
 *   通道关闭时暂停位无意义，不出项（与面板侧方案 B 隐藏语义一致，不造无入口项）；
 * - 末项「打开设置页」恒在。
 */
internal fun buildWidgetMenuItems(state: WidgetMenuState): List<WidgetMenuItem> {
    val items = mutableListOf(
        WidgetMenuItem(MpSqlVisualizerBundle.message("statusbar.sqlviz.menu.openLog"), WidgetMenuAction.OPEN_LOG_PANEL),
    )
    if (state.enhancedEnabled) {
        val pauseKey = if (state.paused) "statusbar.sqlviz.menu.resume" else "statusbar.sqlviz.menu.pause"
        items += WidgetMenuItem(MpSqlVisualizerBundle.message(pauseKey), WidgetMenuAction.TOGGLE_PAUSE)
    }
    items += WidgetMenuItem(MpSqlVisualizerBundle.message("statusbar.sqlviz.menu.settings"), WidgetMenuAction.OPEN_SETTINGS)
    return items
}

/** 菜单动作执行（全部走既有入口；调用方保证 EDT 与项目存活）。 */
internal fun executeWidgetMenuAction(project: Project, action: WidgetMenuAction) {
    when (action) {
        WidgetMenuAction.OPEN_LOG_PANEL ->
            runCatching {
                ToolWindowManager.getInstance(project).getToolWindow(SqlLogManager.TOOL_WINDOW_ID)?.show()
            }
        WidgetMenuAction.TOGGLE_PAUSE ->
            runCatching {
                val am = AgentManager.getInstance(project)
                am.setUserPaused(!am.userPaused)
            }
        WidgetMenuAction.OPEN_SETTINGS ->
            runCatching {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsFolderConfigurable::class.java)
            }
    }
}
