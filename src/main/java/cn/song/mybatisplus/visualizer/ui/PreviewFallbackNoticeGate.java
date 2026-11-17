// T-SyncK2JMaster178 自 master SqlPreviewPanelEditorLifecycle.kt 的 internal class PreviewFallbackNoticeGate 手工翻译（顶层独立件，test 与 SqlPreviewPanelEditorLifecycleKt 共用形态）
package cn.song.mybatisplus.visualizer.ui;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 13.12：预览侧「自动兜底 + 只告知一次」的去重闸门。
 *
 * <p>落点判定每次预览/预热都会跑；若每次都提示就变成新噪音源，用户只需要知道一次。
 * master 侧为 top-level internal class，k2j 按独立件承载（测试与面板同入口）。</p>
 */
public final class PreviewFallbackNoticeGate {
    private final AtomicBoolean notified = new AtomicBoolean(false);

    /** @return true = 本次是首个调用方（应当提示）；后续调用 false */
    public boolean shouldNotify() {
        return notified.compareAndSet(false, true);
    }
}
