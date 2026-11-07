package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MyBatis 控制台日志解析器（T-M31-01）：逐行喂入控制台输出，配对 `Preparing:` / `Parameters:` 行。
 *
 * 支持的日志形态（T-M31.14 起前缀可配置）：
 * - MP 前缀：`==>  Preparing: SELECT ...` / `==> Parameters: 1(Long), abc(String)`
 * - 原生：`Preparing: SELECT ...` / `Parameters: 1, abc(String)`
 * - 带日志框架前缀（时间戳/线程/类名）——mixedPrefix=true 时按关键字定位（默认，宽容匹配）；
 *   false 时仅行首精确前缀匹配（用户自定义前缀后开启）
 * - 无参语句：仅 `Preparing:` 行（无 Parameters），还原时不做替换
 *
 * 噪声行（`<== Columns:` / `<== Total:` / 业务日志等）自然忽略；
 * 一条语句可跟多条 Parameters 行（极端形态），参数顺序追加。
 * maxParseLength 超长截断（防控制台大输出卡顿）。
 *
 * T-LogParseConc（并发配对修正，用户实测驱动）：
 * - <b>按线程分桶</b>（{@link LogThreadRouter}）：并发应用里多线程语句按到达序混排，单槽状态机下另一线程的
 *   {@code Preparing:} 行会把本线程尚未收到参数的语句提前闭合、本线程随后的 {@code Parameters:} 行因无上下文被丢弃
 *   （参数全丢、{@code ?} 未还原）。故全部状态（pendingSql/参数/等待态/续行态）按线程 token 隔离。
 * - <b>空 {@code Parameters:} 行不再吞下一行</b>：下一行若带日志行特征（looksLikeLogLine），说明该语句本身
 *   无参数（MyBatis 对无参语句打印空前缀行）——闭合且<b>不吞该行</b>（按正常流程重新喂入）。旧行为会把
 *   整行日志当作参数值塞进 {@code ?}（面板出现 {@code LIMIT 2026-09-16 14:35:00.413 DEBUG …} 这类文字）。
 * - <b>多行 SQL 续行</b>（T-LPC-07）：{@code Preparing:} 之后的裸文本行（不含日志特征、非空）并入当前 SQL——
 *   mapper 里格式化的 SQL 不再只留首行，增强通道两侧 rawSql 同形（日志时间相关器得以命中）。
 *
 * <p>T-IbatisPanwei 券 A/B（iBatis 2.x 控制台形态，2026-11-06）：iBatis 的 JDBC 日志代理按
 * {@code {pstm-N}} / {@code {conn-N}} 实例计数打三行一组——{@code Executing Statement: <sql>} →
 * {@code Parameters: [v1, null, v2, …]} → {@code Types: [java.lang.String, null, …]}。参数行是 Java 集合
 * {@code toString}，与 MyBatis 的「值(类型)」语法<b>不兼容</b>（旧行为：单值把整段方括号当字面量渲染成
 * {@code '[x]'}；含 null 的多值只匹配到 null 项、其余值丢弃并错位填充）。</p>
 *
 * <p>本分支的边界（钉死，不做猜测式增强）：
 * <ul>
 * <li><b>行级识别</b>：只认带 {@code {pstm-N}} / {@code {conn-N}} 标记的行，命中即由本分支消费并返回，绝不落到
 *   用户配置前缀分支（用户把 SQL 前缀配置成 {@code Executing Statement:} 也不会产生双条目）；</li>
 * <li><b>列表切分</b>：外层 {@code [ ]} 剥一层、按顶层逗号切分、裸 {@code null} 记为 null 值（切分口径复用
 *   {@code ListParamRenderer.splitListElements}）。<b>元素内含逗号/嵌套方括号/空串元素</b>（{@code List.toString}
 *   不可逆）是已知边界：按逗号切分、不猜转义；{@code splitListElements} 会滤掉空串元素，这一形态下
 *   参数与 {@code Types:} 的位置对齐可能偏移（与 {@code ListParamRenderer} 同口径，不另造实现）；</li>
 * <li><b>类型接入</b>：{@code Parameters:} 行先挂起（键 = pstm id），同 id 的 {@code Types:} 行按位置并入
 *   {@code LogSqlParam.type} 后闭合。<b>必须并入类型</b>：{@code 00340025002200200006} 这类纯数字串在无类型
 *   启发式下会裸出成数值字面量（Oracle 模式语义错误）。兜底闭合点：同 id 的新 {@code Executing
 *   Statement}、{@code {conn-N} Preparing Statement:}、MyBatis {@code Preparing:} 语句行、closePending；
 *   {@code Types:} 缺行时按无类型启发式渲染（现状口径），不伪造类型；</li>
 * <li><b>takeEntries 不冲刷挂起态</b>：生产桥每行喂入后都会调 takeEntries，若在此闭合，{@code Parameters:}
 *   行一落地就被取出、随后的 {@code Types:} 行永远无处并入；</li>
 * <li><b>{@code {conn-N} Preparing Statement:} 只消费不产条目</b>（决策 D-2：iBatis 每条语句都 prepare 一次、
 *   紧接 execute，两条都收会翻倍）；</li>
 * <li><b>批处理一期不支持</b>（iBatis batch 一次性 prepare、多次 {@code Parameters:}）——同 id 的第二条
 *   {@code Parameters:} 会先把上一批按无类型闭合，登记为缺口。</li>
 * </ul></p>
 */
