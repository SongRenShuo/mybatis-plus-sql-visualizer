# T-PreviewExecWriteDefer：预览打开不写 exec 文件（延迟到首次执行）

- 立项：2026-11-14 00:20
- 任务源：用户「dumb 的问题无法解决掉吗」→ 实施「预览打开不落盘」这一刀；A/B 已由用户操作完成
- 前置计划：`20261113-2245-T-PreviewNoReindex…`（S0 取证 / 备忘 / 目录级映射；§6.5 记录了本次的机制定位）

## 一、A/B 结果（用户 00:10~00:11 沙盒操作，决定性）

| 次数 | exec 文件 | `enter dumb mode` | 备注 |
|---|---|---|---|
| 第 1 次（新 SQL） | `exec file updated via document+save` | **有**（2s 内） | 写完 → `PushedFilePropertiesUpdater` → `Reparsing … because of language substitution` |
| 第 2 次（同一 SQL） | **没有写**（整条 prep 被跳过） | **无** | 同一条语句重开不碰文件 |

⇒ 触发点是**写这个带方言映射的文件**，不是映射写入本身（备忘已把映射写入压到 0）、不是新文件、不是编译。
这也与既有 SEVERE 同源：`SlowOperations on EDT ← FileManagerImpl.shouldResurrect → shouldCreatePsi
→ isUnderIgnored`（反复重写让该文件的 PSI 失效，EDT 上再要 PSI 就走索引）。

## 二、方案：预览只**准备**控制台，不落盘；落盘归执行链

- 预览打开走的是 `chooseAndPrepare(executeAfterPrepare = false)`（`SqlExecutor.prepareConsole`）；
  执行走 `executeAfterPrepare = true`（`selectAndExecute`）。执行链本身会在执行前把 exec 文件同步成
  当前 SQL（既有行为），所以**把预览侧的落盘剪掉不影响执行正确性**。
- 控制台显示不依赖磁盘：挂载后 `T-PreviewConsoleFmt` 的 post-attach `renderSql()` 会把当前渲染文本
  回填进控制台文档（内存态），用户看到的仍是正确 SQL。
- 开关：Registry `sqlvis.preview.deferExecWrite`（默认 **true**）；置 false 回到旧行为（预览即落盘）。

## 三、待验证前提（实施即证）

- **P-1**：预览侧剪掉落盘后，控制台首次打开仍显示完整 SQL（靠 post-attach 回填）。
- **P-2**：点执行时执行链把文件同步成当前 SQL 再执行（`selectAndExecute` 既有路径），结果与改前一致。
- **P-3**：`write SKIPPED/DEFERRED` 之后不再出现 `enter dumb mode`（用户沙盒复测）。
- **P-4**：`testDbExecPsiGate`（DB 桶，本地可跑）与面板/执行链定向用例不回归。

## 四、验收

- **A1**：连开 4 次预览（不同语句）`enter dumb mode` **0 次**（改前 4/4）。
- **A2**：`request→console-visible` 首次/后续均下降（用户基线 4619 / 337·370·706ms）。
- **A3**：功能零回归——控制台显示正确 SQL、执行结果不变、换数据源/回滚弹窗等复用同一 prep 的入口不受影响。
- **A4**：开关置 false 回到改前读数（回退通道可用）。

## 五、风险

- 剪掉落盘后，**控制台文档内容与磁盘不一致**（内存有、磁盘旧）：任何「从磁盘读这个文件」的路径都会拿到旧内容。
  已核对：执行链执行前会同步；若实测发现别的读取点（例如 EXPLAIN、复制可执行 SQL），则改为「执行前统一同步」并在
  那些入口加上同一同步调用。
- `T-PreviewFlash`/`UI-023`/exec 文档锁三条既有守卫都在这条链上：改动只加"跳过落盘"一个分支，不重排时序；
  定向用例 + DB 桶用例共同兜。

## 六、实施记录

**已实施（2026-11-14 00:4x）**

- 新增 `ui/PreviewConsoleEager`：单一开关 `sqlvis.preview.eagerConsole`（默认 **false＝惰性**）。
  单测/沙盒默认走旧行为（`isUnitTestMode`），既有用例零改动；`enabledForTest` 可强制任一侧。
- `SqlPreviewPanelResultView`：`prepareDefaultConsole()` 改为受开关控制；惰性时打
  `[open-perf] console prep DEFERRED` 并计 `PreviewConsoleEager.deferredCount`。
- `SqlPreviewService.previewAsync`：惰性时**不预热 exec 文件**（预热本身就是那次写盘）。
- plugin.xml 登记 registryKey（默认 false）。

