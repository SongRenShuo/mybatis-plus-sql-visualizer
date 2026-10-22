package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JLabel;
import java.util.List;

/**
 * SQL 日志面板交互 UI 测试（真实点击驱动，headless 断言）：
 *  - 耗时排序按钮点击 → 切换 DURATION_DESC 且列表按耗时降序；再次点击回 NONE（F-44-2 耗时排序用户交互）
 *  - 慢查询计数标签：注入超过阈值的耗时条目 → 标签可见且文案含慢查询条数（O-1 慢查询计数 UI）
 *
 * 注意：[LogDurationParser.parse] 从 rawSql 的 p6spy/druid 格式取耗时，[SlowQueryCounter] 取
 * executionTimeMs 字段；两者在桥层一致，测试里同时设置以保证排序可见与计数正确。
 */
public class SqlLogPanelSortAndSlowQueryTest extends BasePlatformTestCase {

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

    /** 构造条目：rawSql 走 p6spy 格式（第二段=耗时）以保证排序解析命中；executionTimeMs 同步设置。 */
    private static LogSqlEntry entry(String sql, long ms, long ts) {
        return new LogSqlEntry(
            "1723456789012|" + ms + "|statement|" + sql,
            sql,
            List.<LogSqlParam>of(),
            ts,
            null,
            0,
            ms
        );
    }

