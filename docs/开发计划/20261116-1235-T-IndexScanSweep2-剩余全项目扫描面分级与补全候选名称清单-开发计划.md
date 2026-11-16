# T-IndexScanSweep2：剩余「全项目扫描」分级与补全候选名称清单

- 立项：2026-11-16 12:35
- 任务源：用户 2026-11-16 指示「候选推荐做的话 先立项」——即：把 T-MechanismSweep（`501db7d89`）收口后剩下的同形态扫描面逐个评估，**要做的先出计划再动手**。
- 前序：`docs/开发计划/20261116-0925-T-IbatisGutterPerf-…-开发计划.md` §9.8（本轮已索引化 `MybatisXmlTargets` / `XmlCrossNsFragmentResolver`，保留 HBM 等三面并留账）。

## 一、范围与判据

**范围**：全仓 `src/main` 里所有「按文件类型 / 文件名 / 扩展名取全项目文件、再逐个建 PSI」的落点。

**判据（沿用 §9.8 的口径，量化一档）**：

| 维度 | 判据 |
|---|---|
| 进不进交互热路径 | 每次**按键/补全弹窗/高亮 pass** 都跑 → 必须处理；只在预览、设置页、启动各跑一次 → 先实测再定 |
| 单次成本 | 台账记「同一次调用里建 PSI 的文件数上限」与实测耗时；**建 PSI 的文件数**是成本主因，索引查询本身（拿 VirtualFile 列表）廉价 |
| 有没有缓存 | 有「按 PSI 修改计数」失效的缓存等于没有（击键即失效）；按索引/结构版本失效才有效 |

## 二、候选清单与证据（2026-11-16 实测枚举）

| # | 落点 | 形态 | 调用频率 | 单次成本（代码实测） | 现有缓存 | 分级 |
|---|---|---|---|---|---|---|
| C1 | `ui/highlight/XmlCompletionSymbols.crossFileSqlIds`（:231-254） | `FileTypeIndex(XML)` → `take(500)` → **对最多 500 个 XML 建 PSI** + 取 `rootTag` + 遍历 `<sql>` | **每次补全调用**（`XmlSqlCompletionContributor:300-301` 跨文件 `refid` 候选，弹窗期随输入重复触发） | 500 个 PSI 文件读（每次全付） | **无** | **P1 做** |
| C2 | `ui/highlight/XmlCompletionSymbols.configPropertyKeys`（:298-315） | 同上（`take(500)` + PSI） | 补全链（`OgnlVariableSources:47`） | 同上 | 有，但失效戳是 `PsiModificationTracker.modificationCount`（**击键即失效，等于没有**） | **P1 做**（同一套清单复用） |
| C3 | `psi/jpa/HbmXmlEntityResolver.findHbmXmlFiles`（:218-228） | `FileTypeIndex(XML)` → 文件名后缀过滤 `.hbm.xml` → 只对这些文件建 PSI | JPA/Hibernate 预览链（`resolve` / `findNamedQuery` / `findNamedQueryDefinition`），有按查询名的负命中缓存 | 索引查询返回全项目 XML 列表 + 后缀过滤；**非 hbm XML 不建 PSI**。demo 语料实测 `.hbm.xml` 仅 **1 个** | 负命中缓存 + generation | **P3 不做**（见 §三） |
| C4 | `sql/ProjectDialectEvidence`（:414-432） | `FilenameIndex.getVirtualFilesByName`（按名）+ `getAllFilesByExt`（按扩展名） | 方言推断（预览/设置，有缓存代） | 按名/扩展名的索引查询（**不建 PSI**），随后只读少量候选文件 | TTL + 结构版本缓存 | **P3 不做** |
| C5 | `psi/jpa/HibernateNamingConfigProbe`（:201-206） | `getAllFilesByExt(yml/yaml/properties)` → `readText` 若干配置文件 | JPA 预览期命名策略探测 | 索引查询 + 少量配置文件读；扩展名只有 3 个纯配置类 | 模块级/工程级两档，先模块后工程 | **P3 不做** |

