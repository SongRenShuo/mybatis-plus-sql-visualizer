package cn.song.mybatisplus.visualizer.ui.nav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlToken

/**
 * T-MapperNavShortcut：Mapper 双向跳转接入 IDEA 原生 **Ctrl+B / Ctrl+Click**（`GotoDeclaration`）。
 *
 * ## 为什么用 `GotoDeclarationHandler` 而不是给 Java 侧补 `PsiReference`
 *
 * - 平台顺序是 **handler 优先、返回 null 才回落原生**（javap 实证 `GotoDeclarationUtil`：
 *   `findTargetElementsFromProviders` 只遍历 handler，首个非空即返回）。因此「非 Mapper 位置
 *   返回 null」即可保证原生跳转零变化，不存在「引用解析不到 → Cannot find declaration」风险。
 * - handler 只在用户按键时被调用，不进 daemon/索引热路径；Java 文件量远大于 mapper XML，
 *   给每个接口方法名造 `PsiReference` 会在高亮侧反复付成本。
 *
 * ## 快捷键归属（用户 2026-11-05 明确要求）
 *
 * 本类不持有任何 keymap，挂在平台 action 上——用户在 `Settings → Keymap` 把 Declaration
 * 改绑成别的键、加第二快捷键、改 Ctrl+Click 鼠标快捷键，本功能全部自动跟随；插件侧没有
 * 第二份键位配置需要同步。
 *
 * ## 覆盖方向
 *
 * - Java 接口方法名 → XML 同名语句（落点取语句 `id` 的文本 token）；
 * - XML 语句的起始标签名 / `id` 属性名 / `id` 属性值 → 接口方法。
 *
 * 解析与判定集中在 [MapperNavigationTargets]，本类只做「元素形态分流」。
 */
class MapperNavigationGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        // 任何解析异常都按「本次不接管」处理：回落原生跳转，不弹提示、不写 ERROR
        return runCatching {
            when (element) {
                is PsiIdentifier -> javaToXml(element)
                is XmlToken -> xmlToJava(element)
                else -> null
            }
        }.getOrNull()
    }

    private fun javaToXml(identifier: PsiIdentifier): Array<PsiElement>? {
        val method = identifier.parent as? PsiMethod ?: return null
        if (method.nameIdentifier !== identifier) return null
        val tags = MapperNavigationTargets.statementTagsFor(method)
        if (tags.isEmpty()) return null
        return tags.map { MapperNavigationTargets.statementAnchor(it) }.toTypedArray()
    }

    private fun xmlToJava(token: XmlToken): Array<PsiElement>? {
        val tag = MapperNavigationTargets.ownerStatementTag(token) ?: return null
        val method = MapperNavigationTargets.methodFor(tag) ?: return null
        return arrayOf(method)
    }
}
