# T-TestShardParallel 全量测试分片真实并行与耗时压缩 — 开发计划

- 日期：2026-10-20 18:43
- 任务编号：T-TestShardParallel
- 来源：用户 2026-10-20「优化下全量测试的时间，现在跑测试太耗时了」
- 验证基线：master `1870b41d`（dev 全量 8028/0/0/9 的同一棵树）
- 性质：构建/测试基础设施优化。不改生产代码、不改测试断言与分片语义；串行 `testShards` 保留为权威兜底。

---

## 一、实测诊断（先量再改）

### 1.1 15 分钟的构成

`./gradlew testShards --no-configuration-cache --continue` = **15m11s**：

- 构建段约 85s：`compileKotlin` + `jar` + `composedJar` + 5×`prepareSandbox_testShardN`。
- 测试段 **826s**：5 个分片**严格串行**，START/END 首尾相接——shard1 0–168.5s、shard2 168.6–336.9s、shard3 337.0–498.5s、shard4 498.6–673.7s、shard5 673.8–826.2s。

### 1.2 根因

5 个分片 Test 任务都注册在 **root project**（`buildSrc/src/main/kotlin/mpv-testing.gradle.kts` 的 `intellijPlatformTesting.testIde` 块）。Gradle 的 `org.gradle.parallel=true` 只并行**不同项目**的任务，同项目任务串行执行。设计注释里的「一命令真并行」从未生效；5 片只起到「时长均衡 + 结果分目录」的作用，墙钟等于全部测试时间之和。

### 1.3 测试时间分布

732 个测试类合计 769s（与 826s 差值为 JVM/框架开销）。长杆：

| 类 | 耗时 | 用例数 |
|---|---|---|
| `log.O147HistoryUpgradeTest` | 80.4s | 26（其中 5 个 revision 用例各约 16s） |
| `reverse.JavaCodeGeneratorTest` | 18.5s | 45 |
| `ui.SqlPreviewPanelTest` | 18.4s | 29 |
| `ui.SqlPreviewPanelParamSchemesTest` | 16.9s | 26 |
| `ui.SqlPreviewPanelDisplayEditorTest` | 16.4s | 31 |

Top20 占 39%，其余 712 类占 61%——没有「砍一个类就大幅提速」的空间，**并行是唯一数量级杠杆**。

---

## 二、已证伪的两条快路（附读数）

### 2.1 分片内多 fork（`maxParallelForks=4`）——否决

`testShard1 -PtestShardForks=4`：**1686 例 / 362 失败**。多 fork 共享同一个 `build/test-sandbox/shard1/config_testShard1`，并发写坏插件配置/全局态；与构建脚本注释里「多 fork 共享默认 test 沙盒…191 失败」同根因。已还原实验改动。

### 2.2 两个独立 Gradle 进程并发跑分片——机制可行，但暴露两个阻塞

做法：先单构建预热 `testClasses composedJar prepareSandbox_testShard1..5`，再用两个独立 `--project-cache-dir` 的 Gradle 进程并发跑 shard1/shard2，用 `-x` 排除所有写共享产物的任务。

- **机制成立**：shard2 **3m15s 全绿**，其 START 比 shard1 晚 17.5s（真实重叠）。
- 阻塞 1：并发构建会**同时编译 buildSrc**，`buildSrc:compileKotlin` 打包同一 `buildSrc/build/classes` 冲突（`Could not get file mode …`）。→ 预热阶段必须先跑一次 `buildSrc:classes`。
- 阻塞 2：`AgentManagerPremainReattachTest.test巡检随暂停通道驻留_清空后复位` 在并发下失败——测试级共享状态（agent 全局态），不是沙盒目录问题。
- 阻塞 3：内存。首轮 shard1 测试 JVM OOM（当时 Commit 57.8/68.7GB、余量 11GB）；清理后余量 16GB 时复跑未再 OOM，但余量阈值需要闸门。

