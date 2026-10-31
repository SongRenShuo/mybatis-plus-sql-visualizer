package cn.song.mybatisplus.visualizer.log

/**
 * SQL 日志条目来源（区分不同 ORM 框架的捕获结果）。
 */
enum class SqlSource {
    /** MyBatis-Plus 控制台日志（`Preparing:` / `Parameters:` 格式）。 */
    MYBATIS,
    /** Hibernate/JPA 控制台日志（`Hibernate:` / `binding parameter` 格式）。 */
    HIBERNATE,
    /** Spring JdbcTemplate 控制台日志（`Executing prepared SQL statement [SQL]` / `Setting SQL statement parameter value: ...` 格式）。 */
    JDBC,
    /**
     * ShardingSphere 双段日志（R10 T-ShardingSphereCompat，5.x sql-show 形态）：
     * `Logic SQL:` 主条目 + `Actual SQL:` 折叠附注（[LogSqlEntry.actualSql]）。
     * 骨架按公开文档形态实现，真实语料未采样（V36）——行为细节见解析器 TODO。
     */
    SHARDINGSPHERE,
    /** agent 字节码通道（M56 增强捕获）：应用 JVM 内 JDBC 执行点 hook 直取的结构化事件。 */
    AGENT,
    /**
     * MCP 工具通道（T-McpAuditTrail / T-MAT-03）：MCP 执行类工具在真实数据源上跑过的只读语句。
     *
     * 当前唯一产出点 = `sqlv_explain`（十个 MCP 工具里唯一有执行面的一个）——条目文本恒为
     * 调用方传入的**原始 selectSql**，绝不落 `EXPLAIN <select>` 包装句（T-MAT-02：包装句首 token
     * 为 `EXPLAIN`，会被 [LogStatementClassifier] 判 `OTHER` → 列表无类型染色）。
     * 因此本枚举值同时充当「该条来自 EXPLAIN 包装执行」的独立标记。
     *
     * 与 [AGENT] 的分工：agent 是应用进程内 JDBC hook（被观测应用自己执行的 SQL），
     * 本值是本插件 MCP 工具主动执行的语句——两者都不来自控制台日志解析。
     */
    MCP,
    /** 未知来源（兼容旧版本条目）。 */
    UNKNOWN,
}

/**
 * T-AgentCaptureProvenance（O-147）：条目捕获路径（进入插件的**传输通道**）。
 *
 * 与 [SqlSource] 分工：SqlSource 是 ORM 日志**形态**（MyBatis/Hibernate/JDBC/agent 结构化事件…），
 * 本枚举是「这条条目从哪条链路进的面板」——agent 事件、控制台 EP 过滤器、控制台桥轮询，
 * 或融合条目（同去重桶先后收到多通道观测，字段级合并后的产物）。
 *
 * 用途有二：
 *  1. **归因诊断**（W0）——「增强已开启却出现无源码条目」时，idea.log 能直接读出该条来自哪条
 *     通道（历史上只能靠 UI 形态反推，V180-1/V180-2 现场无法裁决的根因）；
 *  2. **反馈分流**（W3）——跳转 MissingSource 时，控制台回落条目不再弹「开启源码捕获」笼统引导
 *     （增强已在跑时是误导），改走轻提示。
 *
 * UNKNOWN = 旧版本条目 / 历史读回缺 `pv` 键 / MCP 审计等未打标通道（向前兼容，不迁移）。
 * 主构造字段：参与 data class equals——同通道纯重复不融合（见 [mergedProvenance]）依赖该语义。
 */
enum class CaptureProvenance {
    /** agent 事件通道（[cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter] 适配产出）。 */
    AGENT,
    /** 控制台 EP 过滤器通道（SqlConsoleBridgeFilterProvider，平台自动装配的主控制台通道）。 */
    CONSOLE_EP,
    /** 控制台桥轮询通道（SqlLogConsoleBridge 逐行 / 静默冲刷兜底）。 */
    CONSOLE_BRIDGE,
    /** 融合条目：同去重桶先后收到不同通道的观测，字段级合并后替换原位（O-147 W1）。 */
    MERGED,
    /** 未打标（旧条目 / 历史读回缺键 / MCP 审计）。 */
    UNKNOWN,
}

/**
 * 日志 SQL 条目模型（T-M31）：一条 SQL 语句 + 其参数列表。
 *
 * 来源：MyBatis 控制台日志（MP 前缀 `==>  Preparing:` / 原生 `Preparing:`）或
 * Hibernate 控制台日志（`Hibernate:` / `binding parameter`），
 * 经解析器配对、[LogSqlRestorer] 还原后进入工具窗口面板。
 */
