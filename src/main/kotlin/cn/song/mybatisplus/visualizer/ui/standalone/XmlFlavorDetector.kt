package cn.song.mybatisplus.visualizer.ui.standalone

/**
 * XML 片段风味判定（T-StandaloneIbatisRecognize D-1）：判断一段 XML 语句片段属于
 * **MyBatis 3** 还是 **iBatis 2**。
 *
 * ## 为什么需要它
 *
 * `<select>` / `<insert>` / `<update>` / `<delete>` 四个标签名在两代框架里**同名**，
 * 独立预览的检测门只看「标签名 + id 属性」时无法区分；把 iBatis 2 片段送进 MyBatis 链会
 * 得到 `Unknown element <dynamic> in SQL statement.`（`xml/XmlScriptBuilder.kt:42`）这种
 * **假错误**——用户 2026-11-18 报障的根因。
 *
 * ## 为什么不能复用 [cn.song.mybatisplus.visualizer.xml.ibatis.IbatisXmlRecognizer]
 *
 * 那个是**文件级**三选二（根元素 `sqlMap` / DOCTYPE / 工程 classpath 有 `SqlMapClient`）。
 * 粘贴片段三条全取不到（根是 `select`、无 DOCTYPE、无工程上下文），必然判否。本判定器是
 * **片段级**，只读文本。两处的标签/DTD 名单保持同源（新增 iBatis 标签时两处一起加）。
 *
 * ## 判据（纯文本、零平台依赖）
 *
 * 加权计分，iBatis 分数严格高于 MyBatis 才判 iBatis；**同分判 MyBatis**（保住既有行为，
 * iBatis 需要证据才抢）。四类信号：
 *
 * | 类别 | iBatis 2 | MyBatis 3 |
 * |---|---|---|
 * | 根元素 | `sqlMap` / `sqlMapConfig` | `mapper` / `configuration` |
 * | DOCTYPE | `sql-map-2.dtd` / `SQL Map 2.0` | `mybatis-3-mapper.dtd` |
 * | 专有标签 | `dynamic` / `isXxx` 十四族 / `iterate` | `if` / `foreach` / `choose` / `where` / `set` / `trim` / `bind` |
 * | 专有属性 | `parameterClass` / `resultClass` / `parameterMap` / `listClass` / `compareValue` | `parameterType` / `statementType` / `resultOrdered` |
 * | 占位语法 | `#name#` / `$name$` | `#{name}` / `${name}` |
 *
 * **占位语法必须 string-aware**：MyBatis 里写 `WHERE a = '#x#'`（字符串字面量内的 iBatis 风格
 * 文本）不得给出 iBatis 信号——否则会因一处字面量把整段判错。注释内的信号同样不计。
 *
 * ## 判错也不致命
 *
 * [cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver] 在首选链失败后
 * 会跑另一条链择优，所以本判定器只需「大概率对」，不必追求绝对。
 */
object XmlFlavorDetector {

    /** 片段风味。 */
    enum class Flavor { MYBATIS, IBATIS }

    /** 判定入口：返回首选解析链的风味。 */
    fun detect(text: String): Flavor {
        if (text.isBlank()) return Flavor.MYBATIS
        val stripped = stripXmlComments(text)
        var ibatis = 0
        var mybatis = 0

        if (ROOT_IBATIS.containsMatchIn(stripped)) ibatis += ROOT_WEIGHT
        if (ROOT_MYBATIS.containsMatchIn(stripped)) mybatis += ROOT_WEIGHT
        if (stripped.contains("sql-map-2.dtd", ignoreCase = true) ||
            stripped.contains("SQL Map 2.0", ignoreCase = true)
        ) {
            ibatis += DOCTYPE_WEIGHT
        }
        if (stripped.contains("mybatis-3-mapper.dtd", ignoreCase = true)) mybatis += DOCTYPE_WEIGHT

        if (IBATIS_TAG.containsMatchIn(stripped)) ibatis += TAG_WEIGHT
        if (MYBATIS_TAG.containsMatchIn(stripped)) mybatis += TAG_WEIGHT
        if (IBATIS_ATTR.containsMatchIn(stripped)) ibatis += ATTR_WEIGHT
        if (MYBATIS_ATTR.containsMatchIn(stripped)) mybatis += ATTR_WEIGHT

        when (val ph = placeholderFlavor(text)) {
            Flavor.IBATIS -> ibatis += PLACEHOLDER_WEIGHT
            Flavor.MYBATIS -> mybatis += PLACEHOLDER_WEIGHT
            null -> Unit
        }
        return if (ibatis > mybatis) Flavor.IBATIS else Flavor.MYBATIS
    }

