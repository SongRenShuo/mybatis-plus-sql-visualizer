package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * SQL 日志面板布局 / 兼容性 UI 测试（F-44-5 全插件 UI 测试）：
 *
 * 用户反馈「布局错乱 / 不兼容」——工具栏是主战场：
 *  - 关键组件（SQL类型按钮、去重勾选、帮助动作组；捕捉开关 T-CaptureBack 起在面板工具栏首位）构建即存在；
 *  - 默认可见性正确（排序选择器恒可见、搜索条默认隐藏、无慢查询不显示徽章）；
 *  - **T-UiCoord F2（2026-09-21 用户定案「不要两行」）：工具栏恒为单行**——控制组实现高度恒等于
 *    其单行理想高度（FlowLayout 折行时实现高度会翻倍，这就是判据），且任意宽度下无越界。
 *    旧用例 `test窄侧边栏_WrapLayout_类型筛选按钮不被裁切` 已退役：它自建 holder 手工装配组件、
 *    且只对 holder 调 doLayout（Container.doLayout 不递归 → 读到的子件 bounds 是未布局的陈旧值），
 *    实际断言恒真——守的是假形态（本仓「守卫看不见它要防的站点」同族）。
 */
public class SqlLogPanelLayoutTest extends BasePlatformTestCase {

