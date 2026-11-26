---
name: project-embedded-col-prefix-gap
description: 用户报障「预览 SQL 的 @Embeddable 识别不正确、缺了一部分变量」——20261013 根因=嵌入列名写死「无前缀」一种约定，A+B+校正兜底+探测按模块收范围四笔已提交；20261016 用户补「实体都在 jar 里」⇒ 线二收窄并实施 W1（信号面）+W1b（自定义策略语义识别）+W2（EntityClassFinder 库兜底**最终版：撤掉 notScope，改 allScope**，性能红线=只做快路径）；W3 已派子智能体先证伪；**用户拍板「按推荐实施」⇒ 优化方案 1+2 正在实施（库全库扫描缓存+弹窗显式触发）**
metadata:
  node_type: memory
  type: project
  originSessionId: sess_00a325f2-4943-4c18-87d1-b80d31784e6f
---

2026-10-13 用户报障：HQL `inv.qty.baseQty` 被预览成 `AND inv.base_qty > 0`，
**真库列名是 `qty_base_qty`**（用户原话「缺了一部分变量」）；同句 `inv.moveTool.palletNo` → `inv.pallet_no`。
用户实体：`@Embedded protected WmsMoveTool moveTool;` / `WmsSku sku;` / `WmsInventoryQty qty;`。
HQL 是 `+` 拼接字面量，经 `commonDao.findByQuery(...)`（自定义 DAO 通道）。

计划：`docs/开发计划/20261013-1343-T-EmbeddedColPrefix-嵌入列前缀缺失致预览列名错误-开发计划.md`（197 行）。
用户第二轮说「你先按照推荐实施 可以反编译 Hibernate 的依赖辅助」⇒ 已按 **A+B 实施**。

## 根因（代码级定位，非推断）

**错在列名推导，不在「嵌入段去留」**：`inv.qty.baseQty` 在
`JpaAliasPropertyMapper.mapProperties`（`converter/jpa/JpaAliasTableResolver.kt:249`）里切成
qualifier `inv` / 中间段 `qty.` / 属性 `baseQty`，取到展平列后在嵌入分支
`isFlattenedEmbeddedPath`（同文件 `:325`）命中 → `:267` 输出 `qualifier + column.columnName`
= **`inv.base_qty`**。去掉 `qty.` **按设计做的、也是对的**（嵌入对象没有自己的表，`inv.qty`
留在 SQL 里会被当成「库.表」限定；口径写在 `ColumnInfo.embeddedParent` KDoc）。错的是
`column.columnName` 这个值本身。

`columnName` 推导链在 `psi/jpa/JpaMetadataReader.kt` 的 `flattenEmbeddableClass`：
`@AttributeOverride`（三级键）> 内层 `@Column(name)` > `NamingStrategy.toSnakeCase(fieldName)` 兜底
（`:750`）。旧 KDoc 明写「**JPA 无隐式列名前缀**」——插件假设嵌入列名＝内层字段名本身；
仓内用例正这么断言（`EntityResolverEmbeddedTest` 断言 `street`→`street`、
`HqlBarePropertyMappingTest` 夹具 `ColumnInfo("beDelete","BE_DELETE",embedded=true)` 不带前缀）。
⇒ 插件**只实现「无前缀」一种约定、既无开关也无探测**，实体本身不携带该信息，故工程用前缀约定时
嵌入列名必错，且是**静默错**。

## 前缀形态：hibernate-core 源码实证（关键，别再靠猜）

用 `.m2` 里 **`hibernate-core-5.6.4.Final-sources.jar`**（sources jar 比反编译更权威）直接读源码：

- **只有 `ImplicitNamingStrategyComponentPathImpl` 加前缀**：
  `transformAttributePath` 递归 `process()`，`parent != null` 时先拼父路径再 `sb.append('_')`，
  用**原始属性名**（`qty` + `_` + `baseQty`）；下划线化由 **PhysicalNamingStrategy** 完成。
  ⇒ 插件里等价实现＝「各段蛇形后以 `_` 连接」＝ 对连接后的整串做一次 `toSnakeCase`
  （`toSnakeCase("qty_baseQty")` == `qty_base_qty`）。**多级嵌入＝整条链**（`a.b.c`→`a_b_c`，非 `b_c`）。
- 基类 `ImplicitNamingStrategyJpaCompliantImpl.transformAttributePath` 只返回
  `attributePath.getProperty()`（末段）⇒ 无前缀。
- **`ImplicitNamingStrategyLegacyJpaImpl` / `LegacyHbmImpl` 都不覆写 `transformAttributePath`**
  ⇒ 它们**不加**前缀（≠ component-path）。别把 `legacy*` 也当成前缀档。
- `component-path` 也可配**全名**；Hibernate 4 的 `DefaultComponentSafeNamingStrategy` 同源（老工程仍可能配）。

## 已实施（A+B；改动 8 文件改 + 4 文件新增，未提交）

- **新增 `psi/jpa/EmbeddedColumnNaming.kt`**：`enum EmbeddedColumnNaming { NONE, COMPONENT_PATH }`
  + `object EmbeddedColumnNamingResolver.resolve(project)`——优先级
  **显式设置 > 工程配置探测 > 默认 NONE**。默认 NONE 是为了 JPA 默认工程与既有用例逐字不回归。
- **新增 `psi/jpa/HibernateNamingConfigProbe.kt`**：扫 `application*.yml/.yaml/.properties`
  （`FilenameIndex.getAllFilesByExt` + 名字前缀 `application`）命中标记即判前缀档；标记
  `ImplicitNamingStrategyComponentPathImpl` / `DefaultComponentSafeNamingStrategy` /
  `component-path` / `component_path`（**大小写不敏感**）；结果按
  `PsiModificationTracker.MODIFICATION_COUNT` 缓存（编辑自动失效）。`indicatesComponentPath(text)`
  抽出为**纯文本可单测**形态，**剔除整行注释**（`#`/`!` 起首）——被注释掉的配置不是生效配置。
  取舍＝**只认强信号**：宁可漏判（用户可显式开）也不误判（误判会把默认工程改错）。
