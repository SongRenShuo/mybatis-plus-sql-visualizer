package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 .idea/sql-visualizer/sql-history/ 文件反解析为 LogSqlEntry（F-44；F-44-4 目录随插件更名）。
 *
 * 支持两种格式：
 *   - 新版（F-44-2）：-- source: xxx | time: xxx | yyyy-MM-dd HH:mm:ss.SSS --\nSQL\n
 *   - 历史 F-44（已停止生产）：--- source: xxx | time: xxx | timestamp ---\nSQL\n
 *   - 旧版（F-43）：-- yyyy-MM-dd HH:mm:ss\nSQL\n
 *
 * 混合文件中三种格式共存时均可正确解析。
 */
public final class SqlLogFileReader {

    public static final SqlLogFileReader INSTANCE = new SqlLogFileReader();

    private SqlLogFileReader() {
    }

    /** T-Perf-05：countInFile 缓存上限（超过整体清空——路径多样性有限，简单封顶防膨胀）。 */
    private static final int COUNT_CACHE_MAX = 256;

    /**
     * T-PerfSweep7 4a 测试缝：SimpleDateFormat 实际构造次数（仅供同模块测试断言
     * 「同格式两次解析只建一次 formatter」；生产语义与本计数无关）。
     */
    public static final java.util.concurrent.atomic.AtomicInteger formatterCreateCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * T-Perf-05：countInFile 结果缓存（键=绝对路径，值=(lastModified, length, 计数)）。
     * 自动保存单文件滚动阈值 100MB（SqlLogAutoSaver），长期开启必然长出大文件——
     * 每次 refresh 逐行全量扫描是历史 tab 打开冻结的主要来源；lastModified+length 未变即命中
     * （length 一并校验，规避同毫秒重写导致 lastModified 不变而内容已变的误命中）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> COUNT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 新版格式头部：兼容 2 或 3 个 `-` 起结（前缀与后缀的 `-` 数量独立，长度匹配即可）。
     * 捕获组：1=source 2=time 3=timestamp 4=logTime（可选）5=db（可选，T-AgentDialectLoop）。
     * 毫秒分数为非捕获组——曾误用捕获组占位第 4 组致 logTime 永远读丢（T-AgentDialectLoop 修复）。
     */
    private static final Pattern NEW_HEADER_REGEX = Pattern.compile(
            "^--+ source: (.+?) \\| time: (.+?) \\| (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)(?: \\| logTime: (\\d+))?(?: \\| db: (.+?))? --+$");

    /** 旧版格式头部：-- timestamp（无源码位置和耗时） */
    private static final Pattern OLD_HEADER_REGEX = Pattern.compile(
            "^-- (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?)$");

    /**
     * 参数持久化行前缀/结尾（T-HistParam F1 格式 v2）：头部注释行之下的独立注释行，
     * 载荷为 Base64 编码的 rawSql + params（编解码见 SqlLogParamCodec）。
     * 前缀固定 `-- pd: ` 且以 ` --` 结尾——旧版头部判据（`-- ` 起始无 `source:`）会把
     * pd 行误判为 F-43 头部导致块被劈开，块切分与计数必须显式排除。
     */
    private static final String PD_PREFIX = "-- pd: ";
    private static final String PD_SUFFIX = " --";

    /** O-147：历史修订块前缀（`-- pd: o147-id: <uuid>` / `-- pd: o147-revision: <uuid> <sha> <b64>`）。 */
    private static final String ID_PREFIX = "-- pd: o147-id: ";
    private static final String REVISION_PREFIX = "-- pd: o147-revision: ";
    static final int MAX_REVISION_PAYLOAD = 65536;
    static final int MAX_REVISIONS = 2048;
    private static final int MAX_REVISION_TOTAL = 8 * 1024 * 1024;
    private static final java.util.regex.Pattern ID_REGEX =
            java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * 读取目录下所有 .sql 文件，返回按时间戳排序的 LogSqlEntry 列表。
     */
    public List<LogSqlEntry> readAll(String dir) {
        File directory = new File(dir);
        if (!directory.isDirectory()) {
            return List.of();
        }
        File[] files = directory.listFiles(f -> f.getName().endsWith("sql"));
        if (files == null) {
            return List.of();
        }
        List<LogSqlEntry> all = new ArrayList<>();
        for (File f : files) {
            all.addAll(readFromFile(f));
        }
        all.sort(Comparator.comparingLong(LogSqlEntry::timestamp));
        return all;
    }

