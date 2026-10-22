package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JList;
import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * 日志列表单元格渲染器 UI 测试（F-44-2/T-LogListFreeze-04 回归锁）：
 *
 * 直接渲染 {@link LogEntryListCellRenderer} 覆盖此前测试抓不到的 UI 问题。
 * 片段通道（双 {@link SimpleColoredComponent}，T-TwoLine 两行结构）下锁定：
 *  - 通道根治断言：渲染行为 SimpleColoredComponent 系（JLabel-HTML 通道即 BasicHTML 解析 = Freeze 根因，不允许回潮）
 *  - 两行结构：元信息行在上（时间戳/耗时/来源/徽章）、SQL 高亮行在下，SQL 不混入元信息行
 *  - 超长 SQL（>160 摘要截断）渲染不异常
 *  - 耗时着色分档：慢查询红 0xD32F2F、偏慢橙 0xE67E22、正常灰 0x9E9E9E、选中白 0xFFFFFF（原 HTML 配色语义平移）
 *  - 去重 ⨉N 徽章随 dedupeCounts 渲染
 */
public class SqlLogPanelRendererUiTest extends BasePlatformTestCase {

    private final LogEntryListCellRenderer renderer = new LogEntryListCellRenderer();
    /** 真实（非空）列表实例；渲染器取 selectionBackground 等需 list 非空（生产环境 list 恒非空）。 */
    private final JList<Object> list = new JList<>();

