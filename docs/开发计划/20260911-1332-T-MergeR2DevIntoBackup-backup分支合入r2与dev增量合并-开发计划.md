# r2-backup-pre-dev-merge-20260910 合入 feature/r2-competition-response 与 dev 增量合并开发计划

- 日期：2026-09-11 13:32
- 任务号：`T-MergeR2DevIntoBackup`
- 目标分支：`r2-backup-pre-dev-merge-20260910`（= `b632086`）
  - 本次执行 worktree：`C:\Users\USER\WorkBuddy\Worktrees\mybatis-plus-sql-visualizer\r2-backup-pre-dev-merge-20260910-346f4bf8`
  - 该 worktree 当前检出分支 `workbuddy/r2-backup-pre-dev-merge-20260910-346f4bf8`，与目标分支同指 `b632086`
- 待合入①：`feature/r2-competition-response` = `42cc889`（worktree `F:/code/Java/Gitee/SongRenShuo/mpsv-r2`）
- 待合入②：`dev` = `dc31137`（worktree `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`）
- 合并顺序：**两段式——先 r2，后 dev**
- 合并方向：两个来源分支均**合入**目标分支（目标分支为集成分支）
- 预演方式：`git merge-tree --write-tree` 预扫 + 临时 worktree 真实 `git merge --no-commit` 双重复核（已执行，临时环境已清理，主 worktree 保持干净）
- 关联上一轮同型合并：
  - `docs/开发计划/20260910-1241-T-MergeDevR2Round2-dev合入r2第二轮增量合并-开发计划.md`（merge `e1471fc`）
  - `docs/开发计划/20260910-1211-T-MergeMasterIntoDev-master合入dev增量合并-开发计划.md`（merge `e6a42ff`）

## 一、分叉事实（实测）

| 关系 | merge-base | 独有提交 | 结论 |
|---|---|---|---|
| base ↔ r2 | `b632086`（即 base 自身） | base 0 / r2 30 | **base 是 r2 的祖先 → 第一段为纯快进** |
| base ↔ dev | `e64987e` | base 28 / dev 58 | base 与 dev 分叉较深，dev 侧含量大 |
| r2 ↔ dev | `ed7723f` | r2 31 / dev 31 | **r2 已含上一轮 dev 合入**，第二段实为增量 |

三条关键结论：

1. **第一段（base + r2）是纯快进**。`git merge-tree --write-tree r2-backup-pre-dev-merge-20260910 feature/r2-competition-response` 干净返回树 `dd3dcdd5`，无冲突；`git merge` 将直接 `Fast-forward`，不产生合并提交。校验锚点：合并后 `HEAD` 应等于 `42cc889`。
2. **r2 已包含上一轮 dev 合入**（merge-base = `ed7723f`，即 T-MergeMasterIntoDev 的交付同步提交；r2 侧 `e1471fc` 那次合并的产物）。因此第二段真正要比对的是 dev 在 `ed7723f` 之后的 31 个提交，而非全量。
3. **base 线不含 dev 侧资产**：实测 `record/` 在 `b632086` 为 0 文件、`ed7723f` 为 11 文件、`dev` 为 12 文件——第一段快进会把 dev 侧录制管线等资产一次性纯新增带入，无覆盖风险。

## 二、冲突预判（merge-tree + 真实试合并双重实测）

内容冲突 **2 文件 2 块**，全部是「最新在上」表格的**顶部插行**同型冲突：

| 文件 | 冲突块位置 | r2 侧（HEAD/ours） | dev 侧（theirs） | 性质 |
|---|---|---|---|---|
| `docs/变更记录.md` | 第 12–17 行 | 2 行：`2026-09-10 dev 合入 r2 第二轮（T-MergeDevR2Round2，merge e1471fc）`、`2026-09-10 生成测试功能 r2 分支跟随 dev 双线一致下线` | 1 行：`2026-09-11 T-PerfBatch 第二轮交付 + T-PerfSweep 性能分析遗留热点清扫` | 同表顶部并集 |
| `docs/MEMORY.md` | 第 221–226 行 | 2 行（同上述两条的 MEMORY 版措辞） | 1 行：`2026-09-11 T-PerfBatch 第二轮交付 + T-PerfSweep 四项遗留热点清扫` | 同表顶部并集 |

