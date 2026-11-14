package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * F-1/F-xx：JOIN 关系图渲染面板。
 *
 * 渲染方案：paintComponent 内直接 Graphics2D 变换绘制，无中间位图。
 * 组件尺寸 = 图表自然尺寸 × zoom，scroll pane 处理溢出。
 *
 * T-UiNative T-UN-14：加载态/空态接原生件——加载态由 [JBLoadingPanel] 盖平台转圈
 * （原 indeterminate JProgressBar 自摆页删除），「无 JOIN」占位与错误态保留原样。
 *
 * T-RA-02：错误态接线——渲染（[DiagramRenderer] 测量/布局）抛异常时落 [errorScroll] 错误页，
 * 文案键 `diagram.render.failed`，由 [renderGuarded] 统一兜底。此前该错误页只有声明没有接线
 * （84d4407「直接 Graphics2D 渲染」重写时删掉了 showError 与 try/catch，字段与文案键一起悬空），
 * 类 KDoc 宣称的错误态与实际不符——本次把实现补上，表述与实现对齐。
 */
class DiagramPanel(private val project: Project? = null) : JBPanel<DiagramPanel>(BorderLayout()), Disposable {

    private var zoom = 1.0
    private var currentModel: DiagramModel? = null

    /**
     * T-UiNative T-UN-14：加载态接原生件——[JBLoadingPanel] 作为 CENTER 容器包住 swapContent
     * 的目标区（占位/画布/错误态都在其内容层换页），加载时 startLoading 盖平台转圈，
     * 原自摆 JBLabel + indeterminate JProgressBar 加载页删除；文案沿用既有 "diagram.render.loading" 键。
     * Disposable 挂面板自身（本类实现 Disposable，dispose 走既有 `Disposer.dispose(this)` 链）。
     *
     * T-F8：本层只在「未注入宿主加载面」时出转圈（独立使用/单测）；预览面板场景转圈由
     * [hostLoadingPanel] 出，原因见该字段 KDoc。
     */
    private val contentLayer = JBLoadingPanel(BorderLayout(), this)

    /**
     * T-F8：宿主加载面（预览面板注入）——本面板的转圈实际画在哪一层。
     *
     * 为什么必须由宿主出图：唯一真正的耗时段是 `SqlPreviewPanel.updateDiagramTab` 的**后台 schema
     * 富化**，而原生图路径（默认设置 `joinDiagramNative = true`，Ultimate 真机默认）下
     * `showJoinDiagram` 的 `diagramTabHost.removeAll()` 会把本面板摘出层级——自摆转圈根本不可见，
     * 这正是审计项 F8「showLoading/hideLoading 全仓零调用点」之外的第二层失效。宿主层
     * （预览面板把整个 JOIN 图标签页包进 JBLoadingPanel）对原生/自绘两条路径都可见。
     *
     * 为什么是「转交」而不是「两层同开」：同页两层 LoadingDecorator 各持一条独立动画时间线
     * （每个实例一个 AnimatedIcon/Animator，转圈相位互不同步），会叠成双圈；且宿主层已完整覆盖
     * 本面板区域（面板是其 CENTER 内容），面板侧再转一圈纯属重复。
     */
    private var hostLoadingPanel: JBLoadingPanel? = null

    /** T-F8：注入宿主加载面（预览面板的标签页级 [JBLoadingPanel]）。 */
    internal fun bindHostLoadingPanel(panel: JBLoadingPanel) {
        hostLoadingPanel = panel
    }

    /** 当前出转圈的层：宿主注入优先，否则本面板 [contentLayer]。 */
    private fun loadingTarget(): JBLoadingPanel = hostLoadingPanel ?: contentLayer

    private val placeholderLabel = JBLabel(
        MpSqlVisualizerBundle.message("diagram.placeholder.noJoin"), SwingConstants.CENTER,
    ).apply { foreground = JBColor.GRAY }

