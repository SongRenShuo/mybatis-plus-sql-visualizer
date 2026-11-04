package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * UI-049（2026-11-04 用户真机）：把「空 Runner · 方言不匹配」从一句长红字升级为可操作的提示。
 *
 * 现场问题（master 包）：12 个数据源名一长串、用户不知道改哪一个；「属性页 → Dialect 下拉」是纯文字
 * 指引、没有可点入口；文案用「执行失败」，但其实是缺配置。
 *
 * 本文件只做**呈现层**的两件事，不碰 Runner 归因：
 *  1. 用 JDBC URL 子协议（[UrlSubprotocolDialects]，与 T-DialectAutoInfer 同源）挑出「看起来就是
 *     目标方言」的源，供文案点名；挑不出就诚实说挑不出。
 *  2. 把可见行（一句话）与悬停详文（折叠其余源）拆成两个 bundle 模板。
 *
 * 归因五态（无源 / 判不出方言 / 平台调用失败 / 已绑定仍不可用 / 方言不匹配）仍由调用方
 * `emptyRunnersReason` 判定；[DialectMismatchHint] 只在最后一态构造。
 */
/**
 * 一个在册数据源在提示层的轻量投影。
 *
 * 公开（非 internal）：`DataSourceSelector.prepareConsole` / `selectAndExecute` 与
 * `SqlExecutor` 的对应入口是 public API，参数类型不能暴露 internal 类型（Kotlin
 * `EXPOSED_PARAMETER_TYPE`）。本类只承载展示所需的名字/URL/句柄，无行为。
 */
data class RegisteredJdbcSource(
    /** 数据源显示名（与平台 Data Sources 列表一致）。 */
    val name: String,
    /** JDBC URL；reflection 取不到时 null（推断阶段据此跳过，不猜）。 */
    val url: String?,
    /** 平台 LocalDataSource 句柄，供「打开数据源属性」定位；测试/取不到时 null。 */
    val handle: Any?,
)

/** 空 Runner「方言不匹配」态的提示数据（公开原因同 [RegisteredJdbcSource]：公共回调签名）。 */
data class DialectMismatchHint(
    /** 文案里的渲染方言名（如 MySQL / PostgreSQL / DM（达梦））。 */
    val dialectDisplay: String,
    /** 项目在册数据源总数。 */
    val totalRegistered: Int,
    /** 由 URL 推断出来「像目标方言」的源（可能为空、可能多个）。 */
    val likely: List<RegisteredJdbcSource>,
) {
    val uniqueLikely: RegisteredJdbcSource? get() = likely.singleOrNull()

    /** 未点名的其余源数量（用于「另有 N 个源已折叠」）。 */
    val otherCount: Int get() = (totalRegistered - likely.size).coerceAtLeast(0)
}

/**
 * 平台 SQL language id / 产品别名 → 统一比较面。
 *
 * `renderedDialectName` 有渲染事实时是 [cn.song.mybatisplus.visualizer.sql.Dialect.name]
 * （`SQL Server`），回退路径却是平台 language id（`TSQL`）——两者必须能对上，否则 URL 推断
 * 会对 SQL Server 源全部漏点名。
 */
private val DIALECT_NAME_ALIASES: Map<String, String> = mapOf(
    "tsql" to "sql server",
    "mssql" to "sql server",
    "dameng" to "dm",
    "kingbasees" to "kingbase",
)

/**
 * 方言名归一化：剥掉中文/英文括号后缀、转小写，并把平台的 language id 别名对齐到
 * [UrlSubprotocolDialects] 的产品名（`TSQL`/`mssql` → `sql server`）。
 *
 * 目的只有一个：让 [UrlSubprotocolDialects] 的值（如 `DM（达梦）`、`SQL Server`）与运行时
 * 渲染方言名（可能只是 `DM`，也可能是平台 language id `TSQL`）能落在同一比较面。不做方言家族
 * 合并——MariaDB 不等于 MySQL，归因准确性优先。
 */
internal fun normalizeDialectNameForMatch(name: String): String {
    val stripped = name.substringBefore('（').substringBefore('(').trim().lowercase()
    return DIALECT_NAME_ALIASES[stripped] ?: stripped
}

