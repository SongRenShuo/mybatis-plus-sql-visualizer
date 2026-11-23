package cn.song.mybatisplus.visualizer.ui.completion

import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wrapper 链状态检测器（F-5，阶段 A2）。
 *
 * 在补全触发时 O(1) 文本判定当前位置的链状态——**无 resolve**，符合 M10 性能红线。
 * 复用 [WrapperLocator] 的文本匹配模式（类名集合 + 工厂方法名集合 + 服务链方法名集合）。
 *
 * 检测逻辑：
 *  1. 从锚点向上找最近的方法调用（含锚点自身）；
 *  2. 沿 `methodExpression.qualifierExpression` 回溯到链首；
 *  3. 链首判定（new 构造 / 工厂调用 / 服务链）→ WrapperType；
 *  4. 收集链首到当前位置之间的已调用方法名。
 *
 * 非 Wrapper 链返回 null（不触发补全，不干扰正常代码）。
 */
object ChainStateDetector {

    /**
     * 链状态检测结果。
     *
     * @param wrapperType       Wrapper 类型
     * @param calledMethods    当前位置之前已调用的方法名（按源码顺序，不含当前正在输入的方法）
     * @param lastMethod       最近一个已调用的方法名（[calledMethods] 末项，便捷访问）；链首时为 null
     * @param isInsideMethodArgs 锚点是否在方法实参列表内（`.eq(|)` 的 `|` 位置）
     * @param currentMethodName  若在实参内，当前所在方法的名称（如 "eq"）；否则为 null
     */
    data class ChainState(
        val wrapperType: WrapperType,
        val calledMethods: List<String>,
        val lastMethod: String?,
        val isInsideMethodArgs: Boolean,
        val currentMethodName: String?,
    )

    /**
     * 检测当前位置的链状态。
     *
     * @param position 补全位置 PSI 元素（[com.intellij.codeInsight.completion.CompletionParameters.position]）
     * @return 链状态；非 Wrapper 链或无法判定时返回 null
     */
    fun detect(position: PsiElement): ChainState? {
        // 判定锚点是否在方法实参列表内（`.eq(|)` 的 `|` 位置）
        val expressionList = PsiTreeUtil.getParentOfType(position, PsiExpressionList::class.java, false)
        val isInsideArgs = expressionList != null

        // 从锚点向上找最近的方法调用（含锚点自身）
        val currentCall = if (expressionList != null) {
            expressionList.parent as? PsiMethodCallExpression
        } else {
            generateSequence(position) { it.parent }
                .filterIsInstance<PsiMethodCallExpression>()
                .firstOrNull()
        } ?: return null

        // 回溯到链首（沿 qualifier 链走到头）
        val chainHead = generateSequence(currentCall) {
            it.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }.lastOrNull() ?: currentCall

        // 链首判定 → WrapperType（纯文本匹配，无 resolve）
        val wrapperType = detectChainHeadType(chainHead) ?: return null

        // 收集已调用方法：
        //  - 实参内（`.eq(|)`）：当前方法正在输入，不含当前方法 → 收集链首到当前方法之间的方法
        //  - 链中（`.eq(...).|`）：当前方法名刚输入完（`referenceName` 存在但方法体不完整），
        //    不含当前方法 → 收集链首到当前方法之间的方法
        // 两种场景均不含当前方法（用户"正在输入"当前方法），故统一排除 currentCall 自身。
        val calledMethods = collectCalledMethods(chainHead, currentCall)

        // 当前方法名（实参内或正在输入方法名时取）
        val currentMethodName = currentCall.methodExpression.referenceName

        // 最近已调用方法：calledMethods 末项（链首之后第一个方法的前一个 = null 合理）
        val lastMethod = calledMethods.lastOrNull()

        return ChainState(
            wrapperType = wrapperType,
            calledMethods = calledMethods,
            lastMethod = lastMethod,
            isInsideMethodArgs = isInsideArgs,
            currentMethodName = currentMethodName,
        )
    }

    // ------------------------------------------------------------------
    // 链首类型判定（纯文本匹配，O(1)，无 resolve）
    // ------------------------------------------------------------------

    /**
     * 链首 Wrapper 类型判定（F-5，复用 [WrapperLocator] 文本模式）：
     *  - new 构造链首 → 按构造类型类名匹配
     *  - 工厂/服务链首 → 按 (qualifier 类名, 方法名) 匹配
     * 非 Wrapper 链首返回 null。
     */
    private fun detectChainHeadType(chainHead: PsiMethodCallExpression): WrapperType? {
        val qualifier = chainHead.methodExpression.qualifierExpression

        // new 构造链首：new LambdaQueryWrapper<X>()
        if (qualifier is PsiNewExpression) {
            // D256 M1：与预览链路同源（自研 Wrapper 子类走父链/规则 4 识别）
            return WrapperLocator.detectWrapperTypeDeep(qualifier.type)
        }

        // 工厂/服务链首：Wrappers.<X>lambdaQuery() / service.lambdaQuery()
        val methodName = chainHead.methodExpression.referenceName ?: return null
        val qualifierName = (qualifier as? PsiReferenceExpression)?.referenceName
        return detectFactoryChainHeadType(qualifierName, methodName)
    }

