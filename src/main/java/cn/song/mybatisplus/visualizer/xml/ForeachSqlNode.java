package cn.song.mybatisplus.visualizer.xml;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * foreach 节点（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.ForEachSqlNode。
 *
 * 关键语义：
 * - collection 求值为 null/空 → 整段输出空串（open/close 都不输出）；
 *   null 且 nullable=false（默认）→ 错误记录（对齐 BuilderException 场景，预览不中断）；
 * - 拼接顺序：open + (每项: [separator] + 项内容) + close；separator 由 PrefixedContext 惰性插入；
 * - Map 迭代：index = 键、item = 值；
 * - 变量双写：裸名 + __frch_<名>_<唯一编号>（FilteredContext 把 #{item.x} 重写为 #{__frch_item_0.x}）；
 * - 循环结束清理裸 item/index（__frch_* 保留，供参数对照）。
 */
public final class ForeachSqlNode implements SqlNode {

    private final SqlNode contents;
    private final String collectionExpression;
    private final String separator;
    private final String open;
    private final String close;
    private final String item;
    private final String index;
    private final boolean nullable;

    public ForeachSqlNode(
            SqlNode contents,
            String collectionExpression,
            String separator,
            String open,
            String close,
            String item,
            String index,
            boolean nullable
    ) {
        this.contents = contents;
        this.collectionExpression = collectionExpression;
        this.separator = separator;
        this.open = open;
        this.close = close;
        this.item = item;
        this.index = index;
        this.nullable = nullable;
    }

    public SqlNode getContents() {
        return contents;
    }

    public String getCollectionExpression() {
        return collectionExpression;
    }

    public String getItem() {
        return item;
    }

    public String getIndex() {
        return index;
    }

    public String getOpen() {
        return open;
    }

    public String getClose() {
        return close;
    }

    public String getSeparator() {
        return separator;
    }

    @Override
    public boolean apply(DynamicContext context) {
        Map<String, Object> bindings = context.getBindings();
        Iterable<?> iterable = evaluateIterable(context);
        if (iterable == null) {
            return true;
        }
        if (!iterable.iterator().hasNext()) {
            return true;
        }
        boolean first = true;
        if (open != null) {
            context.appendSql(open);
        }
        int i = 0;
        for (Object o : iterable) {
            PrefixedContext scoped = new PrefixedContext(context, (first || separator == null) ? "" : separator);
            int uniqueNumber = scoped.getUniqueNumber();
            if (o instanceof Map.Entry<?, ?> entry) {
                applyIndex(scoped, entry.getKey(), uniqueNumber);
                applyItem(scoped, entry.getValue(), uniqueNumber);
            } else {
                applyIndex(scoped, i, uniqueNumber);
                applyItem(scoped, o, uniqueNumber);
            }
            contents.apply(new FilteredContext(scoped, index, item, uniqueNumber));
            if (first) {
                first = !scoped.hasContent();
            }
            i++;
        }
        if (close != null) {
            context.appendSql(close);
        }
        if (item != null) {
            bindings.remove(item);
        }
        if (index != null) {
            bindings.remove(index);
        }
        return true;
    }

    /**
     * collection 求值（预览适配）：bindings 中表达式全文精确命中（面板 mock 值）优先；
     * 否则 OGNL 求值 → Iterable / 数组 / Map(entrySet)；null → nullable 时 null，否则错误记录。
     */
    private Iterable<?> evaluateIterable(DynamicContext context) {
        Object direct = context.getBindings().get(collectionExpression);
        Object value = direct;
        if (direct == null) {
            EvalResult r = OgnlEvaluator.evaluate(collectionExpression, context.getBindings(), context.getStaticFieldValues());
            if (r instanceof EvalResult.Ok ok) {
                value = ok.value();
            } else {
                EvalResult.Err err = (EvalResult.Err) r;
                context.getErrors().add("无法解析 foreach collection '" + collectionExpression + "'（" + err.message() + "）");
                return null;
            }
        }
        if (value == null) {
            if (nullable) {
                return null;
            }
            // T-MbgExamplePreview R6：原文是 MyBatis 英文异常文案，中文 UI 直接透传读不懂；
            // 改成中文并给可操作指引（集合为 null 多半是面板勾了「为 null」）
            context.getErrors().add("foreach 集合 '" + collectionExpression + "' 求值为 null"
                    + "（模拟参数没给这个集合；若真实调用中它必有值，请取消该集合的『为 null』勾选）");
            return null;
        }
        if (value instanceof Iterable<?>) {
            return (Iterable<?>) value;
        }
        if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        if (value instanceof Map<?, ?>) {
            return ((Map<?, ?>) value).entrySet();
        }
        context.getErrors().add("foreach 集合 '" + collectionExpression + "' 的求值结果不可迭代"
                + "（" + (value == null ? "null" : value.getClass().getSimpleName()) + "）");
        return null;
    }

    private void applyIndex(DynamicContext context, Object o, int i) {
        if (index != null) {
            context.bind(index, o);
            context.bind(itemizeItem(index, i), o);
        }
    }

