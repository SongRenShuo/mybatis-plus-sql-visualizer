# 其他 ORM 官方 API 覆盖矩阵（候选框架调研基座）

> **文档用途**：本矩阵是「SQL Visualizer」对插件尚未适配的主流 ORM/SQL 框架的**调研覆盖矩阵**，作用与《[MP-MPJ官方API覆盖矩阵](./MP-MPJ官方API覆盖矩阵.md)》《[JPA-Hibernate官方API覆盖矩阵](./JPA-Hibernate官方API覆盖矩阵.md)》相同：每行一个「API → 插件现状 → 对应任务 → 备注」，作为后续立项（M44+）的语料基座与验收对照清单。**本矩阵只做调研记录，不代表已立项实施。**
>
> **调研方式**（2026-09-05，四路智能体并行）：本地 jar `javap`/`unzip -l` 为方法名唯一权威 + 官方文档 + Maven Central 版本核实 + 插件源码逐行推演。研究用 jar 存放 `F:\code\orm-research-jars\`（临时目录，不入库）。
>
> **成本门槛**（沿 M37.5 先例）：新框架 Converter 增量维护 < 现有 MP 系 15% 才立项；Flex 因两段式条件 API 被暂缓是本矩阵的判例基准。
>
> **状态图例**：✅ = 已支持；🟡 = 部分支持（含「侥幸可用」——碰巧能出大体正确 SQL 但无显式支持）；⬜ = 暂不支持；❗ = 当前会产出**错误**结果（比不支持更糟，需优先止血）。
>
> **任务编号**：SD=Spring Data JDBC/R2DBC，JO=jOOQ，QD=Querydsl，EQ=Easy-Query，JM=Jimmer，XB=xbatis，DS=MyBatis Dynamic SQL，FM=Fluent MyBatis，DV=dbVisitor，BS/ST=BeetlSQL/sqltoy。均为预分配，立项时启用。

---

## 0. 结论速览（立项决策表）

> **交付快照（2026-09-05 实施，2026-10-27 核实已合入 master）**：Spring Data JDBC 止血（SD6/SD7/SD8/SD10）✅、xbatis（XB1/XB2）✅、Easy-Query proxy MVP（EQ1/EQ4~EQ10）✅、MyBatis Dynamic SQL（DS1~DS5）✅、Querydsl MVP（QD1/QD2/QD4/QD5 ✅、QD3 算术 Raw 🟡）；全量 3353 测试 0 失败 + buildPlugin 通过。第一批实施提交 `a3ccca6c` 已在 master 历史中（`git merge-base --is-ancestor` 实测），对应转换器 `converter/{EasyQuery,Xbatis,Mds,Querydsl}Converter.kt` 均在主线；原记「分支 feature/m44-orm-batch2 未合并」已过时。仍待立项：SD 完整期（SD1~SD5/SD9）、EQ core/api4j（EQ2/EQ3）、QD 子查询（QD6）/版本矩阵声明（QD7）、jOOQ（JO）、Jimmer（JM）、Fluent MyBatis/dbVisitor/BeetlSQL/sqltoy/lazy。

| 框架 | 最新版（2026-09） | 结构家族 | 成本档 | 结论 | 建议节奏 |
|------|------|------|------|------|------|
| Spring Data JDBC / R2DBC | 4.1.1（Boot 4 线） | Spring Data 家族（与 JPA 共轨） | 止血 <5%；完整 5-15% | **立项·止血优先**（当前 @Query 链路产出错误 SQL，❗） | M44 第一批 |
| xbatis（原 mybatis-mp） | cn.xbatis 1.10.8（2026-08-21） | MP 同构 wrapper 链 | <5% | **立项·最易**（分派+方法名别名表即可） | M44 第一批 |
| Easy-Query | 3.2.15（2026-06） | MP 同构链（core 9/10、proxy 7.5/10） | 8-12% | **立项**（proxy 优先 MVP） | M44/M45 |
| MyBatis Dynamic SQL | 2.0.0（2026-03，官方） | 手写 DSL+规整 AST | 5-15% | **立项候选**（无生成代码依赖） | M44/M45 |
| Querydsl | 5.1.0（javax；社区 fork 至 7.x） | 生成代码 DSL+JPA 复用 | 10-20%（5-15% 上沿） | **做·优先于 jOOQ**（条件与 MP 几乎 1:1，实体解析复用 JPA 管线） | M45 |
| jOOQ | 3.21.8（2026-08） | 生成代码 DSL | 25-35%（>15%） | **缓做·仅 MVP 子集**（链步进只按方法名分派友好，但生成代码基建与 API 面大） | 按需 |
| Fluent MyBatis | 1.10.1（2025-12，低频） | 编译期生成查询类 | 5-15% | P1 按需 | 按用户反馈 |
| dbVisitor | 6.7.0（2026-02） | 注解 SQL+lambda 混合 | 5-15% | P1 按需 | 按用户反馈 |
| BeetlSQL 3 | 3.40.1-RELEASE（2026-06） | md 模板驱动 | 模板 >15%；lambda 轨 5-15% | P2 分步（先 md 静态化） | 按需 |
| sqltoy | 5.6.94.RC6（RC 高频） | XML 模板驱动 | 完整 >15% | P2（已有第三方 IDE 插件先例） | 按需 |
| Jimmer | 0.9.96（2026-05） | 谓词表达式树（Criteria 族） | >15% | **暂缓**（Flex 同判；未来可挂 Criteria 族轨道） | 重启条件见 §5 |
| lazy-orm | 1.3.6（2025-11，低频） | 自研链变体 | 5-15% | P3 占位 | 不排期 |

> 放弃项：Spring Data MongoDB/Redis/ES 等 NoSQL 家族（非 SQL 面）、OpenJPA/DataNucleus（停滞）、Exposed/Ktorm/GORM（Kotlin/Groovy 专属，待 M3 Kotlin PSI 基建）。

---

## 1. Spring Data JDBC / R2DBC

> 与已支持的 Spring Data JPA 同属 spring-data 生态：Repository 基接口同名（CrudRepository/ListCrudRepository），**现状大量「侥幸可用」**——receiver 家族判定与默认 snake_case 命名双向凑巧对上；缺口集中在 relational 注解轨与 @Query 路由。插件源码 grep `data.relational|r2dbc` 零命中，无任何显式感知。

### 1.1 Repository 与 CRUD（版本基线 spring-data-jdbc 4.1.1）

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `CrudRepository` 直系 CRUD（findById/save/findAll/findAllById/existsById/count/deleteById/deleteAll 系） | 🟡 | SD1 | 家族判定已命中（JpaCrudOperationConverter REPOSITORY_BASE_TYPES 含 CrudRepository，SQL 大体正确）；save 的 isNew 注解文案是 JPA 语境，需按家族分流 |
| `ListCrudRepository` / `ListPagingAndSortingRepository`（3.0 变体） | ✅ | SD6 | JpaCrudOperationConverter 名单已含 List*，但 DerivedMethodLocator 基类名单**不含**（仅靠「接口名 Repository 后缀」启发式侥幸放行派生方法）——三处名单需抽公共常量对齐 |
| `deleteById` | 🟡 | SD2 | SQL 本体可用（DELETE WHERE id=?），但 note 误标「JPA 逐实体 remove 语义」——JDBC 实为 bulk DELETE+级联删子表 |
| `save` / `saveAll` | 🟡 | SD3 | JDBC 语义：新聚合=根表 INSERT+级联 INSERT 子表；旧聚合=UPDATE+**子表全删重插**——插件仅呈现根表单语句，注解需换 JDBC 语境 |
| `R2dbcRepository` / `ReactiveCrudRepository` / `ReactiveSortingRepository` | 🟡 | SD4 | 三者均不在基类名单，仅启发式侥幸；javap 证实 R2dbcRepository 自身零方法（聚合接口）；Mono/Flux 返回类型与实体推断天然兼容，仅需名单+notes 标注 Reactive 语义 |

### 1.2 实体映射注解（org.springframework.data.relational.core.mapping，7 注解 javap 实证）

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `@Table(value/name/schema)` | ✅ | SD8 | 短名兜底命中且 relational @Table 确有 name 属性→`@Table(name=...)` 正确；但社区最常用的 **value 简写** `@Table("t_user")` 只读 name 属性→表名丢失回退推断；另支持 SpEL 动态表名（文档化边界） |
| `@Column(value)` | ✅ | SD8 | relational @Column **只有 value 属性**（无 name/insertable/updatable），插件只读 name→**列名系统性丢失**为字段名 snake |
| `@Id`（org.springframework.data.annotation.Id） | ✅ | SD8 | 短名兜底正确解析；需固化双轨 FQN（commons 包非 jakarta）；无 @GeneratedValue——主键生成靠 @Sequence/KeyGenerator |
| `@Transient` / `@Version`（spring-data-commons 包） | ✅ | SD8 | 短名兜底正确；同上固化 FQN |
| `@Embedded(prefix/onEmpty)` | 🟡 | SD9 | 展平命中但 prefix 不读（JPA 走 @AttributeOverride）→有前缀时列名全错；前缀化列名拼接为新增语义 |
| `@MappedCollection(idColumn/keyColumn)` | ✅ | SD10 | 最大单项：集合/嵌套实体字段不在 jakarta 跳过名单→被当**普通列**输出不存在的列（错误 SQL）；正确语义：反向列=引用方实体表名、key 列=反向列+_KEY（NamingStrategy javap+文档双证）；可复用 TableInfo.collectionAssociations 通道 |
| `@Sequence(value/sequence/schema)` / `@InsertOnlyProperty` | ⬜ | SD9 | 主键序列/聚合根仅插入属性（INSERT 进、UPDATE 跳过） |
| 命名策略默认 snake_case | ✅ | — | DefaultNamingStrategy.getTableName=reconcatenateCamelCase(name,"_")，与插件 inferTableName 默认一致；自定义 NamingStrategy bean 文档化边界 |
| save 聚合级联呈现 | ⬜ | SD3 | 根+子表多语句展开（远期，先注解说明） |

### 1.3 派生查询与 @Query

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| 派生方法（CrudRepository 直系） | 🟡 | SD5 | PartTreeParser 关键字是 JDBC 支持面的**超集**（JDBC 无 RegExp/NEAR/WITHIN/EMPTY），交集输出一致；**JDBC 禁跨聚合 JOIN** 而插件嵌套属性会展开 JOIN→产出运行时不存在的 SQL，需家族门控 |
| countBy / existsBy / deleteBy | 🟡 | SD5 | 4.1.x 运行时支持（PartTreeJdbcQuery.createDeleteQueries/JdbcDeleteQueryCreator javap 实证），插件可出 |
| `org.springframework.data.jdbc.repository.query.Query` | ✅ | SD7 | **最危险假阳性链路**：短名 Query 兜底亮图标→JpqlQueryLocator 命中→无 nativeQuery 属性被当 **JPQL** 喂 JpqlConverter→`SELECT *` 等非 JPQL 语法产出错误结果；正确路由=native 透传分支（现成代码 :594-612）；仅命名参数、不支持分页参数（文档明示） |
| `@Modifying`（jdbc/r2dbc 包，纯标记） | ✅ | SD7 | 随 @Query 路由一并识别 |
| R2DBC `@Query`（仅 value；`$1` 原生绑定+SpEL `:#{}`） | ✅ | SD7 | 走 native 透传最干净 |
| R2DBC 返回类型 Mono/Flux | ⬜ | SD4 | notes 标注 Reactive 语义即可，实体解析无需特判 |

