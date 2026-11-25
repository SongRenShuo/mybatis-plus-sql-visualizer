# T-EpAttachBeforeCapture —— 控制台先于捕获创建时 EP 通道缺席、全局 epActive 短路致旧控制台零捕获

- 任务编号：T-EpAttachBeforeCapture
- 立项时间：2026-11-25 14:40
- 触发：用户真机报障（2026-11-25）——「两个应用单独启动都能捕获，但不能并存：第一个单独时有日志，第二个启动后就只显示第二个的」。截图读数：`已挂载 pid=6300,pid=20092`、`已收事件 144 条`、`控制台：已挂载 2 个，已扫描 4,031 行，产出 0 条 | EP 通道已短路 3,721 行`、面板提示「控制台有输出但未见 MyBatis SQL 日志」。
- 关联：T-M56-02（双路让位）、T-PS4-02（EP 短路计数）、T-CaptureTruth T-CT-04（epActive 置位收窄）、T-MultiModuleConsoleYield（行级 pid 归属）。复验装置见 demo 仓 `gauss-multi-module-a/b`（`46d5c87d`）。

---

## 一、现状机制（代码实证）

1. EP 通道（`consoleFilterProvider`）**只在控制台创建那一刻装配**：`SqlConsoleBridgeFilterProvider.getDefaultFilters` 先调 `sqlLogManagerIfCreated(project)`，返回 null 时直接 `return emptyArray()`（`SqlConsoleBridgeFilterProvider.kt:26-31`）。`sqlLogManagerIfCreated` 是 `getServiceIfCreated`（`SqlLogManagerLookup.kt:51-54`）——捕获服务从未创建过就是 null。
2. 桥通道（`addMessageFilter`）在捕获开启后**轮询补挂**，不依赖控制台创建时机（`SqlLogConsoleBridge.kt:330-372`）。所以「先启动应用、后开捕获」时，旧控制台只有桥。
3. 短路判据是**进程级全局**：`SqlCaptureGate.epActive`（`SqlLogConsoleBridge.kt:1007`，object 字段），桥 handler 只要看到它就 `return` 并只递增计数（`SqlLogConsoleBridge.kt:426-430`），不区分「本控制台是否有 EP」。
4. 增强接管期间 `paused=true`，静默冲刷会把待闭合条目丢弃（`SqlLogConsoleBridge.kt:543`）——空参数 SQL（`==> Parameters: ` 空行）在桥侧本来就要靠冲刷才闭合，这让桥的文字通道在接管期只剩「行级即时闭合」的形态。

## 二、根因链（用户现象对应关系）

时序（第一步与第二步的顺序是复现关键）：

1. 捕获关闭时先启动应用 A → 控制台 A 创建，`getDefaultFilters` 拿不到 manager → **A 没有 EP**；
2. 打开捕获 → manager 创建，桥轮询补挂控制台 A → A 单独可捕获（此时桥解析，`epActive` 仍 false）；
3. 启动应用 B（捕获已开）→ 控制台 B 创建，EP 正常装配；
4. B 的 EP 产出首条 SQL → `epActive = true`（进程级）；
5. A 的桥 handler 下一行命中全局短路 → 不解析、只 +1；A 又没有 EP → **A 零产出**，面板只剩 B。

截图读数与这条链逐项吻合：`EP 通道已短路 3,721 行` = 第 5 步的计数；`产出 0 条` = 该控制台只有桥且被短路；`已挂载 2 个` = 桥确实挂了两台。

为什么「单独启动能捕获」：只有一台控制台时，`epActive` 永不置位（没有别的 EP 产出），桥是唯一通道且不被短路。

为什么「先开捕获再启动两个应用」不复现：两台控制台创建时 manager 都在，EP 都装配成功，桥短路只发生在 EP 确实产出的控制台。

## 三、方案比较

