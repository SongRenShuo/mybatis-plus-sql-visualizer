# GitHub Actions 门禁 matrix 并行改造（2026-11-05）

一句话结论：把单 job 串行门禁拆成 build + verify×3 + test×5 后，CI 总墙钟从 30~40 分钟降到 35 分钟（含首次下载新 IDE 版本），九个 job 失败互不连坐；但**它仍然不是本地发版的最快路径**，因为 CI 的 `build` 段要 21.7 分钟，而本地增量编译只要 13 秒。

## 一、时间到底花在哪（改前实测）

本地 `releaseGate` 一条命令 51m01s：

| 段 | 耗时 | 说明 |
|---|---|---|
| `buildPlugin` | 13s | 增量，不是瓶颈 |
| `verifyGate`（三目标） | 288s 验证 + **285s 故意延迟** | 那 285s 是刻意的错峰 |
| `testShards`（五片） | 约 30 min | 自适应把并发降到 2 路 = 3 轮 |
| `verifyMarketplace` | 789s（13 min） | 源码注释写明是「releaseGate 关键路径」 |
| `e2eFakeIde` | 约 5 min | 真机级探针 |

CI 上（windows-latest，2 核 7GB）同一套串行跑，`testShards` 一步就被卡了 `timeout-minutes: 25`，而这正好是它的临界值。

## 二、改造做法

`buildSrc` 侧先解决一个前提：`-PverifyIdes` 原来只有 `smoke`/`minimal`/全量三态，matrix 拿不到单目标。加了三档单版本（`243`/`253`/`262`），且**两处都要改**——`pluginVerification.ides`（供 `verifyPlugin`）与 `verifyGate` 的 `targets`。漏一处就会出现「verifyPlugin 验三个、verifyGate 验一个」的错位。

workflow 拆成四个 job：

| job | 内容 |
|---|---|
| `build` | 打包 + `verifyPlugin` + `verifyMarketplace` + 上传编译产物 |
| `verify`（matrix 3） | 每 IDE 版本一个 job，`-PverifyIdes=<ide> -PgateStaggerMs=0` |
| `test`（matrix 5） | 每片一个 job，`testShard<N>` |
| `publish` | `needs: [build, verify, test]`，release 模式三者全绿才发 |

`-PgateStaggerMs=0` 是必须的：那 285s 错峰原本是为「verifyGate 与 marketplace 抢同一台 runner」准备的，matrix 后每个 job 独占一台，错峰纯属浪费。

## 三、四个坑（都花了实跑轮次才发现）

### 1. `verifyPlugin` 的 `OVERRIDE_ONLY_API_USAGES` 只在 CI 暴露

`build` 段是 `:buildPlugin :verifyPlugin`，而本地 `releaseGate` 只跑 `verifyGate`。后者用 `-ignored-problems` 豁免清单，前者按 `failureLevel` 判、**不读豁免**。于是同一个 jar，本地绿、CI 红。

命中的是 `ChooseDataSourceTakeoverGroup.delegateChildren` 调用标了 `@ApiStatus.OverrideOnly` 的 `ActionGroup.getChildren(e)`。

处置：把 `OVERRIDE_ONLY_API_USAGES` 也移出 `failureLevel` 失败集（与既有四类同理由——兼容判定交 `verifyGate`），报告仍逐行列出可查。

**教训**：CI 不是本地门禁的复制品，是另一套检查面。本地看不到的失败，一定是某个只在 CI 跑的任务。

### 2. `verifyGate` 在配置期就要 verifier-cli，而跨 job 的 gradle 缓存不可靠

报错是 `Could not create task ':verifyGate' > verifier-cli jar 未在 gradle 缓存找到`。

`verifyGate` 是 verifier-cli 直驱任务，它在**配置期**就从 `caches/modules-2/.../verifier-cli` 找 jar，找不到直接拒绝建任务。而这个 jar 是 `verifyPlugin` 下载的。

build job 虽然跑过 `verifyPlugin`，但缓存保存失败了——日志里一片 `Failed to save cache entry`，多 job 并发写同一 key。verify 是另一台 runner，拿不到。

处置：verify job 里先跑一次 `:verifyPlugin -PverifyIdes=<ide>` 预热，再跑 `verifyGate`。每个 verify job 多约 3 分钟，但仍是并行。

### 3. 产物上传用默认 `if: success()` 会连坐九个下游 job

run#1 里 `build` 的 `verifyPlugin` 失败 → `上传编译产物` 被 skipped → 九个 matrix job 全部「下载编译产物失败」→ 分片与兼容门禁全 skipped，`testShards` 一片都没跑。

