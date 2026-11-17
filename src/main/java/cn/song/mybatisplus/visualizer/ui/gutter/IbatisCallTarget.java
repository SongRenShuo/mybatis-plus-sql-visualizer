package cn.song.mybatisplus.visualizer.ui.gutter;

import com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一个「iBatis {@code SqlMapExecutor} 调用点」：调用表达式 + statementId + 调用点所属类限定名。
 *
 * <p>statementId 与形状门（{@link IbatisCallYieldGate}）同一口径：首参 String 字面量、
 * {@code id} / {@code ns.id} 标识符形态；存在性<b>不在这里校验</b>（gutter 慢趟与点击期各自走
 * 既有 memo 解析）。</p>
 *
 * @param call            调用表达式
 * @param statementId     语句 id（{@code id} / {@code ns.id} 字面量）
 * @param ownerNamespace  调用点所属类的限定名（裸 id 的「同 namespace 优先」排序偏好）
 */
public record IbatisCallTarget(
        @NotNull PsiMethodCallExpression call,
        @NotNull String statementId,
        @Nullable String ownerNamespace) {
}
