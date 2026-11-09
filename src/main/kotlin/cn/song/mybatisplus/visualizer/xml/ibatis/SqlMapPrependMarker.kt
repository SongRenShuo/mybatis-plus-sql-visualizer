package cn.song.mybatisplus.visualizer.xml.ibatis

import java.util.Arrays

/**
 * `removeFirstPrepend` marker 栈 + `<iterate>` 迭代上下文 + 标签求值上下文
 * （T-Ibatis2Support 券 B，复刻 `SqlTagContext` / `RemoveFirstPrependMarker` / `IterateContext`）。
 *
 * **本机制是 iBatis 2 独有的文本拼装逻辑，MyBatis 3 没有对应物**：
 * `<dynamic prepend="...">` 会「吃掉」第一个产出内容的子标签的 prepend；
 * 吃掉动作通过 [IbatisTagContext.disableRemoveFirstPrependMarker] 操作 **marker 栈的第 1 层（父层）** 实现
 * ——引擎源码 `SqlTagContext.peekRemoveFirstPrependMarker` / `disableRemoveFirstPrependMarker` 都是
 * `stack.get(1)`，这是**刻意如此**（子标签的 doStartFragment 已把自己压在第 0 层）。
 */
internal class RemoveFirstPrependMarker(
    val tag: IbatisTagNode,
    var removeFirstPrepend: Boolean,
)

/**
 * `<iterate>` 的迭代上下文（引擎 `IterateContext`）。
 *
 * 下标语义：[index] 从 -1 起，[next] 先自增再取元素；`#ids[]#` 里的 `[]` 由
 * [addIndexToTagProperty] 逐次替换为 `ids[0]`/`ids[1]`…。
 */
internal class IbatisIterateContext(
    collection: Any?,
    val tag: IbatisTagNode,
    val parent: IbatisIterateContext?,
) {

    private val iterator: Iterator<*>?

    var index: Int = -1
        private set

    var property: String = ""

    var allowNext: Boolean = true

    var isFinal: Boolean = false

    var someSubElementsHaveContent: Boolean = false

    var isPrependEnabled: Boolean = false

    init {
        // 引擎 IterateContext 构造器：非 Collection/Iterator/数组一律抛 SqlMapException；
        // collection 为 null 时 `collection.getClass()` 直接 NPE——本链折成可回溯的渲染异常。
        iterator = when {
            collection is Collection<*> -> collection.iterator()
            collection is Iterator<*> -> collection
            collection != null && collection.javaClass.isArray -> arrayToList(collection).iterator()
            else -> throw IbatisRenderException(
                "iterate 的 property 取值不是 Collection / Array / Iterator：property='${tag.property}'",
            )
        }
    }

    fun hasNext(): Boolean = iterator?.hasNext() == true

    fun next(): Any? {
        index++
        return iterator?.next()
    }

    /** 引擎 `isLast()`：`iterator != null && !iterator.hasNext()`。 */
    fun isLast(): Boolean = iterator != null && !iterator.hasNext()

    /** `DynamicSql.iteratePropertyReplace` 的逐点替换入口。 */
    fun addIndexToTagProperty(tagProperty: String): String = processTagProperty(tagProperty).first

    /** 引擎 `getEndProperty()`：嵌套 iterate 里剥掉父级 property 前缀，得到本层要加下标的属性名。 */
    private fun endProperty(): String {
        val currentParent = parent ?: return property
        val parentPropertyIndex = property.indexOf(currentParent.property)
        if (parentPropertyIndex > -1) {
            val end1 = property.indexOf(']', parentPropertyIndex)
            val end2 = property.indexOf('.', parentPropertyIndex)
            return property.substring(parentPropertyIndex + maxOf(end1, end2) + 1)
        }
        return property
    }

    private fun processTagProperty(tagProperty: String): Pair<String, Int> =
        if (parent != null) {
            val (text, at) = parent.processTagProperty(tagProperty)
            addIndex(text, at)
        } else {
            addIndex(tagProperty, 0)
        }

    /**
     * 引擎 `addIndex`：把 `endProperty + "["` 之后的第一个 `]` 处插入当前下标。
     *
     * 引擎用 `input.charAt(propertyIndex + endProperty.length())` 判「待填下标」——
     * 越界时抛 `StringIndexOutOfBoundsException`；本链对越界按「不匹配」处理（畸形输入不打断整篇，
     * 与券 B 的降级口径一致，合法输入上与引擎逐字节等价）。
     */
    private fun addIndex(input: String, startIndex: Int): Pair<String, Int> {
        val endProperty = endProperty() + "["
        val propertyIndex = input.indexOf(endProperty, startIndex)
        if (propertyIndex < 0) return input to 0
        val after = propertyIndex + endProperty.length
        if (after >= input.length || input[after] != ']') return input to 0
        return (input.substring(0, after) + index + input.substring(after)) to after
    }

    private fun arrayToList(array: Any): List<Any?> =
        if (array is Array<*>) {
            Arrays.asList(*array)
        } else {
            val length = java.lang.reflect.Array.getLength(array)
            (0 until length).map { java.lang.reflect.Array.get(array, it) }
        }
}