### 1.4 实施切分建议

止血期（<5%）：SD6 名单对齐 + SD8 @Table/@Column value 属性 + SD7 @Query 路由 + SD10 集合字段跳过（不展开）；完整期（5-15%）：SD3 级联语义 notes、SD9 prefix/@Sequence、SD5 派生禁 JOIN 门控。R2DBC 增量≈JDBC 的 10-20%。

---

## 2. jOOQ（3.21.8，javap 实证）

> API 面巨大：DSLContext 941 方法签名/188 唯一名；39 个 Select 步进接口；Field 359 签名；DSL 静态工厂 2185 签名。核心链方法名多年稳定，按方法名白名单维护可控。OSS 许可仅覆盖开源数据库方言（影响用户基数判断）。

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| 链入口 `DSL.using(cfg)` 变量 / `DSL.select(...)` 静态 / DSLContext 字段形参 | ⬜ | JO1 | 锚点需 receiver 轻类型推断（比 MP 重一档）；链中间只按方法名分派、无需解析步进类型（友好） |
| `selectFrom/from + where（8 重载）+ whereExists/whereNotExists` | ⬜ | JO2 | where(Condition)/Field<Boolean>/String/SQL 多形态；String/SQL 走 Raw |
| `SelectConditionStep` and/or/andNot/orNot/andExists/orExists | ⬜ | JO2 | 条件延续即隐式 AND，语义同 MP |
| join 14 种（inner/left/right/full/cross/natural/apply/semi/anti）+ on/onKey/using | ⬜ | JO3 | on 列比较→ColumnComparison；onKey 需读生成代码元数据（远期） |
| groupBy/groupByDistinct/having/orderBy（元数化步进）/limit/offset | ⬜ | JO2 | |
| Field 比较：eq/ne/gt/ge/lt/le（+equal/greaterThan 别名）/in/notIn/between(Symmetric)/like 族/isNull/isNotNull/isTrue/isFalse/contains/startsWith/endsWith(+IgnoreCase) | ⬜ | JO4 | 直接映射 Condition.Simple/Unary/In/Between/Like（contains 族→LikePosition）；MP 布尔条件重载在 jOOQ 无对应物（零成本） |
| Field 函数：coalesce/nvl/nullif/decode/cast/coerce/concat/lower/upper/trim/substring/abs/round/extract/窗口 over/case | ⬜ | JO5 | Raw 透传或新增 FunctionExpr 列引用模型 |
| Condition.and/or/not/xor | ⬜ | JO4 | Group（negated 直接映射） |
| 子查询：Select 即 Field/TableLike/Condition（`in(select(...))`/`whereExists`/标量比较） | ⬜ | JO6 | 复用子查询渲染抽象（与 MPJ from/fill 共享的先例） |
| `insertInto(Table, Field...).values(...).onDuplicateKeyUpdate/onConflict` | ⬜ | JO7 | DML 链形态与 MP set(map) 不同（列序 values） |
| `update(Table).set(Field,值)/setNull + where` / `deleteFrom` | ⬜ | JO7 | |
| 原生逃生舱 `where(String,{0})` / `DSL.sql` / `DSL.field(String)` | ⬜ | JO8 | Raw 一等公民（jOOQ 用户高频） |
| 生成代码：Tables.T_USER 常量、TableField 字符串字面量、**零注解** | ⬜ | JO9 | 表/列名靠生成类源码（target/generated-sources/jooq，PSI 可读）读 `TableImpl(new Name("t_user"))`/createField 字面量；依赖 codegen 已跑且源码根被索引，缺失时按字段名 snake 兜底 |
| mergeInto/batch/DDL（createTable/alterTable 等 90 个） | ⬜ | 文档化边界 | Raw 透传 |
| **成本结论** | — | — | **25-35%（>15% 档）**；若做只立 MVP：selectFrom/from+where 核心+join.on+orderBy/limit+DML 三件套，函数/子查询最小支持，其余 Raw |

