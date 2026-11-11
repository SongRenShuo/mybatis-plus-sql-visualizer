package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.MessageBusConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SQL 方言死路主动提示（T-NotifyTune 券 A）：
 *
 * 竞品 MyBatisCodeHelper-Pro 在打开 MyBatis 文件且方言未配置时主动弹一条「说后果 + 一键配置 +
 * 当前项目不再提示」的气球。本插件默认智能推断、多数项目零配置，故只吸收**形态**：
 * 只在推断走到死路（`Status.UNAVAILABLE` 且无方言名）时提示一次，不把方言变成必须配置项。
 *
 * 触发约束（与 [OnboardingAnchorTip] 同形，热路径零涉足）：
 *  - 探测挂**低频入口**——postStartupActivity 订阅 `FileEditorManagerListener.fileOpened`，
 *    后台线程先做一次文件文本启发式扫描（[isSqlBearingFile]），命中才进方言决策；
 *  - 一次性——「已展示」持久化到 [PluginSettings.State.dialectTipShown]（application 级），
 *    每台机器至多一条（不再是每会话）；
 *  - 让位——首用锚点引导尚未展示且仍启用时，先让新用户看「预览怎么用」，不叠第二条气球；
 *  - 开关 + 本项目永久抑制（[PluginSettings.State.dialectTipEnabled] /
 *    `dialectTipSuppressedProjects`），过弹窗克制红线准入五问第 1/3/4 问；
 *  - headless（isUnitTestMode）下持久化语义照常、不弹通知。
 *
 * 为什么不用 MyBatis 口径：本插件支持 MyBatis / MyBatis-Plus / MPJ / iBatis 2.x / MBG Example /
 * JPA-HQL / JdbcTemplate / 通用 XML 内联 SQL（2026-11-11 用户指出），识别面必须同宽。
 */
class DialectGapTip : ProjectActivity {

