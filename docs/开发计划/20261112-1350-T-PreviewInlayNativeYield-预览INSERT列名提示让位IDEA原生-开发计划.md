# T-PreviewInlayNativeYield：预览 INSERT 列名提示与 IDEA 原生重复 — 让位机制开发计划

- 日期：2026-11-12 13:50
- 任务编号：T-PreviewInlayNativeYield（前序：`T-InsertColumnInlay` O-44b，20260920）
- 性质：体验缺陷（**纯显示层**：不改 SQL 文本、不影响执行/复制/分享产物）
- 来源：用户 2026-11-12 真机反馈，附 cust-big-demo 的 `CustAddressMapper.xml` insert 预览截图：「插件的字段提示和 idea 原生的字段提示冲突了」+「应该优先 idea 原生的字段提示，原生没有的插件再提示」
- 建议分支 / 工作树：`t-preview-inlay-native-yield`，自 `master` 切出
- 本阶段交付：**只出计划**，不动 `src/main`

---

## 一、需求拆解

用户一句话里有两件事，必须分开处理：

1. **现象**：同一条 INSERT 的值表达式上出现了两个一模一样的列名提示（截图 `VALUES (ID?, CUST_NO CUST_NO '1', CUST_NAME CUST_NAME '1', …)`——`CUST_NO`、`CUST_NAME`、`CUST_TYPE` 各出现两次，`ID` / `SOURCE_SYS` / `EXT_ATTR` / `CERT_TYPE` 各一次）。
2. **诉求**：这条链路以 IDEA 原生为准；只有原生给不出提示的地方，本插件才补。

本任务做三件事：

- 把「原生那一侧到底是谁在画」（EP、provider、选项、默认开闭）钉死在源码/发行物一级；
- 给本插件的 INSERT 目标列 inlay 补一道**让位闸门**，判据取「原生具备该能力且处于开启态」；
- 让位必须**只在原生真能覆盖时发生**——原生不可用（Community / 未装 DatabaseTools / 版本无该 provider）时，本插件照旧提示。

不做：不动 SQL 渲染、参数面板、回填、执行、复制、分享链；不动 XML/注解补全面（`XmlSqlCompletionContributor`、`AnnotationSqlCompletionContributor` 那套是**补全候选**，与本次的 inlay 显示层无关）；不新增平台 EP 注册项（沿用既有 `codeInsight.inlayProvider` 注册）。

## 二、复现与证据

### 2.1 现象读数（读图，含一处不确定）

- 预览标题栏：`表: cust_address | XML insert: insert | parameterType: cn.song.custmetadata.entity.CustAddress`，右上角提示「未绑定数据源（在控制台执行一次查询即可绑定）」。
- 第 3 行 `VALUES (ID?, CUST_NO CUST_NO '1', CUST_NAME CUST_NAME '1', CUST_TYPE CUST_TYPE '1', SOURCE_SYS?, EXT_ATTR?, CERT_TYPE? …)`：
  - 灰底小胶囊是 inlay presentation，`'1'` / `?` 是文档文本；
  - **有值的列出现两枚同名胶囊，无值的列（`?`）只出现一枚**。
- 读图结论（**不是定论**）：两枚胶囊 = 两个 provider 在同一 offset 各投一枚。哪一枚是原生、哪一枚是我方，靠截图分不清；且头部写着「未绑定数据源」，与「原生需要 schema 解析才画得出」存在张力。故设 **P-1 分诊步骤**（§八）：用原生开关做 A/B，分诊结果决定本计划是否按原路线走。

### 2.2 我方当事人（源码级）

