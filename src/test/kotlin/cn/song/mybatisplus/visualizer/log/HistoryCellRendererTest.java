package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import static cn.song.mybatisplus.visualizer.config.PluginSettings.LOG_TIMESTAMP_SOURCE_CAPTURE;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JList;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * 历史面板两级导航渲染器 UI 测试（F-44-3 渲染健壮性；T-LogListFreeze-04 迁移到片段通道）：
 *
 * 直接渲染 {@link HistoryCellRenderer}（二级 SQL 条目）与 {@link HistoryFileCellRenderer}（一级文件条目）覆盖：
 *  - 通道根治断言：渲染行为 SimpleColoredComponent 系（JLabel-HTML 通道不允许回潮）
 *  - 二级条目两行结构（T-TwoLine）：元信息行 [耗时] [时间戳] [来源] 在上、SQL 高亮行在下
 *  - 二级条目：空耗时 / 空源码 / 特殊字符 / 超长 SQL 渲染不崩溃
 *  - 一级文件：文件大小格式化边界（0 B / 1.0 KB / 1.0 MB）、条数随文件名展示
 */
public class HistoryCellRendererTest extends BasePlatformTestCase {

    private final HistoryCellRenderer sqlRenderer = new HistoryCellRenderer();
    private final HistoryFileCellRenderer fileRenderer = new HistoryFileCellRenderer();
    /** 真实（非空）列表实例；渲染器取 selectionBackground 等需 list 非空（生产环境 list 恒非空）。 */
    private final JList<Object> list = new JList<>();

    private HistoryCellRenderer renderSql(LogSqlEntry entry) {
        return renderSql(entry, false);
    }

    private HistoryCellRenderer renderSql(LogSqlEntry entry, boolean selected) {
        java.awt.Component c = sqlRenderer.getListCellRendererComponent(list, entry, 0, selected, false);
        return (HistoryCellRenderer) c;
    }

    private HistoryFileCellRenderer renderFile(File file, int sqlCount) {
        java.awt.Component c = fileRenderer.getListCellRendererComponent(list, new HistoryFileEntry(file, sqlCount), 0, false, false);
        return (HistoryFileCellRenderer) c;
    }

