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
    public void test两行结构_轨道在左SQL在右() throws Exception {
        // T-LogListRail：原「元信息在上 / SQL 在下」改为「信息轨在左 / SQL 区在右」。
        // 来源类名随耗时进轨道；去重徽章宽度不可控，迁到 SQL 区下方注脚行。
        String sql = "SELECT * FROM repeated WHERE id = 1";
        renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 2);
        LogEntryListCellRenderer r = render(entry(sql, null, "src/main/java/A.java", 42));
        String meta = r.getMetaTextForTest();
        assertTrue("头部行含来源类名 A: " + meta, meta.contains("A"));
        assertTrue("头部行含行号 :42: " + meta, meta.contains(":42"));
        assertTrue("头部行含去重徽章", r.getMetaTextForTest().contains("⨉2"));
        assertFalse("轨道不应混入 SQL", meta.contains("SELECT"));
        assertTrue("SQL 行完整保留语句（含分号结束符）",
                joinedText(r.getSqlLine()).contains("SELECT * FROM repeated WHERE id = 1;"));
    }

    @Test
    public void test窄侧栏按像素裁切SQL而复制源文本不变() throws Exception {
        StringBuilder cols = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            if (i > 1) cols.append(", ");
            cols.append("column_").append(i);
        }
        String original = "SELECT " + cols + " FROM wide_table";
        list.setSize(170, 80);
        try {
            LogSqlEntry e = entry(original);
            String sql = joinedText(render(e).getSqlLine());
            assertTrue("可见 SQL 应有宽度省略号", sql.endsWith("…"));
            assertTrue("省略只发生在展示层", e.restoredSql().endsWith("FROM wide_table"));
        } finally {
            list.setSize(0, 0);
        }
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
        // T-LogListHeader：行数片段并入头部行（metaTextForTest）
        LogSqlEntry neg = agentEntry("SELECT 1", null, -1L, null);
        assertFalse("负值影响行数应不显示", render(neg).getMetaTextForTest().contains("rows)"));
        LogSqlEntry zero = agentEntry("UPDATE t SET a = 1", null, 0L, null);
        assertTrue("0 行是合法计数，应显示", render(zero).getMetaTextForTest().contains("(0 rows)"));
    }

    @Test
    public void test影响行数与报错片段同列() throws Exception {
        // T-LogListRail：报错迁到独立错误行（errorTextForTest），行数并入头部行
        LogSqlEntry err = agentEntry("UPDATE t SET a = 1", null, 3L, "SQLException: Data too long for column 'a'");
        LogEntryListCellRenderer r = render(err);
        assertTrue("头部行应含影响行数片段", r.getMetaTextForTest().contains("(3 rows)"));
        assertTrue("错误行含报错片段（单行截断）", r.getErrorTextForTest().contains("⛔ SQLException: Data too long"));
        // 无报错无行数的普通条目不显示
        LogEntryListCellRenderer ok = render(entry("SELECT 1"));
        assertFalse("普通条目不应有行数片段", ok.getMetaTextForTest().contains("rows)"));
        assertFalse("普通条目不应有报错片段", ok.getErrorTextForTest().contains("⛔"));
    }

    // ---------- O-90：同模板 ×N 徽章 ----------

    @Test
    public void test同模板徽章_达阈值渲染到元信息行() throws Exception {
        String sql = "SELECT * FROM big_orders WHERE uid = 7";
        renderer.templateCounts = Map.of(LogSqlModelsKt.structureFingerprint(sql), 3);
        String d = render(entry(sql)).getMetaTextForTest();
        assertTrue("达阈值应渲染同模板徽章: " + d,
                d.contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("log.panel.templateBadge", 3)));
        // 与去重徽章相邻共存（本条无去重计数，仅模板徽章）
        renderer.templateCounts = Map.of(LogSqlModelsKt.structureFingerprint(sql), 5);
        assertTrue("计数 5 显示 ×5",
                render(entry(sql)).getMetaTextForTest()
                        .contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("log.panel.templateBadge", 5)));
    }

    @Test
    public void test同模板徽章_未达阈值不渲染() throws Exception {
        String sql = "SELECT * FROM small_orders WHERE uid = 7";
        int below = LogSqlModelsKt.TEMPLATE_BADGE_MIN_COUNT - 1;
        renderer.templateCounts = Map.of(LogSqlModelsKt.structureFingerprint(sql), below);
        assertFalse("计数 " + below + " 未达阈值不渲染",
                joinedText(render(entry(sql)).metaLine)
                        .contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("log.panel.templateBadge", below)));
        renderer.templateCounts = Map.of();
        assertFalse("无计数不渲染",
                joinedText(render(entry(sql)).metaLine)
                        .contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("log.panel.templateBadge", 3)));
    }

    @Test
    public void test同模板徽章片段_选中白未选中琥珀() throws Exception {
        String sql = "SELECT * FROM t WHERE id = 1";
        LogSqlEntry e = entry(sql);
        Map<String, Integer> counts = Map.of(LogSqlModelsKt.structureFingerprint(sql), 3);
        assertEquals("未选中琥珀（与耗时偏慢同族）",
                new Color(0xE67E22),
                SqlLogPanelKt.formatTemplateCountFragment(e, counts, false).getSecond().getFgColor());
        assertEquals("选中白（不抢 SQL 高亮）",
                new Color(0xFFFFFF),
                SqlLogPanelKt.formatTemplateCountFragment(e, counts, true).getSecond().getFgColor());
        assertNull("未达阈值返回 null",
                SqlLogPanelKt.formatTemplateCountFragment(e, Map.of(LogSqlModelsKt.structureFingerprint(sql), 2), false));
        assertNull("无计数返回 null", SqlLogPanelKt.formatTemplateCountFragment(e, Map.of(), false));
    }

    @Test
    public void test格式化模式不再只格式化首行摘要() throws Exception {
        StringBuilder cols = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            if (i > 1) cols.append(", ");
            cols.append("c").append(i);
        }
        String full = "SELECT " + cols + " FROM record WHERE id = 42";
        LogEntryListCellRenderer r = renderInMode("FORMATTED", entry(full));
        StringBuilder visible = new StringBuilder();
        for (SimpleColoredComponent line : r.getSqlLines()) {
            if (visible.length() > 0) visible.append('\n');
            visible.append(joinedText(line));
        }
        assertTrue("全文最后的 WHERE 子句应在格式化模式可见", visible.toString().contains("WHERE id = 42"));
    }

    // ---------- R60（T-LaneB D96）：日志列表链脱敏（判别断言两态，走真实渲染器链路） ----------

    @Test
    public void test列表脱敏开_渲染文本打码() throws Exception {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean saved = state.getDisplayMaskEnabled();
        try {
            state.setDisplayMaskEnabled(true);
            String sqlText = joinedText(render(entry("SELECT * FROM t WHERE phone = '13812345678'")).getSqlLine());
            assertTrue("脱敏开应打码: " + sqlText, sqlText.contains("***"));
            assertFalse("原文手机号不得出现: " + sqlText, sqlText.contains("13812345678"));
        } finally {
            state.setDisplayMaskEnabled(saved);
        }
    }

    @Test
    public void test列表脱敏关_原文渲染() throws Exception {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean saved = state.getDisplayMaskEnabled();
        try {
            state.setDisplayMaskEnabled(false);
            String sqlText = joinedText(render(entry("SELECT * FROM t WHERE phone = '13812345678'")).getSqlLine());
            assertTrue("脱敏关原文渲染: " + sqlText, sqlText.contains("13812345678"));
        } finally {
            state.setDisplayMaskEnabled(saved);
        }
    }

    @Test
    public void test摘要截断在敏感字面量中间_日志展示仍须脱敏() throws Exception {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean saved = state.getDisplayMaskEnabled();
        try {
            state.setDisplayMaskEnabled(true);
            String secret = "PRIVATE_MARKER_" + "x".repeat(200);
            LogSqlEntry e = entry("SELECT * FROM account WHERE password = '" + secret + "'");
            assertTrue("夹具须在闭合引号前截断", e.summary().contains("PRIVATE_MARKER_"));
            assertFalse(e.summary().contains("'" + secret + "'"));
            String displayed = joinedText(render(e).getSqlLine());
            assertTrue("密码应按完整字面量打码：" + displayed, displayed.contains("password = '***'"));
            assertFalse("截断的敏感片段不得泄漏：" + displayed, displayed.contains("PRIVATE_MARKER_"));
        } finally {
            state.setDisplayMaskEnabled(saved);
        }
    }

    // ---------- T-DataSourceBadgeMode：数据源角标默认仅显示数据库类型（显示侧门控） ----------

    /** 构造带目标库的 agent 条目（badgeName 全量形态：方言 · 连接事实）。 */
    private static LogSqlEntry dbEntry(String db) {
        return agentEntry("SELECT 1", db, null, null);
    }

    /** 在给定开关值下执行断言（收尾原样恢复）。 */
    private void withBadgeDetail(boolean enabled, Runnable block) {
        PluginSettings.State s = PluginSettings.getInstance().getState();
        boolean saved = s.getDataSourceBadgeDetail();
        try {
            s.setDataSourceBadgeDetail(enabled);
            block.run();
        } finally {
            s.setDataSourceBadgeDetail(saved);
        }
    }

    @Test
    public void test数据源角标_默认关仅渲染方言段() throws Exception {
        // OFF（默认）：badgeName 全量串「MySQL · jpa_demo@localhost:3306」只显方言段（D135 显示侧门控）
        withBadgeDetail(false, () -> {
            String full = "MySQL · jpa_demo@localhost:3306";
            assertEquals("OFF 态片段应切出纯方言段", "[MySQL] ",
                    SqlLogPanelKt.formatDatabaseFragment(dbEntry(full), false).getFirst());
            String d = render(dbEntry(full)).getMetaTextForTest();
            assertTrue("注脚行应含方言角标: " + d, d.contains("[MySQL]"));
            assertFalse("注脚行不应含连接事实: " + d, d.contains("jpa_demo"));
        });
    }

    @Test
    public void test数据源角标_开启后渲染全量上下文() throws Exception {
        withBadgeDetail(true, () -> {
            String full = "MySQL · jpa_demo@localhost:3306";
            assertEquals("ON 态片段应原样全量", "[" + full + "] ",
                    SqlLogPanelKt.formatDatabaseFragment(dbEntry(full), false).getFirst());
            String d = render(dbEntry(full)).getMetaTextForTest();
            assertTrue("注脚行应含完整上下文: " + d, d.contains("[" + full + "]"));
        });
    }

    @Test
    public void test数据源角标_无分隔符串关闭态原样保留() throws Exception {
        // 已知边界：方言解析失败仅存连接事实（无 · 分隔符）→ OFF 态原样保留最后上下文
        withBadgeDetail(false, () -> assertEquals(
                "无 · 串 OFF 态应原样保留",
                "[localhost:5236] ",
                SqlLogPanelKt.formatDatabaseFragment(dbEntry("localhost:5236"), false).getFirst()));
    }

    @Test
    public void test数据源角标_未知库名恒不显示() throws Exception {
        // null databaseName 恒 null 不显示（既有语义不变；渲染器级 null 用例见 test目标库角标_未知库名不显示）
        withBadgeDetail(false, () -> assertNull(SqlLogPanelKt.formatDatabaseFragment(dbEntry(null), false)));
        withBadgeDetail(true, () -> assertNull("ON 态同样恒 null",
                SqlLogPanelKt.formatDatabaseFragment(dbEntry(null), true)));
    }

    @Test
    public void test数据源角标_历史读回条目随开关切换() throws Exception {
        // P4 口径：历史读回条目的 databaseName 为自由文本全量串，显示门控对它同样生效
        LogSqlEntry readBack = dbEntry("DM（达梦） · localhost:5236");
        withBadgeDetail(false, () -> assertEquals("[DM（达梦）] ",
                SqlLogPanelKt.formatDatabaseFragment(readBack, false).getFirst()));
        withBadgeDetail(true, () -> assertEquals("[DM（达梦） · localhost:5236] ",
                SqlLogPanelKt.formatDatabaseFragment(readBack, false).getFirst()));
    }

    // ---------- T-DdlTypeColor：DDL 虚线色条 ----------

    /**
     * DDL 条目走虚线色条、DML 走实心。
     *
     * 这是**形态断言**（不是像素比对）：用户明确要「虚线区分 DDL」，若谁把 DDL 也画成实心，
     * 视觉区分就静默消失了——不报错、不崩溃，只是回退到「看不出类型」。
     */
    @Test
    public void testDdl条目色条为虚线_dml为实心() throws Exception {
        assertTrue("CREATE 应触发虚线色条", render(entry("CREATE TABLE t (id INT)")).colorBar.isDashed());
        assertTrue("DROP 应触发虚线色条", render(entry("DROP TABLE t")).colorBar.isDashed());
        assertTrue("TRUNCATE 应触发虚线色条", render(entry("TRUNCATE TABLE t")).colorBar.isDashed());
        for (String sql : List.of("SELECT 1", "INSERT INTO t VALUES (1)", "UPDATE t SET a = 1", "DELETE FROM t")) {
            assertFalse(sql + " 不应走虚线", render(entry(sql)).colorBar.isDashed());
        }
    }

    /** DDL 条目与同色 DML 条目都着色（不再是无色条的 OTHER）。 */
    @Test
    public void testDdl条目有着色条() throws Exception {
        for (String sql : List.of("CREATE TABLE t (id INT)", "ALTER TABLE t ADD c INT", "DROP TABLE t", "TRUNCATE TABLE t")) {
            assertTrue(sql + " 应有色条（不再是无色条的 OTHER）", render(entry(sql)).colorBar.isOpaque());
        }
    }

    /** 虚线段长常量（形态契约：实/空各 3px，周期 6px）。 */
    @Test
    public void test虚线段长常量() throws Exception {
        TypeColorBar bar = new TypeColorBar();
        kotlin.Pair<Integer, Integer> pattern = bar.dashPatternForTest();
        assertEquals("实心段 3px", 3, pattern.getFirst().intValue());
        assertEquals("空白段 3px", 3, pattern.getSecond().intValue());
        assertEquals("周期 6px", 6, TypeColorBar.DASH_PERIOD);
    }

    /** 同形态重复设置不重复重绘（渲染热路径守卫）。 */
    @Test
    public void test色条形态_同值不重复设置() throws Exception {
        TypeColorBar bar = new TypeColorBar();
        bar.setDashed(true);
        bar.setDashed(true);
        bar.setDashed(true);
        assertEquals("同值重复设置不得触发重绘", 1, bar.getRepaintRequests());
        bar.setDashed(false);
        assertEquals("切换形态应再触发一次", 2, bar.getRepaintRequests());
    }

    /**
     * 虚线色条必须**真的画成虚线段**（像素级验证）。
     *
     * 为什么必须像素级：早期实现「先填 background 再按段填 background」，前景与底色同色 →
     * 整条全实心，形态断言（{@code dashed == true}）照样通过、肉眼看上去却没区别。
     * 这条用渲染后的像素序列判定，抓的就是这种「设了标志但画不出来」的假绿。
     */
    @Test
    public void test虚线色条_像素序列为实空交替() throws Exception {
        TypeColorBar bar = new TypeColorBar();
        bar.setBackground(new Color(0x4CAF50));
        bar.setDashed(true);
        bar.setSize(3, 40);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(3, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics g = img.createGraphics();
        bar.paint(g);
        g.dispose();
        StringBuilder seq = new StringBuilder();
        for (int y = 0; y < 40; y++) {
            int rgb = img.getRGB(1, y) & 0xFFFFFF;
            seq.append(rgb == 0x4CAF50 ? '#' : '.');
        }
        assertTrue("应存在实心段: " + seq, seq.toString().contains("#"));
        assertTrue("应存在空白段（否则等于实心）: " + seq, seq.toString().contains("."));
        // 周期契约：每 6px 一个循环
        StringBuilder expectHead = new StringBuilder();
        expectHead.append("#".repeat(TypeColorBar.DASH_ON)).append(".".repeat(TypeColorBar.DASH_OFF));
        assertTrue("实空交替周期应为 " + TypeColorBar.DASH_PERIOD + "px: " + seq,
                seq.toString().startsWith(expectHead.toString()));
    }

    /** 实心形态下整条填满（不出现空白段）。 */
    @Test
    public void test实心色条_像素全填满() throws Exception {
        TypeColorBar bar = new TypeColorBar();
        bar.setBackground(new Color(0x4CAF50));
        bar.setDashed(false);
        bar.setSize(3, 40);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(3, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics g = img.createGraphics();
        bar.paint(g);
        g.dispose();
        for (int y = 0; y < 40; y++) {
            assertTrue("实心形态应整条填满", (img.getRGB(1, y) & 0xFFFFFF) == 0x4CAF50);
        }
    }
}
