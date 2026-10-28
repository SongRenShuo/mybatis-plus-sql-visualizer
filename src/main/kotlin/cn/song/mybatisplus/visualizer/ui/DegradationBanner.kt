package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout

/**
 * 面板内嵌诚实降级横幅（T-PreviewUx-Pack #2，吸收 DBX「诚实降级」口径）。
 *
 * 面板内嵌的**非模态单行提示**，不算弹窗（docs/规范/设计规范-弹窗克制红线.md 口径：面板内嵌
 * 提示不属于弹窗形态，弹窗克制零新增）。触发即显示、恢复即隐藏（不占高度）。
 * 文案三要素由各 i18n 键完整承载：哪段没还原 / 为什么 / 用户能做什么，绝不静默失败。
 *
 * 视觉照既有同类实现（LogToSqlPanel statusLabel / SqlPreviewPanel meta 警示同款）：
 * 命名色 + JBUI 内边距，明暗主题自适应；文案自带 `⚠ ` 前缀（同 meta 行警示风格），不引入新图标形态。
 * T-DegradedPreviewUx P2：**按类别分色**——RENDER_WARNING 是错误（`ErrorForeground` 红），
 * DEGRADED 是「能力受限的提示」（`WarningForeground` 橙），避免把「慢/降级」呈现成故障。
 * 两者共用同一 label，故颜色在 [show] 内按 Kind 设置，不在构造期定死。
 *
 * 消息类别与优先级（[Kind]，高优先不被低优先覆盖/清除）：
 *  DEGRADED（降级链，最高）> RENDER_WARNING（渲染警示）> DOLLAR_INJECT（`${}` 直替
 *  信息提示，T-DollarInjectMark——措辞中性的注入面提示，**可关闭**：仅该类别带 ✕ 按钮，
 *  点击回调 [onDismiss] 由调用方做会话级记忆）。
 *
 * T-PrewarmLateRecover F2：DEGRADED 可挂一个**动作**（[onRetry]，文案 `panel.banner.retry`）——
 * 预热硬失败时没有产物可等，只能由用户点一次重新臂预热。动作标签照抄同文件 ✕ 按钮的形态
 * （JBLabel + HAND_CURSOR + MouseAdapter），不新造控件类型；两者共用 EAST 位（横排容器）。
 */
class DegradationBanner : JBPanel<DegradationBanner>(BorderLayout()) {

    /** 消息类别（同类别「恢复即清除」；高优先类别不被低优先类别清除）。 */
    enum class Kind { RENDER_WARNING, DEGRADED, DOLLAR_INJECT }

    /** 类别优先级（数值大者优先——show 低优先不覆盖高优先）。 */
    private fun priority(kind: Kind): Int = when (kind) {
        Kind.DEGRADED -> 2
        Kind.RENDER_WARNING -> 1
        Kind.DOLLAR_INJECT -> 0
    }

    /** T-DollarInjectMark：DOLLAR_INJECT 的 ✕ 关闭回调（调用方负责会话级记忆与 clear）。 */
    var onDismiss: (() -> Unit)? = null

    /**
     * T-PrewarmLateRecover F2：DEGRADED 的「重试」回调（null = 不显示动作）。
     * 调用方在挂降级横幅时接上（[SqlPreviewPanel.showDegradedBanner]），主路径装配成功时摘除。
     */
    var onRetry: (() -> Unit)? = null

    private val label = JBLabel().apply {
        foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        // 详情可能含 SQL 片段（`<` 开头），禁用 HTML 解析纯文本渲染（SqlSelectionDialog 同款约定）
        putClientProperty("html.disable", true)
    }

