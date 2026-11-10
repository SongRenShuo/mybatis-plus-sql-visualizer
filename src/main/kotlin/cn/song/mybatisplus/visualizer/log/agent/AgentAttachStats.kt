package cn.song.mybatisplus.visualizer.log.agent

import java.util.concurrent.atomic.AtomicReference

/**
 * T-CaptureCatchAll 券03：agent 侧 **attach 覆盖自检** 计数的 IDE 侧镜像。
 *
 * 立项动因：agent 的 `retransformLoadedDrivers` 只在宿主 stderr 打一行 `matched=N wove=M …`，
 * IDE 侧完全看不到——白名单漏一个驱动时，用户视角是「插件坏了」，面板连一句「本次 attach
 * 命中 0 个驱动」都说不出来（调研报告 §1.2「看着支持、一条不出」的静默来源）。
 *
 * 传输约定：agent 把计数放进任意**入站帧 body** 的 JSON 键（C1 路当前挂在心跳 body：
 * `{"weavable":…,"woven":…,"failed":…,"matchedByInterface":…,"interfaceWoven":…,"skippedByVersion":…,"catchAll":…}`）。
 * 本侧解析刻意做成**宽容扫描**（只认 `"key":数字` 形态，不要求整体是合法 JSON、不要求键序）：
 *  - 旧版 agent 的 body（`{}` 或事件 JSON）不含这些键 → [parse] 返回 null，零行为变化；
 *  - 新键（如 C1 后续再加计数）无需改本侧即可忽略；
 *  - 任何解析异常都被吞掉——诊断计数绝不能影响事件主链（红线：失败不影响既有采集）。
 *
 * 口径：数值为 agent 侧**单调累计**的最近一次上报值（每次上报整体覆盖，不做加法——重连/重附后
 * agent 侧计数从零起算，加法会把上一轮的值带进来）。
 *
 * 注意 [matched] 与 [weavable] 的关系：attach 循环的 `matched=N` 目前只打宿主 stderr，心跳里
 * 未携带；心跳的 `weavable` 是 transformer 入口命中数（同一批类的另一视角）。故「零命中」判据
 * 取两者较大值 [matchedTotal]，避免「心跳只有 weavable 却报命中 0」的假事实。
 */
