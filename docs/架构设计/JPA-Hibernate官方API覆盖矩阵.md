# JPA / Hibernate 官方 API 覆盖矩阵（全量兼容测试语料基座）

> **文档用途**：本矩阵是「SQL Visualizer」插件对 **Spring Data JPA、jakarta.persistence（JPA 规范）、Hibernate ORM、Criteria API、Spring ORM（HibernateTemplate）** 官方全部 SQL 相关 API 的覆盖矩阵，作用与《[MP-MPJ官方API覆盖矩阵](./MP-MPJ官方API覆盖矩阵.md)》相同：每行一个「API → 插件现状 → 对应任务 → 备注」，作为批量生成 fixture 测试与各里程碑验收的对照清单。
>
> **版本基线**：Spring Data JPA **3.5.13**（OSS 末期，2026-06-24）/ **4.0.7**（2026-08-20，Boot 4 线）；spring-data-commons 本地反编译 3.5.10 / 4.0.3（javap 证实 Part$Type 27 项、CrudRepository 面与 3.5.10 一致）；jakarta.persistence **3.1**（本地 3.1.0 jar）/ **3.2**（2024 规范，Hibernate 7 实现子集）；**javax.persistence 2.2.3**（本地 jakarta.persistence-api-2.2.3.jar，219 类同形注解仅包名不同——插件须双包名匹配）；Hibernate ORM **6.4.10.Final**（本地 jar）/ **6.6**（HQL 指南）/ 7.x；Spring ORM **6.1.15**（本地 jar）。
>
> **状态图例**：✅ = 已支持；🟡 = 部分支持（形态有限制/半成品）；⬜ = 暂不支持 / 文档化边界（远期或外部依赖）。
>
> **任务编号**：对应《[ORM框架功能覆盖缺口分析与补全-开发计划](../开发计划/20260904-2340-ORM框架功能覆盖缺口分析与补全-开发计划.md)》J1~J23；「已覆盖」表示现有能力无需新任务。
>
> **插件现状的核实方式**：源码全量检索（psi/jpa、converter/jpa、SqlPreviewService）+ demo 工程（jpa-hibernate-demo）对照，2026-09-04 二轮调研定稿；API 侧以本地 jar javap 为唯一权威，文档只补引入版本与语义。2026-09-05 起按 M39 三波交付逐行勾行（备注列尾标「M39 交付」）。2026-10 的实体解析与嵌入列命名修复（T-EntityNameScope / T-EmbeddedColPrefix / T-EmbeddedHostCascade）以「M39 后修复」标注，不改变矩阵状态列的语义。

---

## 1. Spring Data JPA 官方 API 矩阵

### 1.1 Repository 体系与 CRUD 方法

| 方法 / 形态 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `Repository<T,ID>` 标记接口 | ⬜ | J1 | 基类识别目前仅 5 个具名接口 + `Repository` 后缀启发；以父接口遍历替代 |
| `save(S)` | ✅ | J1 | isNew 判定 → INSERT/UPDATE；receiver 家族感知消歧（JpaCrudOperationConverter 三家族），不再撞 MP 批量转换器输出错误表名；M39 交付 |
| `saveAll(Iterable<S>)` | ✅ | J1 | ListCrudRepository 版返回 List；M39 交付 |
| `findById(ID)` | ✅ | J1 | WHERE 主键等值，Optional 返回；M39 交付 |
| `existsById(ID)` | ✅ | J1 | SELECT 1 ... LIMIT 1 形态；M39 交付 |
| `findAll()` | ✅ | J1/J3 | 裸 findAll 已放行（强制 By 门控修复）；M39 交付 |
| `findAllById(Iterable<ID>)` | ⬜ | J1 | IN 展开 |
| `count()` | ✅ | J1/J3 | SELECT COUNT(*)；M39 交付 |
| `deleteById(ID)` / `delete(T)` | ✅ | J1 | 逐实体 EM.remove 语义（非 bulk DELETE）；M39 交付 |
| `deleteAllById(Iterable)` | ✅ | J1 | 2.5 新增；M39 交付 |
| `deleteAll()` / `deleteAll(Iterable)` | ✅ | J1 | 同逐条 remove 语义；M39 交付 |
| `ListCrudRepository`（3.0） | ⬜ | J1 | findAll/findAllById/saveAll 返回收窄 List |
| `deleteAllInBatch()` / `deleteAllInBatch(Iterable)` / `deleteAllByIdInBatch(Iterable)` | ✅ | J1 | 唯一 bulk DELETE 形态（无级联不触发回调）；M39 交付 |
| `deleteInBatch(Iterable)` | ⬜ | J1 | Deprecated → deleteAllInBatch |
| `flush()` / `saveAndFlush(S)` / `saveAllAndFlush(Iterable)` | ✅ | J1 | flush 无独立 SQL（脏检查批量刷写，标注形态）；saveAndFlush 按 save SQL 呈现；M39 交付 |
| `getReferenceById(ID)` | ✅ | J1 | 延迟代理默认不发 SQL，标注「无查询」；M39 交付 |
| `getOne(ID)` / `getById(ID)` | ✅ | J1 | 双双 Deprecated（javap 证），迁移 getReferenceById；M39 交付 |
| `findAll(Pageable)` / `findAll(Sort)`（PagingAndSortingRepository） | ✅ | J10 | Pageable→LIMIT + Sort→ORDER BY 全路径（4.0.3 反编译未废弃）；M39 交付 |
| `ListPagingAndSortingRepository`（3.0） | 🟡 | J10 | findAll(Sort) 返回 List，随分页排序线部分形态识别；M39 交付 |
| `JpaRepositoryImplementation` / `RevisionRepository`（Envers） | ⬜ | 文档化边界 | 框架内部 SPI / 版本审计需 Envers，非 SQL 可视化核心 |

### 1.2 派生查询（PartTree 关键字权威清单 = spring-data-commons PartTree$Type 枚举 27 项）