- **`JpaMetadataReader`**：`flattenEmbeddedColumns` 加 `naming: EmbeddedColumnNaming = EmbeddedColumnNaming.NONE`
  参数；`flattenEmbeddableClass` 新增 `snakePathPrefix`（原始段名以 `_` 连接、含尾随 `_`）与 `naming`；
  兜底改 `embeddedFallbackColumnName(snakePathPrefix, fieldName, naming)`。**两档只影响兜底档**——
  `@AttributeOverride` / 内层 `@Column(name)` 命中时**恒优先、不叠前缀**（`override ?: column ?: fallback` 链不变）。
  KDoc「JPA 无隐式列名前缀」已更正。
- **`psi/jpa/JpaEntityResolver.kt`**：`flattenEmbeddedColumns` 里
  `val naming = EmbeddedColumnNamingResolver.resolve(field.project)` 传入。
- **设置项（复用 `reverseWrapperFormat` 全套先例）**：`PluginSettings.State.embeddedColumnNaming:String = EMBEDDED_NAMING_AUTO`
  + 三常量 `EMBEDDED_NAMING_AUTO/NONE/COMPONENT_PATH`；`PluginSettingsPanel` 加
  `EmbeddedNamingOption` data class（**JDK17 `JComboBox.setSelectedItem` 按 equals 匹配，必须 data class**）、
  `embeddedNamingCombo`、`embeddedNamingOptions()`；**isModified / apply / reset 四处都要挂**
  （`PluginSettingsPanelBindingTest` 的前车之鉴＝`reverseWrapperFormat` 漏挂 isModified ⇒ 改动被平台静默丢弃的**假开关**）；
  `PluginSettingsPanelSections` 在**表映射组**（`SECTION_TABLE_MAPPING`，`columnCalibrationCheck` 行后）加一行；
  `PluginSettingsFolderConfigurable` 加 `SearchableField`；bundle 中英各 5 键。
- **`sdPrefix`（Spring Data `@Embedded(prefix=...)`）与 Hibernate 前缀互斥**，不得叠成 `biz_qty_`；
  `fromAttributeOverride` 门控决定「覆写命中不叠前缀」。

## 影响面＝一处根因、两处产物（修在元数据层一处即同修）

`columnName` 同时喂：①WHERE/条件段属性映射；②SELECT 子句展开
（`converter/jpa/SelectClauseExpander.kt:122` 用 `table.columns.map { it.columnName }`）。
Wrapper 渲染/批量模板/补全等共用列清单的链路一并受益，**不需要在 mapper 里再打补丁**。

## 方案候选（计划 §三；已选 A+B）

- **A 读工程 Hibernate 命名配置探测（已实施）** + **B 设置项兜底/可显式纠正（已实施）**。
- C 以真库列名为准（读 JDBC metadata 校正）——最权威但把转换器绑上 DB、离线不可用，**未做**，登记候选。
- D 不静默猜：嵌入列名来自 snake 兜底时给可读提示（成本低，可作加固，**未做**）。

## 已提交清单（20261013，两仓四笔，**全未 push**）

插件仓：`a33466c9`（A+B+列名推导接前缀）、`86e2eb42`（校正层嵌入段路径兜底）、`6919d8ef`（探测按模块收范围）；
demo 仓：`5e1bcfa`（新增 `jpa-embedded-prefix-demo` 复现载体）。
定向读数：16 类 **222 例 0 失败 0 错误**；反向验证＝前缀档短路 → 3 例必红（组件路径前缀档 / 两级嵌入链 /
端到端产物），无前缀档/自动档/覆写优先/内层 `@Column` 优先四类保持绿。

## 待用户材料（机制已由源码坐实，但用户工程的实际来源仍未证）

用户只给了 `qty_base_qty` 一例，`moveTool.palletNo` 未逐字给出（按同规则推 `move_tool_pallet_no`，需实测）。
仍需：①`WmsInventory` 实体**完整源码**（重点：类级有没有 `@AttributeOverrides`——若其实有覆写，
方向就变成「覆写没被读到」而非命名约定）；②`application*.yml/properties` 的命名配置；
③该表真实列清单（`desc`/`SHOW COLUMNS`）。**「前缀从哪来」是推断**——插件只看到实体、推不出命名策略；
A 的探测是补这个缺口，但探测器本身只认白名单里的强信号。

## 20261016 续报：截图三处错名 = 同一条线；「jar 里的嵌套类」是**另一条线**

用户再贴截图（HQL `SELECT inventory FROM WmsInventory inventory LEFT JOIN WmsItemKey itemKey …`
含 `inventory.qty.baseQty` / `inventory.sku.inventoryStatus` / `itemKey.lotInfo.receivedDate`），
预览产物分别丢 `qty_` / `sku_` / `lot_info_` 前缀，并称「**jar 包里的嵌套类没有正常识别**」。

**先分清两条线（别混）**：

- **线一（已确证、已修未发布）**：截图三处错名形态完全一致＝**嵌入段被正确去掉、前缀没加上**，
  与本文档上文同一根因、同一修复（`a33466c9` 起）。用户装机版本没有该修复（该修复**在最新已发布
  tag 2026.10.9 上也不存在**）⇒ 光升级即应消失（前提是 A 或 C 能命中）。
  截图另有 `inventory.frozenStatus` **未映射**（同句 `operationStatus`/`beLockStatus` 都映射了）——
  最简解释＝该字段显式 `@Column(name="frozenStatus")`（列名与字段名逐字相同 ⇒ 映射器原样返回）；
  也可能是 `@Transient` 或注解写在 getter（`@Access(PROPERTY)`）。**不属该任务目标**，登记待用户实体源码。
- **线二（本次要补的）＝JAR 场景的识别面与信号源**（用户说的「jar 里的类」落在这里，
  但**现有信息不足以判定是哪一环断**——截图能证明实体被解析到了，证不了哪些类来自 JAR）。

**四条代码级 JAR 边界（20261016 读码所得，带行号，实施前先照 W0 取证别先改）**：

1. **表名→实体反查只扫工程源码**：`psi/EntityClassFinder.kt:41/88/97` 三处一律
   `GlobalSearchScope.projectScope` ⇒ 依赖 JAR 里的实体**反查不到**（消费方＝`reverse/SqlToJavaConverter.kt:232`）。
