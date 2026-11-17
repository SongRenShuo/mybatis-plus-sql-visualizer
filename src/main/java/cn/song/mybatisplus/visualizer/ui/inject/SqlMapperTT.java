// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperTT.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.psi.tree.IElementType;

/**
 * 注入语言的 token 类型（lexer-only，无 PSI 元素）。
 *
 * <p>T-XmlGap W2/W3 扩面（对照内部调研报告 §4.1/§5.1 的「分色缺失 / 粒度差一档」行）：</p>
 *
 * <p>W2 —— {@code #{}} 与 {@code ${}} 分色：原单一 {@code PARAM}（{@code #{}} 与 {@code ${}}
 * 同一 token 同色）拆为 {@link #PARAM_HASH}（{@code #{…}} 定界符）与 {@link #PARAM_DOLLAR}
 * （{@code ${…}} 定界符）两个 token，各持一个 TextAttributesKey（见 SqlMapperSyntaxHighlighter）。
 * <b>{@code PARAM} 已删除且不留别名</b>——半吊子别名会让「谁还在用旧 token」不可判。</p>
 *
 * <p>W3 —— 表达式细着色 + 覆盖面：</p>
 * <ul>
 *   <li>{@link #BOOLEAN}（OGNL/SQL 的 {@code true}/{@code false} 字面量）与 {@link #STATIC_REF}
 *       （OGNL {@code @com.Foo@bar} 形态的类引用与成员）此前不存在，都塌在整段 PARAM 里；</li>
 *   <li>{@link #PAREN} / {@link #BRACKET} / {@link #BRACE} / {@link #COMMA} / {@link #DOT} 标点族：
 *       此前全部塌进 {@link #OPERATOR}（{@code (}、{@code )}、{@code ,}、{@code .} 与
 *       {@code =}、{@code <} 同 token）→ 平台 {@code PARENTHESES}/{@code BRACKETS}/{@code BRACES}/
 *       {@code COMMA}/{@code DOT} 四个语义槽位无法接上。</li>
 * </ul>
 *
 * <p>{@link #IDENTIFIER} / {@link #OPERATOR} 的语义不变，只是高亮器不再把两者映射为空
 * （W3 #6：表名/列名/运算符此前与普通文本同色）。</p>
 */
public final class SqlMapperTT {

    private SqlMapperTT() {
    }

    public static final IElementType KEYWORD = new IElementType("MPSQL_KEYWORD", SqlMapperLanguage.INSTANCE);
    public static final IElementType IDENTIFIER = new IElementType("MPSQL_IDENTIFIER", SqlMapperLanguage.INSTANCE);
    public static final IElementType STRING = new IElementType("MPSQL_STRING", SqlMapperLanguage.INSTANCE);
    public static final IElementType NUMBER = new IElementType("MPSQL_NUMBER", SqlMapperLanguage.INSTANCE);
    public static final IElementType COMMENT = new IElementType("MPSQL_COMMENT", SqlMapperLanguage.INSTANCE);

    /** 真运算符（{@code =}、{@code <}、{@code !=}、{@code ||} 等）；括号/逗号/点已另立 token（见下）。 */
    public static final IElementType OPERATOR = new IElementType("MPSQL_OPERATOR", SqlMapperLanguage.INSTANCE);

    /** W3 标点族：{@code (} {@code )} → 平台 {@code PARENTHESES}。 */
    public static final IElementType PAREN = new IElementType("MPSQL_PAREN", SqlMapperLanguage.INSTANCE);

    /** W3 标点族：{@code [} {@code ]} → 平台 {@code BRACKETS}。 */
    public static final IElementType BRACKET = new IElementType("MPSQL_BRACKET", SqlMapperLanguage.INSTANCE);

    /** W3 标点族："{" 与 "}" → 平台 {@code BRACES}（也用于 {@code ${…}} 内 OGNL 列表字面量的花括号）。 */
    public static final IElementType BRACE = new IElementType("MPSQL_BRACE", SqlMapperLanguage.INSTANCE);

    /** W3 标点族：{@code ,} → 平台 {@code COMMA}（{@code #{}} 参数规格的分隔符也是它）。 */
    public static final IElementType COMMA = new IElementType("MPSQL_COMMA", SqlMapperLanguage.INSTANCE);

    /** W3 标点族：{@code .} → 平台 {@code DOT}（{@code page.size} 的属性链点）。 */
    public static final IElementType DOT = new IElementType("MPSQL_DOT", SqlMapperLanguage.INSTANCE);

    /** W2：{@code #{…}} 的定界符 "#{"（2 字符）与配对 "}"（1 字符）；内容另出细 token。 */
    public static final IElementType PARAM_HASH = new IElementType("MPSQL_PARAM_HASH", SqlMapperLanguage.INSTANCE);

    /** W2：{@code ${…}} 的定界符 "${"（2 字符）与配对 "}"（1 字符）；内容另出细 token。 */
    public static final IElementType PARAM_DOLLAR = new IElementType("MPSQL_PARAM_DOLLAR", SqlMapperLanguage.INSTANCE);

    /** W3：{@code true} / {@code false} 字面量（大小写不敏感命中；{@code null} 归 KEYWORD，与 Java 关键字口径一致）。 */
    public static final IElementType BOOLEAN = new IElementType("MPSQL_BOOLEAN", SqlMapperLanguage.INSTANCE);

    /** W3：OGNL 静态引用 {@code @com.Foo@bar}（类引用 + 成员）整体一个 token。 */
    public static final IElementType STATIC_REF = new IElementType("MPSQL_STATIC_REF", SqlMapperLanguage.INSTANCE);

    public static final IElementType WHITE_SPACE = new IElementType("MPSQL_WHITE_SPACE", SqlMapperLanguage.INSTANCE);
}
