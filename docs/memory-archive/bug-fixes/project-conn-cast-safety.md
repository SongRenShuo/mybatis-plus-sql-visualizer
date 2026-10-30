---
name: project-conn-cast-safety
description: 增强捕获连接代理致 CCE/VerifyError（20261028 用户 P0，v2026.10.28 已发行 id 1248331 并经用户沙盒复测通过）——修法＝出厂不替换宿主 JDBC 对象（wrapConnection opt-in）+ 撤出达梦 connect 面 + 参数捕获搬进织入通道；含 JVM 校验开关决定「启动崩 vs curl 崩」、XA/Atomikos 链路对照、探针取证法
metadata:
  node_type: memory
  type: project
  originSessionId: session_7092525161dcaf3e3e32e7027a8a534d
---

# T-ConnCastSafety：采集不得改变宿主 JDBC 对象身份（20261028 用户 P0）

用户报障原文：`com.sun.proxy.$Proxy129 cannot be cast to dm.jdbc.driver.DmdbConnection`，附「以前好好的，今天程序死活跑不起来」，并给出生产库清单 **高斯 GaussDB、达梦、翰高 HighGo、海量 Vastbase、人大金仓 Kingbase、Oracle、MySQL、PostgreSQL**，红线原话：**「插件不好使了，别让程序起不来，这是红线」**。用户随后要求「在 demo 项目里用旧插件复现 → 用新代码确认解决 → 再发行，update.json 只能他手动改」。

## 根因（构造性，不是可补的边界条件）

- 织入点 `ConnectWeaver`：在驱动 `connect` 每个 `ARETURN` 前插回调，返回值 `CHECKCAST java/sql/Connection` 后回存——即**替换宿主拿到的对象**。
- 替换点 `SqlHookCallbacks.onConnection` → `ProxySupport.wrapConnection`；代理接口集只用 `collectInterfaces` 递归 `getInterfaces()`。
- JDK 动态代理**只能实现接口、永远不可能伪装具体类** ⇒ 宿主任何 `(DmdbConnection) conn` 必抛 CCE；异常抛在宿主线程栈上，agent 的 `catch (Throwable)` 自吞**拦不住**。同根因对金仓/翰高/海量/GaussDB 一并成立，只是先撞在达梦。
- 「以前好好的、今天才坏」的拐点＝2026-10-23 增强捕获默认开且不再弹确认（此前不启用根本不会替换连接）。达梦在 T-DmWeave 时代 connect 面织入恒 VerifyError，等于从没被代理过。

## 修法（已实施并提交 `b3ba8838`，v2026.10.28 发行版）

1. **S1 出厂不替换宿主连接对象**：`SqlHookCallbacks` 新增 `sqlvis.agent.wrapConnection` 系统属性闸门（默认 false）——先 `UrlResolver.noteConnectUrl(url)` 记 URL，然后原样返回；代理通道保留为显式 opt-in。测试缝 `setWrapConnectionForTest(Boolean)`。
2. **S2 撤出达梦 connect 面**：从 `DriverRegistry` 删 `dm.jdbc.driver.DmDriver`（该出口已知 VerifyError，premain 下让 `DmDriver` 类定义失败 → NCDFE → 起不来；达梦已有语句面+prepare 面）。
3. **S3 参数捕获搬进织入通道（S1 的必要配套，否则等于用崩溃换功能归零）**：新增 `StatementWeaver.Kind.PARAM`——**只在白名单 setter 方法头插一行** `onParamSet(stmt, index, boxedValue)`：不新增局部、不插出口、不加分支 ⇒ 不动 maxLocals、不补栈图帧，**天然免疫达梦出口 chop_frame**。取值按描述符装箱（I/S/B→Integer、Z→Boolean、C→Character、J→Long、F/D 同理、引用/数组→ALOAD；`setNull(int,int)` 第二参是 SQL 类型码 → 压 null）。登记槽 `StatementRegistry.Ctx.params`（弱键随 PS 回收）；`WovenEvents.emit` 从槽取 `ParamCapture.snapshot(sql, desensitize)`，脱敏与代理层同源。`WeaveSpecs` 的语句面与达梦 timed 面都追加 `PARAM`。

