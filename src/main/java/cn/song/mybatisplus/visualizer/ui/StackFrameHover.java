// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/StackFrameHover.kt
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.AbstractAction;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.KeyEvent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * T-StackHoverUi：SQL 预览面板 meta 行的调用栈悬停弹层（D84 拍板 A 案）。
 *
 * <p>交互契约（对照 IDEA 调试器 Frames 视图形态）：</p>
 * <ul>
 * <li>meta 行悬停 400ms 后弹层（单发 Timer 防抖，historyAlarm 同款惯用法）；栈顶帧在顶、
 *   首行默认选中（对应调试器「当前帧」高亮）；行内三段着色＝方法名（前景）+ 全限定类名（灰）+
 *   `:行号`（灰），类名超宽中段省略（行号始终可见）；agent 自身帧整行淡灰降权（模拟库帧弱化）。</li>
 * <li>鼠标在「meta 行 ∪ 弹层」区域内移动不关；离开 300ms 宽限后判定当前指针位置再关（事件坐标
 *   是越界瞬间的旧值，判定用 {@link MouseInfo} 实时指针），ESC / 点击外部 / 其他弹层打开由平台 popup
 *   栈兜底关闭。</li>
 * <li>点击帧跳源码（D85 拍板纳入）：全项目域按类名解析，命中即 {@link OpenFileDescriptor} 直跳行号
 *   （与日志面板跳转同基建）；未命中<b>弹层保持不关</b>——不制造已跳转的假象，也无声无成功提示。</li>
 * <li>底部「复制调用栈全文」复制原始快照串（原样，未解析）。</li>
 * </ul>
 *
 * <p>栈数据来源：{@code updateDigest} 由 {@code SqlPreviewPanel.updateMeta} 每次预览喂入；栈非空时 tooltip
 * 不再承载栈文本（旧实现把截断摘要+全文重复拼进 tooltip、超长单行折成文本墙——本类是栈文本
 * 在悬停面的唯一载体）。</p>
 */
public final class StackFrameHover implements Disposable {

    private final Project project;
    private final Runnable popupOpener;
    private JComponent target = null;
    private String digest = null;
    private String threadName = null;
    private JBPopup popup = null;
    private JComponent contentPanel = null;
    private Point pendingAnchor = null;

    /** 悬停延迟（ms）：防误触——meta 行是高频掠过区域，立即弹层会干扰正常阅读。 */
    private final Timer hoverTimer = new Timer(HOVER_DELAY_MS, e -> openPopup());

    /** 移出宽限（ms）：跨「meta 行 ↔ 弹层」边界的空档不立即关，宽限后按实时指针位置判定。 */
    private final Timer hideTimer = new Timer(HIDE_GRACE_MS, e -> hideIfPointerOutside());