/** 纯函数：从在册源里挑出 URL 子协议映射到 [dialectDisplay] 的源（保持原顺序）。 */
internal fun inferLikelyDialectSources(
    dialectDisplay: String,
    sources: List<RegisteredJdbcSource>,
): List<RegisteredJdbcSource> {
    val target = normalizeDialectNameForMatch(dialectDisplay)
    if (target.isEmpty()) return emptyList()
    return sources.filter { source ->
        val mapped = UrlSubprotocolDialects.dialectName(source.url) ?: return@filter false
        normalizeDialectNameForMatch(mapped) == target
    }
}

/**
 * 平台侧适配：在册 `LocalDataSource` 列表 → [DialectMismatchHint]。
 *
 * 取名/取 URL 全部走既有反射入口（[DataSourceSettings.getName] / [ExecDialectRouting.dataSourceUrl]），
 * Community 或平台异常时退化为「无名字/无 URL」，不抛。
 */
internal fun buildDialectMismatchHint(
    dialectDisplay: String,
    registered: List<Any>,
): DialectMismatchHint {
    val sources = registered.map { ds ->
        RegisteredJdbcSource(
            name = DataSourceSettings.getName(ds),
            url = ExecDialectRouting.dataSourceUrl(ds),
            handle = ds,
        )
    }
    return DialectMismatchHint(
        dialectDisplay = dialectDisplay,
        totalRegistered = sources.size,
        likely = inferLikelyDialectSources(dialectDisplay, sources),
    )
}

/**
 * 状态行可见文案（一句话，过长由 JLabel 省略、悬停看 [detailMessage]）。
 *
 * 标题写「下一步」而不是「执行失败」：这是缺配置，不是插件坏掉。
 */
internal fun DialectMismatchHint.statusMessage(): String {
    // 先取值再判空：uniqueLikely 是自定义 getter（`likely.singleOrNull()`），Kotlin 不对其做智能转换。
    val unique = uniqueLikely
    return when {
        unique != null -> MpSqlVisualizerBundle.message(
            "execute.datasource.dialectMismatch.status.unique",
            unique.name,
            dialectDisplay,
        )
        likely.size > 1 -> MpSqlVisualizerBundle.message(
            "execute.datasource.dialectMismatch.status.multiple",
            likely.size,
            dialectDisplay,
        )
        else -> MpSqlVisualizerBundle.message(
            "execute.datasource.dialectMismatch.status.unknown",
            dialectDisplay,
        )
    }
}

/**
 * 悬停详文：解释归因（缺配置）、点名推断源、折叠其余源，并说明属性页底部 Dialect 下拉的位置。
 */
internal fun DialectMismatchHint.detailMessage(): String {
    val unique = uniqueLikely
    return when {
        unique != null -> MpSqlVisualizerBundle.message(
            "execute.datasource.dialectMismatch.detail.unique",
            totalRegistered,
            dialectDisplay,
            unique.name,
            unique.url ?: "",
        ) + foldSuffix()
        likely.size > 1 -> MpSqlVisualizerBundle.message(
            "execute.datasource.dialectMismatch.detail.multiple",
            totalRegistered,
            dialectDisplay,
            likely.size,
            likelyNamesSummary(),
        ) + foldSuffix()
        else -> MpSqlVisualizerBundle.message(
            "execute.datasource.dialectMismatch.detail.unknown",
            totalRegistered,
            dialectDisplay,
        )
    }
}

/** 其余源折叠说明：0 个时不追加，避免「另有 0 个源」这类噪音。 */
private fun DialectMismatchHint.foldSuffix(): String =
    if (otherCount > 0) {
        "\n\n" + MpSqlVisualizerBundle.message(
            "execute.datasource.dialectMismatch.detail.fold",
            otherCount,
            dialectDisplay,
        )
    } else {
        ""
    }

/** 推断源名字摘要：最多点名 5 个，其余折叠为「等 N 个」。 */
private fun DialectMismatchHint.likelyNamesSummary(): String {
    val shown = likely.take(MAX_NAMED_SOURCES).joinToString(", ") { it.name }
    val hidden = likely.size - MAX_NAMED_SOURCES
    return if (hidden > 0) {
        shown + " " + MpSqlVisualizerBundle.message("execute.datasource.dialectMismatch.names.more", hidden)
    } else {
        shown
    }
}

/** 文案里最多逐个点名的推断源数量（其余折叠，避免又出现一长串）。 */
internal const val MAX_NAMED_SOURCES = 5