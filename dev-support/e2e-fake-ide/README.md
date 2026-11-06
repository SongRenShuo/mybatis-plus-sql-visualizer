# e2e-fake-ide —— 假 IDE 探针：真机级端到端验证

用**自己写的 loopback socket 服务端**替代 IntelliJ GUI（不跑 runIde），把「IDE → 应用侧 CONFIG 通道」
与「源码位置定位」两条链在**真实应用**（真 Spring Boot + 真 MyBatis-Plus-Join + 真 MySQL JDBC 驱动）
上一次钉死。

## 一条命令复现

**推荐（T-AgentResiduals R7 起已纳 gradle，属发布前置）**：

```bash
./gradlew e2eFakeIde
```

- 任务会先构建 agent jar（`dependsOn(":sql-visualizer-agent:agentFatJar")`），再跑本目录的 `run-e2e.sh`；
- **已挂 `releaseGate`**（发版链路一命令：buildPlugin → verifyGate → testShards → **e2eFakeIde**），
  **不挂 `testShards`**（日常门禁不因此变慢）；时序上排在最后——它启动真实应用并占用端口；
- 前置缺失（demo 工程 / JDK / agent jar / MySQL）一律**硬失败并给指引**，不静默跳过
  （跳过＝把「没验」伪装成「验过」）；
- 覆盖项：`-Pe2eDemoDir=<demo 路径>`、`-Pe2eMvn=<mvn 路径>`、`-Pe2eJavaHome=<jdk 路径>`
  （Gradle daemon 的 PATH 不含交互式 shell 的 JDK，任务把 JDK 交给脚本，脚本再把
  `$JAVA_HOME/bin` 前置进 PATH）。
- 应用默认使用 8082；被占用时从该端口起向后找最多 10 个空闲端口，并给应用显式传
  `--server.port`、为探针生成相同端口的请求脚本。手动运行可用 `APP_PORT` 改变起始端口；
  全部候选被占用则在启动前失败。收尾仅清理本轮 agent HELLO 对应的进程，不按端口误杀他人应用。

手动运行（排障用，等价）：

```bash
# 前置：agent jar 已构建（在仓库根执行）
./gradlew :sql-visualizer-agent:agentFatJar -Pkotlin.compiler.execution.strategy=in-process

# 跑端到端（探针 + 应用 + 断言）；demo 工程位置见下（MPSV_DEMO_ROOT）
bash dev-support/e2e-fake-ide/run-e2e.sh
```

退出码 `0` = 全部断言通过；非 0 = 有断言失败（详见 `evidence/probe.log` 的 `SUMMARY` 段）。

**前置条件**：本机 MySQL `127.0.0.1:3306`（`root/root`，库 `mpj_demo`，表 `car_device_info` / `station_info` / `user` / `user_address` / `area` 有数据）；
demo 工程位置由环境变量 `MPSV_DEMO_ROOT` 提供（其下的 `mybatis-plus-join-demo`，键名见 `.local/local.env.example`），不再写死本机路径（2026-10-10 T-LocalEnvOverride）；
`~/.m2` 已有 demo 依赖（离线跑）。

## 可复跑读数（P-6 取证：2026-09-22 R7 纳门禁时连跑三轮）

断言窗口内的读数是**逐值一致**的（不是「都绿」而已）；帧累计计数随运行时长浮动，不参与判定。

| 读数 | 第 2 轮（23.8s） | 第 3 轮（44.7s，慢跑） |
| --- | --- | --- |
| 停采窗口 3000ms 内事件数（A 段） | 0 | 0 |
| 恢复后窗口 3000ms 内事件数（B 段） | 2 | 2 |
| 脱敏关 / 开窗口各 2000ms 内事件数（D1/D2） | 1 / 1 | 1 / 1 |
| 收口停采窗口 2000ms 内事件数 | 0 | 0 |
| 断言 | PASS=26 FAIL=0 | PASS=26 FAIL=0 |
| 定位器 `primary`（`T-SourceJumpFunnel`） | `DeviceInfoServiceImpl.java:92/42/63` + `MpjFullCoverageServiceImpl.java:103` | 同上，逐值一致 |
| `CONFIG_ACK` 累计 | 5 | 5 |
| `EVENT` / `HEARTBEAT` 累计 | 4 / 2 | 6 / 1（窗口外多出的就绪探测与心跳；断言窗口内读数不变） |

第 3 轮刻意取更慢的一次运行（应用启动 + 反射包装耗时约翻倍），窗口内读数仍逐值相同——
判定不依赖「跑得快」，只依赖控制通道与定位链的实际行为。

## 产物

