# T-JarEmbedded：依赖 JAR 内实体/嵌入类的识别缺口（开发计划）

> 仓库：`mybatis-plus-sql-visualizer`（插件仓，当前分支 `master`）
> 日期：2026-10-16
> 触发来源：用户 2026-10-16 报障 —— HQL 里 `inventory.qty.baseQty` / `inventory.sku.inventoryStatus` /
> `itemKey.lotInfo.receivedDate` 三处嵌入段路径的预览列名都不对（丢前缀），并称
> 「**jar 包里的嵌套类没有正常识别**」。
> 状态：**线一（前缀缺失）已确证＝已修未发布；线二（JAR 场景）现有信息不足以定位，故计划第一步是取证。
> 本轮只出计划、不动代码。**

---

## 一、需求

1. 依赖 JAR（无源码）里提供的实体、`@Embeddable` 嵌入类、嵌套类、`@MappedSuperclass` 父类，
   插件都要能识别到与源码类同等的信息（列清单、嵌入展平、命名档位判定）。
2. 嵌入列命名（`@Embedded` 前缀）在上述场景下也要能判对，而不是只有源码工程才成立。
3. 不引入第二套元数据读取机制，不把 HQL/JPQL 转换主链改复杂。

## 二、现状与实证

### 2.1 截图产物逐条对照（这部分**确证**）

| HQL 写法 | 截图产物 | 组件路径约定的正确产物 | 判定 |
|---|---|---|---|
| `inventory.qty.baseQty` | `inventory.base_qty` | `inventory.qty_base_qty` | 丢 `qty_` 前缀 |
| `inventory.sku.inventoryStatus` | `inventory.inventory_status` | `inventory.sku_inventory_status` | 丢 `sku_` 前缀 |
| `itemKey.lotInfo.receivedDate` | `itemKey.received_date` | `itemKey.lot_info_received_date` | 丢 `lot_info_` 前缀 |
| `inventory.sku.itemKeyId`（JOIN ON 左值） | `inventory.item_key_id` | `inventory.sku_item_key_id` | 同族 |
| `inventory.operationStatus` / `beLockStatus` | `operation_status` / `be_lock_status` | 同 | 普通列，正常 |
| `inventory.frozenStatus` | `inventory.frozenStatus`（**未映射**） | — | 见 §2.4，推断非缺陷 |

三条嵌入列的共同形态是：**嵌入段被正确去掉、前缀没加上**——这正是 `T-EmbeddedColPrefix` 已修的口径
（`psi/jpa/JpaMetadataReader.kt` 的 `embeddedFallbackColumnName`，COMPONENT_PATH 档按
`snake(嵌入段路径) + snake(内层字段)` 拼），而该修复**尚未发布**（本地提交 `a33466c9` 起，
`86e2eb42` / `6919d8ef` / `ee619b45` 续）。

**所以截图本身可以完全用「用户装的版本没有该修复」解释。** 但用户强调「jar 里的类」，下面逐条查
JAR 场景是否**另有**断点——因为即便装上含修复的版本，JAR 场景仍可能因**没有信号源**而判不出前缀。

### 2.2 JAR 场景的代码级边界（逐条带证据）

1. **表名 → 实体反查只扫工程源码**：`psi/EntityClassFinder.kt:41`、`:88`、`:97` 三处一律
   `GlobalSearchScope.projectScope(project)`。依赖 JAR 里的实体**反查不到**。已知消费方：
   `reverse/SqlToJavaConverter.kt:232`（SQL→Java 反向转换）。
2. **前向解析（HQL `FROM 实体名`）走 allScope**：`ui/SqlPreviewServiceEnhancements.kt:510-511` 用
   `JavaPsiFacade.findClass(name, GlobalSearchScope.allScope(project))` → JAR 里的实体**能**解析。
   这与截图「列名确实被映射出来了（说明实体读到了）」一致。**两条路的 scope 口径不一致**，是本轮
   最值得收口的结构性问题。
3. **class 文件 PSI 的注解判定比源码脆**：`psi/jpa/JpaMetadataReader.kt:147` 的 `findFlex` 第三轨
   靠 `annotation.nameReferenceElement?.referenceName` 兜底（`:154`），而 **class 文件 PSI 没有
   nameReferenceElement**（那是源码 PSI 的东西）→ 该轨对 JAR 类恒不命中；前两轨走
   `findAnnotation(fqn)`（按注解全名），要求注解类在 classpath。同类按全名判定的还有
   `isLikelyMappedEntityClass`（`:526` `annotation.qualifiedName ?: continue`）与
   `isSpringDataEmbeddedField`（`:204`、`springDataEmbeddedPrefixOf` 一带的 `:645`）。javax/jakarta
   混用、或注解类不在 classpath 时会整轨漏判。
4. **嵌入列命名的配置探测（A 通道）在实体来自 JAR 时无信号源**：`HibernateNamingConfigProbe`
   的 `moduleResourcesDir` 靠「自实体文件路径上溯找最近的 `pom.xml` / `build.gradle*`」定位模块；
   JAR 条目路径（`…/wms-model.jar!/cn/song/…`）不是本地文件，`LocalFileSystem.findFileByPath` 取不到
   → 回落「工程全扫」。而**命名策略往往根本不在用户工程里**（在共享模型 JAR 自己的模块/构建里），
   于是 A 无论怎么扫都判不出来。
5. **C 通道（按真库列名校正）在本场景不可用**：截图预览面板明确写着「**未绑定数据源**（在控制台执行
   一次查询即可绑定）」→ `ColumnNameCalibrator` 取不到真实列集合，按设计原样返回、零改动。
6. **无「同名双份」串味风险**（排除项）：`JpaEntityResolver` 缓存键含
   `file.virtualFile?.url` + `System.identityHashCode(file)`（`:75`），源码与 JAR 两份同名类不会互相命中。

### 2.3 结论：两条线，别混

- **线一（已确证、已修未发布）**：截图三条错名 ＝ 嵌入段前缀缺失。`a33466c9` 起已支持
  （A 配置探测 / B 设置项 / C 数据源校正 三通道）。用户版本没有它 → 升级即应消失（前提是 A 或 C 能命中）。
- **线二（本次要补）**：**JAR 场景的识别面与信号源**——§2.2 的 ① ② ③ ④ 四条。用户说的
  「jar 里的嵌套类没正常识别」最可能落在其中之一，但**现有信息不足以判定是哪一个**：
  截图能证明实体被解析到了（列名被映射），却证明不了「哪些类来自 JAR」「命名策略写在哪」。
  故本计划第一步是**取证**（§四 W0），不先动手改。

### 2.4 `frozenStatus` 未映射（单列排查，不作本轮目标）

`inventory.frozenStatus` 原样透传，而同一句里的 `operationStatus`/`beLockStatus` 都映射成了下划线列名。
最简解释：该字段显式声明了 `@Column(name = "frozenStatus")`（列名与字段名逐字相同，映射器按
`fieldName == columnName` 原样返回）。也可能是 `@Transient` 或注解写在 getter（`@Access(PROPERTY)`）
上——`ColumnNameCalibrator` 的 KDoc 正把「注解写在 getter / `@Access(PROPERTY)`」列为已知读不到的形态。
**三种解释都不改变本计划的范围**，登记待用户实体源码确认（§六.4）。

## 三、方案与取舍

### 3.1 W0 先取证：造「类在 JAR 里」的载体，逐形态实测

仓内已有先例可照抄：`selfdev-shared-lib`（普通 JAR 模块，非 Boot 应用）+ `selfdev-framework-demo`
（依赖它的 Boot 应用），当初就是为「SQL 执行点在 JAR 里」这类 JAR 场景造的。本轮用同一手法造
**四种形态**，各测「插件能读到什么」：

