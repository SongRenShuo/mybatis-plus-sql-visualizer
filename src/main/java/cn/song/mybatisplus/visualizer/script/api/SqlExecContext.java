package cn.song.mybatisplus.visualizer.script.api;

/**
 * T-RollbackSnapshotPreExec P6：前置脚本拿到的执行上下文（**全部只读**）。
 *
 * D-1 已定案（2026-11-14 用户拍板按计划建议）：不给改写 SQL 的 API——setter 会绕过
 * 确认弹窗与写安全闸门的既有契约。脚本只能观察、打日志、否决（返回 false）。
 */
public final class SqlExecContext {

    private final String sqlText;
    private final String statementKind;
    private final String targetTable;
    private final String dataSourceName;
    private final String dialectName;

    public SqlExecContext(
            String sqlText,
            String statementKind,
            String targetTable,
            String dataSourceName,
            String dialectName) {
        this.sqlText = sqlText;
        this.statementKind = statementKind;
        this.targetTable = targetTable;
        this.dataSourceName = dataSourceName;
        this.dialectName = dialectName;
    }

    /** 将要提交的可执行 SQL（已填参数/占位；与控制台收到的同一份）。 */
    public String getSqlText() {
        return sqlText;
    }

    /** 语句类型（UPDATE / DELETE）。 */
    public String getStatementKind() {
        return statementKind;
    }

    /** 解析出的单目标表名；解析不出时为空串。 */
    public String getTargetTable() {
        return targetTable;
    }

    /** 绑定数据源显示名；未绑定时为 null。 */
    public String getDataSourceName() {
        return dataSourceName;
    }

    /** 当前生效方言名；未确定时为 null。 */
    public String getDialectName() {
        return dialectName;
    }

    @Override
    public String toString() {
        return "SqlExecContext{" + statementKind + " on " + targetTable
                + " ds=" + dataSourceName + " dialect=" + dialectName + "}";
    }
}
