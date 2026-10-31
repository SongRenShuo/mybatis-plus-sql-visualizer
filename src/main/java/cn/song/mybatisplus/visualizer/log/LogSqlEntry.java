package cn.song.mybatisplus.visualizer.log;

import java.util.List;

/**
 * 日志 SQL 条目模型（T-M31）：一条 SQL 语句 + 其参数列表。
 *
 * 来源：MyBatis / Hibernate / JdbcTemplate 控制台日志，经解析器配对、LogSqlRestorer 还原后进入面板。
 */
public record LogSqlEntry(
    /** 原始 SQL 文本（未替换占位符）。 */
    String rawSql,
    /** 还原后的可执行 SQL（参数已替换；无法替换的 ? 保留）。 */
    String restoredSql,
    /** 参数列表（与 SQL 中 ? 按序对应）。 */
    List<LogSqlParam> params,
    /** 捕获时间戳。 */
    long timestamp,
    /** 日志原始时间戳（从日志行中解析，未解析到则为 null）。 */
    Long logTimestamp,
    /**
     * 摘要（列表展示用，智能截断：在第一行末尾或 MAX_SUMMARY_LENGTH 处取较小值，
     * 避免 mid-line 截断导致 multiline SQL 列表展示不完整）。
     */
    String summary,
    /** 条目来源（MyBatis / Hibernate / 未知）。 */
    SqlSource source,
    /**
     * 执行耗时（毫秒）；null 表示未解析到耗时信息。
     */
    Long executionTimeMs,
    /**
     * 产生该 SQL 的源文件路径（相对项目根目录）。
     * null 表示未知（旧版 F-43 保存的条目无此信息）。
     */
    String sourceFile,
    /**
     * 产生该 SQL 的源码行号（0 表示未知）。
     */
    int sourceLine,
    /**
     * T-AgentSourceJump：源码位置候选（栈序 [file, line]；仅 agent 通道产出，控制台通道/历史读回为空）。
     * 主位置在工程内解析失败时，跳转按候选序取首个可解析项——候选全部来自本次执行的真实调用栈，
     * 非表名等启发式（不会误导）。
     */
    List<kotlin.Pair<String, Integer>> sourceCandidates,
    /**
     * T-SourceJumpFunnel：候选帧类名（与 sourceCandidates 栈序对齐，旧 agent 事件可能缺类名列 → 空）。
     * 展示性字段（选帧弹层/NotFound 提示），body 内属性不参与 equals/hashCode——但 sourceCandidates
     * 在主构造里，本字段也在主构造，须评估 equals 影响：去重走 dedupeKey（惰性指纹），data class equals
     * 变化不影响面板去重口径（T-CrudColor 同理定案）。
     */
    List<String> candidateClasses,
    /**
     * T-SourceJumpFunnel：调用栈快照「类#方法:行 ← …」（仅 agent 通道；详情面板人工往上追踪用）。
     */
    String stackDigest,
    /**
     * T-StackBizFrame S3：产生该 SQL 的线程名（仅 agent 通道）。调用栈弹层标题展示，
     * 让「调度线程 / 异步池 / 请求线程」一眼可辨（同类误报的第一判别量）。
     * null = 未知（桥通道 / 旧条目 / 旧日志文件），标题退回纯「调用栈」。
     */
    String threadName,
    /**
     * T-LogRowNumber：会话内捕获序号（「SQL 日志」列表行号）。由 SqlLogEntryBuffer.addEntry
     * 成功入队时分配（单调递增；去重丢弃/暂停/过滤关键字命中的条目不占号），
     * 项目启动（processStarted）或手动清空列表时归零。0 表示无行号
     * （历史文件读回、日志转 SQL round-trip 产出的条目）。
     */
    long sequenceNumber,
    // ---------- M56 T-M56-00：agent 通道结构化字段（桥通道经标记行第 4/5 段与 pd 载荷扩展部分承接） ----------
    /**
     * mapper 语句 id（如 {@code com.example.mapper.UserMapper.selectById}）。
     * agent 通道从 MyBatis Executor 直取；桥通道来自源码标记第 4 段（拦截器 {@code ms.getId()}）。
     * null 表示未知（旧条目 / 非 MyBatis 来源）。
     */
    String mapperStatement,
    /**
     * 执行报错（结构化直取：异常类名 + 消息，堆栈截断）；null 表示执行成功。
     * agent 通道在执行点直捕；桥通道暂无对应日志形态（T-M56-05 前保持 null）。
     */
    String errorText,
    /**
     * 请求级聚合标签（T-M56-07 请求聚合预留）：同一次请求的多条 SQL 共享同值，
     * 聚焦页签据此分组。桥通道来自源码标记第 5 段（拦截器 MDC 兜底）；null 表示无标签。
     */
    String requestTag,
    /**
     * 影响行数（agent 通道 {@code getUpdateCount} 直取，DML 语义）；null 表示未知（查询 / 旧条目）。
     */
    Long updateCount,
    /**
     * 目标数据库标注（T-AgentDialectLoop 起，R32 弱形态升级：agent 通道由连接 URL 解析——
     * 方言显示名 · url 解析连接事实，如 "MySQL · jpa_demo@localhost:3306"、"DM（达梦） · localhost:5236"；
     * 只有方言可辨或只有连接事实可解析时输出其一）。解析与凭据剔除在
     * {@code cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects.badgeName}（user/password 参数、
     * Oracle thin 登录前缀解析期剔除，凭据串绝不入本字段）。
     * null 表示未知（桥通道 / 旧版 agent / 畸形 URL）。展示性字段，不入去重指纹；
     * 自动存档/读回按自由文本回环（SqlLogAutoSaver/SqlLogFileReader，格式无关）。
     */
    String databaseName,
    /**
     * T-DialectInferFix：本次执行连的那条连接的<b>方言名</b>（{@code cn.song.mybatisplus.visualizer.sql.DialectRegistry}
     * 命名空间，如 {@code PostgreSQL}；不是 {@link #databaseName} 那种展示串）。
     *
     * <p>与 {@link #databaseName} 同源（agent 事件的 {@code connect} URL），但<b>取的是渲染用的命名空间</b>：
     * {@code cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects} 的显示名不能直接喂
     * {@code DialectRegistry.byName}（HSQL/GBASE 不在注册表里、命名口径也不同）。</p>
     *
     * <p>用途：日志/历史双击预览据此把「这条 SQL 当时跑在哪个库」当作 EXACT 方言证据
     * （O-145 方案第 2 条），不再让预览回落 MySQL。null = 未知（桥通道 / 旧版 agent / 旧条目）。
     * 随 pd 载荷键 {@code dl} 落盘（仅非空落键，旧读端忽略未知键）。</p>
     */
    String dialectName,
    /**
     * Actual SQL 折叠附注（R10 T-ShardingSphereCompat，D1=A）：{@link SqlSource#SHARDINGSPHERE}
     * 条目的 {@code Actual SQL:} 段原文（多分片逐行、换行连接）；其余来源恒 null。
     * Logic 主条目 + Actual 折叠的模型承载——面板对照渲染（折叠态样式）等真实语料（V36）。
     * 展示性字段：随 pd 载荷键 {@code a} 落盘（仅非默认落键），不入去重指纹。
     */
    String actualSql,
    /** 捕获通道；旧条目和未打标的生产者保持 UNKNOWN。 */
    CaptureProvenance captureProvenance
) {
    /** 摘要最大长度。 */
    private static final int MAX_SUMMARY_LENGTH = 160;

    /**
     * Kotlin data class 默认参语义还原：{@code summary} 在 master 侧是带默认值的构造参
     * （{@code = run { …restoredSql… }}）——调用方传 {@code null}（Kotlin 侧省略该参经 Java 桥、
     * 或 Java 侧 20 参规范构造器直传 null）时按 master 口径现算，而非携带 null 下渗到渲染链
     * （{@code withTerminator(null)} NPE，k2j 首轮 testShards 46 败根因）。
     * 显式传非 null 时原样保留（与 Kotlin「传了实参就用实参」一致）。
     */
    public LogSqlEntry {
        if (captureProvenance == null) captureProvenance = CaptureProvenance.UNKNOWN;
        if (summary == null) {
            summary = summarizeSql(restoredSql);
        }
    }

    /**
     * T-CrudColor/T-ListMode 缓存侧表（Java record 无实例字段——Kotlin data class 体属性的
     * Java 等价承载）：键为条目（record 值相等语义），弱键随条目 GC。缓存内容（statementType、
     * 行片段）均由条目组件派生，值相等的条目共享缓存结果语义完全等价。
     * 仅 EDT 渲染路径读写，synchronized 包装仅作跨面板安全垫。
     */
    private static final java.util.Map<LogSqlEntry, StatementType> STATEMENT_TYPE_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 三指纹侧表缓存（STATEMENT_TYPE_CACHE 同款 WeakHashMap 形态）：master 侧三键是
     * {@code by lazy} 惰性字段——每条只算一次；Java record 无实例字段承载，纯函数每次现算
     * 在结构聚类排序（LogPipelinePerfTest 实测 684ms 超预算 120ms）与屏蔽筛选热路径上不可接受。
     * 键为条目（record 值相等），弱键随条目 GC；指纹均由组件派生，值相等条目共享结果语义等价。
     */
    private static final java.util.Map<LogSqlEntry, String> DEDUPE_KEY_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<LogSqlEntry, String> SUPPRESS_KEY_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<LogSqlEntry, String> STRUCTURE_KEY_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 行片段缓存载荷（片段 + 模式一并存取，保证两值一致）。 */
    private record LineFragmentCacheEntry(List<List<SqlSemanticFragment>> fragments, SqlListDisplayMode mode,
                                          Boolean masked) {
    }

    private static final java.util.Map<LogSqlEntry, LineFragmentCacheEntry> LINE_FRAGMENT_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 头部行静态段缓存载荷（券01 T-PerfSweep27-01：静态段 + 来源段 + 缓存键一并存取）。 */
    private record HeaderCacheEntry(List<SqlTextFragment> core, List<SqlTextFragment> source,
                                     HeaderStaticCacheKey key) {
    }

    /**
     * 券01（T-PerfSweep27-01）头部行静态段/来源段条目级缓存侧表：序号/时间/耗时/库/MCP/行数与
     * 来源片段只依赖条目不可变字段，每次可见格重绘都重算属浪费——对照上方多行语义片段
     * 缓存先例放条目侧（EDT 单线程读写，无并发面）。
     */
    private static final java.util.Map<LogSqlEntry, HeaderCacheEntry> HEADER_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** R60：脱敏摘要缓存（完整 SQL 先打码再截断，避免旧摘要在引号中间截断后漏打码）。 */
    private static final java.util.Map<LogSqlEntry, String> MASKED_SUMMARY_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public String cachedMaskedSummary() {
        return MASKED_SUMMARY_CACHE.get(this);
    }

    /** Kotlin {@code var cachedMaskedSummary} 的 JVM 读取器等价入口。 */
    public String getCachedMaskedSummary() {
        return cachedMaskedSummary();
    }

    public void setCachedMaskedSummary(String maskedSummary) {
        if (maskedSummary == null) {
            MASKED_SUMMARY_CACHE.remove(this);
        } else {
            MASKED_SUMMARY_CACHE.put(this, maskedSummary);
        }
    }

    /**
     * T-CrudColor：条目语句类型（渲染热路径缓存，惰性计算一次）。
     *
     * 由 {@link LogStatementClassifier#classify(String)} 得出——与类型过滤弹窗<b>同一口径</b>，
     * 因此「筛出来的 SELECT 行不会被标成红色」这一自洽性天然成立。
     *
     * 之所以缓存而非每次现算：classify 内部 {@code split("\\s+")}
     * <b>每次调用都重新编译 Pattern</b>，渲染路径按 cell 调用会把该开销放大到
     * 「每次重绘 × 可见行数」（滚动/悬停/选中/resize 均触发重绘）。
     *
     * 与 dedupeKey 同属 body 内属性——<b>不参与 record 的 equals / hashCode</b>，
     * 故新增该字段不影响既有去重与集合语义（T-LogRowNumber 的 sequenceNumber 教训：
     * 构造函数内新增字段会改变 equals，此处不会）。
     */
    public StatementType statementType() {
        StatementType t = STATEMENT_TYPE_CACHE.get(this);
        if (t == null) {
            t = LogStatementClassifier.classify(restoredSql);
            STATEMENT_TYPE_CACHE.put(this, t);
        }
        return t;
    }

    /** Kotlin {@code val statementType} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public StatementType getStatementType() {
        return statementType();
    }

    /**
     * T-ListMode：多行模式（KEYWORD_WRAP/FORMATTED）的行片段缓存：可变行高下每次布局状态更新会对全部条目
     * 逐条调渲染器取首选尺寸（T-LogListFreeze-04 的 O(N) 因子），缓存词法片段后测量只剩轻量
     * 组件布局。SINGLE_LINE 不走缓存（固定行高 + 渲染直读，主题切换实时生效）。
     */
    public List<List<SqlSemanticFragment>> cachedListLineFragments() {
        LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
        return e != null ? e.fragments() : null;
    }

    /** Kotlin {@code var cachedListLineFragments} 的 JVM 读取器等价入口（属性名读取用）。 */
    public List<List<SqlSemanticFragment>> getCachedListLineFragments() {
        return cachedListLineFragments();
    }

    public void setCachedListLineFragments(List<List<SqlSemanticFragment>> value) {
        synchronized (LINE_FRAGMENT_CACHE) {
            LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
            LINE_FRAGMENT_CACHE.put(this, new LineFragmentCacheEntry(value, e != null ? e.mode() : null,
                    e != null ? e.masked() : null));
        }
    }

    /** 行片段缓存对应的显示模式——读取处按当前模式比对，模式变更自动失效（无需显式清缓存）。 */
    public SqlListDisplayMode cachedListLineFragmentsMode() {
        LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
        return e != null ? e.mode() : null;
    }

    /** Kotlin {@code var cachedListLineFragmentsMode} 的 JVM 读取器等价入口（属性名读取用）。 */
    public SqlListDisplayMode getCachedListLineFragmentsMode() {
        return cachedListLineFragmentsMode();
    }

    /** 缓存对应的脱敏态（R60 T-LaneB D96）：读取处按当前 displayMaskEnabled 比对——脱敏开关
     * 翻转后旧缓存（打码/未打码形态）自动失效重渲染，防脏缓存（V112.3 附带发现）。 */
    public Boolean cachedListLineFragmentsMasked() {
        LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
        return e != null ? e.masked() : null;
    }

    /** Kotlin {@code var cachedListLineFragmentsMasked} 的 JVM 读取器等价入口。 */
    public Boolean getCachedListLineFragmentsMasked() {
        return cachedListLineFragmentsMasked();
    }

    public void setCachedListLineFragmentsMasked(Boolean masked) {
        synchronized (LINE_FRAGMENT_CACHE) {
            LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
            LINE_FRAGMENT_CACHE.put(this, new LineFragmentCacheEntry(e != null ? e.fragments() : null,
                    e != null ? e.mode() : null, masked));
        }
    }

    public void setCachedListLineFragmentsMode(SqlListDisplayMode value) {
        synchronized (LINE_FRAGMENT_CACHE) {
            LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
            LINE_FRAGMENT_CACHE.put(this, new LineFragmentCacheEntry(e != null ? e.fragments() : null, value,
                    e != null ? e.masked() : null));
        }
    }

    /**
     * 头部行静态段缓存（券01：序号/时间/耗时/库/MCP/影响行数；恒非选中态属性）。
     * 中间的 ⨉去重/×N 同结构徽章不进缓存（输入是面板级快照 map，随流式灌入变化）。
     */
    public List<SqlTextFragment> cachedHeaderCore() {
        HeaderCacheEntry e = HEADER_CACHE.get(this);
        return e != null ? e.core() : null;
    }

    /** Kotlin {@code var cachedHeaderCore} 的 JVM 读取器等价入口（属性名读取用）。 */
    public List<SqlTextFragment> getCachedHeaderCore() {
        return cachedHeaderCore();
    }

    public void setCachedHeaderCore(List<SqlTextFragment> core) {
        synchronized (HEADER_CACHE) {
            HeaderCacheEntry e = HEADER_CACHE.get(this);
            HEADER_CACHE.put(this, new HeaderCacheEntry(core, e != null ? e.source() : null,
                    e != null ? e.key() : null));
        }
    }

    /** 来源段缓存（`[文件.java:行号]`；无源码位置为空列表），与 {@link #cachedHeaderCore()} 同键生命周期。 */
    public List<SqlTextFragment> cachedHeaderSource() {
        HeaderCacheEntry e = HEADER_CACHE.get(this);
        return e != null ? e.source() : null;
    }

    /** Kotlin {@code var cachedHeaderSource} 的 JVM 读取器等价入口（属性名读取用）。 */
    public List<SqlTextFragment> getCachedHeaderSource() {
        return cachedHeaderSource();
    }

    public void setCachedHeaderSource(List<SqlTextFragment> source) {
        synchronized (HEADER_CACHE) {
            HeaderCacheEntry e = HEADER_CACHE.get(this);
            HEADER_CACHE.put(this, new HeaderCacheEntry(e != null ? e.core() : null, source,
                    e != null ? e.key() : null));
        }
    }

    /**
     * 缓存键（{@link HeaderStaticCacheKey}）：showDuration+时间戳设置+角标细节+
     * 慢查询阈值——键含设置值，任一设置变更自动失效重算（显示格式严格跟随设置，免失效钩子）。
     */
    public HeaderStaticCacheKey cachedHeaderKey() {
        HeaderCacheEntry e = HEADER_CACHE.get(this);
        return e != null ? e.key() : null;
    }

    /** Kotlin {@code var cachedHeaderKey} 的 JVM 读取器等价入口（属性名读取用）。 */
    public HeaderStaticCacheKey getCachedHeaderKey() {
        return cachedHeaderKey();
    }

    public void setCachedHeaderKey(HeaderStaticCacheKey key) {
        synchronized (HEADER_CACHE) {
            HeaderCacheEntry e = HEADER_CACHE.get(this);
            HEADER_CACHE.put(this, new HeaderCacheEntry(e != null ? e.core() : null,
                    e != null ? e.source() : null, key));
        }
    }


    /**
     * T-Perf-19（自 dev 移植）：去重键指纹（sqlFingerprint 惰性计算一次）——dedupeCounts 不再以
     * 完整 SQL 原文为键（还原参数文本可达数 KB/条，长会话数十~上百 MB 永久驻留）。
     * T-LogDedupeMpMark：指纹前先 normalizeSqlWhitespace——同一语句的双路日志副本（named 行 trim 过 /
     * prepared 行尾随空白原样保留）仅差首尾空白，原文指纹判重永不命中。
     * （record 无实例字段，侧表缓存承载「每条只算一次」——master 的 by lazy 等价。）
     */
    public String dedupeKey() {
        String cached = DEDUPE_KEY_CACHE.get(this);
        if (cached == null) {
            cached = LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace(restoredSql));
            DEDUPE_KEY_CACHE.put(this, cached);
        }
        return cached;
    }

    /** Kotlin {@code val dedupeKey} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public String getDedupeKey() {
        return dedupeKey();
    }

    /**
     * T-SqlSuppressUx（20260924 用户走查返修）：屏蔽匹配键 = {@link #dedupeKey()} 口径追加<b>尾部分号剥离</b>
     * ——规则粘贴态常带收尾 {@code ;} 而日志还原链不带（用户实证「已拦截 0 次」根因，多行/单行空白差异
     * 本就被折叠）。与 dedupeKey 同属 body 属性不参与 equals/hashCode；仅在屏蔽规则集非空时被
     * 闸门/面板过滤触达（空集短路），无规则常态零成本。口径函数 = {@link LogSqlModelsKt#suppressFingerprint}
     * （规则侧 {@code SqlSuppressRuleManager} 同源，两侧必须同函数）。
     */
    public String suppressKey() {
        String cached = SUPPRESS_KEY_CACHE.get(this);
        if (cached == null) {
            cached = LogSqlModelsKt.suppressFingerprint(restoredSql);
            SUPPRESS_KEY_CACHE.put(this, cached);
        }
        return cached;
    }

    /** Kotlin {@code val suppressKey} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public String getSuppressKey() {
        return suppressKey();
    }

    /**
     * T-SqlSuppressUx 二段：结构匹配键（{@link LogSqlModelsKt#structureFingerprint}，字面量/数字参数化）
     * ——仅当某条<b>手动开启结构匹配</b>的规则的键集含本键时命中；与 {@link #suppressKey()} 同属 lazy 体属性，
     * 空集短路下无结构规则则零成本。
     */
    public String structureKey() {
        String cached = STRUCTURE_KEY_CACHE.get(this);
        if (cached == null) {
            cached = LogSqlModelsKt.structureFingerprint(restoredSql);
            STRUCTURE_KEY_CACHE.put(this, cached);
        }
        return cached;
    }

    /** Kotlin {@code val structureKey} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public String getStructureKey() {
        return structureKey();
    }

    /**
     * K2J 互操作：master 侧 {@code actualSql} 为带默认值的后置字段（Kotlin 默认参），历史 19 参
     * 位置构造形态（三解析器/桥通道等 Java 调用方）天然合法——Java 侧保留同形重载，
     * 新参取默认 null（与 20 参规范构造器同为 null 归一语义）。
     */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, long timestamp,
                       Long logTimestamp, String summary, SqlSource source, Long executionTimeMs,
                       String sourceFile, int sourceLine, List<kotlin.Pair<String, Integer>> sourceCandidates,
                       List<String> candidateClasses, String stackDigest, long sequenceNumber,
                       String mapperStatement, String errorText, String requestTag, Long updateCount,
                       String databaseName) {
        this(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source, executionTimeMs,
            sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, null, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, null, null, CaptureProvenance.UNKNOWN);
    }

    /** 保留新增归因前的完整构造签名（旧 20 参形态，旧生产者默认尚未归因）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, long timestamp,
                       Long logTimestamp, String summary, SqlSource source, Long executionTimeMs,
                       String sourceFile, int sourceLine, List<kotlin.Pair<String, Integer>> sourceCandidates,
                       List<String> candidateClasses, String stackDigest, long sequenceNumber,
                       String mapperStatement, String errorText, String requestTag, Long updateCount,
                       String databaseName, String actualSql) {
        this(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source, executionTimeMs,
            sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, null, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, null, actualSql, CaptureProvenance.UNKNOWN);
    }

    /**
     * K2J 互操作：master 新增 {@code threadName}（T-StackBizFrame S3，声明序插在 stackDigest 与
     * sequenceNumber 之间）与 {@code dialectName}（T-DialectInferFix，插在 databaseName 与 actualSql
     * 之间）取默认 null 的历史 21 参形态（Kotlin 默认参的 Java 等价），委托 23 参规范构造器。
     */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, long timestamp,
                       Long logTimestamp, String summary, SqlSource source, Long executionTimeMs,
                       String sourceFile, int sourceLine, List<kotlin.Pair<String, Integer>> sourceCandidates,
                       List<String> candidateClasses, String stackDigest, long sequenceNumber,
                       String mapperStatement, String errorText, String requestTag, Long updateCount,
                       String databaseName, String actualSql, CaptureProvenance captureProvenance) {
        this(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source, executionTimeMs,
            sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, null, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, null, actualSql, captureProvenance);
    }

    /** 仅原始/还原 SQL + 参数（其余取默认）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(), null,
            summarizeSql(restoredSql), SqlSource.UNKNOWN, null, null, 0, List.of(), List.of(), null, 0L,
            null, null, null, null, null, null, CaptureProvenance.UNKNOWN);
    }

    /** 含显式时间戳。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, long timestamp) {
        this(rawSql, restoredSql, params, timestamp, null,
            summarizeSql(restoredSql), SqlSource.UNKNOWN, null, null, 0, List.of(), List.of(), null, 0L,
            null, null, null, null, null, null, CaptureProvenance.UNKNOWN);
    }

    /** 含来源。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, SqlSource source) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(), null,
            summarizeSql(restoredSql), source, null, null, 0, List.of(), List.of(), null, 0L,
            null, null, null, null, null, null);
    }

    /** 含时间戳 + 源码位置 + 耗时（历史文件反解析）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params,
                       long timestamp, String sourceFile, int sourceLine, Long executionTimeMs) {
        this(rawSql, restoredSql, params, timestamp, null,
            summarizeSql(restoredSql), SqlSource.UNKNOWN, executionTimeMs, sourceFile, sourceLine, List.of(), List.of(), null, 0L,
            null, null, null, null, null, null);
    }

    /** T-LogRowNumber：以本条为原型返回带指定捕获序号的副本（Kotlin data class copy(sequenceNumber=…) 的 Java 等价）。 */
    public LogSqlEntry withSequenceNumber(long newSequenceNumber) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, newSequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, dialectName, actualSql, captureProvenance);
    }

    /** O-147：修订应用拷贝原语——rawSql 覆写（其余字段同 {@link #withSequenceNumber} 形态）。 */
    public LogSqlEntry withRawSql(String newRawSql) {
        return new LogSqlEntry(newRawSql, restoredSql, params, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, dialectName, actualSql, captureProvenance);
    }

    /** O-147：修订应用拷贝原语——restoredSql 覆写。 */
    public LogSqlEntry withRestoredSql(String newRestoredSql) {
        return new LogSqlEntry(rawSql, newRestoredSql, params, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, dialectName, actualSql, captureProvenance);
    }

    /** O-147：修订应用拷贝原语——params 覆写。 */
    public LogSqlEntry withParams(List<LogSqlParam> newParams) {
        return new LogSqlEntry(rawSql, restoredSql, newParams, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, dialectName, actualSql, captureProvenance);
    }

    /** O-147：修订应用拷贝原语——timestamp 覆写。 */
    public LogSqlEntry withTimestamp(long newTimestamp) {
        return new LogSqlEntry(rawSql, restoredSql, params, newTimestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, dialectName, actualSql, captureProvenance);
    }

    /** T-AgentLogTime：以本条为原型返回带日志原始时间的副本（copy(logTimestamp=…) 的 Java 等价）。 */
    public LogSqlEntry withLogTimestamp(Long newLogTimestamp) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, newLogTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, dialectName, actualSql, captureProvenance);
    }

    /** T-M55-02：以本条为原型返回带执行报错的副本（copy(errorText=…) 的 Java 等价；缓冲错误反向绑定用）。 */
    public LogSqlEntry withErrorText(String newErrorText) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, sequenceNumber,
            mapperStatement, newErrorText, requestTag, updateCount, databaseName, dialectName, actualSql, captureProvenance);
    }

    public LogSqlEntry withCaptureProvenance(CaptureProvenance provenance) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, threadName, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName, dialectName, actualSql, provenance);
    }

    public CaptureProvenance getCaptureProvenance() {
        return captureProvenance;
    }

    /** 智能截断摘要（包内可见：解析器/桥通道按 Kotlin data class 默认参数语义构造条目时复用同一口径）。 */
    public static String summarizeSql(String restoredSql) {
        int newlineIdx = restoredSql.indexOf('\n');
        int endIdx;
        if (newlineIdx >= 0 && newlineIdx < MAX_SUMMARY_LENGTH) {
            endIdx = newlineIdx;
        } else {
            endIdx = Math.min(restoredSql.length(), MAX_SUMMARY_LENGTH);
        }
        if (endIdx > 0 && endIdx < restoredSql.length()
                && Character.isHighSurrogate(restoredSql.charAt(endIdx - 1))
                && Character.isLowSurrogate(restoredSql.charAt(endIdx))) {
            endIdx--;
        }
        if (endIdx < restoredSql.length()) {
            return restoredSql.substring(0, endIdx) + "\u2026";
        }
        return restoredSql;
    }
}
