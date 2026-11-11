package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * 轻量 SQL 词法器（M8-01）。
 *
 * 仅为本插件产出的 SQL 做语法高亮分词，不追求通用 SQL 方言：
 *  - 关键字 / 标识符 / 表名 / 字符串 / 数字 / 注释 / 运算符 / 括号
 *  - 表名启发式：`FROM` / `JOIN` / `INTO` / `UPDATE` 后的标识符（及紧随的表别名）
 *    标记为 [SqlTokenTypes.TABLE]，对齐参考样式中"表名/别名"与"列名"分色。
 *
 * 原生 SQL 高亮由平台 SqlSyntaxHighlighterFactory 提供；SqlLexer 仅供 [SqlFormatter] 格式化与 HTML 渲染使用。
 */
class SqlLexer : LexerBase() {

    private var myBuffer: CharSequence = ""
    private var myEnd = 0
    private var myTokenStart = 0
    private var myTokenEnd = 0
    private var myTokenType: IElementType? = null

    /** 表名启发式状态：0=普通；1=期望表名；2=表名后（期望别名或进入后续子句）。 */
    private var myTableState = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        myBuffer = buffer
        myEnd = endOffset
        myTokenStart = startOffset
        myTokenEnd = startOffset
        myTokenType = null
        myTableState = 0
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = myTokenType

    override fun getTokenStart(): Int = myTokenStart

    override fun getTokenEnd(): Int = myTokenEnd

    override fun getBufferSequence(): CharSequence = myBuffer

    override fun getBufferEnd(): Int = myEnd

    override fun advance() {
        myTokenStart = myTokenEnd
        if (myTokenEnd >= myEnd) {
            myTokenType = null
            return
        }
        val c = myBuffer[myTokenStart]
        when {
            c.isWhitespace() -> scanWhitespace()
            isLineCommentStart() -> scanLineComment()
            isBlockCommentStart() -> scanBlockComment()
            c == '\'' -> scanString()
            // T-M29.3：双引号字符串（MySQL 字符串字面量 / PG 标识符）——整段单 token，
            // 否则 `"1"` 被拆成 `"`+`1`+`"` 三个 token（格式化拼出 `" 1 "`）
            c == '"' -> scanDoubleQuoted()
            c.isDigit() -> scanNumber()
            isIdentifierStart(c) -> scanWord()
            c == '`' || c == '[' -> scanQuotedIdentifier()
            c == '(' || c == ')' -> {
                myTokenEnd++
                myTokenType = SqlTokenTypes.PAREN
            }
            else -> scanOperator()
        }
    }

    // ------------------------------------------------------------------
    // 各 token 扫描
    // ------------------------------------------------------------------

    /** 空白（不改变表名启发式状态，避免 `FROM tbl` 中间空白打断）。 */
    private fun scanWhitespace() {
        while (myTokenEnd < myEnd && myBuffer[myTokenEnd].isWhitespace()) myTokenEnd++
        myTokenType = TokenType.WHITE_SPACE
    }

    /** `--` 行注释。 */
    private fun scanLineComment() {
        myTokenEnd += 2
        while (myTokenEnd < myEnd && myBuffer[myTokenEnd] != '\n') myTokenEnd++
        myTokenType = SqlTokenTypes.COMMENT
    }

    /** 扫描块注释（斜杠星号包裹）。 */
    private fun scanBlockComment() {
        myTokenEnd += 2
        while (myTokenEnd + 1 < myEnd && !(myBuffer[myTokenEnd] == '*' && myBuffer[myTokenEnd + 1] == '/')) myTokenEnd++
        myTokenEnd = if (myTokenEnd + 1 < myEnd) myTokenEnd + 2 else myEnd
        myTokenType = SqlTokenTypes.COMMENT
    }

    /** `'...'` 字符串（`''` 转义）。 */
    private fun scanString() {
        myTokenEnd++
        while (myTokenEnd < myEnd) {
            if (myBuffer[myTokenEnd] == '\'') {
                if (myTokenEnd + 1 < myEnd && myBuffer[myTokenEnd + 1] == '\'') {
                    myTokenEnd += 2
                } else {
                    myTokenEnd++
                    break
                }
            } else {
                myTokenEnd++
            }
        }
        myTokenType = SqlTokenTypes.STRING
        myTableState = 0
    }

    /** `"..."` 双引号字符串（`""` 转义；T-M29.3）。 */
    private fun scanDoubleQuoted() {
        myTokenEnd++
        while (myTokenEnd < myEnd) {
            if (myBuffer[myTokenEnd] == '"') {
                if (myTokenEnd + 1 < myEnd && myBuffer[myTokenEnd + 1] == '"') {
                    myTokenEnd += 2
                } else {
                    myTokenEnd++
                    break
                }
            } else {
                myTokenEnd++
            }
        }
        myTokenType = SqlTokenTypes.STRING
        myTableState = 0
    }

    /** 数字（含小数）。 */
    private fun scanNumber() {
        myTokenEnd++
        while (myTokenEnd < myEnd && (myBuffer[myTokenEnd].isDigit() || myBuffer[myTokenEnd] == '.')) myTokenEnd++
        myTokenType = SqlTokenTypes.NUMBER
        myTableState = 0
    }

    /** 标识符 / 关键字（按表名启发式状态分类）。 */
    private fun scanWord() {
        myTokenEnd++
        while (myTokenEnd < myEnd && isIdentifierPart(myBuffer[myTokenEnd])) myTokenEnd++
        val word = myBuffer.subSequence(myTokenStart, myTokenEnd).toString()
        myTokenType = classifyWord(word)
    }

