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

### 3. 试错：把项目级 `.gradle` 塞进工件（已撤回）

推理是：下游 job 恢复了 `build/classes` 却判不了 UP-TO-DATE，因为 Gradle 的执行历史与输入指纹在 `.gradle/<ver>` 里；同一运行、同一提交、同一绝对路径，恢复后应当直接跳过编译（实测每片省约 2.75 分钟）。

实测 run#8：test(4) 编译期 `ClosedFileSystemException`（读 243 的 `database-plugin.jar` 时 zip 文件系统已被关闭）→ 报成 `Could not find bundled plugin with ID: 'com.intellij.database'`，一片红。当时判为 `.gradle` 引起，撤回。

**归因订正（2026-11-05 晚）**：撤回后 run#11 的 test(1)/test(4) 犯了**同一个**异常，且失败片的 `gradle-transforms` 缓存是**完整恢复**的（4775MB + 4385MB，126 秒）——所以根因不是缓存残缺，也不是 `.gradle` 工件，而是 Gradle 自身在并行解析时关闭了 zip 文件系统句柄（同一 IDE jar 被多线程打开，plugin-structure 的 `FsHandlerFileSystemProvider.checkAccess` 随后撞上已关闭的 FS）。实证分布：run#8 1/5 片、run#11 2/5 片、run#7/#9/#10 共 15 片零命中，约 3/25。

`.gradle` 仍然保持撤回状态——理由不是「它导致了这个异常」，而是「收益 2 分钟，而它把构建状态交给跨 job 的文件搬运，多一层不可控」。要再试必须单独验证。

**处置**：分片步骤对 `ClosedFileSystemException` 这一个签名做一次定向重跑（测试真失败不会命中该签名，不会掩盖问题），并打 `::warning::` 留痕。

**教训**：一次红不等于找到根因。同一个签名在改动撤回后复发，是判定「与我的改动无关」的最直接反证——所以任何归因落文档前，要么有反向验证，要么等下一次复发来证伪。

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
| #10 | 19.4 | 8.1 | 7.1/7.3/7.6 | 8.2/9.2/9.5/9.5/10.8 | 11.3 |
| #11 | 18.8（红） | 8.4 | **4.0/4.3/4.0** | 8.7~10.9（2 片 flake） | 10.3 |
| **#12** | **18.4** | 8.2 | 4.0/4.5/6.0 | 8.6~11.2 | 10.0 |

### 5.1 第三轮改动的效果与剩余瓶颈

三项改动（test 分片不再等 build、verifier-cli 随工件播种、marketplace 补 `-PverifyOnly=true`）在 run#11/#12 兑现：

- verify 三腿 **7.1~7.6 → 4.0~6.0**（播种替代预热，省掉连带重新编译）；
- test 五片 **起跑时刻从 +8.1 变成 0**，与 build 同起；
- marketplace **11.3 → 10.0**（`verifyOnly` 生效，日志里已无编译任务；剩下的是三目标在线验证本身约 5.7 分钟 + 缓存恢复约 3 分钟）。

墙钟构成变成 `build 8.2 + marketplace 10.0`。要继续压只剩两条路，都不便宜：

1. 让 marketplace 在打包完成时就起跑（把 `:verifyPlugin` 从 build 里挪走）。卡点是 verifier-cli 的导出依赖 `:verifyPlugin` 跑过一次——挪走后就得直接按坐标从 Maven Central 拉 jar，而 verifier-cli 的版本号由 IPGP 内部决定（当前 1.411），硬编码会让门禁用的 verifier 版本与 IPGP 脱钩。收益约 1.7 分钟，不值当动门禁核心。
2. 把 `verifyPlugin` 整条腿从 CI 去掉（权威判定在 `verifyGate`，前者只是报告口径）。它省 1.5 分钟，但同样把 verifier-cli 的获取责任甩给下游 job，仍受上一条限制。

另外，marketplace 的 10 分钟里有约 5.7 分钟是**在线**验证（要下载插件元数据）、约 3 分钟是 9.6GB 缓存恢复，两者都是固定成本，换 OS 也换不掉（见 `docs/调研报告/20261105-1106-CI换Linux的提速空间与迁移成本-调研报告.md`）。

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