/**
 * 求值上下文（引擎 `SqlTagContext` 的栈与参数侧）。
 *
 * 文本缓冲由渲染器按「每个标签体一个 `StringBuilder`」管理（对齐引擎的
 * 每标签一个 `StringWriter` + 处理后才有条件 `out.print(body)`），本类只负责
 * marker 栈、iterate 栈、属性表与参数序列。
 */
internal class IbatisTagContext {

    private val markers = ArrayList<RemoveFirstPrependMarker>()

    private val iterateStack = ArrayList<IbatisIterateContext>()

    private val attributes = HashMap<IbatisTagNode, IbatisIterateContext?>()

    val parameterMappings = ArrayList<IbatisParamMapping>()

    val warnings = ArrayList<String>()

    // ---------------------------------------------------------------- marker 栈

    /** 引擎 `pushRemoveFirstPrependMarker`：三分支判定（详见类 KDoc 与 [computeRemoveFirstPrepend]）。 */
    fun pushRemoveFirstPrependMarker(tag: IbatisTagNode) {
        markers.add(0, RemoveFirstPrependMarker(tag, computeRemoveFirstPrepend(tag)))
    }

    /** 引擎 `popRemoveFirstPrependMarker`：只有栈顶就是本标签时才弹（迭代重复进入时不重复压栈）。 */
    fun popRemoveFirstPrependMarker(tag: IbatisTagNode) {
        if (markers.isNotEmpty() && markers[0].tag === tag) markers.removeAt(0)
    }

    /** 引擎 `peekRemoveFirstPrependMarker`：读 **父层**（第 1 层）状态。 */
    fun peekParentRemoveFirstPrepend(): Boolean =
        if (markers.size > 1) markers[1].removeFirstPrepend else false

    /** 引擎 `disableRemoveFirstPrependMarker`：置 **父层** 为 false（= 父 prepend 已被用掉）。 */
    fun disableParentRemoveFirstPrepend() {
        if (markers.size > 1) markers[1].removeFirstPrepend = false
    }

    /** 引擎 `reEnableRemoveFirstPrependMarker`：置 **栈顶（本层）** 为 true（`removeFirstPrepend="iterate"` 用）。 */
    fun reEnableRemoveFirstPrepend() {
        if (markers.isNotEmpty()) markers[0].removeFirstPrepend = true
    }

    // ---------------------------------------------------------------- iterate 栈

    fun pushIterateContext(context: IbatisIterateContext) {
        iterateStack.add(0, context)
    }

    fun popIterateContext() {
        if (iterateStack.isNotEmpty()) iterateStack.removeAt(0)
    }

    fun peekIterateContext(): IbatisIterateContext? = iterateStack.firstOrNull()

    fun setAttribute(tag: IbatisTagNode, value: IbatisIterateContext?) {
        attributes[tag] = value
    }

    fun getAttribute(tag: IbatisTagNode): IbatisIterateContext? = attributes[tag]

    // ---------------------------------------------------------------- 属性解析

    /**
     * 引擎 `ConditionalTagHandler.getResolvedProperty` / `BaseTagHandler` 的 `tag.getPropertyAttr()`。
     *
     * **副作用是契约的一部分**：在 iterate 内求值时会把当前迭代推进一格
     * （`allowNext` 保证同一次迭代内只推进一次，与 `IterateTagHandler.doEndFragment` 的推进互斥）。
     */
    fun resolveProperty(tag: IbatisTagNode): String? {
        val prop = tag.property ?: return null
        val iterate = peekIterateContext()
        if (iterate != null && iterate.allowNext) {
            iterate.next()
            iterate.allowNext = false
            if (!iterate.hasNext()) iterate.isFinal = true
        }
        if (prop.indexOf('[') > -1 && iterate != null) return iterate.addIndexToTagProperty(prop)
        return prop
    }

    /** 引擎 `pushRemoveFirstPrependMarker` 的初始状态判定。 */
    private fun computeRemoveFirstPrepend(tag: IbatisTagNode): Boolean {
        val parentTag = tag.parent
        return when {
            tag.kind == IbatisTagKind.DYNAMIC -> tag.hasPrepend
            tag.removeFirstPrepend == RFP_TRUE || tag.removeFirstPrepend == RFP_ITERATE -> true
            !tag.hasPrepend && parentTag != null ->
                parentTag.removeFirstPrepend == RFP_TRUE || parentTag.removeFirstPrepend == RFP_ITERATE
            else -> false
        }
    }

    private companion object {
        const val RFP_TRUE = "true"
        const val RFP_ITERATE = "iterate"
    }
}
