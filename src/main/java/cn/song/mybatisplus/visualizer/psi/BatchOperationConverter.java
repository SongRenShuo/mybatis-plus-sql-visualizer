package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.BatchKind;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.BatchPreview;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 批量/单实体操作转换器（F-15，M6-01 + T-M15-08 扩展 + M19.5 实体单条 CRUD）：识别
 * {@code service.updateBatchById(list)} 等调用，生成 SQL 模板 + 参数组说明。
 *
 * <p>支持的批量方法：</p>
 * <ul>
 *  <li>{@code updateBatchById(list)} / {@code updateById(entity)}：UPDATE 表 SET 非主键列 = ? WHERE 主键 = ?</li>
 *  <li>{@code saveBatch(list)} / {@code insertBatchSomeColumn(list)} / {@code save(entity)}（M19.5 补注册，
 *      与注释一致化）/ {@code insert(list)} / {@code insert(entity)}（T-M20-08，BaseMapper 3.5.7+ 批量族，
 *      单实体与集合同 INSERT 模板）：INSERT INTO 表 (列...) VALUES (?, ...)</li>
 *  <li>{@code saveOrUpdate(entity)} / {@code saveOrUpdateBatch(list)}（U12，T-M15-08）/
 *      {@code insertOrUpdate(entity)}（T-M20-08）：INSERT + 方言 upsert（PG {@code ON CONFLICT}）</li>
 *  <li>{@code removeByIds(list)} / {@code removeById(id)} / {@code deleteByIds(ids)}（U11/P1-02，T-M15-08 + M19.5）/
 *      {@code removeBatchByIds(list)}（T-M20-10 核对补注册）/ {@code deleteById(id)}（T-M22-04，Mapper 单删与
 *      ActiveRecord 无参形态同主键等值模板）：DELETE FROM 表 WHERE 主键 IN/=；
 *      <b>T-M23-01（用户确认）</b>：实体含 @TableLogic 时删除操作实际是修改操作——转软删除
 *      {@code UPDATE 表 SET deleted = 1 WHERE ... AND deleted = 0}（对齐 MP LogicDeleteSqlInjector）；
 *      无 @TableLogic 维持物理 DELETE。</li>
 *  <li>{@code removeByMap(map)} / {@code deleteByMap(map)}（T-M20-07，MP/MPJ Map 条件删除）与
 *      {@code selectByMap(map)} / {@code listByMap(map)}（T-M20-07 Map 条件查询）：
 *      WHERE Map 等值条件占位（键值静态不可得，可读占位文本）</li>
 *  <li>{@code getById(id)} / {@code getOptById(id)}（T-M20-10，IService 3.5.15）/ {@code listByIds(ids)} /
 *      {@code selectByIds(ids)}（M19.5，实体单条查询，P2-08 同点合并）：SELECT 全列 FROM 表 WHERE 主键 =/IN；
 *      <b>T-M23-01</b>：实体含 @TableLogic 时 WHERE 追加 {@code AND deleted = 0}（对齐 MP 内置 SQL）</li>
 *  <li>{@code selectAll()}（T-M22-04，ActiveRecord）：SELECT 全列 FROM 表（无 WHERE；@TableLogic 时 WHERE deleted = 0）</li>
 * </ul>
 *
 * <p>实体类解析顺序：集合参数泛型（{@code List<Entity>}）→ 实体参数类型 → service qualifier 泛型
 * （{@code XxxService extends IService<Entity>} / {@code ServiceImpl<Mapper, Entity>}，getById/removeById 单参场景）
 * → qualifier 实体类型（T-M22-04，ActiveRecord {@code entity.insert()} 等无参形态）。
 * 运行时行值不可得，输出 {@code ?} 模板并附参数组说明（每行一组，字段顺序与 SQL 一致）。</p>
 *
 * <p>T-M23-02（参数面板）：单主键方法实参可静态求值（字面量/可追踪变量，{@link ExpressionEvaluator}）时
 * <b>内联</b>进 SQL（{@code removeById(1L)} → {@code WHERE id = 1}）；不可求值 → {@code ?} + 参数登记
 * （source=实参文本，面板参数行）；实体/集合方法每个 {@code ?} 登记 {@code 实体变量.列名}。</p>
 */
public final class BatchOperationConverter {

    private BatchOperationConverter() {}

    /** T-TenantLine 诊断日志：INSERT 列清单追加租户列决策。 */
    private static final Logger LOG = Logger.getInstance(BatchOperationConverter.class);

    /** 默认租户列名（T-TenantLine）：tenantColumnName 空白时回退此值。 */
    private static final String DEFAULT_TENANT_COLUMN = "tenant_id";

    /**
     * T-M2：@KeySequence 序列提示生效的方言名集合（Oracle/DM/KingBase——序列取主键的常见国产/PL 方言；
     * PG 常用 serial/identity 序列形态不在本提示范围，MySQL/SQL Server 无序列语义）。
     */
    private static final Set<String> KEY_SEQUENCE_DIALECT_NAMES =
        Set.of("Oracle", "DM（达梦）", "KingBase（人大金仓）");

    /** 批量方法 → SQL 模板生成策略。 */
    private enum Operation {
        UPDATE("UPDATE"),
        INSERT("INSERT"),
        DELETE("DELETE"),
        UPSERT("UPSERT"),
        SELECT("SELECT");

        @SuppressWarnings("unused")
        final String sqlPrefix;

        Operation(String sqlPrefix) {
            this.sqlPrefix = sqlPrefix;
        }
    }

    private static final Map<String, Operation> BATCH_OPERATIONS = createBatchOperations();

    private static Map<String, Operation> createBatchOperations() {
        Map<String, Operation> map = new LinkedHashMap<>();
        map.put("updateBatchById", Operation.UPDATE);
        map.put("updateById", Operation.UPDATE);
        map.put("saveBatch", Operation.INSERT);
        map.put("insertBatchSomeColumn", Operation.INSERT);
        // T-M20-08：BaseMapper 批量族（MP 3.5.7+）——insert(Collection)/insert(Collection, int)/insert(T)
        // 同名共用一个 INSERT 模板；insert 为通用 Java 方法名，非实体调用由 isLikelyEntity 挡掉
        map.put("insert", Operation.INSERT);
        map.put("save", Operation.INSERT);
        map.put("saveOrUpdate", Operation.UPSERT);
        // T-M20-08：BaseMapper.insertOrUpdate（全重载同名，BiPredicate 参数忽略）与 saveOrUpdate 同 upsert 模板
        map.put("insertOrUpdate", Operation.UPSERT);
        map.put("saveOrUpdateBatch", Operation.UPSERT);
        map.put("removeByIds", Operation.DELETE);
        map.put("removeById", Operation.DELETE);
        map.put("deleteByIds", Operation.DELETE);
        // T-M22-04：Mapper.deleteById(id) 与 ActiveRecord 无参形态（entity.deleteById()）同模板——主键等值
        map.put("deleteById", Operation.DELETE);
        // T-WriteFamily（R4）：空 Wrapper 写族——`BaseMapper.delete(Wrapper)`（3.5.17 javap 实证，参数可 null）/
        // `BaseMapper.update(T, Wrapper)` / `IService.remove(Wrapper)`。三者只认「Wrapper 实参为 null 字面量」这一
        // 精确形态（isNullWrapperShape），非空 Wrapper 由 Wrapper 链锚点承载、本注册表不接管（return null）。
        // 名字同时是 Wrapper 工厂/链终结点（delete/update），锚点侧另有接收者守卫（hasMpCrudReceiver）。
        map.put("delete", Operation.DELETE);
        map.put("update", Operation.UPDATE);
        map.put("remove", Operation.DELETE);
        // T-M20-10 核对：IService 3.5.15 便捷重载 removeBatchByIds(Collection)——核对时补注册（与 removeByIds 同模板）
        map.put("removeBatchByIds", Operation.DELETE);
        // T-M20-07：Map 条件消费（MP/MPJ）——DELETE/SELECT WHERE Map 等值条件（独立 builder）
        map.put("removeByMap", Operation.DELETE);
        map.put("deleteByMap", Operation.DELETE);
        map.put("getById", Operation.SELECT);
        // T-M20-10：IService 3.5.15 getOptById(id) → 与 getById 同主键等值模板
        map.put("getOptById", Operation.SELECT);
        // T-M22-04：ActiveRecord（AbstractModel）selectAll() 全列查询——无 WHERE
        map.put("selectAll", Operation.SELECT);
        map.put("listByIds", Operation.SELECT);
        map.put("selectByIds", Operation.SELECT);
        map.put("selectByMap", Operation.SELECT);
        map.put("listByMap", Operation.SELECT);
        // T-Mxx：BaseMapper 核心方法（MP 3.5.x）——补全单数/列表/计数
        map.put("selectById", Operation.SELECT);
        map.put("selectList", Operation.SELECT);
        map.put("selectMaps", Operation.SELECT);
        map.put("selectObjs", Operation.SELECT);
        map.put("selectCount", Operation.SELECT);
        // T-MapResultVo：MPJ Map 响应族（JoinMapper.selectJoinMaps/selectJoinMapsPage，1.5.8 javap 实证）
        map.put("selectJoinMaps", Operation.SELECT);
        map.put("selectJoinMapsPage", Operation.SELECT);
        return map;
    }

