# T-PreviewPsiDialect —— 预览临时文件映射到插件自注册方言后 PSI 创建失败（ClassCastException）

- 任务编号：T-PreviewPsiDialect（建议占 **O-156**；落笔前按口径 ㉕ 再查台账确认号位）
- 立项日期：2026-10-30
- 触发：用户在日常 IDE（IntelliJ IDEA 2026.2.3 / IU-262.10968.63）用 `mybatis-plus-demo` 验 GaussDB 时，预览面板顶部常驻黄色横幅「预览以简化模式打开——临时文件解析失败；表/列补全暂不可用」
- 在役版本：SQL Visualizer **2026.10.31-beta.1**
- 性质：缺陷修复。用户可感后果＝方言为 GaussDB 时预览恒定丢表/列补全，且每次预览往 `idea.log` 写一条 **SEVERE + Plugin to blame: SQL Visualizer**

---

## 一、现象与根因

### 1.1 现象

`mybatis-plus-demo` 工程（project hash 69432）当天 10 次预览**全部**降级，无一次成功：

```
15:26:51,924 WARN SqlPreviewPanel - [M36] prewarm FAILED in 74ms: PsiManager.findFile null for mpsql-preview-69432-5.sql
15:26:51,951 INFO SqlPreviewPanel - [M36] in-memory PSI preview document created (len=140)
```

面板横幅取的是 `panel.banner.degradedFailed` + `panel.banner.degradedCause.psi`（`PreviewDegradationReason.PSI_UNAVAILABLE`，见 `ui/PreviewDegradationReason.kt:24,48`）——即「预热阶段临时文件 PSI 建不出来」，不是超时宽限那条正常路径。预览本体、参数替换、执行都还能用（编辑器落在内存 PSI 上），**只有表/列补全没了**。

### 1.2 根因（真机日志 + 平台字节码双证）

紧挨着失败有一条被平台「频繁异常抑制」掩盖的 `ClassCastException`，当天共 **9 次**（其中 3 次打成 SEVERE）：

```
SEVERE - #c.i.p.SingleRootFileViewProvider -
class com.intellij.sql.psi.SqlLanguage cannot be cast to class com.intellij.sql.dialects.SqlLanguageDialect
	at com.intellij.sql.dialects.base.SqlParserDefinitionBase.createFile(SqlParserDefinitionBase.java:90)
	at com.intellij.psi.AbstractFileViewProvider.createFile(AbstractFileViewProvider.java:152)
	at com.intellij.psi.SingleRootFileViewProvider.getPsiInner(SingleRootFileViewProvider.java:108)
	at com.intellij.psi.AbstractFileViewProvider.getPsi(AbstractFileViewProvider.java:199)
	at com.intellij.psi.impl.file.impl.FileManagerImpl.findFile(FileManagerImpl.java:486)
	at com.intellij.psi.impl.PsiManagerImpl.findFile(PsiManagerImpl.java:207)
	at cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelEditorLifecycleKt.awaitPreviewPsiLanguage$lambda$0(SqlPreviewPanelEditorLifecycle.kt:463)
	at ...SqlPreviewPanelEditorLifecycleKt.prewarmPreviewTempFile$lambda$1(SqlPreviewPanelEditorLifecycle.kt:139)
Plugin to blame: SQL Visualizer version: 2026.10.31-beta.1
```

链路还原：

