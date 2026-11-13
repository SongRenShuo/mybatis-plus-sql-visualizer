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