2. **前向解析却走 allScope**：`ui/SqlPreviewServiceEnhancements.kt:510-511` 用
   `findClass(name, allScope)` ⇒ JAR 里实体**能**解析（这也解释了截图里列名确实被映射出来了）。
   **两条路 scope 口径不一致**，是最值得收口的结构性问题。
3. **class 文件 PSI 的注解判定比源码脆（最可能的单点）**：`JpaMetadataReader.findFlex:147` 第三轨靠
   `annotation.nameReferenceElement?.referenceName` 兜底（`:154`），而 **class 文件 PSI 没有
   nameReferenceElement** ⇒ 该轨对 JAR 类恒不命中；前两轨走 `findAnnotation(fqn)` 要求注解类在 classpath。
   同类按全名判定的还有 `isLikelyMappedEntityClass`（`:526` `annotation.qualifiedName ?: continue`）与
   `isSpringDataEmbeddedField`（`:204`、`springDataEmbeddedPrefixOf` 一带 `:645`）。
4. **A 探测在 JAR 实体上没有信号源**：`moduleResourcesDir` 靠「自实体文件路径上溯找最近 `pom.xml`/
   `build.gradle*`」定位模块，而 JAR 条目路径（`…wms-model.jar!/cn/song/…`）不是本地文件 ⇒ 回落工程全扫；
   而**命名策略往往根本不在用户工程里**（在共享模型 JAR 自己的模块/构建里）⇒ A 怎么扫都判不出。
   叠加 C 通道也不可用（截图预览面板明写「**未绑定数据源**」⇒ `ColumnNameCalibrator` 原样返回零改动）
   ⇒ 只剩手动设置项 B。
5. **无「同名双份」串味风险（排除项）**：`JpaEntityResolver` 缓存键含 `file.virtualFile?.url` +
   `System.identityHashCode(file)`（`:75`），源码与 JAR 两份同名类不会互相命中。

**出计划（本轮只出计划）**：`docs/开发计划/20261016-1020-T-JarEmbedded-依赖JAR内实体与嵌入类识别缺口-开发计划.md`
（156 行，**未提交**）。**W0 先取证必做**（不是先改码）：照 `selfdev-shared-lib`+`selfdev-framework-demo`
先例造四种 JAR 形态（J1 实体与嵌入类都在 JAR / J2 实体在源码而嵌入类在 JAR / J3 嵌入类型是 JAR 里的
**嵌套类** / J4 `@MappedSuperclass` 父类在 JAR），逐一实测「插件能读到什么」再定修法。
预置修法 W1（给 A 扩信号源：扫 classpath/JAR 侧 `persistence.xml`、`hibernate.cfg.xml`、JAR 内
`application*.yml`）/ W2（`EntityClassFinder` scope 改「源码优先＋库兜底」+ 定同名双份择优规则，需评估
全库扫描性能）/ W3（补齐 class 文件注解判定，最可能只需这条）/ W4（列名来自兜底时给可发现提示）。
**需要用户三件材料**才能钉死线二：①哪些实体/嵌入类在 JAR、②命名策略写在哪（工程 yml / 自定义类 /
JAR 内声明）、③用户装的插件版本（About 内部构建时间戳）。**「嵌套类」要按两种理解都造语料**
（`@Embeddable` 嵌入类 vs 字面 `static class`）。

## 🔴 20261016 用户补两句关键信息 → 线二收窄并已实施 W1（master 本地 `1c8d149d`）

**用户补料两句**：①「这些实体类**都是 jar 里的**」②上一轮已答「**没有用 @AttributeOverride**」。

**第一句把范围收窄了（推导链，重要）**：截图产物里 `inventory.operation_status` /
`be_lock_status` / `item_key_id` **都被正确映射出来了** ⇒ 插件**已读到 JAR 里实体的列清单**，
`@Embedded`/`@Embeddable` 识别与展平也都在跑。故 §上「线二四条 JAR 边界」里最可疑的第 3 条
（class 文件注解判定脆弱）**被截图反证掉一半**——「读 JAR 类」这件事本身没坏。
**真正缺的只是「这个工程用了 component-path」这个信号**：A 探测在实体来自 JAR 时模块根不可知
（回落工程全扫），而**命名策略往往写在 Java 代码里**（`props.put("hibernate.implicit_naming_strategy",
ImplicitNamingStrategyComponentPathImpl.class)`）或 `META-INF/persistence.xml` —— 这两处此前**一个都不看**。

**已实施（W1，`1c8d149d`，未 push）**：`HibernateNamingConfigProbe` 扫描面分两档——
- **模块已知**（实体在工程源码里）：行为不变，只看该模块 `src/main/resources`（含 `META-INF/`），防兄弟模块外溢；
- **模块不可知**（实体来自依赖 JAR）：退到工程级信号，**三条互补的路**：
  ① `application*.{yml,yaml,properties}` 按扩展名索引扫（覆盖 Boot 连字符键 `naming.implicit-strategy`）；
  ② 按**设置键 `implicit_naming_strategy`** 走词索引找**任意文件**（不限类型 ⇒ `persistence.xml`/`orm.xml`/
     自定义 properties 一并覆盖；命中后读该文件 `text` 再走同一判据 `indicatesComponentPath`）；
  ③ 词索引在 **Java 源码**里找 `ImplicitNamingStrategyComponentPathImpl` / `DefaultComponentSafeNamingStrategy`
     类名（覆盖代码式配置，此前的最大盲区）。
  判据本身未改（纯文本标记匹配 + 剔整行注释），故既有纯逻辑用例不回归。

**读数**：10 类 **125 例 0 失败 0 错误**（含 `SettingsRealityGuardTest`、demo 锚点门禁
`DemoEmbeddedPrefixReproAnchorTest`、`CustomDaoHqlLocatorTest`/`HqlQueryLocatorTest`、`ColumnNameCalibratorTest`）。
**反向验证**：把「模块不可知 → 工程级信号」整档短路为 `false` → 新增
`HibernateNamingConfigProbeProjectTest` **2 例红**（源码式配置 / `persistence.xml`），
「无信号回落无前缀」保持绿，`GRADLE_EXIT=1`；还原后复跑 125/0/0。

**🔴 平台 API 三坑（243，实施时连踩，别重复试）**：
- `PsiElementProcessor` 在 **`com.intellij.psi.search`**（不是 `com.intellij.psi`）；
- `PsiSearchHelper.processElementsWithWord` **只接受 `TextOccurenceProcessor`**
  （`boolean execute(element, offsetInElement)`），整个重载族没有 `PsiElementProcessor` 版；
