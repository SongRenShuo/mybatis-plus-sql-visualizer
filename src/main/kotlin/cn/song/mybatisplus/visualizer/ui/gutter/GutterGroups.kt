package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings

/**
 * R2（W2 体验包）：gutter 按来源分组显隐——组 → provider 映射单一事实源。
 *
 * 动机＝MyBatisX「图标太大太醒目」差评多轮；此前仅全局布尔 showGutterIcon，
 * 15 个 lineMarkerProvider 无分组。组口径（默认全开=现行为不变）：
 *  - MAPPER_XML：mapper XML 语句（select/insert/update/delete 文本 + pages/hbm 命名查询）
 *  - ANNOTATION：注解 SQL（@Select 族 + @NamedQuery）
 *  - JPA_HQL：JPA/HQL 族（JPA 仓储 / JPQL / Criteria / HQL / 自研 DAO HQL）
 *  - JDBC：JdbcTemplate 字符串 SQL
 *  - INLINE_FALLBACK：通用 XML 内嵌兜底（第四通道）
 *  - OTHER：MP Wrapper 链 / 方法名字符串 SQL 等其余
 *
 * [enabledFor] 为平台包装（读设置，测试环境兜底全开）；[groupOf]/纯逻辑面独立可测。
 */
object GutterGroups {

    /** 组枚举（设置页每组一个开关）。 */
    val GROUPS = listOf("mapperXml", "annotation", "jpaHql", "jdbc", "inlineFallback", "other")

    /** provider 类 simpleName → 组（未登记 = other）。 */
    private val PROVIDER_GROUP: Map<String, String> = mapOf(
        "XmlSqlGutterLineMarkerProvider" to "mapperXml",
        "PagesXmlGutterLineMarkerProvider" to "mapperXml",
        "HbmNamedQueryGutterLineMarkerProvider" to "mapperXml",
        "MapperXmlToJavaLineMarkerProvider" to "mapperXml",
        "MapperJavaToXmlLineMarkerProvider" to "mapperXml",
        "AnnotationSqlGutterLineMarkerProvider" to "annotation",
        "NamedQueryGutterLineMarkerProvider" to "annotation",
        "JpaGutterLineMarkerProvider" to "jpaHql",
        "JpqlGutterLineMarkerProvider" to "jpaHql",
        "CriteriaQueryGutterLineMarkerProvider" to "jpaHql",
        "HqlGutterLineMarkerProvider" to "jpaHql",
        "CustomDaoHqlGutterLineMarkerProvider" to "jpaHql",
        "JdbcTemplateSqlGutterLineMarkerProvider" to "jdbc",
        "XmlInlineSqlGutterLineMarkerProvider" to "inlineFallback",
        "SqlGutterLineMarkerProvider" to "other",
        "StringSqlMethodGutterLineMarkerProvider" to "other",
    )

    /** provider 类所属组（未登记 = other）。 */
    fun groupOf(providerSimpleName: String): String =
        PROVIDER_GROUP[providerSimpleName] ?: "other"

    /** 该 provider 的组开关是否开启（全局 showGutterIcon 总闸在前；测试环境兜底全开）。 */
    fun enabledFor(providerClass: Class<*>, showGutterIcon: Boolean): Boolean {
        if (!showGutterIcon) return false
        return runCatching {
            groupAllowed(providerClass, PluginSettings.getInstance().state)
        }.getOrDefault(true)
    }

    /**
     * F36（T-PerfSweep16）：调用方已持有 State 的重载——provider 在 getLineMarkerInfo 方法头
     * 单次取 state 后传入，消掉原 `(Class, Boolean)` 路径里包装层与本函数内部的重复
     * app 级服务查找（每到达元素 3 次 → 1 次）。
     *
     * 语义与 `(Class, Boolean)` 版逐值等价：state 非空时总闸（`showGutterIcon`）与组开关判定
     * 同现状；state 为 null（设置服务不可读）承接失败回退语义返回 true（全开）。
     * 组判定共用 [groupAllowed] 单一事实。
     */
    fun enabledFor(providerClass: Class<*>, state: PluginSettings.State?): Boolean {
        if (state == null) return true // 设置读取失败回退：同 runCatching { … }.getOrDefault(true)
        if (!state.showGutterIcon) return false
        return groupAllowed(providerClass, state)
    }

    /** 组判定私有实现（两个重载共用）：组解析 + 组开关读取的单一事实源。 */
    private fun groupAllowed(providerClass: Class<*>, state: PluginSettings.State): Boolean =
        groupEnabled(groupOf(providerClass.simpleName), state)

    /** 纯逻辑面（可测）：组开关读取（组 → State 字段映射单一事实）。 */
    fun groupEnabled(group: String, s: PluginSettings.State): Boolean = when (group) {
        "mapperXml" -> s.gutterGroupMapperXml
        "annotation" -> s.gutterGroupAnnotation
        "jpaHql" -> s.gutterGroupJpaHql
        "jdbc" -> s.gutterGroupJdbc
        "inlineFallback" -> s.gutterGroupInlineFallback
        else -> s.gutterGroupOther
    }
}
