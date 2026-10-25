package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * ExplainPanelChannel.kt 顶层辅助函数的 Java 承载（端口约定：Kotlin 顶层函数 → {@code XxxKt}）。
 * 失败分型签名族全部小写 contains 判定（headless 直测，零 IDE 依赖）。
 */
public final class ExplainPanelChannelKt {

    private ExplainPanelChannelKt() {
    }

    /** 认证失败签名（小写 contains；覆盖 MySQL/PostgreSQL/Oracle/SQL Server 常见形态）。 */
    private static final List<String> EXPLAIN_AUTH_SIGNATURES = Arrays.asList(
            "access denied",                   // MySQL / 通用
            "password authentication failed",  // PostgreSQL
            "invalid username/password",       // Oracle 文本形态
            "ora-01017",                       // Oracle 数字形态
            "login failed for user"            // SQL Server
    );

    /**
     * T-ExplainFailUx W1：「表不在当前源」签名（小写 contains）。覆盖四家方言的固定报文：
     * MySQL {@code [42S02][1146] Table 'x.y' doesn't exist}（仓内实证 DataSourceSelectorResults:1056）、
     * PostgreSQL {@code relation "x" does not exist}（42P01）、SQL Server {@code Invalid object name 'x'}（208）、
     * Oracle {@code ORA-00942: table or view does not exist}。
     *
     * <p>注意与「列不存在」区分：{@code Unknown column} 是列级问题（同一张表在，换源解决不了），<b>不算</b>本档。</p>
     */
    private static final List<String> EXPLAIN_WRONG_TARGET_SIGNATURES = Arrays.asList(
            "doesn't exist",                    // MySQL 1146 / SQL Server 变体
            "does not exist",                   // PostgreSQL 42P01 / Oracle 942 文本
            "unknown table",                    // MySQL 泛化
            "invalid object name",              // SQL Server 208
            "ora-00942",                        // Oracle 数字形态
            "no such table"                     // SQLite / 通用
    );

    /** 文本是否「表不在当前源」形态（W1）。 */
    public static boolean isWrongTargetText(@NotNull String text) {
        String lower = text.toLowerCase();
        for (String signature : EXPLAIN_WRONG_TARGET_SIGNATURES) {
            if (lower.contains(signature)) return true;
        }
        return false;
    }

    /** 文本是否认证失败形态（{@link ExplainPanelChannelKt#classifyExplainFailure} 与 ui.PreviewSideQuery 共用）。 */
    public static boolean isAuthFailureText(@NotNull String text) {
        String lower = text.toLowerCase();
        for (String signature : EXPLAIN_AUTH_SIGNATURES) {
            if (lower.contains(signature)) return true;
        }
        return false;
    }

    /**
     * T-ExplainPwdPrompt W2（2026-10-24）：执行计划失败分型。
     *
     * <p>取消判定排除超时——驱动超时消息常含 "cancelled"（MySQL "Statement cancelled due to
     * timeout"），超时是执行错误不是用户取消（{@link ExecutionTimeoutGuard}
     * 已转 {@code timed out} 标记），误判取消会把真失败吞成轻通知。</p>
     */
    public static ExplainFailureKind classifyExplainFailure(@NotNull String text) {
        if (isAuthFailureText(text)) return ExplainFailureKind.AUTH;
        // T-ExplainFailUx W1：认证优先（`doesn't exist` 与认证不会同时出现，但认证误判代价更高），
        // 其次「表不在当前源」——该换源而非报错（列级 `Unknown column` 不算，换源解决不了）。
        if (isWrongTargetText(text)) return ExplainFailureKind.WRONG_TARGET;
        String lower = text.toLowerCase();
        // "cancell" 词根同时覆盖 cancelled / cancellation（CancellationException 是双 L，
        // 只写 "canceled" 漏掉平台取消形态——ExplainFailureClassifierTest 首跑实证）
        boolean cancelLike = lower.contains("canceled") || lower.contains("cancell");
        boolean timeoutLike = lower.contains("timeout") || lower.contains("timed out");
        if (cancelLike && !timeoutLike) return ExplainFailureKind.CANCELLED;
        return ExplainFailureKind.OTHER;
    }
}