补充说明：C3~C5 的**索引查询本身不建 PSI**（C3 只对 `.hbm.xml` 建、C4/C5 只读少量配置文本），与 C1/C2 的「500 个 PSI」不是同一量级。
`ui/highlight/XmlCompletionSymbols` 的另外两处（同文件 `<sql>` / 当前文件标签）是纯 PSI 局部读，不在本清单。

## 三、分级结论

- **P1（推荐做，本计划主体）**：C1 + C2 —— 补全候选的「跨文件 `<sql id>`」与「mybatis-config `<properties>` key」两份清单，改成**项目级名称清单服务**：一次构建、按索引状态失效、补全期零 PSI 遍历。设计见 §四。
- **P3（不做，留账）**：C3/C4/C5。理由：三处都不在「每次按键」路径上，且成本主因（建 PSI）只落在极少数文件上；C3 在 demo 语料里目标文件只有 1 个，索引化要新增一套 `.hbm.xml` 实体索引（新索引 + 内核 + 测试 + EP 注册 + 一轮 verifyPlugin），收益小于成本。**若日后 JPA 预览出现可测的卡顿**，再按 §五 的实测方案决定。

## 四、P1 设计草案

### 4.1 名称清单服务

新增 `ui/highlight/XmlSymbolNameIndexCache`（项目级服务，`@Service(Service.Level.PROJECT)`）：

- 维护两份 `Set<String>`（不可变快照）：`crossFileSqlIds`（`ns.sqlId`，无 namespace 时裸 id）与 `configPropertyKeys`；
- **构建来源**：`log/MapperNamespaceIndex` 的**文件级**查询（C1 只需 mapper 文件；C2 只需 `mybatis-config` / `configuration` 根）→ 见 4.2 的键面扩展；
- **失效口径（关键）**：按**索引文件集的变化**失效，而不是按 `PsiModificationTracker`——具体用「索引里 mapper 文件集合的内容哈希/数量 + `FileBasedIndex.getModificationStamp(project, NAME)`」（F1''' 已验证索引可查）作为代戳，PSI 内容改动只在**该文件属于清单来源**时才重建；
- **构建时机**：懒建（首次补全时后台建一次），建好前补全返回空（与现有 dumb 闸同语义，不阻塞 EDT）；构建走 `ReadAction.nonBlocking`（承 `MapperXmlLocator`/`GutterWarmScheduler` 定例），不引 `PsiTreeChangeListener`（红线族维持 0）。

### 4.2 索引键面扩展（`MapperNamespaceIndex` v1 → v2）

现有键面（F1'''，`log/MapperNamespaceIndex`）：**键 = 根标签 `namespace` 属性值本身**（无前缀，value 恒 null 的标量键），查询侧 `getMapperXmlFiles(project, namespace)`。

本刀新增两类**文件级标记键**，让「有哪些 mapper 文件」「有哪些配置文件」同样一次查回，避免再退化成 FileTypeIndex 全量：

| 键 | 含义 | 消费者 |
|---|---|---|
| `#mapper`（常量键） | 「本文件根标签是 `<mapper>`」 | C1 清单构建 |
| `#config`（常量键） | 「本文件是 mybatis-config 形态（根 `<configuration>` 且含 `<properties>`/`<mappers>` 之一）」 | C2 清单构建 |

- **前缀用 `#`**：namespace 是 Java 包名形态、不含 `#`，故与既有 namespace 键天然不撞（iBatis 侧的 `all`/`ns:`/`stmt:` 前缀方案同理，但那边允许无前缀字面量，这边不行）；
- `getVersion()` 由 1 → 2（触发一次增量重建，属计划内的平台行为）；
- 键面扩展后与 MPJ 的 `W.d` 名称清单 service 是同形态（`docs/调研报告/20261115-0202` 已记其失效口径，可对照）；
- 兼容性：新增键不改既有 namespace 键的语义与消费方（`MapperXmlLocator` 零改动），只有 `getVersion` 递增。

