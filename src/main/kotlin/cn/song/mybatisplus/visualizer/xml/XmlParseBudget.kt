package cn.song.mybatisplus.visualizer.xml

/**
 * mapper 解析预算（F-06③）：超大 XML 的**前置闸**。
 *
 * 为什么需要：`XmlMapperParser.parse` 是「文件文本 → DOM → 语句树」的全量路径，
 * 一份几 MB 的 mapper（生成代码、误把整库导出粘进 mapper）会让 DOM 建树与 include 展开
 * 按文件大小线性变慢，在 UI 触发路径上表现为长时间无响应。这里在解析前先按两条硬线判定，
 * 超限直接返回**降级模型**（错误说明 + 语句 id 清单），绝不抛异常、绝不建树。
 *
 * 两条线都是常量（不接设置 UI——阈值属于安全护栏，用户改小只会更容易降级、改大会失去保护）：
 *  - 文本 2MB：超过后 DOM 建树与后续脚本解析的内存/耗时都不再是「一次预览」的量级；
 *  - 顶层语句节点 5000 个：文本不大但语句极多的 mapper（代码生成产物常见）同样会让
 *    语句树构建与面板渲染变慢。
 *
 * 节点数是**文本扫描估算**（数 `<select|insert|update|delete|sql` 开标签），不是 DOM 计数——
 * 目的就是在建树前判定。估算只会多算不会少算（CDATA 里恰好写着 `<select` 时），多算的
 * 后果是降级提示，比「漏判后卡住」安全。
 *
 * 纯函数 + 常量，零 IDE 依赖。
 */
object XmlParseBudget {

    /** 文本上限（字节）：2MB。 */
    const val MAX_TEXT_BYTES: Int = 2 * 1024 * 1024

    /** 顶层语句节点上限：5000。 */
    const val MAX_TOP_LEVEL_NODES: Int = 5000

    /** 超限原因。 */
    enum class Violation { TEXT_TOO_LARGE, TOO_MANY_NODES }

    /** 一次预算判定结果：原因 + 实测值 + 上限（供文案展示）。 */
    data class BudgetCheck(val violation: Violation, val actual: Int, val limit: Int)

    /**
     * 判定文本是否超限；未超限返回 null。
     *
     * 字节数按 UTF-8 计，分三段走：字符数已超上限时直接用字符数（1 个 UTF-16 单元至少 1 字节，
     * 已是下界，够判「超了」）；字符数 × 3 都不超上限时零拷贝放行；只有落在灰色区间才真正编码一次。
     */
    fun check(text: String): BudgetCheck? {
        if (text.length > MAX_TEXT_BYTES) {
            return BudgetCheck(Violation.TEXT_TOO_LARGE, text.length, MAX_TEXT_BYTES)
        }
        if (text.length * 3 > MAX_TEXT_BYTES) {
            val bytes = utf8Size(text)
            if (bytes > MAX_TEXT_BYTES) return BudgetCheck(Violation.TEXT_TOO_LARGE, bytes, MAX_TEXT_BYTES)
        }
        val nodes = countStatementTags(text)
        if (nodes > MAX_TOP_LEVEL_NODES) return BudgetCheck(Violation.TOO_MANY_NODES, nodes, MAX_TOP_LEVEL_NODES)
        return null
    }

    /** UTF-8 字节数（精确值；只在灰色区间调用，正常大小的文本不会走到编码这一步）。 */
    internal fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** 估算语句节点数（开标签计数；见类注释的「只多不少」口径）。 */
    internal fun countStatementTags(text: String): Int =
        STATEMENT_TAG_REGEX.findAll(text).count()

    private val STATEMENT_TAG_REGEX = Regex("<(?:select|insert|update|delete|sql)[\\s/>]")

    /**
     * 降级模型里的语句 id 清单（文本扫描，不建树）。
     *
     * 扫描口径与节点计数一致：`<select|insert|update|delete` 开标签里的 `id` 属性
     * （单双引号都认，属性顺序不限）。无 id 的语句不占位（预览本就按 id 定位）。
     */
    internal fun scanStatementIds(text: String, limit: Int = MAX_ID_SCAN): List<String> {
        val ids = ArrayList<String>()
        for (tag in STATEMENT_TAG_WITH_ATTRS_REGEX.findAll(text)) {
            if (ids.size >= limit) break
            val id = ID_ATTR_REGEX.find(tag.groupValues[1])?.let { match ->
                match.groupValues[1].ifEmpty { match.groupValues[2] }
            }
            if (!id.isNullOrEmpty()) ids += id
        }
        return ids
    }

    /** 降级模型 id 清单的扫描上限（只用于提示，不需要穷举）。 */
    const val MAX_ID_SCAN: Int = 200

    private val STATEMENT_TAG_WITH_ATTRS_REGEX = Regex("<(?:select|insert|update|delete)\\b([^>]*)>")
    private val ID_ATTR_REGEX = Regex("\\bid\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
}