## 八、第三、四轮提速：18.4 → 13.4 分钟（2026-11-05 晚）

目标是把墙钟压进 15 分钟。到 run#12 为止的构成是 `build 8.2 + marketplace 10.0`，两处都是「等」出来的，不是干活干出来的。

### 1. 三处结构改动

| 改动 | 依据 | 效果 |
|---|---|---|
| `test` 五片去掉 `needs: build` | `testShardN` 的依赖只有 `verifyDemoFixture` + `testShardStagger`（`mpv-testing.gradle.kts:972-975`），测试源码零处读 `build/distributions`；而它们下载了产物仍从头编译（run#10 test(4)：compileKotlin 1m02 + compileTestKotlin 28s） | 五片从 +8.1 起跑变成 0 |
| `:verifyPlugin` 移出 build，独立成 job 且加 `-x buildPlugin` | 它要 1.5 分钟而六条腿全在等它；`-x` 让它直接用工件里的发行包，不再重编译（run#14 该 job 8.7 分钟里有 2.4 是编译） | build 8.2 → 5.2~6.9；该 job 8.7 → 5.0 |
| marketplace 拆三条腿（`verifyMarketplaceVerdict<tag>`） | 三个 verifier JVM 挤在 2 核 runner 上互相争抢（run#11 同一份工作 162.9s / 249.3s / 256.7s） | 10.0 → 最晚 6.9 |

`verifyMarketplaceVerdict<tag>` 是新增的单目标门禁任务：`doLast` 里调的是聚合任务同一个 `checkMarketplaceVerdicts`，只是 `reportsDir` 从三个减到一个（该函数本就按目录逐个判）。聚合任务 `verifyMarketplace` 原样保留，本地 `releaseGate` 行为零变化。

**拆腿的连带代价**：verifier-cli 不能再靠「build 跑过 verifyPlugin」顺带拿到，改为按 `dev-support/verifier-cli-version.txt`（`1.411`）从 Maven Central 直取。版本写死会跟 IPGP 升级脱钩，因此加了 `VerifierCliVersionGuardTest`（本机缓存里出现不同版本即红）；反向验证：把钉子改成 `9.999` 后必红（`:47`），已还原。

### 2. 读数

| run | 总墙钟 | build | verifyplugin | verify ×3 | marketplace ×3 | test ×5 |
|---|---|---|---|---|---|---|
| #12 | 18.4 | 8.2 | （在 build 内） | 4.0~6.0 | 10.0（1 job 三目标） | 8.6~11.2 |
| #13 | 23.9 | 15.2（冷缓存：改 buildSrc 换 key，Post 保存 7.95） | 7.6 | 3.8~4.3 | 6.2/7.4/8.5 | 11.2~12.7 |
| #14 | 15.3（红） | 6.6 | 8.7 | 3.7~5.3 | 5.3/6.4/7.3 | 8.6~10.7 |
| #16 | 13.2（红） | 5.2 | 4.5 | 3.6~4.2 | 5.2/6.2/7.8 | 10.2~11.6 |
| **#19** | **13.4（全绿）** | 6.3 | 5.0 | 3.9/4.3/4.3 | 5.4/6.7/6.9 | 8.2~11.0 |

run#13 是冷缓存轮：改 `buildSrc` 会让 setup-gradle 的缓存 key 换新，build 的 `Post 配置 Gradle 缓存` 因此要整份重存 9.6GB（0.3 → 7.95 分钟）。**结论：动 buildSrc 的提交必然多付一次约 7 分钟。**

### 3. 这一轮踩到的三个 flake（都不是结构问题）

