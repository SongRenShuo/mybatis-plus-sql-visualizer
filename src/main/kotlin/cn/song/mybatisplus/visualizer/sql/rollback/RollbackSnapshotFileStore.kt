package cn.song.mybatisplus.visualizer.sql.rollback

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * T-RollbackSnapshotPreExec P1：执行前快照的**落盘存储**（纯逻辑，零 IDE 依赖，可进 testFast）。
 *
 * 目录形态（计划 §3.4）：`<项目根>/.sql-visualizer/snapshots/` 下每条目一个 JSON
 * （`<id>.json`）+ 一份轻量索引 `index.json`（只存列表用元数据）。剪枝三阈值
 * 7 天 / 200 条 / 总量 50MB 先到先剪，幂等；IDE 启动与每次新增各跑一次由编排层触发。
 *
 * 用纯 java.nio 写盘（不经 VFS/document+save）：快照数据文件不进编辑器、不需要 PSI，
 * VFS 是否感知无所谓；`.gitignore("*")` 防业务数据误入版本控制（同 projectExecFile 纪律）。
 * **不**复用内存 `RollbackSnapshotStore`（LRU 100、会话即丢——计划 §3.4 明确不复用）。
 *
 * 线程模型：写入只发生在编排层的池线程回调里；对象内不做加锁，索引重建用
 * 「读旧索引 → 合并 → 单文件原子替换（临时文件 + MOVE）」防半写。
 */
object RollbackSnapshotFileStore {

    const val KEEP_DAYS = 7
    const val MAX_ENTRIES = 200
    const val MAX_TOTAL_BYTES: Long = 50L * 1024 * 1024
    const val INDEX_FILE = "index.json"

    /** 条目状态机（计划 §3.4；P3/P4 消费，P1 先落字段）。 */
    object Status {
        const val CAPTURED = "captured"
        const val COMPARED = "compared"
        const val ROLLED_BACK = "rolledBack"
        const val STALE = "stale"
        const val NO_ROWS_AFFECTED = "noRowsAffected"
    }

    data class SnapshotRecord(
        val id: String,
        val createdAtMs: Long,
        val mode: String,
        val connectionName: String? = null,
        val database: String? = null,
        val table: String,
        val dialect: String? = null,
        val statementSql: String,
        val snapshotSelectSql: String,
        val columns: List<String> = emptyList(),
        val beforeRows: List<List<String>> = emptyList(),
        val rowLimitHit: Boolean = false,
        val warnings: List<String> = emptyList(),
        val status: String = Status.CAPTURED,
        val afterColumns: List<String>? = null,
        val afterRows: List<List<String>>? = null,
        val afterCapturedAtMs: Long = 0,
        val rollbackExecutedAtMs: Long = 0,
    )