---

## 3. Querydsl（5.1.0 javax 线；openfeign fork 5.0.1~7.6）

> 条件 API 与 MP 几乎一一对应；Q 类只携带 Java 属性名，列名在 JPA 实体注解——**预览定位为「JPQL 语义渲染成 SQL」（对齐现有 HqlConverter 口径），实体解析全复用 JPA 管线**。注意 org.querydsl 坐标已从 Maven Central 下架，社区主维护线在 io.github.openfeign fork（包名不变，javax→jakarta 断代在 6.x）。

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `JPAQueryFactory` 10 方法（select/selectDistinct/selectFrom/selectOne/from/query/update/delete/insert） | ✅ | QD1 | 锚点与现有 WrapperLocator 初始化器/字段类型识别同构；update/delete/insert 返回独立 Clause |
| JPAQuery 链：where(Predicate...)/groupBy/having/orderBy/limit/offset/restrict + join/innerJoin/leftJoin/rightJoin/fetchJoin/on | ✅ | QD1 | **无 fullJoin**（JPQL 限制，白名单）；fetchJoin→JOIN FETCH 语义 |
| Fetchable 终态：fetch/fetchOne/fetchFirst/iterate/stream | ✅ | QD1 | fetchCount/fetchResults 已弃用（忽略） |
| SimpleExpression：eq/ne/in/notIn/isNull/isNotNull/eqAny/eqAll/count/countDistinct | ✅ | QD2 | 与 MP 1:1 |
| ComparableExpression：gt/goe/lt/loe/between/notBetween/min/max/coalesce | ✅ | QD2 | |
| StringExpression：like/notLike/likeIgnoreCase/contains/startsWith/endsWith/lower/upper/trim/substring/concat | ✅ | QD2 | LikePosition 直接对应 |
| NumberExpression：add/subtract/multiply/divide/mod/abs/sum/avg | 🟡 | QD3 | Raw 或算术 RawExpr |
| BooleanExpression and/or/not/isTrue/isFalse + BooleanBuilder 动态累积 | ✅ | QD2 | Group；BooleanBuilder 链式 and/or 可静态收集 |
| **表达式树 walker**（where 实参是 Operation mixin 表达式树而非平铺链） | ✅ | QD4 | 唯一新件：Predicate→Condition 递归收集器（量级小） |
| Q 类（EntityPathBase + BeanPath.createString/Number/... 属性名字面量） | ✅ | QD5 | APT 源在 generated-sources/annotations，PSI 可读；字段类型名→StringPath/NumberPath 可判；列名映射走实体 @Table/@Column（复用 JpaEntityResolver） |
| JPAExpressions 子查询（select/selectFrom/avg/max/min/treat/type） | ⬜ | QD6 | 复用子查询渲染 |
| Expressions.*Template / CaseBuilder / PathBuilder 动态路径 | ⬜ | 文档化边界 | Template 走 Raw；动态路径 get(String) 可解析 |
| 版本矩阵声明（javax 5.x 主 + fork jakarta 6/7 辅） | — | QD7 | README/描述文档级工作 |
| **成本结论** | — | — | **10-20%（5-15% 上沿）**；结论：做，优先于 jOOQ |