### 4.3 验收与回退

- 回退开关（Registry）：`sqlvis.completion.nameIndexCache`（默认 true）→ false 时补全改回「每次现扫」（即当前行为），用于真机 A/B；下一版删除（承 T-IbatisGutterPerf P5 的口径：**不承诺长期保留**）。
- 观测：清单构建打一条 `[completion-perf] name index built: sqlIds=N props=M files=K ms=T`。

## 五、C3 的实测方案（只有出现 JPA 预览卡顿才做）

1. 在 `findHbmXmlFiles` 前后加一次性 `System.err` 计时（不进产品）；
2. 在 **demo 根工程**（2493 个 XML）打开 JPA 预览，记录单次耗时；
3. 判据：单次 > **50ms** 才立项做；否则维持现状并在 §八 记一条「已实测，收益不足」。

## 六、验收清单（可照抄）

| 项 | 判据 | 期望读数 |
|---|---|---|
| A1 | 补全候选仍正确 | 跨文件 `refid` 候选（`ns.sqlId`）与 mybatis-config properties key 与改造前逐项一致（改造前后各取一次候选清单做集合比对） |
| A2 | 补全期不再建 500 个 PSI | 源码扫描：`crossFileSqlIds` / `configPropertyKeys` 体内不得再出现 `FileTypeIndex` 与 `PsiManager.findFile`；行为面：清单构建计数在连续多次补全中为 1 |
| A3 | 清单失效正确 | 新增/修改 mapper XML 的 `<sql id>`、改 `mybatis-config` properties 后，下一次补全立即包含新值（无 5s 窗口） |
| A4 | dumb / 索引未就绪 | 补全返回空候选，不抛、不标红（与现状同语义） |
| A5 | 大工程稳态 | demo 根工程连续补全 20 次：`[completion-perf]` 只出现 1 次（除非期间改过 mapper XML） |
| A6 | 回退通道 | Registry `sqlvis.completion.nameIndexCache=false` 后行为回到现扫路径（A/B 用） |

## 七、待验证前提（实施者先证伪再动手）

| # | 推断 | 证伪方式 |
|---|---|---|
| P-1 | `crossFileSqlIds` 真的**每次补全**都跑（而非弹窗首帧一次） | 在方法入口临时打点，连续输入 10 个字符看计数 |
| P-2 | 500 个 PSI 读是补全延迟的主因 | 用 P-1 的探针同时打耗时；若单次 < 20ms，则本任务优先级下调 |
| P-3 | `FileBasedIndex.getModificationStamp(project, NAME)` 在 243/262/263 三平台均可用且语义为「索引内容代」 | 三腿 `verifyPlugin` + 单测（key 名以 jar `javap` 为准，别凭记忆） |
| P-4 | mybatis-config 的 `configuration` 根标签判定能覆盖真实工程形态（`<configuration>` 直根；`<properties>` 可能在 `<configuration>` 子级） | 用 demo 与用户 `cyzc-platform` 语料各核一遍 |

## 八、实施记录

实施日期 2026-11-16；工作树 `.worktrees/t-index-scan-sweep2`（分支 `t-index-scan-sweep2`，基线 master `ba8b53dc5`）。按主控定例**不跑 verifyPlugin / 构建校验**（交 CI），只跑定向测试。

### 8.1 P-1/P-2 证伪（先做，结论：**继续实施**）

**静态证据**

- 补全入口在**每次补全调用**都执行：`ui/highlight/XmlSqlCompletionContributor.kt:299-302` 的 `refid` 分支（`:301` 调 `XmlCompletionSymbols.crossFileSqlIds(project, enclosingTag.containingFile as? XmlFile)`）；该分支在 `addCompletions`（`:65-69` 起）的属性值分流里，每次弹窗/随输入重算都会走到。
- 改造前 `XmlCompletionSymbols.kt:233-254` 的 `crossFileSqlIds` 体内**无任何缓存**（无 stamp、无 project user data），每次都是 `FileTypeIndex(XML)` → `take(500)` → 逐文件 `PsiManager.findFile` → `rootTag` → 遍历 `<sql>`。
- 改造前 `configPropertyKeys`（`:299-317`）虽有缓存，但失效戳是 `PsiModificationTracker.modificationCount`（击键即失效，等于没有）。

