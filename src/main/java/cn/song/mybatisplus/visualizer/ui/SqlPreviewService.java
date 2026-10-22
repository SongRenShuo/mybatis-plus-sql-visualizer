// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt 移植（T-ST-02b2：历史耗时补全读路径改 sqlLogManagerIfCreated）
// 自 master 同文件 O-145/R54/T-PerfSweep24/T-UpdateSetSqlConcat/T-DialectQuoteWarn 增量移植（来源上下文 + 方言决策 + 历史条目回填两级化）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings;
import cn.song.mybatisplus.visualizer.config.TableNameRule;
import cn.song.mybatisplus.visualizer.context.ActualSqlPsiResolver;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext.ModuleIdentityResolution;
import cn.song.mybatisplus.visualizer.context.ModuleIdentityResolver;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext.PreviewSourceOrigin;
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext.SqlPsiResolution;
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry;
import cn.song.mybatisplus.visualizer.converter.EasyQueryConverter;
import cn.song.mybatisplus.visualizer.converter.MdsConverter;
import cn.song.mybatisplus.visualizer.converter.QuerydslConverter;
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser;
import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter;
import cn.song.mybatisplus.visualizer.sql.GeneratedSql;
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer;
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser;
import cn.song.mybatisplus.visualizer.converter.jpa.PartTree;
import cn.song.mybatisplus.visualizer.converter.jpa.QueryByExampleConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.SpecificationQueryConverter;
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry;
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.log.SqlLogManagerLookupKt;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.ConditionToggle;
import cn.song.mybatisplus.visualizer.model.ConditionView;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.model.BatchPreview;
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter;
import cn.song.mybatisplus.visualizer.psi.WrapperCache;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.psi.LocatedWrapper;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.DaoQueryKind;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlParameter;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer;
import cn.song.mybatisplus.visualizer.sql.interceptor.DataPermissionPlaceholder;
import cn.song.mybatisplus.visualizer.sql.interceptor.DynamicTableNameProcessor;
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator;
import cn.song.mybatisplus.visualizer.sql.interceptor.TenantLineEnhancer;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.TableConfigSignature;
import cn.song.mybatisplus.visualizer.xml.XmlDirectText;
import cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 预览服务（M3-01 整合层，F-07 统一入口）。
 *
 * <p>职责：把编辑器中的任意 PSI 元素转换为可展示的 {@link PreviewResult}。
 * 集成链路：WrapperLocator 定位 → ConverterRegistry 分发（SPI）→ SqlGenerator 生成 →
 * ParameterReplacer 参数对照 → WrapperCache 缓存（F-20）。
 *
 * <p>M5 起读取 {@link PluginSettings} 配置：方言（分页子句）、格式化开关（多行/压缩）。
 * 轻服务（M16）：{@code @Service(Service.Level.PROJECT)} 注解声明，无需 plugin.xml
 * {@code <projectService>} 注册（IDE 自动发现；构造函数仅依赖 Project，满足轻服务条件），
 * 获取方式不变（project.getService）。
 */
@Service(Service.Level.PROJECT)
public final class SqlPreviewService {

    /** 文件级解析缓存（F-20）：文件内容变更（modificationStamp）即失效。 */
    private final WrapperCache cache = new WrapperCache();

    /**
     * T-ErrorLocate T-EL-03：最近一次预览的源锚点（文件 + 0 基光标行），错误定位动作消费。
     * 预览触发入口（ConvertToSqlAction）刷新；null = 本会话尚未有可解析锚点（定位按钮不出现）。
     * 单槽即可：预览弹窗每项目至多一个（M36.5.47 单例化），同刻只有一个错误面板可定位。
     */
    @Nullable
    public SourceAnchor lastPreviewSourceAnchor = null;

    /** 预览源锚点（文件 + 0 基行）。 */
    public record SourceAnchor(
            /** 预览源文件。 */
            @NotNull com.intellij.openapi.vfs.VirtualFile file,
            /** 0 基光标行。 */
            int line0) {
    }

    /**
     * Kotlin {@code var lastPreviewSourceAnchor} 的 setter 形态——Java 调用方（ConvertToSqlAction）
     * 按属性赋值语义调用本方法；字段本身保持 public 供 {@link SqlPreviewPanel} 直读（同一落点）。
     */
    public void setLastPreviewSourceAnchor(@Nullable SourceAnchor anchor) {
        this.lastPreviewSourceAnchor = anchor;
    }

    /** 当前项目（主要用于获取项目级设置）。 */
    public final Project project;

    /**
     * 全局配置（测试环境未注册 service 时为 null，使用默认值）。
     */
    public final PluginSettings settings = safeGet(PluginSettings::getInstance);

    /**
     * O-5：项目级配置（project-level 轻服务；未启用时回退全局）。
     */
    private final ProjectPluginSettings projectSettings;

    public SqlPreviewService(@NotNull Project project) {
        this.project = project;
        this.projectSettings = safeGet(() -> ProjectPluginSettings.getInstance(project));
        // T-PerfTail #1：编辑器关闭即清理该文件的 WrapperCache 条目——长会话中条目数随打开
        // 文件集有界（LRU 512 上限之外的主动回收）。连接绑定 project Disposable，项目关闭自动注销。
        // FileEditorManagerListener 的 fileClosed 携带 VirtualFile，与 WrapperCache 键的
        // VirtualFile.url 字符串维度（T-Perf-18）直接对齐。
        project.getMessageBus().connect(project).subscribe(
                com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new com.intellij.openapi.fileEditor.FileEditorManagerListener() {
                    @Override
                    public void fileClosed(@NotNull com.intellij.openapi.fileEditor.FileEditorManager source,
                                           @NotNull com.intellij.openapi.vfs.VirtualFile file) {
                        cache.evictFile(file.getUrl());
                    }
                });
        // T-K2JWalk1 KW-1：弹窗工程关闭收口**不在本服务注册**——gutter 的 XML 链在 getService
        // 之前即 return，本服务可能整个会话都不实例化，这里挂收口等于没挂（真机复测已证）。
        // 收口改挂在 SqlPreviewDialog 构造器（弹窗自挂 project Disposer 树，创建即必在）。
    }

    /**
     * T-M19.4（用户实测：表名配置修改后预览不生效——缓存按文件戳失效，设置变更不触发）：
     * 影响解析结果的表相关设置签名，作为 {@link WrapperCache} 缓存维度。
     * 复用 {@link TableConfigSignature#projectSignature()} 统一来源，
     * 与 EntityResolver 的解析缓存防漂移。
     *
     * <p>O-5：项目级设置开启时追加项目级表配置签名——项目设置变更后缓存同样失效。
     */
    private String tableConfigSignature() {
        String base = TableConfigSignature.projectSignature();
        ProjectPluginSettings ps = projectSettings;
        if (ps == null || !ps.isEnabled()) return base;
        ProjectPluginSettings.State s = ps.getState();
        List<String> parts = new ArrayList<>();
        parts.add(blank(s.getTableNameInference()));
        parts.add(blank(s.getIgnoredFields()));
        parts.add(blank(s.getTablePrefix()));
        parts.add(blank(s.getTableSuffix()));
        String projectPart = String.join("|", parts);
        if (projectPart.isBlank() || projectPart.equals("|||")) return base;
        return base + "|proj:" + projectPart;
    }

    /**
     * O-5：获取有效的设置值（项目级优先，关闭/空值回退全局）。
     */
    private boolean injectLogicDelete() {
        return (settings != null && settings.getState() != null) ? settings.getState().getInjectLogicDelete() : true;
    }

    private boolean injectTenantLine() {
        return (settings != null && settings.getState() != null) ? settings.getState().getInjectTenantLine() : false;
    }

    /**
     * O-5：是否格式化输出（项目级优先，回退全局）。
     */
    public boolean formatSql() {
        boolean global = (settings != null && settings.getState() != null) ? settings.getState().getFormatSql() : true;
        if (projectSettings != null && projectSettings.isEnabled()) {
            return projectSettings.effectiveFormatSql();
        }
        return global;
    }

    // T-SettingsTruth P4：以下 7 项直读点全部改走项目级 effective——
    // useProjectSettings 开启时项目级覆盖全局，关闭/null/空回退全局（回退语义在
    // ProjectPluginSettings.effectiveXxx 内置，此处仅透传；effectiveString 由零调用转正）。

    /** 有效参数替换开关（项目级优先，回退全局）。 */
    private boolean effectiveReplaceParams() {
        if (projectSettings != null && projectSettings.isEnabled()) return projectSettings.effectiveReplaceParams();
        return (settings != null && settings.getState() != null) ? settings.getState().getReplaceParams() : true;
    }

    /** 有效逻辑删除注入开关（项目级优先，回退全局）。 */
    private boolean effectiveInjectLogicDelete() {
        if (projectSettings != null && projectSettings.isEnabled()) return projectSettings.effectiveInjectLogicDelete();
        return (settings != null && settings.getState() != null) ? settings.getState().getInjectLogicDelete() : true;
    }

