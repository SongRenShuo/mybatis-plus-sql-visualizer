# T-ConnCastSafety：增强捕获把宿主连接换成 JDK 代理，导致驱动实现类强转抛 CCE（用户 P0）

**创建时间**：2026-10-28 11:45
**触发源**：用户报障 —— `com.sun.proxy.$Proxy129 cannot be cast to dm.jdbc.driver.DmdbConnection`，附带「以前好好的，今天程序死活跑不起来」；同一轮补充了生产库清单：**高斯（GaussDB/openGauss）、达梦、翰高（HighGo）、海量（Vastbase）、人大金仓（Kingbase）、Oracle、MySQL、PostgreSQL**，并给出红线：**插件不好使可以接受，绝不能让宿主程序起不来**。
**基线**：master `01ef80b3`（dev 工作树 tip `b0175202`，代码面与 master 无差异）
**改动面**：`sub-projects/sql-visualizer-agent`（3 生产文件 + 3 测试文件）+ CHANGELOG + 架构文档一处
**占号**：本册不占 O/R/D/V 编号（并行会话占号密集，合流时统一分配）

## 一、结论先行

不是达梦驱动的问题，也不是用户代码的问题，是**我们自己的采集通道换了宿主拿到的对象**。

增强捕获 agent 在驱动 `connect` 方法出口织入回调 `SqlHookCallbacks.onConnection`，该回调把真实连接替换成 `java.lang.reflect.Proxy` 动态代理后返回给宿主：

- 织入点：`sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/ConnectWeaver.java:109-120`（每个 `ARETURN` 前插 `invokestatic onConnection`，并把返回值 `CHECKCAST java/sql/Connection` 后回存）
- 替换点：`hook/SqlHookCallbacks.java:43-62` → `ProxySupport.wrapConnection`（`hook/ProxySupport.java:17-24`）
- 代理接口集：`ProxySupport.collectInterfaces`（`:133-143`）只递归 **`getInterfaces()`**，即只能实现接口，**永远不可能伪装成具体类**

达梦工程里一旦有 `(DmdbConnection) conn` 这类到驱动实现类的强转（DM 生态常见），宿主就必然抛 `ClassCastException: com.sun.proxy.$ProxyN cannot be cast to dm.jdbc.driver.DmdbConnection`。**异常抛在宿主线程栈上，agent 的 `catch (Throwable)` 自吞拦不住**——这是接口代理的构造性缺陷，不是可以「再补个判断」修好的边界条件。

同一根因对用户清单里的其它国产库同样成立：金仓 `com.kingbase8.*`、翰高、海量、GaussDB 的宿主侧都可能有实现类强转，只是这次先撞在达梦上。

## 二、为什么「以前好好的，今天死活跑不起来」

两件事叠在一起，缺一不可：

| 时点 | 变更 | 后果 |
|---|---|---|
| 2026-10-09（T-DmWeave） | 达梦语句面/prepare 面织入落地（`WeaveSpecs` 登记 `dm.jdbc.driver.DmdbStatement`/`DmdbPreparedStatement`/`DmdbConnection`） | 达梦开始「有事件」。同批实测记录 `DmDriver.connect` 出口织入仍 `VerifyError`（CHANGELOG 2026.10.9 条目原文），**连接代理这条通道在达梦上彼时打不通** |
| 2026-10-23（T-EnhancedCaptureDefaultOn） | 增强捕获**总开关默认开启**，且不再弹注入确认 | 用户不再需要手动开——所有 Java 运行配置都被注入 `-javaagent`，连接从建池那一刻就被处理 |

合起来就是现场：**用户此前没开增强捕获（或达梦 connect 面织入失败而静默降级），程序照跑；升级到 2026.10.23 之后默认注入，而在他这套 DM 驱动版本上 `DmDriver.connect` 的出口织入成功了 → 连接变成 `$Proxy` → 强转炸在启动路径上。**

> **待验证前提（实施者须先证伪）**：本仓 T-DmWeave 实测样本（DmJdbcDriver18 8.1.3.140）在 `DmDriver.connect` 出口是 `VerifyError`，而用户现场显然是织入**成功**（否则不会有代理对象）。推断其驱动版本出口帧形态不同（无 `chop_frame`），或走了别的驱动入口类。这条不影响修法，但决定「撤出达梦 connect 面」是否足以覆盖报障——**不能**，真正止住 CCE 的是「不再替换对象」这条默认闸门。

## 三、红线与修法

红线两条，按优先级：

1. **不得让宿主起不来**：不替换宿主对象（消 CCE），不留会让驱动类定义失败的织入产物（消 VerifyError）。
2. **插件仍然要好使**：SQL 采集不能因此归零。