**零 delete/modify 冲突、零 add/add 冲突、零 rename 冲突。源码（`src/main`、`src/test`）零冲突。**

试合并输出摘录：

```
Auto-merging docs/MEMORY.md
CONFLICT (content): Merge conflict in docs/MEMORY.md
Auto-merging docs/变更记录.md
CONFLICT (content): Merge conflict in docs/变更记录.md
Auto-merging src/main/resources/messages/MpSqlVisualizerBundle.properties
Auto-merging src/main/resources/messages/MpSqlVisualizerBundle_zh.properties
Automatic merge failed; fix conflicts and then commit the result.
```

## 三、自动合并区关键结论（预演已核实，无需人工干预）

1. **两侧都改的文件只有 4 个**。r2 侧自 merge-base 改动 43 文件、dev 侧改动 73 文件，**交集仅 4 个**：`docs/变更记录.md`、`docs/MEMORY.md`、`MpSqlVisualizerBundle.properties`、`MpSqlVisualizerBundle_zh.properties`。前两个冲突，后两个**自动合并成功**——源码文件两侧零交集，这是本次合并「几乎无痛」的根本原因。
2. **bundle 中英键完全对称**：合并结果 en 与 zh 均为 **553 键**，双向差集为空（无单边键、无重复键）。较上一轮 548 键 +5，为 dev 侧新增键。
3. **`CHANGELOG.md` 自动全取 dev 版**：合并结果含 `## [2026.9.10] - 2026-09-10`、`## [2026.9.9] - 2026-09-09` 两段（r2 侧本轮未动该文件，dev 侧为超集）。
4. **版本号走向 `2026.9.8` → `2026.9.9` → `2026.9.10`**：base 为 `2026.9.8`、r2 为 `2026.9.9`、dev 为 `2026.9.10`，合并结果取 **`2026.9.10`**（`gradle.properties`），与 CHANGELOG 版本段自洽。
5. **`plugin.xml` 保留 r2 版**：r2 侧独有改动、dev 侧自 merge-base 未动该文件，自动合并即 r2 内容 → r2 竞品应答描述文案（冷启动组合拳 / 承接 MyBatis Log Free 老用户 / 弹窗克制）一字不丢。
6. **r2 竞品应答代码资产逐项计数保留**：合并结果 `supportsDeleteLimit` 8 行、`entityResolutionFailed` 4 行、`isWrapperVariable` 13 行，与 r2 基线（`git grep 42cc889`）**逐项相等**。
7. **dev 侧删除仅 1 文件**：`docs/images/t-vstack-requirement.png`（T-VStack 需求截图移出 docs，r2 侧无引用面，安全）。r2 侧零删除。
8. **`testgen` 双线下线一致**：合并结果 `src/` 内 `testgen` / `TestAssetGenerator` 命中 **0**，无残留引用。
9. **单侧新增互不影响**：dev 侧新增 17 文件（11 份开发计划/性能分析文档 + 4 个测试类 + `record/pos_frame.py` + 1 份发行说明）；r2 侧新增 9 文件（`dev-support/ci-verify.ps1`、`docs/规范/设计规范-弹窗克制红线.md`、6 份调研/计划/测试计划文档、`DatabaseSchemaResolverFallbackTest.kt`、`XmlMapParamSpecMatrixTest.kt`）。均为单侧新增，无覆盖风险。
10. **`record/` 录制管线**：dev 相对 merge-base 改 10 文件（+332/−57，含新增 `pos_frame.py`），与 r2 零交集。
11. **dev 上游能力在位**（合并结果 `src/main` 计数）：`sequenceNumber` 4、`isModuleBasedLaunch` 3、`awaitPrewarmedTempFile` 3、`lineFragments` 6。

## 四、解决原则

