# CI-dev：push-test workflow 扩展 dev 分支（车道 1 / 阶段 0）

- 日期：2026-11-08（闲时任务 offpeak-6a4afce2 阶段 0）
- 基线 SHA：master `95e107b1849e58fdeb62d1e3927c424ac75319fc`（冻结，本轮 master 再前进不追）
- 车道登记：parallel_tasks `ci-dev` @ dev 工作树
- dev 起点：`d0b45a97`（ahead 326 / behind 275，工作树干净）

## 侦察结论

- dev 的 push-test.yml 落后 master 69 行：缺 nightly-nudge job、`actions: write` 权限、fast 两片切分矩阵（T-CiFastSplit）、6 片回退注释。
- dev buildSrc：`testShardCount` 默认 5 ✓；`testFast` 任务在 ✓；`fastBucketSliceCount` 无支持——`-P` 属性无人读＝no-op，两片各自跑全量快桶，只浪费 runner 分钟不会红（车道 4 合并 master buildSrc 后自然消除）。
- `testShardExcludeClasses` 支持 ✓（mpv-testing.gradle.kts:385 单一解析点）；`agentTestExclude` 无支持（no-op，dev 的分片不含 agent 测试则无事）。
- `dev-support/ci/run-gradle-retry.ps1` 与 `dev-support/test-demo/setup-test-env.ps1` 均在位 ✓；`.local/github-token.json` 在位 ✓。
- CI JDK 17 ✓；本地 JAVA_HOME=ms-17.0.19。

## 分阶段步骤

1. 从基线取 master 版 push-test.yml 覆盖 dev 副本（`git checkout 95e107b1 -- .github/workflows/push-test.yml`）。
2. `on.push.branches` [master] → [master, dev]。
3. nightly-nudge 加 `if: github.ref_name == 'master'`（夜间兜底是 master 专属语义；dev 推送不应派发夜间轮，注释口径也只写 master）。
4. 本地编译门禁：`classes testClasses`（后台，登记表在册）。
5. 编译过 → 提交（信息走 -F 文件）→ `push-all.ps1 -Branch dev`。
6. 后台轮询 Actions 至 completed；首轮判据四项：job 能启动 / matrix 能展开 / checkout+编译能过 / gradle 认得任务名。测试红属代码问题另行登记。
7. workflow 配置红 → 读日志修后重推，上限 3 轮。

## 验收判据

- push-test 在 dev 分支真实触发（run 落在 dev head SHA 上）。
- test×5 + fast×2 + nightly-nudge(skipped) 各 job 按预期启动/跳过。
- 四项配置判据全过；测试红绿单独登记读数。

## 待决项

- fast 两片在 dev 上重复跑全桶（约 2×300s runner 分钟）：接受，不单独修——车道 4 合并后 buildSrc 补上切分支持。
- dev 的 build-plugin.yml（落后 74 行）与 nightly-full.yml（落后 332 行）本车道不动，留给车道 4 合并裁决。

## 实施记录

（待补）
