# T-BuildConcurrency 多工作树并行构建资源争用治理 — 开发计划

> 来源：2026-09-22 用户报「同时运行测试或构建时效率很低、并发受限，可能是构建器的原因」，诉求为「多工作树、多智能体并发完成任务，不受构建工具或其它工具影响」。
> 编号：占用 **O-65**（2026-09-23 订正：立项时自称 O-59，第三轮全景审计发现与台账冲突，改占 O-65；O-59 实为 T-RollbackIdentityWrap 已交付）。O-57/O-58 已由并行会话占用，O-56 为 T-AgentResiduals。
> 性质：工程效能治理（Gradle 配置 + 环境变量 + 协调脚本，**源码零改动、测试零改动**）。
> 实施方式：先做只读核实与对照实测，破坏性动作（停 daemon、迁盘）逐项经用户确认后执行。
> 前置：本计划全部实测快照点为 **2026-09-22 20:00~20:38**，HEAD=`t-round10` `fa1cf55`。并行会话高频推进，实施时若环境已变（daemon 数、worktree 数、磁盘余量），**先重跑 §一 的核实命令**。

---

## 一、现状核实（实测，2026-09-22 20:00~20:38）

### 1.1 机器与资源

| 项 | 实测值 | 依据 |
|---|---|---|
| CPU | Intel i5-10400F，**6 核 12 线程** | `wmic cpu get Name,NumberOfCores,NumberOfLogicalProcessors` |
| 物理内存 | 31.9 GB | `wmic ComputerSystem get TotalPhysicalMemory` |
| 内存实际占用 | 已用 23 GB（72%），**提交量 57.3 GB / 上限 74.7 GB** | `Win32_OperatingSystem` + `Get-Counter '\Memory\Committed Bytes'` |
| 磁盘余量 | C: **3.0 GB** / D: 61.1 GB / E: 9.2 GB / F: 138.8 GB | `Get-PSDrive` |
| 运行中 java 进程 | 11 个 | `Get-CimInstance Win32_Process` |

提交量 57.3 GB 已远超物理内存 31.9 GB，说明系统正在使用页面文件。这是构建卡顿体感的主要来源，与 Gradle 配置无关。

### 1.2 daemon 实况（参数不一致，池已膨胀）

| 类型 | 实例数 | 实测 JVM 参数 |
|---|---|---|
| GradleDaemon | 3 | 1× `-Xmx3g -XX:MaxMetaspaceSize=1024m`、2× `-Xmx2g -XX:MaxMetaspaceSize=256m` |
| KotlinDaemon | 3 | 1× `-Xmx700m`（Kotlin 默认）、2× `-Xmx2g -XX:MaxMetaspaceSize=256m` |

判据来自 java 进程命令行。三个 GradleDaemon 出现两种参数，意味着其中至少一个无法与其余复用。

### 1.3 配置覆盖链（项目级配置从未生效）

| 层 | 文件 | 关键值 |
|---|---|---|
| 用户级（**优先级高**） | `~/.gradle/gradle.properties` | `org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=256m`、`caching=true`、`parallel=true`、`codex.sdk.jar=…` |
| 项目级 | `gradle.properties` | `org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1536m`、`caching=false`、`parallel=true`、`configuration-cache=true` |
| settings 层 | `settings.gradle.kts` | `buildCache.local.isEnabled = false` |

Gradle 属性优先级为 GRADLE_USER_HOME > 项目根，故项目级那份 `-Xmx4096m/MaxMetaspaceSize=1536m` 被用户级 `-Xmx2g/256m` 覆盖，**自落地起从未生效**（项目 `gradle.properties` 注释已自认此点；本轮 daemon 实测参数 2g/256m 与该结论一致）。256 MB metaspace 对 IntelliJ 平台插件开发明显偏小。

`caching=true` 被项目 `settings.gradle.kts` 的 `buildCache.local.isEnabled = false` 关死（settings 层优先级高于属性文件）——这是 T-TestPerf-01/T-TestPerf2 的既定处置，本轮不改。

### 1.4 磁盘账（`~/.gradle` 约 40 GB，全在 C 盘）

| 子项 | 体积 | 备注 |
|---|---|---|
| `caches/` | 39 GB | 其中 `9.7.0/` 21 GB、`modules-2/` 14 GB、`build-cache-1/` 1.5 GB（缓存已禁用，纯残留）、`jars-9/` 1.4 GB、`transforms-3/` 435 MB |
| `wrapper/` | 861 MB | `gradle-9.7.0-bin` 325 MB、`gradle-7.5-bin` 257 MB、`8.5-bin` 141 MB、`8.2-bin` 137 MB |
| `daemon/` | 453 MB | 注册表含 10 个版本目录（7.5 至 9.7.0） |
| `jdks/` | 7.7 MB | toolchain 自动下载的 JDK |
| 其余 | < 10 MB | `native` / `kotlin-profile` / `sandbox-tmp` / `workers` / `notifications` / 两份 `gradle.properties` |

无 `init.d/` 脚本。

### 1.5 工作树与并行活动

| 项 | 实测 |
|---|---|
| worktree | 7 个：主仓 `t-round10`、`mpsv-agent-config-locate`(master)、`mpsv-hql-colmap`、`mpsv-prewarm-late-recover`、`mpsv-t05-reverse`(detached)、`-dev`、`-kotlin-to-java` |
| 单树占用 | 2.7 GB / 2.1 GB / 1.6 GB / 1.4 GB / 503 MB / 456 MB（各自带独立 `.gradle` 与 `.intellijPlatform`，后者约 963 MB） |
| 并行登记表 | `parallel-tasks.json` 累计 **244 条**，其中 **9 条 status=active**，多数为未注销残留（最早的 expectedEndAt 停在 2026-09-22 11:36） |

登记表无法据以判断"此刻有谁在跑"——这是本计划 §三 F 项的独立动因。

### 1.6 分片与沙盒隔离（既有实现，本轮不动）

