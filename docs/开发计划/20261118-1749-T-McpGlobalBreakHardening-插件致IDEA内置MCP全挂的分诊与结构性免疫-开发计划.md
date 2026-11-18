# T-McpGlobalBreakHardening：插件致 IDEA 内置 MCP Server 全挂的分诊与结构性免疫

> 立项日期：2026-11-18
> 报障来源：用户书面反馈「插件导致 IDEA 的所有 MCP 都无法正常使用」，附外部审计条目 A5（`/stream` 恒 500、`CollectionsKt.addAll(collection, null)`、触发版本标注 SQL Visualizer **2026.10.28**）
> 严重度：**P0**（影响面跨插件：本插件 11 个工具 + 平台内置 MCP Server 全部客户端 + 其他插件注册的工具）
> 相关计划：[20261029-1600-T-Mcp261FilterContract](20261029-1600-T-Mcp261FilterContract-261过滤器契约不兼容致内置MCP全挂-开发计划.md)（同一条缺陷的首次定位与修复）、[20260923-1316-T-McpGateDynamicFilter](20260923-1316-T-McpGateDynamicFilter-MCP门控动态过滤-开发计划.md)（O-68 首次接入该 EP）

---

## 一、先给结论：这次报障落在哪一档

这条缺陷**不是新缺陷**。它与 2026-10-29 的 P0 报障是同一件事（IDEA 261.25134 + 插件 2026.10.28），修复提交 `7e6471747` 已在 master，并自 `2026.10.29` 起的每一个发行 tag 都携带（本次 `git tag --contains 7e6471747` 实测：`2026.10.29` 至 `2026.11.17` 共 **40 个 tag 全部包含，无遗漏**）。

因此本次用户反馈只有两种可能，**先分诊再决定要不要改代码**：

| 档 | 判据 | 处置 |
|---|---|---|
| **A. 旧版本残留**（最可能） | 用户插件版本 ≤ `2026.10.28` | 升级即修复；不需要改代码。走券06 的升级指引与自查三步 |
| **B. 新版本仍复现** | 插件 ≥ `2026.10.29` 且 `idea.log` 仍出现同一句 NPE | 说明存在未覆盖的契约形态（新缺陷）。按附录 A 抓 IDE build + 完整栈，回到券01 补白名单 |

但**无论哪一档，当前实现仍留着三个能再犯同类事故的结构性口子**（§四 R-1 / R-2 / R-3）。本计划的实质不是重做修复，而是三件事：

1. 把「插件让平台 MCP 全挂」从「这次修好了」变成**结构性不可能**（券01 + 券03）；
2. 补上 261 真机端到端——这是唯一能证实「已修」的手段，至今仍是 `not run`（券02）；
3. 给用户与支持一条**一分钟自查路径**，避免下次报障再靠猜版本（券06）。

---

## 二、根因（三层，逐层都有字节码证据）

### 2.1 第一层：探测只判「类是否存在」

旧 `McpGateFilterEpRegistrar` 只做 `Class.forName("com.intellij.mcpserver.McpToolFilterProvider")`，命中即注册。但同一 EP `com.intellij.mcpServer.mcpToolFilterProvider` 在三条版本线上有**三种不同契约**：

| 平台 | `McpToolFilterProvider` 形态 | 类是否存在 |
|---|---|---|
| 253.31033 / 261.17801 | 无此类、无此 EP | 否 |
| 261.22158 | 形态 C：`getFilters(Implementation): StateFlow<List<McpToolFilter>>` | **是** |
| 261.24374 / 261.25134 | 形态 B：`getFilters(Implementation, McpSessionOptions): List<McpToolFilter>` + `modify`/`apply` | **是** |
| 262.10968 / 263.7307 | 形态 A：`applyFilters(Context, Implementation, McpSessionOptions, McpToolInvocationMode): Unit` | **是** |

「类存在」在 261 上恒为真，于是按 262 写的代理被挂到了一个方法名完全不同的接口上。

### 2.2 第二层：代理按 262 签名分发，261 的 `getFilters` 落兜底返回 null

旧 `InvocationHandler` 只认 `applyFilters` / `getUpdates` / Object 三件套，其余 `else -> null`。261 调 `getFilters` 正好落在 `else`。

### 2.3 第三层：平台拿到 null 直接 `addAll`，且不在 try/catch 内

`McpServerService.getMcpToolsFiltered` 的 Exception table 只覆盖 `getTools()`，过滤器链裸奔：

```
context = new McpToolFilterContext(disallowed = ∅, allowed = 全量)
for (f in allFilters) context = f.modify(context).apply(context)
return context.allowedTools.toList()
```

`allFilters` 的拼装是 `CollectionsKt.addAll(collection, provider.getFilters(...))`——`elements` 是 Kotlin 非空参数，传 null 直接抛：

