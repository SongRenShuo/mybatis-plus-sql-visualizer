# T-Jdk8RedisAgentStartupCrash：JDK 8 接入 Redis 后增强捕获启动崩溃排查开发计划

- 日期：2026-10-15
- 范围：用户所称 2026.10.9 版本启动故障；先取证和复现，再决定是否需要修复。基线为发行标签 `2026.10.9@518d15bf` 与分析时 `master@98da27a8`，不可用后者替代前者认定用户实际加载的 agent。
- 状态：仅分析并立计划；未取得该用户原始 `hs_err_pid*.log`，未复现事故，未修改功能代码，未运行测试。不预占 O/R/D/V 编号，不提交、合并、推送或发版。
- 关联：`20261006-1139-T-DevAcceptFix-dev验收六缺陷根因分析与修复立项-开发计划.md` §2.6；`20261005-1148-T-StatementWeave-agent语句级织入-开发计划.md`；`dev-support/agent-capture-repro/README.md`。关联材料记录的是**别的事故/版本**，不能代替本次用户日志。

## 一、报告与证据边界

用户反馈“上一个版本 2026.10.9 让项目无法启动”，怀疑增强捕获；补充“之前没有连 Redis，现在连了 Redis”，目标应用运行在 JDK 8。截图是此前助手对崩溃日志的**解释文字截图**，提到 `EXCEPTION_ACCESS_VIOLATION (0xc0000005)`、`ApiConfiguration`/Spring CGLIB、若干 `ClassNotFoundException`、多个 Java agent 和 JDK 8 小版本；**不是原始崩溃日志**。截图中的 agent 清单、JDK 补丁号、栈与“90% 概率”等判断均待原件核实，不能写成事故事实。截图里的项目/个人路径不纳入计划，也不要求用户公开敏感 SQL、凭据。

仓库与本机已找到的 `hs_err` 分属 IDE 编译服务器内存分配失败，且时间与进程身份不符；`dev-support/agent-capture-repro/demo-postfix-crash.log` 是另一场 MySQL 语句织入 `VerifyError` 复现，不是此用户 `ApiConfiguration` 的 JVM 原生访问冲突。现阶段**无法判定是 Redis、增强捕获、两者相互作用、其他 agent、JDK 自身还是资源压力**。`ClassNotFoundException` 可以是框架正常的可选类探测；Java 栈中出现 `ApiConfiguration` 仅说明当时线程在此初始化路径，不能证明其构造器、Redis 或某插件写坏了 native 地址。Java `LinkageError`/`VerifyError` 与 JVM 致命 `0xc0000005` 是不同层级的失败，不得混称同一根因。

## 二、版本与机制对账（已核代码，不等于本次根因）

1. 发行标签 `2026.10.9@518d15bf` 的 `SqlHookTransformer` 只接受 `DriverRegistry.isWeavable` 的精确 JDBC Driver 类名；`ConnectWeaver` 只改 `connect(String, Properties)` 的返回前调用，写出使用 `ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS)`。该版名单含 JDBC（包括 MySQL/达梦等），**不含** Redis、Lettuce、Jedis、Netty 或用户的 `ApiConfiguration`。源文件：`sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/{SqlHookTransformer,DriverRegistry,ConnectWeaver}.java`，以 `git show 2026.10.9:<路径>` 为发行版权威，勿看当前文件冒充旧版。
2. Redis 连接成功可能改变 Spring bean 初始化顺序、触发不同的 JDBC 首次建连时机、网络线程与直接内存开销；如果应用构建也变了，还可能引入新的客户端/native 依赖。**仅从“现在连 Redis”不能推出 classpath 已变化或 Redis 客户端含 JNI**。由于 agent 不直接改 Redis 类，首要研究的是“Redis 开关是否让 JDBC driver 在不同状态下被织入”和“native 事故现场是否真有 agent/Redis 帧”，而非直接补 Redis 匹配器。
3. 仓库已有另一例 JDK8/Spring Boot/MySQL 的启动期 `LinkageError: attempted duplicate class definition`，说明 `COMPUTE_FRAMES` 在 transform 期解析类型可能重入加载；修复提交 `afb62e4a`（dev 分支历史）及当前 master 的 `ConnectWeaver.java:29-36,77-79` 改用零类加载 writer / `COMPUTE_MAXS`。**该提交不是 2026.10.9 标签的祖先**；tag 与修复分叉。旧版虽有同类风险，但已有实例涉及后续扩大的 Statement/prepare 织入面，而 2026.10.9 仅织 JDBC Driver.connect：不能把前例直接套成用户 `0xc0000005` 的证明。
4. 现行 master 的 `StatementWeaver`、`ZeroLoadClassWriter`、厂商语句面和相应测试属于**标签之后的实现范围**；当前版本测试绿不能证明旧版对用户现场无故障，也不能靠把旧版换成新版就声称修复了未定位的 native 崩溃。JDK 8 需要记录精确 `java -version`、应用实际 `java.exe` 路径与位数，截图文字出现的小版本号不代替原始命令行。