    /**
     * 工厂/服务链首类型判定（复用 [WrapperLocator.wrapperFactoryClassNames] /
     * [WrapperLocator.wrapperFactoryMethodNames] / [WrapperLocator.serviceChainMethodNames] 文本集合）：
     *  - qualifier 是工厂类（Wrappers/ChainWrappers 等）→ 按 (qualifier, method) 映射
     *  - qualifier 非工厂类（service 等）→ 按服务链方法名映射
     * 纯文本匹配，无 resolve。
     */
    private fun detectFactoryChainHeadType(qualifierName: String?, methodName: String): WrapperType? {
        if (qualifierName != null && qualifierName in WrapperLocator.wrapperFactoryClassNames) {
            return when (methodName) {
                "lambdaQuery" -> WrapperType.LAMBDA_QUERY
                "lambdaUpdate" -> WrapperType.LAMBDA_UPDATE
                "lambdaDelete" -> WrapperType.LAMBDA_DELETE
                "query" -> if (qualifierName in MPJ_FACTORY_CLASS_NAMES) WrapperType.MPJ_QUERY else WrapperType.QUERY
                "update" -> if (qualifierName in MPJ_FACTORY_CLASS_NAMES) WrapperType.UPDATE_JOIN else WrapperType.UPDATE
                "queryChain" -> WrapperType.QUERY
                "lambdaQueryChain" -> WrapperType.LAMBDA_QUERY
                "lambdaUpdateChain" -> WrapperType.LAMBDA_UPDATE
                "lambdaDeleteChain" -> WrapperType.LAMBDA_DELETE
                "updateChain" -> WrapperType.UPDATE
                "deleteChain" -> WrapperType.LAMBDA_DELETE
                "lambda", "lambdaJoin" ->
                    if (qualifierName in MPJ_FACTORY_CLASS_NAMES) WrapperType.MPJ_LAMBDA else null
                "queryJoin" -> if (qualifierName in MPJ_FACTORY_CLASS_NAMES) WrapperType.MPJ_QUERY else null
                "delete" -> if (qualifierName in MPJ_FACTORY_CLASS_NAMES) WrapperType.DELETE_JOIN else null
                else -> null
            }
        }
        // 服务链（service.lambdaQuery() 等）按方法名映射
        if (methodName !in WrapperLocator.serviceChainMethodNames) return null
        return when (methodName) {
            "lambdaQuery" -> WrapperType.LAMBDA_QUERY
            "lambdaUpdate" -> WrapperType.LAMBDA_UPDATE
            "query" -> WrapperType.QUERY
            "update" -> WrapperType.UPDATE
            else -> null
        }
    }

    /** MPJ 工厂类名（JoinWrappers / MPJWrappers）——query/update 映射与 Wrappers 不同。 */
    private val MPJ_FACTORY_CLASS_NAMES: Set<String> = setOf("JoinWrappers", "MPJWrappers")

    // ------------------------------------------------------------------
    // 已调用方法收集
    // ------------------------------------------------------------------

    /**
     * 收集链首到当前调用之间的已调用方法名（按源码顺序，不含 currentCall 自身）。
     *
     * 用户"正在输入" currentCall（实参内或刚输入方法名），故 currentCall 不算已调用。
     * 沿 qualifier 链从 currentCall.qualifier 回溯到 chainHead，收集途经的方法调用名
     * （不含 chainHead 自身——链首是 new 构造或工厂调用，非 Wrapper 操作）。
     *
     * @param chainHead   链首调用
     * @param currentCall 当前调用（锚点所在，不计入已调用）
     */
    private fun collectCalledMethods(chainHead: PsiMethodCallExpression, currentCall: PsiMethodCallExpression): List<String> {
        val methods = mutableListOf<String>()
        // 从 currentCall.qualifier 回溯到 chainHead（不含 currentCall 自身）
        var cursor = currentCall.methodExpression.qualifierExpression as? PsiMethodCallExpression
        while (cursor != null && cursor !== chainHead) {
            cursor.methodExpression.referenceName?.let { methods.add(it) }
            cursor = cursor.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }
        // chainHead 是 Wrapper 操作（qualifier 是 new 构造）时计入已调用；
        // chainHead 是工厂调用（Wrappers.lambdaQuery()）时不计入（非 Wrapper 操作）。
        if (cursor === chainHead && chainHead.methodExpression.qualifierExpression is PsiNewExpression) {
            chainHead.methodExpression.referenceName?.let { methods.add(it) }
        }
        // 回溯收集是逆序的（从近到远），反转恢复源码顺序
        return methods.asReversed()
    }
}
