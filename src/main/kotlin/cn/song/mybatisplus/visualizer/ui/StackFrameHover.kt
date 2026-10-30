package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Computable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * T-StackHoverUi：SQL 预览面板 meta 行的调用栈悬停弹层（D84 拍板 A 案）。
 *
 * 交互契约（对照 IDEA 调试器 Frames 视图形态）：
 * - meta 行悬停 400ms 后弹层（单发 Timer 防抖，historyAlarm 同款惯用法）；栈顶帧在顶、
 *   首行默认选中（对应调试器「当前帧」高亮）；行内三段着色＝方法名（前景）+ 全限定类名（灰）+
 *   `:行号`（灰），类名超宽中段省略（行号始终可见）；agent 自身帧整行淡灰降权（模拟库帧弱化）。
 * - 鼠标在「meta 行 ∪ 弹层」区域内移动不关；离开 300ms 宽限后判定当前指针位置再关（事件坐标
 *   是越界瞬间的旧值，判定用 [MouseInfo] 实时指针），ESC / 点击外部 / 其他弹层打开由平台 popup
 *   栈兜底关闭。
 * - 点击帧跳源码（D85 拍板纳入）：全项目域按类名解析，命中即 `OpenFileDescriptor` 直跳行号
 *   （与日志面板跳转同基建）；未命中**弹层保持不关**——不制造已跳转的假象，也无声无成功提示。
 * - 底部「复制调用栈全文」复制原始快照串（原样，未解析）。
 *
 * 栈数据来源：[updateDigest] 由 [SqlPreviewPanel.updateMeta] 每次预览喂入；栈非空时 tooltip
 * 不再承载栈文本（旧实现把截断摘要+全文重复拼进 tooltip、超长单行折成文本墙——本类是栈文本
 * 在悬停面的唯一载体）。
 */
/**
 * T-StackBizFrame S3：调用栈弹层标题。
 *
 * 有线程名时附在标题后——用户报障的典型困惑是「这条 SQL 为什么不在我的请求链上」，
 * 而它其实跑在 `scheduling-1` / `pool-2-thread-1` 这类线程里；线程名是这条困惑的第一判别量。
 * 无线程名（桥通道 / 旧条目）退回纯「调用栈」，文案与既有行为逐字一致。
 *
 * 抽成顶层纯函数是为了脱 JBPopup 单测标题文案（弹层本体在 headless 下无法真实弹出）。
 */
internal fun buildStackPopupTitle(threadName: String?): String =
    if (threadName.isNullOrBlank()) {
        MpSqlVisualizerBundle.message("panel.stackPopup.title")
    } else {
        MpSqlVisualizerBundle.message("panel.stackPopup.titleWithThread", threadName)
    }