## 三、阶段 0：先确定哪个进程、哪个故障先发生

1. 从用户提供的**应用 JVM**原始 `hs_err_pid*.log`、崩溃前后 2 分钟应用控制台完整异常链（第一条 `Caused by` 起）、IDE 运行配置与 `idea.log` 时间戳取证。先核 PID/进程命令行/工作目录，排除误取 IDE、Gradle/编译服务器、其他服务的 `hs_err`。若未生成日志，查应用 JVM 的 `-XX:ErrorFile` 指向与进程工作目录，记录“缺失”而不构造事故栈。
2. 原始日志至少摘取：`# Problematic frame`（模块和符号）、`Current thread`、Java/native frames、VM Arguments（`-javaagent`/`-agentlib:jdwp`/堆与 direct-memory）、JDK 完整版本、OS/架构、动态库清单、native memory/可用内存及 pagefile、崩溃时间。记录应用启动前是否已出现 `VerifyError`、`LinkageError` 或异常导致 Spring 初始化停止；区分首因与报错处理时二次异常。日志分享前脱敏路径、IP、账号和密钥，保留类名、帧、模块与相对时序。
3. 用实际运行配置/应用进程参数和内嵌 jar 哈希确认插件 ZIP 版本、被加载的 `mps-sql-agent` 产物、增强捕获注入方式（启动前 `premain` 或启动中 `attach`）、其余 agent 是否同进程，以及 Redis/数据库客户端的确切版本。不要以 IDE 设置界面“已开启”推断应用真的带 `-javaagent`；也不要用本机当前构建产物替代发行 ZIP。
4. 故障分流：若首因是 JVM fatal error，按 native 帧和内存证据定位库/JDK/agent 交互；若为 Java `LinkageError`/`VerifyError`，查织入期类加载或字节码校验；若只有常规 Spring bean 创建失败，沿最早 `Caused by` 查配置/Redis/JDBC 连通性。不要把三类统一当成“字节码冲突”。

## 四、阶段 1：单变量复现与因果判定

在**隔离的测试应用/环境**固定同一应用包、JDK 8 小版本、数据库、依赖版本、启动参数和请求，除指定变量外不修改构建或配置。Redis 的“关闭”应只切连接/功能，不通过删 Redis 依赖改变 classpath；如果关闭导致业务启动必然失败，使用可启动的等价测试配置并注明不可完全对照。每格记录命令行、产物摘要、重复启动次数、最先失败时刻与 `hs_err` 摘要：

| | Redis 连接关闭 | Redis 连接开启 |
|---|---|---|
| 增强捕获关闭，证实应用无本 agent 的 premain/attach | A：应用基线 | B：只改 Redis |
| 增强捕获开启，证实应用真实加载同一发行 agent | C：只改增强捕获 | D：组合场景 |

