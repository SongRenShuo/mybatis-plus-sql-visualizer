package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.model.BatchPreview
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParamQuote
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * 批量/单实体操作转换器（F-15，M6-01 + T-M15-08 扩展 + M19.5 实体单条 CRUD）：识别
 * `service.updateBatchById(list)` 等调用，生成 SQL 模板 + 参数组说明。
 *
 * 支持的批量方法：
 *  - `updateBatchById(list)` / `updateById(entity)`：UPDATE 表 SET 非主键列 = ? WHERE 主键 = ?
 *  - `saveBatch(list)` / `insertBatchSomeColumn(list)` / `save(entity)`（M19.5 补注册，与注释一致化）/
 *    `insert(list)` / `insert(entity)`（T-M20-08，BaseMapper 3.5.7+ 批量族，单实体与集合同 INSERT 模板）：
 *    INSERT INTO 表 (列...) VALUES (?, ...)
 *  - `saveOrUpdate(entity)` / `saveOrUpdateBatch(list)`（U12，T-M15-08）/ `insertOrUpdate(entity)`（T-M20-08）：
 *    INSERT + 方言 upsert（PG `ON CONFLICT`）
 *  - `removeByIds(list)` / `removeById(id)` / `deleteByIds(ids)`（U11/P1-02，T-M15-08 + M19.5）/
 *    `removeBatchByIds(list)`（T-M20-10 核对补注册）/ `deleteById(id)`（T-M22-04，Mapper 单删与
 *    ActiveRecord 无参形态同主键等值模板）：DELETE FROM 表 WHERE 主键 IN/=；
 *    **T-M23-01（用户确认）**：实体含 @TableLogic 时删除操作实际是修改操作——转软删除
 *    `UPDATE 表 SET deleted = 1 WHERE ... AND deleted = 0`（对齐 MP LogicDeleteSqlInjector）；
 *    无 @TableLogic 维持物理 DELETE。
 *  - `removeByMap(map)` / `deleteByMap(map)`（T-M20-07，MP/MPJ Map 条件删除）与
 *    `selectByMap(map)` / `listByMap(map)`（T-M20-07 Map 条件查询）：
 *    WHERE Map 等值条件占位（键值静态不可得，可读占位文本）
 *  - `delete(Wrapper)` / `remove(Wrapper)` / `update(T, Wrapper)`（T-WriteFamily R4 写族补全）：
 *    **只接管 Wrapper 实参为 `null` 字面量**的形态——`delete(null)` / `remove(null)` → `DELETE FROM 表`
 *    （@TableLogic 转 `UPDATE 表 SET deleted = 1 WHERE deleted = 0`）；`update(entity, null)` →
 *    `UPDATE 表 SET 实体列 = ?`（**无 WHERE**）；两者都带全表写操作警示（[NO_WHERE_DELETE_WARNING] /
 *    [NO_WHERE_UPDATE_WARNING]）。非 null 形态返回 null：由 Wrapper 链路径承载（绝不退化成无 WHERE）
 *  - `getById(id)` / `getOptById(id)`（T-M20-10，IService 3.5.15）/ `listByIds(ids)` / `selectByIds(ids)`
 *    （M19.5，实体单条查询，P2-08 同点合并）：SELECT 全列 FROM 表 WHERE 主键 =/IN；
 *    **T-M23-01**：实体含 @TableLogic 时 WHERE 追加 `AND deleted = 0`（对齐 MP 内置 SQL）
 *  - `selectAll()`（T-M22-04，ActiveRecord）：SELECT 全列 FROM 表（无 WHERE；@TableLogic 时 WHERE deleted = 0）
 *
 * 实体类解析顺序：集合参数泛型（`List<Entity>`）→ 实体参数类型 → service qualifier 泛型
 * （`XxxService extends IService<Entity>` / `ServiceImpl<Mapper, Entity>`，getById/removeById 单参场景）
 * → qualifier 实体类型（T-M22-04，ActiveRecord `entity.insert()` 等无参形态）。
 * 运行时行值不可得，输出 `?` 模板并附参数组说明（每行一组，字段顺序与 SQL 一致）。
 *
 * T-M23-02（参数面板）：单主键方法实参可静态求值（字面量/可追踪变量，[ExpressionEvaluator]）时
 * **内联**进 SQL（`removeById(1L)` → `WHERE id = 1`）；不可求值 → `?` + 参数登记
 * （source=实参文本，面板参数行）；实体/集合方法每个 `?` 登记 `实体变量.列名`。
 */
object BatchOperationConverter {

    /** 批量方法 → SQL 模板生成策略。 */
    private enum class Operation(val sqlPrefix: String) {
        UPDATE("UPDATE"),
        INSERT("INSERT"),
        DELETE("DELETE"),
        UPSERT("UPSERT"),
        SELECT("SELECT"),
    }

    private val BATCH_OPERATIONS = mapOf(
        "updateBatchById" to Operation.UPDATE,
        "updateById" to Operation.UPDATE,
        "saveBatch" to Operation.INSERT,
        "insertBatchSomeColumn" to Operation.INSERT,
        // T-M20-08：BaseMapper 批量族（MP 3.5.7+）——insert(Collection)/insert(Collection, int)/insert(T)
        // 同名共用一个 INSERT 模板；insert 为通用 Java 方法名，非实体调用由 [isLikelyEntity] 挡掉
        "insert" to Operation.INSERT,
        "save" to Operation.INSERT,
        "saveOrUpdate" to Operation.UPSERT,
        // T-M20-08：BaseMapper.insertOrUpdate（全重载同名，BiPredicate 参数忽略）与 saveOrUpdate 同 upsert 模板
        "insertOrUpdate" to Operation.UPSERT,
        "saveOrUpdateBatch" to Operation.UPSERT,
        "removeByIds" to Operation.DELETE,
        "removeById" to Operation.DELETE,
        "deleteByIds" to Operation.DELETE,
        // T-M22-04：Mapper.deleteById(id) 与 ActiveRecord 无参形态（entity.deleteById()）同模板——主键等值
        "deleteById" to Operation.DELETE,
        // T-WriteFamily（R4）：空 Wrapper 写族——`BaseMapper.delete(Wrapper)`（3.5.17 javap 实证，参数可 null）/
        // `BaseMapper.update(T, Wrapper)` / `IService.remove(Wrapper)`。三者只认「Wrapper 实参为 null 字面量」这一
        // 精确形态（[isNullWrapperShape]），非空 Wrapper 由 Wrapper 链锚点承载、本注册表不接管（return null）。
        // 名字同时是 Wrapper 工厂/链终结点（delete/update），锚点侧另有接收者守卫（[hasMpCrudReceiver]）。
        "delete" to Operation.DELETE,
        "update" to Operation.UPDATE,
        "remove" to Operation.DELETE,
        // T-M20-10 核对：IService 3.5.15 便捷重载 removeBatchByIds(Collection)——核对时补注册（与 removeByIds 同模板）
        "removeBatchByIds" to Operation.DELETE,
        // T-M20-07：Map 条件消费（MP/MPJ）——DELETE/SELECT WHERE Map 等值条件（独立 builder，见 [buildMapConditionBatch]）
        "removeByMap" to Operation.DELETE,
        "deleteByMap" to Operation.DELETE,
        "getById" to Operation.SELECT,
        // T-M20-10：IService 3.5.15 getOptById(id) → 与 getById 同主键等值模板
        "getOptById" to Operation.SELECT,
        // T-M22-04：ActiveRecord（AbstractModel）selectAll() 全列查询——无 WHERE（buildSelectBatch 方法名分支）
        "selectAll" to Operation.SELECT,
        "listByIds" to Operation.SELECT,
        "selectByIds" to Operation.SELECT,
        "selectByMap" to Operation.SELECT,
        "listByMap" to Operation.SELECT,
        // T-Mxx：BaseMapper 核心方法（MP 3.5.x）——补全单数/列表/计数
        "selectById" to Operation.SELECT,
        "selectList" to Operation.SELECT,
        "selectMaps" to Operation.SELECT,
        "selectObjs" to Operation.SELECT,
        "selectCount" to Operation.SELECT,
        // T-MapResultVo：MPJ Map 响应族（JoinMapper.selectJoinMaps/selectJoinMapsPage，1.5.8 javap 实证）
        "selectJoinMaps" to Operation.SELECT,
        "selectJoinMapsPage" to Operation.SELECT,
    )

