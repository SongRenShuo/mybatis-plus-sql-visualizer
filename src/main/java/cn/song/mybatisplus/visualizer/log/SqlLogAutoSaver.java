package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动保存历史 SQL 到文件（F-43）：[SqlLogManager] 装配为 buffer 的 onEvicted 回调。
 *
 * 文件规则：
 *  - 默认路径：.idea/sql-visualizer/sql-history/（F-44-4：随插件更名）
 *  - 文件名模板：sql-history-$yyyy-MM-dd$.sql（支持 $yyyy$ $MM$ $dd$ $HH$ $mm$ $ss$ 占位符）
 *  - 文件滚动：单文件 ≥100MB 时滚动（.2.sql、.3.sql…）
 *  - 格式：`-- 时间戳\nSQL\n\n`（纯文本，可直接在数据库工具中打开）
 *
 * 生命周期跟随 [SqlLogManager]：manager dispose 时 [close] 释放文件句柄。
 *
 * T-AutoSaverSOE：write 的文件切换/滚动改循环判定，不再递归自调用（跨天/模板变更 +
 * 单文件超限叠加时旧实现递归两次以上可致 StackOverflowError）；configure/write/close 加锁，
 * 消除并发写与滚动判定竞态。
 */
public class SqlLogAutoSaver {

    /** 时钟（测试注入用），默认系统时钟。 */
    private final LongSupplier clock;

    // ---------- O-147：历史修订追加（元数据升级只追加修订注释，不重写旧字节） ----------

    /** 已写入条目的身份索引：实例身份→文件+随机 ID+fileKey+historyIdentity（上限 2048 条防无界）。 */
    private record WrittenBlock(java.io.File file, String id, Object key, String identity) {
    }

    private final java.util.IdentityHashMap<LogSqlEntry, WrittenBlock> writtenBlocks =
            new java.util.IdentityHashMap<>();
    private final java.util.Queue<java.util.AbstractMap.SimpleEntry<LogSqlEntry, LogSqlEntry>> pendingMerges =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.ArrayDeque<LogSqlEntry> writtenOrder = new java.util.ArrayDeque<>();

    private record WaitingMerge(LogSqlEntry merged, long expires) {
    }

    private final java.util.IdentityHashMap<LogSqlEntry, WaitingMerge> waitingMerges =
            new java.util.IdentityHashMap<>();
    private final java.util.ArrayDeque<LogSqlEntry> waitingOrder = new java.util.ArrayDeque<>();
    /** 单文件滚动阈值（默认 100MB），测试注入小值用。 */
    private final long rollThresholdBytes;
    /**
     * T-PS4-10：两次历史文件裁剪的最小间隔（默认 60s）——风暴期每非空写批不再重复全目录
     * listFiles+stat+排序。测试注入小值/可变时钟用。
     */
    private final long pruneMinIntervalMs;
    /**
     * T-PS4-13：configure 路径 close 时单次同步排水条数上限（默认 500）——调用线程常为 EDT，
     * 风暴在途积压大时限批排水，超出部分留在队列由后续 submit 重建的写线程补写（不丢条）。
     * 测试注入小值用；dispose 路径走无参 close 全量排水不受此限。
     */
    private final int configureDrainLimit;

    private BufferedWriter writer = null;
    private File currentFile = null;
    private String saveDir = "";
    private String fileNamePattern = "";
    private String currentResolvedName = "";
    private int currentIndex = 1;

    /** T-HistoryFileRetention：历史文件保留上限（按基名归并的组数）。&lt;=0 = 全量保留。 */
    private volatile int maxFiles = 0;

    /**
     * T-PS4-10：本插件历史文件名族匹配正则（由 fileNamePattern 推导，configure 时重建）。
     * null = 无法安全认领（configure 未跑过 / 模板无字面段可锚定）——放弃裁剪，宁漏删不误删。
     */
    private volatile Pattern familyRegex = null;

    /** T-PS4-10：上次实际执行裁剪扫描的时刻（节流基准；仅 pruneRetainedFiles 的监视器内读写）。-1 = 从未扫描。 */
    private long lastPruneAtMs = -1L;

    // ---------- T-Perf-12：异步批量写（淘汰条目投递无锁队列 + 专用单线程执行器） ----------

    /** 在途淘汰条目队列（控制台输出线程投递，写线程消费）。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<LogSqlEntry> pendingEntries =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 本轮已调度标志（幂等调度——风暴期 N 条投递 = 1 个写任务）。 */
    private final java.util.concurrent.atomic.AtomicBoolean drainScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 专用写线程（懒创建；close 后下次 submit 重建）。 */
    private volatile java.util.concurrent.ExecutorService executor = null;

