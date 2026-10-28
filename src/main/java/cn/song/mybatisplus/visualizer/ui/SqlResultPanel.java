// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlResultPanel.kt 移植（T-Round10FU：定位按钮 tooltip 三态文案）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * SQL 执行结果面板（M36）：在 SQL 预览面板下方展示执行状态与结果摘要。
 */
public class SqlResultPanel extends JBPanel<SqlResultPanel> {

    private enum State { EMPTY, LOADING, ERROR, SUCCESS }

    // T-RA-02：原 `statusLabel`（JBLabel）已删——自 M36 引入起就从未进过任何容器（无 add 调用），
    // 面板全部状态（EMPTY/LOADING/ERROR/SUCCESS）都经下方 infoLabel 单行展示，不存在独立的
    // 「状态行 + 信息行」分工；留着只会让后来者以为还有一条没接线的状态行。
    private final JBLabel infoLabel = new JBLabel();

    /**
     * T-UiNative T-UN-14：加载态转圈接原生 {@link AnimatedIcon.Default}（243 javap 核实），
     * 与既有文案并排（WEST 位，仅 LOADING 态可见）；原纯文字 JBLabel 加载态的
     * 文案键（"execute.result.loading"）沿用不变，测试断言文字仍成立。
     * 文字用空串（而非 JBLabel(Icon) 构造器）——保持 text 非 null，组件遍历类
     * 测试（SqlResultPanelUiTest/SqlPreviewPanelUiTest 的非空标签断言）零适配。
     */
    private final JBLabel loadingIconLabel;

    private final JButton errorCopyButton;

    /**
     * T-ErrorLocate T-EL-03：错误定位按钮（同 errorCopyButton 款式，仅错误态出现——
     * 渲染后 SQL 与 mapper 原文行不一一对应，v1 承诺语句起始行 + tooltip 如实说明，绝不虚标精确）。
     */
    private final JButton errorLocateButton;

    /** T-ErrorLocate：定位动作（宿主面板注入——需要 project + 预览源锚点，面板自身不可知）。 */
    private Runnable locateHandler = null;

    /** T-DialectGateUx：方言设置动作（错误态第二个可操作按钮，仅门控阻断时可见）。 */
    private Runnable settingsHandler = null;

    /** T-DialectGateUx：方言设置按钮（同 errorCopyButton 款式，挂 east 组）。 */
    private final JButton errorSettingsButton;

    private String currentError = null;
    private State state = State.EMPTY;