    /** 弹层内容区的显隐联动：移入取消关闭宽限、移出重新宽限判定。 */
    private final MouseAdapter hideManager = new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
            hideTimer.stop();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (popup != null && !popup.isDisposed()) hideTimer.restart();
        }
    };

    public StackFrameHover(@Nullable Project project) {
        this(project, null);
    }

    public StackFrameHover(@Nullable Project project, @Nullable Runnable popupOpener) {
        this.project = project;
        this.popupOpener = popupOpener;
        hoverTimer.setRepeats(false);
        hideTimer.setRepeats(false);
    }

    /** 挂到 meta 行（悬停触发点）；面板 init 里调用一次。 */
    public void attach(JComponent target) {
        this.target = target;
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (digest == null) return;
                if (popup != null && !popup.isDisposed()) return;
                pendingAnchor = e.getPoint();
                hoverTimer.restart();
            }

            // 锚点跟随悬停落点：mouseEntered 只给边缘穿越点（自下而上进入＝行底），弹层锚定位置随
            // 进入方向漂移；移动中持续更新才保证「锚在哪弹在哪」
            @Override
            public void mouseMoved(MouseEvent e) {
                if (digest == null) return;
                if (popup != null && !popup.isDisposed()) return;
                pendingAnchor = e.getPoint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverTimer.stop();
                if (popup != null && !popup.isDisposed()) hideTimer.restart();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1
                        || digest == null || digest.isBlank()) return;
                hoverTimer.stop();
                hideTimer.stop();
                pendingAnchor = e.getPoint();
                openPopup();
            }
        };
        target.addMouseListener(listener);
        target.addMouseMotionListener(listener);
    }

    /**
     * 每次预览更新栈数据；栈变化即关旧弹层（下次悬停按新数据重弹）。
     *
     * <p>Kotlin 默认参数 {@code threadName = null} 的 Java 重载形态（既有单参调用点不动）。</p>
     */
    public void updateDigest(@Nullable String newDigest) {
        updateDigest(newDigest, null);
    }

    /**
     * 每次预览更新栈数据；栈变化即关旧弹层（下次悬停按新数据重弹）。
     *
     * @param threadName T-StackBizFrame S3：产生该 SQL 的线程名（agent 通道透传）——弹层标题展示，
     *                   让「调度线程 / 异步池 / 请求线程」一眼可辨；null 时标题退回纯「调用栈」。
     */
    public void updateDigest(@Nullable String newDigest, @Nullable String threadName) {
        digest = newDigest;
        this.threadName = threadName;
        cancelPopup();
    }

    /** 当前栈快照（面板接线单测断言用）。 */
    @Nullable
    public String currentDigestForTest() {
        return digest;
    }

    /** 当前线程名（面板接线单测断言用）。 */
    @Nullable
    public String currentThreadNameForTest() {
        return threadName;
    }

    @Override
    public void dispose() {
        hoverTimer.stop();
        hideTimer.stop();
        cancelPopup();
    }

    /**
     * T-StackBizFrame S3：调用栈弹层标题。
     *
     * <p>有线程名时附在标题后——用户报障的典型困惑是「这条 SQL 为什么不在我的请求链上」，
     * 而它其实跑在 {@code scheduling-1} / {@code pool-2-thread-1} 这类线程里；线程名是这条困惑的第一判别量。
     * 无线程名（桥通道 / 旧条目）退回纯「调用栈」，文案与既有行为逐字一致。</p>
     *
     * <p>抽成纯函数（master 同文件顶层函数的单文件静态承载）是为了脱 JBPopup 单测标题文案
     * （弹层本体在 headless 下无法真实弹出）。</p>
     */
    public static String buildStackPopupTitle(@Nullable String threadName) {
        if (threadName == null || threadName.isBlank()) {
            return MpSqlVisualizerBundle.message("panel.stackPopup.title");
        }
        return MpSqlVisualizerBundle.message("panel.stackPopup.titleWithThread", threadName);
    }

    // ------------------------------------------------------------------
    // 弹层构建与展示
    // ------------------------------------------------------------------

    private void openPopup() {
        if (popup != null && !popup.isDisposed()) return;
        if (popupOpener != null) popupOpener.run();
        else showPopup();
    }

    private void showPopup() {
        Point anchor = pendingAnchor;
        if (anchor == null) return;
        String d = digest;
        if (d == null) return;
        JComponent t = target;
        if (t == null) return;
        if (popup != null && !popup.isDisposed()) return;
        StackFramesProjection projection = StackFramesProjection.build(d);
        if (projection.nodes().isEmpty()) return;

        JComponent root = buildContent(projection, d);
        com.intellij.openapi.ui.popup.ComponentPopupBuilder builder =
                JBPopupFactory.getInstance().createComponentPopupBuilder(root, null)
                        .setTitle(buildStackPopupTitle(threadName))
                        .setRequestFocus(false)
                        .setFocusable(true)
                        .setCancelOnClickOutside(true);
        builder.addListener(new JBPopupListener() {
            @Override
            public void onClosed(LightweightWindowEvent event) {
                popup = null;
                contentPanel = null;
            }
        });
        JBPopup p = builder.createPopup();
        popup = p;
        contentPanel = root;
        p.show(new RelativePoint(t, anchor));
    }

    private JComponent buildContent(StackFramesProjection projection, String fullDigest) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("stack");
        for (StackFramesNode node : projection.nodes()) {
            DefaultMutableTreeNode branch = new DefaultMutableTreeNode(node);
            if (node instanceof StackFramesNode.Internal internal) {
                for (StackFrameEntry frame : internal.frames()) {
                    branch.add(new DefaultMutableTreeNode(frame, false));
                }
            }
            rootNode.add(branch);
        }
        JTree tree = new JTree(new DefaultTreeModel(rootNode));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);
        tree.setCellRenderer(new FrameRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                tree.requestFocusInWindow();
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                Object data = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (data instanceof StackFramesNode.Visible visible) navigateTo(visible.frame());
                else if (data instanceof StackFrameEntry frame) navigateTo(frame);
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "stack.activate");
        tree.getActionMap().put("stack.activate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath path = tree.getSelectionPath();
                if (path == null) return;
                Object data = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (data instanceof StackFramesNode.Internal) {
                    if (tree.isExpanded(path)) tree.collapsePath(path);
                    else tree.expandPath(path);
                } else if (data instanceof StackFramesNode.Visible visible) navigateTo(visible.frame());
                else if (data instanceof StackFrameEntry frame) navigateTo(frame);
            }
        });
        if (tree.getRowCount() > 0) tree.setSelectionRow(0);
        ActionLink copyLink = new ActionLink(MpSqlVisualizerBundle.message("panel.stackPopup.copy"), (ActionEvent e) -> {
            UiTextClipboard.copy(fullDigest);
            cancelPopup();
        });
        ActionLink expandLink = new ActionLink(MpSqlVisualizerBundle.message("panel.stackPopup.expandAll"), (ActionEvent e) -> {
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
                if (child.getUserObject() instanceof StackFramesNode.Internal) tree.expandPath(new TreePath(child.getPath()));
            }
        });
        ActionLink collapseLink = new ActionLink(MpSqlVisualizerBundle.message("panel.stackPopup.collapseAll"), (ActionEvent e) -> {
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                tree.collapsePath(new TreePath(((DefaultMutableTreeNode) rootNode.getChildAt(i)).getPath()));
            }
        });
        JBPanel<JBPanel<?>> controls = new JBPanel<>();
        controls.setOpaque(false);
        controls.add(expandLink);
        controls.add(collapseLink);
        JBPanel<JBPanel<?>> south = new JBPanel<>(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(JBUI.Borders.empty(4, 10, 6, 10));
        south.add(controls, BorderLayout.WEST);
        south.add(copyLink, BorderLayout.EAST);
        JBPanel<JBPanel<?>> root = new JBPanel<>(new BorderLayout());
        if (projection.noProjectFrames()) {
            root.add(new JBLabel(MpSqlVisualizerBundle.message("panel.stackPopup.noProjectFrames")), BorderLayout.NORTH);
        }
        root.add(new JBScrollPane(tree), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        root.addMouseListener(hideManager);
        root.setPreferredSize(new Dimension(MAX_POPUP_WIDTH,
                Math.max(120, Math.min(520, tree.getRowCount() * tree.getFont().getSize() * 2 + 56))));
        tree.addMouseListener(hideManager);
        return root;
    }

    // ------------------------------------------------------------------
    // 关闭判定与跳转
    // ------------------------------------------------------------------

    /** 宽限期到：按**实时**指针位置判定——仍落在「弹层（带边距）∪ meta 行」内则继续留。 */
    private void hideIfPointerOutside() {
        JBPopup p = popup;
        if (p == null) return;
        if (p.isDisposed()) return;
        Point pointer = MouseInfo.getPointerInfo() != null ? MouseInfo.getPointerInfo().getLocation() : null;
        if (pointer == null) {
            cancelPopup();
            return;
        }
        JComponent c = contentPanel;
        if (c != null && c.isShowing()) {
            Rectangle b = new Rectangle(c.getLocationOnScreen(), c.getSize());
            b.grow(HIDE_MARGIN_PX, HIDE_MARGIN_PX);
            if (b.contains(pointer)) return;
        }
        JComponent t = target;
        if (t != null && t.isShowing()
                && new Rectangle(t.getLocationOnScreen(), t.getSize()).contains(pointer)) return;
        cancelPopup();
    }

    private void cancelPopup() {
        JBPopup p = popup;
        if (p != null && !p.isDisposed()) p.cancel();
        popup = null;
        contentPanel = null;
    }

    /**
     * 点击帧跳源码：全项目域解析类名（库类落反编译视图，与调试器行为一致）；未命中静默返回
     * （弹层不关——不制造已跳转假象）。行号换算 `line - 1`（{@link OpenFileDescriptor} 0 基），与
     * 日志面板跳转链一致；无行号帧落类声明处。
     */
    private void navigateTo(StackFrameEntry frame) {
        Project proj = project;
        if (proj == null) return;
        PsiClass psiClass = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiClass>) () -> JavaPsiFacade.getInstance(proj).findClass(frame.fqcn(), GlobalSearchScope.allScope(proj)));
        if (psiClass == null) return;
        com.intellij.openapi.vfs.VirtualFile vf = ApplicationManager.getApplication().runReadAction(
                (Computable<com.intellij.openapi.vfs.VirtualFile>) () ->
                        psiClass.getContainingFile() != null ? psiClass.getContainingFile().getVirtualFile() : null);
        cancelPopup();
        if (vf != null && frame.line() > 0) {
            new OpenFileDescriptor(proj, vf, frame.line() - 1, 0).navigate(true);
        } else if (vf != null) {
            new OpenFileDescriptor(proj, vf).navigate(true);
        } else {
            psiClass.navigate(true);
        }
    }

    // ------------------------------------------------------------------
    // 调试器 Frames 风格行渲染
    // ------------------------------------------------------------------

    /**
     * 行渲染：`method  fqcn:line` 三段着色——方法名前景（agent 自身帧淡灰降权）、类名灰、
     * 行号灰；类名超宽按 {@code ellipsizeMiddle} 中段省略（保留头部包名与尾部类名），行号恒可见。
     * 预算取树组件字体度量，展开内部帧后仍保持行号可见。
     */
    private final class FrameRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                                          boolean leaf, int row, boolean hasFocus) {
            Object data = ((DefaultMutableTreeNode) value).getUserObject();
            setToolTipText(null);
            if (data instanceof StackFramesNode.Internal internal) {
                append(MpSqlVisualizerBundle.message("panel.stackPopup.internal", internal.frames().size()),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (data instanceof StackFramesNode.Omission omission) {
                append(omission.count() == null
                                ? MpSqlVisualizerBundle.message("panel.stackPopup.omittedUnknown")
                                : MpSqlVisualizerBundle.message("panel.stackPopup.omitted", omission.count()),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (data instanceof StackFramesNode.Visible visible) renderFrame(visible.frame(), tree);
            else if (data instanceof StackFrameEntry frame) renderFrame(frame, tree);
        }

        private void renderFrame(StackFrameEntry frame, JTree tree) {
            String line = frame.line() > 0 ? ":" + frame.line() : "";
            FontMetrics metrics = tree.getFontMetrics(tree.getFont());
            int classBudget = MAX_POPUP_WIDTH - 72 - metrics.stringWidth(frame.method() + ROW_GAP + line);
            String clazz = SqlPreviewPanelTextKt.ellipsizeMiddle(frame.fqcn(), metrics::stringWidth,
                    Math.max(24, classBudget));
            append(frame.method(), StackDigestFrames.isAgentFrame(frame)
                    ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(ROW_GAP + clazz, SimpleTextAttributes.GRAY_ATTRIBUTES);
            if (!line.isEmpty()) append(line, SimpleTextAttributes.GRAY_ATTRIBUTES);
            setToolTipText(frame.method() + "  " + frame.fqcn() + line);
        }
    }

    /** 悬停延迟：见 {@link #hoverTimer}。 */
    private static final int HOVER_DELAY_MS = 400;

    /** 移出宽限：见 {@link #hideTimer}。 */
    private static final int HIDE_GRACE_MS = 300;

    /** 弹层越界判定的外扩边距（px）：指针在边界抖动时不误关。 */
    private static final int HIDE_MARGIN_PX = 8;

    /** 行内「方法名 ↔ 类名」间隔串（同时参与测宽）。 */
    private static final String ROW_GAP = "  ";

    /** 弹层宽度上下界（px）：内容自适应，最长 2000 字符快照也封顶横向滚动外溢。 */
    private static final int MIN_POPUP_WIDTH = 280;
    private static final int MAX_POPUP_WIDTH = 680;

    /** 测宽加成：行内边距 + 竖向滚动条余量。 */
    private static final int POPUP_WIDTH_PADDING = 24;
    private static final int ROOT_WIDTH_PADDING = 8;

    /** 弹层视口最大可见行数：对齐 agent 栈帧预算钳位上限（StackDigest 12~64）。 */
    private static final int MAX_VISIBLE_ROWS = 64;
}
