// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/MissingFragmentDialog.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * 缺失 {@code <sql id="…">} 片段的补齐输入框（T-IncludePlaceholder A 券）。
 *
 * <p>为什么自建而不 {@code Messages.showMultilineInput}：本平台（243 编译目标）{@code Messages} 没有可用的
 * 多行输入重载（编译期 Unresolved reference 实证），且这里需要「提示语 + 多行文本框 + 快捷键」
 * 的固定形态。</p>
 *
 * <p>用户可粘贴 {@code <sql id="x">…</sql>} 整段，也可只贴内容（纯列清单），由
 * {@link MissingSqlFragmentSupport#withFragments} 决定是否补壳。</p>
 */
public class MissingFragmentDialog extends DialogWrapper {

    private static final String DIMENSION_KEY = "cn.song.mybatisplus.visualizer.MissingFragmentDialog";

    private final Project project;
    private final String refid;

    private final JBTextArea area = createArea();

    private static JBTextArea createArea() {
        JBTextArea area = new JBTextArea();
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(JBUI.Borders.empty(4));
        return area;
    }

    public MissingFragmentDialog(@Nullable Project project, String refid) {
        super(project, true);
        this.project = project;
        this.refid = refid;
        setTitle(MpSqlVisualizerBundle.message("standalone.missingFragment.title"));
        init();
        // 按钮文案必须走插件 bundle：平台默认的 OK/Cancel 随 IDE 语言包，插件语言独立（用户实测中文环境仍显示 OK/Cancel）
        setOKButtonText(MpSqlVisualizerBundle.message("standalone.missingFragment.ok"));
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"));
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel hint = new JBLabel(
            MpSqlVisualizerBundle.message("standalone.missingFragment.prompt", refid));
        hint.setBorder(JBUI.Borders.emptyBottom(6));
        // T-IncludePlaceholder A 券二轮：从工程里选一个 mapper XML，自动抽出本 refid 对应的
        // `<sql id="…">` 内部内容（找不到就把整段文本带进来，由用户自己挑）
        JButton pickButton = new JButton(
            MpSqlVisualizerBundle.message("standalone.missingFragment.fromFile"));
        pickButton.addActionListener(e -> pickFromFile());
        JPanel header = new JPanel(new BorderLayout());
        header.add(hint, BorderLayout.CENTER);
        header.add(pickButton, BorderLayout.EAST);
        header.setBorder(JBUI.Borders.emptyBottom(6));
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setPreferredSize(new Dimension(560, 180));
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(JBUI.Borders.empty(8));
        body.add(header, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        return body;
    }

    /** 选文件 → 抽出本 refid 的 {@code <sql>} 内容（找不到则整段带入）。 */
    private void pickFromFile() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory
            .createSingleFileDescriptor("xml")
            .withTitle(MpSqlVisualizerBundle.message("standalone.missingFragment.fromFile.title"));
        VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
        if (file == null) {
            return;
        }
        String text;
        try {
            // 243 编译目标：ReadAction.compute(ThrowableComputable) 仅此重载
            text = ReadAction.compute(() -> VfsUtilCore.loadText(file));
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            text = null;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        String fragment = MissingSqlFragmentSupport.extractFragment(text, refid);
        area.setText(fragment != null ? fragment : text);
    }

    /** 用户输入的内容（确定时读取；取消路径不读）。 */
    public String content() {
        return area.getText();
    }

    @Override
    public String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }
}