| 方法 / 形态 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| 前缀 `find/read/get/query/search/stream...By` | ✅ | J3 | 六前缀全放行（`queryBy/searchBy/streamBy` 3.2 新增前缀门控修复）；M39 交付 |
| `countBy` / `existsBy` / `deleteBy` | ✅ | — | deleteBy 实为恒先查后逐条 remove（非 bulk DELETE） |
| `All`（findAll） | ✅ | J3 | 含 By 与裸 findAll/count() 均放行（门控修复）；M39 交付 |
| `Top N` / `First N` / `Distinct` | ✅ | — | First 无数字默认 1 |
| `OrderBy<prop><Asc|Desc>...` 多字段 | ✅ | — | OrderBySource，查询后缀非谓词 |
| `Between` | ✅ | — | 关键字 IsBetween，2 参 |
| `IsNull` / `NotNull`（IsNotNull/NonNull） | ✅ | — | |
| `LessThan(Equal)` / `GreaterThan(Equal)` / `Before` / `After` | ✅ | — | |
| `Like` / `NotLike` | ✅ | — | 需手写 % |
| `StartingWith` / `EndingWith` / `Containing` / `NotContaining` | ✅ | — | 参数两侧加 %（EscapeCharacter 转义） |
| `IsEmpty` / `IsNotEmpty` | ✅ | — | 集合空判定 |
| `In` / `NotIn` | ✅ | J4 | inClauseParams 已接线，面板多值 hint 齐全；M39 交付 |
| `NEAR` / `WITHIN` | ✅ 解析 | 文档化边界 | commons 通用枚举；JPA 无空间查询语义，应告警而非转换 |
| `MatchesRegex` | ✅ 解析 | 文档化边界 | JPA 关键字表未列；依赖方言 regexp |
| `Exists`（关键字形态） | ✅ 解析 | 文档化边界 | 语义映射依赖实现，JPA 文档未单列 |
| `True` / `False` | ✅ | — | |
| `IsNot` / `Is`（SIMPLE_PROPERTY） | ✅ | — | |
| `IgnoreCase` / `AllIgnoreCase` | ✅ | J4 | 生成 `LOWER(col)=LOWER(?)`；条件中间位置的 IgnoreCase 并入属性名缺陷已修；M39 交付 |
| `And` / `Or` 组合 | ✅ | — | 同级 OR、内层 AND |
| `Limit` 参数（3.2 新接口 Limit.of/unlimited/max） | ✅ | J10 | PartTree.getResultLimit 证实；Limit 参数收进 LIMIT 子句；M39 交付 |
| 返回 `Stream<T>` / `Streamable<T>` | 🟡 | J22 | SQL 同 List；JpaQueryEnhancer notes 级标注（事务中消费并 close）；M39 交付（notes 级） |
| 返回 `Page<T>` / `Slice<T>` | ✅ | J22 | Page count SQL 一并展示、Slice 多取一条；M39 交付 |
| 返回 `Window<T>` + `ScrollPosition`（keyset 滚动，3.2） | ⬜ | J22 | 键集过滤 SQL 变形 `(k1>?) OR (k1=? AND k2>?)`，远期 |
| `Sort` 参数（含 JpaSort.unsafe） | ✅ | J10 | Sort 参数→ORDER BY（findAll(Sort) 全路径）；unsafe 函数注入不做（文档化边界）；M39 交付 |
| 返回 `CompletableFuture/Future` | ⬜ | 文档化边界 | 异步执行期行为，SQL 与同步一致 |

### 1.3 @Query 与注解族

| 方法 / 形态 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `@Query`（JPQL / nativeQuery=true / 字符串拼接） | ✅ | — | javap 证属性全集 value/countQuery/countProjection/nativeQuery/name/countName/queryRewriter |
| `@Query.countQuery` / `countProjection` | 🟡 | J22 | Page 分页 count 专用；count 形态部分识别；M39 交付 |
| `@Query.name` / `countName`（绑定具名查询） | ⬜ | J12 | 代替内联 SQL 的引用形态 |
| `@Query.queryRewriter` | ⬜ | 文档化边界 | 运行期回调，静态不可预知 |
| `@Modifying(clearAutomatically, flushAutomatically)` | ⬜ | J2 | bulk UPDATE/DELETE 必需；全项目零引用 |
| UPDATE / DELETE JPQL | ⬜ | J2 | 现无 UPDATE 语句模型（DELETE 已特判） |
| `@Param` | ✅ | — | 包名陷阱：org.springframework.data.repository.query（commons 包） |
| `@Procedure`（value/procedureName/outputParameterName/refCursor 全重载） | 🟡 | J16 | 包名陷阱：...jpa.repository.query.Procedure；生成 CALL/EXEC；重载形态部分识别；M39 交付 |
| `@Lock(LockModeType)` | ✅ | J15 | PESSIMISTIC_WRITE → FOR UPDATE；M39 交付 |
| `@QueryHints`（jakarta @QueryHint[]，forCounting） | ✅ | J15 | 锁超时等 hint 影响方言渲染；M39 交付 |
| `@EntityGraph(attributePaths, type)` | 🟡 | J15 | attributePaths → LEFT JOIN FETCH 生成；EntityGraphType{LOAD,FETCH}；部分形态；M39 交付 |
| jakarta `@NamedEntityGraph(s)` + `@EntityGraph("name")` 引用 | ⬜ | J15 | 与 attributePaths 两条等价链路 |
| `@NamedQuery/@NamedQueries`（方法名↔查询名绑定 getNamedQueryName） | ✅ | J12 | javax/jakarta 双 FQN 固化（不再仅短名兜底）；orm.xml 定义仍未覆盖（远期）；M39 交付 |
| SpEL `:#{...}` / 值表达式 `:?{...}`（3.4） | ⬜ | J22（SpEL 基础）/ 文档化边界（:?{}） | `#{#entityName}` 高频，至少支持 entityName |
| `@Temporal`（org.springframework.data.jpa.repository） | ⬜ | 文档化边界 | 仅 java.util.Date 遗留参数 |
| `@Meta(comment)` | ⬜ | 文档化边界 | 仅 SQL 注释 |

