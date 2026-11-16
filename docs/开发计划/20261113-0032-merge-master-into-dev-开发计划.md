# 开发计划：master 并入 dev（v2026.11.12 整线反哺）合并轮

- **立项时间**：2026-11-13 00:32
- **任务来源**：用户指令——dev 分支合并主分支 master，解决冲突、全量测试并修复问题；构建校验（buildPlugin/verifyGate 类）本轮不跑。
- **工作树**：`.worktrees/dev-merge`（dev 分支，基线 `192f4e19b`）
- **状态**：实施中

## 一、任务背景

- merge-base：`0b1666030`（v2026.11.11 正式版准备）。
- master 侧独有 **178 笔**（v2026.11.12 整条线）：MBG 预览多条件还原与粘连修复（D 系列）、预览打开性能 T-PreviewOpenPerfM2（M1~M9 检索缓存/收范围/提前终止/dumb 跳过/EDT 违规修复）、schema 预热 60 秒去重窗口、参数名截断修复、v2026.11.12-beta.1~beta.4 与正式版发行批、CI 夜间腿本地化（L42/L43）。
- dev 侧独有 **332 笔**：F 队列功能批（F-01 类型感知字面量还原、F-02 参数可解析性检查、F-03 日志工具窗常驻、F-04 ShardingSphere 染色、F-05 JPA/Hibernate 归因、F-06 超大解析有界化/列头溯源/拼写提示、F-07 卸载清理钩子、F-08 历史项目维度、F-09 交互面超大 SQL 护栏、F-10 状态栏菜单化、F-11 上游 MyBatis 版本监听、F-12 一键重跑、F-13 列排序、F-14 CSV 无 BOM 开关、F-15 SQL Server 空间类型）。
- 两侧同触 `SqlPreviewPanel*`、`PluginSettings*`、`ExplainIndexAdvisor`、update 段与 messages properties，预计冲突可控但需逐 hunk 裁决。

## 二、试合侦察结论（git merge-tree --write-tree）

冲突 **13 个文件、32 个 hunk**：

| 文件 | hunk 数 | 侧向 |
|---|---|---|
| CHANGELOG.md | 1 | 双方 |
| docs/开发计划/20260916-1730-待完成开发计划全景盘点.md | 2 | 双方 |
| docs/开发计划/20261105-2350-闲时任务待办清单-开发计划.md | 1 | 双方 |
| src/main/.../config/PluginSettings.kt | 1 | F 队列开关 × master 新增项 |
| src/main/.../config/UpdateSection.kt | 3 | dev F-11 × master 更新链修复 |
| src/main/.../execute/DataSourceSelectorResults.kt | 4 | dev F 队列 × master D289（getName 回退） |
| src/main/.../mcp/McpSourceLocator.kt | 3 | 双方 |
| src/main/.../ui/SqlPreviewPanelExecution.kt | 1 | dev F-09 护栏 × master M7/onError 接线 |
| src/main/.../ui/SqlPreviewPanelParamPanel.kt | 1 | dev F-13/F-14 × master D 系列 |
| src/main/.../util/ExplainIndexAdvisor.kt | 10 | dev F-02 × master 解释链改动 |
| src/main/resources/messages/MpSqlVisualizerBundle.properties | 1 | 双方新增文案 |
| src/main/resources/messages/MpSqlVisualizerBundle_zh.properties | 1 | 双方新增文案 |
| src/test/.../convention/ExecTempFileEdtGuardTest.kt | 3 | dev 守卫 × master 511ad990a 线程契约跟进 |

另有大量自动合并文件（SqlPreviewPanel.kt、PreviewResult.kt、PluginUpdateRunner.kt 等），合并后需编译验证自动合并语义。

## 三、实施步骤

1. **逐 hunk 裁决 13 个冲突文件**（不整文件取一边）：
   - 源码类：保双方功能，裁语义——master 侧的性能/修复类改动（M 系列、D 系列、getName 回退）与 dev 侧的 F 队列功能叠加；同一行语义重叠时先读两边提交原文再裁决。
   - `PluginSettings.kt` / properties：双方新增项都保留（开关矩阵并集），key 命名撞车时以各自功能原名为准。
   - CHANGELOG.md / 变更记录 / 开发计划文档：两侧条目都保留，按时间序归位。
   - `ExecTempFileEdtGuardTest.kt`：以 master `511ad990a` 的线程契约口径为准，保留 dev 守卫新增断言并适配。
2. **合并提交**：消息按前例「merge: master 并入 dev——v2026.11.12 整线反哺（13 冲突逐 hunk 裁决）」，写文件 `-F` 提交（L4 纪律）。
3. **合并后编译**：`./gradlew compileKotlin compileTestKotlin`；若见整类 `NoSuchMethodError <init>/copy$default` 按 L21 先清产物重编（用 `clean`，禁手删 build/classes，L33）。
4. **全量测试**：跑 gradle 前先 `parallel_tasks.py check` + `register`；`./gradlew testShards` 后台全量落盘（L22：禁 `2>&1 | tail`，退出码 echo 进日志）。构建校验（verifyGate/buildPlugin/verifyPlugin）本轮不跑。
5. **红例定性三组对照**（S2/前例 f9341c3f 口径）：master 干净基线对照 × dev 合并前对照 × 合并后——区分「合并引入」与「分支既有/环境红」。
6. **修复合并引入的红**并定向复跑收口；平台 UI/延迟机制面（L13）只认全量读数。
7. **文档回填**：本计划册实施记录、CHANGELOG（合并提交哈希）、`docs/索引/开发计划.md` 索引行。

