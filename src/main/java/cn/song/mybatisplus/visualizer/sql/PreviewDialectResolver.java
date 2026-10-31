// T-SyncK2JMaster175 afd3d09 自 master sql/PreviewDialectResolver.kt（新增）移植
// （master 同文件顶层扩展函数 PreviewResult.withResolvedDialect 按同文件顶层声明合并惯例
//  承载为本类静态方法：Kotlin 调用方 result.withResolvedDialect(...) → Java PreviewDialectResolver.withResolvedDialect(result, ...)）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * T-DialectInferFix：预览链路唯一的方言决策入口。
 *
 * <p>为什么需要这一层：预览有多条来源（PSI/Wrapper、XML、JPA、SQL 日志、历史、MCP），每条来源能拿到的
 * 证据不同。此前渲染端各自 {@code result.effectiveDialect ?: MySqlDialect}，临时文件映射端又在
 * {@code cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.mapPreviewDialectBeforePsi} 里独立落 MySQL 兜底，
 * 两侧可以指向不同方言——用户看到的「文本是一种方言、报错是另一种方言」就是这么来的。</p>
 *
 * <p>统一优先级（强→弱），与 {@code DialectRegistry.decision} 内部一致：
 * <ol>
 *  <li>本次执行连接的精确方言（日志/历史条目捕获的 JDBC URL，{@code EXACT}）；</li>
 *  <li>面板已绑定 / 上次使用的数据源（{@code STRONG}）；</li>
 *  <li>项目数据源候选（{@code CONSENSUS}/{@code STRONG}）；</li>
 *  <li>项目配置文件 JDBC URL → 构建文件驱动坐标（{@code FALLBACK}，无数据源时的主力证据）；</li>
 *  <li>SQL 文本方言独占特征（{@code FALLBACK}）；</li>
 *  <li>平台为该文件配置/继承的 SQL 方言（{@code DialectRegistry} 内部），仅在映射侧可用；</li>
 *  <li>仍未判定 → 保持 null，由调用方<b>继承平台设置</b>，不再硬编码 MySQL。</li>
 * </ol></p>
 */
public final class PreviewDialectResolver {

    private PreviewDialectResolver() {}

    /** 归一化到注册方言名；未知/空 → null（绝不回落到 MySQL）。 */
    @Nullable
    public static String knownDialect(@Nullable String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        for (Dialect d : DialectRegistry.INSTANCE.getDialects()) {
            if (d.getName().equalsIgnoreCase(trimmed)) {
                return d.getName();
            }
        }
        return null;
    }

    /**
     * 从日志条目的目标库角标（{@code "PostgreSQL · cyzc_db1@host:5432"}）里取方言名。
     *
     * <p>兜底用途：旧条目没有 pd 载荷里的 {@code dl} 键，但角标随 {@code -- … | db: …} 头持久化过。
     * 角标里的显示名与注册名在 MySQL/PostgreSQL/Oracle/SQL Server/H2/SQLite/MariaDB/DM/KingBase/
     * OceanBase/openGauss 上同名；{@code HSQL}/{@code GBASE} 无注册方言 → null（不猜近似方言）。</p>
     */
    @Nullable
    public static String dialectFromBadge(@Nullable String badge) {
        if (badge == null) return null;
        int sep = badge.indexOf('·');
        String head = (sep >= 0 ? badge.substring(0, sep) : badge).trim();
        if (head.isEmpty()) return null;
        return knownDialect(head);
    }

    /** 「本次执行连接的方言」决策（EXACT 证据，不参与候选投票）。 */
    public static DialectDecisionEngine.Decision capturedDecision(String dialectName) {
        return new DialectDecisionEngine.Decision(
                DialectDecisionEngine.Status.RESOLVED,
                dialectName,
                DialectDecisionEngine.Confidence.EXACT,
                DialectDecisionEngine.Source.CAPTURED_URL,
                List.of(),
                List.of(),
                null);
    }

    /** Kotlin 默认参数等价重载：仅项目上下文。 */
    public static DialectDecisionEngine.Decision resolve(@Nullable Project project) {
        return resolve(project, null, null, null);
    }

    /** Kotlin 默认参数等价重载：带捕获方言。 */
    public static DialectDecisionEngine.Decision resolve(@Nullable Project project,
                                                         @Nullable String capturedDialect) {
        return resolve(project, capturedDialect, null, null);
    }