`buildSrc/src/main/kotlin/mpv-testing.gradle.kts`：

- 分片数默认 3，上限 6（`-PtestShardCount=N`）。第 66~71 行注释记有实测：**6C12T 下 3 片最优，2 片慢约 40%**；T-TestPerf3 于 2026-09-17 复测 4 片为 3m02s，而 3 片为 2m03s，结论是"4 片并发超出物理核竞争反而慢"，默认 3 片定案。
- 默认 `test` 与 `testFast` 均 `maxParallelForks = 1`（多 fork 共享默认沙盒会 PersistentFS 互踩，有 191 例失败实证）。
- 每片独立沙盒目录，这是并发安全的既有前提。

**由该注释可直接推出**：单路 `testShards`（3 片）已经吃满本机 6 核 12 线程。多路并行属于 CPU 超订阅，而非配置缺陷。

### 1.7 路径引用核查（为迁盘方案排除障碍）

| 核查项 | 结果 | 依据 |
|---|---|---|
| 项目内硬编码 `~/.gradle` 路径 | **零命中** | `grep -rn "\.gradle[/\\]\|GRADLE_USER_HOME\|Users/USER" --include=*.kts --include=*.gradle --include=*.py --include=*.bat --include=*.sh`（排除 `build/`、`.gradle/`） |
| `GRADLE_USER_HOME` 环境变量 | 未设置（User / Machine 两级注册表均为空） | `[Environment]::GetEnvironmentVariable('GRADLE_USER_HOME', …)` |
| IDE 全局 Gradle user home 覆盖 | 无（`%APPDATA%\JetBrains\*\options\gradle.xml` 均不存在） | 目录枚举 |
| 项目 `.idea/gradle.xml` | 无 `gradleUserHome` 项 | 文件读取 |
| `C:\Users\USER\.gradle` 属性 | `Directory`（普通目录，非 reparse point） | `(Get-Item -Force).Attributes` |
| wrapper 发行版来源 | `distributionUrl` 指向 `mirrors.cloud.tencent.com`，`retries=0` | `gradle/wrapper/gradle-wrapper.properties` |

结论：走目录联接（junction）路线时，**不需要改任何环境变量或 IDE 设置**，前提条件全部满足。

---

## 二、成因分析（五项，性质不同须分开处置）

| # | 成因 | 性质 | 可否靠隔离解决 |
|---|---|---|---|
| C1 | ~~**CPU 超订阅**：单路 `testShards` 3 片已占满 6 核~~ **【2026-09-23 实测订正为内存瓶颈】** 单路 CPU 均值 49.8%、双路 71.6%，**CPU 始终有余量**；真正的约束是内存提交量（单路已 61.6 GB vs 物理 31.9 GB；双路峰值 66.9 GB / 上限 74.7 GB） | 物理约束 | **否** |
| C2 | **内存超售**：提交量 57.3 GB > 物理 31.9 GB，已用页面文件（§1.1） | 物理约束 | 部分（隔离会加剧） |
| C3 | **Kotlin 编译 daemon 复用致编译串行**：复用判据为 JVM args，参数相同即复用同一 daemon，而一个 Kotlin daemon 同时只服务一个编译请求 | 工具机制 | **是** |
| C4 | **Gradle daemon 池膨胀**：jvmargs 出现两种取值（§1.2），参数不同即无法复用；根因是配置覆盖链不统一（§1.3） | 配置缺陷 | **是**（统一即可） |
| C5 | **跨工作树零复用**：`buildCache.local` 被关闭（§1.3），每个工作树每次构建全量重编 | 配置取舍 | **是**（改为隔离而非关闭） |

另有 C3/C5 的伴生形态：全局 `caches/9.7.0`（21 GB）承载 IntelliJ 平台 distro 的 artifact transform，多路并发读写存在锁争用；历史已登记过"共享 transform 缓存被并行会话重抽/打坏"的成片失败形态（见 `docs/MEMORY.md`「回归假失败清单」第三形态）。

**核心判断**：用户诉求中的「多智能体并发」与「构建并发」是两件事。写码几乎不占 CPU/内存，可宽并行；构建与测试受**内存**硬约束（非 CPU——2026-09-23 实测订正，见 §实施记录 一），当前 32 GB 配置下真并行上限为 2 路。目标架构应为「宽并行写码 + 窄并行构建 + 集中串行收口」，而非让每路都跑全量构建。并行本身是有收益的（双路加速比 1.52），要提上限得先加内存。

---

## 三、方案（处置表）

按投入产出排序，A/B/C 为第一梯队，D/E/F 视用户定案与实测结果决定是否推进。

| 项 | 动作 | 目标 | 写集 | 风险等级 |
|---|---|---|---|---|
| **A** | **C 盘空间止血**：将 `C:\Users\USER\.gradle`（约 40 GB）迁至 F 盘 | 解除 C 盘 3.0 GB 的构建失败隐患；为后续隔离方案腾出空间 | 环境（文件系统 + 可选环境变量） | 中（需停 daemon；见 §六 R1） |
| **B** | **统一 daemon 配置 + Kotlin 编译策略**：① 消除用户级与项目级的 jvmargs 覆盖冲突，使 Gradle daemon 参数在全工作树一致；② Kotlin 编译改 `in-process` 策略（先实测 Metaspace），绕开共享 Kotlin daemon 的串行化 | ① 止住 daemon 池膨胀（当前 3 个 daemon 两种参数）；② 消除多路编译排队 | `~/.gradle/gradle.properties` 与/或项目 `gradle.properties` | 低（①）/ 中（②，见 §四 P-3） |
| **C** | **机器级构建闸门**：同时运行的 gradle 构建数上限（建议 2），并行时每路降 `-PtestShardCount`【2026-09-24 订正：降片已被 §实施记录 五 实测证伪并禁用，本项落地为**纯限流**闸门（`dev-support/gradle-gate.ps1`），不带任何降片逻辑】 | 把"每路内并行"换成"路间并行"，总并行度收敛到物理核内 | `dev-support/` 新增脚本 + `docs/MEMORY.md` 约定节 | 低 |
| **D** | **共享状态隔离（可选）**：每路独立 `GRADLE_USER_HOME` + `GRADLE_RO_DEP_CACHE` 指向共享只读依赖缓存 | 彻底分离 daemon 与 transform 写锁 | 环境变量 + 启动脚本 | 中（内存翻倍；见 §四 P-5） |
| **E** | **缓存复用（可选）**：`buildCache.local` 由"关闭"改为"按工作树隔离目录" | 恢复同分支反复构建的增量收益，同时不跨分支串味 | `settings.gradle.kts` | 中（须先证伪原失败根因；见 §四 P-4） |
| **F** | **协调脚本可靠性**：登记表自动过期（定时 prune）+ 沙盒容器名从工作树名自动派生（不再手改 `build.gradle.kts` 再还原） | 让"此刻有谁在跑"可判、沙盒名不再靠人工纪律 | `parallel_tasks.py` + `buildSrc/mpv-testing.gradle.kts` 或独立约定 | 低 |

