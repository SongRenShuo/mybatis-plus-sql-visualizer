package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SettingsTruth P1/P2 守卫测试（纯逻辑，无平台依赖，进 testFast 快测桶）：
 *  - State 不再含 autoInjectSqlSourceInterceptor 字段（写而不读的假开关防回潮——反射断言字段不存在）
 *  - 双语 bundle tooltip 均含「不保存状态 / does not persist」声明（properties 文本断言）
 *
 * P2 的 statusSummary 语义态由 T-CaptureTruth T-CT-01 拆为纯函数 [AgentManagerStatusText] 后
 * 改由运行期测试覆盖（`log/agent/AgentStatusTextTest`，五态全分支），本文件只留**接线**类源码断言。
 *
 * **T-CaptureTruth T-CT-01 附带修复**：本类三个用例此前**从未被执行**——既无 `@Test` 注解也不继承
 * JUnit3 `TestCase`，任何 runner 都不会发现它们（全仓 test-results XML 中零出现为证），
 * 属「守卫存在但从未运行」的假守卫；而其中「statusSummary 无会话应返回 null」这条断言恰好把
 * T-CaptureTruth 的缺陷（开着但未授权 → 状态行空白）钉成了「期望行为」。现补 `@Test` 使守卫真正生效。
 */
class SettingsTruthTest {

    // ---------- P1 ----------

    @Test
    fun testP1_state无autoInjectSqlSourceInterceptor字段() {
        val declared = PluginSettings.State::class.java.declaredFields.map { it.name }
        assertFalse(
            "State 不得再声明 autoInjectSqlSourceInterceptor（纯动作开关不持久化）",
            declared.any { it.equals("autoInjectSqlSourceInterceptor", ignoreCase = true) },
        )
    }

