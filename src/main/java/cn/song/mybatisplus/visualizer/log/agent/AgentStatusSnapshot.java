// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt 移植（AgentStatusSnapshot 顶层 data class）
package cn.song.mybatisplus.visualizer.log.agent;

import java.util.List;

/**
 * T-CaptureTruth T-CT-01：增强捕获状态行输入快照（纯数据，{@link AgentManagerKt#agentStatusText} 入参，零平台依赖）。
 *
 * <p>T-EnhancedCaptureDefaultOn（2026-10-19）：{@code confirmed} 与 {@code rejectCooldownRemainingMs}
 * 已随注入确认流移除——授权语义收敛到 {@code enabled} 单字段。</p>
 */
public record AgentStatusSnapshot(
    /** 增强捕获总开关是否开启。 */
    boolean enabled,
    /** 健康（socket 未断且心跳窗口内）会话的 pid 列表。 */
    List<Long> livePids,
    /** 是否发生过成功挂载。 */
    boolean everAttached,
    /** T-ReachAudit T-RA-01：最近一次摘除是否因心跳过期（应用侧无响应，而非连接断开）。 */
    boolean heartbeatExpired,
    /**
     * T-AgentHeartbeat T-05：最近一次摘除是否属「连接刚建立、首条心跳尚未到达」
     * （过期会话从未收到过 HELLO 之外的存活帧）。与 {@link #heartbeatExpired} <b>可同时置位</b>（不是互斥，
     * 独立复核订正）：多会话同轮过期时可两者都为真，此时叙事以 {@link #heartbeatExpired} 优先。
     */
    boolean firstHeartbeatPending,
    /** T-AgentHeartbeat T-05：在线窗口秒数（文案带出的可核实读数；默认取协议常量，测试可注入短窗）。 */
    long heartbeatWindowSeconds,
    /**
     * T-CaptureCatchAll 券02：控制台桥活性回落已解封——agent 在线但长期零事件、控制台仍有 SQL 行，
     * 桥已恢复解析。状态行必须如实披露（用户看到「已挂载」却零条目时，这句是唯一解释）。
     */
    boolean activityFallbackActive
) {
    /** 便捷构造器：heartbeatExpired 缺省 false（Kotlin 默认参数）。 */
    public AgentStatusSnapshot(boolean enabled, List<Long> livePids, boolean everAttached) {
        this(enabled, livePids, everAttached, false, false, AgentProtocol.ONLINE_WINDOW_MS / 1000, false);
    }

    /** 便捷构造器：firstHeartbeatPending / heartbeatWindowSeconds 缺省（Kotlin 默认参数）。 */
    public AgentStatusSnapshot(boolean enabled, List<Long> livePids, boolean everAttached, boolean heartbeatExpired) {
        this(enabled, livePids, everAttached, heartbeatExpired, false, AgentProtocol.ONLINE_WINDOW_MS / 1000, false);
    }

    /** 便捷构造器：heartbeatWindowSeconds 缺省（Kotlin 默认参数）。 */
    public AgentStatusSnapshot(boolean enabled, List<Long> livePids, boolean everAttached, boolean heartbeatExpired,
                               boolean firstHeartbeatPending) {
        this(enabled, livePids, everAttached, heartbeatExpired, firstHeartbeatPending,
            AgentProtocol.ONLINE_WINDOW_MS / 1000, false);
    }

    /** 便捷构造器：activityFallbackActive 缺省（Kotlin 默认参数）。 */
    public AgentStatusSnapshot(boolean enabled, List<Long> livePids, boolean everAttached, boolean heartbeatExpired,
                               boolean firstHeartbeatPending, long heartbeatWindowSeconds) {
        this(enabled, livePids, everAttached, heartbeatExpired, firstHeartbeatPending,
            heartbeatWindowSeconds, false);
    }
}