internal class StackFrameHover(
    private val project: Project?,
    private val popupOpener: (() -> Unit)? = null,
) : Disposable {

    private var target: JComponent? = null
    private var digest: String? = null
    private var threadName: String? = null
    private var popup: JBPopup? = null
    private var contentPanel: JComponent? = null
    private var pendingAnchor: Point? = null

    /** 悬停延迟（ms）：防误触——meta 行是高频掠过区域，立即弹层会干扰正常阅读。 */
    private val hoverTimer = javax.swing.Timer(HOVER_DELAY_MS) { openPopup() }.apply { isRepeats = false }

    /** 移出宽限（ms）：跨「meta 行 ↔ 弹层」边界的空档不立即关，宽限后按实时指针位置判定。 */
    private val hideTimer = javax.swing.Timer(HIDE_GRACE_MS) { hideIfPointerOutside() }.apply { isRepeats = false }

    /** 弹层内容区的显隐联动：移入取消关闭宽限、移出重新宽限判定。 */
    private val hideManager = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            hideTimer.stop()
        }

        override fun mouseExited(e: MouseEvent) {
            if (popup != null && popup?.isDisposed == false) hideTimer.restart()
        }
    }

    /** 挂到 meta 行（悬停触发点）；面板 init 里调用一次。 */
    fun attach(target: JComponent) {
        this.target = target
        val listener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (digest == null) return
                if (popup != null && popup?.isDisposed == false) return
                pendingAnchor = e.point
                hoverTimer.restart()
            }

            // 锚点跟随悬停落点：mouseEntered 只给边缘穿越点（自下而上进入＝行底），弹层锚定位置随
            // 进入方向漂移；移动中持续更新才保证「锚在哪弹在哪」
            override fun mouseMoved(e: MouseEvent) {
                if (digest == null) return
                if (popup != null && popup?.isDisposed == false) return
                pendingAnchor = e.point
            }

            override fun mouseExited(e: MouseEvent) {
                hoverTimer.stop()
                if (popup != null && popup?.isDisposed == false) hideTimer.restart()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1 || digest.isNullOrBlank()) return
                hoverTimer.stop()
                hideTimer.stop()
                pendingAnchor = e.point
                openPopup()
            }
        }
        target.addMouseListener(listener)
        target.addMouseMotionListener(listener)
    }

    /**
     * 每次预览更新栈数据；栈变化即关旧弹层（下次悬停按新数据重弹）。
     *
     * @param threadName T-StackBizFrame S3：产生该 SQL 的线程名（agent 通道透传）——弹层标题展示，
     *   让「调度线程 / 异步池 / 请求线程」一眼可辨；null 时标题退回纯「调用栈」。
     */
    fun updateDigest(newDigest: String?, threadName: String? = null) {
        digest = newDigest
        this.threadName = threadName
        cancelPopup()
    }

    /** 当前栈快照（面板接线单测断言用）。 */
    internal fun currentDigestForTest(): String? = digest

    /** 当前线程名（面板接线单测断言用）。 */
    internal fun currentThreadNameForTest(): String? = threadName

    override fun dispose() {
        hoverTimer.stop()
        hideTimer.stop()
        cancelPopup()
    }

    // ------------------------------------------------------------------
    // 弹层构建与展示
    // ------------------------------------------------------------------

    private fun openPopup() {
        if (popup != null && popup?.isDisposed == false) return
        if (popupOpener != null) popupOpener.invoke() else showPopup()
    }

    private fun showPopup() {
        val anchor = pendingAnchor ?: return
        val d = digest ?: return
        val t = target ?: return
        if (popup != null && popup?.isDisposed == false) return
        val projection = StackFramesProjection.build(d)
        if (projection.nodes.isEmpty()) return

        val root = buildContent(projection, d)
        val builder = JBPopupFactory.getInstance().createComponentPopupBuilder(root, null)
            .setTitle(buildStackPopupTitle(threadName))
            .setRequestFocus(false)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
        builder.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                popup = null
                contentPanel = null
            }
        })
        val p = builder.createPopup()
        popup = p
        contentPanel = root
        p.show(RelativePoint(t, anchor))
    }

    private fun buildContent(projection: StackFramesProjection, fullDigest: String): JComponent {
        val rootNode = DefaultMutableTreeNode("stack")
        projection.nodes.forEach { node ->
            val branch = DefaultMutableTreeNode(node)
            if (node is StackFramesNode.Internal) node.frames.forEach { branch.add(DefaultMutableTreeNode(it, false)) }
            rootNode.add(branch)
        }
        val tree = JTree(DefaultTreeModel(rootNode)).apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = 0
            cellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(
                    tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
                    leaf: Boolean, row: Int, hasFocus: Boolean,
                ) {
                    val data = (value as DefaultMutableTreeNode).userObject
                    toolTipText = null
                    when (data) {
                        is StackFramesNode.Internal -> append(
                            MpSqlVisualizerBundle.message("panel.stackPopup.internal", data.frames.size),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                        is StackFramesNode.Omission -> append(
                            if (data.count == null) MpSqlVisualizerBundle.message("panel.stackPopup.omittedUnknown")
                            else MpSqlVisualizerBundle.message("panel.stackPopup.omitted", data.count),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                        is StackFramesNode.Visible -> renderFrame(data.frame, tree)
                        is StackFrameEntry -> renderFrame(data, tree)
                    }
                }

                private fun renderFrame(frame: StackFrameEntry, tree: JTree) {
                    val line = if (frame.line > 0) ":${frame.line}" else ""
                    val metrics = tree.getFontMetrics(tree.font)
                    val classBudget = MAX_POPUP_WIDTH - 72 - metrics.stringWidth(frame.method + ROW_GAP + line)
                    val clazz = ellipsizeMiddle(frame.fqcn, { metrics.stringWidth(it) }, classBudget.coerceAtLeast(24))
                    append(frame.method, if (StackDigestFrames.isAgentFrame(frame))
                        SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(ROW_GAP + clazz, SimpleTextAttributes.GRAY_ATTRIBUTES)
                    if (line.isNotEmpty()) append(line, SimpleTextAttributes.GRAY_ATTRIBUTES)
                    toolTipText = "${frame.method}  ${frame.fqcn}$line"
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    requestFocusInWindow()
                    val path = getPathForLocation(e.x, e.y) ?: return
                    val data = (path.lastPathComponent as DefaultMutableTreeNode).userObject
                    val frame = when (data) {
                        is StackFramesNode.Visible -> data.frame
                        is StackFrameEntry -> data
                        else -> null
                    }
                    if (frame != null) navigateTo(frame)
                }
            })
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "stack.activate")
            actionMap.put("stack.activate", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    val path = selectionPath ?: return
                    val data = (path.lastPathComponent as DefaultMutableTreeNode).userObject
                    when (data) {
                        is StackFramesNode.Internal -> if (isExpanded(path)) collapsePath(path) else expandPath(path)
                        is StackFramesNode.Visible -> navigateTo(data.frame)
                        is StackFrameEntry -> navigateTo(data)
                    }
                }
            })
            if (rowCount > 0) setSelectionRow(0)
        }
        val copyLink = ActionLink(MpSqlVisualizerBundle.message("panel.stackPopup.copy")) {
            UiTextClipboard.copy(fullDigest)
            cancelPopup()
        }
        val expandLink = ActionLink(MpSqlVisualizerBundle.message("panel.stackPopup.expandAll")) {
            for (i in 0 until rootNode.childCount) {
                val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
                if (child.userObject is StackFramesNode.Internal) tree.expandPath(TreePath(child.path))
            }
        }
        val collapseLink = ActionLink(MpSqlVisualizerBundle.message("panel.stackPopup.collapseAll")) {
            for (i in 0 until rootNode.childCount) tree.collapsePath(TreePath((rootNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
        val south = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 10, 6, 10)
            add(JBPanel<JBPanel<*>>().apply {
                isOpaque = false
                add(expandLink)
                add(collapseLink)
            }, BorderLayout.WEST)
            add(copyLink, BorderLayout.EAST)
        }
        val root = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            if (projection.noProjectFrames) add(JBLabel(MpSqlVisualizerBundle.message("panel.stackPopup.noProjectFrames")), BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
            addMouseListener(hideManager)
            preferredSize = Dimension(MAX_POPUP_WIDTH, (tree.rowCount * tree.font.size * 2 + 56).coerceIn(120, 520))
        }
        tree.addMouseListener(hideManager)
        return root
    }

    // ------------------------------------------------------------------
    // 关闭判定与跳转
    // ------------------------------------------------------------------

    /** 宽限期到：按**实时**指针位置判定——仍落在「弹层（带边距）∪ meta 行」内则继续留。 */
    private fun hideIfPointerOutside() {
        val p = popup ?: return
        if (p.isDisposed) return
        val pointer = MouseInfo.getPointerInfo()?.location
        if (pointer == null) {
            cancelPopup()
            return
        }
        contentPanel?.let { c ->
            if (c.isShowing) {
                val b = Rectangle(c.locationOnScreen, c.size)
                b.grow(HIDE_MARGIN_PX, HIDE_MARGIN_PX)
                if (b.contains(pointer)) return
            }
        }
        target?.let { t ->
            if (t.isShowing && Rectangle(t.locationOnScreen, t.size).contains(pointer)) return
        }
        cancelPopup()
    }

    private fun cancelPopup() {
        popup?.let { p -> if (!p.isDisposed) p.cancel() }
        popup = null
        contentPanel = null
    }

    /**
     * 点击帧跳源码：全项目域解析类名（库类落反编译视图，与调试器行为一致）；未命中静默返回
     * （弹层不关——不制造已跳转假象）。行号换算 `line - 1`（[OpenFileDescriptor] 0 基），与
     * 日志面板跳转链一致；无行号帧落类声明处。
     */
    private fun navigateTo(frame: StackFrameEntry) {
        val proj = project ?: return
        val psiClass = ApplicationManager.getApplication().runReadAction(
            Computable {
                JavaPsiFacade.getInstance(proj).findClass(frame.fqcn, GlobalSearchScope.allScope(proj))
            },
        ) ?: return
        val vf = ApplicationManager.getApplication().runReadAction(
            Computable { psiClass.containingFile?.virtualFile },
        )
        cancelPopup()
        when {
            vf != null && frame.line > 0 -> OpenFileDescriptor(proj, vf, frame.line - 1, 0).navigate(true)
            vf != null -> OpenFileDescriptor(proj, vf).navigate(true)
            else -> psiClass.navigate(true)
        }
    }

    private companion object {
        /** 悬停延迟：见 [hoverTimer]。 */
        private const val HOVER_DELAY_MS = 400

        /** 移出宽限：见 [hideTimer]。 */
        private const val HIDE_GRACE_MS = 300

        /** 弹层越界判定的外扩边距（px）：指针在边界抖动时不误关。 */
        private const val HIDE_MARGIN_PX = 8

        /** 行内「方法名 ↔ 类名」间隔串（同时参与测宽）。 */
        private const val ROW_GAP = "  "

        /** 弹层宽度上下界（px）：内容自适应，最长 2000 字符快照也封顶横向滚动外溢。 */
        private const val MIN_POPUP_WIDTH = 280
        private const val MAX_POPUP_WIDTH = 680

        /** 测宽加成：行内边距 + 竖向滚动条余量。 */
        private const val POPUP_WIDTH_PADDING = 24
        private const val ROOT_WIDTH_PADDING = 8

        /** 弹层视口最大可见行数：对齐 agent 栈帧预算钳位上限（StackDigest 12~64）。 */
        private const val MAX_VISIBLE_ROWS = 64
    }
}