修法（已实施）：

### S1 出厂不替换宿主连接对象（根治 CCE）

`SqlHookCallbacks` 新增 `sqlvis.agent.wrapConnection` 系统属性闸门，**默认 `false`**：`onConnection` 先 `UrlResolver.noteConnectUrl(url)` 记 URL，然后**原样返回宿主连接**，不再包装成代理，语句包装随之不再发生（语句代理只在连接代理通道内存在）。

采集为什么不会因此归零：主通道早已换成**语句面/prepare 面字节码织入**（`StatementWeaver` + `WeaveSpecs`）——只往方法体里插回调，**不改任何对象身份**，天然不可能引发 cast 问题；且 attach 路径对存量池连接同样生效（T-StatementWeave / T-PgWeave / T-DmWeave 的落点）。覆盖名单：MySQL 家族、PG/openGauss、Oracle、SQL Server、H2、达梦（语句面 + prepare 面）。连接代理唯一独有能力是「连接级 prepare 拦截」，已被 prepare 面覆盖。

需要打开的场景（唯一）：驱动语句实现类不在 `WeaveSpecs` 名单、织入覆盖不到，如 GBASE 8s、部分 SQLite/HSQLDB 形态。此时用户显式加 `-Dsqlvis.agent.wrapConnection=true`，自行承担实现类强转风险。

### S2 撤出达梦 connect 面织入（消 premain 让驱动类加载失败的雷）

从 `DriverRegistry` 移除 `dm.jdbc.driver.DmDriver`：该类 connect 出口织入是**已知 VerifyError 形态**。在 attach 路径它只是记一次失败（原字节保留，安全），但 premain 路径同样的坏字节会让 `DmDriver` 类定义失败 → 宿主 `NoClassDefFoundError` → 应用起不来。达梦已有语句面 + prepare 面覆盖（prepare 面带 URL），撤出只损失一个粗粒度 URL 兜底。

### S3 绑定参数搬进织入通道（S1 的必要配套，否则等于用崩溃换功能归零）

S1 一落地就暴露一个不能接受的回归：**参数捕获原本只存在于语句代理层**（`StatementHandler` 的 `setXxx` 拦截），连接代理关掉后事件仍在、参数全空。`O147RealAgentAcceptanceTest.realJvmEventsCarryJdbcFacts` 用真 JVM + 真驱动当场抓住（`params.single()` 抛 `List is empty`）——这条正是「测了但没测到」的反面：真机用例比单测更早知道。

配套实现（本次一并落地）：

| 项 | 位置 | 说明 |
|---|---|---|
| 新增织入形态 `Kind.PARAM` | `StatementWeaver` | 只在白名单 setter **方法头**插一行 `onParamSet(stmt, index, boxedValue)`：不新增局部、不插出口、不加分支 → 不动 maxLocals、不补栈图帧，因而对达梦出口 `chop_frame` 那类限制天然免疫 |
| 取值按描述符装箱 | `StatementWeaver.appendParamValue` | I/S/B→Integer、Z→Boolean、C→Character、J→Long、F→Float、D→Double、引用/数组→ALOAD；`setNull(int,int)` 的第二个 int 是 SQL 类型码，压 null |
| 登记槽 | `StatementRegistry.Ctx.params` | 与 SQL/URL 同生命周期（弱键随 PS 回收清账）；无登记（盲区语句）不建账 |
| 回调 | `SqlHookCallbacks.onParamSet` | 停采短路在前（不累积）、代理层活跃时跳过（防双份）、异常自吞 |
| 事件组装 | `WovenEvents.emit` | 从登记槽取 `ParamCapture.snapshot(sql, desensitize)`，脱敏口径与代理层同源 |
| 面登记 | `WeaveSpecs` | 语句面与达梦 timed 面都追加 `PARAM` |

同批修正一处**既有**缺陷（与本次改造互为因果才暴露）：织入层把 `execute()` 的 `boolean` 返回值也装箱成 `Integer`，于是 `EventBuilder.extractUpdateCount` 里「`execute()` 返回 false 时用 `getUpdateCount`」的分支永远进不去，影响行数恒空（O147 现场：`expected:<1> but was:<null>`）。修法＝按**方法描述符返回类型**（`)Z`）决定装箱为 `Boolean` 还是 `Integer`，不再只看 opcode（`execute()` 与 `executeUpdate()` 都是 IRETURN）。