| 文件 | 内容 |
| --- | --- |
| `evidence/probe.log` | 探针全量帧级日志：每帧的「时刻 / 命令 / 摘要」+ 每条断言的 PASS/FAIL |
| `evidence/app.log` | 应用 stdout+stderr（agent 的 `System.err` 诊断打点在此：`[T-SourceJumpFunnel]` 等） |
| `evidence/run-meta.txt` | 运行元数据（agent jar sha256、demo git HEAD、java 版本） |
| `evidence/p1-stack-evidence.txt` | P-1 栈成分取证（真机 jstack 采样 + 定位器诊断行） |
| `evidence/stack-probe.log` | 上述 jstack 采样那一轮的探针日志 |

## 文件

| 文件 | 作用 |
| --- | --- |
| `FakeIde.java` | 假 IDE 探针（自包含，仅 JDK 标准库，Java 8 编译） |
| `script-e2e.txt` | 主验证脚本：握手 → A 停采 → B 恢复 → C 位置 → D 脱敏 → 收口 |
| `script-panwei-gaussdb.txt` | 磐维 V1 驱动 + GaussDB 内核的采集验收脚本（`/order/list` 无参 + 带参 + `GROUP BY` 三种形态）；用法见下节「换驱动/换库」 |
| `script-stackprobe.txt` | P-1 栈采样专用脚本（只握手 + 开采集 + 保持连接 45s） |
| `run-e2e.sh` | 驱动：编译探针 → 起探针 → 起应用（`-javaagent`）→ 等脚本 → 收口 → 打印读数 |

## 换驱动 / 换库（DEMO_DIR / DEMO_PROFILE / APP_MAIN / E2E_SCRIPT）

默认跑 join-demo + mysql。要验「别的驱动」或「别的库」，四个变量一起给，命令行参数优先级高于 demo 构建态：

```bash
DEMO_DIR=<demo 仓>/mybatis-plus-demo \
DEMO_PROFILE=panwei-gaussdb \
APP_MAIN=cn.song.mybatisplusdemo.MybatisPlusDemoApplication \
E2E_SCRIPT=$PWD/dev-support/e2e-fake-ide/script-panwei-gaussdb.txt \
  bash dev-support/e2e-fake-ide/run-e2e.sh
```

- `DEMO_DIR` 必须指向**具体模块**（有 `pom.xml` 与 `target/` 的那层）；
- `DEMO_PROFILE` 是 Spring profile，对应 demo 的 `application-<profile>.yml`；
- `APP_MAIN` 是该模块的 Spring Boot 启动类全限定名；
- `E2E_SCRIPT` 是探针脚本；脚本里的 `http://localhost:8082/` 会被替换成本轮 `APP_PORT`。
- 探针日志的 `EVENT` 行带 `u=`（事件 url）——它是 IDE 侧角标/方言映射的输入，换库验收时先看这一列。

## 协议契约（与仓库源码逐值一致）

```
帧   = [4 字节大端长度][1 字节命令][body UTF-8]
命令 = 1 EVENT / 2 HEARTBEAT / 3 CONFIG(IDE→应用) / 4 HELLO / 5 CONFIG_ACK(应用→IDE)
CONFIG body = {"disabled":<bool>,"desensitize":<bool>[,"skipPrefixes":["a."]]}
ACK    body = {"disabled":<bool>,"desensitize":<bool>,"skipPrefixes":<int>}
```

权威定义：应用侧 `sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java`
与 `EventBus.java`；IDE 侧 `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentProtocol.kt`。

时序要点：**探针必须先于应用就绪**——agent 在 `premain` 阶段即回连（`AgentMain.premain` → `EventBus.start`）；
探针脚本第一条 `wait-hello` 就是等这个连接。

## 探针脚本指令

| 指令 | 语义 |
| --- | --- |
| `mark <text>` | 打印分节标记 |
| `sleep <ms>` | 等待 |
| `wait-hello <ms>` | 等 HELLO 帧（超时即 FAIL） |
| `wait-http <url> <ms>` | 轮询 URL 直到有任意 HTTP 响应（应用就绪同步点，不看状态码） |
| `config <json>` | 下发 CONFIG 帧（cmd=3） |
| `expect-ack <substr>` | 等下一帧 ACK 且 body 含 substr（超时/不含即 FAIL） |
| `reset-events` | 事件计数与事件表归零（读数窗口起点） |
| `get <url>` | HTTP GET（触发业务 SQL），打印状态码与响应长度 |
| `assert-no-events <ms>` | 窗口内事件数必须为 0 |
| `assert-events <ms>` | 窗口内事件数必须 > 0 |
| `assert-event-sf-sl <file>:<line>` | 窗口内至少一条事件的 `sf`/`sl` 等于给定值（文件名按后缀匹配） |
| `report-events` | 打印窗口内全部事件（sql 前 100 字 + sf/sl/mk/p/cands/sd） |
| `summary` | 打印计数与失败清单 |
| `quit` | 结束 |

不带 `--script` 时从 stdin 读同名指令（人工排障用）。