    /** 仅 DOLLAR_INJECT 可见的信息级关闭按钮（错误/降级类保持常驻不可关）。 */
    private val dismissButton = com.intellij.ui.components.JBLabel("✕").apply {
        toolTipText = null
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                onDismiss?.invoke()
            }
        })
        isVisible = false
    }

    /** 当前挂起的消息类别（null = 无提示）。 */
    private var currentKind: Kind? = null

    /**
     * T-PrewarmLateRecover F2：「重试」动作标签（形态同 [dismissButton]——HAND_CURSOR + 点击回调）。
     * 文案在 [show] 时按当前语言解析（语言设置可在运行期切换，构造期定死会跟着一起旧）。
     */
    private val retryLabel = JBLabel().apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        foreground = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                onRetry?.invoke()
            }
        })
        isVisible = false
    }

    /** EAST 位横排容器：动作标签 + ✕ 关闭按钮共用（BorderLayout 的 EAST 只能放一个组件）。 */
    private val actions = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0)).apply {
        isOpaque = false
        add(retryLabel)
        add(dismissButton)
    }

    init {
        isOpaque = false
        add(label, BorderLayout.CENTER)
        add(actions, BorderLayout.EAST)
        isVisible = false
    }

    /** 显示提示（文案由调用方按 i18n 键组装三要素）。 */
    fun show(kind: Kind, message: String, tooltip: String? = null) {
        // 高优先类别挂起期间，低优先提示不覆盖（DEGRADED 常驻语义的泛化——
        // RENDER_WARNING 挂起时 DOLLAR_INJECT 信息提示同样让位，警示清除后下次更新再回）
        val current = currentKind
        if (current != null && priority(current) > priority(kind)) return
        currentKind = kind
        label.text = message
        // T-DegradedPreviewUx P2：降级=提示色、渲染警示=错误色（同一 label 按类别切换）
        label.foreground = if (kind == Kind.DEGRADED) {
            JBColor.namedColor("WarningForeground", JBColor(0xB26A00, 0xE0A94D))
        } else {
            JBColor.namedColor("ErrorForeground", JBColor.RED)
        }
        // 宽度不足时正文会被裁：调用方传入完整文案作 tooltip（不新造「详情」控件）
        label.toolTipText = tooltip
        dismissButton.isVisible = kind == Kind.DOLLAR_INJECT
        dismissButton.toolTipText = message
        // T-PrewarmLateRecover F2：动作只在「有回调的降级提示」上出现
        val retryVisible = kind == Kind.DEGRADED && onRetry != null
        retryLabel.isVisible = retryVisible
        if (retryVisible) retryLabel.text = MpSqlVisualizerBundle.message("panel.banner.retry")
        actions.isVisible = retryVisible || dismissButton.isVisible
        isVisible = true
    }

    /**
     * 清除指定类别：当前挂起的是该类别才隐藏——渲染警示（低优先）清除不影响挂起的降级提示。
     */
    fun clear(kind: Kind) {
        if (currentKind != kind) return
        currentKind = null
        label.text = ""
        dismissButton.isVisible = false
        retryLabel.isVisible = false
        actions.isVisible = false
        isVisible = false
    }

    /** 测试访问器：当前文案（显隐断言用 isVisible）。 */
    internal fun textForTest(): String = label.text

    /** 测试访问器：当前挂起类别。 */
    internal fun kindForTest(): Kind? = currentKind

    /** 测试访问器：✕ 关闭按钮可见性（仅 DOLLAR_INJECT）。 */
    internal fun dismissVisibleForTest(): Boolean = dismissButton.isVisible

    /** 测试触发器：模拟点击 ✕。 */
    internal fun clickDismissForTest() {
        if (dismissButton.isVisible) onDismiss?.invoke()
    }

    /** T-PrewarmLateRecover F2：测试访问器——「重试」动作可见性（仅挂了回调的 DEGRADED）。 */
    internal fun retryVisibleForTest(): Boolean = retryLabel.isVisible

    /** T-PrewarmLateRecover F2：测试访问器——动作文案（i18n 键解析结果）。 */
    internal fun retryTextForTest(): String = retryLabel.text

    /** T-PrewarmLateRecover F2：测试触发器——模拟点击动作（不可见时不触发，同 [clickDismissForTest]）。 */
    internal fun clickRetryForTest() {
        if (retryLabel.isVisible) onRetry?.invoke()
    }
}
