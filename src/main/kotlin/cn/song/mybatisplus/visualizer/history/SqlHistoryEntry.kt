package cn.song.mybatisplus.visualizer.history

import java.util.UUID

/**
 * SQL 历史记录条目（F-7）：一次预览的不可变快照 + 用户标注（收藏/标签）。
 *
 * 持久化友好：全部字段为基本类型 / String / List，XmlSerializer 可直接序列化。
 * [tags] 用 MutableList 而非 Set（XmlSerializer 对 Set 支持不稳定），[SqlHistoryManager] 维护唯一性。
 *
 * @param id         唯一标识（空白时由 [SqlHistoryManager.add] 自动分配 UUID）
 * @param sql        完整 SQL（多行格式化）
 * @param compactSql 压缩 SQL（摘要展示用）
 * @param timestamp  预览时间戳（毫秒）
 * @param sourceFile 来源文件名（如 UserMapper.java）
 * @param sourceLine 来源文件行号（0 表示未知）
 * @param tableName  目标表名（可为空）
 * @param favorite   是否收藏（收藏条目不参与上限淘汰）
 * @param tags       用户自定义标签列表（唯一、有序）
 * @param executionTimeMs 执行耗时（毫秒）；null 表示无耗时信息
 */
data class SqlHistoryEntry(
    var id: String = "",
    var sql: String = "",
    var compactSql: String = "",
    var timestamp: Long = 0L,
    var sourceFile: String = "",
    var sourceLine: Int = 0,
    var tableName: String = "",
    var favorite: Boolean = false,
    var tags: MutableList<String> = mutableListOf(),
    var executionTimeMs: Long? = null,
    /** R15（W3 历史工作流包）：钉住——非收藏分区裁剪跳过钉住条目（防翻找丢失）；上限由 manager 把关。 */
    var pinned: Boolean = false,
    /**
     * R51（O-105 余半·主体）：参数快照（键值对，格式＝SqlPreviewPanel.buildHistorySnapshot——
     * 普通参数 source→值 + R22 特殊键 `__chk__`/`__fnull__`/`__fev__`）。
     *
     * 生命周期：recordHistory 早于面板参数区构建（预览产物先落历史、参数后填），故初始恒空；
     * 面板参数编辑防抖落库（flushHistoryToCache）时按缓存键回写**最新匹配条目**。recall 链
     * （recallFromHistory）在 ParamHistoryCache 未命中（LRU 淘汰/清缓存）时按同一缓存键回落
     * 本快照回填参数面板——「历史条目→面板参数态」桥。
     *
     * 持久化（V75 实测在案）：XmlSerializer 对 LinkedHashMap 往返逐值兼容
     * （V75ParamSnapshotSerializerProbeTest ①a）；旧历史文件无本字段 → 缺省空 Map 不炸（②a）。
     * 用 LinkedHashMap 而非 Set 系（同 [tags] 注释口径，Set 不稳）。
     */
    var paramSnapshot: LinkedHashMap<String, String> = LinkedHashMap(),
    /**
     * R90：APP 历史域的项目归属（F-08 的 projectId 维度）——basePath 的 SHA-256
     * （[ExplainPlanKey.projectIdentity]），不落本机路径、跨机稳定。旧条目缺省空，不猜测归属。
     */
    var projectIdentity: String = "",
    /**
     * F-08：项目显示名（[com.intellij.openapi.project.Project.getName] 的落库快照）——
     * 过滤/展示用的人类可读维度，与 [projectIdentity]（projectId）成对；
     * identity 相同的条目以最新一条的名字为准刷新（[SqlHistoryManager.projectNames]）。
     * 旧历史文件无本字段 → 缺省空串，读回不炸（XmlSerializer 缺字段走默认值）。
     */
    var projectName: String = "",
    var explainPlans: MutableList<ExplainPlanSnapshot> = mutableListOf(),
    /**
     * T-PresetMethodScope：预览语句的声明作用域（方法身份短哈希，见
     * [cn.song.mybatisplus.visualizer.psi.PreviewOriginScope]）。参数快照回落（R51）按同一口径
     * 匹配历史条目；旧条目缺省空 → 只参与旧键匹配，不参与带 scope 键的精确匹配。
     */
    var originScopeKey: String = "",
) {
    /** 分配唯一 id（仅当当前为空白时）。 */
    fun ensureId() {
        if (id.isBlank()) id = UUID.randomUUID().toString()
    }

    /** 摘要文本：取 compactSql 首行，截断至 [MAX_SUMMARY_LEN] 字符。 */
    fun summary(): String {
        val firstLine = compactSql.trim().split("\n").firstOrNull()?.trim() ?: ""
        return if (firstLine.length <= MAX_SUMMARY_LEN) firstLine
        else firstLine.substring(0, MAX_SUMMARY_LEN) + "..."
    }

    /** 来源描述文案（如 "UserMapper.java:10"）。 */
    fun sourceLabel(): String =
        if (sourceFile.isBlank()) "" else if (sourceLine > 0) "$sourceFile:$sourceLine" else sourceFile

    companion object {
        /** 摘要最大字符数（列表渲染用）。 */
        const val MAX_SUMMARY_LEN = 80
    }
}
