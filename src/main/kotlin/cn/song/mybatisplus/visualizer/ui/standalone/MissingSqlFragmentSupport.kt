package cn.song.mybatisplus.visualizer.ui.standalone

/**
 * 缺失 `<include refid>` 片段的检测与补齐（T-IncludePlaceholder A 券）。
 *
 * ## 背景
 *
 * 用户只贴「语句」不贴「被引用的 `<sql id>` 片段」时，两代链都只能丢弃 include：
 * 渲染出来是 `SELECT` 后面直接接 `FROM` 的残句（2026-11-18 用户截图）。渲染侧已改为留一条
 * 「TODO 缺失片段」注释占位，本条补的是「让用户把它补上」的入口。
 *
 * ## 判定口径
 *
 * - 引用 = 文本里 `<include refid="X">` 的 X 集合；
 * - 定义 = 文本里 `<sql id="X">` 的 X 集合；
 * - 缺失 = 引用 − 定义。`refid` 可能带 namespace 前缀（`ns.id`），而定义常写成裸 `id`，
 *   故两边都按「末段名」再对一次，避免把「其实定义过」误报成缺失。
 *
 * 纯逻辑、零平台依赖（可直接进快桶单测）。
 */
internal object MissingSqlFragmentSupport {

    private val INCLUDE_REFID = Regex(
        "<include\\s+[^>]*refid\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE,
    )

    private val SQL_ID = Regex(
        "<sql\\s+[^>]*id\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE,
    )

    /** 引用但未定义的片段 id（排序稳定，便于 UI 文案与断言）。 */
    fun missingRefids(text: String): List<String> {
        if (text.isBlank() || !text.contains("include", ignoreCase = true)) return emptyList()
        val referenced = INCLUDE_REFID.findAll(text).map { it.groupValues[1] }.toSet()
        if (referenced.isEmpty()) return emptyList()
        val defined = SQL_ID.findAll(text).map { it.groupValues[1] }.toSet()
        val definedLeaf = defined.map { it.substringAfterLast('.') }.toSet()
        return referenced
            .filterNot { it in defined || it.substringAfterLast('.') in definedLeaf }
            .sorted()
    }

    /**
     * 把用户补齐的片段拼到待解析文本之前。
     *
     * 拼在**前面**的理由：两代解析器都按「根元素下的 `<sql>` 片段表」收集，片段先于语句声明
     * 不受顺序影响；而用户补的内容若已是完整的 `<sql …>…</sql>` 则原样拼入（不二次包壳）。
     */
    fun withFragments(text: String, fragments: Map<String, String>): String {
        if (fragments.isEmpty()) return text
        val block = fragments.entries.joinToString("\n") { (id, body) ->
            if (body.trimStart().startsWith("<sql", ignoreCase = true)) body else "<sql id=\"$id\">$body</sql>"
        }
        return "$block\n$text"
    }
}