```
java.lang.NullPointerException: Parameter specified as non-null is null:
  method kotlin.collections.CollectionsKt__MutableCollectionsKt.addAll, parameter elements
    at com.intellij.mcpserver.impl.McpServerService.getMcpToolsFiltered(McpServerService.kt:455)
    at com.intellij.mcpserver.impl.McpServerService.getMcpTools(McpServerService.kt:312)
    at com.intellij.mcpserver.impl.McpSessionHandler.<init>(McpSessionHandler.kt:118)
```

崩溃点在**会话创建阶段**（协议协商还没开始），所以任何客户端、任何参数都是 HTTP 500；`/sse` 虽返回 200 但推不出 `endpoint` 事件。

### 2.4 为什么「关掉插件 MCP 总开关」无效

注册发生在 `ProjectActivity`（`McpGateFilterRegistrar.execute`，`src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt:41-42`），与 `mcpToolsEnabled` 完全无关；代理一旦挂上就一直在链上。用户唯一的自救手段是**禁用整个插件**——这是本次反馈里最伤体验的一点，也是 §四 R-3 要收的口子。

---

## 三、现状核实（2026-11-18 本次实测）

### 3.1 代码现状：修复已在 master

| 位置 | 现状 |
|---|---|
| `McpGateFilterRegistrar.kt:52-70` | `Class.forName` → `detectFilterForm` → `A/B` 注册、`UNKNOWN` 不注册并留方法签名日志 |
| `McpGateFilterEpBridge.kt:76-90` | 代理同时分发 `applyFilters` / `getFilters` / `getUpdates`，未知方法走 `defaultValueFor` |
| `McpGateFilterEpBridge.kt:124-135` | 261 形态 B：`getFilters` 返回一个过滤器代理，解析失败返回**空清单**（非 null） |
| `McpGateFilterEpBridge.kt:219-238` | `defaultValueFor`：Set/Collection/Iterable/Map/Flow/原始类型一律给空值 |
| `McpGateFilterEpBridge.kt:254-262` | `detectFilterForm`：`applyFilters` 在场 → A；`getFilters` 双参且返回 List → B；其余 UNKNOWN |

### 3.2 262 契约本次复核（直接解析 2026.2.1 自带 `plugins/mcpserver/lib/mcpserver.jar`）

本机 gradle transform 缓存里有 `idea-2026.2.1-win`（major 69 的 class 文件，JBR 25 可载）。用 class 文件解析（非 `Class.forName`）取到：

```
McpToolFilterProvider:
  applyFilters (Context, Implementation, McpSessionOptions, McpToolInvocationMode)V   ← 非 suspend、返回 void
  getUpdates   (Implementation, CoroutineScope, McpSessionOptions, McpToolInvocationMode)Flow
McpToolFilterProvider$McpToolFilterContext:
  getOnTools()Set / getRouterOnlyTools()Set / updateState(McpToolState, Function1)V / updateState(Boolean, Boolean, Function1)V
  ← 262 的 context 没有 getAllowedTools / getDisallowedTools / copy
```

两个结论：

1. **形态 A 不是 suspend**——若它是 suspend，代理返回 null 会被 JVM 当成「协程挂起且永不恢复」，工具清单会一直挂着而不是 500。这次实测排除了这条隐患，同时也说明现桥的 `applyFilters -> null` 返回是安全的。
2. **形态 A 与形态 B 的 context 是两个不同的类**（262 的 context 没有 `getAllowedTools`/`copy`）。现桥按方法名分发，B 分支只在平台真调 `getFilters` 时才会走到，262 上不会误碰不存在的反射目标——反射失败也被 `runCatching` 吞成「原样返回 context」。

### 3.3 发行覆盖

```
git tag --contains 7e6471747 --sort=creatordate
→ 2026.10.29, 2026.10.30, 2026.10.31-beta.1, …, 2026.11.17
```

`github/master`、`github/dev`、`github/t-branch-merge-route` 均含该提交。即：**今天从 Marketplace 安装的用户拿到的是已修复版本**。

### 3.4 仍未闭环（承旧计划 §10.5，本次复核仍成立）

| 项 | 状态 |
|---|---|
| 261 真机端到端（装插件 → `/stream` 从 500 变 200） | **未做**。本机沙盒只有 243 / 262 发行版 |
| `verifyMarketplace` 在线门禁 | **未跑**（上次实施时用户指示不做构建校验） |
| 261 运行期 `descriptor.getName()` 读数（P-3） | **未取**。失败会降级为「不隐藏」，不会崩，但门控是否真生效没证据 |

---

## 四、残留风险：为什么「已经修好了」还要立这个计划

### R-1（最高）未来契约漂移会以同样方式再犯