| 形态 | 造法 | 要测的读数 |
|---|---|---|
| J1 实体与嵌入类都在 JAR | 模型 JAR 提供 `@Entity` + `@Embeddable`；宿主工程只依赖它 | 列清单是否含嵌入展平列、`embeddedParent/embeddedPath` 是否写入、A 探测结果 |
| J2 实体在源码、**嵌入类在 JAR** | 宿主写 `@Entity`，字段类型来自模型 JAR | 同 J1（这是最常见的拆分方式） |
| J3 嵌入类型是 JAR 里的**嵌套类** | `WmsItemKey.LotInfo` 这种静态嵌套类放 JAR | `type.resolve()` 与 `clazz.allFields` 是否正常 |
| J4 `@MappedSuperclass` 父类在 JAR | 父类放 JAR、子实体在源码 | 父类字段是否进列清单（`collectFieldsIncludingSuper` 沿 `superClass` 递归） |

判据（都要拿到**读数**，不接受推断）：插件端 headless 断言（读 demo 源码 + jar 依赖的既有手法见
`converter/jpa/DemoJpaSqlPipelineTest`）+ 必要时 `runIde` 真机走查。

### 3.2 预置修法（按取证结果取舍，不预先全做）

- **W1 给 A 扩信号源**：除工程内 `application*.yml/.properties`，再扫 classpath / JAR 侧的命名策略声明——
  `META-INF/persistence.xml` 的 `hibernate.implicit_naming_strategy`、`hibernate.cfg.xml`、以及 JAR 内
  `application*.yml`。理由：共享模型 JAR 常自带这些声明，而用户工程里**根本没有**（§2.2.4）。
- **W2 反查 scope 对齐**：`EntityClassFinder` 由 `projectScope` 改为「源码优先 + 库兜底」，并定
  「同名双份取源码」的择优规则；顺带审 §2.2.2 两条路 scope 口径不一致的问题。影响面＝按表名找实体的
  全部链路（SQL→Java 反向、实体选择候选等），需评估大工程全库扫描的性能（缓存 + 限流）。
- **W3 class 文件注解判定补齐**：把「按注解全名 / `nameReferenceElement`」的判定改成对 JAR 类也成立的
  形态（`findFlex` 前两轨 + 文本兜底），逐个过 `isLikelyMappedEntityClass` / `isSpringDataEmbeddedField` /
  `springDataEmbeddedPrefixOf`。这是「JAR 类识别退化」的最可能的单点。
- **W4（可选）预览侧可发现提示**：嵌入列名由兜底推出时给一次提示（带「设置 → 嵌入列命名」入口），
  与既有「未绑定数据源」提示同域——避免用户再次把它当缺陷上报。

### 3.3 不做

不改 HQL/JPQL 转换主链；不引入「读字节码注解」的新框架（平台 PSI 已能给出 class 文件的注解，问题在
**判定口径**而非取数能力）；不为 JAR 场景新增设置开关（能用现有「嵌入列命名」档位表达的就不加）。

## 四、改动清单（取证后细化）

- **W0（取证，必做）**：demo 仓新增 JAR 载体（模型 JAR + 宿主工程，照 `selfdev-shared-lib` 手法），
  四形态各一份；插件仓加 headless 断言与真机走查记录；产出「哪一环断」的实测表。
- W1~W3：按 W0 结果落地（预计至少 W3；若命名策略确实只在 JAR 内声明，则 W1 必做）。
- W4：视用户是否会再报同类而定。
- `CHANGELOG.md`、本计划 §七 回填。

## 五、验证方案

- **定向**：`psi/jpa/JpaEntityResolverMappingDeepTest`、`psi/EntityResolverEmbeddedTest`、
  `converter/jpa/*`（嵌入相关）、`ui/gutter/Demo*Anchor*Test`（锚点普查）；W0 新增用例自带读数。
- **反向验证**：凡新增「JAR 形态判定」的分支，摘掉即必须变红（贴两次读数）；既有源码形态用例保持绿
  （证明是「加一档」不是「换一档」）。
- **真机**：JAR 载体的四形态走查（`runIde`），确认预览列名与真库一致；demo 侧真跑建表对照
  （照 `jpa-embedded-prefix-demo` 的 `/columns` 手法）。
- 不跑全量/门禁（留待收口）。

## 六、待验证前提（**先证伪再动手**）

1. 用户的 `WmsInventory` / `WmsInventoryQty` / `WmsSku` / `WmsItemKey.LotInfo` **到底哪些在 JAR、
   哪些在源码**——需用户给出工程结构（模块划分）或 `mvn dependency:tree` 片段。这是线二成立与否的前提。
2. 用户的命名策略**写在哪**：工程 `application*.yml`、自定义 `ImplicitNamingStrategy` 类、还是 JAR 内
   声明（`persistence.xml` / JAR 自带 yml）——决定 A 能否命中、W1 是否必做。
3. 用户装的插件版本（About 对话框里的「内部构建时间戳」）——确认截图是否就是「修复前」，避免重复定位。
4. `frozenStatus` 未映射的真实原因（显式 `@Column` / `@Transient` / getter 注解）——需实体源码；
   **不属本轮目标**。
5. class 文件 PSI 上 `findAnnotation(fqn)` 的实际命中率（javax/jakarta 两档、注解类不在 classpath 档）——
   W0 实测，别凭直觉。
6. `EntityClassFinder` 扩到库 scope 的性能影响（大工程全库类扫描）——实测 + 缓存评估后再定 W2 形态。
7. **「JAR 里的嵌套类」的准确含义**：用户原话可能指「嵌入类」（`@Embeddable`）或「嵌套类」
   （`static class`）——J3 两种都要造，别只按一种理解做。

## 七、实施记录

### 7.1 W1 已实施（2026-10-16，master）

**关键收窄（用户回话后）**：用户确认「这些实体类都在 JAR 里」。回看截图产物即可证**读 JAR 类本身是通的**
——`inventory.operation_status` / `be_lock_status` / `item_key_id` 这些列都映射出来了，说明实体解析、
class 文件注解读取、`@Embedded` 展平三条都成立；缺的只是「这个工程用了 component-path」这个**信号**。
故 §四 W0 里「JAR 类读取能力取证」暂缓（截图本身已是反证），直接做 W1。

**改动**：`HibernateNamingConfigProbe` 的扫描面分两档——

- **模块已知**（实体在工程源码里，能上溯找到模块标记）：行为不变，只看该模块 `src/main/resources`
  （含 `META-INF/`），防兄弟模块配置外溢；
- **模块不可知**（实体来自依赖 JAR：jar 条目路径不是本地文件，`LocalFileSystem.findFileByPath`
  取不到模块根）：退到**工程级信号**，三条互补的路——
  1. `application*.{yml,yaml,properties}` 按扩展名索引扫（能覆盖 Boot 的连字符键 `naming.implicit-strategy`）；
  2. 按**设置键** `implicit_naming_strategy` 走词索引找**任意文件**（不限文件类型，`META-INF/persistence.xml`、
     `orm.xml`、自定义 properties 一并覆盖；命中后读该文件文本再走同一判据）；
  3. 词索引在**Java 源码**里找 `ImplicitNamingStrategyComponentPathImpl` /
     `DefaultComponentSafeNamingStrategy` 类名——`props.put("hibernate.implicit_naming_strategy", X.class)`
     这类**代码式配置**在 `application*.yml` 里根本看不见，是此前最大的盲区。

判据本身未改（`indicatesComponentPath` 仍是纯文本标记匹配 + 剔整行注释），故纯逻辑用例不回归。

**测试**：新增 `HibernateNamingConfigProbeProjectTest` 3 例（源码式配置命中 / 按设置键命中
`persistence.xml` / 无信号回落无前缀）；既有 `HibernateNamingConfigProbeTest`（纯逻辑 + 模块上溯）不回归。

