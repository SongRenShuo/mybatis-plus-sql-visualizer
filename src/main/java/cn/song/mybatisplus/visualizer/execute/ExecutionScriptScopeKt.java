package cn.song.mybatisplus.visualizer.execute;

/**
 * T-DateLiteralBackfill R10（PG 回归）：预览执行是否按「整批」提交。
 *
 * <p>master 侧为顶层函数 {@code shouldExecWholeScript}，Java 侧按 k2j 惯例落
 * {@code ExecutionScriptScopeKt} 门面类（既有 Java 调用方经此路由）。</p>
 */
public final class ExecutionScriptScopeKt {

    private ExecutionScriptScopeKt() {
    }

    /**
     * 预览执行是否按「整批」提交。
     *
     * <p>背景：DBN 控制台默认按「光标所在语句 / 选区」提交。SQL 里出现
     * {@code TIMESTAMP '…'} / {@code DATE '…'} 这类 ANSI 类型字面量后，长行被控制台折行、光标落在
     * 以 TIMESTAMP 开头的片段上时，getSelectedStatementsRange 会把该片段当成一条语句，只提交
     * TIMESTAMP 本身——PostgreSQL 报 {@code 42601 syntax error at or near "TIMESTAMP"}。</p>
     *
     * <p>预览面板的「执行 SQL」语义是<b>执行当前预览的 SQL</b>；单语句预览没有「只想执行其中一段」
     * 的需求，整批提交与「执行当前语句」等价，却绕开光标/选区这个变量。多语句预览
     * （主 SQL + 分页 COUNT 等）保持既有「当前语句」逻辑，避免把 COUNT 段一起执行。</p>
     *
     * @param sql       预览 SQL（已回填）
     * @param requested 调用方已显式要求整批（如 SQL Server identity 包装）时直接透传
     */
    public static boolean shouldExecWholeScript(String sql, boolean requested) {
        if (requested) {
            return true;
        }
        return SqlTypeClassifier.splitStatements(sql).size() <= 1;
    }

    /** requested=false 的缺省形态（Kotlin 默认参等价）。 */
    public static boolean shouldExecWholeScript(String sql) {
        return shouldExecWholeScript(sql, false);
    }
}
