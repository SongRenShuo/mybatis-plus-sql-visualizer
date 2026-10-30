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
            """"(matched|matchedByInterface|weavable|woven|failed|unmodifiable|interfaceWoven|skippedByVersion|catchAll)"\s*:\s*(-?\d{1,15})""",
        )

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
            )
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