**读数**：10 个类 **125 例 0 失败 0 错误**（含 `SettingsRealityGuardTest`、demo 锚点门禁
`DemoEmbeddedPrefixReproAnchorTest`、`CustomDaoHqlLocatorTest` / `HqlQueryLocatorTest`、
`ColumnNameCalibratorTest` 等回归）。

**反向验证**：把「模块不可知 → 工程级信号」整档短路为 `false` → `HibernateNamingConfigProbeProjectTest`
**2 例红**（源码式配置 / `persistence.xml`），「无信号回落无前缀」保持绿，`GRADLE_EXIT=1`；
还原后复跑 125/0/0。

**踩坑（记录）**：本仓平台版本（243）里 `PsiElementProcessor` 在 `com.intellij.psi.search` 而非
`com.intellij.psi`；`PsiSearchHelper.processElementsWithWord` 只接受 `TextOccurenceProcessor`
（`execute(element, offsetInElement)`）；且 `FilenameIndex.getFilesByName` 返回 `Array<PsiFile>` 而非
`Collection<VirtualFile>`（与 `getAllFilesByExt` 不同）。另：按文件名查表在 light 测试里看不到 fixture
文件（`getFilesByName` 那条用例首轮红），改走「设置键词索引」后既更通用也可测。

**未做**：W0 的 JAR 载体取证（改由子智能体在 demo 仓补，见 §7.2）；W2（`EntityClassFinder` 库兜底）与
W3（class 文件注解判定加固）——W2 仍是真缺口（表名→实体反查在 JAR 场景下找不到），但与本轮报障的
预览列名无关，另立批次。

### 7.1b W1b 已实施（同日）：**自定义** `ImplicitNamingStrategy` 的语义识别

用户追问「自定义的 `ImplicitNamingStrategy` 实现类能不能自动识别」。答案是**能，但必须按语义特征识别，
不能见到自定义策略就算**——多数自定义策略只改**表名**（加前缀/去后缀），对嵌入列毫无影响；那种工程若被
当成组件路径档，反而会把列名改错。

判定依据（从 hibernate-core 5.6.4 源码取的 API 事实）：`AttributePath` 上只有
`getParent()` / `getFullPath()` / `getDepth()` 能拿到**父段/整条路径**，内置组件路径策略正是
`getProperty()` + `getParent()` 递归拼接；只取末端属性名（`getProperty()`）的策略不会碰这三个方法。
故新判据＝**文件既参与隐式命名（`implements`/`extends` 命名策略类型）又用到 `AttributePath` 的
父段/全路径**。

**测试**：`HibernateNamingConfigProbeProjectTest` +2——「自定义组件路径策略被语义识别」（递归取
`getParent()` 拼 `_` → 判是）与「只改表名的自定义策略不误判」（只重写 `determinePrimaryTableName`、
不碰 `AttributePath` → 判否）。**5 例全绿**。

**已知边界（如实登记，勿当已解决）**：自定义策略类若在**依赖 JAR** 里（工程无源码）→ 探测不到，仍需
设置项/数据源校正；自定义策略若用**别的连接符或形态**（不是 `_` 连接 + snake 化）→ 插件按内置
component-path 口径渲染仍可能与真库不同（本轮不猜连接符）；判据是文本特征，不解析控制流。

### 7.1c 索引维护（同日）

顺手补齐 `docs/索引/开发计划.md`：原 **11 份漏登记**（含本计划、T-EmbeddedColPrefix、T-HqlBufConcat、
T-SqlTemplateCaret/Ux、T-DialectQuoteWarn、T-PerfSweep25/1719、竞品对比第二十六轮/第五~十三轮、
T-PrewarmWilDeadlock）＋ **1 条失效链接**（`20261015-1830-T-SqlPreviewPrepareFreeze` 已被改名成
`20261015-1842-T-PrewarmWilDeadlock` 且原文件已删，索引未跟上）。补齐后复核：**目录 553 份计划 /
索引 553 条引用 / 漏登记 0 / 失效链接 0 / 重复 0**。

### 7.2 demo 仓 JAR 复现载体（已完成，子智能体交付 `74cc257`）

模型 JAR 模块 `jpa-jar-model-lib`（`@Entity` + 三个 `@Embedded` + `WmsItemKey` 内的静态嵌套类
`LotInfo`，嵌入字段零注解）+ 宿主 Boot 模块 `jpa-jar-model-demo`（端口 8088 / 库 `jpa_demo` /
表 `wms_inventory_jar_demo`），命名约定备了「`application.yml` 里写」与「Java 代码里
`props.put(...)`（`@Profile("code-naming")`）」两份，分别对应 W1 的两条信号路。

**真跑读数**（子智能体实测）：
```
create table wms_inventory_jar_demo (
    id, item_key_lot_info_received_date, qty_base_qty, sku_inventory_status, warehouse_id, ...)
/jar-model/columns → ["id","item_key_lot_info_received_date","qty_base_qty","sku_inventory_status","warehouse_id"]
/jar-model/query（plain / buffer）→ 各 rowCount=1；两种命名配置下 hibernate 属性均生效
```

**勘误（子智能体纠正本计划原文）**：三层嵌入路径的真库列名是 **`item_key_lot_info_received_date`**，
不是本文早先写的 `lot_info_received_date`——组件路径把**整条链** `itemKey.lotInfo.receivedDate` 都并进列名
（与 §8.1 从 hibernate-core 源码得出的「多级取整条链」一致，是本文原稿手误）。

### 7.3 W2 已实施（同日）：表名→实体反查的库兜底

**缺口**：`EntityClassFinder` 的两条查找路（候选名快路径 + `@TableName` 全类扫描慢路径）此前一律用
`GlobalSearchScope.projectScope`，于是**依赖 JAR 里的实体一个都反查不到**（用户场景＝实体类都在 JAR 里）。
影响面＝所有「按表名找实体」的链路（已知消费方 `reverse/SqlToJavaConverter.kt`，SQL→Java 反向转换）。

**改法（性能优先，明确不做越线的事）**：

- 快路径补**库兜底**：项目 scope 0 命中后，再按**候选类名**在库 scope 找一遍，命中后同样按表名精确
  校验；源码命中时行为逐字不变（库兜底只在 0 命中时执行）。代价上界＝4 个候选名 × 一次索引查询，
  且 `EntityResolver.resolve` 自身有「文件 + 类名 + 配置签名」三维缓存，重复解析零成本。
- **不做**「全库逐类扫描」的慢路径库兜底：那要遍历全库类名并逐类读注解/解析（O(库类数) 的 PSI 访问），
  会把「表名无对应实体」这条此前反馈过卡顿的路径成本推高——**性能红线**。本轮先写过「表名 token 预筛
  短名 + 扫全库」的版本，复核后撤掉（弱 token 如 `sys`/`rel` 会匹配大量库类名，成本仍不可控）。
- scope 选型用 `allScope` 而非 `notScope(projectScope)`：后者会把 `isSearchInModuleContent` 一并取反，
  挂在模块上的库根可能被排除（实测库类查不到）；`allScope` 语义明确，重复查项目类的代价可忽略。

**测试**（`EntityClassFinderIntegrationTest`，`HeavyPlatformTestCase`）：

- **库兜底正向**：给测试模块挂上**本机 JDK 当 SDK**，再在环境里探测一个真实库类、按插件自己的口径
  反推表名，断言 `find(表名)` 能把它找回来；并断言**库命中不触发慢路径全量扫描**（性能口径钉成断言）。
- **快路径命中不触发慢扫描**（同口径，任何环境都成立）。
- 既有 9 条（`@TableName` 命中、去首段推断、缓存命中零扫描、缓存清除重扫、多候选顺序、VO/DTO 排除等）不回归。

