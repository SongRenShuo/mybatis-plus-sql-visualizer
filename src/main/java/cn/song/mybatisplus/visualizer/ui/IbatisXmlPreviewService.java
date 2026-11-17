package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModel;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamModelBuilder;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamTypeHints;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParseException;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisRenderResult;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisSelectKey;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisSqlMap;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatement;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapParser;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapSqlRenderer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * iBatis 2.x 语句预览服务（T-Ibatis2Support 券 A 接管面）：{@code <sqlMap>} 文本 → 语句 →
 * {@link PreviewResult}。
 *
 * <p>与 {@code <pages>} 链同款<b>纯文本入口</b>（fileText 输入，无 PSI 依赖）：gutter / Action 负责
 * 取文件文本与锚定标签信息，弹窗与面板完全复用 SqlPreviewService.showResult 链路。</p>
 *
 * <h2>走的是哪条链（与 §券 0 的关系）</h2>
 *
 * <p>iBatis 2.x <b>无 HQL 概念</b>，语言判定对它整体是无效计算。本服务<b>完全绕开</b>
 * PagesSourceLanguageDetector / PagesEntityProbeResolver——这同时消掉「XML 内表名反查实体」那条
 * 慢路径（计划册 §券 0 的 CD-3 修法在 pages 链落地后，本链连判定入口都不进）。</p>
 *
 * <h2>三条对齐计划册 §1.10 的口径</h2>
 *
 * <ol>
 * <li><b>{@code #} 不转 {@code ?} 的分支只在渲染器里</b>（声明 parameterMap 且非动态语句）——
 *     本服务只做 {@code ?} 位点数与参数名序的对齐核对，不自行改写 SQL；</li>
 * <li><b>{@code $x$} 直替接既有注入闸门</b>：hasDollarRawSegments 由渲染器事实决定，不新开无闸门
 *     路径；iBatis {@code ${prop}}（引擎的全局属性 token，本链不做替换）同样计入；</li>
 * <li><b>渲染告警落到面板可见区</b>：复用既有 warning 载体；UI-001 起告警分档：parameterMap 的
 *     「引擎行为」说明与 iterate 未供值的「待填值」提示标 informationalWarning，面板用 ℹ 信息级
 *     前缀呈现，不再统一冒充「⚠ 预览未完整还原」。</li>
 * </ol>
 *
 * <h2>参数名口径（拍板 D-7）</h2>
 *
 * <p>IbatisRenderResult.paramNames 按 {@code ?} 出现顺序给出参数名（iBatis {@code #name#} 语义）。
 * 三种不齐形态：数量相符 → 直接用参数名；数量不符（手写 {@code ?}、{@code #} 保留未转、iterate 内
 * 重解析）→ 按 param1..N 兜底 + 告警说明；参数名非空但无 {@code ?} → 参数行留空 + 告警说明
 * 「参数名不可知」。</p>
 *
 * <h2>T-IbatisFix2（2026-11-10 用户 2026.3 验收反馈）</h2>
 *
 * <ol>
 * <li><b>{@code <selectKey>} 子语句展示</b>：按引擎语义组装——pre 在主语句之前、post 在后，块间用
 *     注释分隔。条件行/参数行仍以主语句为准（keyProperty 只进块注释）；selectKey 自身若含
 *     {@code #x#}，其参数名拼进参数名序保证 {@code ?} 位点对齐；</li>
 * <li><b>重渲染失败可见</b>：整条回调 catch（FatalErrors 豁免）：失败时 idea.log 记带堆栈 warn +
 *     返回可见失败结果，禁止吞掉后返回旧结果；</li>
 * <li><b>告警分档过滤</b>：信息级说明不再混入 ⚠ 失败档合并行。</li>
 * </ol>
 */
public final class IbatisXmlPreviewService {

    private static final Logger LOG = Logger.getInstance(IbatisXmlPreviewService.class);

    /** 渲染器降级告警的固定措辞（渲染器 catch 点「渲染降级（按原文保留 # 直替…）」）。 */
    private static final String DEGRADE_MARK = "渲染降级";

    private static final int MAX_WARNING_CHARS = 300;

    /** Kotlin object 语义兼容实例。 */
    public static final IbatisXmlPreviewService INSTANCE = new IbatisXmlPreviewService();

    private IbatisXmlPreviewService() {
    }

    /**
     * 预览一份 {@code <sqlMap>} 文本里的语句。
     *
     * @param fileText         sqlMap 文件全文
     * @param statementId      锚定标签的 id 属性（首选定位键；null 时按 tagName + ordinal 定位）
     * @param statementTagName 锚定标签名（select/insert/…；null 时退化为「取文件首条语句」）
     * @param statementOrdinal 同名标签中的序号（0 起；仅无 id 的非法文件用得上）
     * @param project          解析 parameterClass 属性类型用（null = 纯文本测试链，维持现状）
     * @param parameterClass   语句的 parameterClass 原值；null 时回落到 XML 属性里的同名项
     * @param moduleName       语句所在模块名——短名重名时按「就近」收窄；null = 不做模块过滤
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
        IbatisParamModel paramModel = IbatisParamModelBuilder.build(statement);
        List<SelectKeyRender> selectKeyRenders = renderSelectKeys(statement, null);
        // T-IbatisParamClassQuote：裸 `#name#` 的类型来源——parameterClass 的属性类型。
        // 只按首屏实际出现的属性名解析一次，重渲染复用（新分支带出的属性再查不到就维持启发式）。
        String effectiveParameterClass = parameterClass != null
                ? parameterClass : statement.getAttributes().get("parameterClass");
        List<String> propertyNames = new ArrayList<>(rendered.paramNames());
        for (SelectKeyRender render : selectKeyRenders) {
            propertyNames.addAll(render.result().paramNames());
        }
        Map<String, ParamHint> propertyHints = IbatisParameterClassResolver.resolve(
                project, effectiveParameterClass, propertyNames, moduleName);
        PreviewResult baseline = toPreviewResult(
                rendered,
                !paramModel.getDollarRows().isEmpty(),
                // 无参基线：iterate 行全部未供值（待填值态）
                !paramModel.getIterateRows().isEmpty(),
                selectKeyRenders,
                propertyHints);
        return baseline.withIbatisExtras(paramModel,
                // T-IbatisFix2 修复三：闭包捕获基线结果——重渲染失败时回落到它（sql 保留降级文本），
                // 不再静默吞掉异常
                input -> rerender(statement, paramModel, baseline, input, propertyHints));
    }

    /** 省略可选参的形态（gutter 侧常规入口）。 */
    @NotNull
    public static PreviewResult preview(@NotNull String fileText,
                                        @Nullable String statementId,
                                        @Nullable String statementTagName,
                                        int statementOrdinal) {
        return preview(fileText, statementId, statementTagName, statementOrdinal, null, null, null);
    }

    /** 只带 fileText + statementId 的形态（纯文本测试链）。 */
    @NotNull
    public static PreviewResult preview(@NotNull String fileText, @Nullable String statementId) {
        return preview(fileText, statementId, null, 0, null, null, null);
    }

    /**
     * 面板状态 → Map 装配 → 既有渲染器（空装配回退 null = 无参基线，与现状一致）。
     *
     * <p>T-IbatisFix2 修复三：整条回调 catch（FatalErrors.isFatal 豁免直传）——用户 2026.3 上
     * 「全勾全填后 SQL 保持在无参基线」本地不可复现，推断平台上回调内部抛异常被吞。失败不再静默：
     * idea.log 记带堆栈 warn + 返回可见失败结果。同时是取证手段：真机再触发时面板直接给出异常内容。</p>
     */
    private static PreviewResult rerender(@NotNull IbatisStatement statement,
                                          @NotNull IbatisParamModel paramModel,
                                          @NotNull PreviewResult baseline,
                                          @NotNull PreviewRerenderInput input,
                                          @Nullable Map<String, ParamHint> propertyHints) {
        try {
            Object params = IbatisParamModelBuilder.assembleParams(paramModel, input);
            IbatisRenderResult rendered = new SqlMapSqlRenderer().render(statement, params);
            boolean iteratePending = false;
            for (var row : paramModel.getIterateRows()) {
                if (input.collections().getOrDefault(row.property(), List.of()).isEmpty()) {
                    iteratePending = true;
                    break;
                }
            }
            return toPreviewResult(
                    rendered,
                    !paramModel.getDollarRows().isEmpty(),
                    iteratePending,
                    renderSelectKeys(statement, params),
                    propertyHints != null ? propertyHints : Map.of());
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) {
                throw e;
            }
            LOG.warn("iBatis 预览重渲染失败（statement=" + statement.getId() + "）：" + e.getMessage(), e);
            return rerenderFailedResult(baseline, e);
        }
    }

    /**
     * T-IbatisFix2 修复三的失败档产物（测试缝）：sql/compactSql/参数模型保留<b>基线降级文本</b>
     * （不是空结果），warning 换成失败档「重渲染失败：&lt;原因&gt;」，横幅以 ⚠ 呈现——禁止吞掉后
     * 静默返回旧结果。rerender 闭包随派生保留，用户修正参数后可立即重试。
     */
    @NotNull
    public static PreviewResult rerenderFailedResult(@NotNull PreviewResult baseline, @NotNull Throwable error) {
        return baseline.withWarning(
                MpSqlVisualizerBundle.message("ibatis.preview.rerenderFailed.warning",
                        error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()),
                false);
    }

    // ------------------------------------------------------------------
    // T-IbatisFix2 修复一：<selectKey> 子语句展示
    // ------------------------------------------------------------------

    /** selectKey 渲染产物对（key + 渲染结果）。 */
    record SelectKeyRender(IbatisSelectKey selectKey, IbatisRenderResult result) {
    }

    /** 渲染 {@code <selectKey>} 子语句（渲染器 renderSelectKey 已过保真对照，服务层此前漏调用）。 */
    @NotNull
    private static List<SelectKeyRender> renderSelectKeys(@NotNull IbatisStatement statement,
                                                          @Nullable Object params) {
        if (statement.getSelectKeys().isEmpty()) {
            return List.of();
        }
        SqlMapSqlRenderer renderer = new SqlMapSqlRenderer();
        List<SelectKeyRender> renders = new ArrayList<>();
        for (IbatisSelectKey selectKey : statement.getSelectKeys()) {
            IbatisRenderResult result;
            try {
                result = renderer.renderSelectKey(selectKey, params);
            } catch (Throwable error) {
                if (FatalErrors.isFatal(error)) {
                    throw error;
                }
                // selectKey 渲染失败不拖垮主语句：空块 + 失败档告警（可见失败，不静默丢块）
                result = new IbatisRenderResult(
                        "",
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(MpSqlVisualizerBundle.message(
                                "ibatis.preview.selectKeyRenderFailed.warning",
                                selectKey.id(),
                                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName())),
                        0,
                        List.of(),
                        List.of(selectKey.id()),
                        List.of(),
                        true,
                        List.of(),
                        List.of());
            }
            renders.add(new SelectKeyRender(selectKey, result));
        }
        return List.copyOf(renders);
    }

    /**
     * 多语句组装：<b>主语句恒在最前</b>，selectKey 块紧随其后，块间空行 + 注释分隔。
     *
     * <h2>为什么主语句必须在最前（安全面，不许按引擎时序排）</h2>
     *
     * <p>预览产出的 sql 会流进执行链，而执行链<b>只认首条语句</b>：
     * execute/SqlTypeClassifier.getFirstKeyword = splitStatements(sql).firstOrNull()，类型判定
     * （QUERY/DML/DDL）据此决定是否放行只读路径；execute/ExecutionScriptScope.shouldExecWholeScript
     * 在语句数 &gt; 1 时返回 false，「执行 SQL」退化为「当前游标所在语句」。</p>
     *
     * <p>若按引擎时序把 {@code type=pre} 的 selectKey 拼在最前，insertUser 预览的首条语句就是
     * {@code SELECT SEQ_USER_ID.NEXTVAL FROM DUAL} ⇒ 类型判成 QUERY（只读）⇒ <b>写操作被写安全闸
     * 当只读放过</b>，且用户点「执行 SQL」先跑的是序列查询而不是 INSERT。故物理顺序固定为
     * 主语句 → selectKey 块，真实时序只由块注释标明（type=pre + 「在主语句之前执行」）。</p>
     *
     * <h2>形态说明</h2>
     *
     * <p>本仓 MyBatis 链的多语句展示走独立字段（countSql/fillClauses 由面板并列渲染），
     * <b>没有文本级多语句先例可抄</b>——iBatis 链不动面板，故用注释标注 + 分号的纯文本形态。</p>
     */
    @Nullable
    private static String assembleMultiStatementSql(@NotNull IbatisRenderResult main,
                                                    @NotNull List<SelectKeyRender> selectKeyRenders) {
        if (selectKeyRenders.isEmpty()) {
            return null;
        }
        List<String> blocks = new ArrayList<>();
        appendBlock(blocks, "", main);
        for (SelectKeyRender render : selectKeyRenders) {
            appendBlock(blocks, selectKeyComment(render.selectKey()), render.result());
        }
        return String.join("\n\n", blocks);
    }

    private static void appendBlock(@NotNull List<String> blocks,
                                    @NotNull String comment,
                                    @NotNull IbatisRenderResult result) {
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
     * selectKey 块注释：type 按 {@link IbatisSelectKey#runAfterSql()}（引擎的显式 type + 位置推断
     * 合并口径），并<b>用文字标明真实执行时机</b>——物理上它排在主语句之后（执行链安全面要求），
     * 时序不能靠位置读。
     */
    @NotNull
    private static String selectKeyComment(@NotNull IbatisSelectKey selectKey) {
        String type = selectKey.runAfterSql() ? "post" : "pre";
        String timing = MpSqlVisualizerBundle.message(selectKey.runAfterSql()
                ? "ibatis.preview.selectKey.timing.post"
                : "ibatis.preview.selectKey.timing.pre");
        String keyProperty = selectKey.attributes().get("keyProperty");
        if (keyProperty == null || keyProperty.isBlank()) {
            return MpSqlVisualizerBundle.message("ibatis.preview.selectKey.blockCommentNoKey", type, timing);
        }
        return MpSqlVisualizerBundle.message("ibatis.preview.selectKey.blockComment", type, keyProperty, timing);
    }

    /** 多语句的紧凑形态（与 assembleMultiStatementSql 同序：主语句在前，分号收尾）。 */
    @NotNull
    private static String assembleMultiStatementCompact(@NotNull IbatisRenderResult main,
                                                        @NotNull List<SelectKeyRender> selectKeyRenders) {
        List<String> parts = new ArrayList<>();
        appendCompactPart(parts, main);
        for (SelectKeyRender render : selectKeyRenders) {
            appendCompactPart(parts, render.result());
        }
        return String.join(" ", parts);
    }

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
        for (IbatisStatement statement : model.statements()) {
            if (statementTagName == null || statementTagName.equals(statement.getKindTagName())) {
                sameKind.add(statement);
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
    private static PreviewResult toPreviewResult(@NotNull IbatisRenderResult rendered,
                                                 boolean statementHasRawSubstitutionSites,
                                                 boolean iteratePending,
                                                 @NotNull List<SelectKeyRender> selectKeyRenders,
                                                 @Nullable Map<String, ParamHint> propertyHints) {
        // T-IbatisFix2 修复一：selectKey 块参与 sql/compactSql 组装与参数名序（pre 前 / post 后）
        String assembledSql = assembleMultiStatementSql(rendered, selectKeyRenders);
        String sql = assembledSql != null ? assembledSql : rendered.sql();
        String compactSql = assembledSql != null
                ? assembleMultiStatementCompact(rendered, selectKeyRenders) : rendered.compactSql();
        int placeholders = PlaceholderPositionsKt.placeholderPositions(sql).size();
        // 参数名序 = SQL 文本里 `?` 的出现序（主语句块在前，selectKey 块按声明序在其后）——
        // 与拼装顺序严格同源，否则位点错位会让参数名贴到别的占位符上。
        List<String> names = new ArrayList<>(rendered.paramNames());
        for (SelectKeyRender render : selectKeyRenders) {
            names.addAll(render.result().paramNames());
        }
        List<String> unresolved;
        if (placeholders == 0) {
            unresolved = List.of();
        } else if (names.size() == placeholders) {
            unresolved = names;
        } else {
            List<String> paramN = new ArrayList<>(placeholders);
            for (int i = 1; i <= placeholders; i++) {
                paramN.add("param" + i);
            }
            unresolved = paramN;
        }

        // T-IbatisFix3 缺陷三：`#x:VARCHAR#` 的 jdbcType 必须传到面板的回填提示链——否则填纯数字
        // 会按「纯数字」启发式裸渲染。只在<b>位点与参数名数量相符</b>时装配（数量不符走 paramN 兜底，
        // 名字与 jdbcType 已无法对齐，强行贴类型会把 A 列的类型挂到 B 位点上）；映射不到 jdbcType 的
        // 位点用默认 ParamHint（quote=null = 面板启发式，与修复前逐字节一致）。
        List<String> jdbcTypes = new ArrayList<>(rendered.paramJdbcTypes());
        for (SelectKeyRender render : selectKeyRenders) {
            jdbcTypes.addAll(render.result().paramJdbcTypes());
        }
        List<ParamHint> paramHints = List.of();
        if (names.size() == placeholders && jdbcTypes.size() == placeholders) {
            // T-IbatisParamClassQuote：jdbcType 仍是首位（既有口径不动）；裸 `#name#`（jdbcType 缺省）
            // 才按 parameterClass 的属性类型补位——iBatis 引擎本身就是拿它反射决定 setString/setInt
            List<ParamHint> hints = new ArrayList<>(placeholders);
            for (int index = 0; index < placeholders; index++) {
                ParamHint hint = IbatisParamTypeHints.hintOf(jdbcTypes.get(index));
                if (hint == null && propertyHints != null) {
                    hint = propertyHints.get(names.get(index));
                }
                hints.add(hint != null ? hint : new ParamHint());
            }
            paramHints = List.copyOf(hints);
        }

        // UI-001：告警分档——「待填值」降级与「引擎行为说明」是信息级说明，不得包装成渲染失败。
        // 判据按来源自证，不猜文案全量：解析器对 parameterMap 自标「（引擎行为）」；iterate 未供值
        // 的降级 = 行清单里该行就在、填值即补齐。
        // T-IbatisFix2 附带小修：分档**过滤**——信息级说明与真失败分开收集，混合时 ⚠ 失败档合并行
        // 只保留真失败（信息级说明此前会混进 ⚠ 行、ℹ 行又重复同内容）；纯信息级整行走 ℹ。
        List<String> failureWarnings = new ArrayList<>();
        List<String> noteWarnings = new ArrayList<>();
        for (String warning : rendered.warnings()) {
            classify(warning, iteratePending, failureWarnings, noteWarnings);
        }
        for (SelectKeyRender render : selectKeyRenders) {
            for (String warning : render.result().warnings()) {
                classify(warning, iteratePending, failureWarnings, noteWarnings);
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

        boolean hasDollarRawSegments = rendered.dollarRawCount() > 0
                || selectKeyRenders.stream().anyMatch(r -> r.result().dollarRawCount() > 0)
                || statementHasRawSubstitutionSites
                || sql.contains("${");

        String warningLine = joinWarnings(warningsForLine);
        // UI-001：信息级分档——纯信息级说明（无真失败且有告警）标 informationalWarning，
        // 面板 updateMeta 据此用 ℹ 前缀（master: failureWarnings.isEmpty() && warningsForLine.isNotEmpty()）。
        boolean informational = failureWarnings.isEmpty() && !warningsForLine.isEmpty();
        return new PreviewResult(
                sql,
                compactSql,
                unresolved,
                ParameterReplacer.buildParamTable(unresolved),
                null,
                null,
                warningLine,
                List.<String>of(),
                Map.<String, String>of(),
                null,
                null,
                paramHints,
                List.<TableInfo>of(),
                null,
                null,
                Map.<String, String>of(),
                null,
                null,
                null,
                null,
                Map.<String, ParamHint>of(),
                null,
                // 安全面（计划册 §六.4「不得新开无闸门路径」）：
                // ① `$x$` **站点存在**即置闸——即使无参基线下取值为 null 被引擎静默丢弃（预览 SQL 里
                //    看不到那段文本），运行期它仍是原文直拼面；条件子树内的站点（statementHasRawSubstitutionSites）
                //    一并置闸，否则「勾上 isNotEmpty 填值后执行」绕过注入确认；
                // ② 未替换的 `${prop}`（引擎按全局属性替换、本链保留原文）在 SQL 文本里就是 ${} 原文段；
                // ③ T-IbatisFix2 修复一：selectKey 块内的 `$` 站点同样计闸。
                hasDollarRawSegments)
                .withWarning(warningLine, informational);
    }

    private static void classify(@NotNull String warning,
                                 boolean iteratePending,
                                 @NotNull List<String> failureWarnings,
                                 @NotNull List<String> noteWarnings) {
        if (isInformationalWarning(warning, iteratePending)) {
            noteWarnings.add(warning);
        } else {
            failureWarnings.add(warning);
        }
    }

    /** UI-001：信息性告警判据（按产出点自带标记，见 toPreviewResult 内注释）。 */
    private static boolean isInformationalWarning(@NotNull String warning, boolean iteratePending) {
        if (warning.contains("（引擎行为）")) {
            return true;
        }
        return iteratePending && warning.contains(DEGRADE_MARK) && warning.contains("iterate");
    }

    /** 告警清单压成一行（面板 meta 单行渲染；超长截断，完整信息在 SQL 注释与日志里）。 */
    @Nullable
    private static String joinWarnings(@NotNull List<String> warnings) {
        List<String> distinct = warnings.stream().filter(w -> !w.isBlank()).distinct().toList();
        if (distinct.isEmpty()) {
            return null;
        }
        String joined = String.join("；", distinct);
        if (joined.length() > MAX_WARNING_CHARS) {
            return joined.substring(0, MAX_WARNING_CHARS) + "…";
        }
        return joined;
    }

    @NotNull
    private static PreviewResult emptyResult(@NotNull String message) {
        return new PreviewResult(
                "",
                "",
                List.of(),
                null,
                null,
                null,
                message,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }
}
