package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * VersionHighlightsActivity 触发面测试（20261104 262 沙盒验收缺口 2 收口，
 * 兑现账本矩阵 #9「headless/单测跳过逻辑归定向测试」备注）。
 *
 * 内容面（正文渲染/转义/动作文案键）由既有 [VersionHighlightsTest] 覆盖，此处不重复；
 * 这里钉**触发语义与记忆写回**（生产代码无 ForTest 缝，平台-free 驱动方式见各说明）：
 *
 *  - execute 四道闸：无 Application → return；`shouldNotify`（headless=unitTestMode 静默、
 *    同版本不重弹、版本推进才弹）→ 未登记版本静默 → 会话领取（claim，一个会话只弹一条）；
 *  - 「领取即消耗」：先写 `PluginSettings.state.lastHighlightedVersion` 再弹通知（写回发生在
 *    invokeLater 回调之前），通知链异常也不会让同版本重弹；
 *  - 通知按登记项 target 去重挂动作。
 *
 * **平台-free 驱动说明**：
 *  - Application 是接口 → `Proxy` 造假应用（`ApplicationManager.setApplication(app, parent)`
 *    挂 Disposer，teardown 恢复现场；PluginSettings/NotificationGroupManager 经假 app 的
 *    `getService` 注入真 PluginSettings 实例与记录型代理链）；
 *  - `PluginVersion.current()` 读 classpath 资源（本环境=2026.11.3，未登记）且带缓存 →
 *    反射写 `cached` 钉成已登记版本（finally 还原）；
 *  - `claim`/`claimedVersion` 是私有伴生状态 → 反射驱动（生产同模块私有成员）。
 */
class VersionHighlightsActivityTest {

    companion object {
        /** HIGHLIGHTS_BY_VERSION 已登记的版本（亮点 3 条）。 */
        private const val REGISTERED_VERSION = "2026.10.31"

        /** 未登记版本（静默分支用）。 */
        private const val UNREGISTERED_VERSION = "2099.01.01"

        /** 升级前记忆的旧版本（版本推进分支用）。 */
        private const val PREVIOUS_VERSION = "2026.10.30"
    }

    private class Capture {
        val callbacks = mutableListOf<Runnable>()

        /** 经 Notifications 总线送达的通知（MessageBus 代理的 syncPublisher 捕获）。 */
        val delivered = mutableListOf<Notification>()
    }

    private val parents = mutableListOf<com.intellij.openapi.Disposable>()

    /** 每次安装假应用前抓的原始 Application（本环境恒 null；setApplication 的 Disposer 恢复只还原非 null 旧值）。 */
    private val originalApps = mutableListOf<Application?>()

    @After
    fun tearDown() {
        VersionHighlightsActivity.resetForTest()
        while (parents.isNotEmpty()) {
            Disposer.dispose(parents.removeAt(parents.size - 1))
        }
        // setApplication(instance, parent) 的恢复回调是 `if (old != null)`——纯 JUnit 环境旧值为 null
        // 时 dispose 不会还原，必须显式归位，否则假应用泄漏给同 JVM 后续测试（平台测试全程持写意图）
        if (originalApps.isNotEmpty()) {
            ApplicationManager.setApplication(originalApps.removeAt(originalApps.size - 1) as Application?)
        }
        assertEquals("假应用必须全部恢复", 0, parents.size)
        assertEquals("原始应用全部还原", 0, originalApps.size)
    }

    // ---------- 平台-free 驱动设施 ----------

    private fun proxyOf(type: Class<*>, handler: (method: java.lang.reflect.Method, args: Array<Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "${type.simpleName}Proxy(VersionHighlightsActivityTest)"
                else -> handler(method, args)
            }
        }

    private fun messageBusProxy(capture: Capture): MessageBus =
        proxyOf(MessageBus::class.java) { method, args ->
            when (method.name) {
                "syncPublisher" -> proxyOf(Notifications::class.java) { _, a ->
                    (a?.getOrNull(0) as? Notification)?.let { capture.delivered.add(it) }
                    null
                }
                else -> null
            }
        } as MessageBus

    /**
     * 造一个真实 [NotificationGroup]：类（Kotlin，非接口）不可代理；公共次构造器全部
     * `registerGroup=true`（会注册进平台全局表且 pluginId 初始化要走 PluginUtil）。
     * 反射走私有主构造器 `registerGroup=false`，且**必须在假应用安装前**（app==null 时
     * `ApplicationManager.getApplication()?.let{…}` 短路，pluginId 直取 null，零平台调用）。
     */
    private fun newNotificationGroup(groupId: String): com.intellij.notification.NotificationGroup {
        val ctor = com.intellij.notification.NotificationGroup::class.java.getDeclaredConstructor(
            String::class.java,
            com.intellij.notification.NotificationDisplayType::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            com.intellij.openapi.extensions.PluginId::class.java,
            Boolean::class.javaPrimitiveType,
        )
        ctor.isAccessible = true
        return ctor.newInstance(
            groupId,
            com.intellij.notification.NotificationDisplayType.BALLOON,
            true,
            null,
            null,
            null,
            false,
        ) as com.intellij.notification.NotificationGroup
    }

