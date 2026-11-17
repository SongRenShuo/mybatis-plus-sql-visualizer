package cn.song.mybatisplus.visualizer.xml;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * trim 节点（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.TrimSqlNode。
 *
 * 算法（FilteredDynamicContext.applyAll）：
 *  1. 子内容整体 trim()（去首尾空白含换行）；
 *  2. 裁剪后为空 → 什么都不输出（prefix/suffix 都不加）；
 *  3. 前缀：内容转大写，按 prefixOverrides（| 分隔，转大写）顺序 startsWith，命中第一个即停、
 *     按 toRemove.trim().length() 长度删除（大小写不敏感、空格/制表符为字面量）；
 *     prefix 追加 = 先插空格再插 prefix（prefix + " " + 内容，恒有一个空格）；
 *  4. 后缀：同理 endsWith 或 endsWith(trim 后)，只删第一个命中；suffix 前插空格后追加；
 *  5. bind / 唯一编号转发外层上下文（filtered 只拦截 appendSql 到自缓冲）。
 */
public non-sealed class TrimSqlNode implements SqlNode {

    private final SqlNode contents;
    private final String prefix;
    private final String suffix;
    private final List<String> prefixesToOverride;
    private final List<String> suffixesToOverride;

    public TrimSqlNode(
            SqlNode contents,
            String prefix,
            List<String> prefixesToOverride,
            String suffix,
            List<String> suffixesToOverride
    ) {
        this.contents = contents;
        this.prefix = prefix;
        this.suffix = suffix;
        this.prefixesToOverride = parseOverrides(prefixesToOverride);
        this.suffixesToOverride = parseOverrides(suffixesToOverride);
    }

    public SqlNode getContents() {
        return contents;
    }

    /** | 分隔 + 大写化（大小写不敏感匹配的载体）。 */
    private static List<String> parseOverrides(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String s : raw) {
            out.add(s.toUpperCase(Locale.ENGLISH));
        }
        return out;
    }

    @Override
    public boolean apply(DynamicContext context) {
        FilteredDynamicContext filtered = new FilteredDynamicContext(context);
        boolean result = contents.apply(filtered);
        filtered.applyAll();
        return result;
    }

    /** 子内容缓冲上下文：appendSql 攒入自缓冲；bind/唯一编号/条件覆盖转发外层。 */
    private final class FilteredDynamicContext extends DynamicContext {

        private final DynamicContext delegate;
        private final StringBuilder sqlBuffer = new StringBuilder();

        FilteredDynamicContext(DynamicContext delegate) {
            super(Map.of());
            this.delegate = delegate;
        }

        /**
         * 与 MyBatis 的 {@code sqlBuffer.append(sql)} 有一处刻意差异（T-MbgExamplePreview R2）：
         * <b>且仅当</b>粘连会拼出一个词（左侧收尾是操作数、右侧起笔是标识符字符）时补一个空格。
         *
         * <p>理由：本引擎的 foreach 会把每轮迭代 trim 成紧凑段（T-M25.5），两次迭代的 {@code and}
         * 在此处直接相接就成了单词 {@code andand}，{@code <trim prefixOverrides="and">} 只裁掉首个，
         * 残余成了括号里的幽灵条件。真实 MyBatis 因 XML 换行保留空白而不会粘词。
         *
         * <p>收窄到「会拼成词」是必须的：R2 首版对任何「两侧都无空白」的相邻段一律补空格，
         * 于是 foreach 的 {@code open}/{@code separator}/{@code close}（{@code (}、{@code ,}、{@code )}）
         * 与 {@code ?} 之间也被撑开，{@code <where>} 里的 {@code IN (?,?)} 变成 {@code IN ( ? , ? )}——
         * 而真实 MyBatis 的 trim 缓冲是裸 append（其根上下文才按 StringJoiner(" ") 补空格），
         * 该形态本应保持紧凑。判据只取「左收尾 ∈ {标识符字符, {@code )}, {@code ]}, {@code ?}, 引号} 且
         * 右起笔 = 标识符字符」，故 {@code (}/{@code ,} 之后的段、以及右起笔为标点的段一律不补——
         * {@code IN (?,?)}/{@code IN (1,2,4,5)} 原样保留。
         */
        @Override
        public void appendSql(String sql) {
            if (sql.isEmpty()) {
                return;
            }
            if (sqlBuffer.length() > 0 && needsSeparator(sqlBuffer.charAt(sqlBuffer.length() - 1), sql.charAt(0))) {
                sqlBuffer.append(' ');
            }
            sqlBuffer.append(sql);
        }

        /**
         * 两个相邻段之间是否需要一个空格。
         *
         * <ol>
         * <li><b>粘连成词才补</b>（6212f24e0 收窄后的口径，也是 {@code IN (?,?)} 保持紧凑的约束）：
         *     左侧以操作数收尾且右侧以标识符起笔时补，否则不补。</li>
         * <li><b>操作符与左括号之间补</b>（T-MbgPreviewParityAndOpenPerf P2）：{@code = (} 而不是 {@code =(}。
         *     只认「左侧是操作符字符」这一种情形，所以 {@code count(*)}（左为标识符）、{@code ( ?}
         *     （左为左括号）都不受影响——MBG 的 {@code and ${condition}} 与内层 {@code <foreach open="(">}
         *     会被撑成 {@code CUSTOMERNUMBER = (?)}，与真实 MyBatis 保留 XML 换行的形态一致。</li>
         * </ol>
         */
        private static boolean needsSeparator(char last, char first) {
            return (endsOperand(last) && startsIdentifier(first))
                    || (OPERATOR_CHARS.indexOf(last) >= 0 && first == '(');
        }

        /** 运算符字符（{@code = < > ! + - * / % | & ^}：其后紧跟 {@code (} 属于「条件 + 列表/分组」形态）。 */
        private static final String OPERATOR_CHARS = "=<>!+-*/%|&^";

        /** 标识符字符（{@code \w} 面 + {@code $}：列名/关键字/数字，粘连即拼成一个词）。 */
        private static boolean startsIdentifier(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }

        /**
         * 左侧是否以「操作数」收尾——其后紧跟标识符就需要一个空格。
         * 含右括号/右方括号/引号（子表达式、字面量、反引号标识符）、绑定占位 {@code ?}，
         * 以及 {@code }}（T-MbgPreviewParityAndOpenPerf R1）。
         *
         * <p><b>{@code }} 为什么必须在</b>：本缓冲里放的是<b>渲染前文本</b>——{@code #{criterion.value}}
         * 的收尾是 {@code }}，而 {@code #{} → {@code ?} 的替换发生在渲染最后一步
         * （{@code XmlSqlRenderer.parseParameterMappings} 扫全文），所以判据里那个 {@code ?} 在这一层
         * <b>永远不可能出现</b>。漏掉 {@code }} 时两轮 foreach 迭代会拼成 {@code ?and}
         * （真跑不了的错 SQL），比 {@code andand} 更隐蔽。
         *
         * <p>左括号、逗号、运算符、空白仍不在内：它们本身就是分隔符，补空格是多余噪音。
         */
        private static boolean endsOperand(char c) {
            return startsIdentifier(c) || c == ')' || c == ']' || c == '}' || c == '?'
                    || c == '\'' || c == '"' || c == '`';
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public void countDollarSubstitution() {
            delegate.countDollarSubstitution();
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }

        @Override
        public Boolean conditionResult(String test) {
            return delegate.conditionResult(test);
        }

        @Override
        public boolean isProtected(String name) {
            return delegate.isProtected(name);
        }

        @Override
        public Map<String, Object> getStaticFieldValues() {
            return delegate.getStaticFieldValues();
        }

        // T-MbgExamplePreview R1：占位模式必须转发——否则 trim/where/set 体内的 `${}` 仍按空串输出
        @Override
        public boolean isEmptyDollarPlaceholder() {
            return delegate.isEmptyDollarPlaceholder();
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        // T-FS-07：errors 必须转发——否则 where/trim/set 体内 `<if test="非法表达式">` 的求值失败被丢弃
        // （与 Foreach 的 Prefixed/Filtered 同一缺陷形态，本次一并收口）
        @Override
        public List<String> getErrors() {
            return delegate.getErrors();
        }

        // R30 探镜：体内条件判定结果转发根上下文（同 errors 的转发缺陷形态，缺席即丢）
        @Override
        public Map<String, Boolean> getConditionOutcomes() {
            return delegate.getConditionOutcomes();
        }

        void applyAll() {
            String sql = sqlBuffer.toString().trim();
            String upper = sql.toUpperCase(Locale.ENGLISH);
            if (!upper.isEmpty()) {
                String p = applyPrefix(sql, upper);
                if (p != null) {
                    sql = p;
                }
                String s = applySuffix(sql, upper);
                if (s != null) {
                    sql = s;
                }
            }
            delegate.appendSql(sql);
        }

        private String applyPrefix(String sql, String upper) {
            String result = sql;
            for (String toRemove : prefixesToOverride) {
                if (upper.startsWith(toRemove)) {
                    result = result.substring(toRemove.trim().length());
                    break;
                }
            }
            return prefix != null ? prefix + " " + result : result;
        }

        private String applySuffix(String sql, String upper) {
            String result = sql;
            for (String toRemove : suffixesToOverride) {
                if (upper.endsWith(toRemove) || upper.endsWith(toRemove.trim())) {
                    result = result.substring(0, result.length() - toRemove.trim().length());
                    break;
                }
            }
            return suffix != null ? result + " " + suffix : result;
        }
    }
}
