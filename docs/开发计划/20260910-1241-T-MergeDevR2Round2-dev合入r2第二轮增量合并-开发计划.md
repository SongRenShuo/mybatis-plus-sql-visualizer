# dev 合入 feature/r2-competition-response 第二轮增量合并开发计划

- 日期：2026-09-10 12:41
- 分支方向：`git merge dev`（在 r2 worktree `F:/code/Java/Gitee/SongRenShuo/mpsv-r2` 执行，dev 合入 feature/r2-competition-response）
- merge-base：`e64987e`（dev 的 T-PerfBatch 立项提交，即上一轮 T-MergeDevR2 的合并锚点）
- 分叉规模：dev 独有 27 提交 / r2 独有 28 提交
- 前置侦察：`git merge-tree --write-tree r2 dev` 预演完成，**仅 2 个文档文件冲突**，已逐块过目
- 关联：上一轮同类合并 `docs/开发计划/20260909-2308-T-MergeDevR2-dev合入r2竞品应答分支-开发计划.md`（merge 9b477fe）
- 依赖：本计划排在上游「T-MergeMasterIntoDev（master 合入 dev，merge e6a42ff）」之后执行——dev 的这 27 提交里绝大部分来自那次合并

## 一、背景与两侧增量的真实分布

**dev 独有（27 提交）**，可归为三簇：

1. 演示 GIF 录制管线两笔（`record/` 脚本、`dev-support/`、经验总结文档）——r2 完全没有的新资产，纯新增目录。
2. **master 合入 dev 的整簇（本次合并的主体）**：T-VStack 三笔、T-LogRowNumber 两笔、T-RefreshDbSteal、T-ExecCopyFix、T-DepApi、T-PreviewFlash、T-AutoShowMaster、T-AutoShowRecover、T-PortLossBatch1 A~D、v2026.9.9 发行（版本号 + CHANGELOG 版本段 + 发行说明 + preview 清单），以及合并提交本身与两份文档。
3. 合并带进来的上游 assets（如 `docs/images/t-vstack-requirement.png`、`ConsoleReplayGuard.kt`、`CopyFeedback.kt` 等 master 侧新文件）。

**r2 独有（28 提交）**：竞品应答主线——冷启动组合拳与承接 MyBatis Log Free 老用户（plugin.xml 描述 / README / marketplace 自定义 HTML / 发布流程）、弹窗克制设计红线（`docs/规范/设计规范-弹窗克制红线.md`）、竞品翻车场景金样矩阵 X1~X8、N-9 测试资产真实断言增强、O-6 WrapperSearchService 服务链通道、O-8 字符串版 QueryWrapper 服务链三处缺陷修复（var 消费语义 / `service.query` 无预览 / DELETE 丢 last）、M55/M56 立项文档、Mybatis Log Ultra 对标调研报告、生成测试功能跟随 dev 一致下线。

两侧几乎不重叠：r2 的代码改动集中在 `WrapperSearchService` / `WrapperLocator` / `UpdateDeleteStatement` / `Dialect` / `SqlGenerator` / `SqlPreviewService` / `DatabaseSchemaResolver` / 矩阵测试，dev 本次带来的代码改动集中在 log 包 / 预览包 / gutter。因此**源码零冲突**。

目标：合并后 r2 拿到 dev 全部上游能力（含 v2026.9.9 发行面）、r2 自身竞品应答资产一项不丢、全量测试 0 失败、verifyPlugin 三目标 Compatible。

## 二、冲突预判（merge-tree 预演实测）

内容冲突 **2 文件 2 块**，均为「表格顶部插入行」：

| 文件 | 块数 | 性质 |
|---|---|---|
| docs/MEMORY.md | 1 | r2 侧 2 行（生成测试下线、上轮 T-MergeDevR2）vs dev 侧 14 行（master 合入簇），需时序并集 |
| docs/变更记录.md | 1 | 同上，r2 侧 2 行 vs dev 侧 14 行 |

两者都是同型冲突：两侧各自往「最新在上的表格」顶部插了自己那批行，并集后按日期倒序即可。

## 三、自动合并区关键结论（预演已核实，无需人工干预）

1. **CHANGELOG.md 自动全取 dev 版**。r2 侧自 base 起**逐字节未改**该文件（blob 与 base 同为 `27f3640`，CRLF），而 dev 侧在本轮把它改写为并集并归一 LF，三方合并直接采用 dev 版。**顺带效果**：上一轮识别出的 `CHANGELOG.md` CRLF 孤立异常（dev/r2 双 CRLF、master/基点 LF）在 r2 上就此消失，此后 master↔dev↔r2 三方合并不再有该伪冲突。
2. **plugin.xml 保留 r2 版**（合并结果与 r2 逐字节相同）——r2 的竞品应答描述文案（冷启动组合拳 / 承接老用户 / 弹窗克制）一字不丢；dev 侧自 base 起未改该文件。
3. **r2 的代码资产全部在位**：`Dialect.supportsDeleteLimit`（4 处）、`SqlPreviewService.entityResolutionFailed`（2 处）、`WrapperLocator.isWrapperVariable`（10 处）在合并结果中与 r2 侧计数一致。
4. **版本号走向 2026.9.8 → 2026.9.9**：base 与 r2 均为 2026.9.8，dev 因 master 的发行提交为 2026.9.9，合并取 dev 值。与 CHANGELOG 已含 `[2026.9.9]` 段、`build.gradle.kts` 已含 v2026.9.9 changeNotes 自洽。
5. **r2 独有的 docs 资产**（`规范/设计规范-弹窗克制红线.md`、竞品调研/对标报告、金样矩阵计划文档、marketplace 自定义页）dev 侧不存在，自动保留。

