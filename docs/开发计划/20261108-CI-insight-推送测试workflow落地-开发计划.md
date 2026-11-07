# CI-insight：push-test workflow 落地 t-sql-insight（车道 2 / 阶段 0）

- 日期：2026-11-08（闲时任务 offpeak-6a4afce2 阶段 0）
- 基线 SHA：master `95e107b1849e58fdeb62d1e3927c424ac75319fc`（冻结）
- 车道登记：parallel_tasks `ci-insight` @ t-sql-insight 工作树
- 分支起点：`0b8ef457`（ahead 380 / behind 478，工作树干净）

## 侦察结论

- 该分支只有 build-plugin.yml（旧单 job 版）；push-test.yml 缺失。
- buildSrc：`testShardCount` 默认 5 ✓；`testFast` ✓；`fastBucketSliceCount` 无支持（no-op，两片各跑全桶，不红）；`testShardExcludeClasses` 支持 ✓（mpv-testing.gradle.kts:623）；`agentTestExclude` 无支持（no-op）。
- **`dev-support/ci/` 整目录缺失**（含 run-gradle-retry.ps1）——workflow 调用会 file-not-found，必须从基线带入。
- `dev-support/test-demo/setup-test-env.ps1` 在位 ✓（本分支世系自带）。
- agent 子项目 sub-projects/sql-visualizer-agent 在位；CI JDK 17 ✓；`.local/github-token.json` 在位 ✓。
- 工作树不在主会话可访问工作目录清单，文件工具可达性以实测为准（Read 已验通，Write/Edit 探针见实施记录）。

## 分阶段步骤

1. 从基线带入 `dev-support/ci/run-gradle-retry.ps1`（独立包装脚本，无仓内依赖）。
2. 从基线带入 push-test.yml，branches 写 [t-sql-insight]；nightly-nudge 加 `if: github.ref_name == 'master'`（该分支上恒 skip，保留结构与 master 一致，降低将来合并冲突面）。
3. 本地编译门禁：`classes testClasses`（后台）。
4. 编译过 → 提交 → `push-all.ps1 -Branch t-sql-insight`。
5. 后台轮询至 completed，四项判据同车道 1；workflow 配置红修 ≤3 轮。

## 验收判据

- push-test 在 t-sql-insight 首次真实触发，matrix 展开、gradle 认得任务名。
- 分支自身测试红绿单独登记（该分支 380 私有提交，测试形态与 master 已分叉，红属代码问题）。

## 待决项

- build-plugin.yml 对齐 master 多 job 版不在本车道范围（任务书只要求 push-test）——旧版手动门禁保留现状。
- 本车道只碰 .github/workflows 与 dev-support/ci，不碰 src。

## 实施记录

- 工作树写权限实测通过（Read/Write/Edit 均可用），无需降级方案。
- 步骤 1-2 完成：run-gradle-retry.ps1 与 push-test.yml 按基线 95e107b1 带入，branches 写 [t-sql-insight]，nightly-nudge 加 master 专属 if；YAML 校验通过。
- 本地编译门禁：`classes testClasses` BUILD SUCCESSFUL in 4m 22s（COMPILE_EXIT=0）。
- 提交 `04b6c05d`（push-test.yml + run-gradle-retry.ps1 + 本计划文件）；push-all.ps1 -Branch t-sql-insight 双仓推送成功（GitHub 走直连回落，令牌通路）。
- **CI 结论（run 42403761197，failure）**：四项配置判据全过（job 启动、matrix 展开 test×5+fast×2、checkout+编译过、gradle 认得任务名；nightly-nudge 按设计 skipped）——workflow 配置验证完成。测试红 19+3 例属分支自身/环境依赖，非配置问题：
  - O147RealAgentAcceptanceTest 族在 fast 腿照跑并红——insight 的 buildSrc 旧于 master，testFast 不认 -PtestShardExcludeClasses（master 的「同源尊重」管线是后加的）；车道 5 合并 master buildSrc 后自然排除。
  - EnhancedCaptureDefaultOnGuardTest / GiantFileBudgetTest / InsightDetailPanelLayoutTest×3 / InsightPresentationTest / InsightSummaryTest / SqlPreviewStatusBarWidgetTest——insight 线自身红或守卫前提过期，留车道 5 合并后裁决。
- 计划回填笔 4eaa5bc7（本地，未推——避免取消本轮 CI；随车道 5 合并提交一并推）。