    /** 假应用：isUnitTestMode/isDispatchThread 可配，invokeLater 记录不执行，getService 注入真 PluginSettings。 */
    private fun installFakeApp(unitTestMode: Boolean, settings: PluginSettings): Capture {
        val capture = Capture()
        // 组必须趁应用未装时构造（见 newNotificationGroup 注释）
        val group = newNotificationGroup("cn.song.mybatisplus.visualizer.updates")
        val app = proxyOf(Application::class.java) { method, args ->
            when (method.name) {
                "isUnitTestMode" -> unitTestMode
                "isDispatchThread" -> true
                "isHeadless" -> true
                "isDisposed" -> false
                "invokeLater" -> { capture.callbacks.add(args!![0] as Runnable); null }
                "getService" -> when (args!![0] as Class<*>) {
                    PluginSettings::class.java -> settings
                    NotificationGroupManager::class.java -> proxyOf(NotificationGroupManager::class.java) { m, _ ->
                        if (m.name == "getNotificationGroup") group else null
                    }
                    else -> null
                }
                "getMessageBus" -> messageBusProxy(capture)
                else -> null
            }
        }
        val parent = Disposer.newDisposable()
        originalApps.add(ApplicationManager.getApplication())
        ApplicationManager.setApplication(app as Application, parent)
        parents.add(parent)
        return capture
    }

    /** Project 接口桩：alive + 非 default + 记录型 MessageBus（Notifications.Bus 走 project 分支）。 */
    private fun projectStub(capture: Capture): Project =
        proxyOf(Project::class.java) { method, _ ->
            when (method.name) {
                "isDisposed" -> false
                "isDefault" -> false
                "getMessageBus" -> messageBusProxy(capture)
                else -> null
            }
        } as Project

    /** 钉 PluginVersion 缓存（object 单例私有字段；finally 还原原值，防污染同 JVM 其他测试）。 */
    private inline fun <T> withPinnedVersion(version: String?, block: () -> T): T {
        val cls = PluginVersion::class.java
        val singleton = cls.getDeclaredField("INSTANCE").get(null)
        val field = cls.getDeclaredField("cached")
        field.isAccessible = true
        val old = field.get(singleton)
        field.set(singleton, version)
        try {
            return block()
        } finally {
            field.set(singleton, old)
        }
    }

    /** 反射驱动私有 claim（会话领取守卫的状态机本体）。 */
    private fun claimDirect(version: String): Boolean {
        val companion = VersionHighlightsActivity.Companion
        val method = VersionHighlightsActivity.Companion::class.java.getDeclaredMethod("claim", String::class.java)
        method.isAccessible = true
        return method.invoke(companion, version) as Boolean
    }

    private fun claimedVersionOrNull(): String? {
        // 注意：companion 私有 var 的背衬字段被 Kotlin 提升到**外部类**静态字段（javap 实证：
        // VersionHighlightsActivity 上 `private static volatile String claimedVersion`，Companion 上无此字段）
        val field = VersionHighlightsActivity::class.java.getDeclaredField("claimedVersion")
        field.isAccessible = true
        return field.get(null) as String?
    }

    // ---------- 触发语义 ----------

    @Test
    fun test首装或升级首启触发一次并回写持久化版本() = withPinnedVersion(REGISTERED_VERSION) {
        val settings = PluginSettings()
        assertEquals("前置：新装记忆为空", "", settings.state.lastHighlightedVersion)
        val capture = installFakeApp(unitTestMode = false, settings)
        VersionHighlightsActivity.resetForTest()

        runBlocking { VersionHighlightsActivity().execute(projectStub(capture)) }

        assertEquals("应触发一次（一次回调=一次通知投递计划）", 1, capture.callbacks.size)
        assertEquals("回写持久化值：lastHighlightedVersion=当前版本", REGISTERED_VERSION, settings.state.lastHighlightedVersion)
        assertEquals("会话领取已消耗", REGISTERED_VERSION, claimedVersionOrNull())
    }

    @Test
    fun test已高亮同版本不再触发() = withPinnedVersion(REGISTERED_VERSION) {
        val settings = PluginSettings()
        settings.state.lastHighlightedVersion = REGISTERED_VERSION
        val capture = installFakeApp(unitTestMode = false, settings)

        runBlocking { VersionHighlightsActivity().execute(projectStub(capture)) }

        assertEquals("同版本不重弹", 0, capture.callbacks.size)
        assertEquals("记忆不被改写", REGISTERED_VERSION, settings.state.lastHighlightedVersion)
    }

    @Test
    fun test版本推进后再次触发() = withPinnedVersion(REGISTERED_VERSION) {
        val settings = PluginSettings()
        settings.state.lastHighlightedVersion = PREVIOUS_VERSION
        val capture = installFakeApp(unitTestMode = false, settings)
        VersionHighlightsActivity.resetForTest()

        runBlocking { VersionHighlightsActivity().execute(projectStub(capture)) }

        assertEquals("版本推进 → 再提示一次", 1, capture.callbacks.size)
        assertEquals("记忆推进到当前版本", REGISTERED_VERSION, settings.state.lastHighlightedVersion)
    }