- `FilenameIndex.getFilesByName` 返回 **`Array<PsiFile>`**（不是 `Collection<VirtualFile>`，
  与 `getAllFilesByExt` 不同）——而且**按文件名查表在 light 测试里看不到 fixture 文件**
  （首轮那条 `persistence.xml` 用例因此红）；改走「设置键词索引」后既更通用也可测。
- 查平台 API 的最快路：本机有 **`ideaIC-243.28141.41-sources.jar`**
  （`F:/gradle-home/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/243.28141.41/*/`），
  零依赖 python zipfile 直接读 `.java` 源，比 javap 反汇编准。

**子智能体已派出（demo 仓，后台）**：造 `selfdev-shared-lib` 式 JAR 载体——模型 JAR 模块
（`@Entity` + 三个 `@Embedded` + 静态嵌套类 `WmsItemKey.LotInfo`，嵌入字段零注解）+ 宿主 Boot 模块
（**8088** / 库 `jpa_demo`，端口 8081~8087 已占），命名配置分「yml 里写」与「Java 代码里 put」两份
验 W1 两条信号路；要求真跑读 `information_schema`、杀干净进程、只提交 demo 仓。

**仍待确认**：若用户工程是用**自定义 `ImplicitNamingStrategy` 实现类**加前缀，上面的类名白名单
**匹配不到** ⇒ 只能靠设置项 B 或数据源校正 C（§七.1 仍未证）。

## 🔴 20261016 后续：自定义策略「能自动识别」→ W1b 已实施（`60c7b3f1`）+ 索引补齐（`7e3ca658`）

用户追问「自定义的 `ImplicitNamingStrategy` 实现类能不能自动识别？」——**能，但必须按语义特征判**，
不能「见到自定义策略就算」：**多数自定义策略只改表名**（加 `t_` 前缀/去 Entity 后缀），对嵌入列毫无
影响；那种工程若被当成组件路径档，反而把列名改错。

**判据（API 事实取自 hibernate-core 5.6.4 源码，非推断，可复用）**：`AttributePath` 上**只有**
`getParent()` / `getFullPath()` / `getDepth()` 能拿到**父段/整条路径**；内置组件路径策略正是
`getProperty()` + `getParent()` 递归拼接，而只取末端属性名的策略不会碰这三个方法。
⇒ 新判据＝**文件既参与隐式命名（`implements`/`extends` 命名策略类型）又用到 `AttributePath` 的
父段/全路径**（词索引找候选文件 + 文件文本语义特征；与 W1 两条同走工程级信号）。

**测试**：`HibernateNamingConfigProbeProjectTest` +2——「自定义组件路径策略被语义识别」（递归取
`getParent()` 拼 `_` → 判是）与「**只改表名的自定义策略不误判**」（只重写 `determinePrimaryTableName`、
不碰 `AttributePath` → 判否）；该类 **5 例全绿**；本轮最终 10 类 **127 例 0 失败 0 错误**。

**如实登记的边界（勿当已解决）**：①自定义策略类若在**依赖 JAR 里**（工程无源码）→ 文本特征看不到，
仍需设置项 B 或数据源校正 C；②自定义策略若用**别的连接符/形态**（不是 `_` 连接 + snake 化）→
插件按内置 component-path 口径渲染仍可能与真库不同（本轮不猜连接符）；③判据是文本特征、不解析控制流。

**demo 仓 JAR 载体已就位（子智能体，`74cc257`）**：模型 JAR 模块 `jpa-jar-model-lib`（`@Entity`
+ 三个 `@Embedded`，含 `WmsItemKey` 内**静态嵌套类** `LotInfo`，嵌入字段零注解）+ 宿主 Boot 模块
`jpa-jar-model-demo`（**8088** / 库 `jpa_demo` / 表 `wms_inventory_jar_demo`），命名约定备
**yml 式**与 **Java 代码式**（`@Profile("code-naming")` + `HibernatePropertiesCustomizer`）两份。
真跑建表 DDL 实测列名＝`id, item_key_lot_info_received_date, qty_base_qty, sku_inventory_status,
warehouse_id`；`/jar-model/columns` 同；`/jar-model/query`（plain/buffer）各 rowCount=1。

**🔴 子智能体抓到我派活任务书里的一处错（值得记）**：第三条真库列名是
**`item_key_lot_info_received_date`**，不是我任务书里写的 `lot_info_received_date`——组件路径把**整条链**
`itemKey.lotInfo.receivedDate` 每段都并进列名（与上文「多级嵌入＝整条链」一致，`BinderHelper.getRelativePath`
返回相对实体根的完整属性链）。子智能体按真实语义实现、把出入写进 README 与提交信息，**没有照错的写**。
⇒ **派活时写的期望值若与源码语义冲突，要允许执行方afvig并如实回报**（另：`lot_info_received_date` 是
`item_key_lot_info_received_date` 的**后缀子串**，子串匹配能糊过去、整列名精确匹配过不了）。

**插件侧补门禁** `ui/gutter/DemoJarModelReproAnchorTest`（新增）：加载宿主服务源码，断言两处
`wmsJarCommonDao.findByQueryMaxNum(...)` **各恰 1 枚图标**——防「复现用例自己不可预览」。

**索引维护（顺带，用户点名）**：`docs/索引/开发计划.md` 补齐 **11 份漏登记**（含本计划、
T-EmbeddedColPrefix、T-HqlBufConcat、T-SqlTemplateCaret/Ux、T-DialectQuoteWarn、T-PerfSweep25/1719、
竞品对比第二十六轮/第五~十三轮、T-PrewarmWilDeadlock）+ 修 **1 条失效链接**（`20261015-1830-…PrepareFreeze`
已被改名成 `20261015-1842-T-PrewarmWilDeadlock` 且原文件已删，另一会话改名时漏回填）。
**复核口径（可照抄）**：python 逐名比对 `os.listdir(docs/开发计划)` vs 索引里 `](../开发计划/xxx.md)` 的
引用集合 → 目录 **553 / 索引 553 / 漏登记 0 / 失效链接 0 / 重复 0**。**索引 URL 用正斜杠、文件名含中文，
命令行直接写中文文件名必乱码 ⇒ 一律 python heredoc 读文件**（本轮自己在 bash 里写中文名踩了一次）。