---

## 4. Easy-Query（3.2.15，javap 实证）

> dromara 系、增长快。3.x 无 api4j 包（独立 artifact sql-api4j）；官方主推 **sql-api-proxy 的 APT 代理风格**（EasyEntityQuery→EntityQueryable，lambda 参数是生成 XxxProxy）。框架自带 `toSQL()`，语义核对成本极低。**非 Flex 式两段式 API，Flex 殷鉴不适用。**

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| proxy 入口 `easyEntityQuery.queryable/insertable/updatable/deletable(Class)` | ✅ | EQ1 | 官方主推；锚点=现有链首识别（qualifier 类型+方法名）同构 |
| core 入口 `easyQueryClient.queryable(...)`（条件为 String 列名+布尔重载） | ⬜ | EQ2 | 与 MP Wrapper **9/10 同构**（布尔重载/and-or 闭包/limit/orderBy 全对应） |
| sql-api4j 方法引用版 `eq(DefTable::getId, id)` | ⬜ | EQ3 | Property 接口 Serializable——与 MP SFunction 同基建（SerializedLambda 语义名解析复用）；二期 |
| proxy 条件 `o.name().eq()/like()/in()/isNull()`（void 注册式 lambda 块） | ✅ | EQ4 | 7.5/10：lambda 体内是分号分隔的注册语句（非纯链式 qualifier），现有 lambda 收集器需增强为「块内语句遍历」 |
| 布尔前置重载贯穿（`where(boolean, lambda)`/`eq(boolean,...)`） | ✅ | EQ5 | ConditionToggle 直接复用 |
| and/or 闭包嵌套 + `whereObject(dto)` | ✅ | EQ5 | NestedArg 复用；whereObject≈MP 实体条件形态 |
| `leftJoin(Class, (a,b) -> a.uid().eq(b.id()))` 双参 on lambda | ✅ | EQ6 | 与 MPJ on-lambda 同构（MpjJoinSupport.parseOnLambda 思路复用）；@Navigate 声明关联 |
| range 族：rangeClosed/rangeOpen/rangeOpenClosed/rangeClosedOpen | ✅ | EQ7 | Between 家族映射（开闭区间语义需在渲染层区分） |
| 实体注解 @Table(value,schema,oldName)/@Column(value,primaryKey,exist)/@ColumnIgnore/@InsertIgnore/@UpdateIgnore | ✅ | EQ8 | 与 JPA 形态几乎一致——JpaEntityResolver 轨道复制改注即可；默认命名同为驼峰转下划线 |
| @LogicDelete(strategy) / @Version / @Navigate(RelationTypeEnum,...) | ✅ | EQ9 | 逻辑删除/乐观锁注入复用中央注入点模式；@Navigate 关联声明 |
| 生成类 XxxProxy（`getStringColumn("name",...)` 字面量） | ✅ | EQ8 | APT 源静态可读，作列名二级来源 |
| toPageResult/limit/distinct/union | ✅ | EQ5 | EntityPageAble1 |
| 写路径 insertable(entity)/updatable+setColumns/deletable + 条件版 ClientExpressionUpdatable/Deletable | ✅ | EQ10 | BatchModel 模板可复用 |
| **成本结论** | — | — | **8-12%（5-15% 档）**；结论：立项，proxy 优先 MVP、core/api4j 二期 |