    /** 有效逻辑删除值（项目级优先，空回退全局）。 */
    @NotNull
    public String effectiveLogicDeleteValue() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getLogicDeleteValue() : "",
                projectSettings != null ? projectSettings.effectiveLogicDeleteValue() : null);
    }

    /** 有效表前缀（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveTablePrefix() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getTablePrefix() : "",
                projectSettings != null ? projectSettings.effectiveTablePrefix() : null);
    }

    /** 有效表后缀（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveTableSuffix() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getTableSuffix() : "",
                projectSettings != null ? projectSettings.effectiveTableSuffix() : null);
    }

    /** 有效表名推断策略（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveTableNameInference() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getTableNameInference() : PluginSettings.INFERENCE_SNAKE,
                projectSettings != null ? projectSettings.effectiveTableNameInference() : null);
    }

    /** 有效忽略字段名（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveIgnoredFields() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getIgnoredFields() : "",
                projectSettings != null ? projectSettings.effectiveIgnoredFields() : null);
    }

    /** O-5：获取有效的字符串设置值（项目级优先，空值回退全局）。 */
    @NotNull
    private String effectiveString(@NotNull String global, @Nullable String project) {
        return (projectSettings != null && projectSettings.isEnabled() && project != null && !project.isBlank())
                ? project : global;
    }

    /**
     * F-1 修复：Repository <b>接口方法声明行</b>预览（无调用表达式，{@link #preview} 的批量/JPA 管线永不认领）。
     *
     * <p>缺口形态（2026-10-12 演示录制中发现，诊断报告 {@code docs/调研报告/20261012-0900-*}）：
     * {@code deleteAllInBatch()} 这类 bulk 形态既不在派生查询线（不含 {@code By}、{@code *All} 词干
     * 白名单不放行 {@code InBatch}），也没有调用表达式给 CRUD 转换线——声明行光标下零可达，
     * 而模板在转换层完整存在。</p>
     *
     * <p>语义与调用点 CRUD 线一致（同一 {@code buildRepositoryMethod} 模板），差别只有：声明行无实参可静态
     * 求值，一律 {@code ?} 占位。非 Repository 接口 / 方法名不在名单 / 实体不可解析 → null（交回回落链）。</p>
     */
    @Nullable
    public PreviewResult previewRepositoryDeclaration(@NotNull PsiMethod method) {
        JpaCrudOperationConverter.JpaCrudPreview jpaCrud =
                JpaCrudOperationConverter.convertRepositoryDeclaration(method, effectiveReplaceParams());
        if (jpaCrud == null) return null;
        PreviewResult result = new PreviewResult(
                jpaCrud.getSql(),
                jpaCrud.getSql(),
                jpaCrud.getParamSources(),
                jpaCrud.getParamGroups(),
                null,
                jpaCrud.getTableName(),
                jpaCrud.getWarning(),
                null,
                java.util.Map.of(),
                null,
                null,
                jpaCrud.getParamHints(),
                java.util.List.of(),
                jpaCrud.getBatchModel(),
                null,
                java.util.Map.of(),
                null,
                null,
                null,
                null);
        recordHistory(result, method);
        return result;
    }

    /**
     * 预览锚点元素处的 Wrapper 转换结果。
     *
     * <p>识别顺序：
     * <ol>
     *   <li>Wrapper 调用链（SELECT/UPDATE/DELETE/MPJ）</li>
     *   <li>批量操作调用（F-15，{@code service.updateBatchById(list)} 等）</li>
     * </ol>
     *
     * @param anchor 光标处 / 悬停处的 PSI 元素
     * @return 预览结果；两者均未命中时返回 null
     */
    @Nullable
    public PreviewResult preview(@NotNull PsiElement anchor) {
        return preview(anchor, null);
    }

    /** Wrapper 的 owner 是调用所在源码文件，不是实体类文件，也不是把 Java/Kotlin 当成 SQL PSI。 */
    @NotNull
    public PreviewSourceContext resolveWrapperSourceContext(@NotNull PsiElement anchor) {
        PsiFile file = anchor.getContainingFile();
        com.intellij.psi.PsiElement owner = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class, false);
        if (owner == null) owner = anchor;
        SqlPsiResolution sqlResolution = file == null
                ? SqlPsiResolution.None.INSTANCE
                : ActualSqlPsiResolver.resolve(file, owner, anchor.getTextOffset());
        ModuleIdentityResolution moduleResolution =
                file != null && file.getVirtualFile() != null
                        ? ModuleIdentityResolver.resolve(project, file.getVirtualFile())
                        : null;
        if (moduleResolution == null) {
            moduleResolution = ModuleIdentityResolution.None.INSTANCE;
        }
        return new PreviewSourceContext(project, PreviewSourceOrigin.PSI, file,
                file != null ? file.getVirtualFile() : null,
                sqlResolution, moduleResolution, null, null);
    }

    @Nullable
    public PreviewResult preview(@NotNull PsiElement anchor, @Nullable PreviewSourceContext sourceContext) {
        // JDBC 模板 SQL 字符串预览（正向）：queryForList/update 等方法的字符串 SQL 实参 → 预览
        // 放在最前：JDBC 分支不写 Wrapper 缓存、不依赖实体解析，与既有路径完全隔离
        boolean jdbcPreviewOff = settings != null && settings.getState() != null
                && !settings.getState().getJdbcSqlPreviewEnabled();
        if (!jdbcPreviewOff) {
            JdbcTemplateSqlLocator.JdbcTemplateSqlCall jdbcCall = JdbcTemplateSqlLocator.INSTANCE.locate(anchor);
            if (jdbcCall != null) {
                return JdbcTemplateSqlPreviewService.preview(jdbcCall, anchor);
            }
        }

        PsiFile file = anchor.getContainingFile();
        if (file == null) return null;
        PreviewSourceContext context = sourceContext != null
                && project.equals(sourceContext.project())
                && file.equals(sourceContext.hostPsiFile())
                ? sourceContext
                : resolveWrapperSourceContext(anchor);
        // 宿主文件提供 IDEA 有效方言映射；SQL PSI 参数只在确有真实 SqlFile 时提供注入作用域。
        DialectDecisionEngine.Decision decision =
                (context.sqlPsiResolution() instanceof SqlPsiResolution.Ambiguous
                        || context.moduleResolution() instanceof ModuleIdentityResolution.Ambiguous)
                        ? new DialectDecisionEngine.Decision(
                        DialectDecisionEngine.Status.AMBIGUOUS, null,
                        DialectDecisionEngine.Confidence.FALLBACK, null, List.of(), List.of(), null)
                        : DialectRegistry.INSTANCE.decision(project, context.hostVirtualFile(),
                        context.sqlPsiFile(), context.canonicalModuleKey());
        Dialect dialect = DialectRegistry.INSTANCE.byName(decision.dialectName());
        // 缓存键含锚点偏移：同文件多个 Wrapper（查询/删除/修改）各自独立缓存，避免串结果
        // M14.6：缓存键含「替换 ? 占位符」开关，开关切换后不命中旧结果
        // T-M19.4（用户实测：表名配置修改后预览不生效）：缓存键含**表配置签名**——设置变更
        // （文件戳不变的场景）不再命中旧结果
        boolean resolveVariables = effectiveReplaceParams();
        LocatedWrapper located = cache.getOrCompute(
                file, anchor.getTextRange().getStartOffset(), resolveVariables,
                tableConfigSignature() + "|" + decision.status() + "|" + decision.dialectName()
                        + "|" + decision.source() + "|" + decision.dataSourceIds(),
                () -> WrapperLocator.locateWithTable(anchor, resolveVariables));
        // T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按**锚点文件**戳失效，
        // 实体文件变化不触发）：命中缓存时校验实体文件戳，变更则重算
        LocatedWrapper effectiveLocated = (located != null && !located.entityFileValid())
                ? WrapperLocator.locateWithTable(anchor, resolveVariables) : located;

        // 1. Wrapper 转换（优先）
        if (effectiveLocated != null) {
            TableInfo table = effectiveLocated.getTable();
            if (table == null) {
                // O-8（缺陷 B）：字符串服务链实体解析失败（三通道与方法引用推断全失败）→
                // 返回带显式警示的空结果（面板红字），不再静默 return null——
                // 「返回 null = 非 wrapper 锚点」契约收窄保留：仅 located 带 entityResolutionFailed
                // 标记（服务链形态）时降级，工厂/Kotlin/构造形态与其余锚点行为不变
                if (effectiveLocated.entityResolutionFailed() || "kotlin".equalsIgnoreCase(file.getLanguage().getID())) {
                    LOG.info("preview: DEGRADE table=null, wrapperType="
                            + (effectiveLocated.getChain() != null ? effectiveLocated.getChain().getWrapperType() : null)
                            + ", anchor=" + anchor.getTextRange());
                    String degradeWarning = effectiveLocated.entityResolutionFailed()
                            ? "⚠ 字符串服务链无法推断实体表：Wrapper 链未携带实体类型"
                            + "（无 <实体> 类型实参，服务接口/调用返回类型也无实体泛型），SQL 无法生成"
                            : "Kotlin 源码无法推断实体表；SQL 无法生成";
                    return new PreviewResult(
                            "",
                            "",
                            List.of(),
                            null,
                            effectiveLocated.getChain() != null ? effectiveLocated.getChain().getWrapperType() : null,
                            null,
                            degradeWarning,
                            List.of(), Map.of(), null, null, List.of(), List.of(), null, null, Map.of(),
                            List.of(), List.of(), null, null,
                            Map.of(), null, false, null, null,
                            List.of(), java.util.Set.of(), null, null,
                            null, dialect, decision, null, null);
                }
                LOG.info("preview: RETURN_NULL table=null, wrapperType="
                        + (effectiveLocated.getChain() != null ? effectiveLocated.getChain().getWrapperType() : null)
                        + ", anchor=" + anchor.getTextRange());
                return null;
            }
            WrapperChain chain = effectiveLocated.getChain();
            SqlStatement statement;
            if (chain.getCountConsumed() && isQueryLike(chain.getWrapperType())) {
                // 计数消费：service.count(wrapper) / selectJoinCount(wrapper) / lambdaQuery()...count() → SELECT COUNT(*) FROM ...
                SelectStatement base = parseQueryLike(chain, table);
                statement = new SelectStatement(
                        table,
                        List.of(new ColumnRef(null, "*", null, "COUNT", null, null, null)),
                        base.getTableAlias(), base.getJoins(), false, base.getConditions(),
                        List.of(), null, List.of(), null, null, null, null, List.of(), null, null);
            } else if (chain.getExistsConsumed() && isQueryLike(chain.getWrapperType())) {
                // exists 链式终结点消费：service.lambdaQuery()...exists() → SELECT EXISTS (子查询)
                statement = parseQueryLike(chain, table);
            } else if (chain.getUpdateEntityConsumed() && !UPDATE_DELETE_JOIN_TYPES.contains(chain.getWrapperType())) {
                // 实体更新消费：service.update(entity, wrapper) 双参——wrapper 无 set 时用实体字段展开 SET 列
                SelectStatement parsed = new QueryWrapperParser(table).parse(chain);
                List<SetClause> sets = new ArrayList<>();
                for (ColumnInfo col : table.getColumns()) {
                    if (col.isId()) continue;
                    // @Version 列排除——实体 SET 不含 version（对齐 MP OptimisticLockerInnerInterceptor）
                    if (col.getColumnName().equals(table.getVersionColumn())) continue;
                    sets.add(new SetClause(
                            new ColumnRef(null, col.getColumnName(), null, null, null, null, null),
                            new SqlValue.Placeholder("?"), null));
                }
                statement = new UpdateStatement(table, sets, parsed.getConditions(), null, null, List.of());
            } else if (chain.getDeleteConsumed()
                    && (isQueryLike(chain.getWrapperType()) || chain.getWrapperType() == WrapperType.LAMBDA_UPDATE)
                    && !UPDATE_DELETE_JOIN_TYPES.contains(chain.getWrapperType())) {
                // MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造，service.remove/delete 消费
                SelectStatement parsed = new QueryWrapperParser(table).parse(chain);
                // O-8（缺陷 C）：`last("LIMIT n")` 尾段透传 DELETE 渲染（此前落在 SelectStatement
                // 独有字段被静默丢弃；跨方言渲染与否由 SqlGenerator 按 Dialect.supportsDeleteLimit 决定）
                statement = new DeleteStatement(
                        table, parsed.getConditions(), List.of(), null, List.of(), parsed.getLimit());
            } else {
                // T-FS-04：显式传本服务绑定的 project——converter 内「关联/collection 子表实体类 PSI
                // 不可解析」的类名兜底推断据此读项目级表配置（不再受「首个打开项目」影响）
                SqlStatement converted = ConverterRegistry.INSTANCE.convert(chain, table, project);
                if (converted == null) return null;
                statement = converted;
            }

            // 缺陷②修复：参数历史键指纹取「注入前」语句文本——下面这一串注入（逻辑删除 / 乐观锁 /
            // 多租户 / 数据权限 / 分页 LIMIT）都会改写 SQL 文本，而历史键原先是 SHA-256(SQL 全文)，
            // 用户一开这些开关键就变 → 已回填参数召回 miss 退回占位符（且防抖后新键以空值覆盖落库）。
            String historyFingerprintSeed = SqlGenerator.generate(statement, true, dialect, null);

            // T-M15-07：逻辑删除注入（@TableLogic 实体 + 配置开启）
            LogicDeleteState logicDelete = evaluateLogicDeleteState(chain.getCalls());
            SqlStatement baseStatement = effectiveInjectLogicDelete() ? SqlPreviewServiceEnhancementsKt.injectLogicDeleteCondition(this, table, statement, logicDelete) : statement;
            // T-M22-02：乐观锁注入（@Version 实体 UPDATE）
            SqlStatement finalStatement = injectOptimisticLockCondition(table, baseStatement);
            // T-TenantLine：多租户注入
            boolean hasInterceptorIgnore = hasInterceptorIgnoreAnnotation(anchor, "tenantLine");
            SqlStatement tenantWhereStatement = SqlPreviewServiceEnhancementsKt.injectTenantLineCondition(this, table, finalStatement, hasInterceptorIgnore);
            // F-6 Phase A：UPDATE 追加租户列 SET
            SqlStatement tenantStatement;
            if (injectTenantLine() && tenantWhereStatement instanceof UpdateStatement) {
                String tenantColumn = blank(settings != null && settings.getState() != null
                        ? settings.getState().getTenantColumnName() : null);
                tenantStatement = TenantLineEnhancer.enhanceUpdateSet(
                        (UpdateStatement) tenantWhereStatement, tenantColumn,
                        blank(settings != null && settings.getState() != null
                                ? settings.getState().getTenantColumnDefault() : null));
            } else {
                tenantStatement = tenantWhereStatement;
            }
            // F-6 Phase B：动态表名替换
            SqlStatement tableNameStatement = processDynamicTableName(table, tenantStatement);
            // F-6 Phase C：数据权限占位提示
            SqlStatement dataPermissionStatement = injectDataPermissionCondition(table, tableNameStatement);
            // T-M29-02/03：分页消费（service.page / selectPage / 链式 page()）→ LIMIT 子句
            SqlStatement pagedStatement = (chain.getCountConsumed() || chain.getExistsConsumed())
                    ? dataPermissionStatement : applyPageLimit(dataPermissionStatement, chain.getPageLimit());
            // R12 T-PaginationSim：分页 COUNT 包裹预览（D1=A 预览附第二条语句；D2=A 基础包裹形态；
            // D3=A searchCount=false 跳过；count/exists 消费不产 COUNT——复用 T-M29-02/03 分页消费口径）
            String countSql = paginationCountSql(chain, pagedStatement, dialect);
            PreviewResult result = SqlPreviewServiceEnhancementsKt.buildPreviewResult(
                    this, chain.getWrapperType(), table, pagedStatement, countSql, historyFingerprintSeed,
                    dialect, decision);
            recordHistory(result, anchor);
            // exists 消费：外层包裹 SELECT EXISTS
            if (chain.getExistsConsumed() && isQueryLike(chain.getWrapperType())) {
                return new PreviewResult(
                        "SELECT EXISTS (" + result.getSql() + ")",
                        "SELECT EXISTS (" + result.getCompactSql() + ")",
                        result.getUnresolvedParams(), result.getParamTable(), result.getWrapperType(), result.getTableName(),
                        result.getWarning(), result.getColumns(), result.getColumnComments(), result.getTableComment(),
                        result.getPreviewModel(), result.getParamHints(), result.getCandidateTables(), result.getBatchModel(),
                        result.getXmlModel(), result.getInitialParamValues(), result.getPerfWarnings(), result.getFillClauses(),
                        result.getPreviousSql(), result.getPagesColumns());
            }
            return result;
        }

        // 2. 批量操作识别（anchor 或其父链上的方法调用）
        PsiMethodCallExpression batchCall = null;
        PsiElement cur = anchor;
        while (cur != null) {
            if (cur instanceof PsiMethodCallExpression) { batchCall = (PsiMethodCallExpression) cur; break; }
            cur = cur.getParent();
        }
        // T-J1/T-J6/T-J21：JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作派发
        // （receiver 家族感知，消歧规则见 JpaCrudOperationConverter KDoc）——receiver 判定为
        // JPA 家族时必须走 JpaEntityResolver：MP EntityResolver 只认 @TableName，JPA 实体
        // （@Table(name="t_user")）会被输出成 `INSERT INTO user_entity` 错表错列；
        // 非 JPA 家族 convert 返回 null，维持下方 MP 批量路径现状（存量行为零变化）
        // T-J5/T-J20 前置分支：Specification / Query By Example executor 调用先于 JPA CRUD 家族判定——
        // `findAll(spec)` / `findAll(example)` 的方法名同在 JpaCrudOperationConverter.REPOSITORY_METHODS
        // 名单内，后置会被渲染成「无 WHERE 全表 SELECT」（JpaCrudOperationConverter KDoc 建议的扩面方式：
        // 在 SqlPreviewService 侧新增前置分支，不改 Wave2 交付文件）
        if (batchCall != null) {
            SpecificationQueryConverter.SpecificationPreview specPreview =
                    SpecificationQueryConverter.convert(batchCall, resolveVariables);
            if (specPreview != null) {
                DialectDecisionEngine.Decision specDecision = jpaSourceDecision(batchCall);
                PreviewResult result = new PreviewResult(
                        specPreview.getSql(),
                        SQL_WHITESPACE_RUN.matcher(specPreview.getSql()).replaceAll(" ").trim(),
                        specPreview.getParamSources(),
                        ParameterReplacer.buildParamTable(specPreview.getParamSources()),
                        null,
                        specPreview.getTableName(),
                        specPreview.getWarning(),
                        specPreview.getColumns(),
                        Map.of(),
                        null,
                        null,
                        specPreview.getParamHints(),
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        false,
                        null,
                        null,
                        List.of(),
                        java.util.Set.of(),
                        null,
                        null,
                        null,
                        dialectOf(specDecision),
                        specDecision,
                        null,
                        null);
                recordHistory(result, anchor);
                return result;
            }
            QueryByExampleConverter.ExamplePreview examplePreview =
                    QueryByExampleConverter.convert(batchCall, resolveVariables);
            if (examplePreview != null) {
                DialectDecisionEngine.Decision exampleDecision = jpaSourceDecision(batchCall);
                PreviewResult result = new PreviewResult(
                        examplePreview.getSql(),
                        SQL_WHITESPACE_RUN.matcher(examplePreview.getSql()).replaceAll(" ").trim(),
                        examplePreview.getParamSources(),
                        ParameterReplacer.buildParamTable(examplePreview.getParamSources()),
                        null,
                        examplePreview.getTableName(),
                        null,
                        examplePreview.getColumns(),
                        Map.of(),
                        null,
                        null,
                        examplePreview.getParamHints(),
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        false,
                        null,
                        null,
                        List.of(),
                        java.util.Set.of(),
                        null,
                        null,
                        null,
                        dialectOf(exampleDecision),
                        exampleDecision,
                        null,
                        null);
                recordHistory(result, anchor);
                return result;
            }
            // EQ/DS/QD（M44 其他 ORM 第一批）前置派发：Easy-Query proxy 链 / MyBatis Dynamic SQL /
            // Querydsl——三者均非 MP Wrapper 链（locateWithTable 不命中），按 entry+receiver 启发认领；
            // 不认领返回 null 走后续 JPA CRUD / MP 批量管道（存量行为零变化）
            PreviewResult ormPreview = SqlPreviewServiceEnhancementsKt.convertOtherOrm(this, batchCall, resolveVariables);
            if (ormPreview != null) {
                recordHistory(ormPreview, anchor);
                return ormPreview;
            }
        }
        JpaCrudOperationConverter.JpaCrudPreview jpaCrud = batchCall != null
                ? JpaCrudOperationConverter.convert(batchCall, resolveVariables)
                : null;
        if (jpaCrud != null) {
            PreviewResult result = new PreviewResult(
                    jpaCrud.getSql(),
                    jpaCrud.getSql(),
                    jpaCrud.getParamSources(),
                    jpaCrud.getParamGroups(),
                    null,
                    jpaCrud.getTableName(),
                    jpaCrud.getWarning(),
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    jpaCrud.getParamHints(),
                    List.of(),
                    jpaCrud.getBatchModel(),
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
            recordHistory(result, anchor);
            return result;
        }
        // T-M23-01/02：批量路径接线——逻辑删除注入开关（settings.injectLogicDelete）与变量求值开关
        // （settings.replaceParams，实参可静态求值时内联）对齐 Wrapper 路径；参数来源/回填提示透传面板
        BatchPreview batch = batchCall != null
                ? BatchOperationConverter.convert(
                batchCall,
                effectiveInjectLogicDelete(),
                effectiveReplaceParams(),
                // D2：INSERT 租户列追加 = 主开关 AND 子开关（injectTenantOnInsert）；UPDATE 的 SET 租户注入
                // 走主开关（见上方 enhanceUpdateSet 链），不受本参数影响
                injectTenantLine() && ((settings != null && settings.getState() != null) ? settings.getState().getInjectTenantOnInsert() : true))
                : null;
        if (batch == null) return null;
        PreviewResult result = new PreviewResult(
                batch.getSql(),
                batch.getSql(),
                batch.getParamSources(),
                batch.getParamGroups(),
                null,
                batch.getTableName(),
                null,
                List.of(),
                Map.of(),
                null,
                null,
                batch.getParamHints(),
                List.of(),
                batch.getBatchModel(),
                null,
                Map.of(),
                null,
                null,
                null,
                null);
        recordHistory(result, anchor);
        return result;
    }

    /**
     * JPA 推导查询预览（F-14 阶段四）：解析 Repository 方法名 → PartTree → SqlStatement → PreviewResult。
     *
     * <p>供 cn.song.mybatisplus.visualizer.ui.gutter.JpaGutterLineMarkerProvider 点击调用与
     * T-J9 调用点反查（ConvertToSqlAction）调用。
     *
     * @param method   Repository 声明方法
     * @param callArgs T-J9 调用点实参（{@code userRepository.findByName(x, PageRequest.of(0, 20))} 的实参列表）；
     *                 非 null 时对 PageRequest.of / Sort.by 做静态求值（有字面量就内联渲染，无则 ? 占位）
     */
    @Nullable
    public PreviewResult previewDerivedQuery(@NotNull PsiMethod method) {
        return previewDerivedQuery(method, null);
    }

    @Nullable
    public PreviewResult previewDerivedQuery(@NotNull PsiMethod method, @Nullable List<PsiExpression> callArgs) {
        PsiFile file = method.getContainingFile();
        if (file == null) return null;
        DerivedMethodInfo methodInfo = DerivedMethodLocator.locate(file, method.getTextRange().getStartOffset());
        if (methodInfo == null) return null;
        PsiClass entityClass = methodInfo.getEntityClass();
        if (entityClass == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        if (table == null) return null;
        PartTree partTree = PartTreeParser.parse(methodInfo.getMethodName());
        SqlStatement statement = DerivedQueryConverter.INSTANCE.convert(partTree, table);
        // T-PS5-03：入口求一次方言（分页分析/方法级增强/结果构建共用同一实例，不再各求一次）
        Dialect dialect = DialectRegistry.INSTANCE.effective(project, file.getVirtualFile());
        // T-J10：Pageable/Page/Sort/Limit 参数全路径——Pageable/Page → LIMIT 占位（调用点形态静态求值）、
        // Sort → ORDER BY（Sort.by 字面量内联）、Limit（3.2） → 仅 LIMIT 无 OFFSET
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(method, callArgs, dialect, table);
        if (pageSort != null) {
            SelectStatement select = (statement instanceof SelectStatement) ? (SelectStatement) statement : null;
            if (pageSort.getLimit() != null && select != null) {
                select = copySelectWithLimit(select, pageSort.getLimit());
            }
            if (pageSort.getOrderBy() != null && !pageSort.getOrderBy().isEmpty() && select != null) {
                select = copySelectWithOrderBy(select, pageSort.getOrderBy());
            }
            if (select != null) statement = select;
        }
        // 使用实际方法参数名（而非通用的 param1/param2）
        // 分页/排序/限流参数：排除参数本身，追加分页占位参数行（pageOffset/pageSize 等）
        List<String> paramNames = new ArrayList<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            String typeName = param.getType().getCanonicalText();
            int lt = typeName.indexOf('<');
            if (lt >= 0) typeName = typeName.substring(0, lt);
            if (JpaQueryEnhancer.isPageableType(typeName)
                    || JpaQueryEnhancer.isSortType(typeName)
                    || JpaQueryEnhancer.isLimitType(typeName)) {
                continue;
            }
            paramNames.add(param.getName());
        }
        if (pageSort != null && pageSort.getExtraParamNames() != null) {
            paramNames.addAll(pageSort.getExtraParamNames());
        }
        // DerivedQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        String sql = SqlGenerator.generate(statement, false, dialect, null);
        // T-J4：IN/NOT IN 条件参数接线——inClauseParameterOrdinals 序号对齐方法参数名
        // （过滤 Pageable 后的 paramNames 序），把 IN 参数挂进 buildJpaPreviewResult 的
        // inClauseParams（面板回填按逗号分割多值，对齐 JPQL/HQL 路径的 Result.inClauseParams 惯例）
        Set<String> inClauseParams = new LinkedHashSet<>();
        for (int ordinal : DerivedQueryConverter.inClauseParameterOrdinals(methodInfo.getMethodName())) {
            if (ordinal >= 0 && ordinal < paramNames.size()) {
                inClauseParams.add(paramNames.get(ordinal));
            }
        }
        // T-J15/J22：@Lock（FOR UPDATE）/ @QueryHints / @EntityGraph（LEFT JOIN）/ 返回类型 notes；
        // pageNotes：分页/排序分析已在上方完成（含调用点实参求值），透传语义注释、跳过二次分析
        String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, 
                method, sql, table, false,
                true,
                pageSort != null && pageSort.getNotes() != null ? pageSort.getNotes() : List.of(),
                dialect);
        return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, statement, enhancedSql, table.getTableName(), paramNames, method, inClauseParams, dialect);
    }

    /** {@link SelectStatement} 的 Kotlin {@code copy(limit = ...)} Java 等价（显式全参重建）。 */
    private static SelectStatement copySelectWithLimit(@NotNull SelectStatement sel, @Nullable LimitClause limit) {
        return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(),
                sel.getDistinct(), sel.getConditions(), sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(),
                limit, sel.getRawFirst(), sel.getRawAround(), sel.getComment(), sel.getUnions(),
                sel.getFromSubQuery(), sel.getFillClauses());
    }

    /** {@link SelectStatement} 的 Kotlin {@code copy(orderBy = ...)} Java 等价（显式全参重建）。 */
    private static SelectStatement copySelectWithOrderBy(@NotNull SelectStatement sel, @NotNull List<OrderByItem> orderBy) {
        return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(),
                sel.getDistinct(), sel.getConditions(), sel.getGroupBy(), sel.getHaving(), orderBy,
                sel.getLimit(), sel.getRawFirst(), sel.getRawAround(), sel.getComment(), sel.getUnions(),
                sel.getFromSubQuery(), sel.getFillClauses());
    }

    /** T-J22：@Query.countQuery → count SQL 注释行（Page 返回语义；转换失败降级原文注释）。 */
    public List<String> countQueryNotes(@Nullable PsiMethod method, @Nullable TableInfo table) {
        if (method == null) return List.of();
        String countJpql = JpaQueryEnhancer.countQueryAttribute(method);
        if (countJpql == null) return List.of();
        TableInfo countTable = extractEntityNameFromHql(countJpql) == null ? table
                : SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, null, null, countJpql, method);
        String sql = JpqlConverter.convert(countJpql, countTable, project, method).getSql();
        List<String> notes = new ArrayList<>();
        notes.add("@Query.countQuery（Page 返回的 count 伴随查询）：");
        notes.addAll(sql.lines().toList());
        return notes;
    }

    /**
     * 从 SQL 文本探测主表别名（EntityGraph JOIN 的 ON 左值用）：
     * {@code FROM t_user u} → {@code u}；无别名/别名是 SQL 关键字 → 表名本名。
     */
    public String detectMainAlias(@NotNull String sql, @NotNull TableInfo table) {
        try {
            java.util.regex.Matcher match = FROM_TABLE_ALIAS.matcher(sql);
            String alias = match.find() ? match.group(2) : null;
            boolean isKeyword = false;
            if (alias != null) {
                for (String kw : SQL_CLAUSE_KEYWORDS) {
                    if (alias.equalsIgnoreCase(kw)) {
                        isKeyword = true;
                        break;
                    }
                }
            }
            return (alias == null || isKeyword) ? table.getTableName() : alias;
        } catch (Throwable t) {
            return table.getTableName();
        }
    }

    /**
     * JPQL/HQL 字符串的宿主是 Java 源文件，不是 DatabaseTools SqlFile。跨文件命名查询
     * 使用定义所在文件；无文件/模块歧义时保留项目级兼容回退，不猜测调用点模块。
     */
    @NotNull
    public PreviewSourceContext jpaSourceContext(@Nullable com.intellij.psi.PsiElement owner) {
        com.intellij.psi.PsiFile host = owner != null ? owner.getContainingFile() : null;
        com.intellij.openapi.vfs.VirtualFile file = host != null ? host.getVirtualFile() : null;
        ModuleIdentityResolution module;
        if (file != null) {
            ModuleIdentityResolution resolved = com.intellij.openapi.application.ReadAction.compute(
                    () -> ModuleIdentityResolver.resolve(project, file));
            module = resolved != null ? resolved : ModuleIdentityResolution.None.INSTANCE;
        } else {
            module = ModuleIdentityResolution.None.INSTANCE;
        }
        return new PreviewSourceContext(
                project,
                PreviewSourceOrigin.PSI,
                host,
                file,
                SqlPsiResolution.None.INSTANCE,
                module,
                null,
                null);
    }

    @NotNull
    public DialectDecisionEngine.Decision jpaSourceDecision(@Nullable com.intellij.psi.PsiElement owner) {
        return DialectRegistry.INSTANCE.decision(jpaSourceContext(owner));
    }

    @NotNull
    private Dialect dialectOf(@NotNull DialectDecisionEngine.Decision decision) {
        return decision.dialectName() != null
                ? DialectRegistry.INSTANCE.byName(decision.dialectName())
                : DialectRegistry.INSTANCE.byName(null);
    }

    /**
     * JPQL/@Query 预览（波次一）：解析 @Query 注解方法 → JPQL 字符串 → SQL → PreviewResult。
     *
     * <p>供 cn.song.mybatisplus.visualizer.ui.gutter.JpqlGutterLineMarkerProvider 点击调用。
     * T-J10/J15/J22：方法级增强（Pageable/Limit → LIMIT 占位、@Lock → FOR UPDATE、
     * @QueryHints 注释、@EntityGraph → LEFT JOIN、Page 返回 → count SQL 展示）见
     * {@link #applyJpaMethodEnhancements}。
     */
    @Nullable
    public PreviewResult previewJpql(@NotNull PsiMethod method) {
        PsiFile file = method.getContainingFile();
        JpqlQueryInfo info = (file != null)
                ? JpqlQueryLocator.INSTANCE.locate(file, method.getTextRange().getStartOffset()) : null;
        if (info == null) return null;
        String jpql = info.getJpql();
        List<String> paramNames = extractJpqlParamNames(method);
        PsiClass entityClass = info.getEntityClass();
        TableInfo table = info.isNative() ? (entityClass != null ? new JpaEntityResolver().resolve(entityClass) : null)
                : SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, entityClass, null, jpql, method);
        // O-145：Java 方法宿主的非 URL 来源证据；不将 JPQL 字面量冒充 SqlFile。
        DialectDecisionEngine.Decision decision = jpaSourceDecision(method);
        Dialect dialect = dialectOf(decision);
        // T-J10：分页/排序参数占位名（无参数时空列表，存量行为零变化）
        JpaQueryEnhancer.PageSortEval pageSortEval = JpaQueryEnhancer.evaluatePageSort(method, null, dialect, table);
        List<String> extraParamNames = (pageSortEval != null && pageSortEval.getExtraParamNames() != null)
                ? pageSortEval.getExtraParamNames() : List.of();
        List<String> allParamNames = new ArrayList<>(paramNames);
        allParamNames.addAll(extraParamNames);
        // 原生 SQL：转换命名参数 :param → ? 后展示（保持可读性）
        if (info.isNative()) {
            String convertedSql = convertNativeSqlParameters(jpql);
            // 检测 IN 子句参数（在命名参数替换前检测 :paramName）
            Set<String> inParams = JpqlConverter.findInClauseParams(jpql);
            List<ParamHint> hints = !inParams.isEmpty()
                    ? allParamNames.stream().map(n -> new ParamHint(true, null, inParams.contains(n))).toList()
                    : List.of();
            // T-J15：@EntityGraph 对原生查询运行时不生效 → 仅注释说明；LIMIT/@Lock/notes 照常
            String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, method, convertedSql, table, true, dialect);
            String tableName;
            if (table != null) tableName = table.getTableName();
            else if (info.getEntityClass() != null) tableName = JpaEntityResolver.inferDecoratedTableName(info.getEntityClass().getName(), project);
            else tableName = "";
            return new PreviewResult(
                    enhancedSql,
                    // 无增强时保持旧字节级行为（convertedSql 原样），有增强时压缩多行空白
                    enhancedSql.equals(convertedSql) ? convertedSql
                            : SQL_WHITESPACE_RUN.matcher(enhancedSql).replaceAll(" ").trim(),
                    allParamNames,
                    ParameterReplacer.buildParamTable(allParamNames),
                    null, tableName, null,
                    table != null ? table.getAllColumnNames() : List.of(),
                    // O-145：方言决策随行入结果（面板指示行/执行闸消费）
                    Map.of(), null, null,
                    hints, List.of(), null, null, Map.of(), null, null, null, null,
                    Map.of(), null, false, null, null,
                    List.of(), java.util.Set.of(), null, null,
                    null, dialect, decision, null, null);
        }
        // Wave1 第三参 project：激活 PSI 级 JOIN ON（@JoinColumn 真实化）与子查询实体映射
        JpqlConverter.Result result = JpqlConverter.convert(jpql, table, project, method);
        // T-J10/J15/J22：方法级增强（LIMIT/ORDER BY/FOR UPDATE/EntityGraph JOIN/notes/count SQL）
        String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, method, result.getSql(), table, false, dialect);
        return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, result.getStatement(), enhancedSql, table != null ? table.getTableName() : "",
                allParamNames, method, result.getInClauseParams(), dialect, decision);
    }

    /**
     * 将原生 SQL 中的命名参数 :paramName 转换为 ? 占位符。
     * 示例：{@code :keyword} → {@code ?}，{@code CONCAT('%', :keyword, '%')} → {@code CONCAT('%', ?, '%')}
     */
    // T-JpaParamOrder F3：正则提常量——extractNativeParamNames 与替换共用同一 pattern，
    // 保证名序与替换产物 `?` 序一致
    private static final Pattern NATIVE_NAMED_PARAM = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    private String convertNativeSqlParameters(@NotNull String nativeSql) {
        return NATIVE_NAMED_PARAM.matcher(nativeSql).replaceAll("?");
    }

    /**
     * T-JpaParamOrder F3：原生 SQL 命名参数名序（首现序，与 {@link #convertNativeSqlParameters}
     * 替换出的 {@code ?} 一一对应）。位置参数 {@code ?N} 不在内。
     */
    private List<String> extractNativeParamNames(@NotNull String nativeSql) {
        List<String> names = new ArrayList<>();
        Matcher matcher = NATIVE_NAMED_PARAM.matcher(nativeSql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * T-JpaParamOrder F3：全部 {@code ?} 均来自命名参数替换时返回名序（供按名回填），否则 null
     * 维持按序回填。位置参数混入时名序数量与 {@code ?} 不齐，按名会把无名的 {@code ?} 配空值——
     * 保守回退既有语义。{@code ?} 计数用朴素 count（判定用途，非替换位点）。
     */
    @Nullable
    public List<String> usableNamedPlaceholderSources(@NotNull String sql, @NotNull List<String> namedParamNames) {
        int questionMarks = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') questionMarks++;
        }
        return !namedParamNames.isEmpty() && namedParamNames.size() == questionMarks ? namedParamNames : null;
    }

    // T-JpaParamOrder F6：静态值字面量形态（数字含 Java 类型后缀 / 枚举常量引用）
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("-?\\d+(\\.\\d+)?[LlFfDd]?");
    private static final Pattern ENUM_CONSTANT_REF = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*\\.[A-Z][A-Z0-9_]*");

    // T-PerfSweep24 F84：函数体内现场 Regex 提常量（本文件 18 处收敛；含 :1289 命名参数
    // 与既有 NATIVE_NAMED_PARAM 同 pattern 直接收编复用）。
    /** 预览 SQL 折叠空白（compactSql 构建处共用，12 处）。 */
    private static final Pattern SQL_WHITESPACE_RUN = Pattern.compile("\\s+");

    /** JPA 查询内顶层分页子句探测（LIMIT/OFFSET/FETCH）。 */
    private static final Pattern LIMIT_OFFSET_FETCH_CLAUSE = Pattern.compile("(?i)\\b(LIMIT|OFFSET|FETCH)\\b");

    /** JPA 查询内顶层 ORDER BY 探测。 */
    private static final Pattern ORDER_BY_CLAUSE = Pattern.compile("(?i)\\bORDER\\s+BY\\b");

    /**
     * 主表别名探测：{@code FROM t_user u}（detectMainAlias 用）。
     * F84 附带修正：原函数体内字面量带一个多余的 {@code )}（unbalanced paren——PatternSyntaxException
     * 被 detectMainAlias 的 runCatching 长期吞掉，别名探测实际一直走表名兜底）；提常量后构造期
     * 即爆出，顺手摘除多余括号恢复 KDoc 声明的探测语义。
     */
    private static final Pattern FROM_TABLE_ALIAS =
            Pattern.compile("(?i)\\bFROM\\s+[`\"(]*([\\w.]+)[`\"]*\\s+([a-zA-Z_][\\w$]*)");

    /** HQL 位置参数 {@code ?N}（extractParamNamesFromHql 用）。 */
    private static final Pattern POSITIONAL_PARAM_INDEX = Pattern.compile("\\?(\\d+)");

    /** 集合元素类型提取：{@code Collection<X>}/{@code List<X>}/{@code Set<X>}（参数数字面量判定用）。 */
    private static final Pattern GENERIC_COLLECTION_TYPE = Pattern.compile("(?:Collection|List|Set)<(.+)>");

    /**
     * T-JpaParamOrder F6：setParameter 静态值求值——参数框初值自动回显。
     *
     * <p>用户追问「为什么这些常量没有回显」（20261006）：{@code setParameter("locationStatus", "E")}
     * 这类字面量/枚举常量可静态求值，此前面板一律留空、渲染全 {@code ?}，与代码事实不符。</p>
     *
     * <p>识别面（保守，宁缺勿错）：</p>
     * <ul>
     *  <li>字符串字面量 {@code "E"} → E（去引号 + 简单反转义）</li>
     *  <li>数字字面量 {@code 0} / {@code 1L} / {@code 0.5f} → 去 Java 类型后缀</li>
     *  <li>布尔字面量 {@code true} / {@code false}</li>
     *  <li>枚举常量引用 {@code Enum.CONST}（取末段；{@code @Enumerated(STRING)} 运行期绑定值即该名）</li>
     * </ul>
     * <p>变量引用 / 方法调用 / 任意表达式 → null（保持空待填，用户手动输入）。</p>
     */
    @Nullable
    private String staticParamValue(@NotNull String valueExpr) {
        String expr = valueExpr.trim();
        if (expr.isEmpty()) return null;
        // 注意：单字符字面量（数字 `0`）合法——长度守卫只挡空串，勿写成 length < 2
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length() >= 2) {
            return expr.substring(1, expr.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        if (NUMERIC_LITERAL.matcher(expr).matches()) {
            int end = expr.length();
            while (end > 0 && "LlFfDd".indexOf(expr.charAt(end - 1)) >= 0) end--;
            return expr.substring(0, end);
        }
        if ("true".equals(expr) || "false".equals(expr)) return expr;
        if (ENUM_CONSTANT_REF.matcher(expr).matches()) return expr.substring(expr.lastIndexOf('.') + 1);
        return null;
    }

    /** T-JpaParamOrder F6：setParameter 追踪列表 → 参数框初值表（仅静态可求值项入表）。 */
    private Map<String, String> staticParamValues(@NotNull List<HqlParameter> parameters) {
        Map<String, String> map = new LinkedHashMap<>();
        for (HqlParameter p : parameters) {
            if (p.isPositional()) continue;
            String value = staticParamValue(p.getValueExpr());
            if (value != null) map.put(p.getParamName(), value);
        }
        return map;
    }

    /**
     * 提取 JPQL/@Query 方法的参数名。
     * 优先 @Param 注解值（@Param("foo") → "foo"），无注解时用方法参数名。
     * T-J10：Pageable/Sort/Limit 类型参数本体不登记参数行（分页占位参数行 pageSize/pageOffset 等
     * 由 {@link JpaQueryEnhancer#evaluatePageSort} 的 extraParamNames 补充，避免同一参数双行）。
     */
    private List<String> extractJpqlParamNames(@NotNull PsiMethod method) {
        List<String> parameters = new ArrayList<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            String typeName = param.getType().getCanonicalText();
            int lt = typeName.indexOf('<');
            if (lt >= 0) typeName = typeName.substring(0, lt);
            if (JpaQueryEnhancer.isPageableType(typeName)
                    || JpaQueryEnhancer.isSortType(typeName)
                    || JpaQueryEnhancer.isLimitType(typeName)) {
                continue;
            }
            PsiModifierList modifierList = param.getModifierList();
            PsiAnnotation annotation = (modifierList != null)
                    ? modifierList.findAnnotation("org.springframework.data.repository.query.Param") : null;
            if (annotation == null && modifierList != null) {
                for (PsiAnnotation a : modifierList.getAnnotations()) {
                    PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
                    if (ref != null && "Param".equals(ref.getReferenceName())) { annotation = a; break; }
                }
            }
            if (annotation != null) {
                PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
                if (value instanceof PsiLiteralExpression) {
                    Object v = ((PsiLiteralExpression) value).getValue();
                    parameters.add(v instanceof String ? (String) v : param.getName());
                } else {
                    parameters.add(param.getName());
                }
            } else {
                parameters.add(param.getName());
            }
        }
        return parameters;
    }

    /**
     * CriteriaQuery 预览（波次一）：定位 CriteriaQueryModel → CriteriaQueryConverter → SQL → PreviewResult。
     *
     * <p>供 cn.song.mybatisplus.visualizer.ui.gutter.CriteriaQueryGutterLineMarkerProvider 点击调用。
     * T-J10：宿主方法 Pageable/Limit 参数 → LIMIT 子句（语句模型级，进 previewModel 交互重生成）。
     */
    @Nullable
    public PreviewResult previewCriteriaQuery(@NotNull PsiElement anchor) {
        // 1. 用 CriteriaQueryLocator.locate(anchor) 获取 CriteriaQueryModel
        CriteriaQueryModel model = CriteriaQueryLocator.INSTANCE.locate(anchor);
        if (model == null) return null;
        // 2. CriteriaQueryConverter.convert(model) → SelectStatement
        // T-TS-07：显式传本服务所属项目——实体类/join/子查询的**类名兜底推断**按该项目读取
        // 项目级表配置（原先传 null → 取「首个打开项目」，多项目同开时表名修饰错项目）
        SqlStatement statement = CriteriaQueryConverter.INSTANCE.convert(model, null, project);
        PsiMethod hostMethod = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class);
        // 3. 参数名：从包含 CriteriaQuery 的宿主方法参数列表提取
        List<String> paramNames = (hostMethod != null)
                ? Arrays.stream(hostMethod.getParameterList().getParameters()).map(PsiParameter::getName).toList()
                : List.<String>of();
        // Criteria 源是 createQuery 所在 Java 文件；不能把生成的 SQL 伪装成 SqlFile。
        DialectDecisionEngine.Decision decision = jpaSourceDecision(anchor);
        Dialect dialect = dialectOf(decision);
        // T-J10：宿主方法分页参数 → LIMIT 子句（criteria 路径是语句模型，直接挂 limit）
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(hostMethod, null, dialect, null);
        if (pageSort != null) {
            SelectStatement select = (statement instanceof SelectStatement) ? (SelectStatement) statement : null;
            SelectStatement limited = select;
            if (pageSort.getLimit() != null && select != null) {
                limited = copySelectWithLimit(select, pageSort.getLimit());
            }
            if (limited != null) statement = limited;
        }
        // CriteriaQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        String sql = SqlGenerator.generate(statement, false, dialect, null);
        TableInfo resolvedTable = (model.getEntityClass() != null)
                ? new JpaEntityResolver().resolve(model.getEntityClass()) : null;
        String tableName = (model.getEntityClassName() != null)
                ? JpaEntityResolver.inferDecoratedTableName(model.getEntityClassName(), project) : "";
        // T-J10/J15/J22：方法级增强（Sort ORDER BY/锁/hints/返回类型 notes；LIMIT 已在语句模型级挂载，
        // 分页/排序 notes 由上方分析透传）
        String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, 
                hostMethod, sql, resolvedTable, false,
                true,
                pageSort != null && pageSort.getNotes() != null ? pageSort.getNotes() : List.of(),
                dialect);
        List<String> allParamNames = new ArrayList<>(paramNames);
        if (pageSort != null && pageSort.getExtraParamNames() != null) {
            allParamNames.addAll(pageSort.getExtraParamNames());
        }
        return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, statement, enhancedSql, tableName, allParamNames, hostMethod, Set.of(), dialect, decision);
    }

    /**
     * Hibernate HQL 预览（session.createQuery）。
     */
    @Nullable
    public PreviewResult previewHql(@NotNull PsiElement anchor) {
        HqlQueryInfo info = HqlQueryLocator.INSTANCE.locate(anchor);
        if (info == null) return null;
        // 双重检测：isNativeQuery 标志 + 方法名 fallback
        if (info.isNativeQuery() || "createNativeQuery".equals(info.getCreateQueryMethodName())) {
            return previewNativeSql(info, anchor);
        }
        // T-PreviewShortcut：createNamedQuery("name") 按名回查实体 @NamedQuery，解析真实 JPQL；
        // 跨文件 / 命名不规范解析不到时优雅降级为把名字当 HQL 处理（不抛、不挂起）。
        if ("createNamedQuery".equals(info.getCreateQueryMethodName())) {
            List<NamedQueryInfo> namedCandidates = NamedQueryLocator.findCandidatesByQueryName(project, info.getHql(),
                    info.getMethod() != null ? info.getMethod() : anchor);
            if (namedCandidates.size() > 1) return null;
            NamedQueryInfo named = namedCandidates.size() == 1 ? namedCandidates.get(0) : null;
            if (named != null && !named.getQuery().isBlank()) {
                PsiClass entityClass = named.getAnnotation() != null
                        ? PsiTreeUtil.getParentOfType(named.getAnnotation(), PsiClass.class, false) : null;
                TableInfo namedTable = SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, entityClass, null, named.getQuery(),
                        info.getMethod() != null ? info.getMethod() : anchor);
                JpqlConverter.Result namedResult = JpqlConverter.convert(named.getQuery(), namedTable, project,
                        info.getMethod() != null ? info.getMethod() : anchor);
                List<String> namedParams = extractParamNamesFromHql(named.getQuery());
                // 命名查询以定义注解的文件为 owner，而非 createNamedQuery 的调用文件。
                DialectDecisionEngine.Decision decision = jpaSourceDecision(named.getAnnotation());
                Dialect dialect = dialectOf(decision);
                // T-J10/J15/J22：宿主方法级增强
                String namedEnhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, info.getMethod(), namedResult.getSql(), namedTable, false, dialect);
                JpaQueryEnhancer.PageSortEval namedPageSort =
                        JpaQueryEnhancer.evaluatePageSort(info.getMethod(), null, dialect, namedTable);
                List<String> namedExtraParams = (namedPageSort != null && namedPageSort.getExtraParamNames() != null)
                        ? namedPageSort.getExtraParamNames() : List.of();
                List<String> namedAllParams = new ArrayList<>(namedParams);
                namedAllParams.addAll(namedExtraParams);
                return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, namedResult.getStatement(), namedEnhancedSql,
                        namedTable != null ? namedTable.getTableName() : "", namedAllParams,
                        info.getMethod(), namedResult.getInClauseParams(), dialect, decision);
            }
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（hbm 纯 POJO 项目无注解可查）
            HbmXmlMapping.NamedQuery hbmNamed = HbmXmlEntityResolver.findNamedQuery(info.getHql(), project,
                    info.getMethod() != null ? info.getMethod() : anchor);
            if (hbmNamed != null) {
                return previewHbmNamedQuery(hbmNamed, info.getMethod(), null,
                        info.getMethod() != null ? info.getMethod() : anchor);
            }
            // 同名 hbm 定义歧义不能把首项 SQL 或查询名误当 HQL 发给用户。
            if (HbmXmlEntityResolver.hasAmbiguousNamedQuery(info.getHql(), project,
                    info.getMethod() != null ? info.getMethod() : anchor)) {
                return null;
            }
        }
        PsiElement context = info.getMethod() != null ? info.getMethod() : anchor;
        TableInfo table = SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, info.getEntityClass(), info.getEntityClassName(), info.getHql(), context);
        HqlConverter.Result result = HqlConverter.convert(info.getHql(), table, project, extractEntityNameFromHql(info.getHql()), context);
        // 参数名提取：
        // 1. 追踪 setParameter 结果（位置参数用 valueExpr 即变量名，命名参数用 paramName）
        // 2. 若为空（链式调用场景），直接从 HQL 提取
        List<String> paramNames = extractHqlParamNames(info.getParameters());
        if (paramNames.isEmpty()) paramNames = extractParamNamesFromHql(info.getHql());
        DialectDecisionEngine.Decision decision = jpaSourceDecision(info.getMethod() != null ? info.getMethod() : anchor);
        Dialect dialect = dialectOf(decision);
        // T-J10/J15/J22：宿主方法级增强
        String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, info.getMethod(), result.getSql(), table, false, dialect);
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(info.getMethod(), null, dialect, table);
        List<String> extraParams = (pageSort != null && pageSort.getExtraParamNames() != null)
                ? pageSort.getExtraParamNames() : List.of();
        List<String> allParamNames = new ArrayList<>(paramNames);
        allParamNames.addAll(extraParams);
        // 关键：使用 HqlConverter 转换后的 SQL 字符串（含 ? 占位符），而非从 statement 重新生成。
        return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, result.getStatement(), enhancedSql, table != null ? table.getTableName() : "",
                allParamNames, info.getMethod(), result.getInClauseParams(), dialect, decision,
                result.getPlaceholderParamNames(), info.getDuplicateParamNames(),
                // T-JpaParamOrder F6：字面量/枚举常量自动预填参数框（变量引用不入表，保持待填）
                staticParamValues(info.getParameters()));
    }

    /**
     * 原生 SQL 预览（createNativeQuery）。
     * 不做实体→表名映射，仅替换 :param → ? 并解析实体表名。
     * T-J10/J15：宿主方法级增强（Pageable/Limit → LIMIT、@Lock → FOR UPDATE、notes）。
     */
    private PreviewResult previewNativeSql(@NotNull HqlQueryInfo info, @NotNull PsiElement anchor) {
        String sql = convertNativeSqlParameters(info.getHql());
        List<String> paramNames = extractHqlParamNames(info.getParameters());
        if (paramNames.isEmpty()) paramNames = extractParamNamesFromHql(info.getHql());
        // 解析实体表名（用于显示）
        String tableName = resolveHqlEntityTable(info.getEntityClass());
        if (tableName == null) tableName = (info.getEntityClassName() != null) ? info.getEntityClassName() : "";
        DialectDecisionEngine.Decision decision = jpaSourceDecision(info.getMethod() != null ? info.getMethod() : anchor);
        Dialect dialect = dialectOf(decision);
        // T-J10/J15/J22：方法级增强（native 路径：EntityGraph 仅注释说明）
        String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, info.getMethod(), sql, null, true, dialect);
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(info.getMethod(), null, dialect, null);
        List<String> extraParams = (pageSort != null && pageSort.getExtraParamNames() != null)
                ? pageSort.getExtraParamNames() : List.of();
        List<String> allParamNames = new ArrayList<>(paramNames);
        allParamNames.addAll(extraParams);
        // T-JpaParamOrder F3：native 命名参数名序透传（全部 `?` 来自 :name 替换时启用按名回填）
        List<String> usableNamedSources = usableNamedPlaceholderSources(enhancedSql, extractNativeParamNames(info.getHql()));
        return new PreviewResult(
                enhancedSql, SQL_WHITESPACE_RUN.matcher(enhancedSql).replaceAll(" ").trim(), allParamNames,
                ParameterReplacer.buildParamTable(allParamNames),
                null, tableName, null,
                List.of(), Map.of(), null, null,
                (usableNamedSources != null ? usableNamedSources : allParamNames).stream()
                        .map(n -> new ParamHint(Boolean.valueOf(!isNumericParam(info.getMethod(), n)), null, false)).toList(),
                List.of(), null, null,
                // T-JpaParamOrder F6：native 链同样预填静态常量
                staticParamValues(info.getParameters()),
                null, null, null, null,
                Map.of(), null, false,
                // countSql/paginationLimitLine 未用（规范构造器位置参数）
                null, null,
                usableNamedSources != null ? usableNamedSources : List.of(),
                info.getDuplicateParamNames(),
                // diagramStatement/historyFingerprintSeed 未用；O-145：方言决策随行入结果
                null, null,
                null, dialect, decision, null, null);
    }

    /**
     * 解析原生 SQL 对应的实体表名。
     */
    private String resolveHqlEntityTable(@Nullable PsiClass entityClass) {
        if (entityClass == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        return table != null ? table.getTableName() : null;
    }

    /**
     * 实体级 @NamedQuery/@NamedQueries 预览（T-PreviewShortcut）：
     * 从光标所在 @NamedQuery 注解反查实体类 → 定位对应命名查询 → JPQL 转 SQL。
     *
     * 供 NamedQueryGutterLineMarkerProvider 点击与快捷键调用。
     */
    @Nullable
    public PreviewResult previewNamedQuery(@NotNull PsiElement anchor) {
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(anchor, PsiAnnotation.class, false);
        if (annotation == null) return null;
        PsiClass cls = PsiTreeUtil.getParentOfType(annotation, PsiClass.class, false);
        if (cls == null) return null;
        List<NamedQueryInfo> infos = NamedQueryLocator.INSTANCE.locate(cls);
        if (infos.isEmpty()) return null;
        // 优先取光标所在那一条 @NamedQuery（注解引用匹配）；多选 fallback 取第一条
        NamedQueryInfo info = null;
        for (NamedQueryInfo it : infos) {
            if (it.getAnnotation() == annotation) {
                info = it;
                break;
            }
        }
        if (info == null) info = infos.get(0);
        String jpql = info.getQuery();
        TableInfo table = SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, cls, null, jpql, annotation);
        JpqlConverter.Result result = JpqlConverter.convert(jpql, table, project, annotation);
        List<String> paramNames = extractParamNamesFromHql(jpql);
        DialectDecisionEngine.Decision decision = jpaSourceDecision(info.getAnnotation() != null ? info.getAnnotation() : annotation);
        return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, result.getStatement(), result.getSql(),
                table != null ? table.getTableName() : "", paramNames,
                null, result.getInClauseParams(), dialectOf(decision), decision);
    }

    /**
     * 自定义 DAO HQL 预览（commonDao.findByQuery 等）。
     * 供 cn.song.mybatisplus.visualizer.ui.gutter.CustomDaoHqlGutterLineMarkerProvider 点击调用。
     *
     * <p>T-J21：按 {@link CustomDaoHqlLocator} 登记的 {@link DaoQueryKind} 分派——
     * <ul>
     *  <li>HQL（默认）：既有 HqlConverter 管道（行为不变）；</li>
     *  <li>NAMED_QUERY（findByNamedQuery 族）：hql 字段存查询名——复用 NamedQueryLocator.findByQueryName
     *      按名回查实体 @NamedQuery 的真实 JPQL 再走 JpqlConverter（参考 previewNamedQuery 既有做法）；
     *      回查失败优雅降级为可读占位（不抛异常、不出错误 SQL）；</li>
     *  <li>QBC（executeFind）：可读占位「QBC/DetachedCriteria 形态暂不支持静态解析」；</li>
     *  <li>ENTITY_OP（get/load/save/delete 等）：locate 不认领（无 SQL 字符串），由 preview 批量分支
     *      经 JpaCrudOperationConverter 派发，不经本方法。</li>
     * </ul>
     */
    @Nullable
    public PreviewResult previewCustomDaoHql(@NotNull PsiElement anchor) {
        CustomDaoHqlInfo info = CustomDaoHqlLocator.INSTANCE.locate(anchor);
        if (info == null) return null;
        if (info.getKind() == DaoQueryKind.QBC) {
            return placeholderPreview(
                    "-- QBC/DetachedCriteria 形态暂不支持静态解析（条件树运行时构建）");
        }
        if (info.getKind() == DaoQueryKind.NAMED_QUERY) {
            List<NamedQueryInfo> namedCandidates = NamedQueryLocator.findCandidatesByQueryName(project, info.getHql(),
                    info.getMethod() != null ? info.getMethod() : anchor);
            if (namedCandidates.size() > 1) return null;
            NamedQueryInfo named = namedCandidates.size() == 1 ? namedCandidates.get(0) : null;
            if (named != null && !named.getQuery().isBlank()) {
                PsiClass entityClass = named.getAnnotation() != null
                        ? PsiTreeUtil.getParentOfType(named.getAnnotation(), PsiClass.class, false) : null;
                TableInfo namedTable = SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, entityClass, null, named.getQuery(),
                        info.getMethod() != null ? info.getMethod() : anchor);
                JpqlConverter.Result namedResult = JpqlConverter.convert(named.getQuery(), namedTable, project,
                        info.getMethod() != null ? info.getMethod() : anchor);
                List<String> namedParams = extractParamNamesFromHql(named.getQuery());
                DialectDecisionEngine.Decision decision = jpaSourceDecision(named.getAnnotation());
                return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, namedResult.getStatement(), namedResult.getSql(),
                        namedTable != null ? namedTable.getTableName() : "", namedParams,
                        info.getMethod(), namedResult.getInClauseParams(), dialectOf(decision), decision);
            }
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（与 previewHql 同款）
            HbmXmlMapping.NamedQuery hbmNamed = HbmXmlEntityResolver.findNamedQuery(info.getHql(), project,
                    info.getMethod() != null ? info.getMethod() : anchor);
            if (hbmNamed != null) {
                return previewHbmNamedQuery(hbmNamed, info.getMethod(), null,
                        info.getMethod() != null ? info.getMethod() : anchor);
            }
            // 同名 hbm 定义歧义不能把首项 SQL 或查询名误当 HQL 发给用户。
            if (HbmXmlEntityResolver.hasAmbiguousNamedQuery(info.getHql(), project,
                    info.getMethod() != null ? info.getMethod() : anchor)) {
                return null;
            }
            // 回查失败：优雅降级占位（跨文件/命名不规范），不出错误 SQL
            return placeholderPreview(
                    "-- 命名查询 \"" + info.getHql() + "\"：未能回查到 @NamedQuery 定义（跨文件/命名不规范），无法静态解析 JPQL");
        }
        PsiElement context = info.getMethod() != null ? info.getMethod() : anchor;
        TableInfo table = SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, null, info.getEntityClassName(), info.getHql(), context);
        // 使用查询根名替换别名，不再全项目二次找类；JAR 实体保留 DAO 模块上下文。
        HqlConverter.Result result = HqlConverter.convert(info.getHql(), table, project, extractEntityNameFromHql(info.getHql()), context);
        List<String> paramNames = extractHqlParamNames(info.getParameters());
        if (paramNames.isEmpty()) paramNames = extractParamNamesFromHql(info.getHql());
        DialectDecisionEngine.Decision decision = jpaSourceDecision(info.getMethod() != null ? info.getMethod() : anchor);
        return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this, result.getStatement(), result.getSql(), table != null ? table.getTableName() : "",
                paramNames, info.getMethod(), result.getInClauseParams(),
                dialectOf(decision), decision,
                result.getPlaceholderParamNames(),
                // T-JpaParamOrder F6：自定义 DAO 链同样预填静态常量
                Set.of(), staticParamValues(info.getParameters()));
    }

    /** T-J21：可读占位预览卡（优雅降级：无 SQL 模板时给出解释性注释，不抛异常）。 */
    private PreviewResult placeholderPreview(@NotNull String note) {
        return new PreviewResult(
                note,
                note,
                List.of(),
                null,
                null,
                null,
                null,
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

    /**
     * T-H3：hbm 命名查询预览——按种类分派：
     * <ul>
     *  <li>HQL（{@code <query>}）→ {@link HqlConverter} 管道（实体表经 resolveHqlTable 路径 3 hbm 映射取得）；</li>
     *  <li>原生（{@code <sql-query>}）→ 命名参数 {@code :param} → {@code ?} 替换（与 previewNativeSql 同款）。</li>
     * </ul>
     *
     * <p>供 hbm 文件 gutter（cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider）
     * 与 createNamedQuery / 自定义 DAO 回查 fallback 共用。
     */
    @Nullable
    public PreviewResult previewHbmNamedQuery(@NotNull HbmXmlMapping.NamedQuery query) {
        return previewHbmNamedQuery(query, null);
    }

    @Nullable
    public PreviewResult previewHbmNamedQuery(@NotNull HbmXmlMapping.NamedQuery query, @Nullable PsiMethod hostMethod) {
        return previewHbmNamedQuery(query, hostMethod, null, hostMethod);
    }

    @Nullable
    public PreviewResult previewHbmNamedQuery(@NotNull HbmXmlMapping.NamedQuery query, @Nullable PsiMethod hostMethod,
                                               @Nullable XmlTag definitionTag, @Nullable PsiElement context) {
        if (query.getQueryText().isBlank()) return null;
        // 命名查询的 owner 是定义 XML 标签，绝不是跨文件 createNamedQuery 调用方法。
        XmlTag owner = definitionTag != null
                ? definitionTag
                : HbmXmlEntityResolver.findNamedQueryDefinition(query, project, context);
        DialectDecisionEngine.Decision decision = jpaSourceDecision(owner);
        Dialect dialect = dialectOf(decision);
        List<String> paramNames = extractParamNamesFromHql(query.getQueryText());
        if (query.getKind() == HbmXmlMapping.QueryKind.NATIVE) {
            String sql = convertNativeSqlParameters(query.getQueryText());
            String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, hostMethod, sql, null, true, dialect);
            // T-JpaParamOrder F3：native 命名参数名序透传（与 previewNativeSql 同款判定）
            List<String> usableNamedSources = usableNamedPlaceholderSources(enhancedSql, extractNativeParamNames(query.getQueryText()));
            return new PreviewResult(
                    enhancedSql,
                    SQL_WHITESPACE_RUN.matcher(enhancedSql).replaceAll(" ").trim(),
                    paramNames,
                    ParameterReplacer.buildParamTable(paramNames),
                    null,
                    null,
                    null,
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    (usableNamedSources != null ? usableNamedSources : paramNames).stream()
                            .map(n -> new ParamHint(Boolean.TRUE, null, false)).toList(),
                    List.of(),
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    false,
                    // countSql/paginationLimitLine 未用（规范构造器位置参数）
                    null, null,
                    usableNamedSources != null ? usableNamedSources : List.of(),
                    Set.of(),
                    null, null,
                    null, dialect, decision, null, null);
        }
        String entityName = extractEntityNameFromHql(query.getQueryText());
        TableInfo table = SqlPreviewServiceEnhancementsKt.resolveHqlTable(this, null, entityName, query.getQueryText(), context);
        HqlConverter.Result result = HqlConverter.convert(query.getQueryText(), table, project, entityName, context);
        String enhancedSql = SqlPreviewServiceEnhancementsKt.applyJpaMethodEnhancements(this, hostMethod, result.getSql(), table, false, dialect);
        return SqlPreviewServiceEnhancementsKt.buildJpaPreviewResult(this,
                result.getStatement(),
                enhancedSql,
                table != null ? table.getTableName() : "",
                paramNames,
                hostMethod,
                result.getInClauseParams(),
                dialect,
                decision,
                result.getPlaceholderParamNames(),
                Set.of(),
                Map.of());
    }

    /** T-H2：hbm 文件 gutter 点击入口——从 {@code <query>}/{@code <sql-query>} XmlTag 构造命名查询后预览。 */
    @Nullable
    public PreviewResult previewHbmNamedQueryTag(@NotNull Project tagProject, @NotNull XmlTag tag) {
        String name = tag.getAttributeValue("name");
        if (name == null) return null;
        HbmXmlMapping.QueryKind kind = HbmNamedQueryGutterLineMarkerProvider.kindOf(tag.getName());
        if (kind == null) return null;
        // 文本提取复用 resolver 的解析口径（直属 XmlText、CDATA 剥包裹、排除子标签）；
        // tag 与 findNamedQuery 同源
        String text = XmlDirectText.directTextOf(tag);
        return previewHbmNamedQuery(new HbmXmlMapping.NamedQuery(name, kind, text), null, tag, tag);
    }

    /**
     * JpaSpecificationExecutor 调用预览（T-J5）：定位 anchor 所在方法调用 →
     * {@link SpecificationQueryConverter} 解析 Specification lambda/组合器 → SELECT/COUNT/DELETE + WHERE。
     *
     * <p>供 gutter 点击与 T-J9 调用点动作路径调用；不识别（非 executor 家族/非 Specification 实参）返回 null。
     */
    @Nullable
    public PreviewResult previewSpecification(@NotNull PsiElement anchor) {
        PsiMethodCallExpression call = null;
        PsiElement cur = anchor;
        while (cur != null) {
            if (cur instanceof PsiMethodCallExpression) { call = (PsiMethodCallExpression) cur; break; }
            cur = cur.getParent();
        }
        if (call == null) return null;
        SpecificationQueryConverter.SpecificationPreview preview =
                SpecificationQueryConverter.convert(call,
                        (settings != null && settings.getState() != null) ? settings.getState().getReplaceParams() : true);
        if (preview == null) return null;
        DialectDecisionEngine.Decision specDecision = jpaSourceDecision(call);
        PreviewResult result = new PreviewResult(
                preview.getSql(),
                SQL_WHITESPACE_RUN.matcher(preview.getSql()).replaceAll(" ").trim(),
                preview.getParamSources(),
                ParameterReplacer.buildParamTable(preview.getParamSources()),
                null,
                preview.getTableName(),
                preview.getWarning(),
                preview.getColumns(),
                Map.of(),
                null,
                null,
                preview.getParamHints(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                false,
                null,
                null,
                List.of(),
                java.util.Set.of(),
                null,
                null,
                null,
                dialectOf(specDecision),
                specDecision,
                null,
                null);
        recordHistory(result, anchor);
        return result;
    }

    /**
     * QueryByExampleExecutor 调用预览（T-J20）：定位 anchor 所在方法调用 →
     * {@link QueryByExampleConverter} 解析 Example.of(probe[, matcher]) → probe 非空属性等值 WHERE。
     */
    @Nullable
    public PreviewResult previewQueryByExample(@NotNull PsiElement anchor) {
        PsiMethodCallExpression call = null;
        PsiElement cur = anchor;
        while (cur != null) {
            if (cur instanceof PsiMethodCallExpression) { call = (PsiMethodCallExpression) cur; break; }
            cur = cur.getParent();
        }
        if (call == null) return null;
        QueryByExampleConverter.ExamplePreview preview =
                QueryByExampleConverter.convert(call,
                        (settings != null && settings.getState() != null) ? settings.getState().getReplaceParams() : true);
        if (preview == null) return null;
        DialectDecisionEngine.Decision exampleDecision = jpaSourceDecision(call);
        PreviewResult result = new PreviewResult(
                preview.getSql(),
                SQL_WHITESPACE_RUN.matcher(preview.getSql()).replaceAll(" ").trim(),
                preview.getParamSources(),
                ParameterReplacer.buildParamTable(preview.getParamSources()),
                null,
                preview.getTableName(),
                null,
                preview.getColumns(),
                Map.of(),
                null,
                null,
                preview.getParamHints(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                false,
                null,
                null,
                List.of(),
                java.util.Set.of(),
                null,
                null,
                null,
                dialectOf(exampleDecision),
                exampleDecision,
                null,
                null);
        recordHistory(result, anchor);
        return result;
    }

    /**
     * @Procedure 存储过程预览（T-J16）：repository 方法注解 → CALL 渲染
     * （procedureName/value 优先、方法名兜底）+ outputParameterName/name 引用标注。
     *
     * <p>供 gutter 点击与 T-J9 调用点动作路径调用；无 @Procedure 注解返回 null。
     */
    @Nullable
    public PreviewResult previewProcedure(@NotNull PsiMethod method) {
        JpaQueryEnhancer.ProcedureInfo info = JpaQueryEnhancer.procedureInfo(method);
        if (info == null) return null;
        StringBuilder placeholders = new StringBuilder();
        for (int i = 1; i <= info.getParamCount(); i++) {
            if (placeholders.length() > 0) placeholders.append(", ");
            placeholders.append("?");
        }
        StringBuilder sql = new StringBuilder();
        sql.append("CALL ").append(info.getProcName()).append('(').append(placeholders).append(')');
        if (info.getNameRef() != null) {
            sql.append("\n-- @Procedure(name=\"").append(info.getNameRef())
                    .append("\")：引用元数据具名存储过程查询，过程名以元数据注册为准");
        }
        if (info.getOutputParameterName() != null) {
            sql.append("\n-- 输出参数：").append(info.getOutputParameterName())
                    .append("（注册为 OUT 参数，执行后按名取值）");
        }
        String sqlText = sql.toString();
        DialectDecisionEngine.Decision procedureDecision = jpaSourceDecision(method);
        PreviewResult result = new PreviewResult(
                sqlText,
                SQL_WHITESPACE_RUN.matcher(sqlText).replaceAll(" ").trim(),
                List.of(),
                null,
                null,
                info.getProcName(),
                null,
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
                null,
                Map.of(),
                null,
                false,
                null,
                null,
                List.of(),
                java.util.Set.of(),
                null,
                null,
                null,
                dialectOf(procedureDecision),
                procedureDecision,
                null,
                null);
        recordHistory(result, method);
        return result;
    }

    /**
     * 从 HQL 参数列表提取参数名（位置参数用 valueExpr，命名参数用 paramName）。
     */
    private List<String> extractHqlParamNames(@NotNull List<HqlParameter> parameters) {
        List<String> names = new ArrayList<>();
        for (HqlParameter p : parameters) {
            names.add(p.isPositional() ? p.getValueExpr() : p.getParamName());
        }
        return names;
    }

    /**
     * 从 HQL 字符串中提取参数名（支持命名参数和位置参数）。
     */
    private List<String> extractParamNamesFromHql(@NotNull String hql) {
        Set<String> named = new LinkedHashSet<>();
        Matcher m = NATIVE_NAMED_PARAM.matcher(hql);
        while (m.find()) named.add(m.group(1));
        Set<String> positional = new LinkedHashSet<>();
        Matcher pm = POSITIONAL_PARAM_INDEX.matcher(hql);
        while (pm.find()) positional.add("param" + pm.group(1));
        List<String> all = new ArrayList<>(named);
        for (String p : positional) if (!all.contains(p)) all.add(p);
        return all;
    }

    /**
     * 判断方法参数是否为数值类型（Integer/Long/BigDecimal 等）。
     */
    public boolean isNumericParam(@Nullable PsiMethod method, @NotNull String paramName) {
        if (method == null) return false;
        PsiParameter param = null;
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (paramName.equals(p.getName())) { param = p; break; }
        }
        if (param == null) return false;
        PsiType type = param.getType();
        String elementType;
        if (type instanceof PsiClassType) {
            String className = ((PsiClassType) type).getCanonicalText();
            Matcher m = GENERIC_COLLECTION_TYPE.matcher(className);
            elementType = m.find() ? m.group(1) : className;
        } else if (type instanceof PsiArrayType) {
            elementType = ((PsiArrayType) type).getComponentType().getCanonicalText();
        } else {
            elementType = type.getCanonicalText();
        }
        for (String t : NUMERIC_TYPES) {
            if (elementType.endsWith(t)) return true;
        }
        return false;
    }

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "Integer", "int", "Long", "long", "Short", "short", "Byte", "byte",
            "Float", "float", "Double", "double", "BigDecimal", "BigInteger", "Number");

    /**
     * JPA 语句风险警示：DELETE/UPDATE 无 WHERE 时提示全表删除/更新（BlockAttack 风格）。
     * T-JpaDeleteWhereGuard 普查批：补 UPDATE 分支——此前只报 DELETE，JPA 链无 WHERE 的
     * bulk UPDATE 静默（MP 链 buildWarning 两种都报，不对称）；UPDATE 的 conditions 空
     * ⟺ 文本无 WHERE（JpaUpdateParser.parseWhere 对非空 WHERE 必产 Raw 兜底），无错报面。
     */
    public String buildJpaWarning(@NotNull SqlStatement statement) {
        if (statement instanceof UpdateStatement) {
            UpdateStatement upd = (UpdateStatement) statement;
            if (upd.getConditions().isEmpty()) return "⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！";
        }
        if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            if (del.getConditions().isEmpty()) return "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！";
        }
        return null;
    }

    /**
     * 性能分析（仅 SELECT）：委托 SqlPerfAnalyzer（F-2）。
     *
     * <p>T-DialectQuoteWarn §十二：带方言 + 绑定数据源的 ANSI_QUOTES 三态分析——「MySQL 家族 + 双引号
     * 标识符」这一档只有在<b>确知连接声明了</b> {@code ANSI_QUOTES} 时才不报，否则降级 WARN（见
     * {@link #boundAnsiQuotesOrNull}）。</p>
     */
    public List<PerfWarning> analyzePerf(@NotNull String sql, @NotNull SqlStatement statement) {
        return analyzePerf(sql, statement, currentDialect());
    }

    public List<PerfWarning> analyzePerf(@NotNull String sql, @NotNull SqlStatement statement, @NotNull Dialect dialect) {
        if (!(statement instanceof SelectStatement)) return null;
        List<PerfWarning> warnings = SqlPerfAnalyzer.analyze(sql, dialect, boundAnsiQuotesOrNull());
        return warnings.isEmpty() ? null : warnings;
    }

    /**
     * T-DialectQuoteWarn §十二：绑定数据源是否<b>已声明</b> {@code sql_mode} 含 {@code ANSI_QUOTES}
     * （三态：{@code true}/{@code false}/{@code null}＝未知）。
     *
     * <p>绑定源与预览面板指示行<b>同源同优先级</b>（{@code ui.boundDataSourceNameForDisplay} 的链）：
     * 活控制台绑定 {@code DataSourceSelector.boundConsoleDataSourceName} &gt; last-used 记忆
     * {@code DataSourceSelector.rememberedDataSourceName}（面板 {@code bindstate.associated}）&gt; 未绑定
     * （null）。拿到名字后按名取 URL（{@code DataSourceUrlProbe.urlByName}），<b>不用</b>
     * {@code DataSourceUrlProbe.firstUrl}——首个数据源与绑定源可以不同且 {@code ANSI_QUOTES} 状态相反
     * （本项目 demo：首个 jpa_demo、绑定 mpj_demo），拿首个当绑定源会给出错误结论。</p>
     *
     * <p>取不到名字/URL/无法判定 → null（未知），规则据此降级 WARN（不假装知道连接选项）。
     * 全程自吞异常：无项目/无 Database 插件/反射失败一律回落 null，绝不影响预览主流程。</p>
     */
    @Nullable
    private Boolean boundAnsiQuotesOrNull() {
        String name;
        try {
            name = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSourceName();
        } catch (Throwable ignored) {
            name = null; // getOrNull 语义
        }
        if (name == null) {
            try {
                name = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.rememberedDataSourceName(project);
            } catch (Throwable ignored) {
                name = null; // getOrNull 语义
            }
        }
        if (name == null) return null;
        String url;
        try {
            url = cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe.urlByName(project, name);
        } catch (Throwable ignored) {
            return null; // getOrNull 语义
        }
        if (url == null) return null;
        try {
            return cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe.ansiQuotesFromUrl(url);
        } catch (Throwable ignored) {
            return null; // getOrNull 语义
        }
    }

    /**
     * R12 T-PaginationSim：分页 COUNT 包裹预览（D1=A 预览附第二条语句；D2=A 基础包裹形态）。
     *
     * <p>由语句模型构建预览结果时透传 {@link cn.song.mybatisplus.visualizer.model.PreviewResult#getCountSql()}
     * （面板在主 SQL 后并列渲染）。判定链与 master 同形：总开关/COUNT 子开关关、count/exists 消费、
     * searchCount=false（{@code new Page<>(c, s, false)}）、非 SELECT、无 LIMIT → 均不产出；
     * 包裹体取 **去掉 LIMIT 的原语句** 紧凑形态（运行时 COUNT 先于 LIMIT 执行）。</p>
     */
    private String paginationCountSql(@NotNull cn.song.mybatisplus.visualizer.model.WrapperChain chain,
                                      @NotNull SqlStatement statement, @NotNull Dialect dialect) {
        // settings?.state?.paginationSimEnabled == false → null（设置服务缺席时按默认开）
        if (settings != null && settings.getState() != null
                && !settings.getState().getPaginationSimEnabled()) return null;
        if (settings != null && settings.getState() != null
                && !settings.getState().getPaginationCountEnabled()) return null;
        if (chain.getCountConsumed() || chain.getExistsConsumed()) return null;
        if (!chain.getPageSearchCount()) return null;
        if (!(statement instanceof SelectStatement)) return null;
        SelectStatement select = (SelectStatement) statement;
        if (select.getLimit() == null) return null;
        SelectStatement withoutLimit = select.copy(
                select.getTable(), select.getSelectColumns(), select.getTableAlias(), select.getJoins(),
                select.isDistinct(), select.getConditions(), select.getGroupBy(), select.getHaving(),
                select.getOrderBy(), null, select.getRawFirst(), select.getRawAround(),
                select.getComment(), select.getUnions(), select.getFromSubQuery(),
                select.getFillClauses());
        String base = SqlGenerator.generate(withoutLimit, true, dialect, null);
        return PaginationSimulator.wrapCountSql(base);
    }

    /**
     * 收集语句中的动态条件行（M12）：遍历 WHERE 条件树，扁平化 toggle 元数据（含嵌套 Group 内）。
     */
    public List<ConditionView> collectConditionViews(@NotNull SqlStatement statement) {
        if (statement instanceof SelectStatement) return flatMapConditionViews(((SelectStatement) statement).getConditions());
        if (statement instanceof UpdateStatement) return flatMapConditionViews(((UpdateStatement) statement).getConditions());
        if (statement instanceof DeleteStatement) return flatMapConditionViews(((DeleteStatement) statement).getConditions());
        return List.of();
    }

    private List<ConditionView> flatMapConditionViews(@NotNull List<Condition> conditions) {
        List<ConditionView> views = new ArrayList<>();
        for (Condition c : conditions) views.addAll(toConditionViews(c));
        return views;
    }

    /**
     * T-M22-02：乐观锁条件注入（@Version 实体 + MP OptimisticLockerInnerInterceptor 语义）。
     */
    private SqlStatement injectOptimisticLockCondition(@NotNull TableInfo table, @NotNull SqlStatement statement) {
        if (!(statement instanceof UpdateStatement)) return statement;
        UpdateStatement update = (UpdateStatement) statement;
        String versionColumn = table.getVersionColumn();
        if (versionColumn == null) return update;
        for (SetClause set : update.getSets()) {
            if (set.getColumn() != null && versionColumn.equals(set.getColumn().getColumnName())) return update;
        }
        if (conditionReferencesColumn(update.getConditions(), versionColumn)) return update;
        String alias = update.getTableAlias();
        String prefix = (alias != null) ? alias + "." : "";
        List<SetClause> sets = new ArrayList<>(update.getSets());
        sets.add(new SetClause(
                new ColumnRef(alias, versionColumn, null, null, null, null, null), null,
                prefix + versionColumn + " = " + prefix + versionColumn + " + 1"));
        List<Condition> conditions = new ArrayList<>(update.getConditions());
        conditions.add(new Condition.Simple(
                new ColumnRef(alias, versionColumn, null, null, null, null, null), "=", new SqlValue.Placeholder("?"), null));
        return new UpdateStatement(update.getTable(), sets, conditions, update.getRawTail(), update.getTableAlias(), update.getJoins());
    }

    /**
     * F-6 Phase B：动态表名替换。
     */
    private SqlStatement processDynamicTableName(@NotNull TableInfo table, @NotNull SqlStatement statement) {
        if (settings == null || settings.getState() == null) return statement;
        List<TableNameRule> rules = settings.getState().getDynamicTableNameRules();
        if (rules == null) return statement;
        rules = rules.stream().filter(r -> r.getEnabled() && r.getPattern() != null && !r.getPattern().isBlank()).toList();
        if (rules.isEmpty()) return statement;
        String newTableName = DynamicTableNameProcessor.process(table.getTableName(), rules);
        if (newTableName == null) return statement;
        return rewriteTableName(statement, newTableName);
    }

    /**
     * 将语句模型中的表名替换为新的表名。
     */
    private SqlStatement rewriteTableName(@NotNull SqlStatement statement, @NotNull String newTableName) {
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            return new SelectStatement(copyTableWithName(sel.getTable(), newTableName), sel.getSelectColumns(), sel.getTableAlias(),
                    sel.getJoins(), sel.getDistinct(), sel.getConditions(), sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(),
                    sel.getLimit(), sel.getRawFirst(), sel.getRawAround(), sel.getComment(), sel.getUnions(),
                    sel.getFromSubQuery(), sel.getFillClauses());
        } else if (statement instanceof UpdateStatement) {
            UpdateStatement upd = (UpdateStatement) statement;
            return new UpdateStatement(copyTableWithName(upd.getTable(), newTableName), upd.getSets(), upd.getConditions(),
                    upd.getRawTail(), upd.getTableAlias(), upd.getJoins());
        } else if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            return new DeleteStatement(copyTableWithName(del.getTable(), newTableName), del.getConditions(), del.getDeleteTargets(),
                    del.getTableAlias(), del.getJoins());
        }
        return statement;
    }

    /**
     * 以新表名重建 {@link TableInfo}（对应 Kotlin 的 {@code tableInfo.copy(tableName = newTableName)}）。
     * 注意：Kotlin data class 的 {@code copy} 为具名参数，Java 中无单参数重载，必须显式构造。
     */
    private static @NotNull TableInfo copyTableWithName(@NotNull TableInfo table, @NotNull String newTableName) {
        return new TableInfo(table.getEntityClassName(), newTableName, table.getColumns(),
                table.getLogicDeleteColumn(), table.getVersionColumn(), table.getComment(), table.getAssociations());
    }

    /**
     * F-6 Phase C：数据权限模拟条件注入。
     */
    private SqlStatement injectDataPermissionCondition(@NotNull TableInfo table, @NotNull SqlStatement statement) {
        if (settings == null || settings.getState() == null || !settings.getState().getDataPermissionHint()) return statement;
        return DataPermissionPlaceholder.injectMockCondition(statement, settings.getState().getDataPermissionMockCondition());
    }

    /**
     * T-TenantLine：检测调用链所在方法是否标注 @InterceptorIgnore(tenantLine=true)。
     */
    private boolean hasInterceptorIgnoreAnnotation(@NotNull PsiElement anchor, @NotNull String attrName) {
        try {
            PsiMethod method = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class);
            if (method == null) return false;
            PsiModifierList modifierList = method.getModifierList();
            if (modifierList == null) return false;
            PsiAnnotation[] annotations = modifierList.getAnnotations();
            if (annotations == null) return false;
            for (PsiAnnotation annotation : annotations) {
                PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
                String simpleName = (ref != null) ? ref.getReferenceName() : null;
                if ("InterceptorIgnore".equals(simpleName)) {
                    PsiAnnotationMemberValue value = annotation.findAttributeValue(attrName);
                    if (value instanceof PsiLiteralExpression) {
                        Object v = ((PsiLiteralExpression) value).getValue();
                        if (Boolean.TRUE.equals(v)) return true;
                    }
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * T-M29-03：分页 LIMIT 注入（仅 SELECT 语句生效）。
     */
    private SqlStatement applyPageLimit(@NotNull SqlStatement statement, @Nullable LimitClause pageLimit) {
        if (pageLimit == null) return statement;
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(), sel.getDistinct(),
                    sel.getConditions(), sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(), pageLimit, sel.getRawFirst(),
                    sel.getRawAround(), sel.getComment(), sel.getUnions(), sel.getFromSubQuery(), sel.getFillClauses());
        }
        return statement;
    }

    /**
     * T-M22-02：条件树是否已提及指定列。
     */
    public boolean conditionReferencesColumn(@NotNull List<Condition> conditions, @NotNull String columnName) {
        for (Condition condition : conditions) {
            if (condition instanceof Condition.Simple) {
                if (((Condition.Simple) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Unary) {
                if (((Condition.Unary) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.In) {
                if (((Condition.In) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Between) {
                if (((Condition.Between) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Like) {
                if (((Condition.Like) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.ColumnComparison) {
                Condition.ColumnComparison cc = (Condition.ColumnComparison) condition;
                if (cc.getLeft().getColumnName().equals(columnName) || cc.getRight().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Group) {
                if (conditionReferencesColumn(((Condition.Group) condition).getChildren(), columnName)) return true;
            }
        }
        return false;
    }

    /**
     * 单个条件节点 → 条件视图（Kotlin 扩展函数 toConditionViews 的 Java 等价实现）。
     */
    private List<ConditionView> toConditionViews(@NotNull Condition condition) {
        ConditionToggle ownToggle = condition.getToggle();
        if (condition instanceof Condition.Group && ownToggle != null) {
            List<String> sources = groupValueSources(condition);
            List<ConditionView> views = new ArrayList<>();
            views.add(new ConditionView(ownToggle.getId(), ownToggle.getLabel(), ownToggle.getFlagSource(),
                    ownToggle.getDefaultEnabled(), sources.isEmpty() ? null : sources.get(0), sources));
            return views;
        }
        ConditionView own = null;
        if (ownToggle != null) {
            List<String> sources = ownToggle.getValueSources();
            List<String> finalSources = sources.isEmpty()
                    ? (ownToggle.getValueSource() != null ? List.of(ownToggle.getValueSource()) : List.of())
                    : sources;
            own = new ConditionView(ownToggle.getId(), ownToggle.getLabel(), ownToggle.getFlagSource(),
                    ownToggle.getDefaultEnabled(), ownToggle.getValueSource(), finalSources);
        }
        List<ConditionView> nested = new ArrayList<>();
        if (condition instanceof Condition.Group) {
            for (Condition child : ((Condition.Group) condition).getChildren()) {
                nested.addAll(toConditionViews(child));
            }
        }
        List<ConditionView> result = new ArrayList<>();
        if (own != null) result.add(own);
        result.addAll(nested);
        return result;
    }

    /**
     * T-M29.1：组内子条件值来源递归收集。
     */
    private List<String> groupValueSources(@NotNull Condition condition) {
        List<String> result = new ArrayList<>();
        if (condition instanceof Condition.Simple) {
            SqlValue v = ((Condition.Simple) condition).getValue();
            if (v instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) v).getSource());
        } else if (condition instanceof Condition.Like) {
            SqlValue v = ((Condition.Like) condition).getPattern();
            if (v instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) v).getSource());
        } else if (condition instanceof Condition.Between) {
            Condition.Between b = (Condition.Between) condition;
            if (b.getLo() instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) b.getLo()).getSource());
            if (b.getHi() instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) b.getHi()).getSource());
        } else if (condition instanceof Condition.In) {
            for (SqlValue v : ((Condition.In) condition).getValues()) {
                if (v instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) v).getSource());
            }
        } else if (condition instanceof Condition.Group) {
            for (Condition child : ((Condition.Group) condition).getChildren()) result.addAll(groupValueSources(child));
        }
        // 去重保持渲染顺序
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    /**
     * O-5：当前方言（配置未加载时默认 MySQL）；T-DialectAutoInfer：auto 开启时智能推断。
     *
     * <p>T-TS-FixC：**方言模式（auto/manual）不在本层判定**，整段委托 {@link DialectRegistry#effective}，
     * 本方法只负责把宿主 project 传下去。为什么不像其余 effective* 那样写
     * 「项目值 ?: 全局值」两段式：方言的模式判定是**跨层**的——项目级 auto 与全局 auto 参与同一次
     * 判定（项目级 true 必须压过全局 false），而项目级手动方言名又要压过全局手动值；拆成
     * 「先在本层算 auto、再让下层算 manual」就会出现两个事实源，正是本次缺陷的成因
     * （本层按项目级判 auto=true 进 effective，下层却按全局 false 返回全局手动值）。
     * 收口后优先级语义见 {@code DialectRegistry.effective} 的 KDoc：模式与手动值均「项目级 &gt; 全局」。
     */
    @NotNull
    public Dialect currentDialect() {
        return DialectRegistry.INSTANCE.effective(project);
    }

    /**
     * 查询类 Wrapper 类型（可能被删除类消费方法复用为删除条件，M10）。
     */
    private boolean isQueryLike(@NotNull WrapperType type) {
        return type == WrapperType.LAMBDA_QUERY
                || type == WrapperType.QUERY
                || type == WrapperType.MPJ_LAMBDA
                || type == WrapperType.MPJ_QUERY
                || type == WrapperType.MPJ_LAMBDA_QUERY;
    }

    /**
     * 查询类解析统一入口：优先走 ConverterRegistry 注册的 converter（保留主表别名与 JOIN）。
     */
    private SelectStatement parseQueryLike(@NotNull WrapperChain chain, @NotNull TableInfo table) {
        // T-FS-04：显式传本服务绑定的 project（语义同 else -> ConverterRegistry.convert 分支）
        SqlStatement converted = ConverterRegistry.INSTANCE.convert(chain, table, project);
        if (converted instanceof SelectStatement) return (SelectStatement) converted;
        return new QueryWrapperParser(table).parse(chain);
    }

    /**
     * 逻辑删除注入状态（T-M20-05）：驱动 injectLogicDeleteCondition。
     */
    public static final class LogicDeleteState {
        public final boolean mainActive;
        public final boolean subActive;
        public final boolean subToWhere;

        LogicDeleteState() {
            this(true, true, false);
        }

        LogicDeleteState(boolean mainActive, boolean subActive, boolean subToWhere) {
            this.mainActive = mainActive;
            this.subActive = subActive;
            this.subToWhere = subToWhere;
        }
    }

    /**
     * 逻辑删除控制求值（T-M20-05，对齐 MPJ 官方六方法语义）。
     */
    private LogicDeleteState evaluateLogicDeleteState(@NotNull List<WrapperCall> calls) {
        LogicDeleteState state = new LogicDeleteState();
        for (WrapperCall call : calls) {
            switch (call.getMethodName()) {
                case METHOD_DISABLE_LOGIC_DEL:
                    state = new LogicDeleteState(false, state.subActive, state.subToWhere);
                    break;
                case METHOD_ENABLE_LOGIC_DEL:
                    state = new LogicDeleteState(true, state.subActive, state.subToWhere);
                    break;
                case METHOD_DISABLE_SUB_LOGIC_DEL:
                    state = new LogicDeleteState(state.mainActive, false, state.subToWhere);
                    break;
                case METHOD_ENABLE_SUB_LOGIC_DEL:
                    state = new LogicDeleteState(state.mainActive, true, state.subToWhere);
                    break;
                case METHOD_LOGIC_DEL_TO_ON:
                    state = new LogicDeleteState(state.mainActive, state.subActive, false);
                    break;
                case METHOD_LOGIC_DEL_TO_WHERE:
                    state = new LogicDeleteState(state.mainActive, state.subActive, true);
                    break;
                default:
                    break;
            }
        }
        return state;
    }

    /**
     * T-HqlInhCols：按 {@code @Entity(name=...)} 实体别名查找实体类。
     *
     * <p>HQL {@code FROM Alias} 的 Alias 可为实体名（JPA 语义）而非类名——
     * 类名快路径（findClass/findClassByShortName）未命中时按注解值反查。
     * 扫描结果（实体名 → 全限定名）缓存于 Project userData，以
     * PsiModificationTracker.modificationCount 失效（照抄 EntityClassFinder 的
     * {@code @TableName} 反查同款缓存惯例；未命中也入缓存防重复全量扫描）。
     */
    @Nullable
    public PsiClass findClassByEntityName(@NotNull Project project, @NotNull String entityName, @NotNull GlobalSearchScope scope) {
        return findClassByEntityName(project, entityName, scope, null);
    }

    @Nullable
    public PsiClass findClassByEntityName(@NotNull Project project, @NotNull String entityName,
                                          @NotNull GlobalSearchScope scope, @Nullable PsiElement context) {
        PsiClass candidate = cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep.resolve(project, entityName, context).psiClass();
        return candidate != null && candidate.getContainingFile() != null && candidate.getContainingFile().getVirtualFile() != null
                && scope.contains(candidate.getContainingFile().getVirtualFile()) ? candidate : null;
    }

    /**
     * 从 HQL 提取实体类名（T-HqlUpdateEntityMap S1 起委托共享提取器）。
     * 适用于 createQuery(HQL) 单参数场景（无 Entity.class 二参）；
     * 语句头优先口径——UPDATE/DELETE 头同样命中，不再只认 FROM 子句。
     */
    public String extractEntityNameFromHql(@NotNull String hql) {
        return HqlEntityNameExtractor.extract(hql);
    }

    /**
     * 将 HBM XML 映射转换为 TableInfo。
     */
    public TableInfo convertHbmMappingToTableInfo(@NotNull HbmXmlMapping.ClassMapping mapping) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (HbmXmlMapping.PropertyMapping prop : mapping.getProperties()) {
            columns.add(new ColumnInfo(prop.getFieldName(), prop.getColumnName(), prop.isId(), false, null, null, null, ColumnRole.BUSINESS));
        }
        List<AssociationInfo> associations = new ArrayList<>();
        for (HbmXmlMapping.AssociationMapping assoc : mapping.getAssociations()) {
            String targetSimpleName = substringAfterLast(assoc.getTargetClassName(), '.');
            associations.add(new AssociationInfo(assoc.getFieldName(), assoc.getJoinColumn(), targetSimpleName,
                    decapitalize(targetSimpleName)));
        }
        return new TableInfo(mapping.getEntityClassName(), mapping.getTableName(), columns, null, null, null, associations);
    }

    /**
     * 按短类名查找 PsiClass。
     */
    public PsiClass findClassByShortName(@NotNull Project project, @NotNull String shortName, @NotNull GlobalSearchScope scope) {
        return findClassByShortName(project, shortName, scope, null);
    }

    @Nullable
    public PsiClass findClassByShortName(@NotNull Project project, @NotNull String shortName,
                                         @NotNull GlobalSearchScope scope, @Nullable PsiElement context) {
        PsiClass candidate = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.resolveClassByName(project, shortName, context);
        return candidate != null && candidate.getContainingFile() != null && candidate.getContainingFile().getVirtualFile() != null
                && scope.contains(candidate.getContainingFile().getVirtualFile()) ? candidate : null;
    }

    /**
     * 清空缓存（编辑器文件切换/项目关闭时由持有方调用）。
     */
    public void clearCache() {
        cache.clear();
    }

    /** Wrapper 解析缓存条目数（设置页缓存统计，PluginCacheManager 调用）。 */
    public int wrapperCacheEntryCount() {
        return cache.size();
    }

    /** Wrapper 解析缓存大小估算（设置页缓存统计，PluginCacheManager 调用）。 */
    public long wrapperCacheSizeBytes() {
        return cache.sizeBytes();
    }

    /**
     * F-7：预览完成后自动记录历史。
     */
    private void recordHistory(@NotNull PreviewResult result, @NotNull PsiElement anchor) {
        try {
            SqlHistoryManager manager = SqlHistoryManager.getInstance();
            manager.setMaxSize((settings != null && settings.getState() != null)
                    ? settings.getState().getHistoryMaxSize() : SqlHistoryManager.DEFAULT_MAX_SIZE);
            PsiFile file = anchor.getContainingFile();
            String sourceFile = (file != null) ? file.getName() : "";
            int sourceLine;
            String text = (file != null) ? file.getText() : null;
            int offset = anchor.getTextRange().getStartOffset();
            if (text != null) {
                int count = 0;
                int lim = Math.min(offset, text.length());
                for (int i = 0; i < lim; i++) {
                    if (text.charAt(i) == '\n') count++;
                }
                sourceLine = count + 1;
            } else {
                sourceLine = 0;
            }
            String tableName = (result.getTableName() != null) ? result.getTableName() : "";
            // 尝试从最近日志条目补全耗时（SQL 指纹匹配；R54 两级化——exact 优先＋结构指纹兜底）
            // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建）；取不到即不做耗时补全（等同未命中）
            Long executionTimeMs = null;
            SqlLogManager logManager = SqlLogManagerLookupKt.sqlLogManagerIfCreated(anchor.getProject());
            if (logManager != null) {
                executionTimeMs = backfillExecutionTimeMs(logManager.getEntries().snapshot(), result.getSql());
            }
            SqlHistoryEntry entry = new SqlHistoryEntry("", result.getSql(), result.getCompactSql(), System.currentTimeMillis(),
                    sourceFile, sourceLine, tableName, false, List.of());
            entry.setExecutionTimeMs(executionTimeMs);
            entry.setProjectIdentity(cn.song.mybatisplus.visualizer.history.ExplainPlanKey
                    .projectIdentity(anchor.getProject().getBasePath()));
            // T-PresetMethodScope：方法身份随结果与历史条目落库——面板据此算带作用域的缓存键，
            // R51 历史快照回落按同一口径匹配条目。
            String originScopeKey;
            try {
                originScopeKey = cn.song.mybatisplus.visualizer.psi.PreviewOriginScope.of(
                        PsiTreeUtil.getParentOfType(anchor, PsiMethod.class, false),
                        anchor.getProject());
            } catch (Throwable t) {
                originScopeKey = "";
            }
            result.setOriginScopeKey(originScopeKey);
            entry.setOriginScopeKey(originScopeKey);
            result.setHistoryEntryId(manager.add(entry).getId());
        } catch (Throwable e) {
            // 服务不可用时静默跳过（不阻塞预览）
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String blank(@Nullable String s) {
        return (s == null || s.isBlank()) ? "" : s;
    }

    private static String substringAfterLast(@NotNull String s, char c) {
        int idx = s.lastIndexOf(c);
        return (idx >= 0) ? s.substring(idx + 1) : s;
    }

    private static String decapitalize(@NotNull String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    private static <T> T safeGet(@NotNull ThrowableSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable e) {
            return null;
        }
    }

    private interface ThrowableSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * 风险警示（F-09/F-10 + F-6 Phase C BlockAttack 增强 + DataPermission 占位提示
     * + T-UpdateSetSqlConcat SET 解析失败留痕）。
     */
    public String buildWarning(@NotNull SqlStatement statement) {
        String baseWarning = null;
        if (statement instanceof UpdateStatement) {
            if (((UpdateStatement) statement).getConditions().isEmpty()) {
                baseWarning = "⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！";
            }
        } else if (statement instanceof DeleteStatement) {
            if (((DeleteStatement) statement).getConditions().isEmpty()) {
                baseWarning = "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！";
            }
        }
        // T-UpdateSetSqlConcat：SET 片段解析失败留痕（`setSql(sqlVar)` 这类纯运行期片段静态不可还原）。
        // 省略是唯一正确选择，但不能静默产出「无 SET」的非法 SQL——用户至少要看到少了什么。
        String setWarning = null;
        if (statement instanceof UpdateStatement) {
            List<String> unparsedSets = ((UpdateStatement) statement).getUnparsedSets();
            if (unparsedSets != null && !unparsedSets.isEmpty()) {
                setWarning = "⚠ [预览] 有 " + unparsedSets.size() + " 处 SET 片段无法静态解析，已省略："
                        + String.join("；", unparsedSets);
            }
        }
        String dataPermissionWarning = DataPermissionPlaceholder.buildWarning(
                settings != null && settings.getState() != null && settings.getState().getDataPermissionHint());
        List<String> parts = new ArrayList<>();
        if (baseWarning != null) parts.add(baseWarning);
        if (setWarning != null) parts.add(setWarning);
        if (dataPermissionWarning != null) parts.add(dataPermissionWarning);
        String joined = String.join("\n", parts);
        return joined.isEmpty() ? null : joined;
    }

    // ------------------------------------------------------------------
    // 伴侣对象（companion object）等价静态成员
    // ------------------------------------------------------------------

    public static final Logger LOG = Logger.getInstance(SqlPreviewService.class);

    /**
     * R54（O-108）：历史耗时回填匹配键两级化——exact 命中优先（原全文精确匹配，现行为
     * 零回退），未命中（或命中条目无耗时值）改比 {@link LogSqlEntry#getStructureKey}
     * （{@code LogSqlModelsKt.structureFingerprint}：字面量/数字参数化，{@code ?} 占位与实值归一同
     * token——V81① 判实在案）取同键<b>中位数</b>兜底（V81③：同模板循环 N 次时首条可能是冷启动
     * 离群值，中位数取样代表性优于 firstOrNull；无耗时的同键条目不参与取样）。
     * O-108 根因：10.6 起预览默认空参填 {@code ?}，全文匹配对实值日志必失配——耗时列默认
     * 配置恒空。
     */
    public static Long backfillExecutionTimeMs(@NotNull List<LogSqlEntry> entries, @NotNull String previewSql) {
        for (LogSqlEntry e : entries) {
            if (previewSql.equals(e.restoredSql())) {
                Long exact = e.executionTimeMs();
                if (exact != null) return exact;
                break;
            }
        }
        String key = LogSqlModelsKt.structureFingerprint(previewSql);
        List<Long> durations = new ArrayList<>();
        for (LogSqlEntry e : entries) {
            Long duration = e.executionTimeMs();
            if (duration == null) continue;
            if (key.equals(e.structureKey())) durations.add(duration);
        }
        if (durations.isEmpty()) return null;
        durations.sort(Long::compare);
        int n = durations.size();
        return n % 2 == 1 ? durations.get(n / 2) : (durations.get(n / 2 - 1) + durations.get(n / 2)) / 2L;
    }

    /**
     * T-PreviewFast P7：最近一次预览请求的起始时刻（点击/快捷键入口记时）。三段耗时的锚点：
     * 请求 → 弹窗（{@link #showResult} 记）→ 控制台可见（面板 embedConsole 记）。仅诊断用，
     * 无锁 volatile 读写，取不到 0 即忽略。
     */
    public static volatile long lastPreviewRequestMs = 0L;

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** 逻辑删除控制方法名（T-M20-05，MPJ 官方六方法）。 */
    private static final String METHOD_DISABLE_LOGIC_DEL = "disableLogicDel";
    private static final String METHOD_ENABLE_LOGIC_DEL = "enableLogicDel";
    private static final String METHOD_DISABLE_SUB_LOGIC_DEL = "disableSubLogicDel";
    private static final String METHOD_ENABLE_SUB_LOGIC_DEL = "enableSubLogicDel";
    private static final String METHOD_LOGIC_DEL_TO_ON = "logicDelToOn";
    private static final String METHOD_LOGIC_DEL_TO_WHERE = "logicDelToWhere";

    /** 默认租户列名（T-TenantLine）：tenantColumnName 空白时回退此值。 */
    public static final String DEFAULT_TENANT_COLUMN = "tenant_id";

    /** SQL 子句关键字（detectMainAlias 探测主表别名时排除的关键字形态）。 */
    private static final Set<String> SQL_CLAUSE_KEYWORDS = Set.of(
            "WHERE", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "JOIN", "ON", "CROSS",
            "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "UNION", "SET", "AS"
    );

    /** 联表更新/删除 Wrapper 类型集合（T-M21-08）。 */
    private static final Set<WrapperType> UPDATE_DELETE_JOIN_TYPES = Set.of(WrapperType.UPDATE_JOIN, WrapperType.DELETE_JOIN);

    /**
     * 统一展示入口（M10-03）：恒以弹窗（SqlPreviewDialog）展示预览结果。
     *
     * <p>T-RefreshDbSteal F2：弹窗单例化——同项目已有预览弹窗时先 dispose 旧弹窗再开新。
     * 旧实现每次调用都 new 一个 MODELESS 弹窗且从不关闭，多弹窗各自持有 rehost 监听，
     * 互相抢占插件级共享控制台（真机 6 连抢根因）；单例化后同一时刻每个项目至多一个
     * 预览弹窗存活，旧面板的监听随 dispose 显式移除（F3），僵尸源头消灭。
     * 代价：旧弹窗的滚动/尺寸状态不保留（新弹窗重新初始化，尺寸走 DimensionService 记忆）。
     *
     * @param result         null 表示未找到可转换的 Wrapper → 弹友好提示（不抛异常）
     * @param sourceLocation 当前代码位置（文件名:行号），来源列默认值；null 表示未知
     */
    public static void showResult(@NotNull Project project, @Nullable PreviewResult result, @Nullable String sourceLocation) {
        // T-PreviewFast P7：请求→弹窗耗时（第二段锚点；后续控制台可见由面板记）
        try {
            long req = lastPreviewRequestMs;
            if (req > 0 && result != null) {
                LOG.info("[T-PreviewFast] request→dialog: " + (System.currentTimeMillis() - req) + "ms");
            }
        } catch (Throwable ignored) {
            // 与原 runCatching 等价（诊断打点不阻断主流程）
        }
        if (result == null) {
            // T-UiNative T-UN-02：未找到可转换 Wrapper 提示改轻通知（原模态弹窗——纯告知无需打断）；
            // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    new Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("action.notFound.title"),
                            MpSqlVisualizerBundle.message("action.notFound.message"),
                            NotificationType.INFORMATION
                    ).notify(project);
                } catch (Throwable ignored) {
                    // headless 无通知系统依赖，与原 runCatching 等价
                }
            }
            return;
        }
        // 关旧开新：旧弹窗 dispose 级联释放面板（rehost 监听随之移除），再开新弹窗
        WeakReference<SqlPreviewDialog> previousRef = ACTIVE_PREVIEW_DIALOGS.get(project);
        SqlPreviewDialog previous = previousRef != null ? previousRef.get() : null;
        if (previous != null) {
            if (previous.isDisposed()) {
                LOG.info("[M36] preview dialog singleton: previous dialog already disposed");
            } else {
                LOG.info("[M36] preview dialog singleton: closing previous dialog (id=" + System.identityHashCode(previous) + ")");
                try {
                    previous.doDispose();
                } catch (Throwable t) {
                    // 关旧弹窗失败不阻断开新
                }
            }
        }
        SqlPreviewDialog dialog = new SqlPreviewDialog(project, result, sourceLocation);
        ACTIVE_PREVIEW_DIALOGS.put(project, new WeakReference<>(dialog));
        dialog.show();
    }

    /** T-RefreshDbSteal F2：项目 → 当前存活的预览弹窗（弱引用——弹窗被用户关闭后不阻回收）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Project, WeakReference<SqlPreviewDialog>> ACTIVE_PREVIEW_DIALOGS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** T-RefreshDbSteal F2：测试访问器——当前项目登记的存活预览弹窗（单例化断言用）。 */
    public static SqlPreviewDialog activePreviewDialogForTest(@NotNull Project project) {
        WeakReference<SqlPreviewDialog> ref = ACTIVE_PREVIEW_DIALOGS.get(project);
        return ref != null ? ref.get() : null;
    }

    /**
     * 后台线程 + 读操作执行转换，EDT 回调结果（Kotlin 协程的 Java 等价实现）。
     *
     * <p>PSI 定位/解析在 IDEA 2025.3+ 属于"慢操作"，禁止在 EDT 上执行；
     * 按钮/快捷键/编辑器跟随等 UI 触发方统一走此入口，避免 "Slow operations are prohibited on EDT"。
     *
     * @param project   项目
     * @param compute   在 ReadAction 内执行转换（锚点元素可先在 EDT 捕获，块内读取）
     * @param onResult 在 EDT 上回调转换结果（未找到 Wrapper 时为 null）
     */
    public static void previewAsync(
            @NotNull Project project,
            @NotNull Function0<? extends PreviewResult> compute,
            @NotNull Function1<? super PreviewResult, Unit> onResult) {
        // T-PreviewFast P7：三段耗时埋点起点（点击/快捷键触发即记时）——真机排障时直接给出
        // 「请求→弹窗」「弹窗→控制台可见」两段数字，取代靠多条阶段日志手工推时序
        lastPreviewRequestMs = System.currentTimeMillis();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            PreviewResult result;
            try {
                result = ReadAction.compute(() -> compute.invoke());
            } catch (Throwable e) {
                // T-FatalPassthrough T-FP-02b（独立复核条件项，收口原豁免）：本处是**预览主链**
                // （点击/快捷键 → PSI 转换 → 弹窗），不是「可选能力探测」——原豁免理由把主链写成
                // 探测，属借豁免放走真该收口的站点。fatal（VME/LinkageError/ThreadDeath）透传给池
                // 线程平台处理器（原状：任何失败都返回 null，用户只看到「未找到可转换的 Wrapper」
                // 提示，真实异常零日志且归因错误——真机排障无入口）。
                // 非 fatal 仍返回 null（既有降级语义逐字不变），但补一条 WARN 留排查锚点。
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(e)) throw e;
                LOG.warn("[M36] previewAsync compute FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                result = null;
            }
            final PreviewResult r = result;
            // T-ExecSlowOps F2：确定性 SQL（无参数开关/批量/XML 模型）预览成功即后台预热
            // exec 临时文件（写盘→refresh→方言映射→findFile→填缓存槽），弹窗打开后
            // prepareDefaultConsole 命中缓存，EDT 冷路径（refresh+findFile 慢操作 SEVERE 本体）
            // 退化为兜底。独立池线程 fire-and-forget——预热含 VFS refresh 有界耗时，不得推迟
            // 弹窗；带参/批量/XML 预览 SQL 随面板勾选变化，预热内容不可信，跳过。
            if (r != null && r.getPreviewModel() == null && r.getBatchModel() == null
                    && r.getXmlModel() == null
                    && (r.getFillClauses() == null || r.getFillClauses().isEmpty())
                    && cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()) {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFile(
                                project, r.getSql(),
                                r.getEffectiveDialect() != null ? r.getEffectiveDialect().getName() : null);
                    } catch (Throwable t) {
                        // T-FatalPassthrough T-FP-02b（独立复核阻断 2 同族）：本 runCatching 包住整条
                        // 预热链——不放行 fatal 会让 DataSourceSelector 预热链的 6 处透传（缓存命中
                        // 刷新 / 写盘 / pre-refresh 收敛 / refresh / syncVf / findPsi）全部作废。
                        // 非 fatal 仍 warn 降级（fire-and-forget 语义不变）；fatal 透传到池线程
                        // 平台处理器记录现场。
                        if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t;
                        LOG.warn("[M36] exec prewarm dispatch failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                });
            }
            ApplicationManager.getApplication().invokeLater(() -> onResult.invoke(r));
        });
    }

    /**
     * 简洁重载：无 sourceLocation 的展示入口。
     */
    public static void showResult(@NotNull Project project, @Nullable PreviewResult result) {
        showResult(project, result, null);
    }
}