## 🔴 20261016 再续：W2 已实施（`EntityClassFinder` 库兜底，**性能红线是本轮主约束**）

用户令「W2 继续」，并在中途插话「**需留意 不要出现性能红线问题**」——本轮据此做了**范围收缩**。

**缺口**：`EntityClassFinder` 两条查找路（候选名快路径 `findByCandidateNames` + `@TableName` 全类扫描
慢路径 `findByTableNameAnnotationCached`）此前一律 `GlobalSearchScope.projectScope` ⇒ 依赖 JAR 里的
实体一个都反查不到（消费方＝SQL→Java 反向转换 `reverse/SqlToJavaConverter.kt`）。

**最终形态（只做快路径）**：
- `find()` 拆出 `findByCandidateNames(tableName, resolver, scope)`；源码 scope 0 命中后，再按
  **`libraryScope() = GlobalSearchScope.notScope(GlobalSearchScope.projectScope(project))`** 找一遍
  （`notScope` 是 243 实测存在的 API——不是 `allScope`，避免与刚跑过的项目 scope 重复解析）。
  **源码命中时行为逐字不变**（库兜底只在 0 命中时执行）。
- 代价上界＝**4 个候选名 × 一次索引查询**，且 `EntityResolver.resolve` 自身有「文件 + 类名 +
  配置签名」三维缓存（`EntityCacheKey`）⇒ 重复解析零成本。

**明确撤掉（性能红线，已写进 KDoc 与 CHANGELOG 防「顺手补全」）**：慢路径（`@TableName` 全类扫描）
的库兜底。我**第一版写了**「表名 token 预筛短名 + 扫全库」的版本（`tableNameTokens` 按 `_` 切分取
len≥3 段、`shortName.contains(token)` 预筛），复核后**整段回退**——弱 token（`sys` / `rel` 这类）会
匹配大量库类名，仍要遍历全库类名并逐类读注解/解析，是 O(库类数) 的 PSI 访问；而这条路径正是
**用户此前反馈过卡顿的「表名无对应实体」场景**。⇒ **凡「为补覆盖而扩到全库/全工程扫描」的改动，
先算 O(规模) 与它落在哪条热路径上，再决定做不做。**

**已知边界（如实登记）**：库里的实体若**类名与表名约定不符**、仅靠显式 `@TableName` 反查 → 仍找不到
（与改前一致，无回归）。

**测试**：`EntityClassFinderIntegrationTest`（`HeavyPlatformTestCase`）+2——
①**库兜底正向**：用**库源码根**造「不在项目内容根里」的类（等价 JAR 无源码形态），并断言
**库命中后慢路径全量扫描次数为 0**（把性能口径**钉成断言**，而不只是写在注释里）；
②**源码优先**：源码与库各一份同名实体 ⇒ 取源码那份（守既有行为）。

**测试夹具三坑（全实测，照抄可用）**：
- **`createLibrary` 必须在写动作里**——只包 model.commit 不够，否则
  `RuntimeExceptionWithAttachments: Write access is allowed inside write-action only`；
  正解＝`ApplicationManager.getApplication().runWriteAction(Computable { …createLibrary(…) })`。
- **新挂库根会触发 indexing ⇒ 此刻是 dumb mode**，索引查询抛 `IndexNotReadyException`；
  正解＝挂完调 **`com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)`**
  （243 实测可用；`PlatformTestUtil` 内部就在用它）。
- **`LibraryTablesRegistrar` 在 `com.intellij.openapi.roots.libraries`**（不是 `.roots`）；
  建库根目录用 `FileUtil.createTempDirectory` + `LocalFileSystem.getInstance().refreshAndFindFileByIoFile`
  （测试基类的 `createTempDir` 返回 `File`，不是 `VirtualFile`）。

**与 W1/W1b 的分工回顾（T-JarEmbedded 三刀现已全落）**：W1＝命名约定信号面扩容（`1c8d149d`）、
W1b＝自定义策略语义识别（`60c7b3f1`）、**W2＝表名→实体反查库兜底**（本条）。三条都只在「源码 0 命中」
或「模块不可知」时才生效，**既有工程零回归**是本轮反复守的底线。

**索引维护（`7e3ca658`，用户点名）**：`docs/索引/开发计划.md` 补齐 **11 份漏登记** + 修 **1 条失效链接**
（`20261015-1830-…PrepareFreeze` 已改名成 `20261015-1842-T-PrewarmWilDeadlock` 且原文件已删，另一会话
改名时漏回填）。**复核口径可照抄**：python 逐名比对 `os.listdir(docs/开发计划)` vs 索引里
`](../开发计划/xxx.md)` 引用集合 ⇒ 结果 **553 / 553 / 漏登记 0 / 失效链接 0 / 重复 0**。
**命令行直接写含中文的文件名必乱码 ⇒ 一律 python heredoc 读文件**（本轮自己踩了一次）。

## 🔴 20261016 第三段：W3 已派子智能体 + 「库内实体非常规命名」的四个优化方案（用户点名要方案）

用户令「**W3 拍子智能体现在做**」+「这个推荐几个优化方案」（指 W2 遗留的那条已知边界）。

**W3 派活要点（已派出，后台）**：任务是**先证伪再动手**——用 demo 仓的**真 JAR** 当夹具
（`jpa-jar-model-lib-1.0.0.jar`，在 demo 仓 `jpa-jar-model-lib/target/` 与 `~/.m2/repository/cn/song/
jpa-jar-model-lib/1.0.0/`，内含 `WmsInventoryEntity`/`WmsInventoryQty`/`WmsSku`/`WmsItemKey`/
**`WmsItemKey$LotInfo`（嵌套 @Embeddable）**），在测试里挂成 `OrderRootType.CLASSES` 库根 → 等索引 →
拿 **class 文件 PSI** 直接断言 `isEmbeddedField` / `findFlex(FQN_EMBEDDED/FQN_EMBEDDABLE/FQN_COLUMN)` /
`JpaEntityResolver.resolve` 的列清单与 `embeddedParent`/`embeddedPath`。**读数若全正常 ⇒ 结论就是
「读 JAR 类本来可行」，只把这组断言作为回归护栏提交，不为改而改**；退化才最小修 + 反向验证。
派活时把本轮踩过的测试环境坑全写进任务书（无 javac / 库源码根不进索引 / heavy 默认无 SDK /
setSdk 须先 ProjectJdkTable.addJdk / IdeaTestUtil 与 com.intellij.JavaTestUtil 都不在插件测试 classpath）。

