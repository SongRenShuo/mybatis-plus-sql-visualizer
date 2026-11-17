package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.PagesColumn;
import cn.song.mybatisplus.visualizer.xml.PagesEntityProbe;
import cn.song.mybatisplus.visualizer.xml.PagesFrameworkPlaceholder;
import cn.song.mybatisplus.visualizer.xml.PagesModel;
import cn.song.mybatisplus.visualizer.xml.PagesSourceDetection;
import cn.song.mybatisplus.visualizer.xml.PagesSourceEvidence;
import cn.song.mybatisplus.visualizer.xml.PagesSourceLanguage;
import cn.song.mybatisplus.visualizer.xml.PagesSourceLanguageDetector;
import cn.song.mybatisplus.visualizer.xml.PagesStatement;
import cn.song.mybatisplus.visualizer.xml.PagesXmlParser;
import cn.song.mybatisplus.visualizer.xml.PagesXmlParseException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * maintainPage 预览服务（F-45）：XML 文本 → 语句 → 预览模型 → PreviewResult。
 *
 * <p>纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位 maintainPage id；
 * 弹窗与面板完全复用 SqlPreviewService.showResult 链路。</p>
 *
 * <ul>
 *   <li>{@code maintainPageId} 为 null 或未命中 → 取文件首个 maintainPage；</li>
 *   <li>解析失败（非 &lt;pages&gt; / 非良构）→ 返回带 warning 的空结果（面板红色提示，不抛异常）；</li>
 *   <li>参数对照表复用 {@link ParameterReplacer#buildParamTable}；</li>
 *   <li>{@code project} 非 null 时解析 entityClass → 补全上下文（表名/列名/注释）。</li>
 * </ul>
 *
 * <p>T-PagesHql（2026-11-05）：{@code <datasource>} 的内容既可能是原生 SQL，也可能是 HQL。
 * 判定见 {@code PagesSourceLanguageDetector}（{@code type} 显式 &gt; 根标识符与 entityClass 的关系 &gt; 根标识符
 * 可解析为实体；默认 SQL 保守）。命中 HQL 时复用 {@code SqlPreviewService.previewHbmNamedQuery} 的
 * 既有管道转换，不另造一条；转换失败/实体不可解析一律回退原文直显 + warning，绝不空白。</p>
 */
public final class PagesXmlPreviewService {

    public static final PagesXmlPreviewService INSTANCE = new PagesXmlPreviewService();

    private static final Logger LOG = Logger.getInstance(PagesXmlPreviewService.class);

    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    /** HQL 管道根实体不可解析时的产物指纹（与通用 XML 链同款）。 */
    private static final String UNRESOLVED_TABLE = "unknown_table";

    /** 降级清单压缩上限（面板 meta 单行渲染；完整信息在日志与 SQL 注释里）。 */
    private static final int MAX_WARNING_CHARS = 300;

    private PagesXmlPreviewService() {
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String maintainPageId) {
        return preview(fileText, maintainPageId, null);
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String maintainPageId, @Nullable Project project) {
        return preview(fileText, maintainPageId, project, null);
    }

    /**
     * T-PagesHql 全参形态。
     *
     * @param contextTag 光标/图标所在的 {@code <maintainPage>} 标签（可空）。传入后 HQL 转换会以该标签
     *                   作为 PSI 宿主锚点，按<b>该 XML 所属模块</b>收窄实体检索范围；null 时退化为全工程范围。
     */
    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String maintainPageId, @Nullable Project project,
                                 @Nullable XmlTag contextTag) {
        PagesModel pagesModel;
        try {
            pagesModel = new PagesXmlParser().parse(fileText);
        } catch (PagesXmlParseException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "XML 解析失败。");
        }

        PagesStatement statement = null;
        if (maintainPageId != null) {
            for (PagesStatement s : pagesModel.statements()) {
                if (maintainPageId.equals(s.id())) {
                    statement = s;
                    break;
                }
            }
        }
        if (statement == null) {
            for (PagesStatement s : pagesModel.statements()) {
                statement = s;
                break;
            }
        }
        if (statement == null) {
            return emptyResult("未找到可预览的 SQL 语句（<pages> 下无带 <datasource> 的页面语句）。");
        }

        PagesSourceDetection detection = detect(statement, project, contextTag);
        if (detection.language() == PagesSourceLanguage.HQL) {
            LOG.info("[T-PagesHql] page=" + statement.id() + " evidence=" + detection.evidence().id()
                    + " root=" + detection.rootIdentifier());
            return previewAsHql(statement, project, contextTag, detection);
        }
        return toResult(statement, project);
    }

    // ------------------------------------------------------------------
    // T-PagesHql：语言判定
    // ------------------------------------------------------------------

    private PagesSourceDetection detect(PagesStatement statement, @Nullable Project project,
                                        @Nullable XmlTag contextTag) {
        String root;
        try {
            root = HqlEntityNameExtractor.extract(statement.sql());
        } catch (Throwable ignored) {
            root = null;
        }
        String mode = sourceMode();
        if (PluginSettings.PAGES_SOURCE_SQL.equals(mode)) {
            return new PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.DEFAULT_SQL, root);
        }
        if (PluginSettings.PAGES_SOURCE_HQL.equals(mode)) {
            return new PagesSourceDetection(PagesSourceLanguage.HQL, PagesSourceEvidence.EXPLICIT_TYPE, root);
        }
        java.util.function.Function<String, PagesEntityProbe> probe = project == null
                ? name -> null
                : name -> resolveProbe(project, name, contextTag);
        // 跨层依赖（xml/ 波次）：PagesSourceLanguageDetector 为 master 顶层 object，推断落 static 承载；
        // declaredType 为 PagesXmlParser.kt 增量的 PagesStatement 新组件（同波次照译）
        return PagesSourceLanguageDetector.detect(
                statement.sql(),
                statement.entityClass() != null && !statement.entityClass().isBlank()
                        ? statement.entityClass() : null,
                statement.declaredType(),
                HqlEntityNameExtractor::extract,
                probe);
    }

    /** 设置读取兜底：测试/未注册 service 环境按 AUTO（跨层依赖 A3 波次 config/）。 */
    private static String sourceMode() {
        try {
            return PluginSettings.getInstance().getState().getPagesXmlSourceMode();
        } catch (Throwable ignored) {
            return PluginSettings.PAGES_SOURCE_AUTO;
        }
    }

    /** 实体探针：名字 → (实体名, 表名)；解析不到返回 null（与通用 XML 链共用同一实现）。 */
    @Nullable
    private static PagesEntityProbe resolveProbe(@NotNull Project project, @NotNull String name,
                                                 @Nullable PsiElement context) {
        return PagesEntityProbeResolver.probe(project, name, context);
    }

    // ------------------------------------------------------------------
    // T-PagesHql：HQL 转换（复用既有管道）
    // ------------------------------------------------------------------

    private PreviewResult previewAsHql(PagesStatement statement, @Nullable Project project,
                                       @Nullable XmlTag contextTag, PagesSourceDetection detection) {
        String raw = statement.sql();
        if (project == null) {
            // 纯文本环境（单测/无工程）：能判定但不能转换——保留原文，明确告知原因
            return withHqlMeta(toResult(statement, project), raw,
                    MpSqlVisualizerBundle.message("pages.hql.needIndex"));
        }
        // 跨层依赖（xml/ 波次）：PagesFrameworkPlaceholder.rewrite 为 #{...} 框架占位预处理
        // （#{x} → :x；非法标识符 → :__p_N，displayNames 保留原名）
        PagesFrameworkPlaceholder.PagesPlaceholderRewrite rewritten = PagesFrameworkPlaceholder.rewrite(raw);
        XmlTag anchor = contextTag != null && contextTag.findFirstSubTag("datasource") != null
                ? contextTag.findFirstSubTag("datasource") : contextTag;
        PreviewResult converted = null;
        try { // Kotlin runCatching.getOrNull() 语义（管道异常回退原文直显，不抛错）
            converted = project.getService(SqlPreviewService.class).previewHbmNamedQuery(
                    new HbmXmlMapping.NamedQuery("", HbmXmlMapping.QueryKind.HQL, rewritten.text()),
                    null, anchor, anchor);
        } catch (Throwable ignored) {
            // 转换管道异常 → 回退原文直显（不抛错）
        }

        if (converted == null || converted.getSql().contains(UNRESOLVED_TABLE)) {
            // 转换管道异常 / 根实体不可解析：回退原文直显（验收底线：至少能看到原文）
            return withHqlMeta(toResult(statement, project), raw,
                    MpSqlVisualizerBundle.message(converted == null ? "pages.hql.fallback" : "pages.hql.entityUnresolved"));
        }

        List<String> unresolved = new ArrayList<>();
        for (String p : converted.getUnresolvedParams()) {
            unresolved.add(rewritten.displayNames().getOrDefault(p, p));
        }
        List<String> placeholderNames = new ArrayList<>();
        for (String p : converted.getPlaceholderParamNames()) {
            placeholderNames.add(rewritten.displayNames().getOrDefault(p, p));
        }
        String warning = mergeWarnings(converted.getWarning(), converted.getDegradations());
        LOG.info("[T-PagesHql] converted page=" + statement.id() + " evidence=" + detection.evidence().id()
                + " params=" + unresolved.size() + " degradations=" + converted.getDegradations().size());
        List<PagesColumn> pagesColumns = statement.columns().isEmpty() ? null : statement.columns();
        return withHqlMeta(converted, raw, warning, unresolved,
                ParameterReplacer.buildParamTable(unresolved), placeholderNames, pagesColumns);
    }

    /** Kotlin 默认参等价形态：仅覆盖 sourceLanguage/originalSql/warning 三项（回退路径用）。 */
    @NotNull
    private static PreviewResult withHqlMeta(@NotNull PreviewResult base, @NotNull String originalSql,
                                             @Nullable String warning) {
        return withHqlMeta(base, originalSql, warning, base.getUnresolvedParams(), base.getParamTable(),
                base.getPlaceholderParamNames(), base.getPagesColumns());
    }

    /**
     * {@code copy(sourceLanguage=, originalSql=, warning= [, unresolvedParams=, paramTable=,
     * placeholderParamNames=, pagesColumns=])} 的 Java 承载（跨层依赖 model/ 波次的 PreviewResult
     * 尾参增量——sourceLanguage/originalSql 按 master 尾参位照译，单点收口便于该波次形态落位后对齐）。
     */
    @NotNull
    private static PreviewResult withHqlMeta(@NotNull PreviewResult base, @NotNull String originalSql,
                                             @Nullable String warning,
                                             @NotNull List<String> unresolvedParams, @Nullable String paramTable,
                                             @NotNull List<String> placeholderParamNames,
                                             @Nullable List<PagesColumn> pagesColumns) {
        return new PreviewResult(
                base.getSql(), base.getCompactSql(), unresolvedParams, paramTable,
                base.getWrapperType(), base.getTableName(), warning, base.getColumns(),
                base.getColumnComments(), base.getTableComment(), base.getPreviewModel(),
                base.getParamHints(), base.getCandidateTables(), base.getBatchModel(),
                base.getXmlModel(), base.getInitialParamValues(), base.getPerfWarnings(),
                base.getFillClauses(), base.getPreviousSql(), pagesColumns,
                base.getNamedParamHints(), base.getStackDigest(), base.getThreadName(),
                base.isHasDollarRawSegments(), base.getCountSql(), base.getPaginationLimitLine(),
                placeholderParamNames, base.getDuplicateParamNames(),
                base.getDiagramStatement(), base.getHistoryFingerprintSeed(),
                base.getPaginationParamSources(), base.getRenderedDialect(), base.getDialectDecision(),
                base.getHistoryEntryId(), base.getActualSql(),
                cn.song.mybatisplus.visualizer.model.SourceLanguage.HQL, originalSql);
    }

    /** 降级清单压成一行（面板 meta 单行渲染；超长截断，完整信息在日志与 SQL 注释里）。 */
    @Nullable
    private static String mergeWarnings(@Nullable String base, List<String> degradations) {
        List<String> parts = new ArrayList<>();
        if (base != null && !base.isBlank()) parts.add(base);
        for (String d : degradations) {
            if (d != null && !d.isBlank() && !parts.contains(d)) parts.add(d);
        }
        String joined = String.join("；", parts);
        if (joined.length() > MAX_WARNING_CHARS) {
            return joined.substring(0, MAX_WARNING_CHARS) + "…";
        }
        return joined.isEmpty() ? null : joined;
    }

    // ------------------------------------------------------------------
    // 原生 SQL 路径（既有行为，零变化）
    // ------------------------------------------------------------------

    /** PagesStatement → PreviewResult（原生 SQL 路径）。 */
    private PreviewResult toResult(@NotNull PagesStatement statement, @Nullable Project project) {
        String sql = statement.sql();
        List<String> params = extractPlaceholders(sql);
        SqlCompletionContext entityContext = resolveEntityContext(statement.entityClass(), project);
        return new PreviewResult(
                sql,
                WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim(),
                params,
                ParameterReplacer.buildParamTable(params),
                null,
                entityContext != null ? entityContext.tableName : null,
                null,
                entityContext != null ? entityContext.columns : List.of(),
                entityContext != null ? entityContext.columnComments : Map.of(),
                entityContext != null ? entityContext.tableComment : null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                statement.columns().isEmpty() ? null : statement.columns()
        );
    }

    /** 实体类推断：全限定名 → PSI findClass → EntityResolver → SqlCompletionContext。 */
    @Nullable
    private SqlCompletionContext resolveEntityContext(@Nullable String entityClass, @Nullable Project project) {
        if (entityClass == null || entityClass.isBlank() || project == null) return null;
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(entityClass, GlobalSearchScope.allScope(project));
        if (psiClass == null) return null;
        TableInfo tableInfo = new EntityResolver().resolve(psiClass);
        Map<String, String> comments = new HashMap<>();
        for (ColumnInfo col : tableInfo.getColumns()) {
            String c = col.getComment();
            if (c != null && !c.isBlank()) {
                comments.put(col.getColumnName(), c);
            }
        }
        return new SqlCompletionContext(
                tableInfo.getTableName(),
                tableInfo.getAllColumnNames(),
                null,
                comments,
                tableInfo.getComment()
        );
    }

    /**
     * 提取 {@code ?} 占位符 → param1..N。
     *
     * <p>T-PagesHql D-6：改用 string-aware 位点（{@code placeholderPositions}），与服务端面板回填
     * （{@code PlaceholderPositions} 同一份口径）对齐——旧实现 {@code sql.count { it == '?' }} 会把字符串
     * 字面量与注释里的 {@code ?} 也算成参数，导致参数行条数与可填位点错配。</p>
     */
    private List<String> extractPlaceholders(@NotNull String sql) {
        int count = PlaceholderPositionsKt.placeholderPositions(sql).size();
        if (count == 0) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) result.add("param" + i);
        return result;
    }

    private PreviewResult emptyResult(@NotNull String message) {
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
