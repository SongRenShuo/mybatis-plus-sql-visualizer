package cn.song.mybatisplus.visualizer.log;

import java.util.stream.Collectors;

/**
 * 日志条目渲染回 MyBatis 日志文本（T-M38）的扩展函数载体。
 */
public final class LogSqlModelsKt {
    private LogSqlModelsKt() {}

    /** 条目渲染回 MyBatis 日志文本。 */
    public static String toLogText(LogSqlEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("==>  Preparing: ").append(entry.rawSql());
        if (!entry.params().isEmpty()) {
            sb.append('\n').append("==> Parameters: ");
            sb.append(entry.params().stream().map(LogSqlModelsKt::toParamText).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    /** 单个参数渲染回日志形态：1(Long) / abc(String) / null。 */
    private static String toParamText(LogSqlParam p) {
        String v = p.value();
        if (v == null) return "null";
        return p.type() != null ? v + "(" + p.type() + ")" : v;
    }

    /**
     * T-Perf-19（自 dev 移植）：SQL 指纹（FNV-1a 64 位十六进制串）——去重计数键用，避免以完整 SQL 原文为键
     * 的内存驻留。64 位散列下两条不同 SQL 撞键概率可忽略（~2^-64 量级）。
     */
    public static String sqlFingerprint(String sql) {
        long h = -0x340d631b7bdddcdbL; // FNV-1a 64-bit offset basis
        for (int i = 0; i < sql.length(); i++) {
            h = h ^ (long) sql.charAt(i);
            h *= 0x100000001b3L; // FNV-1a 64-bit prime
        }
        return Long.toHexString(h);
    }

    /**
     * T-LogDedupeMpMark：去重口径的空白规范化（trim + 连续空白折叠为单空格）。
     * JdbcTemplate 双路日志对同一语句产出 named/prepared 两副本，仅差首尾空白；
     * 大小写与分号不动——COUNT 前缀判定、达梦带引号标识符大小写均不受影响。
     */
    public static String normalizeSqlWhitespace(String sql) {
        return WHITESPACE_RUN_REGEX.matcher(sql.trim()).replaceAll(" ");
    }

    /** T-PS4-04：空白折叠正则提文件级常量（dedupeKey lazy/stampLogTime/桥嗅探三路共用，一处受益三路）。 */
    private static final java.util.regex.Pattern WHITESPACE_RUN_REGEX = java.util.regex.Pattern.compile("\\s+");

    /**
     * T-SqlSuppressUx（20260924 用户走查返修）：屏蔽匹配口径 = 去重口径追加<b>尾部分号剥离</b>——
     * 规则粘贴态常带收尾 {@code ;} 而日志还原链不带（用户实证 0 命中根因）。只在字符串收尾剥终结符：
     * {@code trimEnd(';')} 触不到字面量内分号（其后必有引号等其余内容，不满足「收尾」）。
     * 大小写仍不动（O-69 D2 定案：与既有规范化一致）；空白折叠先行（{@link #normalizeSqlWhitespace}）。
     * 侧约定：{@link LogSqlEntry#suppressKey()}（条目侧）与 {@code SqlSuppressRuleManager}
     * 的键集重建（规则侧）必须同走本函数，两侧口径永不手写第二份。
     */
    public static String suppressFingerprint(String sql) {
        return sqlFingerprint(trimEndSemicolon(normalizeSqlWhitespace(sql)).trim());
    }

    /**
     * T-SqlSuppressUx 二段（20260924 用户定案「结构匹配手动开启」）：<b>结构指纹</b> = 屏蔽口径再追加
     * 字面量参数化——字符串字面量（{@code '…'}，含 {@code ''} 转义）与裸数字各自折叠为 {@code ?}，参数值不同的同形状
     * SQL 由此同指纹。边界（逐字扫描实现，非正则）：
     * - 双引号/反引号<b>标识符</b>不参数化（{@code "MyCol"}、{@code `t1`} 逐字保留——不同列/表 = 不同形状）；
     * - 标识符内数字不参数化（前邻词字符不满足边界，如 {@code t2024}、{@code plan_2026001}）；
     * - IN 列表按元素个数区分形状（{@code IN (?,?)} ≠ {@code IN (?,?,?)}）——同形状不同个数不命中，已知边界；
     * - 畸形未闭合字面量按一个 {@code ?} 收尾（防御，不抛错）。
     * 仅对<b>手动开启结构匹配的规则</b>与条目侧 {@link LogSqlEntry#structureKey()} 使用；精确口径规则不走此函数。
     */
    public static String structureFingerprint(String sql) {
        return sqlFingerprint(parameterizeLiterals(trimEndSemicolon(normalizeSqlWhitespace(sql)).trim()));
    }

    /** Kotlin {@code String.trimEnd(';')} 等价：剥掉收尾的<b>全部</b>分号（JDK String 无对应 API）。 */
    private static String trimEndSemicolon(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ';') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 字面量参数化（{@link #structureFingerprint} 专属，逐字扫描；口径边界见其 Javadoc）。 */
    public static String parameterizeLiterals(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // 字符串字面量整体折叠为 ?（'' 转义跳过；未闭合按防御收尾）
                int j = i + 1;
                while (j < sql.length()) {
                    if (sql.charAt(j) == '\'') {
                        if (j + 1 < sql.length() && sql.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                sb.append('?');
                i = j < sql.length() ? j + 1 : sql.length();
            } else if (Character.isDigit(c) && (i == 0 || !isSqlWordChar(sql.charAt(i - 1)))) {
                // 裸数字折叠为 ?；前邻词字符 = 标识符内数字（t2024/_id1），逐字保留
                int j = i;
                while (j < sql.length() && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.')) {
                    j++;
                }
                if (j < sql.length() && isSqlWordChar(sql.charAt(j))) {
                    sb.append(sql, i, j); // 2024Q1 类：数字后随词字符，属标识符
                } else {
                    sb.append('?');
                }
                i = j;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** SQL 词字符（标识符边界判定）：字母/数字/下划线/美元符。 */
    private static boolean isSqlWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * T-AgentLogTime：日志时间关联键——<b>占位符版 rawSql 的指纹</b>。
     *
     * 增强通道的 logTimestamp 是派生的（控制台链嗅探暂存 → agent 事件取回），关联键必须两侧同源：
     * 控制台侧 rawSql = {@code Preparing:} 行原文，agent 侧 rawSql = {@code prepareStatement} 收到的原文，
     * 二者都不经过参数还原。旧口径用 {@link LogSqlEntry#dedupeKey()}（还原后 SQL 指纹）要求两套还原器
     * 逐字符一致，布尔参数渲染、注释内 {@code ?} 计数、参数个数守卫三类分叉都会静默失配。
     *
     * 与 dedupeKey 的分工：dedupeKey 管面板去重（跨通道同 SQL 折叠，语义不动），
     * 本键只管日志时间配对——错位替换防护（agent 侧「占位符数 ≠ 参数数就不还原」）因此得以保留。
     */
    public static String logTimeKey(LogSqlEntry entry) {
        return sqlFingerprint(normalizeSqlWhitespace(entry.rawSql()));
    }

    /**
     * T-AgentLogTime：日志时间配对的次级判别——参数<b>值</b>序列签名。
     * 同键（同一条 SQL 多次执行）时用它挑参数一致的那条；两侧渲染口径若分叉则退化为队首配对
     * （近似但不错失），故不做强制相等断言。无参语句两侧都是空串，天然相等。
     * T-PS4-05：join 前逐值截断 {@link #PARAM_SIGNATURE_MAX_CHARS}——超长 blob/JSON 参数不再整串进签名，
     * 相关器驻留有界（桥嗅探 put 与 agent take 共用本函数，截断天然等价）。
     */
    public static String logTimeParamSignature(java.util.List<LogSqlParam> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append('\u0001');
            String rendered = paramSignatureToken(params.get(i));
            sb.append(rendered.length() > PARAM_SIGNATURE_MAX_CHARS
                ? rendered.substring(0, PARAM_SIGNATURE_MAX_CHARS) : rendered);
        }
        return sb.toString();
    }

    // ---------- T-BinaryParamSig（20261105）：二进制参数签名归一 ----------

    /**
     * 二进制占位（两侧归一口径）。只参与匹配，不进展示——列表仍显示各自原文
     * （控制台 {@code '[B@1a2b3c'}、agent {@code '<byte[]>'}）。
     */
    public static final String BINARY_PARAM_PLACEHOLDER = "<binary>";

    /** null 参数的签名 token（与原口径 {@code "\u0000"} 逐字一致，不改变既有配对语义）。 */
    private static final String NULL_SIGNATURE_TOKEN = "\u0000";

    /**
     * 二进制族类型标注：控制台 MyBatis 打印的简单名（{@code byte[]} / {@code Blob} / {@code Clob}）+ agent
     * {@code ParamCapture.simpleTypeName} 对数组类型产出的 JVM 名（{@code [B}）+ 全限定名（旧日志/第三方日志）。
     */
    private static final java.util.Set<String> BINARY_TYPE_MARKERS = java.util.Set.of(
        "byte[]", "[B", "Blob", "Clob", "NClob", "InputStream", "Reader",
        "java.sql.Blob", "java.sql.Clob", "java.sql.NClob",
        "java.io.InputStream", "java.io.Reader");

    /** 对象默认 toString 形态（{@code [B@1a2b3c}、{@code [Ljava.lang.String;@7f3a}）——控制台日志对二进制参数打的就是它。 */
    private static final java.util.regex.Pattern OBJECT_ADDRESS_REGEX =
        java.util.regex.Pattern.compile("^\\[+[A-Za-z.;/]*@[0-9a-fA-F]+$");

    /** agent 侧降级形态（{@code <byte[]>} / {@code <Blob>} / {@code <InputStream>}，见 {@code ParamCapture.renderValue}）。 */
    private static final java.util.regex.Pattern AGENT_DEGRADED_REGEX =
        java.util.regex.Pattern.compile("^<([A-Za-z\\[\\].;$]+)>$");

    /**
     * T-BinaryParamSig：是否为「值文本两侧天然不可比」的二进制族参数。
     *
     * <p>判据三条任一成立：类型标注命中 {@link #BINARY_TYPE_MARKERS} 集合；值是对象默认 toString
     * （{@code [B@1a2b3c}）；值是 agent 降级形态（{@code <byte[]>}）且内层类型名命中同一集合。
     * 三条都要求类型/形态证据，普通字符串（哪怕长得像十六进制，如 {@code deadbeef}）不会被误判。</p>
     */
    public static boolean isBinaryParam(LogSqlParam param) {
        String type = param.type();
        if (type != null && BINARY_TYPE_MARKERS.contains(type)) return true;
        String value = param.value();
        if (value == null) return false;
        if (OBJECT_ADDRESS_REGEX.matcher(value).matches()) return true;
        java.util.regex.Matcher degraded = AGENT_DEGRADED_REGEX.matcher(value);
        if (!degraded.matches()) return false;
        return BINARY_TYPE_MARKERS.contains(degraded.group(1));
    }

    /**
     * T-BinaryParamSig：单个参数的签名 token。
     *
     * <p>二进制族归一为 {@link #BINARY_PARAM_PLACEHOLDER}——否则同一次执行在控制台侧是
     * {@code [B@1a2b3c}、agent 侧是 {@code <byte[]>}，签名不等、crossKey 查不到，兜底折不上；
     * 而「跨通道同源未折叠」哨兵只在 crossKey 相同时才报，这类形态既不会折叠也不会留痕
     * （真机上只能靠截图反推）。</p>
     *
     * <p>副作用边界：归一后「同一条 SQL 连续两次执行且参数是不同的二进制内容」签名相同——但二进制值
     * 本来就不可比，且跨通道折叠还有 5s 窗 + 异通道双重限制，影响限于毫秒级耗时归属。</p>
     */
    public static String paramSignatureToken(LogSqlParam param) {
        String value = param.value();
        if (value == null) return NULL_SIGNATURE_TOKEN;
        return isBinaryParam(param) ? BINARY_PARAM_PLACEHOLDER : value;
    }

    /**
     * T-PS4-05：签名单值截断上限（256 字符）。两侧（桥嗅探 put 与 agent take）走同一函数天然等价；
     * 次级判别失配本就设计为退化为队首配对（LogTimeCorrelator 注释），语义风险可控。
     */
    public static final int PARAM_SIGNATURE_MAX_CHARS = 256;

    /**
     * T-AgentLogTime：给 agent 条目回填控制台嗅探到的日志时间；未命中返回 null
     * （调用方回落 {@link LogSqlEntry#timestamp()} 并留痕）。
     *
     * 增强通道回填的唯一入口——生产路径（AgentManager.AgentSession）与跨通道守卫测试都走它，
     * 避免测试另抄一份「取键 + take」逻辑而自证（T-AgentDialectLoop 的教训：两侧各自绿、集成不通）。
     */
    public static LogSqlEntry stampLogTime(LogSqlEntry entry) {
        Long logTime = LogTimeCorrelator.take(logTimeKey(entry), logTimeParamSignature(entry.params()));
        return logTime != null ? entry.withLogTimestamp(logTime) : null;
    }

    /**
     * O-90：同模板计数聚合（structureKey → 条数）——N+1 <b>捕获后聚类标记</b>的计数数据源
     * （与 Hibernate Debugger 的运行期实时检测差异化：对已捕获条目离线聚合，零运行时开销，
     * 不做实时 profiler——红线）。
     * 判别口径 = {@link #structureFingerprint}（字面量参数化后同形状同键）：参数值不同的同模板 SQL 计入
     * 同键；IN 元素个数 / 表名 / 列名不同的异形互不计入。条目侧 structureKey 为 lazy 体属性
     * （每条只算一次，空集短路），本聚合 O(N) 单遍。
     */
    public static java.util.Map<String, Integer> structureKeyCounts(java.util.List<LogSqlEntry> entries) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (LogSqlEntry e : entries) {
            counts.merge(e.structureKey(), 1, Integer::sum);
        }
        return counts;
    }

    /** 同一次执行的重复观测只补缺失信息，保留原条目的 SQL、时间及序号。 */
    public static LogSqlEntry mergeDuplicateEntry(LogSqlEntry existing, LogSqlEntry incoming) {
        boolean existingFileReal = existing.sourceFile() != null
                && !SourceMarkers.isMpPaginationCount(existing.sourceFile());
        boolean incomingFileReal = incoming.sourceFile() != null
                && !SourceMarkers.isMpPaginationCount(incoming.sourceFile());
        boolean takeIncomingSource = !existingFileReal && incomingFileReal;
        String sourceFile = takeIncomingSource ? incoming.sourceFile()
                : existing.sourceFile() != null ? existing.sourceFile() : incoming.sourceFile();
        int sourceLine = takeIncomingSource ? incoming.sourceLine()
                : existing.sourceFile() != null ? existing.sourceLine() : incoming.sourceLine();
        return new LogSqlEntry(existing.rawSql(), existing.restoredSql(), existing.params(), existing.timestamp(),
                existing.logTimestamp() != null ? existing.logTimestamp() : incoming.logTimestamp(),
                existing.summary(), existing.source(),
                existing.executionTimeMs() != null ? existing.executionTimeMs() : incoming.executionTimeMs(),
                sourceFile, sourceLine,
                existing.sourceCandidates().isEmpty() ? incoming.sourceCandidates() : existing.sourceCandidates(),
                existing.candidateClasses().isEmpty() ? incoming.candidateClasses() : existing.candidateClasses(),
                existing.stackDigest() != null ? existing.stackDigest() : incoming.stackDigest(),
                existing.threadName(),
                existing.sequenceNumber(),
                existing.mapperStatement() != null ? existing.mapperStatement() : incoming.mapperStatement(),
                existing.errorText() != null ? existing.errorText() : incoming.errorText(),
                existing.requestTag() != null ? existing.requestTag() : incoming.requestTag(),
                existing.updateCount() != null ? existing.updateCount() : incoming.updateCount(),
                existing.databaseName() != null ? existing.databaseName() : incoming.databaseName(),
                existing.dialectName() != null ? existing.dialectName() : incoming.dialectName(),
                existing.actualSql(), mergedProvenance(existing.captureProvenance(), incoming.captureProvenance()));
    }

    public static CaptureProvenance mergedProvenance(CaptureProvenance existing, CaptureProvenance incoming) {
        if (existing == CaptureProvenance.UNKNOWN) return incoming;
        if (incoming != CaptureProvenance.UNKNOWN && incoming != existing) return CaptureProvenance.MERGED;
        return existing;
    }

    public static java.util.List<String> mergeUpgradedAspects(LogSqlEntry existing, LogSqlEntry merged) {
        java.util.List<String> aspects = new java.util.ArrayList<>();
        if (!java.util.Objects.equals(existing.sourceFile(), merged.sourceFile())
                || existing.sourceLine() != merged.sourceLine()) aspects.add("源码");
        if (!java.util.Objects.equals(existing.sourceCandidates(), merged.sourceCandidates())) aspects.add("候选帧");
        if (!java.util.Objects.equals(existing.executionTimeMs(), merged.executionTimeMs())) aspects.add("耗时");
        if (!java.util.Objects.equals(existing.updateCount(), merged.updateCount())) aspects.add("行数");
        if (!java.util.Objects.equals(existing.databaseName(), merged.databaseName())) aspects.add("库标");
        if (!java.util.Objects.equals(existing.logTimestamp(), merged.logTimestamp())) aspects.add("日志时间");
        if (!java.util.Objects.equals(existing.stackDigest(), merged.stackDigest())) aspects.add("栈快照");
        return aspects;
    }

    public static String provenanceDiagText(LogSqlEntry entry, boolean gatePaused) {
        String fingerprint = entry.dedupeKey();
        return "捕获归因: 通道=" + entry.captureProvenance() + " 来源=" + entry.source()
                + " 指纹=" + fingerprint.substring(0, Math.min(8, fingerprint.length()))
                + " 有源码=" + (entry.sourceFile() != null) + " 有候选=" + !entry.sourceCandidates().isEmpty()
                + " 有耗时=" + (entry.executionTimeMs() != null) + " 有库标=" + (entry.databaseName() != null)
                + " 桥封停=" + gatePaused;
    }

    public static boolean isConsoleFallbackWithoutSource(LogSqlEntry entry) {
        if (entry.sourceFile() != null || !entry.sourceCandidates().isEmpty()) return false;
        return switch (entry.captureProvenance()) {
            case CONSOLE_EP, CONSOLE_BRIDGE, MERGED -> true;
            default -> false;
        };
    }

    /** Kotlin String.take(n) 等价（越界安全）。 */
    private static String take(String s, int n) {
        return s.substring(0, Math.min(n, s.length()));
    }

    /**
     * T-CrossChannelDedupe（20261105）：**跨通道同源未折叠**的诊断行——同 crossKey（占位符版 rawSql +
     * 参数值签名）、异 dedupeKey（两套还原器口径分叉），且在折叠窗内，却仍未折成一行。
     *
     * <p>用途：这次现场（同一条 INSERT 一行带耗时/位置、一行全 {@code —}）只能靠截图反推，因为没有「键不同 →
     * 各占一行」的痕迹。修完之后这行仍要保留——它是「还原器又分叉了、且兜底也接不住」的长期哨兵。</p>
     *
     * <p><b>内容红线</b>（沿用 {@link #provenanceDiagText}）：只有 crossKey 指纹前缀、两侧 dedupeKey 前缀与通道名——
     * 绝不含 SQL 原文、参数值与连接凭据（诊断行进 idea.log）。</p>
     */
    public static String crossChannelUnfoldedDiagText(String crossKey, LogSqlEntry existing, LogSqlEntry incoming) {
        return "跨通道同源未折叠: crossKey=" + take(crossKey, 8)
            + " dedupeA=" + take(existing.dedupeKey(), 8) + "(通道=" + existing.captureProvenance() + ")"
            + " dedupeB=" + take(incoming.dedupeKey(), 8) + "(通道=" + incoming.captureProvenance() + ")";
    }

    /**
     * T-SqlListCatchup 券 F1（20261107 真机第三轮压测缺陷）：**越窗兜底对账**的诊断行——同 crossKey、
     * 异 dedupeKey、超 5s 折叠窗但仍在去重窗内的两份观测被折回一行（修复前留两行）。
     *
     * <p><b>内容红线</b>（沿用 {@link #crossChannelUnfoldedDiagText}）：只有 crossKey 指纹前缀、两侧 dedupeKey 前缀与
     * 通道名——绝不含 SQL 原文、参数值与连接凭据（诊断行进 idea.log）。</p>
     */
    public static String crossChannelStaleReconcileDiagText(String crossKey, LogSqlEntry existing,
            LogSqlEntry incoming, LogSqlEntry merged) {
        return "超窗兜底对账: crossKey=" + take(crossKey, 8)
            + " dedupeA=" + take(existing.dedupeKey(), 8) + "(通道=" + existing.captureProvenance() + ")"
            + " dedupeB=" + take(incoming.dedupeKey(), 8) + "(通道=" + incoming.captureProvenance() + ")"
            + "→" + merged.captureProvenance() + " 升级=" + mergeUpgradedAspects(existing, merged);
    }

    /**
     * T-CrossChannelDedupe：跨通道等价键**折叠成功**的诊断行（与 {@link #crossChannelUnfoldedDiagText} 同红线：
     * 只有指纹前缀、通道与升级面）。它回答「兜底真的在工作」，是 §6.4 真机复验「兜底触发次数」的读数来源；
     * 未折叠哨兵与它分开成两行，故 {@code 跨通道同源未折叠} 的计数为 0 仍可当作「无残余分叉」的判据。
     */
    public static String crossChannelMergeDiagText(String crossKey, LogSqlEntry existing,
            LogSqlEntry incoming, LogSqlEntry merged) {
        return "跨通道等价键融合: crossKey=" + take(crossKey, 8)
            + " dedupeA=" + take(existing.dedupeKey(), 8) + "(通道=" + existing.captureProvenance() + ")"
            + " dedupeB=" + take(incoming.dedupeKey(), 8) + "(通道=" + incoming.captureProvenance() + ")"
            + "→" + merged.captureProvenance() + " 升级=" + mergeUpgradedAspects(existing, merged);
    }

    /** O-90：同模板徽章显示阈值（≥N 显示「×N 同模板」；常量起步不做设置项——缩范围定案）。 */
    public static final int TEMPLATE_BADGE_MIN_COUNT = 3;
}
