package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ConsumptionKind
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperConsumption
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wrapper 定位器（M1-02，F-01 入口）：从光标所在元素定位 Wrapper 调用链。
 *
 * 支持三种写法（对照 docs/项目介绍.md）：
 *  1. 链式：`new LambdaQueryWrapper<Entity>().eq(...).like(...)`
 *  2. 分步：`LambdaQueryWrapper<Entity> wrapper = new LambdaQueryWrapper<>(); wrapper.eq(...)`
 *  3. 混合：初始化链 + 后续分步调用
 *
 * 当前实现聚焦 Java PSI（项目示例为 Java 代码）；Kotlin 文件支持列入后续里程碑。
 */
object WrapperLocator {

    /**
     * 从锚点元素定位并解析 Wrapper 调用链。
     *
     * @param anchor 光标所在元素（编辑器 PSI 元素）
     * @return 调用链模型；未找到 Wrapper 或类型不支持时返回 null
     */
    fun locate(anchor: PsiElement): WrapperChain? = locateWithTable(anchor)?.chain

    /**
     * 定位并同时返回实体表信息（供 converter 全链路使用）。
     *
     * 支持三类 Wrapper 来源：
     *  - 变量声明（`LambdaQueryWrapper<X> w = ...`，含分步续链）
     *  - 内联 new 表达式（`list(new MPJLambdaQueryWrapper<X>().select(...).eq(...))`，无变量）
     *  - Wrappers 静态工厂 / 服务链（`Wrappers.<X>lambdaQuery()...` / `service.lambdaQuery()...`）
     * T-M23.7：末尾追加 [locateFromChainCall] 兜底——链中/链尾（eq/list 调用处）任意位置定位。
     */
    fun locateWithTable(
        anchor: PsiElement,
        resolveVariables: Boolean = true,
        preferredConsumption: ConsumptionKind? = null,
    ): LocatedWrapper? {
        val extension = anchor.containingFile?.virtualFile?.extension
        if (extension == "kt" || extension == "kts") return null
        findWrapperVariable(anchor)?.let { return buildChain(it, resolveVariables, anchor, preferredConsumption) }
        findWrapperNewExpression(anchor)?.let { return buildChainFromNew(it, resolveVariables, anchor, preferredConsumption) }
        findWrapperFactoryCall(anchor)?.let { return buildChainFromFactory(it, resolveVariables, anchor, preferredConsumption) }
        locateFromChainCall(anchor, resolveVariables, preferredConsumption)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------
    // T-M23.7：链中/链尾任意位置定位（eq/list 调用处）与选择区扫描
    // ------------------------------------------------------------------

    /**
     * 从**任意相关位置**定位 wrapper 链（用户需求：光标在 `eq` 调用处、`.list()` 调用处等
     * 链中/链尾也可触发）：
     *  1. 沿 parent 链找**最近的方法调用**（含锚点自身）；
     *  2. 沿 `methodExpression.qualifierExpression` **回溯到链首**（与工厂链收集同构）；
     *  3. 链首判定（[isChainHeadCall]）：new wrapper 构造 → [buildChainFromNew]；
     *     Wrappers 工厂/服务链 → [buildChainFromFactory]；
     *  4. 非 wrapper 链首（普通方法调用，如 `helper.applyFilter(w)`）→ null（防误伤）。
     *
     * 仅用户显式触发（快捷键/右键）时调用，无 M10 daemon 性能红线。
     */
    private fun locateFromChainCall(
        anchor: PsiElement,
        resolveVariables: Boolean,
        preferredConsumption: ConsumptionKind? = null,
    ): LocatedWrapper? {
        val call = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull() ?: return null
        // 回溯到链首（qualifier 不再是方法调用为止）；sequence 至少含 call 自身，兜底自身
        val chainHead = generateSequence(call) {
            it.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }.lastOrNull() ?: call
        return when {
            chainHead.methodExpression.qualifierExpression is PsiNewExpression ->
                (chainHead.methodExpression.qualifierExpression as PsiNewExpression)
                    .takeIf { detectWrapperType(it.type) != null }
                    ?.let { buildChainFromNew(it, resolveVariables, anchor, preferredConsumption) }
            isWrapperFactoryCall(chainHead) -> buildChainFromFactory(chainHead, resolveVariables, anchor, preferredConsumption)
            else -> null
        }
    }

    /**
     * T-M23.7：调用是否为 wrapper **链首**（new wrapper 构造链首 / 工厂·服务链首）。
     * internal 供选择区扫描（[isSqlCandidate]）与 ConvertToSqlAction 复用。
     */
    internal fun isChainHeadCall(call: PsiMethodCallExpression): Boolean {
        val qualifier = call.methodExpression.qualifierExpression
        return (qualifier is PsiNewExpression && detectWrapperType(qualifier.type) != null) ||
            isWrapperFactoryCall(call)
    }

    /** T-M23.7：链首 wrapper 类型（new 构造 → 构造类型；工厂/服务链 → 工厂类型）；非链首 null。 */
    private fun wrapperTypeOfChainHead(call: PsiMethodCallExpression): WrapperType? {
        val qualifier = call.methodExpression.qualifierExpression
        return when {
            qualifier is PsiNewExpression -> detectWrapperType(qualifier.type)
            isWrapperFactoryCall(call) -> factoryWrapperType(call)
            else -> null
        }
    }

    /**
     * T-M23.7：调用是否为 SQL 候选——**wrapper 链首** 或 **批量操作方法调用**
     * （`getById`/`save`/`removeById`/`selectAll` 等，注册表 [BatchOperationConverter.batchOperationMethodNames]；
     * 用户实测：选择区含 `bannerService.getById(id)` 时也应进多 SQL 选择列表）。
     * T-WriteFamily（R4）：写族新增的 `delete`/`update`/`remove` 措辞与链工厂/终结点撞名，
     * 须叠加「空 Wrapper 精确形态 + MP receiver」门（[BatchOperationConverter.isBatchOperationCall]），
     * 否则 `redisTemplate.delete(null)` / `list.remove(0)` 会进候选列表。
     * internal 供选择区扫描（[sqlCandidatesInRange]）与 ConvertToSqlAction 复用。
     */
    internal fun isSqlCandidate(call: PsiMethodCallExpression): Boolean =
        isChainHeadCall(call) || BatchOperationConverter.isBatchOperationCall(call)

    /**
     * T-M23.7：扫描选择区（offset 范围）内全部 SQL 候选调用（wrapper 链首 + 批量操作方法，
     * 按 offset 去重排序）——同一链的 eq/list 多处只计 1 个；批量方法（getById 等）单调用各计 1；
     * 嵌套子查询链首在实参子树内为独立链首（合理行为，文档化）。
     *
     * @param file  目标文件
     * @param start 选择区起始 offset（含）
     * @param end   选择区结束 offset（不含）
     */
    fun sqlCandidatesInRange(file: PsiFile, start: Int, end: Int): List<PsiMethodCallExpression> =
        PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression::class.java)
            .filter { it.textRange.startOffset in start until end && isSqlCandidate(it) }
            .distinctBy { it.textRange.startOffset }
            .sortedBy { it.textRange.startOffset }

    /**
     * T-M23.7：多 SQL 选择列表项摘要：`L{行号} {Wrapper 类型} {表名}`（wrapper 链首）/
     * `L{行号} {方法名}`（批量方法调用，如 `L18 getById`）。行号按文档行（1-based）。
     */
    fun sqlCandidateSummary(call: PsiMethodCallExpression): String {
        val line = runCatching {
            val doc = PsiDocumentManager.getInstance(call.project).getDocument(call.containingFile)
            doc?.getLineNumber(call.textRange.startOffset)?.plus(1) ?: 0
        }.getOrDefault(0)
        return if (isChainHeadCall(call)) {
            val typeName = wrapperTypeOfChainHead(call)?.name ?: "?"
            val tableName = locateWithTable(call)?.table?.tableName
            "L$line $typeName${tableName?.let { "  $it" } ?: ""}"
        } else {
            "L$line ${call.methodExpression.referenceName ?: "?"}"
        }
    }

