package cn.song.mybatisplus.visualizer.xml.ibatis

import java.lang.reflect.Array
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Date

/**
 * 参数取值器——逐条复刻 iBatis `com.ibatis.common.beans.GenericProbe` / `ComplexBeanProbe` 的
 * **取值与类型判定**语义（T-Ibatis2Support 券 B）。
 *
 * 为什么自研而不复用引擎：本链是纯静态解析，产物要能脱离引擎跑；但**保真对照**要求同一份 sqlMap +
 * 同一组参数下输出逐字节相同，故取值的可观测行为必须与 `Probe` 对齐。
 *
 * ## 引擎的真实取值链（`GenericProbe.getObject` → `ComplexBeanProbe.getObject`/`getProperty`）
 * 1. 对象是 `List` / 任意数组 → 直接走 `BaseProbe.getIndexedProperty(object, name)`
 *    （**要求 name 含 `[i]`**，否则引擎抛 `ProbeException`）；
 * 2. 否则 `ComplexBeanProbe.getObject`：name 含 `.` → 按 `.` 切段**逐段** `getProperty`（遇 null 提前返回）；
 * 3. `getProperty`：name 含 `[` → `getIndexedProperty`（先取 `name[i]` 的容器部分再取下标）；
 *    `Map` → 点号前段 `map.get(prefix)` 后递归（无点号则 `map.get(name)`）；JavaBean → getter 反射。
 *
 * **`orConditions[0].conditions` 这类嵌套 iterate 的形态只有走完这条链才取得到值**——
 * 单次 `map.get(整名)` 会返回 null，进而让 `<iterate>` 抛「不是集合」。
 *
 * 与引擎的**有意差异**（券 B 的降级口径，合法输入上逐字节等价）：getter 缺失、
 * 下标越界、在非容器对象上用 `[i]` 形态等畸形输入在本链返回 null/`Any::class.java`，
 * 引擎会抛 `ProbeException`。
 */
internal object IbatisProbe {

    /** `TypeHandlerFactory.hasTypeHandler(clazz)` 的等价判据：标量参数在 `$x$` 里取整个对象而非属性。 */
    fun hasTypeHandler(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        return clazz.isPrimitive || SCALAR_TYPES.any { it.isAssignableFrom(clazz) }
    }

    /** `GenericProbe.getObject(object, name)`。 */
    fun getObject(parameterObject: Any?, name: String?): Any? {
        if (parameterObject == null || name == null) return null
        return if (isIndexedContainer(parameterObject)) {
            indexedProperty(parameterObject, name)
        } else {
            beanGetObject(parameterObject, name)
        }
    }

    /**
     * `ComplexBeanProbe.getObject`：name 含 `.` 时按点号切段逐段取；否则直接 `getProperty`。
     */
    private fun beanGetObject(parameterObject: Any, name: String): Any? {
        if (name.indexOf('.') < 0) return beanGetProperty(parameterObject, name)
        var value: Any? = parameterObject
        for (segment in name.split('.')) {
            if (value == null) return null
            value = beanGetProperty(value, segment)
        }
        return value
    }

    /** `ComplexBeanProbe.getProperty`（下标优先 → Map → JavaBean）。 */
    private fun beanGetProperty(parameterObject: Any, name: String): Any? {
        if (name.indexOf('[') > -1) return indexedProperty(parameterObject, name)
        val dot = name.indexOf('.')
        if (parameterObject is Map<*, *>) {
            if (dot > -1) {
                val nested = parameterObject[name.substring(0, dot)] ?: return null
                return beanGetProperty(nested, name.substring(dot + 1))
            }
            return parameterObject[name]
        }
        if (dot > -1) {
            val head = name.substring(0, dot)
            val nested = beanGetObject(parameterObject, head) ?: return null
            return beanGetProperty(nested, name.substring(dot + 1))
        }
        return runCatching { invokeGetter(parameterObject, name) }.getOrNull()
    }

    /**
     * `BaseProbe.getIndexedProperty`：`name[i]` 形态；name 为空段时对对象自身取下标。
     *
     * 支持 `List` / `Object[]` / 全部原始类型数组（`java.lang.reflect.Array.get` 自动装箱）。
     */
    private fun indexedProperty(parameterObject: Any, indexedName: String): Any? {
        val open = indexedName.indexOf('[')
        val close = indexedName.indexOf(']')
        if (open < 0 || close < open) return null
        val name = indexedName.substring(0, open)
        val index = indexedName.substring(open + 1, close).toIntOrNull() ?: return null
        val container: Any? = if (name.isEmpty()) parameterObject else beanGetProperty(parameterObject, name)
        return elementAt(container, index)
    }

    /**
     * `GenericProbe.getPropertyTypeForGetter`：比较族标签的**类型换算基准**
     * （`ConditionalTagHandler.compare` 的 `type` 形参）。
     *
     * 实测分派顺序：`Class` 形参 → 含 `[` 走 `getIndexedType` → `Map` 用**整名**直取值的类
     * （点号不拆）→ JavaBean 按 getter 返回类型。
     */
    fun getPropertyTypeForGetter(parameterObject: Any?, name: String?): Class<*> {
        if (parameterObject == null) return Any::class.java
        if (parameterObject is Class<*>) return classGetterType(parameterObject, name)
        if (name != null && name.indexOf('[') > -1) return indexedType(parameterObject, name)
        if (parameterObject is Map<*, *>) {
            val value = parameterObject[name]
            return value?.javaClass ?: Any::class.java
        }
        return beanGetterType(parameterObject.javaClass, name)
    }

