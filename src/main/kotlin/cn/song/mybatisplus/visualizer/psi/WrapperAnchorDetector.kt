package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.converter.EasyQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wrapper 锚点判定器（M10-01，gutter 预览图标用）。
 *
 * 职责：判断单个叶子元素是否为「可转换为 SQL 的 Wrapper 调用链」的锚点，供
 * [cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProvider] 在 Line marker
 * 快速路径（daemon 高亮 pass，逐元素回调）中使用。
 *
 * **性能红线（见 M10 开发计划 §2.4）**：本判定器只允许 O(1) 纯语法判断——
 * 仅访问 `element.parent` 与 `type.canonicalText` / `referenceName` 文本匹配，**禁止** PSI resolve、
 * `PsiTreeUtil` 遍历、文件级扫描、任何转换/解析。负例（非 Wrapper 元素）必须零成本返回 null。
 *
 * 支持的锚点形态（与 [WrapperLocator] 的三种写法对应）：
 *  - 场景 A（链式 / 内联 new）：`new LambdaQueryWrapper<X>()...` 的 **`new` 关键字**；
 *  - 场景 B（分步变量）：`LambdaQueryWrapper<X> w = ...` 的**变量名 `PsiIdentifier`**；
 *  - 场景 C（内联 Wrappers 工厂）：`Wrappers.<X>lambdaQuery()...` 的**方法名 `PsiIdentifier`**
 *    （qualifier 文本为 `Wrappers`，语法级匹配，不 resolve）。
 *  - 去重：A/C 的表达式若同时是某 Wrapper 变量的初始化表达式（同行声明 + 构造/工厂），
 *    只标变量声明（它是调用链的根），避免同行双图标。
 *  - 让位：D（批量族）的调用若**实参是 Wrapper 链/变量**（`userMapper.selectList(wrapper)`），
 *    语句承载在实参链上、锚点由 B/C 承载，本场景让位（T-GutterDupFreeze，避免「查询调用」
 *    被当批量操作再挂一枚）。
 *  - T-WriteFamily（R4）场景 D-0：写族 `delete` / `update` / `remove` 只在**空 Wrapper 精确形态**
 *    （`delete(null)` / `remove(null)` 单参 null；`update(entity, null)` 第二参 null）且 receiver 属
 *    MP CRUD 家族（[BatchOperationConverter.hasMpCrudReceiver]）时才以方法名作锚点；
 *    `delete(new ...)` / `delete(wrapperVar)` / `update(entity, wrapperVar)` / `JoinWrappers.delete(...)` /
 *    `service.lambdaUpdate()...update()` 的锚点仍归链（A/B/C），同一语句不出第二枚图标。
 */
object WrapperAnchorDetector {

    /** Wrapper 抽象基类型（基类型声明降级仅限这两类，与 [cn.song.mybatisplus.visualizer.psi.WrapperLocator] 一致）。 */
    private val WRAPPER_BASE_TYPE_NAMES: Set<String> = setOf("Wrapper", "AbstractWrapper")

    /**
     * 与 wrapper 链方法撞名的批量操作方法名（T-M23.11）：同时收录于
     * [BatchOperationConverter.batchOperationMethodNames] 与 MPJ/MP 链方法——
     * 场景 D 对白名单调用做分步形态变量判定（wrapper 变量上的调用去重，见场景 D）；
     * 将来批量注册表新增名字与链方法撞名时扩展本集合。
     */
    private val BATCH_CHAIN_COLLISION_NAMES: Set<String> = setOf("selectAll")