    /** 既有用例锁定单行语义（T-ListMode 默认关键字换行——模式相关行为由专区用例显式切模式锁定）。 */
    private String savedDisplayMode;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PluginSettings.State state = PluginSettings.getInstance().getState();
        savedDisplayMode = state.getLogListSqlDisplayMode();
        state.setLogListSqlDisplayMode("SINGLE_LINE");
    }

    @Override
    protected void tearDown() throws Exception {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        state.setLogListSqlDisplayMode(savedDisplayMode != null
                ? savedDisplayMode : PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE);
        super.tearDown();
    }

    /** 触发渲染并返回渲染组件（片段通道：元信息行/SQL 行经 metaLine/sqlLine 断言）。 */
    private LogEntryListCellRenderer render(LogSqlEntry entry) {
        return render(entry, false);
    }

    private LogEntryListCellRenderer render(LogSqlEntry entry, boolean selected) {
        java.awt.Component c = renderer.getListCellRendererComponent(list, entry, 0, selected, false);
        return (LogEntryListCellRenderer) c;
    }

    /** 切显示模式渲染（T-ListMode 专区用例；自动随测试收尾还原）。 */
    private LogEntryListCellRenderer renderInMode(String mode, LogSqlEntry entry) {
        PluginSettings.getInstance().getState().setLogListSqlDisplayMode(mode);
        return render(entry);
    }

    private String joinedText(SimpleColoredComponent component) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = component.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
        }
        return sb.toString();
    }

    private static LogSqlEntry entry(String sql) {
        return entry(sql, null, null, 0);
    }

    private static LogSqlEntry entry(String sql, Long ms, String sourceFile, int sourceLine) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), 1_700_000_000_000L, sourceFile, sourceLine, ms);
    }

    /** agent 通道形态条目（source=AGENT，可带目标库/影响行数/报错）。 */
    private static LogSqlEntry agentEntry(String sql, String databaseName, Long updateCount, String errorText) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), 1_700_000_000_000L, null,
                LogSqlEntry.summarizeSql(sql), SqlSource.AGENT, null, null, 0, List.of(), List.of(), null, 0L,
                null, errorText, null, updateCount, databaseName);
    }

    @Test
    public void test渲染组件为片段通道_非HTML标签() throws Exception {
        LogEntryListCellRenderer r = render(entry("SELECT * FROM user"));
        assertTrue("元信息行应为 SimpleColoredComponent（JLabel-HTML 通道不允许回潮）",
                r.metaLine instanceof SimpleColoredComponent);
        assertTrue("SQL 行应含 SELECT", joinedText(r.getSqlLine()).contains("SELECT"));
        assertFalse("渲染文本不应为 HTML 包裹", joinedText(r.getSqlLine()).startsWith("<html>"));
        assertFalse("无耗时不应出现 ms", joinedText(r.metaLine).contains("ms"));
    }

    @Test
    public void test两行结构_元信息在上SQL在下() throws Exception {
        String sql = "SELECT * FROM repeated WHERE id = 1";
        renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 2);
        LogEntryListCellRenderer r = render(entry(sql, null, "src/main/java/A.java", 42));
        String meta = joinedText(r.metaLine);
        assertTrue("元信息行含来源 [文件:行]", meta.contains("[A.java:42]"));
        assertTrue("元信息行含去重徽章", meta.contains("⨉2"));
        assertFalse("元信息行不应混入 SQL", meta.contains("SELECT"));
        assertTrue("SQL 行完整保留语句（含分号结束符）",
                joinedText(r.getSqlLine()).contains("SELECT * FROM repeated WHERE id = 1;"));
    }

    @Test
    public void test目标库角标_有库名渲染到元信息行() throws Exception {
        // T-AgentDialectLoop：agent 条目带 databaseName → 元信息行出现 [MySQL] 角标
        String meta = joinedText(render(agentEntry("SELECT 1", "MySQL", null, null)).metaLine);
        assertTrue("元信息行应含目标库角标", meta.contains("[MySQL] "));
    }

    @Test
    public void test目标库角标_未知库名不显示() throws Exception {
        String meta = joinedText(render(agentEntry("SELECT 1", null, null, null)).metaLine);
        assertFalse("无 databaseName 不应显示角标", meta.contains("[MySQL]"));
    }

    @Test
    public void testMCP徽标_来源MCP渲染到元信息行() throws Exception {
        // T-McpAuditTrail T-MAT-05：MCP 执行类工具落历史的条目带 [MCP] 徽标（目标库角标后、来源前）
        LogSqlEntry e = new LogSqlEntry(
                "SELECT id FROM user WHERE id = 1",
                "SELECT id FROM user WHERE id = 1",
                List.<LogSqlParam>of(),
                1_700_000_000_000L,
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
                "MySQL");
        String meta = joinedText(render(e).metaLine);
        assertTrue("元信息行应含 MCP 徽标: " + meta, meta.contains("[MCP] "));
        assertTrue("MCP 徽标与目标库角标共存: " + meta, meta.contains("[MySQL] "));
        assertTrue("耗时同列展示: " + meta, meta.contains("7ms"));
    }

    @Test
    public void testMCP徽标_非MCP来源不显示() throws Exception {
        LogSqlEntry e = new LogSqlEntry(
                "SELECT 1",
                "SELECT 1",
                List.<LogSqlParam>of(),
                1_700_000_000_000L,
                null,
                LogSqlEntry.summarizeSql("SELECT 1"),
                SqlSource.MYBATIS,
                null,
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
                null);
        String meta = joinedText(render(e).metaLine);
        assertFalse("非 MCP 来源不应显示 MCP 徽标: " + meta, meta.contains("[MCP]"));
    }

    @Test
    public void test特殊字符SQL_原文保真() throws Exception {
        // 片段通道无 HTML 解析面——< > & 原样绘制即安全（原通道需转义，此处断言不转义不破版）
        LogEntryListCellRenderer r = render(entry("SELECT * FROM t WHERE a < 5 AND b > 3 AND c & d"));
        String sqlText = joinedText(r.getSqlLine());
        assertTrue("小于号原文保留", sqlText.contains("< 5"));
        assertTrue("大于号原文保留", sqlText.contains("> 3"));
        assertTrue("与号原文保留", sqlText.contains("& d"));
    }

    @Test
    public void testSQL含标签样文本_不构成HTML结构() throws Exception {
        LogEntryListCellRenderer r = render(entry("SELECT '</span><script>'"));
        assertTrue("标签样文本原样保留（片段通道无注入面）",
                joinedText(r.getSqlLine()).contains("</span><script>"));
    }

    @Test
    public void test超长SQL_渲染不异常且含截断摘要() throws Exception {
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 1; i <= 200; i++) {
            if (i > 1) sb.append(", ");
            sb.append("col_").append(i);
        }
        sb.append(" FROM wide_table WHERE id = 1");
        String longSql = sb.toString();
        assertTrue("构造的 SQL 超过 160 字符", longSql.length() > 160);
        LogEntryListCellRenderer r = render(entry(longSql));
        String sqlText = joinedText(r.getSqlLine());
        assertTrue("摘要截断开头仍是 SELECT", sqlText.contains("SELECT"));
        assertTrue("超长 SQL 摘要含省略号", sqlText.contains("…"));
    }

    @Test
    public void test慢查询耗时着色分档_红橙灰() throws Exception {
        PluginSettings.State pluginSettings = PluginSettings.getInstance().getState();
        long saved = pluginSettings.getSlowQueryThresholdMs();
        try {
            pluginSettings.setSlowQueryThresholdMs(1000);
            assertEquals("慢查询(2000ms)应红色", new Color(0xD32F2F),
                    SqlLogPanelKt.formatDurationFragment(2000L, false).getSecond().getFgColor());
            assertEquals("偏慢(600ms >= 阈值/2)应橙色", new Color(0xE67E22),
                    SqlLogPanelKt.formatDurationFragment(600L, false).getSecond().getFgColor());
            // 分档语义：ms==null 才灰色；正常（< 阈值/2）为绿（原通道该断言蹭到时间戳灰，片段直读属性后修正）
            assertEquals("正常(50ms)应绿色", new Color(0x4CAF50),
                    SqlLogPanelKt.formatDurationFragment(50L, false).getSecond().getFgColor());
        } finally {
            pluginSettings.setSlowQueryThresholdMs(saved);
        }
    }

    @Test
    public void test选中态耗时着色用白色_不抢SQL高亮() throws Exception {
        kotlin.Pair<String, com.intellij.ui.SimpleTextAttributes> f = SqlLogPanelKt.formatDurationFragment(2000L, true);
        assertEquals("选中态耗时套白色（避免与选中背景/SQL 高亮冲突）", new Color(0xFFFFFF), f.getSecond().getFgColor());
    }

    @Test
    public void test去重徽章随dedupeCounts渲染() throws Exception {
        String sql = "SELECT * FROM repeated WHERE id = 1";
        renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 4);
        assertTrue("去重 ⨉4 徽章应渲染", joinedText(render(entry(sql)).metaLine).contains("⨉4"));

        renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 0);
        assertFalse("计数 0 不显示徽章", joinedText(render(entry(sql)).metaLine).contains("⨉"));

        renderer.dedupeCounts = Map.of();
        assertFalse("无计数不显示徽章", joinedText(render(entry(sql)).metaLine).contains("⨉"));
    }

    @Test
    public void test渲染返回值非空_组件有效() throws Exception {
        java.awt.Component c = renderer.getListCellRendererComponent(list, entry("SELECT 1"), 0, false, false);
        assertNotNull("渲染组件不应为 null", c);
        assertTrue("渲染组件应为渲染器自身（含双行组件面板）", c instanceof java.awt.Component);
    }

    // ---------- T-ListMode：三种显示模式（嫁接片段管线） ----------

    @Test
    public void test单行模式_恒单行_完整SQL含结束符() throws Exception {
        LogEntryListCellRenderer r = renderInMode("SINGLE_LINE", entry("SELECT * FROM user WHERE id = 1"));
        assertEquals("单行模式 SQL 行组件恒 1 个", 1, r.getSqlLines().size());
        assertTrue("完整 SQL 保留（含分号结束符）", joinedText(r.getSqlLine()).contains("SELECT * FROM user WHERE id = 1;"));
        LogSqlEntry e2 = entry("SELECT 1");
        renderInMode("SINGLE_LINE", e2);
        assertNull("单行模式不走条目级片段缓存", e2.cachedListLineFragments());
    }

    @Test
    public void test关键字换行模式_多子句分行且缓存生效() throws Exception {
        LogSqlEntry e = entry("SELECT * FROM orders WHERE id = 1");
        LogEntryListCellRenderer r = renderInMode("KEYWORD_WRAP", e);
        assertTrue("关键字换行应多行（实际 " + r.getSqlLines().size() + " 行）", r.getSqlLines().size() >= 3);
        assertTrue("首行 SELECT 开头", joinedText(r.getSqlLines().get(0)).startsWith("SELECT"));
        assertTrue("FROM 独立成行", r.getSqlLines().stream().anyMatch(it -> joinedText(it).startsWith("FROM")));
        assertTrue("WHERE 独立成行", r.getSqlLines().stream().anyMatch(it -> joinedText(it).startsWith("WHERE")));
        assertNotNull("多行模式应写条目级片段缓存", e.cachedListLineFragments());
        assertEquals("缓存键记录当前模式", "KEYWORD_WRAP",
                e.cachedListLineFragmentsMode() != null ? e.cachedListLineFragmentsMode().name() : null);
    }

    @Test
    public void test格式化模式_缩进行保真() throws Exception {
        LogEntryListCellRenderer r = renderInMode("FORMATTED", entry("SELECT * FROM t WHERE a = 1 AND b = 2"));
        assertTrue("格式化应多行", r.getSqlLines().size() >= 3);
        assertTrue(
            "AND 应独立成行且顶层缩进 4 空格（片段文本不折叠空格）",
            r.getSqlLines().stream().anyMatch(it -> joinedText(it).startsWith("    AND")));
    }

    @Test
    public void test模式切换_缓存按模式键自动失效() throws Exception {
        LogSqlEntry e = entry("SELECT * FROM orders WHERE id = 1");
        LogEntryListCellRenderer kw = renderInMode("KEYWORD_WRAP", e);
        int kwLines = kw.getSqlLines().size();
        LogEntryListCellRenderer fm = renderInMode("FORMATTED", e);
        assertEquals("模式键已更新", "FORMATTED",
                e.cachedListLineFragmentsMode() != null ? e.cachedListLineFragmentsMode().name() : null);
        assertTrue(
            "格式化与关键字换行行数不同（缓存重算而非复用）",
            fm.getSqlLines().size() != kwLines);
    }

    @Test
    public void test元信息行不受显示模式影响() throws Exception {
        String sql = "SELECT * FROM user WHERE id = 1";
        for (String mode : List.of("SINGLE_LINE", "KEYWORD_WRAP", "FORMATTED")) {
            renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 2);
            LogEntryListCellRenderer r = renderInMode(mode, entry(sql, null, "src/main/java/A.java", 42));
            String meta = joinedText(r.metaLine);
            assertTrue("[" + mode + "] 元信息行含来源与徽章", meta.contains("[A.java:42]") && meta.contains("⨉2"));
            assertFalse("[" + mode + "] 元信息行不混入 SQL", meta.contains("SELECT"));
        }
    }

    // ---------- T-LogRowNumber：行号片段 ----------

    @Test
    public void test行号片段在元信息行最前() throws Exception {
        LogSqlEntry e = entry("SELECT 1").withSequenceNumber(12L);
        LogEntryListCellRenderer r = render(e);
        String meta = joinedText(r.metaLine);
        assertTrue("元信息行含行号 #12", meta.contains("#12 "));
        assertTrue("行号应在片段序列最前", meta.startsWith("#12 "));
        assertFalse("SQL 不混入元信息行", meta.contains("SELECT"));
    }

    @Test
    public void test无行号条目不渲染行号片段() throws Exception {
        // sequenceNumber=0：历史文件读回 / round-trip 条目
        LogEntryListCellRenderer r = render(entry("SELECT 1"));
        assertFalse("无行号不渲染 # 前缀", joinedText(r.metaLine).contains("#"));
    }

    // ---------- T-CrudColor：CRUD 类型颜色标记 ----------

    /** 在给定标记样式 + 固定四色下执行断言（其余状态原样恢复）。 */
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

    /** 渲染后取色条色（未着色 → null）。 */
    private Color barColorAfterRender(String sql, boolean selected) {
        render(entry(sql), selected);
        return renderer.colorBar.isOpaque() ? renderer.colorBar.getBackground() : null;
    }

    private Color barColorAfterRender(String sql) {
        return barColorAfterRender(sql, false);
    }

    @Test
    public void test色条样式_四类型着色且OTHER不标记() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BAR, () -> {
            assertEquals(new Color(0x4A86E8), barColorAfterRender("SELECT * FROM user"));
            assertEquals(new Color(0x4CAF50), barColorAfterRender("INSERT INTO user VALUES (1)"));
            assertEquals(new Color(0xE67E22), barColorAfterRender("UPDATE user SET a = 1"));
            assertEquals(new Color(0xD32F2F), barColorAfterRender("DELETE FROM user"));
            assertEquals("OTHER 不应着色（避免满屏色条稀释信号）",
                null, barColorAfterRender("MERGE INTO t USING s ON (1=1)"));
        });
    }

    @Test
    public void test色条样式_选中态色条不丢色() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BAR, () -> {
            // 选型核心依据：选中行仍保留类型色（平台填充只覆盖单元格背景，色条是独立子组件）
            assertEquals("选中态色条应仍为类型色", new Color(0x4A86E8), barColorAfterRender("SELECT * FROM user", true));
        });
    }

    @Test
    public void test关闭样式_色条不可见且背景走原逻辑() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_OFF, () -> {
            assertEquals(null, barColorAfterRender("SELECT * FROM user"));
            assertEquals("关闭态应与改造前一致（非选中取列表背景）", list.getBackground(), renderer.getBackground());
            render(entry("SELECT * FROM user"), true);
            assertEquals("关闭态选中仍走平台填充", list.getSelectionBackground(), renderer.getBackground());
        });
    }

    @Test
    public void test背景样式_未选中淡背景混色_选中走平台填充() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND, () -> {
            Color bg = list.getBackground();
            Color update = new Color(0xE67E22);
            render(entry("UPDATE user SET a = 1"));
            assertEquals("未选中应为 14% 混色（类型淡背景标记保留）",
                SqlListTypeColor.blend(bg, update, SqlListTypeColor.MIX_NORMAL), renderer.getBackground());
            render(entry("UPDATE user SET a = 1"), true);
            assertEquals("选中态交给平台选中色（T-UN-09 描边退役）",
                list.getSelectionBackground(), renderer.getBackground());
        });
    }

    @Test
    public void test背景样式_选中态不再安装描边边框() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND, () -> {
            render(entry("UPDATE user SET a = 1"));
            assertSame("未选中应为普通边框", renderer.normalBorder, renderer.getBorder());
            render(entry("UPDATE user SET a = 1"), true);
            assertSame("选中态恢复平台选中绘制，边框与未选中一致（描边已退役）",
                renderer.normalBorder, renderer.getBorder());
        });
    }

    @Test
    public void test背景样式与色条样式_选中态前景均走selectionForeground() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND, () -> {
            render(entry("SELECT 1", 5L, null, 0), true);
            assertEquals("选中态底色已是平台选中填充，前景应转 selectionForeground（T-UN-09）",
                list.getSelectionForeground(), renderer.metaLine.getForeground());
        });
        withCrudColors(SqlListTypeColor.STYLE_BAR, () -> {
            render(entry("SELECT 1", 5L, null, 0), true);
            assertEquals("色条样式选中前景仍为 selectionForeground（现状不变）",
                list.getSelectionForeground(), renderer.metaLine.getForeground());
        });
    }

    // ---------- T-CrudColor：渲染热路径无副作用（性能守卫） ----------

    /**
     * 同状态连续渲染不得产生 Swing 属性实例变更。
     *
     * 依据（JBR 21 实测）：`setBorder(新实例)` 即使同值也 `repaint() +1`、`setBackground(新 Color 同值)`
     * 同样 `+1`；传**同一实例**则零副作用。逐格无脑赋值 = 每格一次重绘调度，
     * 与 T-LogListFreeze 的成因同类，故用**实例同一性**锁定。
     */
    @Test
    public void test色条样式_重复渲染同类型条目不产生属性实例变更() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BAR, () -> {
            render(entry("SELECT 1"));
            Color barBg = renderer.colorBar.getBackground();
            javax.swing.border.Border border = renderer.getBorder();
            Color cellBg = renderer.getBackground();
            render(entry("SELECT 2"));
            assertSame("同色格应复用同一 Color 实例", barBg, renderer.colorBar.getBackground());
            assertSame("同状态应复用同一 Border 实例", border, renderer.getBorder());
            assertSame("同状态单元格背景不应换实例", cellBg, renderer.getBackground());
        });
    }

    @Test
    public void test背景样式_重复渲染不替换背景与边框实例() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND, () -> {
            render(entry("UPDATE t SET a = 1"));
            Color bg = renderer.getBackground();
            javax.swing.border.Border border = renderer.getBorder();
            // 混色每次都会算出「新 Color 实例但同值」——守卫必须拦下它，否则每格一次 repaint
            render(entry("UPDATE t SET a = 1"));
            assertSame("同值混色结果不应重新赋值背景", bg, renderer.getBackground());
            assertSame("同状态不应换边框实例", border, renderer.getBorder());
        });
    }

    @Test
    public void test背景样式_选中与未选中边框恒同一实例() throws Exception {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND, () -> {
            render(entry("UPDATE t SET a = 1"));
            javax.swing.border.Border normal = renderer.getBorder();
            render(entry("UPDATE t SET a = 1"), true);
            assertSame("描边退役后选中/未选中共用同一 Border（不再切换）", normal, renderer.getBorder());
            render(entry("UPDATE t SET a = 1"));
            assertSame("回到未选中仍是同一实例", normal, renderer.getBorder());
        });
    }

    @Test
    public void test行号与后续片段拼接顺序正确() throws Exception {
        // 时间戳开启时：#N 在时间戳之前（行号是最高频引用锚点，视觉最左）
        PluginSettings.State pluginSettings = PluginSettings.getInstance().getState();
        boolean savedTs = pluginSettings.getLogTimestampEnabled();
        try {
            pluginSettings.setLogTimestampEnabled(true);
            LogSqlEntry e = entry("SELECT 1").withSequenceNumber(3L);
            LogEntryListCellRenderer r = render(e);
            String meta = joinedText(r.metaLine);
            int idxRow = meta.indexOf("#3 ");
            int idxTs = meta.indexOf("[");
            assertTrue("应同时含行号与时间戳", idxRow >= 0 && idxTs >= 0);
            assertTrue("行号在时间戳之前", idxRow < idxTs);
        } finally {
            pluginSettings.setLogTimestampEnabled(savedTs);
        }
    }

    // ---------- M56 T-M56-02/05：来源角标（已移除）/ 影响行数 / 报错同列 ----------

    @Test
    public void test增强来源角标已移除_两通道均不显示() throws Exception {
        // 用户要求移除：增强为默认捕获通道，逐行标来源属噪音
        LogSqlEntry agent = agentEntry("SELECT 1", null, null, null);
        assertFalse("AGENT 来源不应再显示增强角标", joinedText(render(agent).metaLine).contains("[⚡增强]"));
        LogSqlEntry bridge = entry("SELECT 1");
        assertFalse("桥来源同样不显示", joinedText(render(bridge).metaLine).contains("[⚡增强]"));
    }

    @Test
    public void test影响行数负值不显示_负行数守卫() throws Exception {
        // JDBC getUpdateCount 在结果为 ResultSet 时返回 -1（无有效计数）→ 不得渲染「(-1 rows)」
        LogSqlEntry neg = agentEntry("SELECT 1", null, -1L, null);
        assertFalse("负值影响行数应不显示", joinedText(render(neg).metaLine).contains("rows)"));
        LogSqlEntry zero = agentEntry("UPDATE t SET a = 1", null, 0L, null);
        assertTrue("0 行是合法计数，应显示", joinedText(render(zero).metaLine).contains("(0 rows)"));
    }

    @Test
    public void test影响行数与报错片段同列() throws Exception {
        LogSqlEntry err = agentEntry("UPDATE t SET a = 1", null, 3L, "SQLException: Data too long for column 'a'");
        String meta = joinedText(render(err).metaLine);
        assertTrue("应含影响行数片段", meta.contains("(3 rows)"));
        assertTrue("应含报错片段（单行截断）", meta.contains("⛔ SQLException: Data too long"));
        // 无报错无行数的普通条目不显示
        String ok = joinedText(render(entry("SELECT 1")).metaLine);
        assertFalse("普通条目不应有行数片段", ok.contains("rows)"));
        assertFalse("普通条目不应有报错片段", ok.contains("⛔"));
    }
}
