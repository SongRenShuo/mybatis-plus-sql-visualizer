package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.MbgCriterionMock
import cn.song.mybatisplus.visualizer.xml.MockObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * MBG Example 调用点解析（T-MbgExamplePreview M2）。
 *
 * MBG 生成器的 Example 用法固定为
 * `example.createCriteria().andCustIdEqualTo(v)` + `mapper.selectByExample(example)`，
 * 而 `andXxxYyy` 的方法体固定是一句 `addCriterion("cust_id =", value, "custId")`。
 * 所以反推 Criterion 不需要猜列名：从 mapper 方法的调用点拿到 example 变量，
 * 在同一方法体里找 `and*` / `or*` 调用，再读对应方法的 `addCriterion` 第一个实参常量即可。
 *
 * 产出直接喂给 [cn.song.mybatisplus.visualizer.xml.XmlPreviewModel.from] 的
 * `collectionElements`：
 *  - `oredCriteria` → 一个 Criteria 元素（`valid=true` + `criteria` 列表）
 *  - `criteria.criteria` → 上述 Criterion 元素
 *
 * 全部失败路径返回 null（无 project / 接口不可解析 / 找不到调用点 / 方法体没有 addCriterion 常量），
 * 上游据此退回升级前的「无属性元素」行为——不报错、不改变既有预览。
 */
object MbgExampleCallResolver {

    /** MBG Criteria 的方法名：`and` / `or` + 大写驼峰首字母。 */
    private val AND_OR_METHOD = Regex("""^(and|or)[A-Z]\w+$""")

    /** 单次反推的 Criterion 上限（防御性：方法体里可能有大量链式调用）。 */
    private const val MAX_CRITERIA = 20

    fun resolve(
        project: Project?,
        namespace: String?,
        statementId: String,
    ): Map<String, List<MockObject>>? {
        if (project == null || namespace.isNullOrBlank()) return null
        return runCatching {
            ReadAction.compute<Map<String, List<MockObject>>?, RuntimeException> {
                resolveInReadAction(project, namespace, statementId)
            }
        }.getOrNull()
    }

    private fun resolveInReadAction(
        project: Project,
        namespace: String,
        statementId: String,
    ): Map<String, List<MockObject>>? {
        val mapperClass = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project)) ?: return null
        val mocks = mutableListOf<MbgCriterionMock>()
        mapperClass.findMethodsByName(statementId, true).forEach { method ->
            val call = findCallSite(project, method) ?: return@forEach
            val exampleName = exampleVariableName(call) ?: return@forEach
            val body = PsiTreeUtil.getParentOfType(call, PsiCodeBlock::class.java) ?: return@forEach
            mocks += collectCriteria(project, body, exampleName)
        }
        if (mocks.isEmpty()) return null
        return mapOf(
            "oredCriteria" to listOf(MbgCriterionMock.criteriaOf(mocks)),
            "criteria.criteria" to mocks.map { it.toMockObject() },
        )
    }

    /** mapper 方法在本项目里的首个调用点。 */
    private fun findCallSite(project: Project, method: PsiMethod): PsiMethodCallExpression? =
        ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
            .findAll()
            .asSequence()
            .mapNotNull { ref: PsiReference ->
                PsiTreeUtil.getParentOfType(ref.element, PsiMethodCallExpression::class.java)
            }
            .firstOrNull()

    /** 调用实参的变量名（`selectByExample(example)` → `example`）。 */
    private fun exampleVariableName(call: PsiMethodCallExpression): String? =
        (call.argumentList.expressions.firstOrNull() as? PsiReferenceExpression)?.referenceName

    /** 方法体内所有以 example 为根的 `and*` / `or*` 调用。 */
    private fun collectCriteria(
        project: Project,
        body: PsiCodeBlock,
        exampleName: String,
    ): List<MbgCriterionMock> {
        val result = mutableListOf<MbgCriterionMock>()
        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).forEach { call ->
            if (result.size >= MAX_CRITERIA) return@forEach
            val name = call.methodExpression.referenceName ?: return@forEach
            if (!AND_OR_METHOD.matches(name)) return@forEach
            val method = call.resolveMethod() ?: return@forEach
            if (!isExampleCriteria(method)) return@forEach
            if (!rootedAtExample(call.methodExpression.qualifierExpression, exampleName)) return@forEach
            val condition = addCriterionCondition(project, method) ?: return@forEach
            result += MbgCriterionMock(
                condition = condition,
                values = call.argumentList.expressions.map { it.text },
                kind = MbgCriterionMock.kindOf(name),
            )
        }
        return result
    }

    /** 方法是否属于 MBG 的 `XxxExample`（或其内部类 `Criteria`）。 */
    private fun isExampleCriteria(method: PsiMethod): Boolean {
        val owner = method.containingClass ?: return false
        return owner.name?.endsWith("Example") == true ||
            owner.containingClass?.name?.endsWith("Example") == true
    }

    /**
     * 调用链的根是否指向 example 变量。
     *
     * 覆盖两种写法：链式 `example.createCriteria().andXxx(v)`（qualifier 最左端是 example 引用）
     * 与分步 `Criteria c = example.createCriteria(); c.andXxx(v)`（根变量类型名含 Example / Criteria）。
     */
    private fun rootedAtExample(expr: PsiExpression?, exampleName: String): Boolean {
        var cur = expr
        while (cur is PsiMethodCallExpression) cur = cur.methodExpression.qualifierExpression
        val ref = cur as? PsiReferenceExpression ?: return false
        if (ref.referenceName == exampleName) return true
        return runCatching {
            val variable = ref.resolve() as? PsiVariable ?: return@runCatching false
            val type = variable.type as? PsiClassType ?: return@runCatching false
            type.className?.endsWith("Example") == true || type.className == "Criteria"
        }.getOrDefault(false)
    }

    /** `andXxxYyy` 方法体里 `addCriterion("<condition>", ...)` 的第一个实参常量。 */
    private fun addCriterionCondition(project: Project, method: PsiMethod): String? {
        val body = method.body ?: return null
        val call = PsiTreeUtil.findChildOfType(body, PsiMethodCallExpression::class.java) ?: return null
        if (call.methodExpression.referenceName != "addCriterion") return null
        val arg = call.argumentList.expressions.firstOrNull() ?: return null
        return JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper.computeConstantExpression(arg) as? String
    }
}
