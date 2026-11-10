package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * 轻量 SQL 格式化器（M9 增强，T-M27.4 缩进模型重构）：把可编辑 SQL 规范化为标准可读格式。
 *
 * 基于 [SqlLexer] 分词，规则：
 *  - 关键字大写；主子句（SELECT/FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/JOIN/UPDATE/SET/DELETE 等）各占一行
 *  - `LEFT JOIN` 等连写不换行；顶层 `AND`/`OR` 缩进换行
 *  - SELECT/SET 后列清单换行（列缩进 = 本行缩进 + 4）
 *  - 子查询（括号内换行后）内容缩进 = 起始行 + 4、闭括号对齐起始行（EXISTS 子查询标准格式）
 *  - 运算符两侧空格、逗号后空格、`IN (`/`EXISTS (` 等运算符关键字与括号间留空格、函数调用 `COUNT(` 不空格
 *  - 空白（含 XML 动态标签展开残留空行）剔除
 *  - 字符串/数字/标识符原文保留（不做大小写改写）
 *
 * 覆盖本插件产出的 SELECT/UPDATE/DELETE + MPJ 联表形态，不做通用 SQL 方言格式化。
 */
object SqlFormatter {

    private val NEW_LINE_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT",
        "UPDATE", "SET", "DELETE", "INSERT", "INTO", "VALUES", "UNION", "JOIN",
        "LEFT", "RIGHT", "INNER", "FULL", "CROSS",
    )

    /**
     * DDL 首关键字（T-DdlLogPreview S2，20261006 第三轮走查）。
     *
     * 立项动因：`NEW_LINE_KEYWORDS` 只覆盖 DML 子句，DDL（CREATE/ALTER/DROP/TRUNCATE）**一个换行点
     * 都没有** ⇒ 用户点「格式化」看不到任何变化（实测 `CREATE TABLE …` 仍单行）。现按首关键字分派：
     * DDL 走下面两条规则（定义列表「`(` 换行缩进 4 + 顶层逗号逐列换行 + 闭括号对齐起始行」，
     * ALTER 的顶层逗号亦换行）——**DML 路径与既有语料输出逐字节不变**（规则全部由本集合门控）。
     */
    private val DDL_LEAD_KEYWORDS = setOf("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME")

    /** DDL 定义列表缩进（`(` 后与列定义逐行缩进量）。 */
    private const val DDL_LIST_INDENT = 4

    /** DDL 域内 `(` 前**保留空格**的关键字（惯例形态；其余关键字/标识符后的括号贴紧＝类型尺寸）。 */
    private val DDL_PAREN_SPACED_KEYWORDS = setOf("KEY", "REFERENCES", "CHECK", "IN", "EXISTS", "USING", "VALUES")

    /** 紧跟 JOIN 的修饰关键字（`LEFT JOIN` 保持同行）。 */
    private val JOIN_QUALIFIERS = setOf("LEFT", "RIGHT", "INNER", "FULL", "CROSS")

    /** 函数名（括号前不空格：`COUNT(`）。T-M29.3：补 CONCAT（MP LIKE 常用）/TRIM_SCALE（项目自定义）及常见函数。 */
    private val FUNCTION_NAMES = setOf(
        "COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "CAST", "CONVERT",
        "CONCAT", "TRIM_SCALE", "NULLIF", "IFNULL", "LENGTH", "SUBSTRING", "SUBSTR", "REPLACE",
        "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "ROUND", "ABS", "FLOOR", "CEIL", "CEILING",
        "NOW", "CURDATE", "DATE_FORMAT", "STR_TO_DATE", "GROUP_CONCAT", "IF", "JSON_EXTRACT",
        "DATEDIFF", "DATEADD",
    )

    /** 格式化 SQL 文本。 */
    fun format(sql: String): String {
        val tokens = lex(sql)
        if (tokens.isEmpty()) return sql.trim()
        val sb = StringBuilder(sql.length + 64)
        var depth = 0
        var pendingSpace = false
        var atLineStart = true
        var prevText = ""
        // T-DdlLogPreview S2：DDL 域内判定「类型尺寸括号」（`VARCHAR(64)` 贴紧标识符）需前一 token 类型
        var prevType: IElementType? = null
        // T-M27.4 缩进模型：lineIndent = 当前行缩进；parenIndents = 括号起始行缩进栈
        // （子查询内容缩进 = 起始行 + 4；闭括号对齐起始行）；parenNewlined = 括号内已换行
        var lineIndent = 0
        val parenIndents = ArrayDeque<Int>()
        var parenNewlined = false
        // T-M29.2：当前主子句（SELECT/SET 列清单逗号换行；LIMIT/GROUP/ORDER 等逗号保持同行）
        var currentClause = ""

        fun flushSpace() {
            if (pendingSpace && !atLineStart && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            pendingSpace = false
        }

        /** 换行 + 缩进（更新当前行缩进；括号内标记已换行）。 */
        fun newline(indent: Int) {
            sb.append('\n')
            repeat(indent) { sb.append(' ') }
            lineIndent = indent
            atLineStart = true
            pendingSpace = false
            if (depth > 0) parenNewlined = true
        }

        /** 子查询内新行缩进（起始行 + 4；顶层 0）。 */
        fun subIndent(): Int = if (depth > 0) parenIndents.last() + 4 else 0

        // T-DdlLogPreview S2：DDL 分派（首关键字判定；注释/空白已由 lex 剔除）
        val ddlLead = tokens.firstOrNull { it.first != SqlTokenTypes.COMMENT }
            ?.takeIf { it.first == SqlTokenTypes.KEYWORD }
            ?.second?.uppercase()
            ?.takeIf { it in DDL_LEAD_KEYWORDS }
        val ddlStatement = ddlLead != null

        for ((type, text) in tokens) {
            val upper = text.uppercase()
            when {
                // JOIN：前一个 token 是 LEFT/RIGHT/INNER 等 → 保持同行；否则新行（子查询内缩进）
                type == SqlTokenTypes.KEYWORD && upper == "JOIN" -> {
                    val qualifier = prevText.uppercase() in JOIN_QUALIFIERS
                    if (qualifier) {
                        flushSpace()
                    } else if (!atLineStart) {
                        newline(subIndent())
                    }
                    currentClause = "JOIN"
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 主子句 → 新行（顶层顶格；子查询内 = 起始行 + 4）
                type == SqlTokenTypes.KEYWORD && upper in NEW_LINE_KEYWORDS -> {
                    if (!atLineStart) newline(subIndent())
                    pendingSpace = false
                    currentClause = upper
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                    // SELECT/SET 后列清单换行（列缩进 = 本行缩进 + 4）
                    if (upper == "SELECT" || upper == "SET") newline(lineIndent + 4)
                }

                // AND/OR：顶层 → 换行缩进 4；子查询内（括号内已换行）→ 换行缩进 起始行+8；
                // 条件括号内（未换行）→ 保持同行
                type == SqlTokenTypes.KEYWORD && (upper == "AND" || upper == "OR") -> {
                    when {
                        depth == 0 -> if (!atLineStart) newline(4)
                        parenNewlined -> if (!atLineStart) newline(parenIndents.last() + 8)
                        else -> flushSpace()
                    }
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 其余关键字
                type == SqlTokenTypes.KEYWORD -> {
                    flushSpace()
                    sb.append(upper)
                    pendingSpace = true
                    atLineStart = false
                }

                // 左括号：运算符关键字后留空格（IN (）、函数名后不留（COUNT(）；记录起始行缩进
                type == SqlTokenTypes.PAREN && text == "(" -> {
                    // T-DdlLogPreview S2：DDL 定义列表内的**类型尺寸括号**贴紧左侧 token
                    // （`VARCHAR(64)`/`DECIMAL(10,2)` 不得被插空格——注意类型名多为 KEYWORD token；
                    // 而 `PRIMARY KEY (`id`)`／`CHECK (…)`／`REFERENCES` 按惯例留空格）
                    val ddlTightParen = ddlStatement && depth >= 1 && (
                        prevType == SqlTokenTypes.IDENTIFIER ||
                            (prevType == SqlTokenTypes.KEYWORD &&
                                prevText.uppercase() !in DDL_PAREN_SPACED_KEYWORDS)
                        )
                    if (!ddlTightParen && prevText.uppercase() !in FUNCTION_NAMES) flushSpace()
                    sb.append('(')
                    depth++
                    parenIndents.addLast(lineIndent)
                    parenNewlined = false
                    pendingSpace = false
                    atLineStart = false
                    // T-DdlLogPreview S2：DDL 定义列表开括号 → 换行缩进（列定义逐行；
                    // parenNewlined 由 newline 置位 ⇒ 闭括号按既有机制对齐起始行）
                    if (ddlStatement && depth == 1) {
                        newline(DDL_LIST_INDENT)
                    }
                }
                // 右括号：子查询闭括号（括号内已换行）→ 换行对齐起始行；否则同行
                type == SqlTokenTypes.PAREN && text == ")" -> {
                    // T-DdlLogPreview S2：DDL 定义列表的**外层闭括号恒独立成行**——列表内嵌套括号
                    // （`PRIMARY KEY (`id`)`）会把 parenNewlined 复位，靠既有判据会让 `)` 与表尾选项
                    // 粘在最后一条列定义行尾（实测形态），故 DDL 域内按 depth==1 显式断行
                    if (depth == 1 && ddlStatement && !atLineStart) {
                        newline(parenIndents.last())
                    } else if (parenNewlined && depth > 0) {
                        newline(parenIndents.last())
                    }
                    sb.append(')')
                    depth--
                    if (parenIndents.isNotEmpty()) parenIndents.removeLast()
                    parenNewlined = false
                    pendingSpace = true
                    atLineStart = false
                }

                // 逗号：SELECT/SET 列清单（顶层）→ 逗号保留行尾 + 换行缩进 4；其余顶层（LIMIT 参数/
                // GROUP BY/ORDER BY 列表）→ 保持同行；括号内（函数参数/IN 列表）→ 同行
                // T-M29.2：原实现顶层逗号只换行不输出逗号——列清单/分页参数逗号丢失（非法 SQL）
                type == SqlTokenTypes.OPERATOR && text == "," -> {
                    if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    sb.append(',')
                    if (ddlStatement && !atLineStart &&
                        (depth == 1 || (depth == 0 && ddlLead == "ALTER"))
                    ) {
                        // T-DdlLogPreview S2：DDL 定义列表（depth 1）逐列换行；ALTER 顶层动作（depth 0）
                        // 同样逐条换行——嵌套小括号（VARCHAR(64)/PRIMARY KEY (`id`)）不受影响
                        newline(DDL_LIST_INDENT)
                    } else if (depth == 0 && (currentClause == "SELECT" || currentClause == "SET") && !atLineStart) {
                        newline(4)
                    } else {
                        pendingSpace = true
                        atLineStart = false
                    }
                }

                // 点号：无空格
                type == SqlTokenTypes.OPERATOR && text == "." -> {
                    sb.append('.')
                    pendingSpace = false
                    atLineStart = false
                }

                // 其他运算符
                type == SqlTokenTypes.OPERATOR -> {
                    flushSpace()
                    sb.append(text)
                    pendingSpace = true
                    atLineStart = false
                }

                // 注释
                type == SqlTokenTypes.COMMENT -> {
                    if (!atLineStart) sb.append('\n')
                    sb.append(text)
                    sb.append('\n')
                    atLineStart = true
                    pendingSpace = false
                }

                // 标识符 / 字符串 / 数字 / 表名
                else -> {
                    flushSpace()
                    sb.append(text)
                    pendingSpace = true
                    atLineStart = false
                }
            }
            prevText = text
            prevType = type
        }
        return sb.toString().trim()
    }

    /**
     * 关键字换行格式化（M-KeywordWrap）：只在主子句关键字处换行，子句内容同行。
     *
     * 与 [format]（全展开）互补——前者用于阅读细节，后者用于快速总览。
     * 规则：
     *  - 关键字大写；主子句（SELECT/FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/JOIN/UPDATE/SET/DELETE 等）各占一行
     *  - SELECT/SET 后列清单同行，逗号无空格（`id,password,age`）
     *  - WHERE 后条件同行，AND/OR 前双空格（`=?  AND status=?`）
     *  - LIMIT/GROUP/ORDER 参数同行，逗号+空格
     *  - JOIN 修饰符（LEFT/RIGHT/INNER/FULL/CROSS）与 JOIN 同行
     *  - 括号内内容同行（子查询不展开）
     *  - 运算符两侧空格、字符串/数字/标识符原文保留
     *  - 空白剔除
     */
    fun formatClauseLine(sql: String): String {
        val tokens = lex(sql)
        if (tokens.isEmpty()) return sql.trim()
        val sb = StringBuilder(sql.length + 64)
        var pendingSpace = false
        var atLineStart = true
        var prevText = ""
        // T-DdlLogPreview S2：DDL 域内判定「类型尺寸括号」（`VARCHAR(64)` 贴紧标识符）需前一 token 类型
        var prevType: IElementType? = null
        var currentClause = ""
        var depth = 0  // 括号嵌套深度（括号内不换行 — 子查询同行）
        // SELECT/SET 列清单中刚经过逗号 → 下一个标识符前不空格（逗号紧贴列名）
        var pendingComma = false

        fun flushSpace() {
            if (pendingSpace && !atLineStart && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            pendingSpace = false
        }

        /** 换行顶格（仅顶层触发；括号内不换行）。 */
        fun newline() {
            sb.append('\n')
            atLineStart = true
            pendingSpace = false
        }

        for ((type, text) in tokens) {
            val upper = text.uppercase()
            when {
                // JOIN：前一个 token 是 LEFT/RIGHT/INNER 等 → 保持同行；否则顶层新行
                type == SqlTokenTypes.KEYWORD && upper == "JOIN" -> {
                    val qualifier = prevText.uppercase() in JOIN_QUALIFIERS
                    if (qualifier) {
                        flushSpace()
                    } else if (depth == 0 && !atLineStart) {
                        newline()
                    }
                    currentClause = "JOIN"
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                    pendingComma = false
                }

                // 主子句 → 顶层新行（括号内不换行 — 子查询同行，保留前导空格）
                type == SqlTokenTypes.KEYWORD && upper in NEW_LINE_KEYWORDS -> {
                    if (depth == 0) {
                        if (!atLineStart) newline()
                        pendingSpace = false
                    } else {
                        flushSpace()
                    }
                    currentClause = upper
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                    pendingComma = false
                }

                // AND/OR：不换行，保持同行（前导双空格 — 子句分隔视觉醒目）
                type == SqlTokenTypes.KEYWORD && (upper == "AND" || upper == "OR") -> {
                    // 清除待处理空格，手动添加双空格（不依赖 flushSpace 的单空格）
                    pendingSpace = false
                    if (!atLineStart) {
                        sb.append(' ')
                        sb.append(' ')
                    }
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                    pendingComma = false
                }

                // 其余关键字
                type == SqlTokenTypes.KEYWORD -> {
                    flushSpace()
                    sb.append(upper)
                    pendingSpace = true
                    atLineStart = false
                    pendingComma = false
                }

                // 左括号：运算符关键字后留空格（IN (）；函数名后不留（COUNT(）；深度+1
                type == SqlTokenTypes.PAREN && text == "(" -> {
                    if (prevText.uppercase() !in FUNCTION_NAMES) flushSpace()
                    sb.append('(')
                    depth++
                    pendingSpace = false
                    atLineStart = false
                }
                // 右括号：同行，深度-1（不 flushSpace — 避免 `1 )` 前多余空格）
                type == SqlTokenTypes.PAREN && text == ")" -> {
                    pendingSpace = false
                    sb.append(')')
                    depth--
                    pendingSpace = true
                    atLineStart = false
                }

                // 逗号：SELECT/SET 列清单或括号内 → 逗号紧贴（无空格）；其余 → 逗号+空格
                // F-CASE：始终设置 pendingSpace=true，后续关键字通过 flushSpace() 加空格；
                // 标识符通过 pendingComma 标记跳过空格（紧凑列清单）
                type == SqlTokenTypes.OPERATOR && text == "," -> {
                    if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    sb.append(',')
                    pendingComma = currentClause == "SELECT" || currentClause == "SET" || depth > 0
                    atLineStart = false
                    pendingSpace = true
                }

                // 点号：无空格
                type == SqlTokenTypes.OPERATOR && text == "." -> {
                    sb.append('.')
                    pendingSpace = false
                    pendingComma = false
                    atLineStart = false
                }

                // 其他运算符
                type == SqlTokenTypes.OPERATOR -> {
                    flushSpace()
                    sb.append(text)
                    pendingSpace = true
                    pendingComma = false
                    atLineStart = false
                }

                // 注释
                type == SqlTokenTypes.COMMENT -> {
                    if (!atLineStart) sb.append('\n')
                    sb.append(text)
                    sb.append('\n')
                    atLineStart = true
                    pendingSpace = false
                    pendingComma = false
                }

                // 标识符 / 字符串 / 数字 / 表名
                else -> {
                    if (pendingComma) {
                        // SELECT/SET 逗号后紧贴列名（无空格）
                        pendingComma = false
                    } else {
                        flushSpace()
                    }
                    sb.append(text)
                    pendingSpace = true
                    atLineStart = false
                }
            }
            prevText = text
        }
        return sb.toString().trim()
    }

    /**
     * 竖向排列格式化（T-VStack）：INSERT 语句列清单与 VALUES 逐行竖排，VALUES 每值行尾按最宽值
     * （含行尾逗号）对齐追加 `-- 列名` 配对标注——大 INSERT 列-值对位不必数逗号。
     *
     * T-VStack·扩面：非 INSERT 语句 = [format] 全展开 + [expandHorizontalGroups] 后处理
     * （IN 括号列表 / ORDER BY / GROUP BY / FROM 逗号联表纵向化），与「格式化」按钮产出可区分。
     *
     * 规则：
     *  - INSERT → 专用竖排路径；非 INSERT → format + 横向组展开
     *  - INSERT 列清单/VALUES 组各一元素一行（缩进 2、行尾逗号紧贴、闭括号顶格；批量多组 `),` 接排）
     *  - 配对标注：列清单全为单 token 元素才启用（`t.a` 等多 token 列不标注）；无列清单不标注；
     *    值按序与列取小对位，组内每行行尾注释只挂一个（用户注释优先于配对标注）；
     *    [annotatePairs]=false 时整项关闭（T-VPairAnno）——仅竖排不追加标注，用户自带注释不受影响
     *  - 值表达式整段保留：depth==1 逗号是唯一切分点，NOW()/CONCAT(a,b) 等嵌套括号内逗号原样
     *  - 组内注释挂行尾（本功能输出的 `-- 列名` 再输入时归位本行 → 幂等）；顶层注释自成一行；
     *    关闭标注后再输入已带标注文本，既有 `-- 列名` 按"用户行尾注释"规则归位保留（不剥离）
     *  - LIMIT 分页参数逗号与子查询 IN（元素首 token SELECT/WITH）不展开
     *  - 输出不变式：剔 COMMENT 后重分词的 token 流与原文一致——只动空白与行尾注释，不改语义 token
     */
    fun formatVertical(sql: String, annotatePairs: Boolean = true): String {
        val tokens = lex(sql)
        if (tokens.isEmpty()) return sql.trim()
        val first = tokens.firstOrNull { it.first != SqlTokenTypes.COMMENT } ?: return format(sql)
        if (first.first != SqlTokenTypes.KEYWORD || first.second.uppercase() != "INSERT") {
            // T-PreviewUx-Pack #1：UPDATE → SET 各项行尾追加「列名 (new) = 表达式」标注（吸收 DBX
            // UPDATE 预览 (new) 列口径：SET 表达式原样搬——按旧值求值即得新值，自引用 a = a + 1 亦正确）。
            // 标注以行尾注释呈现（T-VPairAnno 同款范式），不破坏 SQL 可执行性；开关复用 annotatePairs
            //（verticalPairAnnotation 设置，竖排标注族同一开关）。
            if (first.first == SqlTokenTypes.KEYWORD && first.second.uppercase() == "UPDATE") {
                return annotateUpdateNewColumns(expandHorizontalGroups(format(sql)), tokens, annotatePairs)
            }
            // T-VStack·扩面：非 INSERT = format 全展开 + 横向组展开后处理（IN 列表/ORDER BY/
            // GROUP BY/FROM 联表纵向化），与「格式化」按钮产出可区分
            return expandHorizontalGroups(format(sql))
        }

        // T-VPairAnno：关闭标注即空名单——renderGroup 的 `elemIndex in columnNames.indices` 恒假，
        // 标注追加与无注释行的对齐补齐同源归零（对齐只为标注竖向对齐服务），渲染主体零改动
        val columnNames = if (annotatePairs) collectInsertColumns(tokens) else emptyList()

        /** 组内一行：元素 token（闭组时统一渲染）+ 行尾逗号标记 + 行尾注释；elemIndex 为配对下标。 */
        class Line {
            val tokens = ArrayList<Pair<IElementType, String>>()
            var hadComma = false
            val trailing = ArrayList<String>()
            var elemIndex = -1
        }

        val sb = StringBuilder(sql.length + 128)
        var depth = 0
        var pendingSpace = false
        var atLineStart = true
        var seenValues = false
        var groupValues = false   // 当前括号组是否 VALUES 组（配对标注只在 VALUES 组）
        var elemTokens = ArrayList<Pair<IElementType, String>>()
        var elemHadComma = false
        val elemTrailing = ArrayList<String>()
        var elemCounter = 0       // 组内元素序号（配对列名下标；纯注释行不占号）
        val groupLines = ArrayList<Line>()

        fun flushSpace() {
            if (pendingSpace && !atLineStart && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            pendingSpace = false
        }

        fun newline(indent: Int) {
            sb.append('\n')
            repeat(indent) { sb.append(' ') }
            atLineStart = true
            pendingSpace = false
        }

        /** 元素 token 渲染（format 局部空格规则：运算符两侧空格、点号紧贴、函数名后括号紧贴）。 */
        fun renderElem(elem: List<Pair<IElementType, String>>): String {
            if (elem.isEmpty()) return ""
            val s = StringBuilder()
            var space = false
            var prev = ""
            for ((type, text) in elem) {
                when {
                    text == "(" -> {
                        if (prev.uppercase() !in FUNCTION_NAMES && space && s.isNotEmpty()) s.append(' ')
                        s.append('(')
                        space = false
                    }
                    text == ")" -> {
                        s.append(')')
                        space = true
                    }
                    text == "." -> {
                        s.append('.')
                        space = false
                    }
                    text == "," -> {
                        s.append(',')
                        space = true
                    }
                    else -> {
                        if (space && s.isNotEmpty()) s.append(' ')
                        s.append(text)
                        space = true
                    }
                }
                prev = text
            }
            return s.toString()
        }

        /** 收行入组缓冲（对齐宽依赖全组文本，闭组时才渲染）。 */
        fun closeLine() {
            val line = Line()
            line.tokens.addAll(elemTokens)
            line.hadComma = elemHadComma
            line.trailing.addAll(elemTrailing)
            if (elemTokens.isNotEmpty()) {
                line.elemIndex = elemCounter
                elemCounter++
            }
            groupLines += line
            elemTokens = ArrayList()
            elemHadComma = false
            elemTrailing.clear()
        }

        /** 闭组渲染：缩进 2 逐行 + 行尾逗号；VALUES 组可配对行按最宽前缀对齐追加 `-- 列名`（行尾注释优先）。 */
        fun renderGroup() {
            val rendered = groupLines.map { renderElem(it.tokens) to it }
            val alignWidth = rendered
                .filter { (text, _) -> text.isNotEmpty() }
                .maxOfOrNull { (text, line) -> text.length + if (line.hadComma) 1 else 0 } ?: 0
            for ((text, line) in rendered) {
                sb.append('\n').append("  ").append(text)
                if (line.hadComma) sb.append(',')
                val trailingText = line.trailing.joinToString(" ")
                // 配对标注只在 VALUES 组（列清单组不自我标注）；用户注释跨组通用且优先于配对标注
                if (trailingText.isNotEmpty() || (groupValues && line.elemIndex in columnNames.indices)) {
                    // 补齐到最宽前缀（含行尾逗号）即可——分隔空格由 ` -- ` 前导空格保证，不再设下限
                    // （下限会把零补齐行整体推歪一格）
                    val prefixWidth = text.length + if (line.hadComma) 1 else 0
                    repeat((alignWidth - prefixWidth).coerceAtLeast(0)) { sb.append(' ') }
                    when {
                        trailingText.isNotEmpty() -> sb.append(' ').append(trailingText)
                        else -> sb.append(" -- ").append(columnNames[line.elemIndex])
                    }
                }
            }
            sb.append('\n').append(')')
            groupLines.clear()
            atLineStart = false
            pendingSpace = true
        }

        for ((type, text) in tokens) {
            val upper = text.uppercase()
            when {
                // 注释：组内挂当前行尾（本功能输出的 `-- 列名` 重输入归位）；顶层自成一行
                type == SqlTokenTypes.COMMENT -> {
                    if (depth > 0) {
                        elemTrailing += text
                    } else {
                        if (!atLineStart) newline(0)
                        sb.append(text)
                        newline(0)
                    }
                }

                // depth-1 逗号：元素边界（行尾逗号标记；后续注释仍归本行）
                depth == 1 && type == SqlTokenTypes.OPERATOR && text == "," -> elemHadComma = true

                // depth-1 闭括号：收行 + 整组渲染
                depth == 1 && type == SqlTokenTypes.PAREN && text == ")" -> {
                    if (elemTokens.isNotEmpty() || elemHadComma || elemTrailing.isNotEmpty()) closeLine()
                    renderGroup()
                    depth = 0
                }

                // 组内内容：depth-1 逗号后的首个语义 token 收行开新行；其余入元素（括号调 depth）
                depth > 0 -> {
                    if (elemHadComma) closeLine()
                    elemTokens += type to text
                    if (type == SqlTokenTypes.PAREN && text == "(") depth++
                    if (type == SqlTokenTypes.PAREN && text == ")") depth--
                }

                // 顶层左括号：组开括号自占一行（列清单/VALUES 组）
                type == SqlTokenTypes.PAREN && text == "(" -> {
                    if (!atLineStart) newline(0)
                    sb.append('(')
                    atLineStart = false
                    pendingSpace = false
                    depth = 1
                    groupValues = seenValues
                    groupLines.clear()
                    elemCounter = 0
                }

                // 主子句关键字 → 顶格新行（首 token INSERT 顶格起）
                type == SqlTokenTypes.KEYWORD && upper in NEW_LINE_KEYWORDS -> {
                    if (!atLineStart) newline(0)
                    pendingSpace = false
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                    if (upper == "VALUES") seenValues = true
                }

                // AND/OR：顶层缩进换行（ON DUPLICATE KEY UPDATE 等尾缀条件）
                type == SqlTokenTypes.KEYWORD && (upper == "AND" || upper == "OR") -> {
                    if (!atLineStart) newline(4)
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 其余关键字
                type == SqlTokenTypes.KEYWORD -> {
                    flushSpace()
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 顶层逗号：批量 VALUES 组间分隔（`),` 后随组开括号换行）
                type == SqlTokenTypes.OPERATOR && text == "," -> {
                    sb.append(',')
                    atLineStart = false
                    pendingSpace = false
                }

                // 点号：无空格
                type == SqlTokenTypes.OPERATOR && text == "." -> {
                    sb.append('.')
                    pendingSpace = false
                    atLineStart = false
                }

                // 其他运算符 / 标识符 / 字符串 / 数字 / 表名
                else -> {
                    flushSpace()
                    sb.append(text)
                    atLineStart = false
                    pendingSpace = true
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * INSERT 列清单收集（配对名单）：VALUES 前首个 depth-0 括号组。
     * 元素恰为单 token（反引号列名是单 IDENTIFIER）才返回名单；多 token（`t.a`）/空组/括号不闭合 → 空（不标注）。
     */
    private fun collectInsertColumns(tokens: List<Pair<IElementType, String>>): List<String> {
        var depth = 0
        var seenValues = false
        for (i in tokens.indices) {
            val (type, text) = tokens[i]
            if (type == SqlTokenTypes.COMMENT) continue
            when {
                type == SqlTokenTypes.PAREN && text == "(" && depth == 0 && !seenValues -> {
                    val elements = ArrayList<ArrayList<Pair<IElementType, String>>>()
                    var cur = ArrayList<Pair<IElementType, String>>()
                    var d = 1
                    var j = i + 1
                    while (j < tokens.size && d > 0) {
                        val (t2, x2) = tokens[j]
                        when {
                            t2 == SqlTokenTypes.COMMENT -> {}  // 注释不参与配对名单
                            t2 == SqlTokenTypes.PAREN && x2 == "(" -> {
                                d++
                                cur += tokens[j]
                            }
                            t2 == SqlTokenTypes.PAREN && x2 == ")" -> {
                                d--
                                if (d > 0) cur += tokens[j]
                            }
                            t2 == SqlTokenTypes.OPERATOR && x2 == "," && d == 1 -> {
                                elements += cur
                                cur = ArrayList()
                            }
                            else -> cur += tokens[j]
                        }
                        j++
                    }
                    val closed = d == 0
                    if (cur.isNotEmpty()) elements += cur
                    val valid = closed && elements.isNotEmpty() && elements.all { it.size == 1 }
                    return if (valid) elements.map { it[0].second } else emptyList()
                }
                type == SqlTokenTypes.PAREN && text == "(" -> depth++
                type == SqlTokenTypes.PAREN && text == ")" -> depth--
                type == SqlTokenTypes.KEYWORD && text.uppercase() == "VALUES" && depth == 0 -> seenValues = true
            }
        }
        return emptyList()
    }

    // ------------------------------------------------------------------
    // T-PreviewUx-Pack #1：UPDATE 预览 (new) 列（吸收 DBX dml_preview_sql 纯函数口径）
    // ------------------------------------------------------------------

    /** SET 赋值项：列名（token 原文紧贴拼合）+ 表达式（token 渲染，运算符空格/函数括号紧贴）。 */
    internal data class UpdateSetAssignment(val column: String, val expression: String)

    /** SET 段终止关键字（顶层新主子句开始；沿用 NEW_LINE_KEYWORDS，剔除 SET 自身）。 */
    private val SET_TERMINAL_KEYWORDS = NEW_LINE_KEYWORDS - "SET"

    /**
     * 解析 UPDATE 语句 SET 子句 → 列名/表达式分离（纯函数，无 PSI，headless 可测）。
     *
     * 口径（DBX dml_preview_sql.rs）：SET 表达式**原样搬**——按旧行值求值即得新值，
     * 自引用（`a = a + 1`）原样即正确。顶层逗号切分各项，括号内逗号（函数参数/嵌套表达式）
     * 不切分；每项以首个顶层 `=` 分离列名与表达式；无 `=`/表达式为空的项跳过（不标注）。
     */
    internal fun parseUpdateSetAssignments(tokens: List<Pair<IElementType, String>>): List<UpdateSetAssignment> {
        // 1) 定位 UPDATE 后首个顶层 SET（防表名恰为 set 的误匹配）
        var depth = 0
        var setIdx = -1
        var seenUpdate = false
        var i = 0
        while (i < tokens.size) {
            val (type, text) = tokens[i]
            when {
                type == SqlTokenTypes.PAREN && text == "(" -> depth++
                type == SqlTokenTypes.PAREN && text == ")" -> depth--
                type == SqlTokenTypes.KEYWORD && depth == 0 -> {
                    val upper = text.uppercase()
                    if (upper == "UPDATE" && !seenUpdate) {
                        seenUpdate = true
                    } else if (upper == "SET" && seenUpdate) {
                        setIdx = i
                        break
                    }
                }
            }
            i++
        }
        if (setIdx < 0) return emptyList()
        // 2) SET 段收集各项：顶层逗号切分，终止于下一个顶层主子句关键字
        val items = ArrayList<List<Pair<IElementType, String>>>()
        var cur = ArrayList<Pair<IElementType, String>>()
        depth = 0
        var j = setIdx + 1
        while (j < tokens.size) {
            val (type, text) = tokens[j]
            when {
                type == SqlTokenTypes.COMMENT -> {}
                type == SqlTokenTypes.PAREN && text == "(" -> {
                    depth++
                    cur += tokens[j]
                }
                type == SqlTokenTypes.PAREN && text == ")" -> {
                    depth--
                    cur += tokens[j]
                }
                type == SqlTokenTypes.OPERATOR && text == "," && depth == 0 -> {
                    if (cur.isNotEmpty()) items += cur
                    cur = ArrayList()
                }
                type == SqlTokenTypes.KEYWORD && depth == 0 && text.uppercase() in SET_TERMINAL_KEYWORDS -> return finishUpdateSetItems(items, cur)
                else -> cur += tokens[j]
            }
            j++
        }
        return finishUpdateSetItems(items, cur)
    }

    /** 各项按首个顶层 `=` 分离列名/表达式（无 `=`/表达式空的项跳过）。 */
    private fun finishUpdateSetItems(
        items: List<List<Pair<IElementType, String>>>,
        tail: List<Pair<IElementType, String>>,
    ): List<UpdateSetAssignment> {
        val all = if (tail.isNotEmpty()) items + listOf(tail) else items
        return all.mapNotNull { item ->
            val eqIdx = item.indexOfFirst { it.first == SqlTokenTypes.OPERATOR && it.second == "=" }
            if (eqIdx <= 0) return@mapNotNull null
            val exprTokens = item.subList(eqIdx + 1, item.size)
            if (exprTokens.isEmpty()) return@mapNotNull null
            UpdateSetAssignment(
                column = concatTokenText(item.subList(0, eqIdx)),
                expression = renderTokenText(exprTokens),
            )
        }
    }

    /** token 原文紧贴拼合（列名形态：`age` / `t.a` / `` `order` ``）。 */
    private fun concatTokenText(tokens: List<Pair<IElementType, String>>): String =
        tokens.joinToString("") { it.second }

    /**
     * token 渲染为表达式文本（与 INSERT 组元素渲染同款空格规则：运算符两侧空格、点号紧贴、
     * 函数名后括号紧贴、逗号后空格）。
     */
    private fun renderTokenText(elem: List<Pair<IElementType, String>>): String {
        val s = StringBuilder()
        var space = false
        var prev = ""
        for ((type, text) in elem) {
            when {
                text == "(" -> {
                    if (prev.uppercase() !in FUNCTION_NAMES && space && s.isNotEmpty()) s.append(' ')
                    s.append('(')
                    space = false
                }
                text == ")" -> {
                    s.append(')')
                    space = true
                }
                text == "." -> {
                    s.append('.')
                    space = false
                }
                text == "," -> {
                    s.append(',')
                    space = true
                }
                else -> {
                    if (space && s.isNotEmpty()) s.append(' ')
                    s.append(text)
                    space = true
                }
            }
            prev = text
        }
        return s.toString()
    }

    /**
     * UPDATE 竖排产物追加 (new) 列标注：SET 段内匹配到 [parseUpdateSetAssignments] 项的行，
     * 行尾追加 `-- 列名 (new) = 表达式`（T-VPairAnno 行尾标注同款范式，不破坏 SQL 可执行性）。
     * 幂等：已有同名 `(new)` 标注（行尾注释或独立注释行，format 会把行尾注释拆为独立行）不重复追加。
     */
    private fun annotateUpdateNewColumns(formatted: String, tokens: List<Pair<IElementType, String>>, annotate: Boolean): String {
        if (!annotate) return formatted
        val assignments = parseUpdateSetAssignments(tokens)
        if (assignments.isEmpty()) return formatted
        val lines = formatted.split("\n")
        // 第一遍：全量收集已有 (new) 标注列名——format 会把行尾注释拆为独立行（位于其 SET 项行
        // 下方），逐行边收集边追加会漏掉下方注释导致重复标注
        val annotatedCols = HashSet<String>()
        lines.forEach { line -> existingNewColumnAnnotation(line.trim())?.let { annotatedCols += it } }
        // 第二遍：SET 段内匹配项追加行尾标注
        val out = StringBuilder(formatted.length + 64)
        var inSetClause = false
        lines.forEachIndexed { idx, line ->
            val trimmed = line.trim()
            val lineTokens = lex(trimmed)
            val firstTok = lineTokens.firstOrNull()
            if (firstTok != null && firstTok.first == SqlTokenTypes.KEYWORD) {
                val upper = firstTok.second.uppercase()
                when {
                    upper == "SET" -> inSetClause = true
                    upper in NEW_LINE_KEYWORDS -> inSetClause = false
                }
            }
            var lineOut = line
            if (inSetClause && trimmed.isNotEmpty() && !trimmed.startsWith("--")) {
                val eqIdx = lineTokens.indexOfFirst { it.first == SqlTokenTypes.OPERATOR && it.second == "=" }
                if (eqIdx > 0) {
                    val col = concatTokenText(lineTokens.subList(0, eqIdx))
                    val assignment = assignments.firstOrNull { it.column == col }
                    if (assignment != null && col !in annotatedCols) {
                        annotatedCols += col
                        lineOut = line.trimEnd() + "  -- " + col + " (new) = " + assignment.expression
                    }
                }
            }
            if (idx > 0) out.append('\n')
            out.append(lineOut)
        }
        return out.toString()
    }

    /** 提取行内已有 `(new)` 标注的列名（`-- col (new) = ...`；无标注返回 null）。 */
    private fun existingNewColumnAnnotation(trimmed: String): String? {
        val idx = trimmed.lastIndexOf("--")
        if (idx < 0) return null
        val comment = trimmed.substring(idx + 2).trim()
        val newIdx = comment.indexOf("(new)")
        if (newIdx <= 0) return null
        return comment.substring(0, newIdx).trim()
    }

    /**
     * 横向组展开（T-VStack·扩面）：对 [format] 产物做纯文本后处理，把仍横排的组纵向化——
     * IN 括号列表（元素非子查询）/ ORDER BY 字段清单 / GROUP BY 字段清单 / FROM 逗号联表。
     * 只在「已被 format 排成单行」的子句段内操作，是纯增量变换 → 幂等；LIMIT 逗号（分页参数）
     * 与子查询 IN（元素首 token 为 SELECT）不展开。
     */
    private fun expandHorizontalGroups(formatted: String): String {
        val out = StringBuilder(formatted.length + 128)
        var pos = 0
        var changed = false
        while (pos < formatted.length) {
            val lineEnd = formatted.indexOf('\n', pos).let { if (it < 0) formatted.length else it }
            val line = formatted.substring(pos, lineEnd)

            /** 当前行的缩进（展开元素行沿用同一缩进 + 4）。 */
            val indent = line.takeWhile { it == ' ' }
            val trimmed = line.trim()
            val expanded: String? = when {
                trimmed.startsWith("--") || trimmed.startsWith("/*") -> null
                else -> expandInList(line, trimmed, indent)
                    ?: expandCommaClause(line, trimmed, indent, "ORDER BY")
                    ?: expandCommaClause(line, trimmed, indent, "GROUP BY")
                    ?: expandFromCommaTables(line, trimmed, indent)
            }
            if (expanded == null) {
                out.append(line)
            } else {
                out.append(expanded)
                changed = true
            }
            if (lineEnd < formatted.length) out.append('\n')
            pos = lineEnd + 1
        }
        return if (changed) out.toString() else formatted
    }

    /**
     * IN 列表展开：行含 `IN (` 且括号在本行闭合、首元素非子查询、含顶层（括号内层）逗号 → 逐元素一行
     * （`(` 自占一行，对齐已交付 INSERT 组形态）。line=原行（保前缀缩进）、trimmed=去缩进行（定位用）。
     */
    private fun expandInList(line: String, trimmed: String, indent: String): String? {
        val inIdx = findKeywordIndex(trimmed, "IN")
        if (inIdx < 0) return null
        val openIdx = trimmed.indexOf('(', inIdx + 2)
        if (openIdx < 0) return null
        val closeIdx = matchParen(trimmed, openIdx) ?: return null
        val inner = trimmed.substring(openIdx + 1, closeIdx).trim()
        if (inner.isEmpty()) return null
        // 子查询形态（IN (SELECT …)）不展开
        val innerTokens = lex(inner)
        val first = innerTokens.firstOrNull { it.first != SqlTokenTypes.COMMENT } ?: return null
        if (first.first == SqlTokenTypes.KEYWORD && first.second.uppercase() in setOf("SELECT", "WITH")) return null
        // 顶层（括号内层 depth1）逗号切分
        val elements = splitTopLevel(inner)
        // T-VStack·InAll：含逗号即 ≥2 元素一律逐行展开（InThreshold 阈值经用户复议回退——
        // 短清单数逗号对位反成负担）；仅单元素（无逗号）保持同行——无清单可对齐，竖排纯花行高
        if (elements.size < 2) return null
        val body = StringBuilder()
        body.append(indent).append(trimmed.substring(0, openIdx).trimEnd()).append('\n')
        body.append(indent).append('(').append('\n')
        elements.forEachIndexed { i, element ->
            body.append(indent).append("    ").append(element.trim())
            if (i < elements.size - 1) body.append(',')
            body.append('\n')
        }
        body.append(indent).append(')').append(trimmed.substring(closeIdx + 1).trimEnd())
        return body.toString()
    }

    /** ORDER BY / GROUP BY 字段展开：`ORDER BY a DESC, b ASC` → 每字段一行（修饰符紧跟字段）。 */
    private fun expandCommaClause(line: String, trimmed: String, indent: String, keyword: String): String? {
        val kwIdx = findKeywordIndex(trimmed, keyword)
        if (kwIdx < 0) return null
        val rest = trimmed.substring(kwIdx + keyword.length).trim()
        if (rest.isEmpty() || rest.startsWith("(")) return null
        val elements = splitTopLevel(rest)
        if (elements.size < 2) return null
        val body = StringBuilder()
        body.append(indent).append(trimmed.substring(0, kwIdx)).append(keyword)
        elements.forEachIndexed { i, element ->
            body.append('\n').append(indent).append("    ").append(element.trim())
            if (i < elements.size - 1) body.append(',')
        }
        // 行尾残余校验：切分覆盖整个 rest——tail 含括号说明形态超出单行清单（不展开，避免误伤）
        val closeIdx = kwIdx + keyword.length + trimmed.substring(kwIdx + keyword.length).length
        val tail = trimmed.substring(closeIdx)
        if (tail.contains('(') || tail.contains(')')) return null
        return body.toString()
    }

    /** FROM 逗号联表展开：`FROM a, b` → 一表一行（含别名）。 */
    private fun expandFromCommaTables(line: String, trimmed: String, indent: String): String? {
        val kwIdx = findKeywordIndex(trimmed, "FROM")
        if (kwIdx < 0) return null
        val rest = trimmed.substring(kwIdx + "FROM".length).trim()
        if (rest.isEmpty() || rest.contains('(')) return null
        val elements = splitTopLevel(rest)
        if (elements.size < 2) return null
        val body = StringBuilder()
        body.append(indent).append(trimmed.substring(0, kwIdx)).append("FROM")
        elements.forEachIndexed { i, element ->
            body.append('\n').append(indent).append("    ").append(element.trim())
            if (i < elements.size - 1) body.append(',')
        }
        return body.toString()
    }

    /** 关键字整词定位（大小写不敏感、两侧非标识符字符）。 */
    private fun findKeywordIndex(line: String, keyword: String): Int {
        var idx = 0
        while (true) {
            idx = line.indexOf(keyword, idx, ignoreCase = true)
            if (idx < 0) return -1
            val beforeOk = idx == 0 || !line[idx - 1].isLetterOrDigit() && line[idx - 1] != '_' && line[idx - 1] != '.'
            val after = idx + keyword.length
            val afterOk = after >= line.length || !line[after].isLetterOrDigit() && line[after] != '_'
            if (beforeOk && afterOk) return idx
            idx++
        }
    }

    /** 与 openIdx 的 `(` 配对的 `)` 下标（嵌套计数；字符串字面量内的括号不计数）。 */
    private fun matchParen(line: String, openIdx: Int): Int? {
        var depth = 0
        var i = openIdx
        var inString = false
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\'' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 顶层逗号切分（括号深度 0 的逗号；字符串字面量内逗号不切）。 */
    private fun splitTopLevel(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var inString = false
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            when {
                c == '\'' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> depth--
                !inString && c == ',' && depth == 0 -> {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += text.substring(start)
        return parts
    }

    /** 分词（剔除空白——含 XML 动态标签展开残留空行）＋ 参数占位原子段合并（见 [mergeParamPlaceholders]）。 */
    private fun lex(sql: String): List<Pair<IElementType, String>> {
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE) tokens += type to lexer.tokenText
            lexer.advance()
        }
        return mergeParamPlaceholders(tokens)
    }

    // ------------------------------------------------------------------
    // T-IbatisFix2（2026-11-10 真机反馈 ④）：参数占位原子段
    // ------------------------------------------------------------------

    /**
     * `#x#` / `#{x}` / `${x}` 参数占位是**原子段**——三条格式化路径（[format] / [formatClauseLine] /
     * [formatVertical]）同源分词，一处合并三处生效。
     *
     * iBatis 的 `#username#`（含 `#x:TYPE:null#` 三段）与 MyBatis 的 `#{x}` / `${x}` 在 [SqlLexer]
     * 下被拆成 `#` + 标识符 + `#`（`{`/`}` 各为运算符、`$` 是标识符首字符）多个 token，格式化时按
     * 运算符空格规则拼出 `# username #` / `# { x }`（用户 2026.3 截图实证）——占位符必须整体保留：
     * 内容原样拼接（不做大小写改写、不插空格），前后按既有 token 间距规则走。
     *
     * 合并判据（token 流上做，词法器不吐偏移）：
     * - `#`（运算符）后随 `{`（运算符）→ MyBatis 花括号形态，合并到配对 `}`（嵌套计数）；
     * - `$`（单字符标识符）后随 `{` → MyBatis `${}` 形态，同上；
     * - `#`（运算符）→ 中间只含标识符/数字/`:` `[` `]`（iBatis 的 `#x:TYPE:null#` 与 `#ids[0]#`）
     *   → 闭合 `#`：iBatis 内联参数；`##` 转义（空内容）同样合并、原样保留。
     * 闭合/配对找不到时**整段不动**（未闭合残段按原 token 展示，与渲染器「未闭合」告警口径一致）。
     * 合并产物标 [SqlTokenTypes.IDENTIFIER]——三条路径的「原文保留」分支（token 文本原样输出）。
     */
    private fun mergeParamPlaceholders(tokens: List<Pair<IElementType, String>>): List<Pair<IElementType, String>> {
        if (tokens.none { (type, text) ->
                (type == SqlTokenTypes.OPERATOR && text == "#") ||
                    (text == "$" && (type == SqlTokenTypes.IDENTIFIER || type == SqlTokenTypes.TABLE))
            }
        ) {
            return tokens
        }
        val out = ArrayList<Pair<IElementType, String>>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val (type, text) = tokens[i]
            val mergedEnd: Int? = when {
                type == SqlTokenTypes.OPERATOR && text == "#" ->
                    if (i + 1 < tokens.size && tokens[i + 1].second == "{") matchBraceClose(tokens, i + 1)
                    else matchHashClose(tokens, i)
                text == "$" && (type == SqlTokenTypes.IDENTIFIER || type == SqlTokenTypes.TABLE) &&
                    i + 1 < tokens.size && tokens[i + 1].second == "{" -> matchBraceClose(tokens, i + 1)
                else -> null
            }
            if (mergedEnd == null) {
                out += tokens[i]
                i++
            } else {
                out += SqlTokenTypes.IDENTIFIER to tokens.subList(i, mergedEnd + 1).joinToString(separator = "") { it.second }
                i = mergedEnd + 1
            }
        }
        return out
    }

    /** iBatis 内联参数闭合 `#` 定位（[openIdx] 是开 `#`；中间只许标识符/数字/`:` `[` `]`）。未闭合返回 null。 */
    private fun matchHashClose(tokens: List<Pair<IElementType, String>>, openIdx: Int): Int? {
        for (j in openIdx + 1 until tokens.size) {
            val (type, text) = tokens[j]
            if (type == SqlTokenTypes.OPERATOR && text == "#") return j
            val innerOperator = type == SqlTokenTypes.OPERATOR && text in HASH_INNER_OPERATORS
            if (type != SqlTokenTypes.IDENTIFIER && type != SqlTokenTypes.NUMBER && !innerOperator) return null
        }
        return null
    }

    /** MyBatis 花括号形态配对 `}` 定位（[openIdx] 是开 `{`；嵌套计数）。未闭合返回 null。 */
    private fun matchBraceClose(tokens: List<Pair<IElementType, String>>, openIdx: Int): Int? {
        var depth = 0
        for (j in openIdx until tokens.size) {
            val (type, text) = tokens[j]
            if (type != SqlTokenTypes.OPERATOR) continue
            if (text == "{") depth++
            if (text == "}") {
                depth--
                if (depth == 0) return j
            }
        }
        return null
    }

    /** iBatis `#x#` 内部允许出现的运算符（三段 `:`、iterate 下标 `[]`）。 */
    private val HASH_INNER_OPERATORS = setOf(":", "[", "]")
}
