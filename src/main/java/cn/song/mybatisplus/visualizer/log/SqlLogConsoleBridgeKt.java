package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.diagnostic.Logger;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code SqlLogConsoleBridge.kt} 顶层函数（processConsoleLine / findConsoleView）的 Java 承载类。
 */
public final class SqlLogConsoleBridgeKt {
    private SqlLogConsoleBridgeKt() {
    }

    private static final Logger MARKER_DEFENSE_LOG =
            Logger.getInstance("cn.song.mybatisplus.visualizer.log.SqlSourceMarkerDefense");

    /**
     * 处理一行控制台文本（F-44-6）：
     *  1. 若为本插件源码标记（SqlSourceInterceptor 注入）→ 暂存，不喂解析器；
     *     T-IntrUpg-01：自位污染标记（旧模板 isSelf 常量分叉时拦截器上报自身帧）不暂存，
     *     条目落无位置链路（MissingSource 引导 → 立即注入触发升级）；
     *  1.5) T-M55-02：SQL 执行报错行（{@link SqlErrorLineRecognizer} 词表命中）→ 旁路聚合错误块并经
     *     onErrorText 实时反向绑定缓冲最近条目（不改控制台输出、不干扰解析器主链）；
     *  2. 喂行到三个解析器（配置快照已由设置变更事件同步——T-LogPerf-04），取出已配对 SQL 条目；
     *  2.5) T-ConsolePendingFlush：喂行后冲刷「已静默 ≥1s」桶的待产出条目（见
     *     {@link JdbcTemplateLogParser#flushSettled()}）——按线程分桶后，语句行之后没有同线程后继行的
     *     语句（JdbcTemplate 无 TRACE 参数行 + 线程按请求新建）否则永远停在桶里；
     *  3. 附加耗时解析结果 + 暂存源码位置，写入 LogSqlEntry。
     * 纯函数式、无 IDE 依赖，可在单元测试中直接驱动（SqlLogConsoleBridgeMarkerTest）。
     */
    /** 无错误绑定回调形态（Kotlin onErrorText 默认参数 null 的 Java 重载等价）。 */
    public static List<LogSqlEntry> processConsoleLine(
            String line,
            BridgeLineContext ctx,
            String timePrefix,
            Runnable syncBufferConfig
    ) {
        return processConsoleLine(line, ctx, timePrefix, syncBufferConfig, null);
    }