data class LogSqlEntry(
    /** 原始 SQL 文本（未替换占位符）。 */
    val rawSql: String,
    /** 还原后的可执行 SQL（参数已替换；无法替换的 `?` 保留）。 */
    val restoredSql: String,
    /** 参数列表（与 SQL 中 `?` 按序对应）。 */
    val params: List<LogSqlParam>,
    /** 捕获时间戳。 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 日志原始时间戳（从日志行中解析，未解析到则为 null）。 */
    val logTimestamp: Long? = null,
    /**
     * 摘要（列表展示用，智能截断：在第一行末尾或 MAX_SUMMARY_LENGTH 处取较小值，
     * 避免 mid-line 截断导致 multiline SQL 列表展示不完整）。
     */
    val summary: String = summarizeSql(restoredSql),
    /** 条目来源（MyBatis / Hibernate / 未知）。 */
    val source: SqlSource = SqlSource.UNKNOWN,
    /**
     * 执行耗时（毫秒）；null 表示未解析到耗时信息。
     * 由 [ExecutionTimeParser] 从日志文本解析后填入（O-1 Phase B）。
     */
    val executionTimeMs: Long? = null,
    // ---------- F-44：源码位置 ----------
    /**
     * 产生该 SQL 的源文件路径（相对项目根目录，如 "src/main/java/com/example/mapper/UserMapper.java"）。
     * null 表示未知（旧版 F-43 保存的条目无此信息）。
     */
    val sourceFile: String? = null,
    /**
     * 产生该 SQL 的源码行号（0 表示未知）。
     */
    val sourceLine: Int = 0,
    /**
     * T-AgentSourceJump：源码位置候选（栈序 [file, line]；仅 agent 通道产出，控制台通道/历史读回为空）。
     * 主位置在工程内解析失败时，跳转按候选序取首个可解析项——候选全部来自本次执行的真实调用栈，
     * 非表名等启发式（不会误导）。
     */
    val sourceCandidates: List<Pair<String, Int>> = emptyList(),
    /**
     * T-SourceJumpFunnel：候选帧类名（与 [sourceCandidates] 栈序对齐，旧 agent 事件可能缺类名列 → 空）。
     * 展示性字段（选帧弹层/NotFound 提示），**body 内属性不参与 equals/hashCode**——但 sourceCandidates
     * 在主构造里，本字段也在主构造，须评估 equals 影响：去重走 dedupeKey（惰性指纹），data class equals
     * 变化不影响面板去重口径（T-CrudColor 同理定案）。
     */
    val candidateClasses: List<String> = emptyList(),
    /** T-SourceJumpFunnel：调用栈快照「类#方法:行 ← …」（仅 agent 通道；详情面板人工往上追踪用）。 */
    val stackDigest: String? = null,
    /**
     * T-StackBizFrame S3：产生该 SQL 的线程名（仅 agent 通道）。调用栈弹层标题展示，
     * 让「调度线程 / 异步池 / 请求线程」一眼可辨（同类误报的第一判别量）。
     * null = 未知（桥通道 / 旧条目 / 旧日志文件），标题退回纯「调用栈」。
     */
    val threadName: String? = null,
    /**
     * T-LogRowNumber：会话内捕获序号（「SQL 日志」列表行号）。由 [SqlLogEntryBuffer.addEntry]
     * 成功入队时分配（单调递增；去重丢弃/暂停/过滤关键字命中的条目不占号），
     * 项目启动（processStarted）或手动清空列表时归零。0 表示无行号
     * （历史文件读回、日志转 SQL round-trip 产出的条目）。
     */
    val sequenceNumber: Long = 0L,
    // ---------- M56 T-M56-00：agent 通道结构化字段（桥通道经标记行第 4/5 段与 pd 载荷扩展部分承接） ----------
    /**
     * mapper 语句 id（如 `com.example.mapper.UserMapper.selectById`）。
     * agent 通道从 MyBatis Executor 直取；桥通道来自源码标记第 4 段（拦截器 `ms.getId()`）。
     * null 表示未知（旧条目 / 非 MyBatis 来源）。
     */
    val mapperStatement: String? = null,
    /**
     * 执行报错（结构化直取：异常类名 + 消息，堆栈截断）；null 表示执行成功。
     * agent 通道在执行点直捕；桥通道暂无对应日志形态（T-M56-05 前保持 null）。
     */
    val errorText: String? = null,
    /**
     * 请求级聚合标签（T-M56-07 请求聚合预留）：同一次请求的多条 SQL 共享同值，
     * 聚焦页签据此分组。桥通道来自源码标记第 5 段（拦截器 MDC 兜底）；null 表示无标签。
     */
    val requestTag: String? = null,
    /**
     * 影响行数（agent 通道 `getUpdateCount` 直取，DML 语义）；null 表示未知（查询 / 旧条目）。
     */
    val updateCount: Long? = null,
    /**
     * 目标数据库标注（T-AgentDialectLoop 起，R32 弱形态升级：agent 通道由连接 URL 解析——
     * 方言显示名 · url 解析连接事实，如 "MySQL · jpa_demo@localhost:3306"、"DM（达梦） · localhost:5236"；
     * 只有方言可辨或只有连接事实可解析时输出其一）。解析与凭据剔除在
     * [cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects.badgeName]（user/password 参数、
     * Oracle thin 登录前缀解析期剔除，凭据串绝不入本字段）。
     * null 表示未知（桥通道 / 旧版 agent / 畸形 URL）。展示性字段，不入去重指纹；
     * 自动存档/读回按自由文本回环（SqlLogAutoSaver/SqlLogFileReader，格式无关）。
     */
    val databaseName: String? = null,
    /**
     * T-DialectInferFix：本次执行连的那条连接的**方言名**（[cn.song.mybatisplus.visualizer.sql.DialectRegistry]
     * 命名空间，如 `PostgreSQL`；不是 [databaseName] 那种展示串）。
     *
     * 与 [databaseName] 同源（agent 事件的 `connect` URL），但**取的是渲染用的命名空间**：
     * [cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects] 的显示名不能直接喂
     * `DialectRegistry.byName`（HSQL/GBASE 不在注册表里、命名口径也不同）。
     *
     * 用途：日志/历史双击预览据此把「这条 SQL 当时跑在哪个库」当作 EXACT 方言证据
     * （O-145 方案第 2 条），不再让预览回落 MySQL。null = 未知（桥通道 / 旧版 agent / 旧条目）。
     * 随 pd 载荷键 `dl` 落盘（仅非空落键，旧读端忽略未知键）。
     */
    val dialectName: String? = null,
    /**
     * Actual SQL 折叠附注（R10 T-ShardingSphereCompat，D1=A）：[SqlSource.SHARDINGSPHERE]
     * 条目的 `Actual SQL:` 段原文（多分片逐行、换行连接）；其余来源恒 null。
     * Logic 主条目 + Actual 折叠的模型承载——面板对照渲染（折叠态样式）等真实语料（V36）。
     * 展示性字段：随 pd 载荷键 `a` 落盘（仅非默认落键），不入去重指纹。
     */
    val actualSql: String? = null,
    /**
     * T-AgentCaptureProvenance（O-147）：捕获路径（见 [CaptureProvenance]）。默认 UNKNOWN，
     * 全部既有构造点零改动；生产链路在三条产出点显式打标（agent 适配 / EP 过滤器 / 桥轮询）。
     * 主构造字段——参与 equals：同通道纯重复不融合的判定依赖它（[mergedProvenance]）。
     */
    val captureProvenance: CaptureProvenance = CaptureProvenance.UNKNOWN,
) {
    /**
     * T-CrudColor：条目语句类型（渲染热路径缓存，惰性计算一次）。
     *
     * 由 [LogStatementClassifier.classify] 得出——与类型过滤弹窗**同一口径**，
     * 因此「筛出来的 SELECT 行不会被标成红色」这一自洽性天然成立。
     *
     * 之所以缓存而非每次现算：[LogStatementClassifier.classify] 内部 `Regex("\\s+")`
     * **每次调用都重新编译 Pattern**，渲染路径按 cell 调用会把该开销放大到
     * 「每次重绘 × 可见行数」（滚动/悬停/选中/resize 均触发重绘）。
     *
     * 与 [dedupeKey] 同属 body 内属性——**不参与 data class 的 equals / hashCode / copy**，
     * 故新增该字段不影响既有去重与集合语义（T-LogRowNumber 的 `sequenceNumber` 教训：
     * 构造函数内新增字段会改变 equals，此处不会）。
     */
    val statementType: StatementType by lazy { LogStatementClassifier.classify(restoredSql) }

    /**
     * T-Perf-19（自 dev 移植）：去重键指纹（[sqlFingerprint] 惰性计算一次）——dedupeCounts 不再以
     * 完整 SQL 原文为键（还原参数文本可达数 KB/条，长会话数十~上百 MB 永久驻留）。
     * T-LogDedupeMpMark：指纹前先 [normalizeSqlWhitespace]——同一语句的双路日志副本（named 行 trim 过 /
     * prepared 行尾随空白原样保留）仅差首尾空白，原文指纹判重永不命中。
     */
    val dedupeKey: String by lazy { sqlFingerprint(normalizeSqlWhitespace(restoredSql)) }

    /**
     * T-SqlSuppressUx（20260924 用户走查返修）：屏蔽匹配键 = [dedupeKey] 口径追加**尾部分号剥离**
     * ——规则粘贴态常带收尾 `;` 而日志还原链不带（用户实证「已拦截 0 次」根因，多行/单行空白差异
     * 本就被折叠）。与 [dedupeKey] 同属 body 属性不参与 equals/hashCode；仅在屏蔽规则集非空时被
     * 闸门/面板过滤触达（空集短路），无规则常态零成本。口径函数 = [suppressFingerprint]（规则侧
     * [cn.song.mybatisplus.visualizer.log.SqlSuppressRuleManager] 同源，两侧必须同函数）。
     */
    val suppressKey: String by lazy { suppressFingerprint(restoredSql) }

    /**
     * T-SqlSuppressUx 二段：结构匹配键（[structureFingerprint]，字面量/数字参数化）——仅当某条
     * **手动开启结构匹配**的规则的键集含本键时命中；与 [suppressKey] 同属 lazy 体属性，空集短路下
     * 无结构规则则零成本。
     */
    val structureKey: String by lazy { structureFingerprint(restoredSql) }

    // ---------- T-ListMode：多行显示模式行片段缓存（体属性不入 equals/copy；仅 EDT 渲染路径读写） ----------

    /**
     * 多行模式（KEYWORD_WRAP/FORMATTED）的语义行片段缓存：只存原文和 token 类型，
     * 不存 SimpleTextAttributes/颜色；消费者每次绘制按当前 scheme 投影，切主题无需重新分词。
     * SINGLE_LINE 不走缓存（固定行高 + 渲染直读）。
     */
    var cachedListLineFragments: List<List<SqlSemanticFragment>>? = null

    /** 缓存对应的显示模式——读取处按当前模式比对，模式变更自动失效（无需显式清缓存）。 */
    var cachedListLineFragmentsMode: SqlListDisplayMode? = null

    /**
     * 缓存对应的脱敏态（R60 T-LaneB D96；T-MagpieGems W2 起存 MaskMode 名）：读取处按当前
     * displayMaskMode 比对——模式切换（OFF/WARN 原样、MASK 打码、BLOCK 占位四态互异）后旧缓存
     * 自动失效重渲染，防脏缓存（V112.3 附带发现；W2 扩展）。
     */
    var cachedListLineFragmentsMaskMode: String = ""

    /**
     * 券01（T-PerfSweep27-01）头部行静态段/来源段条目级缓存：序号/时间/耗时/库/MCP/行数与
     * 来源片段只依赖条目不可变字段，每次可见格重绘都重算属浪费——对照上方多行语义片段
     * 缓存先例放条目字段上（EDT 单线程读写，无并发面）。
     * 中间的 ⨉去重/×N 同结构徽章不进缓存（输入是面板级快照 map，随流式灌入变化）。
     */
    var cachedHeaderCore: List<SqlTextFragment>? = null

    /** 来源段缓存（`[文件.java:行号]`；无源码位置为空列表），与 [cachedHeaderCore] 同键生命周期。 */
    var cachedHeaderSource: List<SqlTextFragment>? = null

    /**
     * 缓存键（[HeaderStaticCacheKey]）：showDuration+时间戳设置+角标细节+
     * 慢查询阈值——键含设置值，任一设置变更自动失效重算（显示格式严格跟随设置，免失效钩子）。
     */
    var cachedHeaderKey: HeaderStaticCacheKey? = null

    /** 脱敏展示前先处理完整 SQL，避免旧摘要在字面量引号中间截断后漏打码。 */
    var cachedMaskedSummary: String? = null

    /** [cachedMaskedSummary] 对应的 MaskMode 名（T-MagpieGems W2）：MASK/BLOCK 产物不同，切模式须失效。 */
    var cachedMaskedSummaryMode: String? = null

    companion object {
        private const val MAX_SUMMARY_LENGTH = 160

        internal fun summarizeSql(sql: String): String {
            val newlineIdx = sql.indexOf('\n')
            val endIdx = when {
                newlineIdx in 0 until MAX_SUMMARY_LENGTH -> newlineIdx
                else -> minOf(sql.length, MAX_SUMMARY_LENGTH)
            }
            val safeEndIdx = if (endIdx in 1 until sql.length &&
                Character.isHighSurrogate(sql[endIdx - 1]) && Character.isLowSurrogate(sql[endIdx])) {
                endIdx - 1
            } else endIdx
            return if (safeEndIdx < sql.length) sql.substring(0, safeEndIdx) + "…" else sql
        }
    }
}