| 现象 | 真因 | 处置 |
|---|---|---|
| `ClosedFileSystemException` 读 243 的 `database-plugin.jar` → `Could not find bundled plugin 'com.intellij.database'`，约 4/25 片 | Gradle 自身 zip 文件系统句柄竞态。**关键证据：原地重跑 4 秒就同样失败**——Gradle daemon 跨两次客户端调用复用，关闭状态留在 daemon 里 | `dev-support/ci/run-gradle-retry.ps1`：命中该签名先 `--stop` 换新 daemon 再重跑；九处 Gradle 调用统一走它 |
| `SqlLogAutoSaverPruneGuardTest:176` 断言「写批后 200ms 内完成裁剪」 | 固定 `Thread.sleep(200)` 当同步，2 核 + 十三 job 并行时不够 | 改 `awaitFileGone` 有界轮询（5s/20ms） |
| `DiagramPanelTest:189` 断言「clear 必须复位加载态」 | `JBLoadingPanel.startLoading/stopLoading` 异步落 EDT，测试线程同步读会读到旧值 | 改 `awaitLoading` 有界轮询（5s/10ms） |

三条都是「用固定时间当同步」的同一类缺陷。**教训**：CI 并行度一提，所有靠「跑得够快」的断言都会开始假红；凡是有异步生效点的断言，一律写成有界等待，超时才算失败。

另：`DiagramPanelTest` 需要平台测试框架（本地 `test` 任务里没有 Application，10 例全 NPE），只能在分片环境验证——派活/自查时别拿本地 `test` 绿当依据。

### 4. 当前瓶颈与不再动的理由

`marketplace(262)` 6.7 分钟是尾巴，构成是：缓存恢复约 2 + Gradle 启动与配置约 1 + 在线验证约 3.5。三条都是固定成本：

- 在线验证要下载插件元数据（`-offline` 会漏报可选依赖 API，这是 verifyMarketplace 存在的理由）；
- 缓存恢复是 9.6GB（4 个 IDE 的 zip + 解包产物）；
- 想让它更早起跑，只能让 build 更快，而 build 的 6.3 里约 2.5 是 Kotlin 编译（无执行历史，跨 job 复用需要 `.gradle` 或 build cache，前者试过失败、后者是项目明令关闭的）。

### 5. 新维护点：verifier-cli 版本钉子

拆腿后 CI 的 verify/marketplace 六条腿按 `dev-support/verifier-cli-version.txt`（当前 `1.411`）从 Maven Central 直取 verifier-cli，不再靠「build 跑过 `:verifyPlugin`」顺带获得。

**升级 IPGP（`settings.gradle.kts:36`）后必须同步改这个钉子**，否则 CI 会继续用旧版 verifier 跑判定且不报错——门禁绿不代表按新版口径验过。具体操作写在 `docs/发行说明/T-ReleaseChecklist-发行检查清单.md` 的 RC-P2c（跑一次 `:verifyPlugin` → 查缓存里的 jar 版本 → 改钉子 → 跑 `VerifierCliVersionGuardTest` 确认绿）。

守卫本身只在本机缓存里有 jar 时生效（CI 上会 skip），**是本地防线而不是 CI 防线**——所以它挂在「升级 IPGP」这个动作上，而不是挂在门禁上。

## 九、测试时长的真相：本地那 5 片从来没并行过（2026-11-05 实测）

做「纯逻辑桶独立成腿」时量出来的，推翻了一个长期假设：

**`./gradlew testShards` 在本机的 5 个分片是串行执行的**，`laneAtEnd` 连续两轮全是 `[0,0,0,0,0,0]`，
各片 END 时刻逐片相接。原因是 `org.gradle.parallel=true` 只在**跨项目**并行，而 5 个 `testShardN`
同属 root project。所谓「5 片并行」只在两种场合成立：CI（每片独立 runner）与
`dev-support/test-shards-parallel.ps1`（跨进程）。

这条决定了所有「拆腿提速」的收益方向：

| 场景 | 拆成两条腿的收益 |
|---|---|
| 本机（串行） | 总量与失衡下降是实的（execSum 961.3→735.8s，失衡 +20%→+6.6%），但两条腿**串着跑**，墙钟只省总量 |
| CI（并行） | 两条腿各付一遍固定开销（缓存恢复 2 + 编译 3），test 腿墙钟反而 11.0→11.7 分钟 |

配套实测（2026-11-05）：

- 平台类 446 / 4250 例 / 735.8s；纯逻辑 374 / 4416 例 / 226.6s；合计 833 类、配置期断言并集零遗漏；
- 全量轮 `tests=8728 / 0 失败`，分片 850/818/781/910/891；
- 权重画像刷新后一轮：分片 159.3/167.6/155.7/155.1/132.2s，`relCrit 1.088`（<1.10 告警线）。