| 方案 | 做法 | 可行性 | 代价 |
|---|---|---|---|
| **A. EP 总是装配、捕获开启后惰性生效** | `getDefaultFilters` 不再因 manager 未创建返回空；Filter 内惰性取 manager（未拿到时零处理、限频重试），拿到后常驻 | 高（不创建服务，不毒化容器） | 未启用捕获时每控制台每秒最多 2 次 service 查找（可忽略） |
| B. epActive 改 per-console | 按控制台记录「本控制台 EP 产出过」 | 低——`ConsoleFilterProvider.getDefaultFilters` 不传 console，EP/桥都拿不到「本控制台」身份 | 需平台层改造或反射硬猜 |
| C. 取消短路，统一靠跨通道去重 | 桥始终解析，EP 条目按 dedupeKey 折叠 | 中 | 去重键跨通道分叉已有六类（`[[pitfall-cross-channel-dedupe-key-divergence]]`），双报风险面大；接管期双通道 CPU 翻倍 |

## 四、拍板

- **A 实施**。它是唯一不依赖「平台把 console 传进来」的解，且顺带修掉同类形态：任何「控制台先于捕获创建」的场景（IDE 重启后先跑应用再开捕获、多模块先后启动）都不再出现旧控制台无 EP。
- B / C 不做。B 前提不成立；C 与既有去重口径冲突面太大。

## 五、实施内容

1. `SqlConsoleBridgeFilterProvider.getDefaultFilters(project)`：删除「manager 未创建 → 返回空」分支，始终返回 1 个 `EpLineFilter(project)`。
2. `EpLineFilter` 去掉构造参数 `manager`，改为惰性持有：
   - `@Volatile private var manager: SqlLogManager? = null`；
   - `currentManager()`：命中缓存直接返回；未命中时限频（≤2 次/秒）调 `sqlLogManagerIfCreated(project)`，拿到即常驻；
   - `applyFilter` 首行取 manager，null → `return null`（非捕获态零处理，等价于现在的空数组语义）；`!manager.capturing` → `return null`（保持捕捉态守卫）。
   - `ctx` 仍为 filter 实例级（per-console 状态不变）。
3. 边界：插件卸载/项目销毁后 filter 仍可能被平台调用——`sqlLogManagerIfCreated` 已判 `project.isDisposed`，manager 缓存需在 dispose 后失效（简单做法：manager 缓存对象若 `manager.isDisposed` 则重置）。核实 `SqlLogManager` 是否已有 `isDisposed` 可读，没有则用 `project.isDisposed` 兜底。
4. 测试（非 GUI 复现，新增 `EpAttachBeforeCaptureTest`，独立于 `EpBridgeSinglePathTest` 的 setUp——后者的 setUp 先建 manager，正是本缺陷的盲区）：
   - **修复前必红**：manager 未创建时 `getDefaultFilters(project)` 应返回 1 个 filter（旧逻辑返回 0，`.single()` 抛异常）。
   - manager 未创建时喂行 → `applyFilter` 返回 null、不产出、不创建服务。
   - 同一 filter 实例：先取 filter（manager 未创建）→ 创建 manager 并开捕获 → 喂 MyBatis 两行 → 产出 1 条且 `epActive=true`（「旧控制台开捕获后 EP 自动生效」的正面断言）。
   - 并存形态：filter1（先取，模拟旧控制台）与 filter2（后取）都能产出；`epActive` 置位后**桥侧**短路计数仍递增（既有防双报语义不回归），但旧控制台不再零产出。
5. 文档：`docs/架构设计/02-核心流程详解.md`（或子系统-日志捕获）补一句「EP 装配与捕获开启解耦，先建控制台在捕获开启后由惰性 manager 生效」；demo 仓 `gauss-multi-module-a/README.md` 的复现顺序同步为「修复后应两边都有」。
6. CHANGELOG `[Unreleased]` 一条。

## 六、待验证前提（实施前先证伪）