    @Test
    public void test排序方式选择器_默认时间升序_切耗时降序再回时间升序() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entry("SELECT a FROM t", 50L, 100L));
            manager.getEntries().addEntry(entry("SELECT b FROM t", 2000L, 200L));
            manager.getEntries().addEntry(entry("SELECT c FROM t", 300L, 300L));
            waitForEdt();

            assertEquals("默认排序方式应为时间升序", SortMode.TIME_ASC, panel.getCurrentSortMode());
            assertEquals("时间升序：入列顺序 a,b,c", "SELECT a FROM t", panel.getVisibleEntries().get(0));

            // 选中「耗时降序」→ 2000(b) > 300(c) > 50(a)
            panel.selectSortModeForTest(SortMode.DURATION_DESC);
            waitForEdt();
            assertEquals("选择后排序模式 DURATION_DESC", SortMode.DURATION_DESC, panel.getCurrentSortMode());
            assertEquals("降序首条应为 2000ms 的 b", "SELECT b FROM t", panel.getVisibleEntries().get(0));
            assertEquals("降序次条应为 300ms 的 c", "SELECT c FROM t", panel.getVisibleEntries().get(1));
            assertEquals("降序末条应为 50ms 的 a", "SELECT a FROM t", panel.getVisibleEntries().get(2));

            // 切回「时间升序」
            panel.selectSortModeForTest(SortMode.TIME_ASC);
            waitForEdt();
            assertEquals("回时间升序", SortMode.TIME_ASC, panel.getCurrentSortMode());
            assertEquals("回时间升序首条为 a", "SELECT a FROM t", panel.getVisibleEntries().get(0));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test排序方式_时间降序_耗时升序() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entry("SELECT a FROM t", 50L, 100L));
            manager.getEntries().addEntry(entry("SELECT b FROM t", 2000L, 200L));
            manager.getEntries().addEntry(entry("SELECT c FROM t", 300L, 300L));
            waitForEdt();

            panel.selectSortModeForTest(SortMode.TIME_DESC);
            waitForEdt();
            assertEquals("时间降序首条应为最晚的 c", "SELECT c FROM t", panel.getVisibleEntries().get(0));
            assertEquals("时间降序末条应为最早的 a", "SELECT a FROM t", panel.getVisibleEntries().get(2));

            panel.selectSortModeForTest(SortMode.DURATION_ASC);
            waitForEdt();
            assertEquals("耗时升序首条应为 50ms 的 a", "SELECT a FROM t", panel.getVisibleEntries().get(0));
            assertEquals("耗时升序末条应为 2000ms 的 b", "SELECT b FROM t", panel.getVisibleEntries().get(2));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test排序方式_行号() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entry("SELECT a FROM t", 50L, 300L));
            manager.getEntries().addEntry(entry("SELECT b FROM t", 2000L, 100L));
            waitForEdt();
            // 行号 = 入列序（a 是 #1、b 是 #2），与时间无关
            panel.selectSortModeForTest(SortMode.SEQUENCE);
            waitForEdt();
            assertEquals("行号序首条应为先入列的 a", "SELECT a FROM t", panel.getVisibleEntries().get(0));
            assertEquals("行号序次条应为 b", "SELECT b FROM t", panel.getVisibleEntries().get(1));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test排序方式按钮_恒可见且含全部选项() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 无任何耗时信息时排序方式仍可见（默认时间升序恒可用，不再按耗时显隐）
            manager.getEntries().addEntry(new LogSqlEntry("SELECT 1", "SELECT 1", List.<LogSqlParam>of(), 1L));
            waitForEdt();
            assertTrue("排序动作应在工具栏动作组内",
                    java.util.Arrays.asList(panel.toolbarGroup.getChildActionsOrStubs())
                            .contains(panel.getSortActionForTest()));
            assertEquals("默认按钮文案应为时间升序", "时间升序", panel.getSortButtonTextForTest());
            assertEquals(
                "菜单应含七种排序方式且时间升序居首（O-148 补同结构聚类与按库分组）",
                List.of("时间升序", "时间降序", "耗时升序", "耗时降序", "行号", "同结构聚类", "按目标库分组"),
                panel.getSortMenuLabelsForTest());
        } finally {
            panel.dispose();
        }
    }

    /** agent 通道形态：耗时只在结构化 executionTimeMs，SQL 文本内无耗时。 */
    private static LogSqlEntry agentEntry(String sql, long ms, long ts) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), ts, null,
                LogSqlEntry.summarizeSql(sql), SqlSource.AGENT, ms, null, 0, List.of(), List.of(), null, 0L,
                null, null, null, null, null);
    }

    @Test
    public void test增强通道条目_仅结构化耗时_可按耗时排序() throws Exception {
        // agent 通道形态：耗时只在结构化 executionTimeMs，SQL 文本内无耗时（旧实现仅看文本解析
        // → 排序取值全空；本用例锁死该回归）
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(agentEntry("SELECT a FROM t", 12L, 100L));
            manager.getEntries().addEntry(agentEntry("SELECT b FROM t", 480L, 200L));
            manager.getEntries().addEntry(agentEntry("SELECT c FROM t", 3L, 300L));
            waitForEdt();

            panel.selectSortModeForTest(SortMode.DURATION_DESC);
            waitForEdt();
            assertEquals("降序首条应为 480ms 的 b", "SELECT b FROM t", panel.getVisibleEntries().get(0));
            assertEquals("降序次条应为 12ms 的 a", "SELECT a FROM t", panel.getVisibleEntries().get(1));
            assertEquals("降序末条应为 3ms 的 c", "SELECT c FROM t", panel.getVisibleEntries().get(2));

            panel.selectSortModeForTest(SortMode.DURATION_ASC);
            waitForEdt();
            assertEquals("升序首条应为 3ms 的 c", "SELECT c FROM t", panel.getVisibleEntries().get(0));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test注入慢查询_工具栏计数标签可见且含条数() throws Exception {
        long saved = PluginSettings.getInstance().getState().getSlowQueryThresholdMs();
        PluginSettings.getInstance().getState().setSlowQueryThresholdMs(1000);
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 一条慢查询（2000ms >= 1000）+ 一条正常（50ms）
            manager.getEntries().addEntry(entry("SELECT slow FROM big_table", 2000L, 100L));
            manager.getEntries().addEntry(entry("SELECT fast FROM t", 50L, 200L));
            waitForEdt();

            JLabel label = panel.getSlowQueryLabelForTest();
            assertNotNull("慢查询标签应存在", label);
            assertEquals("存在慢查询，标签应可见", true, label.isVisible());
            assertTrue("标签文案应含慢查询条数 1", label.getText().contains("1"));
        } finally {
            PluginSettings.getInstance().getState().setSlowQueryThresholdMs(saved);
            panel.dispose();
        }
    }

    @Test
    public void test无慢查询_工具栏计数标签隐藏() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entry("SELECT ok FROM t", 50L, 100L));
            waitForEdt();
            JLabel label = panel.getSlowQueryLabelForTest();
            assertEquals("无慢查询，标签应隐藏", false, label.isVisible());
        } finally {
            panel.dispose();
        }
    }
}
