package cn.song.mybatisplus.visualizer.log.agent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 最近一次 attach 覆盖上报（进程级单例，诊断行只读）。
 *
 * <p>为什么是进程级而非 project 级：attach 计数的归属是「agent 实例」，与项目无关；一个 IDE 里
 * 多个项目共用一个 JVM 时，最近一次 attach 就是用户当下要看的事实。项目 dispose 时由
 * {@code AgentManager.dispose} 清空，避免跨项目残留。</p>
 *
 * <p>master 侧为 {@code AgentAttachStats.kt} 内 {@code object AgentAttachReport}（与 data class 同文件），
 * k2j 按一 public 类一文件惯例独立成文件。</p>
 */
public final class AgentAttachReport {

    private AgentAttachReport() {
    }

    private static final AtomicReference<AgentAttachStats> LATEST = new AtomicReference<>(null);

    /** 读循环逐帧调用：命中即整体覆盖；不命中零动作。异常全吞（红线）。 */
    public static void updateFromFrame(String body) {
        try {
            AgentAttachStats parsed = AgentAttachStats.parse(body);
            if (parsed != null) {
                LATEST.set(parsed);
            }
        } catch (Throwable t) {
            // 红线：诊断计数绝不能影响事件主链
        }
    }

    /** 最近一次上报（null = 旧版 agent 未上报 / 尚未 attach）。 */
    public static AgentAttachStats latest() {
        return LATEST.get();
    }

    /** 项目销毁 / 测试收尾清空。 */
    public static void reset() {
        LATEST.set(null);
    }
}
