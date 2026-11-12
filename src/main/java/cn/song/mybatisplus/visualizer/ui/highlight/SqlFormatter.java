package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import kotlin.Pair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 轻量 SQL 格式化器（M9 增强，T-M27.4 缩进模型重构）：把可编辑 SQL 规范化为标准可读格式。
 *
 * 基于 {@link SqlLexer} 分词，规则：
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
public final class SqlFormatter {

    public static final SqlFormatter INSTANCE = new SqlFormatter();

    /** 主子句关键字（各占一行）。 */
    private static final Set<String> NEW_LINE_KEYWORDS = Set.copyOf(Arrays.asList(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT",
            "UPDATE", "SET", "DELETE", "INSERT", "INTO", "VALUES", "UNION", "JOIN",
            "LEFT", "RIGHT", "INNER", "FULL", "CROSS"
    ));

    /**
     * DDL 首关键字（T-DdlLogPreview S2，20261006 第三轮走查）。
     *
     * <p>立项动因：{@code NEW_LINE_KEYWORDS} 只覆盖 DML 子句，DDL（CREATE/ALTER/DROP/TRUNCATE）**一个换行点
     * 都没有** ⇒ 用户点「格式化」看不到任何变化（实测 {@code CREATE TABLE …} 仍单行）。现按首关键字分派：
     * DDL 走下面两条规则（定义列表「{@code (} 换行缩进 4 + 顶层逗号逐列换行 + 闭括号对齐起始行」，
     * ALTER 的顶层逗号亦换行）——<b>DML 路径与既有语料输出逐字节不变</b>（规则全部由本集合门控）。
     */
    private static final Set<String> DDL_LEAD_KEYWORDS = Set.copyOf(Arrays.asList(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME"));

    /** DDL 定义列表缩进（{@code (} 后与列定义逐行缩进量）。 */
    private static final int DDL_LIST_INDENT = 4;

    /** DDL 域内 {@code (} 前**保留空格**的关键字（惯例形态；其余关键字/标识符后的括号贴紧＝类型尺寸）。 */
    private static final Set<String> DDL_PAREN_SPACED_KEYWORDS = Set.copyOf(Arrays.asList(
            "KEY", "REFERENCES", "CHECK", "IN", "EXISTS", "USING", "VALUES"));

    /** 紧跟 JOIN 的修饰关键字（`LEFT JOIN` 保持同行）。 */
    private static final Set<String> JOIN_QUALIFIERS = Set.copyOf(Arrays.asList("LEFT", "RIGHT", "INNER", "FULL", "CROSS"));

    /** 函数名（括号前不空格：`COUNT(`）。T-M29.3：补 CONCAT（MP LIKE 常用）/TRIM_SCALE（项目自定义）及常见函数。 */
    private static final Set<String> FUNCTION_NAMES = Set.copyOf(Arrays.asList(
            "COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "CAST", "CONVERT",
            "CONCAT", "TRIM_SCALE", "NULLIF", "IFNULL", "LENGTH", "SUBSTRING", "SUBSTR", "REPLACE",
            "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "ROUND", "ABS", "FLOOR", "CEIL", "CEILING",
            "NOW", "CURDATE", "DATE_FORMAT", "STR_TO_DATE", "GROUP_CONCAT", "IF", "JSON_EXTRACT",
            "DATEDIFF", "DATEADD"
    ));

    /** 格式化 SQL 文本。 */
    public static String format(String sql) {
        List<Pair<IElementType, String>> tokens = lex(sql);
        if (tokens.isEmpty()) {
            return sql.trim();
        }
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        FmtState st = new FmtState(sb);
        // T-DdlLogPreview S2：DDL 分派（首关键字判定；注释/空白已由 lex 剔除）
        String ddlLead = null;
        for (Pair<IElementType, String> token : tokens) {
            if (token.getFirst() != SqlTokenTypes.COMMENT) {
                if (token.getFirst() == SqlTokenTypes.KEYWORD
                        && DDL_LEAD_KEYWORDS.contains(token.getSecond().toUpperCase())) {
                    ddlLead = token.getSecond().toUpperCase();
                }
                break;
            }
        }
        boolean ddlStatement = ddlLead != null;
        for (Pair<IElementType, String> entry : tokens) {
            IElementType type = entry.getFirst();
            String text = entry.getSecond();
            String upper = text.toUpperCase();
            if (type == SqlTokenTypes.KEYWORD && upper.equals("JOIN")) {
                boolean qualifier = JOIN_QUALIFIERS.contains(st.prevText.toUpperCase());
                if (qualifier) {
                    st.flushSpace();
                } else if (!st.atLineStart) {
                    st.newline(st.subIndent());
                }
                st.currentClause = "JOIN";
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.KEYWORD && NEW_LINE_KEYWORDS.contains(upper)) {
                if (!st.atLineStart) {
                    st.newline(st.subIndent());
                }
                st.pendingSpace = false;
                st.currentClause = upper;
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
                // SELECT/SET 后列清单换行（列缩进 = 本行缩进 + 4）
                if (upper.equals("SELECT") || upper.equals("SET")) {
                    st.newline(st.lineIndent + 4);
                }
            } else if (type == SqlTokenTypes.KEYWORD && (upper.equals("AND") || upper.equals("OR"))) {
                if (st.depth == 0) {
                    if (!st.atLineStart) {
                        st.newline(4);
                    }
                } else if (st.parenNewlined) {
                    if (!st.atLineStart) {
                        st.newline(st.parenIndents.peekLast() + 8);
                    }
                } else {
                    st.flushSpace();
                }
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.KEYWORD) {
                st.flushSpace();
                sb.append(upper);
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                // T-DdlLogPreview S2：DDL 定义列表内的**类型尺寸括号**贴紧左侧 token
                // （`VARCHAR(64)`/`DECIMAL(10,2)` 不得被插空格——注意类型名多为 KEYWORD token；
                // 而 `PRIMARY KEY (`id`)`／`CHECK (…)`／`REFERENCES` 按惯例留空格）
                boolean ddlTightParen = ddlStatement && st.depth >= 1 && (
                        st.prevType == SqlTokenTypes.IDENTIFIER
                                || (st.prevType == SqlTokenTypes.KEYWORD
                                && !DDL_PAREN_SPACED_KEYWORDS.contains(st.prevText.toUpperCase())));
                if (!ddlTightParen && !FUNCTION_NAMES.contains(st.prevText.toUpperCase())) {
                    st.flushSpace();
                }
                sb.append('(');
                st.depth++;
                st.parenIndents.addLast(st.lineIndent);
                st.parenNewlined = false;
                st.pendingSpace = false;
                st.atLineStart = false;
                // T-DdlLogPreview S2：DDL 定义列表开括号 → 换行缩进（列定义逐行；
                // parenNewlined 由 newline 置位 ⇒ 闭括号按既有机制对齐起始行）
                if (ddlStatement && st.depth == 1) {
                    st.newline(DDL_LIST_INDENT);
                }
            } else if (type == SqlTokenTypes.PAREN && text.equals(")")) {
                // T-DdlLogPreview S2：DDL 定义列表的**外层闭括号恒独立成行**——列表内嵌套括号
                // （`PRIMARY KEY (`id`)`）会把 parenNewlined 复位，靠既有判据会让 `)` 与表尾选项
                // 粘在最后一条列定义行尾（实测形态），故 DDL 域内按 depth==1 显式断行
                if (st.depth == 1 && ddlStatement && !st.atLineStart) {
                    st.newline(st.parenIndents.peekLast());
                } else if (st.parenNewlined && st.depth > 0) {
                    st.newline(st.parenIndents.peekLast());
                }
                sb.append(')');
                st.depth--;
                if (!st.parenIndents.isEmpty()) {
                    st.parenIndents.removeLast();
                }
                st.parenNewlined = false;
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(",")) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                sb.append(',');
                if (ddlStatement && !st.atLineStart
                        && (st.depth == 1 || (st.depth == 0 && "ALTER".equals(ddlLead)))) {
                    // T-DdlLogPreview S2：DDL 定义列表（depth 1）逐列换行；ALTER 顶层动作（depth 0）
                    // 同样逐条换行——嵌套小括号（VARCHAR(64)/PRIMARY KEY (`id`)）不受影响
                    st.newline(DDL_LIST_INDENT);
                } else if (st.depth == 0 && (st.currentClause.equals("SELECT") || st.currentClause.equals("SET")) && !st.atLineStart) {
                    st.newline(4);
                } else {
                    st.pendingSpace = true;
                    st.atLineStart = false;
                }
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(".")) {
                sb.append('.');
                st.pendingSpace = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR) {
                st.flushSpace();
                sb.append(text);
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.COMMENT) {
                if (!st.atLineStart) {
                    sb.append('\n');
                }
                sb.append(text);
                sb.append('\n');
                st.atLineStart = true;
                st.pendingSpace = false;
            } else {
                st.flushSpace();
                sb.append(text);
                st.pendingSpace = true;
                st.atLineStart = false;
            }
            st.prevText = text;
            st.prevType = type;
        }
        return sb.toString().trim();
    }

    /**
     * 关键字换行格式化（M-KeywordWrap）：只在主子句关键字处换行，子句内容同行。
     *
     * 与 {@link #format(String)}（全展开）互补——前者用于阅读细节，后者用于快速总览。
     */
    public static String formatClauseLine(String sql) {
        List<Pair<IElementType, String>> tokens = lex(sql);
        if (tokens.isEmpty()) {
            return sql.trim();
        }
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        FmtClauseState st = new FmtClauseState(sb);
        for (Pair<IElementType, String> entry : tokens) {
            IElementType type = entry.getFirst();
            String text = entry.getSecond();
            String upper = text.toUpperCase();
            if (type == SqlTokenTypes.KEYWORD && upper.equals("JOIN")) {
                boolean qualifier = JOIN_QUALIFIERS.contains(st.prevText.toUpperCase());
                if (qualifier) {
                    st.flushSpace();
                } else if (st.depth == 0 && !st.atLineStart) {
                    st.newline();
                }
                st.currentClause = "JOIN";
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.KEYWORD && NEW_LINE_KEYWORDS.contains(upper)) {
                if (st.depth == 0) {
                    if (!st.atLineStart) {
                        st.newline();
                    }
                    st.pendingSpace = false;
                } else {
                    st.flushSpace();
                }
                st.currentClause = upper;
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
                st.pendingComma = false;
            } else if (type == SqlTokenTypes.KEYWORD && (upper.equals("AND") || upper.equals("OR"))) {
                st.pendingSpace = false;
                if (!st.atLineStart) {
                    sb.append(' ');
                    sb.append(' ');
                }
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
                st.pendingComma = false;
            } else if (type == SqlTokenTypes.KEYWORD) {
                st.flushSpace();
                sb.append(upper);
                st.pendingSpace = true;
                st.atLineStart = false;
                st.pendingComma = false;
            } else if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                if (!FUNCTION_NAMES.contains(st.prevText.toUpperCase())) {
                    st.flushSpace();
                }
                sb.append('(');
                st.depth++;
                st.pendingSpace = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.PAREN && text.equals(")")) {
                st.pendingSpace = false;
                sb.append(')');
                st.depth--;
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(",")) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                sb.append(',');
                // F-CASE：始终设置 pendingSpace=true，后续关键字通过 flushSpace() 加空格；
                // 标识符通过 pendingComma 标记跳过空格（紧凑列清单）
                st.pendingComma = st.currentClause.equals("SELECT") || st.currentClause.equals("SET") || st.depth > 0;
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(".")) {
                sb.append('.');
                st.pendingSpace = false;
                st.pendingComma = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR) {
                st.flushSpace();
                sb.append(text);
                st.pendingSpace = true;
                st.pendingComma = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.COMMENT) {
                if (!st.atLineStart) {
                    sb.append('\n');
                }
                sb.append(text);
                sb.append('\n');
                st.atLineStart = true;
                st.pendingSpace = false;
                st.pendingComma = false;
            } else {
                if (st.pendingComma) {
                    st.pendingComma = false;
                } else {
                    st.flushSpace();
                }
                sb.append(text);
                st.pendingSpace = true;
                st.atLineStart = false;
            }
            st.prevText = text;
            st.prevType = type;
        }
        return sb.toString().trim();
    }

    /**
     * 竖向排列格式化（T-VStack）：INSERT 语句列清单与 VALUES 逐行竖排，VALUES 每值行尾按最宽值
     * （含行尾逗号）对齐追加 `-- 列名` 配对标注——大 INSERT 列-值对位不必数逗号。
     *
     * T-VStack·扩面：非 INSERT 语句 = {@link #format} 全展开 + expandHorizontalGroups 后处理
     * （IN 括号列表 / ORDER BY / GROUP BY / FROM 逗号联表纵向化），与「格式化」按钮产出可区分。
     *
     * 规则：
     *  - INSERT → 专用竖排路径；非 INSERT → format + 横向组展开
     *  - INSERT 列清单/VALUES 组各一元素一行（缩进 2、行尾逗号紧贴、闭括号顶格；批量多组 `),` 接排）
     *  - 配对标注：列清单全为单 token 元素才启用（`t.a` 等多 token 列不标注）；无列清单不标注；
     *    值按序与列取小对位，组内每行行尾注释只挂一个（用户注释优先于配对标注）；
     *    annotatePairs=false 时整项关闭（T-VPairAnno）——仅竖排不追加标注，用户自带注释不受影响
     *  - 值表达式整段保留：depth==1 逗号是唯一切分点，NOW()/CONCAT(a,b) 等嵌套括号内逗号原样
     *  - 组内注释挂行尾（本功能输出的 `-- 列名` 再输入时归位本行 → 幂等）；顶层注释自成一行；
     *    关闭标注后再输入已带标注文本，既有 `-- 列名` 按"用户行尾注释"规则归位保留（不剥离）
     *  - LIMIT 分页参数逗号与子查询 IN（元素首 token SELECT/WITH）不展开
     *  - 输出不变式：剔 COMMENT 后重分词的 token 流与原文一致——只动空白与行尾注释，不改语义 token
     */
    public static String formatVertical(String sql) {
        return formatVertical(sql, true);
    }

    /** 竖向排列格式化（annotatePairs=false 时仅竖排不追加配对标注，T-VPairAnno）。 */
    public static String formatVertical(String sql, boolean annotatePairs) {
        List<Pair<IElementType, String>> tokens = lex(sql);
        if (tokens.isEmpty()) {
            return sql.trim();
        }
        Pair<IElementType, String> first = null;
        for (Pair<IElementType, String> token : tokens) {
            if (token.getFirst() != SqlTokenTypes.COMMENT) {
                first = token;
                break;
            }
        }
        if (first == null) {
            return format(sql);
        }
        if (first.getFirst() != SqlTokenTypes.KEYWORD || !first.getSecond().toUpperCase().equals("INSERT")) {
            // T-PreviewUx-Pack #1：UPDATE → SET 各项行尾追加「列名 (new) = 表达式」标注（吸收 DBX
            // UPDATE 预览 (new) 列口径：SET 表达式原样搬——按旧值求值即得新值，自引用 a = a + 1 亦正确）。
            // 标注以行尾注释呈现（T-VPairAnno 同款范式），不破坏 SQL 可执行性；开关复用 annotatePairs
            //（verticalPairAnnotation 设置，竖排标注族同一开关）。
            if (first.getFirst() == SqlTokenTypes.KEYWORD && first.getSecond().toUpperCase().equals("UPDATE")) {
                return annotateUpdateNewColumns(expandHorizontalGroups(format(sql)), tokens, annotatePairs);
            }
            // T-VStack·扩面：非 INSERT = format 全展开 + 横向组展开后处理（IN 列表/ORDER BY/
            // GROUP BY/FROM 联表纵向化），与「格式化」按钮产出可区分
            return expandHorizontalGroups(format(sql));
        }

        // T-VPairAnno：关闭标注即空名单——renderGroup 的 elemIndex in columnNames.indices 恒假，
        // 标注追加与无注释行的对齐补齐同源归零（对齐只为标注竖向对齐服务），渲染主体零改动
        List<String> columnNames = annotatePairs ? collectInsertColumns(tokens) : Collections.emptyList();

        StringBuilder sb = new StringBuilder(sql.length() + 128);
        VerticalState st = new VerticalState(sb, columnNames);
        for (Pair<IElementType, String> entry : tokens) {
            IElementType type = entry.getFirst();
            String text = entry.getSecond();
            String upper = text.toUpperCase();
            if (type == SqlTokenTypes.COMMENT) {
                // 注释：组内挂当前行尾（本功能输出的 `-- 列名` 重输入归位）；顶层自成一行
                if (st.depth > 0) {
                    st.elemTrailing.add(text);
                } else {
                    if (!st.atLineStart) st.newline(0);
                    sb.append(text);
                    st.newline(0);
                }
            } else if (st.depth == 1 && type == SqlTokenTypes.OPERATOR && text.equals(",")) {
                // depth-1 逗号：元素边界（行尾逗号标记；后续注释仍归本行）
                st.elemHadComma = true;
            } else if (st.depth == 1 && type == SqlTokenTypes.PAREN && text.equals(")")) {
                // depth-1 闭括号：收行 + 整组渲染
                if (!st.elemTokens.isEmpty() || st.elemHadComma || !st.elemTrailing.isEmpty()) st.closeLine();
                st.renderGroup();
                st.depth = 0;
            } else if (st.depth > 0) {
                // 组内内容：depth-1 逗号后的首个语义 token 收行开新行；其余入元素（括号调 depth）
                if (st.elemHadComma) st.closeLine();
                st.elemTokens.add(new Pair<>(type, text));
                if (type == SqlTokenTypes.PAREN && text.equals("(")) st.depth++;
                if (type == SqlTokenTypes.PAREN && text.equals(")")) st.depth--;
            } else if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                // 顶层左括号：组开括号自占一行（列清单/VALUES 组）
                if (!st.atLineStart) st.newline(0);
                sb.append('(');
                st.atLineStart = false;
                st.pendingSpace = false;
                st.depth = 1;
                st.groupValues = st.seenValues;
                st.groupLines.clear();
                st.elemCounter = 0;
            } else if (type == SqlTokenTypes.KEYWORD && NEW_LINE_KEYWORDS.contains(upper)) {
                // 主子句关键字 → 顶格新行（首 token INSERT 顶格起）
                if (!st.atLineStart) st.newline(0);
                st.pendingSpace = false;
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
                if (upper.equals("VALUES")) st.seenValues = true;
            } else if (type == SqlTokenTypes.KEYWORD && (upper.equals("AND") || upper.equals("OR"))) {
                // AND/OR：顶层缩进换行（ON DUPLICATE KEY UPDATE 等尾缀条件）
                if (!st.atLineStart) st.newline(4);
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.KEYWORD) {
                st.flushSpace();
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(",")) {
                // 顶层逗号：批量 VALUES 组间分隔（`),` 后随组开括号换行）
                sb.append(',');
                st.atLineStart = false;
                st.pendingSpace = false;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(".")) {
                // 点号：无空格
                sb.append('.');
                st.pendingSpace = false;
                st.atLineStart = false;
            } else {
                // 其他运算符 / 标识符 / 字符串 / 数字 / 表名
                st.flushSpace();
                sb.append(text);
                st.atLineStart = false;
                st.pendingSpace = true;
            }
        }
        return sb.toString().trim();
    }

    /**
     * INSERT 列清单收集（配对名单）：VALUES 前首个 depth-0 括号组。
     * 元素恰为单 token（反引号列名是单 IDENTIFIER）才返回名单；多 token（`t.a`）/空组/括号不闭合 → 空（不标注）。
     */
    private static List<String> collectInsertColumns(List<Pair<IElementType, String>> tokens) {
        int depth = 0;
        boolean seenValues = false;
        for (int i = 0; i < tokens.size(); i++) {
            IElementType type = tokens.get(i).getFirst();
            String text = tokens.get(i).getSecond();
            if (type == SqlTokenTypes.COMMENT) continue;
            if (type == SqlTokenTypes.PAREN && text.equals("(") && depth == 0 && !seenValues) {
                List<List<Pair<IElementType, String>>> elements = new ArrayList<>();
                List<Pair<IElementType, String>> cur = new ArrayList<>();
                int d = 1;
                int j = i + 1;
                while (j < tokens.size() && d > 0) {
                    IElementType t2 = tokens.get(j).getFirst();
                    String x2 = tokens.get(j).getSecond();
                    if (t2 == SqlTokenTypes.COMMENT) {
                        // 注释不参与配对名单
                    } else if (t2 == SqlTokenTypes.PAREN && x2.equals("(")) {
                        d++;
                        cur.add(tokens.get(j));
                    } else if (t2 == SqlTokenTypes.PAREN && x2.equals(")")) {
                        d--;
                        if (d > 0) cur.add(tokens.get(j));
                    } else if (t2 == SqlTokenTypes.OPERATOR && x2.equals(",") && d == 1) {
                        elements.add(cur);
                        cur = new ArrayList<>();
                    } else {
                        cur.add(tokens.get(j));
                    }
                    j++;
                }
                boolean closed = d == 0;
                if (!cur.isEmpty()) elements.add(cur);
                boolean valid = closed && !elements.isEmpty();
                if (valid) {
                    for (List<Pair<IElementType, String>> element : elements) {
                        if (element.size() != 1) {
                            valid = false;
                            break;
                        }
                    }
                }
                if (!valid) return Collections.emptyList();
                List<String> names = new ArrayList<>();
                for (List<Pair<IElementType, String>> element : elements) {
                    names.add(element.get(0).getSecond());
                }
                return names;
            } else if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                depth++;
            } else if (type == SqlTokenTypes.PAREN && text.equals(")")) {
                depth--;
            } else if (type == SqlTokenTypes.KEYWORD && text.toUpperCase().equals("VALUES") && depth == 0) {
                seenValues = true;
            }
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------
    // T-PreviewUx-Pack #1：UPDATE 预览 (new) 列（吸收 DBX dml_preview_sql 纯函数口径）
    // ------------------------------------------------------------------

    /** SET 赋值项：列名（token 原文紧贴拼合）+ 表达式（token 渲染，运算符空格/函数括号紧贴）。 */
    public record UpdateSetAssignment(
            /** 列名（token 原文紧贴拼合）。 */
            String column,
            /** 表达式（token 渲染，运算符空格/函数括号紧贴）。 */
            String expression) {
    }

    /** SET 段终止关键字（顶层新主子句开始；沿用 NEW_LINE_KEYWORDS，剔除 SET 自身）。 */
    private static final Set<String> SET_TERMINAL_KEYWORDS = buildSetTerminalKeywords();

    private static Set<String> buildSetTerminalKeywords() {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(NEW_LINE_KEYWORDS);
        s.remove("SET");
        return Set.copyOf(s);
    }

    /**
     * 解析 UPDATE 语句 SET 子句 → 列名/表达式分离（纯函数，无 PSI，headless 可测）。
     *
     * <p>口径（DBX dml_preview_sql.rs）：SET 表达式<b>原样搬</b>——按旧行值求值即得新值，
     * 自引用（{@code a = a + 1}）原样即正确。顶层逗号切分各项，括号内逗号（函数参数/嵌套表达式）
     * 不切分；每项以首个顶层 {@code =} 分离列名与表达式；无 {@code =}/表达式为空的项跳过（不标注）。
     */
    public static List<UpdateSetAssignment> parseUpdateSetAssignments(List<Pair<IElementType, String>> tokens) {
        // 1) 定位 UPDATE 后首个顶层 SET（防表名恰为 set 的误匹配）
        int depth = 0;
        int setIdx = -1;
        boolean seenUpdate = false;
        int i = 0;
        while (i < tokens.size()) {
            IElementType type = tokens.get(i).getFirst();
            String text = tokens.get(i).getSecond();
            if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                depth++;
            } else if (type == SqlTokenTypes.PAREN && text.equals(")")) {
                depth--;
            } else if (type == SqlTokenTypes.KEYWORD && depth == 0) {
                String upper = text.toUpperCase();
                if (upper.equals("UPDATE") && !seenUpdate) {
                    seenUpdate = true;
                } else if (upper.equals("SET") && seenUpdate) {
                    setIdx = i;
                    break;
                }
            }
            i++;
        }
        if (setIdx < 0) return List.of();
        // 2) SET 段收集各项：顶层逗号切分，终止于下一个顶层主子句关键字
        List<List<Pair<IElementType, String>>> items = new ArrayList<>();
        List<Pair<IElementType, String>> cur = new ArrayList<>();
        depth = 0;
        int j = setIdx + 1;
        while (j < tokens.size()) {
            IElementType type = tokens.get(j).getFirst();
            String text = tokens.get(j).getSecond();
            if (type == SqlTokenTypes.COMMENT) {
                // 注释不参与切分（跳过）
            } else if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                depth++;
                cur.add(tokens.get(j));
            } else if (type == SqlTokenTypes.PAREN && text.equals(")")) {
                depth--;
                cur.add(tokens.get(j));
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(",") && depth == 0) {
                if (!cur.isEmpty()) items.add(cur);
                cur = new ArrayList<>();
            } else if (type == SqlTokenTypes.KEYWORD && depth == 0 && SET_TERMINAL_KEYWORDS.contains(text.toUpperCase())) {
                return finishUpdateSetItems(items, cur);
            } else {
                cur.add(tokens.get(j));
            }
            j++;
        }
        return finishUpdateSetItems(items, cur);
    }

    /** 各项按首个顶层 {@code =} 分离列名/表达式（无 {@code =}/表达式空的项跳过）。 */
    private static List<UpdateSetAssignment> finishUpdateSetItems(
            List<List<Pair<IElementType, String>>> items,
            List<Pair<IElementType, String>> tail) {
        List<List<Pair<IElementType, String>>> all = new ArrayList<>(items);
        if (!tail.isEmpty()) all.add(tail);
        List<UpdateSetAssignment> out = new ArrayList<>();
        for (List<Pair<IElementType, String>> item : all) {
            int eqIdx = -1;
            for (int k = 0; k < item.size(); k++) {
                if (item.get(k).getFirst() == SqlTokenTypes.OPERATOR && item.get(k).getSecond().equals("=")) {
                    eqIdx = k;
                    break;
                }
            }
            if (eqIdx <= 0) continue;
            List<Pair<IElementType, String>> exprTokens = item.subList(eqIdx + 1, item.size());
            if (exprTokens.isEmpty()) continue;
            out.add(new UpdateSetAssignment(
                    concatTokenText(item.subList(0, eqIdx)),
                    renderTokenText(exprTokens)));
        }
        return out;
    }

    /** token 原文紧贴拼合（列名形态：{@code age} / {@code t.a} / {@code `order`}）。 */
    private static String concatTokenText(List<Pair<IElementType, String>> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Pair<IElementType, String> token : tokens) {
            sb.append(token.getSecond());
        }
        return sb.toString();
    }

    /**
     * token 渲染为表达式文本（与 INSERT 组元素渲染同款空格规则：运算符两侧空格、点号紧贴、
     * 函数名后括号紧贴、逗号后空格）。
     */
    private static String renderTokenText(List<Pair<IElementType, String>> elem) {
        StringBuilder s = new StringBuilder();
        boolean space = false;
        String prev = "";
        for (Pair<IElementType, String> entry : elem) {
            String text = entry.getSecond();
            if (text.equals("(")) {
                if (!FUNCTION_NAMES.contains(prev.toUpperCase()) && space && s.length() > 0) s.append(' ');
                s.append('(');
                space = false;
            } else if (text.equals(")")) {
                s.append(')');
                space = true;
            } else if (text.equals(".")) {
                s.append('.');
                space = false;
            } else if (text.equals(",")) {
                s.append(',');
                space = true;
            } else {
                if (space && s.length() > 0) s.append(' ');
                s.append(text);
                space = true;
            }
            prev = text;
        }
        return s.toString();
    }

    /**
     * UPDATE 竖排产物追加 (new) 列标注：SET 段内匹配到 {@link #parseUpdateSetAssignments} 项的行，
     * 行尾追加 {@code -- 列名 (new) = 表达式}（T-VPairAnno 行尾标注同款范式，不破坏 SQL 可执行性）。
     * 幂等：已有同名 {@code (new)} 标注（行尾注释或独立注释行，format 会把行尾注释拆为独立行）不重复追加。
     */
    private static String annotateUpdateNewColumns(String formatted, List<Pair<IElementType, String>> tokens,
                                                   boolean annotate) {
        if (!annotate) return formatted;
        List<UpdateSetAssignment> assignments = parseUpdateSetAssignments(tokens);
        if (assignments.isEmpty()) return formatted;
        String[] lines = formatted.split("\n", -1);
        // 第一遍：全量收集已有 (new) 标注列名——format 会把行尾注释拆为独立行（位于其 SET 项行
        // 下方），逐行边收集边追加会漏掉下方注释导致重复标注
        java.util.HashSet<String> annotatedCols = new java.util.HashSet<>();
        for (String line : lines) {
            String annotated = existingNewColumnAnnotation(line.trim());
            if (annotated != null) annotatedCols.add(annotated);
        }
        // 第二遍：SET 段内匹配项追加行尾标注
        StringBuilder out = new StringBuilder(formatted.length() + 64);
        boolean inSetClause = false;
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            String trimmed = line.trim();
            List<Pair<IElementType, String>> lineTokens = lex(trimmed);
            Pair<IElementType, String> firstTok = lineTokens.isEmpty() ? null : lineTokens.get(0);
            if (firstTok != null && firstTok.getFirst() == SqlTokenTypes.KEYWORD) {
                String upper = firstTok.getSecond().toUpperCase();
                if (upper.equals("SET")) {
                    inSetClause = true;
                } else if (NEW_LINE_KEYWORDS.contains(upper)) {
                    inSetClause = false;
                }
            }
            String lineOut = line;
            if (inSetClause && !trimmed.isEmpty() && !trimmed.startsWith("--")) {
                int eqIdx = -1;
                for (int k = 0; k < lineTokens.size(); k++) {
                    if (lineTokens.get(k).getFirst() == SqlTokenTypes.OPERATOR
                            && lineTokens.get(k).getSecond().equals("=")) {
                        eqIdx = k;
                        break;
                    }
                }
                if (eqIdx > 0) {
                    String col = concatTokenText(lineTokens.subList(0, eqIdx));
                    UpdateSetAssignment assignment = null;
                    for (UpdateSetAssignment a : assignments) {
                        if (a.column().equals(col)) {
                            assignment = a;
                            break;
                        }
                    }
                    if (assignment != null && !annotatedCols.contains(col)) {
                        annotatedCols.add(col);
                        lineOut = line.stripTrailing() + "  -- " + col + " (new) = " + assignment.expression();
                    }
                }
            }
            if (idx > 0) out.append('\n');
            out.append(lineOut);
        }
        return out.toString();
    }

    /** 提取行内已有 {@code (new)} 标注的列名（{@code -- col (new) = ...}；无标注返回 null）。 */
    private static String existingNewColumnAnnotation(String trimmed) {
        int idx = trimmed.lastIndexOf("--");
        if (idx < 0) return null;
        String comment = trimmed.substring(idx + 2).trim();
        int newIdx = comment.indexOf("(new)");
        if (newIdx <= 0) return null;
        return comment.substring(0, newIdx).trim();
    }

    /**
     * 横向组展开（T-VStack·扩面）：对 {@link #format} 产物做纯文本后处理，把仍横排的组纵向化——
     * IN 括号列表（元素非子查询）/ ORDER BY 字段清单 / GROUP BY 字段清单 / FROM 逗号联表。
     * 只在「已被 format 排成单行」的子句段内操作，是纯增量变换 → 幂等；LIMIT 逗号（分页参数）
     * 与子查询 IN（元素首 token 为 SELECT）不展开。
     */
    private static String expandHorizontalGroups(String formatted) {
        StringBuilder out = new StringBuilder(formatted.length() + 128);
        int pos = 0;
        boolean changed = false;
        while (pos < formatted.length()) {
            int nl = formatted.indexOf('\n', pos);
            int lineEnd = nl < 0 ? formatted.length() : nl;
            String line = formatted.substring(pos, lineEnd);

            // 当前行缩进（展开元素行沿用同一缩进 + 4）
            StringBuilder indentBuilder = new StringBuilder();
            for (int i = 0; i < line.length() && line.charAt(i) == ' '; i++) {
                indentBuilder.append(' ');
            }
            String indent = indentBuilder.toString();
            String trimmed = line.trim();
            String expanded;
            if (trimmed.startsWith("--") || trimmed.startsWith("/*")) {
                expanded = null;
            } else {
                expanded = expandInList(line, trimmed, indent);
                if (expanded == null) expanded = expandCommaClause(line, trimmed, indent, "ORDER BY");
                if (expanded == null) expanded = expandCommaClause(line, trimmed, indent, "GROUP BY");
                if (expanded == null) expanded = expandFromCommaTables(line, trimmed, indent);
            }
            if (expanded == null) {
                out.append(line);
            } else {
                out.append(expanded);
                changed = true;
            }
            if (lineEnd < formatted.length()) out.append('\n');
            pos = lineEnd + 1;
        }
        return changed ? out.toString() : formatted;
    }

    /**
     * IN 列表展开：行含 `IN (` 且括号在本行闭合、首元素非子查询、含顶层（括号内层）逗号 → 逐元素一行
     * （`(` 自占一行，对齐已交付 INSERT 组形态）。line=原行（保前缀缩进）、trimmed=去缩进行（定位用）。
     */
    private static String expandInList(String line, String trimmed, String indent) {
        int inIdx = findKeywordIndex(trimmed, "IN");
        if (inIdx < 0) return null;
        int openIdx = trimmed.indexOf('(', inIdx + 2);
        if (openIdx < 0) return null;
        Integer closeBoxed = matchParen(trimmed, openIdx);
        if (closeBoxed == null) return null;
        int closeIdx = closeBoxed;
        String inner = trimmed.substring(openIdx + 1, closeIdx).trim();
        if (inner.isEmpty()) return null;
        // 子查询形态（IN (SELECT …)）不展开
        List<Pair<IElementType, String>> innerTokens = lex(inner);
        Pair<IElementType, String> first = null;
        for (Pair<IElementType, String> token : innerTokens) {
            if (token.getFirst() != SqlTokenTypes.COMMENT) {
                first = token;
                break;
            }
        }
        if (first == null) return null;
        if (first.getFirst() == SqlTokenTypes.KEYWORD
                && (first.getSecond().toUpperCase().equals("SELECT") || first.getSecond().toUpperCase().equals("WITH"))) {
            return null;
        }
        // 顶层（括号内层 depth1）逗号切分
        List<String> elements = splitTopLevel(inner);
        // T-VStack·InAll：含逗号即 ≥2 元素一律逐行展开（InThreshold 阈值经用户复议回退——
        // 短清单数逗号对位反成负担）；仅单元素（无逗号）保持同行——无清单可对齐，竖排纯花行高
        if (elements.size() < 2) return null;
        StringBuilder body = new StringBuilder();
        body.append(indent).append(trimmed.substring(0, openIdx).stripTrailing()).append('\n');
        body.append(indent).append('(').append('\n');
        for (int i = 0; i < elements.size(); i++) {
            body.append(indent).append("    ").append(elements.get(i).trim());
            if (i < elements.size() - 1) body.append(',');
            body.append('\n');
        }
        body.append(indent).append(')').append(trimmed.substring(closeIdx + 1).stripTrailing());
        return body.toString();
    }

    /** ORDER BY / GROUP BY 字段展开：`ORDER BY a DESC, b ASC` → 每字段一行（修饰符紧跟字段）。 */
    private static String expandCommaClause(String line, String trimmed, String indent, String keyword) {
        int kwIdx = findKeywordIndex(trimmed, keyword);
        if (kwIdx < 0) return null;
        String rest = trimmed.substring(kwIdx + keyword.length()).trim();
        if (rest.isEmpty() || rest.startsWith("(")) return null;
        List<String> elements = splitTopLevel(rest);
        if (elements.size() < 2) return null;
        StringBuilder body = new StringBuilder();
        body.append(indent).append(trimmed.substring(0, kwIdx)).append(keyword);
        for (int i = 0; i < elements.size(); i++) {
            body.append('\n').append(indent).append("    ").append(elements.get(i).trim());
            if (i < elements.size() - 1) body.append(',');
        }
        // 行尾残余校验：切分覆盖整个 rest——tail 含括号说明形态超出单行清单（不展开，避免误伤）
        int closeIdx = kwIdx + keyword.length() + trimmed.substring(kwIdx + keyword.length()).length();
        String tail = trimmed.substring(closeIdx);
        if (tail.contains("(") || tail.contains(")")) return null;
        return body.toString();
    }

    /** FROM 逗号联表展开：`FROM a, b` → 一表一行（含别名）。 */
    private static String expandFromCommaTables(String line, String trimmed, String indent) {
        int kwIdx = findKeywordIndex(trimmed, "FROM");
        if (kwIdx < 0) return null;
        String rest = trimmed.substring(kwIdx + "FROM".length()).trim();
        if (rest.isEmpty() || rest.contains("(")) return null;
        List<String> elements = splitTopLevel(rest);
        if (elements.size() < 2) return null;
        StringBuilder body = new StringBuilder();
        body.append(indent).append(trimmed.substring(0, kwIdx)).append("FROM");
        for (int i = 0; i < elements.size(); i++) {
            body.append('\n').append(indent).append("    ").append(elements.get(i).trim());
            if (i < elements.size() - 1) body.append(',');
        }
        return body.toString();
    }

    /** 关键字整词定位（大小写不敏感、两侧非标识符字符）。 */
    private static int findKeywordIndex(String line, String keyword) {
        int idx = 0;
        while (true) {
            idx = indexOfIgnoreCase(line, keyword, idx);
            if (idx < 0) return -1;
            boolean beforeOk = idx == 0
                    || (!Character.isLetterOrDigit(line.charAt(idx - 1)) && line.charAt(idx - 1) != '_' && line.charAt(idx - 1) != '.');
            int after = idx + keyword.length();
            boolean afterOk = after >= line.length()
                    || (!Character.isLetterOrDigit(line.charAt(after)) && line.charAt(after) != '_');
            if (beforeOk && afterOk) return idx;
            idx++;
        }
    }

    /** 忽略大小写的 indexOf（Kotlin {@code indexOf(string, startIndex, ignoreCase=true)} 等价）。 */
    private static int indexOfIgnoreCase(String line, String keyword, int fromIndex) {
        int limit = line.length() - keyword.length();
        for (int i = Math.max(fromIndex, 0); i <= limit; i++) {
            if (line.regionMatches(true, i, keyword, 0, keyword.length())) return i;
        }
        return -1;
    }

    /** 与 openIdx 的 `(` 配对的 `)` 下标（嵌套计数；字符串字面量内的括号不计数）。 */
    private static Integer matchParen(String line, int openIdx) {
        int depth = 0;
        int i = openIdx;
        boolean inString = false;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return null;
    }

    /** 顶层逗号切分（括号深度 0 的逗号；字符串字面量内逗号不切）。 */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
            } else if (!inString && c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** {@link #formatVertical} 的局部可变状态（等价 Kotlin 局部函数 + 闭包捕获）。 */
    private static final class VerticalState {
        final StringBuilder sb;
        final List<String> columnNames;
        int depth = 0;
        boolean pendingSpace = false;
        boolean atLineStart = true;
        boolean seenValues = false;
        /** 当前括号组是否 VALUES 组（配对标注只在 VALUES 组）。 */
        boolean groupValues = false;
        List<Pair<IElementType, String>> elemTokens = new ArrayList<>();
        boolean elemHadComma = false;
        final List<String> elemTrailing = new ArrayList<>();
        /** 组内元素序号（配对列名下标；纯注释行不占号）。 */
        int elemCounter = 0;
        final List<VerticalLine> groupLines = new ArrayList<>();

        VerticalState(StringBuilder sb, List<String> columnNames) {
            this.sb = sb;
            this.columnNames = columnNames;
        }

        void flushSpace() {
            if (pendingSpace && !atLineStart && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            pendingSpace = false;
        }

        void newline(int indent) {
            sb.append('\n');
            for (int i = 0; i < indent; i++) {
                sb.append(' ');
            }
            atLineStart = true;
            pendingSpace = false;
        }

        /** 元素 token 渲染（format 局部空格规则：运算符两侧空格、点号紧贴、函数名后括号紧贴）。 */
        static String renderElem(List<Pair<IElementType, String>> elem) {
            if (elem.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            boolean space = false;
            String prev = "";
            for (Pair<IElementType, String> entry : elem) {
                String text = entry.getSecond();
                if (text.equals("(")) {
                    if (!FUNCTION_NAMES.contains(prev.toUpperCase()) && space && out.length() > 0) out.append(' ');
                    out.append('(');
                    space = false;
                } else if (text.equals(")")) {
                    out.append(')');
                    space = true;
                } else if (text.equals(".")) {
                    out.append('.');
                    space = false;
                } else if (text.equals(",")) {
                    out.append(',');
                    space = true;
                } else {
                    if (space && out.length() > 0) out.append(' ');
                    out.append(text);
                    space = true;
                }
                prev = text;
            }
            return out.toString();
        }

        /** 收行入组缓冲（对齐宽依赖全组文本，闭组时才渲染）。 */
        void closeLine() {
            VerticalLine line = new VerticalLine();
            line.tokens.addAll(elemTokens);
            line.hadComma = elemHadComma;
            line.trailing.addAll(elemTrailing);
            if (!elemTokens.isEmpty()) {
                line.elemIndex = elemCounter;
                elemCounter++;
            }
            groupLines.add(line);
            elemTokens = new ArrayList<>();
            elemHadComma = false;
            elemTrailing.clear();
        }

        /** 闭组渲染：缩进 2 逐行 + 行尾逗号；VALUES 组可配对行按最宽前缀对齐追加 `-- 列名`（行尾注释优先）。 */
        void renderGroup() {
            List<String> rendered = new ArrayList<>();
            for (VerticalLine line : groupLines) {
                rendered.add(renderElem(line.tokens));
            }
            int alignWidth = 0;
            for (int i = 0; i < rendered.size(); i++) {
                String text = rendered.get(i);
                if (text.isEmpty()) continue;
                alignWidth = Math.max(alignWidth, text.length() + (groupLines.get(i).hadComma ? 1 : 0));
            }
            for (int i = 0; i < rendered.size(); i++) {
                String text = rendered.get(i);
                VerticalLine line = groupLines.get(i);
                sb.append('\n').append("  ").append(text);
                if (line.hadComma) sb.append(',');
                String trailingText = String.join(" ", line.trailing);
                // 配对标注只在 VALUES 组（列清单组不自我标注）；用户注释跨组通用且优先于配对标注
                if (!trailingText.isEmpty() || (groupValues && line.elemIndex >= 0 && line.elemIndex < columnNames.size())) {
                    // 补齐到最宽前缀（含行尾逗号）即可——分隔空格由 ` -- ` 前导空格保证，不再设下限
                    // （下限会把零补齐行整体推歪一格）
                    int prefixWidth = text.length() + (line.hadComma ? 1 : 0);
                    for (int pad = alignWidth - prefixWidth; pad > 0; pad--) {
                        sb.append(' ');
                    }
                    if (!trailingText.isEmpty()) {
                        sb.append(' ').append(trailingText);
                    } else {
                        sb.append(" -- ").append(columnNames.get(line.elemIndex));
                    }
                }
            }
            sb.append('\n').append(')');
            groupLines.clear();
            atLineStart = false;
            pendingSpace = true;
        }
    }

    /** 组内一行：元素 token（闭组时统一渲染）+ 行尾逗号标记 + 行尾注释；elemIndex 为配对下标。 */
    private static final class VerticalLine {
        final List<Pair<IElementType, String>> tokens = new ArrayList<>();
        boolean hadComma = false;
        final List<String> trailing = new ArrayList<>();
        int elemIndex = -1;
    }

    /** 分词（剔除空白——含 XML 动态标签展开残留空行）＋ 参数占位原子段合并（见 {@link #mergeParamPlaceholders}）。 */
    private static List<Pair<IElementType, String>> lex(String sql) {
        SqlLexer lexer = new SqlLexer();
        lexer.start(sql, 0, sql.length(), 0);
        List<Pair<IElementType, String>> tokens = new java.util.ArrayList<>();
        while (true) {
            IElementType type = lexer.getTokenType();
            if (type == null) {
                break;
            }
            if (type != TokenType.WHITE_SPACE) {
                tokens.add(new Pair<>(type, lexer.getTokenText()));
            }
            lexer.advance();
        }
        return mergeParamPlaceholders(tokens);
    }

    // ------------------------------------------------------------------
    // T-IbatisFix2（2026-11-10 真机反馈 ④）：参数占位原子段
    // ------------------------------------------------------------------

    /**
     * {@code #x#} / {@code #{x}} / {@code ${x}} 参数占位是<b>原子段</b>——三条格式化路径（format / formatClauseLine /
     * formatVertical）同源分词，一处合并三处生效。
     *
     * <p>iBatis 的 {@code #username#}（含 {@code #x:TYPE:null#} 三段）与 MyBatis 的 {@code #{x}} / {@code ${x}} 在 {@link SqlLexer}
     * 下被拆成 {@code #} + 标识符 + {@code #}（{@code {}/{}} 各为运算符、{@code $} 是标识符首字符）多个 token，格式化时按
     * 运算符空格规则拼出 {@code # username #} / {@code # { x }}（用户 2026.3 截图实证）——占位符必须整体保留：
     * 内容原样拼接（不做大小写改写、不插空格），前后按既有 token 间距规则走。</p>
     *
     * <p>合并判据（token 流上做，词法器不吐偏移）：</p>
     * <ul>
     * <li>{@code #}（运算符）后随 {@code {}（运算符）→ MyBatis 花括号形态，合并到配对 {@code }}（嵌套计数）；</li>
     * <li>{@code $}（单字符标识符）后随 {@code {} → MyBatis {@code ${}} 形态，同上；</li>
     * <li>{@code #}（运算符）→ 中间只含标识符/数字/{@code :} {@code [} {@code ]}（iBatis 的 {@code #x:TYPE:null#} 与 {@code #ids[0]#}）
     *     → 闭合 {@code #}：iBatis 内联参数；{@code ##} 转义（空内容）同样合并、原样保留。</li>
     * </ul>
     *
     * <p>闭合/配对找不到时<b>整段不动</b>（未闭合残段按原 token 展示，与渲染器「未闭合」告警口径一致）。
     * 合并产物标 {@link SqlTokenTypes#IDENTIFIER}——三条路径的「原文保留」分支（token 文本原样输出）。</p>
     */
    private static List<Pair<IElementType, String>> mergeParamPlaceholders(List<Pair<IElementType, String>> tokens) {
        boolean hasCandidate = false;
        for (Pair<IElementType, String> token : tokens) {
            IElementType type = token.getFirst();
            String text = token.getSecond();
            if ((type == SqlTokenTypes.OPERATOR && "#".equals(text))
                || ("$".equals(text) && (type == SqlTokenTypes.IDENTIFIER || type == SqlTokenTypes.TABLE))) {
                hasCandidate = true;
                break;
            }
        }
        if (!hasCandidate) {
            return tokens;
        }
        List<Pair<IElementType, String>> out = new java.util.ArrayList<>(tokens.size());
        int i = 0;
        while (i < tokens.size()) {
            IElementType type = tokens.get(i).getFirst();
            String text = tokens.get(i).getSecond();
            Integer mergedEnd = null;
            if (type == SqlTokenTypes.OPERATOR && "#".equals(text)) {
                mergedEnd = i + 1 < tokens.size() && "{".equals(tokens.get(i + 1).getSecond())
                    ? matchBraceClose(tokens, i + 1)
                    : matchHashClose(tokens, i);
            } else if ("$".equals(text) && (type == SqlTokenTypes.IDENTIFIER || type == SqlTokenTypes.TABLE)
                && i + 1 < tokens.size() && "{".equals(tokens.get(i + 1).getSecond())) {
                mergedEnd = matchBraceClose(tokens, i + 1);
            }
            if (mergedEnd == null) {
                out.add(tokens.get(i));
                i++;
            } else {
                StringBuilder merged = new StringBuilder();
                for (int j = i; j <= mergedEnd; j++) {
                    merged.append(tokens.get(j).getSecond());
                }
                out.add(new Pair<>(SqlTokenTypes.IDENTIFIER, merged.toString()));
                i = mergedEnd + 1;
            }
        }
        return out;
    }

    /** iBatis 内联参数闭合 {@code #} 定位（openIdx 是开 {@code #}；中间只许标识符/数字/{@code :} {@code [} {@code ]}）。未闭合返回 null。 */
    private static Integer matchHashClose(List<Pair<IElementType, String>> tokens, int openIdx) {
        for (int j = openIdx + 1; j < tokens.size(); j++) {
            IElementType type = tokens.get(j).getFirst();
            String text = tokens.get(j).getSecond();
            if (type == SqlTokenTypes.OPERATOR && "#".equals(text)) return j;
            boolean innerOperator = type == SqlTokenTypes.OPERATOR && HASH_INNER_OPERATORS.contains(text);
            if (type != SqlTokenTypes.IDENTIFIER && type != SqlTokenTypes.NUMBER && !innerOperator) return null;
        }
        return null;
    }

    /** MyBatis 花括号形态配对 {@code }} 定位（openIdx 是开 {@code {}；嵌套计数）。未闭合返回 null。 */
    private static Integer matchBraceClose(List<Pair<IElementType, String>> tokens, int openIdx) {
        int depth = 0;
        for (int j = openIdx; j < tokens.size(); j++) {
            IElementType type = tokens.get(j).getFirst();
            String text = tokens.get(j).getSecond();
            if (type != SqlTokenTypes.OPERATOR) continue;
            if ("{".equals(text)) depth++;
            if ("}".equals(text)) {
                depth--;
                if (depth == 0) return j;
            }
        }
        return null;
    }

    /** iBatis {@code #x#} 内部允许出现的运算符（三段 {@code :}、iterate 下标 {@code []}）。 */
    private static final Set<String> HASH_INNER_OPERATORS = Set.of(":", "[", "]");

    /** {@link #format} 的局部可变状态（等价 Kotlin 局部函数 + 闭包捕获）。 */
    private static final class FmtState {
        final StringBuilder sb;
        int depth = 0;
        boolean pendingSpace = false;
        boolean atLineStart = true;
        String prevText = "";
        // T-DdlLogPreview S2：DDL 域内判定「类型尺寸括号」（`VARCHAR(64)` 贴紧标识符）需前一 token 类型
        IElementType prevType = null;
        int lineIndent = 0;
        final ArrayDeque<Integer> parenIndents = new ArrayDeque<>();
        boolean parenNewlined = false;
        String currentClause = "";

        FmtState(StringBuilder sb) {
            this.sb = sb;
        }

        void flushSpace() {
            if (pendingSpace && !atLineStart && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            pendingSpace = false;
        }

        /** 换行 + 缩进（更新当前行缩进；括号内标记已换行）。 */
        void newline(int indent) {
            sb.append('\n');
            for (int i = 0; i < indent; i++) {
                sb.append(' ');
            }
            lineIndent = indent;
            atLineStart = true;
            pendingSpace = false;
            if (depth > 0) {
                parenNewlined = true;
            }
        }

        /** 子查询内新行缩进（起始行 + 4；顶层 0）。 */
        int subIndent() {
            return depth > 0 ? parenIndents.peekLast() + 4 : 0;
        }
    }

    /** {@link #formatClauseLine} 的局部可变状态。 */
    private static final class FmtClauseState {
        final StringBuilder sb;
        boolean pendingSpace = false;
        boolean atLineStart = true;
        String prevText = "";
        // T-DdlLogPreview S2：DDL 域内判定「类型尺寸括号」需前一 token 类型（本方法暂不使用，口径同 format）
        IElementType prevType = null;
        String currentClause = "";
        int depth = 0;
        boolean pendingComma = false;

        FmtClauseState(StringBuilder sb) {
            this.sb = sb;
        }

        void flushSpace() {
            if (pendingSpace && !atLineStart && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            pendingSpace = false;
        }

        /** 换行顶格（仅顶层触发；括号内不换行）。 */
        void newline() {
            sb.append('\n');
            atLineStart = true;
            pendingSpace = false;
        }
    }

    private SqlFormatter() {
    }
}
