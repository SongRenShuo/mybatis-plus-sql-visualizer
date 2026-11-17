package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 参数取值器——逐条复刻 iBatis {@code com.ibatis.common.beans.GenericProbe} /
 * {@code ComplexBeanProbe} 的<b>取值与类型判定</b>语义（T-Ibatis2Support 券 B）。
 *
 * <p>为什么自研而不复用引擎：本链是纯静态解析，产物要能脱离引擎跑；但<b>保真对照</b>要求同一份
 * sqlMap + 同一组参数下输出逐字节相同，故取值的可观测行为必须与 {@code Probe} 对齐。
 *
 * <h2>引擎的真实取值链（{@code GenericProbe.getObject} → {@code ComplexBeanProbe.getObject}/{@code getProperty}）</h2>
 * <ol>
 * <li>对象是 {@code List} / 任意数组 → 直接走 {@code BaseProbe.getIndexedProperty(object, name)}
 *     （<b>要求 name 含 {@code [i]}</b>，否则引擎抛 {@code ProbeException}）；</li>
 * <li>否则 {@code ComplexBeanProbe.getObject}：name 含 {@code .} → 按 {@code .} 切段<b>逐段</b>
 *     {@code getProperty}（遇 null 提前返回）；</li>
 * <li>{@code getProperty}：name 含 {@code [} → {@code getIndexedProperty}（先取 {@code name[i]} 的容器部分
 *     再取下标）；{@code Map} → 点号前段 {@code map.get(prefix)} 后递归（无点号则 {@code map.get(name)}）；
 *     JavaBean → getter 反射。</li>
 * </ol>
 *
 * <p><b>{@code orConditions[0].conditions} 这类嵌套 iterate 的形态只有走完这条链才取得到值</b>——
 * 单次 {@code map.get(整名)} 会返回 null，进而让 {@code <iterate>} 抛「不是集合」。
 *
 * <p>与引擎的<b>有意差异</b>（券 B 的降级口径，合法输入上逐字节等价）：getter 缺失、下标越界、
 * 在非容器对象上用 {@code [i]} 形态等畸形输入在本链返回 null/{@code Object.class}，引擎会抛
 * {@code ProbeException}。
 */
public final class IbatisProbe {

    private IbatisProbe() {
    }

    /** {@code TypeHandlerFactory.hasTypeHandler(clazz)} 的等价判据：标量参数在 {@code $x$} 里取整个对象而非属性。 */
    public static boolean hasTypeHandler(@Nullable Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        if (clazz.isPrimitive()) {
            return true;
        }
        for (Class<?> scalar : SCALAR_TYPES) {
            if (scalar.isAssignableFrom(clazz)) {
                return true;
            }
        }
        return false;
    }

    /** {@code GenericProbe.getObject(object, name)}。 */
    @Nullable
    public static Object getObject(@Nullable Object parameterObject, @Nullable String name) {
        if (parameterObject == null || name == null) {
            return null;
        }
        return isIndexedContainer(parameterObject)
                ? indexedProperty(parameterObject, name)
                : beanGetObject(parameterObject, name);
    }

    /** {@code ComplexBeanProbe.getObject}：name 含 {@code .} 时按点号切段逐段取；否则直接 {@code getProperty}。 */
    @Nullable
    private static Object beanGetObject(Object parameterObject, String name) {
        if (name.indexOf('.') < 0) {
            return beanGetProperty(parameterObject, name);
        }
        Object value = parameterObject;
        for (String segment : name.split("\\.")) {
            if (value == null) {
                return null;
            }
            value = beanGetProperty(value, segment);
        }
        return value;
    }

    /** {@code ComplexBeanProbe.getProperty}（下标优先 → Map → JavaBean）。 */
    @Nullable
    private static Object beanGetProperty(Object parameterObject, String name) {
        if (name.indexOf('[') > -1) {
            return indexedProperty(parameterObject, name);
        }
        int dot = name.indexOf('.');
        if (parameterObject instanceof Map<?, ?> map) {
            if (dot > -1) {
                Object nested = map.get(name.substring(0, dot));
                if (nested == null) {
                    return null;
                }
                return beanGetProperty(nested, name.substring(dot + 1));
            }
            return map.get(name);
        }
        if (dot > -1) {
            String head = name.substring(0, dot);
            Object nested = beanGetObject(parameterObject, head);
            if (nested == null) {
                return null;
            }
            return beanGetProperty(nested, name.substring(dot + 1));
        }
        return invokeGetter(parameterObject, name);
    }

    /**
     * {@code BaseProbe.getIndexedProperty}：{@code name[i]} 形态；name 为空段时对对象自身取下标。
     *
     * <p>支持 {@code List} / {@code Object[]} / 全部原始类型数组（{@code Array.get} 自动装箱）。
     */
    @Nullable
    private static Object indexedProperty(Object parameterObject, String indexedName) {
        int open = indexedName.indexOf('[');
        int close = indexedName.indexOf(']');
        if (open < 0 || close < open) {
            return null;
        }
        String name = indexedName.substring(0, open);
        Integer index;
        try {
            index = Integer.valueOf(indexedName.substring(open + 1, close));
        } catch (NumberFormatException e) {
            return null;
        }
        Object container = name.isEmpty() ? parameterObject : beanGetProperty(parameterObject, name);
        return elementAt(container, index);
    }

    /**
     * {@code GenericProbe.getPropertyTypeForGetter}：比较族标签的<b>类型换算基准</b>
     * （{@code ConditionalTagHandler.compare} 的 {@code type} 形参）。
     *
     * <p>实测分派顺序：{@code Class} 形参 → 含 {@code [} 走 {@code getIndexedType} →
     * {@code Map} 用<b>整名</b>直取值的类（点号不拆）→ JavaBean 按 getter 返回类型。
     */
    public static Class<?> getPropertyTypeForGetter(@Nullable Object parameterObject, @Nullable String name) {
        if (parameterObject == null) {
            return Object.class;
        }
        if (parameterObject instanceof Class<?> type) {
            return name == null ? Object.class : beanGetterType(type, name);
        }
        if (name != null && name.indexOf('[') > -1) {
            return indexedType(parameterObject, name);
        }
        if (parameterObject instanceof Map<?, ?> map) {
            Object value = map.get(name);
            return value != null ? value.getClass() : Object.class;
        }
        return beanGetterType(parameterObject.getClass(), name);
    }

    private static Class<?> indexedType(Object parameterObject, String indexedName) {
        Object value = indexedProperty(parameterObject, indexedName);
        return value != null ? value.getClass() : Object.class;
    }

    /** {@code Probe.hasReadableProperty}。 */
    public static boolean hasReadableProperty(@Nullable Object parameterObject, @Nullable String name) {
        if (parameterObject == null || name == null || name.isEmpty()) {
            return false;
        }
        if (parameterObject instanceof Map<?, ?>) {
            return true;
        }
        return getter(parameterObject.getClass(), name) != null;
    }

    /**
     * {@code IsPropertyAvailableTagHandler} 的 JavaBean 分支：先按「最后一段点号」拆一次，
     * 再对末端属性判可读性（IBATIS-281/293 的兼容逻辑）。
     *
     * <p><b>只在非 Map 参数对象上生效</b>：Map 分支在处理器里就被 {@code containsKey(原始属性名)} 短路了。
     */
    public static boolean hasReadablePropertyAfterLastDot(@Nullable Object parameterObject, String property) {
        if (parameterObject == null) {
            return false;
        }
        Object target = parameterObject;
        String name = property;
        int lastIndex = property.lastIndexOf('.');
        if (lastIndex != -1) {
            String firstPart = property.substring(0, lastIndex);
            name = property.substring(lastIndex + 1);
            target = getObject(target, firstPart);
            if (target == null) {
                return false;
            }
        }
        if (target instanceof Map<?, ?> map) {
            return map.containsKey(name);
        }
        return hasReadableProperty(target, name);
    }

    /**
     * {@code nullValue} 哨兵判定（引擎 {@code BaseTypeHandler.equals(object, string)}：
     * {@code castedObject = valueOf(string); object.equals(castedObject)}）。
     *
     * <p><b>注意语义方向</b>：是「参数值等于 nullValue 字符串 ⇒ 该参数按 SQL NULL 绑定」，
     * 不是「参数值为 null 时填这个字面量」——官方指南同口径
     * （「you cannot specify the null value replacement without also specifying the type」）。
     * 非标量类型（引擎侧会走 {@code toString()}）退化为字符串比较。
     */
    public static boolean matchesNullValue(@Nullable Object value, @Nullable String nullValue) {
        if (value == null || nullValue == null) {
            return false;
        }
        try {
            if (value instanceof String text) {
                return text.equals(nullValue);
            }
            if (value instanceof Byte number) {
                return number.byteValue() == Byte.parseByte(nullValue);
            }
            if (value instanceof Short number) {
                return number.shortValue() == Short.parseShort(nullValue);
            }
            if (value instanceof Integer number) {
                return number.intValue() == Integer.parseInt(nullValue);
            }
            if (value instanceof Long number) {
                return number.longValue() == Long.parseLong(nullValue);
            }
            if (value instanceof Float number) {
                return number.floatValue() == Float.parseFloat(nullValue);
            }
            if (value instanceof Double number) {
                return number.doubleValue() == Double.parseDouble(nullValue);
            }
            if (value instanceof Boolean flag) {
                return flag == Boolean.parseBoolean(nullValue);
            }
            if (value instanceof BigDecimal decimal) {
                return decimal.compareTo(new BigDecimal(nullValue)) == 0;
            }
            if (value instanceof BigInteger integer) {
                return integer.equals(new BigInteger(nullValue));
            }
            if (value instanceof Date date) {
                SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss");
                return format.parse(nullValue).equals(date);
            }
            return value.toString().equals(nullValue);
        } catch (java.text.ParseException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部实现

    @Nullable
    private static Object invokeGetter(Object target, String property) {
        Method getter = getter(target.getClass(), property);
        if (getter == null) {
            return null;
        }
        try {
            return getter.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    /** getter 查找：{@code getXxx()} / {@code isXxx()}；与 {@code ClassInfo} 的驼峰命名一致。 */
    @Nullable
    private static Method getter(Class<?> type, String property) {
        if (property.isEmpty()) {
            return null;
        }
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        try {
            return type.getMethod("get" + suffix);
        } catch (NoSuchMethodException ignored) {
            // 落到 is 前缀
        }
        try {
            return type.getMethod("is" + suffix);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /** {@code ComplexBeanProbe.getPropertyTypeForGetter} 的 JavaBean 分支（点号逐段走 getter 返回类型）。 */
    private static Class<?> beanGetterType(Class<?> type, @Nullable String name) {
        if (name == null || name.isEmpty()) {
            return Object.class;
        }
        Class<?> current = type;
        for (String segment : name.split("\\.")) {
            if (segment.isEmpty()) {
                return Object.class;
            }
            if (Map.class.isAssignableFrom(current)) {
                return Object.class;
            }
            Method getter = getter(current, segment);
            if (getter == null) {
                return Object.class;
            }
            current = getter.getReturnType();
        }
        return current;
    }

    private static boolean isIndexedContainer(Object value) {
        return value instanceof List<?> || value.getClass().isArray();
    }

    @Nullable
    private static Object elementAt(@Nullable Object container, int index) {
        if (container == null) {
            return null;
        }
        if (container instanceof List<?> list) {
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        if (container.getClass().isArray()) {
            try {
                return java.lang.reflect.Array.get(container, index);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** {@code TypeHandlerFactory} 内置处理器覆盖的标量族（{@code $x$} 的取值分派判据）。 */
    private static final List<Class<?>> SCALAR_TYPES = List.of(
            String.class,
            CharSequence.class,
            Character.class,
            Boolean.class,
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            BigDecimal.class,
            BigInteger.class,
            Date.class,
            Number.class,
            java.sql.Date.class,
            java.sql.Time.class,
            java.sql.Timestamp.class,
            byte[].class);
}
