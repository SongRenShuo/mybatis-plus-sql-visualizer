package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 求值器的模拟可迭代值（T-M24-02）：foreach 展开 N 次，元素为无属性 MockObject。
 *
 * <p>T-MbgExamplePreview M2：可携带元素级模拟值（从调用点反推的 Criteria / Criterion）。
 * 给了 elements 就按元素展开、忽略 size；元素只读共享，迭代器每次返回同一批对象。
 */
public class MockIterable implements Iterable<MockObject> {

    private final int size;
    private final List<MockObject> elements;

    public MockIterable(int size) {
        this(size, null);
    }

    /** 元素级模拟值形态（T-MbgExamplePreview M2）；elements 优先于 size。 */
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
