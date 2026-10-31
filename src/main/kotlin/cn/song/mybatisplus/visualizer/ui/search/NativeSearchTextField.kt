package cn.song.mybatisplus.visualizer.ui.search

import com.intellij.find.FindModel
import com.intellij.find.SearchReplaceComponent
import com.intellij.find.SearchSession
import com.intellij.find.editorHeaderActions.Utils
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.find.editorHeaderActions.ToggleRegex
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JList

/**
 * 原生 IDEA 搜索框形态的文本搜索组件（T-SearchToggleNativeLook O-137）。
 *
 * 走平台 `SearchReplaceComponent.Builder` 全套（原生框体/边距/焦点，EditorSearchSession 同款装配）；
 * `withShowOnlySearchPanel()` 只保留搜索行。开关钮挂位（243 源码钉死的事实，两轮真机返工换来）：
 * - **必须 `addExtraSearchActions`**——SearchReplaceComponent 构造期只从 searchToolbar2 组
 *   （即 addExtraSearchActions 的组）过滤 `instanceof Embeddable` 抽给
 *   `SearchTextArea.setExtraActions` 渲染进框内按钮行；
 * - `addPrimarySearchActions` 的动作进右侧工具栏，showOnlySearchPanel 形态下该面板**根本没挂进布局**（不可见）；
 * - `addSearchFieldActions` 的组只用于快捷键绑定，不渲染。
 *
 * 命中数读数放框外右侧 [statusLabel]（灰色小字）：平台原生 StatusTextAction 走 ActionToolbar
 * 自定义组件路径才显示，而 setExtraActions 的嵌入钮是 ActionButton（只画图标），
 * 文本标签在框内是死路——框内嵌入面留给开关钮，读数贴框放右。
 *
 * 搜索历史（T-LogToSqlHint 同批用户反馈「搜索历史好像是假的」）：平台历史钮数据源=IDE 全局
 * Find 历史（`FindInProjectSettings.getRecentFindStrings()`，与本项目无关的串线词+空串杂质）。
 * 处置：①`withAddSearchResultsToGlobalSearch(false)` 止住本组件搜索词写进全局 Find 历史；
 * ②[project] 传真（null 时平台 `removeNotify→addTextToRecent` 对 `FindInProjectSettings.getInstance(null)`
 * 必 NPE）；③**接管历史钮**——组件树里找到平台 ShowHistoryAction 的钮并在原位换成本组件的
 * 「最近搜索」（[recentSearches] 会话级，[recordSearchQuery] 由宿主面板在过滤真正应用时喂词，
 * 空词不记），找不到平台钮则不装（降级=无历史钮，不影响其余能力）。
 *
 * 会话仍为极小 [MinSearchSession]（自有 [FindModel]，不与 IDE 全局 Find 串线）；
 * SearchReplaceComponent 自身实现 DataProvider 应答 SearchSession.KEY，故原生钮可用，
 * 本类兜底自答同键（两处同源，无歧义）。
 *
 * 语义约定（T-HistorySearchToggles 拍板保持不变）：
 * - regex/matchCase 两旗由调用方从 [findModel] 读取喂给各自过滤链；
 * - `regex:` 前缀或语义、开关态会话级不持久化，均与本组件无关；
 * - 日志页不需要正则钮：构造传 `showRegex = false` 即不挂 ToggleRegex（D2 拍板保持）。
 */