## 真机复现与验收（demo 达梦环境，已闭环）

- demo 仓 `mybatis-plus-join-demo` 天然带达梦配置：`src/main/resources/application-dm.yml`（`jdbc:dm://114.66.55.245:20096?schema=MPJ_DEMO`、用户 `MPJ_DEMO`）、依赖 `com.dameng:DmJdbcDriver18:8.1.3.140`、Maven profile `dm` 切换。
- 探针＝取驱动连接后按用户写法强转到实现类，跑两个驱动：
  - 旧包（2026.10.26 zip 内嵌 `agent/mps-sql-agent.jar`）+ **达梦 8.1.3.140** → `VerifyError: Bad local variable type @ dm/jdbc/driver/DmDriver.connect(...) @57: aload_1`（驱动类根本没加载起来，比 CCE 更早把应用打死）
  - 旧包 + **MySQL 8.0.33** → `ClassCastException: com.sun.proxy.$Proxy0 cannot be cast to com.mysql.cj.jdbc.ConnectionImpl`（与用户报障同形态，只差实现类名）
  - 新包 → 两条链路强转均成功
- `e2eFakeIde`（假 IDE + 真应用 + 真驱动）：26/0 ALL-PASS、EVENT=4、CONFIG_ACK=5、源码定位落 `DeviceInfoServiceImpl.java:92` 等真实业务行。
- `O147RealAgentAcceptanceTest`（真 JVM + 真 H2/MyBatis）当了两回哨兵：先红于 `params` 为空（`List is empty`）、再红于 `updateCount` 为 null（`expected:<1> but was:<null>`）。

## 顺带挖出的既有缺陷（不是本次改造引入）

织入层把 `execute()` 的 **boolean** 返回值也装箱成 `Integer`——`execute()` 与 `executeUpdate()` 都是 IRETURN，只看 opcode 分不出来，于是 `extractUpdateCount` 里「execute 返回 false → 读 `getUpdateCount`」的分支永远进不去，影响行数恒空。修法＝按**方法描述符返回类型**（`)Z`）决定装箱成 `Boolean` 还是 `Integer`。

## 门禁与发行推进（20261028 收口途中状态）

- 修完 S1/S2 后跑 `testShards --max-workers=2` 逐轮暴露的三处红，**都不是本次改造引入**但都由本次改造才照出来：①`BannedApiSourceScanTest.方言setMapping反射只允许DialectPreMapping单入口` 按**方法名**判，把 T-ConsoleBind 新增的 `ExecFileBinding.realignExecBind`（调的是数据源 `DataSourcePerFileMappingsImpl.setMapping`，另一个同名平台 API）误判成绕开收口 → 判据收紧为「须同时命中 `setMapping` 与 `SqlDialectMappings`」，并用**临时 TEMP-RED 探针类**（模拟方言 side 直调）反向验证仍能抓真绕开，验完即删。②`SqlDialectMappingPrewarm` 三处 `catch (Throwable)` 缺 `FatalErrors.isFatal(t) throw t`。③参数/影响行数两条真回归见上文。
- 阶段门禁（`releaseGate` 一条命令）在本机内存条件下不可靠：实测 13m28s 被内存打断；改为**分阶段单跑**并只把 `--max-workers` 降到 2（片数不变）。
- 发行包已在 `build/distributions/mybatis-plus-sql-visualizer-2026.10.28.zip` 产出，`inspect-plugin-zip.py` 首轮曾 FAIL：**changeNotes 块扫到全角括号**（`ClassCastException（$Proxy …）`）——红线是块内禁「（ ） ：」，改成逗号后合格；`preview.update.json` 同步改字面量（`update/update.json` 始终保持未动）。
- 尚未完成：分片全量拿到干净读数 → 打 tag `2026.10.28` → 推 Gitee → 建发行版并回读校验。

