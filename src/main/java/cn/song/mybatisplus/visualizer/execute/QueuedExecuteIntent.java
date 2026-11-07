package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 排队执行意图（F1 拦截点的回调快照）。拦截时执行链的回调不可丢弃——面板在 executeSql 里
 * 已 showLoading 并拿到 callback(true)，消费点必须用意图自身的 onError/onErrorRich 解除
 * 加载态、用 onConsole（面板 EXECUTE 内嵌）换入结果，否则排队执行失败即静默卡死。
 */
public record QueuedExecuteIntent(
        /** 控制台内嵌回调（面板 EXECUTE 结果换入）；null = 不内嵌。 */
        @Nullable Consumer<JComponent> onConsole,
        /** 控制台编辑器就绪回调；null = 不需要。 */
        @Nullable Consumer<Editor> onEditor,
        /** 旧通道错误回调（解除加载态）；null = 不通知。 */
        @Nullable Consumer<String> onError,
        /** 富通道错误回调；null = 不通知。 */
        @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
        /** 是否整脚本执行。 */
        boolean wholeScriptExec,
        /** UI-040：方言闸请求随意图携带（排队期间用户已点执行，闸门判定须在真实提交前完成）。 */
        @Nullable ExecutionDialectGate.Request dialectGateRequest,
        /** 方言闸判定回调；null = 不通知。 */
        @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
        /** 提交回执出口（终态一次性）。 */
        @Nullable Consumer<ExecutionSubmission> submissionSink,
        /** UI-049：方言不匹配态结构化提示（排队意图自身的面板可据此出「下一步」，而不是红字）。 */
        @Nullable Consumer<DialectMismatchHint> onDialectMismatchHint
) {

    /**
     * 每实例独立完成位。record 的 equals/hashCode 是值语义，两个同形意图会撞键——必须用
     * <b>恒等</b>映射（IdentityHashMap），否则一个完成会连带标记另一个。
     */
    private static final Map<QueuedExecuteIntent, AtomicBoolean> COMPLETED_BY_INTENT =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** 便捷构造：方言闸/提交回执/方言不匹配提示缺省（Kotlin 默认参数）。 */
    public QueuedExecuteIntent(@Nullable Consumer<JComponent> onConsole,
                               @Nullable Consumer<Editor> onEditor,
                               @Nullable Consumer<String> onError,
                               @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
                               boolean wholeScriptExec) {
        this(onConsole, onEditor, onError, onErrorRich, wholeScriptExec, null, null, null, null);
    }

    private AtomicBoolean completedFlag() {
        synchronized (COMPLETED_BY_INTENT) {
            return COMPLETED_BY_INTENT.computeIfAbsent(this, k -> new AtomicBoolean(false));
        }
    }

    public boolean isCompleted() {
        return completedFlag().get();
    }

    /** 提交回执出口（终态一次性交付）。 */
    public Consumer<ExecutionSubmission> onSubmission() {
        return this::complete;
    }

    public void complete(ExecutionSubmission outcome) {
        complete(outcome, null);
    }

    public void complete(ExecutionSubmission outcome, @Nullable String message) {
        if (!completedFlag().compareAndSet(false, true)) {
            return;
        }
        Consumer<ExecutionSubmission> sink = submissionSink();
        if (sink != null) {
            sink.accept(outcome);
        }
        if (message != null && onError() != null) {
            onError().accept(message);
        }
    }
}
