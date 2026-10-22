package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 自动保存 → 历史读取 回环测试（F-44-2/F-44-3 真实场景）：
 *
 * 模拟真实链路——日志面板捕获 SQL（带源码位置与耗时）→ buffer 淘汰触发 [SqlLogAutoSaver.write]
 * 写入 `.idea/sql-visualizer/sql-history/` → 用户打开历史面板经 [SqlLogFileReader] 读回。
 * 断言写读两端字段完全一致（SQL 文本、源码位置、耗时、时间戳、条数），防止格式回归。
 */
public class SqlLogHistoryRoundTripTest {

    /** 构造带完整元数据的真实条目（对齐 FilterProxyHandler 组装的 entry）。 */
    private static LogSqlEntry capturedEntry() {
        return capturedEntry("SELECT * FROM user WHERE id = 1",
                "src/main/java/com/example/mapper/UserMapper.java", 42, 5L, 1724567890000L);
    }

    private static LogSqlEntry capturedEntry(String sql, String sourceFile, int sourceLine,
                                             Long executionTimeMs, long timestamp) {
        return new LogSqlEntry(sql, sql, List.of(), timestamp, sourceFile, sourceLine, executionTimeMs);
    }

    private static File tempDir() throws Exception {
        File dir = Files.createTempDirectory("roundtrip-").toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static String timestampStr(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).format(new Date(ms));
    }

    @Test
    public void 真实链路_捕获条目自动保存后读取回环_字段全一致() throws Exception {
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        // 真实捕获条目：带源码位置 + 5ms 耗时。
        // T-SqlDuration（master）：读回端 removeSuffix(";") 剥除 SQL 结束符——写入侧带分号、
        // 读回侧统一无分号，故本用例输入不带分号；带分号写入的剥除行为由下方独立断言覆盖。
        LogSqlEntry captured = capturedEntry(
                "SELECT id, username, password FROM user WHERE id = 1",
                "src/main/java/com/example/mapper/UserMapper.java",
                42,
                5L,
                1724567890000L);
        saver.write(captured);
        saver.close();

        File[] files = Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql")));
        assertEquals("应生成 1 个文件", 1, files.length);
        // 文件头部应为标准 SQL 注释 `--`（F-44-2 双横线，非 `---`）
        List<String> lines = Files.readAllLines(files[0].toPath());
        String firstLine = lines.get(0);
        assertEquals("-- source: src/main/java/com/example/mapper/UserMapper.java:42 | time: 5ms | "
                + timestampStr(captured.timestamp()) + " --", firstLine);

        // 用户打开历史面板读取
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(files[0]);
        assertEquals("条数一致", 1, entries.size());
        LogSqlEntry read = entries.get(0);
        assertEquals("SQL 文本一致", captured.restoredSql(), read.restoredSql());
        assertEquals("源码文件一致", captured.sourceFile(), read.sourceFile());
        assertEquals("源码行号一致", captured.sourceLine(), read.sourceLine());
        assertEquals("耗时一致", captured.executionTimeMs(), read.executionTimeMs());
        assertEquals("时间戳一致", captured.timestamp(), read.timestamp());
    }

    @Test
    public void 真实链路_多文件多条目_文件条数统计与读取一致() throws Exception {
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        // 模拟两天的历史：今天 3 条、昨天 2 条
        saver.write(capturedEntry("SELECT 1", "A.java", 1, 1L, 1724567890000L));
        saver.write(capturedEntry("SELECT 2", "A.java", 2, 2L, 1724567890001L));
        saver.write(capturedEntry("SELECT 3", "A.java", 3, 3L, 1724567890002L));
        saver.close();

        File[] files = Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql")));
        assertEquals("同一天应写入 1 个文件", 1, files.length);
        // 一级文件列表显示的条数统计（轻量 countInFile）
        assertEquals("countInFile 条数 = 3", 3, SqlLogFileReader.INSTANCE.countInFile(files[0]));
        // 二级读取的完整解析
        assertEquals("readFromFile 条数 = 3", 3, SqlLogFileReader.INSTANCE.readFromFile(files[0]).size());

        // 第二个文件（模拟另一天：固定文件名，避免与当天文件同名被覆盖——newBufferedWriter 默认 TRUNCATE）
        SqlLogAutoSaver saver2 = new SqlLogAutoSaver();
        saver2.configure(dir.getAbsolutePath(), "sql-history-2026-08-30.sql", null);
        saver2.write(capturedEntry("SELECT A", "B.java", 10, null, 1724567000000L));
        saver2.write(capturedEntry("SELECT B", "B.java", 11, null, 1724567000001L));
        saver2.close();
        assertEquals("两天共 2 个文件",
                2, Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql"))).length);

        // 无耗时条目：文件写 `—`，读回 null
        List<LogSqlEntry> all = SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath());
        assertEquals("两文件共 5 条", 5, all.size());
        List<LogSqlEntry> noDuration = all.stream()
                .filter(e -> e.restoredSql().startsWith("SELECT A") || e.restoredSql().startsWith("SELECT B"))
                .collect(Collectors.toList());
        assertEquals(2, noDuration.size());
        noDuration.forEach(e -> assertNull("无耗时条目读回 null", e.executionTimeMs()));

        // T-LogHistoryRowNo：round-trip 读回条目按文件内顺序带序号（写端不持久化，读端派生）
        List<LogSqlEntry> today = SqlLogFileReader.INSTANCE.readFromFile(files[0]);
        assertEquals("今天文件 3 条按顺序号 1..3", List.of(1L, 2L, 3L),
                today.stream().map(LogSqlEntry::sequenceNumber).toList());
    }

