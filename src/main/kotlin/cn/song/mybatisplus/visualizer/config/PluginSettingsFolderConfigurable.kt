package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.GridLayout
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 落地页描述灰（T-UN-20：与日志页 LIST_META_GRAY / 历史页 HISTORY_META_GRAY 同源 namedColor，亮值 0x9E9E9E）。 */
private val SETTINGS_DESC_GRAY = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)

/**
 * 设置页主页面（左侧树导航的父节点）。
 *
 * 参考 IDEA 原生设置风格：
 *  - 顶部搜索栏
 *  - 子页面列表（图标 + 名称 + 描述）
 *  - 搜索时显示匹配的设置项（名称 + 所属页面 + 当前值预览）
 */
class PluginSettingsFolderConfigurable : Configurable {

    private val searchField = SearchTextField()
    private val listModel = DefaultListModel<SearchItem>()
    private val list = JBList(listModel)

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        rebuildList("")
        schedulePendingFocus()

        list.cellRenderer = SearchItemRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fixedCellHeight = 56  // 固定行高，匹配 IDEA 风格

        // 点击跳转（双击导航到子页面）
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                list.selectedValue?.let { navigateTo(it) }
                return true
            }
        }.installOn(list)

        // 搜索过滤
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildList(searchField.text)
            override fun removeUpdate(e: DocumentEvent) = rebuildList(searchField.text)
            override fun changedUpdate(e: DocumentEvent) = rebuildList(searchField.text)
        })

        // 搜索面板
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 16, 6, 16)
            add(searchField, BorderLayout.CENTER)
        }

        // 列表面板
        val scrollPane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
        }

        // R20（W3 可发现性修缮包）：落地页价值展示——三行能力矩阵 + 官网链接
        // （原页=搜索框+子页列表，用户不知道插件能干什么；「设置两层深」的兜底从搜索变一眼）
        val valuePanel = JPanel(GridLayout(0, 1)).apply {
            border = JBUI.Borders.empty(10, 16, 10, 16)
            add(JBLabel(MpSqlVisualizerBundle.message("settings.landing.capture")))
            add(JBLabel(MpSqlVisualizerBundle.message("settings.landing.preview")))
            add(JBLabel(MpSqlVisualizerBundle.message("settings.landing.mcp")))
            add(JBLabel(MpSqlVisualizerBundle.message("settings.landing.docs")))
        }

        return JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(valuePanel, BorderLayout.SOUTH)
        }
    }

    /**
     * 重建列表：先显示匹配的子页面，再显示匹配的设置项内容。
     */
    private fun rebuildList(query: String) {
        val q = query.trim().lowercase()
        listModel.removeAllElements()

        if (q.isEmpty()) {
            for (page in SUB_PAGES) {
                listModel.addElement(SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.ref))
            }
            return
        }

        // 1) 匹配的子页面
        val matchedPages = SUB_PAGES.filter {
            MpSqlVisualizerBundle.message(it.labelKey).lowercase().contains(q)
                || MpSqlVisualizerBundle.message(it.descKey).lowercase().contains(q)
        }
        for (page in matchedPages) {
            listModel.addElement(SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.ref))
        }

        // 2) 匹配的设置项内容
        for (field in ALL_SEARCHABLE_FIELDS) {
            val label = field.searchLabel.lowercase()
            val tooltip = field.searchTooltip.lowercase()
            if (label.contains(q) || tooltip.contains(q)) {
                listModel.addElement(SearchItem.FieldItem(
                    fieldLabel = field.searchLabel,
                    fieldTooltip = field.searchTooltip,
                    pageLabelKey = field.pageLabelKey,
                    ref = field.ref,
                ))
            }
        }
    }

    private fun navigateTo(item: SearchItem) {
        val focusHint = when (item) {
            is SearchItem.PageItem -> null
            is SearchItem.FieldItem -> item.fieldLabel
        }
        navigateToPage(item.ref, focusHint)
    }

    /**
     * 在同一对话框内导航到指定子页面，并聚焦目标控件。
     *
     * 从宿主 SettingsEditor 取得当前 Settings（同一设置对话框），用 settings.select 切换树选择；
     * 切换完成后聚焦目标控件并闪烁高亮，让用户感知目标位置。取不到 Settings 时才回退打开设置对话框。
     *
     * **对话框内定位必须按 EP id 查（[resolveTarget]），不得按类查**——平台
     * `Settings.find(Class)` = `ConfigurableVisitor.findByType`，对树里每个 `ConfigurableWrapper`
     * 调 `ConfigurableWrapper.cast`；而 `cast` 里那条「EP 声明类型与目标不相容就不创建」的短路，
     * 取的是 `ConfigurableEP.getConfigurableType()`，`ClassProducer` 未覆写 `ObjectProducer.getType()`
     * （2024.3 `ConfigurableEP.java:392` 返回 null）→ 短路是死代码 → **整棵树（含其它插件页面）
     * 全被实例化**。Kotlin 插件 `KotlinScriptingSettingsConfigurable` 构造器里做文件名索引访问
     * （`KotlinScriptingSettingsConfigurable.java:46` → `FilenameIndex.getVirtualFilesByName`），
     * 于是在 EDT 上触发 `Slow operations are prohibited on EDT`（真机 2024.3.7.1 两份栈实证）。
     * 按 id 查时 `ConfigurableWrapper.getId()` 对声明了 id 的 EP 直接返回 `myEp.id`（不实例化），
     * 只有目标页会被创建。
     */
    /**
     * 消费外部预置的待定位目标（T-SettingsFocusFromLink）：页面构建后双跳 invokeLater——
     * 确保设置对话框已完成装配、`Settings.KEY` 经 searchField DataContext 可达（P1 前提）。
     * 未置目标 → 零成本（双跳前先查，避免无谓调度）；消费即清（防重复定位）。
     */
    private fun schedulePendingFocus() {
        if (pendingFocus == null) return
        javax.swing.SwingUtilities.invokeLater {
            javax.swing.SwingUtilities.invokeLater {
                val pending = consumePendingFocus() ?: return@invokeLater
                SUB_PAGES.firstOrNull { it.ref.id == pending.pageId }
                    ?.let { navigateToPage(it.ref, pending.hint) }
            }
        }
    }

    private fun navigateToPage(ref: SubPageRef, focusHint: String? = null) {
        val settings = currentSettings() ?: run {
            // 未在已打开的设置对话框内，回退为打开设置对话框（平台只有按类定位的公开重载）
            val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ref.cls)
            return
        }

        val target = resolveTarget(settings, ref.id) ?: return
        val callback = settings.select(target)
        if (focusHint != null) {
            // 切换完成后异步聚焦目标控件，确保页面已显示、控件已创建
            callback.doWhenDone { focusTarget(target, focusHint) }
        }
    }

    /**
     * 在设置树中定位目标页（测试缝 + 反回归哨兵：签名里没有 Class，退回按类查编译不过）。
     *
     * 为什么不能按类查，见 [navigateToPage] 的 KDoc。
     */
    internal fun resolveTarget(settings: Settings, configurableId: String): Configurable? =
        settings.find(configurableId)

    /**
     * 获取当前已打开设置对话框的 [Settings] 实例。
     *
     * 主设置面板由 SettingsEditor 承载，SettingsEditor 实现 UiDataProvider 并对外提供 Settings.KEY。
     * 优先通过 DataManager 从 searchField 的真实组件 DataContext 取（会沿组件树收集 UiDataProvider 数据）；
     * 失败则沿组件树反射读取 SettingsEditor.settings 字段兜底。
     * SettingsEditor 为 @ApiStatus.Internal，用 Class.forName 反射避免编译期依赖内部 API。
     */
    private fun currentSettings(): Settings? {
        runCatching { Settings.KEY.getData(DataManager.getInstance().getDataContext(searchField)) }
            .getOrNull()
            ?.let { return it }
        // 兜底：沿组件树反射读取 SettingsEditor.settings 字段（反射类名，避免直接引用 internal 类型）
        val editorClass = runCatching { Class.forName("com.intellij.openapi.options.newEditor.SettingsEditor") }.getOrNull() ?: return null
        var comp: Component? = searchField
        while (comp != null) {
            if (editorClass.isInstance(comp)) {
                return runCatching {
                    val field = editorClass.getDeclaredField("settings")
                    field.isAccessible = true
                    field.get(comp) as? Settings
                }.getOrNull()
            }
            comp = comp.parent
        }
        return null
    }

    /**
     * 聚焦目标设置控件：页面切换后在真实面板中查找目标字段，滚动到可见区域并闪烁高亮。
     * 页面切换为异步，延迟到 EDT 调度以确保布局完成。
     */
    private fun focusTarget(target: Configurable, focusHint: String) {
        javax.swing.SwingUtilities.invokeLater {
            javax.swing.SwingUtilities.invokeLater {
                runCatching {
                    // 页面已显示，createComponent() 返回真实面板（SectionConfigurable 已缓存）
                    val root = target.createComponent() ?: return@invokeLater
                    val matched = findComponent(root, focusHint)
                    if (matched != null) {
                        // 滚动到可见区域，让用户感知目标位置
                        (matched as? javax.swing.JComponent)?.scrollRectToVisible(matched.bounds)
                        flashHighlight(matched)
                        root.revalidate(); root.repaint()
                    }
                }.onFailure { /* 忽略聚焦失败，不影响导航 */ }
            }
        }
    }

    /**
     * 递归查找匹配 hint 的控件。
     *
     * 类型判定按「输入框 tooltip → 按钮文本 → 标签文本」顺序（同一组件只可能命中一类）；
     * 行内按组件树顺序深度优先，故「同一 hint 同时命中行标签与该行控件」时以先遍历到者为准
     * （DSL v2 `row(label)` 行里标签在前）。
     *
     * T-FS-09：标签分支须匹配**普通 [javax.swing.JLabel]**——项目页走 DSL v2（`row(label)`
     * 生成的就是普通 JLabel），全局页手摆的是 [JBLabel]（JLabel 子类）。原先只认 JBLabel，
     * 项目页 6 行（方言下拉 / 推断策略下拉 / 4 个文本框）的行标签定位不到 → 双击搜索条目
     * 「跳了但不亮」；改认 JLabel 后与全局页形态一致（两页都定位到行标签）。
     *
     * @return 匹配的控件，未找到返回 null
     */
    private fun findComponent(container: Container, hint: String): Component? {
        for (comp in container.components) {
            val matched = when {
                comp is javax.swing.text.JTextComponent ->
                    comp.toolTipText?.contains(hint, ignoreCase = true) == true
                comp is javax.swing.AbstractButton ->
                    comp.text.contains(hint, ignoreCase = true)
                comp is javax.swing.JLabel ->
                    comp.text.contains(hint, ignoreCase = true)
                else -> false
            }
            if (matched) return comp
            if (comp is Container) {
                findComponent(comp, hint)?.let { return it }
            }
        }
        return null
    }

    /**
     * T-FS-09 测试缝：暴露「按搜索项 hint 在页面组件树中定位高亮目标」的查找入口。
     *
     * 闪烁（[flashHighlight]）是视觉行为、headless 不可断言；但「目标控件能否被定位」是纯组件树
     * 查找，可在 headless 下逐条断言（定位不到 = 用户看到「跳了但不亮」的隐性缺陷）。
     */
    internal fun findHighlightTargetForTest(container: Container, hint: String): Component? =
        findComponent(container, hint)

    /**
     * 短暂高亮目标控件：黄底闪烁 + 橙色边框，持续约 1.5 秒。
     */
    private fun flashHighlight(comp: Component) {
        comp.requestFocusInWindow()
        if (comp !is JComponent) return
        val originalBg = comp.background
        val originalOpaque = comp.isOpaque
        val originalBorder = comp.border
        comp.isOpaque = true
        // T-UN-13（T-UiNative）：闪黄两色 → namedColor（亮/暗值均原样平移）
        comp.border = javax.swing.BorderFactory.createLineBorder(MpvsUiColors.HIGHLIGHT_BORDER, 2)
        var count = 0
        val timer = javax.swing.Timer(250) {
            count++
            if (count % 2 == 1) {
                comp.background = MpvsUiColors.HIGHLIGHT_BACKGROUND
            } else {
                comp.background = originalBg
            }
            comp.repaint()
            if (count >= 6) {
                comp.background = originalBg
                comp.isOpaque = originalOpaque
                comp.border = originalBorder
                (it.source as? javax.swing.Timer)?.stop()
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    /**
     * 跳转目标：EP id（`plugin.xml` 声明）+ 目标类。
     *
     * 对话框内按 [id] 查（理由见 [navigateToPage]），[cls] 只服务兜底分支
     * ——未在设置对话框内、需新开对话框时，平台没有按 id 的公开重载可用。
     */
    private data class SubPageRef(val id: String, val cls: Class<out Configurable>)

    /** 搜索结果项 */
    private sealed class SearchItem {
        abstract val ref: SubPageRef

        data class PageItem(
            val labelKey: String,
            val descKey: String,
            val icon: Icon,
            override val ref: SubPageRef,
        ) : SearchItem()

        data class FieldItem(
            val fieldLabel: String,
            val fieldTooltip: String,
            val pageLabelKey: String,
            override val ref: SubPageRef,
        ) : SearchItem()
    }

    /** 子页面描述 */
    private data class SubPageDesc(
        val labelKey: String,
        val descKey: String,
        val icon: Icon,
        val ref: SubPageRef,
    )

    /** 可搜索的设置项字段 */
    private data class SearchableField(
        val searchLabel: String,
        val searchTooltip: String,
        val pageLabelKey: String,
        val ref: SubPageRef,
    )

    /**
     * 列表渲染器 — 参考 IDEA 原生设置风格。
     * 子页面：图标 + 名称（粗体）+ 描述（灰色小字）
     * 设置项：图标 + 名称 + 所属页面路径
     *
     * T-UN-20（T-UiNative）：行渲染归一 [SimpleColoredComponent] 片段通道——与日志页
     * LogEntryListCellRenderer、历史页 HistoryCellRenderer 同款双行结构（名称行在上、描述行在下），
     * GridBag 行内面板拼接退役。选中态走平台选中填充（背景取列表选中色、文本随 selectionForeground），
     * 不再自绘选中背景/描边；固定 56px 行高由 list.fixedCellHeight 承担，渲染器只管内容。
     */
    private class SearchItemRenderer : JPanel(BorderLayout()), javax.swing.ListCellRenderer<Any?> {

        /** 名称行（internal 供测试遍历片段断言文本与着色）。 */
        internal val nameLine = SimpleColoredComponent()

        /** 描述行（internal 供测试遍历片段断言文本与着色）。 */
        internal val descLine = SimpleColoredComponent()

        private val linesPanel = JPanel(GridLayout(0, 1)).apply { isOpaque = false }

        private val iconLabel = JBLabel().apply {
            border = EmptyBorder(JBUI.insetsRight(12))
            // 原 GridBag NORTHWEST 锚点语义：图标顶对齐（BorderLayout.WEST 纵向拉伸后需显式置顶）
            verticalAlignment = SwingConstants.TOP
        }

        init {
            layout = BorderLayout()
            isOpaque = true
            nameLine.isOpaque = false
            descLine.isOpaque = false
            descLine.border = EmptyBorder(JBUI.insetsTop(2)) // 原两行间距
            linesPanel.add(nameLine)
            linesPanel.add(descLine)
            add(iconLabel, BorderLayout.WEST)
            add(linesPanel, BorderLayout.CENTER)
            border = EmptyBorder(JBUI.insets(8, 16))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val item = value as? SearchItem
            if (item == null) {
                iconLabel.icon = null
                nameLine.clear()
                descLine.clear()
                return this
            }

            // 平台选中填充：背景/前景取列表（选中）色，不自绘选中视觉（与日志/历史页渲染器同款）
            val listBg = list?.background ?: background
            background = if (isSelected) (list?.selectionBackground ?: listBg) else listBg
            val fg = if (isSelected) (list?.selectionForeground ?: foreground) else (list?.foreground ?: foreground)

            nameLine.foreground = fg
            descLine.foreground = fg
            nameLine.clear()
            descLine.clear()
            when (item) {
                is SearchItem.PageItem -> {
                    iconLabel.icon = item.icon
                    nameLine.append(MpSqlVisualizerBundle.message(item.labelKey), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    descLine.append(MpSqlVisualizerBundle.message(item.descKey), descAttributes(isSelected))
                }
                is SearchItem.FieldItem -> {
                    iconLabel.icon = AllIcons.Actions.Search
                    nameLine.append(item.fieldLabel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    descLine.append(MpSqlVisualizerBundle.message(item.pageLabelKey), descAttributes(isSelected))
                }
            }
            return this
        }

        /** 描述行着色：未选中用元信息灰；选中随 selectionForeground（REGULAR）保证可读——与日志页 meta 灰同款。 */
        private fun descAttributes(isSelected: Boolean): SimpleTextAttributes =
            if (isSelected) SimpleTextAttributes.REGULAR_ATTRIBUTES else SETTINGS_DESC_GRAY
    }

    companion object {
        // 设置页 EP id —— 与 src/main/resources/META-INF/plugin.xml 的 id 属性一一对应。
        // 导航按 id 查（见 navigateToPage），id 写错会静默跳不动，由
        // PluginSettingsFolderConfigurableTest 的「id 与类指向同一页」守卫兜住。
        private const val ID_UI = "cn.song.mybatisplus.visualizer.settings.ui"
        private const val ID_PREVIEW = "cn.song.mybatisplus.visualizer.settings.preview"
        private const val ID_TABLE_MAPPING = "cn.song.mybatisplus.visualizer.settings.tableMapping"
        private const val ID_SQL_LOG = "cn.song.mybatisplus.visualizer.settings.sqlLog"

        /**
         * 外部（面板空态/引导链接，T-SettingsFocusFromLink）预置的待定位目标。
         *
         * 链接从**对话框外**发起——拿不到本 Configurable 实例（`navigateToPage` 需要 Settings/实例），
         * 故单槽预置、本页 `createComponent` 后消费一次。后到覆盖（点击是显式用户动作，无需排队）。
         */
        @Volatile
        private var pendingFocus: PendingFocus? = null

        /** 待定位目标（页 id + hint；hint=null 只切页不聚焦行）。 */
        internal data class PendingFocus(val pageId: String, val hint: String?)

        /**
         * 打开插件主设置页并定位到 SQL 日志页（hint=null 只切页）。
         * 面板引导链接统一入口——定位高亮复用既有 `navigateToPage → focusTarget`（滚动+黄闪+橙框）。
         */
        fun openSqlLogSettingsFocused(project: com.intellij.openapi.project.Project, hint: String?) {
            pendingFocus = PendingFocus(ID_SQL_LOG, hint)
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, PluginSettingsFolderConfigurable::class.java)
        }

        /** 消费待定位目标（取走即清；生产调度与测试缝单源共用，防两处漂移）。 */
        internal fun consumePendingFocus(): PendingFocus? {
            val p = pendingFocus
            pendingFocus = null
            return p
        }

        internal fun requestFocusOnOpenForTest(pageId: String, hint: String?) {
            pendingFocus = PendingFocus(pageId, hint)
        }
        private const val ID_SHORTCUTS = "cn.song.mybatisplus.visualizer.settings.shortcuts"
        private const val ID_MCP = "cn.song.mybatisplus.visualizer.settings.mcp"
        // T-BannedApiCheck（FR-0045 第 2 项）：禁用 API 规则管理页
        private const val ID_BANNED_API = "cn.song.mybatisplus.visualizer.settings.bannedApi"
        // T-TemplateHook（FR-0045 第 3 项 MVP）：外部 SQL 模板渲染器页
        private const val ID_EXTERNAL_TEMPLATE = "cn.song.mybatisplus.visualizer.settings.externalTemplate"
        private const val ID_ABOUT = "cn.song.mybatisplus.visualizer.settings.about"
        private const val ID_PROJECT = "cn.song.mybatisplus.visualizer.settings.project"

        private val PAGE_UI = SubPageDesc(
            "settings.section.ui", "settings.section.ui.desc", AllIcons.Nodes.Plugin,
            SubPageRef(ID_UI, UiActionsConfigurable::class.java),
        )
        private val PAGE_PREVIEW = SubPageDesc(
            "settings.section.preview", "settings.section.preview.desc", AllIcons.Nodes.Class,
            SubPageRef(ID_PREVIEW, SqlPreviewConfigurable::class.java),
        )
        private val PAGE_TABLE_MAPPING = SubPageDesc(
            "settings.section.tableMapping", "settings.section.tableMapping.desc", AllIcons.Nodes.DataTables,
            SubPageRef(ID_TABLE_MAPPING, TableMappingConfigurable::class.java),
        )
        private val PAGE_SQL_LOG = SubPageDesc(
            "settings.section.sqlLog", "settings.section.sqlLog.desc", AllIcons.Nodes.Console,
            SubPageRef(ID_SQL_LOG, SqlLogConfigurable::class.java),
        )
        // T-ShortcutSettings：快捷键统一管理 + MCP 工具（跳转平台 MCP Server 设置 + 门控开关）
        private val PAGE_SHORTCUTS = SubPageDesc(
            "settings.section.shortcuts", "settings.section.shortcuts.desc", AllIcons.Nodes.Plugin,
            SubPageRef(ID_SHORTCUTS, ShortcutsConfigurable::class.java),
        )
        private val PAGE_MCP = SubPageDesc(
            "settings.section.mcp", "settings.section.mcp.desc", AllIcons.Nodes.Plugin,
            SubPageRef(ID_MCP, McpConfigurable::class.java),
        )
        // T-BannedApiCheck：禁用 API 规则（列表 + 编辑器，即时生效）；图标用平台「检查」语义图标
        private val PAGE_BANNED_API = SubPageDesc(
            "settings.section.bannedApi", "settings.section.bannedApi.desc", AllIcons.General.InspectionsWarning,
            SubPageRef(ID_BANNED_API, BannedApiConfigurable::class.java),
        )

        /** T-TemplateHook（FR-0045 第 3 项 MVP）：外部 SQL 模板渲染器页。 */
        private val PAGE_EXTERNAL_TEMPLATE = SubPageDesc(
            "settings.section.externalTemplate", "settings.section.externalTemplate.desc", AllIcons.Nodes.Plugin,
            SubPageRef(ID_EXTERNAL_TEMPLATE, ExternalTemplateConfigurable::class.java),
        )
        private val PAGE_ABOUT = SubPageDesc(
            "settings.section.about", "settings.section.about.desc", AllIcons.Nodes.Favorite,
            SubPageRef(ID_ABOUT, AboutConfigurable::class.java),
        )

        /** 落地页列表（顺序即呈现顺序）。 */
        private val SUB_PAGES = listOf(
            PAGE_UI, PAGE_PREVIEW, PAGE_TABLE_MAPPING, PAGE_SQL_LOG, PAGE_SHORTCUTS, PAGE_MCP,
            PAGE_BANNED_API, PAGE_EXTERNAL_TEMPLATE, PAGE_ABOUT,
        )

        /** 所有可搜索的设置项字段 */
        private val ALL_SEARCHABLE_FIELDS = buildSearchableFields()

        private fun buildSearchableFields(): List<SearchableField> {
            val fields = mutableListOf<SearchableField>()
            val b = MpSqlVisualizerBundle

            // SQL 预览
            val p = PAGE_PREVIEW.ref
            fields.add(SearchableField(b.message("settings.dialect"), "", "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.replaceParams"), b.message("settings.replaceParams"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.formatSql"), b.message("settings.formatSql"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.injectLogicDelete"), b.message("settings.injectLogicDelete"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.logicDeleteValue"), b.message("settings.logicDeleteValue.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.injectTenantLine"), b.message("settings.injectTenantLine"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.tenantColumnName"), b.message("settings.tenantColumnName.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.tenantColumnDefault"), b.message("settings.tenantColumnDefault.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.injectTenantOnInsert"), b.message("settings.injectTenantOnInsert.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.dataPermissionHint"), b.message("settings.dataPermissionHint.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.dataPermissionMockCondition"), b.message("settings.dataPermissionMockCondition.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.reverseWrapperFormat"), b.message("settings.reverseWrapperFormat.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.reverseManualEntitySelection"), b.message("settings.reverseManualEntitySelection.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.embeddedColumnNaming"), b.message("settings.embeddedColumnNaming.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.verticalPairAnnotation"), "", "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.autoFold.enabled"), b.message("settings.autoFold.enabled.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.autoFold.threshold"), b.message("settings.autoFold.threshold.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.literalParamRecovery"), "", "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.preview.jdbc"), b.message("settings.preview.jdbc.tip"), "settings.section.preview", p))

            // 表名映射
            val t = PAGE_TABLE_MAPPING.ref
            fields.add(SearchableField(b.message("settings.tablePrefix"), b.message("settings.tablePrefix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.tableSuffix"), b.message("settings.tableSuffix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.removeTablePrefix"), b.message("settings.removeTablePrefix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.removeTableSuffix"), b.message("settings.removeTableSuffix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.ignoredFields"), b.message("settings.ignoredFields.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.tableNameInference"), b.message("settings.tableNameInference.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.columnCalibration"), b.message("settings.columnCalibration.tip"), "settings.section.tableMapping", t))

            // SQL 日志
            val l = PAGE_SQL_LOG.ref
            fields.add(SearchableField(b.message("settings.log.sqlPrefix"), b.message("settings.log.sqlPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.paramPrefix"), b.message("settings.log.paramPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.mixedPrefix"), b.message("settings.log.mixedPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoCapture"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoOpen"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.stopOnToolWindowClose"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.timestamp"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.timestampFormat"), b.message("settings.log.timestampFormat.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.formatSql"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.filterKeywords"), b.message("settings.log.filterKeywords.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.dedupeWindow"), b.message("settings.log.dedupeWindow.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.maxParseLength"), b.message("settings.log.maxParseLength.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.maxCount"), b.message("settings.log.maxCount.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.timePrefix"), b.message("settings.log.timePrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.slowQueryThreshold"), b.message("settings.log.slowQueryThreshold.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.hibernate.enabled"), b.message("settings.log.hibernate.enabled.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.hibernate.sqlPrefix"), b.message("settings.log.hibernate.sqlPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.hibernate.paramPrefix"), b.message("settings.log.hibernate.paramPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.jdbc"), b.message("settings.log.jdbc.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoSave"), b.message("settings.log.autoSave.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoSave.path"), b.message("settings.log.autoSave.path.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoSave.pattern"), b.message("settings.log.autoSave.pattern.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoSave.maxFiles"), b.message("settings.log.autoSave.maxFiles.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.timestampSource"), b.message("settings.log.timestampSource.log.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("log.list.displayMode"), b.message("log.list.displayMode.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.crudColor.style"), b.message("settings.log.crudColor.style.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.enhancedCapture"), b.message("settings.enhancedCapture.status"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.enhancedCapture.desensitize"), "", "settings.section.sqlLog", l))
            // R60 展示/导出链脱敏（T-SettingsSearchB 补登记）：T-MagpieGems W2 起该键是
            // displayMaskModeCombo 分级下拉的行标签（控件可搜索身份仍在本条目，防「跳了不亮」幽灵条目），
            // 搜「脱敏/展示/导出/打码」必须能翻到该行；完整性由 SettingsPanelSearchableGuardTest 守卫
            fields.add(SearchableField(b.message("settings.display.mask"), b.message("settings.display.mask.tip"), "settings.section.sqlLog", l))
            // T-MagpieGems W2：豁免白名单文本域（行标签同键）；「白名单/豁免」可搜索命中
            fields.add(SearchableField(b.message("settings.display.mask.allowList"), b.message("settings.display.mask.allowList.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.sourceFrameSkipPrefixes"), b.message("settings.sourceFrameSkipPrefixes.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.sourceFrameBizJarPrefixes"), b.message("settings.sourceFrameBizJarPrefixes.tip"), "settings.section.sqlLog", l))

            // 界面与操作
            val u = PAGE_UI.ref
            fields.add(SearchableField(b.message("settings.showGutterIcon"), "", "settings.section.ui", u))
            // T-SettingsConverge：六组 gutter 来源开关聚合为单行多选控件——索引条目随之收敛为一条
            // （原先 6 个分组键未索引，列在 SettingsPanelSearchableGuardTest 的存量缺口台账里）
            fields.add(SearchableField(b.message("settings.gutterGroup.aggregate"), b.message("settings.gutterGroup.aggregate.tip"), "settings.section.ui", u))
            // T-MapperNavCoexistYield：共存让位开关（界面节，默认开）
            fields.add(SearchableField(b.message("settings.mapperNavYield"), b.message("settings.mapperNavYield.tip"), "settings.section.ui", u))
            // O-72 T-XmlInlineSql：通用 XML 内嵌 SQL 检测开关（界面节）
            fields.add(SearchableField(b.message("settings.xmlInlineSql"), b.message("settings.xmlInlineSql.tip"), "settings.section.ui", u))
            fields.add(SearchableField(b.message("settings.language"), "", "settings.section.ui", u))
            fields.add(SearchableField(b.message("settings.shortcut"), b.message("settings.shortcut.tip"), "settings.section.ui", u))
            fields.add(SearchableField(b.message("settings.shortcut.sqlToJava"), "", "settings.section.ui", u))
            fields.add(SearchableField(b.message("settings.joinDiagramNative"), b.message("settings.joinDiagramNative.tip"), "settings.section.ui", u))
            // T-PreviewWhitespaceToggle O-44a：空白符可视化开关（界面节，默认关=不设置该属性）
            fields.add(SearchableField(b.message("settings.editorShowWhitespaces"), b.message("settings.editorShowWhitespaces.tip"), "settings.section.ui", u))

            // T-TemplateHook（FR-0045 第 3 项 MVP）：外部 SQL 模板渲染器页 5 项
            val et = PAGE_EXTERNAL_TEMPLATE.ref
            fields.add(SearchableField(b.message("settings.externalTemplate.enabled"), b.message("settings.externalTemplate.enabled.tip"), "settings.section.externalTemplate", et))
            fields.add(SearchableField(b.message("settings.externalTemplate.statementTags"), b.message("settings.externalTemplate.statementTags.tip"), "settings.section.externalTemplate", et))
            fields.add(SearchableField(b.message("settings.externalTemplate.command"), b.message("settings.externalTemplate.command.tip"), "settings.section.externalTemplate", et))
            fields.add(SearchableField(b.message("settings.externalTemplate.timeout"), b.message("settings.externalTemplate.timeout.tip"), "settings.section.externalTemplate", et))
            fields.add(SearchableField(b.message("settings.externalTemplate.workingDirectory"), b.message("settings.externalTemplate.workingDirectory.tip"), "settings.section.externalTemplate", et))

            // 关于
            val a = PAGE_ABOUT.ref
            fields.add(SearchableField(b.message("settings.cache.label"), b.message("settings.cache.tip"), "settings.section.about", a))
            // NEW-18：一键环境体检入口（搜「体检/环境/诊断」可定位；SettingsPanelSearchableGuardTest 守卫）
            fields.add(SearchableField(b.message("settings.envCheck.label"), b.message("settings.envCheck.tip"), "settings.section.about", a))
            fields.add(SearchableField(b.message("settings.update.versionInfo"), "", "settings.section.about", a))
            fields.add(SearchableField(b.message("settings.buildNumber"), b.message("settings.buildNumber.tip"), "settings.section.about", a))

            // T-ProjectSettingsScope P3：项目级设置页 11 项（总开关 + 10 项可覆盖字段；O-58 起新增
            // 列名数据源校正，索引条目与页面控件同批扩一项）。
            // 前置对照实验（ProjectPluginSettingsConfigurableTest 内探针）：项目上下文下该页在设置树里
            // （ConfigurableVisitor.findByType 走 Configurable.PROJECT_CONFIGURABLE，project=null 时为 null），
            // 故本页条目可跳转，不是 T-SettingsTruth P3 清理过的「幽灵搜索项」形态。
            // 文案键复用全局页同项（T-SettingsTruth 6.5 已把项目页标签收编为全局键），
            // 仅 pageLabelKey 指向项目页 displayName——用户在同一批关键词下同时看到两个作用域。
            val pj = SubPageRef(ID_PROJECT, ProjectPluginSettingsConfigurable::class.java)
            fields.add(SearchableField(b.message("settings.project.useProjectSettings"), b.message("settings.project.useProjectSettings.tip"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.dialect"), "", "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.replaceParams"), b.message("settings.replaceParams"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.formatSql"), b.message("settings.formatSql"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.injectLogicDelete"), b.message("settings.injectLogicDelete"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.logicDeleteValue"), b.message("settings.logicDeleteValue.tip"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.tablePrefix"), b.message("settings.tablePrefix.tip"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.tableSuffix"), b.message("settings.tableSuffix.tip"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.tableNameInference"), b.message("settings.tableNameInference.tip"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.ignoredFields"), b.message("settings.ignoredFields.tip"), "configurable.settings.project.displayName", pj))
            fields.add(SearchableField(b.message("settings.columnCalibration"), b.message("settings.columnCalibration.tip"), "configurable.settings.project.displayName", pj))

            return fields
        }

        /**
         * 测试缝：登记的全部跳转目标（EP id → 目标类）。
         *
         * 供「id 与类指向同一设置页」守卫逐个断言——导航按 id 查，id 写错会静默跳到别处或跳不动，
         * 纯 UI 断言看不出来（本仓「幽灵搜索项」的同类风险）。
         */
        internal fun registeredTargetsForTest(): List<Pair<String, Class<out Configurable>>> =
            (SUB_PAGES.map { it.ref.id to it.ref.cls } + ALL_SEARCHABLE_FIELDS.map { it.ref.id to it.ref.cls })
                .distinct()

        /**
         * 测试缝：登记的全部可搜索 label（T-SettingsSearchB 守卫用）。
         *
         * 「面板上有控件」与「搜索索引里有条目」两件事只靠本清单的维护纪律绑定——
         * SettingsPanelSearchableGuardTest 拿真实面板的每个输入型文本逐条到本结果里配对，
         * 漏登记即红。只比对 label 文本（搜索命中判定同源：label 或 tooltip 命中即可，
         * 本清单 tooltip 全部与控件 tooltip 同键，配对在 label 层已足够判漏）。
         */
        internal fun registeredSearchLabelsForTest(): List<String> =
            ALL_SEARCHABLE_FIELDS.map { it.searchLabel }

        /**
         * T-SettingsSearchA：页内搜索与 IDE 设置搜索**共用的索引条目**（单一数据源）。
         *
         * 两套搜索读同一份清单，避免「面板加了控件、某一边索引忘了登记」的漂移在 IDE 搜索侧
         * 重演（B 那类缺陷的根因）。返回 (label, tooltip, 页显示名, EP id) 四元组：
         *  - label/tooltip 喂 [com.intellij.ide.ui.search.SearchableOptionProcessor.addOptions]
         *    的 text/hit；
         *  - 页显示名喂 configurableDisplayName（搜索结果分组显示）；
         *  - EP id 喂 configurableId（平台按 id 把命中映射回设置树节点，见 `filterById`）。
         *
         * 只做内存映射，无 IO/PSI——Contributor 在设置对话框初始化路径上执行（计划 §八）。
         */
        internal fun ideSearchEntries(): List<IdeSearchEntry> = ALL_SEARCHABLE_FIELDS.map {
            IdeSearchEntry(
                label = it.searchLabel,
                tooltip = it.searchTooltip,
                pageDisplayName = MpSqlVisualizerBundle.message(it.pageLabelKey),
                configurableId = it.ref.id,
            )
        }

        /** IDE 设置搜索条目（[ideSearchEntries] 的元素；四元组语义见该函数 KDoc）。 */
        internal data class IdeSearchEntry(
            val label: String,
            val tooltip: String,
            val pageDisplayName: String,
            val configurableId: String,
        )
    }
}
