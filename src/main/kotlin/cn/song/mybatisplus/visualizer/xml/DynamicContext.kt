package cn.song.mybatisplus.visualizer.xml

/**
 * 渲染上下文（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.DynamicContext`。
 *
 * - bindings：变量上下文（初始 `_parameter` / `_databaseId`；bind / foreach 变量写入）
 * - appendSql：段间以单个空格分隔（对齐 3.5.13+ 的 StringJoiner(" ") 行为），最终 getSql() 去首尾空白
 * - conditionOverrides：条件开关覆盖（预览面板直接决定 if/when 真假，id = test 表达式原文）
 * - protectedBindNames：bind 覆盖名（面板输入的覆盖值优先于表达式求值）
 * - errors：渲染期错误（判重表，嵌套子上下文须转发 delegate，否则内层错误静默）
 *
 * 预览引擎专用扩展（conditionOverrides / protectedBindNames / errors）不影响运行时复刻语义。
 */
open class DynamicContext(
    initialBindings: Map<String, Any?>,
    private val conditionOverrides: Map<String, Boolean> = emptyMap(),
    private val protectedBindNames: Set<String> = emptySet(),
    /** 静态字段值（T-M25.2）：`@class@field` → 值；预览引擎由 PSI 层解析用户项目常量类注入。 */
    open val staticFieldValues: Map<String, Any?> = emptyMap(),
    /**
     * 空值占位模式（T-MbgExamplePreview R1）：`${}` 求值为 null 且表达式含变量引用时，输出
     * `{{表达式}}` 而不是空串——缺段在产物里可见，不会被误读成「插件把 SQL 识别错了」。
     *
     * 默认 false：真实 SQL 产物与全仓既有断言零影响；只有预览渲染链（[XmlPreviewModel.renderSql]）开启。
     */
    open val emptyDollarPlaceholder: Boolean = false,
) {

    /**
     * 变量上下文（`_parameter` / `_databaseId` / bind 变量 / foreach item/index / 模拟值）。
     *
     * 注意：用属性初始化器而非 init 块填充——子类（Trim/Foreach 的过滤上下文）覆写本属性
     * 转发 delegate，父类 init 块中的调用会虚分派到未初始化的子类字段（NPE）。
     */
    open val bindings: MutableMap<String, Any?> = initialBindings.toMutableMap()

    /**
     * 渲染期错误提示（如 foreach collection 为 null 且 nullable=false、表达式解析失败）。
     *
     * T-FS-07：①判重表——`<foreach>` 逐迭代会把同一表达式同一原因重复登记 N 次，
     * 用户看到的 `⚠` 警示行（`errors.joinToString("；")`）不该被同一句话刷屏，故同文案只留首现一行
     * （判重键 = 完整错误文案：9 个写入点里 8 个的文案内嵌表达式原文 + 原因，天然等价于「同表达式同原因」；
     * 例外是 [IfSqlNode] 的「bind 表达式被 `||` 包裹」提示，文案固定不含表达式名——同一语句里两条不同的
     * `||X||` bind 会并成一行，只丢「条数」不丢可区分信息，属可接受边界）；
     * ②子上下文（Foreach 的 Prefixed/Filtered、Trim 的 filtered）覆写本属性转发 delegate——
     * 此前各自持有独立列表，内层 `<if>` 求值失败被丢弃（静默，与 `${}`/bind/顶层 if 形态不一致）。
     */
    open val errors: MutableList<String> = DedupErrorList()

    /**
     * 条件求值结果登记（R30 探镜，只读观测——渲染语义零变更）：IfSqlNode 每次被走到（apply）
     * 就登记 test 表达式原文 → 本轮判定真值；渲染中**未到达**的条件（假 if 体内层 / choose 短路后的
     * 其余 when）不登记，模型侧据缺席映射「未求值」（见 [XmlPreviewModel.conditionOutcomes]）。
     *
     * 同表达式多次求值（foreach 体内）按「任一次为真即真」合并（merge OR）——探针回答的是
     * 「这一轮这条条件有没有放行内容进 SQL」。子上下文（Trim/Foreach 的过滤上下文）覆写本属性
     * 转发 delegate（同 [errors] 的 T-FS-07 缺陷形态，否则体内条件的判定结果被丢弃）。
     */
    open val conditionOutcomes: MutableMap<String, Boolean> = LinkedHashMap()

    /** 条件判定登记入口：同表达式多次求值按 OR 合并（[conditionOutcomes]）。 */
    open fun recordConditionOutcome(test: String, truth: Boolean) {
        conditionOutcomes.merge(test, truth) { previous, current -> previous || current }
    }

    /**
     * `${}` 直替计数（T-DollarInjectMark）：TextSqlNode 每求值一个 `${}` token 经
     * [countDollarSubstitution] 自增——引擎忠实口径（MyBatis 对字符串字面量内的 `${}`
     * 同样直替，计数不排除）；转义 `\${` 不经求值不计。渲染结束随
     * RenderedXmlSql.dollarSubstitutions 出栈，预览横幅消费。
     */
    var dollarSubstitutions: Int = 0
        protected set

    /** 直替计数入口：子类过滤上下文（Foreach 的 Prefixed/Filtered）转发到 delegate，计数落根上下文。 */
    open fun countDollarSubstitution() {
        dollarSubstitutions++
    }

    private val sqlBuilder = StringBuilder()
    private var uniqueNumber = 0

    /** 追加 SQL 段（段间单个空格；空段不产生分隔）。 */
    open fun appendSql(sql: String) {
        if (sql.isEmpty()) return
        if (sqlBuilder.isNotEmpty()) sqlBuilder.append(' ')
        sqlBuilder.append(sql)
    }

    /** 当前累积 SQL（首尾空白已去除）。 */
    open fun getSql(): String = sqlBuilder.toString().trim()

    /** 绑定变量（bind / foreach item·index）。 */
    open fun bind(name: String, value: Any?) {
        bindings[name] = value
    }

    /** 每次调用自增的唯一编号（foreach 的 `__frch_<名>_<n>` 用）。 */
    open fun getUniqueNumber(): Int = uniqueNumber++

    /** 条件开关覆盖：test 表达式原文 → 强制真值；null 表示无覆盖（走表达式求值）。 */
    open fun conditionResult(test: String): Boolean? = conditionOverrides[test]

    /** bind 名是否受保护（面板覆盖值优先，VarDeclSqlNode 跳过求值）。 */
    open fun isProtected(name: String): Boolean = name in protectedBindNames

    companion object {
        const val PARAMETER_OBJECT_KEY = "_parameter"
        const val DATABASE_ID_KEY = "_databaseId"
    }
}

