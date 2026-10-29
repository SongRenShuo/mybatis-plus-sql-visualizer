# T-Mcp261FilterContract：261 过滤器契约不兼容致内置 MCP Server 全挂收口

> 立项日期：2026-10-29
> 报障来源：用户书面报告（IDEA 2026.1.3 / IU-261.25134.95，插件 2026.10.28）
> 严重度：**P0**。不是插件功能退化，而是**插件让整个 IDEA 内置 MCP Server 对所有客户端不可用**（含其他插件注册的工具），且关闭插件 MCP 总开关无效，只能卸载插件。
> 相关既有计划：[20260923-1316-T-McpGateDynamicFilter-MCP门控动态过滤-开发计划.md](20260923-1316-T-McpGateDynamicFilter-MCP门控动态过滤-开发计划.md)（O-68 首次实现，其「该 EP 仅 262+ 存在」的前提被本报告证伪一半：253 无、**261 有但签名不同**）

---

## 一、报障与复现

### 1.1 现象

| 项 | 值 |
|---|---|
| IDE | IntelliJ IDEA 2026.1.3，build **IU-261.25134.95**（Ultimate） |
| 插件 | 2026.10.28（Marketplace 最新） |
| 端点 | 平台内置 MCP Server `127.0.0.1:64342` |

- `POST /stream`：**每次** HTTP 500
- `/sse`：200 但 3 秒内不推 `endpoint` 事件，同样建不起会话
- `/mcp`、`/`：404（说明 `/stream` 路径本身是对的）

`idea.log` 同一 NPE 累积 21 次：

```
SEVERE - io.ktor.server.Application - Unhandled: POST - /stream
java.lang.NullPointerException: Parameter specified as non-null is null:
  method kotlin.collections.CollectionsKt__MutableCollectionsKt.addAll, parameter elements
    at kotlin.collections.CollectionsKt__MutableCollectionsKt.addAll(MutableCollections.kt)
    at com.intellij.mcpserver.impl.McpServerService.getMcpToolsFiltered$intellij_mcpserver(McpServerService.kt:455)
    at com.intellij.mcpserver.impl.McpServerService.getMcpTools(McpServerService.kt:312)
    at com.intellij.mcpserver.impl.McpSessionHandler.<init>(McpSessionHandler.kt:118)
    at com.intellij.mcpserver.impl.McpServerService$startServer$1$3.invokeSuspend(McpServerService.kt:291)
    at ...createStreamableSession(mcp.sdk.util.kt:238)
    at ...handleStreamablePost(mcp.sdk.util.kt:177)
```

崩溃点在 `McpSessionHandler.<init>`，即**会话创建阶段**，协议协商尚未开始 → 与客户端参数无关。

### 1.2 关总开关无效

插件设置页「MCP 工具 → 总开关」关闭（`mp-sql-visualizer.xml` 中 `mcpToolsEnabled=false` 已确认落盘）并重启 IDE，现象不变。因为注册发生在 `ProjectActivity`，不受该开关约束（见 §2.1）。

---

## 二、根因

### 2.1 R1：探测只判「类是否存在」，不判方法契约

`McpGateFilterRegistrar.kt:43`：

```kotlin
val iface = runCatching { Class.forName("com.intellij.mcpserver.McpToolFilterProvider") }.getOrNull()
    ?: return                       // 只查类在不在
```

`McpGateFilterRegistrar.kt:25-33` 的 `ProjectActivity` 无条件调 `McpGateFilterEpRegistrar.registerOnce()`，与 `mcpToolsEnabled` 无关。

261 上这个类**存在**（且 EP 名相同，见 §2.5），探测通过 → 注册照常发生。`idea.log` 实证（报告提供，重启后 30 秒出现 NPE）：

```
15:21:03,981 INFO - McpGateFilterEpBridge    - O-68: sqlv gate filter registered onto mcpToolFilterProvider EP (proxy form)
15:21:03,981 INFO - McpGateFilterEpRegistrar - O-68: sqlv gate filter registered onto mcpToolFilterProvider EP
```

### 2.2 R2：代理按 262 签名分发，261 的 `getFilters` 落 `else → null`

`McpGateFilterEpBridge.kt:60-72`：

```kotlin
private fun providerHandler() = InvocationHandler { proxy, method, args ->
    when (method.name) {
        "applyFilters" -> { applyFilters(args?.firstOrNull()); null }
        "getUpdates"   -> emptyFlow<Any>()
        "toString" / "hashCode" / "equals" -> ...
        else -> null            // ← 261 的 getFilters 走这里，返回 null
    }
}
```

### 2.3 R3：平台拿到 null 直接 `addAll`，且不在 try/catch 内

261.25134.203 `McpServerService.getMcpToolsFiltered$intellij_mcpserver` 字节码（本次实测，见 §附录命令）：