1. 预览方言＝GaussDB，`mapPreviewDialectBeforePsi`（`ui/SqlPreviewPanelEditorLifecycle.kt:370-401`）把预览**目录**映射到平台语言 `GaussDB`；日志同时给出映射确实生效：`Reparsing .../preview/mpsql-preview-*.sql because of language substitution PostgreSQL->GaussDB`（一次性重解析了该目录下 16 个历史残留文件）
2. `GaussDB` 这个语言是**本插件自己注册**的：`META-INF/database-optional.xml:38-40` 的 `<sql.dialect dbms="UNKNOWN" implementationClass="...GaussDbSqlDialect"/>`，实现类 `sql/gaussdb/GaussDbSqlDialect.kt:30`（`SqlLanguageDialectBase(ID)`，tokensHelper 委托 `Sql92Dialect`）
3. 但插件**只注册了 `sql.dialect`，没有为该语言注册 `lang.parserDefinition`**。平台查 ParserDefinition 时沿语言链上溯，只能落到基语言 `SQL` 的那份（`database-plugin.jar` 的 `plugin.xml:1253`：`<lang.parserDefinition language="SQL" implementationClass="SqlParserDefinitionBase$Sql"/>`）
4. 那份 `SqlParserDefinitionBase.createFile`（字节码第 90 行附近）把 view provider 的基语言强转 `SqlLanguageDialect`，而此刻拿到的是**基类 `SqlLanguage`** → CCE
5. CCE 抛出 `findFile`；同一文件再次尝试时表现为 `findFile` 返回 null（异常已被平台记为抑制/缓存），于是走到 `SqlPreviewPanelEditorLifecycle.kt:153` 的 `PSI_UNAVAILABLE` 分支，横幅亮起

对照平台内置方言的注册形态（同一份 `database-plugin.jar` 的 `plugin.xml`）：

| 语言 | parserDefinition |
|---|---|
| SQL | `SqlParserDefinitionBase$Sql`（:1253） |
| GenericSQL | `GenericParserDefinition`（:1913） |
| SQL92 | `Sql92ParserDefinition`（:1925） |
| **DM / GaussDB / HighGo / Vastbase（本插件）** | **无** |

### 1.3 影响面

- **必现、且不限 GaussDB**：四个自注册方言（`DmSqlDialect` / `GaussDbSqlDialect` / `HighGoSqlDialect` / `VastbaseSqlDialect`）注册形态完全相同，只要预览或执行临时文件被映射到其中任一语言，都会撞同一处 CCE。达梦/瀚高/海量用户此前没人报，是因为「预览方言落到这四个语言」这条路径本轮才随 GaussDB 独立身份真正跑起来
- **两条链都受影响**：预览预热（`prewarmPreviewTempFile`）与执行临时文件（`DataSourceSelector` 共用 `execute/DialectPreMapping.kt:219-257`）
- **质量问题升级**：每次预览都往 `idea.log` 写 SEVERE 并点名本插件（`Plugin to blame`），Marketplace 审核与用户排障都会读到
- 不受影响：MySQL / PostgreSQL 等平台内置方言的预览（走平台自带 parser definition）

---

## 二、目标与非目标

**目标**

1. 方言为 GaussDB / DM / HighGo / Vastbase 时，预览预热**不再降级**，表/列补全可用
2. `idea.log` 不再出现该 CCE（含被抑制的计数），不再有点名本插件的 SEVERE
3. 修复落在「方言注册形态」这一层，不靠吞异常掩盖

**非目标**

- 不做 GaussDB 专属 parser（不引入新语法解析能力）；关键字集仍以 `SqlKeywords.GAUSSDB_RESERVED` 为渲染侧单一事实源
- 不改 `PreviewDegradationReason` 的分型与文案（那套分型是对的，这次只是暴露了一条以前没走到的失败路径）
- 不动执行/预览的方言推断逻辑（`ExecDialectRouting` 的候选链保持不变）

---

## 三、实施方案

### S1（主修）给四个自注册方言补 `lang.parserDefinition`

在 `META-INF/database-optional.xml` 现有四个 `<sql.dialect>` 旁各加一条 parser definition 注册，实现类照平台 `Sql92ParserDefinition` 的最小形态（本插件的方言 tokensHelper 已委托 Sql92，语义一致）：

```xml
<lang.parserDefinition language="GaussDB"
                       implementationClass="cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbParserDefinition"/>
```

实现类（每方言一个，几行）：

```kotlin
class GaussDbParserDefinition : Sql92ParserDefinition()   // 或 SqlParserDefinitionBase 的最小派生
```

- 首选直接继承平台 `Sql92ParserDefinition`（零自维护）；若它在构造期就强依赖自身语言（需 P-1 验证），改为继承 `SqlParserDefinitionBase` 并只实现 `createElementFactory()`，工厂委托 `Sql92Dialect`
- 四个方言一次补齐，不留同类缺口

