package cn.song.mybatisplus.visualizer.sql.rollback

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Path

/**
 * T-RollbackSnapshotPreExec P1：执行前快照的**项目级落盘门面**（light PROJECT service）。
 *
 * 职责只有两件：把 [RollbackSnapshotFileStore]（纯逻辑）接上项目根路径，以及把磁盘 IO
 * 隔离在池线程（EDT 回调只装内存态/提示）。落点 `<项目根>/.sql-visualizer/snapshots/`
 * （计划 §3.4）；目录不可写/磁盘异常 → 记 WARN 后静默降级（V7：不抛、不阻断执行链——
 * 采集失败本来就该 fail-open）。
 *
 * 剪枝时机：每次落盘后顺带剪一次（幂等）；启动兜底由 [StartupPruner]（postStartupActivity，
 * fail-open，开关关闭时不跑）。
 */
@Service(Service.Level.PROJECT)
class RollbackSnapshotProjectStore(private val project: Project) {

    /** 项目快照目录（失败时抛 IllegalArgumentException 由调用方 runCatching 兜住）。 */
    fun dir(): Path {
        val base = project.basePath ?: throw IllegalStateException("project basePath is null")
        return RollbackSnapshotFileStore.dirFor(base)
    }

    /** 落盘 + 顺带剪枝；全部在池线程执行，异常记 WARN 不抛（V7 fail-open）。 */
    fun persist(record: RollbackSnapshotFileStore.SnapshotRecord, nowMs: Long) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val dir = dir()
                RollbackSnapshotFileStore.save(dir, record, nowMs)
                RollbackSnapshotFileStore.prune(dir, nowMs)
            }.onFailure { e ->
                LOG.warn("[TRollbackSnapshot] persist failed (fail-open, execution unaffected): ${e.message}")
            }
        }
    }

    /** 读取条目（回滚弹窗/对比窗口用；同步小文件读，调用方自行选线程）。 */
    fun load(id: String): RollbackSnapshotFileStore.SnapshotRecord? =
        runCatching { RollbackSnapshotFileStore.load(dir(), id) }.getOrNull()

    /** 索引清单（列表 UI 用）。 */
    fun listEntries(): List<RollbackSnapshotFileStore.IndexEntry> =
        runCatching { RollbackSnapshotFileStore.listEntries(dir()) }.getOrDefault(emptyList())

    /** 状态推进（P3 改后采集 / P4 回滚回执）。 */
    fun updateStatus(
        id: String,
        mutate: (RollbackSnapshotFileStore.SnapshotRecord) -> RollbackSnapshotFileStore.SnapshotRecord,
    ): Boolean = runCatching { RollbackSnapshotFileStore.updateStatus(dir(), id, mutate) }.getOrDefault(false)

    /** 删除单条目（历史对话框「删除」用）；删除后重建索引。 */
    fun deleteEntry(id: String): Boolean = runCatching {
        val removed = java.nio.file.Files.deleteIfExists(dir().resolve("$id.json"))
        RollbackSnapshotFileStore.rebuildIndex(dir(), System.currentTimeMillis())
        removed
    }.getOrDefault(false)

    companion object {
        private val LOG = Logger.getInstance(RollbackSnapshotProjectStore::class.java)
    }
}

/**
 * 启动兜底剪枝（V7 兜底：上次会话异常退出留下的超量）。fail-open；开关关闭时不跑。
 * 注册：plugin.xml `<postStartupActivity implementation="…"/>`。
 */
internal class RollbackSnapshotStartupPruner : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!PluginSettings.getInstance().state.rollbackSnapshotBeforeExec) return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val base = project.basePath ?: return@executeOnPooledThread
                RollbackSnapshotFileStore.prune(
                    RollbackSnapshotFileStore.dirFor(base),
                    System.currentTimeMillis(),
                )
            }
        }
    }
}
