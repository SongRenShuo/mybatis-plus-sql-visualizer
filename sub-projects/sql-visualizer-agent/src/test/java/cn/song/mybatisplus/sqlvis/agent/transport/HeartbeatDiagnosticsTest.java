package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.CaptureBudget;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
        AgentState.setDisabled(false); // 进程级状态：本类断言 gate 键，起点必须是「未自停」
    }

    @After
    public void tearDown() {
        CaptureBudget.resetForTest();
        AgentState.setDisabled(false);
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
        // T-IbatisPanwei 券 D：未登记实现键**恒出现**（无则空串）——缺键会被 IDE 侧读成
        // 「旧版 agent 未上报」，与「本次确实没有未登记实现」混为一谈。
        assertTrue("心跳必须恒带 unreg 键（无未登记实现时为空串）：" + body,
                body.contains("\"unreg\":\""));
    }

    /**
     * 券 D：未登记 JDBC 实现随心跳带出（逗号分隔、只类名、有界 8 个）。
     *
     * <p>反向验证：把 heartbeatBody 里的 {@code "unreg"} 段摘掉，本用例必须红。
     */
    @Test
    public void 心跳body_未登记实现按类名逗号分隔带出且有界() {
        assertTrue("无未登记实现时为空串：" + SenderLoop.heartbeatBody(),
                SenderLoop.heartbeatBody().contains("\"unreg\":\"\""));

        CaptureBudget.noteUnregisteredDriver("org.panweidb.jdbc.PgPreparedStatement");
        CaptureBudget.noteUnregisteredDriver("org.panweidb.jdbc.PgConnection");
        final String body = SenderLoop.heartbeatBody();
        assertTrue("两个类名按逗号分隔：" + body,
                body.contains("\"unreg\":\"org.panweidb.jdbc.PgPreparedStatement,"
                        + "org.panweidb.jdbc.PgConnection\""));

        for (int i = 0; i < 20; i++) {
            CaptureBudget.noteUnregisteredDriver("com.example.driver.Impl" + i);
        }
        final String bounded = SenderLoop.heartbeatBody();
        final int from = bounded.indexOf("\"unreg\":\"") + "\"unreg\":\"".length();
        final int to = bounded.indexOf('"', from);
        final String[] names = bounded.substring(from, to).split(",");
        assertEquals("心跳上报有界（≤8 个类名）：" + bounded, CaptureBudget.MAX_UNREGISTERED, names.length);
        assertTrue("未登记类名不得带空格/换行（单行 JSON 契约）：" + bounded,
                !bounded.contains("\n"));
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

    /**
     * T-AgentSilentAfter16s：心跳恒带自停归因 {@code gate}（0=无自停 / 1=CONFIG 看门狗自停）。
     *
     * <p>为什么这是一条契约级用例而不是「实现细节」：现场四轮取证里，agent 自停与「钩子不产事件」
     * 在探针侧完全同形（都是 EVENT 归零、心跳照发、请求 200），只能靠宿主 stderr 一条告警归因。
     * 归因上心跳后，「为什么没事件」变成对端一条帧就能回答的事实；本用例钉住三件事：
     * 键恒出现、默认 0、看门狗自停后为 1、任何显式开关（CONFIG 恢复）后归零。
     *
     * <p>反向验证（实测两次读数见计划「实施记录」）：把 {@code heartbeatBody} 里的 {@code "gate"}
     * 段摘掉 → 本用例与 {@code ConfigApplyEndToEndTest.看门狗_…} 同时转红。
     */
    @Test
    public void 心跳body_自停归因恒带出且随CONFIG恢复归零() {
        assertTrue("未停采时 gate 恒为 0（归因只在确实停了时有意义）：" + SenderLoop.heartbeatBody(),
                SenderLoop.heartbeatBody().contains("\"gate\":0"));

        AgentState.setDisabledByWatchdog();
        assertTrue("看门狗自停后 gate=1（心跳是这条链上唯一还活着的通道）：" + SenderLoop.heartbeatBody(),
                SenderLoop.heartbeatBody().contains("\"gate\":1"));
        assertTrue("自停仍是停采态（gate 只补归因，不改停采语义）", AgentState.isDisabled());

        // CONFIG 下发（等价于对端恢复控制通道）→ 自停解除、归因归零
        AgentState.setDisabled(false);
        assertTrue("CONFIG 恢复后不得再自称自停：" + SenderLoop.heartbeatBody(),
                SenderLoop.heartbeatBody().contains("\"gate\":0"));
    }

    /**
     * 用户/IDE 主动停采（CONFIG {@code disabled:true}）**不**算自停：gate 必须回 0。
     * 否则「用户自己关的采集」会被心跳报成 agent 自停，把排查引向错误方向。
     */
    @Test
    public void 心跳body_主动停采不报自停归因() {
        AgentState.setDisabledByWatchdog();
        assertTrue(AgentState.gateCode() == AgentState.GATE_CONFIG_WATCHDOG);

        AgentState.setDisabled(true); // 显式停采（CONFIG disabled=true 走同一入口）
        assertTrue("主动停采不是自停：gate 应回 0 —— " + SenderLoop.heartbeatBody(),
                SenderLoop.heartbeatBody().contains("\"gate\":0"));
        assertEquals(AgentState.GATE_NONE, AgentState.gateCode());
    }
}