    /**
     * T-M23.8：候选调用所在**代码行完整文本**（trim；选择弹窗第二行预览）。
     * 文档不可得时返回空串（兜底，不抛异常）。
     */
    fun sqlCandidateLineText(call: PsiMethodCallExpression): String {
        val doc = PsiDocumentManager.getInstance(call.project).getDocument(call.containingFile) ?: return ""
        val line = doc.getLineNumber(call.textRange.startOffset)
        return doc.getText(TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim()
    }

    /**
     * T-M23.8：候选元素所在行的 `(行首, 行尾)` offset（编辑器定位跳转用——
     * 光标到行首 + 整行选中；T-M29.6 泛化支持任意 PSI 元素：Wrapper 链首/XML 语句/注解）。
     * 文档不可得时回退元素自身范围（兜底）。
     */
    fun lineOffsetsOf(element: PsiElement): Pair<Int, Int> {
        val doc = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
            ?: return element.textRange.startOffset to element.textRange.endOffset
        val line = doc.getLineNumber(element.textRange.startOffset)
        return doc.getLineStartOffset(line) to doc.getLineEndOffset(line)
    }

    // ------------------------------------------------------------------
    // 定位 Wrapper 变量
    // ------------------------------------------------------------------

    /**
     * 从锚点向上查找 Wrapper 类型变量。
     *
     * 查找路径：
     *  - 锚点本身/父链上的局部变量或字段（类型为 Wrapper）
     *  - 锚点在方法调用上时，其 qualifier 引用的变量
     */
    private fun findWrapperVariable(anchor: PsiElement): PsiVariable? {
        var current: PsiElement? = anchor
        while (current != null) {
            when (current) {
                is PsiLocalVariable -> if (isWrapperVariable(current)) return current
                is PsiField -> if (isWrapperVariable(current)) return current
                is PsiParameter -> if (isWrapperVariable(current)) return current
                // T-PreviewStmtTypeScope：赋值表达式右侧的 wrapper 构造/工厂链（`w = new LambdaQueryWrapper<>()`
                // 的 new 锚点、`w = Wrappers.lambdaQuery()...`）按目标变量走变量路径——buildChainFromNew /
                // buildChainFromFactory 只收集赋值表达式内部的链，会丢掉该变量后续的 .eq 语句与消费调用
                // （demo 语料 WrapperReuseRepro 重新赋值行实测：预览退化成无 WHERE 的 SELECT）。
                // LHS 不是 wrapper 变量时不命中，继续向上（不拦截普通赋值）。
                is PsiAssignmentExpression -> {
                    val target = (current.lExpression as? PsiReferenceExpression)?.resolve() as? PsiVariable
                    if (target != null && isWrapperVariable(target)) return target
                }
                // 实参位置的 wrapper 引用（`update(null, wrapper)` / `selectCount(wrapper)`，U08，T-M15-05）：
                // 直接 resolve 引用到 wrapper 变量即命中（isWrapperVariable 类型检查兜底，不会误判普通变量）
                is PsiReferenceExpression -> {
                    val resolved = current.resolve() as? PsiVariable
                    if (resolved != null && isWrapperVariable(resolved)) return resolved
                }
                is PsiMethodCallExpression -> {
                    // ① 直接 qualifier 引用：wrapper.eq(...) / wrapper.lambda()
                    val qualifier = current.methodExpression.qualifierExpression as? PsiReferenceExpression
                    val resolved = qualifier?.resolve() as? PsiVariable
                    if (resolved != null && isWrapperVariable(resolved)) return resolved
                    // ② 链式中间节点：set(...) 的 qualifier 是 eq 调用——沿 qualifier 回溯到链首
                    //    （`wrapper.lambda().eq(...).set(...)` 的 lambda()，U09-④），仅在当前表达式内，不会跨方法误命中
                    val chainHead = generateSequence(current) {
                        it.methodExpression.qualifierExpression as? PsiMethodCallExpression
                    }.lastOrNull()
                    val headQualifier = chainHead?.methodExpression?.qualifierExpression as? PsiReferenceExpression
                    val headResolved = headQualifier?.resolve() as? PsiVariable
                    if (headResolved != null && isWrapperVariable(headResolved)) return headResolved
                    // ③ 实参位置的 wrapper（`update(null, wrapper)` / `selectCount(wrapper)`，U08，T-M15-05）：
                    //    只查**直接实参**的引用（不递归嵌套调用，避免跨方法误命中；内联工厂走 findWrapperFactoryCall）
                    current.argumentList.expressions.firstNotNullOfOrNull { arg ->
                        ((arg as? PsiReferenceExpression)?.resolve() as? PsiVariable)?.takeIf { isWrapperVariable(it) }
                    }?.let { return it }
                }
            }
            current = current.parent
        }
        return null
    }

    /**
     * 变量是否为 Wrapper（含基类型降级）：类型直接是具体 Wrapper，或类型为抽象基类
     * （`Wrapper<X>` / `AbstractWrapper<X>`）但初始化表达式链首是具体 Wrapper 构造 / Wrappers 工厂。
     *
     * 降级必须限定基类型（T-M15-01 回归）：`long total = service.lambdaQuery()...count()` 的 total
     * 类型不是 Wrapper 基类，若仅按 initializer 链首判断会被误判为 wrapper 变量（链被截断、table 为 null）。
     *
     * O-8（缺陷 A）：`var` 声明（LightPlatform 下类型推断不可靠，常落 Object/未知类型）——
     * initializer 链头为 wrapper 构造/工厂 **且链上无终结消费方法** 时接受为 wrapper 变量，
     * 使 var 形态走变量路径（ReferencesSearch 消费检测天然生效：`service.remove(w)` 等不再整体丢失）。
     * 终结点黑名单守卫（[initializerIsWrapperChainWithoutTerminal]，单一来源 [chainTerminalMethodNames]）
     * 保住 T-M15-01/T-M19.5 反向语义：`var d = service.lambdaQuery()...one()` 的结果变量仍被拒绝。
     *
     * M19.5（T-M19.5-01a）起为 internal：供 [WrapperAnchorDetector] 复用（结果变量赋值形态
     * 不应抑制服务链锚点，见 20260813-1536 开发计划）。判定为 O(1) 纯语法（文本匹配 + initializer
     * 链首回溯，无 resolve），符合 M10 性能红线。
     */
    internal fun isWrapperVariable(variable: PsiVariable): Boolean {
        if (detectWrapperType(variable.type) != null) return true
        // var 声明的语法判定走 typeElement 文本（不依赖 getType() 推断成败，O(1) 纯文本）；
        // var 仅限局部变量，字段/形参不可能命中
        if ((variable as? PsiLocalVariable)?.typeElement?.text == "var") {
            return initializerIsWrapperChainWithoutTerminal(variable.initializer)
        }
        val typeName = (variable.type as? PsiClassType)?.className ?: return false
        if (typeName !in wrapperBaseTypeNames) return false
        return detectWrapperTypeFromInitializer(variable.initializer) != null
    }

    /**
     * O-8（缺陷 A）：从 initializer 链头推断 Wrapper 类型（var 推断形态专用，仅 [buildChain] 的
     * var 分支调用，不改变非 var 声明的既有判定）。
     * 与 [detectWrapperTypeFromInitializer] 的差异：**工厂调用自身即链首**——
     * `Wrappers.<Device>query().eq(...)` 沿 qualifier 回溯的终点是 Wrappers 引用（非调用/new），
     * 原实现落 `else -> null` 恒不识别工厂链头；本实现取链上**最后一次调用**为链首候选
     * （isWrapperFactoryCall 门控，与 [findWrapperFactoryCall] 同一判定），new 构造链头同 [detectWrapperTypeFromInitializer]。
     */
    private fun wrapperTypeFromInitializerChain(init: PsiElement?): WrapperType? {
        var expr: PsiElement? = init ?: return null
        var lastCall: PsiMethodCallExpression? = null
        while (expr is PsiMethodCallExpression) {
            lastCall = expr
            expr = expr.methodExpression.qualifierExpression
        }
        return when {
            expr is PsiNewExpression -> detectWrapperType(expr.type)
            lastCall != null && isWrapperFactoryCall(lastCall) -> factoryWrapperType(lastCall)
            else -> null
        }
    }

    /**
     * initializer 是否为「无终结消费方法」的 wrapper 构造/工厂链（O-8 缺陷 A 接受门）：
     * 链头经 [wrapperTypeFromInitializerChain] 判为 wrapper，且链上任一调用都不在
     * [chainTerminalMethodNames]（one/list/count/remove 等消费终结点）——
     * 链含终结点即为消费结果赋值（`var d = service.lambdaQuery()...one()`），不是 wrapper 变量。
     */
    private fun initializerIsWrapperChainWithoutTerminal(init: PsiElement?): Boolean {
        var expr: PsiElement? = init ?: return false
        while (expr is PsiMethodCallExpression) {
            // T-ExistsChainTerminal：带参 exists 是条件方法（非终结点），不得据此拒收 wrapper 变量
            if (expr.methodExpression.referenceName in chainTerminalMethodNames && !isExistsConditionCall(expr)) return false
            expr = expr.methodExpression.qualifierExpression
        }
        return wrapperTypeFromInitializerChain(init) != null
    }

    /** Wrapper 抽象基类型（基类型声明降级仅限这两类，见 [isWrapperVariable]）。 */
    private val wrapperBaseTypeNames: Set<String> = setOf("Wrapper", "AbstractWrapper")

    /**
     * 从初始化表达式链首推断 Wrapper 类型（基类型声明降级用）：
     * 沿 `methodExpression.qualifierExpression` 回溯到链起点，返回其 wrapper 类型。
     */
    private fun detectWrapperTypeFromInitializer(init: PsiElement?): WrapperType? {
        var expr: PsiElement? = init ?: return null
        while (expr is PsiMethodCallExpression) {
            expr = expr.methodExpression.qualifierExpression
        }
        return when (expr) {
            is PsiNewExpression -> detectWrapperType(expr.type)
            is PsiMethodCallExpression -> factoryWrapperType(expr)
            else -> null
        }
    }

    /**
     * O-8（缺陷 A）：从 initializer 链头解析 (实体类, 类型参数类名)——var/推断形态变量声明类型
     * 无泛型实参可取。链头是 wrapper 构造（`new QueryWrapper<Device>()`）时取构造类型泛型；
     * 链头是工厂调用（`Wrappers.<Device>query()`）时取显式类型实参——链首候选为链上**最后一次调用**
     * （qualifier 回溯终点是引用，工厂调用自身即链首，同 [wrapperTypeFromInitializerChain]），
     * 解析顺位与 [buildChainFromFactory] 同源（显式类型实参 → Class 字面量实参 → 调用返回类型）。
     */
    private fun entityInfoFromInitializerChain(init: PsiElement?): Pair<PsiClass?, String?> {
        var expr: PsiElement? = init ?: return null to null
        var lastCall: PsiMethodCallExpression? = null
        while (expr is PsiMethodCallExpression) {
            lastCall = expr
            expr = expr.methodExpression.qualifierExpression
        }
        return when {
            expr is PsiNewExpression -> entityInfoFromType(expr.type)
            lastCall != null -> {
                val typeArg = lastCall.typeArguments.firstOrNull() as? PsiClassType
                val entityClass = typeArg?.resolve()?.takeIf { it !is PsiTypeParameter }
                    ?: entityClassFromFactoryArgs(lastCall)
                    ?: resolveEntityClass(lastCall.type)
                entityClass to typeArg?.className
            }
            else -> null to null
        }
    }

    /**
     * 表达式链首的最近消费调用：向上找到第一个实参列表（`PsiExpressionList`）的父方法调用。
     * 用于判断 wrapper 作为实参传给了哪个方法（`service.remove(new W()...)` 取 remove 而非更外层）。
     */
    private fun PsiElement.consumingCall(): PsiMethodCallExpression? {
        var cursor: PsiElement? = parent
        while (cursor != null) {
            when (cursor) {
                is PsiExpressionList -> return cursor.parent as? PsiMethodCallExpression
                is PsiStatement, is PsiMethod, is PsiClass -> return null
            }
            cursor = cursor.parent
        }
        return null
    }

    /**
     * 从锚点查找内联 Wrapper 构造表达式（`list(new MPJLambdaQueryWrapper<X>().eq(...))`，清单 P17）。
     *
     * 定位策略（防跨方法误命中）：
     *  - 向下扫锚点**子树**只在锚点初始位置执行一次（锚点可能是包裹调用，如 `list(new W()...)`）；
     *  - 沿祖先链只检查元素**自身**是否为 wrapper new 表达式——绝不能每层都向下扫，
     *    否则走到 PsiClass 级别会命中类内其他方法的 wrapper（回归：删除/修改被串成查询，2026-08-12）。
     */
    private fun findWrapperNewExpression(anchor: PsiElement): PsiNewExpression? {
        // T-M29-03：扫锚点子树全部 new 表达式取**首个 wrapper 构造**（原 findChildOfType 只取第一个——
        // `selectPage(new Page<>(...), new LambdaQueryWrapper<>()...)` 的 Page 先于 wrapper 时漏检）
        PsiTreeUtil.collectElementsOfType(anchor, PsiNewExpression::class.java)
            .firstOrNull { detectWrapperType(it.type) != null }
            ?.let { return it }
        var current: PsiElement? = anchor
        while (current != null) {
            if (current is PsiNewExpression && detectWrapperType(current.type) != null) return current
            current = current.parent
        }
        return null
    }

    /**
     * 从内联 new 表达式构建调用链（无变量声明场景）。
     *
     * 收集以该 new 表达式为链起点的所有链式调用（`new W().select(...).eq(...)`），
     * 按源码结束位置排序。
     *
     * @param anchor T-PreviewStmtTypeScope：触发预览的锚点（D-2 按它选判型依据）；null = 无锚点上下文
     * @param preferredConsumption T-PreviewStmtTypeScope D-3：强制档位（面板「预览目标」切换用）；null = 按 D-2 判
     */
    private fun buildChainFromNew(
        newExpr: PsiNewExpression,
        resolveVariables: Boolean,
        anchor: PsiElement? = null,
        preferredConsumption: ConsumptionKind? = null,
    ): LocatedWrapper? {
        val wrapperType = detectWrapperType(newExpr.type) ?: return null
        // T-M20-03：`new MPJLambdaWrapper<>(userEntity)` 菱形 + 实体变量实参（无泛型实参，
        // entityInfoFromType 解析不到实体）时，从构造第一参表达式类型解析实体；
        // (Class) / (Class, alias) 构造（第一参 PsiClassObjectAccessExpression）不受影响
        val (entityClass, typeArgName) = entityInfoFromType(newExpr.type)
        val entityFromArg = if (entityClass == null) entityClassFromConstructorArg(newExpr) else null
        // 实参解析出的实体（T-M20-03）与类型实参解析结果一致地参与 table/chain 全链路
        val resolvedEntity = entityClass ?: entityFromArg
        // T-TS-07：兜底推断显式传本 new 表达式所属项目（多项目同开时不再受「首个打开项目」影响）
        val table: TableInfo? = resolveTableInfo(resolvedEntity, typeArgName ?: resolvedEntity?.name, newExpr.project)
        val constructorAlias = extractConstructorAlias(newExpr)

        // 宿主 = 包含该 new 表达式的最外层表达式（如 list(new W().select(...).eq(...)) 的整个调用）
        var host: PsiElement = newExpr
        var cursor: PsiElement? = newExpr.parent
        while (cursor != null) {
            if (cursor is PsiExpression) host = cursor
            cursor = cursor.parent
        }
        val wrapperCalls = PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression::class.java)
            .filterNot { PsiTreeUtil.getParentOfType(it, PsiLambdaExpression::class.java) != null }
            .filter { call ->
                // 链首的 qualifier 是 newExpr（即以该 new 为起点的 wrapper 链）
                ArgResolver.collectCallChain(call).first().methodExpression.qualifierExpression === newExpr
            }
            .distinctBy { it.textRange.endOffset }
            .sortedBy { it.textRange.endOffset }
            .map { ArgResolver.toWrapperCall(it, table, resolvedEntity?.name, resolveVariables) }

        // 消费识别：wrapper 作为实参传给删除/计数/exists/实体更新方法（`service.remove(new W()...)` / `service.count(...)` / `service.exists(...)` / `service.update(entity, ...)`）
        // T-PreviewStmtTypeScope：先记**证据**（档位 + 方法名 + 行号 + 语句区间），再由 [decideConsumption]
        // 按锚点位置选出一条决定语句类型——旧口径「该 wrapper 任意一处被消费即改判」在「同一 wrapper
        // 多处消费」时会让判型与用户正在看的语句脱钩（真机报障：查询链预览出 UPDATE/DELETE）。
        val consumingCall = newExpr.consumingCall()
        val consumptions = mutableListOf<WrapperConsumption>()
        if (consumingCall != null) {
            consumptionOf(newExpr, consumingCall)?.let { consumptions += it }
        }
        val deciding = decideConsumption(consumptions, anchorOffsetOf(anchor), preferredConsumption)
        // T-M29-02/03：分页消费（`service.page(page, new W()...)`）——Page 实参解析为 LIMIT 子句
        // R12 T-PaginationSim：连同 searchCount 标志一并解析（D3=A）
        val pageArg = consumingCall?.let {
            if (it.methodExpression.referenceName in pageConsumerMethodNames) parsePageArg(it) else null
        }

        return LocatedWrapper(
            chain = WrapperChain(
                wrapperType, resolvedEntity?.name, wrapperCalls, constructorAlias,
                deciding?.kind == ConsumptionKind.DELETE, deciding?.kind == ConsumptionKind.COUNT,
                deciding?.kind == ConsumptionKind.EXISTS, deciding?.kind == ConsumptionKind.UPDATE_ENTITY,
                pageArg?.limit, pageArg?.searchCount ?: true,
                decidingConsumption = deciding, allConsumptions = consumptions.sortedBy { it.referenceOffset },
            ),
            table = table,
            entityFile = resolvedEntity?.containingFile,
        )
    }

    // ------------------------------------------------------------------
    // Wrappers 静态工厂链（getOne(Wrappers.<T>lambdaQuery().select(...).eq(...)) 等）
    // ------------------------------------------------------------------

