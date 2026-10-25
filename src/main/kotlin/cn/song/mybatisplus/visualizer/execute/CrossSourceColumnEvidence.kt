package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator

/**
 * T-ExplainConsoleFirst W7（2026-10-24）：`Unknown column` 的**跨源升格**判据。
 *
 * ## 为什么需要它
 *
 * 用户实测 `[42S22][1054] Unknown column 'first_name' in 'field list'`，上下文行三处读数全是
 * `mp_demo`（`[当前库=mp_demo，数据源=mp_demo@localhost，数据源默认库=mp_demo]`）——说明**源没选错**，
 * 错的是「SQL 与库」这一对：`first_name` 属 `jpa_demo` 库的同名 `user` 表，而 `mp_demo` 的 `user`
 * 表没有这一列。
 *
 * 既有 [classifyExplainFailure] 把 `Unknown column` **明确排除**在 WRONG_TARGET 之外
 * （`ExplainPanelChannel.kt:209` 注释：「列级问题，同一张表在，换源解决不了」）。这个排除对
 * 「列名拼错 / 前缀写错」是对的，但对「同名表、不同列集」就漏判了——那是**源选错**，换源正是解。
 *
 * ## 判据（三条同时成立才升格，缺一条一律不升格）
 *
 *  1. 错误文本是 `Unknown column '<col>'`（能解析出具体列名）；
 *  2. 该列**不在**当前源同名表的列集里（当前源确证没有——缓存未热则无从证明，不升格）；
 *  3. 存在**另一条在籍源**，其同名表**带这一列**（换个源就真能解决）。
 *
 * 第 3 条是关键：它把「拼错列名」（任何源都没有这列 → 不升格，照旧只报错）与「源选错」
 * （另一源确实有 → 升格，给就地换源引导）分开。**宁可漏判不可误判**——误判会把拼错列名的
 * 用户引导去换源，比不给引导更迷惑。
 *
 * ## 线程纪律
 *
 * 纯函数，零 IDE 依赖、零连接、零内省：调用方把「各源 → 表 → 列集」的查询器喂进来
 * （生产取 `DatabaseSchemaResolver.columnNameSources` 的只读缓存）。EDT 可直调。
 */
internal object CrossSourceColumnEvidence {

    /** MySQL / 通用形态：`Unknown column 'x.y' in 'field list'` / `in 'where clause'`。 */
    private val UNKNOWN_COLUMN = Regex(
        """unknown\s+column\s+'([^']+)'""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 从错误文本里取「报错列名」（已剥别名前缀，取末段）。
     *
     * `Unknown column 'u.first_name'` → `first_name`；`Unknown column 't.area_id'` → `area_id`。
     * 取不到（不是该形态 / 引号缺失）返回 null。
     */
    fun unknownColumnOf(errorText: String): String? {
        val raw = UNKNOWN_COLUMN.find(errorText)?.groupValues?.get(1)?.trim() ?: return null
        if (raw.isEmpty()) return null
        return raw.substringAfterLast('.').takeIf { it.isNotBlank() }
    }

    /**
     * 升格判定。
     *
     * @param errorText 驱动报文（可含插件拼的 `[当前库=…]` 上下文行，本函数只看列名形态）
     * @param sql 被执行/被 EXPLAIN 的 SQL（用于取表名；取不到表名一律不升格）
     * @param currentSourceColumns 当前源的查询器：表名 → 该源该表的列集；未持有/未热返回 null
     * @param otherSourcesColumns 其余在籍源的查询器清单（逐个问「你有没有这张表且带这一列」）
     * @return 升格证据（含列名与表名）或 null（不升格）
     */
    fun assess(
        errorText: String,
        sql: String,
        currentSourceColumns: (String) -> Set<String>?,
        otherSourcesColumns: List<(String) -> Set<String>?>,
    ): Evidence? {
        val column = unknownColumnOf(errorText) ?: return null
        val tables = runCatching { SchemaValidator.tableNames(sql) }.getOrNull().orEmpty()
            .filter { it.isNotBlank() }
        if (tables.isEmpty()) return null
        // 当前源必须**确证没有**这一列：缓存未热（null）说明无从证明，诚实不升格。
        var provenAbsent = false
        for (table in tables) {
            val cols = currentSourceColumns(table) ?: continue
            if (cols.isEmpty()) continue
            if (cols.any { it.equals(column, ignoreCase = true) }) {
                // 当前源其实有这一列——报错另有原因（大小写敏感/权限/别名遮蔽），不归因换源。
                return null
            }
            provenAbsent = true
        }
        if (!provenAbsent) return null
        // 存在另一条源持有「同名表 + 这一列」→ 换源真能解决。
        val holder = tables.firstOrNull { table ->
            otherSourcesColumns.any { src ->
                src(table)?.any { it.equals(column, ignoreCase = true) } == true
            }
        } ?: return null
        return Evidence(column = column, table = holder)
    }

    /** 升格证据：报错列名 + 承载它的表名（供提示文案指名道姓）。 */
    data class Evidence(val column: String, val table: String)
}