| 环节 | 位置 | 现状 |
|---|---|---|
| EP 注册 | `src/main/resources/META-INF/plugin.xml:314-315` | `<codeInsight.inlayProvider language="SQL" implementationClass="…ui.inlay.PreviewInsertColumnInlayProvider"/>`（language 级） |
| Collector 闸门 | `ui/inlay/PreviewInsertColumnInlayProvider.kt:163-171` | 三道：作用域双锚点（文件名 + userData）→ 语句类型 `INSERT` → 规划器静态可判 |
| 作用域双锚点 | 同文件 `:57-139` `PreviewInlayScope` | 文件名紧致形态（`mpsql-preview-<owner>-<seq>.sql` / `mpsql-preview-memory.sql`）+ `KEY` userData 载体 |
| 规划 | `ui/inlay/InsertColumnInlayPlanner.kt` | 只读文档文本，`INSERT INTO t (c1,c2) VALUES (v1,v2)` 逐表达式挂列名；`INSERT … SELECT` 逐投影挂；不可判即不提示 |
| 一次性投递 | `PreviewInsertColumnInlayProvider.kt:212-233` | `AtomicBoolean` CAS，首次调用投完全部提示 |
| 设置项 | 同文件 `:153-156`、`:306-308` | **零设置项**（EP 未注册 `settingsKeyId`，`NoOpConfigurable` 空壳）——用户当前唯一关法是平台 inlay 全局/语言级开关（但那会连原生一起关，无法只关我方） |

### 2.3 原生当事人（263 实测，IU-263.7307.200）

发行物一级证据——`plugins/DatabaseTools/lib/database-plugin.jar!/META-INF/plugin.xml:2533-2538`：

```xml
<codeInsight.declarativeInlayProvider providerId="sql.column.names.inlay.hints" language="SQL"
    implementationClass="com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider"
    isEnabledByDefault="true" group="PARAMETERS_GROUP" …>
  <option optionId="sql.show.column.names.in.insert.values"   enabledByDefault="true" …/>
  <option optionId="sql.show.column.names.in.select"          enabledByDefault="true" …/>
  <option optionId="sql.show.column.names.for.asterisk"       enabledByDefault="true" …/>
  <option optionId="sql.show.column.names.for.set.operations" enabledByDefault="true" …/>
</codeInsight.declarativeInlayProvider>
```

| 项 | 实测值 |
|---|---|
| 能力 | **INSERT VALUES 值列名提示**（`sql.show.column.names.in.insert.values`），与 O-44b 完全重叠；`in.select` 一项还与 `INSERT … SELECT` 投影分支重叠 |
| 默认态 | provider `isEnabledByDefault="true"`，四个 option 也各 `true` ⇒ 开箱即重复，不需要用户动过设置 |
| 实现类所在 | `plugins/DatabaseTools/lib/modules/intellij.database.sql.core.impl.ex.jar` → `com/intellij/sql/inlays/SqlColumnNamesInlayHintsProvider.class` |
| 接口 | 实现的是**声明式** `com.intellij.codeInsight.hints.declarative.InlayHintsProvider`（常量池实测），走的是**另一条 pass**（`DeclarativeInlayHintsPass`），不是我方那条 legacy `InlayHintsPass` |
| 选项读取 | 内部 `InlayTreeSink.whenOptionEnabled("sql.show.column.names.in.insert.values", …)` |
| 预览样例 | 同 jar `inlayProviders/sql.column.names.inlay.hints/*.sql`：`INSERT INTO foo VALUES (/*<# id #>*/1, …)`——写法与本插件同一形态（值前挂列名） |
| 版本面 | 263 实测存在；243（编译基线）/253/262 是否有、以及当年注册在 `codeInsight.inlayProvider` 还是 `codeInsight.declarativeInlayProvider`，**未验证** → P-2 |

### 2.4 平台机制：为什么"逐位判定"这条路走不通

要让位得体面，最直觉的做法是"投提示前先看这个 offset 上原生有没有画"。三条实测证据否掉它：

1. `lib/intellij.platform.lang.impl.jar!/com/intellij/codeInsight/hints/InlayHintsSinkImpl.class`：sink 不直接写编辑器，而是写进 `HintsBuffer`（`addInlineElement` → `HintsBuffer.getInlineHints(): ConcurrentIntObjectMap`，收口 `complete()`）。
2. 同 jar `CollectorWithSettings.class`：**每个 collector 自带一个 sink**，`collectHints` 只转发 `collector.collect`，`collectTraversing` 结束时 `sink.complete()` 出一份 **per-provider** 的 buffer。
3. 同 jar `InlayHintsPass$Companion.class` 的 `applyCollected(HintsBuffer, PsiElement, Editor, …)`：真正写 `editor.inlayModel.addInlineElement` 发生在**收集全部结束之后**，且它自己也是按 offset 去 `inlayModel.getInlineElementsInRange(...)` 取"已有 inlay"。

