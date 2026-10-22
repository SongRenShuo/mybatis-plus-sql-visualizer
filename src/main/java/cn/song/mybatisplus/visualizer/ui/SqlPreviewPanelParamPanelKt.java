// T-SyncK2JMaster482 波D1 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelParamPanel.kt
// （T-M53-II 2026-10-10：SqlPreviewPanel 的「参数面板构建、方案/历史与参数值回填渲染」内聚成员，
//   以同包扩展函数形式搬出——方法体逐字符搬运）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.ConditionView;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.ConditionOutcome;
import cn.song.mybatisplus.visualizer.xml.ExprReason;
import cn.song.mybatisplus.visualizer.xml.ExprSupport;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql;
import cn.song.mybatisplus.visualizer.xml.XmlConditionView;
import cn.song.mybatisplus.visualizer.xml.XmlForEachView;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HideableTitledPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code SqlPreviewPanelParamPanel.kt}（T-M53-II 拆分件）的 Java 承载类——扩展函数
 * {@code fun SqlPreviewPanel.foo(...)} 落为 {@code public static R foo(SqlPreviewPanel receiver, ...)}。
 *
 * <p>含参数面板构建、方案/历史与参数值回填渲染相关成员。</p>
 */
public final class SqlPreviewPanelParamPanelKt {

    private SqlPreviewPanelParamPanelKt() {
    }

    public static void rebuildLeftPanel(@NotNull SqlPreviewPanel p, PreviewResult result) {
        p.paramPanel.removeAll();
        p.paramFields.clear();
        p.paramWarnLabels.clear();
        p.conditionCheckBoxes.clear();
        p.xmlConditionOutcomeLabels.clear();
        p.conditionValueFields.clear();
        p.batchCheckBoxes.clear();
        p.xmlForEachNullBoxes.clear();
        p.xmlForEachValueFields.clear();
        // T-M26.3：按钮随场景重建（标题行右侧）——先清空上次场景的残留引用
        p.toggleSelectButton = null;
        p.clearButton = null;
        p.batchToggleSelectButton = null;
        p.batchClearButton = null;
        List<ConditionView> views = result.getPreviewModel() != null
                ? result.getPreviewModel().getConditionViews() : null;
        if (views == null) views = List.of();
        BatchModel batchModel = result.getBatchModel();
        // R22 T-04：先算 hasParams——方案行插在首个早退之后，xml 无控件早退场景必须覆盖
        // （xml 无控件时无参数可存召回，不插方案行，避免「方案下拉 + 无参数提示」同屏）
        XmlPreviewModel xmlModelForSchemeRow = result.getXmlModel();
        boolean xmlHasControls = xmlModelForSchemeRow != null
                && (!xmlModelForSchemeRow.getConditionViews().isEmpty()
                        || !xmlModelForSchemeRow.getForEachViews().isEmpty());

        if (views.isEmpty() && result.getUnresolvedParams().isEmpty() && batchModel == null && !xmlHasControls) {
            // T-DevAcceptFix ①：绑定指示行已迁顶部常驻行——无参数早退不再重复建行
            JBLabel none = new JBLabel(MpSqlVisualizerBundle.message("panel.param.none"));
            none.setBorder(JBUI.Borders.empty(6, 8));
            p.paramPanel.add(none);
            p.paramPanel.revalidate();
            p.paramPanel.repaint();
            return;
        }

        // R22 T-04：方案行（设计定稿位置——首个早退之后、batchModel 分支之前）
        p.paramPanel.add(buildSchemeRow(p));

        // T-M23-04：批量列级勾选（toggle 列：复选框 + 值输入框，勾选决定列是否进入 SQL；
        // fixed 列：仅值输入框，恒参与）——与动态条件区互斥（批量预览无 conditionViews）
        // T-M23.2（用户要求）：参数可选场景必须有【全选/全不选】按钮（标题行右侧，T-M26.3 回归）
        if (batchModel != null) {
            p.paramPanel.add(batchSectionHeader(p));
            // T-M26.3：fixed 列标签参与列宽统一（toggle 列复选框文本独立截断上限）
            List<String> fixedLabels = new ArrayList<>();
            for (BatchColumn c : batchModel.getFixedColumns()) fixedLabels.add("#" + c.getSource());
            int labelWidth = labelColumnWidth(p, fixedLabels);
            for (BatchColumn c : batchModel.getToggleColumns()) {
                p.paramPanel.add(buildBatchColumnRow(p, c, true));
            }
            for (BatchColumn c : batchModel.getFixedColumns()) {
                p.paramPanel.add(buildBatchColumnRow(p, c, false, labelWidth));
            }
            p.paramPanel.revalidate();
            p.paramPanel.repaint();
            return;
        }

        // T-M24-04：XML 预览左栏——动态条件开关 + foreach 次数/null + 参数值输入
        // T-M26.4：bind 分区已删除——bind 求值结果由「参数」区回显（输入覆盖、清空恢复自动值）
        XmlPreviewModel xmlModel = result.getXmlModel();
        if (xmlModel != null) {
            boolean hasControls = !xmlModel.getConditionViews().isEmpty() || !xmlModel.getForEachViews().isEmpty();
            if (!hasControls && result.getUnresolvedParams().isEmpty()) {
                JBLabel none = new JBLabel(MpSqlVisualizerBundle.message("panel.param.none"));
                none.setBorder(JBUI.Borders.empty(6, 8));
                p.paramPanel.add(none);
                p.paramPanel.revalidate();
                p.paramPanel.repaint();
                return;
            }
            // #{} 参数值输入（T-M25.4：foreach 展开项不再单独建行——集合值由 foreach 输入框统一回填）
            List<String> ordinarySources = new ArrayList<>();
            for (String s : result.getUnresolvedParams()) {
                if (!s.startsWith("__frch_") && !ordinarySources.contains(s)) ordinarySources.add(s);
            }
            // T-M26.3：两列行标签列宽统一（foreach/参数按最长可见标签自适应 140~260）
            // T-M26.5：foreach 行标签即集合表达式（分区标题已含「foreach 集合」语义）
            List<String> labelTexts = new ArrayList<>();
            for (XmlForEachView v : xmlModel.getForEachViews()) labelTexts.add(v.collectionExpr());
            for (String s : ordinarySources) labelTexts.add("#" + s);
            int labelWidth = labelColumnWidth(p, labelTexts);
            if (!xmlModel.getConditionViews().isEmpty()) {
                p.paramPanel.add(conditionSectionHeader(p));
                for (XmlConditionView v : xmlModel.getConditionViews()) p.paramPanel.add(buildXmlConditionRow(p, v));
            }
            if (!xmlModel.getForEachViews().isEmpty()) {
                p.paramPanel.add(sectionHeaderRow(p, MpSqlVisualizerBundle.message("panel.xml.foreach.section")));
                for (XmlForEachView v : xmlModel.getForEachViews()) p.paramPanel.add(buildXmlForEachRow(p, v, labelWidth));
            }
            if (!ordinarySources.isEmpty()) {
                if (hasControls) {
                    p.paramPanel.add(sectionHeaderRow(p, MpSqlVisualizerBundle.message("panel.param.section")));
                }
                for (String s : ordinarySources) p.paramPanel.add(buildParamRow(p, s, labelWidth));
            }
            p.paramPanel.revalidate();
            p.paramPanel.repaint();
            return;
        }

        Set<String> viewSources = new LinkedHashSet<>();
        for (ConditionView v : views) viewSources.addAll(v.getValueSources());
        // 普通参数行：未解析参数中不属于任何动态条件值输入框的来源（去重，同一 source 共享一个输入框）
        List<String> ordinarySources = new ArrayList<>();
        for (String s : result.getUnresolvedParams()) {
            if (!viewSources.contains(s) && !ordinarySources.contains(s)) ordinarySources.add(s);
        }
        List<String> labelTexts = new ArrayList<>();
        for (String s : ordinarySources) labelTexts.add("#" + s);
        int labelWidth = labelColumnWidth(p, labelTexts);
        if (!views.isEmpty()) {
            p.paramPanel.add(conditionSectionHeader(p));
            for (ConditionView v : views) p.paramPanel.add(buildConditionRow(p, v));
        }
        if (!ordinarySources.isEmpty()) {
            if (!views.isEmpty()) {
                p.paramPanel.add(sectionHeaderRow(p, MpSqlVisualizerBundle.message("panel.param.section")));
            }
            for (String s : ordinarySources) p.paramPanel.add(buildParamRow(p, s, labelWidth));
        }
        // T-JpaParamOrder F5：同名参数多次 setParameter 轻提示（JPA 覆盖语义，运行时以最后一次
        // 为准）——帮用户发现自身代码的写重；灰字注记行，不发通知、不弹窗
        if (!result.getDuplicateParamNames().isEmpty()) {
            List<String> sorted = new ArrayList<>(result.getDuplicateParamNames());
            Collections.sort(sorted);
            JBLabel duplicateNote = new JBLabel(MpSqlVisualizerBundle.message(
                    "panel.param.duplicate.note", String.join(", ", sorted)));
            duplicateNote.setForeground(JBColor.GRAY);
            duplicateNote.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
            p.paramPanel.add(duplicateNote);
        }
        p.paramPanel.revalidate();
        p.paramPanel.repaint();
    }

    // ------------------------------------------------------------------
    // R22 T-04：命名方案下拉 + 管理对话框接入（设计定稿：方案行 / 防串桶 / 对话框回调）
    // ------------------------------------------------------------------