    /** T-M20-07：Map 等值条件操作方法名（独立 builder，不并入主键等值/IN 分支）。 */
    private val MAP_CONDITION_METHODS = setOf("removeByMap", "deleteByMap", "selectByMap", "listByMap")

    /** T-M20-10：主键等值单条查询方法名（getById / getOptById，其余批量查询走主键 IN）。 */
    private val SINGLE_ID_SELECT_METHODS = setOf("getById", "getOptById", "selectById")

    /** T-Mxx：全列查询无 WHERE 方法名（selectList / selectMaps / selectObjs——BaseMapper 列表查询）。 */
    private val FULL_COLUMN_SELECT_METHODS = setOf("selectList", "selectMaps", "selectObjs")

    /** T-M22-04：主键等值删除方法名（removeById / deleteById——Mapper 单删与 ActiveRecord 无参形态同模板，其余走主键 IN）。 */
    private val SINGLE_ID_DELETE_METHODS = setOf("removeById", "deleteById")

    /** 批量操作方法名集合（M10 起 internal 供 [WrapperAnchorDetector] gutter 锚点复用）。 */
    internal val batchOperationMethodNames: Set<String> = BATCH_OPERATIONS.keys

    /**
     * T-WriteFamily（R4）：只认「Wrapper 实参为 null 字面量」的写族方法名
     * （`delete` / `remove` 单参 null；`update` 两参第二参 null）。
     *
     * 与其余批量方法名的差别：其余名字的实参是实体/集合/主键，方法名本身即足够；
     * 这三个名字的 Wrapper 实参才是语句载体，**非空 Wrapper 必须让位给链锚点**，
     * 因此锚点侧（[WrapperAnchorDetector] 场景 D）与选择区候选侧（[WrapperLocator.isSqlCandidate]）
     * 都要叠加 [isNoWrapperConsumingCall] 的精确形态 + 接收者判定。
     */
    internal val nullWrapperWriteMethodNames: Set<String> = setOf("delete", "update", "remove")

    /** T-WriteFamily：无 WHERE 全表删除警示（与 JPA BlockAttack / EasyQuery 全表警示同文案口径）。 */
    internal const val NO_WHERE_DELETE_WARNING = "⚠ 无 WHERE 条件，将删除全表数据！"

    /** T-WriteFamily：无 WHERE 全表更新警示。 */
    internal const val NO_WHERE_UPDATE_WARNING = "⚠ 无 WHERE 条件，将更新全表数据！"

    /** T-WriteFamily：MP CRUD 基类 FQN 前缀（优先按 FQN 判定）。 */
    private const val MP_CRUD_FQN_PREFIX = "com.baomidou.mybatisplus"

    /**
     * T-WriteFamily：MP CRUD 基类简名集合（`BaseMapper` / `IService` / `ServiceImpl` / `AbstractModel`）——
     * 类型父链（含父链的父链）命中任一即视为 MP 写族 receiver。
     * 简名兜底用于「依赖不在索引里」的工程（含测试桩）；口径见计划 P-5（同名自有基类风险已知并接受）。
     */
    private val MP_CRUD_BASE_SIMPLE_NAMES: Set<String> =
        setOf("BaseMapper", "IService", "ServiceImpl", "AbstractModel")

    /** T-WriteFamily：类型父链遍历深度上限（`XxxServiceImpl → ServiceImpl → IService` 两跳够用，留一档余量）。 */
    private const val MP_CRUD_SUPER_TYPE_DEPTH = 3

    /**
     * T-M23-02：单个 `?` 的参数登记（source + 回填引号提示，供面板参数行）。
     */
    private data class ParamEntry(val source: String, val hint: ParamHint)

    /**
     * T-M23-02：批量构建结果（SQL 模板 + 按 `?` 出现顺序的参数登记 + 参数组说明）。
     * T-M23-04：INSERT/UPDATE/UPSERT 携带 [BatchModel]（面板勾选重建 SQL 的唯一结构源）。
     */
    private data class BatchBuild(
        val sql: String,
        val params: List<ParamEntry> = emptyList(),
        val paramGroups: String? = null,
        val model: BatchModel? = null,
        /** T-WriteFamily（R4）：无 WHERE 写操作警示（delete/remove/update 的全表形态）。 */
        val warning: String? = null,
    )

    /**
     * T-M23-04：由列清单构造勾选列（source = `实体前缀.列名`，hint 按列类型引号判定）。
     *
     * O-52 订正（独立复核 major⑭）：本链是**全仓第三条产出 INSERT 的链**（MP `saveBatch`/`insertBatch`
     * 等 PSI 批量链），此前只给 `CrudSqlGenerator` 一条链接线了 `autoGenerated`——缺这个标记时
     * [BatchModel.renderSql] 的 INSERT 分支判 `active.none { it.autoGenerated }` 为真，于是用户在
     * 预览面板**勾回自增列**后，SQL Server 上仍产出无包装的 INSERT、依旧报 544。取值口径与实体
     * CRUD 链同源（`GenerateCrudSqlFromEntityAction` 的 `autoGenerated = col.idTypeAuto`）。
     */
    private fun batchColumns(prefix: String, table: TableInfo, columns: List<ColumnInfo>, checked: (ColumnInfo) -> Boolean): List<BatchColumn> =
        columns.map { col ->
            BatchColumn(
                columnName = col.columnName,
                source = idSource(prefix, col.columnName),
                quote = ParamQuote.decide(col.fieldType),
                checked = checked(col),
                autoGenerated = col.idTypeAuto,
                fill = col.fill,
            )
        }

    /**
     * T-M23-04：模型驱动的批量构建（初始 SQL 与参数登记均出自 [BatchModel.renderSql]/ordered*，
     * 与面板勾选重建共用唯一渲染源）。
     * T-M23.4：注入当前方言（关键字标识符引用——保留字列名按方言符号包裹；设置服务不可用时兜底 MySQL）。
     */
    private fun modelBuild(model: BatchModel, call: PsiMethodCallExpression): BatchBuild {
        val model = model.copy(dialect = currentDialect(call.project))
        val initialActive = (model.toggleColumns.filter { it.checked }.map { it.columnName } +
            model.fixedColumns.map { it.columnName }).toSet()
        val params = model.orderedColumns(initialActive).map { ParamEntry(it.source, ParamHint(quote = it.quote)) }
        return BatchBuild(sql = model.renderSql(initialActive), params = params, model = model)
    }