---

## 5. Jimmer（0.9.96，javap 实证）

> 谓词一等对象 + varargs 组合 + prop 导航隐式 join + 接口实体（注解在 getter 方法上）——五个维度全部偏离 Wrapper 链范式，反而与已支持的 JPA Criteria 系同族。**暂缓（Flex 同判）**；重启条件：用户反馈集中、或 Criteria 族表达式树基建（QD4/JM4 可共用）落地后按 Criteria 轨道立项。

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `sqlClient.createQuery(BookTable.$)` + 表代理变量（`var book = BookTable.$` 跨语句） | ⬜ | JM1 | 变量跟踪轨道扩展到「表变量」 |
| prop 导航隐式 join：`book.store().authors()` | ⬜ | JM2 | 需关联图解析（@ManyToOne 方向+@JoinColumn） |
| Expression eq/ne/in/isNull + `*If(boolean,...)` 布尔变体 | ⬜ | JM3 | |
| StringExpression like/ilike(String, LikeMode) | ⬜ | JM3 | |
| Predicate 一等对象（and(Predicate...)/or/not 静态组合）+ `where(Predicate...)` varargs 隐式 AND + 局部谓词变量 | ⬜ | JM4 | 表达式树收集器——可参照 CriteriaQueryConverter 先例（与 QD4 共用基建） |
| createUpdate().set(PropExpression,值)/createDelete | ⬜ | JM5 | |
| 实体注解 @Entity/@Table/@Id/@Column/@JoinColumn/@OneToMany/@LogicalDeleted/@Default（**接口 getter 方法级**） | ⬜ | JM6 | 注解名与 JPA 同形但挂方法上——JpaEntityResolver 需 PsiMethod 级适配 |
| fetchBy/Fetcher/DTO 语言/Entities.save(saveCommand) | ⬜ | 文档化边界 | 投影/保存 DSL 的 SQL 预览价值低 |
| **成本结论** | — | — | **>15% 档**；相似度 3.5/10；暂缓 |

