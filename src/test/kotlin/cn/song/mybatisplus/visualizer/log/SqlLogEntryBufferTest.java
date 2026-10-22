package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 日志条目缓冲单元测试（T-M31-03，纯逻辑）。
 */
public class SqlLogEntryBufferTest {

    private static final long DEFAULT_WINDOW = 60000L;

    private final SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(3);

    private LogSqlEntry entry(String sql) {
        return entry(sql, 0L);
    }

    private LogSqlEntry entry(String sql, long ts) {
        return new LogSqlEntry(
                sql,
                sql,
                List.of(),
                ts);
    }

    @Test
    public void 新增条目最早在前() throws Exception {
        buffer.addEntry(entry("SELECT 1"));
        buffer.addEntry(entry("SELECT 2"));
        List<LogSqlEntry> snap = buffer.snapshot();
        assertEquals(2, snap.size());
        assertEquals("SELECT 1", snap.get(0).restoredSql());
        assertEquals("SELECT 2", snap.get(1).restoredSql());
    }

    @Test
    public void 超过上限裁掉最旧() throws Exception {
        buffer.addEntry(entry("SELECT 1"));
        buffer.addEntry(entry("SELECT 2"));
        buffer.addEntry(entry("SELECT 3"));
        buffer.addEntry(entry("SELECT 4"));
        List<LogSqlEntry> snap = buffer.snapshot();
        assertEquals(3, snap.size());
        assertEquals("SELECT 2", snap.get(0).restoredSql());
        assertFalse(snap.stream().anyMatch(it -> it.restoredSql().equals("SELECT 1")));
    }

    @Test
    public void 暂停态丢弃新增() throws Exception {
        buffer.setPaused(true);
        assertFalse(buffer.addEntry(entry("SELECT 1")));
        assertTrue(buffer.snapshot().isEmpty());
        buffer.setPaused(false);
        assertTrue(buffer.addEntry(entry("SELECT 1")));
    }

