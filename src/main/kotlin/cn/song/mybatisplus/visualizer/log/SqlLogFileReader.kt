package cn.song.mybatisplus.visualizer.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从 .idea/sql-visualizer/sql-history/ 文件反解析为 LogSqlEntry（F-44；F-44-4 目录随插件更名）。
 *
 * 支持两种格式：
 *   - 新版（F-44-2）：-- source: xxx | time: xxx | yyyy-MM-dd HH:mm:ss.SSS --\nSQL\n
 *     （起结均为两个 `-`，标准 SQL 单行注释）
 *   - 历史 F-44（已停止生产）：--- source: xxx | time: xxx | timestamp ---\nSQL\n
 *     （三个 `-`，与新格式含义相同，仅写法差异——Reader 兼容读取，以防历史文件被误判）
 *   - 旧版（F-43）：-- yyyy-MM-dd HH:mm:ss\nSQL\n
 *
 * 混合文件中三种格式共存时均可正确解析。
 */
object SqlLogFileReader {

    /** T-Perf-05：countInFile 缓存上限（超过整体清空——路径多样性有限，简单封顶防膨胀）。 */
    private const val COUNT_CACHE_MAX = 256

    /**
     * T-PerfSweep7 4a 测试缝：SimpleDateFormat 实际构造次数（仅供同模块测试断言
     * 「同格式两次解析只建一次 formatter」；生产语义与本计数无关）。
     */
    internal val formatterCreateCount = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 新版格式头部：兼容 2 或 3 个 `-` 起结（前缀与后缀的 `-` 数量独立，长度匹配即可）。
     * 捕获组：1=source 2=time 3=timestamp 4=logTime（可选）5=db（可选，T-AgentDialectLoop）。
     * 毫秒分数为非捕获组——曾误用捕获组占位第 4 组致 logTime 永远读丢（T-AgentDialectLoop 修复）。
     */
    private val NEW_HEADER_REGEX = Regex(
        """^--+ source: (.+?) \| time: (.+?) \| (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{3})?)(?: \| logTime: (\d+))?(?: \| db: (.+?))? --+$"""
    )

    /** 旧版格式头部：-- timestamp（无源码位置和耗时） */
    private val OLD_HEADER_REGEX = Regex(
        """^-- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{3})?)$"""
    )

    /**
     * 参数持久化行前缀/结尾（T-HistParam F1 格式 v2）：头部注释行之下的独立注释行，
     * 载荷为 Base64 编码的 rawSql + params（编解码见 [SqlLogParamCodec]）。
     * 前缀固定 `-- pd: ` 且以 ` --` 结尾——旧版头部判据（`-- ` 起始无 `source:`）会把
     * pd 行误判为 F-43 头部导致块被劈开，块切分与计数必须显式排除。
     */
    private const val PD_PREFIX = "-- pd: "
    private const val PD_SUFFIX = " --"
    private const val ID_PREFIX = "-- pd: o147-id: "
    private const val REVISION_PREFIX = "-- pd: o147-revision: "
    internal const val MAX_REVISION_PAYLOAD = 65536
    internal const val MAX_REVISIONS = 2048
    private const val MAX_REVISION_TOTAL = 8 * 1024 * 1024
    private val ID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private data class Revision(val identity: String, val entry: LogSqlEntry)

