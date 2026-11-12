package cn.song.mybatisplus.visualizer.execute;

/**
 * T-DateLiteralBackfill R10（PG 回归）：预览执行是否按「整批」提交。
 *
 * <p>背景：DBN 控制台默认按「光标所在语句 / 选区」提交。SQL 里出现
 * {@code TIMESTAMP '…'} / {@code DATE '…'} 这类 ANSI 类型字面量后，长行被控制台折行、光标落在
 * 以 {@code TIMESTAMP} 开头的片段上时，{@code getSelectedStatementsRange} 会把该片段当成一条语句，
 * 只提交 {@code TIMESTAMP} 本身——PostgreSQL 报 {@code 42601 syntax error at or near "TIMESTAMP"}。</p>
 *
 * <p>预览面板的「执行 SQL」语义是<b>执行当前预览的 SQL</b>；单语句预览没有「只想执行其中一段」
 * 的需求，整批提交与「执行当前语句」等价，却绕开光标/选区这个变量。多语句预览
 * （主 SQL + 分页 COUNT 等）保持既有「当前语句」逻辑，避免把 COUNT 段一起执行。</p>
 *
 * <p>k2j 移植说明：master 侧为 {@code execute/ExecutionScriptScope.kt} 顶层函数
 * {@code shouldExecWholeScript(sql, requested=false)}——{@code requested} 默认参落双重载，
 * 1 参壳委托 2 参全参形态（A3 消费点两种调用形都在：
 * {@code ui.SqlPreviewPanelExecutionKt} 的 1 参 / 2 参各两处）。</p>
 */
public final class ExecutionScriptScopeKt {

    private ExecutionScriptScopeKt() {
    }

    /** {@code requested} 默认参（master {@code = false}）的重载：1 参壳委托全参形态。 */
    public static boolean shouldExecWholeScript(String sql) {
        return shouldExecWholeScript(sql, false);
    }

    /**
     * 预览执行是否按「整批」提交。
     *
     * @param sql       预览 SQL（已回填）
     * @param requested 调用方已显式要求整批（如 SQL Server identity 包装）时直接透传
     */
    public static boolean shouldExecWholeScript(String sql, boolean requested) {
        if (requested) return true;
        return SqlTypeClassifier.splitStatements(sql).size() <= 1;
    }
}