    @Test
    public void 清空与删除单条() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(3);
        buf.setDedupeEnabled(false); // 本用例验证删除语义（与去重开关态隔离）
        LogSqlEntry e1 = entry("SELECT 1");
        LogSqlEntry e2 = entry("SELECT 2");
        buf.addEntry(e1);
        buf.addEntry(e2);
        // T-LogRowNumber：面板从列表模型拿到的是入库实例（带行号 copy），与 e1 同一
        LogSqlEntry storedOne = buf.snapshot().stream()
                .filter(it -> it.restoredSql().equals("SELECT 1")).findFirst().orElseThrow();
        buf.removeEntry(storedOne);
        assertEquals(List.of("SELECT 2"), buf.snapshot().stream().map(LogSqlEntry::restoredSql).toList());
        buf.clear();
        assertTrue(buf.snapshot().isEmpty());
    }

    @Test
    public void removeEntry按同一性删除_带行号实例可删_无号原型不误删() throws Exception {
        // T-LogRowNumber：入库为带号 copy；面板引用=入库实例（同一性成立可删）
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.addEntry(entry("SELECT 1", 0L));
        LogSqlEntry stored = buf.snapshot().get(0);
        assertEquals(1L, stored.sequenceNumber());
        buf.removeEntry(stored);
        assertTrue(buf.snapshot().isEmpty());
        // 无号原型（外部构造的等值条目）不应误删列表中的真身
        LogSqlEntry proto = entry("SELECT 2", 100L);
        buf.addEntry(proto);
        buf.removeEntry(proto);
        assertEquals(1, buf.snapshot().size());
        assertEquals("SELECT 2", buf.snapshot().get(0).restoredSql());
    }

    @Test
    public void 监听器分层_内容通知节流与即时出口() throws Exception {
        // T-PerfBatch 分层契约：内容监听器走「数量+时间」节流（时间窗内首条即时）；
        // onEntryAccepted 每次成功入队即时回调（自动展开语义）；被抑制条目由兜底出口承接。
        int[] contentNotified = {0};
        int[] suppressed = {0};
        int[] accepted = {0};
        Runnable listener = () -> contentNotified[0] += 1;
        buffer.addListener(listener);
        buffer.setOnContentNotifySuppressed(() -> suppressed[0] += 1);
        buffer.setOnEntryAccepted(() -> accepted[0] += 1);

        buffer.addEntry(entry("SELECT 1")); // 首条：距上次通知超时间窗 → 即时
        buffer.addEntry(entry("SELECT 2")); // 窗口内：节流抑制，兜底出口接管
        assertEquals("时间窗内首条即时通知", 1, contentNotified[0]);
        assertEquals("被抑制条目由兜底出口承接", 1, suppressed[0]);
        assertEquals("每次成功入队即时回调", 2, accepted[0]);

        // 超过时间窗（200ms）后恢复即时通知——低流量等价即时
        Thread.sleep(210);
        buffer.addEntry(entry("SELECT 3"));
        assertEquals("超窗后恢复即时通知", 2, contentNotified[0]);

        buffer.removeListener(listener);
        buffer.setOnContentNotifySuppressed(null);
        buffer.setOnEntryAccepted(null);
        buffer.addEntry(entry("SELECT 4"));
        assertEquals("移除监听后内容通知停止", 2, contentNotified[0]);
    }

    @Test
    public void 监听器分层_去重丢弃只走徽章通道不触内容() throws Exception {
        buffer.setDedupeWindowMs(60000L);
        int[] contentNotified = {0};
        int[] badgeNotified = {0};
        int[] accepted = {0};
        buffer.addListener(() -> contentNotified[0] += 1);
        buffer.setOnDedupeCountsChanged(() -> badgeNotified[0] += 1);
        buffer.setOnEntryAccepted(() -> accepted[0] += 1);

        buffer.addEntry(entry("SELECT 1")); // 成功：内容（即时）+ 接受回调
        buffer.addEntry(entry("SELECT 1")); // 窗口内重复：丢弃 → 仅徽章通道
        buffer.addEntry(entry("SELECT 1")); // 同上
        assertEquals("首条即时内容通知", 1, contentNotified[0]);
        assertEquals("两次丢弃两次徽章通知（⨉N 即时）", 2, badgeNotified[0]);
        assertEquals("重复 SQL 不是新 SQL，接受回调不触发", 1, accepted[0]);
        assertEquals(1, buffer.snapshot().size());
    }

    // ---------- F-43：onEvicted 回调 ----------

    @Test
    public void onEvicted_回调在淘汰时触发() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        List<String> evicted = new ArrayList<>();
        buf.setOnEvicted((Consumer<LogSqlEntry>) e -> evicted.add(e.restoredSql()));
        buf.addEntry(entry("SELECT 1"));
        buf.addEntry(entry("SELECT 2"));
        buf.addEntry(entry("SELECT 3")); // 淘汰 SELECT 1
        assertEquals(List.of("SELECT 1"), evicted);
    }

    // ---------- T-SqlHistoryFull：onEntryStored 全量写入回调 ----------

    @Test
    public void onEntryStored_每次成功入队触发() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(5);
        List<String> stored = new ArrayList<>();
        buf.setOnEntryStored((Consumer<LogSqlEntry>) e -> stored.add(e.restoredSql()));
        buf.addEntry(entry("A"));
        buf.addEntry(entry("B"));
        buf.addEntry(entry("C"));
        assertEquals(List.of("A", "B", "C"), stored);
    }

    @Test
    public void onEntryStored_覆盖被淘汰条目全量且不含去重丢弃() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        List<String> stored = new ArrayList<>();
        buf.setOnEntryStored((Consumer<LogSqlEntry>) e -> stored.add(e.restoredSql()));
        buf.addEntry(entry("A"));
        buf.addEntry(entry("B"));
        buf.addEntry(entry("A")); // 去重窗口内重复（A 仍在列表第一）→ 丢弃，不入 stored
        buf.addEntry(entry("C")); // 淘汰 A，但 A 已入队成功 → 已在 stored
        assertEquals("被淘汰条目也在全量历史中，去重丢弃不在", List.of("A", "B", "C"), stored);
    }

    @Test
    public void onEntryStored_在暂停和过滤时不触发() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(5);
        List<String> stored = new ArrayList<>();
        buf.setOnEntryStored((Consumer<LogSqlEntry>) e -> stored.add(e.restoredSql()));
        buf.setFilterKeywords(List.of("DROP"));
        buf.setPaused(true);
        buf.addEntry(entry("DROP TABLE x")); // 过滤命中去重判定前被拦 → 不入
        buf.addEntry(entry("SELECT 1")); // 暂停态 → 不入
        assertEquals("暂停/过滤条目不进全量历史", 0, stored.size());
    }

    @Test
    public void onEntryStored_null_时无异常() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(1);
        buf.setOnEntryStored(null);
        buf.addEntry(entry("SELECT 1"));
        buf.addEntry(entry("SELECT 2"));
        assertEquals(1, buf.snapshot().size());
    }

    @Test
    public void onEvicted_null_时无异常() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(1);
        buf.setOnEvicted(null);
        buf.addEntry(entry("SELECT 1"));
        buf.addEntry(entry("SELECT 2")); // 淘汰 SELECT 1，无回调
        assertEquals(1, buf.snapshot().size());
        assertEquals("SELECT 2", buf.snapshot().get(0).restoredSql());
    }

    @Test
    public void 多条目连续淘汰触发多次回调() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(1);
        List<String> evicted = new ArrayList<>();
        buf.setOnEvicted((Consumer<LogSqlEntry>) e -> evicted.add(e.restoredSql()));
        buf.addEntry(entry("SELECT 1"));
        buf.addEntry(entry("SELECT 2"));
        buf.addEntry(entry("SELECT 3"));
        buf.addEntry(entry("SELECT 4"));
        assertEquals(List.of("SELECT 1", "SELECT 2", "SELECT 3"), evicted);
    }

    @Test
    public void onEvicted_回调顺序为最早到最新() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        List<String> evicted = new ArrayList<>();
        buf.setOnEvicted((Consumer<LogSqlEntry>) e -> evicted.add(e.restoredSql()));
        buf.addEntry(entry("A"));
        buf.addEntry(entry("B"));
        buf.addEntry(entry("C")); // 淘汰 A
        buf.addEntry(entry("D")); // 淘汰 B
        buf.addEntry(entry("E")); // 淘汰 C
        assertEquals(List.of("A", "B", "C"), evicted);
    }

    // ---------- 去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」） ----------

    // ---------- T-SqlSuppress（O-69）：屏蔽闸门 ----------

    /** 构造屏蔽指纹（与 SqlSuppressRuleManager 发布口径同源：suppressFingerprint = 去重口径 + 尾分号剥离）。 */
    private static String suppressKeyOf(String sql) {
        return LogSqlModelsKt.suppressFingerprint(sql);
    }

    @Test
    public void 屏蔽规则命中_入队前丢弃() throws Exception {
        buffer.setDedupeEnabled(false); // 与去重开关隔离，纯验屏蔽闸门
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT 1")));
        assertFalse("命中规则 → 入队返回 false（从未捕获过）", buffer.addEntry(entry("SELECT 1")));
        assertTrue("列表不含该条目", buffer.snapshot().isEmpty());
        // 空白差异的变体天然命中（规范化口径与去重一致）
        assertFalse("多空白/首尾空白变体同样被拦", buffer.addEntry(entry("  SELECT   1  ")));
        assertTrue(buffer.snapshot().isEmpty());
        // 大小写不同不命中（与既有规范化口径一致：大小写与分号不动）
        assertTrue("大小写变体不被拦", buffer.addEntry(entry("select 1")));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 结构匹配规则开启后_参数值不同的同形状SQL被拦_精确规则不误拦() throws Exception {
        // T-SqlSuppressUx 二段：结构匹配 opt-in——精确口径规则不拦不同参数值；结构规则拦
        buffer.setDedupeEnabled(false);
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT * FROM t WHERE p = 'a'")));
        assertTrue("精确口径规则不拦不同参数值（opt-in 前置）", buffer.addEntry(entry("SELECT * FROM t WHERE p = 'b'")));
        assertEquals("仅精确未拦的那条在列", 1, buffer.snapshot().size());
        // 结构规则：同形状不同参数值被拦（闸门走 entry.structureKey）；被拦条目不入列
        buffer.setSuppressKeys(Set.of(LogSqlModelsKt.structureFingerprint("SELECT * FROM t WHERE p = 'a'")));
        buffer.setHasStructureKeys(true); // F20：结构键在集（生产由 SqlLogManager 同步 manager 标志）
        assertFalse("结构规则拦同形状不同参数值", buffer.addEntry(entry("SELECT * FROM t WHERE p = 'c'")));
        assertEquals("列表仍只有先前一条", 1, buffer.snapshot().size());
        // 不同形状不拦
        assertTrue(buffer.addEntry(entry("SELECT * FROM t WHERE q = 'a'")));
    }

    @Test
    public void 屏蔽规则带尾分号_命中不带分号的日志还原SQL() throws Exception {
        // T-SqlSuppressUx（20260924 走查返修）：规则粘贴态带 `;`、日志还原链不带——尾分号剥离后互命中
        buffer.setDedupeEnabled(false);
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT 1;"))); // 规则侧带分号
        assertFalse("不带分号的日志形态同样被拦", buffer.addEntry(entry("SELECT 1")));
        assertTrue(buffer.snapshot().isEmpty());
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT 1"))); // 规则侧不带分号
        assertFalse("带分号的日志形态同样被拦（对称）", buffer.addEntry(entry("SELECT 1;")));
        assertTrue(buffer.snapshot().isEmpty());
        // 剥离只作用收尾终结符：字面量内分号不构成跨语句误吞
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT * FROM t WHERE s = 'a;b'")));
        assertTrue("形状不同的语句不被误吞", buffer.addEntry(entry("SELECT * FROM t WHERE s = 'a'")));
    }

    @Test
    public void 审计入队同样被屏蔽闸门拦截() throws Exception {
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT 1")));
        assertFalse("addAuditEntry 走同一私有门序，屏蔽一并生效", buffer.addAuditEntry(entry("SELECT 1")));
        assertTrue(buffer.snapshot().isEmpty());
    }

    @Test
    public void 被拦条目不占捕获序号不产生去重计数() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT dup")));
        buffer.addEntry(entry("SELECT dup", 0L));
        buffer.addEntry(entry("SELECT dup", 1L)); // 若未被屏蔽，第二条会进去重计数
        assertTrue("被拦条目无去重计数", buffer.dedupeCountsSnapshot().isEmpty());
        assertTrue(buffer.snapshot().isEmpty());
        // 序号：屏蔽条目不占号——解除屏蔽后首条成功入队从 #1 起
        buffer.setSuppressKeys(Set.of());
        assertTrue(buffer.addEntry(entry("SELECT 2", 2L)));
        List<LogSqlEntry> snap = buffer.snapshot();
        assertEquals(1, snap.size());
        assertEquals("屏蔽不占捕获序号", 1L, snap.get(0).sequenceNumber());
    }

    @Test
    public void 移除规则后恢复入队() throws Exception {
        buffer.setDedupeEnabled(false);
        Set<String> keys = new HashSet<>(Set.of(suppressKeyOf("SELECT 1")));
        buffer.setSuppressKeys(keys);
        assertFalse(buffer.addEntry(entry("SELECT 1")));
        keys.clear(); // 订阅者（SqlLogManager）整体替换/清空键集
        buffer.setSuppressKeys(keys);
        assertTrue("规则移除后恢复入队", buffer.addEntry(entry("SELECT 1")));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 屏蔽命中走专用计数出口_不触内容与入队回调() throws Exception {
        buffer.setDedupeEnabled(false);
        buffer.setSuppressKeys(Set.of(suppressKeyOf("SELECT 1")));
        List<String> hitKeys = new ArrayList<>();
        int[] accepted = {0};
        int[] storedCount = {0};
        int[] contentNotified = {0};
        buffer.setOnSuppressedHit(key -> hitKeys.add(key));
        buffer.setOnEntryAccepted(() -> accepted[0] += 1);
        buffer.setOnEntryStored(e -> storedCount[0] += 1);
        buffer.addListener(() -> contentNotified[0] += 1);
        buffer.addEntry(entry("SELECT 1"));
        assertEquals("命中指纹回调（管理对话框「已拦截 N 次」数据源）", List.of(suppressKeyOf("SELECT 1")), hitKeys);
        assertEquals("从未捕获过：接受回调不触发", 0, accepted[0]);
        assertEquals("全量历史不写", 0, storedCount[0]);
        assertEquals("内容通知不触发", 0, contentNotified[0]);
    }

    @Test
    public void 去重窗口内相同_SQL_丢弃且通知徽章() throws Exception {
        // F-44-2：用户实测「重复值计数更新的也不及时」——徽章数据变了必须即时通知 UI。
        // T-PerfBatch 分层：丢弃走徽章通道（即时），内容通道时间窗内首条入列即时通知。
        buffer.setDedupeEnabled(true);
        int[] contentNotified = {0};
        int[] badgeNotified = {0};
        buffer.addListener(() -> contentNotified[0]++);
        buffer.setOnDedupeCountsChanged(() -> badgeNotified[0]++);
        assertTrue(buffer.addEntry(entry("SELECT 1", 0L)));
        assertFalse(buffer.addEntry(entry("SELECT 1", 500L))); // 窗口内
        assertEquals(1, contentNotified[0]); // 首条入列内容通知
        assertEquals(1, badgeNotified[0]); // 丢弃即时走徽章通道
        assertEquals(1, buffer.snapshot().size());
        assertEquals(1, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void 去重窗口外相同_SQL_保留() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 1", DEFAULT_WINDOW + 100L))); // 超窗口
        assertEquals(2, buffer.snapshot().size());
    }

    @Test
    public void 不同_SQL_不去重() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 2", 100L)));
        assertEquals(2, buffer.snapshot().size());
    }

    // ---------- T-LogDedupeMpMark：跨格式副本（仅差首尾空白）判重 ----------

    @Test
    public void 尾随空白差异判重命中() throws Exception {
        // JdbcTemplate 双路日志：named 副本 trim、prepared 副本尾随空白原样保留
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertFalse(buffer.addEntry(entry("SELECT 1  ", 100L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 首部与内部连续空白差异判重命中() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT * FROM t WHERE id = 1", 0L));
        assertFalse(buffer.addEntry(entry("  SELECT *  FROM t WHERE id = 1", 100L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 空白差异副本丢弃计数并入同一徽章键() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1 ", 100L));
        buffer.addEntry(entry("  SELECT 1", 200L));
        assertEquals(2, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void 空白规范化不改大小写与内容_不同SQL仍不去重() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("select 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 1", 100L)));
        assertTrue(buffer.addEntry(entry("SELECT 1 WHERE", 200L)));
        assertEquals(3, buffer.snapshot().size());
    }

    @Test
    public void 去重开关默认开启() throws Exception {
        assertTrue(buffer.isDedupeEnabled()); // 默认开（T-M31.13 用户实测后要求）
        buffer.addEntry(entry("SELECT 1", 0L));
        assertFalse(buffer.addEntry(entry("SELECT 1", 100L))); // 窗口内重复丢弃
    }

    @Test
    public void 过滤关键字命中时丢弃() throws Exception {
        buffer.setFilterKeywords(List.of("from_dual", "TMP_"));
        assertFalse(buffer.addEntry(entry("SELECT * FROM from_dual", 0L)));
        assertFalse(buffer.addEntry(entry("SELECT * FROM TMP_LOG", 100L)));
        assertTrue(buffer.addEntry(entry("SELECT * FROM user", 200L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 过滤关键字为空时全部保留() throws Exception {
        buffer.setFilterKeywords(List.of());
        assertTrue(buffer.addEntry(entry("SELECT * FROM user", 0L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 去重开关显式关闭时全部保留() throws Exception {
        buffer.setDedupeEnabled(false);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 1", 100L)));
        assertEquals(2, buffer.snapshot().size());
    }

    // ---------- 去重丢弃计数（列表 ⨉N 徽章数据源） ----------

    @Test
    public void 去重丢弃计数随丢弃递增() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃 1 次
        buffer.addEntry(entry("SELECT 1", 200L)); // 丢弃 2 次
        assertEquals(2, buffer.dedupeCount("SELECT 1"));
        // T-Perf-19：快照键为 SQL 指纹（非原文）——经 sqlFingerprint 查键
        assertEquals(Integer.valueOf(2), buffer.dedupeCountsSnapshot().get(LogSqlModelsKt.sqlFingerprint("SELECT 1")));
    }

    @Test
    public void 去重计数键为还原SQL原文_不同SQL互不干扰() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 2", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃
        assertEquals(1, buffer.dedupeCount("SELECT 1"));
        assertEquals(0, buffer.dedupeCount("SELECT 2"));
        assertEquals(0, buffer.dedupeCount("SELECT 3")); // 无记录返回 0
    }

    @Test
    public void clear_归零去重计数() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃 1 次
        assertEquals(1, buffer.dedupeCount("SELECT 1"));
        buffer.clear();
        assertEquals(0, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void resetDedupeCounts_归零而不清列表() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃 1 次
        buffer.resetDedupeCounts();
        assertEquals(0, buffer.dedupeCount("SELECT 1"));
        assertEquals(1, buffer.snapshot().size()); // 列表条目保留
    }

    @Test
    public void 去重关闭期间不累计计数() throws Exception {
        buffer.setDedupeEnabled(false);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 去重关闭，正常入列
        assertEquals(0, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void 去重丢弃触发监听器() throws Exception {
        // F-44-2：徽章数据变了必须通知 UI（实测反馈「不及时」是缺陷）。
        // T-PerfBatch 分层：徽章走独立通道，丢弃不再触发内容监听器。
        buffer.setDedupeEnabled(true);
        int[] contentNotified = {0};
        int[] badgeNotified = {0};
        buffer.addListener(() -> contentNotified[0]++);
        buffer.setOnDedupeCountsChanged(() -> badgeNotified[0]++);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃
        assertEquals(1, contentNotified[0]); // 第一条入列
        assertEquals(1, badgeNotified[0]); // 第二条丢弃 → 徽章通道即时
    }

    @Test
    public void 去重计数有界化_超限淘汰最旧键() throws Exception {
        // T-PerfSweep F3：计数键超上限淘汰最旧——内存有界，最新徽章语义保持。
        // 计数键仅在「丢弃」时产生：每个键构造「入队一条 + 重复丢弃一条」。
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10000);
        buf.setDedupeWindowMs(60000L);
        buf.addEntry(entry("SELECT key-0", 0L));
        buf.addEntry(entry("SELECT key-0", 1L)); // 丢弃 → 键 key-0 计数（最旧，将被淘汰）
        for (int i = 1; i <= 4096; i++) {
            buf.addEntry(entry("SELECT key-" + i, 0L));
            buf.addEntry(entry("SELECT key-" + i, 1L)); // 丢弃 → 键 key-$i 计数
        }
        assertEquals("最新键计数保留", 1, buf.dedupeCount("SELECT key-4096"));
        assertEquals("最旧键已淘汰", 0, buf.dedupeCount("SELECT key-0"));
        assertEquals("计数键总量封顶", 4096, buf.dedupeCountsSnapshot().size());
    }

    // ---------- F-44-2：dedupeExisting / disableDedupe ----------

    @Test
    public void dedupeExisting_按窗口裁剪既有条目() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        // 先关闭去重以便添加多条重复条目（验证 dedupeExisting 自身的去重逻辑，而非 addEntry 的）
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 200L));
        buf.addEntry(entry("SELECT 1", 400L));
        buf.addEntry(entry("SELECT 2", 800L));
        // 不动缓冲，先确认现有 4 条都在
        assertEquals(4, buf.snapshot().size());
        // 用户勾选「移除重复的sql」→ 启用 dedupeEnabled 后调用 dedupeExisting
        buf.setDedupeEnabled(true);
        int removed = buf.dedupeExisting();
        // SELECT 1 三个 → 留首条、丢 2 个；SELECT 2 不重复 → 0 个；总移除 2
        assertEquals(2, removed);
        assertEquals(2, buf.snapshot().size());
        assertEquals(0L, buf.snapshot().get(0).timestamp()); // 首条保留
        assertEquals("SELECT 2", buf.snapshot().get(1).restoredSql());
        assertEquals(2, buf.dedupeCount("SELECT 1"));
    }

    @Test
    public void dedupeExisting_关闭时不生效() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 100L));
        assertEquals(0, buf.dedupeExisting());
        assertEquals(2, buf.snapshot().size());
    }

    @Test
    public void dedupeExisting_空白差异副本按同键裁剪() throws Exception {
        // T-LogDedupeMpMark：勾选去重对既有跨格式副本（named trim / prepared 尾随空白）同样生效
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1  ", 200L));
        buf.setDedupeEnabled(true);
        assertEquals(1, buf.dedupeExisting());
        assertEquals(1, buf.snapshot().size());
        assertEquals("SELECT 1", buf.snapshot().get(0).restoredSql());
        assertEquals(1, buf.dedupeCount("SELECT 1"));
    }

    @Test
    public void dedupeExisting_单条不触发() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 0L));
        assertEquals(0, buf.dedupeExisting());
        assertEquals(1, buf.snapshot().size());
    }

    @Test
    public void dedupeExisting_跨窗口重复也裁剪() throws Exception {
        // T-LogRowNumber 追加：手动全量裁剪不看时间窗口——跨窗口（>dedupeWindowMs）的重复也必须裁掉，
        // 否则用户点击「移除重复」后重复项仍在列表（用户反馈「显示不正确」根因）。
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 2000L)); // 超 1s 窗口
        assertEquals(2, buf.snapshot().size());
        buf.setDedupeEnabled(true);
        assertEquals(1, buf.dedupeExisting()); // 跨窗口仍裁 1 条
        assertEquals(1, buf.snapshot().size());
        assertEquals(1, buf.dedupeCount("SELECT 1"));
    }

    @Test
    public void disableDedupe_清空累计计数() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 100L));
        // 关闭态 → 入列，不累计计数
        assertEquals(0, buf.dedupeCount("SELECT 1"));
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 200L)); // 现在窗口内，去重 +1
        assertEquals(1, buf.dedupeCount("SELECT 1"));
        buf.disableDedupe();
        assertEquals(0, buf.dedupeCount("SELECT 1"));
        assertFalse(buf.isDedupeEnabled());
    }

    @Test
    public void disableDedupe_重复调用幂等() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.disableDedupe();
        // 二次调用不应抛异常/重置状态报错
        buf.disableDedupe();
        assertEquals(0, buf.dedupeCount("SELECT 1"));
    }

    // ---------- T-LogRowNumber：捕获序号（列表行号） ----------

    @Test
    public void 行号连续入队单调递增() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 2", 100L));
        buf.addEntry(entry("SELECT 3", 200L));
        List<Long> seqs = buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList();
        assertEquals(List.of(1L, 2L, 3L), seqs);
    }

    @Test
    public void 去重丢弃不占号() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 0L));                 // #1
        assertFalse(buf.addEntry(entry("SELECT 1", 100L)));  // 丢弃
        buf.addEntry(entry("SELECT 2", 200L));               // 应为 #2（不跳号）
        List<Long> seqs = buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList();
        assertEquals(List.of(1L, 2L), seqs);
    }

    @Test
    public void 暂停态与过滤关键字不占号() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false); // 本用例专验暂停/过滤分支不占号（与既有去重用例隔离）
        buf.addEntry(entry("SELECT 1", 0L));  // #1
        buf.setPaused(true);
        assertFalse(buf.addEntry(entry("SELECT X", 100L)));
        buf.setPaused(false);
        buf.setFilterKeywords(List.of("TMP_"));
        assertFalse(buf.addEntry(entry("SELECT TMP_LOG", 200L)));
        buf.setFilterKeywords(List.of());
        buf.addEntry(entry("SELECT 2", 300L)); // 应为 #2
        List<Long> seqs = buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList();
        assertEquals(List.of(1L, 2L), seqs);
    }

    @Test
    public void clear后行号重新从1计数() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 2", 100L));
        buf.clear();
        buf.addEntry(entry("SELECT 3", 200L));
        List<LogSqlEntry> snap = buf.snapshot();
        assertEquals(1, snap.size());
        assertEquals(1L, snap.get(0).sequenceNumber());
    }

    @Test
    public void resetEntrySequence后新条目从1起旧条目行号保留() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.addEntry(entry("SELECT 1", 0L));  // #1
        buf.addEntry(entry("SELECT 2", 100L)); // #2
        buf.resetEntrySequence();
        // 旧条目不清、行号保留原值
        assertEquals(List.of(1L, 2L), buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList());
        buf.addEntry(entry("SELECT 3", 200L)); // 新一轮 #1
        assertEquals(1L, buf.snapshot().get(buf.snapshot().size() - 1).sequenceNumber());
    }

    @Test
    public void dedupeExisting裁剪后剩余条目行号保留原值() throws Exception {
        // T-LogRowNumber 用户确认：裁剪只删条目不重新编号，跳号表达「重复被移除」
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));    // #1
        buf.addEntry(entry("SELECT 1", 200L));  // #2（重复副本）
        buf.addEntry(entry("SELECT 2", 400L));  // #3
        buf.setDedupeEnabled(true);
        assertEquals(1, buf.dedupeExisting()); // 裁掉 #2
        List<LogSqlEntry> snap = buf.snapshot();
        assertEquals(List.of("SELECT 1", "SELECT 2"), snap.stream().map(LogSqlEntry::restoredSql).toList());
        assertEquals(List.of(1L, 3L), snap.stream().map(LogSqlEntry::sequenceNumber).toList()); // 跳号：#2 不复用
    }

    @Test
    public void 头部淘汰后剩余条目行号保留原值() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        buf.addEntry(entry("SELECT 1", 0L));    // #1，后被淘汰
        buf.addEntry(entry("SELECT 2", 100L));  // #2
        buf.addEntry(entry("SELECT 3", 200L));  // #3，触发淘汰 #1
        List<LogSqlEntry> snap = buf.snapshot();
        assertEquals(List.of("SELECT 2", "SELECT 3"), snap.stream().map(LogSqlEntry::restoredSql).toList());
        assertEquals(List.of(2L, 3L), snap.stream().map(LogSqlEntry::sequenceNumber).toList());
    }

    // ---------- O-147 T-AgentCaptureProvenance：同桶融合（冻结 target 用例的 Java 形态） ----------

    private LogSqlEntry consoleProvEntry(String sql, long ts) {
        return new LogSqlEntry(sql, sql, List.of(), ts, null,
            LogSqlEntry.summarizeSql(sql), SqlSource.MYBATIS, null, null, 0,
            List.of(), List.of(), null, 0L, null, null, null, null, null,
            null, CaptureProvenance.CONSOLE_BRIDGE);
    }

    private LogSqlEntry agentProvEntry(String sql, long ts) {
        return agentProvEntry(sql, ts, "src/main/java/com/example/RefundOrderService.java", 20L);
    }

    private LogSqlEntry agentProvEntry(String sql, long ts, String sourceFile, Long durationMs) {
        return new LogSqlEntry(sql, sql, List.of(), ts, null,
            LogSqlEntry.summarizeSql(sql), SqlSource.AGENT, durationMs,
            sourceFile, 67, List.of(new kotlin.Pair<>(sourceFile, 67)),
            List.of("com.example.RefundOrderService"), null, 0L,
            null, null, null, null, "PostgreSQL · cyzc@localhost:5432",
            null, CaptureProvenance.AGENT);
    }

    @Test
    public void O147控制台先到agent后到融合升级为可跳转() {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeWindowMs(60_000L);
        String sql = "SELECT COUNT(*) AS total FROM t WHERE station_id IN (250,205)";
        assertTrue(buf.addEntry(consoleProvEntry(sql, 1_000L)));
        assertFalse(buf.addEntry(agentProvEntry(sql, 2_000L)));
        List<LogSqlEntry> snap = buf.snapshot();
        assertEquals(1, snap.size());
        LogSqlEntry merged = snap.get(0);
        assertEquals(1L, merged.sequenceNumber());
        assertEquals(1_000L, merged.timestamp());
        assertEquals("src/main/java/com/example/RefundOrderService.java", merged.sourceFile());
        assertEquals(67, merged.sourceLine());
        assertEquals(20L, merged.executionTimeMs().longValue());
        assertEquals("PostgreSQL · cyzc@localhost:5432", merged.databaseName());
        assertEquals(List.of(new kotlin.Pair<>("src/main/java/com/example/RefundOrderService.java", 67)),
            merged.sourceCandidates());
        assertEquals(CaptureProvenance.MERGED, merged.captureProvenance());
        assertEquals(1, buf.dedupeCount(sql));
    }

    @Test
    public void O147agent先到控制台后到不降级() {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeWindowMs(60_000L);
        String sql = "SELECT * FROM t WHERE id = 7";
        assertTrue(buf.addEntry(agentProvEntry(sql, 1_000L)));
        assertFalse(buf.addEntry(consoleProvEntry(sql, 2_000L)));
        LogSqlEntry merged = buf.snapshot().get(0);
        assertEquals("src/main/java/com/example/RefundOrderService.java", merged.sourceFile());
        assertEquals(20L, merged.executionTimeMs().longValue());
        assertEquals(CaptureProvenance.MERGED, merged.captureProvenance());
    }

    @Test
    public void O147融合只补空不覆盖既有值() {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeWindowMs(60_000L);
        assertTrue(buf.addEntry(agentProvEntry("SELECT 1", 1_000L)));
        assertFalse(buf.addEntry(agentProvEntry("SELECT 1", 2_000L)));
        LogSqlEntry merged = buf.snapshot().get(0);
        assertEquals(20L, merged.executionTimeMs().longValue());
        assertEquals(CaptureProvenance.AGENT, merged.captureProvenance());
    }

    @Test
    public void O147MP分页伪标记被真实源码升级() {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeWindowMs(60_000L);
        String sql = "SELECT COUNT(*) FROM biz";
        LogSqlEntry paginated = new LogSqlEntry(sql, sql, List.of(), 1_000L, null,
            LogSqlEntry.summarizeSql(sql), SqlSource.MYBATIS, null,
            SourceMarkers.MP_PAGINATION_COUNT, 0, List.of(), List.of(), null, 0L,
            null, null, null, null, null, null, CaptureProvenance.CONSOLE_EP);
        assertTrue(buf.addEntry(paginated));
        assertFalse(buf.addEntry(agentProvEntry(sql, 2_000L)));
        assertEquals("分页 COUNT 的伪标记让位于真实调用点",
            "src/main/java/com/example/RefundOrderService.java", buf.snapshot().get(0).sourceFile());
    }

    @Test
    public void O147融合条目随淘汰回收后同键新观测正常入库() {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        buf.setDedupeWindowMs(60_000L);
        buf.addEntry(consoleProvEntry("A", 1_000L));
        buf.addEntry(agentProvEntry("A", 2_000L));
        buf.addEntry(consoleProvEntry("B", 3_000L));
        buf.addEntry(consoleProvEntry("C", 4_000L));
        assertEquals(List.of("B", "C"), buf.snapshot().stream().map(LogSqlEntry::restoredSql).toList());
        assertTrue(buf.addEntry(consoleProvEntry("A", 5_000L)));
        assertEquals(List.of("C", "A"), buf.snapshot().stream().map(LogSqlEntry::restoredSql).toList());
    }
}
