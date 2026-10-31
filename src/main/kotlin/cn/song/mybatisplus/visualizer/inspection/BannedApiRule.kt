package cn.song.mybatisplus.visualizer.inspection

import java.util.UUID

/**
 * 禁用 API 规则（FR-0045 第 2 项 T-BannedApiCheck）：一条「团队规范里不该被调用」的方法声明。
 *
 * 持久化友好（照 [cn.song.mybatisplus.visualizer.log.SqlSuppressRule] 形态）：
 * 全部字段为基本类型 / String，XmlSerializer 可直接序列化。
 *
 * @param id          唯一标识（空白时由 [BannedApiRuleManager.addRule] 自动分配 UUID）
 * @param className   类匹配串——[classRegex] 为 false 时是**全限定名精确值**，为 true 时是**正则**
 *                    （整串匹配，建议写成 `com\.foo\..*`；点号须转义）
 * @param classRegex  类匹配模式显式标记（false=精确 FQN，true=正则）。显式标记而非「猜」——
 *                    正则里的 `.` 与 FQN 分隔点同形，自动猜测会让精确规则被当正则解释，
 *                    一个 `com.foo.Bar` 就能误伤 `comXfooYBar`
 * @param methodName  方法名正则（整串匹配）；**空串 = 任意方法**
 * @param description 为何禁用（必填——报告与编辑器提示都直接引用这段说明）
 * @param level       级别：[LEVEL_ERROR] / [LEVEL_WARNING]（控制编辑器下划线严重度）
 * @param createdAt   创建时间戳（毫秒；管理页列表展示）
 */
data class BannedApiRule(
    var id: String = "",
    var className: String = "",
    var classRegex: Boolean = false,
    var methodName: String = "",
    var description: String = "",
    var level: String = LEVEL_WARNING,
    var createdAt: Long = 0L,
) {
    /** 分配唯一 id（仅当当前为空白时）。 */
    fun ensureId() {
        if (id.isBlank()) id = UUID.randomUUID().toString()
    }

    /** 规则目标摘要：`类#方法`；方法正则为空时只显示类名（列表/报告同口径）。 */
    fun targetSummary(): String {
        val cls = className.trim()
        return if (methodName.isBlank()) cls else "$cls#${methodName.trim()}"
    }

    /** 是否为 error 级（其余一律按 warning 处理——未知级别不放大成 error）。 */
    fun isError(): Boolean = level == LEVEL_ERROR

    companion object {
        /** 编辑器标红级。 */
        const val LEVEL_ERROR = "error"

        /** 编辑器标黄级（默认）。 */
        const val LEVEL_WARNING = "warning"

        /** 合法级别清单（管理页下拉与校验共用）。 */
        val LEVELS: List<String> = listOf(LEVEL_ERROR, LEVEL_WARNING)
    }
}