1. 两处表格冲突**一律并集去重 + 日期倒序**：dev 的 `2026-09-11` 行置顶 → r2 的 `2026-09-10` 两行 → 向下接共有区。**不取边、不丢行**。
2. **严禁按文件整取一边**（`git checkout --theirs / --ours`）。`docs/MEMORY.md` 的**非表格索引区两侧各自插过行**（实测：r2 在旧行 35 后插 1 行、dev 在旧行 81 后插 1 行），整取会直接抹掉另一侧改动——**上一轮 T-MergeMasterIntoDev 已因此翻车过一次**（`releaseForPanel` 悬空致编译失败）。
3. 正确的处置姿势：`git checkout -m <file>` 恢复三方**自动合并结果**，然后**只替换冲突块**；或用 `git merge-file` 复现自动合并输出后定点替换。
4. 自动合并区（bundle / CHANGELOG / gradle.properties / plugin.xml / 源码）只做**语义核验**，不重写。

## 五、逐块解决方案

### 5.1 `docs/变更记录.md`（冲突块第 12–17 行）

- r2 侧（HEAD）2 行：
  - `| 2026-09-10 | **dev 合入 feature/r2-competition-response 第二轮（T-MergeDevR2Round2，merge e1471fc）**…|`
  - `| 2026-09-10 | **生成测试功能 r2 分支跟随 dev 双线一致下线（T-MergeDevR2 后续，用户决策）**…|`
- dev 侧 1 行：
  - `| 2026-09-11 | **T-PerfBatch 第二轮交付 + T-PerfSweep 性能分析遗留热点清扫…**|`
- **定案**：删除 `<<<<<<< HEAD` / `=======` / `>>>>>>> dev` 三行，按 **2026-09-11（dev 1 行）→ 2026-09-10（r2 2 行）** 顺序落位。紧随其后的 `| 2026-09-10 | **master 合入 dev 增量合并（T-MergeMasterIntoDev，merge e6a42ff）**…|` 属共有行，保持原样不动。
- 表头行与分隔行沿用 r2 侧（避免无谓的内容宽度改写）。

### 5.2 `docs/MEMORY.md`（冲突块第 221–226 行）

- 同型：r2 侧 2 行 vs dev 侧 1 行。
- **定案**：与 5.1 完全一致的并集顺序；**但禁止「表头取一侧 + 表行并集重写整文件」的脚本做法**，必须先 `git checkout -m docs/MEMORY.md` 拿到真实自动合并结果，再只替换那一个冲突块（理由见第四节第 2 条）。

### 5.3 自动合并区核验（只核对，不施工）

| 核验项 | 期望值 |
|---|---|
| `gradle.properties` | `version=2026.9.10` |
| `CHANGELOG.md` | 含 `[2026.9.10]`、`[2026.9.9]` 两段 |
| bundle en / zh | 各 553 键、双向差集为空 |
| `plugin.xml` | 与 r2 版逐字节一致 |
| r2 资产计数 | `supportsDeleteLimit` 8、`entityResolutionFailed` 4、`isWrapperVariable` 13 |
| dev 资产计数 | `sequenceNumber` 4、`isModuleBasedLaunch` 3、`awaitPrewarmedTempFile` 3、`lineFragments` 6 |
| r2 文档资产 | `docs/规范/设计规范-弹窗克制红线.md` 在位、`SqlPreviewSpecMatrixTest` / `XmlMapParamSpecMatrixTest` 在位 |
| `testgen` 残留 | 0 |

## 六、实施步骤

### Wave 0 前置（本计划）

- 生成并提交本计划文档。
- **打回滚锚点 tag**：`git tag backup/pre-r2dev-merge-20260911 b632086`
  - 必要性：第一段是快进，分支指针会**离开** `b632086`，此后回滚无 SHA 可依；同时该 tag 与 worktree 检出分支 `workbuddy/r2-backup-pre-dev-merge-20260910-346f4bf8`（同指 `b632086`）互为双锚点。
- 确认三处 worktree（目标 / r2 / dev）均无未提交改动，避免带入脏工作树。
- 确认并行任务登记表无占用（沿用前两轮：`T-Merge*` 任务名 + 独立沙盒）。

### Wave 1 第一段——合入 r2（快进）

- 在目标 worktree 执行：`git merge feature/r2-competition-response`
- 预期：`Fast-forward`，无冲突、无合并提交。
- 校验：`git rev-parse HEAD` == `42cc889`。
- 决策点：若希望保留一条显式合并提交（体现「两源一次性整合」的语义），改 `git merge --no-ff`。**本计划默认 FF**（无信息量的合并提交不入库），如需 `--no-ff` 请在执行前确认。

