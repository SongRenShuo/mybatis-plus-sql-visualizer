package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.generator.CrudColumn
import cn.song.mybatisplus.visualizer.generator.CrudStatement
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.psi.currentDialect
import cn.song.mybatisplus.visualizer.sql.ParamQuote
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * 实体类右键生成 CRUD SQL（T-CrudSql-02）。
 *
 * 注册（plugin.xml）：EditorPopupMenu + ProjectViewPopupMenu（均 anchor=last，与既有两个
 * 转换 action 同区）。update 判定：上下文内存在 MyBatis-Plus 实体（@TableName/@TableId/
 * @TableField/@TableLogic 任一标注）才可用——PSI 读取在 BGT update 线程（262 平台 EDT 读
 * PSI 硬抛，见既有平台陷阱约定）。
 *
 * 行为：后台 ReadAction 内 EntityResolver 解析（带缓存）→ [CrudSqlGenerator] 生成 →
 * EDT 上 [CrudSqlPreviewFlow.showCandidates]（现有选择弹窗 + 现有预览窗口，零新弹窗）。
 *
 * 已知边界（文档化）：Kotlin KtClass 不在本次支持面（PSI_ELEMENT 为 KtClass 非 PsiClass；
 * Kotlin 侧支持需仿 kotlin-optional.xml 的可选注册模式，留后续迭代）。
 */
class GenerateCrudSqlFromEntityAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasEntity = project != null && collectEntityClasses(e).any { isMpEntity(it) }
        e.presentation.isEnabledAndVisible = hasEntity
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entityClasses = collectEntityClasses(e).filter { isMpEntity(it) }
        if (entityClasses.isEmpty()) return
        @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        val payload = try {
            ReadAction.compute<List<Pair<String, List<CrudStatement>>>, Throwable> {
                entityClasses.mapNotNull { clazz ->
                    val spec = entitySpec(clazz) ?: return@mapNotNull null
                    val statements = CrudSqlGenerator.generate(spec)
                    if (statements.isEmpty()) null else spec.tableName to statements
                }
            }
        } catch (t: Throwable) {
            emptyList<Pair<String, List<CrudStatement>>>()
        }
        ApplicationManager.getApplication().invokeLater {
            if (payload.isEmpty()) {
                CrudSqlPreviewFlow.showNoneFound(project)
            } else {
                CrudSqlPreviewFlow.showCandidates(project, payload)
            }
        }
    }

    // ------------------------------------------------------------------
    // 实体收集与判定（internal 供测试）
    // ------------------------------------------------------------------

    /**
     * 从事件上下文收集实体候选类：
     *  - PSI_ELEMENT（编辑器光标处/项目视图单选）与 PSI_ELEMENT_ARRAY（项目视图多选）；
     *  - 叶子元素（字段/方法上右键）经 [PsiTreeUtil.getParentOfType] 上溯所在类；
     *  - 文件级选择（PsiClassOwner，如 Java 源文件右键）取全部顶层类。
     */
    internal fun collectEntityClasses(e: AnActionEvent): List<PsiClass> {
        val classes = mutableListOf<PsiClass>()
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let { classes += classCandidates(it) }
        e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.forEach { classes += classCandidates(it) }
        if (classes.isEmpty()) {
            val editor = e.getData(CommonDataKeys.EDITOR)
            val file = e.getData(CommonDataKeys.PSI_FILE)
            if (editor != null && file != null) {
                file.findElementAt(editor.caretModel.offset)?.let { leaf ->
                    PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false)?.let { classes += it }
                }
            }
        }
        return classes.distinctBy { it.qualifiedName ?: it.name }
    }

    private fun classCandidates(element: PsiElement): List<PsiClass> = when (element) {
        is PsiClass -> listOf(element)
        is PsiClassOwner -> element.classes.toList()
        else -> listOfNotNull(PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false))
    }

    /**
     * MyBatis-Plus 实体判定：类或任一字段带 MP 注解（短名匹配，对齐 EntityResolver 的
     * 「注解类不可解析时按短名兜底」策略）——普通 POJO 不误启用。
     */
    internal fun isMpEntity(clazz: PsiClass): Boolean {
        val classAnnotationNames = clazz.modifierList?.annotations
            ?.mapNotNull { it.nameReferenceElement?.referenceName }
            ?.toSet()
            .orEmpty()
        if (classAnnotationNames.any { it in MP_ANNOTATION_SHORT_NAMES }) return true
        return clazz.fields.any { field ->
            field.modifierList?.annotations.orEmpty().any {
                it.nameReferenceElement?.referenceName in MP_ANNOTATION_SHORT_NAMES
            }
        }
    }

    /** TableInfo → CrudTableSpec（实体侧适配；列顺序/角色分组/逻辑删除列均继承 EntityResolver 语义）。 */
    internal fun entitySpec(clazz: PsiClass): CrudTableSpec? {
        val table = EntityResolver().resolve(clazz)
        if (table.tableName.isBlank()) return null
        return table.toCrudSpec(clazz.project)
    }

    private fun TableInfo.toCrudSpec(project: Project): CrudTableSpec = CrudTableSpec(
        tableName = tableName,
        columns = columns.map { col ->
            CrudColumn(
                columnName = col.columnName,
                comment = col.comment,
                isPrimaryKey = col.isId,
                autoGenerated = col.idTypeAuto,
                quoteHint = ParamQuote.decide(col.fieldType),
                temporal = ParamQuote.isTemporal(col.fieldType),
                // R48：@TableField(fill=...) 解析值贯通（角标/手填模拟值数据面；DB 侧无此元数据恒 null）
                fill = col.fill,
            )
        },
        logicDeleteColumn = logicDeleteColumn,
        versionColumn = versionColumn,
        dialect = currentDialect(project),
    )

    internal companion object {
        /** MP 注解短名集合（@TableName/@TableId/@TableField/@TableLogic/@Version）。 */
        val MP_ANNOTATION_SHORT_NAMES = setOf("TableName", "TableId", "TableField", "TableLogic", "Version")
    }
}
