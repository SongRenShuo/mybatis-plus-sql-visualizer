package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 轻量 SQL 词法器（M8-01）。
 *
 * 仅为本插件产出的 SQL 做语法高亮分词，不追求通用 SQL 方言：
 *  - 关键字 / 标识符 / 表名 / 字符串 / 数字 / 注释 / 运算符 / 括号
 *  - 表名启发式：`FROM` / `JOIN` / `INTO` / `UPDATE` 后的标识符（及紧随的表别名）
 *    标记为 {@link SqlTokenTypes#TABLE}，对齐参考样式中"表名/别名"与"列名"分色。
 *
 * 原生 SQL 高亮由平台 SqlSyntaxHighlighterFactory 提供；SqlLexer 仅供 {@link SqlFormatter} 格式化与 HTML 渲染使用。
 */
public class SqlLexer extends LexerBase {

    private CharSequence myBuffer = "";
    private int myEnd = 0;
    private int myTokenStart = 0;
    private int myTokenEnd = 0;
    private IElementType myTokenType = null;

    /** 表名启发式状态：0=普通；1=期望表名；2=表名后（期望别名或进入后续子句）。 */
    private int myTableState = 0;

    @Override
    public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
        myBuffer = buffer;
        myEnd = endOffset;
        myTokenStart = startOffset;
        myTokenEnd = startOffset;
        myTokenType = null;
        myTableState = 0;
        advance();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public IElementType getTokenType() {
        return myTokenType;
    }

    @Override
    public int getTokenStart() {
        return myTokenStart;
    }

    @Override
    public int getTokenEnd() {
        return myTokenEnd;
    }

    @Override
    public CharSequence getBufferSequence() {
        return myBuffer;
    }

    @Override
    public int getBufferEnd() {
        return myEnd;
    }

    @Override
    public void advance() {
        myTokenStart = myTokenEnd;
        if (myTokenEnd >= myEnd) {
            myTokenType = null;
            return;
        }
        char c = myBuffer.charAt(myTokenStart);
        if (Character.isWhitespace(c)) {
            scanWhitespace();
        } else if (isLineCommentStart()) {
            scanLineComment();
        } else if (isBlockCommentStart()) {
            scanBlockComment();
        } else if (c == '\'') {
            scanString();
        } else if (c == '"') {
            // T-M29.3：双引号字符串（MySQL 字符串字面量 / PG 标识符）——整段单 token，
            // 否则 `"1"` 被拆成 `"`+`1`+`"` 三个 token（格式化拼出 `" 1 "`）
            scanDoubleQuoted();
        } else if (Character.isDigit(c)) {
            scanNumber();
        } else if (isIdentifierStart(c)) {
            scanWord();
        } else if (c == '`' || c == '[') {
            scanQuotedIdentifier();
        } else if (c == '(' || c == ')') {
            myTokenEnd++;
            myTokenType = SqlTokenTypes.PAREN;
        } else {
            scanOperator();
        }
    }

    // ------------------------------------------------------------------
    // 各 token 扫描
    // ------------------------------------------------------------------

    /** 空白（不改变表名启发式状态，避免 `FROM tbl` 中间空白打断）。 */
    private void scanWhitespace() {
        while (myTokenEnd < myEnd && Character.isWhitespace(myBuffer.charAt(myTokenEnd))) {
            myTokenEnd++;
        }
        myTokenType = TokenType.WHITE_SPACE;
    }

    /** `--` 行注释。 */
    private void scanLineComment() {
        myTokenEnd += 2;
        while (myTokenEnd < myEnd && myBuffer.charAt(myTokenEnd) != '\n') {
            myTokenEnd++;
        }
        myTokenType = SqlTokenTypes.COMMENT;
    }

    /** 扫描块注释（斜杠星号包裹）。 */
    private void scanBlockComment() {
        myTokenEnd += 2;
        while (myTokenEnd + 1 < myEnd && !(myBuffer.charAt(myTokenEnd) == '*' && myBuffer.charAt(myTokenEnd + 1) == '/')) {
            myTokenEnd++;
        }
        myTokenEnd = (myTokenEnd + 1 < myEnd) ? myTokenEnd + 2 : myEnd;
        myTokenType = SqlTokenTypes.COMMENT;
    }

    /** `'...'` 字符串（`''` 转义）。 */
    private void scanString() {
        myTokenEnd++;
        while (myTokenEnd < myEnd) {
            char ch = myBuffer.charAt(myTokenEnd);
            if (ch == '\'') {
                if (myTokenEnd + 1 < myEnd && myBuffer.charAt(myTokenEnd + 1) == '\'') {
                    myTokenEnd += 2;
                } else {
                    myTokenEnd++;
                    break;
                }
            } else {
                myTokenEnd++;
            }
        }
        myTokenType = SqlTokenTypes.STRING;
        myTableState = 0;
    }

    /** `"..."` 双引号字符串（`""` 转义；T-M29.3）。 */
    private void scanDoubleQuoted() {
        myTokenEnd++;
        while (myTokenEnd < myEnd) {
            char ch = myBuffer.charAt(myTokenEnd);
            if (ch == '"') {
                if (myTokenEnd + 1 < myEnd && myBuffer.charAt(myTokenEnd + 1) == '"') {
                    myTokenEnd += 2;
                } else {
                    myTokenEnd++;
                    break;
                }
            } else {
                myTokenEnd++;
            }
        }
        myTokenType = SqlTokenTypes.STRING;
        myTableState = 0;
    }

    /** 数字（含小数）。 */
    private void scanNumber() {
        myTokenEnd++;
        while (myTokenEnd < myEnd && (Character.isDigit(myBuffer.charAt(myTokenEnd)) || myBuffer.charAt(myTokenEnd) == '.')) {
            myTokenEnd++;
        }
        myTokenType = SqlTokenTypes.NUMBER;
        myTableState = 0;
    }

    /** 标识符 / 关键字（按表名启发式状态分类）。 */
    private void scanWord() {
        myTokenEnd++;
        while (myTokenEnd < myEnd && isIdentifierPart(myBuffer.charAt(myTokenEnd))) {
            myTokenEnd++;
        }
        String word = myBuffer.subSequence(myTokenStart, myTokenEnd).toString();
        myTokenType = classifyWord(word);
    }

    /** 反引号 `` `xxx` `` / 方括号 `[xxx]` 标识符。 */
    private void scanQuotedIdentifier() {
        myTokenEnd++;
        while (myTokenEnd < myEnd && myBuffer.charAt(myTokenEnd) != '`' && myBuffer.charAt(myTokenEnd) != ']') {
            myTokenEnd++;
        }
        if (myTokenEnd < myEnd) {
            myTokenEnd++;
        }
        myTokenType = SqlTokenTypes.IDENTIFIER;
        myTableState = 0;
    }

    /**
     * 运算符 / 标点（= != <> < <= > >= + - * / % ? 等）。
     *
     * <p>T-SqlOperatorFidelity（用户 2026-11-11 真机反馈）：多字符运算符必须<b>整体</b>成一个 token。
     * 原实现只合并 {@code !=} {@code <>} {@code <=} {@code >=} 四个，{@code ||} 被切成两个 {@code |}
     * token，SqlFormatter 的「运算符两侧空格」规则随即把它拼成 {@code | |}（Oracle 下语法错）——
     * 而预览面板的编辑器文本同时是复制可执行 SQL、分享、直接执行的输入源。同类还有 {@code ::}
     * （PG 强转）/ {@code <=>} / {@code ->} / {@code ->>} / {@code &&} / {@code <<} / {@code >>} /
     * {@code :=} / {@code @>} / {@code <@} / {@code !~} / {@code ~*} / {@code =>}。
     */
    private void scanOperator() {
        for (String op : MULTI_CHAR_OPERATORS) {
            if (matchesAt(myTokenStart, op)) {
                myTokenEnd = myTokenStart + op.length();
                myTokenType = SqlTokenTypes.OPERATOR;
                myTableState = 0;
                return;
            }
        }
        myTokenEnd++;
        myTokenType = SqlTokenTypes.OPERATOR;
        myTableState = 0;
    }

    /** index 起是否逐字等于 op（越界即否）。 */
    private boolean matchesAt(int index, String op) {
        if (index + op.length() > myEnd) {
            return false;
        }
        for (int k = 0; k < op.length(); k++) {
            if (myBuffer.charAt(index + k) != op.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 表名启发式
    // ------------------------------------------------------------------

    /**
     * 关键字集合（大小写不敏感）。仅覆盖本插件 SQL 会出现的子句关键字。
     */
    private IElementType classifyWord(String word) {
        String upper = word.toUpperCase();
        if (KEYWORDS.contains(upper)) {
            switch (upper) {
                case "FROM":
                case "JOIN":
                case "INNER":
                case "LEFT":
                case "RIGHT":
                case "FULL":
                case "CROSS":
                case "INTO":
                case "UPDATE":
                case "TABLE":
                    myTableState = 1;
                    break;
                case "AS":
                    // AS 后可为表别名或列别名，保持当前状态
                    break;
                default:
                    myTableState = 0;
                    break;
            }
            return SqlTokenTypes.KEYWORD;
        }
        switch (myTableState) {
            case 1:
                myTableState = 2;
                return SqlTokenTypes.TABLE;
            case 2:
                myTableState = 0;
                return SqlTokenTypes.TABLE;
            default:
                return SqlTokenTypes.IDENTIFIER;
        }
    }

    private boolean isLineCommentStart() {
        return cAt(myTokenStart) == '-' && cAt(myTokenStart + 1) == '-';
    }

    private boolean isBlockCommentStart() {
        return cAt(myTokenStart) == '/' && cAt(myTokenStart + 1) == '*';
    }

    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** 越界返回 NUL。 */
    private char cAt(int index) {
        return index < myEnd ? myBuffer.charAt(index) : '\u0000';
    }

    /**
     * 多字符运算符表（T-SqlOperatorFidelity）：<b>长的在前</b>，{@link #scanOperator()} 按此顺序最长匹配。
     *
     * <p>收录口径＝插件产物与用户 SQL 里真实出现、且合并后不会与别的原子段打架的组合：
     * {@code ||} 字符串拼接（Oracle / 达梦 / 金仓 / PG / SQLite；MySQL 逻辑或）；{@code ::} PG 系强转；
     * {@code :=} 赋值；{@code =>} 具名实参；{@code <=>} MySQL 空安全等值；{@code ->} / {@code ->>}
     * JSON 取值；{@code &&} 逻辑与 / 数组重叠；{@code <<} / {@code >>} 位移；{@code !~} {@code ~*}
     * PG 正则；{@code @>} {@code <@} jsonb/数组包含；既有 {@code !=} {@code <>} {@code <=} {@code >=}
     * （产物逐字节不变）。
     *
     * <p><b>刻意不收录</b>：{@code #>} / {@code #>>} / {@code #-}——iBatis 内联参数 {@code #x#} 的
     * <b>闭合 {@code #}</b> 就在前面，合并后 SqlFormatter.matchHashClose 再也认不出闭合界
     * （{@code WHERE id#>0} 会把参数拆坏）；{@code ?|} / {@code ?&}——{@code ?} 是未解析参数占位符，
     * 另有一套位点记账，不与运算符合并。
     */
    private static final List<String> MULTI_CHAR_OPERATORS = List.of(
            "<=>", "->>",
            "!=", "<>", "<=", ">=", "||", "::", ":=",
            "->", "&&", "<<", ">>", "!~", "~*", "@>", "<@", "=>");

    /** SQL 关键字（大写，去重；补全器复用）。 */
    public static final Set<String> KEYWORDS = Set.copyOf(Arrays.asList(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN", "LIKE", "EXISTS",
            "GROUP", "BY", "ORDER", "ASC", "DESC", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "ALL",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON", "AS", "USING", "UNION",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "TABLE", "CREATE", "DROP", "ALTER", "ADD",
            "COLUMN", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "DEFAULT", "INDEX",
            "CASE", "WHEN", "THEN", "ELSE", "END",
            "COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "NULLIF", "CAST", "CONVERT"
    ));
}
