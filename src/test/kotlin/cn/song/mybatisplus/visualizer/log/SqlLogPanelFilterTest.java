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
            case CREATE -> "CREATE TABLE t (id INT)";
            case DROP -> "DROP TABLE t";
            case OTHER -> "MERGE INTO t USING s ON (1=1)";
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

    // ---------- T-HistorySearchToggles：MatchCase 大小写开关（filterBySearch matchCase 参数） ----------

    @Test
    public void testSearchMatchCaseOnIsCaseSensitive() throws Exception {
        List<LogSqlEntry> entries = List.of(entryOfType(StatementType.SELECT, "SELECT * FROM user"));
        // 开（敏感）：小写查询不命中大写 SQL；关（默认）：忽略大小写照旧
        // k2j：master 单一全默认参函数在 Java 侧拆为 (matchCase, regexEnabled, memo) 5 参重载
        assertEquals(0, SqlLogPanelKt.filterBySearch(entries, "select", true, false, new HistoryRegexMemo()).size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "select", false, false, new HistoryRegexMemo()).size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "select").size()); // 免注入重载 = 忽略大小写
    }

    @Test
    public void testSearchMatchCaseRowNumberUnaffected() throws Exception {
        // 行号 `#N` 精确 equals 不受大小写旗影响（数字无大小写，契约锁死）
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of(), 0L).withSequenceNumber(5L);
        assertEquals(1, SqlLogPanelKt.filterBySearch(List.of(entry), "#5", true, false, new HistoryRegexMemo()).size());
    }

    /**
     * 时间目标同样随 matchCase 旗。
     *
     * k2j 重载面注：master 的 filterBySearch 是单一全默认参函数（matchCase × timestampTextOf 可任意组合），
     * Java 承载拆为三个重载后**无 matchCase+timestampTextOf 组合缝**——本用例拆两段锁语义：
     * ①注入时间片段（3 参版，matchCase 缺省=忽略大小写）验证时间维度参与匹配；
     * ②5 参版（真实设置）在时间开关关闭时时间维度不参与。组合缝缺口登记主控评估补重载。
     */
    @Test
    public void testSearchMatchCaseTimestampTarget() throws Exception {
        List<LogSqlEntry> entries = List.of(entryOfType(StatementType.SELECT, "SELECT 1"));
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "Jan-02",
                e -> "[2026-Jan-02 10:30:00]").size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "JAN-02",
                e -> "[2026-Jan-02 10:30:00]").size());
        // 时间开关关闭（真实 timestampDisplayText → null）：时间维度不参与，大小写旗只作用于 SQL 两维
        PluginSettings.getInstance().getState().setLogTimestampEnabled(false);
        try {
            assertEquals(0, SqlLogPanelKt.filterBySearch(entries, "Jan-02", true, false, new HistoryRegexMemo()).size());
            assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "select", false, false, new HistoryRegexMemo()).size());
        } finally {
            PluginSettings.getInstance().getState().setLogTimestampEnabled(true);
        }
    }

    /**
     * 面板接线（开关 → refreshList → filterBySearch(matchCase)）：toggle 点击即生效；
     * headless 下防抖 Timer 不可控，搜索文本经 applySearchTextForTest 同步应用。
     */
    @Test
    public void testMatchCaseToggleDrivesPanelFiltering() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM USER", 0L));
            pumpEdt();
            panel.getSearchFieldForTest().setText("user");
            panel.applySearchTextForTest();
            assertEquals("默认关=忽略大小写，命中", 1, panel.getVisibleEntryCount());
            panel.setMatchCaseToggleForTest(!panel.getMatchCaseToggleForTest()); // 等价用户点击：翻转选中态并触发刷新
            assertEquals("开关开=敏感，user 不命中 USER", 0, panel.getVisibleEntryCount());
            panel.setMatchCaseToggleForTest(!panel.getMatchCaseToggleForTest());
            assertEquals("再点关闭恢复命中", 1, panel.getVisibleEntryCount());
        } finally {
            panel.dispose();
        }
    }

    // ---------- T-SearchToggleNativeLook：Regex 正则开关（20261010 用户令日志页补正则，D133 翻案） ----------

    @Test
    public void testSearchRegexToggleMatchesPattern() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"),
                entryOfType(StatementType.UPDATE, "UPDATE t SET a = 1"));
        // 开关正则：按 pattern 语义命中（\d = 数字），两维 restoredSql/summary 参与
        assertEquals(2, SqlLogPanelKt.filterBySearch(entries, "id = \\d|SET a = \\d", false, true, new HistoryRegexMemo()).size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "FROM user WHERE", false, true, new HistoryRegexMemo()).size());
        // 锚点+大小写：`^select` 敏感态不命中大写开头；忽略态命中
        assertEquals(0, SqlLogPanelKt.filterBySearch(entries, "^select", true, true, new HistoryRegexMemo()).size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "^select", false, true, new HistoryRegexMemo()).size());
    }

    @Test
    public void testSearchRegexOffKeepsSubstringSemantics() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"));
        // 关（默认）：正则元字符按字面子串匹配（现状语义零变化）
        assertEquals(0, SqlLogPanelKt.filterBySearch(entries, "id = \\d").size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "id = ").size());
    }

    @Test
    public void testSearchRegexInvalidFallsBackToSubstring() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"),
                entryOfType(StatementType.SELECT, "bad [ pattern row"));
        // 非法正则 fail-closed：回退子串（查询为原文），restoredSql 维度恰一行含 "["
        // （k2j 适配：master 注入 timestampTextOf=null 隔离时间片段；Java 重载无该组合缝，
        //  改用真实设置关闭时间开关——timestampDisplayText → null，等价隔离）
        PluginSettings.getInstance().getState().setLogTimestampEnabled(false);
        try {
            assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "[", false, true, new HistoryRegexMemo()).size());
            assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "bad [ pattern", false, true, new HistoryRegexMemo()).size());
        } finally {
            PluginSettings.getInstance().getState().setLogTimestampEnabled(true);
        }
    }

    @Test
    public void testSearchRegexPrefixWorksWithoutToggle() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"));
        // `regex:` 前缀独立生效（开关关也走正则）；行号/时间正则态不参与（对齐历史页）
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "regex:^SELECT \\*").size());
        assertEquals(1, SqlLogPanelKt.filterBySearch(entries, "regex:id = \\d").size());
    }

    @Test
    public void testRegexToggleDrivesPanelFiltering() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM USER", 0L));
            pumpEdt();
            panel.getSearchFieldForTest().setText("FROM \\d+");
            panel.applySearchTextForTest();
            assertEquals("默认关：\\d+ 按字面子串不命中", 0, panel.getVisibleEntryCount());
            panel.setRegexToggleForTest(!panel.getRegexToggleForTest()); // 等价用户点击 .* 钮：翻旗并触发刷新
            assertEquals("正则开：按 pattern 语义仍不命中（无数字）", 0, panel.getVisibleEntryCount());
            panel.getSearchFieldForTest().setText("USER|missing");
            panel.applySearchTextForTest();
            assertEquals("正则开：或模式单侧命中", 1, panel.getVisibleEntryCount());
        } finally {
            panel.dispose();
        }
    }

    // ---------- T-DdlTypeColor：DDL 可单独筛选 ----------

    /**
     * 勾「建表」只出 CREATE/ALTER 条目，不把 INSERT 一起带出来。
     *
     * 这正是 DDL 独立成档（而非复用 INSERT 档）的核心理由：若复用，勾「只看插入」
     * 会把建表语句一起筛出来，语义错误。
     */
    @Test
    public void test筛选建表_只出结构性新增() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.SELECT, "SELECT 1"),
                entryOfType(StatementType.INSERT, "INSERT INTO t VALUES (1)"),
                entryOfType(StatementType.CREATE, "CREATE TABLE t (id INT)"),
                entryOfType(StatementType.CREATE, "ALTER TABLE t ADD c INT"),
                entryOfType(StatementType.DROP, "DROP TABLE t"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(entries, Set.of(StatementType.CREATE));
        assertEquals("应只命中建表与改表 2 条", 2, filtered.size());
        assertTrue("应含 CREATE", filtered.stream().anyMatch(e -> e.restoredSql().startsWith("CREATE")));
        assertTrue("应含 ALTER", filtered.stream().anyMatch(e -> e.restoredSql().startsWith("ALTER")));
        assertFalse("不应含 INSERT", filtered.stream().anyMatch(e -> e.restoredSql().startsWith("INSERT")));
    }

    /** 勾「删表」只出 DROP/TRUNCATE 条目。 */
    @Test
    public void test筛选删表_只出结构性删除() throws Exception {
        List<LogSqlEntry> entries = List.of(
                entryOfType(StatementType.DELETE, "DELETE FROM t"),
                entryOfType(StatementType.DROP, "DROP TABLE t"),
                entryOfType(StatementType.DROP, "TRUNCATE TABLE t"),
                entryOfType(StatementType.CREATE, "CREATE TABLE t (id INT)"));
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(entries, Set.of(StatementType.DROP));
        assertEquals("应只命中 DROP 与 TRUNCATE 2 条", 2, filtered.size());
        assertFalse("不应含 DELETE", filtered.stream().anyMatch(e -> e.restoredSql().startsWith("DELETE")));
    }

    /** 筛选弹窗可勾选项应含六个类型（四 DML + 两 DDL）。 */
    @Test
    public void test筛选候选类型含Ddl两档() throws Exception {
        assertEquals("可筛选类型应为 6 项", 6, SqlLogPanelKt.FILTERABLE_TYPES.size());
        assertTrue("应含 CREATE", SqlLogPanelKt.FILTERABLE_TYPES.contains(StatementType.CREATE));
        assertTrue("应含 DROP", SqlLogPanelKt.FILTERABLE_TYPES.contains(StatementType.DROP));
        assertFalse("OTHER 不单列", SqlLogPanelKt.FILTERABLE_TYPES.contains(StatementType.OTHER));
    }
}
