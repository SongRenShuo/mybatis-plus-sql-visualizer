package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * SqlLogPanel 过滤/搜索/排序集成测试（2026-08-27 筛选项精简改造，覆盖管线逻辑）。
 *
 * 测试策略：核心管线抽为顶层纯函数（filterByType / filterBySearch），直接注入条目断言；
 * 排序委托 LogDurationParser（已有独立单测）。原高频折叠测试随折叠功能移除而删除。
 */
class SqlLogPanelFilterTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        // 管线测试需要重复 SQL 场景，关闭去重避免相同 SQL 被丢弃
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    /** 构造指定类型的 LogSqlEntry。 */
    private fun entryOfType(type: StatementType, sql: String? = null, ts: Long = 0L): LogSqlEntry {
        val actualSql = sql ?: when (type) {
            StatementType.SELECT -> "SELECT * FROM t"
            StatementType.INSERT -> "INSERT INTO t VALUES (1)"
            StatementType.UPDATE -> "UPDATE t SET x = 1"
            StatementType.DELETE -> "DELETE FROM t"
            StatementType.CREATE -> "CREATE TABLE t (id INT)"
            StatementType.DROP -> "DROP TABLE t"
            StatementType.OTHER -> "MERGE INTO t USING s ON (1=1)"
        }
        return LogSqlEntry(rawSql = actualSql, restoredSql = actualSql, params = emptyList(), timestamp = ts)
    }

    // ---------- 类型过滤（filterByType 纯函数） ----------

    fun testFilterSelectOnly() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
            entryOfType(StatementType.UPDATE, "UPDATE 1"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT))
        assertEquals(listOf("SELECT 1"), filtered.map { it.restoredSql })
    }

    fun testFilterMultiType() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
            entryOfType(StatementType.UPDATE, "UPDATE 1"),
            entryOfType(StatementType.DELETE, "DELETE 1"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT, StatementType.UPDATE))
        assertEquals(listOf("SELECT 1", "UPDATE 1"), filtered.map { it.restoredSql })
    }

    fun testFilterAllPassesOtherThrough() {
        // 全选（= FILTERABLE_TYPES 全集）时 OTHER 透传——修正原筛选条 OTHER 被隐性过滤的缺陷
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.OTHER, "TRUNCATE TABLE t"),
        )
        val filtered = filterByType(entries, FILTERABLE_TYPES.toSet())
        assertEquals(2, filtered.size)
    }

    fun testFilterPartialHidesOther() {
        // 非全选时 OTHER 不透传（不在可筛选集合中）
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.OTHER, "TRUNCATE TABLE t"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT))
        assertEquals(listOf("SELECT 1"), filtered.map { it.restoredSql })
    }

    fun testFilterEmptySelectionShowsNothing() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
        )
        assertTrue(filterByType(entries, emptySet()).isEmpty())
    }

    // ---------- 搜索过滤（filterBySearch 纯函数） ----------

    fun testSearchEmptyQueryPassesAll() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user"),
            entryOfType(StatementType.SELECT, "SELECT * FROM orders"),
        )
        assertEquals(2, filterBySearch(entries, "").size)
        assertEquals(2, filterBySearch(entries, "   ").size) // 纯空白同空
    }

    fun testSearchCaseInsensitive() {
        val entries = listOf(entryOfType(StatementType.SELECT, "SELECT * FROM user"))
        assertEquals(1, filterBySearch(entries, "USER").size)
        assertEquals(1, filterBySearch(entries, "select").size)
    }

    fun testSearchMatchesParameterValue() {
        // 摘要（restoredSql 首行截断）含参数还原值——按订单号等参数值检索是常见场景
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM orders WHERE id = ?",
            restoredSql = "SELECT * FROM orders WHERE id = 'NO-20260827-001'",
            params = emptyList(),
            timestamp = 0L,
        )
        assertEquals(1, filterBySearch(listOf(entry), "NO-20260827-001").size)
        assertEquals(0, filterBySearch(listOf(entry), "NO-99999").size)
    }

    fun testSearchCombinedWithTypeFilter() {
        // 过滤 + 搜索叠加：先类型后搜索（与面板 refreshList 顺序一致）
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user"),
            entryOfType(StatementType.INSERT, "INSERT INTO user VALUES (1)"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT))
        val searched = filterBySearch(filtered, "user")
        assertEquals(listOf("SELECT * FROM user"), searched.map { it.restoredSql })
    }

    // ---------- T-HistorySearchToggles：MatchCase 大小写开关（filterBySearch matchCase 参数） ----------

    fun testSearchMatchCaseOnIsCaseSensitive() {
        val entries = listOf(entryOfType(StatementType.SELECT, "SELECT * FROM user"))
        // 开（敏感）：小写查询不命中大写 SQL；关（默认）：忽略大小写照旧
        assertEquals(0, filterBySearch(entries, "select", matchCase = true).size)
        assertEquals(1, filterBySearch(entries, "select", matchCase = false).size)
        assertEquals(1, filterBySearch(entries, "select").size) // 默认参数 = 忽略大小写
    }

    fun testSearchMatchCaseRowNumberUnaffected() {
        // 行号 `#N` 精确 equals 不受大小写旗影响（数字无大小写，契约锁死）
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 0L,
            sequenceNumber = 5L,
        )
        assertEquals(1, filterBySearch(listOf(entry), "#5", matchCase = true).size)
    }

    fun testSearchMatchCaseTimestampTarget() {
        val entries = listOf(entryOfType(StatementType.SELECT, "SELECT 1"))
        // 时间目标同样随旗：数字文本本无大小写，用字母月区格式锁语义（注入时间片段绕开设置）
        assertEquals(
            1,
            filterBySearch(entries, "Jan-02", matchCase = true, timestampTextOf = { "[2026-Jan-02 10:30:00]" }).size,
        )
        assertEquals(
            0,
            filterBySearch(entries, "JAN-02", matchCase = true, timestampTextOf = { "[2026-Jan-02 10:30:00]" }).size,
        )
        assertEquals(
            1,
            filterBySearch(entries, "JAN-02", matchCase = false, timestampTextOf = { "[2026-Jan-02 10:30:00]" }).size,
        )
    }

    /**
     * 面板接线（开关 → refreshList → filterBySearch(matchCase)）：toggle 点击即生效；
     * headless 下防抖 Timer 不可控，搜索文本经 [SqlLogPanel.applySearchTextForTest] 同步应用。
     */
    fun testMatchCaseToggleDrivesPanelFiltering() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM USER", ts = 0L))
            pumpEdt()
            panel.searchFieldForTest.text = "user"
            panel.applySearchTextForTest()
            assertEquals("默认关=忽略大小写，命中", 1, panel.visibleEntryCount)
            panel.matchCaseToggleForTest = !panel.matchCaseToggleForTest // 等价用户点击：翻转选中态并触发刷新
            assertEquals("开关开=敏感，user 不命中 USER", 0, panel.visibleEntryCount)
            panel.matchCaseToggleForTest = !panel.matchCaseToggleForTest
            assertEquals("再点关闭恢复命中", 1, panel.visibleEntryCount)
        } finally {
            panel.dispose()
        }
    }

    // ---------- T-SearchToggleNativeLook：Regex 正则开关（20261010 用户令日志页补正则，D133 翻案） ----------

    fun testSearchRegexToggleMatchesPattern() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"),
            entryOfType(StatementType.UPDATE, "UPDATE t SET a = 1"),
        )
        // 开关正则：按 pattern 语义命中（\\d = 数字），两维 restoredSql/summary 参与
        assertEquals(2, filterBySearch(entries, "id = \\d|SET a = \\d", regexEnabled = true).size)
        assertEquals(1, filterBySearch(entries, "FROM user WHERE", regexEnabled = true).size)
        // 锚点+大小写：`^select` 敏感态不命中大写开头；忽略态命中
        assertEquals(0, filterBySearch(entries, "^select", matchCase = true, regexEnabled = true).size)
        assertEquals(1, filterBySearch(entries, "^select", matchCase = false, regexEnabled = true).size)
    }

    fun testSearchRegexOffKeepsSubstringSemantics() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"),
        )
        // 关（默认）：正则元字符按字面子串匹配（现状语义零变化）
        assertEquals(0, filterBySearch(entries, "id = \\d").size)
        assertEquals(1, filterBySearch(entries, "id = ").size)
    }

    fun testSearchRegexInvalidFallsBackToSubstring() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"),
            entryOfType(StatementType.SELECT, "bad [ pattern row"),
        )
        // 非法正则 fail-closed：回退子串（查询为原文），restoredSql 维度恰一行含 "["
        // （时间片段注入 null 隔离——真实时间片段 "[yyyy-MM-dd …]" 自带方括号会干扰该断言）
        assertEquals(
            1,
            filterBySearch(entries, "[", regexEnabled = true, timestampTextOf = { null }).size,
        )
        assertEquals(
            1,
            filterBySearch(entries, "bad [ pattern", regexEnabled = true, timestampTextOf = { null }).size,
        )
    }

    fun testSearchRegexPrefixWorksWithoutToggle() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user WHERE id = 1"),
        )
        // `regex:` 前缀独立生效（开关关也走正则）；行号/时间正则态不参与（对齐历史页）
        assertEquals(1, filterBySearch(entries, "regex:^SELECT \\*").size)
        assertEquals(1, filterBySearch(entries, "regex:id = \\d").size)
    }

    fun testRegexToggleDrivesPanelFiltering() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM USER", ts = 0L))
            pumpEdt()
            panel.searchFieldForTest.text = "FROM \\d+"
            panel.applySearchTextForTest()
            assertEquals("默认关：\\d+ 按字面子串不命中", 0, panel.visibleEntryCount)
            panel.regexToggleForTest = !panel.regexToggleForTest // 等价用户点击 .* 钮：翻旗并触发刷新
            assertEquals("正则开：按 pattern 语义仍不命中（无数字）", 0, panel.visibleEntryCount)
            panel.searchFieldForTest.text = "USER|missing"
            panel.applySearchTextForTest()
            assertEquals("正则开：或模式单侧命中", 1, panel.visibleEntryCount)
        } finally {
            panel.dispose()
        }
    }

    // ---------- 耗时排序（委托 LogDurationParser） ----------

    fun testDurationSortHiddenWithoutDurationInfo() {
        val snapshot = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.SELECT, "SELECT 2"),
        )
        assertFalse(snapshot.any { LogDurationParser.hasDurationInfo(it) })
    }

    fun testDurationSortOrderDesc() {
        val e1 = LogSqlEntry(
            rawSql = "1723456789012|100|statement|SELECT * FROM a",
            restoredSql = "SELECT * FROM a",
            params = emptyList(),
            timestamp = 0L,
        )
        val e2 = LogSqlEntry(
            rawSql = "1723456789012|500|statement|SELECT * FROM b",
            restoredSql = "SELECT * FROM b",
            params = emptyList(),
            timestamp = 0L,
        )
        val e3 = LogSqlEntry(
            rawSql = "1723456789012|300|statement|SELECT * FROM c",
            restoredSql = "SELECT * FROM c",
            params = emptyList(),
            timestamp = 0L,
        )
        val snapshot = listOf(e1, e2, e3)
        val sorted = snapshot.sortedByDescending { LogDurationParser.parse(it) ?: -1L }
        assertEquals("SELECT * FROM b", sorted[0].restoredSql) // 500ms
        assertEquals("SELECT * FROM c", sorted[1].restoredSql) // 300ms
        assertEquals("SELECT * FROM a", sorted[2].restoredSql) // 100ms
    }

    // ---------- 缓冲层去重计数联动（面板徽章数据源） ----------

    fun testDedupeBadgeDataFromBuffer() {
        manager.entries.dedupeEnabled = true
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 0L))
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 100L)) // 丢弃
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 200L)) // 再丢弃
        // 等待 EDT 刷新（面板 UI 监听器经 invokeLater）
        waitForEdt()
        val counts = manager.entries.dedupeCountsSnapshot()
        // T-Perf-19：快照键为 SQL 指纹（非原文）
        assertEquals(2, counts[sqlFingerprint("SELECT * FROM t WHERE id = 1")])
    }

    fun testClearResetsBadgeCounts() {
        manager.entries.dedupeEnabled = true
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT dup", ts = 0L))
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT dup", ts = 100L)) // 丢弃
        assertEquals(1, manager.entries.dedupeCount("SELECT dup"))
        manager.entries.clear()
        assertEquals(0, manager.entries.dedupeCount("SELECT dup"))
    }

    // ---------- PluginSettings 默认值（改造后残留状态检查） ----------

    fun testPluginSettingsDefaultsAfterCleanup() {
        // 去重开关在 buffer 层（SqlLogEntryBuffer.dedupeEnabled 默认 true，T-M31.13），
        // 不再断言 PluginSettings（筛选条相关字段已随精简改造移除）
        val state = PluginSettings.getInstance().state
        assertFalse("历史自动保存默认关闭", state.logAutoSaveEnabled)
    }

    // ---------- M56 T-M56-00：聚焦过滤层（filterByFocus 纯函数，插 filterByType→filterBySearch 之间） ----------

    /** 构造带扩展字段的条目。 */
    private fun focusedEntry(
        sql: String,
        mapperStatement: String? = null,
        requestTag: String? = null,
    ) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        mapperStatement = mapperStatement,
        requestTag = requestTag,
    )

    fun testFocusNullPassthrough() {
        val entries = listOf(focusedEntry("SELECT 1"), focusedEntry("SELECT 2"))
        assertEquals(entries, filterByFocus(entries, null))
        // 全空维度等价不过滤
        assertEquals(entries, filterByFocus(entries, FocusFilter()))
    }

    fun testFocusByMapperStatement() {
        val entries = listOf(
            focusedEntry("SELECT 1", mapperStatement = "com.x.UserMapper.selectById"),
            focusedEntry("SELECT 2", mapperStatement = "com.x.OrderMapper.list"),
            focusedEntry("SELECT 3"),
        )
        val filtered = filterByFocus(entries, FocusFilter(method = "com.x.UserMapper.selectById"))
        assertEquals(listOf("SELECT 1"), filtered.map { it.restoredSql })
    }

    fun testFocusMethodIgnoreCase() {
        val entries = listOf(focusedEntry("SELECT 1", mapperStatement = "com.x.UserMapper.selectById"))
        assertEquals(
            entries,
            filterByFocus(entries, FocusFilter(method = "COM.X.USERMAPPER.SELECTBYID")),
        )
    }

    fun testFocusByRequestTag() {
        val entries = listOf(
            focusedEntry("SELECT 1", requestTag = "req-A"),
            focusedEntry("SELECT 2", requestTag = "req-B"),
            focusedEntry("SELECT 3", requestTag = "req-A"),
        )
        val filtered = filterByFocus(entries, FocusFilter(requestTag = "req-A"))
        assertEquals(listOf("SELECT 1", "SELECT 3"), filtered.map { it.restoredSql })
    }

    fun testFocusMethodAndTagConjunctive() {
        val entries = listOf(
            focusedEntry("SELECT 1", mapperStatement = "com.x.A.get", requestTag = "req-A"),
            focusedEntry("SELECT 2", mapperStatement = "com.x.B.get", requestTag = "req-A"),
            focusedEntry("SELECT 3", mapperStatement = "com.x.A.get", requestTag = "req-B"),
        )
        val filtered = filterByFocus(entries, FocusFilter(method = "com.x.A.get", requestTag = "req-A"))
        assertEquals(listOf("SELECT 1"), filtered.map { it.restoredSql })
    }

    fun testFocusFilterPipelineOrderWithTypeAndSearch() {
        // 管线叠加：filterByType → filterByFocus → filterBySearch 与 refreshList 一致
        val entries = listOf(
            focusedEntry("SELECT alpha", mapperStatement = "com.x.A.get"),
            focusedEntry("SELECT alpha2", mapperStatement = "com.x.B.get"),
            focusedEntry("UPDATE alpha3", mapperStatement = "com.x.A.get"),
        )
        val filtered = filterByFocus(filterByType(entries, setOf(StatementType.SELECT)), FocusFilter(method = "com.x.A.get"))
        val searched = filterBySearch(filtered, "alpha")
        assertEquals(listOf("SELECT alpha"), searched.map { it.restoredSql })
    }

    // ---------- T-SqlSuppress（O-69）：屏蔽规则存量过滤 ----------

    /** 唯一的心跳 SQL 常量（测试间经 manager 清理隔离，防 APP 级单例串台）。 */
    private val heartbeatSql = "SELECT HEARTBEAT_69"

    fun testSuppressEmptyKeysPassThrough() {
        val entries = listOf(entryOfType(StatementType.SELECT, "SELECT 1"))
        assertEquals(entries, filterBySuppress(entries, emptySet()))
    }

    fun testSuppressFiltersMatchingDedupeKeyOnly() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, heartbeatSql),
            entryOfType(StatementType.SELECT, "SELECT 2"),
            // 空白变体同指纹（与去重/屏蔽同一规范化口径）——同样被滤
            entryOfType(StatementType.SELECT, "  $heartbeatSql  "),
        )
        val keys = setOf(suppressFingerprint(heartbeatSql))
        val filtered = filterBySuppress(entries, keys)
        assertEquals(listOf("SELECT 2"), filtered.map { it.restoredSql })
    }

    fun testSuppressRemoveRuleEntryComesBack() {
        val keys = setOf(suppressFingerprint(heartbeatSql))
        val entries = listOf(entryOfType(StatementType.SELECT, heartbeatSql))
        assertEquals(0, filterBySuppress(entries, keys).size)
        assertEquals("移除规则（空集）后复现", entries, filterBySuppress(entries, emptySet()))
    }

    /**
     * 全链路（§五 验收 3 / §四 前提 2）：缓冲中已有心跳行 → 注册规则 → refreshList 输出不含匹配行；
     * 移除规则后复现。真链路：SqlSuppressRuleManager（APP 级）通知 → SqlLogManager 同步缓冲键集
     * + notifyUi → 面板 refreshList（规则变更必须显式触发——差分只认缓冲快照变化）。
     */
    fun testRegisterRuleHidesExistingHeartbeatRowsViaPanelRefresh() {
        val panel = SqlLogPanel(project, manager)
        val suppressManager = SqlSuppressRuleManager.getInstance()
        try {
            manager.entries.addEntry(entryOfType(StatementType.SELECT, heartbeatSql, ts = 0L))
            manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT 2", ts = 1L))
            pumpEdt()
            assertEquals("屏蔽前 2 条全部展示", 2, panel.visibleEntryCount)

            suppressManager.addRule(heartbeatSql)
            pumpEdt()
            assertEquals("注册规则后心跳行立即消失（前提 2 显式刷新已接通）", 1, panel.visibleEntryCount)
            assertEquals(listOf("SELECT 2"), panel.visibleEntries)
            assertTrue(
                "缓冲仍在（只是隐藏——删除规则后可复现）",
                manager.entries.snapshot().any { it.restoredSql == heartbeatSql },
            )

            suppressManager.removeRule(suppressManager.rules.single { it.pattern == heartbeatSql }.id)
            pumpEdt()
            assertEquals("移除规则后存量行复现", 2, panel.visibleEntryCount)
        } finally {
            // APP 级单例跨测试类共享（分片 JVM 复用）——必须清理，防后续测试类被殃及
            suppressManager.clearRules()
            panel.dispose()
        }
    }

    /**
     * 泵空 EDT 队列（DedupeToggleTest 同款）：manager.notifyUi 用 invokeLater 调度刷新，
     * headless 测试必须 dispatchAllInvocationEvents 才能让列表刷新执行（invokeAndWait 空任务不够）。
     */
    private fun pumpEdt() {
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    }

    /** 等待 EDT 调度完成。 */
    private fun waitForEdt() {
        ApplicationManager.getApplication().invokeAndWait { java.lang.Runnable { } }
    }

    // ---------- T-DdlTypeColor：DDL 可单独筛选 ----------

    /**
     * 勾「建表」只出 CREATE/ALTER 条目，不把 INSERT 一起带出来。
     *
     * 这正是 DDL 独立成档（而非复用 INSERT 档）的核心理由：若复用，勾「只看插入」
     * 会把建表语句一起筛出来，语义错误。
     */
    fun test筛选建表_只出结构性新增() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT INTO t VALUES (1)"),
            entryOfType(StatementType.CREATE, "CREATE TABLE t (id INT)"),
            entryOfType(StatementType.CREATE, "ALTER TABLE t ADD c INT"),
            entryOfType(StatementType.DROP, "DROP TABLE t"),
        )
        val filtered = filterByType(entries, setOf(StatementType.CREATE))
        assertEquals("应只命中建表与改表 2 条", 2, filtered.size)
        assertTrue("应含 CREATE", filtered.any { it.restoredSql.startsWith("CREATE") })
        assertTrue("应含 ALTER", filtered.any { it.restoredSql.startsWith("ALTER") })
        assertFalse("不应含 INSERT", filtered.any { it.restoredSql.startsWith("INSERT") })
    }

    /** 勾「删表」只出 DROP/TRUNCATE 条目。 */
    fun test筛选删表_只出结构性删除() {
        val entries = listOf(
            entryOfType(StatementType.DELETE, "DELETE FROM t"),
            entryOfType(StatementType.DROP, "DROP TABLE t"),
            entryOfType(StatementType.DROP, "TRUNCATE TABLE t"),
            entryOfType(StatementType.CREATE, "CREATE TABLE t (id INT)"),
        )
        val filtered = filterByType(entries, setOf(StatementType.DROP))
        assertEquals("应只命中 DROP 与 TRUNCATE 2 条", 2, filtered.size)
        assertFalse("不应含 DELETE", filtered.any { it.restoredSql.startsWith("DELETE") })
    }

    /** 筛选弹窗可勾选项应含六个类型（四 DML + 两 DDL）。 */
    fun test筛选候选类型含Ddl两档() {
        assertEquals("可筛选类型应为 6 项", 6, FILTERABLE_TYPES.size)
        assertTrue("应含 CREATE", StatementType.CREATE in FILTERABLE_TYPES)
        assertTrue("应含 DROP", StatementType.DROP in FILTERABLE_TYPES)
        assertFalse("OTHER 不单列", StatementType.OTHER in FILTERABLE_TYPES)
    }
}
