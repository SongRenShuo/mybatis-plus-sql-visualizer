package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import java.util.Map;

/**
 * 日志列表条目单元格渲染器（T-LogListHeader）。
 *
 * <p>版面：{@code [色条] 头部行（全部元信息） / SQL 行… / 可选错误行}。
 * 头部行顺序与优先级裁剪见 {@link RailCellRendererBase}（bindHeaderLine）与
 * {@code clipSqlFragments} 的顺序截断（与 2026.10.23 一致）；条目分割靠上下留白 + 底部细线。</p>
 *
 * <p>演变：{@code bed58b64} 的 88px 左侧信息轨与进度条已被用户实测否决并撤除；
 * {@code metaLine} 保留为头部行的兼容别名，存量测试断言零改动继续有效。</p>
 *
 * <p>渲染热路径约束（T-LogListFreeze 系列）：不在逐格渲染里增删组件、不新建 Border/Color 实例。</p>
 */
public class LogEntryListCellRenderer extends RailCellRendererBase {

    /** 去重丢弃计数快照（徽章渲染数据源；由 SqlLogPanel.refreshList 更新）。 */
    public Map<String, Integer> dedupeCounts = Map.of();

    /** O-90：同模板计数快照（「×N 同结构」徽章渲染数据源；refreshList 随 dedupeCounts 一并更新）。 */
    public Map<String, Integer> templateCounts = Map.of();

    /** 当前列表可见条目的最大耗时（refreshList 更新；进度条已退役，仍留作诊断读数与测试断言）。 */
    public Long maxDurationMs;

    @Override
    protected Map<String, Integer> templateCountsSnapshot() {
        return templateCounts;
    }

    @Override
    protected Long maxDurationMsSnapshot() {
        return maxDurationMs;
    }

    @Override
    protected Map<String, Integer> dedupeCountsSnapshot() {
        return dedupeCounts;
    }

    @Override
    protected boolean showDuration() {
        return PluginSettings.getInstance().getState().getShowDurationInLogList();
    }
}
