package cn.song.mybatisplus.visualizer.ui

/**
 * T-StackHoverUi：调用栈快照串的结构化解析与调试器 Frames 风格展示模型。
 *
 * 快照串由 agent 侧 `StackDigest.build(StackTraceElement[])` 产出，格式
 * `pkg.Class#method:line<-pkg.Class#method:line<-…`（`<-` 连接、栈顶在左、行号负数不写、
 * 帧数随配置钳位在 12～64；深栈采样时用独立的中段省略标记）。本对象保留已采集帧
 * 与省略段的原始次序，供 [StackFrameHover] 展示与折叠。
 */
internal data class StackFrameEntry(
    /** 全限定类名（`#` 之前；内嵌类/代理类 `$` 段原样保留）。 */
    val fqcn: String,
    /** 方法名（`#` 之后、行号 `:n` 之前；`lambda$run$0` 等 `$` 段原样保留）。 */
    val method: String,
    /** 源码行号（agent 侧语义：负数不写 `:n`，此处 0 = 未知）。 */
    val line: Int,
)

internal sealed class StackDigestItem {
    data class Frame(val entry: StackFrameEntry) : StackDigestItem()
    data class Omitted(val count: Int?) : StackDigestItem()
}

/** Only proven library/agent frames are folded. Unknown namespaces remain visible. */
internal sealed class StackFramesNode {
    abstract fun frames(): List<StackFrameEntry>
    data class Visible(val frame: StackFrameEntry) : StackFramesNode() {
        override fun frames() = listOf(frame)
    }
    data class Internal(val entries: List<StackFrameEntry>) : StackFramesNode() {
        val frames: List<StackFrameEntry> get() = entries
        override fun frames() = entries
    }
    data class Omission(val count: Int?) : StackFramesNode() {
        override fun frames(): List<StackFrameEntry> = emptyList()
    }
}

internal data class StackFramesProjection(
    val original: String?,
    val nodes: List<StackFramesNode>,
    val noProjectFrames: Boolean,
) {
    companion object {
        fun build(digest: String?): StackFramesProjection {
            val nodes = mutableListOf<StackFramesNode>()
            val pending = mutableListOf<StackFrameEntry>()
            fun flush() {
                if (pending.isNotEmpty()) {
                    nodes.add(StackFramesNode.Internal(pending.toList()))
                    pending.clear()
                }
            }
            for (item in StackDigestFrames.parseItems(digest)) {
                when (item) {
                    is StackDigestItem.Omitted -> {
                        flush()
                        nodes.add(StackFramesNode.Omission(item.count))
                    }
                    is StackDigestItem.Frame -> {
                        if (StackDigestFrames.isKnownInternal(item.entry)) pending.add(item.entry)
                        else {
                            flush()
                            nodes.add(StackFramesNode.Visible(item.entry))
                        }
                    }
                }
            }
            flush()
            return StackFramesProjection(digest, nodes, nodes.none { it is StackFramesNode.Visible })
        }
    }
}

internal object StackDigestFrames {

    /** 帧间连接符（agent 侧 StackDigest 逐字契约）。 */
    internal const val FRAME_SEPARATOR = "<-"

    /** agent 侧截断标记（帧数触顶追加在完整末帧之后；长度触顶切在半帧中段，二者均以 `…` 收尾）。 */
    internal const val TRUNCATION_MARK = "…"

    /** agent 自身帧的包前缀（弹层里淡灰降权，模拟调试器「库帧」弱化样式）。 */
    internal const val AGENT_PACKAGE_PREFIX = "cn.song.mybatisplus.sqlvis."

    /**
     * 把快照串拆回帧列表（栈顶在左，展示序与串序一致）。解析失败容错到底：
     * 无 `#` 的残段跳过、整串不可解析返回空表（调用方回退纯文本形态），永不抛异常。
     *
     * 截断串（结尾 `…`）的末段一律丢弃：触顶末段要么是带标记的完整帧、要么是被 2000 字符
     * 硬截切出的半帧（后者解析后方法名是残缺前缀，展示会造假），丢 1 帧换来「不展示残帧」。
     */
    internal fun parse(digest: String?): List<StackFrameEntry> =
        parseItems(digest).filterIsInstance<StackDigestItem.Frame>().map { it.entry }

    internal fun parseItems(digest: String?): List<StackDigestItem> {
        if (digest.isNullOrBlank()) return emptyList()
        val trimmedDigest = digest.trim()
        if (trimmedDigest.isEmpty()) return emptyList()
        val truncated = trimmedDigest.endsWith(TRUNCATION_MARK)
        val s = if (truncated) trimmedDigest.removeSuffix(TRUNCATION_MARK) else trimmedDigest
        if (s.isEmpty()) return emptyList()
        val segments = s.split(FRAME_SEPARATOR)
        // 截断串末段一律丢弃：触顶末段要么是带标记的完整帧（丢 1/16 帧无伤）、要么是被 2000 字符
        // 硬截切出的半帧（解析后方法名是残缺前缀，展示会造假）——两者按「丢弃」统一处理。
        val usable = if (truncated && segments.isNotEmpty()) segments.dropLast(1) else segments
        val items = ArrayList<StackDigestItem>(usable.size + 1)
        for (seg in usable) {
            val seg1 = seg.trim()
            if (seg1.isEmpty()) continue
            val omitted = Regex("^<…中间省略 ([0-9]+) 帧…>$").matchEntire(seg1)
            if (omitted != null) {
                items.add(StackDigestItem.Omitted(omitted.groupValues[1].toIntOrNull()))
            } else {
                parseFrame(seg1)?.let { items.add(StackDigestItem.Frame(it)) }
            }
        }
        // 旧版末尾省略号不区分完整帧与硬截半帧；末段仍不展示为帧，但让缺口可见。
        if (truncated) items.add(StackDigestItem.Omitted(null))
        return items
    }

