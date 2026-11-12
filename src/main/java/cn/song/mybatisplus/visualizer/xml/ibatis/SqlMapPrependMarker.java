// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/SqlMapPrependMarker.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagKind;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParamMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@code removeFirstPrepend} marker 栈 + {@code <iterate>} 迭代上下文 + 标签求值上下文
 * （T-Ibatis2Support 券 B，复刻 {@code SqlTagContext} / {@code RemoveFirstPrependMarker} / {@code IterateContext}）。
 *
 * <p><b>本机制是 iBatis 2 独有的文本拼装逻辑，MyBatis 3 没有对应物</b>：
 * {@code <dynamic prepend="...">} 会「吃掉」第一个产出内容的子标签的 prepend；
 * 吃掉动作通过 {@link IbatisTagContext#disableParentRemoveFirstPrepend()} 操作 <b>marker 栈的第 1 层（父层）</b>实现
 * ——引擎源码 {@code SqlTagContext.peekRemoveFirstPrependMarker} / {@code disableRemoveFirstPrependMarker} 都是
 * {@code stack.get(1)}，这是<b>刻意如此</b>（子标签的 doStartFragment 已把自己压在第 0 层）。</p>
 *
 * <p>k2j 承载形态：master 侧本文件的 {@code RemoveFirstPrependMarker} / {@code IbatisIterateContext} /
 * {@code IbatisTagContext} 三个 top-level 类聚合落为本类的嵌套类型。</p>
 */
public final class SqlMapPrependMarker {

    private SqlMapPrependMarker() {
    }

    /** master 侧同名的 top-level class（本嵌套形态对齐「第 0 层 = 栈顶」语义的持有者）。 */
    public static final class RemoveFirstPrependMarker {

        private final IbatisTagNode tag;
        private boolean removeFirstPrepend;

        public RemoveFirstPrependMarker(IbatisTagNode tag, boolean removeFirstPrepend) {
            this.tag = tag;
            this.removeFirstPrepend = removeFirstPrepend;
        }

        public IbatisTagNode getTag() {
            return tag;
        }

        public boolean isRemoveFirstPrepend() {
            return removeFirstPrepend;
        }

        public void setRemoveFirstPrepend(boolean value) {
            this.removeFirstPrepend = value;
        }
    }

    /**
     * {@code <iterate>} 的迭代上下文（引擎 {@code IterateContext}）。
     *
     * <p>下标语义：{@code index} 从 -1 起，{@link #next()} 先自增再取元素；{@code #ids[]#} 里的 {@code []} 由
     * {@link #addIndexToTagProperty(String)} 逐次替换为 {@code ids[0]}/{@code ids[1]}…。</p>
     */
    public static final class IbatisIterateContext {

        private final Iterator<?> iterator;

        private final IbatisTagNode tag;

        private final IbatisIterateContext parent;

        private int index = -1;

        private String property = "";

        private boolean allowNext = true;

        private boolean finalState = false;

        private boolean someSubElementsHaveContent = false;

        private boolean prependEnabled = false;

        public IbatisIterateContext(Object collection, IbatisTagNode tag, IbatisIterateContext parent) {
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
                throw new IbatisModel.IbatisRenderException(
                        "iterate 的 property 取值不是 Collection / Array / Iterator：property='" + tag.getProperty() + "'");
            }
        }

        public int getIndex() {
            return index;
        }

        public String getProperty() {
            return property;
        }

        public void setProperty(String value) {
            this.property = value;
        }

        public boolean isAllowNext() {
            return allowNext;
        }

        public void setAllowNext(boolean value) {
            this.allowNext = value;
        }

        /** master 侧 {@code var isFinal}（Java 侧改名 {@code finalState}，避免与保留字形态混淆）。 */
        public boolean isFinal() {
            return finalState;
        }

        public void setFinal(boolean value) {
            this.finalState = value;
        }

        public boolean isSomeSubElementsHaveContent() {
            return someSubElementsHaveContent;
        }

        public void setSomeSubElementsHaveContent(boolean value) {
            this.someSubElementsHaveContent = value;
        }

        public boolean isPrependEnabled() {
            return prependEnabled;
        }

        public void setPrependEnabled(boolean value) {
            this.prependEnabled = value;
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

        private TagProperty processTagProperty(String tagProperty) {
            if (parent != null) {
                TagProperty parentResult = parent.processTagProperty(tagProperty);
                return addIndex(parentResult.first(), parentResult.second());
            }
            return addIndex(tagProperty, 0);
        }

        /**
         * 引擎 {@code addIndex}：把 {@code endProperty + "["} 之后的第一个 {@code ]} 处插入当前下标。
         *
         * <p>引擎用 {@code input.charAt(propertyIndex + endProperty.length())} 判「待填下标」——
         * 越界时抛 {@code StringIndexOutOfBoundsException}；本链对越界按「不匹配」处理（畸形输入不打断整篇，
         * 与券 B 的降级口径一致，合法输入上与引擎逐字节等价）。</p>
         */
        private TagProperty addIndex(String input, int startIndex) {
            String endProperty = endProperty() + "[";
            int propertyIndex = input.indexOf(endProperty, startIndex);
            if (propertyIndex < 0) {
                return new TagProperty(input, 0);
            }
            int after = propertyIndex + endProperty.length();
            if (after >= input.length() || input.charAt(after) != ']') {
                return new TagProperty(input, 0);
            }
            return new TagProperty(input.substring(0, after) + index + input.substring(after), after);
        }

        private List<Object> arrayToList(Object array) {
            List<Object> result = new ArrayList<>();
            if (array instanceof Object[] objects) {
                result.addAll(Arrays.asList(objects));
                return result;
            }
            int length = java.lang.reflect.Array.getLength(array);
            for (int i = 0; i < length; i++) {
                result.add(java.lang.reflect.Array.get(array, i));
            }
            return result;
        }

        /** master 侧 Kotlin {@code Pair<String, Int>} 的本地载体（{@code first}/{@code second} 同名口径）。 */
        private record TagProperty(String first, int second) {
        }
    }

    /**
     * 求值上下文（引擎 {@code SqlTagContext} 的栈与参数侧）。
     *
     * <p>文本缓冲由渲染器按「每个标签体一个 {@code StringBuilder}」管理（对齐引擎的
     * 每标签一个 {@code StringWriter} + 处理后才有条件 {@code out.print(body)}），本类只负责
     * marker 栈、iterate 栈、属性表与参数序列。</p>
     */
    public static final class IbatisTagContext {

        private static final String RFP_TRUE = "true";
        private static final String RFP_ITERATE = "iterate";

        private final List<RemoveFirstPrependMarker> markers = new ArrayList<>();

        private final List<IbatisIterateContext> iterateStack = new ArrayList<>();

        private final Map<IbatisTagNode, IbatisIterateContext> attributes = new HashMap<>();

        private final List<IbatisParamMapping> parameterMappings = new ArrayList<>();

        private final List<String> warnings = new ArrayList<>();

        // ---------------------------------------------------------------- marker 栈

        /** 引擎 {@code pushRemoveFirstPrependMarker}：三分支判定（详见类 KDoc 与 {@link #computeRemoveFirstPrepend}）。 */
        public void pushRemoveFirstPrependMarker(IbatisTagNode tag) {
            markers.add(0, new RemoveFirstPrependMarker(tag, computeRemoveFirstPrepend(tag)));
        }

        /** 引擎 {@code popRemoveFirstPrependMarker}：只有栈顶就是本标签时才弹（迭代重复进入时不重复压栈）。 */
        public void popRemoveFirstPrependMarker(IbatisTagNode tag) {
            if (!markers.isEmpty() && markers.get(0).getTag() == tag) {
                markers.remove(0);
            }
        }

        /** 引擎 {@code peekRemoveFirstPrependMarker}：读 <b>父层</b>（第 1 层）状态。 */
        public boolean peekParentRemoveFirstPrepend() {
            return markers.size() > 1 && markers.get(1).isRemoveFirstPrepend();
        }

        /** 引擎 {@code disableRemoveFirstPrependMarker}：置 <b>父层</b> 为 false（= 父 prepend 已被用掉）。 */
        public void disableParentRemoveFirstPrepend() {
            if (markers.size() > 1) {
                markers.get(1).setRemoveFirstPrepend(false);
            }
        }

        /** 引擎 {@code reEnableRemoveFirstPrependMarker}：置 <b>栈顶（本层）</b> 为 true（{@code removeFirstPrepend="iterate"} 用）。 */
        public void reEnableRemoveFirstPrepend() {
            if (!markers.isEmpty()) {
                markers.get(0).setRemoveFirstPrepend(true);
            }
        }

        // ---------------------------------------------------------------- iterate 栈

        public void pushIterateContext(IbatisIterateContext context) {
            iterateStack.add(0, context);
        }

        public void popIterateContext() {
            if (!iterateStack.isEmpty()) {
                iterateStack.remove(0);
            }
        }

        /** 栈顶上下文（空栈返回 null，对齐 Kotlin 可空返回）。 */
        public IbatisIterateContext peekIterateContext() {
            return iterateStack.isEmpty() ? null : iterateStack.get(0);
        }

        public void setAttribute(IbatisTagNode tag, IbatisIterateContext value) {
            attributes.put(tag, value);
        }

        public IbatisIterateContext getAttribute(IbatisTagNode tag) {
            return attributes.get(tag);
        }

        public List<IbatisParamMapping> getParameterMappings() {
            return parameterMappings;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        // ---------------------------------------------------------------- 属性解析

        /**
         * 引擎 {@code ConditionalTagHandler.getResolvedProperty} / {@code BaseTagHandler} 的
         * {@code tag.getPropertyAttr()}。
         *
         * <p><b>副作用是契约的一部分</b>：在 iterate 内求值时会把当前迭代推进一格
         * （{@code allowNext} 保证同一次迭代内只推进一次，与 {@code IterateTagHandler.doEndFragment} 的推进互斥）。</p>
         */
        public String resolveProperty(IbatisTagNode tag) {
            String prop = tag.getProperty();
            if (prop == null) {
                return null;
            }
            IbatisIterateContext iterate = peekIterateContext();
            if (iterate != null && iterate.isAllowNext()) {
                iterate.next();
                iterate.setAllowNext(false);
                if (!iterate.hasNext()) {
                    iterate.setFinal(true);
                }
            }
            if (prop.indexOf('[') > -1 && iterate != null) {
                return iterate.addIndexToTagProperty(prop);
            }
            return prop;
        }

        /** 引擎 {@code pushRemoveFirstPrependMarker} 的初始状态判定。 */
        private boolean computeRemoveFirstPrepend(IbatisTagNode tag) {
            IbatisTagNode parentTag = tag.getParent();
            if (tag.getKind() == IbatisTagKind.DYNAMIC) {
                return tag.hasPrepend();
            }
            if (RFP_TRUE.equals(tag.getRemoveFirstPrepend()) || RFP_ITERATE.equals(tag.getRemoveFirstPrepend())) {
                return true;
            }
            if (!tag.hasPrepend() && parentTag != null) {
                return RFP_TRUE.equals(parentTag.getRemoveFirstPrepend())
                        || RFP_ITERATE.equals(parentTag.getRemoveFirstPrepend());
            }
            return false;
        }
    }
}