现探测只回答「是不是已知的 A 或 B」，**不回答「有没有我不认识的方法」**。如果 263/264 给接口加一个方法（比如 `getToolAliases(): Map<String,String>`），`applyFilters` 仍在场 → 仍判 A → 仍注册 → 平台调到新方法 → `defaultValueFor` 对对象返回类型给 **null** → 同类 NPE。这不是假想：从 `261.22158`（C）到 `261.24374`（B）再到 `262`（A），同一个接口在三个相邻版本线上就是三套契约。

### R-2 261 真机零端到端

现有 261 结论全部来自「真实 jar 的字节码探测」+「本地假接口行为测试」。这两层能证「判据对、代理不返回 null」，证不了「装进真 IDE、客户端 initialize 能成」。旧计划自己把这条登记成 `not run`，本计划必须补上——否则下次同类报障仍然只能靠字节码推演。

### R-3 总开关关闭仍然注册

`mcpToolsEnabled=false` 的用户照样被挂上过滤代理。本次报障里「关总开关无效、只能卸载插件」就是这条的直接后果。**风险不该由已经关掉功能的用户承担。**

### R-4 失败只 WARN，没有自愈也没有逃生开关

注册失败、反射失败都只落一条 WARN。现场支持拿不到「门控到底生效没有」的确定读数，用户也没有「关掉这个 EP 注册」的开关（只能整插件禁用）。

### R-5 形态 C 静默降级

261.22158 上不注册（MCP 可用，门控不生效），日志只有一条 INFO。这个取舍本身正确，但用户/支持无法从界面上知道「我的工具为什么没被隐藏」。

### R-6 只有一层兜底，没有熔断

平台一旦调到未知方法，现实现只能「返回一个看起来安全的空值」。如果那个方法的返回类型是平台强转的非空对象，空值照样炸。缺一个「发现契约不认识 → 立刻把扩展点摘掉」的止损动作。

---

## 五、目标与非目标

**目标**

1. 任何平台版本、任何契约漂移下，最坏结果都是「门控不生效」，**永不让平台 MCP 会话创建失败**；
2. 261.25134 真机上拿到 `/stream = 200` + 客户端 initialize 成功的端到端证据；
3. 关掉插件 MCP 总开关的用户完全不触碰平台过滤链；
4. 现场支持能靠一条日志/一个界面读数判断「门控生效 / 未识别 / 未注册」；
5. 不引入任何 `com.intellij.mcpserver.*` 类型引用（Marketplace verifier 红线，见旧计划券07）。

**非目标**

- 不改 11 个工具的功能与输出；
- 不恢复 XML 声明形态的 toolset / filter 注册（会重新引入 verifier 未解析类问题）；
- 不动 `update/update.json`（只有用户本人能改）；
- 不在本轮做「MCP 工具页大改版」之类 UI 重构。

---

## 六、方案（按券拆，券01/02 为 P0）

### 券01（P0）契约白名单 + 未知方法熔断

**做法**

1. `detectFilterForm` 升级为「白名单判定」：先枚举 `iface.methods`，把**不属于已知集合**的方法（排除 Object 基方法与编译器合成的 `*$default`）视为契约未知，直接返回 `UNKNOWN`（不注册）。已知集合按形态收窄：

   | 方法 | 形态 A（262/263） | 形态 B（261.24374+） |
   |---|---|---|
   | `applyFilters` | 4 参、返回 `void`、无 `Continuation` 参数 | 不存在 |
   | `getFilters` | 不存在 | 2 参、返回 `List`、无 `Continuation` |
   | `getUpdates` | 返回 `Flow`、无 `Continuation` | 返回 `Flow`、无 `Continuation` |

   任何一条不满足 → `UNKNOWN`。**suspend 化也是 UNKNOWN**——这是新增判据，覆盖「代理返回 null 被当成挂起」这一类（本次已实测 262 非 suspend，但下一条线可能变）。

2. 运行期熔断：代理 handler 命中未知方法时，记一条带完整方法签名的 SEVERE（只记一次），并尝试 `ExtensionPoint.unregisterExtension(provider)` 把自己从链上摘掉，然后返回 `defaultValueFor`。EP 在 262 是 `dynamic="true"`（`mcpserver.jar!/META-INF/plugin.xml:65` 实测），261 同源需在券02 一并确认。

3. 注册日志升级为一行可判读的结论：`O-68: form=A, ideBuild=IU-262.9437.185, registered=true`。

**判据**：造一个「A 形态 + 多一个 `getToolAliases(): Map`」的假接口 → 必须判 `UNKNOWN`、不注册；反向拆掉白名单 → 该用例必须红。

### 券02（P0）261 真机端到端验证

**做法**

