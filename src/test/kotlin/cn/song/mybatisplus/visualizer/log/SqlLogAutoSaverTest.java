package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 自动保存历史 SQL 写入器单元测试（F-43，纯 JUnit，无平台依赖）。
 */
public class SqlLogAutoSaverTest {

    private static LogSqlEntry entry(String sql) {
        return new LogSqlEntry(
            sql,
            sql,
            List.<LogSqlParam>of(),
            1724567890000L // 固定时间戳方便断言
        );
    }

    private static String tempDir() throws Exception {
        File dir = Files.createTempDirectory("f43-test-").toFile();
        dir.deleteOnExit();
        return dir.getAbsolutePath();
    }

    @Test
    public void 写入单条生成正确格式() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test-$yyyy-MM-dd$.sql", dir);
        saver.write(entry("SELECT 1"));
        File file = saver.currentFile();
        assertNotNull("File should be created", file);
        // 读取文件内容前不 close（close 会置 null）
        String content = Files.readString(file.toPath());
        // F-44-2：新格式 -- source: xxx | time: xxx | timestamp --（标准 SQL 单行注释）
        assertTrue("Should contain source header: " + content, content.contains("source: unknown"));
        assertTrue("Should contain duration: " + content, content.contains("time: —"));
        assertTrue("Should contain timestamp: " + content,
            content.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"));
        assertTrue("Should contain SQL: " + content, content.contains("SELECT 1"));
        saver.close();
    }

    @Test
    public void 写入头使用双横线注释() throws Exception {
        // F-44-2：注释头部必须为 `--` 起结（标准 SQL 单行注释），不再用 `---`
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test.sql", dir);
        saver.write(entry("SELECT 1"));
        File file = saver.currentFile();
        String content = Files.readString(file.toPath());
        // 起结行 -- source: ... -- （中间可能有内容，end 必须是 ` --`，不能再用 ` ---`）
        String firstLine = content.contains("\n") ? content.substring(0, content.indexOf('\n')) : content;
        assertTrue("首行应以 `--` 起始: " + firstLine, firstLine.startsWith("-- source:"));
        assertTrue("首行应以 ` --` 结尾: " + firstLine, firstLine.endsWith(" --"));
        assertFalse("首行不应再有 `---`: " + firstLine, firstLine.startsWith("---"));
        assertFalse("首行不应再以 `---` 结尾: " + firstLine, firstLine.endsWith("---"));
        saver.close();
    }

