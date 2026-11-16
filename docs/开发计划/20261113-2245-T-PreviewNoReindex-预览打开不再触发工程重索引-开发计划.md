# T-PreviewNoReindex：预览打开不再触发工程重索引（dumb mode）

- 立项：2026-11-13 22:45
- 任务源：用户首测日志（2026-11-13 22:20~22:22 默认沙盒）显示**每次打开预览都会 `enter dumb mode`**；用户令「走」
- 关联计划：`20261113-0839-T-StartupReadLockFreeze…` §二十二（预览链全部读数）、`20261113-2152-T-PreviewAssemblyWarm…` §八（用户首测）

## 一、现象与证据（可复现，4/4）

```
22:21:02,184  [M36] exec file updated via document+save
22:21:03,265  #c.i.o.p.DumbServiceImpl - enter dumb mode [mybatis-plus-sql-visualizer-demo]
22:21:03,280  [M36] dialect pre-mapped to MySQL for mpsql-exec.sql
22:21:03,573  [M36][O-145] session created
22:21:04,644  [M36] attachConsole -> {}
22:21:05,049  [T-PreviewFast] request→console-visible: 4619ms
```

同一会话四次预览，四次都出现 `enter dumb mode`（22:21:03 / :15 / :42 / :51），紧跟我们的
「exec 落盘 + 方言映射」。后果不止那 4.6s：**每次预览后索引相关能力（含本插件自己的反查/补全/候选预热）
都会短暂失效**，也解释了控制台 392/1053/316ms 的抖动。

三段成本归属（同一窗口）：

| 段 | 量级 | 归属 |
|---|---|---|
| `enter dumb mode` + 重解析 | 0.3~1.5s | **我们的写盘/方言映射** ← 本任务 |
| `attachConsole`（平台建 DbConsole） | ~1.07s | 平台，不碰 |
| JPS `BUILDER_PROCESS` | 785ms | 引擎建模型，不碰 |

## 二、关键未知（必须先取证，别按直觉改）

预览临时文件按设计**每次换名**（`mpsql-preview-<pid>-<seq>.sql`，见 `SqlPreviewPanel` 的 KDoc：固定名会撞
`MemoryDiskConflictResolver` 的模态冲突——**这条不能退回去**），落在 `.idea/sql-visualizer/preview/`；
方言映射按**父目录**做（`SqlPreviewPanel.nextPreviewTempFileName` 附近的 KDoc 明确：按文件映射会
「每轮白付一次 setMapping（`SqlDialectPusher` push all dumb）」）。

那么 `enter dumb mode` 到底是谁触发的？两个候选：

- **C1 文件新增**：新临时文件写入 → VFS 事件 → pushed properties（`.idea/` 理论上被平台排除索引，待证）。
- **C2 方言映射写入**：`DialectPreMapping.ensureMapped` 若真的写了映射，`SqlDialectPusher` 会 push-all →
  dumb。当前日志**无法区分**「跳过」与「真写」——`DataSourceSelector.kt:2416` 在 `ensureMapped` 返回 true
  时一律打 `dialect pre-mapped`，而 true 同时代表「已相等跳过」和「写成功」。

## 三、方案（两步，先证后改）

**S0 取证（本任务第一步，只加埋点不改行为）**
1. 把方言映射日志分成两态：`dialect mapping: skipped(already=<id>)` / `dialect mapping: wrote <id>`
   （`ensureMapped` 返回「跳过/写入」两值或用一次性计数透出）。
2. 预览文件建立/复用分支各记一行：`preview temp: created <name>` / `preview temp: reused <name>`。
3. 沙盒跑 3~4 次预览，看 `enter dumb mode` 与哪条同拍出现。

**S1 按取证结果改**（候选，取其一或组合）
- 若 C2 成立：让「已映射」成为**真正短路**——`configuredLanguageId` 探测失败（null）时**不写**（当前是
  「保守照写」，代价是每次 push-all dumb），改为「沿用上一次成功的映射结果」并把探测失败记一行；
  另把「目录映射」结果按项目缓存（目录 vf 稳定），避免每轮重复 setMapping。
- 若 C1 成立：让预览目录**显式排除索引**（`ProjectFileIndex` 的 excluded 语义），或复用「已存在且无 document」
  的旧文件（`projectPreviewFile` 已有 existing 分支）——注意**不能退回固定名**（模态冲突）。

## 四、验收

