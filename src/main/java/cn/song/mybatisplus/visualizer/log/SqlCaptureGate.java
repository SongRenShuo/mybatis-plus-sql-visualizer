package cn.song.mybatisplus.visualizer.log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M56 T-M56-02：增强捕获接管闸门——agent 在线时置 true，控制台桥的解析入口短路（空转），
 * 面板只呈现 agent 通道条目；连接丢失/开关关闭回落时置 false 恢复。桥链路本身零改动。
 * AtomicBoolean：读侧（逐行热路径）单次 volatile 读，写侧（会话状态切换）罕见。
 */
public final class SqlCaptureGate {

    private SqlCaptureGate() {
    }

    public static final AtomicBoolean paused = new AtomicBoolean(false);

    /**
     * M56 T-M56-09：「EP 首行标志」——consoleFilterProvider EP 的 Filter 处理控制台行时置 true
     * （会话内粘滞）。桥据此短路自身的重复解析（条目由 EP 单路产出）。
     *
     * <p>T-CaptureTruth T-CT-04 <b>语义收窄</b>：置位条件由「EP 处理过任意一行」改为「EP 实证产出过条目」。
     * 原口径下 EP 只要被平台调用一次就封死桥侧解析，而 EP 对某类控制台是否真在链上、是否真能产出
     * 从未真机验证（provider KDoc 自述「真机门禁留人工」）——一旦 EP 覆盖不全，桥的回落通道已被
     * 自己封死 = <b>静默零捕获</b>（与 T-CaptureTruth 立项现场同类症状）。收窄后：EP 必须先交出一条
     * SQL 条目才获信任；EP 不在链上或对该控制台产不出条目时，桥照常解析（代价仅是「全程无 SQL 的
     * 会话」多跑几道解析门，无条目可建，成本可忽略）。
     */
    public static volatile boolean epActive = false;

    /**
     * T-CaptureCatchAll 券02：桥的**有效**封停态——{@code paused} 且活性回落未解封。
     *
     * <p>逐行热路径只读两个 volatile（{@code paused} + {@code CaptureActivityFallback.isActive()}），无锁。
     * 桥的所有「是否让位」判定都改用本函数（不再直接读 {@code paused}）：白名单漏一个驱动时
     * agent 会长期零事件，此时必须让控制台桥回来，否则两条通道同时零产出。</p>
     */
    public static boolean bridgeSuppressed() {
        return paused.get() && !CaptureActivityFallback.isActive();
    }
}
