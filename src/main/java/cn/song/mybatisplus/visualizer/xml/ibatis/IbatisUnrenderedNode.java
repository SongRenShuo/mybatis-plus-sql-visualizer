package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * 券 D：降级时「哪个顶层节点之后开始未渲染」的结构化事实。
 *
 * <p>位置口径：解析器（DOM）没有保留原文行号/偏移，故只给<b>失败节点在
 * {@link IbatisStatement#getBody()} 顶层的位置（0 基）</b>——即「第 N 个顶层节点（含其后全部内容）
 * 未渲染」。渲染到此节点前已写入的输出与既有回退拼装（顶层文本节点）口径一致。
 *
 * @param tagName      失败的顶层标签名；顶层文本节点抛错时为 null（文本节点路径 token 解析只告警不抛，实践中不会出现）
 * @param topNodeIndex 失败节点在语句体顶层的位置（0 基）
 * @param reason       失败原因分类（UI 分档显示：ITERATE_NOT_COLLECTION = 待填值，不是错误）
 * @param property     失败涉及的属性名（来自抛点：{@code <iterate property="ids">} 的 ids）——供 UI 提示「该语句需要此参数」
 * @param message      引擎/渲染器的原始错误消息（可回溯）
 */
public record IbatisUnrenderedNode(
        @Nullable String tagName,
        int topNodeIndex,
        IbatisRenderFailureKind reason,
        @Nullable String property,
        @Nullable String message) {

    public IbatisUnrenderedNode {
        java.util.Objects.requireNonNull(reason, "reason");
    }

    /** 只带必填三项的形态（property/message 缺省）。 */
    public IbatisUnrenderedNode(@Nullable String tagName,
                                int topNodeIndex,
                                IbatisRenderFailureKind reason) {
        this(tagName, topNodeIndex, reason, null, null);
    }
}