## 已发行（20261028 收口完成，门禁读数已回填）

- 门禁：`testShards --max-workers=2` **8324/0/0/8**（11m13s，775 类）+ agent **182/0/1** + `O147` 真机 4 方法全绿 + `e2eFakeIde` 26/0；`verifyGate` 三目标全 **Compatible**（IU-243 5/41/98、253 6/52/96、262 8/125/96）。
- 产物 `build/distributions/mybatis-plus-sql-visualizer-2026.10.28.zip`，7,224,507 字节，sha256 `c0f20924a572d41d…`，`inspect-plugin-zip.py` 全 PASS。
- tag `2026.10.28` + master 已推 Gitee；**Gitee 发行版 id 1248331**，`--verify-only` 回读附件 sha256 一致。提交链 `b3ba8838`（主体）→ `8c960343`（参数织入 + 两处既有红）→ `f5f740a2`（读数回填）→ `766b6655`（发行台账 id）。
- `update/update.json` 全程未动（用户手改）；`preview.update.json`、CHANGELOG、changeNotes、MEMORY 索引、变更记录、架构 02 均已同步。

## 「启动崩 vs curl 崩」＝JVM 字节码校验开关（20261028 下午已结案）

用户实机（日常 IDE 2026.2 跑 demo）复现出的是 **CCE、应用启动正常**；命令行复现出的是 **VerifyError、应用起不来**。差异**不在驱动版本**，在字节码校验：

| 启动方式 | 旧包在达梦 8.1.3.140 上的表现 |
|---|---|
| `java -cp … DemoApp`（及 `-jar` / `mvn spring-boot:run` fork），校验默认开 | 织入出的 `DmDriver.connect` 帧非法 → `VerifyError: Bad local variable type`，Hikari 建池失败 → **起不来** |
| IDEA Spring Boot 运行配置（「Enable launch optimization」默认开，JVM 参数含 `-noverify` / `-Xverify:none`） | 坏帧不校验，织入「成功」，`connect` 返回值被换成 JDK 代理 → **启动正常**，宿主强转时 CCE |

**本轮实测对照**（同一 demo、同一 agent、dm profile，只改 `-Xverify:none`）：校验关时 `Started MybatisPlusJoinDemoApplication in 6.093 seconds` + 探针返回 `connClass=com.sun.proxy.$Proxy78`、`proxyHandler=cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler`、`proxyInterfaces=dm.jdbc.internal.IDmdbConnection,java.sql.Wrapper,java.lang.AutoCloseable,java.sql.Connection`、`detail=$Proxy78 cannot be cast to dm.jdbc.driver.DmdbConnection` —— 与用户报障只差代理编号。**两种症状同源**，已发布修复两条都堵（不替换对象消 CCE + 撤达梦 connect 面消 VerifyError）。

**排障中自造的一次误判（教训）**：曾以「用户两次请求拿到同一个 `$Proxy96`」推断「不是我们的包装」。实际那两次是**重启后的不同 JVM，代理类编号各自从零计**，编号相同毫无判据力。真正判据＝`Proxy.getInvocationHandler(conn)` 的类名。另：拿 `jcmd GC.class_histogram` 数 handler 实例也靠不住（当时读到 0，而对象其实在）。

demo 探针 `mybatis-plus-join-demo/src/main/java/cn/song/mpjdemo/controller/ConnCastProbeController.java`（`GET :8082/probe/conn-cast`）：**必须走 `DriverManager` 不走 `DataSource`**（Hikari 自己就返回代理，测不出插件行为）；强转目标按 `driver-class-name` 前缀分派（`dm.`→`DmdbConnection`、`com.mysql`→`ConnectionImpl`，两者 javap 为 public）；并回显 `proxyHandler` / `proxyInterfaces` / `agentMarker` / `weaverLoaded` / `javaVersion` / `jvmArgs`（`jvmArgs` 里可直接看到 `-Xverify:none`）。demo 无 devtools，**改动后必须重启应用**才生效。该文件是 demo 仓**未跟踪**新文件，未替用户提交。