1. 用 IntelliJ Platform Gradle Plugin 拉 `IU-261.25134.95`（报障版本）到独立沙盒（临时工作树，不污染主树），从 Marketplace 装 261 线的 `com.intellij.mcpServer` 插件；
2. 装当前发行包，启动 MCP Server，按 §附录命令打 `/stream`，期望 **200**；再用任一 MCP 客户端 initialize，期望拿到工具清单；
3. 对 `261.22158`（形态 C）同验一次：期望 `/stream = 200`，日志出现「未识别契约，跳过注册」；
4. 对 262 / 263 回归：`/stream = 200` + 门控生效（关掉 `sqlv_explain` 后外部清单里消失）；
5. 若 261 发行版/插件因网络或许可拉不到：如实登记 `not run`，改为「发 beta 给报障用户验证」，并把用户侧读数（IDE build + 插件版本 + curl 状态码 + 日志两行）作为验收证据。

**判据（A-1）**：261.25134 上 `/stream` 200、客户端 initialize 成功、11 个工具在列、`idea.log` 无 `addAll` NPE。

### 券03（P1）总开关联动 + 逃生开关

**做法**

1. `mcpToolsEnabled=false` 时**不注册**过滤 EP（若 D-1 选「连 toolset 也不注册」，则两者都不注册）；用户在设置页打开开关时再注册（动态 EP 支持晚注册，已在册；需补一条设置变更监听）；
2. 新增 Registry 逃生开关 `sqlvis.mcp.gateFilterEp`（默认 true）——下次再出同类事故，用户可以「关掉一个开关」而不是卸载插件；
3. 设置页 MCP 区加一行只读状态：`外部会话门控：已生效 / 平台契约未识别（门控不生效，MCP 不受影响）/ 已关闭`。

**判据（A-2）**：`mcpToolsEnabled=false` 启动 → 日志无注册记录、EP 里查不到本插件 provider；打开开关 → 无需重启即生效。

**决策点 D-1**：总开关关闭时是否连 toolset 也跳过注册。推荐「是」——关掉 MCP 的用户应当完全不碰平台链。

### 券04（P1）去过滤器化评估（决策点 D-2）

门控的原始动机是「外部会话清单路径不经过 `McpToolset.isEnabled()`」。但还有一条更强的实现路径：**门控 = 是否注册该 toolset**——不注册的工具根本不会出现在任何清单里，完全不需要过滤 EP，也就没有「代理让平台挂掉」这个风险类别。

**代价**：工具级开关需要在运行期 unregister / re-register toolset（EP 是 `dynamic="true"`，已在册）；需确认动态变更后平台会话清单的刷新时机（filter 路径有 `getUpdates` 推送，toolset 路径依赖下一次 list 调用）。

**处置**：本轮**不改**。券01 是必选补偿；券02 真机通过后，若对 toolset 注销后的清单刷新有实证，再单独立项迁移。若 261 真机验证失败或又出一次同类事故，D-2 直接转「立即迁移」。

### 券05（P1）测试矩阵补齐

1. 真 jar 探测扩到 262 / 263：现有 `McpGateFilter261SignatureTest` 因 class file major 69 无法在 JBR 21 载入而跳过。改为**字节级解析 class 文件**（本次已用 80 行解析器实证可行，见 §附录），不再受 JVM 版本限制；把 262/263 的 `applyFilters` 非 suspend、返回 void 一并钉成断言。
2. 新增用例：未知方法否决、suspend 否决、熔断（unregister 被调用）、总开关关闭不注册、逃生开关关闭不注册。
3. 守卫：源级扫描「注册调用点之前必须先过白名单判定」，防止后人为了「让门控生效」绕过探测直接注册。

**判据**：反向验证三项（拆白名单 / 拆熔断 / 拆总开关门）各恰 1 红，撤回后复绿。

### 券06（P2）用户分诊与文档

1. 报障模板（给未来所有 MCP 类反馈）：IDE build（Help → About 的 `IU-xxx`）、插件版本、`idea.log` 关键字 `addAll|McpToolFilterProvider|O-68`、§附录 curl 状态码。
2. 文档站/发行说明补两条：升级到 ≥ `2026.10.29` 即修复；「MCP 全挂自查三步」（关插件 → 是否恢复；看日志是否有 `O-68: form=`；curl `/stream` 状态码）。
3. 若分诊为 A 档：直接回复升级，不改代码，本计划只剩券01/03/05 的加固。

### 券07（P2）发版

`CHANGELOG.md` `[Unreleased]` 随提交回填；发行说明单篇；`verifyMarketplace` 在线门禁必须跑（旧计划遗留未跑项）；beta → 正式按 release-workflow。

---

