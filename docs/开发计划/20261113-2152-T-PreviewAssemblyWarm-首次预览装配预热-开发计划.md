# T-PreviewAssemblyWarm：首次预览的装配预热（只算不建 UI）

- 立项：2026-11-13 21:52
- 任务源：用户「继续做，性能控制在极致」→ 追问「你建议调整吗」→ 采纳「不预构建面板、只预热装配」的建议，令先立项
- 关联计划：`docs/开发计划/20261113-0839-T-StartupReadLockFreeze-…-开发计划.md` §二十二（22.1~22.15 全部读数在此）
- 关联教训：`docs/LESSONS.md` L64（缓存失效基准）、L66（组件释放不能只挂 removeNotify）、L67（TTL 优先是伪缓存）、L68（按 ORM 分源）

## 一、现状读数（2026-11-13 21:44 沙盒，cust-big-demo，`P8` 埋点）

| 场景 | `request→dialog` | `construct` | `show` | 合计 |
|---|---|---|---|---|
| **本会话第一次** | 351 ms | **256 ms** | 88 ms | ≈ **695 ms** |
| 之后每一次 | 10~17 ms | 15~20 ms | 29~38 ms | ≈ **55~75 ms** |

关键判据：`construct` 冷 256ms / 热 15~20ms，差的是**编辑器/控制台/Laf 的首次初始化**；`request→dialog` 的
351ms 里除了装配（热态 47~62ms）剩下的也是**首次类加载与 JIT**。两笔都是「本会话仅一次」的冷启动成本。

同时已用读数否掉一条候选优化：`prepareDefaultConsole elapsed=0ms`（exec 临时文件已由 `previewAsync` 的后台
预热写就）⇒ 控制台准备无需挪到 show 之后。

## 二、明确**不做**：打开文件时预构建预览面板

| 维度 | 判断 |
|---|---|
| 收益 | 只在本会话第一次预览省 ~250ms（`construct` 冷→热）；稳定态本就 55~75ms，一分不省 |
| 代价 | 面板构造是 **EDT 活** ⇒ 等于用「打开文件慢 250ms」换「首次预览快 250ms」，与整轮目标（打开 15ms）相反 |
| 风险 | 新增「建好但从未显示」的生命周期（编辑器 + Disposable 树都要有人释放）。`a02ad51cf` 刚修完预览编辑器/补全监听未释放被 ObjectTree 记为泄漏——不该再开一条同类并行生命周期 |
| 浪费 | 用不上的预览也会建面板（demo 有 2042 个 mapper XML，随手翻十个就白建十次 Editor） |

结论：**收益一次性、代价换错指标、风险落在刚清理过的面上**，不做。

## 三、方案 W1：文件打开时后台预热「装配」，只算不建 UI

拿光标下那条语句在后台跑一遍**装配段**（parse → ctx → profile → model），结果**丢弃**。它只碰解析/OGNL/
模型/实体与表上下文，不创建 Editor、不建面板、不碰 Disposable 树，因此没有第二条生命周期。

- **W1-a 注入点**：沿用 gutter 现有的「文件打开预热」信号（`XmlSqlGutterLineMarkerProvider` 里
  `warmCompletionContextOnce` 同一处：语句标签 + dumb 门 + 每文件一次）。同一个信号做两件事——
  已存在的 ctx 预热 + 新增的装配预热，便于统一预算与去重。
- **W1-b 预热内容与边界**：走 `XmlPreviewService.preview(...)` 的组装入口，但必须满足：
  ① 不触发 m2 同步检索（首开已 deferred，保持）；② 不写 exec 临时文件、不碰控制台（那是
  `previewAsync` 的 prewarm 分支，预热不调用）；③ 不创建编辑器/面板；④ 结果只留在既有缓存里
  （实体缓存、候选缓存、解析器自身缓存），对象本身丢弃。
- **W1-c 线程与预算**：`ReadAction.nonBlocking { … }.inSmartMode(project).expireWith(project)
  .coalesceBy(this, "assemblyWarm:$file:$statement").submit(AppExecutorUtil…)`——与 S1 预取链、
  `warmCandidates` 同款（不持读锁等索引、可撤销、单飞）。dumb 态直接跳过；单文件一次、单会话内不重试。
- **W1-d 开关与回退**：`registryKey sqlvis.preview.assemblyWarm`（默认开）。关掉即完全回到现行为。
- **W1-e 测试缝**：`assemblyWarmForTest`（跑/不跑）、`assemblyWarmCount`（次数断言，不透出给用户）。

