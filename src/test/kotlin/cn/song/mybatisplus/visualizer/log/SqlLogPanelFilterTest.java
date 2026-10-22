package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SqlLogPanel 过滤/搜索/排序集成测试（2026-08-27 筛选项精简改造，覆盖管线逻辑）。
 *
 * 测试策略：核心管线抽为顶层纯函数（filterByType / filterBySearch），直接注入条目断言；
 * 排序委托 LogDurationParser（已有独立单测）。原高频折叠测试随折叠功能移除而删除。
 */
public class SqlLogPanelFilterTest extends BasePlatformTestCase {

    private SqlLogManager manager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        manager = new SqlLogManager(getProject());
        // 管线测试需要重复 SQL 场景，关闭去重避免相同 SQL 被丢弃
        manager.getEntries().setDedupeEnabled(false);
    }

    @Override
    protected void tearDown() throws Exception {
        manager.dispose();
        super.tearDown();
    }

    /** 构造指定类型的 LogSqlEntry。 */
    private LogSqlEntry entryOfType(StatementType type) {
        return entryOfType(type, null, 0L);
    }

    private LogSqlEntry entryOfType(StatementType type, String sql) {
        return entryOfType(type, sql, 0L);
    }

    private LogSqlEntry entryOfType(StatementType type, String sql, long ts) {
        String actualSql = sql != null ? sql : switch (type) {
            case SELECT -> "SELECT * FROM t";
            case INSERT -> "INSERT INTO t VALUES (1)";
            case UPDATE -> "UPDATE t SET x = 1";
            case DELETE -> "DELETE FROM t";
            case OTHER -> "TRUNCATE TABLE t";
        };
        return new LogSqlEntry(actualSql, actualSql, List.of(), ts);
    }

    private static List<String> restoredSqls(List<LogSqlEntry> entries) {
        return entries.stream().map(LogSqlEntry::restoredSql).collect(Collectors.toList());
    }

    // ---------- 类型过滤（filterByType 纯函数） ----------

    @Test
    public void testFilterSelectOnly() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT 1"),
                entryOfType(StatementType.INSERT, "INSERT 1"),
                entryOfType(StatementType.UPDATE, "UPDATE 1"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(entries, Set.of(StatementType.SELECT));
        assertEquals(List.of("SELECT 1"), restoredSqls(filtered));
    }

    @Test
    public void testFilterMultiType() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT 1"),
                entryOfType(StatementType.INSERT, "INSERT 1"),
                entryOfType(StatementType.UPDATE, "UPDATE 1"),
                entryOfType(StatementType.DELETE, "DELETE 1"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(entries,
                Set.of(StatementType.SELECT, StatementType.UPDATE));
        assertEquals(List.of("SELECT 1", "UPDATE 1"), restoredSqls(filtered));
    }

    @Test
    public void testFilterAllPassesOtherThrough() throws Exception {
        // 全选（= FILTERABLE_TYPES 全集）时 OTHER 透传——修正原筛选条 OTHER 被隐性过滤的缺陷
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT 1"),
                entryOfType(StatementType.OTHER, "TRUNCATE TABLE t"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(entries,
                new HashSet<>(SqlLogPanelKt.FILTERABLE_TYPES));
        assertEquals(2, filtered.size());
    }

    @Test
    public void testFilterPartialHidesOther() throws Exception {
        // 非全选时 OTHER 不透传（不在可筛选集合中）
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT 1"),
                entryOfType(StatementType.OTHER, "TRUNCATE TABLE t"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(entries, Set.of(StatementType.SELECT));
        assertEquals(List.of("SELECT 1"), restoredSqls(filtered));
    }

    @Test
    public void testFilterEmptySelectionShowsNothing() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT 1"),
                entryOfType(StatementType.INSERT, "INSERT 1"));
        assertTrue(SqlLogPanelKt.filterByType(entries, Set.of()).isEmpty());
    }

    // ---------- 搜索过滤（filterBySearch 纯函数） ----------

    @Test
    public void testSearchEmptyQueryPassesAll() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT * FROM user"),
                entryOfType(StatementType.SELECT, "SELECT * FROM orders"));
        assertEquals(2, SqlLogPanelKt.filterBySearch(entries, "").size());
        assertEquals(2, SqlLogPanelKt.filterBySearch(entries, "   ").size()); // 纯空白同空
    }

    @Test
    public void testSearchCaseInsensitive() throws Exception {
        List<LogSqlEntry> entries = List.of(entryOfType(StatementType.SELECT, "SELECT * FROM user"));
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "USER").size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "select").size());
    }

    @Test
    public void testSearchMatchesParameterValue() throws Exception {
        // 摘要（restoredSql 首行截断）含参数还原值——按订单号等参数值检索是常见场景
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM orders WHERE id = ?",
                "SELECT * FROM orders WHERE id = 'NO-20260827-001'",
                List.of(), 0L);
        assertEquals(1, SqlLogPanelKt.filterBySearch(List.of(entry), "NO-20260827-001").size());
        assertEquals(0, SqlLogPanelKt.filterBySearch(List.of(entry), "NO-99999").size());
    }

    @Test
    public void testSearchCombinedWithTypeFilter() throws Exception {
        // 过滤 + 搜索叠加：先类型后搜索（与面板 refreshList 顺序一致）
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT * FROM user"),
                entryOfType(StatementType.INSERT, "INSERT INTO user VALUES (1)"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(entries, Set.of(StatementType.SELECT));
        List<LogSqlEntry> searched = SqlLogPanelKt.filterBySearch(filtered, "user");
        assertEquals(List.of("SELECT * FROM user"), restoredSqls(searched));
    }

    // ---------- 耗时排序（委托 LogDurationParser） ----------

    @Test
    public void testDurationSortHiddenWithoutDurationInfo() throws Exception {
        List<LogSqlEntry> snapshot = List.of(
                entryOfType(StatementType.SELECT, "SELECT 1"),
                entryOfType(StatementType.SELECT, "SELECT 2"));
        boolean any = snapshot.stream().anyMatch(LogDurationParser::hasDurationInfo);
        assertFalse(any);
    }

    @Test
    public void testDurationSortOrderDesc() throws Exception {
        LogSqlEntry e1 = new LogSqlEntry(
                "1723456789012|100|statement|SELECT * FROM a", "SELECT * FROM a", List.of(), 0L);
        LogSqlEntry e2 = new LogSqlEntry(
                "1723456789012|500|statement|SELECT * FROM b", "SELECT * FROM b", List.of(), 0L);
        LogSqlEntry e3 = new LogSqlEntry(
                "1723456789012|300|statement|SELECT * FROM c", "SELECT * FROM c", List.of(), 0L);
        List<LogSqlEntry> snapshot = List.of(e1, e2, e3);
        List<LogSqlEntry> sorted = new ArrayList<>(snapshot);
        sorted.sort(Comparator.comparingLong((LogSqlEntry e) -> {
            Long p = LogDurationParser.INSTANCE.parse(e);
            return p == null ? -1L : p;
        }).reversed());
        assertEquals("SELECT * FROM b", sorted.get(0).restoredSql()); // 500ms
        assertEquals("SELECT * FROM c", sorted.get(1).restoredSql()); // 300ms
        assertEquals("SELECT * FROM a", sorted.get(2).restoredSql()); // 100ms
    }

    // ---------- 缓冲层去重计数联动（面板徽章数据源） ----------

    @Test
    public void testDedupeBadgeDataFromBuffer() throws Exception {
        manager.getEntries().setDedupeEnabled(true);
        manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", 0L));
        manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", 100L)); // 丢弃
        manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", 200L)); // 再丢弃
        // 等待 EDT 刷新（面板 UI 监听器经 invokeLater）
        waitForEdt();
        java.util.Map<String, Integer> counts = manager.getEntries().dedupeCountsSnapshot();
        // T-Perf-19：快照键为 SQL 指纹（非原文）
        assertEquals(Integer.valueOf(2), counts.get(LogSqlModelsKt.sqlFingerprint("SELECT * FROM t WHERE id = 1")));
    }

    @Test
    public void testClearResetsBadgeCounts() throws Exception {
        manager.getEntries().setDedupeEnabled(true);
        manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT dup", 0L));
        manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT dup", 100L)); // 丢弃
        assertEquals(1, manager.getEntries().dedupeCount("SELECT dup"));
        manager.getEntries().clear();
        assertEquals(0, manager.getEntries().dedupeCount("SELECT dup"));
    }

    // ---------- PluginSettings 默认值（改造后残留状态检查） ----------

    @Test
    public void testPluginSettingsDefaultsAfterCleanup() throws Exception {
        // 去重开关在 buffer 层（SqlLogEntryBuffer.dedupeEnabled 默认 true，T-M31.13），
        // 不再断言 PluginSettings（筛选条相关字段已随精简改造移除）
        boolean enabled = PluginSettings.getInstance().getState().getLogAutoSaveEnabled();
        assertFalse("历史自动保存默认关闭", enabled);
    }

    // ---------- M56 T-M56-00：聚焦过滤层（filterByFocus 纯函数，插 filterByType→filterBySearch 之间） ----------

    /** 构造带扩展字段的条目。 */
    private static LogSqlEntry focusedEntry(String sql, String mapperStatement, String requestTag) {
        return new LogSqlEntry(sql, sql, List.of(), System.currentTimeMillis(), null,
                LogSqlEntry.summarizeSql(sql), SqlSource.UNKNOWN, null, null, 0, List.of(), List.of(), null, 0L,
                mapperStatement, null, requestTag, null, null);
    }

    private static LogSqlEntry focusedEntry(String sql) {
        return focusedEntry(sql, null, null);
    }

    @Test
    public void testFocusNullPassthrough() throws Exception {
        List<LogSqlEntry> entries = List.of(focusedEntry("SELECT 1"), focusedEntry("SELECT 2"));
        assertEquals(entries, SqlLogPanelKt.filterByFocus(entries, null));
        // 全空维度等价不过滤
        assertEquals(entries, SqlLogPanelKt.filterByFocus(entries, new SqlLogPanelKt.FocusFilter(null, null)));
    }

    @Test
    public void testFocusByMapperStatement() throws Exception {
        List<LogSqlEntry> entries = List.of(
            focusedEntry("SELECT 1", "com.x.UserMapper.selectById", null),
            focusedEntry("SELECT 2", "com.x.OrderMapper.list", null),
            focusedEntry("SELECT 3"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByFocus(entries,
            new SqlLogPanelKt.FocusFilter("com.x.UserMapper.selectById", null));
        assertEquals(List.of("SELECT 1"), filtered.stream().map(LogSqlEntry::restoredSql).toList());
    }

    @Test
    public void testFocusMethodIgnoreCase() throws Exception {
        List<LogSqlEntry> entries = List.of(focusedEntry("SELECT 1", "com.x.UserMapper.selectById", null));
        assertEquals(
            entries,
            SqlLogPanelKt.filterByFocus(entries,
                new SqlLogPanelKt.FocusFilter("COM.X.USERMAPPER.SELECTBYID", null)));
    }

    @Test
    public void testFocusByRequestTag() throws Exception {
        List<LogSqlEntry> entries = List.of(
            focusedEntry("SELECT 1", null, "req-A"),
            focusedEntry("SELECT 2", null, "req-B"),
            focusedEntry("SELECT 3", null, "req-A"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByFocus(entries,
            new SqlLogPanelKt.FocusFilter(null, "req-A"));
        assertEquals(List.of("SELECT 1", "SELECT 3"), filtered.stream().map(LogSqlEntry::restoredSql).toList());
    }

    @Test
    public void testFocusMethodAndTagConjunctive() throws Exception {
        List<LogSqlEntry> entries = List.of(
            focusedEntry("SELECT 1", "com.x.A.get", "req-A"),
            focusedEntry("SELECT 2", "com.x.B.get", "req-A"),
            focusedEntry("SELECT 3", "com.x.A.get", "req-B"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByFocus(entries,
            new SqlLogPanelKt.FocusFilter("com.x.A.get", "req-A"));
        assertEquals(List.of("SELECT 1"), filtered.stream().map(LogSqlEntry::restoredSql).toList());
    }

    @Test
    public void testFocusFilterPipelineOrderWithTypeAndSearch() throws Exception {
        // 管线叠加：filterByType → filterByFocus → filterBySearch 与 refreshList 一致
        List<LogSqlEntry> entries = List.of(
            focusedEntry("SELECT alpha", "com.x.A.get", null),
            focusedEntry("SELECT alpha2", "com.x.B.get", null),
            focusedEntry("UPDATE alpha3", "com.x.A.get", null));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByFocus(
            SqlLogPanelKt.filterByType(entries, java.util.Set.of(StatementType.SELECT)),
            new SqlLogPanelKt.FocusFilter("com.x.A.get", null));
        List<LogSqlEntry> searched = SqlLogPanelKt.filterBySearch(filtered, "alpha");
        assertEquals(List.of("SELECT alpha"), searched.stream().map(LogSqlEntry::restoredSql).toList());
    }

    // ---------- T-SqlSuppress（O-69）：屏蔽规则存量过滤 ----------

    /** 唯一的心跳 SQL 常量（测试间经 manager 清理隔离，防 APP 级单例串台）。 */
    private final String heartbeatSql = "SELECT HEARTBEAT_69";

    public void testSuppressEmptyKeysPassThrough() throws Exception {
        List<LogSqlEntry> entries = List.of(entryOfType(StatementType.SELECT, "SELECT 1"));
        assertEquals(entries, SqlLogPanelKt.filterBySuppress(entries, Set.of()));
    }

    public void testSuppressFiltersMatchingDedupeKeyOnly() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, heartbeatSql),
                entryOfType(StatementType.SELECT, "SELECT 2"),
                // 空白变体同指纹（与去重/屏蔽同一规范化口径）——同样被滤
                entryOfType(StatementType.SELECT, "  " + heartbeatSql + "  "));
        Set<String> keys = Set.of(LogSqlModelsKt.suppressFingerprint(heartbeatSql));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterBySuppress(entries, keys);
        assertEquals(List.of("SELECT 2"), filtered.stream().map(LogSqlEntry::restoredSql).toList());
    }

    public void testSuppressRemoveRuleEntryComesBack() throws Exception {
        Set<String> keys = Set.of(LogSqlModelsKt.suppressFingerprint(heartbeatSql));
        List<LogSqlEntry> entries = List.of(entryOfType(StatementType.SELECT, heartbeatSql));
        assertEquals(0, SqlLogPanelKt.filterBySuppress(entries, keys).size());
        assertEquals("移除规则（空集）后复现", entries, SqlLogPanelKt.filterBySuppress(entries, Set.of()));
    }

    /**
     * 全链路（§五 验收 3 / §四 前提 2）：缓冲中已有心跳行 → 注册规则 → refreshList 输出不含匹配行；
     * 移除规则后复现。真链路：SqlSuppressRuleManager（APP 级）通知 → SqlLogManager 同步缓冲键集
     * + notifyUi → 面板 refreshList（规则变更必须显式触发——差分只认缓冲快照变化）。
     */
    public void testRegisterRuleHidesExistingHeartbeatRowsViaPanelRefresh() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        SqlSuppressRuleManager suppressManager = SqlSuppressRuleManager.getInstance();
        try {
            manager.getEntries().addEntry(entryOfType(StatementType.SELECT, heartbeatSql, 0L));
            manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT 2", 1L));
            pumpEdt();
            assertEquals("屏蔽前 2 条全部展示", 2, panel.getVisibleEntryCount());

            suppressManager.addRule(heartbeatSql);
            pumpEdt();
            assertEquals("注册规则后心跳行立即消失（前提 2 显式刷新已接通）", 1, panel.getVisibleEntryCount());
            assertEquals(List.of("SELECT 2"), panel.getVisibleEntries());
            assertTrue(
                    "缓冲仍在（只是隐藏——删除规则后可复现）",
                    manager.getEntries().snapshot().stream().anyMatch(it -> it.restoredSql().equals(heartbeatSql)));

            suppressManager.removeRule(suppressManager.getRules().stream()
                    .filter(it -> it.getPattern().equals(heartbeatSql)).findFirst().orElseThrow().getId());
            pumpEdt();
            assertEquals("移除规则后存量行复现", 2, panel.getVisibleEntryCount());
        } finally {
            // APP 级单例跨测试类共享（分片 JVM 复用）——必须清理，防后续测试类被殃及
            suppressManager.clearRules();
            panel.dispose();
        }
    }

    /**
     * 泵空 EDT 队列（DedupeToggleTest 同款）：manager.notifyUi 用 invokeLater 调度刷新，
     * headless 测试必须 dispatchAllInvocationEvents 才能让列表刷新执行（invokeAndWait 空任务不够）。
     */
    private void pumpEdt() {
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
    }

    /** 等待 EDT 调度完成。 */
    private void waitForEdt() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
        });
    }
}