    /**
     * 批量注册表里的 **MPJ Maps 终止调用**族（T-MapResultVo `ee090f7` 登记）——
     * `deviceInfoMapper.selectJoinMaps(wrapper)` / `selectJoinMapsPage(wrapper)`。
     * **不参与 S2 让位**：按 T-MapResultVo 契约（20261007 复核判为「契约更新而非回归」），
     * 这类终止调用在调用行独立承载锚点（wrapper 声明行与调用行各一枚，长年行为）。
     * T-GutterDupFreeze 的 S2 收窄为「消费调用（selectList/selectPage 等）让位」，
     * 不覆盖本族——否则 `WrapperAnchorDetectorTest` 三个 Maps 形态不变量用例转红。
     */
    private val MAPS_TERMINAL_NAMES: Set<String> = setOf("selectJoinMaps", "selectJoinMapsPage")

    /**
     * 与 [WrapperLocator.wrapperFactoryMethodNames] / [WrapperLocator.serviceChainMethodNames] 撞名的
     * JPA 写方法名（T-JpaDeleteAnchor）：`repository.delete(entity)` / `ht.delete(entity)` / `ht.update(entity)`。
     *
     * 这两个名字在场景 C 走「非工厂、非服务链」分支即 `return null`，**在场景 E 之前短路**，
     * 于是 `JpaCrudOperationConverter.crudAnchorMethodNames` 刻意剔除了它们（`WRAPPER_FACTORY_COLLIDING_NAMES`）。
     * 本分支只补锚点入口，不改那两个注册表：receiver 家族判定直接复用
     * [JpaCrudOperationConverter.hasJpaCrudReceiver]（家族 × 方法名双重判定，与场景 E 同口径）。
     */
    private val JPA_COLLIDING_METHOD_NAMES: Set<String> = setOf("delete", "update")

