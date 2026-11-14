package cn.song.mybatisplus.visualizer.log.agent

import java.util.concurrent.atomic.AtomicLong

/**
 * agent 通信协议常量与编解码（§4.3，M56 T-M56-01/02）：
 * loopback TCP，长度前缀帧（4 字节 len + 1 字节 command + JSON body UTF-8）。
 * 命令三值起步 + HELLO（agent 连接后自报 pid）；loadAgent 参数 k=v 分号分隔。
 * 两侧（agent Java / IDE Kotlin）各自实现同一契约——本对象是 IDE 侧权威定义，
 * agent 侧 SenderLoop/EventBus 的常量须与这里逐值一致（AgentProtocolSyncTest 锁定）。
 *
 * T-AgentConfigChannel T-02：新增 agent → IDE 方向的 [CMD_CONFIG_ACK]（控制指令回执）——
 * 此前只有 IDE → agent 单向，IDE 侧日志只证明「指令已写出」，应用侧生效与否零证据。
 */
object AgentProtocol {

    /** 事件帧：SQL 结构化事件流（JSON body，见 [AgentEvent]）。 */
    const val CMD_EVENT = 1
    /** 心跳帧：静默 10s 一发，在线态判定依据。 */
    const val CMD_HEARTBEAT = 2
    /** 配置帧（IDE → agent）：禁用开关/脱敏开关下发。 */
    const val CMD_CONFIG = 3
    /** 握手帧：agent 连接后首帧，body `{"pid":<n>}`。 */
    const val CMD_HELLO = 4

    /**
     * 配置回执帧（agent → IDE，T-AgentConfigChannel T-02）：应用侧把 CONFIG 应用到采集开关后
     * 回一帧，body 见 [configAckBody]。
     *
     * 为什么需要：本轮现场（开关未开却被捕获）里 IDE 侧只有「CONFIG 已下发」的日志，
     * 应用侧收到与否零证据——排障只能靠行为反推（全天无 `startCapture` 却有条目）。回执把
     * 「生效」从推断变成一行可核实的日志，并把「控制通道静默失效」暴露成可观测事实。
     *
     * 取值 5（下一个空闲号）。**旧 agent 天然兼容**（未知 cmd 走 else 分支忽略），故本常量
     * 只在支持回执的新 agent 侧出现——`AgentProtocolSyncTest.命令常量两侧一致` 因此暂不锁它，
     * 待应用侧 T-02 落地后由两侧同步补断言。
     */
    const val CMD_CONFIG_ACK = 5

    /** 单帧 payload 上限（1MB：SQL/堆栈文本远小于此，超限视为流损坏立即断开）。 */
    const val MAX_FRAME_LEN = 1 shl 20

    /**
     * agent 侧心跳间隔（§4.3 静默 10s）——与 agent `SenderLoop.DEFAULT_HEARTBEAT_MS` 同值，
     * 由 [AgentProtocolSyncTest] 源码扫描锁定（`ONLINE_WINDOW_MS >= 3 * 该值`）。
     */
    const val HEARTBEAT_INTERVAL_MS = 10_000L

    /**
     * 在线态判定：最近 N 毫秒内有心跳或事件。
     *
     * **不变量：窗口 = 3 倍心跳间隔**——留 3 次心跳的容错余量（一次网络/调度抖动、一次事件突发
     * 占用发送线程、一次入站读阻塞跨过判定点）。窗口不再与心跳间隔各自写死：此前 agent 侧按
     * 「假定每轮 200ms」累加计时，「静默 10s」实际要 60s 才发（真实每轮≈1.2s），而窗口 30s 照旧
     * 执行 → 增强捕获会话建立后 30 秒必被摘除（T-AgentHeartbeat 现场：HELLO +17ms、首条
     * HEARTBEAT +60633ms）。值未变（心跳 10s → 窗口 30s），推导关系写进代码，任一侧漂移都会在
     * `AgentProtocolSyncTest.心跳节奏_窗口不小于三倍间隔` 转红。
     */
    const val ONLINE_WINDOW_MS = HEARTBEAT_INTERVAL_MS * 3