    private fun indexedType(parameterObject: Any, indexedName: String): Class<*> {
        val value = indexedProperty(parameterObject, indexedName)
        return value?.javaClass ?: Any::class.java
    }

    /** `Probe.hasReadableProperty`。 */
    fun hasReadableProperty(parameterObject: Any?, name: String?): Boolean {
        if (parameterObject == null || name.isNullOrEmpty()) return false
        if (parameterObject is Map<*, *>) return true
        return getter(parameterObject.javaClass, name) != null
    }

    /**
     * `IsPropertyAvailableTagHandler` 的 JavaBean 分支：先按「最后一段点号」拆一次，
     * 再对末端属性判可读性（IBATIS-281/293 的兼容逻辑）。
     *
     * **只在非 Map 参数对象上生效**：Map 分支在处理器里就被 `containsKey(原始属性名)` 短路了。
     */
    fun hasReadablePropertyAfterLastDot(parameterObject: Any?, property: String): Boolean {
        if (parameterObject == null) return false
        var target = parameterObject
        var name = property
        val lastIndex = property.lastIndexOf('.')
        if (lastIndex != -1) {
            val firstPart = property.substring(0, lastIndex)
            name = property.substring(lastIndex + 1)
            target = getObject(target, firstPart) ?: return false
        }
        if (target is Map<*, *>) return target.containsKey(name)
        return hasReadableProperty(target, name)
    }

    /**
     * `nullValue` 哨兵判定（引擎 `BaseTypeHandler.equals(object, string)`：
     * `castedObject = valueOf(string); object.equals(castedObject)`）。
     *
     * **注意语义方向**：是「参数值等于 nullValue 字符串 ⇒ 该参数按 SQL NULL 绑定」，
     * 不是「参数值为 null 时填这个字面量」——官方指南同口径
     * （「you cannot specify the null value replacement without also specifying the type」）。
     * 非标量类型（引擎侧会走 `toString()`）退化为字符串比较。
     */
    fun matchesNullValue(value: Any?, nullValue: String?): Boolean {
        if (value == null || nullValue == null) return false
        return when (value) {
            is String -> value == nullValue
            is Byte -> runCatching { value == nullValue.toByte() }.getOrDefault(false)
            is Short -> runCatching { value == nullValue.toShort() }.getOrDefault(false)
            is Int -> runCatching { value == nullValue.toInt() }.getOrDefault(false)
            is Long -> runCatching { value == nullValue.toLong() }.getOrDefault(false)
            is Float -> runCatching { value == nullValue.toFloat() }.getOrDefault(false)
            is Double -> runCatching { value == nullValue.toDouble() }.getOrDefault(false)
            is Boolean -> value == nullValue.toBoolean()
            is BigDecimal -> runCatching { value.compareTo(BigDecimal(nullValue)) == 0 }.getOrDefault(false)
            is BigInteger -> runCatching { value == BigInteger(nullValue) }.getOrDefault(false)
            is Date -> runCatching {
                java.text.SimpleDateFormat("yyyy/MM/dd hh:mm:ss").parse(nullValue) == value
            }.getOrDefault(false)
            else -> value.toString() == nullValue
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private fun invokeGetter(target: Any, property: String): Any? {
        val getter = getter(target.javaClass, property) ?: return null
        return runCatching { getter.invoke(target) }.getOrNull()
    }

    /** getter 查找：`getXxx()` / `isXxx()`；与 `ClassInfo` 的驼峰命名一致。 */
    private fun getter(type: Class<*>, property: String): Method? {
        if (property.isEmpty()) return null
        val suffix = property.substring(0, 1).uppercase() + property.substring(1)
        return runCatching { type.getMethod("get$suffix") }.getOrNull()
            ?: runCatching { type.getMethod("is$suffix") }.getOrNull()
    }

    /** `ComplexBeanProbe.getPropertyTypeForGetter` 的 JavaBean 分支（点号逐段走 getter 返回类型）。 */
    private fun beanGetterType(type: Class<*>, name: String?): Class<*> {
        if (name.isNullOrEmpty()) return Any::class.java
        var current: Class<*> = type
        for (segment in name.split('.')) {
            if (segment.isEmpty()) return Any::class.java
            if (Map::class.java.isAssignableFrom(current)) return Any::class.java
            val getter = getter(current, segment) ?: return Any::class.java
            current = getter.returnType
        }
        return current
    }

    private fun classGetterType(type: Class<*>, name: String?): Class<*> =
        if (name == null) Any::class.java else beanGetterType(type, name)

    private fun isIndexedContainer(value: Any): Boolean = value is List<*> || value.javaClass.isArray

    private fun elementAt(container: Any?, index: Int): Any? {
        if (container == null) return null
        return when {
            container is List<*> -> container.getOrNull(index)
            container.javaClass.isArray -> runCatching { Array.get(container, index) }.getOrNull()
            else -> null
        }
    }

    /** `TypeHandlerFactory` 内置处理器覆盖的标量族（`$x$` 的取值分派判据）。 */
    private val SCALAR_TYPES: List<Class<*>> = listOf(
        String::class.java,
        CharSequence::class.java,
        Char::class.java,
        Boolean::class.java,
        Byte::class.java,
        Short::class.java,
        Int::class.java,
        Long::class.java,
        Float::class.java,
        Double::class.java,
        BigDecimal::class.java,
        BigInteger::class.java,
        Date::class.java,
        Number::class.java,
        java.sql.Date::class.java,
        java.sql.Time::class.java,
        java.sql.Timestamp::class.java,
        ByteArray::class.java,
    )
}