    /** 默认构造（系统时钟 + 100MB 滚动阈值 + 默认裁剪间隔/排水上限）。 */
    public SqlLogAutoSaver() {
        this(System::currentTimeMillis, 100L * 1024 * 1024,
                DEFAULT_PRUNE_MIN_INTERVAL_MS, DEFAULT_CONFIGURE_DRAIN_LIMIT);
    }

    /** 完整构造（测试注入时钟与滚动阈值；裁剪间隔/排水上限取默认）。 */
    public SqlLogAutoSaver(LongSupplier clock, long rollThresholdBytes) {
        this(clock, rollThresholdBytes, DEFAULT_PRUNE_MIN_INTERVAL_MS, DEFAULT_CONFIGURE_DRAIN_LIMIT);
    }

    /** 全参构造（测试注入裁剪间隔/排水上限用）。 */
    public SqlLogAutoSaver(LongSupplier clock, long rollThresholdBytes, long pruneMinIntervalMs, int configureDrainLimit) {
        this.clock = clock;
        this.rollThresholdBytes = rollThresholdBytes;
        this.pruneMinIntervalMs = pruneMinIntervalMs;
        this.configureDrainLimit = configureDrainLimit;
    }

    /**
     * T-Perf-12：异步写入入口（buffer.onEvicted 回调用）——投递后由专用后台线程批量写出。
     * 此前回调在控制台输出热路径同步执行「检查文件长度 + 写两行 + flush 磁盘」，风暴期数百次/秒，
     * 且 EDT 刷新列表 snapshot() 抢同一把锁互相阻塞。
     */
    public void submit(LogSqlEntry entry) {
        pendingEntries.add(entry);
        if (drainScheduled.compareAndSet(false, true)) {
            ensureExecutor().execute(this::drainAndWrite);
        }
    }

