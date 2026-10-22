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
import cn.song.mybatisplus.visualizer.xml.XmlScriptException;
import cn.song.mybatisplus.visualizer.xml.XmlStatement;
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind;
import com.intellij.openapi.application.ReadAction;
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
                    module,
                    null,
                    null);
        });
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId,
                                 @Nullable Project project, @Nullable SqlCompletionContext entityContext,
                                 @Nullable PreviewSourceContext sourceContext) {
        // R12 T-PaginationSim：解析产物三元组（namespace/语句 id/语句类型）透出 try 块——分页模拟后处理用
        ParsedRef[] parsedRef = new ParsedRef[1];
        XmlPreviewModel model;
        List<String> staticWarnings;
        try {
            // T-IC-02：跨命名空间 include 片段表（无 project → 空表，纯文本链行为不变）
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
            model = XmlPreviewModel.from(statement, parsed.errors(), Set.of(), staticValues, ewBindings, Map.of(), paramTypes);
            staticWarnings = warnings;
        } catch (XmlParseException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "XML 解析失败。");
        } catch (XmlScriptException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "SQL 脚本解析失败。");
        }

        PreviewResult preview = toResult(model, staticWarnings, entityContext);
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
        return applyPagination(
                result,
                project,
                ref.namespace(),
                ref.statementId(),
                ref.kind());
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

    /** 预览模型 → PreviewResult（T-M25-02 注解预览共用）：初始渲染 + 参数对照 + 错误合并。 */
    public PreviewResult toResult(XmlPreviewModel model) {
        return toResult(model, java.util.Collections.emptyList(), null);
    }

    public PreviewResult toResult(XmlPreviewModel model, List<String> extraWarnings) {
        return toResult(model, extraWarnings, null);
    }

    public PreviewResult toResult(XmlPreviewModel model, List<String> extraWarnings, SqlCompletionContext entityContext) {
        RenderedXmlSql rendered = model.renderSql();
        List<String> errors = new ArrayList<>(model.getErrors());
        errors.addAll(extraWarnings);
        errors.addAll(rendered.errors());

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