    /**
     * 转换批量/单实体方法调用。
     *
     * @param call              service 方法调用表达式
     * @param injectLogicDelete 逻辑删除注入开关（T-M23-01，settings.injectLogicDelete）：
     *                          关闭时 SELECT 不追加 `deleted = 0`、删除不转软删除
     * @param resolveVariables  变量求值开关（T-M23-02，settings.replaceParams）：false 时跳过
     *                          实参静态求值，一律 `?` + 参数行（对齐 Wrapper 路径 ArgResolver）
     * @return 批量预览；方法不匹配或实体无法解析时返回 null
     */
    fun convert(
        call: PsiMethodCallExpression,
        injectLogicDelete: Boolean = true,
        resolveVariables: Boolean = true,
        injectTenantLine: Boolean = true,
    ): BatchPreview? {
        val methodName = call.methodExpression.referenceName ?: return null
        val operation = BATCH_OPERATIONS[methodName] ?: return null
        val target = resolveEntityTable(call) ?: return null
        val table = target.table
        // T-M20-07：Map 等值条件方法独立分支（键值静态不可得 → 占位文本；避免并入主键等值/IN 分支）
        // T-WriteFamily（R4）：空 Wrapper 写族在各自 builder 内按「null 字面量」精确判定，非该形态返回 null
        // （绝不退化成无 WHERE）→ 本条链路整体 return null，交回 Wrapper 路径 / 如实无预览
        val build = (if (methodName in MAP_CONDITION_METHODS) {
            buildMapConditionBatch(call, methodName, table, injectLogicDelete)
        } else {
            when (operation) {
                Operation.UPDATE -> buildUpdateBatch(call, methodName, table)
                Operation.INSERT -> buildInsertBatch(call, methodName, table, target.entityClass, injectTenantLine)
                Operation.DELETE -> buildDeleteBatch(call, methodName, table, injectLogicDelete, resolveVariables)
                Operation.UPSERT -> buildUpsertBatch(call, methodName, table)
                Operation.SELECT -> buildSelectBatch(call, methodName, table, injectLogicDelete, resolveVariables)
            }
        }) ?: return null
        return BatchPreview(
            methodName = methodName,
            tableName = table.tableName,
            sql = build.sql,
            paramGroups = build.paramGroups,
            paramSources = build.params.map { it.source },
            paramHints = build.params.map { it.hint },
            batchModel = build.model,
            warning = build.warning,
        )
    }

    // ------------------------------------------------------------------
    // T-WriteFamily（R4）：空 Wrapper 写族的精确形态 + 接收者守卫
    // ------------------------------------------------------------------

