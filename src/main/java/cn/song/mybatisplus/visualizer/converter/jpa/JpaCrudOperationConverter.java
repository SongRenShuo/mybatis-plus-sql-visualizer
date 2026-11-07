package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.BatchKind;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator;
import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver;
import cn.song.mybatisplus.visualizer.psi.PsiDialect;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.DaoQueryKind;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.RepositoryBaseTypes;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * JPA CRUD / 写路径转换器（T-J1 Spring Data CRUD + T-J6 EntityManager 写/单查 + T-J21 HibernateTemplate 实体操作）。
 *
 * 参照 [cn.song.mybatisplus.visualizer.psi.BatchOperationConverter] 的结构（方法 → Operation → SQL 模板构建），
 * 但**实体解析恒走 [JpaEntityResolver]**（@Table/@Column/@GeneratedValue 注解语义）——
 * MP 侧 [cn.song.mybatisplus.visualizer.psi.EntityResolver] 只认 @TableName，JPA 实体会被
 * 输出成「类名驼峰推断表 + 字段名当列名」的错误 SQL（J1 修复点：`UserEntity@Table(name="t_user")`
 * 曾被输出成 `INSERT INTO user_entity`）。
 *
 * ## 消歧规则（receiver 家族判定优先级，J1 最大风险点）
 *
 * 撞名方法（`save`/`deleteById`/`getById`/`count` 等通用名）在 MP 与 JPA 语境共存，派发以
 * **接收者（qualifier）类型**为唯一事实源，优先级从高到低：
 *
 *  1. **SPRING_DATA_REPOSITORY**：qualifier 变量/表达式类型沿父类型链找到 Spring Data Repository
 *     基类（[REPOSITORY_BASE_TYPES]，与 DerivedMethodLocator 名单对齐）→ 本转换器 J1 语义；
 *  2. **JPA_ENTITY_MANAGER**：qualifier 为 EntityManager/Session 家族（复用
 *     [PreviewReceiverResolver.classify]：变量名 em/session 或以 entityManager 结尾的通配形态、
 *     类型 EntityManager/Session、getSession()/getEntityManager() 方法调用）→ J6 语义（仅 EM 方法名单内）；
 *  3. **HIBERNATE_TEMPLATE**：qualifier 为 HibernateTemplate 家族（同上）→ T-J21 语义
 *     （按 [CustomDaoHqlLocator.classifyHibernateTemplateMethod] 类别：ENTITY_OP 认领、
 *     QBC 占位、HQL/NAMED_QUERY 返回 null 交回 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.previewCustomDaoHql]）；
 *  4. 以上均不命中（MP service/mapper、CUSTOM_DAO、UNKNOWN）→ 返回 null，
 *     **维持 MP 批量路径现状**（[cn.song.mybatisplus.visualizer.psi.BatchOperationConverter]）。
 *
 * 解析失败应回退「不识别」而非套错模板——宁可无图标，不可出错误 SQL：
 * 普通类 `userRepository` 变量（无 Spring Data 基类）`save(...)` → null；`list.selectAll()` → null。
 * `save` 等撞名方法在 receiver 无法判定时由 MP 路径兜底（对存量 MP 用户零行为变化）。
 *
 * ## INSERT 模板 Hibernate 语义（J14 基础形态）
 *
 *  - `@GeneratedValue(IDENTITY)` 主键列**不进 INSERT 列清单**（数据库自增）；
 *  - `SEQUENCE` / `TABLE` → 主键列保留 `?` 占位 + 注释说明 INSERT 前置取值（select nextval / 序列表 hi/lo）；
 *  - `@Column(insertable = false)` 列不进 INSERT 列清单；
 *  - 每列参数行登记 `实体变量.列名`（对齐 MP 批量面板惯例，列可勾选重建）。
 *
 * 优雅降级：全程不抛异常，方法不匹配 / 实体不可解析 / 主键缺失（按 id 语义操作时）一律返回 null。
 */
public final class JpaCrudOperationConverter {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaCrudOperationConverter INSTANCE = new JpaCrudOperationConverter();

    private JpaCrudOperationConverter() {
    }

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * JPA CRUD 预览结果（与 [cn.song.mybatisplus.visualizer.model.BatchPreview] 同构，
     * 增加 [warning]——deleteAll 等全表操作对齐 MP BlockAttack 警示通道）。
     */
    public static final class JpaCrudPreview {
        private final String methodName;
        private final String tableName;
        private final String sql;
        private final String paramGroups;
        private final List<String> paramSources;
        private final List<ParamHint> paramHints;
        private final String warning;
        private final BatchModel batchModel;

        public JpaCrudPreview(String methodName, String tableName, String sql, String paramGroups,
                              List<String> paramSources, List<ParamHint> paramHints, String warning,
                              BatchModel batchModel) {
            this.methodName = methodName;
            this.tableName = tableName;
            this.sql = sql;
            this.paramGroups = paramGroups;
            this.paramSources = paramSources;
            this.paramHints = paramHints;
            this.warning = warning;
            this.batchModel = batchModel;
        }

        public JpaCrudPreview(String methodName, String tableName, String sql) {
            this(methodName, tableName, sql, null, List.of(), List.of(), null, null);
        }

        public String getMethodName() { return methodName; }
        public String getTableName() { return tableName; }
        public String getSql() { return sql; }
        public String getParamGroups() { return paramGroups; }
        public List<String> getParamSources() { return paramSources; }
        public List<ParamHint> getParamHints() { return paramHints; }
        public String getWarning() { return warning; }
        public BatchModel getBatchModel() { return batchModel; }
    }

    /** 单个 `?` 的参数登记（source + 回填引号提示），语义同 BatchOperationConverter.ParamEntry。 */
    private static final class ParamEntry {
        final String source;
        final ParamHint hint;

        ParamEntry(String source, ParamHint hint) {
            this.source = source;
            this.hint = hint;
        }
    }

    /** 模板构建中间产物（SQL + 参数登记 + 语义注释 + 可选列勾选模型）。 */
    private static final class Build {
        final String sql;
        final List<ParamEntry> params;
        final String paramGroups;
        final List<String> notes;
        final String warning;
        final BatchModel model;

        Build(String sql) {
            this(sql, List.of(), null, List.of(), null, null);
        }

        Build(String sql, List<ParamEntry> params, List<String> notes) {
            this(sql, params, null, notes, null, null);
        }