### A 项的两条路线（须用户定案，见 §九 D1）

**A-1 目录联接（junction）**：`robocopy /MOVE` 搬移后 `mklink /J` 在原位重建联接。所有工具看到的路径不变，**零配置改动、报错风险最低**；代价是不具备"每路独立 home"的能力（但与 D 项不冲突，D 项是给特定会话设不同环境变量去绕开默认路径）。

**A-2 环境变量迁移**：设系统级 `GRADLE_USER_HOME` 指向 F 盘，同时在 IDE 设置中显式指定 Gradle user home。为 D 项打基础；代价是 IDE 不自动跟随（须显式配置），且 `jdks/` 路径变化可能触发 toolchain 重新探测。

---

## 四、待验证前提（实施前先证伪，勿直接照抄）

| # | 前提 | 验证方式 | 若不成立 |
|---|---|---|---|
| P-1 | ~~迁盘后 transform 缓存与增量编译历史因绝对路径变化而失效，首次构建退化为全量、之后恢复，且不报错~~ **【2026-09-23 实测：证伪】** 迁后首个构建 9/10 任务仍 `up-to-date`，缓存完全有效（junction 保持逻辑路径不变） | ~~迁移后取首次构建耗时读数对照~~ **已做**（`./gradlew help`，见 §实施记录 三） | 无需回退 |
| P-2 | ~~单路 3 片已接近饱和，故「2 路并行跑全量」的总吞吐与串行接近（§九 D3 的推算）~~ **【2026-09-23 实测：推翻】** 单路 CPU 均值仅 49.8%（未饱和），双路加速比 1.52；真实约束是内存（双路 Commit 峰值 66.9/74.7 GB） | ~~N=1/2/3 路墙钟对照 + CPU 利用率~~ **已完成 N=1/2 两档**（读数见 §实施记录 一） | 已据实测重写 §九 D3；N=3 因内存余量不足未测 |
| P-3 | Kotlin 编译改 `in-process` 策略可绕开共享 daemon 的串行化，且 Metaspace 需求在本机可控【2026-09-24 实测：成立】 | ~~单独实测该策略下的编译墙钟与 Metaspace 峰值~~ **已做**：统一 4096m/1536m 后新树首编 `:compileKotlin compileTestKotlin` 2m57s 无 OOM、KotlinDaemon 进程数 0（见 §实施记录 六）；既有记录「只加该开关在 `:compileMcpStubKotlin` 报 Metaspace OOM」的前提正是 jvmargs 被用户级压成 256m，本轮随参数统一一并消除 | 改用 D 项（每路独立 Kotlin daemon） |
| P-4 | build cache 关闭的**原根因**确为"多分支共用一个 cache 目录导致复用陈旧 `compileTestKotlin` 产物"；按工作树隔离目录即可根除 | 查证 T-TestPerf-01 三次 `NoSuchMethodError` 假失败的现场记录与当时的 cache 目录配置 | 维持关闭，E 项不推进 |
| P-5 | 每路独立 `GRADLE_USER_HOME` + 共享只读依赖缓存对本项目有效（IntelliJ 平台插件的 artifact transform 对只读缓存无特殊约束） | 在单个工作树做对照实验：独立 home + `GRADLE_RO_DEP_CACHE` 能否完成 `verifyGate` | D 项降级为"仅隔离 daemon 参数" |
| P-6 | 机器级文件锁闸门与 Gradle 自身的 daemon 调度不发生死锁 | 构造两路并发申请闸门的用例，含异常退出后锁释放路径 | 改用"按工作树分波"的人工调度 |

**已验证项（无需再证）**：daemon 实际按用户级 `-Xmx2g/MaxMetaspaceSize=256m` 运行（§1.2 进程命令行实测）；项目内零硬编码 `~/.gradle` 引用、`GRADLE_USER_HOME` 未设、IDE 无覆盖（§1.7）。

---

## 五、验收标准

1. **A 项**：C 盘可用空间显著回升（迁出约 40 GB）；`gradlew help` 与一次 `testShards` 在迁移后均成功；若走 A-1，`(Get-Item C:\Users\USER\.gradle -Force).Attributes` 显示 `ReparsePoint`。
2. **B 项**：`./gradlew --stop` 后重跑一次构建，`Get-CimInstance Win32_Process` 中 GradleDaemon 的参数**取值唯一**（不再出现两种 jvmargs）；若采用 `in-process` 策略，KotlinDaemon 实例数为 0（编译在 Gradle daemon 内完成），且编译与门禁均通过。
3. **C 项**：闸门生效时并发构建数不超过设定上限；两路并发申请与异常退出后的锁释放均有读数；`docs/MEMORY.md` 有对应约定条目。
4. **D/E 项（若推进）**：各自 P-5 / P-4 的对照实验读数齐，且 `verifyGate` 三目标仍全 Compatible。
5. **F 项**：`parallel_tasks.py` 支持自动过期；新登记的沙盒名可由工作树名推导，无需改 `build.gradle.kts`。
6. **全计划收口**：`testShards` 三片 0 失败 + `verifyGate` 三目标 Compatible；本计划**源码零改动**（`git diff --stat` 仅构建配置、脚本与文档）。

