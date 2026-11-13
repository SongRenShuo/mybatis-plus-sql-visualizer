// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/IbatisXmlPreviewService.kt 移植
// （Kotlin object 翻译：成员全静态——纯函数服务无实例状态，调用方以对象成员形态调用，静态承载可互操作。）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParseException;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisRenderResult;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSelectKey;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSqlMap;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatement;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModel;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapParser;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapSqlRenderer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * iBatis 2.x 语句预览服务（T-Ibatis2Support 券 A 接管面）：{@code <sqlMap>} 文本 → 语句 → {@link PreviewResult}。
 *
 * <p>与 {@code <pages>} 链同款<b>纯文本入口</b>（fileText 输入，无 PSI 依赖）：gutter / Action 负责取文件文本
 * 与锚定标签信息，弹窗与面板完全复用 {@link SqlPreviewService}{@code .showResult} 链路。</p>
 *
 * <h2>走的是哪条链（与 §券 0 的关系）</h2>
 *
 * <p>iBatis 2.x <b>无 HQL 概念</b>，语言判定对它整体是无效计算。本服务<b>完全绕开</b>
 * {@code PagesSourceLanguageDetector} / {@code PagesEntityProbeResolver}——这同时消掉「XML 内表名反查实体」
 * 那条慢路径（计划册 §券 0 的 CD-3 修法在 pages 链落地后，本链连判定入口都不进）。</p>
 *
 * <h2>三条对齐计划册 §1.10 的口径</h2>
 *
 * <ol>
 * <li><b>{@code #} 不转 {@code ?} 的分支只在渲染器里</b>（声明 {@code parameterMap} 且非动态语句）——本服务只做
 *     {@code ?} 位点数与参数名序的对齐核对，不自行改写 SQL；</li>
 * <li><b>{@code $x$} 直替接既有注入闸门</b>：{@code hasDollarRawSegments}（{@code PreviewResult.hasDollarRawSegments}）
 *     由渲染器事实决定，不新开无闸门路径；iBatis {@code ${prop}}（引擎的全局属性 token，本链不做替换）
 *     同样计入——它在 SQL 文本里就是 {@code ${}} 原文拼接段；</li>
 * <li><b>渲染告警落到面板可见区</b>：复用既有 {@code warning} 载体（面板 {@code currentWarning} → 降级横幅 +
 *     错误复制按钮），不新造 UI 形态。告警取自渲染器的降级清单（未知标签 / 声明 parameterMap
 *     导致 {@code #} 未转 {@code ?} / {@code ${...}} 未支持 / {@code #} 格式错 等）。UI-001 起告警分档：parameterMap 的
 *     「引擎行为」说明与 iterate 未供值的「待填值」提示标 {@code informationalWarning}，面板用 {@code ℹ}
 *     信息级前缀呈现，不再统一冒充「⚠ 预览未完整还原」。</li>
 * </ol>
 *
 * <h2>参数名口径（拍板 D-7）</h2>
 *
 * <p>{@code IbatisRenderResult.paramNames} 按 {@code ?} 出现顺序给出参数名（iBatis {@code #name#} 语义）。三种不齐形态：</p>
 * <ul>
 * <li><b>数量相符</b> → 直接用参数名（面板列出 {@code name} 而不是 {@code param1}）；</li>
 * <li><b>数量不符</b>（如 {@code <statement>} 手写 {@code ?}、{@code #} 保留未转、iterate 内重解析）→ 按 {@code param1..N} 兜底
 *     + 告警说明；</li>
 * <li><b>参数名非空但无 {@code ?}</b>（声明 parameterMap 且体内 {@code #} 未转 {@code ?}）→ 参数行留空 + 告警说明
 *     「参数名不可知」，与通用兜底通道同口径（不显示假的空参数表）。</li>
 * </ul>
 *
 * <h2>T-IbatisFix2（2026-11-10 用户 2026.3 验收反馈）</h2>
 *
 * <ol>
 * <li><b>{@code <selectKey>} 子语句展示</b>：渲染器 {@code renderSelectKey}（保真对照 4/4）此前服务层从未调用，
 *     {@code insertUser} 的 {@code SELECT SEQ_USER_ID.NEXTVAL FROM DUAL} 完全不可见。现按引擎语义组装——
 *     {@code type=pre}（含未写 type 时按位置推断的 pre）在主语句之前、{@code post} 在后，块间用注释分隔
 *     （{@code -- [selectKey type=pre → id]} / {@code -- [语句id]}）。条件行/参数行仍以主语句为准：
 *     selectKey 的 {@code keyProperty} 回写目标不是 {@code ?} 参数，不进参数面板（keyProperty 只进块注释）；
 *     selectKey 自身若含 {@code #x#}，其参数名按 pre 前 / post 后拼进参数名序，保证 {@code ?} 位点对齐。</li>
 * <li><b>重渲染失败可见</b>（用户机器独有、本地探针 probe1 不可复现）：推断 263 平台上 rerender
 *     回调内部抛异常被面板吞掉、静默保留基线。整条回调 catch（{@link FatalErrors} 豁免）：失败时
 *     {@code idea.log} 记带堆栈的 warn（取证）＋返回可见失败结果（sql 保留无参基线降级文本、
 *     warning 失败档明确写「重渲染失败：<原因>」），禁止吞掉后返回旧结果。</li>
 * <li><b>告警分档过滤</b>：信息级说明（「（引擎行为）」等）不再混入 ⚠ 失败档合并行——混合时
 *     ⚠ 行只保留真失败（此前信息级说明与失败说明拼同一行、ℹ 行又重复同内容）；纯信息级整行走 ℹ。</li>
 * </ol>
 */
public final class IbatisXmlPreviewService {

    private IbatisXmlPreviewService() {
    }

    private static final Logger LOG = Logger.getInstance(IbatisXmlPreviewService.class);

    /** 告警清单压成一行（面板 meta 单行渲染；超长截断，完整信息在 SQL 注释与日志里）。 */
    private static final int MAX_WARNING_CHARS = 300;

    /** 渲染器降级告警的固定措辞（渲染器 catch 点 {@code 渲染降级（按原文保留 # 直替…）}）。 */
    private static final String DEGRADE_MARK = "渲染降级";

    /**
     * 预览一份 {@code <sqlMap>} 文本里的语句（全参形态；Kotlin 默认参量由重载补齐）。
     *
     * @param statementId      锚定标签的 {@code id} 属性（首选定位键；null 时按 statementTagName + statementOrdinal 定位）
     * @param statementTagName 锚定标签名（{@code select}/{@code insert}/…；null 时退化为「取文件首条语句」）
     * @param statementOrdinal 同名标签中的序号（0 起；仅无 id 的非法文件用得上）
     * @param project          T-IbatisParamClassQuote：解析 {@code parameterClass} 属性类型用（null = 纯文本测试链，维持现状）
     * @param parameterClass   T-IbatisParamClassQuote：语句的 {@code parameterClass} 原值；null 时回落到 XML 属性里的同名项
     * @param moduleName       T-IbatisParamClassQuote（L34）：语句所在模块名——短名重名时按「就近」收窄候选；
     *                         null = 不做模块过滤（单模块项目与测试环境的行为与升级前一致）
     */
    @NotNull
    public static PreviewResult preview(@NotNull String fileText,
                                        @Nullable String statementId,
                                        @Nullable String statementTagName,
                                        int statementOrdinal,
                                        @Nullable Project project,
                                        @Nullable String parameterClass,
                                        @Nullable String moduleName) {
        IbatisSqlMap model;
        try {
            model = new SqlMapParser().parse(fileText);
        } catch (IbatisParseException e) {
            return emptyResult(e.getMessage() != null
                ? e.getMessage()
                : MpSqlVisualizerBundle.message("ibatis.preview.notSqlMap.warning"));
        }
        IbatisStatement statement = locateStatement(model, statementId, statementTagName, statementOrdinal);
        if (statement == null) {
            return emptyResult(MpSqlVisualizerBundle.message("ibatis.preview.statementNotFound.warning"));
        }
        IbatisRenderResult rendered;
        try {
            rendered = new SqlMapSqlRenderer().render(statement);
        } catch (RuntimeException e) {
            // 渲染器本身把语句级问题降级为 warning（不抛穿）；能到这里是意外形态，仍不打断预览
            return emptyResult(e.getMessage() != null
                ? e.getMessage()
                : MpSqlVisualizerBundle.message("ibatis.preview.renderFailed.warning"));
        }
        // T-Ibatis2Gap 券 A：把「无参基线」从唯一输出变成初始态——行清单 + 重渲染回调随结果进面板。
        // 回调闭包复用同一 statement（纯数据，无 PSI/引擎引用），面板每次交互重新装配 Map 并渲染。
        IbatisParamModel paramModel = IbatisParamModel.IbatisParamModelBuilder.build(statement);
        List<kotlin.Pair<IbatisSelectKey, IbatisRenderResult>> selectKeyRenders = renderSelectKeys(statement, null);
        // T-IbatisParamClassQuote：裸 `#name#` 的类型来源——parameterClass 的属性类型。
        // 只按首屏实际出现的属性名解析一次，重渲染复用（新分支带出的属性再查不到就维持启发式）。
        List<String> propertyNames = new ArrayList<>(rendered.paramNames());
        for (kotlin.Pair<IbatisSelectKey, IbatisRenderResult> pair : selectKeyRenders) {
            propertyNames.addAll(pair.getSecond().paramNames());
        }
        Map<String, ParamHint> propertyHints = IbatisParameterClassResolver.resolve(
            project,
            parameterClass != null ? parameterClass : statement.attributes().get("parameterClass"),
            propertyNames,
            moduleName
        );
        PreviewResult baseline = toPreviewResult(
            rendered,
            !paramModel.dollarRows().isEmpty(),
            // 无参基线：iterate 行全部未供值（待填值态）
            !paramModel.iterateRows().isEmpty(),
            selectKeyRenders,
            propertyHints
        );
        // T-IbatisFix2 修复三：闭包捕获基线结果——重渲染失败时回落到它（sql 保留降级文本），
        // 不再静默吞掉异常
        return withIbatisModel(
            baseline,
            paramModel,
            input -> rerender(statement, paramModel, baseline, input, propertyHints)
        );
    }

    /** Kotlin 默认参数等价重载：statementTagName/statementOrdinal/project/parameterClass/moduleName 缺省。 */
    @NotNull
    public static PreviewResult preview(@NotNull String fileText, @Nullable String statementId) {
        return preview(fileText, statementId, null, 0, null, null, null);
    }

    /**
     * 面板状态 → Map 装配 → 既有渲染器（空装配回退 null = 无参基线，与现状一致）。
     *
     * <p>T-IbatisFix2 修复三：整条回调 catch（{@link FatalErrors#isFatal} 豁免直传）——用户 2026.3 上
     * 「全勾全填后 SQL 保持在无参基线」本地不可复现（探针 probe1 重渲染正常），推断平台上回调
     * 内部抛异常被吞。失败不再静默：{@code idea.log} 记带堆栈 warn + 返回可见失败结果（sql 保留
     * 基线降级文本、warning 失败档「重渲染失败：<原因>」）。同时是取证手段：真机再触发时面板
     * 会直接给出异常内容。</p>
     */
    @NotNull
    private static PreviewResult rerender(@NotNull IbatisStatement statement,
                                          @NotNull IbatisParamModel paramModel,
                                          @NotNull PreviewResult baseline,
                                          @NotNull PreviewRerenderInput input,
                                          @NotNull Map<String, ParamHint> propertyHints) {
        try {
            Object params = IbatisParamModel.IbatisParamModelBuilder.assembleParams(paramModel, input);
            IbatisRenderResult rendered = new SqlMapSqlRenderer().render(statement, params);
            boolean iteratePending = false;
            for (IbatisParamModel.IbatisIterateRow row : paramModel.iterateRows()) {
                List<String> values = input.collections().get(row.property());
                if (values == null || values.isEmpty()) {
                    iteratePending = true;
                    break;
                }
            }
            return toPreviewResult(
                rendered,
                !paramModel.dollarRows().isEmpty(),
                iteratePending,
                renderSelectKeys(statement, params),
                propertyHints
            );
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) {
                throw e;
            }
            LOG.warn("iBatis 预览重渲染失败（statement=" + statement.id() + "）：" + e.getMessage(), e);
            return rerenderFailedResult(baseline, e);
        }
    }

    /**
     * T-IbatisFix2 修复三的失败档产物（internal＝测试缝）：sql/compactSql/参数模型保留<b>基线降级
     * 文本</b>（不是空结果），warning 换成失败档「重渲染失败：<原因>」，横幅以 ⚠ 呈现——禁止吞掉
     * 后静默返回旧结果。{@code rerender} 闭包随 copy 保留，用户修正参数后可立即重试。
     */
    @NotNull
    public static PreviewResult rerenderFailedResult(@NotNull PreviewResult baseline, @NotNull Throwable error) {
        String reason = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        // T-SyncK2JMaster178-fix2：master baseline.copy(warning = "重渲染失败：<原因>", informationalWarning = false)
        // ——k2j 此前漏译 warning 位（恒 null），失败档静默无告警，与「失败可见」口径相悖。
        return withIbatisOverride(
            baseline,
            baseline.getSql(), baseline.getCompactSql(), baseline.getUnresolvedParams(), baseline.getParamTable(),
            baseline.getWrapperType(), baseline.getTableName(),
            MpSqlVisualizerBundle.message("ibatis.preview.rerenderFailed.warning", reason),
            baseline.getParamHints(),
            baseline.isHasDollarRawSegments(), false,
            baseline.getIbatisParamModel(), baseline.getRerender()
        );
    }

    // ------------------------------------------------------------------
    // T-IbatisFix2 修复一：<selectKey> 子语句展示
    // ------------------------------------------------------------------

    /** 渲染 {@code <selectKey>} 子语句（渲染器 {@code renderSelectKey} 已过保真对照，服务层此前漏调用）。 */
    @NotNull
    private static List<kotlin.Pair<IbatisSelectKey, IbatisRenderResult>> renderSelectKeys(
        @NotNull IbatisStatement statement, @Nullable Object params) {
        if (statement.selectKeys().isEmpty()) {
            return List.of();
        }
        SqlMapSqlRenderer renderer = new SqlMapSqlRenderer();
        List<kotlin.Pair<IbatisSelectKey, IbatisRenderResult>> result = new ArrayList<>();
        for (IbatisSelectKey selectKey : statement.selectKeys()) {
            IbatisRenderResult rendered;
            try {
                rendered = renderer.renderSelectKey(selectKey, params);
            } catch (Throwable error) {
                if (FatalErrors.isFatal(error)) {
                    throw error;
                }
                // selectKey 渲染失败不拖垮主语句：空块 + 失败档告警（可见失败，不静默丢块）
                String reason = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                rendered = new IbatisRenderResult(
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(MpSqlVisualizerBundle.message(
                        "ibatis.preview.selectKeyRenderFailed.warning", selectKey.id(), reason)),
                    0,
                    List.of(),
                    List.of(selectKey.id()),
                    List.of(),
                    true,
                    List.of(),
                    List.of()
                );
            }
            result.add(new kotlin.Pair<>(selectKey, rendered));
        }
        return result;
    }

    /**
     * 多语句组装：<b>主语句恒在最前</b>，selectKey 块紧随其后，块间空行 + 注释分隔。
     *
     * <h2>为什么主语句必须在最前（安全面，不许按引擎时序排）</h2>
     *
     * <p>预览产出的 {@code sql} 会流进执行链，而执行链<b>只认首条语句</b>：</p>
     * <ul>
     * <li>{@code execute/SqlTypeClassifier.getFirstKeyword} = {@code splitStatements(sql).firstOrNull()}，
     *     类型判定（QUERY/DML/DDL）据此决定是否放行只读路径；</li>
     * <li>{@code execute/ExecutionScriptScope.shouldExecWholeScript} 在语句数 &gt; 1 时返回 false，
     *     「执行 SQL」退化为「当前游标所在语句」。</li>
     * </ul>
     *
     * <p>若按引擎时序把 {@code type=pre} 的 selectKey 拼在最前，{@code insertUser} 预览的首条语句就是
     * {@code SELECT SEQ_USER_ID.NEXTVAL FROM DUAL} ⇒ 类型判成 QUERY（只读）⇒ <b>写操作被写安全闸
     * 当只读放过</b>，且用户点「执行 SQL」先跑的是序列查询而不是 INSERT。故物理顺序固定为
     * 主语句 → selectKey 块，真实时序只由块注释标明（{@code type=pre} + 「在主语句之前执行」）。</p>
     *
     * <h2>形态说明</h2>
     * <p>本仓 MyBatis 链的多语句展示走独立字段（{@code countSql}/{@code fillClauses} 由面板并列渲染），
     * <b>没有文本级多语句先例可抄</b>——iBatis 链不动面板，故用注释标注 + 分号的纯文本形态。</p>
     */
    @Nullable
    private static String assembleMultiStatementSql(
        @NotNull IbatisRenderResult main,
        @NotNull List<kotlin.Pair<IbatisSelectKey, IbatisRenderResult>> selectKeyRenders) {
        if (selectKeyRenders.isEmpty()) {
            return null;
        }
        List<String> blocks = new ArrayList<>();
        // 主语句恒在最前，且不加块注释——首字符必须是语句关键字本身：执行链按首条语句判类型
        // （见类 KDoc），且用户复制出去的第一段就该是可直接执行的主语句，不能被 `--` 注释行打头。
        appendBlock(blocks, "", main);
        for (kotlin.Pair<IbatisSelectKey, IbatisRenderResult> pair : selectKeyRenders) {
            appendBlock(blocks, selectKeyComment(pair.getFirst()), pair.getSecond());
        }
        return String.join("\n\n", blocks);
    }

    /** Kotlin 局部函数 {@code appendBlock(comment, result)} 的 Java 承载（blocks 为累加载体）。 */
    private static void appendBlock(@NotNull List<String> blocks, @NotNull String comment, @NotNull IbatisRenderResult result) {
        String sql = result.sql().trim();
        if (sql.isEmpty()) {
            blocks.add(comment);
        } else if (comment.isEmpty()) {
            blocks.add(sql + ";");
        } else {
            blocks.add(comment + "\n" + sql + ";");
        }
    }

    /**
     * selectKey 块注释：{@code type} 按 {@link IbatisSelectKey#runAfterSql()}（引擎的显式 type + 位置推断合并口径），
     * 并<b>用文字标明真实执行时机</b>——物理上它排在主语句之后（执行链安全面要求），时序不能靠位置读。
     */
    @NotNull
    private static String selectKeyComment(@NotNull IbatisSelectKey selectKey) {
        String type = selectKey.runAfterSql() ? "post" : "pre";
        String timing = MpSqlVisualizerBundle.message(
            selectKey.runAfterSql()
                ? "ibatis.preview.selectKey.timing.post"
                : "ibatis.preview.selectKey.timing.pre");
        String keyProperty = selectKey.attributes().get("keyProperty");
        if (keyProperty == null || keyProperty.isBlank()) {
            return MpSqlVisualizerBundle.message("ibatis.preview.selectKey.blockCommentNoKey", type, timing);
        }
        return MpSqlVisualizerBundle.message("ibatis.preview.selectKey.blockComment", type, keyProperty, timing);
    }

    /** 多语句的紧凑形态（与 {@link #assembleMultiStatementSql} 同序：主语句在前，分号收尾）。 */
    @NotNull
    private static String assembleMultiStatementCompact(
        @NotNull IbatisRenderResult main,
        @NotNull List<kotlin.Pair<IbatisSelectKey, IbatisRenderResult>> selectKeyRenders) {
        List<String> parts = new ArrayList<>();
        appendCompactPart(parts, main);
        for (kotlin.Pair<IbatisSelectKey, IbatisRenderResult> pair : selectKeyRenders) {
            appendCompactPart(parts, pair.getSecond());
        }
        return String.join(" ", parts);
    }

    /** Kotlin 局部函数 {@code appendPart(result)} 的 Java 承载。 */
    private static void appendCompactPart(@NotNull List<String> parts, @NotNull IbatisRenderResult result) {
        String compact = result.compactSql().trim();
        if (!compact.isEmpty()) {
            parts.add(compact + ";");
        }
    }

    // ------------------------------------------------------------------
    // 语句定位
    // ------------------------------------------------------------------

    @Nullable
    private static IbatisStatement locateStatement(@NotNull IbatisSqlMap model,
                                                   @Nullable String statementId,
                                                   @Nullable String statementTagName,
                                                   int statementOrdinal) {
        if (statementId != null && !statementId.isBlank()) {
            IbatisStatement byId = model.statementById(statementId);
            if (byId != null) {
                return byId;
            }
        }
        List<IbatisStatement> sameKind = new ArrayList<>();
        for (IbatisStatement candidate : model.statements()) {
            if (statementTagName == null || statementTagName.equals(candidate.kindTagName())) {
                sameKind.add(candidate);
            }
        }
        if (statementOrdinal >= 0 && statementOrdinal < sameKind.size()) {
            return sameKind.get(statementOrdinal);
        }
        return sameKind.isEmpty() ? null : sameKind.get(0);
    }

    // ------------------------------------------------------------------
    // 产物转换（IbatisRenderResult → PreviewResult）
    // ------------------------------------------------------------------

    @NotNull
    private static PreviewResult toPreviewResult(
        @NotNull IbatisRenderResult rendered,
        boolean statementHasRawSubstitutionSites,
        boolean iteratePending,
        @NotNull List<kotlin.Pair<IbatisSelectKey, IbatisRenderResult>> selectKeyRenders,
        @NotNull Map<String, ParamHint> propertyHints) {
        // T-IbatisFix2 修复一：selectKey 块参与 sql/compactSql 组装与参数名序（pre 前 / post 后）
        String assembledSql = assembleMultiStatementSql(rendered, selectKeyRenders);
        String sql = assembledSql != null ? assembledSql : rendered.sql();
        String compactSql = assembledSql != null
            ? assembleMultiStatementCompact(rendered, selectKeyRenders)
            : rendered.compactSql();
        int placeholders = PlaceholderPositionsKt.placeholderPositions(sql).size();
        // 参数名序 = SQL 文本里 `?` 的出现序（主语句块在前，selectKey 块按声明序在其后）——
        // 与拼装顺序严格同源，否则位点错位会让参数名贴到别的占位符上。
        List<String> names = new ArrayList<>(rendered.paramNames());
        for (kotlin.Pair<IbatisSelectKey, IbatisRenderResult> pair : selectKeyRenders) {
            names.addAll(pair.getSecond().paramNames());
        }
        List<String> unresolved;
        if (placeholders == 0) {
            unresolved = List.of();
        } else if (names.size() == placeholders) {
            unresolved = names;
        } else {
            unresolved = new ArrayList<>(placeholders);
            for (int i = 1; i <= placeholders; i++) {
                unresolved.add("param" + i);
            }
        }

        // T-IbatisFix3 缺陷三：`#x:VARCHAR#` 的 jdbcType 必须传到面板的回填提示链——否则
        // `WHERE EMAIL = #email:VARCHAR:0#` 填 11212 会按「纯数字」启发式裸渲染成 `= 11212`。
        // 只在位点与参数名数量相符时装配（数量不符走 paramN 兜底，名字与 jdbcType 已无法对齐，
        // 强行贴类型会把 A 列的类型挂到 B 位点上）；映射不到 jdbcType 的位点用默认 ParamHint
        // （quote=null = 面板启发式，与修复前逐字节一致）。
        List<String> jdbcTypes = new ArrayList<>(rendered.paramJdbcTypes());
        for (kotlin.Pair<IbatisSelectKey, IbatisRenderResult> pair : selectKeyRenders) {
            jdbcTypes.addAll(pair.getSecond().paramJdbcTypes());
        }
        List<ParamHint> paramHints = List.of();
        if (names.size() == placeholders && jdbcTypes.size() == placeholders) {
            // T-IbatisParamClassQuote：jdbcType 仍是首位（既有口径不动）；裸 `#name#`（jdbcType 缺省）
            // 才按 parameterClass 的属性类型补位——iBatis 引擎本身就是拿它反射决定 setString/setInt
            List<ParamHint> hints = new ArrayList<>(placeholders);
            for (int index = 0; index < jdbcTypes.size(); index++) {
                ParamHint hint = IbatisParamModel.IbatisParamTypeHints.hintOf(jdbcTypes.get(index));
                if (hint == null) {
                    hint = propertyHints.get(names.get(index));
                }
                hints.add(hint != null ? hint : new ParamHint());
            }
            paramHints = hints;
        }

        // UI-001：告警分档——「待填值」降级与「引擎行为说明」是信息级说明，不得包装成渲染失败。
        // 判据按来源自证，不猜文案全量：解析器对 parameterMap 自标「（引擎行为）」；iterate 未供值
        // 的降级 = 行清单里该行就在、填值即补齐。
        // T-IbatisFix2 附带小修：分档过滤——信息级说明与真失败分开收集，混合时 ⚠ 失败档合并行
        // 只保留真失败（信息级说明此前会混进 ⚠ 行、ℹ 行又重复同内容）；纯信息级整行走 ℹ。
        List<String> failureWarnings = new ArrayList<>();
        List<String> noteWarnings = new ArrayList<>();
        for (String warning : rendered.warnings()) {
            classify(warning, noteWarnings, failureWarnings, iteratePending);
        }
        for (kotlin.Pair<IbatisSelectKey, IbatisRenderResult> pair : selectKeyRenders) {
            for (String warning : pair.getSecond().warnings()) {
                classify(warning, noteWarnings, failureWarnings, iteratePending);
            }
        }

        if (placeholders > 0 && names.size() != placeholders) {
            if (names.isEmpty()) {
                // 手写 `?` 的 <statement> / `#` 未转 `?` 的配置错配：参数名不可知——引擎行为说明（信息级）
                noteWarnings.add(MpSqlVisualizerBundle.message("ibatis.preview.paramNamesUnavailable.warning"));
            } else {
                // 数量不符是真错配（paramN 兜底），保持失败档
                failureWarnings.add(MpSqlVisualizerBundle.message(
                    "ibatis.preview.paramCountMismatch.warning", names.size(), placeholders));
            }
        }
        if (placeholders == 0 && !names.isEmpty()) {
            // <parameterMap> 声明了参数、体内却是手写 ? 或 # 未转 ? ——参数行留空但要明说原因（信息级）
            noteWarnings.add(MpSqlVisualizerBundle.message("ibatis.preview.paramNamesUnavailable.warning"));
        }
        List<String> warningsForLine = failureWarnings.isEmpty() ? noteWarnings : failureWarnings;

        return withIbatisOverride(
            null,
            sql, compactSql, unresolved, ParameterReplacer.buildParamTable(unresolved),
            null, null, joinWarnings(warningsForLine), paramHints,
            // 安全面（计划册 §六.4「不得新开无闸门路径」）：
            // ① `$x$` 站点存在即置闸——即使无参基线下取值为 null 被引擎静默丢弃（预览 SQL 里看不到
            //    那段文本），运行期它仍是原文直拼面，预览必须走既有 `${}` 确认通道而不是伪装成安全 SQL；
            //    T-Ibatis2Gap 券 A 补强：站点在条件子树内时无参基线连那段都渲染不出来（条件整段
            //    跳过），dollarRawCount=0 会漏闸——行清单里的 `$` 站点（statementHasRawSubstitutionSites）
            //    一并置闸，否则「勾上 isNotEmpty 填值后执行」绕过注入确认；
            // ② 未替换的 `${prop}`（引擎按全局属性替换、本链保留原文）在 SQL 文本里就是 `${}` 原文段；
            // ③ T-IbatisFix2 修复一：selectKey 块内的 `$` 站点同样计闸。
            rendered.dollarRawCount() > 0 || anySelectKeyDollarRaw(selectKeyRenders)
                || statementHasRawSubstitutionSites || sql.contains("${"),
            // UI-001：informationalWarning（ℹ 信息级前缀）
            failureWarnings.isEmpty() && !warningsForLine.isEmpty(),
            null, null
        );
    }

    private static boolean anySelectKeyDollarRaw(@NotNull List<kotlin.Pair<IbatisSelectKey, IbatisRenderResult>> selectKeyRenders) {
        for (kotlin.Pair<IbatisSelectKey, IbatisRenderResult> pair : selectKeyRenders) {
            if (pair.getSecond().dollarRawCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Kotlin 局部函数 {@code classify(warning)} 的 Java 承载。 */
    private static void classify(@NotNull String warning,
                                 @NotNull List<String> noteWarnings,
                                 @NotNull List<String> failureWarnings,
                                 boolean iteratePending) {
        if (isInformationalWarning(warning, iteratePending)) {
            noteWarnings.add(warning);
        } else {
            failureWarnings.add(warning);
        }
    }

    /** UI-001：信息性告警判据（按产出点自带标记，见 {@link #toPreviewResult} 内注释）。 */
    private static boolean isInformationalWarning(@NotNull String warning, boolean iteratePending) {
        if (warning.contains("（引擎行为）")) {
            return true;
        }
        return iteratePending && warning.contains(DEGRADE_MARK) && warning.contains("iterate");
    }

    /** 告警清单压成一行（面板 meta 单行渲染；超长截断，完整信息在 SQL 注释与日志里）。 */
    @Nullable
    private static String joinWarnings(@NotNull List<String> warnings) {
        List<String> cleaned = new ArrayList<>();
        for (String warning : warnings) {
            if (!warning.isBlank() && !cleaned.contains(warning)) {
                cleaned.add(warning);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        String joined = String.join("；", cleaned);
        return joined.length() > MAX_WARNING_CHARS ? joined.substring(0, MAX_WARNING_CHARS) + "…" : joined;
    }

    @NotNull
    private static PreviewResult emptyResult(@NotNull String message) {
        return withIbatisOverride(
            null,
            "", "", List.of(), null,
            null, null, message, List.of(),
            false, false,
            null, null
        );
    }

    /**
     * 「语句未找到」等空产物（{@code IbatisStatementPreview.statementNotFound} 复用；
     * master 侧为 PreviewResult 命名实参直构，Java 侧统一走本服务构造缝）。
     */
    @NotNull
    public static PreviewResult statementNotFoundResult(@NotNull String message) {
        return emptyResult(message);
    }

    // ------------------------------------------------------------------
    // 跨波次对齐缝（单点收口，参照 PagesXmlPreviewService.withHqlMeta 先例）
    // ------------------------------------------------------------------

    /**
     * 本服务唯一的 {@link PreviewResult} 构造点（base == null 时全量新建；base != null 时为
     * master {@code copy} 语义——除列出的量外全量沿用基线，含 {@code rerender} 闭包）。
     *
     * <p>跨层依赖 model/ 波次的 PreviewResult 增量（master {@code 95e107b1..183c98b3d} +43 行：
     * {@code retryWhenIndexReady/informationalWarning} 两中列字段 + {@code ibatisParamModel/rerender}
     * 两尾参字段 + {@link PreviewRerenderInput}）——按 master 尾参位照译，本方法即单点收口，
     * 该波次构造器形态落位后对齐（canonical 39 参 + 新四参为编译预期形态）。</p>
     */
    @NotNull
    private static PreviewResult withIbatisOverride(@Nullable PreviewResult base,
                                                    @NotNull String sql,
                                                    @NotNull String compactSql,
                                                    @NotNull List<String> unresolvedParams,
                                                    @Nullable String paramTable,
                                                    @Nullable WrapperType wrapperType,
                                                    @Nullable String tableName,
                                                    @Nullable String warning,
                                                    @NotNull List<ParamHint> paramHints,
                                                    boolean hasDollarRawSegments,
                                                    boolean informationalWarning,
                                                    @Nullable IbatisParamModel ibatisParamModel,
                                                    @Nullable Function1<PreviewRerenderInput, PreviewResult> rerender) {
        PreviewResult b = base;
        return new PreviewResult(
            sql, compactSql, unresolvedParams, paramTable,
            wrapperType, tableName, warning,
            b != null ? b.getColumns() : null,
            b != null ? b.getColumnComments() : null,
            b != null ? b.getTableComment() : null,
            b != null ? b.getPreviewModel() : null,
            paramHints,
            b != null ? b.getCandidateTables() : null,
            b != null ? b.getBatchModel() : null,
            b != null ? b.getXmlModel() : null,
            b != null ? b.getInitialParamValues() : null,
            b != null ? b.getPerfWarnings() : null,
            b != null ? b.getFillClauses() : null,
            b != null ? b.getPreviousSql() : null,
            b != null ? b.getPagesColumns() : null,
            b != null ? b.getNamedParamHints() : null,
            b != null ? b.getStackDigest() : null,
            b != null ? b.getThreadName() : null,
            hasDollarRawSegments,
            b != null ? b.getRetryWhenIndexReady() : null,
            informationalWarning,
            b != null ? b.getCountSql() : null,
            b != null ? b.getPaginationLimitLine() : null,
            b != null ? b.getPlaceholderParamNames() : null,
            b != null ? b.getDuplicateParamNames() : null,
            b != null ? b.getDiagramStatement() : null,
            b != null ? b.getHistoryFingerprintSeed() : null,
            b != null ? b.getPaginationParamSources() : null,
            b != null ? b.getRenderedDialect() : null,
            b != null ? b.getDialectDecision() : null,
            b != null ? b.getHistoryEntryId() : null,
            b != null ? b.getActualSql() : null,
            b != null ? b.getSourceLanguage() : null,
            b != null ? b.getOriginalSql() : null,
            b != null ? b.getDegradations() : null,
            b != null ? b.getRuntimeSlots() : null,
            ibatisParamModel,
            rerender
        );
    }

    /**
     * master {@code baseline.copy(ibatisParamModel =, rerender =)} 的 Java 承载
     * （preview() 基线挂参数行清单 + 重渲染回调；retryWhenIndexReady 等其余量沿用基线）。
     */
    @NotNull
    private static PreviewResult withIbatisModel(@NotNull PreviewResult baseline,
                                                 @Nullable IbatisParamModel ibatisParamModel,
                                                 @Nullable Function1<PreviewRerenderInput, PreviewResult> rerender) {
        return withIbatisOverride(
            baseline,
            baseline.getSql(), baseline.getCompactSql(), baseline.getUnresolvedParams(), baseline.getParamTable(),
            baseline.getWrapperType(), baseline.getTableName(), baseline.getWarning(), baseline.getParamHints(),
            baseline.isHasDollarRawSegments(), baseline.isInformationalWarning(),
            ibatisParamModel, rerender
        );
    }
}