    private void applyItem(DynamicContext context, Object o, int i) {
        if (item != null) {
            context.bind(item, o);
            context.bind(itemizeItem(item, i), o);
        }
    }

    private static String itemizeItem(String item, int i) {
        return "__frch_" + item + "_" + i;
    }

    /** separator 惰性插入上下文：首段有实际内容时才插入 prefix（即 separator），其后原样转发。 */
    private static final class PrefixedContext extends DynamicContext {

        private final DynamicContext delegate;
        private final String prefix;
        private boolean prefixApplied = false;

        PrefixedContext(DynamicContext delegate, String prefix) {
            super(Map.of());
            this.delegate = delegate;
            this.prefix = prefix;
        }

        @Override
        public void appendSql(String sql) {
            if (!prefixApplied && !sql.isBlank()) {
                // D7：单词分隔符（`or` / `and` / `union all`）后补一个空格——否则与下一段的
                // 起始 `(` 粘成 `or(`（真机 `WHERE ( a ) or( b )`）。标点分隔符（`,`）保持原样，
                // `IN (?,?)` 的紧凑形态不动。
                String sep = !prefix.isEmpty() && Character.isLetterOrDigit(prefix.charAt(prefix.length() - 1))
                        ? prefix + " " : prefix;
                delegate.appendSql(sep);
                prefixApplied = true;
            }
            delegate.appendSql(sql);
        }

        /** 本轮迭代是否产出过内容（separator 顺延判据，见 {@link ForeachSqlNode#apply}）。 */
        boolean hasContent() {
            return prefixApplied;
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

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        // T-FS-07：errors 必须转发——否则体内 `<if test="非法表达式">` 的求值失败写进本层临时列表后被丢弃
        @Override
        public List<String> getErrors() {
            return delegate.getErrors();
        }

        // R30 探镜：体内条件判定结果转发根上下文（同 errors 的转发缺陷形态，缺席即丢）
        @Override
        public Map<String, Boolean> getConditionOutcomes() {
            return delegate.getConditionOutcomes();
        }

        // T-MbgExamplePreview R1：占位模式必须转发——否则 foreach 体内的 ${} 仍按空串输出
        @Override
        public boolean getEmptyDollarPlaceholder() {
            return delegate.getEmptyDollarPlaceholder();
        }
    }

    /** #{item.x} / #{index.x} 重写上下文：把 token 内容开头的 item/index 名重写为 __frch_ 唯一名。 */
    private static final class FilteredContext extends DynamicContext {

        private final DynamicContext delegate;
        private final String index;
        private final String item;
        private final int uniqueNumber;
        private final Pattern itemPattern;
        private final Pattern indexPattern;

        FilteredContext(DynamicContext delegate, String index, String item, int uniqueNumber) {
            super(Map.of());
            this.delegate = delegate;
            this.index = index;
            this.item = item;
            this.uniqueNumber = uniqueNumber;
            this.itemPattern = item != null ? Pattern.compile("^\\s*" + Pattern.quote(item) + "(?![^.,:\\s])") : null;
            this.indexPattern = index != null ? Pattern.compile("^\\s*" + Pattern.quote(index) + "(?![^.,:\\s])") : null;
        }

        @Override
        public void appendSql(String sql) {
            // 逐 token 判断（对齐 MyBatis：item 改写未命中该 token 时才尝试 index 改写）
            String newSql = TokenParser.parse(sql, "#{", "}", content -> {
                String newContent = content;
                if (itemPattern != null) {
                    newContent = itemPattern.matcher(newContent).replaceFirst("__frch_" + item + "_" + uniqueNumber);
                }
                if (newContent.equals(content) && indexPattern != null) {
                    newContent = indexPattern.matcher(newContent).replaceFirst("__frch_" + index + "_" + uniqueNumber);
                }
                return "#{" + newContent + "}";
            });
            // 迭代内容 trim——mapper 里 foreach 体的换行缩进原样输出会使 IN 每项换行一行；
            // trim 后预览紧凑（SQL 语义不变，MyBatis 原样保留）
            //
            // T-MbgExamplePreview R2：段间分隔改由下游缓冲负责（DynamicContext.appendSql 恒补空格；
            // TrimSqlNode 的 filtered 缓冲仅在粘连会拼成词时补）——此处再加空格会叠加成双空格。
            delegate.appendSql(newSql.trim());
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

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        // T-FS-07：同上（本层 delegate 是 PrefixedContext，逐级转发到根上下文）
        @Override
        public List<String> getErrors() {
            return delegate.getErrors();
        }

        // R30 探镜：逐级转发（本层 delegate 是 PrefixedContext，最终落根上下文）
        @Override
        public Map<String, Boolean> getConditionOutcomes() {
            return delegate.getConditionOutcomes();
        }

        // T-MbgExamplePreview R1：占位模式必须转发——否则 foreach 体内的 ${} 仍按空串输出
        @Override
        public boolean getEmptyDollarPlaceholder() {
            return delegate.getEmptyDollarPlaceholder();
        }
    }
}