```
527: invokeinterface  McpToolFilterProvider.getFilters(Implementation, McpSessionOptions) -> List
532: checkcast        java/lang/Iterable
541: invokestatic     kotlin/collections/CollectionsKt.addAll(Collection, Iterable)
```

对应源码形如 `filters.addAll(provider.getFilters(clientInfo, sessionOptions))`。
`Exception table` 只有一条，覆盖偏移 `88-98`（`getTools()` 调用），**过滤链不在保护范围内** → null → NPE 直接冒到 Ktor → HTTP 500。

### 2.4 261 的过滤器契约（javap 权威，261.25134.203 `mcpserver.jar`）

```java
public interface McpToolFilterProvider {
  List<McpToolFilter> getFilters(Implementation, McpServerService$McpSessionOptions);
  Flow<Unit>          getUpdates(Implementation, CoroutineScope, McpServerService$McpSessionOptions);
}
public interface McpToolFilter { McpToolFilterModification modify(McpToolFilterContext); }
public interface McpToolFilterModification { McpToolFilterContext apply(McpToolFilterContext); }

public final class McpToolFilterContext {   // data class
  Set<McpTool> disallowedTools; Set<McpTool> allowedTools;
  Set<McpTool> getDisallowedTools(); Set<McpTool> getAllowedTools();
  McpToolFilterContext copy(Set<McpTool>, Set<McpTool>);
}
```

**261 没有 `applyFilters`，也没有 `updateState`/`McpToolState`**（262/263 才有）。

平台消费链（`getMcpToolsFiltered` 字节码 556-639）：

```
context = new McpToolFilterContext(emptySet() /*disallowed*/, tools.toSet() /*allowed*/)
for (f in allFilters) context = f.modify(context).apply(context)
return context.allowedTools.toList()
```

平台自带 `DisallowMcpTools.apply` 的语义（字节码 0-38）＝ `context.copy(disallowed + toolList, allowed - toolList)`。
`modify(context)` 的返回值**直接 `.apply()`，无判空**（609-618）——即 form B 路径上任何一次返回 null 都是同一颗炸弹。

### 2.5 261 内部还变过两次形态（本次实测，影响兼容矩阵与探测设计）

| 平台版本 | `McpToolFilterProvider` | EP `com.intellij.mcpServer.mcpToolFilterProvider` | 现状 |
|---|---|---|---|
| 253.31033（2025.3） | **无此类** | 无 | 探测失败 → 不注册 → 正常 |
| 261.17801.91 | **无此类** | 无 | 同上 |
| 261.22158.220 | 有，形态 C：`getFilters(Implementation): StateFlow<List<McpToolFilter>>` | **有**（同名） | 探测通过 → 代理返回 null → 调用点 `getFilters(null).getValue()` 在 null 上 invokeinterface → 同样失败 |
| 261.24374.39 | 有，形态 B（同 25134；调用点字节码偏移逐字相同） | 有 | 同上 |
| **261.25134.203**（用户所在） | 有，形态 B | 有 | 同上 |
| 262.10968.63 / .92 | 有，形态 A：`applyFilters(Context, Implementation, SessionOptions, InvocationMode)` | 有 | 正常（现实现按此写） |
| 263.7307.50 | 形态 A（同 262） | 有 | 正常 |

即：**受影响的是 261 线自 261.22158 起**；262/263 正确；253 及更早 261 安全。这也解释了为什么 2026-09-23 的「253 无此 EP」与「262 有」两次实测都通过，却漏掉了中间的 261。

### 2.6 完整链条

```
ProjectActivity 启动
  → Class.forName("com.intellij.mcpserver.McpToolFilterProvider") 成功（261 也有）
  → 注册全反射代理到 EP
  → 客户端 initialize → McpSessionHandler.<init> → getMcpToolsFiltered
  → provider.getFilters(...) 命中代理 else 分支 → null
  → CollectionsKt.addAll(collection, null)
  → NullPointerException（不在 try/catch）
  → Ktor 返回 HTTP 500 → 任何客户端都拿不到工具
```

### 2.7 影响面（这是最严重的地方）

| 维度 | 影响 |
|---|---|
| 本插件 11 个 `sqlv_*` 工具 | 完全不可用 |
| IDEA 内置 MCP Server | **对所有外部客户端完全不可用**（Claude Code、Codex、Cursor、VS Code 等） |
| 其他插件注册的工具 | 一并不可用（Database 工具集、调试器 MCP、Qodana、Jupyter 等） |
| 绕过手段 | 无。关插件 MCP 总开关无效；只能禁用整个插件 |

这是「一个插件的可选功能让平台公共能力整体失效」，按 P0 处理。

---

## 三、目标与非目标

**目标**