    public SqlResultPanel() {
        infoLabel.setForeground(JBColor.GRAY);
        infoLabel.setBorder(JBUI.Borders.empty(1, 4));

        loadingIconLabel = new JBLabel("", new AnimatedIcon.Default(), SwingConstants.CENTER);
        loadingIconLabel.setVisible(false);
        loadingIconLabel.setBorder(JBUI.Borders.empty(0, 4, 0, 0));

        errorCopyButton = new JButton(MpSqlVisualizerBundle.message("panel.copyError"));
        errorCopyButton.setVisible(false);
        errorCopyButton.setOpaque(false);
        errorCopyButton.setContentAreaFilled(false);
        errorCopyButton.setBorder(JBUI.Borders.empty(1, 6));
        errorCopyButton.setFont(errorCopyButton.getFont().deriveFont(11f));
        errorCopyButton.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.GRAY));
        errorCopyButton.setMargin(JBUI.insets(0, 6, 0, 6));
        errorCopyButton.addActionListener(e -> {
            if (currentError != null) {
                copyToClipboard(currentError);
                // T-ExecCopyFix：复制成功反馈（对齐 SqlClickPreview.flashCopied 对号语义）
                CopyFeedback.flashCopied(errorCopyButton);
            }
        });

        // T-ErrorLocate T-EL-03：错误定位按钮（同 errorCopyButton 款式，见字段注释）。
        errorLocateButton = new JButton(MpSqlVisualizerBundle.message("execute.error.locate"));
        errorLocateButton.setVisible(false);
        errorLocateButton.setOpaque(false);
        errorLocateButton.setContentAreaFilled(false);
        errorLocateButton.setBorder(JBUI.Borders.empty(1, 6));
        errorLocateButton.setFont(errorLocateButton.getFont().deriveFont(11f));
        errorLocateButton.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.GRAY));
        errorLocateButton.setMargin(JBUI.insets(0, 6, 0, 6));
        errorLocateButton.addActionListener(e -> {
            if (locateHandler != null) locateHandler.run();
        });

        // T-DialectGateUx：方言设置按钮（同 errorCopyButton 款式，仅错误态出现）。
        errorSettingsButton = new JButton(MpSqlVisualizerBundle.message("execute.dialectGate.action.openSettings"));
        errorSettingsButton.setVisible(false);
        errorSettingsButton.setOpaque(false);
        errorSettingsButton.setContentAreaFilled(false);
        errorSettingsButton.setBorder(JBUI.Borders.empty(1, 6));
        errorSettingsButton.setFont(errorSettingsButton.getFont().deriveFont(11f));
        errorSettingsButton.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.GRAY));
        errorSettingsButton.setMargin(JBUI.insets(0, 6, 0, 6));
        errorSettingsButton.addActionListener(e -> {
            if (settingsHandler != null) settingsHandler.run();
        });

        setBorder(JBUI.Borders.empty());
        setOpaque(false);
        // T-UiNative T-UN-14：加载转圈放左侧（CENTER 是 infoLabel 文案，EAST 是错误复制按钮）
        add(loadingIconLabel, BorderLayout.WEST);
        add(infoLabel, BorderLayout.CENTER);
        JBPanel<JBPanel<?>> east = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        east.setOpaque(false);
        east.setBorder(JBUI.Borders.empty());
        east.add(errorSettingsButton);
        east.add(errorLocateButton);
        east.add(errorCopyButton);
        add(east, BorderLayout.EAST);
        setVisible(false);
    }

    private void showContent() {
        setVisible(true);
    }

    /** T-UiNative T-UN-14：转圈图标随状态显隐（仅 showLoading 打开，其余状态统一关闭）。 */
    private void setLoadingIconVisible(boolean visible) {
        loadingIconLabel.setVisible(visible);
    }

    public void showTempFileOpened(String dataSourceName) {
        state = State.EMPTY;
        currentError = null;
        errorCopyButton.setVisible(false);
        errorLocateButton.setVisible(false);
        setLoadingIconVisible(false);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.fileOpened") + dsInfo + "</html>");
        infoLabel.setForeground(JBColor.GRAY);
        showContent();
        revalidate();
        repaint();
    }

    /**
     * 显示执行中状态（T-UiNative T-UN-14：转圈为原生 AnimatedIcon.Default，文字沿用既有键）。
     */
    public void showLoading(String dataSourceName) {
        state = State.LOADING;
        currentError = null;
        errorCopyButton.setVisible(false);
        errorLocateButton.setVisible(false);
        setLoadingIconVisible(true);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText(dataSourceName != null && !dataSourceName.isEmpty()
                ? "<html>" + MpSqlVisualizerBundle.message("execute.result.loading") + " | "
                + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) + "</html>"
                : MpSqlVisualizerBundle.message("execute.result.loading"));
        infoLabel.setForeground(JBColor.GRAY);
        showContent();
        revalidate();
        repaint();
    }

    public void showUpdateResult(int affectedRows, long elapsedMs, String dataSourceName) {
        state = State.SUCCESS;
        currentError = null;
        errorCopyButton.setVisible(false);
        errorLocateButton.setVisible(false);
        setLoadingIconVisible(false);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.result.affected", affectedRows, elapsedMs) + dsInfo + "</html>");
        infoLabel.setForeground(JBColor.namedColor("Label.successForeground", java.awt.Color.BLACK));
        showContent();
        revalidate();
        repaint();
    }

    public void showQueryResult(int rowCount, long elapsedMs, String dataSourceName) {
        state = State.SUCCESS;
        currentError = null;
        errorCopyButton.setVisible(false);
        errorLocateButton.setVisible(false);
        setLoadingIconVisible(false);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.result.rows", rowCount, elapsedMs) + dsInfo + "</html>");
        infoLabel.setForeground(JBColor.namedColor("Label.successForeground", java.awt.Color.BLACK));
        showContent();
        revalidate();
        repaint();
    }

    public void showError(String error) {
        state = State.ERROR;
        currentError = error;
        setLoadingIconVisible(false);
        String esc = error.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.result.error", esc) + "</html>");
        infoLabel.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        infoLabel.setToolTipText(error);
        errorCopyButton.setVisible(true);
        errorCopyButton.revalidate();
        errorCopyButton.repaint();
        locateHandler = null;
        errorLocateButton.setVisible(false);
        showContent();
        revalidate();
        repaint();
    }

    /**
     * T-ErrorLocate T-EL-03：富错误呈现——在 {@link #showError} 基础上追加「定位到出错位置」动作。
     *
     * <p>显隐先例对齐 errorCopyButton（仅错误时出现，不做常驻）；{@code locate} 为宿主注入的跳转动作
     * （预览面板持有 project 与预览源锚点，本面板不可知），null = 锚点不可解析、按钮不出现。
     * v1 落点为语句起始行（诚实降级）：驱动报的字符位置指向**被执行 SQL 文本**，与 mapper 原文
     * 行不一一对应（动态 SQL 拼接/参数注入），绝不虚标精确——tooltip 注明。
     *
     * <p><b>T-ELU-01 契约（调用方必读）</b>：{@code locate} 必须在<b>投递时</b>解析完毕——解析不出就传 null。
     * 宿主侧 {@code SqlPreviewPanel.showErrorRichWithLocate} 已改为「先解析、后显隐」；若调用方传入一个
     * 内部会静默 return 的动作，按钮就会可见而点击无反应（本任务修掉的正是这个形态）。</p>
     *
     * <p><b>T-ELU-01 的抹除链</b>：本类的 {@link #showError} 会清 {@code locateHandler} 与按钮可见性，
     * 故富通道投递后<b>不得</b>再投一次旧通道（{@code onError(String)}）——{@code DataSourceSelector.deliverError}
     * 已把两条通道改为互斥，否则富信息刚置的定位态会被同一次执行的旧通道立刻抹掉。</p>
     *
     * <p><b>T-Round10FU 三态 tooltip（独立复核 ㉕）</b>：{@code positionChar} → 带驱动字符位置的文案；
     * 否则 {@code lineInSql} → 带驱动行号的文案（MySQL 语法错误 {@code at line N} / {@code Line: N} 走这条）；
     * 两者皆无 → 「语句起始行」兜底文案。三条都如实标注位置指向<b>被执行的 SQL 文本</b>。</p>
     *
     * @param info   执行链富错误信息（T-EL-02 富通道投递；{@code ExecutionErrorInfo.executedSqlLength}
     *               为投递点传入的提交文本长度，供 tooltip 标注「位置指向执行态 SQL」，不做换算）
     * @param locate 定位动作；null = 不显示定位按钮
     */
    public void showErrorRich(cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo info, Runnable locate) {
        showError(info.message());
        locateHandler = locate;
        if (locate != null) {
            // T-Round10FU（独立复核 ㉕）：三态文案——字符位置 / 行号 / 两者皆无。
            // 改前只判 positionChar，故**只带行号的驱动报错**（MySQL 语法错误 `at line N`、
            // `Line: N`）落到「语句起始行」兜底文案：驱动其实报了行号，面板却当作没报
            // （`ExecutionErrorInfo.lineInSql` 因此在 src/main 无任何消费方）。
            Integer positionChar = info.positionChar();
            Integer lineInSql = info.lineInSql();
            if (positionChar != null) {
                errorLocateButton.setToolTipText(
                        MpSqlVisualizerBundle.message("execute.error.locate.tooltip.position", positionChar));
            } else if (lineInSql != null) {
                errorLocateButton.setToolTipText(
                        MpSqlVisualizerBundle.message("execute.error.locate.tooltip.line", lineInSql));
            } else {
                errorLocateButton.setToolTipText(
                        MpSqlVisualizerBundle.message("execute.error.locate.tooltip.start"));
            }
            errorLocateButton.setVisible(true);
            errorLocateButton.revalidate();
            errorLocateButton.repaint();
        }
    }

    /**
     * T-DdlRecover T-DR-03：「断开但已生效」警示条——错误态升级呈现（信息级、无新弹窗）。
     * 警示不消除错误原文（tooltip 保留原文）；文案口径「对象已存在（可能为本次已生效）」——
     * 探测命中仅呈现事实与时间戳，不声称「本次执行所建」（同名旧对象恰在场时不误导）。
     * 仅 ERROR 态生效（防陈旧探测在用户重新执行后误刷）。
     */
    public void showDdlEffectiveWarning(String target, String probedAt) {
        if (state != State.ERROR) return;
        String esc = target.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        infoLabel.setText("<html>"
                + MpSqlVisualizerBundle.message("execute.ddl.effective.warning", esc, probedAt)
                + "</html>");
        infoLabel.setForeground(JBColor.namedColor("WarningForeground", JBColor.ORANGE));
        revalidate();
        repaint();
    }

    /**
     * T-DdlRecover T-DR-03：探测未命中的如实注记——错误呈现保持原样，仅 tooltip 追加一行
     * 「已尝试生效性探测：未检出对象」（绝不把「没查到」包装成「没生效」）。仅 ERROR 态生效。
     */
    public void noteDdlProbeMissed() {
        if (state != State.ERROR) return;
        String miss = MpSqlVisualizerBundle.message("execute.ddl.probe.missed");
        StringBuilder tooltip = new StringBuilder();
        String existing = infoLabel.getToolTipText();
        if (existing != null) tooltip.append(existing);
        if (tooltip.length() > 0) tooltip.append("\n");
        tooltip.append(miss);
        infoLabel.setToolTipText(tooltip.toString());
        revalidate();
        repaint();
    }

    public void showEmpty() {
        state = State.EMPTY;
        currentError = null;
        errorCopyButton.setVisible(false);
        errorLocateButton.setVisible(false);
        setLoadingIconVisible(false);
        infoLabel.setText("");
        setVisible(false);
        revalidate();
        repaint();
    }

    public void clear() {
        showEmpty();
    }

    /**
     * 执行完成后的"空闲收尾"——清 loading/成功态，但**保留错误**（ERROR 态不清）。
     */
    public void showIdle() {
        if (state == State.ERROR) {
            revalidate();
            repaint();
            return;
        }
        showEmpty();
    }

    private void copyToClipboard(String text) {
        UiTextClipboard.copy(text);
    }

    /** M36.5.48：当前错误文本（测试断言用）。 */
    public String currentErrorForTest() {
        return currentError;
    }

    /** M36.5.49：当前状态（测试断言用）。 */
    public String stateForTest() {
        return state.name();
    }

    /** T-DialectGatePass：状态行文本（测试断言放行提示用，showStatus/showError 均写 infoLabel）。 */
    public String infoTextForTest() {
        return infoLabel.getText();
    }

    /** O-145 W7：显示不属于执行成功/失败的诚实状态（如已重渲染、需复核后二击）。 */
    public void showStatus(String message) {
        state = State.EMPTY;
        currentError = null;
        errorCopyButton.setVisible(false);
        errorSettingsButton.setVisible(false);
        if (locateHandler != null) locateHandler = null;
        setLoadingIconVisible(false);
        String esc = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        infoLabel.setText("<html>" + esc + "</html>");
        infoLabel.setToolTipText(message);
        infoLabel.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        showContent();
        revalidate();
        repaint();
    }

    /**
     * T-DialectGateUx：阻断态错误 + 就地出口（切数据源 / 打开方言设置）。
     * 入口为 null/label 为空时不显示对应按钮（例如 Community 无 Database 插件）。
     */
    public void showErrorWithActions(
            String error,
            String fixLabel,
            String fixTooltip,
            Runnable fix,
            String settingsLabel,
            String settingsTooltip,
            Runnable settings) {
        showError(error);
        if (fixLabel != null && fix != null) {
            locateHandler = fix;
            errorLocateButton.setText(fixLabel);
            errorLocateButton.setToolTipText(fixTooltip);
            errorLocateButton.setVisible(true);
        }
        if (settingsLabel != null && settings != null) {
            settingsHandler = settings;
            errorSettingsButton.setText(settingsLabel);
            errorSettingsButton.setToolTipText(settingsTooltip);
            errorSettingsButton.setVisible(true);
        }
        revalidate();
        repaint();
    }

}
