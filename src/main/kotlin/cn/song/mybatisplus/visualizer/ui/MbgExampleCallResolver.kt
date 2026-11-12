package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.MbgCriterionMock
import cn.song.mybatisplus.visualizer.xml.MockObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.ConcurrentHashMap

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

    private val LOG = Logger.getInstance(MbgExampleCallResolver::class.java)

    /** MBG Criteria 的方法名：`and` / `or` + 大写驼峰首字母。 */
    private val AND_OR_METHOD = Regex("""^(and|or)[A-Z]\w+$""")

    /** 单次反推的 Criterion 上限（防御性：方法体里可能有大量链式调用）。 */
    private const val MAX_CRITERIA = 20

    /**
     * 缓存条目（T-PreviewOpenPerfM2 M1）：`ReferencesSearch` 是**全项目索引检索**，实测在最小规模
     * demo 上就占装配耗时的 78%（`[open-perf] m2=207` / `total=266ms`），大工程按命中规模放大；
     * 而原实现每次 `preview()` 都重跑。key＝`namespace#statementId`，失效判据＝解析时实际读过的
     * 文件的 `modificationStamp`（mapper 接口 / Example 方法 / 调用点所在文件，任一改动即失效）。
     */
    internal data class CachedResolution(
        val elements: Map<String, List<MockObject>>,
        val stamps: Map<VirtualFile, Long>,
    )

    private val cache = ConcurrentHashMap<String, CachedResolution>()

    /** 诊断/测试缝：缓存命中次数与真正执行检索的次数（单线程测试用，生产仅观测）。 */
    internal var cacheHits: Int = 0
        private set

    internal var searchRuns: Int = 0
        private set

    /** 测试缝：清空缓存与计数。 */
    internal fun resetCacheForTest() {
        cache.clear()
        cacheHits = 0
        searchRuns = 0
    }

    fun resolve(
        project: Project?,
        namespace: String?,
        statementId: String,
    ): Map<String, List<MockObject>>? {
        if (project == null || namespace.isNullOrBlank()) return null
        // key 带 project 身份：本 object 是进程级，多项目窗口打开时同 `namespace#statementId`
        // 会撞车（不同项目可以有同名 mapper）
        val key = "${project.locationHash}#$namespace#$statementId"
        return runCatching {
            ReadAction.compute<Map<String, List<MockObject>>?, RuntimeException> {
                resolveCached(key) { resolveInReadAction(project, namespace, statementId) }
            }
        }.getOrNull()
    }

    /**
     * 缓存外壳（**纯逻辑**，可单测）：命中且依赖文件未变则直接返回；否则执行 [compute] 并落缓存。
     *
     * `compute` 返回 null 表示「解析不到」——**不写缓存**：把「当时索引没就绪 / 找不到调用点」
     * 永久固化下来会让下次打开永远拿不到（宁可比预期多跑一次）。
     */
    internal fun resolveCached(
        key: String,
        compute: () -> CachedResolution?,
    ): Map<String, List<MockObject>>? {
        cache[key]?.let { cached ->
            if (isFresh(cached)) {
                cacheHits++
                LOG.info("[m2] cache hit ${describe(cached)} $key")
                return cached.elements
            }
            LOG.info("[m2] cache stale (依赖文件已改) $key")
        }
        searchRuns++
        val resolved = compute()
        if (resolved == null) {
            // 不写缓存：把「当时索引没就绪 / 找不到调用点」永久固化会让下次打开永远拿不到
            LOG.info("[m2] unresolved（不写缓存） $key")
            return null
        }
        cache[key] = resolved
        LOG.info("[m2] resolved ${describe(resolved)} $key")
        return resolved.elements
    }

    private fun describe(entry: CachedResolution): String =
        "criteria=${entry.elements["criteria.criteria"]?.size ?: 0} stamps=${entry.stamps.size}"

    /** 依赖文件全部仍有效且 stamp 未变才算新鲜（不引文件监听，按需比 stamp）。 */
    private fun isFresh(entry: CachedResolution): Boolean =
        entry.stamps.all { (file, stamp) -> file.isValid && file.modificationStamp == stamp }

    private fun resolveInReadAction(
        project: Project,
        namespace: String,
        statementId: String,
    ): CachedResolution? {
        val trace = StringBuilder()
        val mapperClass = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project))
        if (mapperClass == null) {
            LOG.info("[m2] trace $namespace#$statementId mapperClass=NOT_FOUND")
            return null
        }
        val stamps = mutableMapOf<VirtualFile, Long>()
        fun note(file: PsiFile?) {
            file?.virtualFile?.takeIf { it.isValid }?.let { stamps[it] = it.modificationStamp }
        }
        note(mapperClass.containingFile)
        val mocks = mutableListOf<MbgCriterionMock>()
        val methods = mapperClass.findMethodsByName(statementId, true)
        trace.append("methods=${methods.size} ")
        methods.forEach { method ->
            note(method.containingFile)
            val call = findCallSite(project, method)
            if (call == null) {
                trace.append("noCallSite ")
                return@forEach
            }
            note(call.containingFile)
            val exampleName = exampleVariableName(call)
            if (exampleName == null) {
                trace.append("noExampleArg(arg=${call.argumentList.expressions.firstOrNull()?.text?.take(40)}) ")
                return@forEach
            }
            val body = PsiTreeUtil.getParentOfType(call, PsiCodeBlock::class.java)
            if (body == null) {
                trace.append("noBody ")
                return@forEach
            }
            val found = collectCriteria(project, body, exampleName, trace)
            trace.append("[$exampleName:${found.size}] ")
            mocks += found
        }
        LOG.info("[m2] trace $namespace#$statementId $trace -> criteria=${mocks.size}")
        if (mocks.isEmpty()) return null
        return CachedResolution(
            elements = mapOf(
                "oredCriteria" to listOf(MbgCriterionMock.criteriaOf(mocks)),
                "criteria.criteria" to mocks.map { it.toMockObject() },
            ),
            stamps = stamps,
        )
    }

    /**
     * mapper 方法在本项目里的**首个**调用点。
     *
     * T-PreviewOpenPerfM2 M5 的 `findFirst()` 在真机大工程上返回 null（同一方法 `Find Usages`
     * 有 253 处引用、索引健康）——`Query.findFirst()` 在这条检索链上不可靠。改为 `forEach`
     * 取首个命中（语义等价、仍是提前终止，不把全项目引用收成 List）。
     */
    private fun findCallSite(project: Project, method: PsiMethod): PsiMethodCallExpression? {
        val scope = GlobalSearchScope.projectScope(project)
        // ① 先按「提前终止」取第一个命中（不收集全量）
        var ref: PsiReference? = null
        ReferencesSearch.search(method, scope).forEach { candidate ->
            ref = candidate
            false
        }
        if (ref == null) {
            // ② 诊断：真机上「forEach 也为空」时把总引用数记下来，区分「索引没建」与「检索语义不同」
            val total = runCatching { ReferencesSearch.search(method, scope).findAll().size }.getOrNull()
            LOG.info("[m2] findCallSite empty method=$method totalRefs=$total")
            return null
        }
        return PsiTreeUtil.getParentOfType(ref!!.element, PsiMethodCallExpression::class.java)
    }

    /** 调用实参的变量名（`selectByExample(example)` → `example`）。 */
    private fun exampleVariableName(call: PsiMethodCallExpression): String? =
        (call.argumentList.expressions.firstOrNull() as? PsiReferenceExpression)?.referenceName

    /** 方法体内所有以 example 为根的 `and*` / `or*` 调用。 */
    private fun collectCriteria(
        project: Project,
        body: PsiCodeBlock,
        exampleName: String,
        trace: StringBuilder? = null,
    ): List<MbgCriterionMock> {
        val result = mutableListOf<MbgCriterionMock>()
        var matched = 0
        var noResolve = 0
        var notExample = 0
        var notRooted = 0
        var noCondition = 0
        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).forEach { call ->
            if (result.size >= MAX_CRITERIA) return@forEach
            val name = call.methodExpression.referenceName ?: return@forEach
            if (!AND_OR_METHOD.matches(name)) return@forEach
            matched++
            val method = call.resolveMethod()
            if (method == null) {
                noResolve++
                return@forEach
            }
            if (!isExampleCriteria(method)) {
                notExample++
                return@forEach
            }
            if (!rootedAtExample(call.methodExpression.qualifierExpression, exampleName)) {
                notRooted++
                return@forEach
            }
            val condition = addCriterionCondition(project, method)
            if (condition == null) {
                noCondition++
                return@forEach
            }
            result += MbgCriterionMock(
                condition = condition,
                values = call.argumentList.expressions.map { it.text },
                kind = MbgCriterionMock.kindOf(name),
            )
        }
        trace?.append("(and/or=$matched noResolve=$noResolve notExample=$notExample notRooted=$notRooted noCond=$noCondition) ")
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
