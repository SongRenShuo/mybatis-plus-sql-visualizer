// T-SyncK2JMaster309 自 execute/ExecuteConfirmMessages.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * T-UnboundedWriteGuard：执行确认消息构建（纯函数，零 Swing 依赖可直接测）。
 *
 * <p>消费方三处确认流共用（LogToSqlPanel.executeStatement / SqlPreviewPanel.executeSql /
 * SqlPreviewPanel.executeAdhocSql）——同一对话框形态只换 message 文案：</p>
 * <ul>
 *  <li>无危险发现：文案逐字节走既有 {@code execute.confirm.dml.message}（频率/形态零变化）；</li>
 *  <li>有危险发现：摘要行 + 按类别去重的发现清单（每类一行，文案中性说事实）。</li>
 * </ul>
 *
 * <p>不新增弹窗形态、不新增步骤（弹窗克制红线 {@code docs/规范/设计规范-弹窗克制红线.md}）。</p>
 */
public final class ExecuteConfirmMessages {

    public static final ExecuteConfirmMessages INSTANCE = new ExecuteConfirmMessages();
    private ExecuteConfirmMessages() {}

    /** 无危险发现 → 既有文案；有 → 升级为发现清单。 */
    @org.jetbrains.annotations.NotNull
    public static String build(@org.jetbrains.annotations.NotNull String sql, @org.jetbrains.annotations.NotNull String keyword) {
        return build(sql, keyword, null, java.util.List.of());
    }

    @org.jetbrains.annotations.NotNull
    public static String build(@org.jetbrains.annotations.NotNull String sql, @org.jetbrains.annotations.NotNull String keyword,
                               @org.jetbrains.annotations.Nullable cn.song.mybatisplus.visualizer.sql.Dialect dialect) {
        return build(sql, keyword, dialect, java.util.List.of());
    }

    /**
     * 无任何发现 → 既有文案；有险 → 升级为发现清单。
     *
     * <p>R52（方言能力自检）：dialect 非空时追加<b>方言能力缺口</b>发现（{@link cn.song.mybatisplus.visualizer.sql.DialectCapabilities#gapsFor}）
     * ——SQL 用到了本方言不支持的形态（DELETE LIMIT / UPSERT / identity 包装）时执行前如实提示。
     * dialect=null 时逐字节维持既有行为（旧调用点与既有测试零回归）。</p>
     *
     * @param dialect           当前生效方言（R52；null = 不做能力自检）
     * @param additionalFindings R46 等上游预检已渲染好的发现行（含 {@code • } 前缀；无无界写发现时
     *                           与能力缺口同走 {@code execute.confirm.precheck.summary} 抬头）
     */
    @org.jetbrains.annotations.NotNull
    public static String build(@org.jetbrains.annotations.NotNull String sql, @org.jetbrains.annotations.NotNull String keyword,
                               @org.jetbrains.annotations.Nullable cn.song.mybatisplus.visualizer.sql.Dialect dialect,
                               @org.jetbrains.annotations.NotNull java.util.List<String> additionalFindings) {
        UnboundedWriteDetector.WriteRisk risk = UnboundedWriteDetector.detect(sql);
        java.util.List<String> riskLines = new ArrayList<>();
        if (risk.isDangerous()) {
            // Kotlin: risk.kinds().sorted().joinToString("\n") — 排序去重后逐类一行
            Set<UnboundedWriteDetector.FindingKind> sorted = new TreeSet<>(risk.kinds());
            for (UnboundedWriteDetector.FindingKind kind : sorted) {
                riskLines.add("• " + MpSqlVisualizerBundle.message(messageKeyOf(kind)));
            }
        }
        java.util.List<String> gapLines = new ArrayList<>();
        if (dialect != null) {
            for (cn.song.mybatisplus.visualizer.sql.DialectCapabilities.CapabilityGap gap
                    : cn.song.mybatisplus.visualizer.sql.DialectCapabilities.gapsFor(sql, dialect)) {
                gapLines.add("• " + MpSqlVisualizerBundle.message(gap.messageKey(), gap.dialectName()));
            }
        }
        // 全部为空 → 既有文案逐字节不变（确认流零变化红线）
        if (riskLines.isEmpty() && gapLines.isEmpty() && additionalFindings.isEmpty()) {
            return MpSqlVisualizerBundle.message("execute.confirm.dml.message", keyword);
        }
        // 无无界写发现（仅能力缺口/预检发现）→ 通用预检抬头（不冒用「无界写特征」）
        if (riskLines.isEmpty()) {
            java.util.List<String> merged = new ArrayList<>(gapLines);
            merged.addAll(additionalFindings);
            return MpSqlVisualizerBundle.message("execute.confirm.precheck.summary", keyword,
                    String.join("\n", merged));
        }
        // 有无界写发现（可同时带能力缺口/预检发现）→ 走既有 unbounded.summary（既有测试逐字节钉住）
        java.util.List<String> merged = new ArrayList<>(riskLines);
        merged.addAll(gapLines);
        merged.addAll(additionalFindings);
        return MpSqlVisualizerBundle.message("execute.confirm.unbounded.summary", keyword, String.join("\n", merged));
    }

    /** 发现类别 → 文案键（单一映射点；键值见 bundle execute.confirm.unbounded.* 段）。
     *  跨包公开：share.format 诊断报告直接复用本映射（Kotlin 侧为 internal）。 */
    public static String messageKeyOf(@org.jetbrains.annotations.NotNull UnboundedWriteDetector.FindingKind kind) {
        switch (kind) {
            case MISSING_WHERE: return "execute.confirm.unbounded.missingWhere";
            case TAUTOLOGY: return "execute.confirm.unbounded.tautology";
            case OR_TAUTOLOGY: return "execute.confirm.unbounded.orTautology";
            case LIKE_WILDCARD: return "execute.confirm.unbounded.likeWildcard";
            case NO_COLUMN_REF: return "execute.confirm.unbounded.noColumnRef";
            case SUBQUERY: return "execute.confirm.unbounded.subquery";
            case PARSE_FAILED: return "execute.confirm.unbounded.parseFailed";
            default: throw new IllegalArgumentException("unknown kind: " + kind);
        }
    }
}