    @Test
    public void 多条追加不覆盖() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test.sql", dir);
        saver.write(entry("SELECT 1"));
        saver.write(entry("SELECT 2"));
        saver.write(entry("SELECT 3"));
        File file = saver.currentFile();
        assertNotNull(file);
        String content = Files.readString(file.toPath());
        // F-44-2：新格式以 "-- source:" 开头（标准 SQL 单行注释，两个 `-`）
        int count = content.split("-- source:", -1).length - 1;
        assertEquals(3, count);
        saver.close();
    }

    @Test
    public void 文件名模板解析() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "sql-history-$yyyy-MM-dd$.sql", "/tmp");
        String resolved = saver.resolveFileName();
        // resolveFileName 使用当前时间，验证格式匹配即可
        assertTrue("Expected pattern like sql-history-YYYY-MM-DD.sql, got: " + resolved,
            resolved.matches("sql-history-\\d{4}-\\d{2}-\\d{2}\\.sql"));
        // 验证各占位符都被替换（不应再包含 $）
        assertFalse("All placeholders should be replaced: " + resolved, resolved.contains("$"));
    }

    @Test
    public void 文件名模板解析_冒号替换为横线() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "log-$HH:mm:ss$.sql", "/tmp");
        String resolved = saver.resolveFileName();
        assertFalse("':' should be replaced with '-': " + resolved, resolved.contains(":"));
        assertTrue("Expected pattern like log-HH-mm-ss.sql, got: " + resolved,
            resolved.matches("log-\\d{2}-\\d{2}-\\d{2}\\.sql"));
    }

    @Test
    public void 文件名模板解析_未知占位符原样保留() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "log-$unknown$-$yyyy$.sql", "/tmp");
        String resolved = saver.resolveFileName();
        assertTrue("Unknown placeholder should be preserved: " + resolved,
            resolved.startsWith("log-$unknown$-"));
    }

    @Test
    public void 默认目录解析() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "test.sql", "/home/user/myproject");
        String resolved = saver.resolveFileName();
        assertEquals("test.sql", resolved);
    }

    @Test
    public void 自定义路径() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String customDir = tempDir();
        saver.configure(customDir, "custom.sql", "/other/path");
        saver.write(entry("SELECT 1"));
        File file = saver.currentFile();
        assertNotNull(file);
        assertEquals(customDir, file.getParentFile().getAbsolutePath());
        assertEquals("custom.sql", file.getName());
        saver.close();
    }

    @Test
    public void 无效路径不抛异常() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        // 使用包含空字符的路径（Windows/macOS 均不允许路径含 \0）
        saver.configure("invalid\u0000path", "test.sql", null);
        // write 不应抛出异常
        saver.write(entry("SELECT 1"));
        // currentFile 应该为 null（因为文件无法创建）
        assertNull(saver.currentFile());
        saver.close();
    }

    @Test
    public void close后写入安全() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test.sql", dir);
        saver.write(entry("SELECT 1"));
        saver.close();
        // close 后再 write，不应抛异常（懒打开新文件）
        saver.write(entry("SELECT 2"));
        saver.close();
    }

    @Test
    public void configure关闭旧文件重置状态() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir1 = tempDir();
        String dir2 = tempDir();
        saver.configure(dir1, "file1.sql", null);
        saver.write(entry("SELECT 1"));
        assertNotNull(saver.currentFile());
        // 切换目录
        saver.configure(dir2, "file2.sql", null);
        // 写入后应在 dir2 创建文件
        saver.write(entry("SELECT 2"));
        File file = saver.currentFile();
        assertNotNull(file);
        assertEquals(dir2, file.getParentFile().getAbsolutePath());
        assertEquals("file2.sql", file.getName());
        saver.close();
    }

    // ---------- T-AutoSaverSOE：无限递归回归（真机 2026-09-04 00:09 StackOverflowError） ----------

    /** 解析本地时间字符串为 epoch 毫秒（时钟注入用）。 */
    private static long timeOf(String dateTime) throws Exception {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateTime).getTime();
    }

    @Test
    public void 跨天切换文件不再无限递归() throws Exception {
        // 回归：旧实现在文件名变化分支递归重写，但 currentResolvedName 永不更新，
        // 跨天后首次写入即 StackOverflowError
        final long[] now = {timeOf("2026-09-04 23:59:50")};
        SqlLogAutoSaver saver = new SqlLogAutoSaver(() -> now[0], 100L * 1024 * 1024);
        String dir = tempDir();
        saver.configure(dir, "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(entry("SELECT 1"));
        File day1 = saver.currentFile();
        now[0] = timeOf("2026-09-05 00:00:10");
        saver.write(entry("SELECT 2"));
        File day2 = saver.currentFile();
        assertNotNull(day1);
        assertNotNull(day2);
        org.junit.Assert.assertNotEquals("跨天后应切换文件: " + day1 + " -> " + day2,
                day1.getName(), day2.getName());
        assertEquals("sql-history-2026-09-05.sql", day2.getName());
        assertEquals(1, Files.readString(day1.toPath()).split("-- source:", -1).length - 1);
        assertEquals(1, Files.readString(day2.toPath()).split("-- source:", -1).length - 1);
        saver.close();
    }

    @Test
    public void 秒级模板连续写入不再无限递归() throws Exception {
        // 回归：模板含 $ss$ 等细粒度占位符时，旧实现第二次写入即 StackOverflowError
        final long[] now = {timeOf("2026-09-04 10:00:00")};
        SqlLogAutoSaver saver = new SqlLogAutoSaver(() -> now[0], 100L * 1024 * 1024);
        String dir = tempDir();
        saver.configure(dir, "log-$HH-mm-ss$.sql", null);
        for (int i = 0; i < 3; i++) {
            saver.write(entry("SELECT " + i));
            now[0] += 2000;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (File f : new File(dir).listFiles()) {
            names.add(f.getName());
        }
        java.util.Collections.sort(names);
        assertEquals(java.util.List.of("log-10-00-00.sql", "log-10-00-02.sql", "log-10-00-04.sql"), names);
        saver.close();
    }

    @Test
    public void 单文件超限滚动序号递增() throws Exception {
        // 回归：旧实现滚动分支条件恒真且递归无出口，达阈值即 StackOverflowError
        SqlLogAutoSaver saver = new SqlLogAutoSaver(System::currentTimeMillis, 100);
        String dir = tempDir();
        saver.configure(dir, "test.sql", null);
        for (int i = 0; i < 3; i++) {
            saver.write(entry("SELECT " + i));
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (File f : new File(dir).listFiles()) {
            names.add(f.getName());
        }
        java.util.Collections.sort(names);
        assertEquals(java.util.List.of("test.2.sql", "test.sql"), names);
        assertEquals(2, Files.readString(new File(dir, "test.sql").toPath()).split("-- source:", -1).length - 1);
        assertEquals(1, Files.readString(new File(dir, "test.2.sql").toPath()).split("-- source:", -1).length - 1);
        saver.close();
    }

    @Test
    public void 跨天后滚动序号复位() throws Exception {
        final long[] now = {timeOf("2026-09-04 10:00:00")};
        SqlLogAutoSaver saver = new SqlLogAutoSaver(() -> now[0], 100);
        String dir = tempDir();
        saver.configure(dir, "sql-history-$yyyy-MM-dd$.sql", null);
        for (int i = 0; i < 3; i++) {
            saver.write(entry("SELECT " + i));
        }
        assertTrue(new File(dir, "sql-history-2026-09-04.2.sql").exists());
        now[0] = timeOf("2026-09-05 10:00:00");
        saver.write(entry("SELECT 9"));
        assertEquals("sql-history-2026-09-05.sql", saver.currentFile().getName());
        saver.close();
    }

    // ------------------------------------------------------------------
    // 格式 v2：参数持久化行（T-HistParam）
    // ------------------------------------------------------------------

    @Test
    public void 带参数条目写参数行_纯静态不写() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "test.sql", null);
        saver.write(new LogSqlEntry(
                "SELECT * FROM t WHERE a = ?",
                "SELECT * FROM t WHERE a = 'x'",
                List.of(new LogSqlParam("x", "String")),
                1724567890000L));
        saver.write(entry("SELECT 1"));
        saver.close();
        String content = Files.readString(new File(dir, "test.sql").toPath());
        java.util.List<String> blocks = java.util.Arrays.stream(content.split("-- source:")).skip(1).collect(java.util.stream.Collectors.toList());
        assertEquals(2, blocks.size());
        String withParams = blocks.get(0);
        String staticBlock = blocks.get(1);
        assertTrue("带参条目应有 pd 行: " + withParams, withParams.contains("\n-- pd: "));
        assertFalse("纯静态条目不应有 pd 行: " + staticBlock, staticBlock.contains("-- pd: "));
        // 正文仍是可执行 SQL（restoredSql）
        assertTrue(withParams.contains("SELECT * FROM t WHERE a = 'x'"));

        // 读回 round-trip：rawSql/params 还原，识别通道可用
        java.util.List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(new File(dir, "test.sql"));
        assertEquals(2, entries.size());
        assertEquals("SELECT * FROM t WHERE a = ?", entries.get(0).rawSql());
        assertEquals(List.of(new LogSqlParam("x", "String")), entries.get(0).params());
        assertEquals("SELECT * FROM t WHERE a = 'x'", entries.get(0).restoredSql());
        assertTrue(LogEntryPreviewRecognizerKt.toRecognizedPreview(entries.get(0)).recognized());
        assertEquals("SELECT 1", entries.get(1).rawSql());
        assertTrue(entries.get(1).params().isEmpty());
    }

    @Test
    public void 绑定行缺失的占位符条目也写参数行() throws Exception {
        // params 空但 rawSql 含 ?：历史页重试识别需要 rawSql 的占位符形态
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "test.sql", null);
        saver.write(new LogSqlEntry(
                "SELECT * FROM t WHERE a = ?",
                "SELECT * FROM t WHERE a = ?",
                List.<LogSqlParam>of(),
                1724567890000L));
        saver.close();
        String content = Files.readString(new File(dir, "test.sql").toPath());
        assertTrue("应写 pd 行: " + content, content.contains("\n-- pd: "));
    }

    // ------------------------------------------------------------------
    // T-McpAuditTrail T-MAT-03：MCP 来源标记的 pd 持久化（配套必改）
    // ------------------------------------------------------------------

    /** 无参数/无占位符/无扩展字段的最小 MCP 条目——explain 落历史的常态形态。 */
    private static LogSqlEntry mcpEntry(SqlSource source) {
        return new LogSqlEntry(
                "SELECT id, name FROM user WHERE id = 1",
                "SELECT id, name FROM user WHERE id = 1",
                List.<LogSqlParam>of(),
                1724567890000L,
                null,
                LogSqlEntry.summarizeSql("SELECT id, name FROM user WHERE id = 1"),
                source,
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
                null);
    }

    @Test
    public void MCP来源无参数无占位符_仍写pd行() throws Exception {
        // T-MAT-03 配套必改的专属用例：pd 写入条件若只判 params/占位符/扩展字段，
        // 这条无参无占位符的 MCP SELECT 会丢掉来源标记——本任务的存在意义所在。
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "test.sql", null);
        saver.write(mcpEntry(SqlSource.MCP));
        // 对照：除来源外逐字段相同的非 MCP 条目
        saver.write(mcpEntry(SqlSource.UNKNOWN));
        saver.close();

        String content = Files.readString(new File(dir, "test.sql").toPath());
        java.util.List<String> blocks = java.util.Arrays.stream(content.split("-- source:")).skip(1)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, blocks.size());
        assertTrue("MCP 条目应写 pd 行: " + blocks, blocks.get(0).contains("\n-- pd: "));
        assertFalse("非 MCP 同形态条目不写 pd 行: " + blocks.get(1), blocks.get(1).contains("-- pd: "));

        // 头部段零新增：两条头部逐字符相等（T-MAT-03 硬约束 1——头部不得出现来源新段）
        java.util.List<String> headers = content.lines().filter(l -> l.startsWith("-- source:"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals("两条头部逐字符一致（MCP 不得新增头部段）", headers.get(0), headers.get(1));

        // 载荷携带来源标记（读端回填被并行占用文件阻塞，见计划 §实施记录 T-MAT-03）
        String payloadLine = blocks.get(0).lines().filter(l -> l.startsWith("-- pd: ")).findFirst().orElse(null);
        assertNotNull("应写 pd 行", payloadLine);
        String payload = payloadLine.substring("-- pd: ".length());
        if (payload.endsWith(" --")) {
            payload = payload.substring(0, payload.length() - 3);
        }
        SqlLogParamCodec.DecodedPayload decoded = SqlLogParamCodec.decode(payload);
        assertEquals(SqlSource.MCP, decoded.source());
        assertEquals("SELECT id, name FROM user WHERE id = 1", decoded.rawSql());
    }

    @Test
    public void MCP条目pd行不破坏既有读回() throws Exception {
        // pd 行是新增行，正文与既有字段读回不得受影响（旧版读端把它当注释行的同一形态）
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "test.sql", null);
        saver.write(mcpEntry(SqlSource.MCP));
        saver.close();

        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(new File(dir, "test.sql"));
        assertEquals(1, entries.size());
        assertEquals("SELECT id, name FROM user WHERE id = 1", entries.get(0).restoredSql());
        assertEquals("SELECT id, name FROM user WHERE id = 1", entries.get(0).rawSql());
        assertTrue("MCP 条目无参数", entries.get(0).params().isEmpty());
        assertEquals(Long.valueOf(7L), entries.get(0).executionTimeMs());
        assertEquals(1724567890000L, entries.get(0).timestamp());
        assertEquals("countInFile 只数头部行，pd 行不参与计数", 1,
                SqlLogFileReader.INSTANCE.countInFile(new File(dir, "test.sql")));
    }

    // ------------------------------------------------------------------
    // T-SqlHistoryFull：全量写盘风暴压测（对应用户关切——全量化后大写盘量不卡、不丢）
    // ------------------------------------------------------------------

    /**
     * 压测：单线程风暴投递（模拟控制台风暴期每条成功入队即 submit）。
     * onEntryStored 全量化后大写盘是用户首要性能关切——断言：
     *  1) 不丢条：close 同步清空在途队列后文件块数 == 投递数；
     *  2) T-Perf-12 批量写收敛：投递 N 条只触发 ≪N 次的写任务批次（writeBatch 整批一次 flush）。
     */
    @Test
    public void 压测_万条全量投递不丢条_批量写收敛() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "storm.sql", null);

        int n = 20000;
        for (int i = 0; i < n; i++) {
            saver.submit(entry("SELECT * FROM t WHERE id = " + i));
        }
        // close 同步清空在途队列（不丢条）——写线程吞掉全部 2 万条
        saver.close();

        File file = new File(dir, "storm.sql");
        assertTrue("文件已创建", file.exists());
        int count = Files.readString(file.toPath()).split("-- source:").length - 1;
        assertEquals("万条全量投递不丢条", n, count);
    }

    /**
     * 压测：读侧流式往返一致——全量写盘的大文件能被流式逐行读回，条目数与内容与写入一致
     * （SqlLogFileReader 整读改流式的回归防线；往返走既有读取 API，非 readText）。
     */
    @Test
    public void 压测_万条全量写入后流式读回一致() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "roundtrip.sql", null);
        int n = 10000;
        for (int i = 0; i < n; i++) {
            saver.submit(entry("SELECT * FROM big WHERE id = " + i + " -- marker=" + i));
        }
        saver.close();

        File file = new File(dir, "roundtrip.sql");
        // 生产读路径：流式逐行（readFromFile 内部 useLines），不整读
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(file);
        assertEquals("读回条数与写入一致", n, entries.size());
        assertTrue("首条 SQL 正确", entries.get(0).restoredSql().startsWith("SELECT * FROM big WHERE id = 0"));
        assertTrue("末条 SQL 正确", entries.get(entries.size() - 1).restoredSql().endsWith("-- marker=" + (n - 1)));
    }

    // ------------------------------------------------------------------
    // T-HistoryFileRetention：历史文件保留上限裁剪（防磁盘爆炸）
    // ------------------------------------------------------------------

    private static void makeFile(String dir, String name, long stamp) throws Exception {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), "-- source: x --\nSELECT 1\n\n");
        f.setLastModified(stamp);
    }

    private static List<String> fileNames(String dir) {
        File[] files = new File(dir).listFiles();
        return java.util.Arrays.stream(files == null ? new File[0] : files)
                .map(File::getName).sorted().collect(java.util.stream.Collectors.toList());
    }

    /** pruneRetainedFiles 直接驱动：造 maxFiles+1 个文件，断言删到只剩 maxFiles 个（删最旧）。 */
    @Test
    public void 上限裁剪_超限删最旧文件() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "sql-history-$yyyy-MM-dd$.sql", null, 2);
        // 三个不同基名文件，stamp 递增（最早 = 2026-09-10）
        makeFile(dir, "sql-history-2026-09-10.sql", 1000);
        makeFile(dir, "sql-history-2026-09-15.sql", 2000);
        makeFile(dir, "sql-history-2026-09-16.sql", 3000);
        saver.pruneRetainedFiles();
        assertEquals("保留最近 2 个逻辑文件，删最旧",
                List.of("sql-history-2026-09-15.sql", "sql-history-2026-09-16.sql"), fileNames(dir));
        saver.close();
    }

    /** maxFiles<=0（全量保留）时超过也不删。 */
    @Test
    public void 上限裁剪_0表示全量保留不删() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "sql-history-$yyyy-MM-dd$.sql", null, 0);
        makeFile(dir, "sql-history-2026-09-10.sql", 1000);
        makeFile(dir, "sql-history-2026-09-15.sql", 2000);
        makeFile(dir, "sql-history-2026-09-16.sql", 3000);
        saver.pruneRetainedFiles();
        assertEquals("0=不限制，3 个全保留", 3, new File(dir).listFiles().length);
        saver.close();
    }

    /** 滚动块归并：同 base 的两块计 1 个逻辑文件，不被误删。 */
    @Test
    public void 上限裁剪_滚动块按基名归并不误删() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure(dir, "sql-history-$yyyy-MM-dd$.sql", null, 2);
        // 4 个文件实际是 3 个逻辑文件（09-10 有 .sql + .2.sql）
        makeFile(dir, "sql-history-2026-09-10.sql", 1000);
        makeFile(dir, "sql-history-2026-09-10.2.sql", 1300);
        makeFile(dir, "sql-history-2026-09-15.sql", 2000);
        makeFile(dir, "sql-history-2026-09-16.sql", 3000);
        saver.pruneRetainedFiles();
        assertEquals("3 组不超上限 2？不，3>2 应删最旧组（09-10 两块全删）",
                List.of("sql-history-2026-09-15.sql", "sql-history-2026-09-16.sql"), fileNames(dir));
        saver.close();
    }

    /** 自定义目录同样生效（maxFiles 生效于 configure 传入的目录）。
     *  T-PS4-10 后：裁剪只认领本插件名族——族内超限删最旧，族外文件（init.sql）不计数零删除。 */
    @Test
    public void 上限裁剪_自定义目录生效() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String customDir = tempDir();
        saver.configure(customDir, "log-$yyyy-MM-dd$.sql", null, 1);
        makeFile(customDir, "log-2026-09-10.sql", 1000);
        makeFile(customDir, "log-2026-09-15.sql", 2000);
        makeFile(customDir, "init.sql", 900);
        saver.pruneRetainedFiles();
        assertEquals("族内删最旧、族外用户文件零删除",
                List.of("init.sql", "log-2026-09-15.sql"), fileNames(customDir));
        saver.close();
    }

    // ---------- T-HistoryTabAlways：目录解析（读端与写端同源） ----------

    /** 自定义保存目录优先，且去首尾空白。 */
    @Test
    public void test目录解析_自定义目录优先并去空白() throws Exception {
        assertEquals(
            "自定义目录应原样生效（仅去首尾空白）",
            "/tmp/custom-history",
            SqlLogAutoSaver.resolveHistoryDir("  /tmp/custom-history  ", "/proj"));
    }

    /** 未填自定义目录 → 项目根下 .idea/sql-visualizer/sql-history。 */
    @Test
    public void test目录解析_默认落项目根idea子目录() throws Exception {
        String expected = "/proj" + java.io.File.separator + ".idea" + java.io.File.separator +
            "sql-visualizer" + java.io.File.separator + "sql-history";
        assertEquals(expected, SqlLogAutoSaver.resolveHistoryDir("", "/proj"));
    }

    /** 无项目根（Default Project 等非文件型项目）→ 回落 user.home 下的同一相对路径（与写端一致）。 */
    @Test
    public void test目录解析_无项目根回落user_home() throws Exception {
        String expected = System.getProperty("user.home") + java.io.File.separator + ".idea" +
            java.io.File.separator + "sql-visualizer" + java.io.File.separator + "sql-history";
        assertEquals(expected, SqlLogAutoSaver.resolveHistoryDir("", null));
    }
}