**读数**：6 个类 **63 例 0 失败 0 错误**（`EntityClassFinderHeavyTest` 17、`SqlToJavaConverterTest` 22、
`PluginCacheManagerCoverageTest` 5、`SettingsRealityGuardTest` 4、`HibernateNamingConfigProbeProjectTest` 5、
`EntityClassFinderIntegrationTest` 10）。

**反向验证**：摘掉库兜底那一档（`find` 里删掉 `findByCandidateNames(..., libraryScope())` 调用）→
`testLibraryEntityFoundByCandidateNameFallback` **恰 1 例红**（`表名 object → []`），其余 9 条保持绿，
`GRADLE_EXIT=1`；还原后复跑 63/0/0。

**踩坑（记录备查）**：`IdeaTestUtil` 与 `com.intellij.JavaTestUtil` 都**不在插件测试 classpath** 上；
`HeavyPlatformTestCase` 默认**不挂任何 SDK**；`ModuleRootManager.setSdk` 会校验注册表，须先
`ProjectJdkTable.addJdk`（并在 tearDown 里 `removeJdk` 防污染）；`LibraryTablesRegistrar.createLibrary`
必须在写动作内；测试 JVM **无 javac**（临时 jar 造不出来）；「库源码根」形态的类不进索引。

### 7.4 优化方案 1+2 已实施（同日）：库内「非常规命名」实体的反查

**背景**＝§7.3 的已知边界：库里的实体若类名与表名约定不符（`LegacyBooking` ↔ `t_booking`），只能靠
`@TableName` 认出；而「扫全库类名 + 逐类读注解」是 O(库类数) 的 PSI 访问，直接放在反查热路径上会
拖慢「表名根本没有对应实体」这条最常见也最敏感的路径。

**方案 1（默认开）**：把全库扫描挪到**后台一次性执行**，结果按「表名 → 类」落**项目级缓存**
（`PsiModificationTracker` 失效 ⇒ 每项目版本最多扫一次）；反查时**只读缓存**，未就绪就投递一次并
**本次原样返回空**——调用方按既有回落（字符串列名形态）走，下一次反查即命中。三道闸与
`ColumnNameCalibrator` 的后台预热同款：设置开关 / 每项目版本只投递一次 / 全局在途上限；
测试缝也同款（`sweepEnabledForTest` / `sweepDispatchForTest` 投递替身 / `seedLibrarySweepForTest`）。
设置页「清除缓存」（`clearReverseLookupCache`）同步清库扫描缓存。

- 新增设置项 `libraryEntitySweepEnabled`（默认开，「代码生成」组，中英 bundle 齐）——关掉即不投递。
- 扫描判定与项目慢路径同一套（`@TableName` 注解值 > 按类名推断），区别只在 scope（库）与结果形态
  （一次扫全部表，而非只找一张表）⇒ 成本从「每张表一次」摊成「每项目版本一次」。

**方案 2（显式触发，默认零成本）**：实体选择弹窗底部新增「在依赖库中查找」按钮——用户点了才跑
一次同一趟扫描（BGT 扫库 → 按新结果重解析各表候选 → EDT 就地重建行、保留用户已选）。
`EntitySelectionDialog.TableRow` 增 `tableName`（4 个生产构造点带上），供重解析；
同步内核 `refreshCandidatesFromLibraries()` / `resolveCandidatesFromFinder()` 抽为 internal 以便确定性断言。

**测试**：
- `EntityClassFinderIntegrationTest` +5：真扫（挂本机 JDK 当 SDK，断言 `runLibrarySweepNow()` 在**真实
  库类**上扫出条目、且缓存条目数与返回值一致）/ 缓存→命中接线（注入 `t_booking → LegacyBooking` 后
  `find("t_booking")` 命中，而该名字既不在候选类名里、慢路径也认不出）/ 反向对照（无缓存 + 不投递 →
  同一表名找不到）/ 开关关闭不投递 / 每项目版本只投递一次（投递缝计数，不产生真实后台线程）。
- `EntitySelectionDialogTest` +3：按钮内核确实跑了库扫描 / 扫描结果被用于重解析各表候选 / 无结果不虚增候选。
- **未新增二进制 jar 夹具**（`src/test/resources` 里无先例）：注解读取那一环由 W3 的取证覆盖，这里用
  「真扫 + 注入缓存接线」两条互补用例代替，不依赖「库里恰好有个 @TableName 与类名不符的类」这种环境巧合。

**读数**（`--rerun-tasks` 强制重编译，10 个类 100 例 0 失败；与本轮改动相邻的既有回归类一并在场）

```
ok   PluginSettingsPanelBindingTest             tests= 15 fail=0
ok   SettingsRealityGuardTest                   tests=  4 fail=0
ok   SettingsTruthTest                          tests=  5 fail=0
ok   BundleParityGuardTest                      tests=  5 fail=0
ok   EntityClassFinderIntegrationTest           tests= 16 fail=0
ok   EntityClassFinderHeavyTest                 tests= 17 fail=0
ok   EntitySelectionDialogTest                  tests=  6 fail=0
ok   JpaJarClassFileAnnotationTest              tests=  5 fail=0
ok   SqlToJavaConverterTest                     tests= 22 fail=0
ok   PluginCacheManagerCoverageTest             tests=  5 fail=0
TOTAL 100 FAILURES 0
```

其中 `JpaJarClassFileAnnotationTest`（W3 的取证）在本轮改动**全部在场**时同批通过，交叉印证两条线不互斥。

**反向验证**（把 `findByLibrarySweepCached` 的缓存查询那一档摘掉——只留 `requestLibrarySweep()` 后直接
`return emptyList()`，其余一字不动）：`GRADLE_EXIT=1`，**恰 3 红**，且红在预期的那三处——

```
EntityClassFinderIntegrationTest  tests=16 fail=2
  testLibrarySweepCacheHitForUnconventionalTableName :: 库扫描缓存命中应返回该实体 expected:<1> but was:<0>
  testLibrarySweepCacheRestoresLibraryClass         :: 库扫描缓存命中应取回库类 expected:<1> but was:<0>
EntitySelectionDialogTest         tests= 6 fail=1
  testScanResultRefreshesRowCandidates               :: 重解析后该表应拿到新候选 expected:<1> but was:<0>
```

第三处红证明方案 2 的「重解析」确实经过 `find` 的库缓存这一档，不是自己另走一条路；随后恢复修复
并 `--rerun-tasks` 复跑回到上表全绿。

**过程踩坑（记一笔，防下次假绿）**：复跑时我把其中 7 个类的 FQCN 写错了包名（`EntityClassFinderHeavyTest`
实际在 `…visualizer.heavy`、`SqlToJavaConverterTest` 在 `…visualizer.reverse`、守卫三家族在
`…config`/`…i18n`/`…util`，我却按 `psi`/`ui.gutter` 写了）。Gradle 在**只有部分** `--tests` 过滤式
匹配不上时**不报错**——于是那一轮 `BUILD SUCCESSFUL`、`GRADLE_EXIT=0`，却只跑了 3 个类 27 例，看上去
像「跑过了一遍验证集」。**判据：看 `build/test-results/test/` 下 XML 的类数**，不看退出码。上表 100 例
是用正确 FQCN 复跑得到的读数。

**自审修正两处**（提交前自查发现，均非测试暴露）：
- `restoreMatched` 原先用 `projectScope` 还原——但扫描缓存里装的是**库类**，该 scope 恒取不回，等于
  「缓存写进去了、读不出来」。已改 `allScope`，并补 `testLibrarySweepCacheRestoresLibraryClass`
  （注入 `java.lang.Object` 挂 `t_object`）堵住这个盲区——原先那条注入用例用的是工程内类，把这个
  缺陷盖住了。
