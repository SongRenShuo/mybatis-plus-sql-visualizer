package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * T-WorkspaceHeal（2026-11-13）：项目打开时**自愈**被旧版本写坏的 `.idea/workspace.xml`。
 *
 * 存量治愈的落点：写侧根因（后台线程写项目级 `PropertiesComponent`）已随 `f508d55dc`/`e08280a00`
 * 归位 EDT 修掉，但**已经被写坏的工程不会自己好**——平台每次启动都报
 * `JsonDecodingException: Expected quotation mark '"', but had '8'`，而且不重写该条目。
 * 本 Activity 在项目打开时把那一行修回来：平台先加载（本次启动的报错已产生），插件随后修文件，
 * **从下一次启动起彻底干净**。
 *
 * 纪律（任何一条不成立就什么都不做）：
 *  - 只认 `.idea/workspace.xml` 里 `<component name="PropertiesComponent">` 这一块；
 *  - 只认 [OWN_PROPERTY_KEY_PREFIX] 前缀的键，别人的键一个字节都不碰；
 *  - 改完整块必须能重新解析成 JSON，否则不落盘（说明别处也坏了，不归本插件管）；
 *  - 落盘前先备份到 `.sql-visualizer/backup/`；
 *  - 修完立刻把 `PropertiesComponent` 预热一次：加载成功即平台持有合法 state，后续保存不会
 *    用空 state 覆盖修复（这也是「本会话内功能恢复」的判据，失败只影响本会话）。
 *
 * 健康工程的开销＝一次文件读 + 一次子串判定（不含本插件键直接返回），不做 JSON 解析。
 */
class WorkspacePropertiesRepairActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        runCatching { heal(project) }
            .onFailure { LOG.warn("T-WorkspaceHeal: 自愈流程异常（已忽略，不影响启动）", it) }
    }

    companion object {
        private val LOG = Logger.getInstance(WorkspacePropertiesRepairActivity::class.java)

        /** 通知组 id（plugin.xml 注册，一域一组惯例）。 */
        const val NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.repair"

        /** 备份时间戳格式（与日志时间口径一致，便于对照）。 */
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * 自愈主流程。internal 供测试按真实工程目录直接驱动。
         */
        internal fun heal(project: Project) {
            if (project.isDisposed) return
            val basePath = project.basePath ?: return
            val workspace = Paths.get(basePath, ".idea", "workspace.xml")
            if (!Files.isRegularFile(workspace)) return

            val source = readPreservingBom(workspace) ?: return
            if (!source.text.contains(OWN_PROPERTY_KEY_PREFIX)) return

            when (val outcome = repairWorkspaceFileText(source.text)) {
                is RepairOutcome.Repaired -> {
                    if (outcome.text == source.text) return
                    val backup = backup(workspace)
                    if (!writePreservingBom(workspace, outcome.text, source.hasBom)) return
                    LOG.info(
                        "T-WorkspaceHeal: 已修复 workspace.xml（keys=${outcome.fixedKeys}, backup=${backup ?: "未生成"}）",
                    )
                    warmPropertiesComponent(project)
                    notifyRepaired(project, outcome.fixedKeys, backup)
                }

                RepairOutcome.NoOwnDamage -> LOG.info(
                    "T-WorkspaceHeal: workspace.xml 的 PropertiesComponent 解析失败，但坏的不是本插件的键——按约定不处理",
                )

                RepairOutcome.Unrepairable -> LOG.warn(
                    "T-WorkspaceHeal: 本插件的键已修，但整块 JSON 仍不合法（别处也坏了），本次不落盘",
                )

                RepairOutcome.Healthy -> Unit
            }
        }

        /**
         * 读文本并记下 BOM：workspace.xml 实际无 BOM（真机样本实测首字节 `<?x`），但写回时
         * 必须原样保留工程文件原有 BOM，否则会变成整文件的首字符差异（P-4）。
         */
        private class SourceText(val text: String, val hasBom: Boolean)

        private fun readPreservingBom(path: Path): SourceText? = runCatching {
            val bytes = Files.readAllBytes(path)
            val hasBom = bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
            SourceText(String(body, StandardCharsets.UTF_8), hasBom)
        }.getOrNull()

        /**
         * 写回修复后的文本，返回是否真的写了。
         *
         * 写安全（照 `DataSourceSelector.writeVfTextViaDocument` 的既有纪律）：若该文件在编辑器里已有
         * **未保存**文档，放弃写入——用户手上有未落盘的改动时，程序性覆盖会撞 File Cache Conflict。
         * workspace.xml 几乎不会被人手工编辑，但这是「宁可不修」的一条零成本保险。
         */
        private fun writePreservingBom(path: Path, text: String, hasBom: Boolean): Boolean {
            val body = text.toByteArray(StandardCharsets.UTF_8)
            val bytes = if (hasBom) {
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
            } else {
                body
            }
            val vf = runCatching {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toAbsolutePath().toString().replace('\\', '/'))
            }.getOrNull()
            if (vf != null) {
                val fdm = FileDocumentManager.getInstance()
                val cached = runCatching { fdm.getCachedDocument(vf) }.getOrNull()
                if (cached != null && runCatching { fdm.isDocumentUnsaved(cached) }.getOrDefault(false)) {
                    LOG.info("T-WorkspaceHeal: workspace.xml 有未保存文档，本次不写（下次启动会重试）")
                    return false
                }
                return runCatching {
                    @Suppress("DEPRECATION") // WriteAction.compute(ThrowableComputable) 是 243 编译目标可用重载
                    WriteAction.compute<Unit, Throwable> { vf.setBinaryContent(bytes) }
                }.isSuccess
            }
            // VFS 拿不到条目（工程不在 VFS 里）：退回裸写
            Files.write(path, bytes)
            return true
        }

        /**
         * 改前备份到 `<项目>/.sql-visualizer/backup/workspace-<时间戳>.xml`。
         * 该目录已被插件用于其它落点，`.gitignore` 内容是 `*`，不会污染工作树。
         */
        private fun backup(workspace: Path): Path? = runCatching {
            val projectRoot = workspace.parent?.parent ?: return null
            val storeDir = projectRoot.resolve(".sql-visualizer")
            val backupDir = storeDir.resolve("backup")
            Files.createDirectories(backupDir)
            val ignore = storeDir.resolve(".gitignore")
            if (!Files.exists(ignore)) Files.write(ignore, "*\n".toByteArray(StandardCharsets.UTF_8))
            val target = backupDir.resolve("workspace-${STAMP.format(LocalDateTime.now())}.xml")
            Files.copy(workspace, target, StandardCopyOption.REPLACE_EXISTING)
            target
        }.getOrNull()

        /**
         * 修复后预热一次 `PropertiesComponent`：文件合法了，若平台重新加载成功，本会话的功能立即恢复，
         * 且平台随后保存的是合法 state（P-2）。失败不影响「下次启动干净」。
         */
        private fun warmPropertiesComponent(project: Project) {
            runCatching { PropertiesComponent.getInstance(project) }
                .onSuccess { LOG.info("T-WorkspaceHeal: 修复后 PropertiesComponent 已重新加载，本会话即恢复") }
                .onFailure { LOG.info("T-WorkspaceHeal: 修复后仍未加载（本会话不恢复，下次启动干净）", it) }
        }

        private fun notifyRepaired(project: Project, fixedKeys: List<String>, backup: Path?) {
            runCatching {
                val notification = Notification(
                    NOTIFICATION_GROUP,
                    MpSqlVisualizerBundle.message("workspaceRepair.title"),
                    MpSqlVisualizerBundle.message("workspaceRepair.message", backup?.toString() ?: "-"),
                    NotificationType.INFORMATION,
                )
                Notifications.Bus.notify(notification, project)
            }.onFailure { LOG.warn("T-WorkspaceHeal: 通知发送失败（已忽略）", it) }
        }
    }
}