    /**
     * 调用是否为批量/写族候选（[WrapperLocator.isSqlCandidate] 选择区扫描用）。
     *
     * 普通批量方法名（`getById` / `save` / `removeById` …）只要在注册表内即算候选（存量行为）；
     * T-WriteFamily 新增的 [nullWrapperWriteMethodNames] 必须叠加
     * [isNoWrapperConsumingCall]（空 Wrapper 精确形态 + MP receiver），否则 `redisTemplate.delete(null)`、
     * `list.remove(0)` 之类会进多 SQL 选择列表。
     */
    internal fun isBatchOperationCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName ?: return false
        if (methodName !in BATCH_OPERATIONS) return false
        return methodName !in nullWrapperWriteMethodNames || isNoWrapperConsumingCall(call)
    }

    /**
     * 调用是否为「空 Wrapper 消费」形态（T-WriteFamily R4）：
     *  - `delete` / `remove`：**恰好 1 个实参且为 `null` 字面量**（解一层 `PsiTypeCastExpression`）；
     *  - `update`：**至少 2 个实参且第 2 个为 `null` 字面量**（解一层 cast）。
     *
     * `delete(new LambdaQueryWrapper<>()...)` / `delete(wrapperVar)` / `update(entity, wrapperVar)` /
     * `service.update(null)`（单参，SQL 本身非法）都不满足——锚点让位给 Wrapper 链（场景 A/B/C）。
     */
    internal fun isNullWrapperShape(call: PsiMethodCallExpression): Boolean {
        val args = call.argumentList.expressions
        return when (call.methodExpression.referenceName) {
            "delete", "remove" -> args.size == 1 && isNullLiteral(args[0])
            "update" -> args.size >= 2 && isNullLiteral(args[1])
            else -> false
        }
    }

    /**
     * 空 Wrapper 写族调用的完整门（注册表 + 形态 + receiver）：锚点与选择区候选共用同一判定，
     * 保证 gutter 亮图标与预览可产出严格一致（宁可无图标，不可误亮）。
     *
     * **注册表成员资格也是门的一部分**：名字不在 [BATCH_OPERATIONS] 时预览链路必然 null，
     * 此时若锚点仍亮就是死图标（反向验证「去掉注册表的 delete」必须同时打掉锚点与预览）。
     */
    internal fun isNoWrapperConsumingCall(call: PsiMethodCallExpression): Boolean =
        call.methodExpression.referenceName in BATCH_OPERATIONS &&
            isNullWrapperShape(call) &&
            hasMpCrudReceiver(call)

    /** 表达式是否为 `null` 字面量（解一层 `(Wrapper<X>) null` 强转包裹）。 */
    private fun isNullLiteral(expr: PsiExpression): Boolean {
        val unwrapped = (expr as? PsiTypeCastExpression)?.operand ?: expr
        val literal = unwrapped as? PsiLiteralExpression ?: return false
        return literal.value == null
    }

    /**
     * receiver 是否为 MyBatis-Plus CRUD 家族（形态对齐 [cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter.hasJpaCrudReceiver]）。
     *
     * 判定顺序：
     *  1. qualifier 变量 / 形参 / 字段的类型（含父类型链）命中 `BaseMapper` / `IService` / `ServiceImpl` /
     *     `AbstractModel`（FQN 前缀 `com.baomidou.mybatisplus` 优先，简名兜底）；
     *  2. qualifier 是 `getBaseMapper()` 调用或变量名 `baseMapper` → 直接放行（MP ServiceImpl 惯用形态）；
     *  3. 隐式 / 显式 `this` → 所在类父链含 `ServiceImpl` / `IService`（Service 内部 `update(entity, null)`）。
     *
     * 成本：只在 [isNullWrapperShape] 通过后调用（形态门已把负例压到极小），一次变量 resolve +
     * 类型父链文本匹配，与场景 E 的 receiver 判定同款微秒级文档化例外。
     */
    internal fun hasMpCrudReceiver(call: PsiMethodCallExpression): Boolean = runCatching {
        when (val qualifier = call.methodExpression.qualifierExpression) {
            null -> isMpCrudClassChain(PsiTreeUtil.getParentOfType(call, PsiClass::class.java))
            is PsiThisExpression -> isMpCrudClassChain(PsiTreeUtil.getParentOfType(call, PsiClass::class.java))
            is PsiMethodCallExpression ->
                qualifier.methodExpression.referenceName == "getBaseMapper" || isMpCrudType(receiverType(qualifier))
            is PsiReferenceExpression ->
                qualifier.referenceName == "baseMapper" || isMpCrudType(receiverType(qualifier))
            else -> isMpCrudType(qualifier.type as? PsiClassType)
        }
    }.getOrDefault(false)

    /** qualifier 表达式的接收者类型（变量优先解析声明类型，方法调用用返回类型）。 */
    private fun receiverType(expr: PsiExpression): PsiClassType? = when (expr) {
        is PsiReferenceExpression ->
            (expr.resolve() as? PsiVariable)?.type as? PsiClassType ?: expr.type as? PsiClassType
        else -> expr.type as? PsiClassType
    }

    /** 类型自身或父链是否命中 MP CRUD 基类。 */
    private fun isMpCrudType(type: PsiClassType?, depth: Int = MP_CRUD_SUPER_TYPE_DEPTH): Boolean {
        if (type == null || depth < 0) return false
        if (isMpCrudTypeText(type.canonicalText)) return true
        return isMpCrudClassChain(type.resolve(), depth - 1)
    }

    /** 类自身的父类型链（含父链的父链）是否命中 MP CRUD 基类。 */
    private fun isMpCrudClassChain(clazz: PsiClass?, depth: Int = MP_CRUD_SUPER_TYPE_DEPTH): Boolean {
        if (clazz == null || depth < 0) return false
        for (superType in clazz.superTypes) {
            if (isMpCrudTypeText(superType.canonicalText)) return true
            if (isMpCrudClassChain(superType.resolve(), depth - 1)) return true
        }
        return false
    }

    /**
     * 类型文本是否为 MP CRUD 基类。
     *
     * FQN 可得且以 `com.baomidou.mybatisplus` 开头 → 直接按基类简名认定（优先口径）；
     * FQN 不可得 / 工程未索引依赖 / 测试桩只有简名 → **简名兜底**（同集合）。
     * 两条路径的接受集合相同，差别只在语义来源：前者是真 MP 类型，后者是计划 P-5 登记并接受的
     * 「同名自有基类」误放面（D-7 已明确不把该守卫扩到 insert 族，避免改变既有锚点集合）。
     */
    private fun isMpCrudTypeText(canonicalText: String?): Boolean {
        if (canonicalText.isNullOrBlank()) return false
        val raw = canonicalText.substringBefore('<').trim()
        // FQN 前缀优先：真 MP 全限定名（com.baomidou.mybatisplus.*）按基类简名认定
        if (raw.startsWith(MP_CRUD_FQN_PREFIX)) return raw.substringAfterLast('.') in MP_CRUD_BASE_SIMPLE_NAMES
        // 简名兜底：工程未索引 MP 依赖（含测试桩）时 canonicalText 只有简名或别的包名
        return raw.substringAfterLast('.') in MP_CRUD_BASE_SIMPLE_NAMES
    }

    /**
     * 批量操作方法调用解析结果（T-M2）：表信息 + 实体 PsiClass（@KeySequence 等类级注解读取用；
     * 实体来自泛型推断等无具体 PsiClass 的路径为 null）。
     */
    private data class ResolvedTarget(val table: TableInfo, val entityClass: PsiClass? = null)

    /**
     * 解析目标实体表信息（T-M15-08 起支持三种来源，T-M22-04 增补第四种）：
     *  1. 集合参数泛型（`List<Entity>`，批量形态）
     *  2. 实体参数类型（`save(entity)` / `updateById(entity)` 单实体形态）
     *  3. service qualifier 泛型（`getById(id)` / `removeById(id)` 单参形态：`XxxService extends IService<Entity>`）
     *  4. qualifier 实体类型（T-M22-04，ActiveRecord：`entity.insert()` / `entity.updateById()` /
     *     `entity.deleteById()` / `entity.selectAll()` 无实参形态——实体从 qualifier 变量类型解析）
     *
     * M19.6（2026-08-13 用户实测）：步骤 1/2 增加 [isLikelyEntity] 限定——`getById(dto.getId())`
     * 的实参 Long 在真实 IDE 可解析，原实现把 java.lang.Long 当实体（FROM long + serialVersionUID
     * 字段列，用户实测症状）；限定后 Long/String 等基础类型跳过，走步骤 3 service 泛型。
     * T-M22-04：步骤 3 首分支（变量类型自身泛型参数）同样加 [isLikelyEntity] 限定（selectAll 等
     * 常见方法名防误伤——`List<String> list.selectAll()` 的 String 不得解析为实体）；
     * 步骤 4 置于步骤 3 之后：`service.getById(id)` 的 qualifier 是 service 变量（非实体），
     * 由 [isLikelyEntity] 限定保证不误判，实体变量仅在 service 泛型解析失败时兜底。
     *
     * T-M2：同时携带解析出的实体 PsiClass（INSERT 模板 @KeySequence 序列提示读取用）。
     */
    private fun resolveEntityTable(call: PsiMethodCallExpression): ResolvedTarget? {
        val firstArg = call.argumentList.expressions.firstOrNull()
        // 1. 集合参数泛型（List<Entity>）；2. 实体参数类型（save(entity) / updateById(entity)）——
        // 无实参调用（ActiveRecord 无参形态）跳过，走步骤 3/4
        if (firstArg != null) {
            val listType = firstArg.type as? PsiClassType
            val entityType = listType?.parameters?.firstOrNull() as? PsiClassType
            val entityFromList = entityType?.resolve()
            if (entityFromList != null && isLikelyEntity(entityFromList)) {
                return ResolvedTarget(EntityResolver().resolve(entityFromList), entityFromList)
            }
            // 类字面量实参（Db.getById(User.class, id) / Db.list(User.class, wrapper)，T-M22-04）显式处理：
            // LightPlatform 下 `User.class` 表达式的 type（Class<User>）可能不可解析，
            // operand 类型（User 本身）更可靠；`save(entity)` 等普通实体实参走 .type 原路径
            val entityFromArg = when (firstArg) {
                is PsiClassObjectAccessExpression -> (firstArg.operand.type as? PsiClassType)?.resolve()
                else -> (firstArg.type as? PsiClassType)?.resolve()
            }
            if (entityFromArg != null && isLikelyEntity(entityFromArg)) {
                return ResolvedTarget(EntityResolver().resolve(entityFromArg), entityFromArg)
            }
        }
        // 3. service qualifier 泛型（getById(id) 等单参非实体场景）——
        // 含 Entity 后缀 / @TableName 的实体走 isLikelyEntity 快速路径；
        // 不含后缀的实体（如 User）由 resolveServiceEntityClass 步骤 3b 兜底（仅排除 JDK 包）
        val entityFromService = resolveServiceEntityClass(call)
        if (entityFromService != null) return ResolvedTarget(EntityResolver().resolve(entityFromService), entityFromService)
        // 3b. 隐式 this 回退（this.getById(id) —— qualifierExpression 为 null）：
        // 从 containing class 的父类/接口泛型参数解析实体（ServiceImpl<M, User> → User）
        val entityFromImplicitThis = resolveEntityFromImplicitThis(call)
        if (entityFromImplicitThis != null) {
            return ResolvedTarget(EntityResolver().resolve(entityFromImplicitThis), entityFromImplicitThis)
        }
        // 3c. 方法调用 qualifier 回退（getBaseMapper().selectById(id) —— qualifier 是 PsiMethodCallExpression）：
        // 从 qualifier 方法的返回类型泛型参数解析实体（BaseMapper<User> → User）
        val entityFromMethodQualifier = resolveEntityFromMethodQualifier(call)
        if (entityFromMethodQualifier != null) {
            return ResolvedTarget(EntityResolver().resolve(entityFromMethodQualifier), entityFromMethodQualifier)
        }
        // 4. qualifier 实体类型（T-M22-04，ActiveRecord 无参形态）
        val entityFromQualifier = resolveQualifierEntityClass(call) ?: return null
        return ResolvedTarget(EntityResolver().resolve(entityFromQualifier), entityFromQualifier)
    }

    /**
     * T-Mxx：隐式/显式 this 调用的实体解析——`getById(id)`（隐式 this，qualifierExpression 为 null）
     * 或 `this.getById(id)`（显式 this，qualifierExpression 为 PsiThisExpression）时，
     * 从 containing class 的父类/接口泛型参数解析实体（ServiceImpl<M, User> → User）。
     * 高置信度：方法名已在 BATCH_OPERATIONS 白名单中 + 调用者自身是 ServiceImpl/IService 子类。
     */
    private fun resolveEntityFromImplicitThis(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression
        // 仅当 qualifier 为 null（隐式 this）或 PsiThisExpression（显式 this）时触发
        if (qualifier != null && qualifier !is com.intellij.psi.PsiThisExpression) return null
        val containingClass = PsiTreeUtil.getParentOfType(call, PsiClass::class.java) ?: return null
        // 沿父类/接口链取最后一个类型参数（ServiceImpl<M, User> → User）
        for (superType in containingClass.superTypes) {
            val params = superType.parameters
            val candidate = params.lastOrNull() as? PsiClassType ?: continue
            val resolved = candidate.resolve() ?: continue
            val qualifiedName = resolved.qualifiedName ?: continue
            if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) continue
            return resolved
        }
        return null
    }

    /**
     * T-Mxx：方法调用 qualifier 的实体解析——`getBaseMapper().selectById(id)` 时 qualifier 是
     * PsiMethodCallExpression，从 qualifier 方法的返回类型泛型参数解析实体（BaseMapper<User> → User）。
     * 返回类型自身无泛型参数时（如 UserMapper extends BaseMapper<User>），回退到父类/接口泛型参数。
     */
    private fun resolveEntityFromMethodQualifier(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression as? PsiMethodCallExpression ?: return null
        // 解析 qualifier 方法的返回类型
        val returnType = qualifier.methodExpression.type ?: return null
        val classType = returnType as? PsiClassType ?: return null
        // 返回类型自身泛型参数（BaseMapper<User> → User）
        (classType.parameters.firstOrNull() as? PsiClassType)?.resolve()?.let { resolved ->
            val qualifiedName = resolved.qualifiedName ?: return@let
            if (!qualifiedName.startsWith("java.") && !qualifiedName.startsWith("javax.")) return resolved
        }
        // 回退：返回类型的父类/接口泛型参数（UserMapper extends BaseMapper<User> → User）
        val resolved = classType.resolve() ?: return null
        for (superType in resolved.superTypes) {
            val params = superType.parameters
            val candidate = params.lastOrNull() as? PsiClassType ?: continue
            val entity = candidate.resolve() ?: continue
            val qualifiedName = entity.qualifiedName ?: continue
            if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) continue
            return entity
        }
        return null
    }

    /**
     * T-M22-04（ActiveRecord 识别）：从 qualifier 变量类型解析实体——`entity.insert()` 的
     * qualifier 变量类型即实体本身（AbstractModel 子类）；[isLikelyEntity] 限定避免
     * `service.getById(id)` 等 service/mapper 变量被误判为实体（service 类名无 Entity 后缀、
     * 无 @TableName → 返回 null，保持原步骤 3 语义）。
     */
    private fun resolveQualifierEntityClass(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
        val variable = qualifier.resolve() as? PsiVariable ?: return null
        val varType = variable.type as? PsiClassType ?: return null
        val resolved = varType.resolve() ?: return null
        return resolved.takeIf { isLikelyEntity(it) }
    }

    /**
     * 实体类启发式判定（M19.6）：仅接受 **@TableName 注解实体**（真实实体，兼容无 Entity 后缀命名
     * 如 SpWorkflow）或**类名含 "Entity"** 的类；排除 JDK 基础类型（java./javax. 前缀）——
     * 避免 getById/removeById 的 Long/String 等实参类型被误当实体解析。
     */
    private fun isLikelyEntity(psiClass: PsiClass): Boolean {
        val qualifiedName = psiClass.qualifiedName ?: return false
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return false
        if (psiClass.annotations.any { (it.qualifiedName ?: "").endsWith(".TableName") }) return true
        val name = psiClass.name ?: return false
        return "Entity" in name
    }

    /**
     * 从 service qualifier 解析实体类（`XxxService extends IService<Entity>` / `ServiceImpl<Mapper, Entity>`）：
     * 变量类型自身泛型参数优先（`XxxService<Entity>`），否则沿父类型链取「最后一个类型参数」
     * （T-Mxx 放宽：不再要求 Entity 后缀，由 [isLikelyEntity] 排除 JDK 基础类型防误判）。
     */
    private fun resolveServiceEntityClass(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
        val variable = qualifier.resolve() as? PsiVariable ?: return null
        val varType = variable.type as? PsiClassType ?: return null
        // 变量类型自身泛型（XxxService<Entity>）——T-M22-04：加 [isLikelyEntity] 限定
        // （selectAll 等常见方法名防误伤：`List<String> list.selectAll()` 的 String 不得解析为实体）
        (varType.parameters.firstOrNull() as? PsiClassType)?.resolve()?.takeIf { isLikelyEntity(it) }?.let { return it }
        // 父类/接口泛型参数（ServiceImpl<M, Entity> 的 Entity、IService<Entity> 的 Entity）
        val clazz = varType.resolve() ?: return null
        for (superType in clazz.superTypes) {
            val params = superType.parameters
            val candidate = params.lastOrNull() as? PsiClassType ?: continue
            val resolved = candidate.resolve() ?: continue
            // T-Mxx（2026-08-19）：放宽后缀限定——IService<User> / ServiceImpl<M, User>
            // 的泛型参数在 service + 白名单方法名上下文下高置信度指向实体；
            // 仅排除 java./javax. 包（防基础类型误判），不再要求 Entity 后缀或 @TableName
            val qualifiedName = resolved.qualifiedName ?: continue
            if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) continue
            return resolved
        }
        return null
    }

    // ------------------------------------------------------------------
    // SQL 模板构建（T-M23-01：逻辑删除/软删除；T-M23-02：参数登记）
    // ------------------------------------------------------------------

    /**
     * UPDATE 模板：非主键列 SET + 主键 WHERE（无主键标记时用首列；updateById 同 update-strategy: always 全列语义）。
     * T-M22-02（@Version 乐观锁）：实体含 @Version 字段时（对齐 MP OptimisticLockerInnerInterceptor）——
     * version 列不进实体 SET（`version = ?` 由 `version = version + 1` 取代），
     * WHERE 末尾追加 `AND version = ?`；参数组说明同步追加 version（buildParamGroups 顺序与 SQL 一致）。
     * T-M23-04：SET 列为可勾选（默认全勾选，用户可按需取消——近似 NOT_NULL"只更新需要修改的字段"），
     * WHERE 主键/@Version 为固定列（无勾选）；初始 SQL 与参数登记出自 [BatchModel.renderSql]/ordered*。
     */
    private fun buildUpdateBatch(call: PsiMethodCallExpression, methodName: String, table: TableInfo): BatchBuild? {
        // T-WriteFamily（R4）：`BaseMapper.update(T entity, Wrapper<T> updateWrapper)` 的 null-wrapper 形态
        // （`update(entity, null)` / `update(entity, (Wrapper<X>) null)`）——全列 SET 且**无任何 WHERE**。
        // 不复用 [modelBuild]：[BatchModel.renderSql] 的 UPDATE 分支把 fixedColumns 硬编码成 WHERE
        // （BatchModel.kt UPDATE 分支），复用会凭空多出 `WHERE id = ?` 与版本条件。
        if (methodName == "update") return buildNoWhereUpdateBatch(call, table)
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val versionColumn = table.versionColumn
        val setColumns = table.columns.filter { it.columnName != idColumn.columnName && it.columnName != versionColumn }
        val prefix = entityPrefix(call)
        val toggleColumns = batchColumns(prefix, table, setColumns) { true }
        val fixedColumns = listOf(
            BatchColumn(idColumn.columnName, idSource(prefix, idColumn.columnName), ParamQuote.decide(idColumn.fieldType), checked = true),
        ) + listOfNotNull(versionColumn?.let { version ->
            val versionField = table.columns.firstOrNull { it.columnName == version }
            BatchColumn(version, "$prefix.$version", ParamQuote.decide(versionField?.fieldType), checked = true)
        })
        val paramGroups = buildParamGroups(setColumns.map { it.columnName } + idColumn.columnName + listOfNotNull(versionColumn))
        return modelBuild(BatchModel(BatchKind.UPDATE, table.tableName, toggleColumns, fixedColumns, versionColumn), call).copy(paramGroups = paramGroups)
    }

    /**
     * T-WriteFamily（R4）：`update(entity, null)` 的**无 WHERE** 更新模板 —— `UPDATE 表 SET 实体列 = ?`。
     *
     * 语义（对齐 MP `SqlMethod.UPDATE` + `sqlWhereEntityWrapper`：wrapper 为 null 时该片段不产生 WHERE）：
     *  - SET 列与 [buildUpdateBatch]（updateById）同口径：排除主键列与 @Version 列；
     *  - **不生成任何 WHERE**——@Version 乐观锁条件也不得生成（没有 wrapper 就没有可定位的行）；
     *  - 不复用 [BatchModel]（其 UPDATE 渲染把主键硬编码成 WHERE）→ `batchModel = null`，面板不提供列勾选；
     *  - 附 [NO_WHERE_UPDATE_WARNING] 全表更新警示。
     *
     * 非「第二参为 null 字面量」的形态（`update(entity, wrapperVar)` / 单参 `update(Wrapper)` / 无参）
     * 一律返回 null：前者由 Wrapper 链路径承载，后者 SQL 本身非法（`UPDATE t SET` 无赋值）。
     */
    private fun buildNoWhereUpdateBatch(call: PsiMethodCallExpression, table: TableInfo): BatchBuild? {
        if (!isNullWrapperShape(call)) return null
        val dialect = currentDialect(call.project)
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val versionColumn = table.versionColumn
        val setColumns = table.columns.filter {
            it.columnName != idColumn.columnName && it.columnName != versionColumn
        }
        // 只有主键（+版本列）的实体没有可赋值列 → UPDATE 无 SET，SQL 非法，如实无预览
        if (setColumns.isEmpty()) return null
        val prefix = entityPrefix(call)
        val sql = "UPDATE ${dialect.quoteIfKeyword(table.tableName)}\nSET " +
            setColumns.joinToString(", ") { "${dialect.quoteIfKeyword(it.columnName)} = ?" }
        return BatchBuild(
            sql = sql,
            params = setColumns.map {
                ParamEntry(idSource(prefix, it.columnName), ParamHint(quote = ParamQuote.decide(it.fieldType)))
            },
            warning = NO_WHERE_UPDATE_WARNING,
        )
    }

    /**
     * INSERT 模板：全列 + VALUES。
     * `insertBatchSomeColumn`（自定义注入器，X09）：排除 `FieldFill.UPDATE` 填充列（update 侧维护，插入不参与）；
     * `save(entity)` 单实体同模板（F-15 语义一致）。
     * T-M23-04：全列可勾选，**主键默认不勾选**（`@TableId(type = IdType.AUTO)` 自增场景，对齐 MP INSERT 不含主键列）；
     * 初始 SQL 与参数登记出自 [BatchModel.renderSql]/ordered*。
     *
     * T-M2（@KeySequence）：实体类标注 `@KeySequence(value = "seq")`（主键取序列，Oracle/PG/DM/KingBase
     * 方言常见）且当前方言为 [KEY_SEQUENCE_DIALECT_NAMES] 时——主键列保持 `?` 占位 + SQL 尾部追加
     * 「主键取序列」注释提示。**边界（文档化，不做真实渲染）**：不渲染 `seq.nextval` 真实取值表达式
     * （MP KeyGenerator 运行时行为，静态按占位呈现）；序列名来自 [EntityResolver.keySequenceValue]；
     * 面板勾选重建 SQL 时注释行不保留（BatchModel 纯渲染无注释通道，参数占位不受影响）。
     */
    private fun buildInsertBatch(
        call: PsiMethodCallExpression,
        methodName: String,
        table: TableInfo,
        entityClass: PsiClass? = null,
        injectTenantLine: Boolean = true,
    ): BatchBuild {
        val columns = if (methodName == "insertBatchSomeColumn") {
            table.columns.filter { it.fill != "UPDATE" }
        } else {
            table.columns
        }
        val prefix = entityPrefix(call)
        var toggleColumns = batchColumns(prefix, table, columns) { col -> !(col.isId && col.idTypeAuto) }
        // T-TenantLine：INSERT 列清单追加租户列（固定列、默认勾选、恒参与——对齐 MP INSERT 含租户列语义）；
        // 实体已有 tenant 列（字段）时不重复追加（toggleColumns.none 检测去重）
        val tenantColumn = if (injectTenantLine) {
            PluginSettings.getInstance().state.tenantColumnName.ifBlank { DEFAULT_TENANT_COLUMN }
        } else {
            LOG.info("buildInsertBatch: SKIP injectTenantLine=false, method=$methodName, table=${table.tableName}")
            null
        }
        if (tenantColumn != null) {
            if (toggleColumns.none { it.columnName == tenantColumn }) {
                LOG.info("buildInsertBatch: INJECT col=$tenantColumn, method=$methodName, table=${table.tableName}")
                toggleColumns = toggleColumns + BatchColumn(
                    columnName = tenantColumn,
                    source = "$prefix.$tenantColumn",
                    quote = null,  // tenant_id 类型未知，面板走数字启发式
                    checked = true,
                )
            } else {
                LOG.info("buildInsertBatch: DEDUP col=$tenantColumn already in entity columns, method=$methodName")
            }
        }
        val paramGroups = buildParamGroups(columns.map { it.columnName })
        val build = modelBuild(BatchModel(BatchKind.INSERT, table.tableName, toggleColumns), call).copy(paramGroups = paramGroups)
        // T-M2：@KeySequence 序列方言注释提示（主键列占位不变，仅追加提示行）
        val keySequence = entityClass?.let { EntityResolver.keySequenceValue(it) }
            ?: return build
        val dialect = currentDialect(call.project)
        if (dialect.name !in KEY_SEQUENCE_DIALECT_NAMES) return build
        return build.copy(
            sql = build.sql + "\n-- @KeySequence: 主键取序列 $keySequence（${dialect.name} 方言，运行时由 MP KeyGenerator 取 nextval）",
        )
    }

    /**
     * DELETE 模板（T-M23-01）：主键 IN 列表（removeByIds/deleteByIds）/ 主键等值（removeById，U11；deleteById，T-M22-04）。
     *
     * **有 @TableLogic（且逻辑删除开关开启）→ 软删除**（用户确认：删除操作实际是修改操作）：
     * `UPDATE 表 SET deleted = 1 WHERE 主键 ... AND deleted = 0`（对齐 MP LogicDeleteSqlInjector）；
     * 无 @TableLogic → 物理 DELETE（存量行为）。
     * T-M23-02：主键等值实参可静态求值 → 内联（`removeById(1L)` → `WHERE id = 1`）；不可求值 → `?` + 参数行。
     */
    private fun buildDeleteBatch(
        call: PsiMethodCallExpression,
        methodName: String,
        table: TableInfo,
        injectLogicDelete: Boolean,
        resolveVariables: Boolean,
    ): BatchBuild? {
        // T-WriteFamily（R4）：`BaseMapper.delete(Wrapper)` / `IService.remove(Wrapper)` 的 null-wrapper 形态——
        // 无 WHERE（无逻辑删除 → DELETE FROM 表；有 @TableLogic → UPDATE 表 SET deleted = 1 WHERE deleted = 0，
        // 对齐 MP `SqlMethod.LOGIC_DELETE` + TableInfo.getLogicDeleteSql）；附全表删除警示。
        // 非 null 字面量实参（wrapper 变量 / 内联链 / 其它对象）→ null：由 Wrapper 链路径承载，绝不退化成无 WHERE。
        if (methodName == "delete" || methodName == "remove") return buildNoWhereDeleteBatch(call, table, injectLogicDelete)
        val dialect = currentDialect(call.project)
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val logicColumn = if (injectLogicDelete) table.logicDeleteColumn else null
        val singleId = methodName in SINGLE_ID_DELETE_METHODS
        val (idWhere, idParams) = if (singleId) {
            renderIdCondition(call, idColumn, resolveVariables, dialect)
        } else {
            "${dialect.quoteIfKeyword(idColumn.columnName)} IN (?, ?, ...)" to emptyList()
        }
        return if (logicColumn != null) {
            // 软删除：删除转 UPDATE（deleted = 1 为字面量，不进参数行）；T-M23.5 表名/列名按方言引用
            val logic = dialect.quoteIfKeyword(logicColumn)
            BatchBuild(
                sql = "UPDATE ${dialect.quoteIfKeyword(table.tableName)}\nSET $logic = 1\nWHERE $idWhere AND $logic = 0",
                params = idParams,
            )
        } else {
            BatchBuild(
                sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $idWhere",
                params = idParams,
            )
        }
    }

    /**
     * T-WriteFamily（R4）：`delete(null)` / `remove(null)` 的**无 WHERE** 删除模板。
     *
     *  - 无 @TableLogic（或逻辑删除开关关闭）→ `DELETE FROM 表`（对齐 MP `SqlMethod.DELETE`：
     *    wrapper 为 null 时 `sqlWhereEntityWrapper` 不产生 WHERE）；
     *  - 有 @TableLogic → `UPDATE 表 SET deleted = 1 WHERE deleted = 0`（对齐 `SqlMethod.LOGIC_DELETE`：
     *    逻辑删除片段**无条件**追加 `TableInfo.getLogicDeleteSql`，即 `deleted = 0`）——
     *    该 WHERE 只是软删除的记录范围，不是行定位条件，仍按全表写操作警示。
     *
     * 形态门 = [isNullWrapperShape]（恰好 1 个 null 字面量实参，解一层 cast）；其余形态返回 null。
     */
    private fun buildNoWhereDeleteBatch(
        call: PsiMethodCallExpression,
        table: TableInfo,
        injectLogicDelete: Boolean,
    ): BatchBuild? {
        if (!isNullWrapperShape(call)) return null
        val dialect = currentDialect(call.project)
        val tableName = dialect.quoteIfKeyword(table.tableName)
        val logicColumn = if (injectLogicDelete) table.logicDeleteColumn else null
        return if (logicColumn != null) {
            val logic = dialect.quoteIfKeyword(logicColumn)
            BatchBuild(
                sql = "UPDATE $tableName\nSET $logic = 1\nWHERE $logic = 0",
                warning = NO_WHERE_DELETE_WARNING,
            )
        } else {
            BatchBuild(sql = "DELETE FROM $tableName", warning = NO_WHERE_DELETE_WARNING)
        }
    }

    /**
     * SELECT 模板（M19.5，实体单条查询，P1-02/P2-08 同点合并）：
     * 全列清单 + 主键等值（getById/getOptById，T-M20-10 并入）/ 主键 IN（listByIds/selectByIds）。
     * T-M22-04：ActiveRecord selectAll() 全列查询——**无 WHERE**（方法名分支）。
     * T-M23-01：实体含 @TableLogic（开关开启）→ WHERE 追加 `AND deleted = 0`（对齐 MP 内置 SQL）；
     * selectAll 无 WHERE 时输出 `WHERE deleted = 0`。
     * T-M23-02：主键等值实参可静态求值 → 内联；不可求值 → `?` + 参数行。
     */
    private fun buildSelectBatch(
        call: PsiMethodCallExpression,
        methodName: String,
        table: TableInfo,
        injectLogicDelete: Boolean,
        resolveVariables: Boolean,
    ): BatchBuild {
        val dialect = currentDialect(call.project)
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        // T-M23.5：全列清单/表名/条件列名按方言引用（用户实测：批量 SELECT 未处理关键字列）
        val selectColumns = table.columns.joinToString(", ") { dialect.quoteIfKeyword(it.columnName) }
        val logicCond = if (injectLogicDelete) table.logicDeleteColumn?.let { "${dialect.quoteIfKeyword(it)} = 0" } else null
        return when {
            methodName == "selectAll" || methodName in FULL_COLUMN_SELECT_METHODS -> {
                val where = logicCond?.let { "\nWHERE $it" } ?: ""
                BatchBuild("SELECT $selectColumns\nFROM ${dialect.quoteIfKeyword(table.tableName)}$where")
            }
            methodName == "selectCount" -> {
                val where = logicCond?.let { "WHERE $it" } ?: ""
                BatchBuild("SELECT COUNT(*)\nFROM ${dialect.quoteIfKeyword(table.tableName)}${if (where.isNotEmpty()) "\n$where" else ""}")
            }
            methodName in SINGLE_ID_SELECT_METHODS -> {
                val (idWhere, idParams) = renderIdCondition(call, idColumn, resolveVariables, dialect)
                val where = idWhere + (logicCond?.let { " AND $it" } ?: "")
                BatchBuild("SELECT $selectColumns\nFROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where", idParams)
            }
            else -> {
                val where = "${dialect.quoteIfKeyword(idColumn.columnName)} IN (?, ?, ...)" + (logicCond?.let { " AND $it" } ?: "")
                BatchBuild("SELECT $selectColumns\nFROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where")
            }
        }
    }

    /**
     * T-M20-07（Map 条件消费）：`removeByMap(Map)` / `deleteByMap(Map)` / `selectByMap(Map)` / `listByMap(Map)`
     * 的 Map 等值条件模板——Map 键值均为运行时数据，列名与取值静态不可得，WHERE 用可读占位文本
     * `col = ? AND col = ? ...`；删除/查询由方法名前缀区分。参数组说明置空（Map 键不可静态枚举）。
     * T-M23-01：Map 删除在有 @TableLogic 时同为软删除（`UPDATE 表 SET deleted = 1 WHERE <Map 条件> AND deleted = 0`）；
     * SELECT 同理追加 `AND deleted = 0`。
     */
    private fun buildMapConditionBatch(call: PsiMethodCallExpression, methodName: String, table: TableInfo, injectLogicDelete: Boolean): BatchBuild {
        val dialect = currentDialect(call.project)
        val isDelete = methodName.startsWith("remove") || methodName.startsWith("delete")
        val logicColumn = if (injectLogicDelete) table.logicDeleteColumn else null
        val condition = "<Map 等值条件: col = ? AND col = ? ...>" + (logicColumn?.let { " AND ${dialect.quoteIfKeyword(it)} = 0" } ?: "")
        val sql = when {
            isDelete && logicColumn != null ->
                "UPDATE ${dialect.quoteIfKeyword(table.tableName)}\nSET ${dialect.quoteIfKeyword(logicColumn)} = 1\nWHERE $condition"
            isDelete ->
                "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $condition"
            else ->
                "SELECT ${table.columns.joinToString(", ") { dialect.quoteIfKeyword(it.columnName) }}\nFROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $condition"
        }
        return BatchBuild(sql)
    }

    /**
     * UPSERT 模板（saveOrUpdate / saveOrUpdateBatch，U12，T-M15-08；insertOrUpdate，T-M20-08）：
     * INSERT + 方言冲突子句（T-M43——PG 系 `ON CONFLICT`、MySQL 系 `ON DUPLICATE KEY UPDATE`、
     * Oracle/SQL Server/DM 降级纯 INSERT，由 [BatchModel.renderSql] 按 [cn.song.mybatisplus.visualizer.sql.Dialect.renderUpsert] 渲染）。
     * T-M23.2（用户实测：saveOrUpdate 主键应为可选）：主键纳入 toggleColumns（默认勾选，完整 upsert；
     * 取消勾选 → 纯 INSERT 新记录路径——[BatchModel.conflictColumn] 判活跃态）；
     * 初始 SQL 与参数登记出自 [BatchModel.renderSql]/ordered*。
     */
    private fun buildUpsertBatch(call: PsiMethodCallExpression, methodName: String, table: TableInfo): BatchBuild {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val prefix = entityPrefix(call)
        val toggleColumns = batchColumns(prefix, table, table.columns) { true }
        val paramGroups = buildParamGroups(table.columns.map { it.columnName })
        return modelBuild(
            BatchModel(BatchKind.UPSERT, table.tableName, toggleColumns, conflictColumn = idColumn.columnName), call,
        ).copy(paramGroups = paramGroups)
    }

    // ------------------------------------------------------------------
    // T-M23-02：参数登记辅助
    // ------------------------------------------------------------------

    /**
     * 单主键方法的 `?` 渲染与参数登记：
     *  - 实参可静态求值（字面量/可追踪变量，[ExpressionEvaluator]；受 resolveVariables 开关控制）→
     *    **内联**进 SQL（`removeById(1L)` → `id = 1`，无参数行）；
     *  - 运行时值 → `?` + 参数行（source=实参表达式文本，如 `id` / `dto.getId()`）；
     *  - 无实参（ActiveRecord `entity.getById()` / `entity.deleteById()` 形态）→ `?` + `实体.主键列` 参数行。
     *
     * @return (WHERE 片段, 参数登记)；Db.getById(Class, id) 两参形态 id 取第二个实参
     */
    private fun renderIdCondition(
        call: PsiMethodCallExpression,
        idColumn: ColumnInfo,
        resolveVariables: Boolean,
        dialect: Dialect,
    ): Pair<String, List<ParamEntry>> {
        val args = call.argumentList.expressions
        val idExpr = if (args.size > 1) args[1] else args.firstOrNull()
        val idName = dialect.quoteIfKeyword(idColumn.columnName)
        if (idExpr != null) {
            val value = if (resolveVariables) ExpressionEvaluator.evaluate(idExpr) else SqlValue.Placeholder(idExpr.text)
            return when (value) {
                is SqlValue.Literal ->
                    "$idName = ${value.raw}" to emptyList()
                is SqlValue.Placeholder ->
                    "$idName = ?" to listOf(ParamEntry(value.source, ParamHint(quote = ParamQuote.decide(idColumn.fieldType))))
            }
        }
        // ActiveRecord 无参形态：id 来自实体字段
        return "$idName = ?" to listOf(
            ParamEntry(idSource(entityPrefix(call), idColumn.columnName), ParamHint(quote = ParamQuote.decide(idColumn.fieldType))),
        )
    }

    /**
     * 实体/集合参数前缀：首实参文本（`save(entity)` → `entity`、`saveBatch(list)` → `list`）；
     * 无实参（ActiveRecord `entity.insert()` 形态）→ qualifier 文本；均无 → 空串。
     */
    private fun entityPrefix(call: PsiMethodCallExpression): String {
        call.argumentList.expressions.firstOrNull()?.let { return it.text }
        return (call.methodExpression.qualifierExpression as? PsiReferenceExpression)?.text.orEmpty()
    }

    /** 实体字段参数来源：`实体变量.列名`；无前缀（ActiveRecord 形态）时仅列名。 */
    private fun idSource(prefix: String, columnName: String): String =
        if (prefix.isEmpty()) columnName else "$prefix.$columnName"

    /**
     * 参数组说明（docs/项目介绍.md 格式）：`-- 实际参数: [col=?, col=?] × N`。
     */
    private fun buildParamGroups(columns: List<String>): String {
        val group = columns.joinToString(", ") { "$it=?" }
        return "-- 实际参数: [$group] × N（每行一组，运行时值不可静态获取）"
    }

    /** 默认租户列名（T-TenantLine）：tenantColumnName 空白时回退此值。 */
    private const val DEFAULT_TENANT_COLUMN = "tenant_id"

    /**
     * T-M2：@KeySequence 序列提示生效的方言名集合（Oracle/DM/KingBase——序列取主键的常见国产/PL 方言；
     * PG 常用 serial/identity 序列形态不在本提示范围，MySQL/SQL Server 无序列语义）。
     */
    private val KEY_SEQUENCE_DIALECT_NAMES = setOf("Oracle", "DM（达梦）", "KingBase（人大金仓）")

    /** T-TenantLine 诊断日志：INSERT 列清单追加租户列决策。 */
    private val LOG = Logger.getInstance(BatchOperationConverter::class.java)

}