### 1.4 Specification / Query By Example / Querydsl

| 方法 / 形态 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `JpaSpecificationExecutor` 8 方法（含 delete(Spec)→long、findBy(Spec, Function) fluent） | 🟡 | J5 | SpecificationQueryConverter 谓词解析（复用 CriteriaQueryConverter）；主体形态可预览；M39 交付 |
| `Specification` 组合器：where/not/and/or/allOf/anyOf/toPredicate | 🟡 | J5 | 生成 AND/OR 树；嵌套组合静态不可求值时降级占位；M39 交付 |
| `QueryByExampleExecutor` 7 方法 | 🟡 | J20 | 按 probe 非空属性生成等值 WHERE（QueryByExampleConverter）；M39 交付 |
| `Example.of` / `ExampleMatcher`（matching(Any/All)/withIgnorePaths/withIgnoreCase/withMatcher/NullHandler） | 🟡 | J20 | null 处理决定 WHERE 剔除；withIgnorePaths/withIgnoreCase/withMatcher 已接；M39 交付 |
| `ExampleMatcher.StringMatcher`{EXACT..REGEX} / `MatchMode`{ALL,ANY} | 🟡 | J20 | StringMatcher 5 形态 LIKE 映射（REGEX 降级注释）；MatchMode ANY→OR 未接；M39 交付（源码核对补勾） |
| `ListQueryByExampleExecutor` | 🟡 | J20 | 3.2.12 已存在；随 QBE 管道部分形态；M39 交付 |
| `QuerydslPredicateExecutor` 9 方法 | ⬜ | J23 | 依赖 APT 生成的 Q 类，静态解析成本高，文档化边界倾向，按需立项 |

### 1.5 投影

| 方法 / 形态 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| 类 DTO 构造投影（NEW dto(...)） | ✅ | — | JPQL 路径 |
| 接口投影（closed，按 getter 名选列） | 🟡 | J22 | SELECT 收敛到所需列；notes 级标注；M39 交付（notes 级） |
| 开放投影 `@Value("#{...}")` | ⬜ | 文档化边界 | 结果级表达式，不改变 SQL |
| 动态投影（方法参数 Class<T>） | 🟡 | J22 | Parameter.isDynamicProjectionParameter；部分形态；M39 交付 |
| `Tuple` / `Object[]` 返回 | ⬜ | J22 | 多列非实体结果 |
| 派生 countBy/existsBy 计数投影 | ✅ | — | |

### 1.6 版本备注（2024–2026）

| 版本 | 要点 |
|------|------|
| 3.4.x（2024-11，已 EOL） | 值表达式 `:?{}`、@EntityGraph 可重复容器（jar 未本地验证） |
| 3.5.x（2025-05；末版 3.5.13 = 2026-06-24；OSS 支持 2026-06-30 止） | 维护线；矩阵以本地 3.5.10 反编译为基线 |
| 4.0.x（2025-11 起；4.0.7 = 2026-08-20） | JSpecify 注解、废弃清理；javap 证 Part$Type 27 项与 Repository 面与 3.5.10 一致 |
| 4.1.x / 4.2.0-M1（2026） | tag 证实存在，特性未取证，跟进 |

---

## 2. jakarta.persistence 矩阵

### 2.1 EntityManager API（本地 3.1.0 jar javap 全实证；javax 2.2.3 同形仅包名不同）

| 方法 / 形态 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `persist(Object)` | ✅ | J6 | INSERT；受 @DynamicInsert/@Column(insertable)/ID 策略影响；M39 交付 |
| `merge(T)` | ✅ | J6 | detached→UPDATE、new→INSERT；按 UPDATE 呈现并标注歧义；M39 交付 |
| `remove(Object)` | ✅ | J6 | DELETE；@SQLDelete 时透传脚本、@SoftDelete 时改写 UPDATE（注入面待接）；M39 交付 |
| `find(Class,Object)` + 3 重载 | ✅ | J6 | SELECT by id；LockMode→FOR UPDATE；3.2 加 FindOption 变体；M39 交付 |
| `getReference(Class,Object)` | ✅ | J6 | 延迟代理，标注「无查询」（标注形态，不发 SQL）；M39 交付 |
| `flush()` | ✅ | J6 | 脏检查批量刷写，无独立 SQL（标注形态）；M39 交付 |
| `lock(Object,LockModeType)` | 🟡 | J15 | FOR UPDATE / FOR SHARE；部分形态；M39 交付 |
| `refresh(Object)` ×4 | ✅ | J6 | SELECT by id 重新物化（标注形态）；M39 交付 |
| `detach/clear/contains/getLockMode/setFlushMode/setProperty/joinTransaction/unwrap/getDelegate/close/getTransaction` | ⬜ | 文档化边界 | 不产生 SQL |
| `createQuery(String)` / `(String,Class)` | ✅ | — | 仅按方法名命中，不看 receiver（同名误报风险） |
| `createQuery(CriteriaQuery/Update/Delete)` | ✅ | J6/J17 | Criteria 查询链路 + Update/Delete 语句模型全贯通；M39 交付 |
| `createNamedQuery(String)` ×2 | 🟡 | J12 | JPQL 内容依赖 @NamedQuery 解析回查 |
| `createNativeQuery(String)` ×3 重载 | ✅ / 🟡 | J16 | 第 3 参 @SqlResultSetMapping 名未消费 |
| `createStoredProcedureQuery(String)` ×3 / `createNamedStoredProcedureQuery` | 🟡 | J16 | CALL/exec 渲染与参数方向；部分形态；M39 交付 |
| `createEntityGraph ×2 / getEntityGraph(s)` | ⬜ | J15 | 作 hint 时改 fetch 形态 |
| javax / jakarta 双包名匹配 | ✅ | J1 | 双 FQN 常量固化 + fixture 验证；M39 交付 |

