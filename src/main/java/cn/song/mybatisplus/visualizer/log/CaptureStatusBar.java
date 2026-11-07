package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * R53（V80① 落地）：工具窗双通道捕获状态栏（SQL 日志页签底部，捕捉期间常驻）。
 *
 * <p>立项动因：增强捕获「现在捕获没有 / 暂停了」状态此前只藏在空态诊断行里——列表有条目时
 * 用户完全看不到双通道状态；R53 把两条通道的实时状态摆到台面上：</p>
 * <ul>
 * <li>控制台通道：SqlLogConsoleBridge.attachedConsoles（已挂载数）+ consoleLinesSeen（已扫描行）；</li>
 * <li>增强 agent 通道：总开关关（未开启）/ 暂停中 / 已挂载 pid / 已收事件 N（复用
 *     AgentManager.statusSummary 的五态文案）。</li>
 * </ul>
 *
 * <p>V80① 证伪结论：取数全为只读内存态（CopyOnWriteArrayList / ConcurrentHashMap / AtomicLong /
 * volatile 字段），工具窗 2s 无锁轮询安全——与既有空态诊断行（SqlLogPanel.syncDiagnostics）
 * 同一取数口径，不新开线程。</p>
 *
 * <p>纯函数（零平台依赖）便于全分支单测；面板只负责取数与渲染（SqlLogPanel.syncCaptureStatusBar）。</p>
 */
public final class CaptureStatusBar {

    private CaptureStatusBar() {
    }

    /** 状态栏输入快照（纯数据）。 */
    public record CaptureStatusLine(
            /** 是否在捕捉中（状态栏只在捕捉期间显示；非捕捉态返回 null）。 */
            boolean capturing,
            /** 控制台通道：已挂载控制台数。 */
            int consolesAttached,
            /** 控制台通道：已扫描行数（SqlLogConsoleBridge.consoleLinesSeen）。 */
            long linesSeen,
            /**
             * 增强 agent 通道：AgentManager.statusSummary 成品文案（null = 增强总开关关）。
             * 只传成品文案而非快照，面板取数经 statusSummary 单点（与设置页/空态诊断行同源）。
             */
            @Nullable String agentStatus,
            /** 增强 agent 通道：健康（已挂载）会话 pid 列表（AgentManager.healthyPids）。 */
            @NotNull List<Long> agentLivePids,
            /** 增强 agent 通道：用户是否已暂停（AgentManager.userPaused）。 */
            boolean agentPaused,
            /** 增强 agent 通道：已收事件累计（AgentEventAdapter.receivedEvents）。 */
            long agentEventsReceived,
            /**
             * T-IbatisPanwei 券 D：接口命中但不在驱动白名单的类名（有界 ≤8，只类名）。
             *
             * <p>为什么必须落在状态栏：空态诊断行在 {@code entryCount > 0} 时直接返回 null（控制台有产出就完全不
             * 显示），而「驱动没登记」这条事实恰恰在「控制台有产出、增强零产出」的现场最需要被看见。
             * 状态栏是捕捉期间常驻的那一处，不新造面板。</p>
             */
            @NotNull List<String> unregisteredImplementations,
            /**
             * T-SqlListCatchup 券D：追赶读数——buffer 未消费内容通知计数（SqlLogEntryBuffer.pendingNotifyCount）。
             * &gt;0 时控制台段追加「追赶中：落后 N 条」；0 时不追加（已过 CatchUpBadgeState 一拍节流）。
             *
             * <p>口径修正（2026-11-07，券C 证伪链）：原计划的「桥通道未解析行数取大者」不存在——
             * {@code SqlLogConsoleBridge} 为 filter 逐行制（每行解析在控制台输出线程完成，KDoc :388-390 自证），
             * 无逐拍解析积压量，桥接缝已随券C取消，本字段是追赶读数的唯一数据源。</p>
             */
            int catchUpBehind,
            /**
             * T-SqlListCatchup 券 F2a：agent 侧事件队列<b>丢弃累计</b>（agent 心跳 {@code dropped} 键 →
             * {@code AgentAttachStats.dropped}）。
             *
             * <p>为什么必须落在状态栏：风暴期事件队列打满时 agent 侧 {@code offer()} 满即丢（非阻塞红线，丢弃本身
             * 是对的），但「丢了多少」此前只在 agent 进程内可见——用户视角是「已收事件 2,227 条就不再涨了」，
             * 无法区分「应用真停了」和「队列溢出在丢」。0 或旧版 agent 未上报时不挂段。</p>
             */
            long agentDropped
    ) {

        /** 便捷构造器：unregisteredImplementations/catchUpBehind/agentDropped 缺省（Kotlin 默认参数）。 */
        public CaptureStatusLine(boolean capturing, int consolesAttached, long linesSeen,
                @Nullable String agentStatus, @NotNull List<Long> agentLivePids, boolean agentPaused,
                long agentEventsReceived) {
            this(capturing, consolesAttached, linesSeen, agentStatus, agentLivePids, agentPaused,
                    agentEventsReceived, List.of(), 0, 0L);
        }
    }