**结论：不能直接把 5 片改成并发跑**。测试套件存在并发共享态，直接上会引入 flake；必须先做「并发不安全类」隔离。

---

## 三、方案（分阶段，P0/P1 是主线）

### P0 并行运行器（不改生产/测试代码）

1. 预热（单构建）：`./gradlew buildSrc:classes testClasses composedJar prepareSandbox_testShard1..N --no-configuration-cache`。
2. 并发：每片一个 Gradle 进程，独立 `--project-cache-dir`、独立日志/退出码；`-x` 排除所有写共享产物的任务（compile*/process*/classes/testClasses/jar/composedJar/embedAgentJar/buildPlugin/patchPluginXml/generateManifest/instrument* 与 agent 子项目同名任务）。
3. 内存闸门：启动前读 Windows Commit Limit/Committed；按「每路约 6GB 余量」决定并发路数（5→3→2→串行自动降级），不足时直接走串行 `testShards`。
4. 结果聚合：各工作树的 `build/test-results/testShardN/*.xml`（按 `.round-start` mtime 过滤本轮），输出与 `testShards` 同口径的 tests/failures/errors/skipped 汇总。

### P1 并发不安全类隔离

1. 维护 `dev-support/test-shard-serial-classes.txt`（首批已知：`AgentManagerPremainReattachTest`）。
2. 分片计划剔除清单内类；这些类由串行任务（如 `testSerialQuarantine`）单独跑，跑完再与并集合并。
3. 每轮并行跑把失败类名打印为「候选隔离项」，人工确认后入清单——不做自动拉黑（防把真回归当 flake 屏蔽）。

### P2 长杆优化（可选，与并行独立）

`O147HistoryUpgradeTest` 五条 revision 用例各 ~16s（写/读 `MAX_REVISIONS` 条超大修订 × 全部 reader）。评估共享夹具或减少重复读取；目标 −30~40s。收益有限，排在 P1 之后。

---

## 四、实测结果（2026-10-20 实施后）

| 配置 | 端到端墙钟 | 测试总数（shard+隔离类） | 备注 |
|---|---|---|---|
| 串行 `testShards`（基线） | **911s（15m11s）** | 8028/0/0/9 口径 | 原权威门禁 |
| 并行 3 路 × 3 片（验证） | 312s | 4901/0/0/6 + 隔离 16 | 只跑 3 片，非全量 |
| 并行 4 路 × 5 片（稳态，权威） | **499s（8m19s）** | 7997/0/0/8（已含隔离类 16）+ external 31/0/0/1 = **8028/0/0/9** | 降幅约 45% |

- 稳态轮：`lane plans: 1 distinct`，5 片 exit 全 0，testExternal 31/0/0/1，agent 单测 exit 0；四轮连续跑 SHARD TOTAL 恒为 7997（隔离类另 16），类并集 731（= 分片计划全集）。
- 口径残留已销案（2026-10-20 补验）：**并行与串行逐类 parity 完全一致**。同树串行 `testShards` 复跑 902s，官方聚合 `SHARD TOTAL: tests=8028 failures=0 errors=0 skipped=9`、计划类全集 732/732 均有 XML；直接按 XML 求和：串行分片 7997/8 + external 31/1 = 8028/9，并行分片 7981/8（剔除隔离类）+ 隔离类串行补跑 16/0 + external 31/1 = 8028/9。逐类比对 731 个分片类计数完全相同，隔离类 16 个用例名逐一相同。此前写的「8013 vs 8028 差 15 例」是**记账重复**：把已计入 SHARD TOTAL 的隔离类 16 又加了一次；比对脚本里出现的「该类 32」则是把并行轮遗留在 `build/test-results/test` 的 XML 也扫进了串行侧。无漏跑、无重复执行。
- 冷态轮（当轮改了 `buildSrc` 源码）：786s——首批 4 路并发 in-process 重编 buildSrc 抢 6 核，多出约 2.5 分钟一次性开销；稳态轮该开销消失（prep 约 30s）。
- 分片计划一致性：并行轮开跑前把上一轮 `build/test-results` 冻结成快照（`-PtestShardWeightRoot`），全部 lane 读同一份权重；运行器对每 lane 打印的 `duration-balanced` 行做 `distinct == 1` 断言，不一致直接判失败。修复前实测两轮总数 7949 vs 8119（各 lane 各自吃到不同的本地样本 → 重复/漏跑）。