- B、D 失败而 A、C 正常：优先查 Redis 接入、资源、native 库；C、D 失败而 A、B 正常：优先查增强捕获的 JDBC 织入；仅 D 失败：优先查启动时序、资源和两个子系统交互；全格都失败：对照条件或外部环境不稳定，停止归因。以上只是**排查方向**，须以首次异常/致命帧和重现稳定性裁决。
- 若增强捕获参与的格子失败，再仅变更注入时机：先禁 premain、应用稳定启动后单次 attach，比较启动期类加载和存量 JDBC 连接的区别；单独比较**发行原始 agent vs 对应当前候选修复产物**，其他 agent 固定。第三方调试/HTTP/Groovy agent 只有在已确认同进程且基线可复现后才逐个减法对照，不批量删除/清缓存；必要时再对照 JDK 8 补丁版本，不在未定位前让用户换 JDK 作为“修复”。
- 若 Redis 仅改变负载或时序，记录 Spring 初始化 Redis 成功、JDBC Driver 首次加载、Hikari 首次建连、agent 安装与崩溃的相对时间；`ApiConfiguration` 的构造器只在首个异常指向它或改动 Redis 开关可重复触发时再单独检查。若原生帧在 Netty/native SSL 或资源分配路径，优先按该实证路径缩小，而不擅自改 ASM。

## 五、阶段 2：由证据决定的最小修复与守卫

- **确认旧 `COMPUTE_FRAMES` 织入为首因**：在发行版隔离复现下先补“真实 JDK8 + 目标 JDBC 驱动 + Spring/Redis 启动顺序”的失败测试；检查当前 `ZeroLoadClassWriter(COMPUTE_MAXS)` 可否针对**旧版仅 connect 面**安全移植，保持回调/协议/类名单不扩面。反向把 writer 临时恢复旧形态应变红；再以前后同包同环境对照验证应用启动正常、SQL 正常、agent 能采集。不要直接搬后来的 StatementWeaver 并扩大注入面。
- **确认其他 Java agent 交互**：做双 agent 安装顺序及单独启用对照，收集两侧变换后的真实目标驱动字节；给宿主隔离/失败安全降级而不“关闭所有 agent 即已修复”。若无可验证的本插件修复点，记录兼容性边界与可逆缓解，不虚构补丁。
- **确认 Redis 自身或 native/JDK/资源首因**：由实际帧/内存决定应用依赖或环境修正；本插件仅在有独立证据表明扩大了故障时才改行为。保留原始诊断链，以免启停 agent 恰好改变时序而误归因。
- **未稳定复现**：先加不含 SQL 原文、凭据和全路径的有界诊断（注入方式、匹配 JDBC 类、transform 成败及首事件），保留“原因未定”；不得用合成 ASM 测试绿或“换 JDK 后暂时没崩”宣告解决。

验收至少包含故障原格复测与负对照、JDK8 子 JVM 真实类定义/启动（只解析 ASM 不够）、原异常与 JVM 致命信号未再出现、Redis 开/关与 agent 开/关四格的明确读数，且 SQL 业务结果不变。代理侧选择 `ZeroLoadClassWriterWeaveTest`、`ConnectWeaveEndToEndTest`、`AttachGateTest` 等实际存在的定向测试；涉及生产改码时先做删修复行必红的反向验证，报 run/pass/skip，跳过的 Redis/真库格不得报绿。实施者按项目并行登记与 Gradle 前 `parallel_tasks.py check`，只跑最相关定向测试；全部实施完成后独立只读收口单次全量 `testShards --no-configuration-cache`，真正发版前再按发行流程跑构建/兼容门禁。真实 IDE 用户工程的最终复测不可由假 TCP 接收端取代。

## 六、待验证前提与停手边界

1. 用户应用实际加载的是否为 2026.10.9 ZIP 内 agent、是否经 premain 而非运行后 attach、是否同时加载截图列举的其他 agent：以应用命令行/加载产物为准。
2. `0xc0000005` 是否来自用户应用 JVM、首个 fatal frame 是否涉及本 agent 触发的 JVM 类定义/ASM、Redis 客户端 native 库或内存耗尽：以原始完整 `hs_err` 为准；截图文字不足以裁定。
3. “接入 Redis”是只改连接状态，还是同时换了应用构建、Spring/客户端依赖或运行配置；若有变化先分解变量，不把多变量对照写作因果实验。
4. 当前 master 对旧版风险的覆盖范围与旧版产物的真实差异：先比 fat jar/字节码，不拿源码提交日期当是否已发版的证据。
5. 运行参数如果并未启用本 agent，不执行 agent 改码计划；若没有原始日志和稳定复现，停在可审计的候选假设与安全缓解，不做不可证伪的修复/发布。

本轮交付为调查与开发计划，**不是事故根因已确认或补丁已交付**。实施前第一件事仍是取得原始应用崩溃证据并复核四格基线。