    /**
     * loadAgent 参数串（§4.3 k=v 契约，agent 侧 AgentArgs.parse 对应）。
     * T-StackHoverUi：追加 `stackFrames`（调用栈快照总帧数；旧 agent 的 AgentArgs.parse 容忍未知键）。
     * 缺省 24 与 PluginSettings.DEFAULT_ENHANCED_CAPTURE_STACK_MAX_FRAMES 同值
     * （协议层不反向依赖 config 包；生产调用方一律显式传设置值）。
     *
     * T-SAF-02：追加 `token`（通道令牌，[newChannelToken] 产物）——loopback 绑定不是对端认证，
     * 本机任意进程都能连上协商端口；令牌经 agentArgs 下发（唯一出口是目标 JVM 的 agent 参数，
     * 与端口同通道、同等可见性），agent 在 HELLO 帧回带，IDE 侧验令牌通过才提交会话。
     * null 时省略键（既有调用方/测试的零兼容负担；生产链路一律非 null）。
     */
    fun agentArgs(port: Int, logLevel: String = "warn", stackMaxFrames: Int = 24, channelToken: String? = null): String {
        val base = "port=$port;logLevel=$logLevel;stackFrames=$stackMaxFrames"
        return if (channelToken.isNullOrEmpty()) base else "$base;token=$channelToken"
    }

    /** 通道令牌随机源（T-SAF-02；SecureRandom，进程内复用）。 */
    private val channelTokenRandom = java.security.SecureRandom()