/**
 * T-Perf-19（自 dev 移植）：SQL 指纹（FNV-1a 64 位十六进制串）——去重计数键用，避免以完整 SQL 原文为键
 * 的内存驻留。64 位散列下两条不同 SQL 撞键概率可忽略（~2^-64 量级）。
 */
internal fun sqlFingerprint(sql: String): String {
    var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (i in sql.indices) {
        h = h xor sql[i].code.toLong()
        h *= 0x100000001b3L // FNV-1a 64-bit prime
    }
    return java.lang.Long.toHexString(h)
}

/**
 * T-LogDedupeMpMark：去重口径的空白规范化（trim + 连续空白折叠为单空格）。
 * JdbcTemplate 双路日志对同一语句产出 named/prepared 两副本，仅差首尾空白；
 * 大小写与分号不动——COUNT 前缀判定、达梦带引号标识符大小写均不受影响。
 */
internal fun normalizeSqlWhitespace(sql: String): String =
    sql.trim().replace(WHITESPACE_RUN_REGEX, " ")

/**
 * T-SqlSuppressUx（20260924 用户走查返修）：屏蔽匹配口径 = 去重口径追加**尾部分号剥离**——
 * 规则粘贴态常带收尾 `;` 而日志还原链不带（用户实证 0 命中根因）。只在字符串收尾剥终结符：
 * `trimEnd(';')` 触不到字面量内分号（其后必有引号等其余内容，不满足「收尾」）。
 * 大小写仍不动（O-69 D2 定案：与既有规范化一致）；空白折叠先行（[normalizeSqlWhitespace]）。
 * 侧约定：[LogSqlEntry.suppressKey]（条目侧）与 [cn.song.mybatisplus.visualizer.log.SqlSuppressRuleManager]
 * 的键集重建（规则侧）必须同走本函数，两侧口径永不手写第二份。
 */