## 四、待验证前提（实施前先证伪，别把推断当事实）

- **P-1**：预热确实能压低冷态装配——判据是沙盒首开 `request→dialog` 从 351ms 明显下降；若预热后仍 300ms+，
  说明这 351ms 主要是 EDT 排队而不是类加载，本方案收益不成立，立即停手并把读数记回本节。
- **P-2**：预热不产生副作用——不得新增临时文件（`mpsql-preview-*.sql` / `mpsql-exec.sql` 计数不变）、
  不得出现 ObjectTree 泄漏记账、不得写 `workspace.xml`。
- **P-3**：预热链路能容忍「面板不存在」——`XmlPreviewService.preview` 是否隐含依赖面板状态（当前从
  `previewAsync` 之外调用时是否有空指针/断言风险）。若有依赖，取「只预热 Parser+Model 两段」的窄口径。
- **P-4**：dumb/索引期跳过不影响正确性——预热只是一次提前计算，跳过等价于现状。

## 五、验收

- **A1**（沙盒，主判据）：本会话第一次预览 `request→dialog` 目标 **< 450ms**（现 695ms 里的 351→<130ms），
  `construct` 不变（256ms 属面板/编辑器，本方案不碰）。
- **A2**（回归门）：稳定态 `request→dialog + construct + show` 不得回退（现 55~75ms）。
- **A3**（副作用门）：预热后同一会话的临时文件数、ObjectTree 泄漏条目、`mpsql-exec.sql` 写入次数与改前一致。
- **A4**（开关）：`sqlvis.preview.assemblyWarm=false` 时读数回到改前。

## 六、风险与不做项

- 风险：预热与真点击**并发**时争同一批缓存（实体/候选）——两者都是幂等的读+缓存写，冲突只会导致一次重复计算，
  不产生错误结果；但仍要求预热在点击到达前完成或自行放弃（`coalesceBy` + 预算）。
- 不做：先弹骨架窗后填内容（能再前移 ~350ms 的可见时间，但要动面板空态/无结果路径与用例，单独一轮）。
- 不做：预构建面板（理由见 §二）。

## 七、实施记录

**已实施（2026-11-13 22:0x）**：新增 `ui/PreviewAssemblyWarm.kt`（object，`warmOnce(tag)` 幂等），
接线在 `XmlSqlGutterLineMarkerProvider` 的文件打开预热处（紧跟既有 `warmCompletionContextOnce`，同一信号）；
Registry `sqlvis.preview.assemblyWarm`（默认开，plugin.xml `<registryKey>` 已登记）。

落地形态与计划的偏差：无。要点复述：

- 走 `XmlPreviewService.preview(...)` 这个**纯装配入口**（返回 `PreviewResult` 直接丢弃），不碰
  `SqlPreviewService.previewAsync`（那条会写 exec 临时文件、建控制台），所以 §三 W1-b 的四条边界天然满足；
- 去重键＝`文件 url#语句 id`；`coalesceBy(this, "assemblyWarm:$key")` 单飞；dumb 跳过并把 key 放回；
  失败/取消把 key 放回（下次渲染重试）；`expireWith(project)` 随工程关闭撤掉；
- 测试缝：`enabledProvider`（Registry 读取）、`syncForTest`（单测同步）、`runCount`（次数读数）、
  `resetForTest()`（跨测试类清态）。

守卫 `PreviewAssemblyWarmTest` **5/0/0/0**：开关关死不跑、同语句只跑一次、无 id 不跑、预热不建弹窗且
工程根不新增任何文件（A3 副作用门的机内版）、**接线点源码守卫**（防止「holder 有用例但没人调它」的空绿）。
反向：摘掉 gutter 接线 + 去掉去重 → 恰 2 红，还原复绿。定向：`*PreviewAssemblyWarm*` / `*SqlPreviewPanel*` /
`*XmlSqlGutter*` / plugin.xml 三守卫全绿。

**not run**：A1/A2/A4 的沙盒读数（按用户 2026-11-13「这次最后的 gui 验收我来」留给用户验收）——
判据见 §五；日志锚点 `[open-perf] assembly warm done in Xms key=…`（预热是否跑、耗时）与
`[T-PreviewFast] request→dialog: Xms` / `[open-perf] dialog: construct=Xms show=Xms`（首开三段）。
