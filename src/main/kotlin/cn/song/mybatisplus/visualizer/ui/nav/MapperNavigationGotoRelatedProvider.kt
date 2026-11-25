package cn.song.mybatisplus.visualizer.ui.nav

import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import javax.swing.Icon

/**
 * T-MapperNavShortcut：`Ctrl+Alt+Home`（Navigate → Related Symbol / `GotoRelated`）的 Mapper 关联项。
 *
 * 与 gutter 箭头同义、只是换成键盘入口：光标在接口方法上时列出对应 XML 语句，光标在 XML 语句上时
 * 列出对应接口方法。平台传入的是**光标处的叶子元素**（javap 实证 `GotoRelatedSymbolAction` 走
 * `file.findElementAt(offset)` 再 `NavigationUtil.collectRelatedItems`），所以两个方向都从祖先链判定。
 *
 * 快捷键归属同 [MapperNavigationGotoDeclarationHandler]：不持有 keymap，跟随平台 `GotoRelated` action。
 */
class MapperNavigationGotoRelatedProvider : GotoRelatedProvider() {

    override fun getItems(element: PsiElement): List<GotoRelatedItem> =
        runCatching { itemsFor(element) }.getOrDefault(emptyList())

    override fun getItems(context: DataContext): List<GotoRelatedItem> = emptyList()

    private fun itemsFor(element: PsiElement): List<GotoRelatedItem> {
        // T-MapperNavNamespaceJump：光标在 `<mapper namespace>` 根标签上 → 接口类（无方法也能列）。
        rootMapperTag(element)?.let { tag ->
            val cls = MapperNavigationTargets.mapperClassOfRootTag(tag)
            if (cls != null) {
                return listOf(
                    RelatedItem(
                        element = cls,
                        label = cls.qualifiedName ?: cls.name ?: "",
                        groupName = GROUP_METHOD,
                        icon = MpvsIcons.MapperMethod,
                    ),
                )
            }
        }
        // T-MapperClassJump：光标在 Mapper 接口类名上 → 对应 XML 根标签。
        (element as? com.intellij.psi.PsiIdentifier)?.parent?.let { owner ->
            if (owner is com.intellij.psi.PsiClass && owner.nameIdentifier === element) {
                val xml = MapperNavigationTargets.xmlFileForMapperInterface(owner)
                val root = xml?.rootTag
                if (root != null) {
                    return listOf(
                        RelatedItem(
                            element = root,
                            label = xml.name ?: "",
                            groupName = GROUP_STATEMENT,
                            icon = MpvsIcons.MapperStatement,
                        ),
                    )
                }
            }
        }
        MapperNavigationTargets.nearestMapperMethod(element)?.let { method ->
            val statements = MapperNavigationTargets.statementTagsFor(method).map { tag ->
                RelatedItem(
                    element = tag,
                    label = statementLabel(tag),
                    groupName = GROUP_STATEMENT,
                    icon = MpvsIcons.MapperStatement,
                )
            }
            if (statements.isNotEmpty()) return statements
        }
        val tag = MapperNavigationTargets.nearestStatementTag(element) ?: return emptyList()
        val method = MapperNavigationTargets.methodFor(tag) ?: return emptyList()
        val owner = method.containingClass?.name
        val label = if (owner.isNullOrEmpty()) method.name else "$owner#${method.name}"
        return listOf(
            RelatedItem(
                element = method,
                label = label,
                groupName = GROUP_METHOD,
                icon = MpvsIcons.MapperMethod,
            ),
        )
    }

    /** 光标元素的最近 XmlTag 是否就是 `<mapper>` 根标签（标签体内的语句标签直接返回 null，不误吞）。 */
    private fun rootMapperTag(element: PsiElement): XmlTag? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth++ < 6) {
            if (current is XmlTag) {
                return current.takeIf { it.name == "mapper" && it.parentTag == null }
            }
            if (current is PsiFile) return null
            current = current.parent
        }
        return null
    }

    /** 语句项展示名：`selectById`（`databaseId` 变体补后缀，避免两项同名看不出区别）。 */
    private fun statementLabel(tag: XmlTag): String {
        val id = tag.getAttributeValue("id") ?: tag.name
        val databaseId = tag.getAttributeValue("databaseId")
        return if (databaseId.isNullOrBlank()) id else "$id [$databaseId]"
    }

    /** 关联项：自定义展示名与图标（默认取元素自身的 name/icon，XML 标签会显示成 `select`）。 */
    private class RelatedItem(
        element: PsiElement,
        private val label: String,
        groupName: String,
        private val icon: Icon,
    ) : GotoRelatedItem(element, groupName) {
        override fun getCustomName(): String = label

        override fun getCustomIcon(): Icon = icon
    }

    private companion object {
        val GROUP_STATEMENT: String = MpSqlVisualizerBundle.message("nav.related.group.mapperStatement")
        val GROUP_METHOD: String = MpSqlVisualizerBundle.message("nav.related.group.mapperMethod")
    }
}