### 2.2 实体映射注解（JpaEntityResolver 基础 12 个：Entity/Table/Column/Id/Transient/ManyToOne/OneToMany/OneToOne/ManyToMany/JoinColumn/JoinTable/MappedSuperclass；M39 起经 JpaMetadataReader 扩展消费 GeneratedValue/SequenceGenerator(s)/Version/Embedded/Embeddable/EmbeddedId/AttributeOverride(s)/ElementCollection/Inheritance/@Discriminator*/SecondaryTable(s)/OrderBy/MapsId + Hibernate @Where/@SQLRestriction/@SoftDelete/@SQLDelete 族）

| 注解 | 现状 | 对应任务 | 备注（SQL 影响） |
|------|:----:|------|------|
| `@Entity` / `@Id` / `@Transient` | ✅ | — | |
| `@Table(name/schema/catalog)` | ✅ | J18 | schema/catalog 已消费，跨 schema 表名正确；M39 交付 |
| `@Column` | ✅ | J18 | `insertable/updatable` 已消费——=false 时列从 INSERT/UPDATE 消失；M39 交付 |
| `@GeneratedValue` | ✅ | J14 | IDENTITY→INSERT 无该列；SEQUENCE→前置 select nextval（注释标注）；TABLE/UUID/AUTO 全量策略远期；M39 交付 |
| `@SequenceGenerator(s)` / `@TableGenerator(s)` | 🟡 | J14 | 序列名/分配大小元数据记录；前置 SELECT 形态部分；M39 交付 |
| `@Enumerated` / `@Temporal` | ⬜ | J18 | 绑定值形态/精度 |
| `@Access` | ⬜ | J18 | FIELD/PROPERTY 决定属性发现面 |
| `@Embedded/@Embeddable` | ✅ | J18 | 嵌入列展平（无 JOIN）；M39 交付。命名约定按 `application.yml` 的 Hibernate 命名策略推断 component-path 前缀，作用域＝实体所在模块（T-EmbeddedColPrefix，`6919d8ef`）→ 判不出再级联查询宿主模块（T-EmbeddedHostCascade，`b3970e1f`：实体打包进 JAR/lib 模块、命名策略配在宿主 App 的形态）；两档都判不出不回退工程级扫描，防多模块兄弟配置外溢 |
| `@EmbeddedId` / `@IdClass` | ✅ | J18 | 复合主键 WHERE 多列等值；M39 交付 |
| `@AttributeOverride(s)` / `@AssociationOverride(s)` | ✅ | J18 | 展平时套用列名覆写（嵌入展平）；M39 交付 |
| `@Version`（jakarta） | 🟡 | J8 | 记入 versionColumn 元数据；UPDATE 追加 SET version+1 WHERE version=? 注入未接（远期）；M39 交付 |
| `@Convert/@Converter(s)` | ⬜ | 文档化边界 | 改绑定值不改 SQL 形态 |
| `@Lob` / `@Basic` | ⬜ | 文档化边界 | 列名不变/fetch 需字节码增强 |
| `@NamedQuery(s)` | 🟡 | J12 | 内容转换已有 |
| `@NamedNativeQuery(s)` | ⬜ | J16 | 原生 SQL 透传 + mapping 引用 |
| `@NamedStoredProcedureQuery(s)/@StoredProcedureParameter` | 🟡 | J16 | CALL 渲染；部分形态；M39 交付 |
| `@NamedEntityGraph(s)/@NamedAttributeNode/@NamedSubgraph` | ⬜ | J15 | hint 引用时改 fetch 形态 |
| `@SqlResultSetMapping(s)/@EntityResult/@FieldResult/@ColumnResult/@ConstructorResult` | ⬜ | J16 | 列别名→实体/DTO 映射 |
| `@Cacheable` / `@Index` / `@UniqueConstraint` / `@QueryHint` / 生命周期回调族 | ⬜ | 文档化边界 | DDL/缓存/回调，不改 DML 结构 |
| 3.2-only `@CheckConstraint` / `@EnumeratedValue` | ⬜ | 文档化边界 | 3.1 jar 无此类 |

### 2.3 关联与继承注解

| 注解 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `@ManyToOne` | ✅ | — | 默认 EAGER → Hibernate 6 主表 JOIN |
| `@OneToOne` | ✅ | J7 | 有 FK 侧 JOIN / mappedBy 侧按主键二次 SELECT，侧别语义已建模；M39 交付 |
| `@OneToMany` | 🟡 | J7/J18 | 默认 LAZY 独立 SELECT；单向 @JoinColumn 写时 UPDATE 子表 |
| `@ManyToMany` | 🟡 | J7 | 中间表 JOIN；写涉及中间表 |
| `@JoinColumn(s)` | ✅ | J7 | 复合 @JoinColumns 已展开（真实三级解析）；M39 交付 |
| `@JoinTable` | ✅ | — | |
| `@MapsId` | 🟡 | J18 | 共享主键形态；元数据标记；M39 交付 |
| `@OrderBy` / `@OrderColumn` | 🟡 | J18 | 集合查询 ORDER BY / 索引列；元数据记录（SQL 注入未接）；M39 交付 |
| `@ElementCollection` / `@CollectionTable` | 🟡 | J18 | 独立第二查询（N+1 高发面）；元数据识别；M39 交付 |
| `@MapKey` 族 6 注解 | ⬜ | J18 | Map 键列 |
| `@Inheritance(strategy)` | 🟡 | J18 | SINGLE_TABLE 加 discriminator 谓词 / JOINED 每子类 INNER JOIN / TABLE_PER_CLASS UNION ALL；策略元数据识别（注入形态部分）；M39 交付 |
| `@DiscriminatorColumn` / `@DiscriminatorValue` | 🟡 | J18 | 判别列；元数据识别；M39 交付 |
| `@SecondaryTable(s)` / `@PrimaryKeyJoinColumn(s)` | 🟡 | J18 | 主表 LEFT JOIN 第二表；元数据识别；M39 交付 |
| `@MappedSuperclass` | ✅ | — | 父类字段展平已有 |

