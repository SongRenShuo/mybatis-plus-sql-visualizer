package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

public final class SqlDockedPreview implements Disposable {
    private final Project project;
    private final JList<LogSqlEntry> list;
    private final Runnable onClose;
    private final JPanel component = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(MpSqlVisualizerBundle.message("log.dockedPreview.empty"));
    private final JButton restore = new JButton(MpSqlVisualizerBundle.message("log.dockedPreview.restore"));
    private SqlClickPreviewContent content;
    private LogSqlEntry displayedEntry;
    private String originalSql;
    private boolean updating;
    private boolean opened;

    public SqlDockedPreview(Project project, JList<LogSqlEntry> list, Runnable onClose) {
        this.project = project;
        this.list = list;
        this.onClose = onClose;
        restore.setVisible(false);
        restore.addActionListener(e -> restoreAndFollow());
        component.setPreferredSize(new java.awt.Dimension(200, 220));
        JPanel header = new JPanel(new BorderLayout());
        header.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(restore);
        header.add(actions, BorderLayout.EAST);
        component.add(header, BorderLayout.NORTH);
        component.add(new JLabel(status.getText(), SwingConstants.CENTER), BorderLayout.CENTER);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) refreshSelection(); });
        list.getModel().addListDataListener(new ListDataListener() {
            @Override public void intervalAdded(ListDataEvent e) { refreshSelection(); }
            @Override public void intervalRemoved(ListDataEvent e) { refreshSelection(); }
            @Override public void contentsChanged(ListDataEvent e) { refreshSelection(); }
        });
    }

    public JPanel getComponent() { return component; }
    public boolean isOpen() { return opened; }
    public String getDisplayedSqlForTest() { return content != null && displayedEntry != null ? content.editor().getDocument().getText() : null; }
    public Editor getEditorForTest() { return content != null ? content.editor() : null; }
    public boolean isPinnedForTest() { return isPinned(); }

    /** T-PerfSweep26-05（F98）测试缝：isPinned() 调用计数（内部含 document.text 全文读）——
     *  同一条目展示中二次事件经同一性短路后不得再增长。 */
    private int isPinnedCallCountForTest;

    public int getIsPinnedCallCountForTest() { return isPinnedCallCountForTest; }

    public void restoreAndFollowForTest() { restoreAndFollow(); }

    public void open() { opened = true; refreshSelection(); }
    public void close() { opened = false; }

    public void resetContext() {
        displayedEntry = null;
        originalSql = null;
        restore.setVisible(false);
        Editor editor = getEditorForTest();
        if (editor != null && !editor.getDocument().getText().isEmpty()) {
            updating = true;
            try { WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().setText("")); }
            finally { updating = false; }
        }
        status.setText(MpSqlVisualizerBundle.message("log.dockedPreview.empty"));
        showCenter(new JLabel(status.getText(), SwingConstants.CENTER));
    }

    public void refreshSelection() {
        if (!opened) return;
        // T-PerfSweep26-05（F98）：同一条目已展示且仍是唯一选中 → 走到下方既有 `displayedEntry == entry`
        // 处本来就会原样返回，pin 态重算无任何旁路影响——提前返回，免付 isPinned() 的
        // document.text 全文拷贝（流式灌入每拍 ListDataEvent 都进这里）。两道守卫保证与原语义逐字
        // 等价：displayedEntry==null 不得短路（否则 null==null 命中，空态/多条选中态的状态文案
        // 不再刷新）；多选（selectedIndices.length!=1）不得短路（原路径会把预览清掉改显「已选多条」）。
        if (displayedEntry != null && list.getSelectedIndices().length == 1 && displayedEntry == list.getSelectedValue()) return;
        if (isPinned()) return;
        LogSqlEntry entry = list.getSelectedIndices().length == 1 ? list.getSelectedValue() : null;
        if (entry == null) {
            displayedEntry = null;
            originalSql = null;
            status.setText(MpSqlVisualizerBundle.message(list.getSelectedIndices().length > 1
                    ? "log.dockedPreview.multiple" : "log.dockedPreview.empty"));
            showCenter(new JLabel(status.getText(), SwingConstants.CENTER));
            return;
        }
        if (displayedEntry == entry) return;
        PluginSettings.State dockedState = PluginSettings.getInstance().getState();
        String sql = SqlClickPreview.clickPreviewSql(entry.restoredSql(), dockedState.getLogFormatSql(),
                dockedState.getDisplayMaskEnabled());
        if (content == null) {
            content = SqlClickPreview.buildContent(project, sql, UiTextClipboard::copy, onClose);
            content.editor().getDocument().addDocumentListener(new DocumentListener() {
                @Override public void documentChanged(DocumentEvent event) { updatePinnedStatus(); }
            });
        }
        if (!content.editor().getDocument().getText().equals(sql)) {
            updating = true;
            try { WriteCommandAction.runWriteCommandAction(project, () -> content.editor().getDocument().setText(sql)); }
            finally { updating = false; }
        }
        displayedEntry = entry;
        originalSql = sql;
        status.setText(MpSqlVisualizerBundle.message("log.dockedPreview.title"));
        restore.setVisible(false);
        showCenter(content.component());
    }

    private boolean isPinned() {
        isPinnedCallCountForTest++; // F98 测试缝
        return displayedEntry != null && !Objects.equals(content != null ? content.editor().getDocument().getText() : null, originalSql);
    }

    private void updatePinnedStatus() {
        if (updating || displayedEntry == null) return;
        boolean pinned = isPinned();
        status.setText(MpSqlVisualizerBundle.message(pinned ? "log.dockedPreview.pinned" : "log.dockedPreview.title"));
        restore.setVisible(pinned);
    }

    private void restoreAndFollow() {
        if (originalSql == null || content == null) return;
        updating = true;
        try { WriteCommandAction.runWriteCommandAction(project, () -> content.editor().getDocument().setText(originalSql)); }
        finally { updating = false; }
        restore.setVisible(false);
        refreshSelection();
        updatePinnedStatus();
    }

    private void showCenter(java.awt.Component next) {
        java.awt.Component previous = ((BorderLayout) component.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (previous != null) component.remove(previous);
        component.add(next, BorderLayout.CENTER);
        component.revalidate();
        component.repaint();
    }

    @Override public void dispose() {
        if (content != null) EditorFactory.getInstance().releaseEditor(content.editor());
        content = null;
    }
}