    /** O-147 修订记录（identity = 首次 SQL/绑定/时间的 SHA-256，见 SqlLogParamCodec.historyIdentity）。 */
    private static final class Revision {
        final String identity;
        final LogSqlEntry entry;

        Revision(String identity, LogSqlEntry entry) {
            this.identity = identity;
            this.entry = entry;
        }
    }

    /** 修订容器：按文件读入的修订块（限流：条数/字节双上限；超限行折叠为裸前缀）。 */
    private static final class Revisions {
        final java.util.Map<String, Revision> values = new java.util.HashMap<>();
        private long bytes = 0;
        private int count = 0;

        void offer(String line) {
            if (!line.startsWith(REVISION_PREFIX)) {
                return;
            }
            // 文件流把超长修订折叠成裸前缀；直接传入完整字符串也按同一前缀成本计费。
            int chargedLength = line.length() > MAX_REVISION_PAYLOAD + 150 ? REVISION_PREFIX.length() : line.length();
            if (count >= MAX_REVISIONS || bytes + chargedLength > MAX_REVISION_TOTAL) {
                return;
            }
            count++;
            bytes += chargedLength;
            if (line.length() > MAX_REVISION_PAYLOAD + 150) {
                return;
            }
            try {
                String stripped = line.substring(REVISION_PREFIX.length());
                if (stripped.endsWith(PD_SUFFIX)) {
                    stripped = stripped.substring(0, stripped.length() - PD_SUFFIX.length());
                }
                String[] parts = stripped.split(" ", 3);
                if (parts.length != 3 || !ID_REGEX.matcher(parts[0]).matches()
                        || !parts[1].matches("[0-9a-f]{64}") || parts[2].length() > MAX_REVISION_PAYLOAD) {
                    return;
                }
                String decoded = new String(java.util.Base64.getDecoder().decode(parts[2]),
                        java.nio.charset.StandardCharsets.UTF_8);
                // 禁止递归修订，且只接受一个完整原生历史块。
                if (decoded.contains(ID_PREFIX) || decoded.contains(REVISION_PREFIX)) {
                    return;
                }
                EntryBlockScanner scanner = new EntryBlockScanner(false);
                List<LogSqlEntry> parsed = new ArrayList<>();
                for (String revLine : decoded.split("\n", -1)) {
                    LogSqlEntry e = scanner.offer(revLine);
                    if (e != null) {
                        parsed.add(e);
                    }
                }
                LogSqlEntry last = scanner.finish();
                if (last != null) {
                    parsed.add(last);
                }
                if (parsed.size() != 1) {
                    return;
                }
                LogSqlEntry entry = parsed.get(0);
                if (!SqlLogParamCodec.historyIdentity(entry).equals(parts[1])) {
                    return;
                }
                values.put(parts[0], new Revision(parts[1], entry));
            } catch (Throwable ignored) {
                // runCatching 等价：损坏修订块按不存在处理
            }
        }

        /** 身份匹配则应用元数据升级（SQL/绑定/时间/序号永远来自原始块）；否则原样返回。 */
        LogSqlEntry apply(String id, LogSqlEntry original) {
            Revision revision = id != null ? values.get(id) : null;
            if (revision == null || !SqlLogParamCodec.historyIdentity(original).equals(revision.identity)) {
                return original;
            }
            LogSqlEntry merged = cn.song.mybatisplus.visualizer.log.LogSqlModelsKt.mergeDuplicateEntry(
                    original, revision.entry);
            return merged
                    .withRawSql(original.rawSql())
                    .withRestoredSql(original.restoredSql())
                    .withParams(original.params())
                    .withTimestamp(original.timestamp())
                    .withSequenceNumber(original.sequenceNumber());
        }
    }