    /** 只折叠明确来自平台/依赖的包；项目可能使用 framework 命名空间，未知包一律显示。 */
    internal fun isKnownInternal(frame: StackFrameEntry): Boolean {
        val name = frame.fqcn
        val knownClasses = listOf(
            "org.apache.catalina.core.ApplicationFilterChain",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.FrameworkServlet",
            "org.springframework.web.filter.OncePerRequestFilter",
            "org.springframework.web.filter.FormContentFilter",
            "org.springframework.web.filter.CharacterEncodingFilter",
            "org.springframework.web.filter.RequestContextFilter",
            "org.springframework.web.filter.DelegatingFilterProxy",
            "org.springframework.security.web.FilterChainProxy",
        )
        return isAgentFrame(frame) || knownClasses.any { name == it || name.startsWith("$it\$") } || listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "com.zaxxer.hikari.pool.", "org.apache.ibatis.executor.",
            "org.apache.ibatis.session.", "org.apache.ibatis.plugin.",
            "org.apache.coyote.", "org.apache.tomcat.", "cn.dev33.satoken.",
            // T-StackBizFrame S3：JDBC 驱动族与 MyBatis 日志/绑定/反射/类型族。它们在每条 SQL 的
            // 栈顶恒在场，旧名单不收时弹层里「可见行」反而是驱动帧——项目帧缺席时观感上更像
            // 「看不到项目类」。这些包是纯依赖，误折叠风险低。
            "org.apache.ibatis.logging.", "org.apache.ibatis.binding.",
            "org.apache.ibatis.reflection.", "org.apache.ibatis.type.",
            "org.apache.ibatis.datasource.", "org.apache.ibatis.transaction.",
            "com.mysql.", "org.mariadb.", "org.postgresql.", "org.h2.", "org.hsqldb.",
            "org.sqlite.", "com.microsoft.sqlserver.", "oracle.jdbc.", "dm.jdbc.",
            "com.gbasedbt.", "com.kingbase8.", "com.alibaba.druid.", "com.mchange.",
            "com.huawei.", "org.opengauss.",
            "org.apache.commons.dbcp2.", "com.ibm.db2.", "com.teradata.", "org.hibernate.",
            "com.baomidou.", "org.springframework.transaction.", "org.springframework.jdbc.",
        ).any { name.startsWith(it) }
    }

    /** agent 自身帧判定（采集埋点链，弹层降权用）。 */
    internal fun isAgentFrame(frame: StackFrameEntry): Boolean =
        frame.fqcn.startsWith(AGENT_PACKAGE_PREFIX)

    /** 单段解析（供内联摘要取景复用；解析失败返回 null，永不抛）。 */
    internal fun parseSegment(segment: String): StackFrameEntry? = parseFrame(segment.trim())

    /**
     * T-StackBizFrame S3：meta 行内联摘要的取景（只取景，不截断——截断仍由调用方按
     * `STACK_DIGEST_MAX_CHARS` 处理，宽度预算与文案契约不变）。
     *
     * 原实现固定取摘要串前若干字符，而摘要头恒为 agent/驱动/框架帧——长栈下那一行永远不含项目帧
     * （用户报障形态：位置列有项目类，可见行的栈摘要里全是驱动帧）。现规则：头部全为已知内部帧时
     * 跳到首个非内部帧；首个非内部帧本来就在开头（或整条栈都是内部帧）时原样返回，既有契约不变。
     */
    internal fun projectExcerpt(digest: String): String {
        if (digest.isBlank()) return digest
        val segments = digest.split(FRAME_SEPARATOR)
        for (i in segments.indices) {
            val entry = parseSegment(segments[i]) ?: continue
            if (!isKnownInternal(entry)) {
                return if (i == 0) digest else segments.drop(i).joinToString(FRAME_SEPARATOR)
            }
        }
        return digest
    }

    /**
     * 单帧解析：`pkg.Class#method:line`。`#` 定位类名/方法名边界（首个 `#`——方法名不含 `#`）；
     * 行号取方法名内**最后一个** `:` 之后全数字段（lambda 名 `lambda$run$0` 等含 `:` 的情况不存在，
     * 但代理/内嵌类名 `$` 段已在 `#` 前被隔离）。无行号段或非数字 → [StackFrameEntry.line] = 0。
     */
    private fun parseFrame(segment: String): StackFrameEntry? {
        val hash = segment.indexOf('#')
        if (hash <= 0 || hash == segment.length - 1) return null
        val fqcn = segment.substring(0, hash)
        if (fqcn.isBlank()) return null
        val rest = segment.substring(hash + 1)
        val colon = rest.lastIndexOf(':')
        if (colon > 0) {
            val linePart = rest.substring(colon + 1)
            if (linePart.isNotEmpty() && linePart.all { it.isDigit() }) {
                linePart.toIntOrNull()?.let { return StackFrameEntry(fqcn, rest.substring(0, colon), it) }
            }
        }
        return StackFrameEntry(fqcn, rest, 0)
    }
}