- `runLibrarySweepNow` 原先在扫完之后才读 `modificationCount` 作为缓存版本号，扫描期间若 PSI 有改动，
  会把「扫的是旧状态」标成「当前版本有效」。已改为进循环前先取版本号。

**未做**：真机走查（弹窗按钮观感）。

### 7.4b 性能实测（20261016 补）：**用户问「几千个类首次加载会不会卡顿」→ 实测会，且比预想严重**

临时探针（`TmpSweepCostProbeTest`，跑完即删，不入库）在**只挂本机 JDK、没有任何工程代码与三方依赖**
的 `HeavyPlatformTestCase` 里实测三条：

```
PROBE1 shortNames=23342 classesSeen=27113 entries=20226 enumerateMs=7160 fullSweepMs=4585 warmSweepMs=4333
PROBE2 controlWriteMs=0 contendedWriteMs=5551
PROBE3 dispatchedBeforeEdit=0 dispatchedAfterEdit=1 modBefore=4 modAfter=16
```

**① 成本量级比「库类数」大得多——实际是「整条 classpath 的全部类」**：只有 JDK 时 `allScope` 就可见
**27,113** 个类，扫描落 **20,226** 条表名条目（≈75% 的类都被当成「表」）。原因是判定 `tableNameAnnotation(clazz)
?: inferDecoratedTableName(className)` 对**任何**类名都能推出非空表名 ⇒ 除了「非接口/枚举/注解」与
「VO/DTO 后缀」两道筛子外**没有任何实体可信度过滤**，等于把整个 classpath 建了张反查表。单趟
**4.5 s**（≈0.17 ms/类；第二次 4.33 s，说明不是一次性的 stub 冷加载，是实打实的遍历）。真实工程叠加
Spring/Hibernate/MyBatis 等依赖后类数通常 6 万~15 万 ⇒ 单趟约 **10~25 s**。

**② 它确实会卡打字——不是「后台线程所以无感」**：`requestLibrarySweep` 用的是**阻塞式**
`ReadAction.compute`，整趟扫描期间持有应用级**读锁**；写动作（打字→文档提交、VFS 刷新、索引写阶段）
必须等读锁释放。实测对照：无扫描时一次写动作 **0 ms**，扫描进行中同一写动作等了 **5,551 ms**。
⇒ 这就是用户问的那种卡顿，而且发生在**第一次** `find` 未命中时（＝打开工程后第一次 SQL→Java 转换），
也就是「首次加载」那一刻。

**③ 缓存会被普通编辑作废并重扫**：失效键是 `PsiModificationTracker.modificationCount`，它对**普通编辑**
也递增（实测加一个文件：4 → 16）。实测「扫完 → 未命中不再投递 → 编辑一次 → 未命中重新投递」
（`dispatchedBeforeEdit=0` → `dispatchedAfterEdit=1`）。⇒ 「每项目版本一次」实际是「每次 PSI 变化一次」，
在持续编辑中会反复扫。

**④ 另缺两条**：循环内**没有** `ProgressManager.checkCanceled()`（不可取消、关工程也停不下来）；
而我声称照抄的 `ColumnNameCalibrator` 其实还有一道**时间冷却窗口**（`PREFETCH_COOLDOWN_MS = 5_000`），
我**没有**把它搬过来（我只搬了「每版本一次 + 在途上限 + 测试缝 + 投递失败归还计数」）。

**修法（待用户拍板，未实施）**：
1. **扫描面加实体可信度过滤**（治本，同时治成本与误命中）：只保留「带注解」或「类名以可配置实体后缀
   结尾（`Entity`/`DO`/`PO`）」的类；再叠一条**包前缀黑名单**（`java.`/`javax.`/`sun.`/`com.sun.`/
   `kotlin.`/`org.springframework.`/`org.apache.`/`com.intellij.` 等）——后者不需要任何 PSI 注解访问，
   先把 JDK 与框架类整体剪掉，是最便宜的一刀。
2. **改非阻塞可取消读动作**：`ReadAction.nonBlocking { … }.inSmartMode(project)` + 循环内每 N 个类
   `ProgressManager.checkCanceled()` ⇒ 让写动作（打字）能抢占、且能被打断，而不是干等。
3. **换失效键**：库扫描的结果只取决于 **classpath**（依赖库类），不取决于用户改源码 ⇒ 改挂
   `ProjectRootModificationTracker`（模块/依赖变化才失效），或叠一道时间冷却（照先例的 5 s）+
   最短重扫间隔，别让「改一个字符」触发重扫。
4. **保留现有开关与显式触发**作为兜底；考虑把默认值改成「关」（大工程默认不扫，让用户按需点按钮）。

### 7.4c 性能收口已实施（同日）：上节四条修法落地，并推翻上节第 1、2 条的原始设想

**用户令「实施」后动手。上节四条修法里有两条在实测中被改写**：

- 上节第 1 条的「加包前缀黑名单」**没有做**——因为它治不了本：真正的问题是**按类名推断表名的兜底**，
  见下「根因一」。
- 上节第 2 条设想的 `ReadAction.nonBlocking` **没有用**，也没用「`checkCanceled` 就够」——实测**不够**
  （见下「根因二」）。真正有效的是**分片读动作**。

**先证伪了一条看似更优的路（词索引）**：本想用 `PsiSearchHelper.processElementsWithWord` 一次索引查询
替掉 23k 次枚举。实测两条都否掉——① 243 平台的 `UsageSearchContext` **没有 `IN_ANNOTATIONS`**
（jar 实证只有 `IN_CODE`/`IN_COMMENTS`/`IN_STRINGS`/`IN_PLAIN_TEXT`/`IN_FOREIGN_LANGUAGES`）；
② 用 `IN_CODE` 搜 `Deprecated`/`NotNull` 在**已挂 JDK** 的环境里**命中 0**（工程内文件能命中，71ms）
⇒ **词索引看不见库/class 文件里的注解**，用它等于把「库内实体」这条正路弄丢。故库侧只能枚举。

**三条根因与对应修法（每条都配了前后实测）**

**根因一：扫描面是「整条 classpath 的所有类」，不是「库内实体」。** 判定写成
`tableNameAnnotation(clazz) ?: inferDecoratedTableName(className)`，而按类名推断**对任何类名都能推出
非空表名** ⇒ 只挂 JDK 就把 27k 类里的 **20,226** 个建成「表」。
**修法＝只认 `@TableName`**（类名与表名**相符**的库内实体已由 §7.3 的快路径库兜底覆盖，本扫描存在的
意义只有「类名与表名不符、只能靠 `@TableName` 认出」那一类）⇒ 条目 **20,226 → 0**（JDK 类没有
`@TableName`，本就不该进）。

**根因二：冻结机制＝`ReadAction.compute` 整趟持锁**——这正是 20261008 真机冻结事故
（T-GutterDupFreeze）的形态，当时已用 `EntityAliasSweep`（上限 + 取消检查 + 命中即止）收口，
**但仓内没有任何守卫钉住这个形态**，所以我把它又写了一遍（本轮最大的教训）。
**只加 `checkCanceled` 不够**：加完实测写动作仍被阻塞 **5,480 ms**——`ReadAction.compute` 没有可取消的
进度上下文，平台不会替我们让锁。**真正有效的是分片读动作**：每 500 个短名一个**独立**的
`ReadAction.compute`，片间**真正释放读锁** ⇒ 扫描期间连发 60 次写动作取最大值，
**5,480 ms → 158 / 286 ms**（约 35 倍）。调用方因此**不得**把 `runLibrarySweepNow` 包进外层
`ReadAction`（守卫钉住）。
**注意扫描总耗时没变**（4.4 s，枚举主导）：那是后台 CPU 代价，不是卡顿；用户感知的「卡」是持锁。

