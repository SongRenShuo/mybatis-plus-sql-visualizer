// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt object 段移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.mcp.McpDbExecutorKt.WarningSurface;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * T-McpToolset headless 执行通道（sqlv_explain 专用，反射隔离 DatabaseTools）。
 *
 * 为什么不复用 {@code cn.song.mybatisplus.visualizer.execute.SqlExecutor}：既有执行链是 UI 控制台
 * 形态（弹数据源选择框 + 异步回调 + 结果进原生控制台），违反 MCP 零弹窗红线且拿不到文本结果。
 * 本通道走 DatabaseConnectionManager 同步建连（243/262 javap 同形实证）：
 * <pre>{@code
 * DatabaseConnectionManager.getInstance()                    // static
 *   .build(project, localDataSource)                         // LocalDataSource 即 DatabaseConnectionPoint
 *   .setAskPassword(false)                                   // 零弹窗：密码未保存直接失败（fail closed）
 *   .createBlocking()  -> GuardedRef<DatabaseConnection>     // 同步无 UI
 * ref.get().getRemoteConnection()                            // DatabaseConnectionCore 接口方法
 *   .createStatement().executeQuery(sql)  -> RemoteResultSet // DBN remote JDBC 镜像
 * }</pre>
 * 全反射 + Method 一律从声明接口/类获取（T-Embed-10 跨 classloader 教训）。签名由
 * McpDbSignatureCrossVersionTest 对本机 243/253/262 发行版核验。
 *
 * T-NoticeChannel：执行前后经 {@code warningSurfaces} 取尽语句级与连接级 warnings
 * （getAllWarnings + clearWarnings，DBN 镜像接口），产出挂 RawResult.notices 供工具层渲染。
 *
 * 数据源发现复用 {@code cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver} 同链：
 * DataSourceManager.getManagers(project) → getDataSources()，按 DasDataSource.getName() 匹配别名。
 *
 * 移植注记：Kotlin 侧方法体不经 catch 直接把反射异常冒泡给调用方的 runCatching；Java 无受检
 * 异常豁免，故以 {@code throws Exception} 表达同一「异常原样上抛、调用方兜底」契约。
 */
public final class McpDbExecutor {

    private McpDbExecutor() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpDbExecutor INSTANCE = new McpDbExecutor();

    private static final Logger LOG = Logger.getInstance(McpDbExecutor.class);

    /** database 插件标记类（探测用，与 DatabaseSchemaResolver 同款）。 */
    private static final String MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    private static final String DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource";

    private static final String DCM = "com.intellij.database.dataSource.DatabaseConnectionManager";
    private static final String CONNECTION_POINT = "com.intellij.database.dataSource.DatabaseConnectionPoint";
    private static final String BUILDER = "com.intellij.database.dataSource.DatabaseConnectionManager$Builder";
    private static final String GUARDED_REF = "com.intellij.database.util.GuardedRef";
    private static final String CONNECTION_CORE = "com.intellij.database.dataSource.DatabaseConnectionCore";
    private static final String CONNECTION = "com.intellij.database.dataSource.DatabaseConnection";
    private static final String REMOTE_CONNECTION = "com.intellij.database.remote.jdbc.RemoteConnection";
    private static final String REMOTE_STATEMENT = "com.intellij.database.remote.jdbc.RemoteStatement";
    private static final String REMOTE_RESULT_SET = "com.intellij.database.remote.jdbc.RemoteResultSet";
    private static final String REMOTE_META = "com.intellij.database.remote.jdbc.RemoteResultSetMetaData";

    /** T-McpAuditTrail T-MAT-01：失败文本上限（历史文件单条元数据行，不落整段堆栈）。 */
    private static final int ERROR_TEXT_LIMIT = 200;

