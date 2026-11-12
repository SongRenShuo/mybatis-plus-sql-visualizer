package cn.song.mybatisplus.visualizer.xml;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 渲染上下文（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.DynamicContext。
 *
 * - bindings：变量上下文（初始 _parameter / _databaseId；bind / foreach 变量写入）
 * - appendSql：段间以单个空格分隔（对齐 3.5.13+ 的 StringJoiner(" ") 行为），最终 getSql() 去首尾空白
 * - conditionOverrides：条件开关覆盖（预览面板直接决定 if/when 真假，id = test 表达式原文）
 * - protectedBindNames：bind 覆盖名（面板输入的覆盖值优先于表达式求值）
 * - errors：渲染期错误（判重表，嵌套子上下文须转发 delegate，否则内层错误静默）
 *
 * 预览引擎专用扩展（conditionOverrides / protectedBindNames / errors）不影响运行时复刻语义。
 */
public class DynamicContext {

    /** _parameter 键。 */
    public static final String PARAMETER_OBJECT_KEY = "_parameter";

    /** _databaseId 键。 */
    public static final String DATABASE_ID_KEY = "_databaseId";

    private final Map<String, Boolean> conditionOverrides;
    private final Set<String> protectedBindNames;
    private final Map<String, Object> staticFieldValues;

    /**
     * 变量上下文（_parameter / _databaseId / bind 变量 / foreach item·index / 模拟值）。
     *
     * 注意：用属性初始化器而非 init 块填充——子类（Trim/Foreach 的过滤上下文）覆写本属性
     * 转发 delegate，父类 init 块中的调用会虚分派到未初始化的子类字段（NPE）。
     */
    private Map<String, Object> bindings;

    /**
     * 渲染期错误提示（如 foreach collection 为 null 且 nullable=false、表达式解析失败）。
     *
     * <p>T-FS-07：①判重表——{@code <foreach>} 逐迭代会把同一表达式同一原因重复登记 N 次，
     * 用户看到的 {@code ⚠} 警示行（{@code errors.joinToString("；")}）不该被同一句话刷屏，故同文案只留首现一行
     * （判重键 = 完整错误文案：9 个写入点里 8 个的文案内嵌表达式原文 + 原因，天然等价于「同表达式同原因」；
     * 例外是 {@link IfSqlNode} 的「bind 表达式被 {@code ||} 包裹」提示，文案固定不含表达式名——同一语句里两条不同的
     * {@code ||X||} bind 会并成一行，只丢「条数」不丢可区分信息，属可接受边界）；
     * ②子上下文（Foreach 的 Prefixed/Filtered、Trim 的 filtered）覆写 {@link #getErrors()} 转发 delegate——
     * 此前各自持有独立列表，内层 {@code <if>} 求值失败被丢弃（静默，与 ${}/bind/顶层 if 形态不一致）。
     */
    private final List<String> errors = new DedupErrorList();

    /**
     * 条件求值结果登记（R30 探镜，只读观测——渲染语义零变更）：IfSqlNode 每次被走到（apply）
     * 就登记 test 表达式原文 → 本轮判定真值；渲染中<b>未到达</b>的条件（假 if 体内层 / choose 短路后的
     * 其余 when）不登记，模型侧据缺席映射「未求值」（见 {@link XmlPreviewModel#getConditionOutcomes}）。
     *
     * <p>同表达式多次求值（foreach 体内）按「任一次为真即真」合并（merge OR）——探针回答的是
     * 「这一轮这条条件有没有放行内容进 SQL」。子上下文（Trim/Foreach 的过滤上下文）覆写
     * {@link #getConditionOutcomes()} 转发 delegate（同 {@link #getErrors()} 的 T-FS-07 缺陷形态，
     * 否则体内条件的判定结果被丢弃）。
     */
    private final Map<String, Boolean> conditionOutcomes = new LinkedHashMap<>();

    /**
     * 空值占位模式（T-MbgExamplePreview R1）：{@code ${}} 求值为 null 且表达式含变量引用时，输出
     * {@code {{表达式}}} 而不是空串——缺段在产物里可见，不会被误读成「插件把 SQL 识别错了」。
     *
     * <p>默认 false：真实 SQL 产物与全仓既有断言零影响；只有预览渲染链
     * （{@code XmlPreviewModel.renderSql}）开启。</p>
     */
    private final boolean emptyDollarPlaceholder;

    /**
     * ${} 直替计数（T-DollarInjectMark）：TextSqlNode 每求值一个 ${} token 经
     * {@link #countDollarSubstitution()} 自增——引擎忠实口径（MyBatis 对字符串字面量内的 ${}
     * 同样直替，计数不排除）；转义 \${ 不经求值不计。渲染结束随
     * RenderedXmlSql.dollarSubstitutions 出栈，预览横幅消费。
     */
    private int dollarSubstitutions = 0;