    /**
     * 从锚点查找内联 Wrapper 工厂调用（`getOne(Wrappers.<T>lambdaQuery().select(...).eq(...))`）。
     *
     * `Wrappers.<T>lambdaQuery()` 是静态工厂调用，不产生 `new` 表达式、也不一定绑定 Wrapper 类型变量。
     * 定位策略同 [findWrapperNewExpression]：向下扫子树只在锚点初始位置一次，向上只检查元素自身，
     * 避免走到类级别时误命中其他方法的工厂调用。
     */
    private fun findWrapperFactoryCall(anchor: PsiElement): PsiMethodCallExpression? {
        PsiTreeUtil.collectElementsOfType(anchor, PsiMethodCallExpression::class.java)
            .firstOrNull { isWrapperFactoryCall(it) }
            ?.let { return it }
        var current: PsiElement? = anchor
        while (current != null) {
            if (current is PsiMethodCallExpression && isWrapperFactoryCall(current)) return current
            current = current.parent
        }
        return null
    }

    /**
     * 从内联工厂调用构建调用链（无变量声明场景）。
     *
     * 收集以该工厂调用为链起点的所有链式调用（`Wrappers.<T>lambdaQuery().select(...).eq(...)`、
     * `service.lambdaQuery()...one()` 服务链，T-M15-01），按源码结束位置排序；
     * 工厂调用自身（非 Wrapper 操作）不入链；链式终结点（one/list/count/update/remove 等）识别消费语义后剔除。
     *
     * @param anchor T-PreviewStmtTypeScope：触发预览的锚点（D-2 按它选判型依据）；null = 无锚点上下文
     * @param preferredConsumption T-PreviewStmtTypeScope D-3：强制档位（面板「预览目标」切换用）；null = 按 D-2 判
     */
    private fun buildChainFromFactory(
        factoryCall: PsiMethodCallExpression,
        resolveVariables: Boolean,
        anchor: PsiElement? = null,
        preferredConsumption: ConsumptionKind? = null,
    ): LocatedWrapper? {
        val wrapperType = factoryWrapperType(factoryCall) ?: return null
        // 实体类优先取显式类型实参（`Wrappers.<MerchantEntity>lambdaQuery()`），失败时用类名按命名策略兜底；
        // 服务链（`service.lambdaQuery()`，无类型实参）回退到调用表达式返回类型（LambdaQueryChainWrapper<T>），
        // 仍失败时从链内首个「首参方法引用」推断（T-M15-01）；
        // O-8（缺陷 B）第三通道：服务链 qualifier 变量的服务类型泛型实参（`DeviceService extends IService<Device>`）
        val typeArg = factoryCall.typeArguments.firstOrNull() as? PsiClassType
        // XB（M44）：xbatis 工厂实体**优先取 Class 字面量实参**（`QueryChain.of(mapper, User.class)`，
        // javap 实证签名）——stub/LightPlatform 下返回类型泛型常为未推断的类型形参 T，不可作实体
        val entityClass = when {
            wrapperType == WrapperType.XBATIS ->
                entityClassFromFactoryArgs(factoryCall)
                    ?: typeArg?.resolve()?.takeIf { it !is PsiTypeParameter }
                    ?: resolveEntityClass(factoryCall.type)
            else -> typeArg?.resolve() ?: resolveEntityClass(factoryCall.type)
                ?: entityClassFromFactoryArgs(factoryCall)
                ?: entityClassFromServiceQualifier(factoryCall)
        }
        val typeArgName = typeArg?.className
        // XB（M44）：xbatis 链实体解析——xbatis @Table 注解实体走 JpaEntityResolver
        // （@Table value / @TableField / @TableId / @Ignore 轨），MP 兼容注解实体走既有 MP EntityResolver
        var table: TableInfo? = when {
            wrapperType == WrapperType.XBATIS -> resolveTableInfoForXbatis(entityClass, typeArgName, factoryCall.project)
            else -> resolveTableInfo(entityClass, typeArgName, factoryCall.project)
        }
        if (table == null) {
            table = inferEntityFromChain(factoryCall)
        }

        // 宿主 = 包含该工厂调用的最外层表达式（如 getOne(Wrappers.<T>lambdaQuery()...) 的整个调用）
        var host: PsiElement = factoryCall
        var cursor: PsiElement? = factoryCall.parent
        while (cursor != null) {
            if (cursor is PsiExpression) host = cursor
            cursor = cursor.parent
        }
        val chainCalls = PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression::class.java)
            .filterNot { PsiTreeUtil.getParentOfType(it, PsiLambdaExpression::class.java) != null }
            .filter { call ->
                // 链起点是工厂调用（沿 qualifier 回溯命中）；排除工厂调用自身
                call !== factoryCall && ArgResolver.collectCallChain(call).any { it === factoryCall }
            }
            .distinctBy { it.textRange.endOffset }
            .sortedBy { it.textRange.endOffset }

        // 链式终结点（Q17/U07-④⑤/U11-⑤，T-M15-01）：`service.lambdaQuery()...one()` / `service.lambdaUpdate()...remove()`。
        // 终结点是链的消费方法（非 Wrapper 操作），从链中剔除并记录消费语义。
        // M19.6：wrapper.clear() 截断（MP 语义清空全部条件）——遇 clear 丢弃此前收集（与 buildChain 一致）
        val clearedCalls = mutableListOf<PsiMethodCallExpression>()
        for (call in chainCalls) {
            if (call.methodExpression.referenceName == CLEAR_METHOD) {
                clearedCalls.clear()
            } else {
                clearedCalls += call
            }
        }
        // T-ExistsChainTerminal：终结点判定排除「带参 exists」——它是条件方法而非终结点。裸工厂链直接作
        // service 实参时链内无真终结点，旧判定会选中带参 exists → 条件丢失 + 整链误包 SELECT EXISTS
        val terminal = clearedCalls.lastOrNull {
            it.methodExpression.referenceName in chainTerminalMethodNames && !isExistsConditionCall(it)
        }
        val nonTerminalCalls = if (terminal != null) clearedCalls.filterNot { it === terminal } else clearedCalls
        val terminalName = terminal?.methodExpression?.referenceName
        // XB（M44）：DeleteChain 条件链转 DELETE 语义（无 set 调用，终结点 execute 已入终结名单剔除）
        val chainDeleteConsumed = terminalName == REMOVE_TERMINAL ||
            xbatisChainFamilyOf(factoryCall) == XBATIS_DELETE_CHAIN
        val chainCountConsumed = terminalName == COUNT_TERMINAL
        val chainExistsConsumed = terminalName == EXISTS_TERMINAL
        // P1-04（M19.5）：链上 `update(entity)` 终结点（带实参）——实体字段展开 SET
        // （复用 SqlPreviewService.updateEntityConsumed 分支：实体全列 SET + 主键进 WHERE）
        val chainUpdateEntity = terminalName == UPDATE_TERMINAL &&
            terminal.argumentList.expressions.isNotEmpty()
        // T-M29-02/03：链式分页终结点（`service.lambdaQuery()...page(new Page<>(...))`）——Page 实参解析为 LIMIT
        // R12 T-PaginationSim：连同 searchCount 标志一并解析（D3=A）
        val chainPageArg = if (terminalName == PAGE_TERMINAL) parsePageArg(terminal) else null

        val wrapperCalls = nonTerminalCalls.map { ArgResolver.toWrapperCall(it, table, entityClass?.name, resolveVariables) }

        // 消费识别：wrapper 作为实参传给删除/计数/exists/实体更新方法（`service.remove(Wrappers.<T>lambdaQuery()...)` 等）
        // T-PreviewStmtTypeScope：链式终结点（`...remove()`）与实参形态（`service.remove(链)`）都先记成证据，
        // 再由 [decideConsumption] 按锚点位置推出决定语句类型的那一条。
        val consumingCall = factoryCall.consumingCall()
        val consumptions = mutableListOf<WrapperConsumption>()
        terminal?.let { t ->
            val terminalKind = when {
                chainDeleteConsumed -> ConsumptionKind.DELETE
                chainCountConsumed -> ConsumptionKind.COUNT
                chainExistsConsumed -> ConsumptionKind.EXISTS
                chainUpdateEntity -> ConsumptionKind.UPDATE_ENTITY
                terminalName == PAGE_TERMINAL -> ConsumptionKind.PAGE
                else -> null
            }
            if (terminalKind != null) {
                consumptions += consumptionEvidence(t, terminalKind, t.methodExpression.referenceName ?: "", t)
            }
        }
        // XB（M44）：DeleteChain 工厂链没有 remove 终结点，删除语义来自链类型本身——按链首位置记为证据，
        // 锚点落在链内任意位置（同一条语句）即命中。
        if (xbatisChainFamilyOf(factoryCall) == XBATIS_DELETE_CHAIN &&
            consumptions.none { it.kind == ConsumptionKind.DELETE }
        ) {
            consumptions += consumptionEvidence(factoryCall, ConsumptionKind.DELETE, XBATIS_DELETE_CHAIN, factoryCall)
        }
        consumingCall?.let { call -> consumptionOf(factoryCall, call)?.let { consumptions += it } }
        val deciding = decideConsumption(consumptions, anchorOffsetOf(anchor), preferredConsumption)
        // T-M29-02/03：分页消费（链式终结点 page / `service.page(page, Wrappers.lambdaQuery()...)` 实参形态）
        // R12 T-PaginationSim：链式终结点优先，实参形态次之；searchCount 取命中形态的解析值
        // （分页只影响 LIMIT/COUNT 包裹，不参与语句类型判型，故不走 [decideConsumption]）
        val pageArg = chainPageArg ?: consumingCall?.let {
            if (it.methodExpression.referenceName in pageConsumerMethodNames) parsePageArg(it) else null
        }
        val pageLimit = pageArg?.limit

        // O-8（缺陷 B）：字符串服务链实体解析失败标记（preview 侧显式降级，不再静默 null）——
        // 仅服务链形态（接收者非静态工厂类：Wrappers/ChainWrappers/MPJ/Db 解析为 PsiClass 排除，
        // this/无 qualifier 服务链同按服务链计），工厂形态维持既有 null 语义
        val serviceChainEntityUnresolved = table == null &&
            factoryCall.methodExpression.referenceName in serviceChainMethodNames &&
            (factoryCall.methodExpression.qualifierExpression as? PsiReferenceExpression)?.resolve() !is PsiClass