**「库内实体类名与表名约定不符」（如 `LegacyBooking` ↔ `t_booking`）的优化方案（已交付用户，待其拍板）**：
先讲清成本结构＝快路径候选名一个都不沾，只能靠慢路径**扫全库**，而慢路径恰好落在「表名无对应实体」
这条反馈过卡顿的热路径上 ⇒ **取舍点统一为「能否把 O(库类数) 的扫描移出热路径、且只付一次」**。

- **方案 1（我推荐）：后台一次性全库扫描 + 项目级缓存**。未命中时**不阻塞**，只投递一次后台预热
  （表名冷却窗口 + 全局在途上限两道闸），本次调用按现状回落；后台把**整库**「`@TableName` 值 → 类」
  建成一张表挂项目上（PSI 变更自动失效）。成本从「每张表一次扫描」摊销为「**每个项目版本一次**」，
  且跑在后台线程；缓存建好后查询 O(1)。**关键理由不只是成本**：这套「未命中只投递一次后台预热并原样
  返回——不阻塞、不等待、不轮询」的护栏语义仓内**已有经过审计的先例**＝`ColumnNameCalibrator`
  （数据源列名校正），照搬即可，不必新造。代价：首次转换仍回落成字符串列名形态、要重渲染一次才拿到
  实体（与数据源校正体验一致）；超大工程后台那趟占一点 CPU，靠冷却+在途上限兜住。
- **方案 2：显式触发，默认零成本**。SQL→Java 转换的**实体选择弹窗已存在**，在里面加「在依赖库中查找」
  动作，只在用户点击时才扫库（可带进度/取消）。默认完全不扫、确定性最高，适合大工程或对后台任务敏感。
  与方案 1 互补（1 覆盖常见，2 兜住 1 没扫到或用户不想等）。
- **方案 3：设置项/映射表，零扫描**。设置页加「实体↔表名 附加映射」清单（照既有 `ignoredFields`
  那种分号分隔文本项），一次配置长期有效，能覆盖任何启发式都无解的工程（类名与表名毫无关系）。
  我倾向**先不加**——避免设置项膨胀，等 1/2 都覆盖不了再加。
- **方案 4（微优化，可搭在 1/2 上）**：扫描时先廉价预筛——只对「类上存在任意注解」的类去读
  `@TableName`，或只扫 `*Entity`/`*DO`/`*PO` 这类**可配置后缀**。**压的是常数不是量级**，
  能让方案 1 后台那趟更快。
- **不推荐（本轮已试并撤掉）**：表名 token 预筛短名 + 扫全库——弱 token（`sys`/`rel`）会匹配大量库类名，
  量级不变（见上文 W2 段）。
- **我的建议＝方案 1 + 方案 2**（1 自动化、2 给大工程的开关）；3 留兜底。若用户认为后台扫描不可接受，
  就只做方案 2（纯用户触发、默认零成本、风险最小）。

相关补充：[[pitfall-regression-false-failure-flakes]]（并行会话共用 `build/test-results/` ⇒ 读数归属错，
本轮第二次命中；以及「一条 gradle 命令只报首个编译错误」）。

相关：[[project-hql-buffer-concat-gap]]（同批同句 HQL 的另一个缺陷，已交付）、
[[project-pending-plans]]（在册计划速查）、[[pitfall-fake-green-test-assertions]]
（反向验证＝前缀档短路必红 + 无前缀档用例须仍绿，证「加了一档」而非「换了一档」）、
[[pitfall-regression-false-failure-flakes]]（并行会话共用 `build/test-results/` 时读数须按时间戳/日志归属）。

## 🔴 20261016 第四段：T-JarEmbedded 全落（W2 + W3 证伪 + 方案 1+2），master 本地 `4b7bf710`

用户令「**/goal 按照你的推荐 实施**」（＝方案 1 + 方案 2），并两次纠口径：「**不要用 sleep 阻塞等待
应该后台等通知**」「**需留意 不要出现性能红线问题**」，另告知「**测试环境可以挂 SDK/库类**」（这句直接
解锁了库类测试——`HeavyPlatformTestCase` 默认不挂 SDK）。

**W2（已提交 `e0675b41`）**：`EntityClassFinder.find` 在项目范围 0 命中后补**库兜底**（按 4 个候选类名在
`allScope` 找一遍再按表名精确校验）。明确**不做**慢路径的库兜底（O(库类数) 的 PSI 访问＝性能红线）。
坑：`notScope(projectScope)` **取不到库类**（它连 `isSearchInModuleContent` 一起否定）⇒ 必须用 `allScope`。

**W3（子智能体，已提交 `777e0316`）＝先证伪再动手，结论「无缺口」**：我 §2.2.3 怀疑的三条（class 文件
PSI 无 `nameReferenceElement`、按注解全名判定要求注解类在 classpath、嵌套类读不到）**全被实测推翻**——
class 文件上 `nameReferenceElement.referenceName` 非空、`resolveAnnotationType()` 才为 null，嵌套
`@Embeddable` 同样命中。产出＝回归护栏 `JpaJarClassFileAnnotationTest`（5 例），列名读数与 demo 真库
逐字一致。**教训：JAR 类识别那条线本来就能跑，别照「看起来脆弱」去改。**

**方案 1+2（`4b7bf710`，14 文件，未 push）**：
- **方案 1**：`EntityClassFinder` 加「库内全库扫描」——后台一次性执行、结果按「表名 → 类」落**项目级
  缓存**（`PsiModificationTracker` 失效 ⇒ 每项目版本最多一次）；反查**只读缓存**，未就绪就投递一次并
  **本次原样返回空**。三道闸（设置开关 / 每版本只投递一次 / 全局在途上限）与测试缝（`sweepEnabledForTest`
  / `sweepDispatchForTest` 投递替身 / `seedLibrarySweepForTest`）**照抄 `ColumnNameCalibrator`**。
  新设置项 `libraryEntitySweepEnabled`（默认开）。