    /**
     * T-SqlListCatchup 券D：追赶徽章一拍节流状态机（纯逻辑，零平台依赖，面板每拍驱动）。
     *
     * <p>为什么需要它：追赶读数在节流窗（200ms）边界抖动——风暴期每拍在 0 与小正数间振荡，
     * 直接透传会让「追赶中」徽章以 2s 轮询频率闪烁。本机给防闪烁语义：
     * <ul>
     * <li>落后数 &gt;0 → 立即显示该数（升起不等拍）；</li>
     * <li>落后数首次归零 → <b>持有上一读数再亮一拍</b>（=0 持续一拍后消失，计划 §三.4 口径）；</li>
     * <li>持有拍内又升起（短暂归零又升高）→ 无缝续显新数，不闪烁。</li>
     * </ul></p>
     *
     * <p>持有拍显示的是上一拍的读数（最多滞后一拍刷新周期，状态栏轮询 2s / 刷新拍更快），有界陈旧。</p>
     */
    public static final class CatchUpBadgeState {
        private int lastShown = 0;
        private boolean zeroBeatSeen = false;

        /** 每拍驱动（SqlLogPanel.syncCaptureStatusBar 的每个刷新拍），返回本拍应显示的落后数（0 = 不显示）。 */
        public int onNextBeat(int behind) {
            if (behind > 0) {
                lastShown = behind;
                zeroBeatSeen = false;
                return behind;
            }
            if (lastShown > 0 && !zeroBeatSeen) {
                // 追平的第一拍：持有上一读数再亮一拍——短暂归零又升起在这里被吸收
                zeroBeatSeen = true;
                return lastShown;
            }
            lastShown = 0;
            zeroBeatSeen = false;
            return 0;
        }

        /** 非捕捉态/清栏时复位（下次捕捉从干净态起算）。 */
        public void reset() {
            lastShown = 0;
            zeroBeatSeen = false;
        }
    }

    /**
     * 状态栏两段文案（第一段=控制台通道，第二段=增强通道）；非捕捉态返回 null（状态栏清空隐藏）。
     *
     * @return Pair(控制台段, 增强段)；null = 非捕捉态
     */
    public static @Nullable Pair captureStatusLineText(@NotNull CaptureStatusLine d) {
        if (!d.capturing()) {
            return null;
        }
        String consoleBase = MpSqlVisualizerBundle.message("log.statusbar.console",
                d.consolesAttached(), d.linesSeen());
        // 券D：追赶读数挂控制台段尾（>0 才挂；已过 CatchUpBadgeState 一拍节流，无抖动闪烁）
        String console = d.catchUpBehind() > 0
                ? MpSqlVisualizerBundle.message("log.statusbar.catchup", consoleBase, d.catchUpBehind())
                : consoleBase;
        String agent;
        if (d.agentStatus() == null) {
            // 总开关关：无叙事（与 agentStatusText 的 !enabled → null 同口径）
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.off");
        } else if (d.agentPaused()) {
            // 暂停中：暂停语义盖过挂载叙事（暂停中也可能已挂载——连接活着、只是不入列表）
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.paused",
                    d.agentStatus(), d.agentEventsReceived());
        } else if (!d.agentLivePids().isEmpty()) {
            // 已挂载：状态文案（已挂载 pid=…）+ 已收事件 N
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.attached",
                    d.agentStatus(), d.agentEventsReceived());
        } else {
            // 未挂载：等待/冷却/回落等既有五态文案
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.summary", d.agentStatus());
        }
        // 券 D：未登记驱动具名事实挂尾（有界；增强未开启时无意义，不挂）
        String agentWithDrivers = d.agentStatus() != null && !d.unregisteredImplementations().isEmpty()
                ? MpSqlVisualizerBundle.message("log.statusbar.agent.unregistered",
                        agent, String.join(", ", d.unregisteredImplementations()))
                : agent;
        // 券 F2a：agent 侧事件队列丢弃累计挂尾（>0 才挂——零丢弃/旧版 agent 未上报时不加噪音）
        // 【反向验证探针锚点】注释掉下面 if 块 = 摘除丢弃可见化，CaptureStatusBarTest 丢弃用例必红。
        String agentWithDropped = d.agentStatus() != null && d.agentDropped() > 0L
                ? MpSqlVisualizerBundle.message("log.statusbar.agent.dropped", agentWithDrivers, d.agentDropped())
                : agentWithDrivers;
        return new Pair(console, agentWithDropped);
    }

    /** 两段文案载体（Kotlin Pair 的 Java 承载）。 */
    public record Pair(String first, String second) {
    }
}