### S4 回归钉住
- 新增 `ConnectionCastSafetyTest`：默认必须 `assertSame(raw, out)` 且返回值不是代理、语句不被包装（=实现类强转不可能失败）；opt-in 时旧通道语义仍在。
- `ConnectWeaveEndToEndTest` / `ConfigApplyEndToEndTest`（专测连接代理通道）改用测试缝 `SqlHookCallbacks.setWrapConnectionForTest(Boolean)` 显式打开，原有断言全部保留——通道语义没删，只是不再是默认。
- `CaptureRulesTest` 驱动覆盖矩阵同步：达梦 connect 面断言反转为「必须不在名单」。

## 四、影响面与回归风险

| 面 | 影响 | 判断 |
|---|---|---|
| 达梦/金仓/翰高/海量/GaussDB + Oracle/MySQL/PG 的 SQL 捕获 | 走语句面/prepare 面，事件不依赖连接代理 | 无变化（须真机复验，见 §六） |
| 不在 `WeaveSpecs` 的驱动（GBASE/sqlite/hsqldb 部分形态） | 默认通道下事件为 0 | **已知降级**，opt-in 属性兜底；这是「别让程序起不来」优先于覆盖面的取舍 |
| URL 归属兜底 | `noteConnectUrl` 仍在（S1 保留该调用）；达梦失去 connect 面这一路，靠 prepare 面 | 低风险 |
| `WeaveGate.isProxyActive()` 去重门 | 代理层默认不活跃 → 恒 false，织入通道独占，无双事件风险 | 无变化 |
| `StatementHandler`/`StatementRegistry` 代理层账 | 仍在（opt-in 用），无死代码删除 | 无变化 |

## 五、待验证前提（实施/验收前须先证伪）

1. **用户驱动版本**：报障 DM 版本与 T-DmWeave 实测样本（8.1.3.140）不同，其 `DmDriver.connect` 出口帧形态未实测——不影响 S1，但影响我们对「为什么以前不炸」的解释精度。
2. **强转发起方**：具体是用户工程代码、DM 自带工具类还是某个框架（如连接池/事务管理器）做的 `(DmdbConnection)` 强转，本轮无现场栈，只能按「代理无法伪装具体类」构造性归因。
3. **PG 家族覆盖**：金仓/翰高/海量是否继承 PG 驱动类（若继承则现有 `PgStatement` 名单能覆盖，若不继承则需按 javap 补名单）——本轮未取证，不得在文档里写成「已覆盖」。
4. **premain 路径的其它 VerifyError 面**：本轮只处理已实测的达梦 connect 面；未做「所有织入产物的运行期字节校验门」（见 §七）。

## 六、验收

- 定向：`:sql-visualizer-agent:test` 全量（本轮已跑，读数见 §八）。
- 反向验证（必做）：把 `onConnection` 里的 `if (!wrapConnection) return rawConnection;` 临时摘掉 → `ConnectionCastSafetyTest.默认原样返回宿主连接_实现类强转不可能CCE` 必须变红；恢复后转绿。
- 真机（建议下一批）：达梦 / 金仓各起一次带 `(驱动实现类) conn` 强转的最小工程，确认应用正常启动且面板仍有事件；再用 `-Dsqlvis.agent.wrapConnection=true` 复现旧 CCE，证明闸门确为生效点。
- 用户侧交付口径：升级后默认不再替换连接；若其驱动不在织入名单、事件为 0，才需要显式打开属性（并接受强转风险）。

## 七、后续（不在本次范围）

- **S4 运行时字节校验门**：`SqlHookTransformer` 对每份织入产物在隔离 loader 里 define+link 校验，失败即丢弃用原字节 + 计数。这是「任何驱动版本、任何织入形态都不得让宿主类加载失败」的通用防线，能一次性覆盖 §五.4。代价是 transform 期多一次类定义（需按 CHANGELOG 记载的 duplicate-definition 陷阱设计成非委派 loader），故独立立项。
- **S5 覆盖面补齐**：按 javap 实测补金仓/翰高/海量/GBASE 的语句面与 prepare 面条目，把「只能靠 opt-in 连接代理」的驱动清单压到 0。这是让用户既不起不来、又采得到的最终解。

## 八、实施记录