    public static List<LogSqlEntry> processConsoleLine(
            String line,
            BridgeLineContext ctx,
            String timePrefix,
            Runnable syncBufferConfig,
            java.util.function.Consumer<String> onErrorText
    ) {
        // M56 T-M56-02：增强接管闸门——agent 在线时桥不产出条目，回落即恢复。
        // T-AgentDialectLoop / T-AgentLogTime：接管期间无条件转入日志时间嗅探——把行喂给既有解析器
        // 提取「日志行原始时间」暂存 LogTimeCorrelator（键=占位符版 SQL 指纹），agent 事件入队前
        // 取回回填，面板「日志时间」在增强模式下不丢失。日志时间是**数据**：采集不再受「时间显示开
        // 关/时间源」这类渲染偏好控制（关掉显示只影响渲染，不影响条目字段），故此处零开关判断。
        //
        // T-CaptureCatchAll 券02：让位不再是二值——CaptureActivityFallback 在「agent 静默但控制台
        // 仍有 SQL 行」时自动解封（白名单漏驱动的双哑场景）。解封态下本函数直接走正常解析；让位态下
        // 嗅探产出的条目若恰是解封触发点，直接采用该条目（不重复喂解析器、不丢这一行）。
        if (SqlCaptureGate.paused.get()) {
            if (!CaptureActivityFallback.isActive()) {
                List<LogSqlEntry> sniffed = sniffLogTimestamp(line, ctx);
                if (CaptureActivityFallback.onConsoleLine(!sniffed.isEmpty(), CaptureActivityFallback.now())) {
                    return finishEntries(sniffed, line, ctx, timePrefix);
                }
                return List.of();
            }
        }
        // 1) 实时源码标记 → 暂存（不喂解析器，避免被当成 SQL）
        SourceMarkerData marker = SqlSourceMarker.parse(line);
        if (marker != null) {
            if (SqlSourceMarker.isInterceptorSelfPath(marker.file())) {
                // T-IntrUpg-01 防线：标记指向拦截器文件本身 = 存量旧件自位污染，丢弃不暂存。
                // 留痕含原路径/行号，远程排障定位污染拷贝件用。
                MARKER_DEFENSE_LOG.info("SqlLogConsoleBridge: 丢弃拦截器自位污染标记 "
                        + marker.file() + ":" + marker.line() + "（旧版生成文件，建议设置页重新注入升级）");
                return List.of();
            }
            ctx.pendingSource = marker;
            return List.of();
        }
        // 1.5) T-M55-02：SQL 执行报错旁路识别——词表廉价前置判定（O(词表×行长)，无正则），
        // 命中即聚合错误块并经 onErrorText 实时反向绑定缓冲最近条目（旁路记录，不改控制台输出、
        // 不影响三解析器主链：错误行随后照常喂解析器，行为与无错误行逐字一致）。
        // 续行判定先行：### /堆栈帧在块内视为续行（MyBatis ### 续行同时命中错误词表，不得重开块丢首行）。
        if (ctx.pendingError != null && SqlErrorLineRecognizer.isContinuationLine(line)) {
            StringBuilder sb = ctx.pendingError;
            int newlineCount = 0;
            for (int i = 0; i < sb.length(); i++) {
                if (sb.charAt(i) == '\n') newlineCount++;
            }
            if (sb.length() < SqlErrorLineRecognizer.MAX_ERROR_TEXT_LENGTH
                    && newlineCount < SqlErrorLineRecognizer.MAX_ERROR_STACK_LINES) {
                sb.append('\n').append(line.trim());
                if (onErrorText != null) onErrorText.accept(sb.toString());
            }
        } else if (SqlErrorLineRecognizer.isErrorLine(line)) {
            ctx.pendingError = new StringBuilder(SqlErrorLineRecognizer.firstLineText(line));
            if (onErrorText != null) onErrorText.accept(ctx.pendingError.toString());
        } else if (!line.isBlank()) {
            // 块外普通行（含空行放行，容忍堆栈间空行）结束当前错误块
            ctx.pendingError = null;
        }
        // 2) 喂行到四个解析器（各自忽略不匹配的行；配置快照随设置变更事件刷新，逐行零同步成本）
        // F58（T-PerfSweep18 压轴）：线程 token 每行只提一次下传——原四解析器各自 bucketFor(line)
        // 重提同一 token（同一行最多 4 次 substring 窗口+级别锚正则+方括号扫描）。
        String threadToken = LogThreadRouter.threadTokenOf(line);
        ctx.myBatisParser.feed(line, threadToken);
        if (ctx.hibernateEnabled()) {
            ctx.hibernateParser.feed(line, threadToken);
        }
        ctx.jdbcParser.feed(line, threadToken);
        if (ctx.shardingEnabled) {
            ctx.shardingParser.feed(line, threadToken);
        }
        // 2.5) T-ConsolePendingFlush：喂行后冲刷**已静默**桶的待产出条目。放在喂行之后是有意的——本行刚
        // 触达它自己的桶（活动时间刷新），该桶因此不会出现在冲刷名单里，本行所属语句的参数行不会被截断；
        // 被冲刷的只有「别的线程静默 ≥1s 的语句」（JdbcTemplate 无 TRACE 参数行 + 线程按请求新建时，
        // 那些语句永无同线程后继行）。冲刷出的条目与本次喂行产出的条目由下方同一次 takeEntries() 一并交出。
        // R10：ShardingSphere 解析器同款接入（其条目只在下一条 Logic 行闭合，静默冲刷是低频访问的兜底）。
        ctx.jdbcParser.flushSettled();
        if (ctx.shardingEnabled) {
            ctx.shardingParser.flushSettled();
        }
        List<LogSqlEntry> taken = new ArrayList<>();
        taken.addAll(ctx.myBatisParser.takeEntries());
        taken.addAll(ctx.hibernateParser.takeEntries());
        taken.addAll(ctx.jdbcParser.takeEntries());
        taken.addAll(ctx.shardingParser.takeEntries());
        if (taken.isEmpty()) {
            return List.of();
        }
        return finishEntries(taken, line, ctx, timePrefix);
    }

