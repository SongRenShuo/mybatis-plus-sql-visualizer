package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.EwPlaceholder
import cn.song.mybatisplus.visualizer.xml.XmlEwExpander
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * MP 占位符 Java 侧关联（T-M26-B，P-立项-03）：`${ew.customSqlSegment}` 等占位 → wrapper 实参 → SQL 片段。
 *
 * 流程（对齐 MP 运行时语义）：
 *  1. mapper 的 namespace → Java 接口类（findClass）；
 *  2. 语句 id → 接口同名方法；
 *  3. 方法参数中定位 wrapper 参数（@Param 名 == 占位变量名，或类型为 MP Wrapper）；
 *  4. ReferencesSearch 找调用处 → 取 wrapper 实参表达式（按参数位置对应）；
 *  5. 实参走 [SqlPreviewService.preview]（复用 Wrapper 全链路：消费语义/逻辑删除/方言）→ 完整 SQL
 *     → 按片段类型提取（[XmlEwExpander]）；
 *  6. 多调用处取首个 + 提示；无调用处/类/方法 → warning（预览降级为占位求值为空提示）。
 *
 * 边界（文档化）：实参为跨方法传递的变量时依赖现有 WrapperLocator 追踪能力；
 * 多调用处仅展开第一处（提示其余数量）；wrapper 类型按简单名匹配（LambdaQueryWrapper/QueryWrapper/UpdateWrapper 等）。
 */
object XmlEwResolver {

    private val LOG = Logger.getInstance(XmlEwResolver::class.java)

    /** 解析结果：变量名 → mock 属性值（customSqlSegment/sqlSelect/sqlSet 片段）。 */
    fun resolve(
        namespace: String?,
        statementId: String,
        placeholders: List<EwPlaceholder>,
        project: Project,
        warnings: MutableList<String>,
        callFinder: (PsiMethod) -> List<PsiElement> = { method ->
            // T-StartupReadLockFreeze S5（同族面）：`ReferencesSearch` 依赖索引，索引构建期会同步等待
            // ——预览装配线程在这里持锁等索引，撞上 EDT 的写锁升级就是冻结（计划 §三）。
            // 与 MapperNavigationTargets 同口径：dumb 时降级为空（预览退回占位提示），不做检索。
            val startedMs = System.currentTimeMillis()
            if (DumbService.isDumb(method.project)) {
                LOG.info(
                    "[m2] resolve skip reason=dumb-mode stage=ew-refs " +
                        "elapsedMs=${System.currentTimeMillis() - startedMs} method=${method.name}",
                )
                emptyList()
            } else {
                ReferencesSearch.search(method).findAll().map { it.element }
            }
        },
    ): Map<String, Map<String, String>> {
        if (placeholders.isEmpty()) return emptyMap()
        if (namespace.isNullOrEmpty()) {
            warnings += "mapper 缺 namespace，无法关联 Java Mapper 方法展开 \${${placeholders.first().variable}...}"
            return emptyMap()
        }
        val mapperClass = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project))
        if (mapperClass == null) {
            warnings += "找不到 Mapper 接口 $namespace，无法展开 MP 占位 \${${placeholders.first().variable}...}"
            return emptyMap()
        }
        val method = mapperClass.findMethodsByName(statementId, false).firstOrNull()
        if (method == null) {
            warnings += "Mapper 接口 $namespace 无方法 $statementId，无法展开 MP 占位"
            return emptyMap()
        }

        // 占位变量名 → wrapper 参数（@Param 名匹配优先，其次按类型）
        val wrapperParam = findWrapperParam(method, placeholders)
        if (wrapperParam == null) {
            warnings += "方法 $statementId 无 Wrapper 参数（${placeholders.joinToString(", ") { it.variable }}），无法展开 MP 占位"
            return emptyMap()
        }

        // 调用处 → wrapper 实参（callFinder 可注入：生产 ReferencesSearch，测试轻量查找）
        val calls = callFinder(method)
        if (calls.isEmpty()) {
            warnings += "未找到 Mapper 方法 $statementId 的调用，无法展开 MP 占位（可在预览中填写覆盖值）"
            return emptyMap()
        }
        if (calls.size > 1) {
            warnings += "Mapper 方法 $statementId 有 ${calls.size} 处调用，取第一处展开"
        }
        val call = calls.first()
        // 调用元素可能是方法名标识符（生产 ReferencesSearch）或调用表达式本身（轻量查找）→ 统一取调用表达式
        val callExpression = if (call is PsiMethodCallExpression) call
        else PsiTreeUtil.getParentOfType(call, PsiMethodCallExpression::class.java) ?: return emptyMap()
        val argIndex = method.parameterList.parameters.indexOf(wrapperParam)
        val arg = callExpression.argumentList.expressions.getOrNull(argIndex) ?: return emptyMap()

        // wrapper 实参 → 完整 SQL（复用 Wrapper 预览全链路）→ 按片段类型提取
        val service = project.getService(SqlPreviewService::class.java)
        val sql = service.preview(arg)?.sql ?: return emptyMap()
        return mapOf(wrapperParamName(wrapperParam) to extractFragments(sql, placeholders))
    }

    /** 按片段类型提取（同变量多个占位 → 各自片段）。 */
    private fun extractFragments(sql: String, placeholders: List<EwPlaceholder>): Map<String, String> {
        val fragments = mutableMapOf<String, String>()
        placeholders.forEach { ph ->
            val fragment = when (ph.kind) {
                "customSqlSegment" -> XmlEwExpander.extractWhere(sql)
                "sqlSelect" -> XmlEwExpander.extractSelect(sql)
                else -> XmlEwExpander.extractSet(sql)
            }
            if (fragment != null) fragments[ph.kind] = fragment
        }
        return fragments
    }

    /** 定位 wrapper 参数：@Param 名命中占位变量优先；否则类型简单名含 Wrapper。 */
    private fun findWrapperParam(method: PsiMethod, placeholders: List<EwPlaceholder>): PsiParameter? {
        val params = method.parameterList.parameters
        // 1. @Param 名匹配
        placeholders.forEach { ph ->
            params.firstOrNull { param ->
                param.annotations.any { it.qualifiedName?.substringAfterLast('.') == "Param" &&
                    it.findAttributeValue(null)?.text?.trim('"') == ph.variable }
            }?.let { return it }
        }
        // 2. 类型简单名含 Wrapper（LambdaQueryWrapper/QueryWrapper/UpdateWrapper/AbstractWrapper 等；
        //     Light 测试等类型未解析场景 canonicalText 为短名，不要求全限定名）
        return params.firstOrNull { param ->
            param.type.canonicalText.substringAfterLast('.').contains("Wrapper")
        }
    }

    /** wrapper 参数名（@Param 值优先，用于 mock 变量名对齐占位）。 */
    fun wrapperParamName(param: PsiParameter): String {
        val paramAnno = param.annotations.firstOrNull { it.qualifiedName?.substringAfterLast('.') == "Param" }
        return paramAnno?.findAttributeValue(null)?.text?.trim('"')?.takeIf { it.isNotEmpty() } ?: param.name
    }
}