class NativeSearchTextField(
    placeholderText: String?,
    showRegex: Boolean,
    multiline: Boolean = false,
    private val project: Project? = null,
) : DataProvider {

    val findModel: FindModel = FindModel().apply {
        isCaseSensitive = false
        isRegularExpressions = false
    }

    private val searchSession: SearchSession = MinSearchSession(findModel)

    /** 原生全套组件：框体/边框/焦点/开关钮都由平台装配。 */
    val component: SearchReplaceComponent = SearchReplaceComponent.buildFor(
        project,
        javax.swing.JPanel(),
        searchSession,
    )
        .addExtraSearchActions(ToggleMatchCase())
        .apply { if (showRegex) addExtraSearchActions(ToggleRegex()) }
        .withAddSearchResultsToGlobalSearch(false)
        .withShowOnlySearchPanel()
        .withMultilineEnabled(multiline)
        .build()

    private val textComponent: JBTextArea =
        requireNotNull(component.getSearchTextComponent() as? JBTextArea) {
            "SearchReplaceComponent search text component is not a JBTextArea (platform change?)"
        }

    /** 框外右侧命中数读数（灰字，空文本隐藏）。 */
    private val statusLabel = JBLabel().apply {
        foreground = UIUtil.getLabelInfoForeground()
        border = JBUI.Borders.emptyLeft(8)
        isVisible = false
    }

    /** 调用方入布局的行组件：搜索框（含框内开关钮）+ 右侧读数。 */
    val row: JComponent = JBUI.Panels.simplePanel()
        .addToCenter(component)
        .addToRight(statusLabel)

    /** 会话级最近搜索词（最新在后；去重移位；空词不记）。 */
    private val recentSearches = ArrayDeque<String>()

    init {
        if (placeholderText != null) {
            textComponent.emptyText.text = placeholderText
        }
        installOwnHistoryButton()
    }

    fun getTextField(): JBTextArea = textComponent

    // ---------- SearchTextField 兼容面（迁移既有调用点零改动） ----------

    /** 历史页调用点用 `searchField.textEditor.emptyText/minimumSize`。 */
    val textEditor: JBTextArea get() = textComponent

    /** 兼容 `SearchTextField.text` 读写。 */
    var text: String
        get() = component.getSearchTextComponent().text
        set(value) {
            component.getSearchTextComponent().text = value
        }

    /** 兼容 `SearchTextField.addDocumentListener`（document 委托）。 */
    fun addDocumentListener(listener: javax.swing.event.DocumentListener) {
        component.getSearchTextComponent().document.addDocumentListener(listener)
    }

    fun isMatchCase(): Boolean = findModel.isCaseSensitive

    fun isRegex(): Boolean = findModel.isRegularExpressions

    /**
     * 命中数读数（对齐 IDEA 搜索条「N results」语义）：过滤完成后由调用方写入；
     * 空串隐藏标签。同步存入平台状态区（配色语义留给后续未命中变体）。
     */
    fun setStatusText(text: String) {
        component.setStatusText(text)
        statusLabel.text = text
        statusLabel.isVisible = text.isNotEmpty()
    }

    /** 搜索校验反馈：复用平台搜索错误配色，并同步实际挂载的右侧标签。null 清除错误。 */
    fun setSearchError(message: String?) {
        if (message == null) component.setRegularBackground() else component.setNotFoundBackground()
        statusLabel.foreground = if (message == null) UIUtil.getLabelInfoForeground() else component.statusColor
        textComponent.toolTipText = message
        if (message != null) setStatusText(message)
    }

    /**
     * 宿主面板在过滤真正应用（防抖后）时喂词：非空去重移到队尾，容量 [MAX_HISTORY] 淘汰最旧。
     * 与平台历史（全局 Find 历史）完全隔离。
     */
    fun recordSearchQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        recentSearches.remove(q)
        recentSearches.addLast(q)
        while (recentSearches.size > MAX_HISTORY) recentSearches.removeFirst()
    }

    internal fun recentSearchesForTest(): List<String> = recentSearches.toList()

    /** 原生开关钮经 DataManager 沿组件树取 `SearchSession.KEY`（SearchReplaceComponent 已应答；兜底自答）。 */
    override fun getData(dataId: String): Any? =
        if (SearchSession.KEY.`is`(dataId)) searchSession else null

    // ---------- 搜索历史接管（T-LogToSqlHint 批） ----------

    /**
     * 把平台历史钮（数据源=全局 Find 历史）原位换成本组件「最近搜索」钮。
     * 平台钮是 SearchTextArea 内部装配的 ActionButton（action 为内部类 ShowHistoryAction），
     * 类名匹配定位，找不到时静默跳过（平台结构变化只损失历史钮，不伤其余）。
     */
    private fun installOwnHistoryButton() {
        val platformButton = findPlatformHistoryButton(component) ?: return
        val parent = platformButton.parent as? JComponent ?: return
        val index = parent.getComponentZOrder(platformButton)
        if (index < 0) return
        parent.remove(platformButton)

        val ownAction = object : AnAction(
            "最近搜索", "本组件最近使用过的搜索词", AllIcons.Actions.SearchWithHistory,
        ), DumbAware {
            // 不覆写 update 设 isEnabled=false——ActionButton 的 update 时机不可控，会呈现常灰；
            // 空历史时 actionPerformed → showHistoryPopup 直接 return（静默），钮保持可点观感。
            override fun actionPerformed(e: AnActionEvent) = showHistoryPopup()
        }
        // 尺寸对齐平台内嵌钮基线（自定尺寸会撑高搜索行——用户实测「搜索框变得太高」）
        val button = ActionButton(
            ownAction, null, "NativeSearchTextFieldHistory",
            com.intellij.openapi.actionSystem.ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
        )
        button.isOpaque = false
        button.toolTipText = MpSqlVisualizerBundle.message("search.history.tooltip")
        // 点击由 AnAction 系统驱动（ActionButton 非 AbstractButton，无 addActionListener）
        parent.add(button, index)
        parent.revalidate()
        parent.repaint()
    }

    private fun findPlatformHistoryButton(root: Component): Component? {
        val action = runCatching {
            (root as? ActionButton)?.action
        }.getOrNull()
        if (action != null && action.javaClass.simpleName == "ShowHistoryAction") return root
        val children = (root as? java.awt.Container)?.components ?: return null
        for (child in children) {
            findPlatformHistoryButton(child)?.let { return it }
        }
        return null
    }

    private fun showHistoryPopup() {
        if (recentSearches.isEmpty()) return
        val list = JBList(recentSearches.toList().asReversed())
        list.cellRenderer = SimpleHistoryRenderer()
        Utils.showCompletionPopup(component, list, null, textComponent, null)
    }

    /** 历史条目渲染：与普通文本项区分（原生 popup 同款简洁形态）。 */
    private class SimpleHistoryRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean,
        ): Component = super.getListCellRendererComponent(list, value, index, selected, focused).apply {
            (this as? JComponent)?.border = JBUI.Borders.empty(2, 8)
        }
    }

    companion object {
        /** 会话级历史容量。 */
        const val MAX_HISTORY = 20

    }
}