    @Test
    public void 真实链路_带分号写入读回剥除结束符() throws Exception {
        // T-SqlDuration（master 5fbb3c7）：历史读回统一剥除 SQL 结束符（；耗时展示不受分号干扰）。
        // M38 合并期 feature 侧曾按旧读回器断言「保留分号」，合并后产品行为以剥除为准——
        // 本用例显式固化：带分号写入 → 读回无分号。
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(capturedEntry("SELECT * FROM user WHERE id = 1;",
                "src/main/java/com/example/mapper/UserMapper.java", 42, 5L, 1724567890000L));
        saver.close();

        File[] files = Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql")));
        List<LogSqlEntry> read = SqlLogFileReader.INSTANCE.readFromFile(files[0]);
        assertEquals(1, read.size());
        assertEquals("读回剥除结束符", "SELECT * FROM user WHERE id = 1", read.get(0).restoredSql());
    }

    @Test
    public void 真实链路_旧F43格式文件仍可读取() throws Exception {
        // 升级前遗留的 F-43 文件（无源码位置、无耗时），历史面板应能展示
        File dir = tempDir();
        File legacy = new File(dir, "sql-history-2026-08-20.sql");
        Files.writeString(legacy.toPath(),
                "-- 2026-08-20 10:00:00\nSELECT * FROM user WHERE id = 1\n\n"
                        + "-- 2026-08-20 10:00:01\nUPDATE user SET name = 'x' WHERE id = 2\n\n");
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath());
        assertEquals(2, entries.size());
        assertEquals("SELECT * FROM user WHERE id = 1", entries.get(0).restoredSql());
        assertEquals("UPDATE user SET name = 'x' WHERE id = 2", entries.get(1).restoredSql());
        assertNull("旧格式无源码位置", entries.get(0).sourceFile());
        assertNull("旧格式无耗时", entries.get(0).executionTimeMs());
        assertEquals("旧格式条数统计一致", 2, SqlLogFileReader.INSTANCE.countInFile(legacy));
    }

    @Test
    public void 真实链路_混合格式单文件_新老格式条目共存() throws Exception {
        // 升级过渡期：同文件内既有 F-44-2 双横线、又有历史 F-43 时间戳头
        File dir = tempDir();
        File file = new File(dir, "sql-history-2026-08-31.sql");
        Files.writeString(file.toPath(),
                "-- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --\n"
                        + "SELECT 1\n\n"
                        + "-- 2026-08-31 09:00:00\n"
                        + "SELECT 2\n\n"
                        + "-- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:31:00.000 --\n"
                        + "SELECT 3\n\n");
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(file); // 保持文件内顺序（readAll 会按 timestamp 重排）
        assertEquals(3, entries.size());
        // 新格式：源码 + 耗时
        assertEquals("UserMapper.java", entries.get(0).sourceFile());
        assertEquals(Long.valueOf(3L), entries.get(0).executionTimeMs());
        // 旧格式：无源码无耗时
        assertNull(entries.get(1).sourceFile());
        // 新格式
        assertEquals("OrderMapper.java", entries.get(2).sourceFile());
        assertEquals(Long.valueOf(10L), entries.get(2).executionTimeMs());
        assertEquals("轻量计数与完整解析一致", 3, SqlLogFileReader.INSTANCE.countInFile(file));
    }

    @Test
    public void 真实链路_空历史目录_返回空列表() throws Exception {
        File dir = tempDir();
        assertEquals(0, SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath()).size());
        assertEquals(0, SqlLogFileReader.INSTANCE.countInFile(new File(dir, "not-exists.sql")));
    }

    @Test
    public void 真实链路_带参数条目写读回环_历史页参数识别与日志页一致() throws Exception {
        // T-HistParam：捕获条目（rawSql + params）→ 自动落盘 → 历史面板读回 →
        // 统一识别通道（toPreviewResult）产出与日志页相同的参数形态
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(new LogSqlEntry(
                "SELECT * FROM user WHERE id = ? AND name = ?",
                "SELECT * FROM user WHERE id = 42 AND name = 'neo'",
                List.of(new LogSqlParam("42", "Long"), new LogSqlParam("neo", "String")),
                1724567890000L,
                "src/main/java/com/example/mapper/UserMapper.java",
                42,
                5L));
        saver.close();

        File[] files = dir.listFiles(f -> f.getName().endsWith("sql"));
        assertEquals(1, files.length);
        LogSqlEntry read = SqlLogFileReader.INSTANCE.readFromFile(files[0]).get(0);
        assertEquals("rawSql 回环", "SELECT * FROM user WHERE id = ? AND name = ?", read.rawSql());
        assertEquals("restoredSql 回环", "SELECT * FROM user WHERE id = 42 AND name = 'neo'", read.restoredSql());
        assertEquals(2, read.params().size());

        // 历史页预览构建（formatSql=false 现状）与日志页识别通道同源
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                LogEntryPreviewRecognizerKt.toPreviewResult(read, false, true);
        assertEquals(List.of("param1", "param2"), result.getUnresolvedParams());
        assertEquals(java.util.Map.of("param1", "42", "param2", "neo"), result.getInitialParamValues());
        assertEquals(
                List.of(Boolean.FALSE, Boolean.TRUE),
                result.getParamHints().stream().map(h -> h.getQuote()).collect(Collectors.toList()));
        assertNull(result.getWarning());
    }

    // ---------- M56 T-M56-00：扩展字段（mapperStatement/errorText/requestTag/updateCount）回环 ----------

    @Test
    public void 真实链路_扩展字段条目写读回环_四字段不丢() throws Exception {
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        // agent 通道形态：无参数、无占位符，但扩展字段齐备——pd 行须因扩展字段在场而写入
        LogSqlEntry captured = new LogSqlEntry(
                "UPDATE user SET name = 'x' WHERE id = 7",
                "UPDATE user SET name = 'x' WHERE id = 7",
                List.of(),
                1724567890000L,
                null,
                LogSqlEntry.summarizeSql("UPDATE user SET name = 'x' WHERE id = 7"),
                SqlSource.UNKNOWN,
                2L,
                null,
                0,
                List.of(),
                List.of(),
                null,
                0L,
                "com.example.mapper.UserMapper.updateName",
                null,
                "req-88",
                1L,
                null);
        saver.write(captured);
        saver.close();

        File[] files = Objects.requireNonNull(dir.listFiles(f -> f.getName().endsWith(".sql")));
        assertEquals(1, files.length);
        LogSqlEntry read = SqlLogFileReader.INSTANCE.readFromFile(files[0]).get(0);
        assertEquals("mapperStatement 回环", captured.mapperStatement(), read.mapperStatement());
        assertEquals("requestTag 回环", captured.requestTag(), read.requestTag());
        assertEquals("updateCount 回环", captured.updateCount(), read.updateCount());
        assertNull("无报错条目回环 null", read.errorText());
    }

    @Test
    public void 真实链路_报错条目写读回环_errorText不丢() throws Exception {
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(new LogSqlEntry(
                "SELECT * FROM no_such_table",
                "SELECT * FROM no_such_table",
                List.of(),
                1724567890001L,
                null,
                LogSqlEntry.summarizeSql("SELECT * FROM no_such_table"),
                SqlSource.UNKNOWN,
                null,
                null,
                0,
                List.of(),
                List.of(),
                null,
                0L,
                null,
                "BadSqlGrammarException: Table 'no_such_table' doesn't exist",
                null,
                null,
                null));
        saver.close();
        LogSqlEntry read = SqlLogFileReader.INSTANCE
                .readFromFile(Objects.requireNonNull(dir.listFiles(f -> f.getName().endsWith(".sql")))[0]).get(0);
        assertEquals(
                "errorText 回环",
                "BadSqlGrammarException: Table 'no_such_table' doesn't exist",
                read.errorText());
        assertNull(read.mapperStatement());
    }

    // ---------- T-McpAuditTrail T-MAT-03 读端回填：来源标记 round-trip ----------
    // 立项背景：写入侧（pd 载荷落 `s` 键）与 pd 载荷层 round-trip 已由 T-McpAuditTrail 交付，
    // 但读端 SqlLogFileReader.parseBlock 当时被并行任务占用未同步改动 → 重启后 MCP 条目的
    // 来源退化为 SqlSource.UNKNOWN（历史页 MCP 徽标消失）。本组用例钉住读端回填。

    /** MCP 条目写入侧的常态形态：无参数、无占位符、无 mapperStatement——pd 行完全靠 source==MCP 触发。 */
    private static LogSqlEntry mcpEntry(String sql, long timestamp) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), timestamp, null,
                LogSqlEntry.summarizeSql(sql), SqlSource.MCP, 7L, null, 0, List.of(), List.of(), null, 0L,
                null, null, null, null, "demo-mysql");
    }

    private static LogSqlEntry mcpEntry(String sql) {
        return mcpEntry(sql, 1724567890000L);
    }

    @Test
    public void 真实链路_MCP条目写读回环_来源标记不退化() throws Exception {
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(mcpEntry("SELECT id FROM user WHERE id = 1"));
        saver.close();

        File[] sqlFiles = Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql")));
        assertEquals(1, sqlFiles.length);
        File file = sqlFiles[0];
        // 写入侧前提（T-MAT-03）：无参数无占位符的 MCP SELECT 也必须落 pd 行，否则来源无处可存
        assertTrue("MCP 条目必须写出 pd 载荷行", Files.readString(file.toPath()).contains("-- pd: "));

        // 读端（重启后历史面板读文件）：来源必须仍是 MCP
        LogSqlEntry read = SqlLogFileReader.INSTANCE.readFromFile(file).get(0);
        assertEquals("读端回填来源标记（改前退化为 UNKNOWN → 徽标消失）", SqlSource.MCP, read.source());
        assertEquals("SQL 文本不受来源键影响", "SELECT id FROM user WHERE id = 1", read.restoredSql());
        assertEquals("目标库角标随载荷回环", "demo-mysql", read.databaseName());
        assertNull("pd 载荷不侵占头部 source: 段语义（仍为源码位置）", read.sourceFile());
    }

    @Test
    public void 真实链路_非MCP条目写读回环_来源保持UNKNOWN不扩张() throws Exception {
        // T-MAT-03 的读端口径：只有 MCP 落 `s` 键，其余来源不因本次改动改变读回结果
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(new LogSqlEntry(
                "SELECT * FROM user WHERE id = ?",
                "SELECT * FROM user WHERE id = 1",
                List.of(new LogSqlParam("1", "Long")),
                1724567890000L,
                "UserMapper.java",
                7,
                null));
        saver.close();

        File[] sqlFiles = Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql")));
        LogSqlEntry read = SqlLogFileReader.INSTANCE.readFromFile(sqlFiles[0]).get(0);
        assertEquals("非 MCP 条目读回来源仍为 UNKNOWN", SqlSource.UNKNOWN, read.source());
        assertEquals("其余字段不受影响", "UserMapper.java", read.sourceFile());
    }

    @Test
    public void 真实链路_旧版F43头部下的pd载荷_来源回填MCP() throws Exception {
        // 旧版头部分支（-- yyyy-MM-dd HH:mm:ss）同样要回填——升级过渡期的混合文件里，
        // 头部形态与 pd 行是否在场互相独立，两条分支漏一条即出现「同一文件内徽标时有时无」
        File dir = tempDir();
        String pd = SqlLogParamCodec.encodeEntry(mcpEntry("SELECT 2"));
        Files.writeString(new File(dir, "sql-history-2026-08-20.sql").toPath(),
                "-- 2026-08-20 10:00:00\n-- pd: " + pd + " --\nSELECT 2;\n\n");

        LogSqlEntry read = SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath()).get(0);
        assertEquals("旧版头部分支读端回填来源标记", SqlSource.MCP, read.source());
        assertEquals("SELECT 2", read.restoredSql());
        assertNull("旧版头部无源码位置", read.sourceFile());
    }
}