    /**
     * T-RA-02：渲染失败页——[contentLayer] 的第三个换页目标（另两个是 [placeholderLabel] 与 [scrollPane]）。
     *
     * 84d4407 把渲染改为直接 Graphics2D 后删掉了 `showError`，这两个字段与 bundle 键
     * `diagram.render.failed` 一起悬空（全仓含测试零引用），类 KDoc 却仍宣称有错误态。
     * 现由 [showError] 接线：渲染失败时用户看到失败原因，而不是上一态/空画布。
     * 用 JTextArea（非 JBLabel）是为了长异常摘要可换行、可选中复制。
     */
    private val errorTextArea = javax.swing.JTextArea().apply {
        isEditable = false
        font = UIUtil.getLabelFont()
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)
        lineWrap = true
        wrapStyleWord = true
    }
    private val errorScroll = JBScrollPane(errorTextArea)

    private val diagramCanvas = DiagramCanvas()

    private val scrollPane = JBScrollPane(diagramCanvas).apply {
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getPanelBackground()
        addMouseWheelListener { e ->
            if (e.isControlDown()) {
                if (e.wheelRotation < 0) zoomIn() else zoomOut()
                e.consume()
            }
        }
    }

    private var currentCenter: JComponent? = null

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(contentLayer, BorderLayout.CENTER)
        swapContent(placeholderLabel)
    }

    /**
     * T-UiNative T-UN-10：文本 JButton 按钮行 → 平台 ActionToolbar 图标动作组。
     * 图标均为 AllIcons 既有常量（javap 243 app-client.jar 核实：缩放/导出键都在 General，
     * Actions 组无 Zoom、Export 字段）；原按钮文案键转 tooltip（presentation.description），
     * 不新增 bundle 键。
     */
    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup(
            diagramToolbarAction(AllIcons.General.ZoomIn, "diagram.zoomIn") { zoomIn() },
            diagramToolbarAction(AllIcons.General.ZoomOut, "diagram.zoomOut") { zoomOut() },
            diagramToolbarAction(AllIcons.General.ActualZoom, "diagram.zoomReset") { zoomReset() },
        ).apply { addSeparator() } // 保留原「缩放 | 导出」分隔
        group.add(diagramToolbarAction(AllIcons.General.Export, "diagram.exportPng") { exportPng() })
        group.add(diagramToolbarAction(AllIcons.General.Export, "diagram.exportSvg") { exportSvg() })
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("mpvs.diagram.toolbar", group, true)
            .apply { targetComponent = this@DiagramPanel }
        return toolbar.component
    }

    /** 图标动作（T-UN-10）：icon 固定、tooltip 沿用原按钮 bundle 键、回调直通原按钮逻辑。 */
    private fun diagramToolbarAction(icon: javax.swing.Icon, tipKey: String, action: () -> Unit): AnAction =
        object : AnAction() {
            override fun update(e: AnActionEvent) {
                e.presentation.icon = icon
                e.presentation.description = MpSqlVisualizerBundle.message(tipKey)
            }

            override fun actionPerformed(e: AnActionEvent) = action()
        }

    /**
     * 展示模型（空模型 → 「无 JOIN」占位）。
     *
     * T-RA-02：测量先于换页——测量失败时错误页直接顶上，不留半成品画布（原顺序是先换页再测量，
     * 测量一抛就只剩一个空画布）；失败判定与错误页切换都在 [renderGuarded] 内。
     */
    fun showDiagram(model: DiagramModel) {
        if (model.isEmpty) { showPlaceholder(); return }
        currentModel = model
        if (!renderGuarded(model) { diagramCanvas.setModel(it, zoom) }) return
        swapContent(scrollPane)
        revalidate()
        repaint()
    }

    /**
     * T-RA-02：渲染动作的统一兜底。
     *
     * 为什么必须有：本面板的渲染 try/catch 在 84d4407「直接 Graphics2D 渲染」重写时被删掉，
     * 只留下 [errorTextArea]/[errorScroll] 与文案键 `diagram.render.failed` 悬空；而调用链
     * `SqlPreviewPanel.showJoinDiagram`（:1187）→ `updateDiagramTab`（:1118）→ `showResult`（:1059）
     * → `SqlPreviewDialog.init`（:33）→ `SqlPreviewService.showResult`（:1977）**全程无 catch**——
     * 渲染一抛，异常直接穿到 DialogWrapper 构造，弹窗起不来且用户看不到任何原因。
     *
     * 单独成函数（而非把 try/catch 内联进 [showDiagram]）是为了让失败路径可测：结构合法的模型在
     * [DiagramRenderer.measurePreferredSize] 里几乎不抛，公开 API 复现不出错误页。
     *
     * @return true = 渲染成功；false = 已落错误页（调用方不得再换页/重绘）
     */
    internal fun renderGuarded(model: DiagramModel, render: (DiagramModel) -> Unit): Boolean =
        try {
            render(model)
            true
        } catch (t: Throwable) {
            // 失败即回到「无图」语义：画布与 currentModel 一起清空，后续缩放/切页不再重试坏模型
            //（否则每次缩放都在同一处再抛一次）。warn 留痕：真机只有这里能看到渲染失败现场。
            com.intellij.openapi.diagnostic.Logger.getInstance(DiagramPanel::class.java)
                .warn("JOIN diagram render failed", t)
            currentModel = null
            diagramCanvas.setModel(null, zoom)
            showError("${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            false
        }

    /**
     * T-RA-02：渲染失败的错误页（[errorScroll] 接到内容层换页目标）。
     *
     * @param message 异常摘要——调用方拼 `异常类名: message`（比裸 message 更利于定位）。
     *                文案沿用既有键 `diagram.render.failed`（双语齐备、此前零引用），不新增键。
     */
    fun showError(message: String) {
        errorTextArea.text = MpSqlVisualizerBundle.message("diagram.render.failed", message)
        // 长摘要自动换行后默认停在末尾，从顶部看起更符合「读错误」的顺序
        errorTextArea.caretPosition = 0
        // T-F8：错误页顶上即「加载已结束」（渲染失败也是一种收尾），转圈必须同时摘下
        stopLoadingInternal()
        swapContent(errorScroll)
        revalidate()
        repaint()
    }

    /** T-RA-02：错误页是否当前页（测试断言用）。 */
    internal fun isErrorPageForTest(): Boolean = currentCenter === errorScroll

    /** T-RA-02：错误页文本（测试断言用）。 */
    internal fun errorTextForTest(): String = errorTextArea.text

    fun clear() {
        // T-F8：整图卸载必须复位加载态——加载期间（后台富化未回 EDT）用户切到无 JOIN 的 SQL
        // 或面板被清空时，转圈若不复位会留在已卸载的空页上永不消失
        stopLoadingInternal()
        currentModel = null
        diagramCanvas.setModel(null, zoom)
        showPlaceholder()
    }

    fun refreshAfterVisible() {
        if (currentModel != null && isShowing) {
            diagramCanvas.revalidate()
            repaint()
        }
    }

    private fun showPlaceholder() {
        // T-F8：空态是「无图」而非「加载中」——空模型换页即收尾，转圈同时摘下
        stopLoadingInternal()
        swapContent(placeholderLabel)
        revalidate()
        repaint()
    }

    /**
     * T-UiNative T-UN-14：换页目标从面板本体改为 [contentLayer] 内容层
     * （加载转圈盖在内容层之上，画布/占位/错误态不受影响）。
     */
    private fun swapContent(component: JComponent) {
        if (currentCenter === component) return
        currentCenter?.let { contentLayer.remove(it) }
        contentLayer.add(component, BorderLayout.CENTER)
        currentCenter = component
    }

    /** T-UiNative T-UN-14：进入加载态——平台转圈 + 既有「加载中」文案键（原自摆进度条删除）。 */
    fun showLoading() {
        loadingTarget().apply {
            setLoadingText(MpSqlVisualizerBundle.message("diagram.render.loading"))
            startLoading()
        }
        revalidate()
        repaint()
        loadingProbeForTest?.invoke(true)
    }

    /** T-UiNative T-UN-14：退出加载态（渲染/换页完成后调用）。 */
    fun hideLoading() {
        stopLoadingInternal()
        loadingProbeForTest?.invoke(false)
    }

    /**
     * T-F8：内部安全停止——不发探针（探针只报显式 API 调用，见 [loadingProbeForTest]）。
     *
     * 供 [clear]/[showPlaceholder]/[showError] 三条「加载态不再成立」的路径统一收口：
     * 加载期间无 JOIN 卸载、模型为空、渲染失败时都必须摘转圈，否则转圈留在空页/错误页上永不消失
     * （对齐 SqlResultPanel「仅 showLoading 打开、其余状态统一关闭」的既有约定）。
     */
    private fun stopLoadingInternal() {
        loadingTarget().stopLoading()
        revalidate()
        repaint()
    }

    /** T-UiNative T-UN-14：加载态（测试断言用）。 */
    internal fun isLoadingForTest(): Boolean = loadingTarget().isLoading

    /**
     * T-TS-05：出转圈的层（测试断言用）。
     *
     * 与 [isLoadingForTest] 的区别：后者读的是**委托结果**，宿主注入被删掉后仍能自洽地读回
     * [contentLayer]——单看它断不出「转圈画在了哪一层」。真机默认原生图路径下本面板会被
     * `diagramTabHost.removeAll()` 摘出层级，[contentLayer] 上的转圈根本不可见（F8 的原始形态），
     * 故第二层失效只能靠「出圈层 === 宿主加载面板」这种结构断言钉住。
     */
    internal fun loadingTargetForTest(): JBLoadingPanel = loadingTarget()

    /**
     * T-F8：加载态接线探针（测试用）——[showLoading]/[hideLoading] 末尾回调。
     *
     * 只报这两次「显式 API 调用」，内部安全停止（[clear]/[showPlaceholder]/[showError] 里的复位）
     * 不回调：接线用例要断的是「谁在什么时刻进了/出了加载态」，内部复位属状态自愈，由
     * [isLoadingForTest] 观测即可，混进同一序列会让「进入→渲染→退出」的顺序断言失真。
     */
    internal var loadingProbeForTest: ((Boolean) -> Unit)? = null

    /** T-F8：是否已渲染出图（测试用）——接线用例据此断言「停转圈发生在渲染之后」。 */
    internal fun hasModelForTest(): Boolean = currentModel != null

    fun zoomIn() { zoom = (zoom * 1.25).coerceAtMost(5.0); applyZoomToCanvas() }
    fun zoomOut() { zoom = (zoom / 1.25).coerceAtLeast(0.2); applyZoomToCanvas() }
    fun zoomReset() { zoom = 1.0; applyZoomToCanvas() }
    fun zoomForTest(): Double = zoom

    /**
     * T-RA-02：缩放重建画布尺寸走同一兜底（[renderGuarded]）——缩放是工具栏动作回调，
     * 里面抛异常只进平台日志，用户侧表现为「点缩放没反应」（同族静默失效）。
     */
    private fun applyZoomToCanvas() {
        val model = currentModel ?: return
        renderGuarded(model) { diagramCanvas.setModel(it, zoom) }
        revalidate()
        repaint()
    }

    private fun exportPng() {
        val model = currentModel ?: return
        val proj = project ?: return
        // T-Perf-14：渲染 + 编码移后台（宽 SELECT 大模型全量重绘/图像编码在 EDT 构成卡顿）；
        // 保存对话框留在 EDT（用户交互），渲染/编码失败回 EDT 提示
        ApplicationManager.getApplication().executeOnPooledThread {
            val bytes = runCatching {
                val image = DiagramRenderer.render(model)
                val baos = ByteArrayOutputStream()
                ImageIO.write(image, "png", baos)
                if (baos.size() == 0) throw RuntimeException("PNG encode produced no output")
                baos.toByteArray()
            }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (bytes == null) {
                    showExportError(proj, "render/encode")
                    return@invokeLater
                }
                runCatching {
                    saveBytesToFile(proj, bytes, "join_diagram.png", "png")
                }.onFailure { e ->
                    showExportError(proj, e.message ?: "unknown")
                }
            }
        }
    }

    private fun exportSvg() {
        val model = currentModel ?: return
        val proj = project ?: return
        // T-Perf-14：SVG 渲染/编码移后台（同 exportPng）
        ApplicationManager.getApplication().executeOnPooledThread {
            val bytes = runCatching {
                DiagramRenderer.renderToSvg(model).toByteArray(Charsets.UTF_8)
            }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (bytes == null) {
                    showExportError(proj, "render/encode")
                    return@invokeLater
                }
                runCatching {
                    saveBytesToFile(proj, bytes, "join_diagram.svg", "svg")
                }.onFailure { e ->
                    showExportError(proj, e.message ?: "unknown")
                }
            }
        }
    }

    private fun saveBytesToFile(proj: Project, bytes: ByteArray, defaultName: String, ext: String) {
        val descriptor = FileSaverDescriptor(
            MpSqlVisualizerBundle.message("diagram.export.title"),
            MpSqlVisualizerBundle.message("diagram.export.desc"), ext)
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, proj)
        val vfsFile: VirtualFileWrapper? = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName)
        if (vfsFile != null) {
            val file: File = vfsFile.file
            // T-Perf-14：磁盘写入移后台；成功提示保持 EDT
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { file.writeBytes(bytes) }
                    .onFailure { e ->
                        com.intellij.openapi.diagnostic.Logger.getInstance(DiagramPanel::class.java)
                            .warn("diagram export write failed: ${e.message}", e)
                    }
            }
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                ApplicationManager.getApplication().invokeLater {
                    runCatching {
                        Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("diagram.export.title"),
                            MpSqlVisualizerBundle.message("diagram.export.success", file.absolutePath),
                            NotificationType.INFORMATION,
                        ).notify(proj)
                    }
                }
            }
        }
    }

    /**
     * T-UiNative T-UN-02：导出失败改 ERROR 轻通知（原模态错误框——失败仍醒目，但不打断）。
     * 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
     */
    private fun showExportError(proj: Project, detail: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("diagram.export.title"),
                MpSqlVisualizerBundle.message("diagram.export.failed", detail),
                NotificationType.ERROR,
            ).notify(proj)
        }
    }

    override fun dispose() {
        currentModel = null
        // T-HiddenBugPerfScan 券 Y2：删除自处置——本实例已由宿主挂树
        // （SqlPreviewPanel Disposer.register(this, diagramPanel)），树级联到达本方法
        // 时再对正在被处置的自身调用 Disposer.dispose(this) 属重入双处置
        // （平台记 already disposed 级错误日志/测试断言失败风险）。全仓无
        // diagramPanel.dispose() 直调路径（grep 取证），树处置已覆盖。
    }

    private companion object {
        /** T-UiNative T-UN-02：导出成功/失败轻通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
    }

    /**
     * 直接 Graphics2D 渲染画布。
     * preferredSize = 图表自然尺寸 × zoom → scroll pane 自动处理溢出。
     */
    private inner class DiagramCanvas : JComponent() {
        private var model: DiagramModel? = null

        fun setModel(m: DiagramModel?, z: Double) {
            model = m
            if (m != null && !m.isEmpty) {
                val pref = DiagramRenderer.measurePreferredSize(m)
                val w = (pref.width * z).toInt().coerceAtLeast(100)
                val h = (pref.height * z).toInt().coerceAtLeast(60)
                preferredSize = java.awt.Dimension(w, h)
            } else {
                preferredSize = java.awt.Dimension(200, 100)
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val m = model ?: return
            if (m.isEmpty) return

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            // 背景
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)

            val pref = DiagramRenderer.measurePreferredSize(m)
            val scale = zoom.coerceIn(0.2, 5.0)
            val offsetX = ((width - pref.width * scale) / 2).coerceAtLeast(0.0)
            val offsetY = ((height - pref.height * scale) / 2).coerceAtLeast(0.0)
            g2.translate(offsetX, offsetY)
            g2.scale(scale, scale)

            val font = UIUtil.getLabelFont()
            val bold = font.deriveFont(Font.BOLD)
            val frc = g2.fontRenderContext

            val lines = DiagramRenderer.prepareLinesFor(m)
            // Canvas 与 PNG/SVG 使用相同的绘制顺序和关系线
            DiagramRenderer.drawOrthoLinesForCanvas(g2, m, lines, font, bold)
            DiagramRenderer.drawCards(g2, m, font, bold)
            DiagramRenderer.drawLabels(g2, m, lines, font, frc)

            g2.dispose()
        }
    }
}