internal fun suppressFingerprint(sql: String): String =
    sqlFingerprint(normalizeSqlWhitespace(sql).trimEnd(';').trim())

/**
 * T-SqlSuppressUx 二段（20260924 用户定案「结构匹配手动开启」）：**结构指纹** = 屏蔽口径再追加
 * 字面量参数化——字符串字面量（`'…'`，含 `''` 转义）与裸数字各自折叠为 `?`，参数值不同的同形状
 * SQL 由此同指纹。边界（逐字扫描实现，非正则）：
 * - 双引号/反引号**标识符**不参数化（`"MyCol"`、`` `t1` `` 逐字保留——不同列/表 = 不同形状）；
 * - 标识符内数字不参数化（前邻词字符不满足边界，如 `t2024`、`plan_2026001`）；
 * - IN 列表按元素个数区分形状（`IN (?,?)` ≠ `IN (?,?,?)`）——同形状不同个数不命中，已知边界；
 * - 畸形未闭合字面量按一个 `?` 收尾（防御，不抛错）。
 * 仅对**手动开启结构匹配的规则**与条目侧 [LogSqlEntry.structureKey] 使用；精确口径规则不走此函数。
 */
internal fun structureFingerprint(sql: String): String =
    sqlFingerprint(parameterizeLiterals(normalizeSqlWhitespace(sql).trimEnd(';').trim()))

