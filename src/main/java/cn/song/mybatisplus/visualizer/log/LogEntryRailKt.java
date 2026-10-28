package cn.song.mybatisplus.visualizer.log;

import java.util.List;

/**
 * 日志列表的纯投影辅助（T-ListMetaRevert）。
 *
 * <p>演进注记：Kotlin 侧本文件曾承载 88px 左侧信息轨的整套辅助（时间格式剥离、类名骨架化、
 * 进度条），用户实测后全部否决（「类名为什么要省略，还不如 2026.10.23 版本的」），
 * 现只保留与「可见条目最大耗时」相关的一个纯函数——其余回归 {@code formatSourceFragment}
 * 等既有实现（完整文件名、不压缩）。</p>
 */
public final class LogEntryRailKt {
    private LogEntryRailKt() {
    }

    /**
     * 可见条目的最大耗时。
     *
     * <p>用途：慢查询判定与诊断读数（慢查询徽章用阈值判定，这里给的是<b>相对最大值</b>）。
     * 单遍遍历、零分配——不用 {@code mapNotNull{}.maxOrNull()}，后者要为每条分配列表槽，
     * 而本函数每次刷新都跑；{@code logMaxCount} 可被用户调到几万，流式灌入下是纯 GC 压力。</p>
     */
    public static Long maxDurationOf(List<LogSqlEntry> entries) {
        Long max = null;
        for (int i = 0; i < entries.size(); i++) {
            Long ms = entries.get(i).executionTimeMs();
            if (ms == null) continue;
            if (max == null || ms > max) max = ms;
        }
        return max;
    }
}