## 四、解决原则

1. 表格类冲突（本次两处）**一律并集去重 + 日期倒序**，不取边、不丢行。
2. 沿用任务一踩坑结论：**严禁按文件整取一边**。本次两处冲突都是「表格顶部一块」，若整取会直接把另一侧 14 行或 2 行的交付记录抹掉。
3. 自动合并区（尤其 plugin.xml / gradle.properties / 矩阵测试）只做**语义核验**，不重写。
4. 合并后必须验证 r2 的竞品应答资产与 dev 的上游能力**双向都在位**（第三节已给逐项计数法）。

## 五、逐块解决方案

### 5.1 docs/变更记录.md —— 表格行时序并集去重

r2 侧 2 行：`2026-09-10 生成测试功能 r2 跟随 dev 双线一致下线`、`2026-09-09 dev 分支合入 r2（T-MergeDevR2）`。
dev 侧 14 行：`2026-09-10` 四条（含本次 T-MergeMasterIntoDev 合并行、T-VStack·InThreshold、IDEA 性能分析复查、T-LogRowNumber 非主进程修复）、`2026-09-09` 若干（T-RefreshDbSteal / T-PreviewFlash / T-DepApi / T-ExecCopyFix / T-LogRowNumber / T-AutoShowRecover / T-PortLossBatch1 D / T-PortLossBatch1 A~C / T-AutoShowMaster）、再往下为两侧共有行。

定案：并集（r2 侧独有行按日期插入对应位置）、去重、日期倒序，表头与分隔行沿用 r2 侧（内容宽度差异，避免无谓改写）。

### 5.2 docs/MEMORY.md —— 同法并集

同一张「变更记录」表的两行 vs 十四行，处置与 5.1 完全一致。

### 5.3 自动合并区核验（不施工，只核对）

- `plugin.xml` = r2 版（第三节 2 已实测字节相同）。
- `gradle.properties` = 2026.9.9；`build.gradle.kts` changeNotes 含 v2026.9.9 段。
- bundle zh/en 键对称（合并后再跑一次对称性检查，防止两侧各自加键后出现只在一边的键）。
- r2 侧矩阵测试（`SqlPreviewSpecMatrixTest` / `XmlMapParamSpecMatrixTest`）与 dev 侧 log/预览新增测试共存，全量跑通即锁。

## 六、实施步骤

### Wave 0 前置（本计划）
- merge-tree 预演 + 2 冲突块分析；本计划文档提交入库。
- 备份：r2 当前位置（`b632086`）打本地分支 `r2-backup-pre-dev-merge-20260910`。
- 并行任务登记表：任务一登记项注销后以 `T-MergeDevR2Round2` / 独立沙盒重新登记。

### Wave 1 合并与冲突解决
- r2 worktree 执行 `git merge dev`，按第五节解决 2 处文档冲突。

### Wave 2 语义风险清扫
- 第六节核验项逐条过；重点：r2 矩阵测试与 dev 新增测试是否互相污染共享设置（`PluginSettings` 显示模式 / 行号 / 开关态），bundle 键对称，`testgen` 双线下线后无残留引用。

### Wave 3 全量测试与修复
- r2 worktree `./gradlew test` 全量（`--no-daemon`，独立沙盒）。
- 红线 0 失败。预期规模：r2 基线（合并前）量级 + dev 侧新增 ≈ 3700 上下。
- 已知假失败两类预案同任务一（K2J 陈旧类 / clean 首跑 worker 崩溃）。

### Wave 4 构建校验
- `./gradlew verifyPlugin` 三目标 Compatible + `buildPlugin` 出产物。

### Wave 5 定稿与同步
- merge 提交：`merge: dev into feature/r2-competition-response——master合入dev整簇（T-VStack/T-LogRowNumber/T-RefreshDbSteal/T-ExecCopyFix/T-DepApi/T-PreviewFlash/T-AutoShowMaster/T-AutoShowRecover/T-PortLossBatch1/v2026.9.9）+ GIF录制管线合入，2文档冲突并集 + R2竞品应答资产核验`。
- 文档同步：r2 的 `docs/变更记录.md` 顶插交付行、`docs/MEMORY.md` 变更记录行、本计划 §九 实施记录回填。
- 不主动 push。
- runIde 真机项留人工：r2 竞品应答面（冷启动首屏 / 承接老用户文案 / 弹窗克制）与 dev 上游面（行号 / 竖向排列 / 预览闪烁）各抽一项。