1. 任何平台版本线上，本插件的过滤器代理**绝不向平台返回 null、绝不向平台抛异常**（结构性安全网，与版本无关）。
2. 261 线（B 形态）恢复门控语义：设置页关闭的工具不出现在外部会话清单里。
3. 未知形态（未来 264+ 或 261.22158 的 C 形态）一律**不注册**，日志留痕，MCP 服务保持可用。

**非目标**

- 不追求支持 261.22158 的 C 形态（`StateFlow` 返回值）。该形态已被 B 形态取代，实现收益低、风险面大；按「未知形态不注册」降级处理，仅在计划中登记。
- 不改 `McpToolGate` 决策核语义（总开关 ∧ 工具级禁用清单，`sqlv_` 前缀识别）。
- 不动 toolset 注册链（`McpToolsetDynamicRegistrar`，与本次无关）。

---

## 四、修复方案

### 券01（P0）代理分发结构性安全网

`McpGateFilterEpBridge.kt` 的 `providerHandler()` 不再有 `else -> null`，改为按返回类型兜底：

```kotlin
else -> defaultValueFor(method.returnType)   // 绝不返回 null

private fun defaultValueFor(type: Class<*>): Any? = when {
    type == Void.TYPE || type == Void::class.java -> null          // void 方法合法返回 null
    Set::class.java.isAssignableFrom(type) -> emptySet<Any>()      // 必须先判 Set：emptyList 不满足 Set
    Collection::class.java.isAssignableFrom(type) -> emptyList<Any>()
    Iterable::class.java.isAssignableFrom(type) -> emptyList<Any>()
    Map::class.java.isAssignableFrom(type) -> emptyMap<Any, Any>()
    Flow::class.java.isAssignableFrom(type) -> emptyFlow<Any>()
    type == java.lang.Boolean.TYPE -> false
    type == java.lang.Integer.TYPE -> 0
    type == java.lang.Long.TYPE -> 0L
    type == java.lang.Double.TYPE -> 0.0
    type == java.lang.Float.TYPE -> 0f
    type == java.lang.Short.TYPE -> 0.toShort()
    type == java.lang.Byte.TYPE -> 0.toByte()
    type == java.lang.Character.TYPE -> '\u0000'
    else -> null                                                   // 对象类型：null 是唯一合法占位
}
```

同时对 `applyFilters` / form B 的全部实现体保持 `runCatching`（现 `applyFilters` 已有，`McpGateFilterEpBridge.kt:77-100`）。

> 判据：**「返回集合/Flow 的方法」永不为 null**；void 与对象类型仍可 null（`getTransport` 之类平台语义要求 null 表「不表态」，不在本桥内）。

### 券02（P0）契约感知探测

`McpGateFilterEpRegistrar.registerOnce()` 增加形态判定，替换现在的「类存在即注册」：

```kotlin
internal enum class FilterForm { A_APPLY, B_GET_LIST, UNKNOWN }

internal fun detectForm(iface: Class<*>): FilterForm = when {
    iface.methods.any { it.name == "applyFilters" } -> FilterForm.A_APPLY
    iface.methods.any {
        it.name == "getFilters" && it.parameterCount == 2 &&
            List::class.java.isAssignableFrom(it.returnType)
    } -> FilterForm.B_GET_LIST
    else -> FilterForm.UNKNOWN
}
```

- `A_APPLY` → 现路径（262/263）。
- `B_GET_LIST` → 券03（261.24374+）。
- `UNKNOWN` → **不注册**，`LOG.info` 打印接口全部方法名与返回类型，便于下一条版本线定位。

注册成功/跳过两条都要有 INFO 日志，把「命中哪个形态」写清楚（现在日志只说 registered，无法回溯）。

### 券03（P1）261 B 形态实现

在 `McpGateFilterEpBridge` 内新增分支，全部反射、零平台类型引用（保持 verifier 通过）：

```
getFilters(clientInfo, sessionOptions) -> listOf(filterProxy)
    filterProxy.modify(context)        -> modificationProxy        // 非 null
    modificationProxy.apply(context)   -> context.copy(
                                             disallowed + hidden,
                                             allowed    - hidden)  // hidden = 谓词命中的自家工具
```

实现要点：

1. `hidden` 由 `allowed` 逐个读 `tool.getDescriptor().getName()`（261 `McpTool` 与 `McpToolDescriptor` 均有这两个方法，javap 已验），交给 `McpToolGate.shouldHideFromExternalToolsList` 判定；只碰 `sqlv_` 前缀，外来工具零命中。
2. `copy` 通过 `context.javaClass.getMethod("copy", Set::class.java, Set::class.java)` 反射调用；拿不到 `copy`（形态漂移）时**返回原 context**（等于不隐藏），不抛。
3. 所有分支 `runCatching`，失败降级为「原样返回」：
   - `getFilters` 失败 → `emptyList()`
   - `modify` 失败 → 一个恒等 modification 代理（`apply` 原样返回 context）
   - `apply` 失败 → 原 context