## 五、验收标准（原始预期，保留对照）

| 路数 | 原预期 | 实测 |
|---|---|---|
| 串行（现状） | 15m11s | 911s |
| 2 路 | ≈9 min | —（2 片验证 3102/0/0/2，墙钟 4.4 min 仅分片段） |
| 3 路 | ≈7.5 min | 312s（3 片） |
| 5 路 | ≈5.5 min | 499s（4 路自动闸门，5 片全量） |

- 并行跑 0 失败、类并集与计划全集一致（731）——已满足。
- **反向验证**：①摘掉内存闸门 → 构造每路 100GB 预算（lanes=1）必须回落串行：已验（打印「内存余量不足两路，回落串行 testShards」并进入串行分支）；②摘掉串行隔离 → 2026-10-20 21:40 临时移出隔离类后尝试复跑，被并行登记检查硬拦（另一会话 `sync-k2j-496` 活跃至 22:25，按定例 check 非零必须硬停），本轮未取得读数；隔离依据仍为前轮战役的并发失败实测（§2.2），该轮若复现仍按回归排查而非默认屏蔽；③预热去掉 buildSrc 隔离 → 3 路真并发复现 `buildSrc:compilePluginsBlocks FAILED` / `Daemon compilation failed`（shard1/2/4 exit=1），加 `-PmpvBuildSrcDir` 隔开后同配置全绿：已验。
- 串行 `testShards` 语义不变，继续作为发布门禁权威读数。

---

## 六、实施记录（2026-10-20）

改动（未提交，均在 master 工作树）：

- `buildSrc/build.gradle.kts`：新增 `-PmpvBuildSrcDir`，把 buildSrc 的 `layout.buildDirectory` 指向每 lane 独立目录（缺省 `build`）。
- `buildSrc/src/main/kotlin/mpv-testing.gradle.kts`：
  - `-PtestShardExcludeClasses`：分片计划剔除并发不安全类（缺省空集）。
  - `-PtestShardWeightRoot`：权重样本根目录（并行轮指向冻结快照）。
  - `-PtestShardIgnoreLocalWeights`：只吃入库画像的退路（冷树无快照时）。
- `dev-support/test-shards-parallel.ps1`：并行运行器。内存闸门（Commit 余量 / 3GB 每路）、`cmd /c` 单命令行、`.exit` 文件读退出码、`Get-Process` 判活、权重快照、计划一致性断言、`-x` 共享产物排除表、隔离类串行补跑、testExternal/agent 与分片并行。
- `dev-support/test-shard-aggregate.py`：新增 `--quarantine-dir`，隔离类计数计入 SHARD TOTAL（对齐官方口径）。
- `dev-support/test-shard-serial-classes.txt`：首批隔离清单（`AgentManagerPremainReattachTest`）。
- `.gitignore`：忽略 `buildSrc/build-lane*/`。

用法：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\test-shards-parallel.ps1
# 可选：-Lanes N / -ShardCount N / -SkipPrep / -UseDaemon
```

已知边界：

- 首次跑或改了 `buildSrc` 后的一轮会慢约 2~3 分钟（各 lane 首次编译自己的 buildSrc）；之后稳态约 8 分钟。
- 分片计划用「上一轮冻结样本」；冷树无样本时退到入库画像，均衡度较差（实测 shard4 +50%，端到端 852s）。
- 并发跑会让单类耗时膨胀 15~25%，聚合的失衡告警会因此触发；判均衡看同一配置的连续两轮。
- 发布门禁仍用串行 `testShards`；并行运行器是本地提速工具，未接入 releaseGate。