---

## 3. JPQL / HQL 语言特性

### 3.1 JPQL（JPA 规范）

| 特性 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| SELECT 别名展开 / 裸 from 补列清单 | ✅ | — | SelectClauseExpander（T-BareFromSelect 已修） |
| 命名 `:param` / 位置 `?1` / `IN ?` | ✅ | — | |
| JOIN [LEFT/INNER] ... ON | ✅ | J7 | ON 读真实 @JoinColumn（JpaJoinOnResolver 三级解析），消除猜测；M39 交付 |
| JOIN FETCH | ✅ | J7 | 已渲染 LEFT JOIN；ON 同上 |
| MEMBER OF | 🟡 | J19 | 现降级注释；标准形态子查询 |
| NEW 构造器投影 | ✅ | — | |
| DELETE FROM → DeleteStatement | ✅ | — | |
| UPDATE ... SET 语句 | ✅ | J2 | UPDATE 语句模型贯通（JpaUpdateParser），不再走 SELECT 管道产出错乱结果；M39 交付 |
| 标量子查询 / IN(子查询) / EXISTS / ALL|ANY|SOME | 🟡 | J13 | 字符串管道实证可预览 + FROM 子查询实体映射；完整转换远期（备注更新，状态保持 🟡） |
| GROUP BY / HAVING / 聚合 / ORDER BY / DISTINCT | ✅ | J17 | 已与别名展开联动验证；M39 交付 |
| 分页 setFirstResult/setMaxResults（API 形态） | ⬜ | J10 | |
| TREAT / TYPE / ENTRY|KEY|VALUE | ⬜ | J19 | 多态/集合映射函数 |
| 函数集（CONCAT/LOCATE/SUBSTRING/TRIM/LOWER/UPPER/LENGTH/ABS/SQRT/MOD/SIZE/INDEX/CURRENT_*/COALESCE/NULLIF） | 🟡 透传 | J17 | 白名单建模可选，直译兜底 |
| JPQL 3.2 新函数 key()/id()/version() | ⬜ | 文档化边界 | Hibernate 7 才入规范 |

### 3.2 HQL 扩展（Hibernate 6 指南实证；括号为引入版本）

| 特性 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| UNION [ALL] / INTERSECT / EXCEPT（6.1） | 🟡 | J19 | 括号分组 + ORDER BY 仅限整体；透传保护（不产错 SQL）；M39 交付 |
| WITH CTE（含 RECURSIVE/MATERIALIZED/SEARCH/CYCLE）（6.1） | 🟡 | J19 | 非 recursive CTE 亦可放 FROM；透传保护；M39 交付 |
| INSERT ... SELECT（HQL 专有 DML） | 🟡 | J19 | 透传保护（不产错 SQL）；M39 交付 |
| 窗口函数 OVER(PARTITION BY/ORDER BY) 10 函数（6.0） | ⬜ | J19 | 投影透传 |
| LISTAGG WITHIN GROUP / every()/any()（6.0/6.1） | ⬜ | J19 | |
| ROLLUP / CUBE / GROUPING（6.0） | ⬜ | J19 | |
| 派生根 / lateral / FROM 子查询 JOIN（6.0） | ⬜ | J19 | |
| TREAT/TYPE/ENTRY/KEY/VALUE | ⬜ | J19 | |
| 集合谓词全集 IS [NOT] EMPTY / SIZE / INDEX / ELEMENT(S) / INDICES / KEYS / VALUES | 🟡 | J19 | 仅 MEMBER OF |
| fk()/id()/naturalid()/version() 实体函数 | ⬜ | J19 | fk() 渲染 FK 列而非 JOIN |
| OFFSET n ROWS / FETCH FIRST n ROWS ONLY（6 标准分页） | ⬜ | J10 | |
| 宽函数集 CAST/EXTRACT/OVERLAY/BIT_LENGTH/PAD/LEFT/RIGHT/REPLACE/TRUNC/LEAST/GREATEST/FORMAT 等 | 🟡 透传 | 文档化边界 | 直译或方言映射 |
| function()/sql()/column() 原生逃逸 | ⬜ | 文档化边界 | 原文透传 |
| 时长字面量 / 日期算术 / by 排序空值子句 | ⬜ | 文档化边界 | 方言直译 |

---

## 4. Criteria API 矩阵（本地 3.1.0 jar javap 权威）

### 4.1 CriteriaBuilder 谓词与比较

| 方法 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `createQuery()` / `createQuery(Class)` | ✅ | — | |
| `createTupleQuery()` | ✅ | J17 | SELECT a,b 无实体投影 → Tuple 渲染；M39 交付 |
| `equal/notEqual` 双重载 | ✅ | — | |
| `greaterThan` 系 4 方法 | ✅ | — | |
| `gt/ge/lt/le`（Number 变体 8 重载） | ✅ | J17 | 与 greaterThan 同 SQL，加名即可；M39 交付 |
| `between` 双重载 | ✅ | — | |
| `like/notLike` 6 重载 | ✅ | J17 | escape 参正确渲染 ESCAPE 子句（不再并入 pattern 文本）；M39 交付 |
| `isNull/isNotNull` | ✅ | — | Expression.isNull() 实例形态未处理 |
| `isTrue/isFalse` | ✅ | J17 | = TRUE/FALSE；M39 交付 |
| `not(Expression)` / `and/or` 双重载 | ✅ | — | |
| `conjunction()/disjunction()` | ✅ | J17 | 渲染 1=1 / 1=0；M39 交付 |
| `cb.in(expr).value(...)` 链 | 🟡 | J17 | 连续 value 仅捕获最后一层 |
| `Expression.in(...)` 4 重载（path.in(collection)） | 🟡 | J17 | qualifier 非简单引用时不走识别 |
| `notIn` | — | J4 | M39 已清理（3.1.0 无此方法，javap 证） |
| `exists(Subquery)` / `all/some/any(Subquery)` | 🟡 | J17/J13 | 依赖子查询能力；单层 Subquery 已支持，correlate 待接；M39 交付 |
| `treat(...)` 7 重载 | ⬜ | J19 | TREAT 多态 |
| `getRestriction` | — | J4 | M39 已清理（名单死条目；distinct 已另行交付 ✅） |