4. `getUpdates` 三参（261）与四参（262）都由同一 `emptyFlow` 分支覆盖（按方法名，不看参数个数）。

> 之所以把「失败降级」写这么死：261 的过滤链**不在 try/catch 内**（§2.3 Exception table），form B 路径上抛一次异常＝重现本次事故。这条是券03 的验收红线。

### 券04（P1）测试与守卫

新增/扩展 `src/test/kotlin/cn/song/mybatisplus/visualizer/mcp/`：

| 用例 | 内容 |
|---|---|
| `未知方法返回集合不得为null` | 假接口含 `List<Any> getFilters(Any)`，handler 返回非 null 空集合 |
| `未知方法返回Flow不得为null` | 假接口含 `Flow<Unit> getUpdates(...)`，返回 `emptyFlow` |
| `原始类型返回不得为null` | 假接口含 `boolean foo()`，返回 `false`（Proxy 对原始类型返回 null 会直接 NPE） |
| `261形态按平台链语义隐藏自家工具` | 假 261 链路：context(disallowed=∅, allowed={sqlv_x, db_tool})，门控关 `sqlv_x` → 结果 allowed 只剩 `db_tool`、disallowed 含 `sqlv_x` |
| `261形态modify不得返回null` | `modify` 返回值非 null 且能 `.apply(context)` |
| `261形态异常不外泄` | context 的 `getAllowedTools` 抛异常 → `apply` 返回原 context，不抛 |
| `探测命中三种形态` | 假接口 A / B / C / 无 → `detectForm` 分别 A / B / UNKNOWN / UNKNOWN；UNKNOWN 时 `registered` 保持 false |
| `源级守卫` | `McpGateFilterEpBridge.kt` 不得出现 `else -> null`，且必须含 `defaultValueFor`；`McpGateFilterRegistrar.kt` 必须含 `detectForm` |

守卫按项目纪律做**反向验证**：临时移除修复分支 → 对应用例必须变红，贴两次读数。

### 券05（P2）文档与发版

1. 本仓：`CHANGELOG.md`、`docs/变更记录/`、`docs/架构设计/02-核心流程详解.md`（若记有 O-68 过滤链描述）。
2. 兄弟文档站仓 `mpsv-bounty-budget-docs`：`features/mcp.md` 与 `en/features/mcp.md` 的「使用前提」补**版本兼容矩阵**（252/253 无 EP → 门控不生效；261.22158+ → 见本版修复说明；262/263 → 正常），并去掉「2025.2 及以上」这种会被读成「全区间可用」的笼统表述。
3. 发版：这是「插件让平台能力全挂」的缺陷，**建议出补丁版**（不等常规节奏）。若需抢时间，券01+02 单独发版即可止血（261 上门控不生效但 MCP 可用），券03 随后跟进。

### 券06（P2）同类反射代理审计（结论已出，本轮不改）

| 位置 | 返回契约 | 结论 |
|---|---|---|
| `AcpTransportRegistrar.transportHandler`（`else -> null`） | `getTransport` 返回可空对象，「null＝不表态」是平台语义 | 不改 |
| `RunConfigurationProcessListener`（handler 恒返回 null） | `ExecutionListener` 方法全 void | 不改 |
| `LogFilterProxyHandler`（`else -> null`） | `ConsoleView.Filter` 唯一方法 `applyFilter` 返回 void | 不改 |

结论入档即可，防下一轮重复怀疑；若后续平台给这几个接口加返回集合/Flow 的方法，按券01 的同一判据处理。

### 券07（P0，与实现同批）verifier 兼容红线与门禁

**背景事实（20260923 Marketplace 审核拒绝）**：`dev-support/verifier-marketplace-baseline.txt` 头注原文——v2026.9.23 因 243×2 + 251×1 compatibility problems 被拒（IU-243：`McpToolsetSupport.audit` 的 K2 `SpillingKt` spill ＋ `Package 'com.intellij.mcpserver'` 三类；IU-251：同族 Package 块）。同族更早记录（`McpGateFilterEpBridge` 头注）是「实现类 `implements` 平台接口 → 未解析类，每线 4 条」。共同点：verifier **分析 jar 内全部类，与片段声明/可达性无关**。修复路径＝全动态化（运行期 ASM 壳 + 全反射代理），此后 243/251/253/262/263 五目标全 Compatible，基线文件清空为零条目。

**本次实现的红线（写死，不可协商）**