---

## 6. xbatis（原 mybatis-mp；cn.xbatis 1.10.8，2026-08-21）

> 旧坐标 cn.mybatis-mp 冻结在 1.8.1-RC3（2025-02），新坐标高频迭代。MP 同构 wrapper 链（cmd/method 81 类方法名基本对齐 eq/gt/like/in/orderBy），条件用 `Entity::getXxx` 方法引用。**全清单最易适配**。

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `QueryWrapper.of(User.class).eq(User::getId, 1L).like(User::getName, "a").list(userDao)` | ✅ | XB1 | 复用 LambdaQueryConverter 语义：方法名别名表 + QueryWrapperParser 前置分派 |
| QueryChain / IService 风格链 | ✅ | XB1 | 同上 |
| 双坐标识别（cn.mybatis-mp 旧版存量项目 + cn.xbatis 新版） | ✅ | XB2 | wrapperFactoryClassNames 补两组 |
| **成本结论** | — | — | **<5%**；P0 |

---

## 7. MyBatis Dynamic SQL（org.mybatis.dynamic-sql 2.0.0，2026-03）

> MyBatis 官方 DSL：手写 SqlTable 常量（无生成代码依赖）→ builder 链 → build() 出 SelectModel（AST）→ render(RenderingStrategy) 出 SQL。结构极其规整，2.0 新增独立 dsl 包与 RenderingContext。

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `SelectDSL.select(cols).from(table[,alias]).where(col, isEqualTo(1).and(col2, isIn(...)))` | ✅ | DS1 | 新建 MdsConverter：链→现有 Condition 模型→14 方言渲染 |
| SqlTable.of("t_user") / table.column("id", JDBCType) 手写常量 | ✅ | DS2 | 列名直接可读（无生成代码） |
| VisitableCondition 谓词 50+（isEqualTo/isNotEqualTo/isIn/notIn/isLike/isBetween/isNotNull/isNull/...） | ✅ | DS3 | 高频 20 个先覆盖 + 未支持条件降级提示 |
| RenderingStrategy：MyBatis3（#{} / p1,p2）与 SpringNamedParameter（:name） | ✅ | DS4 | 占位符形态与插件参数面板两套对齐 |
| orderBy(descending)/groupBy/having/limit/fetchFirst/offsetWhenPresent/union/join(JoinType,...) | ✅ | DS1 | |
| update/delete/insert(+multiple/insertSelect) DSL + build().render() | ✅ | DS5 | 终态拦截点 |
| **成本结论** | — | — | **5-15%**；P0 候选 |

