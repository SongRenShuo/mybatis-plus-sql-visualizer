package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 织入回调入口（§4.2 #6 classloader 契约）：被织入代码经 SystemClassLoader 反射调用本类。
 * 注意：本类的方法签名被织入代码硬编码（"onConnection"），改动须同步 ConnectWeaver。
 * 采集红线：任何异常自吞（钩子绝不影响宿主应用）；全局禁用 volatile 短路。
 * T-AgentProxyCL：自吞不等于无声——失败统一走 {@link #dropToRaw}（计数 + 一次性告警）。
 */
public final class SqlHookCallbacks {

    private static final AtomicLong DROP_FALLBACK = new AtomicLong();

    /**
     * 首次失败只告警一次：诊断可见性与日志洪水的取舍。失败形态（loader 选不对/驱动隐藏 agent 类）
     * 一旦发生就是**每条连接、每条语句**都失败——逐次 WARN 会在宿主应用日志里刷屏，比故障本身更糟；
     * 而「一次都不说」正是 T-AgentProxyCL 的成因（真机零诊断，只能靠只读代理跑真实驱动才钉住）。
     * 取中间：首次打印完整原因（类名 + 消息），此后只累加 {@link #fallbackCount()} 并**每满
     * {@link #SUMMARY_EVERY} 次打一行汇总**（T-PerfSweep6 T-PS6-08 / 性能报告发现 13：此前该计数
     * 零读取、宿主侧无任何出口，注释里的「读该计数即可判断」没有落点）。
     */
    private static final AtomicBoolean WARNED_ONCE = new AtomicBoolean();

    /**
     * 连接对象替换开关（T-ConnCastSafety，默认<b>关</b>，出厂不得替换宿主连接）。
     *
     * <p><b>为什么默认关。</b>连接替换＝把驱动 {@code connect} 返回值换成 JDK 动态代理，而 JDK 代理
     * 只能实现接口、无法伪装成具体类：宿主只要写 {@code (DmdbConnection) conn}、{@code (KbConnection) conn}
     * 这类到驱动实现类的强转，就必然抛 {@code ClassCastException: com.sun.proxy.$ProxyN cannot be cast to
     * dm.jdbc.driver.DmdbConnection}。这不是可修的边界条件，而是「接口代理」的构造性缺陷——一旦命中，
     * 崩的是宿主业务代码，agent 的 {@code catch (Throwable)} 自吞救不了（异常在宿主栈上抛出）。
     * 国产库（达梦/金仓/翰高/海量/GaussDB）宿主侧做实现类强转的现场并不少见，故采集红线升级为
     * 「<b>绝不在宿主侧替换 JDBC 对象</b>」：默认只登记 URL、原对象直返。
     *
     * <p><b>那还采得到吗。</b>采得到。SQL 采集主通道已是语句面/prepare 面字节码织入
     * （{@code StatementWeaver} + {@link cn.song.mybatisplus.sqlvis.agent.WeaveSpecs}）——它只往方法体里
     * 插回调，<b>不改任何对象身份</b>，MySQL/MariaDB 家族、PG/openGauss、Oracle、SQL Server、H2、达梦
     * 都走这条路，且 attach 路径对存量池连接同样生效（T-StatementWeave/T-DmWeave/T-PgWeave 的落点）。
     * 连接代理是更早的通道，唯一独有能力是「连接级 prepare 拦截」，而这已被 prepare 面覆盖。
     *
     * <p><b>何时需要打开。</b>仅当某驱动的语句实现类不在 {@code WeaveSpecs} 名单里（如 GBASE 8s、
     * 部分 SQLite/HSQLDB 形态），织入通道覆盖不到、只能靠连接代理时，用户显式加
     * {@code -Dsqlvis.agent.wrapConnection=true} 承担强转风险。默认值来自系统属性，进程内不再变，
     * 避免每条连接读一次属性；测试用 {@link #setWrapConnectionForTest} 显式开关。
     */
    private static final String WRAP_CONNECTION_PROP = "sqlvis.agent.wrapConnection";

    private static final boolean DEFAULT_WRAP_CONNECTION =
            Boolean.parseBoolean(System.getProperty(WRAP_CONNECTION_PROP, "false"));

    /** volatile：测试缝可改（生产初值即 DEFAULT_WRAP_CONNECTION，运行期无写入点）。 */
    private static volatile boolean wrapConnection = DEFAULT_WRAP_CONNECTION;

    /** 测试缝：null = 复位到系统属性默认值。 */
    public static void setWrapConnectionForTest(Boolean value) {
        wrapConnection = value != null ? value : DEFAULT_WRAP_CONNECTION;
    }

    /**
     * 连接出口回调（织入字节码在 connect 出口调用）：**一律包装**，停采只做逐调用短路。
     *
     * <p><b>为什么这里不看 {@code disabled}</b>（T-AgentConfigChannel T-08，2026-09-22 真机实证）：
     * 连接级跳过会让「停采期间建立的连接**永远不被包装**」——连接池（Hikari 等）复用同一批连接，
     * 于是用户「先跑应用、再点开始捕获」时一条都捕不到，停采→恢复同样失效（真机现场：
     * `disabled=false` 的 CONFIG 与 ACK 均已生效，业务窗口内事件数仍为 0）。设计红线本就是
     * 「禁用 = volatile 短路、**不卸载**」：钩子在位、逐调用零成本直返。
     * T-AgentResiduals R5 起该红线**只落在执行点**——{@code ConnectionHandler.invoke} 顶层的
     * {@code disabled} 短路（会让「停采期间 prepare、恢复后执行」的语句永久哑掉）已删除，
     * 连接/语句包装一律在位，唯一闸门是 {@link StatementHandler#invoke} 的逐调用短路。
     *
     * <p>包装失败的兜底不变：{@link #dropToRaw}（自吞 + 计数 + 一次性告警），宿主应用行为不受影响。
     */
    public static Object onConnection(Object rawConnection, String url) {
        try {
            // T-StatementWeave S4：connect 钩子记最近 URL——语句面归属链的粗粒度兜底源
            UrlResolver.noteConnectUrl(url);
            if (rawConnection == null) {
                return rawConnection;
            }
            if (!wrapConnection) {
                // T-ConnCastSafety：出厂不替换宿主连接对象（$Proxy 无法强转到 DmdbConnection 等实现类）。
                // URL 已在上面登记，采集交给语句面/prepare 面织入（不换对象）。
                return rawConnection;
            }
            if (ProxySupport.isOurProxy(rawConnection)) {
                return rawConnection; // 幂等：连接池复用/二次包装不叠加代理
            }
            final Connection proxied = ProxySupport.wrapConnection(rawConnection, url);
            if (proxied == null) {
                return dropToRaw("wrapConnection 返回空", null, rawConnection);
            }
            return proxied;
        } catch (Throwable t) {
            // 采集红线：catch(Throwable) 自吞——失败返回原连接；但必须可观测（T-AgentProxyCL）
            return dropToRaw("wrapConnection 抛异常", t, rawConnection);
        }
    }

    // ---------- T-StatementWeave：语句面/prepare 面织入回调（签名被 StatementWeaver 字节码硬编码，改动两侧同步） ----------

    /**
     * execute 族入口：返回共享 ExecutionScope 的 sequence token，0 = 停采/同对象重入。
     * scope 在投递之前记录身份、入口墙钟及 nanoTime；出口 token==0 直返。
     * WeaveGate 仅保留旧调用方兼容，新代理按对象身份仲裁，不屏蔽异对象嵌套。
     * 织入侧零分支：一切判定收在本回调，异常自吞返回 0。
     */
    public static long onStatementEnter(Object stmt) {
        try {
            if (stmt == null || AgentState.isDisabled() || WeaveGate.isProxyActive()) {
                return 0L;
            }
            final ExecutionScope scope = ExecutionScope.begin(stmt, "WOVEN");
            return scope == null ? 0L : scope.sequence;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * execute 族**出口**（每个 ARETURN/ATHROW/IRETURN/LRETURN 前统一切入，EXEC 与 BATCH 共用
     * 本签名——织入侧 BATCH 的 sql 槽传 null，批 SQL 由本回调按 op 名从登记表取并**读后即复位**
     * （F74：executeBatch 消费即清，对齐驱动批缓冲 JDBC 语义）；failure 非空 =
     * 异常路径，事件记 errorText 后原样重抛由织入侧完成）。SQL 解析：带参形态取 sqlParam；
     * 无参 PS 形态与 executeBatch 族取 [StatementRegistry]（attach 前 prepare 的 PS 无登记 =
     * 盲区，跳过不上报 + 计数，计划 §三 S3）。URL 归属链见 [UrlResolver]（S4）。
     * 签名被 StatementWeaver 字节码硬编码，改动两侧同步。
     */
    public static void onExecuteExit(Object stmt, String sqlParam, String op, long token,
                                     Object result, Throwable failure) {
        if (token == 0L || stmt == null) return;
        final ExecutionScope scope = ExecutionScope.find(token);
        if (scope == null) return;
        scope.failed = failure != null;
        try {
            String sql = sqlParam;
            String url = null;
            if (sql == null || sql.isEmpty()) {
                final StatementRegistry.Ctx ctx = StatementRegistry.getOrCountUnresolved(stmt);
                if (ctx == null) {
                    cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger.omitted();
                    return; // 盲区：attach 前 prepare 的 PS
                }
                if (isBatchOp(op) && ctx.batch.length() > 0) {
                    sql = ctx.batch.toString();
                    // F74：executeBatch 消费即复位——JDBC 语义驱动侧批缓冲在 executeBatch 完成后清空，
                    // 登记批串同口径（成功与异常出口同走此处，异常路径读串在先、复位紧随）；
                    // 不复位则残串跨批次累积：内存/组装耗时双无界，且下一批事件携带上一批残串。
                    ctx.batch.setLength(0);
                } else {
                    sql = ctx.sql;
                }
                url = ctx.url;
                if (sql == null || sql.isEmpty()) {
                    cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger.omitted();
                    return; // 登记在但无 SQL 可言（防御性跳过）
                }
            }
            if (url == null) {
                url = resolveUrl(stmt);
            }
            WovenEvents.emit(stmt, op, sql, result, failure, scope.startNanos, url, scope);
        } catch (Throwable t) {
            cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger.captureFailed();
        } finally {
            scope.close();
        }
    }

    private static boolean isBatchOp(String op) {
        return "executeBatch".equals(op) || "executeLargeBatch".equals(op);
    }

    /** addBatch(String) 出口：批串累积进登记表（代理层活跃时跳过——代理层自己累积，防双份）。 */
    public static void onAddBatchExit(Object stmt, String sql) {
        try {
            if (AgentState.isDisabled() || WeaveGate.isProxyActive() || sql == null || sql.isEmpty()) {
                return;
            }
            final StatementRegistry.Ctx ctx = StatementRegistry.batchCtxOf(stmt);
            if (ctx.batch.length() > 0) {
                ctx.batch.append(";\n");
            }
            ctx.batch.append(sql);
        } catch (Throwable t) {
            // 采集红线：自吞
        }
    }

    /**
     * clearBatch() 出口：清登记批串（F74：宿主清批而登记不清，清批前残串会串进下一批事件）。
     * 代理层活跃时跳过——批串本来就只在织入通道累积（onAddBatchExit 同款门禁），无账可清；
     * 无登记直接跳过，不为清账惰性建登记（batchCtxOf 的建账语义只属于 addBatch）。
     * 签名被 StatementWeaver 字节码硬编码，改动两侧同步。
     */
    public static void onClearBatchExit(Object stmt) {
        try {
            if (AgentState.isDisabled() || WeaveGate.isProxyActive()) {
                return;
            }
            StatementRegistry.clearBatchOf(stmt);
        } catch (Throwable t) {
            // 采集红线：自吞
        }
    }

    /**
     * setXxx **入口**（语句面织入，T-ConnCastSafety）：记录绑定参数。
     *
     * <p>为什么需要这条：参数捕获原本只存在于语句代理（连接被包装后才有），而连接替换已在
     * 出厂关闭（{@code $Proxy} 无法强转到驱动实现类）。缺了它，「修好崩溃」的代价就是「参数全丢」，
     * 那是把红线换成了功能归零。织入形态与观测语义都对齐代理层：白名单 setter、值渲染与
     * 默认 toString 降级、脱敏在快照时按列名提示做。
     *
     * <p>签名被 StatementWeaver 字节码硬编码，改动两侧同步。停采短路排在写入之前——停采期间的
     * 参数不累积，恢复后不会错配到后续语句（与 T-AgentResiduals R5 同口径）。代理层活跃时跳过，
     * 参数由代理自己那份 ParamCapture 记（双通道去重，防双份）。
     */
    public static void onParamSet(Object stmt, int index, Object value) {
        try {
            if (stmt == null || AgentState.isDisabled() || WeaveGate.isProxyActive()) {
                return;
            }
            StatementRegistry.captureParam(stmt, index, value);
        } catch (Throwable t) {
            // 采集红线：自吞（宿主 setXxx 语义不受影响）
        }
    }

    /** prepareStatement/prepareCall 出口：登记「PS 实例 → SQL + URL」（幂等覆盖）。 */
    public static void onStatementPrepare(Object ps, String sql, Object connection) {
        try {
            if (ps == null || sql == null || sql.isEmpty()) {
                return;
            }
            StatementRegistry.register(ps, sql, UrlResolver.resolve(connection));
        } catch (Throwable t) {
            // 采集红线：自吞
        }
    }

    /**
     * 归属链（S4）：stmt.getConnection() 反射 → 驱动 getURL → 最近 connect URL 兜底。
     * F75（T-PerfSweep23）：getConnection 的 Method 按语句类缓存（CHM，对齐同分支
     * UrlResolver.GET_URL_METHODS 同款）——原每条 SQL 一次 getMethod 反射查找，
     * 池化高并发下归属链每 SQL 重复付；解析失败不缓存（外层 catch 落兜底 URL）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Method> GET_CONNECTION_METHODS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Method>();

    private static String resolveUrl(Object stmt) {
        try {
            final Class<?> clazz = stmt.getClass();
            java.lang.reflect.Method m = GET_CONNECTION_METHODS.get(clazz);
            if (m == null) {
                m = clazz.getMethod("getConnection");
                GET_CONNECTION_METHODS.put(clazz, m);
            }
            final Object conn = m.invoke(stmt);
            final String u = UrlResolver.resolve(conn);
            return u != null ? u : UrlResolver.lastConnectUrl();
        } catch (Throwable t) {
            return UrlResolver.lastConnectUrl();
        }
    }

    /**
     * 包装失败统一出口（T-AgentProxyCL）：计数 + 一次性告警 + 返回原对象。
     * 供本包内 Statement 包装路径（ConnectionHandler → wrapStatement）复用——那条路径的异常
     * 会直接冒到宿主的 prepareStatement 调用点，必须在此收口成「返回原语句」。
     */
    static Object dropToRaw(String where, Throwable t, Object fallback) {
        final long count = DROP_FALLBACK.incrementAndGet();
        // T-AgentResiduals R5：停采期间只计数、不打印、不占一次性名额——注意短路顺序，
        // 停采时 CAS 不执行，名额保持未消耗（理由见上方 KDoc）。
        if (!AgentState.isDisabled() && WARNED_ONCE.compareAndSet(false, true)) {
            System.err.println("[T-AgentProxyCL] hook 包装失败，降级为直通（本次采集失效，仅此一条告警，"
                    + "累计次数见 fallbackCount）: " + where + " -> "
                    + (t == null ? "null" : t.getClass().getName() + ": " + t.getMessage())
                    + " [fallbackCount=" + count + "]");
        } else if (count % SUMMARY_EVERY == 0L) {
            // T-PerfSweep6 T-PS6-08（性能报告发现 13）：{@link #fallbackCount()} 此前**零读取**——
            // 注释自述「读该计数即可判断偶发还是持续」，而宿主应用侧没有任何出口读它（agent 侧
            // 无 logger，跨侧协议帧加字段会撞 AgentProtocolSyncTest 的帧守卫）。
            // 折中：首次告警之后，每满 [SUMMARY_EVERY] 次打一行**汇总**到同一个 System.err 出口。
            // 「偶发一次」= 只有首行；「持续失败」= 首行之后按百递增的汇总行——判据可读，且量级是
            // 「每百次一行」，不会在持续故障时刷屏（这正是首行只打一次要避免的问题）。
            System.err.println("[T-AgentProxyCL] hook 包装失败持续发生，累计 fallbackCount=" + count
                    + "（最近一次: " + where + "）");
        }
        return fallback;
    }

    /** 汇总行间隔（每满这么多次失败打一行；见 {@link #dropToRaw} 的 T-PS6-08 注释）。 */
    private static final long SUMMARY_EVERY = 100L;

    /** 供测试观测（反射内联钩子链路不可直接断言的兜底计数）。 */
    public static long fallbackCount() {
        return DROP_FALLBACK.get();
    }

    // T-AgentConfigChannel T-08：原 `AgentStateBridge`（宿主 loader 看不见 agent 类时视同禁用、连接级跳过包装）
    // 已随连接级 `disabled` 判定一并删除——它带来的「连接永不包装」正是恢复采集失效的根因；包装失败的安全网
    // 改由 dropToRaw（自吞 + 计数 + 一次性告警）承担，且从「静默跳过」变为**可观测**。

    private SqlHookCallbacks() {
    }
}