    /** 修订行超限即丢弃剩余字符，不先 readLine 分配任意大小的外部载荷。普通 SQL 不截断。 */
    private static java.util.Iterator<String> boundedLines(java.io.BufferedReader reader) {
        return new java.util.Iterator<String>() {
            final StringBuilder text = new StringBuilder();
            boolean revision = false;
            boolean overLimit = false;
            String next = null;
            boolean eof = false;

            private void advance() {
                if (next != null || eof) {
                    return;
                }
                while (true) {
                    int c;
                    try {
                        c = reader.read();
                    } catch (java.io.IOException e) {
                        c = -1;
                    }
                    if (c == -1 || c == '\n') {
                        if (text.length() > 0 || c != -1) {
                            String out = text.toString();
                            if (out.endsWith("\r")) {
                                out = out.substring(0, out.length() - 1);
                            }
                            next = overLimit ? REVISION_PREFIX : out;
                        }
                        text.setLength(0);
                        revision = false;
                        overLimit = false;
                        if (c == -1) {
                            eof = true;
                        }
                        return;
                    }
                    if (!overLimit) {
                        text.append((char) c);
                        if (text.length() == REVISION_PREFIX.length()) {
                            revision = text.toString().equals(REVISION_PREFIX);
                        }
                        // 最后一个 CR 暂不计入逻辑长度：若随后 LF/EOF，出行时剥除；
                        // 若随后仍有字符，它会重新计入。只宽限单个尾 CR，不截断普通 SQL。
                        int logicalLength = text.length() - (c == '\r' ? 1 : 0);
                        if (revision && logicalLength > MAX_REVISION_PAYLOAD + 150) {
                            text.setLength(0);
                            overLimit = true;
                        }
                    }
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return next != null;
            }

            @Override
            public String next() {
                advance();
                String out = next;
                next = null;
                return out;
            }
        };
    }

    private static Revisions revisionsIn(File file) {
        Revisions revisions = new Revisions();
        if (!file.exists()) {
            return revisions;
        }
        try (java.io.BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            java.util.Iterator<String> lines = boundedLines(reader);
            while (lines.hasNext()) {
                revisions.offer(lines.next());
            }
        } catch (Exception ignored) {
            // 读失败按无修订处理
        }
        return revisions;
    }

    public List<LogSqlEntry> readFromFile(File file) {
        if (!file.exists()) {
            return List.of();
        }
        // T-SqlHistoryFull：整读 file.readText() → 流式逐行（单文件滚动阈值 100MB，整读内存峰值可达文件体量；
        // 逐行消费只在解析块内累积当前块，峰值≈单条 SQL）。解析结果与整读路径完全一致。
        // T-SqlLogPerf：历史文件加载是 20261006 实测延迟大头嫌疑（8708 条/19MB 计 15~16.6s），阈值打点归因。
        return LogPerfProbe.measure("SqlLogFileReader.readFromFile",
                () -> file.getName() + " " + file.length() + "B",
                () -> {
                    try (java.io.BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                        // O-147：修订已整文件预扫（revisionsIn），正文再过 boundedLines 时不重复收集。
                        Revisions revisions = revisionsIn(file);
                        return parseContent(java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(boundedLines(reader), java.util.Spliterator.ORDERED), false),
                        revisions, false);
                    } catch (Exception e) {
                        return List.of();
                    }
                });
    }