- **方案 2**：实体选择弹窗底部加「在依赖库中查找」按钮（BGT 扫库 → 重解析各表候选 → EDT 就地重建行、
  保留已选）；`TableRow` 增 `tableName`（4 个生产构造点带上）；同步内核抽 internal 以便确定性断言。
- **读数**：10 类 **100 例 0 失败**（`--rerun-tasks`）。**反向验证**：把 `findByLibrarySweepCached` 的缓存
  查询那一档摘掉 → **恰 3 红**（两条缓存命中 + 弹窗重解析），恢复后复跑回 100/0/0。
- **自审修正两处（提交前自查发现，非测试暴露，值得当检查项）**：①`restoreMatched` 原用 `projectScope`
  还原，但扫描缓存里装的是**库类** ⇒ 该 scope 恒取不回（「写进去了读不出来」），改 `allScope`；原先那条
  注入用例用的是**工程内类**，恰好把这个缺陷盖住 ⇒ 补 `testLibrarySweepCacheRestoresLibraryClass`
  （注入 `java.lang.Object` 挂 `t_object`）堵盲区。②`runLibrarySweepNow` 原先**扫完之后**才取
  `modificationCount` 当缓存版本号，扫描期间 PSI 变更会被标成「当前版本有效」⇒ 改为**进循环前先取**。
- **未做**：真机走查（弹窗按钮观感、超大工程上后台扫描耗时）。

**🆕 踩坑（重要，属「假绿」新形态）：Gradle `--tests` 过滤式**部分**匹配不上时不报错**。复跑时我把 7 个
类的 FQCN 写错包名（`EntityClassFinderHeavyTest` 在 `…visualizer.heavy`、`SqlToJavaConverterTest` 在
`…visualizer.reverse`、`SettingsRealityGuardTest`/`SettingsTruthTest`/`PluginSettingsPanelBindingTest`
在 `…config`、`BundleParityGuardTest` 在 `…i18n`、`PluginCacheManagerCoverageTest` 在 `…util`），
结果 **`BUILD SUCCESSFUL` + `GRADLE_EXIT=0`，却只跑了 3 个类 27 例**——看上去像「跑过了一遍验证集」。
**判据：数 `build/test-results/test/` 下的 XML 类数，不看退出码。**（本仓 `failOnNoMatchingTests` 应为关；
只有**全部**过滤式都不匹配时才会报 "No tests found for given includes"。）

**并行会话隔离（本轮验证有效）**：CHANGELOG.md 里我的 `### Added`/`### Fixed` 与另一会话的 `### Changed`
（README 首页更新）**挤在同一个 hunk** ⇒ 用 python 把 diff 里那 4 行 `+### Changed` 段摘掉、改 hunk 头
`@@ -4,6 +4,14 @@` → `+4,10`，`git apply --cached` **行级暂存**；提交后工作树里 `### Changed` 与
README（502 行改动）**原样留着**给那个会话。提交信息走 `git commit -F`（反引号陷阱）。

## 🔴 20261016 第五段：性能收口（用户追问「几千个类首次加载会不会卡顿」→ 实测会，已修，`974d125e`）

**这一轮最大的教训：我把一个仓内已被事故封禁的形态又写了一遍，而没有任何守卫拦住我。**
20261008 真机冻结事故（T-GutterDupFreeze）的形态＝「全量短名扫描跑在阻塞式 `ReadAction` 里、读锁全程不放
⇒ EDT 等读许可被冻住（实测 70 秒、空白窗关不掉）」；当时已用 `psi/jpa/EntityAliasSweep.kt`
（`SCAN_LIMIT=5000` + 每 64 个短名 `checkCanceled` + 命中即止）收口——但**仓内 0 处守卫覆盖这个形态**，
所以我新写的库扫描（整条 classpath、无取消检查、无上限）测试全绿却复现了同一个冻结机制。

**修前实测（临时探针，只挂本机 JDK、无工程代码与三方依赖）**
```
PROBE1 shortNames=23342 classesSeen=27113 entries=20226 fullSweepMs=4585 warmSweepMs=4333
PROBE2 controlWriteMs=0 contendedWriteMs=5551          ← 写动作（＝打字→文档提交）被阻塞 5.5s
PROBE3 dispatchedBeforeEdit=0 dispatchedAfterEdit=1    ← 编辑一个文件就重扫
```
**修后**：`entries=0` / `fullSweepMs=4430` / `maxWriteWaitMs=286`（扫描期间连发 60 次写动作取最大）
/ `dispatchedAfterContentEdit=0`。定向 **11 类 107 例 0 失败**。

**四条可复用的平台结论（本轮最有价值的产出）**

1. **`checkCanceled` 单独加，修不了「阻塞读动作冻住写动作」——必须分片读动作。**
   实测：在整趟 `ReadAction.compute` 里加 `checkCanceled` 后，写动作仍被阻塞 **5,480ms**（几乎没变）——
   整趟持锁没有可取消的进度上下文，平台不会替我们让锁。改成**每 500 个短名一个独立
   `ReadAction.compute`、片间真正释放读锁**后 ⇒ 最大等待 **158/286ms**（约 35 倍）。
   推论：**任何「整趟持锁的长扫描」的调用方都不得在外层再包 `ReadAction`**（会抵消分片）。
   扫描**总耗时不变**（4.4s，枚举主导）——那是后台 CPU 代价；用户感知的「卡」是持锁，两者要分开报。

2. **`ProjectRootModificationTracker` 的精确口径**（用来替代 `PsiModificationTracker` 当「粗失效键」）：
   **改文件正文不递增**（打字不作废缓存），**文件增删 / 依赖与模块根变化递增**。
   坑：我第一版用例拿「新增文件」模拟打字 → 假红（新增属结构变化，本来就会递增）。
   判据要写「改**已有文件**的正文」，并把「PSI 版本必须递增」也钉进断言防用例退化成空转。

3. **词索引看不见库 / class 文件里的注解**：`PsiSearchHelper.processElementsWithWord` 在**已挂 JDK** 的
   环境里搜 `Deprecated` / `NotNull` **命中 0**（工程内文件能命中，71ms）⇒ 想用它替掉「枚举全库类」
   会把「库内实体」这条正路弄丢。另：**243 平台的 `UsageSearchContext` 没有 `IN_ANNOTATIONS`**
   （jar 实证只有 `IN_CODE`/`IN_COMMENTS`/`IN_STRINGS`/`IN_PLAIN_TEXT`/`IN_FOREIGN_LANGUAGES`）。