⇒ 收集期间各 provider **互相看不见**，我方 collector 里读到的 InlayModel 只可能是上一轮 pass 的残影；再叠上平台把分片元素交给 `JobLauncher.invokeConcurrentlyUnderProgress` 并发跑（本仓 `InsertColumnInlayGateTest` 头注释已就 243 源码实证过这一点），"先看对方画没画"就是一个必输的竞态。**故让位判据只能取能力级，不能取位置级。**

### 2.5 根因

| 编号 | 根因 | 确定度 |
|---|---|---|
| **R1** | 能力重叠：IDEA 原生 `sql.column.names.inlay.hints` 默认开启，做的正是"INSERT VALUES 值前挂列名"，与本插件 O-44b 逐字同一件事——我方当初照 DBX `insertValueHints.ts` 实现时，没检查"平台自己已经有一份" | 事实（§2.3） |
| **R2** | 我方缺让位门：provider 只有作用域/语句类型/可判三道闸，没有任何"原生在位与否"的判据（`PreviewInsertColumnInlayProvider.kt:163-171`） | 事实（§2.2） |
| **R3** | 用户不可自救：本 provider 有意做成"零设置项"（`:153-156`），平台 inlay 开关又是语言级的——关掉 SQL inlay 会连原生一起关，等于把两侧都关掉 | 事实（§2.2） |
| **R4** | 截图里"未绑定数据源"与"原生不该画出列名"存在张力：若原生那侧其实没渲染，则重复另有成因（我方同一 offset 重复投递 / 预览文本自带列名注释 / 灰度期两版 provider 并存） | **待证**（P-1） |

## 三、目标与非目标

**目标**

1. 原生 SQL 列名 inlay 在位且开启时，本插件的 INSERT 列名提示**整体不渲染**（不产生任何 presentation）；
2. 原生不可用时（Community、DatabaseTools 未装/停用、版本无该 provider、探测链失败）本插件**照旧提示**——不因让位把功能丢掉；
3. 让位判据是**纯函数 + 测试缝注入**的可反向验证形态（对齐 `MyBatisCompetitorGate` 的既有纪律：判据取插件/EP 在位，不做"对方是否真画了"的运行期推断）。

**非目标**

- 不做位置级（offset 级）去重——机制上不可行（§2.4）；
- 不改 SQL 文本/渲染/执行/复制/分享；不动参数面板；
- 不为这一点新增平台 EP 注册，也不改 `plugin.xml` 现有注册行；
- 不改原生那侧的默认值（用户自己的 IDE 设置归用户）。

## 四、方案

### 4.1 决策点（**待用户拍板**）

| 决策点 | 推荐 | 备选与理由 |
|---|---|---|
| **D1 让位粒度** | **A：能力级整体让位**（原生在位+开启 ⇒ 我方零提示） | B：位置级 —— §2.4 实证不可行，直接否。<br>C：启发式（我方 schema 缓存里查得到该表列清单才让位）——false-negative 有真实风险：缓存未预热时"我方查不到"≠"原生画不出"，结果就是重复复发，正是本缺陷本身；列为不推荐 |
| **D2 原生被用户关掉时怎么办** | **A：同样让位**（尊重"用户明确关掉了列名提示"的意图） | B：那我方接上（用户原话的字面执行）——风险：用户关的正是这个提示，B 等于把设置反着执行 |
| **D3 是否加插件设置项** | **A：v1 不加**（继续零设置项；D2-A 已经能表达"跟随原生"） | B：加「预览 INSERT 列名提示：跟随 IDE 原生 / 始终显示 / 关闭」——为极少数"原生画得丑但我还想要插件这版"的场景，代价是设置页 + i18n 双语键 + 面板守卫同步，改动面翻倍 |