    private class Revisions {
        val values = mutableMapOf<String, Revision>()
        private var bytes = 0
        private var count = 0
        fun offer(line: String) {
            if (!line.startsWith(REVISION_PREFIX)) return
            // 文件流把超长修订折叠成裸前缀；直接传入完整字符串也按同一前缀成本计费。
            val chargedLength = if (line.length > MAX_REVISION_PAYLOAD + 150) REVISION_PREFIX.length else line.length
            if (count >= MAX_REVISIONS || bytes + chargedLength > MAX_REVISION_TOTAL) return
            count++; bytes += chargedLength
            if (line.length > MAX_REVISION_PAYLOAD + 150) return
            runCatching {
                val parts = line.removePrefix(REVISION_PREFIX).removeSuffix(PD_SUFFIX).split(' ', limit = 3)
                if (parts.size != 3 || !ID_REGEX.matches(parts[0]) || !parts[1].matches(Regex("[0-9a-f]{64}")) ||
                    parts[2].length > MAX_REVISION_PAYLOAD) return
                val decoded = java.util.Base64.getDecoder().decode(parts[2]).toString(Charsets.UTF_8)
                // 禁止递归修订，且只接受一个完整原生历史块。
                if (decoded.contains(ID_PREFIX) || decoded.contains(REVISION_PREFIX)) return
                val scanner = EntryBlockScanner()
                val entries = mutableListOf<LogSqlEntry>()
                decoded.lineSequence().forEach { scanner.offer(it)?.let(entries::add) }
                scanner.finish()?.let(entries::add)
                val entry = entries.singleOrNull() ?: return
                if (SqlLogParamCodec.historyIdentity(entry) != parts[1]) return
                values[parts[0]] = Revision(parts[1], entry)
            }
        }
        fun apply(id: String?, original: LogSqlEntry): LogSqlEntry {
            val revision = values[id] ?: return original
            if (SqlLogParamCodec.historyIdentity(original) != revision.identity) return original
            // 身份字段永远来自原始块；不允许修订注释替换 SQL/绑定值/顺序。
            return mergeDuplicateEntry(original, revision.entry).copy(
                rawSql = original.rawSql, restoredSql = original.restoredSql, params = original.params,
                timestamp = original.timestamp, sequenceNumber = original.sequenceNumber,
            )
        }
    }

    /** 修订行超限即丢弃剩余字符，不先 readLine 分配任意大小的外部载荷。普通 SQL 不截断。 */
    private fun boundedLines(reader: java.io.BufferedReader): Sequence<String> = sequence {
        val text = StringBuilder()
        var revision = false
        var overLimit = false
        while (true) {
            val c = reader.read()
            if (c == -1 || c == '\n'.code) {
                if (text.isNotEmpty() || c != -1) yield(if (overLimit) REVISION_PREFIX else text.toString().removeSuffix("\r"))
                text.setLength(0); revision = false; overLimit = false
                if (c == -1) break
            } else if (!overLimit) {
                text.append(c.toChar())
                if (text.length == REVISION_PREFIX.length) revision = text.toString() == REVISION_PREFIX
                // 最后一个 CR 暂不计入逻辑长度：若随后 LF/EOF，出行时剥除；
                // 若随后仍有字符，它会重新计入。只宽限单个尾 CR，不截断普通 SQL。
                val logicalLength = text.length - if (c == '\r'.code) 1 else 0
                if (revision && logicalLength > MAX_REVISION_PAYLOAD + 150) { text.setLength(0); overLimit = true }
            }
        }
    }

    private fun revisionsIn(file: File): Revisions = Revisions().also { revisions ->
        file.bufferedReader().use { reader -> boundedLines(reader).forEach(revisions::offer) }
    }

    /**
     * 读取目录下所有 .sql 文件，返回按时间戳排序的 LogSqlEntry 列表。
     */
    fun readAll(dir: String): List<LogSqlEntry> {
        val directory = File(dir)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { f -> f.extension == "sql" }
            ?.flatMap { readFromFile(it) }
            ?.sortedBy { it.timestamp }
            ?: emptyList()
    }

    fun readFromFile(file: File): List<LogSqlEntry> {
        if (!file.exists()) return emptyList()
        // T-SqlHistoryFull：整读 file.readText() → 流式逐行（单文件滚动阈值 100MB，整读内存峰值可达文件体量；
        // 逐行消费只在解析块内累积当前块，峰值≈单条 SQL）。解析结果与整读路径完全一致。
        // T-SqlLogPerf：历史文件加载是 20261006 实测延迟大头嫌疑（8708 条/19MB 计 15~16.6s），阈值打点归因。
        return LogPerfProbe.measure("SqlLogFileReader.readFromFile", { "${file.name} ${file.length()}B" }) {
            val revisions = revisionsIn(file)
            file.bufferedReader().use { reader -> parseContent(boundedLines(reader), revisions, collectRevisions = false) }
        }
    }

