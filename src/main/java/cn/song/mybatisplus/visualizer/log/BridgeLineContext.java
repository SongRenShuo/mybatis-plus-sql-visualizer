package cn.song.mybatisplus.visualizer.log;

import java.util.function.Supplier;

/**
 * 每个已挂载控制台持有的一组解析器 + 暂存源码位置（F-44-6）。
 * 解析器跨行保持配对状态（MyBatis 的 Preparing/Parameters 跨多行），故须按 console 持久化。
 * T-LogPerf-04：配置快照在挂载/设置变更时经 {@link SqlLogConsoleBridge#refreshConsoleContexts} 写入，
 * 逐行不再调 configProvider 同步（字段保留供测试直接构造）。
 */
final class BridgeLineContext {
    final MyBatisLogParser myBatisParser;
    final HibernateLogParser hibernateParser;
    final JdbcTemplateLogParser jdbcParser;
    /**
     * ShardingSphere 第四解析器（R10 T-ShardingSphereCompat，循三解析器并列先例）。
     * 默认值只为既有测试构造点零改动（8 处直构 BridgeLineContext 的用例不传即得新实例）；
     * 生产两处（EP EpLineFilter / 桥 attachFilter）<b>显式传入</b>。
     */
    final ShardingSphereLogParser shardingParser;
    final Supplier<ParserConfig> configProvider;
    SourceMarkerData pendingSource;
    /** Hibernate 喂行开关（快照同步自 ParserConfig.hibernateEnabled）。 */
    boolean hibernateEnabled;
    /** ShardingSphere 喂行开关（快照同步自 ParserConfig.shardingEnabled；R10 D3=A 默认开）。 */
    boolean shardingEnabled;

    /**
     * T-M55-02：正在聚合的执行报错文本块（错误行起，堆栈/`###` 续行追加；null = 块外）。
     * 逐行累积、经 processConsoleLine 的 onErrorText 回调实时绑定缓冲最近条目——
     * 最终绑定文本为完整块（首行 + 聚合堆栈），中间态被后一次绑定覆盖。
     */
    StringBuilder pendingError;

    /**
     * T-ConsolePendingFlush：本上下文当前有效的 filter 挂载代际号（{@code SqlLogConsoleBridge.attachFilter}
     * 每次挂载 +1 写入）。旧代际的 filter 代理凭它识别自身已作废——2026.2 无单 filter 移除，旧代理摘不下来。
     */
    long generation = 0L;

    /** T-PreviewFlash：重放守卫（每 console 独立；editor 不可得时降级为恒 Process）。 */
    final ConsoleReplayGuard replayGuard = new ConsoleReplayGuard();

    /** 控制台文档 modificationStamp 读取函数（editor 不可得时为 null）。 */
    Supplier<Long> docStampReader;

    /** 控制台首行文本读取函数（混合态判定用；editor 不可得时为 null）。 */
    Supplier<String> line0Reader;

    /**
     * 控制台文档总长度读取函数（券 G2 重置判定用；editor/文档不可得时为 null）。
     *
     * <p>只在守卫 stamp 变分支惰性求值（流式/纯重放热路径零反射调用）；复用 attachReplayGuard
     * 已解析的 {@code getCharsSequence} 句柄取 {@code .length()}——免拷贝视图的长度读，无全文档扫描成本。</p>
     */
    java.util.function.Supplier<Integer> docLengthReader;

    /**
     * 重放丢弃行累计计数（T-RA-03：面板链路诊断行经 {@link SqlLogConsoleBridge#getReplayDropCount()} 读取）。
     *
     * <p>AtomicLong 而非普通 long：自增在控制台出线程（filter 回调），读取在 EDT（诊断定时器），
     * 普通字段无可见性保证（JIT 可长期持寄存器值）——诊断计数一旦读到陈旧 0 就恰好复现
     * 「守卫误丢行却无证据」的原始故障，故与 {@link SqlLogConsoleBridge#consoleLinesSeen} 同口径。
     */
    final java.util.concurrent.atomic.AtomicLong replayDropCount = new java.util.concurrent.atomic.AtomicLong();

    /** 默认构造形态（shardingParser 取新实例、shardingEnabled 取默认 true）——对齐 Kotlin 默认参数。 */
    BridgeLineContext(MyBatisLogParser myBatisParser, HibernateLogParser hibernateParser,
                      JdbcTemplateLogParser jdbcParser, Supplier<ParserConfig> configProvider,
                      boolean hibernateEnabled) {
        this(myBatisParser, hibernateParser, jdbcParser, new ShardingSphereLogParser(),
                configProvider, hibernateEnabled, true);
    }

    /** 全参构造（生产两处显式传入 shardingParser/shardingEnabled）。 */
    BridgeLineContext(MyBatisLogParser myBatisParser, HibernateLogParser hibernateParser,
                      JdbcTemplateLogParser jdbcParser, ShardingSphereLogParser shardingParser,
                      Supplier<ParserConfig> configProvider, boolean hibernateEnabled, boolean shardingEnabled) {
        this.myBatisParser = myBatisParser;
        this.hibernateParser = hibernateParser;
        this.jdbcParser = jdbcParser;
        this.shardingParser = shardingParser;
        this.configProvider = configProvider;
        this.pendingSource = null;
        this.hibernateEnabled = hibernateEnabled;
        this.shardingEnabled = shardingEnabled;
    }

    boolean hibernateEnabled() {
        return hibernateEnabled;
    }

    boolean shardingEnabled() {
        return shardingEnabled;
    }
}