> D1 的 C 与 D3 的 B 可以组合成"v2 精细化"路线；本计划按 D1-A + D2-A + D3-A 出步骤，另两条在 §九 留给用户。

### 4.2 检测链（三段，逐段可降级）

新建 `ui/inlay/NativeColumnInlayYieldGate.kt`（object），三段判定：

**G1 在位**——枚举两代 EP，命中任一路即算在位：

- 声明式：`codeInsight.declarativeInlayProvider`（原生在 263 走这条）。bean 类为 `com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean`，可读 `providerId` / `implementationClass` / `language`；
- legacy：`codeInsight.inlayProvider`（243~262 若原生当年按旧 EP 注册，走这条；本插件自己也在这条链上，故要按实现类/账号排除自身）。
- 命中判据取**双锚点候选链**：`providerId == "sql.column.names.inlay.hints"`（DatabaseTools 自己声明的契约）**或**实现类名以 `SqlColumnNamesInlayHintsProvider` 结尾（版本漂移兜底）。声明式 EP 的属性在不同平台版本形态不同，枚举统一走 `ExtensionPointName.create<Any>(…)` 擦除泛型 + 反射读 bean 属性（仓内先例：`acp/AcpTransportRegistrar.kt:111`、`mcp/McpGateFilterEpBridge.kt:69`）。

**G2 开启**——两个来源，任一可判即用：

- 声明式设置：`com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings`（`lib/intellij.platform.lang.jar`）提供 `isProviderEnabled(providerId): Boolean?` 与 `isOptionEnabled(providerId, optionId): Boolean?`（本次常量池实测；`RequiresReadLock`，须在 read action 内调）；
- legacy 设置：`InlayHintsSettings.Companion.instance` 的 `getDisabledHintProviderIds()` / `getDisabledLanguages()` / `hintsEnabled(Language)`。
- 两者都返回 null（拿不到/形态变了）时按**默认开**处理（native `isEnabledByDefault=true`）。

**G3 结论**——`shouldYield() = G1 && (G2 非 false)`；`D2-A` 让"G2=false（用户关了）"也落在让位上，即 `shouldYield = G1`（G2 只用于日志与诊断，不参与放行）。

### 4.3 闸门落点

只动一处：`PreviewInsertColumnInlayProvider.getCollectorFor` 在三道既有闸门**之前**加第 0 道——

```kotlin
if (NativeColumnInlayYieldGate.shouldYield()) return null
```

返回 `null` 即"平台侧无 collector"= 零 inlay，与既有闸门同一语义，不碰收集器内部逻辑。

（可选 T-NCY-05，见 §五）原生把 `in.insert.values` 与 `in.select` 做成两个独立选项，若 D2 改判为"逐分支让位"，则让 `Hint` 带上 `branch: VALUES | PROJECTION`，由闸门按分支选项分别决定——成本很小，但只在 D2 从 A 改成 B 时才有意义。

### 4.4 缓存、线程与日志

- 结论在 object 内 `@Volatile` 懒缓存（插件/EP 启停需重启 IDE，对齐 `MyBatisCompetitorGate.kt:43-71`）；`DeclarativeInlayHintsSettings` 值可变，故只缓存 G1（在位），G2 每次现读（一次 map 查询，成本可忽略）。
- 任何反射/形态失败按"不在位"处理并只 WARN 一次（同上 `:46-48`、`:125-129`）——**方向是保留功能**（不 yield），不因探测失败把提示丢掉。
- 测试缝：`internal var probe: (() -> Boolean)?` + `resetForTest()`（同一既有范式）。既有 4 个 inlay 测试类必须在 `setUp` 里先注入 `probe = { false }` 并清槽，否则测试平台是 **Ultimate 2024.3.7.1**（`build.gradle.kts:157`）——DatabaseTools 在位，闸门会把它们的用例整体短路成"零提示"而假红。

## 五、实施步骤