## 七、改动清单（文件级）

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterEpBridge.kt` | 白名单判定、suspend 判据、熔断（unregister）、注册日志带 form + build |
| `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt` | 探测升级、总开关联动、Registry 逃生开关、设置变更后补注册 |
| `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetDynamicRegistrar.kt` | 总开关联动（视 D-1） |
| `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt` / `McpConfigurable.kt` | MCP 区只读状态行（券03 第 3 项） |
| `src/test/kotlin/.../mcp/McpGateFilterFormDetectTest.kt` | 白名单 / suspend / 未知方法用例 |
| `src/test/kotlin/.../mcp/McpGateFilterEpBridgeProxyTest.kt` | 熔断用例 |
| `src/test/kotlin/.../mcp/McpGateFilter261SignatureTest.kt` | 字节级解析，扩 262/263 |
| `src/test/kotlin/.../mcp/McpGateFilterRegistrationGuardTest.kt` | 总开关 / 逃生开关 / 白名单先行守卫 |
| `dev-support/mcp261-verify.ps1`（新增） | 261 沙盒起停 + `/stream` 探测 + 日志抓取，供券02 复现 |
| `docs/发行说明/`、`CHANGELOG.md`、`docs/变更记录.md` | 券06/07 落账 |

**红线**：`src/main` 仍然零 `com.intellij.mcpserver.*` 类型引用；所有平台 API 只经 `Class.forName` 字符串 + 反射。

---

## 八、待验证前提（实施者先证伪再动手）

| 编号 | 前提 | 当前证据 |
|---|---|---|
| P-1 | 261.25134 真机 `/stream` 能到 200 | **未验**，本计划券02 的核心 |
| P-2 | 262 的 `applyFilters` 非 suspend、返回 void | 已验（本次解析 2026.2.1 `mcpserver.jar`） |
| P-3 | 261 运行期 `descriptor.getName()` 可读 | **未验**（旧计划 P-3 遗留）；失败只降级为不隐藏 |
| P-4 | 261 的 `mcpToolFilterProvider` EP 也是 `dynamic="true"` | 262 已验；261 需在券02 一并读 `plugin.xml` |
| P-5 | 动态 EP 上 `unregisterExtension` 在 261/262 可用 | **未验**；不可用则熔断退化为「只记日志 + 返回空值」 |
| P-6 | 总开关关闭时平台是否仍会调 provider（若曾注册） | **未验**；券03 依赖它决定「跳过注册」还是「注册后注销」 |
| P-7 | 动态注销 toolset 后外部会话清单的刷新时机 | **未验**；决定 D-2 是否迁移 |

---

## 九、验收判据

| 编号 | 判据 | 手段 |
|---|---|---|
| A-1 | 261.25134 上 `/stream` = 200、initialize 成功、11 工具在列、无 `addAll` NPE | 券02 沙盒 |
| A-2 | `mcpToolsEnabled=false` 时 EP 里无本插件 provider；打开后无需重启生效 | 日志 + 设置页 |
| A-3 | 人为造「多一个未知方法」的接口 → 不注册；造「注册后平台调未知方法」→ 熔断摘除、会话仍可用 | 定向测试 + 反向验证 |
| A-4 | 262 / 263 回归：`/stream` 200 + 门控生效 | 券02 第 4 步 |
| A-5 | 261.22158：`/stream` 200，日志有「未识别契约，跳过注册」 | 券02 第 3 步 |
| A-6 | 定向测试全绿；反向验证三项各恰 1 红 | 子代理报告 |
| A-7 | 全量门禁交 CI；`verifyMarketplace` 在线通过 | 发行前 |

---

## 十、风险与回退

| 风险 | 处置 |
|---|---|
| 白名单过严导致 261/262 上误判 UNKNOWN → 门控静默失效 | 白名单按「已知形态 + 合成方法豁免」实现；A-4 真机回归兜底；最坏结果只是门控不生效 |
| 熔断误摘 → 门控在会话中途消失 | 只在「未知方法被平台实际调用」时触发，属契约已漂移场景，摘除比留着安全；日志留痕 |
| 总开关联动引入注册时序问题（开关打开时工具已缓存） | 动态 EP 晚注册会触发清单重算（已在册），A-2 真机验证 |
| 261 沙盒拉不起来（网络/许可） | 券02 第 5 步降级为用户侧 beta 验证，如实登记 `not run`，不拿字节码读数冒充真机 |
| 262/263 字节级解析误读 | 先用本次已知读数（262 `applyFilters` void）做对照，再扩 263 |

**回退**：Registry `sqlvis.mcp.gateFilterEp=false` 即退回「不注册」；代码层面回退只需还原 `McpGateFilterRegistrar` 的探测分支，桥类可保留。

---

## 十一、实施顺序

1. **先分诊**（券06 第 1 项）：拿到报障用户的插件版本与 `idea.log` 关键字。A 档 → 只做券01/03/05 加固；B 档 → 先按 §附录 抓完整栈，再进券01。
2. 券01 + 券05 同批（实现与守卫一起落，反向验证成对读数）。
3. 券02 独立进行（拉 261 沙盒耗时最长，可并行）。
4. 券03 视 D-1 拍板。
5. 券06/07 收尾。

**not run 口径**：261 沙盒拉不起来、`verifyMarketplace` 未跑，都必须在报告里写明「未跑 + 原因 + 替代证据」，不得用字节码读数冒充真机。

---

## 附录：取证命令

### A. 用户侧分诊（Windows）

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://127.0.0.1:64342/stream `
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" `
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"p","version":"1"}}}'
```

500 = 命中本条；200 = 正常。

日志关键字：`addAll|McpToolFilterProvider|O-68|getMcpToolsFiltered`。

### B. 版本边界（本仓）

```bash
git tag --contains 7e6471747 --sort=creatordate | head -3   # 首个含修复的发行版 = 2026.10.29
git show 7e6471747 --stat                                    # 修复范围
```

### C. 平台契约复核（不依赖 JVM 版本）

262/263 的 mcpserver jar 是 class file major 69，JBR 21 载不进去；改用字节级解析：

```bash
# 1. 找到平台发行版里的 mcpserver.jar（gradle transform 缓存）
ls ~/.gradle/caches/*/transforms/*/transformed/idea-2026.2.1-win/plugins/mcpserver/lib/mcpserver.jar