### 4.2 CriteriaBuilder 函数与表达式

| 方法 | 现状 | 对应任务 | SQL 形态 |
|------|:----:|------|------|
| `count/countDistinct` | ✅ | J17 | countDistinct 已支持；M39 交付 |
| `avg/sum/max/min` | ✅ | — | 仅 select/multiselect 实参识别 |
| `sumAsLong/sumAsDouble` / `greatest/least` | ✅ | J17 | SUM/MAX/MIN 语义；M39 交付 |
| 算术 `sum/prod/diff/quot/mod/neg` | ✅ | J17 | a+b / a*b / a-b / a/b / MOD；M39 交付 |
| `abs/ceiling/floor/round/exp/ln/power/sqrt/sign` | ✅ | J17 | M39 交付 |
| `toLong/toInteger/toFloat/toDouble/toBigDecimal/toBigInteger/toString` | ✅ | J17 | CAST；M39 交付 |
| 字符串 `concat/substring/lower/upper/length/locate/trim(Trimspec)` | ✅ | J17 | LOWER 是 IgnoreCase 生成的前置依赖；M39 交付 |
| `literal/nullLiteral/parameter` | ✅ | J17 | M39 交付 |
| 集合 `isEmpty/isNotEmpty/size/isMember/isNotMember/keys/values` | 🟡 | J17 | 部分形态以注释降级呈现；M39 交付 |
| `coalesce` 双形态 / `nullif` | ✅ | J17 | M39 交付 |
| `selectCase` → Case/SimpleCase | ✅ | J17 | CASE WHEN；M39 交付 |
| `function(name, type, args...)` | ⬜ | J17 | 白名单防注入 |
| `construct/tuple/array` 投影 | ⬜ | J17 | multiselect 等价 |

### 4.3 CriteriaQuery 子句与投影

| 方法 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `select(Selection)` / `multiselect(Selection...)` | ✅ | — | |
| `multiselect(List)` | ✅ | J17 | List 重载已解出实参（不再误判全列）；M39 交付 |
| `where` 双重载 | ✅ | — | |
| `orderBy` 双重载 / `Order.reverse()` | ✅ | J17 | List 重载已支持；M39 交付 |
| `groupBy` 双重载 | ✅ | J17 | List 重载已支持；M39 交付 |
| `having` 双重载 | ✅ / 🟡 | J17 | 聚合条件解不出字段降级 Raw |
| `distinct(boolean)` | ✅ | J4 | 扫描分支 + 模型字段贯通；M39 交付 |
| `from(Class)` / `from(EntityType)` | 🟡 | J17 | EntityType 实参降级表名推断 |
| 各 getter（getOrderList/getRoots 等） | ⬜ | 文档化边界 | 读取型 API |

### 4.4 Path / Join / Fetch / 子查询

| 方法 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `Path.get(String)` / `get(SingularAttribute 等)` | ✅ | — | 字符串与元模型双形态 |
| `Path.type()` | ⬜ | J19 | |
| `join(String[, JoinType])` | 🟡 | J7 | 无 JoinType 默认 LEFT，规范默认 INNER |
| `join(PluralAttribute...)` 4 族 | ✅ | — | |
| `joinCollection/joinSet/joinList/joinMap` 8 重载 | ✅ | J7 | 名单已扩全 4 族；M39 交付 |
| `Join.on(Expression/Predicate...)` | ✅ | J7 | 显式 ON 覆盖推断，消除「请验证」；M39 交付 |
| `MapJoin.key()/value()/entry()` / `ListJoin.index()` | ⬜ | J17 | |
| `FetchParent.fetch(...)` 6 重载 | ✅ | J7 | fetch 已入名单，含 fetch join 的查询整体保留 JOIN；M39 交付 |
| `Subquery.select/where/groupBy/having/distinct` + `subquery(Class)` | ✅ | J17/J13 | 高频 IN (SELECT ...)；单层支持；M39 交付 |
| `Subquery.correlate(...)` 5 重载 | ⬜ | J17 | 相关子查询 |

### 4.5 CriteriaUpdate / CriteriaDelete

| 方法 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `createCriteriaUpdate/createCriteriaDelete(Class)` | ✅ | J6 | Update/Delete 语句模型贯通（复用 MP 侧）；M39 交付 |
| `from/getRoot` | ✅ | J6 | M39 交付 |
| `set(...)` 5 重载（含 setNull） | ✅ | J6 | root.get("x") 形态最常用；M39 交付 |
| `where` ×4 | ✅ | J6 | 谓词抽取与查询共享；M39 交付 |
| CriteriaDelete 同上 | ✅ | J6 | M39 交付 |

### 4.6 Criteria 3.2 新增（apidoc 为准，本地无 3.2 jar）

| 方法 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `CriteriaSelect` 新接口 | ⬜ | 文档化边界 | 3.2 才发布 |
| `union/unionAll/intersect/intersectAll/except/exceptAll` | ⬜ | J19 | MySQL 系方言不支持 EXCEPT，需方言开关 |
| `extract` / `left/right` / `replace` 4 重载 | ⬜ | J17 | |
| window 函数 / `visible()` / `currentInstant()` | — | 文档化边界 | 3.2 apidoc 无此 API，判定不存在 |

---

## 5. Hibernate Session API 与专有注解

### 5.1 Session API（6.4.10.Final javap 实证）

