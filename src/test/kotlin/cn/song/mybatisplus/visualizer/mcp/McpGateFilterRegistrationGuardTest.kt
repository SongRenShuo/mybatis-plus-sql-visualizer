package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-McpGateDynamicFilter（O-68）注册结构守卫（纯逻辑，testFast 桶）。钉住三条结构不变量：
 *
 *  1. plugin.xml 对 `com.intellij.mcpServer` 只允许**一条** optional depends——第二条会被
 *     verifier 判 "duplicated dependency" 结构缺陷（2026-09-23 verifyGate 实测），且双片段
 *     加载形态不可靠；过滤注册必须走 mcp-optional.xml 内的探测式 McpGateFilterRegistrar；
 *  2. mcp-optional.xml 内**不得**直接 XML 声明 `mcpToolFilterProvider`——253 线无此 EP，
 *     未知 EP 会拒载整文件连坐十个 toolset（idea-2025.3.5 mcpserver.jar 本地实测）；
 *  3. 探测式注册的 registrar 与桥类在册（改名漂移会让注册静默失效）。
 */
class McpGateFilterRegistrationGuardTest {

    private val pluginXml = File("src/main/resources/META-INF/plugin.xml")
    private val mcpOptional = File("src/main/resources/META-INF/mcp-optional.xml")

    @Test
    fun `mcpServer依赖只允许一条optional声明`() {
        val text = pluginXml.readText()
        val count = Regex(">com\\.intellij\\.mcpServer<").findAll(text).count()
        assertEquals("com.intellij.mcpServer depends 声明必须恰好一条（第二条 = duplicated dependency 结构缺陷）", 1, count)
        assertFalse("独立过滤片段文件应已移除（改走探测式注册）", File("src/main/resources/META-INF/mcp-filter-optional.xml").exists())
    }

    @Test
    fun `过滤EP不得XML直宣且注册器必须在册`() {
        val text = mcpOptional.readText()
        assertFalse("mcpToolFilterProvider 直宣会在 253 拒载整文件连坐十个 toolset", text.contains("mcpToolFilterProvider"))
        assertTrue("探测式注册器在册", text.contains("McpGateFilterRegistrar"))
    }

    @Test
    fun `探测与桥两件在册防改名漂移`() {
        assertTrue(
            McpGateFilterEpRegistrar::class.java.name.endsWith("McpGateFilterEpRegistrar"),
        )
        val registrarSrc = File("src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt").readText()
        assertTrue("Class.forName 探针必须在册", registrarSrc.contains("com.intellij.mcpserver.McpToolFilterProvider"))
        val bridgeSrc = File("src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterEpBridge.kt").readText()
        assertTrue("EP 名 ldc 依据必须在册", bridgeSrc.contains("com.intellij.mcpServer.mcpToolFilterProvider"))
    }

    // ---- T-Mcp261FilterContract（20261029）：契约形态分流结构守卫 --------------------------

    @Test
    fun `契约探测必须先于注册且UNKNOWN不注册`() {
        val src = File("src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt").readText()
        assertTrue("必须按契约形态分流（旧形态「类存在即注册」正是 261 全挂根因）", src.contains("detectFilterForm"))
        assertTrue("UNKNOWN 分支必须在册", src.contains("FilterForm.UNKNOWN"))
        val detectIdx = src.indexOf("detectFilterForm")
        val registerIdx = src.indexOf("McpGateFilterEpBridge.register")
        assertTrue(
            "探测必须先于注册（detect=$detectIdx, register=$registerIdx）——顺序反过来就会在未知形态上" +
                "挂一个返回 null 的代理",
            detectIdx in 1 until registerIdx,
        )
    }

    @Test
    fun `桥分发三形态入口与兜底在册`() {
        val src = File("src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterEpBridge.kt").readText()
        assertTrue("形态 A 入口在册", src.contains("\"applyFilters\" ->"))
        assertTrue("形态 B 入口在册（261）", src.contains("\"getFilters\" ->"))
        assertTrue("未知方法必须按返回类型兜底", src.contains("else -> defaultValueFor(method.returnType)"))
        assertTrue(
            "形态 B 两段接口必须以字符串出现（类型引用会重新触发审核拒绝）",
            src.contains("McpToolFilterProvider\\\$McpToolFilter"),
        )
    }
}
