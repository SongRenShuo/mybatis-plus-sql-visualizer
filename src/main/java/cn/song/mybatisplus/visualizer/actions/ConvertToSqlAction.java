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
import cn.song.mybatisplus.visualizer.ui.XmlInlineSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.gutter.PagesXmlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.StringSqlMethodGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlInlineSqlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
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
            SqlPreviewService.previewAsync(project,
                    () -> AnnotationSqlPreviewService.INSTANCE.preview(annotation, project),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return;
        }

        // PSI 转换在后台线程执行（EDT 上慢操作会被 2025.3+ 禁止），回调在 EDT 渲染
        SqlPreviewService.previewAsync(project,
                () -> service.preview(element),
                (PreviewResult result) -> {
                    if (result != null) {
                        SqlPreviewService.showResult(project, result, sourceLocation);
                    } else if (!tryHqlFallback(service, project, element, sourceLocation)
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
     */
    private boolean tryJdbcTemplateFallback(Project project, PsiElement element, String sourceLocation) {
        if (JdbcTemplateSqlLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> JdbcTemplateSqlPreviewService.preview(element, project),
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
     * XML mapper 语句预览——光标所在语句优先；不在语句内时收集全部语句（0/1/多分发）。
     * O-72：光标上爬与全文件收集均追加通用内嵌 SQL 载体（开关 {@code xmlInlineSqlEnabled} 闸）。
     */
    private void previewXmlStatement(Project project, XmlFile file, Editor editor, String sourceLocation) {
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        XmlTag tag = element != null ? PsiTreeUtil.getParentOfType(element, XmlTag.class) : null;
        XmlTag statement = tag;
        while (statement != null && !XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(statement.getName())
                && !(XmlInlineSqlDetector.INSTANCE.detectionEnabled() && XmlInlineSqlDetector.INSTANCE.isSqlCarrierTag(statement))) {
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
                            .filter(it -> XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(it.getName())
                                    || (XmlInlineSqlDetector.INSTANCE.detectionEnabled() && XmlInlineSqlDetector.INSTANCE.isSqlCarrierTag(it)))
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