        return LocatedWrapper(
            chain = WrapperChain(
                wrapperType, table?.entityClassName ?: entityClass?.name, wrapperCalls, constructorAlias = null,
                deleteConsumed = deciding?.kind == ConsumptionKind.DELETE,
                countConsumed = deciding?.kind == ConsumptionKind.COUNT,
                existsConsumed = deciding?.kind == ConsumptionKind.EXISTS,
                updateEntityConsumed = deciding?.kind == ConsumptionKind.UPDATE_ENTITY,
                pageLimit = pageLimit,
                pageSearchCount = pageArg?.searchCount ?: true,
                decidingConsumption = deciding,
                allConsumptions = consumptions.sortedBy { it.referenceOffset },
            ),
            table = table,
            entityFile = entityClass?.containingFile,
            entityResolutionFailed = serviceChainEntityUnresolved,
        )
    }

    /**
     * O-8（缺陷 B）第三通道：服务链 qualifier 变量的服务类型泛型实参 → 实体 PsiClass。
     * `service.query()` 的 service 引用 resolve 到变量 → 其类型（`DeviceService`）→
     * 父接口/父类泛型实参（`IService<Device>`）→ 实体。真实 PSI 解析，LightPlatform 可测
     * （fixture 内声明 `interface IService<T>` 与 `interface DeviceService extends IService<Device>` 桩即可）。
     *
     * 实体取**末位**泛型实参：MP 惯例实体在末位（`IService<T>` 单参、`ServiceImpl<M, T>` 双参，
     * 首参可能是 Mapper）；类型自身已有泛型实参（`IService<Device> service` 形态）时优先。
     * PsiTypeParameter 守卫（宁可不识别不出错表），全失败返回 null 交由降级/推断链兜底。
     */
    private fun entityClassFromServiceQualifier(factoryCall: PsiMethodCallExpression): PsiClass? {
        val qualifier = factoryCall.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
        val variable = qualifier.resolve() as? PsiVariable ?: return null
        val serviceType = variable.type as? PsiClassType ?: return null
        val serviceClass = serviceType.resolve() ?: return null
        fun lastEntityOf(type: PsiClassType): PsiClass? =
            (type.parameters.lastOrNull() as? PsiClassType)?.resolve()?.takeIf { it !is PsiTypeParameter }
        // ① 服务类型自身泛型实参（`IService<Device> service` 参数直以接口类型声明）
        lastEntityOf(serviceType)?.let { return it }
        // ② 父接口/父类泛型实参（`DeviceService extends IService<Device>`，接口 extends 与类 implements 皆扫）
        val supers = serviceClass.extendsList?.referencedTypes?.toList().orEmpty() +
            serviceClass.implementsList?.referencedTypes?.toList().orEmpty()
        return supers.firstNotNullOfOrNull { superType -> lastEntityOf(superType) }
    }

    /**
     * 服务链实体推断（T-M15-01）：`service.lambdaQuery()` 无类型实参时，从链内调用**首参**的方法引用
     * （`eq(Entity::getX, v)` / `set(Entity::getX, v)` 的列位置）推断实体与列。
     *
     * 优先 resolve 方法引用的所属类（真实 IDE 环境）；resolve 不可用时（LightPlatform 测试环境
     * 方法引用 resolve 返回 null，与既有「类型参数类名兜底」同一原则）按
     * qualifier 文本类名 + getter 反解属性列兜底。
     */
    private fun inferEntityFromChain(chainHead: PsiMethodCallExpression): TableInfo? {
        var host: PsiElement = chainHead
        var cursor: PsiElement? = chainHead.parent
        while (cursor != null) {
            if (cursor is PsiExpression) host = cursor
            cursor = cursor.parent
        }

        var resolvedClass: PsiClass? = null
        val refs = mutableListOf<Pair<String, String>>() // (类名文本, 属性名)
        PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression::class.java)
            .asSequence()
            .filter { call -> call !== chainHead && ArgResolver.collectCallChain(call).any { it === chainHead } }
            // collectElementsOfType 为 pre-order（外层调用先于内层 qualifier 链）；
            // 链式调用各节点 textOffset 均指向链起点，须按 endOffset 恢复源码顺序（同 buildChainFromFactory）
            .sortedBy { it.textRange.endOffset }
            .forEach { call ->
                call.argumentList.expressions.forEachIndexed { index, expr ->
                    val ref = expr as? PsiMethodReferenceExpression ?: return@forEachIndexed
                    // 仅列位置：首参（eq/set/orderBy 等）+ select 的任意位置；selectAs 第二参（VO 字段）跳过
                    if (index != 0 && call.methodExpression.referenceName != "select") return@forEachIndexed
                    val property = getterToProperty(ref.referenceName ?: return@forEachIndexed) ?: return@forEachIndexed
                    if (resolvedClass == null) {
                        resolvedClass = (ref.resolve() as? PsiMethod)?.containingClass
                    }
                    val qualifierName = (ref.qualifierExpression as? PsiReferenceExpression)?.referenceName
                    if (qualifierName != null) refs += qualifierName to property
                }
            }
        if (refs.isEmpty()) return null

        // resolve 优先（真实 IDE）：@TableName/@TableField 注解解析
        resolvedClass?.let { return EntityResolver().resolve(it) }

        // T-M23.10：方法引用 resolve 不可用时（LightPlatform 测试环境），按类名文本在**宿主文件**内
        // 解析 PsiClass（嵌套 static class 可解析；类声明不在表达式子树内，须以文件为遍历根），
        // 使 @TableLogic/@Version 等注解在 JoinWrappers.update/delete 等工厂形态同样生效；
        // 解析失败退回纯文本兜底（既有行为）
        val className = refs.firstNotNullOfOrNull { it.first } ?: return null
        PsiTreeUtil.collectElementsOfType(host.containingFile, PsiClass::class.java)
            .firstOrNull { it.name == className }
            ?.let { return EntityResolver().resolve(it) }

        // 文本兜底：类名推断表名（走统一推断策略 + 修饰链，T-M19.4 全局语义）+ getter 属性列（与既有命名策略兜底一致）
        // T-TS-07：显式传宿主表达式所属项目——多项目同开且项目级表配置不同时，兜底推断不再
        // 受「首个打开项目」影响（宿主是 PSI，project 必可得）
        val columns = refs.map { it.second }.distinct()
            .map { ColumnInfo(fieldName = it, columnName = NamingStrategy.toSnakeCase(it)) }
        return TableInfo(
            entityClassName = className,
            tableName = EntityResolver.inferDecoratedTableName(className, host.project),
            columns = columns,
        )
    }

    /** getter 方法名转属性名（`getStationId` → `stationId`，`isEnabled` → `enabled`）；非 getter/is 前缀返回 null。 */
    private fun getterToProperty(methodName: String): String? {
        val prefix = when {
            methodName.startsWith("get") && methodName.length > 3 -> "get"
            methodName.startsWith("is") && methodName.length > 2 -> "is"
            else -> return null
        }
        return methodName.removePrefix(prefix).replaceFirstChar { it.lowercaseChar() }
    }

    /**
     * 识别 Wrapper 工厂/链起点调用：
     *  - `Wrappers.<T>lambdaQuery()` 静态工厂 / `ChainWrappers.lambdaQueryChain(Class)` 等（P1-05，qualifier 指向工具类）；
     *  - `service.lambdaQuery()` / `service.lambdaUpdate()` 服务链（T-M15-01，qualifier 为任意 service 引用或
     *    隐式 this 无 qualifier）；仅放行 [serviceChainMethodNames]，避免把 `service.update(wrapper)` 等
     *    消费调用误判为链起点（P1-03：query/update 为歧义方法，需 [isServiceChainStart] 守卫）。
     */
    private fun isWrapperFactoryCall(call: PsiMethodCallExpression): Boolean {
        // XB（M44）：xbatis 双坐标静态工厂（QueryChain.of/create 等）优先识别
        if (isXbatisFactoryCall(call)) return true
        val methodName = call.methodExpression.referenceName
        if (methodName !in wrapperFactoryMethodNames) return false
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression
        if (qualifier == null) return methodName in serviceChainMethodNames && isServiceChainStart(call)
        val resolved = qualifier.resolve()
        return when (resolved) {
            is PsiClass -> resolved.name in wrapperFactoryClassNames
            else -> qualifier.referenceName in wrapperFactoryClassNames ||
                (methodName in serviceChainMethodNames && isServiceChainStart(call))
        }
    }

    /**
     * 服务链起点守卫（P1-03，M19.5）：`lambdaQuery`/`lambdaUpdate` 恒为链起点；
     * `query`/`update` 同时是**消费方法名**（`service.update(wrapper)`）——仅当调用**无实参**
     * 且存在**后续链调用**（该调用是某外层方法调用的 qualifier）时才按链起点识别。
     * 纯语法 O(1)（不 resolve），符合 M10 性能红线；internal 供 [WrapperAnchorDetector] 复用。
     */
    internal fun isServiceChainStart(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName
        if (methodName !in ambiguousServiceChainMethods) return true
        if (call.argumentList.expressions.isNotEmpty()) return false
        val parent = call.parent
        return parent is PsiReferenceExpression && parent.parent is PsiMethodCallExpression
    }

    /** 与消费方法名冲突的服务链起点方法（需 [isServiceChainStart] 守卫）。 */
    private val ambiguousServiceChainMethods: Set<String> = setOf("query", "update")

    /** MPJ 官方/旧工厂类名（T-M20-02）：JoinWrappers（com.github.yulichang.toolkit）与 MPJWrappers——
     *  query/update/lambda 的映射语义与 Wrappers/ChainWrappers 不同，需按 qualifier 区分。 */
    private val mpjFactoryClassNames: Set<String> = setOf("JoinWrappers", "MPJWrappers")

    // ------------------------------------------------------------------
    // XB（M44 其他 ORM 第一批）：xbatis（原 mybatis-mp）双坐标链识别
    // ------------------------------------------------------------------

    /**
     * xbatis 链类 FQN 前缀（双坐标）：新版 `cn.xbatis.`（1.10.8）与旧版 `cn.mybatis.mp.`
     * （坐标 cn.mybatis-mp，冻结 1.8.1-RC3——javap 实证 Java 包名为 cn.mybatis.mp）。
     * 变量/工厂类型的 canonicalText 前缀匹配即可区分，
     * 防与 MP `QueryWrapper` 等同名类混淆（按 FQN 前缀而非短名）。
     */
    private val XBATIS_FQN_PREFIXES: List<String> = listOf("cn.xbatis.", "cn.mybatis.mp.")

    /**
     * xbatis 工厂/链类简单名（javap cn.xbatis.core.sql.executor.chain 实证：QueryChain/UpdateChain/
     * DeleteChain/InsertChain；1.x 系无 QueryWrapper 类，QueryWrapper/UpdateWrapper 为防御性超集，
     * 仅在静态工厂形态 `Xxx.of(...)` 下生效——MP 的 QueryWrapper 无静态 of/create，不产生误判）。
     */
    private val XBATIS_FACTORY_CLASS_NAMES: Set<String> = setOf(
        "QueryChain", "UpdateChain", "DeleteChain", "InsertChain", "QueryWrapper", "UpdateWrapper",
    )

    /** xbatis 工厂/链类简单名（锚点快速路径 O(1) 文本匹配用；语义见上）。 */
    internal val xbatisFactoryClassNames: Set<String> = XBATIS_FACTORY_CLASS_NAMES

    /** xbatis 静态工厂方法名（javap 实证：`QueryChain.of(mapper, Class)` / `QueryChain.create()`）。 */
    private val XBATIS_FACTORY_METHOD_NAMES: Set<String> = setOf("of", "create")

    /** xbatis 静态工厂方法名（锚点快速路径 O(1) 文本匹配用）。 */
    internal val xbatisFactoryMethodNames: Set<String> = XBATIS_FACTORY_METHOD_NAMES

    /** xbatis 链家族（XbatisConverter UPDATE/DELETE 语义分派用）。 */
    private const val XBATIS_DELETE_CHAIN = "DeleteChain"

    /**
     * XB：调用是否为 xbatis 静态工厂（`QueryChain.of(userDao, UserEntity.class)` /
     * `QueryChain.create()`，双坐标同形）。qualifier 简名 + 工厂方法名双重门控——
     * `Optional.of` / `Map.of` 等通用 `of` 调用因 qualifier 不在 [XBATIS_FACTORY_CLASS_NAMES] 而天然排除。
     */
    internal fun isXbatisFactoryCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName ?: return false
        if (methodName !in XBATIS_FACTORY_METHOD_NAMES) return false
        val qualifierName = (call.methodExpression.qualifierExpression as? PsiReferenceExpression)?.referenceName
        return qualifierName in XBATIS_FACTORY_CLASS_NAMES
    }

    /** XB：类型是否为 xbatis 链类（canonicalText 按双坐标 FQN 前缀判定，不 resolve）。 */
    private fun isXbatisType(type: PsiType?): Boolean {
        val canonical = runCatching { type?.canonicalText }.getOrNull() ?: return false
        return XBATIS_FQN_PREFIXES.any { canonical.startsWith(it) }
    }

    /**
     * 工厂/链起点调用 → Wrapper 类型映射（T-M20-02 起按 (qualifier 类名, 方法名) 映射，
     * 不能只按方法名——`Wrappers.query()` → QUERY 而 `JoinWrappers.query()` → MPJ_QUERY）：
     *  - Wrappers / ChainWrappers 静态工厂（P1-05）：lambdaQuery → LAMBDA_QUERY、query → QUERY 等；
     *  - MPJ 工厂（JoinWrappers / MPJWrappers）：`lambda()` / `lambdaJoin()` → MPJ_LAMBDA、
     *    `query()` / `queryJoin()` → MPJ_QUERY；`delete()` / `update()` 返回
     *    DeleteJoinWrapper / UpdateJoinWrapper（T-M21-08，原 P3-01 提前：UPDATE_JOIN/DELETE_JOIN）；
     *  - 服务链（`service.lambdaQuery()` 等，T-M15-01，qualifier 非工厂类）：按方法名映射。
     * qualifier 取引用文本（referenceName）即可分辨，无需 resolve（LightPlatform 无 MPJ classpath 也生效）。
     */
    private fun factoryWrapperType(call: PsiMethodCallExpression): WrapperType? {
        val methodName = call.methodExpression.referenceName
        val qualifierName = (call.methodExpression.qualifierExpression as? PsiReferenceExpression)?.referenceName
        // XB（M44）：xbatis 双坐标工厂（QueryChain/UpdateChain/DeleteChain/InsertChain 的 of/create）→ XBATIS，
        // 先于 MP 分支——"of"/"create" 不在 MP 工厂映射内，避免落到服务链兜底返回 null
        if (qualifierName in XBATIS_FACTORY_CLASS_NAMES && methodName in XBATIS_FACTORY_METHOD_NAMES) {
            return WrapperType.XBATIS
        }
        if (qualifierName in wrapperFactoryClassNames) {
            return when (methodName) {
                "lambdaQuery" -> WrapperType.LAMBDA_QUERY
                "lambdaUpdate" -> WrapperType.LAMBDA_UPDATE
                "lambdaDelete" -> WrapperType.LAMBDA_DELETE
                "query" -> if (qualifierName in mpjFactoryClassNames) WrapperType.MPJ_QUERY else WrapperType.QUERY
                // T-M21-08：JoinWrappers.update(Class) → UpdateJoinWrapper（UPDATE_JOIN）；
                // Wrappers.update()（字符串版 UpdateWrapper 工厂）仍为 UPDATE
                "update" -> if (qualifierName in mpjFactoryClassNames) WrapperType.UPDATE_JOIN else WrapperType.UPDATE
                // ChainWrappers 静态工厂（MP 官方 API，P1-05）
                "queryChain" -> WrapperType.QUERY
                "lambdaQueryChain" -> WrapperType.LAMBDA_QUERY
                "lambdaUpdateChain" -> WrapperType.LAMBDA_UPDATE
                "lambdaDeleteChain" -> WrapperType.LAMBDA_DELETE
                "updateChain" -> WrapperType.UPDATE
                "deleteChain" -> WrapperType.LAMBDA_DELETE
                // T-M20-02：MPJ 工厂方法（lambda/lambdaJoin/queryJoin 仅 JoinWrappers/MPJWrappers 收录）
                "lambda", "lambdaJoin" ->
                    if (qualifierName in mpjFactoryClassNames) WrapperType.MPJ_LAMBDA else null
                "queryJoin" -> if (qualifierName in mpjFactoryClassNames) WrapperType.MPJ_QUERY else null
                // T-M21-08：JoinWrappers.delete(Class) → DeleteJoinWrapper（DELETE_JOIN）；
                // 非 MPJ 工厂无 delete 静态工厂方法 → null
                "delete" -> if (qualifierName in mpjFactoryClassNames) WrapperType.DELETE_JOIN else null
                else -> null
            }
        }
        // 服务链（service.lambdaQuery() / service.lambdaUpdate()，T-M15-01）按方法名映射
        return when (methodName) {
            "lambdaQuery" -> WrapperType.LAMBDA_QUERY
            "lambdaUpdate" -> WrapperType.LAMBDA_UPDATE
            "query" -> WrapperType.QUERY
            "update" -> WrapperType.UPDATE
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 构建调用链
    // ------------------------------------------------------------------

    /**
     * M19.6（2026-08-13 用户实测）：wrapper 作为实参传入自定义 helper 方法
     * （`applyBusinessOpenInfoFilter(wrapper, dto)`，清单 P16 "wrapper 作为方法入参就地修改"形态）——
     * 展开方法体内对该形参的**直接链式调用**并入主链（helper 内追加的 apply/in/eq 等条件参与 SQL 生成）。
     *
     * 规则：
     *  - 仅收集链首 qualifier resolve 到对应形参的调用（`wrapper.apply(...)` / `wrapper.in(...)`）；
     *  - lambda 体内调用（`wrapper.and(w -> w.eq(...))`）的 w 是 lambda 参数，不在此收集（由 NestedArg 解析）；
     *  - 只展开一层（helper 内不再递归传参）；
     *  - 展开调用的逻辑位置由调用方锚定在 helper **调用点**之后（文件偏移可能早于主链）。
     */
    private fun expandHelperCalls(call: PsiMethodCallExpression, ref: PsiReferenceExpression): List<PsiMethodCallExpression> {
        val method = call.resolveMethod() ?: return emptyList()
        val body = method.body ?: return emptyList()
        val paramIndex = call.argumentList.expressions.indexOfFirst { it === ref }
        if (paramIndex < 0) return emptyList()
        val param = method.parameterList.getParameter(paramIndex) ?: return emptyList()
        val result = mutableListOf<PsiMethodCallExpression>()
        PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .forEach { inner ->
                val chain = ArgResolver.collectCallChain(inner)
                val head = chain.firstOrNull() ?: return@forEach
                val headQualifier = head.methodExpression.qualifierExpression as? PsiReferenceExpression
                if (headQualifier?.resolve() === param) result += chain
            }
        return result
    }

    /**
     * 从 Wrapper 变量构建完整调用链。
     *
     * 调用来源（按源码 offset 合并排序、去重）：
     *  - 变量初始化表达式中的链式调用（new W().eq(...)）
     *  - 变量后续引用上的方法调用（wrapper.eq(...)）
     *
     * @param anchor T-PreviewStmtTypeScope：触发预览的锚点（D-2 按它选判型依据）；null = 无锚点上下文
     * @param preferredConsumption T-PreviewStmtTypeScope D-3：强制档位（面板「预览目标」切换用）；null = 按 D-2 判
     */
    private fun buildChain(
        variable: PsiVariable,
        resolveVariables: Boolean,
        anchor: PsiElement? = null,
        preferredConsumption: ConsumptionKind? = null,
    ): LocatedWrapper? {
        // 基类型声明降级：类型不是具体 Wrapper 时从初始化表达式链首推断（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`）；
        // O-8（缺陷 A）：var 推断形态（typeElement 文本 "var"）工厂链头原实现不识别，补第三顺位
        val isVarDeclaration = (variable as? PsiLocalVariable)?.typeElement?.text == "var"
        val wrapperType = detectWrapperType(variable.type)
            ?: detectWrapperTypeFromInitializer(variable.initializer)
            ?: (if (isVarDeclaration) wrapperTypeFromInitializerChain(variable.initializer) else null)
            ?: return null
        // O-8（缺陷 A）：var/推断形态声明类型不可识别 → 泛型实参解析不到实体，
        // 从 initializer 链头补实体解析（`var w = Wrappers.<Device>query()` 显式类型实参 /
        // `var w = new QueryWrapper<Device>()` 构造类型），与工厂路径实体解析同源
        var (entityClass, typeArgName) = entityInfoFromType(variable.type)
        if (entityClass == null && typeArgName == null && detectWrapperType(variable.type) == null) {
            val (initEntity, initTypeArg) = entityInfoFromInitializerChain(variable.initializer)
            entityClass = initEntity
            typeArgName = initTypeArg
        }
        // XB（M44）：xbatis 变量声明形态实体解析与工厂形态同轨（xbatis @Table → JpaEntityResolver）
        // T-TS-07：兜底推断显式传变量所属项目（多项目同开时不再受「首个打开项目」影响）
        val table: TableInfo? = when {
            wrapperType == WrapperType.XBATIS -> resolveTableInfoForXbatis(entityClass, typeArgName, variable.project)
            else -> resolveTableInfo(entityClass, typeArgName, variable.project)
        }
        val constructorAlias = extractConstructorAlias(variable.initializer)

        val callElements = mutableListOf<Pair<PsiMethodCallExpression, Int>>()
        // T-PreviewStmtTypeScope：消费识别产物是**证据清单**（档位 + 方法名 + 行号 + 语句区间），四个标志
        // 由 [decideConsumption] 按锚点位置单点决定，见循环内注释。
        val consumptions = mutableListOf<WrapperConsumption>()
        // T-PreviewStmtTypeScope：重新赋值点（赋值 LHS 是该变量）——wrapper 被重新赋值即开启一段新的
        // 条件生命周期，[filterCallsToAnchorSegment] 据此只收锚点所在段的链上调用。
        val assignmentOffsets = mutableListOf<Int>()
        // XB（M44）：DeleteChain 变量分步形态（`DeleteChain c = DeleteChain.of(mapper, X.class); c.eq(...)`）
        // → DELETE 语义（XbatisConverter 按 deleteConsumed 分派）；删除语义来自链类型本身而非引用消费，
        // 按变量声明位置记为证据（锚点在声明语句内即命中）。
        if ((variable.type as? PsiClassType)?.className == "DeleteChain") {
            consumptions += consumptionEvidence(variable, ConsumptionKind.DELETE, XBATIS_DELETE_CHAIN)
        }
        // T-M29-02/03：分页消费（`service.page(new Page<>(...), wrapper)`）→ Page 实参解析的 LIMIT 子句
        // R12 T-PaginationSim：连同 searchCount 标志一并解析（D3=A，默认 true 对齐 MP Page 默认）
        var pageLimit: LimitClause? = null
        var pageSearchCount = true

        // 1. 初始化表达式中的链式调用；若链首为工厂方法调用（baseJoinWrapper()），展开其方法体内的 wrapper 链（P03）；
        //    Wrappers 静态工厂调用（Wrappers.<T>lambdaQuery()）不是 Wrapper 操作，从链中剔除
        variable.initializer?.let { init ->
            val initCalls = ArgResolver.collectChainFromInitializer(init).dropWhile { isWrapperFactoryCall(it) }
            expandFactoryCalls(initCalls).forEach { callElements += it to it.textRange.endOffset }
        }

        // 2. 变量引用上的方法调用（分步写法）+ 消费识别（`service.remove(wrapper)` / `service.count(wrapper)` 等）
        // T-PreviewStmtTypeScope：消费不再「任意一处即置标志」，而是收进证据清单——作用域收窄见 [decideConsumption]
        ReferencesSearch.search(variable).forEach { ref ->
            val refExpression = ref.element as? PsiReferenceExpression ?: return@forEach
            // T-PreviewStmtTypeScope：重新赋值点记录（`w = new LambdaQueryWrapper<>()` 的 LHS 引用）
            val parent = refExpression.parent
            if (parent is PsiAssignmentExpression && parent.lExpression === refExpression) {
                assignmentOffsets += refExpression.textRange.startOffset
            }
            consumerCallOf(refExpression)?.let { consumptionOf(refExpression, it)?.let { evidence -> consumptions += evidence } }
            // T-M29-02：分页消费（`service.page(page, wrapper)` / `mapper.selectPage(page, wrapper)`）
            if (pageLimit == null) {
                pageConsumingCall(refExpression)?.let { call ->
                    parsePageArg(call)?.let { arg ->
                        pageLimit = arg.limit
                        pageSearchCount = arg.searchCount
                    }
                }
            }
            // w 引用的 PSI 结构：w.eq(...) 中 w 引用 → methodExpression(eq 引用) → PsiMethodCallExpression
            // 向上遍历找最近的调用表达式
            val call = generateSequence(refExpression.parent) { it.parent }
                .filterIsInstance<PsiMethodCallExpression>()
                .firstOrNull() ?: return@forEach
            // 仅收集 qualifier 是该引用的调用（wrapper.eq(...) 而非方法实参中的引用）；
            // 链式延续（`wrapper.set(a).set(b)` 的 set(b) / `wrapper.lambda().eq(...)` 的 eq）不直接引用
            // wrapper——由首个直接调用扩展收集整条延续链（T-M15-05/U09-④）
            val qualifier = call.methodExpression.qualifierExpression
            val isDirect = qualifier === refExpression
            if (isDirect) {
                collectExtendingChain(call).forEach { callElements += it to call.textRange.endOffset }
            }
            val isLambdaConverted = qualifier is PsiMethodCallExpression &&
                qualifier.methodExpression.referenceName == "lambda" &&
                qualifier.methodExpression.qualifierExpression === refExpression
            if (isLambdaConverted) {
                callElements += call to call.textRange.endOffset
            }
            // M19.6（2026-08-13 用户实测）：wrapper 作为**实参**传入自定义 helper 方法
            // （`applyBusinessOpenInfoFilter(wrapper, dto)`，清单 P16 "wrapper 作为方法入参就地修改"）——
            // 展开方法体内对该形参的链式调用；展开调用**锚定在 helper 调用点**（逻辑顺序，而非文件偏移）
            if (call.argumentList.expressions.any { it === refExpression }) {
                expandHelperCalls(call, refExpression).forEach { callElements += it to call.textRange.endOffset }
            }
        }

        // 合并去重（注意：链式调用表达式的 textOffset 可能相同——都从 new 起始，
        // 必须用 endOffset 区分）；按**逻辑顺序**排序：主链按源码偏移，跨方法展开的调用
        // 锚定其 helper 调用点（M19.6），组内按自身源码偏移
        val sortedCalls = callElements
            .distinctBy { it.first.textRange.endOffset }
            .sortedWith(compareBy({ it.second }, { it.first.textRange.endOffset }))
            .map { it.first }

        // M19.6（2026-08-13 用户实测）：wrapper.clear() 截断——MP 语义清空全部条件；
        // 按源码顺序遇 clear() 丢弃此前已收集的调用（clear 自身不入链）
        val truncatedCalls = mutableListOf<PsiMethodCallExpression>()
        for (call in sortedCalls) {
            if (call.methodExpression.referenceName == CLEAR_METHOD) {
                truncatedCalls.clear()
            } else {
                truncatedCalls += call
            }
        }

        // P15（T-M15-05）：if/else 互斥分支——排序后（源码序）同一 if 语句内的调用只保留第一个分支
        // （then），避免 `if (x) { w.eq(...) } else { w.in(...) }` 合并成 AND 双条件
        val seenIfStatements = mutableSetOf<PsiIfStatement>()
        val dedupedCalls = truncatedCalls.filter { call ->
            val enclosingIf = PsiTreeUtil.getParentOfType(call, PsiIfStatement::class.java, false)
            enclosingIf == null || seenIfStatements.add(enclosingIf)
        }

        // T-PreviewStmtTypeScope：重新赋值分段——锚点落在哪一段条件生命周期，就只收那一段的链上调用
        // （demo 语料 WrapperReuseRepro：重新赋值行的锚点若收全量，DELETE 会带上 update 段的 user_id/status）
        val segmentCalls = filterCallsToAnchorSegment(dedupedCalls, assignmentOffsets, anchorOffsetOf(anchor))
        val wrapperCalls = segmentCalls.map { ArgResolver.toWrapperCall(it, table, entityClass?.name, resolveVariables) }
        // T-PreviewStmtTypeScope M2（D-2）：从全部消费证据里按锚点位置选一条决定语句类型；
        // D-3 的强制档位（面板切换）优先于 D-2。
        val deciding = decideConsumption(consumptions, anchorOffsetOf(anchor), preferredConsumption)
        return LocatedWrapper(
            chain = WrapperChain(
                wrapperType, entityClass?.name, wrapperCalls, constructorAlias,
                deciding?.kind == ConsumptionKind.DELETE, deciding?.kind == ConsumptionKind.COUNT,
                deciding?.kind == ConsumptionKind.EXISTS, deciding?.kind == ConsumptionKind.UPDATE_ENTITY,
                pageLimit, pageSearchCount,
                decidingConsumption = deciding, allConsumptions = consumptions.sortedBy { it.referenceOffset },
            ),
            table = table,
        )
    }

    /**
     * 收集调用之后的整条链式延续（T-M15-05）：`wrapper.set(a).set(b)` 中 set(b) 不直接引用 wrapper 变量，
     * 由首个直接调用（set(a)）向上延伸「作为外层调用 qualifier 的延续链」后回溯收集。
     *
     * 仅当调用是外层调用的 qualifierExpression 时才延伸（链式延续）；
     * 实参容器（`service.list(wrapper.eq(a))` 的 list）不延伸，避免跨方法误收集。
     */
    private fun collectExtendingChain(call: PsiMethodCallExpression): List<PsiMethodCallExpression> {
        var outermost: PsiMethodCallExpression = call
        var cursor: PsiElement? = call.parent
        while (cursor != null) {
            when {
                // 链式延续：外层调用，其 qualifier 是当前链尾（wrapper.set(a).set(b)）
                cursor is PsiMethodCallExpression && cursor.methodExpression.qualifierExpression === outermost -> {
                    outermost = cursor
                    cursor = cursor.parent
                }
                // 引用层延续：`wrapper.lambda().eq(...)` 中 lambda() 的父是 eq 的 methodExpression 引用
                // （其 qualifier 是当前链尾）→ 延伸到引用所属的调用
                cursor is PsiReferenceExpression && cursor.qualifierExpression === outermost -> {
                    val next = cursor.parent as? PsiMethodCallExpression ?: break
                    outermost = next
                    cursor = next.parent
                }
                else -> break
            }
        }
        return ArgResolver.collectCallChain(outermost)
    }

    // ------------------------------------------------------------------
    // T-PreviewStmtTypeScope：消费证据（判型依据）与 D-2 作用域收窄
    // ------------------------------------------------------------------

    /** 实体更新消费方法名（`service.update(entity, wrapper)` 双参形态；updateJoin/updateJoinAndNull 为 MPJ 官方联表更新，M15 增补；
     *  T-M20-10：`saveOrUpdate(entity, wrapper)` 双参按实体字段展开 UPDATE（近似 MP 先更新语义，upsert 预览以 UPDATE 呈现））。 */
    private val UPDATE_CONSUMER_METHODS = setOf("update", "updateJoin", "updateJoinAndNull", "saveOrUpdate")

    /**
     * 引用所在**消费调用的实参列表父调用**（`wrapper` 作为实参传给谁）；引用不在实参位置
     * （链上 qualifier / 非调用上下文）返回 null。旧 isConsumerArgument / isEntityUpdateArgument /
     * pageConsumingCall 三处同构向上遍历收口于此。
     */
    private fun consumerCallOf(ref: PsiReferenceExpression): PsiMethodCallExpression? {
        var cursor: PsiElement? = ref.parent
        while (cursor != null) {
            when (cursor) {
                is PsiExpressionList -> return cursor.parent as? PsiMethodCallExpression
                // 引用是链调用的 qualifier / 语句 / 方法 / 类边界，不是消费实参
                is PsiMethodCallExpression, is PsiStatement, is PsiMethod, is PsiClass -> return null
            }
            cursor = cursor.parent
        }
        return null
    }

    /**
     * T-M29-02：引用是否作为**分页消费方法**的实参（`service.page(page, wrapper)` / `selectPage` 等）；
     * 命中返回消费调用（[parsePageLimit] 从其实参解析 Page），否则 null。
     */
    private fun pageConsumingCall(ref: PsiReferenceExpression): PsiMethodCallExpression? =
        consumerCallOf(ref)?.takeIf { it.methodExpression.referenceName in pageConsumerMethodNames }

    /**
     * T-PreviewStmtTypeScope：一处消费表达式的**档位**判定——方法名落在消费集合内 + 档位形态 + M3 接收者负门；
     * 不是消费返回 null。
     *
     * @param wrapperExpr 该 wrapper 在实参中的表达式（引用 / 内联链表达式），UPDATE 档位据它判「wrapper 非首参」
     */
    private fun consumptionKindOf(call: PsiMethodCallExpression, wrapperExpr: PsiElement): ConsumptionKind? {
        val name = call.methodExpression.referenceName ?: return null
        val kind = when {
            name in UPDATE_CONSUMER_METHODS -> ConsumptionKind.UPDATE_ENTITY
            name in deleteConsumerMethodNames -> ConsumptionKind.DELETE
            name in countConsumerMethodNames -> ConsumptionKind.COUNT
            name == EXISTS_CONSUMER -> ConsumptionKind.EXISTS
            name in pageConsumerMethodNames -> ConsumptionKind.PAGE
            else -> return null
        }
        if (hasNonDbReceiver(call)) return null
        if (kind == ConsumptionKind.UPDATE_ENTITY && !isEntityUpdateShape(call, wrapperExpr)) return null
        return kind
    }

    /**
     * 实体更新消费形态（U07-②，T-M15-04）：实参 >= 2、wrapper 位于**非首参**子树内（entity 在前、
     * wrapper 在后；`update(null, wrapper)` 首参 null 字面量为 mapper 批量更新，不算实体更新）。
     * 引用形态与内联链表达式共用同一判据。
     */
    private fun isEntityUpdateShape(call: PsiMethodCallExpression, wrapperExpr: PsiElement): Boolean {
        val args = call.argumentList.expressions
        if (args.size < 2) return false
        if (args[0] is PsiLiteralExpression && (args[0] as PsiLiteralExpression).value == null) return false
        return args.drop(1).any { arg -> PsiTreeUtil.isAncestor(arg, wrapperExpr, false) }
    }

    /** 消费表达式 → 证据（不是消费返回 null）。 */
    private fun consumptionOf(wrapperExpr: PsiElement, call: PsiMethodCallExpression): WrapperConsumption? =
        consumptionKindOf(call, wrapperExpr)?.let { kind ->
            consumptionEvidence(wrapperExpr, kind, call.methodExpression.referenceName ?: "", call)
        }

    /**
     * T-PreviewStmtTypeScope：构造一条消费证据（M1 展示载体 + D-2 排序依据）。
     *
     * @param offsetSource 偏移/行号取谁（引用优先；内联链取链首表达式）
     * @param kind         档位
     * @param methodName   方法名留痕；空串时回落到调用的方法名（DeleteChain 这类链类型事实需显式给）
     * @param call         消费调用；null 表示「不是调用形态」（如 DeleteChain 链类型事实）
     */
    private fun consumptionEvidence(
        offsetSource: PsiElement,
        kind: ConsumptionKind,
        methodName: String,
        call: PsiMethodCallExpression? = null,
    ): WrapperConsumption {
        val statement = PsiTreeUtil.getParentOfType(offsetSource, PsiStatement::class.java, false)
        val range = statement?.textRange?.let { it.startOffset..(it.endOffset - 1) } ?: IntRange.EMPTY
        val line = runCatching {
            val file = offsetSource.containingFile ?: return@runCatching 0
            val document = PsiDocumentManager.getInstance(offsetSource.project).getDocument(file)
                ?: return@runCatching 0
            document.getLineNumber(offsetSource.textRange.startOffset) + 1
        }.getOrDefault(0)
        return WrapperConsumption(
            kind = kind,
            methodName = methodName.ifBlank { call?.methodExpression?.referenceName ?: "" },
            referenceOffset = offsetSource.textRange.startOffset,
            statementRange = range,
            line = line,
            fileName = offsetSource.containingFile?.name ?: "",
        )
    }

    /** 锚点偏移（D-2 判型用）；无锚点上下文返回 null（[decideConsumption] 退回档位优先级口径）。 */
    private fun anchorOffsetOf(anchor: PsiElement?): Int? = anchor?.textRange?.startOffset

    /**
     * T-PreviewStmtTypeScope M2（D-2）：从全部消费证据里选出**决定语句类型**的那一条。
     *
     *  1. 锚点所在语句里命中消费 → 取该语句内的消费（同语句多条按档位优先级 COUNT > EXISTS > UPDATE >
     *     DELETE > PAGE）；
     *  2. 否则取**锚点之后最近**的一条（保住 `w = new ...; service.remove(w);` 这类声明与消费分离的写法）；
     *  3. 都没有 → null，语句类型维持默认 SELECT。
     *
     * 旧口径是「该 wrapper 任意一处被消费即改判」——同一 wrapper 在方法里被多处消费时，判型与用户
     * 正在看的那条语句脱钩（真机报障：查询链预览出 UPDATE/DELETE，见 20261112-1740 开发计划 §二）。
     *
     * @param anchorOffset 触发预览的锚点偏移；null = 无锚点上下文，退回旧口径（按档位优先级取一条）
     * @param preferred    D-3 强制档位（面板「预览目标」切换）：命中该档时优先于下面的位置判定；
     *                     该档没有证据时回落 D-2（防御：UI 只会给出现过的档位，正常不会走到）
     */
    internal fun decideConsumption(
        consumptions: List<WrapperConsumption>,
        anchorOffset: Int?,
        preferred: ConsumptionKind? = null,
    ): WrapperConsumption? {
        if (consumptions.isEmpty()) return null
        fun byPriority(list: List<WrapperConsumption>): WrapperConsumption? =
            list.minWithOrNull(compareBy({ CONSUMPTION_PRIORITY.indexOf(it.kind) }, { it.referenceOffset }))
        if (preferred != null) {
            val pool = consumptions.filter { it.kind == preferred }
            if (pool.isNotEmpty()) {
                // 同档多条写法：锚点之后最近的一条优先（与 D-2 同口径），否则取最早的一条
                return anchorOffset?.let { anchor ->
                    pool.filter { it.referenceOffset >= anchor }.minByOrNull { it.referenceOffset }
                } ?: pool.minByOrNull { it.referenceOffset }
            }
        }
        if (anchorOffset == null) return byPriority(consumptions)
        byPriority(consumptions.filter { anchorOffset in it.statementRange })?.let { return it }
        return consumptions.filter { it.referenceOffset >= anchorOffset }.minByOrNull { it.referenceOffset }
    }

    /**
     * T-PreviewStmtTypeScope：重新赋值分段——wrapper 变量被重新赋值后，赋值点即新一段条件生命周期的起点；
     * 锚点落在哪一段，就只收那一段的链上调用。
     *
     *  - 锚点在某次重新赋值之后 → 只收该赋值之后的调用（`w = new ...; w.eq(b); delete(w)` 的锚点
     *    不再带上前一段的 `w.eq(a)`）；
     *  - 锚点在第一次重新赋值之前（含变量声明行）→ 只收该赋值之前的调用（声明段的链）；
     *  - 无重新赋值 / 无锚点 → 全量（存量行为零变化）。
     *
     * 消费证据**不在此过滤**：D-3 的「预览目标」切换需要全部档位（update 段的证据在 delete 段的
     * 锚点上仍要能切到）。纯函数，单测直接驱动。
     */
    internal fun filterCallsToAnchorSegment(
        calls: List<PsiMethodCallExpression>,
        assignmentOffsets: List<Int>,
        anchorOffset: Int?,
    ): List<PsiMethodCallExpression> {
        if (assignmentOffsets.isEmpty() || anchorOffset == null) return calls
        val segmentStart = assignmentOffsets.filter { it <= anchorOffset }.maxOrNull()
        return if (segmentStart != null) {
            calls.filter { it.textRange.startOffset > segmentStart }
        } else {
            val firstAssignment = assignmentOffsets.minOrNull() ?: return calls
            calls.filter { it.textRange.startOffset < firstAssignment }
        }
    }

    /** 消费档位优先级（与 SqlPreviewService 分派顺序同源：count > exists > update > delete > page）。 */
    private val CONSUMPTION_PRIORITY: List<ConsumptionKind> = listOf(
        ConsumptionKind.COUNT, ConsumptionKind.EXISTS, ConsumptionKind.UPDATE_ENTITY,
        ConsumptionKind.DELETE, ConsumptionKind.PAGE,
    )

    /**
     * T-PreviewStmtTypeScope M3：接收者负门——`list.remove(wrapper)` / `map.remove(k, wrapper)` 这类
     * JDK 容器/常见非 DB 类型上的同名调用不是 SQL 消费语义，不该把查询链改判成删除/更新
     * （判型侧此前只看方法名字符串，与锚点侧带家族判定的口径不一致）。
     *
     * 取**负门**而非「正向要求 MP CRUD 家族」是有意的：正向白名单（[WrapperAnchorDetector] 侧
     * `hasMpCrudReceiver` 的口径）要求接收者类型能解析到 BaseMapper/IService 家族，而真实工程与测试语料
     * 大量使用「未接入依赖的自有 Service 桩 / 自有 Component 中间层」，正向判定会把它们的
     * `service.remove(w)` / `service.update(entity, w)` 一并否掉（存量语义大面积回退）。负门只切掉
     * 「明确不是 DB 接收者」的那一小撮，宁缺毋滥的方向与锚点侧一致。
     */
    private fun hasNonDbReceiver(call: PsiMethodCallExpression): Boolean {
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return false
        val type = runCatching { qualifier.type as? PsiClassType }.getOrNull() ?: return false
        return isNonDbReceiverType(type, 0)
    }

    /** 类型父链（含自身，深度上限 [NON_DB_RECEIVER_DEPTH]）是否命中 JDK 容器/常见非 DB 类型。 */
    private fun isNonDbReceiverType(type: PsiClassType, depth: Int): Boolean {
        if (depth > NON_DB_RECEIVER_DEPTH) return false
        val resolved = runCatching { type.resolve()?.qualifiedName }.getOrNull()
        if (resolved != null) {
            if (resolved in NON_DB_RECEIVER_FQNS) return true
        } else {
            // 类型不可解析（依赖未索引 / 桩语料）时按简单名兜底：裸 `List` / `Map` 不可能是工程自有 DB 类型
            val simple = type.canonicalText.substringBefore('<').substringAfterLast('.')
            if (simple in NON_DB_RECEIVER_SIMPLE_NAMES) return true
        }
        return runCatching { type.superTypes }.getOrNull()
            ?.any { it is PsiClassType && isNonDbReceiverType(it, depth + 1) } == true
    }

    /** JDK 容器/常见非 DB 接收者类型（解析成功时按全限定名判定）。 */
    private val NON_DB_RECEIVER_FQNS: Set<String> = setOf(
        "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map",
        "java.util.Iterator", "java.lang.Iterable", "java.util.Optional",
        "java.util.stream.Stream", "java.lang.CharSequence", "java.lang.String",
        "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.Class",
        "java.lang.Thread", "java.lang.ThreadLocal", "java.io.Writer", "java.io.OutputStream",
    )

    /** 类型不可解析时的简单名兜底集合（与 [NON_DB_RECEIVER_FQNS] 同口径）。 */
    private val NON_DB_RECEIVER_SIMPLE_NAMES: Set<String> =
        NON_DB_RECEIVER_FQNS.mapTo(mutableSetOf()) { it.substringAfterLast('.') }

    /** 类型父链遍历深度上限（接收者至多几跳；防病态继承链的无界递归）。 */
    private const val NON_DB_RECEIVER_DEPTH = 4

    /**
     * T-M29-03：解析分页消费调用的 Page 实参（`new Page<>(current, size)`）为 [LimitClause]。
     *
     *  - Page 实参：直接 new 表达式，或可解析到 new Page 初始化表达式的变量引用（分步写法）；
     *  - 空构造 `new Page<>()` → 默认 current=1、size=10（对齐 MP 默认）；
     *  - current/size 字面量 → 直接计算 offset=(current-1)*size（current<=0 按 0，对齐 MP getOffet）；
     *  - 任一为运行时值 → `?` 占位（offset 来源标注计算公式，面板回填后即真实偏移）；
     *  - 解析失败（Page 变量不可解析 / 非 Page 类型等）→ null（不渲染 LIMIT，维持现状）。
     */
    private fun parsePageLimit(call: PsiMethodCallExpression): LimitClause? = parsePageArg(call)?.limit

    /**
     * R12 T-PaginationSim：解析分页消费调用的 Page 实参全量（LIMIT 子句 + searchCount 标志）。
     *
     * searchCount 判定（D3=A）：Page 构造器第 3/4 参的布尔字面量（MP 3.5.17 反编译实证构造器全集
     * `(long,long)` / `(long,long,long)` / `(long,long,boolean)` / `(long,long,long,boolean)`，
     * boolean 位于下标 2 或 3）；布尔字面量缺失（无此参 / 变量实参静态不可判）→ 保守 true
     * （对齐 MP `Page()` 默认 searchCount=true，宁多勿少）。
     */
    private fun parsePageArg(call: PsiMethodCallExpression): PageArg? {
        val pageExpr = call.argumentList.expressions.firstNotNullOfOrNull { arg -> resolvePageNewExpression(arg) }
            ?: return null
        val args = pageExpr.argumentList?.expressions.orEmpty()
        val current = args.getOrNull(0)?.let { ExpressionEvaluator.evaluate(it) } ?: SqlValue.Literal("1")
        val size = args.getOrNull(1)?.let { ExpressionEvaluator.evaluate(it) } ?: SqlValue.Literal("10")
        val offset = when {
            current is SqlValue.Literal && size is SqlValue.Literal -> {
                val c = current.unquoted.toLongOrNull()
                val s = size.unquoted.toLongOrNull()
                if (c != null && s != null) {
                    SqlValue.Literal((if (c > 0) (c - 1) * s else 0L).toString())
                } else {
                    pageOffsetPlaceholder(current, size)
                }
            }
            else -> pageOffsetPlaceholder(current, size)
        }
        val searchCount = args.drop(2)
            .filterIsInstance<PsiLiteralExpression>()
            .mapNotNull { it.value as? Boolean }
            .firstOrNull() ?: true
        return PageArg(LimitClause(offset = offset, count = size), searchCount)
    }

    /** Page 实参解析产物（R12 T-PaginationSim）：LIMIT 子句 + searchCount 标志（见 [parsePageArg]）。 */
    private data class PageArg(val limit: LimitClause, val searchCount: Boolean)

    /** new Page 表达式解析：直接 new 表达式，或变量引用 → 初始化表达式（分步写法 `Page p = new Page<>(...); service.page(p, w)`）。 */
    private fun resolvePageNewExpression(expr: PsiExpression): PsiNewExpression? {
        if (expr is PsiNewExpression && isPageType(expr)) return expr
        if (expr is PsiReferenceExpression) {
            val variable = expr.resolve() as? PsiVariable ?: return null
            val init = variable.initializer as? PsiNewExpression ?: return null
            return init.takeIf { isPageType(it) }
        }
        return null
    }

    /** Page 类型判定（Page / PageImpl 简单类名，不 resolve——LightPlatform 无 MP classpath 也生效）。 */
    private fun isPageType(newExpr: PsiNewExpression): Boolean {
        val className = (newExpr.type as? PsiClassType)?.className ?: return false
        return className == "Page" || className == "PageImpl"
    }

    /** 分页 offset 运行时占位来源：`(current - 1) * size` 计算公式文本（对齐 MP getOffet 语义）。 */
    private fun pageOffsetPlaceholder(current: SqlValue, size: SqlValue): SqlValue {
        val currentText = when (current) {
            is SqlValue.Literal -> current.unquoted
            is SqlValue.Placeholder -> current.source
        }
        val sizeText = when (size) {
            is SqlValue.Literal -> size.unquoted
            is SqlValue.Placeholder -> size.source
        }
        return SqlValue.Placeholder("($currentText - 1) * $sizeText")
    }

    /**
     * 提取表别名构造的别名（清单 C4/P11）：`new MPJLambdaWrapper<>(Entity.class, "apply_info")` 的第二参。
     * 非两参构造或无字符串第二参时返回 null（使用默认主表别名）。
     */
    private fun extractConstructorAlias(initializer: PsiElement?): String? {
        val newExpr = when (initializer) {
            is PsiNewExpression -> initializer
            else -> initializer?.let { PsiTreeUtil.findChildOfType(it, PsiNewExpression::class.java) }
        } ?: return null
        val args = newExpr.argumentList?.expressions ?: return null
        if (args.size < 2) return null
        val alias = (args[1] as? PsiLiteralExpression)?.value as? String
        return alias?.takeIf { it.isNotEmpty() }
    }

    /**
     * 从构造实参第一参解析实体类（T-M20-03）：`new MPJLambdaWrapper<>(userEntity)` 菱形 + 实体变量实参
     * （无泛型实参，[entityInfoFromType] 解析为 null）时，从第一参表达式类型
     * （PsiClassType → resolve → PsiClass）解析实体，供 [resolveTableInfo] 走既有流程接表名。
     * `(Class)` / `(Class, alias)` 构造的第一参是 PsiClassObjectAccessExpression（类字面量，非实体实例）→
     * 返回 null；字面量实参（字符串等）同理跳过。
     */
    private fun entityClassFromConstructorArg(newExpr: PsiNewExpression): PsiClass? {
        val firstArg = newExpr.argumentList?.expressions?.firstOrNull() ?: return null
        if (firstArg is PsiClassObjectAccessExpression || firstArg is PsiLiteralExpression) return null
        return (firstArg.type as? PsiClassType)?.resolve()
    }

    /**
     * XB（M44）：从工厂调用实参解析实体类——扫描全部实参取首个 Class 字面量
     * （`QueryChain.of(userDao, UserEntity.class)` 的第二参，javap 实证签名）。
     * 与 [entityClassFromConstructorArg] 相反：Class 字面量即目标，其余实参跳过。
     */
    private fun entityClassFromFactoryArgs(call: PsiMethodCallExpression): PsiClass? =
        call.argumentList.expressions.firstNotNullOfOrNull { arg ->
            (arg as? PsiClassObjectAccessExpression)?.operand?.type?.let { type ->
                (type as? PsiClassType)?.resolve()
            }
        }

    /**
     * XB（M44）：xbatis 链家族语义分派——qualifier 类名 → "delete"（DeleteChain 条件链转 DELETE）；
     * QueryChain/UpdateChain 返回 null（SELECT 由 XbatisConverter 默认、UPDATE 由 set 家族调用判定）。
     */
    private fun xbatisChainFamilyOf(call: PsiMethodCallExpression): String? =
        (call.methodExpression.qualifierExpression as? PsiReferenceExpression)?.referenceName
            ?.takeIf { it == XBATIS_DELETE_CHAIN || it == "UpdateChain" }

    /**
     * XB（M44）：xbatis 链实体表解析。实体类标注 xbatis @Table → [JpaEntityResolver]
     * （@Table value / @TableField / @TableId / @Ignore 轨）；MP 兼容注解（@TableName）实体
     * → 既有 MP [EntityResolver]。实体类缺失时类型参数类名按命名策略兜底（与 resolveTableInfo 一致）。
     *
     * @param project 调用方 PSI 元素所属项目（T-TS-07）；null = 未提供，兜底推断沿用
     *   「首个打开项目」的既有语义（纯单测/无 PSI 上下文）
     */
    private fun resolveTableInfoForXbatis(
        entityClass: PsiClass?,
        typeArgName: String?,
        project: Project? = null,
    ): TableInfo? {
        // PsiTypeParameter 守卫：变量声明 `QueryChain<T>` 等未推断泛型场景下实体解析到类型形参，
        // 不可作实体（推断表名 "t"、列清单空——宁缺不可错）
        if (entityClass == null || entityClass is PsiTypeParameter) {
            val name = typeArgName?.takeIf { it != entityClass?.name } ?: return null
            return TableInfo(
                entityClassName = name,
                tableName = EntityResolver.inferDecoratedTableName(name, project),
                columns = emptyList(),
            )
        }
        return if (JpaMetadataReader.findFlex(entityClass, JpaMetadataReader.FQN_XBATIS_TABLE) != null) {
            JpaEntityResolver().resolve(entityClass)
        } else {
            EntityResolver().resolve(entityClass)
        }
    }

    // ------------------------------------------------------------------
    // 跨方法工厂链（清单 P03/P16）：baseJoinWrapper() 返回 wrapper，调用方继续续链
    // ------------------------------------------------------------------

    /**
     * 展开工厂方法调用：当初始化链首元素是「非构造的独立方法调用」（如 `baseJoinWrapper()`）时，
     * 通过 [PsiMethodCallExpression.resolveMethod] 找到方法定义，解析其 `return` 表达式中的 wrapper 链，
     * 返回「工厂内部链 + 续链」的完整调用列表。
     *
     * 非工厂调用（new 构造链）或解析失败时原样返回。
     */
    private fun expandFactoryCalls(calls: List<PsiMethodCallExpression>): List<PsiMethodCallExpression> {
        if (calls.isEmpty()) return calls
        val first = calls.first()
        val qualifier = first.methodExpression.qualifierExpression
        val isFactoryCall = qualifier == null && first.methodExpression.referenceName != null &&
            !(first is PsiNewExpression)
        if (!isFactoryCall) return calls

        val method = first.resolveMethod() ?: return calls
        val factoryChain = findFactoryReturnChain(method)
        if (factoryChain.isEmpty()) return calls
        // P03 3 级链（T-M15-05）：工厂方法 return 链首的 qualifier 是**方法参数**
        // （`applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)` 中 return `wrapper.like(...)`）时，
        // 把调用点对应实参（baseWithdrawQueryWrapper()）的初始链递归展开并前置
        val argumentChain = resolveFactoryArgumentChain(first, method)
        return argumentChain + factoryChain + calls.drop(1)
    }

    /**
     * 解析工厂方法「参数 wrapper 实参」的初始链（P03 3 级链，T-M15-05）：
     * `applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)` 的 return 链首 `wrapper.like(...)` 的 qualifier
     * 是方法参数 wrapper —— 取调用点对应位置的实参（baseWithdrawQueryWrapper()），
     * 若实参本身是工厂调用则递归展开其 return 链（参数链前置到方法内部链之前）。
     */
    private fun resolveFactoryArgumentChain(call: PsiMethodCallExpression, method: PsiMethod): List<PsiMethodCallExpression> {
        val returnChain = findFactoryReturnChain(method)
        val headCall = returnChain.firstOrNull() ?: return emptyList()
        val headQualifier = headCall.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return emptyList()
        val param = headQualifier.resolve() as? PsiParameter ?: return emptyList()
        val paramList = param.parent as? PsiParameterList ?: return emptyList()
        if (paramList.parent !== method.parameterList.parent) return emptyList()
        val arg = call.argumentList.expressions.getOrNull(paramList.getParameterIndex(param)) ?: return emptyList()
        val argCall = arg as? PsiMethodCallExpression ?: return emptyList()
        // 递归展开实参工厂；过滤掉工厂调用自身（其 return 链并入）
        return expandFactoryCalls(listOf(argCall)).filter { it !== argCall }
    }

    /**
     * 解析工厂方法体 `return` 表达式中的 wrapper 构造链。
     * 如 `return new MPJLambdaWrapper<>().selectAll(...).leftJoin(...)` → [new, selectAll, leftJoin]。
     */
    private fun findFactoryReturnChain(method: PsiMethod): List<PsiMethodCallExpression> {
        val body = method.body ?: return emptyList()
        val returnStatement = PsiTreeUtil.findChildOfType(body, PsiReturnStatement::class.java) ?: return emptyList()
        val expr = PsiTreeUtil.getChildOfType(returnStatement, PsiExpression::class.java) ?: return emptyList()
        return ArgResolver.collectChainFromInitializer(expr)
    }

    // ------------------------------------------------------------------
    // 类型识别
    // ------------------------------------------------------------------

    /** Wrappers 静态工厂方法名（`Wrappers.<T>lambdaQuery()` 等）。M10 起 internal 供 [WrapperAnchorDetector] 复用。
     *  P1-05（M19.5）：ChainWrappers 工厂方法（queryChain/lambdaQueryChain 等）一并收录。
     *  T-M20-02：MPJ 官方工厂 JoinWrappers（lambda/delete；query/update 已在集合中，映射按 qualifier 区分）
     *  与旧工厂 MPJWrappers（lambdaJoin/queryJoin）方法名一并收录。 */
    internal val wrapperFactoryMethodNames: Set<String> = setOf(
        "lambdaQuery", "lambdaUpdate", "lambdaDelete", "query", "update",
        "queryChain", "lambdaQueryChain", "lambdaUpdateChain", "lambdaDeleteChain", "updateChain", "deleteChain",
        "lambda", "delete", "lambdaJoin", "queryJoin",
    )

    /**
     * Wrappers / ChainWrappers 静态工厂类名（P1-05，M19.5）；
     * T-M20-02：MPJ 官方工厂 JoinWrappers（com.github.yulichang.toolkit）与旧工厂 MPJWrappers 一并收录。
     * T-M22-04：Db kit（com.baomidou.mybatisplus.extension.toolkit.Db 静态工具）——
     * `Db.lambdaQuery(User.class)...` 与 Wrappers 同映射（lambdaQuery → LAMBDA_QUERY，
     * factoryWrapperType 的 qualifier 分支恒映射；实体从链内方法引用推断）。
     * M10 起 internal 供 [WrapperAnchorDetector] 复用（gutter 锚点快速路径文本匹配）。
     */
    internal val wrapperFactoryClassNames: Set<String> = setOf("Wrappers", "ChainWrappers", "JoinWrappers", "MPJWrappers", "Db")

    /**
     * 服务链起点方法名（`service.lambdaQuery()` / `service.lambdaUpdate()`，T-M15-01；
     * P1-03 扩展 `service.query()` / `service.update()` 字符串链——歧义方法经 [isServiceChainStart] 守卫）。
     * 与 [wrapperFactoryMethodNames] 不同：只放行这些方法（`service.update(wrapper)` 等消费调用
     * 不在此列，避免误判为链起点）。
     */
    internal val serviceChainMethodNames: Set<String> = setOf("lambdaQuery", "lambdaUpdate", "query", "update")

    /**
     * 链式 wrapper 终结点（消费方法，非 Wrapper 操作，T-M15-01）：
     * `service.lambdaQuery()...one()/.list()/.count()/.exists()/.page()` 与
     * `service.lambdaUpdate()...update()/.remove()`。
     * 官方 API 补充（M15-P0）：MPJ Chain 的 `first`（取首条）与 `mapOne/mapFirst/mapList/mapPage`（Map 结果）。
     * T-M20-06：ChainQuery（MP 3.5.4+）的 `getObj()`（取单条字段值）补注册为终结点——
     * 消费方法全集核对发现缺口（此前按普通链调用入链，被解析器静默忽略）。
     * T-M21-08：UpdateJoin 链终结点 `updateAndNull()` / `updateAndNull(T)`（MPJ 1.4.10+，
     * 含 null 字段一并 set 的联表更新）——与 `update`/`update(T)` 同列剔除出链。
     */
    private val chainTerminalMethodNames: Set<String> = setOf(
        "one", "oneOpt", "list", "count", "exists", "page",
        "getOne", "getMap", "listMaps", "listObjs", "getObj", "map",
        "first", "mapOne", "mapFirst", "mapList", "mapPage",
        "update", "updateAndNull", "remove",
        // XB（M44）：xbatis 链终结点（javap QueryChain/UpdateChain/DeleteChain 实证）——
        // get/getOptional/list/cursor/count/exists/paging（查询）、execute（更新/删除）。
        // 仅在已识别的 wrapper 链内参与终结判定，普通 Stream/map 调用不受影响
        "get", "getOptional", "paging", "cursor", "execute",
    )

    private const val REMOVE_TERMINAL = "remove"
    private const val COUNT_TERMINAL = "count"
    private const val EXISTS_TERMINAL = "exists"
    private const val UPDATE_TERMINAL = "update"
    /** T-M29-02：链式分页终结点（`service.lambdaQuery()...page(Page)`）。 */
    private const val PAGE_TERMINAL = "page"
    private const val CLEAR_METHOD = "clear"

    /**
     * exists 双语义守卫（T-ExistsChainTerminal）：`exists` 在 MP 里同时是**条件方法**与**链终结点**——
     * `AbstractWrapper.exists(boolean, String, Object...)` 是条件（Q14-①，javap 实证 3.5.7/3.5.15/3.5.17 均只有带参形态），
     * `ChainQuery.exists()` 无参才是终结点（Q14-③）。二者落在链尾时结构完全同形，**只能按实参个数区分**。
     *
     * 带参形态被当终结点会同时错两处（裸工厂链直接作 service 实参、链内无真终结点时暴露）：
     * ①该调用被剔除 → `EXISTS (...)` 条件整条丢失；②`existsConsumed` 置位 → 整链被误包成 `SELECT EXISTS (...)`。
     *
     * 单一来源：终结点筛选（[buildChainFromFactory]）与 wrapper 变量接受门
     * （[initializerIsWrapperChainWithoutTerminal]）共用，避免同一语义两处各判一遍。
     */
    private fun isExistsConditionCall(call: PsiMethodCallExpression): Boolean =
        call.methodExpression.referenceName == EXISTS_TERMINAL &&
            call.argumentList.expressions.isNotEmpty()

    /** 删除类消费方法：wrapper 作为实参传入时，即使类型为查询类也应转 DELETE（MP 惯用删除写法；
     *  deleteJoin 为 MPJ 官方联表删除，M15 增补）。 */
    internal val deleteConsumerMethodNames: Set<String> = setOf("remove", "delete", "deleteJoin")

    /**
     * T-M29-02：分页类消费方法——wrapper 作为实参传入时查询转分页渲染（LIMIT 子句）：
     * `IService.page(Page, wrapper)` / `BaseMapper.selectPage(Page, wrapper)` / MPJ `selectJoinPage` /
     * `Db.page(Page, wrapper)`（链式 `lambdaQuery()...page(...)` 终结点由 [PAGE_TERMINAL] 单独识别）。
     *
     * R12 T-PaginationSim：补注册 BaseMapper 3.5.17 的 IPage 重载族（本地 jar 反编译实证全集 =
     * selectList(IPage,Wrapper) / selectMaps(IPage,Wrapper) / selectPage / selectMapsPage，
     * **无 selectObjs(IPage,...) 重载故不收**）——`mapper.selectList(page, w)` 此前按无分页
     * SELECT 渲染（LIMIT 缺失）。
     */
    internal val pageConsumerMethodNames: Set<String> =
        setOf("page", "selectPage", "selectJoinPage", "selectJoinMapsPage", "selectList", "selectMaps", "selectMapsPage")

    /**
     * 计数类消费方法：wrapper 作为实参传入时，查询类 wrapper 转 `SELECT COUNT(*) FROM ...`。
     * T-M20-06：BaseMapper.selectCount(wrapper) 补注册（消费方法全集核对缺口——
     * 此前 selectCount 消费被默认 SELECT 分支吞掉，SQL 形态错误）。
     */
    internal val countConsumerMethodNames: Set<String> = setOf("count", "selectJoinCount", "selectCount")

    /** exists 消费方法（Q14-②，T-M15-03）：`service.exists(wrapper)` / `mapper.exists(wrapper)` → `SELECT EXISTS (...)`。 */
    private const val EXISTS_CONSUMER = "exists"

    /**
     * 识别 PsiType 对应的 Wrapper 类型（按简单类名匹配）。
     *
     * 注意：不能用 `canonicalText.substringAfterLast('.')` 提取类名——当泛型实参带包名时
     * （真实项目常态，如 `LambdaQueryWrapper<com.biz.BikeDeviceProductInfoEntity>`），
     * 最后一个 `.` 会落在泛型参数内导致提取错误（回归：M10 gutter 图标 + WrapperLocator 定位）。
     * `PsiClassType.getClassName()` 在第一个 `<` 之前取末段，语义正确且无需 resolve（快速路径安全）。
     *
     * M10 起为 internal：供 [WrapperAnchorDetector] 复用（gutter 锚点判定，快速路径
     * 只做 canonicalText 文本匹配，不 resolve），保证 Wrapper 类名集合单一来源。
     */
    internal fun detectWrapperType(type: PsiType?): WrapperType? {
        val simpleName = (type as? PsiClassType)?.className ?: return null
        // XB（M44）：双坐标 FQN 前缀识别（cn.xbatis. / cn.mybatis-mp.）——QueryChain/UpdateChain/
        // DeleteChain/InsertChain 变量声明；防与 MP QueryWrapper 撞类名（canonicalText 文本匹配，不 resolve）。
        // 前缀命中后须叠加链类简单名白名单：用户工程 cn.xbatis.* 包下的任意类（实体/DAO/枚举等）
        // 均命中前缀，若仅凭前缀会把「like(LikeMode.LEFT, ...) 实参解析到的字段变量」等非链类型
        // 误判为链变量（实证：字段类型 cn.xbatis.core.LikeMode → 变量路径抢跑 → table=null）
        if (isXbatisType(type) && simpleName in XBATIS_FACTORY_CLASS_NAMES) return WrapperType.XBATIS
        return when (simpleName) {
            "LambdaQueryWrapper" -> WrapperType.LAMBDA_QUERY
            "LambdaUpdateWrapper" -> WrapperType.LAMBDA_UPDATE
            "LambdaDeleteWrapper" -> WrapperType.LAMBDA_DELETE
            "MPJLambdaWrapper" -> WrapperType.MPJ_LAMBDA
            "MPJQueryWrapper" -> WrapperType.MPJ_QUERY
            "MPJLambdaQueryWrapper" -> WrapperType.MPJ_LAMBDA_QUERY
            "QueryWrapper" -> WrapperType.QUERY
            "UpdateWrapper" -> WrapperType.UPDATE
            // T-M21-08：MPJ 官方联表更新/删除 wrapper（1.4.5+，UpdateJoinWrapper / DeleteJoinWrapper）
            "UpdateJoinWrapper" -> WrapperType.UPDATE_JOIN
            "DeleteJoinWrapper" -> WrapperType.DELETE_JOIN
            // 链式 wrapper 显式声明（`LambdaQueryChainWrapper<X> w = lambdaQuery()...`，Q17，T-M15-01）
            "LambdaQueryChainWrapper" -> WrapperType.LAMBDA_QUERY
            "LambdaUpdateChainWrapper" -> WrapperType.LAMBDA_UPDATE
            else -> null
        }
    }

    /**
     * 从 Wrapper 泛型参数解析实体类：`LambdaQueryWrapper<DeviceInfoEntity>` → PsiClass。
     */
    private fun resolveEntityClass(type: PsiType?): PsiClass? {
        val classType = type as? PsiClassType ?: return null
        val parameters = classType.parameters
        if (parameters.isEmpty()) return null
        val entityType = parameters[0] as? PsiClassType ?: return null
        // PsiTypeParameter 守卫（XB/M44）：LightPlatform/stub 下 `QueryChain<T>` 的泛型参数可能
        // 仍为类型形参 T（未被推断替换），resolve() 返回 PsiTypeParameter（PsiClass 子类）——
        // 若当实体解析，表名推断成 "t"、列清单为空（宁可不识别不可出错误 SQL）
        return entityType.resolve()?.takeIf { it !is PsiTypeParameter }
    }

    /**
     * 从 Wrapper 类型解析 (实体类, 类型参数类名)。
     *
     * 实体类可能不可解析（如粘贴片段无实体类定义）：此时类型参数类名仍可获取，
     * 供 [resolveTableInfo] 按命名策略兜底推断表名。
     */
    private fun entityInfoFromType(type: PsiType?): Pair<PsiClass?, String?> {
        val classType = type as? PsiClassType ?: return null to null
        val param = classType.parameters.firstOrNull() as? PsiClassType
        return param?.resolve() to param?.className
    }

    /**
     * 解析实体表信息；实体类不可解析时，用类型参数类名按命名策略推断表名
     * （列名由 getter → 命名策略兜底，见 [cn.song.mybatisplus.visualizer.psi.ArgResolver.resolveLambdaColumn]）。
     * T-M19.4：兜底推断走统一入口（策略 + 修饰链，全局语义，与 [EntityResolver.resolve] 一致）。
     *
     * @param project 调用方 PSI 元素所属项目（T-TS-07）；null = 未提供，兜底推断沿用
     *   「首个打开项目」的既有语义（纯单测/无 PSI 上下文）
     */
    private fun resolveTableInfo(
        entityClass: PsiClass?,
        typeArgName: String?,
        project: Project? = null,
    ): TableInfo? {
        entityClass?.let { return EntityResolver().resolve(it) }
        val name = typeArgName ?: return null
        return TableInfo(
            entityClassName = name,
            tableName = EntityResolver.inferDecoratedTableName(name, project),
            columns = emptyList(),
        )
    }
}