    /**
     * T-CaptureCatchAll 券02：条目补全（耗时 + 源码位置）——正常解析路径与「解封瞬间采用嗅探条目」
     * 共用同一出口，避免两处各抄一份补全逻辑（两处漂移会让解封首批条目丢耗时/源码）。
     */
    private static List<LogSqlEntry> finishEntries(
            List<LogSqlEntry> taken,
            String line,
            BridgeLineContext ctx,
            String timePrefix
    ) {
        // 3) 耗时 + 源码位置（标记不可用时 sourceFile/sourceLine 为 null，回退表名搜索）
        // 解析策略：优先从日志行文本解析（ExecutionTimeParser），命中则统一填入；
        // 未命中时对每个条目尝试从 rawSql/restoredSql 解析（LogDurationParser，覆盖 druid/p6spy 格式）
        Long lineDuration = ExecutionTimeParser.parse(line, timePrefix);
        SourceMarkerData src = ctx.pendingSource;
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry e : taken) {
            // F-44-7：MP 分页 COUNT 标记逐条判定（T-LogDedupeMpMark）——批次是「同一行喂入产出的全部条目」，
            // named 行触发 closePending 时批内常有两条（闭合的 prepared 副本 + 本条 named），批级 any 会把
            // 与 COUNT 同批的数据查询一并误标（且连带被判 NoSource 丢失源码跳转）。源码标记优先级不变。
            SourceMarkerData eSrc = src;
            if (eSrc == null && e.restoredSql().stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT COUNT(")) {
                eSrc = SourceMarkers.mpPaginationCount();
            }
            Long duration = lineDuration != null ? lineDuration : LogDurationParser.parse(e);
            String sourceFile = eSrc != null ? eSrc.file() : e.sourceFile();
            int sourceLine = eSrc != null ? eSrc.line() : e.sourceLine();
            String mapperStatement = eSrc != null ? eSrc.mapperStatement() : e.mapperStatement();
            String requestTag = eSrc != null ? eSrc.requestTag() : e.requestTag();
            result.add(new LogSqlEntry(
                    e.rawSql(),
                    e.restoredSql(),
                    e.params(),
                    e.timestamp(),
                    e.logTimestamp(),
                    e.summary(),
                    e.source(),
                    duration != null ? duration : e.executionTimeMs(),
                    sourceFile,
                    sourceLine,
                    e.sourceCandidates(),
                    e.candidateClasses(),
                    e.stackDigest(),
                    e.threadName(),
                    e.sequenceNumber(),
                    mapperStatement,
                    e.errorText(),
                    requestTag,
                    e.updateCount(),
                    e.databaseName(),
                    e.dialectName(),
                    // master 为 e.copy(...)——未触及字段须原样携带（actualSql 同权透传）
                    e.actualSql(),
                    e.captureProvenance()));
        }
        if (ctx.pendingSource != null) {
            ctx.pendingSource = null;
        }
        return result;
    }

    /**
     * T-AgentDialectLoop / T-AgentLogTime：增强接管期间的日志时间嗅探——复用既有三解析器的配对/时间解析
     * （零新解析逻辑），产出的条目不进面板、仅把「[logTimeKey] → 日志时间」暂存 LogTimeCorrelator
     * （参数值序列作次级判别签名）。
     * 成本口径：每行 3 次 contains 门控（解析器既有门），每条 SQL 一次条目构造 + 指纹，
     * 条目即弃零驻留；源码标记行跳过（条目已弃，无需归属）。
     *
     * <p>T-CaptureCatchAll 券02：返回本行解析出的条目——「控制台有 SQL 行」的实证信号由调用方喂给
     * {@link CaptureActivityFallback}；解封触发的那一行直接采用这些条目（不再重复喂解析器）。</p>
     */
    private static List<LogSqlEntry> sniffLogTimestamp(String line, BridgeLineContext ctx) {
        // F58（T-PerfSweep18 压轴）：线程 token 每行只提一次下传——原四解析器各自 bucketFor(line)
        // 重提同一 token（同一行最多 4 次 substring 窗口+级别锚正则+方括号扫描）。
        String threadToken = LogThreadRouter.threadTokenOf(line);
        ctx.myBatisParser.feed(line, threadToken);
        if (ctx.hibernateEnabled()) {
            ctx.hibernateParser.feed(line, threadToken);
        }
        ctx.jdbcParser.feed(line, threadToken);
        if (ctx.shardingEnabled) {
            ctx.shardingParser.feed(line, threadToken);
        }
        List<LogSqlEntry> taken = new ArrayList<>();
        taken.addAll(ctx.myBatisParser.takeEntries());
        taken.addAll(ctx.hibernateParser.takeEntries());
        taken.addAll(ctx.jdbcParser.takeEntries());
        taken.addAll(ctx.shardingParser.takeEntries());
        for (LogSqlEntry e : taken) {
            // 无时间 StdOut 的 timestamp 是 IDE 解析时刻，不能冒充应用日志时间——不入册。
            if (e.logTimestamp() == null) {
                continue;
            }
            LogTimeCorrelator.put(
                    LogSqlModelsKt.logTimeKey(e),
                    e.logTimestamp(),
                    LogSqlModelsKt.logTimeParamSignature(e.params()));
        }
        return taken;
    }

    /** 组件树中查找实现 `com.intellij.execution.ui.ConsoleView` 接口的组件（接口名匹配，无编译期引用）。 */
    public static Object findConsoleView(Component component) {
        if (component == null) {
            return null;
        }
        if (implementsConsoleView(component.getClass())) {
            return component;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                Object r = findConsoleView(child);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 类（含父类/父接口链）是否实现 ConsoleView 接口（接口名匹配）。 */
    private static boolean implementsConsoleView(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        for (Class<?> it : clazz.getInterfaces()) {
            if (CONSOLE_VIEW_IFACE_NAME.equals(it.getName())) {
                return true;
            }
        }
        for (Class<?> it : clazz.getInterfaces()) {
            if (implementsConsoleView(it)) {
                return true;
            }
        }
        Class<?> sup = clazz.getSuperclass();
        return sup != null && implementsConsoleView(sup);
    }

    private static final String CONSOLE_VIEW_IFACE_NAME = "com.intellij.execution.ui.ConsoleView";
}
