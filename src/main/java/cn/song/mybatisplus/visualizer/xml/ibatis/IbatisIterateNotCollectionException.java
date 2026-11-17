package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * 券 D：{@code <iterate>} 取值非集合的分类载体。
 *
 * <p>抛点在 {@link SqlMapSqlRenderer} 的 startIterate——那里已知标签与 property，包一层才能把
 * 「取值不是集合」升级成可分类（ITERATE_NOT_COLLECTION）与可出分档文案的形态；上层
 * （renderBody 的顶层回退）据此填 {@link IbatisUnrenderedNode} 的 reason/property。
 *
 * <p>直接继承 {@code RuntimeException} 而不是 {@link IbatisRenderException}：分类走
 * {@code classifyFailure} 的 {@code instanceof} 判定型分支，不依赖类型层级。
 */
public final class IbatisIterateNotCollectionException extends RuntimeException {

    /** {@code <iterate property="...">} 的 property 原值（可为 null——无 property 时取参数对象本身）。 */
    @Nullable
    private final String property;

    public IbatisIterateNotCollectionException(@Nullable String property, String message) {
        super(message);
        this.property = property;
    }

    @Nullable
    public String getProperty() {
        return property;
    }
}
