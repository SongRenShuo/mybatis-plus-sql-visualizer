package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import cn.song.mybatisplus.visualizer.log.withTerminator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动保存历史 SQL 到文件（F-43）：[SqlLogManager] 装配为 buffer 的 onEvicted 回调。
 *
 * 文件规则：
 *  - 默认路径：.idea/sql-visualizer/sql-history/（F-44-4：随插件更名由旧名
 *    mybatis-plus-sql-visualizer 改为 sql-visualizer，**不考虑向前兼容**——旧目录数据不再读取）
 *  - 文件名模板：sql-history-$yyyy-MM-dd$.sql（支持 $yyyy$ $MM$ $dd$ $HH$ $mm$ $ss$ 占位符）
 *  - 文件滚动：单文件 ≥100MB 时滚动（.2.sql、.3.sql…）
 *  - 格式：`-- 时间戳\nSQL\n\n`（纯文本，可直接在数据库工具中打开）
 *
 * 生命周期跟随 [SqlLogManager]：manager dispose 时 [close] 释放文件句柄。
 */
class SqlLogAutoSaver(
    /** 时钟（测试注入用），默认系统时钟。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 单文件滚动阈值（默认 100MB），测试注入小值用。 */
    internal val rollThresholdBytes: Long = 100L * 1024 * 1024,
    /**
     * T-PS4-10：两次历史文件裁剪的最小间隔（默认 60s）——风暴期每非空写批不再重复全目录
     * listFiles+stat+排序。测试注入小值/可变时钟用。
     */
    internal val pruneMinIntervalMs: Long = DEFAULT_PRUNE_MIN_INTERVAL_MS,
    /**
     * T-PS4-13：configure 路径 close 时单次同步排水条数上限（默认 500）——调用线程常为 EDT，
     * 风暴在途积压大时限批排水，超出部分留在队列由后续 submit 重建的写线程补写（不丢条）。
     * 测试注入小值用；dispose 路径走无参 close 全量排水不受此限。
     */
    internal val configureDrainLimit: Int = DEFAULT_CONFIGURE_DRAIN_LIMIT,
) {

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var saveDir: String = ""
    private var fileNamePattern: String = ""
    private var currentResolvedName: String = ""  // 当前解析后的文件名（不含滚动序号）
    private var currentIndex: Int = 1  // 当前滚动序号（1=无后缀）

    /** T-HistoryFileRetention：历史文件保留上限（按基名归并的组数）。<=0 = 全量保留。 */
    @Volatile
    private var maxFiles: Int = 0

    /**
     * T-PS4-10：本插件历史文件名族匹配正则（由 [fileNamePattern] 推导，configure 时重建）。
     * null = 无法安全认领（configure 未跑过 / 模板无字面段可锚定）——放弃裁剪，宁漏删不误删。
     */
    @Volatile
    private var familyRegex: Regex? = null

    /** T-PS4-10：上次实际执行裁剪扫描的时刻（节流基准；仅 [pruneRetainedFiles] 的监视器内读写）。-1 = 从未扫描。 */
    private var lastPruneAtMs: Long = -1L

    // ---------- T-Perf-12：异步批量写（淘汰条目投递无锁队列 + 专用单线程执行器） ----------

    /** 在途淘汰条目队列（控制台输出线程投递，写线程消费）。 */
    private val pendingEntries = java.util.concurrent.ConcurrentLinkedQueue<LogSqlEntry>()

    // O-147：最多 2048 条身份索引，只认本写入器写出的实例，不靠 SQL/会话序号猜测归属。
    private data class WrittenBlock(val file: File, val id: String, val key: Any, val identity: String)
    private val writtenBlocks = java.util.IdentityHashMap<LogSqlEntry, WrittenBlock>()
    private val pendingMerges = java.util.concurrent.ConcurrentLinkedQueue<Pair<LogSqlEntry, LogSqlEntry>>()
    private val writtenOrder = java.util.ArrayDeque<LogSqlEntry>()
    private data class WaitingMerge(val merged: LogSqlEntry, val expires: Long)
    private val waitingMerges = java.util.IdentityHashMap<LogSqlEntry, WaitingMerge>()
    private val waitingOrder = java.util.ArrayDeque<LogSqlEntry>()

    private fun awaitStore(old: LogSqlEntry, merged: LogSqlEntry) {
        waitingMerges[old] = WaitingMerge(merged, clock() + 60_000)
        waitingOrder.removeIf { it === old }
        waitingOrder.addLast(old)
        while (waitingOrder.size > 2048) waitingMerges.remove(waitingOrder.removeFirst())
    }

    private fun forgetWritten(entry: LogSqlEntry) {
        writtenBlocks.remove(entry)
        writtenOrder.removeIf { it === entry }
    }

    /** 元数据升级只追加修订注释，不重写旧字节，也不追加第二条可执行 SQL。 */
    fun submitMerge(old: LogSqlEntry, merged: LogSqlEntry) {
        pendingMerges.add(old to merged)
        if (drainScheduled.compareAndSet(false, true)) ensureExecutor().execute { drainAndWrite() }
    }

    private fun fileKey(file: File): Any? {
        val a = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        return (a.fileKey() ?: a.creationTime()).takeIf { a.isRegularFile && !a.isSymbolicLink }
    }

    private fun remember(entry: LogSqlEntry, block: WrittenBlock) {
        writtenBlocks[entry] = block
        writtenOrder.removeIf { it === entry }
        writtenOrder.addLast(entry)
        while (writtenOrder.size > 2048) writtenBlocks.remove(writtenOrder.removeFirst())
        waitingMerges.remove(entry)?.let { waiting ->
            waitingOrder.removeIf { it === entry }
            if (waiting.expires >= clock()) pendingMerges.add(entry to waiting.merged)
        }
    }

    private fun drainMerges() {
        val now = clock()
        waitingMerges.entries.removeIf { it.value.expires < now }
        waitingOrder.removeIf { !waitingMerges.containsKey(it) }
        while (true) {
            val (old, merged) = pendingMerges.poll() ?: break
            val block = writtenBlocks[old]
            if (block == null) {
                awaitStore(old, merged)
                continue
            }
            if (old.sequenceNumber != merged.sequenceNumber || SqlLogParamCodec.historyIdentity(merged) != block.identity) continue
            try {
                // 不 CREATE：删除/替换/重配后的文件不恢复、不认领。非协作外写无法CAS，
                // 但 APPEND 永不覆盖其字节；修订还须 ID + 原条目摘要匹配才在读取时生效。
                check(fileKey(block.file) == block.key)
                if (currentFile == block.file) writer?.flush()
                // Windows 创建时间可能被文件系统隧道复用；必须再找到本实例写出的随机标识。
                check(block.file.bufferedReader().use { containsExactLine(it, "-- pd: o147-id: ${block.id} --") })
                val payload = revisionPayload(merged)
                val line = "\n-- pd: o147-revision: ${block.id} ${block.identity} $payload --\n"
                java.nio.channels.FileChannel.open(block.file.toPath(), StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
                    channel.lock().use {
                        check(fileKey(block.file) == block.key)
                        val bytes = java.nio.ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                        while (bytes.hasRemaining()) channel.write(bytes)
                    }
                }
                forgetWritten(old)
                remember(merged, block)
            } catch (_: Exception) {
                forgetWritten(old)
                LOG.warn("自动保存历史 SQL：融合修订未追加，保留原历史")
            }
        }
    }

    /** 固定内存逐字符比较；不论外部行多长均不累积，CRLF/LF/EOF 都支持。 */
    internal fun containsExactLine(reader: java.io.Reader, expected: String): Boolean {
        var index = 0
        var matches = true
        var cr = false
        while (true) {
            val c = reader.read()
            if (c == -1 || c == '\n'.code) {
                if (matches && index == expected.length) return true
                if (c == -1) return false
                index = 0; matches = true; cr = false
            } else if (matches) {
                if (index < expected.length && c == expected[index].code && !cr) index++
                else if (index == expected.length && c == '\r'.code && !cr) cr = true
                else matches = false
            }
        }
    }

    internal class RevisionLimitExceeded : java.io.IOException("history revision exceeds byte budget")

    /** Base64最终输出硬封顶；超额在流写时抛出，绝不先生成完整payload。 */
    internal class RevisionOutput(private val limit: Int) : java.io.OutputStream() {
        private val data = java.io.ByteArrayOutputStream(minOf(limit, 1024))
        val size: Int get() = data.size()
        override fun write(b: Int) {
            if (size >= limit) throw RevisionLimitExceeded()
            data.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > limit - size) throw RevisionLimitExceeded()
            data.write(b, off, len)
        }
        fun text(): String = data.toString("US-ASCII")
    }

    internal fun revisionPayload(entry: LogSqlEntry): String {
        val output = RevisionOutput(SqlLogFileReader.MAX_REVISION_PAYLOAD)
        writeRevision(entry, output)
        return output.text()
    }

    internal fun writeRevision(entry: LogSqlEntry, output: java.io.OutputStream) {
        val encoded = java.util.Base64.getEncoder().wrap(output)
        val w = SqlLogParamCodec.ChunkedWriter(java.io.OutputStreamWriter(encoded, Charsets.UTF_8))
        writeEntryText(w, entry, null)
        w.close()
    }

    /** 本轮已调度标志（幂等调度——风暴期 N 条投递 = 1 个写任务）。 */
    private val drainScheduled = java.util.concurrent.atomic.AtomicBoolean()

    /** 专用写线程（懒创建；close 后下次 submit 重建）。 */
    @Volatile
    private var executor: java.util.concurrent.ExecutorService? = null

    /**
     * T-Perf-12：异步写入入口（buffer.onEvicted 回调用）——投递后由专用后台线程批量写出。
     * 此前回调在控制台输出热路径同步执行「检查文件长度 + 写两行 + flush 磁盘」，风暴期数百次/秒，
     * 且 EDT 刷新列表 snapshot() 抢同一把锁互相阻塞。
     */
    fun submit(entry: LogSqlEntry) {
        pendingEntries.add(entry)
        if (drainScheduled.compareAndSet(false, true)) {
            ensureExecutor().execute { drainAndWrite() }
        }
    }

    private fun ensureExecutor(): java.util.concurrent.ExecutorService {
        executor?.takeIf { !it.isShutdown }?.let { return it }
        val new = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mpsv-sql-history-autosave").apply { isDaemon = true }
        }
        executor = new
        return new
    }

    /**
     * 写任务：整批一次 flush；写期间新到条目重新调度（不丢条）。
     * T-SqlHistoryFull：@Synchronized 与 [close] 在同一监视器串行——此前无锁 drainAndWrite 与 close
     * 并发时，后台已 poll 未写完的批次会在 close 关闭 writer 后被 writeEntry 的 currentFile==null 分支
     * 跳过丢条（2 万条风暴压测丢 9 千，dispose 与风暴写并发的既有缺陷，T-SqlHistoryFull 全量化放大触发面）。
     * writeBatch 本身 @Synchronized 重入，此处持锁串行即可保证「先写完全部再关闭」或「先清空写完全部」，两序都不丢。
     */
    @Synchronized
    private fun drainAndWrite() {
        drainScheduled.set(false)
        val batch = mutableListOf<LogSqlEntry>()
        while (true) {
            val e = pendingEntries.poll() ?: break
            batch.add(e)
        }
        if (batch.isNotEmpty()) {
            writeBatch(batch)
            // T-HistoryFileRetention：写批之后在同写线程裁剪超限历史文件（不阻塞捕获热路径；与写串行无竞态）
            pruneRetainedFiles()
        }
        drainMerges()
    }

    /** T-HistoryFileRetention：历史文件保留上限裁剪——目录下**本插件名族**的逻辑历史文件（按基名归并滚动块）
     *  超过上限时删最旧组。
     *  仅在写批后台线程调用（@Synchronized 串行）；maxFiles<=0 表示全量保留不删。
     *  T-PS4-10 安全门：归组前按 [familyRegex]（由 fileNamePattern 推导的渲染名族，见 [buildFamilyRegex]）
     *  过滤——非本插件命名的 .sql（自定义保存目录下用户自有脚本，第七轮审计数据安全发现）不计数、永不删除。
     *  **定案口径：宁漏删不误删**——用户改模板后旧命名文件因固定段对不上而脱管、不再被裁剪（放任漏删）；
     *  全通配模板无字面段可锚定则整体放弃裁剪。绝不为兜底删量放宽匹配去碰用户文件。
     *  归并口径：`sql-history-2026-09-17.sql` 与 `sql-history-2026-09-17.2.sql` 是同一逻辑文件的滚动块，
     *  按基名归并为 1 组计——否则单文件自行滚动多块会误删跨天文件。超限按组内文件时间戳最旧组整组删。
     *  T-PS4-10 节流：距上次裁剪扫描不足 [pruneMinIntervalMs]（默认 60s）直接跳过——风暴期每非空写批
     *  不再重复全目录 listFiles+stat+排序（写线程内一个时间戳判断；lastPruneAtMs 由本方法监视器内串行读写）。
     */
    @Synchronized
    internal fun pruneRetainedFiles() {
        if (maxFiles <= 0) return
        val family = familyRegex ?: return
        val now = clock()
        val last = lastPruneAtMs
        if (last >= 0 && now - last < pruneMinIntervalMs) return
        lastPruneAtMs = now
        val dir = File(saveDir)
        if (!dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("sql", ignoreCase = true) } ?: return
        if (files.isEmpty()) return
        // T-PS4-10：先按本插件名族过滤再归组——非匹配 .sql 不计数、不参与删除候选
        val ours = files.filter { family.matches(baseNameOf(it.name)) }
        if (ours.isEmpty()) return
        // 按基名（去滚动序号）归并：base -> 该逻辑文件的组成文件
        val groups = ours.groupBy { baseNameOf(it.name) }
        if (groups.size <= maxFiles) return
        // 按「组内最新文件时间戳」作组年龄下限排序（取组内最年轻文件代表该组，跨天视为新组），最旧在前的组先删
        val oldestFirst = groups.entries.sortedBy { (_, g) -> g.maxOfOrNull { it.lastModified() } ?: 0L }
        val toDelete = oldestFirst.take(oldestFirst.size - maxFiles)
        for ((_, g) in toDelete) {
            g.forEach { f ->
                runCatching { f.delete() }.onFailure {
                    LOG.warn("T-HistoryFileRetention: 删除历史文件失败 ${f.absolutePath}", it)
                }
            }
        }
    }

    /**
     * T-PS4-10：由文件名模板推导「本插件历史文件名族」匹配正则——对滚动序号剥离后的基名
     * （见 [baseNameOf]）做整串匹配。模板尾部扩展名（.sql，大小写不敏感）先剥除，随后
     * $日期占位符$ 段替换为通配 `.*`、其余字面段原样转义。定案采用「占位段通配 + 字面段锚定」
     * 而非纯前缀 startsWith：无占位符的固定模板（如 `log.sql`）也能把滚动块 `log.2.sql`
     * （基名同为 log）纳入名族，避免滚动块脱管泄漏。
     * 安全约束（宁漏删不误删）：
     *  - 整串锚定且必须含至少一个字面段——全通配模板（如 `$yyyy$.sql`）无法与任意用户 .sql
     *    区分，返回 null 放弃裁剪；
     *  - 匹配区分大小写（本插件生成名大小写固定，更严匹配只会漏删不会误删）。
     */
    private fun buildFamilyRegex(pattern: String): Regex? {
        val stem = pattern.replace(Regex("""\.sql$""", RegexOption.IGNORE_CASE), "")
        val sb = StringBuilder()
        var idx = 0
        var hasLiteral = false
        for (m in PLACEHOLDER_REGEX.findAll(stem)) {
            if (m.range.first > idx) {
                sb.append(Regex.escape(stem.substring(idx, m.range.first)))
                hasLiteral = true
            }
            sb.append(".*")
            idx = m.range.last + 1
        }
        if (idx < stem.length) {
            sb.append(Regex.escape(stem.substring(idx)))
            hasLiteral = true
        }
        if (!hasLiteral) return null
        return Regex(sb.toString())
    }

    /** 文件名取滚动序号前基名：`xxx.sql` → `xxx`；`xxx.2.sql` → `xxx`；`xxx.yyy.sql` 视作整体（无滚动序号不拆分）。 */
    private fun baseNameOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return fileName
        // 去掉扩展名后若形如 `xxx.N`（N 为数字且前面还有扩展名前的点），剥掉滚动序号段
        val stem = fileName.substring(0, dot)
        val secondDot = stem.lastIndexOf('.')
        if (secondDot > 0) {
            val numPart = stem.substring(secondDot + 1)
            if (numPart.isNotEmpty() && numPart.all { it.isDigit() }) {
                return stem.substring(0, secondDot)
            }
        }
        return stem
    }

    /**
     * close 前同步排水在途队列（close 持有同一监视器，与写任务串行；不丢条）。
     * T-PS4-13：maxEntries 限单次同步排水批量（configure 路径默认 [configureDrainLimit]，
     * EDT 不承担整批磁盘写）——只 poll 到上限为止，**超出部分留在队列不清空**，
     * 由后续 submit → ensureExecutor 重建的写线程经 drainAndWrite 补写；<=0 表示本轮不排水。
     */
    private fun drainPendingSync(maxEntries: Int) {
        if (maxEntries <= 0) return
        var drained = 0
        val batch = mutableListOf<LogSqlEntry>()
        while (drained < maxEntries) {
            val e = pendingEntries.poll() ?: break
            batch.add(e)
            drained++
        }
        if (batch.isNotEmpty()) writeBatch(batch)
    }

    /**
     * 配置保存参数（设置变更时调用）。
     * @param customPath 用户自定义目录（空串 = 默认 .idea 路径）
     * @param pattern 文件名模板（空串 = 默认模板）
     * @param projectBasePath 项目基路径（默认路径计算用）
     * @param maxFiles T-HistoryFileRetention：历史文件保留上限（基名归并组数，<=0 = 全量保留）
     */
    @Synchronized
    fun configure(customPath: String, pattern: String, projectBasePath: String?, maxFiles: Int = 0) {
        // T-PS4-13：configure 链调用线程常为 EDT（设置变更）——限批同步排水防长批磁盘写阻塞
        close(configureDrainLimit)
        writtenBlocks.clear()
        writtenOrder.clear()
        saveDir = resolveHistoryDir(customPath, projectBasePath)
        fileNamePattern = pattern.ifBlank { PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
        this.maxFiles = maxFiles
        // T-PS4-10：按当前模板重建本插件名族匹配正则；节流基准复位（重配后首个写批即刻获得裁剪机会）
        familyRegex = buildFamilyRegex(fileNamePattern)
        lastPruneAtMs = -1L
        currentResolvedName = ""
        currentIndex = 1
    }

    /**
     * 写入被淘汰的条目（同步 API——测试/直接调用；生产回调路径请走 [submit]）。
     * 懒打开文件；文件名变化（跨天/模板变更）时重开并复位滚动序号，
     * 单文件超限时递增滚动序号。含 flush（调用方拿到的文件内容即时可见）。
     */
    @Synchronized
    fun write(entry: LogSqlEntry) {
        prepareForEntry(entry)
        val w = writer ?: return
        writeEntry(w, entry)
        flushQuietly()
    }

    /** T-Perf-12：批量写出——滚动/重开状态机逐条应用，**整批仅一次 flush**。 */
    @Synchronized
    internal fun writeBatch(entries: List<LogSqlEntry>) {
        if (entries.isEmpty()) return
        for (entry in entries) {
            prepareForEntry(entry)
            val w = writer ?: continue
            writeEntry(w, entry)
        }
        flushQuietly()
    }

    /** 写入前状态机：首写/文件名切换/滚动阈值检查与重开（须在 @Synchronized 内调用）。 */
    private fun prepareForEntry(entry: LogSqlEntry) {
        val resolved = resolveFileName()
        if (writer == null || resolved != currentResolvedName) {
            // 首次写入 / 文件名切换：重开文件，滚动序号复位
            closeWriter()
            currentResolvedName = resolved
            currentIndex = 1
            openWriterForCurrent()
        } else if ((currentFile?.length() ?: 0L) >= rollThresholdBytes) {
            // 单文件超限：滚动（xxx.sql → xxx.2.sql）
            closeWriter()
            currentIndex += 1
            openWriterForCurrent()
        }
    }

    /** 静默 flush（写失败不阻断批次）。 */
    private fun flushQuietly() {
        runCatching {
            writer?.flush()
        }.onFailure {
            LOG.warn("自动保存历史 SQL：flush 失败", it)
        }
    }

    /** 根据 currentResolvedName + currentIndex 打开文件。 */
    private fun openWriterForCurrent() {
        val file = File(currentFileName())
        runCatching {
            file.parentFile?.mkdirs()
            // APPEND 模式：多次启动后历史内容追加而非覆盖
            writer = Files.newBufferedWriter(
                file.toPath(), Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            currentFile = file
        }.onFailure {
            LOG.warn("自动保存历史 SQL：无法打开文件 ${file.absolutePath}", it)
            writer = null
            currentFile = null
        }
    }

    /** 当前应写入的完整文件名（含目录 + 滚动序号后缀）。 */
    private fun currentFileName(): String {
        val name = currentResolvedName
        val fullName = if (name.contains(File.separator)) name else "$saveDir${File.separator}$name"
        if (currentIndex <= 1) return fullName
        // 在扩展名前插入滚动序号：xxx.sql → xxx.2.sql
        val dotIdx = fullName.lastIndexOf('.')
        return if (dotIdx > 0) {
            "${fullName.substring(0, dotIdx)}.$currentIndex${fullName.substring(dotIdx)}"
        } else {
            "$fullName.$currentIndex"
        }
    }

    /**
     * 写入单条条目到文件。
     * 格式（F-44-2）：-- source: xxx | time: xxx | timestamp --\nSQL\n
     *
     * 旧版格式（F-43）：-- timestamp\nSQL\n（向后兼容，Reader 同时支持）
     * 旧版 F-44 一度使用 `--- source: ... ---`（三个 `-`），本方法回归为标准 SQL 单行注释 `--`
     * （更严格 SQL 方言对 3 个 `-` 整行注释兼容性差，且与历史 F-43 注释一致便于人眼识读）。
     * Reader 正则兼容 2/3 个 `-` 起结，已存在的旧 3 `-` 文件仍可读。
     */
    private fun writeEntry(w: BufferedWriter, entry: LogSqlEntry) {
        runCatching {
            val id = if (entry.captureProvenance != CaptureProvenance.UNKNOWN) java.util.UUID.randomUUID().toString() else null
            writeEntryText(w, entry, id)
            if (id != null) currentFile?.let { file ->
                fileKey(file)?.let { key -> remember(entry, WrittenBlock(file, id, key, SqlLogParamCodec.historyIdentity(entry))) }
            }
        }.onFailure { LOG.warn("自动保存历史 SQL：写入失败", it) }
    }

    private fun writeEntryText(w: java.io.Writer, entry: LogSqlEntry, historyId: String?) {
            // 头部：源码位置 | 耗时 | 时间戳 | 日志原始时间（可选）
            w.write("-- source: ")
            w.write(entry.sourceFile ?: "unknown")
            if (entry.sourceFile != null && entry.sourceLine > 0) w.write(":${entry.sourceLine}")
            val duration = entry.executionTimeMs?.let { "${it}ms" } ?: "—"
            // T-Perf-12：SimpleDateFormat（每条新建/线程缓存）→ DateTimeFormatter 常量（不可变线程安全）
            val timestamp = TIMESTAMP_FORMATTER.format(
                java.time.Instant.ofEpochMilli(entry.timestamp).atZone(java.time.ZoneId.systemDefault()),
            )
            // 日志原始时间（解析到才写，向后兼容旧文件）
            val logTimePart = entry.logTimestamp?.let { " | logTime: $it" } ?: ""
            // T-AgentDialectLoop：目标库显示名（agent 通道才有，解析到才写）
            w.write(" | time: $duration | $timestamp$logTimePart")
            entry.databaseName?.let { w.write(" | db: "); w.write(it) }
            w.write(" --\n")
            // T-HistParam F1：参数持久化——params 非空或 rawSql 含 `?`（绑定行缺失未识别条目）时，
            // 在头部下写独立 Base64 注释行（正文保持可执行 restoredSql；读端 SqlLogFileReader 探测解码）。
            // M56 T-M56-00：扩展字段（mapperStatement/errorText/requestTag/updateCount）任一在场也写
            // pd 行（载荷扩展键，仅非默认落键）——否则 agent 通道结构化信息在历史文件丢失。
            // T-McpAuditTrail T-MAT-03：**source == MCP 必须入条件**——一条无参数、无占位符、
            // 无 mapperStatement 的 MCP SELECT（explain 的常态）若不带本条件就不会写出 pd 行，
            // 来源标记随之丢失（历史读回退化为 UNKNOWN，MCP 徽标消失）——这正是本任务的存在意义。
            // R10：actualSql 附注与 SHARDINGSPHERE 来源同款入条件（无参 Logic SELECT 的 Actual
            // 对照与来源标记若不落 pd 行，历史读回即丢）。
            if (entry.params.isNotEmpty() || countPlaceholders(entry.rawSql) > 0 ||
                entry.mapperStatement != null || entry.errorText != null ||
                entry.requestTag != null || entry.updateCount != null ||
                entry.sourceCandidates.isNotEmpty() || entry.stackDigest != null ||
                entry.threadName != null ||
                entry.actualSql != null || entry.captureProvenance != CaptureProvenance.UNKNOWN ||
                entry.source == SqlSource.MCP || entry.source == SqlSource.SHARDINGSPHERE
            ) {
                w.write("-- pd: ")
                SqlLogParamCodec.writeEncodedEntry(entry, w)
                w.write(" --\n")
            }
            historyId?.let { w.write("-- pd: o147-id: $it --\n") }
            w.write(entry.restoredSql)
            if (entry.restoredSql.isNotEmpty() && !entry.restoredSql.endsWith(";")) w.write(";")
            w.write("\n\n")
    }

    /**
     * 解析文件名模板（替换 $日期格式$ 占位符为 SimpleDateFormat 格式化结果）。
     * 若占位符内容不含日期模式字符（如 $unknown$），则原样保留。
     * Windows 文件名不允许 :，自动替换为 -（macOS 也兼容）。
     */
    fun resolveFileName(): String = resolveFileName(clock())

    internal fun resolveFileName(now: Long): String {
        return fileNamePattern.replace(PLACEHOLDER_REGEX) { mr ->
            val formatStr = mr.groupValues[1]
            try {
                val formatted = formatter(formatStr).format(Date(now))
                // 若格式化结果与输入一致（说明无日期字符），原样保留
                if (formatted == formatStr) mr.value else formatted
            } catch (_: Exception) {
                // 非法日期格式原样保留
                mr.value
            }
        }.replace(':', '-')
    }

    /** SimpleDateFormat 非线程安全，按线程+模板缓存，避免每条日志重建。 */
    private val formatterCache =
        ThreadLocal.withInitial<HashMap<String, SimpleDateFormat>> { HashMap() }

    private fun formatter(formatStr: String): SimpleDateFormat =
        formatterCache.get().getOrPut(formatStr) { SimpleDateFormat(formatStr, Locale.ENGLISH) }

    /** 当前打开的保存文件（测试访问 + 状态展示用）。 */
    fun currentFile(): File? = currentFile

    /** T-PS4-13 测试访问器：在途队列余量（限批排水后未落盘条目数）。 */
    internal fun pendingCount(): Int = pendingEntries.size

    /**
     * 关闭文件句柄（manager dispose 或路径变更时调用）。
     * T-Perf-12：先同步排水在途队列（不丢条），再停写线程、关句柄；
     * 后续 configure/submit 可重建执行器。
     * T-PS4-13：[maxDrainEntries] 限单次同步排水批量——configure 路径传 [configureDrainLimit]
     * （EDT 限批），超出上限的条目**留在队列不清空**（旧实现此处 clear 会把余量丢掉），
     * 后续 submit → ensureExecutor 重建写线程时由 drainAndWrite 一并补写；
     * **dispose 路径（SqlLogManager.dispose → 无参 close）保持全量排水语义不变**（终态不丢条）。
     */
    @Synchronized
    fun close(maxDrainEntries: Int = Int.MAX_VALUE) {
        drainPendingSync(maxDrainEntries)
        drainMerges()
        waitingMerges.clear()
        waitingOrder.clear()
        executor?.shutdownNow()
        executor = null
        drainScheduled.set(false)
        // T-PS4-13：不再 clear——限批排水后余量留在队列待补写；全量排水（dispose）后队列本已空
        closeWriter()
    }

    /** 仅关闭当前 writer（滚动/切换文件用；不动队列与执行器）。 */
    private fun closeWriter() {
        writer?.let { w ->
            runCatching {
                w.close()
            }.onFailure {
                LOG.debug("自动保存历史 SQL：关闭文件异常", it)
            }
        }
        writer = null
        currentFile = null
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogAutoSaver::class.java)

        /** T-Perf-12：条目时间戳格式（DateTimeFormatter 不可变线程安全，替代逐条 SimpleDateFormat）。 */
        private val TIMESTAMP_FORMATTER: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH)

        /**
         * 项目内插件数据根目录名（F-44-4）：随插件更名「SQL Visualizer」由旧名
         * `mybatis-plus-sql-visualizer` 改为 `sql-visualizer`，**不考虑向前兼容**
         * （旧 `.idea/mybatis-plus-sql-visualizer/` 数据不再读取，历史 SQL 与新捕获从新目录起算）。
         * 其他模块（HistorySqlLogPanel / DataSourceSelector / SqlPreviewPanel）统一引用本常量。
         */
        const val DATA_DIR_NAME = "sql-visualizer"

        private val DEFAULT_DIR = "$DATA_DIR_NAME${File.separator}sql-history"

        /**
         * 解析历史 SQL 目录（写端 [configure] 与读端 HistorySqlLogPanel 的**唯一来源**）：
         * 自定义目录非空则优先（去首尾空白）；否则 `<项目根>/.idea/sql-visualizer/sql-history`；
         * 无项目根（Default Project 等非文件型项目）回落 `user.home` 下的同一相对路径。
         *
         * T-HistoryTabAlways：此前读端自己拼一份同形路径且只认默认目录——用户把「保存目录」改成
         * 自定义路径后，自动保存写进去的历史文件在历史页里看不到（读写两端各拼一份、只有写端认自定义
         * 目录）。收口到本函数后两端恒同源。
         */
        internal fun resolveHistoryDir(customPath: String, projectBasePath: String?): String {
            if (customPath.isNotBlank()) return customPath.trim()
            val base = projectBasePath ?: System.getProperty("user.home")
            return "$base${File.separator}.idea${File.separator}$DEFAULT_DIR"
        }

        /** 匹配 $变量$ 占位符。 */
        private val PLACEHOLDER_REGEX = Regex("""\$([^$]+)\$""")

        /** T-PS4-10：两次历史文件裁剪扫描的最小间隔——风暴期写批密集时避免重复全目录扫描。 */
        internal const val DEFAULT_PRUNE_MIN_INTERVAL_MS = 60_000L

        /** T-PS4-13：configure 路径 close 单次同步排水条数上限（EDT 不承担超长批磁盘写）。 */
        internal const val DEFAULT_CONFIGURE_DRAIN_LIMIT = 500
    }
}
