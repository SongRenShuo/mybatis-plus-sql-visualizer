package cn.song.mybatisplus.visualizer.xml.ibatis

import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 条件标签求值器——逐行复刻 iBatis 2.3.4.726 的 `ConditionalTagHandler` 与 14 个 `isXxx` 处理器。
 *
 * **iBatis 2 没有表达式语言**（全树 grep `ognl` 零命中）：条件＝「属性取值（`Probe`）+ 类型换算后的比较」。
 *
 * 三个必须照抄的实测细节：
 * 1. `compareValues` 的**类型换算顺序**：先按 `value1` 的声明类型把 `compareValue` 转过去；
 *    若转换结果是 String 而声明类型不是 String，则把 `value1` 也 `toString()`；
 *    任一非 `Comparable` 时**双方都**转 String 再比；
 * 2. `NOT_COMPARABLE = Long.MIN_VALUE`：**它是负数**，所以「不可比」在
 *    `isLessThan` / `isLessEqual` 上判**真**、在 `isGreaterThan` / `isGreaterEqual` 上判假；
 * 3. `<isNull>`/`<isNotNull>`/`<isEmpty>`/`<isNotEmpty>` **不参与 compare**，各自实现 `isCondition`。
 */
internal class SqlMapConditionEvaluator(private val warn: (String) -> Unit = {}) {

    /** 引擎 `ConditionalTagHandler.doStartFragment` 的条件判据。 */
    fun isCondition(kind: IbatisTagKind, ctx: IbatisTagContext, tag: IbatisTagNode, params: Any?): Boolean =
        when (kind) {
            IbatisTagKind.IS_NULL -> isNullCondition(ctx, tag, params)
            IbatisTagKind.IS_NOT_NULL -> !isNullCondition(ctx, tag, params)
            IbatisTagKind.IS_EMPTY -> isEmptyCondition(ctx, tag, params)
            IbatisTagKind.IS_NOT_EMPTY -> !isEmptyCondition(ctx, tag, params)
            IbatisTagKind.IS_PROPERTY_AVAILABLE -> isPropertyAvailable(ctx, tag, params)
            IbatisTagKind.IS_NOT_PROPERTY_AVAILABLE -> !isPropertyAvailable(ctx, tag, params)
            IbatisTagKind.IS_PARAMETER_PRESENT -> params != null
            IbatisTagKind.IS_NOT_PARAMETER_PRESENT -> params == null
            IbatisTagKind.IS_EQUAL -> compare(ctx, tag, params) == 0L
            IbatisTagKind.IS_NOT_EQUAL -> compare(ctx, tag, params) != 0L
            IbatisTagKind.IS_GREATER_THAN -> compare(ctx, tag, params) > 0L
            IbatisTagKind.IS_GREATER_EQUAL -> compare(ctx, tag, params) >= 0L
            IbatisTagKind.IS_LESS_THAN -> compare(ctx, tag, params) < 0L
            IbatisTagKind.IS_LESS_EQUAL -> compare(ctx, tag, params) <= 0L
            // dynamic / iterate 不走条件求值（doStartFragment 不使用 isCondition）
            IbatisTagKind.DYNAMIC, IbatisTagKind.ITERATE -> true
        }

    /** 引擎 `IsNullTagHandler.isCondition`。 */
    private fun isNullCondition(ctx: IbatisTagContext, tag: IbatisTagNode, params: Any?): Boolean {
        if (params == null) return true
        val prop = ctx.resolveProperty(tag)
        val value = if (prop != null) IbatisProbe.getObject(params, prop) else params
        return value == null
    }

    /** 引擎 `IsEmptyTagHandler.isCondition`（Collection `size()<1` / 数组 `length==0` / 其余 `==null || ""`）。 */
    private fun isEmptyCondition(ctx: IbatisTagContext, tag: IbatisTagNode, params: Any?): Boolean {
        if (params == null) return true
        val prop = ctx.resolveProperty(tag)
        val value = if (prop != null) IbatisProbe.getObject(params, prop) else params
        return when {
            value is Collection<*> -> value.size < 1
            value != null && value.javaClass.isArray -> java.lang.reflect.Array.getLength(value) == 0
            else -> value == null || value.toString() == ""
        }
    }

