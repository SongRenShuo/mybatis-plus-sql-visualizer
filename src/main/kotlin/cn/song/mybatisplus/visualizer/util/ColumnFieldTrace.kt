package cn.song.mybatisplus.visualizer.util

/**
 * 结果列 → 实体字段溯源（F-06①）。
 *
 * 结果集列名来自数据库（snake_case，或带表别名前缀），实体字段是 Java 驼峰名，两者只有
 * 命名约定上的对应关系。本文件给出**判定内核**：命中就给字段名，判不出就返回 null——
 * 不猜。可判定的形态：
 *  - 精确命中（忽略大小写）：`user_name` ↔ 列名 `user_name`；
 *  - 归一命中（小写 + 去下划线/连字符）：`user_name` / `USER_NAME` ↔ 字段 `userName`；
 *  - 带表前缀：`u.user_name`，且前缀与已知表名（或表名末段）一致时才认。
 *
 * 判不出（返回 null）的形态：
 *  - 函数列/表达式（含括号或空白，如 `COUNT(*)`、`CONCAT(a,b)`）；
 *  - `AS` 改名的别名列（列头只剩别名，与实体字段没有可靠对应）；
 *  - 前缀不是已知表名（跨表/跨别名——归属未知，宁可不认）；
 *  - 多级限定（`db.t.col`）与通配 `*`；
 *  - 归一后有多个实体字段同时命中（歧义，不任选其一）。
 *
 * 纯函数，零 com.intellij 依赖，进 testFast 快桶。
 */
data class ColumnFieldTrace(
    /** 命中的结果列名（原样，未做归一）。 */
    val columnName: String,
    /** 实体字段名（声明形态，如 `userName`）。 */
    val fieldName: String,
    /** 实体类简单名；未知为 null。 */
    val entityName: String?,
)

/**
 * 解析一列的结果列名 → 实体字段。
 *
 * @param tableName   已知实体表名（可空；非空时才认 `表名.列名` 形态的前缀）
 * @param columnName  结果集列名（可空）
 * @param entityFields 实体字段名清单（驼峰声明形态）
 * @param entityName  实体类简单名（仅用于回填展示，不参与判定）
 * @return 命中即返回溯源；判不出返回 null
 */
fun resolveColumnFieldTrace(
    tableName: String?,
    columnName: String?,
    entityFields: Collection<String>,
    entityName: String? = null,
): ColumnFieldTrace? {
    val raw = columnName?.trim().orEmpty()
    if (raw.isEmpty() || entityFields.isEmpty()) return null
    // 函数列/表达式：括号或空白都意味着「这不是一个裸列名」
    if (raw.any { it == '(' || it == ')' || it.isWhitespace() }) return null
    if (raw == "*") return null
    val bare = stripQualifier(raw, tableName) ?: return null
    if (bare.isEmpty() || bare == "*") return null

    val exact = entityFields.filter { it.equals(bare, ignoreCase = true) }
    if (exact.size == 1) return ColumnFieldTrace(raw, exact.single(), entityName)
    if (exact.size > 1) return null

    val key = normalizeColumnKey(bare)
    if (key.isEmpty()) return null
    val normalized = entityFields.filter { normalizeColumnKey(it) == key }
    if (normalized.size != 1) return null
    return ColumnFieldTrace(raw, normalized.single(), entityName)
}

/**
 * 列头 tooltip 映射（列序号 → 文案）：命中溯源的列才出条目，判不出的列不进 map
 * （调用方据此判断「是否需要装渲染器」——无实体上下文时 map 为空，列头零变化）。
 *
 * @param columnNames  各列列名（与表格模型列序一致）
 * @param tableName    已知表名
 * @param entityFields 实体字段名清单
 * @param entityName   实体类简单名（null 时文案不带「（实体 X）」）
 */
fun columnFieldTraceTooltips(
    columnNames: List<String?>,
    tableName: String?,
    entityFields: Collection<String>,
    entityName: String?,
): Map<Int, String> {
    if (entityFields.isEmpty()) return emptyMap()
    val result = LinkedHashMap<Int, String>()
    columnNames.forEachIndexed { index, column ->
        val trace = resolveColumnFieldTrace(tableName, column, entityFields, entityName) ?: return@forEachIndexed
        result[index] = if (trace.entityName.isNullOrBlank()) {
            MpSqlVisualizerBundle.message("pages.columns.fieldTrace.noEntity", trace.fieldName)
        } else {
            MpSqlVisualizerBundle.message("pages.columns.fieldTrace", trace.fieldName, trace.entityName)
        }
    }
    return result
}

/**
 * 归一键：小写 + 去掉下划线/连字符。`user_name` / `USER_NAME` / `userName` 归一到同一键；
 * 不做首字母大写等再变形（那会把 `URL` 之类缩写搅乱）。
 */
internal fun normalizeColumnKey(name: String): String =
    name.lowercase().filter { it != '_' && it != '-' }

/** 去掉标识符引号：`"col"`、`` `col` ``、`[col]` 都是同名列。 */
private fun unquote(identifier: String): String {
    val trimmed = identifier.trim()
    if (trimmed.length < 2) return trimmed
    val first = trimmed.first()
    val last = trimmed.last()
    val quoted = (first == '"' && last == '"') ||
        (first == '`' && last == '`') ||
        (first == '[' && last == ']')
    return if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed
}

/**
 * 拆表前缀：`u.user_name` → `user_name`（前缀须与 [tableName] 或其后段一致）；无前缀原样返回；
 * 前缀不认识、或出现多级限定 → null（归属未知）。
 */
private fun stripQualifier(raw: String, tableName: String?): String? {
    val dot = raw.lastIndexOf('.')
    if (dot < 0) return unquote(raw)
    if (raw.indexOf('.') != dot) return null
    val qualifier = unquote(raw.substring(0, dot))
    val expected = tableName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { unquote(it.substringAfterLast('.')) }
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    if (!qualifier.equals(expected, ignoreCase = true)) return null
    return unquote(raw.substring(dot + 1))
}