    /** T-M20-07：Map 等值条件操作方法名（独立 builder，不并入主键等值/IN 分支）。 */
    private static final Set<String> MAP_CONDITION_METHODS =
        Set.of("removeByMap", "deleteByMap", "selectByMap", "listByMap");

    /** T-M20-10：主键等值单条查询方法名（getById / getOptById，其余批量查询走主键 IN）。 */
    private static final Set<String> SINGLE_ID_SELECT_METHODS = Set.of("getById", "getOptById", "selectById");

    /** T-Mxx：全列查询无 WHERE 方法名（selectList / selectMaps / selectObjs——BaseMapper 列表查询）。 */
    private static final Set<String> FULL_COLUMN_SELECT_METHODS = Set.of("selectList", "selectMaps", "selectObjs");

    /** T-M22-04：主键等值删除方法名（removeById / deleteById，其余走主键 IN）。 */
    private static final Set<String> SINGLE_ID_DELETE_METHODS = Set.of("removeById", "deleteById");

    /**
     * 批量操作方法名集合（M10 起供 {@link WrapperAnchorDetector} gutter 锚点复用）。
     */
    public static Set<String> getBatchOperationMethodNames() {
        return BATCH_OPERATIONS.keySet();
    }

    /**
     * T-WriteFamily（R4）：只认「Wrapper 实参为 null 字面量」的写族方法名
     * （{@code delete} / {@code remove} 单参 null；{@code update} 两参第二参 null）。
     *
     * <p>与其余批量方法名的差别：其余名字的实参是实体/集合/主键，方法名本身即足够；
     * 这三个名字的 Wrapper 实参才是语句载体，<b>非空 Wrapper 必须让位给链锚点</b>，
     * 因此锚点侧（{@link WrapperAnchorDetector} 场景 D）与选择区候选侧（{@code WrapperLocator.isSqlCandidate}）
     * 都要叠加 {@link #isNoWrapperConsumingCall} 的精确形态 + 接收者判定。</p>
     */
    public static final Set<String> NULL_WRAPPER_WRITE_METHOD_NAMES = Set.of("delete", "update", "remove");

    /** T-WriteFamily：无 WHERE 全表删除警示（与 JPA BlockAttack / EasyQuery 全表警示同文案口径）。 */
    public static final String NO_WHERE_DELETE_WARNING = "⚠ 无 WHERE 条件，将删除全表数据！";

    /** T-WriteFamily：无 WHERE 全表更新警示。 */
    public static final String NO_WHERE_UPDATE_WARNING = "⚠ 无 WHERE 条件，将更新全表数据！";

    /** T-WriteFamily：MP CRUD 基类 FQN 前缀（优先按 FQN 判定）。 */
    private static final String MP_CRUD_FQN_PREFIX = "com.baomidou.mybatisplus";

    /**
     * T-WriteFamily：MP CRUD 基类简名集合（{@code BaseMapper} / {@code IService} / {@code ServiceImpl} /
     * {@code AbstractModel}）——类型父链（含父链的父链）命中任一即视为 MP 写族 receiver。
     * 简名兜底用于「依赖不在索引里」的工程（含测试桩）；口径见计划 P-5（同名自有基类风险已知并接受）。
     */
    private static final Set<String> MP_CRUD_BASE_SIMPLE_NAMES =
        Set.of("BaseMapper", "IService", "ServiceImpl", "AbstractModel");

    /** T-WriteFamily：类型父链遍历深度上限（{@code XxxServiceImpl → ServiceImpl → IService} 两跳够用，留一档余量）。 */
    private static final int MP_CRUD_SUPER_TYPE_DEPTH = 3;

    /**
     * T-M23-02：单个 {@code ?} 的参数登记（source + 回填引号提示，供面板参数行）。
     */
    private static final class ParamEntry {
        final String source;
        final ParamHint hint;

        ParamEntry(String source, ParamHint hint) {
            this.source = source;
            this.hint = hint;
        }
    }

    /**
     * T-M23-02：批量构建结果（SQL 模板 + 按 {@code ?} 出现顺序的参数登记 + 参数组说明）。
     * T-M23-04：INSERT/UPDATE/UPSERT 携带 {@link BatchModel}（面板勾选重建 SQL 的唯一结构源）。
     * T-WriteFamily（R4）：无 WHERE 写操作警示（delete/remove/update 的全表形态）。
     */
    private static final class BatchBuild {
        final String sql;
        final List<ParamEntry> params;
        final String paramGroups;
        final BatchModel model;
        final String warning;

        BatchBuild(String sql) {
            this(sql, List.of(), null, null, null);
        }

        BatchBuild(String sql, String warning) {
            this(sql, List.of(), null, null, warning);
        }

        BatchBuild(String sql, List<ParamEntry> params) {
            this(sql, params, null, null, null);
        }

        BatchBuild(String sql, List<ParamEntry> params, String warning) {
            this(sql, params, null, null, warning);
        }

        BatchBuild(String sql, List<ParamEntry> params, String paramGroups, BatchModel model) {
            this(sql, params, paramGroups, model, null);
        }

        BatchBuild(String sql, List<ParamEntry> params, String paramGroups, BatchModel model, String warning) {
            this.sql = sql;
            this.params = params;
            this.paramGroups = paramGroups;
            this.model = model;
            this.warning = warning;
        }

        BatchBuild withParamGroups(String groups) {
            return new BatchBuild(sql, params, groups, model, warning);
        }

        BatchBuild withSql(String newSql) {
            return new BatchBuild(newSql, params, paramGroups, model, warning);
        }
    }

    /**
     * 批量操作方法调用解析结果（T-M2）：表信息 + 实体 PsiClass（@KeySequence 等类级注解读取用；
     * 实体来自泛型推断等无具体 PsiClass 的路径为 null）。
     */
    private static final class ResolvedTarget {
        final TableInfo table;
        final com.intellij.psi.PsiClass entityClass;

        ResolvedTarget(TableInfo table, com.intellij.psi.PsiClass entityClass) {
            this.table = table;
            this.entityClass = entityClass;
        }
    }