### Wave 2 第二段——合入 dev（真实冲突在此）

- 执行：`git merge dev`
- 预期：复现第二节的 **2 文件 2 冲突块**，与预判一致。
- 按第五节处置：
  - `docs/变更记录.md`：并集 3 行按日期倒序落位。
  - `docs/MEMORY.md`：`git checkout -m` 恢复自动合并结果 → 只替换冲突块。
- 校验：`git diff --name-only --diff-filter=U` 为空；两文件 `grep -c '^<<<<<<<'` 为 0。

### Wave 3 语义清扫

- bundle zh/en 键对称（553/553，双向差集空）。
- r2 资产计数（8/4/13）与 dev 资产计数（4/3/3/6）复跑，与预演值比对。
- `testgen` 全树零残留；`plugin.xml` 与 r2 逐字节比对。
- `CHANGELOG.md` 段落顺序与 `gradle.properties` 版本号复核。
- **语义风险点**（源码零交集，重点在时序与共享设置）：
  1. dev 侧 `SqlLogEntryBuffer` 三出口拆分（T-PerfBatch 第二轮）改变了列表通知时序，需确认 r2 侧矩阵测试（`SqlPreviewSpecMatrixTest` / `XmlMapParamSpecMatrixTest`）与预览链路测试不受影响。
  2. 两侧各自新增的测试共存后是否互相污染共享设置（`PluginSettings` 显示模式 / 行号 / 开关态）——bundle +5 键在 zh 侧无遗漏。

### Wave 4 全量测试与构建

- `./gradlew test --no-daemon`（独立沙盒）→ **红线 0 失败**。
  - 预期规模：r2 基线 3720（合并前）+ dev 侧新增（dev 基线 3696）去重 ≈ **3700–3760** 量级，以实跑为准（测试类数同步增长）。
  - 已知假失败两类预案（沿用前两轮）：① K2J 陈旧类劫持——`build/classes/java/main` 残留旧类导致字节码级假红，按包清除后复跑；② clean 首跑 worker 崩溃——原样重跑一次。
- `./gradlew verifyPlugin --no-daemon` → 三目标（IU-243 / IU-253 / IU-262）**Compatible**；`buildPlugin` 出产物 `build/distributions/mybatis-plus-sql-visualizer-2026.9.10.zip`。

### Wave 5 定稿与同步

- 合并提交信息（第二段）建议：
  `merge: feature/r2-competition-response + dev into r2-backup-pre-dev-merge-20260910——r2 快进 30 提交（含上轮 dev 合入整簇与竞品应答资产）+ dev 31 提交（T-PerfBatch 第二轮/T-PerfSweep/T-PreviewSlow/T-HibDualCapture/T-VersionUtil/GIF 录制环境）；2 文档冲突并集，源码零冲突`
- 文档同步：`docs/变更记录.md` 顶插本次交付行、`docs/MEMORY.md` 变更记录行、本计划 §九 实施记录回填。
- **不主动 push**（沿用前两轮约定）。
- runIde 真机抽验留人工：dev 面（日志行号 / 竖向排列 VALUES 列名标注 / 预览加载速度）+ r2 面（冷启动首屏 / 承接老用户文案 / 弹窗克制）。

## 七、风险与回滚

| 风险 | 等级 | 处置 |
|---|---|---|
| 第一段快进使分支指针离开 `b632086`，丢失原位 | 中 | Wave 0 先打 tag `backup/pre-r2dev-merge-20260911`；worktree 检出分支 `workbuddy/…-346f4bf8` 同指 `b632086` 为第二锚点 |
| `docs/MEMORY.md` 非表格区被「整表重写」抹掉 | **高** | 强制 `git checkout -m` + 只替冲突块；第四节第 2 条为硬约束 |
| 版本号语义：目标分支变为 `2026.9.10` | 低 | 与 dev 一致是最省事口径（避免又一处内容/EOL 分叉）；若该分支另有独立发版计划，发版时重新定号，本次不做额外处理 |
| bundle 出现单边键 | 中 | Wave 3 双向差集检查；本预演已实测为空（553/553） |
| 全量测试规模膨胀至 3700+ | 低 | 独立沙盒 + 两类已知假失败预案 |
| 中途回滚 | — | 第二段：`git merge --abort`；第一段快进后：`git reset --hard b632086`（或 `backup/pre-r2dev-merge-20260911`） |