| 方法 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `save(String?, Object)` ×2 | ✅ | J6 | INSERT；M39 交付 |
| `saveOrUpdate` ×2 / `update` ×2 / `persist(String,entity)` / `merge` | ✅ | J6 | M39 交付 |
| `delete` ×2 / `remove` | ✅ | J6 | @SQLDelete 透传；M39 交付 |
| `get` ×6 重载 | ✅ | J6 | 立即 SELECT by id；M39 交付 |
| `load` ×7 重载 | ⬜ | 文档化边界 | 代理无立即 SQL |
| `replicate` ×2 | ⬜ | 文档化边界 | ReplicationMode 语义 |
| `byId(...).load/getReference` / `byMultipleIds` / `byNaturalId` 族 | 🟡 | J6 | IN 分片 / @NaturalId WHERE；部分形态；M39 交付 |
| `createQuery/createNamedQuery/createNativeQuery` | ✅ | — | |
| `createSQLQuery` / `createCriteria` | 🟡 | J12 | 6.0 已移除（6.4 字节码零命中）；M39：createSQLQuery 已入名单按 native 语义处理 |
| `createMutationQuery` ×4 / `createNamedSelectionQuery` ×2 / `createNamedMutationQuery` | ⬜ | J6 | 6.x 新查询入口 |
| `enableFilter/disableFilter` + `@FilterDef/@Filter` | ⬜ | J8 | 运行时 WHERE 注入 |
| `enableFetchProfile` + `@FetchProfile` | ⬜ | J15 | |
| `lock/buildLockRequest` | 🟡 | J15 | FOR UPDATE；部分形态；M39 交付 |
| `refresh/evict/clear/detach/setReadOnly` | ⬜ | 文档化边界 | |
| `setFetchBatchSize/setSubselectFetchingEnabled` | ⬜ | 文档化边界 | 全局开关静态不可判 |

### 5.2 Hibernate 专有注解（6.4.10 org.hibernate.annotations 类清单实证）

| 注解 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `@Where`（6.3 起 deprecated） | 🟡 | J8 | 元数据读取 ✅；WHERE 片段注入全部 DML 未接（远期）；M39 交付 |
| `@SQLRestriction`（6.3） | 🟡 | J8 | 元数据读取 ✅；同 @Where 注入未接，支持 {alias}；M39 交付 |
| `@SoftDelete`（6.4，ACTIVE/DELETED 策略） | 🟡 | J8 | 元数据读取 ✅；查询追加 deleted 条件 / DELETE 改写 UPDATE 注入未接；M39 交付 |
| `@SQLDelete/@SQLDeleteAll/@SQLInsert/@SQLUpdate(s)` | 🟡 | J8 | 元数据读取 ✅；自定义 SQL 整段透传注入未接；M39 交付 |
| `@Loader(namedQuery)` / `@SQLSelect/@HQLSelect/@SQLOrder/@Synchronize` | ⬜ | J16 | 6.x 自定义读语句 |
| `@Fetch(FetchMode)` | ⬜ | J15 | SUBSELECT→IN (SELECT 外层)；JOIN→主查询 JOIN；SELECT→N+1 |
| `@BatchSize(size)` | ⬜ | J15 | N+1 改 IN 分批 |
| `@FetchProfile(s)/@FetchProfileOverride` | ⬜ | J15 | |
| `@LazyCollection/@LazyToOne/@LazyGroup` | ⬜ | 文档化边界 | 加载时机，不改单条 SQL |
| `@DynamicInsert/@DynamicUpdate` | ⬜ | J18 | INSERT/UPDATE 列集动态化 |
| `@SelectBeforeUpdate` / `@Immutable` | ⬜ | J18 | |
| `@Subselect` | ⬜ | J18 | 实体映射到子查询 |
| `@Formula/@JoinFormula/@JoinColumnOrFormula(s)/@DiscriminatorFormula` | ⬜ | J18 | SELECT 片段直渲染 |
| `@ColumnTransformer(s)` | ⬜ | J18 | read/write 片段包装列 |
| `@CreationTimestamp/@UpdateTimestamp/@CurrentTimestamp/@GeneratorType` | ⬜ | J14 | INSERT/UPDATE 追加列赋值 |
| `@GenericGenerator(s)/@UuidGenerator/@IdGeneratorType` | ⬜ | J14 | ID 策略扩展 |
| `@Generated/@GeneratedColumn` | ⬜ | J14 | DB 生成列 INSERT 排除 |
| `@NaturalId` | 🟡 | J6 | byNaturalId WHERE 列；元数据识别；M39 交付 |
| `@NotFound(IGNORE)` | 🟡 | J7 | FK 失配 JOIN 改型；元数据识别；M39 交付 |
| `@Any/@ManyToAny/@AnyDiscriminator*` | ⬜ | 文档化边界 | 多态 FK 罕用 |
| `@Filter(s)/@FilterDef(s)/@FilterJoinTable(s)` | ⬜ | J8 | 运行时 WHERE 注入 |
| `@TenantId/@PartitionKey` | ⬜ | J8 | 多租户列（与 MP Tenant 模拟同思路） |
| `@OnDelete` / `@SecondaryRow(s)` | ⬜ | J18 | |
| DDL/类型系统类（@ColumnDefault/@Check/@Comment/@View/@Proxy/@Target/@TypeDef 等） | ⬜ | 文档化边界 | 不改 DML 结构；@TypeDef 6.x 已移除 |
| `processing.@Find/@HQL/@SQL/@CheckHQL`（6.4） | ⬜ | J19 | 注解处理器生成查询方法，SQL 语义同名 JPQL/HQL |

### 5.3 HibernateTemplate / HibernateOperations（spring-orm 6.1.15 javap；注意：全部 HQL 字符串系方法均带字节码 Deprecated 标志，扩展优先级向 Criteria/JPA 倾斜）

