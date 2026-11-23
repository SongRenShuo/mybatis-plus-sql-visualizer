package cn.song.mybatisplus.visualizer.log.agent

/**
 * T-M56Phase2-Premain：premain 注入决策与 VM 选项拼装（纯函数，无平台依赖）。
 *
 * 拆出来的理由有两条：一是注入链要过四道闸门（增强开关 / 目标 JVM 类版本 / agent jar 可用 /
 * 端口协商），组合面靠真机跑不现实，抽成纯函数后表驱动一次覆盖；二是注入发生在**用户运行配置的
 * 准备期**，任何一步失败都不能影响用户 Run——判定与副作用分离，调用方（[AgentManager]）只在
 * [Decision.Inject] 时落盘/协商，其余分支只留日志。
 *
 * 闸门顺序即求值顺序（[decide] 的 when 分支序）：开关为假或目标 JVM 太老时，调用方**不得**触发
 * jar 提取与端口协商（后者会开 ServerSocket），[agentJarAvailable] / [port] 传占位值即可。
 *
 * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认闸门已移除——开关打开即注入。
 * T-OldJvmPremainGuard（2026-11-20）：新增目标 JVM 类版本闸——JDK 6/7 上 `-javaagent` 载不动
 * major 52 的入口类，JVM 会在 premain 阶段直接终止（不是采集失败，是宿主起不来）。
 */
internal object PremainInjection {

    /** 增强捕获总开关关闭（复用 [AgentManager.isEnhancedEnabled] 语义）。 */
    const val REASON_DISABLED = "enhanced-disabled"

    /** agent jar 解压落盘失败（资源缺失/磁盘不可写）。 */
    const val REASON_NO_JAR = "agent-jar-unavailable"

    /** 端口前置协商失败（loopback 绑定失败或挂起通道已达上限）。 */
    const val REASON_NO_PORT = "port-negotiation-failed"

    /**
     * T-OldJvmPremainGuard 券01：目标 JVM 的类版本上限低于 agent 产物（跳过注入）。
     *
     * agent 按 Java 8 编译（major 52）；JDK 6/7 的上限是 50/51。入口类加载不了时 `-javaagent`
     * 处理失败会让**目标 JVM 启动即终止**（用户现场：JDK 1.6 项目开增强捕获后起不来），
     * 故这是注入侧必须拦住的一条——它不是「少采集」，是「别杀宿主」。
     */
    const val REASON_TARGET_JVM_TOO_OLD = "target-jvm-too-old"

    /** agent 产物（`AgentMain` 及其依赖）的类文件 major：Java 8。升级 agent 编译目标时同步。 */
    const val MIN_TARGET_CLASS_MAJOR = 52

    /** 注入判定结果。 */
    sealed interface Decision {
        /** 注入：[port] 为已前置协商并处于监听态的 loopback 端口。 */
        data class Inject(val port: Int) : Decision

        /** 跳过：[reason] 取本对象常量，仅用于日志与测试断言。 */
        data class Skip(val reason: String) : Decision
    }

    /**
     * 目标 JVM 是否认得 agent 字节码（纯判定）。[maxMajor] 为 null = 探测不出（未知）→ 放行：
     * 未知时宁可注入（结构性保护由 agent 侧引导类承担，见 T-OldJvmPremainGuard 券02），
     * 也不因为探测能力不足把能力整体关掉。
     */
    fun targetJvmSupported(maxMajor: Int?): Boolean = maxMajor == null || maxMajor >= MIN_TARGET_CLASS_MAJOR

    /**
     * 闸门判定（纯函数）。闸门顺序即求值顺序：开关 → 目标 JVM 版本 → jar 可用 → 端口。
     * [port] 为 null/非正即视为协商失败——端口 0 在 agent 侧 `AgentArgs.parse` 后会被
     * `AgentMain.install` 判为「无端口可上报」直接放弃挂载（AgentMain.java:45-48），故这里同样拒绝。
     *
     * T-OldJvmPremainGuard：版本闸放在 jar/端口之前——老 JVM 上既不落盘 jar 也不开 ServerSocket。
     */
    fun decide(
        enhancedEnabled: Boolean,
        agentJarAvailable: Boolean = true,
        port: Int? = null,
        targetJvmMaxMajor: Int? = null,
    ): Decision = when {
        !enhancedEnabled -> Decision.Skip(REASON_DISABLED)
        !targetJvmSupported(targetJvmMaxMajor) -> Decision.Skip(REASON_TARGET_JVM_TOO_OLD)
        !agentJarAvailable -> Decision.Skip(REASON_NO_JAR)
        port == null || port <= 0 -> Decision.Skip(REASON_NO_PORT)
        else -> Decision.Inject(port)
    }

    /**
     * `-javaagent` VM 选项：`-javaagent:<jar 绝对路径>=port=<n>;logLevel=warn;stackFrames=<n>[;token=<hex>]`。
     *
     * args 段直接复用 [AgentProtocol.agentArgs]（AgentProtocol.kt:62），与 agent 侧
     * `AgentArgs.parse`（AgentArgs.java:20-55）的 `k=v` 分号契约逐字对齐——两侧任一侧改动
     * 都会被 `AgentProtocolSyncTest` 的常量对齐守卫拦下。
     * T-StackHoverUi：[stackMaxFrames] 由调用方传设置值（本类保持纯函数不依赖 config 包；
     * 缺省 24 与 PluginSettings 缺省同值）。
     * T-SAF-02：[channelToken] 为本通道令牌（生产链路恒非 null，随 args 下发、agent HELLO 回带）；
     * null 时省略键（测试/兼容形态）。
     */
    fun vmOption(jarPath: String, port: Int, stackMaxFrames: Int = 24, channelToken: String? = null): String =
        "-javaagent:$jarPath=${AgentProtocol.agentArgs(port, stackMaxFrames = stackMaxFrames, channelToken = channelToken)}"
}