## 八、验收标准

- [x] 第一段合入 r2 零冲突（**偏差**：因 Wave 0 先提交计划文档，HEAD 离开 `b632086`，故为真实合并 `3ae4d1b` 而非快进；与 r2 的树差异仅计划文档本身，等价于快进效果）
- [x] 第二段 2 处文档冲突并集完成：无丢行、无重复行、无冲突标记残留（日期倒序在冲突块内成立；表格第 30–31 行处 dev 侧**固有**倒序见 §九）
- [x] r2 竞品应答资产在位：`plugin.xml` blob `d58ae1b` 与 r2 逐字节一致、`docs/规范/设计规范-弹窗克制红线.md`、金样矩阵测试、`supportsDeleteLimit`/`entityResolutionFailed`/`isWrapperVariable` 计数 8/4/13
- [x] dev 上游能力在位：`sequenceNumber` 4 / `isModuleBasedLaunch` 3 / `awaitPrewarmedTempFile` 3 / `lineFragments` 6、`record/` 12 文件、bundle 553×2 键对称
- [x] `testgen` 零残留；版本号 `2026.9.10`；CHANGELOG 含 `[2026.9.10]` 与 `[2026.9.9]`
- [x] 全量测试 0 失败（3760/0/0/6，见 §九）
- [x] verifyPlugin 243/253/262 三目标 Compatible，`buildPlugin` 出包
- [x] 变更记录 / MEMORY / 本计划三处同步；回滚 tag `backup/pre-r2dev-merge-20260911` 已打

## 九、实施记录（回填）

### 执行偏差（两处，均已处置）

1. **第一段非快进**。计划设想的「Wave 0 提交计划文档 → Wave 1 快进」在时序上互斥：提交计划文档必然使 HEAD 离开 `b632086`。实际按「先立项提交、后合并」执行，第一段为真实合并 `3ae4d1b`（零冲突，`git diff feature/r2-competition-response HEAD` 仅计划文档 203 行新增），语义与快进等价且多一条可追溯的合并点。
2. **dev 侧固有阻塞（非合并引入）**。dev tip `dc31137` 的 `438c57b`「T-GifFullscreen 修订九录制环境——262 平台/JCEF/toolchain24/独立沙盒 MPSV-GifFresh（**录制期配置**）」把构建平台由 `2024.3.7.1` 切到 `2026.2.1`，导致 **dev 自身的 test 源码无法编译**：`com.intellij.diagram.DiagramRelationshipInfo.label` 6 处 Unresolved reference、`String.toLowerCase()` 硬弃用报错 1 处、`runCatching` 类型推断连带 1 处，共 8 条。**取证**：在 dev worktree（`dc31137`）执行同一 `compileTestKotlin` 复现完全相同的 8 条错误；且 4 个报错测试文件与 `build.gradle.kts`、`gradle/` 与 dev **逐字节一致**，排除了合并因素。**决策**：用户选定「还原为 243 常规态」，以 commit `3b7b5af` 回退该 4 处录制期改动（平台回 `2024.3.7.1`、toolchain 回 `17`、移除 `bundledPlugin(jcef)` 与 `sandboxContainer` 覆盖）；还原后 `compileTestKotlin` BUILD SUCCESSFUL。**遗留**：dev 分支本身仍带该阻塞，后续需在 dev 侧自行处置（否则再合并会重复带入）。

### Wave 0 前置

- 计划文档提交 `f651015`（203 行）。
- 回滚锚点 tag **`backup/pre-r2dev-merge-20260911`** → `b632086`（已核 `git rev-parse`）；本 worktree 检出分支 `workbuddy/r2-backup-pre-dev-merge-20260910-346f4bf8` 同指 `b632086` 为第二锚点。
- 三处 worktree（目标 / r2 / dev）合并前均无未提交改动；`.workbuddy/` 已在 `.gitignore`，不污染提交。

