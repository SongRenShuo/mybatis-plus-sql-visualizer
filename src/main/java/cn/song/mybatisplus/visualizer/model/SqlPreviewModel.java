package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import java.util.List;
import java.util.Objects;

/**
 * SQL 预览模型（M12）：供预览面板做模型级重生成（动态条件勾选）。
 *
 * @param statement      语句模型（含动态条件 toggle 元数据）
 * @param dialect        方言（重生成分页子句用）
 * @param formatSql      多行/压缩设置（false=压缩），面板据此对齐初始 compactMode
 * @param conditionViews 动态条件行（扁平化，含嵌套组内），供左栏复选框渲染
 */
public final class SqlPreviewModel {
    private final SqlStatement statement;
    private final Dialect dialect;
    private final boolean formatSql;
    private final List<ConditionView> conditionViews;

    public SqlPreviewModel(SqlStatement statement, Dialect dialect, boolean formatSql,
                           List<ConditionView> conditionViews) {
        this.statement = statement;
        this.dialect = dialect;
        this.formatSql = formatSql;
        this.conditionViews = conditionViews;
    }

    public SqlStatement getStatement() {
        return statement;
    }

    public Dialect getDialect() {
        return dialect;
    }

    public boolean isFormatSql() {
        return formatSql;
    }

    public boolean getFormatSql() {
        return formatSql;
    }

    public List<ConditionView> getConditionViews() {
        return conditionViews;
    }

    public SqlPreviewModel copy(SqlStatement statement, Dialect dialect, boolean formatSql,
                                List<ConditionView> conditionViews) {
        return new SqlPreviewModel(statement, dialect, formatSql, conditionViews);
    }

    /** 目标方言无损重渲染：仅替换方言，其余字段原样。 */
    public SqlPreviewModel withDialect(Dialect target) {
        return new SqlPreviewModel(statement, target, formatSql, conditionViews);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SqlPreviewModel)) {
            return false;
        }
        SqlPreviewModel that = (SqlPreviewModel) o;
        return formatSql == that.formatSql
                && Objects.equals(statement, that.statement)
                && Objects.equals(dialect, that.dialect)
                && Objects.equals(conditionViews, that.conditionViews);
    }

    @Override
    public int hashCode() {
        return Objects.hash(statement, dialect, formatSql, conditionViews);
    }

    @Override
    public String toString() {
        return "SqlPreviewModel(statement=" + statement + ", dialect=" + dialect + ", formatSql=" + formatSql
                + ", conditionViews=" + conditionViews + ")";
    }
}