### S2（前置保险）映射目标加「可建 PSI」门

`mapPreviewDialectBeforePsi` 在 `ensureMapped` 之前，对目标语言做一次**能力探测**：若该语言的 ParserDefinition 不是 SQL 方言族（或探测本身失败），则**跳过映射**、保持平台默认/GenericSQL，并打一行 INFO 说明原因。这样即使将来再出现同类注册缺口，最差结果是「补全按 GenericSQL 走」，不会整条预览降级。

### S3（防御）PSI 创建失败不再连带整条预热

`awaitPreviewPsiLanguage`（`:454-470`）与主路径 `findFile`（`:141-143`）外包 `runCatching`：CCE 这类**平台契约错误**只记一次 WARN（带堆栈，供排障），预热按「无 PSI」继续，避免把 SEVERE 留给平台记到插件头上。

### S4 测试与反向验证

- 新增定向用例（`testFast` 桶）：构造 `GaussDB` / `DM` 语言 → 建临时 `.sql` → 映射 → `PsiManager.findFile` 非 null 且 `psi.language.id == "GaussDB"`
- 反向验证：摘掉 S1 的 `lang.parserDefinition` 注册 → 该用例必须变红（复现 CCE），还原复绿；摘掉 S2 的探测 → 需构造「注册缺口」场景证明降级路径生效
- 守卫：`PlatformSqlDialectRegistrationTest` 扩一条「每个 `<sql.dialect>` 必须有同名 `lang.parserDefinition`」的静态断言（扫描 `database-optional.xml`，成对校验）——这是**根因守卫**，防止将来加第五个方言时重演

### S5 真机验证与文档

- runIde 沙盒 + `mybatis-plus-demo`（`-Pgaussdb`）双击日志条目 → 横幅不再出现、`idea.log` 无 CCE、补全能列出 `t_order` 等表
- 同步 `docs/架构设计/03` 的「新增方言」清单（补 parser definition 这一步，现有清单漏了它）
- `CHANGELOG.md` Fixed 段 + 变更记录

---

## 四、待验证前提（实施前先证伪，不成立就改方案）

| 编号 | 前提 | 证伪方式 | 不成立时的影响 |
|---|---|---|---|
| **P-1** | 平台 `Sql92ParserDefinition` 可以无参构造、语言由 EP 的 `language` 属性绑定（直接继承即可复用） | `javap` 看构造器 + 沙盒里注册后跑 S4 用例 | 改为继承 `SqlParserDefinitionBase` 并实现 `createElementFactory()`（工作量 +1 券） |
| **P-2** | 补了 parserDefinition 后，`SqlLanguageDialect` 强转即可通过（即 CCE 的唯一成因就是「缺 parser definition」而非方言对象本身形态不对） | 沙盒内最小复现：先只加注册跑用例 | 若仍 CCE，则要看 `SqlLanguageDialectBase` 的 `getLanguage()` 是否返回自身；退到方案 B（见下） |
| **P-3** | DM / HighGo / Vastbase 同样撞这条（同一注册形态） | 在四个方言上各跑一次 S4 用例 | 若只有 GaussDB 撞，则差异点在 GaussDB 独有属性（如 `dbms=UNKNOWN` 之外的 id 冲突），缩小修复面 |
| **P-4** | 262 平台与 2024.3 编译基线在 `SqlParserDefinitionBase.createFile` 上同形 | 262 真机栈（已有一条：`:90`）与 2024.3 javap 对齐 | 若 262 另有分支（如按 `dbms` 分派），S1 形态要按 262 调整 |
| **P-5** | 平台不会因为「同一 language 既有 sql.dialect 又有 parserDefinition」而告重复注册 | 沙盒启动日志零 duplicate EP 告警 | 改为把 parserDefinition 挂在方言类内部的 EP 里（形态变化） |
| **P-6** | 预览目录的历史残留文件（当天实测 16 个）在映射变更时会被一次性重解析，放大 CCE 面 | 清空目录后复现一次 | 若残留是放大器，S3 之外再补一条「预热前清理本目录过期文件」 |