    /**
     * T-SAF-02：生成通道令牌（128bit SecureRandom → 32 位 hex）。每通道（premain 协商 /
     * attach 一次性 ServerSocket / 原端口 re-bind）各生成一次，绝不跨通道复用；
     * hex 形态无需 JSON/参数转义，令牌值不落任何日志。
     */
    fun newChannelToken(): String {
        val bytes = ByteArray(16)
        channelTokenRandom.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0x0f])
            sb.append("0123456789abcdef"[b.toInt() and 0x0f])
        }
        return sb.toString()
    }

    /** HELLO 帧内令牌键的提取（T-SAF-02；agent 侧 SenderLoop.helloBody 的 `,"token":"<hex>"` 对应）。 */
    internal val HELLO_TOKEN_REGEX = Regex("\"token\"\\s*:\\s*\"([0-9a-fA-F]+)\"")

    /** 从 HELLO body 提取通道令牌（无该键/畸形返回 null——调用方按「无令牌」拒绝）。 */
    internal fun parseHelloToken(helloBody: String): String? =
        HELLO_TOKEN_REGEX.find(helloBody)?.groupValues?.get(1)

    /** CONFIG 帧 body：禁用/脱敏开关（agent 侧极简 contains 解析对应——键序固定）。 */
    fun configBody(disabled: Boolean, desensitize: Boolean): String =
        """{"disabled":$disabled,"desensitize":$desensitize}"""

    /**
     * T-SourceJumpFunnel P1-3：CONFIG 帧扩展 body——追加用户登记的帧过滤前缀
     * （`"skipPrefixes":["a.","b."]`；空列表省略键，旧 agent contains 解析天然忽略未知键）。
     * 前缀文本 JSON 转义走最简 deny-list（反斜杠/引号/控制字符），与 [configBody] 的
     * 固定布尔键序兼容（agent 侧 disabled/desensitize 判定不受追加键影响）。
     */
    fun configBody(disabled: Boolean, desensitize: Boolean, skipPrefixes: List<String>): String =
        configBody(disabled, desensitize, skipPrefixes, emptyList())

    /** 无 [stackMaxFrames] 的四参形态保留兼容（帧数取协议缺省 24）；生产走五参重载。 */
    fun configBody(
        disabled: Boolean,
        desensitize: Boolean,
        skipPrefixes: List<String>,
        bizJarPrefixes: List<String>,
    ): String = configBody(disabled, desensitize, skipPrefixes, bizJarPrefixes, stackMaxFrames = 24)

    /**
     * T-StackHoverUi：CONFIG 帧再追加调用栈快照总帧数（`"stackFrames":<int>`，恒带键无空格——
     * agent 侧 SenderLoop 按 `"stackFrames":` 定位后扫数字；缺省键=保持现值）。旧 agent contains
     * 解析天然忽略未知键；CONFIG 回执（CMD_CONFIG_ACK）形态不动——帧数无需生效回执。
     */
    fun configBody(
        disabled: Boolean,
        desensitize: Boolean,
        skipPrefixes: List<String>,
        bizJarPrefixes: List<String>,
        stackMaxFrames: Int,
    ): String {
        val clean = skipPrefixes.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanBiz = bizJarPrefixes.map { it.trim() }.filter { it.isNotEmpty() }
        val parts = mutableListOf(
            "\"disabled\":$disabled",
            "\"desensitize\":$desensitize",
        )
        // 空列表省略键（agent 侧缺键=清空，语义等同）
        if (clean.isNotEmpty()) {
            parts += "\"skipPrefixes\":[" + clean.joinToString(",") { escapeArrayItem(it) } + "]"
        }
        if (cleanBiz.isNotEmpty()) {
            parts += "\"bizJarPrefixes\":[" + cleanBiz.joinToString(",") { escapeArrayItem(it) } + "]"
        }
        parts += "\"stackFrames\":$stackMaxFrames"
        return parts.joinToString(",", "{", "}")
    }

    /** 数组项最简 deny-list JSON 转义（反斜杠/引号/控制字符）。 */
    private fun escapeArrayItem(p: String): String =
        "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\b", "\\b").replace("\u000c", "\\f")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    // ---------- T-AgentConfigChannel T-02：CONFIG 回执（agent → IDE） ----------

    /**
     * CONFIG 回执生效值四元（[parseConfigAck] 产物）。
     * [skipPrefixes]/[bizJarPrefixes] 是应用侧**实际接受**的帧过滤前缀与 JAR 业务帧反向名单条数
     * （不是列表本身——回执只报生效结果，前缀文本两侧各自持有，IDE 侧下发源即设置页）。
     */
    internal data class ConfigAck(
        val disabled: Boolean,
        val desensitize: Boolean,
        val skipPrefixes: Int,
        val bizJarPrefixes: Int,
    )

    /**
     * 回执 body（agent 侧同契约逐字节一致）：
     * `{"disabled":<bool>,"desensitize":<bool>,"skipPrefixes":<int>,"bizJarPrefixes":<int>}`。
     *
     * 键序固定、无空格——**这是刻意的严格形态**：两侧各自手写 JSON，本轮缺陷正是「形态漂移
     * 无人发现」（出站写 `[len][cmd]`、入站按 `[cmd][len]` 读，两侧都自认为对）。回执只认
     * 这一种形态，任何偏差都会被 [parseConfigAck] 记成畸形并留痕，不会静默按「差不多」放行。
     * bizJarPrefixes 为 T-ToolSiteBizFrame 列尾追加段（agent 与 IDE 同制品分发、版本恒同对；
     * 旧 agent 错配窗口的 3 键 ACK 按畸形留痕——与 skipPrefixes 引入时同边界）。
     */
    internal fun configAckBody(
        disabled: Boolean,
        desensitize: Boolean,
        skipPrefixes: Int,
        bizJarPrefixes: Int,
    ): String =
        """{"disabled":$disabled,"desensitize":$desensitize,"skipPrefixes":$skipPrefixes,"bizJarPrefixes":$bizJarPrefixes}"""

    /** 严格形态判据（无空格、键序固定、值域受限）。 */
    private val CONFIG_ACK_REGEX =
        Regex("""\{"disabled":(true|false),"desensitize":(true|false),"skipPrefixes":(-?\d+),"bizJarPrefixes":(-?\d+)\}""")

    /**
     * 回执解析失败累计（诊断计数，跨会话单调递增）：畸形回执是「两侧契约漂移」的直接证据，
     * 故不只丢帧，还要计数（会话侧 WARN 会带出该累计值）。
     */
    internal val configAckParseFailures = AtomicLong()

    /**
     * 解析 CONFIG 回执 body；**不抛**——非 [configAckBody] 的严格形态一律返回 null 并累加
     * [configAckParseFailures]（调用方只记 WARN 不中断读循环；畸形回执不构成掉线理由）。
     * `skipPrefixes`/`bizJarPrefixes` 溢出 Int 同样按畸形处理（回执值域本就该是小整数）。
     */
    internal fun parseConfigAck(body: String): ConfigAck? {
        val m = CONFIG_ACK_REGEX.matchEntire(body)
        val n = m?.groupValues?.get(3)?.toIntOrNull()
        val biz = m?.groupValues?.get(4)?.toIntOrNull()
        if (m == null || n == null || biz == null) {
            configAckParseFailures.incrementAndGet()
            return null
        }
        return ConfigAck(
            disabled = m.groupValues[1] == "true",
            desensitize = m.groupValues[2] == "true",
            skipPrefixes = n,
            bizJarPrefixes = biz,
        )
    }
}