### Wave 1 合入 r2

- `git merge feature/r2-competition-response --no-ff` → 合并提交 **`3ae4d1b`**，**零冲突**。
- 校验：`git diff --stat feature/r2-competition-response HEAD` = 仅计划文档 `1 file changed, 203 insertions(+)`，与预期完全一致。

### Wave 2 合入 dev

- `git merge dev` → **起手即复现预判的 2 文件 2 冲突块**（`docs/MEMORY.md`、`docs/变更记录.md`），源码与 bundle 侧全部自动合并成功。
- 处置：`git checkout -m` 两文件取回三方自动合并真实结果，再用脚本**只替换冲突块**（`theirs`（dev 的 `2026-09-11` 行）→ `ours`（r2 的 `2026-09-10` 两行）），未整表重写、未碰自动合并区。文件行数 214→211、273→270（各减 3 个标记行），冲突标记残留 0。
- 合并提交 **`7072028`**。
- **行级实证（整行全文口径，非截断比对）**：

  | 文件 | r2 行 | dev 行 | 结果行 | 并集 | 丢行 | 重复 | 超并集 |
  |---|---|---|---|---|---|---|---|
  | `docs/变更记录.md` | 190 | 185 | 199 | 199 | 0 | 0 | 0 |
  | `docs/MEMORY.md` | 47 | 47 | 50 | 50 | 0 | 0 | 0 |

  两文件「结果 == 并集」均成立，即**零丢行、零重复、零多余**。
- **留档（未处置）**：`docs/变更记录.md` 第 30–31 行存在日期倒序（`2026-09-09` 行后接 `2026-09-10` 行）。经比对，该倒序**在 dev 侧同样存在**（dev 版第 27–28 行），属 dev 固有状态，合并结果逐字继承。按 §四「自动合并区只做语义核验、不重写」原则未改动，以免造成与 dev 的永久内容分叉。

### Wave 3 语义清扫

| 核验项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| bundle en / zh 键 | 对称 | 553 / 553，双向差集 0 | OK |
| `plugin.xml` | 与 r2 逐字节同 | blob `d58ae1bb430ca39058c20a10a3c606cef2acade2` 两侧相同 | OK |
| r2 资产计数 | 8 / 4 / 13 | `supportsDeleteLimit` 8、`entityResolutionFailed` 4、`isWrapperVariable` 13 | OK（与 r2 基线逐项相等）|
| dev 资产计数 | 4 / 3 / 3 / 6 | `sequenceNumber` 4、`isModuleBasedLaunch` 3、`awaitPrewarmedTempFile` 3、`lineFragments` 6 | OK |
| `testgen` 残留 | 0 | 0 | OK |
| 版本号 | `2026.9.10` | `2026.9.10` | OK |
| CHANGELOG 段 | 含 9.10 / 9.9 | `## [2026.9.10]`、`## [2026.9.9]` 均在 | OK |
| r2 文档/测试资产 | 在位 | 弹窗克制红线、`SqlPreviewSpecMatrixTest`、`XmlMapParamSpecMatrixTest` 均在位 | OK |

- 补充：bundle 键计数需用**宽口径**（键名允许含连字符）——`feedback.category.sql-to-java`、`feedback.category.sql-log` 两个键不匹配 `[A-Za-z0-9_.]+`，窄口径会误报「551 vs 553」的假不对称。

### Wave 4 全量测试与构建

