package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lexer.LexerBase
import com.intellij.lexer.RestartableLexer
import com.intellij.lexer.TokenIterator
import com.intellij.psi.tree.IElementType

/**
 * 手写单趟词法（lexer-only）。T-XmlGap W2/W3 在原 SQL 词法之上加两个**参数子状态机**。
 *
 * ## token 优先级（未变部分）
 *
 * SQL 顶层：注释（`--` / 块注释）> 字符串（`'…'` / `"…"`，`''` 加倍不截断）> 参数定界符
 * （`#{` / `${`，`\#` 转义按普通文本）> 数字 > 关键字/标识符 > 标点/空白。
 * 字符串态内不识别 `#{}`（MyBatis 参数占位不出现在字符串内的主形态；字符串内一切按 STRING）。
 * 未闭合字符串/注释扫到尾（BAD 形态并入 STRING/COMMENT）——**不产 error token，注入着色不做校验器**。
 *
 * ## W2：`#{}` 与 `${}` 分色（决策 2-A）
 *
 * `#{`/`${` 各出一个定界符 token（[SqlMapperTT.PARAM_HASH] / [SqlMapperTT.PARAM_DOLLAR]），
 * 内容另出细 token；配对的 `}` 也按**起头定界符的种类**回归同类 token——因此两个占位符
 * 「从开头到闭合」都可辨（高亮器给两个 token 各配一个 TextAttributesKey）。
 *
 * ## W3：两个子状态机（同一 Lexer，不新建 Language）
 *
 * | 子状态 | 进入 | 内容 token | 退出 |
 * |---|---|---|---|
 * | 参数规格 `#{…}` | 顶层 `#{` | 参数名/属性名/属性值一律标识符（**此处不判关键字**，见 [classifyWord]）、`,`、`=`、属性值（字符串/数字） | 配对 `}`（或缓冲末尾） |
 * | OGNL `${…}` | 顶层 `${` | 关键字（`and/or/not/in/null`，按 `Ognl.kt` 实际支持）、`true/false` 布尔、双引号/单引号字符串、数字、`@com.Foo@bar` 静态引用、运算符、括号/方括号/花括号/逗号/点 | 深度归零的 `}`（或缓冲末尾） |
 *
 * 未闭合 `#{`/`${` 照旧扫到缓冲末尾：**不产 error token**（红线：注入着色不做校验器）；
 * 闭合 `}` 的缺失只体现为「后续内容留在子状态里」，不报错。
 *
 * ## 券 D：iBatis 定界符（T-Ibatis2Support 券 D，**新增第三类**——单趟、无子状态）
 *
 * | 形态 | token |
 * |---|---|
 * | `#name#` / `#name:TYPE#` / `#name:TYPE:nullValue#`（两段式第二段恒为 jdbcType） | 整段一个 [SqlMapperTT.PARAM_HASH] |
 * | `$name$` | 整段一个 [SqlMapperTT.PARAM_DOLLAR] |
 * | `##` / `$$`（iBatis 转义字面量，产出字面 `#`/`$`） | [SqlMapperTT.IBATIS_ESCAPE]（无色＝普通文本） |
 * | 落单 `#`/`$`（未闭合或形态非法） | 单字符 [SqlMapperTT.OPERATOR]（与放宽前一致） |
 *
 * **判定以配对闭合为准**：先在**有界窗口**（[IBATIS_PARAM_MAX_LENGTH]）内找配对的闭合定界符，
 * 找到且内容形态合法才出参数 token；找不到/非法则退回单字符运算符，**绝不**吞掉后续文本。
 * 细则与被否掉的「进子状态」形态见 [scanIbatisDelimiter]/[findIbatisParamClose] 的 KDoc。
 *
 * 与 MyBatis 分支的关系：`#{`/`${`（2 字符定界符）优先级更高，排在 iBatis 分支**之前**，
 * `#{…}`/`${…}` 的既有语义逐字节不变。
 *
 * ## 零分配与 O(n) 取向（T-PerfSweep16 F46 风格延续）
 *
 * 全程只读 [buffer] 索引，不做 `subSequence().toString()`：关键字判定用
 * `regionMatches(ignoreCase)` 直读缓冲（按词长分桶，见 [SqlMapperLanguage.KEYWORDS_BY_LENGTH]），
 * 布尔字面量与静态引用同样直读（先比长度/字符，不建子串）。单趟 O(n)：每个字符最多被一个
 * token 扫过一次（字符串/注释/参数内容都是线性推进）。
 *
 * ## 跨 token 状态与增量重扫
 *
 * 子状态必须跨 token 保存（`#{` 之后的 token 要知道自己在参数里），故本 lexer 实现
 * [RestartableLexer]：**状态值 = 该 token 起点处的状态**（`0` = SQL 顶层，`1` = `#{}` 参数规格，
 * `2+n` = `${}` OGNL 且花括号嵌套深度 n），`isRestartableState` 恒真（状态自含全部上下文，
 * 任意状态可续扫）。这是注入片段被编辑后平台增量重着色（`LexerEditorHighlighter` 从记录状态
 * 续扫）依然正确的必要条件——否则会从参数中部按 SQL 顶层重扫，把闭合 `}` 判成普通标点。
 */
