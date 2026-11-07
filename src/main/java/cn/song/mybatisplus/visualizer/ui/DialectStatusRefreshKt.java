// T-SyncK2JMaster496 自 master ui/DialectStatusRefresh.kt 移植（applyDialectStatusSegment 最小形态）；
// 177 轮（A2 波次）增量补全：refreshDialectStatusRow / adoptDialectForNeutralCarrier（UI-004）等
// 面板侧刷新链成员按 master 终态落位（applyDialectStatusSegment 维持 k2j 最小形态——Java 端
// meta 行由 SqlPreviewPanelResultViewKt 的 applyMetaTruncation 统一重建，见方法注）。
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate;
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe;
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-DialectStatusLiveRefresh 方案 A/B：方言状态行随执行/绑定即时刷新（{@code DialectStatusRefresh.kt} 顶层扩展函数承载）。
 *
 * <p>背景：方言决策在预览生成时算一次并固化进 {@code PreviewResult.dialectDecision}，执行 SQL 虽已通过
 * 提交闸写入模块数据源学习（DataSourceSelectorConsole 的 {@code remember}），但面板没有重算入口——
 * 头部停留在「候选方言不一致」，须关开预览才更新（真机实锤）。</p>
 *
 * <p>本类只做三件事：</p>
 * <ol>
 *   <li>{@code DialectStatusRefreshPolicy.pickStronger}（独立承载类）：纯函数，决定新决策能否替换旧决策
 *       （仅升级、手动/冲突不被覆盖）；</li>
 *   <li>{@link #refreshDialectStatusRow}：池线程重算决策（含绑定源信号），EDT 轻量重组方言段；</li>
 *   <li>UI-004：{@link #adoptDialectForNeutralCarrier} 方言无关载体证据对齐 + 底部方言提示行重判。</li>
 * </ol>
 *
 * <p>不新增监听器（循 R30 零监听器口径），挂点复用 {@code embedConsole} 既有单点钩子。</p>
 */
public final class DialectStatusRefreshKt {

    private DialectStatusRefreshKt() {
    }

    /**
     * 按当前 {@link SqlPreviewPanel#currentDialectDecision} 重组 meta 行方言段。
     *
     * <p>k2j 最小形态：Java 端 meta 行由 {@code SqlPreviewPanelResultViewKt} 的 applyMetaTruncation
     * 统一重建，本方法只把方言段并入 metaLineHead 头（决策 null 时不动——回落纯 meta 主体）。</p>
     */
    public static void applyDialectStatusSegment(@NotNull SqlPreviewPanel p) {
        DialectDecisionEngine.Decision decision = p.currentDialectDecision;
        if (decision == null) {
            return;
        }
        DialectPreviewStatusText.Text status = DialectPreviewStatusText.describe(decision);
        p.metaLineHead = status.visible() + "   " + p.metaLineHead;
        p.metaLabel.setToolTipText(p.metaLineHead);
    }

    /**
     * 面板已绑定/上次使用数据源的方言（方案 B 信号）。只在池线程调用：活控制台取名走反射 +
     * ReadAction，记忆回退走 URL 探测（带 TTL 缓存），均不可在 EDT 直调。
     */
    @Nullable
    public static String boundDataSourceDialectForDecision(@NotNull SqlPreviewPanel p) {
        Object consoleDataSource;
        try {
            consoleDataSource = DataSourceSelector.boundConsoleDataSource();
        } catch (Throwable ignored) {
            consoleDataSource = null;
        }
        if (consoleDataSource != null) {
            String liveDialect;
            try {
                liveDialect = ExecutionDialectGate.fromDataSource(consoleDataSource).dialectName();
            } catch (Throwable ignored) {
                liveDialect = null;
            }
            if (liveDialect != null && !liveDialect.isBlank()) return liveDialect;
        }
        String remembered = SqlPreviewPanelParamPanelKt.rememberedDataSourceNameForDisplay(p);
        if (remembered == null) return null;
        String url;
        try {
            url = DataSourceUrlProbe.urlByName(p.project, remembered);
        } catch (Throwable ignored) {
            url = null;
        }
        return UrlSubprotocolDialects.dialectName(url);
    }

    /** Kotlin 默认参等价重载：{@code includeSourceFile} 缺省 false（控制台挂载钩子既有形态）。 */
    public static void refreshDialectStatusRow(@NotNull SqlPreviewPanel p) {
        refreshDialectStatusRow(p, false);
    }

    /**
     * 执行/绑定后的方言状态刷新：池线程重算 → EDT 仅升级替换 → 只重组方言段。
     *
     * <p>幂等且防抖：决策未变（或更弱）时不触碰 UI；面板已 dispose 时丢弃晚到结果。</p>
     *
     * @param includeSourceFile UI-004：把预览来源文件一并交给决策链
     *        （{@code IdeaSourceDialectProbe} 才能读到 IDEA 的 SQL 方言映射）。
     *        只在用户点「刷新」时置 true——控制台挂载钩子保持既有「绑定源优先」的形态，
     *        避免同一次预览的头部被两套证据来回翻转。
     */
    public static void refreshDialectStatusRow(@NotNull SqlPreviewPanel p, boolean includeSourceFile) {
        if (p.current == null) return;
        String moduleKey = p.sourceModuleKey;
        // 跨层依赖（A4 波次 SqlPreviewPanel.kt 增量）：sourceVirtualFile 字段按 master 签名照译
        com.intellij.openapi.vfs.VirtualFile sourceFile = includeSourceFile ? p.sourceVirtualFile : null;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String boundDialect = boundDataSourceDialectForDecision(p);
            DialectDecisionEngine.Decision fresh;
            try {
                // UI-004：源文件 → IDEA 方言映射是平台服务读取，后台线程包读锁（262 无读锁读平台
                // 结构会硬抛，与 UI-002 同族）。
                final com.intellij.openapi.vfs.VirtualFile sf = sourceFile;
                fresh = ReadAction.compute(
                        (com.intellij.openapi.util.ThrowableComputable<DialectDecisionEngine.Decision, Throwable>) () ->
                                DialectRegistry.INSTANCE.decision(p.project, sf, null, moduleKey, boundDialect));
            } catch (Throwable e) {
                Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[T-DialectStatusLiveRefresh] re-decide FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                fresh = null;
            }
            if (fresh == null) return;
            final DialectDecisionEngine.Decision fFresh = fresh;
            final String fBoundDialect = boundDialect;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (Disposer.isDisposed(p)) return;
                DialectDecisionEngine.Decision chosen = DialectStatusRefreshPolicy.pickStronger(p.currentDialectDecision, fFresh);
                if (chosen != null) {
                    p.currentDialectDecision = chosen;
                    applyDialectStatusSegment(p);
                    // T-DialectGateExit P1：绑定源给出更强方言时，把 SQL 也重渲染到该方言
                    rerenderIfBoundDialectDiffers(p, chosen);
                    // UI-004：XML/纯文本载体无法无损重渲染（rerenderForExecutionTarget 返回 false），
                    // 但它们的文本本来就与方言无关——把记录的方言证据对齐到最新决策，底部闸门
                    // 才与顶部/实际渲染同源（否则顶部 MySQL、底部仍报 PostgreSQL）。
                    adoptDialectForNeutralCarrier(p, chosen);
                }
                // UI-004：已显示的方言提示按同一份证据重判——MATCH 即清掉（红条消失），
                // 仍不一致则报真实的两边方言。决策未变时也要重判：底部可能停在旧快照上。
                refreshDialectGateStatusRow(p, fBoundDialect);
            }, ModalityState.any());
        });
    }

    /**
     * T-DialectGateExit P1：绑定源给出更强方言且与当前渲染不同 → 按目标方言重渲染。
     *
     * <p>不这么做的话「状态行说 A、SQL 文本是 B、执行目标是 C」可以并存（UI-040 现场）：
     * 用户绑了 MySQL 源，但 SQL 仍按模块学到的 PostgreSQL 渲染，执行/回滚全被方言闸挡住。</p>
     *
     * <p>护栏：只在用户没改过编辑器文本时做（{@code lastRenderedSql == 当前文本}）——重渲染整篇重写，
     * 不能吞掉用户的编辑；模型不支持无损重渲染时 {@code rerenderForExecutionTarget} 自己返回 false。</p>
     */
    private static void rerenderIfBoundDialectDiffers(@NotNull SqlPreviewPanel p,
                                                      @NotNull DialectDecisionEngine.Decision decision) {
        String targetName = decision.dialectName();
        if (targetName == null) return;
        PreviewResult current = p.current;
        if (current == null || current.getEffectiveDialect() == null) return;
        String rendered = current.getEffectiveDialect().getName();
        String text = SqlPreviewPanelExecutionKt.currentEditorSql(p);
        boolean untouched = text != null && text.equals(p.lastRenderedSql);
        if (!shouldRerenderForBoundDialect(decision.source(), targetName, rendered, untouched)) return;
        SqlPreviewPanelExecutionKt.rerenderForExecutionTarget(p, targetName);
    }

    /**
     * UI-004：方言无关载体（XML 原文 / 纯文本）把记录的渲染方言对齐到最新决策。
     *
     * <p>为什么可以这么做而不是「冒充重渲染」：这类载体的 SQL 是用户书写的原文，插件从不按方言改写
     * （唯一的方言敏感产物是分页 LIMIT 行，{@link DialectGateRenderDialect#isDialectNeutralCarrier} 已排除）。
     * {@code effectiveDialect} 对它们只是「生成时算出的方言证据」快照；不更新就会与顶部状态行
     * （{@code currentDialectDecision}，随绑定/刷新重算）分裂成「顶部 MySQL、底部 PostgreSQL」。</p>
     *
     * <p>护栏：</p>
     * <ul>
     *   <li>只处理方言无关载体（模型/批量/分页载体由 {@code rerenderForExecutionTarget} 负责，不在此处动手）；</li>
     *   <li>编辑器文本必须仍是插件最后一次渲染的那份（用户改过就不再代表插件的渲染事实）；</li>
     *   <li>目标方言与记录一致时不动。</li>
     * </ul>
     *
     * @return true = 证据已对齐（未改一个字符，只更新方言事实）
     */
    public static boolean adoptDialectForNeutralCarrier(@NotNull SqlPreviewPanel p,
                                                        @NotNull DialectDecisionEngine.Decision decision) {
        PreviewResult result = p.current;
        if (result == null) return false;
        String target = decision.dialectName();
        if (target == null || target.isBlank()) return false;
        if (!DialectGateRenderDialect.isDialectNeutralCarrier(
                result.getPreviewModel() != null,
                result.getBatchModel() != null,
                result.getPaginationLimitLine() != null)) {
            return false;
        }
        String text = SqlPreviewPanelExecutionKt.currentEditorSql(p);
        if (text == null) return false;
        if (!text.equals(p.lastRenderedSql)) return false;
        String effectiveName = result.getEffectiveDialect() != null ? result.getEffectiveDialect().getName() : null;
        if (target.equalsIgnoreCase(effectiveName)) return false;
        // copy(renderedDialect=, dialectDecision=) 手工重建（k2j PreviewResult 无带方言位的 copy 重载，
        // 走 35 参全构造器逐字段转抄，仅 renderedDialect/dialectDecision 换新值）。
        PreviewResult updated = new PreviewResult(
                result.getSql(), result.getCompactSql(), result.getUnresolvedParams(), result.getParamTable(),
                result.getWrapperType(), result.getTableName(), result.getWarning(), result.getColumns(),
                result.getColumnComments(), result.getTableComment(), result.getPreviewModel(),
                result.getParamHints(), result.getCandidateTables(), result.getBatchModel(),
                result.getXmlModel(), result.getInitialParamValues(), result.getPerfWarnings(),
                result.getFillClauses(), result.getPreviousSql(), result.getPagesColumns(),
                result.getNamedParamHints(), result.getStackDigest(), result.getThreadName(),
                result.isHasDollarRawSegments(), result.getCountSql(), result.getPaginationLimitLine(),
                result.getPlaceholderParamNames(), result.getDuplicateParamNames(),
                result.getDiagramStatement(), result.getHistoryFingerprintSeed(),
                result.getPaginationParamSources(), DialectRegistry.INSTANCE.byName(target),
                decision, result.getHistoryEntryId(), result.getActualSql());
        // 与 rerenderForExecutionTarget 同款：同一预览换方言不是新参数会话，保留填充模拟的归属。
        if (p.fieldFillPreview == result) p.fieldFillPreview = updated;
        p.current = updated;
        return true;
    }

    /**
     * UI-004：底部方言提示行随刷新重判。
     *
     * <p>只替换「当前信息行仍是方言提示」的情形（{@code SqlPreviewPanel.lastDialectGateMessage} 在场且结果区
     * 处于纯信息态）——执行结果与真实执行错误一律不动。MATCH 时清空提示（红条消失），
     * 仍不一致则按<b>真实的两边方言</b>重新渲染。</p>
     *
     * <p>跨层依赖（A4 波次）：{@code lastDialectGateMessage} 面板字段与 {@code SqlResultPanel.isInfoOnlyState()}
     * 按 master 签名照译，待 A4 波次落位后编译闭合。</p>
     *
     * @param boundDialectName 已绑定控制台/数据源的方言（池线程取好传入；null=未知，不动提示）
     */
    private static void refreshDialectGateStatusRow(@NotNull SqlPreviewPanel p, @Nullable String boundDialectName) {
        if (p.lastDialectGateMessage == null) return;
        if (boundDialectName == null || boundDialectName.isBlank()) return;
        if (!p.resultPanel.isInfoOnlyState()) return;
        String sql = SqlPreviewPanelExecutionKt.currentEditorSql(p);
        if (sql == null) return;
        ExecutionDialectGate.Intent intent = SqlTypeClassifier.isQuery(sql)
                ? ExecutionDialectGate.Intent.QUERY
                : ExecutionDialectGate.Intent.DML;
        ExecutionDialectGate.Verdict verdict =
                ExecutionDialectGate.evaluate(SqlPreviewPanelExecutionKt.executionDialectGateRequest(p, intent),
                        boundDialectName);
        if (verdict.status() == ExecutionDialectGate.Status.MATCH) {
            p.resultPanel.showEmpty();
            p.lastDialectGateMessage = null;
        } else {
            SqlPreviewPanelExecutionKt.onExecutionDialectGate(p, verdict);
        }
    }

    /**
     * T-DialectGateExit P1 判据（纯函数，便于单测）：
     * 绑定源给出方言、与当前渲染不同、且用户没改过编辑器文本时，才按目标方言重渲染。
     *
     * <ul>
     *   <li>只有 {@link DialectDecisionEngine.Source#BOUND_DATASOURCE} 生效：模块学习/项目共识都不是「执行目标」，
     *       不该因为一条弱证据就改写 SQL；</li>
     *   <li>文本被编辑过时不重渲染（重渲染整篇重写，会吞掉用户改动）。</li>
     * </ul>
     */
    public static boolean shouldRerenderForBoundDialect(
            @Nullable DialectDecisionEngine.Source source,
            @Nullable String targetDialectName,
            @Nullable String renderedDialectName,
            boolean textUntouched) {
        if (source != DialectDecisionEngine.Source.BOUND_DATASOURCE) return false;
        if (targetDialectName == null || targetDialectName.isBlank()
                || renderedDialectName == null || renderedDialectName.isBlank()) return false;
        if (targetDialectName.equalsIgnoreCase(renderedDialectName)) return false;
        return textUntouched;
    }
}