- `./gradlew test --no-daemon` → **BUILD SUCCESSFUL**，**293 测试类 / 3760 用例 / 0 失败 / 0 错误 / 6 跳过**（跳过为 Docker 条件测试），`tests=0` 陈旧结果文件 0 个。
- **首次全量 4 失败的真实归因（已排除合并因素）**：`DataSourceJdbcSmokeTest`（2）与 `DemoRecognitionTest`（2）均因缺少**同级 demo 工程**而失败，报错原文即 `demo 文件应存在: ..\mybatis-plus-sql-visualizer-demo\...` / `demo 项目 dataSources.xml 未找到任何数据源`。该工程在本机位于 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo`（dev worktree 的同级），而 WorkBuddy worktree 的上级目录下不存在。**处置**：在 `C:/Users/USER/WorkBuddy/Worktrees/mybatis-plus-sql-visualizer/` 建 junction 指向该工程后复跑该 2 类（`tests=2/2`、`skipped=0/failures=0/errors=0` 全过），随后带 junction 重跑完整全量取得上述单一权威绿灯数。
- **踩坑沉淀**：WorkBuddy worktree 下跑全量测试前需确认同级 `mybatis-plus-sql-visualizer-demo` 可用（junction 即可），否则恒有 4 个环境性失败。
- `./gradlew verifyPlugin --no-daemon` → **BUILD SUCCESSFUL**（12m50s，纯验证 10m44s），三目标均 **Compatible**：

  | 目标 | 结论 | scheduled-for-removal | deprecated | experimental |
  |---|---|---|---|---|
  | IU-243.28141.41 | Compatible | 1 | 3 | 10 |
  | IU-253.33514.17 | Compatible | 1 | 6 | 10 |
  | IU-262.9437.185 | Compatible | 1 | **46** | 10 |

  262 deprecated 由上一轮记录的 44 增至 46：明细为 `ReadAction.compute(ThrowableComputable)` 40 处 + 其他 6 处（`FileSaverDescriptor` ×3、`Messages.showChooseDialog`、`TextAttributesKey` 相关、`TransactionGuard.submitTransactionLater`、`DbPsiFacade.findElement`），增量归因于两侧后续提交新增的 `ReadAction.compute` 站点（r2 tip 66 → 合并后 69）。全部 warning 级，不阻断发布。
- 注：以上 verifyPlugin 计数是在「还原 243 常规态」后（构建基准 `2024.3.7.1`、toolchain 17）取得，与前几轮口径一致、可直接对比；若在 dev 的 262 录制配置下构建，计数口径不同，不可直接比对。
- `buildPlugin` 出产物：`build/distributions/mybatis-plus-sql-visualizer-2026.9.10.zip`（6.26 MB）。

### Wave 5 定稿与同步

- 提交序列：

  | 提交 | 内容 |
  |---|---|
  | `f651015` | 立项：本计划文档 |
  | `3ae4d1b` | merge `feature/r2-competition-response`（零冲突）|
  | `7072028` | merge `dev`（2 文档冲突并集）|
  | `3b7b5af` | 还原 243 常规开发态（回退 dev 录制期配置）|
  | 交付同步提交 | `docs/变更记录.md` 顶插交付行 + `docs/MEMORY.md` 变更记录行 + 本 §九 回填 |

- **不主动 push**（沿用前两轮约定）。
- runIde 真机抽验留人工：dev 面（日志行号 / 竖向排列 VALUES 行尾列名标注 / 预览加载速度）+ r2 面（冷启动首屏 / 承接 MyBatis Log Free 老用户文案 / 弹窗克制）。

## 附：预演命令与实测输出（可复现）

```bash
# 分叉量（实测）
git rev-list --left-right --count r2-backup-pre-dev-merge-20260910...feature/r2-competition-response  # 0  30
git rev-list --left-right --count r2-backup-pre-dev-merge-20260910...dev                               # 28 58
git rev-list --left-right --count feature/r2-competition-response...dev                                 # 31 31
git merge-base r2-backup-pre-dev-merge-20260910 feature/r2-competition-response  # b632086
git merge-base r2-backup-pre-dev-merge-20260910 dev                              # e64987e
git merge-base feature/r2-competition-response dev                               # ed7723f

# 冲突预演（临时 worktree，执行后已清理）
git worktree add --detach /tmp/mpsv-merge-check b632086
cd /tmp/mpsv-merge-check
git merge --no-commit --no-ff feature/r2-competition-response   # Automatic merge went well（可快进，无冲突）
git commit -m "tmp"
git merge --no-commit --no-ff dev                                # 冲突 2 文件：docs/MEMORY.md、docs/变更记录.md
git diff --name-only --diff-filter=U
git merge --abort
```

- 预演产物核对：bundle 553/553 键对称、版本 `2026.9.10`、r2 资产 8/4/13 与基线相等、`testgen` 残留 0。