---

## 8. Fluent MyBatis（com.github.atool 1.10.1，2025-12；低频维护）

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| `new EmployeeQuery().select.id().userName().end().where.age().eq(18).userName().likeLeft("张").and(q -> q.salary().gt(3000).or.salary().lt(1000)).end().orderBy.desc.salary().end().limit(0,10)` | ⬜ | FM1 | 链在**编译期生成类**上（列方法=生成方法）；`where.and/or` 字段式入口与 MP 的 and(consumer) 不同，需专用链模型 |
| 布尔 if 条件 / Predicate 生效重载（MP 风格 condition 参数） | ⬜ | FM1 | ConditionToggle 复用 |
| 生成物 XxxQuery/QueryWhere/XxxUpdater/TableMeta（target/generated-sources/annotations） | ⌛ | FM2 | PSI 可读；QueryWhere 列方法名=驼峰属性名→Entity 字段映射 |
| eqByEntity/eqByExclude/eqMap/applyFunc/exists/notExists/union | ⬜ | FM3 | |
| **成本结论** | — | — | **5-15%**；P1（市场在萎缩，按用户反馈排期） |

---

## 9. dbVisitor（net.hasor 6.7.0，2026-02-14）

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| Mapper `@Query/@Update` SQL 注解（paramMapping） | ⬜ | DV1 | 近现有注解 SQL 通道（<5% 增量） |
| `lambdaQuery(User.class).eq(User::getName, "x").query()`（LambdaTemplate） | ⬜ | DV2 | (a) 族方法映射（5-15%） |
| XML mapper + tiny 模板 | ⬜ | 文档化边界 | 后置 |
| **成本结论** | — | — | **5-15%**；P1 |

---

## 10. BeetlSQL / sqltoy / lazy-orm（长尾）

| API / 形态 | 插件现状 | 对应任务 | 备注 |
|------|:----:|------|------|
| BeetlSQL 3 `*.md` 模板（sqlId 定位/`#name` 占位/`@if(){}` 条件块，com.ibeetl 3.40.1） | ⬜ | BS1 | 复用 XML 引擎轨道：sqlId↔md 跳转 + 无条件块静态预览 + 条件块灰显；完整 beetl 模板引擎自实现 >15% |
| BeetlSQL LambdaQuery（`sqlManager.lambdaQuery(User.class).andEq(User::getName,"hi")`）/QueryChain/SQLReady | ⬜ | BS2 | lambda 轨 5-15% |
| sqltoy XML `<sql id><value>` + `#[and x=:param]` 条件占位（com.sagframe 5.6.94.RC6） | ⬜ | 文档化边界 | 静态骨架+参数面板可到 5-15%；缓存翻译/跨库函数 >15%；已有第三方 sqltoy-idea-plugin 先例 |
| lazy-orm（top.wu2020 1.3.6，@LazyTable/@LazyColumn 自研链） | ⬜ | 文档化边界 | 2025-11 后无发版，社区小，仅留扩展点 |

