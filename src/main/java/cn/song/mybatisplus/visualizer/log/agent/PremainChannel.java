// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt 移植（PremainChannel 顶层类）
package cn.song.mybatisplus.visualizer.log.agent;

import com.intellij.openapi.Disposable;

import java.net.ServerSocket;

/**
 * T-M56Phase2-Premain：一条待回连的 premain 通道——启动前协商出的 loopback 端口 + 其监听 socket。
 * {@link #claimed} 由 accept 线程在收到回连时置位（{@code AgentManager.hasPendingPremainChannel()} 与
 * prune 的判据）。
 *
 * <p>premain 通道（T-AgentReconnect T-03：生命周期 = 绑定到本次运行）。
 *
 * <p>一个通道 = 一个前置协商的监听端口 + 一个循环 accept 的守护线程，服务<b>一次运行</b>内 agent 的
 * 首次回连与后续全部重连。实现 {@link Disposable} 并注册进项目 disposable 树——socket 与 accept 线程
 * 都在 {@link #dispose} 里收口（Disposer 树是既有踩坑：不注册会在 IDE 退出时被 ObjectTree 报泄漏）。
 */
public final class PremainChannel implements Disposable {

    /** 前置协商出的 loopback 端口。 */
    public final int port;

    /** 监听 socket（回连 accept 用；超时/清理时关闭）。 */
    public final ServerSocket server;

    /** 通道准备时刻（时钟同 AgentManager.clockMs；过期清理与 attach 跳过窗判定用）。 */
    public final long preparedAtMs;

    /** 是否已等到<b>首次</b>回连（{@code AgentManager.hasPendingPremainChannel()} 的跳过判据读它）。 */
    public volatile boolean claimed = false;

    /** 首次回连到达时刻（0=尚未回连）；兜底 TTL 自此计时（T-AgentReconnect T-03）。 */
    public volatile long claimedAtMs = 0L;

    /** 已接受的回连次数（首次 + 重连；上限 {@code AgentManager.MAX_PREMAIN_RECONNECTS}）。 */
    public volatile int acceptCount = 0;

    /** 首次回连自报的 pid（0=HELLO 未取到）；进程存活判定依据（运行结束的旁证）。 */
    public volatile long pid = 0L;

    /** accept 线程引用（dispose 时打断——线程与 socket 同随 disposable 树释放）。 */
    public volatile Thread acceptThread = null;

    /** 通道是否已关闭（accept 循环退出判据）。 */
    public volatile boolean closed = false;

    /**
     * T-PremainReattach S1：暂停待重连态（关开关 {@code AgentManager.shutdownAll} 对 claimed 通道的分流语义）。
     * 暂停 = accept 循环退出、<b>监听 socket 保留</b>——agent 侧 SenderLoop 重连在内核 backlog 停泊
     * （不被 accept 也就不计入 agent 侧「连上即被关」的 [放弃端口计数]，暂停期可任意长），重开后
     * {@code AgentManager.resumePremainChannel} 重建 accept 循环，第一个停泊连接即被取回重建会话，
     * 跳过注定失败的 loadAgent（agentmain 不重入，新端口送不进去）。
     */
    public volatile boolean paused = false;

    /**
     * T-ReleasePerf8（S5 挂死修复）：accept 循环代际。每次起 accept 循环（{@code AgentManager.openPremainChannel}
     * / {@code AgentManager.resumePremainChannel}）递增取新代；旧循环在 accept 返回/超时拍处发现失主即让位
     * 退出——不依赖 {@code paused}（pause→resume 竞态下 paused 先被 resume 翻回 false，旧循环若只依赖它会
     * 失去全部退出条件而无限 tick，S5 发行门禁轮实测挂 75min）。
     */
    public volatile int acceptGeneration = 0;

    public PremainChannel(int port, ServerSocket server, long preparedAtMs) {
        this.port = port;
        this.server = server;
        this.preparedAtMs = preparedAtMs;
    }

    /** 关闭通道（幂等）：置位 + 关监听 socket——阻塞中的 accept 立即抛 SocketException 退出。 */
    public void closeChannel() {
        closed = true;
        try {
            server.close();
        } catch (Throwable ignored) {
            // 对齐 Kotlin runCatching：已关/关失败都按清理完成处理
        }
    }

    /** 项目 dispose 路径：关 socket + 打断 accept 线程。 */
    @Override
    public void dispose() {
        closeChannel();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }
}