class SqlMapperHighlightingLexer : LexerBase(), RestartableLexer {

    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    /** 当前 token 起点处的状态（[getState] 语义 = 「在该 token 起点续扫所需状态」）。 */
    private var state = STATE_SQL

    /** 下一个 token 起点处的状态（[advance] 扫完当前 token 时落到这里）。 */
    private var pendingState = STATE_SQL

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        tokenType = null
        state = normalizeState(initialState)
        pendingState = state
        advance()
    }

    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    /** 状态 = 当前 token 起点处的状态（见类 KDoc「跨 token 状态与增量重扫」）。 */
    override fun getState(): Int = state

    // ------------------------------------------------------------------
    // RestartableLexer（增量重扫契约）
    // ------------------------------------------------------------------

    override fun getStartState(): Int = STATE_SQL

    /** 状态自含参数上下文（0/1/2+depth），任意状态都能从对应 token 边界续扫。 */
    override fun isRestartableState(state: Int): Boolean = state >= 0

    override fun start(
        buffer: CharSequence,
        startOffset: Int,
        endOffset: Int,
        initialState: Int,
        tokenIterator: TokenIterator?,
    ) {
        // 本 lexer 的续扫只依赖状态（不依赖前序 token 序列），iterator 忽略。
        start(buffer, startOffset, endOffset, initialState)
    }

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
            tokenEnd = endOffset
            return
        }
        state = normalizeState(pendingState)
        pendingState = state
        scanToken()
    }

    private fun normalizeState(raw: Int): Int = if (raw < STATE_SQL) STATE_SQL else raw

    private fun scanToken() {
        when (state) {
            STATE_SQL -> scanSql()
            STATE_HASH_SPEC -> scanHashSpec()
            else -> scanOgnl()
        }
    }

    // ------------------------------------------------------------------
    // SQL 顶层
    // ------------------------------------------------------------------

    private fun scanSql() {
        val c = buffer[tokenStart]
        when {
            c == '-' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '-' -> scanLineComment()
            c == '/' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '*' -> scanBlockComment()
            c == '\'' || c == '"' -> scanQuotedString(c)
            (c == '#' || c == '$') && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '{' &&
                (tokenStart == 0 || buffer[tokenStart - 1] != '\\') -> {
                // R21-注：\#{ 转义形态不产参数占位（转义=非真参数，照 MyBatis TokenParser 转义语义）
                tokenEnd = tokenStart + 2
                if (c == '#') {
                    tokenType = SqlMapperTT.PARAM_HASH
                    pendingState = STATE_HASH_SPEC
                } else {
                    tokenType = SqlMapperTT.PARAM_DOLLAR
                    pendingState = STATE_OGNL_BASE
                }
            }
            // T-Ibatis2Support 券 D：iBatis 定界符（`#name#` / `$name$` / `##` / `$$`）。
            // 必须排在 `#{`/`${` 分支之后——MyBatis 形态优先级更高（`#{` 是 2 字符定界符，
            // 不被 iBatis 的单字符配对逻辑抢走）。
            c == '#' || c == '$' -> scanIbatisDelimiter(c)
            c.isDigit() -> scanNumber()
            isWordStart(c) -> scanWord()
            c.isWhitespace() -> scanWhitespace()
            else -> scanPunctuation()
        }
    }

    // ------------------------------------------------------------------
    // `#{…}` 参数规格子状态
    // ------------------------------------------------------------------

    private fun scanHashSpec() {
        val c = buffer[tokenStart]
        when {
            c == '\'' || c == '"' -> scanQuotedString(c)
            c == '}' -> {
                tokenEnd = tokenStart + 1
                tokenType = SqlMapperTT.PARAM_HASH
                pendingState = STATE_SQL
            }
            c.isDigit() -> scanNumber()
            isWordStart(c) -> scanWord()
            c.isWhitespace() -> scanWhitespace()
            else -> scanPunctuation()
        }
    }

    // ------------------------------------------------------------------
    // `${…}` OGNL 子状态
    // ------------------------------------------------------------------

    private fun scanOgnl() {
        val c = buffer[tokenStart]
        when {
            c == '\'' || c == '"' -> scanQuotedString(c)
            c == '}' -> {
                tokenEnd = tokenStart + 1
                if (state == STATE_OGNL_BASE) {
                    tokenType = SqlMapperTT.PARAM_DOLLAR
                    pendingState = STATE_SQL
                } else {
                    // `${a in {1,2}}` 的内层花括号：深度减一，留在 OGNL 子状态
                    tokenType = SqlMapperTT.BRACE
                    pendingState = state - 1
                }
            }
            c == '{' -> {
                tokenEnd = tokenStart + 1
                tokenType = SqlMapperTT.BRACE
                pendingState = state + 1
            }
            c == '@' -> scanStaticRef()
            c.isDigit() -> scanNumber()
            isWordStart(c) -> scanWord()
            c.isWhitespace() -> scanWhitespace()
            else -> scanPunctuation()
        }
    }

    /**
     * OGNL 静态引用 `@com.Foo@bar`（类引用 + 成员）整体一个 token；形态不成立时退化为
     * 单字符 [SqlMapperTT.OPERATOR]（`@` 在 OGNL 里也可能是别的东西，宁可不上色也不吞字）。
     *
     * 零分配：只做索引推进与字符判定，不建子串、不 `split`。
     */
    private fun scanStaticRef() {
        var i = tokenStart + 1
        while (i < endOffset && isClassPathChar(buffer[i])) i++
        // 类名段非空、且下一字符是分隔 `@`
        if (i > tokenStart + 1 && i < endOffset && buffer[i] == '@') {
            var j = i + 1
            while (j < endOffset && isWordPart(buffer[j])) j++
            if (j > i + 1) {
                tokenEnd = j
                tokenType = SqlMapperTT.STATIC_REF
                return
            }
        }
        tokenEnd = tokenStart + 1
        tokenType = SqlMapperTT.OPERATOR
    }

    // ------------------------------------------------------------------
    // iBatis 定界符（T-Ibatis2Support 券 D）
    // ------------------------------------------------------------------

    /**
     * iBatis 参数/转义定界符（`#name#` / `#name:TYPE#` / `#name:TYPE:nullValue#` / `$name$` /
     * 转义 `##` / `$$`）。**只在 SQL 顶层**被调用（`#{`/`${` 那两条分支在前，字符串/注释在更前）。
     *
     * ## 为什么「先向前找配对、再决定出不出参数 token」（而不是进子状态）
     *
     * 子状态形态（遇 `#` 进子状态、遇闭合 `#` 退出）在**未闭合**时会把缓冲剩余全文都吞进该状态
     * ——正是券 D 明令禁止的形态（落单 `#` / 半截注释会让后续整段 SQL 无色；平台增量重扫也
     * 无法自洽）。故本实现**先向前找配对**：
     *  - 找到且内容形态合法 → **整段（含两侧定界符）出一个 token**（`#…#` → [SqlMapperTT.PARAM_HASH]、
     *    `$…$` → [SqlMapperTT.PARAM_DOLLAR]），token 边界**就是**配对边界（配对闭合为准）；
     *  - 找不到 / 形态非法 → 退回单字符 [SqlMapperTT.OPERATOR]（与放宽前「落单 `#` 按运算符」一致），
     *    且**绝不**跨过该字符继续吞后续文本。
     *
     * 「形态合法」（见 [findIbatisParamClose]，全 O(1) 字符判定，长度有上限）：
     *  - 内容非空、首字符是词起始（字母 / `_`）——属性名或属性路径（`#{` 不走这里）；
     *  - 内容仅含词字符 / `.` / `[` / `]` / `:`（覆盖 `x.y`、`ids[]`、`list[].name`、`x:VARCHAR`、
     *    `x:VARCHAR:nullValue`；空白与换行天然不合法 ⇒ 不跨行配对）；
     *  - 配对上限 [IBATIS_PARAM_MAX_LENGTH] 字符内。
     *
     * ## `##` / `$$` 转义字面量
     *
     * 相邻同符即 iBatis 的转义（产出字面 `#` / `$`）：出 [SqlMapperTT.IBATIS_ESCAPE]（不映射颜色＝
     * 普通文本）并**消费两个字符**。这一步同时挡掉「第二个 `#` 被当参数起始」的错配——例如
     * `##foo#` 必须读作「转义 `#` + `foo#`」，**不得**读作「空参数 + `#foo#` 参数」。
     * 没有本分支时 `##` 会落到两次单字符 [SqlMapperTT.OPERATOR]，`##foo#` 还会错配出一个参数；
     * 本分支即券 D 反向验证 ② 的被摘对象（摘掉 → 字面量用例必红）。
     */
    private fun scanIbatisDelimiter(c: Char) {
        // 转义字面量 `##` / `$$`：整体一个无色文本 token（消费两字符，防第二个定界符被当起始）
        if (tokenStart + 1 < endOffset && buffer[tokenStart + 1] == c) {
            tokenEnd = tokenStart + 2
            tokenType = SqlMapperTT.IBATIS_ESCAPE
            return
        }
        val close = findIbatisParamClose(c)
        if (close < 0) {
            // 未闭合 / 形态不合法：不认参数，退回单字符运算符（不吞后续文本）
            tokenEnd = tokenStart + 1
            tokenType = SqlMapperTT.OPERATOR
            return
        }
        tokenEnd = close + 1
        tokenType = if (c == '#') SqlMapperTT.PARAM_HASH else SqlMapperTT.PARAM_DOLLAR
    }

    /**
     * 找配对的闭合定界符下标（`#` 配 `#`、`$` 配 `$`）；内容空 / 形态不合法 / 超上限 → -1。
     * 零分配（只做索引推进与字符判定，不建子串）。
     */
    private fun findIbatisParamClose(open: Char): Int {
        val contentStart = tokenStart + 1
        if (contentStart >= endOffset) return -1
        if (!isWordStart(buffer[contentStart])) return -1
        val limit = minOf(endOffset, tokenStart + IBATIS_PARAM_MAX_LENGTH)
        var i = contentStart
        while (i < limit) {
            val ch = buffer[i]
            if (ch == open) return i
            if (!isIbatisParamContentChar(ch)) return -1
            i++
        }
        return -1
    }

    /** iBatis 参数内容允许字符：词字符 / `.`（属性链）/ `[]`（iterate 下标）/ `:`（段分隔，如 jdbcType）。 */
    private fun isIbatisParamContentChar(c: Char): Boolean =
        isWordPart(c) || c == '.' || c == '[' || c == ']' || c == ':'

    // ------------------------------------------------------------------
    // 共用扫描
    // ------------------------------------------------------------------

    private fun scanLineComment() {
        var i = tokenStart + 2
        while (i < endOffset && buffer[i] != '\n') i++
        tokenEnd = i
        tokenType = SqlMapperTT.COMMENT
    }

    private fun scanBlockComment() {
        var i = tokenStart + 2
        while (i + 1 < endOffset && !(buffer[i] == '*' && buffer[i + 1] == '/')) i++
        tokenEnd = minOf(endOffset, i + 2)
        tokenType = SqlMapperTT.COMMENT
    }

    /** `'…'` / `"…"` 字符串：`''` 加倍不截断；单引号态下 `\` 转义跳过下一字符（既有语义不变）。 */
    private fun scanQuotedString(quote: Char) {
        var i = tokenStart + 1
        while (i < endOffset) {
            if (buffer[i] == '\\' && quote == '\'') i += 2
            else if (buffer[i] == quote) {
                if (i + 1 < endOffset && buffer[i + 1] == quote) i += 2 else break
            } else i++
        }
        tokenEnd = minOf(endOffset, i + 1)
        tokenType = SqlMapperTT.STRING
    }

    private fun scanNumber() {
        var i = tokenStart
        while (i < endOffset && (buffer[i].isDigit() || buffer[i] == '.')) i++
        tokenEnd = i
        tokenType = SqlMapperTT.NUMBER
    }

    private fun scanWhitespace() {
        var i = tokenStart
        while (i < endOffset && buffer[i].isWhitespace()) i++
        tokenEnd = i
        tokenType = SqlMapperTT.WHITE_SPACE
    }

    private fun scanWord() {
        var i = tokenStart
        while (i < endOffset && isWordPart(buffer[i])) i++
        tokenEnd = i
        tokenType = classifyWord(tokenStart, i)
    }

    /** 标点族：括号/方括号/花括号/逗号/点各立 token（平台语义槽位各自接色），其余按运算符。 */
    private fun scanPunctuation() {
        when (val c = buffer[tokenStart]) {
            '(', ')' -> {
                tokenEnd = tokenStart + 1
                tokenType = SqlMapperTT.PAREN
            }
            '[', ']' -> {
                tokenEnd = tokenStart + 1
                tokenType = SqlMapperTT.BRACKET
            }
            '{', '}' -> {
                tokenEnd = tokenStart + 1
                tokenType = SqlMapperTT.BRACE
            }
            ',' -> {
                tokenEnd = tokenStart + 1
                tokenType = SqlMapperTT.COMMA
            }
            '.' -> {
                tokenEnd = tokenStart + 1
                tokenType = SqlMapperTT.DOT
            }
            else -> {
                tokenEnd = if (c == '!' || c == '<' || c == '>' || c == '|' || c == '&') {
                    if (isTwoCharOperator(c)) tokenStart + 2 else tokenStart + 1
                } else {
                    tokenStart + 1
                }
                tokenType = SqlMapperTT.OPERATOR
            }
        }
    }

    private fun isTwoCharOperator(first: Char): Boolean {
        if (tokenStart + 1 >= endOffset) return false
        val second = buffer[tokenStart + 1]
        return when (first) {
            '!' -> second == '='
            '<' -> second == '=' || second == '>'
            '>' -> second == '='
            '|', '&' -> second == first
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 词分类
    // ------------------------------------------------------------------

    /**
     * 词分类：`true`/`false` → [SqlMapperTT.BOOLEAN]（先比长度再 regionMatches，零分配）；
     * 其余按**所处子状态**分用不同词集：
     *  - `#{…}` 参数规格：**不判关键字**，一切词按标识符——那里的词是参数名/属性名/属性值
     *    （W3 #4 口径「参数名 identifier、`,`、`=`、属性名、属性值」）；若在此处套用方言并集，
     *    高频参数名 `#{size}`/`#{key}`/`#{value}`/`#{list}` 会被 Oracle/H2 保留字误着关键字色。
     *  - `${…}` OGNL：用 [SqlMapperLanguage.OGNL_KEYWORDS]（`Ognl.kt` 实际支持的词，见其 KDoc）。
     *  - SQL 顶层（语句文本 / 属性值宿主）：用全量 [SqlMapperLanguage.KEYWORDS]（含方言超集）。
     *
     * 例外（W3 #8 的 `page.size` 判别位）：**紧跟 `.` 之后的词一律按标识符**——它是属性/成员名
     * （`page.size`、`t.order`、`x.value`），不是裸用的关键字。这条同时挡掉方言并集在 SQL 顶层的
     * 过度着色：`size`/`value`/`key`/`row`/`top` 等在 Oracle/H2 等方言里是保留字，裸用该包引号
     * （着色正确），但作为限定名尾段时是标识符（`t.size` 不可能被当关键字）。判据只看前一个字符，
     * 零分配。
     */
    private fun classifyWord(start: Int, end: Int): IElementType {
        if (start > 0 && buffer[start - 1] == '.') return SqlMapperTT.IDENTIFIER
        val len = end - start
        if (len == LEN_TRUE && buffer.regionMatches(start, TRUE_WORD, 0, LEN_TRUE, ignoreCase = true)) {
            return SqlMapperTT.BOOLEAN
        }
        if (len == LEN_FALSE && buffer.regionMatches(start, FALSE_WORD, 0, LEN_FALSE, ignoreCase = true)) {
            return SqlMapperTT.BOOLEAN
        }
        return if (matchesKeyword(start, len)) SqlMapperTT.KEYWORD else SqlMapperTT.IDENTIFIER
    }

    private fun matchesKeyword(start: Int, len: Int): Boolean = when {
        state >= STATE_OGNL_BASE -> SqlMapperLanguage.OGNL_KEYWORDS.any {
            it.length == len && buffer.regionMatches(start, it, 0, len, ignoreCase = true)
        }
        // `#{…}` 内不判关键字（见 classifyWord KDoc）
        state == STATE_HASH_SPEC -> false
        else -> SqlMapperLanguage.KEYWORDS_BY_LENGTH[len]?.any {
            buffer.regionMatches(start, it, 0, len, ignoreCase = true)
        } ?: false
    }

    private fun isWordStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isWordPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** `@` 与成员名之间的类路径字符（包名点 + 内部类 `$`）。 */
    private fun isClassPathChar(c: Char): Boolean = isWordPart(c) || c == '.' || c == '$'

    companion object {
        /** 状态：SQL 顶层。 */
        private const val STATE_SQL = 0

        /** 状态：`#{…}` 参数规格子状态。 */
        private const val STATE_HASH_SPEC = 1

        /** 状态：`${…}` OGNL 子状态、花括号嵌套深度 0。深度 d → `STATE_OGNL_BASE + d`。 */
        private const val STATE_OGNL_BASE = 2

        private const val TRUE_WORD = "true"
        private const val FALSE_WORD = "false"
        private const val LEN_TRUE = 4
        private const val LEN_FALSE = 5

        /**
         * iBatis 参数 token 的最大长度（含两侧定界符）。超限即判「不是参数占位」——防止在正文里
         * 找配对 `#` 时跨过大段 SQL 误配（iBatis 属性路径远短于此；也让向前找配对的开销有硬上限）。
         */
        private const val IBATIS_PARAM_MAX_LENGTH = 256
    }
}