---

## 11. 目标状态填写规则

与《MP-MPJ官方API覆盖矩阵》§3 一致：⬜/🟡 项均为文档化边界或待立项项，插件对不支持面必须优雅降级（可读占位提示），不得抛异常。立项一项、启用对应任务号段、交付一行 ⬜/🟡 → ✅ 并同步 fixture 测试；❗ 行（当前产出错误结果）优先级恒高于普通 ⬜。

## 12. 信息来源

| 来源 | 版本 | 核实内容 |
|------|------|------|
| 本地 jar javap/unzip（唯一权威） | spring-data-relational/jdbc/r2dbc 4.1.1、jooq 3.21.8、querydsl-jpa 5.1.0（core 5.1.1 openfeign fork）、sql-core/sql-api-proxy 3.2.15、jimmer-core/sql 0.9.96、fluent-mybatis 1.10.1、mybatis-dynamic-sql 2.0.0 | 注解属性、接口方法签名、生成代码构造约定、@Query 属性集差异 |
| Maven Central metadata | 2026-09-05 实时 | 各框架最新版与发布日期（xbatis 1.10.8/BeetlSQL 3.40.1/sqltoy 5.6.94.RC6/dbvisitor 6.7.0/lazy 1.3.6） |
| 官方文档 | docs.spring.io/relational、easy-query.com、babyfish-ct.github.io/jimmer-doc、jooq.org | 命名策略/派生查询限制/save 语义、api4j 与 proxy 两套 API、Jimmer 查询样例、jOOQ 步进接口 |
| 插件源码推演 | worktree mpsv-orm（feature/m39-orm-coverage） | Spring Data JDBC 现状逐场景判定（JpaCrudOperationConverter/DerivedMethodLocator/JpaEntityResolver/JpqlQueryLocator 行号级证据） |
| 存证 | F:\code\orm-research-jars\（临时） | 11 个研究用 jar |

## 13. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-05 | **M44 第一批交付勾行（分支 feature/m44-orm-batch2）**：SD 止血 4 任务（含两处 ❗ 错误输出链路修复：JDBC @Query native 路由、@Column value）、xbatis 全量、Easy-Query proxy MVP、MyBatis Dynamic SQL、Querydsl MVP 共 24 个任务行置 ✅/🟡；单智能体实施 + 编排者集中测试，修复三处共性根因（collectSegments 引用层断链、xbatis 泛型形参误当实体、cn.xbatis.* 前缀宽松匹配误判变量）；全量 3353 测试 0 失败 |
| 2026-09-05 | 初版：四路智能体并行调研（Spring Data JDBC/R2DBC、jOOQ+Querydsl、Easy-Query+Jimmer、国产长尾 7 框架）——建立 10 个框架的 API 覆盖行与立项决策表；关键发现：Spring Data JDBC 存在「侥幸可用」面与两处 ❗ 错误输出链路（@Column value 丢失、JDBC @Query 被当 JPQL）、xbatis 成本 <5% 最易、Querydsl 优于 jOOQ（10-20% vs 25-35%）、Easy-Query 与 MP 9/10 同构可立项、Jimmer 与 Flex 同判暂缓；org.querydsl 坐标已从 Central 下架（openfeign fork 接续）的版本风险记录 |
| 2026-10-27 | **合并状态订正**：核实 `a3ccca6c`（M44 第一批）已在 master 历史，`converter/` 下 EasyQuery/Xbatis/Mds/Querydsl 四个转换器均在主线且后续有修复（`e8909d70` SPI 穿 project、`4d6a976f` flagSource 口径统一、`6062c26d` 同构普查）；原「分支未合并 master」表述作废。矩阵状态列无变化（仍待立项项不变） |
