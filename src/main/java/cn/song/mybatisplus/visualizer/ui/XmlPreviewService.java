package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.context.ActualSqlPsiResolver;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext.ModuleIdentityResolution;
import cn.song.mybatisplus.visualizer.context.ModuleIdentityResolver;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext.PreviewSourceOrigin;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext.SqlPsiResolution;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psi.XmlCrossNsFragmentResolver;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.xml.CrossNamespaceFragments;
import cn.song.mybatisplus.visualizer.xml.MockObject;
import cn.song.mybatisplus.visualizer.xml.ParameterMapping;
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo;
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql;
import cn.song.mybatisplus.visualizer.xml.XmlEwExpander;
import cn.song.mybatisplus.visualizer.ui.XmlEwResolver;
import cn.song.mybatisplus.visualizer.xml.XmlMapperModel;
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser;
import cn.song.mybatisplus.visualizer.xml.XmlParseException;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel.XmlPreviewProfile;
import cn.song.mybatisplus.visualizer.xml.XmlScriptException;
import cn.song.mybatisplus.visualizer.xml.XmlStatement;
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.jvm.functions.Function0;

/**
 * XML 预览服务（T-M24-04）：mapper 文件文本 → 语句 → 预览模型 → PreviewResult。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位语句 id；
 * 弹窗与面板完全复用 SqlPreviewService.showResult 链路。
 *
 * <ul>
 * <li>参数对照表复用 {@link ParameterReplacer#buildParamTable}（`#{}` 属性名清单，顺序对应 `?`）；</li>
 * <li>T-M25.2：传入 project 时解析 `@class@field` 静态常量引用（用户项目常量类字段初始值，
 * 经 PSI 常量求值器取值）注入渲染——MyBatis mapper 常见 OGNL 常量写法。</li>
 * <li>T-IC-02：传入 project 时另经 {@link XmlCrossNsFragmentResolver} 解析跨命名空间 include
 * （`refid="otherNamespace.sqlId"`）并注入展开；无 project（纯文本链）传空表，
 * 行为与改动前逐字节一致。</li>
 * </ul>
 */
public final class XmlPreviewService {

    public static final XmlPreviewService INSTANCE = new XmlPreviewService();

    /** T-MbgPreviewParityAndOpenPerf P5：装配分段耗时埋点（超阈值才打一条，正常路径不刷屏）。 */
    private static final Logger LOG = Logger.getInstance(XmlPreviewService.class);

    /** 分段耗时日志阈值（毫秒）：低于此值不打，避免每次预览都写日志。 */
    private static final long PERF_LOG_THRESHOLD_MS = 200L;

    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");
    /** T-M25.2：OGNL 静态字段引用 `@类全名@字段名`（类名可含 `$` 内部类）。 */
    private static final Pattern STATIC_REF_REGEX = Pattern.compile("@([A-Za-z_$][\\w.$]*)@(\\w+)");

    private XmlPreviewService() {
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId) {
        return preview(fileText, statementId, null, null, null);
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId, @Nullable Project project) {
        return preview(fileText, statementId, project, null, null);
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId,
                                 @Nullable Project project, @Nullable SqlCompletionContext entityContext) {
        return preview(fileText, statementId, project, entityContext, null);
    }

