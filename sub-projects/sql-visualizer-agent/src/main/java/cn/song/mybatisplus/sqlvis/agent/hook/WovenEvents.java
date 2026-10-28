package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import cn.song.mybatisplus.sqlvis.agent.util.MdcAccessor;
import cn.song.mybatisplus.sqlvis.agent.util.StackDigest;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;

import java.util.concurrent.TimeUnit;

/**
 * 语句面织入通道的事件组装（T-StatementWeave S2）：字段语义与代理层 [StatementHandler] 对齐
 * （耗时/影响行数/异常文本/栈定位/摘要/MDC/线程名/绑定参数），两处**结构性差异**如实登记：
 * <ul>
 *   <li>params 来自语句面 setXxx 入口织入（T-ConnCastSafety 起；此前只有代理层能采，连接代理
 *       出厂关闭后必须自持）。盲区语句（attach 前 prepare、无 SQL 登记）取不到 SQL 时 params 为
 *       null——不产空壳；</li>
 *   <li>timestamp 为出口回推（出口 currentTimeMillis 减 duration）——毫秒级精度，日志时间相关器
 *       未命中时的回落值与语句开始时刻最多差一个执行耗时（计划 §四.4 取舍）。</li>
 * </ul>
 * 采集红线：本类只被 SqlHookCallbacks 调用（异常已在回调层自吞），EventBus.offer 失败不影响宿主。
 */
final class WovenEvents {

    private WovenEvents() {
    }

    /**
     * T-AttachZeroEvent（2026-10-09）：织入通道**首条事件**取证打点——事件组装即打一行 stderr
     * 到宿主应用控制台。现场复盘：attach 后面板零条目时，无法区分「agent 没产事件」与「IDE 消费链
     * 丢事件」（对端 AgentManager 的 markReceived 计数没有宿主侧出口）；本行让「织入钩子真在产」
     * 在宿主侧可见，与 IDE 侧首条到达 INFO 行配对即可三分故障域（agent 侧 / 传输 / 消费链）。
     * 只打首条（AtomicBoolean 一次），后续事件零开销零噪音。行内只有 op / sqlLen / registryUnresolved
     * 三个判读字段，**不含 SQL 原文**（2026-10-14 起；本行不过脱敏开关，故从源头不带业务文本）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_FIRE =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 仅测试用：复位首条打点（用例间隔离）。 */
    static void resetFirstFireForTest() {
        FIRST_FIRE.set(false);
    }

    static void emit(Object stmt, String op, String sql, Object result, Throwable failure,
                     long startNanos, String url) {
        if (AgentState.isDisabled() || sql == null || sql.isEmpty()) {
            return;
        }
        if (FIRST_FIRE.compareAndSet(false, true)) {
            // 2026-10-14：不再打印 SQL 片段（原 sqlHead=前 80 字符）。本行的用途只是「钩子在产」
            // （见上方 KDoc），SQL 原文对判读无贡献；而本行不过脱敏开关（脱敏只作用于事件载荷），
            // Statement 路径的 sql 可能含字面量——改打长度，判读能力不变、宿主日志不留业务文本。
            System.err.println("[SqlVisAgent] 织入通道首条事件已产出 op=" + op
                    + " sqlLen=" + sql.length()
                    + " registryUnresolved=" + StatementRegistry.unresolvedCount()
                    + " ——本行即「钩子在产」的宿主侧证据（仅此一条）");
        }
        final long now = System.nanoTime();
        final long durationNanos = now - startNanos;
        final long startMillis = System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(durationNanos);
        // loader hint 取被织入语句类（驱动侧类）——StackLocator 据此判定帧来源是否 JAR（L1 过滤）
        ClassLoader hint = null;
        try {
            hint = stmt.getClass().getClassLoader();
        } catch (Throwable ignore) {
            // 保持 null，StackLocator 内部退上下文 loader
        }
        // T-PS4-09 同口径：一次快照两用（定位 + 摘要）
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        final StackLocator.Frame frame = StackLocator.locate(stack, hint);
        final Long updateCount = EventBuilder.extractUpdateCount(op, result, stmt);
        final SqlEvent event = new SqlEvent();
        event.sql = sql;
        event.timestamp = startMillis;
        event.durationNanos = durationNanos;
        event.updateCount = updateCount;
        event.errorText = failure == null ? null
                : failure.getClass().getName() + ": " + failure.getMessage();
        event.sourceFile = frame == null ? null : frame.sourceFile;
        event.sourceLine = frame == null ? 0 : frame.line;
        event.methodKey = frame == null ? null : frame.methodKey;
        event.sourceCandidates = frame == null ? null : frame.candidates;
        event.stackDigest = StackDigest.build(stack);
        event.requestTag = MdcAccessor.requestTag();
        // T-ConnCastSafety：参数改由语句面 setXxx 织入采集（连接代理出厂关闭后代理层那份不再在）。
        // 无登记（盲区语句）即 null——SQL 都取不到时参数无处挂，与旧语义一致的「诚实缺省」。
        final StatementRegistry.Ctx ctx = StatementRegistry.get(stmt);
        event.params = ctx == null ? null : ctx.params.snapshot(sql, AgentState.isDesensitize());
        event.url = url;
        event.threadName = Thread.currentThread().getName();
        EventBus.offer(event);
    }
}
