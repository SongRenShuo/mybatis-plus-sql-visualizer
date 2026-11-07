package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.Nullable;

/**
 * UI-004：底部方言闸门「渲染方言」取值与「方言无关载体」判据（纯函数，零平台依赖）。
 *
 * <p>现场：面板顶部 {@code 方言: MySQL（已绑定数据源）}、SQL 文本已是 MySQL 形态、右上 {@code 已绑定: mp_demo@localhost}，
 * 底部红字却写「当前 SQL 按 PostgreSQL 渲染，所选数据源是 MySQL」。矛盾来源是<b>两份方言事实</b>：</p>
 * <ul>
 *   <li>{@code currentDialectDecision}（顶部状态行）：执行/绑定后会经 {@code refreshDialectStatusRow} 重算升级；</li>
 *   <li>{@code PreviewResult.effectiveDialect}（底部闸门取的值）：预览生成时固化，只有模型/批量载体能被
 *       {@code rerenderForExecutionTarget} 重写；XML 载体（{@code xmlModel != null}）重渲染直接返回 false，
 *       于是停在旧值（PostgreSQL）。</li>
 * </ul>
 *
 * <p>判据：<b>载体文本是否由插件按方言生成</b>。</p>
 * <ul>
 *   <li>模型/批量载体（{@code previewModel}/{@code batchModel}）的 SQL 是插件按 {@code dialect} 生成的，{@code effectiveDialect}
 *       就是渲染事实——闸门必须用它（用户改 IDEA 方言后若没真正重渲染，就该报真实差异）；</li>
 *   <li>XML 原文与纯文本载体由用户书写，插件不做方言改写（分页 LIMIT 行除外），{@code effectiveDialect}
 *       只是当时的决策快照，不是渲染事实——取<b>最新决策</b>才与顶部状态行同源。</li>
 * </ul>
 *
 * <p>注意：这不是「用字符串替换冒充重渲染」。方言无关载体不改一个字符，只是把记录的方言证据
 * 更新为最新决策；{@code canLosslesslyRerender=false} 的事实（XML/分页载体无法按目标方言无损重渲染）
 * 在 {@code executionDialectGateRequest} 里照旧保留。</p>
 */
public final class DialectGateRenderDialect {

    private DialectGateRenderDialect() {
    }

    /**
     * 载体文本是否与插件渲染方言无关（用户书写、插件不按方言改写）。
     *
     * @param hasPreviewModel        模型级重渲染载体（SQL 由插件按 dialect 生成）
     * @param hasBatchModel          批量 CRUD 载体（同上）
     * @param hasPaginationLimitLine 分页模拟追加的 LIMIT 行（方言敏感，按 {@code renderLimit} 生成）
     */
    public static boolean isDialectNeutralCarrier(
            boolean hasPreviewModel,
            boolean hasBatchModel,
            boolean hasPaginationLimitLine) {
        return !hasPreviewModel && !hasBatchModel && !hasPaginationLimitLine;
    }

    /**
     * 闸门「渲染方言」取值：方言无关载体取最新决策，方言敏感载体取渲染事实。
     *
     * <p>两者都缺席时返回 null（调用方保留既有兼容兜底，不在此处硬编码）。</p>
     */
    @Nullable
    public static String resolve(
            @Nullable String effectiveDialectName,
            @Nullable String decisionDialectName,
            boolean dialectNeutral) {
        String effective = effectiveDialectName != null && !effectiveDialectName.isBlank() ? effectiveDialectName : null;
        String decision = decisionDialectName != null && !decisionDialectName.isBlank() ? decisionDialectName : null;
        if (dialectNeutral) {
            return decision != null ? decision : effective;
        }
        return effective != null ? effective : decision;
    }
}
