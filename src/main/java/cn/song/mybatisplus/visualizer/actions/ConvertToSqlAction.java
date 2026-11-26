package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator;
import com.intellij.psi.PsiClass;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator;
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.PagesXmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTarget;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.XmlInlineSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.gutter.PagesXmlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.StringSqlMethodGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlInlineSqlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets;
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 转换 Action（M3-02，F-04 右键菜单 + F-05 快捷键）。
 *
 * 触发方式（plugin.xml 注册）：
 *  - 编辑器右键菜单：EditorPopupMenu → "MP-SQL-Preview → Convert to SQL"
 *  - 快捷键：默认 Ctrl+Alt+S（Windows/Linux）/ Cmd+Option+S（macOS）
 *
 * 行为（T-M23.7）：
 *  - 选择代码块 → 扫描选择区内全部 Wrapper 链首：0 个 → 未找到提示；1 个 → 直接预览；
 *    多个 → SqlSelectionDialog 选择后预览；
 *  - 光标处 → 取光标 PSI 元素 → SqlPreviewService.preview（内部定位增强）。
 * 未找到 Wrapper 时给出友好提示，不抛异常。
 */
public final class ConvertToSqlAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (file == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) return;

        // F-46：当前代码位置（文件名:行号），来源列默认值（XML/Java 共用）
        String sourceLocation = file.getName() + ":" + (editor.getDocument().getLineNumber(editor.getCaretModel().getOffset()) + 1);

        // T-ErrorLocate T-EL-03：登记可解析源锚点（文件+0 基行），执行失败时错误定位动作消费。
        // 单槽按项目覆盖（预览弹窗每项目至多一个，同刻只有一个错误面板可定位）。
        try {
            com.intellij.openapi.vfs.VirtualFile vf = file.getVirtualFile();
            if (vf != null) {
                int line0 = editor.getDocument().getLineNumber(editor.getCaretModel().getOffset());
                project.getService(SqlPreviewService.class).setLastPreviewSourceAnchor(
                        new SqlPreviewService.SourceAnchor(vf, line0));
            }
        } catch (Throwable ignored) {
        }

        // T-M24-04：XML mapper 文件 → 预览光标所在语句；F-45：<pages> XML 文件 → maintainPage 预览
        if (file instanceof XmlFile) {
            XmlFile xmlFile = (XmlFile) file;
            XmlTag rootTag = xmlFile.getRootTag();
            if (rootTag != null && "pages".equals(rootTag.getName())) {
                SelectionModelOrSelection sm = new SelectionModelOrSelection(editor);
                if (sm.hasSelection) {
                    previewPagesSelection(project, xmlFile, editor, sm.selectionStart, sm.selectionEnd);
                } else {
                    previewPagesStatement(project, xmlFile, editor);
                }
                return;
            }
            SelectionModelOrSelection sm = new SelectionModelOrSelection(editor);
            if (sm.hasSelection) {
                previewXmlSelection(project, xmlFile, editor, sm.selectionStart, sm.selectionEnd, sourceLocation);
            } else {
                previewXmlStatement(project, xmlFile, editor, sourceLocation);
            }
            return;
        }

        SqlPreviewService service = project.getService(SqlPreviewService.class);

        // 分支 A（T-M23.7）：选择代码块 → 扫描选择区内 SQL 候选（wrapper 链首 + 批量方法），0/1/多分发
        SelectionModelOrSelection sm = new SelectionModelOrSelection(editor);
        if (sm.hasSelection) {
            final int selStart = sm.selectionStart;
            final int selEnd = sm.selectionEnd;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                Pair<List<PsiMethodCallExpression>, List<SqlSelectionDialog.Row>> payload;
                try {
                    payload = ReadAction.compute(() -> {
                        List<PsiMethodCallExpression> heads =
                                WrapperLocator.sqlCandidatesInRange(file, selStart, selEnd);
                        List<SqlSelectionDialog.Row> rows = heads.stream().map(head ->
                                SqlSelectionDialog.Row.of(
                                        WrapperLocator.sqlCandidateSummary(head),
                                        WrapperLocator.sqlCandidateLineText(head))
                        ).collect(Collectors.toList());
                        return Pair.create(heads, rows);
                    });
                } catch (Throwable t) {
                    payload = Pair.create(Collections.emptyList(), Collections.emptyList());
                }
                List<PsiMethodCallExpression> chainHeads = payload.getFirst();
                List<SqlSelectionDialog.Row> displayTexts = payload.getSecond();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    if (chainHeads.isEmpty()) {
                        // T-M29.6：选择区内无 Wrapper 候选 → 回落选择区内 SQL 注解候选
                        showAnnotationSelectionCandidates(project, file, editor, selStart, selEnd, sourceLocation);
                    } else if (chainHeads.size() == 1) {
                        preview(service, chainHeads.get(0), project, sourceLocation);
                    } else {
                        new SqlSelectionDialog<>(project, chainHeads, displayTexts, (PsiMethodCallExpression head) -> {
                            // 选中确定 → 编辑器定位（光标到行首 + 滚动居中 + 整行选中）→ 预览 SQL
                            locateEditorTo(editor, head);
                            preview(service, head, project, sourceLocation);
                        }).show();
                    }
                });
            });
            return;
        }

        // 分支 B（现状）：光标处定位（内部增强：eq/list 调用处同样可触发）
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null) return;

        // T-M25-03：光标在 MyBatis SQL 注解内 → 注解预览
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
        if (annotation != null && AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(annotation)) {
            previewSqlAnnotation(project, annotation, sourceLocation);
            return;
        }

        // PSI 转换在后台线程执行（EDT 上慢操作会被 2025.3+ 禁止），回调在 EDT 渲染
        // T-PreviewFallbackEdtSlowOps（2026-11-26 真机 SEVERE「Slow operations are prohibited on EDT」，
        // 平台点名本动作）：Mapper XML 兜底的锚点解析走自定义 fileBasedIndex
        // （`MapperNamespaceIndex` → `FileBasedIndex.getContainingFiles`），必须在 compute 块（BGT +
        // ReadAction）里做；放到下面的 onResult 回调里就是在 EDT 上查索引。回调只消费这里的预解析结果。
        java.util.concurrent.atomic.AtomicReference<List<XmlTag>> mapperXmlTags =
                new java.util.concurrent.atomic.AtomicReference<>(Collections.emptyList());
        SqlPreviewService.previewAsync(project,
                () -> {
                    PreviewResult result;
                    try {
                        result = service.preview(element);
                    } catch (Throwable t) {
                        // compute 抛错时回调仍会走兜底链（既有语义）——异常分支同样要备料，
                        // 否则 XML 档会在"预览计算失败"这条路径上静默失效。
                        mapperXmlTags.set(mapperXmlTagsFor(element));
                        throw sneakyThrow(t);
                    }
                    // 只有 Wrapper 链未命中才需要兜底锚点：命中路径零额外索引查询。
                    if (result == null) mapperXmlTags.set(mapperXmlTagsFor(element));
                    return result;
                },
                (PreviewResult result) -> {
                    if (result != null) {
                        SqlPreviewService.showResult(project, result, sourceLocation);
                    } else if (!tryMapperXmlMethodFallback(project, mapperXmlTags.get(), sourceLocation)
                            && !tryMapperAnnotationMethodFallback(project, element, sourceLocation)
                            && !tryIbatisCallFallback(project, element, sourceLocation)
                            && !tryIbatisMethodFallback(project, element, sourceLocation)
                            && !tryHqlFallback(service, project, element, sourceLocation)
                            && !tryNamedQueryFallback(service, project, element, sourceLocation)
                            && !tryJdbcTemplateFallback(project, element, sourceLocation)
                            && !tryJpaFallback(service, project, element, sourceLocation)
                            && !tryMethodBodyStringSqlFallback(project, element, sourceLocation)) {
                        // 均未命中 → 回落文件级 SQL 注解候选
                        showAnnotationCandidates(project, file, editor, sourceLocation);
                    }
                    return Unit.INSTANCE;
                });
    }

    /** 受检异常透传（master 的 Kotlin `throw t` 在 Java 侧只能经泛型擦除等价实现）。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Mapper XML 兜底的锚点：光标处接口方法 → 同 namespace XML 里的同名语句（含 {@code databaseId} 变体）。
     *
     * <p><b>只许在后台线程的 ReadAction 内调用</b>：解析走自定义 fileBasedIndex
     * （{@code MapperNamespaceIndex.getMapperXmlFiles} → {@code FileBasedIndex.getContainingFiles}），EDT 上调它
     * 就是 {@code SlowOperations} 断言源（2026-11-26 真机 SEVERE，见
     * {@code 20261126-1514-T-PreviewFallbackEdtSlowOps} 计划）。非 fatal 异常吞成空表——尤其
     * {@code IndexNotReadyException} 不得冒泡到 {@code previewAsync}（它会把「预览无命中」变成 runWhenSmart
     * 重试，改变语义）。</p>
     *
     * <p>{@code public} 供测试直调（无 UI 副作用）。</p>
     */
    public List<XmlTag> mapperXmlTagsFor(PsiElement element) {
        try {
            com.intellij.psi.PsiMethod method = MapperNavigationTargets.nearestMapperMethod(element);
            return method != null
                    ? MapperNavigationTargets.statementTagsFor(method)
                    : Collections.emptyList();
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw (Error) t;
            return Collections.emptyList();
        }
    }

    /**
     * Mapper XML 绑定兜底（T-MapperMethodSqlPreview，2026-11-26）：光标在 Mapper <b>接口方法</b>上
     * （方法名、参数、返回类型任意位置）→ 该方法在同 namespace XML 中的语句预览。
     *
     * <p>与方法行预览图标同源：定位走 {@link MapperNavigationTargets#statementTagsFor}（接口判定 + 同名语句，
     * 含 {@code databaseId} 变体），渲染走 {@link XmlSqlGutterLineMarkerProvider#previewStatementTag}（与 XML
     * 语句行点击、gutter 图标点击逐字节一致）。单条直接预览；多方言变体弹选择器；无 XML 语句
     * 返回 false 继续回落（注解候选 / 通用未找到），不出错源提示。</p>
     *
     * <p>排在 fallback 链首位：iBatis / JDBC / HQL 等家族的判据都看方法体或调用形态，对无 body 的
     * Mapper 接口方法本来就不命中，顺序不改变它们的行为；MyBatis XML 绑定语义最直接。</p>
     *
     * <p>{@code tags} 是 {@link #mapperXmlTagsFor} 在 {@code previewAsync} 的 compute 块里预解析的结果——
     * 本函数只做分发（单条直预览 / 多变体弹选择器），不查索引（T-PreviewFallbackEdtSlowOps）。</p>
     */
    private boolean tryMapperXmlMethodFallback(Project project, List<XmlTag> tags, String sourceLocation) {
        if (tags.isEmpty()) return false;
        if (tags.size() == 1) {
            previewXmlStatementTag(project, tags.get(0), sourceLocation);
            return true;
        }
        List<SqlSelectionDialog.Row> rows = new ArrayList<>(tags.size());
        for (XmlTag tag : tags) {
            String databaseId = tag.getAttributeValue("databaseId");
            int line = 0;
            if (tag.getContainingFile() != null && tag.getContainingFile().getViewProvider() != null
                    && tag.getContainingFile().getViewProvider().getDocument() != null) {
                line = tag.getContainingFile().getViewProvider().getDocument()
                        .getLineNumber(tag.getTextRange().getStartOffset()) + 1;
            }
            StringBuilder label = new StringBuilder();
            label.append(tag.getName());
            if (databaseId != null && !databaseId.isBlank()) {
                label.append(" [").append(databaseId).append(']');
            }
            label.append("（XML 第 ").append(line).append(" 行）");
            rows.add(SqlSelectionDialog.Row.of(label.toString(), tag.getText()));
        }
        new SqlSelectionDialog<>(project, tags, rows, (XmlTag selected) ->
                previewXmlStatementTag(project, selected, sourceLocation)).show();
        return true;
    }

    /** 渲染一条 Mapper XML 语句（复用 XML 语句行 / 方法行预览图标同一条预览链）。 */
    private void previewXmlStatementTag(Project project, XmlTag tag, String sourceLocation) {
        XmlSqlGutterLineMarkerProvider.previewStatementTag(project, sourceLocation, () ->
                tag.getContainingFile() instanceof XmlFile
                        ? new kotlin.Pair<>((XmlFile) tag.getContainingFile(), tag) : null);
    }

    /**
     * Mapper 注解绑定兜底（T-MapperPreviewDedupAndAnnotationShortcut，2026-11-26 用户报障）：
     * 光标停在带 SQL 注解的方法声明处（方法名 / 参数 / 返回类型 / 方法 JavaDoc）→ 直接预览
     * <b>该方法自己的</b>注解 SQL。
     *
     * <p>此前只有「光标落在 {@code PsiAnnotation} 内」才直预览；停在方法行（注解在上一行）会一路回落到
     * 文件级候选列表 {@code showAnnotationCandidates}，用户被迫从全文件注解里挑一条。判据纯 PSI：
     * {@code PsiMethod.modifierList.annotations} 里首个 {@code isSqlAnnotation} 命中的注解；光标在方法外
     * （类体空白 / import / 字段）仍返回 false，保留列表兜底语义。</p>
     */
    private boolean tryMapperAnnotationMethodFallback(Project project, PsiElement element, String sourceLocation) {
        PsiAnnotation annotation = sqlAnnotationForMethod(element);
        if (annotation == null) return false;
        previewSqlAnnotation(project, annotation, sourceLocation);
        return true;
    }

    /**
     * 光标所在方法上的首个 SQL 注解（无方法 / 无注解返回 null）。
     * {@code public} 供测试直调（不含任何 UI 副作用）；判定与快捷键回落共用同一份。
     */
    public PsiAnnotation sqlAnnotationForMethod(PsiElement element) {
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method == null) return null;
        for (PsiAnnotation candidate : method.getModifierList().getAnnotations()) {
            if (AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(candidate)) return candidate;
        }
        return null;
    }

    /** SQL 注解预览（注解内光标与方法声明处两处共用，渲染链一份）。 */
    private void previewSqlAnnotation(Project project, PsiAnnotation annotation, String sourceLocation) {
        java.util.concurrent.atomic.AtomicReference<cn.song.mybatisplus.visualizer.context.PreviewSourceContext> contextRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        SqlPreviewService.previewAsync(project,
                () -> {
                    var context = AnnotationSqlPreviewService.INSTANCE.resolveSourceContext(annotation, project);
                    contextRef.set(context);
                    return AnnotationSqlPreviewService.INSTANCE.preview(annotation, project, context);
                },
                (PreviewResult result) -> {
                    SqlPreviewService.showResult(project, result, sourceLocation, contextRef.get(), false);
                    return Unit.INSTANCE;
                });
    }

    /** 小工具：抽取编辑器选区状态。 */
    private static final class SelectionModelOrSelection {
        boolean hasSelection;
        int selectionStart;
        int selectionEnd;
        SelectionModelOrSelection(Editor editor) {
            com.intellij.openapi.editor.SelectionModel m = editor.getSelectionModel();
            hasSelection = m.hasSelection();
            selectionStart = m.getSelectionStart();
            selectionEnd = m.getSelectionEnd();
        }
    }

    /**
     * HQL 兜底（T-PreviewShortcut）：标准 HQL/JPQL 运行时字符串调用 + 自定义 DAO HQL。
     * 两定位器 locate(任意 PSI 元素) 均可从光标元素定位到宿主方法体内匹配调用，命中任一即触发预览并返回 true。
     * 与 CriteriaQuery（首参 Class/CriteriaQuery 变量）无冲突——hasStringHqlArgument 已排除。
     */
    private boolean tryHqlFallback(SqlPreviewService service, Project project, PsiElement element, String sourceLocation) {
        // 1. 标准 HQL/JPQL：session/em.createQuery / createNativeQuery / createNamedQuery（字符串首参）
        if (HqlQueryLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewHql(element),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 2. 自定义 DAO HQL：commonDao.findByQuery(...) / findByQueryUniqueResult(...) 等
        if (CustomDaoHqlLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewCustomDaoHql(element),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        return false;
    }

    /**
     * @NamedQuery 实体级兜底（T-PreviewShortcut）：光标落在实体类 @NamedQuery(...) 注解内时，
     * 反查实体类 → previewNamedQuery 转换对应命名查询的 JPQL。
     */
    private boolean tryNamedQueryFallback(SqlPreviewService service, Project project, PsiElement element, String sourceLocation) {
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
        if (annotation == null) return false;
        String qualified = annotation.getQualifiedName();
        String simpleName = qualified != null
                ? qualified.substring(qualified.lastIndexOf('.') + 1)
                : (annotation.getNameReferenceElement() != null
                        ? annotation.getNameReferenceElement().getReferenceName() : null);
        if (!"NamedQuery".equals(simpleName)) return false;
        PsiClass cls = PsiTreeUtil.getParentOfType(annotation, PsiClass.class, false);
        if (cls == null) return false;
        boolean match = false;
        for (NamedQueryInfo info : NamedQueryLocator.INSTANCE.locate(cls)) {
            if (info.getAnnotation() == annotation) {
                match = true;
                break;
            }
        }
        if (!match) return false;
        SqlPreviewService.previewAsync(project,
                () -> service.previewNamedQuery(annotation),
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
        return true;
    }

    /**
     * JDBC 模板兜底（T-PreviewShortcut）：jdbcTemplate.queryForList / update 等 →
     * JdbcTemplateSqlPreviewService.preview（支持命名参数 / 字符串拼接）。
     *
     * <p>T-IbatisAtShapeGate S4（错源兜底）：JDBC 通道认领后渲染失败（{@code isLikelySql} 拒绝——首参不是 SQL
     * 而是某种语句名）时，<b>再试一次 iBatis 解析</b>（{@link cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets#resolveIgnoringShape}，
     * 跳过形状正则、只判入口方法名 + 首参字面量）。命中才接管预览，未命中照旧落「未找到」——于是今后语句名
     * 里再出现第三种自定义字符时，也不会把「语句明明存在」的调用打成错源提示。
     * 解析在 {@code previewAsync} 的读锁内执行，EDT 只渲染结果。</p>
     */
    private boolean tryJdbcTemplateFallback(Project project, PsiElement element, String sourceLocation) {
        if (JdbcTemplateSqlLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> {
                        PreviewResult jdbc = JdbcTemplateSqlPreviewService.preview(element, project);
                        if (jdbc != null) return jdbc;
                        // T-IbatisAtShapeGate S4：JDBC 渲染失败（非 SQL 形态）→ iBatis 解析兜底
                        cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTarget target =
                                cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets.resolveIgnoringShape(element);
                        if (target == null) return null;
                        return cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview
                                .previewResolvedOrNull(project, target.statementId(), target.ownerNamespace());
                    },
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        return false;
    }

    /**
     * 方法定义锚点兜底（T-DaoSql-05）：光标在方法定义名或方法体内任意非调用处，且方法体内
     * 存在字符串 SQL 调用（`beanCruder.selectList(Class, sql, ...)` 等）→ 预览方法体内候选
     * （0/1/多分发，与 {@link cn.song.mybatisplus.visualizer.ui.gutter.StringSqlMethodGutterLineMarkerProvider}
     * 点击共用 {@link cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService#collectMethodCandidates}）。
     * 位于 JPA 回落之后、文件级注解候选之前——方法体内候选（局部）优先于文件级候选（全局）。
     */
    private boolean tryMethodBodyStringSqlFallback(Project project, PsiElement element, String sourceLocation) {
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method == null || method.getBody() == null) return false;
        StringSqlMethodGutterLineMarkerProvider.previewMethodCandidates(project, method, sourceLocation);
        return true;
    }

    /**
     * JPA 三态回落：派生查询 / @Query / CriteriaQuery —— 命中任一即触发预览并返回 true。
     */
    private boolean tryJpaFallback(SqlPreviewService service, Project project, PsiElement element, String sourceLocation) {
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        // 1. JPA 回落：@Query
        if (method != null && JpqlQueryLocator.INSTANCE.hasQueryAnnotation(method)) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewJpql(method),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 2. JPA 回落：派生查询
        if (method != null && DerivedMethodLocator.INSTANCE.isDerivedQuery(method)) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewDerivedQuery(method),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 3. JPA 回落：CriteriaQuery
        if (CriteriaQueryLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewCriteriaQuery(element),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 4. JPA 回落（T-J9 调用点锚点）：service/controller 中调用 Repository 派生/@Query/@Procedure 方法处
        if (tryRepositoryCallSiteFallback(service, project, element, sourceLocation)) return true;
        // 5. JPA 回落（F-1 修复）：Repository **接口方法声明行**——`deleteAllInBatch()` 这类 bulk 形态
        //    既不在派生查询线（无 `By`、`*All` 词干白名单不放行 `InBatch`）也无调用表达式，
        //    声明行光标下零可达；此处按接口泛型解析实体后走同一条 CRUD 模板线。
        if (tryRepositoryDeclarationFallback(service, project, method, sourceLocation)) return true;
        return false;
    }

    /**
     * F-1 修复：Repository 接口<b>声明行</b>回落（{@link #tryJpaFallback} 第 5 步）。
     *
     * <p>只认接口声明（实现类方法由既有路径覆盖），实体解析走 {@code resolveRepositoryEntityFromClass} 的
     * 严格基类判定——非 Repository 接口返回 null，不会误亮普通接口方法。</p>
     */
    private boolean tryRepositoryDeclarationFallback(SqlPreviewService service, Project project, PsiMethod method, String sourceLocation) {
        PsiClass iface = method != null ? method.getContainingClass() : null;
        if (iface == null) return false;
        if (!iface.isInterface()) return false;
        PreviewResult preview = service.previewRepositoryDeclaration(method);
        if (preview == null) return false;
        SqlPreviewService.showResult(project, preview, sourceLocation);
        return true;
    }

    /**
     * T-J9 调用点锚点（动作/快捷键路径）：光标落在 `userRepository.findByName(x)` 这类
     * Repository 方法**调用**上时，resolve 被调方法到接口声明（`call.resolveMethod()`），
     * 按声明形态分派既有预览管道：
     *  - `@Query` → previewJpql；
     *  - `@Procedure` → previewProcedure（T-J16）；
     *  - 派生查询 → previewDerivedQuery，并把调用点实参带上——
     *    `PageRequest.of(0, 20)` / `Sort.by("id")` 静态求值内联（T-J10 全路径）。
     *
     * <p>边界（KDoc 文档化）：gutter 图标不做调用点反查（声明↔调用反查需全文件扫描/索引缓存，
     * 与 gutter 快速路径性能红线冲突）——调用点预览仅通过本动作/快捷键触发；Repository 声明处
     * 图标仍由 {@link cn.song.mybatisplus.visualizer.ui.gutter.JpaGutterLineMarkerProvider} 承载。
     */
    private boolean tryRepositoryCallSiteFallback(SqlPreviewService service, Project project, PsiElement element, String sourceLocation) {
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, false);
        if (call == null) return false;
        PsiMethod resolved;
        try {
            resolved = call.resolveMethod();
        } catch (Throwable t) {
            return false;
        }
        if (resolved == null) return false;
        PsiClass containingClass = resolved.getContainingClass();
        if (containingClass == null) return false;
        // 只认接口声明（Repository 接口方法无方法体；实现类方法不在此回落——派生方法本就无实现）
        if (!containingClass.isInterface()) return false;

        // @Query 优先（与 gutter 派发一致：@Query 方法即使方法名匹配派生模式也走 JPQL 路径）
        if (JpqlQueryLocator.hasQueryAnnotation(resolved)) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewJpql(resolved),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // @Procedure（T-J16）
        if (JpaQueryEnhancer.procedureInfo(resolved) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewProcedure(resolved),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 派生查询（T-J10：调用点实参 PageRequest.of/Sort.by 静态求值）
        if (DerivedMethodLocator.isDerivedQuery(resolved)) {
            List<PsiExpression> callArgs = Arrays.asList(call.getArgumentList().getExpressions());
            SqlPreviewService.previewAsync(project,
                    () -> service.previewDerivedQuery(resolved, callArgs),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        return false;
    }

    /** M26-A：文件级 SQL 注解候选——收集文件内全部 @Select/@Update 等注解（0/1/多分发）。 */
    private void showAnnotationCandidates(Project project, PsiFile file, Editor editor, String sourceLocation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<PsiAnnotation>, List<SqlSelectionDialog.Row>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<PsiAnnotation> annotations = AnnotationSqlPreviewService.INSTANCE.annotationCandidates(file);
                    List<SqlSelectionDialog.Row> rows = annotations.stream().map(anno -> {
                        PsiMethod m = PsiTreeUtil.getParentOfType(anno, PsiMethod.class);
                        String qn = anno.getQualifiedName();
                        String shortName = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : "";
                        return SqlSelectionDialog.Row.of(
                                (m != null ? m.getName() : "?") + "（" + shortName + "）",
                                AnnotationSqlPreviewService.INSTANCE.sqlSnippet(anno, 80));
                    }).collect(Collectors.toList());
                    return Pair.create(annotations, rows);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<PsiAnnotation> annotations = payload.getFirst();
            List<SqlSelectionDialog.Row> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (annotations.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (annotations.size() == 1) {
                    previewAnnotation(project, annotations.get(0), sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, annotations, displayTexts, (PsiAnnotation selected) -> {
                        locateEditorTo(editor, selected);
                        previewAnnotation(project, selected, sourceLocation);
                    }).show();
                }
            });
        });
    }

    /** T-M29.6：选择区内 SQL 注解候选回落。 */
    private void showAnnotationSelectionCandidates(
            Project project, PsiFile file, Editor editor, int selStart, int selEnd, String sourceLocation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<PsiAnnotation>, List<SqlSelectionDialog.Row>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<PsiAnnotation> annotations =
                            AnnotationSqlPreviewService.INSTANCE.annotationCandidatesInRange(file, selStart, selEnd);
                    List<SqlSelectionDialog.Row> rows = annotations.stream().map(anno -> {
                        PsiMethod m = PsiTreeUtil.getParentOfType(anno, PsiMethod.class);
                        String qn = anno.getQualifiedName();
                        String shortName = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : "";
                        return SqlSelectionDialog.Row.of(
                                (m != null ? m.getName() : "?") + "（" + shortName + "）",
                                AnnotationSqlPreviewService.INSTANCE.sqlSnippet(anno, 80));
                    }).collect(Collectors.toList());
                    return Pair.create(annotations, rows);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<PsiAnnotation> annotations = payload.getFirst();
            List<SqlSelectionDialog.Row> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (annotations.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (annotations.size() == 1) {
                    previewAnnotation(project, annotations.get(0), sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, annotations, displayTexts, (PsiAnnotation selected) -> {
                        locateEditorTo(editor, selected);
                        previewAnnotation(project, selected, sourceLocation);
                    }).show();
                }
            });
        });
    }

    /** 预览单个 SQL 注解（后台转换，EDT 渲染）。 */
    private void previewAnnotation(Project project, PsiAnnotation annotation, String sourceLocation) {
        SqlPreviewService.previewAsync(project,
                () -> AnnotationSqlPreviewService.INSTANCE.preview(annotation, project),
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
    }

    /** T-M29.6：XML 选择区语句候选。 */
    private void previewXmlSelection(Project project, XmlFile file, Editor editor, int selStart, int selEnd, String sourceLocation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<XmlTag>, List<SqlSelectionDialog.Row>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    // O-72：mapper 语句候选 + 通用内嵌 SQL 载体候选（排除集合保证互斥，按 offset 归并）
                    List<XmlTag> mapperTags = XmlSqlGutterLineMarkerProvider.statementsInRange(file, selStart, selEnd);
                    List<XmlTag> inlineTags = XmlInlineSqlDetector.INSTANCE.detectionEnabled()
                            ? XmlInlineSqlGutterLineMarkerProvider.carriersInRange(file, selStart, selEnd)
                            : Collections.<XmlTag>emptyList();
                    List<XmlTag> tags = new ArrayList<>(mapperTags.size() + inlineTags.size());
                    tags.addAll(mapperTags);
                    tags.addAll(inlineTags);
                    tags.sort(Comparator.comparingInt(t -> t.getTextRange().getStartOffset()));
                    List<SqlSelectionDialog.Row> rows = tags.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.Row.of(
                                t.getName() + " " + (id != null ? id : "") + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList());
                    return Pair.create(tags, rows);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<XmlTag> tags = payload.getFirst();
            List<SqlSelectionDialog.Row> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (tags.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (tags.size() == 1) {
                    previewXmlTag(project, file, tags.get(0), sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, tags, displayTexts, (XmlTag selected) -> {
                        locateEditorTo(editor, selected);
                        previewXmlTag(project, file, selected, sourceLocation);
                    }).show();
                }
            });
        });
    }

    /**
     * T-IbatisCallPreview：光标在 iBatis {@code SqlMapExecutor} 调用行上 → 直接预览该语句的 SQL。
     *
     * <p>为什么必须排在 JDBC 模板回落之前：SqlMapExecutor 的入口名（queryForObject/queryForList/
     * queryForMap/update/delete）与 JDBC 模板白名单<b>完全重叠</b>，而 JDBC 定位器没有 iBatis 让位门——
     * 此前该调用被 JDBC 通道认领后 isLikelySql 拒绝、通道又已 return true，用户只拿到
     * 「未找到可转换的 Wrapper」（错源提示）。形状门复用 {@link IbatisCallTargets}（内部就是
     * IbatisCallYieldGate，与 gutter 让位同一函数）；目标不存在时 previewResolved 返回
     * 「语句未找到」可见失败——形状命中即认领、不回落 JDBC（标识符形态首参本来就不是 SQL）。</p>
     */
    private boolean tryIbatisCallFallback(Project project, PsiElement element, String sourceLocation) {
        IbatisCallTarget target = IbatisCallTargets.resolve(element);
        if (target == null) return false;
        previewIbatisTarget(project, target, sourceLocation);
        return true;
    }

    /**
     * T-IbatisCallPreview：光标在方法定义行 / 方法体任意处 → 按方法体内的 iBatis 调用出候选
     * （0 → 继续回落；1 → 直接预览；多 → SqlSelectionDialog）。方法体扫描带「无引号 / 超长方法体」
     * 两道廉价预筛。
     */
    private boolean tryIbatisMethodFallback(Project project, PsiElement element, String sourceLocation) {
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method == null || method.getBody() == null) return false;
        List<IbatisCallTarget> targets = IbatisCallTargets.callsInMethod(method);
        if (targets.isEmpty()) return false;
        if (targets.size() == 1) {
            previewIbatisTarget(project, targets.get(0), sourceLocation);
            return true;
        }
        List<SqlSelectionDialog.Row> rows = new ArrayList<>(targets.size());
        for (IbatisCallTarget target : targets) {
            int line = 0;
            if (target.call().getContainingFile() != null
                    && target.call().getContainingFile().getViewProvider() != null
                    && target.call().getContainingFile().getViewProvider().getDocument() != null) {
                line = target.call().getContainingFile().getViewProvider().getDocument()
                        .getLineNumber(target.call().getTextRange().getStartOffset()) + 1;
            }
            String methodName = target.call().getMethodExpression().getReferenceName() != null
                    ? target.call().getMethodExpression().getReferenceName() : "?";
            rows.add(SqlSelectionDialog.Row.of(
                    methodName + "(\"" + target.statementId() + "\")（第 " + line + " 行）",
                    target.call().getText().trim()));
        }
        new SqlSelectionDialog<>(project, targets, rows, (IbatisCallTarget selected) ->
                previewIbatisTarget(project, selected, sourceLocation)).show();
        return true;
    }

    /** 预览一个 iBatis 调用目标（后台 ReadAction 内解析语句 + 渲染，EDT 只渲染结果）。 */
    private void previewIbatisTarget(Project project, IbatisCallTarget target, String sourceLocation) {
        // 只把字符串带进后台块（PSI 元素不跨线程读；解析链自己持读锁）
        final String statementId = target.statementId();
        final String ownerNamespace = target.ownerNamespace();
        SqlPreviewService.previewAsync(project,
                () -> IbatisStatementPreview.previewResolved(project, statementId, ownerNamespace),
                (PreviewResult result) -> {
                    SqlPreviewService.showResult(project, result, sourceLocation);
                    return Unit.INSTANCE;
                });
    }

    /**
     * XML mapper 语句预览——光标所在语句优先；不在语句内时收集全部语句（0/1/多分发）。
     * O-72：光标上爬与全文件收集均追加通用内嵌 SQL 载体（开关 {@code xmlInlineSqlEnabled} 闸）。
     */
    /**
     * T-Ibatis2Support 券 A 收尾：XML 侧「这算一条可预览的语句吗」的<b>单一判据</b>。
     *
     * <p>三路来源：MyBatis mapper 语句 / 通用内嵌 SQL 载体 / iBatis {@code <sqlMap>} 语句。
     * 第三条是必须的：通用兜底通道已对 iBatis 语句<b>让位</b>（防与专用 provider 双图标），
     * 若不在这里补回，Alt+Shift+Q 在 sqlMap 文件里会一条语句都列不出来——gutter 图标正常、
     * 快捷键却空候选，属最难自证的半可用态。三处消费点共用本判据（光标上爬 / 全文件候选 /
     * 选区候选），避免三份条件漂移。</p>
     */
    private static boolean isXmlStatementCandidate(com.intellij.psi.xml.XmlTag tag) {
        return XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(tag.getName())
                || (XmlInlineSqlDetector.INSTANCE.detectionEnabled()
                    && XmlInlineSqlDetector.INSTANCE.isSqlCarrierTag(tag))
                || IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag);
    }

    private void previewXmlStatement(Project project, XmlFile file, Editor editor, String sourceLocation) {
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        XmlTag tag = element != null ? PsiTreeUtil.getParentOfType(element, XmlTag.class) : null;
        XmlTag statement = tag;
        while (statement != null && !isXmlStatementCandidate(statement)) {
            statement = statement.getParentTag();
        }
        if (statement != null) {
            previewXmlTag(project, file, statement, sourceLocation);
            return;
        }
        // 光标不在语句内 → 全部语句候选（T-M25-01）
        // T-Perf-14：全文件 XmlTag 遍历 + 摘要预计算移同一后台 ReadAction（大 XML 中 EDT 直跑
        // findChildrenOfType 构成卡顿；0/1/多 分发在后台结果回来后进行，EDT 只做预览与弹窗）
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<XmlTag>, List<SqlSelectionDialog.Row>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<XmlTag> statements = PsiTreeUtil.findChildrenOfType(file, XmlTag.class).stream()
                            .filter(ConvertToSqlAction::isXmlStatementCandidate)
                            .collect(Collectors.toList());
                    List<SqlSelectionDialog.Row> rows = statements.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.Row.of(
                                t.getName() + " " + (id != null ? id : "") + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList());
                    return Pair.create(statements, rows);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            final Pair<List<XmlTag>, List<SqlSelectionDialog.Row>> finalPayload = payload;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                List<XmlTag> statements = finalPayload.getFirst();
                List<SqlSelectionDialog.Row> displayTexts = finalPayload.getSecond();
                if (statements.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (statements.size() == 1) {
                    previewXmlTag(project, file, statements.get(0), sourceLocation);
                } else if (displayTexts.size() != statements.size()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, statements, displayTexts, (XmlTag selected) -> {
                        // T-M29.6：选中确定 → 编辑器定位（行首 + 滚动居中 + 整行选中）→ 预览 SQL
                        locateEditorTo(editor, selected);
                        previewXmlTag(project, file, selected, sourceLocation);
                    }).show();
                }
            });
        });
    }

    /** 预览单个 XML 语句（后台转换，EDT 渲染；T-M25.2 传 project 解析静态常量引用）。 */
    /** O-72：统一 XML 语句分发——通用内嵌 SQL 载体走 inline 预览，其余走 mapper 语句预览。 */
    private void previewXmlTag(Project project, XmlFile file, XmlTag tag, String sourceLocation) {
        // T-Ibatis2Support 券 A 收尾：iBatis 语句走独立预览链——它没有 namespace / Mapper 接口 /
        // 实体注解，进 mapper 链只会拿到空上下文；且兜底通道已让位，不补这条就一个入口都没有。
        // T-IbatisCallPreview：装配收口到 IbatisStatementPreview.previewTag（与 gutter / MCP 同源）。
        if (IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)) {
            SqlPreviewService.previewAsync(project,
                    () -> IbatisStatementPreview.previewTag(tag),
                    (PreviewResult result) -> {
                        SqlPreviewService.showResult(project, result, sourceLocation);
                        return Unit.INSTANCE;
                    });
            return;
        }
        if (XmlInlineSqlDetector.INSTANCE.isSqlCarrierTag(tag)) {
            SqlPreviewService.previewAsync(project,
                    () -> XmlInlineSqlPreviewService.INSTANCE.preview(project, tag),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return;
        }
        previewXml(project, file, tag, sourceLocation);
    }

    /** 预览单个 XML 语句（注入实体上下文）。 */
    private void previewXml(Project project, XmlFile file, XmlTag tag, String sourceLocation) {
        SqlPreviewService.previewAsync(project, () -> {
            SourceSqlContext src = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag);
            SqlCompletionContext entity = null;
            if (src.getMain() != null) {
                SqlCompletionContext m = src.getMain();
                entity = new SqlCompletionContext(
                        m.tableName, m.columns, m.tableAlias,
                        m.columnComments, m.tableComment, src.getCandidates());
            }
            return XmlPreviewService.INSTANCE.preview(file.getText(), tag.getAttributeValue("id"), project, entity);
        }, (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
    }

    /** F-45：maintainPage 语句预览。 */
    private void previewPagesStatement(Project project, XmlFile file, Editor editor) {
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        XmlTag tag = element != null ? PsiTreeUtil.getParentOfType(element, XmlTag.class) : null;
        XmlTag statement = tag;
        // 页面标签名不固定：上爬到「直接父级是 <pages> 且自身是 SQL 载体」的标签
        while (statement != null
                && !(statement.getParentTag() != null && "pages".equals(statement.getParentTag().getName())
                && PagesXmlGutterLineMarkerProvider.isPageStatementTag(statement))) {
            statement = statement.getParentTag();
        }
        if (statement != null) {
            previewPages(project, file, statement);
            return;
        }
        // 光标不在页面语句内 → 全部候选
        // T-Perf-14：全文件 XmlTag 遍历 + 摘要预计算移同一后台 ReadAction（同 mapper 语句分支）
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<XmlTag>, List<SqlSelectionDialog.Row>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    XmlTag root = file.getRootTag();
                    List<XmlTag> statements = new ArrayList<>();
                    if (root != null) {
                        for (PsiElement child : root.getChildren()) {
                            if (child instanceof XmlTag
                                    && PagesXmlGutterLineMarkerProvider.isPageStatementTag((XmlTag) child)) {
                                statements.add((XmlTag) child);
                            }
                        }
                    }
                    List<SqlSelectionDialog.Row> rows = statements.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.Row.of(
                                (id != null ? id : t.getName()) + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList());
                    return Pair.create(statements, rows);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            final Pair<List<XmlTag>, List<SqlSelectionDialog.Row>> finalPayload = payload;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                List<XmlTag> statements = finalPayload.getFirst();
                List<SqlSelectionDialog.Row> displayTexts = finalPayload.getSecond();
                if (statements.isEmpty()) {
                    SqlPreviewService.showResult(project, null, null);
                } else if (statements.size() == 1) {
                    previewPages(project, file, statements.get(0));
                } else if (displayTexts.size() != statements.size()) {
                    SqlPreviewService.showResult(project, null, null);
                } else {
                    new SqlSelectionDialog<>(project, statements, displayTexts, (XmlTag selected) -> {
                        locateEditorTo(editor, selected);
                        previewPages(project, file, selected);
                    }).show();
                }
            });
        });
    }

    /** F-45：选择区内 maintainPage 候选。 */
    private void previewPagesSelection(Project project, XmlFile file, Editor editor, int selStart, int selEnd) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<XmlTag>, List<SqlSelectionDialog.Row>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<XmlTag> tags = PagesXmlGutterLineMarkerProvider.statementsInRange(file, selStart, selEnd);
                    List<SqlSelectionDialog.Row> rows = tags.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.Row.of(
                                (id != null ? id : "") + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList());
                    return Pair.create(tags, rows);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<XmlTag> tags = payload.getFirst();
            List<SqlSelectionDialog.Row> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (tags.isEmpty()) {
                    SqlPreviewService.showResult(project, null, null);
                } else if (tags.size() == 1) {
                    previewPages(project, file, tags.get(0));
                } else {
                    new SqlSelectionDialog<>(project, tags, displayTexts, (XmlTag selected) -> {
                        locateEditorTo(editor, selected);
                        previewPages(project, file, selected);
                    }).show();
                }
            });
        });
    }

    /** 预览单个 maintainPage（后台转换，EDT 渲染）。 */
    private void previewPages(Project project, XmlFile file, XmlTag tag) {
        SqlPreviewService.previewAsync(project,
                () -> {
                    // T-PagesHql：传 PSI 宿主锚点，HQL 转换按该 XML 所属模块收窄实体检索范围；
                    // id 缺省时用标签名（解析器同口径回落）
                    String id = tag.getAttributeValue("id");
                    return PagesXmlPreviewService.INSTANCE.preview(
                            file.getText(),
                            id != null ? id : tag.getName(),
                            project,
                            tag);
                },
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, null); return Unit.INSTANCE; });
    }

    /** 预览单个链首调用（后台转换，EDT 渲染）。 */
    private void preview(SqlPreviewService service, PsiElement anchor, Project project, String sourceLocation) {
        SqlPreviewService.previewAsync(project,
                () -> service.preview(anchor),
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
    }

    /** T-M23.8：编辑器定位跳转到候选元素所在行。 */
    private void locateEditorTo(Editor editor, PsiElement element) {
        try {
            kotlin.Pair<Integer, Integer> off = WrapperLocator.lineOffsetsOf(element);
            int start = off.getFirst();
            int end = off.getSecond();
            editor.getCaretModel().moveToOffset(start);
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER_UP);
            editor.getSelectionModel().setSelection(start, end);
        } catch (Throwable ignored) {
            // 失败兜底（不中断预览）
        }
    }

    /** 取 XML 标签所在行号（1 起）。 */
    private static int lineOf(XmlTag tag) {
        try {
            com.intellij.openapi.editor.Document document = tag.getContainingFile().getViewProvider().getDocument();
            if (document != null) {
                return document.getLineNumber(tag.getTextRange().getStartOffset()) + 1;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0;
    }

    // update() 读取 PSI，必须在后台线程执行
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(file instanceof PsiJavaFile || file instanceof XmlFile);
    }

    /** 插件动作 ID（plugin.xml 中注册）。 */
    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.ConvertToSql";
}
