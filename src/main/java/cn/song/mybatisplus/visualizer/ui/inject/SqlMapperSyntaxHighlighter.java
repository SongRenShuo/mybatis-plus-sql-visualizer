// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperSyntaxHighlighter.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;

/**
 * 注入语言高亮器（lexer-only）。颜色一律<b>借平台语义键</b>（主题跟随，不自管颜色——
 * colorSettingsPage 是 T-XmlGap 的另一波次 W6，键 id 已就位）。
 *
 * <p>T-XmlGap W2/W3 变化：</p>
 * <ul>
 *   <li>W2 {@code SqlMapperTT.PARAM_HASH} / {@code SqlMapperTT.PARAM_DOLLAR} 各持一个键（不再是同一个
 *       {@code PARAM} 键）：默认主题下两者<b>必须肉眼可分</b>（PARAM_HASH 落 {@code KEYWORD}、
 *       PARAM_DOLLAR 落 {@code INSTANCE_FIELD}，见各自 KDoc 的取舍理由）；</li>
 *   <li>W3 覆盖面：{@code IDENTIFIER} / {@code OPERATOR} / 标点族此前<b>完全不映射</b>（表名、列名、
 *       运算符、括号与普通文本同色），现按平台语义槽位接线；{@code BOOLEAN} / {@code STATIC_REF} /
 *       标点族为新增 token。</li>
 * </ul>
 *
 * <h2>W2 键表取舍（计划 §六 W2 行的「参考取向」三句互相冲突时的落地口径）</h2>
 *
 * <p>参考取向原文要求「"#{"/"}" 与 "${"/"}" 的定界符都用 KEYWORD」＋「{@code #{}} 整体沿用
 * {@code TEMPLATE_LANGUAGE_COLOR} 淡底色」＋「{@code ${}} 用自有键、其 fallback 与淡底明显不同」。
 * 前两句与第三句<b>不可同时成立</b>（两者都 KEYWORD 就不可分；{@code #{}} 淡底与 "#{" / "}" 用
 * KEYWORD 也互斥——一个 token 只能落一组属性）。落地取「分色优先」（W2 的验收判据是
 * 「{@code #{}} 与 {@code ${}} 显示不同色」，且计划 §一 把「{@code #{}} 与 {@code ${}} 同 token 同色」
 * 列为差距主因）：</p>
 * <ul>
 *   <li><b>PARAM_HASH → {@code KEYWORD}</b>：与竞品同构（内部调研报告 §2.1：竞品 Param 语言把
 *       "#{"/"}" 当 KEYWORD 着色），也是本插件 SQL 关键字既有观感；</li>
 *   <li><b>PARAM_DOLLAR → {@code INSTANCE_FIELD}</b>：{@code ${}} 是<b>裸字符串替换</b>（值来自参数
 *       对象的属性/字段名，MyBatis 里直接拼进 SQL 文本），语义上最接近「实例字段」；两套默认主题下
 *       都与 {@code KEYWORD} 明显不同色相（Light：#660E7A 粗体紫 vs #000080 粗体深蓝；Dark：
 *       #9876AA 紫 vs #CC7832 橙）——比「再用一次淡底」更强地提示「这个占位符与 {@code #{}} 不是一回事」。</li>
 *   <li>代价（如实登记）：原 {@code #{…}} 整体的 {@code TEMPLATE_LANGUAGE_COLOR} 淡底不再用于
 *       {@code ${}}，{@code #{…}} 的观感也从「整段淡底」变为「定界符关键字色 + 内容细 token 色」。</li>
 * </ul>
 *
 * <h2>其他键位理由</h2>
 * <ul>
 *   <li>BOOLEAN（{@code true}/{@code false}）→ 复用 {@code KEYWORD}：平台主流语言（Java/JS/PHP）都把
 *       布尔字面量当关键字上色；而默认主题里语义上更贴近「字面量」的槽位（{@code PREDEFINED_SYMBOL}/
 *       {@code CONSTANT}）在 Darcula 下<b>未定义</b>（回落到普通文本），会变成「无色」而非「另一种色」。
 *       故与关键字同色更稳；token 类型已分开，将来 W6 颜色页可再拆。</li>
 *   <li>STATIC_REF → {@code STATIC_FIELD}：语义精确对上 OGNL {@code @com.Foo@bar}（静态成员访问）。</li>
 *   <li>标点族 → {@code PARENTHESES}/{@code BRACKETS}/{@code BRACES}/{@code COMMA}/{@code DOT}；
 *       运算符 → {@code OPERATION_SIGN}；标识符 → {@code IDENTIFIER}。</li>
 * </ul>
 */
public class SqlMapperSyntaxHighlighter extends SyntaxHighlighterBase {

    @Override
    public Lexer getHighlightingLexer() {
        return new SqlMapperHighlightingLexer();
    }

