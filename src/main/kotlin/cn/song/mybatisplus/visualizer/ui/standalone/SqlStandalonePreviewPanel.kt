package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.applyPrimaryButtonStyle
import cn.song.mybatisplus.visualizer.ui.copySql
import cn.song.mybatisplus.visualizer.ui.executeSql
import cn.song.mybatisplus.visualizer.ui.showResult
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * 独立 SQL 预览工作台（T-SqlPreviewStandalone D-2）：不依赖语句入口，用户粘贴一段
 * SQL 或 mapper XML 片段，走 [StandaloneSqlSourceResolver] 识别后喂内嵌 [SqlPreviewPanel]
 * 展示——格式化、参数填值、复制可执行 SQL、动态条件勾选（XML 路径）与执行全部复用
 * 既有面板能力，本类只做装配。
 *
 * 布局（Splitter 上下分栏，对齐 SqlToJavaPanel 先例）：
 *  - 上：SQL 输入区（[SqlInputEditor]：SQL 高亮 + 打字自动弹补全）；工具栏：解析预览 / 格式化 / 清空 / 在弹框中打开；
 *  - 下：预览区（内嵌 [SqlPreviewPanel]，懒创建——首次解析成功才建实例，避免常驻双 Editor 内存代价）；
 *  - 底部：状态行（识别结果如实呈现：XML 语句块 / 纯 SQL / 未识别 / 过长）。
 *
 * 线程纪律：解析是纯文本函数（project=null 链零 PSI 读），派后台线程；结果经代次守卫
 * 回 EDT 喂面板（对齐 SqlToJavaPanel 的 ConversionRequest 代次模式）。
 */
class SqlStandalonePreviewPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val inputEditor = SqlInputEditor(project, "")

    private val statusLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 输入区空文档灰字提示（用户交互引导；有内容自动隐藏）。 */
    private val inputHintLabel = JBLabel(MpSqlVisualizerBundle.message("standalone.hint.input")).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 预览区空态灰字提示（首次解析成功前显示）。 */
    private val previewHintLabel = JBLabel(MpSqlVisualizerBundle.message("standalone.hint.preview")).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 预览区宿主：懒创建前显示灰字提示，解析成功后替换为 [SqlPreviewPanel]。 */
    private val previewHolder = JPanel(BorderLayout()).apply {
        add(previewHintLabel, BorderLayout.NORTH)
    }

    /**
     * T-IncludePlaceholder A 券：用户补齐的 `<sql id>` 片段（id → 片段内容）。
     *
     * 与输入区分开存：用户输入永远保持原样（贴什么显示什么），补齐内容只在解析前拼到前面——
     * 这样「补齐」不污染用户手里那段文本，也不会因为再次编辑输入而丢失。
     */
    private val fragmentOverrides = linkedMapOf<String, String>()

    /**
     * 补齐内容来源（T-PreviewActionRow D-6）：默认弹 `MissingFragmentDialog`，测试可注入。
     *
     * 与 [MissingFragmentFillFlow.promptViaDialog] 同一实现——注入点只在"怎么问用户"，
     * 「记进 overrides + 重解析」仍走真实链路。
     */
    internal var missingFragmentPrompt: (String) -> String? = { refid ->
        MissingFragmentFillFlow.promptViaDialog(project, refid)
    }

    /** 内嵌预览面板（懒创建；Disposer 挂本面板，防双 Editor 常驻）。@Volatile：ExecuteAction 的 update 在 BGT 读（面板内执行入口同口径先例）。 */
    @Volatile
    private var previewPanel: SqlPreviewPanel? = null

    /** Disposer 防重注册守卫（对齐 SqlLogPanel.historyPanelRegistered 先例）。 */
    private var previewPanelRegistered = false

    /** 代次守卫：晚到的后台解析结果触碰已过期/已清空的 UI（对齐 SqlToJavaPanel invalidateRequests）。 */
    private var resolveGeneration = 0L

    /**
     * 当前输入来自哪个文件（T-LoadFilePlacement D-2）：非 null 时状态行带「已从 X 加载」，
     * 用户再编辑输入 / 清空 / 换文件即失效。
     *
     * 为什么记状态而不是只弹一次提示：解析是异步的，加载后状态行会立刻被识别结果覆盖——
     * 不挂住来源的话，用户看不到「我现在看的是文件里的内容」。
     */
    private var loadedFileName: String? = null

    /**
     * 测试缝：替代真实文件选择框的「选文件」通道（headless 下不能弹 `FileChooser`）。
     *
     * 生产路径为 null → 走 [MapperFileLoader.chooseFile]（EDT 选文件）；用例注入一个真实
     * `VirtualFile` 后驱动 [LoadFileAction] 的完整动作路径——选文件与读文件由此分离：
     * 本缝只决定「选了哪个文件」，读文本仍走 [loadTextInvoker]。
     */
    internal var fileChooserForTest: (() -> VirtualFile?)? = null

    /**
     * 测试缝：把「读文件文本」这一原语换成可观察的替身（校验读发生在后台线程 / 不该被读时抛错）。
     *
     * 生产默认 [MapperFileLoader.loadText]——读权限 IO，由 [LoadFileAction] 派到后台线程执行。
     */
    internal var loadTextInvoker: (VirtualFile) -> String? = { MapperFileLoader.loadText(it) }

    /** 载入文件的代次守卫：晚到的后台读取结果不得触碰已清空/已释放/已被新一次加载取代的 UI。 */
    private var loadFileGeneration = 0L

    /**
     * 测试缝：替代真实剪贴板的「粘贴计划」文本来源（headless 不能依赖系统剪贴板；
     * 对齐 [missingFragmentPrompt]/[fileChooserForTest] 注入点惯例）。
     * 生产默认 null → 读系统剪贴板（读取失败按空处理，不炸）。
     */
    internal var planClipboardForTest: (() -> String?)? = null

    /** 测试缝：替代真实弹窗的「粘贴计划」视图打开通道（headless 只认登记事实，对齐 DialogTest 口径）。 */
    internal var pasteDialogInvoker: ((cn.song.mybatisplus.visualizer.ui.ExplainPlanResultView) -> Unit)? = null

    private var disposed = false

    /** 解析预览动作实例（字段化：构造期就要注册组件级快捷键，不能每次建工具栏都新建一个）。 */
    private val resolveAction = ResolveAction()

    /** 执行 SQL 动作实例（字段化同 [resolveAction] 先例；enable 随内嵌面板存亡翻转）。 */
    private val executeAction = ExecuteAction()

    /** 复制 SQL（T-PreviewActionRow D-1）：页签里唯一的「取走产物」入口。 */
    private val copySqlAction = CopySqlAction()

    private val clearAction = ClearAction()

    private val openInDialogAction = OpenInDialogAction()

    /** 粘贴执行计划（PEV-01）：从剪贴板读计划文本/JSON，直出结构化计划视图弹窗。 */
    private val pastePlanAction = PastePlanAction()

    /**
     * 顶部工具栏（图标）：解析 / 格式化 / 从文件加载 / 粘贴计划 / 清空 / 在弹框中打开。
     *
     * T-PreviewActionRow 二轮（用户 2026-11-20 反馈「清空与在弹框中打开这两个按钮需要保留」）：
     * 这两项回到顶部——它们是用户按位置记忆的「随时可用」操作；底部只留两个真正新增的产物出口
     * （执行 SQL / 复制 SQL），同一动作不再两处重复。
     */
    private val inputActions: List<AnAction> = listOf(
        resolveAction,
        FormatAction(),
        // 读文件与粘贴计划都是**输入源**能力，只挂在有输入区的工作台上；通用预览弹窗
        //（22 个文件 / 53 处调用点共用、只展示已渲染 SQL）不再拥有它们。
        LoadFileAction(),
        pastePlanAction,
        clearAction,
        openInDialogAction,
    )

    /** 底部动作行（顺序即按钮顺序；enable 由 [refreshBottomActions] 统一刷新）。 */
    private val bottomActions: List<AnAction> = listOf(
        executeAction,
        copySqlAction,
    )

    /** 底部按钮（与 [bottomActions] 下标一一对应）。 */
    private val bottomButtons = mutableListOf<javax.swing.JButton>()

    init {
        Disposer.register(this, inputEditor)

        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(buildInputToolbar(), BorderLayout.NORTH)
        inputPanel.add(inputHintLabel, BorderLayout.SOUTH)
        inputPanel.add(inputEditor.component, BorderLayout.CENTER)

        val splitter = Splitter(true, 0.4f).apply {
            firstComponent = inputPanel
            secondComponent = previewHolder
            isShowDividerControls = false
        }
        add(splitter, BorderLayout.CENTER)
        // T-PreviewActionRow：底部两行——产物动作行 + 识别状态行
        add(
            JPanel(BorderLayout()).apply {
                add(buildBottomActionRow(), BorderLayout.NORTH)
                add(statusLabel, BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH,
        )

        // T-StandaloneHintAndWarning：Alt+Shift+Q 在工作台里接「解析预览」。
        // 为什么必须接管：该键全局绑 ConvertToSqlAction，而它只在 Java/XML/kt 文件上 enabled
        //（`actions/ConvertToSqlAction.kt` 的 update）——工作台输入是 `input.sql`，动作恒 disabled，
        // 按键被丢弃、界面零反馈，而预览区空态提示恰恰承诺了「按 Alt+Shift+Q」。组件级 custom
        // shortcut 命中即消费按键（同 SqlPreviewPanel 格式化键 T-M26.6 的既有手法）。
        resolveAction.registerCustomShortcutSet(
            CustomShortcutSet(*reparseShortcuts()),
            this,
        )

        // 空态提示显隐跟随文档内容
        inputEditor.editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(e: DocumentEvent) {
                inputHintLabel.isVisible = inputEditor.text.isBlank()
                // 输入一变，「来自某个文件」的溯源就不再成立（载入后本字段会立刻重新赋值）
                loadedFileName = null
            }
        }, this)
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private fun buildInputToolbar(): JPanel {
        val group = DefaultActionGroup(inputActions)
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, group, true)
            .apply { targetComponent = this@SqlStandalonePreviewPanel }
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.WEST) }
    }

    /**
     * 底部产物动作行（T-PreviewActionRow D-1）：文字按钮，右对齐。
     *
     * 为什么用手工 `JButton` 而不是 `ActionToolbar`：工具窗工具栏在图标模式下只画图标，
     * 要文字得逐按钮改 presentation（平台未提供稳定的"强制文字"入口）；而且这一排要和
     * 预览弹窗底部那排手感一致（弹窗侧也是 `JButton`）。
     *
     * 文案与 enable 都来自 [bottomActions] 的动作本体：先 `update(e)` 再读 presentation，
     * enable 判定只有一处（`ExecuteAction`/`CopySqlAction` 各看 `previewPanel != null`）。
     */
    private fun buildBottomActionRow(): JPanel {
        val labels = listOf(
            "standalone.action.execute",
            "standalone.action.copySql",
            "standalone.action.openDialog",
            "standalone.action.clear",
        )
        val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4)).apply {
            border = JBUI.Borders.empty(2, 6, 2, 6)
        }
        bottomActions.forEachIndexed { index, action ->
            val button = javax.swing.JButton(MpSqlVisualizerBundle.message(labels[index])).apply {
                addActionListener { action.actionPerformed(anActionEventOf(action)) }
            }
            // 主行动＝复制 SQL（与弹窗「复制 SQL 并关闭」同一取走口径；执行 SQL 次之）
            if (index == PRIMARY_BOTTOM_INDEX) applyPrimaryButtonStyle(button)
            bottomButtons += button
            row.add(button)
        }
        refreshBottomActions()
        return row
    }

    /** 按动作自身的 `update` 结果刷新底部按钮的 enable（面板未建立时执行/复制禁用）。 */
    private fun refreshBottomActions() {
        bottomButtons.forEachIndexed { index, button ->
            val action = bottomActions[index]
            val event = anActionEventOf(action)
            action.update(event)
            button.isEnabled = event.presentation.isEnabled
        }
    }

    private fun anActionEventOf(action: AnAction): AnActionEvent =
        AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)

    /** 解析预览：后台跑双源解析器，代次守卫回 EDT 喂内嵌面板。 */
    private inner class ResolveAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Convert
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.resolve")
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            resolveFromInput()
        }
    }

    /**
     * 执行 SQL（D233/T-StandaloneExecPhase2）：把内嵌预览面板的产物送既有执行链——
     * Schema 校验 / DML 确认 / `${}` raw 确认门 / 未知参数确认门 / 方言闸 / 控制台内嵌
     * 全部复用 [executeSql]（`SqlPreviewPanelExecution.kt` 的 internal 扩展），本动作只做
     * 入口与 enable 判定，不另设闸门（弹窗克制红线：模态确认只留给执行链既有形态）。
     *
     * enable 判定＝内嵌面板已创建（懒创建只在解析成功路径发生——未识别文本+开弹窗不建面板）
     * 且未被清空；面板无 SQL 文本时 [executeSql] 静默 return（M36 既有口径），点不出事故。
     */
    /**
     * **`internal` 而非 `private`**：回归用例必须走真实动作路径（对齐 [OpenInDialogAction]
     * 的既有口径——只测面板方法会漏掉接线缺陷）。
     */
    internal inner class ExecuteAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Execute
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.execute")
            e.presentation.description = MpSqlVisualizerBundle.message("standalone.action.execute.description")
            e.presentation.isEnabled = previewPanel != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val panel = previewPanel
            if (panel == null) {
                // 竞态兜底（update 拦可见性后仍到不了这里）：给引导不裸跑
                setStatus(MpSqlVisualizerBundle.message("standalone.status.execute.noPreview"))
                return
            }
            panel.executeSql()
        }
    }

    /**
     * 复制 SQL（T-PreviewActionRow D-1）：页签里最重要的"取走"动作——此前**完全没有入口**，
     * 用户要么进弹窗点一次，要么在编辑器里全选 Ctrl+C（复制到的是带注释原文）。
     *
     * 走 [SqlPreviewPanel.copySql]（与弹窗「复制 SQL」同一条链，含去注释），但**必须**
     * `offerClosePanelAction = false`：那条通知默认会挂「关闭 SQL 面板」动作，而
     * `onClosePanelRequested` 只有弹窗注入——页签里会挂一条点了没反应的死链接。
     *
     * **`internal`**：回归用例走真实动作路径（同 [ExecuteAction] 口径）。
     */
    internal inner class CopySqlAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.copySql")
            e.presentation.description =
                MpSqlVisualizerBundle.message("standalone.action.copySql.description")
            e.presentation.isEnabled = previewPanel != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val panel = previewPanel
            if (panel == null) {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.copy.noPreview"))
                return
            }
            panel.copySql(offerClosePanelAction = false)
        }
    }

    /** 格式化输入区 SQL（SqlFormatter 幂等；程序性写入不触发补全，对齐 SqlToJavaPanel.FormatAction）。 */
    private inner class FormatAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Format
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.format")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = inputEditor.text
            if (text.isBlank()) return
            inputEditor.setSql(SqlFormatter.format(text))
        }
    }

    /** 清空：作废在途解析、清输入、预览区回到空态提示（释放内嵌面板，下轮解析重建）。**`internal`**：回归用例走真实动作路径（同 [OpenInDialogAction] 口径）。 */
    internal inner class ClearAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Clear
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            clear()
        }
    }

    /**
     * 从文件加载（T-LoadFilePlacement D-2）：选一个 mapper XML → 替换输入区 → 直接解析预览。
     *
     * 为什么在工作台而不在弹窗：弹窗是「看结果」的通用展示层（18 个 gutter 图标、日志、历史
     * 共 21 处入口），在里面换输入会静默改掉「这条 SQL 从哪来」的语义；工作台才有「取文本」这一层。
     *
     * 线程（T-Perf33MergeAlign）：选文件在 EDT、读文本在后台、回填回 EDT；读前先按 `file.length`
     * 粗筛超大文件（UTF-8 4 字节/字符），避免整份读进内存。
     *
     * **`internal`**：回归用例必须走真实动作路径（同 [OpenInDialogAction] 口径）。
     */
    internal inner class LoadFileAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.MapperXml
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.loadFile")
            e.presentation.description = MpSqlVisualizerBundle.message("standalone.action.loadFile.description")
        }

        override fun actionPerformed(e: AnActionEvent) {
            // 一、选文件（EDT）：FileChooser 是 UI 操作，测试缝可替换
            val file = fileChooserForTest?.invoke()
                // parent 传本面板：选择器归属工具窗，不落到主窗口后面（T-PreviewInfoLayout 问题一）
                ?: MapperFileLoader.chooseFile(
                    project,
                    "standalone.action.loadFile.title",
                    parentComponent = this@SqlStandalonePreviewPanel,
                )
                ?: return
            // 二、读前粗筛（EDT，零成本）：UTF-8 单字符最多 4 字节，字节数 > 4×字符上限
            //      ⇒ 字符数必超上限，直接拒绝——避免把极端大文件整份读进内存再被精确判据丢弃。
            //      （精确判据仍是 applyLoadedFile 里的字符数检查，这里只是保守前置拦截。）
            if (file.length > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH * 4L) {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.loadedFile.tooLong"))
                return
            }
            // 三、读文本（后台）：读权限 IO 不能压 EDT；代次守卫丢弃在途/过期的结果
            val generation = ++loadFileGeneration
            val app = ApplicationManager.getApplication()
            app.executeOnPooledThread {
                val text = loadTextInvoker(file)
                app.invokeLater(
                    {
                        if (disposed || generation != loadFileGeneration || project.isDisposed) return@invokeLater
                        text?.takeIf { it.isNotBlank() }?.let {
                            applyLoadedFile(MapperFileLoader.LoadedFile(file.name, it))
                        }
                    },
                    ModalityState.nonModal(),
                )
            }
        }
    }

    /**
     * 粘贴执行计划（PEV-01）：剪贴板里的 EXPLAIN 文本/JSON → 结构化计划视图弹窗。
     *
     * 路由：JSON 形态（可解析）→ 走 [ExplainPlanResultView] 既有 JSON 管线（徽章/收拢/统计
     * 随 ANALYZE JSON 自动生效）；其余 → [ExplainTextPlanTree.parse]（失败按结构化 reason
     * 出状态行提示，不猜格式——诚实降级红线）。text 档建议走 [ExplainIndexAdvisor.adviseText]
     * 单一来源；徽章/统计对 text 档不做（其行数字段不在 JSON 徽章判定位，v1 如实不做）。
     */
    internal inner class PastePlanAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.MapperXml
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.pastePlan")
            e.presentation.description = MpSqlVisualizerBundle.message("standalone.plan.paste.description")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = (planClipboardForTest?.invoke() ?: readClipboard()).orEmpty().trim()
            if (text.isEmpty()) {
                setStatus(MpSqlVisualizerBundle.message("standalone.plan.paste.empty"))
                return
            }
            if (text.startsWith("[") || text.startsWith("{")) {
                if (runCatching { com.google.gson.JsonParser.parseString(text) }
                        .getOrNull()?.let { it.isJsonObject || it.isJsonArray } == true) {
                    // 诚实门：JSON 可解析但不含计划节点（任意 JSON 都可能被贴进来）→ 失败提示，
                    // 不开「{plan}」废视图
                    val hasPlanNodes = runCatching {
                        cn.song.mybatisplus.visualizer.ui.ExplainJsonPlanTree.flattenPlanNodes(listOf(listOf(text)))
                    }.getOrDefault(emptyList()).isNotEmpty()
                    if (!hasPlanNodes) {
                        setStatus(
                            MpSqlVisualizerBundle.message(
                                "standalone.plan.paste.failed",
                                MpSqlVisualizerBundle.message("standalone.plan.paste.failed.jsonShaped"),
                            ),
                        )
                        return
                    }
                    // JSON 档：raw.rows 直接进既有管线（build() 全语义：徽章/收拢/统计）
                    openPastePlanDialog(
                        cn.song.mybatisplus.visualizer.ui.ExplainPlanResultView(
                            dialectName = MpSqlVisualizerBundle.message("standalone.plan.paste.dialect"),
                            dataSourceName = null,
                            elapsedMs = -1L,
                            raw = cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                                listOf("QUERY PLAN"),
                                listOf(listOf(text)),
                            ),
                            format = cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat.JSON,
                        ),
                    )
                    setStatus(MpSqlVisualizerBundle.message("standalone.plan.paste.success.json"))
                    return
                }
                // 形似 JSON 但解析不出 → 按 JSON_SHAPED 失败提示（解析器也会给同款 reason）
                setStatus(
                    MpSqlVisualizerBundle.message(
                        "standalone.plan.paste.failed",
                        MpSqlVisualizerBundle.message("standalone.plan.paste.failed.jsonShaped"),
                    ),
                )
                return
            }
            when (val parsed = cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.parse(text)) {
                is cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Result.Success -> {
                    val view = cn.song.mybatisplus.visualizer.ui.ExplainPlanResultView(
                        dialectName = MpSqlVisualizerBundle.message("standalone.plan.paste.dialect"),
                        dataSourceName = null,
                        elapsedMs = -1L,
                        raw = cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                            listOf("QUERY PLAN"),
                            listOf(listOf(text)),
                        ),
                        format = cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat.JSON,
                        prebuiltRoots = parsed.roots,
                        hints = runCatching {
                            cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.adviseText(text)
                        }.getOrDefault(emptyList()),
                    )
                    openPastePlanDialog(view)
                    setStatus(MpSqlVisualizerBundle.message("standalone.plan.paste.success.text", parsed.roots.size))
                }
                is cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Result.Failure -> {
                    setStatus(
                        MpSqlVisualizerBundle.message(
                            "standalone.plan.paste.failed",
                            pasteFailureText(parsed.reason),
                        ),
                    )
                }
            }
        }

        private fun readClipboard(): String? = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }

    /** PEV-01：粘贴失败原因 → 状态行文案（结构化 reason 分支，不猜格式）。 */
    private fun pasteFailureText(reason: cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Reason): String = when (reason) {
        cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Reason.EMPTY_INPUT ->
            MpSqlVisualizerBundle.message("standalone.plan.paste.empty")
        cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Reason.JSON_SHAPED ->
            MpSqlVisualizerBundle.message("standalone.plan.paste.failed.jsonShaped")
        cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Reason.NO_PLAN_NODES ->
            MpSqlVisualizerBundle.message("standalone.plan.paste.failed.noPlanNodes")
        cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Reason.UNRECOGNIZED_NODE_LINE ->
            MpSqlVisualizerBundle.message("standalone.plan.paste.failed.unrecognized")
    }

    /** PEV-01：粘贴计划视图弹窗（生产走 [ExplainPlanDialog]；测试缝可替换）。 */
    private fun openPastePlanDialog(view: cn.song.mybatisplus.visualizer.ui.ExplainPlanResultView) {
        (
            pasteDialogInvoker
                ?: { v: cn.song.mybatisplus.visualizer.ui.ExplainPlanResultView ->
                    cn.song.mybatisplus.visualizer.ui.ExplainPlanDialog(project, v).show()
                }
            ).invoke(view)
    }

    /**
     * 把选中的文件内容换进输入区并解析（P-1/P-3 口径：替换不弹确认，但状态行写明来源；
     * 超长文件**不写入**，避免把编辑器撑爆）。
     */
    private fun applyLoadedFile(loaded: MapperFileLoader.LoadedFile) {
        if (loaded.text.length > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.loadedFile.tooLong"))
            return
        }
        val replacedExisting = inputEditor.text.isNotBlank()
        // 程序性写入走 setSql（抑制补全 autopopup）；文档监听会先把 loadedFileName 清掉，故在其后赋值
        inputEditor.setSql(loaded.text)
        inputHintLabel.isVisible = false
        loadedFileName = loaded.fileName
        setStatus(
            MpSqlVisualizerBundle.message(
                if (replacedExisting) "standalone.status.loadedFile.replaced" else "standalone.status.loadedFile",
                loaded.fileName,
            ),
        )
        resolveFromInput()
    }

    /**
     * 在弹框中打开：同一解析链产出 → 既有 modeless 弹窗（与三条语句入口同构）。
     *
     * **`internal` 而非 `private`**：回归用例必须走这条真实动作路径——2026-11-18 缺陷就出在
     * 「动作先置标志位、再调无参方法被默认值抹掉」，只测面板方法会漏掉。
     */
    internal inner class OpenInDialogAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.DockedPreview
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.openDialog")
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            resolveFromInput(openDialog = true)
        }
    }

    // ------------------------------------------------------------------
    // 解析与展示
    // ------------------------------------------------------------------

    /**
     * 解析当前输入并展示（内部入口）。流程：EDT 取文本快照 → 后台纯文本解析 →
     * 代次守卫回 EDT 装配。
     *
     * **`openDialog` 只存在于本条调用链**（捕获进后台闭包、随代次守卫回传），不再用字段传递——
     * 2026-11-18 缺陷：原实现用 `openDialogPending` 字段，动作先置真再调无参 `resolveFromInput()`，
     * 被形参默认值 `false` 就地抹掉，导致「在弹框中打开」永不弹窗。字段形态跨调用、语义靠调用
     * 顺序维持，禁止回归。
     *
     * 空输入与未识别文本同样可以开弹窗（用户诉求：这个按钮永远能开）——空输入给空编辑器，
     * 未识别给原文 + 信息级说明，用户可直接在弹窗内粘贴/编辑。
     */
    fun resolveFromInput(openDialog: Boolean = false) {
        val rawInput = inputEditor.text
        if (rawInput.isBlank()) {
            if (openDialog) {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.emptyOpenedDialog"))
                openDialogWith(StandaloneSqlSourceResolver.emptyPreviewResult(), autoReparse = true)
            } else {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.empty"))
            }
            return
        }
        if (rawInput.length > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.tooLong"))
            return
        }
        // T-IncludePlaceholder A 券：补齐的片段只在解析前拼入，用户输入保持原样
        val text = MissingSqlFragmentSupport.withFragments(rawInput, fragmentOverrides)
        val generation = ++resolveGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching { StandaloneSqlSourceResolver.resolve(text) }.getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposed || generation != resolveGeneration || project.isDisposed) return@invokeLater
                    applyResolved(resolved, openDialog, rawInput)
                },
                ModalityState.nonModal(),
            )
        }
    }

    /**
     * 装配一次解析结果。
     *
     * T-PreviewActionRow D-5：缺失片段事件**不再由这里算**——解析器已按原文把
     * `missingFragmentIds` 挂进载荷，面板据此在结果区渲染补齐入口（工作台只负责注入"怎么补"）。
     * 这样弹窗（同一面板）也能展示同一份事实，不必各自扫文本。
     */
    private fun applyResolved(
        resolved: StandaloneSqlSourceResolver.Resolved?,
        openDialog: Boolean,
        rawText: String,
    ) {
        if (resolved == null) {
            setStatus(withOrigin(MpSqlVisualizerBundle.message("standalone.status.unrecognized")))
            if (openDialog) openDialogWith(rawTextResult(rawText), autoReparse = true)
            return
        }
        // 两条解析链都没还原：warning 换成结构化四段（标题｜来源｜语句｜原因｜建议），
        // 面板横幅与「复制错误信息」都读这一份——用户粘贴出去的才是可定位的完整信息。
        val result = resolved.failure?.let { failure ->
            resolved.result.copy(
                warning = buildFailureWarning(failure),
                informationalWarning = false,
            )
        } ?: resolved.result
        ensurePreviewPanel().showResult(result)
        previewHintLabel.isVisible = false
        setStatus(withOrigin(statusTextOf(resolved)))
        if (openDialog) openDialogWith(result)
    }

    /**
     * 状态行挂上「输入来源」（T-LoadFilePlacement）：文件载入后识别结果会覆盖状态行，
     * 不带上来源用户就分不清「这是文件里的内容还是我贴的」。
     */
    private fun withOrigin(text: String): String {
        val name = loadedFileName ?: return text
        return "$text ｜ ${MpSqlVisualizerBundle.message("standalone.status.loadedFile", name)}"
    }

    /**
     * T-PreviewActionRow D-6：补齐一个缺失片段后重解析。
     *
     * 为什么逐个补：一个片段可能引用另一个缺失片段，一次只问一个才不会让用户面对一个
     * 不知道从哪粘起的空框；补完重解析会重新算缺失集合，链路自然收敛。
     *
     * 内容来源走 [missingFragmentPrompt]（默认弹出框，测试可注入）——「记 overrides + 重解析」
     * 仍是真实链路，与弹窗侧共用 [MissingFragmentFillFlow]。
     */
    private fun fillMissingFragment(refid: String) {
        val content = missingFragmentPrompt(refid)
        if (!MissingFragmentFillFlow.record(refid, content, fragmentOverrides)) return
        resolveFromInput()
    }

    /**
     * 走既有 modeless 弹窗（`SqlPreviewService` 单例关旧开新）。
     *
     * @param autoReparse 「先开弹窗、后贴内容」场景置 true：弹窗挂防抖监听，用户往空编辑器里
     *   贴入 SQL / mapper 片段后自动重新识别一次（命中即交还手动，不干扰后续编辑）。
     */
    private fun openDialogWith(result: PreviewResult, autoReparse: Boolean = false) {
        if (project.isDisposed) return
        // T-PreviewActionRow D-7：把原文随窗带上——弹窗内看到 TODO 占位时才有东西可重解析补齐
        //（其余入口无原文，不传 → 弹窗不挂补齐入口）
        SqlPreviewService.showResult(
            project,
            result,
            autoParseOnPaste = autoReparse,
            sourceText = inputEditor.text,
        )
    }

    /**
     * 未识别文本的弹窗载荷：原文照搬 + 信息级说明（UI-001 的 `informationalWarning`，
     * 面板用 ℹ 前缀，不冒充渲染失败）。
     */
    private fun rawTextResult(text: String): PreviewResult = PreviewResult(
        sql = text,
        compactSql = text,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = StandaloneWarningText.rawTextNotice(),
        informationalWarning = true,
    )

    /** 结构化失败文案（单行、`｜` 分段）——与弹窗同源，见 [StandaloneWarningText]。 */
    private fun buildFailureWarning(failure: StandaloneSqlSourceResolver.Failure): String =
        StandaloneWarningText.failure(failure)

    /** 识别结果如实呈现（状态条教会用户「插件认成了什么」，含 MyBatis / iBatis 哪条链）。 */
    private fun statusTextOf(resolved: StandaloneSqlSourceResolver.Resolved): String = when (resolved.kind) {
        StandaloneSqlSourceResolver.Kind.XML_STATEMENT -> {
            val id = resolved.statementId ?: "?"
            val iBatis = resolved.flavor == XmlFlavorDetector.Flavor.IBATIS
            when {
                resolved.statementCount > 1 -> MpSqlVisualizerBundle.message(
                    if (iBatis) "standalone.status.ibatis.multi" else "standalone.status.xml.multi",
                    id,
                    resolved.statementCount,
                )
                iBatis -> MpSqlVisualizerBundle.message("standalone.status.ibatis", id)
                else -> MpSqlVisualizerBundle.message("standalone.status.xml", id)
            }
        }
        StandaloneSqlSourceResolver.Kind.PLAIN_SQL ->
            MpSqlVisualizerBundle.message("standalone.status.plain", resolved.paramCount)
    }

    /** 懒创建内嵌面板（防重注册守卫 + 预览区替换空态提示）。 */
    private fun ensurePreviewPanel(): SqlPreviewPanel {
        previewPanel?.let {
            previewHolder.add(it, BorderLayout.CENTER)
            previewHolder.revalidate()
            previewHolder.repaint()
            return it
        }
        val panel = SqlPreviewPanel(
            project,
            // T-StandaloneHintAndWarning：工作台专用空态提示——原文案的「选中代码」与 Alt+Shift+Q
            // 是编辑器流的语义，搬过来是假承诺（本类已接管该键，但「选中代码」在工作台不成立）。
            emptyHint = MpSqlVisualizerBundle.message("standalone.preview.emptyHint"),
        )
        // T-PreviewActionRow D-5：把「补齐」注入面板（面板只有渲染后 SQL，重解析要原文）
        panel.missingFragmentFillHandler = { refid -> fillMissingFragment(refid) }
        if (!previewPanelRegistered) {
            Disposer.register(this, panel)
            previewPanelRegistered = true
        }
        previewHolder.removeAll()
        previewHolder.add(panel, BorderLayout.CENTER)
        previewHolder.revalidate()
        previewHolder.repaint()
        previewPanel = panel
        refreshBottomActions()
        return panel
    }

    private fun clear() {
        resolveGeneration++
        loadFileGeneration++ // 丢弃在途的文件读取（清空后晚到的文本不得写回）
        fragmentOverrides.clear()
        loadedFileName = null
        inputEditor.setSql("")
        previewPanel?.let {
            previewHolder.removeAll()
            previewHolder.add(previewHintLabel, BorderLayout.NORTH)
            previewHintLabel.isVisible = true
            previewHolder.revalidate()
            previewHolder.repaint()
            Disposer.dispose(it)
        }
        previewPanel = null
        previewPanelRegistered = false
        refreshBottomActions()
        setStatus(" ")
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
    }

    // ------------------------------------------------------------------
    // 面板外通道（SqlLogPanel / action 桥接）
    // ------------------------------------------------------------------

    /** 工具窗页签选中后聚焦输入区；prefillText 非 null 时先程序性预填再解析（D-4 剪贴板候选）。 */
    fun focusAndMaybeResolve(prefillText: String?) {
        prefillText?.let { inputEditor.setSql(it) }
        inputEditor.editor.contentComponent.requestFocusInWindow()
        if (!prefillText.isNullOrBlank()) resolveFromInput()
    }

    /** 测试缝：程序性写入输入区（Light 用例驱动识别/弹窗链，不依赖真实键入）。 */
    internal fun setInputTextForTest(text: String) {
        inputEditor.setSql(text)
    }

    /** 测试缝：输入区当前文本（T-LoadFilePlacement：断言「载入替换了输入」）。 */
    internal fun inputTextForTest(): String = inputEditor.text

    /** 测试缝：输入工具栏动作序列（简单类名）——钉住「从文件加载」真的挂在工具栏上。 */
    internal fun inputToolbarActionNamesForTest(): List<String> = inputActions.map { it.javaClass.simpleName }

    /** 测试缝：底部动作行动作序列（简单类名）——钉住「复制 SQL」等确实在底部那一排。 */
    internal fun bottomActionNamesForTest(): List<String> = bottomActions.map { it.javaClass.simpleName }

    /** 测试缝：底部按钮 enable（按动作简单类名；不存在返回 null）。 */
    internal fun bottomButtonEnabledForTest(actionSimpleName: String): Boolean? {
        val index = bottomActions.indexOfFirst { it.javaClass.simpleName == actionSimpleName }
        return if (index < 0) null else bottomButtons.getOrNull(index)?.isEnabled
    }

    /** 测试缝：内嵌面板的缺失片段提示行组件（派发真实点击，覆盖「面板 → 工作台」回调链）。 */
    internal fun embeddedMissingFragmentLabelForTest(): javax.swing.JLabel? =
        previewPanel?.missingFragmentLabelForTest()

    /** 测试缝：内嵌面板实例（断言复制通知动作集合等面板态）。 */
    internal fun embeddedPanelForTest(): SqlPreviewPanel? = previewPanel

    /** 测试缝：内嵌预览面板当前生效的空态提示（证工作台专用文案真的接上了）。 */
    internal fun embeddedPreviewHintForTest(): String = ensurePreviewPanel().previewEmptyHintForTest()

    /** 测试缝：ExecuteAction 的 enable 判定现值（**不触发懒创建**——与 embeddedPreviewHintForTest 不同，读的是裸字段）。 */
    internal fun executeActionEnabledForTest(): Boolean = previewPanel != null

    /** 测试缝：状态行现值（无面板时点执行的引导文案断言）。 */
    internal fun statusTextForTest(): String = statusLabel.text

    override fun dispose() {
        if (disposed) return
        disposed = true
        resolveGeneration++
        loadFileGeneration++ // 丢弃在途的文件读取（释放后晚到的文本不得写回）
        // 显式释放子件（对齐 SqlToJavaPanel.dispose 的既有口径；guard 幂等防双释放）
        inputEditor.dispose()
        previewPanel?.let { Disposer.dispose(it) }
        previewPanel = null
        previewPanelRegistered = false
    }

    companion object {
        private const val TOOLBAR_ID = "SqlStandalonePreviewPanelToolbar"

        /** 底部主行动下标（顺序见 [bottomActions]）：复制 SQL——与弹窗「复制 SQL 并关闭」同一取走口径。 */
        private const val PRIMARY_BOTTOM_INDEX = 1

        /**
         * 重新解析快捷键：与 plugin.xml 里 ConvertToSql 的双 keymap 登记同源（Win/Linux `alt shift Q`，
         * macOS `meta shift Q`）——用户是在编辑器里学会这个键的，工作台沿用同一手感。
         */
        internal fun reparseShortcuts(): Array<Shortcut> = if (SystemInfo.isMac) {
            arrayOf(KeyboardShortcut(KeyStroke.getKeyStroke("meta shift Q"), null))
        } else {
            arrayOf(KeyboardShortcut(KeyStroke.getKeyStroke("alt shift Q"), null))
        }
    }
}
