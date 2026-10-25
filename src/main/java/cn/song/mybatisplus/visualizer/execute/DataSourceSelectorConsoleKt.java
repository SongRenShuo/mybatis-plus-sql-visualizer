// T-M53-II D2-4：master 把 DataSourceSelector.kt 的控制台准备/执行族成员搬为 execute 包顶层扩展函数
// （DataSourceSelectorConsole.kt）；本类是其 Java 承载——k2j 侧原为 DataSourceSelector.java 的成员，
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
 * {@code DataSourceSelectorConsole.kt} 顶层扩展函数（控制台准备链 / 执行链 / 数据上下文诊断族）的 Java 承载类。
 *
 * <p>本类方法经 {@code import static ...DataSourceSelector.*} 直引原 object 成员（master 侧为扩展函数接收者）。</p>
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class DataSourceSelectorConsoleKt {

    private DataSourceSelectorConsoleKt() {
    }

    /**
     * M36.5：控制台准备链后段（T-Perf-13 从 prepareInConsole 抽出——编辑器实例就绪后执行：
     * scriptModel 装配 / 执行上下文缓存 / 原生控制台 UI 拼合内嵌 / 可选立即执行）。
     * EDT 编辑器异步等待路径会经此继续（异常处理与同步路径一致：warn + onError 面板内显示）。
     */
    public static void prepareAfterEditorResolved(
            @NotNull Project project,
            @NotNull VirtualFile vf,
            boolean executeAfterPrepare,
            @Nullable String dataSourceName,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<String> onError,
            Object console,
            Document document,
            Editor editor,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            boolean ownsPrepareGate) {
        prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName, onConsole, onError, console,
                document, editor, onErrorRich, wholeScriptExec, null, null, null, null, () -> true);
    }

    public static void prepareAfterEditorResolved(
            @NotNull Project project,
            @NotNull VirtualFile vf,
            boolean executeAfterPrepare,
            @Nullable String dataSourceName,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<String> onError,
            Object console,
            com.intellij.openapi.editor.Document document,
            com.intellij.openapi.editor.Editor editor,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable QueuedExecuteIntent prepareOwner,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            @NotNull java.util.function.Supplier<Boolean> isPrepareActive) {
        if (!isPrepareActive.get()) return;
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        // T-FirstExecResultInvisible F1：排队执行意图在本链完成准备时消费（下方缓存落地后）。
        // 意图消费后、执行落地前撞上失败 → onFailure 兜底回落意图 onError（面板已 showLoading）。
        QueuedExecuteIntent queuedIntent = null;
        boolean queuedExecuted = false;
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);

            // T-PreviewExecDs-03 F2：getScriptModel 前 isValid 预检 + 三分量诊断——attach 后
            // console 可能已被 getActiveConsoles 清扫（isValid=false，依赖 facade 数据源加载状态，
            // 见开发计划 §2.2 反编译实锤）。disposed 直接快速失败（等待无意义，F3 自愈清缓存）；
            // 未 disposed 但分量失效时，非 EDT 上有界重查等 facade 异步窗口，EDT 不阻塞直走
            //（getScriptModel 若撞上清扫由 F3 特判接管）。
            ConsoleValidity initialValidity = probeConsoleValidity(console, project, LOG);
            if (initialValidity != null && initialValidity.disposed) {
                LOG.warn("[M36] console DISPOSED right after attach: " + initialValidity);
                throw new IllegalStateException(MpSqlVisualizerBundle.message("execute.console.disposed"));
            }
            if (initialValidity != null && !initialValidity.valid()
                    && !ApplicationManager.getApplication().isDispatchThread()) {
                try {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        Thread.sleep(300);
                        ConsoleValidity v = probeConsoleValidity(console, project, LOG);
                        if (v != null && v.valid()) {
                            LOG.info("[M36] console valid after retry (attempt=" + (attempt + 1) + ")");
                        }
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
                LOG.warn("[M36] console still invalid after bounded retry: " + probeConsoleValidity(console, project, LOG));
            }

            Object scriptModel = consoleClass.getMethod("getScriptModel").invoke(console);
            Class<?> execOptionClass = Class.forName("com.intellij.database.settings.DatabaseSettings$ExecOption");
            Object execOption;
            try {
                execOption = Class.forName("com.intellij.database.settings.DatabaseSettings")
                        .getMethod("getDefaultExecOption").invoke(null);
            } catch (Throwable ignored) {
                execOption = execOptionClass.getDeclaredConstructor().newInstance();
            }
            if (scriptModel == null) {
                // T-FirstExecResultInvisible F1：链就此静默终结（历史形态，未到排队消费点）——
                // 门持有链复位，否则后续执行被永久排队
                PrepareSingleFlightGateKt.failPrepareRequests(prepareGate, prepareOwner, onSubmission, onError,
                        queuedIntent, MpSqlVisualizerBundle.message("execute.failed", "scriptModel unavailable"));
                return;
            }

            Object effectiveScriptModel = scriptModel;
            try {
                Object consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console);
                if (consoleVf instanceof VirtualFile) {
                    VirtualFile cvf = (VirtualFile) consoleVf;
                    String consoleText = document != null ? document.getText() : "";
                    WriteAction.run(() -> {
                        // T-ExecDocSync2 F2：同 F1 收口——commit 后补 saveDocument，console document
                        // 不再滞留「已修改未保存」（第四轮根因 G3，开发计划 §1.2）
                        writeVfTextViaDocument(project, cvf, consoleText);
                    });
                    // T-ConsoleDbContext B：控制台就绪即把库上下文对齐到「数据源 URL 里的库」——
                    // DBN 的文件→数据源映射与库记忆会把会话钉在别的库上（真机事故：exec 文件被映射
                    // 到 mpj_demo@localhost，而 SQL 属于 jpa_demo → Unknown column 'u.username'）。
                    // 用户定案：预览默认以数据源 URL 的库为准；用户之后在控制台手调库，记忆照常保留
                    //（对齐只在「与数据源默认库不一致」时切一次，已一致即 no-op，不会覆盖用户后来的选择）。
                    LOG.info("[M36] console db-context: " + ExecConsoleContext.alignToDataSourceDatabase(console));
                    final Object consoleForModel = console;
                    final VirtualFile vfForModel = cvf;
                    DumbService.getInstance(project).runWhenSmart(() ->
                            // 延迟到 dialpct push 完全结束后（dumb 退出 + 一次 EDT 泵）再重建
                            // write-safe: UI-only（纯 Swing/只读，无文档写）
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try {
                                    refreshConsoleModel(project, consoleForModel, vfForModel, LOG);
                                } catch (Throwable e) {
                                    LOG.warn("[M36] scriptModel refresh (smart) FAILED: " + e.getMessage());
                                }
                            }, ModalityState.any()));
                }
            } catch (Throwable e) {
                LOG.warn("[M36] scriptModel vf-sync FAILED (fallback to attach-time model): " + e.getMessage());
            }

            executionGeneration++;
            preparedConsole = console;
            preparedEditor = editor;
            preparedScriptModel = effectiveScriptModel;
            preparedExecOption = execOption;
            DataSourceSelector.preparedDialectGateRequest = dialectGateRequest;
            DataSourceSelector.preparedDialectGateCallback = onDialectGate;

            // T-FirstExecResultInvisible F1：排队执行意图消费点——准备完成即取走（一次性）。
            // executeNow = 本链显式执行（selectAndExecute 全链）或排队意图就位；后者按
            // executePrepared 口径执行（见下方消费块）。仅门持有链可消费——PASS_THROUGH
            // 预览链消费会把别人的执行意图打在自己的 SQL 上。
            if (!isPrepareActive.get()) return;
            if (prepareOwner != null) {
                queuedIntent = prepareGate.consumePendingExecute(prepareOwner);
                if (queuedIntent != null) {
                    LOG.info("[M36][T-FirstExec F1] queued execute intent consumed after prepare (executeAfterPrepare=" + executeAfterPrepare + ")");
                }
            }
            final boolean executeNow = executeAfterPrepare || queuedIntent != null;

            Object logView = null;
            try {
                logView = consoleClass.getMethod("getView").invoke(console);
            } catch (Throwable ignored) {
                // ignore
            }
            if (logView != null) {
                // 单测/惰性平台在 attach 时尚未建 view；这里 getView 后补登记同一资源。
                NativeConsoleViewLifetime.remember(project, console);
                try {
                    logView.getClass().getMethod("ensureContentInitialized").invoke(logView);
                } catch (Throwable e) {
                    LOG.warn("[M36] ensureContentInitialized FAILED: " + e.getMessage());
                }
            }
            Object ui = null;
            if (logView != null) {
                try { ui = logView.getClass().getMethod("getUi").invoke(logView); } catch (Throwable ignored) { /* ignore */ }
            }
            if (ui == null) {
                try { ui = consoleClass.getMethod("getUi").invoke(console); } catch (Throwable ignored) { /* ignore */ }
            }
            preparedUi = ui;
            JComponent resultComponent = null;
            if (ui != null) {
                try {
                    Object rc = ui.getClass().getMethod("getComponent").invoke(ui);
                    resultComponent = rc instanceof JComponent ? (JComponent) rc : null;
                } catch (Throwable ignored) {
                    // ignore
                }
            }

            JComponent editorComponentRaw = editor.getComponent();
            if (editorComponentRaw == null) {
                // T-FirstExecResultInvisible F1：此处已过排队意图消费点——链就此静默终结（历史
                // 形态）时意图若未执行须回落 onError（面板已 showLoading），并复位单飞位
                PrepareSingleFlightGateKt.failPrepareRequests(prepareGate, prepareOwner, onSubmission, onError,
                        queuedIntent, consoleEditorUnavailableMessage("editorComponent"));
                return;
            }
            editorComponentRaw.setVisible(true);
            if (editorComponentRaw.getPreferredSize() == null || editorComponentRaw.getPreferredSize().height < 50) {
                editorComponentRaw.setPreferredSize(new Dimension(400, 200));
            }
            if (editorComponentRaw.getMinimumSize() == null || editorComponentRaw.getMinimumSize().height < 200) {
                editorComponentRaw.setMinimumSize(new Dimension(400, 200));
            }

            String clientKeyName = null;
            try {
                Field f = Class.forName("com.intellij.database.console.client.SessionClientHolder").getField("CLIENT_KEY");
                DataKey<?> dk = (DataKey<?>) f.get(null);
                clientKeyName = dk.getName();
            } catch (Throwable ignored) {
                // ignore
            }
            final Object consoleForData = console;
            final Editor editorForData = editor;
            final VirtualFile vfForData = vf;
            final PsiFile[] psiForData = {null};
            try {
                psiForData[0] = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
            } catch (Throwable ignored) {
                // ignore
            }
            JComponent editorComponent = new EditorDataPanel(project, editor, vf, console, clientKeyName, psiForData);
            editorComponent.setOpaque(false);
            editorComponent.setLayout(new GridLayout(1, 1));
            editorComponent.add(editorComponentRaw);
            editorComponentRaw.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            editorComponent.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    Dimension avail = editorComponent.getSize();
                    if (avail.width > 0 && avail.height > 0) {
                        editorComponentRaw.setSize(avail.width, avail.height);
                        editorComponentRaw.revalidate();
                    }
                }
            });

            JComponent topBlock = editorComponent;
            try {
                Method buildHeader = consoleClass.getMethod("buildHeaderComponent", Editor.class, consoleClass);
                buildHeader.invoke(null, editor, console);
                LOG.info("[M36] native console header built (Console.Jdbc.Left/Right toolbars)");
            } catch (Throwable headerError) {
                LOG.warn("[M36] buildHeaderComponent FAILED (" + headerError.getMessage() + "), fallback to console-actions toolbar");
                try {
                    Object consoleView = consoleClass.getMethod("getConsoleView").invoke(console);
                    if (consoleView == null) return;
                    com.intellij.openapi.actionSystem.DefaultActionGroup group = new com.intellij.openapi.actionSystem.DefaultActionGroup();
                    Class<?> util = Class.forName("com.intellij.database.console.JdbcConsoleUtil");
                    Method addActions = util.getMethod("addLeftToolbarConsoleActions",
                            com.intellij.openapi.actionSystem.DefaultActionGroup.class, Class.forName("com.intellij.execution.ui.ConsoleView"));
                    addActions.invoke(null, group, consoleView);
                    if (group.getChildrenCount() > 0) {
                        com.intellij.openapi.actionSystem.ActionToolbar toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                                .createActionToolbar("MpSqlNativeConsole", group, true);
                        toolbar.setTargetComponent(editorComponent);
                        JComponent tb = toolbar.getComponent();
                        topBlock = new JPanel(new BorderLayout()) {{
                            setOpaque(false);
                            setBorder(com.intellij.util.ui.JBUI.Borders.empty());
                            add(tb, BorderLayout.NORTH);
                            add(editorComponent, BorderLayout.CENTER);
                        }};
                        LOG.info("[M36] console-actions toolbar injected: " + group.getChildrenCount() + " actions");
                    }
                } catch (Throwable e) {
                    LOG.warn("[M36] toolbar injection skipped (fallback to bare editor): " + e.getMessage());
                }
            }

            // 4.5 完整控制台 = 顶部块（上）/ 结果区（下）垂直 SplitPane（M36.4 主题化形态）
            // ★A49：**无嵌入宿主（onConsole == null）时不组 SplitPane、不摘 DBN 结果组件**——
            // SQL 快捷模板 / 日志转 SQL 执行链只把语句送原生 Database 控制台执行，控制台由 DBN
            // 在 IDE 底部 Services 视图自行展示；此时组出来的 SplitPane 永不展示（onConsole?.invoke
            // 是 no-op），摘取结果组件（ui.getComponent()）无收益、只扰动 DBN 组件树。判据见
            // shouldComposeEmbedSplitPane（含归因更正：这是排查「网格 0 rows」时顺带发现的收敛，
            // 该现象真因是调用方传了不存在的表名，非插件行为）。
            JComponent fullComponent = resultComponent != null
                    ? (DataSourceSelectorResultsKt.shouldComposeEmbedSplitPane(onConsole != null, true)
                        ? DataSourceSelectorResultsKt.buildConsoleSplitPane(topBlock, resultComponent)
                        : null)
                    : null;

            pendingOnConsole = onConsole;
            JComponent component = fullComponent != null ? fullComponent : topBlock;
            preparedTopBlock = topBlock;
            preparedResultComponent = resultComponent;
            preparedFullComponent = fullComponent;
            LOG.info("[M36] embed component=" + component + " (executeAfterPrepare=" + executeAfterPrepare + ")");

            if (!executeAfterPrepare) {
                DataSourceSelectorResultsKt.attachResultsHook(ui, topBlock, resultComponent, console, editor.getDocument().getText(), LOG);
            }
            logDataContextCheck(editorComponent, console, editor, execOption, LOG);
            clearConsolePrompt(console, LOG);

            sharedConsole = console;
            sharedEditor = editor;
            sharedScriptModel = effectiveScriptModel;
            sharedExecOption = execOption;
            sharedUi = ui;
            sharedTopBlock = topBlock;
            // M36.5.53：sharedFullComponent 缓存**完整 SplitPane**（preparedFullComponent 也是
            // fullComponent），不随预览时内嵌的 component（可能为 topBlock 编辑器）变化——否则
            // 执行时 swapResultIntoPersistentConsole 拿到的 sharedFullComponent 是 topBlock（非
            // SplitPane）→ 回退 buildConsoleSplitPane 重建 → A4 身份漂移。预留给执行复用。
            // ★A49：无嵌入宿主时 fullComponent 为 null（不组不摘）——执行复用链
            // （reuseSharedConsole）在 sharedFullComponent == null 时自行回退 buildConsoleSplitPane，
            // 语义不变（见 shouldComposeEmbedSplitPane）。
            sharedFullComponent = fullComponent;
            sharedDataSourceName = dataSourceName;
            DataSourceSelector.sharedDialectGateRequest = dialectGateRequest;
            DataSourceSelector.sharedDialectGateCallback = onDialectGate;
            // T-SI-02：控制台已建，数据源 UUID 从此可取（LocalDataSource.getUniqueId）——
            // 记忆升级为「UUID + 显示名」双字段，下次预览按身份命中而非猜名字。
            // T-ModuleScopedLastUsed W2：带上模块键，让「上次使用」按模块分桶（同时仍写项目级
            // 作无模块上下文的兜底）；moduleKey 缺失时行为与改前逐字一致。
            if (dataSourceName != null) {
                rememberRunner(project, dataSourceName, consoleDataSourceId(console),
                        dialectGateRequest != null ? dialectGateRequest.moduleKey() : null);
            }

            if (onConsole != null) onConsole.accept(component);

            if (!executeNow) {
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                if (component instanceof JSplitPane) {
                    final JComponent compX = component;
                    // T-FirstExecResultInvisible F3：折叠归口 embedMode——仅 PREVIEW 且结果区无内容才折叠
                    //（本链要执行（executeNow）恒不折叠；有内容不吞用户可见的结果）。
                    boolean collapse = ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent(ui));
                    if (collapse) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            ((JSplitPane) compX).setDividerLocation(1.0);
                            ((JSplitPane) compX).revalidate();
                            ((JSplitPane) compX).repaint();
                        }, ModalityState.any());
                    }
                }
            }

            if (executeNow) {
                if (prepareOwner != null && !prepareGate.beginSubmission(prepareOwner)) return;
                // T-FirstExecResultInvisible F1：排队执行意图消费——按 executePrepared 口径调
                // executeOnConsole（onResultsReady 齐：提交后完整 UI 换入 + 面板 EXECUTE 内嵌展开）；
                // onError/onErrorRich/wholeScriptExec 用意图自身回调（面板 showLoading 的解除与错误
                // 投递归意图，不借预览链的静默 onError——否则排队执行失败即静默卡死）。
                if (queuedIntent != null && queuedIntent.onEditor() != null) {
                    queuedIntent.onEditor().accept(editor);
                }
                // 2026-09-23 真机走查补：此处原**漏传** onErrorRich（默认 null）⇒ 这条路径上的错误
                // 投递收口只能回退旧通道，定位按钮永不出现。本函数签名里一直有该参数，只是没往下传
                // ——「实现有、接线漏」的又一例（同族见 ErrorPositionWiringGuardTest 的转发断言）。
                // （k2j 移植基线此处已传 onErrorRich，语义等价；本轮按 master 统一为显式多行形态并接 wholeScriptExec）
                Consumer<JComponent> effectiveOnConsole = queuedIntent != null ? queuedIntent.onConsole() : null;
                Consumer<String> effectiveOnError = (queuedIntent != null && queuedIntent.onError() != null)
                        ? queuedIntent.onError() : onError;
                Consumer<ExecutionErrorInfo> effectiveOnErrorRich = (queuedIntent != null && queuedIntent.onErrorRich() != null)
                        ? queuedIntent.onErrorRich() : onErrorRich;
                // wholeScriptExecEffective 即本调用点的 wholeScriptExec 转发值
                // （master 形态：onResultsReady = queuedIntent?.onConsole …
                //   wholeScriptExec = queuedIntent?.wholeScriptExec ?: wholeScriptExec；
                // Java 无具名实参故以变量名承载，O-64 扫描守卫按 wholeScriptExec
                // 令牌识别旗标转发点）
                final boolean wholeScriptExecEffective = queuedIntent != null ? queuedIntent.wholeScriptExec() : wholeScriptExec;
                Consumer<ExecutionDialectGate.Verdict> effectiveOnDialectGate = (queuedIntent != null
                        && queuedIntent.onDialectGate() != null) ? queuedIntent.onDialectGate() : onDialectGate;
                ExecutionDialectGate.Request effectiveGateRequest = (queuedIntent != null
                        && queuedIntent.dialectGateRequest() != null) ? queuedIntent.dialectGateRequest() : dialectGateRequest;
                executeOnConsole(
                        project, console, editor, effectiveScriptModel, execOption, ui,
                        effectiveOnConsole, effectiveOnError, effectiveOnErrorRich, wholeScriptExecEffective,
                        effectiveGateRequest, effectiveOnDialectGate,
                        PrepareSingleFlightGateKt.selectPrepareSubmission(queuedIntent, onSubmission));
                if (queuedIntent != null) queuedExecuted = true;
            }
        } catch (Throwable e) {
            NativeConsoleViewLifetime.unwrapAndRethrowCritical(e);
            LOG.warn("[M36] prepareInConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传给调用方捕获边界；非 fatal 路径行为不变。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            // T-PreviewExecDs-03 F3：console 已被 getActiveConsoles 清扫（Disposer.register 撞
            // 已 dispose 父节点抛 IncorrectOperationException）→ 清单例缓存（否则下轮执行复用
            // dead 缓存继续失败）+ 可读文案，不再透平台异常长串。
            String failureMsg;
            if (cause instanceof com.intellij.util.IncorrectOperationException
                    && cause.getMessage() != null && cause.getMessage().contains("already been disposed")) {
                LOG.warn("[M36] console disposed during prepare (swept by getActiveConsoles), clear caches for retry: "
                        + cause.getMessage().substring(0, Math.min(120, cause.getMessage().length())));
                clearPrepared();
                dropSharedConsole();
                failureMsg = MpSqlVisualizerBundle.message("execute.console.disposed");
            } else {
                failureMsg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            }
            if (onSubmission != null) onSubmission.accept(ExecutionSubmission.BLOCKED);
            if (onError != null) onError.accept(failureMsg);
            // 消费前异常也必须结算仍在门里的排队请求，不能只复位后丢弃。
            if (prepareOwner != null) prepareGate.abort(prepareOwner, ExecutionSubmission.BLOCKED, failureMsg);
            // 消费后的请求已离开门，仍须单独结算。
            QueuedExecuteIntent qi = queuedIntent;
            if (qi != null && !queuedExecuted) {
                LOG.warn("[M36][T-FirstExec F1] queued execute intent dropped by prepare failure, surface to intent onError");
                qi.complete(ExecutionSubmission.BLOCKED, failureMsg);
            }
        }

        // T-FirstExecResultInvisible F1：单飞位复位（成功/失败同尽；排队意图的消费与回落都在
        // 上方完成）——仅门持有链；PASS_THROUGH 预览链不得触碰别人的门。
        if (prepareOwner != null) prepareGate.finishPrepare(prepareOwner);
    }


    /** M36.5：在指定控制台上执行 SQL。 */
    public static void executeOnConsole(
            @NotNull Project project,
            Object console,
            @NotNull Editor editor,
            Object scriptModel,
            Object execOption,
            Object ui,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        executeOnConsole(project, console, editor, scriptModel, execOption, ui, onResultsReady, onError,
                onErrorRich, wholeScriptExec, null, null, null);
    }

    public static void executeOnConsole(
            @NotNull Project project,
            Object console,
            @NotNull Editor editor,
            Object scriptModel,
            Object execOption,
            Object ui,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        // O-145 W7：真实提交总口再次从 JdbcConsole 取实际数据源；必须早于任何文档改写、失效、
        // submitTransactionLater/executeQueries。准备后用户切换原生控制台数据源时，session 时判定已过期，
        // 本闸负责第二次阻断，绝不按 runner 显示名或缓存名猜目标。
        ExecutionDialectGate.Target actualTarget = ExecutionDialectGate.fromConsole(console);
        if (!DataSourceSelector.allowDialectGate(project, dialectGateRequest, actualTarget, onDialectGate,
                onError, onSubmission)) {
            LOG.warn("[M36][O-145] submit blocked before console mutation: target="
                    + (actualTarget != null ? actualTarget.stableId() : null) + "/"
                    + (actualTarget != null ? actualTarget.dialectName() : null));
            return;
        }
        // 只有真正通过提交前闸的模块/目标才学习；预览 prepare、冲突或取消均不写绑定。
        String moduleKey = dialectGateRequest != null ? dialectGateRequest.moduleKey() : null;
        String dataSourceId = actualTarget != null ? actualTarget.stableId() : null;
        if (moduleKey != null && !moduleKey.isBlank() && dataSourceId != null && !dataSourceId.isBlank()) {
            cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
                    .remember(moduleKey, dataSourceId);
        }
        // O-64/T-RollbackIdentityParse：identity 包装产物整批直跑——覆写本次执行的 ExecOption
        // （只造**副本**传下去，prepared/shared 缓存与用户全局设置一概不动），弹层不再出现、
        // 整批（SET…ON + INSERT + SET…OFF）全部执行，即「Statements 默认全选」的平台实现杠杆。
        // 反射不可用（Database 插件类缺席）时原样返回基础实例 = 维持现态，不劣化。
        Object effectiveExecOption = wholeScriptExec ? wholeScriptExecOption(execOption) : execOption;
        // T-MysqlGStrip T-MG-02：提交链总口（已备控制台路径）——提交文本源是控制台编辑器文档，
        // 执行前剥离 `\G` 后缀并把剥后文本**回写编辑器文档**（所见=所执行，避免显示/执行分叉）。
        // 回写复用既有 T-ExecDocSync 写纪律（dispatchDocWriteOnEdt → writeVfTextViaDocument：
        // setText→commit→saveDocument 同临界区，BGT 调用派 EDT），不新开写路径。
        // 方言按控制台数据源 JDBC URL 判定：非 MySQL 系方言不剥；URL 不可得 → 默认剥离（计划 §三定案）。
        VirtualFile consoleVfForG = null;
        try {
            Object g = console.getClass().getMethod("getVirtualFile").invoke(console);
            consoleVfForG = g instanceof VirtualFile ? (VirtualFile) g : null;
        } catch (Throwable ignored) {
            // getOrNull 语义
        }
        MysqlClientGSuffix.Result gStrip = stripGSuffixForSubmit(editor.getDocument().getText(),
                ExecConsoleContext.dataSourceUrl(console));
        if (gStrip.stripped()) {
            LOG.info("[T-MysqlGStrip] stripped \\G suffix before submit "
                    + "(len " + gStrip.original().length() + "->" + gStrip.submitText().length()
                    + ", vf=" + (consoleVfForG != null ? consoleVfForG.getName() : null) + ")");
            if (consoleVfForG != null) DataSourceSelectorResultsKt.dispatchDocWriteOnEdt(project, consoleVfForG, gStrip.submitText());
        }
        // T-DdlCacheInvalidate（O-12）：DDL/事务提交语句在提交前失效插件自持 schema 缓存
        // （DBX object_cache 语义：失效须在结果返回前、失效失败只记日志不吞执行结果——
        // Oracle 系 DDL 隐式提交，DDL 报错不代表没提交）。全执行链唯一提交收口点；
        // SQL 文本取控制台编辑器文档（三调用路径共用同一文档，提交时刻即执行内容）。
        String executedSql = gStrip.stripped() ? gStrip.submitText() : editor.getDocument().getText();
        try {
            MetadataInvalidator.invalidateAfterExecution(executedSql);
        } catch (Throwable t) {
            LOG.warn("[T-DdlCacheInvalidate] invalidate hook failed (execution continues)", t);
        }
        lastReportedError = null;
        // T-ExecSilentNoop：新执行亦重置提示去重标记——同一次执行只报一次提示。
        executionHintReported = false;
        // T-ExecSilentNoop：notSubmitted 提示携带本次目标数据源名（Kotlin 侧默认参 dsName = sharedDataSourceName）
        final String dsName = sharedDataSourceName;
        consoleErrorBaseline = consoleHistoryLength(console);
        // T-CEC-01：取证日志去重基线同批重置——每次执行至多记一条未识别尾部。
        lastUnrecognizedTail = null;
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);
            Method execute = consoleClass.getMethod("executeQueries", Editor.class, Class.forName("com.intellij.database.script.ScriptModel"), effectiveExecOption.getClass());

            final int generation = executionGeneration;
            LOG.info("[M36] executeOnConsole begin (build=" + BUILD_STAMP + ")");
            // ★A49 只读排障日志（非功能改动，整体自吞不干扰提交流程）：本次提交的 ExecOption 四字段 +
            // 控制台视图是否 showing + 原生编辑器组件当前宿主。「原生控制台结果不渲染/停在 0 rows」
            // 这类现象的三列判别读数——本次该现象的真因已查明在调用方（传了不存在的表名 → 1146），
            // 本行留作同族现象的快速判因工具：newTab=true＝走 A48 家族「新建结果页签」路径；
            // viewShowing=false＝DBN 结果视图未挂载/未展示；editorCompParent=…editorComponent$1＝
            // 我方把原生编辑器组件摘进了自建容器。
            try {
                final Object optTarget = effectiveExecOption;
                java.util.function.Function<String, Object> opt = n -> {
                    try {
                        return optTarget.getClass().getField(n).get(optTarget);
                    } catch (Throwable ignored) {
                        return null; // getOrNull 语义
                    }
                };
                Object viewShowing;
                try {
                    Object v = consoleClass.getMethod("getView").invoke(console);
                    viewShowing = v != null ? v.getClass().getMethod("isShowing").invoke(v) : null;
                } catch (Throwable ignored) {
                    viewShowing = null; // getOrNull 语义
                }
                String editorCompParent;
                try {
                    editorCompParent = editor.getComponent() != null && editor.getComponent().getParent() != null
                            ? editor.getComponent().getParent().getClass().getName() : null;
                } catch (Throwable ignored) {
                    editorCompParent = null; // getOrNull 语义
                }
                LOG.info("[M36][A49] submit diag: newTab=" + opt.apply("newTab") + ", execInside=" + opt.apply("execInside")
                        + ", execOutside=" + opt.apply("execOutside") + ", execSelection=" + opt.apply("execSelection")
                        + ", viewShowing=" + viewShowing + ", editorCompParent=" + editorCompParent);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
                // 排障读数失败只略过（runCatching 语义），不打断提交流程
            }
            DumbService.getInstance(project).runWhenSmart(() -> {
                if (generation != executionGeneration) {
                    LOG.warn("[M36] execution submit cancelled (generation " + generation + " != " + executionGeneration + ")");
                    if (onSubmission != null) onSubmission.accept(ExecutionSubmission.CANCELLED);
                    return;
                }
                try {
                    Object consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console);
                    if (consoleVf instanceof VirtualFile) {
                        VirtualFile cvf = (VirtualFile) consoleVf;
                        // T-MysqlGStrip：剥离后的文本才是本次执行内容（回写失败时编辑器文档可能仍是
                        // 原文，此处以剥后文本为准收敛 vf，保证提交内容恒定无 `\G`）
                        String consoleText = gStrip.stripped() ? gStrip.submitText() : editor.getDocument().getText();
                        WriteAction.run(() -> {
                            // T-ExecDocSync T-EC-01：document 驱动写 + 同临界区落盘（saveDocument）——
                            // 执行前 console 手改未保存内容与磁盘收口，不留「已修改未保存」窗口
                            //（M36.5.20 的 commitDocument/PSI 对齐语义保留在 writeVfTextViaDocument 内）
                            writeVfTextViaDocument(project, cvf, consoleText);
                        });
                        LOG.info("[M36] console doc synced+committed+saved to vf before execution (" + cvf.getName() + ")");
                    }
                } catch (Throwable e) {
                    LOG.warn("[M36] sync console doc to vf FAILED: " + e.getMessage());
                }
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    final boolean[] submittedRef = {false};
                    try {
                        ReadAction.compute(() -> {
                            VirtualFile modelVf = null;
                            try {
                                modelVf = FileDocumentManager.getInstance().getFile(editor.getDocument());
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            if (modelVf == null) {
                                try {
                                    Object cv = consoleClass.getMethod("getVirtualFile").invoke(console);
                                    modelVf = cv instanceof VirtualFile ? (VirtualFile) cv : null;
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }
                            PsiFile vfPsi = modelVf != null ? PsiManager.getInstance(project).findFile(modelVf) : null;
                            java.util.function.Function<Object, String> statementsInfo = m -> {
                                try {
                                    Object st = m.getClass().getMethod("statements").invoke(m);
                                    List<?> l = st instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) st)) : null;
                                    return "count=" + (l != null ? l.size() : "null");
                                } catch (Throwable ex) {
                                    return "FAILED:" + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                                }
                            };
                            Object freshModel = null;
                            if (vfPsi != null) {
                                try {
                                    Object facade = Class.forName("com.intellij.sql.psi.SqlPsiFacade")
                                            .getMethod("getInstance", Project.class).invoke(null, project);
                                    freshModel = facade.getClass().getMethod("createScriptModel", PsiFile.class).invoke(facade, vfPsi);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }
                            boolean freshModelDemoted = false;
                            if (freshModel != null && statementsInfo.apply(freshModel).startsWith("count=0")) {
                                LOG.warn("[M36] vf PSI model has 0 statements, fallback to console scriptModel");
                                freshModel = null;
                                freshModelDemoted = true;
                            }
                            if (freshModel == null) {
                                try {
                                    freshModel = consoleClass.getMethod("getScriptModel").invoke(console);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                if (freshModel == null) freshModel = scriptModel;
                                freshModelDemoted = true;
                            }
                            if (freshModel != scriptModel) {
                                preparedScriptModel = freshModel;
                                if (sharedConsole == console) sharedScriptModel = freshModel;
                            }
                            LOG.info("[M36] executing model: class=" + freshModel.getClass().getSimpleName()
                                    + ", stmts=" + statementsInfo.apply(freshModel)
                                    + ", vfPsiText tail=..." + (vfPsi != null && vfPsi.getText() != null
                                    ? (vfPsi.getText().replace("\n", " ").length() > 30 ? vfPsi.getText().replace("\n", " ").substring(vfPsi.getText().replace("\n", " ").length() - 30) : vfPsi.getText().replace("\n", " "))
                                    : ""));
                            // T-ExecSilentNoop F1a：提交前预检——探测 DBN 会静默拒绝的现场。
                            // demoted 时（vf PSI 解析 0 语句回落 console model）说明准备窗口不稳
                            //（方言 push 未完成 / PSI 未对齐），优先走 executeQueries(freshModel) 兜底
                            // 而非原生链，把"静默不执行"降级为"明确执行/明确报错"。
                            // 保守起见只在 demoted 时强制降级；非 demoted 保持 native 链原行为，
                            // 交由提交后的回执判定（afterSubmitted/startResultTabRetry）兜底。
                            boolean demoteToFallback = freshModelDemoted;
                            DataSourceSelectorResultsKt.execSilentProbe(console, freshModel, freshModelDemoted, dsName, LOG);
                            final PsiFile vfPsiFinal = vfPsi;
                            final Editor editorFinal = editor;
                            final Object execOptionFinal = effectiveExecOption;
                            final boolean[] nativeRunRef = {false};
                            if (vfPsi != null && !demoteToFallback) {
                                Class<?> providerClass = Class.forName(CONSOLE_PROVIDER_CLASS);
                                Method findScriptModel = providerClass.getMethod("findScriptModel", PsiFile.class, com.intellij.psi.PsiElement.class, Editor.class, effectiveExecOption.getClass());
                                final Method[] doRunMethodRef = {null};
                                try {
                                    doRunMethodRef[0] = providerClass.getMethod("doRunQueryInConsole", consoleClass, Class.forName("com.intellij.database.console.JdbcConsoleProvider$Info"));
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                if (doRunMethodRef[0] == null) {
                                    LOG.warn("[M36] native doRunQueryInConsole method not found, fallback");
                                } else {
                                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                                    ApplicationManager.getApplication().invokeLater(() -> {
                                        if (generation != executionGeneration) {
                                            LOG.warn("[M36] execution submit cancelled at EDT (generation " + generation + " != " + executionGeneration + ")");
                                            if (onSubmission != null) onSubmission.accept(ExecutionSubmission.CANCELLED);
                                            return;
                                        }
                                        com.intellij.openapi.Disposable disposable = (console instanceof com.intellij.openapi.Disposable)
                                                ? (com.intellij.openapi.Disposable) console : (com.intellij.openapi.Disposable) () -> {};
                                        try {
                                            TransactionGuard.getInstance().submitTransactionLater(disposable, () -> {
                                                try {
                                                    Object info = findScriptModel.invoke(null, vfPsiFinal, vfPsiFinal, editorFinal, execOptionFinal);
                                                    if (info == null) {
                                                        LOG.warn("[M36] native findScriptModel returned null (nothing to run?)");
                                                        // M36.5.48：findScriptModel 空 = 无可执行语句——显示**真实原因**，
                                                        // 而非笼统凭据超时提示（此前误用 execute.credential.hint，误导用户查密码/驱动）。
                                                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                        ApplicationManager.getApplication().invokeLater(() -> {
                                                            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.noStatement"));
                                                        }, ModalityState.any());
                                                        return;
                                                    }
                                                    LOG.info("[M36] native findScriptModel OK, doRunQueryInConsole");
                                                    doRunMethodRef[0].invoke(null, console, info);
                                                    LOG.info("[M36] native query SUBMITTED (starting result watch)");
                                                    DataSourceSelectorResultsKt.afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, false, executedSql, onErrorRich, onSubmission);
                                                } catch (Throwable t) {
                                                    Throwable cause = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
                                                    final Throwable fCause = cause;
                                                    // T-FP-02：反射包装层（InvocationTargetException）是普通异常，
                                                    // 直接判外层会漏判——须解包后再判 fatal 并透传（见 util/FatalErrors.kt）
                                                    if (FatalErrors.isFatal(cause)) throw (Error) cause;
                                                    LOG.warn("[M36] native run chain FAILED: " + cause.getClass().getName() + ": " + cause.getMessage()
                                                            + "\n    " + stackTrace(cause), cause);
                                                    // M36.5.45：native 执行链失败必须通知 UI（此前仅 LOG.warn，用户看不到任何错误）
                                                    // T-ErrorLocate T-EL-02：富通道并行投递（SQLState/errorCode/驱动位置结构化），
                                                    // 旧 onError(String) 消费方零改动
                                                    // T-ErrorLocateUsability T-ELU-03（O-49）：接上被执行文本长度——
                                                    // 越界钳制此前只在单测生效（调用方全都不传 sqlMaxLength）
                                                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                    ApplicationManager.getApplication().invokeLater(() -> {
                                                        DataSourceSelectorResultsKt.deliverError(
                                                                onErrorRich, onError, fCause,
                                                                fCause.getMessage() != null ? fCause.getMessage() : fCause.toString(),
                                                                executedSql.length());
                                                    }, ModalityState.any());
                                                }
                                            });
                                        } catch (Throwable t) {
                                            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
                                            LOG.warn("[M36] submitTransactionLater FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                                            if (onSubmission != null) onSubmission.accept(ExecutionSubmission.BLOCKED);
                                        }
                                    }, ModalityState.any());
                                    nativeRunRef[0] = true;
                                }
                            }
                            if (!nativeRunRef[0]) {
                                LOG.info("[M36][EXEC] fallback path (native not used: " + (demoteToFallback ? "demoted" : "no vf psi or doRun missing") + "), executeQueries(freshModel)");
                                execute.invoke(console, editor, freshModel, execOptionFinal);
                                submittedRef[0] = true;
                            }
                            return null;
                        });
                        if (submittedRef[0]) {
                            LOG.info("[M36] executeQueries invoked OK (fallback path, submitted synchronously)");
                        } else {
                            LOG.info("[M36] native transaction posted (NOT submitted yet - watch starts at submit)");
                        }
                    } catch (Exception e) {
                        LOG.warn("[M36] executeQueries FAILED: " + e.getMessage(), e);
                        Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
                        // T-FP-02 定例（守卫解包锚点尾款收口）：解包 ITE 后判 fatal 并透传；本块为
                        // catch(Exception) 形态、不入守卫解包锚点计数，收口语录见守卫落地集表注。
                        if (FatalErrors.isFatal(cause)) throw (Error) cause;
                        String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                        final String fMsg = msg;
                        final Throwable fCause = cause;
                        // 打开期间默认 modality 的 invokeLater 冻结（沙盒 12:10:02→12:10:50 实证
                        // 48s 挂起），换入/重试/提示全不跑 → "执行中"卡住。any() 使任务在模态
                        // pump 中照常执行（纯 Swing 更新，安全）。
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        ApplicationManager.getApplication().invokeLater(() -> {
                            // T-ELU-01/03：经 deliverError 收口——富通道优先、**不再**同时投旧通道
                            // （旧通道会抹掉定位按钮），并接上被执行文本长度作越界钳制上界
                            DataSourceSelectorResultsKt.deliverError(onErrorRich, onError, fCause, fMsg, executedSql.length());
                        }, ModalityState.any());
                        return;
                    }
                    if (submittedRef[0]) {
                        // 兜底路径（executeQueries 同步提交）：监听在此启动；原生路径由事务内
                        // afterSubmitted 启动，此处不再重复（防双重监听互相顶掉结果）。
                        DataSourceSelectorResultsKt.afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, false, executedSql, onErrorRich, onSubmission);
                    }
                });
            });
        } catch (Throwable e) {
            LOG.warn("[M36] executeOnConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传（本链跑在池化线程，透传即线程终止、平台记录现场）；非 fatal 路径行为不变。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            final Throwable fCause = cause;
            // T-ELU-01/03：经 deliverError 收口（富通道优先 + 提交文本长度钳制上界）。
            // 本对回调在 executeOnPooledThread 的后台线程上（非 EDT）——deliverError 只做纯计算
            // 与回调转发，不含 Swing 操作，故不改变既有线程契约。
            DataSourceSelectorResultsKt.deliverError(onErrorRich, onError, fCause,
                    fCause.getMessage() != null ? fCause.getMessage() : fCause.toString(),
                    executedSql.length());
        }
    }

    public static String stackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = t.getStackTrace();
        int n = Math.min(st.length, 8);
        for (int i = 0; i < n; i++) sb.append(st[i].toString()).append("\n    at ");
        return sb.toString();
    }


    /** M36.5.6：嵌入后自检数据上下文。 */
    public static void logDataContextCheck(
            JComponent editorBlock,
            Object console,
            Editor editor,
            Object execOption,
            Logger LOG) {
        // T-Perf-13：诊断探针降 debug——非 debug 模式零开销（此前每次执行一次 DataManager 解析
        // + 数十条反射 + info 刷屏；需要诊断时开 debug 恢复全部输出）
        if (!LOG.isDebugEnabled()) return;
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater(() ->
                ReadAction.compute(() -> {
                    try {
                        DataContext ctx = com.intellij.ide.DataManager.getInstance().getDataContext(editorBlock);
                        boolean projectOk = CommonDataKeys.PROJECT.getData(ctx) != null;
                        boolean editorOk = CommonDataKeys.EDITOR.getData(ctx) != null;
                        DataKey<?> clientKey = null;
                        try {
                            Field f = Class.forName("com.intellij.database.console.client.SessionClientHolder")
                                    .getField("CLIENT_KEY");
                            clientKey = (DataKey<?>) f.get(null);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        boolean consoleOk = clientKey != null && clientKey.getData(ctx) != null;
                        PsiFile psiFile = CommonDataKeys.PSI_FILE.getData(ctx);
                        boolean psiOk = psiFile != null;
                        Object sqlAtPlace = null;
                        try {
                            sqlAtPlace = Class.forName("com.intellij.database.console.JdbcConsoleProvider")
                                    .getMethod("isSqlAtPlace", PsiFile.class, Editor.class)
                                    .invoke(null, psiFile, editor);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        Object statementsRange = null;
                        try {
                            Class<?> clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient");
                            statementsRange = Class.forName("com.intellij.database.script.ScriptModelUtil")
                                    .getMethod("getSelectedStatementsRange", clientClass, Editor.class, execOption.getClass())
                                    .invoke(null, console, editor, execOption);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        String modelDiag = null;
                        try {
                            Object model = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console);
                            Object isActual = model != null ? model.getClass().getMethod("isActual").invoke(model) : null;
                            Iterable<?> stmts = null;
                            if (model != null) {
                                Object s = model.getClass().getMethod("statements").invoke(model);
                                stmts = s instanceof Iterable ? (Iterable<?>) s : null;
                            }
                            Object tr = model != null ? model.getClass().getMethod("getTextRange").invoke(model) : null;
                            Language lang = null;
                            try {
                                lang = model != null ? (Language) model.getClass().getMethod("getLanguage").invoke(model) : null;
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            List<?> stmtsList = stmts != null ? new ArrayList<Object>((java.util.Collection<?>) toList(stmts)) : List.of();
                            StringBuilder stmtDetail = new StringBuilder();
                            int si = 0;
                            for (Object it : stmtsList) {
                                if (si++ >= 3) break;
                                String cls = it != null ? it.getClass().getSimpleName() : "null";
                                Object r = null;
                                try {
                                    r = it.getClass().getMethod("getTextRange").invoke(it);
                                } catch (Throwable ignored) {
                                    try { r = it.getClass().getMethod("range").invoke(it); } catch (Throwable ignored2) { /* ignore */ }
                                }
                                stmtDetail.append(cls).append("(").append(r).append("),");
                            }
                            int modelIdentity = System.identityHashCode(model);
                            String subDiag;
                            try {
                                Class<?> posRangeClass = Class.forName("com.intellij.database.script.ScriptModel$PositionRange");
                                Object posRange = posRangeClass.getConstructor(TextRange.class)
                                        .newInstance(TextRange.create(0, editor.getDocument().getTextLength()));
                                Object sub = model != null ? model.getClass().getMethod("subModel", TextRange.class).invoke(model, posRange) : null;
                                List<?> subStmts = null;
                                if (sub != null) {
                                    Object ss = sub.getClass().getMethod("statements").invoke(sub);
                                    subStmts = ss instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) ss)) : null;
                                }
                                Object first = subStmts != null && !subStmts.isEmpty() ? subStmts.get(0) : null;
                                Object api = first != null ? first.getClass().getMethod("api").invoke(first) : null;
                                LanguageExtension<?> ep = null;
                                try {
                                    Field epf = Class.forName("com.intellij.sql.dialects.EvaluationHelper").getField("EP");
                                    ep = (LanguageExtension<?>) epf.get(null);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                Object helperInstance = lang != null && ep != null ? ep.forLanguage(lang) : null;
                                Object isStmt = null;
                                try {
                                    isStmt = helperInstance != null ? helperInstance.getClass()
                                            .getMethod("isStatement", api != null ? api.getClass() : Object.class)
                                            .invoke(helperInstance, api) : null;
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                Object v = null;
                                try {
                                    if (isStmt instanceof com.intellij.openapi.util.Condition) {
                                        v = ((com.intellij.openapi.util.Condition<Object>) isStmt).value(first);
                                    }
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                Object firstRange = null;
                                try {
                                    Class<?> pr2 = Class.forName("com.intellij.database.script.ScriptModel$PositionRange");
                                    Object pr = pr2.getConstructor(TextRange.class).newInstance(TextRange.create(0, 1));
                                    Object sub2 = model != null ? model.getClass().getMethod("subModel", TextRange.class).invoke(model, pr) : null;
                                    Object ss2 = sub2 != null ? sub2.getClass().getMethod("statements").invoke(sub2) : null;
                                    List<?> st2 = ss2 instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) ss2)) : null;
                                    firstRange = st2 != null && !st2.isEmpty() ? (run(() -> {
                                        try { return st2.get(0).getClass().getMethod("range").invoke(st2.get(0)); }
                                        catch (Throwable ignored) { return null; }
                                    })) : null;
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                subDiag = "firstStmt=" + (first != null ? first.getClass().getSimpleName() : "null") + "(" + run(() -> {
                                    try { return first != null ? first.getClass().getMethod("range").invoke(first) : null; }
                                    catch (Throwable ignored) { return null; }
                                }) + ") api=" + (api != null ? api.getClass().getSimpleName() : "null")
                                        + " helper=" + (helperInstance != null ? helperInstance.getClass().getName() : "null")
                                        + " isStatement=" + v + " probe01=" + (st2size(firstRange) + ":" + firstRange);
                            } catch (Throwable t) {
                                subDiag = "filter FAILED:" + t.getMessage();
                            }
                            String docRel;
                            try {
                                Object consoleDoc = Class.forName(CONSOLE_CLASS).getMethod("getDocument").invoke(console);
                                Object consoleVf = Class.forName(CONSOLE_CLASS).getMethod("getVirtualFile").invoke(console);
                                Document fileDoc = consoleVf != null ? FileDocumentManager.getInstance().getDocument((VirtualFile) consoleVf) : null;
                                PsiFile file = null;
                                try {
                                    file = (PsiFile) Class.forName(CONSOLE_CLASS).getMethod("getFile").invoke(console);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                docRel = "consoleDoc(" + System.identityHashCode(consoleDoc) + ":"
                                        + (consoleDoc instanceof Document ? ((Document) consoleDoc).getTextLength() : "null") + ") "
                                        + "editorDoc(" + System.identityHashCode(editor.getDocument()) + ":" + editor.getDocument().getTextLength() + ") "
                                        + "same=" + (consoleDoc == editor.getDocument()) + " "
                                        + "vfDoc(" + System.identityHashCode(fileDoc) + ":"
                                        + (fileDoc instanceof Document ? ((Document) fileDoc).getTextLength() : "null") + ") "
                                        + "vfDocSameEditorDoc=" + (fileDoc == editor.getDocument()) + " "
                                        + "consoleFile=" + (file != null ? file.getClass().getSimpleName() : "null") + ":"
                                        + (file != null ? file.getTextLength() : "null") + ":"
                                        + (file != null && file.getVirtualFile() != null ? file.getVirtualFile().getName() : "null");
                            } catch (Throwable t) {
                                docRel = "docRel FAILED:" + t.getMessage();
                            }
                            String clientCheck;
                            try {
                                Class<?> clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient");
                                boolean isClient = clientClass.isInstance(console);
                                Object clientModel = isClient ? clientClass.getMethod("getScriptModel").invoke(console) : null;
                                Object consoleModel = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console);
                                clientCheck = "consoleIsClient=" + isClient
                                        + " clientModelId=" + (clientModel != null ? System.identityHashCode(clientModel) : "null")
                                        + " consoleModelId=" + System.identityHashCode(consoleModel)
                                        + " sameModel=" + (clientModel == consoleModel);
                            } catch (Throwable t) {
                                clientCheck = "clientCheck FAILED:" + t.getMessage();
                            }
                            modelDiag = "modelIsActual=" + isActual + " stmts=" + stmtsList.size() + " modelRange=" + tr
                                    + " lang=" + (lang != null ? lang.getID() : "null") + " stmtsDetail=[" + stmtDetail + "]"
                                    + " modelId=" + modelIdentity + " docLen=" + editor.getDocument().getTextLength()
                                    + " caret=" + editor.getCaretModel().getOffset() + " execSelection="
                                    + run(() -> {
                                        try { return execOption.getClass().getField("execSelection").getInt(execOption); }
                                        catch (Throwable ignored) { return null; }
                                    })
                                    + " " + docRel + " " + clientCheck + " | " + subDiag;
                        } catch (Throwable t) {
                            modelDiag = "subDiag FAILED:" + t.getMessage();
                        }
                        LOG.debug("[M36] dataContext check: PROJECT=" + projectOk + ", EDITOR=" + editorOk
                                + ", CLIENT_KEY=" + consoleOk + ", PSI_FILE=" + psiOk
                                + ", isSqlAtPlace=" + sqlAtPlace + ", statementsRange=" + statementsRange + ", " + modelDiag);
                        return null;
                    } catch (Throwable e) {
                        LOG.warn("[M36] dataContext check FAILED: " + e.getMessage());
                        return null;
                    }
                }), ModalityState.any());
    }

    public static List<?> toList(Iterable<?> it) {
        List<Object> list = new ArrayList<>();
        for (Object o : it) list.add(o);
        return list;
    }

    public static Object run(RunWithResult r) {
        try { return r.run(); } catch (Throwable ignored) { return null; }
    }

    private interface RunWithResult { Object run() throws Throwable; }

    public static String st2size(Object firstRange) {
        return String.valueOf(firstRange);
    }


    /** 准备链续接（consumer 回调内调用）。 */
    public static void prepareInConsole(
            @NotNull Project project,
            Object session,
            @NotNull VirtualFile vf,
            boolean executeAfterPrepare,
            @Nullable String dataSourceName,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            boolean ownsPrepareGate) {
        prepareInConsole(project, session, vf, executeAfterPrepare, dataSourceName, onConsole, onEditor, onError,
                onErrorRich, wholeScriptExec, null, null, null, null, () -> true);
    }

    public static void prepareInConsole(
            @NotNull Project project,
            Object session,
            @NotNull VirtualFile vf,
            boolean executeAfterPrepare,
            @Nullable String dataSourceName,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable QueuedExecuteIntent prepareOwner,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            @NotNull java.util.function.Supplier<Boolean> isPrepareActive) {
        if (!isPrepareActive.get()) return;
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Class<?> providerClass = Class.forName(CONSOLE_PROVIDER_CLASS);
            Class<?> sessionClass = Class.forName(SESSION_CLASS);

            Method attach = providerClass.getMethod("attachConsole", Project.class, sessionClass, VirtualFile.class);
            attach.setAccessible(true);
            // T-ReverseParenWira：attachConsole 包 WriteIntentReadAction（既非 plain ReadAction，
            // 亦非裸 EDT）——三轮踩坑收口：
            //   · T-PreviewDocLock2 F1 包 plain ReadAction 压读权限断言 → DBN attachConsole 底层
            //     new EditorImpl 的 setHighlighter 走 WriteIntentReadAction，平台锁层明文禁
            //     「plain ReadAction 内再开 WIRA」硬抛（IllegalStateException <- setHighlighter:1832
            //     <- ConsoleExecutionEditor.<init>），被 runCatching 吞成 InvocationTargetException:null
            //     -> 控制台从未挂载。
            //   · T-PreviewSlow F2 整段拆包还原裸 EDT → 修了 setHighlighter 嵌套，但 262 同一条构造链
            //     上 ConsoleExecutionEditor.<init>:54 → FileDocumentManager.getDocument 要求调用方已
            //     持读权限，裸 EDT 直调硬抛 RuntimeExceptionWithAttachments「Read access is allowed
            //     from inside read-action only」（真机堆栈实证），又被外层 runCatching 吞掉静默降级。
            //   · 本轮包 WIRA：隐含读权限满足 getDocument 断言；内部 setHighlighter 的 WIRA 属
            //     WIRA-in-WIRA 重入——平台只禁 plain ReadAction→WIRA，重入合法。同文件
            //     T-ExecCopyFix F2b createTempPsiFile 冷路径同款先例（Computable 单类型实参，
            //     243~262 交集——T-Wia262）。V-2 留 262 真机 idea.log 验证无新嵌套硬抛。
            Object console = WriteIntentReadAction.compute(
                (com.intellij.openapi.util.Computable<Object>) () -> {
                    try {
                        return attach.invoke(null, project, session, vf);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            LOG.info("[M36] attachConsole -> " + console);
            if (console == null) {
                if (onSubmission != null) onSubmission.accept(ExecutionSubmission.BLOCKED);
                DataSourceSelector.abortPrepareGate(prepareOwner, null, LOG);
                return;
            }
            // 在回调/后续准备可能失败之前登记已创建的原生 view；不依赖 prepared/shared 缓存。
            NativeConsoleViewLifetime.remember(project, console);

            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);

            // 2. 执行用编辑器：按 console 文档反查（先于内嵌——编辑器随 console 创建即初始化）。
            // T-ReverseParenWira：单独为 getDocument 包 plain ReadAction（T-PreviewSlow F2 注释
            // 预先授权方案）——此句只取 attachConsole 已构建好的 console 文档，不 new EditorImpl、
            // 不触发 WIRA，plain ReadAction 即满足 262 读权限断言，无嵌套风险。
            Document document = ReadAction.compute(
                (com.intellij.openapi.util.ThrowableComputable<Document, Exception>) () -> {
                    Object documentObj = consoleClass.getMethod("getDocument").invoke(console);
                    return documentObj instanceof Document ? (Document) documentObj : null;
                });
            // T-Perf-13：EDT 上不再 repeat(20){Thread.sleep(20)} 忙等编辑器实例（最多 400ms 冻结）——
            // 首查即中（绝大多数：编辑器随 attachConsole 同步构造）或后台线程调用：同步短轮询（原语义）；
            // EDT 首查未命中：后台轮询线程 20ms 一查，命中后回 EDT 继续余下准备链（prepareAfterEditorResolved）。
            final EditorFactory editorFactory = EditorFactory.getInstance();
            final Document documentRef = document;
            java.util.function.Supplier<Editor> findEditor = () ->
                    documentRef == null ? null
                            : Arrays.stream(editorFactory.getEditors(documentRef)).findFirst().orElse(null);
            Editor firstEditor = findEditor.get();
            if (firstEditor != null || !ApplicationManager.getApplication().isDispatchThread()) {
                Editor editor = firstEditor;
                if (editor == null) {
                    for (int i = 0; i < 20 && editor == null; i++) {
                        editor = findEditor.get();
                        if (editor == null) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        }
                    }
                }
                LOG.info("[M36] editor=" + editor);
                if (editor == null) {
                    // T-TS-09：原为静默 `?: return`——非 EDT 调用方（本分支只在非 EDT 到达）在控制台
                    // 文档无人建编辑器时永久拿不到任何回调，UI 停在「执行中」且 errors=[]（驱动管线打通
                    // 前观测到的「120s 不就绪 + 错误为空」正是此处与下方异步分支两处静默共同造成）。
                    // 只补通知，不改准备链语义（onEditor/onConsole 仍只在成功路径触发）。
                    LOG.warn("[M36] console editor unavailable after 400ms wait (sync path), abort prepare");
                    // write-safe: UI-only（纯 Swing/只读，无文档写）——onError 由面板直接刷状态栏，
                    // 本分支在非 EDT 线程上，须归位 EDT（同下方异步分支语义）
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (onError != null) onError.accept(consoleEditorUnavailableMessage("sync"));
                    }, ModalityState.any());
                    // T-FirstExecResultInvisible F1：链就此终结（未到消费点）——门持有链复位 +
                    // 排队执行意图回落错误回调（原子位操作任意线程可调）
                    DataSourceSelector.abortPrepareGate(prepareOwner, consoleEditorUnavailableMessage("sync"), LOG);
                    return;
                }
                Editor ed = editor;
                // M36.2：编辑器就绪回调（调用方注入插件补全上下文，与原生补全叠加）
                if (onEditor != null) onEditor.accept(ed);
                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName,
                        onConsole, onError, console, document, ed, onErrorRich, wholeScriptExec, prepareOwner,
                        dialectGateRequest, onDialectGate, onSubmission, isPrepareActive);
            } else {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    Editor editor = null;
                    for (int i = 0; i < 20 && editor == null; i++) {
                        editor = findEditor.get();
                        if (editor == null) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        }
                    }
                    final Editor resolved = editor;
                    // T-WSC-02：本块经 prepareAfterEditorResolved 写 console 文档（`:1354`
                    // WriteAction.compute { writeVfTextViaDocument }）——文档写回调一律 write-safe
                    // 模态，any() 恒 write-unsafe（见 DocWriteContext KDoc）。代价：模态弹窗存续期
                    // 准备链延后到弹窗关闭，正是平台对「改模型」要求的语义。
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!isPrepareActive.get()) return;
                        LOG.info("[M36] editor=" + resolved + " (async wait)");
                        if (resolved != null) {
                            if (onEditor != null) onEditor.accept(resolved);
                            try {
                                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName,
                                        onConsole, onError, console, document, resolved, onErrorRich, wholeScriptExec,
                                        prepareOwner, dialectGateRequest, onDialectGate, onSubmission, isPrepareActive);
                            } catch (Throwable e) {
                                NativeConsoleViewLifetime.unwrapAndRethrowCritical(e);
                                LOG.warn("[M36] prepareInConsole(editor async wait) FAILED: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                                Throwable cause = (e instanceof InvocationTargetException)
                                        ? ((InvocationTargetException) e).getTargetException() : e;
                                // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，
                                // 只判外层会漏判目标方法抛出的 fatal——解包后判 fatal 并透传给平台
                                // EDT 处理器记录现场；非 fatal 路径行为不变。
                                if (FatalErrors.isFatal(cause)) throw (Error) cause;
                                if (onError != null) {
                                    onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                                }
                                // T-FirstExecResultInvisible F1：准备链在此终结（未到消费点）——
                                // 门持有链复位 + 排队执行意图回落错误回调
                                DataSourceSelector.abortPrepareGate(prepareOwner, cause.getMessage() != null ? cause.getMessage() : cause.toString(), LOG);
                            }
                        } else {
                            // T-TS-09：原为「无 else」——EDT 调用方（测试/面板准备链的常态）轮询 400ms
                            // 仍取不到编辑器时，本函数就此静默结束：调用方既无 onError 也无 callback，
                            // 面板停在「执行中」且 errors=[]，与上面的同步分支同款不可诊断（20260918
                            // 基线 idea.log `[M36] editor=null (async wait)` 实证）。只补通知，不改语义。
                            LOG.warn("[M36] console editor unavailable after 400ms wait (async path), abort prepare");
                            if (onError != null) onError.accept(consoleEditorUnavailableMessage("async"));
                            // T-FirstExecResultInvisible F1：同上——链终结复位 + 意图回落
                            DataSourceSelector.abortPrepareGate(prepareOwner, consoleEditorUnavailableMessage("async"), LOG);
                        }
                    }, ModalityState.nonModal());
                });
            }
        } catch (Throwable e) {
            LOG.warn("[M36] prepareInConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传给调用方捕获边界；非 fatal 路径行为不变。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            // T-PreviewExecDs-03 F3：console 被清扫 dispose 的处置（prepare 链后段撞上时同款
            // 特判，见 prepareAfterEditorResolved onFailure）
            String failureMsg;
            if (cause instanceof com.intellij.util.IncorrectOperationException
                    && cause.getMessage() != null && cause.getMessage().contains("already been disposed")) {
                LOG.warn("[M36] console disposed during prepareInConsole (swept), clear caches for retry: "
                        + cause.getMessage().substring(0, Math.min(120, cause.getMessage().length())));
                clearPrepared();
                dropSharedConsole();
                failureMsg = MpSqlVisualizerBundle.message("execute.console.disposed");
            } else {
                failureMsg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            }
            if (onSubmission != null) onSubmission.accept(ExecutionSubmission.BLOCKED);
            if (onError != null) onError.accept(failureMsg);
            // T-FirstExecResultInvisible F1：准备链在此终结（未到消费点）——门持有链复位 +
            // 排队执行意图回落错误回调
            DataSourceSelector.abortPrepareGate(prepareOwner, failureMsg, LOG);
        }
    }


    /** M36.5.24g：dumb 退出后重建 console 的 scriptModel。 */
    public static void refreshConsoleModel(
            @NotNull Project project,
            Object console,
            @NotNull VirtualFile consoleVf,
            Logger LOG) {
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);
            Language lang = null;
            try {
                lang = (Language) Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
                        .getMethod("getMapping", Project.class, VirtualFile.class)
                        .invoke(null, project, consoleVf);
            } catch (Throwable ignored) {
                // ignore
            }
            if (lang == null) lang = LanguageUtil.getLanguageForPsi(project, consoleVf);
            Object fresh = Class.forName("com.intellij.sql.script.SqlScriptModel")
                    .getConstructor(Project.class, VirtualFile.class, Language.class)
                    .newInstance(project, consoleVf, lang);
            List<?> freshStmts = null;
            try {
                Object fs = fresh.getClass().getMethod("statements").invoke(fresh);
                freshStmts = fs instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) fs)) : null;
            } catch (Throwable ignored) {
                // ignore
            }
            Object freshLang = null;
            try { freshLang = fresh.getClass().getMethod("getLanguage").invoke(fresh); } catch (Throwable ignored) { /* ignore */ }
            if (freshStmts == null || freshStmts.isEmpty()) {
                LOG.info("[M36] refreshConsoleModel: model empty (lang=" + freshLang + ", stmts=" + (freshStmts != null ? freshStmts.size() : "null") + "), keep console model");
                return;
            }
            Field myScriptField = fresh.getClass().getDeclaredField("myScript");
            myScriptField.setAccessible(true);
            Object myScript = myScriptField.get(fresh);
            Object targetMode = null;
            try {
                targetMode = Class.forName("com.intellij.database.script.ScriptModelUtilCore")
                        .getMethod("getSplitScriptMode", Class.forName("com.intellij.sql.script.Script"))
                        .invoke(null, myScript);
            } catch (Throwable ignored) {
                // ignore
            }
            Object settings = Class.forName("com.intellij.database.settings.DatabaseSettings")
                    .getMethod("getSettings").invoke(null);
            Field splitField = settings.getClass().getField("splitScriptMode");
            Object oldSplit = splitField.get(settings);
            if (targetMode != null && !targetMode.equals(oldSplit)) {
                splitField.set(settings, targetMode);
            }
            Object freshIsActual = null;
            try { freshIsActual = fresh.getClass().getMethod("isActual").invoke(fresh); } catch (Throwable ignored) { /* ignore */ }
            Field field = consoleClass.getDeclaredField("myScriptModel");
            field.setAccessible(true);
            ((AtomicReference<Object>) field.get(console)).set(fresh);
            preparedScriptModel = fresh;
            if (sharedConsole == console) sharedScriptModel = fresh;
            Object afterSet = null;
            try { afterSet = consoleClass.getMethod("getScriptModel").invoke(console); } catch (Throwable ignored) { /* ignore */ }
            List<?> afterStmts = null;
            try {
                Object as = afterSet != null ? afterSet.getClass().getMethod("statements").invoke(afterSet) : null;
                afterStmts = as instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) as)) : null;
            } catch (Throwable ignored) {
                // ignore
            }
            LOG.info("[M36] refreshConsoleModel: replaced with explicit-language model: "
                    + "newLang=" + freshLang + ", stmts=" + freshStmts.size() + ", freshIsActual=" + freshIsActual
                    + ", splitMode=" + oldSplit + "->" + targetMode + ", setAfterGetIdentity=" + System.identityHashCode(afterSet)
                    + ", setAfterStmts=" + (afterStmts != null ? afterStmts.size() : "null"));
            final int[] stable = {0};
            final int[] retries = {0};
            final Language fLang = lang;
            final VirtualFile fVf = consoleVf;
            final Object fConsole = console;
            final Project fProject = project;
            final Logger fLog = LOG;
            Timer timer = new Timer(1000, null);
            timer.addActionListener(e -> {
                try {
                    Object cur = fConsole.getClass().getMethod("getScriptModel").invoke(fConsole);
                    List<?> curStmts = null;
                    try {
                        Object cs = cur != null ? cur.getClass().getMethod("statements").invoke(cur) : null;
                        curStmts = cs instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) cs)) : null;
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (curStmts != null && !curStmts.isEmpty()) {
                        stable[0]++;
                        if (stable[0] >= 2) {
                            timer.stop();
                            fLog.info("[M36] refreshConsoleModel: model stable (stmts=" + curStmts.size() + ")");
                            try {
                                Editor ed = preparedEditor;
                                Object ex = preparedExecOption;
                                JComponent tb = preparedTopBlock;
                                if (ed != null && ex != null && tb != null) logDataContextCheck(tb, fConsole, ed, ex, fLog);
                            } catch (Throwable ex) {
                                fLog.warn("[M36] refreshConsoleModel stable-probe FAILED: " + ex.getMessage());
                            }
                        }
                    } else {
                        stable[0] = 0;
                        retries[0]++;
                        if (retries[0] > 6) {
                            timer.stop();
                            fLog.warn("[M36] refreshConsoleModel: give up after " + retries[0] + " retries (stmts=" + (curStmts != null ? curStmts.size() : "null") + ")");
                            return;
                        }
                        Object freshN = Class.forName("com.intellij.sql.script.SqlScriptModel")
                                .getConstructor(Project.class, VirtualFile.class, Language.class)
                                .newInstance(fProject, fVf, fLang);
                        int nStmts = 0;
                        try {
                            Object ns = freshN.getClass().getMethod("statements").invoke(freshN);
                            List<?> nl = ns instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) ns)) : null;
                            nStmts = nl != null ? nl.size() : 0;
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        if (nStmts > 0) {
                            Field f2 = fConsole.getClass().getDeclaredField("myScriptModel");
                            f2.setAccessible(true);
                            ((AtomicReference<Object>) f2.get(fConsole)).set(freshN);
                            preparedScriptModel = freshN;
                            if (sharedConsole == fConsole) sharedScriptModel = freshN;
                            fLog.info("[M36] refreshConsoleModel: retry#" + retries[0] + " re-set fresh (stmts=" + nStmts + ")");
                        } else {
                            fLog.info("[M36] refreshConsoleModel: retry#" + retries[0] + " fresh empty (stmts=" + nStmts + "), keep current");
                        }
                    }
                } catch (Throwable ex) {
                    fLog.warn("[M36] refreshConsoleModel timer FAILED: " + ex.getMessage());
                    timer.stop();
                }
            });
            timer.setRepeats(true);
            timer.start();
            try {
                Editor ed = preparedEditor;
                Object ex = preparedExecOption;
                JComponent tb = preparedTopBlock;
                if (ed != null && ex != null && tb != null) logDataContextCheck(tb, console, ed, ex, LOG);
            } catch (Throwable ex) {
                LOG.warn("[M36] refreshConsoleModel post-probe FAILED: " + ex.getMessage());
            }
        } catch (Throwable e) {
            LOG.warn("[M36] refreshConsoleModel FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }


}