---

## 六、风险

1. **停 daemon 会打断并行会话**：`./gradlew --stop` 停的是所有共享同一 `GRADLE_USER_HOME` 的 daemon，包括其他会话正在使用的。实施前须确认并行登记表与进程状态，且**当前登记表本身不可信**（§1.5），须以进程实测为准。迁盘（A 项）同理——被 daemon 锁住的文件 `robocopy /MOVE` 删不掉，会留下半残状态。
2. **junction 的删除陷阱**：拆联接只能用 cmd 的 `rmdir`（只删联接本身）；Git Bash 的 `rm -rf` 可能跟随联接递归删除 F 盘的真实内容。
3. **跨盘搬移的中间态占空间**：C 盘仅剩 3.0 GB，禁用"先复制到 F 盘再手动删 C 盘"的两步法，须用 `robocopy /MOVE` 逐文件搬移删除，避免中途写满。
4. **`robocopy` 退出码语义**：0~7 均为成功，8 以上才是失败。脚本判断禁用 `&&` 串联（与本仓既有收口纪律同款理由）。
5. **D 项的内存反效果**：独立 home 会让每路各自持有 daemon 与 transform 缓存，在 C2（已超售）现状下可能加剧页面交换，反而变慢。故 D 项须在 A 项完成、内存压力缓解后再评估。
6. **B 项与并行会话同文件**：`~/.gradle/gradle.properties` 是全局文件，其它项目（如某个第三方工程依赖其中的 `codex.sdk.jar`）共用，改动前须确认不误删他人条目。
7. **E 项的既有定案不可轻率推翻**：T-TestPerf-01 关闭 build cache 是三次实证假失败后的处置，重启前必须先完成 P-4 的根因查证。

---

## 七、实施顺序

```
① 只读核实 + §四 P-2 并发档位对照实测                                   【2026-09-23 已完成，见 §实施记录 一】
② B 项（统一 daemon 配置 + Kotlin 编译策略，风险最低，先做以便后续读数干净）  【2026-09-24 已完成，见 §实施记录 六】
③ A 项（迁盘止血）                                                      【2026-09-23 已完成，见 §实施记录 三】
④ C 项（机器级闸门 + 约定登记）                                          【2026-09-24 已完成，见 §实施记录 七；约定文案待主控转录 docs/MEMORY.md】
⑤ F 项（协调脚本可靠性）                                                【2026-09-24 已完成，见 §实施记录 八】
⑥ D / E 项（视 P-4 / P-5 实测结果与用户 D2 / D3 定案决定是否推进）
⑦ §五 验收读数 + `docs/变更记录.md` 台账行
```

~~**下一轮首选实验**：「降每路片数 + 多路并行」~~ **已完成（2026-09-23，见 §实施记录 五），方向证伪**——双路 2 片比 3 片慢一倍且内存更糟。**最终定论**：闸门 2 路；日常验证走 `testFast` 快通道（双路 94~155s，Commit 低 9.4 GB）；全量 3 片只在单路收口跑；提并行度只剩加内存。

依赖关系：② 与 ③ 都触碰 daemon 生命周期，须串行；③ 完成前不做 ⑥（内存前提）；① 的 P-2 实测须在 ③ 之前完成，否则闸门上限缺少本地读数依据。

---

## 八、不做的事

- **不改源码、不改测试代码**：本计划全部动作落在构建配置、环境变量、脚本与文档。
- **不动 `testShards` 的分片规则**（默认 3 片有 6C12T 实测依据，4 片更慢已复测；并发问题不在分片规则本身）。
- **不引入新的 Gradle 插件或构建依赖**。
- **不做容器化 / 远程 CI**：属另一量级的方案，需单独立项评估，不在本轮。
- **不清理并行会话的工作树或缓存**（除 A 项约定的 `~/.gradle` 迁移外，不动他人产物）。
- **不擅自删除 `~/.gradle` 下任何内容**——A 项只做整体搬移，不做选择性清理；`build-cache-1`（1.5 GB 残留）是否删除另行确认。

---

## 九、定案与推荐（2026-09-22 主调度推荐，待用户确认）

### D1 迁盘方式 → 推荐 A-1 目录联接，暂不设环境变量【2026-09-23 已执行，读数见 §实施记录 三】

理由：当前最紧迫的是 C 盘仅剩 3.0 GB（构建失败隐患），A-1 零配置改动、零报错风险。实测搬移 40.003 GB 零失败、耗时 14m23s，C 盘可用空间 2.7 GB → 43.0 GB，迁后 Gradle 正常且缓存有效。A-2 需同时改系统环境变量与 IDE 设置两处，多两个出错点，而它换来的能力（为 D 项打基础）在本阶段用不上。

**修正原表述**：A-1 与 A-2 不是二选一，而是先后关系。目录联接管的是"默认 home 落在哪个物理盘"，环境变量管的是"某个会话用哪个 home"。将来推进 D 项时，对特定会话设 `GRADLE_USER_HOME=F:\gradle-homes\<session>` 即可绕开联接，两者共存。因此 A-2 应视为"将来做 D 项时的一个动作"，而非现在的选项。

### D2 daemon 策略 → 推荐拆成两条独立处置，不做"全部一致 / 全部不同"的一刀切

原问法有误：Gradle daemon 与 Kotlin daemon 是两套独立机制，串行化只发生在后者。