## 七、风险与回滚

- 回滚锚点：`r2-backup-pre-dev-merge-20260910`（= `b632086`）；中途废弃 `git merge --abort`。
- 最大风险不是冲突（仅 2 处文档表），而是**版本号与发行面的语义**：合并后 r2 版本变为 2026.9.9、CHANGELOG 含 `[2026.9.9]` 段。若 r2 另有独立发版计划，需在发版时重新定版本，本次不做额外处理（保持与 dev 一致，避免又一处 EOL/内容分叉）。
- 次风险：r2 竞品应答的 plugin.xml 描述与 dev 上游若在未来同时改动会再冲突；本次 dev 未动该文件，无风险。

## 八、验收标准

- [ ] 2 处文档冲突并集完成，无丢行、无重复行、日期倒序
- [ ] r2 侧竞品应答资产在位（plugin.xml 文案 / 弹窗克制红线文档 / 金样矩阵 / O-8 三处修复符号）
- [ ] dev 侧上游能力在位（行号 / 竖向排列 / 预览闪烁守卫 / 执行失败可见化 / 自动打开日志窗 / 竖向排列常量）
- [ ] 全量测试 0 失败
- [ ] verifyPlugin 243/253/262 三目标 Compatible
- [ ] 变更记录 / MEMORY / 计划文档三处同步

## 九、实施记录（回填）

- **Wave 0**：备份分支 `r2-backup-pre-dev-merge-20260910`（= `b632086`）已打；并行登记表由任务一的 `T-MergeMasterIntoDev` 注销后换 `T-MergeDevR2Round2` / `sandbox-r2merge` 登记。
- **Wave 1**：`git merge dev` 起手即复现预演的 **2 文件 2 冲突块**，与预判完全一致，源码零冲突。
  - `docs/变更记录.md`：并集 189 行（r2 176 ∪ dev 独有 13），去重、日期倒序、无丢行。该文件两侧非表格行内容**逐字相同**（实测），故可直接按「表头 + 并集行 + 表尾」重写。
  - `docs/MEMORY.md`：该文件非表格区域两侧**各有改动**（不是纯表格文件），因此不能沿用上一条的整表重写法——首次用「表头取 r2 侧」的脚本会把 dev 对非表格区的改动抹掉，遂改用 `git checkout -m` 恢复真实自动合并结果、只替换冲突块，并集后块内 6 行（r2 2 ∪ dev 4）。
  - 两文件最终与预演产物逐行比对：行数相同且工作树为预演的超集，确认自动合并区内容零丢失。
- **Wave 2 语义清扫**：bundle zh/en **548/548** 键完全对称、零重复键；`testgen` / `TestAssetGenerator` 全树零残留（双线下线彻底）；r2 竞品应答资产在位（`supportsDeleteLimit`、`entityResolutionFailed`、`isWrapperVariable`；`docs/规范/设计规范-弹窗克制红线.md`、`SqlPreviewSpecMatrixTest`、marketplace 自定义页）；dev 上游能力在位（`sequenceNumber`、`isModuleBasedLaunch`、`consoleAttachedToMe`、`ConsoleReplayGuard`、`awaitPrewarmedTempFile`）；`plugin.xml` 合并结果与 r2 逐字节相同。
- **Wave 3**：全量 `./gradlew test --no-daemon` **3720 tests / 0 failures / 0 errors / 6 skipped 绿**，289 个测试类（无 `tests="0"` 陈旧结果文件）。较 dev 侧多 3 类 / 27 例，为 r2 竞品应答独有测试。
- **Wave 4**：`./gradlew verifyPlugin --no-daemon` **BUILD SUCCESSFUL**（9m22s），三目标报告 `verification-verdict.txt` 均 **Compatible**，计数与 dev 侧合并后完全一致（同源代码面）：

  | 目标 | 结论 | scheduled-for-removal | deprecated | experimental |
  |---|---|---|---|---|
  | IU-243.28141.41 | Compatible | 1 | 3 | 10 |
  | IU-253.33514.17 | Compatible | 1 | 6 | 10 |
  | IU-262.9437.185 | Compatible | 1 | 44 | 10 |

  均为 warning 级，不阻断发布。`buildPlugin` 出产物 `build/distributions/mybatis-plus-sql-visualizer-2026.9.9.zip`。
- **commit**：合并提交 `e1471fc`。
- **未做**：不主动 push；runIde 真机抽验留人工（r2 面：冷启动首屏 / 承接老用户文案 / 弹窗克制；dev 面：行号 / 竖向排列 / 预览闪烁）。
- **版本号说明**：合并后 r2 版本由 2026.9.8 变为 2026.9.9（随 dev 的 v2026.9.9 发行面）。r2 若另有独立发版计划，发版时需重新定版本号，本次不做额外处理。
