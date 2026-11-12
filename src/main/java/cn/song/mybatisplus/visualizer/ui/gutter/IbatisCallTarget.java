// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisStatementPreview.kt 移植（拆件：internal data class）
package cn.song.mybatisplus.visualizer.ui.gutter;

import com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.annotations.Nullable;

/**
 * 一个「iBatis {@code SqlMapExecutor} 调用点」：调用表达式 + statementId + 调用点所属类限定名。
 *
 * <p>{@code statementId} 与形状门（{@code IbatisCallYieldGate}）同一口径：首参 String 字面量、{@code id} / {@code ns.id}
 * 标识符形态；存在性<b>不在这里校验</b>（gutter 慢趟与点击期各自走既有 memo 解析）。</p>
 */
public record IbatisCallTarget(
    @Nullable com.intellij.psi.PsiMethodCallExpression call,
    @org.jetbrains.annotations.NotNull String statementId,
    @Nullable String ownerNamespace
) {
}
