package cn.song.mybatisplus.visualizer.script.api;

import java.util.Collections;
import java.util.List;

/**
 * T-RollbackSnapshotPreExec P6：后置脚本拿到的执行结果（**全部只读**）。
 *
 * resultRowsSample 是结果集截断样本（编排层负责截到上限，默认 100 行），不是全量结果——
 * 大结果集进脚本内存既慢又危险。
 */
public final class SqlExecResult {

    private final long affectedRows;
    private final List<String> columns;
    private final List<List<String>> resultRowsSample;
    private final boolean rowsTruncated;
    private final String snapshotId;
    private final long elapsedMs;

    public SqlExecResult(
            long affectedRows,
            List<String> columns,
            List<List<String>> resultRowsSample,
            boolean rowsTruncated,
            String snapshotId,
            long elapsedMs) {
        this.affectedRows = affectedRows;
        this.columns = columns == null ? Collections.emptyList() : List.copyOf(columns);
        List<List<String>> rows = new java.util.ArrayList<>();
        if (resultRowsSample != null) {
            for (List<String> row : resultRowsSample) {
                rows.add(row == null ? Collections.emptyList() : List.copyOf(row));
            }
        }
        this.resultRowsSample = Collections.unmodifiableList(rows);
        this.rowsTruncated = rowsTruncated;
        this.snapshotId = snapshotId;
        this.elapsedMs = elapsedMs;
    }

    /** 影响行数（SELECT 为 0）。 */
    public long getAffectedRows() {
        return affectedRows;
    }

    /** 结果集列名（无结果集时为空表）。 */
    public List<String> getColumns() {
        return columns;
    }

    /** 结果集样本行（截断上限内；全部为字符串化值）。 */
    public List<List<String>> getResultRowsSample() {
        return resultRowsSample;
    }

    /** 结果集是否被截断（true = 库端返回比样本多）。 */
    public boolean isRowsTruncated() {
        return rowsTruncated;
    }

    /** 本次执行关联的改前快照条目 id（开关关闭/不可采集时为 null）。 */
    public String getSnapshotId() {
        return snapshotId;
    }

    /** 执行耗时毫秒。 */
    public long getElapsedMs() {
        return elapsedMs;
    }
}