    /**
     * O-145 W1：只在选定的 mapper 语句域解析实际 SQL 注入；文件内其他语句不能充当 owner。
     * 光标不在该语句内时不传精确偏移，由解析器完整扫描 owner 并对多注入返回歧义。
     * 仅在本次预览中携带 PSI，不写入模型、面板或历史。
     */
    @NotNull
    public PreviewSourceContext resolveSourceContext(@NotNull XmlFile file, @NotNull XmlTag tag,
                                                     @Nullable Integer caretOffset) {
        Project project = file.getProject();
        return ReadAction.compute(() -> {
            boolean validOwner = tag.isValid() && tag.getContainingFile() == file
                    && tag.getParentTag() != null && "mapper".equals(tag.getParentTag().getName())
                    && ("select".equals(tag.getName()) || "insert".equals(tag.getName())
                    || "update".equals(tag.getName()) || "delete".equals(tag.getName()));
            Integer offset = caretOffset;
            if (offset != null && !(validOwner
                    && offset >= tag.getTextRange().getStartOffset()
                    && offset < tag.getTextRange().getEndOffset())) {
                offset = null;
            }
            SqlPsiResolution resolution = validOwner
                    ? ActualSqlPsiResolver.resolve(file, tag, offset)
                    : SqlPsiResolution.None.INSTANCE;
            ModuleIdentityResolution module = file.getVirtualFile() != null
                    ? ModuleIdentityResolver.resolve(project, file.getVirtualFile())
                    : null;
            if (module == null) {
                module = PreviewSourceContext.ModuleIdentityResolution.None.INSTANCE;
            }
            return new PreviewSourceContext(
                    project,
                    PreviewSourceOrigin.XML,
                    file,
                    file.getVirtualFile(),
                    resolution,
                    module);
        });
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId,
                                 @Nullable Project project, @Nullable SqlCompletionContext entityContext,
                                 @Nullable PreviewSourceContext sourceContext) {
        // R12 T-PaginationSim：解析产物三元组（namespace/语句 id/语句类型）透出 try 块——分页模拟后处理用
        ParsedRef[] parsedRef = new ParsedRef[1];
        // T-PreviewOpenPerfM2 M9：本轮是否因 dumb mode 跳过了 M2（决定要不要挂「索引就绪后重算」）
        boolean[] m2Skipped = new boolean[1];
        XmlPreviewModel model;
        List<String> staticWarnings;
        try {
            // T-IC-02：跨命名空间 include 片段表（无 project → 空表，纯文本链行为不变）
            // T-MbgPreviewParityAndOpenPerf P5：分段计时（把「打开慢」归因到具体一段）
            long perfStart = System.currentTimeMillis();
            CrossNamespaceFragments crossNamespace = XmlCrossNsFragmentResolver.INSTANCE.resolve(fileText, project);
            XmlMapperModel parsed = new XmlMapperParser().parse(fileText, crossNamespace);
            XmlStatement statement = null;
            if (statementId != null) {
                for (XmlStatement s : parsed.statements()) {
                    if (statementId.equals(s.id())) {
                        statement = s;
                        break;
                    }
                }
            }
            if (statement == null) {
                for (XmlStatement s : parsed.statements()) {
                    statement = s;
                    break;
                }
            }
            if (statement == null) {
                return emptyResult("未找到可预览的 SQL 语句（mapper 中无 select/insert/update/delete）。");
            }
            parsedRef[0] = new ParsedRef(parsed.namespace(), statement.id(), statement.kind());
            List<String> warnings = new ArrayList<>();
            Map<String, Object> staticValues = resolveStaticFields(fileText, project, warnings);
            // O-77 T-EwStatementScope：占位按语句域扫描（statement.rawText），他语句占位不再记账到本语句
            Map<String, Object> ewBindings = resolveEwPlaceholders(fileText, statement.rawText(), parsed.namespace(), statement.id(), project, warnings);
            // T-M29.5 / T-PT-01：mapper 方法参数类型 → 类型类别映射（引号由类别派生；
            // Map/解析失败不含键 → 面板兜底引号、无占位提示）
            Map<String, ParamTypeInfo> paramTypes = XmlParamTypeResolver.INSTANCE.resolveTypes(project, parsed.namespace(), statement.id());
            long perfCtxParsed = System.currentTimeMillis();
            // T-MbgExamplePreview R5：MBG Example 参数类型 → 默认勾选档（distinct / orderByClause 不展开、
            // 集合按 1 项）；解析不到一律 null，行为与升级前一致
            XmlPreviewProfile profile = resolveExampleProfile(project, statement.parameterType(), parsed.namespace());
            long perfProfile = System.currentTimeMillis();
            // T-MbgExamplePreview M2：Example 形态下再从调用点反推 Criterion（andXxx 方法体的
            // addCriterion 常量）——拿到真值后 ${criterion.condition} 不再落占位
            // T-PreviewOpenPerfM2 M8：dumb mode（索引未就绪 / 正在重建）时**跳过 M2 检索**。
            // `ReferencesSearch` 依赖索引，在索引构建期会同步等待——真机 2026-11-12 11:21 冷启动首次
            // 打开 request→dialog 4499ms，而同一时刻 xml 装配只有 417ms；热态同一语句仅 170ms。
            // 跳过后退回占位骨架（`{{criterion.condition}}`），热态打开或下次打开由缓存补齐。
            boolean m2Allowed = project != null && !DumbService.getInstance(project).isDumb();
            m2Skipped[0] = profile != null && !m2Allowed;
            if (m2Skipped[0]) LOG.info("[open-perf] m2 skipped: dumb mode (索引未就绪)");
            Map<String, List<MockObject>> collectionElements;
            if (profile == null || !m2Allowed) {
                collectionElements = Map.of();
            } else {
                Map<String, List<MockObject>> resolved = MbgExampleCallResolver.resolve(project, parsed.namespace(), statement.id());
                collectionElements = resolved != null ? resolved : Map.of();
            }
            long perfM2 = System.currentTimeMillis();
            model = XmlPreviewModel.from(statement, parsed.errors(), Set.of(), staticValues, ewBindings, Map.of(),
                    paramTypes, profile, collectionElements);
            logPreviewPerf(perfStart, perfCtxParsed, perfProfile, perfM2, profile != null);
            staticWarnings = warnings;
        } catch (XmlParseException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "XML 解析失败。");
        } catch (XmlScriptException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "SQL 脚本解析失败。");
        }

        // T-MbgExamplePreview R6：预览面板传 includeRenderErrors=false——面板随后会按用户当前勾选
        // 重渲染，并把本轮错误放进 xmlErrors；两份混装会让告警描述一个与当前产物不一致的状态
        //（用户截图里两条互相矛盾的错误即此形态）。
        PreviewResult preview = toResult(model, staticWarnings, entityContext, false);
        PreviewResult result = preview;
        if (project != null) {
            PreviewSourceContext contextForDecision = sourceContext != null && project.equals(sourceContext.project())
                    ? sourceContext
                    : null;
            cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision decision =
                    contextForDecision != null
                            ? DialectRegistry.INSTANCE.decision(contextForDecision)
                            : DialectRegistry.INSTANCE.decision(project);
            result = copyWithDialectDecision(preview,
                    DialectRegistry.INSTANCE.byName(decision.dialectName()), decision);
        }

        ParsedRef ref = parsedRef[0];
        if (ref == null) return result;
        PreviewResult finalResult = applyPagination(
                result,
                project,
                ref.namespace(),
                ref.statementId(),
                ref.kind());
        // T-PreviewOpenPerfM2 M9：索引未就绪时跳过过 M2 —— 挂「就绪后重算一次」的闭包，
        // 面板收到后由 `DumbService.runWhenSmart` 在 smart mode 重算并刷新
        //（冷态秒出骨架 + 索引就绪后自动补齐真实 Criterion，无需用户手动刷新）。
        if (m2Skipped[0]) {
            return copyWithRetryWhenIndexReady(finalResult,
                    () -> preview(fileText, statementId, project, entityContext, sourceContext));
        }
        return finalResult;
    }

    /** T-PreviewOpenPerfM2 M9：master {@code finalResult.copy(retryWhenIndexReady = …)} 的 Java 承载。 */
    private static PreviewResult copyWithRetryWhenIndexReady(@NotNull PreviewResult src,
                                                             @NotNull Function0<PreviewResult> retry) {
        return new PreviewResult(
                src.getSql(), src.getCompactSql(), src.getUnresolvedParams(), src.getParamTable(),
                src.getWrapperType(), src.getTableName(), src.getWarning(),
                src.getColumns(), src.getColumnComments(), src.getTableComment(), src.getPreviewModel(),
                src.getParamHints(), src.getCandidateTables(), src.getBatchModel(), src.getXmlModel(),
                src.getInitialParamValues(), src.getPerfWarnings(), src.getFillClauses(), src.getPreviousSql(),
                src.getPagesColumns(), src.getNamedParamHints(), src.getStackDigest(), src.getThreadName(),
                src.isHasDollarRawSegments(), retry, src.isInformationalWarning(),
                src.getCountSql(), src.getPaginationLimitLine(), src.getPlaceholderParamNames(),
                src.getDuplicateParamNames(), src.getDiagramStatement(), src.getHistoryFingerprintSeed(),
                src.getPaginationParamSources(), src.getRenderedDialect(), src.getDialectDecision(),
                src.getHistoryEntryId(), src.getActualSql(), src.getSourceLanguage(), src.getOriginalSql(),
                src.getDegradations(), src.getRuntimeSlots(), src.getIbatisParamModel(), src.getRerender());
    }

    /** Kotlin data class {@code copy(renderedDialect=…, dialectDecision=…)} 等价：其余字段原样保留。 */
    static PreviewResult copyWithDialectDecision(
            @NotNull PreviewResult src,
            @Nullable cn.song.mybatisplus.visualizer.sql.Dialect renderedDialect,
            @Nullable cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision dialectDecision) {
        return new PreviewResult(
                src.getSql(),
                src.getCompactSql(),
                src.getUnresolvedParams(),
                src.getParamTable(),
                src.getWrapperType(),
                src.getTableName(),
                src.getWarning(),
                src.getColumns(),
                src.getColumnComments(),
                src.getTableComment(),
                src.getPreviewModel(),
                src.getParamHints(),
                src.getCandidateTables(),
                src.getBatchModel(),
                src.getXmlModel(),
                src.getInitialParamValues(),
                src.getPerfWarnings(),
                src.getFillClauses(),
                src.getPreviousSql(),
                src.getPagesColumns(),
                src.getNamedParamHints(),
                src.getStackDigest(),
                src.isHasDollarRawSegments(),
                src.getCountSql(),
                src.getPaginationLimitLine(),
                src.getPlaceholderParamNames(),
                src.getDuplicateParamNames(),
                src.getDiagramStatement(),
                src.getHistoryFingerprintSeed(),
                src.getPaginationParamSources(),
                renderedDialect,
                dialectDecision,
                src.getHistoryEntryId(),
                src.getActualSql());
    }

    /**
     * R12 T-PaginationSim：XML 语句路径的分页拦截器模拟后处理（mapper 接口方法带 Page/IPage 形参时）。
     * 定位形参后委托 {@link #applyPaginationShared}。
     */
    private PreviewResult applyPagination(
            PreviewResult result,
            Project project,
            String namespace,
            String statementId,
            XmlStatementKind kind) {
        try {
            if (!paginationSimEnabled()) return result;
            String paramName = MapperPageParamLocator.fromStatement(project, namespace, statementId);
            if (paramName == null) return result;
            return applyPaginationShared(result, project, paramName, kind);
        } catch (Throwable t) {
            return result;
        }
    }

    /**
     * R12 T-PaginationSim：分页拦截器模拟后处理共享实现（XML 路径与注解路径共用，
     * {@code pageParamName} 非 null 且语句为 SELECT 时生效）。
     *
     * <ul>
     *  <li>SELECT 语句 → 渲染文本尾部追加分页 LIMIT（方言形态，{@link DialectRegistry#effective}；
     *    形参运行时值静态不可得 → {@code LIMIT ?, ?} 占位，参数来源标注 IPage 访问器公式）；</li>
     *  <li>同产出 COUNT 包裹附第二条语句（{@code SELECT COUNT(*) FROM (原SQL) TOTAL}，MP 3.5.17
     *    getOriginalCountSql 逐字节形态，包裹体**不含** LIMIT——运行时 COUNT 先于 LIMIT 执行）；
     *    受 COUNT 子开关 {@link #paginationCountEnabled} 独立控制（T-CountSimToggle，D129：关=只追加
     *    LIMIT 不产 COUNT；总开关在调用方判定，父开关优先）；</li>
     *  <li>语句已含 LIMIT（用户手写，V13c 边界）→ 不重复追加、不产 COUNT（预览保守口径，偏离
     *    MP 运行时「字面拼接」的偏离已登记）；</li>
     *  <li>非 SELECT（INSERT/UPDATE/DELETE）不分页（对齐 T-M29-03「仅 SELECT 生效」口径）。</li>
     * </ul>
     */
    public static PreviewResult applyPaginationShared(
            PreviewResult result,
            Project project,
            String pageParamName,
            XmlStatementKind kind) {
        try {
            if (pageParamName == null) return result;
            if (kind != XmlStatementKind.SELECT) return result;
            if (PaginationSimulator.hasExistingLimit(result.getSql())) return result;
            cn.song.mybatisplus.visualizer.model.LimitClause limitClause =
                    PaginationSimulator.placeholderLimitClause(pageParamName);
            cn.song.mybatisplus.visualizer.sql.Dialect effectiveDialect = result.getEffectiveDialect();
            String limitLine = (effectiveDialect != null
                    ? effectiveDialect
                    : DialectRegistry.INSTANCE.effective(project)).renderLimit(limitClause);
            String countSql = paginationCountEnabled()
                    ? PaginationSimulator.wrapCountSql(WHITESPACE_REGEX.matcher(result.getSql()).replaceAll(" ").trim())
                    : null;
            List<String> extraSources = PaginationSimulator.placeholderParamSources(pageParamName);
            List<String> unresolvedParams = new ArrayList<>(result.getUnresolvedParams());
            unresolvedParams.addAll(extraSources);
            List<ParamHint> paramHints = new ArrayList<>(result.getParamHints());
            for (String s : extraSources) paramHints.add(new ParamHint(false));
            // Kotlin data class copy 语义：未覆盖字段（含 diagramStatement/historyFingerprintSeed/
            // renderedDialect/dialectDecision/historyEntryId/actualSql）原样保留。
            return new PreviewResult(
                    trimEnd(result.getSql()) + "\n" + limitLine,
                    trimEnd(result.getCompactSql()) + " " + limitLine,
                    unresolvedParams,
                    ParameterReplacer.buildParamTable(unresolvedParams),
                    result.getWrapperType(),
                    result.getTableName(),
                    result.getWarning(),
                    result.getColumns(),
                    result.getColumnComments(),
                    result.getTableComment(),
                    result.getPreviewModel(),
                    paramHints,
                    result.getCandidateTables(),
                    result.getBatchModel(),
                    result.getXmlModel(),
                    result.getInitialParamValues(),
                    result.getPerfWarnings(),
                    result.getFillClauses(),
                    result.getPreviousSql(),
                    result.getPagesColumns(),
                    result.getNamedParamHints(),
                    result.getStackDigest(),
                    result.isHasDollarRawSegments(),
                    countSql,
                    limitLine,
                    result.getPlaceholderParamNames(),
                    result.getDuplicateParamNames(),
                    result.getDiagramStatement(),
                    result.getHistoryFingerprintSeed(),
                    extraSources,
                    result.getRenderedDialect(),
                    result.getDialectDecision(),
                    result.getHistoryEntryId(),
                    result.getActualSql());
        } catch (Throwable t) {
            return result;
        }
    }

    /** 分页模拟开关（测试环境未注册设置服务时按默认开）。 */
    public static boolean paginationSimEnabled() {
        try {
            return PluginSettings.getInstance().getState().getPaginationSimEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 分页 COUNT 子开关（T-CountSimToggle；测试环境未注册设置服务时按默认开，父开关优先在调用方判定）。 */
    public static boolean paginationCountEnabled() {
        try {
            return PluginSettings.getInstance().getState().getPaginationCountEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Kotlin {@code String.trimEnd()} 等价（去尾部空白）。 */
    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** R12 T-PaginationSim：解析产物三元组（namespace/语句 id/语句类型）。 */
    private record ParsedRef(String namespace, String statementId, XmlStatementKind kind) {
    }

    /**
     * T-M26-B：MP 占位展开——扫描 `${变量.customSqlSegment/sqlSelect/sqlSet}`，
     * 经 XmlEwResolver 关联 Java Mapper 方法 wrapper 实参展开为 SQL 片段，
     * 包装为 MockObject 注入 mock bindings。
     *
     * <p>O-77 T-EwStatementScope：扫描域为当前语句原文（{@code statementText}，include 展开后内容，
     * 不含他语句与注释），识别域对齐解析域——同文件他语句的 ew 占位不再触发本语句的关联与警示；
     * {@code statementText} 为 null（注解 {@code <script>} 等非 mapper 解析链构造）回落文件全文，行为不变。</p>
     */
    private Map<String, Object> resolveEwPlaceholders(String fileText, String statementText, String namespace,
                                                     String statementId, Project project, List<String> warnings) {
        List<cn.song.mybatisplus.visualizer.xml.EwPlaceholder> placeholders =
                XmlEwExpander.placeholders(statementText != null ? statementText : fileText);
        if (placeholders.isEmpty() || project == null) return java.util.Collections.emptyMap();
        Map<String, Map<String, String>> resolved =
                XmlEwResolver.INSTANCE.resolve(namespace, statementId, placeholders, project, warnings);
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> e : resolved.entrySet()) {
            result.put(e.getKey(), new MockObject(new HashMap<>(e.getValue())));
        }
        return result;
    }

    /**
     * 静态字段引用解析（T-M25.2）：扫描文本中的 `@类全名@字段名`（OGNL 静态访问），
     * 经 PSI 定位用户项目中的常量类与字段初始值。
     */
    public Map<String, Object> resolveStaticFields(String fileText, Project project, List<String> warnings) {
        if (project == null) return java.util.Collections.emptyMap();
        Set<String> refs = new java.util.LinkedHashSet<>();
        Matcher m = STATIC_REF_REGEX.matcher(fileText);
        while (m.find()) {
            refs.add(m.group(1) + "@" + m.group(2));
        }
        if (refs.isEmpty()) return java.util.Collections.emptyMap();
        Map<String, Object> result = new HashMap<>();
        for (String ref : refs) {
            String[] parts = ref.split("@", 2);
            String className = parts[0];
            String fieldName = parts[1];
            String value = resolveStaticFieldValue(project, className, fieldName);
            if (value == null) {
                warnings.add("静态常量 " + ref + " 无法解析（类/字段不存在或非常量），预览按空值处理，可在预览中填写覆盖值");
            } else {
                result.put(ref, value);
            }
        }
        return result;
    }

    /** 单字段求值：findClass → findFieldByName → 字段初始值常量求值。 */
    public String resolveStaticFieldValue(Project project, String className, String fieldName) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.allScope(project));
        if (psiClass == null) return null;
        PsiField field = psiClass.findFieldByName(fieldName, false);
        if (field == null) return null;
        PsiExpression initializer = field.getInitializer();
        if (initializer == null) return null;
        Object constVal = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper()
                .computeConstantExpression(initializer);
        return constVal != null ? constVal.toString() : null;
    }

    /**
     * T-MbgPreviewParityAndOpenPerf P5：装配分段耗时（总耗时低于阈值不打，正常路径零噪音）。
     *
     * <p>现场（2026-11-12）prewarm done 到首次 showSql 之间有 <b>5.67s 空档</b>且插件侧零日志，
     * 无法归因。这条把装配切成四段：解析+上下文 / profile（PSI 类解析）/ M2（PSI 调用点检索）/
     * 建模——下次真机复现即可读出是哪一段。</p>
     */
    private static void logPreviewPerf(long start, long ctxEnd, long profileEnd, long m2End, boolean always) {
        long end = System.currentTimeMillis();
        long total = end - start;
        // MBG Example 档恒记（always）：缓存命中的热态打开常在 200ms 以下，阈值会把
        // 「第二次 m2 是否接近 0」这条唯一可证伪的读数一起吞掉（T-PreviewOpenPerfM2 M1 验收缺口）。
        if (!always && total < PERF_LOG_THRESHOLD_MS) return;
        LOG.info("[open-perf] total=" + total + "ms parse+ctx=" + (ctxEnd - start)
                + " profile=" + (profileEnd - ctxEnd) + " m2=" + (m2End - profileEnd)
                + " model=" + (end - m2End));
    }

    /**
     * T-MbgExamplePreview R5：{@code parameterType} 是否为 MBG 生成器的 Example 类。
     *
     * <p>判据（任一命中即可，都是 MBG 生成模板的固定结构）：类里有 {@code oredCriteria} 字段，
     * 或有 {@code createCriteria()} 方法。命中 → {@link XmlPreviewProfile#MBG_EXAMPLE}；无 project、
     * 空 parameterType、类解析不到 → null（不改任何默认值，行为与升级前一致）。</p>
     *
     * <p>{@code parameterType} 可能是全限定名（MBG 默认）也可能是短名；短名按 mapper 接口所在包补全
     * （namespace 是 mapper 接口全名，Example 类与之同包）。</p>
     */
    static XmlPreviewProfile resolveExampleProfile(@Nullable Project project, @Nullable String parameterType,
                                                   @Nullable String namespace) {
        if (project == null || parameterType == null || parameterType.isBlank()) return null;
        String fqn;
        if (parameterType.indexOf('.') >= 0) {
            fqn = parameterType;
        } else {
            String pkg = namespace != null && namespace.contains(".")
                    ? namespace.substring(0, namespace.lastIndexOf('.')) : "";
            if (pkg.isEmpty()) return null;
            fqn = pkg + "." + parameterType;
        }
        PsiClass psiClass;
        try {
            psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project));
        } catch (Throwable t) { // master runCatching.getOrNull 语义
            psiClass = null;
        }
        if (psiClass == null) return null;
        boolean isExample;
        try {
            isExample = psiClass.findFieldByName("oredCriteria", true) != null
                    || psiClass.findMethodsByName("createCriteria", true).length > 0;
        } catch (Throwable t) {
            isExample = false;
        }
        return isExample ? XmlPreviewProfile.MBG_EXAMPLE : null;
    }

    /** 预览模型 → PreviewResult（T-M25-02 注解预览共用）：初始渲染 + 参数对照 + 错误合并。 */
    public PreviewResult toResult(XmlPreviewModel model) {
        return toResult(model, java.util.Collections.emptyList(), null, true);
    }

    public PreviewResult toResult(XmlPreviewModel model, List<String> extraWarnings) {
        return toResult(model, extraWarnings, null, true);
    }

    public PreviewResult toResult(XmlPreviewModel model, List<String> extraWarnings, SqlCompletionContext entityContext) {
        return toResult(model, extraWarnings, entityContext, true);
    }

    /**
     * Kotlin 默认参 {@code includeRenderErrors: Boolean = true} 的等价全参形态
     * （T-MbgExamplePreview R6：预览面板传 false——面板随后按用户当前勾选重渲染并自记
     * {@code xmlErrors}，两份混装会让告警描述与当前产物矛盾的状态；注解预览链无交互态保持 true）。
     */
    public PreviewResult toResult(XmlPreviewModel model, List<String> extraWarnings,
                                  SqlCompletionContext entityContext, boolean includeRenderErrors) {
        RenderedXmlSql rendered = model.renderSql();
        List<String> errors = new ArrayList<>(model.getErrors());
        errors.addAll(extraWarnings);
        if (includeRenderErrors) errors.addAll(rendered.errors());

        String sql = rendered.sql();
        List<String> unresolvedParams = new ArrayList<>();
        for (ParameterMapping mp : rendered.mappings()) {
            unresolvedParams.add(mp.property());
        }
        String paramTable = ParameterReplacer.buildParamTable(unresolvedParams);

        String joined = String.join("；", errors);
        String warning = joined.isEmpty() ? null : joined;

        return new PreviewResult(
                sql,
                WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim(),
                unresolvedParams,
                paramTable,
                null,
                entityContext != null ? entityContext.tableName : null,
                warning,
                entityContext != null ? entityContext.columns : List.of(),
                entityContext != null ? entityContext.columnComments : Map.of(),
                entityContext != null ? entityContext.tableComment : null,
                null,
                List.of(),
                List.of(),
                null,
                model,
                Map.of(),
                null,
                null,
                null,
                null,
                // T-InjectionGate T-IG-01：`${}` raw 拼接事实透传（解析期 token 收集，非最终文本 regex——
                // 成功替换为模拟值后文本里已无 `${}` 痕迹）
                Map.of(),
                null,
                model.hasDollarRawSegments(),
                List.of(),
                Set.of()
        );
    }

    private PreviewResult emptyResult(String message) {
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
                null
        );
    }
}