1. 新增代码**零 `import com.intellij.mcpserver.*`**。B 形态的 `McpToolFilter` / `McpToolFilterModification` / `McpToolFilterContext` 一律只以**字符串 FQN**出现（`Class.forName("…$McpToolFilter")`）。
2. **不新增 mcpStub 桩类型**。桩源集产物只进编译 classpath、不进 jar，但一旦被 main `import`，main 的字节码里就留下真引用——正是 20260923 被拒的形态。`src/mcpStub/…/McpToolFilterProvider.kt` 只补头注说明 261 签名出处，不加任何 261-only 类型。
3. 新代码只用 JDK / kotlinx 类型：`List`/`Set`/`Collection`/`Map`（JDK）、`Flow`/`emptyFlow`（kotlinx-coroutines，243 平台自带，现实现已在用）。**不引入 `StateFlow`**（券 C 形态不做）。
4. 全部代码留在 `visualizer/mcp/` 包内——`McpScopeSourceScanTest` 已把「mcpserver 引用只许在 mcp 包 + META-INF 只许 mcp-optional.xml」钉死，跨包即红。

**门禁真相（这条必须写进实施记录，否则会重演「门禁全绿却审核被拒」）**

| 任务 | 口径 | 对本类风险是否有效 |
|---|---|---|
| `verifyGate` | `-offline`（不解析可选依赖，**凡引用可选插件 API 的类直接跳过**）+ `-ignored-problems` 机制在役（当前零规则，头注明令禁再按包前缀成族豁免）+ 判定只看退出码 | **结构性失明**。同一 zip 离线跑 243 报 0 问题、在线跑报 5 问题；实测 verifier 带 4 个 compatibility problems 仍退出 0 |
| `verifyMarketplace` | 在线、不套任何豁免、逐份 verdict 判文本必须以 `Compatible` 开头 | **唯一有效门禁**。基线文件现为零条目 → 任何新问题块直接失败 |

因此本计划的验收要求：**发版前必须跑 `verifyMarketplace`**，`verifyGate` 绿不能作为「没有重新引入未解析类」的证据。覆盖面＝`intellijPlatform.pluginVerification.ides` 里声明的目标（当前 243/253/262）；若要把 261 也纳入，需在该处声明 261 发行版（每个目标 +5~7 分钟墙钟，按 release-gate 预算评估，可先做一次性 probe）。

**同时要说清的边界**：verifier **抓不到本次这个 bug**。261 上 `McpToolFilterProvider` 类存在、引用可解析，问题是**运行期契约不同**（方法名不匹配 → 代理返回 null）。verifier 只防「静态引用缺类」，防不了契约漂移——后者只能靠券02 的契约感知探测 + 券04 的行为用例。不要把这两件事混为一谈，也不要用「verifyMarketplace 绿」宣称本缺陷已验。

**守卫（券04 内实现）**

- 新增源级用例：剥注释与字符串字面量后，`McpGateFilterEpBridge.kt` 与 `McpGateFilterRegistrar.kt` 内 `com.intellij.mcpserver` 出现次数为 **0**（现两个文件已满足，加断言防未来回退）。
- 新增源级用例：`src/mcpStub/` 下不得出现 `McpToolFilter` 相关桩类型（防「为 B 形态加桩」这条最诱人的错路）。
- 两条都要做反向验证：临时加一行 `import com.intellij.mcpserver.McpToolFilterProvider` → 必须变红，撤回后变绿。

---