    /**
     * T-Perf-05：countInFile 结果缓存（键=绝对路径，值=(lastModified, length, 计数)）。
     * 自动保存单文件滚动阈值 100MB（SqlLogAutoSaver），长期开启必然长出大文件——
     * 每次 refresh 逐行全量扫描是历史 tab 打开冻结的主要来源；lastModified+length 未变即命中
     * （length 一并校验，规避同毫秒重写导致 lastModified 不变而内容已变的误命中）。
     */
    private val countCache = java.util.concurrent.ConcurrentHashMap<String, kotlin.Triple<Long, Long, Int>>()

    /**
     * 快速统计文件中的 SQL 条目数（F-44-3 两级导航一级列表用）：
     * 只数头部行（`-- source: ...` / `--- source: ...` / `-- yyyy-MM-dd ...`），
     * 不构建 [LogSqlEntry]——大文件文件列表加载时避免无谓的对象分配。
     * 判定规则与 [parseContent] 保持一致（含 `source:` 与 `time:` 关键字的 `--` 行为新版头部，
     * `-- 日期` 为旧版头部），因此计数与 [readFromFile] 结果一致。
     */
    fun countInFile(file: File): Int {
        if (!file.exists()) return 0
        val key = file.absolutePath
        val lastModified = file.lastModified()
        val length = file.length()
        countCache[key]?.takeIf { it.first == lastModified && it.second == length }?.let { return it.third }
        var count = 0
        file.bufferedReader().use { reader ->
            for (line in boundedLines(reader)) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                    count++
                } else if (line.startsWith("-- ") && !line.contains("source:") && !line.startsWith(PD_PREFIX)) {
                    count++
                }
            }
        }
        if (countCache.size >= COUNT_CACHE_MAX) countCache.clear()
        countCache[key] = kotlin.Triple(lastModified, length, count)
        return count
    }

    /**
     * 解析文件内容为 LogSqlEntry 列表。
     * 逐行扫描，遇到头部行则开始新块，兼容新旧两种头部混合。
     *
     * T-LogHistoryRowNo：返回前按解析顺序注入文件内条目序号（sequenceNumber = 索引+1）——
     * 历史行号语义 = 文件内顺序号（静态历史无「会话内捕获序号」概念，见开发计划 §1.2）。
     * 序号是读取层派生的展示属性，不持久化；搜索过滤只删不改造条目实例，序号天然稳定。
     */
    fun parseContent(content: String): List<LogSqlEntry> =
        parseContent(content.lineSequence())

    /**
     * 解析文件内容为 LogSqlEntry 列表（T-SqlHistoryFull：流式逐行输入源）。
     * 逐行扫描，遇到头部行则开始新块，兼容新旧两种头部混合。
     *
     * T-LogHistoryRowNo：返回前按解析顺序注入文件内条目序号（sequenceNumber = 索引+1）——
     * 历史行号语义 = 文件内顺序号（静态历史无「会话内捕获序号」概念，见开发计划 §1.2）。
     * 序号是读取层派生的展示属性，不持久化；搜索过滤只删不改造条目实例，序号天然稳定。
     */
    fun parseContent(lines: Sequence<String>): List<LogSqlEntry> = parseContent(lines, Revisions())

    private fun parseContent(lines: Sequence<String>, revisions: Revisions, collectRevisions: Boolean = true): List<LogSqlEntry> {
        val entries = mutableListOf<LogSqlEntry>()
        val ids = mutableListOf<String?>()
        // T-SqlLogPerf（20261006-1020 计划 P2）：块累积不再走 joinToString → lines() → joinToString
        // 三份全量拷贝——头部行与正文行分开持有，parseBlockLines 直收行序列，单块只剩
        // 组装 restoredSql 那一次必要拷贝。判定逻辑与逐字语义与旧双拷贝版完全一致（round-trip 测试钉）。
        val scanner = EntryBlockScanner()
        for (line in lines) {
            if (collectRevisions) revisions.offer(line)
            scanner.offer(line)?.let { entries.add(it); ids.add(scanner.completedId) }
        }
        scanner.finish()?.let { entries.add(it); ids.add(scanner.completedId) }
        // T-LogHistoryRowNo：按文件内解析顺序注入序号（防御：已有序号的条目不覆盖）
        return entries.mapIndexed { index, original ->
            val entry = revisions.apply(ids[index], original)
            if (entry.sequenceNumber == 0L) entry.copy(sequenceNumber = (index + 1).toLong()) else entry
        }
    }

    /**
     * V47（D31 道 A 硬前置，F55 三合一）：**逐条迭代器**——回调形，[action] 返回 false 即停止
     * 读取（扫描封顶/提前收束），文件随迭代及时关闭（use 语义，中途停止不漏句柄）。
     *
     * 核实结论（20261007 实施期）：本 Reader 原有接口面**无条目粒度的流式消费口**——
     * [parseContent] 虽收行序列（T-SqlHistoryFull 的行流），但仍把全部条目物化成 List 返回，
     * 调用方无法边读边滤/按条目封顶。本接口补齐该缺口：与 [parseContent] 共用同一
     * [EntryBlockScanner] 状态机（单遍化、逐字节同语义，round-trip 测试钉），条目序号
     * 按产出顺序注入（与 parseContent 的 mapIndexed 序号一致）。旧接口全部原样保留。
     */
    fun forEachEntry(file: File, action: (LogSqlEntry) -> Boolean) {
        if (!file.exists()) return
        // T-SqlLogPerf：与 readFromFile 同款阈值打点（风暴期归因，常态零噪音）
        LogPerfProbe.measure("SqlLogFileReader.forEachEntry", { "${file.name} ${file.length()}B" }) {
            val revisions = revisionsIn(file)
            file.bufferedReader().use { reader ->
                val scanner = EntryBlockScanner()
                var seq = 0L
                for (line in boundedLines(reader)) {
                    val original = scanner.offer(line) ?: continue
                    val entry = revisions.apply(scanner.completedId, original)
                    seq++
                    val numbered = if (entry.sequenceNumber == 0L) entry.copy(sequenceNumber = seq) else entry
                    if (!action(numbered)) return@use
                }
                scanner.finish()?.let { original ->
                    val entry = revisions.apply(scanner.completedId, original)
                    seq++
                    val numbered = if (entry.sequenceNumber == 0L) entry.copy(sequenceNumber = seq) else entry
                    action(numbered)
                }
            }
        }
    }

    /**
     * 共享块切分状态机（parseContent 与 forEachEntry 单一实现源）：逐行喂入，头部行
     * 开新块并吐出上一块的解析结果（无上一块/头部不合规解析失败返回 null）。
     * 头部判定与 [countInFile] 的计数判据保持一致（含 `source:` 与 `time:` 的新版头部、
     * `-- 日期` 的旧版头部、排除 `-- pd: ` 参数持久化行）。
     */
    private class EntryBlockScanner {
        private var header: String? = null
        private var id: String? = null
        var completedId: String? = null
            private set
        private val bodyLines = mutableListOf<String>()

        /** 喂一行；若该行开启新块则返回上一块解析结果（无上一块或解析失败返回 null）。 */
        fun offer(line: String): LogSqlEntry? {
            if (line.startsWith(REVISION_PREFIX)) return null
            if (line.startsWith(ID_PREFIX)) {
                if (header != null) id = line.removePrefix(ID_PREFIX).removeSuffix(PD_SUFFIX).takeIf(ID_REGEX::matches)
                return null
            }
            // 检测新版头部（兼容 2/3 个 `-`：-- source 或 --- source）
            // 此处不能用 startsWith 严格判别「-- source」——旧版 F-43 的「-- 2026-08-31 ...」也会匹配，
            // 故先看是否为新版结构（含 "source:" 与 "time:" 关键字）——具体交由正则捕获 timestamp/format ，
            // parseBlockLines 中先 NEW 后 OLD 顺序尝试，任一正则匹配即可。
            val trimmed = line.trimStart()
            if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                val done = flush()
                header = line
                return done
            }
            // 检测旧版头部（F-43：`-- yyyy-MM-dd HH:mm:ss`）；`-- pd: ` 是参数持久化行非头部，
            // 不排除会被当作旧头部把条目劈成两块（T-HistParam）
            if (line.startsWith("-- ") && !line.contains("source:") && !line.startsWith(PD_PREFIX)) {
                val done = flush()
                header = line
                return done
            }
            // 非头部行（SQL 正文或空行）
            if (header != null) bodyLines.add(line)
            return null
        }

        /** 收尾：吐出最后一个未闭合块（无块/解析失败返回 null）。 */
        fun finish(): LogSqlEntry? = flush()

        private fun flush(): LogSqlEntry? {
            val h = header ?: return null
            val entry = parseBlockLines(h, bodyLines)
            completedId = id
            id = null
            header = null
            bodyLines.clear()
            return entry
        }
    }

    private fun parseBlockLines(header: String, contentLines: List<String>): LogSqlEntry? {
        // T-SqlLogPerf（20261006-1020 计划 P2）：直收 parseContent 的行序列——旧 parseBlock(block)
        // 的 block.lines() 再切分已随单遍化移除；头部行/正文行由调用方分开传入，语义逐字一致。
        var bodyLines = contentLines
        var rawSqlOverride: String? = null
        var params: List<LogSqlParam> = emptyList()
        var decodedExt: SqlLogParamCodec.DecodedPayload? = null
        contentLines.firstOrNull()?.let { first ->
            val trimmedFirst = first.trim()
            if (trimmedFirst.startsWith(PD_PREFIX) && trimmedFirst.endsWith(PD_SUFFIX)) {
                SqlLogParamCodec.decode(trimmedFirst.removePrefix(PD_PREFIX).removeSuffix(PD_SUFFIX))
                    ?.let { payload ->
                        rawSqlOverride = payload.rawSql
                        params = payload.params
                        decodedExt = payload
                        bodyLines = contentLines.drop(1)
                    }
            }
        }
        // 写入端 withTerminator 会补 `;`（T-SqlDuration），读回去掉还原为原始 SQL——
        // 展示/复制路径会重新调用 withTerminator() 补回，此处只剥结尾一个
        val sql = bodyLines.joinToString("\n").trim().removeSuffix(";").trim()
        if (sql.isEmpty()) return null

        // 尝试新版格式
        NEW_HEADER_REGEX.find(header)?.let { mr ->
            val sourceStr = mr.groupValues[1]
            val timeStr = mr.groupValues[2]
            val timestampStr = mr.groupValues[3]
            val timestamp = parseTimestampNew(timestampStr) ?: return null
            val logTime = mr.groupValues[4].toLongOrNull()
            val (sourceFile, sourceLine) = parseSource(sourceStr)
            val executionTimeMs = parseDuration(timeStr)
            return LogSqlEntry(
                rawSql = rawSqlOverride ?: sql,
                restoredSql = sql,
                params = params,
                timestamp = timestamp,
                logTimestamp = logTime,
                sourceFile = sourceFile,
                sourceLine = sourceLine,
                executionTimeMs = executionTimeMs,
                source = decodedExt?.source ?: SqlSource.UNKNOWN,
                databaseName = mr.groupValues[5].takeIf { it.isNotBlank() },
                mapperStatement = decodedExt?.mapperStatement,
                errorText = decodedExt?.errorText,
                requestTag = decodedExt?.requestTag,
                updateCount = decodedExt?.updateCount,
                sourceCandidates = decodedExt?.sourceCandidates ?: emptyList(),
                candidateClasses = decodedExt?.candidateClasses ?: emptyList(),
                stackDigest = decodedExt?.stackDigest,
                threadName = decodedExt?.threadName,
                actualSql = decodedExt?.actualSql,
                captureProvenance = decodedExt?.captureProvenance ?: CaptureProvenance.UNKNOWN,
            )
        }

        // 尝试旧版格式
        OLD_HEADER_REGEX.find(header)?.let { mr ->
            val timestampStr = mr.groupValues[1]
            val timestamp = parseTimestampOld(timestampStr) ?: return null
            return LogSqlEntry(
                rawSql = rawSqlOverride ?: sql,
                restoredSql = sql,
                params = params,
                timestamp = timestamp,
                source = decodedExt?.source ?: SqlSource.UNKNOWN,
                mapperStatement = decodedExt?.mapperStatement,
                errorText = decodedExt?.errorText,
                requestTag = decodedExt?.requestTag,
                updateCount = decodedExt?.updateCount,
                sourceCandidates = decodedExt?.sourceCandidates ?: emptyList(),
                candidateClasses = decodedExt?.candidateClasses ?: emptyList(),
                stackDigest = decodedExt?.stackDigest,
                threadName = decodedExt?.threadName,
                actualSql = decodedExt?.actualSql,
                captureProvenance = decodedExt?.captureProvenance ?: CaptureProvenance.UNKNOWN,
            )
        }

        return null
    }

    private fun parseSource(sourceStr: String): Pair<String?, Int> {
        if (sourceStr == "unknown") return null to 0
        // 分离文件路径和行号：path/to/File.java:42 → (path/to/File.java, 42)
        val colonIdx = sourceStr.lastIndexOf(':')
        return if (colonIdx > 0) {
            val file = sourceStr.substring(0, colonIdx)
            val line = sourceStr.substring(colonIdx + 1).toIntOrNull() ?: 0
            file to line
        } else {
            sourceStr to 0
        }
    }

    private fun parseDuration(timeStr: String): Long? {
        if (timeStr == "—" || timeStr == "-" || timeStr.isBlank()) return null
        return timeStr.removeSuffix("ms").toLongOrNull()
    }

    /**
     * T-PerfSweep7 4a：SimpleDateFormat 每次解析新建 → **每线程每格式一次**（照
     * [LogTimestampParser.parseTimestamp] 的 ThreadLocal + 格式串键缓存范式；日志导入是
     * O(行数) 的解析热路径，SDF 构造是其中的固定开销）。
     *
     * **等价性红线**：复用实例的输出与逐次新建逐字节一致（`parse` 每次完整重置内部 calendar）；
     * 且**刻意不**照搬 LogTimestampParser 的 `isLenient = false` 与时区赋值——本处现状是
     * 默认宽松模式 + 默认时区，缓存化只改构造频率，不改任何解析语义（异常回落 null 不变）。
     */
    private val timestampFormatCache =
        ThreadLocal.withInitial<HashMap<String, SimpleDateFormat>> { HashMap() }

    private fun formatterFor(pattern: String): SimpleDateFormat =
        timestampFormatCache.get().getOrPut(pattern) {
            formatterCreateCount.incrementAndGet()
            SimpleDateFormat(pattern, Locale.ENGLISH)
        }

    private fun parseTimestampNew(ts: String): Long? = try {
        formatterFor("yyyy-MM-dd HH:mm:ss.SSS").parse(ts)?.time
    } catch (_: Exception) { null }

    private fun parseTimestampOld(ts: String): Long? = try {
        formatterFor("yyyy-MM-dd HH:mm:ss").parse(ts)?.time
    } catch (_: Exception) { null }
}
