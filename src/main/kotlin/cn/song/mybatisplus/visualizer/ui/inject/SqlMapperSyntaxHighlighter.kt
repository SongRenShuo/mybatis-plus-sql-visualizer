package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * 注入语言高亮器（lexer-only）。颜色一律**借平台语义键**（主题跟随，不自管颜色、不挂
 * colorSettingsPage——颜色页是 T-XmlGap 的另一波次 W6）。
 *
 * T-XmlGap W2/W3 变化：
 *  - W2 [SqlMapperTT.PARAM_HASH] / [SqlMapperTT.PARAM_DOLLAR] 各持一个键（不再是同一个
 *    `PARAM` 键）：默认主题下两者**必须肉眼可分**（[PARAM_HASH] 落 `KEYWORD`、[PARAM_DOLLAR]
 *    落 `INSTANCE_FIELD`，见各自 KDoc 的取舍理由）；
 *  - W3 覆盖面：`IDENTIFIER` / `OPERATOR` / 标点族此前**完全不映射**（表名、列名、运算符、
 *    括号与普通文本同色），现按平台语义槽位接线；`BOOLEAN` / `STATIC_REF` / 标点族为新增 token。
 *
 * ## W2 键表取舍（计划 §六 W2 行的「参考取向」三句互相冲突时的落地口径）
 *
 * 参考取向原文要求「`#{`/`}` 与 `${`/`}` 的定界符都用 KEYWORD」＋「`#{}` 整体沿用
 * `TEMPLATE_LANGUAGE_COLOR` 淡底色」＋「`${}` 用自有键、其 fallback 与淡底明显不同」。
 * 前两句与第三句**不可同时成立**（两者都 KEYWORD 就不可分；`#{}` 淡底与 `#{`/`}` 用 KEYWORD
 * 也互斥——一个 token 只能落一组属性）。落地取「分色优先」（W2 的验收判据是「`#{}` 与 `${}`
 * 显示不同色」，且计划 §一 把「`#{}` 与 `${}` 同 token 同色」列为差距主因）：
 *
 *  - **[PARAM_HASH] → `KEYWORD`**：与竞品同构（内部调研报告 §2.1：竞品 Param 语言把 `#{`/`}`
 *    当 KEYWORD 着色），也是本插件 SQL 关键字既有观感；
 *  - **[PARAM_DOLLAR] → `INSTANCE_FIELD`**：`${}` 是**裸字符串替换**（值来自参数对象的属性/字段
 *    名，MyBatis 里直接拼进 SQL 文本），语义上最接近「实例字段」；两套默认主题下都与 `KEYWORD`
 *    明显不同色相（Light：`#660E7A` 粗体紫 vs `#000080` 粗体深蓝；Dark：`#9876AA` 紫 vs
 *    `#CC7832` 橙）——比「再用一次淡底」更强地提示「这个占位符与 `#{}` 不是一回事」。
 *  - 代价（如实登记）：原 `#{…}` 整体的 `TEMPLATE_LANGUAGE_COLOR` 淡底不再用于 `${}`，
 *    `#{…}` 的观感也从「整段淡底」变为「定界符关键字色 + 内容细 token 色」。
 *
 * ## 其他键位理由
 *
 *  - [BOOLEAN]（`true`/`false`）→ 复用 `KEYWORD`：平台主流语言（Java/JS/PHP）都把布尔字面量
 *    当关键字上色；而默认主题里语义上更贴近「字面量」的槽位（`PREDEFINED_SYMBOL`/`CONSTANT`）
 *    在 Darcula 下**未定义**（回落到普通文本），会变成「无色」而非「另一种色」。故与关键字同色
 *    更稳；token 类型已分开，将来 W6 颜色页可再拆。
 *  - [STATIC_REF] → `STATIC_FIELD`：语义精确对上 OGNL `@com.Foo@bar`（静态成员访问）。
 *  - 标点族 → `PARENTHESES`/`BRACKETS`/`BRACES`/`COMMA`/`DOT`；运算符 → `OPERATION_SIGN`；
 *    标识符 → `IDENTIFIER`。
 */
class SqlMapperSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = SqlMapperHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        SqlMapperTT.KEYWORD -> KEYWORD_KEYS
        SqlMapperTT.BOOLEAN -> KEYWORD_KEYS
        SqlMapperTT.IDENTIFIER -> IDENTIFIER_KEYS
        SqlMapperTT.STRING -> STRING_KEYS
        SqlMapperTT.NUMBER -> NUMBER_KEYS
        SqlMapperTT.COMMENT -> COMMENT_KEYS
        SqlMapperTT.OPERATOR -> OPERATOR_KEYS
        SqlMapperTT.PAREN -> PAREN_KEYS
        SqlMapperTT.BRACKET -> BRACKET_KEYS
        SqlMapperTT.BRACE -> BRACE_KEYS
        SqlMapperTT.COMMA -> COMMA_KEYS
        SqlMapperTT.DOT -> DOT_KEYS
        SqlMapperTT.PARAM_HASH -> PARAM_HASH_KEYS
        SqlMapperTT.PARAM_DOLLAR -> PARAM_DOLLAR_KEYS
        SqlMapperTT.STATIC_REF -> STATIC_REF_KEYS
        // 券 D：iBatis 转义字面量 `##`/`$$` **有意不配色**——语义就是「字面 `#`/`$`」，
        // 渲染为普通文本才是正确观感（给它任一颜色都会读成「某种占位符」）。
        SqlMapperTT.IBATIS_ESCAPE -> EMPTY_KEYS
        else -> EMPTY_KEYS
    }

    companion object {
        private fun key(id: String, fallback: TextAttributesKey): TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(id, fallback)

        val KEYWORD: TextAttributesKey = key("MPSQL_KEYWORD_DEFAULT", DefaultLanguageHighlighterColors.KEYWORD)
        val IDENTIFIER: TextAttributesKey = key("MPSQL_IDENTIFIER_DEFAULT", DefaultLanguageHighlighterColors.IDENTIFIER)
        val STRING: TextAttributesKey = key("MPSQL_STRING_DEFAULT", DefaultLanguageHighlighterColors.STRING)
        val COMMENT: TextAttributesKey = key("MPSQL_COMMENT_DEFAULT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val NUMBER: TextAttributesKey = key("MPSQL_NUMBER_DEFAULT", DefaultLanguageHighlighterColors.NUMBER)
        val OPERATOR: TextAttributesKey = key("MPSQL_OPERATOR_DEFAULT", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val PAREN: TextAttributesKey = key("MPSQL_PAREN_DEFAULT", DefaultLanguageHighlighterColors.PARENTHESES)
        val BRACKET: TextAttributesKey = key("MPSQL_BRACKET_DEFAULT", DefaultLanguageHighlighterColors.BRACKETS)
        val BRACE: TextAttributesKey = key("MPSQL_BRACE_DEFAULT", DefaultLanguageHighlighterColors.BRACES)
        val COMMA: TextAttributesKey = key("MPSQL_COMMA_DEFAULT", DefaultLanguageHighlighterColors.COMMA)
        val DOT: TextAttributesKey = key("MPSQL_DOT_DEFAULT", DefaultLanguageHighlighterColors.DOT)
        val STATIC_REF: TextAttributesKey = key("MPSQL_STATIC_REF_DEFAULT", DefaultLanguageHighlighterColors.STATIC_FIELD)

        /** W2：`#{…}` 定界符的语义槽位（与竞品同构的 KEYWORD 色）。 */
        val PARAM_HASH_FALLBACK: TextAttributesKey = DefaultLanguageHighlighterColors.KEYWORD

        /** W2：`${…}` 定界符的语义槽位（与 [PARAM_HASH_FALLBACK] 明显不同，理由见类 KDoc）。 */
        val PARAM_DOLLAR_FALLBACK: TextAttributesKey = DefaultLanguageHighlighterColors.INSTANCE_FIELD

        /** W2：`#{…}` 定界符（与竞品同构的 KEYWORD 色）。 */
        val PARAM_HASH: TextAttributesKey = key("MPSQL_PARAM_HASH_DEFAULT", PARAM_HASH_FALLBACK)

        /** W2：`${…}` 定界符（自有键；与 [PARAM_HASH] 明显不同，见类 KDoc）。 */
        val PARAM_DOLLAR: TextAttributesKey = key("MPSQL_PARAM_DOLLAR_DEFAULT", PARAM_DOLLAR_FALLBACK)

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val STRING_KEYS = arrayOf(STRING)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val PAREN_KEYS = arrayOf(PAREN)
        private val BRACKET_KEYS = arrayOf(BRACKET)
        private val BRACE_KEYS = arrayOf(BRACE)
        private val COMMA_KEYS = arrayOf(COMMA)
        private val DOT_KEYS = arrayOf(DOT)
        private val STATIC_REF_KEYS = arrayOf(STATIC_REF)
        private val PARAM_HASH_KEYS = arrayOf(PARAM_HASH)
        private val PARAM_DOLLAR_KEYS = arrayOf(PARAM_DOLLAR)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}