- **A1**：沙盒连开 4 次预览，日志里 `enter dumb mode` 出现 **0 次**（改前 4/4）。
- **A2**：`request→console-visible` 首次/后续分别下降（改前 4619 / 392·1053·316ms），不做硬性目标。
- **A3**：预览功能零回归——SQL 正确、方言正确、执行/复制/参数面板不变；`MemoryDiskConflictResolver`
  模态冲突**不得复现**（这是临时文件换名设计的初衷）。
- **A4**：工程重建索引后（平台自身的 dumb 期），预览链照常降级不报错。

## 五、风险

- 改「映射跳过条件」可能让某类方言路由在首轮不生效（探测失败 → 不写 → 语言停留在推断值）。缓解：探测
  失败时保留一次写，但把「push-all dumb」成本挪到**预览打开之前**（文件打开时预热映射），并与 A3 一起验。
- 改临时文件落点/排除语义会碰 inlay 作用域锚点（`PreviewInlayScope` 按文件名形态判定）与
  `PreviewUnknownColumnAnnotator`——两者都按名字形态匹配，改名不受影响，但**落点变化**要让它们的
  作用域解析跟着改。

## 六、实施记录

### 6.1 S0 取证（两处对照，2026-11-13 22:2x / 23:04）

| 环境 | 预览次数 | `enter dumb mode` | 方言映射 | 其他 |
|---|---|---|---|---|
| 我的沙盒（`cust-big-demo`，单模块，无编译） | 4 | **0** | `dialect mapping skipped (already=MySQL)` | `preview root: .idea`；`preview temp: created …-N.sql` |
| 用户会话（demo 多模块，327 条 `BUILDER_PROCESS`） | 7 | **7（严格同拍）** | 未知（该版无 S0 埋点） | dumb 与编译活动相关性仅 2/8 |

两点结论：
1. **不是「新文件」引起的**：dumb 的任务名是 `com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater`
   ——**属性推送**，与我们每轮 `setMapping`（`SqlDialectPusher` push-all）同源；纯新增文件走的是索引更新
   任务。且我的沙盒每轮都新建临时文件却 0 次 dumb（`.idea` 本就被排除索引）。
2. **与「编译」也不强相关**：8 次 dumb 里只有 2 次 ±3s 内有 `BUILDER_PROCESS`，而 7 次预览全对应 dumb。
3. 差异解释：我的沙盒里 `getConfiguredMapping` 能读到 MySQL（历史会话写过文件级映射）⇒ 跳过写入；
   用户工程里探测返回不可判定 ⇒ 旧口径「保守照写」⇒ 每轮 push-all ⇒ 每轮 dumb。

### 6.2 S1 修法（`mappedMemo`）

`DialectPreMapping` 新增 `mappedMemo`（`project.locationHash|vf.path` → (languageId, 写入时刻)，TTL 10 分钟）：
- 探测**可用**时仍以平台为准（不看备忘）——避免「用户手动改过映射而我们照旧跳过」；
- 探测**不可用**（`configured == null`）时，若备忘显示上一轮已写成目标方言且未过期 ⇒ 跳过写入
  （这正是消灭「每轮 push-all dumb」的那一刀）；
- 写入成功才落备忘，过期后允许再写一次（宁多一次校验，不要永远跳过）。

守卫：`skipByMemo` 纯函数 4 例（探测可用不看备忘 / 备忘命中跳过 / 过期照写 / 目标变化照写）+
接线源码守卫（`skipByOwnMemo` 与 `mappedMemo[key] =` 必须在位）。
反向：# 备忘判定一旦摘掉，`skipByMemo` 的三态用例直接红（纯函数是主证据；接线守卫防「接了但没生效」）。

### 6.3 读数与 not run

- **not run**：用户工程复测 A1（连开 4 次预览 `enter dumb mode` 应为 0 次）、A2 的 `console-visible`
  对比、A3 的模态冲突回归——按用户 2026-11-13「最后的 GUI 验收我来」留给用户。
- 复测看点：`[M36] dialect mapping skipped (already=memo:MySQL byMemo=true)` 应出现，且其后**不再**出现
  `enter dumb mode [工程名]`；`request→console-visible` 应明显下降（用户基线 4619 / 392 / 1053 / 316ms）。

### 6.4 用户验收复测（2026-11-13 23:26~23:28 沙盒）：**备忘生效，但 dumb 仍在——上一轮的归因错了**

```
23:27:41,192  request→dialog: 1256ms（会话首开）
23:27:41,891  dialect mapping skipped (already=MySQL target=MySQL byMemo=…)   ← 备忘生效：**没有写映射**
23:27:42,194  [M36] exec file updated via document+save                       ← 真正的触发点
23:27:42,955  enter dumb mode
23:27:43,015  (dumb mode task) PushedFilePropertiesUpdater
23:27:43,022  Reparsing …/.idea/sql-visualiz…                                 ← 重解析的是我们的 exec/预览文件
23:27:44,548  request→console-visible: 4613ms
```

