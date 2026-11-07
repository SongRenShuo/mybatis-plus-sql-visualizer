package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import java.util.List;
import java.util.Objects;

/**
 * 批量操作预览（F-15，M6-01）：service.updateBatchById(list) 等批量调用的 SQL 模板。
 *
 * 批量方法的运行时参数（list 元素）静态不可得，因此：
 *  - sql 为 SQL 模板（? 占位符）
 *  - paramGroups 为参数组说明文本（如 -- 实际参数: [stationId=?, schemeId=?, id=?] × N）
 *  - T-M23-02：单主键方法实参可静态求值时内联进 SQL（无参数行）；不可求值时
 *    paramSources/paramHints 登记（面板参数行，与 SQL 中 ? 顺序一一对应）
 *
 * @param methodName  批量方法名（如 updateBatchById）
 * @param tableName   目标表名
 * @param sql         SQL 模板
 * @param paramGroups 参数组说明（null 表示单语句、无需参数组）
 * @param paramSources 未解析参数来源（T-M23-02，与 SQL 中 ? 顺序对应；无参数为空列表）
 * @param paramHints  与 paramSources 并行的回填引号提示（T-M23-02，按列类型 ParamQuote）
 * @param batchModel  批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 的列级勾选结构，
 *                   驱动面板勾选重建 SQL；SELECT/DELETE 等无列级选择的方法为 null
 * @param warning     T-WriteFamily（R4）：无 WHERE 条件的高危写操作警示（{@code delete(null)} /
 *                   {@code update(entity, null)} / {@code remove(null)}），透传到
 *                   {@code cn.song.mybatisplus.visualizer.model.PreviewResult}；null 表示无警示
 */
public final class BatchPreview {
    private final String methodName;
    private final String tableName;
    private final String sql;
    private final String paramGroups;
    private final List<String> paramSources;
    private final List<ParamHint> paramHints;
    private final BatchModel batchModel;
    private final String warning;

    public BatchPreview(String methodName, String tableName, String sql, String paramGroups,
                        List<String> paramSources, List<ParamHint> paramHints, BatchModel batchModel,
                        String warning) {
        this.methodName = methodName;
        this.tableName = tableName;
        this.sql = sql;
        this.paramGroups = paramGroups;
        this.paramSources = paramSources;
        this.paramHints = paramHints;
        this.batchModel = batchModel;
        this.warning = warning;
    }

    public BatchPreview(String methodName, String tableName, String sql, String paramGroups,
                        List<String> paramSources, List<ParamHint> paramHints, BatchModel batchModel) {
        this(methodName, tableName, sql, paramGroups, paramSources, paramHints, batchModel, null);
    }

    public BatchPreview(String methodName, String tableName, String sql, String paramGroups,
                         List<String> paramSources, List<ParamHint> paramHints) {
        this(methodName, tableName, sql, paramGroups, paramSources, paramHints, null);
    }

    public BatchPreview(String methodName, String tableName, String sql, String paramGroups,
                         List<String> paramSources) {
        this(methodName, tableName, sql, paramGroups, paramSources, List.of(), null);
    }

    public BatchPreview(String methodName, String tableName, String sql, String paramGroups) {
        this(methodName, tableName, sql, paramGroups, List.of(), List.of(), null);
    }

    public BatchPreview(String methodName, String tableName, String sql) {
        this(methodName, tableName, sql, null, List.of(), List.of(), null);
    }

    public String getMethodName() {
        return methodName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getSql() {
        return sql;
    }

    public String getParamGroups() {
        return paramGroups;
    }

    public List<String> getParamSources() {
        return paramSources;
    }

    public List<ParamHint> getParamHints() {
        return paramHints;
    }

    public BatchModel getBatchModel() {
        return batchModel;
    }

    /** T-WriteFamily（R4）：无 WHERE 条件的高危写操作警示（null = 无警示）。 */
    public String getWarning() {
        return warning;
    }

    public BatchPreview copy(String methodName, String tableName, String sql, String paramGroups,
                             List<String> paramSources, List<ParamHint> paramHints, BatchModel batchModel) {
        return new BatchPreview(methodName, tableName, sql, paramGroups, paramSources, paramHints, batchModel, warning);
    }

    public BatchPreview copy(String paramGroups) {
        return new BatchPreview(methodName, tableName, sql, paramGroups, paramSources, paramHints, batchModel, warning);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BatchPreview)) {
            return false;
        }
        BatchPreview that = (BatchPreview) o;
        return Objects.equals(methodName, that.methodName)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(sql, that.sql)
                && Objects.equals(paramGroups, that.paramGroups)
                && Objects.equals(paramSources, that.paramSources)
                && Objects.equals(paramHints, that.paramHints)
                && Objects.equals(batchModel, that.batchModel)
                && Objects.equals(warning, that.warning);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName, tableName, sql, paramGroups, paramSources, paramHints, batchModel, warning);
    }

    @Override
    public String toString() {
        return "BatchPreview(methodName=" + methodName + ", tableName=" + tableName + ", sql=" + sql
                + ", paramGroups=" + paramGroups + ", paramSources=" + paramSources + ", paramHints=" + paramHints
                + ", batchModel=" + batchModel + ", warning=" + warning + ")";
    }
}