    // ---------- 占位语法（string-aware） ----------

    /**
     * 占位语法风味：按字符串外出现次数比较两种风格。都出现且数量相等时返回 null
     * （不给信号，交其它判据决定）；都不出现同样 null。
     */
    private fun placeholderFlavor(text: String): Flavor? {
        var ibatis = 0
        var mybatis = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> i = skipString(text, i)
                c == '-' && i + 1 < text.length && text[i + 1] == '-' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i++
                }
                c == '#' -> {
                    if (i + 1 < text.length && text[i + 1] == '{') {
                        mybatis++
                        i += 2
                        continue
                    }
                    val end = bracedHashEnd(text, i)
                    if (end > 0) {
                        ibatis++
                        i = end
                        continue
                    }
                }
                c == '$' -> {
                    if (i + 1 < text.length && text[i + 1] == '{') {
                        mybatis++
                        i += 2
                        continue
                    }
                    val end = bracedDollarEnd(text, i)
                    if (end > 0) {
                        ibatis++
                        i = end
                        continue
                    }
                }
            }
            i++
        }
        return when {
            ibatis > mybatis -> Flavor.IBATIS
            mybatis > ibatis -> Flavor.MYBATIS
            else -> null
        }
    }

    /** `#name#` 的结束位（`#` 之后是标识符且以 `#` 收尾）；不是该形态返回 -1。 */
    private fun bracedHashEnd(text: String, start: Int): Int {
        var i = start + 1
        if (i >= text.length || !isNameStart(text[i])) return -1
        while (i < text.length && isNamePart(text[i])) i++
        return if (i < text.length && text[i] == '#') i + 1 else -1
    }

    /** `$name$` 的结束位（同形规则）；不是该形态返回 -1。 */
    private fun bracedDollarEnd(text: String, start: Int): Int {
        var i = start + 1
        if (i >= text.length || !isNameStart(text[i])) return -1
        while (i < text.length && isNamePart(text[i])) i++
        return if (i < text.length && text[i] == '$') i + 1 else -1
    }

    private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_'
    private fun isNamePart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.' || c == ':'

    /** 跳过单引号字符串（`''` 转义），返回字符串后的位点。 */
    private fun skipString(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            if (text[i] == '\'') {
                if (i + 1 < text.length && text[i + 1] == '\'') {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return text.length
    }

    /** 剥 XML 注释——注释掉的标签/属性不得当信号。 */
    private fun stripXmlComments(text: String): String {
        if (!text.contains("<!--")) return text
        return text.replace(XML_COMMENT, " ")
    }

    // ---------- 权重与名单 ----------

    private const val ROOT_WEIGHT = 3
    private const val DOCTYPE_WEIGHT = 3
    private const val TAG_WEIGHT = 2
    private const val ATTR_WEIGHT = 2
    private const val PLACEHOLDER_WEIGHT = 2

    private val XML_COMMENT = Regex("<!--[\\s\\S]*?-->")

    private val ROOT_IBATIS = Regex("<\\s*(sqlMap|sqlMapConfig)\\b", RegexOption.IGNORE_CASE)
    private val ROOT_MYBATIS = Regex("<\\s*(mapper|configuration)\\b", RegexOption.IGNORE_CASE)

    /** iBatis 2 专有条件/结构标签（与 `xml/ibatis/IbatisModel.kt` 的 IbatisNodeKind 同源）。 */
    private val IBATIS_TAG = Regex(
        "<\\s*(dynamic|isNull|isNotNull|isEmpty|isNotEmpty|isEqual|isNotEqual|isGreaterThan|" +
            "isGreaterEqual|isLessThan|isLessEqual|isPropertyAvailable|isNotPropertyAvailable|" +
            "isParameterPresent|iterate)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** MyBatis 3 专有动态标签。 */
    private val MYBATIS_TAG = Regex(
        "<\\s*(if|foreach|choose|when|otherwise|where|set|trim|bind)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** iBatis 2 专有属性（`resultMap` 两代同名，不计入）。 */
    private val IBATIS_ATTR = Regex(
        "\\b(parameterClass|resultClass|parameterMap|listClass|compareValue)\\s*=",
        RegexOption.IGNORE_CASE,
    )

    /** MyBatis 3 专有属性。 */
    private val MYBATIS_ATTR = Regex(
        "\\b(parameterType|statementType|resultOrdered)\\s*=",
        RegexOption.IGNORE_CASE,
    )
}