| 子项 | 推荐 | 理由 |
|---|---|---|
| Gradle daemon 参数 | 全部工作树统一 | 一个 Gradle daemon 同时只跑一个构建，参数一致使闲置 daemon 可被复用，省掉每次 10~30 秒的 daemon 重启；当前 3 个 daemon 出现两种参数（§1.2）正是不统一的结果。参数一致**不会**导致构建排队——都忙时 Gradle 会另起 daemon |
| Kotlin 编译 | 先试 `in-process` 策略（P-3 实测） | 这是"两路分别卡 70/20 分钟"的机制来源。绕开共享 Kotlin daemon 是消除串行化的直接手段。代价是编译吃 Gradle daemon 内存，须同步提高 metaspace（项目原本要 1536m，一直被用户级 256m 覆盖）。若实测 Metaspace 不足，退路是给每路不同的 `kotlin.daemon.jvm.options` 强制独立 Kotlin daemon |

### D3 并发闸门上限 → 推荐 2 路；上限由**内存**决定而非 CPU（2026-09-23 实测订正）

**原推算已被实测推翻。** 原推算假定「单路 3 片吃满 6 核，故并行只是重新分配同一份资源，吞吐提升接近零」。P-2 实测（见 §实施记录 一）显示单路 CPU 均值仅 49.8%，**远未饱和**；双路并行每路退化 1.31 倍，但两个任务的总完成时间从串行的 454s 降到 298s，**加速比 1.52 倍**。

**真实约束是内存。** 双路时 Commit 峰值 66.9 GB / 上限 74.7 GB（余量 7.8 GB），可用物理内存最低 2.8 GB。CPU 尚有约 28% 余量，但内存不允许再加一路——**2 路是 32 GB 配置下的实际上限**。项目历史「4 片比 3 片慢」（3m02s vs 2m03s）同因：第 4 个测试 JVM 加剧换页，而非 CPU 竞争。

**据此杠杆排序也要改**（原第 1 条「并行无收益」的前提已不成立）：
1. **加内存**——当前唯一能提高并行度的手段。CPU 有约 28% 余量而内存已顶格，加到 64 GB 后并行路数上限随之抬升；A/B 项解决的是固定开销，量级小于此条。
2. ~~**降每路片数**~~ **【2026-09-23 实测证伪，见 §实施记录 五】**——双路 2 片比 3 片慢一倍（598s vs 298s）且 free 跌到 0.3 GB。日常验证的正解是 `testFast`（双路 94~155s，Commit 峰值低 9.4 GB，吞吐约 2 倍）。
3. **降低每路工作量**——日常验证用 `testFast`（秒级）而非 `testShards`，全量只在收口跑一次。
4. **消除固定开销与失败重跑**——A/B 项解决此项。

具体推荐：闸门上限 **2 路**（内存约束），全量 3 片用于单路收口；若要提并行路数，先加内存，或先做「降片 + 多路」的对照实测。**6 核不是当前天花板，内存才是。**

### D4 build cache 是否推进 → 推荐维持关闭

理由：build cache 解决的是"减少重复计算"，而当前痛点是"资源不够分"，方向不匹配。其失败模式是"假失败"，代价极高（整轮重跑 + 排查），而 T-TestPerf-01 已三次实证；多路并行时 cache 的写入竞争本身也是开销。推进前提是先完成 P-4 根因查证，而该查证需翻历史现场，成本不低而收益不明。

### 若只做一件事

**A-1 迁盘**。它解决的是"随时可能构建失败"的确定性风险，零风险、十分钟可完成，且不受其余定案影响。

---

## 实施记录

### 一、P-2 实测（2026-09-23 08:45~09:47）

**设计**：以同一提交（`01c6a99`）的两个工作树做对照——`mpsv-agent-config-locate`（基线）与 `mpsv-hql-colmap`（陪跑），各跑 `testShards -PtestShardCount=3`。单路与双路各采 CPU 与内存提交量（3 秒粒度）。实测前确认无其他 Gradle 构建在跑（CPU 基线 12~23%）。

**读数**：

| 场景 | 墙钟 | CPU 均值 | CPU≥80% 占比 | Commit 均值 / 峰值 | 可用物理内存最低 |
|---|---|---|---|---|---|
| 单路 3 片 | **227s**（3m47s） | 49.8% | 9% | 61.6 / 63.0 GB | — |
| 双路各 3 片 | **298s**（每路） | 71.6% | 47% | 63.6 / **66.9 GB** | **2.8 GB** |

**判定：P-2 原假设被推翻。** 原假设为「单路 3 片已吃满 6 核，故并行无吞吐收益」。实测单路 CPU 均值仅 49.8%（53% 的采样点低于 50%），**远未饱和**；双路并行每路退化 1.31 倍，但串行跑两个任务需 454s 而并行只需 298s，**加速比 1.52 倍，并行有显著收益**。

**真实瓶颈是内存，不是 CPU。** 双路时 Commit 峰值 66.9 GB（上限 74.7 GB，余量仅 7.8 GB），可用物理内存最低跌到 2.8 GB，而物理内存总共 31.9 GB——约 2 倍超售、大量换页。CPU 尚有约 28% 余量，但内存已不允许再加一路，**2 路是当前 32 GB 配置下的实际上限**。

这同时解释了项目历史数据里「4 片比 3 片慢」（3m02s vs 2m03s）：不是 CPU 竞争，而是第 4 个测试 JVM 加剧了内存换页。

**据此订正 §九 D3**（见该节）。

### 二、过程中的干扰项（均已定位，非本计划引入）

1. **陈旧编译产物致 663 个 `NoSuchMethodError`**：首跑（实验 1a）报 663 个 `NoSuchMethodError`，签名与本仓在册的「多分支合并后增量编译解析旧签名」形态一致。实测根因为该树 `build/classes`（09-22 16:48）落后主源码（09-23 07:21）约 15 小时。按既有修法清项目本地 `build/classes` + `build/kotlin` 后重编译即全绿。

