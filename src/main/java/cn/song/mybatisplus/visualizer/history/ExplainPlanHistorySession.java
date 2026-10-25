package cn.song.mybatisplus.visualizer.history;

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * R90 的提交/成功回调共同使用的上下文；只有值快照进入 manager，target 仅在面板会话内。
 *
 * <p>master 的 {@code class ExplainPlanHistorySession} 的 Java 承载（有状态会话，非全 static）。</p>
 */
public final class ExplainPlanHistorySession {

    /** 提交请求（面板构造并传入 complete）。 */
    public static final class Request {
        private final ExplainPlanKey key;
        private final ExplainPlanCapture capture;
        private final long generation;
        private final Object target;
        private final boolean retentionAtSubmission;

        Request(ExplainPlanKey key, ExplainPlanCapture capture, long generation, Object target,
                boolean retentionAtSubmission) {
            this.key = key;
            this.capture = capture;
            this.generation = generation;
            this.target = target;
            this.retentionAtSubmission = retentionAtSubmission;
        }

        public ExplainPlanKey getKey() { return key; }
        public ExplainPlanCapture getCapture() { return capture; }
        long getGeneration() { return generation; }
        Object getTarget() { return target; }
        boolean isRetentionAtSubmission() { return retentionAtSubmission; }
    }

    /** 基线（历史快照或上次会话结果）。 */
    public record Baseline(String label, McpExplainFormatter.RawResult raw) {
    }

    /** 完成产物：基线清单 + 保存结果。 */
    public record Completion(List<Baseline> baselines, ExplainPlanSaveResult saved) {
    }

    private static final class Previous {
        final Request request;
        final McpExplainFormatter.RawResult raw;

        Previous(Request request, McpExplainFormatter.RawResult raw) {
            this.request = request;
            this.raw = raw;
        }
    }

    private Previous previous = null;

    public Request begin(SqlHistoryManager manager, String previewSql, ExplainPlanKey key, Object target) {
        return begin(manager, previewSql, key, target, null, false);
    }

    public Request begin(SqlHistoryManager manager, String previewSql, ExplainPlanKey key, Object target,
                         String entryId) {
        return begin(manager, previewSql, key, target, entryId, false);
    }

    public Request begin(SqlHistoryManager manager, String previewSql, ExplainPlanKey key, Object target,
                         String entryId, boolean retentionEnabled) {
        ExplainPlanCapture capture = retentionEnabled
                ? manager.beginExplainCapture(key.projectIdentity(), previewSql, key, entryId) : null;
        return new Request(key, capture, manager.explainPlanGeneration(), target, retentionEnabled);
    }

    /** 在保存新结果之前取基线；故不会与自己比较。UI 选择基线只消费返回值，不再执行 SQL。 */
    public Completion complete(SqlHistoryManager manager, Request request,
                               McpExplainFormatter.RawResult raw) {
        return complete(manager, request, raw, false);
    }

    public Completion complete(SqlHistoryManager manager, Request request,
                               McpExplainFormatter.RawResult raw, boolean retentionEnabled) {
        boolean retain = request.isRetentionAtSubmission() && retentionEnabled;
        if (request.getGeneration() != manager.explainPlanGeneration()) {
            previous = null;
            return new Completion(List.of(), ExplainPlanSaveResult.CLEARED_OR_REMOVED);
        }
        List<Baseline> baselines = new ArrayList<>();
        if (retain) {
            for (ExplainPlanSnapshot snapshot : manager.matchingExplainPlans(request.getKey())) {
                McpExplainFormatter.RawResult snapshotRaw = snapshot.raw();
                if (snapshotRaw != null) {
                    String label = MpSqlVisualizerBundle.message("panel.explain.history.snapshot",
                            PluginBuildInfo.formatBuildTime(String.valueOf(snapshot.getCapturedAt())),
                            snapshot.getId().substring(0, Math.min(8, snapshot.getId().length())));
                    baselines.add(new Baseline(label, snapshotRaw));
                }
            }
        }
        Previous last = previous;
        if (last != null && last.request.getGeneration() == request.getGeneration()
                && last.request.getKey().equals(request.getKey())
                && (request.getKey().isPersistable() || last.request.getTarget() == request.getTarget())) {
            // R61：上次结果即使超限未持久化，也仍可在同目标会话内比较。
            String previousLabel = MpSqlVisualizerBundle.message("panel.explain.history.previousSession");
            int retainedIndex = -1;
            for (int i = 0; i < baselines.size(); i++) {
                // master Kotlin `it.raw == last.raw` 是 data class 结构相等；Java record 必须显式
                // equals——直译成 `==` 变引用比较，已保存快照（反序列化新实例）永不命中，去重失效
                // （ExplainPlanResultViewUiTest assertBaselineLabels 去重例实证 expected 1 was 2）。
                if (baselines.get(i).raw().equals(last.raw)) {
                    retainedIndex = i;
                    break;
                }
            }
            if (retainedIndex < 0) {
                baselines.add(0, new Baseline(previousLabel, last.raw));
            } else {
                // 保持原来的去重、顺序与比较数据，只补充这一项的会话来源。
                Baseline retained = baselines.get(retainedIndex);
                baselines.set(retainedIndex, new Baseline(previousLabel + " · " + retained.label(), retained.raw()));
            }
        }
        ExplainPlanSaveResult saved;
        if (!retain) {
            saved = ExplainPlanSaveResult.RETENTION_DISABLED;
        } else if (request.getCapture() != null) {
            saved = manager.saveExplainPlan(request.getCapture(), raw);
        } else {
            saved = ExplainPlanSaveResult.SESSION_ONLY;
        }
        previous = new Previous(request, raw);
        return new Completion(baselines, saved);
    }
}