    /** database 插件是否在场（Ultimate/DataGrip）。 */
    public static boolean isDatabaseAvailable() {
        try {
            Class.forName(MANAGER_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 列出全部数据源显示名（alias 未匹配时给 agent 的可选清单）。 */
    public static List<String> listDataSourceNames(Project project) {
        List<Object> sources = dataSources(project);
        if (sources == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object ds : sources) {
            try {
                Object name = Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds);
                if (name instanceof String s) {
                    out.add(s);
                }
            } catch (Throwable ignored) {
                // 单个数据源名反射失败 → 跳过该条（mapNotNull 语义）
            }
        }
        return out;
    }

    /**
     * 按别名找数据源。alias 空时：恰好一个数据源 → 用之；0 个/多个 → null（调用方报错并列名）。
     * 别名匹配大小写不敏感；找不到返回 null。
     */
    public static Object findDataSource(Project project, String alias) {
        List<Object> sources = dataSources(project);
        if (sources == null) {
            return null;
        }
        String named = alias == null ? null : alias.trim();
        if (named != null && named.isEmpty()) {
            named = null;
        }
        if (named == null) {
            return sources.size() == 1 ? sources.get(0) : null;
        }
        for (Object ds : sources) {
            String name = null;
            try {
                Object raw = Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds);
                if (raw instanceof String s) {
                    name = s;
                }
            } catch (Throwable ignored) {
                // 反射失败视作无名（不参与匹配）
            }
            if (name != null && name.equalsIgnoreCase(named)) {
                return ds;
            }
        }
        return null;
    }

    /**
     * 一次执行的结果载体（T-McpAuditTrail / T-MAT-01）：只含 JDK 类型，不引平台/IDE 服务类型——
     * 回调注入形态正是为了不把 {@link McpDbExecutor} 的 headless 边界与
     * {@code cn.song.mybatisplus.visualizer.log.SqlLogManager} 之类的项目级服务绑在一起
     * （依赖留在工具层）。
     *
     * @param executedSql 实际交给驱动的语句文本（explain 通道=EXPLAIN 包装句）。<b>注意</b>：历史入库
     *   必须用调用方自己持有的<b>原始</b> selectSql，不得落本字段（T-MAT-02：包装句首 token 为
     *   {@code EXPLAIN}，{@code cn.song.mybatisplus.visualizer.log.LogStatementClassifier} 会判
     *   {@code OTHER} → 无类型染色）。
     * @param elapsedMs 本次执行耗时（含建连与取数，毫秒）
     * @param errorText 失败时的「异常类名: 首行消息」（截断）；成功为 null
     * @param dataSourceName 数据源显示名（反射 DasDataSource.getName；不可达/headless 为 null）
     */
    public record ExecutedOutcome(
        String executedSql,
        long elapsedMs,
        String errorText,
        String dataSourceName
    ) {
    }

    /**
     * 在指定数据源上执行只读查询并采集行列文本（EXPLAIN 用）。
     * 密码未保存/不可达等建连失败以异常冒泡，适配层按
     * {@link McpToolErrors#DATASOURCE_UNREACHABLE} 包装为 agent 可读错误（零弹窗 fail closed）。
     *
     * <p>T-ExecTimeoutGuard（O-25）：timeoutSeconds &gt; 0 时执行前 setQueryTimeout（驱动级
     * 建议值，RemoteStatement 镜像 243/262 javap 同形）；超时形态异常
     * （{@code cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard.isTimeoutLike}）先
     * Statement.cancel() 收尾再冒泡，消息尾附 {@code timed out after Ns}（agent 可读，不引 JSON）。</p>
     *
     * <p>T-McpRowBudget（O-36）：{@code maxRows} 先过 {@link McpDbExecutorKt#channelRowBudget} 兜底钳制
     * 再进采集——调用方（含未来新增的执行类工具）传什么都不会突破
     * {@link McpExplainFormatter#HARD_MAX_ROWS}。</p>
     *
     * <p>T-McpAuditTrail（T-MAT-01）：{@code onExecuted} 为执行链唯一出口钩子——成功与失败<b>两条路径</b>
     * 都回调一次（失败回调后异常原样冒泡）。钩子是旁路：自身抛异常只记日志，绝不连坐执行结果
     * 或吞掉失败（审计不得成为门控）。null（默认）时零行为差异——既有调用点逐字节不变。</p>
     *
     * <p>R23（Oracle/SQL Server EXPLAIN 接入）：{@code channelNote} 非 null 时走 <b>execute() 多结果宽容
     * 通道</b>——{@code executeQuery(String)} 只接受「首结果即 ResultSet」的语句，而 Oracle 的
     * {@code EXPLAIN PLAN FOR}（首结果为 update count、无行返回）与 SQL Server 的 {@code SET STATISTICS XML
     * ON; …} 批（前导 SET 产生 update count）都会让它直接抛错。宽容通道按 JDBC 结果游走协议
     * （execute → updateCount → getMoreResults，有界）取<b>首个</b> ResultSet；一个 ResultSet 都
     * 拿不到时返回空行列（诚实降级）。{@code channelNote} 追加在驱动 notices 之后的 notices 节——
     * 二段式/附加结果集方言的计划「为什么不在上面的行里」的说明由
     * {@code cn.song.mybatisplus.visualizer.sql.ExplainSupport} 表单源持有。null（默认）走既有
     * executeQuery 通道，<b>行为逐字节不变</b>（其余 9 家方言与其他调用点的防回归面）。</p>
     *
     * <p>T-ExplainPwdPrompt（2026-10-24）：{@code askPassword} <b>必填</b>——编译期强迫每个调用点按通道
     * 表态，不设默认值（默认 false 会让未来新增的 UI 调用点「静默不弹」重演缺陷；默认 true 会
     * 击穿 MCP 零弹窗红线）。UI 上下文（面板执行计划/预估行数）传 true：数据源未存密码时交
     * 平台原生密码框（Builder 默认即 true，javap 构造器字节码实证；只在「未存」时问，已存密码
     * 零变化）；MCP 工具（{@code SqlVisualizerExplainTool}）传 false：程序性调用弹模态框即挂死，
     * 维持零弹窗 fail closed。</p>
     */
    public static McpExplainFormatter.RawResult executeQuery(
        Project project,
        Object dataSource,
        String sql,
        int maxRows,
        int timeoutSeconds,
        boolean askPassword,
        Consumer<ExecutedOutcome> onExecuted,
        String channelNote
    ) throws Exception {
        long started = System.currentTimeMillis();
        try {
            McpExplainFormatter.RawResult result =
                executeQueryInternal(project, dataSource, sql, maxRows, timeoutSeconds, askPassword, channelNote);
            notifyExecuted(onExecuted, sql, System.currentTimeMillis() - started, null, dataSource);
            return result;
        } catch (Throwable e) {
            // T-FatalPassthrough（合流收口，T-FP-03 守卫要求）：JVM 级致命错误不投审计钩子——
            // 钩子会构造 ExecutedOutcome（反射取数据源名 + 文本构造）并落历史，内存/链接已损坏时
            // 继续这些副作用只会把现场搅乱；fatal 原样透传给平台/进程级通道。反射链须先解包：
            // 目标方法抛出的 Error 被 Method.invoke 包进 InvocationTargetException（普通异常），
            // 只判外层会漏判（与 errorTextOf 同款解包口径，见 util/FatalErrors.java 用法说明）。
            Throwable cause = e instanceof InvocationTargetException ite ? ite.getTargetException() : e;
            if (FatalErrors.isFatal(cause)) {
                // master 的 `throw cause` 直译：Java 的 `throws Exception` 装不下 Throwable/Error，
                // 经同包 sneakyThrow 擦除类型后抛出——运行时抛出的仍是 cause 实例本身（语义等价）。
                throw McpToolsetSupport.<RuntimeException>sneakyThrow(cause);
            }
            notifyExecuted(onExecuted, sql, System.currentTimeMillis() - started, e, dataSource);
            // 同上：master `throw e` 直译——原异常实例原样冒泡（调用方按 Throwable 兜底）。
            throw McpToolsetSupport.<RuntimeException>sneakyThrow(e);
        }
    }

    /** Kotlin 默认参数等价重载：channelNote 缺省 null（askPassword 必填，T-ExplainPwdPrompt）。 */
    public static McpExplainFormatter.RawResult executeQuery(
        Project project,
        Object dataSource,
        String sql,
        int maxRows,
        int timeoutSeconds,
        boolean askPassword,
        Consumer<ExecutedOutcome> onExecuted
    ) throws Exception {
        return executeQuery(project, dataSource, sql, maxRows, timeoutSeconds, askPassword, onExecuted, null);
    }

    /** Kotlin 默认参数等价重载：onExecuted/channelNote 缺省 null（askPassword 必填，T-ExplainPwdPrompt）。 */
    public static McpExplainFormatter.RawResult executeQuery(
        Project project,
        Object dataSource,
        String sql,
        int maxRows,
        int timeoutSeconds,
        boolean askPassword
    ) throws Exception {
        return executeQuery(project, dataSource, sql, maxRows, timeoutSeconds, askPassword, null, null);
    }

    /**
     * 钩子投递（T-MAT-01）：hook 为 null 时<b>立即返回</b>——耗时以外的任何采集（反射取数据源名、
     * 异常文本构造）都不发生，未接线的调用点零开销。
     */
    private static void notifyExecuted(
        Consumer<ExecutedOutcome> hook,
        String sql,
        long elapsedMs,
        Throwable failure,
        Object dataSource
    ) {
        if (hook == null) {
            return;
        }
        ExecutedOutcome outcome = new ExecutedOutcome(
            sql,
            elapsedMs,
            failure != null ? errorTextOf(failure) : null,
            dataSourceName(dataSource)
        );
        try {
            hook.accept(outcome);
        } catch (Throwable t) {
            LOG.warn("McpDbExecutor: onExecuted hook failed (execution result unaffected)", t);
        }
    }

    /**
     * 失败文本（T-MAT-01）：异常类名 + 首行消息，{@link #ERROR_TEXT_LIMIT} 截断。
     *
     * <p>反射调用链里异常被包在 {@code InvocationTargetException} 中（其 message 恒 null），故先解包取
     * 目标异常——否则历史里只剩一个类名，排障无信息。多行堆栈只取首行（历史文件单条正文，
     * 不落整段堆栈）。</p>
     */
    private static String errorTextOf(Throwable e) {
        Throwable target = e instanceof InvocationTargetException ite ? ite.getTargetException() : e;
        String message = target.getMessage();
        String firstLine = message == null
            ? null
            : message.lines().filter(line -> !line.isBlank()).findFirst().orElse(null);
        String text = target.getClass().getSimpleName() + ": "
            + (firstLine != null ? firstLine : "(no message)");
        return text.length() <= ERROR_TEXT_LIMIT ? text : text.substring(0, ERROR_TEXT_LIMIT) + "…";
    }

    /** 数据源显示名（反射 DasDataSource.getName；不可达返回 null——展示性字段，取不到不影响主链）。 */
    private static String dataSourceName(Object dataSource) {
        try {
            Object raw = Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(dataSource);
            return raw instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** T-MAT-01 之前的执行本体（钩子包装前逐行原样；行为零变化）。 */
    private static McpExplainFormatter.RawResult executeQueryInternal(
        Project project,
        Object dataSource,
        String sql,
        int maxRows,
        int timeoutSeconds,
        boolean askPassword,
        String channelNote
    ) throws Exception {
        int rowBudget = McpDbExecutorKt.channelRowBudget(maxRows);
        Object manager = Class.forName(DCM).getMethod("getInstance").invoke(null);
        Object builder = Class.forName(DCM).getMethod("build", Project.class, Class.forName(CONNECTION_POINT))
            .invoke(manager, project, dataSource);
        // T-ExplainPwdPrompt（2026-10-24）：askPassword 按调用点通道表态（见 executeQuery Javadoc）——
        // 此前硬编码 false（2026-09-15 T-McpToolset 为 MCP 立的零弹窗红线），面板 EXPLAIN 与预估
        // 行数旁路切到本通道时被连坐：数据源未存密码不弹原生密码框，直接 Access denied 红字
        //（沙盒实测截图 2026-10-23）。平台 Builder 默认即 true（javap 构造器字节码 iconst_1），
        // 询问链在 DatabaseConnectionEstablisher（suspend，含凭据失败重试）。
        Class.forName(BUILDER).getMethod("setAskPassword", Boolean.TYPE)
            .invoke(builder, askPassword);
        // T-PreviewChainNoise W2：建连前补齐阻塞上下文（见 withDbBlockingContext Javadoc）——
        // 裸池线程（ExplainPanelChannel.executeStructured 的 executeOnPooledThread）上
        // createBlocking 内部 runBlockingCancellable 缺 Job 即打
        // "There is no ProgressIndicator or Job in this thread" SEVERE（真机 2026-10-23 实证）。
        Object ref = McpDbExecutorKt.withDbBlockingContext(
            // Kotlin lambda 可直接抛受检异常并原样外传；Java Supplier 装不下——sneakyThrow 擦除类型
            // 抛出（运行时抛的仍是原实例，外层 catch(Throwable)/ITE 解包语义与 master 逐字一致）。
            () -> {
                try {
                    return Class.forName(BUILDER).getMethod("createBlocking").invoke(builder);
                } catch (Throwable t) {
                    throw McpToolsetSupport.<RuntimeException>sneakyThrow(t);
                }
            });
        try {
            Object connection = Class.forName(GUARDED_REF).getMethod("get").invoke(ref);
            Object remoteConnection = findInterfaceMethod(connection, CONNECTION_CORE, CONNECTION, "getRemoteConnection")
                .invoke(connection);
            Object statement = Class.forName(REMOTE_CONNECTION).getMethod("createStatement").invoke(remoteConnection);
            try {
                // 超时护栏：驱动级建议值，设置 0=不限；设置失败不拦执行（护栏尽力而为语义）
                if (timeoutSeconds > 0) {
                    try {
                        Class.forName(REMOTE_STATEMENT).getMethod("setQueryTimeout", Integer.TYPE)
                            .invoke(statement, timeoutSeconds);
                    } catch (Throwable failure) {
                        LOG.info("McpDbExecutor: setQueryTimeout unsupported by driver, guard off", failure);
                    }
                }
                WarningSurface[] surfaces = warningSurfaces(remoteConnection, statement);
                // 执行前先 drain 一次（DBX 口径）：DBN 连接可复用，上一条语句/会话遗留的陈旧
                // notices 不允许串扰进本次结果；返回值丢弃，只做清空。
                McpDbExecutorKt.drainWarnings(surfaces);
                try {
                    // R23：channelNote 非 null（二段式/多结果集方言）走 execute() 宽容通道；
                    // null（默认）保持 executeQuery 通道——既有 9 家方言与其他调用点逐字节不变
                    McpExplainFormatter.RawResult raw;
                    if (channelNote == null) {
                        Object resultSet = Class.forName(REMOTE_STATEMENT).getMethod("executeQuery", String.class)
                            .invoke(statement, sql);
                        raw = collect(resultSet, rowBudget);
                    } else {
                        raw = executeStatementCollectFirst(statement, sql, rowBudget);
                    }
                    // 执行成功才采集 notices；失败路径直接抛出（notices 无从产生=DBX「出错丢弃」口径）
                    List<String> notices = McpDbExecutorKt.drainWarnings(surfaces);
                    if (!notices.isEmpty()) {
                        LOG.info("McpDbExecutor: drained " + notices.size() + " driver notice(s)");
                    }
                    // R23：通道说明固定排在驱动 notices 之后——「行里为什么没有计划」的说明总是收尾
                    List<String> finalNotices = notices;
                    if (channelNote != null) {
                        finalNotices = new ArrayList<>(notices);
                        finalNotices.add(channelNote);
                    }
                    return new McpExplainFormatter.RawResult(
                        raw.headers(), raw.rows(), raw.truncated(), finalNotices);
                } catch (InvocationTargetException e) {
                    // T-ExecTimeoutGuard：超时先收尾再报错——cancel 请求（RMI 镜像尽力而为）
                    // 后再冒泡带 timed out 标记的异常；cancel 失败不吞超时事实（如实措辞）
                    Throwable target = e.getTargetException();
                    if (cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard.isTimeoutLike(target)) {
                        try {
                            Class.forName(REMOTE_STATEMENT).getMethod("cancel").invoke(statement);
                        } catch (Throwable cancelFailure) {
                            LOG.warn("McpDbExecutor: cancel after timeout failed", cancelFailure);
                        }
                        throw new SQLException(
                            "query timed out after " + timeoutSeconds + "s (cancel requested); "
                                + "original: " + (target.getMessage() != null
                                    ? target.getMessage()
                                    : target.getClass().getSimpleName()),
                            target
                        );
                    }
                    throw e;
                }
            } finally {
                try {
                    Class.forName(REMOTE_STATEMENT).getMethod("close").invoke(statement);
                } catch (Throwable failure) {
                    LOG.warn("McpDbExecutor: close statement failed", failure);
                }
            }
        } finally {
            try {
                Class.forName(GUARDED_REF).getMethod("close").invoke(ref);
            } catch (Throwable failure) {
                LOG.warn("McpDbExecutor: close connection ref failed", failure);
            }
        }
    }

    /**
     * R23：execute() 多结果宽容通道——按 JDBC 结果游走协议取<b>首个</b> ResultSet。
     *
     * <p>协议（java.sql.Statement 契约，RemoteStatement 镜像 262 javap 同形实证）：
     * {@code execute(sql)} 返回首结果是否为 ResultSet；false 时 {@code getUpdateCount()} 给出当前结果的
     * update count（<b>-1 = 再无结果</b>）；{@code getMoreResults()} 前进到下一结果。前导 SET 语句
     * （Oracle {@code EXPLAIN PLAN FOR} / SQL Server {@code SET STATISTICS XML ON}）产生 update count=0
     * 的首结果——本函数跳过它们取到真正的查询行；一个 ResultSet 都没有（Oracle 计划只登记）
     * 返回空行列。游走<b>有界</b>（[MAX_RESULT_HOPS]）——驱动 getMoreResults 失灵时不挂死。</p>
     */
    private static McpExplainFormatter.RawResult executeStatementCollectFirst(Object statement, String sql, int maxRows)
        throws Exception {
        Class<?> stmtClass = Class.forName(REMOTE_STATEMENT);
        Method execute = stmtClass.getMethod("execute", String.class);
        Method getResultSet = stmtClass.getMethod("getResultSet");
        Method getMoreResults = stmtClass.getMethod("getMoreResults");
        Method getUpdateCount = stmtClass.getMethod("getUpdateCount");
        Object rs = ((Boolean) execute.invoke(statement, sql)) ? getResultSet.invoke(statement) : null;
        int hops = 0;
        while (rs == null && hops < MAX_RESULT_HOPS) {
            hops++;
            if (((Integer) getUpdateCount.invoke(statement)) == -1) {
                break;
            }
            if ((Boolean) getMoreResults.invoke(statement)) {
                rs = getResultSet.invoke(statement);
            }
        }
        LOG.info("McpDbExecutor: execute-channel first-result walk done (hops=" + hops
            + ", resultSet=" + (rs != null) + ")");
        return rs != null
            ? collect(rs, maxRows)
            : new McpExplainFormatter.RawResult(List.of(), List.of(), false);
    }

    /** execute() 宽容通道的结果游走上限（防驱动失灵挂死；正常批 ≤3~4 个结果，余量充足）。 */
    private static final int MAX_RESULT_HOPS = 32;

    /**
     * warnings 采集面（语句级先、连接级后，执行前 drain 与执行后采集共用同一组 Method 查找）。
     * getAllWarnings/clearWarnings 在 RemoteStatement 与 RemoteConnection 上均有声明
     * （243/262 发行版 javap 实证，McpDbSignatureChecker 登记）——DBN remote JDBC 镜像自带
     * 取尽方法，无需手写 getNextWarning 链式遍历（每次 next 都是一次 RMI 往返）。
     */
    private static WarningSurface[] warningSurfaces(Object remoteConnection, Object statement) {
        return new WarningSurface[]{
            new WarningSurface(
                () -> {
                    try {
                        Object raw = Class.forName(REMOTE_STATEMENT).getMethod("getAllWarnings").invoke(statement);
                        return raw instanceof List<?> list ? list : null;
                    } catch (Throwable ignored) {
                        // 该面取 warnings 失败 → drainWarnings 兜底为不产出（runCatching 语义）
                        return null;
                    }
                },
                () -> {
                    try {
                        Class.forName(REMOTE_STATEMENT).getMethod("clearWarnings").invoke(statement);
                    } catch (Throwable ignored) {
                        // clear 失败由 drainWarnings 兜底（不丢已取到的消息）
                    }
                }
            ),
            new WarningSurface(
                () -> {
                    try {
                        Object raw = Class.forName(REMOTE_CONNECTION).getMethod("getAllWarnings")
                            .invoke(remoteConnection);
                        return raw instanceof List<?> list ? list : null;
                    } catch (Throwable ignored) {
                        // 该面取 warnings 失败 → drainWarnings 兜底为不产出（runCatching 语义）
                        return null;
                    }
                },
                () -> {
                    try {
                        Class.forName(REMOTE_CONNECTION).getMethod("clearWarnings").invoke(remoteConnection);
                    } catch (Throwable ignored) {
                        // clear 失败由 drainWarnings 兜底（不丢已取到的消息）
                    }
                }
            ),
        };
    }

    /** 采集 ResultSet 行列（读满 maxRows 即停，truncated 标记供格式化层提示）。 */
    private static McpExplainFormatter.RawResult collect(Object resultSet, int maxRows) throws Exception {
        Class<?> rsClass = Class.forName(REMOTE_RESULT_SET);
        Object meta = rsClass.getMethod("getMetaData").invoke(resultSet);
        int columnCount = (Integer) Class.forName(REMOTE_META).getMethod("getColumnCount").invoke(meta);
        List<String> headers = new ArrayList<>();
        for (int col = 1; col <= columnCount; col++) {
            Object label = Class.forName(REMOTE_META).getMethod("getColumnLabel", Integer.TYPE)
                .invoke(meta, col);
            headers.add(label instanceof String s ? s : "col" + col);
        }
        Method nextMethod = rsClass.getMethod("next");
        Method getString = rsClass.getMethod("getString", Integer.TYPE);
        Method wasNull = rsClass.getMethod("wasNull");
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while ((Boolean) nextMethod.invoke(resultSet)) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<String> row = new ArrayList<>();
            for (int col = 1; col <= columnCount; col++) {
                Object raw = getString.invoke(resultSet, col);
                String value = raw instanceof String s ? s : null;
                if (value == null && (Boolean) wasNull.invoke(resultSet)) {
                    row.add("");
                } else {
                    row.add(value != null ? value : "");
                }
            }
            rows.add(row);
        }
        try {
            rsClass.getMethod("close").invoke(resultSet);
        } catch (Throwable ignored) {
            // 关闭失败不影响已采集数据（runCatching 语义）
        }
        LOG.info("McpDbExecutor: collected " + rows.size() + " rows (truncated=" + truncated
            + "), columns=" + headers.size());
        return new McpExplainFormatter.RawResult(headers, rows, truncated);
    }

    /**
     * 方法弹性查找：优先声明接口 primaryInterface（T-Embed-10：Method 一律从声明接口拿，
     * 跨插件 classloader 用实现类反射会抛 IllegalAccessException），243/262 声明层差异时
     * 回落 fallbackInterface。
     */
    private static Method findInterfaceMethod(Object target, String primaryInterface, String fallbackInterface,
                                              String name) throws Exception {
        try {
            return Class.forName(primaryInterface).getMethod(name);
        } catch (Throwable primaryFailure) {
            try {
                return Class.forName(fallbackInterface).getMethod(name);
            } catch (Throwable ignored) {
                // 两个声明层都没有 → 从实例类拿（照 DatabaseSchemaResolver 的失败即空语义，此处应不可达）
                return target.getClass().getMethod(name);
            }
        }
    }

    /** 数据源清单（反射 DataSourceManager.getManagers(project) → getDataSources()）。 */
    private static List<Object> dataSources(Project project) {
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Object managersRaw = managerClass.getMethod("getManagers", Project.class).invoke(null, project);
            List<?> managers = managersRaw instanceof List<?> list ? list : List.of();
            List<Object> out = new ArrayList<>();
            for (Object manager : managers) {
                if (manager == null) {
                    continue;
                }
                Object listRaw = managerClass.getMethod("getDataSources").invoke(manager);
                if (!(listRaw instanceof List<?> list)) {
                    continue;
                }
                for (Object item : list) {
                    if (item != null) {
                        out.add(item);
                    }
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
