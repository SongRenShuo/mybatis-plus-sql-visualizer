// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt 移植（agentStatusText / configAckTimeoutMessage 顶层函数）
package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

/**
 * AgentManager.kt 顶层函数载体。
 */
public final class AgentManagerKt {

    private AgentManagerKt() {
    }

    /**
     * T-CaptureTruth T-CT-01：增强捕获状态行文案（纯函数，全分支可单测）。
     *
     * <p>立项动因：原实现只有「有活会话 / 曾挂载 / 其余 null」三态——<b>「开关开着但从未授权」落进 null
     * 分支</b>，设置页状态行渲染成空白而复选框仍显示勾选，用户读作「开着且没事」，实际一条都捕不到
     * （T-CaptureTruth 根因之三；旧守卫断言「无会话应返回 null」把该行为钉死，本次一并改）。
     *
     * <p>各态（T-EnhancedCaptureDefaultOn（2026-10-19）起无「未授权」类叙事——注入确认已移除）：
     *  1. 关闭（!enabled）→ null（复选框未勾选，无需叙事）；
     *  2. 有活会话 →「已挂载 pid=…」；
     *  3. 曾挂载 + 心跳过期 → 带窗口秒数的可核实表述（T-AgentHeartbeat T-05 去因果断言）；
     *  4. 曾挂载 + 首条心跳未到 → 中性等待叙述（T-AgentHeartbeat T-05 新增）；
     *  5. 曾挂载 + 其余 → 连接中断回落；
     *  6. 从未挂载 → 等待应用进程启动后挂载。
     */
    public static String agentStatusText(AgentStatusSnapshot s) {
        if (!s.enabled()) {
            return null;
        }
        if (!s.livePids().isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < s.livePids().size(); i++) {
                if (i > 0) {
                    joined.append(',');
                }
                joined.append("pid=").append(s.livePids().get(i));
            }
            String attached = MpSqlVisualizerBundle.message("settings.enhancedCapture.status.attached", joined.toString());
            // T-CaptureCatchAll 券02：解封态必须与「已挂载」同时出现——只说已挂载会让用户以为
            // agent 在产出；只说回退会丢掉「会话还在」这一事实。
            return s.activityFallbackActive()
                ? attached + "｜" + MpSqlVisualizerBundle.message("settings.enhancedCapture.status.activityFallback")
                : attached;
        }
        if (s.everAttached()) {
            // T-ReachAudit T-RA-01：心跳过期 = socket 未断而帧停，与真断连分开叙述。
            // T-AgentHeartbeat T-05：过期再分两因，且只报**可核实的事实**（已 N 秒未收到心跳）——
            // 旧文案「进程可能已卡死」是代码无从得知的因果断言（现场应用 JVM 又活了 7 分半）。
            if (s.heartbeatExpired()) {
                return MpSqlVisualizerBundle.message(
                    "settings.enhancedCapture.status.heartbeatLost",
                    s.heartbeatWindowSeconds());
            }
            return MpSqlVisualizerBundle.message(
                s.firstHeartbeatPending()
                    // 连接刚建立、首条心跳尚未到达：中性等待叙述，不得说成「心跳中断/停摆」
                    ? "settings.enhancedCapture.status.firstHeartbeatPending"
                    : "settings.enhancedCapture.status.fellBack");
        }
        return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.authorizedWaiting");
    }

    /**
     * T-AgentResiduals R4-①：CONFIG 回执超时的<b>三态文案</b>（纯函数，全分支可单测）。
     *
     * <p>复核 F3 的现场：原实现只有一句「CONFIG 未获确认——应用侧可能未生效」，把三种成因（连接已断 /
     * 应用侧在跑但不回执 / 应用侧静默）混成一种叙述。<b>半死 socket 被判成「应用侧未生效」是错叙事</b>——
     * 连接都不在了，「应用侧应用与否」根本无从谈起，用户会去查应用侧配置而真正的处置是重连。
     *
     * <p>三态判据（调用方在巡检到点那一刻取实况）：
     * <ul>
     *   <li>{@code aliveNow} = {@code false} → <b>控制通道已断开</b>（socket 断 / 读循环退出）；</li>
     *   <li>{@code frameAfterSend} = {@code true}（下发之后仍收到入站帧）→ <b>应用侧仍在跑</b>，典型是旧版 agent 无回执
     *       能力或入站帧解析错位；</li>
     *   <li>两者皆非 → <b>应用侧静默</b>，这才是「可能未生效」真正对应的形态。</li>
     * </ul>
     *
     * <p>三种文案<b>互斥且各含判据词</b>（{@code 控制通道已断开} / {@code 应用侧仍在跑} / {@code 应用侧可能未生效}），
     * 供 {@code AgentManagerIntakeGateTest} 按态断言——文案分叉是本项交付的主体，不能只测「有告警」。
     */
    public static String configAckTimeoutMessage(
        long pid,
        boolean disabled,
        boolean aliveNow,
        boolean frameAfterSend,
        long sinceLastFrameMs) {
        if (!aliveNow) {
            return "CONFIG 未获确认，且**控制通道已断开**（pid=" + pid + ", 下发 disabled=" + disabled + " 后 "
                + AgentManager.CONFIG_ACK_TIMEOUT_MS + "ms 内未收到 CMD_CONFIG_ACK，且会话 socket 已断/"
                + "读循环已退出）——连接已断时「应用侧是否曾生效」无从确认，请以应用侧日志与实际采集"
                + "行为为准（面板条目真实性以本机应用侧为准）";
        }
        if (frameAfterSend) {
            return "CONFIG 未获确认，但**应用侧仍在跑**（pid=" + pid + ", 下发 disabled=" + disabled + " 后 "
                + AgentManager.CONFIG_ACK_TIMEOUT_MS + "ms 内未收到 CMD_CONFIG_ACK；期间仍收到存活帧，"
                + "最近一条距巡检 " + sinceLastFrameMs + "ms）——应用侧活着却不回执，典型是**旧版 agent "
                + "不支持回执**或入站帧解析错位；若条目仍在产出，属混合版本下的正常双通道（列表去重"
                + "窗口内折叠），不必按「开关未生效」处理";
        }
        return "CONFIG 未获确认——应用侧可能未生效（pid=" + pid + ", 下发 disabled=" + disabled + " 后 "
            + AgentManager.CONFIG_ACK_TIMEOUT_MS + "ms 内未收到 CMD_CONFIG_ACK，且期间无任何入站帧）；"
            + "控制通道可能失效（入站帧解析错位 / 旧版 agent 不支持回执）——面板条目真实性以本机应用侧为准";
    }
}
