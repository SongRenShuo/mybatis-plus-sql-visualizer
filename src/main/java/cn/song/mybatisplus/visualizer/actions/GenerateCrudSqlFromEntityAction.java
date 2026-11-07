package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.generator.CrudColumn;
import cn.song.mybatisplus.visualizer.generator.CrudStatement;
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator;
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.psi.PsiDialect;
import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 实体类右键生成 CRUD SQL（T-CrudSql-02）。
 *
 * <p>注册（plugin.xml）：EditorPopupMenu + ProjectViewPopupMenu（均 anchor=last，与既有两个
 * 转换 action 同区）。update 判定：上下文内存在 MyBatis-Plus 实体（@TableName/@TableId/
 * @TableField/@TableLogic 任一标注）才可用——PSI 读取在 BGT update 线程（262 平台 EDT 读
 * PSI 硬抛，见既有平台陷阱约定）。
 *
 * <p>行为：后台 ReadAction 内 EntityResolver 解析（带缓存）→ {@link CrudSqlGenerator} 生成 →
 * EDT 上 {@link CrudSqlPreviewFlow#showCandidates}（现有选择弹窗 + 现有预览窗口，零新弹窗）。
 *
 * <p>已知边界（文档化）：Kotlin KtClass 不在本次支持面（PSI_ELEMENT 为 KtClass 非 PsiClass；
 * Kotlin 侧支持需仿 kotlin-optional.xml 的可选注册模式，留后续迭代）。
 */
public final class GenerateCrudSqlFromEntityAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean hasEntity = false;
        if (project != null) {
            for (PsiClass clazz : collectEntityClasses(e)) {
                if (isMpEntity(clazz)) {
                    hasEntity = true;
                    break;
                }
            }
        }
        e.getPresentation().setEnabledAndVisible(hasEntity);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        List<PsiClass> entityClasses = new ArrayList<>();
        for (PsiClass clazz : collectEntityClasses(e)) {
            if (isMpEntity(clazz)) {
                entityClasses.add(clazz);
            }
        }
        if (entityClasses.isEmpty()) {
            return;
        }
        // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        List<Pair<String, List<CrudStatement>>> payload;
        try {
            payload = ReadAction.compute(() -> {
                List<Pair<String, List<CrudStatement>>> result = new ArrayList<>();
                for (PsiClass clazz : entityClasses) {
                    CrudTableSpec spec = entitySpec(clazz);
                    if (spec == null) {
                        continue;
                    }
                    List<CrudStatement> statements = CrudSqlGenerator.generate(spec);
                    if (!statements.isEmpty()) {
                        result.add(Pair.create(spec.getTableName(), statements));
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            payload = Collections.emptyList();
        }
        List<Pair<String, List<CrudStatement>>> finalPayload = payload;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (finalPayload.isEmpty()) {
                CrudSqlPreviewFlow.showNoneFound(project);
            } else {
                CrudSqlPreviewFlow.showCandidates(project, finalPayload);
            }
        });
    }

    // ------------------------------------------------------------------
    // 实体收集与判定（internal 供测试）
    // ------------------------------------------------------------------

    /**
     * 从事件上下文收集实体候选类：
     * <ul>
     *  <li>PSI_ELEMENT（编辑器光标处/项目视图单选）与 PSI_ELEMENT_ARRAY（项目视图多选）；</li>
     *  <li>叶子元素（字段/方法上右键）经 {@link PsiTreeUtil#getParentOfType} 上溯所在类；</li>
     *  <li>文件级选择（PsiClassOwner，如 Java 源文件右键）取全部顶层类。</li>
     * </ul>
     */
    public List<PsiClass> collectEntityClasses(AnActionEvent e) {
        List<PsiClass> classes = new ArrayList<>();
        PsiElement psiElement = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (psiElement != null) {
            classes.addAll(classCandidates(psiElement));
        }
        PsiElement[] elementArray = e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY);
        if (elementArray != null) {
            for (PsiElement element : elementArray) {
                classes.addAll(classCandidates(element));
            }
        }
        if (classes.isEmpty()) {
            com.intellij.openapi.editor.Editor editor = e.getData(CommonDataKeys.EDITOR);
            PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
            if (editor != null && file != null) {
                PsiElement leaf = file.findElementAt(editor.getCaretModel().getOffset());
                if (leaf != null) {
                    PsiClass inClass = PsiTreeUtil.getParentOfType(leaf, PsiClass.class, false);
                    if (inClass != null) {
                        classes.add(inClass);
                    }
                }
            }
        }
        // distinctBy { qualifiedName ?: name }
        Set<String> seen = new LinkedHashSet<>();
        List<PsiClass> distinct = new ArrayList<>();
        for (PsiClass clazz : classes) {
            String key = clazz.getQualifiedName() != null ? clazz.getQualifiedName() : clazz.getName();
            if (seen.add(key)) {
                distinct.add(clazz);
            }
        }
        return distinct;
    }

    private static List<PsiClass> classCandidates(PsiElement element) {
        if (element instanceof PsiClass) {
            return List.of((PsiClass) element);
        }
        if (element instanceof PsiClassOwner) {
            return List.of(((PsiClassOwner) element).getClasses());
        }
        PsiClass inClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        return inClass != null ? List.of(inClass) : Collections.emptyList();
    }

    /**
     * MyBatis-Plus 实体判定：类或任一字段带 MP 注解（短名匹配，对齐 EntityResolver 的
     * 「注解类不可解析时按短名兜底」策略）——普通 POJO 不误启用。
     */
    public boolean isMpEntity(PsiClass clazz) {
        Set<String> classAnnotationNames = new LinkedHashSet<>();
        PsiModifierList classModifierList = clazz.getModifierList();
        if (classModifierList != null) {
            for (com.intellij.psi.PsiAnnotation annotation : classModifierList.getAnnotations()) {
                String referenceName = annotationName(annotation);
                if (referenceName != null) {
                    classAnnotationNames.add(referenceName);
                }
            }
        }
        for (String name : classAnnotationNames) {
            if (MP_ANNOTATION_SHORT_NAMES.contains(name)) {
                return true;
            }
        }
        for (PsiField field : clazz.getFields()) {
            PsiModifierList modifierList = field.getModifierList();
            if (modifierList == null) {
                continue;
            }
            for (com.intellij.psi.PsiAnnotation annotation : modifierList.getAnnotations()) {
                String referenceName = annotationName(annotation);
                if (referenceName != null && MP_ANNOTATION_SHORT_NAMES.contains(referenceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @Nullable String annotationName(com.intellij.psi.PsiAnnotation annotation) {
        com.intellij.psi.PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
        return reference != null ? reference.getReferenceName() : null;
    }

    /** TableInfo → CrudTableSpec（实体侧适配；列顺序/角色分组/逻辑删除列均继承 EntityResolver 语义）。 */
    public @Nullable CrudTableSpec entitySpec(PsiClass clazz) {
        TableInfo table = new EntityResolver().resolve(clazz);
        if (table.getTableName().isBlank()) {
            return null;
        }
        return toCrudSpec(table);
    }

    private static CrudTableSpec toCrudSpec(TableInfo table) {
        List<CrudColumn> columns = new ArrayList<>();
        for (cn.song.mybatisplus.visualizer.model.ColumnInfo col : table.getColumns()) {
            columns.add(new CrudColumn(
                    col.getColumnName(),
                    col.getComment(),
                    col.isId(),
                    col.isIdTypeAuto(),
                    ParamQuote.decide(col.getFieldType()),
                    // R48：@TableField(fill=...) 解析值贯通（角标/手填模拟值数据面；DB 侧无此元数据恒 null）
                    col.getFill(),
                    // T-DateLiteralBackfill：实体字段类型日期族 → 参数行产 ANSI 类型字面量
                    ParamQuote.isTemporal(col.getFieldType())));
        }
        return new CrudTableSpec(
                table.getTableName(),
                columns,
                table.getLogicDeleteColumn(),
                table.getVersionColumn(),
                PsiDialect.currentDialect());
    }

    /** MP 注解短名集合（@TableName/@TableId/@TableField/@TableLogic/@Version）。 */
    public static final Set<String> MP_ANNOTATION_SHORT_NAMES =
            Set.of("TableName", "TableId", "TableField", "TableLogic", "Version");
}
