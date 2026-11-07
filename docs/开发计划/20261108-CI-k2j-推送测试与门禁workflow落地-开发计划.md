# CI-k2j：push-test 落地 + build-plugin 对齐 master（车道 3 / 阶段 0）

- 日期：2026-11-08（闲时任务 offpeak-6a4afce2 阶段 0）
- 基线 SHA：master `95e107b1849e58fdeb62d1e3927c424ac75319fc`（冻结）
- 车道登记：parallel_tasks `ci-k2j` @ k2j 工作树
- 分支起点：`aa004fab`（ahead 665，工作树干净）

## 侦察结论

- 分支只有旧单 job 版 build-plugin.yml；其「准备测试 demo」步骤引用的 `dev-support/test-demo/setup-test-env.ps1` **在本分支树里不存在**——现行门禁本就带不起来，对齐属修复。
- **`dev-support/ci/`、`dev-support/test-demo/` 两目录缺失**；k2j 的 src/test 有 Demo* 用例硬断言 demo 存在 → 两目录都要从基线带入。
- buildSrc：`testShardCount` 默认 5 ✓；`testFast` ✓；`fastBucketSliceCount` 无支持（no-op）；`testShardExcludeClasses` 支持 ✓。
- **verifier 目标集与 master 不同**：k2j 是 243/253/262（ideDir `idea-2026.2.3-win`），master 是 243/253/263（idea-2026.3-win）——门禁矩阵必须用分支自己的目标。
- **k2j 无 `verifyMarketplaceVerdict<tag>` 任务、无 `dev-support/verifier-cli-version.txt`**；有 `:verifyMarketplace` 聚合任务与 `:verifyGate`（支持 -PverifyIdes/-PverifyOnly）→ marketplace 腿改为单 job 跑聚合任务，参数化差异写明。
- CI JDK 17 ✓；`.local/github-token.json` 在位 ✓。

## 分阶段步骤

1. 从基线带入 `dev-support/ci/run-gradle-retry.ps1` 与整个 `dev-support/test-demo/`（含 demo-revision.txt 钉版）。
2. 从基线带入 push-test.yml：branches [feature/kotlin-to-java]；nightly-nudge 加 `if: github.ref_name == 'master'`。
3. build-plugin.yml 以 master 多 job 版为底重写，分支差异参数化：
   - verify 腿 matrix 用 243/253/262 + 分支自己的 ideDir 名；
   - marketplace 腿去掉三 tag 矩阵与 verifier-cli 播种/按目标 provisioning，改单 job 跑 `:verifyMarketplace`（其依赖的发行包走 build-outputs 工件）；
   - gate 聚合、build/verifyplugin/test/publish 作业保持 master 结构。
4. 本地编译门禁：`classes testClasses`（后台）。
5. 提交 → `push-all.ps1 -Branch feature/kotlin-to-java` → 后台轮询；配置红修 ≤3 轮。
6. 门禁能力验证：派发 master 的 build-plugin.yml `-f mode=gate -f branch=feature/kotlin-to-java`（与 push-test 错开，阶段 3 执行）。

## 验收判据

- push-test 在 feature/kotlin-to-java 首次触发，四项判据过。
- gate 派发可启动且各 job 用的任务名在本分支都存在（buildPlugin/verifyPlugin/verifyGate×3/verifyMarketplace/testShard1-5+fast）。

## 待决项

- master 的 verifyMarketplaceVerdict 分 tag 判定在 k2j 缺失：本车道用 `:verifyMarketplace` 聚合等价替代；若 k2j 门禁阶段需要逐 tag verdict，属 k2j 端口同步范围（车道 6 斟酌），不在 CI 车道补 buildSrc。
- demo 钉版取基线的 demo-revision.txt（k2j 测试形态是端口翻译件，若 Demo 断言与新版 demo 有漂移，以 CI 读数为准再单独调）。

## 实施记录

（待补）