        Build(String sql, List<String> notes, String warning) {
            this(sql, List.of(), null, notes, warning, null);
        }

        Build(String sql, List<ParamEntry> params, String paramGroups, List<String> notes,
              String warning, BatchModel model) {
            this.sql = sql;
            this.params = params;
            this.paramGroups = paramGroups;
            this.notes = notes;
            this.warning = warning;
            this.model = model;
        }

        /** 对齐 Kotlin copy(notes = notes + note)。 */
        Build withNote(String note) {
            List<String> appended = new ArrayList<>(notes);
            appended.add(note);
            return new Build(sql, params, paramGroups, appended, warning, model);
        }

        /** 对齐 Kotlin copy(notes = ..., paramGroups = ...)。 */
        Build with(String paramGroups, List<String> notes) {
            return new Build(sql, params, paramGroups, notes, warning, model);
        }
    }

    // ------------------------------------------------------------------
    // receiver 家族与方法名单
    // ------------------------------------------------------------------

    /** receiver 家族（消歧判定结果）。 */
    private enum Family { SPRING_DATA_REPOSITORY, JPA_ENTITY_MANAGER, HIBERNATE_TEMPLATE }

    /**
     * Spring Data Repository 基类全限定名（SD6 起统一走 [RepositoryBaseTypes] 名单：
     * 含 3.0 List* 变体与 R2dbcRepository / ReactiveCrudRepository / ReactiveSortingRepository）。
     * superType 匹配用 canonicalText 前缀（真实 IDE 解析态）；未解析态（LightPlatform/跨模块）按
     * 简名后缀兜底（[RepositoryBaseTypes.SIMPLE_NAMES]）。
     */
    private static final Set<String> REPOSITORY_BASE_TYPES = RepositoryBaseTypes.FQNS;

    /** Spring Data 基类简单名（canonicalText 前缀匹配失败时的兜底集合）。 */
    private static final Set<String> REPOSITORY_BASE_SIMPLE_NAMES = RepositoryBaseTypes.SIMPLE_NAMES;