**根因三：失效键太细 ⇒ 编辑一次就重扫。** `PsiModificationTracker` 对任何 PSI 变化都递增
（实测改一个文件 4 → 16）。**修法＝`ProjectRootModificationTracker`**，实测口径：
**改文件正文不递增**（打字不作废缓存）、**文件增删 / 依赖与模块根变化递增**。
（第一版用例拿「新增文件」模拟打字，因此假红——已改成编辑正文，并把「PSI 版本必须递增」也钉进断言，
防用例退化成空转。）另加**自适应冷却窗口**：窗口取 `max(配置 5 s, 上趟实际耗时)`——扫描是秒级，
固定 5 s 冷却短于一趟，重构中反复增删文件会一趟接一趟连跑；**直接执行的扫描（弹窗按钮）同样进冷却窗口**。

**新守卫 `FullScanCancelGuardTest`（3 条，纯逻辑，进 testFast）**：
① 每个 `getAllClassNames()` 所在成员函数体必须含 `checkCanceled` 或委派 `EntityAliasSweep`；
② 库扫描函数体不得出现 `inferDecoratedTableName`；③ 库扫描不得被外层 `ReadAction` 包住。
**它存在的理由就是补上「守卫看不见它要防的站点」**——修前仓内 0 处守卫覆盖这个形态。

**读数（`--rerun-tasks`，11 类 107 例 0 失败）**；探针前后对照（临时探针，跑完即删）：

```
修前 PROBE1 shortNames=23342 classesSeen=27113 entries=20226 fullSweepMs=4585
     PROBE2 controlWriteMs=0 contendedWriteMs=5551
     PROBE3 dispatchedBeforeEdit=0 dispatchedAfterEdit=1
修后 AFTER-P1 entries=0 fullSweepMs=4430
     AFTER-P2 controlWriteMs=0 maxWriteWaitMs=286 samples=60 sweepFinished=true
     AFTER-P3 dispatchedBeforeEdit=0 dispatchedAfterContentEdit=0
```

**反向验证**（两批）：第一批五刀（只认 `@TableName` / 取消检查 / 失效键 / 冷却 / 触顶不落缓存）
→ **恰 8 红**（5 条对应五刀 + 2 条「失效键退回 PSI 版本」的必然连带 + 1 条守卫）；第二批两刀
（自适应下界 / 分片被外层包住）→ **恰 2 红**。

**未做**：真机走查；超大工程（10 万类级）的真实单趟耗时——本机只量到 JDK 的 27k 类 / 4.4 s，
真工程按线性外推约 10~25 s，**仍属后台 CPU（不再阻塞打字）**，且开关可关、弹窗可显式触发。

### 7.5 W3 已取证（同日）：class 文件（依赖 JAR）上的注解判定——**证伪：无缺口，落回归护栏**

**取证方式**：新增 `JpaJarClassFileAnnotationTest`（`HeavyPlatformTestCase`）。把 §7.2 的模型 JAR
（`jpa-jar-model-lib-1.0.0.jar`：`@Entity` + 三个 `@Embedded` + `WmsItemKey` 内静态嵌套 `@Embeddable`，
注解**全部是 `javax.persistence.*`**）拷到 `java.io.tmpdir` 后按 `jar://…!/` 挂成测试模块的库根
（`ModuleRootModificationUtil.addModuleLibrary`），`IndexingTestUtil.waitUntilIndexesAreReady` 后对判定
函数逐项取数。**先证伪再动手：本轮未改任何生产代码。**

**读数（原文，落盘 `build/w3-jar-readings-<用例名>.txt`；树内含主控未提交改动，本用例不依赖那几个类）**

夹具可见性：`allScope.findClass` / `everythingScope` / `shortNameCache` 三处都命中
`cn.song.jarlib.entity.WmsInventoryEntity`，`findPackage("cn.song.jarlib.entity") = true`，
`entity.containingFile.path = …jar!/cn/song/jarlib/entity/WmsInventoryEntity.class`（确认是 class 文件 PSI）。

class 文件上注解的三要素（**本节核心反证**）：

```
entity.annotation = qName=javax.persistence.Entity refName=Entity resolved=null text=@javax.persistence.Entity
entity.annotation = qName=javax.persistence.Table refName=Table resolved=null text=@javax.persistence.Table(name = "wms_inventory_jar_demo")
```

即 `nameReferenceElement.referenceName` **非空**（`Entity`/`Table`）、`qualifiedName` 是全限定名，而
`resolveAnnotationType()` 为 **null**（`javax.persistence` 确实不在测试 classpath）——「class 文件 PSI 没有
`nameReferenceElement`」「按注解全名判定要求注解类在 classpath」两条前提同时被实测推翻。

判定函数逐项（全部正常）：

- `findFlex(entity, FQN_TABLE)` → `javax.persistence.Table`，`name` = `wms_inventory_jar_demo`（jakarta→javax 轨命中）
- `findFlex(qty, FQN_EMBEDDABLE)` / `findFlex(lotInfo, FQN_EMBEDDABLE)` → `javax.persistence.Embeddable`（**嵌套类同样命中**）
- `findFlex(qtyField, FQN_EMBEDDED)` → `javax.persistence.Embedded`；`isEmbeddedField(qtyField) = true`，`isEmbeddedField(warehouseId) = false`
- `isLikelyMappedEntityClass`：entity = true；`qty`/`itemKey`/`lotInfo`（`@Embeddable`）= false
- `isSpringDataEmbeddedField(qtyField) = false`、`springDataEmbeddedPrefixOf(qtyField) = ""`（SD 轨不误伤 jakarta/javax 的 `@Embedded`）
- `findFlex(warehouseField, FQN_COLUMN)?.name = warehouse_id`、`columnValueOrSnake(warehouseId) = warehouse_id`
- `generatedValueStrategy(idField) = IDENTITY`（枚举属性同样可从 class 文件读出）
- `attributeOverrides(entity) = {}`、`hasTransientLikeAnnotation(qtyField) = false`、`readClassMeta(entity)` 各字段空/空表（本夹具确实没有这些注解）
- 展平：`qty` → `base_qty` / `qtyBaseQty`；`sku` → `inventory_status` / `skuInventoryStatus`；`itemKey` **两层递归** → `received_date` / `itemKeyLotInfoReceivedDate`；COMPONENT_PATH → `item_key_lot_info_received_date`
- `JpaEntityResolver.resolve`：表名 `wms_inventory_jar_demo`；列清单（NONE）`[id, warehouse_id, base_qty, inventory_status, received_date]`，逐列 `embedded=true` + `embeddedParent`（qty/sku/itemKey）+ `embeddedPath` 均正确；COMPONENT_PATH 档 `[id, warehouse_id, qty_base_qty, sku_inventory_status, item_key_lot_info_received_date]` —— **与 §7.2 demo 侧真库 `/jar-model/columns` 读数逐字一致**
- `EntityClassFinder.find("wms_inventory_jar_demo") = []`（**只记录不硬断言**：该 finder 是 MP `@TableName` 侧反查，JPA `@Table` 不在其轨；JPA 前向解析走 allScope，见 §2.2.2）

**结论：§2.2.3 的五条怀疑全部证伪，无缺口，不改生产代码。**