2. **`DialectDockerVerificationTest` 挂起 10 分钟以上（真问题，建议另行立项）**：该类经 `DockerClientFactory.instance()` 探测 Docker，而本机 Docker Desktop 进程虽在运行、`docker info` 却**超时无响应**（12 秒被杀），Testcontainers 的 npipe 探测却判定「成功」，于是测试真的去拉 MySQL/PostgreSQL/MariaDB/SQL Server/Oracle 23ai 镜像并阻塞——两次实验都停在 shard2 的同一位置（115 个类）。
   - 影响：任何全量测试（含门禁）在本机会被拖长 10 分钟以上或直接挂死。
   - 项目文档「无 Docker 时预期跳过 6 例」的前提在本机**不成立**（本机 Docker 处于「进程在跑但 daemon 无响应」的中间态）。
   - 本轮处置：实验期间临时移出该类，实验后**已还原**；`~/.testcontainers.properties` 的临时改动亦已还原。**未改任何产品代码**。
   - 建议：① 给探测加显式超时，或改用快速失败的策略；② 用户已指出 dbx MCP 里有可用真库（Oracle ×5、达梦、金仓、虚谷），Oracle 容器测试可改走 dbx 连接，省掉拉 Oracle 镜像的数分钟。

3. **实验期间环境**：并行登记表另有 11 条 active（多为未注销残留），进程实测确认无其他构建；读数在无干扰下取得。

### 三、A-1 迁盘（2026-09-23 09:50~10:05）

**执行**：停全部 Gradle/Kotlin daemon（`./gradlew --stop` 停 2 个，另手工停残留的 1 个 Gradle daemon + 1 个 Kotlin daemon）→ `robocopy C:\Users\USER\.gradle F:\gradle-home /E /MOVE` → `New-Item -ItemType Junction`。

**读数**：

| 项 | 结果 |
|---|---|
| 搬移量 | 140114 个文件 / 70947 个目录 / **40.003 GB**，**0 个失败** |
| 耗时 | 14m23s（复制 11m30s + 删源 2m53s），均速 62 MB/s |
| robocopy 退出码 | 1（0~7 为成功） |
| C 盘可用 | **2.7 GB → 43.0 GB** |
| F 盘可用 | 137.7 GB → 97.5 GB |
| junction | `Directory, ReparsePoint` → `F:\gradle-home` |
| 迁后 Gradle | `./gradlew help` **BUILD SUCCESSFUL in 19s**，`10 actionable tasks: 1 executed, 9 up-to-date` |

**P-1 被证伪（好消息）**：原推断「迁盘后 transform 缓存与增量编译历史因绝对路径变化而失效、首次构建退化为全量」**不成立**——迁后首个构建 9/10 任务仍为 `up-to-date`，缓存完全有效。原因是目录联接保持了逻辑路径 `C:\Users\USER\.gradle` 不变，缓存内的路径引用因此依然有效。这也是 junction 相对「环境变量方案」的额外优势（后者改变逻辑路径，会触发缓存失效）。

**过程记录（Windows + Git Bash 坑）**：首次尝试在 Git Bash 里直接调 `robocopy` 失败——MSYS 把 `/E` 参数转换成 `E:/` 路径，robocopy 报「无效参数 #3」并直接退出，**未产生任何改动**（源目录完好，零损坏）。改用 PowerShell 脚本执行即成功。**结论：Windows 命令行工具带 `/X` 形式参数时不要在 Git Bash 里直接调用。**

**拆除联接的方法（风险提示）**：只能用 cmd 的 `rmdir`（只删联接本身）或 PowerShell `Remove-Item`；**禁用 Git Bash 的 `rm -rf`**（可能跟随联接递归删除 F 盘的真实内容）。

**副作用**：搬移前停掉了共享 daemon，其他并行会话的下次构建需重启 daemon（多花约 30 秒），不影响正确性。

### 四、未做

- **未测 3 路及以上**（内存余量不足，且无第三个就绪工作树）。「2 路是上限」目前是**基于内存读数的推断**，非直接实测。
- ~~**未测「每路降片数」的影响**~~ **已于 2026-09-23 上午补测，降片方向被证伪**，见 §实施记录 五。
- ~~**A-1 迁盘未执行**~~ **已执行，见 §实施记录 三**。
- 实测日志（`p2-exp*.log` / `p3-r*.log`，3 秒粒度原始采样）实验后已清理，读数见文内各表。

### 五、降片对照实验与快通道验证（2026-09-23 10:13~10:38）

**设计**：同一对工作树（`mpsv-agent-config-locate` / `mpsv-hql-colmap`），验证「降每路片数能否换更多并行路」与「快通道的资源特征」。实验期间仍须临时移出 `DialectDockerVerificationTest`（Docker daemon 无响应会挂起，见 §二.2），两轮后均已还原。

**读数**：

| 配置 | 每路墙钟 | CPU 均值 | Commit 峰值 | 可用物理内存最低 |
|---|---|---|---|---|
| （参照）单路 3 片 | 227s | 49.8% | 63.0 GB | — |
| （参照）双路各 3 片 | 298s | 71.6% | 66.9 GB | 2.8 GB |
| 双路各 2 片 | **598s / 503s** | 79.4% | 62.0 GB | **0.3 GB** |
| 双路 testFast | **94s / 155s** | 80.1% | **57.5 GB** | 2.0 GB |

**结论一：降片换并行度——证伪。** 双路 2 片比双路 3 片**慢一倍**（每路 598s vs 298s），且可用物理内存反而从 2.8 GB 跌到 **0.3 GB**（换页风暴）。机制：片数 3→2 使每片的测试类从约 512 涨到约 770（时长均衡分区按片数重新贪心分配），单个测试 JVM 生命周期拉长约 1.5 倍、累积占用更大，叠加两路共享资源，时间 ≈ 类数 ×1.5 × 资源减半 ≈ 3 倍；而 Commit 峰值只省 4.9 GB——**省下的内存远抵不上拖长的时间，也换不来第三路的内存余量**。理论侧同样不成立：吞吐 ∝ 路数（每路时间 ∝ 片数，分子分母同约掉片数），降片本身不产生吞吐。