internal data class AgentAttachStats(
    /** 白名单 + 接口兜底合计命中的驱动类数（agent 侧 `matched`；心跳可能不携带）。 */
    val matched: Long = 0L,
    /** transformer 入口可织类数（agent 侧 `weavable`）。 */
    val weavable: Long = 0L,
    /** 真正产出新字节的类数（agent 侧 `woven`）。 */
    val woven: Long = 0L,
    /** 仅由接口兜底（白名单未命中）命中的类数——C1 路新增；缺省 0 表示旧版 agent 未上报。 */
    val matchedByInterface: Long = 0L,
    /** 接口兜底命中里真正织入的类数（C1 路 `interfaceWoven`）。 */
    val interfaceWoven: Long = 0L,
    /** retransform 失败的类数（JVM 拒绝/版本不支持等）。 */
    val failed: Long = 0L,
    /** 不可重转换（`isModifiableClass` 为假）的类数。 */
    val unmodifiable: Long = 0L,
    /** 类文件 major 版本闸挡下的类数（C1 路 `skippedByVersion`）——「命中了但没织」的一条硬原因。 */
    val skippedByVersion: Long = 0L,
    /** 兜底状态码（C1 路 `catchAll`）：0=纯白名单，1=transformer 兜底开，2=已熔断。 */
    val catchAll: Int = 0,
    /**
     * T-IbatisPanwei 券 D：接口命中但**不在驱动白名单**的类名（agent 心跳 `unreg` 键，逗号分隔、
     * 有界 8 个）。空 = 旧版 agent 未上报，或本次确实没有未登记实现——两者都按「无事实可报」处理。
     *
     * 为什么要有这条：磐维现场「增强零产出」全靠人工猜驱动包名才定位到「白名单漏登记」。
     * 类名本身就是「下一个国产驱动是谁」的取证（计划券 D 也是券 C 拿不到 jar 时的替代路径）。
     */
    val unregisteredImplementations: List<String> = emptyList(),
    /**
     * T-SqlListCatchup 券 F2a：agent 侧事件队列**丢弃累计**（agent 心跳 `dropped` 键，单调累计、
     * 整体覆盖口径与 [matched] 等键一致）。0 = 旧版 agent 未上报，或确实零丢弃——状态栏只对
     * >0 挂段，两种「0」都不产生噪音。
     */
    val dropped: Long = 0L,
    /**
     * T-PanweiInsertCapture F3：登记盲区 PS 实现类名（agent 心跳 `blind` 键，逗号分隔、有界 8 个）。
     *
     * 与 [unregisteredImplementations] 不是一回事：`unreg` 是「类不在驱动白名单」，`blind` 是
     * 「类在织入面内、但执行时登记表里查不到它的 SQL」。后者才是「同一秒 SELECT 能采到、INSERT
     * 采不到」在实例级的最直接解释。空 = 旧 agent 未上报或确实无盲区。
     */
    val blindClasses: List<String> = emptyList(),
    /**
     * T-PanweiInsertCapture F2：`onExecuteExit` 外层 `catch (Throwable)` 吞掉的组装失败累计
     * （agent 心跳 `assembleFailures` 键）。事件「组不出来」此前零出口，>0 即说明存在静默丢弃。
     */
    val assembleFailures: Long = 0L,
    /**
     * T-PanweiInsertCapture F2：被 `NestGate` 抑制的外层帧累计（心跳 `nestSuppressed` 键）。
     * 正常嵌套（Hikari/MyBatis 包装层）会产生少量计数；**持续增长**才是线程残留态的信号。
     */
    val nestSuppressed: Long = 0L,
    /**
     * T-NestGateObserve：**陈旧嵌套**累计（心跳 `nestStale` 键）。
     *
     * 与 [nestSuppressed] 的差别是本轮立项的核心：后者只统计「被内层挤掉的外层帧」，而深度泄漏时
     * 被抑制的是最外层自己（该路径不计）。>0 即说明某条线程存在一条活过阈值（默认 5s）的嵌套链，
     * 也就是「深度未归零」的硬证据。
     */
    val nestStale: Long = 0L,
    /**
     * T-NestGateObserve：进程级最高嵌套深度（心跳 `nestMaxDepth` 键）。与 [nestStale] 配对读——
     * 泄漏后深度持续攀升说明残留帧在累加，而不是一次性的单帧错位。
     */
    val nestMaxDepth: Long = 0L,
    /**
     * T-PanweiInsertCapture F3：登记盲区累计（心跳 `unresolved` 键），与 [blindClasses] 配对读。
     */
    val unresolvedBlind: Long = 0L,
) {
    /** 命中类数（`matched` 与 `weavable` 取大——心跳只带后者时的零命中判据）。 */
    val matchedTotal: Long get() = maxOf(matched, weavable)

    /** 本次 attach 是否零命中——「增强捕获本会话无事件来源」的硬事实。 */
    val zeroMatched: Boolean get() = matchedTotal <= 0L

    /** 是否有任何一类织入产物（transformer 白名单织入 + 接口兜底织入）。 */
    val anyWoven: Boolean get() = woven > 0L || interfaceWoven > 0L

    /** transformer 兜底已触发 kill switch（C1 路熔断态）。 */
    val catchAllKilled: Boolean get() = catchAll == 2

    companion object {
        /** 单键匹配：`"matched":12`（允许冒号后空白；值域只认整数，防止把 JSON 字符串误读成数字）。 */
        private val KEY_VALUE = Regex(
            """"(matched|matchedByInterface|weavable|woven|failed|unmodifiable|interfaceWoven|skippedByVersion|catchAll|dropped|assembleFailures|nestSuppressed|nestStale|nestMaxDepth|unresolved)"\s*:\s*(-?\d{1,15})""",
        )

        /**
         * 券 D：未登记实现类名（`"unreg":"a.b.C,d.e.F"`）。
         *
         * 值域刻意只收「类名字符 + 逗号 + 空格」：任何其它字符（引号、反斜杠、换行、SQL 片段）
         * 都说明 body 形态漂移，宁可不解析也不把脏串端到面板上。空格容忍是为了「逗号后带空格」
         * 这类无害写法（逐项 trim 后丢弃空项）。
         */
        private val UNREG_VALUE = Regex("\"unreg\"\\s*:\\s*\"([A-Za-z0-9_.$, ]*)\"")

        /**
         * T-PanweiInsertCapture F3：登记盲区类名（`"blind":"a.b.C,d.e.F"`）。
         * 值域与 [UNREG_VALUE] 同款（只类名字符 + 逗号 + 空格）——脏串宁可不解析。
         */
        private val BLIND_VALUE = Regex("\"blind\"\\s*:\\s*\"([A-Za-z0-9_.$, ]*)\"")

        /** 未登记实现的上报上限（与 agent 侧 `CaptureBudget.MAX_UNREGISTERED` 同值；超出的丢弃）。 */
        const val MAX_UNREGISTERED = 8

        /** 一次上报里至少要有一个已知键才认；否则返回 null（事件帧/旧心跳形态一律不认）。 */
        fun parse(body: String): AgentAttachStats? {
            if (body.isEmpty() || body.length > MAX_BODY_CHARS) return null
            // 廉价前置门：事件帧（含 SQL 文本）逐帧跑正则不值得；只对含 attach 计数字样的 body 解析。
            if (!body.contains("\"matched\"") && !body.contains("\"weavable\"") &&
                !body.contains("\"matchedByInterface\"")
            ) {
                return null
            }
            val values = HashMap<String, Long>(8)
            for (m in KEY_VALUE.findAll(body)) {
                // 同名键重复时以最后一次为准（agent 侧同一 body 不应重复；重复即畸形，取最新无害）
                values[m.groupValues[1]] = m.groupValues[2].toLongOrNull() ?: continue
            }
            if (values.isEmpty()) return null
            return AgentAttachStats(
                matched = values["matched"] ?: 0L,
                weavable = values["weavable"] ?: 0L,
                woven = values["woven"] ?: 0L,
                matchedByInterface = values["matchedByInterface"] ?: 0L,
                interfaceWoven = values["interfaceWoven"] ?: 0L,
                failed = values["failed"] ?: 0L,
                unmodifiable = values["unmodifiable"] ?: 0L,
                skippedByVersion = values["skippedByVersion"] ?: 0L,
                catchAll = (values["catchAll"] ?: 0L).toInt(),
                unregisteredImplementations = parseUnregistered(body),
                dropped = values["dropped"] ?: 0L,
                blindClasses = parseCsvNames(body, BLIND_VALUE),
                assembleFailures = values["assembleFailures"] ?: 0L,
                nestSuppressed = values["nestSuppressed"] ?: 0L,
                nestStale = values["nestStale"] ?: 0L,
                nestMaxDepth = values["nestMaxDepth"] ?: 0L,
                unresolvedBlind = values["unresolved"] ?: 0L,
            )
        }

        /** `unreg` 值 → 类名列表（去空白、去空项、去重、截到 [MAX_UNREGISTERED]；无键=空列表）。 */
        private fun parseUnregistered(body: String): List<String> =
            parseCsvNames(body, UNREG_VALUE)

        /** 通用 CSV 类名解析（`"key":"a,b"` → 列表；值域非法/空/无键 → 空列表）。 */
        private fun parseCsvNames(body: String, pattern: Regex): List<String> {
            val raw = pattern.find(body)?.groupValues?.get(1) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_UNREGISTERED)
        }

        /** 单帧 body 上限：attach 计数行是短 JSON；超长 body（事件/堆栈）直接跳过，避免无谓扫描。 */
        private const val MAX_BODY_CHARS = 8 * 1024
    }
}

/**
 * 最近一次 attach 覆盖上报（进程级单例，诊断行只读）。
 *
 * 为什么是进程级而非 project 级：attach 计数的归属是「agent 实例」，与项目无关；一个 IDE 里
 * 多个项目共用一个 JVM 时，最近一次 attach 就是用户当下要看的事实。项目 dispose 时由
 * [AgentManager.dispose] 清空，避免跨项目残留。
 */
internal object AgentAttachReport {

    private val latest = AtomicReference<AgentAttachStats?>(null)

    /** 读循环逐帧调用：命中即整体覆盖；不命中零动作。异常全吞（红线）。 */
    fun updateFromFrame(body: String) {
        runCatching {
            AgentAttachStats.parse(body)?.let { latest.set(it) }
        }
    }

    /** 最近一次上报（null = 旧版 agent 未上报 / 尚未 attach）。 */
    fun latest(): AgentAttachStats? = latest.get()

    /** 项目销毁 / 测试收尾清空。 */
    fun reset() {
        latest.set(null)
    }
}