| §2.2.3 怀疑 | 实测 |
|---|---|
| ① `findFlex` 第三轨（`nameReferenceElement`）对 class 文件恒不命中 | 证伪：`refName=Entity`/`Table`；平台源码/javap 双证 `ClsAnnotationImpl.getNameReferenceElement()` 非空（243 与 262 同） |
| ② `isLikelyMappedEntityClass` 的 `annotation.qualifiedName` 取不到 | 证伪：`qName=javax.persistence.Entity` → 判 true（且它根本不走 `findFlex`，走 `qualifiedName` 直查） |
| ③ `isSpringDataEmbeddedField` / `springDataEmbeddedPrefixOf` 短名/文本轨 | 正常：false / `""`，无误判 |
| ④ `isEmbeddedField` / `hasTransientLikeAnnotation` / `attributeOverrides` / `EntityClassFinder.tableNameAnnotation` | 正常（前三个有读数；`tableNameAnnotation` 依赖同一个 `qualifiedName` + `nameReferenceElement` 机制，见 ①） |
| ⑤ javax/jakarta 混用、注解类不在 classpath → 整轨漏判 | 证伪：`resolved=null`（注解类确实不在 classpath）下，jakarta→javax FQN 轨照常命中，展平与列清单全对 |

**平台侧依据（先证伪再动手）**：`ClsAnnotationImpl`（243 sources 与 262 javap 双证）——

```java
public @NotNull String getQualifiedName() { return myReferenceElement.getValue().getCanonicalText(); }
public PsiJavaCodeReferenceElement getNameReferenceElement() { return myReferenceElement.getValue(); }
```

`myReferenceElement` 由**注解文本**（class 文件常量池，形如 `@javax.persistence.Table(name = "…")`）惰性构造，
`getReferenceName()` 取末段短名——故「class 文件没有 `nameReferenceElement`」不成立。

**反向验证（证明护栏不空绿）**：把 `JpaMetadataReader.findFlex` 临时降为**只留 jakarta FQN 第一轨**
（正是 §2.2.3 假设的退化态，即前两轨按全名、第三轨失效）→ `JpaJarClassFileAnnotationTest` **5 例 3 红**
（`testJarClassFileAnnotationsAreReadable` / `testJarEmbeddedFlattenReadings` / `testJarEntityResolveReadings`；
夹具诊断与 MP 反查两例按设计保持绿，`GRADLE_EXIT=1`），红档读数：

```
findFlex(entity, jakarta.Table)?.qualifiedName = null
isEmbeddedField(qtyField) = false
flatten(itemKey, NONE) = [lot_info|lotInfo|path=itemKeyLotInfo]
resolve(NONE).columns = [id:id:…, warehouseId:warehouse_id:…, qty:qty:…, sku:sku:…, itemKey:item_key:…]
```

还原后复跑 **5 例 0 失败 0 错误**（`BUILD SUCCESSFUL`）——退化态的产物（嵌入段整体不展平、`itemKey` 塌成
单列）与用户报障「jar 里的嵌套类没正常识别」同形，说明这组断言确实钉住了该能力。

**环境坑（记录备查）**：`HeavyPlatformTestCase` 的 `VfsRootAccess` 只放行「测试临时目录 + 默认条目」，
仓外 jar 直接 `refreshAndFindFileByIoFile` 抛 `VfsRootAccessNotAllowedError`（先拷 `java.io.tmpdir` 即可，
且 `java.io` 拷贝不经 VFS）；`ModuleRootModificationUtil.addModuleLibrary` 断言库根 URL **必须是
`jar://…!/`**（传本地文件 URL 报 `jar file is expected, local file is used`）；**普通 `.jar` 文件 VF 当
`OrderRootType.CLASSES` 根不会进类索引**——首轮 `findClass` 全返 null 就是这个原因，不是能力问题。
另：本仓 JUnit3 形态的 Heavy 用例里 `assumeTrue` 会被平台 runner 记成红任务，夹具缺件要走
「println + return」的显式绿跳。

**测试**：新增 `JpaJarClassFileAnnotationTest` 5 例（夹具可见性诊断 / 注解读取 / 嵌入展平 / 实体解析 /
MP 反查读数）；**只跑本类**（未跑全量、未跑门禁）。

**未做**：真机走查（用户工程形态的端到端预览）；`EntityClassFinder` 的 JPA 侧反查（不是它的职责，
见 §2.2.2，其库兜底由 §7.3 W2 覆盖）。

### 7.6 同名实体按查询宿主模块隔离（2026-10-16，T-EntityNameScope）

用户在 `WmsInventoryJarQueryService.findAvailableJarInventory` 点击预览，产物却是
`wms_inventory_embed_demo`，SELECT 展开出 `move_tool_pallet_no`，WHERE 中的
`sku.inventoryStatus` 与 `itemKey.lotInfo.receivedDate` 保持原样。两处 demo 分别声明同简单名
`WmsInventoryEntity`，全工程短名查询取第一个使 jar-model 查询拿到了嵌入列 demo 的元数据。
这与 §7.5 验证的“JAR 类注解可读”不矛盾：直接解析已指定的正确 PsiClass 不能验证入口是否选对类。

本次不重命名 demo 隐藏碰撞，也不新增设置项。按用户要求，先提交共享接口基底，再用三个独立工作树
并行开发；各工作树独立沙盒、显式测试类，主控通过完成通知协调，不共享在途测试产物。

实施内容：

- `EntityClassResolver` 统一短名、FQN 和完整别名候选的选择。查询宿主模块已知时只查该模块及依赖/库，
  不再向全工程扩大范围捞取不可见类。同 FQN 的可见源码/二进制定义由平台 classpath 顺序处理；
  无上下文或同级多候选时不取首个，返回未解析并通过双语通知提示歧义。
- `@Entity(name)` 与默认实体名处于同一命名空间，大小写精确匹配。此前建议“显式名一定优先”不成立：
  两个可见实体实际使用同一名字时仍是歧义，排序只用于稳定展示，不用于伪造确定答案。
- `EntityAliasSweep.resolve` 收口已有别名慢路径。直接查找有候选时不增加全类遍历；慢路径在既有
  5000 短名预算内收集完整候选，截断不作唯一性判断、不写负缓存。缓存包含查询 scope、PSI 与根版本，
  以智能指针保存结果；索引未就绪安全降级，取消异常向上传播。
- HQL、custom DAO、JPQL、NamedQuery 注解及 HBM tag 入口传递真实查询宿主，而不是选中实体的文件。
  根实体以查询文本为准；NamedQuery 歧义不能继续落入 HBM 路径并偷偷选另一实体。
- HQL/JPQL 的根表、别名、子查询和关联 JOIN 复用查询级解析结果；JOIN 按左侧别名找所属实体。
  保留全限定名边界，不重复把已映射表名当实体查询，取消与严重错误不吞。既有 JOIN FETCH 测试发现
  `FETCH` 被当成实体标识符覆盖根别名，已修复解析，未修改原测试期望。

分车道证据（**不是**集成态验收——见下「收口」）：

| 车道 | 正向与恢复结果 | 反向实验 |
|---|---|---|
| 共享解析器 | 12/0失败/0错误/0跳过；5组外部 javac class 库夹具 | 模块 scope 改 allScope：6红；唯一候选改取首个：5红 |
| 预览入口 | 4类40/0/0/0；含真实class库 customDAO 入口 | 别名扫描恢复首命中即返：3红，其中1条真实PSI缓存链 |
| 转换链 | 7类99/0/0/0；含6项真实模块用例 | 断开查询宿主context：6项中1红，根表、子查询与JOIN映射失效 |
| 转换链补强（副表借根列） | 8/0/0/0 | 摘掉限定符守卫：**3红**（歧义副表、缺失副表/未知限定符契约、FQN 不塌陷） |

**收口（主控接手，2026-10-16）**：三车道集成到 `bcd46e95` 后由只读复核查出三处**跨链路绕过**，
三车道各自全绿都不覆盖它们：

1. **显式传入的类绕过门禁**：`resolveHqlTable` 里 `entityClass`（TypedQuery 结果类型 / `@NamedQuery`
   注解宿主）只要名字匹配就直接返回，既不校验当前查询宿主的可见范围、也不看同名歧义。转换链随后
   优先采用这个非空 TableInfo，因此无法补救。
