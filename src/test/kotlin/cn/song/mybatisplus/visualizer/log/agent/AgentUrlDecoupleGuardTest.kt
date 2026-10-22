package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PS5-04：AgentManager 常驻面解耦源码守卫（静态哨兵断言，普通 JUnit 零平台 fixture）。
 *
 * 解耦目标（第八轮报告发现 4）：方言智能推断默认开启后，「预览一次」即经
 * `AgentManager.getInstance` 实例化项目级服务，连带 executor + monitor 两条守护线程与
 * SETTINGS 监听常驻——增强捕获从未使用也常驻。运行期难以断言「类未被初始化」（服务可能被
 * 其它链路合法实例化），故用**源码级哨兵**把关：
 *  1. 推断读点（`sql/DialectRegistry.java`、`config/PluginSettingsPanel.java`）不得再引用 AgentManager
 *     ——读推断 URL 只能走 [AgentUrlRegistry]（零服务实例化）；
 *  2. 持有者自身零 AgentManager 依赖（避免反向把服务拖起来）；
 *  3. monitor 定时任务只允许出现在 `ensureMonitorStarted` 内（懒启红线：不得回到 init 排定）。
 *
 * k2j 适配（生产树为 Java，扫描根/扩展名/锚点随树改写）：
 *  - 扫描根 `src/main/kotlin` → `src/main/java`，源文件后缀 `.kt` → `.java`；
 *  - master 的 `sql/Dialect.kt` 同时承载 `interface Dialect` 与 `object DialectRegistry`，k2j 拆成
 *    `sql/Dialect.java` + `sql/DialectRegistry.java`——推断读点落在 `DialectRegistry.java`；
 *  - Java 声明的锚点形态（`public void ensureMonitorStarted` / `public void refreshInferredLabel()` /
 *    `private boolean isModifiedPreview`）与 Kotlin 的 `fun ...` 不同；
 *  - `onUrlCaptured = { syncInferenceUrl() }` 的 Java 形态是方法引用 `this::syncInferenceUrl`；
 *  - 设置页里 `AgentUrlRegistry` 与 `.lastConnectionUrl` 被换行拆开，改用容忍空白的正则。
 */
class AgentUrlDecoupleGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（守卫锚点必须选代码实体，不能选注释字面量）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun source(relPath: String): String =
        stripComments(repoRoot().resolve("src/main/java/cn/song/mybatisplus/visualizer/$relPath").readText())

    /** 容忍换行/空白的成员访问（Java 形态里 `AgentUrlRegistry` 与 `.lastConnectionUrl` 可能分行）。 */
    private fun containsMemberAccess(code: String, owner: String, member: String): Boolean =
        Regex("${Regex.escape(owner)}\\s*\\.\\s*${Regex.escape(member)}").containsMatchIn(code)

    @Test
    fun `推断读点不再引用AgentManager`() {
        val dialect = source("sql/DialectRegistry.java")
        assertTrue(
            "sql/DialectRegistry.java 读 agentUrl 必须走 AgentUrlRegistry（不得再经 AgentManager.getInstance 实例化服务）",
            !dialect.contains("AgentManager"),
        )
        assertTrue(
            "sql/DialectRegistry.java 应经持有者读取",
            containsMemberAccess(dialect, "AgentUrlRegistry", "lastConnectionUrl"),
        )

        // 设置页：**仅推断状态行**不得实例化 AgentManager——该文件另有增强捕获状态行，读
        // AgentManager.statusSummary() 是其正当职责（设置页展示增强捕获状态），故按函数体收窄。
        val panel = source("config/PluginSettingsPanel.java")
        val inferredBody = panel.substring(
            panel.indexOf("public void refreshInferredLabel()"),
            panel.indexOf("private boolean isModifiedPreview"),
        )
        assertTrue(
            "设置页推断状态行不得实例化 AgentManager（仅展示状态不该带起守护线程）",
            !inferredBody.contains("AgentManager"),
        )
        // master 82ee88b1：方言决策/状态文案统一走 DialectRegistry.decision + DialectSettingsStatusText
        assertTrue("设置页必须以项目为单位取得方言决策",
            inferredBody.contains("DialectRegistry.INSTANCE") && inferredBody.contains("decision("))
        assertTrue("设置页必须将同一决策交给状态文案层",
            inferredBody.contains("DialectSettingsStatusText.describe(projects.size(), decision)"))
    }

    @Test
    fun `持有者零AgentManager依赖`() {
        val registry = source("log/agent/AgentUrlRegistry.java")
        assertTrue("AgentUrlRegistry 不得引用 AgentManager（否则解耦失效）", !registry.contains("AgentManager"))
    }

    @Test
    fun `monitor定时任务只在懒启函数内排定`() {
        val manager = source("log/agent/AgentManager.java")
        val scheduleCount = Regex("scheduleWithFixedDelay").findAll(manager).count()
        assertEquals("monitor 定时任务排定点唯一（懒启）", 1, scheduleCount)
        val scheduleIdx = manager.indexOf("scheduleWithFixedDelay")
        val ensureIdx = manager.indexOf("public void ensureMonitorStarted")
        assertTrue("排定点必须落在 ensureMonitorStarted 内", ensureIdx in 0 until scheduleIdx)
        // 懒启的调用点：两条会话建立链（attach / premain）各一次 + 关开关分流（T-PremainReattach S4：
        // 暂停通道驻留时保持巡检，暂停通道清空后仍走 stopMonitor 复位——排定红线不受影响）
        assertTrue("attach 链建立会话后应懒启巡检", manager.contains("ensureMonitorStarted()"))
        assertEquals(
            "ensureMonitorStarted 定义 1 + 调用 3（attach/premain/shutdownAll 暂停通道驻留保巡检）",
            4,
            Regex("ensureMonitorStarted\\(\\)").findAll(manager).count(),
        )
        // 推断 URL 回写点：会话建立/URL 捕获/摘除/关闭/销毁
        assertTrue("会话生命周期点须回写推断 URL", manager.contains("syncInferenceUrl()"))
        assertTrue("URL 捕获回调须回写推断 URL", manager.contains("this::syncInferenceUrl"))
        assertTrue(
            "会话生命周期收口（attach/premain/摘除/关闭/销毁 五处）应含探测缓存失效",
            Regex("onSessionLifecycleChanged\\(\\)").findAll(manager).count() >= 5,
        )
    }
}