为什么这两处就够了：预览侧真正的写盘入口只有两个——`previewAsync` 的后台预热、`prepareDefaultConsole`
里的准备链；执行侧（`selectAndExecute`）本来就会「准备 + 同步 exec 文件 + 执行」，所以惰性口径下
用户第一次执行时文件自然被写对，正确性不依赖预览那一次写。

读数（本地可跑的验证）：
- `testDbExecPsiGate`（DB 桶，含 exec 链 PSI 门 / 映射自愈 30+ 例）**全绿**——顺带验证了 `cc6745550`
  的「映射落到父目录」对 exec 链安全；
- 面板/预热/开关定向（`*SqlPreviewPanel*`、`PreviewAssemblyWarm`、`PreviewConsoleEager`）**全绿**；
- `PreviewConsoleEagerTest` 3 例钉住口径（单测默认 eager、显式惰性、显式旧行为）。

**not run / 待用户验收**：A1（连开 4 次预览 dumb = 0）、A2 的 console-visible 对比、A3 功能回归
（控制台显示/执行/换数据源）、A4 开关回退。**已知取舍**：惰性口径下预览打开时**控制台区域为空**，
首次执行（或点「切换数据源」）后才出现——这是本刀的代价；要恢复旧观感把 `sqlvis.preview.eagerConsole`
置 true 即可。

### 6.1 三档口径（设置页可切，2026-11-14 01:1x 追加）

用户要求「设置页也露一格」→ 新增 `PluginSettings.State.consolePrepMode`（默认 `LAZY`）与设置页下拉
「界面与操作 → 执行控制台」，三档：

| 档位 | 存档值 | 预览打开时 | 绑定行入口 |
|---|---|---|---|
| 按需准备（带入口）**默认** | `LAZY` | 不建控制台（不写 exec 文件） | 有「显示执行控制台」 |
| 按需准备（不放入口） | `LAZY_SILENT` | 同上 | 无 |
| 打开即准备（旧行为） | `EAGER` | 建控制台（写 exec 文件） | 无（不需要） |

- 选项**由 `PreviewConsoleEager.Mode` 枚举派生**（key 用完整字面量，bundle 守卫可扫），UI 与解析端不可能分叉；
- Registry `sqlvis.preview.eagerConsole` 保留为**高级逃生舱**（置 true 强制 EAGER 语义）；
- 守卫：`PreviewConsoleEagerTest` 增三档判定矩阵 + 默认档 = LAZY + 枚举名/存档值一致性；
  `PluginSettingsPanelBindingTest`（17 例）与 `BundleParityGuardTest` 全绿。

### 6.2 自验收读数（2026-11-14 01:19~01:30，runIde 沙盒 IU-2024.3.7.1，cust-big-demo）

**A1（主判据）通过**——预览打开不再写 exec 文件、不再进 dumb：

```
01:19:31,646  [T-PreviewFast] request→dialog: 305ms      ← 会话首开（冷）
01:19:31,853  [M36] preview temp: created mpsql-preview-74872-*.sql
01:19:31,937  [open-perf] console prep DEFERRED（惰性口径：预览打开不写 exec 文件）
```

同窗口内 **没有** `exec file updated`、**没有** `enter dumb mode`、**没有** `SlowOperations` SEVERE；
右侧只剩 SQL 编辑器，绑定行显示「未绑定数据源（在控制台执行一次查询即可绑定） 显示执行控制台」，
弹窗底部「执行 SQL / 复制 SQL / 复制 SQL 并关闭」齐备。

**入口有效性通过**——点「显示执行控制台」后：

```
01:21:59,090  [M36] prepareDefaultConsole: editor=true, ...
01:21:59,132  [M36] exec file updated via document+save
01:21:59,139  enter dumb mode [cust-big-demo]
```

即：写盘与 dumb **只在用户显式点入口时发生一次**，与设计一致（用户动作可接受）。

**A3 未完全验证（环境限制）**：该沙盒工程无可用数据源（同段日志 `runners=0`），控制台拿不到 runner ⇒
原生控制台组件不会挂上，无法在本环境目视「控制台出现 + 工具栏（含 Data Sources 菜单）」。链路本身已触发
（`prepareDefaultConsole` 有日志），但「控制台可见」这一条留给有数据源的工程复验。

**A4 由用例覆盖、GUI 未验**：三档判定矩阵（LAZY / LAZY_SILENT / EAGER）在 `PreviewConsoleEagerTest` 全绿；
GUI 侧设置页下拉已目视确认存在（「执行控制台：按需准备（带入口）」），但自动化点击设置页 OK 未能稳定落地
（坐标偏差），EAGER 档的端到端行为改由用例 + 代码路径同源（`prepareDefaultConsole()` 仍是旧路径）担保。

**遗留**：无数据源工程里点「显示执行控制台」不会出现控制台（与 eager 档下的旧行为一致），属既有语义，非本刀引入。