    private final StringBuilder sqlBuilder = new StringBuilder();
    private int uniqueNumber = 0;

    public DynamicContext(Map<String, Object> initialBindings) {
        this(initialBindings, Map.of(), Set.of(), Map.of());
    }

    public DynamicContext(
            Map<String, Object> initialBindings,
            Map<String, Boolean> conditionOverrides,
            Set<String> protectedBindNames,
            Map<String, Object> staticFieldValues
    ) {
        this(initialBindings, conditionOverrides, protectedBindNames, staticFieldValues, false);
    }

    public DynamicContext(
            Map<String, Object> initialBindings,
            Map<String, Boolean> conditionOverrides,
            Set<String> protectedBindNames,
            Map<String, Object> staticFieldValues,
            boolean emptyDollarPlaceholder
    ) {
        this.conditionOverrides = conditionOverrides;
        this.protectedBindNames = protectedBindNames;
        this.staticFieldValues = staticFieldValues;
        this.emptyDollarPlaceholder = emptyDollarPlaceholder;
        this.bindings = new HashMap<>(initialBindings);
    }

    /** 变量上下文。 */
    public Map<String, Object> getBindings() {
        return bindings;
    }

    /**
     * 渲染期错误列表（判重表，T-FS-07）。
     *
     * <p>可覆写：嵌套子上下文（Foreach 的 Prefixed/Filtered、Trim 的 filtered）必须转发到 delegate
     * 根上下文，否则内层求值失败写进本层临时列表后被丢弃。
     */
    public List<String> getErrors() {
        return errors;
    }

    /** ${} 直替计数（T-DollarInjectMark，Kotlin {@code protected set}：外部只读，经计数入口自增）。 */
    public int getDollarSubstitutions() {
        return dollarSubstitutions;
    }

    /**
     * 条件求值结果（R30 探镜，只读观测）。
     *
     * <p>可覆写：嵌套子上下文（Foreach 的 Prefixed/Filtered、Trim 的 filtered）必须转发到 delegate
     * 根上下文，否则体内条件的判定结果被丢弃（与 {@link #getErrors()} 同款转发缺陷形态）。
     */
    public Map<String, Boolean> getConditionOutcomes() {
        return conditionOutcomes;
    }

    /** 条件判定登记入口：同表达式多次求值按 OR 合并（{@link #getConditionOutcomes()}）。 */
    public void recordConditionOutcome(String test, boolean truth) {
        // 经 getter 写入（而非私有字段直写）：子上下文覆写 getConditionOutcomes() 转发 delegate 时
        // 判定结果才落到根上下文——对齐 Kotlin `open val conditionOutcomes` 的虚分派语义。
        getConditionOutcomes().merge(test, truth, (previous, current) -> previous || current);
    }

    /** 直替计数入口：子类过滤上下文（Foreach 的 Prefixed/Filtered）转发到 delegate，计数落根上下文。 */
    public void countDollarSubstitution() {
        dollarSubstitutions++;
    }

    /** 追加 SQL 段（段间单个空格；空段不产生分隔）。 */
    public void appendSql(String sql) {
        if (sql.isEmpty()) {
            return;
        }
        if (sqlBuilder.length() > 0) {
            sqlBuilder.append(' ');
        }
        sqlBuilder.append(sql);
    }

    /** 当前累积 SQL（首尾空白已去除）。 */
    public String getSql() {
        return sqlBuilder.toString().trim();
    }

    /** 绑定变量（bind / foreach item·index）。 */
    public void bind(String name, Object value) {
        getBindings().put(name, value);
    }

    /** 每次调用自增的唯一编号（foreach 的 __frch_<名>_<n> 用）。 */
    public int getUniqueNumber() {
        return uniqueNumber++;
    }

    /** 条件开关覆盖：test 表达式原文 → 强制真值；null 表示无覆盖（走表达式求值）。 */
    public Boolean conditionResult(String test) {
        return conditionOverrides.get(test);
    }

    /** bind 名是否受保护（面板覆盖值优先，VarDeclSqlNode 跳过求值）。 */
    public boolean isProtected(String name) {
        return protectedBindNames.contains(name);
    }

    /** 静态字段值（T-M25.2）：@class@field → 值。 */
    public Map<String, Object> getStaticFieldValues() {
        return staticFieldValues;
    }

    /**
     * 空值占位模式（T-MbgExamplePreview R1，master 侧 {@code open val}——可覆写转发）：
     * true 时 {@code ${}} 求值为 null 且含变量引用输出 {@code {{表达式}}} 占位。
     */
    public boolean getEmptyDollarPlaceholder() {
        return emptyDollarPlaceholder;
    }