    /**
     * T-M23-04：由列清单构造勾选列（source = {@code 实体前缀.列名}，hint 按列类型引号判定）。
     *
     * O-52 订正（独立复核 major⑭）：本链是<b>全仓第三条产出 INSERT 的链</b>（MP {@code saveBatch}/{@code insertBatch}
     * 等 PSI 批量链），此前只给 {@code CrudSqlGenerator} 一条链接线了 {@code autoGenerated}——缺这个标记时
     * {@code BatchModel.renderSql} 的 INSERT 分支判 {@code active.none { it.autoGenerated }} 为真，于是用户在
     * 预览面板<b>勾回自增列</b>后，SQL Server 上仍产出无包装的 INSERT、依旧报 544。取值口径与实体
     * CRUD 链同源（{@code GenerateCrudSqlFromEntityAction} 的 {@code autoGenerated = col.idTypeAuto}）。
     */
    private static List<BatchColumn> batchColumns(
        String prefix,
        TableInfo table,
        List<ColumnInfo> columns,
        Predicate<ColumnInfo> checked
    ) {
        List<BatchColumn> result = new ArrayList<>();
        for (ColumnInfo col : columns) {
            result.add(new BatchColumn(
                col.getColumnName(),
                idSource(prefix, col.getColumnName()),
                ParamQuote.decide(col.getFieldType()),
                checked.test(col),
                col.isIdTypeAuto(),
                col.getFill(),
                ParamQuote.isTemporal(col.getFieldType())));
        }
        return result;
    }

    /**
     * T-M23-04：模型驱动的批量构建（初始 SQL 与参数登记均出自 {@link BatchModel#renderSql}/ordered*，
     * 与面板勾选重建共用唯一渲染源）。
     * T-M23.4：注入当前方言（关键字标识符引用——保留字列名按方言符号包裹；设置服务不可用时兜底 MySQL）。
     */
    private static BatchBuild modelBuild(BatchModel model, PsiMethodCallExpression call) {
        BatchModel withDialect = model.copy(PsiDialect.currentDialect(call.getProject()));
        Set<String> initialActive = new LinkedHashSet<>();
        for (BatchColumn column : withDialect.getToggleColumns()) {
            if (column.isChecked()) initialActive.add(column.getColumnName());
        }
        for (BatchColumn column : withDialect.getFixedColumns()) {
            initialActive.add(column.getColumnName());
        }
        List<ParamEntry> params = new ArrayList<>();
        for (BatchColumn column : withDialect.orderedColumns(initialActive)) {
            params.add(new ParamEntry(column.getSource(), new ParamHint(column.getQuote(), null, false, column.isTemporal())));
        }
        return new BatchBuild(withDialect.renderSql(initialActive), params, null, withDialect);
    }