public class MyBatisLogParser {

    private static final String DEFAULT_SQL_PREFIX = "Preparing:";
    private static final String DEFAULT_PARAM_PREFIX = "Parameters:";
    private static final int DEFAULT_MAX_PARSE_LENGTH = 20000;

    // ---------- iBatis 2.x 标记（券 A/B） ----------

    /** 廉价前置判据（两次 indexOf 即可挡掉 MyBatis/Hibernate 行，不跑正则）。 */
    private static final String PSTM_MARKER_PREFIX = "{pstm-";
    private static final String CONN_MARKER_PREFIX = "{conn-";

    /** 标记种类分组值：{conn-N} 只消费不产条目，其余（pstm）走语句状态机。 */
    private static final String CONN_KIND = "conn";

    private static final String IBATIS_EXECUTING_STATEMENT = "Executing Statement:";
    private static final String IBATIS_PREPARING_STATEMENT = "Preparing Statement:";
    private static final String IBATIS_PARAMETERS = "Parameters:";
    private static final String IBATIS_TYPES = "Types:";

    /** iBatis JDBC 日志代理的实例计数器标记：{pstm-100021} / {conn-100022}。 */
    private static final Pattern IBATIS_MARKER = Pattern.compile("\\{(pstm|conn)-(\\d+)\\}");

    /**
     * Hibernate 参数绑定行模式：`binding parameter [N] as [TYPE] - [VALUE]`
     */
    private static final Pattern HIBERNATE_BINDING_PATTERN = Pattern.compile(
            "binding parameter\\s+\\[(\\d+)\\]\\s+as\\s+\\[(\\w+)\\]\\s+-\\s+\\[(.*)\\]",
            Pattern.CASE_INSENSITIVE);