## 四、纪律红线

- 不 push、不合流回 master、不建 tag、不动 `update/update.json`。
- dev 工作树独占本车道；提交后 `git show --stat HEAD` 核对只含本车道文件（S5）。
- 全量读数必须落到逐例口径（suite 数 / 例数 / 失败 / 错误 / skip），不接受「全绿」二字。

## 五、验收标准

1. 13 个冲突文件逐 hunk 裁决完成，无遗留 `<<<<<<<`。
2. `testShards` 全量跑完，逐例读数在档；合并引入的红全部修复并定向复跑绿。
3. not run 条款如实列出（本轮：verifyGate/buildPlugin 类构建校验按用户指令不跑）。
4. 文档三件回填完成。

## 实施记录

### 2026-11-13 00:32~01:1x 合并实施

- 工作树 `.worktrees/dev-merge`（dev @ `192f4e19b`），`git merge origin/master` → 13 冲突文件全部逐 hunk 裁决，合并提交 **`a5f54c45a`**。
- **R45 语义簇（关键裁决）**：`explainStructuredRender` 默认值 dev=true（10-31 默认开案）× master=false（2026-11-11 用户定案「转正保留、维持默认关；dev 侧 G4 默认开案废止不追认」）——**按 master 定案取默认关**：
  - `PluginSettings.kt` KDoc + 默认值取 master；bundle 双语取 master 口径；
  - dev 侧 `ExplainStructuredDefaultOnGuardTest`（钉 true）删除，恢复 master 的 `ExplainStructuredDefaultOffGuardTest`，元守卫登记同步改回；
  - `PluginSettingsPersistenceTest` 往返用例回默认关口径；
  - 全景盘点 §〇 表 #13 取 master 版记录 + 拍板段补并轨注记。
- **搬移×演进三连**（dev 拆文件 × master 原地演进，函数体全部灌 master 最新版、结构保持 dev）：
  - `ExecTempFileFactory.kt`（+ master 5 个新诊断函数）；`DataSourceSelectorResults.kt` 签名演进（failureReason 参数）取 master、搬空区域保持 dev；
  - `SqlPreviewPanelExecutionExplain.kt`（22 函数）；`SqlPreviewPanelExecution.kt` 拆分结构保持 dev；
  - `McpSourceLocator.kt`：dev RenderStep 结构 + master iBatis 两档叠加（toOutcome/fail 包 `RenderStep.Direct`）。
- **守卫并轨**：`ExecTempFileEdtGuardTest` 以 dev 三文件内核为骨架，并轨 master M7 补强（nonModal / revalidate 位置 / 缺 revalidate 三规则）+ 回补 dev 重构时丢失的 refresh 分流（T-PrewarmWilDeadlock）与续接收口（failPrepareRequests/isPrepareActive）两规则，新增五态自证用例；`revalidateOnEdt`→`revalidate` 全量改名。
- `ExplainIndexAdvisor.kt` 取 dev 单源化版（master 仅注释差异，且 DM8 已随 T-Fr45DmGuard 走 TABLE 档）；`UpdateSection.kt`/`DataSourceSelectorResults` seam/CHANGELOG/计划册保双方。
- `update/update.json`/`preview.update.json` 为 master 发行状态顺流（与 origin/master 逐字节一致，与上轮合并 `192f4e19b` 同款处理）。

### 门禁读数（2026-11-13 收口，最终 HEAD `e6508b6e0`）

- **compileKotlin + compileTestKotlin**：全绿（合并态编译经 4 轮修复收口，`920c619ca`）。
- **testFast**：5339 例，**0 失败**，10 skip（`e6508b6e0` 实跑；首轮 12 红全部修复——守卫接缝/R51 接线/预算留痕/场景组登记/headless 尺度回退）。
- **testShard1 / testShard2 / testShard3 / testShard4 / testShard5**：逐桶显式跑齐，**全部绿**（shard2 含 OB 面板修复验证，`dialectName` 实参回补 `57ea2700a`；shard3/4 首曝的 R51/F-13 两红定性 dev 潜伏红已修，`9091fc5f8`；prune TOCTOU 假红修 `e6508b6e0`）。
- **log 包全量（`test --tests log.*`）**：2049 例绿（prune 夹具修正后含共跑态）。
- **testExternal**：32 例 1 失败 1 skip——`AcpSignatureCrossVersionTest` 红为**环境腿**（本机 IDEA 2025.3 的 AI Assistant 插件内容漂移，缺 `AcpIdeaMcpTransportProvider.class`，与合并无关；L43 口径）。
- **教训沉淀**：L52（testShards lane 停摆 + 桶画像缺口 18%——「全量」的部分桶假象）。

### not run 条款

- **verifyGate / buildPlugin / verifyPlugin / releaseGate**：按用户指令本轮不跑（构建校验）。
- **AcpSignatureCrossVersionTest**：环境腿未过（上述），代码侧无修复项；本机 AI Assistant 插件恢复携带目标类后自愈。
- **真机 GUI 走查**：本轮未做（合并轮纪律为全量桶收口；面板行为面建议下一轮真机验收时带看 OB 结构化面板与历史重跑图标）。
