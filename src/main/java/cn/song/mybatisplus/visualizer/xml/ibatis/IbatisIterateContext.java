package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * {@code <iterate>} 的迭代上下文（引擎 {@code IterateContext}）。
 *
 * <p>下标语义：{@code index} 从 -1 起，{@link #next()} 先自增再取元素；
 * {@code #ids[]#} 里的 {@code []} 由 {@link #addIndexToTagProperty} 逐次替换为
 * {@code ids[0]}/{@code ids[1]}…。
 */
public final class IbatisIterateContext {

    @Nullable
    private final Iterator<?> iterator;
    public final IbatisTagNode tag;
    @Nullable
    public final IbatisIterateContext parent;

    private int index = -1;

    public String property = "";

    public boolean allowNext = true;

    public boolean isFinal = false;

    public boolean someSubElementsHaveContent = false;

    public boolean isPrependEnabled = false;

    public IbatisIterateContext(@Nullable Object collection,
                                IbatisTagNode tag,
                                @Nullable IbatisIterateContext parent) {
        this.tag = tag;
        this.parent = parent;
        // 引擎 IterateContext 构造器：非 Collection/Iterator/数组一律抛 SqlMapException；
        // collection 为 null 时 `collection.getClass()` 直接 NPE——本链折成可回溯的渲染异常。
        if (collection instanceof Collection<?> collectionValue) {
            this.iterator = collectionValue.iterator();
        } else if (collection instanceof Iterator<?> iteratorValue) {
            this.iterator = iteratorValue;
        } else if (collection != null && collection.getClass().isArray()) {
            this.iterator = arrayToList(collection).iterator();
        } else {
            throw new IbatisRenderException(
                    "iterate 的 property 取值不是 Collection / Array / Iterator：property='" + tag.getProperty() + "'");
        }
    }

    public int getIndex() {
        return index;
    }

    public boolean hasNext() {
        return iterator != null && iterator.hasNext();
    }

    public Object next() {
        index++;
        return iterator != null ? iterator.next() : null;
    }

    /** 引擎 {@code isLast()}：{@code iterator != null && !iterator.hasNext()}。 */
    public boolean isLast() {
        return iterator != null && !iterator.hasNext();
    }

    /** {@code DynamicSql.iteratePropertyReplace} 的逐点替换入口。 */
    public String addIndexToTagProperty(String tagProperty) {
        return processTagProperty(tagProperty).first();
    }

    /** 引擎 {@code getEndProperty()}：嵌套 iterate 里剥掉父级 property 前缀，得到本层要加下标的属性名。 */
    private String endProperty() {
        IbatisIterateContext currentParent = parent;
        if (currentParent == null) {
            return property;
        }
        int parentPropertyIndex = property.indexOf(currentParent.property);
        if (parentPropertyIndex > -1) {
            int end1 = property.indexOf(']', parentPropertyIndex);
            int end2 = property.indexOf('.', parentPropertyIndex);
            return property.substring(parentPropertyIndex + Math.max(end1, end2) + 1);
        }
        return property;
    }

    private TagPropertyResult processTagProperty(String tagProperty) {
        if (parent != null) {
            TagPropertyResult parentResult = parent.processTagProperty(tagProperty);
            return addIndex(parentResult.first(), parentResult.second());
        }
        return addIndex(tagProperty, 0);
    }

    /**
     * 引擎 {@code addIndex}：把 {@code endProperty + "["} 之后的第一个 {@code ]} 处插入当前下标。
     *
     * <p>引擎用 {@code input.charAt(propertyIndex + endProperty.length())} 判「待填下标」——
     * 越界时抛 {@code StringIndexOutOfBoundsException}；本链对越界按「不匹配」处理（畸形输入不打断整篇，
     * 与券 B 的降级口径一致，合法输入上与引擎逐字节等价）。
     */
    private TagPropertyResult addIndex(String input, int startIndex) {
        String endProperty = endProperty() + "[";
        int propertyIndex = input.indexOf(endProperty, startIndex);
        if (propertyIndex < 0) {
            return new TagPropertyResult(input, 0);
        }
        int after = propertyIndex + endProperty.length();
        if (after >= input.length() || input.charAt(after) != ']') {
            return new TagPropertyResult(input, 0);
        }
        return new TagPropertyResult(
                input.substring(0, after) + index + input.substring(after), after);
    }

    private static List<Object> arrayToList(Object array) {
        if (array instanceof Object[] objects) {
            return Arrays.asList(objects);
        }
        int length = java.lang.reflect.Array.getLength(array);
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(java.lang.reflect.Array.get(array, i));
        }
        return result;
    }

    /** 引擎 {@code addIndex} 的返回形态（替换后文本 + 新扫描起点）。 */
    private record TagPropertyResult(String first, int second) {
    }
}
