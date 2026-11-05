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
9. 任何「需要 verifier-cli 的独立 job」都要复制第 3 条（本轮 marketplace 就是漏了它才红）
10. 聚合门禁必须把 `skipped` 判红——GitHub 里被 skip 的 job 不会让整轮变红
11. 不要为了跳过重编译把项目级 `.gradle` 塞进工件（见 §七.3 的实证失败）

## 七、第二轮提速：从 27.8 到 19.4 分钟（2026-11-05 下午）

第一轮把串行拆成并行后，`build` 成了墙钟起点。逐段读数显示它 13.3 分钟里有 4.8 分钟是 `verifyMarketplace` 串行跑（三目标 2m41s / 4m06s / 4m17s），而九个下游 job 全在等它。

### 1. 把 marketplace 拆成独立 job（build 13.3 → 7.3）

`verifyMarketplace` 只依赖 `buildPlugin` 产出的发行包，与 `verifyPlugin` 无关。拆出去后它与 verify×3 / test×5 三路并行，各占一台 runner。**注意它也要预热 verifier-cli**——第一次拆出去时照抄了 verify 的结构却漏了这一步，直接红在 `Could not create task ':verifyMarketplace262'`（与 §三.2 同一条坑）。

### 2. 聚合门禁：skipped 不能当通过

GitHub 里被 skip 的 job 不会让整轮运行变红，而 verify / marketplace / test 都带 `if: !cancelled()`。一旦某条腿因条件求值意外为假、matrix 写错或 needs 链断掉而被 skip，运行结论照样可以是绿的——等于「没验也过」。新增 `gate` job 逐条读 `needs.<job>.result`，`success` 之外一律判红并点名；`publish` 的 release 判定改为只看 `gate` 一条。

**反向验证**（门禁类改动必须做）：把 workflow 里那段脚本原文抽出来，喂四种输入跑 pwsh——四腿全 `success` 退出 0；`test=skipped`、`verify=skipped`、`build=failure` 各自退出 1 并点名对应腿。绿路径由 run#10 在 GitHub 上真跑证明（`gate` success），红路径由同一文本反证。只有绿路径的读数不算数：一个永远绿的 gate 与没有 gate 等价。

### 3. 试错：把项目级 `.gradle` 塞进工件（失败，已撤回）

推理是：下游 job 恢复了 `build/classes` 却判不了 UP-TO-DATE，因为 Gradle 的执行历史与输入指纹在 `.gradle/<ver>` 里；同一运行、同一提交、同一绝对路径，恢复后应当直接跳过编译（实测每片省约 2.75 分钟）。

实测 run#8：test(4) 编译期 `ClosedFileSystemException`（读 243 的 `database-plugin.jar` 时 zip 文件系统已被关闭）→ 报成 `Could not find bundled plugin with ID: 'com.intellij.database'`，一片红。同轮该 job 的 `gradle-transforms` 缓存恢复只花 77s 且体积明显偏小（正常 117s / 4775MB），即变换缓存没真正到位。收益 2 分钟、风险是发布门禁偶发红，不对等，撤回。

**教训**：工件里塞构建状态（`.gradle`）会把「任务是否重跑」交给跨 job 的文件搬运，而变换缓存的完整性不受我们控制。要么彻底验干净，要么不碰。

### 4. 缓存认知（本轮量出来的事实）

| 事实 | 读数 |
|---|---|
| 共享缓存条目总量 | 约 9.6GB：`modules-2` 4.4GB（4 个 IDE 的 zip）+ `transforms` 4.8GB（解包后） |
| 每个 job 恢复耗时 | 1.9~2.9 分钟；九 job 同起时最慢一次 5.2 分钟（抢同一条目） |
| 子缓存 key 与提交的关系 | **无关**：run#8 与 run#9 提交不同，`gradle-transforms` / `dependencies` / `kotlin-dsl` 三个 key 完全一致 |
| key 一次性换新的代价 | run#8 那轮 build 的 `Post 配置 Gradle 缓存` 3.13 分钟（整份重存），之后回落到 0.98 |
| verifier-cli 为何每个 job 都要预热 | build 保存缓存时 jar 还没进 `modules-2`，且 setup-gradle 默认「同 key 不覆盖」→ 之后每轮都不再更新该条目。根治要把它改成按依赖懒解析（动门禁核心，当前墙钟归 test，收益 0.6 分钟，暂不做） |

### 5. 三轮读数

| run | 总墙钟 | build | verify ×3 | test ×5 | marketplace |
|---|---|---|---|---|---|
| #5 | 35.0 | 21.7 | 8.8/9.6/9.8 | 9.2/10.2/10.8/12.0/13.2 | 串在 build 里 |
| #7 | 27.8 | 13.3 | 7.3/7.8/8.2 | 10.0/10.7/11.2/11.9/14.4 | 串在 build 里（4.8） |
| #8 | 22.5（2 红） | 12.2 | 6.2/6.8/7.4 | 8.0/8.7/9.6/10.2 + 1 红 | 2.7（缺预热，红） |
| **#9** | **19.4** | **7.3** | 5.8/7.5/7.6 | 8.9/9.8/10.2/10.6/11.4 | 12.1 |
| **#10** | **19.4** | **8.1** | 7.1/7.3/7.6 | 8.2/9.2/9.5/9.5/10.8 | 11.3 |

连续两轮 19.4 分钟全绿（含新增的 `gate`），读数稳定。当前瓶颈：test 最慢片 10.8 分钟（缓存 2 + 编译 2.75 + 测试 3.2 + Gradle 启动与配置约 2），以及 marketplace 11.3（其中约 4 分钟是预热连带的重编译）。

### 6. 参考 t8y2/dbx 的调研结论（2026-11-05）

dbx 是 Rust/Tauri 桌面客户端，全量运行 47 分钟 / 51 job / 247 runner 分钟，**比我们重**，不是「压到几分钟」的样本；它的 Gradle 部分只有两个小 job 且没开 build cache / configuration cache。可迁移与不可迁移如下：

| 做法 | 出处 | 结论 |
|---|---|---|
| 聚合门禁把 skipped 判红 | `.github/scripts/ci-gate.mjs:4-66` | **已采用**（§七.2） |
| 缓存 key 里清零 CI 选择变量（分片号不进 key） | `ci.yml:279-283` | 不适用：实测我们的子 key 与提交无关，无此问题 |
| 缓存只在主分支写、失败不写 | `ci.yml:291-293` | 部分已具备（verify/test 只读）；`cache-on-failure` 无对应输入 |
| 廉价前置 lane（fmt/契约 1 分钟挡住 30 分钟） | `ci.yml:1424-1464` | 不适用：我们的同类守卫是 Kotlin 测试，必须先编译约 4 分钟 |
| 最重的 job 不恢复大缓存 | `ci.yml:1101` | 不适用：分片不恢复就要重下 IDE，约 5 分钟/片，比 2 分钟恢复更亏 |
| 跨 run 复用预编译产物 | `find-win7-prebuilt-artifact.mjs` | 暂不做：要改 IPGP 的 IDE 解析路径，收益不确定 |
| sccache / mold / 自托管 runner | `ci.yml:250-277` 等 | 不可用：JVM 无对应物，对方也全是 GitHub 托管 runner |

一句话：dbx 的价值在「缓存写入纪律 + 门禁防假绿」，不在压缩墙钟。