    @Test
    fun testP1_tooltip双语均声明不保存状态() {
        val zh = javaClass.getResourceAsStream("/messages/MpSqlVisualizerBundle_zh.properties")
            ?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.firstOrNull { it.startsWith("settings.log.autoInjectInterceptor.tip=") }
            }
        val en = javaClass.getResourceAsStream("/messages/MpSqlVisualizerBundle.properties")
            ?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.firstOrNull { it.startsWith("settings.log.autoInjectInterceptor.tip=") }
            }
        assertTrue("zh tooltip 应存在", zh != null)
        assertTrue("en tooltip 应存在", en != null)
        assertTrue("zh tooltip 应声明「本开关不保存状态」", zh!!.contains("不保存状态"))
        assertTrue("en tooltip 应声明 does not persist", en!!.contains("does not persist"))
    }

    @Test
    fun testP1_本地存储披露双语与真实接线() {
        fun messages(resource: String) = java.util.Properties().apply {
            this@SettingsTruthTest.javaClass.getResourceAsStream("/messages/$resource")!!
                .bufferedReader(Charsets.UTF_8).use(::load)
        }
        val zh = messages("MpSqlVisualizerBundle_zh.properties")
        val en = messages("MpSqlVisualizerBundle.properties")
        val keys = listOf(
            "settings.storage.captureFiles", "settings.storage.previewHistory",
            "settings.storage.paramHistory", "settings.storage.clearBoundary",
        )
        // k2j 互操作：master 的 disclosure(key, action) 单行调用在 Java 侧为
        // addDisclosure(p, g, message(key), self.<button>) 两行形态，用带 \s* 的正断等价钉住。
        val sections = mainSrc("cn/song/mybatisplus/visualizer/config/PluginSettingsPanelSections.java")
        val actionByKey = mapOf(
            keys[0] to "openCaptureDirectoryButton",
            keys[1] to "clearPreviewHistoryButton",
            keys[2] to "clearParamHistoryButton",
        )
        keys.forEach { key ->
            val call = actionByKey[key]?.let {
                Regex("addDisclosure\\(p, g, MpSqlVisualizerBundle\\.message\\(\"$key\"\\),\\s*self\\.$it\\)")
            } ?: Regex("addDisclosure\\(p, g, MpSqlVisualizerBundle\\.message\\(\"$key\"\\),\\s*null\\)")
            assertTrue("设置页须可见地接线说明及独立动作 $key", call.containsMatchIn(sections))
            assertTrue("中文披露缺失 $key", !zh.getProperty(key).isNullOrBlank())
            assertTrue("英文披露缺失 $key", !en.getProperty(key).isNullOrBlank())
        }
        assertTrue(zh.getProperty(keys[0]).contains("默认不自动保存"))
        assertTrue(en.getProperty(keys[0]).contains("off by default"))
        assertTrue(zh.getProperty(keys[1]).contains("mp-sql-visualizer.xml"))
        assertTrue(en.getProperty(keys[2]).contains("plaintext JSON"))
        assertTrue(zh.getProperty(keys[3]).contains("不会清除上述三类"))
        assertTrue(en.getProperty(keys[3]).contains("not these three"))
        listOf(zh, en).forEach { bundle ->
            assertFalse("自动保存提示不应称仅淘汰时写文件", bundle.getProperty("settings.log.autoSave.tip").contains("被淘汰的 SQL") ||
                bundle.getProperty("settings.log.autoSave.tip").contains("entries evicted"))
            assertFalse("历史空态不应称仅淘汰时存档", bundle.getProperty("log.history.files.empty").contains("被淘汰的 SQL") ||
                bundle.getProperty("log.history.files.empty").contains("evicted SQL"))
        }
        // 文案与真实写入/清理边界同时守卫，防止只改文字却漏掉实际独立持久化链。
        // k2j 互操作：Kotlin 属性赋值 buffer.onEntryStored = { ... } 翻为 setOnEntryStored(方法引用)。
        assertTrue(mainSrc("cn/song/mybatisplus/visualizer/log/SqlLogManager.java")
            .contains("buffer.setOnEntryStored(autoSaver::submit)"))
        val settings = mainSrc("cn/song/mybatisplus/visualizer/config/PluginSettings.java")
        assertTrue(settings.contains("private boolean logAutoSaveEnabled = false;"))
        assertTrue(settings.contains("private String paramHistoryJson"))
        assertTrue(settings.contains("private String paramSchemesJson"))
        // k2j 互操作：@Storage 注解形态（Kotlin 侧为 Storage("mp-sql-visualizer.xml")）。
        assertTrue(mainSrc("cn/song/mybatisplus/visualizer/history/SqlHistoryManager.java")
            .contains("Storage(value = \"mp-sql-visualizer.xml\""))
        val clear = mainSrc("cn/song/mybatisplus/visualizer/util/PluginCacheManager.java")
        assertFalse(clear.contains("SqlHistoryManager.clear("))
        assertFalse(clear.contains("ParamHistoryCache.clear("))
        assertTrue(java.io.File("README.md").readText().contains("两者均在 IDE 应用配置目录"))
    }

    @Test
    fun testP1_旧方言状态键不能被新键前缀伪装为已消费() {
        listOf("MpSqlVisualizerBundle.properties", "MpSqlVisualizerBundle_zh.properties").forEach { name ->
            val lines = javaClass.getResourceAsStream("/messages/$name")!!
                .bufferedReader(Charsets.UTF_8).use { it.readLines() }
            assertFalse("$name: 父键已无人消费，不能因新键前缀而假绿",
                lines.any { it.startsWith("settings.dialect.inferred=") })
            assertFalse("$name: 旧空态键已被有证据的分类状态取代",
                lines.any { it.startsWith("settings.dialect.inferred.none=") })
        }
    }

    @Test
    fun testP1_执行超时说明不能冒充原生控制台取消护栏() {
        fun read(resource: String) = java.util.Properties().apply {
            this@SettingsTruthTest.javaClass.getResourceAsStream("/messages/$resource")!!.bufferedReader(Charsets.UTF_8).use(::load)
        }.getProperty("settings.preview.executionTimeout.tip")
        val zh = read("MpSqlVisualizerBundle_zh.properties")
        val en = read("MpSqlVisualizerBundle.properties")
        assertTrue("中文设置须明确原生控制台不受插件超时保护", zh.contains("原生控制台") && zh.contains("不受"))
        assertTrue("英文设置须明确原生控制台不受插件超时保护", en.contains("native console") && en.contains("not"))
        assertTrue("适用的 MCP 通道仍须在文案中可见", zh.contains("MCP") && en.contains("MCP"))
    }

    @Test
    fun testP1_全局方言状态不得取首项目首数据源() {
        // k2j 互操作：锚点从 internal fun 换为 Java 方法签名；区间收窄到 refreshInferredLabel
        // 方法体（master 原区间止于 isModifiedPreview，Java 侧中间夹着 refreshCacheInfo/
        // triggerInterceptorInjection 等合法取首项目的无关方法，负向断言须只盯状态行本体）。
        val section = mainSrc("cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.java")
            .substringAfter("public void refreshInferredLabel()")
            .substringBefore("public void selectLanguage(")
        assertFalse("全局设置不能把首个项目冒充当前项目", section.contains("getOpenProjects()[0]"))
        assertFalse("全局状态不能取数据源首个 URL", section.contains("DataSourceUrlProbe.firstUrl("))
        assertTrue("状态应消费项目候选共识决策", section.contains("DialectRegistry.INSTANCE") && section.contains(".decision("))
        assertTrue("多项目不得偷取其一的决策", section.contains("projects.size() == 1"))
    }

    /**
     * T-DialectLabelStale（2026-10-27 用户真机复现）：方言下拉切到具体方言时，
     * 推断状态只读行必须同步刷新。原实现只在「切回智能推断」分支刷新，
     * 切到 Oracle 等具体方言时副标题停在旧文案，直到面板重绘/apply 后的 reset() 才追上。
     */
    @Test
    fun testT_DialectLabelStale_下拉切具体方言也刷新状态行() {
        // k2j 互操作：行锚点带 self. 前缀；Kotlin 尾 lambda 换为 Java lambda 形态。
        val src = stripComments(
            mainSrc("cn/song/mybatisplus/visualizer/config/PluginSettingsPanelSections.java"),
        )
        val listener = src.substringAfter("addRow(p, g, \"\", self.dialectInferredLabel)")
            .substringBefore("addRow(p, g, \"\", self.replaceParamsCheck)")
        assertTrue(
            "方言下拉监听必须无条件刷新推断状态行（不得只判 autoDialectItem 分支）",
            listener.contains("dialectCombo.addActionListener(e -> self.refreshInferredLabel())"),
        )
        assertFalse(
            "切到具体方言时不得跳过刷新（不得回潮按哨兵项判等的条件刷新）",
            listener.contains("selectedItem == autoDialectItem") ||
                listener.contains("getSelectedItem(), autoDialectItem"),
        )
    }

    // ---------- P2 / T-CaptureTruth T-CT-01/02：接线源码守卫（语义走 AgentStatusTextTest） ----------

    private fun mainSrc(rel: String) = java.io.File("src/main/java/$rel").readText()

    /** 剥 // 行注释与块注释——守卫扫的是代码事实，注释里写「不得如何」不该被判违规。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.split('\n').joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    @Test
    fun testP2_状态行接线与五态键源码守卫() {
        // k2j 互操作：master 的 AgentManager.kt 单文件在这里拆为 AgentManager.java（类）+
        // AgentManagerKt.java（顶层函数 agentStatusText），接线断言读合并文本，等价同文件。
        // T-M53-II（2026-10-10）master 侧同区把 AgentStatusSnapshot 等支撑类型搬出到
        // AgentSessionSupport.kt——k2j 侧该类型落独立文件 AgentStatusSnapshot.java
        // （存在性由编译期保证），读取面维持两文件并读不变。
        val src = mainSrc("cn/song/mybatisplus/visualizer/log/agent/AgentManager.java") + "\n" +
            mainSrc("cn/song/mybatisplus/visualizer/log/agent/AgentManagerKt.java")
        // statusSummary 只取数，文案交纯函数（纯函数五态语义由 AgentStatusTextTest 运行期覆盖）
        assertTrue("AgentManager 应声明 statusSummary", src.contains("String statusSummary()"))
        assertTrue("statusSummary 应委托纯函数 agentStatusText", src.contains("agentStatusText("))
        // data class 快照翻为独立文件 AgentStatusSnapshot.java（存在性由编译期保证），这里钉接线调用
        assertTrue("应有状态行输入快照 AgentStatusSnapshot", src.contains("new AgentStatusSnapshot("))
        // 五态文案键齐备（不许再出现「无会话恒 null」的收敛）
        listOf(
            "settings.enhancedCapture.status.attached",
            "settings.enhancedCapture.status.fellBack",
            "settings.enhancedCapture.status.authorizedWaiting",
            // T-AgentHeartbeat T-05：心跳两因（停摆 / 首条心跳未到）必须各有独立键
            "settings.enhancedCapture.status.heartbeatLost",
            "settings.enhancedCapture.status.firstHeartbeatPending",
        ).forEach { key ->
            assertTrue("agentStatusText 应引用状态行键 $key", src.contains("\"$key\""))
        }
        // T-EnhancedCaptureDefaultOn：确认流与重新授权入口整体移除，不得回潮
        assertFalse("不得再出现 requestReauthorization", src.contains("requestReauthorization("))
        assertFalse("不得再出现 confirmed 判定", src.contains("isEnhancedConfirmed"))
        // Topic 声明形态（仿 PluginSettings.SETTINGS_CHANGE_TOPIC）
        assertTrue("AGENT_STATUS_TOPIC 应为 Topic.create", src.contains("Topic<AgentStatusListener> AGENT_STATUS_TOPIC = Topic.create("))
    }

    @Test
    fun testP2_状态变更广播点覆盖同意流三态() {
        // k2j 互操作（同 testP2_状态行接线与五态键源码守卫）：master 的 AgentManager.kt 单文件
        // 在这里拆为 AgentManager.java（类）+ AgentManagerKt.java（顶层函数），守卫读合并文本。
        val src = mainSrc("cn/song/mybatisplus/visualizer/log/agent/AgentManager.java") + "\n" +
            mainSrc("cn/song/mybatisplus/visualizer/log/agent/AgentManagerKt.java")
        // 广播点：定义 1 + attach 成功 1 + sweepOffline 1 + shutdownAll 1 + setUserPaused 1
        // T-M56Phase2-Premain（合流后 +1）：premain 回连建会话成功亦广播一次状态行（启动即捕获生效）
        // T-EnhancedCaptureDefaultOn：确认流/重新授权两处广播随 B1 摘除（master 同口径 6 处）
        val count = Regex("publishStatusChanged\\(\\)").findAll(src).count()
        assertTrue("publishStatusChanged 应有 6 处（定义 1 + 调用 5，实际 $count）", count == 6)
    }

    @Test
    fun testP2_面板接线与不得自注册Disposer() {
        val panelSrc = stripComments(mainSrc("cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.java"))
        assertTrue("面板应订阅 AGENT_STATUS_TOPIC", panelSrc.contains("AgentManager.AGENT_STATUS_TOPIC"))
        assertTrue("面板应有 disposeResources", panelSrc.contains("void disposeResources()"))
        assertTrue("面板 reset 应拉取状态行", panelSrc.contains("refreshEnhancedStatusLabel()"))
        // T-CaptureTruth T-CT-05：面板不得把自身注册进 Disposer 树——父节点未注册会落 ROOT_DISPOSABLE
        // 子节点而 Configurable 链路无 dispose 面板的动作，IDE 退出即报 Memory leak（沙盒实测 6 例）
        assertFalse(
            "面板不得 Disposer.register(this, …)（ROOT 子节点无释放路径 → 泄漏）",
            panelSrc.contains("Disposer.register(this"),
        )
        // T-EnhancedCaptureDefaultOn：重新授权按钮已移除，不得回潮
        assertFalse("面板不得再有重新授权接线", panelSrc.contains("requestReauthorization()"))
        val secSrc = mainSrc("cn/song/mybatisplus/visualizer/config/SectionConfigurable.java")
        assertTrue("SectionConfigurable.disposeUIResources 应调 panel.disposeResources()",
            Regex("disposeUIResources[\\s\\S]{0,200}panel\\.disposeResources\\(\\)").containsMatchIn(secSrc))
        // P1 面板三处绑定已删
        assertFalse("面板不得再写 autoInjectSqlSourceInterceptor", panelSrc.contains("autoInjectSqlSourceInterceptor"))
    }
}