# 2. 解出目标 class，解析常量池与 method_info 的 name/descriptor
#    本次用 80 行 Python 解析器完成；关键读数：
#    applyFilters (Context, Implementation, SessionOptions, InvocationMode)V   ← 非 suspend
#    getUpdates   (Implementation, CoroutineScope, SessionOptions, InvocationMode)Flow
#    McpToolFilterContext: getOnTools/getRouterOnlyTools/updateState（无 getAllowedTools/copy）
```

### D. EP 声明（262 实证）

```bash
unzip -p <mcpserver.jar> META-INF/plugin.xml | grep -n "mcpToolFilterProvider\|mcpToolset"
# <extensionPoint name="mcpToolFilterProvider" interface="..." dynamic="true" />
# <extensionPoint name="mcpToolset" interface="..." dynamic="true" />
```

---

## 十二、实施记录（2026-11-18）

### 12.1 改动清单

| 文件 | 改动 |
|---|---|
| `mcp/McpGateFilterEpBridge.kt` | 契约白名单 `unknownContractMethods` / `signatureOf` / `isSuspendLike`；`detectFilterForm` 白名单否决 + suspend 判据 + `getUpdates` 返回类型校验；`tripBreaker`（首次摘除 EP 注册）；`unregister` / `isRegistered` / `resetForTest`；`handlerForTest` 测试缝 |
| `mcp/McpGateFilterRegistrar.kt` | `GateRegistrationState`（6 态）；`McpGateRegistrationController`（总开关联动 + `SETTINGS_CHANGE_TOPIC` 订阅 + toolset/filter 两面统一进出）；`McpGateFilterEpRegistrar.sync`（总开关 → 逃生开关 → 契约 → 注册）；注册日志带 form + IDE build |
| `mcp/McpToolsetDynamicRegistrar.kt` | `registeredInstances` 台账 + `unregisterAll()`（总开关关闭时摘除 11 个 toolset 并复位 `registered`） |
| `mcp/McpToolGate.kt` | `isMasterEnabled()`；`isEnabled` 复用 |
| `config/McpConfigurable.kt` | 新增只读状态行「外部会话门控：已生效 / 未识别 / 已关闭 / 无此能力 / 已熔断 / 尚未探测」 |
| `resources/META-INF/plugin.xml` | 声明 Registry 逃生开关 `sqlvis.mcp.gateFilterEp`（默认 true） |
| `resources/messages/*.properties` | 7 个新键（`settings.mcp.banner.gateStatus` + 6 态），中英双份 |
| 测试 | FormDetect +5（白名单/suspend×2/坏 getUpdates/签名清单）、Proxy +3（通知/熔断一次/类型兜底）、RegistrationGuard +2、SignatureTest +3（class 文件解析器 + 262/263 字节级 + 解析器自证） |

### 12.2 定向读数

`mcp.*` + `McpConfigurableGateTest` + `i18n.*` + 三条 convention 守卫：**46 suites / 337 tests / 0 failures / 0 errors / 7 skipped**（skip 全部是 261 取证 jar 缺失的 Assume 跳过）。其中本轮直接相关的三件：

| 类 | 用例数 |
|---|---|
| `McpGateFilterFormDetectTest` | 12 |
| `McpGateFilterEpBridgeProxyTest` | 12 |
| `McpGateFilterRegistrationGuardTest` | 7 |

### 12.3 反向验证（四轮，成对读数）

| 临时拆除 | 读数 | 结论 |
|---|---|---|
| 白名单判定（`unknownContractMethods` 早退） | 12 例 **1 红**（白名单外多一个方法即归 UNKNOWN） | 有效 |
| suspend 判据（`isSuspendLike` 恒 false） | 12 例 **0 红** | 如实登记：suspend 方法必然「参数个数 ≠ 4」或「返回 Object 非 void」，已被现有两条判据覆盖；显式 suspend 判据是冗余防线，**无法用合法 Kotlin 夹具独立观测** |
| 熔断通知（`onUnknown(...)` 调用） | 12 例 **1 红**（未知方法每次都通知熔断层） | 有效 |
| 过滤 EP 的总开关判定 | 首轮 **0 红**（守卫只查「文件内任意位置」出现 `McpToolGate.isMasterEnabled()`，controller 里的那处满足了它）→ 把守卫收窄到 `substringAfter("internal object McpGateFilterEpRegistrar")` 后 **7 例 1 红** | 有效（守卫已加强并留了「20261118 反向验证实测 0 红」的证据注释） |

### 12.4 真机端到端（A-1 / A-2 / A-4 实测）

**（1）262 沙盒（IU-262.9437.185，`runIde -PgifRecord=true` + 录制沙盒许可）**

```
O-68: mcpToolFilterProvider 注册完成（form=A(applyFilters, 262/263), build=IU-262.9437.185）
T-McpDynamicToolset: 11 toolsets registered onto mcpToolset EP
POST /stream  → HTTP 200  {"serverInfo":{"name":"IntelliJ IDEA MCP Server","version":"2026.2.1"}}
tools/list    → HTTP 200，68 个工具，11 个 sqlv_* 全在
idea.log      → addAll 出现 0 次
```

**（2）261 真机（IU-261.25134.95 —— 用户报障版本，`ideaIU-2026.1.3.zip` 直装 + JBR 21 + 许可 agent/key）**

```
Loaded custom plugins: SQL Visualizer (2026.11.17)
O-68: mcpToolFilterProvider 注册完成（form=B(getFilters, 261.24374+), build=IU-261.25134.95）
T-McpDynamicToolset: 11 toolsets registered onto mcpToolset EP
POST /stream  → HTTP 200  {"serverInfo":{"name":"IntelliJ IDEA MCP Server","version":"2026.1.3"}}
tools/list    → HTTP 200，65 个工具，11 个 sqlv_* 全在
idea.log      → addAll 出现 0 次
```

**（3）261 总开关关闭（A-2）**：`MpSqlVisualizerSettings.mcpToolsEnabled=false` 重启后

```
（无 O-68 / toolset 注册日志）
POST /stream → HTTP 200
tools/list   → 54 个工具，sqlv_* 0 个
```

**（4）261 Registry 逃生开关（券03）**：`sqlvis.mcp.gateFilterEp=false`

```
T-McpDynamicToolset: 11 toolsets registered ...
O-68: Registry sqlvis.mcp.gateFilterEp=false，跳过 mcpToolFilterProvider 注册（…MCP 服务不受影响）
POST /stream → HTTP 200；tools/list → 65 个工具，11 个 sqlv_*
```

三条路径（正常注册 / 关总开关 / 关逃生开关）在报障版本上全部保持 `/stream = 200`。

### 12.5 与计划的偏差

1. **真机环境取得方式**：262 用录制沙盒（`-PgifRecord=true`，已有 idea.key）；261 不经 gradle（`intellijIdeaUltimate` 在构建脚本里硬编码 243/262，临时加平台覆盖属性要动构建脚本），改为直下 `ideaIU-2026.1.3.zip` + 复用 243 的 JBR 21 + `-javaagent:qwdn-afb.jar` + 许可 key。取证产物留在 `%TEMP%\iu261`（含插件安装目录），可复用。
2. **261 上 `idea.plugins.path` 未被采纳**（启动日志逐行打印的路径里 plugins 仍是 `%APPDATA%\JetBrains\IntelliJIdea2026.1\plugins`，且报目录不存在）：第一轮插件没加载、`/stream` 仅靠平台自身返回 200（54 工具、无 sqlv）——这一步差点得出「插件没生效也 200」的假结论，故把插件改装到默认 plugins 目录后重跑，才拿到 form=B 与 11 工具。**判据：工具清单里必须有 11 个 `sqlv_*`，否则不算验到插件。**
3. **熔断日志用 WARN 不用 SEVERE**：平台测试框架把 `LOG.error` 当测试失败；文案带 `O-68` 供现场 grep。计划里写的「SEVERE」按此调整。
4. **白名单在 262 真实字节码上为空集**：`unknownContractMethods(262 接口) = []`（applyFilters/getUpdates 都在白名单内），A 形态注册路径与改动前一致。

### 12.6 not run

- **263 真机**：本机无 2026.3 安装；263 与 262 同形（A 形态，字节码已核），262 真机通过视为覆盖，不冒充 263 读数。
- **261 运行期切总开关**（设置页 Apply → 动态注销/重注册，不重启）：只验了启动态关闭；运行期切换依赖 `SETTINGS_CHANGE_TOPIC` 订阅，单测覆盖订阅存在，真机 GUI 未走查。
- **`verifyMarketplace` 在线门禁**：发行前必跑（券07）。
- **设置页状态行真机目测**：代码与 bundle 键测试通过，界面未截图验收。

### 12.7 提交记录

| 提交 | 内容 |
|---|---|
| `db2eaaaef` | 券01/05：契约白名单 + 未知方法熔断 + class 文件解析器扩 262/263 + 反向验证 |
| `d00b49801` | 券03/05：总开关联动 + toolset 运行期注销 + Registry 逃生开关 + 设置页状态行 + 守卫收窄 |
| `2657c8bd5` | 计划实施记录 §十二（261/262 批）+ 索引状态 + CHANGELOG `[Unreleased]` |

### 12.8 263 真机补测（2026-11-19，用户令「263 真机也做一下」）——**发现并修复一个真缺陷**

**字节码对照**（解析 `idea-2026.3-win/plugins/mcpserver-plugin/lib/intellij.mcpserver.jar`，IU-263.7307.200，class major 69）：

- **过滤器 EP 与 262 逐字同形**：`applyFilters(...)V`（非 suspend）+ `getUpdates(...)Flow` + `updateState(Boolean, Boolean, Function1)`——券01 的白名单判据原样适用，`unknownContractMethods(263 过滤接口) = ∅`；
- **toolset EP 长了三个钩子**：`McpToolset` 在 262 的 `isEnabled/isExperimental/alwaysIncluded` 之外新增 `isUserConfigurable()` / `displayName()` / `displayDescription()` + `(String)` 重载（三个都有接口默认体）——**这是计划 §12.5 偏差 4 没盖住的另一半**：白名单只护了过滤 EP，toolset EP 是「类可达即注册」，没有契约收窄。

**首跑现象**：`/stream` 200、注册日志正常，但 `tools/list` 92 个工具里**只有 `sqlv_render_sql`**（注册清单第一个）——10 个 toolset 被平台静默丢弃，idea.log 无任何异常。

**修复**：`McpToolsetSupport` 补齐三钩子——`isUserConfigurable()=true`、`displayName()=运行时类 simpleName`（与 262 的 category shortName / T-McpToolNaming 同源）、`displayDescription()=""` / `(String)` 透传 fallback；243/262 无这三个方法，多出来的普通方法无副作用。守卫用例钉「钩子在基类 + 语义」，反向（`isUserConfigurable=false`）**7 例恰 1 红**。

**修复后三态（IU-263.7307.200 真机）**：

| 态 | 注册日志 | tools/list | /stream |
|---|---|---|---|
| 主路径 | form=A + 11 toolset | **102 个，11 个 sqlv_* 全在** | 200 |
| 总开关关 | 无注册日志 | 91 个，sqlv 0 个 | 200 |
| Registry 逃生开关关 | 11 toolset + 「Registry …=false，跳过过滤注册」 | 102 个，11 个 sqlv_* | 200 |

**环境陷阱（差点出假读数）**：首跑 `idea.plugins.path` 落在用户**真实** 2026.3 的 plugins 目录（`idea.plugins.path` 属性不被采纳 + `idea.bat` 硬编码的 `-Didea.paths.selector=IntelliJIdea2026.3` 会压过 vmoptions 里的覆盖），加载的是用户日常安装的**同版本号**插件（2026.11.17）——「/stream 200」不证明本轮代码在 263 上工作。隔离法＝复制 `idea.bat` 为 `idea263e2e.bat` 并把 selector 换成 `IntelliJIdea2026.3E2E`，插件装 `%APPDATA%\JetBrains\IntelliJIdea2026.3E2E\plugins`；**判据＝`Loaded custom plugins` 只有 SQL Visualizer 一条**（首跑该行混着 Air/JRebel 等 50+ 个用户插件，即污染）。

**影响面**：本缺陷随过滤器无关、自 2026.3 起所有版本命中——当前发行的 2026.11.17 在 2026.3 用户侧就只剩 1 个 sqlv 工具，随下一版修复。

### 12.9 263 批定向读数与提交

- `McpToolsetShellFactoryTest` 7 例（新增 263 三钩子守卫 1 例，反向 `isUserConfigurable=false` 恰 1 红）；mcp 全包复跑 **BUILD SUCCESSFUL 0 红**；
- 提交：`c75203c7b`（263 三钩子补齐 + 守卫）；本笔（docs）＝§12.8/12.9 + 索引 + CHANGELOG。