    /**
     * 判重错误表（T-FS-07）：保首现顺序、同文案只留一行的 {@link List}。
     *
     * <p>只用于 {@link DynamicContext#getErrors()}（MyBatis 无此概念，属预览引擎专用扩展）；保留 List 形态
     * 使既有写入点（{@code context.errors += msg}）与既有测试无需改动。{@code add(index, element)} 忽略索引——
     * 判重集合无插入位置语义，仓内既有写入只有 {@code +=} / {@code add(msg)} 两种形态。
     *
     * <p><b>T-PerfSweep6 T-PS6-08（性能报告发现 10）：列表 + 判重集合双结构</b>。此前用 LinkedHashSet 单结构
     * 冒充 List，{@code get(index)} 走逐节点前进的 <b>O(n)</b>；而 {@code AbstractList}
     * 的 {@code equals}/{@code hashCode}/{@code subList}/{@code indexOf} 与默认 listIterator 实现都按 index 遍历
     * ——一次 {@code errors == other} 比较就是 O(n²)。现改为 items（ArrayList，承担顺序与随机访问）+
     * seen（HashSet，承担 O(1) 判重），两个结构在所有写点同步维护。
     *
     * <p><b>仍不支持位置型写入语义</b>（T-FS-07 复核注记沿用）：{@code add(index, element)} 忽略索引、
     * {@code set(index, element)} 把新值挪到末尾（且新值已存在时会缩容）。当前仓内对 {@code .errors} 无任何位置型
     * 调用点，故无实际后果；需要位置语义时应改用普通 ArrayList，而不是改本类。
     * modCount 沿用既有形态（不自增）：本类不是并发容器，仓内也无「边遍历边写」的调用点。
     */
    private static final class DedupErrorList extends AbstractList<String> {

        /** 首现顺序 + 随机访问（get O(1)）。 */
        private final List<String> items = new ArrayList<>();

        /** 判重集合（与 items 严格同集，所有写点成对维护）。 */
        private final Set<String> seen = new HashSet<>();

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public String get(int index) {
            return items.get(index);
        }

        @Override
        public void add(int index, String element) {
            if (seen.add(element)) {
                items.add(element);
            }
        }

        @Override
        public String remove(int index) {
            String removed = items.remove(index);
            seen.remove(removed);
            return removed;
        }

        /** 判重集合无「按位替换」语义：旧值移除、新值追加到末尾（仓内无调用点，仅为满足 List 契约）。 */
        @Override
        public String set(int index, String element) {
            String previous = get(index);
            if (!previous.equals(element)) {
                remove(index); // 旧值出表（两结构同步）
                if (seen.add(element)) {
                    items.add(element);
                }
            }
            return previous;
        }

        @Override
        public boolean add(String element) {
            if (!seen.add(element)) {
                return false;
            }
            items.add(element);
            return true;
        }

        @Override
        public void clear() {
            items.clear();
            seen.clear();
        }

        @Override
        public boolean contains(Object element) {
            return seen.contains(element);
        }

        @Override
        public int indexOf(Object element) {
            return items.indexOf(element);
        }

        /**
         * 迭代器（T-Round10 独立复核 ㉚ 订正）：<b>不再直接返回 items.iterator()</b>。
         *
         * <p>那个 ArrayList 迭代器的 {@code remove()} 只从 items 移除、<b>不动 seen</b>，于是删掉的元素
         * {@code contains(x)} 恒真、{@code add(x)} 永远返回 false——<b>再也写不回来</b>；Java 侧 {@code removeIf} /
         * {@code retainAll} 的默认实现同样走 {@code iterator().remove()}，同源受害。旧实现是单结构
         * （LinkedHashSet）时该形态自洽，双结构改造（T-PS6-08）后才成失同步路径。
         * 现改为游标式迭代器，{@code remove()} 走本类 {@link #remove(int)}（两结构成对维护）。
         */
        @Override
        public Iterator<String> iterator() {
            return new Iterator<String>() {
                private int cursor = 0;
                private int lastReturned = -1;

                @Override
                public boolean hasNext() {
                    return cursor < items.size();
                }

                @Override
                public String next() {
                    if (cursor >= items.size()) {
                        throw new NoSuchElementException();
                    }
                    lastReturned = cursor;
                    return items.get(cursor++);
                }

                @Override
                public void remove() {
                    if (lastReturned < 0) {
                        throw new IllegalStateException("next() 之前不可 remove()");
                    }
                    DedupErrorList.this.remove(lastReturned); // 走本类实现 → items 与 seen 同步
                    cursor = lastReturned;
                    lastReturned = -1;
                }
            };
        }
    }
}
