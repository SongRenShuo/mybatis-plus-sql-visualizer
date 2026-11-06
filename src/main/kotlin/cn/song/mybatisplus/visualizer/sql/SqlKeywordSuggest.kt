package cn.song.mybatisplus.visualizer.sql

/**
 * SQL 关键字拼写提示（F-06②，最小形态）。
 *
 * 只回答一个问题：这个「看起来像关键字但拼错了」的单词，最可能想写哪个关键字？
 * 判定口径刻意保守——宁可不出提示，也不要把表名/列名/别名认成拼错的关键字：
 *  - 只接受**纯字母** token（带下划线、点、数字、引号的都是标识符或表达式，一律不判）；
 *  - 已在白名单里的词不提示（拼对了没有可修的东西）；
 *  - 编辑距离 ≤ [MAX_EDIT_DISTANCE]；取最近的一档，该档**只有一个**候选才算唯一命中，
 *    有并列（`ih` 到 `in` 与 `is` 都是 1）就返回 null；
 *  - 结果统一大写（SQL 关键字惯例），与用户原文大小写无关。
 *
 * 纯函数，零 IDE 依赖，可独立单测。
 */

/** 参与拼写提示的关键字白名单（小写）。只收高频结构关键字，避免把方言函数名也卷进来。 */
val SUGGESTABLE_KEYWORDS: Set<String> = setOf(
    "select", "insert", "update", "delete", "from", "where", "join", "group", "order", "limit",
    "having", "union", "values", "into", "set", "on", "and", "or", "not", "null", "distinct",
    "left", "right", "inner", "outer", "cross", "as", "by", "asc", "desc", "offset", "exists",
    "case", "when", "then", "else", "end", "like", "in", "is", "between", "all", "any",
)

/** 允许的最大编辑距离（含）。超过这个距离的词形差异太大，提示只会变成噪音。 */
const val MAX_EDIT_DISTANCE: Int = 2

/**
 * 给出拼写修正建议。
 *
 * @param token    待判定的单词（大小写不敏感）
 * @param keywords 白名单（默认 [SUGGESTABLE_KEYWORDS]）
 * @return 建议的关键字（大写）；判不出/不需要/有歧义时返回 null
 */
fun suggestKeywordFix(token: String?, keywords: Set<String> = SUGGESTABLE_KEYWORDS): String? {
    val raw = token?.trim().orEmpty()
    if (raw.isEmpty() || raw.length > 32) return null
    // 纯字母才可能是关键字；`user_name`、`t.id`、`count(*)`、`123` 都不是
    if (raw.any { !it.isLetter() }) return null
    val lower = raw.lowercase()
    if (lower in keywords) return null

    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    var tied = false
    for (keyword in keywords) {
        val distance = boundedEditDistance(lower, keyword, MAX_EDIT_DISTANCE)
        if (distance > MAX_EDIT_DISTANCE) continue
        when {
            distance < bestDistance -> {
                best = keyword
                bestDistance = distance
                tied = false
            }
            distance == bestDistance -> tied = true
        }
    }
    if (best == null || tied) return null
    return best.uppercase()
}

/**
 * 带上界的编辑距离：距离一旦确定超过 [limit] 就提前返回 `limit + 1`（调用方只关心「是否 ≤ limit」）。
 *
 * 标准 Levenshtein 动态规划，逐行滚动；插入/删除/替换代价均为 1。
 */
internal fun boundedEditDistance(a: String, b: String, limit: Int): Int {
    if (a == b) return 0
    val lengthGap = kotlin.math.abs(a.length - b.length)
    if (lengthGap > limit) return limit + 1
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var rowMin = current[0]
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            if (current[j] < rowMin) rowMin = current[j]
        }
        // 安全下界：min(row i) ≤ d(a[0..i], b)，而 d(a[0..i], b) ≤ d(a, b) + (n - i)，
        // 故 d(a, b) ≥ min(row i) - (n - i)；该下界都超限才可提前收工。
        if (rowMin - (a.length - i) > limit) return limit + 1
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length].coerceAtMost(limit + 1)
}