## 读数与判据

- **A 停采生效**：CONFIG `disabled=true` → 收到 ACK（`"disabled":true`）→ 触发 `rightAlias`/`extSub`
  → 3000ms 窗口内 **0 条 EVENT**。
- **B 恢复采集**：同一进程内 CONFIG `disabled=false` → 收到 ACK → 同一请求 → EVENT 恢复，
  且 `sf`/`sl` 分别为 `DeviceInfoServiceImpl.java:42` 与 `:63`（两个不同业务调用点各自正确）。
- **C 位置取证**：应用日志里 `[T-SourceJumpFunnel]` 行的 `entry=` 即**真实 entryKey 原文**；
  `primary=` 是本次判定出的主位置。`[T-AgentSourceLocateStale]` 行是入口键 ≠ 主位置身份时的
  一次性摘要（与 `logLevel` 无关）——正常业务帧入口时**零输出**。
- **D 脱敏通道**：`/mpj/full/ifExists?tel=13800138000` 的绑定参数在 `desensitize=false` 时原样
  `"%13800138000%"`，`desensitize=true` 时按值形态（11 位手机号）打码 `"***"`。

## P-1 栈成分采样（不在 run-e2e.sh 内，手工两步）

`run-e2e.sh` 只覆盖「入口键实测值」；要证「MPJ 拦截器帧是否真在 execute 栈上」需另采一次 jstack：

```bash
cd dev-support/e2e-fake-ide
# 1) 起探针（只握手 + 开采集 + 保持连接 45s）
java -cp build FakeIde --port 53996 --script "$(cygpath -w $PWD/script-stackprobe.txt)" \
     --log "$(cygpath -w $PWD/evidence/stack-probe.log)" &
# 2) 起应用（同上，port=53996），等 evidence/stack-probe.log 出现「应用就绪」
#    记下探针日志里的 HELLO pid=<应用 PID>
# 3) 并发打请求 + 连打 jstack
for k in 1 2 3 4; do ( for i in $(seq 1 400); do curl -s -o /dev/null \
  http://localhost:8082/mpj/device/rightAlias; done ) & done
for i in $(seq 1 25); do /d/HuanJing/Java/jdk1.8.0_181/bin/jstack <应用PID> >> evidence/jstack-samples.txt; done
# 4) 抽出含 MPJInterceptor 的那一段栈
awk 'BEGIN{RS="";ORS="\n\n"} /MPJInterceptor/ && /DeviceInfoServiceImpl/ {print; exit}' evidence/jstack-samples.txt
```

## 环境噪音与已知边界（如实记录）

- **控制台中文乱码无害**：Windows 控制台代码页 936，探针的 `System.out` 中文在控制台显示为乱码；
  `--log` 写出的文件是 UTF-8，读数一律以文件为准（`evidence/probe.console.log` 仅为控制台副本，已 gitignore）。
- **停采帧到达的那一轮仍可能先写出 ≤64 条存量事件**（`SenderLoop` 已知边界，代码注释明确「勿顺手修」）：
  因此 A 段的 `reset-events` 放在 `expect-ack` **之后**——ACK 与事件尽出在同一轮内、ACK 在后，之后必无出站。
- **探针只看 `--log` 文件判定就绪**：`run-e2e.sh` 用 `grep LISTENING evidence/probe.log` 判监听，
  该文件为 UTF-8，`LISTENING` 是 ASCII，不受控制台代码页影响。
- **`-javaagent` 的 args 用 `;` 分隔**（`port=...;logLevel=info`）：Git Bash 下必须整体加引号，
  且脚本里用 `MSYS2_ARG_CONV_EXCL='*'` 关掉 MSYS 的路径/参数转换。
- **MSYS 路径与 Java 不兼容**：`/f/code/...` 交给 java 会被当相对路径（实测 `错误: 找不到或无法加载主类`），
  脚本统一经 `cygpath -w` 转换。
- **磐维驱动 + GaussDB 场景：应用启动约 16s 后钩子不再产出**（2026-11-06 复现 4 次，待三查）。
  现象：前 ~16s 事件正常（含参数与 url），之后 `execute` 不再产事件，但请求仍 200、心跳仍发
  （sender 活着）；`-Dsqlvis.agent.nestGate=false`、`-Dsqlvis.agent.catchAll=false` 关掉后现象不变
  → 与 NestGate、premain 兜底扫描都无关，指向「Hikari 池预热完成后，后续 PS 的 SQL 归属取不到」
  （宿主 stderr 有且仅有一条 `织入通道登记盲区：无登记 PS 执行被跳过 psClass=org.panweidb.jdbc.PgPreparedStatement unresolvedCount=1`）。
  暂按**观察项**登记，不阻塞磐维采集验收：`script-panwei-gaussdb.txt` 只保留一条业务请求（落在 16s 内）。