**结论二：日常验证的正解是 `testFast`，不是降片 testShards。** 双路 testFast 每路 94~155s（对比 testShards 双路 298s），Commit 峰值低 9.4 GB（57.5 vs 66.9），验证吞吐约 2 倍（0.78 vs 0.40 次/分钟）。与「测试三档分层」既有口径合流：日常定向/冒烟走 testFast，全量 3 片只在单路收口跑。

**结论三：双路是 32 GB 配置的硬上限**（三组双路配置 free 均 ≤2.8 GB，2 片甚至跌到 0.3 GB）。提高并行度只剩两条路：加内存（CPU 始终有 20~28% 余量），或加快验证通道（testFast）。

**环境注记**：本轮结束后的清理中发现，两个实验工作树已被并行治理会话摘除 worktree 注册（`.git` 指针与 `.git/worktrees/*` 已删、`git worktree list` 不再列出；对应分支清理属 BranchHygiene3 批次）——目录文件完好、两树 `DialectDockerVerificationTest.kt` 均已还原；worktree 元数据的删除属治理会话动作，本计划不做恢复。

### 六、B 项实施：daemon 参数统一 + Kotlin in-process（2026-09-24 00:30~01:45）

**实施位置**：独立工作树 `F:\code\Java\Gitee\SongRenShuo\mpsv-build-conc`（分支 `t-build-concurrency`，基于 dev 顶 `92b906e`）。开工第一步 `./gradlew --stop`（停 3 个 daemon，窗口期避开并行车道）。

**改动**：
1. 用户级 `~/.gradle/gradle.properties`（junction 实体 `F:\gradle-home\gradle.properties`）：`org.gradle.jvmargs` 由 `-Xmx2g -XX:MaxMetaspaceSize=256m` 改为 `-Xmx4096m -XX:MaxMetaspaceSize=1536m`，其余条目逐字保留（`codex.sdk.jar` 等）。
2. 项目 `gradle.properties`：新增 `kotlin.compiler.execution.strategy=in-process` 常驻配置（注释写明机制、内存代价承接、OOM 回退方式与 §九 D2 备选）；订正「jvmargs 被用户级覆盖从未生效」的过时注释。提交 `0472a59`。

**改前留底（用户级文件全文，2026-09-24 01:36 读）**：

```
# 并行构建
org.gradle.parallel=true

# 构建缓存
org.gradle.caching=true

# JVM 内存优化
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=256m

# 某第三方工程：其 build.gradle.kts 依赖一个本地 jar，默认值指向作者本机路径
codex.sdk.jar=<该工程本地的 codex-java-sdk jar 路径>
```

**改前读数**：`--stop`（01:35）后约 5 分钟，并行车道即拉起新 GradleDaemon（pid 18972），参数仍是 `-Xmx2g -XX:MaxMetaspaceSize=256m`（用户级旧值）——覆盖冲突与「每次构建拉新 daemon」的实证；此时机器另有 7 个非 gradle java 进程（IDE 等）。

**改后验收读数（§五 2）**：
- 编译密集定向：新工作树首编 `./gradlew :compileKotlin compileTestKotlin` → **BUILD SUCCESSFUL in 2m 57s**（24 tasks：18 executed + 6 from cache，全新树无增量可吃，属真全量编译），日志零 `OutOfMemory`/Metaspace 异常 → **P-3 成立**，daemon 1536m 元空间承接 in-process 编译足够；既有「`compileMcpStubKotlin` OOM」记录的前提（jvmargs 被压到 256m）已随参数统一消除。
- KotlinDaemon 进程数：**0**（编译全程无 `KotlinCompileDaemon` 进程，in-process 生效，§五 2 的「实例数为 0」达成）。
- GradleDaemon 参数：新建 daemon（pid 2400）参数 `-Xmx4096m -XX:MaxMetaspaceSize=1536m`，改后新建 daemon **取值唯一**；唯一 straggler（pid 18972，旧 2g/256m）产生于「`--stop` 之后、配置落盘之前」的窗口期，属改前遗产，与统一后参数不兼容、不会被复用，闲置 3 小时后自动退出（约束不许二次 `--stop`，如实记录）。
- 终采补记（02:12）：并行车道（分支未含本改动）的构建拉起 1 个 KotlinDaemon（pid 78660）属预期——in-process 配置在本分支，合入 dev 后各树生效；该 Kotlin daemon 的 JVM 参数已随用户级统一自动变为 `4096m/1536m`，Kotlin daemon 池的参数一致性作为 B 项统一的连带收益。

### 七、C 项实施：机器级构建闸门 dev-support/gradle-gate.ps1（2026-09-24 01:45~02:05）

**实现形态**（`dev-support/gradle-gate.ps1`，提交 `913f8c9`）：
- 锁目录 `%TEMP%\<GateName>\slot-0..slot-(N-1)`，**目录创建（create-or-fail）为原子互斥原语**；槽内 `holder.txt` 记 pid/session/utc/cmd。`%TEMP%` 按用户隔离，全部车道同一 Windows 用户运行时即等价机器级。
- 默认 `-MaxConcurrent 2`（内存约束依据 §九 D3）；**无任何降片逻辑**（§三 C 行已订正）。
- 两种用法：`-Command "<gradle 命令>"`（拿锁-执行-还锁-传播退出码，推荐）；`-Acquire [-HoldSeconds N] [-NoRelease]` / `-Release -Session <guid>` 两段式。
- 自愈：持有者异常退出不删目录，下一路按「holder pid 已死」立即 TAKEOVER；15 秒初始化宽限防「建目录-写 holder」窗口被误判；`-StaleAfterMinutes`（默认 240）兜底 pid 复用。
- 脚本头注释带 Windows 坑记录：`/X` 参数工具禁 Git Bash 直调（MSYS 路径转换）、robocopy 退出码 0~7 均成功、ps1 中文注释必须带 UTF-8 BOM。

