package cn.song.mybatisplus.visualizer.execute

/**
 * 执行错误分类学（T-MagpieGems W4，机制取自 magpie gateway fallback 的错误分类学）：
 * **先归因，再处置**——错在连接、错在对象、错在权限、错在语法，用户该做的事完全不同，
 * 全部塞进同一条红色错误文本里等于把归因成本转嫁给用户。
 *
 * 分类只产出「类别 + 出路提示文案键」，不改变错误本身的呈现与跳转；
 * 消费点在 [ExecutionErrorFormatter.format]（UI 三入口共用），MCP 链拿的是结构化
 * [ExecutionErrorInfo]，不受提示文案污染。分类器是纯函数（消息/码/SQLState 三路，与
 * [ConnectionErrorClassifier] 同构），headless 可直接单测。
 *
 * 判定顺序即优先级（前者吃掉后者）：CONNECTION → PERMISSION → OBJECT_MISSING → SYNTAX → OTHER。
 * PERMISSION 必须在 OBJECT_MISSING 之前——「Access denied ... to user」与「... doesn't exist」
 * 可能同串出现，权限问题不该被对象缺失的文案盖掉。
 */
enum class ExecutionErrorCategory(val hintKey: String?) {
    /** 连接类：出口由既有链承接（方言门禁「切换数据源」/DDL 已生效性探测），不加重复提示。 */
    CONNECTION(null),

    /** 权限不足：换数据源/重试都无用，出路是检查账号授权。 */
    PERMISSION("error.category.permission"),

    /** 对象缺失（库/表/列不存在）：切数据源大概率无用（除非绑错了库），出路是核对库表与数据源绑定。 */
    OBJECT_MISSING("error.category.objectMissing"),

    /** 语法/词法错误：定位跳转已就位，提示只做「不是环境问题」的定性。 */
    SYNTAX("error.category.syntax"),

    /** 其余（数据冲突、约束、超时等）：维持原样呈现，不硬给出路。 */
    OTHER(null),
}

/**
 * 执行错误分类器（纯函数）。判定三路与 [ConnectionErrorClassifier] 同构：
 * SQLState（含 42 子类细分）/ vendor errorCode 表 / 消息文本签名兜底。
 */
object ExecutionErrorClassifier {

    /** 权限族 vendor 码：MySQL 1044/1045/1142/1143/1227；Oracle 1031/1045（两者同码同义，殊途同归）。 */
    private val PERMISSION_CODES = setOf(1044, 1045, 1142, 1143, 1227, 1031)

    /** 权限族消息签名（小写 contains）。 */
    private val PERMISSION_SIGNATURES = listOf(
        "access denied",
        "permission denied",
        "not authorized",
        "insufficient privilege",
        "privileges for",
        "denied to user",
    )

    /** 对象缺失族 vendor 码：MySQL 1049/1051/1054/1146；Oracle 904/942。 */
    private val OBJECT_MISSING_CODES = setOf(1049, 1051, 1054, 1146, 904, 942)

    /** 对象缺失族 SQLState：MySQL 42S02（表）/42S22（列）；PG 42P01/42703。 */
    private val OBJECT_MISSING_STATES = setOf("42S02", "42S22", "42P01", "42703")

    /** 对象缺失族消息签名。 */
    private val OBJECT_MISSING_SIGNATURES = listOf(
        "doesn't exist",
        "does not exist",
        "unknown table",
        "unknown column",
        "unknown database",
        "no such table",
        "no such column",
        "invalid identifier",
    )

    /** 语法族 vendor 码：MySQL 1064；Oracle 911/936/933/1756；PG 42601 走 SQLState。 */
    private val SYNTAX_CODES = setOf(1064, 911, 933, 936, 1756)

    /** 语法族消息签名。 */
    private val SYNTAX_SIGNATURES = listOf(
        "syntax error",
        "parse error",
        "sql command not properly ended",
        "missing expression",
        "invalid character",
        "not properly terminated",
        "you have an error in your sql syntax",
    )

    fun classify(info: ExecutionErrorInfo): ExecutionErrorCategory {
        // 1. 连接类优先（内部已带排除表：deadlock/access denied/password 不误判）
        if (ConnectionErrorClassifier.isConnectionError(info)) return ExecutionErrorCategory.CONNECTION
        val lower = info.message.lowercase()
        val code = info.errorCode
        // 2. SQLState 精确形态（42 子类细分在码表之前——42S02/42S22 是对象缺失，42000/42601 才是语法）
        info.sqlState?.uppercase()?.let { state ->
            if (state.startsWith("28")) return ExecutionErrorCategory.PERMISSION
            if (state in OBJECT_MISSING_STATES) return ExecutionErrorCategory.OBJECT_MISSING
        }
        // 3. vendor 码表
        if (code != null && code in PERMISSION_CODES) return ExecutionErrorCategory.PERMISSION
        if (code != null && code in OBJECT_MISSING_CODES) return ExecutionErrorCategory.OBJECT_MISSING
        if (code != null && code in SYNTAX_CODES) return ExecutionErrorCategory.SYNTAX
        // 4. 消息签名兜底（顺序同优先级）
        if (PERMISSION_SIGNATURES.any { lower.contains(it) }) return ExecutionErrorCategory.PERMISSION
        if (OBJECT_MISSING_SIGNATURES.any { lower.contains(it) }) return ExecutionErrorCategory.OBJECT_MISSING
        if (SYNTAX_SIGNATURES.any { lower.contains(it) }) return ExecutionErrorCategory.SYNTAX
        // 5. SQLState 42 前缀兜底（标准「syntax or access rule violation」——码表没接住时的保守归类）
        info.sqlState?.uppercase()?.let { state ->
            if (state.startsWith("42")) return ExecutionErrorCategory.SYNTAX
        }
        return ExecutionErrorCategory.OTHER
    }
}
