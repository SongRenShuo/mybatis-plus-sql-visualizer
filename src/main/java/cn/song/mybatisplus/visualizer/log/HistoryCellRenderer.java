package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import java.util.Map;

/**
 * 二级 SQL 列表单元格渲染器（F-44-2；T-LogListFreeze-04 弃 HTML 通道）。
 *
 * <p><b>T-LogListRail（2026-10-26）改版</b>：与日志页 {@link LogEntryListCellRenderer} 同步改为
 * 「色条 + 头部行 + SQL 区」结构。改版动机与演变见 {@link RailCellRendererBase} 类 javadoc。</p>
 *
 * <p>本类曾与日志页渲染器逐行同构（三组方法近乎复制），改版时把共用部分收进 {@link RailCellRendererBase}，
 * 本类只保留历史页差异：耗时开关此前硬编码 {@code true}，现已与日志页同走设置项。</p>
 *
 * <p>供 UI 测试断言各种条目状态（空耗时/空源码/特殊字符/超长 SQL）下的渲染健壮性。</p>
 */
public class HistoryCellRenderer extends RailCellRendererBase {

    /** O-90：同模板计数快照（「×N 同结构」徽章渲染数据源；HistorySqlLogPanel.applySqlSearch 更新）。 */
    public Map<String, Integer> templateCounts = Map.of();

    /** 当前列表可见条目的最大耗时（进度条归一基准）。 */
    public Long maxDurationMs;

    @Override
    protected Map<String, Integer> templateCountsSnapshot() {
        return templateCounts;
    }

    @Override
    protected Long maxDurationMsSnapshot() {
        return maxDurationMs;
    }

    /**
     * 历史页条目来自历史文件读回，{@code sequenceNumber} 恒为 0（见 LogSqlEntry.sequenceNumber 契约），
     * 故头部行通常只有时刻。耗时显示与日志页同走设置项（此前此处硬编码 true，设置页开关对历史页无效）。
     */
    @Override
    protected boolean showDuration() {
        return PluginSettings.getInstance().getState().getShowDurationInLogList();
    }
}
