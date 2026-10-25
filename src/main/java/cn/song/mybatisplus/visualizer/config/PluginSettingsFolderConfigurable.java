package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.options.ex.Settings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import org.jetbrains.annotations.NotNull;

/**
 * 设置页主页面（左侧树导航的父节点）。
 *
 * 参考 IDEA 原生设置风格：
 *  - 顶部搜索栏
 *  - 子页面列表（图标 + 名称 + 描述）
 *  - 搜索时显示匹配的设置项（名称 + 所属页面 + 当前值预览）
 */
public class PluginSettingsFolderConfigurable implements Configurable {

    /** 落地页描述灰（T-UN-20：与日志页 LIST_META_GRAY / 历史页 HISTORY_META_GRAY 同源 namedColor，亮值 0x9E9E9E）。 */
    private static final SimpleTextAttributes SETTINGS_DESC_GRAY =
        new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY);

    private final SearchTextField searchField = new SearchTextField();
    private final DefaultListModel<SearchItem> listModel = new DefaultListModel<>();
    private final JBList<SearchItem> list = new JBList<>(listModel);

    @Override
    public String getDisplayName() {
        return MpSqlVisualizerBundle.message("plugin.name");
    }

    @NotNull
    @Override
    public JComponent createComponent() {
        rebuildList("");
        schedulePendingFocus();

        list.setCellRenderer(new SearchItemRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(56); // 固定行高，匹配 IDEA 风格

        // 点击跳转（双击导航到子页面）
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(MouseEvent e) {
                SearchItem selected = list.getSelectedValue();
                if (selected != null) navigateTo(selected);
                return true;
            }
        }.installOn(list);

        // 搜索过滤
        searchField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuildList(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuildList(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuildList(searchField.getText());
            }
        });

        // 搜索面板
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(JBUI.Borders.empty(10, 16, 6, 16));
        searchPanel.add(searchField, BorderLayout.CENTER);

        // 列表面板
        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setBorder(JBUI.Borders.empty());

        // R20（W3 可发现性修缮包）：落地页价值展示——三行能力矩阵 + 官网链接
        // （原页=搜索框+子页列表，用户不知道插件能干什么；「设置两层深」的兜底从搜索变一眼）
        JPanel valuePanel = new JPanel(new GridLayout(0, 1));
        valuePanel.setBorder(JBUI.Borders.empty(10, 16, 10, 16));
        valuePanel.add(new JBLabel("<html><b>增强捕获</b>：运行即采集真实参数、耗时、影响行数与目标库</html>"));
        valuePanel.add(new JBLabel("<html><b>免库预览</b>：Wrapper / mapper XML / JPA·HQL / 注解 SQL 写前静态还原</html>"));
        valuePanel.add(new JBLabel("<html><b>MCP 十一工具</b>：AI agent 直连渲染 / 审计 / 执行计划（Ultimate）</html>"));
        valuePanel.add(new JBLabel("<html>兼容矩阵与文档：<a href=\"https://sql-visualizer.jvtools.cn\">sql-visualizer.jvtools.cn</a></html>"));

        JPanel root = new JPanel(new BorderLayout());
        root.add(searchPanel, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(valuePanel, BorderLayout.SOUTH);
        return root;
    }

    /**
     * 重建列表：先显示匹配的子页面，再显示匹配的设置项内容。
     */
    private void rebuildList(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        listModel.removeAllElements();

        if (q.isEmpty()) {
            for (SubPageDesc page : SUB_PAGES) {
                listModel.addElement(new SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.ref));
            }
            return;
        }

        // 1) 匹配的子页面
        for (SubPageDesc page : SUB_PAGES) {
            if (MpSqlVisualizerBundle.message(page.labelKey).toLowerCase(Locale.ROOT).contains(q)
                || MpSqlVisualizerBundle.message(page.descKey).toLowerCase(Locale.ROOT).contains(q)) {
                listModel.addElement(new SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.ref));
            }
        }

        // 2) 匹配的设置项内容
        for (SearchableField field : ALL_SEARCHABLE_FIELDS) {
            String label = field.searchLabel.toLowerCase(Locale.ROOT);
            String tooltip = field.searchTooltip.toLowerCase(Locale.ROOT);
            if (label.contains(q) || tooltip.contains(q)) {
                listModel.addElement(new SearchItem.FieldItem(
                    field.searchLabel,
                    field.searchTooltip,
                    field.pageLabelKey,
                    field.ref));
            }
        }
    }

    private void navigateTo(SearchItem item) {
        String focusHint = item instanceof SearchItem.FieldItem
            ? ((SearchItem.FieldItem) item).fieldLabel
            : null;
        navigateToPage(item.ref, focusHint);
    }

    /**
     * 在同一对话框内导航到指定子页面，并聚焦目标控件。
     *
     * 从宿主 SettingsEditor 取得当前 Settings（同一设置对话框），用 settings.select 切换树选择；
     * 切换完成后聚焦目标控件并闪烁高亮，让用户感知目标位置。取不到 Settings 时才回退打开设置对话框。
     *
     * <p>**对话框内定位必须按 EP id 查（{@link #resolveTarget}），不得按类查**——平台
     * {@code Settings.find(Class)} = {@code ConfigurableVisitor.findByType}，对树里每个 {@code ConfigurableWrapper}
     * 调 {@code ConfigurableWrapper.cast}；而 {@code cast} 里那条「EP 声明类型与目标不相容就不创建」的短路，
     * 取的是 {@code ConfigurableEP.getConfigurableType()}，{@code ClassProducer} 未覆写 {@code ObjectProducer.getType()}
     * （2024.3 {@code ConfigurableEP.java:392} 返回 null）→ 短路是死代码 → **整棵树（含其它插件页面）
     * 全被实例化**。Kotlin 插件 {@code KotlinScriptingSettingsConfigurable} 构造器里做文件名索引访问
     * （{@code KotlinScriptingSettingsConfigurable.java:46} → {@code FilenameIndex.getVirtualFilesByName}），
     * 于是在 EDT 上触发 {@code Slow operations are prohibited on EDT}（真机 2024.3.7.1 两份栈实证）。
     * 按 id 查时 {@code ConfigurableWrapper.getId()} 对声明了 id 的 EP 直接返回 {@code myEp.id}（不实例化），
     * 只有目标页会被创建。</p>
     */
    /**
     * 消费外部预置的待定位目标（T-SettingsFocusFromLink）：页面构建后双跳 invokeLater——
     * 确保设置对话框已完成装配、{@code Settings.KEY} 经 searchField DataContext 可达（P1 前提）。
     * 未置目标 → 零成本（双跳前先查，避免无谓调度）；消费即清（防重复定位）。
     */
    private void schedulePendingFocus() {
        if (pendingFocus == null) return;
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            PendingFocus pending = consumePendingFocus();
            if (pending == null) return;
            for (SubPageDesc page : SUB_PAGES) {
                if (page.ref.id().equals(pending.pageId())) {
                    navigateToPage(page.ref, pending.hint());
                    break;
                }
            }
        }));
    }

    private void navigateToPage(SubPageRef ref, String focusHint) {
        Settings settings = currentSettings();
        if (settings == null) {
            // 未在已打开的设置对话框内，回退为打开设置对话框（平台只有按类定位的公开重载）
            Project[] projects = ProjectManager.getInstance().getOpenProjects();
            if (projects.length == 0) return;
            ShowSettingsUtil.getInstance().showSettingsDialog(projects[0], ref.cls());
            return;
        }

        Configurable target = resolveTarget(settings, ref.id());
        if (target == null) return;
        settings.select(target).doWhenDone(() -> {
            if (focusHint != null) {
                focusTarget(target, focusHint);
            }
        });
    }

    /**
     * 在设置树中定位目标页（测试缝 + 反回归哨兵：签名里没有 Class，退回按类查编译不过）。
     *
     * <p>为什么不能按类查，见 {@link #navigateToPage} 的 KDoc。</p>
     *
     * <p>移植注记：master 侧为 {@code internal}（KOTLIN_TO_JAVA_RULES §0.1：internal → public）。</p>
     */
    public Configurable resolveTarget(Settings settings, String configurableId) {
        return settings.find(configurableId);
    }

    /**
     * 获取当前已打开设置对话框的 {@link Settings} 实例。
     *
     * 主设置面板由 SettingsEditor 承载，SettingsEditor 实现 UiDataProvider 并对外提供 Settings.KEY。
     * 优先通过 DataManager 从 searchField 的真实组件 DataContext 取（会沿组件树收集 UiDataProvider 数据）；
     * 失败则沿组件树反射读取 SettingsEditor.settings 字段兜底。
     * SettingsEditor 为 @ApiStatus.Internal，用 Class.forName 反射避免编译期依赖内部 API。
     */
    private Settings currentSettings() {
        try {
            Settings fromData = Settings.KEY.getData(DataManager.getInstance().getDataContext(searchField));
            if (fromData != null) return fromData;
        } catch (Throwable ignored) {
            // 忽略，走反射兜底
        }

        // 兜底：沿组件树反射读取 SettingsEditor.settings 字段（反射类名，避免直接引用 internal 类型）
        Class<?> editorClass;
        try {
            editorClass = Class.forName("com.intellij.openapi.options.newEditor.SettingsEditor");
        } catch (Throwable t) {
            return null;
        }

        Component comp = searchField;
        while (comp != null) {
            if (editorClass.isInstance(comp)) {
                try {
                    java.lang.reflect.Field field = editorClass.getDeclaredField("settings");
                    field.setAccessible(true);
                    Object value = field.get(comp);
                    if (value instanceof Settings) return (Settings) value;
                } catch (Throwable ignored) {
                    // 忽略，返回 null
                }
                return null;
            }
            comp = comp.getParent();
        }
        return null;
    }

    /**
     * 聚焦目标设置控件：页面切换后在真实面板中查找目标字段，滚动到可见区域并闪烁高亮。
     * 页面切换为异步，延迟到 EDT 调度以确保布局完成。
     */
    private static void focusTarget(Configurable target, String focusHint) {
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            try {
                // 页面已显示，createComponent() 返回真实面板（SectionConfigurable 已缓存）
                JComponent root = target.createComponent();
                if (root == null) return;
                Component matched = findComponent(root, focusHint);
                if (matched != null) {
                    // 滚动到可见区域，让用户感知目标位置
                    if (matched instanceof JComponent) {
                        ((JComponent) matched).scrollRectToVisible(matched.getBounds());
                    }
                    flashHighlight(matched);
                    root.revalidate();
                    root.repaint();
                }
            } catch (Throwable ignored) {
                // 忽略聚焦失败，不影响导航
            }
        }));
    }

    /**
     * 递归查找匹配 hint 的控件。
     *
     * 类型判定按「输入框 tooltip → 按钮文本 → 标签文本」顺序（同一组件只可能命中一类）；
     * 行内按组件树顺序深度优先，故「同一 hint 同时命中行标签与该行控件」时以先遍历到者为准
     * （DSL v2 {@code row(label)} 行里标签在前）。
     *
     * T-FS-09：标签分支须匹配<b>普通 {@link JLabel}</b>——项目页走 DSL v2（{@code row(label)}
     * 生成的就是普通 JLabel），全局页手摆的是 {@link JBLabel}（JLabel 子类）。原先只认 JBLabel，
     * 项目页 6 行（方言下拉 / 推断策略下拉 / 4 个文本框）的行标签定位不到 → 双击搜索条目
     * 「跳了但不亮」；改认 JLabel 后与全局页形态一致（两页都定位到行标签）。
     *
     * @return 匹配的控件，未找到返回 null
     */
    private static Component findComponent(Container container, String hint) {
        for (Component comp : container.getComponents()) {
            boolean matched;
            if (comp instanceof JTextComponent) {
                String tip = ((JTextComponent) comp).getToolTipText();
                matched = tip != null && tip.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT));
            } else if (comp instanceof AbstractButton) {
                String text = ((AbstractButton) comp).getText();
                matched = text != null && text.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT));
            } else if (comp instanceof JLabel) {
                String text = ((JLabel) comp).getText();
                matched = text != null && text.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT));
            } else {
                matched = false;
            }
            if (matched) return comp;
            if (comp instanceof Container) {
                Component deeper = findComponent((Container) comp, hint);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }

    /**
     * T-FS-09 测试缝：暴露「按搜索项 hint 在页面组件树中定位高亮目标」的查找入口。
     *
     * 闪烁（{@link #flashHighlight}）是视觉行为、headless 不可断言；但「目标控件能否被定位」是纯组件树
     * 查找，可在 headless 下逐条断言（定位不到 = 用户看到「跳了但不亮」的隐性缺陷）。
     *
     * 移植注记：master 侧为 {@code internal}（KOTLIN_TO_JAVA_RULES §0.1：internal → public），
     * 跨包测试 ProjectPluginSettingsConfigurableTest 需按公开形态调用。
     */
    public Component findHighlightTargetForTest(Container container, String hint) {
        return findComponent(container, hint);
    }

    /**
     * 短暂高亮目标控件：黄底闪烁 + 橙色边框，持续约 1.5 秒。
     */
    private static void flashHighlight(Component target) {
        target.requestFocusInWindow();
        if (!(target instanceof JComponent)) return;

        JComponent comp = (JComponent) target;
        Color originalBg = comp.getBackground();
        boolean originalOpaque = comp.isOpaque();
        Border originalBorder = comp.getBorder();

        // T-UN-13（T-UiNative）：闪黄两色 → namedColor（亮/暗值均原样平移）
        final Color flash = MpvsUiColors.HIGHLIGHT_BACKGROUND;
        comp.setOpaque(true);
        comp.setBorder(BorderFactory.createLineBorder(MpvsUiColors.HIGHLIGHT_BORDER, 2));

        int[] count = {0};
        Timer timer = new Timer(250, ev -> {
            count[0]++;
            if (count[0] % 2 == 1) {
                comp.setBackground(flash);
            } else {
                comp.setBackground(originalBg);
            }
            comp.repaint();
            if (count[0] >= 6) {
                comp.setBackground(originalBg);
                comp.setOpaque(originalOpaque);
                comp.setBorder(originalBorder);
                ((Timer) ev.getSource()).stop();
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
    }

    @Override
    public void reset() {
    }

    /**
     * 跳转目标：EP id（{@code plugin.xml} 声明）+ 目标类。
     *
     * <p>对话框内按 {@link #id()} 查（理由见 {@link #navigateToPage}），{@link #cls()} 只服务兜底分支
     * ——未在设置对话框内、需新开对话框时，平台没有按 id 的公开重载可用。</p>
     *
     * <p>Kotlin {@code data class} → Java {@code record}（KOTLIN_TO_JAVA_RULES §0.2）。</p>
     */
    private record SubPageRef(
        /** EP id（{@code plugin.xml} 声明的 id 属性）。 */
        String id,
        /** 目标设置页类（只服务「新开设置对话框」兜底分支）。 */
        Class<? extends Configurable> cls
    ) {
    }

    /** 搜索结果项 */
    private abstract static class SearchItem {

        final SubPageRef ref;

        SearchItem(SubPageRef ref) {
            this.ref = ref;
        }

        static final class PageItem extends SearchItem {

            final String labelKey;
            final String descKey;
            final Icon icon;

            PageItem(String labelKey, String descKey, Icon icon, SubPageRef ref) {
                super(ref);
                this.labelKey = labelKey;
                this.descKey = descKey;
                this.icon = icon;
            }
        }

        static final class FieldItem extends SearchItem {

            final String fieldLabel;
            final String fieldTooltip;
            final String pageLabelKey;

            FieldItem(String fieldLabel,
                      String fieldTooltip,
                      String pageLabelKey,
                      SubPageRef ref) {
                super(ref);
                this.fieldLabel = fieldLabel;
                this.fieldTooltip = fieldTooltip;
                this.pageLabelKey = pageLabelKey;
            }
        }
    }

    /** 子页面描述 */
    private static final class SubPageDesc {

        final String labelKey;
        final String descKey;
        final Icon icon;
        final SubPageRef ref;

        SubPageDesc(String labelKey, String descKey, Icon icon, SubPageRef ref) {
            this.labelKey = labelKey;
            this.descKey = descKey;
            this.icon = icon;
            this.ref = ref;
        }
    }

    /** 可搜索的设置项字段 */
    private static final class SearchableField {

        final String searchLabel;
        final String searchTooltip;
        final String pageLabelKey;
        final SubPageRef ref;

        SearchableField(String searchLabel,
                        String searchTooltip,
                        String pageLabelKey,
                        SubPageRef ref) {
            this.searchLabel = searchLabel;
            this.searchTooltip = searchTooltip;
            this.pageLabelKey = pageLabelKey;
            this.ref = ref;
        }
    }

    /**
     * 列表渲染器 — 参考 IDEA 原生设置风格。
     * 子页面：图标 + 名称（粗体）+ 描述（灰色小字）
     * 设置项：图标 + 名称 + 所属页面路径
     *
     * <p>T-UN-20（T-UiNative）：行渲染归一 {@link SimpleColoredComponent} 片段通道——与日志页
     * LogEntryListCellRenderer、历史页 HistoryCellRenderer 同款双行结构（名称行在上、描述行在下），
     * GridBag 行内面板拼接退役。选中态走平台选中填充（背景取列表选中色、文本随 selectionForeground），
     * 不再自绘选中背景/描边；固定 56px 行高由 list.fixedCellHeight 承担，渲染器只管内容。
     */
    private static final class SearchItemRenderer extends JPanel implements javax.swing.ListCellRenderer<Object> {

        /** 名称行（internal 供测试遍历片段断言文本与着色）。 */
        final SimpleColoredComponent nameLine = new SimpleColoredComponent();

        /** 描述行（internal 供测试遍历片段断言文本与着色）。 */
        final SimpleColoredComponent descLine = new SimpleColoredComponent();

        private final JPanel linesPanel = new JPanel(new GridLayout(0, 1));

        private final JBLabel iconLabel = new JBLabel();

        private SearchItemRenderer() {
            super(new BorderLayout());
            setOpaque(true);
            nameLine.setOpaque(false);
            descLine.setOpaque(false);
            descLine.setBorder(new EmptyBorder(JBUI.insetsTop(2))); // 原两行间距
            linesPanel.setOpaque(false);
            linesPanel.add(nameLine);
            linesPanel.add(descLine);
            // 原 GridBag NORTHWEST 锚点语义：图标顶对齐（BorderLayout.WEST 纵向拉伸后需显式置顶）
            iconLabel.setBorder(new EmptyBorder(JBUI.insetsRight(12)));
            iconLabel.setVerticalAlignment(SwingConstants.TOP);
            add(iconLabel, BorderLayout.WEST);
            add(linesPanel, BorderLayout.CENTER);
            setBorder(new EmptyBorder(JBUI.insets(8, 16)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list,
                                                      Object value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            if (!(value instanceof SearchItem)) {
                iconLabel.setIcon(null);
                nameLine.clear();
                descLine.clear();
                return this;
            }
            SearchItem item = (SearchItem) value;

            // 平台选中填充：背景/前景取列表（选中）色，不自绘选中视觉（与日志/历史页渲染器同款）
            Color listBg = list != null ? list.getBackground() : getBackground();
            setBackground(isSelected
                ? (list != null && list.getSelectionBackground() != null ? list.getSelectionBackground() : listBg)
                : listBg);
            Color fg = isSelected
                ? (list != null && list.getSelectionForeground() != null ? list.getSelectionForeground() : getForeground())
                : (list != null ? list.getForeground() : getForeground());

            nameLine.setForeground(fg);
            descLine.setForeground(fg);
            nameLine.clear();
            descLine.clear();
            if (item instanceof SearchItem.PageItem) {
                SearchItem.PageItem page = (SearchItem.PageItem) item;
                iconLabel.setIcon(page.icon);
                nameLine.append(MpSqlVisualizerBundle.message(page.labelKey), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                descLine.append(MpSqlVisualizerBundle.message(page.descKey), descAttributes(isSelected));
            } else {
                SearchItem.FieldItem field = (SearchItem.FieldItem) item;
                iconLabel.setIcon(AllIcons.Actions.Search);
                nameLine.append(field.fieldLabel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                descLine.append(MpSqlVisualizerBundle.message(field.pageLabelKey), descAttributes(isSelected));
            }
            return this;
        }

        /** 描述行着色：未选中用元信息灰；选中随 selectionForeground（REGULAR）保证可读——与日志页 meta 灰同款。 */
        private static SimpleTextAttributes descAttributes(boolean isSelected) {
            return isSelected ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SETTINGS_DESC_GRAY;
        }
    }

    // 设置页 EP id —— 与 src/main/resources/META-INF/plugin.xml 的 id 属性一一对应。
    // 导航按 id 查（见 navigateToPage），id 写错会静默跳不动，由
    // PluginSettingsFolderConfigurableTest 的「id 与类指向同一页」守卫兜住。
    private static final String ID_UI = "cn.song.mybatisplus.visualizer.settings.ui";
    private static final String ID_PREVIEW = "cn.song.mybatisplus.visualizer.settings.preview";
    private static final String ID_TABLE_MAPPING = "cn.song.mybatisplus.visualizer.settings.tableMapping";
    private static final String ID_SQL_LOG = "cn.song.mybatisplus.visualizer.settings.sqlLog";

    /**
     * 外部（面板空态/引导链接，T-SettingsFocusFromLink）预置的待定位目标。
     *
     * <p>链接从<b>对话框外</b>发起——拿不到本 Configurable 实例（{@code navigateToPage} 需要
     * Settings/实例），故单槽预置、本页 {@code createComponent} 后消费一次。后到覆盖
     * （点击是显式用户动作，无需排队）。</p>
     */
    private static volatile PendingFocus pendingFocus = null;

    /** 待定位目标（页 id + hint；hint=null 只切页不聚焦行）。 */
    public record PendingFocus(String pageId, String hint) {
    }

    /**
     * 打开插件主设置页并定位到 SQL 日志页（hint=null 只切页）。
     * 面板引导链接统一入口——定位高亮复用既有 {@code navigateToPage → focusTarget}（滚动+黄闪+橙框）。
     */
    public static void openSqlLogSettingsFocused(Project project, String hint) {
        pendingFocus = new PendingFocus(ID_SQL_LOG, hint);
        ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, PluginSettingsFolderConfigurable.class);
    }

    /** 消费待定位目标（取走即清；生产调度与测试缝单源共用，防两处漂移）。 */
    public static PendingFocus consumePendingFocus() {
        PendingFocus p = pendingFocus;
        pendingFocus = null;
        return p;
    }

    public static void requestFocusOnOpenForTest(String pageId, String hint) {
        pendingFocus = new PendingFocus(pageId, hint);
    }

    private static final String ID_SHORTCUTS = "cn.song.mybatisplus.visualizer.settings.shortcuts";
    private static final String ID_MCP = "cn.song.mybatisplus.visualizer.settings.mcp";
    private static final String ID_ABOUT = "cn.song.mybatisplus.visualizer.settings.about";
    private static final String ID_PROJECT = "cn.song.mybatisplus.visualizer.settings.project";

    private static final SubPageDesc PAGE_UI = new SubPageDesc(
        "settings.section.ui", "settings.section.ui.desc", AllIcons.Nodes.Plugin,
        new SubPageRef(ID_UI, UiActionsConfigurable.class));
    private static final SubPageDesc PAGE_PREVIEW = new SubPageDesc(
        "settings.section.preview", "settings.section.preview.desc", AllIcons.Nodes.Class,
        new SubPageRef(ID_PREVIEW, SqlPreviewConfigurable.class));
    private static final SubPageDesc PAGE_TABLE_MAPPING = new SubPageDesc(
        "settings.section.tableMapping", "settings.section.tableMapping.desc", AllIcons.Nodes.DataTables,
        new SubPageRef(ID_TABLE_MAPPING, TableMappingConfigurable.class));
    private static final SubPageDesc PAGE_SQL_LOG = new SubPageDesc(
        "settings.section.sqlLog", "settings.section.sqlLog.desc", AllIcons.Nodes.Console,
        new SubPageRef(ID_SQL_LOG, SqlLogConfigurable.class));
    // T-ShortcutSettings：快捷键统一管理 + MCP 工具（跳转平台 MCP Server 设置 + 门控开关）
    private static final SubPageDesc PAGE_SHORTCUTS = new SubPageDesc(
        "settings.section.shortcuts", "settings.section.shortcuts.desc", AllIcons.Nodes.Plugin,
        new SubPageRef(ID_SHORTCUTS, ShortcutsConfigurable.class));
    private static final SubPageDesc PAGE_MCP = new SubPageDesc(
        "settings.section.mcp", "settings.section.mcp.desc", AllIcons.Nodes.Plugin,
        new SubPageRef(ID_MCP, McpConfigurable.class));
    private static final SubPageDesc PAGE_ABOUT = new SubPageDesc(
        "settings.section.about", "settings.section.about.desc", AllIcons.Nodes.Favorite,
        new SubPageRef(ID_ABOUT, AboutConfigurable.class));

    /** 落地页列表（顺序即呈现顺序）。 */
    private static final List<SubPageDesc> SUB_PAGES = List.of(
        PAGE_UI, PAGE_PREVIEW, PAGE_TABLE_MAPPING, PAGE_SQL_LOG, PAGE_SHORTCUTS, PAGE_MCP, PAGE_ABOUT);

    /** 所有可搜索的设置项字段 */
    private static final List<SearchableField> ALL_SEARCHABLE_FIELDS = buildSearchableFields();

    private static List<SearchableField> buildSearchableFields() {
        List<SearchableField> fields = new java.util.ArrayList<>();

        // SQL 预览
        SubPageRef p = PAGE_PREVIEW.ref;
        fields.add(new SearchableField(msg("settings.dialect"), "", "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.replaceParams"), msg("settings.replaceParams"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.formatSql"), msg("settings.formatSql"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.injectLogicDelete"), msg("settings.injectLogicDelete"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.logicDeleteValue"), msg("settings.logicDeleteValue.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.injectTenantLine"), msg("settings.injectTenantLine"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.tenantColumnName"), msg("settings.tenantColumnName.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.tenantColumnDefault"), msg("settings.tenantColumnDefault.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.injectTenantOnInsert"), msg("settings.injectTenantOnInsert.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.dataPermissionHint"), msg("settings.dataPermissionHint.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.dataPermissionMockCondition"), msg("settings.dataPermissionMockCondition.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.reverseWrapperFormat"), msg("settings.reverseWrapperFormat.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.reverseManualEntitySelection"), msg("settings.reverseManualEntitySelection.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.embeddedColumnNaming"), msg("settings.embeddedColumnNaming.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.verticalPairAnnotation"), "", "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.autoFold.enabled"), msg("settings.autoFold.enabled.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.autoFold.threshold"), msg("settings.autoFold.threshold.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.literalParamRecovery"), "", "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.preview.jdbc"), msg("settings.preview.jdbc.tip"), "settings.section.preview", p));

        // 表名映射
        SubPageRef t = PAGE_TABLE_MAPPING.ref;
        fields.add(new SearchableField(msg("settings.tablePrefix"), msg("settings.tablePrefix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.tableSuffix"), msg("settings.tableSuffix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.removeTablePrefix"), msg("settings.removeTablePrefix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.removeTableSuffix"), msg("settings.removeTableSuffix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.ignoredFields"), msg("settings.ignoredFields.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.tableNameInference"), msg("settings.tableNameInference.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.columnCalibration"), msg("settings.columnCalibration.tip"), "settings.section.tableMapping", t));

        // SQL 日志
        SubPageRef l = PAGE_SQL_LOG.ref;
        fields.add(new SearchableField(msg("settings.log.sqlPrefix"), msg("settings.log.sqlPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.paramPrefix"), msg("settings.log.paramPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.mixedPrefix"), msg("settings.log.mixedPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoCapture"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoOpen"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.stopOnToolWindowClose"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.timestamp"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.timestampFormat"), msg("settings.log.timestampFormat.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.formatSql"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.filterKeywords"), msg("settings.log.filterKeywords.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.dedupeWindow"), msg("settings.log.dedupeWindow.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.maxParseLength"), msg("settings.log.maxParseLength.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.maxCount"), msg("settings.log.maxCount.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.timePrefix"), msg("settings.log.timePrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.slowQueryThreshold"), msg("settings.log.slowQueryThreshold.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.hibernate.enabled"), msg("settings.log.hibernate.enabled.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.hibernate.sqlPrefix"), msg("settings.log.hibernate.sqlPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.hibernate.paramPrefix"), msg("settings.log.hibernate.paramPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.jdbc"), msg("settings.log.jdbc.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoSave"), msg("settings.log.autoSave.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoSave.path"), msg("settings.log.autoSave.path.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoSave.pattern"), msg("settings.log.autoSave.pattern.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoSave.maxFiles"), msg("settings.log.autoSave.maxFiles.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.timestampSource"), msg("settings.log.timestampSource.log.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("log.list.displayMode"), msg("log.list.displayMode.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.crudColor.style"), msg("settings.log.crudColor.style.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.enhancedCapture"), msg("settings.enhancedCapture.status"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.enhancedCapture.desensitize"), "", "settings.section.sqlLog", l));
        // R60 展示/导出链脱敏（T-SettingsSearchB 补登记）：与面板控件 displayMaskCheck 同文案键，
        // 搜「脱敏/展示/导出/打码」必须能翻到该开关；完整性由 SettingsPanelSearchableGuardTest 守卫
        fields.add(new SearchableField(msg("settings.display.mask"), msg("settings.display.mask.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.sourceFrameSkipPrefixes"), msg("settings.sourceFrameSkipPrefixes.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.sourceFrameBizJarPrefixes"), msg("settings.sourceFrameBizJarPrefixes.tip"), "settings.section.sqlLog", l));

        // 界面与操作
        SubPageRef u = PAGE_UI.ref;
        fields.add(new SearchableField(msg("settings.showGutterIcon"), "", "settings.section.ui", u));
        // O-72 T-XmlInlineSql：通用 XML 内嵌 SQL 检测开关（界面节）
        fields.add(new SearchableField(msg("settings.xmlInlineSql"), msg("settings.xmlInlineSql.tip"), "settings.section.ui", u));
        fields.add(new SearchableField(msg("settings.language"), "", "settings.section.ui", u));
        fields.add(new SearchableField(msg("settings.shortcut"), msg("settings.shortcut.tip"), "settings.section.ui", u));
        fields.add(new SearchableField(msg("settings.shortcut.sqlToJava"), "", "settings.section.ui", u));
        fields.add(new SearchableField(msg("settings.joinDiagramNative"), msg("settings.joinDiagramNative.tip"), "settings.section.ui", u));
        // T-PreviewWhitespaceToggle O-44a：空白符可视化开关（界面节，默认关=不设置该属性）
        fields.add(new SearchableField(msg("settings.editorShowWhitespaces"), msg("settings.editorShowWhitespaces.tip"), "settings.section.ui", u));

        // 关于
        SubPageRef a = PAGE_ABOUT.ref;
        fields.add(new SearchableField(msg("settings.cache.label"), msg("settings.cache.tip"), "settings.section.about", a));
        fields.add(new SearchableField(msg("settings.update.versionInfo"), "", "settings.section.about", a));
        fields.add(new SearchableField(msg("settings.buildNumber"), msg("settings.buildNumber.tip"), "settings.section.about", a));

        // T-ProjectSettingsScope P3：项目级设置页 11 项（总开关 + 10 项可覆盖字段；O-58 起新增
        // 列名数据源校正，索引条目与页面控件同批扩一项）。
        // 前置对照实验（ProjectPluginSettingsConfigurableTest 内探针）：项目上下文下该页在设置树里
        // （ConfigurableVisitor.findByType 走 Configurable.PROJECT_CONFIGURABLE，project=null 时为 null），
        // 故本页条目可跳转，不是 T-SettingsTruth P3 清理过的「幽灵搜索项」形态。
        // 文案键复用全局页同项（T-SettingsTruth 6.5 已把项目页标签收编为全局键），
        // 仅 pageLabelKey 指向项目页 displayName——用户在同一批关键词下同时看到两个作用域。
        SubPageRef pj = new SubPageRef(ID_PROJECT, ProjectPluginSettingsConfigurable.class);
        fields.add(new SearchableField(msg("settings.project.useProjectSettings"), msg("settings.project.useProjectSettings.tip"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.dialect"), "", "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.replaceParams"), msg("settings.replaceParams"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.formatSql"), msg("settings.formatSql"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.injectLogicDelete"), msg("settings.injectLogicDelete"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.logicDeleteValue"), msg("settings.logicDeleteValue.tip"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.tablePrefix"), msg("settings.tablePrefix.tip"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.tableSuffix"), msg("settings.tableSuffix.tip"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.tableNameInference"), msg("settings.tableNameInference.tip"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.ignoredFields"), msg("settings.ignoredFields.tip"), "configurable.settings.project.displayName", pj));
        fields.add(new SearchableField(msg("settings.columnCalibration"), msg("settings.columnCalibration.tip"), "configurable.settings.project.displayName", pj));

        return fields;
    }

    /**
     * 测试缝：登记的全部跳转目标（EP id → 目标类）。
     *
     * <p>供「id 与类指向同一设置页」守卫逐个断言——导航按 id 查，id 写错会静默跳到别处或跳不动，
     * 纯 UI 断言看不出来（本仓「幽灵搜索项」的同类风险）。</p>
     *
     * <p>移植注记：master 侧为 {@code internal}（KOTLIN_TO_JAVA_RULES §0.1：internal → public）；
     * Kotlin {@code List<Pair<String, Class<out Configurable>>>} → {@link kotlin.Pair}（与本树既有
     * {@code kotlin.Pair} 用法一致，保留 {@code distinct()} 的 equals 语义）。</p>
     */
    public static List<kotlin.Pair<String, Class<? extends Configurable>>> registeredTargetsForTest() {
        List<kotlin.Pair<String, Class<? extends Configurable>>> targets = new java.util.ArrayList<>();
        for (SubPageDesc page : SUB_PAGES) {
            targets.add(new kotlin.Pair<>(page.ref.id(), page.ref.cls()));
        }
        for (SearchableField field : ALL_SEARCHABLE_FIELDS) {
            targets.add(new kotlin.Pair<>(field.ref.id(), field.ref.cls()));
        }
        return targets.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    /**
     * 测试缝：登记的全部可搜索 label（T-SettingsSearchB 守卫用）。
     *
     * <p>「面板上有控件」与「搜索索引里有条目」两件事只靠本清单的维护纪律绑定——
     * SettingsPanelSearchableGuardTest 拿真实面板的每个输入型文本逐条到本结果里配对，
     * 漏登记即红。只比对 label 文本（搜索命中判定同源：label 或 tooltip 命中即可，
     * 本清单 tooltip 全部与控件 tooltip 同键，配对在 label 层已足够判漏）。</p>
     */
    public static List<String> registeredSearchLabelsForTest() {
        List<String> labels = new java.util.ArrayList<>();
        for (SearchableField field : ALL_SEARCHABLE_FIELDS) {
            labels.add(field.searchLabel);
        }
        return labels;
    }

    /**
     * T-SettingsSearchA：页内搜索与 IDE 设置搜索<b>共用的索引条目</b>（单一数据源）。
     *
     * <p>两套搜索读同一份清单，避免「面板加了控件、某一边索引忘了登记」的漂移在 IDE 搜索侧
     * 重演（B 那类缺陷的根因）。返回 (label, tooltip, 页显示名, EP id) 四元组：</p>
     * <ul>
     * <li>label/tooltip 喂 {@link com.intellij.ide.ui.search.SearchableOptionProcessor#addOptions}
     * 的 text/hit；</li>
     * <li>页显示名喂 configurableDisplayName（搜索结果分组显示）；</li>
     * <li>EP id 喂 configurableId（平台按 id 把命中映射回设置树节点，见 {@code filterById}）。</li>
     * </ul>
     *
     * <p>只做内存映射，无 IO/PSI——Contributor 在设置对话框初始化路径上执行（计划 §八）。</p>
     */
    public static List<IdeSearchEntry> ideSearchEntries() {
        List<IdeSearchEntry> entries = new java.util.ArrayList<>();
        for (SearchableField field : ALL_SEARCHABLE_FIELDS) {
            entries.add(new IdeSearchEntry(
                field.searchLabel,
                field.searchTooltip,
                MpSqlVisualizerBundle.message(field.pageLabelKey),
                field.ref.id()
            ));
        }
        return entries;
    }

    /** IDE 设置搜索条目（{@link #ideSearchEntries()} 的元素；四元组语义见该函数 KDoc）。 */
    public record IdeSearchEntry(String label, String tooltip, String pageDisplayName, String configurableId) {
    }

    private static String msg(String key) {
        return MpSqlVisualizerBundle.message(key);
    }
}