| 编号 | 内容 | 落点 | 验证 |
|---|---|---|---|
| T-NCY-01 | 新建 `NativeColumnInlayYieldGate`：双 EP 枚举 + providerId/类名候选链 + 反射读属性 + 懒缓存 + WARN 一次 + `probe`/`resetForTest` | `ui/inlay/NativeColumnInlayYieldGate.kt`（新） | 纯函数真值表单测（在位×开启四态）+ 探测失败降级用例 |
| T-NCY-02 | 接闸门：`getCollectorFor` 第 0 道早退 | `PreviewInsertColumnInlayProvider.kt:163-171` | 注入 `probe={true}` ⇒ 断言 `getCollectorFor == null`；`{false}` ⇒ 非 null |
| T-NCY-03 | 既有 4 类补 `probe={false}` 与 `resetForTest()` | `ui/inlay/*Test.kt` | 4 类全绿（改动前基线一致） |
| T-NCY-04 | 单一事实源落地：把「原生 providerId / 选项 id / EP 名」写成 `NativeColumnInlayYieldGate` 内的常量表 + KDoc 说明来源（DatabaseTools `plugin.xml:2533-2538`、263 实测），便于后续跟版本 | 同上 | KDoc 复核；常量被测试直接引用（断链即红） |
| T-NCY-05（可选，随 D2） | 逐分支让位（`Hint.branch`） | `InsertColumnInlayPlanner.kt` + 闸门 | 分支用例各 2 条 |
| T-NCY-06 | 文档：`docs/架构设计/` 归属文档补一句（inlay 面已含让位门）+ `CHANGELOG.md [Unreleased]` 一句（提交即回填） | 文档 | 人工复核 |
| T-NCY-07 | 真机 A/B：用户在 263 上验 A1~A4（§七） | runIde 沙盒 | 见 §七 |

## 六、风险与回退

| 风险 | 处置 |
|---|---|
| 原生 provider 对方改了 providerId/类名 ⇒ 探测不到 ⇒ 重复复发（退回现状，不是新损坏） | 双锚点候选链 + WARN 日志留痕；KDoc 记明版本与出处 |
| 原生在位且开启、但该表在本项目解析不到 ⇒ 两侧都不显示（"原生优先"的代价） | D1-A 的已知残余，登记为 not-run 面；若用户反馈强烈再走 D1-C（需先解决 false-negative） |
| 探测在 dumb 模式/索引期跑 | G1 只读 EP 列表，`DumbAware` 可用；G2 的 `RequiresReadLock` 包 read action |
| 测试平台 DatabaseTools 在位 ⇒ 既有用例被短路 | §4.4 的 `probe` 注入是硬要求，先改测试再改生产，避免"绿了但什么都没测" |
| 回退 | 摘掉 `getCollectorFor` 那一行即回到现状；反向验证以"摘掉必现重复"为判据 |

## 七、验证矩阵

**定向（实施者自跑，不跑全量）**

- `NativeColumnInlayYieldGateTest`（新）：在位/不在位 × 开启/关闭/未知 六格真值表；反射失败降级；缓存不随重复调用漂移。
- `PreviewInsertColumnInlayProviderTest`：现有用例保留（`probe={false}`）+ 新增"让位时 `getCollectorFor` 返回 null"。
- `InsertColumnInlayGateTest` / `InsertColumnInlayPanelWiringTest` / `InsertColumnInlayPlannerTest`：`probe={false}` 后与改动前读数一致。

**反向验证（成对读数）**

1. 摘掉 `getCollectorFor` 的第 0 道闸 → `probe={true}` 的"返回 null"用例必红；
2. 把 G1 判据改成"永远 false" → 同上必红；
3. 把 `probe` 影响面挪到收集器内部（而非闸门）→ 应有至少一条用例红（证明闸门位置唯一）。

**真机（263，cust-big-demo / CustAddressMapper.xml 的 insert）**