- P-1：`ConsoleFilterProvider.getDefaultFilters` 在控制台创建时是否**每控制台调用一次**、返回的 Filter 实例是否 per-console（现有 KDoc 与 `EpBridgeSinglePathTest` 的 `newEpFilter().single()` 支持，但「多控制台各拿一份」未见真机读数）。
- P-2：返回 Filter 但不创建 manager 是否真不毒化服务容器（`getServiceIfCreated` 语义已证，需用例钉住「未创建仍是 null」）。
- P-3：未启用捕获时逐行限频查找的开销可接受（限频 500ms 后每控制台 ≤2 次/秒）；若平台对每个控制台每行都调 `applyFilter`，控制台行速率可达数万/秒——限频必须生效，不能每行查。
- P-4：`SqlLogManager` 是否有可读的 disposed 状态；没有则用 `project.isDisposed` + 缓存置空。
- P-5（用户现象复现）：非 GUI 用例能否覆盖「先建控制台 → 后开捕获」的真实装配时序——平台侧不可注入，只能以「filter 先取、manager 后建」等价模拟；真机顺序验证仍需用户按 demo README 跑一次。

## 七、验收判据

- 非 GUI 复现：新增用例在**未改生产代码前**跑红（旧 provider 返回空 → `.single()` 失败），读数贴原始断言输出。
- 修复后：新增用例全绿；`EpBridgeSinglePathTest` 6 例不全回归；`CrossChannelDedupeTest` / `CrossChannelStaleReconcileTest` 定向绿。
- 反向验证：把 `getDefaultFilters` 改回「manager 未创建返回空」→ 新增「先取 filter」用例必须红 → 还原绿（git 补丁，不手改）。
- 真机（用户）：按 demo README 的「先启动 A → 开捕获 → 启动 B」顺序，修复后 A 的 `/item/pulse` 应继续出条目；截图同位置的 `EP 通道已短路` 计数不再伴随「A 零产出」。

## 八、不改

- 不动 `epActive` 的全局语义与桥侧短路（防双报仍由它承担）；
- 不动 `flushSettledEntries` 的 paused 丢弃（属增强接管期语义，本缺陷不需要它解释）；
- 不补 Oracle/Druid 驱动白名单——截图里的 `OracleClosedStatement` / `OracleStatementWrapper` / `Druid ConnectionProxyImpl` 未登记是增强覆盖的另一条账，与本现象无因果；
- 不改 demo 装置的数据源形态（Gauss / 多数据源都不是本现象的必要条件）。

## 九、实施记录

| 项 | 状态 | 提交 | 读数 |
|---|---|---|---|
| 立项 | 已完成 2026-11-25 | — | 本文件 |
| 非 GUI 复现（修前） | 已完成 2026-11-25 | — | `EpAttachBeforeCaptureTest` **4 例全红**：1 条 `AssertionFailedError`（expected 1 but 0，manager 未创建时 provider 返回空数组）+ 3 条 `NoSuchElementException`（`.single()` 拿到空集合） |
| 实施 | 已完成 2026-11-25 | 待提交 | `getDefaultFilters` 恒返回 Filter；`EpLineFilter` 惰性 manager（500ms 限频重查、取到后常驻、project 销毁清缓存）；测试注入 lookup + interval=0 |
| 定向 | 已完成 2026-11-25 | — | XML 实证：`EpAttachBeforeCaptureTest tests=4 failures=0`、`EpBridgeSinglePathTest tests=7 failures=0` |
| 反向（git 补丁） | 待做 | — | 恢复「manager 未创建返回空」→ 新用例必红 |
| 真机 | 待用户 | — | 按 demo README「先启动 A → 开捕获 → 启动 B」复验 |

实施坑一条：测试用 `.single()` 取 filter，修前直接抛 `NoSuchElementException`——这本身就是「旧控制台永远没有 EP」的最短证据；修复后同一写法变成正常断言，不用改测试结构。