    /**
     * 快速统计文件中的 SQL 条目数（F-44-3 两级导航一级列表用）：
     * 只数头部行，不构建 LogSqlEntry。判定规则与 {@link #parseContent(String)} 保持一致。
     */
    public int countInFile(File file) {
        if (!file.exists()) {
            return 0;
        }
        String key = file.getAbsolutePath();
        long lastModified = file.lastModified();
        long length = file.length();
        long[] cached = COUNT_CACHE.get(key);
        if (cached != null && cached[0] == lastModified && cached[1] == length) {
            return (int) cached[2];
        }
        int count = 0;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.stripLeading();
                if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                    count++;
                } else if (line.startsWith("-- ") && !line.contains("source:") && !line.startsWith(PD_PREFIX)) {
                    count++;
                }
            }
        } catch (Exception e) {
            return count;
        }
        if (COUNT_CACHE.size() >= COUNT_CACHE_MAX) {
            COUNT_CACHE.clear();
        }
        COUNT_CACHE.put(key, new long[]{lastModified, length, count});
        return count;
    }

    /**
     * 解析文件内容为 LogSqlEntry 列表。逐行扫描，遇到头部行则开始新块，兼容新旧两种头部混合。
     *
     * <p>T-LogHistoryRowNo：返回前按解析顺序注入文件内条目序号（sequenceNumber = 索引+1）——
     * 历史行号语义 = 文件内顺序号（静态历史无「会话内捕获序号」概念，见开发计划 §1.2）。
     * 序号是读取层派生的展示属性，不持久化；搜索过滤只删不改造条目实例，序号天然稳定。
     */
    public List<LogSqlEntry> parseContent(String content) {
        return parseContent(content.lines());
    }

    /**
     * 解析文件内容为 LogSqlEntry 列表（T-SqlHistoryFull：流式逐行输入源）。
     * 逐行扫描，遇到头部行则开始新块，兼容新旧两种头部混合。
     *
     * <p>T-LogHistoryRowNo：返回前按解析顺序注入文件内条目序号（sequenceNumber = 索引+1）——
     * 历史行号语义 = 文件内顺序号（静态历史无「会话内捕获序号」概念，见开发计划 §1.2）。
     * 序号是读取层派生的展示属性，不持久化；搜索过滤只删不改造条目实例，序号天然稳定。
     */
    public List<LogSqlEntry> parseContent(java.util.stream.Stream<String> lines) {
        return parseContent(lines, new Revisions());
    }

    public List<LogSqlEntry> parseContent(java.util.stream.Stream<String> lines, Revisions revisions) {
        return parseContent(lines, revisions, true);
    }

    /**
     * O-147：解析内容并应用/收集修订块（readFromFile 路径 revisions 已预扫故 collectRevisions=false）。
     * 条目序号按文件内解析顺序注入（防御：已有序号的条目不覆盖）。
     */
    public List<LogSqlEntry> parseContent(java.util.stream.Stream<String> lines, Revisions revisions,
                                          boolean collectRevisions) {
        List<LogSqlEntry> entries = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        // 共享块切分状态机（parseContent 与 forEachEntry 单一实现源）：逐行喂入，头部行
        // 开新块并吐出上一块的解析结果。
        EntryBlockScanner scanner = new EntryBlockScanner(true);
        for (java.util.Iterator<String> it = lines.iterator(); it.hasNext(); ) {
            String line = it.next();
            if (collectRevisions) {
                revisions.offer(line);
            }
            LogSqlEntry done = scanner.offer(line);
            if (done != null) {
                entries.add(done);
                ids.add(scanner.completedId);
            }
        }
        LogSqlEntry last = scanner.finish();
        if (last != null) {
            entries.add(last);
            ids.add(scanner.completedId);
        }
        List<LogSqlEntry> numbered = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            LogSqlEntry entry = revisions.apply(ids.get(i), entries.get(i));
            numbered.add(entry.sequenceNumber() == 0L ? entry.withSequenceNumber(i + 1L) : entry);
        }
        return numbered;
    }

    /**
     * O-147：逐条迭代器——回调形，action 返回 false 即停止读取（扫描封顶/提前收束），
     * 文件随迭代及时关闭（use 语义，中途停止不漏句柄）。
     *
     * <p>与 {@link #parseContent(java.util.stream.Stream)} 共用同一 {@link EntryBlockScanner}
     * 状态机（单遍化、逐字节同语义，round-trip 测试钉），条目序号按产出顺序注入。
     * 旧接口全部原样保留。</p>
     */
    public void forEachEntry(File file, java.util.function.Predicate<LogSqlEntry> action) {
        if (!file.exists()) {
            return;
        }
        LogPerfProbe.measure("SqlLogFileReader.forEachEntry",
                () -> file.getName() + " " + file.length() + "B",
                () -> {
                    Revisions revisions = revisionsIn(file);
                    try (java.io.BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                        EntryBlockScanner scanner = new EntryBlockScanner(true);
                        long seq = 0L;
                        java.util.Iterator<String> lines = boundedLines(reader);
                        while (lines.hasNext()) {
                            LogSqlEntry original = scanner.offer(lines.next());
                            if (original == null) {
                                continue;
                            }
                            LogSqlEntry entry = revisions.apply(scanner.completedId, original);
                            seq++;
                            LogSqlEntry numbered = entry.sequenceNumber() == 0L
                                    ? entry.withSequenceNumber(seq) : entry;
                            if (!action.test(numbered)) {
                                return null;
                            }
                        }
                        LogSqlEntry tail = scanner.finish();
                        if (tail != null) {
                            LogSqlEntry entry = revisions.apply(scanner.completedId, tail);
                            seq++;
                            LogSqlEntry numbered = entry.sequenceNumber() == 0L
                                    ? entry.withSequenceNumber(seq) : entry;
                            action.test(numbered);
                        }
                    } catch (Exception ignored) {
                        // use 语义：读失败即收束
                    }
                    return null;
                });
    }

    /**
     * 共享块切分状态机（parseContent 与 forEachEntry 单一实现源）：逐行喂入，头部行
     * 开新块并吐出上一块的解析结果（无上一块/头部不合规解析失败返回 null）。
     * 头部判定与 {@code countInFile} 的计数判据保持一致（含 source:/time: 的新版头部、
     * {@code -- 日期} 的旧版头部、排除 {@code -- pd: } 参数持久化行）。
     */
    static final class EntryBlockScanner {
        private String header = null;
        private String id = null;
        private String completedId = null;
        private final List<String> bodyLines = new ArrayList<>();
        private final boolean skipRevisions;

        EntryBlockScanner(boolean skipRevisions) {
            this.skipRevisions = skipRevisions;
        }

        /** 喂一行；若该行开启新块则返回上一块解析结果（无上一块或解析失败返回 null）。 */
        LogSqlEntry offer(String line) {
            if (skipRevisions && line.startsWith(REVISION_PREFIX)) {
                return null;
            }
            if (line.startsWith(ID_PREFIX)) {
                if (header != null) {
                    String stripped = line.substring(ID_PREFIX.length());
                    if (stripped.endsWith(PD_SUFFIX)) {
                        stripped = stripped.substring(0, stripped.length() - PD_SUFFIX.length());
                    }
                    id = ID_REGEX.matcher(stripped).matches() ? stripped : null;
                }
                return null;
            }
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                LogSqlEntry done = flush();
                header = line;
                return done;
            }
            if (line.startsWith("-- ") && !line.contains("source:") && !line.startsWith(PD_PREFIX)) {
                LogSqlEntry done = flush();
                header = line;
                return done;
            }
            if (header != null) {
                bodyLines.add(line);
            }
            return null;
        }

        /** 收尾：吐出最后一个未闭合块（无块/解析失败返回 null）。 */
        LogSqlEntry finish() {
            return flush();
        }

        private LogSqlEntry flush() {
            String h = header;
            if (h == null) {
                return null;
            }
            LogSqlEntry e = parseBlockLines(h, bodyLines);
            completedId = id;
            id = null;
            header = null;
            bodyLines.clear();
            return e;
        }
    }

    private static LogSqlEntry parseBlockLines(String header, List<String> contentLines) {
        // T-SqlLogPerf（20261006-1020 计划 P2）：直收 parseContent 的行序列——旧 parseBlock(block)
        // 的 block.lines() 再切分已随单遍化移除；头部行/正文行由调用方分开传入，语义逐字一致。
        List<String> bodyLines = contentLines;
        String rawSqlOverride = null;
        List<LogSqlParam> params = List.of();
        SqlLogParamCodec.DecodedPayload decodedExt = null;
        if (!contentLines.isEmpty()) {
            String first = contentLines.get(0);
            String trimmedFirst = first.trim();
            if (trimmedFirst.startsWith(PD_PREFIX) && trimmedFirst.endsWith(PD_SUFFIX)) {
                SqlLogParamCodec.DecodedPayload payload = SqlLogParamCodec.decode(
                    trimmedFirst.substring(PD_PREFIX.length(), trimmedFirst.length() - PD_SUFFIX.length()));
                if (payload != null) {
                    rawSqlOverride = payload.rawSql();
                    params = payload.params();
                    decodedExt = payload;
                    bodyLines = contentLines.subList(1, contentLines.size());
                }
            }
        }
        // 写入端 withTerminator 会补 `;`（T-SqlDuration），读回去掉还原为原始 SQL——
        // 展示/复制路径会重新调用 withTerminator() 补回，此处只剥结尾一个
        String sql = String.join("\n", bodyLines).trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isEmpty()) {
            return null;
        }

        Matcher m1 = NEW_HEADER_REGEX.matcher(header);
        if (m1.find()) {
            String sourceStr = m1.group(1);
            String timeStr = m1.group(2);
            String timestampStr = m1.group(3);
            Long timestamp = parseTimestampNew(timestampStr);
            if (timestamp == null) {
                return null;
            }
            // logTime（可选段，master groupValues[4].toLongOrNull()——未匹配时空串转 null 等价）
            String logTimeStr = m1.group(4);
            Long logTime = logTimeStr != null ? toLongOrNull(logTimeStr) : null;
            Pair<String, Integer> src = parseSource(sourceStr);
            Long executionTimeMs = parseDuration(timeStr);
            // db（可选段，T-AgentDialectLoop；空白按 null）
            String dbStr = m1.group(5);
            String databaseName = dbStr != null && !dbStr.isBlank() ? dbStr : null;
            String rawSql = rawSqlOverride != null ? rawSqlOverride : sql;
            return new LogSqlEntry(rawSql, sql, params, timestamp,
                    logTime, LogSqlEntry.summarizeSql(sql),
                    decodedExt != null && decodedExt.source() != null ? decodedExt.source() : SqlSource.UNKNOWN,
                    executionTimeMs,
                    src.getFirst(), src.getSecond(),
                    decodedExt != null ? decodedExt.sourceCandidates() : List.of(),
                    decodedExt != null ? decodedExt.candidateClasses() : List.of(),
                    decodedExt != null ? decodedExt.stackDigest() : null,
                    0L,
                    decodedExt != null ? decodedExt.mapperStatement() : null,
                    decodedExt != null ? decodedExt.errorText() : null,
                    decodedExt != null ? decodedExt.requestTag() : null,
                    decodedExt != null ? decodedExt.updateCount() : null,
                    databaseName,
                    decodedExt != null ? decodedExt.actualSql() : null,
                    decodedExt != null && decodedExt.captureProvenance() != null
                            ? decodedExt.captureProvenance() : CaptureProvenance.UNKNOWN);
        }

        Matcher m2 = OLD_HEADER_REGEX.matcher(header);
        if (m2.find()) {
            String timestampStr = m2.group(1);
            Long timestamp = parseTimestampOld(timestampStr);
            if (timestamp == null) {
                return null;
            }
            String rawSql = rawSqlOverride != null ? rawSqlOverride : sql;
            return new LogSqlEntry(rawSql, sql, params, timestamp,
                    null, LogSqlEntry.summarizeSql(sql),
                    decodedExt != null && decodedExt.source() != null ? decodedExt.source() : SqlSource.UNKNOWN,
                    null,
                    null, 0,
                    decodedExt != null ? decodedExt.sourceCandidates() : List.of(),
                    decodedExt != null ? decodedExt.candidateClasses() : List.of(),
                    decodedExt != null ? decodedExt.stackDigest() : null,
                    0L,
                    decodedExt != null ? decodedExt.mapperStatement() : null,
                    decodedExt != null ? decodedExt.errorText() : null,
                    decodedExt != null ? decodedExt.requestTag() : null,
                    decodedExt != null ? decodedExt.updateCount() : null,
                    null,
                    null,
                    decodedExt != null && decodedExt.captureProvenance() != null
                            ? decodedExt.captureProvenance() : CaptureProvenance.UNKNOWN);
        }

        return null;
    }

    private static Pair<String, Integer> parseSource(String sourceStr) {
        if ("unknown".equals(sourceStr)) {
            return new Pair<>(null, 0);
        }
        int colonIdx = sourceStr.lastIndexOf(':');
        if (colonIdx > 0) {
            String file = sourceStr.substring(0, colonIdx);
            Integer line = toIntOrNull(sourceStr.substring(colonIdx + 1));
            return new Pair<>(file, line != null ? line : 0);
        }
        return new Pair<>(sourceStr, 0);
    }

    private static Long parseDuration(String timeStr) {
        if ("—".equals(timeStr) || "-".equals(timeStr) || timeStr.isBlank()) {
            return null;
        }
        String s = timeStr.endsWith("ms") ? timeStr.substring(0, timeStr.length() - 2) : timeStr;
        return toLongOrNull(s);
    }

    /**
     * T-PerfSweep7 4a：SimpleDateFormat 每次解析新建 → <b>每线程每格式一次</b>（照
     * {@code LogTimestampParser.parseTimestamp} 的 ThreadLocal + 格式串键缓存范式；日志导入是
     * O(行数) 的解析热路径，SDF 构造是其中的固定开销）。
     *
     * <p><b>等价性红线</b>：复用实例的输出与逐次新建逐字节一致（{@code parse} 每次完整重置内部 calendar）；
     * 且<b>刻意不</b>照搬 LogTimestampParser 的 {@code isLenient = false} 与时区赋值——本处现状是
     * 默认宽松模式 + 默认时区，缓存化只改构造频率，不改任何解析语义（异常回落 null 不变）。
     */
    private static final ThreadLocal<java.util.HashMap<String, SimpleDateFormat>> TIMESTAMP_FORMAT_CACHE =
            ThreadLocal.withInitial(java.util.HashMap::new);

    /** 取（或按格式串新建并缓存）本线程的 SimpleDateFormat（{@code getOrPut} 语义）。 */
    private static SimpleDateFormat formatterFor(String pattern) {
        java.util.HashMap<String, SimpleDateFormat> cache = TIMESTAMP_FORMAT_CACHE.get();
        SimpleDateFormat existing = cache.get(pattern);
        if (existing != null) {
            return existing;
        }
        formatterCreateCount.incrementAndGet();
        SimpleDateFormat created = new SimpleDateFormat(pattern, Locale.ENGLISH);
        cache.put(pattern, created);
        return created;
    }

    private static Long parseTimestampNew(String ts) {
        try {
            return formatterFor("yyyy-MM-dd HH:mm:ss.SSS").parse(ts).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseTimestampOld(String ts) {
        try {
            return formatterFor("yyyy-MM-dd HH:mm:ss").parse(ts).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