## XA 链路（用户真实配置：Atomikos + `DmdbXADataSource`）

用户补充真实用法：`atomikosDataSourceBean.setXaDataSourceClassName("dm.jdbc.driver.DmdbXADataSource")`，环境 JDK **1.8.0_301**。用驱动自带 `DmdbXADataSource` 直接复刻（不引 Atomikos），三组对照（均 `-Xverify:none`）：

| 组合 | `XAConnection.getConnection()` | handler | 内层接口集 |
|---|---|---|---|
| 不挂 agent（基线） | `$Proxy0` | `dm.jdbc.driver.DmdbPooledConnection$1` | `IDmdbConnection, Connection` |
| 旧包 | `$Proxy0` | 同上（达梦自己的 XA 包装） | 多 `Wrapper, AutoCloseable` ← **里面包的是我们的代理** |
| 新包 | `$Proxy0` | 同上 | 与基线逐项一致 ← 内层已是 `DmdbConnection` |

三条结论：①**达梦 XA 自己就返回代理**，把 XA 连接直接强转到 `DmdbConnection` 在任何组合下都失败（基线同样失败），那是驱动设计不是插件缺陷，正确取法是 `unwrap(DmdbConnection.class)`（三组都成功）；②旧包的问题形态＝达梦 XA 外层代理的内层被我们换过，达梦/Atomikos 内部一旦对本层强转就抛 CCE；③新包接口集与基线逐项相同，XA 路径已被修复覆盖。
**交付口径**：升级后 XA+Atomikos 场景插件不再介入连接对象；若用户代码自己强转 XA 连接，需改 `unwrap`，与插件无关。

## 代价与未闭环

- 语句实现类**不在** `WeaveSpecs` 的驱动（GBASE 8s、部分 SQLite/HSQLDB）默认不再出事件，需 `-Dsqlvis.agent.wrapConnection=true` 并自行承担强转风险；「不让程序起不来」优先于覆盖面。
- **未测**：金仓/翰高/海量是否继承 PG 驱动类（未 javap 取证，不得写「已覆盖」）；驱动实现类强转的发出方（用户代码还是框架）未取证。
- **后续**：S4＝transformer 对每份织入产物做隔离 loader 的 define+link 校验，失败即用原字节（通用防线，须避开 duplicate-definition 陷阱）；S5＝按 javap 补金仓/翰高/海量/GBASE 名单，把 opt-in 依赖压到 0。
- 用户 20261028 另给了一份 DBX smoke 环境清单（`114.66.55.245`：openGauss 20105、Kingbase 20097、达梦 DM8 20096 / DM7 20123 / DM6 20122、GBase 8a 20116 / 8s 20117、XuguDB 20111 等），补名单时可直接用（凭据在用户消息里，本档不抄密码）。

## 改 agent 时的固定检查项（第 4 条）

前三条见 [[project-agent-enhanced-capture-consent]]（COMPUTE 帧/局部槽、premain 兜底）。第 4 条：**任何「把宿主对象换成我们造的对象」的设计都要先问「宿主会不会按实现类强转」**——接口代理对具体类强转是构造性必炸，不能靠 try/catch 兜；同理名单新增驱动时先确认其 connect 出口织入在 **premain** 路径可过 JVM 校验，否则宁可少一个兜底。第 5 条（本次新增）：**关掉一条采集通道前先数清它承载哪些字段**——参数就只在代理层有，关代理立刻让 `params` 归零。

计划册 `docs/开发计划/20261028-1145-T-ConnCastSafety-*.md`、验收册 `docs/验收文档/20261028-T-ConnCastSafety-demo达梦真机复现与修复验收记录.md`、发行说明 `docs/发行说明/20261028-1200-*.md`。参见 [[project-agent-enhanced-capture-consent]]、[[project-enhanced-capture-default-on]]、[[pitfall-java-linkage-visible-at-link-time]]。