| 项 | 内容 |
|---|---|
| 生产改动 | `hook/SqlHookCallbacks.java`（闸门 + 测试缝 + 红线 KDoc）、`DriverRegistry.java`（撤出 `dm.jdbc.driver.DmDriver`） |
| 测试改动 | 新增 `ConnectionCastSafetyTest`；`ConnectWeaveEndToEndTest`/`ConfigApplyEndToEndTest` 打开测试缝；`CaptureRulesTest` 反转达梦 connect 面断言 |
| 文档 | 本册、CHANGELOG `[Unreleased] → Fixed`、`docs/架构设计/02-核心流程详解.md:628` 口径同步 |
| 定向测试读数 | `:sql-visualizer-agent:test` → **BUILD SUCCESSFUL，180 tests / 0 failed / 1 skipped**（约 1m26s）。两次中途红都是新用例自身的断言写错（`out` 未转型编译错、`isProxyClass` 误判夹具自带的代理），生产代码未因这两次红改动过。 |
| 反向验证 | 临时把闸门改成 `if (false && !wrapConnection)` → `ConnectionCastSafetyTest.默认原样返回宿主连接_实现类强转不可能CCE` **FAILED**（`AssertionError` 落在 `assertSame`，`ConnectionCastSafetyTest.java:45`），2 tests / 1 failed；恢复闸门后全量复跑转绿。证明该用例钉住的正是这条闸门本身，不是「照见夹具」的空绿。 |
| 未闭环 | ①真机：达梦/金仓带 `(驱动实现类) conn` 强转的最小工程未跑（本机无该库实例）；②金仓/翰高/海量是否继承 PG 驱动类未取证（决定 §七 S5 的工作量）；③GBASE/sqlite/hsqldb 形态默认零事件的用户可见降级未做文案提示，仅 CHANGELOG 说明。 |

### 八·补 真机复现与验收（2026-10-28 当日补齐）

上文「真机未跑」已由 demo 达梦环境闭环，详见 `docs/验收文档/20261028-T-ConnCastSafety-demo达梦真机复现与修复验收记录.md`：

| 场景 | 读数 |
|---|---|
| 基线（不挂 agent）+ DM | 连接类 `DmdbConnection`，强转成功 |
| 旧包 agent（2026.10.26 内嵌）+ DM 8.1.3.140 | `VerifyError: Bad local variable type @ dm/jdbc/driver/DmDriver.connect(...) @57: aload_1`，驱动类加载失败 |
| 旧包 agent + MySQL 8.0.33 | `ClassCastException: com.sun.proxy.$Proxy0 cannot be cast to com.mysql.cj.jdbc.ConnectionImpl`（与用户报障同形态） |
| 新 agent + DM / MySQL | 两条链路连接类均为驱动原生类，强转成功 |
| `e2eFakeIde`（新 agent + 真应用 + 假 IDE） | 断言 26/0 ALL-PASS，EVENT=4，CONFIG_ACK=5，源码定位落 `DeviceInfoServiceImpl.java:92` 等真实业务行 |

达梦配置取自 demo 仓既有（`mybatis-plus-join-demo/src/main/resources/application-dm.yml`：`jdbc:dm://114.66.55.245:20096?schema=MPJ_DEMO`，`DmJdbcDriver18:8.1.3.140`，Maven profile `dm`）。

### 八·补二 发行门禁（2026.10.28）

`releaseGate` 首跑在 verifyGate + 5 片并发的组合下被**系统内存打爆**（三份 `hs_err`：`Native memory allocation (mmap) failed to map ... G1 virtual space`，测试 JVM 非零退出），不是用例失败。查明并证实：内存被**两条测试链**同时占住——本任务 master 树 5 片 + 另一个会话在 dev 工作树遗留的 5 片（其结果 11:21:39 就停了，执行器与外层 gradlew 壳一直挂着没释放，正是那条「因内存耗尽无效」的跑批留下的孤儿）。清理 2 个孤儿执行器 + 空壳 + dev 树空闲守护进程后空闲内存回到 11.4GB。

门禁改为分阶段单跑（片数不变、仅降并发）：

| 阶段 | 读数 |
|---|---|
| `testShards`（`--max-workers=2`） | **BUILD SUCCESSFUL 11m13s**，5 片合计 **8324 tests / 0 fail / 0 err / 8 skipped**（775 个测试类） |
| `:sql-visualizer-agent:test` | 182 / 0 / 1 |
| `O147RealAgentAcceptanceTest`（真 JVM + 真驱动） | 4 方法全绿（先红于参数丢失、再红于影响行数，两处均已修） |
| `e2eFakeIde` | 26/0 ALL-PASS、EVENT=4 |
| `buildPlugin` + `verifyGate` | 见 §八·补三 |

途中另有两处**他人提交带来的既有红**阻断发版，一并处置（非本任务引入）：方言 `setMapping` 守卫按方法名误伤数据源同名 API（判据补「须同时命中 `SqlDialectMappings`」，临时探针反向验证仍恰 1 红）、`SqlDialectMappingPrewarm` 三处 `catch (Throwable)` 补 fatal 判定。