## 十、JUnit5 不值得迁（速查）

平台 JUnit3 路径已经「每 JVM 一个 Application + 每 JVM 一个 light project 且跨类复用」，
平台测试 77% 的时间又跑在 EDT 上（`@Execution(CONCURRENT)` 被串行化），所以换框架的上限是 0~10%，
而成本是 398 个平台类 + 449 个纯逻辑类、约 550~1100 人时。详见
`docs/调研报告/20261105-1830-JUnit5迁移可行性与测试提速替代方案-调研报告.md`。

## 十一、今日 CI 踩坑清单（2026-11-05 汇总，按「下次别再踩」排序）

| # | 现象 | 真因 | 处置 / 判据 |
|---|---|---|---|
| 1 | workflow 的 PowerShell 步骤 2 分钟炸，报语法错 | `$($keep -join ',' / '')` ——手滑写坏的表达式 | **workflow 里的 PowerShell 片段先在本地用真实输入跑一遍**；整段可用 `[System.Management.Automation.Language.Parser]::ParseFile` 做纯语法校验 |
| 2 | 落盘文件里 `instances = ` 与 `mysql84, pg16` 断成两行，测试判「矩阵未声明任何实例」 | `@("# 注释", "x = " + ($arr -join ', '))`：数组字面量里 `+` 与 `,` 的优先级把第二个元素切成两项 | 一律用 `-f` 格式化：`$out += ("instances = {0}" -f (...))`；**落盘类操作要把产物读回来逐行验形态**，退出码 0 不代表内容对 |
| 3 | e2e 业务请求全 HTTP 500，而 agent 心路正常 | JDBC URL 带 `&`，作为 `-P` 参数穿过 `gradlew.bat` 被 **cmd 当命令分隔符**截断 | 参数改**文件注入**（写 `gradle.properties`，由 `providers.gradleProperty` 读回） |
| 4 | `Couldn't resolve IntellijIdea download URL for version: '2026.3'`，而官方链接存在 | IPGP 读**项目内缓存**的 product-releases 清单（`.intellijPlatform/productReleases/<hash>.json`，已 gitignore），缓存早于发布 | `rm -rf .intellijPlatform/productReleases` 重跑；`--info` 可见 `Reading product releases listing from cache: …`；CI 不受影响 |
| 5 | 分片偶发 `ClosedFileSystemException`（读 243 的 `database-plugin.jar`） | Gradle zip 文件系统句柄被关闭后又被 plugin-structure 使用 | 重跑策略逐级加码：原地重跑 ❌ → `--stop` ❌ → `--stop`+`--max-workers=1` ❌ → **删 `caches/*/transforms/*/transformed/idea*`**（待验证）；判据是日志出现「已清理 IDE 解包目录 N 个」 |
| 6 | 分片本身 `BUILD SUCCESSFUL`，但 job 判红在三个 post 动作 | 收尾清理时文件仍被残留 JVM 持有（该轮日志末尾有 `Terminate orphan process: pid … (java)`） | 先观察是否复现；若复现，在测试步骤末尾补 `./gradlew --stop` 收尾 |
| 7 | 全量 12 实例的 `dialectExternalVerify` 跑 91 分钟仍未完 | 跨洋 RTT × 每实例上千次建表/查询的乘数效应（本地同一套矩阵 5~10 分钟） | CI 只跑代表实例子集（`mysql84`+`pg16`，`CI_DIALECT_KEYS` 可覆盖），预算 40/50 分钟；**全量以本地 `releaseGate` 为准** |
| 8 | `testFast` 以为「秒级」，CI 上 4382 例要 4.5 分钟 | 本地热 JVM + 6 核，CI 冷 JVM + 2 核 | 按 CI 实测估时；本机与 CI 的单测耗时不可互相外推 |
| 9 | 子集版方言腿跑通了，但只差 2.2 分钟就撞步骤上限（27.8 vs 30） | 成本**按字计费**：mysql84 262 个保留字 1113s（≈4.3s/字）、pg16 86 个 196s（≈2.3s/字），加冷启动 6 分钟；跨洋 200ms RTT × 每字多次往返 | 预算按「实测 + 余量」订，别按「感觉 5~15 分钟」订；要再压缩只能减保留字或把 runner 挪近网，**加预算只是延迟失败** |