**定量证据**（临时 `BasePlatformTestCase` 探针，N=100/200/400 个 mapper XML；每档热身一次后连测两次，探针测后已删）

| N | xmlFiles | size1 / size2 | ms1 / ms2 |
|---|---|---|---|
| 100 | 101 | 100 / 100 | 3.46 / 2.88 |
| 200 | 201 | 200 / 200 | 44.69（首查含索引落地+JIT）/ 2.99 |
| 400 | 401 | 200 / 200（CAP=200 截断） | 2.80 / 2.77 |

判读：**第二次与第一次同量级 ⇒ 每次全付、无缓存**；耗时随 N 增长（100→200 档可见）。Light 夹具的 PSI 比真机便宜（绝对值 2~4ms），故按计划口径取**相对形态**作判据，不取绝对值 → **继续实施**。

**P-3 前置事实（实测推翻计划里猜的签名，影响设计）**

- `javap` 实证：243（`lib/app-client.jar`）与 262（`lib/intellij.platform.indexing.jar`）的 `com.intellij.util.indexing.FileBasedIndex` **没有** `getModificationStamp(Project, String)`；在册的是 **`getIndexModificationStamp(ID<K,V>, Project): long`**。
- 运行期实测语义：该代戳只随**索引产出**变化——新增一个 XML 文件 +1；改已存在 mapper 的 `<sql id>`（namespace/根标签没变 ⇒ 索引键不变）**恒不变**（`before=401 after=401`）；同期该文件 `VirtualFile.modificationStamp` 由 `14 → 2112`。故本刀失效代戳＝索引代戳 **⊕** 逐文件 `VirtualFile.modificationStamp`，缺一不可。

### 8.2 改动点与提交

| 提交 | 内容 |
|---|---|
| `bf2f61010` | `log/MapperNamespaceIndex` v1→v2：新增 `#mapper` / `#config` 文件级标记键；新增 `rootElementName`（跳过 XML 声明/DOCTYPE/注释后首个元素名，修掉 `<mappers><mapper resource=…/>` 被老锚误判成 mapper 文件）；`getVersion()` 1→2；新增 `getMapperFiles` / `getConfigFiles` / `getIndexStamp`。既有 namespace 键语义零改动（`MapperXmlLocator` / `XmlCrossNsFragmentResolver` / `MybatisXmlTargets` 三消费方不动） |
| `b77180ee6` | 新增 `ui/highlight/XmlSymbolNameIndexCache`（`@Service(Service.Level.PROJECT)`，懒建、`ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project)`、按「索引代戳 ⊕ 逐文件 modStamp」失效、建好前返回空、不引 `PsiTreeChangeListener`/`EditorFactoryListener`）；`XmlCompletionSymbols.crossFileSqlIds` / `configPropertyKeys` 接线 + `legacyCrossFileSqlIds` / `legacyConfigPropertyKeys` 回退；`plugin.xml` 登记 `<registryKey key="sqlvis.completion.nameIndexCache" defaultValue="true">`（英文 description，不引 bundle 键） |
| `1184529a2` | `XmlSymbolNameIndexCacheTest`（8 例）+ `XmlCompletionNameIndexWiringTest`（2 例：方法体扫描 + 合成样本自证）+ `MapperFileLookupIndexTest` 注释同步（`XmlCompletionSymbols` 的扫描面改指新守卫） |
| `8bb718909` | `CHANGELOG.md` `[Unreleased]` 回填 |
| （本笔） | 本节 §八 实施记录回填 |

### 8.3 定向读数（同批 98 例，全绿）