    /**
     * R22 T-04：方案行——「预设: [下拉] [管理预设…]」（compactButton 同字号，非自创形态）。
     * 下拉项为原始方案名（默认方案 = 空串内部名），显示名经 bundle 本地化
     * （默认方案显示名与用户命名可撞名，故渲染器映射而非直接用显示名作条目）。
     * O-88：整行收进平台标准折叠件 {@link HideableTitledPanel}，标题复用 `panel.scheme.label`，
     * 缺省折叠（20261006 T-DevAcceptFix 验收反馈：预设低频，默认不占竖向空间）；折叠时内容区
     * setVisible(false) 不占竖向空间。折叠态存静态字段（{@code presetSectionCollapsed}，
     * 跨预览重建且跨面板实例保留、不持久化）。
     */
    @NotNull
    public static JComponent buildSchemeRow(@NotNull SqlPreviewPanel p) {
        // O-88：懒惰捕获——上一份折叠件实例若被用户点标题折叠过，先回写静态字段再重建
        // （HideableTitledPanel 公共面无监听器；摘除后实例仍持有最后视觉态，clear/rebuild 均不丢）
        if (p.presetFold != null) {
            SqlPreviewPanel.presetSectionCollapsed = !p.presetFold.isExpanded();
        }
        JComboBox<String> combo = new JComboBox<>();
        p.schemeCombo = combo;
        // Kotlin：SimpleListCellRenderer.create<String> { label, value, _ -> label.text = displaySchemeName(value ?: DEFAULT_SCHEME) }
        // Java 用匿名子类覆写 customize，避开 create 工厂通配参数化的推断歧义。
        combo.setRenderer(new SimpleListCellRenderer<String>() {
            @Override
            public void customize(javax.swing.JList<? extends String> list, String value, int index,
                                  boolean selected, boolean hasFocus) {
                setText(ParamSchemeManagerDialogKt.displaySchemeName(
                        value != null ? value : ParamHistoryCache.DEFAULT_SCHEME));
            }
        });
        combo.addActionListener(e -> {
            if (p.schemeSyncingCombo) return;
            Object sel = combo.getSelectedItem();
            String raw = sel instanceof String ? (String) sel : null;
            if (raw == null) return;
            if (!raw.equals(p.paramHistoryCache.activeSchemeFor(historyCacheKey(p)))) switchToScheme(p, raw);
        });
        syncSchemeCombo(p);
        JButton manageButton = compactButton(p, MpSqlVisualizerBundle.message("panel.scheme.manage"),
                () -> openSchemeManagerDialog(p));
        JBPanel<JBPanel<?>> schemeRow = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6));
        schemeRow.setName(SqlPreviewPanel.SCHEME_ROW_NAME);
        schemeRow.add(new JBLabel(MpSqlVisualizerBundle.message("panel.scheme.label")));
        schemeRow.add(combo);
        schemeRow.add(manageButton);
        schemeRow.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        // O-88：adjustWindow=false——预览面板嵌在弹窗/工具窗内容区，折叠/展开不得调整窗口尺寸
        // （源码语义：true 时 adjustWindow 会按内容尺寸改祖先 Window 大小，Run Config 弹窗专用）
        HideableTitledPanel titled = new HideableTitledPanel(
                MpSqlVisualizerBundle.message("panel.scheme.label"),
                false,
                schemeRow,
                !SqlPreviewPanel.presetSectionCollapsed);
        p.presetFold = titled;
        return titled;
    }

    /**
     * R22 T-04：按 cache 当前态重建下拉模型与选中项（程序化填充，抑制切换语义）。
     * T-DevAcceptFix2 ①（20261006 第二轮验收）：名单与选中项均<b>按当前方法/SQL 键</b>取——
     * 每个方法只列自己的预设（{@code schemeNamesFor(key)}），激活态取该键的指针（{@code activeSchemeFor(key)}）。
     */
    public static void syncSchemeCombo(@NotNull SqlPreviewPanel p) {
        JComboBox<String> combo = p.schemeCombo;
        if (combo == null) return;
        p.schemeSyncingCombo = true;
        try {
            String key = historyCacheKey(p);
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            model.addElement(ParamHistoryCache.DEFAULT_SCHEME);
            for (String n : p.paramHistoryCache.schemeNamesFor(key)) model.addElement(n);
            String active = p.paramHistoryCache.activeSchemeFor(key);
            // T-PresetMethodBinding：同一方法改了 SQL 后，该方案在新键下没有快照（靠最近键指针回落），
            // 名单按键过滤会漏掉它，而激活态仍是它——补进名单，避免「下拉看不到却显示选中」。
            if (!ParamHistoryCache.DEFAULT_SCHEME.equals(active) && model.getIndexOf(active) < 0) {
                model.addElement(active);
            }
            combo.setModel(model);
            combo.setSelectedItem(active);
            p.lastShownActiveScheme = active;
        } finally {
            p.schemeSyncingCombo = false;
        }
    }

    // ------------------------------------------------------------------
    // O-95 T-PreviewEdtGuard S3：绑定数据源常驻指示行
    //（T-DevAcceptFix ①：迁顶栏「更多」按钮下方右对齐常驻行，原参数区预设行之下位置废止）
    // ------------------------------------------------------------------

    /**
     * S3 指示行数据源名（null = 未绑定/取不到）。测试缝优先——无 Database 插件的 headless
     * 用例经 {@code bindStateNameProvider} 构造绑定态；生产走 {@code DataSourceSelector.boundConsoleDataSourceName}
     * （T-ConsoleDbContext 同源链：共享/已备控制台 + isValid 先探 + DBN 取名，全程 fail-closed）。
     */
    @Nullable
    public static String boundDataSourceNameForDisplay(@NotNull SqlPreviewPanel p) {
        String provided = p.bindStateNameProvider != null ? p.bindStateNameProvider.get() : null;
        if (provided != null) return provided;
        try {
            return DataSourceSelector.boundConsoleDataSourceName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * O-96 T-PreviewBindAssociation S1：last-used 记忆回退取名（指示行第二优先级——无活控制台时，
     * 经 {@code DataSourceSelector.rememberedDataSourceName} 按 T-SI-02 同一口径（UUID→名唯一→歧义弃）
     * 解析在籍数据源名；只读，歧义不清记忆不弹窗）。project 为 null（无项目上下文）或解析异常 → null。
     */
    @Nullable
    public static String rememberedDataSourceNameForDisplay(@NotNull SqlPreviewPanel p) {
        Project proj = p.project;
        if (proj == null) return null;
        try {
            return DataSourceSelector.rememberedDataSourceName(proj);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * S3 指示行文案（O-96 S1 升级为三级）：活控制台绑定=「已绑定：&lt;源名&gt;」（panel.bindstate.bound，
     * 数据源名与 R33 结构化视图头部同源）；无活控制台但 last-used 记忆命中在籍数据源=
     * 「关联（上次使用）：&lt;源名&gt;」（panel.bindstate.associated——S2 证伪定案：EXPLAIN 直送通道
     * 走 selectAndExecute 不做记忆静默复用，与主执行链 prepareConsole 非严格同源，故记忆态
     * 文案区分，不冒称「已绑定」）；两者皆无=「未绑定数据源（在控制台执行一次查询即可绑定）」
     * （panel.bindstate.unbound，与 R33 回退通知同源文案族）。
     */
    @NotNull
    public static String bindStateText(@NotNull SqlPreviewPanel p) {
        String name = boundDataSourceNameForDisplay(p);
        if (name != null) return MpSqlVisualizerBundle.message("panel.bindstate.bound", name);
        String remembered = rememberedDataSourceNameForDisplay(p);
        if (remembered != null) return MpSqlVisualizerBundle.message("panel.bindstate.associated", remembered);
        return MpSqlVisualizerBundle.message("panel.bindstate.unbound");
    }

    /**
     * S3 指示行（灰字文本行，D2=A 定案——轻量形态禁自创图形）：contextHelp 灰字与同面板
     * 既有灰字形态一致（degradation 提示行同款 UIUtil.getContextHelpForeground）。
     *
     * <p>T-DevAcceptFix ①（20261006 验收）：容器改<b>顶栏「更多」按钮下方右对齐</b>常驻薄行
     * （FlowLayout.RIGHT，右缘边距与工具栏 8px 对齐）——与右侧内嵌控制台头部数据源显示相邻，
     * 不再挂在参数区预设行之下；init 一次构建，刷新仍走 {@link #refreshBindStateRow} 单点收口。</p>
     */
    @NotNull
    public static JComponent buildBindStateRow(@NotNull SqlPreviewPanel p) {
        JBLabel label = new JBLabel(bindStateText(p));
        label.setName(SqlPreviewPanel.BIND_STATE_LABEL_NAME);
        label.setForeground(UIUtil.getContextHelpForeground());
        p.bindStateLabel = label;
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        row.setName(SqlPreviewPanel.BIND_STATE_ROW_NAME);
        row.add(label);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    /**
     * S3 绑定态刷新：renderSql 尾部单点收口（循 R30 条件探镜「零监听器」口径——控制台上下文
     * 变更链〔attach/reuse/clearPrepared〕的既有 post-attach 渲染/清空路径都会走 renderSql，
     * 不另立监听器；纯文本读，write-safe 闸重投递亦无副作用）。
     */
    public static void refreshBindStateRow(@NotNull SqlPreviewPanel p) {
        JBLabel label = p.bindStateLabel;
        if (label == null) return;
        label.setText(bindStateText(p));
    }

    /**
     * R22 T-04：切换方案（下拉/对话框回调共用）——清态 → {@code ParamHistoryCache.switchScheme} →
     * {@link #recallFromHistory}。清场含可见字段文本与勾选框视觉态（新方案快照未覆盖的键不得残留旧值）；
     * 末尾无条件 renderSql：召回未命中时 SQL 也必须反映清空后的参数态。
     */
    public static void switchToScheme(@NotNull SqlPreviewPanel p, String name) {
        clearParamStateForSchemeSwitch(p);
        String key = historyCacheKey(p);
        if (key != null) p.paramHistoryCache.switchScheme(name, key);
        p.lastShownActiveScheme = name;
        recallFromHistory(p);
        SqlPreviewPanelSqlViewKt.renderSql(p);
    }

    /**
     * R22 T-04：方案切换清场（等价 clearStateForTest 的状态面 + 可见组件视觉态回默认）。
     * 顺序敏感：先清可见字段文本（DocumentListener 回写空值——onParamChanged 无条件 put，
     * 先清 map 会被复写），状态集清场放最后，保证 paramValues 干净、召回「已输入值优先」不误判。
     */
    public static void clearParamStateForSchemeSwitch(@NotNull SqlPreviewPanel p) {
        for (JBTextField f : p.paramFields) f.setText("");
        for (Pair<String, JBTextField> pair : p.xmlForEachValueFields) pair.getSecond().setText("");
        // 编程式 isSelected 不触发 ActionListener，enabledIds 不受影响（末尾直接清）
        for (JCheckBox cb : toggleBoxes(p)) cb.setSelected(false);
        for (JCheckBox cb : p.xmlForEachNullBoxes) cb.setSelected(false);
        p.enabledIds.clear();
        p.paramValues.clear();
        p.xmlForEachValues.clear();
        p.xmlForEachNulls.clear();
    }

    /** R22 T-04：管理方案对话框工厂（快照与回调在此接线——面板供给，对话框不知面板）。 */
    @NotNull
    public static ParamSchemeManagerDialog createSchemeManagerDialog(@NotNull SqlPreviewPanel p, Project proj) {
        return new ParamSchemeManagerDialog(
                proj,
                p.paramHistoryCache,
                () -> {
                    String key = historyCacheKey(p);
                    if (key == null) return null;
                    return new Pair<>(key, buildHistorySnapshot(p));
                },
                () -> onSchemesChangedFromDialog(p));
    }

    /** R22 T-04：管理方案对话框（MODELESS 单实例——可见时置前，不堆叠）。 */
    public static void openSchemeManagerDialog(@NotNull SqlPreviewPanel p) {
        Project proj = p.project;
        if (proj == null) return;
        if (p.schemeManagerDialog != null && p.schemeManagerDialog.isVisible()) {
            p.schemeManagerDialog.toFront();
            return;
        }
        ParamSchemeManagerDialog dialog = createSchemeManagerDialog(p, proj);
        p.schemeManagerDialog = dialog;
        dialog.show();
    }

    /** R22 T-04：对话框回调——同步下拉；激活方案被对话框变更（另存为自动切换）时整面重召回。 */
    public static void onSchemesChangedFromDialog(@NotNull SqlPreviewPanel p) {
        String active = p.paramHistoryCache.activeSchemeFor(historyCacheKey(p));
        boolean activeChanged = !active.equals(p.lastShownActiveScheme);
        syncSchemeCombo(p);
        if (activeChanged) switchToScheme(p, active);
    }

    /**
     * T-M23-04：批量列行——toggle 列渲染「复选框（列来源，tooltip 列名）+ 值输入框」，
     * 勾选变更重渲染（列名入 {@code enabledIds}）；fixed 列渲染「#来源 标签 + 值输入框」。
     * T-M26.3：fixed 列标签按统一列宽（labelWidth）构建。
     */
    @NotNull
    public static JBPanel<JBPanel<?>> buildBatchColumnRow(@NotNull SqlPreviewPanel p, BatchColumn col, boolean isToggle) {
        return buildBatchColumnRow(p, col, isToggle, 0);
    }

    /** Java 形态：Kotlin 默认参数 {@code labelWidth: Int = 0} → 两重载。 */
    @NotNull
    public static JBPanel<JBPanel<?>> buildBatchColumnRow(
            @NotNull SqlPreviewPanel p, BatchColumn col, boolean isToggle, int labelWidth) {
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6));
        if (isToggle) {
            String display = checkBoxText(p, col.getSource());
            JCheckBox checkBox = new JCheckBox(display, p.enabledIds.contains(col.getColumnName()));
            checkBox.setToolTipText(!display.equals(col.getSource()) ? col.getSource() : col.getColumnName());
            checkBox.putClientProperty(SqlPreviewPanel.PROP_TOGGLE_ID, col.getColumnName()); // R22 T-02：快照枚举/召回同步的 id 锚点
            checkBox.addActionListener(e -> {
                if (checkBox.isSelected()) p.enabledIds.add(col.getColumnName());
                else p.enabledIds.remove(col.getColumnName());
                SqlPreviewPanelSqlViewKt.renderSql(p);
            });
            p.batchCheckBoxes.add(checkBox);
            row.add(checkBox);
        } else {
            row.add(paramLabel(p, col.getSource(), labelWidth));
        }
        JBTextField field = new JBTextField(8);
        field.setToolTipText("#" + col.getSource());
        String existing = p.paramValues.get(col.getSource());
        field.setText(existing != null ? existing : "");
        field.putClientProperty(SqlPreviewPanel.PROP_FIELD_SOURCE, col.getSource()); // T-DevAcceptFix ⑤：召回回写的 source 锚点
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onParamChanged(p, col.getSource(), field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onParamChanged(p, col.getSource(), field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onParamChanged(p, col.getSource(), field.getText());
            }
        });
        p.paramFields.add(field);
        row.add(field);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    /**
     * T-M26.3：分区标题行（IDEA Group Header 惯例）——CENTER 为 TitledSeparator（短标题 + 分隔线），
     * EAST 为可选操作按钮组（全选/全不选回挂此处，T-M23.2 形态回归；T-M26.2 曾固定面板右上角，
     * 长文本截断后按钮不再被推出视口）。
     */
    @NotNull
    public static JBPanel<JBPanel<?>> sectionHeaderRow(@NotNull SqlPreviewPanel p, String title) {
        return sectionHeaderRow(p, title, List.of());
    }

    /** Java 形态：Kotlin 默认参数 {@code buttons: List<JButton> = emptyList()} → 两重载。 */
    @NotNull
    public static JBPanel<JBPanel<?>> sectionHeaderRow(
            @NotNull SqlPreviewPanel p, String title, List<JButton> buttons) {
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new BorderLayout());
        row.add(new TitledSeparator(title), BorderLayout.CENTER);
        if (!buttons.isEmpty()) {
            JBPanel<JBPanel<?>> btnPanel = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.RIGHT, 2, 2));
            for (JButton b : buttons) btnPanel.add(b);
            row.add(btnPanel, BorderLayout.EAST);
        }
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    /**
     * 批量「参数列」分区标题行（T-M23.2 回归，T-M26.3 TitledSeparator 化）：左侧分区标题 +
     * 右侧单个全选/全不选切换按钮（T-SelToggle；操作 {@code batchCheckBoxes}，与动态条件区按钮互不影响）。
     */
    @NotNull
    public static JBPanel<JBPanel<?>> batchSectionHeader(@NotNull SqlPreviewPanel p) {
        p.batchToggleSelectButton = compactButton(p, MpSqlVisualizerBundle.message("panel.condition.selectAll"),
                () -> setAllBatchColumns(p, !allSelected(p.batchCheckBoxes)));
        p.batchClearButton = compactButton(p, MpSqlVisualizerBundle.message("panel.param.clear"),
                () -> clearBatchValues(p));
        return sectionHeaderRow(p,
                MpSqlVisualizerBundle.message("panel.batch.columns"),
                List.of(p.batchToggleSelectButton, p.batchClearButton));
    }

    /**
     * 批量列全选/全不选（T-M23.2）：操作 {@code batchCheckBoxes} 与 {@code enabledIds}（列名作 id），
     * 同步复选框 UI 与 SQL 重渲染（全不选 → 空列清单形态，文档化边界）。
     * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
     */
    public static void setAllBatchColumns(@NotNull SqlPreviewPanel p, boolean enabled) {
        BatchModel model = p.current != null ? p.current.getBatchModel() : null;
        if (model == null) return;
        for (BatchColumn col : model.getToggleColumns()) {
            if (enabled) p.enabledIds.add(col.getColumnName());
            else p.enabledIds.remove(col.getColumnName());
        }
        for (JCheckBox cb : p.batchCheckBoxes) cb.setSelected(enabled);
        SqlPreviewPanelSqlViewKt.renderSql(p);
    }

    /**
     * 动态条件分区标题行（T-M26.3）：TitledSeparator「动态条件」+ 右侧单个全选/全不选切换按钮
     * （T-M23.2 形态回归，IDEA 组操作惯例，T-SelToggle 双按钮合一；XML 条件开关同样复用，见 T-M24-04）。
     */
    @NotNull
    public static JBPanel<JBPanel<?>> conditionSectionHeader(@NotNull SqlPreviewPanel p) {
        p.toggleSelectButton = compactButton(p, MpSqlVisualizerBundle.message("panel.condition.selectAll"),
                () -> setAllConditions(p, !allSelected(p.conditionCheckBoxes)));
        p.clearButton = compactButton(p, MpSqlVisualizerBundle.message("panel.param.clear"),
                () -> clearConditionValues(p));
        return sectionHeaderRow(p,
                MpSqlVisualizerBundle.message("panel.condition.section"),
                List.of(p.toggleSelectButton, p.clearButton));
    }

    /**
     * 左栏分区内的紧凑按钮（比工具栏按钮更小，避免挤压复选框行）。
     * T-DevAcceptFix2 ⑨（20261006 第三轮走查，用户对照工具栏按钮反馈「文字有些糊」）：
     * 字体改由 {@code buttonFont} 统一取——原 {@code deriveFont(10.5f)} 是<b>非整数磅值</b>，Java2D 走非整数
     * 度量/次像素 hinting 路径，字形边缘发虚；工具栏按钮（11f）则清晰。现与工具栏同源同值。
     */
    @NotNull
    public static JButton compactButton(@NotNull SqlPreviewPanel p, String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFont(SqlPreviewPanelExecutionKt.buttonFont(p));
        button.setMargin(JBUI.insets(0, 6, 0, 6));
        button.addActionListener(e -> action.run());
        return button;
    }

    /**
     * T-SelToggle：切换按钮文案判定——复选框非空且全部勾选 → 「全不选」（点击清空），
     * 其余一切态（空集/部分勾选）→ 「全选」（点击填满）。以复选框 UI 态为基准
     * （enabledIds 跨场景共享可能残留本场景外 id，与用户所见不一致）。
     */
    @NotNull
    public static String toggleLabel(@NotNull SqlPreviewPanel p, List<JCheckBox> boxes) {
        return MpSqlVisualizerBundle.message(
                !boxes.isEmpty() && allSelected(boxes) ? "panel.condition.selectNone" : "panel.condition.selectAll");
    }

    /**
     * T-SelToggle：切换按钮文案随勾选态刷新——挂在 {@code renderSql} 末尾单点收口
     * （三个勾选入口——切换按钮自身、批量列行/动态条件行/XML 条件行 listener——均以 renderSql 收尾）。
     * 文案未变化不 setText，避免每次重渲染触发标题行 FlowLayout 无谓重排。
     */
    public static void syncToggleSelectLabels(@NotNull SqlPreviewPanel p) {
        if (p.toggleSelectButton != null) {
            String label = toggleLabel(p, p.conditionCheckBoxes);
            if (!p.toggleSelectButton.getText().equals(label)) p.toggleSelectButton.setText(label);
        }
        if (p.batchToggleSelectButton != null) {
            String label = toggleLabel(p, p.batchCheckBoxes);
            if (!p.batchToggleSelectButton.getText().equals(label)) p.batchToggleSelectButton.setText(label);
        }
    }

    /**
     * 全选/全不选（M13.1）：批量设置动态条件勾选态（普通参数不受影响），
     * 同步复选框 UI 与 SQL 重渲染。T-M24-04：XML 预览的条件开关（id = test 表达式）一并处理。
     * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
     */
    public static void setAllConditions(@NotNull SqlPreviewPanel p, boolean enabled) {
        List<ConditionView> views = p.current != null && p.current.getPreviewModel() != null
                ? p.current.getPreviewModel().getConditionViews() : null;
        if (views == null) views = List.of();
        List<XmlConditionView> xmlViews = p.current != null && p.current.getXmlModel() != null
                ? p.current.getXmlModel().getConditionViews() : null;
        if (xmlViews == null) xmlViews = List.of();
        for (ConditionView v : views) {
            if (enabled) p.enabledIds.add(v.getId());
            else p.enabledIds.remove(v.getId());
        }
        for (XmlConditionView v : xmlViews) {
            if (enabled) p.enabledIds.add(v.id());
            else p.enabledIds.remove(v.id());
        }
        for (JCheckBox cb : p.conditionCheckBoxes) cb.setSelected(enabled);
        SqlPreviewPanelSqlViewKt.renderSql(p);
    }

    /** 动态条件行：复选框（勾选开关）+（有值来源时）值输入框（M19.5：between 等多值条件每个 source 一个输入框）。 */
    @NotNull
    public static JBPanel<JBPanel<?>> buildConditionRow(@NotNull SqlPreviewPanel p, ConditionView view) {
        String rawLabel = view.getLabel();
        String displayLabel = checkBoxText(p, rawLabel);
        JCheckBox checkBox = new JCheckBox(displayLabel, p.enabledIds.contains(view.getId()));
        // T-M26.3：标签被截断时 tooltip 展示表达式原文（否则保留 flagSource 提示）
        checkBox.setToolTipText(!displayLabel.equals(rawLabel) ? rawLabel : conditionTooltip(p, view));
        checkBox.putClientProperty(SqlPreviewPanel.PROP_TOGGLE_ID, view.getId()); // R22 T-02：快照枚举/召回同步的 id 锚点
        checkBox.addActionListener(e -> {
            if (checkBox.isSelected()) p.enabledIds.add(view.getId());
            else p.enabledIds.remove(view.getId());
            SqlPreviewPanelSqlViewKt.renderSql(p);
        });
        p.conditionCheckBoxes.add(checkBox);
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.add(checkBox);
        // M19.5：多值条件（between 等）每个 source 一个输入框；valueSources 为空时回退单值（旧路径兼容）
        List<String> sources = !view.getValueSources().isEmpty()
                ? view.getValueSources() : listOfNotNull(view.getValueSource());
        for (String source : sources) {
            JBTextField field = conditionValueField(p, view, source);
            row.add(field);
            p.paramFields.add(field);
            p.conditionValueFields.add(field);
        }
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    /** 动态条件值输入框：值变更联动勾选态与 SQL 重渲染（按 source 绑定，多值条件每个来源一个输入框）。 */
    @NotNull
    public static JBTextField conditionValueField(@NotNull SqlPreviewPanel p, ConditionView view, String source) {
        JBTextField field = new JBTextField(8);
        field.setToolTipText("#" + source);
        String existing = p.paramValues.get(source);
        field.setText(existing != null ? existing : "");
        field.putClientProperty(SqlPreviewPanel.PROP_FIELD_SOURCE, source); // T-DevAcceptFix ⑤：召回回写的 source 锚点
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onConditionValueChanged(p, view, source, field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onConditionValueChanged(p, view, source, field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onConditionValueChanged(p, view, source, field.getText());
            }
        });
        return field;
    }

    /** 清除按钮：清空动态条件分区所有参数值（不影响勾选态）。 */
    public static void clearConditionValues(@NotNull SqlPreviewPanel p) {
        // T-DevAcceptFix2 ⑧（20261006 第三轮走查，用户反馈「清除按钮失效」）：原实现只清
        // `previewModel.conditionViews` 的条件值字段——XML 预览**没有条件值字段**（XML 条件行只有
        // 复选框），其 `#{}` 参数落在「参数 / foreach」分区 ⇒ 在 XML/注解预览下这条路径
        // **无可清对象**（点按钮毫无反应）。现按「清空本预览全部参数输入」语义覆盖：
        // 模型预览的条件值来源 + 普通参数 + foreach 权威值。
        // 边界：批量列由批量分区自己的清除负责；勾选态不在此动（那是「全不选」的职责）；
        // `__frch_` 展开项随 foreach 权威值一并清（与参数行构建的过滤口径一致）。
        List<String> conditionSources = new ArrayList<>();
        if (p.current != null && p.current.getPreviewModel() != null) {
            for (ConditionView view : p.current.getPreviewModel().getConditionViews()) {
                conditionSources.addAll(!view.getValueSources().isEmpty()
                        ? view.getValueSources() : listOfNotNull(view.getValueSource()));
            }
        }
        List<String> ordinarySources = new ArrayList<>();
        if (p.current != null) {
            for (String s : p.current.getUnresolvedParams()) {
                if (!s.startsWith("__frch_") && !ordinarySources.contains(s)) ordinarySources.add(s);
            }
        }
        List<String> all = new ArrayList<>(conditionSources);
        all.addAll(ordinarySources);
        for (String source : all) {
            p.paramValues.put(source, "");
            syncParamFieldText(p, source, "");
        }
        // foreach 权威值（可见字段文本）；监听链同步权威 map，权威 map 再显式清一遍兜底
        for (Pair<String, JBTextField> pair : p.xmlForEachValueFields) pair.getSecond().setText("");
        p.xmlForEachValues.clear();
        SqlPreviewPanelSqlViewKt.renderSql(p);
        saveToHistory(p);
    }

    /** 清除按钮：清空批量参数列分区所有参数值（不影响勾选态）。 */
    public static void clearBatchValues(@NotNull SqlPreviewPanel p) {
        BatchModel model = p.current != null ? p.current.getBatchModel() : null;
        if (model == null) return;
        List<BatchColumn> cols = new ArrayList<>(model.getToggleColumns());
        cols.addAll(model.getFixedColumns());
        for (BatchColumn col : cols) {
            p.paramValues.put(col.getSource(), "");
            syncParamFieldText(p, col.getSource(), "");
        }
        SqlPreviewPanelSqlViewKt.renderSql(p);
        saveToHistory(p);
    }

    /** 动态条件复选框 tooltip：布尔条件源码提示（M13 起无判空语义提示，复选框纯手动）。 */
    @NotNull
    public static String conditionTooltip(@NotNull SqlPreviewPanel p, ConditionView view) {
        String flagSource = view.getFlagSource();
        return flagSource != null ? MpSqlVisualizerBundle.message("panel.condition.flag", flagSource) : "";
    }

    /**
     * 动态条件值变更（M13：复选框纯手动）：仅更新按 source 绑定的参数值并重渲染，
     * 勾选态只由用户操作决定（值空保留 {@code ?}，不自动取消勾选）。
     * T-Perf-04：击键路径——重渲染防抖（同 {@link #onParamChanged}）。
     */
    public static void onConditionValueChanged(
            @NotNull SqlPreviewPanel p, ConditionView view, String source, String text) {
        p.paramValues.put(source, text);
        p.scheduleRender(() -> SqlPreviewPanelSqlViewKt.renderSql(p));
        saveToHistory(p);
    }

    /**
     * T-M24-04：XML 动态条件行——复选框（label = test 表达式，id 进 {@code enabledIds}），
     * 与 Wrapper 动态条件交互一致（全选/全不选按钮共用）。
     */
    @NotNull
    public static JBPanel<JBPanel<?>> buildXmlConditionRow(@NotNull SqlPreviewPanel p, XmlConditionView view) {
        String rawLabel = view.label();
        String displayLabel = checkBoxText(p, rawLabel);
        JCheckBox checkBox = new JCheckBox(displayLabel, p.enabledIds.contains(view.id()));
        // T-M26.3：标签被截断时 tooltip 展示 test 表达式原文（否则保留条件提示）
        checkBox.setToolTipText(!displayLabel.equals(rawLabel)
                ? rawLabel : MpSqlVisualizerBundle.message("panel.xml.condition"));
        checkBox.putClientProperty(SqlPreviewPanel.PROP_TOGGLE_ID, view.id()); // R22 T-02：快照枚举/召回同步的 id 锚点（XML 条件行）
        checkBox.addActionListener(e -> {
            if (checkBox.isSelected()) p.enabledIds.add(view.id());
            else p.enabledIds.remove(view.id());
            SqlPreviewPanelSqlViewKt.renderSql(p);
        });
        p.conditionCheckBoxes.add(checkBox);
        JBLabel outcomeLabel = new JBLabel();
        outcomeLabel.setForeground(UIUtil.getContextHelpForeground());
        p.xmlConditionOutcomeLabels.put(view.id(), outcomeLabel);
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.add(checkBox);
        // T-ExprAstPrecheck T-EP-03：表达式超出支持面时补淡色标注（提示，不改展开行为）
        JBLabel annotation = xmlConditionAnnotation(p, view);
        if (annotation != null) row.add(annotation);
        // R30 探镜：本轮求值结果标注（真/假/未求值；占位空串，renderSql 首轮刷新）
        row.add(outcomeLabel);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    /**
     * R30 条件求值探镜：渲染后按本轮判定结果刷新各条件行尾的三态标注（真/假/未求值）。
     * 只读探针——读 {@code XmlPreviewModel.conditionOutcomes} 判定产物，不参与渲染文本；零监听器
     * （刷新挂在 renderSql 单点，勾选/参数/foreach 变更后的每轮渲染自动跟上）。
     */
    public static void syncXmlConditionOutcomeLabels(
            @NotNull SqlPreviewPanel p, XmlPreviewModel xml, RenderedXmlSql rendered) {
        if (p.xmlConditionOutcomeLabels.isEmpty()) return;
        Map<String, ConditionOutcome> outcomes = xml.conditionOutcomes(rendered);
        for (Map.Entry<String, JBLabel> entry : p.xmlConditionOutcomeLabels.entrySet()) {
            ConditionOutcome outcome = outcomes.get(entry.getKey());
            if (outcome == null) continue;
            String text;
            switch (outcome) {
                case TRUE:
                    text = MpSqlVisualizerBundle.message("panel.xml.outcome.true");
                    break;
                case FALSE:
                    text = MpSqlVisualizerBundle.message("panel.xml.outcome.false");
                    break;
                case UNEVALUATED:
                    text = MpSqlVisualizerBundle.message("panel.xml.outcome.unevaluated");
                    break;
                default:
                    continue;
            }
            JBLabel label = entry.getValue();
            label.setText(text);
            label.setToolTipText(MpSqlVisualizerBundle.message("panel.xml.outcome.tooltip", text));
        }
    }

    /**
     * T-ExprAstPrecheck T-EP-03：条件行的支持面标注（SUPPORTED 返回 null 不占位）。
     * 用上下文提示色（淡）以与条件标签区分；理由进 tooltip，行内只留档位短句。
     */
    @Nullable
    public static JBLabel xmlConditionAnnotation(@NotNull SqlPreviewPanel p, XmlConditionView view) {
        String text;
        switch (view.support()) {
            case SUPPORTED:
                return null;
            case UNSUPPORTED_SYNTAX:
                text = MpSqlVisualizerBundle.message("panel.xml.expr.unsupported");
                break;
            case EVAL_FALLBACK:
                text = MpSqlVisualizerBundle.message("panel.xml.expr.fallback");
                break;
            default:
                return null;
        }
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setToolTipText(xmlConditionAnnotationTooltip(p, view));
        return label;
    }

    /** 标注 tooltip：按理由码取文案，附细节（方法名 / 静态字段引用 / 解析错误消息）。 */
    @NotNull
    public static String xmlConditionAnnotationTooltip(@NotNull SqlPreviewPanel p, XmlConditionView view) {
        String detail = view.supportDetail() != null ? view.supportDetail() : "";
        ExprReason reason = view.supportReason();
        if (reason == null) return view.label();
        switch (reason) {
            case SYNTAX:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.syntax", detail);
            case UNKNOWN_METHOD:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.unknownMethod", detail);
            case NARROW_CARRIER:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.narrowCarrier", detail);
            case STATIC_FIELD:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.staticField", detail);
            default:
                return view.label();
        }
    }

    /** T-M25.4：foreach 行——<b>集合表达式标签 + 集合值输入框</b>（逗号分隔，如 {@code 2,3} → 展开两项并回填）+ null 复选框（T-M26.5：标签即集合表达式，分区标题已含「foreach 集合」语义）。 */
    @NotNull
    public static JBPanel<JBPanel<?>> buildXmlForEachRow(
            @NotNull SqlPreviewPanel p, XmlForEachView view, int labelWidth) {
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JBLabel label = columnLabel(p, view.collectionExpr(), labelWidth);
        label.setBorder(JBUI.Borders.empty(0, 8, 0, 0));
        row.add(label);
        JBTextField valuesField = new JBTextField(8);
        valuesField.setToolTipText("集合值（逗号分隔，如 2,3；留空 = 默认展开 "
                + XmlPreviewModel.DEFAULT_FOREACH_SIZE + " 项）");
        valuesField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onXmlForEachValuesChanged(p, view, valuesField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onXmlForEachValuesChanged(p, view, valuesField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onXmlForEachValuesChanged(p, view, valuesField.getText());
            }
        });
        row.add(valuesField);
        p.paramFields.add(valuesField);
        p.xmlForEachValueFields.add(new Pair<>(view.collectionExpr(), valuesField)); // R22 T-03：召回时按 expr 同步字段文本
        JCheckBox nullBox = new JCheckBox(MpSqlVisualizerBundle.message("panel.xml.foreach.null"),
                p.xmlForEachNulls.contains(view.collectionExpr()));
        nullBox.putClientProperty(SqlPreviewPanel.PROP_FOREACH_NULL, view.collectionExpr()); // R22 T-02：召回同步的 expr 锚点
        nullBox.addActionListener(e -> {
            if (nullBox.isSelected()) p.xmlForEachNulls.add(view.collectionExpr());
            else p.xmlForEachNulls.remove(view.collectionExpr());
            SqlPreviewPanelSqlViewKt.renderSql(p);
        });
        p.xmlForEachNullBoxes.add(nullBox); // R22 T-02：召回视觉同步登记
        row.add(nullBox);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    /**
     * T-M25.4：foreach 集合值变更——逗号分隔值解析为展开项并<b>自动回填</b>：
     * {@code 2,3} → 展开 2 项 + {@code IN ( 2 , 3 )}；留空 → 恢复默认展开次数并清空该 foreach 的参数值（{@code ?} 保持）。
     *
     * <p>O-78 T-ForeachItemRender：值以 collectionExpr 为键存入 {@code xmlForEachValues}，播种收进
     * {@code renderSql}（{@link #syncForEachItemValues}）——原「渲染后同步读 xmlMappings 取键」在 write-unsafe
     * （防抖 Timer）上下文里 renderSql 被重投递、键清单滞后一拍，值按旧键播种丢尾巴
     * （真机形态 {@code IN ('1','2','4',?)}）；状态化后渲染延迟与否都按最新状态出图。</p>
     *
     * <p>T-Perf-04：击键路径防抖（停顿后以最终文本执行一次）语义不变。</p>
     */
    public static void onXmlForEachValuesChanged(@NotNull SqlPreviewPanel p, XmlForEachView view, String text) {
        p.scheduleRender(() -> applyXmlForEachValues(p, view, text));
    }

    /** foreach 值应用（由防抖 Timer 调度）：存状态 + 设展开次数 + 单次渲染（播种在渲染帧内）。 */
    public static void applyXmlForEachValues(@NotNull SqlPreviewPanel p, XmlForEachView view, String text) {
        List<String> values = splitTrimmed(text);
        p.xmlForEachValues.put(view.collectionExpr(), values);
        p.xmlForEachCounts.put(view.collectionExpr(),
                values.isEmpty() ? XmlPreviewModel.DEFAULT_FOREACH_SIZE : values.size());
        SqlPreviewPanelSqlViewKt.renderSql(p);
        saveToHistory(p); // R22 T-03：foreach 权威态变更触发历史保存（原缺口③——权威态仅间接入库且不触发）
    }

    /**
     * O-78：foreach 值播种——在 renderSql 帧内（{@code xmlMappings} 刚按当前状态刷新，恒新鲜）按
     * collectionExpr → view.item 反查前缀回填/清除 {@code __frch_<item>_*} 参数值；多出键清除语义与
     * 原实现一致（留空 → {@code ?} 保持）。
     */
    public static void syncForEachItemValues(@NotNull SqlPreviewPanel p, XmlPreviewModel xml) {
        if (p.xmlForEachValues.isEmpty()) return;
        for (Map.Entry<String, List<String>> entry : p.xmlForEachValues.entrySet()) {
            String expr = entry.getKey();
            List<String> values = entry.getValue();
            String item = null;
            for (XmlForEachView v : xml.getForEachViews()) {
                if (v.collectionExpr().equals(expr)) {
                    item = v.item();
                    break;
                }
            }
            if (item == null) continue;
            List<String> keys = new ArrayList<>();
            for (cn.song.mybatisplus.visualizer.xml.ParameterMapping m : p.xmlMappings) {
                if (m.property().startsWith("__frch_" + item + "_")) keys.add(m.property());
            }
            if (values.isEmpty()) {
                for (String key : keys) p.paramValues.remove(key);
            } else {
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    if (i < values.size()) p.paramValues.put(key, values.get(i));
                    else p.paramValues.remove(key);
                }
            }
        }
    }

    /** 普通参数行：`#<source>` 标签 + 值输入框（T-PT-02：按签名类型类别挂占位提示与行尾校验警示）。 */
    @NotNull
    public static JBPanel<JBPanel<?>> buildParamRow(@NotNull SqlPreviewPanel p, String source, int labelWidth) {
        ParamKind kind = paramKindOf(p, source);
        // T-PT-02：行尾淡色警示标（校验是提示不是闸门——值照原样渲染，渲染链 renderValue 行为不动）
        JBLabel warnLabel = new JBLabel("");
        warnLabel.setForeground(SqlPreviewPanel.PARAM_WARN_FOREGROUND);
        warnLabel.setVisible(false);
        // T-HibDualCapture：8 列放不下 16 字符日期值（视口滚到尾部被当成「值截断」），下限放宽到 16 列
        JBTextField field = new JBTextField(16);
        String existing = p.paramValues.get(source);
        field.setText(existing != null ? existing : "");
        field.putClientProperty(SqlPreviewPanel.PROP_FIELD_SOURCE, source); // T-DevAcceptFix ⑤：召回回写的 source 锚点
        field.getEmptyText().setText(paramEmptyText(p, source, kind));
        // 长值超框宽时光标钉回首字符，视口停在头部；完整值以 tooltip 兜底
        if (!field.getText().isEmpty()) field.setCaretPosition(0);
        field.setToolTipText(paramTooltip(p, source, field.getText()));
        updateParamWarning(p, warnLabel, kind, field.getText());
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onParamFieldText(p, source, field, warnLabel, kind);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onParamFieldText(p, source, field, warnLabel, kind);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onParamFieldText(p, source, field, warnLabel, kind);
            }
        });
        p.paramFields.add(field);
        p.paramWarnLabels.add(warnLabel);
        // T-M19.4-01：FlowLayout 间距 (4,2) → (8,6)——参数名较长时视觉"贴太近"（用户实测）
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6));
        row.add(paramLabel(p, source, labelWidth));
        row.add(field);
        row.add(warnLabel);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    // ------------------------------------------------------------------
    // T-PT-02：按签名类型类别的占位提示与校验（提示不是闸门——渲染链零改动）
    // ------------------------------------------------------------------

    /** 参数 source 的类型类别（无类型信息 → UNKNOWN，行为=升级前）。 */
    @NotNull
    public static ParamKind paramKindOf(@NotNull SqlPreviewPanel p, String source) {
        ParamKind kind = p.current != null && p.current.getXmlModel() != null
                ? p.current.getXmlModel().kindFor(source) : null;
        return kind != null ? kind : ParamKind.UNKNOWN;
    }

    /** 占位提示（emptyText）：有类型信息按类别；UNKNOWN 走参数命名启发式（zMyBatis 同款，仅提示）。 */
    @NotNull
    public static String paramEmptyText(@NotNull SqlPreviewPanel p, String source, ParamKind kind) {
        switch (kind) {
            case NUMBER:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.number");
            case TEMPORAL:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.temporal");
            case COLLECTION:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.collection");
            case OBJECT:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.object");
            case TEXT:
                return "";
            case UNKNOWN:
                return looksLikeCollectionName(p, source)
                        ? MpSqlVisualizerBundle.message("panel.param.emptyText.collection")
                        : "";
            default:
                return "";
        }
    }

    /**
     * 集合命名启发式（T-PT-02，zMyBatis ParameterInputDialog 同款）：参数名以 `s` 结尾
     * （排除 status/address 等单数词）或含 list/ids/array/collection → 按数组提示。
     * O-111 追加（用户 demo 实测 locationStatus 误配集合占位）：排除词改<b>后缀匹配</b>——
     * 复合驼峰词（locationStatus/orderStatus/userAddress）以单数词结尾同样不是集合。
     */
    public static boolean looksLikeCollectionName(@NotNull SqlPreviewPanel p, String source) {
        int idx = source.lastIndexOf('.');
        String name = (idx >= 0 ? source.substring(idx + 1) : source).toLowerCase();
        if (SqlPreviewPanel.COLLECTION_NAME_EXCEPTIONS.contains(name)) return false;
        for (String ex : SqlPreviewPanel.COLLECTION_NAME_EXCEPTIONS) {
            if (name.endsWith(ex)) return false;
        }
        if (name.contains("list") || name.contains("ids") || name.contains("array") || name.contains("collection")) {
            return true;
        }
        return name.endsWith("s") && !name.endsWith("ss");
    }

    /** 行尾校验警示（NUMBER 非数字 / TEMPORAL 非可解析日期）：淡色标记 + tooltip 说明，不阻塞渲染。 */
    public static void updateParamWarning(
            @NotNull SqlPreviewPanel p, JBLabel label, ParamKind kind, @Nullable String value) {
        String message = paramWarningOf(p, kind, value);
        label.setText(message == null ? "" : SqlPreviewPanel.PARAM_WARN_MARK);
        label.setToolTipText(message);
        label.setVisible(message != null);
    }

    @Nullable
    public static String paramWarningOf(@NotNull SqlPreviewPanel p, ParamKind kind, @Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        switch (kind) {
            case NUMBER:
                return SqlPreviewPanel.NUMBER_REGEX.matcher(trimmed).matches()
                        ? null : MpSqlVisualizerBundle.message("panel.param.warn.number");
            case TEMPORAL:
                return SqlPreviewPanel.DATE_REGEX.matcher(trimmed).matches()
                        ? null : MpSqlVisualizerBundle.message("panel.param.warn.temporal");
            default:
                return null;
        }
    }

    /**
     * 参数行标签（T-M19.4-01 自适应 + T-M26.3 统一列宽）：`#source` 超列宽时中段省略显示、
     * tooltip 全名（IDEA 长文本惯例，用户 T-M19.4-01"长参数名显示不全"诉求由 tooltip 兜底）。
     */
    @NotNull
    public static JBLabel paramLabel(@NotNull SqlPreviewPanel p, String source, int columnWidth) {
        return columnLabel(p, "#" + source, columnWidth);
    }

    /**
     * T-M26.3：两列行标签——统一列宽（输入框左缘对齐，IDEA 表单惯例），
     * 文本超列宽时中段省略（保留尾部）+ tooltip 全名。
     */
    @NotNull
    public static JBLabel columnLabel(@NotNull SqlPreviewPanel p, String fullText, int columnWidth) {
        JBLabel label = new JBLabel(fullText);
        FontMetrics metrics = label.getFontMetrics(UIUtil.getLabelFont());
        if (metrics.stringWidth(fullText) > columnWidth) {
            label.setToolTipText(fullText);
            label.setText(ellipsize(p, fullText, metrics, columnWidth));
        }
        label.setPreferredSize(new Dimension(columnWidth, label.getPreferredSize().height));
        return label;
    }

    /**
     * T-M26.3：两列行标签列宽——按最长可见标签自适应，<b>上限跟随左栏视口宽度</b>
     * （标签列 + 输入框约 90px + 间距边距 38px 不超出视口，保证标题行右侧按钮不被推出；
     * 默认 240 面板 → 上限约 109，宽面板放宽至 260 封顶；未布局/测试环境取 260）。
     */
    public static int labelColumnWidth(@NotNull SqlPreviewPanel p, List<String> texts) {
        if (texts.isEmpty()) return 140;
        FontMetrics metrics = p.getFontMetrics(UIUtil.getLabelFont());
        int viewport = p.paramScroll.getViewport().getWidth();
        int maxWidth = viewport <= 0 ? 260 : Math.max(80, Math.min(viewport - 128, 260));
        int maxTextWidth = 0;
        for (String t : texts) maxTextWidth = Math.max(maxTextWidth, metrics.stringWidth(t));
        return Math.max(80, Math.min(maxTextWidth, maxWidth));
    }

    /**
     * T-M26.3：长文本中段省略——保留尾部（bind 字段名/参数名尾部有意义），超宽时显示 `头…尾`。
     *
     * <p>度量以 {@code (String) -> Int} 注入（{@code ellipsizeMiddle} 纯函数），使 meta 行的宽度预算组装
     * （{@code metaLineWithinBudget}）可脱 FontMetrics 单测。</p>
     */
    @NotNull
    public static String ellipsize(@NotNull SqlPreviewPanel p, String text, FontMetrics metrics, int maxWidth) {
        return SqlPreviewPanelTextKt.ellipsizeMiddle(text, metrics::stringWidth, maxWidth);
    }

    /**
     * T-M26.3：复选框标签截断上限——跟随视口宽度（文本 + 图标约 20px + 边距 36px 不超出视口，
     * 保证标题行右侧按钮不被推出；默认 240 面板 → 约 181；未布局/测试环境取 280）。
     */
    public static int checkBoxMaxWidth(@NotNull SqlPreviewPanel p) {
        int viewport = p.paramScroll.getViewport().getWidth();
        return viewport <= 0 ? 280 : Math.max(100, Math.min(viewport - 56, 280));
    }

    /** 复选框字体（与渲染一致，截断度量用——UIUtil.getLabelFont 与复选框字体在 headless 下可能不一致）。 */
    @NotNull
    public static String checkBoxText(@NotNull SqlPreviewPanel p, String raw) {
        return checkBoxText(p, raw, checkBoxMaxWidth(p));
    }

    /** Java 形态：Kotlin 默认参数 {@code maxWidth: Int = checkBoxMaxWidth()} → 两重载。 */
    @NotNull
    public static String checkBoxText(@NotNull SqlPreviewPanel p, String raw, int maxWidth) {
        FontMetrics metrics = p.getFontMetrics(p.checkBoxFont);
        return metrics.stringWidth(raw) > maxWidth ? ellipsize(p, raw, metrics, maxWidth) : raw;
    }

    /**
     * 普通参数值变更：更新回填表并重渲染 SQL（T-Perf-04：重渲染防抖——逐键全量重生成+格式化
     * +重分词在大 SQL 下构成卡顿；paramValues 即时更新，渲染合并到停顿后一次）。
     */
    public static void onParamChanged(@NotNull SqlPreviewPanel p, String source, String value) {
        p.paramValues.put(source, value);
        p.scheduleRender(() -> SqlPreviewPanelSqlViewKt.renderSql(p));
        saveToHistory(p);
    }

    /**
     * T-HibDualCapture：参数框文本变更（含导入回填等程序化 setText 触发的文档事件）——
     * tooltip 随值同步，其余走既有回填链路。
     * T-PT-02：行尾校验警示随值同步（默认参数无警示标，调用方为 buildParamRow）。
     */
    public static void onParamFieldText(
            @NotNull SqlPreviewPanel p, String source, JBTextField field,
            @Nullable JBLabel warnLabel, ParamKind kind) {
        field.setToolTipText(paramTooltip(p, source, field.getText()));
        if (warnLabel != null) updateParamWarning(p, warnLabel, kind, field.getText());
        onParamChanged(p, source, field.getText());
    }

    /** Java 形态：Kotlin 默认参数 {@code warnLabel/kind} → 重载。 */
    public static void onParamFieldText(@NotNull SqlPreviewPanel p, String source, JBTextField field) {
        onParamFieldText(p, source, field, null, ParamKind.UNKNOWN);
    }

    /** T-HibDualCapture：参数框 tooltip——`#source = 完整值`，框宽放不下的长值以此兜底可见。 */
    @NotNull
    public static String paramTooltip(@NotNull SqlPreviewPanel p, String source, @Nullable String value) {
        return "#" + source + (value == null || value.isEmpty() ? "" : " = " + value);
    }

    // ------------------------------------------------------------------
    // P-05：参数批量导入 / 历史召回
    // ------------------------------------------------------------------

    /**
     * P-05.1：弹参数导入手动输入弹窗（工具栏按钮触发）。
     * 默认回显当前 SQL 参数（JSON 格式），用户可在此基础上修改后导入。
     * 用户粘贴/编辑文本后走 {@code ParamImportParser.parse} → {@link #importParams} 回填。
     */
    public static void importParamsFromClipboard(@NotNull SqlPreviewPanel p) {
        Project proj = p.project;
        if (proj == null) return;
        // 有参数时预填当前 paramValues（JSON 格式），无参数时弹空框 + 占位示例
        String preFill = p.paramValues.isEmpty() ? null : serializeParamsToJson(p);
        new ParamImportDialog(proj, preFill,
                input -> importParams(p, ParamImportParser.INSTANCE.parse(input))).show();
    }

    /**
     * P-05.1：将当前 paramValues 序列化为 JSON 字符串（自实现，不依赖 Jackson）。
     * 过滤空值参数（未填写的不显示），key 按插入顺序排列。
     */
    @NotNull
    public static String serializeParamsToJson(@NotNull SqlPreviewPanel p) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : p.paramValues.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String escaped = e.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
            entries.add("  \"" + e.getKey() + "\": \"" + escaped + "\"");
        }
        String joined = String.join(",\n", entries);
        return joined.isEmpty() ? "" : "{\n" + joined + "\n}";
    }

    /**
     * P-05：按 source 模糊匹配导入参数值并回填。
     *
     * <p>模糊匹配规则：key ≡ source 或 key ≡ `#source`。
     * 回填 paramValues + 更新对应输入框文本 + 触发 renderSql()。
     * 未匹配项计入通知。</p>
     *
     * @param params 导入的参数映射（key → value）
     */
    public static void importParams(@NotNull SqlPreviewPanel p, Map<String, String> params) {
        if (params.isEmpty()) {
            showImportResult(p, 0, 0, List.of(), true);
            return;
        }
        Set<String> allSources = buildAllSources(p);
        int matched = 0;
        List<String> unmatchedKeys = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            String source = resolveSource(p, key, allSources);
            if (source != null) {
                p.paramValues.put(source, value);
                syncParamFieldText(p, source, value);
                matched++;
            } else {
                unmatchedKeys.add(key);
            }
        }
        SqlPreviewPanelSqlViewKt.renderSql(p);
        showImportResult(p, matched, unmatchedKeys.size(), unmatchedKeys, false);
    }

    /** P-05：构建当前面板所有参数 source 集合（用于模糊匹配）。 */
    @NotNull
    public static Set<String> buildAllSources(@NotNull SqlPreviewPanel p) {
        PreviewResult result = p.current;
        if (result == null) return Set.of();
        Set<String> sources = new LinkedHashSet<>();
        sources.addAll(result.getUnresolvedParams());
        if (result.getPreviewModel() != null && result.getPreviewModel().getConditionViews() != null) {
            for (ConditionView view : result.getPreviewModel().getConditionViews()) {
                List<String> vs = !view.getValueSources().isEmpty()
                        ? view.getValueSources() : listOfNotNull(view.getValueSource());
                sources.addAll(vs);
            }
        }
        if (result.getBatchModel() != null) {
            if (result.getBatchModel().getToggleColumns() != null) {
                for (BatchColumn c : result.getBatchModel().getToggleColumns()) sources.add(c.getSource());
            }
            if (result.getBatchModel().getFixedColumns() != null) {
                for (BatchColumn c : result.getBatchModel().getFixedColumns()) sources.add(c.getSource());
            }
        }
        if (result.getXmlModel() != null && result.getXmlModel().getForEachViews() != null) {
            for (XmlForEachView v : result.getXmlModel().getForEachViews()) sources.add(v.collectionExpr());
        }
        return sources;
    }

    /**
     * P-05.1：模糊匹配 source（三级 fallback）。
     *
     * <ol>
     *   <li>精确命中（key ≡ source）</li>
     *   <li>去 `#` 前缀精确命中（key ≡ `#source`）</li>
     *   <li>驼峰/下划线互转命中（`userName` ≡ `user_name`）</li>
     * </ol>
     *
     * <p>精确匹配优先，互转匹配兜底，避免风格差异导致漏匹配。</p>
     */
    @Nullable
    public static String resolveSource(@NotNull SqlPreviewPanel p, String key, Set<String> sources) {
        // 1. 精确命中
        if (sources.contains(key)) return key;
        // 2. 去 # 前缀精确命中
        String stripped = key.startsWith("#") ? key.substring(1) : key;
        if (sources.contains(stripped)) return stripped;
        // 3. 驼峰/下划线互转命中
        String snake = CaseFormatUtil.camelToSnake(stripped);
        if (sources.contains(snake)) return snake;
        String camel = CaseFormatUtil.snakeToCamel(stripped);
        if (sources.contains(camel)) return camel;
        return null;
    }

    /** P-05：同步参数输入框文本（导入回填时更新 UI 显示）。 */
    public static void syncParamFieldText(@NotNull SqlPreviewPanel p, String source, String value) {
        // T-DevAcceptFix ⑤（20261006 验收）：按 source 锚点显式映射——原实现以 paramValues 插入序
        // 对位 paramFields 行序（currentParamFieldIndex），方案切换后 map 重填序=另一方案快照保存序，
        // 与其后的面板行构建序漂移时回写错位（值写进别家输入框）。锚点挂在输入框 clientProperty 上，
        // 与任何遍历序解耦。反向验证读数：退回插入序对位，跨方案键序漂移用例即 ComparisonFailure 红。
        for (JBTextField field : p.paramFields) {
            if (source.equals(field.getClientProperty(SqlPreviewPanel.PROP_FIELD_SOURCE))) {
                field.setText(value);
                return;
            }
        }
    }

    /**
     * P-05：显示导入结果通知。
     *
     * @param matched          匹配数
     * @param unmatched        未匹配数
     * @param unmatchedKeys    未匹配的 key 列表
     * @param isEmptyClipboard 是否剪贴板无有效内容（true=空内容提示，false=匹配结果提示）
     */
    public static void showImportResult(
            @NotNull SqlPreviewPanel p, int matched, int unmatched, List<String> unmatchedKeys, boolean isEmptyClipboard) {
        // T-Perf-20：空剪贴板提示是**纯行内状态更新**（不弹任何窗），与下面的通知分支性质不同，
        // 故前置到测试模式闸门之前——闸门只为避开 Messages/TestDialog 拦截器，行内提示无此依赖，
        // 前置后该提示态可被 headless 测试驱动（T-SourceJumpFunnel P6 的 tooltip 契约据此断言）。
        if (isEmptyClipboard) {
            showEmptyClipboardHint(p);
            return;
        }
        // 测试环境跳过弹窗（Messages 在 test mode 下触发 TestDialog 拦截器抛异常）
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        String title = MpSqlVisualizerBundle.message("panel.param.import.result.title");
        Project proj = p.project;
        if (proj == null) return;
        if (unmatched == 0) {
            // T-UiNative T-UN-02：导入结果改轻通知（原模态弹窗——纯告知无需打断）
            try {
                new Notification(
                        SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                        title,
                        MpSqlVisualizerBundle.message("panel.param.import.result.success", matched),
                        NotificationType.INFORMATION).notify(proj);
            } catch (Throwable ignored) {
            }
        } else {
            // T-UiNative T-UN-02：部分匹配改轻通知（原模态警示框；WARNING 保留原警示图标语义）
            try {
                new Notification(
                        SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                        title,
                        MpSqlVisualizerBundle.message("panel.param.import.result.partial",
                                matched, unmatched, String.join(", ", unmatchedKeys)),
                        NotificationType.WARNING).notify(proj);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * T-Perf-20：空剪贴板（未识别到有效参数）的<b>行内</b>提示——元信息行红字 + 错误复制按钮可见，
     * 不弹模态框（toast 语义：下次 {@code updateMeta} 覆盖）。
     *
     * <p>T-SourceJumpFunnel P6 修复：提示态 tooltip 由「覆写」改「拼接」——{@code updateMeta} 写入的
     * {@code metaTooltipBase}（T-StackHoverUi 起只含 meta/警示/perf，完整调用链在悬停弹层）不能被提示
     * 覆写丢掉——故提示态按「提示 + 换行 + 基线」拼接。</p>
     *
     * <p>P10 修复（2026-09-20 真机走查发现）：提示此前<b>只写</b> {@code metaLabel.text}，不进 {@code metaLineHead} /
     * {@code metaLineText}。而 {@code errorCopyButton.isVisible = true} 会改变工具栏理想宽 → topRow 重排 →
     * 紧接着的 {@code applyMetaTruncation}（预算变了故不短路）用旧的 {@code metaLineText} 把提示<b>当场覆盖</b>——
     * 真机走查实测「提示态根本看不到」（红字与复制按钮都没出现），即本项此前只在单测里成立。
     * 现把提示写进同一套分段缓存并复位 {@code lastMetaBudget}，可见行统一由 {@code applyMetaTruncation} 收口渲染
     * （含宽度预算与 perf 段），提示因此参与布局且不再被覆盖；`toast 语义`不变——下次 {@code updateMeta}
     * 重写分段缓存即覆盖。</p>
     */
    public static void showEmptyClipboardHint(@NotNull SqlPreviewPanel p) {
        String hint = MpSqlVisualizerBundle.message("panel.param.import.result.empty");
        p.currentWarning = hint;
        String hintLine = "⚠ " + hint;
        p.metaLineHead = hintLine;
        p.metaLineDigest = "";
        p.metaLineText = hintLine;
        p.lastMetaBudget = -1;
        p.metaLabel.setToolTipText(p.metaTooltipBase.isEmpty() ? hint : hint + "\n" + p.metaTooltipBase);
        p.metaLabel.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        if (p.errorCopyButton != null) p.errorCopyButton.setVisible(true);
        SqlPreviewPanelResultViewKt.applyMetaTruncation(p);
    }

    /** P-05：计算当前预览的缓存键（R22 T-01 起：无表名以 SQL 文本规范化指纹为键，恒非 null）。 */
    @Nullable
    public static String historyCacheKey(@NotNull SqlPreviewPanel p) {
        PreviewResult result = p.current;
        if (result == null) return null;
        String tableName = result.getTableName();
        // 缺陷②修复：优先用「注入前」稳定文本当指纹种子——逻辑删除/多租户等拦截器模拟会改写
        // result.sql，直接拿它算键会让开关一切切换就召回 miss（已回填参数退回占位符）。
        String rawSql = result.getHistoryFingerprintSeed() != null ? result.getHistoryFingerprintSeed() : result.getSql();
        // T-PresetMethodScope：追加声明作用域——同一段 SQL 写在两个方法里不再共用预设与参数历史；
        // 无方法上下文（日志/历史双击）保持旧键。
        String scope = result.getOriginScopeKey();
        return p.paramHistoryCache.computeKey(tableName, rawSql, scope == null || scope.isEmpty() ? null : scope);
    }

    /**
     * T-PresetMethodBinding：当前预览实际存在的参数来源（回落白名单——只回填这些行）。
     *
     * <p>为什么不用 {@code paramValues.keys}：方案切换路径先 {@code clearParamStateForSchemeSwitch} 清场，
     * 到召回时 paramValues 已空，白名单会把所有参数都挡掉。这里从结果模型取「当前 SQL 有哪些来源」。</p>
     */
    public static Set<String> currentParamSources(@NotNull SqlPreviewPanel p) {
        PreviewResult result = p.current;
        if (result == null) return Set.of();
        Set<String> sources = new LinkedHashSet<>();
        sources.addAll(result.getUnresolvedParams());
        if (result.getPreviewModel() != null) {
            for (ConditionView view : result.getPreviewModel().getConditionViews()) {
                sources.addAll(view.getValueSources());
                if (view.getValueSource() != null) sources.add(view.getValueSource());
            }
        }
        BatchModel model = result.getBatchModel();
        if (model != null) {
            for (BatchColumn col : model.getToggleColumns()) sources.add(col.getSource());
            for (BatchColumn col : model.getFixedColumns()) sources.add(col.getSource());
        }
        if (result.getXmlModel() != null) {
            for (XmlForEachView view : result.getXmlModel().getForEachViews()) {
                sources.add(view.collectionExpr());
            }
        }
        return sources;
    }

    /**
     * P-05：从历史缓存召回参数值（showResult 后调用）。
     *
     * <p>命中时按 source 回填（用户已输入值优先，不覆盖）。
     * 召回成功显示淡入提示。</p>
     *
     * <p>R22 T-02/T-03：快照特殊键一并还原——{@code __chk__:<id>}=1/0 勾选态（1 加 0 移，可复现
     * 「关掉默认勾选」的方案）、{@code __fnull__:<expr>}=1 foreach null 勾选、{@code __fev__:<expr>}=
     * 逗号分隔 foreach 权威值（还原权威 map + 同步可见输入框）。还原后 renderSql 收敛。</p>
     */
    public static void recallFromHistory(@NotNull SqlPreviewPanel p) {
        String key = historyCacheKey(p);
        if (key == null) return;
        Map<String, String> cached = p.paramHistoryCache.recall(key);
        // T-PresetMethodBinding：同一方法里改了 SQL（键的 SQL 指纹段变化）时，精确键与旧键都未命中，
        // 按「该方案在该方法下最近一次保存」回落。回落只恢复参数值——结构态（勾选/foreach）
        // 不参与，见下方分支；且只回填当前面板存在的 source（白名单），防止旧参数名越积越多。
        Map<String, String> recovered = cached == null ? p.paramHistoryCache.recallRecent(key) : null;
        Map<String, String> history = cached != null ? cached : recovered;
        if (history == null) return;
        if (history.isEmpty()) return;
        // 回落来源只回填当前预览真实存在的参数（不能用 paramValues.keys：方案切换前刚被清场）
        Set<String> allowedSources = recovered != null ? currentParamSources(p) : Set.of();
        int applied = 0;
        boolean stateRestored = false;
        for (Map.Entry<String, String> e : history.entrySet()) {
            String source = e.getKey();
            String value = e.getValue();
            if (source.startsWith(SqlPreviewPanel.SNAPSHOT_CHK)) {
                // T-PresetMethodBinding：跨 SQL 回落不恢复勾选态——条件 id 是按解析序递增的序号，
                // SQL 增删条件后整体移位，套用旧快照会把勾选落到别的条件上（错位比丢失更难排查）。
                if (recovered == null) {
                    String id = source.substring(SqlPreviewPanel.SNAPSHOT_CHK.length());
                    if ("1".equals(value)) p.enabledIds.add(id);
                    else p.enabledIds.remove(id);
                    stateRestored = true;
                }
            } else if (source.startsWith(SqlPreviewPanel.SNAPSHOT_FNULL)) {
                if (recovered == null) {
                    String expr = source.substring(SqlPreviewPanel.SNAPSHOT_FNULL.length());
                    if ("1".equals(value)) p.xmlForEachNulls.add(expr);
                    else p.xmlForEachNulls.remove(expr);
                    stateRestored = true;
                }
            } else if (source.startsWith(SqlPreviewPanel.SNAPSHOT_FEV)) {
                if (recovered == null) {
                    String expr = source.substring(SqlPreviewPanel.SNAPSHOT_FEV.length());
                    List<String> values = splitTrimmed(value);
                    if (!values.isEmpty()) {
                        p.xmlForEachValues.put(expr, values);
                        for (Pair<String, JBTextField> pair : p.xmlForEachValueFields) {
                            if (pair.getFirst().equals(expr)) {
                                pair.getSecond().setText(value);
                                break;
                            }
                        }
                        stateRestored = true;
                    }
                }
            } else {
                // 用户已输入值优先（不覆盖）；回落来源只认当前预览存在的参数名
                if (recovered != null && !allowedSources.contains(source)) continue;
                String existing = p.paramValues.get(source);
                if (existing == null || existing.isEmpty()) {
                    p.paramValues.put(source, value);
                    syncParamFieldText(p, source, value);
                    applied++;
                }
            }
        }
        if (stateRestored) syncToggleChecksFromState(p);
        if (applied > 0 || stateRestored) {
            SqlPreviewPanelSqlViewKt.renderSql(p);
        }
    }

    // ------------------------------------------------------------------
    // T-PreviewSideQuery P1 / T-DmlImpactProminence：预估影响行数内联结果与独立横幅
    // ------------------------------------------------------------------

    /**
     * UI-006: keep the safety explanation visible, including unbroken identifiers in errors.
     * 像素宽度折行 + HTML 转义（{@code <br>} 断行）——长文本（错误说明等）不撑破参数面板。
     */
    static String fieldFillText(String text, Font font, int limitPx) {
        FontMetrics metrics = new javax.swing.JLabel().getFontMetrics(font);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n'
                    || (line.length() > 0 && metrics.stringWidth(line.toString() + character) > limitPx)) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            if (character != '\n') line.append(character);
        }
        lines.add(line.toString());
        StringBuilder out = new StringBuilder("<html>");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("<br>");
            String it = lines.get(i);
            out.append(it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
        }
        out.append("</html>");
        return out.toString();
    }

    /** T-PreviewSideQuery P1：预估影响行数内联结果（Kotlin 默认参 {@code tooltip=null} 形态）。 */
    public static void showDmlImpactResult(@NotNull SqlPreviewPanel p, String text, boolean error) {
        showDmlImpactResult(p, text, error, null);
    }

    /** T-PreviewSideQuery P1：预估影响行数内联结果；不触碰编辑器/控制台。 */
    public static void showDmlImpactResult(@NotNull SqlPreviewPanel p, String text, boolean error, @Nullable String tooltip) {
        JBLabel label = p.impactResultLabel;
        if (label == null) {
            label = new JBLabel();
            label.setName("dmlImpactResult");
            label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            label.setBorder(JBUI.Borders.empty(4, 16, 4, 8));
            p.paramPanel.add(label);
            p.impactResultLabel = label;
        }
        label.setText(fieldFillText(text, label.getFont(), JBUI.scale(260)));
        label.setToolTipText(tooltip != null ? tooltip : text);
        label.setForeground(error ? JBColor.RED : UIUtil.getContextHelpForeground());
        // T-DmlImpactProminence（用户真机反馈：左下角灰字不够明显）：结果同时挂到顶部独立横幅
        // （蓝色加粗；失败红色），与降级/渲染警示各占一行、互不抢位。
        showDmlImpactBanner(p, text, error);
        p.paramPanel.revalidate();
        p.paramPanel.repaint();
    }

    /** T-DmlImpactProminence：影响行数结果横幅显隐与配色（独立行，见 SqlPreviewPanel.dmlImpactBanner）。 */
    public static void showDmlImpactBanner(@NotNull SqlPreviewPanel p, String text, boolean error) {
        p.dmlImpactBannerLabel.setText(text);
        p.dmlImpactBannerLabel.setForeground(error
                ? JBColor.RED
                : JBColor.namedColor("Link.activeForeground", JBColor.BLUE));
        Font base = UIUtil.getLabelFont();
        p.dmlImpactBannerLabel.setFont(error ? base : base.deriveFont(Font.BOLD));
        p.dmlImpactBanner.setVisible(true);
        p.dmlImpactBanner.revalidate();
        p.dmlImpactBanner.repaint();
    }

    public static void clearDmlImpactBanner(@NotNull SqlPreviewPanel p) {
        p.dmlImpactBanner.setVisible(false);
        p.dmlImpactBannerLabel.setText("");
    }

    /**
     * R22 T-02：把当前状态集同步到可见勾选框（召回路径——勾选框初始态在渲染时定型，
     * 召回发生在面板已建之后，须显式回写 isSelected）。
     */
    public static void syncToggleChecksFromState(@NotNull SqlPreviewPanel p) {
        for (JCheckBox cb : toggleBoxes(p)) {
            Object id = cb.getClientProperty(SqlPreviewPanel.PROP_TOGGLE_ID);
            if (!(id instanceof String)) continue;
            cb.setSelected(p.enabledIds.contains((String) id));
        }
        for (JCheckBox cb : p.xmlForEachNullBoxes) {
            Object expr = cb.getClientProperty(SqlPreviewPanel.PROP_FOREACH_NULL);
            if (!(expr instanceof String)) continue;
            cb.setSelected(p.xmlForEachNulls.contains((String) expr));
        }
    }

    /**
     * R22 T-02：参数历史快照构建——paramValues 之外并入勾选态与 foreach 权威态：
     * <ul>
     *   <li>{@code __chk__:<id>} = "1"/"0"：条件/批量勾选<b>全量 1/0 编码</b>（可见组件逐个枚举 + enabledIds
     *     残留项补 "1"）——只存 "1" 无法复现「关掉默认勾选」的方案；</li>
     *   <li>{@code __fnull__:<expr>} = "1"：foreach null 勾选（默认态=未勾，只存勾选侧）；</li>
     *   <li>{@code __fev__:<expr>} = 逗号分隔值：foreach 集合权威值（空值不存=默认展开）。</li>
     * </ul>
     */
    @NotNull
    public static Map<String, String> buildHistorySnapshot(@NotNull SqlPreviewPanel p) {
        Map<String, String> snapshot = new LinkedHashMap<>(p.paramValues);
        Set<String> seenToggles = new HashSet<>();
        for (JCheckBox cb : toggleBoxes(p)) {
            Object id = cb.getClientProperty(SqlPreviewPanel.PROP_TOGGLE_ID);
            if (!(id instanceof String)) continue;
            String sid = (String) id;
            seenToggles.add(sid);
            snapshot.put(SqlPreviewPanel.SNAPSHOT_CHK + sid, p.enabledIds.contains(sid) ? "1" : "0");
        }
        for (String it : p.enabledIds) {
            if (!seenToggles.contains(it)) snapshot.put(SqlPreviewPanel.SNAPSHOT_CHK + it, "1");
        }
        for (String it : p.xmlForEachNulls) {
            snapshot.put(SqlPreviewPanel.SNAPSHOT_FNULL + it, "1");
        }
        for (Map.Entry<String, List<String>> e : p.xmlForEachValues.entrySet()) {
            if (!e.getValue().isEmpty()) {
                snapshot.put(SqlPreviewPanel.SNAPSHOT_FEV + e.getKey(), String.join(",", e.getValue()));
            }
        }
        return snapshot;
    }

    /**
     * P-05：保存当前参数值到历史缓存（debounce 500ms）。
     *
     * <p>每次击键 restart 计时器（取消前次 + 重新调度），避免频繁序列化。</p>
     */
    public static void saveToHistory(@NotNull SqlPreviewPanel p) {
        p.historyAlarm.restart();
    }

    /**
     * P-05：Timer 动作——实际写回缓存（由 historyAlarm 调度）。
     * T-Perf-24：序列化/持久化写挪 pooled 线程（EDT 只做取值快照）。
     * R22 T-04 防串桶：取快照（EDT）与写回（pooled 线程）之间用户可能切换方案——
     * 快照时捕获方案名随行落库（saveForScheme），不再依赖写回时刻的激活态。
     *
     * <p>T-DevAcceptFix2 ⑦（20261006 第三轮走查，用户实测复现）：自动保存<b>恒写默认（工作区）桶</b>。
     * 原实现按「当前激活预设」归桶——用户流程「输入 zhangsan → 另存为『zhangsan』 → 改参数为 lisi」
     * 中，第 2 步后激活指针指向 zhangsan 预设，第 3 步的编辑经本例 500ms 防抖直接<b>就地覆写该预设</b>
     * （schemes["zhangsan"][key] 变成 lisi），于是「切回 zhangsan 预设仍显示 lisi」。
     * 现语义：<b>命名预设＝快照</b>，只经「另存为/管理预设」显式写入；参数编辑只更新工作区（默认桶），
     * 切预设时按目标预设快照召回。防串桶目标（异步写回不落到别的桶）由「恒默认」天然满足。</p>
     */
    public static void flushHistoryToCache(@NotNull SqlPreviewPanel p) {
        String key = historyCacheKey(p);
        if (key == null) return;
        Map<String, String> snapshot = buildHistorySnapshot(p);
        // headless 单测：同步执行保持既有测试语义
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            p.paramHistoryCache.saveForScheme(ParamHistoryCache.DEFAULT_SCHEME, key, snapshot);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() ->
                p.paramHistoryCache.saveForScheme(ParamHistoryCache.DEFAULT_SCHEME, key, snapshot));
    }

    /**
     * 按出现顺序把 SQL 中的 {@code ?} 替换为用户输入（按 source 定位参数值，取代位置绑定，见 M12-R2）。
     * 值渲染按回填提示 {@link ParamHint}（M13）：LIKE 位置拼 {@code %}、String 列恒引号、数值列恒原样；
     * 提示缺失/类型未知 → 数字启发式；空值一律保留 {@code ?}（复选框纯手动，不联动勾选态）。
     *
     * <p>检测 SQL 中 IN 子句的参数索引。
     * 扫描 {@code IN (?, ?, ...)} 模式，返回 IN 子句内 {@code ?} 的参数索引集合。
     * 面板回填时这些参数按逗号分割多值渲染。</p>
     *
     * <p>T-Perf-04：单趟 O(N) 扫描——旧实现对每个 {@code ?} 复制并大写化整个前缀（O(L×P)）；
     * 现改为就地回扫（跳过空白 → {@code (} → 可选一个空格 → {@code IN}，与旧 trimEnd+endsWith 语义一致），
     * 零分配。</p>
     *
     * <p>O-81 T-XmlListParamSplit：{@code IN} 与 {@code (} 之间从「可选一个空格」放宽为<b>任意空白串</b>——
     * mapper 排版 {@code IN\n(}（先红读数实证：升级前漏判 → 集合参数整串单值 {@code 'PI,WP'}）不再漏。
     * 多 {@code ?} IN 列表仍仅首位命中（foreach 已有逐项回填通道，扩面会双拆，语义钉住不变）。</p>
     */
    @NotNull
    public static Set<Integer> detectInClauseParams(@NotNull SqlPreviewPanel p, String sql) {
        Set<Integer> result = new LinkedHashSet<>();
        int paramIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) != '?') continue;
            int j = i - 1;
            while (j >= 0 && Character.isWhitespace(sql.charAt(j))) j--;
            if (j >= 0 && sql.charAt(j) == '(') {
                j--;
                while (j >= 0 && Character.isWhitespace(sql.charAt(j))) j--;
                if (j >= 1 && (sql.charAt(j) == 'N' || sql.charAt(j) == 'n')
                        && (sql.charAt(j - 1) == 'I' || sql.charAt(j - 1) == 'i')) {
                    result.add(paramIndex);
                }
            }
            paramIndex++;
        }
        return result;
    }

    /**
     * T-PSG-03（N-33）：按 source 回填 {@code ?} 占位符——位点判定走 {@code placeholderPositions}
     * （string-aware），字符串字面量与 {@code --} / 块注释内的 {@code ?} 不再被替换（改前朴素逐字符
     * {@code c == '?'} 会错位：引号内 {@code ?} 吃掉了本属真占位符的参数值）。
     *
     * <p>值渲染语义不变（{@link #renderParamValue} / {@link #renderSingleValue} 一行未动）；不含这类 {@code ?} 的
     * SQL 回填结果与改前逐字节一致。</p>
     *
     * <p>T-PSG-04：同一趟记下「位点数 vs 参数条数」对账结论（{@code placeholderReconciliation}），
     * 执行前由 {@code warnOnPlaceholderMismatch} 如实标注——不阻断。</p>
     */
    @NotNull
    public static String backfillParamsBySource(@NotNull SqlPreviewPanel p, String sql, List<String> sources) {
        return backfillParamsBySource(p, sql, sources, List.of());
    }

    /** Java 形态：Kotlin 默认参数 {@code hints: List<ParamHint> = emptyList()} → 两重载。 */
    @NotNull
    public static String backfillParamsBySource(
            @NotNull SqlPreviewPanel p, String sql, List<String> sources, List<ParamHint> hints) {
        if (sources.isEmpty()) return sql;
        List<Integer> positions = PlaceholderPositionsKt.placeholderPositions(sql);
        p.placeholderReconciliation = PlaceholderCardinality.mismatchMarker(positions.size(), sources.size());
        if (positions.isEmpty()) return sql;
        StringBuilder sb = new StringBuilder(sql.length() + 32);
        int paramIndex = 0;
        int prev = 0;
        for (int pos : positions) {
            sb.append(sql, prev, pos);
            String source = paramIndex < sources.size() ? sources.get(paramIndex) : null;
            String v = source != null ? p.paramValues.get(source) : null;
            ParamHint hint = paramIndex < hints.size() ? hints.get(paramIndex) : null;
            sb.append(renderParamValue(p, v, hint, source));
            paramIndex++;
            prev = pos + 1;
        }
        sb.append(sql, prev, sql.length());
        return sb.toString();
    }

    @NotNull
    public static String renderParamValue(@NotNull SqlPreviewPanel p, @Nullable String v, @Nullable ParamHint hint) {
        return renderParamValue(p, v, hint, null);
    }

    /** Java 形态：Kotlin 默认参数 {@code source: String? = null} → 两重载。 */
    @NotNull
    public static String renderParamValue(
            @NotNull SqlPreviewPanel p, @Nullable String v, @Nullable ParamHint hint, @Nullable String source) {
        // 未填写（paramValues 未播种的 source，非标准路径）保留 `?` 兜底提示
        if (v == null) return "?";
        // T-PT-04 / O-79：空输入按设置策略出 ?（默认占位档）/ NULL / ''——**仅对带签名类型信息的
        // 参数**生效；无类型信息（UNKNOWN）恒 `?` 占位提示（宁缺勿错）
        if (v.isBlank()) return hasSignatureTypeInfo(p, source) ? emptyParamLiteral(p) : "?";
        LikePosition like = hint != null ? hint.getLikePosition() : null;
        // M19.6（用户实测）：IN 列表多值回填——逗号分割，每个值独立渲染（引号规则同单值），
        // 输入 `1,2` → `('1','2')`（原实现拼成 `'1,2'` 单值）
        if (hint != null && hint.isInList()) {
            List<String> parts = splitTrimmed(v);
            // 逗号分割后无有效值（如 `,` 形态）：按空输入档处理
            if (parts.isEmpty()) return hasSignatureTypeInfo(p, source) ? emptyParamLiteral(p) : "?";
            List<String> rendered = new ArrayList<>();
            for (String part : parts) rendered.add(renderSingleValue(p, part, hint));
            return String.join(", ", rendered);
        }
        String escaped = v.replace("'", "''");
        if (like != null) {
            // LIKE 模式：恒引号 + 按位置拼 %（值本身转义）
            switch (like) {
                case ANYWHERE:
                    return "'%" + escaped + "%'";
                case LEFT:
                    return "'%" + escaped + "'";
                case RIGHT:
                    return "'" + escaped + "%'";
                case NONE:
                    return "'" + escaped + "'";
                default:
                    break;
            }
        }
        return renderSingleValue(p, v, hint);
    }

    /**
     * T-PT-04 / O-79：空参数填充字面量——按设置「空参数填充策略」出 {@code ?}（默认占位档，保留待填
     * 提示，SQL 不臆造语义）/ {@code NULL} / {@code ''}。作用于 {@link #renderParamValue} 的空值分支，且仅当参数带
     * 签名类型信息（{@link #hasSignatureTypeInfo}）；未知策略值按占位档兜底。
     */
    @NotNull
    public static String emptyParamLiteral(@NotNull SqlPreviewPanel p) {
        switch (PluginSettings.getInstance().getState().getEmptyParamFill()) {
            case PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING:
                return "''";
            case PluginSettings.EMPTY_PARAM_FILL_NULL:
                return "NULL";
            default:
                return "?";
        }
    }

    /** T-PT-04：参数是否带签名类型信息（XML/注解 mapper 参数行）——决定空输入是否走填充策略。 */
    public static boolean hasSignatureTypeInfo(@NotNull SqlPreviewPanel p, @Nullable String source) {
        return source != null && paramKindOf(p, source) != ParamKind.UNKNOWN;
    }

    /** 单值渲染（M19.6 抽取，IN 列表各值与普通参数共用）：String 列恒引号、数值恒原样、未知走数字启发式。 */
    @NotNull
    public static String renderSingleValue(@NotNull SqlPreviewPanel p, String raw, @Nullable ParamHint hint) {
        String escaped = raw.replace("'", "''");
        if (hint != null && Boolean.TRUE.equals(hint.getQuote())) return "'" + escaped + "'";
        if (hint != null && Boolean.FALSE.equals(hint.getQuote())) return raw.trim();
        String trimmed = raw.trim();
        return SqlPreviewPanel.NUMBER_REGEX.matcher(trimmed).matches() ? trimmed : "'" + escaped + "'";
    }

    // ------------------------------------------------------------------
    // 内部辅助（Kotlin 集合操作/可空链的等价形态；均私有，不进公开 API）
    // ------------------------------------------------------------------

    /** {@code listOfNotNull(x)} 等价。 */
    @NotNull
    private static List<String> listOfNotNull(@Nullable String x) {
        return x != null ? List.of(x) : List.of();
    }

    /** Kotlin {@code all { it.isSelected }}：空集合返回 true。 */
    private static boolean allSelected(List<JCheckBox> boxes) {
        for (JCheckBox cb : boxes) {
            if (!cb.isSelected()) return false;
        }
        return true;
    }

    /** {@code (conditionCheckBoxes + batchCheckBoxes)} 等价。 */
    @NotNull
    private static List<JCheckBox> toggleBoxes(@NotNull SqlPreviewPanel p) {
        List<JCheckBox> all = new ArrayList<>(p.conditionCheckBoxes);
        all.addAll(p.batchCheckBoxes);
        return all;
    }

    /** Kotlin {@code text.split(",").map { it.trim() }.filter { it.isNotEmpty() }} 等价。 */
    @NotNull
    private static List<String> splitTrimmed(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