2. **未解析的副表别名借根表列**：`(owner ?: rootTable).columnByField(field)` 会把「已声明但解析失败
   （含歧义）」的副表限定符改写成根表列名——歧义实体不选类做到了，歧义别名不借列没做到；且首轮
   限定符守卫会被后续「裸词替换」阶段二次绕过。
3. **预览与转换链的根实体提取口径不一致**：预览用旧弱提取器（不掩码字面量、不看括号深度、只取包名
   首段），转换链用强算法；预览先产出的非空 TableInfo 会被转换链优先采用，于是弱提取压过强提取。
   实测反例：`select (select max(d.id) from Detail d) from Inventory i` → 按 `Detail` 渲染。

修法与依据：

- 把转换链已有的强算法（字面量掩码 + 括号深度 0 + 全限定名保留）**收口进共享
  [HqlEntityNameExtractor]**，`JpaQueryEntityLookup.rootEntity` 改为纯委托——生产只剩一处实现。
- `resolveHqlTable`：显式类不再是旁路。scope 内解析出根实体时以它为准；解析不出时只有显式类本身
  在查询宿主可见范围内才可用；**歧义即终态**（不借显式类/XML/别名 fallback 再挑一个）。
- 副表别名：已声明但解析失败的限定符保持属性原文（不借根表列），裸词替换阶段补「完整点号路径」
  无捕获分支，避免二次绕过；未知限定符与嵌入路径保留既有契约。

**红基线（修前，生产 `bcd46e95` + 仅新增用例）**：2 类 32 例中 **10 红**——提取器 5 条新口径
（标量子查询 / 字面量里的 `from` / 全限定名 / INSERT SELECT 目标表 / CTE 内层）、预览 5 条
（显式不可见类、显式同名歧义、显式类挤掉唯一可见候选、标量子查询抢根、全限定根被包名诱饵吃掉）。
这 10 条就是本次的判别集。

**修后集成读数**：**19 类 199 例 0 失败 0 错误 0 跳过**（解析器 12、预览 17、别名 8、命名查询 6+15、
提取器 15、转换 8+22+4+14+6+15+32+10、demo 3、JAR 注解 5、守卫 4+5+3）。

**反向验证**：一次摘掉三处（①显式门禁 / ②副表限定符守卫 / ③共享提取器）→ **恰 12 红**，与预期集合
逐条对应（③ 7、② 3、① 2），其余 187 例保持绿；还原后复跑回 199/0/0。另单独摘掉「歧义即终态」那一行
→ 58 例中**恰 1 红**（`testExplicitSameNameClassCannotBypassVisibleAmbiguity`），补上①里未被前一次
摘修覆盖的那一半。

**修复过程中自身踩到一个真回归**（记录备查）：第一版把「类解析不到」直接 `return null`，切断了 HBM 纯
XML 映射工程的既有路径（`testHbmRootAndNamedQueryCachesAreModuleScoped` 由绿转红，`199/1`）。改为
「类解析不到 → 仍按既有路径查 XML 映射」后复跑回 199/0/0。**教训：给显式类加门禁时，很容易顺手把
「根本没有 Java 类」这条合法形态一起判死。**

用户报障形态的端到端回归 `testFindAvailableJarInventoryMapsAllThreeEmbeddedPathsThroughProductionPreview`
（外部 javac 真实 class 库、宿主依赖它、另一不可见模块放同名实体 + `embed_demo` 表与 `move_tool_pallet_no`）
在修前修后**都是绿**——它守的是「用户场景不被改坏」，**不是**本次三处绕过的判别用例，两者不要混为一谈。

边界（不变）：普通短名快路不额外扫描「另一 Java 类名声明了同名 `@Entity(name)`」的实体，因此不能声称
穷尽实体名冲突；别名慢路径到 5000 短名预算上限时按未解析处理，而非证明不存在。NamedQuery 仍沿用
「宿主名.查询名」作用域，HBM 未新增 `entity-name`、package、继承映射语法支持。**尚未在用户 IDEA 安装包上
点击验证**（本轮全部为 headless 定向），也未跑全量测试与构建/兼容性门禁。

协作方式记录：共享接口先由主控落地并提交可编译基底，随后三车道在**独立工作树 + 独立沙盒**并行（解析器 /
预览入口 / 转换链），写集互不重叠、各自带定向与反向；用户要求后段收归主控，遂停掉子智能体、把两侧成果
cherry-pick 进主树、由主控补齐 ①③ 并跑集成定向与反向。**教训：分车道全绿只证明各自契约，证明不了跨链路的绕过路径。**

### 7.7 别名慢路径性能收口（同日）：触顶结果落缓存

**起因**：用户追问「这次改动不会带来性能问题吧」。在共享入口 `EntityAliasSweep.resolve` 挂临时探针实测：
一次 19 类定向里 `resolve` 共 **92 次**，其中 **16 次走扫描**（约 17%），其余为**直接命中（不扫描）**；
16 次扫描在测试沙盒里都 `complete=true`，单次 13–97 ms、合计 557 ms；触发扫描的都是**按名直接查不到**的
名字（`a_department` / `stockDetail` / `MissingDetail` 等）。另确认 `previewAsync` 的 compute 跑在后台线程的
**阻塞读动作**里（`SqlPreviewService.kt:1850-1852`），且 gutter 图标**悬停零转换**（tooltip 是静态文案，
点击才转换）⇒ 代价只落在「点一次预览」上，不落在打字/悬停。

**真机代价与收口**：真实工程 `allClassNames` 2 万+（含 JDK）恒大于 `SCAN_LIMIT=5000` ⇒ **触顶是常态**；
触顶后原实现不写缓存 ⇒ **同一名字每次预览都重扫一整段预算**（按本机实测 0.19–0.27 ms/短名外推，单次约
1.0–1.35 s，且该扫描持读锁同等时长——与 T-GutterDupFreeze 同形态，量级从 70 s / 5.5 s 降到 ~1 s）。
**收口＝触顶（未扫完）也落缓存（空候选）**：不改变结果（触顶本就恒为未解析），只去掉重复代价；缓存随
PSI/根版本失效，工程变小后仍会重扫；**直接命中在缓存查询之前就已返回**，故空缓存不会遮蔽真实实体。
另加测试缝 `scanLimitForTest`（置 0 确定性制造触顶）与扫描计数 `scanCountForTest()`。

**读数**：19 类 **205 例 0 失败**（独立沙盒，外置 `-I build/mpsv-perf.init.gradle` 设 `sandboxContainer`）。
**反向**：摘掉「触顶也写缓存」→ **恰 1 红**
（`EntityAliasSweepTest.testTruncatedScanIsCachedAndNotRepeated`，第二次调用重新扫描），还原后复跑回绿。

**两处过程记录（都值得留底）**：

1. **验证命令的静默漏匹配再次命中**：本轮前两次「19 类」读数实际只跑了 **18 类**——`BundleParityGuardTest`
   的包是 `…visualizer.i18n`，我在过滤式里写成 `…config`，而 Gradle 在**部分**过滤式不匹配时不报错，
   结果目录里根本没有它的 XML（**19 条过滤 vs 18 个 XML 才对得上**）。判据仍是「过滤条数 == 落盘 XML
   条数」，不是退出码。修正后为 19 类 205 例。
2. **与另一会话的发布构建撞车**：复跑时另一个会话正在跑「2026.10.16 发布全量+打包」，它清掉了我的日志与
   大部分结果（只剩 13/19 类），该次读数作废。教训：`check` 报警时必须**改用独立沙盒**；且**不要在另一
   会话的发布构建窗口内编辑共享工作树的源码**——编译产物 mtime 早于源码即说明「产物对应哪个源码状态」
   已说不清（本轮就出现过 `.class` 15:48 / 源码 15:50）。