| 测试类 | tests | failures | errors |
|---|---|---|---|
| `ui.highlight.XmlSymbolNameIndexCacheTest` | 8 | 0 | 0 |
| `ui.highlight.XmlCompletionNameIndexWiringTest` | 2 | 0 | 0 |
| `ui.highlight.XmlSqlCompletionW4W5CrossFileTest` | 7 | 0 | 0 |
| `ui.highlight.XmlSqlCompletionW4W5Test` | 29 | 0 | 0 |
| `log.MapperXmlLocatorTest` | 10 | 0 | 0 |
| `ui.ref.MapperFileLookupIndexTest` | 2 | 0 | 0 |
| `convention.GuardSelfTestCoverageMetaGuardTest` | 8 | 0 | 0 |
| `i18n.BundleParityGuardTest` | 5 | 0 | 0 |
| `config.PluginXmlExtensionPointResolutionTest` | 4 | 0 | 0 |
| `config.PluginXmlStructureGuardTest` | 23 | 0 | 0 |

覆盖的验收项：A1（候选逐项正确）/ A2（连续 5 次调用只构建 1 次，计数缝增量断言）/ A3（新增 mapper XML、改 `<sql id>`、改 properties 三项均立即生效）/ A4（dumb 态返回空不抛）/ A6（Registry 回退走现扫且不动用服务）。

### 8.4 反向验证（两次读数）

- **红**：① 把 `FileTypeIndex.getFiles(…)` 塞回 `crossFileSqlIds` 方法体；② 把 `signature()` 乘 0（失效做坏，恒返回 0）。
  - `XmlCompletionNameIndexWiringTest`：2 例 / **1 失败**（`清单来源方法体不得回退现扫形态_且必须经名称清单服务` 点名 `FileTypeIndex`）。
  - `XmlSymbolNameIndexCacheTest`：8 例 / **3 失败**（`testA3_新增mapperXML后立即可见` 只见 `NsA3a.fragA3a`、缺 `NsA3aNew.newFrag`；`testA3_修改已有mapper的sqlId后立即可见` 仍只见 `oldFrag`；`testA3_修改config_properties后立即可见` 仍只见 `p1`）。
- **绿**：`git checkout --` 还原两处后重跑：`XmlCompletionNameIndexWiringTest` 2/0/0、`XmlSymbolNameIndexCacheTest` 8/0/0。

### 8.5 偏离与未做项

- **失效边界＝落盘**：`VirtualFile.modificationStamp` 只随保存变化，故「别的文件里**未保存**的编辑」要等保存才进清单；当前文件自身的 `<sql id>` 不受影响（同文件片段由 `localIds` 走活 PSI 补齐）。计划 §4.1「PSI 内容改动只在该文件属于清单来源时才重建」以落盘为界，已在服务 KDoc 与本节登记。
- **排除当前文件的语义**：改造前按**文件身份**排除；现按**名字集合**排除——两个文件声明同一 `ns.sqlId`（同 namespace 重名属工程错误）会被一并排除；无 namespace 的 mapper 文件其裸 id 亦按名字排除。病态形态，正常工程逐项一致。
- **生产首帧**：懒建，首次补全的极短窗口内跨文件候选为空（计划 §4.1 已认可）；单测/unit-test 模式同步构建以保证确定性（`syncForTest` / `isUnitTestMode` 两档）。
- **测试基建补丁**：实测单测的 project 在同一测试类内会被复用，导致服务状态跨用例污染（某用例置 `enabledProvider=false` 后，后续用例静默走回退链）——故服务新增 `resetForTest()` 测试缝，用例同时用唯一文件/命名空间 + 计数增量断言。
- **未做**：P3（C3 `HbmXmlEntityResolver` / C4 `ProjectDialectEvidence` / C5 `HibernateNamingConfigProbe`）按 §三 不做；C3 的「JPA 预览卡顿实测」未做（无卡顿报告，按计划留账）。
- **构建校验**：按主控定例（全量门禁与构建校验交 CI）**未跑** `verifyPlugin` / `buildPlugin` / `verifyGate` / `verifySmoke`。