/**
 * 判重错误表（T-FS-07）：保首现顺序、同文案只留一行的 [MutableList]。
 *
 * 只用于 [DynamicContext.errors]（MyBatis 无此概念，属预览引擎专用扩展）；保留 MutableList 形态
 * 使既有写入点（`context.errors += msg`）与既有测试无需改动。`add(index, element)` 忽略索引——
 * 判重集合无插入位置语义，仓内既有写入只有 `+=` / `add(msg)` 两种形态。
 *
 * **T-PerfSweep6 T-PS6-08（性能报告发现 10）：列表 + 判重集合双结构**。此前用 `LinkedHashSet` 单结构
 * 冒充 [MutableList]，`get(index)` 走 `elementAt(index)` = 逐节点前进的 **O(n)**；而 `AbstractMutableList`
 * 的 `equals`/`hashCode`/`subList`/`indexOf` 与 Kotlin 的 `listIterator()` 默认实现都按 index 遍历
 * ——一次 `errors == other` 比较就是 O(n²)。现改为 [items]（ArrayList，承担顺序与随机访问）+
 * [seen]（HashSet，承担 O(1) 判重），两个结构在所有写点同步维护。
 *
 * **仍不支持位置型写入语义**（T-FS-07 复核注记沿用）：`add(index, element)` 忽略索引、
 * `set(index, element)` 把新值挪到末尾（且新值已存在时会缩容）。当前仓内对 `.errors` 无任何位置型
 * 调用点，故无实际后果；需要位置语义时应改用普通 `mutableListOf`，而不是改本类。
 * `modCount` 沿用既有形态（不自增）：本类不是并发容器，仓内也无「边遍历边写」的调用点。
 */
private class DedupErrorList : AbstractMutableList<String>() {

    /** 首现顺序 + 随机访问（[get] O(1)）。 */
    private val items = ArrayList<String>()

    /** 判重集合（与 [items] 严格同集，所有写点成对维护）。 */
    private val seen = HashSet<String>()

    override val size: Int get() = items.size

    override fun get(index: Int): String = items[index]

    override fun add(index: Int, element: String) {
        if (seen.add(element)) items.add(element)
    }

    override fun removeAt(index: Int): String = items.removeAt(index).also { seen.remove(it) }

    /** 判重集合无「按位替换」语义：旧值移除、新值追加到末尾（仓内无调用点，仅为满足 MutableList 契约）。 */
    override fun set(index: Int, element: String): String {
        val previous = get(index)
        if (previous != element) {
            removeAt(index) // 旧值出表（两结构同步）
            if (seen.add(element)) items.add(element)
        }
        return previous
    }

    override fun add(element: String): Boolean {
        if (!seen.add(element)) return false
        items.add(element)
        return true
    }

    override fun clear() {
        items.clear()
        seen.clear()
    }

    override fun contains(element: String): Boolean = seen.contains(element)

    override fun indexOf(element: String): Int = items.indexOf(element)

    /**
     * 迭代器（T-Round10 独立复核 ㉚ 订正）：**不再直接返回 `items.iterator()`**。
     *
     * 那个 ArrayList 迭代器的 `remove()` 只从 [items] 移除、**不动 [seen]**，于是删掉的元素
     * `contains(x)` 恒真、`add(x)` 永远返回 false——**再也写不回来**；Java 侧 `removeIf` /
     * `retainAll` 的默认实现同样走 `iterator().remove()`，同源受害。旧实现是单结构
     * （`LinkedHashSet`）时该形态自洽，双结构改造（T-PS6-08）后才成失同步路径。
     * 现改为游标式迭代器，`remove()` 走本类 [removeAt]（两结构成对维护）。
     */
    override fun iterator(): MutableIterator<String> = object : MutableIterator<String> {
        private var cursor = 0
        private var lastReturned = -1

        override fun hasNext(): Boolean = cursor < items.size

        override fun next(): String {
            if (cursor >= items.size) throw NoSuchElementException()
            lastReturned = cursor
            return items[cursor++]
        }

        override fun remove() {
            if (lastReturned < 0) throw IllegalStateException("next() 之前不可 remove()")
            removeAt(lastReturned) // 走本类实现 → items 与 seen 同步
            cursor = lastReturned
            lastReturned = -1
        }
    }
}