4. **`tableNameAnnotation(clazz) ?: inferDecoratedTableName(className)` 这个兜底会把整条 classpath
   变成「表」**——按类名推断对**任何**类名都能推出非空表名（JDK 27k 类 → 20,226 条目）。
   要做「库内实体反查」只能**只认 `@TableName`**；类名与表名相符的那些由快路径覆盖。

**新守卫 `FullScanCancelGuardTest`（3 条，纯逻辑进 testFast）**：① 每个 `getAllClassNames()` 所在成员
函数体必须含 `checkCanceled` 或委派 `EntityAliasSweep`；② 库扫描函数体不得出现 `inferDecoratedTableName`；
③ 库扫描不得被外层 `ReadAction` 包住。**反向验证**：五刀（只认 @TableName / 取消检查 / 失效键 / 冷却 /
触顶不落缓存）→ 恰 8 红；另两刀（自适应下界 / 分片被外层包）→ 恰 2 红。

相关：[[pitfall-guard-covers-audited-targets]]（「守卫看不见它要防的站点」本轮第三次命中）、
[[pitfall-fake-green-test-assertions]]（拿 `find` 的结果当断言等于没验——快路径本来就会命中）、
[[project-pending-plans]]。

## 🔴 20261016 第六段：用户再报「识别结果还是不正确」→ 诊断为 **demo 跨模块同名实体碰撞**（未改码）

**用户输入（关键）**：点 `cn.song.jarmodeldemo.service.WmsInventoryJarQueryService#findAvailableJarInventory`
的预览，贴出结果给我看。该 HQL（jar-model demo 里）：
`from WmsInventoryEntity inv where inv.warehouseId = :warehouseId and inv.sku.inventoryStatus = :inventoryStatus
and inv.qty.baseQty > 0 and inv.itemKey.lotInfo.receivedDate is not null order by inv.id`，
DAO 是 `wmsJarCommonDao.findByQueryMaxNum(...)`。
**用户贴出的（错误）预览产物**：
`FROM wms_inventory_embed_demo` + SELECT 展开 `inv.id, inv.warehouse_id, inv.move_tool_pallet_no, inv.qty_base_qty`，
WHERE 里 `inv.warehouseId` 与 `inv.qty.baseQty` **被改写**，而 `inv.sku.inventoryStatus` /
`inv.itemKey.lotInfo.receivedDate` **原样透传**（`IS NOT NULL`）。

**根因（判据＝产物内部自洽，不是缓存过期）**：预览把 HQL 实体解析到了**另一个同名类**。
- `wms_inventory_embed_demo` + `move_tool_pallet_no` **只属于嵌入列 demo 那个实体**
  （`jpa-embedded-prefix-demo` 的 `WmsInventoryEntity`＝表 `wms_inventory_embed_demo`，字段
  **`id / warehouseId / moveTool / qty`**，**没有 `sku`、没有 `itemKey`**）。
- 被改写的两条路径恰好是该实体**存在**的字段；没被改写的两条恰好是它**不存在**的 ⇒ 找不到就按既有约定
  **原样透传**（`converter/jpa/HqlConverter.kt:382`「非关联字段，保持原样」）。
- **为什么不是缓存过期**：过期的 jar-model 缓存应把 WHERE 换成 `moveTool.palletNo`；而 WHERE 来自用户真点的
  那段 HQL ⇒ 是**解析选错类**。

**为什么会选错**：HQL 里写的是**简单类名** `WmsInventoryEntity`，解析链**按短名查、取第一个**，无消歧：
- `SqlPreviewServiceEnhancements.kt:508-515`（`resolveHqlTable` 路径 2）用
  `GlobalSearchScope.allScope(project)` → `findClassByShortName`；
- `SqlPreviewService.kt:1738-1746`（`findClassByShortName`）＝`getClassesByName(shortName, scope).firstOrNull()` ← **元凶**；
- 同型还有 `converter/jpa/JpaFromEntityMapper.kt:71-73`（子查询实体）、
  `converter/jpa/JpaAliasTableResolver.kt:95-97`、`converter/jpa/JpaJoinOnResolver.kt:214`。
而 demo 仓**两个模块各有一个 `WmsInventoryEntity`**：`jpa-embedded-prefix-demo`（表 `wms_inventory_embed_demo`）
与 `jpa-jar-model-lib`（表 `wms_inventory_jar_demo`）；打开 demo 仓根目录时两者都在 `allScope`，`firstOrNull()`
挑中了嵌入列那个。**这条碰撞是我的遗留**——嵌入列 demo 由我建、jar 模型 demo 由子智能体建，两边都照用户
真实工程的实体名取名。**上文 §五「无同名双份串味风险（排除项）」说的是 `JpaEntityResolver` 的缓存键**
（`file.url` + `identityHashCode`，那点仍成立），**不覆盖「按短名选类」这一步**——别把两条混为一谈。

**正确产物应为**（jar 实体、component-path）：
`FROM wms_inventory_jar_demo`，四列 `inv.id / inv.warehouse_id / inv.qty_base_qty / inv.sku_inventory_status /
inv.item_key_lot_info_received_date`，WHERE 三条嵌入路径全部带前缀。

**修的方向（未实施，等用户拍板）**：判据应是**可见性**而非「第一个」——
`GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(HQL 所在模块)` 先查、查不到再退 `allScope`。
本用例里 jar 实体**是** `jpa-jar-model-demo` 的依赖（可见）、嵌入列实体**不是**（不可见），规则能一次分对。
上面列的六处同名解析点要**一起收口**，否则子查询/JOIN 各挑各的。
**30 秒自查**：只打开 `jpa-jar-model-demo` 自己的工程（不带 `jpa-embedded-prefix-demo`）再点预览——
若变成 `FROM wms_inventory_jar_demo` 且三条前缀齐，即纯同名碰撞、与嵌入列前缀逻辑无关。

**非代码的处置选项**：可把其中一个 demo 的实体改名，但**建议留着这个碰撞**——它是条真实压力用例。
