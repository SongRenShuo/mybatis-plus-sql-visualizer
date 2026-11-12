// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/model/PreviewResult.kt 拆件移植
// （master PreviewResult.kt 同文件第二个 data class PreviewRerenderInput；K2J 按 data class→record 拆件惯例落独立文件。）
package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-Ibatis2Gap 券 A：模型级重渲染的面板状态输入（渲染接缝 A 的回调实参）。
 *
 * <p>券 A 原案是 {@code ((Map<String, String>) -> PreviewResult)?}；实现时条件勾选（iBatis 的开关 = Map 里
 * 放不放 key）与 iterate 值列表没法折进一个 {@code Map<String, String>}，故泛化为三段输入——仍是
 * model 层的<b>通用</b>纯数据（不依赖 ibatis/面板类型），{@code params} 键 = 参数名（含条件值输入），
 * {@code enabledConditionIds} 键 = 条件行 id，{@code collections} 键 = 集合属性名。默认值齐备，非 iBatis 链不构造。</p>
 */
public record PreviewRerenderInput(Map<String, String> params,
                                   Set<String> enabledConditionIds,
                                   Map<String, List<String>> collections) {

    public PreviewRerenderInput {
        params = params != null ? params : Map.of();
        enabledConditionIds = enabledConditionIds != null ? enabledConditionIds : Set.of();
        collections = collections != null ? collections : Map.of();
    }

    /** master 默认参形态（全空：无参、无条件勾选、无集合）。 */
    public PreviewRerenderInput() {
        this(Map.of(), Set.of(), Map.of());
    }

    /** master {@code copy()} 手工重建（data class copy 语义：未列出的量沿用本实例）。 */
    public PreviewRerenderInput copy(Map<String, String> params,
                                     Set<String> enabledConditionIds,
                                     Map<String, List<String>> collections) {
        return new PreviewRerenderInput(
                params != null ? params : this.params,
                enabledConditionIds != null ? enabledConditionIds : this.enabledConditionIds,
                collections != null ? collections : this.collections);
    }
}