/** 字面量参数化（[structureFingerprint] 专属，逐字扫描；口径边界见其 KDoc）。 */
internal fun parameterizeLiterals(sql: String): String {
    val sb = StringBuilder(sql.length)
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' -> {
                // 字符串字面量整体折叠为 ?（'' 转义跳过；未闭合按防御收尾）
                var j = i + 1
                while (j < sql.length) {
                    if (sql[j] == '\'') {
                        if (j + 1 < sql.length && sql[j + 1] == '\'') {
                            j += 2
                            continue
                        }
                        break
                    }
                    j++
                }
                sb.append('?')
                i = if (j < sql.length) j + 1 else sql.length
            }
            c.isDigit() && (i == 0 || !isSqlWordChar(sql[i - 1])) -> {
                // 裸数字折叠为 ?；前邻词字符 = 标识符内数字（t2024/_id1），逐字保留
                var j = i
                while (j < sql.length && (sql[j].isDigit() || sql[j] == '.')) j++
                if (j < sql.length && isSqlWordChar(sql[j])) {
                    sb.append(sql, i, j) // 2024Q1 类：数字后随词字符，属标识符
                } else {
                    sb.append('?')
                }
                i = j
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

/**
 * O-90：同模板计数聚合（structureKey → 条数）——N+1 **捕获后聚类标记**的计数数据源
 * （与 Hibernate Debugger 的运行期实时检测差异化：对已捕获条目离线聚合，零运行时开销，
 * 不做实时 profiler——红线）。
 * 判别口径 = [structureFingerprint]（字面量参数化后同形状同键）：参数值不同的同模板 SQL 计入
 * 同键；IN 元素个数 / 表名 / 列名不同的异形互不计入。条目侧 structureKey 为 lazy 体属性
 * （每条只算一次，空集短路），本聚合 O(N) 单遍。
 */
internal fun structureKeyCounts(entries: List<LogSqlEntry>): Map<String, Int> =
    entries.groupingBy { it.structureKey }.eachCount()

/** O-90：同模板徽章显示阈值（≥N 显示「×N 同模板」；常量起步不做设置项——缩范围定案）。 */
internal const val TEMPLATE_BADGE_MIN_COUNT = 3

/** SQL 词字符（标识符边界判定）：字母/数字/下划线/美元符。 */
private fun isSqlWordChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '$'

/** T-PS4-04：空白折叠正则提文件级常量（dedupeKey lazy/stampLogTime/桥嗅探三路共用，一处受益三路）。 */
private val WHITESPACE_RUN_REGEX = Regex("""\s+""")

/**
 * T-AgentLogTime：日志时间关联键——**占位符版 rawSql 的指纹**。
 *
 * 增强通道的 logTimestamp 是派生的（控制台链嗅探暂存 → agent 事件取回），关联键必须两侧同源：
 * 控制台侧 rawSql = `Preparing:` 行原文，agent 侧 rawSql = `prepareStatement` 收到的原文，
 * 二者都不经过参数还原。旧口径用 [LogSqlEntry.dedupeKey]（还原后 SQL 指纹）要求两套还原器
 * 逐字符一致，布尔参数渲染、注释内 `?` 计数、参数个数守卫三类分叉都会静默失配。
 *
 * 与 [LogSqlEntry.dedupeKey] 的分工：dedupeKey 管面板去重（跨通道同 SQL 折叠，语义不动），
 * 本键只管日志时间配对——错位替换防护（agent 侧「占位符数 ≠ 参数数就不还原」）因此得以保留。
 */
internal fun logTimeKey(entry: LogSqlEntry): String = sqlFingerprint(normalizeSqlWhitespace(entry.rawSql))

/**
 * T-AgentLogTime：日志时间配对的参数**值**序列签名。
 * 同键多次执行只消费签名相同的候选；两侧口径分叉则未命中，不借其他执行的时间。
 * 无参语句两侧都是空串，天然相等，仍可按 FIFO 配对。
 * T-PS4-05：join 前逐值截断 [PARAM_SIGNATURE_MAX_CHARS]——超长 blob/JSON 参数不再整串进签名，
 * 相关器驻留有界（桥嗅探 put 与 agent take 共用本函数，截断天然等价）。
 */
internal fun logTimeParamSignature(params: List<LogSqlParam>): String =
    params.joinToString("\u0001") { (it.value ?: "\u0000").take(PARAM_SIGNATURE_MAX_CHARS) }

// ---------- T-AgentCaptureProvenance（O-147）：同去重桶字段级融合与归因诊断 ----------

/**
 * 同去重桶的**字段级信息融合**（O-147 W1 纯函数；调用方 = [cn.song.mybatisplus.visualizer.log.SqlLogEntryBuffer]
 * 去重命中分支）。
 *
 * 背景：混合通道期（增强确认前后回落窗、心跳抖动窗、闸门误开）同一条 SQL 可能先由控制台通道
 * 入库（无源码/无耗时/无库标），随后 agent 事件同指纹落窗内被「先到即留」去重丢弃——用户看到的
 * 保留条目缺跳转元数据，右键只剩 MissingSource 引导（20261014 真机截图形态：同屏 #9 无源码
 * `—` 条目与 #8 带 `[PostgreSQL] [RefundOrderService.java:67]` 的 agent 条目并存）。本函数把
 * 「后到者仅丢弃」升级为「后到者的更完整事实补齐保留条目」，规则：
 *
 *  - **桶身份不动**：rawSql/restoredSql/params/timestamp/sequenceNumber/summary 全部保留 existing
 *    ——列表位置与行号稳定，正在选中/复制的行不跳；捕获时间不因升级后移（排序不跳行）；
 *  - **只补空不覆盖**：耗时/影响行数/库标/mapper 语句/请求标签/报错/日志时间一律 `existing ?: incoming`
 *    ——后到事实只填缺失位，绝不改写已有非空值（agent 先到、控制台后到不得降级）；
 *  - **源码位置真实值优先**：existing 有真实文件（非 MP 分页标记）→ 保留；existing 为空或 MP
 *    分页标记而 incoming 有真实文件 → 升级（分页 COUNT 由此获得可跳转调用点，对应现场 #6→#7
 *    的形态）；反向（existing 真实、incoming 为标记/空）不劣化；
 *  - **候选帧/类名/栈快照只增不删**：仅 incoming 非空且 existing 为空时补（三者仅 agent 通道产出）；
 *  - **通道归并**见 [mergedProvenance]：跨通道 → MERGED；同通道纯重复 → 保持原值
 *    （返回值与 existing 全等，调用方据此跳过替换，不制造假刷新）。
 *
 * 不改变去重语义本身：窗口判定、指纹口径、`×N` 计数（一次升级同样计一次丢弃）均由调用方按
 * 既有路径处理。
 */
internal fun mergeDuplicateEntry(existing: LogSqlEntry, incoming: LogSqlEntry): LogSqlEntry {
    val existingFileReal = existing.sourceFile != null && !SourceMarkers.isMpPaginationCount(existing.sourceFile)
    val incomingFileReal = incoming.sourceFile != null && !SourceMarkers.isMpPaginationCount(incoming.sourceFile)
    val takeIncomingSource = !existingFileReal && incomingFileReal
    return existing.copy(
        sourceFile = when {
            takeIncomingSource -> incoming.sourceFile
            existing.sourceFile != null -> existing.sourceFile
            else -> incoming.sourceFile
        },
        sourceLine = when {
            takeIncomingSource -> incoming.sourceLine
            existing.sourceFile != null -> existing.sourceLine
            else -> incoming.sourceLine
        },
        sourceCandidates = if (existing.sourceCandidates.isEmpty()) incoming.sourceCandidates else existing.sourceCandidates,
        candidateClasses = if (existing.candidateClasses.isEmpty()) incoming.candidateClasses else existing.candidateClasses,
        stackDigest = existing.stackDigest ?: incoming.stackDigest,
        executionTimeMs = existing.executionTimeMs ?: incoming.executionTimeMs,
        updateCount = existing.updateCount ?: incoming.updateCount,
        databaseName = existing.databaseName ?: incoming.databaseName,
        dialectName = existing.dialectName ?: incoming.dialectName,
        mapperStatement = existing.mapperStatement ?: incoming.mapperStatement,
        requestTag = existing.requestTag ?: incoming.requestTag,
        errorText = existing.errorText ?: incoming.errorText,
        logTimestamp = existing.logTimestamp ?: incoming.logTimestamp,
        captureProvenance = mergedProvenance(existing.captureProvenance, incoming.captureProvenance),
    )
}

/**
 * 融合后的通道归并：existing 未打标（旧条目）→ 取 incoming；两侧已知且不同（跨通道观测）
 * → [CaptureProvenance.MERGED]；同通道重复 / 两侧都未知 → 保持 existing（不制造假融合）。
 */
internal fun mergedProvenance(existing: CaptureProvenance, incoming: CaptureProvenance): CaptureProvenance = when {
    existing == CaptureProvenance.UNKNOWN -> incoming
    incoming != CaptureProvenance.UNKNOWN && incoming != existing -> CaptureProvenance.MERGED
    else -> existing
}

/** 融合升级了哪些信息面（诊断文本用；O-147 W0）。 */
internal fun mergeUpgradedAspects(existing: LogSqlEntry, merged: LogSqlEntry): List<String> = buildList {
    if (existing.sourceFile != merged.sourceFile || existing.sourceLine != merged.sourceLine) add("源码")
    if (existing.sourceCandidates != merged.sourceCandidates) add("候选帧")
    if (existing.executionTimeMs != merged.executionTimeMs) add("耗时")
    if (existing.updateCount != merged.updateCount) add("行数")
    if (existing.databaseName != merged.databaseName) add("库标")
    if (existing.logTimestamp != merged.logTimestamp) add("日志时间")
    if (existing.stackDigest != merged.stackDigest) add("栈快照")
}

/**
 * 缺源码条目的归因诊断行（O-147 W0，[cn.song.mybatisplus.visualizer.log.SqlLogEntryBuffer] 成功
 * 入队路径限频输出）。**内容红线**：只有指纹前缀 / 布尔位 / 通道 / 闸门快照——绝不含 SQL 原文、
 * 参数值与连接凭据（诊断行进 idea.log，泄露面与用户偏好红线）。
 */
internal fun provenanceDiagText(entry: LogSqlEntry, gatePaused: Boolean): String =
    "捕获归因: 通道=${entry.captureProvenance} 来源=${entry.source} 指纹=${entry.dedupeKey.take(8)} " +
        "有源码=${entry.sourceFile != null} 有候选=${entry.sourceCandidates.isNotEmpty()} " +
        "有耗时=${entry.executionTimeMs != null} 有库标=${entry.databaseName != null} 桥封停=$gatePaused"

/**
 * 跳转反馈分流判据（O-147 W3）：控制台回落通道产出（含融合后仍无位置）且不带任何源码线索的
 * 条目——增强已在跑时弹「开启源码捕获」引导是误导，调用方改走轻提示。
 * UNKNOWN（旧条目/历史读回）维持既有引导不变（保守：不基于猜测改行为）。
 */
internal fun isConsoleFallbackWithoutSource(entry: LogSqlEntry): Boolean =
    entry.sourceFile == null && entry.sourceCandidates.isEmpty() && when (entry.captureProvenance) {
        CaptureProvenance.CONSOLE_EP, CaptureProvenance.CONSOLE_BRIDGE, CaptureProvenance.MERGED -> true
        else -> false
    }

/**
 * T-PS4-05：签名单值截断上限（256 字符）。两侧（桥嗅探 put 与 agent take）走同一函数天然等价；
 * 截断签名不同则拒绝配对；相同前缀的碰撞风险仍属于既有签名表示边界，本次不改编码。
 */
internal const val PARAM_SIGNATURE_MAX_CHARS = 256

/**
 * T-AgentLogTime：给 agent 条目回填控制台嗅探到的日志时间；未命中返回 null
 * （调用方回落 [LogSqlEntry.timestamp] 并留痕）。
 *
 * 增强通道回填的唯一入口——生产路径（`AgentManager.AgentSession`）与跨通道守卫测试都走它，
 * 避免测试另抄一份「取键 + take」逻辑而自证（T-AgentDialectLoop 的教训：两侧各自绿、集成不通）。
 */
internal fun stampLogTime(entry: LogSqlEntry): LogSqlEntry? =
    LogTimeCorrelator.take(logTimeKey(entry), logTimeParamSignature(entry.params))
        ?.let { entry.copy(logTimestamp = it) }

/**
 * 单个日志参数：值 + 类型标注。
 *
 * @param value 参数值文本（MyBatis 打印形态，如 `abc`、`1`、`2025-01-01 12:00:00.0`）；null 表示 `null` 字面量
 * @param type 类型标注（`(Long)` 中的 Long）；null 表示无类型标注（如 `null` 字面量或旧版日志）
 */
data class LogSqlParam(
    val value: String?,
    val type: String?,
)

/**
 * 条目渲染回 MyBatis 日志文本（T-M38「发送到日志转 SQL」）：`==>  Preparing: <rawSql>` +
 * `==> Parameters: <v(type), ...>`。与 [MyBatisLogParser] 的宽容匹配（mixedPrefix）互逆——
 * 渲染文本可再次解析出等价条目（round-trip）。
 */
internal fun LogSqlEntry.toLogText(): String = buildString {
    append("==>  Preparing: ").append(rawSql)
    if (params.isNotEmpty()) {
        append('\n').append("==> Parameters: ")
        append(params.joinToString(", ") { it.toParamText() })
    }
}

/** 单个参数渲染回日志形态：`1(Long)` / `abc(String)` / `null`（无类型标注直出值）。 */
private fun LogSqlParam.toParamText(): String {
    val v = value ?: return "null"
    return if (type != null) "$v($type)" else v
}