**P-6 实测读数**（`-GateName` 独立命名隔离，不触真实闸门）：

| 用例 | 读数 | 判定 |
|---|---|---|
| 两路并发 + 第三路等待 | c1 01:48:42 取 slot-0、c2 01:48:43 取 slot-1（同时起步各得一槽）；c3 4s 后起步进入排队，01:48:55（c1 01:48:54 释放后）才取得 slot-0；总耗时 13s ≈ 12s 持锁 + 轮询粒度 | 通过，无双持 |
| 异常退出后锁释放 | 伪造死 pid 的 holder（起一个即退的进程取其 pid 写入 holder.txt）→ 下一路输出 `GATE TAKEOVER slot=0 previous holder dead or expired` 并成功 ACQUIRED；对照用活 pid 的槽全程未被接管 | 通过 |
| 退出码传播 | `-Command "cmd /c exit 7"` → 闸门进程退出码 **7**；`cmd /c echo` → **0**，命令 stdout 原样透传 | 通过 |

**实测过程发现（均已修复或记录）**：
1. **新建文件撞实时扫描**：`WriteAllLines` 写 holder 瞬间可报「正由另一进程使用」（AV/索引器对新生文件的瞬时独占），首轮实测直接崩死一个申请者并留下无 holder 的半初始化槽。修复：写入重试 5 次 ×200ms；重试耗尽删自建槽目录返回失败（绝不带槽假装持锁）。
2. **PowerShell 函数输出捕获陷阱**：`Acquire-Gate` 内 `Write-Output` 的状态行被调用方 `$got = Acquire-Gate` 捕获进返回值，`GATE ACQUIRED` 等状态行全部丢失。修复：函数一律静默，状态行只在顶层输出，内部事件经 `$script:GateEvents` 由顶层转储。
3. **沙盒环境 `Stop-Process` 失效**：对子进程 `-Force` 后进程仍存活（`taskkill //F` 可杀）——崩溃模拟改为「伪造死 pid」的确定性方案。
4. **ps1 编码坑**：UTF-8 无 BOM 的 ps1 被 PowerShell 5.1 按 GBK 解析，中文注释尾字节可吞掉换行符导致解析错——本机 ps1 一律带 BOM。

**验收对照（§五 3）**：并发数上限生效（第三路排队）✓；两路并发与异常释放读数齐 ✓；`docs/MEMORY.md` 条目——**待主控转录**（下方约定文案）。

**约定文案（主控转录进 docs/MEMORY.md「并行测试协调登记表」节）**：

> **构建并发闸门（2026-09-24 起）**：任何会话跑 gradle 前过机器级闸门——`powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\gradle-gate.ps1 -Command "<gradle 命令>"`。最多 2 路并行（32 GB 内存约束，加路先加内存），第 3 路自动排队（超时默认 3600 s），持有者异常退出后锁由下一路自动接管。**禁降片**：不许用 `-PtestShardCount` 调小换并行（实测证伪）。

### 八、F 项实施：登记表自动过期 + 沙盒名派生（2026-09-24 01:55~02:10）

**改动点**（`C:\Users\USER\.zcode\cli\parallel_tasks.py`，仓库外文件，原子换装）：
1. 自动过期：新增 `EXPIRE_GRACE_HOURS = 1.0` 与 `expire_stale()`——`check` 时把 `expectedEndAt` 已过且超宽限 1 小时的 active 条目改标 `status=expired`（记 `expiredAt`，**不删数据行**，历史可查；`expectedEndAt` 缺失/不可解析的保守不动）；`cmd_check` 先过期再判活，过期条目打点展示且**不触发 exit 1**。
2. 沙盒名派生：`register` 不传 `--sandbox` 时按当前工作树目录名派生 `MPSV-<dirname>`；传参仍以传参为准。
3. 文件头约定节与 `--help` 同步更新；既有行为（同名/同沙盒先注销旧的、裸 `done` 全注销、prune 按 startedAt 清理）逐字保留。
4. `buildSrc/mpv-testing.gradle.kts` **未动**（超出 F 项「或独立约定」的授权范围，维持沙盒名手工传入与派生名约定并存）。

**原子换装流程**：临时文件改好 → `%TEMP%` 独立数据文件受控自测 → `Move-Item -Force` 一次性覆盖正式脚本（无长半成品状态）。换装后并行车道 T-PreMainScope 即以新版成功 register（01:57:30），并发可用性顺带实证。

**受控自测读数**（`%TEMP%\parallel-tasks.json` 种子 2 条：过期/存活各一）：

| 用例 | 读数 | 判定 |
|---|---|---|
| check 自动过期 | 输出「自动过期 1 条」，仅存活条目列入 WARN；exit 1 由存活条目触发 | 通过 |
| 数据行保留 | 过期条目 `status=expired` + `expiredAt` 落盘，行未删 | 通过 |
| 沙盒名派生 | cwd=`mpsv-fake-tree` → `register` 落 `sandbox=MPSV-mpsv-fake-tree` | 通过 |
| 传参优先 | `--sandbox MPSV-Override` → 落 `MPSV-Override`（派生不生效） | 通过 |
| done 注销 | `done --name` → 「已注销 1 条」，check 不再列出 | 通过 |

**换装后真实表读数**：真实 `parallel-tasks.json` 中 **11 条**陈旧 active（最早 `expectedEndAt` 停在 2026-09-22 11:32，即 §1.5「登记表不可信」的存量）一次清为 expired；剩余 active 仅 T-BuildConc（本任务）与 T-PreMainScope（并行车道），exit 1 语义正常。**自测三连 `check` / `register`（派生） / `done` 全通**（§五 5 达成：自动过期 ✓、沙盒名可由工作树名推导、无需改 `build.gradle.kts` ✓）。