- A1 原生开、我方让位：`VALUES` 每个值前**只有一枚**列名胶囊；
- A2 平台设置里关掉原生 `INSERT … VALUES` 列名 → 用户按 D2 的定案观察我方是否出现（D2-A：仍不出现；D2-B：出现且只一枚）；
- A3 无 DatabaseTools 的环境（Community 或停用 DatabaseTools）→ 我方提示照旧；
- A4 用户自己的 `.sql` 文件开 INSERT → 两侧都不出现（作用域双锚点未被让位逻辑打穿）。

**P-1 分诊（必须先做，否则后面全是空转）**

见 §八 第一条。

## 八、待验证前提

- **P-1（先证伪）**：截图里第二枚胶囊确实来自原生。判据：263 上「Settings → Editor → Inlay Hints → SQL」取消 `INSERT … VALUES` 列名，重开预览——重复的那一枚应消失。若不消失，则 R4 成立（重复另有成因：我方重复投递 / 预览文本自带列名），本计划改走"我方去重"分支，D1~D3 全部重排。
- **P-2**：243（编译基线）/253/262 上原生 provider 是否存在、注册在哪个 EP。本机只有 263 实测；缺版本的结论写"未验证"，不得拿 263 读数外推。
- **P-3**：`DeclarativeInlayHintsSettings.isOptionEnabled(providerId, optionId)` 的 `providerId` 口径是 `sql.column.names.inlay.hints`（EP 的 providerId）还是序列化后的复合 id（源码里有 `getSerializedId(providerId, optionId)`）。先做一次运行时探针把实际读数打出来再定。
- **P-4**：声明式 EP 的 bean 在 243 是否已是 `InlayHintsProviderExtensionBean` 形态；不是则 G1 只能靠 `implementationClass` 字符串匹配。
- **P-5**：测试平台（IU 2024.3.7.1）里 DatabaseTools 的 provider 是否真的被 EP 枚举到——决定"既有用例是否必须注入 probe"，属实施第一步的实测项。
- **P-6**：`isOptionEnabled` 标了 `@RequiresReadLock`；在 inlay 收集线程（BGT，非 EDT）直接调用是否触发断言，需实测后决定是否包 `ReadAction`。

## 九、开放决策（待用户拍板）

1. **D1 让位粒度**：A 能力级整体让位（推荐）/ C 启发式（不推荐，false-negative 会复发重复）。
2. **D2 原生被用户关掉时**：A 同样让位（推荐）/ B 我方接上。
3. **D3 是否加插件设置项**：A v1 不加（推荐）/ B 加三态开关。
4. **残余可接受度**：D1-A 下"原生在位但表解析不到 ⇒ 两侧都不显示"，用户是否接受（影响是否要排 v2 的 D1-C）。

---

## 十、实施记录（2026-11-12）

**决策定案**（用户当日「按照你的推荐」）：**D1-A** 能力级整体让位、**D2-A** 原生被用户关掉时本插件同样不出、**D3-A** v1 不加插件设置项。

**落点**：工作树 `.worktrees/t-preview-inlay-native-yield`，分支 `t-preview-inlay-native-yield`（自 master `b6e09d78e` 切出），实施提交 **`5f054e4c8`**。

| 项 | 内容 |
|---|---|
| 新增 | `ui/inlay/NativeColumnInlayYieldGate.kt`——双 EP 枚举（`codeInsight.declarativeInlayProvider` / `codeInsight.inlayProvider`）、`providerId` 或实现类简单名双锚点、bean 属性全反射读（不绑两代 bean 类）、进程级懒缓存、`probe` 测试缝、探测失败只 WARN 一次并降级为不在位 |
| 接入 | `PreviewInsertColumnInlayProvider.getCollectorFor` 第 0 道闸：`if (NativeColumnInlayYieldGate.shouldYield()) return null`；provider KDoc 的「三道闸门」改为四道 |
| 测试 | 新增 `NativeColumnInlayYieldGateTest`（10 例：锚点真值表 4 / 探针三形态 3 / 缓存 2 / 真探测链降级 1）；`PreviewInsertColumnInlayProviderTest` 补 2 例（在位 ⇒ 零提示、不在位 ⇒ 正向契约不变）；三个平台测试类（`InsertColumnInlayGateTest` / `InsertColumnInlayPanelWiringTest` / `PreviewInsertColumnInlayProviderTest`）在 setUp 钉死 `probe={false}`——测试平台是 IU 2024.3.7.1，DatabaseTools 在位时这一道门会把用例整体短路成假红 |
| 文档 | `docs/架构设计/01-项目架构总览.md`「预览编辑器专属 EP」行补让位门与机制依据；`CHANGELOG.md [Unreleased] → Fixed` 一句 |