## 五、改动清单（文件级）

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/.../mcp/McpGateFilterEpBridge.kt` | 券01 兜底 + 券03 B 形态分支（`getFilters`/`modify`/`apply` 三段代理） |
| `src/main/kotlin/.../mcp/McpGateFilterRegistrar.kt` | 券02 `detectForm` + 形态日志 + UNKNOWN 不注册 |
| `src/test/kotlin/.../mcp/McpGateFilterEpBridgeProxyTest.kt` | 扩展 5 条行为用例（含 261 链路夹具、异常注入） |
| `src/test/kotlin/.../mcp/McpGateFilterFormDetectTest.kt`（新建） | 探测三分支 + UNKNOWN 不注册 |
| `src/test/kotlin/.../mcp/McpGateFilterRegistrationGuardTest.kt` | 追加源级守卫两条 |
| `src/test/kotlin/.../mcp/McpScopeSourceScanTest.kt` | 券07：桥/注册器零 mcpserver 类型引用、`mcpStub` 无 `McpToolFilter*` 桩（两条反向验证） |
| `src/mcpStub/kotlin/com/intellij/mcpserver/McpToolFilterProvider.kt` | 头注补 261 B 形态签名出处（编译期桩不需要新增类型——B 形态全走反射，**不加桩**，避免 verifier 看到 261-only 类型引用） |
| `docs/架构设计/02-核心流程详解.md` | 若含 O-68 描述，补「三形态矩阵」一句 |
| 兄弟仓 `mpsv-bounty-budget-docs/features/mcp.md`、`en/features/mcp.md` | 版本兼容矩阵 |
| `CHANGELOG.md` / `docs/变更记录/` | 发行条目 |

---

## 六、待验证前提（实施者先证伪再动手）

| 编号 | 前提 | 现状 |
|---|---|---|
| P-1 | 261.24374 与 261.25134 的 `McpToolFilterContext` 都有 `copy(Set, Set)` | **已验**（两版 javap 均列出 `copy` + `getAllowedTools`/`getDisallowedTools`） |
| P-2 | 261 平台对 `modify/apply` 的调用不在 try/catch 内 | **已验**（两版 `Exception table` 均只有 `88-98 → 101 Class java/lang/Exception`，仅覆盖 `getTools()`） |
| P-3 | 261 里自家工具名以 `descriptor.getName()` 为 `sqlv_*`（`fullyQualifiedName` 可能是别的形态） | **未验**。实现时先打一行 debug 对比 `name` 与 `fullyQualifiedName`，确认谓词取哪个字段；若 `name` 不带前缀，改用 FQN 的尾部匹配并补用例 |
| P-4 | 261.22158 的 C 形态（`StateFlow`）确实也会因 null 返回而失败 | **已验**：调用点 `getFilters(null).getValue()`（字节码 524/529），null 上 invokeinterface 即 NPE。不影响本方案（UNKNOWN 即不注册），写进文档矩阵 |
| P-5 | 262.10968.63/.92、263.7307.50 都是 A 形态 | 已验（javap 三份） |
| P-6 | 261 上 `ep.getPoint(null).registerExtension(proxy)` 可用 | 用户日志两行 INFO 已证 |
| P-7 | `Proxy` 对「返回 `Flow` 的未知方法」返回 `emptyFlow` 后，平台不会把它当有更新的提供者反复重算 | 未验。`getUpdates` 现实现已是 `emptyFlow` 且在 262 上无异常，风险低；261 上补一条「会话建立后清单不抖动」的真机观察 |
| P-8 | 券07 的红线守住后，jar 内不出现新的 `com.intellij.mcpserver.*` 类型引用 | 实施后以两条源级守卫 + `verifyMarketplace`（在线）实证；**离线 `verifyGate` 对本项无效**，不可替代 |

---

## 七、测试计划

1. **定向**（实施子代理自跑）：`--tests "cn.song.mybatisplus.visualizer.mcp.*"`；纯逻辑新增用例可走 `testFast`。
2. **反向验证**（三次，均先临时移除修复再复跑）：
   - 移除券01 的 `defaultValueFor` → 集合/Flow/原始类型三条用例变红；
   - 移除券02 的 `detectForm` 拦截（恢复「类存在即注册」）→ UNKNOWN 用例变红；
   - 移除券03 的隐藏分支 → 261 链路用例变红。
3. **全量门禁**：全部子代理完成后单派只读收口代理跑 `testShards`（并行会话登记表按 `docs/MEMORY.md` 约定）。
4. **构建校验（本计划必跑，不可省）**：本次引入新的反射入口（261 B 形态），按项目纪律属「大改动」→ 发版前必须跑 `verifyMarketplace`（在线、无豁免、按 verdict 文本判 `Compatible`）。`verifyGate` 必须跑但不构成证据——它离线且不解析可选依赖，对本类问题结构性失明（详见券07）。若同时改动了 `plugin.xml` / `build.gradle.kts`，`verifyGate` 与 `buildPlugin` 一并跑。
5. **真机**：需要 261 沙盒（当前 runIde 沙盒是 243/262）。若无 261 环境，按「环境受限未验证」如实登记，验收降级为：字节码级（三形态 javap 对拍）+ 假接口行为级 + 262 回归。**禁止拿 262 沙盒读数冒充 261 验证通过。**

---

## 八、验收边界

- 必须达成：261.25134 上 MCP Server 可用（`POST /stream` 返回 200），且关闭的工具不出现在清单；262/263 行为零回归。
- 可降级：261.22158（C 形态）门控不生效但 MCP 可用，日志有「未识别契约，跳过注册」。
- 不在本轮：C 形态支持、其他代理的契约加固。

---

## 九、风险与回退

| 风险 | 处置 |
|---|---|
| 券03 的 B 形态实现抛异常 → 重现事故 | 全部路径 `runCatching` + 降级为原样返回；异常注入用例钉住 |
| `copy` 反射签名在某个 261 构建上不同 | 取不到 `copy` 时返回原 context（等于不隐藏），不抛 |
| 探测判据（方法名 + 参数个数 + 返回类型）过宽/过窄 | 三分支各一条用例；UNKNOWN 必须不注册 |
| 发版节奏 | 券01+02 可单独止血发版，券03 随后 |
| **重新踩 20260923 的审核拒绝**（为 B 形态加桩类型或 `import` 平台过滤接口） | 券07 四条红线 + 两条源级守卫 + 反向验证；发版前 `verifyMarketplace` 在线判定（基线零条目，任何新问题块即失败） |

回退：整体回退到「只在 A 形态注册」即可（删券03 分支），261 上门控失效但服务正常——这是可接受的最低水位。

---

## 附录：取证命令（本次分析所用，可复现）

```bash
# 1) 从 Marketplace 取各版本线的 mcpserver 插件（id=26071）
curl -s "https://plugins.jetbrains.com/api/plugins/26071/updates?size=40"
curl -sL -o mcp261.zip "https://downloads.marketplace.jetbrains.com/files/26071/1084096/mcpserver-261.25134.203.zip"

