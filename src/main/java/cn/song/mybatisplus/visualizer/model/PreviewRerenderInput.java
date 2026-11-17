package cn.song.mybatisplus.visualizer.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-Ibatis2Gap 券 A：模型级重渲染的面板状态输入（渲染接缝 A 的回调实参）。
 *
 * <p>券 A 原案是 {@code Function<Map<String, String>, PreviewResult>}；实现时条件勾选（iBatis 的
 * 开关 = Map 里放不放 key）与 iterate 值列表没法折进一个 {@code Map<String, String>}，故泛化为
 * 三段输入——仍是 model 层的<b>通用</b>纯数据（不依赖 ibatis/面板类型），{@code params} 键 = 参数名
 * （含条件值输入），{@code enabledConditionIds} 键 = 条件行 id，{@code collections} 键 = 集合属性名。
 * 默认值齐备，非 iBatis 链不构造。
 */
public record PreviewRerenderInput(
        @NotNull Map<String, String> params,
        @NotNull Set<String> enabledConditionIds,
        @NotNull Map<String, List<String>> collections) {

    public PreviewRerenderInput {
        params = Map.copyOf(params);
        enabledConditionIds = Set.copyOf(enabledConditionIds);
        collections = Map.copyOf(collections);
    }

    /** 全空默认形态（Kotlin 默认参数等价）。 */
    public PreviewRerenderInput() {
        this(Map.of(), Set.of(), Map.of());
    }

    /** 只带 params 的形态（既有面板回调的最简调用面）。 */
    public PreviewRerenderInput(@NotNull Map<String, String> params) {
        this(params, Set.of(), Map.of());
    }
}