**定向读数**：`./gradlew test --tests "cn.song.mybatisplus.visualizer.ui.inlay.*"` → **68 tests / 0 failures / 0 errors / 0 skipped**（Gate 6 / Wiring 3 / Planner 32 / YieldGate 10 / Provider 17）。

**反向验证**（三轮，均先提交再改，改完 `git checkout --` 还原；每轮只跑对应类）：

| 轮 | 改动 | 读数 |
|---|---|---|
| R1 | 摘掉 `getCollectorFor` 的让位行 | Provider 类 17 例 **恰 1 红**（`test原生列名inlay在位时整体让位_零提示`），其余 16 绿 |
| R2 | 摘掉 `matchesAnchor` 的 providerId 锚点分支 | YieldGate 类 10 例 **恰 1 红**（`testAnchor_按ProviderId命中`），其余 9 绿 |
| R3 | `getOrDefault(false)` 反转成 `true`（探测失败视为在位） | YieldGate 类 10 例 **恰 1 红**（`testProbe_探测异常降级为不在位`），其余 9 绿 |

R3 的附带读数：`test真实探测链_无平台实例时降级为不在位且不抛` 在反转后仍为绿 ⇒ 纯 JUnit 环境下 `extensionList` 不抛、直接探空，即"无平台实例"走的是正常空结果路径，`getOrDefault` 只兜真正的异常。这条与 §八 P-5 相关，见下。

**前提收窄（实施时推翻/消解）**：

- **P-3 / P-4 / P-6 全部作废**：D2-A 定案「在位即让位、不看开启态」，实现里完全不读原生设置，于是 `DeclarativeInlayHintsSettings.isOptionEnabled` 的 providerId 口径、read-lock 断言、声明式 bean 字段形态三条都不再需要；bean 属性走反射候选链，**P-4 由设计本身消化**（不静态引用任何 bean 类，243 上形态不同也照常降级）。
- **P-5 未单独实测**：三个平台测试类无条件注入 `probe={false}`，故"测试平台是否枚举到 DatabaseTools 的 provider"不影响读数；该推断没有取到实测读数，按「未验证」登记（不影响结论）。

**not run（留待真机/合流）**：

- **P-1 分诊**：用户在装了 DatabaseTools 的 263 上关掉 `INSERT … VALUES` 列名后重开预览，重复的那一枚应消失——这一条同时是本次缺陷的 A/B 判据，单测无法替代；
- **A1~A4 真机验收**（§七）与 **P-2 的 243/253/262 版本面**；
- **全量门禁**（`testShards`）：本任务属纯显示层，定向 68 例覆盖了新增判定与既有契约；全量按三档分层未跑，合流前由用户决定是否补。

---

## 附：取证命令（复现本次结论）

```bash
# 原生注册（263）
unzip -p "/c/Users/USER/AppData/Local/Programs/IntelliJ IDEA/plugins/DatabaseTools/lib/database-plugin.jar" \
  META-INF/plugin.xml | grep -n -A5 "column.names"
# 原生实现类与接口
unzip -l "/c/Users/USER/AppData/Local/Programs/IntelliJ IDEA/plugins/DatabaseTools/lib/modules/intellij.database.sql.core.impl.ex.jar" | grep inlays
# 平台 sink 是 per-collector 缓冲（证明逐位判定不可行）
unzip -l "/c/Users/USER/AppData/Local/Programs/IntelliJ IDEA/lib/intellij.platform.lang.impl.jar" | grep "codeInsight/hints/InlayHintsSinkImpl\|CollectorWithSettings\|InlayHintsPass"
```