    /**
     * 元素是否为 Wrapper 锚点；是则返回作为 gutter marker 的叶子元素（即元素自身），否则 null。
     *
     * @param element Line marker 快速路径传入的单个叶子元素（可空：调用方 PSI 接口可能返回 null）
     */
    fun anchorElement(element: PsiElement?): PsiElement? {
        // Kotlin 文本检测尚无可靠表名与列/值实参，不能给无法生成 SQL 的链挂预览图标。
        if (element != null && isKotlinFile(element)) return null

        // 场景 B 优先：变量名（PsiLocalVariable / PsiField / PsiParameter 的 nameIdentifier）
        if (element is PsiIdentifier && element.parent is PsiVariable) {
            val variable = element.parent as PsiVariable
            // T-M23.12（用户实测）：lambda 参数（`wrapper.and(o -> ...)` 的 o，真实 IDE 类型推断为
            // LambdaQueryWrapper）不标锚点——嵌套条件 lambda 参数不是独立 wrapper 变量（方法参数
            // `void del(LambdaQueryWrapper<X> w)` 父链是 PsiMethod，不受影响）。O(1) 语法级（parent 链）。
            if (variable is PsiParameter && (variable.parent?.parent) is PsiLambdaExpression) return null
            if (WrapperLocator.detectWrapperType(variable.type) != null) return element
            // 降级：变量用抽象基类型声明（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`），
            // 初始化表达式链首是具体 wrapper 构造 / Wrappers 工厂 → 仍应标变量声明。
            // 仅限 Wrapper 基类型（T-M15-01 回归）：`long total = service.lambdaQuery()...` 的 total 不是锚点
            val typeName = (variable.type as? com.intellij.psi.PsiClassType)?.className
            if (typeName !in WRAPPER_BASE_TYPE_NAMES) return null
            val init = variable.initializer ?: return null
            if (initializerHeadIsWrapper(init)) return element
            return null
        }

        // 场景 A：wrapper 构造的 new 关键字
        if (element is PsiKeyword && element.text == "new") {
            val newExpr = element.parent as? PsiNewExpression ?: return null
            if (WrapperLocator.detectWrapperType(newExpr.type) == null) return null
            // 该 new 位于某 Wrapper 变量初始化表达式内（`w = new W()...`）→ 去重，只标变量声明
            return if (isInsideWrapperVariableInitializer(newExpr)) null else element
        }

        // 场景 C：内联 Wrappers/ChainWrappers 静态工厂调用（`Wrappers.<X>lambdaQuery()...`、`ChainWrappers.queryChain(...)`，
        //          无变量、无 new）+ 服务链调用（`service.lambdaQuery()...` / `service.lambdaUpdate()...`，T-M15-01，
        //          qualifier 为任意 service 引用或隐式 this，语法级文本匹配，不 resolve；
        //          P1-03：query/update 歧义方法经 isServiceChainStart 守卫——`service.update(wrapper)` 消费调用不标锚点）
        if (element is PsiIdentifier) {
            val ref = element.parent as? PsiReferenceExpression ?: return null
            if (ref.referenceNameElement !== element) return null
            // XB（M44）：xbatis 双坐标静态工厂（QueryChain.of(userDao, X.class) / QueryChain.create()）——
            // 工厂方法名 + 工厂类简名双重语法门控（O(1)，Optional.of/Map.of 等通用 of 调用天然排除）
            if (ref.referenceName in WrapperLocator.xbatisFactoryMethodNames &&
                (ref.qualifierExpression as? PsiReferenceExpression)?.referenceName in WrapperLocator.xbatisFactoryClassNames
            ) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                return if (isInsideWrapperVariableInitializer(call)) null else element
            }
            // 场景 D-0（T-WriteFamily R4）：空 Wrapper 写族——`mapper.delete(null)` / `service.remove(null)` /
            // `mapper.update(entity, null)`。三个名字里 `delete` / `update` 同时是 Wrapper 工厂/服务链方法名，
            // 下方场景 C 对「非工厂、非链起点」的调用会直接 `return null`（`JoinWrappers.delete(...)`、
            // `service.update(wrapper)` 用的就是这条短路），因此本判定必须**先于**工厂块；
            // 形态门是精确的「Wrapper 实参为 null 字面量」（[BatchOperationConverter.isNoWrapperConsumingCall]
            // ＝形态 + MP receiver），`delete(new LambdaQueryWrapper<>()...)` / `delete(wrapperVar)` /
            // `update(entity, wrapperVar)` 都不满足 → 落到场景 C/D，锚点仍由链（A/B/C）承载，不产生第二枚图标。
            if (ref.referenceName in BatchOperationConverter.nullWrapperWriteMethodNames) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                if (BatchOperationConverter.isNoWrapperConsumingCall(call)) return element
                // 仅 delete/update 继续走工厂判定（JoinWrappers.delete(Entity.class) 等）；
                // remove 不是工厂名，非空 Wrapper 形态由 Wrapper 链锚点承载，此处不亮
                if (ref.referenceName !in WrapperLocator.wrapperFactoryMethodNames) return null
            }
            if (ref.referenceName in WrapperLocator.wrapperFactoryMethodNames) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                val qualifier = ref.qualifierExpression as? PsiReferenceExpression
                val isWrappersFactory = qualifier?.referenceName in WrapperLocator.wrapperFactoryClassNames
                // M38.1（负例回归集实证）：qualifier 是 new 表达式（`new Helper().lambdaQuery()`）——
                // 任意普通类实例的撞名调用，不是 MP 服务链（服务链 qualifier 是变量/字段/this 引用），不标
                val qualifierIsNewExpression = ref.qualifierExpression is PsiNewExpression
                val isServiceChain = ref.referenceName in WrapperLocator.serviceChainMethodNames &&
                    !qualifierIsNewExpression &&
                    WrapperLocator.isServiceChainStart(call)
                if (!isWrappersFactory && !isServiceChain) {
                    // 优先级：工厂 > 服务链 > JPA > 无锚点（T-JpaDeleteAnchor）。
                    // `delete` / `update` 同时是 Wrapper 工厂/链方法名，上面两个分支优先认领
                    // （`JoinWrappers.delete(Class)` / `JoinWrappers.update(Class)` 仍走工厂分支）；
                    // 落到这里才问一次 JPA 家族：`repository.delete(entity)` / `ht.delete(entity)` /
                    // `ht.update(entity)` 有预览能力、只缺 gutter 入口。家族判定失败（含
                    // `service.update(wrapper)` 消费调用、wrapper 链终结点 `update`）保持原 `return null`。
                    if (ref.referenceName in JPA_COLLIDING_METHOD_NAMES &&
                        JpaCrudOperationConverter.hasJpaCrudReceiver(call)
                    ) return element
                    return null
                }
                // 该工厂/链调用位于某 Wrapper 变量初始化表达式内（`w = Wrappers.lambdaQuery()...`）→ 去重
                return if (isInsideWrapperVariableInitializer(call)) null else element
            }
            // 场景 D：批量操作调用（`service.updateBatchById(list)` 等，F-15/M6）
            if (ref.referenceName in BatchOperationConverter.batchOperationMethodNames) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                if (call.argumentList.expressions.isEmpty()) return null
                // T-M23.11（用户实测）：wrapper 链上的同名调用（`new MPJLambdaWrapper<>().selectAll(Entity.class)`——
                // selectAll 同时收录于批量注册表与 MPJ 链方法）不标批量锚点——链锚点由场景 A（new）/B（变量）/C（工厂）
                // 承载，避免同一链双图标；批量形态（service.updateBatchById / Db.selectAll / entity.selectAll()）
                // 链首非 wrapper 构造/工厂，不受影响。O(1) 语法级链首回溯，无 resolve，符合性能红线。
                if (initializerHeadIsWrapper(call)) return null
                // T-DaoSql-03：字符串 SQL 形态（自研框架 `beanCruder.selectList(X.class, sql, ...)`——
                // selectList 同时收录于批量注册表与字符串 SQL 方法候选）让位给
                // JdbcTemplateSqlGutterLineMarkerProvider（批量转换器只认 Wrapper/集合实参，
                // 字符串 SQL 形态点击必失败；让位后字符串 SQL 路径接管，避免同行双图标）
                if (PreviewReceiverResolver.hasStringLikeArg(call)) return null
                // T-GutterDupFreeze（S2，20261008 用户实测）：**实参是 Wrapper（链或变量）→ 让位**。
                // 形态＝`userMapper.selectList(Wrappers.<User>lambdaQuery(...)...)` / `selectList(variableWrapper)`：
                // 链首是 mapper 变量（非 wrapper 构造/工厂），上一条 `initializerHeadIsWrapper(call)` 拦不住，
                // 于是 selectList 这个 leaf 在链锚点之外再挂一枚批量图标（用户截图 190/211/214 行各 2 枚）。
                // 这类调用的语句承载在**实参链**上：锚点由场景 B/C（链根/变量声明行）承载，
                // 批量族只负责集合/实体/id/Map/无参形态（`selectList(null)` 等无 wrapper 实参者仍照常亮）。
                // **例外＝MPJ Maps 终止调用族**（[MAPS_TERMINAL_NAMES]）：T-MapResultVo 登记时判定
                // 「调用行独立承载锚点」属契约（20261007 复核），让位会破其三个不变量用例，故豁免。
                if (ref.referenceName !in MAPS_TERMINAL_NAMES && hasWrapperArgument(call)) return null
                // T-M23.11 分步形态（`MPJLambdaWrapper w = new ...; w.selectAll(...)`）：qualifier 是变量引用，
                // 语法级回溯不到链首——仅对「与链方法撞名的批量方法」（目前仅 selectAll，新增撞名时扩展）做一次
                // 变量 resolve（局部变量作用域内查找，微秒级；普通标识符负例仍零 resolve，红线语义不变）；
                // wrapper 变量上的调用不标批量锚点（链锚点已由场景 B 变量行承载）
                if (ref.referenceName in BATCH_CHAIN_COLLISION_NAMES) {
                    val variable = (call.methodExpression.qualifierExpression as? PsiReferenceExpression)
                        ?.resolve() as? PsiVariable
                    if (variable != null && WrapperLocator.isWrapperVariable(variable)) return null
                }
                return element
            }
            // 场景 E（T-J1/T-J6/T-J21）：JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作
            // （`userRepository.findById(id)` / `em.persist(entity)` / `getHibernateTemplate().get(X.class, id)` 等）。
            // **与场景 D 撞名面大（save/count/delete/find 等通用名）**，必须叠加 receiver 家族判定——
            // qualifier 变量一次 resolve + 类型父链文本匹配（BATCH_CHAIN_COLLISION_NAMES 同款微秒级先例，
            // 非纯 O(1) 的文档化例外）；家族判定失败（普通类/无 qualifier/wrapper 链上同名终结点）不亮图标：
            // 宁可无图标，不可误亮（`stream.count()` 等海量负例）。点击后 preview 仍以 receiver 家族派发，
            // 非 JPA 家族返回 null 走 MP 批量路径。
            if (ref.referenceName in JpaCrudOperationConverter.crudAnchorMethodNames) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                if (initializerHeadIsWrapper(call)) return null
                return if (JpaCrudOperationConverter.hasJpaCrudReceiver(call)) element else null
            }
            // 场景 F（EQ，M44 其他 ORM 第一批）：Easy-Query proxy 链 entry
            // （easyEntityQuery.queryable(Topic.class)/insertable/updatable/deletable）——
            // entry 方法名 + receiver 变量名/类型启发双重门控（普通类同名方法不识别，负例守卫；
            // qualifier 变量一次 resolve 同场景 E 的文档化例外）。
            if (ref.referenceName in EasyQueryConverter.ENTRY_METHODS) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                if (initializerHeadIsWrapper(call)) return null
                return if (EasyQueryConverter.isEasyQueryCall(call)) element else null
            }
            return null
        }

        return null
    }

    /**
     * 初始化表达式链首是否为具体 Wrapper（语法级）：沿 `methodExpression.qualifierExpression`
     * 回溯到链起点，判断是 wrapper 构造（new）或 `Wrappers.<T>lambdaQuery()` 等工厂调用。
     *
     * 修复（T-Mxx）：原回溯终点停在 qualifier 引用而不继续判定工厂调用——`JoinWrappers.lambda(...)
     * .selectAll(...)` 的链头是 `lambda` 工厂**调用**，但回溯沿 qualifier 落在 `JoinWrappers`
     * （PsiReferenceExpression）即停止 → 返回 false → 场景 D 的 selectAll 未被去重 → 双图标。
     * 现改为先列为**调用自身**即是工厂方法名的情况（链头 = 工厂调用，无需再沿 qualifier 回溯）。
     */
    private fun initializerHeadIsWrapper(init: PsiElement): Boolean {
        var expr: PsiElement = init
        // 链头可以是工厂方法调用自身（`JoinWrappers.lambda(...)`）；此时 expr 保持为该调用
        var headCall: PsiMethodCallExpression? = null
        while (expr is PsiMethodCallExpression) {
            headCall = expr
            val qualifier = expr.methodExpression.qualifierExpression ?: break
            // 若当前方法名即工厂方法（如 JoinWrappers.lambda）→ 该调用即链头，无需再回溯
            if (expr.methodExpression.referenceName in WrapperLocator.wrapperFactoryMethodNames) break
            expr = qualifier
        }
        return when {
            headCall?.methodExpression?.referenceName in WrapperLocator.wrapperFactoryMethodNames -> true
            expr is PsiNewExpression -> WrapperLocator.detectWrapperType(expr.type) != null
            expr is PsiMethodCallExpression -> expr.methodExpression.referenceName in WrapperLocator.wrapperFactoryMethodNames
            else -> false
        }
    }

    /**
     * T-GutterDupFreeze（S2）：调用实参中是否存在 Wrapper（调用链或 wrapper 变量）。
     *
     * 用于批量族锚点（场景 D）让位：`userMapper.selectList(Wrappers.<User>lambdaQuery(...)...)`、
     * `userMapper.selectList(variableWrapper)` 这类调用的 SQL 承载在**实参链**上——链锚点由
     * 场景 B（变量声明）/C（工厂方法名）承载，批量锚点让位，避免同一语句双图标。
     * 批量族本意覆盖集合/实体/id/Map/无参形态（`selectList(null)` 无 wrapper 实参，照常亮）。
     *
     * 判定成本：链实参走纯语法回溯（[initializerHeadIsWrapper]，零 resolve）；变量实参先看声明/推断类型
     * （[WrapperLocator.detectWrapperType]，类名文本匹配），类型不可得时才做一次变量 resolve——
     * 与 [BATCH_CHAIN_COLLISION_NAMES] 的 selectAll 判定同款微秒级文档化例外。调用方已先按批量注册表
     * 方法名过滤，本判定只作用于约 28 个批量方法名上的调用，不构成 daemon 热路径开销。
     */
    private fun hasWrapperArgument(call: PsiMethodCallExpression): Boolean =
        call.argumentList.expressions.any { arg ->
            when (arg) {
                is PsiMethodCallExpression -> initializerHeadIsWrapper(arg)
                is PsiReferenceExpression -> {
                    if (WrapperLocator.detectWrapperType(arg.type) != null) {
                        true
                    } else {
                        (runCatching { arg.resolve() }.getOrNull() as? PsiVariable)
                            ?.let { WrapperLocator.isWrapperVariable(it) } == true
                    }
                }
                else -> false
            }
        }

    /**
     * 判定表达式（new 构造或工厂调用）是否位于某 **Wrapper 变量**的初始化表达式内。
     *
     * 沿 PSI 树向上查找最近的 `PsiVariable` 祖先：是 Wrapper 变量 → 在初始化内（去重）；
     * 无变量祖先（内联场景，如 `list(...)`）→ 不在（应标该表达式锚点）。
     *
     * M19.5（T-M19.5-01，2026-08-13 用户实测）：**仅当变量本身是 Wrapper 变量才去重**——
     * 结果变量赋值形态（`XxxEntity e = service.lambdaQuery()...one()` / `boolean b = service.lambdaUpdate()...update()`）
     * 变量类型非 Wrapper（场景 B 不标变量），此时去重会整条链失去锚点；`isWrapperVariable` 为 O(1)
     * 纯语法判定（文本匹配 + initializer 链首回溯，无 resolve），符合性能红线。
     *
     * 修复（T-Mxx）：原实现沿 `expr.parent` 单链逐层判断 `is PsiExpression` 才继续，链式调用中
     * `PsiReferenceExpression` / `PsiMethodCallExpression` 交替，遇到 lambda 参数列表等非 Expression
     * 节点即中断返回 false → 场景 C 工厂方法名未被去重 → 双图标。改用 [PsiTreeUtil.getParentOfType]
     * 直接定位最近变量祖先，健壮处理跨行链与方法引用/类字面量实参。
     */
    private fun isInsideWrapperVariableInitializer(expr: PsiElement): Boolean {
        val variable = PsiTreeUtil.getParentOfType(expr, PsiVariable::class.java, false)
            ?: return false
        return WrapperLocator.isWrapperVariable(variable)
    }

    // ------------------------------------------------------------------
    // F-4：Kotlin 文件 gutter 锚点检测（MVP，文本级 O(1) 匹配）
    // ------------------------------------------------------------------

    /** 元素所在文件是否为 Kotlin 文件（按扩展名判断，O(1)）。 */
    private fun isKotlinFile(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val ext = file.virtualFile?.extension ?: ""
        return ext == "kt" || ext == "kts"
    }

}