    /**
     * T-LPC-07：`名称: 内容` 形态的行首标识符前缀（如 `Hibernate: select …`）——续行判定拒收，
     * 避免异构语句行/框架头行被并入 MyBatis 条目的 SQL 正文。
     */
    private static final Pattern HEADER_LIKE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.]{2,}\\s*:");

    private final LogSqlRestorer restorer;
    private String sqlPrefix;
    private String paramPrefix;
    private boolean mixedPrefix;
    private int maxParseLength;

    private final List<LogSqlEntry> entries = new ArrayList<>();

    /**
     * iBatis 2.x 未闭合语句（券 A/B）：键 = {@code {pstm-N}} 的 N（配对优先按 id，线程桶只在
     * 「语句行关闭 MyBatis 侧未闭合条目」时用到）。
     */
    private static final class IbatisPending {
        final String sql;
        final String logLine;
        /** null = {@code Parameters:} 行未到（仅 SQL 已知）；非 null = 参数已到、等 {@code Types:} 并入 */
        List<LogSqlParam> params = null;

        IbatisPending(String sql, String logLine) {
            this.sql = sql;
            this.logLine = logLine;
        }

        IbatisPending(String sql, String logLine, List<LogSqlParam> params) {
            this.sql = sql;
            this.logLine = logLine;
            this.params = params;
        }
    }

    /**
     * pstm id → 挂起态（保持插入序，{@link #flushIbatis()} 按序冲刷）。
     * 存在独立于线程桶的第二个理由：{@code Parameters:} 与 {@code Types:} 在并发控制台里可能被别的线程的
     * 语句行夹开，按 id 归位比按线程桶归位更接近 iBatis 的打印事实。
     */
    private final java.util.LinkedHashMap<String, IbatisPending> ibatisPending = new java.util.LinkedHashMap<>();

    /** 单线程桶：一条语句从 `Preparing:` 到闭合之间的全部状态（T-LogParseConc 按线程隔离）。 */
    private static final class Bucket {
        String pendingSql = null;
        String pendingLogLine = null;
        final List<LogSqlParam> pendingParams = new ArrayList<>();

        /** 前缀独占一行兼容（T-M31.xx）：`Preparing:` 行 remainder 为空 → 等下一行作 SQL。 */
        boolean prefixMatched = false;

        /** `Parameters:` 行 remainder 为空 → 等下一行作参数文本（T-LogParseConc 加了日志行判别）。 */
        boolean paramPrefixMatched = false;

        /** 多行 SQL 续行态（T-LPC-07）：下一行是裸文本则并入 pendingSql。 */
        boolean continuing = false;
    }

    private final LogThreadRouter<Bucket> router = new LogThreadRouter<>(
            Bucket::new,
            // 无线程 token 的裸行（续行 / 「前缀独占一行」的下一行）要落到**正在等待它**的桶——
            // 否则带前缀的 `Preparing:`（token=main）与其下一行裸 SQL 会分属两个桶，等待态永远完不成
            b -> b.continuing || b.prefixMatched || b.paramPrefixMatched,
            b -> b.pendingSql == null && !b.prefixMatched && !b.paramPrefixMatched);

    public MyBatisLogParser() {
        this(new LogSqlRestorer());
    }

    public MyBatisLogParser(LogSqlRestorer restorer) {
        this(restorer, DEFAULT_SQL_PREFIX, DEFAULT_PARAM_PREFIX, true, DEFAULT_MAX_PARSE_LENGTH);
    }

    /** 全配置便捷构造（restorer 取默认）——master 具名默认参数形态（EP Filter/桥用）。 */
    public MyBatisLogParser(String sqlPrefix, String paramPrefix, boolean mixedPrefix, int maxParseLength) {
        this(new LogSqlRestorer(), sqlPrefix, paramPrefix, mixedPrefix, maxParseLength);
    }

    public MyBatisLogParser(LogSqlRestorer restorer, String sqlPrefix, String paramPrefix,
                            boolean mixedPrefix, int maxParseLength) {
        this.restorer = restorer;
        this.sqlPrefix = sqlPrefix;
        this.paramPrefix = paramPrefix;
        this.mixedPrefix = mixedPrefix;
        this.maxParseLength = maxParseLength;
    }

    public LogSqlRestorer getRestorer() {
        return restorer;
    }

    public String getSqlPrefix() {
        return sqlPrefix;
    }

    public void setSqlPrefix(String sqlPrefix) {
        this.sqlPrefix = sqlPrefix;
    }

    public String getParamPrefix() {
        return paramPrefix;
    }

    public void setParamPrefix(String paramPrefix) {
        this.paramPrefix = paramPrefix;
    }

    public boolean isMixedPrefix() {
        return mixedPrefix;
    }

    public void setMixedPrefix(boolean mixedPrefix) {
        this.mixedPrefix = mixedPrefix;
    }

    public int getMaxParseLength() {
        return maxParseLength;
    }

    public void setMaxParseLength(int maxParseLength) {
        this.maxParseLength = maxParseLength;
    }

    /** 喂入一行控制台输出（单解析器调用方兼容薄壳；热路径走 feed(line, token) 重载，任意线程；按线程 token 路由到独立桶——解析器实例按 console 隔离）。 */
    public void feed(String line) {
        feed(line, LogThreadRouter.threadTokenOf(line));
    }

    /**
     * F58（T-PerfSweep18 压轴）：喂入一行＋<b>已提取</b>的线程 token（热路径每行一次下传，
     * 语义与 {@code feed(line)} 自提逐字等价；token=null 走 fail-open 回退）。
     */
    public void feed(String line, String threadToken) {
        feedInto(router.bucketForToken(threadToken), line);
    }

    private void feedInto(Bucket bucket, String line) {
        // 分支 0（券 A/B）：iBatis 2.x 标记行——命中即消费并返回，绝不落到下方用户配置前缀分支
        if (feedIbatis(bucket, line)) return;

        // 分支 1：Preparing 行（有 SQL 同行）
        String sql = extractAfterKeyword(line, sqlPrefix);
        if (sql != null) {
            // 其它语句行 = iBatis 挂起参数组的兜底闭合点（Types 缺行/日志截断时不丢条目）
            flushIbatis();
            closePending(bucket); // 上一条未闭合先闭合（无 Parameters 或异常日志）
            bucket.pendingSql = sql.substring(0, Math.min(maxParseLength, sql.length()));
            bucket.pendingLogLine = line;
            bucket.pendingParams.clear();
            bucket.prefixMatched = false;
            bucket.paramPrefixMatched = false; // T-LogParseConc：等待态随语句切换复位（旧实现残留→吞掉后续整行）
            bucket.continuing = true;
            return;
        }

        // 分支 2：Preparing 独占一行（remainder 为空）→ 进入等待 SQL 态
        if (!bucket.prefixMatched && isPrefixOnly(line, sqlPrefix)) {
            bucket.prefixMatched = true;
            bucket.pendingParams.clear();
            return;
        }

        // 分支 3：Parameters 独占一行（极端形态）→ 等待下一行参数文本
        if (!bucket.paramPrefixMatched && isPrefixOnly(line, paramPrefix)) {
            if (bucket.prefixMatched) {
                // 等待 SQL 中看到 Parameters（中间无 SQL 行）→ 放弃等待
                bucket.prefixMatched = false;
                return;
            }
            bucket.paramPrefixMatched = true;
            return;
        }
        if (bucket.paramPrefixMatched) {
            bucket.paramPrefixMatched = false;
            if (line.isBlank()) {
                return; // 跳过空行
            }
            if (bucket.pendingSql == null) {
                return; // 无 SQL 上下文，忽略
            }
            // T-LPC-03：等到的行若本身是日志行 → 该语句**无参数**（MyBatis 对无参语句打印空前缀行）。
            // 闭合且不吞该行（按正常流程重新喂入——含它自己的线程桶路由；本等待态已复位，不会再进本分支）。
            if (LogThreadRouter.looksLikeLogLine(line, sqlPrefix, paramPrefix)) {
                LogParseDiag.swallowedLineBlocked.incrementAndGet();
                closePending(bucket);
                feed(line);
                return;
            }
            bucket.pendingParams.addAll(MyBatisLogParserKt.parseParams(line.trim()));
            closePending(bucket);
            return;
        }

        // 分支 4：Parameters 行（有参数同行）——正常形态
        String paramsText = extractAfterKeyword(line, paramPrefix);
        if (paramsText != null) {
            bucket.prefixMatched = false; // 清除等待态（罕见：Preparing 独占一行后紧跟正常 Parameters）
            if (bucket.pendingSql == null) {
                LogParseDiag.paramWithoutContext.incrementAndGet(); // T-LPC-05：静默丢弃留痕
                return;
            }
            bucket.pendingParams.addAll(MyBatisLogParserKt.parseParams(paramsText));
            // 立即闭合——MyBatis 单条语句 Parameters 行后参数完整，立即生成条目避免去重窗口时序问题；
            // 多 Parameters 行（极端）合并逻辑：若下一行仍是 Parameters，pendingParams 已被清空后重开。
            closePending(bucket);
            return;
        }

        // 分支 5：等待 SQL 态 → 此行即 SQL
        if (bucket.prefixMatched) {
            if (line.isBlank()) {
                return; // 跳过空行
            }
            // 多模块并发时，Hibernate 行可能出现在 Preparing: 独占一行后的等待态 → 跳过并继续等待
            if (looksLikeHibernateLine(line)) {
                return; // 不重置 prefixMatched，继续等 MyBatis SQL
            }
            String trimmed = line.trim();
            bucket.pendingSql = trimmed.substring(0, Math.min(maxParseLength, trimmed.length()));
            bucket.pendingLogLine = line;
            bucket.prefixMatched = false;
            bucket.continuing = true;
            return;
        }

        // 分支 6（T-LPC-07）：SQL 续行——仅当本行未被任何正常分支消费、且不像日志行/异构语句行时并入当前 SQL。
        // 排在等待态之后是必须的：「等下一行参数文本」态下的裸参数行（`42(Long)`）不能被当续行吞掉。
        if (bucket.continuing) {
            if (line.isBlank() || LogThreadRouter.looksLikeLogLine(line, sqlPrefix, paramPrefix)
                    || looksLikeForeignStatement(line)) {
                bucket.continuing = false; // 日志行/异构语句行/空行终止续行（本行交回噪声分支）
            } else {
                appendContinuation(bucket, line);
            }
            return;
        }

        // 分支 7：噪声行（与旧行为一致）
    }

    // ---------- iBatis 2.x 标记行（券 A/B） ----------

    /**
     * 处理带 {@code {pstm-N}} / {@code {conn-N}} 标记的行；返回 true 表示<b>本行已被消费</b>（调用方须立即返回）。
     *
     * <p>廉价前置一次 {@code contains}：MyBatis/Hibernate 日志行在热路径上（每行每解析器调用）零正则开销。</p>
     */
    private boolean feedIbatis(Bucket bucket, String line) {
        if (!line.contains(PSTM_MARKER_PREFIX) && !line.contains(CONN_MARKER_PREFIX)) return false;
        java.util.regex.Matcher marker = IBATIS_MARKER.matcher(line);
        if (!marker.find()) return false;
        String id = marker.group(2);
        String rest = line.substring(marker.end()).trim();
        if (CONN_KIND.equals(marker.group(1))) {
            // {conn-N} Preparing Statement: <sql>（及 conn 的其它行）只消费不产条目（D-2）
            if (rest.startsWith(IBATIS_PREPARING_STATEMENT)) flushIbatis();
            return true;
        }
        if (rest.startsWith(IBATIS_EXECUTING_STATEMENT)) {
            onIbatisExecuting(bucket, id, rest.substring(IBATIS_EXECUTING_STATEMENT.length()).trim(), line);
            return true;
        }
        if (rest.startsWith(IBATIS_PARAMETERS)) {
            onIbatisParameters(id, rest.substring(IBATIS_PARAMETERS.length()).trim());
            return true;
        }
        if (rest.startsWith(IBATIS_TYPES)) {
            onIbatisTypes(id, rest.substring(IBATIS_TYPES.length()).trim());
            return true;
        }
        // 其余带 pstm 标记的行（Closing statement 等）只消费不产条目
        return true;
    }

    /** {pstm-N} Executing Statement: ——开启一条语句；同 id 的旧挂起态先按无类型兜底闭合。 */
    private void onIbatisExecuting(Bucket bucket, String id, String sql, String line) {
        IbatisPending old = ibatisPending.remove(id);
        if (old != null) emitIbatis(old);
        // 语句行同时终止 MyBatis 侧的未闭合语句/续行（与分支 1 同语义）
        closePending(bucket);
        if (sql.isEmpty()) return;
        ibatisPending.put(id, new IbatisPending(
                sql.substring(0, Math.min(maxParseLength, sql.length())), line));
    }

    /** {pstm-N} Parameters: ——挂起等 Types；无对应语句（上下文已丢）按既有诊断计数丢弃。 */
    private void onIbatisParameters(String id, String text) {
        IbatisPending pending = ibatisPending.get(id);
        if (pending == null) {
            LogParseDiag.paramWithoutContext.incrementAndGet();
            return;
        }
        List<LogSqlParam> params = MyBatisLogParserKt.parseIbatisParams(text);
        if (pending.params != null) {
            // 同 id 的第二条 Parameters（批处理 / 同语句多次执行）：上一批先按无类型闭合，本批另起一条
            ibatisPending.remove(id);
            emitIbatis(pending);
            ibatisPending.put(id, new IbatisPending(pending.sql, pending.logLine, params));
        } else {
            pending.params = params;
        }
    }

    /** {pstm-N} Types: ——按位置并入类型后闭合；无对应挂起态（或 Parameters 尚未到）则只消费。 */
    private void onIbatisTypes(String id, String text) {
        IbatisPending pending = ibatisPending.get(id);
        if (pending == null) return;
        List<LogSqlParam> params = pending.params;
        if (params == null) return; // 形态异常（Types 先于 Parameters）：保留 SQL 等参数行
        ibatisPending.remove(id);
        emitEntry(pending.sql, pending.logLine, MyBatisLogParserKt.applyIbatisTypes(params, MyBatisLogParserKt.parseIbatisTypes(text)));
    }

    /** 按「无类型兜底」闭合单条 iBatis 挂起态（Types 缺行 / 被新语句顶掉）。 */
    private LogSqlEntry emitIbatis(IbatisPending pending) {
        return emitEntry(pending.sql, pending.logLine, pending.params != null ? pending.params : List.of());
    }

    /** 冲刷全部 iBatis 挂起态（按插入序），返回首个产出条目（保持 closePending 返回语义）。 */
    private LogSqlEntry flushIbatis() {
        if (ibatisPending.isEmpty()) return null;
        List<IbatisPending> items = new ArrayList<>(ibatisPending.values());
        ibatisPending.clear();
        LogSqlEntry first = null;
        for (IbatisPending item : items) {
            LogSqlEntry produced = emitIbatis(item);
            if (first == null) first = produced;
        }
        return first;
    }

    /** 条目产出唯一收口（MyBatis 桶闭合与 iBatis 挂起闭合共用，避免两处各构造一份）。 */
    private LogSqlEntry emitEntry(String sql, String logLine, List<LogSqlParam> params) {
        String restored = restorer.restore(sql, params);
        Long logTs = logLine != null ? LogTimestampParser.parse(logLine) : null;
        LogSqlEntry entry = new LogSqlEntry(
                sql,
                restored,
                params,
                System.currentTimeMillis(),
                logTs,
                LogSqlEntry.summarizeSql(restored),
                SqlSource.MYBATIS,
                null, null, 0, List.of(), List.of(), null, 0L,
                null, null, null, null, null);
        entries.add(entry);
        return entry;
    }

    /**
     * 是否像<b>异构语句行</b>（其他 ORM/框架的 SQL 行，如 `Hibernate: select …`）——续行判定须拒收：
     * 这类行本身代表另一条语句，并入会污染 MyBatis 条目的 SQL 正文（`DualParserIntegrationTest` 覆盖）。
     */
    private boolean looksLikeForeignStatement(String line) {
        String trimmed = line.trim();
        return looksLikeHibernateLine(line) || HEADER_LIKE_PATTERN.matcher(trimmed).find();
    }

    /** 续行并入（受 maxParseLength 约束，超限即终止续行不吞后续）。 */
    private void appendContinuation(Bucket bucket, String line) {
        String current = bucket.pendingSql;
        if (current == null) {
            bucket.continuing = false;
            return;
        }
        String merged = current + "\n" + stripTrailingWhitespace(line);
        if (merged.length() >= maxParseLength) {
            bucket.continuing = false;
            return;
        }
        bucket.pendingSql = merged;
    }

    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 闭合指定桶的未完成条目（新语句到来 / 面板刷新时调用）。 */
    private LogSqlEntry closePending(Bucket bucket) {
        String sql = bucket.pendingSql;
        if (sql == null) {
            return null;
        }
        bucket.pendingSql = null;
        bucket.continuing = false;
        String logLine = bucket.pendingLogLine;
        bucket.pendingLogLine = null;
        List<LogSqlParam> params = new ArrayList<>(bucket.pendingParams);
        bucket.pendingParams.clear();
        return emitEntry(sql, logLine, params);
    }

    /**
     * 闭合全部未完成条目（面板刷新 / 粘贴末尾时调用）：iBatis 挂起态先冲刷（按 pstm 插入序），
     * 再按线程桶序闭合 MyBatis 侧。
     */
    public LogSqlEntry closePending() {
        LogSqlEntry first = flushIbatis();
        for (Bucket bucket : router.allBuckets()) {
            LogSqlEntry produced = closePending(bucket);
            if (first == null && produced != null) {
                first = produced;
            }
        }
        return first;
    }

    /**
     * 取出全部已闭合条目并清空缓冲区（面板刷新用）。
     *
     * <p><b>不冲刷 iBatis 挂起态</b>：控制台桥每行喂入后都会调用本方法，若在此闭合，{@code Parameters:} 行
     * 落地即被取走、紧随的 {@code Types:} 行再也无处并入（类型丢失 → 纯数字串裸出成语义错误的字面量）。
     * 挂起态的闭合见类注释「兜底闭合点」。</p>
     */
    public List<LogSqlEntry> takeEntries() {
        List<LogSqlEntry> result = new ArrayList<>(entries);
        entries.clear();
        return result;
    }

    private String extractAfterKeyword(String line, String keyword) {
        if (keyword.isEmpty()) {
            return null;
        }
        int idx = mixedPrefix ? line.indexOf(keyword) : (line.startsWith(keyword) ? 0 : -1);
        if (idx < 0) {
            return null;
        }
        String text = line.substring(idx + keyword.length()).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 行仅含前缀关键字（remainder 为空/纯空白）——分行形态信号。
     * 与 {@link #extractAfterKeyword(String, String)} 使用相同的 mixedPrefix 判断路径，保证两种模式下行为一致。
     */
    private boolean isPrefixOnly(String line, String keyword) {
        if (keyword.isEmpty()) {
            return false;
        }
        int idx = mixedPrefix ? line.indexOf(keyword) : (line.startsWith(keyword) ? 0 : -1);
        if (idx < 0) {
            return false;
        }
        return line.substring(idx + keyword.length()).trim().isEmpty();
    }

    /**
     * 是否为 Hibernate 日志行（多模块并发时可能混入 MyBatis 控制台）。
     * 在 prefixMatched 等待态中跳过此类行，避免将 Hibernate 行误认为 MyBatis SQL。
     */
    private boolean looksLikeHibernateLine(String line) {
        String trimmed = line.trim();
        // Hibernate: 前缀 / 块注释开头 / 参数绑定行
        return trimmed.toLowerCase().startsWith("hibernate:") ||
                trimmed.startsWith("/*") ||
                HIBERNATE_BINDING_PATTERN.matcher(trimmed).find();
    }
}
