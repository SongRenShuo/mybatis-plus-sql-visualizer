package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-M56Phase2-Premain：注入决策纯函数表驱动（T-M56P2-05）。
 *
 * 覆盖三道闸门的全部顺序敏感组合——真机跑不出「jar 缺失 + 端口为 0」这类交叉面，
 * 而闸门顺序错一位的后果是「未开启也去开 ServerSocket」或「端口为 0 也注入 → agent 侧
 * AgentMain.install 直接放弃挂载、白注入一条 VM 参数」。
 *
 * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认闸门已移除，表驱动收敛为三道闸门。
 */
class PremainInjectionTest {

    private data class Row(
        val enabled: Boolean,
        val jarAvailable: Boolean,
        val port: Int?,
        val expect: String,
    )

    private fun outcomeOf(row: Row): String =
        when (val d = PremainInjection.decide(row.enabled, row.jarAvailable, row.port)) {
            is PremainInjection.Decision.Inject -> "inject:${d.port}"
            is PremainInjection.Decision.Skip -> "skip:${d.reason}"
        }

    @Test
    fun 三闸门表驱动_顺序敏感全组合() {
        val rows = listOf(
            // 开关关：后续事实一律不参与判定（调用方据此跳过 jar 提取与端口协商）
            Row(false, false, null, "skip:enhanced-disabled"),
            Row(false, true, 53210, "skip:enhanced-disabled"),
            // jar 不可用优先于端口判定（端口此时压根没协商）
            Row(true, false, null, "skip:agent-jar-unavailable"),
            Row(true, false, 53210, "skip:agent-jar-unavailable"),
            // 端口协商失败：null 与 <=0 同判（0 会被 agent 侧 AgentMain.install 放弃）
            Row(true, true, null, "skip:port-negotiation-failed"),
            Row(true, true, 0, "skip:port-negotiation-failed"),
            Row(true, true, -1, "skip:port-negotiation-failed"),
            // 三闸门全过
            Row(true, true, 53210, "inject:53210"),
            Row(true, true, 1, "inject:1"),
        )
        for (row in rows) {
            assertEquals(
                "enabled=${row.enabled} jar=${row.jarAvailable} port=${row.port}",
                row.expect,
                outcomeOf(row),
            )
        }
    }

    @Test
    fun 默认参数不注入_端口缺省即协商失败() {
        // 端口默认 null → 按协商失败处理（调用方在闸门通过后才协商端口，缺省即无端口可注入）
        assertEquals(
            PremainInjection.Decision.Skip(PremainInjection.REASON_NO_PORT),
            PremainInjection.decide(true),
        )
        // jar 缺省 true（调用方在闸门通过后必然已提取）；显式传 false 时按 jar 不可用处理
        assertEquals(
            PremainInjection.Decision.Skip(PremainInjection.REASON_NO_JAR),
            PremainInjection.decide(true, false, null),
        )
    }

    @Test
    fun 原因常量与断言用字面量一致() {
        // 原因串进日志，改动会打断既有排障检索；此处钉死字面量
        assertEquals("enhanced-disabled", PremainInjection.REASON_DISABLED)
        assertEquals("agent-jar-unavailable", PremainInjection.REASON_NO_JAR)
        assertEquals("port-negotiation-failed", PremainInjection.REASON_NO_PORT)
    }

    @Test
    fun vm选项形态与agent侧args契约逐字对齐() {
        val option = PremainInjection.vmOption("C:/tmp/mpsv-agent/mps-sql-agent-123.jar", 53210)
        assertEquals("-javaagent:C:/tmp/mpsv-agent/mps-sql-agent-123.jar=port=53210;logLevel=warn;stackFrames=24", option)
        // args 段即 AgentProtocol.agentArgs 产物（agent 侧 AgentArgs.parse 的 k=v 分号契约）
        assertEquals(AgentProtocol.agentArgs(53210), option.substringAfter("="))
        // 端口可被注入侧原样取回（AgentManager 之外的调用方/测试的取端口口径）
        assertEquals(53210, Regex("port=(\\d+)").find(option)!!.groupValues[1].toInt())
        // T-StackHoverUi：帧数预算随选项下发（显式传参透传，缺省 24 与 PluginSettings 缺省同值）
        assertEquals(30, Regex("stackFrames=(\\d+)").find(
            PremainInjection.vmOption("C:/tmp/mpsv-agent/mps-sql-agent-123.jar", 53210, 30),
        )!!.groupValues[1].toInt())
    }
}