    /** 反引号 `` `xxx` `` / 方括号 `[xxx]` 标识符。 */
    private fun scanQuotedIdentifier() {
        myTokenEnd++
        while (myTokenEnd < myEnd && myBuffer[myTokenEnd] != '`' && myBuffer[myTokenEnd] != ']') myTokenEnd++
        if (myTokenEnd < myEnd) myTokenEnd++
        myTokenType = SqlTokenTypes.IDENTIFIER
        myTableState = 0
    }

    /**
     * 运算符 / 标点（= != <> < <= > >= + - * / % ? 等）。
     *
     * T-SqlOperatorFidelity（用户 2026-11-11 真机反馈）：多字符运算符必须**整体**成一个 token。
     * 原实现只合并 `!=` `<>` `<=` `>=` 四个，`||` 被切成两个 `|` token，[SqlFormatter] 的
     * 「运算符两侧空格」规则随即把它拼成 `| |`（Oracle 下语法错）——而预览面板的编辑器文本同时
     * 是复制可执行 SQL、分享、直接执行的输入源。同类还有 `::`（PG 强转）/ `<=>` / `->` / `->>` /
     * `&&` / `<<` / `>>` / `:=` / `@>` / `<@` / `!~` / `~*` / `=>`。
     */
    private fun scanOperator() {
        for (op in MULTI_CHAR_OPERATORS) {
            if (matchesAt(myTokenStart, op)) {
                myTokenEnd = myTokenStart + op.length
                myTokenType = SqlTokenTypes.OPERATOR
                myTableState = 0
                return
            }
        }
        myTokenEnd++
        myTokenType = SqlTokenTypes.OPERATOR
        myTableState = 0
    }

    /** [index] 起是否逐字等于 [op]（越界即否）。 */
    private fun matchesAt(index: Int, op: String): Boolean {
        if (index + op.length > myEnd) return false
        for (k in op.indices) {
            if (myBuffer[index + k] != op[k]) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // 表名启发式
    // ------------------------------------------------------------------

    /**
     * 关键字集合（大小写不敏感）。仅覆盖本插件 SQL 会出现的子句关键字。
     */
    private fun classifyWord(word: String): IElementType {
        val upper = word.uppercase()
        if (upper in KEYWORDS) {
            when (upper) {
                "FROM", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "INTO", "UPDATE", "TABLE" -> myTableState = 1
                "AS" -> { /* AS 后可为表别名或列别名，保持当前状态 */ }
                else -> myTableState = 0
            }
            return SqlTokenTypes.KEYWORD
        }
        return when (myTableState) {
            1 -> {
                myTableState = 2
                SqlTokenTypes.TABLE
            }
            2 -> {
                myTableState = 0
                SqlTokenTypes.TABLE
            }
            else -> SqlTokenTypes.IDENTIFIER
        }
    }

    private fun isLineCommentStart(): Boolean =
        cAt(myTokenStart) == '-' && cAt(myTokenStart + 1) == '-'

    private fun isBlockCommentStart(): Boolean =
        cAt(myTokenStart) == '/' && cAt(myTokenStart + 1) == '*'

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** 越界返回 NUL。 */
    private fun cAt(index: Int): Char = if (index < myEnd) myBuffer[index] else '\u0000'

    companion object {
        /**
         * 多字符运算符表（T-SqlOperatorFidelity）：**长的在前**，[scanOperator] 按此顺序最长匹配。
         *
         * 收录口径＝插件产物与用户 SQL 里真实出现、且合并后不会与别的原子段打架的组合：
         *  - `||` 字符串拼接（Oracle / 达梦 / 金仓 / PG / SQLite；MySQL 逻辑或）
         *  - `::` PG 系强转；`:=` 赋值；`=>` 具名实参
         *  - `<=>` MySQL 空安全等值；`->` / `->>` JSON 取值
         *  - `&&` 逻辑与 / 数组重叠；`<<` / `>>` 位移
         *  - `!~` `~*` PG 正则；`@>` `<@` jsonb/数组包含
         *  - 既有 `!=` `<>` `<=` `>=`（产物逐字节不变）
         *
         * **刻意不收录**：`#>` / `#>>` / `#-`——iBatis 内联参数 `#x#` 的**闭合 `#`** 就在前面，
         * 合并后 `SqlFormatter.matchHashClose` 再也认不出闭合界（`WHERE id#>0` 会把参数拆坏）；
         * `?|` / `?&`——`?` 是未解析参数占位符，另有一套位点记账，不与运算符合并。
         */
        private val MULTI_CHAR_OPERATORS = listOf(
            "<=>", "->>",
            "!=", "<>", "<=", ">=", "||", "::", ":=",
            "->", "&&", "<<", ">>", "!~", "~*", "@>", "<@", "=>",
        )

        /** SQL 关键字（大写，去重；补全器复用）。 */
        internal val KEYWORDS = setOf(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN", "LIKE", "EXISTS",
            "GROUP", "BY", "ORDER", "ASC", "DESC", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "ALL",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON", "AS", "USING", "UNION",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "TABLE", "CREATE", "DROP", "ALTER", "ADD",
            "COLUMN", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "DEFAULT", "INDEX",
            "CASE", "WHEN", "THEN", "ELSE", "END",
            "COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "NULLIF", "CAST", "CONVERT",
        )
    }
}