**方案 B（P-2 不成立时的退路）**：不做平台语言映射——预览/执行临时文件保持平台默认方言，GaussDB 的渲染与高亮由插件自己的路径负责（渲染侧本来就与平台 PSI 无关），代价是补全按 GenericSQL 走、方言专属补全能力拿不到。

---

## 五、验收

1. 沙盒里 `mybatis-plus-demo`（GaussDB profile）双击日志条目：**无黄色横幅**，`idea.log` 全文 `grep -c "cannot be cast to class com.intellij.sql.dialects.SqlLanguageDialect"` = 0
2. 预览编辑器里表/列补全能列出 GaussDB 上的真实表（`t_order` / `sys_config` / `department`）
3. 达梦 profile 各跑一次同样判据（P-3）
4. S4 三条守卫全绿，且反向验证两次读数在案（摘注册必红、还原复绿）
5. `testShards` 全量 + `verifyGate` 三目标 Compatible（动了 `database-optional.xml` 的 EP 面，属「大改动」判据，必须跑）
6. 发行说明里如实登记：本缺陷在 v2026.10.31-beta.1 存在，自该版起四个自注册方言的预览补全不可用

---

## 六、风险

- **EP 面改动触发平台校验**：`lang.parserDefinition` 是平台核心 EP，注册错会让 SQL 语言解析整体异常 → 必须在沙盒验证后再合流，且 `verifyGate` 不可省
- **补全能力与平台 parser 耦合**：给方言挂 Sql92 系 parser definition 后，`GaussDB` 文件的语法树按 SQL92 解析，与 GaussDB 实际语法有差异（如 PG 系特有语法）→ 补全可能有误报，需要在验收里抽查 3~5 条 GaussDB 常见语句
- **同类缺口不止预览**：`DataSourceSelector` 的执行临时文件共用同一映射入口，修复后需一并回归执行链（打开执行控制台、EXPLAIN 提交）
- **号位**：O-156 为建议值，落笔前查台账，避免与并行会话撞号

---

## 七、实施记录

（待实施后回填：P-1~P-6 实测结论、变更清单、定向读数、反向验证、未闭环项）

### 7.1 P-1~P-6 实测结论（2026-10-30）

| 前提 | 结论 |
|---|---|
| **P-1** `Sql92ParserDefinition` 可直接继承 | **不成立**。`javap` 实证它和 `GenericParserDefinition` 都是 `final`。改照 `GenericParserDefinition` 的**实现内容**自己派生：继承 `SqlParserDefinitionBase` + 委托一个 `Sql92ParserDefinition` 实例（lexer / parser / elementFactory 三处） |
| **P-2** 补注册后 CCE 消失 | **成立**。反向验证：摘掉 GaussDB 那条注册，DB 通道用例直接复现 `ClassCastException`（`PlatformSqlDialectRegistrationTest.kt:119`）；还原后复绿 |
| **P-3** DM/HighGo/Vastbase 同撞 | **成立（同源）**。DB 通道用例对四个语言循环建 PSI，四者共用同一注册形态与同一修复 |
| **P-4** 262 与 2024.3 的 createFile 同形 | **成立**。262 真机栈落在 `createFile:90`；2024.3 制品字节码第 90 行前后正是 `(SqlLanguageDialect) getFileNodeType().getLanguage()` 的 checkcast |
| **P-5** 双注册不会报 duplicate EP | **成立**。`testDbPlatformDialect` 沙盒正常加载四个 `lang.parserDefinition`，无重复注册告警 |
| **P-6** 历史残留文件是放大器 | **未做**（本轮不扩范围）。当日日志确实有一次对 16 个历史预览文件的重解析，但那是映射变更的正常行为；目录清理另立候选 |

### 7.2 变更清单