    override suspend fun execute(project: Project) {
        // 连接绑定 project（Disposable），项目关闭自动注销；弹过即注销（一次性语义下已无使命）。
        val connection = project.messageBus.connect(project)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    onFileOpened(project, file, connection)
                }
            },
        )
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /** 超大文件跳过探测（封顶一次扫描成本）。 */
        private const val MAX_PROBE_BYTES = 1_000_000L

        /** 探测进行中节流（文件连续打开时只保留一个后台扫描）。 */
        private val probing = AtomicBoolean(false)

        /** 一次性展示权领取锁（并发探测命中只弹一条）。 */
        private val claimLock = Any()

        /**
         * 文件打开入口（EDT 回调）：守卫全走纯函数，命中才投一个后台扫描（低频 + 廉价）。
         *
         * @param connection 总线连接句柄（弹过后注销；null = 无连接上下文，仅测试直调）
         */
        internal fun onFileOpened(project: Project, file: VirtualFile, connection: MessageBusConnection? = null) {
            val state = PluginSettings.getInstance().state
            val anchorTipPending = state.onboardingAnchorTipEnabled && !state.onboardingAnchorTipShown
            if (!shouldProbe(
                    enabled = state.dialectTipEnabled,
                    alreadyShown = state.dialectTipShown,
                    suppressedForProject = isSuppressed(state, project),
                    anchorTipPending = anchorTipPending,
                )
            ) return
            if (!isProbeTarget(file.name)) return
            if (!probing.compareAndSet(false, true)) return
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    probe(project, file, connection)
                } finally {
                    probing.set(false)
                }
            }
        }

        /** 后台扫描：读文本（ReadAction）→ 启发式认领 → 方言决策 → 领取展示权 → 弹通知。 */
        private fun probe(project: Project, file: VirtualFile, connection: MessageBusConnection?) {
            val text = runCatching {
                @Suppress("DEPRECATION") // 262 下 ReadAction.compute(ThrowableComputable) 标 deprecated；243 编译目标仅此重载可用
                ReadAction.compute<String, Throwable> {
                    if (file.isValid && file.length in 1..MAX_PROBE_BYTES) VfsUtil.loadText(file) else ""
                }
            }.getOrDefault("")
            if (!isSqlBearingFile(file.name, text)) return
            if (project.isDisposed) return
            val decision = runCatching {
                @Suppress("DEPRECATION")
                ReadAction.compute<DialectDecisionEngine.Decision, Throwable> {
                    DialectRegistry.decision(project = project, sourceFile = file)
                }
            }.getOrNull() ?: return
            if (!isDialectGap(decision.status, decision.dialectName)) return
            if (!claimAndMark()) return
            connection?.disconnect()
            if (ApplicationManager.getApplication().isUnitTestMode) return // headless：持久化已置位，通知跳过
            ApplicationManager.getApplication().invokeLater { notifyGap(project) }
        }

        private fun notifyGap(project: Project) {
            if (project.isDisposed) return
            runCatching {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("dialectGapTip.title"),
                        MpSqlVisualizerBundle.message("dialectGapTip.content"),
                        NotificationType.WARNING,
                    )
                notification.addAction(
                    NotificationAction.createSimpleExpiring(
                        MpSqlVisualizerBundle.message("dialectGapTip.configure"),
                    ) {
                        if (!project.isDisposed) {
                            PluginSettingsFolderConfigurable.openSettingsFocused(
                                project,
                                PluginSettingsFolderConfigurable.PAGE_ID_PREVIEW,
                                MpSqlVisualizerBundle.message("settings.dialect"),
                            )
                        }
                    },
                )
                notification.addAction(
                    NotificationAction.createSimpleExpiring(
                        MpSqlVisualizerBundle.message("dialectGapTip.suppressProject"),
                    ) {
                        suppressForProject(project)
                    },
                )
                notification.notify(project)
            }
        }

        /**
         * 领取一次性展示权：通过守卫则置「已展示」并返回 true（并发命中只有首个领取者返回 true）。
         * headless 测试同样走本入口——持久化语义与通知解耦（测试断言状态翻转）。
         */
        internal fun claimAndMark(): Boolean = synchronized(claimLock) {
            val s = PluginSettings.getInstance().state
            if (!s.dialectTipEnabled || s.dialectTipShown) return@synchronized false
            s.dialectTipShown = true
            true
        }

        /** 「本项目不再提示」：按项目路径哈希记入抑制集合（幂等）。 */
        internal fun suppressForProject(project: Project) {
            val key = projectKey(project)
            val suppressed = PluginSettings.getInstance().state.dialectTipSuppressedProjects
            if (suppressed.none { it == key }) suppressed.add(key)
        }

        internal fun isSuppressed(state: PluginSettings.State, project: Project): Boolean {
            val key = projectKey(project)
            return state.dialectTipSuppressedProjects.any { it == key }
        }

        /**
         * 项目键（纯函数，测试缝）：优先项目根路径、退化项目名，SHA-256 前 8 字节十六进制——
         * 只存哈希不存明文路径，避免配置里泄漏用户目录。
         */
        internal fun projectKey(project: Project): String {
            val base = project.basePath ?: project.name
            val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        /**
         * 展示决策（纯函数，测试缝）：开关开 + 未展示过 + 本项目未抑制 + 首用锚点引导不在待展示态。
         *
         * 「让位」只在锚点引导**仍启用且尚未展示**时生效——若用户关掉了锚点引导，它永远不会展示，
         * 此时不应连带压掉方言提示。
         */
        internal fun shouldProbe(
            enabled: Boolean,
            alreadyShown: Boolean,
            suppressedForProject: Boolean,
            anchorTipPending: Boolean,
        ): Boolean = enabled && !alreadyShown && !suppressedForProject && !anchorTipPending

        /** 探测目标（纯函数，测试缝）：仅 Java / Kotlin / XML 源文件。 */
        internal fun isProbeTarget(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in setOf("java", "kt", "xml")

        /** 死路判据（纯函数，测试缝）：仅「证据不足且无方言名」；歧义/冲突已有面板内处理，不再主动弹。 */
        internal fun isDialectGap(status: DialectDecisionEngine.Status?, dialectName: String?): Boolean =
            status == DialectDecisionEngine.Status.UNAVAILABLE && dialectName == null

        /**
         * 「SQL 承载文件」判定（纯函数，测试缝）：文本级子串匹配，零 PSI resolve / 零索引依赖。
         *
         * 覆盖本插件全部支持面（2026-11-11 用户要求「不止 MyBatis」）：
         *  - Java/Kotlin：Wrapper 链、MyBatis 注解 SQL、JPA `@Query`、JdbcTemplate、iBatis `SqlMapClient`；
         *  - XML：mapper 语句标签、Hibernate hbm、iBatis `<sqlMap>` / `<statement>` / `<procedure>`。
         *
         * 与 [OnboardingAnchorTip.anchorHintHit] **刻意分开**：那是首用引导的语义与误报面，
         * 改成超集会连带改变它的触发。
         */
        internal fun isSqlBearingFile(fileName: String, text: String): Boolean {
            if (text.isEmpty()) return false
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "java", "kt" -> JAVA_HINTS.any { text.contains(it) }
                "xml" -> XML_HINTS.any { text.contains(it, ignoreCase = true) }
                else -> false
            }
        }

        /** Java/Kotlin 侧锚点（大小写敏感——类名/注解形态固定，误报面最小）。 */
        private val JAVA_HINTS = listOf(
            // MyBatis / MyBatis-Plus / MPJ Wrapper 链
            "QueryWrapper", "UpdateWrapper", "JoinWrapper", "MPJLambdaWrapper", "MPJQueryWrapper", "Wrappers.",
            // MyBatis 注解 SQL
            "@Select", "@Insert", "@Update", "@Delete",
            // JPA / HQL
            "@Query", "@NativeQuery",
            // Spring JdbcTemplate
            "JdbcTemplate", "jdbcTemplate", "queryForList(", "queryForObject(", "queryForMap(",
            // iBatis 2.x Java 调用
            "SqlMapClient", "getSqlMapClient",
        )

        /** XML 侧锚点（大小写不敏感）。 */
        private val XML_HINTS = listOf(
            "<select", "<insert", "<update", "<delete",
            "<hibernate-mapping", "<sql-query",
            "<sqlMap", "<statement", "<procedure",
        )
    }
}