    @Override
    public TextAttributesKey[] getTokenHighlights(@Nullable IElementType tokenType) {
        if (tokenType == SqlMapperTT.KEYWORD) {
            return KEYWORD_KEYS;
        } else if (tokenType == SqlMapperTT.BOOLEAN) {
            return KEYWORD_KEYS;
        } else if (tokenType == SqlMapperTT.IDENTIFIER) {
            return IDENTIFIER_KEYS;
        } else if (tokenType == SqlMapperTT.STRING) {
            return STRING_KEYS;
        } else if (tokenType == SqlMapperTT.NUMBER) {
            return NUMBER_KEYS;
        } else if (tokenType == SqlMapperTT.COMMENT) {
            return COMMENT_KEYS;
        } else if (tokenType == SqlMapperTT.OPERATOR) {
            return OPERATOR_KEYS;
        } else if (tokenType == SqlMapperTT.PAREN) {
            return PAREN_KEYS;
        } else if (tokenType == SqlMapperTT.BRACKET) {
            return BRACKET_KEYS;
        } else if (tokenType == SqlMapperTT.BRACE) {
            return BRACE_KEYS;
        } else if (tokenType == SqlMapperTT.COMMA) {
            return COMMA_KEYS;
        } else if (tokenType == SqlMapperTT.DOT) {
            return DOT_KEYS;
        } else if (tokenType == SqlMapperTT.PARAM_HASH) {
            return PARAM_HASH_KEYS;
        } else if (tokenType == SqlMapperTT.PARAM_DOLLAR) {
            return PARAM_DOLLAR_KEYS;
        } else if (tokenType == SqlMapperTT.STATIC_REF) {
            return STATIC_REF_KEYS;
        } else {
            return EMPTY_KEYS;
        }
    }

    // ---- 键表（借平台语义键，主题跟随；W6 颜色页按这些键 id 暴露 14 档可调）----

    private static TextAttributesKey key(String id, TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey(id, fallback);
    }

    public static final TextAttributesKey KEYWORD = key("MPSQL_KEYWORD_DEFAULT", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey IDENTIFIER = key("MPSQL_IDENTIFIER_DEFAULT", DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey STRING = key("MPSQL_STRING_DEFAULT", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey COMMENT = key("MPSQL_COMMENT_DEFAULT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey NUMBER = key("MPSQL_NUMBER_DEFAULT", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey OPERATOR = key("MPSQL_OPERATOR_DEFAULT", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey PAREN = key("MPSQL_PAREN_DEFAULT", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey BRACKET = key("MPSQL_BRACKET_DEFAULT", DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey BRACE = key("MPSQL_BRACE_DEFAULT", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey COMMA = key("MPSQL_COMMA_DEFAULT", DefaultLanguageHighlighterColors.COMMA);
    public static final TextAttributesKey DOT = key("MPSQL_DOT_DEFAULT", DefaultLanguageHighlighterColors.DOT);
    public static final TextAttributesKey STATIC_REF = key("MPSQL_STATIC_REF_DEFAULT", DefaultLanguageHighlighterColors.STATIC_FIELD);

    /** W2：{@code #{…}} 定界符的语义槽位（与竞品同构的 KEYWORD 色）。 */
    public static final TextAttributesKey PARAM_HASH_FALLBACK = DefaultLanguageHighlighterColors.KEYWORD;

    /** W2：{@code ${…}} 定界符的语义槽位（与 PARAM_HASH_FALLBACK 明显不同，理由见类 KDoc）。 */
    public static final TextAttributesKey PARAM_DOLLAR_FALLBACK = DefaultLanguageHighlighterColors.INSTANCE_FIELD;

    /** W2：{@code #{…}} 定界符（与竞品同构的 KEYWORD 色）。 */
    public static final TextAttributesKey PARAM_HASH = key("MPSQL_PARAM_HASH_DEFAULT", PARAM_HASH_FALLBACK);

    /** W2：{@code ${…}} 定界符（自有键；与 PARAM_HASH 明显不同，见类 KDoc）。 */
    public static final TextAttributesKey PARAM_DOLLAR = key("MPSQL_PARAM_DOLLAR_DEFAULT", PARAM_DOLLAR_FALLBACK);

    private static final TextAttributesKey[] KEYWORD_KEYS = {KEYWORD};
    private static final TextAttributesKey[] IDENTIFIER_KEYS = {IDENTIFIER};
    private static final TextAttributesKey[] STRING_KEYS = {STRING};
    private static final TextAttributesKey[] COMMENT_KEYS = {COMMENT};
    private static final TextAttributesKey[] NUMBER_KEYS = {NUMBER};
    private static final TextAttributesKey[] OPERATOR_KEYS = {OPERATOR};
    private static final TextAttributesKey[] PAREN_KEYS = {PAREN};
    private static final TextAttributesKey[] BRACKET_KEYS = {BRACKET};
    private static final TextAttributesKey[] BRACE_KEYS = {BRACE};
    private static final TextAttributesKey[] COMMA_KEYS = {COMMA};
    private static final TextAttributesKey[] DOT_KEYS = {DOT};
    private static final TextAttributesKey[] STATIC_REF_KEYS = {STATIC_REF};
    private static final TextAttributesKey[] PARAM_HASH_KEYS = {PARAM_HASH};
    private static final TextAttributesKey[] PARAM_DOLLAR_KEYS = {PARAM_DOLLAR};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];
}