| 方法 | 现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `find(String, Object...)` / `findByNamedParam` ×2 / `findByValueBean` / `iterate` | ✅ | — | 插件现支持恰为 deprecated 子集 |
| `findByNamedQuery` / `findByNamedQueryAndNamedParam` ×2 / `findByNamedQueryAndValueBean` | ✅ | J12/J21 | 依赖 @NamedQuery 解析（双 FQN 已固化）；M39 交付 |
| `bulkUpdate(String, Object...)` | ✅ | J21 | 模板唯一 DML 入口；UPDATE 语句模型贯通；M39 交付 |
| `get(...)` ×5 | ✅ | J21 | SELECT by id；M39 交付 |
| `load(...)` ×5 / `loadAll(Class)` | ✅ | J21 | load 为代理（标注形态）；M39 交付 |
| `save` ×2 / `saveOrUpdate` ×2 / `persist` ×2 / `merge` ×2 | ✅ | J21 | M39 交付 |
| `update` ×4 / `delete` ×4 / `deleteAll` / `replicate` ×2 | ✅ | J21 | M39 交付 |
| `refresh` ×2 / `lock` | ✅ | J21 | M39 交付 |
| `findByCriteria(DetachedCriteria[, first, max])` ×2 | 🟡 | J21 | QBC 静态不可解析 → 可读占位（含分页形态）；M39 交付 |
| `findByExample` ×4 | ✅ | J21 | 非空属性等值 WHERE；M39 交付 |
| `closeIterator/contains/evict/initialize/flush/clear/enableFilter` / `execute` | ⬜ | 文档化边界 | 会话级/回调体任意 |
| `executeFind` | 🟡 | J21 | 回调体静态不可判，按 find 形态占位呈现；M39 交付 |
| `org.springframework.orm.jpa.JpaTemplate` | — | 文档化边界 | Spring 6.0 已从 jar 移除（javap 证） |

---

## 6. 目标状态填写规则

与《MP-MPJ官方API覆盖矩阵》§3 一致：⬜ 项均为文档化边界（对外能力声明），插件对不支持面必须优雅降级（可读占位提示），不得抛异常。实施一项、对应行 ⬜/🟡 → ✅ 并同步新增 fixture 测试（converter/jpa、psi/jpa 既有 14+11 个测试类惯例，JpaDemoIntegrationTest 以 demo 源码结构为 fixture）。

## 7. 信息来源

| 来源 | 版本 | 核实内容 |
|------|------|------|
| 本地 jar javap（唯一权威） | spring-data-jpa 3.2.12 / spring-data-commons 3.5.10 与 4.0.3 / jakarta.persistence-api 3.1.0 与 2.2.3 / hibernate-core 6.4.10.Final / spring-orm 6.1.15 / spring-data-jpa 3.2.12 | Repository 体系、PartTree$Type 27 项、EntityManager/Session 全方法、org.hibernate.annotations 全注解清单、Criteria 包全接口、HibernateTemplate 全方法与 Deprecated 标志 |
| Spring Data JPA 官方文档 | docs.spring.io（当前版） | 派生查询关键字表、@Procedure/@EntityGraph/投影/Specification 章节 |
| Hibernate 6.6 HQL 指南 | hibernate.org | HQL 全集（CTE/UNION/窗口/INSERT-SELECT）与引入版本 |
| jakarta persistence 3.2.0 tag 源码 | jakarta.ee / GitHub | 3.2 新增 API（FindOption/CriteriaSelect/set 操作等） |
| Spring Data JPA GitHub releases | spring-projects/spring-data-jpa | 版本时间线（3.5.13 = 2026-06-24、4.0.7 = 2026-08-20、4.1/4.2-M1 tag） |
| 插件源码 | master 2026-09-04 | 「插件现状」列逐条核实 |

## 8. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-04 | 初版：四智能体并行调研（jar javap + 官方文档 + releases 三重交叉）建立 8 个分类面（Spring Data JPA 6 节、jakarta 3 节、JPQL/HQL 2 节、Criteria 6 节、Hibernate Session/注解/HibernateTemplate 3 节）；「jar 未能证实、依赖文档」条目已逐节标注；对应开发计划任务 J1~J23 |
| 2026-09-05 | M39 三波交付勾行：Wave1（`a0bd5aa` 转换/定位四线并行）+ Wave2（`7e11d31` CRUD 派发与 demo 写路径语料）+ Wave3（F 智能体 Specification/QBE/查询深化，代码在工作区待提交），测试基线约 1700+ 用例。保持原状行：§1.1 `Repository` 标记接口 / `findAllById` / `ListCrudRepository` / `deleteInBatch`（清单未列）；§1.3 `UPDATE / DELETE JPQL`（清单仅勾 §3.1 UPDATE SET 行）；§1.4 `QuerydslPredicateExecutor`（J23 文档化边界）；§1.5 `Tuple`；§2.1 `createNamedQuery`（🟡）；§2.2 `@NamedQuery(s)`（🟡，清单仅勾 §1.3 同名行）；§3.1 标量子查询行状态保持 🟡 仅更新备注。`ExampleMatcher.StringMatcher/MatchMode` 行经 QueryByExampleConverter 源码核对补勾 🟡（StringMatcher 5 形态已接、MatchMode 未接）；§2.2 节头消费注解清单随 M39 扩展更新 |
| 2026-10-27 | **M39 后实体解析簇回填**（矩阵状态列不变，全部属 ✅ 行的实现加固）：①实体解析按查询宿主模块可见性收敛（`78878520` T-EntityNameScope，2026-10-16）——同名实体不再跨模块误判；②同 FQN 跨模块源码按模块依赖方向仲裁（`1b61964f`）、两份都判为工程内容时按源码形态收窄（`a52d083d`，jar 位于 resources/lib）、workspace 形态零候选拓宽（`91400a3e`，三条均 2026-10-23）、库内宿主叠加工程同名实体（`54059ef0`，2026-10-22）；③嵌入列命名作用域＝实体所在模块（`6919d8ef`）→ 判不出级联宿主模块（`b3970e1f` T-EmbeddedHostCascade，2026-10-26，解 JAR/lib 实体 @Embedded 丢 component-path 前缀的外部报障），§2.2 `@Embedded/@Embeddable` 行备注已补全链；④诊断锚点：`[T-EmbeddedIdentify]` 前缀日志（`8267394d`，覆盖 JPA 轨与 MP 轨的嵌入展平出口、命名档来源、`resolveHqlTable` 实体解析，四处源文件、INFO 级无开关不改语义） |