    /**
     * 引擎 `IsPropertyAvailableTagHandler.isCondition`。
     *
     * **`Map` 分支刻意绕开 `Probe`**：`Probe` 对 `Map` 的任何属性名都返回 true，
     * 故对 `Map` 改用 `containsKey(propertyAttr)`（用**原始**属性名，不做下标解析）。
     */
    private fun isPropertyAvailable(ctx: IbatisTagContext, tag: IbatisTagNode, params: Any?): Boolean {
        if (params == null) return false
        if (params is Map<*, *>) return params.containsKey(tag.property)
        val property = ctx.resolveProperty(tag)
        if (property == null) {
            warn("条件标签 <${tag.kind.tagName}> 缺少 property 属性，按「属性不可用」处理")
            return false
        }
        return IbatisProbe.hasReadablePropertyAfterLastDot(params, property)
    }

    /** 引擎 `ConditionalTagHandler.compare`：`compareProperty` 优先，其次 `compareValue`，都没有是配置错误。 */
    fun compare(ctx: IbatisTagContext, tag: IbatisTagNode, params: Any?): Long {
        val prop = ctx.resolveProperty(tag)
        val value1: Any?
        val type: Class<*>
        if (prop != null) {
            value1 = IbatisProbe.getObject(params, prop)
            type = IbatisProbe.getPropertyTypeForGetter(params, prop)
        } else {
            value1 = params
            type = params?.javaClass ?: Any::class.java
        }
        val comparePropertyName = tag.compareProperty
        val compareValue = tag.compareValue
        return when {
            comparePropertyName != null ->
                compareValues(type, value1, IbatisProbe.getObject(params, comparePropertyName))
            compareValue != null -> compareValues(type, value1, compareValue)
            else -> {
                warn("条件标签 <${tag.kind.tagName}> 既没有 compareProperty 也没有 compareValue，按「不成立」处理")
                NOT_COMPARABLE
            }
        }
    }

    /**
     * 引擎 `ConditionalTagHandler.compareValues`（返回值语义见类 KDoc）。
     *
     * 非法字面量（如声明类型是 Integer 却比 `"abc"`）在引擎里抛 `NumberFormatException`；
     * 本链折成 `NOT_COMPARABLE` + 告警（畸形输入不打断整篇）。
     */
    fun compareValues(type: Class<*>, value1: Any?, value2: Any?): Long {
        if (value1 == null || value2 == null) {
            return if (value1 === value2) 0L else NOT_COMPARABLE
        }
        var left: Any = value1
        var right: Any? = value2
        if (right.javaClass != type) {
            right = runCatching { convertValue(type, right.toString()) }
                .getOrElse {
                    warn("比较值无法换算为 ${type.name}：'${right}'")
                    return NOT_COMPARABLE
                }
        }
        if (right is String && type != String::class.java) left = left.toString()
        if (left !is Comparable<*> || right !is Comparable<*>) {
            left = left.toString()
            right = right.toString()
        }
        return runCatching { (left as Comparable<Any?>).compareTo(right) }
            .getOrElse {
                warn("比较失败（不可比）：${left.javaClass.name} vs ${right?.javaClass?.name}")
                NOT_COMPARABLE
            }
            .toLong()
    }

    /** 引擎 `ConditionalTagHandler.convertValue`（Date 掩码固定 `yyyy/MM/dd hh:mm:ss`）。 */
    fun convertValue(type: Class<*>, value: String): Any = when (type) {
        String::class.java -> value
        java.lang.Byte::class.java, java.lang.Byte.TYPE -> value.toByte()
        java.lang.Short::class.java, java.lang.Short.TYPE -> value.toShort()
        Character::class.java, Character.TYPE -> value[0]
        Integer::class.java, Integer.TYPE -> value.toInt()
        java.lang.Long::class.java, java.lang.Long.TYPE -> value.toLong()
        java.lang.Float::class.java, java.lang.Float.TYPE -> value.toFloat()
        java.lang.Double::class.java, java.lang.Double.TYPE -> value.toDouble()
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> value.toBoolean()
        Date::class.java -> SimpleDateFormat(DATE_MASK).parse(value)
        BigInteger::class.java -> BigInteger(value)
        BigDecimal::class.java -> BigDecimal(value)
        else -> value
    }

    companion object {
        /** 引擎 `ConditionalTagHandler.NOT_COMPARABLE`。 */
        const val NOT_COMPARABLE: Long = Long.MIN_VALUE

        /** 引擎 `ConditionalTagHandler.DATE_MASK`（注意是 12 小时制 `hh`）。 */
        const val DATE_MASK: String = "yyyy/MM/dd hh:mm:ss"
    }
}
