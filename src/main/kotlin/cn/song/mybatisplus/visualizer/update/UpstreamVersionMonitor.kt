package cn.song.mybatisplus.visualizer.update

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * F-11 上游 MyBatis 版本监听最小件（方案文档见
 * `docs/调研报告/20261106-F11-宿主能力EP化与上游版本监听-方案文档.md`）。
 *
 * 职责边界（只做「发现版本变了」这一件事）：
 *  - **读版本**：优先 `META-INF/maven/<group>/<artifact>/pom.properties` 的 `version=`；
 *    缺失时回落到该 artifact 锚点类所在 jar 的 `Implementation-Version`（两种来源都是 jar 自带
 *    元数据，不猜、不联网、不解析版本号语义）；
 *  - **比对**：项目级一次性探测（每项目会话只探一次），与 [PropertiesComponent] 里的上次记录
 *    逐字比较；**无变化不打扰**，首次探测（无记录）静默登记——安装首启不弹窗；
 *  - **提示**：仅变化时弹一条 INFORMATION 通知（复用既有「更新」通知组，不新增 EP、不改
 *    plugin.xml），一次性、可关（[NOTIFY_ENABLED] 常量关）。
 *
 * 已知边界（如实登记）：探测走**插件类加载器**。IDE 运行时项目依赖的 mybatis jar 通常不在插件
 * 类加载器视野内，此时 [probeVersions] 返回空列表 → 静默无动作（不误报、不造噪音）。真机若要覆盖
 * 项目依赖面，需要接项目级 `ProjectFileIndex`/`Library` 扫描（超出最小件范围，登记为后续项）。
 *
 * 纯函数（[parsePomProperties]/[parseManifestVersion]/[snapshotKey]/[changed]）零平台依赖，
 * 可 headless 直接覆盖；平台侧只有 [checkOnce] 一个薄入口。
 */
object UpstreamVersionMonitor {

    /**
     * 总开关（**常量关**）：不接设置 UI——F-11 约束禁改设置三件套（PluginSettings* /
     * SettingsScenarioGroups）。需要关停时改这里为 false 即整条链静默（含记录写入）。
     */
    const val NOTIFY_ENABLED: Boolean = true

    /** 上次探测结果的持久化键（项目级 PropertiesComponent）。 */
    internal const val LAST_SNAPSHOT_KEY: String = "mybatis.upstreamVersion.snapshot"

    /** 复用「更新」域通知组（一域一组惯例，与 StartupUpdateCheck 同组）。 */
    private const val NOTIFICATION_GROUP_ID: String = "cn.song.mybatisplus.visualizer.updates"

    /** 上游 artifact（group 前缀 + artifactId）；顺序即展示顺序。 */
    private val ARTIFACTS: List<Pair<String, String>> = listOf(
        "com.baomidou" to "mybatis-plus-core",
        "com.baomidou" to "mybatis-plus-extension",
        "org.mybatis" to "mybatis",
    )

    /**
     * artifact → 锚点类（pom.properties 缺失时经该类定位 jar 读 Manifest）。
     * 类名是字符串常量：生产依赖图零 mybatis（红线），全部走反射加载。
     */
    private val CLASS_ANCHORS: Map<String, String> = mapOf(
        "mybatis-plus-core" to "com.baomidou.mybatisplus.core.MybatisConfiguration",
        "mybatis" to "org.apache.ibatis.session.Configuration",
    )

    /** 一条上游版本事实（artifactId + 版本串原文）。 */
    data class Upstream(val artifact: String, val version: String)

    /** 已探测过的项目（每项目会话只探一次；项目关闭后自然回收）。 */
    private val probedProjects: MutableSet<Project> =
        Collections.newSetFromMap(ConcurrentHashMap<Project, Boolean>())

    // ------------------------------------------------------------------
    // 纯函数
    // ------------------------------------------------------------------

