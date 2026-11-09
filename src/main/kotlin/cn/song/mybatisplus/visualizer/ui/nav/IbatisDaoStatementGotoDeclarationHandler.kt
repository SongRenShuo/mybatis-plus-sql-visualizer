package cn.song.mybatisplus.visualizer.ui.nav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * T-Ibatis2Support 券 F（车道 L8）：**Java DAO → iBatis XML 语句**跳转接入 IDEA 原生
 * Ctrl+B / Ctrl+Click（`GotoDeclaration`）。
 *
 * ## 为什么是 `GotoDeclarationHandler` 而不是给 Java 侧造 `PsiReference`
 *
 * 与 `MapperNavigationGotoDeclarationHandler` 同款理由：平台顺序是 handler 优先、返回 null 才回落
 * 原生（`GotoDeclarationUtil.findTargetElementsFromProviders` 只遍历 handler，首个非空即返回）。
 * 因此「非 iBatis DAO 调用位置返回 null」即可保证原生跳转零变化。handler 只在用户按键时被调用，
 * **不进 daemon / 索引热路径**（Java 文件量远大于 XML，给每个方法名造引用会在高亮侧反复付成本）。
 *
 * ## 识别口径（jar 实测，非推测）
 *
 * 入口方法集取自 `com.ibatis.sqlmap.client.SqlMapExecutor`（`org.apache.ibatis:ibatis-sqlmap:2.3.4.726`
 * 的 `javap` 实测：`insert(String[,Object])` / `update(String[,Object])` / `delete(String[,Object])` /
 * `queryForObject(String[,Object[,Object]])` / `queryForList(String[,Object[,int,int]] | String,int,int)` /
 * `queryWithRowHandler(String[,Object],RowHandler)` / `queryForPaginatedList(String[,Object],int | String,int)` /
 * `queryForMap(String,Object,String[,String])`）——这一批的 **statementId 都是第一参数**，故取
 * **首参且必须是 String 字面量**（见 [statementIdOf] 注释：不在实参表里搜字面量，避免第二参误判）。
 * `startBatch()` / `executeBatch()` / `executeBatchDetailed()` 不吃 statementId，故不在表内。
 *
 * 判定是**语法级**（方法名 + 首参字面量），不做接收者类型解析——仓内零 `SqlMapClient` 引用可参照，
 * 且 real 工程的 `SqlMapClient` 常经 Spring 代理注入、类型不可解析。**误报由目标存在性兜底**：
 * 目标 sqlMap 里没有该语句 → 返回 null → 不接管（普通 Java 类上的同名调用因此零影响）。
 *
 * ## 解析与落点
 *
 * 解析集中在 [IbatisXmlStatementTargets]（`ns.id` 按 namespace 匹配 sqlMap 文件；裸 id 全项目语句表），
 * 落点 = 命中语句标签的 **`id` 属性值元素**（与 `ui/ref/MybatisXmlTargets.findIdAttributeInFile` 同口径）。
 *
 * ## 线程
 *
 * `getGotoDeclarationTargets` 在 EDT 上被调用。按 `docs/架构设计/PSI跨线程读取协议.md` 形态 A
 * （EDT 直读）本可直接读，但本类仍按仓内红线把 PSI 读取整体包进 [ReadAction.compute]
 * （EDT 内联执行，零额外成本）。任何异常按「本次不接管」处理：回落原生跳转，不弹提示、不写 ERROR。
 *
 * ## 覆盖边界（本期不做）
 *
 * - Kotlin DAO 调用形态（`SqlMapClient` 的 Kotlin 侧调用）未覆盖：本期验收只要求 Java 侧；
 * - Java → XML 反查（XML 语句跳回 DAO 调用点）不做——代价高（需全项目 Java 调用点索引）；
 * - `<selectKey>` 生成的隐式 id（`<insertId>-SelectKey`）不做。
 */
class IbatisDaoStatementGotoDeclarationHandler : GotoDeclarationHandler {

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
        val statementId = statementIdOf(element) ?: return null
        val project = element.project
        if (DumbService.isDumb(project)) return null
        val anchors = IbatisXmlStatementTargets.resolveStatementAnchors(
            project,
            statementId,
            ownerNamespace(element),
        )
        return if (anchors.isEmpty()) null else anchors.toTypedArray()
    }

    /**
     * 光标元素向上找到的最近 `queryForObject(...)` 这类调用，取**首个实参**（必须是 String 字面量）。
     *
     * 只认首参而不在实参表里搜字面量：`javap` 实测 `SqlMapExecutor` 全部重载的 statementId 都在
     * 第一参数位；搜字面量会把 `queryForObject(param, "Tom")` 的第二参误当 statementId。
     */
    private fun statementIdOf(element: PsiElement): String? {
        val call = nearestCall(element) ?: return null
        val name = call.methodExpression.referenceName ?: return null
        if (name !in STATEMENT_ENTRY_METHODS) return null
        val first = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return null
        val value = first.value as? String ?: return null
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun nearestCall(element: PsiElement): PsiMethodCallExpression? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth++ < MAX_UP) {
            if (current is PsiMethodCallExpression) return current
            current = current.parent
        }
        return null
    }

    /** 引用点所属类的限定名（裸 id 的「同 namespace 优先」排序偏好；取不到为 null）。 */
    private fun ownerNamespace(element: PsiElement): String? =
        PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.qualifiedName

    private companion object {
        /** 祖先爬升上限（literal → 实参表 → 调用只需 3 层；留余量给括号/强制转换）。 */
        const val MAX_UP = 6

        /**
         * `SqlMapExecutor` 里吃 statementId 的入口方法——单一事实源在
         * [IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS]（券 B 起跳转图标对与本 handler 共用；
         * 此前本类持有私有副本，清单漂移时会出现「Ctrl+B 认得、箭头不认得」的分裂形态）。
         */
        val STATEMENT_ENTRY_METHODS: Set<String> = IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS
    }
}