    /**
     * 转换批量/单实体方法调用。
     *
     * @param call              service 方法调用表达式
     * @param injectLogicDelete 逻辑删除注入开关（T-M23-01，settings.injectLogicDelete）：
     *                          关闭时 SELECT 不追加 {@code deleted = 0}、删除不转软删除
     * @param resolveVariables  变量求值开关（T-M23-02，settings.replaceParams）：false 时跳过
     *                          实参静态求值，一律 {@code ?} + 参数行（对齐 Wrapper 路径 ArgResolver）
     * @param injectTenantLine  租户列注入开关（T-TenantLine，INSERT 列清单追加租户列）
     * @return 批量预览；方法不匹配或实体无法解析时返回 null
     */
    public static BatchPreview convert(
        PsiMethodCallExpression call,
        boolean injectLogicDelete,
        boolean resolveVariables,
        boolean injectTenantLine
    ) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;
        Operation operation = BATCH_OPERATIONS.get(methodName);
        if (operation == null) return null;
        ResolvedTarget target = resolveEntityTable(call);
        if (target == null) return null;
        TableInfo table = target.table;
        // T-M20-07：Map 等值条件方法独立分支（键值静态不可得 → 占位文本；避免并入主键等值/IN 分支）
        // T-WriteFamily（R4）：空 Wrapper 写族在各自 builder 内按「null 字面量」精确判定，非该形态返回 null
        // （绝不退化成无 WHERE）→ 本条链路整体 return null，交回 Wrapper 路径 / 如实无预览
        BatchBuild build;
        if (MAP_CONDITION_METHODS.contains(methodName)) {
            build = buildMapConditionBatch(call, methodName, table, injectLogicDelete);
        } else {
            switch (operation) {
                case UPDATE:
                    build = buildUpdateBatch(call, methodName, table);
                    break;
                case INSERT:
                    build = buildInsertBatch(call, methodName, table, target.entityClass, injectTenantLine);
                    break;
                case DELETE:
                    build = buildDeleteBatch(call, methodName, table, injectLogicDelete, resolveVariables);
                    break;
                case UPSERT:
                    build = buildUpsertBatch(call, methodName, table);
                    break;
                case SELECT:
                default:
                    build = buildSelectBatch(call, methodName, table, injectLogicDelete, resolveVariables);
                    break;
            }
        }
        if (build == null) return null;
        List<String> sources = new ArrayList<>();
        List<ParamHint> hints = new ArrayList<>();
        for (ParamEntry entry : build.params) {
            sources.add(entry.source);
            hints.add(entry.hint);
        }
        return new BatchPreview(methodName, table.getTableName(), build.sql, build.paramGroups,
            sources, hints, build.model, build.warning);
    }

    // ------------------------------------------------------------------
    // T-WriteFamily（R4）：空 Wrapper 写族的精确形态 + 接收者守卫
    // ------------------------------------------------------------------

    /**
     * 调用是否为批量/写族候选（{@code WrapperLocator.isSqlCandidate} 选择区扫描用）。
     *
     * <p>普通批量方法名（{@code getById} / {@code save} / {@code removeById} …）只要在注册表内即算候选
     * （存量行为）；T-WriteFamily 新增的 {@link #NULL_WRAPPER_WRITE_METHOD_NAMES} 必须叠加
     * {@link #isNoWrapperConsumingCall}（空 Wrapper 精确形态 + MP receiver），否则
     * {@code redisTemplate.delete(null)}、{@code list.remove(0)} 之类会进多 SQL 选择列表。</p>
     */
    public static boolean isBatchOperationCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || !BATCH_OPERATIONS.containsKey(methodName)) return false;
        return !NULL_WRAPPER_WRITE_METHOD_NAMES.contains(methodName) || isNoWrapperConsumingCall(call);
    }

    /**
     * 调用是否为「空 Wrapper 消费」形态（T-WriteFamily R4）：
     * <ul>
     *  <li>{@code delete} / {@code remove}：<b>恰好 1 个实参且为 {@code null} 字面量</b>（解一层
     *      {@code PsiTypeCastExpression}）；</li>
     *  <li>{@code update}：<b>至少 2 个实参且第 2 个为 {@code null} 字面量</b>（解一层 cast）。</li>
     * </ul>
     *
     * <p>{@code delete(new LambdaQueryWrapper<>()...)} / {@code delete(wrapperVar)} /
     * {@code update(entity, wrapperVar)} / {@code service.update(null)}（单参，SQL 本身非法）都不满足
     * ——锚点让位给 Wrapper 链（场景 A/B/C）。</p>
     */
    public static boolean isNullWrapperShape(PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        String methodName = call.getMethodExpression().getReferenceName();
        if ("delete".equals(methodName) || "remove".equals(methodName)) {
            return args.length == 1 && isNullLiteral(args[0]);
        }
        if ("update".equals(methodName)) {
            return args.length >= 2 && isNullLiteral(args[1]);
        }
        return false;
    }

    /**
     * 空 Wrapper 写族调用的完整门（注册表 + 形态 + receiver）：锚点与选择区候选共用同一判定，
     * 保证 gutter 亮图标与预览可产出严格一致（宁可无图标，不可误亮）。
     *
     * <p><b>注册表成员资格也是门的一部分</b>：名字不在 {@link #BATCH_OPERATIONS} 时预览链路必然 null，
     * 此时若锚点仍亮就是死图标（反向验证「去掉注册表的 delete」必须同时打掉锚点与预览）。</p>
     */
    public static boolean isNoWrapperConsumingCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        return methodName != null && BATCH_OPERATIONS.containsKey(methodName)
            && isNullWrapperShape(call)
            && hasMpCrudReceiver(call);
    }

    /** 表达式是否为 {@code null} 字面量（解一层 {@code (Wrapper<X>) null} 强转包裹）。 */
    private static boolean isNullLiteral(PsiExpression expr) {
        PsiExpression unwrapped = expr instanceof PsiTypeCastExpression
            ? ((PsiTypeCastExpression) expr).getOperand() : expr;
        if (!(unwrapped instanceof PsiLiteralExpression)) return false;
        return ((PsiLiteralExpression) unwrapped).getValue() == null;
    }

    /**
     * receiver 是否为 MyBatis-Plus CRUD 家族（形态对齐
     * {@link cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter#hasJpaCrudReceiver}）。
     *
     * <p>判定顺序：</p>
     * <ol>
     *  <li>qualifier 变量 / 形参 / 字段的类型（含父类型链）命中 {@code BaseMapper} / {@code IService} /
     *      {@code ServiceImpl} / {@code AbstractModel}（FQN 前缀 {@code com.baomidou.mybatisplus} 优先，
     *      简名兜底）；</li>
     *  <li>qualifier 是 {@code getBaseMapper()} 调用或变量名 {@code baseMapper} → 直接放行
     *      （MP ServiceImpl 惯用形态）；</li>
     *  <li>隐式 / 显式 {@code this} → 所在类父链含 {@code ServiceImpl} / {@code IService}
     *      （Service 内部 {@code update(entity, null)}）。</li>
     * </ol>
     *
     * <p>成本：只在 {@link #isNullWrapperShape} 通过后调用（形态门已把负例压到极小），一次变量 resolve +
     * 类型父链文本匹配，与场景 E 的 receiver 判定同款微秒级文档化例外。</p>
     */
    public static boolean hasMpCrudReceiver(PsiMethodCallExpression call) {
        try {
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier == null || qualifier instanceof PsiThisExpression) {
                return isMpCrudClassChain(PsiTreeUtil.getParentOfType(call, PsiClass.class), MP_CRUD_SUPER_TYPE_DEPTH);
            }
            if (qualifier instanceof PsiMethodCallExpression) {
                return "getBaseMapper".equals(((PsiMethodCallExpression) qualifier).getMethodExpression().getReferenceName())
                    || isMpCrudType(receiverType(qualifier), MP_CRUD_SUPER_TYPE_DEPTH);
            }
            if (qualifier instanceof PsiReferenceExpression) {
                return "baseMapper".equals(((PsiReferenceExpression) qualifier).getReferenceName())
                    || isMpCrudType(receiverType(qualifier), MP_CRUD_SUPER_TYPE_DEPTH);
            }
            return isMpCrudType(qualifier.getType() instanceof PsiClassType ? (PsiClassType) qualifier.getType() : null,
                MP_CRUD_SUPER_TYPE_DEPTH);
        } catch (Throwable t) {
            return false;
        }
    }

    /** qualifier 表达式的接收者类型（变量优先解析声明类型，方法调用用返回类型）。 */
    private static PsiClassType receiverType(PsiExpression expr) {
        if (expr instanceof PsiReferenceExpression) {
            PsiElement resolved = ((PsiReferenceExpression) expr).resolve();
            if (resolved instanceof PsiVariable) {
                PsiType declared = ((PsiVariable) resolved).getType();
                if (declared instanceof PsiClassType) return (PsiClassType) declared;
            }
        }
        return expr.getType() instanceof PsiClassType ? (PsiClassType) expr.getType() : null;
    }

    /** 类型自身或父链是否命中 MP CRUD 基类。 */
    private static boolean isMpCrudType(PsiClassType type, int depth) {
        if (type == null || depth < 0) return false;
        if (isMpCrudTypeText(type.getCanonicalText())) return true;
        return isMpCrudClassChain(type.resolve(), depth - 1);
    }

    /** 类自身的父类型链（含父链的父链）是否命中 MP CRUD 基类。 */
    private static boolean isMpCrudClassChain(PsiClass clazz, int depth) {
        if (clazz == null || depth < 0) return false;
        for (PsiClassType superType : clazz.getSuperTypes()) {
            if (isMpCrudTypeText(superType.getCanonicalText())) return true;
            if (isMpCrudClassChain(superType.resolve(), depth - 1)) return true;
        }
        return false;
    }

    /**
     * 类型文本是否为 MP CRUD 基类。
     *
     * <p>FQN 可得且以 {@code com.baomidou.mybatisplus} 开头 → 直接按基类简名认定（优先口径）；
     * FQN 不可得 / 工程未索引依赖 / 测试桩只有简名 → <b>简名兜底</b>（同集合）。
     * 两条路径的接受集合相同，差别只在语义来源：前者是真 MP 类型，后者是计划 P-5 登记并接受的
     * 「同名自有基类」误放面（D-7 已明确不把该守卫扩到 insert 族，避免改变既有锚点集合）。</p>
     */
    private static boolean isMpCrudTypeText(String canonicalText) {
        if (canonicalText == null || canonicalText.isBlank()) return false;
        int lt = canonicalText.indexOf('<');
        String raw = (lt >= 0 ? canonicalText.substring(0, lt) : canonicalText).trim();
        // FQN 前缀优先：真 MP 全限定名（com.baomidou.mybatisplus.*）按基类简名认定
        if (raw.startsWith(MP_CRUD_FQN_PREFIX)) {
            return MP_CRUD_BASE_SIMPLE_NAMES.contains(raw.substring(raw.lastIndexOf('.') + 1));
        }
        // 简名兜底：工程未索引 MP 依赖（含测试桩）时 canonicalText 只有简名或别的包名
        return MP_CRUD_BASE_SIMPLE_NAMES.contains(raw.substring(raw.lastIndexOf('.') + 1));
    }

    /** 便捷重载：全默认开关（injectLogicDelete / resolveVariables / injectTenantLine 均为 true）。 */
    public static BatchPreview convert(PsiMethodCallExpression call) {
        return convert(call, true, true, true);
    }

    /**
     * 解析目标实体表信息（T-M15-08 起支持三种来源，T-M22-04 增补第四种）：
     * <ol>
     *  <li>集合参数泛型（{@code List<Entity>}，批量形态）</li>
     *  <li>实体参数类型（{@code save(entity)} / {@code updateById(entity)} 单实体形态）</li>
     *  <li>service qualifier 泛型（{@code getById(id)} / {@code removeById(id)} 单参形态：
     *      {@code XxxService extends IService<Entity>}）</li>
     *  <li>qualifier 实体类型（T-M22-04，ActiveRecord：{@code entity.insert()} / {@code entity.updateById()} /
     *      {@code entity.deleteById()} / {@code entity.selectAll()} 无实参形态）</li>
     * </ol>
     *
     * <p>M19.6（2026-08-13 用户实测）：步骤 1/2 增加 {@link #isLikelyEntity} 限定——{@code getById(dto.getId())}
     * 的实参 Long 在真实 IDE 可解析，原实现把 java.lang.Long 当实体；限定后 Long/String 等基础类型跳过，
     * 走步骤 3 service 泛型。</p>
     */
    private static ResolvedTarget resolveEntityTable(PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        // 1. 集合参数泛型（List<Entity>）；2. 实体参数类型（save(entity) / updateById(entity)）
        if (args.length > 0) {
            PsiExpression firstArg = args[0];
            PsiClass entityFromList = resolveGenericArg(firstArg.getType(), 0);
            if (entityFromList != null && isLikelyEntity(entityFromList)) {
                return new ResolvedTarget(new EntityResolver().resolve(entityFromList), entityFromList);
            }
            // 类字面量实参（Db.getById(User.class, id) / Db.list(User.class, wrapper)，T-M22-04）显式处理：
            // LightPlatform 下 `User.class` 表达式的 type（Class<User>）可能不可解析，
            // operand 类型（User 本身）更可靠
            PsiClass entityFromArg;
            if (firstArg instanceof PsiClassObjectAccessExpression) {
                PsiTypeElement operand = ((PsiClassObjectAccessExpression) firstArg).getOperand();
                PsiType operandType = operand != null ? operand.getType() : null;
                entityFromArg = operandType instanceof PsiClassType ? ((PsiClassType) operandType).resolve() : null;
            } else {
                entityFromArg = firstArg.getType() instanceof PsiClassType
                    ? ((PsiClassType) firstArg.getType()).resolve() : null;
            }
            if (entityFromArg != null && isLikelyEntity(entityFromArg)) {
                return new ResolvedTarget(new EntityResolver().resolve(entityFromArg), entityFromArg);
            }
        }
        // 3. service qualifier 泛型（getById(id) 等单参非实体场景）
        PsiClass entityFromService = resolveServiceEntityClass(call);
        if (entityFromService != null) return new ResolvedTarget(new EntityResolver().resolve(entityFromService), entityFromService);
        // 3b. 隐式 this 回退（this.getById(id)）
        PsiClass entityFromImplicitThis = resolveEntityFromImplicitThis(call);
        if (entityFromImplicitThis != null) {
            return new ResolvedTarget(new EntityResolver().resolve(entityFromImplicitThis), entityFromImplicitThis);
        }
        // 3c. 方法调用 qualifier 回退（getBaseMapper().selectById(id)）
        PsiClass entityFromMethodQualifier = resolveEntityFromMethodQualifier(call);
        if (entityFromMethodQualifier != null) {
            return new ResolvedTarget(new EntityResolver().resolve(entityFromMethodQualifier), entityFromMethodQualifier);
        }
        // 4. qualifier 实体类型（T-M22-04，ActiveRecord 无参形态）
        PsiClass entityFromQualifier = resolveQualifierEntityClass(call);
        if (entityFromQualifier == null) return null;
        return new ResolvedTarget(new EntityResolver().resolve(entityFromQualifier), entityFromQualifier);
    }

    /** 取类类型的第 index 个泛型参数并 resolve（类型不匹配或无该参数时返回 null）。 */
    private static PsiClass resolveGenericArg(PsiType type, int index) {
        if (!(type instanceof PsiClassType)) return null;
        PsiType[] parameters = ((PsiClassType) type).getParameters();
        if (parameters.length <= index) return null;
        PsiType parameter = parameters[index];
        return parameter instanceof PsiClassType ? ((PsiClassType) parameter).resolve() : null;
    }

    /**
     * T-Mxx：隐式/显式 this 调用的实体解析——{@code getById(id)}（隐式 this，qualifierExpression 为 null）
     * 或 {@code this.getById(id)}（显式 this）时，从 containing class 的父类/接口泛型参数解析实体
     * （{@code ServiceImpl<M, User> → User}）。高置信度：方法名已在 BATCH_OPERATIONS 白名单中。
     */
    private static PsiClass resolveEntityFromImplicitThis(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        // 仅当 qualifier 为 null（隐式 this）或 PsiThisExpression（显式 this）时触发
        if (qualifier != null && !(qualifier instanceof PsiThisExpression)) return null;
        PsiClass containingClass = PsiTreeUtil.getParentOfType(call, PsiClass.class);
        if (containingClass == null) return null;
        // 沿父类/接口链取最后一个类型参数（ServiceImpl<M, User> → User）
        return lastSuperTypeParam(containingClass);
    }

    /**
     * T-Mxx：方法调用 qualifier 的实体解析——{@code getBaseMapper().selectById(id)} 时 qualifier 是
     * PsiMethodCallExpression，从 qualifier 方法的返回类型泛型参数解析实体（{@code BaseMapper<User> → User}）。
     * 返回类型自身无泛型参数时（如 {@code UserMapper extends BaseMapper<User>}），回退到父类/接口泛型参数。
     */
    private static PsiClass resolveEntityFromMethodQualifier(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiMethodCallExpression)) return null;
        PsiType returnType = ((PsiMethodCallExpression) qualifier).getMethodExpression().getType();
        if (!(returnType instanceof PsiClassType)) return null;
        PsiClassType classType = (PsiClassType) returnType;
        // 返回类型自身泛型参数（BaseMapper<User> → User）
        PsiClass fromParameter = resolveGenericArg(classType, 0);
        if (fromParameter != null && !isJdkBasePackage(fromParameter)) {
            return fromParameter;
        }
        // 回退：返回类型的父类/接口泛型参数（UserMapper extends BaseMapper<User> → User）
        PsiClass resolved = classType.resolve();
        return resolved != null ? lastSuperTypeParam(resolved) : null;
    }

    /** 沿父类/接口链取最后一个类型参数（{@code ServiceImpl<M, User> → User}），跳过 JDK 基础包。 */
    private static PsiClass lastSuperTypeParam(PsiClass clazz) {
        for (PsiClassType superType : clazz.getSuperTypes()) {
            PsiType[] params = superType.getParameters();
            if (params.length == 0) continue;
            if (!(params[params.length - 1] instanceof PsiClassType)) continue;
            PsiClass resolved = ((PsiClassType) params[params.length - 1]).resolve();
            if (resolved == null) continue;
            if (isJdkBasePackage(resolved)) continue;
            return resolved;
        }
        return null;
    }

    private static boolean isJdkBasePackage(PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return true;
        return qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.");
    }

    /**
     * T-M22-04（ActiveRecord 识别）：从 qualifier 变量类型解析实体——{@code entity.insert()} 的
     * qualifier 变量类型即实体本身（AbstractModel 子类）；{@link #isLikelyEntity} 限定避免
     * {@code service.getById(id)} 等 service/mapper 变量被误判为实体。
     */
    private static PsiClass resolveQualifierEntityClass(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression)) return null;
        PsiElement target = ((PsiReferenceExpression) qualifier).resolve();
        if (!(target instanceof PsiVariable)) return null;
        PsiType varType = ((PsiVariable) target).getType();
        if (!(varType instanceof PsiClassType)) return null;
        PsiClass resolved = ((PsiClassType) varType).resolve();
        return resolved != null && isLikelyEntity(resolved) ? resolved : null;
    }

    /**
     * 实体类启发式判定（M19.6）：仅接受 <b>@TableName 注解实体</b>（真实实体，兼容无 Entity 后缀命名
     * 如 SpWorkflow）或<b>类名含 "Entity"</b> 的类；排除 JDK 基础类型（java./javax. 前缀）——
     * 避免 getById/removeById 的 Long/String 等实参类型被误当实体解析。
     */
    private static boolean isLikelyEntity(PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return false;
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return false;
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String annotationName = annotation.getQualifiedName();
            if (annotationName != null && annotationName.endsWith(".TableName")) return true;
        }
        String name = psiClass.getName();
        return name != null && name.contains("Entity");
    }

    /**
     * 从 service qualifier 解析实体类（{@code XxxService extends IService<Entity>} /
     * {@code ServiceImpl<Mapper, Entity>}）：变量类型自身泛型参数优先（{@code XxxService<Entity>}），
     * 否则沿父类型链取「最后一个类型参数」。
     */
    private static PsiClass resolveServiceEntityClass(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression)) return null;
        PsiElement target = ((PsiReferenceExpression) qualifier).resolve();
        if (!(target instanceof PsiVariable)) return null;
        PsiType varType = ((PsiVariable) target).getType();
        if (!(varType instanceof PsiClassType)) return null;
        PsiClassType classType = (PsiClassType) varType;
        // 变量类型自身泛型（XxxService<Entity>）——T-M22-04：加 isLikelyEntity 限定
        // （selectAll 等常见方法名防误伤：`List<String> list.selectAll()` 的 String 不得解析为实体）
        PsiClass fromParameter = resolveGenericArg(classType, 0);
        if (fromParameter != null && isLikelyEntity(fromParameter)) {
            return fromParameter;
        }
        // 父类/接口泛型参数（ServiceImpl<M, Entity> / IService<Entity> 的 Entity）
        PsiClass clazz = classType.resolve();
        return clazz != null ? lastSuperTypeParam(clazz) : null;
    }

    // ------------------------------------------------------------------
    // SQL 模板构建（T-M23-01：逻辑删除/软删除；T-M23-02：参数登记）
    // ------------------------------------------------------------------

    /**
     * UPDATE 模板：非主键列 SET + 主键 WHERE（无主键标记时用首列；updateById 同 update-strategy: always 全列语义）。
     *
     * <p>T-M22-02（@Version 乐观锁）：实体含 @Version 字段时（对齐 MP OptimisticLockerInnerInterceptor）——
     * version 列不进实体 SET（{@code version = ?} 由 {@code version = version + 1} 取代），
     * WHERE 末尾追加 {@code AND version = ?}；参数组说明同步追加 version（buildParamGroups 顺序与 SQL 一致）。
     * T-M23-04：SET 列为可勾选（默认全勾选），WHERE 主键/@Version 为固定列（无勾选）。</p>
     */
    private static BatchBuild buildUpdateBatch(
        PsiMethodCallExpression call,
        String methodName,
        TableInfo table
    ) {
        // T-WriteFamily（R4）：`BaseMapper.update(T entity, Wrapper<T> updateWrapper)` 的 null-wrapper 形态
        // （`update(entity, null)` / `update(entity, (Wrapper<X>) null)`）——全列 SET 且**无任何 WHERE**。
        // 不复用 modelBuild：BatchModel.renderSql 的 UPDATE 分支把 fixedColumns 硬编码成 WHERE，
        // 复用会凭空多出 `WHERE id = ?` 与版本条件。
        if ("update".equals(methodName)) return buildNoWhereUpdateBatch(call, table);
        List<ColumnInfo> columns = table.getColumns();
        ColumnInfo idColumn = firstIdColumn(columns);
        String versionColumn = table.getVersionColumn();
        List<ColumnInfo> setColumns = new ArrayList<>();
        for (ColumnInfo column : columns) {
            if (!column.getColumnName().equals(idColumn.getColumnName())
                && !column.getColumnName().equals(versionColumn)) {
                setColumns.add(column);
            }
        }
        String prefix = entityPrefix(call);
        List<BatchColumn> toggleColumns = batchColumns(prefix, table, setColumns, column -> true);
        List<BatchColumn> fixedColumns = new ArrayList<>();
        fixedColumns.add(new BatchColumn(idColumn.getColumnName(), idSource(prefix, idColumn.getColumnName()),
            ParamQuote.decide(idColumn.getFieldType()), true, false, null,
            ParamQuote.isTemporal(idColumn.getFieldType())));
        if (versionColumn != null) {
            String versionFieldType = null;
            for (ColumnInfo column : columns) {
                if (column.getColumnName().equals(versionColumn)) {
                    versionFieldType = column.getFieldType();
                    break;
                }
            }
            fixedColumns.add(new BatchColumn(versionColumn, prefix + "." + versionColumn,
                ParamQuote.decide(versionFieldType), true, false, null,
                ParamQuote.isTemporal(versionFieldType)));
        }
        List<String> groupColumns = new ArrayList<>();
        for (ColumnInfo column : setColumns) groupColumns.add(column.getColumnName());
        groupColumns.add(idColumn.getColumnName());
        if (versionColumn != null) groupColumns.add(versionColumn);
        String paramGroups = buildParamGroups(groupColumns);
        return modelBuild(new BatchModel(
            BatchKind.UPDATE, table.getTableName(), toggleColumns, fixedColumns, versionColumn), call)
            .withParamGroups(paramGroups);
    }

    /**
     * T-WriteFamily（R4）：{@code update(entity, null)} 的<b>无 WHERE</b> 更新模板 ——
     * {@code UPDATE 表 SET 实体列 = ?}。
     *
     * <p>语义（对齐 MP {@code SqlMethod.UPDATE} + {@code sqlWhereEntityWrapper}：wrapper 为 null 时该片段
     * 不产生 WHERE）：</p>
     * <ul>
     *  <li>SET 列与 {@link #buildUpdateBatch}（updateById）同口径：排除主键列与 @Version 列；</li>
     *  <li><b>不生成任何 WHERE</b>——@Version 乐观锁条件也不得生成（没有 wrapper 就没有可定位的行）；</li>
     *  <li>不复用 {@link BatchModel}（其 UPDATE 渲染把主键硬编码成 WHERE）→ {@code batchModel = null}，
     *      面板不提供列勾选；</li>
     *  <li>附 {@link #NO_WHERE_UPDATE_WARNING} 全表更新警示。</li>
     * </ul>
     *
     * <p>非「第二参为 null 字面量」的形态（{@code update(entity, wrapperVar)} / 单参 {@code update(Wrapper)} /
     * 无参）一律返回 null：前者由 Wrapper 链路径承载，后者 SQL 本身非法（{@code UPDATE t SET} 无赋值）。</p>
     */
    private static BatchBuild buildNoWhereUpdateBatch(PsiMethodCallExpression call, TableInfo table) {
        if (!isNullWrapperShape(call)) return null;
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        List<ColumnInfo> columns = table.getColumns();
        ColumnInfo idColumn = firstIdColumn(columns);
        String versionColumn = table.getVersionColumn();
        List<ColumnInfo> setColumns = new ArrayList<>();
        for (ColumnInfo column : columns) {
            if (!column.getColumnName().equals(idColumn.getColumnName())
                && !column.getColumnName().equals(versionColumn)) {
                setColumns.add(column);
            }
        }
        // 只有主键（+版本列）的实体没有可赋值列 → UPDATE 无 SET，SQL 非法，如实无预览
        if (setColumns.isEmpty()) return null;
        String prefix = entityPrefix(call);
        List<String> sets = new ArrayList<>();
        for (ColumnInfo column : setColumns) {
            sets.add(dialect.quoteIfKeyword(column.getColumnName()) + " = ?");
        }
        List<ParamEntry> params = new ArrayList<>();
        for (ColumnInfo column : setColumns) {
            params.add(new ParamEntry(idSource(prefix, column.getColumnName()),
                ParamQuote.hint(column.getFieldType())));
        }
        return new BatchBuild(
            "UPDATE " + dialect.quoteIfKeyword(table.getTableName()) + "\nSET " + String.join(", ", sets),
            params, NO_WHERE_UPDATE_WARNING);
    }

    /**
     * INSERT 模板：全列 + VALUES。
     *
     * <p>{@code insertBatchSomeColumn}（自定义注入器，X09）：排除 {@code FieldFill.UPDATE} 填充列；
     * {@code save(entity)} 单实体同模板（F-15 语义一致）。
     * T-M23-04：全列可勾选，<b>主键默认不勾选</b>（{@code @TableId(type = IdType.AUTO)} 自增场景）。
     * T-TenantLine：INSERT 列清单追加租户列（固定、默认勾选、恒参与），实体已有该列时不重复追加。</p>
     */
    private static BatchBuild buildInsertBatch(
        PsiMethodCallExpression call,
        String methodName,
        TableInfo table,
        PsiClass entityClass,
        boolean injectTenantLine
    ) {
        List<ColumnInfo> columns;
        if ("insertBatchSomeColumn".equals(methodName)) {
            columns = new ArrayList<>();
            for (ColumnInfo column : table.getColumns()) {
                if (!"UPDATE".equals(column.getFill())) columns.add(column);
            }
        } else {
            columns = table.getColumns();
        }
        String prefix = entityPrefix(call);
        List<BatchColumn> toggleColumns = new ArrayList<>(
            batchColumns(prefix, table, columns, column -> !(column.isId() && column.isIdTypeAuto())));
        String tenantColumn;
        if (injectTenantLine) {
            String configured = PluginSettings.getInstance().getState().getTenantColumnName();
            tenantColumn = (configured == null || configured.isBlank()) ? DEFAULT_TENANT_COLUMN : configured;
        } else {
            LOG.info("buildInsertBatch: SKIP injectTenantLine=false, method=" + methodName
                + ", table=" + table.getTableName());
            tenantColumn = null;
        }
        if (tenantColumn != null) {
            boolean exists = false;
            for (BatchColumn column : toggleColumns) {
                if (column.getColumnName().equals(tenantColumn)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                LOG.info("buildInsertBatch: INJECT col=" + tenantColumn + ", method=" + methodName
                    + ", table=" + table.getTableName());
                toggleColumns.add(new BatchColumn(tenantColumn, prefix + "." + tenantColumn, null, true));
            } else {
                LOG.info("buildInsertBatch: DEDUP col=" + tenantColumn + " already in entity columns, method="
                    + methodName);
            }
        }
        List<String> groupColumns = new ArrayList<>();
        for (ColumnInfo column : columns) groupColumns.add(column.getColumnName());
        String paramGroups = buildParamGroups(groupColumns);
        BatchBuild build = modelBuild(new BatchModel(BatchKind.INSERT, table.getTableName(), toggleColumns), call)
            .withParamGroups(paramGroups);
        // T-M2：@KeySequence 序列方言注释提示（主键列占位不变，仅追加提示行）
        String keySequence = entityClass != null ? EntityResolver.keySequenceValue(entityClass) : null;
        if (keySequence == null) return build;
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        if (!KEY_SEQUENCE_DIALECT_NAMES.contains(dialect.getName())) return build;
        return build.withSql(build.sql
            + "\n-- @KeySequence: 主键取序列 " + keySequence
            + "（" + dialect.getName() + " 方言，运行时由 MP KeyGenerator 取 nextval）");
    }

    /**
     * DELETE 模板（T-M23-01）：主键 IN 列表（removeByIds/deleteByIds）/ 主键等值（removeById / deleteById）。
     *
     * <p><b>有 @TableLogic（且逻辑删除开关开启）→ 软删除</b>（删除操作实际是修改操作）：
     * {@code UPDATE 表 SET deleted = 1 WHERE 主键 ... AND deleted = 0}；无 @TableLogic → 物理 DELETE。
     * T-M23-02：主键等值实参可静态求值 → 内联（{@code removeById(1L)} → {@code WHERE id = 1}）。</p>
     */
    private static BatchBuild buildDeleteBatch(
        PsiMethodCallExpression call,
        String methodName,
        TableInfo table,
        boolean injectLogicDelete,
        boolean resolveVariables
    ) {
        // T-WriteFamily（R4）：`BaseMapper.delete(Wrapper)` / `IService.remove(Wrapper)` 的 null-wrapper 形态——
        // 无 WHERE（无逻辑删除 → DELETE FROM 表；有 @TableLogic → UPDATE 表 SET deleted = 1 WHERE deleted = 0，
        // 对齐 MP `SqlMethod.LOGIC_DELETE` + TableInfo.getLogicDeleteSql）；附全表删除警示。
        // 非 null 字面量实参（wrapper 变量 / 内联链 / 其它对象）→ null：由 Wrapper 链路径承载，绝不退化成无 WHERE。
        if ("delete".equals(methodName) || "remove".equals(methodName)) {
            return buildNoWhereDeleteBatch(call, table, injectLogicDelete);
        }
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        ColumnInfo idColumn = firstIdColumn(table.getColumns());
        String logicColumn = injectLogicDelete ? table.getLogicDeleteColumn() : null;
        boolean singleId = SINGLE_ID_DELETE_METHODS.contains(methodName);
        kotlin.Pair<String, List<ParamEntry>> idCondition = singleId
            ? renderIdCondition(call, idColumn, resolveVariables, dialect)
            : new kotlin.Pair<>(dialect.quoteIfKeyword(idColumn.getColumnName()) + " IN (?, ?, ...)",
                List.<ParamEntry>of());
        String idWhere = idCondition.getFirst();
        List<ParamEntry> idParams = idCondition.getSecond();
        if (logicColumn != null) {
            // 软删除：删除转 UPDATE（deleted = 1 为字面量，不进参数行）；T-M23.5 表名/列名按方言引用
            String logic = dialect.quoteIfKeyword(logicColumn);
            return new BatchBuild("UPDATE " + dialect.quoteIfKeyword(table.getTableName())
                + "\nSET " + logic + " = 1\nWHERE " + idWhere + " AND " + logic + " = 0", idParams);
        }
        return new BatchBuild("DELETE FROM " + dialect.quoteIfKeyword(table.getTableName())
            + "\nWHERE " + idWhere, idParams);
    }

    /**
     * T-WriteFamily（R4）：{@code delete(null)} / {@code remove(null)} 的<b>无 WHERE</b> 删除模板。
     *
     * <ul>
     *  <li>无 @TableLogic（或逻辑删除开关关闭）→ {@code DELETE FROM 表}（对齐 MP {@code SqlMethod.DELETE}：
     *      wrapper 为 null 时 {@code sqlWhereEntityWrapper} 不产生 WHERE）；</li>
     *  <li>有 @TableLogic → {@code UPDATE 表 SET deleted = 1 WHERE deleted = 0}（对齐
     *      {@code SqlMethod.LOGIC_DELETE}：逻辑删除片段<b>无条件</b>追加 {@code TableInfo.getLogicDeleteSql}，
     *      即 {@code deleted = 0}）——该 WHERE 只是软删除的记录范围，不是行定位条件，仍按全表写操作警示。</li>
     * </ul>
     *
     * <p>形态门 = {@link #isNullWrapperShape}（恰好 1 个 null 字面量实参，解一层 cast）；其余形态返回 null。</p>
     */
    private static BatchBuild buildNoWhereDeleteBatch(
        PsiMethodCallExpression call,
        TableInfo table,
        boolean injectLogicDelete
    ) {
        if (!isNullWrapperShape(call)) return null;
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        String tableName = dialect.quoteIfKeyword(table.getTableName());
        String logicColumn = injectLogicDelete ? table.getLogicDeleteColumn() : null;
        if (logicColumn != null) {
            String logic = dialect.quoteIfKeyword(logicColumn);
            return new BatchBuild(
                "UPDATE " + tableName + "\nSET " + logic + " = 1\nWHERE " + logic + " = 0",
                NO_WHERE_DELETE_WARNING);
        }
        return new BatchBuild("DELETE FROM " + tableName, NO_WHERE_DELETE_WARNING);
    }

    /**
     * SELECT 模板（M19.5，实体单条查询，P1-02/P2-08 同点合并）：
     * 全列清单 + 主键等值（getById/getOptById）/ 主键 IN（listByIds/selectByIds）。
     *
     * <p>T-M22-04：ActiveRecord selectAll() 全列查询——<b>无 WHERE</b>。
     * T-M23-01：实体含 @TableLogic（开关开启）→ WHERE 追加 {@code AND deleted = 0}。
     * T-M23-02：主键等值实参可静态求值 → 内联。</p>
     */
    private static BatchBuild buildSelectBatch(
        PsiMethodCallExpression call,
        String methodName,
        TableInfo table,
        boolean injectLogicDelete,
        boolean resolveVariables
    ) {
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        ColumnInfo idColumn = firstIdColumn(table.getColumns());
        // T-M23.5：全列清单/表名/条件列名按方言引用
        String selectColumns = table.getColumns().stream()
            .map(column -> dialect.quoteIfKeyword(column.getColumnName()))
            .collect(Collectors.joining(", "));
        String logicCond = injectLogicDelete && table.getLogicDeleteColumn() != null
            ? dialect.quoteIfKeyword(table.getLogicDeleteColumn()) + " = 0"
            : null;
        if ("selectAll".equals(methodName) || FULL_COLUMN_SELECT_METHODS.contains(methodName)) {
            String where = logicCond != null ? "\nWHERE " + logicCond : "";
            return new BatchBuild("SELECT " + selectColumns + "\nFROM "
                + dialect.quoteIfKeyword(table.getTableName()) + where);
        }
        if ("selectCount".equals(methodName)) {
            String where = logicCond != null ? "\nWHERE " + logicCond : "";
            return new BatchBuild("SELECT COUNT(*)\nFROM "
                + dialect.quoteIfKeyword(table.getTableName()) + where);
        }
        if (SINGLE_ID_SELECT_METHODS.contains(methodName)) {
            kotlin.Pair<String, List<ParamEntry>> idCondition =
                renderIdCondition(call, idColumn, resolveVariables, dialect);
            String where = idCondition.getFirst() + (logicCond != null ? " AND " + logicCond : "");
            return new BatchBuild("SELECT " + selectColumns + "\nFROM "
                + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + where, idCondition.getSecond());
        }
        String where = dialect.quoteIfKeyword(idColumn.getColumnName()) + " IN (?, ?, ...)"
            + (logicCond != null ? " AND " + logicCond : "");
        return new BatchBuild("SELECT " + selectColumns + "\nFROM "
            + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + where);
    }

    /**
     * T-M20-07（Map 条件消费）：{@code removeByMap(Map)} / {@code deleteByMap(Map)} / {@code selectByMap(Map)} /
     * {@code listByMap(Map)} 的 Map 等值条件模板——Map 键值均为运行时数据，列名与取值静态不可得，
     * WHERE 用可读占位文本 {@code col = ? AND col = ? ...}；删除/查询由方法名前缀区分。
     * 参数组说明置空（Map 键不可静态枚举）。
     */
    private static BatchBuild buildMapConditionBatch(
        PsiMethodCallExpression call,
        String methodName,
        TableInfo table,
        boolean injectLogicDelete
    ) {
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        boolean isDelete = methodName.startsWith("remove") || methodName.startsWith("delete");
        String logicColumn = injectLogicDelete ? table.getLogicDeleteColumn() : null;
        String condition = "<Map 等值条件: col = ? AND col = ? ...>"
            + (logicColumn != null ? " AND " + dialect.quoteIfKeyword(logicColumn) + " = 0" : "");
        String tableName = dialect.quoteIfKeyword(table.getTableName());
        if (isDelete && logicColumn != null) {
            return new BatchBuild("UPDATE " + tableName + "\nSET " + dialect.quoteIfKeyword(logicColumn)
                + " = 1\nWHERE " + condition);
        }
        if (isDelete) {
            return new BatchBuild("DELETE FROM " + tableName + "\nWHERE " + condition);
        }
        String selectColumns = table.getColumns().stream()
            .map(column -> dialect.quoteIfKeyword(column.getColumnName()))
            .collect(Collectors.joining(", "));
        return new BatchBuild("SELECT " + selectColumns + "\nFROM " + tableName + "\nWHERE " + condition);
    }

    /**
     * UPSERT 模板（saveOrUpdate / saveOrUpdateBatch，U12，T-M15-08；insertOrUpdate，T-M20-08）：
     * INSERT + 方言冲突子句（T-M43——PG 系 {@code ON CONFLICT}、MySQL 系 {@code ON DUPLICATE KEY UPDATE}、
     * Oracle/SQL Server/DM 降级纯 INSERT，由 {@link BatchModel#renderSql} 按 Dialect.renderUpsert 渲染）。
     *
     * <p>T-M23.2（用户实测：saveOrUpdate 主键应为可选）：主键纳入 toggleColumns（默认勾选，完整 upsert；
     * 取消勾选 → 纯 INSERT 新记录路径）。</p>
     */
    private static BatchBuild buildUpsertBatch(
        PsiMethodCallExpression call,
        String methodName,
        TableInfo table
    ) {
        ColumnInfo idColumn = firstIdColumn(table.getColumns());
        String prefix = entityPrefix(call);
        List<BatchColumn> toggleColumns = batchColumns(prefix, table, table.getColumns(), column -> true);
        List<String> groupColumns = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) groupColumns.add(column.getColumnName());
        String paramGroups = buildParamGroups(groupColumns);
        return modelBuild(new BatchModel(BatchKind.UPSERT, table.getTableName(), toggleColumns,
            List.of(), null, idColumn.getColumnName(), null), call).withParamGroups(paramGroups);
    }

    // ------------------------------------------------------------------
    // T-M23-02：参数登记辅助
    // ------------------------------------------------------------------

    /**
     * 单主键方法的 {@code ?} 渲染与参数登记：
     * <ul>
     *  <li>实参可静态求值（字面量/可追踪变量，{@link ExpressionEvaluator}；受 resolveVariables 开关控制）→
     *      <b>内联</b>进 SQL（{@code removeById(1L)} → {@code id = 1}，无参数行）；</li>
     *  <li>运行时值 → {@code ?} + 参数行（source=实参表达式文本，如 {@code id} / {@code dto.getId()}）；</li>
     *  <li>无实参（ActiveRecord 形态）→ {@code ?} + {@code 实体.主键列} 参数行。</li>
     * </ul>
     *
     * @return (WHERE 片段, 参数登记)；Db.getById(Class, id) 两参形态 id 取第二个实参
     */
    private static kotlin.Pair<String, List<ParamEntry>> renderIdCondition(
        PsiMethodCallExpression call,
        ColumnInfo idColumn,
        boolean resolveVariables,
        Dialect dialect
    ) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        PsiExpression idExpr = args.length > 1 ? args[1] : (args.length > 0 ? args[0] : null);
        String idName = dialect.quoteIfKeyword(idColumn.getColumnName());
        if (idExpr != null) {
            SqlValue value = resolveVariables
                ? ExpressionEvaluator.evaluate(idExpr)
                : new SqlValue.Placeholder(idExpr.getText());
            if (value instanceof SqlValue.Literal) {
                return new kotlin.Pair<>(idName + " = " + ((SqlValue.Literal) value).getRaw(), List.of());
            }
            return new kotlin.Pair<>(idName + " = ?", List.of(new ParamEntry(
                ((SqlValue.Placeholder) value).getSource(),
                ParamQuote.hint(idColumn.getFieldType()))));
        }
        // ActiveRecord 无参形态：id 来自实体字段
        return new kotlin.Pair<>(idName + " = ?", List.of(new ParamEntry(
            idSource(entityPrefix(call), idColumn.getColumnName()),
            ParamQuote.hint(idColumn.getFieldType()))));
    }

    /** 主键列（无主键标记时用首列）。 */
    private static ColumnInfo firstIdColumn(List<ColumnInfo> columns) {
        for (ColumnInfo column : columns) {
            if (column.isId()) return column;
        }
        return columns.get(0);
    }

    /**
     * 实体/集合参数前缀：首实参文本（{@code save(entity)} → {@code entity}、{@code saveBatch(list)} → {@code list}）；
     * 无实参（ActiveRecord {@code entity.insert()} 形态）→ qualifier 文本；均无 → 空串。
     */
    private static String entityPrefix(PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length > 0) return args[0].getText();
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        return qualifier instanceof PsiReferenceExpression ? qualifier.getText() : "";
    }

    /** 实体字段参数来源：{@code 实体变量.列名}；无前缀（ActiveRecord 形态）时仅列名。 */
    private static String idSource(String prefix, String columnName) {
        return prefix.isEmpty() ? columnName : prefix + "." + columnName;
    }

    /** 参数组说明（docs/项目介绍.md 格式）：{@code -- 实际参数: [col=?, col=?] × N}。 */
    private static String buildParamGroups(List<String> columns) {
        String group = String.join(", ", columns.stream().map(c -> c + "=?").collect(Collectors.toList()));
        return "-- 实际参数: [" + group + "] × N（每行一组，运行时值不可静态获取）";
    }
}