**run#9 / 推送#21 收官读数（2026-11-05，两个 workflow 首个全绿轮）**：夜间 11 腿全绿（含首次真绿的真机 DB 与 e2e），总 30.5 分钟，唯一长腿是 dialect 27.8 分钟；推送档全绿 13.2 分钟，关键路径是 `fast`（601s）而不是分片（433~495s）——快桶单腿装的是本地 226s 的量，等于一个半分片，**它才是便宜档的天花板**。

**方法论层面两条**：①参数面板/落盘类脚本，**产物要读回来验形态**；②CI 上「偶发」的红先做**处置梯度实验**（原地→换 daemon→降并发→换目录），每一步都贴读数，别一步跳到结论——本轮 zip-FS 就是前三级都证伪后才定位到磁盘状态。

## 十二、把「关键路径」继续压：夜间补齐兼容面 + 快桶拆两片 + 第六片 A/B（2026-11-05 晚）

前一轮读数把两件事暴露得很清楚：**夜间的覆盖是「测试面全、验证面零」**（verifyPlugin 与
marketplace 只有人手动 dispatching 手动门禁才跑，夜里没人看），**推送档的天花板是快桶腿而不是分片**。

### 12.1 夜间补 `build → verifyplugin → marketplace`

- 三条腿照抄手动门禁的同名 job（工件名同为 `build-outputs`，两边口径必须同步改）。
- 顺序不能省 `build`：`verifyPlugin` / `verifyMarketplace` 吃的是「已打包的那份字节」，
  各自重编译会让「验过的字节」与「要发的字节」不是同一份。
- 墙钟代价为零：它们与测试/真机腿并行，夜间关键路径仍是方言腿（27.8 分钟）。
- **`if` 判 `needs.build.result == 'success'` 而不是 `!cancelled()`**：`changes` 判无代码提交那天
  `build` 是 skipped，而 skipped 的依赖对 `!cancelled()` 是「继续跑」——它会去下载不存在的
  工件然后红。这是本仓第二次踩「skipped 不等于通过」的同一个坑（第一次是 build-plugin 的聚合 gate）。

### 12.2 快桶拆两片（`-PfastBucketSliceCount=2 -PfastBucketSlice=i`）

- 切法复用分片均衡器那套画像：`dev-support/test-shard-weights.tsv` 权重降序 + 同权按类名升序，
  装当前最轻片（LPT）。零画像类取中位数。
- 配置期实测（本机冷配置）：**187 类 / 116.4s + 187 类 / 116.4s**，两片完全对齐（全集 374 类 232.8s）。
  对 CI 而言每片约 350~380s（本地 116s × 约 3×），对比拆前 601s。
- 覆盖面红线做成配置期 `require`：两片并集必须恒等于 `fastBucketClasses − shardExcludeClassNames`。
  这条断言是本轮最值钱的一行——快桶漏一片就是 2000+ 例在 CI 上静默不跑，而总数看起来只少一点。
- 结果目录仍写 `build/test-results/testFast`：切片模式只给 CI（每片独占一台 runner）。
  本机串行跑两片会混计进 `testShards` 聚合——`shardResultDirs` 是**位置语义**列表，不能随便插成员。

### 12.3 第六片 A/B

- `-PtestShardCount=6` 上线前先算账：每片约 2.4 分钟是同等的编译固定成本，测试执行按 6/5 缩
  （本地片均值 147s × 3 ≈ 440s → 约 370s），预期 433~495s → 约 400~440s，整轮省 1~1.5 分钟。
- **这只是推算**，本仓惯例是片数每次负载形态变化都要实测；本轮改成 6 片后跑一轮对比，
  不合算就退回 5（matrix 与 `-PtestShardCount` 必须同步改，只改一处会出「testShard6 不存在」
  或「第 6 片装空清单」）。