    private String joinedText(SimpleColoredComponent component) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = component.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
        }
        return sb.toString();
    }

    private static LogSqlEntry sqlEntry(String sql) {
        return sqlEntry(sql, null, 0, null);
    }

    private static LogSqlEntry sqlEntry(String sql, String sourceFile, int sourceLine, Long ms) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), 1_700_000_000_000L, sourceFile, sourceLine, ms);
    }

    /** 既有用例锁定单行语义（T-ListMode 默认关键字换行——模式相关行为由专区用例显式切模式锁定）。 */
    private String savedDisplayMode;

    // T-AgentDialectLoop 修复串染：保存值曾用「finally 里现读」——set 之后现读到的已是改后值，
    // 还原恒失败并把 HH:mm:ss 泄漏到同 worker 后续类（新测试类改变分片内类顺序后暴露）。
    // 改为 setUp 捕获 / tearDown 统一还原（与 SqlLogSearchRowTimeTest 同范式）。
    private String savedTsFormat = "";
    private boolean savedTsEnabled = true;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PluginSettings.State state = PluginSettings.getInstance().getState();
        savedDisplayMode = state.getLogListSqlDisplayMode();
        savedTsFormat = state.getLogTimestampFormat();
        savedTsEnabled = state.getLogTimestampEnabled();
        state.setLogListSqlDisplayMode("SINGLE_LINE");
    }

    @Override
    protected void tearDown() throws Exception {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        state.setLogTimestampFormat(savedTsFormat);
        state.setLogTimestampEnabled(savedTsEnabled);
        state.setLogListSqlDisplayMode(savedDisplayMode != null
                ? savedDisplayMode : PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE);
        super.tearDown();
    }

    // ---------- O-90：同模板 ×N 徽章 ----------

    @Test
    public void test二级条目_同模板徽章达阈值渲染() throws Exception {
        String sql = "SELECT * FROM hist_orders WHERE uid = 7";
        sqlRenderer.templateCounts = Map.of(LogSqlModelsKt.structureFingerprint(sql), 3);
        String meta = joinedText(renderSql(sqlEntry(sql, null, 0, null)).metaLine);
        assertTrue("达阈值应渲染同模板徽章",
                meta.contains(MpSqlVisualizerBundle.message("log.panel.templateBadge", 3)));
        sqlRenderer.templateCounts = Map.of(LogSqlModelsKt.structureFingerprint(sql), 2);
        assertFalse("未达阈值不渲染",
                joinedText(renderSql(sqlEntry(sql, null, 0, null)).metaLine)
                        .contains(MpSqlVisualizerBundle.message("log.panel.templateBadge", 2)));
        sqlRenderer.templateCounts = Map.of();
        assertFalse("无计数不渲染",
                joinedText(renderSql(sqlEntry(sql, null, 0, null)).metaLine)
                        .contains(MpSqlVisualizerBundle.message("log.panel.templateBadge", 3)));
    }

    @Test
    public void test二级条目_片段通道_非HTML标签() throws Exception {
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT * FROM user", null, 0, null));
        assertTrue("元信息行应为 SimpleColoredComponent（JLabel-HTML 通道不允许回潮）",
                r.metaLine instanceof SimpleColoredComponent);
        String meta = joinedText(r.metaLine);
        String sql = joinedText(r.getSqlLine());
        assertTrue("含 SQL 关键字", sql.contains("SELECT"));
        assertTrue("无耗时显示占位符 —（与日志页同款无括号）", meta.contains("—"));
        assertTrue("无源码显示占位符 unknown", meta.contains("[unknown]"));
        assertFalse("无耗时不出现 ms", meta.contains("ms"));
        assertFalse("渲染文本不应为 HTML 包裹", sql.startsWith("<html>"));
    }

    @Test
    public void test二级条目_含源码位置_渲染元信息行() throws Exception {
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT 1", "UserMapper.java", 12, 3L));
        String meta = joinedText(r.metaLine);
        assertTrue("应渲染耗时 3ms（与日志页同款无括号）", meta.contains("3ms"));
        assertTrue("应渲染源码 [UserMapper.java:12]", meta.contains("[UserMapper.java:12]"));
        assertFalse("SQL 不应混入元信息行", meta.contains("SELECT"));
    }

    @Test
    public void test二级条目_特殊字符SQL原文保真() throws Exception {
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT * FROM t WHERE a < 5 AND b > 3", null, 0, null));
        String sql = joinedText(r.getSqlLine());
        assertTrue("小于号原文保留", sql.contains("< 5"));
        assertTrue("大于号原文保留", sql.contains("> 3"));
    }

    @Test
    public void test二级条目_超长SQL渲染不异常() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            if (i > 1) sb.append(", ");
            sb.append("c").append(i);
        }
        sb.append(" FROM t WHERE id = 1");
        HistoryCellRenderer r = renderSql(sqlEntry(sb.toString(), null, 0, null));
        assertTrue("超长 SQL 摘要含省略号", joinedText(r.getSqlLine()).contains("…"));
    }

    // ---------- T-TwoLine：元信息换行显示（元信息行在上，SQL 行在下） ----------

    @Test
    public void test二级条目_元信息在上SQL在下两行显示() throws Exception {
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT * FROM orders WHERE id = 1", "OrderDao.java", 9, 30L));
        String meta = joinedText(r.metaLine);
        assertTrue("耗时应在元信息行", meta.contains("30ms"));
        assertTrue("来源应在元信息行", meta.contains("[OrderDao.java:9]"));
        assertTrue("SQL 应在 SQL 行", joinedText(r.getSqlLine()).contains("SELECT"));
        assertFalse("来源不应混入 SQL 行", joinedText(r.getSqlLine()).contains("OrderDao.java"));
        assertTrue("元信息行恒存在（T-TwoLine 两行结构，占位符归元信息行）", meta.contains("[") && meta.contains("]"));
    }

    @Test
    public void test二级条目_空耗时与未知来源占位渲染在元信息行() throws Exception {
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT * FROM user", null, 0, null));
        String meta = joinedText(r.metaLine);
        assertTrue("空耗时占位 — 应在元信息行", meta.contains("—"));
        assertTrue("未知来源占位 [unknown] 应在元信息行", meta.contains("[unknown]"));
        assertTrue("SQL 应在 SQL 行", joinedText(r.getSqlLine()).contains("SELECT"));
    }

    // ---------- T-ListMode：SQL 三种显示模式 ----------

    @Test
    public void test二级条目_关键字换行模式SQL子句分行() throws Exception {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String saved = state.getLogListSqlDisplayMode();
        state.setLogListSqlDisplayMode("KEYWORD_WRAP");
        try {
            HistoryCellRenderer r = renderSql(sqlEntry("SELECT * FROM orders WHERE id = 1", "OrderDao.java", 9, 0L));
            assertTrue("FROM 应独立成行", r.getSqlLines().stream().anyMatch(it -> joinedText(it).startsWith("FROM")));
            assertTrue("WHERE 应独立成行", r.getSqlLines().stream().anyMatch(it -> joinedText(it).startsWith("WHERE")));
            assertFalse("元信息行不混入 SQL", joinedText(r.metaLine).contains("SELECT"));
        } finally {
            state.setLogListSqlDisplayMode(saved);
        }
    }

    @Test
    public void test一级文件_大小为0字节显示0B() throws Exception {
        File f = File.createTempFile("hist0", ".sql");
        f.deleteOnExit();
        String text = joinedText(renderFile(f, 0).line);
        assertTrue("0 字节应显示 0 B", text.contains("0 B"));
        assertTrue("应显示条数 0 条 SQL", text.contains("0"));
        assertTrue("应展示文件名(加粗)", text.contains(f.getName()));
    }

    @Test
    public void test一级文件_大小恰为1KB显示1点0KB() throws Exception {
        File f = File.createTempFile("hist1k", ".sql");
        f.deleteOnExit();
        Files.write(f.toPath(), new byte[1024]);
        String text = joinedText(renderFile(f, 3).line);
        assertTrue("1024 字节应显示 1.0 KB", text.contains("1.0 KB"));
        assertTrue("应显示 3 条 SQL", text.contains("3"));
    }

    @Test
    public void test一级文件_大小恰为1MB显示1点0MB() throws Exception {
        File f = File.createTempFile("hist1m", ".sql");
        f.deleteOnExit();
        Files.write(f.toPath(), new byte[1024 * 1024]);
        String text = joinedText(renderFile(f, 7).line);
        assertTrue("1MB 应显示 1.0 MB", text.contains("1.0 MB"));
        assertTrue("应显示 7 条 SQL", text.contains("7"));
    }

    @Test
    public void test一级文件_渲染含文件名与条数_且文件名加粗() throws Exception {
        File f = File.createTempFile("my-history-file", ".sql");
        f.deleteOnExit();
        Files.write(f.toPath(), new byte[500]);
        HistoryFileCellRenderer r = renderFile(f, 12);
        String text = joinedText(r.line);
        assertTrue("文件名应展示", text.contains(f.getName()));
        // 条数文案来自 bundle：log.history.sqlCount = {0} 条 SQL
        assertTrue("应展示条数 12", text.contains("12"));
        SimpleColoredComponent.ColoredIterator it = r.line.iterator();
        it.next();
        SimpleTextAttributes attrs = it.getTextAttributes();
        assertTrue("文件名片段应加粗（原 HTML <b> 语义）",
                (attrs.getStyle() & SimpleTextAttributes.STYLE_BOLD) != 0);
    }

    // ---------- T-CrudColor：二级 SQL 列表 CRUD 类型颜色标记（与日志页同构） ----------

    private void withCrudColors(String style, Runnable block) {
        PluginSettings.State s = PluginSettings.getInstance().getState();
        String savedStyle = s.getCrudColorStyle();
        List<String> saved = List.of(s.getCrudColorSelect(), s.getCrudColorInsert(),
                s.getCrudColorUpdate(), s.getCrudColorDelete());
        try {
            s.setCrudColorStyle(style);
            s.setCrudColorSelect("#4A86E8");
            s.setCrudColorInsert("#4CAF50");
            s.setCrudColorUpdate("#E67E22");
            s.setCrudColorDelete("#D32F2F");
            block.run();
        } finally {
            s.setCrudColorStyle(savedStyle);
            s.setCrudColorSelect(saved.get(0));
            s.setCrudColorInsert(saved.get(1));
            s.setCrudColorUpdate(saved.get(2));
            s.setCrudColorDelete(saved.get(3));
        }
    }

    private java.awt.Color barColorAfterRender(String sql, boolean selected) {
        renderSql(sqlEntry(sql, null, 0, null), selected);
        return sqlRenderer.colorBar.isOpaque() ? sqlRenderer.colorBar.getBackground() : null;
    }

    private java.awt.Color barColorAfterRender(String sql) {
        return barColorAfterRender(sql, false);
    }

    @Test
    public void test二级条目_色条样式_四类型着色且OTHER不标记() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BAR, () -> {
            assertEquals(new java.awt.Color(0x4A86E8), barColorAfterRender("SELECT * FROM user"));
            assertEquals(new java.awt.Color(0x4CAF50), barColorAfterRender("INSERT INTO user VALUES (1)"));
            assertEquals(new java.awt.Color(0xE67E22), barColorAfterRender("UPDATE user SET a = 1"));
            assertEquals(new java.awt.Color(0xD32F2F), barColorAfterRender("DELETE FROM user"));
            assertEquals("OTHER 不应着色", null, barColorAfterRender("TRUNCATE TABLE user"));
        });
    }

    @Test
    public void test二级条目_色条样式_选中态色条不丢色() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BAR, () -> {
            assertEquals(new java.awt.Color(0x4A86E8), barColorAfterRender("SELECT * FROM user", true));
        });
    }

    @Test
    public void test二级条目_背景样式_未选中淡背景混色_选中走平台填充() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND, () -> {
            java.awt.Color bg = list.getBackground();
            renderSql(sqlEntry("UPDATE user SET a = 1", null, 0, null));
            assertEquals("未选中 14% 混色（类型淡背景标记保留）",
                    SqlListTypeColor.blend(bg, new java.awt.Color(0xE67E22), SqlListTypeColor.MIX_NORMAL),
                    sqlRenderer.getBackground());
            renderSql(sqlEntry("UPDATE user SET a = 1", null, 0, null), true);
            assertEquals("选中态交给平台选中色（T-UN-09 描边退役）",
                    list.getSelectionBackground(), sqlRenderer.getBackground());
        });
    }

    @Test
    public void test二级条目_背景样式_选中态不再安装描边边框() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND, () -> {
            renderSql(sqlEntry("UPDATE user SET a = 1", null, 0, null));
            assertSame("未选中为普通边框", sqlRenderer.normalBorder, sqlRenderer.getBorder());
            renderSql(sqlEntry("UPDATE user SET a = 1", null, 0, null), true);
            assertSame("选中态恢复平台选中绘制，边框与未选中一致（描边已退役）",
                    sqlRenderer.normalBorder, sqlRenderer.getBorder());
        });
    }

    @Test
    public void test二级条目_重复渲染不产生属性实例变更() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BAR, () -> {
            renderSql(sqlEntry("SELECT 1", null, 0, null));
            java.awt.Color barBg = sqlRenderer.colorBar.getBackground();
            javax.swing.border.Border border = sqlRenderer.getBorder();
            renderSql(sqlEntry("SELECT 2", null, 0, null));
            assertSame("同色格应复用同一 Color 实例（否则每格一次 repaint）",
                    barBg, sqlRenderer.colorBar.getBackground());
            assertSame("同状态应复用同一 Border 实例", border, sqlRenderer.getBorder());
        });
    }

    @Test
    public void test二级条目_关闭样式_色条不可见且背景走原逻辑() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_OFF, () -> {
            assertEquals(null, barColorAfterRender("SELECT * FROM user"));
            assertEquals(list.getBackground(), sqlRenderer.getBackground());
        });
    }

    // ---------- T-LogHistoryRowNo：历史列表行号片段 ----------

    @Test
    public void test二级条目_行号片段在元信息行最前() throws Exception {
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT 1", null, 0, null).withSequenceNumber(12L));
        String meta = joinedText(r.metaLine);
        assertTrue("元信息行含行号 #12", meta.contains("#12 "));
        assertTrue("行号应在片段序列最前", meta.startsWith("#12 "));
        assertFalse("SQL 不混入元信息行", meta.contains("SELECT"));
    }

    @Test
    public void test二级条目_无行号条目不渲染行号片段() throws Exception {
        // sequenceNumber=0：旧文件读回 / 未注入条目
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT 1", null, 0, null));
        assertFalse("无行号不渲染 # 前缀", joinedText(r.metaLine).contains("#"));
    }

    @Test
    public void test二级条目_行号在耗时占位之前() throws Exception {
        LogSqlEntry e = sqlEntry("SELECT 1", "UserMapper.java", 3, 3L).withSequenceNumber(5L);
        String meta = joinedText(renderSql(e).metaLine);
        int idxRow = meta.indexOf("#5 ");
        int idxDur = meta.indexOf("3ms");
        assertTrue("应同时含行号与耗时", idxRow >= 0 && idxDur >= 0);
        assertTrue("行号在耗时之前", idxRow < idxDur);
    }

    @Test
    public void test二级条目_元信息顺序与日志页一致_时间在耗时前() throws Exception {
        // 用户反馈：历史页原为「耗时 → 时间」，与日志页「时间 → 耗时」相反
        PluginSettings.State s = PluginSettings.getInstance().getState();
        boolean savedTs = s.getLogTimestampEnabled();
        String savedSrc = s.getLogTimestampSource();
        try {
            s.setLogTimestampEnabled(true);
            s.setLogTimestampSource(LOG_TIMESTAMP_SOURCE_CAPTURE);
            LogSqlEntry e = sqlEntry("SELECT 1", "UserMapper.java", 3, 3L).withSequenceNumber(5L);
            String meta = joinedText(renderSql(e).metaLine);
            int idxRow = meta.indexOf("#5 ");
            int idxTs = meta.indexOf("[");
            int idxDur = meta.indexOf("3ms");
            assertTrue("应含行号/时间/耗时", idxRow >= 0 && idxTs >= 0 && idxDur >= 0);
            assertTrue("行号在最前", idxRow < idxTs);
            assertTrue("时间应在耗时之前（对齐日志页）", idxTs < idxDur);
        } finally {
            s.setLogTimestampEnabled(savedTs);
            s.setLogTimestampSource(savedSrc);
        }
    }

    // ---------- T-McpAuditTrail T-MAT-05：历史页 MCP 徽标（与日志页同落点同协议） ----------

    /** Kotlin `entry.copy(source = …, databaseName = …)` 的 Java 等价（record 无 copy）。 */
    private static LogSqlEntry withSourceAndDatabase(LogSqlEntry e, SqlSource source, String databaseName) {
        return new LogSqlEntry(
                e.rawSql(), e.restoredSql(), e.params(), e.timestamp(), e.logTimestamp(),
                e.summary(), source, e.executionTimeMs(), e.sourceFile(), e.sourceLine(),
                e.sourceCandidates(), e.candidateClasses(), e.stackDigest(), e.sequenceNumber(),
                e.mapperStatement(), e.errorText(), e.requestTag(), e.updateCount(), databaseName);
    }

    @Test
    public void test二级条目_MCP来源_渲染徽标且与目标库角标共存() throws Exception {
        LogSqlEntry e = withSourceAndDatabase(sqlEntry("SELECT id FROM user WHERE id = 1", null, 0, 7L),
                SqlSource.MCP, "MySQL");
        String meta = joinedText(renderSql(e).metaLine);
        assertTrue("历史页元信息行应含 MCP 徽标: " + meta, meta.contains("[MCP] "));
        assertTrue("与目标库角标共存: " + meta, meta.contains("[MySQL] "));
        assertTrue("耗时同列展示: " + meta, meta.contains("7ms"));
    }

    @Test
    public void test二级条目_非MCP来源_不渲染徽标() throws Exception {
        LogSqlEntry e = sqlEntry("SELECT 1", "UserMapper.java", 3, 3L);
        String meta = joinedText(renderSql(e).metaLine);
        assertFalse("非 MCP 来源不应出现徽标: " + meta, meta.contains("[MCP]"));
    }

    @Test
    public void test二级条目_落盘再读回_徽标仍在() throws Exception {
        // 「重启后徽标仍在」验收的端到端半边：写盘 → 历史读端解析 → 渲染器出徽标。
        // 改前读端不回填来源（恒 UNKNOWN），本条红——徽标只在当次会话内存里存在。
        File dir = Files.createTempDirectory("mcp-badge-").toFile();
        dir.deleteOnExit();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(new LogSqlEntry(
                "SELECT id FROM user WHERE id = 1",
                "SELECT id FROM user WHERE id = 1",
                List.<LogSqlParam>of(),
                TEST_TIMESTAMP,
                null,
                LogSqlEntry.summarizeSql("SELECT id FROM user WHERE id = 1"),
                SqlSource.MCP,
                7L,
                null,
                0,
                List.of(),
                List.of(),
                null,
                0L,
                null,
                null,
                null,
                null,
                "MySQL"));
        saver.close();

        File[] files = dir.listFiles(f -> f.getName().endsWith(".sql"));
        assertNotNull("应写出历史文件", files);
        LogSqlEntry read = SqlLogFileReader.INSTANCE.readFromFile(files[0]).get(0);
        String meta = joinedText(renderSql(read).metaLine);
        assertTrue("读回条目来源应仍为 MCP: " + read.source(), read.source() == SqlSource.MCP);
        assertTrue("读回后渲染仍出 MCP 徽标: " + meta, meta.contains("[MCP] "));
        assertTrue("目标库角标共存: " + meta, meta.contains("[MySQL] "));
    }

    // ---------- T-JdbcThreadBucket F3：历史页时间戳跟随设置（2026-09-15 移除硬编码 yyyy-MM-dd HH:mm:ss） ----------

    private static final long TEST_TIMESTAMP = 1_700_000_000_000L;

    private String expectedFormatted(String pattern) {
        try {
            return new java.text.SimpleDateFormat(pattern).format(new java.util.Date(TEST_TIMESTAMP));
        } catch (IllegalArgumentException e) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(TEST_TIMESTAMP));
        }
    }

    private void setTimestampFormat(String format) {
        PluginSettings.getInstance().getState().setLogTimestampFormat(format);
    }

    private void setTimestampEnabled(boolean enabled) {
        PluginSettings.getInstance().getState().setLogTimestampEnabled(enabled);
    }

    @Test
    public void test历史页时间戳_跟随设置格式() throws Exception {
        String fmt = "HH:mm:ss";
        String expected = expectedFormatted(fmt); // 系统时区下的 HH:mm:ss
        setTimestampFormat(fmt);
        setTimestampEnabled(true);
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT 1", null, 0, 3L));
        String meta = joinedText(r.metaLine);
        assertTrue("应渲染为设置格式 HH:mm:ss（期望 " + expected + "，实际 meta=" + meta + "）",
                meta.contains("[" + expected + "]"));
    }

    @Test
    public void test历史页时间戳_显示开关关闭则隐藏() throws Exception {
        setTimestampEnabled(false);
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT 1", null, 0, 3L));
        String meta = joinedText(r.metaLine);
        assertFalse("关闭时间戳后不应含时间片段 " + meta, meta.contains(expectedFormatted("yyyy-MM-dd")));
    }

    @Test
    public void test历史页时间戳_非法pattern回退默认() throws Exception {
        setTimestampFormat("not-a-valid-pattern-%%");
        HistoryCellRenderer r = renderSql(sqlEntry("SELECT 1", null, 0, 3L));
        String meta = joinedText(r.metaLine);
        String expected = expectedFormatted("not-a-valid-pattern-%%"); // 回退默认 yyyy-MM-dd HH:mm:ss
        assertTrue("非法 pattern 应回退默认格式（期望 " + expected + "）", meta.contains("[" + expected + "]"));
    }
}
