package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets

/**
 * 只导航不改码的快速修复工厂（NEW-26 §三 列为「优先」的两类之一：跳转到对应方法/参数）。
 *
 * - 「改为 `#{}`」这类**改写类**修复定义在产生它的检查文件里（[DollarInjectionInspection]）；
 * - 「跳转类」修复全部收口在这里——它们只翻文件、不写盘（`startInWriteAction` 返回 false）。
 *
 * PSI 持有纪律（[LocalQuickFix] 契约明令「不要在 fix 实例里存 PSI——inspection 期会大量创建」）：
 * 一律包成 [SmartPsiElementPointer]，在 `applyFix` 时才 resolve。
 */
object NavigationFixes {

    /** 「跳转到方法 `<name>`」（注解 SQL 参数问题用：跳到所在 mapper 方法）。 */
    fun forMethod(method: PsiMethod): Array<LocalQuickFix> {
        val pointer = SmartPointerManager.getInstance(method.project).createSmartPsiElementPointer(method)
        return arrayOf(NavigateToElementFix(pointer, "inspection.sqlv.navigate.method", method.name))
    }

    /**
     * 「跳转到语句 `<id>` 的接口方法」——`#{}` 参数缺失时最有用的去处（去改方法签名）。
     * 接口/方法解析不出就不给修复（只报不改）。
     */
    fun forMissingParameter(tag: XmlTag, statementId: String): Array<LocalQuickFix> {
        val project = tag.project
        val root = (tag.containingFile as? XmlFile)?.rootTag ?: return LocalQuickFix.EMPTY_ARRAY
        val namespace = root.getAttributeValue("namespace") ?: return LocalQuickFix.EMPTY_ARRAY
        if (namespace.isBlank() || statementId.isBlank()) return LocalQuickFix.EMPTY_ARRAY
        val method = runCatching { MybatisXmlTargets.resolveInterfaceMethod(project, namespace, statementId) }
            .getOrNull() ?: return LocalQuickFix.EMPTY_ARRAY
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method)
        return arrayOf(NavigateToElementFix(pointer, "inspection.sqlv.navigate.methodOf", statementId))
    }
}

/** 打开目标元素所在文件并选中它。 */
class NavigateToElementFix internal constructor(
    // `*` 而非 `out PsiElement`：Java 接口 `SmartPsiElementPointer<E extends PsiElement>` 在 Kotlin 侧
    // 不带协变（声明点不变），SmartPsiElementPointer<PsiMethod> 不是 SmartPsiElementPointer<PsiElement>
    private val target: SmartPsiElementPointer<*>,
    private val labelKey: String,
    private val arg: String,
) : LocalQuickFix {

    override fun getFamilyName(): String = MpSqlVisualizerBundle.message("inspection.sqlv.navigate.family")

    override fun getName(): String = MpSqlVisualizerBundle.message(labelKey, arg)

    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = target.element ?: return
        if (!element.isValid) return
        val virtualFile = element.containingFile?.virtualFile ?: return
        val editor = FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, virtualFile, element.textRange.startOffset),
            true,
        ) ?: return
        editor.caretModel.moveToOffset(element.textRange.startOffset)
        editor.selectionModel.setSelection(element.textRange.startOffset, element.textRange.endOffset)
    }
}