package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 求值器的模拟可迭代值（T-M24-02）：foreach 展开 N 次，元素为无属性 MockObject。
 *
 * <p>T-MbgExamplePreview M2：可携带元素级模拟值（从调用点反推的 Criteria / Criterion）。
 * 给了 {@code elements} 就按元素展开、忽略 {@code size}；元素只读共享，迭代器每次返回同一批对象。</p>
 */
public class MockIterable implements Iterable<MockObject> {

    private final int size;

    /** 元素级模拟值（T-MbgExamplePreview M2）；null = 按 {@code size} 展开无属性元素。 */
    private final List<MockObject> elements;

    /** master 侧默认参量便捷构造器（{@code elements = null}）。 */
    public MockIterable(int size) {
        this(size, null);
    }

    public MockIterable(int size, List<MockObject> elements) {
        this.size = size;
        this.elements = elements;
    }

    /** 展开次数。 */
    public int getSize() {
        return size;
    }

    @Override
    public Iterator<MockObject> iterator() {
        if (elements != null) {
            return elements.iterator();
        }
        List<MockObject> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(new MockObject(Collections.emptyMap()));
        }
        return list.iterator();
    }
}