    private SqlLogManager manager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        manager = new SqlLogManager(getProject());
        manager.getEntries().setDedupeEnabled(false);
    }

    @Override
    protected void tearDown() throws Exception {
        manager.dispose();
        super.tearDown();
    }

    private static void waitForEdt() {
        UIUtil.dispatchAllInvocationEvents();
    }

    @Test
    public void testToolbarkeycomponentspresentdefaultvisibilitycorrect() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            waitForEdt();
            // 关键组件均存在（构建即无 NPE / 缺 i18n）
            assertNotNull("SQL类型按钮应存在", panel.getTypeFilterButtonForTest());
            assertNotNull("去重勾选框应存在", panel.getDedupeCheckBoxForTest());
            assertNotNull("搜索条应存在", panel.getSearchFieldForTest());
            // T-CaptureBack：捕捉开关回归面板工具栏首位（曾迁标题栏致入口不可见），
            // 工具栏组含 5 个动作；捕捉动作存在于组内且为捕捉态
            assertTrue("工具栏动作组应含 ≥5 个动作（捕捉/清空/设置/搜索/帮助）",
                    panel.toolbarGroup.getChildActionsOrStubs().length >= 5);
            assertTrue("捕捉开关动作应在工具栏组内（回迁面板工具栏）",
                    java.util.Arrays.asList(panel.toolbarGroup.getChildActionsOrStubs())
                            .contains(panel.getCaptureToggleAction()));

            // 默认可见性：排序动作并入左侧动作组（按钮退役，方案 A）；搜索条默认隐藏；慢查询徽章隐藏
            assertTrue("排序动作应在工具栏动作组内",
                    java.util.Arrays.asList(panel.toolbarGroup.getChildActionsOrStubs())
                            .contains(panel.getSortActionForTest()));
            assertEquals("搜索条默认隐藏", false, panel.getSearchFieldVisibleForTest());
            assertEquals("默认无慢查询，计数徽章应隐藏/为空", false,
                    panel.getSlowQueryLabelForTest() == null ? false : panel.getSlowQueryLabelForTest().isVisible());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test注入耗时信息_排序方式选择器保持可见() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(new LogSqlEntry(
                    "1723456789012|1500|statement|SELECT * FROM t",
                    "SELECT * FROM t",
                    List.of(),
                    1L,
                    null,
                    0,
                    1500L));
            waitForEdt();
            assertTrue("有耗时信息时排序动作仍在工具栏组内",
                    java.util.Arrays.asList(panel.toolbarGroup.getChildActionsOrStubs())
                            .contains(panel.getSortActionForTest()));
            assertEquals("默认排序方式为时间升序", SortMode.TIME_ASC, panel.getCurrentSortMode());
        } finally {
            panel.dispose();
        }
    }

    // ------------------------------------------------------------------
    // T-UiCoord F2：工具栏单行契约
    // ------------------------------------------------------------------

    /** 把生产工具栏行放进定宽 holder 按 NORTH 布局（NORTH 用理想高 → 行高即单行高），逐层 doLayout。 */
    private JPanel layoutRowAt(SqlLogPanel panel, int width) {
        JPanel row = panel.getToolbarRowForTest();
        JPanel holder = new JPanel(new BorderLayout());
        holder.setSize(new Dimension(width, 400));
        holder.add(row, BorderLayout.NORTH);
        layoutRecursively(holder);
        waitForEdt();
        return row;
    }

    /** Container.doLayout 不递归——必须逐层布局，否则子件 bounds 停在未布局状态（旧用例的假绿根因）。 */
    private void layoutRecursively(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container) layoutRecursively((Container) child);
        }
    }

    /** 控制组的可见子件垂直中心极差（同父坐标系直接可比）：单行应 ≤ 6px，折行会是 20px 以上。 */
    private int verticalBand(JPanel controls) {
        List<Integer> centers = new ArrayList<>();
        for (Component c : controls.getComponents()) {
            if (c.isVisible() && c.getWidth() > 0 && c.getHeight() > 0) {
                centers.add(c.getY() + c.getHeight() / 2);
            }
        }
        if (centers.isEmpty()) return 0;
        return Collections.max(centers) - Collections.min(centers);
    }

    /**
     * 控制组内可见子件分出的**行数**（按垂直中心聚类）。
     *
     * 两条必须写明的边界（2026-09-21 反向验证实测）：
     *  1. **不能拿「实现高度 == preferredSize 高度」当折行判据**——`WrapLayout.preferredLayoutSize`
     *     本就按折行后的多行高度上报（它存在的意义），拿它与实现高度对比对 WrapLayout 恒等，是空绿；
     *  2. **单测环境复现不出「旧结构折行」**：headless 下 `ActionToolbar` 不建按钮，
     *     `preferredSize` 为 0×0（真机量测约 133×26）——图标组不吃宽，旧的流式行在 460px 下反而放得下。
     *     故「一行放得下」由 {@link #test控制组最坏宽在预算内} 的宽度预算锁守，「折行」的真机确认走 runIde 走查。
     * 零尺寸子件（headless 的工具栏占位）不计入行带，否则其中心 0 会造成假红。
     */
    private int distinctRowCount(JPanel controls) {
        List<Integer> centers = new ArrayList<>();
        for (Component c : controls.getComponents()) {
            if (c.isVisible() && c.getWidth() > 0 && c.getHeight() > 0) {
                centers.add(c.getY() + c.getHeight() / 2);
            }
        }
        if (centers.isEmpty()) return 0;
        Collections.sort(centers);
        int rows = 1;
        int prev = centers.get(0);
        for (int i = 1; i < centers.size(); i++) {
            int c = centers.get(i);
            if (c - prev > 10) rows++;
            prev = c;
        }
        return rows;
    }

    /** 控制组内最高控件的理想高（单行行的上限基准；两行必然接近它的两倍）。 */
    private int tallestControlHeight(JPanel controls) {
        int tallest = 0;
        for (Component c : controls.getComponents()) {
            if (c.isVisible() && c.getHeight() > 0) {
                tallest = Math.max(tallest, c.getPreferredSize().height);
            }
        }
        return tallest;
    }

    @Test
    public void test工具栏恒为单行_用户宽度下() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 多选类型 → 按钮文案变长（旧流式行正是在这个状态下把「排序」挤到第二行）
            panel.applyTypeFilterForTest(Set.of(StatementType.SELECT, StatementType.UPDATE));
            waitForEdt();

            JPanel row = layoutRowAt(panel, USER_SIDEBAR_WIDTH);
            JPanel controls = panel.getToolbarControlsForTest();
            assertEquals(
                    "控制组应单行（实测 " + distinctRowCount(controls) + " 行）",
                    1,
                    distinctRowCount(controls));
            assertTrue("控制组内控件应共轴（垂直中心极差 " + verticalBand(controls) + "）",
                    verticalBand(controls) <= 6);
            int tallest = tallestControlHeight(controls);
            assertTrue(
                    "工具栏行高 " + row.getHeight() + " 应明显小于两行高（单行基准 " + tallest + "×2-8）",
                    row.getHeight() < tallest * 2 - 8);
            // 控制组右缘不越界（勾选在最右，不得被推出面板）
            int controlsRight = controls.getX() + controls.getWidth();
            assertTrue("控制组右缘 " + controlsRight + " 不应超出行宽 " + row.getWidth(),
                    controlsRight <= row.getWidth());
        } finally {
            panel.dispose();
        }
    }

    /**
     * 控制组最坏宽锁：类型多选（显示文本被预算封顶）即控制组的最坏形态。
     *
     * 上限 340 = 用户侧边栏 460 − 图标组保守下限 120（真机量测约 133，取更保守的 120）。
     * 本断言防的是「又往控制组加控件 / 又改长文案」——一旦超限，单行就会挤掉图标组，
     * 而 headless 复现不出挤掉过程（见 {@link #distinctRowCount} 边界 2），故必须用预算锁前置拦住。
     */
    @Test
    public void test控制组最坏宽在预算内() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            panel.applyTypeFilterForTest(
                    Set.of(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE));
            waitForEdt();
            JPanel controls = panel.getToolbarControlsForTest();
            int worst = controls.getPreferredSize().width;
            assertTrue(
                    "控制组最坏理想宽 " + worst + " 应 ≤ " + CONTROLS_WIDTH_BUDGET
                            + "（= 用户侧边栏 " + USER_SIDEBAR_WIDTH + " − 图标组保守下限 " + ICON_GROUP_WIDTH_MIN + "）",
                    worst <= CONTROLS_WIDTH_BUDGET);
        } finally {
            panel.dispose();
        }
    }

    /**
     * 控制组结构守卫：单行定构下「列表控制件在 EAST 控制组、动作图标组在 CENTER」。
     * 反向验证的落点——把旧流式行结构放回去时，本用例必红（控制组里会出现图标组与两个标签）。
     *
     * 图标组挂载断言是**实测漏点**：还原反向验证探针时曾把 `row.add(toolbar.component, CENTER)`
     * 一并删掉，而 headless 下工具栏理想宽 0×0、其余用例全绿——图标组整个没进行也无人报错。
     */
    @Test
    public void test控制组结构_含列表控制件且不含图标组() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            waitForEdt();
            JPanel row = panel.getToolbarRowForTest();
            JComponent toolbarComponent = panel.getToolbarComponentForTest();
            assertNotNull("动作图标组组件应存在", toolbarComponent);
            assertTrue(
                    "动作图标组必须挂在行宿主上（曾漏挂且全绿）",
                    java.util.Arrays.asList(row.getComponents()).contains(toolbarComponent));
            JPanel controls = panel.getToolbarControlsForTest();
            List<Component> children = new ArrayList<>();
            for (Component c : controls.getComponents()) {
                if (c.isVisible()) children.add(c);
            }
            assertTrue("控制组应含 SQL类型按钮", children.contains(panel.getTypeFilterButtonForTest()));
            List<AnAction> mainActions = new ArrayList<>();
            for (AnAction a : panel.toolbarGroup.getChildActionsOrStubs()) mainActions.add(a);
            int sortIndex = -1;
            for (int i = 0; i < mainActions.size(); i++) {
                if (mainActions.get(i) == panel.getSortActionForTest()) { sortIndex = i; break; }
            }
            assertTrue("排序动作应在工具栏动作组内", sortIndex >= 0);
            assertTrue("排序应排在滚动到底部之后、帮助之前（方案 A 中排序为操作组末位）",
                    sortIndex > 0 && sortIndex < mainActions.size() - 1);
            AnAction scrollAction = null;
            for (AnAction a : mainActions) { if (a == panel.getScrollToEndAction()) { scrollAction = a; break; } }
            assertNotNull(scrollAction);
            assertTrue("滚动到底部动作应排在排序之前",
                    mainActions.indexOf(scrollAction) < sortIndex);
            assertTrue("控制组应含 去重勾选（仍在最右）", children.contains(panel.getDedupeCheckBoxForTest()));
            assertEquals(
                    "去重勾选应在控制组最右",
                    panel.getDedupeCheckBoxForTest(),
                    children.get(children.size() - 1));
            assertFalse("动作图标组不应混进控制组（单行定构下它在 CENTER）", children.contains(toolbarComponent));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test工具栏宽度扫描_恒单行不折行() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            panel.applyTypeFilterForTest(Set.of(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE));
            waitForEdt();
            for (int width : List.of(400, USER_SIDEBAR_WIDTH, 600, 800)) {
                JPanel row = layoutRowAt(panel, width);
                JPanel controls = panel.getToolbarControlsForTest();
                assertEquals(
                        "宽度 " + width + "px 下控制组应仍为单行（实测 " + distinctRowCount(controls) + " 行）",
                        1,
                        distinctRowCount(controls));
                assertTrue(
                        "宽度 " + width + "px 下控制组不应越界（右缘 "
                                + (controls.getX() + controls.getWidth()) + " > " + row.getWidth() + "）",
                        controls.getX() + controls.getWidth() <= row.getWidth());
            }
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test慢查询徽章出现时仍为单行() throws Exception {
        long saved = PluginSettings.getInstance().getState().getSlowQueryThresholdMs();
        PluginSettings.getInstance().getState().setSlowQueryThresholdMs(1000);
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(new LogSqlEntry(
                    "SELECT slow FROM big_table",
                    "SELECT slow FROM big_table",
                    List.of(),
                    1L,
                    null,
                    0,
                    2000L));
            waitForEdt();
            JLabel label = panel.getSlowQueryLabelForTest();
            assertNotNull("慢查询徽章应存在", label);
            assertEquals("存在慢查询时徽章可见", true, label.isVisible());
            // T-UiCoord F2：全文「慢查询: N」改短徽章（复用 log.panel.slowQueryBadge），全文进 tooltip
            assertEquals(
                    "徽章应为短形态",
                    MpSqlVisualizerBundle.message("log.panel.slowQueryBadge") + " 1",
                    label.getText());
            assertEquals(
                    "tooltip 应为全文（信息不丢）",
                    MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", "1"),
                    label.getToolTipText());
            JPanel row = layoutRowAt(panel, USER_SIDEBAR_WIDTH);
            JPanel controls = panel.getToolbarControlsForTest();
            assertEquals(
                    "徽章在场时控制组仍为单行",
                    1,
                    distinctRowCount(controls));
            assertTrue("徽章在场时控制组不越界", controls.getX() + controls.getWidth() <= row.getWidth());
        } finally {
            PluginSettings.getInstance().getState().setSlowQueryThresholdMs(saved);
            panel.dispose();
        }
    }

    @Test
    public void test类型按钮文本封顶_多选后不撑宽() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            JButton btn = panel.getTypeFilterButtonForTest();
            // 三选（非全选——全选时按钮显示「全部」，不构成超预算文案）
            panel.applyTypeFilterForTest(
                    Set.of(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE));
            waitForEdt();

            String tip = btn.getToolTipText() == null ? "" : btn.getToolTipText();
            assertTrue(
                    "tooltip 应以「SQL类型：」标签键为前缀（标签控件退役后语义由它承载）",
                    tip.startsWith(MpSqlVisualizerBundle.message("log.filter.type.label")));
            for (String key : List.of("log.filter.select", "log.filter.update", "log.filter.delete")) {
                assertTrue("tooltip 应含 " + key + " 全文", tip.contains(MpSqlVisualizerBundle.message(key)));
            }
            assertTrue("多选组合应被截尾省略（显示文本「" + btn.getText() + "」）", btn.getText().endsWith("\u2026"));
            int width = btn.getFontMetrics(btn.getFont()).stringWidth(btn.getText());
            assertTrue(
                    "显示文本宽度 " + width + " 应 ≤ 预算 " + panel.getDropdownTextBudgetForTest(),
                    width <= panel.getDropdownTextBudgetForTest());
            assertTrue("显示文本应短于 tooltip 全文（省略只发生在显示层）", btn.getText().length() < tip.length());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test去标签后tooltip携带完整状态() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            waitForEdt();
            assertEquals(
                    "类型按钮 tooltip 应为「SQL类型：」+ 当前状态",
                    MpSqlVisualizerBundle.message("log.filter.type.label") + MpSqlVisualizerBundle.message("log.filter.all"),
                    panel.getTypeFilterButtonForTest().getToolTipText());
            assertEquals(
                    "排序文案应为当前方式（按钮退役后由动作 presentation 承载）",
                    MpSqlVisualizerBundle.message("log.sort.time.asc"),
                    panel.getSortButtonTextForTest());
            // 排序按钮文案在预算内，不应被省略（省略只对超预算的组合生效）
            assertEquals("时间升序", panel.getSortButtonTextForTest());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void testSwitchhistorytabhistorypanelassembles() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            waitForEdt();
            File dir = new File(getProject().getBasePath() + File.separator + ".idea"
                    + File.separator + SqlLogAutoSaver.DATA_DIR_NAME
                    + File.separator + "sql-history");
            dir.mkdirs();
            File histFile = new File(dir, "sql-history-2026-08-31.sql");
            try (FileWriter w = new FileWriter(histFile)) {
                w.write("-- source: A.java:1 | time: 1ms | 2026-08-31 17:30:00.000 --\nSELECT 1\n\n");
            }
            panel.dispose();
            SqlLogPanel panel2 = new SqlLogPanel(getProject(), manager);
            waitForEdt();
            // O-149：页签容器 JTabbedPane → 平台 JBTabs（tabsForTest 出口）
            int tabCount = panel2.tabsForTest().getTabCount();
            assertTrue("含历史/转换等 tab，tab 数应 ≥ 3", tabCount >= 3);
            panel2.dispose();
        } finally {
            panel.dispose();
        }
    }

    /** 用户日常侧边栏宽度（2026-09-21 真机截图量测：内容区约 460px）——单行契约的主验宽度。 */
    private static final int USER_SIDEBAR_WIDTH = 460;

    /** 图标组宽度的**保守下限**（真机量测约 133：5 枚图标 + 工具栏内边距）。 */
    private static final int ICON_GROUP_WIDTH_MIN = 120;

    /** 控制组理想宽上限 = {@link #USER_SIDEBAR_WIDTH} − {@link #ICON_GROUP_WIDTH_MIN}（超了单行就会挤掉图标组）。 */
    private static final int CONTROLS_WIDTH_BUDGET = USER_SIDEBAR_WIDTH - ICON_GROUP_WIDTH_MIN;
}
