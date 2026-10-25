package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver;
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
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
 * 原生数据源选择器（M36，反射隔离）：选数据源 → Database 控制台直接执行。
 *
 * 流程（选中后自动执行，等价原生 Ctrl+Enter 全链路）：
 *  1. 创建临时 .sql 文件（含 SQL）+ PsiFile —— 同时作为 VF 控制台挂载文件
 *  2. 反射 DatabaseRunners.getAttachDataSourceRunners 获取原生 Runner 列表
 *  3. 选中 runner → run() 内部建 session 并回调 consumer
 *  4. consumer 内续接执行链：JdbcConsoleProvider.attachConsole + console.executeQueries
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class DataSourceSelector {

    /** DatabaseRunners 类名（反射入口）。 */
    private static final String RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners";

    /** 执行链反射目标类名。 */
    public static final String CONSOLE_PROVIDER_CLASS = "com.intellij.database.console.JdbcConsoleProvider";
    public static final String CONSOLE_CLASS = "com.intellij.database.console.JdbcConsole";
    public static final String SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession";

    /** last-used 数据源记忆 key（prepareConsole 静默复用，PropertiesComponent per-project）。 */
    private static final String LAST_RUNNER_KEY = "mpsql.visualizer.last.runner";

    /**
     * T-SI-02：last-used 记忆的<b>身份键</b>（数据源 UUID，LocalDataSource.getUniqueId）。
     * 旧键（显示名）继续读写，兼容一个版本后废弃（见 CHANGELOG [Unreleased]）；命中判定以
     * UUID 为主、显示名为回退——显示名同名多条时旧实现静默取首条，属「猜」，已弃。
     */
    private static final String LAST_RUNNER_ID_KEY = "mpsql.visualizer.last.runner.id";

    /**
     * T-ModuleScopedLastUsed W2：已写过的<b>模块键清单</b>（逗号分隔）。
     *
     * <p>为什么需要单独记清单：{@code PropertiesComponent} 的公开 API 只有 {@code unsetValue/getValue/setValue}
     * （{@code javap} 实证，无列举全部键的方法），故「清空全部层级」时无法靠前缀扫描发现有哪些模块桶。
     * 清单在首次写该模块记忆时登记（{@link #registerLastUsedModuleKey}），清空后一并移除。</p>
     */
    private static final String _LAST_USED_MODULE_KEYS_KEY = "mpsql.visualizer.last.runner.modules";

    /** 构建版本戳。 */
    public static final String BUILD_STAMP = "M36.8-exec-silent-noop";

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    public static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** M36.5.76：OUTFILL 未锚定诊断日志节流（EDT 串行访问，无需同步）。 */
    public static long lastOutFillDiagAt = 0L;

    // M36.5：已准备控制台缓存
    public static Object preparedConsole = null;
    public static Editor preparedEditor = null;
    public static Object preparedScriptModel = null;
    public static Object preparedExecOption = null;
    public static Object preparedUi = null;
    /** O-145 W7 / UI-040：随本次准备缓存携带的方言闸请求与判定回调。 */
    public static volatile ExecutionDialectGate.Request preparedDialectGateRequest = null;
    public static volatile Consumer<ExecutionDialectGate.Verdict> preparedDialectGateCallback = null;
    public static JComponent preparedTopBlock = null;
    public static JComponent preparedResultComponent = null;
    public static JComponent preparedFullComponent = null;

    // M36.5.5：共享控制台缓存
    public static Object sharedConsole = null;
    public static Editor sharedEditor = null;
    public static Object sharedScriptModel = null;
    public static Object sharedExecOption = null;
    public static Object sharedUi = null;
    public static JComponent sharedFullComponent = null;
    public static String sharedDataSourceName = null;
    public static volatile ExecutionDialectGate.Request sharedDialectGateRequest = null;
    public static volatile Consumer<ExecutionDialectGate.Verdict> sharedDialectGateCallback = null;

    /** T-PreviewFlash：mpsql-exec.sql 的 vf+psi 配对缓存（路径稳定，vf 有效即复用，吸收每次预览的 VFS refresh + findFile 慢操作）。
     *  T-ExecSlowOps F3：{@link #prewarmExecTempFile} 后台线程成为第二写方后须 volatile 跨线程可见。 */
    private static volatile TempPsiFile cachedTempPsiFile = null;
    /** T-PreviewExecDs-02 F1：缓存归属项目名（跨项目拒绝诊断用，与 cachedTempPsiFile 同生命周期）。 */
    private static volatile String cachedTempPsiProjectName = null;

    /**
     * T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（stage 标识或异常摘要），
     * 成功时清空——供 chooseAndPrepare 的 onError 文案携带，用户截图反馈的笼统
     * "Failed to create temporary SQL file" 自此可归因。
     */
    public static volatile String lastTempFileFailure = null;

    /** T-PreviewFlash：已 setMapping 的 vf（同文件幂等短路，避免每次预览重设方言触发 push all dumb）。
     * T-ExecSlowOps F3：BGT 预热链也会读写，volatile 保证跨线程可见。 */
    public static volatile VirtualFile dialectMappedVf = null;

    /**
     * O-136 T-PreviewExecDsDialect：{@code dialectMappedVf} 当前映射到的方言 language id
     * （null/「MySQL」= 旧行为）。与 vf 字段同生命周期成对回写——幂等短路判定为
     * 「同 vf <b>且同方言</b>」，目标数据源方言切换（MySQL↔DM）后同 vf 也必须重映射。
     */
    public static volatile String dialectMappedLanguageId = null;

    /** T-UiFixGate9 测试缝：exec 预暖链在途标记读数（victim 面板测试等「链静止」用）。 */
    public static boolean execPrewarmInFlightForTest() {
        return execPrewarmInFlight.get();
    }

    /** 查询的目标或原始渲染方言未知时的确认测试缝；生产为 null 时走本地化确认框。 */
    public static volatile java.util.function.Predicate<ExecutionDialectGate.Verdict>
            unknownTargetQueryConfirmationForTest = null;

    /** UI023：仅替代 DBN 状态读取；测试仍走执行文档身份检查和真实缓存读取。 */
    public static volatile java.util.function.Function<Object, SchemaValidationGate.Target>
            schemaTargetReaderForTest = null;

    /** T-ExecSlowOps F2：exec 预热进行中标记（并发预览只跑一条真实链，其余直接返回）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean execPrewarmInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 预热只作投机增强；平台等待超时后放弃，由弹窗冷路径按需重建。 */
    private static final long EXEC_PREWARM_BUDGET_MS = 30_000L;

    /** M36.5.6：结果区换入回调。 */
    public static Consumer<JComponent> pendingOnConsole = null;

    /** M36.5.9：最近一次换入宿主的结果区组件实例。 */
    public static Component lastLiveResultSwapped = null;

    /** M36.5.52：最近一次已上报的 SQL 错误文本——早检去重用。 */
    public static String lastReportedError = null;

    /** M36.5.57：本次执行开始时 console history 的长度基线——早检只扫<b>本次执行之后</b>新增的
     * 文本，忽略 history 里的旧错误残留（否则正常 SQL 会把上个失败的错误"带过来"，用户实测）。
     * T-PerfSweep2-01：internal 供 ConsoleErrorExtractTest 注入基线（生产路径仅 executeOnConsole 赋值）。 */
    public static int consoleErrorBaseline = 0;

    /** T-CEC-01：最近一次已记入日志的「未命中判据的控制台尾部」——取证日志去重用（每次执行至多
     * 记一条，避免 400ms 拍刷屏）。 */
    public static volatile String lastUnrecognizedTail = null;

    /** T-ExecSilentNoop：本次执行的提示已发出标记——8s 连接监控与 40 拍超时窗口共用，
     * 保证同一次执行只报一次提示（此前二者各按同一 key 报一次，用户见两次红字）。 */
    public static volatile boolean executionHintReported = false;

    /** M36.5.10：执行提交代际计数。 */
    @SuppressWarnings("VolatileArrayField")
    public static volatile int executionGeneration = 0;

    /** M36.5.44：结果换入代际。 */
    public static volatile int swapGeneration = 0;

    // M36.5.7：共享缓存的分件存储
    public static JComponent sharedTopBlock = null;

    /**
     * T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队门——打开预览的准备链未落地时
     * 点执行不再建第二条临时文件/会话/控制台链（H1 双准备链竞态的结构性消除），排队意图由
     * 完成准备的门持有链按 executePrepared 口径消费。纯逻辑见 {@link PrepareSingleFlightGate}。
     */
    public static final PrepareSingleFlightGate prepareGate = new PrepareSingleFlightGate();

    /**
     * T-DevAcceptFix2 ④：写入标记表（key = VFS 路径）。写点唯一=本类的 exec 文件写入路径
     * （{@link #writeVfTextViaDocument}）；读点=差量快路径（{@link #execFileContentMatches}，仅在无 document 时）。
     * 时间戳校验使「外部改盘」自动失效标记（不会误判「已一致」而跳过必要写入）。
     */
    public static final java.util.concurrent.ConcurrentHashMap<String, ExecWrittenSnapshot> lastWrittenExecContent =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * O-96 测试缝：headless 无 Database 插件时注入「在籍数据源名单 + UUID→名解析」替代
     * 真链（{@link DataSourceSettings#getAllDataSources} 反射在 Community 沙盒恒空）；生产为 null
     * 走真链。测试用例负责置空复位（object 单例静态状态）。
     */
    public static java.util.function.Function<Project, kotlin.Pair<java.util.List<String>, java.util.function.Function<String, String>>>
            rememberedLookupForTest = null;

    public static final DataSourceSelector INSTANCE = new DataSourceSelector();
    private DataSourceSelector() {}

    /** 控制台是否已就绪（prepareConsole 成功后 true）。 */
    public static boolean isPrepared() {
        return preparedConsole != null;
    }

    /**
     * T-UI030：是否有准备链正在飞。结构化 EXPLAIN 在无已绑数据源时要弹既有选择器，
     * 但已有准备链在飞时<b>只能等待</b>——预览准备对并发是 PASS_THROUGH，再走一次
     * chooseAndPrepare 会建第二条会话/控制台（T-FirstExecResultInvisible F1 已实证的坑）。
     */
    public static boolean isPrepareInFlight() {
        return prepareGate.prepareInFlight.get();
    }

    /**
     * O-145 W7：最终实际目标方言闸门统一处置。request 为空保持所有旧调用逐字行为；
     * 非空时回调先收到判定，随后按状态继续、确认或阻断。AUTO_RERENDER_REQUIRED 必停当前点击，
     * 由宿主无损重渲染后要求用户复核并再次点击，绝不在同一次点击里偷偷改写后提交。
     */
    public static boolean allowDialectGate(Project project,
                                           @Nullable ExecutionDialectGate.Request request,
                                           @Nullable ExecutionDialectGate.Target target,
                                           @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
                                           @Nullable Consumer<String> onError,
                                           @Nullable Consumer<ExecutionSubmission> onSubmission) {
        if (request == null) return true;
        ExecutionDialectGate.Verdict verdict = ExecutionDialectGate.evaluate(request,
                target != null ? target.dialectName() : null);
        if (onDialectGate != null) onDialectGate.accept(verdict);
        boolean needsConfirmation = verdict.status() == ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM
                || verdict.status() == ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM;
        if (verdict.status() != ExecutionDialectGate.Status.MATCH && !needsConfirmation) {
            // 先报告未提交，后交付原有详细红字，不能用通用阻断状态覆盖详细原因。
            if (onSubmission != null) onSubmission.accept(ExecutionSubmission.BLOCKED);
        }
        boolean allowed;
        switch (verdict.status()) {
            case MATCH -> allowed = true;
            case TARGET_UNKNOWN_QUERY_CONFIRM, RENDER_UNKNOWN_QUERY_CONFIRM -> {
                java.util.function.Predicate<ExecutionDialectGate.Verdict> test =
                        unknownTargetQueryConfirmationForTest;
                if (test != null) {
                    allowed = test.test(verdict);
                } else {
                    boolean confirmed = false;
                    try {
                        String messageKey = verdict.status() == ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM
                                ? "execute.dialectGate.unknownQuery.confirm"
                                : "execute.dialectGate.unknownRenderQuery.confirm";
                        confirmed = com.intellij.openapi.ui.Messages.showYesNoDialog(
                                project,
                                MpSqlVisualizerBundle.message(messageKey),
                                MpSqlVisualizerBundle.message("execute.dialectGate.title"),
                                com.intellij.openapi.ui.Messages.getWarningIcon())
                                == com.intellij.openapi.ui.Messages.YES;
                    } catch (Throwable ignored) {
                        confirmed = false;
                    }
                    allowed = confirmed;
                }
            }
            case AUTO_RERENDER_REQUIRED -> {
                if (onDialectGate == null && onError != null) onError.accept(dialectGateMessage(verdict));
                allowed = false;
            }
            default -> {
                if (onError != null) onError.accept(dialectGateMessage(verdict));
                allowed = false;
            }
        }
        if (!allowed && needsConfirmation && onSubmission != null) {
            onSubmission.accept(ExecutionSubmission.CANCELLED);
        }
        return allowed;
    }

    /** 方言闸判定 → 用户可读文案（bundle 双语；空串仅用于 MATCH）。 */
    public static String dialectGateMessage(ExecutionDialectGate.Verdict verdict) {
        String unknown = MpSqlVisualizerBundle.message("execute.dialectGate.unknownDialect");
        String rendered = verdict.renderedDialectName() != null ? verdict.renderedDialectName() : unknown;
        String target = verdict.targetDialectName() != null ? verdict.targetDialectName() : unknown;
        return switch (verdict.status()) {
            case MATCH -> "";
            case AUTO_RERENDER_REQUIRED ->
                    MpSqlVisualizerBundle.message("execute.dialectGate.rerendered", rendered, target);
            case MANUAL_CONFLICT ->
                    MpSqlVisualizerBundle.message("execute.dialectGate.manualConflict", rendered, target);
            case RERENDER_UNAVAILABLE ->
                    MpSqlVisualizerBundle.message("execute.dialectGate.rerenderUnavailable", rendered, target);
            case TARGET_UNKNOWN_QUERY_CONFIRM ->
                    MpSqlVisualizerBundle.message("execute.dialectGate.unknownQuery.confirm");
            case TARGET_UNKNOWN_BLOCK -> MpSqlVisualizerBundle.message("execute.dialectGate.unknownBlock");
            case RENDER_UNKNOWN_QUERY_CONFIRM ->
                    MpSqlVisualizerBundle.message("execute.dialectGate.unknownRenderQuery.confirm");
            case RENDER_UNKNOWN_BLOCK -> MpSqlVisualizerBundle.message("execute.dialectGate.unknownRenderBlock");
        };
    }

    /**
     * UI023：执行前置的 Schema 校验目标（只认本面板执行文档与 preparedEditor.document 同一对象）。
     */
    @Nullable
    public static SchemaValidationGate.Target schemaValidationTarget(
            com.intellij.openapi.editor.@Nullable Document document) {
        // 首次 onEditor→embedConsole 会清 nativeConsoleEditor 辅助引用，但保留实际执行文档。
        // 只认本面板执行文档与 preparedEditor.document 同一对象；不得借共享或其他预览文档。
        if (document == null || preparedEditor == null || document != preparedEditor.getDocument()) return null;
        Object console = preparedConsole;
        if (console == null) return null;
        java.util.function.Function<Object, SchemaValidationGate.Target> reader = schemaTargetReaderForTest;
        if (reader != null) return reader.apply(console);
        if (!ExecuteSqlSupport.isAvailable()) return null;
        try {
            Object valid = console.getClass().getMethod("isValid").invoke(console);
            if (!Boolean.TRUE.equals(valid)) return null;
            return com.intellij.openapi.application.ReadAction.compute(() ->
                    ExecConsoleContext.schemaValidationTarget(console));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * R33 EXPLAIN 结构化通道：当前预览会话已绑控制台的 DBN 数据源（共享/已备控制台二者取一，
     * 循 T-ConsoleDbContext 既有选择形态——预览会话用哪条数据源，EXPLAIN 就在同一条上跑）。
     *
     * <p>控制台有效性先探（{@code isValid()} 反射，复用链同款判据——失效控制台的数据源对象不可信）；
     * 取引用包 ReadAction（EDT 读 PSI 硬抛平台陷阱；{@link ExecConsoleContext#boundDataSource}
     * 是 DBN 链内 API，Community 下经 McpDbExecutor.isDatabaseAvailable 前置闸不会到达）。
     * 无控制台/已失效/反射失败一律 null → 调用方 fail-closed 回退直送通道。</p>
     */
    @Nullable
    public static Object boundConsoleDataSource() {
        final Object console = sharedConsole != null ? sharedConsole : preparedConsole;
        if (console == null) return null;
        boolean live;
        try {
            live = Boolean.TRUE.equals(console.getClass().getMethod("isValid").invoke(console));
        } catch (Throwable ignored) {
            live = false;
        }
        if (!live) return null;
        return ReadAction.compute(() -> ExecConsoleContext.boundDataSource(console));
    }

    /**
     * O-95 T-PreviewEdtGuard S3：当前预览会话绑定数据源<b>名</b>（预览面板绑定指示行数据源）。
     * 选择链与 {@link #boundConsoleDataSource} 完全同源（共享/已备控制台二者取一 + isValid 先探 +
     * DBN 链内取引用）；取名走反射（本类对 DBN 类型一律反射惯例，Community/无 Database 插件
     * fail-closed → null）。{@code LocalDataSource.getName()} 出处：243 平台 jar 反编译
     * （ExecConsoleContext KDoc 同款口径：{@code JdbcConsole.getDataSource(): LocalDataSource}），
     * 与 {@code ds?.name}（ExecConsoleContext 诊断行在用）同一方法面。
     * null = 未绑定/失效/反射失败 → 面板显示未绑定灰字（panel.bindstate.unbound）。
     */
    @Nullable
    public static String boundConsoleDataSourceName() {
        Object ds = boundConsoleDataSource();
        if (ds == null) return null;
        try {
            Object name = ds.getClass().getMethod("getName").invoke(ds);
            return name instanceof String ? (String) name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * O-96 T-PreviewBindAssociation S1：last-used 记忆回退取名（绑定指示行第二优先级专用，
     * <b>只读</b>——歧义不清记忆、不弹窗、不触碰任何状态，展示路径禁副作用）。
     *
     * <p>判定与执行链 T-SI-02 静默复用<b>同一纯函数</b> {@link #resolveLastUsedRunnerIndex}（UUID 精确命中
     * → 名字唯一命中 → 同名多条歧义即弃），runner 名单换为在籍数据源名
     * （{@link DataSourceSettings#getAllDataSources}）——指示行所说「下次执行会用哪条」与 prepareConsole
     * 静默复用的实际解析完全同源。记忆缺失 → null（快速早退）；UUID 失效（数据源已删）按
     * T-SI-02 口径退回名字判定；歧义/不存在 → null = 未绑定，不静默猜。</p>
     *
     * <p>优先级契约（S3）：活控制台 {@link #boundConsoleDataSourceName} 恒高于本记忆回退 &gt; 未绑定。</p>
     */
    /** Kotlin 默认参等价重载：moduleKey 缺省 null（既有项目级行为，逐字不变）。 */
    @Nullable
    public static String rememberedDataSourceName(@NotNull Project project) {
        return rememberedDataSourceName(project, null);
    }

    /**
     * T-ModuleScopedLastUsed W2：{@code moduleKey} 非空时优先读该模块的记忆，未命中回落项目级
     * （见 {@link LastUsedKeys} 与 {@link #lastUsedRunner(Project, String)} 的分层语义）。不传＝既有项目级行为，逐字不变。
     */
    @Nullable
    public static String rememberedDataSourceName(@NotNull Project project, @Nullable String moduleKey) {
        LastUsedMemory memory = lastUsedRunner(project, moduleKey);
        if (isNullOrBlank(memory.dataSourceId()) && isNullOrBlank(memory.displayName())) return null;
        kotlin.Pair<java.util.List<String>, java.util.function.Function<String, String>> lookup =
                rememberedLookupForTest != null ? rememberedLookupForTest.apply(project) : null;
        java.util.List<String> registeredNames;
        java.util.function.Function<String, String> nameById;
        if (lookup != null) {
            registeredNames = lookup.getFirst();
            nameById = lookup.getSecond();
        } else {
            registeredNames = new java.util.ArrayList<>();
            for (Object ds : DataSourceSettings.getAllDataSources(project)) {
                registeredNames.add(DataSourceSettings.getName(ds));
            }
            nameById = id -> {
                try {
                    Object ds = DataSourceSettings.getDataSourceById(project, id);
                    return ds != null ? DataSourceSettings.getName(ds) : null;
                } catch (Throwable ignored) {
                    return null;
                }
            };
        }
        return resolveRememberedDataSourceName(memory, registeredNames, nameById);
    }

    /**
     * T-ExplainPwdPrompt W4（2026-10-24）：结构化 EXPLAIN 的 <b>last-used 取源回落</b>——
     * 真绑定（{@link #boundConsoleDataSource}）为 null 且绑定链 runner 枚举为 0（数据源无方言
     * 映射时平台 {@code DatabaseRunners.getAttachDataSourceRunners} 返回空；243 沙盒实测：
     * Database 视图已连接仍为 0）时，回落到面板「数据源（上次使用）」记忆取源，让结构化
     * 通道（{@code McpDbExecutor} askPassword=true）不依赖绑定链也能走到。
     *
     * <p>防错源纪律：按名解析<b>歧义即弃</b>（同名多源不猜）；方言不符由既有 allowDialectGate
     * 闸拦（EXPLAIN 同为执行旁路，同门控）。</p>
     *
     * <p>T-DialectInferPollution W9（2026-10-24）：EXPLAIN 链统一取源回落（结构化 W4 + 直送 W8
     * 合并重构）。原「无脑回落 last-used」有跨模块错源副作用：jar-model demo 模块（无学习条目）
     * 的表被 last-used（mp_demo）硬套 → {@code mp_demo.wms_inventory_jar_demo doesn't exist}（用户
     * 沙盒实测截图）。修正语义：</p>
     *
     * <ol>
     *  <li>预览模块有学习绑定 → 用学习源（模块归属强于全局记忆；UUID 解析在籍对象，比按名稳）；</li>
     *  <li>学习源与 last-used <b>同时存在且不同</b> → 证据冲突不猜，返回 null（下游 NO_DATA_SOURCE
     *      引导弹数据源选择器，用户选一次即自愈更新学习与记忆）；</li>
     *  <li>学习缺失 → last-used 唯一命中可用（兜底旧场景）——注意这是兜底不是归属，模块学习会
     *      随执行自愈。</li>
     * </ol>
     *
     * @param moduleKey 预览 SQL 所属模块键（面板 ui.SqlPreviewPanel.sourceModuleKey）
     * @param sql       预览 SQL 原文（T-DialectInferPollution W10 表归属路由用；null/无表 = 跳过路由验证）
     * @return 在籍数据源对象（唯一命中）；名字缺失/歧义/枚举失败一律 null
     */
    @Nullable
    public static Object explainFallbackDataSource(@NotNull Project project,
                                                   @Nullable String moduleKey,
                                                   @Nullable String sql) {
        DialectDecisionEngine.Candidate learned = null;
        if (moduleKey != null && !moduleKey.isBlank()) {
            try {
                learned = cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
                        .learnedCandidate(project, moduleKey);
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
        }
        String lastName = null;
        try {
            String name = rememberedDataSourceName(project, moduleKey);
            if (name != null && !name.isBlank()) lastName = name;
        } catch (Throwable ignored) {
            // getOrNull 语义
        }
        Object lastDs = null;
        if (lastName != null) {
            try {
                Object single = null;
                int hits = 0;
                for (Object ds : DataSourceSettings.getAllDataSources(project)) {
                    if (lastName.equals(DataSourceSettings.getName(ds))) {
                        single = ds;
                        hits++;
                    }
                }
                lastDs = hits == 1 ? single : null;  // singleOrNull 语义：同名多条歧义即弃
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
        }
        String learnedId = learned != null ? learned.id() : null;
        String lastId = null;
        if (lastDs != null) {
            try {
                lastId = DataSourceSettings.getUniqueId(lastDs);
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
        }
        Object candidate;
        String choice = resolveExplainFallbackChoice(learnedId, lastId);
        if (choice == null) {
            return null;                           // 双证据冲突：学习源与 last-used 不同源，交用户选择
        } else if (choice.equals(learnedId)) {
            try {
                candidate = DataSourceSettings.getDataSourceById(project, learnedId);
            } catch (Throwable ignored) {
                candidate = null;                  // getOrNull 语义
            }
        } else {
            candidate = lastDs;
        }
        if (candidate == null) return null;
        // T-DialectInferPollution W10：<b>表归属路由</b>——学习/last-used 证据可能陈旧或跨模块错配
        //（用户实测 `mp_demo.wms_inventory_jar_demo doesn't exist`），而「表在哪个库」是更强的
        // 归属事实：结构缓存（Database 视图展开/预览预热即就位，只读零内省）能证明哪条源持有
        // 预览引用的全部表。候选源不持有而<b>唯一</b>其他源持有 → 路由到它；多源持有 → 冲突交选择
        // 器；零源持有（缓存未热/表不存在）→ 保持候选（诚实降级，不阻断）。
        List<String> tables = new ArrayList<>();
        try {
            List<String> all = cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator.tableNames(sql != null ? sql : "");
            for (int i = 0; i < all.size() && i < 8; i++) tables.add(all.get(i));
        } catch (Throwable ignored) {
            // getOrNull().orEmpty() 语义
        }
        if (!tables.isEmpty()) {
            List<Integer> ownerIndexes;
            try {
                ownerIndexes = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
                        .cachedOwnerIndexes(project, tables);
            } catch (Throwable ignored) {
                ownerIndexes = List.of();          // getOrNull().orEmpty() 语义
            }
            if (!ownerIndexes.isEmpty()) {
                List<Object> all = null;
                try {
                    all = DataSourceSettings.getAllDataSources(project);
                } catch (Throwable ignored) {
                    all = List.of();
                }
                String candidateName = null;
                try {
                    candidateName = DataSourceSettings.getName(candidate);
                } catch (Throwable ignored) {
                    // getOrNull 语义
                }
                List<Object> ownerObjects = new ArrayList<>();
                for (Integer idx : ownerIndexes) {
                    Object ds = idx >= 0 && idx < all.size() ? all.get(idx) : null;
                    if (ds == null) continue;      // mapNotNull
                    String name = null;
                    try {
                        name = DataSourceSettings.getName(ds);
                    } catch (Throwable ignored) {
                        // getOrNull 语义
                    }
                    if (!java.util.Objects.equals(name, candidateName)) ownerObjects.add(ds);
                }
                if (ownerObjects.isEmpty()) {
                    // 候选源即持有者 → 可信，照旧
                } else if (ownerObjects.size() == 1) {
                    candidate = ownerObjects.get(0);   // 唯一持有 → 路由
                } else {
                    return null;                       // 多源都持有 → 冲突，交用户选择
                }
            } else {
                boolean cacheHot = false;
                try {
                    cacheHot = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
                            .cacheHasAnyTable(project);
                } catch (Throwable ignored) {
                    cacheHot = false;                  // getOrDefault(false) 语义
                }
                if (cacheHot) {
                    // T-DialectInferPollution W12：缓存热（有源已内省出表）但没有任何源持有预览的表
                    // → 表不存在于任何已连库，错源执行必败——引导选择数据源（返回 null 走 NO_DATA_SOURCE）。
                    return null;
                }
                // 缓存全冷：无法证明归属，保持候选（诚实降级，Database 视图展开一次后自愈）
            }
        }
        return candidate;
    }

    /**
     * T-DialectInferPollution W9 纯判定核心（headless 直测）：回落证据选择。
     * 学习源与 last-used 同时存在且不同 → null（冲突不猜，下游引导选择数据源）；
     * 学习源优先；学习缺失时兜底 last-used。
     */
    @Nullable
    public static String resolveExplainFallbackChoice(@Nullable String learnedId, @Nullable String lastId) {
        if (learnedId != null && lastId != null && !learnedId.equals(lastId)) return null;
        if (learnedId != null) return learnedId;
        return lastId;
    }

    /** O-96 S1：记忆回退取名判定（纯函数壳——复用 T-SI-02 判定，命中下标换算在籍名）。 */
    @Nullable
    public static String resolveRememberedDataSourceName(LastUsedMemory memory,
                                                         java.util.List<String> registeredNames,
                                                         java.util.function.Function<String, String> dataSourceNameById) {
        Integer index = resolveLastUsedRunnerIndex(memory, registeredNames, dataSourceNameById).index();
        if (index == null) return null;
        return index >= 0 && index < registeredNames.size() ? registeredNames.get(index) : null;
    }

    /** O-96 测试辅助：headless 直写 last-used 记忆双键（生产写入口 {@link #rememberRunner} 为 private）。 */
    public static void plantLastUsedMemoryForTest(@NotNull Project project, @Nullable String id, @Nullable String name) {
        com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
        if (isNullOrBlank(id)) pc.unsetValue(LAST_RUNNER_ID_KEY); else pc.setValue(LAST_RUNNER_ID_KEY, id);
        if (isNullOrBlank(name)) pc.unsetValue(LAST_RUNNER_KEY); else pc.setValue(LAST_RUNNER_KEY, name);
    }

    /** Kotlin {@code isNullOrBlank()} 等价（null 或全空白）。 */
    private static boolean isNullOrBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /** 释放已准备控制台引用（面板清空/关闭时调用）。 */
    public static void clearPrepared() {
        executionGeneration++;
        preparedConsole = null;
        preparedEditor = null;
        preparedScriptModel = null;
        preparedExecOption = null;
        preparedUi = null;
        preparedDialectGateRequest = null;
        preparedDialectGateCallback = null;
        preparedTopBlock = null;
        preparedResultComponent = null;
        preparedFullComponent = null;
        // T-ExecCopyFix F2c：不清 cachedTempPsiFile——vf 失效守卫已兜底（下次命中前校验
        // isValid），内容覆盖写走 document 驱动不破坏 vf↔psi 配对；原实现每次预览切换清
        // 缓存，执行必落冷路径，击穿 T-PreviewFlash「跨点击吸收」的设计意图。
    }

    /**
     * T-Perf-16：面板关闭/项目关闭时释放单例滞留引用——单例的 prepared、shared、
     * pendingOnConsole 槽位强引用已关闭面板（pendingOnConsole 闭包捕获 SqlPreviewPanel 整棵
     * Swing 树）与原生控制台/编辑器/文档（持旧 Project 引用）。此前只有面板「清空」按钮走
     * clearPrepared，弹窗关闭/项目关闭路径无清理挂点 → 堆中滞留整棵面板树。
     */
    public static void releaseForPanel() {
        clearPrepared();
        dropSharedConsole();
        pendingOnConsole = null;
    }

    /**
     * M36.5：准备控制台（不执行）。
     * 数据源选择策略：唯一数据源/last-used 命中 → 静默；多数据源 → 原生弹窗（记住选择）；
     * 无数据源 → 预览路径静默 onError（不抢侧边栏），显式执行路径打开 Database 工具窗口 + onError。
     */
    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback) {
        prepareConsole(project, sql, onConsole, onEditor, onError, callback, null, null, false);
    }

    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            boolean forceChoose) {
        prepareConsole(project, sql, onConsole, onEditor, onError, callback, dialectGateRequest, onDialectGate,
                forceChoose, null, false);
    }

    /**
     * T-ExplainFailUx W4 / T-ExplainSwitchNative W6 扩展形态。
     *
     * @param preferredRunnerName T-ExplainFailUx W4：用户已显式指定源名 → 按名直选建链，不再弹平台选择器。
     * @param forceChoosePopup    T-ExplainSwitchNative W6（2026-10-24 用户实测「点切换数据源却重试执行了」）：
     *                            <b>必须弹选择器</b>——即便 runner 枚举只剩一条（旧行为 {@code shouldBeChosenIfAlone}
     *                            会直选执行，用户看不到列表）。仅「切换数据源」入口置 true；预览默认准备与显式执行不受影响。
     */
    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            boolean forceChoose,
            @Nullable String preferredRunnerName,
            boolean forceChoosePopup) {
        DataSourceSelectorResultsKt.chooseAndPrepare(project, sql, false, onConsole, onEditor, onError, callback,
                null, false, dialectGateRequest, onDialectGate, null, forceChoose,
                preferredRunnerName, forceChoosePopup);
    }

    /** M36.5：在已准备的控制台上执行（Kotlin 默认参 {@code wholeScriptExec = false} 形态）。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        return executePrepared(project, onResultsReady, onError, onErrorRich, false);
    }

    /**
     * M36.5：在已准备的控制台上执行。
     *
     * @param wholeScriptExec O-64：identity 包装产物整批直跑（ExecOption 覆写为
     *                        EXECUTE_INSIDE_WHOLE_SCRIPT/EXECUTE_OUTSIDE_WHOLE_SCRIPT，
     *                        原生 Statements 选择弹层不再弹出、整批全部执行）；普通执行传默认 false
     */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        Object console = preparedConsole;
        Editor editor = preparedEditor;
        Object scriptModel = preparedScriptModel;
        Object execOption = preparedExecOption;
        if (console == null || editor == null || scriptModel == null || execOption == null) return false;
        return executePrepared(project, onResultsReady, onError, onErrorRich, wholeScriptExec, null, null, null);
    }

    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission) {
        Object console = preparedConsole;
        Editor editor = preparedEditor;
        Object scriptModel = preparedScriptModel;
        Object execOption = preparedExecOption;
        if (console == null || editor == null || scriptModel == null || execOption == null) return false;
        DataSourceSelectorConsoleKt.executeOnConsole(project, console, editor, scriptModel, execOption, preparedUi,
                onResultsReady, onError, onErrorRich, wholeScriptExec,
                dialectGateRequest != null ? dialectGateRequest : preparedDialectGateRequest,
                onDialectGate != null ? onDialectGate : preparedDialectGateCallback,
                onSubmission);
        // true 仍仅表示已进入准备好的控制台路径；提交结果只由 onSubmission 回报。
        return true;
    }

    /** 选择数据源并执行 SQL（准备 + 执行合一，Kotlin 默认参 {@code wholeScriptExec = false} 形态）。 */
    public static void selectAndExecute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        selectAndExecute(project, sql, onConsole, onEditor, onError, callback, onErrorRich, false);
    }

    /** 选择数据源并执行 SQL（准备 + 执行合一，O-64 wholeScriptExec 重载）。 */
    public static void selectAndExecute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        selectAndExecute(project, sql, onConsole, onEditor, onError, callback, onErrorRich, wholeScriptExec,
                null, null, null, false);
    }

    public static void selectAndExecute(
            @NotNull Project project,
            @NotNull String sql,
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
        DataSourceSelectorResultsKt.chooseAndPrepare(project, sql, true, onConsole, onEditor, onError, callback,
                onErrorRich, wholeScriptExec, dialectGateRequest, onDialectGate, onSubmission, forceChoose);
    }

    /** M36.5.5：释放共享控制台缓存。 */
    public static void dropSharedConsole() {
        sharedConsole = null;
        sharedEditor = null;
        sharedScriptModel = null;
        sharedExecOption = null;
        sharedUi = null;
        sharedTopBlock = null;
        sharedFullComponent = null;
        sharedDataSourceName = null;
        sharedDialectGateRequest = null;
        sharedDialectGateCallback = null;
    }

    /** M36.5.50：清空 RunnerLayoutUi 的结果 Tab。 */
    public static void clearResultContents(Object ui, Logger LOG) {
        if (ui == null) return;
        try {
            Object contents = ui.getClass().getMethod("getContents").invoke(ui);
            if (!(contents instanceof Content[])) return;
            Content[] arr = (Content[]) contents;
            Method remove = ui.getClass().getMethod("removeContent", Content.class, Boolean.TYPE);
            int removed = 0;
            for (Content content : arr) {
                if (!isOutputContent(content)) {
                    try {
                        remove.invoke(ui, content, true);
                        removed++;
                    } catch (Throwable ex) {
                        LOG.warn("[M36] clear result content FAILED: " + ex.getMessage());
                    }
                }
            }
            LOG.info("[M36] result contents cleared (preview switch, removed=" + removed + ")");
        } catch (Throwable e) {
            LOG.warn("[M36] clear result contents FAILED: " + e.getMessage());
        }
    }

    /** M36.5.57：取 console history 文档长度。 */
    public static int consoleHistoryLength(Object console) {
        if (console == null) return 0;
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return 0;
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer == null) return 0;
            Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
            if (doc == null) return 0;
            Object len = doc.getClass().getMethod("getTextLength").invoke(doc);
            return len instanceof Integer ? (Integer) len : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * T-CEC-03：兜底呈现——判据认不出的失败，把控制台<b>新增文本去掉本次 SQL 回显后的残余</b>交回
     * 面板，而不是给「SQL 已提交执行，但等待结果超时……检查密码输入框/测试连接」这类笼统提示
     * （真机 P15 实证：把一条 MySQL 语法错归因成密码/驱动问题，归因方向是错的）。
     *
     * <p>只做「减去回显」不做任何语义判断：残余是什么就报什么，面板侧附一句如实说明
     * （{@code execute.error.unknownShape}）——不声称它是 SQL 错误，也不猜原因。</p>
     *
     * <p>剔除规则（宁少勿错）：空行；DBN 回显行（剥掉时间戳/提示符后是本次执行 SQL 的子串）；
     * DBN 状态行（Connected / completed in … 等）。全部被剔完返 null（调用方落回既有提示）。</p>
     */
    @Nullable
    public static String extractConsoleResidue(Object console, @Nullable String sqlText, Logger LOG) {
        if (console == null) return null;
        try {
            String text = newConsoleText(console);
            if (text == null) return null;
            if (text.isBlank()) return null;
            String sqlNorm = normalizeForEcho(sqlText != null ? sqlText : "");
            String prompt = consolePrompt(console);
            List<String> kept = new ArrayList<>();
            for (String rawLine : text.split("\n", -1)) {
                String line = rawLine.trim();
                if (line.isBlank()) continue;
                if (isConsoleStatusLine(line)) continue;
                if (isSqlEchoLine(line, sqlNorm, prompt)) continue;
                kept.add(line);
            }
            if (kept.isEmpty()) return null;
            int from = Math.max(0, kept.size() - RESIDUE_MAX_LINES);
            StringBuilder joinedBuilder = new StringBuilder();
            for (int i = from; i < kept.size(); i++) {
                if (i > from) joinedBuilder.append('\n');
                joinedBuilder.append(kept.get(i));
            }
            String joined = joinedBuilder.toString();
            String out = joined.length() > RESIDUE_MAX_CHARS ? joined.substring(0, RESIDUE_MAX_CHARS) : joined;
            String logTail = out.replace("\n", "⏎");
            LOG.info("[T-CEC] console residue (no known signature): "
                    + (logTail.length() > 200 ? logTail.substring(0, 200) : logTail));
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * T-CEC：读取 console history 文档中<b>基线之后</b>的新增文本（读不到 / 无新增返 null）。
     *
     * <p>T-PerfSweep2-01 的片段物化契约在此保持（ConsoleErrorExtractTest 逐条断言）：baseline&lt;=0
     * 走整串一次、&gt;0 只切一刀、无增长零物化——本函数是两个定时器逐拍调用的共用读取面，
     * 抽出来只为让兜底呈现复用同一份切片语义。</p>
     */
    @Nullable
    public static String newConsoleText(Object console) throws Exception {
        if (console == null) return null;
        Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
        if (consoleView == null) return null;
        Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
        if (historyViewer == null) return null;
        Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
        if (doc == null) return null;
        // T-PerfSweep2-01：getCharsSequence + subSequence 片段读取——本函数被 600ms×8 拍与
        // 400ms×40/100 拍两个定时器<b>逐拍</b>调用，此前 doc.getText() 每拍整串拷贝全史
        // （history 随长会话涨到 MB 级后是 EDT 上唯一可感知的 O(全史) 重复分配）。
        // 只物化 baseline 之后的切片；getCharsSequence 与 getText 同属 Document 接口，
        // 反射可达性一致，且免去 BadLocationException 面。
        Object seqObj = doc.getClass().getMethod("getCharsSequence").invoke(doc);
        if (!(seqObj instanceof CharSequence)) return null;
        CharSequence seq = (CharSequence) seqObj;
        // M36.5.57：只扫本次执行之后新增的文本（基线截断）——忽略 history 残留的旧错误。
        // baseline 可能因 DBN 写入历史而增长；若文本长度 <= 基线说明无新增内容，返回 null。
        int baseline = consoleErrorBaseline;
        if (baseline <= 0) {
            return seq.toString();
        } else if (seq.length() > baseline) {
            return seq.subSequence(baseline, seq.length()).toString();
        } else {
            return null;
        }
    }

    /**
     * T-CEC-01：未命中判据的控制台新增文本尾部（有界 + 同一段只记一次）——取证用，供下次扩判据
     * 时核对 DBN 真实写入 history 的报文形态（是否带 {@code [时间戳]} 前缀、是否跨行、SQLSTATE 前缀
     * 是否在场）。行数与字符数双上限，避免长会话刷屏。
     */
    public static void logUnrecognizedConsoleTail(List<String> lines, Logger LOG) {
        int from = Math.max(0, lines.size() - UNRECOGNIZED_TAIL_LINES);
        StringBuilder tailBuilder = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (i > from) tailBuilder.append('\n');
            tailBuilder.append(lines.get(i));
        }
        String joined = tailBuilder.toString();
        String tail = joined.length() > UNRECOGNIZED_TAIL_CHARS ? joined.substring(0, UNRECOGNIZED_TAIL_CHARS) : joined;
        if (tail.isBlank() || tail.equals(lastUnrecognizedTail)) return;
        lastUnrecognizedTail = tail;
        LOG.info("[T-CEC] console tail (no error signature matched): " + tail.replace("\n", "⏎"));
    }

    /** T-CEC-02：错误上下文摘录的最大行数（错误行起算）。 */
    public static final int EXCERPT_MAX_LINES = 8;

    /** T-CEC-01：取证日志的行数 / 字符数上限。 */
    private static final int UNRECOGNIZED_TAIL_LINES = 6;
    private static final int UNRECOGNIZED_TAIL_CHARS = 600;

    /** T-CEC-03：兜底呈现的行数 / 字符数上限。 */
    private static final int RESIDUE_MAX_LINES = 6;
    private static final int RESIDUE_MAX_CHARS = 600;

    /**
     * T-CEC-02：DBN 服务端错误前缀形态 {@code [SQLSTATE][errorCode]}——DBN 把驱动 SQLException 的
     * SQLState 与 vendor code 拼在报文最前（真机日志实证：{@code [42S22][1054] Unknown column …}、
     * {@code [42000][1064] You have an error in your SQL syntax …}）。跨方言同形，比短语词表硬：
     * 原词表认 {@code syntax error}，却认不出 {@code You have an error in your SQL syntax}。
     * 5 位定长是关键——{@code [2026-09-22 08:48:12]} 这类时间戳前缀（19 字符）不会被误命中。
     */
    private static final Pattern SQLSTATE_PREFIX = Pattern.compile("\\[[0-9A-Za-z]{5}]\\[\\d+]");

    /** T-CEC-02：Oracle 错误码形态（{@code ORA-00933} 等）——Oracle 报文常不带 SQLSTATE 前缀。 */
    private static final Pattern ORACLE_CODE = Pattern.compile("\\bORA-\\d{5}\\b", Pattern.CASE_INSENSITIVE);

    /** T-CEC-02：错误签名短语族（结构性判据覆盖不到的方言 / 驱动惯用报文）。 */
    public static final List<String> ERROR_SIGNATURES = List.of(
            // M36.5.48 原有 14 条（历史命中系，行为保持不动）
            "sql exception", "syntax error", "sqlstate", "unknown column",
            "unknown table", "doesn't exist", "does not exist", "access denied",
            "deadlock", "error code", "broken pipe", "connection refused",
            "[日本語エラー]", "sql（", "exception in thread",
            // T-CEC-02 新增：MySQL 语法错（用户 2026-09-22 真机 Output 报文逐字）
            "you have an error in your sql syntax",
            // T-CEC-02 新增：厂商固定报文（MySQL 1062 / 1451、Oracle ORA-00933）
            "duplicate entry", "foreign key constraint",
            "cannot add or update a child row", "cannot delete or update a parent row",
            "sql command not properly ended"
    );

    /**
     * R33 真机走查修复：EXPLAIN 附随的 SHOW WARNINGS note 行（{@code [HY000][1003] Note: …}，
     * 内含重写查询）——informational，不是执行失败。MySQL 服务端 1003 号属 note 消息段
     * （ER_YES，不落失败码段）；结构化通道回退后直送通道执行 EXPLAIN 成功时，DBN 会把这条
     * note 写进 history，早检逐拍扫到即被 {@link #SQLSTATE_PREFIX} 命中误报红条（真机实证：
     * 结果已正常出，底部红条却是这条 note）。整行排除（不要求行内带 "Note" 字样——DBN
     * 是否透出级别词无源码级实锤，宁可宽放：1003 号在 MySQL 不是失败码）。
     */
    private static final Pattern NOTE_1003_PREFIX = Pattern.compile("\\[HY000]\\[1003]", Pattern.CASE_INSENSITIVE);

    /** T-CEC-02：一行是否命中错误判据（结构性判据 + 短语族；EXPLAIN note 1003 行除外）。 */
    public static boolean isErrorSignatureLine(String line) {
        if (NOTE_1003_PREFIX.matcher(line).find()) return false;
        if (SQLSTATE_PREFIX.matcher(line).find()) return true;
        if (ORACLE_CODE.matcher(line).find()) return true;
        for (String s : ERROR_SIGNATURES) {
            if (line.toLowerCase().contains(s.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * T-CEC-02：本插件自己写进 Output 的回显行前缀（{@link #writeConsoleOutput} 的「执行失败: 」）——
     * 提取时排除，避免「已上报的错误被自己写回控制台后又当成新错误」的自反馈。
     */
    public static final String EXEC_FAILURE_ECHO_PREFIX = "执行失败:";

    public static boolean isOwnEchoLine(String line) {
        return stripConsolePrefix(line, null).startsWith(EXEC_FAILURE_ECHO_PREFIX);
    }

    /** T-CEC-02：DBN 输出行的 {@code [时间戳] } 前缀（用户真机报文逐行带此前缀）。 */
    private static final Pattern TIMESTAMP_PREFIX =
            Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?]\\s*");

    private static String stripTimestamp(String line) {
        String s = line.trim();
        Matcher m = TIMESTAMP_PREFIX.matcher(s);
        if (!m.find()) return s;
        return s.substring(m.end()).trim();
    }

    /** T-CEC-02：剥掉时间戳与（DBN 实读的）提示符前缀——呈现与复制都更干净。 */
    public static String stripConsolePrefix(String line, @Nullable String prompt) {
        String s = stripTimestamp(line);
        if (prompt != null && !prompt.isBlank() && s.startsWith(prompt)) {
            s = s.substring(prompt.length()).trim();
        }
        return s;
    }

    /**
     * T-CEC-02：读 DBN 控制台提示符（{@code mpj_demo>} / {@code ds>>}，即 {@code LanguageConsoleView.prompt}——
     * 243 源码 jar 实证为 Kotlin {@code var prompt: String?}，插件既有 {@link #clearConsolePrompt} 已用 setPrompt）。
     * 取不到返 null：调用方退化为只剥时间戳，不猜提示符形态。
     */
    @Nullable
    public static String consolePrompt(Object console) {
        try {
            if (console == null) return null;
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return null;
            Object promptObj = consoleView.getClass().getMethod("getPrompt").invoke(consoleView);
            if (!(promptObj instanceof String)) return null;
            String prompt = (String) promptObj;
            return prompt.isBlank() ? null : prompt;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** T-CEC-03：回显比对用的归一化（空白折叠 + 小写）。 */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static String normalizeForEcho(String text) {
        return WHITESPACE_RUN.matcher(text).replaceAll(" ").trim().toLowerCase();
    }

    /** T-CEC-03：兜底剥离提示符的候选形态（仅当剩余部分是本次 SQL 子串时才据此判为回显）。 */
    private static final Pattern PROMPT_CANDIDATE = Pattern.compile("^\\S{1,64}>+\\s+");

    /**
     * T-CEC-03：该行是否本次执行 SQL 的回显——剥掉时间戳后（必要时再剥提示符）是本次 SQL 文本的
     * 子串即认（多行 SQL 的每一行都是 SQL 的子串）。判据宽松方向是「多剔」：宁可残余变空退回
     * 既有提示，也不把回显当错误报给用户。
     */
    private static boolean isSqlEchoLine(String line, String sqlNorm, @Nullable String prompt) {
        if (sqlNorm.isEmpty()) return false;
        String body = stripTimestamp(line);
        List<String> candidates = new ArrayList<>();
        candidates.add(body);
        if (prompt != null && !prompt.isBlank() && body.startsWith(prompt)) {
            candidates.add(body.substring(prompt.length()).trim());
        }
        Matcher promptMatcher = PROMPT_CANDIDATE.matcher(body);
        if (promptMatcher.find()) {
            candidates.add(body.substring(promptMatcher.end()).trim());
        }
        for (String c : candidates) {
            String n = normalizeForEcho(c);
            if (n.length() >= 4 && sqlNorm.contains(n)) return true;
        }
        return false;
    }

    /**
     * T-CEC-03：DBN 状态 / 结果摘要行——不是错误内容，兜底呈现时剔除。
     *
     * <p>判据刻意偏「多剔」：语句<b>成功</b>但 DBN 不建结果 Tab 的场景同样会走满 40 拍窗口（DML 与
     * {@code use} 这类工具语句的影响行数/完成回显就写在 Output 里），把 {@code 1 row affected} 当残余报出来
     * 等于把成功说成失败；剔多了只是落回既有提示，属可接受方向（宁少勿错）。</p>
     */
    private static final List<String> CONSOLE_STATUS_LINES = List.of(
            "connected", "completed in", "executing", "fetching", "fetched",
            "cancelled", "canceled", "query ok", "affected", "took ", "elapsed"
    );

    /** T-CEC-03：结果摘要词（{@code 1 row affected} / {@code N rows} / {@code updated} 等）——同上，偏多剔。 */
    private static final Pattern CONSOLE_STATUS_WORDS =
            Pattern.compile("\\b(rows?|records?|updated|deleted|inserted)\\b", Pattern.CASE_INSENSITIVE);

    private static boolean isConsoleStatusLine(String line) {
        String n = stripTimestamp(line).toLowerCase();
        for (String s : CONSOLE_STATUS_LINES) {
            if (n.contains(s)) return true;
        }
        return CONSOLE_STATUS_WORDS.matcher(n).find();
    }

    /** M36.5.48：判断一行是否纯 SQL 语句（避免把「WHERE t.id=1」等当错误）。 */
    public static boolean isSqlLine(String line) {
        String lower = line.trim().toLowerCase();
        // 含 SQL 关键字且无错误判据命中 → 大概率是 SQL 文本而非错误
        boolean starts = lower.startsWith("select") || lower.startsWith("from")
                || lower.startsWith("where") || lower.startsWith("join")
                || lower.startsWith("insert") || lower.startsWith("update")
                || lower.startsWith("delete") || lower.startsWith("create")
                || lower.startsWith("alter") || lower.startsWith("drop");
        return starts && !isErrorSignatureLine(line);
    }

    /** M36.5.62：清空 console 历史文档。 */
    public static void clearConsoleHistory(Object console, Logger LOG) {
        if (console == null) return;
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return;
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer == null) return;
            Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
            if (doc == null) return;
            doc.getClass().getMethod("setText", String.class).invoke(doc, "");
            LOG.info("[M36] console history cleared (preview switch)");
        } catch (Throwable e) {
            LOG.warn("[M36] clear console history FAILED: " + e.getMessage());
        }
    }

    /** M36.5.8：清空控制台提示符。 */
    public static void clearConsolePrompt(Object console, Logger LOG) {
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return;
            consoleView.getClass().getMethod("setPrompt", String.class).invoke(consoleView, "");
            LOG.info("[M36] console prompt cleared");
        } catch (Throwable e) {
            LOG.warn("[M36] clear console prompt FAILED: " + e.getMessage());
        }
    }

    /**
     * T-TS-09：控制台编辑器不可得时的用户可读提示（{@link #prepareInConsole} 两条静默分支共用）。
     *
     * <p>复用既有键 {@code execute.failed}（"执行 SQL 失败：{0}"）＋技术性明细参数：明细是诊断串而非文案，
     * 与 {@code execute.tempFile.failed} 传 {@link #lastTempFileFailure} 同款（新增专用键要动双语 bundle，
     * 不在本任务写集内）。</p>
     *
     * @param path 触发的分支（sync = 非 EDT 同步轮询 / async = EDT 异步等待）——排障时据此定位，
     *             两条分支的成因不同（同步：调用方在非 EDT；异步：EDT 首查未命中后后台轮询）
     */
    public static String consoleEditorUnavailableMessage(String path) {
        return MpSqlVisualizerBundle.message(
                "execute.failed",
                "控制台编辑器不可得（" + path + "：400ms 内 getEditors(consoleDocument) 恒空，无一方为该文档建过编辑器）");
    }

    /**
     * T-FirstExecResultInvisible F1：准备链在到达消费点（{@code prepareAfterEditorResolved} 的排队
     * 意图消费）之前终结时的兜底——门持有链复位单飞位 + 已排队执行意图回落意图自身 onError
     * （意图所属面板在 executeSql 里已 showLoading 并拿到 callback(true)，静默丢弃会让状态条
     * 恒「执行中」）。PASS_THROUGH 预览链不得调用（不持有门、也无属于它的排队意图）。
     */
    public static void abortPrepareGate(@Nullable QueuedExecuteIntent prepareOwner, @Nullable String failureMessage,
                                        Logger LOG) {
        if (prepareGate.abort(prepareOwner, ExecutionSubmission.BLOCKED,
                failureMessage != null ? failureMessage
                        : MpSqlVisualizerBundle.message("execute.tempFile.failed", "prepare aborted"))) {
            LOG.warn("[M36][T-FirstExec F1] prepare chain aborted - settle current and queued requests");
        }
    }

    /**
     * T-PreviewExecDs-03 F2：console 有效性的三分量快照（反编译实锤 {@code isValid} =
     * {@code !isDisposed && isViewValid && facade.findDataSource(dataSource.uniqueId) != null}）。
     * 任何一环为 false 即可能被 {@code getActiveConsoles} 清扫 dispose——日志/自愈判定共用。
     */
    public static final class ConsoleValidity {
        public final boolean disposed;
        public final Boolean viewValid;
        public final Boolean dsInFacade;

        ConsoleValidity(boolean disposed, Boolean viewValid, Boolean dsInFacade) {
            this.disposed = disposed;
            this.viewValid = viewValid;
            this.dsInFacade = dsInFacade;
        }

        public boolean valid() {
            return !disposed && !Boolean.FALSE.equals(viewValid) && !Boolean.FALSE.equals(dsInFacade);
        }

        @Override
        public String toString() {
            return "disposed=" + disposed + ", viewValid=" + viewValid + ", dsInFacade=" + dsInFacade + ", valid=" + valid();
        }
    }

    /**
     * T-SI-01：共享会话复用身份判定（纯函数，默认 test 通道可覆盖）——在 {@code console.isValid()}
     * 与编辑器未释放之外，把 {@link ConsoleValidity} 的 {@code dsInFacade} 分量作为<b>独立判据</b>接进复用
     * 判定（复用 attach 路径既有探测，不重造）。{@code identity == null} 表示探测不可用（降级），
     * 按现状放行——探测失败不得把正常复用打成重建（宁可用旧会话，也不每次预览重建连接）。
     */
    public static boolean reuseIdentityAccepted(@Nullable ConsoleValidity identity) {
        return identity == null || identity.valid();
    }

    /** T-PreviewExecDs-03 F2：反射探测 console 三分量有效性（任一环节失败降级 null，绝不阻断）。 */
    @Nullable
    public static ConsoleValidity probeConsoleValidity(Object console, Project project, Logger LOG) {
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);
            boolean disposed;
            try {
                // 243 平台仅 Disposable 版本（标 deprecated），无 Object 重载——cast + suppress
                disposed = (console instanceof com.intellij.openapi.Disposable)
                        ? Disposer.isDisposed((com.intellij.openapi.Disposable) console) : false;
            } catch (Throwable t) {
                disposed = false;
            }
            Boolean viewValid = null;
            try {
                Object v = consoleClass.getMethod("isViewValid").invoke(console);
                viewValid = v instanceof Boolean ? (Boolean) v : null;
            } catch (Throwable ignored) {
                // 探不到按 null 呈现
            }
            Boolean dsInFacade = null;
            try {
                Object ds = consoleClass.getMethod("getDataSource").invoke(console);
                if (ds != null) {
                    Object id = ds.getClass().getMethod("getUniqueId").invoke(ds);
                    if (id instanceof String) {
                        Object facade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                                .getMethod("getInstance", Project.class).invoke(null, project);
                        dsInFacade = facade.getClass().getMethod("findDataSource", String.class)
                                .invoke(facade, id) != null;
                    }
                }
            } catch (Throwable ignored) {
                // 探不到按 null 呈现
            }
            return new ConsoleValidity(disposed, viewValid, dsInFacade);
        } catch (Throwable e) {
            LOG.warn("[M36] probeConsoleValidity FAILED: " + e.getMessage());
            return null;
        }
    }

    /** M36.5.52/53：校准整控台 SplitPane 分隔。 */
    public static void realignSplitDivider(JSplitPane sp, boolean force) {
        ApplicationManager.getApplication().invokeLater(() -> DataSourceSelectorResultsKt.realignDividerOnce(sp, force), ModalityState.any());
        sp.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                DataSourceSelectorResultsKt.realignDividerOnce(sp, false);
            }
        });
    }

    /** M36.5.75：目标子组件填充布局。 */
    public static class FillTargetLayout implements LayoutManager {
        private final Component target;
        private int logged = 0;

        FillTargetLayout(Component target) { this.target = target; }

        @Override public void addLayoutComponent(String name, Component comp) {}
        @Override public void removeLayoutComponent(Component comp) {}
        @Override
        public Dimension preferredLayoutSize(Container parent) {
            if (parent == null) return new Dimension(0, 0);
            if (parent.getHeight() > 0) return new Dimension(parent.getWidth(), parent.getHeight());
            int sbH = 0;
            for (Component cc : parent.getComponents()) {
                if (cc instanceof JScrollBar && cc.isVisible()) sbH = cc.getPreferredSize().height;
            }
            return new Dimension(parent.getWidth(), target.getPreferredSize().height + sbH);
        }
        @Override
        public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
        @Override
        public void layoutContainer(Container parent) {
            Component sb = null;
            for (Component cc : parent.getComponents()) {
                if (cc instanceof JScrollBar && cc.isVisible()) sb = cc;
            }
            int reserved = sb != null ? Math.max(sb.getPreferredSize().height, 0) : 0;
            int avail = Math.max(parent.getHeight() - reserved, 0);
            for (Component c : parent.getComponents()) {
                if (c == target) c.setBounds(0, 0, parent.getWidth(), avail);
                else if (c == sb) c.setBounds(0, avail, parent.getWidth(), reserved);
                else c.setBounds(0, avail, parent.getWidth(), 0);
            }
            if (logged < 3 && avail > 0 && target.getHeight() != avail) {
                logged++;
                Logger.getInstance(DataSourceSelector.class).debug("[M36][OUTFILL] layout: parent=" + parent.getClass().getSimpleName()
                        + " h=" + parent.getHeight() + " target(" + target.getClass().getSimpleName() + ") → " + avail);
            }
        }
    }

    /**
     * O-64/T-RollbackIdentityParse：identity 包装产物的「整批直跑」ExecOption 副本。
     *
     * <p>平台语义（javap 243 {@code DatabaseSettings$ExecOption} + 常量组实证）：多语句执行范围由
     * {@code execInside}（光标在语句内）/ {@code execOutside}（光标在语句间）两个 int 决定，默认
     * {@code EXECUTE_INSIDE_SHOW_CHOOSER} 弹 Statements 选择层且默认只选第一条——P19 的 identity
     * 产物首语句恰是无副作用的 {@code SET … ON}，按默认执行等于 INSERT 没跑。本副本把两键都覆写为
     * {@code *_WHOLE_SCRIPT}：不弹层、整批全部执行，即「默认全选」的唯一平台杠杆。</p>
     *
     * <p>只造副本：{@code copyFrom} 基础配置后覆写两键，preparedExecOption/sharedExecOption 缓存与
     * 用户全局设置（{@code getDefaultExecOption()} 活实例）一概不碰。反射不可用 → 原样返回基础
     * 实例（执行维持现态，不劣化）。测试直接覆盖本函数（DatabaseTools 在默认 test classpath）。</p>
     */
    /**
     * 跨波兼容桥（T-SyncK2JMaster482 C5 波C1）：master 已把本函数搬为 execute 包顶层扩展函数
     * （{@link DataSourceSelectorResultsKt#installOutputHistoryFillLayout}）。{@code ui/SqlPreviewPanel.java}
     * 仍按旧成员形态调用（该文件属波D 重写面，本轮未越界改动）——波D 落地改调
     * {@code DataSourceSelectorResultsKt.installOutputHistoryFillLayout} 后本桥删除。
     */
    public static void installOutputHistoryFillLayout(Component root, Logger log) {
        DataSourceSelectorResultsKt.installOutputHistoryFillLayout(root, log);
    }

    public static Object wholeScriptExecOption(Object base) {
        try {
            Class<?> settingsClass = Class.forName("com.intellij.database.settings.DatabaseSettings");
            Object fresh = base.getClass().getDeclaredConstructor().newInstance();
            base.getClass().getMethod("copyFrom", base.getClass()).invoke(fresh, base);
            base.getClass().getField("execInside").setInt(fresh,
                    settingsClass.getField("EXECUTE_INSIDE_WHOLE_SCRIPT").getInt(null));
            try {
                base.getClass().getField("execOutside").setInt(fresh,
                        settingsClass.getField("EXECUTE_OUTSIDE_WHOLE_SCRIPT").getInt(null));
            } catch (Throwable ignored) {
                // 内层 runCatching：execOutside 缺席不致命，保留 execInside 覆写
            }
            return fresh;
        } catch (Throwable t) {
            return base;
        }
    }

    /** 读取 RunnerLayoutUi 当前全部结果内容（排除 Output/输出）。 */
    public static List<Content> resultContents(Object ui) {
        if (ui == null) return List.of();
        Object all;
        try {
            all = ui.getClass().getMethod("getContents").invoke(ui);
        } catch (Throwable ignored) {
            return List.of();
        }
        if (!(all instanceof Content[])) return List.of();
        Content[] arr = (Content[]) all;
        List<Content> result = new ArrayList<>();
        for (Content c : arr) if (!isOutputContent(c)) result.add(c);
        return result;
    }

    /**
     * T-FirstExecResultInvisible F3：RunnerLayoutUi 结果区是否有真实内容（排除 Output 输出页）。
     * 反射失败降级为「无内容」（与 {@link #resultContents} 同口径）——降级只影响折叠判定（PREVIEW
     * 多折一次），不触碰执行链。
     */
    public static boolean resultAreaHasContent(Object ui) {
        try {
            return !resultContents(ui).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * T-FirstExecResultInvisible F3：面板侧 {@link ConsoleEmbedMode#shouldCollapseDivider} 折叠判定用的
     * 结果区内容探测——共享/已备 RunnerLayoutUi 二者取一（面板不持有 ui 引用，归口本选择器探测，
     * 与 DS 侧两处折叠点用同一判据，避免两侧判定漂移）。
     */
    public static boolean currentResultAreaHasContent() {
        Object ui = sharedUi != null ? sharedUi : preparedUi;
        if (ui == null) return false;
        return resultAreaHasContent(ui);
    }

    private static boolean isOutputContent(Content c) {
        return c.getDisplayName().equalsIgnoreCase("Output") || c.getDisplayName().equalsIgnoreCase("输出");
    }

    /**
     * M36.5.22b：结果 Tab 选择判定（纯函数）。
     *
     * <p>T-ExplainFirstNative：<b>空名占位不算结果</b>——DBN 首次执行先 add 空名 Content 再异步
     * 改名/建网格（262 真机 21:06:45 实证 {@code contentAdded: }），空名 ∉ 快照集合恒真会把占位当
     * 新结果第 1 拍选中并强制换入（网格尚未构建），事后无人补换入 → 结果滞留原生控制台。</p>
     */
    public static Content pickResultTab(Content[] contents, Set<String> preExistingNames, int attempts) {
        Content newOne = null;
        for (Content c : contents) {
            if (!isOutputContent(c) && !c.getDisplayName().isBlank()
                    && !preExistingNames.contains(c.getDisplayName())) { newOne = c; break; }
        }
        if (newOne != null) return newOne;
        Content staleOne = null;
        for (Content c : contents) {
            if (!isOutputContent(c) && !c.getDisplayName().isBlank()) { staleOne = c; break; }
        }
        return (staleOne != null && attempts >= REUSE_ACCEPT_TICKS) ? staleOne : null;
    }

    /** M36.5.44：旧 Tab 复用接受的 tick 数。 */
    public static final int REUSE_ACCEPT_TICKS = 4;

    /**
     * T-DdlPostInvalidate（O-17）：执行收尾兜底失效——{@link MetadataInvalidator#invalidateAfterExecution}
     * 内部先 mayChangeMetadata 预判（命中才动作）且动作幂等，入口+收尾双调全程失效次数有界（1+1）。
     * <b>成功与失败/超时收尾都调</b>（DBX：失败的 DDL 可能已隐式提交——Oracle），把「过程内 DDL/
     * 预判漏判/中途结构变化」三类窗口关死；动作异常只记日志不吞收尾结果。
     */
    public static void runPostExecutionInvalidate(@Nullable String sqlText, Logger LOG) {
        if (sqlText == null) return;
        try {
            MetadataInvalidator.invalidateAfterExecution(sqlText);
        } catch (Throwable t) {
            LOG.warn("[T-DdlPostInvalidate] post-execution invalidation failed", t);
        }
    }

    /** M36.5：读取 last-used 数据源名（prepareConsole 静默复用；异常兜底 null）。 */
    public static String lastRunnerName(Project project) {
        return lastRunnerName(project, null);
    }

    /** T-ModuleScopedLastUsed W2：带模块键读取（模块级优先、项目级兜底）。 */
    public static String lastRunnerName(Project project, @Nullable String moduleKey) {
        return lastUsedRunner(project, moduleKey).displayName();
    }

    /** M36.5：记录 last-used 数据源名。 */
    public static void rememberRunnerName(Project project, String name) {
        rememberRunnerName(project, name, null);
    }

    /** M36.5：记录 last-used 数据源名（T-ModuleScopedLastUsed W2：可带模块键分桶）。 */
    public static void rememberRunnerName(Project project, String name, @Nullable String moduleKey) {
        rememberRunner(project, name, null, moduleKey);
    }

    /**
     * T-SI-02：last-used 记忆（UUID 身份 + 显示名，双字段）。
     * {@code dataSourceId} 来自 LocalDataSource.getUniqueId（{@link DataSourceSettings} 既有反射封装）。
     *
     * @param dataSourceId 数据源 UUID（取不到为 null）
     * @param displayName  显示名（取不到为 null）
     */
    public record LastUsedMemory(@Nullable String dataSourceId, @Nullable String displayName) {}

    /**
     * T-SI-02：记忆命中解析结果。{@code index} 命中 runner 下标；{@code ambiguous} 表示「记忆在、但同名
     * 多条无法唯一对应」——调用方据此清记忆走弹窗（不猜首条）。
     *
     * @param index     命中的 runner 下标（未命中 null）
     * @param ambiguous 同名多条歧义标记
     */
    public record LastUsedResolution(@Nullable Integer index, boolean ambiguous) {}

    /**
     * T-SI-02：last-used 记忆命中判定（纯函数，默认 test 通道可覆盖）。
     *
     * <p>命中顺序（UUID 优先，名字回退，皆不唯一即弃）：</p>
     * <ol>
     *  <li>UUID 精确命中——{@code dataSourceNameById} 取该 UUID 当前的数据源名（数据源被改名也认，
     *      这是 UUID 相对显示名的增益），再按该名在 runner 列表里唯一匹配；</li>
     *  <li>名字命中且唯一——旧键（显示名）兼容路径，升级用户无感；</li>
     *  <li>其余（未命中 / 同名多条）→ 弃记忆，调用方走弹窗重新选择。</li>
     * </ol>
     *
     * <p>{@code dataSourceNameById} 返回 null 表示该 UUID 的数据源已不存在——UUID 分量失效，退回名字判定。</p>
     */
    public static LastUsedResolution resolveLastUsedRunnerIndex(
            @NotNull LastUsedMemory memory,
            @NotNull List<String> runnerNames,
            @NotNull java.util.function.Function<String, String> dataSourceNameById) {
        String id = memory.dataSourceId() != null && !memory.dataSourceId().isBlank() ? memory.dataSourceId() : null;
        if (id != null) {
            String dsName = null;
            try {
                dsName = dataSourceNameById.apply(id);
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
            if (dsName != null && !dsName.isBlank()) {
                List<Integer> hits = new ArrayList<>();
                for (int i = 0; i < runnerNames.size(); i++) {
                    if (runnerNames.get(i).equals(dsName)) hits.add(i);
                }
                if (hits.size() == 1) return new LastUsedResolution(hits.get(0), false);
                if (hits.size() > 1) return new LastUsedResolution(null, true);
            }
        }
        String name = memory.displayName() != null && !memory.displayName().isBlank() ? memory.displayName() : null;
        if (name != null) {
            List<Integer> hits = new ArrayList<>();
            for (int i = 0; i < runnerNames.size(); i++) {
                if (runnerNames.get(i).equals(name)) hits.add(i);
            }
            if (hits.size() == 1) return new LastUsedResolution(hits.get(0), false);
            if (hits.size() > 1) return new LastUsedResolution(null, true);
        }
        return new LastUsedResolution(null, false);
    }

    /** Kotlin 默认参等价重载：moduleKey 缺省 null（既有项目级行为）。 */
    public static LastUsedMemory lastUsedRunner(Project project) {
        return lastUsedRunner(project, null);
    }

    /**
     * T-ModuleScopedLastUsed W2：读 last-used 记忆双字段（异常兜底全空）。
     *
     * <p>三层优先级的第一、二层由调用方掌握（活控制台绑定最高）；本函数负责「模块级优先、
     * 项目级兜底」：给了 {@code moduleKey} 就先读模块级键，<b>模块级未命中再读项目级</b>（不能因为
     * 模块级没有就返回空——那会让新模块的第一次预览失去兜底）。判命中层级<b>直接读模块级原键</b>
     * （{@code moduleKey} 空白时 {@code LastUsedKeys.key} 回落 base 原键，模块级与项目级同键——
     * 不先分流就会「未命中回落」判定恒真，故先按空白分流再分层读）。</p>
     */
    public static LastUsedMemory lastUsedRunner(Project project, @Nullable String moduleKey) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            if (isNullOrBlank(moduleKey)) {
                return new LastUsedMemory(pc.getValue(LastUsedKeys.dataSourceIdKey(null)),
                        pc.getValue(LastUsedKeys.displayNameKey(null)));
            }
            LastUsedMemory scoped = new LastUsedMemory(pc.getValue(LastUsedKeys.dataSourceIdKey(moduleKey)),
                    pc.getValue(LastUsedKeys.displayNameKey(moduleKey)));
            if (scoped.dataSourceId() != null || scoped.displayName() != null) return scoped;
            return new LastUsedMemory(pc.getValue(LastUsedKeys.dataSourceIdKey(null)),
                    pc.getValue(LastUsedKeys.displayNameKey(null)));
        } catch (Throwable ignored) {
            return new LastUsedMemory(null, null);
        }
    }

    /**
     * T-ModuleScopedLastUsed W1（2026-10-24 用户问「上次使用的数据源是否按模块隔离更合适」）：
     * last-used 记忆的<b>键构造</b>（纯函数，headless 直测）。
     *
     * <p>为什么按模块分桶：本项目此前并存两套取源记忆且粒度不一致——项目级 last-used（喂指示行
     * 灰字与静默复用）vs 模块级 {@code ModuleDataSourceBindingStore}
     * （{@code mp-sql-visualizer-datasource.xml}，<b>只喂方言决策</b>）。于是「在 jpa_demo 模块选了
     * jpa_demo@localhost，切到 mp_demo 模块，灰字仍显示同一个源」——多模块工程的源本就按模块
     * 分（demo 仓 mp_demo / jpa_demo / mpj_demo 各连各库），项目级单一记忆必然错配。这也是
     * T-DialectInferPollution W8~W12 那批跨模块错源的根因之一。</p>
     *
     * <p><b>硬约束</b>：{@code moduleKey} 为 null/blank 时<b>必须回落项目级键</b>（返回 {@code LAST_RUNNER_KEY} 族
     * 原键）——纯 {@code .sql} 文件、非 Java 源等入口没有模块上下文，若按模块硬隔离会退化成「每次
     * 都未绑定」，比改前更糟。</p>
     */
    public static final class LastUsedKeys {

        /** 模块级后缀分隔符：模块键是 {@code canonicalKey}（模块身份，非路径），拼上后仍是稳定键。 */
        private static final String MODULE_SEPARATOR = ".";

        private LastUsedKeys() {
        }

        /** 模块级记忆键；{@code moduleKey} 为空时返回 {@code base} 本身（即项目级原键，向后兼容）。 */
        static String key(String base, @Nullable String moduleKey) {
            return isNullOrBlank(moduleKey) ? base : base + MODULE_SEPARATOR + moduleKey;
        }

        public static String displayNameKey(@Nullable String moduleKey) {
            return key(LAST_RUNNER_KEY, moduleKey);
        }

        public static String dataSourceIdKey(@Nullable String moduleKey) {
            return key(LAST_RUNNER_ID_KEY, moduleKey);
        }
    }

    /** Kotlin 默认参等价重载：moduleKey 缺省 null（既有项目级行为）。 */
    public static void rememberRunner(Project project, String name, @Nullable String dataSourceId) {
        rememberRunner(project, name, dataSourceId, null);
    }

    /**
     * T-SI-02：写 last-used 记忆。{@code dataSourceId} 取不到（弹窗路径尚无控制台）时<b>清掉身份键</b>
     * ——留着上一轮的 UUID 会指认另一个数据源（身份说谎比没有身份更糟）。
     *
     * <p>T-ModuleScopedLastUsed W2：给了 {@code moduleKey} 时<b>同时写模块级与项目级</b>——模块级承载
     * 「本模块上次用哪个」，项目级作为无模块上下文入口（纯 {@code .sql} 等）的兜底，两者不冲突。
     * 但模块级命中优先，故读取侧不会串味。</p>
     */
    public static void rememberRunner(Project project, String name, @Nullable String dataSourceId,
                                      @Nullable String moduleKey) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            writeLastUsed(pc, null, name, dataSourceId);
            if (!isNullOrBlank(moduleKey)) {
                writeLastUsed(pc, moduleKey, name, dataSourceId);
                registerLastUsedModuleKey(project, moduleKey);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 单层写入（键构造统一走 {@link LastUsedKeys}；双写两层由 {@link #rememberRunner(Project, String, String, String)} 编排）。 */
    private static void writeLastUsed(com.intellij.ide.util.PropertiesComponent pc, @Nullable String moduleKey,
                                      String name, @Nullable String dataSourceId) {
        pc.setValue(LastUsedKeys.displayNameKey(moduleKey), name);
        if (dataSourceId == null || dataSourceId.isBlank()) pc.unsetValue(LastUsedKeys.dataSourceIdKey(moduleKey));
        else pc.setValue(LastUsedKeys.dataSourceIdKey(moduleKey), dataSourceId);
    }

    /** Kotlin 默认参等价重载：moduleKey 缺省 null（既有「清项目级」行为）。 */
    public static void forgetLastRunner(Project project) {
        forgetLastRunner(project, null);
    }

    /**
     * T-SI-02：清 last-used 记忆（两个键一起清，避免半截身份残留）。
     *
     * <p>T-ModuleScopedLastUsed W2：给了 {@code moduleKey} 只清该模块那一层（不牵连其它模块，也不动
     * 项目级兜底——用户只是在某个模块里换了源，不该把全局兜底一起抹掉）；未给则清项目级。
     * 未指定模块＝既有语义「清项目级」；同时清掉所有模块级分桶（W4 的「清空学习」
     * 入口要求彻底清干净，否则用户点了清空、灰字仍显示旧源）。</p>
     */
    public static void forgetLastRunner(Project project, @Nullable String moduleKey) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            clearLastUsed(pc, moduleKey);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 单层清理（键构造统一走 {@link LastUsedKeys}）。 */
    private static void clearLastUsed(com.intellij.ide.util.PropertiesComponent pc, @Nullable String moduleKey) {
        pc.unsetValue(LastUsedKeys.displayNameKey(moduleKey));
        pc.unsetValue(LastUsedKeys.dataSourceIdKey(moduleKey));
    }

    /**
     * T-ModuleScopedLastUsed W2：清<b>全部</b>层级（项目级 + 已登记的模块分桶）。
     *
     * <p><b>不能用通配扫描</b>：{@code PropertiesComponent} 的公开 API 只有 {@code unsetValue/getValue/setValue}
     * （{@code javap} 实证，无 {@code getAllKeys}），拿不到「有哪些键」。故模块键清单必须<b>由插件自己记</b>：
     * 每次写模块级记忆时把模块键登记进 {@link #_LAST_USED_MODULE_KEYS_KEY}（逗号分隔，去重），
     * 清空时按清单逐个 {@code unsetValue}。</p>
     */
    public static void forgetAllLastUsed(Project project) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            clearLastUsed(pc, null);
            for (String moduleKey : registeredLastUsedModuleKeys(project)) {
                clearLastUsed(pc, moduleKey);
            }
            pc.unsetValue(_LAST_USED_MODULE_KEYS_KEY);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 已登记过的模块键（写模块级记忆时顺带登记，供 {@link #forgetAllLastUsed} 精确清理）。 */
    public static List<String> registeredLastUsedModuleKeys(Project project) {
        try {
            String raw = com.intellij.ide.util.PropertiesComponent.getInstance(project)
                    .getValue(_LAST_USED_MODULE_KEYS_KEY);
            if (raw == null) return List.of();
            List<String> out = new ArrayList<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) out.add(trimmed);
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static void registerLastUsedModuleKey(Project project, String moduleKey) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            List<String> existing = registeredLastUsedModuleKeys(project);
            if (existing.contains(moduleKey)) return;
            List<String> merged = new ArrayList<>(existing);
            merged.add(moduleKey);
            pc.setValue(_LAST_USED_MODULE_KEYS_KEY, String.join(",", merged));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** T-SI-02：从已建控制台取数据源 UUID（复用 {@link DataSourceSettings} 既有反射封装；取不到 null）。 */
    @Nullable
    public static String consoleDataSourceId(@Nullable Object console) {
        try {
            if (console == null) return null;
            Object ds = console.getClass().getMethod("getDataSource").invoke(console);
            if (ds == null) return null;
            return DataSourceSettings.getUniqueId(ds);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Kotlin 默认参等价重载：allDataSources 缺省 false、currentSession 缺省 null（既有 attach 版形态）。 */
    public static List<Object> getAttachDataSourceRunners(PsiFile psiFile, String sql, com.intellij.util.Consumer<Object> sessionConsumer) {
        return getAttachDataSourceRunners(psiFile, sql, sessionConsumer, false, null);
    }

    /**
     * 反射调用 DatabaseRunners.getAttachDataSourceRunners 获取原生 Runner 列表。
     * {@code sessionConsumer} 在选中 runner 执行后收到 DatabaseSession。
     *
     * <p>T-ExplainSwitchNative W8（2026-10-24 用户实测「点击切换数据源只有一个数据源？当前可是
     * 配置了很多」）：<b>用户主动切换</b>时优先调平台切换专用入口 {@code getSwitchDataSourceRunners}，
     * 失败（含签名/无会话）则回退 attach 版。</p>
     *
     * <p>⚠️ W8-hotfix（同日）：{@code getSwitchDataSourceRunners} 的签名是
     * <b>{@code (PsiFile, String, DatabaseSession, Consumer)} 四参</b>（javap 实证），比 attach 版多一个
     * 「当前会话」——首版按三参反射调用必然 {@code NoSuchMethodException}，被 runCatching 吞成空列表
     * → {@code runners=0} → 用户看到「没有与该 SQL 方言匹配的已配置数据源」红条（切换数据源点了就报错）。
     * 现按真实签名调用；{@code currentSession} 为 null（预览尚未建会话，切换入口的常态）时<b>直接用
     * attach 版</b>——这正是切换入口此前一直正常工作的形态。</p>
     *
     * @param allDataSources 用户主动切换时为 true（尝试切换专用入口）
     * @param currentSession 当前已 attach 的会话（无则走 attach 版）
     */
    public static List<Object> getAttachDataSourceRunners(
            PsiFile psiFile,
            String sql,
            com.intellij.util.Consumer<Object> sessionConsumer,
            boolean allDataSources,
            @Nullable Object currentSession) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        List<Object> attach;
        try {
            Class<?> clazz = Class.forName(RUNNERS_CLASS);
            Method method = clazz.getMethod("getAttachDataSourceRunners", PsiFile.class, String.class, com.intellij.util.Consumer.class);
            Object result = method.invoke(null, psiFile, sql, sessionConsumer);
            List<Object> list = new ArrayList<>();
            if (result instanceof List) {
                for (Object o : (List<?>) result) if (o != null) list.add(o);
            }
            attach = list;
        } catch (Throwable e) {
            LOG.warn("[M36] getAttachDataSourceRunners reflection FAILED: " + e, e);
            attach = List.of();
        }
        if (!allDataSources || currentSession == null) return attach;
        // 切换专用入口：四参（多 currentSession）；任何失败都回退 attach 版，绝不返回空。
        List<Object> switched;
        try {
            Class<?> clazz = Class.forName(RUNNERS_CLASS);
            Method method = clazz.getMethod(
                    "getSwitchDataSourceRunners",
                    PsiFile.class,
                    String.class,
                    Class.forName(SESSION_CLASS),
                    com.intellij.util.Consumer.class);
            Object result = method.invoke(null, psiFile, sql, currentSession, sessionConsumer);
            List<Object> list = new ArrayList<>();
            if (result instanceof List) {
                for (Object o : (List<?>) result) if (o != null) list.add(o);
            }
            switched = list;
        } catch (Throwable e) {
            LOG.warn("[M36] getSwitchDataSourceRunners reflection FAILED (fallback to attach): " + e, e);
            switched = List.of();
        }
        return switched.isEmpty() ? attach : switched;
    }

    /**
     * W8 观测缝（用户 2026-10-24「点击切换数据源只有一个数据源？当前可是配置了很多」）：
     * 平台 Runner 枚举走 {@code DbImplUtil.getAppropriateDataSourcesForExec} →
     * {@code DbSqlUtilCore.getAppropriateDataSources} → {@code .filter(CAN_CONNECT_TO)}（反编译实证），
     * 因此<b>当前不可连接的源不会出现在弹层里</b>（未连过/驱动未就绪）。插件侧无法凭空补出
     * 合法的平台 Runner（{@code runInDataSource} 是直接建会话、不产 Runner；私有
     * {@code createDataSourceRunner} 不可用），故本函数只做<b>归因打点</b>，供 UI 在「可选源少于在籍源」
     * 时给出可行动说明（打开 Database 工具窗口连一次即可恢复可选）。
     *
     * @return 在籍源总数（平台不可用时 0）
     */
    public static int registeredDataSourceCount(Project project) {
        try {
            return DataSourceSettings.getAllDataSources(project).size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 反射获取 Runner 显示名称。 */
    public static String getRunnerName(Object runner) {
        try {
            Method method = runner.getClass().getMethod("getDisplayName");
            method.setAccessible(true);
            Object r = method.invoke(runner);
            return r instanceof String ? (String) r : "Unknown";
        } catch (Throwable ignored) {
            return "Unknown";
        }
    }

    /** 反射获取 Runner 图标。 */
    private static javax.swing.Icon getRunnerIcon(Object runner) {
        try {
            Method method = runner.getClass().getMethod("getIcon");
            method.setAccessible(true);
            Object r = method.invoke(runner);
            return r instanceof javax.swing.Icon ? (javax.swing.Icon) r : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射获取 Runner.shouldBeChosenIfAlone。 */
    public static boolean shouldBeChosenIfAlone(Object runner) {
        try {
            Method method = runner.getClass().getMethod("shouldBeChosenIfAlone");
            method.setAccessible(true);
            Object r = method.invoke(runner);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 弹窗展示 Runner 列表。 */
    public static void showRunnerPopup(Project project, List<Object> runners, Consumer<Object> callback) {
        List<RunnerItem> items = new ArrayList<>();
        for (Object r : runners) items.add(new RunnerItem(r, getRunnerName(r), getRunnerIcon(r)));
        // T-PreviewExecDs-02 F5：多项目同开时标题带当前项目名——runner 枚举严格按当前项目
        // facade（F1 修复后），标题注明归属避免「这是哪来的数据源」困惑；单项目不加噪音。
        boolean multiProject = ProjectManager.getInstance().getOpenProjects().length > 1;
        String title = multiProject
                ? MpSqlVisualizerBundle.message("execute.datasource.select.in", project.getName())
                : MpSqlVisualizerBundle.message("execute.datasource.select");
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createListPopup(createRunnerPopupStep(project, runners, callback))
                .showInFocusCenter();
    }

    static com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem> createRunnerPopupStep(
            Project project, List<Object> runners, Consumer<Object> callback) {
        List<RunnerItem> items = new ArrayList<>();
        for (Object r : runners) items.add(new RunnerItem(r, getRunnerName(r), getRunnerIcon(r)));
        boolean multiProject = ProjectManager.getInstance().getOpenProjects().length > 1;
        String title = multiProject
                ? MpSqlVisualizerBundle.message("execute.datasource.select.in", project.getName())
                : MpSqlVisualizerBundle.message("execute.datasource.select");
        return new com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem>(title, items) {
            private boolean choiceAccepted = false;
            private boolean delivered = false;

            private void deliver(Object runner) {
                if (delivered) return;
                delivered = true;
                callback.accept(runner);
            }

            @Override
            public String getTextFor(RunnerItem value) { return value.name; }

            @Override
            public javax.swing.Icon getIconFor(RunnerItem value) { return value.icon; }

            @Override
            public com.intellij.openapi.ui.popup.PopupStep<?> onChosen(RunnerItem selectedValue, boolean finalChoice) {
                Logger.getInstance(DataSourceSelector.class).info("[M36] popup onChosen: "
                        + (selectedValue != null ? selectedValue.name : "null"));
                // 243 禁止在 onChosen 内开模态确认；runner/session 续链须在弹窗关闭后启动。
                choiceAccepted = true;
                return doFinalStep(() -> deliver(selectedValue != null ? selectedValue.runner : null));
            }

            @Override
            public void canceled() {
                // 243 的已显示 popup 在选择后 dispose 也经 cancel；不是用户按 Esc。
                if (!choiceAccepted) deliver(null);
            }
        };
    }

    /** 弹窗列表项（Runner 包装）。 */
    public static final class RunnerItem {
        final Object runner;
        final String name;
        final javax.swing.Icon icon;
        RunnerItem(Object runner, String name, javax.swing.Icon icon) {
            this.runner = runner;
            this.name = name;
            this.icon = icon;
        }
    }

    /** 临时 PsiFile 持有。 */
    public static final class TempPsiFile {
        final VirtualFile vf;
        final PsiFile psi;
        TempPsiFile(VirtualFile vf, PsiFile psi) { this.vf = vf; this.psi = psi; }
    }

    /** M36.5.7：编辑器块补 UiDataProvider（原生 ▶ 执行动作数据上下文）。 */
    public static final class EditorDataPanel extends JBPanel<EditorDataPanel> implements UiDataProvider {
        private final Project project;
        private final Editor editorForData;
        private final VirtualFile vfForData;
        private final Object consoleForData;
        private final String clientKeyName;
        private final PsiFile[] psiForData;

        EditorDataPanel(Project project, Editor editorForData, VirtualFile vfForData, Object consoleForData, String clientKeyName, PsiFile[] psiForData) {
            super(new BorderLayout());
            this.project = project;
            this.editorForData = editorForData;
            this.vfForData = vfForData;
            this.consoleForData = consoleForData;
            this.clientKeyName = clientKeyName;
            this.psiForData = psiForData;
        }

        @Override
        public void uiDataSnapshot(@NotNull DataSink sink) {
            sink.set(CommonDataKeys.PROJECT, project);
            sink.set(CommonDataKeys.EDITOR, editorForData);
            sink.set(CommonDataKeys.VIRTUAL_FILE, vfForData);
            sink.lazy(CommonDataKeys.PSI_FILE, () -> {
                if (psiForData[0] != null && psiForData[0].isValid()) return psiForData[0];
                try {
                    return ReadAction.compute(() -> {
                        PsiFile f = PsiManager.getInstance(project).findFile(vfForData);
                        psiForData[0] = f;
                        return f;
                    });
                } catch (Throwable ignored) {
                    return null;
                }
            });
            if (clientKeyName != null) {
                try {
                    DataKey<Object> ck = (DataKey<Object>) Class.forName(
                            "com.intellij.database.console.client.SessionClientHolder")
                            .getField("CLIENT_KEY").get(null);
                    sink.set(ck, (Object) consoleForData);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /**
     * T-PreviewExecDs-02 F1：消费 exec 临时文件 vf+psi 缓存——可用（归属当前项目且 vf 有效）
     * 返回配对，否则清缓存返回 null（走冷路径重建）。供跨项目隔离测试直测。
     * 跨项目泄漏实锤见开发计划 §2.2：旧实现只查 vf.isValid，在 B 项目执行时返回 A 项目的
     * vf+psi → runner 按 A 项目 facade 枚举数据源 → 弹窗显示另一项目数据源 → console 因
     * facade 找不到该数据源被 getActiveConsoles 清扫 → "parent has already been disposed"。
     */
    @Nullable
    public static TempPsiFile consumeCachedTempPsiFile(Project project) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        TempPsiFile cached = cachedTempPsiFile;
        if (cached == null) return null;
        if (isCacheUsable(project, cached.vf)) {
            LOG.info("[M36] exec psi cache hit (no VFS refresh): " + cached.vf.getName());
            return cached;
        }
        // 跨项目/失效命中——弃缓存，禁止把别项目的 vf+psi 当当前项目用
        if (cachedTempPsiProjectName != null && !cachedTempPsiProjectName.equals(project.getName())) {
            LOG.warn("[M36] exec psi cache REJECTED across project (cached=" + cachedTempPsiProjectName
                    + ", current=" + project.getName() + "), rebuild");
        }
        cachedTempPsiFile = null;
        cachedTempPsiProjectName = null;
        return null;
    }

    /**
     * T-PreviewExecDs-02 F1：exec 临时文件 vf+psi 缓存是否对当前项目可用——既要 vf 有效，
     * 也要 vf 归属当前项目（basePath 前缀）。跨项目泄漏根因修复的判定纯函数（表驱动单测）。
     */
    public static boolean isCacheUsable(Project project, VirtualFile vf) {
        if (!vf.isValid()) return false;
        String base = project.getBasePath();
        if (base == null) return false;
        String nBase = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(base).replaceAll("/+$", "");
        String nPath = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(vf.getPath());
        return nPath.startsWith(nBase + "/");
    }

    /**
     * T-EC-03：执行文件单写方锁——BGT 预热（prewarmExecTempFileInner）与 EDT 冷路径
     *（createTempPsiFile）两条链并发写同一 mpsql-exec.sql 的串行化。临界区毫秒级；
     * 两个调用点均在普通线程语境（非 WriteAction 内），持本锁再取平台写锁无环。
     */
    public static final Object execFileWriteLock = new Object();

    /**
     * T-EC-02 的裸写守卫（canFallBackToRawWrite）已由 T-ExecDocSync3 整体退役——真机日志实证其
     * 判据不可靠（EDT 忙时 getCachedDocument 恒 null，而排队中的写任务随后会建出 document），
     * 现策略见 {@link #projectExecFile} 内注释：VFS 已有该文件即绝不裸写。
     */

    /**
     * T-MysqlGStrip T-MG-02：提交链总口的 {@code \G} 剥离决策（{@link #chooseAndPrepare} 新建控制台链与
     * {@link #executeOnConsole} 已备控制台链共用同一入口）。
     *
     * @param sqlText       原始 SQL 文本
     * @param dataSourceUrl 控制台数据源 JDBC URL（方言判定输入）；null = 方言不可得 →
     *                      {@link MysqlClientGSuffix#stripForDialect} 按 MySQL 处理（默认剥离）
     * @return 两态结果——{@link MysqlClientGSuffix.Result#submitText} 进 exec 文件/控制台文档，
     *         {@link MysqlClientGSuffix.Result#original} 留给历史链（历史记原文、提交文本剥离）
     *
     * <p>public 供 headless 用例直测（沙盒默认 test 任务无 Database 插件，
     * chooseAndPrepare/executeOnConsole 在默认 test 任务不可达——ExecTempFileFixTest 同款口径）。</p>
     */
    public static MysqlClientGSuffix.Result stripGSuffixForSubmit(String sqlText, @Nullable String dataSourceUrl) {
        return MysqlClientGSuffix.stripForDialect(sqlText, MysqlClientGSuffix.dialectFromUrl(dataSourceUrl));
    }

    /**
     * T-EC-01：document 驱动写 + <b>同临界区落盘</b>——{@code setText → commitDocument → saveDocument}，
     * 磁盘=VFS=内存三方在同一写临界区内收口，不留「已修改未保存」窗口（该窗口内任何绕过
     * document 的磁盘改写都触发 File Cache Conflict 弹窗，弹窗期间 invokeLater 链冻结 →
     * 执行链卡死）；落盘不再依赖 IDE 自动保存追赶。
     *
     * <p><b>仅 EDT 调用</b>（BGT 统一经 {@link #dispatchDocWriteOnEdt} 派发）——T-ExecDocSync 关键发现
     *（javap + 沙盒堆栈双实证）：262 平台文件 document（非 FREE_THREADED）的 setText 包
     * CommandProcessor.executeCommand，BGT 直调硬抛 EDT-only 断言（RuntimeExceptionWithAttachments）。
     * 旧实现 BGT 预热走 document 分支<b>每次必炸</b>，runCatching 吞掉后静默掉进裸写兜底——
     * 这正是真机 File Cache Conflict 链的本体（非低概率竞态）。</p>
     *
     * <p>document 未加载（fileDoc == null，即无冲突面）走 setBinaryContent（VFS+磁盘同步）；
     * text 相同但 document 仍 modified（用户改后还原等）时同样 commit+save 收敛。</p>
     */
    public static void writeVfTextViaDocument(Project project, VirtualFile vf, String text) {
        // T-UiFixGate9：生命周期守卫——项目已 dispose（含测试框架的 temporarily-disposed 窗口）后一律放弃写。
        // 半途写入会留下「已修改未保存」脏文档，后续任一 VFS refresh 命中 MemoryDiskConflictResolver →
        // EDT 延迟冲突处理杀掉无辜测试。预热是投机性写入，重跑无意义。
        if (project.isDisposed()) {
            Logger.getInstance(DataSourceSelector.class)
                    .info("[M36] exec doc write abandoned (project disposed, T-UiFixGate9): " + vf.getName());
            return;
        }
        // T-PreviewFast P4：程序性写入整段开闸——文档变更事件同步派发给自动落盘监听，
        // 闸门开着即跳过（内容由本函数自己落盘，无需 1.5s 后再写一遍）。
        // T-UiFixGate9：写体内自包 WriteAction（平台可重入，已持写动作的调用方形态不变）——
        // 零窗口直写分支需要 VFS 写权限，原「等内容路径不需要写权限」的裸调约定随之失效。
        WriteAction.compute(() -> {
            ExecDocAutosaveGate.suppressDuring(() -> {
                FileDocumentManager fdm = FileDocumentManager.getInstance();
                // T-UiFixGate9：改 getCachedDocument（**不主动建 document**）+ 双路写。
                // 旧实现 getDocument 会主动建 document，随后 setText→saveDocument 之间存在
                // ~30ms「已修改未保存」窗口——该窗口对异步 refresh 线程可见，边界迟到的 VFS 会话
                // 恰在此窗口命中，冲突收集后在 EDT 下一拍抛出、杀掉正在收尾的测试。零窗口直写不建
                // document，写后任一时刻 resolver 看到的都是干净态。
                // 编辑器打开（真机控制台正看着这份内容，需视图同步）或文档已脏（用户键入未存，
                // 外部覆盖须走既有冲突语义）仍走 document 路径，T-EC-01 同临界区落盘语义不变。
                Document fileDoc = fdm.getCachedDocument(vf);
                if (fileDoc == null || (!isExecDocOpenInEditor(vf) && !fdm.isDocumentUnsaved(fileDoc))) {
                    try {
                        vf.setBinaryContent(text.getBytes(StandardCharsets.UTF_8));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (fileDoc != null) {
                        // 缓存 doc 是干净态：同步从盘重载对齐新内容（平台对「外部改盘+干净 doc」
                        // 的既有语义），不产生 modified 位
                        fdm.reloadFromDisk(fileDoc);
                    }
                } else {
                    if (!fileDoc.getText().equals(text)) {
                        fileDoc.setText(text);
                    }
                    // setText 的 write command 结束时自动 commit；显式 commitDocument 双保险
                    //（同步后同 tick 内 findFile 的 PSI 立即对齐，M36.5.20 语义保留）
                    PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                    // T-EC-01 核心：同临界区落盘——磁盘即时与内存一致，document 不滞留 modified 态
                    // T-PreviewFast P5：落盘前先判「已修改未保存」——内容本就一致时跳过磁盘写
                    if (fdm.isDocumentUnsaved(fileDoc)) {
                        fdm.saveDocument(fileDoc);
                    }
                }
                lastWrittenExecContent.put(vf.getPath(), new ExecWrittenSnapshot(text, vf.getTimeStamp()));
                return null;
            });
            return null;
        });
    }

    /** BGT 派 EDT document 写的有界等待预算（毫秒）——DialectPreMapping 同款取值；EDT 病态冻结时超时放行走守卫降级。 */
    public static final long EDT_EXEC_DOC_SYNC_WAIT_MS = 2000L;

    /** T-DialectInferPollution W11：排队执行意图的<b>自有结算</b>超时（毫秒）——占门链 60s 逃生太长，
     *  排队者 15s 即 BLOCKED 解除加载态（门不动，owner 链照常），用户重试即可。 */
    public static final long PENDING_EXECUTE_TIMEOUT_MS = 15_000L;

    /** T-DialectInferPollution W11：排队意图超时文案（沿用准备超时键，语义一致：未提交 SQL）。 */
    public static String pendingExecuteTimeoutMessage() {
        return MpSqlVisualizerBundle.message("execute.prepare.timeout");
    }

    /** document 写单次尝试：WriteAction 内 {@link #writeVfTextViaDocument}；失败原因必达日志（旧 getOrDefault 吞 Throwable 的教训）。 */
    public static boolean docWriteOnce(Project project, VirtualFile vf, String sql) {
        try {
            if (execWriteActionFailureInjection) {
                throw new IllegalStateException("injected exec WriteAction failure (T-ExecDocSync test seam)");
            }
            WriteAction.run(() -> writeVfTextViaDocument(project, vf, sql));
            return true;
        } catch (Throwable e) {
            Logger.getInstance(DataSourceSelector.class)
                    .warn("[M36] exec doc write FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * T-EC-04：VFS 缺条目裸写路径的防御性收口——raw write 后由调用方 refresh 得到 vf，
     * 此处探测 document（getCachedDocument，不主动创建）或 VFS 内容与 sql 的分叉，
     * 命中则经 {@link #dispatchDocWriteOnEdt} 补一次 document 驱动同步（T-EC-01 同款落盘收口，
     * 写侧统一派 EDT）。正常流程恒为 no-op（磁盘刚写入 == sql）；防御 VFS 陈旧条目/
     * 裸写与 refresh 之间的外部改写残留。
     *
     * <p>T-PreviewChainNoise W1：分叉探测<b>按线程分流</b>（T-DevAcceptFix2 ④ 同款定例）。
     * 此前无条件 {@code vf.inputStream.readBytes()} 读盘，经 {@code createTempPsiFile} 的 EDT 冷路径
     * （点执行计划 → prepareConsole → 本链）每次都命中平台「Slow operations are prohibited
     * on EDT」断言（2026-10-23 用户真机日志 ×2 实证；同文件 {@code execFileContentMatches} 早在
     * T-DevAcceptFix2 ④ 就为同一调用链删过同款读盘，本函数漏回填）。分流判据：</p>
     * <ul>
     *  <li>有 document：只看内存态（{@code execFileContentMatches} 同口径——文档是权威视图），
     *      额外认「内容已相等但未保存」为分叉（F4 收敛形态：doc=新 SQL、磁盘=旧 SQL）；</li>
     *  <li>无 document + EDT：直接判不分叉（无 document 即不存在 File Cache Conflict 条件
     *      ——冲突需「已加载且已修改」的文档；且 EDT 冷路径刚由本线程写完文件，无外部改写窗口）；</li>
     *  <li>无 document + BGT：读盘比较，保留原防御面（SlowOperations 断言仅 EDT 生效）。</li>
     * </ul>
     *
     * @return true = 执行了修复同步（正常流程 false）
     */
    public static boolean syncExecVfContentIfNeeded(Project project, VirtualFile vf, String sql) throws Throwable {
        boolean allowDiskRead = !ApplicationManager.getApplication().isDispatchThread();
        if (!probeExecVfDivergence(vf, sql, allowDiskRead)) return false;
        return DataSourceSelectorResultsKt.dispatchDocWriteOnEdt(project, vf, sql);
    }

    /**
     * T-PreviewChainNoise W1：exec 文件分叉探测纯判定（{@link #syncExecVfContentIfNeeded} 的探测段
     * 抽出，public 供表驱动测试）。三态见 {@link #syncExecVfContentIfNeeded} Javadoc；{@code allowDiskRead}
     * 由调用方按「当前是否 EDT」给出（生产= {@code !isDispatchThread}，测试可显式注入）。
     *
     * <p>document 读取包 ReadAction（BGT 无隐式读；EDT 直调合法）。<b>有 document 分支绝不读盘</b>
     * ——这正是本抽取的可测不变式（见 ExecVfDivergenceProbeTest「文档在场时盘上再变也不读」用例）。</p>
     */
    public static boolean probeExecVfDivergence(VirtualFile vf, String sql, boolean allowDiskRead) throws Throwable {
        FileDocumentManager fdm = FileDocumentManager.getInstance();
        Document doc = ReadAction.compute(
                (com.intellij.openapi.util.ThrowableComputable<Document, Throwable>) () -> fdm.getCachedDocument(vf));
        if (doc != null) {
            return !doc.getText().equals(sql) || fdm.isDocumentUnsaved(doc);
        }
        if (allowDiskRead) {
            return ReadAction.compute(
                    (com.intellij.openapi.util.ThrowableComputable<Boolean, Throwable>) () ->
                            !new String(vf.getInputStream().readAllBytes(), StandardCharsets.UTF_8).equals(sql));
        }
        return false;
    }

    // ------------------------------------------------------------------
    // T-ExecDocSync2 F5/F6：执行文档防抖自动落盘 + 冲突探针（第四轮）
    // ------------------------------------------------------------------

    /**
     * exec 文件 VFS 路径匹配（F5 自动落盘 / F6 探针共用过滤器；VFS 路径恒 '/' 分隔）。
     * public 供表驱动测试。
     */
    public static boolean isExecVfPath(String path) {
        return path.endsWith("/" + cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME + "/exec/mpsql-exec.sql");
    }

    /** 防抖窗（毫秒）——连击合并，窗内 modified-unsaved 暴露面上界。 */
    private static final long EXEC_DOC_AUTOSAVE_DELAY_MS = 1500L;

    /** F5 防抖调度器（daemon，不阻 IDE 退出；任务本身极轻——路径过滤后仅换句柄）。 */
    private static final java.util.concurrent.ScheduledExecutorService execDocAutosaveExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mpv-exec-doc-autosave");
                t.setDaemon(true);
                return t;
            });

    /** F5 待执行的防抖任务（新变更到达即顶掉旧任务）。 */
    private static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> execDocAutosavePending =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * T-PS4-11 快判缓存：上次命中 exec 路径的 document（<b>弱引用</b>——防强引用滞留已关闭文档，
     * GC 后 get()==null 自然退回原链探测）。companion 监听是应用级的，IDE 全局每次文档变更
     * 都会进 {@link #scheduleExecDocAutosave}；exec 文档连续变更（用户在控制台键入）是唯一需要跟进
     * 落盘的形态，其余文档每次都白付 getFile + endsWith。identity 比对（==）而非 equals，
     * document 换实例（文件关闭重开）即视为未命中。
     */
    private static final java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<Document>> lastExecDocRef =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * F6 探针已安装项目集（连接随项目 disposable 自动断开，此处只防重复安装）。
     *
     * <p>T-PS5-08：改<b>弱键集</b>（{@code WeakHashMap} 键弱引用：项目唯一强引用消失即可回收）——此前强引用
     * 集把项目对象钉到显式 {@code remove}（走 {@link #hookProjectClose} 的项目 Disposer，但该函数在
     * {@code hookProjectsClose} 已登记时会提前 return、不再登记第二次项目级 Disposer，留下不可回收窗口）。
     * 去重/判空语义不变（条目只用于「是否已安装」判定）。</p>
     */
    public static final Set<Project> conflictProbeProjects =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Project, Boolean>()));

    /** T-PS5-08 测试缝：探针项目集（弱引用可达性断言用；生产链路无调用点）。 */
    public static Set<Project> conflictProbeProjectsForTest() {
        return conflictProbeProjects;
    }

    /**
     * F5：应用级 document 变更监听（惰性安装一次）——exec 文档每次变更（用户在控制台手改/
     * 插件 setText）后 1.5s 防抖自动落盘。动机：console 编辑器文档即 exec 文件 document
     * （attachConsole 挂载点），用户手改滞留「已修改未保存」是第四轮冲突弹窗「内存中更改」
     * 一半的唯一插件拦不到的源头；帧失活自动保存最快也要切帧才触发，防抖落盘把暴露面压到
     * 防抖窗内。落盘方向恒 doc→disk（saveDocument），不回读不改写用户内容。
     */
    private static void installExecDocAutosaveListener() {
        // T-PS4-11：改两参重载 addDocumentListener(listener, disposable)，挂 [ExecDocAutosaveHost]
        // （应用级轻服务 = Disposable parent）——此前单参裸装、装后永不卸载，插件动态卸载
        // （DynamicInstallSupport → 平台 unloadPlugin）后监听残活并钉住旧 classloader，正是
        // 动态卸载屏障要杜绝的形态。挂靠定案依据见 [ExecDocAutosaveHost] KDoc。
        ExecDocAutosaveHost.getInstance().ensureListenerInstalled(
                new com.intellij.openapi.editor.event.DocumentListener() {
                    @Override
                    public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) {
                        scheduleExecDocAutosave(e.getDocument());
                    }
                });
    }

    private static void scheduleExecDocAutosave(Document doc) {
        // T-PreviewFast P4：程序性写入不排自动落盘——闸门开着说明本次变更来自插件写入
        //（写入方自己已落盘），防抖落盘只为用户手改兜底。保持最先判（闸门语义先于快判，
        // 程序性写入高频路径不因快判命中而漏抑制）
        if (ExecDocAutosaveGate.isSuppressed()) return;
        // T-PS4-11 快判：上次命中的 document 直接身份比对短路（免 getFile + endsWith）；
        // 未命中走原链探测回退，确认命中后更新缓存。弱引用被 GC 后 get()==null 视为未命中。
        java.lang.ref.WeakReference<Document> cachedRef = lastExecDocRef.get();
        if (cachedRef == null || cachedRef.get() != doc) {
            VirtualFile vf = null;
            try {
                vf = FileDocumentManager.getInstance().getFile(doc);
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
            if (vf == null || !isExecVfPath(vf.getPath())) return;
            lastExecDocRef.set(new java.lang.ref.WeakReference<>(doc));
        }
        java.util.concurrent.Future<?> prev = execDocAutosavePending.getAndSet(
                execDocAutosaveExecutor.schedule(() -> flushExecDocAutosave(doc),
                        EXEC_DOC_AUTOSAVE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
        if (prev != null) prev.cancel(false);
    }

    private static void flushExecDocAutosave(Document doc) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (!autosaveExecDocNow(doc)) return;
                Logger.getInstance(DataSourceSelector.class)
                        .info("[M36] exec doc autosaved (debounced " + EXEC_DOC_AUTOSAVE_DELAY_MS + "ms, T-ExecDocSync2 F5)");
            } catch (Throwable e) {
                Logger.getInstance(DataSourceSelector.class)
                        .warn("[M36] exec doc autosave FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    /**
     * F5 原语：EDT 上守卫落盘——doc 无主文件/已失效/未修改即 no-op（false）；确需落盘包
     * WriteAction saveDocument（T-EC-01 同款落盘语义）。public 供测试。
     */
    public static boolean autosaveExecDocNow(Document doc) throws java.io.IOException {
        FileDocumentManager fdm = FileDocumentManager.getInstance();
        VirtualFile vf = null;
        try {
            vf = fdm.getFile(doc);
        } catch (Throwable ignored) {
            // getOrNull 语义
        }
        if (vf == null || !vf.isValid()) return false;
        // 262：Document.isModified 已从接口移除——「已修改未保存」唯一查询是 isDocumentUnsaved
        if (!fdm.isDocumentUnsaved(doc)) return false;
        WriteAction.run((com.intellij.util.ThrowableRunnable<java.io.IOException>) () -> fdm.saveDocument(doc));
        return true;
    }

    /**
     * M36.5.3：临时文件方言预映射（MySQL）——PSI 创建时即按映射解析为 MySQL（否则走
     * SqlDialectPusher，dumb mode 下被推迟）。任何环节失败静默跳过（保持原行为，仅记录日志）。
     *
     * <p>T-ExecSlowOps F1：反射段收编 {@link DialectPreMapping}（与预览路径 mapPreviewDialectBeforePsi
     * 共用），本方法保留 exec 侧「进程内同 vf 幂等短路」语义 + 成功回写 {@link #dialectMappedVf}；
     * {@code ensureMapped} 线程自适应——EDT 直调安全（本方法现调用点全在 EDT），BGT 调用自动走两段式派发。</p>
     */
    public static void mapDialectBeforePsi(Project project, VirtualFile vf, Logger LOG) {
        mapDialectBeforePsi(project, vf, LOG, null);
    }

    public static void mapDialectBeforePsi(Project project, VirtualFile vf, Logger LOG,
                                           @Nullable String renderedDialectName) {
        // O-145 W7：有渲染事实时 exec PSI 必须跟随渲染方言；没有 request 的旧调用继续沿用
        // O-136 的 last-used/default 数据源路由。平台无对应 parser 时降级 GenericSQL，不冒充 MySQL。
        Object targetDs = renderedDialectName == null ? ExecDialectRouting.resolveTargetDataSource(project) : null;
        Object renderedLanguage = renderedDialectName != null
                ? ExecDialectRouting.resolvePluginDialectLanguage(project, renderedDialectName) : null;
        Object targetLanguage;
        if (renderedDialectName == null) {
            targetLanguage = ExecDialectRouting.resolveTargetLanguage(project, targetDs);
        } else if (renderedLanguage != null) {
            targetLanguage = renderedLanguage;
        } else {
            targetLanguage = DialectPreMapping.languageById(project, ExecDialectRouting.GENERIC_LANGUAGE_ID);
        }
        String targetId = targetLanguage instanceof com.intellij.lang.Language language
                ? language.getID()
                : (renderedDialectName == null ? ExecDialectRouting.MYSQL_LANGUAGE_ID
                : ExecDialectRouting.GENERIC_LANGUAGE_ID);
        // T-PreviewFlash：插件侧幂等短路——mpsql-exec.sql 路径稳定，同 vf 已映射过 MySQL 直接跳过。
        // O-136：短路收紧为「同 vf 且同方言」——目标数据源方言切换后同 vf 也必须重映射。
        boolean inProcessMapped = dialectMappedVf != null && dialectMappedVf.isValid() && dialectMappedVf == vf
                && targetId.equals(dialectMappedLanguageId);
        if (DialectPreMapping.ensureMapped(project, vf, targetLanguage, inProcessMapped)) {
            dialectMappedVf = vf;
            dialectMappedLanguageId = targetId;
            LOG.info("[M36] dialect pre-mapped to " + targetId + " for " + vf.getName()
                    + " (O-136 ds-dialect routing, ds="
                    + (targetDs != null ? targetDs.getClass().getSimpleName() : null) + ")");
        }
    }

    /** 打开 Database 工具窗口（无配置数据源时引导用户，仅显式执行路径调用；预览路径静默）。 */
    public static void openDatabaseToolWindow(Project project) {
        try {
            ToolWindowManager.getInstance(project).getToolWindow("Database").show();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    /** T-ExecCopyFix F2c：exec 临时文件 vf+psi 缓存是否仍持有（clearPrepared 不清缓存断言用）。 */
    public static boolean cachedTempPsiFileForTest() {
        return cachedTempPsiFile != null;
    }

    /**
     * T-ExplainConsoleFirst W8-fix（2026-10-24）：<b>调用方已确保自己在后台线程</b>时的预热入口。
     *
     * <p>与 {@link #prewarmExecTempFile(Project, String, String)} 的区别：后者内部会再 {@code submit} 一个池线程并
     * {@code get(30s)} 等它——那是给「后台预热」这种非交互场景用的<b>自带预算</b>形态。若从池线程调它，就变成
     * 「池线程 A 等池线程 B」的嵌套等待；而本入口<b>同步跑完</b>，调用方自己掌控等待与续接。</p>
     *
     * <p>必须传 {@code LOG}（调用方持有自己的 logger，便于把失败归到发起方而非本类）。
     * EDT 上调用直接拒绝并 warn——本链含 refresh/findFile 慢操作。</p>
     */
    public static void prewarmExecTempFileOnBackgroundThread(
            Project project,
            String sql,
            @Nullable String renderedDialectName,
            Logger LOG) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("[M36] prewarmExecTempFileOnBackgroundThread called on EDT — refused (slow ops)");
            return;
        }
        if (project.isDisposed() || sql.isBlank()) return;
        DataSourceSelectorResultsKt.prewarmExecTempFileInner(project, sql, LOG, renderedDialectName);
    }

    /**
     * T-ExecSlowOps F2：后台预热 exec 临时文件（写盘 → VFS refresh → 方言映射 → findFile →
     * 填 {@link #cachedTempPsiFile}）——<b>只允许后台线程调用</b>（EDT 直调拒绝并 warn：本链含
     * refresh/findFile 慢操作，EDT 执行正是 262 真机堆栈①的 SEVERE 本体）。
     *
     * <p>由 {@code SqlPreviewService.previewAsync} 在池线程内、{@code invokeLater} 派发弹窗之前调用：弹窗打开后
     * {@code prepareDefaultConsole → chooseAndPrepare → consumeCachedTempPsiFile} 必命中缓存，EDT 冷路径
     *（{@link #createTempPsiFile} 的 refresh+findFile）退化为兜底（无预览链触达的显式执行路径首跑等）。</p>
     *
     * <p>SQL 一致性：预热写入的是 {@code PreviewResult.sql} 原文，与
     * 面板渲染文本（格式化/参数回填后）可能不同——与 T-PreviewFlash 缓存命中路径同款语义（缓存命中
     * 时本就不重写文件，文档内容差异由下游 console doc 同步链吸收：reuseSharedConsole setText /
     * prepareAfterEditorResolved model 同步 / executeOnConsole 执行前同步）。</p>
     *
     * <p>幂等：缓存已对当前项目可用即直接返回（同项目连续预览仅一次真实链）。
     * Community/无 Database 插件场景安全：本方法不触任何 Database 类，纯 VFS/PSI。</p>
     */
    public static void prewarmExecTempFile(Project project, String sql) {
        prewarmExecTempFile(project, sql, null);
    }

    public static void prewarmExecTempFile(Project project, String sql, @Nullable String renderedDialectName) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("[M36] prewarmExecTempFile called on EDT — refused (slow ops, T-ExecSlowOps F2)");
            return;
        }
        if (project.isDisposed() || sql.isBlank()) return;
        TempPsiFile cachedVf = cachedTempPsiFile;
        if (cachedVf != null && isCacheUsable(project, cachedVf.vf)) {
            // 缓存命中≠内容新鲜：连续预览 SQL 会变，而缓存命中路径不重写文件——只刷新文件
            // 内容（projectExecFile doc 驱动三方同步，vf/psi 身份不变），保证 attachConsole
            // 初始 document 与本条预览 SQL 一致；差量内容比较在 projectExecFile 内部短路。
            //
            // T-PreviewConsoleFmt F2 + T-PreviewFast P3：**该文件正被编辑器打开时跳过内容刷新**——
            // 有人正看着这份内容时预热不得改写（预热写的是「预览原文」裸单行，会盖掉面板渲染进
            // 控制台的格式化文本；真机 20260917 日志：showSql createEditor len=274/16 行 → prewarm
            // 原文 → attach 显示 1 行）。判据为「文档已加载且挂了编辑器」——原 `console.getVirtualFile
            // () === vf` 判据实机从未命中（SKIPPED 计数 0 / refreshed 10），已退役。
            if (isExecDocOpenInEditor(cachedVf.vf)) {
                LOG.info("[M36] exec prewarm: cache hit, exec doc open in editor — content refresh SKIPPED (T-PreviewFast P3)");
                return;
            }
            // T-PreviewFast P2：非阻塞派发——预热是投机性写入，没必要让 BGT 等满 2 秒
            try {
                DataSourceSelectorResultsKt.projectExecFile(project, sql, true);
            } catch (Throwable e) {
                // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：写盘链的 fatal 不得被本 runCatching
                // 吞成一条 warn（否则 prewarm 链 4 处既有透传全部作废）。非 fatal 仍原样 warn 降级。
                if (FatalErrors.isFatal(e)) throw (Error) e;
                LOG.warn("[M36] exec prewarm refresh-content failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            LOG.info("[M36] exec prewarm: cache hit, content refreshed only");
            return;
        }
        // 并发预览（gutter 连点/多面板）只允许一条真实预热链——其余直接返回，弹窗冷路径兜底
        if (!execPrewarmInFlight.compareAndSet(false, true)) {
            LOG.info("[M36] exec prewarm skipped (another prewarm in flight)");
            return;
        }
        java.util.concurrent.Future<?> prewarmFuture =
                com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(() ->
                        DataSourceSelectorResultsKt.prewarmExecTempFileInner(project, sql, LOG));
        try {
            prewarmFuture.get(EXEC_PREWARM_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            prewarmFuture.cancel(true);
            LOG.warn("[M36] exec prewarm exceeded " + EXEC_PREWARM_BUDGET_MS
                    + "ms — cancelled & abandoned (best-effort; cold path rebuilds on demand)"
                    + " (T-PrewarmWilDeadlock W2)");
        } catch (java.util.concurrent.ExecutionException e) {
            // 内层 fatal 经 Future 包装后仍须原对象上浮；非 fatal 保持预热降级语义。
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            LOG.warn("[M36] exec prewarm failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            prewarmFuture.cancel(true);
            LOG.warn("[M36] exec prewarm waiter interrupted — abandoned (T-PrewarmWilDeadlock W2)");
        } finally {
            execPrewarmInFlight.set(false);
        }
    }

    /**
     * T-PreviewFast P1：exec 文件当前内容是否已等于目标 SQL——差量快路径判据（双源）。
     *
     * <p>有已加载 document 时以 <b>document 文本</b>为准（UI/控制台所见即权威，也是落盘会写入的内容）；
     * 无 document 时读 <b>vf 内容</b>。两者比对失败（异常/不可读）一律返回 false（保守走正常写入路径）。
     * public 供测试。</p>
     */
    public static boolean execFileContentMatches(Project project, VirtualFile vf, String sql) {
        try {
            Document doc = FileDocumentManager.getInstance().getCachedDocument(vf);
            if (doc != null) {
                return doc.getText().equals(sql);
            }
            ExecWrittenSnapshot snap = lastWrittenExecContent.get(vf.getPath());
            return snap != null && snap.timeStamp() == vf.getTimeStamp() && snap.content().equals(sql);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * exec 文件最近一次写入快照（T-PreviewFast 差量快路径判据；content = 写入文本，timeStamp = 写入时 vf 时间戳）。
     */
    public record ExecWrittenSnapshot(
        /** 写入 exec 文件的文本。 */
        String content,
        /** 写入时刻的 {@code vf.timeStamp}（外部改盘自动失效标记）。 */
        long timeStamp
    ) {
    }

    /**
     * T-PreviewFast P3：exec 文件是否<b>正被编辑器打开</b>（= 有人正在看这份内容）——预热据此决定
     * 是否跳过内容刷新。
     *
     * <p>取代 T-PreviewConsoleFmt 的 {@code console.getVirtualFile() === vf} 判据：真机实测该等式从未成立
     * （{@code content refresh SKIPPED} 计数 0 而 {@code content refreshed only} 10 次），预热因此每次仍改写
     * 文件、与面板回填互相覆盖一轮。改判「该 vf 的文档已加载且挂了编辑器」——语义正是
     * 「别改用户眼前的内容」，且不依赖 DBN 内部方法。public 供测试。</p>
     */
    public static boolean isExecDocOpenInEditor(VirtualFile vf) {
        try {
            Document doc = FileDocumentManager.getInstance().getCachedDocument(vf);
            if (doc == null) return false;
            return EditorFactory.getInstance().getEditors(doc).length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** T-PerfSweep2-04：已挂项目关闭钩子的项目集——防对同一项目重复 Disposer.register。 */
    private static final Set<Project> hookProjectsClose =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * T-ExecSlowOps F2：填 exec 临时文件 vf+psi 缓存槽（项目绑定；BGT/EDT 均可调用——volatile 写）。
     * T-PerfSweep2-04：同时挂一次性项目关闭钩子——项目关闭即清槽，不再等下次消费顶掉
     *（此前项目关闭后槽里残留该项目的 vf+psi 强引用至下次写入；isCacheUsable 惰性校验
     * 保留为双保险）。parentDisposable 默认项目自身，测试注入自有 Disposable 手动触发。
     */
    public static void markCachedTempPsiFile(Project project, VirtualFile vf, PsiFile psi) {
        markCachedTempPsiFile(project, vf, psi, project);
    }

    /** parentDisposable 显式重载（Kotlin 默认参数 {@code parentDisposable = project} 的显式形态）。 */
    public static void markCachedTempPsiFile(Project project, VirtualFile vf, PsiFile psi,
                                             com.intellij.openapi.Disposable parentDisposable) {
        cachedTempPsiFile = new TempPsiFile(vf, psi);
        cachedTempPsiProjectName = project.getName();
        hookProjectClose(project, parentDisposable);
        // T-ExecDocSync2 F5/F6 挂载点：exec 缓存槽建立即安装防抖自动落盘监听（应用级惰性一次）
        // 与冲突探针（项目级一次）；非 exec 路径 vf 进不来本方法，路径过滤双保险。
        // 安装段 runCatching 降级——探针 connect 在无 messageBus 的代理桩项目上 NPE
        // （CachedTempPsiFileTest 全代理桩实证）；两件均为增强件，失败仅失去防抖落盘
        // 与冲突取证，不得阻断缓存主链
        if (isExecVfPath(vf.getPath())) {
            try {
                installExecDocAutosaveListener();
                DataSourceSelectorResultsKt.installExecConflictProbe(project);
            } catch (Throwable e) {
                Logger.getInstance(DataSourceSelector.class)
                        .warn("[M36] exec doc autosave/probe install skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private static void hookProjectClose(Project project, com.intellij.openapi.Disposable parentDisposable) {
        if (!hookProjectsClose.add(project)) return;
        Disposer.register(parentDisposable, () -> {
            hookProjectsClose.remove(project);
            conflictProbeProjects.remove(project);
            TempPsiFile cached = cachedTempPsiFile;
            if (cached != null && cached.psi.getProject() == project) {
                cachedTempPsiFile = null;
                cachedTempPsiProjectName = null;
            }
        });
    }

    /** T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（成功时 null）。 */
    public static String lastTempFileFailureForTest() {
        return lastTempFileFailure;
    }

    /** T-ExecCopyFix F2c 测试缝：等价链路置缓存槽（psi 经 PsiManager.findFile 同生产路径配对）。 */
    public static void markCachedTempPsiFileForTest(Project project, VirtualFile vf) {
        PsiFile psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
        if (psi == null) {
            throw new IllegalStateException("markCachedTempPsiFileForTest: findFile null for " + vf.getName());
        }
        markCachedTempPsiFile(project, vf, psi);
    }

    /** T-ExecCopyFix F2c 测试缝：清缓存槽（测试隔离收尾）。 */
    public static void clearCachedTempPsiFileForTest() {
        cachedTempPsiFile = null;
        cachedTempPsiProjectName = null;
    }

    /** T-ExecDocSync 测试缝：置 true 时 {@link #docWriteOnce} 注入失败（测 T-EC-02 守卫拒绝路径；EDT/BGT 两形态统一生效）。 */
    public static boolean execWriteActionFailureInjection = false;

    /** T-PS4-11 测试缝：防抖任务是否在途（未完成）——监听生效/卸载的行为断言点。 */
    public static boolean execDocAutosaveScheduledForTest() {
        java.util.concurrent.Future<?> pending = execDocAutosavePending.get();
        return pending != null && !pending.isDone();
    }

    /** T-PS4-11 测试缝：取消在途防抖任务并清快判缓存（测试隔离收尾，不触碰宿主挂载态）。 */
    public static void resetExecDocAutosaveForTest() {
        java.util.concurrent.Future<?> prev = execDocAutosavePending.getAndSet(null);
        if (prev != null) prev.cancel(false);
        lastExecDocRef.set(null);
    }
}
