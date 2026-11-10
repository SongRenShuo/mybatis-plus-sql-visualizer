package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.tree.IElementType

/**
 * 注入语言的 token 类型（lexer-only，无 PSI 元素）。
 *
 * T-XmlGap W2/W3 扩面（对照内部调研报告 §4.1/§5.1 的「分色缺失 / 粒度差一档」行）：
 *
 * W2 —— `#{}` 与 `${}` 分色：原单一 `PARAM`（`#{}` 与 `${}` 同一 token 同色）拆为
 * [PARAM_HASH]（`#{…}` 定界符）与 [PARAM_DOLLAR]（`${…}` 定界符）两个 token，各持一个
 * TextAttributesKey（见 [SqlMapperSyntaxHighlighter]）。**`PARAM` 已删除且不留别名**——
 * 半吊子别名会让「谁还在用旧 token」不可判。
 *
 * W3 —— 表达式细着色 + 覆盖面：
 *  - [BOOLEAN]（OGNL/SQL 的 `true`/`false` 字面量）与 [STATIC_REF]（OGNL `@com.Foo@bar`
 *    形态的类引用与成员）此前不存在，都塌在整段 PARAM 里；
 *  - [PAREN] / [BRACKET] / [BRACE] / [COMMA] / [DOT] 标点族：此前全部塌进 [OPERATOR]
 *    （`(`、`)`、`,`、`.` 与 `=`、`<` 同 token）→ 平台 `PARENTHESES`/`BRACKETS`/`BRACES`/
 *    `COMMA`/`DOT` 四个语义槽位无法接上。
 *
 * [IDENTIFIER] / [OPERATOR] 的语义不变，只是高亮器不再把两者映射为空（W3 #6：表名/列名/运算符
 * 此前与普通文本同色）。
 *
 * T-Ibatis2Support 券 D —— iBatis 定界符：`#name#` / `#name:TYPE#` / `#name:TYPE:nullValue#` 复用
 * [PARAM_HASH]、`$name$` 复用 [PARAM_DOLLAR]（「整段一个 token」，见 `SqlMapperHighlightingLexer`
 * 的券 D 段）；`##` / `$$` 转义字面量新增 [IBATIS_ESCAPE]。
 */
object SqlMapperTT {
    @JvmField val KEYWORD = IElementType("MPSQL_KEYWORD", SqlMapperLanguage)
    @JvmField val IDENTIFIER = IElementType("MPSQL_IDENTIFIER", SqlMapperLanguage)
    @JvmField val STRING = IElementType("MPSQL_STRING", SqlMapperLanguage)
    @JvmField val NUMBER = IElementType("MPSQL_NUMBER", SqlMapperLanguage)
    @JvmField val COMMENT = IElementType("MPSQL_COMMENT", SqlMapperLanguage)

    /** 真运算符（`=`、`<`、`!=`、`||` 等）；括号/逗号/点已另立 token（见下）。 */
    @JvmField val OPERATOR = IElementType("MPSQL_OPERATOR", SqlMapperLanguage)

    /** W3 标点族：`(` `)` → 平台 `PARENTHESES`。 */
    @JvmField val PAREN = IElementType("MPSQL_PAREN", SqlMapperLanguage)

    /** W3 标点族：`[` `]` → 平台 `BRACKETS`。 */
    @JvmField val BRACKET = IElementType("MPSQL_BRACKET", SqlMapperLanguage)

    /** W3 标点族：`{` `}` → 平台 `BRACES`（也用于 `${}` 内 OGNL 列表字面量的花括号）。 */
    @JvmField val BRACE = IElementType("MPSQL_BRACE", SqlMapperLanguage)

    /** W3 标点族：`,` → 平台 `COMMA`（`#{}` 参数规格的分隔符也是它）。 */
    @JvmField val COMMA = IElementType("MPSQL_COMMA", SqlMapperLanguage)

    /** W3 标点族：`.` → 平台 `DOT`（`page.size` 的属性链点）。 */
    @JvmField val DOT = IElementType("MPSQL_DOT", SqlMapperLanguage)

    /** W2：`#{…}` 的定界符 `#{`（2 字符）与配对 `}`（1 字符）；内容另出细 token。 */
    @JvmField val PARAM_HASH = IElementType("MPSQL_PARAM_HASH", SqlMapperLanguage)

    /** W2：`${…}` 的定界符 `${`（2 字符）与配对 `}`（1 字符）；内容另出细 token。 */
    @JvmField val PARAM_DOLLAR = IElementType("MPSQL_PARAM_DOLLAR", SqlMapperLanguage)

    /** W3：`true` / `false` 字面量（大小写不敏感命中；`null` 归 [KEYWORD]，与 Java 关键字口径一致）。 */
    @JvmField val BOOLEAN = IElementType("MPSQL_BOOLEAN", SqlMapperLanguage)

    /** W3：OGNL 静态引用 `@com.Foo@bar`（类引用 + 成员）整体一个 token。 */
    @JvmField val STATIC_REF = IElementType("MPSQL_STATIC_REF", SqlMapperLanguage)

    /**
     * T-Ibatis2Support 券 D：iBatis 转义字面量 `##` / `$$`（iBatis 规范：`##` 产出字面 `#`、
     * `$$` 产出字面 `$`）。**按普通文本处理**——[SqlMapperSyntaxHighlighter] 对它返回空键表，
     * 故渲染为无色的普通文本（这正是「不是参数占位」的可观测形态），也不进颜色设置页。
     */
    @JvmField val IBATIS_ESCAPE = IElementType("MPSQL_IBATIS_ESCAPE", SqlMapperLanguage)

    @JvmField val WHITE_SPACE = IElementType("MPSQL_WHITE_SPACE", SqlMapperLanguage)
}