    /** Kotlin 默认参数等价重载：带捕获方言 + 绑定数据源方言。 */
    public static DialectDecisionEngine.Decision resolve(@Nullable Project project,
                                                         @Nullable String capturedDialect,
                                                         @Nullable String boundDataSourceDialect) {
        return resolve(project, capturedDialect, boundDataSourceDialect, null);
    }

    /**
     * 预览结果方言求解（全参）。
     *
     * @param project               项目上下文
     * @param capturedDialect       该预览来源自带的精确方言（日志/历史条目）；
     * @param boundDataSourceDialect 面板已绑定/上次使用数据源方言（调用方须在后台线程取好再传）；
     * @param sqlText               本次 SQL 文本（最后一层语法特征证据）
     */
    public static DialectDecisionEngine.Decision resolve(@Nullable Project project,
                                                         @Nullable String capturedDialect,
                                                         @Nullable String boundDataSourceDialect,
                                                         @Nullable String sqlText) {
        return DialectRegistry.INSTANCE.decision(
                project,
                null,
                null,
                null,
                knownDialect(boundDataSourceDialect),
                sqlText,
                knownDialect(capturedDialect));
    }

    /** Kotlin 默认参数等价重载（boundDataSourceDialect 缺省 null）。 */
    public static PreviewResult withResolvedDialect(PreviewResult result, @Nullable Project project) {
        return withResolvedDialect(result, project, null);
    }

    /**
     * 给预览结果补齐方言（幂等；master 顶层扩展函数 {@code PreviewResult.withResolvedDialect} 的承载）：
     * <ul>
     *  <li><b>已有 {@code dialectDecision} → 以上游决策为准</b>：它带着上游算好的证据（来源、置信度、
     *      候选数据源 UUID 等），面板不得重算覆盖——2026-10-30 修：原判据要求「决策与渲染方言都在场」
     *      才早退，导致 {@code dialectName == null} 的冲突/不可用决策（例如 {@code CONFLICT} + 候选 UUID 清单）
     *      被重算成 {@code UNAVAILABLE} 兜底，按需查看的证据面丢掉候选标识。仅当决策知道方言名而
     *      {@code renderedDialect} 缺席时，用该名字回填渲染方言（渲染端与临时文件映射端仍取同一值）；</li>
     *  <li>没有决策时按 {@link #resolve} 求解；判出来就同时写<b>渲染方言</b>与<b>决策</b>，
     *      保证渲染端与临时文件映射端读到同一个值。</li>
     * </ul>
     */
    public static PreviewResult withResolvedDialect(PreviewResult result, @Nullable Project project,
                                                    @Nullable String boundDataSourceDialect) {
        DialectDecisionEngine.Decision existing = result.getDialectDecision();
        if (existing != null) {
            String name = existing.dialectName();
            if (result.getRenderedDialect() != null || name == null) {
                return result;
            }
            return copyWith(result, DialectRegistry.INSTANCE.byName(name), existing);
        }
        String captured = result.getRenderedDialect() != null ? result.getRenderedDialect().getName() : null;
        DialectDecisionEngine.Decision decision = resolve(project, captured, boundDataSourceDialect, result.getSql());
        String name = decision.dialectName();
        if (name == null) {
            return copyWith(result, result.getRenderedDialect(), decision);
        }
        return copyWith(result, DialectRegistry.INSTANCE.byName(name), decision);
    }

    /** Kotlin {@code copy(renderedDialect = …, dialectDecision = …)} 的手工重建（其余构造参数保持当前值）。 */
    private static PreviewResult copyWith(PreviewResult r, Dialect renderedDialect,
                                          DialectDecisionEngine.Decision dialectDecision) {
        return new PreviewResult(
                r.getSql(), r.getCompactSql(), r.getUnresolvedParams(), r.getParamTable(),
                r.getWrapperType(), r.getTableName(), r.getWarning(), r.getColumns(),
                r.getColumnComments(), r.getTableComment(), r.getPreviewModel(), r.getParamHints(),
                r.getCandidateTables(), r.getBatchModel(), r.getXmlModel(), r.getInitialParamValues(),
                r.getPerfWarnings(), r.getFillClauses(), r.getPreviousSql(), r.getPagesColumns(),
                r.getNamedParamHints(), r.getStackDigest(), r.getThreadName(), r.isHasDollarRawSegments(),
                r.getCountSql(), r.getPaginationLimitLine(), r.getPlaceholderParamNames(),
                r.getDuplicateParamNames(), r.getDiagramStatement(), r.getHistoryFingerprintSeed(),
                r.getPaginationParamSources(), renderedDialect, dialectDecision,
                r.getHistoryEntryId(), r.getActualSql());
    }
}
