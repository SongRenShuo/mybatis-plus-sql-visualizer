// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperHighlightingLexer.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.lexer.LexerBase;
import com.intellij.lexer.RestartableLexer;
import com.intellij.lexer.TokenIterator;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 手写单趟词法（lexer-only）。T-XmlGap W2/W3 在原 SQL 词法之上加两个<b>参数子状态机</b>。
 *
 * <h2>token 优先级（未变部分）</h2>
 *
 * <p>SQL 顶层：注释（{@code --} / 块注释）&gt; 字符串（{@code '…'} / {@code "…"}，{@code ''} 加倍
 * 不截断）&gt; 参数定界符（"#{" / "${"，{@code \#} 转义按普通文本）&gt; 数字 &gt;
 * 关键字/标识符 &gt; 标点/空白。字符串态内不识别 {@code #{}}（MyBatis 参数占位不出现在字符串内的
 * 主形态；字符串内一切按 STRING）。未闭合字符串/注释扫到尾（BAD 形态并入 STRING/COMMENT）——
 * <b>不产 error token，注入着色不做校验器</b>。</p>
 *
 * <h2>W2：{@code #{}} 与 {@code ${}} 分色（决策 2-A）</h2>
 *
 * <p>"#{" 与 "${" 各出一个定界符 token（{@code SqlMapperTT.PARAM_HASH} /
 * {@code SqlMapperTT.PARAM_DOLLAR}），内容另出细 token；配对的 "}" 也按<b>起头定界符的种类</b>
 * 回归同类 token——因此两个占位符「从开头到闭合」都可辨（高亮器给两个 token 各配一个
 * TextAttributesKey）。</p>
 *
 * <h2>W3：两个子状态机（同一 Lexer，不新建 Language）</h2>
 *
 * <table border="1">
 *   <tr><th>子状态</th><th>进入</th><th>内容 token</th><th>退出</th></tr>
 *   <tr><td>参数规格 {@code #{…}}</td><td>顶层 "#{"（2 字符）</td>
 *       <td>参数名/属性名/属性值一律标识符（<b>此处不判关键字</b>，见 classifyWord）、
 *           {@code ,}、{@code =}、属性值（字符串/数字）</td><td>配对 "}"（或缓冲末尾）</td></tr>
 *   <tr><td>OGNL {@code ${…}}</td><td>顶层 "${"（2 字符）</td>
 *       <td>关键字（{@code and/or/not/in/null}，按 {@code Ognl} 实际支持）、{@code true/false} 布尔、
 *           双引号/单引号字符串、数字、{@code @com.Foo@bar} 静态引用、运算符、括号/方括号/花括号/逗号/点</td>
 *       <td>深度归零的 "}"（或缓冲末尾）</td></tr>
 * </table>
 *
 * <p>未闭合 "#{"/"${" 照旧扫到缓冲末尾：<b>不产 error token</b>（红线：注入着色不做
 * 校验器）；闭合 "}" 的缺失只体现为「后续内容留在子状态里」，不报错。</p>
 *
 * <h2>券 D：iBatis 定界符（T-Ibatis2Support 券 D，<b>新增第三类</b>——单趟、无子状态）</h2>
 *
 * <table border="1">
 *   <tr><th>形态</th><th>token</th></tr>
 *   <tr><td>{@code #name#} / {@code #name:TYPE#} / {@code #name:TYPE:nullValue#}（两段式第二段恒为 jdbcType）</td>
 *       <td>整段一个 {@link SqlMapperTT#PARAM_HASH}</td></tr>
 *   <tr><td>{@code $name$}</td><td>整段一个 {@link SqlMapperTT#PARAM_DOLLAR}</td></tr>
 *   <tr><td>{@code ##} / {@code $$}（iBatis 转义字面量，产出字面 {@code #}/{@code $}）</td>
 *       <td>{@link SqlMapperTT#IBATIS_ESCAPE}（无色＝普通文本）</td></tr>
 *   <tr><td>落单 {@code #}/{@code $}（未闭合或形态非法）</td>
 *       <td>单字符 {@link SqlMapperTT#OPERATOR}（与放宽前一致）</td></tr>
 * </table>
 *
 * <p><b>判定以配对闭合为准</b>：先在<b>有界窗口</b>（{@link #IBATIS_PARAM_MAX_LENGTH}）内找配对的闭合定界符，
 * 找到且内容形态合法才出参数 token；找不到/非法则退回单字符运算符，<b>绝不</b>吞掉后续文本。
 * 细则与被否掉的「进子状态」形态见 {@link #scanIbatisDelimiter}/{@link #findIbatisParamClose} 的 KDoc。</p>
 *
 * <p>与 MyBatis 分支的关系：{@code #{}/{}}（2 字符定界符）优先级更高，排在 iBatis 分支<b>之前</b>，
 * {@code #{…}/{}} 的既有语义逐字节不变。</p>
 *
 * <h2>零分配与 O(n) 取向（T-PerfSweep16 F46 风格延续）</h2>
 *
 * <p>全程只读 buffer 索引，不做 {@code subSequence().toString()}：关键字判定用
 * {@code regionMatches(ignoreCase)} 直读缓冲（按词长分桶，见
 * {@code SqlMapperLanguage.KEYWORDS_BY_LENGTH}），布尔字面量与静态引用同样直读（先比长度/字符，
 * 不建子串）。单趟 O(n)：每个字符最多被一个 token 扫过一次（字符串/注释/参数内容都是线性推进）。</p>
 *
 * <h2>跨 token 状态与增量重扫</h2>
 *
 * <p>子状态必须跨 token 保存（{@code #{} 之后的 token 要知道自己在参数里），故本 lexer 实现
 * {@link RestartableLexer}：<b>状态值 = 该 token 起点处的状态</b>（{@code 0} = SQL 顶层，
 * {@code 1} = {@code #{}} 参数规格，{@code 2+n} = {@code ${}} OGNL 且花括号嵌套深度 n），
 * {@code isRestartableState} 恒真（状态自含全部上下文，任意状态可续扫）。这是注入片段被编辑后
 * 平台增量重着色（LexerEditorHighlighter 从记录状态续扫）依然正确的必要条件——否则会从参数中部
 * 按 SQL 顶层重扫，把闭合 {@code }} 判成普通标点。</p>
 */
public class SqlMapperHighlightingLexer extends LexerBase implements RestartableLexer {

    private CharSequence buffer = "";
    private int endOffset = 0;
    private int tokenStart = 0;
    private int tokenEnd = 0;
    private IElementType tokenType = null;

    /** 当前 token 起点处的状态（getState 语义 = 「在该 token 起点续扫所需状态」）。 */
    private int state = STATE_SQL;

    /** 下一个 token 起点处的状态（advance 扫完当前 token 时落到这里）。 */
    private int pendingState = STATE_SQL;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.endOffset = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        this.state = normalizeState(initialState);
        this.pendingState = this.state;
        advance();
    }

    @Override
    @Nullable
    public IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return endOffset;
    }

    /** 状态 = 当前 token 起点处的状态（见类 KDoc「跨 token 状态与增量重扫」）。 */
    @Override
    public int getState() {
        return state;
    }

    // ------------------------------------------------------------------
    // RestartableLexer（增量重扫契约）
    // ------------------------------------------------------------------

    @Override
    public int getStartState() {
        return STATE_SQL;
    }

    /** 状态自含参数上下文（0/1/2+depth），任意状态都能从对应 token 边界续扫。 */
    @Override
    public boolean isRestartableState(int state) {
        return state >= 0;
    }

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState,
                      @Nullable TokenIterator tokenIterator) {
        // 本 lexer 的续扫只依赖状态（不依赖前序 token 序列），iterator 忽略。
        start(buffer, startOffset, endOffset, initialState);
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        if (tokenStart >= endOffset) {
            tokenType = null;
            tokenEnd = endOffset;
            return;
        }
        state = normalizeState(pendingState);
        pendingState = state;
        scanToken();
    }

    private int normalizeState(int raw) {
        return raw < STATE_SQL ? STATE_SQL : raw;
    }

    private void scanToken() {
        if (state == STATE_SQL) {
            scanSql();
        } else if (state == STATE_HASH_SPEC) {
            scanHashSpec();
        } else {
            scanOgnl();
        }
    }

    // ------------------------------------------------------------------
    // SQL 顶层
    // ------------------------------------------------------------------

    private void scanSql() {
        char c = buffer.charAt(tokenStart);
        if (c == '-' && tokenStart + 1 < endOffset && buffer.charAt(tokenStart + 1) == '-') {
            scanLineComment();
        } else if (c == '/' && tokenStart + 1 < endOffset && buffer.charAt(tokenStart + 1) == '*') {
            scanBlockComment();
        } else if (c == '\'' || c == '"') {
            scanQuotedString(c);
        } else if ((c == '#' || c == '$') && tokenStart + 1 < endOffset && buffer.charAt(tokenStart + 1) == '{'
                && (tokenStart == 0 || buffer.charAt(tokenStart - 1) != '\\')) {
            // R21-注：\#{ 转义形态不产参数占位（转义=非真参数，照 MyBatis TokenParser 转义语义）
            tokenEnd = tokenStart + 2;
            if (c == '#') {
                tokenType = SqlMapperTT.PARAM_HASH;
                pendingState = STATE_HASH_SPEC;
            } else {
                tokenType = SqlMapperTT.PARAM_DOLLAR;
                pendingState = STATE_OGNL_BASE;
            }
        } else if (c == '#' || c == '$') {
            // T-Ibatis2Support 券 D：iBatis 定界符（`#name#` / `$name$` / `##` / `$$`）。
            // 必须排在 `#{`/`${` 分支之后——MyBatis 形态优先级更高（`#{` 是 2 字符定界符，
            // 不被 iBatis 的单字符配对逻辑抢走）。
            scanIbatisDelimiter(c);
        } else if (Character.isDigit(c)) {
            scanNumber();
        } else if (isWordStart(c)) {
            scanWord();
        } else if (Character.isWhitespace(c)) {
            scanWhitespace();
        } else {
            scanPunctuation();
        }
    }

    // ------------------------------------------------------------------
    // iBatis 定界符（T-Ibatis2Support 券 D）
    // ------------------------------------------------------------------

    /**
     * iBatis 参数/转义定界符（{@code #name#} / {@code #name:TYPE#} / {@code #name:TYPE:nullValue#} / {@code $name$} /
     * 转义 {@code ##} / {@code $$}）。<b>只在 SQL 顶层</b>被调用（{@code #{}/{}} 那条分支在前，字符串/注释在更前）。
     *
     * <h2>为什么「先向前找配对、再决定出不出参数 token」（而不是进子状态）</h2>
     *
     * <p>子状态形态（遇 {@code #} 进子状态、遇闭合 {@code #} 退出）在<b>未闭合</b>时会把缓冲剩余全文都吞进该状态
     * ——正是券 D 明令禁止的形态（落单 {@code #} / 半截注释会让后续整段 SQL 无色；平台增量重扫也
     * 无法自洽）。故本实现<b>先向前找配对</b>：</p>
     * <ul>
     * <li>找到且内容形态合法 → <b>整段（含两侧定界符）出一个 token</b>（{@code #…#} → {@link SqlMapperTT#PARAM_HASH}、
     *     {@code $…$} → {@link SqlMapperTT#PARAM_DOLLAR}），token 边界<b>就是</b>配对边界（配对闭合为准）；</li>
     * <li>找不到 / 形态非法 → 退回单字符 {@link SqlMapperTT#OPERATOR}（与放宽前「落单 {@code #} 按运算符」一致），
     *     且<b>绝不</b>跨过该字符继续吞后续文本。</li>
     * </ul>
     *
     * <p>「形态合法」（见 {@link #findIbatisParamClose}，全 O(1) 字符判定，长度有上限）：</p>
     * <ul>
     * <li>内容非空、首字符是词起始（字母 / {@code _}）——属性名或属性路径（{@code #{} 不走这里）；</li>
     * <li>内容仅含词字符 / {@code .} / {@code [} / {@code ]} / {@code :}（覆盖 {@code x.y}、{@code ids[]}、{@code list[].name}、{@code x:VARCHAR}、
     *     {@code x:VARCHAR:nullValue}；空白与换行天然不合法 ⇒ 不跨行配对）；</li>
     * <li>配对上限 {@link #IBATIS_PARAM_MAX_LENGTH} 字符内。</li>
     * </ul>
     *
     * <h2>{@code ##} / {@code $$} 转义字面量</h2>
     *
     * <p>相邻同符即 iBatis 的转义（产出字面 {@code #} / {@code $}）：出 {@link SqlMapperTT#IBATIS_ESCAPE}（不映射颜色＝
     * 普通文本）并<b>消费两个字符</b>。这一步同时挡掉「第二个 {@code #} 被当参数起始」的错配——例如
     * {@code ##foo#} 必须读作「转义 {@code #} + {@code foo#}」，<b>不得</b>读作「空参数 + {@code #foo#} 参数」。
     * 没有本分支时 {@code ##} 会落到两次单字符 {@link SqlMapperTT#OPERATOR}，{@code ##foo#} 还会错配出一个参数；
     * 本分支即券 D 反向验证 ② 的被摘对象（摘掉 → 字面量用例必红）。</p>
     */
    private void scanIbatisDelimiter(char c) {
        // 转义字面量 `##` / `$$`：整体一个无色文本 token（消费两字符，防第二个定界符被当起始）
        if (tokenStart + 1 < endOffset && buffer.charAt(tokenStart + 1) == c) {
            tokenEnd = tokenStart + 2;
            tokenType = SqlMapperTT.IBATIS_ESCAPE;
            return;
        }
        int close = findIbatisParamClose(c);
        if (close < 0) {
            // 未闭合 / 形态不合法：不认参数，退回单字符运算符（不吞后续文本）
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.OPERATOR;
            return;
        }
        tokenEnd = close + 1;
        tokenType = c == '#' ? SqlMapperTT.PARAM_HASH : SqlMapperTT.PARAM_DOLLAR;
    }

    /**
     * 找配对的闭合定界符下标（{@code #} 配 {@code #}、{@code $} 配 {@code $}）；内容空 / 形态不合法 / 超上限 → -1。
     * 零分配（只做索引推进与字符判定，不建子串）。
     */
    private int findIbatisParamClose(char open) {
        int contentStart = tokenStart + 1;
        if (contentStart >= endOffset) return -1;
        if (!isWordStart(buffer.charAt(contentStart))) return -1;
        int limit = Math.min(endOffset, tokenStart + IBATIS_PARAM_MAX_LENGTH);
        int i = contentStart;
        while (i < limit) {
            char ch = buffer.charAt(i);
            if (ch == open) return i;
            if (!isIbatisParamContentChar(ch)) return -1;
            i++;
        }
        return -1;
    }

    /** iBatis 参数内容允许字符：词字符 / {@code .}（属性链）/ {@code []}（iterate 下标）/ {@code :}（段分隔，如 jdbcType）。 */
    private boolean isIbatisParamContentChar(char c) {
        return isWordPart(c) || c == '.' || c == '[' || c == ']' || c == ':';
    }

    // ------------------------------------------------------------------
    // `#{…}` 参数规格子状态
    // ------------------------------------------------------------------

    private void scanHashSpec() {
        char c = buffer.charAt(tokenStart);
        if (c == '\'' || c == '"') {
            scanQuotedString(c);
        } else if (c == '}') {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.PARAM_HASH;
            pendingState = STATE_SQL;
        } else if (Character.isDigit(c)) {
            scanNumber();
        } else if (isWordStart(c)) {
            scanWord();
        } else if (Character.isWhitespace(c)) {
            scanWhitespace();
        } else {
            scanPunctuation();
        }
    }

    // ------------------------------------------------------------------
    // `${…}` OGNL 子状态
    // ------------------------------------------------------------------

    private void scanOgnl() {
        char c = buffer.charAt(tokenStart);
        if (c == '\'' || c == '"') {
            scanQuotedString(c);
        } else if (c == '}') {
            tokenEnd = tokenStart + 1;
            if (state == STATE_OGNL_BASE) {
                tokenType = SqlMapperTT.PARAM_DOLLAR;
                pendingState = STATE_SQL;
            } else {
                // `${a in {1,2}}` 的内层花括号：深度减一，留在 OGNL 子状态
                tokenType = SqlMapperTT.BRACE;
                pendingState = state - 1;
            }
        } else if (c == '{') {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.BRACE;
            pendingState = state + 1;
        } else if (c == '@') {
            scanStaticRef();
        } else if (Character.isDigit(c)) {
            scanNumber();
        } else if (isWordStart(c)) {
            scanWord();
        } else if (Character.isWhitespace(c)) {
            scanWhitespace();
        } else {
            scanPunctuation();
        }
    }

    /**
     * OGNL 静态引用 {@code @com.Foo@bar}（类引用 + 成员）整体一个 token；形态不成立时退化为
     * 单字符 OPERATOR（{@code @} 在 OGNL 里也可能是别的东西，宁可不上色也不吞字）。
     *
     * <p>零分配：只做索引推进与字符判定，不建子串、不 split。</p>
     */
    private void scanStaticRef() {
        int i = tokenStart + 1;
        while (i < endOffset && isClassPathChar(buffer.charAt(i))) i++;
        // 类名段非空、且下一字符是分隔 `@`
        if (i > tokenStart + 1 && i < endOffset && buffer.charAt(i) == '@') {
            int j = i + 1;
            while (j < endOffset && isWordPart(buffer.charAt(j))) j++;
            if (j > i + 1) {
                tokenEnd = j;
                tokenType = SqlMapperTT.STATIC_REF;
                return;
            }
        }
        tokenEnd = tokenStart + 1;
        tokenType = SqlMapperTT.OPERATOR;
    }

    // ------------------------------------------------------------------
    // 共用扫描
    // ------------------------------------------------------------------

    private void scanLineComment() {
        int i = tokenStart + 2;
        while (i < endOffset && buffer.charAt(i) != '\n') i++;
        tokenEnd = i;
        tokenType = SqlMapperTT.COMMENT;
    }

    private void scanBlockComment() {
        int i = tokenStart + 2;
        while (i + 1 < endOffset && !(buffer.charAt(i) == '*' && buffer.charAt(i + 1) == '/')) i++;
        tokenEnd = Math.min(endOffset, i + 2);
        tokenType = SqlMapperTT.COMMENT;
    }

    /** {@code '…'} / {@code "…"} 字符串：{@code ''} 加倍不截断；单引号态下 {@code \} 转义跳过下一字符（既有语义不变）。 */
    private void scanQuotedString(char quote) {
        int i = tokenStart + 1;
        while (i < endOffset) {
            if (buffer.charAt(i) == '\\' && quote == '\'') {
                i += 2;
            } else if (buffer.charAt(i) == quote) {
                if (i + 1 < endOffset && buffer.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    break;
                }
            } else {
                i++;
            }
        }
        tokenEnd = Math.min(endOffset, i + 1);
        tokenType = SqlMapperTT.STRING;
    }

    private void scanNumber() {
        int i = tokenStart;
        while (i < endOffset && (Character.isDigit(buffer.charAt(i)) || buffer.charAt(i) == '.')) i++;
        tokenEnd = i;
        tokenType = SqlMapperTT.NUMBER;
    }

    private void scanWhitespace() {
        int i = tokenStart;
        while (i < endOffset && Character.isWhitespace(buffer.charAt(i))) i++;
        tokenEnd = i;
        tokenType = SqlMapperTT.WHITE_SPACE;
    }

    private void scanWord() {
        int i = tokenStart;
        while (i < endOffset && isWordPart(buffer.charAt(i))) i++;
        tokenEnd = i;
        tokenType = classifyWord(tokenStart, i);
    }

    /** 标点族：括号/方括号/花括号/逗号/点各立 token（平台语义槽位各自接色），其余按运算符。 */
    private void scanPunctuation() {
        char c = buffer.charAt(tokenStart);
        if (c == '(' || c == ')') {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.PAREN;
        } else if (c == '[' || c == ']') {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.BRACKET;
        } else if (c == '{' || c == '}') {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.BRACE;
        } else if (c == ',') {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.COMMA;
        } else if (c == '.') {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.DOT;
        } else {
            tokenEnd = (c == '!' || c == '<' || c == '>' || c == '|' || c == '&')
                    ? (isTwoCharOperator(c) ? tokenStart + 2 : tokenStart + 1)
                    : tokenStart + 1;
            tokenType = SqlMapperTT.OPERATOR;
        }
    }

    private boolean isTwoCharOperator(char first) {
        if (tokenStart + 1 >= endOffset) return false;
        char second = buffer.charAt(tokenStart + 1);
        switch (first) {
            case '!':
                return second == '=';
            case '<':
                return second == '=' || second == '>';
            case '>':
                return second == '=';
            case '|':
            case '&':
                return second == first;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    // 词分类
    // ------------------------------------------------------------------

    /**
     * 词分类：{@code true}/{@code false} → BOOLEAN（先比长度再 regionMatches，零分配）；
     * 其余按<b>所处子状态</b>分用不同词集：
     * <ul>
     *   <li>{@code #{…}} 参数规格：<b>不判关键字</b>，一切词按标识符——那里的词是参数名/属性名/属性值
     *       （W3 #4 口径「参数名 identifier、{@code ,}、{@code =}、属性名、属性值」）；若在此处套用方言并集，
     *       高频参数名 {@code #{size}}/{@code #{key}}/{@code #{value}}/{@code #{list}} 会被 Oracle/H2
     *       保留字误着关键字色；</li>
     *   <li>{@code ${…}} OGNL：用 {@code SqlMapperLanguage.OGNL_KEYWORDS}（{@code Ognl} 实际支持的词）；
     *   <li>SQL 顶层（语句文本 / 属性值宿主）：用全量 {@code SqlMapperLanguage.KEYWORDS}（含方言超集）。</li>
     * </ul>
     *
     * <p>例外（W3 #8 的 {@code page.size} 判别位）：<b>紧跟 {@code .} 之后的词一律按标识符</b>——
     * 它是属性/成员名（{@code page.size}、{@code t.order}、{@code x.value}），不是裸用的关键字。
     * 这条同时挡掉方言并集在 SQL 顶层的过度着色：{@code size}/{@code value}/{@code key}/{@code row}/
     * {@code top} 等在 Oracle/H2 等方言里是保留字，裸用该包引号（着色正确），但作为限定名尾段时是
     * 标识符（{@code t.size} 不可能被当关键字）。判据只看前一个字符，零分配。</p>
     */
    private IElementType classifyWord(int start, int end) {
        if (start > 0 && buffer.charAt(start - 1) == '.') return SqlMapperTT.IDENTIFIER;
        int len = end - start;
        if (len == LEN_TRUE && regionMatchesIgnoreCase(start, TRUE_WORD, LEN_TRUE)) {
            return SqlMapperTT.BOOLEAN;
        }
        if (len == LEN_FALSE && regionMatchesIgnoreCase(start, FALSE_WORD, LEN_FALSE)) {
            return SqlMapperTT.BOOLEAN;
        }
        return matchesKeyword(start, len) ? SqlMapperTT.KEYWORD : SqlMapperTT.IDENTIFIER;
    }

    private boolean matchesKeyword(int start, int len) {
        if (state >= STATE_OGNL_BASE) {
            for (String keyword : SqlMapperLanguage.OGNL_KEYWORDS) {
                if (keyword.length() == len && regionMatchesIgnoreCase(start, keyword, len)) {
                    return true;
                }
            }
            return false;
        }
        // `#{…}` 内不判关键字（见 classifyWord KDoc）
        if (state == STATE_HASH_SPEC) return false;
        List<String> bucket = SqlMapperLanguage.KEYWORDS_BY_LENGTH.get(len);
        if (bucket == null) return false;
        for (String keyword : bucket) {
            if (regionMatchesIgnoreCase(start, keyword, len)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** {@code @} 与成员名之间的类路径字符（包名点 + 内部类 {@code $}）。 */
    private boolean isClassPathChar(char c) {
        return isWordPart(c) || c == '.' || c == '$';
    }

    /** 忽略大小写直读缓冲比较（String 快路径 + CharSequence 通用兜底，零分配）。 */
    private boolean regionMatchesIgnoreCase(int start, String other, int len) {
        if (buffer instanceof String) {
            return ((String) buffer).regionMatches(true, start, other, 0, len);
        }
        for (int i = 0; i < len; i++) {
            char a = buffer.charAt(start + i);
            char b = other.charAt(i);
            if (a == b) continue;
            if (Character.toLowerCase(a) != Character.toLowerCase(b)
                    && Character.toUpperCase(a) != Character.toUpperCase(b)) {
                return false;
            }
        }
        return true;
    }

    /** 状态：SQL 顶层。 */
    private static final int STATE_SQL = 0;

    /** 状态：{@code #{…}} 参数规格子状态。 */
    private static final int STATE_HASH_SPEC = 1;

    /** 状态：{@code ${…}} OGNL 子状态、花括号嵌套深度 0。深度 d → STATE_OGNL_BASE + d。 */
    private static final int STATE_OGNL_BASE = 2;

    private static final String TRUE_WORD = "true";
    private static final String FALSE_WORD = "false";
    private static final int LEN_TRUE = 4;
    private static final int LEN_FALSE = 5;

    /**
     * iBatis 参数 token 的最大长度（含两侧定界符）。超限即判「不是参数占位」——防止在正文里
     * 找配对 {@code #} 时跨过大段 SQL 误配（iBatis 属性路径远短于此；也让向前找配对的开销有硬上限）。
     */
    private static final int IBATIS_PARAM_MAX_LENGTH = 256;
}