| 文件 | 变更 |
|---|---|
| `sql/SqlFamilyParserDefinitions.kt` | **新增**。`MpvSqlFamilyParserDefinition`（file node type 绑本方言语言；lexer/parser/elementFactory 委托 `Sql92ParserDefinition`）+ 四个方言子类 |
| `META-INF/database-optional.xml` | 四个 `<lang.parserDefinition>` 成对补齐，附「为什么必须成对」的注释 |
| `ui/SqlPreviewPanelEditorLifecycle.kt` | ①`mapPreviewDialectBeforePsi` 增加映射前「可建 PSI」探测（`canCreateSqlPsiFor`，按类名沿父类链判 `SqlLanguageDialect`，不静态引用 DatabaseTools）；②两处 `PsiManager.findFile` 包 `runCatching`，平台契约异常降为带栈 WARN |
| `convention/SqlDialectParserDefinitionPairingGuardTest.kt` | **新增**根因守卫（静态配对扫描）+ 登记到 `GuardSelfTestCoverageMetaGuardTest` |
| `database/PlatformSqlDialectRegistrationTest.kt` | 新增 `testPluginDialectLanguagesCanCreatePsiWithoutClassCastException`（DB 通道，四语言真建 PSI） |
| `docs/架构设计/03-开发与测试指南.md` | 「新增数据库方言」清单补第 12 步「平台 SQL 方言注册必须成对」 |
| `CHANGELOG.md` / `docs/变更记录.md` | `[Unreleased]` Fixed 段 + 变更记录一行 |

### 7.3 定向读数

- `SqlDialectParserDefinitionPairingGuardTest`：**2 / 0 / 0 / 0**
- `testDbPlatformDialect`（含新增 PSI 用例）：**3 / 0 / 0 / 0**
- 主代码 `compileKotlin` BUILD SUCCESSFUL；新增类已落 `build/classes/.../sql/*ParserDefinition.class`

### 7.4 反向验证（两次读数在案）

| 动作 | 读数 |
|---|---|
| 摘掉 GaussDB 的 `<lang.parserDefinition>` | 静态守卫 **2 例 1 红**（「每个自注册方言都必须有配套的 lang parserDefinition」）；DB 通道 **3 例 1 红**，失败形态正是 `ClassCastException`（`PlatformSqlDialectRegistrationTest.kt:119`） |
| 还原注册 | 两者复绿（静态 2/0、DB 3/0） |

### 7.5 未闭环

- **runIde 真机复验未做**：需在沙盒 + `mybatis-plus-demo`（`-Pgaussdb`）双击日志条目，确认①横幅不再出现、②`idea.log` 中该 CCE 计数为 0、③补全能列出 `t_order` / `sys_config` / `department`
- 全量 `testShards` 与 `verifyGate` 三目标：动了 `lang.parserDefinition` 这个平台核心 EP，属「大改动」判据，合流/发版前必须补跑
- P-6（历史预览残留文件的清理）未做

### 7.6 `verifyGate` 暴露的跨版本类缺失（本轮补修）

首轮 `verifyGate` 结果：**IU-243 Compatible，IU-253 / IU-262 各 1 个 compatibility problem**：

```
Constructor cn.song.mybatisplus.visualizer.sql.MpvSqlFamilyParserDefinition.<init>(SqlLanguageDialect, String)
references an unresolved class com.intellij.sql.psi.stubs.SqlFileElementType.
This can lead to NoSuchClassError exception at runtime.
```

即 `SqlFileElementType` 只在 2024.3（编译基线）存在，**253 / 262 已删除整个 `com.intellij.sql.psi.stubs` 包**（253 制品里只剩一个 `hsql/psi/stubs/HsqlStubRegistryExtension`）。这类缺口本仓库测试跑不出来——编译期有、运行期没有，只有 verifier 按目标 IDE 逐个解析才报。

**修法**：不再引用该类，改用核心（三个版本都在）的
`com.intellij.psi.tree.IFileElementType(String, Language)` 直接构造文件根元素类型。查证过程：
- `StubFileElementType` 是抽象类且要求实现 StubSerializer 四件套（`getExternalId` / `serialize` / `deserialize` / `indexStub`）——用不上，预览临时文件不建存根；
- `IFileElementType` 在这个版本线上**本身就是抽象类**（不是接口），恰好带 `(String, Language)` 构造器 → 一行继承即可。

代价：文件根元素类型不是 SQL 侧类型，不参与 SQL 存根索引——对「预览临时文件的瞬时 PSI」没有影响，DB 通道的 PSI 用例仍绿。


