package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 求值上下文（引擎 {@code SqlTagContext} 的栈与参数侧）。
 *
 * <p>文本缓冲由渲染器按「每个标签体一个 {@code StringBuilder}」管理（对齐引擎的
 * 每标签一个 {@code StringWriter} + 处理后才有条件 {@code out.print(body)}），本类只负责
 * marker 栈、iterate 栈、属性表与参数序列。
 */
public final class IbatisTagContext {

    private final List<RemoveFirstPrependMarker> markers = new ArrayList<>();

    private final List<IbatisIterateContext> iterateStack = new ArrayList<>();

    private final Map<IbatisTagNode, IbatisIterateContext> attributes = new HashMap<>();

    private final List<IbatisParamMapping> parameterMappings = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    public List<IbatisParamMapping> getParameterMappings() {
        return parameterMappings;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    // ---------------------------------------------------------------- marker 栈

    /** 引擎 {@code pushRemoveFirstPrependMarker}：三分支判定（详见类 KDoc 与 {@link #computeRemoveFirstPrepend}）。 */
    public void pushRemoveFirstPrependMarker(IbatisTagNode tag) {
        markers.add(0, new RemoveFirstPrependMarker(tag, computeRemoveFirstPrepend(tag)));
    }

    /** 引擎 {@code popRemoveFirstPrependMarker}：只有栈顶就是本标签时才弹（迭代重复进入时不重复压栈）。 */
    public void popRemoveFirstPrependMarker(IbatisTagNode tag) {
        if (!markers.isEmpty() && markers.get(0).tag == tag) {
            markers.remove(0);
        }
    }

    /** 引擎 {@code peekRemoveFirstPrependMarker}：读<b>父层</b>（第 1 层）状态。 */
    public boolean peekParentRemoveFirstPrepend() {
        return markers.size() > 1 && markers.get(1).removeFirstPrepend;
    }

    /** 引擎 {@code disableRemoveFirstPrependMarker}：置<b>父层</b>为 false（= 父 prepend 已被用掉）。 */
    public void disableParentRemoveFirstPrepend() {
        if (markers.size() > 1) {
            markers.get(1).removeFirstPrepend = false;
        }
    }

    /** 引擎 {@code reEnableRemoveFirstPrependMarker}：置<b>栈顶（本层）</b>为 true（{@code removeFirstPrepend="iterate"} 用）。 */
    public void reEnableRemoveFirstPrepend() {
        if (!markers.isEmpty()) {
            markers.get(0).removeFirstPrepend = true;
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

    @Nullable
    public IbatisIterateContext peekIterateContext() {
        return iterateStack.isEmpty() ? null : iterateStack.get(0);
    }

    public void setAttribute(IbatisTagNode tag, @Nullable IbatisIterateContext value) {
        if (value == null) {
            attributes.remove(tag);
        } else {
            attributes.put(tag, value);
        }
    }

    @Nullable
    public IbatisIterateContext getAttribute(IbatisTagNode tag) {
        return attributes.get(tag);
    }

    // ---------------------------------------------------------------- 属性解析

    /**
     * 引擎 {@code ConditionalTagHandler.getResolvedProperty} / {@code BaseTagHandler} 的
     * {@code tag.getPropertyAttr()}。
     *
     * <p><b>副作用是契约的一部分</b>：在 iterate 内求值时会把当前迭代推进一格
     * （{@code allowNext} 保证同一次迭代内只推进一次，与 {@code IterateTagHandler.doEndFragment}
     * 的推进互斥）。
     */
    @Nullable
    public String resolveProperty(IbatisTagNode tag) {
        String prop = tag.getProperty();
        if (prop == null) {
            return null;
        }
        IbatisIterateContext iterate = peekIterateContext();
        if (iterate != null && iterate.allowNext) {
            iterate.next();
            iterate.allowNext = false;
            if (!iterate.hasNext()) {
                iterate.isFinal = true;
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
        if ("true".equals(tag.getRemoveFirstPrepend()) || "iterate".equals(tag.getRemoveFirstPrepend())) {
            return true;
        }
        if (!tag.hasPrepend() && parentTag != null) {
            return "true".equals(parentTag.getRemoveFirstPrepend())
                    || "iterate".equals(parentTag.getRemoveFirstPrepend());
        }
        return false;
    }
}