# 2) 接口形态（261 与 262/263 对照）
javap -p -classpath 261.25134.203/mcpserver/lib/mcpserver.jar com.intellij.mcpserver.McpToolFilterProvider
javap -p -classpath <262 或 263 的 intellij.mcpserver.jar> com.intellij.mcpserver.McpToolFilterProvider

# 3) 平台调用点（addAll 的入参来源 + Exception table）
javap -p -c -classpath 261.25134.203/mcpserver/lib/mcpserver.jar com.intellij.mcpserver.impl.McpServerService \
  | sed -n '/getMcpToolsFiltered\$intellij_mcpserver(com/,/^  public/p'

# 4) 平台自带实现的隐藏语义（DisallowMcpTools.apply）
javap -p -c -classpath 261.25134.203/mcpserver/lib/mcpserver.jar \
  'com.intellij.mcpserver.McpToolFilterProvider$DisallowMcpTools'
```

本地取证产物（本次分析已下载的各版本线 mcpserver 插件与解包目录）在仓外：`F:\code\Java\Gitee\SongRenShuo\_mcp261_probe\`（261.17801.91 / 261.22158.220 / 261.24374.39 / 261.25134.203 / 262.10968.92 / 263.7307.50），不入库。

---

## 十、实施记录（2026-10-29，master 直接实施）

### 10.1 改动清单

| 文件 | 内容 |
|---|---|
| `src/main/kotlin/.../mcp/McpGateFilterEpBridge.kt` | 券01 `defaultValueFor`（Set/Collection/Iterable/Map/Flow/原始类型兜底）+ handler 未知分支改兜底；券03 `filters261`/`buildFilter261`/`buildModification261`/`filterContext261` 三段代理；券02 的 `FilterForm` 枚举与 `detectFilterForm`（放本文件，注册器引用） |
| `src/main/kotlin/.../mcp/McpGateFilterRegistrar.kt` | 券02：探测升级为 `detectFilterForm`，UNKNOWN 提前返回并打一条带方法签名的 INFO；注册成功日志带形态名 |
| `src/test/kotlin/.../mcp/McpGateFilterEpBridgeProxyTest.kt` | 券01/03 行为用例（9 例）：null 兜底三档、261 链路隐藏语义、零触碰、异常降级、空上下文 |
| `src/test/kotlin/.../mcp/McpGateFilterFormDetectTest.kt`（新建） | 券02 形态探测（7 例）：A/B/C/单参 List/双参非集合/无关接口/描述文案 |
| `src/test/kotlin/.../mcp/McpGateFilterRegistrationGuardTest.kt` | 结构守卫两条：探测先于注册且 UNKNOWN 在册、三形态入口与兜底在册 |
| `src/test/kotlin/.../mcp/McpScopeSourceScanTest.kt` | 券07 守卫：mcpStub 不得出现 261-only 过滤器类型桩（`\b` 避免误伤 `McpToolFilterContext`） |
| `CHANGELOG.md` / `docs/变更记录.md` | 券05 发行面记录（`[Unreleased]` 段 + 变更记录 2026-10 首条） |
| 兄弟仓 `mpsv-bounty-budget-docs` 的 `features/mcp.md` / `en/features/mcp.md` | 券05 版本兼容矩阵 + 261 升级提示 |

未改：`plugin.xml` / `mcp-optional.xml` / `build.gradle.kts` / `src/mcpStub/`（券07 红线：B 形态全反射，不加桩、不加 EP 声明）。

### 10.2 定向测试读数

`./gradlew test --tests "cn.song.mybatisplus.visualizer.mcp.*"` → **36 类 / 270 例 / 0 失败 / 0 错误 / 0 跳过**（55s）。

其中本任务相关：`McpGateFilterEpBridgeProxyTest` 9/0、`McpGateFilterFormDetectTest` 7/0、`McpGateFilterRegistrationGuardTest` 5/0、`McpScopeSourceScanTest` 5/0、`McpToolGateTest` 11/0。

中途两例 `NoSuchMethodError: LogSqlEntry.<init>(…)`（`HistorySearchServiceTest` / `McpHistoryIngestTest`）＝合入方言推断三笔后测试类未重编译的陈旧产物（主代码 `LogSqlEntry` 构造已变），`./gradlew compileTestKotlin --rerun-tasks` 后清零复跑全绿——不是本次改动引入。

### 10.3 反向验证（三次，均临时拆掉修复再跑，撤回后复绿）

| 轮次 | 临时拆掉什么 | 读数 |
|---|---|---|
| 1 | 代理 `getFilters` 分支与 `else` 兜底直接返回 `null`（重现 2026-10-29 事故形态） | **2 红**：`test261形态getFilters分发不得返回null`、`test原始类型返回不得为null` |
| 2 | `detectFilterForm` 只按方法名判（去掉参数个数与返回类型约束） | **3 红**：`C形态_StateFlow返回归UNKNOWN`、`单参返回List也归UNKNOWN_防误判`、`双参但返回非集合归UNKNOWN` |
| 3 | `filterContext261` 的隐藏判定改为恒空列表 | **1 红**：`test261过滤器modify不得返回null且apply摘除隐藏工具` |

### 10.4 待验证前提处置

| 编号 | 状态 |
|---|---|
| P-1 / P-2 | 已验（261.24374 与 261.25134 的 `copy(Set,Set)` 与 `Exception table` 逐字一致） |
| P-3 | 实现沿用 262 已验证的 `descriptor.getName()`；261 的 `McpToolDescriptor` 同样有 `getName()`（javap 已验），未使用 `fullyQualifiedName`。**真机运行期仍未取读数** |
| P-4 | 已验（261.22158 调用点 `getFilters(null).getValue()`） |
| P-5 | 已验（262.10968.63/.92、263.7307.50 均为 `applyFilters`） |
| P-6 | 已验（用户日志两行 INFO 证明 261 上 EP 注册成功） |
| P-7 | **未验**（261 真机沙盒缺失） |
| P-8 | 源级守卫绿（`McpScopeSourceScanTest` 既有「核心 API 仅注解或字符串」+ 本次新增 mcpStub 桩禁令）；**在线 `verifyMarketplace` 未跑**（用户指示本轮不做构建校验） |

### 10.5 未闭环

1. **261 真机验收**：本机 runIde 沙盒是 243/262，无 261 发行版；按「环境受限未验证」登记。最小可验路径＝字节码级（§附录命令）+ 假接口行为级（已绿）+ 262 回归（已绿）。
2. **`verifyMarketplace` 在线门禁未跑**：发版前必跑（券07），离线 `verifyGate` 不能替代。
3. **261.22158 的 C 形态**：按设计不注册（门控不生效，MCP 可用），已在文档站矩阵披露。

### 10.6 报障复现（2026-10-29 补，两件）

**（1）语句级复现**：`McpGateFilter261CrashReproTest` 把 261 的两步原样搬进测试——旧形态代理
（只认 `applyFilters`，其余返回 null）＋平台那句 `collected.addAll(provider.getFilters(...))`
（经 Kotlin stdlib 的 `addAll` 真身调用）。复现输出与用户 `idea.log` **逐字一致**：

```
REPRO-261-NPE: java.lang.NullPointerException: Parameter specified as non-null is null:
  method kotlin.collections.CollectionsKt__MutableCollectionsKt.addAll, parameter elements
