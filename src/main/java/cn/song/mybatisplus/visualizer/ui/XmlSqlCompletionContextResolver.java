package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML/注解 SQL 补全上下文解析器（T-M27-02，M27 架构 §3.2）：
 *
 * <p><b>主实体链路</b>（优先级从高到低，对齐 M26-B XmlEwResolver 的 namespace→接口关联链路）：
 * 1. <b>Mapper 接口泛型基类实参</b>：XML 场景向上找根 {@code <mapper>} 的 namespace →
 *    {@link JavaPsiFacade#findClass}（XmlEwResolver 同款）；注解场景 method.containingClass 即
 *    Mapper 接口。沿接口继承链（DFS + 防环）找全限定名后缀 {@code BaseMapper}/{@code MPJBaseMapper}
 *    的基类，取 PsiClassType 泛型实参解析出的实体类；
 * 2. <b>语句方法实体参数</b>：XML 场景按语句 id → {@link PsiClass#findMethodsByName} 首方法；注解
 *    场景即 method 本身。取参数列表第一个<b>实体类型</b>参数——PsiClassType 可解析为 PsiClass，
 *    且简单名不在 Wrapper 族 / 基本类型包装 / String / Collection / Map / Page / IPage 集合中；
 * 3. §二十六：接口/泛型都没解析到时，用 SQL 里的 FROM/JOIN 表名反查实体（MBG 风格 mapper 最常见）；
 * 4. 均失败 → 主表为 null（降级只补关键字/标签）。
 *
 * <p><b>@TableName 实体候选</b>（JOIN 后表名补全）：注解类可解析时走官方
 * {@link AnnotatedElementsSearch#searchPsiClasses}；不可解析（测试沙盒/缺依赖）时兜底按<b>简单名</b>
 * 查注解 stub 索引 {@link JavaAnnotationIndex}，命中后按注解原文筛掉非本注解的同名者。
 * 查询失败/异常 → 空列表兜底（不抛异常）。</p>
 *
 * <p><b>参数名</b>：复用 {@link AnnotationSqlPreviewService#paramTypes}（@Param 值优先 + 实际名 +
 * param1..N，对齐 ParamNameResolver 语义）；方法找不到 → 空列表。</p>
 *
 * <p>T-StartupReadLockFreeze 系列增量（§十九/§二十/§二十五/§二十九/§三十五）：候选 TTL 缓存 +
 * 预算 + 后台预热（warmCandidates）、实体解析按类正文指纹缓存、Example 参数类按 MBG 命名约定
 * 回查同包实体、索引期不解析联表候选。</p>
 */
public final class XmlSqlCompletionContextResolver {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(XmlSqlCompletionContextResolver.class);

    /** @TableName 注解全限定名（真实 MP 注解，libs.mp.core 提供）。 */
    private static final String TABLE_NAME_ANNOTATION = "com.baomidou.mybatisplus.annotation.TableName";

    /**
     * @TableName 的<b>简单名</b>——注解 stub 索引的键就是它（不是 FQN）。
     *
     * <p>243 {@code JavaAnnotationElementType.indexStub} 字节码实证：键＝{@code getReferenceShortName(text)}，
     * 该方法先 {@code indexOf('(')} 截断再取短类名 ⇒ 属性名/属性值都进不了索引，键恒为 TableName。
     * 因此<b>按 FQN 查这个索引永远查不到</b>（该兜底曾恒空，20261113 修）。</p>
     */
    private static final String TABLE_NAME_ANNOTATION_SIMPLE_NAME = "TableName";

    /** 联表候选上限（防大项目候选爆炸，M27 §3.2）。 */
    private static final int CANDIDATES_CAP = 50;

    /** 候选解析预算（T-StartupReadLockFreeze §十九）：超预算即停，剩余候选等下次缓存重建。 */
    public static volatile long candidatesBudgetMs = 400L;

    /** 候选缓存 TTL（§二十）：候选只服务补全提示，容忍 2 分钟陈旧，换掉被自写文档顶掉的版本判据。 */
    public static volatile long candidatesTtlMs = 120_000L;

    /** 测试缝：强制冷态口径（null＝按环境判定）。 */
    public static volatile Boolean candidatesBlockingBuildForTest = null;

    /** 测试缝：表名 → 实体类（Light 沙盒里 EntityClassFinder 依赖索引、拿不到；生产恒 null）。 */
    @Nullable
    public static volatile BiFunction<Project, String, PsiClass> entityFinderForTest = null;

    /** 测试缝：候选实体来源（Light 沙盒拿不到 @TableName 注解索引；生产恒 null）。 */
    @Nullable
    public static volatile Function<Project, List<PsiClass>> tableNameEntitiesProviderForTest = null;

    /** 测试缝：真正执行 EntityResolver.resolve 的次数（验证缓存命中用）。 */
    private static final AtomicInteger ENTITY_RESOLVE_RUNS = new AtomicInteger(0);

    /** 测试缝：缓存命中次数（探针用）。 */
    private static final AtomicInteger CANDIDATES_CACHE_HITS = new AtomicInteger(0);

    /** 测试缝：真正执行 EntityResolver.resolve 的实体数（上限断言的直接证据）。 */
    private static final AtomicInteger ENTITIES_RESOLVED = new AtomicInteger(0);

    /** 非实体参数简单名集合：MP Wrapper 族 + 基本类型包装 + 常见非实体类型（简单名匹配）。 */
    private static final Set<String> NON_ENTITY_SIMPLE_NAMES = Set.of(
            // MP Wrapper 族（简单名匹配——测试环境类型未解析场景 canonicalText 为短名同样生效）
            "LambdaQueryWrapper", "QueryWrapper", "UpdateWrapper", "LambdaUpdateWrapper",
            "LambdaDeleteWrapper", "MPJLambdaWrapper", "MPJQueryWrapper", "DeleteWrapper", "JoinWrapper",
            // 防御补充：Wrapper 抽象基类同样非实体（XmlEwResolverTest 的 `Wrapper ew` 参数场景）
            "Wrapper", "AbstractWrapper",
            // 基本类型包装 / 常见非实体类型
            "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character",
            "Object", "List", "Set", "Collection", "Map", "Page", "IPage"
    );

    /** §二十六：接口/泛型解析不到时的兜底表名锚（FROM/JOIN 后的第一段标识符）。 */
    private static final Pattern FROM_OR_JOIN_TABLE = Pattern.compile("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** §二十九：实体解析缓存（键 = 类限定名，值 = 类正文指纹 + 解析结果）。 */
    private static final Map<String, EntityInfoEntry> ENTITY_INFO_CACHE = new ConcurrentHashMap<>();

    /** 候选缓存（键 = project.locationHash）。 */
    private static final Map<String, CandidatesEntry> CANDIDATES_CACHE = new ConcurrentHashMap<>();

    public static final XmlSqlCompletionContextResolver INSTANCE = new XmlSqlCompletionContextResolver();

    private XmlSqlCompletionContextResolver() {
    }

    /** 实体解析缓存条目：类正文指纹（PSI 重建不误 miss，真改注解/字段不误 hit）。 */
    private record EntityInfoEntry(int signature, TableInfo table) {
    }

    /** 候选缓存条目：TTL 失效。 */
    private record CandidatesEntry(long createdAt, List<TableInfo> tables) {
    }

    /** 测试缝：清空实体/候选缓存与计数。 */
    public static void resetEntityCacheForTest() {
        ENTITY_INFO_CACHE.clear();
        ENTITY_RESOLVE_RUNS.set(0);
    }

    /** 测试缝：清空候选缓存。 */
    public static void clearCandidatesCacheForTest() {
        CANDIDATES_CACHE.clear();
        CANDIDATES_CACHE_HITS.set(0);
        ENTITIES_RESOLVED.set(0);
    }

    /** 测试缝读数：真实 resolve 执行次数。 */
    public static int getEntityResolveRuns() {
        return ENTITY_RESOLVE_RUNS.get();
    }

    /** 测试缝读数：候选缓存命中次数。 */
    public static int getCandidatesCacheHits() {
        return CANDIDATES_CACHE_HITS.get();
    }

    /** 测试缝读数：真正执行 resolve 的实体数。 */
    public static int getEntitiesResolved() {
        return ENTITIES_RESOLVED.get();
    }

    /**
     * 解析 XML 语句标签的补全上下文。
     *
     * @param project      当前项目（findClass/注解索引/EntityResolver 均需）
     * @param statementTag {@code <select|insert|update|delete>} 语句标签（根 {@code <mapper>} 的 namespace
     *                     经 {@link XmlFile#getRootTag()} 向上获取；id 属性即语句 id）
     * @return {@link SourceSqlContext}；任何环节失败均降级（main=null/空候选/空参数名），不抛异常
     */
    @NotNull
    public SourceSqlContext resolveXml(@NotNull Project project, @NotNull XmlTag statementTag) {
        return resolveXml(project, statementTag, true);
    }

    /**
     * 解析 XML 语句标签的补全上下文。
     *
     * @param includeCandidates T-StartupReadLockFreeze §二十（2026-11-13 15:31 真机）：是否解析
     *                          <b>联表候选</b>（全项目 @TableName 实体逐个 EntityResolver.resolve）。
     *                          预览打开一律传 false——实测候选段 2~3s 且最终只得到 1 个候选；
     *                          候选只服务 JOIN 表名补全，等用户真正触发补全时再算（默认 true）。
     */
    @NotNull
    public SourceSqlContext resolveXml(@NotNull Project project,
                                       @NotNull XmlTag statementTag,
                                       boolean includeCandidates) {
        // 向上找根 <mapper> tag 的 namespace（与 XmlEwResolver 的 namespace 来源一致）
        String namespace = null;
        if (statementTag.getContainingFile() instanceof XmlFile xmlFile && xmlFile.getRootTag() != null) {
            namespace = xmlFile.getRootTag().getAttributeValue("namespace");
        }
        PsiClass mapperClass = null;
        if (namespace != null && !namespace.isEmpty()) {
            mapperClass = JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project));
        }
        PsiMethod method = null;
        String id = statementTag.getAttributeValue("id");
        if (id != null && mapperClass != null) {
            PsiMethod[] methods = mapperClass.findMethodsByName(id, false);
            if (methods.length > 0) method = methods[0];
        }
        // §二十六：mapper 接口/泛型实体解析不到时的兜底——直接从语句文本取 FROM/JOIN 表名，
        // 按表名反查实体（MBG 风格 mapper、沙盒没 Load Maven 时最常见）。
        String fallbackTable = null;
        try {
            // 注意：这里的 `\b` 必须是正则词界。上一版经脚本写入时被写成了退格符（U+0008），
            // 正则变成「退格 + from|join」，永远不匹配 ⇒ 兜底恒为 null（已修，勿再用脚本写这段）。
            Matcher matcher = FROM_OR_JOIN_TABLE.matcher(statementTag.getValue().getText());
            if (matcher.find()) {
                fallbackTable = matcher.group(1);
            }
        } catch (RuntimeException ignored) {
            // 兜底是尽力而为：取不到表名就退回既有链
        }
        return resolveCommon(project, mapperClass, method, includeCandidates, fallbackTable);
    }

    /**
     * 解析注解 SQL 方法（@Select/@Insert/... 所在 Mapper 方法）的补全上下文。
     * 所在接口即 Mapper 接口，主实体链路与 XML 场景一致（无 namespace 环节）。
     *
     * @param project 当前项目
     * @param method  Mapper 接口上的 SQL 注解方法
     */
    @NotNull
    public SourceSqlContext resolveAnnotation(@NotNull Project project, @NotNull PsiMethod method) {
        return resolveCommon(project, method.getContainingClass(), method, true, null);
    }

    // ------------------------------------------------------------------
    // 主实体链路
    // ------------------------------------------------------------------

    /** 主实体解析（优先级从高到低）：① Mapper 泛型基类实参 ② 方法实体参数 ③ SQL 表名反查；均失败 → null。 */
    private SourceSqlContext resolveCommon(@NotNull Project project,
                                           @Nullable PsiClass mapperClass,
                                           @Nullable PsiMethod method,
                                           boolean includeCandidates,
                                           @Nullable String fallbackTableName) {
        long t0 = System.currentTimeMillis();
        PsiClass mainEntity = findMainEntity(mapperClass, method);
        long t1 = System.currentTimeMillis();
        TableInfo resolvedTable = mainEntity != null ? resolveEntityCached(project, mainEntity) : null;
        if (resolvedTable != null && (resolvedTable.getTableName() == null || resolvedTable.getTableName().isEmpty())) {
            resolvedTable = null;
        }
        // §二十六：接口/泛型都没解析到时，用 SQL 里的表名反查实体（表名 → @TableName/类名约定）
        TableInfo mainTable = resolvedTable;
        if (resolvedTable == null && fallbackTableName != null) {
            try {
                PsiClass clazz;
                if (entityFinderForTest != null) {
                    clazz = entityFinderForTest.apply(project, fallbackTableName);
                } else {
                    List<PsiClass> found = new cn.song.mybatisplus.visualizer.psi.EntityClassFinder(project)
                            .find(fallbackTableName);
                    clazz = found.isEmpty() ? null : found.get(0);
                }
                if (clazz != null) {
                    TableInfo viaFallback = resolveEntityCached(project, clazz);
                    if (viaFallback != null && viaFallback.getTableName() != null
                            && !viaFallback.getTableName().isEmpty()) {
                        mainTable = viaFallback;
                    }
                }
            } catch (RuntimeException ignored) {
                // 反查失败 = 主表为 null（宁缺勿错）
            }
        }
        if (resolvedTable == null && mainTable != null) {
            LOG.info("[open-perf] ctx fallback: table=" + fallbackTableName + " 反查到实体 " + mainTable.getTableName());
        }
        long t2 = System.currentTimeMillis();
        List<TableInfo> candidates = includeCandidates ? buildCandidates(project, mainTable) : List.of();
        long t3 = System.currentTimeMillis();
        // §十九：分段埋点，下一次真机日志可直接读出「哪一段在吃时间」
        LOG.info("[open-perf] ctx detail: entity=" + (t1 - t0) + "ms resolve=" + (t2 - t1) + "ms "
                + "candidates=" + (t3 - t2) + "ms candidatesCount=" + candidates.size()
                + " include=" + includeCandidates);
        return new SourceSqlContext(
                mainTable != null ? buildMainContext(mainTable) : null,
                candidates,
                // T-PT-01：paramTypes 键序 = 命名参数序 + param1..N（与旧 paramNames 列表逐项一致）
                new ArrayList<>(AnnotationSqlPreviewService.INSTANCE.paramTypes(method).keySet())
        );
    }

    /** 主实体（优先级从高到低）：Mapper 泛型基类实参 → 语句方法实体参数。 */
    @Nullable
    private PsiClass findMainEntity(@Nullable PsiClass mapperClass, @Nullable PsiMethod method) {
        if (mapperClass != null) {
            PsiClass entity = resolveMapperGenericEntity(mapperClass, new HashSet<>());
            if (entity != null) return entity;
        }
        return findEntityParam(method);
    }

    /**
     * 沿 Mapper 接口继承链（DFS，visited 防环）找 {@code BaseMapper<X>} / {@code MPJBaseMapper<X>} 基类，
     * 返回泛型实参解析出的实体类；找不到/实参非实体 → null。
     */
    @Nullable
    private PsiClass resolveMapperGenericEntity(@NotNull PsiClass mapperClass, @NotNull Set<PsiClass> visited) {
        if (!visited.add(mapperClass)) return null;
        for (PsiClassType superType : mapperClass.getSuperTypes()) {
            PsiClass superClass = superType.resolve();
            if (superClass == null) continue;
            if (superClass instanceof PsiTypeParameter) continue;
            if (isMapperBase(superClass)) {
                PsiType[] parameters = superType.getParameters();
                if (parameters.length > 0 && parameters[0] instanceof PsiClassType) {
                    PsiClass entity = ((PsiClassType) parameters[0]).resolve();
                    if (entity != null && !(entity instanceof PsiTypeParameter)) return entity;
                }
            }
        }
        for (PsiClassType superType : mapperClass.getSuperTypes()) {
            PsiClass superClass = superType.resolve();
            if (superClass == null) continue;
            PsiClass result = resolveMapperGenericEntity(superClass, visited);
            if (result != null) return result;
        }
        return null;
    }

    /** 是否为 Mapper 族基类（全限定名后缀 BaseMapper / MPJBaseMapper）。 */
    private boolean isMapperBase(@NotNull PsiClass clazz) {
        String fqn = clazz.getQualifiedName();
        if (fqn == null) return false;
        return fqn.endsWith(".BaseMapper") || fqn.endsWith(".MPJBaseMapper");
    }

    /**
     * 语句方法参数中第一个实体类型参数。找不到 → null。
     */
    @Nullable
    private PsiClass findEntityParam(@Nullable PsiMethod method) {
        if (method == null) return null;
        for (PsiParameter param : method.getParameterList().getParameters()) {
            if (!(param.getType() instanceof PsiClassType)) continue;
            PsiClass clazz = ((PsiClassType) param.getType()).resolve();
            if (clazz == null) continue;
            if (clazz instanceof PsiTypeParameter) continue;
            String name = clazz.getName();
            if (name == null) continue;
            if (NON_ENTITY_SIMPLE_NAMES.contains(name)) continue;
            if (name.endsWith("Example")) {
                // T-StartupReadLockFreeze §二十五（2026-11-13 真机日志）：MBG 的 selectByExample(XxxExample)
                // 参数是**查询条件对象**，不是实体。原实现把它当实体 ⇒ 表名推成 cust_channel_example、
                // 列只有 Example 的 3 个字段（真机 `[completion] ctx: main=cust_channel_example columns=3`），
                // 列名补全于是给出完全错误的候选。
                // 按 MBG 命名约定回查同包实体 Xxx；查不到就跳过（宁可 main=null，也不拿 Example 当实体）。
                PsiClass exampleEntity = exampleEntityOf(clazz);
                if (exampleEntity != null) {
                    return exampleEntity;
                }
                continue;
            }
            return clazz;
        }
        return null;
    }

    /** {@code xxx.CustChannelExample} → 同包 {@code xxx.CustChannel}（MBG 生成器的固定命名对）。 */
    @Nullable
    private static PsiClass exampleEntityOf(@NotNull PsiClass exampleClass) {
        String qualified = exampleClass.getQualifiedName();
        if (qualified == null || !qualified.endsWith("Example")) {
            return null;
        }
        String entityFqn = qualified.substring(0, qualified.length() - "Example".length());
        if (entityFqn.substring(entityFqn.lastIndexOf('.') + 1).isEmpty()) {
            return null;
        }
        try {
            return JavaPsiFacade.getInstance(exampleClass.getProject())
                    .findClass(entityFqn, GlobalSearchScope.allScope(exampleClass.getProject()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * T-StartupReadLockFreeze §二十九：<b>按「类名 + 类正文指纹」</b>缓存实体解析结果。
     *
     * <p>真机实测 EntityResolver.resolve 对同一实体每次要 9 秒，而它自己的缓存键含
     * {@code identityHashCode(PsiFile)}——PSI 一重建就失配，于是每次点击都重算一遍
     * （resolve=9314/9447/9259ms 三连）。</p>
     *
     * <p>判据演进（两次都被真机/门禁打回，别再退）：modificationStamp 在 PSI 重建时被顶掉；
     * TTL 优先会让「改了注解 60 秒内还是旧的」（dev 实测别名补全整片消失）；现口径
     * {@code clazz.text.hashCode()}——PSI 重建后内容不变即哈希不变（不误 miss），真改了注解/字段就变
     * （不误 hit）。文本读取是 O(类大小) 的 PSI 走树（微秒级），远低于一次 resolve。</p>
     */
    @Nullable
    private TableInfo resolveEntityCached(@NotNull Project project, @NotNull PsiClass clazz) {
        String qName = clazz.getQualifiedName() != null ? clazz.getQualifiedName() : clazz.getName();
        if (qName == null) {
            return null;
        }
        // 内容指纹：PSI 重建不误 miss，真改注解/字段不误 hit（口径详见 ENTITY_INFO_CACHE 的 KDoc）
        int signature = 0;
        try {
            signature = clazz.getText().hashCode();
        } catch (RuntimeException ignored) {
            // 取不到正文按 0：等价于每次 miss（慢但正确）
        }
        EntityInfoEntry entry = ENTITY_INFO_CACHE.get(qName);
        if (entry != null && entry.signature() == signature) {
            LOG.info("[open-perf] entity cache hit qName=" + qName + " sig=" + signature);
            return entry.table();
        }
        LOG.info("[open-perf] entity cache miss qName=" + qName + " → EntityResolver.resolve");
        TableInfo resolved;
        try {
            resolved = new EntityResolver().resolve(clazz);
        } catch (Exception e) {
            return null;
        }
        if (resolved == null) {
            return null;
        }
        ENTITY_RESOLVE_RUNS.incrementAndGet();
        ENTITY_INFO_CACHE.put(qName, new EntityInfoEntry(signature, resolved));
        return resolved;
    }

    // ------------------------------------------------------------------
    // 主表上下文 / 联表候选
    // ------------------------------------------------------------------

    /** TableInfo → SqlCompletionContext（tableAlias 恒 null；列注释非空才收录；表注释原样透传）。 */
    @NotNull
    private SqlCompletionContext buildMainContext(@NotNull TableInfo table) {
        Map<String, String> columnComments = new LinkedHashMap<>();
        for (ColumnInfo col : table.getColumns()) {
            String comment = col.getComment();
            if (comment != null && !comment.isBlank()) {
                columnComments.put(col.getColumnName(), comment);
            }
        }
        List<String> columns = new ArrayList<>();
        for (ColumnInfo col : table.getColumns()) {
            columns.add(col.getColumnName());
        }
        return new SqlCompletionContext(
                table.getTableName(),
                columns,
                null,
                columnComments,
                table.getComment()
        );
    }

    /**
     * 联表候选：主表实体 TableInfo 置顶 + 项目内 {@code @TableName} 实体（按 tableName 去重、cap 50）。
     * 查询失败/异常 → 仅主表或空列表兜底（不抛异常）。
     */
    @NotNull
    private List<TableInfo> buildCandidates(@NotNull Project project, @Nullable TableInfo mainTable) {
        List<TableInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (mainTable != null) {
            result.add(mainTable);
            seen.add(mainTable.getTableName());
        }
        // T-StartupReadLockFreeze §十九（2026-11-13 15:00 真机）：原实现把**全项目** @TableName 实体
        // 逐个 EntityResolver.resolve，最后才 take(50)——2500 表工程上一次 6~10s（pre-assembly
        // 埋点直接读出 completion=10327ms/5924ms，且无缓存、第二次照样慢）。
        // §三十五（2026-11-13 20:xx 真机）：即便加了缓存与预算，**冷态首次仍要 374ms**（预算 400ms 打满，
        // 只解析出 1 个候选——本工程 EntityResolver.resolve 单次就是百毫秒级）。候选只服务补全的
        // JOIN 表名提示，不该压在点击/补全路径上 ⇒ 改「缓存优先」：未命中就派后台预热（去重、可取消），
        // 本次降级为空，预热落地后 refreshCompletionContext() 回填。
        long now = System.currentTimeMillis();
        List<TableInfo> cachedBase = null;
        CandidatesEntry entry = CANDIDATES_CACHE.get(project.getLocationHash());
        if (entry != null) {
            // §二十：失效判据从 PsiModificationTracker 改成 TTL——本插件预览链自己会写临时 SQL 文档，
            // 每次打开都把版本号顶掉，实测第二次打开仍要 2116ms（缓存等于没有）。
            long elapsed = now - entry.createdAt();
            if (elapsed >= 0 && elapsed < candidatesTtlMs) {
                CANDIDATES_CACHE_HITS.incrementAndGet();
                cachedBase = entry.tables();
            }
        }
        List<TableInfo> base = cachedBase != null ? cachedBase : (candidatesBlockingBuildEnabled()
                ? computeCandidatesNow(project)
                : warmCandidatesInternal(project, Collections.emptyList()));
        for (TableInfo table : base) {
            if (seen.add(table.getTableName())) {
                result.add(table);
            }
        }
        return result.size() > CANDIDATES_CAP
                ? new ArrayList<>(result.subList(0, CANDIDATES_CAP)) : result;
    }

    /** 冷态是否允许<b>同步</b>算候选（默认＝单测口径 true；生产 false ⇒ 缓存优先 + 后台预热）。 */
    private static boolean candidatesBlockingBuildEnabled() {
        Boolean forced = candidatesBlockingBuildForTest;
        if (forced != null) {
            return forced;
        }
        try {
            return ApplicationManager.getApplication() != null
                    && ApplicationManager.getApplication().isUnitTestMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 冷态候选的<b>同步</b>计算（仅在测试口径、或由 warmCandidates 的后台任务调用时执行）。
     *
     * <p>边解析边判上限（够 CANDIDATES_CAP 就停）、超 {@link #candidatesBudgetMs} 立刻放弃剩余、
     * 结果落 TTL 缓存。</p>
     */
    @NotNull
    private List<TableInfo> computeCandidatesNow(@NotNull Project project) {
        List<TableInfo> resolved = new ArrayList<>();
        Set<String> resolvedSeen = new HashSet<>();
        long deadline = System.currentTimeMillis() + candidatesBudgetMs;
        int scanned = 0;
        List<PsiClass> entities = tableNameEntitiesProviderForTest != null
                ? tableNameEntitiesProviderForTest.apply(project)
                : findTableNameEntities(project);
        for (PsiClass entityClass : entities) {
            if (resolved.size() >= CANDIDATES_CAP) {
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                LOG.info("[open-perf] ctx candidates budget hit: scanned=" + scanned
                        + " resolved=" + resolved.size() + " budget=" + candidatesBudgetMs + "ms");
                break;
            }
            scanned++;
            ENTITIES_RESOLVED.incrementAndGet();
            TableInfo table;
            try {
                table = new EntityResolver().resolve(entityClass);
            } catch (Exception e) {
                continue;
            }
            if (table == null || table.getTableName() == null || table.getTableName().isEmpty()) {
                continue;
            }
            if (resolvedSeen.add(table.getTableName())) {
                resolved.add(table);
            }
        }
        CANDIDATES_CACHE.put(project.getLocationHash(),
                new CandidatesEntry(System.currentTimeMillis(), List.copyOf(resolved)));
        return resolved;
    }

    /**
     * 冷态非同步路径：派后台预热（单飞 coalesceBy），本次返回 fallback（主表兜底）。
     */
    private List<TableInfo> warmCandidatesInternal(@NotNull Project project, List<TableInfo> fallback) {
        warmCandidates(project);
        return fallback;
    }

    /**
     * T-StartupReadLockFreeze §二十一：<b>后台补齐候选</b>（预览打开不再同步算它）。
     *
     * <p>供面板在预览显示后调用：BGT 里把全项目 @TableName 实体解析进 TTL 缓存，落地后由调用方
     * refreshCompletionContext() 回填补全上下文，用户真正补全时就有表名候选。</p>
     *
     * @param onReady 缓存就绪回调（本次刚算完或本来就热时触发）
     * @return true＝缓存已可用
     */
    public boolean warmCandidates(@Nullable Project project, @NotNull Runnable onReady) {
        if (project == null) {
            return false;
        }
        try {
            if (DumbService.isDumb(project)) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        long now = System.currentTimeMillis();
        CandidatesEntry entry = CANDIDATES_CACHE.get(project.getLocationHash());
        if (entry != null) {
            long elapsed = now - entry.createdAt();
            if (elapsed >= 0 && elapsed < candidatesTtlMs) {
                onReady.run();
                return true;
            }
        }
        long started = System.currentTimeMillis();
        // T-StartupReadLockFreeze §二十四（2026-11-13 真机栈实证）：本方法由面板在工作线程上调用，
        // 而 buildCandidates → findTableNameEntities → JavaPsiFacade.findClass 全是 PSI/索引读——
        // 不包读动作会抛 `Read access is allowed from inside read-action only`，异常被吞掉
        // ⇒ 候选永远补不进去（用户看到的就是「补全一直是空的」），同时刷一屏 IDEA 报错。
        // 用 nonBlocking + inSmartMode：等待索引时不持读锁、可取消，与 S1 预取链同款。
        boolean unitTest;
        try {
            unitTest = ApplicationManager.getApplication() != null
                    && ApplicationManager.getApplication().isUnitTestMode();
        } catch (Throwable t) {
            unitTest = false;
        }
        if (unitTest) {
            // 单测走同步路径（测试线程持读动作、没有真正的索引等待），生产走 nonBlocking
            boolean ok;
            try {
                computeCandidatesNow(project);
                ok = true;
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok) {
                onReady.run();
            }
            LOG.info("[completion] warmCandidates(unit-test) ok=" + ok
                    + " cached=" + cachedCandidateTables(project).size());
            return ok;
        }
        try {
            com.intellij.openapi.application.ReadAction.nonBlocking(() -> {
                computeCandidatesNow(project);
                return kotlin.Unit.INSTANCE;
            })
                    .inSmartMode(project)
                    .expireWith(project)
                    .coalesceBy(this, "warmCandidates:" + project.getLocationHash())
                    .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
                    .onSuccess(r -> {
                        LOG.info("[completion] warmCandidates ok=true cached="
                                + cachedCandidateTables(project).size()
                                + " elapsed=" + (System.currentTimeMillis() - started) + "ms");
                        onReady.run();
                    })
                    .onError(t -> LOG.info("[completion] warmCandidates fail "
                            + t.getClass().getSimpleName() + ": " + t.getMessage()));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean warmCandidates(@Nullable Project project) {
        return warmCandidates(project, () -> { });
    }

    /** 只读候选缓存（补全上下文构造用）：未预热/已过期 → 空列表，绝不触发解析。 */
    @NotNull
    public static List<TableInfo> cachedCandidateTables(@Nullable Project project) {
        if (project == null) {
            return List.of();
        }
        CandidatesEntry entry = CANDIDATES_CACHE.get(project.getLocationHash());
        if (entry == null) {
            return List.of();
        }
        long elapsed = System.currentTimeMillis() - entry.createdAt();
        return elapsed >= 0 && elapsed < candidatesTtlMs ? entry.tables() : List.of();
    }

    /**
     * 项目内 {@code @TableName} 实体类：
     * <ul>
     * <li>注解类可解析（真实 IDE）→ {@link AnnotatedElementsSearch#searchPsiClasses}（官方注解索引）；</li>
     * <li>注解类不可解析（测试沙盒/缺依赖）→ 兜底按<b>简单名</b>直查 {@link JavaAnnotationIndex}
     *     注解 stub 索引，命中后按注解原文筛掉非本注解的同名者。</li>
     * </ul>
     *
     * <p><b>索引键是简单名，不是 FQN（243 字节码实证，20261113 修）</b>：
     * {@code JavaAnnotationElementType.indexStub} 写入的键＝{@code getReferenceShortName(stub.getText())}，
     * 它先 {@code indexOf('(')} 截断（属性名/属性值根本不进索引）、再取短类名 ⇒ 键永远是 TableName。
     * 旧实现把 FQN 传进 {@code getAnnotations(...)}，于是这条兜底<b>恒空</b>（且只在「注解类解析不到」时
     * 才走，正好是它被写出来要救的那个场景）。源码守卫
     * {@code XmlSqlCompletionContextResolverIndexKeyTest} 钉住「只传简单名」。</p>
     *
     * <p>同名误命中筛选走<b>文本</b>（不 resolve）：写的名字是 TableName 或 {@code com.baomidou…TableName}
     * 才算；能解析出 FQN 时再要求等于本注解 FQN，拦掉自定义/别家同名注解。任一环节异常 → 空列表
     * （不抛异常）。</p>
     */
    @NotNull
    private List<PsiClass> findTableNameEntities(@NotNull Project project) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        try {
            PsiClass annotationClass = JavaPsiFacade.getInstance(project).findClass(TABLE_NAME_ANNOTATION, scope);
            if (annotationClass != null) {
                return new ArrayList<>(AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll());
            }
            Collection<PsiAnnotation> annotations =
                    JavaAnnotationIndex.getInstance().getAnnotations(TABLE_NAME_ANNOTATION_SIMPLE_NAME, project, scope);
            List<PsiClass> result = new ArrayList<>();
            for (PsiAnnotation annotation : annotations) {
                if (!isMybatisTableNameAnnotation(annotation)) {
                    continue;
                }
                PsiClass psiClass = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
                if (psiClass != null) {
                    result.add(psiClass);
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 该注解是不是 MyBatis-Plus 的 {@code @TableName}（文本判据，不触发 resolve）。
     *
     * <p>索引只保证「简单名是 TableName」；自己人写 {@code @TableName}、或写全限定名
     * {@code @com.baomidou…TableName} 都算；若注解能解析出 FQN 且不是本注解（自定义同名注解），排除。</p>
     */
    private static boolean isMybatisTableNameAnnotation(@NotNull PsiAnnotation annotation) {
        String written = annotation.getNameReferenceElement() != null
                ? annotation.getNameReferenceElement().getReferenceName() : null;
        if (!TABLE_NAME_ANNOTATION_SIMPLE_NAME.equals(written)) {
            return false;
        }
        String resolved;
        try {
            resolved = annotation.getQualifiedName();
        } catch (RuntimeException e) {
            resolved = null;
        }
        return resolved == null || resolved.equals(TABLE_NAME_ANNOTATION);
    }
}
