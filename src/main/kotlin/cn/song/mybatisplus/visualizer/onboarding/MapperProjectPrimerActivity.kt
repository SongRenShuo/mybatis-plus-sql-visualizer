package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable

/**
 * 首用引导（S2「首用即生效」第三项）：首次打开**带 MyBatis mapper 的工程**时给一次能力指路。
 *
 * 与既有两条引导的分工（避免重复打扰）：
 *  - [OnboardingAnchorTip]：**文件级**——首次打开含可预览 SQL 的文件时指路 Alt+Shift+Q；
 *  - [cn.song.mybatisplus.visualizer.log.FirstCaptureGuide]：**首次捕获成功**时指路日志面板；
 *  - 本类：**工程级**——项目一打开就判定「这是不是 MyBatis 工程」，是则指路状态栏入口 + 日志面板。
 *    三者触发维度互不重叠，同一用户至多各见一次。
 *
 * ## 防打扰三重约束（复用 NEW-19 [cn.song.mybatisplus.visualizer.update.VersionHighlights] 口径）
 *
 *  - **一次性 / 版本粒度持久化**：`PluginSettings.State.mapperPrimerVersion` 记录「已提示过的插件
 *    版本」；同版本永不重弹，版本升级后才允许再提示一次（与版本亮点同语义）。
 *    写入发生在通知**之前**（领取即消耗）：通知链任何异常都不会让同版本反复打扰。
 *  - **headless 静默**：`isUnitTestMode` 直接返回，**不探测、不写记忆**（测试不污染用户持久化状态）。
 *  - **应用级单次领取**：多项目同开时只有一个项目能领取本次展示权（[MapperProjectPrimer.tryClaim]）。
 *
 * ## 线程纪律（红线：探测不得在 EDT 做慢操作）
 *
 * mapper 探测走**非阻塞读动作 + smart mode**（[ReadAction.nonBlocking] + `inSmartMode`）：索引未
 * 就绪时平台先等索引（不阻塞 EDT），项目关闭自动取消（`expireWith`）。探测只遍历
 * [FileTypeIndex] 的 XML 文件名（O(1) 名称判定、命中即停），**不读文件内容、不 resolve PSI**；
 * 通知在 `invokeLater` 里弹（EDT）。
 *
 * ## 已知边界（如实登记）
 *
 * 只认**文件名**形如 `*Mapper.xml`（MyBatis 惯例）的 mapper 工程；纯注解（`@Select`）而无 mapper
 * XML 的工程不会被本引导命中——那类工程由文件级 [OnboardingAnchorTip] 在打开源码时兜住。
 * 不做全项目内容扫描：那是「项目打开即重活」，违背「不能拖慢启动」。
 */
class MapperProjectPrimerActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val app = ApplicationManager.getApplication() ?: return
        val version = PluginVersion.current()
        val lastVersion = PluginSettings.getInstance().state.mapperPrimerVersion
        val currentVersion = version ?: return
        if (!MapperProjectPrimer.shouldProbe(app.isUnitTestMode, currentVersion, lastVersion)) return
        ReadAction.nonBlocking(Callable { MapperProjectProbe.hasMapperXml(project) })
            .inSmartMode(project)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { found ->
                if (!found) return@onSuccess
                if (!MapperProjectPrimer.tryClaim(currentVersion)) return@onSuccess
                app.invokeLater {
                    if (project.isDisposed) return@invokeLater
                    notify(project)
                }
            }
    }

    private fun notify(project: Project) {
        runCatching {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("onboarding.mapperPrimer.title"),
                    MpSqlVisualizerBundle.message("onboarding.mapperPrimer.content"),
                    NotificationType.INFORMATION,
                )
            notification.addAction(
                NotificationAction.createSimpleExpiring(
                    MpSqlVisualizerBundle.message("onboarding.mapperPrimer.openPanel"),
                ) {
                    if (!project.isDisposed) {
                        ToolWindowManager.getInstance(project)
                            .getToolWindow(SqlLogManager.TOOL_WINDOW_ID)?.show()
                    }
                },
            )
            notification.notify(project)
        }
    }

    companion object {
        /** 与首用锚点引导共用「预览」通知组（一域一组：onboarding 文案归 preview 域）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
    }
}

/**
 * 首用引导的纯判定与领取（零平台依赖，headless 直接单测）。
 *
 * 「防打扰三重约束」中的两条（版本粒度、headless）在这里以纯函数固化；第三条（应用级单次领取）
 * 与持久化写入在 [tryClaim] 里完成——与 NEW-19 `VersionHighlightsActivity` 的 claim 同形状。
 */
internal object MapperProjectPrimer {

    private val CLAIM_LOCK = Any()

    @Volatile
    private var claimedVersion: String? = null

    /**
     * 是否进入探测（纯函数）：
     *  - headless（单测）→ 一律不探测（连探测都不做，更不写记忆）；
     *  - 插件版本未知（非打包环境）→ 不探测（无法做版本粒度记忆）；
     *  - 本版本已提示过（[lastPrimedVersion] 相同）→ 不探测（同版本永不重弹）。
     */
    fun shouldProbe(unitTestMode: Boolean, currentVersion: String?, lastPrimedVersion: String): Boolean =
        !unitTestMode && !currentVersion.isNullOrBlank() && currentVersion != lastPrimedVersion

    /**
     * 领取本版本展示权：成功则**先**把版本写入持久化记忆再放行通知（领取即消耗），返回 true。
     * 并发多项目只有一个能领到；同版本二次领取失败。
     */
    fun tryClaim(version: String): Boolean = synchronized(CLAIM_LOCK) {
        if (claimedVersion == version) return false
        claimedVersion = version
        PluginSettings.getInstance().state.mapperPrimerVersion = version
        true
    }

    /** 测试重置（会话记忆清零；不改持久化状态——调用方自备复原）。 */
    internal fun resetForTest() {
        synchronized(CLAIM_LOCK) { claimedVersion = null }
    }
}

/**
 * 工程是否「带 mapper」的探测（平台侧；**必须在读动作内调用**，由调用方保证 smart mode）。
 *
 * 判定只看文件名：MyBatis 惯例 `XxxMapper.xml`（大小写不敏感），命中即停——不读内容、不建 PSI。
 * 扫描面与 `MapperXmlLocator` 的「按 XML 文件类型走索引」一致（同一条 `FileTypeIndex`）。
 */
internal object MapperProjectProbe {

    /** 纯判定（测试缝）：是否为 mapper XML 文件名。 */
    fun isMapperXmlFileName(name: String): Boolean =
        name.length > ".xml".length && name.endsWith(".xml", ignoreCase = true) &&
            name.substring(0, name.length - ".xml".length).endsWith("Mapper", ignoreCase = true)

    /** 工程内是否存在 mapper XML（只读索引 + 名称判定，命中即停）。 */
    fun hasMapperXml(project: Project): Boolean {
        if (project.isDisposed) return false
        var found = false
        try {
            FileTypeIndex.processFiles(
                XmlFileType.INSTANCE,
                { virtualFile ->
                    if (isMapperXmlFileName(virtualFile.name)) {
                        found = true
                        false // 命中即停：不遍历其余 XML
                    } else {
                        true
                    }
                },
                GlobalSearchScope.projectScope(project),
            )
        } catch (e: IndexNotReadyException) {
            // inSmartMode 下不应发生；万一索引在取消窗口内失效，按「未命中」降级（不弹引导，无副作用）
            return false
        }
        return found
    }
}