```

同一句调用换成现行桥的代理 → 不抛、返回空清单（门控退化为空清单，平台会话正常建立）。

**（2）真实 261 字节码探测**：`McpGateFilter261SignatureTest` 把 Marketplace 下载的 mcpserver
插件 jar 装进 **child-first 严格子加载器**（`com.intellij.mcpserver` 包不回退 parent，否则会命中
测试 classpath 里的 mcpStub 桩，把「类不存在」测成「A 形态」——首跑即踩此坑），对平台自己的
字节码跑 `detectFilterForm`：

| 版本（仓外取证产物） | 读数 |
|---|---|
| 261.17801.91 | 类不存在 → 探测失败 → 不注册（安全） |
| 261.22158.220 | `UNKNOWN`（`StateFlow` 形态 C，不注册） |
| 261.24374.39 | `B_GET_LIST` |
| 261.25134.203（用户报障版本） | `B_GET_LIST` |

262 / 263 未纳入：其 mcpserver jar 是 class file **major 69（Java 25）**，测试 JVM 是 243 自带的
JBR 21，`Class.forName` 直接 `UnsupportedClassVersionError`（实测）；A 形态由假接口用例 + §附录
的 javap 取证覆盖。

**仍未做**：真机 261 IDE 端到端（装插件 → 开 MCP Server → `/stream` 从 500 变 200）。本机无 261
发行版，这一条只能等有 261 环境的机器或用户侧升级验证。