    // ---------- headless / 单测跳过分支（判据按实现：app 为空 → return；isUnitTestMode → shouldNotify false） ----------

    @Test
    fun test单测模式静默且不写记忆() = withPinnedVersion(REGISTERED_VERSION) {
        val settings = PluginSettings()
        val capture = installFakeApp(unitTestMode = true, settings)
        VersionHighlightsActivity.resetForTest()

        runBlocking { VersionHighlightsActivity().execute(projectStub(capture)) }

        assertEquals("unitTestMode 下不弹", 0, capture.callbacks.size)
        assertEquals("且不写记忆（测试不污染持久化状态）", "", settings.state.lastHighlightedVersion)
        assertNull("且不领取会话展示权", claimedVersionOrNull())
    }

    @Test
    fun test无应用环境静默跳过() = withPinnedVersion(REGISTERED_VERSION) {
        // T-PerfSweep30 收口：本用例的前提是「进程里没有 Application」，只在纯 JUnit 进程成立。
        // 全量门禁的分片 JVM 里，同片前序平台测试会创建 Application（headless unit test），
        // 前提不成立——用 assume 跳过而不是让它假红（原断言在 testShards 上必红，属既有顺序脆弱）。
        org.junit.Assume.assumeTrue(
            "分片 JVM 已有 Application（前序平台测试创建），本用例前提不成立，跳过",
            ApplicationManager.getApplication() == null,
        )
        val settings = PluginSettings()
        VersionHighlightsActivity.resetForTest()

        // 不装假应用：execute 第一道闸 app==null → return，不得抛异常
        runBlocking { VersionHighlightsActivity().execute(projectStub(Capture())) }

        assertEquals("", settings.state.lastHighlightedVersion)
        assertNull(claimedVersionOrNull())
    }

    @Test
    fun test未登记版本静默且不领取() = withPinnedVersion(UNREGISTERED_VERSION) {
        val settings = PluginSettings()
        val capture = installFakeApp(unitTestMode = false, settings)
        VersionHighlightsActivity.resetForTest()

        runBlocking { VersionHighlightsActivity().execute(projectStub(capture)) }

        assertEquals("宁缺勿滥：未登记版本不弹", 0, capture.callbacks.size)
        assertEquals("也不写记忆", "", settings.state.lastHighlightedVersion)
        assertNull("更不领取会话展示权（若改在亮点查找前领取，此断言红）", claimedVersionOrNull())
    }

    // ---------- 领取即消耗：先写记忆后弹通知；动作按入口去重 ----------

    @Test
    fun test先落记忆再弹通知且动作按入口去重() = withPinnedVersion(REGISTERED_VERSION) {
        val settings = PluginSettings()
        val capture = installFakeApp(unitTestMode = false, settings)
        VersionHighlightsActivity.resetForTest()

        runBlocking { VersionHighlightsActivity().execute(projectStub(capture)) }

        // 回调尚未运行（invokeLater 只记录不执行）时记忆已写——「领取即消耗」的时序证据
        assertEquals(REGISTERED_VERSION, settings.state.lastHighlightedVersion)
        assertEquals(1, capture.callbacks.size)

        capture.callbacks.forEach { it.run() }

        // 通知经真实 NotificationGroup 构造、经 Notifications.Bus 送达（MessageBus 代理捕获）——
        // 标题/正文/动作都是插件自己的判定面
        assertEquals("应经总线送达 1 条通知", 1, capture.delivered.size)
        val notification = capture.delivered[0]
        assertEquals(
            "标题 = 「本版亮点」+ 插件名",
            MpSqlVisualizerBundle.message("update.highlights.title", MpSqlVisualizerBundle.message("plugin.name")),
            notification.title,
        )
        assertTrue("正文含版本行", notification.content.contains(REGISTERED_VERSION))
        assertTrue("正文为亮点列表", notification.content.contains("<li>"))
        val expectedLabels = VersionHighlights.highlightsFor(REGISTERED_VERSION)
            .map { it.target }.distinct()
            .map { MpSqlVisualizerBundle.message(VersionHighlights.actionLabelKey(it)) }
        assertEquals(
            "动作按登记项入口去重、顺序即登记序",
            expectedLabels,
            notification.actions.map { it.templatePresentation.text },
        )
    }

    // ---------- 会话领取守卫（claim 状态机，反射直驱） ----------

    @Test
    fun test会话领取守卫_同版本仅领一次() {
        VersionHighlightsActivity.resetForTest()
        assertTrue("首次领取成功", claimDirect(REGISTERED_VERSION))
        assertFalse("同版本第二次领取必须失败（多项目并发只弹一条）", claimDirect(REGISTERED_VERSION))
        assertTrue("不同版本可领取", claimDirect(PREVIOUS_VERSION))
        VersionHighlightsActivity.resetForTest()
        assertTrue("重置后可再领取（resetForTest 契约）", claimDirect(REGISTERED_VERSION))
    }
}