    /** T-J1：Spring Data Repository CRUD 方法名单（锚点 + 转换共用）。 */
    private static final Set<String> REPOSITORY_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
            "findById", "getOne", "getById", "getReferenceById",
            "findAll", "findAllById", "existsById", "count",
            "deleteById", "delete", "deleteAllById", "deleteAll",
            "deleteAllInBatch", "deleteAllByIdInBatch", "deleteInBatch",
            "flush");

    /** T-J6：EntityManager 写/单查方法名单（persist/merge/remove/find/getReference/refresh/flush + 存储过程）。 */
    private static final Set<String> ENTITY_MANAGER_METHODS = Set.of(
            "persist", "merge", "remove", "find", "getReference", "refresh", "flush",
            "createStoredProcedureQuery", "createNamedStoredProcedureQuery");

    /**
     * T-J21：HibernateTemplate 实体操作方法名（镜像 CustomDaoHqlLocator 登记名单 + executeFind；
     * 唯一事实源是 [CustomDaoHqlLocator.classifyHibernateTemplateMethod]，转换时以 classify 结果为准）。
     */
    private static final Set<String> HIBERNATE_TEMPLATE_METHODS = Set.of(
            "save", "saveOrUpdate", "update", "delete", "deleteAll",
            "get", "load", "loadAll", "refresh", "merge", "persist", "replicate", "lock",
            "findByExample", "findByCriteria", "executeFind");

    /** 全表删除警示（对齐 buildJpaWarning BlockAttack 文案）。 */
    private static final String BLOCK_ATTACK_WARNING = "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！";

    /**
     * 与 Wrapper 链工厂/链方法撞名、场景 C 短路后锚点不可达的方法名
     * （对齐 [cn.song.mybatisplus.visualizer.psi.WrapperLocator.wrapperFactoryMethodNames]
     * 与本转换器名单的交集：update / delete）。
     */
    private static final Set<String> WRAPPER_FACTORY_COLLIDING_NAMES = Set.of("update", "delete");

    /**
     * JPA CRUD / EM / HibernateTemplate 方法名并集，剔除与 Wrapper 链工厂/链方法撞名者
     * （[cn.song.mybatisplus.visualizer.psi.WrapperLocator.wrapperFactoryMethodNames] 的
     * `update`/`delete`——WrapperAnchorDetector 场景 C 按工厂名单短路，二者即便入锚点名单也
     * 永远不可达，刻意剔除避免误导；转换入口 [convert] 不受影响，`ht.update/ht.delete` 经
     * ConvertToSqlAction 右键/快捷键仍可预览，仅无 gutter 图标——宁可漏标不可误标的文档化边界）。
     *
     * **撞名面大（save/count/find 等通用名），锚点必须叠加 receiver 家族判定**
     * （[hasJpaCrudReceiver]），单纯按名单亮图标会误亮 `stream.count()` 等海量负例。
     */
    public static final Set<String> crudAnchorMethodNames;

    static {
        Set<String> names = new LinkedHashSet<>(REPOSITORY_METHODS);
        names.addAll(ENTITY_MANAGER_METHODS);
        names.addAll(HIBERNATE_TEMPLATE_METHODS);
        names.removeAll(WRAPPER_FACTORY_COLLIDING_NAMES);
        crudAnchorMethodNames = names;
    }

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * receiver 是否为可识别、且**该方法名可被本转换器认领**的 JPA CRUD 家族
     * （Spring Data Repository / EntityManager / HibernateTemplate）。
     *
     * [cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector] 场景 E 锚点判定用：常规批量方法
     * 名单锚点是纯方法名（容忍 gutter 正例宽松、preview 兜底 null），JPA CRUD 撞名面大
     * （`stream.count()` / `map.get(k)` 等），锚点即须做 receiver 判定——qualifier 变量一次
     * resolve + 类型父链文本匹配（BATCH_CHAIN_COLLISION_NAMES 同款先例，微秒级）。
     * **家族 × 方法名双重判定**：`hibernateTemplate.find(hql)` 家族命中但 find 归 HQL 承载
     * （CustomDaoHqlLocator 管道）→ false 不亮图标，避免「点击无结果」的死图标。
     * 判定失败返回 false——宁可无图标，不可误亮。
     */
    public static boolean hasJpaCrudReceiver(PsiMethodCallExpression call) {
        try {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) return false;
            Family family = resolveFamily(call);
            if (family == null) return false;
            switch (family) {
                case SPRING_DATA_REPOSITORY:
                    return REPOSITORY_METHODS.contains(methodName);
                case JPA_ENTITY_MANAGER:
                    return ENTITY_MANAGER_METHODS.contains(methodName);
                case HIBERNATE_TEMPLATE:
                default:
                    return HIBERNATE_TEMPLATE_METHODS.contains(methodName);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 转换 JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作调用。
     *
     * @param call             方法调用表达式
     * @param resolveVariables 变量求值开关（settings.replaceParams）：false 时跳过实参静态求值，
     *                         一律 `?` + 参数行（对齐 MP 批量路径语义）
     * @return 预览结果；receiver 非 JPA 家族 / 方法不匹配 / 实体不可解析时返回 null（交回 MP 路径）
     */
    public static JpaCrudPreview convert(PsiMethodCallExpression call) {
        return convert(call, true);
    }

    public static JpaCrudPreview convert(PsiMethodCallExpression call, boolean resolveVariables) {
        try {
            return convertInternal(call, resolveVariables);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * F-1 修复：<b>声明行</b>入口——光标落在 Repository 接口方法声明上时预览（无调用表达式，
     * {@link #convert} 永不认领）。
     *
     * <p>缺口形态（2026-10-12 演示录制中发现，诊断报告 {@code docs/调研报告/20261012-0900-*}）：
     * {@code deleteAllInBatch()} 这类 bulk 形态在派生查询线（不含 {@code By}、{@code *All} 词干白名单
     * 不放行 {@code InBatch}）与 CRUD 转换线（只收 PsiMethodCallExpression）<b>双缝隙</b>下零可达
     * ——而模板在转换层是完整的。</p>
     *
     * <p>语义：声明行无实参可静态求值，一律按 {@code ?} 占位呈现（与 {@code resolveVariables=false}
     * 同口径）；非 Repository 接口 / 方法名不在本线 / 实体不可解析时返回 null（负例守卫，交回既有回落链）。</p>
     */
    public static JpaCrudPreview convertRepositoryDeclaration(PsiMethod method, boolean resolveVariables) {
        try {
            PsiClass iface = method.getContainingClass();
            if (iface == null) return null;
            if (!iface.isInterface()) return null;
            String methodName = method.getName();
            if (!REPOSITORY_METHODS.contains(methodName)) return null;
            PsiClass entityClass = resolveRepositoryEntityFromClass(iface, null);
            if (entityClass == null) return null;
            TableInfo table = new JpaEntityResolver().resolve(entityClass);
            if (table.getEntityClassName().isBlank()) return null;
            Dialect dialect = PsiDialect.currentDialect(method.getProject());
            ColumnInfo idColumn = firstIdColumn(table);
            Build build = buildRepositoryMethod(
                    methodName, table, idColumn, new PsiExpression[0], "", dialect, resolveVariables);
            if (build == null) return null;
            return previewOf(methodName, table, build);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@link #convertRepositoryDeclaration(PsiMethod, boolean)} 的默认形态（Kotlin 默认参数 {@code = true}）。 */
    public static JpaCrudPreview convertRepositoryDeclaration(PsiMethod method) {
        return convertRepositoryDeclaration(method, true);
    }

    // ------------------------------------------------------------------
    // 派发（receiver 家族 → 各语义线）
    // ------------------------------------------------------------------

    private static JpaCrudPreview convertInternal(PsiMethodCallExpression call, boolean resolveVariables) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;
        Family family = resolveFamily(call);
        if (family == null) return null;
        switch (family) {
            case SPRING_DATA_REPOSITORY:
                return convertRepositoryCall(call, methodName, resolveVariables);
            case JPA_ENTITY_MANAGER:
                return convertEntityManagerCall(call, methodName, resolveVariables);
            case HIBERNATE_TEMPLATE:
            default:
                return convertHibernateTemplateCall(call, methodName, resolveVariables);
        }
    }

    /**
     * receiver 家族判定（优先级见类 KDoc）。
     * 1. Spring Data Repository：qualifier 类型父链含 Spring Data 基类（authoritative）；
     * 2. EntityManager / HibernateTemplate：复用 [PreviewReceiverResolver] 既有家族分类。
     */
    private static Family resolveFamily(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return null;
        PsiClassType type = receiverClassType(qualifier);
        if (type != null && isSpringDataRepositoryType(type)) {
            return Family.SPRING_DATA_REPOSITORY;
        }
        PreviewReceiverResolver.Family classified = PreviewReceiverResolver.classify(call);
        switch (classified) {
            case JPA_SESSION:
                return Family.JPA_ENTITY_MANAGER;
            case HIBERNATE_TEMPLATE:
                return Family.HIBERNATE_TEMPLATE;
            default:
                return null;
        }
    }

    /** qualifier 表达式的接收者类型（变量优先解析声明类型，方法调用 qualifier 用返回类型）。 */
    private static PsiClassType receiverClassType(PsiExpression qualifier) {
        if (qualifier instanceof PsiReferenceExpression reference) {
            if (reference.resolve() instanceof PsiVariable variable && variable.getType() instanceof PsiClassType declared) {
                return declared;
            }
            return qualifier.getType() instanceof PsiClassType fallback ? fallback : null;
        }
        return qualifier.getType() instanceof PsiClassType type ? type : null;
    }

    /** 类型（自身或父类型链）是否为 Spring Data Repository 基类（canonicalText 前缀 → 简名兜底）。 */
    private static boolean isSpringDataRepositoryType(PsiClassType type) {
        String canonicalText = type.getCanonicalText();
        if (canonicalText == null) return false;
        String direct = substringBefore(canonicalText, '<');
        if (REPOSITORY_BASE_TYPES.contains(direct) || REPOSITORY_BASE_SIMPLE_NAMES.contains(direct)) return true;
        PsiClass clazz = type.resolve();
        if (clazz == null) return false;
        for (PsiType superType : clazz.getSuperTypes()) {
            String canonical = superType.getCanonicalText();
            if (canonical == null) continue;
            String simple = substringAfterLast(substringBefore(canonical, '<'), '.');
            if (startsWithAny(canonical, REPOSITORY_BASE_TYPES) || REPOSITORY_BASE_SIMPLE_NAMES.contains(simple)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spring Data Repository 泛型实体解析：沿父类型链找 Spring Data 基类 superType 的**第一个**
     * 类型参数（JpaRepository&lt;Entity, ID&gt; → Entity）；父链不可解析时回退变量类型自身泛型参数。
     * 解析失败返回 null（负例守卫：不识别）。
     */
    private static PsiClass resolveRepositoryEntity(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return null;
        PsiClassType varType = receiverClassType(qualifier);
        if (varType == null) return null;
        return resolveRepositoryEntityFromClass(varType.resolve(), varType);
    }

    /**
     * F-1 修复：从 <b>PsiClass</b> 解析 Repository 实体（声明行入口复用）。
     *
     * <p>与调用点同一条严格判定：沿 superType 找 Spring Data 基类（[REPOSITORY_BASE_TYPES] /
     * [REPOSITORY_BASE_SIMPLE_NAMES]）的第一个类型参数；{@code fallbackType} 非空时再回退其自身
     * 第一个泛型参数（变量形态）。<b>非 Repository 接口返回 null</b>——声明行入口据此做负例守卫，
     * 不会把普通接口方法（如 {@code stream.count()}）误认成 CRUD。</p>
     */
    private static PsiClass resolveRepositoryEntityFromClass(PsiClass clazz, PsiClassType fallbackType) {
        if (clazz != null) {
            for (PsiType superType : clazz.getSuperTypes()) {
                String canonical = superType.getCanonicalText();
                if (canonical == null) continue;
                String simple = substringAfterLast(substringBefore(canonical, '<'), '.');
                boolean isBase = startsWithAny(canonical, REPOSITORY_BASE_TYPES)
                        || REPOSITORY_BASE_SIMPLE_NAMES.contains(simple);
                if (isBase && superType instanceof PsiClassType superTypeClass) {
                    PsiType[] parameters = superTypeClass.getParameters();
                    if (parameters.length > 0 && parameters[0] instanceof PsiClassType firstParam) {
                        PsiClass entity = firstParam.resolve();
                        if (entity != null) return entity;
                    }
                }
            }
        }
        // 兜底：类型自身第一个泛型参数（UserRepository<User> 变量形态 / 基类不可解析场景）
        if (fallbackType != null) {
            PsiType[] ownParameters = fallbackType.getParameters();
            if (ownParameters.length > 0 && ownParameters[0] instanceof PsiClassType firstParam) {
                return firstParam.resolve();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // T-J1：Spring Data Repository CRUD
    // ------------------------------------------------------------------

    private static JpaCrudPreview convertRepositoryCall(
            PsiMethodCallExpression call, String methodName, boolean resolveVariables) {
        if (!REPOSITORY_METHODS.contains(methodName)) return null;
        PsiClass entityClass = resolveRepositoryEntity(call);
        if (entityClass == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        if (table.getEntityClassName().isBlank()) return null;
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        ColumnInfo idColumn = firstIdColumn(table);
        PsiExpression[] args = call.getArgumentList().getExpressions();
        String prefix = columnParamPrefix(call);
        Build build = buildRepositoryMethod(methodName, table, idColumn, args, prefix, dialect, resolveVariables);
        if (build == null) return null;
        return previewOf(methodName, table, build);
    }

    /**
     * F-1 修复：Repository 方法名 → 模板主体（调用点与<b>声明行</b>共用同一条语义线）。
     *
     * <p>抽出原因：声明行没有 {@code PsiMethodCallExpression}，[convert] 与调用点回落都永不认领
     * （{@code deleteAllInBatch} 这类 bulk 形态因此在声明行零可达）。本函数只依赖
     * 「方法名 + 表 + 主键列 + 实参 + 前缀」，两条入口都能构造。</p>
     *
     * @param args 实参数组；声明行入口传空数组（静态不可得，一律 {@code ?} 占位）
     * @param prefix 列参数前缀；声明行入口传空串
     * @return 模板主体；方法名不在本线或缺少必要上下文时 null
     */
    private static Build buildRepositoryMethod(
            String methodName, TableInfo table, ColumnInfo idColumn, PsiExpression[] args,
            String prefix, Dialect dialect, boolean resolveVariables) {
        Build build;
        switch (methodName) {
            case "save":
                build = insertBuild(table, prefix, dialect).withNote(
                        "save：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）");
                break;
            case "saveAndFlush":
                build = insertBuild(table, prefix, dialect).withNote(
                        "saveAndFlush = save + flush：flush 无独立 SQL（脏检查刷写），此处呈现 save 的 INSERT");
                break;
            case "saveAll": {
                Build insert = insertBuild(table, prefix, dialect);
                build = insert.with(
                        batchParamGroups(buildInsertColumnNames(table), prefix),
                        appendNote(insert.notes, "saveAll：批量 INSERT，集合每元素一组参数（参数组 × N）"));
                break;
            }
            case "saveAllAndFlush": {
                Build insert = insertBuild(table, prefix, dialect);
                build = insert.with(
                        batchParamGroups(buildInsertColumnNames(table), prefix),
                        appendNote(insert.notes,
                                "saveAllAndFlush = saveAll + flush（脏检查刷写，无独立 flush SQL）；集合每元素一组参数（× N）"));
                break;
            }
            case "findById":
            case "getOne":
            case "getById":
            case "getReferenceById": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(
                        args.length > 0 ? args[0] : null, idColumn, null, resolveVariables, dialect);
                List<String> notes;
                if (methodName.equals("getReferenceById")) {
                    notes = List.of("getReferenceById：延迟代理，默认不发起 SQL（首次访问属性才按主键加载）");
                } else if (methodName.equals("getOne") || methodName.equals("getById")) {
                    notes = List.of(methodName + "：已废弃（Deprecated），迁移 getReferenceById（同为延迟代理语义）");
                } else {
                    notes = List.of();
                }
                build = new Build(selectSql(table, dialect, condition.where), condition.params, notes);
                break;
            }
            case "findAll":
                build = new Build(selectSql(table, dialect, null));
                break;
            case "findAllById": {
                if (idColumn == null) return null;
                build = new Build(selectSql(table, dialect,
                        dialect.quoteIfKeyword(idColumn.getColumnName()) + " IN (?, ?, ...)"));
                break;
            }
            case "existsById": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(
                        args.length > 0 ? args[0] : null, idColumn, null, resolveVariables, dialect);
                build = new Build(
                        "SELECT 1\nFROM " + dialect.quoteIfKeyword(table.getTableName())
                                + "\nWHERE " + condition.where + "\n" + dialect.renderLimit(new LimitClause(null, 1L)),
                        condition.params,
                        List.of("existsById：命中即真语义（按当前方言只取首行，运行时语句以实际日志为准）"));
                break;
            }
            case "count":
                build = new Build("SELECT COUNT(*)\nFROM " + dialect.quoteIfKeyword(table.getTableName()));
                break;
            case "deleteById": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(
                        args.length > 0 ? args[0] : null, idColumn, null, resolveVariables, dialect);
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + condition.where,
                        condition.params,
                        List.of("deleteById：逐实体 EntityManager.remove 语义（非 bulk DELETE）"));
                break;
            }
            case "delete": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + condition.where,
                        condition.params,
                        List.of("delete(entity)：EntityManager.remove 语义，按实体主键删除"));
                break;
            }
            case "deleteAllById": {
                if (idColumn == null) return null;
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE "
                                + dialect.quoteIfKeyword(idColumn.getColumnName()) + " IN (?, ?, ...)",
                        List.of(),
                        List.of("deleteAllById：逐实体 remove 语义（集合每元素一条 DELETE）"));
                break;
            }
            case "deleteAll":
                if (args.length == 0) {
                    build = new Build(
                            "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()),
                            List.of("deleteAll()：逐实体 remove 语义的空表快捷形态——无 WHERE 全表删除"),
                            BLOCK_ATTACK_WARNING);
                } else {
                    if (idColumn == null) return null;
                    IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                    build = new Build(
                            "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + condition.where,
                            condition.params,
                            List.of("deleteAll(Iterable)：逐实体 remove 语义（集合每元素一条 DELETE）"));
                }
                break;
            case "deleteAllInBatch":
                if (args.length == 0) {
                    build = new Build(
                            "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()),
                            List.of("deleteAllInBatch()：唯一 bulk DELETE 形态（单条语句、绕过级联与回调）"),
                            BLOCK_ATTACK_WARNING);
                } else {
                    if (idColumn == null) return null;
                    build = new Build(
                            "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE "
                                    + dialect.quoteIfKeyword(idColumn.getColumnName()) + " IN (?, ?, ...)",
                            List.of(),
                            List.of("deleteAllInBatch(Iterable)：bulk DELETE（单条 IN 语句、绕过级联与回调）"));
                }
                break;
            case "deleteAllByIdInBatch": {
                if (idColumn == null) return null;
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE "
                                + dialect.quoteIfKeyword(idColumn.getColumnName()) + " IN (?, ?, ...)",
                        List.of(),
                        List.of("deleteAllByIdInBatch：bulk DELETE（单条 IN 语句、绕过级联与回调）"));
                break;
            }
            case "deleteInBatch": {
                if (idColumn == null) return null;
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE "
                                + dialect.quoteIfKeyword(idColumn.getColumnName()) + " IN (?, ?, ...)",
                        List.of(),
                        List.of("deleteInBatch：已废弃（Deprecated），迁移 deleteAllInBatch（同为 bulk DELETE）"));
                break;
            }
            case "flush":
                build = new Build("", List.of(),
                        List.of("flush()：无独立 SQL——脏检查批量刷写，将挂起的 INSERT/UPDATE/DELETE 一次性落库"));
                break;
            default:
                return null;
        }
        return build;
    }

    // ------------------------------------------------------------------
    // T-J6：EntityManager 写/单查
    // ------------------------------------------------------------------

    private static JpaCrudPreview convertEntityManagerCall(
            PsiMethodCallExpression call, String methodName, boolean resolveVariables) {
        if (!ENTITY_MANAGER_METHODS.contains(methodName)) return null;
        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        PsiExpression[] args = call.getArgumentList().getExpressions();

        // 存储过程（T-J16 基础形态）：过程名直传 + ? 占位（@StoredProcedureParameter 静态不可得）
        if (methodName.equals("createStoredProcedureQuery") || methodName.equals("createNamedStoredProcedureQuery")) {
            String procName = null;
            if (args.length > 0 && args[0] instanceof PsiLiteralExpression literal
                    && literal.getValue() instanceof String literalText) {
                procName = literalText;
            }
            if (procName == null && args.length > 0 && args[0].getText() != null) {
                procName = args[0].getText();
            }
            if (procName == null) return null;
            return new JpaCrudPreview(methodName, procName,
                    "CALL " + procName + "(?)\n-- 存储过程参数：@StoredProcedureParameter 的名称/方向/类型静态不可得，"
                            + "? 占位请按存储过程实际定义回填");
        }

        if (methodName.equals("flush")) {
            return previewOf(methodName, null, new Build("", List.of(),
                    List.of("flush()：无独立 SQL——脏检查批量刷写，将挂起的 INSERT/UPDATE/DELETE 一次性落库")));
        }

        // 其余操作均需实体（Class 字面量实参或实体类型实参，isJpaMappedEntity 负例守卫）
        EntityArg entity = resolveEntityArg(call);
        if (entity == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entity.psiClass);
        if (table.getEntityClassName().isBlank()) return null;
        ColumnInfo idColumn = firstIdColumn(table);
        String prefix = entity.argText;

        Build build;
        switch (methodName) {
            case "persist":
                build = insertBuild(table, prefix, dialect);
                break;
            case "merge": {
                if (idColumn == null) return null;
                List<ColumnInfo> setColumns = columnsExceptId(table, idColumn);
                String setSql = joinSetSql(setColumns, dialect);
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                List<ParamEntry> params = new ArrayList<>();
                for (ColumnInfo column : setColumns) {
                    params.add(new ParamEntry(idSource(prefix, column.getColumnName()),
                            ParamQuote.hint(column.getFieldType())));
                }
                params.addAll(condition.params);
                build = new Build(
                        "UPDATE " + dialect.quoteIfKeyword(table.getTableName()) + "\nSET " + setSql
                                + "\nWHERE " + condition.where,
                        params,
                        List.of("merge：托管/detached 实体 → UPDATE；new 实体（无主键）→ INSERT（静态按 UPDATE 呈现，二义性标注）"));
                break;
            }
            case "remove": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + condition.where,
                        condition.params, List.of());
                break;
            }
            case "find":
            case "getReference": {
                if (idColumn == null) return null;
                PsiExpression idExpr = args.length > 1 ? args[1] : null;
                IdConditionResult condition = idCondition(idExpr, idColumn, null, resolveVariables, dialect);
                List<String> notes = methodName.equals("getReference")
                        ? List.of("getReference：延迟代理，默认不发起 SQL（首次访问属性才按主键加载）")
                        : List.of();
                build = new Build(selectSql(table, dialect, condition.where), condition.params, notes);
                break;
            }
            case "refresh": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                build = new Build(selectSql(table, dialect, condition.where), condition.params,
                        List.of("refresh：按主键重查并覆盖实体内存状态（DB 侧为普通 SELECT）"));
                break;
            }
            default:
                return null;
        }
        return previewOf(methodName, table, build);
    }

    // ------------------------------------------------------------------
    // T-J21：HibernateTemplate 实体操作 / QBC
    // ------------------------------------------------------------------

    private static JpaCrudPreview convertHibernateTemplateCall(
            PsiMethodCallExpression call, String methodName, boolean resolveVariables) {
        DaoQueryKind kind = CustomDaoHqlLocator.classifyHibernateTemplateMethod(methodName);
        // QBC 形态（executeFind / findByCriteria）：DetachedCriteria 条件树运行时构建，静态不可解析 → 可读占位
        if (kind == DaoQueryKind.QBC || methodName.equals("executeFind") || methodName.equals("findByCriteria")) {
            return qbcPlaceholder(methodName);
        }
        // HQL / NAMED_QUERY 字符串承载 → 交回 previewCustomDaoHql 管道（本转换器不认领）
        if (kind != DaoQueryKind.ENTITY_OP) return null;

        Dialect dialect = PsiDialect.currentDialect(call.getProject());
        PsiExpression[] args = call.getArgumentList().getExpressions();

        // 实体操作均需实体（Class 字面量实参或实体类型实参，isJpaMappedEntity 负例守卫）
        EntityArg entity = resolveEntityArg(call);
        if (entity == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entity.psiClass);
        if (table.getEntityClassName().isBlank()) return null;
        ColumnInfo idColumn = firstIdColumn(table);
        String prefix = entity.argText;

        Build build;
        switch (methodName) {
            case "get": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(
                        args.length > 1 ? args[1] : null, idColumn, null, resolveVariables, dialect);
                build = new Build(selectSql(table, dialect, condition.where), condition.params, List.of());
                break;
            }
            case "load": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(
                        args.length > 1 ? args[1] : null, idColumn, null, resolveVariables, dialect);
                build = new Build(selectSql(table, dialect, condition.where), condition.params,
                        List.of("load：延迟代理，默认不发起 SQL（首次访问属性才按主键加载）"));
                break;
            }
            case "loadAll":
                build = new Build(selectSql(table, dialect, null),
                        List.of("loadAll(Class)：等价 find(\"from Entity\") 全量加载；元素默认为延迟代理"),
                        null);
                break;
            case "save":
            case "persist":
                build = insertBuild(table, prefix, dialect);
                break;
            case "saveOrUpdate":
                build = insertBuild(table, prefix, dialect).withNote(
                        "saveOrUpdate：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）");
                break;
            case "merge": {
                if (idColumn == null) return null;
                List<ColumnInfo> setColumns = columnsExceptId(table, idColumn);
                String setSql = joinSetSql(setColumns, dialect);
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                List<ParamEntry> params = new ArrayList<>();
                for (ColumnInfo column : setColumns) {
                    params.add(new ParamEntry(idSource(prefix, column.getColumnName()),
                            ParamQuote.hint(column.getFieldType())));
                }
                params.addAll(condition.params);
                build = new Build(
                        "UPDATE " + dialect.quoteIfKeyword(table.getTableName()) + "\nSET " + setSql
                                + "\nWHERE " + condition.where,
                        params,
                        List.of("merge：detached → UPDATE；new 实体 → INSERT（静态按 UPDATE 呈现，二义性标注）"));
                break;
            }
            case "update":
            case "replicate": {
                if (idColumn == null) return null;
                List<ColumnInfo> setColumns = columnsExceptId(table, idColumn);
                String setSql = joinSetSql(setColumns, dialect);
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                List<ParamEntry> params = new ArrayList<>();
                for (ColumnInfo column : setColumns) {
                    params.add(new ParamEntry(idSource(prefix, column.getColumnName()),
                            ParamQuote.hint(column.getFieldType())));
                }
                params.addAll(condition.params);
                List<String> notes = methodName.equals("replicate")
                        ? List.of("replicate：按指定复制模式全量覆写持久态（静态按全列 UPDATE 呈现）")
                        : List.of();
                build = new Build(
                        "UPDATE " + dialect.quoteIfKeyword(table.getTableName()) + "\nSET " + setSql
                                + "\nWHERE " + condition.where,
                        params, notes);
                break;
            }
            case "delete": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + condition.where,
                        condition.params, List.of());
                break;
            }
            case "deleteAll": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                build = new Build(
                        "DELETE FROM " + dialect.quoteIfKeyword(table.getTableName()) + "\nWHERE " + condition.where,
                        condition.params,
                        List.of("deleteAll：逐实体 remove 语义（集合每元素一条 DELETE）"));
                break;
            }
            case "refresh": {
                if (idColumn == null) return null;
                IdConditionResult condition = idCondition(null, idColumn, prefix, resolveVariables, dialect);
                build = new Build(selectSql(table, dialect, condition.where), condition.params,
                        List.of("refresh：按主键重查并覆盖实体内存状态（DB 侧为普通 SELECT）"));
                break;
            }
            case "lock":
                build = new Build("", List.of("lock：仅申请锁模式（无独立 SQL；锁语义归 J15）"), null);
                break;
            case "findByExample":
                build = new Build(
                        "SELECT " + selectColumnList(table, dialect) + "\nFROM "
                                + dialect.quoteIfKeyword(table.getTableName())
                                + "\nWHERE <Example 非空属性等值条件: col = ? AND col = ? ...>",
                        List.of(),
                        List.of("findByExample：以实体非空属性为等值条件（NullHandler 剔除 null 属性），条件列运行时确定"));
                break;
            default:
                return null;
        }
        return previewOf(methodName, table, build);
    }

    /** QBC/DetachedCriteria 可读占位（条件树运行时构建，静态不可解析）。 */
    private static JpaCrudPreview qbcPlaceholder(String methodName) {
        return new JpaCrudPreview(methodName, "",
                "-- QBC/DetachedCriteria 形态暂不支持静态解析（条件树运行时构建）");
    }

    // ------------------------------------------------------------------
    // 模板构建辅助
    // ------------------------------------------------------------------

    /** SELECT 全列模板（列清单为空时降级 `*`）。 */
    private static String selectSql(TableInfo table, Dialect dialect, String where) {
        String base = "SELECT " + selectColumnList(table, dialect)
                + "\nFROM " + dialect.quoteIfKeyword(table.getTableName());
        return where != null ? base + "\nWHERE " + where : base;
    }

    private static String selectColumnList(TableInfo table, Dialect dialect) {
        StringBuilder columns = new StringBuilder();
        for (ColumnInfo column : table.getColumns()) {
            if (columns.length() > 0) columns.append(", ");
            columns.append(dialect.quoteIfKeyword(column.getColumnName()));
        }
        return columns.length() == 0 ? "*" : columns.toString();
    }

    /**
     * INSERT 模板（JPA/Hibernate 语义，见类 KDoc）：
     *  - IDENTITY 主键不进列清单（注释说明）；
     *  - SEQUENCE/TABLE 主键保留 `?` 占位（注释说明前置取值）；
     *  - `@Column(insertable = false)` 列不进列清单。
     */
    private static Build insertBuild(TableInfo table, String prefix, Dialect dialect) {
        ColumnInfo idColumn = firstIdColumn(table);
        String strategy = idColumn != null && idColumn.getGeneratedValueStrategy() != null
                ? idColumn.getGeneratedValueStrategy().toUpperCase(Locale.ROOT)
                : null;
        List<ColumnInfo> insertColumns = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) {
            if (!column.isInsertable()) continue;
            if (column.isId() && "IDENTITY".equals(strategy)) continue;
            insertColumns.add(column);
        }
        List<BatchColumn> toggleColumns = new ArrayList<>();
        for (ColumnInfo column : insertColumns) {
            toggleColumns.add(new BatchColumn(column.getColumnName(),
                    idSource(prefix, column.getColumnName()),
                    ParamQuote.decide(column.getFieldType()), true, false, null,
                    ParamQuote.isTemporal(column.getFieldType())));
        }
        Set<String> active = new LinkedHashSet<>();
        for (BatchColumn toggleColumn : toggleColumns) {
            active.add(toggleColumn.getColumnName());
        }
        BatchModel model = new BatchModel(BatchKind.INSERT, table.getTableName(), toggleColumns).copy(dialect);
        List<String> notes = new ArrayList<>();
        if ("IDENTITY".equals(strategy) && idColumn != null) {
            notes.add("主键 " + idColumn.getColumnName() + "：@GeneratedValue(IDENTITY) 数据库自增，不进 INSERT 列清单");
        }
        if ("SEQUENCE".equals(strategy) && idColumn != null) {
            notes.add("主键 " + idColumn.getColumnName()
                    + "：@GeneratedValue(SEQUENCE)——INSERT 前置取值（select nextval），占位 ? 由应用回填");
        }
        if ("TABLE".equals(strategy) && idColumn != null) {
            notes.add("主键 " + idColumn.getColumnName()
                    + "：@GeneratedValue(TABLE)——INSERT 前置访问序列表取值（hi/lo），占位 ? 由应用回填");
        }
        List<ParamEntry> params = new ArrayList<>();
        for (BatchColumn column : model.orderedColumns(active)) {
            params.add(new ParamEntry(column.getSource(),
                    new ParamHint(column.getQuote(), null, false, column.isTemporal())));
        }
        List<String> insertColumnNames = new ArrayList<>();
        for (ColumnInfo column : insertColumns) {
            insertColumnNames.add(column.getColumnName());
        }
        return new Build(model.renderSql(active), params,
                batchParamGroups(insertColumnNames, prefix), notes, null, model);
    }

    private static List<String> buildInsertColumnNames(TableInfo table) {
        List<String> names = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) {
            if (column.isInsertable()) names.add(column.getColumnName());
        }
        return names;
    }

    /**
     * 主键条件渲染：实参可静态求值（字面量/可追踪变量）→ 内联；否则 `?` + 参数行；
     * 无实参（实体主键形态 delete(entity)/remove(entity)）→ `?` + `实体.主键列` 参数行。
     */
    private static IdConditionResult idCondition(
            PsiExpression idExpr, ColumnInfo idColumn, String entityPrefix,
            boolean resolveVariables, Dialect dialect) {
        String idName = dialect.quoteIfKeyword(idColumn.getColumnName());
        if (idExpr != null) {
            SqlValue value = resolveVariables
                    ? ExpressionEvaluator.evaluate(idExpr)
                    : new SqlValue.Placeholder(idExpr.getText());
            if (value instanceof SqlValue.Literal literal) {
                return new IdConditionResult(idName + " = " + literal.getRaw(), List.of());
            }
            if (value instanceof SqlValue.Placeholder placeholder) {
                return new IdConditionResult(idName + " = ?", List.of(new ParamEntry(
                        placeholder.getSource(),
                        ParamQuote.hint(idColumn.getFieldType()))));
            }
        }
        return new IdConditionResult(idName + " = ?", List.of(new ParamEntry(
                idSource(entityPrefix != null ? entityPrefix : "", idColumn.getColumnName()),
                ParamQuote.hint(idColumn.getFieldType()))));
    }

    /** 主键条件渲染结果（where 文本 + 参数行）。 */
    private static final class IdConditionResult {
        final String where;
        final List<ParamEntry> params;

        IdConditionResult(String where, List<ParamEntry> params) {
            this.where = where;
            this.params = params;
        }
    }

    /** 实体/集合参数前缀：首实参文本（`save(entity)` → `entity`）；无实参 → qualifier 文本；均无 → 空串。 */
    private static String columnParamPrefix(PsiMethodCallExpression call) {
        PsiExpression[] expressions = call.getArgumentList().getExpressions();
        if (expressions.length > 0 && expressions[0].getText() != null) {
            return expressions[0].getText();
        }
        if (call.getMethodExpression().getQualifierExpression() instanceof PsiReferenceExpression qualifier
                && qualifier.getText() != null) {
            return qualifier.getText();
        }
        return "";
    }

    /** 参数组说明（对齐 MP 批量面板惯例）：`-- 实际参数: [col=?, ...] × N`。 */
    private static String batchParamGroups(List<String> columns, String prefix) {
        StringBuilder group = new StringBuilder();
        for (String column : columns) {
            if (group.length() > 0) group.append(", ");
            group.append(column).append("=?");
        }
        return "-- 实际参数: [" + group + "] × N（每行一组，运行时值不可静态获取）";
    }

    /** 实体字段参数来源：`实体变量.列名`；无前缀时仅列名。 */
    private static String idSource(String prefix, String columnName) {
        return prefix == null || prefix.isEmpty() ? columnName : prefix + "." + columnName;
    }

    /** Build → JpaCrudPreview：SQL 尾部追加语义注释行（note-only 时 SQL 即注释卡）。 */
    private static JpaCrudPreview previewOf(String methodName, TableInfo table, Build build) {
        String sql;
        if (build.sql == null || build.sql.isBlank()) {
            StringBuilder notes = new StringBuilder();
            for (String note : build.notes) {
                if (notes.length() > 0) notes.append("\n");
                notes.append("-- ").append(note);
            }
            sql = notes.toString();
        } else if (build.notes.isEmpty()) {
            sql = build.sql;
        } else {
            StringBuilder withNotes = new StringBuilder(build.sql);
            for (String note : build.notes) {
                withNotes.append("\n-- ").append(note);
            }
            sql = withNotes.toString();
        }
        List<String> paramSources = new ArrayList<>();
        List<ParamHint> paramHints = new ArrayList<>();
        for (ParamEntry param : build.params) {
            paramSources.add(param.source);
            paramHints.add(param.hint);
        }
        return new JpaCrudPreview(methodName,
                table != null ? table.getTableName() : "",
                sql,
                build.paramGroups,
                paramSources,
                paramHints,
                build.warning,
                build.model);
    }

    // ------------------------------------------------------------------
    // 实体实参解析（T-J6 / T-J21：Class 字面量实参 / 实体类型实参）
    // ------------------------------------------------------------------

    /** 实体实参解析结果（psiClass + 实参文本，实参文本作参数行前缀 `entity.列名`）。 */
    private static final class EntityArg {
        final PsiClass psiClass;
        final String argText;

        EntityArg(PsiClass psiClass, String argText) {
            this.psiClass = psiClass;
            this.argText = argText;
        }
    }

    /**
     * 从调用实参解析实体类：
     *  1. Class 字面量实参（`em.find(UserEntity.class, id)` / `ht.get(UserEntity.class, id)` / `loadAll(UserEntity.class)`）；
     *  2. 实体类型实参（`persist(entity)` / `remove(entity)`，[isJpaMappedEntity] 负例守卫——
     *     String/Long 等实参类型不误判）。
     * 两路均失败返回 null（不识别）。
     */
    private static EntityArg resolveEntityArg(PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        for (PsiExpression arg : args) {
            if (!(arg instanceof PsiClassObjectAccessExpression classAccess)) continue;
            PsiClass cls = classAccess.getOperand().getType() instanceof PsiClassType classType
                    ? classType.resolve() : null;
            if (cls == null) continue;
            if (isJpaMappedEntity(cls)) {
                return new EntityArg(cls, args.length > 1 && args[1].getText() != null ? args[1].getText() : "");
            }
        }
        for (PsiExpression arg : args) {
            PsiClass cls = arg.getType() instanceof PsiClassType classType ? classType.resolve() : null;
            if (cls == null) continue;
            if (isJpaMappedEntity(cls)) {
                return new EntityArg(cls, arg.getText());
            }
        }
        return null;
    }

    /**
     * JPA 实体映射守卫（负例）：@Entity / @Table 注解（jakarta/javax 双包名 + 短名兜底）
     * 至少其一存在才视为 JPA 实体——`em.find(SomeDto.class, id)` / `ht.save(string)` 不出 SQL。
     */
    private static boolean isJpaMappedEntity(PsiClass cls) {
        String qualifiedName = cls.getQualifiedName();
        if (qualifiedName == null) return false;
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return false;
        for (PsiAnnotation annotation : cls.getAnnotations()) {
            String qName = annotation.getQualifiedName();
            String shortName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
            if (JPA_ENTITY_ANNOTATIONS.contains(qName) || JPA_TABLE_ANNOTATIONS.contains(qName)
                    || "Entity".equals(shortName) || "Table".equals(shortName)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> JPA_ENTITY_ANNOTATIONS =
            Set.of("jakarta.persistence.Entity", "javax.persistence.Entity");
    private static final Set<String> JPA_TABLE_ANNOTATIONS =
            Set.of("jakarta.persistence.Table", "javax.persistence.Table");

    // ------------------------------------------------------------------
    // 通用辅助
    // ------------------------------------------------------------------

    private static ColumnInfo firstIdColumn(TableInfo table) {
        for (ColumnInfo column : table.getColumns()) {
            if (column.isId()) return column;
        }
        return null;
    }

    private static List<ColumnInfo> columnsExceptId(TableInfo table, ColumnInfo idColumn) {
        List<ColumnInfo> result = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) {
            if (!java.util.Objects.equals(column.getColumnName(), idColumn.getColumnName())) {
                result.add(column);
            }
        }
        return result;
    }

    private static String joinSetSql(List<ColumnInfo> setColumns, Dialect dialect) {
        StringBuilder setSql = new StringBuilder();
        for (ColumnInfo column : setColumns) {
            if (setSql.length() > 0) setSql.append(", ");
            setSql.append(dialect.quoteIfKeyword(column.getColumnName())).append(" = ?");
        }
        return setSql.toString();
    }

    private static List<String> appendNote(List<String> notes, String note) {
        List<String> appended = new ArrayList<>(notes);
        appended.add(note);
        return appended;
    }

    private static String substringBefore(String text, char c) {
        if (text == null) return "";
        int index = text.indexOf(c);
        return index >= 0 ? text.substring(0, index) : text;
    }

    private static String substringAfterLast(String text, char c) {
        if (text == null) return "";
        int index = text.lastIndexOf(c);
        return index >= 0 ? text.substring(index + 1) : text;
    }

    private static boolean startsWithAny(String text, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }
}