    private java.util.concurrent.ExecutorService ensureExecutor() {
        java.util.concurrent.ExecutorService current = executor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        java.util.concurrent.ExecutorService created = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mpsv-sql-history-autosave");
            t.setDaemon(true);
            return t;
        });
        executor = created;
        return created;
    }

    /**
     * 写任务：整批一次 flush；写期间新到条目重新调度（不丢条）。
     * T-SqlHistoryFull：synchronized 与 {@link #close(int)} 在同一监视器串行——此前无锁 drainAndWrite 与 close
     * 并发时，后台已 poll 未写完的批次会在 close 关闭 writer 后被 writeEntry 的 currentFile==null 分支
     * 跳过丢条（2 万条风暴压测丢 9 千，dispose 与风暴写并发的既有缺陷，T-SqlHistoryFull 全量化放大触发面）。
     * writeBatch 本身 synchronized 重入，此处持锁串行即可保证「先写完全部再关闭」或「先清空写完全部」，两序都不丢。
     */
    private synchronized void drainAndWrite() {
        drainScheduled.set(false);
        // O-147：修订追加先于正文排水（同一写线程串行，防交错）
        drainMerges();
        List<LogSqlEntry> batch = new java.util.ArrayList<>();
        while (true) {
            LogSqlEntry e = pendingEntries.poll();
            if (e == null) {
                break;
            }
            batch.add(e);
        }
        if (!batch.isEmpty()) {
            writeBatch(batch);
            // T-HistoryFileRetention：写批之后在同写线程裁剪超限历史文件（不阻塞捕获热路径；与写串行无竞态）
            pruneRetainedFiles();
        }
    }

    /**
     * T-HistoryFileRetention：历史文件保留上限裁剪——目录下<b>本插件名族</b>的逻辑历史文件（按基名归并滚动块）
     * 超过上限时删最旧组。
     * 仅在写批后台线程调用（synchronized 串行）；maxFiles&lt;=0 表示全量保留不删。
     * T-PS4-10 安全门：归组前按 {@link #familyRegex}（由 fileNamePattern 推导的渲染名族，见 {@link #buildFamilyRegex}）
     * 过滤——非本插件命名的 .sql（自定义保存目录下用户自有脚本，第七轮审计数据安全发现）不计数、永不删除。
     * <b>定案口径：宁漏删不误删</b>——用户改模板后旧命名文件因固定段对不上而脱管、不再被裁剪（放任漏删）；
     * 全通配模板无字面段可锚定则整体放弃裁剪。绝不为兜底删量放宽匹配去碰用户文件。
     * 归并口径：{@code sql-history-2026-09-17.sql} 与 {@code sql-history-2026-09-17.2.sql} 是同一逻辑文件的滚动块，
     * 按基名归并为 1 组计——否则单文件自行滚动多块会误删跨天文件。超限按组内文件时间戳最旧组整组删。
     * T-PS4-10 节流：距上次裁剪扫描不足 {@link #pruneMinIntervalMs}（默认 60s）直接跳过——风暴期每非空写批
     * 不再重复全目录 listFiles+stat+排序（写线程内一个时间戳判断；lastPruneAtMs 由本方法监视器内串行读写）。
     */
    public synchronized void pruneRetainedFiles() {
        if (maxFiles <= 0) return;
        Pattern family = familyRegex;
        if (family == null) return;
        long now = clock.getAsLong();
        long last = lastPruneAtMs;
        if (last >= 0 && now - last < pruneMinIntervalMs) return;
        lastPruneAtMs = now;
        File dir = new File(saveDir);
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles(f -> f.isFile() && "sql".equalsIgnoreCase(extensionOf(f)));
        if (files == null || files.length == 0) return;
        // T-PS4-10：先按本插件名族过滤再归组——非匹配 .sql 不计数、不参与删除候选
        List<File> ours = new java.util.ArrayList<>();
        for (File f : files) {
            if (family.matcher(baseNameOf(f.getName())).matches()) {
                ours.add(f);
            }
        }
        if (ours.isEmpty()) return;
        // 按基名（去滚动序号）归并：base -> 该逻辑文件的组成文件
        Map<String, List<File>> groups = new java.util.LinkedHashMap<>();
        for (File f : ours) {
            groups.computeIfAbsent(baseNameOf(f.getName()), k -> new java.util.ArrayList<>()).add(f);
        }
        if (groups.size() <= maxFiles) return;
        // 按「组内最新文件时间戳」作组年龄下限排序（取组内最年轻文件代表该组，跨天视为新组），最旧在前的组先删
        List<Map.Entry<String, List<File>>> oldestFirst = new java.util.ArrayList<>(groups.entrySet());
        oldestFirst.sort(java.util.Comparator.comparingLong(e -> {
            long max = 0L;
            for (File f : e.getValue()) {
                max = Math.max(max, f.lastModified());
            }
            return max;
        }));
        int toDeleteCount = oldestFirst.size() - maxFiles;
        for (int i = 0; i < toDeleteCount; i++) {
            for (File f : oldestFirst.get(i).getValue()) {
                try {
                    f.delete();
                } catch (Throwable t) {
                    LOG.warn("T-HistoryFileRetention: 删除历史文件失败 " + f.getAbsolutePath(), t);
                }
            }
        }
    }

    /** File 扩展名（最后一个点之后；无点/点在首字符为空串——Kotlin File.extension 口径）。 */
    private static String extensionOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1);
    }

    /**
     * T-PS4-10：由文件名模板推导「本插件历史文件名族」匹配正则——对滚动序号剥离后的基名
     * （见 {@link #baseNameOf}）做整串匹配。模板尾部扩展名（.sql，大小写不敏感）先剥除，随后
     * $日期占位符$ 段替换为通配 {@code .*}、其余字面段原样转义。定案采用「占位段通配 + 字面段锚定」
     * 而非纯前缀 startsWith：无占位符的固定模板（如 {@code log.sql}）也能把滚动块 {@code log.2.sql}
     * （基名同为 log）纳入名族，避免滚动块脱管泄漏。
     * 安全约束（宁漏删不误删）：
     * <ul>
     * <li>整串锚定且必须含至少一个字面段——全通配模板（如 {@code $yyyy$.sql}）无法与任意用户 .sql
     * 区分，返回 null 放弃裁剪；</li>
     * <li>匹配区分大小写（本插件生成名大小写固定，更严匹配只会漏删不会误删）。</li>
     * </ul>
     */
    private Pattern buildFamilyRegex(String pattern) {
        String stem = pattern.replaceFirst("(?i)\\.sql$", "");
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        boolean hasLiteral = false;
        Matcher m = PLACEHOLDER_REGEX.matcher(stem);
        while (m.find()) {
            if (m.start() > idx) {
                sb.append(Pattern.quote(stem.substring(idx, m.start())));
                hasLiteral = true;
            }
            sb.append(".*");
            idx = m.end();
        }
        if (idx < stem.length()) {
            sb.append(Pattern.quote(stem.substring(idx)));
            hasLiteral = true;
        }
        if (!hasLiteral) return null;
        return Pattern.compile(sb.toString());
    }

    /** 文件名取滚动序号前基名：{@code xxx.sql} → {@code xxx}；{@code xxx.2.sql} → {@code xxx}；{@code xxx.yyy.sql} 视作整体（无滚动序号不拆分）。 */
    private static String baseNameOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return fileName;
        // 去掉扩展名后若形如 `xxx.N`（N 为数字且前面还有扩展名前的点），剥掉滚动序号段
        String stem = fileName.substring(0, dot);
        int secondDot = stem.lastIndexOf('.');
        if (secondDot > 0) {
            String numPart = stem.substring(secondDot + 1);
            boolean allDigits = !numPart.isEmpty();
            for (int i = 0; i < numPart.length() && allDigits; i++) {
                if (!Character.isDigit(numPart.charAt(i))) allDigits = false;
            }
            if (allDigits) {
                return stem.substring(0, secondDot);
            }
        }
        return stem;
    }

    /**
     * close 前同步排水在途队列（close 持有同一监视器，与写任务串行；不丢条）。
     * T-PS4-13：maxEntries 限单次同步排水批量（configure 路径默认 {@link #configureDrainLimit}，
     * EDT 不承担整批磁盘写）——只 poll 到上限为止，<b>超出部分留在队列不清空</b>，
     * 由后续 submit → ensureExecutor 重建的写线程经 drainAndWrite 补写；&lt;=0 表示本轮不排水。
     */
    private void drainPendingSync(int maxEntries) {
        if (maxEntries <= 0) return;
        int drained = 0;
        List<LogSqlEntry> batch = new java.util.ArrayList<>();
        while (drained < maxEntries) {
            LogSqlEntry e = pendingEntries.poll();
            if (e == null) {
                break;
            }
            batch.add(e);
            drained++;
        }
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
    }

    /** 单文件滚动阈值（测试断言默认值用）。 */
    public long getRollThresholdBytes() {
        return rollThresholdBytes;
    }

    /** T-PS4-10：两次历史文件裁剪的最小间隔（测试断言默认值用）。 */
    public long getPruneMinIntervalMs() {
        return pruneMinIntervalMs;
    }

    /** T-PS4-13：configure 路径 close 单次同步排水条数上限（测试断言默认值用）。 */
    public int getConfigureDrainLimit() {
        return configureDrainLimit;
    }

    /**
     * 配置保存参数（设置变更时调用）。
     * @param customPath 用户自定义目录（空串 = 默认 .idea 路径）
     * @param pattern 文件名模板（空串 = 默认模板）
     * @param projectBasePath 项目基路径（默认路径计算用）
     * @param maxFiles T-HistoryFileRetention：历史文件保留上限（基名归并组数，&lt;=0 = 全量保留）
     */
    public synchronized void configure(String customPath, String pattern, String projectBasePath) {
        configure(customPath, pattern, projectBasePath, 0);
    }

    public synchronized void configure(String customPath, String pattern, String projectBasePath, int maxFiles) {
        // T-PS4-13：configure 链调用线程常为 EDT（设置变更）——限批同步排水防长批磁盘写阻塞
        close(configureDrainLimit);
        // O-147：旧目录的 WrittenBlock 随重配作废——融合修订只认本实例在当前配置下写出的块
        writtenBlocks.clear();
        writtenOrder.clear();
        saveDir = resolveHistoryDir(customPath, projectBasePath);
        fileNamePattern = pattern.isBlank() ? PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN : pattern;
        this.maxFiles = maxFiles;
        // T-PS4-10：按当前模板重建本插件名族匹配正则；节流基准复位（重配后首个写批即刻获得裁剪机会）
        familyRegex = buildFamilyRegex(fileNamePattern);
        lastPruneAtMs = -1L;
        currentResolvedName = "";
        currentIndex = 1;
    }

    /**
     * 写入被淘汰的条目（同步 API——测试/直接调用；生产回调路径请走 {@link #submit}）。
     * 懒打开文件；文件名变化（跨天/模板变更）时重开并复位滚动序号，
     * 单文件超限时递增滚动序号。含 flush（调用方拿到的文件内容即时可见）。
     */
    public synchronized void write(LogSqlEntry entry) {
        prepareForEntry(entry);
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        writeEntry(w, entry);
        flushQuietly();
    }

    /** T-Perf-12：批量写出——滚动/重开状态机逐条应用，<b>整批仅一次 flush</b>。 */
    public synchronized void writeBatch(List<LogSqlEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        for (LogSqlEntry entry : entries) {
            prepareForEntry(entry);
            BufferedWriter w = writer;
            if (w == null) {
                continue;
            }
            writeEntry(w, entry);
        }
        flushQuietly();
    }

    /** 写入前状态机：首写/文件名切换/滚动阈值检查与重开（须在 synchronized 方法内调用）。 */
    private void prepareForEntry(LogSqlEntry entry) {
        String resolved = resolveFileName();
        if (writer == null || !resolved.equals(currentResolvedName)) {
            // 首次写入 / 文件名切换：重开文件，滚动序号复位
            closeWriter();
            currentResolvedName = resolved;
            currentIndex = 1;
            openWriterForCurrent();
        } else if ((currentFile != null ? currentFile.length() : 0L) >= rollThresholdBytes) {
            // 单文件超限：滚动（xxx.sql → xxx.2.sql）
            closeWriter();
            currentIndex += 1;
            openWriterForCurrent();
        }
    }

    /** 静默 flush（写失败不阻断批次）。 */
    private void flushQuietly() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (Throwable t) {
            LOG.warn("自动保存历史 SQL：flush 失败", t);
        }
    }

    /** 根据 currentResolvedName + currentIndex 打开文件。 */
    private void openWriterForCurrent() {
        File file = new File(currentFileName());
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            // APPEND 模式：多次启动后历史内容追加而非覆盖
            writer = Files.newBufferedWriter(
                    file.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
            );
            currentFile = file;
        } catch (Throwable t) {
            LOG.warn("自动保存历史 SQL：无法打开文件 " + file.getAbsolutePath(), t);
            writer = null;
            currentFile = null;
        }
    }

    /** 当前应写入的完整文件名（含目录 + 滚动序号后缀）。 */
    private String currentFileName() {
        String name = currentResolvedName;
        String fullName = name.contains(File.separator) ? name : saveDir + File.separator + name;
        if (currentIndex <= 1) {
            return fullName;
        }
        int dotIdx = fullName.lastIndexOf('.');
        if (dotIdx > 0) {
            return fullName.substring(0, dotIdx) + "." + currentIndex + fullName.substring(dotIdx);
        }
        return fullName + "." + currentIndex;
    }

    /**
     * 写入单条条目到文件。格式（F-44-2）：-- source: xxx | time: xxx | timestamp --\nSQL\n
     */
    private void writeEntry(BufferedWriter w, LogSqlEntry entry) {
        try {
            // O-147：捕获路径条目生成随机 ID + 登记 WrittenBlock（修订追加定位用）
            String id = entry.captureProvenance() != cn.song.mybatisplus.visualizer.log.CaptureProvenance.UNKNOWN
                    ? java.util.UUID.randomUUID().toString() : null;
            writeEntryText(w, entry, id);
            if (id != null && currentFile != null) {
                Object key = fileKey(currentFile);
                if (key != null) {
                    remember(entry, new WrittenBlock(currentFile, id, key,
                            SqlLogParamCodec.historyIdentity(entry)));
                }
            }
        } catch (Throwable t) {
            LOG.warn("自动保存历史 SQL：写入失败", t);
        }
    }

    private static void writeEntryText(java.io.Writer w, LogSqlEntry entry, String historyId)
            throws java.io.IOException {
            // 头部：源码位置 | 耗时 | 时间戳 | 日志原始时间（可选）
            String source;
            if (entry.sourceFile() != null) {
                source = entry.sourceLine() > 0
                        ? entry.sourceFile() + ":" + entry.sourceLine()
                        : entry.sourceFile();
            } else {
                source = "unknown";
            }
            String duration = entry.executionTimeMs() != null ? entry.executionTimeMs() + "ms" : "—";
            // T-Perf-12：SimpleDateFormat（每条新建/线程缓存）→ DateTimeFormatter 常量（不可变线程安全）
            String timestamp = TIMESTAMP_FORMATTER.format(
                    java.time.Instant.ofEpochMilli(entry.timestamp()).atZone(java.time.ZoneId.systemDefault()));
            // 日志原始时间（解析到才写，向后兼容旧文件）
            String logTimePart = entry.logTimestamp() != null ? " | logTime: " + entry.logTimestamp() : "";
            // T-AgentDialectLoop：目标库显示名（agent 通道才有，解析到才写）
            String dbPart = entry.databaseName() != null ? " | db: " + entry.databaseName() : "";
            w.write("-- source: " + source + " | time: " + duration + " | " + timestamp
                    + logTimePart + dbPart + " --\n");
            // T-HistParam F1：参数持久化——params 非空或 rawSql 含 `?`（绑定行缺失未识别条目）时，
            // 在头部下写独立 Base64 注释行（正文保持可执行 restoredSql；读端 SqlLogFileReader 探测解码）。
            // M56 T-M56-00：扩展字段（mapperStatement/errorText/requestTag/updateCount）任一在场也写
            // pd 行（载荷扩展键，仅非默认落键）——否则 agent 通道结构化信息在历史文件丢失。
            // T-McpAuditTrail T-MAT-03：**source == MCP 必须入条件**——一条无参数、无占位符、
            // 无 mapperStatement 的 MCP SELECT（explain 的常态）若不带本条件就不会写出 pd 行，
            // 来源标记随之丢失（历史读回退化为 UNKNOWN，MCP 徽标消失）——这正是本任务的存在意义。
            // R10：actualSql 附注与 SHARDINGSPHERE 来源同款入条件（无参 Logic SELECT 的 Actual
            // 对照与来源标记若不落 pd 行，历史读回即丢）。
            if (!entry.params().isEmpty() || LogEntryPreviewRecognizerKt.countPlaceholders(entry.rawSql()) > 0
                    || entry.mapperStatement() != null || entry.errorText() != null
                    || entry.requestTag() != null || entry.updateCount() != null
                    || !entry.sourceCandidates().isEmpty() || entry.stackDigest() != null
                    || entry.threadName() != null
                    || entry.actualSql() != null
                    || entry.captureProvenance() != CaptureProvenance.UNKNOWN
                    // T-DialectInferFix：方言名随 pd 载荷落盘，否则历史读回后日志预览又回落 MySQL
                    || entry.dialectName() != null
                    || entry.source() == SqlSource.MCP || entry.source() == SqlSource.SHARDINGSPHERE) {
                w.write("-- pd: ");
                SqlLogParamCodec.writeEncodedEntry(entry, w);
                w.write(" --\n");
            }
            // O-147：已写入条目的随机标识（修订追加的定位锚；修订还须 historyIdentity 对账才生效）
            if (historyId != null) {
                w.write("-- pd: o147-id: " + historyId + " --\n");
            }
            w.write(SqlLogPanelKt.withTerminator(entry.restoredSql()));
            w.write("\n\n");
            // T-Perf-12：flush 上移到批末（writeBatch）/同步 API（write）——写线程逐条 flush 磁盘已移除
    }

    // ---------- O-147：修订追加（元数据升级只追加修订注释，不重写旧字节，不追加第二条 SQL） ----------

    /** 元数据升级排入待处理队列并唤醒写线程。 */
    public void submitMerge(LogSqlEntry oldEntry, LogSqlEntry merged) {
        pendingMerges.add(new java.util.AbstractMap.SimpleEntry<>(oldEntry, merged));
        if (drainScheduled.compareAndSet(false, true)) {
            ensureExecutor().execute(this::drainAndWrite);
        }
    }

    private void awaitStore(LogSqlEntry oldEntry, LogSqlEntry merged) {
        synchronized (writtenBlocks) {
            waitingMerges.put(oldEntry, new WaitingMerge(merged, clock.getAsLong() + 60_000));
            waitingOrder.removeIf(it -> it == oldEntry);
            waitingOrder.addLast(oldEntry);
            while (waitingOrder.size() > 2048) {
                waitingMerges.remove(waitingOrder.removeFirst());
            }
        }
    }

    private void forgetWritten(LogSqlEntry entry) {
        synchronized (writtenBlocks) {
            writtenBlocks.remove(entry);
            writtenOrder.removeIf(it -> it == entry);
        }
    }

    private void remember(LogSqlEntry entry, WrittenBlock block) {
        synchronized (writtenBlocks) {
            writtenBlocks.put(entry, block);
            writtenOrder.removeIf(it -> it == entry);
            writtenOrder.addLast(entry);
            while (writtenOrder.size() > 2048) {
                writtenBlocks.remove(writtenOrder.removeFirst());
            }
            WaitingMerge waiting = waitingMerges.remove(entry);
            if (waiting != null) {
                waitingOrder.removeIf(it -> it == entry);
                if (waiting.expires() >= clock.getAsLong()) {
                    pendingMerges.add(new java.util.AbstractMap.SimpleEntry<>(entry, waiting.merged()));
                }
            }
        }
    }

    /** 文件身份键（fileKey 或创建时间；仅常规非符号链接文件）。 */
    private Object fileKey(java.io.File file) {
        try {
            java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(file.toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (a.isRegularFile() && !a.isSymbolicLink()) {
                return a.fileKey() != null ? a.fileKey() : a.creationTime();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void drainMerges() {
        long now = clock.getAsLong();
        synchronized (writtenBlocks) {
            waitingMerges.entrySet().removeIf(e -> e.getValue().expires() < now);
            waitingOrder.removeIf(it -> !waitingMerges.containsKey(it));
        }
        while (true) {
            java.util.AbstractMap.SimpleEntry<LogSqlEntry, LogSqlEntry> pair = pendingMerges.poll();
            if (pair == null) {
                break;
            }
            LogSqlEntry oldEntry = pair.getKey();
            LogSqlEntry merged = pair.getValue();
            WrittenBlock block;
            synchronized (writtenBlocks) {
                block = writtenBlocks.get(oldEntry);
            }
            if (block == null) {
                awaitStore(oldEntry, merged);
                continue;
            }
            if (oldEntry.sequenceNumber() != merged.sequenceNumber()
                    || !SqlLogParamCodec.historyIdentity(merged).equals(block.identity())) {
                continue;
            }
            try {
                // 不 CREATE：删除/替换/重配后的文件不恢复、不认领。APPEND 永不覆盖其字节；
                // 修订还须 ID + 原条目摘要匹配才在读取时生效。
                Object currentKey = fileKey(block.file());
                if (currentKey == null || !currentKey.equals(block.key())) {
                    throw new IllegalStateException("file identity mismatch");
                }
                if (currentFile == block.file() && writer != null) {
                    writer.flush();
                }
                // Windows 创建时间可能被文件系统隧道复用；必须再找到本实例写出的随机标识。
                try (java.io.BufferedReader verify = Files.newBufferedReader(block.file().toPath(),
                        StandardCharsets.UTF_8)) {
                    if (!containsExactLine(verify, "-- pd: o147-id: " + block.id() + " --")) {
                        throw new IllegalStateException("id line not found");
                    }
                }
                String payload = revisionPayload(merged);
                String line = "\n-- pd: o147-revision: " + block.id() + " " + block.identity() + " " + payload + " --\n";
                java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                        block.file().toPath(),
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.APPEND,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                try (java.nio.channels.FileLock lock = channel.lock()) {
                    Object recheck = fileKey(block.file());
                    if (recheck == null || !recheck.equals(block.key())) {
                        throw new IllegalStateException("file identity mismatch (post-lock)");
                    }
                    java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                }
                forgetWritten(oldEntry);
                remember(merged, block);
            } catch (Exception e) {
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(e)) {
                    // fatal 透传：catch 类型是 Exception（Error 不会被捕获）——RuntimeException 子类
                    // 原样重抛（PCE 等），其余包装为 RuntimeException
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException(e);
                }
                forgetWritten(oldEntry);
                LOG.warn("自动保存历史 SQL：融合修订未追加，保留原历史", e);
            }
        }
    }

    /** 固定内存逐字符比较；不论外部行多长均不累积，CRLF/LF/EOF 都支持。 */
    static boolean containsExactLine(java.io.Reader reader, String expected) {
        int index = 0;
        boolean matches = true;
        boolean cr = false;
        while (true) {
            int c;
            try {
                c = reader.read();
            } catch (java.io.IOException e) {
                return false;
            }
            if (c == -1 || c == '\n') {
                if (matches && index == expected.length()) {
                    return true;
                }
                if (c == -1) {
                    return false;
                }
                index = 0;
                matches = true;
                cr = false;
            } else if (matches) {
                if (index < expected.length() && c == expected.charAt(index) && !cr) {
                    index++;
                } else if (index == expected.length() && c == '\r' && !cr) {
                    cr = true;
                } else {
                    matches = false;
                }
            }
        }
    }

    static class RevisionLimitExceeded extends java.io.IOException {
        RevisionLimitExceeded() {
            super("history revision exceeds byte budget");
        }
    }

    /** Base64 最终输出硬封顶；超额在流写时抛出，绝不先生成完整 payload。 */
    static class RevisionOutput extends java.io.OutputStream {
        private final int limit;
        private final java.io.ByteArrayOutputStream data;

        RevisionOutput(int limit) {
            this.limit = limit;
            this.data = new java.io.ByteArrayOutputStream(Math.min(limit, 1024));
        }

        int size() {
            return data.size();
        }

        @Override
        public void write(int b) throws java.io.IOException {
            if (size() >= limit) {
                throw new RevisionLimitExceeded();
            }
            data.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            if (len > limit - size()) {
                throw new RevisionLimitExceeded();
            }
            data.write(b, off, len);
        }

        String text() {
            return data.toString(java.nio.charset.StandardCharsets.US_ASCII);
        }
    }

    static String revisionPayload(LogSqlEntry entry) throws java.io.IOException {
        RevisionOutput output = new RevisionOutput(SqlLogFileReader.MAX_REVISION_PAYLOAD);
        writeRevision(entry, output);
        return output.text();
    }

    static void writeRevision(LogSqlEntry entry, java.io.OutputStream output) throws java.io.IOException {
        java.io.OutputStream encoded = java.util.Base64.getEncoder().wrap(output);
        java.io.Writer w = new SqlLogParamCodec.ChunkedWriter(
                new java.io.OutputStreamWriter(encoded, java.nio.charset.StandardCharsets.UTF_8));
        // master 无 try/catch（Kotlin 无受检异常概念）——RevisionLimitExceeded 等异常原样透传；
        // Java 侧以 throws IOException 承载同一语义（测试按 RevisionLimitExceeded 捕获，
        // drainMerges 侧 catch Exception 兜底不变），不得包装成 RuntimeException 改变异常形态
        writeEntryText(w, entry, null);
        w.close();
    }

    /**
     * 解析文件名模板（替换 $日期格式$ 占位符为 SimpleDateFormat 格式化结果）。
     * 若占位符内容不含日期模式字符（如 $unknown$），则原样保留。
     * Windows 文件名不允许 :，自动替换为 -。
     */
    public String resolveFileName() {
        return resolveFileName(clock.getAsLong());
    }

    /** 指定时刻解析文件名模板（T-AutoSaverSOE 测试注入用）。 */
    public String resolveFileName(long now) {
        Matcher m = PLACEHOLDER_REGEX.matcher(fileNamePattern);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String formatStr = m.group(1);
            String formatted;
            try {
                formatted = formatter(formatStr).format(new Date(now));
            } catch (Exception e) {
                formatted = m.group(0);
            }
            if (formatted.equals(formatStr)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(formatted));
            }
        }
        m.appendTail(sb);
        return sb.toString().replace(':', '-');
    }

    /** SimpleDateFormat 非线程安全，按线程+模板缓存，避免每条日志重建。 */
    private final ThreadLocal<Map<String, SimpleDateFormat>> formatterCache =
            ThreadLocal.withInitial(HashMap::new);

    private SimpleDateFormat formatter(String formatStr) {
        return formatterCache.get().computeIfAbsent(formatStr,
                f -> new SimpleDateFormat(f, Locale.ENGLISH));
    }

    /** 当前打开的保存文件（测试访问 + 状态展示用）。 */
    public File currentFile() {
        return currentFile;
    }

    /** T-PS4-13 测试访问器：在途队列余量（限批排水后未落盘条目数）。 */
    public int pendingCount() {
        return pendingEntries.size();
    }

    /**
     * 关闭文件句柄（manager dispose 或路径变更时调用）。
     * T-Perf-12：先同步排水在途队列（不丢条），再停写线程、关句柄；
     * 后续 configure/submit 可重建执行器。
     * T-PS4-13：maxDrainEntries 限单次同步排水批量——configure 路径传 {@link #configureDrainLimit}
     * （EDT 限批），超出上限的条目<b>留在队列不清空</b>（旧实现此处 clear 会把余量丢掉），
     * 后续 submit → ensureExecutor 重建写线程时由 drainAndWrite 一并补写；
     * <b>dispose 路径（SqlLogManager.dispose → 无参 close）保持全量排水语义不变</b>（终态不丢条）。
     */
    public synchronized void close() {
        close(Integer.MAX_VALUE);
    }

    public synchronized void close(int maxDrainEntries) {
        drainPendingSync(maxDrainEntries);
        // O-147：close 前同步排空待处理融合修订（已落盘条目的元数据升级不丢）；
        // 随后清空等待表——close 后不再有写线程消费 waitingMerges，残留即泄漏（master close 同序）
        drainMerges();
        waitingMerges.clear();
        waitingOrder.clear();
        java.util.concurrent.ExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
        executor = null;
        drainScheduled.set(false);
        // T-PS4-13：不再 clear——限批排水后余量留在队列待补写；全量排水（dispose）后队列本已空
        closeWriter();
    }

    /** 仅关闭当前 writer（滚动/切换文件用；不动队列与执行器）。 */
    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Throwable t) {
                LOG.debug("自动保存历史 SQL：关闭文件异常", t);
            }
        }
        writer = null;
        currentFile = null;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogAutoSaver.class);

    /** T-Perf-12：条目时间戳格式（DateTimeFormatter 不可变线程安全，替代逐条 SimpleDateFormat）。 */
    private static final java.time.format.DateTimeFormatter TIMESTAMP_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH);

    /**
     * 项目内插件数据根目录名（F-44-4）：随插件更名「SQL Visualizer」由旧名
     * `mybatis-plus-sql-visualizer` 改为 `sql-visualizer`，**不考虑向前兼容**
     * （旧 `.idea/mybatis-plus-sql-visualizer/` 数据不再读取，历史 SQL 与新捕获从新目录起算）。
     * 其他模块（HistorySqlLogPanel / DataSourceSelector / SqlPreviewPanel）统一引用本常量。
     */
    public static final String DATA_DIR_NAME = "sql-visualizer";

    private static final String DEFAULT_DIR = DATA_DIR_NAME + File.separator + "sql-history";

    /**
     * 解析历史 SQL 目录（写端 {@link #configure} 与读端 HistorySqlLogPanel 的<b>唯一来源</b>）：
     * 自定义目录非空则优先（去首尾空白）；否则 {@code <项目根>/.idea/sql-visualizer/sql-history}；
     * 无项目根（Default Project 等非文件型项目）回落 {@code user.home} 下的同一相对路径。
     *
     * <p>T-HistoryTabAlways：此前读端自己拼一份同形路径且只认默认目录——用户把「保存目录」改成
     * 自定义路径后，自动保存写进去的历史文件在历史页里看不到（读写两端各拼一份、只有写端认自定义
     * 目录）。收口到本函数后两端恒同源。
     */
    public static String resolveHistoryDir(String customPath, String projectBasePath) {
        if (customPath != null && !customPath.isBlank()) {
            return customPath.trim();
        }
        String base = projectBasePath != null ? projectBasePath : System.getProperty("user.home");
        return base + File.separator + ".idea" + File.separator + DEFAULT_DIR;
    }

    /** 匹配 $变量$ 占位符。 */
    private static final Pattern PLACEHOLDER_REGEX = Pattern.compile("\\$([^$]+)\\$");

    /** T-PS4-10：两次历史文件裁剪扫描的最小间隔——风暴期写批密集时避免重复全目录扫描。 */
    public static final long DEFAULT_PRUNE_MIN_INTERVAL_MS = 60_000L;

    /** T-PS4-13：configure 路径 close 单次同步排水条数上限（EDT 不承担超长批磁盘写）。 */
    public static final int DEFAULT_CONFIGURE_DRAIN_LIMIT = 500;
}
