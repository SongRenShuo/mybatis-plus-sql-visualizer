package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.CaptureBudget;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 券 03（agent 侧）：心跳 body 带出织入/接口匹配计数。
 *
 * <p>向后兼容判据：此前心跳 body 恒为 {@code "{}"}，IDE 侧对 HEARTBEAT 只做存活帧判定、
 * **不解析 body**（{@code AgentSessionSupport} 的 {@code CMD_HEARTBEAT -> livenessFrameSeen = true}），
 * 故追加键不改变任何既有语义；新键供 C2 的覆盖自检上报取值。
 *
 * <p>本类放在 transport 包：{@link SenderLoop} 是包内可见类，心跳 body 是「跨侧字面契约」，
 * 与 {@code ConfigApplyEndToEndTest} 对 CONFIG ACK body 的断言同款口径。
 */
public class HeartbeatDiagnosticsTest {

    @Before
    public void setUp() {
        CaptureBudget.resetForTest();
    }

    @After
    public void tearDown() {
        CaptureBudget.resetForTest();
    }

    @Test
    public void 心跳body_单行JSON且字段齐全() {
        final String body = SenderLoop.heartbeatBody();
        assertTrue("心跳 body 必须仍是单行 JSON（无换行/无空格）：" + body,
                body.startsWith("{") && body.endsWith("}") && !body.contains(" ") && !body.contains("\n"));
        for (String key : new String[]{"weavable", "woven", "failed", "matched", "unmodifiable",
                "matchedByInterface", "interfaceWoven", "skippedByVersion", "catchAll"}) {
            assertTrue("心跳必须带出 " + key + "（C2 覆盖自检的取值面）：" + body,
                    body.contains("\"" + key + "\":"));
        }
    }

    @Test
    public void 心跳body_计数与熔断态如实带出() {
        CaptureBudget.noteInterfaceMatched();
        CaptureBudget.noteInterfaceMatched();
        CaptureBudget.noteInterfaceWoven();
        CaptureBudget.noteSkippedByVersion();

        final String body = SenderLoop.heartbeatBody();
        assertTrue("matchedByInterface=2：" + body, body.contains("\"matchedByInterface\":2"));
        assertTrue("interfaceWoven=1：" + body, body.contains("\"interfaceWoven\":1"));
        assertTrue("skippedByVersion=1：" + body, body.contains("\"skippedByVersion\":1"));
        assertTrue("默认状态码 0（纯白名单）：" + body, body.contains("\"catchAll\":0"));

        CaptureBudget.setKillAfterForTest(1);
        CaptureBudget.noteCatchAllFailure("fixture");
        assertTrue("kill 后状态码 2（IDE 侧诊断行据此显示已熔断）：" + SenderLoop.heartbeatBody(),
                SenderLoop.heartbeatBody().contains("\"catchAll\":2"));
    }
}
