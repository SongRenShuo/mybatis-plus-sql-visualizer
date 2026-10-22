// T-SyncK2JMaster496 自 master ui/DialectStatusRefresh.kt 移植（applyDialectStatusSegment 最小形态）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import org.jetbrains.annotations.NotNull;

/**
 * 按当前 {@link SqlPreviewPanel#currentDialectDecision} 重组 meta 行方言段。
 *
 * <p>与 master {@code DialectStatusRefresh.kt} 的语义对齐，但<b>最小形态</b>：Java 端 meta 行
 * 由 {@link SqlPreviewPanelResultViewKt} 的 applyMetaTruncation 统一重建，本方法只把方言段
 * 并入 metaLineHead 头（决策 null 时不动——回落纯 meta 主体）。</p>
 */
public final class DialectStatusRefreshKt {

    private DialectStatusRefreshKt() {
    }

    public static void applyDialectStatusSegment(@NotNull SqlPreviewPanel p) {
        DialectDecisionEngine.Decision decision = p.currentDialectDecision;
        if (decision == null) {
            return;
        }
        DialectPreviewStatusText.Text status = DialectPreviewStatusText.describe(decision);
        p.metaLineHead = status.visible() + "   " + p.metaLineHead;
        p.metaLabel.setToolTipText(p.metaLineHead);
    }
}