产物在 `buildPlugin` 成功时就已经有了，跟 `verifyPlugin` 成败无关。

处置：`if: ${{ !cancelled() && inputs.mode != 'publish' }}`。这样即使 build 红，测试与兼容验证照常跑完，能拿到「测试绿不绿」这个独立信息。

### 4. 2 核 runner 会放大时间敏感的全局态测试

```
SqlCaptureGateSniffTest    > 接管期间控制台链不产出条目
SqlErrorLineBridgeTest     > 接管期桥链路整体短路
```

run#3 与 run#4 精确复现同 2 例，不是 flaky。

根因在 `SqlCaptureGate.bridgeSuppressed() = paused.get() && !CaptureActivityFallback.isActive()`。`CaptureActivityFallback` 按「让位持续 ≥10s 且控制台仍有 SQL 行」自动解封控制台桥，而这两个用例恰好要喂进真实 SQL 行。

- 本地 6 核：设 `paused=true` 到断言只要几百毫秒，`< 10s`，fallback 不激活 → 绿
- CI 2 核：跑到断言时往往已过 10s → 解封 → 照常产出 → 断言崩

处置：两个类各加 `@Before` 前提隔离（`resetForTest()` + `enabledOverride=false`，`evaluateConsoleLine` 首行即 `if (!enabled()) return false`），`@After` 再 `resetForTest()` 清注入点。

反向验证：把 `enabledOverride=false` 换成 `thresholdOverrideMs=1L`（立即激活）→ `SqlCaptureGateSniffTest` 恰 1/4 红，红的正是「嗅探态_零条目产出」；恢复后 2 类 11 例全绿。

**教训**：只有慢机器才把这类问题放大出来。以后凡是用「持续 N 秒」做判据的全局态，测试都要注入显式开关，不能依赖「跑得够快」。

### 5. 换 IDE 版本后先确认 transforms 目录名（这次运气好）

`ideDir("idea-2026.2.4-win")` 是照 `idea-2026.2.3-win` 的格式推的。IPGP 对 Ultimate 不同大版本的命名不统一（243 是 `ideaIU-2024.3.7.1-win`，带 `IU`；2026.x 是 `idea-2026.x.y-win`，不带）。这次推对了，verify 三目标都成功下载验证。

如果推错，报错是「IDE 发行目录 X 未在 transforms 找到」。正确做法是：先跑一次 `verifyPlugin -PverifyIdes=<目标>` 让它下载，再看实际目录名改代码，别猜。

## 四、改后读数

| run | 总墙钟 | build | verify ×3 | test ×5 |
|---|---|---|---|---|
| #1 | 25.2 min | ❌ verifyPlugin | 连坐 | 连坐 |
| #3 | 45.0 min | ✅ 24.0 | ❌ verifier-cli | 4 绿 1 红 |
| #4 | 37.1 min | ✅ 21.8 | ✅ 8.6/13.0/14.1 | 4 绿 1 红 |
| **#5** | **35.0 min** | ✅ 21.7 | ✅ 8.8/9.6/9.8 | ✅ 9.2/10.2/10.8/12.0/13.2 |

本地 `releaseGate` 是 51 分钟，CI 35 分钟——**快了 16 分钟，但代价是 9 台 runner 的额度**。

## 五、什么时候不该用 CI

CI 快的原因只是「三目标与五片并行」，它的 `build` 段 21.7 分钟（冷编译 + 下载 IDE + clone demo）吃掉了大部分收益，而本地增量编译只要 13 秒。所以：

- 日常发版：**走本地**。加上「零代码改动时可复用分片读数」的判据，最快 10 分钟出包。
- 需要离开机器、或要干净环境排除本机缓存假绿：才 dispatch CI。

## 六、改 CI 时的照抄清单

1. `-PverifyIdes` 单版本取值要在 `buildSrc` 两处都加（`pluginVerification.ides` 与 `verifyGate` targets）
2. 每个 matrix job 都要重复 checkout / JDK / 配置下载环境 / `setup-gradle`——workflow 不能复用 steps
3. verify job 必须自己跑一次 `:verifyPlugin` 预热 verifier-cli
4. 产物上传一律 `if: !cancelled()`，别让单点失败连坐
5. 矩阵并行时 `-PgateStaggerMs=0`，那 285s 错峰是为共享 runner 准备的
6. 换 IDE 版本先让 `verifyPlugin` 下载一次，再看 transforms 实际目录名
7. 用时间阈值做判据的全局态，测试里注入显式开关，不依赖跑得快
8. 每轮都取 `jobs` API 的**逐 job 耗时**，别只看总墙钟——才能看出是哪一段吃掉收益