    /**
     * `pom.properties` 文本 → `version` 值（键缺失返回 null；行尾空白剔除）。
     * 按 Java properties 口径容忍 `key = value` 两侧空白（分隔符取首个 `=`/`:`）。
     */
    fun parsePomProperties(text: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            if (sep <= 0) continue
            if (line.substring(0, sep).trim() != "version") continue
            return line.substring(sep + 1).trim().ifEmpty { null }
        }
        return null
    }

    /**
     * `MANIFEST.MF` 文本 → `Implementation-Version` 值。
     * 按规范处理 72 字节折行（续行以单个空格开头，拼接时去掉该空格）。
     */
    fun parseManifestVersion(text: String): String? {
        val unfolded = StringBuilder()
        for (raw in text.lineSequence()) {
            if (raw.startsWith(" ")) {
                unfolded.append(raw.removePrefix(" "))
            } else {
                unfolded.append('\n').append(raw)
            }
        }
        for (raw in unfolded.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("Implementation-Version:", ignoreCase = true)) continue
            return line.substringAfter(':').trim().ifEmpty { null }
        }
        return null
    }

    /**
     * 版本快照键：`artifact=version` 按 artifact 排序拼接（同集合逐字可比的持久化形态）。
     * 空列表 → 空串（调用方据此判「本次没探到」并保持原记录不覆盖）。
     */
    fun snapshotKey(list: List<Upstream>): String =
        list.sortedBy { it.artifact }.joinToString(";") { "${it.artifact}=${it.version}" }

    /**
     * 是否需要提示：**上次有记录**且与本次不同才提示。
     * 首次探测（last 为空）静默登记——安装首启不打扰；本次空（没探到）也不提示，避免环境抖动误报。
     */
    fun changed(last: String?, current: String?): Boolean =
        !last.isNullOrBlank() && !current.isNullOrBlank() && last != current

    /** 快照键 → 人读多行文本（通知正文；`;` 分段换行，`=` 保留）。 */
    fun describe(key: String): String =
        key.split(';').filter { it.isNotBlank() }.joinToString("\n") { segment ->
            val idx = segment.indexOf('=')
            if (idx <= 0) segment else "${segment.substring(0, idx)}: ${segment.substring(idx + 1)}"
        }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    /**
     * 在给定类加载器视野内探测上游版本（pom.properties 优先，Manifest 兜底）。
     * 探不到返回空列表——**不伪造版本**，调用方保持静默。
     */
    fun probeVersions(loader: ClassLoader): List<Upstream> = ARTIFACTS.mapNotNull { (group, artifact) ->
        val fromPom = readResource(loader, "META-INF/maven/$group/$artifact/pom.properties")
            ?.let { parsePomProperties(it) }
        val version = fromPom ?: readVersionFromJarManifest(loader, artifact)
        version?.let { Upstream(artifact, it) }
    }

    private fun readResource(loader: ClassLoader, path: String): String? =
        runCatching { loader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() } }.getOrNull()

    /** 锚点类所在 jar 的 Manifest 版本（锚点类不在视野内 → null）。 */
    private fun readVersionFromJarManifest(loader: ClassLoader, artifact: String): String? {
        val anchor = CLASS_ANCHORS[artifact] ?: return null
        val location = runCatching {
            Class.forName(anchor, false, loader).protectionDomain?.codeSource?.location
        }.getOrNull() ?: return null
        val path = runCatching { java.nio.file.Paths.get(location.toURI()) }.getOrNull() ?: return null
        if (!path.fileName.toString().endsWith(".jar")) return null
        return runCatching {
            JarFile(path.toFile()).use { jar ->
                jar.manifest?.mainAttributes?.getValue("Implementation-Version")
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 平台入口（每项目会话一次）
    // ------------------------------------------------------------------

    /**
     * 项目级一次性探测 + 变化提示。headless（单测模式）直接返回且**不写记录**——测试不污染
     * 用户持久化状态（与 [VersionHighlightsActivity] 同口径）。
     */
    fun checkOnce(project: Project) {
        if (!NOTIFY_ENABLED) return
        val app = ApplicationManager.getApplication() ?: return
        if (app.isUnitTestMode) return
        if (project.isDisposed) return
        if (!probedProjects.add(project)) return
        val current = snapshotKey(probeVersions(UpstreamVersionMonitor::class.java.classLoader))
        if (current.isEmpty()) return // 探不到：保持原记录，不覆盖、不提示
        val pc = PropertiesComponent.getInstance(project)
        val last = pc.getValue(LAST_SNAPSHOT_KEY)
        // 先落记录再弹：领取即消耗，通知链异常也不会让同一变化反复打扰
        pc.setValue(LAST_SNAPSHOT_KEY, current)
        if (!changed(last, current)) return
        app.invokeLater {
            if (project.isDisposed) return@invokeLater
            runCatching {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("upstream.version.title"),
                        MpSqlVisualizerBundle.message(
                            "upstream.version.content",
                            describe(last ?: ""),
                            describe(current),
                        ),
                        NotificationType.INFORMATION,
                    )
                    .notify(project)
            }
        }
    }

    /** 测试重置（项目探测记忆清零）。 */
    internal fun resetForTest() {
        probedProjects.clear()
    }
}