该会话统计：`preview temp: created` ×4、`exec file updated` ×4、`enter dumb mode` ×4、`dialect mapping skipped` ×11
——**四次预览四次 dumb，且每次 dumb 都紧跟 exec 落盘**；全程**没有一次映射写入**。

结论订正：
- **C2（方言映射写入）被证伪**：备忘把它彻底短路了（`byMemo=true`），dumb 照旧 ⇒ 不是它。
- **真凶是 exec 文件的内容写入**（`document+save` → 平台 `PushedFilePropertiesUpdater` → `Reparsing` 我们的文件
  → dumb）。同一份代码在我沙盒（`cust-big-demo`，单模块）连写 3 次 exec 文件、dumb **0 次** ⇒ 该平台行为
  **与工程形态相关**（多模块/挂编译器的工程才触发）。
- 因此 6.2 的备忘修复**不解决**用户看到的 dumb；它的价值只剩「避免某些环境下反复 push-all」的加固，保留。

为什么不再硬啃（成本/收益）：exec 文件**必须**在工程内才有 PSI 与原生控制台（R6 的 `shouldCreatePsi` 限制，
这也是它从系统临时目录搬到 `.idea` 的原因），所以「不写工程内文件」这条路与「控制台要能执行」直接冲突；
剩下的候选都动的是执行链核心时序——
1. **把 exec 落盘推迟到「首次真要执行」**（预览打开只绑定文档不落盘）⇒ dumb 从「预览打开」挪到「点执行」，
   总时长不降、但不再打断预览后的索引；碰 T-PreviewFlash / UI-023 / exec doc lock 三条既有守卫，风险中高；
2. 换落点回系统临时目录 ⇒ 直接牺牲 PSI/控制台能力（R6 已证伪过一次）。
3. 报 JetBrains：`PushedFilePropertiesUpdater` 对「excluded 目录内的子文件内容变更」也进 dumb，疑似平台过宽。

**建议**：本任务在「已取证 + 已加固」处收口，不再为 ~0.8~1.3s（且属平台行为）去改执行链时序；若用户后续仍被
「预览后补全短暂失灵」困扰，再单独立项做候选 1。

### 6.5 机制精确定位（2026-11-14 00:0x，从用户 23:27 日志取的全路径）

```
23:27:42,194  [M36] exec file updated via document+save
23:27:43,015  (dumb mode task) PushedFilePropertiesUpdaterImpl$MyDumbModeTask
23:27:43,022  Reparsing …/.idea/sql-visualizer/exec/mpsql-exec.sql because of **language substitution**
```

链条读法：**我们写这个文件 → 平台为它重算「语言替换」（因为我们给它设了方言映射）→ 推文件属性（需要索引）
→ 整个工程进 dumb → 重解析该文件**。不是新文件、不是编译、也不是「映射写入」（备忘已把写入压到 0）。

据此本轮再动一刀（`applyExecDialectMapping`）：**方言映射从「文件级」改为「父目录级」**——与预览侧一致
（平台 `getMappingForHierarchy` 自下而上继承，目录映射覆盖其下全部文件，含每轮新建的预览临时文件）。
目的：内容重写时不再因「文件自己的映射」触发替换重算。**该刀是否真能消掉 dumb 尚待复测**（本地单模块工程
不复现，只能由用户在 demo 多模块工程验）。

**决定性 A/B（2 分钟，用户侧）**：同一语句连开两次预览。
- 第二次日志应出现 `[M36] exec file content already matches — write SKIPPED`；
- 若第二次**不再**出现 `enter dumb mode` ⇒ 触发点＝**写入** ⇒ 下一刀走「exec 落盘推迟到首次执行」（方案 C）；
- 若第二次**仍有** dumb ⇒ 触发点＝**替换重算本身**（与映射落点无关）⇒ 下一刀走「exec 文件不设方言映射，
  方言由数据源/控制台决定」（方案 A，需验控制台内 SQL 解析仍按目标方言）。

**not run**：`ExecPsiGateTest` 属 `testDbExecPsiGate` 桶（需 Database 插件），本地跑该桶会因缺插件红，
本轮的映射目标改动由 CI 的 DB 腿覆盖；该测试的断言用 `configuredLanguageId`（含继承链），目录级映射
仍会读出目标方言，预期不破。
