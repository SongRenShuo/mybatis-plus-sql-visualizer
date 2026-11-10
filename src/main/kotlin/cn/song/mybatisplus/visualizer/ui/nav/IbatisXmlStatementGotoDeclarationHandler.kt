package cn.song.mybatisplus.visualizer.ui.nav

import cn.song.mybatisplus.visualizer.ui.gutter.IbatisJavaCallSites
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.util.XmlTagUtil

/**
 * T-IbatisFix4 缺陷一：**iBatis XML 语句 → Java DAO 调用点**的 Ctrl+B / Ctrl+Click 接入。
 *
 * ## 为什么之前只有单向
 *
 * 券 F 的 [IbatisDaoStatementGotoDeclarationHandler] 只做 Java → XML；XML → Java 当时被判
 * 「代价高（需全项目 Java 调用点索引）」而未做。券 B 收尾时 [IbatisJavaCallSites] 已经用
 * **词索引**（`PsiSearchHelper.processElementsWithWord`）实现了这条反查——但它只接了 gutter
 * 箭头（`IbatisXmlToJavaLineMarkerProvider` 的点击处理），没接平台的 GTD 管线。于是真机上
 * 「DAO 里 Ctrl+Click 能跳 XML、XML 里 Ctrl+Click 毫无反应」（用户 2026.3 报障）。
 *
 * ## 为什么是 `GotoDeclarationHandler` 而不是给 `id` 属性造 `PsiReference`
 *
 * 与同包两个 handler 同款理由：`PsiReference.resolve()` 会在高亮期被反复调用，而本方向的解析
 * 是一次**词索引全项目搜索**——造引用等于把索引查询搬进 daemon 热路径。GTD handler 只在用户
 * 按键/悬停时被调用，且「不是 iBatis 语句位置」一律返回 null（平台首个非空 handler 即返回，
 * 其余位置的原生跳转零变化）。
 *
 * ## 认领面（三条，全部 O(1) 语法判定）
 *
 * 1. 光标元素上爬到 `XmlAttributeValue`，其属性名必须是 `id`，宿主标签的父标签是 `<sqlMap>`；
 *    或光标落在**语句起始标签名 token** 上（`<select id=…` 的 `select`）。
 * 2. 宿主标签名 ∈ [IbatisXmlGutterLineMarkerProvider.STATEMENT_TAGS]（券 A 的单一事实源），
 *    与 gutter 图标、箭头用**同一个** `isStatementAnchor` 判据。
 * 3. 根标签名 `sqlMap`（[IbatisXmlStatementTargets.isSqlMapFile]，与 MyBatis 的 `mapper` 天然互斥）。
 *
 * 目标集 = 词索引找到的**全部**调用点首参字面量（通常 1 个，多个时平台弹候选列表——一条语句
 * 被多个 DAO 方法调用是常态）。找不到就是 null（不接管、不弹提示、不报错）。
 *
 * ## 线程
 *
 * `getGotoDeclarationTargets` 在 EDT 上被调用，按仓内红线把 PSI 读取整体包进 [ReadAction.compute]
 * （EDT 内联执行，零额外成本）；dumb 态（索引不可用）直接不接管。
 */
class IbatisXmlStatementGotoDeclarationHandler : GotoDeclarationHandler {

    @Suppress("DEPRECATION") // T-M52 同款：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        return runCatching {
            ReadAction.compute<Array<PsiElement>?, Throwable> { targetsFor(element) }
        }.getOrNull()
    }

    private fun targetsFor(element: PsiElement): Array<PsiElement>? {
        val tag = statementTagOf(element) ?: return null
        if (!IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)) return null
        val file = tag.containingFile
        if (!IbatisXmlStatementTargets.isSqlMapFile(file)) return null
        val statementId = tag.getAttributeValue(ID_ATTRIBUTE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val project = element.project
        if (DumbService.isDumb(project)) return null
        val literals = IbatisJavaCallSites.findAllCallLiterals(
            project,
            IbatisXmlStatementTargets.rootNamespace(file),
            statementId,
            MAX_TARGETS,
        )
        return if (literals.isEmpty()) null else literals.toTypedArray()
    }

    /**
     * 光标元素 → 语句标签（两条锚形态）：
     *  - `id` 属性值（值内任意 leaf 都上爬到 [XmlAttributeValue]）——用户最自然的点击点；
     *  - 起始标签名 token（`<select` 的 `select`）——与 gutter 箭头的锚一致，顺手支持。
     *
     * 其它属性（`resultMap` / `parameterMap` / `refid`）返回 null：那些有各自的引用/落点口径，
     * 不在这里抢。
     */
    private fun statementTagOf(element: PsiElement): XmlTag? {
        val attributeValue = PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false)
        if (attributeValue != null) {
            val attribute = attributeValue.parent as? XmlAttribute ?: return null
            if (attribute.name != ID_ATTRIBUTE) return null
            return attribute.parent as? XmlTag
        }
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return null
        return if (XmlTagUtil.getStartTagNameElement(tag) === element) tag else null
    }

    private companion object {
        const val ID_ATTRIBUTE: String = "id"

        /** 目标表上限：一条语句被大量 DAO 方法调用时，平台候选列表只列前 N 个。 */
        const val MAX_TARGETS: Int = 20
    }
}
