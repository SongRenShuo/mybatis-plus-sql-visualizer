package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues
import cn.song.mybatisplus.visualizer.sql.SlotWarning

/**
 * 可执行 SQL 格式化器（F-8 Phase C）。
 *
 * 复用 [ParameterReplacer.buildExecutableSql] 将 `?` 替换为带类型处理的字面值，
 * 额外提供 MR/PR 评论模板（Code Review 场景贴可执行 SQL + 来源）。
 *
 * T-RuntimeSlotFill 券 B：本类同时是「可执行 SQL」三条链路的**唯一入口**——运行期占位回填、
 * `?` 回填、`:name` 回填都在 [ParameterReplacer.buildExecutableSqlWithSlots] 里一次扫描完成，
 * 复制 / 分享 / 执行 / 导出都从这里取文本，避免出现「预览填了、复制出来还是注释」。
 */
object ExecutableSqlFormatter {

    /**
     * 可执行 SQL 产物（文本 + 回填诊断）。
     *
     * @param sql 最终文本
     * @param slotRender 运行期占位的回填账：哪些填进去了、哪些没填（[warnings] 是给用户看的原因）
     */
    data class Product(
        val sql: String,
        val slotRender: ParameterReplacer.RuntimeSlotRender,
    ) {
        val warnings: List<SlotWarning> get() = slotRender.warnings
        val appliedSlotIds: List<String> get() = slotRender.appliedSlotIds
    }

    /**
     * 生成单行可执行 SQL（替换 `?` 为字面值）。
     *
     * @param sql        含 `?` 的 SQL
     * @param sources    参数来源列表（与 `?` 按序对应）
     * @param paramValues 参数值映射（source → 值）
     * @param hints      回填提示（可空，走启发式）
     * @param slots      运行期占位表（空表 = 无占位，产物与改前逐字节一致）
     * @param slotValues 占位值（空表 = 占位原样保留）
     * @param namedHints `:name` 引号提示（空表 = 不做命名参数回填）
     * @param dialectName 当前方言名（布尔字面量判定：Oracle / 达梦无布尔字面量）
     * @return 可执行 SQL
     */
    fun format(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        hints: List<ParamHint> = emptyList(),
        slots: List<RuntimeSlot> = emptyList(),
        slotValues: RuntimeSlotValues = RuntimeSlotValues.EMPTY,
        namedHints: Map<String, ParamHint> = emptyMap(),
        dialectName: String? = null,
    ): String = formatWithDiagnostics(
        sql = sql,
        sources = sources,
        paramValues = paramValues,
        hints = hints,
        slots = slots,
        slotValues = slotValues,
        namedHints = namedHints,
        dialectName = dialectName,
    ).sql

    /**
     * 同 [format]，但把回填账（警告 / 已填占位）一并返回，供面板提示与测试断言。
     *
     * 占位为空且无命名参数时，内部直接走既有 [ParameterReplacer.buildExecutableSql]——
     * 既有链路的产物逐字节不变。
     */
    fun formatWithDiagnostics(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        hints: List<ParamHint> = emptyList(),
        slots: List<RuntimeSlot> = emptyList(),
        slotValues: RuntimeSlotValues = RuntimeSlotValues.EMPTY,
        namedHints: Map<String, ParamHint> = emptyMap(),
        dialectName: String? = null,
    ): Product {
        val render = ParameterReplacer.buildExecutableSqlWithSlots(
            sql = sql,
            slots = slots,
            slotValues = slotValues,
            sources = sources,
            paramValues = paramValues,
            hints = hints,
            namedHints = namedHints,
            dialectName = dialectName,
        )
        return Product(render.sql, render)
    }

    /**
     * 生成 MR/PR 评论格式（可执行 SQL + 来源前缀）。
     *
     * 模板：
     * ```
     * [SQL Review] UserService.java:42
     * ```sql
     * SELECT id, name FROM user WHERE name = 'zhangsan'
     * ```
     * ```
     *
     * @param sql        含 `?` 的 SQL
     * @param sources    参数来源列表
     * @param paramValues 参数值映射
     * @param source     来源位置（文件名:行号，可 null）
     * @param prefix     标题前缀（默认 `[SQL Review]`）
     * @param hints      回填提示
     * @param slots      运行期占位表
     * @param slotValues 占位值
     * @param dialectName 当前方言名
     * @return MR/PR 评论文本
     */
    fun formatAsMrComment(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        source: String? = null,
        prefix: String = "[SQL Review]",
        hints: List<ParamHint> = emptyList(),
        slots: List<RuntimeSlot> = emptyList(),
        slotValues: RuntimeSlotValues = RuntimeSlotValues.EMPTY,
        dialectName: String? = null,
    ): String {
        val executable = format(sql, sources, paramValues, hints, slots, slotValues, emptyMap(), dialectName)
        val sb = StringBuilder(256)
        sb.append(prefix)
        source?.let { sb.append(" ").append(it) }
        sb.append("\n```sql\n")
        sb.append(executable.trimEnd())
        sb.append("\n```")
        return sb.toString()
    }
}
