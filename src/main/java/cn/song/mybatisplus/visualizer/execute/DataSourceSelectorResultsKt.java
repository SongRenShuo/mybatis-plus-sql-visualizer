// T-M53-II D2-4：master 把 DataSourceSelector.kt 的结果区/临时文件族成员搬为 execute 包顶层扩展函数
// （DataSourceSelectorResults.kt）；本类是其 Java 承载——k2j 侧原为 DataSourceSelector.java 的成员，
// 按 master 拆分结果外提（同包同可见性，零行为改动）。
package cn.song.mybatisplus.visualizer.execute;

import static cn.song.mybatisplus.visualizer.execute.DataSourceSelector.*;

import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageExtension;
import com.intellij.lang.LanguageUtil;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code DataSourceSelectorResults.kt} 顶层扩展函数（结果区换入 / 临时文件 / 执行冲突探针族）的 Java 承载类。
 *
 * <p>本类方法经 {@code import static ...DataSourceSelector.*} 直引原 object 成员（master 侧为扩展函数接收者）。</p>
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class DataSourceSelectorResultsKt {

    private DataSourceSelectorResultsKt() {
    }

    /** 结果 Tab 激活重试。（T-ExecSilentNoop：dsName/demotedOverride 供 40 拍超时窗口提示三分流） */
    public static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames,
            int generation,
            String dsName,
            boolean demotedOverride,
            @Nullable String sqlText,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        final javax.swing.Timer[] timerHolder = {null};
        final int[] attempts = {0};
        ActionListener listener = e -> {
            attempts[0]++;
            if (generation != swapGeneration) {
                timerHolder[0].stop();
                LOG.debug("[M36] result tab retry cancelled (generation " + generation + " != " + swapGeneration + ")");
                return;
            }
            String err = extractConsoleError(sharedConsole != null ? sharedConsole : preparedConsole, LOG, sqlText, project);
            if (err != null && !err.equals(lastReportedError)) {
                timerHolder[0].stop();
                runPostExecutionInvalidate(sqlText, LOG);
                lastReportedError = err;
                LOG.info("[M36] early console error detected (attempts=" + attempts[0] + "): " + (err.length() > 120 ? err.substring(0, 120) : err));
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                // T-ELU-01/03：DBN 写进 console history 的**语句级**错误也走富通道——
                // 这是「正常 SQL 执行失败」的主路径（提交链不抛异常，错误只落在 history），
                // 原实现只投旧 onError ⇒ 定位按钮永不出现。cause 传 null（文本里已有
                // 位置 marker，SQLState/errorCode 由驱动器在 history 文本中给出）。
                final String fErr = err;
                ApplicationManager.getApplication().invokeLater(() -> {
                    deliverError(onErrorRich, onError, null, fErr, sqlText != null ? sqlText.length() : null);
                }, ModalityState.any());
                return;
            }
            Content[] contents;
            try {
                Object c = ui.getClass().getMethod("getContents").invoke(ui);
                contents = c instanceof Content[] ? (Content[]) c : new Content[0];
            } catch (Throwable ignored) {
                contents = new Content[0];
            }
            Content result = pickResultTab(contents, preExistingNames, attempts[0]);
            if (result != null && preExistingNames.contains(result.getDisplayName())) {
                LOG.info("[M36] result tab reused (name=" + result.getDisplayName() + ") - accepting existing tab");
            }
            // T-PerfSweep2-01：列表构建仅喂 LOG.debug——debug 关闭时不再逐拍白建两份列表
            //（400ms 定时器最长 100 拍，此前每拍 2 次 map 空转）。
            if (LOG.isDebugEnabled()) {
                List<String> all = Arrays.stream(contents).map(Content::getDisplayName).collect(Collectors.toList());
                List<Integer> ids = Arrays.stream(contents).map(System::identityHashCode).collect(Collectors.toList());
                LOG.debug("[M36] retry#" + attempts[0] + " contents=" + all + ", ids=" + ids + ", select="
                        + (result != null ? result.getDisplayName() : "null") + ", preExisting=" + preExistingNames);
            }
            if (result != null) {
                timerHolder[0].stop();
                runPostExecutionInvalidate(sqlText, LOG);
                // M36.5.48 勘误：此处**不**调用 extractConsoleError——结果 Tab 复用成功时
                // console history 含正常 SQL 文本（如 "WHERE t.id = 1"），会被误判为错误并
                // onError（日志实证 `extracted console error: WHERE t.id = 1`，误报 + 一闪而过）。
                try {
                    Method select = ui.getClass().getMethod("selectAndFocus", Content.class, Boolean.TYPE, Boolean.TYPE);
                    select.invoke(ui, result, false, true);
                } catch (Throwable ex) {
                    LOG.warn("[M36] select result tab FAILED: " + ex.getMessage());
                }
                refreshResultsComponent(ui, LOG, true, generation, onError, onErrorRich, sqlText);
            } else {
                boolean dumb = false;
                try { dumb = DumbService.isDumb(project); } catch (Throwable ignored) { /* ignore */ }
                int limit = dumb ? 100 : 40;
                if (attempts[0] >= limit) {
                    timerHolder[0].stop();
                    runPostExecutionInvalidate(sqlText, LOG);
                    LOG.info("[M36] result tab not appeared after " + attempts[0] + " attempts (dumb=" + dumb + ")");
                    String realError = extractConsoleError(sharedConsole != null ? sharedConsole : preparedConsole, LOG, sqlText, project);
                    // T-CEC-03：判据认不出的失败也要有下文——控制台确有新增输出（去掉本次 SQL
                    // 回显后的残余）时如实呈现，而不是把语法错归因成密码/驱动（真机 P15 实证）。
                    String residue = realError == null
                            ? extractConsoleResidue(sharedConsole != null ? sharedConsole : preparedConsole, sqlText, LOG)
                            : null;
                    // T-ExecSilentNoop：40 拍超时窗口与 8s 监控共用 executionHintReported 去重。
                    // 真实错误优先（DBN 已把失败写进 history 才叫真失败/真超时）；无错误痕迹时
                    // 不再一律归因凭据——先看本次是否探到"未提交"（freshModel 回落，DBN 侧静默
                    // 拒绝），按情况给 notSubmitted / timeout / indexing 三种真实提示。
                    if (realError == null && !executionHintReported) {
                        executionHintReported = true;
                        String message;
                        if (residue != null) {
                            // 如实标注：这是控制台新增输出，**不是**已识别的错误形态（不猜原因）
                            message = MpSqlVisualizerBundle.message("execute.error.unknownShape") + "\n" + residue;
                        } else {
                            message = dumb
                                    ? MpSqlVisualizerBundle.message("execute.indexing.hint")
                                    : (demotedOverride
                                            ? MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName != null ? dsName : "")
                                            : MpSqlVisualizerBundle.message("execute.timeout.hint"));
                        }
                        LOG.info("[M36] result tab not appeared, no console error (dumb=" + dumb + ", "
                                + "demoted=" + demotedOverride + ", residue=" + (residue != null) + ") - hint via 40-tick window");
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        final String fHint = message;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (onError != null) onError.accept(fHint);
                        }, ModalityState.any());
                    } else if (realError != null) {
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        // T-ELU-01/03：语句级错误同早检路径，走富通道收口
                        final String fErr = realError;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            deliverError(onErrorRich, onError, null, fErr, sqlText != null ? sqlText.length() : null);
                        }, ModalityState.any());
                    }
                }
            }
        };
        Timer timer = new Timer(400, listener);
        timerHolder[0] = timer;
        timer.start();
    }

    /** 结果 Tab 激活重试（使用当前 swapGeneration 作为监听代际，Kotlin 默认参缺省形态）。 */
    public static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames,
            String dsName,
            boolean demotedOverride) {
        startResultTabRetry(project, ui, onError, preExistingNames, swapGeneration, dsName, demotedOverride, null, null);
    }


    /** T-DialectGateExit：是否允许复用已准备好的共享控制台。forceChoose（用户显式要求换数据源）必须跳过复用。 */
    static boolean allowsSharedConsoleReuse(boolean forceChoose) {
        return !forceChoose;
    }

    /** T-DialectGateExit：是否允许 last-used 数据源静默命中（显式执行本来就不静默；forceChoose 也不静默）。 */
    static boolean allowsSilentLastUsed(boolean executeAfterPrepare, boolean forceChoose) {
        return !executeAfterPrepare && !forceChoose;
    }

    public static void chooseAndPrepare(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        chooseAndPrepare(project, sql, executeAfterPrepare, onConsole, onEditor, onError, callback, onErrorRich,
                wholeScriptExec, null, null, null, false);
    }

    public static void chooseAndPrepare(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            boolean forceChoose) {
        chooseAndPrepare(project, sql, executeAfterPrepare, onConsole, onEditor, onError, callback, onErrorRich,
                wholeScriptExec, dialectGateRequest, onDialectGate, onSubmission, forceChoose, null, false);
    }

    /**
     * T-ExplainFailUx W4 / T-ExplainSwitchNative W6 扩展形态。
     *
     * @param preferredRunnerName T-ExplainFailUx W4（2026-10-24 用户实测「为什么我需要选两次数据源」）：
     *                            <b>用户已显式指定数据源名</b>——按名在 runner 枚举里唯一命中就直接建链，
     *                            <b>不再弹平台选择器</b>（此前预览的数据源下拉调 {@code prepareConsole(forceChoose=true)}，
     *                            而 forceChoose 的语义恰是「强制弹窗」，于是用户在自己的下拉里选一次、
     *                            平台选择器又让他选第二次）。语义边界：只跳过「选择」这一步，不跳过方言闸
     *                            （闸门在 sessionConsumer 内照旧执行）；名字未命中/同名歧义 → 回落既有弹窗（不猜）。
     * @param forceChoosePopup    T-ExplainSwitchNative W6：必须弹选择器（跳过 {@code shouldBeChosenIfAlone} 直选分支）。
     */
    public static void chooseAndPrepare(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            boolean forceChoose,
            @Nullable String preferredRunnerName,
            boolean forceChoosePopup) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        if (!ExecuteSqlSupport.isAvailable()) {
            LOG.warn("[M36] database plugin NOT available, abort");
            if (onSubmission != null) onSubmission.accept(ExecutionSubmission.BLOCKED);
            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.noDatabase"));
            if (callback != null) callback.accept(false);
            return;
        }

        // T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队——打开预览的准备链（远程 PG
        // 会话需数秒）未落地时点执行，旧代码在下方无任何去重，会再建第二条临时文件/会话/控制台；
        // 两条链的 executionGeneration++ 与提交/取消判定交错 → 提交被取消或结果组件被换掉
        //（结果区空白、二击正常的 H1 根因）。单飞判定先于共享复用：冷启时 sharedConsole 尚未
        // 落地，复用必不命中，单飞是唯一闸门。
        // 预览也持有身份，超时后旧 session 回调不能消费下一链的队列。
        QueuedExecuteIntent queuedIntent = new QueuedExecuteIntent(onConsole, onEditor, onError, onErrorRich,
                wholeScriptExec, dialectGateRequest, onDialectGate, onSubmission);
        Consumer<ExecutionSubmission> submission = queuedIntent.onSubmission();
        PrepareGateDecision gateDecision = prepareGate.tryEnter(executeAfterPrepare, queuedIntent);
        if (gateDecision == PrepareGateDecision.QUEUE_ONLY) {
            LOG.info("[M36][T-FirstExec F1] prepare chain in flight: execute intent queued, no second session/console chain");
            // T-DialectInferPollution W11（2026-10-24 用户真机「点执行 SQL 卡住」）：排队意图
            // 原本只能等 owner 的 60s 逃生被下一链接管才结算——排队者自身无触发点，UI 永远
            // 「执行中…」。给排队意图自己的有界结算：15s 未消费即 BLOCKED 解除加载态（门不动，
            // owner 链照常；用户重试即可）。
            final QueuedExecuteIntent queuedForTimeout = queuedIntent;
            javax.swing.Timer pendingTimer = new javax.swing.Timer((int) DataSourceSelector.PENDING_EXECUTE_TIMEOUT_MS,
                    event -> {
                        if (prepareGate.expirePendingExecute(queuedForTimeout, DataSourceSelector.pendingExecuteTimeoutMessage())) {
                            ((javax.swing.Timer) event.getSource()).stop();
                        }
                    });
            pendingTimer.setRepeats(false);
            pendingTimer.start();
            if (callback != null) callback.accept(true);
            return;
        }
        final boolean ownsGate = gateDecision == PrepareGateDecision.PROCEED;
        if (gateDecision == PrepareGateDecision.PROCEED) {
            LOG.info("[M36][T-FirstExec F1] prepare gate PROCEED: owning prepare chain (executeAfterPrepare=" + executeAfterPrepare + ")");
        }
        if (gateDecision == PrepareGateDecision.PASS_THROUGH) {
            LOG.info("[M36][T-FirstExec F1] prepare chain in flight: preview prepare passes through (no gate ownership)");
        }

        final QueuedExecuteIntent prepareOwner = ownsGate ? queuedIntent : null;
        // 只有 owner 看守后台；排队 B 不另开计时器截断 A 的用户交互。
        if (prepareOwner != null) {
            javax.swing.Timer expiryTimer = new javax.swing.Timer(1000, event -> {
                if (prepareGate.expire(prepareOwner)) {
                    ((javax.swing.Timer) event.getSource()).stop();
                }
            });
            expiryTimer.start();
        }

        // T-MysqlGStrip T-MG-02：提交链总口——SQL 文本进入 exec 文件/控制台文档之前剥离 MySQL CLI
        // 的 `\G` 后缀（DBX #35）。此点数据源尚未选定（runner 弹窗在下方），方言不可得 → 按计划
        // §三 定案默认剥离（`\G` 非任何方言合法语法，剥离无害）。剥后文本写入 exec 文件，
        // 控制台编辑器所见即所执行；历史链在预览侧记录原文，不受此处影响（两态分离）。
        String submitSql = stripGSuffixForSubmit(sql, null).submitText();

        // M36.5.5：共享控制台命中（last-used 数据源一致且控制台仍有效）→ 直接复用——
        // 不同步 session/连接/临时文件/方言映射，规避"第二个会话连接永不发起"挂死（见字段注释）。
        // 显式执行路径同样复用（结果落在已嵌入面板）；用户切换数据源走弹窗 → 新建并替换共享缓存。
        if (allowsSharedConsoleReuse(forceChoose) && reuseSharedConsole(project, submitSql, executeAfterPrepare,
                onConsole, onEditor, onError, callback, onErrorRich, wholeScriptExec,
                dialectGateRequest, onDialectGate, submission, prepareOwner)) {
            // T-FirstExecResultInvisible F1：复用路径同步收敛（不建链、不经 prepareAfterEditorResolved）
            // ——门持有链就地终结复位；PASS_THROUGH 预览链不动门。
            if (ownsGate) prepareGate.finishPrepare(prepareOwner);
            return;
        }

        // 临时文件 + VF + PsiFile：Runner 上下文 + 控制台挂载点（VF 须保持引用）
        TempPsiFile temp = createTempPsiFile(project, submitSql,
                dialectGateRequest != null ? dialectGateRequest.renderedDialectName() : null);
        if (temp == null) {
            // T-ExecCopyFix F2a：携带阶段原因（stage 标识/异常摘要），本地化文案——
            // 用户真机曾只见笼统英文 "Failed to create temporary SQL file"，无从归因
            LOG.warn("[M36] createTempPsiFile failed (reason=" + lastTempFileFailure + "), abort");
            PrepareSingleFlightGateKt.failPrepareRequests(prepareGate, prepareOwner, submission, onError, null,
                    MpSqlVisualizerBundle.message("execute.tempFile.failed",
                            lastTempFileFailure != null ? lastTempFileFailure : "unknown"));
            if (callback != null) callback.accept(false);
            return;
        }
        VirtualFile vf = temp.vf;
        PsiFile psiFile = temp.psi;

        final String[] chosenRunnerName = {null};
        java.util.function.Supplier<Boolean> isPrepareActive = () ->
                !queuedIntent.isCompleted()
                        && (!ownsGate || prepareGate.isOwner(queuedIntent));
        final Consumer<String> runnerError = message -> {
            if (isPrepareActive.get()) {
                PrepareSingleFlightGateKt.failPrepareRequests(prepareGate, prepareOwner, submission, onError,
                        null, message);
            }
        };
        com.intellij.util.Consumer<Object> sessionConsumer = session -> {
            if (!isPrepareActive.get()) {
                return;
            }
            ExecutionDialectGate.Target actualTarget = ExecutionDialectGate.fromSession(session);
            LOG.info("[M36][O-145] session created: "
                    + (session != null ? session.getClass().getName() : "null")
                    + ", target=" + (actualTarget != null ? actualTarget.stableId() : null) + "/"
                    + (actualTarget != null ? actualTarget.dialectName() : null)
                    + ", execute=" + executeAfterPrepare);
            boolean allowed = prepareGate.duringInteraction(prepareOwner, () ->
                    DataSourceSelector.allowDialectGate(project, dialectGateRequest, actualTarget, onDialectGate,
                            onError, submission));
            if (!allowed) {
                DataSourceSelector.abortPrepareGate(prepareOwner,
                        dialectGateRequest != null
                                ? DataSourceSelector.dialectGateMessage(ExecutionDialectGate.evaluate(
                                        dialectGateRequest,
                                        actualTarget != null ? actualTarget.dialectName() : null))
                                : null,
                        LOG);
                if (callback != null) callback.accept(false);
                return;
            }
            // 确认框的模态事件泵期间可能超时/被替代，返回 Yes 也不能复活旧准备链。
            if (!isPrepareActive.get()) {
                return;
            }
            DataSourceSelectorConsoleKt.prepareInConsole(project, session, vf, executeAfterPrepare,
                    chosenRunnerName[0], onConsole, onEditor, onError, onErrorRich, wholeScriptExec,
                    prepareOwner, dialectGateRequest, onDialectGate, submission, isPrepareActive);
        };

        List<Object> enumerated = getAttachDataSourceRunners(psiFile, submitSql, sessionConsumer);
        // O-136 T-PreviewExecDsDialect：组 Runner 展开——平台在「方言匹配集 ≠ 全部数据源」时返回
        // "All" 组 Runner（getSubRunners 携带全部数据源的 Runner，组项自身 runnable 为空操作），
        // 插件弹窗不展开子级 → 方言不匹配的数据源（DM 等）藏在死项里不可选。展开后按显示名去重，
        // 方言不匹配的数据源在弹窗内直接可见可选（计划 §三「按可选数据源的方言出 Runner」）。
        List<Object> runners = ExecDialectRouting.expandGroupRunners(enumerated);
        if (runners.size() != enumerated.size()) {
            LOG.info("[M36] runners=" + enumerated.size() + " expanded to " + runners.size()
                    + " (O-136 group runners flattened)");
        } else {
            LOG.info("[M36] runners=" + runners.size());
        }

        // M36.5：last-used 数据源静默命中（仅 prepareConsole 路径；显式执行保持弹窗可选）
        // T-SI-02：命中判定升级为「UUID 精确命中 → 名字唯一命中」，同名多条判歧义弃记忆走弹窗
        //（旧实现 firstOrNull 静默取首条，选错数据源时用户无从察觉）。
        // T-DialectGateExit：forceChoose（用户点「切换数据源」）同样跳过——否则点了等于没点。
        if (allowsSilentLastUsed(executeAfterPrepare, forceChoose)) {
            // T-ModuleScopedLastUsed W2：静默复用也按模块读——模块 A 上次用的源不该被模块 B 复用。
            String moduleKeyForLastUsed = dialectGateRequest != null ? dialectGateRequest.moduleKey() : null;
            LastUsedMemory memory = lastUsedRunner(project, moduleKeyForLastUsed);
            LastUsedResolution resolution = resolveLastUsedRunnerIndex(memory,
                    runners.stream().map(DataSourceSelector::getRunnerName).collect(java.util.stream.Collectors.toList()),
                    id -> {
                        try {
                            Object ds = DataSourceSettings.getDataSourceById(project, id);
                            return ds != null ? DataSourceSettings.getName(ds) : null;
                        } catch (Throwable ignored) {
                            return null;
                        }
                    });
            Object matched = (resolution.index() != null && resolution.index() >= 0 && resolution.index() < runners.size())
                    ? runners.get(resolution.index()) : null;
            if (matched != null) {
                String matchedName = getRunnerName(matched);
                LOG.info("[M36] silent reuse last runner: " + matchedName + " (memory id=" + memory.dataSourceId() + ")");
                chosenRunnerName[0] = matchedName;
                runRunner(project, matched, true, runnerError, moduleKeyForLastUsed);
                if (callback != null) callback.accept(true);
                return;
            }
            if (resolution.ambiguous()) {
                LOG.info("[M36] last-used memory ambiguous (id=" + memory.dataSourceId() + ", name=" + memory.displayName()
                        + ") - drop memory, show popup");
                // 只清该模块那一层：其它模块的记忆与项目级兜底不该被一处的歧义连坐。
                forgetLastRunner(project, moduleKeyForLastUsed);
            }
        }

        if (preferredRunnerName != null
                && runners.stream().filter(r -> getRunnerName(r).equals(preferredRunnerName)).count() == 1) {
            // T-ExplainFailUx W4：用户已在下拉里显式指定源名 → 按名唯一命中直接建链，不再弹选择器
            //（一次选择即生效；名字未命中/歧义回落弹窗，不猜）。
            Object picked = runners.stream().filter(r -> getRunnerName(r).equals(preferredRunnerName)).findFirst().orElse(null);
            LOG.info("[M36] preferred runner by name: " + preferredRunnerName + " (skip platform chooser)");
            chosenRunnerName[0] = preferredRunnerName;
            String moduleKeyForPreferred = dialectGateRequest != null ? dialectGateRequest.moduleKey() : null;
            if (!executeAfterPrepare) rememberRunnerName(project, preferredRunnerName, moduleKeyForPreferred);
            runRunner(project, picked, !executeAfterPrepare, runnerError, moduleKeyForPreferred);
            if (callback != null) callback.accept(true);
        } else if (runners.isEmpty()) {
            // O-136 T-PreviewExecDsDialect D3：空 Runner 二分归因——有在册数据源 = 方言不匹配
            //（或数据源均不可连接），给可行动文案（V3），不再让用户笼统「没数据源」或等超时；
            // 真无数据源保持既有引导文案。
            List<Object> registered = java.util.List.of();
            try {
                registered = DataSourceSettings.getAllDataSources(project);
            } catch (Throwable ignored) {
                registered = java.util.List.of();
            }
            LOG.warn("[M36] runners empty (execute=" + executeAfterPrepare + ", registered=" + registered.size() + ")");
            // T-DsCover：仅显式执行引导打开 Database 工具窗；预览默认准备静默——
            // 补全 M36.5 预览路径（onError=null）设计意图，避免每次双击预览抢占侧边栏。
            if (executeAfterPrepare) openDatabaseToolWindow(project);
            if (registered.isEmpty()) {
                if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.datasource.empty"));
            } else {
                Object targetDs = ExecDialectRouting.resolveTargetDataSource(project);
                Object targetLanguage = ExecDialectRouting.resolveTargetLanguage(project, targetDs);
                String dialectDisplay = targetLanguage instanceof com.intellij.lang.Language language
                        ? language.getID() : ExecDialectRouting.MYSQL_LANGUAGE_ID;
                StringBuilder names = new StringBuilder();
                for (Object ds : registered) {
                    if (names.length() > 0) names.append(", ");
                    names.append(DataSourceSettings.getName(ds));
                }
                if (onError != null) {
                    onError.accept(MpSqlVisualizerBundle.message("execute.datasource.dialectMismatch",
                            dialectDisplay, names.toString()));
                }
            }
            // M36.3：无数据源时通知 UI 从「执行中」恢复
            // T-FirstExecResultInvisible F1：链就此终结，复位单飞位（否则后续执行被永久排队）
            submission.accept(ExecutionSubmission.BLOCKED);
            if (ownsGate) prepareGate.abort(prepareOwner, ExecutionSubmission.BLOCKED, null);
            if (callback != null) callback.accept(false);
        } else if (runners.size() == 1 && shouldBeChosenIfAlone(runners.get(0)) && !forceChoosePopup) {
            LOG.info("[M36] single runner, run directly");
            chosenRunnerName[0] = getRunnerName(runners.get(0));
            runRunner(project, runners.get(0), !executeAfterPrepare, runnerError,
                    dialectGateRequest != null ? dialectGateRequest.moduleKey() : null);
            if (callback != null) callback.accept(true);
        } else {
            LOG.info("[M36] show popup with " + runners.size() + " runners");
            prepareGate.beginInteraction(prepareOwner);
            showRunnerPopup(project, runners, runner -> {
                prepareGate.endInteraction(prepareOwner);
                if (!isPrepareActive.get()) {
                    return;
                }
                LOG.info("[M36] popup selected: " + (runner != null ? getRunnerName(runner) : "null"));
                if (!executeAfterPrepare && runner != null) {
                    rememberRunnerName(project, getRunnerName(runner),
                            dialectGateRequest != null ? dialectGateRequest.moduleKey() : null);
                }
                if (runner != null) chosenRunnerName[0] = getRunnerName(runner);
                if (callback != null) callback.accept(runner != null);
                // T-FirstExecResultInvisible F1：用户取消弹窗 = 链就此终结，复位单飞位
                if (runner == null) {
                    if (ownsGate) prepareGate.abort(prepareOwner, ExecutionSubmission.CANCELLED, null);
                    submission.accept(ExecutionSubmission.CANCELLED);
                }
                // 弹窗中用户刚做了主动选择，失败仍需模态引导（silent=false）
                if (runner != null) runRunner(project, runner, false, runnerError,
                        dialectGateRequest != null ? dialectGateRequest.moduleKey() : null);
            });
        }
    }


    public static boolean reuseSharedConsole(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        return reuseSharedConsole(project, sql, executeAfterPrepare, onConsole, onEditor, onError, callback,
                onErrorRich, wholeScriptExec, null, null, null, null);
    }

    public static boolean reuseSharedConsole(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            @Nullable QueuedExecuteIntent prepareOwner) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        Object console = sharedConsole;
        if (console == null) return false;
        Editor editor = sharedEditor;
        if (editor == null) return false;
        JComponent top = sharedTopBlock;
        if (top == null) return false;
        String dsName = sharedDataSourceName;
        if (dsName == null) return false;
        Object scriptModel = sharedScriptModel;
        if (scriptModel == null) return false;
        Object execOption = sharedExecOption;
        if (execOption == null) return false;
        if (!Objects.equals(lastRunnerName(project), dsName)) return false;

        executionGeneration++;
        boolean valid;
        try {
            Object isValid = console.getClass().getMethod("isValid").invoke(console);
            valid = Boolean.TRUE.equals(isValid) && !editor.isDisposed();
        } catch (Throwable ignored) {
            valid = false;
        }
        // T-SI-01：复用身份 fail-closed——复用判定从「显示名相等 + isValid」升级为「显示名相等 +
        // isValid + 三分量探测的 dsInFacade 分量」（复用 attach 路径既有探测，不重造）。复用是
        // 低频路径，探测是内存级反查（DbPsiFacade.findDataSource），成本可接受。
        // 身份被拒时弃共享会话并清 last-used 记忆——旧实现只丢会话不清记忆，下一轮仍会按显示名
        // 静默命中（同名数据源场景直接钉错库），与 attach 路径同规：身份不可信即弃，不猜。
        ConsoleValidity identity = probeConsoleValidity(console, project, LOG);
        if (!valid || !reuseIdentityAccepted(identity)) {
            LOG.warn("[M36] shared console identity rejected (ds=" + dsName + ", isValid=" + valid + ", " + identity + "), drop session");
            dropSharedConsole();
            if (identity != null && Boolean.FALSE.equals(identity.dsInFacade)) {
                forgetLastRunner(project, dialectGateRequest != null ? dialectGateRequest.moduleKey() : null);
            }
            return false;
        }
        try {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                editor.getDocument().setText(sql);
            });
        } catch (Throwable ignored) {
            // ignore
        }
        Object refreshedModel = scriptModel;
        try {
            Object consoleVf = console.getClass().getMethod("getVirtualFile").invoke(console);
            if (consoleVf instanceof VirtualFile) {
                VirtualFile cvf = (VirtualFile) consoleVf;
                WriteAction.run(() -> {
                    // T-ExecDocSync2 F1：同步收口为 writeVfTextViaDocument（T-EC-01 语义超集：
                    // setText-if-differ + commit + saveDocument；fileDoc==null 走 setBinaryContent）。
                    // 原实现只 commit 不落盘——console document 即 exec 文件 document（attachConsole
                    // 挂载点同文件），滞留「已修改未保存」+ 窗口期任意外部改盘 + VFS refresh =
                    // File Cache Conflict 弹窗（第四轮根因 G2，开发计划 §1.2）
                    writeVfTextViaDocument(project, cvf, sql);
                });
                // T-ConsoleDbContext B：复用链同样对齐库上下文（控制台可能来自别的库记忆）
                LOG.info("[M36] shared console db-context: " + ExecConsoleContext.alignToDataSourceDatabase(console));
                final Object consoleForModel = console;
                final VirtualFile vfForModel = cvf;
                DumbService.getInstance(project).runWhenSmart(() ->
                        // write-safe: 无 document 写（反射替换 DBN script model）
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                DataSourceSelectorConsoleKt.refreshConsoleModel(project, consoleForModel, vfForModel, LOG);
                            } catch (Throwable e) {
                                LOG.warn("[M36] shared scriptModel refresh (smart) FAILED: " + e.getMessage());
                            }
                        }, ModalityState.any()));
            }
        } catch (Throwable e) {
            LOG.warn("[M36] shared scriptModel vf-sync FAILED (keep cached): " + e.getMessage());
        }
        if (!executeAfterPrepare) {
            clearResultContents(sharedUi, LOG);
            clearConsoleHistory(console, LOG);
        }
        JComponent liveResult = null;
        try {
            Object c = sharedUi != null ? sharedUi.getClass().getMethod("getComponent").invoke(sharedUi) : null;
            liveResult = c instanceof JComponent ? (JComponent) c : null;
        } catch (Throwable ignored) {
            // ignore
        }
        // ★A49：**无嵌入宿主（onConsole == null）时不重摘 DBN 结果组件**——prepare 侧已 gate，但复用链
        // 若仍 swap 会再次把结果区组件从 DBN 原生宿主摘进我方永不展示的 SplitPane（swapResultIntoPersistentConsole
        // 的强制换入分支会 remove 外部宿主 + 设 bottomComponent）。无宿主时摘取无收益、只扰动 DBN 组件树
        //（详见 shouldComposeEmbedSplitPane 的归因更正：这是排查「网格 0 rows」时顺带发现的收敛，
        // 该现象真因是调用方传了不存在的表名）。无宿主时沿用既有缓存（可能为 null）；有宿主（预览面板
        // 复用）时判据化简后与改前逐字等价，行为不变。
        boolean hasHost = onConsole != null;
        boolean composeNow = shouldComposeEmbedSplitPane(hasHost, liveResult != null);
        JComponent full;
        JComponent sharedFull = sharedFullComponent;
        if (composeNow && liveResult != null) {
            // M36.5.43：swap 助手强制换入（不再返回 null）——结果区从 DBN 宿主摘入本 SplitPane
            full = swapResultIntoPersistentConsole(sharedFull, liveResult, top, LOG);
        } else {
            full = sharedFull;
        }
        if (hasHost && full == null) return false;
        if (liveResult != null) lastLiveResultSwapped = liveResult;
        if (hasHost) sharedFullComponent = full;
        preparedConsole = console;
        preparedEditor = editor;
        preparedScriptModel = refreshedModel != null ? refreshedModel : scriptModel;
        preparedExecOption = execOption;
        sharedScriptModel = refreshedModel != null ? refreshedModel : scriptModel;
        preparedUi = sharedUi;
        preparedTopBlock = top;
        preparedResultComponent = liveResult instanceof JComponent ? (JComponent) liveResult : null;
        if (hasHost) preparedFullComponent = full;
        if (onEditor != null) onEditor.accept(editor);
        if (full != null && onConsole != null) onConsole.accept(full);
        if (!executeAfterPrepare) {
            // write-safe: UI-only（纯 Swing/只读，无文档写）
            if (full instanceof JSplitPane) {
                final JComponent fullX = full;
                // T-FirstExecResultInvisible F3：折叠归口 embedMode——仅 PREVIEW 且结果区无内容才折叠
                //（上方清空失败/竞态下已有内容则保持展开，不吞用户可见的结果）。
                boolean collapse = ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent(sharedUi));
                if (collapse) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ((JSplitPane) fullX).setDividerLocation(1.0);
                        ((JSplitPane) fullX).revalidate();
                        ((JSplitPane) fullX).repaint();
                    }, ModalityState.any());
                }
            }
        }
        pendingOnConsole = onConsole;
        DataSourceSelectorConsoleKt.logDataContextCheck(top, console, editor, execOption, LOG);
        clearConsolePrompt(console, LOG);
        LOG.info("[M36] shared console reused (ds=" + dsName + ", execute=" + executeAfterPrepare + ", liveResult=" + (liveResult != null) + ")");
        if (executeAfterPrepare) {
            if (prepareOwner != null && !prepareGate.beginSubmission(prepareOwner)) {
                return true;
            }
            DataSourceSelectorConsoleKt.executeOnConsole(project, console, editor,
                    refreshedModel != null ? refreshedModel : scriptModel, execOption, sharedUi, null, onError,
                    onErrorRich, wholeScriptExec,
                    dialectGateRequest != null ? dialectGateRequest : DataSourceSelector.sharedDialectGateRequest,
                    onDialectGate != null ? onDialectGate : DataSourceSelector.sharedDialectGateCallback,
                    onSubmission);
        }
        if (callback != null) callback.accept(true);
        return true;
    }


    /** M36.5.6：结果区换入监听。 */
    public static void attachResultsHook(
            Object ui,
            JComponent topBlock,
            JComponent resultComponent,
            Object console,
            String sql,
            Logger LOG) {
        if (ui == null || resultComponent == null) return;
        try {
            Object cm = ui.getClass().getMethod("getContentManager").invoke(ui);
            if (!(cm instanceof ContentManager)) return;
            ContentManager contentManager = (ContentManager) cm;
            contentManager.addContentManagerListener(new ContentManagerListener() {
                @Override
                public void contentAdded(@NotNull ContentManagerEvent event) {
                    Content content = event.getContent();
                    String name = content != null ? content.getDisplayName() : null;
                    if (name == null) return;
                    LOG.info("[M36] contentAdded: " + name);
                    if (name.equalsIgnoreCase("Output") || name.equalsIgnoreCase("输出")) return;
                    // T-ExplainFirstNative：空名占位（DBN 先 add 后改名）换入必然过早——网格未构建，
                    // 交由 startResultTabRetry 在改名后 select+换入
                    if (name.isBlank()) return;
                    Consumer<JComponent> callback = pendingOnConsole;
                    if (callback == null) {
                        LOG.info("[M36] contentAdded swap-in SKIPPED (pendingOnConsole=null)");
                        return;
                    }
                    JComponent liveResult = null;
                    try {
                        Object c = ui.getClass().getMethod("getComponent").invoke(ui);
                        liveResult = c instanceof JComponent ? (JComponent) c : null;
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (liveResult == null) liveResult = resultComponent;
                    lastLiveResultSwapped = liveResult;
                    JComponent full = swapResultIntoPersistentConsole(
                            sharedFullComponent != null ? sharedFullComponent : preparedFullComponent,
                            liveResult, topBlock, LOG);
                    preparedFullComponent = full;
                    sharedFullComponent = full;
                    sharedTopBlock = topBlock;
                    LOG.info("[M36] contentAdded swap-in dispatched (name=" + name + ", rebuilt=true)");
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            callback.accept(full);
                        } catch (Throwable ex) {
                            LOG.warn("[M36] contentAdded swap-in FAILED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
                        }
                        try {
                            Editor ed = (sharedEditor != null ? sharedEditor : preparedEditor);
                            String currentSql = (ed != null && ed.getDocument().getText() != null && !ed.getDocument().getText().isBlank())
                                    ? ed.getDocument().getText() : sql;
                            Object consoleDoc = console.getClass().getMethod("getDocument").invoke(console);
                            if (consoleDoc instanceof Document) {
                                Document cd = (Document) consoleDoc;
                                if (!cd.getText().equals(currentSql)) {
                                    Project proj = null;
                                    try {
                                        Object p = console.getClass().getMethod("getProject").invoke(console);
                                        proj = p instanceof Project ? (Project) p : null;
                                    } catch (Throwable ignored) {
                                        // ignore
                                    }
                                    if (proj == null) return;
                                    final Project fProj = proj;
                                    final String fSql = currentSql;
                                    WriteCommandAction.runWriteCommandAction(fProj, () -> {
                                        cd.setText(fSql);
                                        // T-ExecDocSync2 F3：同临界区落盘——console document 即 exec 文件
                                        // document（attachConsole 挂载点），只 setText 不落盘滞留
                                        // 「已修改未保存」窗口（第四轮根因 G4，开发计划 §1.2）。
                                        // 落盘失败不回滚改写本身（显示优先），仅记录
                                        try {
                                            Object p = console.getClass().getMethod("getProject").invoke(console);
                                            Project p2 = p instanceof Project ? (Project) p : null;
                                            if (p2 != null) {
                                                PsiDocumentManager.getInstance(p2).commitDocument(cd);
                                                FileDocumentManager.getInstance().saveDocument(cd);
                                            }
                                        } catch (Throwable e2) {
                                            LOG.warn("[M36] contentAdded post-rewrite save FAILED: " + e2.getMessage());
                                        }
                                    });
                                    try {
                                        Editor e2 = Arrays.stream(EditorFactory.getInstance().getEditors(cd)).findFirst().orElse(null);
                                        if (e2 != null) {
                                            e2.getSelectionModel().removeSelection();
                                            e2.getCaretModel().moveToOffset(0);
                                        }
                                    } catch (Throwable ignored) {
                                        // ignore
                                    }
                                    LOG.info("[M36] contentAdded: console doc rewritten (len=" + cd.getTextLength() + ")");
                                }
                            }
                        } catch (Throwable e) {
                            LOG.warn("[M36] contentAdded rewrite FAILED: " + e.getMessage());
                        }
                        // T-WSC-02：本回调写 console 文档（setText + commitDocument + saveDocument）且用的是
                        // 快照文本、不幂等——必须「投递时即 write-safe」，不能用重投递自愈，故 any() 改
                        // nonModal（any() 恒 write-unsafe，见 DocWriteContext KDoc）。
                    }, ModalityState.nonModal());
                }
            });
            LOG.info("[M36] results hook attached (contentAdded -> embed results)");
        } catch (Throwable e) {
            LOG.warn("[M36] results hook attach FAILED: " + e.getMessage());
        }
    }


    /**
     * M36.5.70：用 DBN ConsoleView 原生 print API 把文本写入 Output 控制台可视区。
     *
     * <p>T-FatalPassthrough T-FP-02b：{@code public} 供 {@code FatalPassthroughTest} 直测本函数的<b>外层
     * runCatching 边界</b>（同 {@link #execFileContentMatches} / {@link #isExecDocOpenInEditor} 先例）
     * ——私有则行为用例无处注入。</p>
     */
    public static void writeConsoleOutput(Object console, String text, boolean isError) {
        if (console == null || text.isBlank()) return;
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) { LOG.info("[M36] writeConsoleOutput: consoleView null"); return; }
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer != null) {
                Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
                if (doc instanceof Document) {
                    Document d = (Document) doc;
                    String append = (!d.getText().isEmpty() && !d.getText().endsWith("\n")) ? "\n" : "";
                    try {
                        Project proj = (console instanceof Project) ? (Project) console : null;
                        if (proj == null) {
                            try {
                                Object p = console.getClass().getMethod("getProject").invoke(console);
                                proj = p instanceof Project ? (Project) p : null;
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                        final Project fProj = proj;
                        WriteCommandAction.runWriteCommandAction(fProj, () -> d.insertString(d.getTextLength(), append + text));
                        // 滚到末尾（新输出可见）
                        // write-safe: 无 document 写（反射替换 DBN script model）
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                Editor ed = Arrays.stream(EditorFactory.getInstance().getEditors(d)).findFirst().orElse(null);
                                if (ed != null) {
                                    ed.getCaretModel().moveToOffset(d.getTextLength());
                                    ed.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }, ModalityState.any());
                        LOG.info("[M36] writeConsoleOutput: appended to history doc len=" + d.getTextLength());
                        return;
                    } catch (Throwable e) {
                        // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（见 util/FatalErrors.kt）——
                        // 本文件是执行链长驻路径，捕获面覆盖 Error：普通异常（含可选能力探测的
                        // ClassNotFoundException）仍走既有降级，VirtualMachineError/LinkageError 不得
                        // 被吞成一条日志后让执行链带病继续。
                        // Java 形态：isFatal 蕴含 Error，显式收窄后 throw（裸 `throw e` 在 try 含受检异常时
                        // 因精确重抛分析要求方法声明该受检异常而不可编译；语义与原对象逐字一致，不包装）。
                        if (FatalErrors.isFatal(e)) throw (Error) e;
                        LOG.info("[M36] writeConsoleOutput doc-append FAILED: " + e.getMessage());
                    }
                }
            }
            Class<?> contentClass = null;
            try {
                contentClass = Class.forName("com.intellij.execution.ui.ConsoleViewContentType");
            } catch (Throwable ignored) {
                // ignore
            }
            if (contentClass != null) {
                Object ct;
                try {
                    String fieldName = isError ? "ERROR_OUTPUT" : "NORMAL_OUTPUT";
                    ct = contentClass.getField(fieldName).get(null);
                } catch (Throwable e) {
                    if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
                    ct = Arrays.stream(contentClass.getFields()).findFirst().map(f -> {
                        try { return f.get(null); } catch (Throwable ignored) { return null; }
                    }).orElse(null);
                }
                Method print = Arrays.stream(consoleView.getClass().getMethods())
                        .filter(m -> m.getName().equals("print") && m.getParameterCount() >= 2
                                && m.getParameterTypes()[0] == String.class)
                        .findFirst().orElse(null);
                if (ct != null && print != null) {
                    print.invoke(consoleView, text, ct);
                    LOG.info("[M36] writeConsoleOutput: ConsoleView.print ok");
                    return;
                } else {
                    LOG.info("[M36] writeConsoleOutput: ct=" + (ct != null) + " print=" + (print != null));
                }
            } else {
                LOG.info("[M36] writeConsoleOutput: ConsoleViewContentType class not found");
            }
        } catch (Throwable e) {
            // T-FatalPassthrough T-FP-02b（独立复核阻断 1 修正）：本函数的 runCatching 包住上面两处
            // fatal 透传（doc-append 捕获块 / ConsoleViewContentType 字段回退捕获块）——在此把 fatal
            // 吞成一行 INFO 等于透传无效（改前实测：fatal 被转成 "[M36] writeConsoleOutput FAILED: …"
            // 后执行链带病继续，正是上面捕获块注释声称的反面）。反射链先解包 InvocationTargetException
            // 再判（同本文件原生执行链 :2604-2607 形态；只判外层会漏判目标方法抛出的 fatal）。
            // 非 fatal 路径行为逐字节不变：仍只记原 INFO 行，函数返回 void、调用方（EDT 排出的
            // writeConsoleOutput + onError 回调）行为不变；fatal 则透传到平台 EDT 处理器记录现场。
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            LOG.info("[M36] writeConsoleOutput FAILED: " + e.getMessage());
        }
    }

    /** isError 缺省重载（Kotlin 默认参数 {@code isError = false} 的缺省形态）。 */
    public static void writeConsoleOutput(Object console, String text) {
        writeConsoleOutput(console, text, false);
    }


    /** M36.5.60：运行时组件边界取证。 */
    public static void dumpSplitBounds(JSplitPane sp, Logger log) {
        // T-Perf-13：取证 dump 降 debug 门控（非 debug 模式零开销）
        if (!log.isDebugEnabled()) return;
        try {
            java.util.function.Function<Component, String> b = c -> {
                if (c == null) return "null";
                return c.getClass().getSimpleName() + "@" + System.identityHashCode(c) + " bounds=" + c.getBounds()
                        + " pref=" + c.getPreferredSize() + " min=" + c.getMinimumSize() + " max=" + c.getMaximumSize();
            };
            log.info("[M36][BOUNDS] sp=" + b.apply(sp) + " divider=" + sp.getDividerLocation());
            log.info("[M36][BOUNDS]   top=" + b.apply(sp.getTopComponent()));
            Container top = sp.getTopComponent() instanceof Container ? (Container) sp.getTopComponent() : null;
            if (top != null) {
                Component[] tc = top.getComponents();
                for (int i = 0; i < tc.length; i++) {
                    log.info("[M36][BOUNDS]   top.child[" + i + "]=" + b.apply(tc[i]));
                    if (tc[i] instanceof Container) {
                        Component[] dc = ((Container) tc[i]).getComponents();
                        for (int j = 0; j < dc.length; j++) {
                            log.info("[M36][BOUNDS]   top.child[" + i + "].child[" + j + "]=" + b.apply(dc[j]));
                            if (dc[j] instanceof Container) {
                                Component[] ec = ((Container) dc[j]).getComponents();
                                for (int k = 0; k < ec.length; k++) {
                                    log.info("[M36][BOUNDS]   top.child[" + i + "].child[" + j + "].child[" + k + "]=" + b.apply(ec[k]));
                                    if (ec[k] instanceof JScrollPane) {
                                        JScrollPane sp2 = (JScrollPane) ec[k];
                                        log.info("[M36][BOUNDS]     scrollpane viewport=" + (sp2.getViewport() != null
                                                ? b.apply(sp2.getViewport()) + " vsbV=" + (sp2.getVerticalScrollBar() != null && sp2.getVerticalScrollBar().isVisible())
                                                : "null"));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            log.info("[M36][BOUNDS]   bottom=" + b.apply(sp.getBottomComponent()));
            Container bottom = sp.getBottomComponent() instanceof Container ? (Container) sp.getBottomComponent() : null;
            if (bottom != null) {
                Component[] bc = bottom.getComponents();
                for (int i = 0; i < bc.length; i++) {
                    log.info("[M36][BOUNDS]   bottom.child[" + i + "]=" + b.apply(bc[i]));
                    if (bc[i] instanceof Container) {
                        Component[] dc = ((Container) bc[i]).getComponents();
                        for (int j = 0; j < dc.length; j++) dumpDeep(dc[j], 2, "bottom.child[" + i + "].child[" + j + "]", log);
                    }
                }
            }
            if (bottom != null) scanTextViews(bottom, "bottom", log);
        } catch (Throwable e) {
            log.info("[M36][BOUNDS] dump failed: " + e.getMessage());
        }
    }

    public static void dumpDeep(Component c, int depth, String path, Logger log) {
        if (c == null || depth > 12) return;
        String extra = c instanceof JSplitPane ? " div=" + ((JSplitPane) c).getDividerLocation() + " split(" + ((JSplitPane) c).getOrientation() + ")" : "";
        log.info("[M36][BOUNDS]   " + path + "=" + c.getClass().getSimpleName() + "@" + System.identityHashCode(c) + " bounds=" + c.getBounds() + extra);
        if (c instanceof Container) {
            int idx = 0;
            for (Component d : ((Container) c).getComponents()) {
                dumpDeep(d, depth + 1, path + ".child[" + idx + "]", log);
                idx++;
            }
        }
    }

    public static void scanTextViews(Component c, String path, Logger log) {
        if (c == null) return;
        String cls = c.getClass().getName();
        if (c instanceof JScrollPane || cls.contains("ConsoleView") || cls.contains("EditorImpl") || c instanceof javax.swing.text.JTextComponent) {
            log.info("[M36][BOUNDS][TEXT] " + path + " " + cls.substring(cls.lastIndexOf('.') + 1) + " bounds=" + c.getBounds() + " pref=" + c.getPreferredSize());
            if (c instanceof JScrollPane) {
                JScrollPane jsp = (JScrollPane) c;
                log.info("[M36][BOUNDS][TEXT]   viewport=" + (jsp.getViewport() != null ? jsp.getViewport().getBounds().toString() : "null")
                        + " vsbV=" + (jsp.getVerticalScrollBar() != null && jsp.getVerticalScrollBar().isVisible()));
                if (jsp.getViewport() != null) scanTextViews(jsp.getViewport().getView(), path + ".view", log);
            }
        }
        if (c instanceof Container) {
            int idx = 0;
            for (Component d : ((Container) c).getComponents()) {
                scanTextViews(d, path + ".child[" + idx + "]", log);
                idx++;
            }
        }
    }


    /** M36.5.22：真实提交后的统一收尾。（T-ExecSilentNoop：dsName/demotedOverride 供提示三分流与去重） */
    public static void afterSubmitted(
            @NotNull Project project,
            Object ui,
            Object console,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            Logger LOG,
            String dsName,
            boolean demotedOverride,
            @Nullable String sqlText,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, demotedOverride, sqlText,
                onErrorRich, null);
    }

    public static void afterSubmitted(
            @NotNull Project project,
            Object ui,
            Object console,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            Logger LOG,
            String dsName,
            boolean demotedOverride,
            @Nullable String sqlText,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            @Nullable Consumer<ExecutionSubmission> onSubmission) {
        if (onSubmission != null) onSubmission.accept(ExecutionSubmission.SUBMITTED);
        swapGeneration++;
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater(() -> {
            List<Content> pre = resultContents(ui);
            Set<String> preExistingNames = pre.stream().map(Content::getDisplayName).collect(Collectors.toSet());
            LOG.info("[M36] result watch started (pre-existing-names=" + preExistingNames + ", ids=" + pre.stream().map(System::identityHashCode).toList() + ")");
            // ★A49 取证：提交时刻结果区组件的**宿主**——判「有没有被摘走」的决定性读数（无论本拍是否
            // 发生 swap 都打）。`parent` 全类名是判别力所在：`…ServiceViewComponentWrapper` = 仍在 DBN
            // 原生宿主（未摘）；`…buildConsoleSplitPane$1` = 已在我方容器（摘了）。
            try {
                JComponent liveNow = null;
                try {
                    Object c = ui != null ? ui.getClass().getMethod("getComponent").invoke(ui) : null;
                    liveNow = c instanceof JComponent ? (JComponent) c : null;
                } catch (Throwable ignored) {
                    // getOrNull 语义
                }
                JComponent mySp = sharedFullComponent != null ? sharedFullComponent : preparedFullComponent;
                Component myBottom = mySp instanceof JSplitPane ? ((JSplitPane) mySp).getBottomComponent() : null;
                Object viewShowing;
                try {
                    Object v = console.getClass().getMethod("getView").invoke(console);
                    viewShowing = v != null ? v.getClass().getMethod("isShowing").invoke(v) : null;
                } catch (Throwable ignored) {
                    viewShowing = null; // getOrNull 语义
                }
                LOG.info("[M36][A49] result host at submit: parent="
                        + (liveNow != null && liveNow.getParent() != null ? liveNow.getParent().getClass().getName() : "null")
                        + ", mySplitBottom=" + (myBottom != null ? myBottom.getClass().getName() : "null")
                        + ", stolen=" + (liveNow != null && mySp != null && liveNow == myBottom && liveNow.getParent() == mySp)
                        + ", viewShowing=" + viewShowing);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
                // 取证失败只略过（runCatching 语义）
            }
            if (ui != null && onError != null) {
                final Object uiForWatch = ui;
                final Consumer<String> hintOnError = onError;
                final Object consoleForWatch = console;
                Timer watch = new Timer(8000, null);
                watch.addActionListener(e -> {
                    watch.stop();
                    boolean newTab = resultContents(uiForWatch).stream().anyMatch(c ->
                            c.getDisplayName() != null && !c.getDisplayName().isBlank()
                                    && !preExistingNames.contains(c.getDisplayName()));
                    boolean namespaceOk;
                    try {
                        Object ns = consoleForWatch.getClass().getMethod("getCurrentNamespace").invoke(consoleForWatch);
                        namespaceOk = ns != null && !ns.toString().isBlank();
                    } catch (Throwable ignored) {
                        namespaceOk = true;
                    }
                    if (!newTab && !namespaceOk) {
                        // T-ExecSilentNoop：提交后 8s 回执——namespace 都出来了说明连接<b>已建立</b>、
                        // 探针显示未提交（freshModel 回落）则归因"未提交"而非凭据；否则才落到
                        // 凭据/驱动。与 40 拍窗口共用 executionHintReported 去重，同次执行只报一次。
                        if (!executionHintReported) {
                            executionHintReported = true;
                            String hint = demotedOverride
                                    ? MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName != null ? dsName : "")
                                    : MpSqlVisualizerBundle.message("execute.credential.hint");
                            LOG.info("[M36] no NEW result tab AND no namespace within 8s (newTab=" + newTab + ", namespaceOk=" + namespaceOk
                                    + ", demotedOverride=" + demotedOverride + ") - hint=" + (demotedOverride ? "notSubmitted" : "credential"));
                            hintOnError.accept(hint);
                        }
                    } else {
                        LOG.info("[M36] result/layout OK within 8s (newTab=" + newTab + ", namespaceOk=" + namespaceOk + ") - no hint");
                    }
                });
                watch.setRepeats(false);
                watch.start();
            }
            if (onResultsReady != null) {
                JComponent top = preparedTopBlock;
                JComponent liveResult = null;
                try {
                    Object c = ui != null ? ui.getClass().getMethod("getComponent").invoke(ui) : null;
                    liveResult = c instanceof JComponent ? (JComponent) c : null;
                } catch (Throwable ignored) {
                    // ignore
                }
                if (liveResult == null) liveResult = preparedResultComponent;
                if (top != null && liveResult != null) {
                    JComponent full = swapResultIntoPersistentConsole(preparedFullComponent, liveResult, top, LOG);
                    onResultsReady.accept(full);
                    clearConsolePrompt(console, LOG);
                }
            }
            if (ui != null) {
                Set<String> names = preExistingNames;
                startResultTabRetry(project, ui, onError, names, swapGeneration, dsName, demotedOverride, sqlText, onErrorRich);
            }
        }, ModalityState.any());
    }


    /**
     * ★A48：结果换入判定（纯函数，{@link #refreshResultsComponent} 每拍调用）——在既有三条判据
     * （强制首换 / 组件实例变化 / 结果页签名称集合变化）之外补**第四判据：已换入组件被 DBN 挂回原生宿主**。
     *
     * <p>根因（M36.5.43 沙盒实证，见 {@link #swapResultIntoPersistentConsole} KDoc）：DBN 结果区组件的
     * 「长期老家」是 {@code ServiceViewComponentWrapper}，DBN 在**本次提交新建结果页签**时会把它重新
     * 挂回该宿主（同一实例，仅 parent 变）。原判据只比较**组件身份**（live !== lastLiveResultSwapped）
     * 与**页签名称集合**——re-parent 两者都不变，判据恒 false，SplitPane 底部因此滞留空组件，
     * 结果只落在 IDE 底部原生控制台（用户实证「同一会话第一次点执行预览窗内看不到结果，再点一次
     * 才有」）。复用旧页签路径不新建内容、不触发挂父，故不中招——与两次执行日志对照一致
     * （newTab=true 坏 / newTab=false 好）。M36.5.43 声称的「被 DBN 抢走则下拍换回」正是该判据，
     * 但身份去重把它短路成了**一次性换入**。</p>
     *
     * @param host        持久整控台 SplitPane（换入目标；非分栏形态传 null）
     * @param hostBottom  host 当前底部组件
     * @param live        本拍从 {@code ui.getComponent()} 取到的结果组件
     * @param lastSwapped 上一次换入的组件（去重基线）
     * @return true = 本拍需要（重新）换入
     */
    public static boolean resultSwapNeeded(
            @Nullable Component host,
            @Nullable Component hostBottom,
            @Nullable Component live,
            @Nullable Component lastSwapped,
            boolean namesChanged,
            boolean forceSwapIn,
            boolean alreadySwapped) {
        if (live == null) return false;
        if (forceSwapIn && !alreadySwapped) return true;
        if (live != lastSwapped) return true;
        if (namesChanged) return true;
        // 第四判据：身份/名称都未变，但组件已不在本 SplitPane 底部（被 DBN 挂回原生宿主）——
        // 身份去重看不见 re-parent，须按「仍在位」判定，幂等重挂回底部。
        return host != null && (hostBottom != live || live.getParent() != host);
    }

    /**
     * ★A49：是否组我方整控台 SplitPane——即是否允许把 DBN 结果区组件（{@code ui.getComponent()}）摘进
     * 本插件容器（{@link #buildConsoleSplitPane} / {@link #swapResultIntoPersistentConsole}）。**只有存在嵌入宿主
     * （onConsole 回调）时才允许。**
     *
     * <p>无宿主路径（SQL 快捷模板三动作 / 日志转 SQL 的「执行」，二者都走
     * {@code SqlExecutor.execute(onConsole = null)}）只把语句交给**原生 Database 控制台**执行，控制台由
     * DBN 在 IDE 底部 Services 视图自行展示。此时组出来的 SplitPane **永不展示**（{@code onConsole?.invoke}
     * 是 no-op，{@code pendingOnConsole} 也为 null 故 {@link #refreshResultsComponent} 直接早退、无人回收），
     * 摘取结果组件对本路径**无任何收益**，却扰动 DBN 自己的组件树（把结果区组件从原生宿主
     * {@code ServiceViewComponentWrapper} 摘下、再挂进我方容器）——属 ★A48 的**同族现象**（A48 修的是嵌入
     * 路径上「回收判据看不见 re-parent」，本判据消的是无宿主路径上「本就不该摘」）。</p>
     *
     * <p><b>归因更正（2026-10-13）</b>：本判据最初的立项依据是「录制里原生控制台结果网格停在 0 rows」，
     * 后经沙盒控制台 <b>Output 页原文</b>查明该现象真因是**调用方传了不存在的表名**——录制脚本的右键
     * 锚点按单宽估算 CJK 列宽、落到 {@code schema.sql} 注释里的中文词上，动作据其拼出
     * {@code SELECT … FROM 对齐插件测试}，DBN 正确报 {@code [42S02][1146] Table 'mpj_demo.对齐插件测试' doesn't exist}，
     * 网格 0 rows 是该报错的**正常表现**，**与插件行为无关**（锚点 x 610→740 修正后重录一次通过，
     * 网格 4 行）。故本判据是排查该现象时**顺带发现并收敛**的隐患，**不是该现象的修复**；保留理由＝
     * 无宿主路径摘取原生组件无收益、有扰动风险，去掉后更安全。</p>
     *
     * <p>两处组入点（prepare 侧 {@code prepareAfterEditorResolved}、复用侧 {@link #reuseSharedConsole}）同用本判据；
     * 嵌入/预览路径（{@code onConsole != null}）的判据在条件化简后与改前**逐字等价**，行为不变。</p>
     *
     * @param hasEmbedHost       本次准备/执行是否带嵌入宿主回调（{@code onConsole != null}）
     * @param hasResultComponent DBN 结果区组件是否取到（{@code resultComponent}/{@code liveResult} != null）
     * @return true = 组 SplitPane 并换入（有宿主）；false = 不组不摘，保持原生控制台渲染
     */
    public static boolean shouldComposeEmbedSplitPane(boolean hasEmbedHost, boolean hasResultComponent) {
        return hasEmbedHost && hasResultComponent;
    }

    /**
     * T-SqlTemplateUx P2 补：{@link #refreshResultsComponent} 的**组件换入**段是否需要执行——需同时具备
     * 嵌入宿主（{@code pendingOnConsole}）与持久容器（topBlock）。无宿主时摘 DBN 结果组件无收益、
     * 只扰动其组件树（{@link #shouldComposeEmbedSplitPane} 的 A49 口径）。
     *
     * <p><b>错误晚检段不受本判据约束</b>：无嵌入宿主路径（SQL 快捷模板三动作 / 日志转 SQL 的「执行」，
     * 均 {@code SqlExecutor.execute(onConsole = null)}）同样需要执行失败反馈——本函数拆两段正是为此，
     * 详见 {@link #refreshResultsComponent} 的 T-SqlTemplateUx P2 补说明。</p>
     */
    public static boolean refreshSwapEnabled(boolean hasEmbedHost, boolean hasTopBlock) {
        return hasEmbedHost && hasTopBlock;
    }

    /** M36.5.8/M36.5.9/M36.5.13：执行后结果区刷新——限时多次重取。 */
    public static void refreshResultsComponent(Object ui, Logger LOG, boolean forceSwapIn, int generation,
                                                @Nullable Consumer<String> onError,
                                                @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
                                                @Nullable String sqlText) {
        // T-SqlTemplateUx P2 补（真机 2026-10-13 录制实录）：本函数是「结果页签已出现、错误晚到」的
        // **唯一**晚检通道（startResultTabRetry 一旦找到结果 Tab 即停、不再扫错误）。此前首行以
        // `pendingOnConsole` 非空判定整体早退，把**无嵌入宿主**路径（SQL 快捷模板三动作 / 日志转 SQL
        // 的「执行」，均 SqlExecutor.execute(onConsole = null)）一并挡在门外——DBN 错误晚于结果页签写入
        // history 时无人检测，执行失败对用户完全静默（画面只剩控制台报错 + 0 rows）。故拆两段：
        // **错误晚检恒跑**（与宿主无关）；**组件换入**用 refreshSwapEnabled 收窄（无宿主不摘 DBN 组件，A49 口径）。
        Consumer<JComponent> callback = pendingOnConsole;
        JComponent top = sharedTopBlock != null ? sharedTopBlock : preparedTopBlock;
        boolean swapEnabled = refreshSwapEnabled(callback != null, top != null);
        Object shared = sharedConsole;
        final int[] ticks = {0};
        final boolean[] swapped = {false};
        // T-ExplainFirstNative：结果 Tab 名称集合逐拍快照——集合变化（空名占位改名/新 Tab 出现）
        // 说明结果内容更新，即使组件实例未变也要补一次换入重绘（首点网格晚于换入构建的兜底）
        @SuppressWarnings("unchecked")
        final Set<String>[] lastResultNames = new Set[1];
        Timer timer = new Timer(600, null);
        timer.addActionListener(e -> {
            ticks[0]++;
            if (generation != swapGeneration) {
                timer.stop();
                LOG.info("[M36] results refresh cancelled (generation " + generation + " != " + swapGeneration + ")");
                return;
            }
            // T-ExplainConsoleFirst W7：本函数没有 project 参数（结果区刷新的通用组件），
            // 故不传升格所需的工程上下文——跨源列证据在另两处（早检 / 超时兜底）生效。
            String err = extractConsoleError(shared != null ? shared : preparedConsole, LOG, sqlText);
            if (err != null && !err.equals(lastReportedError)) {
                timer.stop();
                lastReportedError = err;
                LOG.info("[M36] refresh console error detected (tick=" + ticks[0] + "): " + (err.length() > 120 ? err.substring(0, 120) : err));
                // T-WSC-02：writeConsoleOutput 内部是文档写（runWriteCommandAction + insertString），
                // 且上游是 Swing Timer 拍（Timer 活动同样非用户活动）——必须 write-safe 模态投递，
                // any() 恒 write-unsafe（见 DocWriteContext KDoc）。
                final String fErr = err;
                ApplicationManager.getApplication().invokeLater(() -> {
                    writeConsoleOutput(shared != null ? shared : preparedConsole, EXEC_FAILURE_ECHO_PREFIX + " " + fErr + "\n", true);
                    // T-ELU-01/03：语句级错误同 startResultTabRetry 的早检路径——走富通道收口
                    deliverError(onErrorRich, onError, null, fErr, sqlText != null ? sqlText.length() : null);
                }, ModalityState.nonModal());
                return;
            }
            if (shared != null) clearConsolePrompt(shared, LOG);
            Set<String> names = resultContents(ui).stream().map(Content::getDisplayName).collect(Collectors.toSet());
            boolean namesChanged = lastResultNames[0] != null && !names.equals(lastResultNames[0]);
            lastResultNames[0] = names;
            JComponent live = null;
            try {
                Object c = ui.getClass().getMethod("getComponent").invoke(ui);
                live = c instanceof JComponent ? (JComponent) c : null;
            } catch (Throwable ignored) {
                // ignore
            }
            // ★A48：第四判据（re-parent 回收）参与换入决策——详见 resultSwapNeeded KDoc。
            JComponent host = sharedFullComponent;
            boolean instanceChanged = live != lastLiveResultSwapped;
            boolean shouldSwap = resultSwapNeeded(
                    host,
                    host instanceof JSplitPane ? ((JSplitPane) host).getBottomComponent() : null,
                    live,
                    lastLiveResultSwapped,
                    namesChanged,
                    forceSwapIn,
                    swapped[0]);
            // 仅第四判据为真 = 身份与名称都没变、组件被 DBN 挂回原生宿主 → 本拍是「回收重挂」
            boolean reclaimed = shouldSwap && !(forceSwapIn && !swapped[0]) && !instanceChanged && !namesChanged;
            // 无宿主（swapEnabled=false）时不做换入——错误晚检已在上方独立完成
            if (swapEnabled && top != null && shouldSwap && live != null) {
                JComponent full = swapResultIntoPersistentConsole(sharedFullComponent, live, top, LOG);
                boolean changed = live != lastLiveResultSwapped;
                swapped[0] = true;
                lastLiveResultSwapped = live;
                sharedFullComponent = full;
                preparedFullComponent = full;
                Consumer<JComponent> cb = pendingOnConsole;
                if (cb != null) cb.accept(full);
                LOG.info("[M36] results refreshed (tick=" + ticks[0] + ", forced=" + forceSwapIn + ", instanceChanged=" + changed + ", namesChanged=" + namesChanged + ", reclaimed=" + reclaimed + ", gen=" + generation + ")");
            }
            if (ticks[0] >= 8) {
                timer.stop();
                LOG.info("[M36] results refresh watch finished (ticks=" + ticks[0] + ")");
            }
        });
        timer.start();
    }


    /** M36.5.75：Output 控制台布局替换修复。 */
    public static void installOutputHistoryFillLayout(Component root, Logger log) {
        try {
            JSplitPane sp = root instanceof JSplitPane ? (JSplitPane) root : null;
            Component searchRoot = sp != null ? sp.getBottomComponent() : root;
            if (!(searchRoot instanceof Container)) return;
            Container sr = (Container) searchRoot;
            List<Component> targets = new ArrayList<>();
            findAllOutputs(sr, 0, targets);
            if (targets.isEmpty()) {
                log.debug("[M36][OUTFILL] no output editor (EditorImpl$PanelWithFloatingToolbar) in result area; skip");
                return;
            }
            boolean installedAny = false;
            for (Component t : targets) {
                if (t.getParent() != null && t.getParent().getLayout() instanceof FillTargetLayout) continue;
                List<Container> chain = new ArrayList<>();
                boolean anchored = false;
                Container cur = t.getParent();
                while (cur != null) {
                    chain.add(cur);
                    if (cur.getLayout() != null && cur.getLayout().getClass().getName().contains("EditorMergedHorizontalScrollBarLayout")) {
                        anchored = true;
                        break;
                    }
                    if (cur == searchRoot) break;
                    cur = cur.getParent();
                }
                if (!anchored) {
                    long now = System.currentTimeMillis();
                    if (now - lastOutFillDiagAt > 2000) {
                        lastOutFillDiagAt = now;
                        StringBuilder chainDesc = new StringBuilder();
                        Container p = t.getParent();
                        int lvl = 0;
                        while (p != null && lvl < 10) {
                            chainDesc.append("\n  ↑").append(lvl).append(" ").append(p.getClass().getName())
                                    .append(" layout=").append(p.getLayout() != null ? p.getLayout().getClass().getName() : "null")
                                    .append(" bounds=").append(p.getBounds());
                            if (p == searchRoot) break;
                            p = p.getParent();
                            lvl++;
                        }
                        log.debug("[M36][OUTFILL] EMHSBL panel not found above output editor; ancestors=" + chainDesc);
                    }
                    continue;
                }
                Component child = t;
                for (Container c : chain) {
                    if (c instanceof JScrollPane) {
                        log.debug("[M36][OUTFILL] chain node is JScrollPane (" + c.getClass().getName() + "), keep its layout");
                    } else if (!(c.getLayout() instanceof FillTargetLayout)) {
                        String oldLayout = c.getLayout() != null ? c.getLayout().getClass().getName() : "null";
                        java.awt.Rectangle before = c.getBounds();
                        c.setLayout(new FillTargetLayout(child));
                        c.revalidate();
                        log.debug("[M36][OUTFILL] installed on " + c.getClass().getName() + " (was " + oldLayout + ") target=" + child.getClass().getSimpleName() + " before=" + before);
                        installedAny = true;
                    }
                    child = c;
                }
            }
            if (installedAny) {
                sr.revalidate();
                sr.repaint();
            }
        } catch (Throwable e) {
            log.warn("[M36][OUTFILL] install failed: " + e.getMessage());
        }
    }

    public static void findAllOutputs(Component c, int depth, List<Component> targets) {
        if (targets.size() >= 4 || depth > 24) return;
        if (c.getClass().getName().contains("EditorImpl$PanelWithFloatingToolbar")) {
            targets.add(c);
            return;
        }
        if (c instanceof Container) {
            for (Component d : ((Container) c).getComponents()) findAllOutputs(d, depth + 1, targets);
        }
    }


    /**
     * 创建临时 .sql 文件。
     *
     * <p>T-ExecCopyFix：失败可见化——原实现整段 runCatching{}.getOrNull() 吞异常零日志，
     * 用户真机 "Failed to create temporary SQL file" 无法归因。现分级记录每个 return null
     * 站点（stage 标识 + ioFile 路径/exists 状态），异常级 warn 带堆栈，并向调用方回传
     * 阶段原因（{@link #lastTempFileFailure}）供错误文案携带。</p>
     *
     * <p>T-ExecCopyFix F2b：冷路径 refresh 段换 WriteIntentReadAction.compute（Computable 单类型
     * 实参，243~262 交集——T-Wia262）——修 262 真机最可能根因：refreshAndFindFileByIoFile 同步
     * VFS refresh 要求 write-safe 语境，262 平台 RefreshSessionImpl.launch 对 EDT-ReadAction
     * 硬抛 "Access is allowed from write thread only"（T-PreviewWiLock 同 API 先例）。WIRA 隐含
     * 读权限，refresh 后续链不受影响。</p>
     *
     * <p>T-ExecSlowOps F2：<b>本方法自此退化为兜底路径</b>——预览链成功后
     * {@link #prewarmExecTempFile} 已在后台线程完成 写盘→refresh→方言映射→findFile→填缓存槽，
     * 弹窗打开后这里 {@link #consumeCachedTempPsiFile} 必命中，EDT 上不再执行 refresh/findFile
     *（262 真机 "Slow operations are prohibited on EDT" SEVERE 本体，WorkspaceFileIndex
     * .ensureIsUpToDate）。冷路径仅存于无预览链触达的显式执行首跑等场景。原"后台化经评估
     * 否决"（BGT 无法安全做 EDT-only 的 setMapping）已被 {@link DialectPreMapping#ensureMapped}
     * 两段式（BGT 读探测 + invokeLater 派 EDT + 有界 latch 等待，绝不 invokeAndWait）解决。</p>
     */
    public static TempPsiFile createTempPsiFile(Project project, String sql) {
        return createTempPsiFile(project, sql, null);
    }

    public static TempPsiFile createTempPsiFile(Project project, String sql, @Nullable String renderedDialectName) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        // T-ExecCopyFix F2a：失败阶段记录（onError 文案携带，见 chooseAndPrepare 错误文案）
        lastTempFileFailure = null;
        final File ioFile;
        try {
            ioFile = projectExecFile(project, sql);
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            // projectExecFile 尾段 FileUtil.writeToFile 可抛 IOException——原实现靠外层
            // runCatching 吞掉，拆除后这里必须自接，否则异常直冲 EDT
            return failTempFile("writeFile", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (ioFile == null) {
            return failTempFile("writeFile", "projectExecFile null (basePath=" + project.getBasePath() + ")");
        }
        // T-PreviewFlash：EDT 同步 VFS refresh + PsiManager.findFile 已被跨点击缓存吸收——
        // mpsql-exec.sql 路径稳定，vf 仍有效时直接复用配对缓存，把
        // WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言移出每次预览的热路径。
        TempPsiFile cached = consumeCachedTempPsiFile(project);
        if (cached != null) return cached;
        // T-ExecDocSync2 F4：refresh 前强制收敛——写点之后、refresh 之前若被用户键入/其他写方
        // 插入（doc modified-unsaved + 磁盘≠文档），refresh 恰会命中 MemoryDiskConflictResolver
        // 弹窗条件；findFileByIoFile（与 projectExecFile 内裸调同形态）命中即过一遍分叉探测 +
        // EDT 派发 doc 写收敛，正常恒 no-op。详见 prewarmExecTempFileInner 同款注释。
        try {
            VirtualFile vf0 = LocalFileSystem.getInstance().findFileByIoFile(ioFile);
            if (vf0 != null) {
                syncExecVfContentIfNeeded(project, vf0, sql);
            }
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            return failTempFile("preRefreshConverge", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // T-ExecCopyFix F2b：refresh 段包 WriteIntentReadAction（详见方法 Javadoc）；
        // mapDialect 与 findFile 维持原线程原语义（setMapping 须 EDT；findFile 沿用
        // ReadAction.compute 既有包装，与 T-M36.6-03 前行为一致）。
        VirtualFile vf;
        try {
            vf = WriteIntentReadAction.compute(
                    (com.intellij.openapi.util.Computable<VirtualFile>) () ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            return failTempFile("refreshVfs", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (vf == null) {
            return failTempFile("refreshVfs",
                    "VFS not visible after refresh (" + ioFile.getPath() + ", exists=" + ioFile.exists() + ")");
        }
        // T-EC-04：VFS 缺条目裸写路径的防御收口——正常恒 no-op，异常残留在此收敛（含落盘）
        try {
            syncExecVfContentIfNeeded(project, vf, sql);
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            return failTempFile("syncVfContent", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        mapDialectBeforePsi(project, vf, LOG);
        PsiFile psi;
        try {
            psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            return failTempFile("findPsi", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (psi == null) {
            return failTempFile("findPsi", "PsiManager.findFile null (" + vf.getName() + ")");
        }
        // T-PerfSweep2-04：写点统一走 markCachedTempPsiFile（挂一次性项目关闭钩子）
        markCachedTempPsiFile(project, vf, psi);
        return new TempPsiFile(vf, psi);
    }

    /** T-ExecCopyFix F2a：失败阶段记录与日志（stage 标识 + 详情），恒返 null 供上层透传。 */
    public static TempPsiFile failTempFile(String stage, String detail) {
        lastTempFileFailure = (detail == null || detail.isEmpty()) ? stage : stage + ": " + detail;
        Logger.getInstance(DataSourceSelector.class)
                .warn("[M36] createTempPsiFile FAILED at " + stage
                        + ((detail == null || detail.isEmpty()) ? "" : " (" + detail + ")"));
        return null;
    }


    /**
     * M36.5.12：项目内执行文件路径——{@code <projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql}
     *（F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再使用）。
     * 目录不存在则创建；文件写入 SQL（覆盖）。与 sql-history 同根（.idea/sql-visualizer/），
     * 但<b>独立子目录</b> exec（sql-history 读取器只扫 sql-history 子目录，互不污染）。
     *
     * <p>M36.5.13/M36.5.43：VFS 已知文件走 document 驱动写（历史：裸 FileUtil.writeToFile 覆盖
     * 磁盘触发 "File Cache Conflict" 模态弹窗，MemoryDiskConflictResolver，沙盒 12:08:06/
     * 16:05:39 两次实证）。T-ExecDocSync 三点收口（同一问题族第三轮）：</p>
     * <ul>
     *  <li><b>① 同临界区落盘</b>（T-EC-01，{@link #writeVfTextViaDocument}）——补 saveDocument，磁盘即时
     *      与内存一致，不再留「已修改未保存」窗口（M36.5.43 只改内存、磁盘靠自动保存追赶，
     *      窗口期内任何外部改盘即弹冲突窗，easy-code-demo 真机截图实证）；</li>
     *  <li><b>② document 写统一派 EDT</b>（{@link #dispatchDocWriteOnEdt}，DialectPreMapping 两段式同款）——
     *      262 平台文件 document 的 setText 包 CommandProcessor BGT 硬抛 EDT-only（javap+沙盒
     *      堆栈双实证）：旧实现 BGT 预热的 document 分支每次必炸、静默掉进裸写兜底 = 真机
     *      File Cache Conflict 链本体。BGT 预热自此不再降级；</li>
     *  <li><b>③ 裸写兜底守卫</b>（T-EC-02，{@code canFallBackToRawWrite}）——document 写失败（含注入缝）
     *      时只有无已加载 document 才允许裸写盘；有 document 一律抛 writeConflictGuard 拒写
     *     （宁内容旧一拍，执行前 console doc 同步链会重写并落盘，执行内容不受影响），
     *      失败原因 Throwable 必达日志（原 getOrDefault(false) 吞掉）；
     *      → <b>T-ExecDocSync3 退役</b>（第四轮真机日志实证判据不可靠，见方法内注释）：改为
     *      「VFS 已有该文件即绝不裸写」，冷启动 EDT 忙场景彻底无冲突面；</li>
     *  <li><b>④ 单写方锁</b>（T-EC-03，{@link #execFileWriteLock}）——串行化 BGT 预热与 EDT 冷路径。</li>
     * </ul>
     * <p>VFS 缺条目路径维持裸写 + 调用方 refresh + {@link #syncExecVfContentIfNeeded} 防御收口（T-EC-04）。</p>
     * T-PreviewFast P1/P2：差量快路径（内容已一致即返回）+ 预热非阻塞派发（{@code nonBlocking=true}）。
     * public 供测试。
     *
     * @param nonBlocking true = 预热等投机性调用：BGT 派发不等 EDT（fire-and-forget，P2）
     */
    public static File projectExecFile(Project project, String sql) throws java.io.IOException {
        return projectExecFile(project, sql, false);
    }

    /** nonBlocking 显式重载（Kotlin 默认参数 {@code nonBlocking = false} 的显式形态）。 */
    public static File projectExecFile(Project project, String sql, boolean nonBlocking) throws java.io.IOException {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        String base = project.getBasePath();
        if (base == null) {
            // T-ExecCopyFix F2a：basePath null 是「无异常的 null」，原来连日志都没有
            LOG.warn("[M36] projectExecFile FAILED: project.basePath null (project=" + project.getName() + ")");
            return null;
        }
        File dir = new File(base, ".idea" + File.separator + SqlLogAutoSaver.DATA_DIR_NAME + File.separator + "exec");
        if (!dir.exists() && !dir.mkdirs()) {
            // T-ExecCopyFix F2a：mkdirs 返回值原来被忽略，目录建不起来后写盘抛异常再被吞
            LOG.warn("[M36] projectExecFile FAILED: mkdirs false (" + dir.getPath() + ")");
            return null;
        }
        File f = new File(dir, "mpsql-exec.sql");
        synchronized (execFileWriteLock) {
            VirtualFile existing = LocalFileSystem.getInstance().findFileByIoFile(f);
            if (existing != null) {
                // T-PreviewFast P1：**差量快路径**——派发前先判当前内容是否已等于目标 SQL。相同则
                // 直接返回，不派发、不落盘、不等 EDT（连续预览同一 SQL 是常见形态：真机日志 14:17
                // 五连预览每次都白付 2s 派发等待 + 一次无效落盘 + 一轮索引噪音）。
                // 判据双源：document 文本（有已加载文档时，UI 所见为准）或 vf 内容（无文档时）。
                if (execFileContentMatches(project, existing, sql)) {
                    LOG.info("[M36] exec file content already matches — write SKIPPED (T-PreviewFast P1): " + f.getName());
                    return f;
                }
                // T-EC-01/T-EC-03：document 写统一派 EDT（EDT 直调同步；BGT 派发 + 有界等待）。
                // 262 实证 document setText BGT 硬抛（见 writeVfTextViaDocument Javadoc）——旧实现
                // 的 BGT document 分支每次必炸、静默掉进裸写兜底，即真机冲突链本体。
                // T-PreviewFast P2：预热调用（unblocking=true）走 fire-and-forget，不再等满 2 秒。
                boolean updated = dispatchDocWriteOnEdt(
                        project, existing, sql,
                        nonBlocking ? 0L : EDT_EXEC_DOC_SYNC_WAIT_MS);
                if (updated) {
                    LOG.info("[M36] exec file updated via document+save (disk synced in-critical-section): " + f.getName());
                    return f;
                }
                // T-ExecDocSync3（第四轮真机复现的收口，20260917 真实 IDE 日志实证）：**VFS 已有
                // 该文件时一律不裸写盘**。原 T-EC-02 兜底守卫查 getCachedDocument==null 就放行裸写，
                // 实机证伪——EDT 忙（冷启动/索引，日志同刻可见 DumbService enter dumb mode）时
                // dispatch 有界等待 2s 超时返回 false，此刻「排队的 EDT 写任务」尚未执行、document
                // 也还没建，getCachedDocument 恒 null；随之而来的裸写先改盘（12,590），排队任务
                // 随后 ExecutionException 建出 document（resolver 日志 oldFileStamp:0 = 文档建于文件
                // 写入之前）并标脏，裸写引发的 VFS 变更落到这个刚建的文档上 →
                // MemoryDiskConflictResolver「重载 mpsql-exec.sql？」弹窗（12,609），23ms 后文档写入
                // 才自己落盘（12,613）。即**插件与自己的排队写赛跑**，而非外部改盘。
                // 宁内容旧一拍：排队任务/later 的 console doc 同步链（attachConsole 后的
                // prepareAfterEditorResolved、executeOnConsole 执行前 T-EC-01 同款同步）都会重写并
                // 落盘，执行内容不受影响。附带收益：冷启动 EDT 忙时不再有裸写 → 该场景彻底无冲突面。
                LOG.warn("[M36] exec doc-write not completed (EDT dispatch timeout/WriteAction failure); "
                        + "raw write SKIPPED to avoid File Cache Conflict (T-ExecDocSync3): " + f.getName());
                return f;
            }
            // VFS 无该条目（首次创建）：不可能存在 document（无 vf 可挂），裸写安全
            com.intellij.openapi.util.io.FileUtil.writeToFile(f, sql);
            return f;
        }
    }


    /** {@link #prewarmExecTempFileInner} 的 Kotlin 默认参等价重载（renderedDialectName 缺省 null）。 */
    public static void prewarmExecTempFileInner(Project project, String sql, Logger LOG) {
        prewarmExecTempFileInner(project, sql, LOG, null);
    }

    /** {@link #prewarmExecTempFileInner(project, sql, LOG, renderedDialectName)} 的真实链（已持有 in-flight 标记；仅后台线程调用）。 */
    public static void prewarmExecTempFileInner(Project project, String sql, Logger LOG,
                                                @Nullable String renderedDialectName) {
        lastTempFileFailure = null;
        // BGT 不得持写意向锁同步 refresh：刷新会话推进也需要 WIL/EDT，会互等自锁。
        // EDT 冷路径 createTempPsiFile 的 WIRA 语境不同，必须保留；这里只移除后台预热包装。
        File ioFile;
        try {
            ioFile = projectExecFile(project, sql);
        } catch (Throwable e) {
            // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：fatal 上浮（预热跑在 BGT，透传由平台
            // 记录现场）；非 fatal 仍返回（writeFile 失败 → 跳过预热，语义逐字不变）。
            if (FatalErrors.isFatal(e)) throw (Error) e;
            LOG.warn("[M36] exec prewarm writeFile failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (ioFile == null) return;
        // T-ExecDocSync2 F4：refresh 前强制收敛——projectExecFile 的 doc 写在 EDT 落盘后、本
        // refresh 之前，用户在控制台（document 即 exec 文件 document）键入可插入 modified-unsaved
        // 态；此时 refresh 命中「doc 已修改 && 磁盘≠文档」即弹 File Cache Conflict（第四轮残余竞态，
        // 开发计划 §1.3）。findFileByIoFile（与 projectExecFile 内裸调同形态，prewarm DONE 日志
        // 实证 BGT 可调）命中即过一遍分叉探测 + EDT 派发收敛，正常恒 no-op。
        try {
            VirtualFile vf0 = LocalFileSystem.getInstance().findFileByIoFile(ioFile);
            if (vf0 != null) {
                syncExecVfContentIfNeeded(project, vf0, sql);
            }
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm pre-refresh converge FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        VirtualFile vf;
        try {
            vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传（prewarm 跑在 BGT，透传由平台记录现场）
            LOG.warn("[M36] exec prewarm refreshVfs failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (vf == null) {
            LOG.warn("[M36] exec prewarm: VFS not visible after refresh (" + ioFile.getPath() + ", exists=" + ioFile.exists() + ")");
            return;
        }
        // T-EC-04：同冷路径——裸写残留的防御收口（正常恒 no-op）
        try {
            syncExecVfContentIfNeeded(project, vf, sql);
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm syncVfContent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        // T-ExecSlowOps F1：BGT 两段式映射（读探测稳态跳过 / 首见派 EDT + 有界等待）。
        // O-145 W7：预览提供了渲染方言时 exec PSI 同步该事实；旧调用仍走 O-136 数据源路由
        //（mapDialectBeforePsi 内部维护 dialectMappedVf/dialectMappedLanguageId 幂等短路）。
        DataSourceSelector.mapDialectBeforePsi(project, vf, LOG, renderedDialectName);
        PsiFile psi;
        try {
            psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm findPsi failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (psi == null) {
            LOG.warn("[M36] exec prewarm: findFile null (" + vf.getName() + ")");
            return;
        }
        if (project.isDisposed()) return;
        markCachedTempPsiFile(project, vf, psi);
        LOG.info("[M36] exec prewarm DONE (cache filled off EDT): " + vf.getName());
    }


    /** M36.5.2：主题化完整控制台 SplitPane。 */
    public static JComponent buildConsoleSplitPane(JComponent topBlock, JComponent resultComponent) {
        Dimension min = topBlock.getMinimumSize();
        topBlock.setMinimumSize(new Dimension(min != null ? min.width : 400, 260));
        Dimension rmin = resultComponent.getMinimumSize();
        resultComponent.setMinimumSize(new Dimension(rmin != null ? rmin.width : 400, 160));
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topBlock, resultComponent) {
            @Override
            public void paint(Graphics g) {
                super.paint(g);
                g.setColor(UIUtil.getPanelBackground());
                g.fillRect(0, getDividerLocation(), getWidth(), getDividerSize());
            }
        };
        sp.setResizeWeight(0.6);
        sp.setContinuousLayout(true);
        sp.setOneTouchExpandable(false);
        sp.setDividerSize(3);
        sp.setBorder(JBUI.Borders.empty());
        sp.setBackground(UIUtil.getPanelBackground());
        sp.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (sp.getHeight() < 200) return;
                sp.removeComponentListener(this);
                sp.setDividerLocation(0.6);
                sp.revalidate();
                sp.repaint();
            }
        });
        return sp;
    }


    /**
     * T-EC-01/T-EC-03 编排：document 写统一派发 EDT——EDT 调用方同步直执行；BGT 两段式
     * invokeLater + 有界 latch 等待（DialectPreMapping.ensureMapped 同款：BGT 等 EDT，
     * EDT 永不反向阻塞，超时自愈不互锁）。失败注入缝 {@link #execWriteActionFailureInjection}
     * 在 {@link #docWriteOnce} 内统一生效（EDT/BGT 两形态等价）。
     *
     * <p>T-PreviewFast P2：{@code waitBudgetMs <= 0} 时走 <b>fire-and-forget</b>（派发后立即返回 true）——
     * 预热是投机性写入，T-ExecDocSync3 取消裸写后超时已无副作用，没必要让 BGT 白等 2 秒。</p>
     *
     * <p>T-FixWriteSafe（T-WriteSafeCtx 留档补口）：两处投递原用 1 参 {@code invokeLater}，即隐式
     * {@code ModalityState.defaultModalityState()}。平台源码实证（{@code openapi/application/modality.kt}：
     * {@code defaultModalityImpl()} 依次取 {@code ProgressManager.currentProgressModality} → 线程/协程上下文
     * modality → NON_MODAL）：BGT 常态取到 NON_MODAL（{@code TransactionGuardImpl:37} 构造期即登记为
     * write-safe，与显式 {@code nonModal()} 等价），但<b>在模态进度/带 modality 上下文里会取到该模态</b>；
     * 若该模态本身是「写不安全进入」的（{@code enteredModality:188-190} 存的是进入那一刻
     * {@code myWritingAllowed} 的快照），排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行 →
     * 改文档即 {@code Write-unsafe context!}。故两处投递的<b>写体</b>统一过 {@link DocWriteContext} 闸门：</p>
     * <ul>
     *  <li>fire-and-forget：整拍改以 nonModal 投递（{@code invokeLaterWriteSafe}）——本路径无有界等待，
     *      模态存续期延后一拍正是「模态期间禁改文档」的正确语义，不会撞任何预算；</li>
     *  <li>有界等待：<b>保留默认模态投递</b>（保证能在该模态事件泵内执行，常态与改前逐字一致），
     *      只把写体过闸（{@code runWriteSafe}）：常态判为可写 → 就地写 + 同拍 countDown；仅当判为不安全
     *      才转 nonModal 投递并<b>立即</b> countDown（ok=false）——不让调用方白等 2 秒预算。延后那一拍
     *      重跑 {@link #docWriteOnce} 是幂等的（内容相同不 setText、已落盘不 saveDocument），且该超时/未完成
     *      路径已由 T-ExecDocSync3 定为无害（不裸写、内容旧一拍由后续同步链收敛）。</li>
     * </ul>
     */
    public static boolean dispatchDocWriteOnEdt(Project project, VirtualFile vf, String sql) {
        return dispatchDocWriteOnEdt(project, vf, sql, EDT_EXEC_DOC_SYNC_WAIT_MS);
    }

    /** waitBudgetMs 显式重载（Kotlin 默认参数 {@code waitBudgetMs = EDT_EXEC_DOC_SYNC_WAIT_MS} 的显式形态）。 */
    public static boolean dispatchDocWriteOnEdt(Project project, VirtualFile vf, String sql, long waitBudgetMs) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            final boolean[] ok = {false};
            DocWriteContext.runWriteSafe(project, "exec doc sync (edt direct)", () -> ok[0] = docWriteOnce(project, vf, sql));
            return ok[0];
        }
        if (waitBudgetMs <= 0L) {
            // project 即 owner disposable：项目关闭后丢弃该拍（写目标就是项目内临时文件，重跑无意义）
            DocWriteContext.invokeLaterWriteSafe(project, "exec prewarm doc write (fire-and-forget)", () -> {
                try {
                    docWriteOnce(project, vf, sql);
                } catch (Throwable ignored) {
                    // runCatching 语义
                }
            });
            LOG.info("[M36] exec doc write dispatched fire-and-forget (T-PreviewFast P2)");
            return true;
        }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        app.invokeLater(() -> {
            try {
                DocWriteContext.runWriteSafe(project, "exec doc sync (bounded)", () ->
                        ok[0] = docWriteOnce(project, vf, sql));
            } finally {
                done.countDown();
            }
        });
        boolean signaled;
        try {
            signaled = done.await(waitBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException t) {
            signaled = false;
            Thread.currentThread().interrupt();
        }
        if (!signaled) {
            LOG.warn("[M36] exec doc write EDT dispatch timeout after " + waitBudgetMs + "ms (EDT busy?)");
        }
        return signaled && ok[0];
    }


    /** M36.5.41-A4：持久整控台——结果刷新时原位换底。 */
    public static JComponent swapResultIntoPersistentConsole(
            JComponent full,
            JComponent liveResult,
            JComponent topBlock,
            Logger LOG) {
        JSplitPane sp = full instanceof JSplitPane ? (JSplitPane) full : null;
        if (sp == null) return buildConsoleSplitPane(topBlock, liveResult);
        // ★A49 取证修正：换入**前**记录结果组件的真实宿主。旧日志在 `sp.bottomComponent = liveResult`
        // 之后才读 parent，恒打印本 SplitPane，证明不了「有没有从 DBN 宿主摘走」。改在改动前取，
        // 并用**全类名**（判别力所在）：`…ServiceViewComponentWrapper` = 仍在 DBN 原生宿主；
        // `…DataSourceSelectorResultsKt$buildConsoleSplitPane$1`（Java 匿名类形态 `…$1`）= 已在我方容器。
        Component prevParent = liveResult.getParent();
        Component current = sp.getBottomComponent();
        if (current == liveResult && liveResult.getParent() == sp) {
            Dimension rmin = liveResult.getMinimumSize();
            liveResult.setMinimumSize(new Dimension(rmin != null ? rmin.width : 400, 160));
            realignSplitDivider(sp, true);
            sp.revalidate();
            sp.repaint();
            return sp;
        }
        if (current != null) sp.remove(current);
        // ★A48：显式从**外部宿主**摘出——DBN 在新建结果页签时会把结果组件重新挂回
        // ServiceViewComponentWrapper（同一实例、仅 parent 变），不摘会让组件继续留在原生宿主
        // （预览窗内空白、结果只出现在 IDE 底部原生控制台）。parent 为 sp/无父时本行 no-op。
        java.awt.Container extHost = liveResult.getParent();
        if (extHost != null && extHost != sp) extHost.remove(liveResult);
        sp.setBottomComponent(liveResult);
        Dimension lrmin = liveResult.getMinimumSize();
        liveResult.setMinimumSize(new Dimension(lrmin != null ? lrmin.width : 400, 160));
        try {
            LOG.info("[M36] liveResult class=" + liveResult.getClass().getName() + ", hasSplit=" + (liveResult instanceof JSplitPane));
            if (liveResult instanceof JSplitPane) {
                JSplitPane lsp = (JSplitPane) liveResult;
                LOG.info("[M36] liveResult divider=" + lsp.getDividerLocation() + " orientation=" + lsp.getOrientation()
                        + " top=" + (lsp.getTopComponent() != null ? lsp.getTopComponent().getClass().getSimpleName() : "null")
                        + " bottom=" + (lsp.getBottomComponent() != null ? lsp.getBottomComponent().getClass().getSimpleName() : "null"));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        realignSplitDivider(sp, true);
        sp.revalidate();
        sp.repaint();
        LOG.info("[M36] persistent console bottom swapped (result id=" + System.identityHashCode(liveResult)
                + ", prevParent=" + (prevParent != null ? prevParent.getClass().getName() : "null")
                + ", fromExternalHost=" + (prevParent != null && prevParent != sp) + ")");
        return sp;
    }


    /**
     * F6：冲突探针——「文件缓冲区冲突」弹窗（MemoryDiskConflictResolver）响应的是平台对修改中
     * document 发起的 reload，reload 前平台经 FileDocumentManagerListener.beforeFileContentReload
     * 广播（与弹窗实现同 topic，监听先后不保证，故仅取证不拦截）。exec 文件命中即落 warn 指纹
     * （doc/vf 长度与 stamp、unsaved 态）：F1-F5 全上后若真机仍复发，idea.log 直出现场数据，
     * 替代人工复现排查。经项目 messageBus 连接（随项目 disposable 自动断开）。
     */
    public static void installExecConflictProbe(Project project) {
        if (!conflictProbeProjects.add(project)) return;
        project.getMessageBus().connect(project).subscribe(
                com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC,
                new com.intellij.openapi.fileEditor.FileDocumentManagerListener() {
                    @Override
                    public void beforeFileContentReload(@NotNull VirtualFile vf, @NotNull Document document) {
                        if (!isExecVfPath(vf.getPath())) return;
                        FileDocumentManager fdm = FileDocumentManager.getInstance();
                        Boolean unsaved = null;
                        try {
                            unsaved = fdm.isDocumentUnsaved(document);
                        } catch (Throwable ignored) {
                            // getOrNull 语义
                        }
                        Logger.getInstance(DataSourceSelector.class)
                                .warn("[T-ExecDocSync2][conflict-probe] reload-with-unsaved-doc: " + vf.getPath()
                                        + ", docLen=" + document.getTextLength() + ", docStamp=" + document.getModificationStamp()
                                        + ", vfLen=" + vf.getLength() + ", vfStamp=" + vf.getModificationStamp()
                                        + ", unsaved=" + unsaved);
                    }
                });
    }


    public static void realignDividerOnce(JSplitPane sp, boolean force) {
        if (sp.getHeight() < 100) return;
        int topH = sp.getTopComponent() != null ? sp.getTopComponent().getHeight() : 0;
        int total = sp.getHeight();
        Logger log = Logger.getInstance(DataSourceSelector.class);
        boolean tooAuto = force || topH < total * 0.35 || topH > total * 0.68;
        if (tooAuto) {
            log.debug("[M36] divider realign(force=" + force + "): topH=" + topH + " total=" + total + " → 0.6");
            sp.setDividerLocation(0.6);
            sp.revalidate();
            sp.repaint();
            dumpSplitBounds(sp, log);
        } else {
            log.debug("[M36] divider OK: topH=" + topH + " total=" + total + " (no change)");
        }
        ApplicationManager.getApplication().invokeLater(() -> installOutputHistoryFillLayout(sp, log), ModalityState.any());
    }


    /**
     * M36.5.48：从 console 历史文档提取真实错误文本——仅当<b>超时无结果</b>（真失败/真超时）时
     * 调用。DBN 查询执行失败（SQL 语法错/表不存在等）会把错误消息写入 console history。
     *
     * <p>提取规则（防误判）：只认<b>明确的 SQL 错误判据</b>（{@link #isErrorSignatureLine}），且错误行不得是
     * 纯 SQL 语句（含 SELECT/FROM/WHERE/JOIN 等 SQL 词），避免把正常 SQL 历史误判为错误
     * （日志实证 {@code extracted console error: WHERE t.id = 1}——A 并无真失败，正常 SQL 片段被误报）。</p>
     *
     * <p>M36.5.57：只扫 <b>consoleErrorBaseline 之后</b>的文本——历史残留错误（上个失败 SQL 写入的
     * Unknown column 等）不在本次范围，正常 SQL 执行后不会"带过来"旧错误。</p>
     *
     * <p>T-CEC-02（用户真机 P15）：判据由「短语词表」升级为「结构性判据 + 短语族」——原词表对
     * {@code [42000][1064] You have an error in your SQL syntax}（MySQL 语法错）一条都不含，面板因此
     * 全程无红字、无复制按钮，用户只能在 Output 页自己找。同批漏检的还有 Oracle {@code ORA-00933}、
     * MySQL 1062/1451 等；历史真机验证过的错误恰好全是 {@code Unknown column} 系，故缺口长期未现。</p>
     */
    @Nullable
    public static String extractConsoleError(Object console, Logger LOG) {
        return extractConsoleError(console, LOG, null, null);
    }

    /** Kotlin 默认参等价重载：project 缺省 null（不判跨源升格）。 */
    @Nullable
    public static String extractConsoleError(Object console, Logger LOG, @Nullable String sqlText) {
        return extractConsoleError(console, LOG, sqlText, null);
    }

    /**
     * @param sqlText T-ExplainConsoleFirst W7：被执行 SQL——用于 {@code Unknown column} 的跨源升格判定（null = 不判定）。
     * @param project T-ExplainConsoleFirst W7：升格判定要枚举在籍数据源（null = 不判定）。
     */
    @Nullable
    public static String extractConsoleError(Object console, Logger LOG, @Nullable String sqlText,
                                             @Nullable Project project) {
        if (console == null) return null;
        try {
            String text = newConsoleText(console);
            if (text == null) return null;
            if (text.isBlank()) return null;
            List<String> lines = Arrays.stream(text.split("\n", -1))
                    .filter(s -> !s.isBlank()).collect(Collectors.toList());
            // 从末尾往前找"命中错误判据"且"非纯 SQL / 非本插件自写回显"的行
            String errorLine = null;
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (isErrorSignatureLine(line) && !isSqlLine(line) && !isOwnEchoLine(line)) {
                    errorLine = line;
                    break;
                }
            }
            if (errorLine == null) {
                // T-CEC-01：未命中即取证——记下本次新增文本的尾部（有界 + 每次执行只记一次），
                // 下次扩判据时以真机报文为准，不照用户截图猜形态。
                logUnrecognizedConsoleTail(lines, LOG);
                return null;
            }
            // 取错误行及其后非空行构成可读上下文——错误消息常跨多行（MySQL 语法错把 "near …" 的
            // SQL 片段跟在其后）。T-CEC-02：4 行放宽到 8 行，并剥掉逐行的 `[时间戳] ` 与提示符。
            String prompt = consolePrompt(console);
            int idx = lines.indexOf(errorLine);
            int end = Math.min(idx + EXCERPT_MAX_LINES, lines.size());
            StringBuilder excerptBuilder = new StringBuilder();
            for (int i = idx; i < end; i++) {
                if (i > idx) excerptBuilder.append('\n');
                excerptBuilder.append(stripConsolePrefix(lines.get(i), prompt));
            }
            String excerpt = excerptBuilder.toString();
            // T-ConsoleDbContext A：附「当前库 / 数据源 / 数据源默认库」上下文——真实事故中 SQL 本身
            // 没问题，是执行落在了错的库（mpj_demo 的 user 表没有 username → 1054），而裸 MySQL 报文
            // 看不出这一层；带上上下文后一眼可归因
            String ctxLine = ExecConsoleContext.describeContext(console);
            // T-ExplainConsoleFirst W7（2026-10-24 用户实测 `Unknown column 'first_name' in 'field list'`
            // ＋上下文三处全是 mp_demo）：源没选错、错的是「SQL 与库」这一对——`first_name` 属 jpa_demo
            // 的同名 user 表，而 mp_demo 的 user 没有该列。既有分类把 `Unknown column` 一律排除在
            // WRONG_TARGET 外（「列级问题换源解决不了」），对拼错列名成立，对「同名表不同列集」漏判。
            // 这里按证据升格：当前源确证无此列 ∧ 另一在籍源的同名表带此列 → 明说「换个源能解决」。
            // 纯加法：判据不成立（缓存未热/任何源都没有/当前源其实有）一律不附加，报错原文不变。
            String crossSourceHint = null;
            try {
                if (sqlText != null && !sqlText.isBlank() && project != null) {
                    List<Object> dataSources = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
                            .allDataSourcesForEvidence(project);
                    Object current = null;
                    if (dataSources != null) {
                        // 反射取控制台数据源（`JdbcConsole.getDataSource()`，与 consoleDataSourceId 同口径）——
                        // 本文件在 Community 下也会加载，不得直接引用 DBN 类。
                        try {
                            current = console.getClass().getMethod("getDataSource").invoke(console);
                        } catch (Throwable ignored) {
                            current = null;
                        }
                    }
                    if (dataSources != null && current != null) {
                        int currentIdx = -1;
                        for (int i = 0; i < dataSources.size(); i++) {
                            if (dataSources.get(i) == current) {
                                currentIdx = i;
                                break;
                            }
                        }
                        if (currentIdx >= 0) {
                            List<java.util.function.Function<String, java.util.Set<String>>> sources =
                                    cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
                                            .columnNameSourcesCached(dataSources);
                            CrossSourceColumnEvidence.Evidence evidence = CrossSourceColumnEvidence.assess(
                                    /* errorText = */ excerpt,
                                    /* sql = */ sqlText,
                                    /* currentSourceColumns = */ currentIdx < sources.size() ? sources.get(currentIdx) : null,
                                    /* otherSourcesColumns = */ filterIndexesExcept(sources, currentIdx));
                            if (evidence != null) {
                                LOG.info("[W7] cross-source column evidence hit: table=" + evidence.table()
                                        + ", column=" + evidence.column());
                                crossSourceHint = MpSqlVisualizerBundle.message("execute.error.crossSourceColumn",
                                        evidence.column(), evidence.table());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                crossSourceHint = null;   // runCatching{...}.getOrNull() 语义：升格失败不附加
            }
            StringBuilder enrichedBuilder = new StringBuilder();
            if (excerpt != null) enrichedBuilder.append(excerpt);
            if (ctxLine != null) {
                if (enrichedBuilder.length() > 0) enrichedBuilder.append('\n');
                enrichedBuilder.append(ctxLine);
            }
            if (crossSourceHint != null) {
                if (enrichedBuilder.length() > 0) enrichedBuilder.append('\n');
                enrichedBuilder.append(crossSourceHint);
            }
            String enriched = enrichedBuilder.toString();
            LOG.info("[M36] extracted console error: " + (enriched.length() > 200 ? enriched.substring(0, 200) : enriched));
            return enriched;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** T-ExplainConsoleFirst W7：filterIndexed { i, _ -> i != currentIdx } 的 Java 等价（升格的「其余在籍源」清单）。 */
    private static List<java.util.function.Function<String, java.util.Set<String>>> filterIndexesExcept(
            List<java.util.function.Function<String, java.util.Set<String>>> sources, int exceptIndex) {
        List<java.util.function.Function<String, java.util.Set<String>>> out = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (i != exceptIndex) out.add(sources.get(i));
        }
        return out;
    }


    /** Kotlin 默认参等价重载：moduleKey 缺省 null（既有项目级行为）。 */
    public static void runRunner(Project project, Object runner, boolean silent, Consumer<String> onError) {
        runRunner(project, runner, silent, onError, null);
    }

    /** 反射调用 Runner.run：建 session → consumer 回调续接执行。失败（常见：驱动未下载）→ 面板行内回调 + 按 silent 决定是否模态引导。
     * T-PreviewExecDs-02 F4：显式传 project——失败路径曾用 openProjects.firstOrNull() 清 last-used
     * /弹错误框，多项目同开时清的是「第一个打开项目」的记忆（错项目 bug，见开发计划 §2.2）。
     * @param moduleKey T-ModuleScopedLastUsed：失败清理只清该模块那一层（不传＝既有项目级行为）。 */
    public static void runRunner(Project project, Object runner, boolean silent, Consumer<String> onError,
                                 @Nullable String moduleKey) {
        // 空操作 Runner（平台组项的 EmptyRunnable）不能执行：run() 立即返回且永不回调 session，
        // 只会把准备门拖到 60s 超时。立即结算错误，让面板红字给出可行动原因。
        if (ExecDialectRouting.isNoOpRunner(runner)) {
            String name = getRunnerName(runner);
            Logger.getInstance(DataSourceSelector.class).warn("[M36] runner '" + name
                    + "' has EmptyRunnable; refusing to run (would wait for prepare timeout)");
            if (onError != null) {
                onError.accept(MpSqlVisualizerBundle.message("execute.datasource.noRunnable", name));
            }
            return;
        }
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Method method = runner.getClass().getMethod("run");
            method.setAccessible(true);
            method.invoke(runner);
            LOG.info("[M36] runRunner invoked OK");
        } catch (Throwable e) {
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传给调用方捕获边界；非 fatal 路径行为不变（仍走 onError 面板回调降级）。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            LOG.warn("[M36] runRunner FAILED: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            // T-DsCover：预览默认准备路径静默失败（被动展示不应弹模态打断）；
            // 并清 last-used 记忆，避免驱动损坏的数据源每次预览都白失败一次。
            // T-Perf-01：失败同时回调 onError（面板行内红字 + 「执行中」态恢复），静默与非静默路径都回调。
            String failMsg = MpSqlVisualizerBundle.message("execute.datasource.failed",
                    cause.getMessage() != null ? cause.getMessage() : cause.toString());
            if (onError != null) onError.accept(failMsg);
            if (silent) {
                // T-SI-02：两个键一起清（只清旧键会留下指认另一个数据源的 UUID 身份）
                // T-ModuleScopedLastUsed：带上模块键，只清该模块层（项目级兜底不动）
                forgetLastRunner(project, moduleKey);
                return;
            }
            // 驱动缺失/连接失败等：提示用户到数据源设置解决（原生 Download Driver 弹窗由 Database 插件触发）
            // T-UiNative T-UN-02：改 ERROR 轻通知（原模态错误框——失败仍醒目，但不打断）；
            // 测试模式跳过 + runCatching 兜底（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    new com.intellij.notification.Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("execute.title"),
                            failMsg,
                            com.intellij.notification.NotificationType.ERROR
                    ).notify(project);
                } catch (Throwable ignored) {
                    // headless 无通知系统依赖，与原 runCatching 等价
                }
            }
        }
    }


    /**
     * T-ExecSilentNoop F1a 探针：提交前的一次性可执行性预检 + 现场埋点。
     *
     * DBN 侧存在两条<b>全静默出口</b>（javap 实证，database-plugin.jar）：{@code chooseStatements}
     * 空选集且 {@code execOutside==1} 时既不消费 model 也不提示、直接 return；{@code beforeExecuteQueries}
     * 遇 {@code model.statements().isEmpty()} 或 {@code console.isValid()==false} 返回 false → 整段跳过。
     * 两者都无输出、无结果 Tab、无异常。此探针把这两个出口对应的现场量提前照出来，
     * 归因"查询没提交"具体撞的是哪一条，并据此决定是否走 {@code executeQueries(freshModel)} 兜底。
     * 纯反射 + try/catch，任何一步失败降级为 null，绝不阻断执行。
     *
     * @return null 表示"DBN 侧可执行（或探不到），按 native 链走"；
     *         非 null 表示"预检判定 DBN 侧会静默拒绝"，返回真实原因描述。
     */
    public static String execSilentProbe(Object console, Object freshModel, boolean demoted, String destDsName, Logger LOG) {
        Object isValid = null;
        try {
            isValid = console.getClass().getMethod("isValid").invoke(console);
        } catch (Throwable ignored) {
            // 探不到按 null 呈现
        }
        String stmts = "null";
        try {
            Object result = freshModel != null ? freshModel.getClass().getMethod("statements").invoke(freshModel) : null;
            if (result instanceof Iterable) {
                int n = 0;
                for (Object ignored : (Iterable<?>) result) n++;
                stmts = String.valueOf(n);
            }
        } catch (Throwable ignored) {
            // 探不到按 null 呈现
        }
        LOG.info("[M36][EXEC] probe start (console isValid=" + isValid + ", freshModel stmts=" + stmts + ", ds=" + destDsName + ")");
        if (demoted) {
            String reason = "freshModel demoted (vf PSI resolved 0 statements, fell back to console model)";
            LOG.info("[M36][EXEC] probe demoted -> " + reason);
            return reason;
        }
        return null;
    }


    /**
     * T-ErrorLocate T-ELU-01/03：错误投递<b>统一收口</b>——富通道优先，富通道缺席才回退旧
     * {@code onError(String)}；两者<b>互斥</b>，不再先后各投一次。
     *
     * <p><b>为什么必须互斥</b>（T-ELU-01 实测的真相，与计划 §一.1 的「按钮可见但点了没反应」不同）：
     * 消费方 {@code SqlPreviewPanel} 的 {@code onError} 恒为 {@code resultPanel.showError(errorMsg)}，而
     * {@code showError} 会清掉定位动作与定位按钮（{@code ui/SqlResultPanel.kt} 的
     * {@code locateHandler = null} + {@code errorLocateButton.isVisible = false}）。原实现先投富通道
     * （{@code showErrorRich} 置按钮可见）、紧接着无条件投旧通道——<b>同一 EDT 任务内</b>后者立刻把
     * 前者抹掉，端态恒为「按钮不可见」，T-ELU-01 的「先解析后显隐」因此形同 no-op。
     * 两者呈现的文本同源（富通道 {@code showErrorRich} 内部就是 {@code showError(info.message)}），
     * 故富通道成功时不再投旧通道，展示行为等价而定位态得以保留。</p>
     *
     * <p>富通道自身抛异常（{@code runCatching}）时<b>回退旧通道</b>——不让「结构化」这一增强把错误呈现吞掉。</p>
     *
     * <p><b>2026-09-23 真机走查补记（本函数曾丢失过，勿再删）</b>：本函数在分支上实现于 {@code 4186231}，
     * 但 {@code 914724c}（「O-48/O-49 采信 master 侧已合入实现」）把 O-49 整体改回 master 侧形态，
     * 该收口随之消失，端态退回「按钮永不出现」——真机走查实证：一条锚点可解析的失败语句
     * （{@code DELETE FROM no_such_table_xyz}）面板只出现红字 +「复制错误信息」，<b>没有定位按钮</b>。</p>
     *
     * @param cause        解包后的异常（取 SQLState/errorCode；控制台历史文本错误传 null）
     * @param message      错误文本（位置 marker 从它提取）
     * @param sqlMaxLength 提交/被执行文本长度（T-ELU-03 越界钳制上界）；null = 不钳制
     */
    public static void deliverError(
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            @Nullable Consumer<String> onError,
            @Nullable Throwable cause,
            @NotNull String message,
            @Nullable Integer sqlMaxLength) {
        if (onErrorRich != null) {
            try {
                onErrorRich.accept(ErrorPositionExtractor.buildInfo(cause, message, sqlMaxLength));
            } catch (Throwable t) {
                Logger.getInstance(DataSourceSelector.class)
                        .warn("[T-ErrorLocate] onErrorRich invoke failed → fallback to onError", t);
                if (onError != null) onError.accept(message);
            }
        } else {
            if (onError != null) onError.accept(message);
        }
    }

    /** Kotlin 默认参 {@code sqlMaxLength = null} 的缺省形态。 */
    public static void deliverError(
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            @Nullable Consumer<String> onError,
            @Nullable Throwable cause,
            @NotNull String message) {
        deliverError(onErrorRich, onError, cause, message, null);
    }


}