    data class IndexEntry(
        val id: String,
        val createdAtMs: Long,
        val mode: String,
        val table: String,
        val status: String,
        val rowCount: Int,
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    private val idCounter = AtomicLong(0)

    fun dirFor(projectBasePath: String): Path =
        Path.of(projectBasePath).resolve(RollbackSnapshotCapture.SNAPSHOT_DIR_RELATIVE)

    /** 生成条目 id：时间戳前缀（按时间排序即文件名排序）+ 随机尾（同毫秒并发不撞名）。 */
    fun newId(nowMs: Long): String {
        val seq = idCounter.incrementAndGet() % 1000
        return "snap-${nowMs}-${String.format("%03d", seq)}-${UUID.randomUUID().toString().take(6)}"
    }

    /** 首次建目录时写 `.gitignore`（内容 `*`），已存在则不动。 */
    fun ensureGitignore(dir: Path) {
        Files.createDirectories(dir)
        val ignore = dir.resolve(".gitignore")
        if (!Files.exists(ignore)) {
            Files.writeString(ignore, "*\n!.gitignore\n", StandardCharsets.UTF_8)
        }
    }

    /** 写条目 + 重建索引（含剪枝）。返回条目文件路径。 */
    fun save(dir: Path, record: SnapshotRecord, nowMs: Long): Path {
        ensureGitignore(dir)
        val file = dir.resolve("${record.id}.json")
        val tmp = dir.resolve("${record.id}.json.tmp")
        Files.writeString(tmp, gson.toJson(record), StandardCharsets.UTF_8)
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        rebuildIndexAfterPrune(dir, nowMs)
        return file
    }

    /** 读索引（损坏/缺失返回空清单，不抛——索引只是列表加速，条目文件才是事实源）。 */
    fun listEntries(dir: Path): List<IndexEntry> {
        val f = dir.resolve(INDEX_FILE)
        if (!Files.exists(f)) return emptyList()
        return runCatching {
            val arr = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).asJsonArray
            arr.map { e ->
                val o = e.asJsonObject
                IndexEntry(
                    id = o.get("id")?.asString ?: "",
                    createdAtMs = o.get("createdAtMs")?.asLong ?: 0,
                    mode = o.get("mode")?.asString ?: "",
                    table = o.get("table")?.asString ?: "",
                    status = o.get("status")?.asString ?: "",
                    rowCount = o.get("rowCount")?.asInt ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 读单条目；文件损坏返回 null（编排层按「无快照」处理，不抛）。 */
    fun load(dir: Path, id: String): SnapshotRecord? {
        val f = dir.resolve("$id.json")
        if (!Files.exists(f)) return null
        return runCatching {
            gson.fromJson(Files.readString(f, StandardCharsets.UTF_8), SnapshotRecord::class.java)
        }.getOrNull()
    }

    /**
     * 状态推进：P4 回滚执行后置 `rolledBack`、P3 改后采集后置 `compared`。
     * 条目缺失/损坏时返回 false（不抛）。
     */
    fun updateStatus(dir: Path, id: String, mutate: (SnapshotRecord) -> SnapshotRecord): Boolean {
        val current = load(dir, id) ?: return false
        save(dir, mutate(current), System.currentTimeMillis())
        return true
    }

    /**
     * 剪枝（幂等）：按 保留期 → 条目数 → 总字节 三阈值裁掉旧条目（旧 = createdAtMs 小者优先）。
     * @return 被删除的条目数
     */
    fun prune(dir: Path, nowMs: Long): Int {
        if (!Files.isDirectory(dir)) return 0
        val cutoff = nowMs - KEEP_DAYS * 24L * 60 * 60 * 1000
        val entries = listEntries(dir)
        val totalBytes = entries.sumOf { entry ->
            runCatching { Files.size(dir.resolve("${entry.id}.json")) }.getOrDefault(0L)
        }
        // 剪枝候选按「最旧优先」，先到期时间、再超条数、再超字节——三个阈值叠加裁剪
        val ordered = entries.sortedBy { it.createdAtMs }
        val doomed = LinkedHashSet<String>()
        var bytes = totalBytes
        for (entry in ordered) {
            val file = dir.resolve("${entry.id}.json")
            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            val tooOld = entry.createdAtMs < cutoff
            val tooMany = entries.size - doomed.size > MAX_ENTRIES
            val tooFat = bytes > MAX_TOTAL_BYTES
            if (tooOld || tooMany || tooFat) {
                doomed.add(entry.id)
                bytes -= size
            }
        }
        var removed = 0
        for (id in doomed) {
            if (runCatching { Files.deleteIfExists(dir.resolve("$id.json")) }.getOrDefault(false)) removed++
        }
        if (doomed.isNotEmpty()) rebuildIndex(dir, nowMs)
        return removed
    }

    private fun rebuildIndexAfterPrune(dir: Path, nowMs: Long) {
        prune(dir, nowMs)
        rebuildIndex(dir, nowMs)
    }

    /** 从条目文件重建索引（外部删除单条目后也走这里；幂等）。 */
    fun rebuildIndex(dir: Path, nowMs: Long) {
        val entries = listRecordFiles(dir)
        writeIndex(dir, entries, nowMs)
    }

    /** 从条目文件重建索引事实源（剪枝后/索引丢失时用）。 */
    private fun listRecordFiles(dir: Path): List<IndexEntry> {
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") && it.fileName.toString() != INDEX_FILE }
                    .map { path ->
                        runCatching { load(dir, path.fileName.toString().removeSuffix(".json")) }.getOrNull()
                    }
                    .toList()
                    .filterNotNull()
                    .map {
                        IndexEntry(it.id, it.createdAtMs, it.mode, it.table, it.status, it.beforeRows.size)
                    }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(dir: Path, entries: List<IndexEntry>, nowMs: Long) {
        ensureGitignore(dir)
        val sorted = entries.sortedByDescending { it.createdAtMs }
        val json = gson.toJson(sorted)
        val tmp = dir.resolve("$INDEX_FILE.tmp")
        Files.writeString(tmp, json, StandardCharsets.UTF_8)
        Files.move(
            tmp, dir.resolve(INDEX_FILE),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
