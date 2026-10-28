# T-ConnCastSafety 真机复现与修复验收记录（demo 达梦环境）

**日期**：2026-10-28
**对象**：增强捕获 agent 在驱动 `connect` 出口替换宿主连接对象（`$Proxy` 无法强转为驱动实现类）
**复现/验收环境**：
- demo 工程 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo`（模块 `mybatis-plus-join-demo`）
- 达梦配置（demo 仓既有，无需新建）：`mybatis-plus-join-demo/src/main/resources/application-dm.yml`
  - driver `dm.jdbc.driver.DmDriver`，驱动包 `com.dameng:DmJdbcDriver18:8.1.3.140`（`mybatis-plus-join-demo/pom.xml:56-61`）
  - URL `jdbc:dm://114.66.55.245:20096?schema=MPJ_DEMO`，用户名 `MPJ_DEMO`
  - 切换方式：Maven profile `dm`（`pom.xml:97-104`，`-Pdm` 或 IDEA Maven 面板勾选）
- 旧插件：`build/distributions/mybatis-plus-sql-visualizer-2026.10.26.zip` 内嵌 `agent/mps-sql-agent.jar`
- 新代码：本地构建 `sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar`（含 T-ConnCastSafety 修复）
- JDK：1.8.0_181（本机默认）

## 一、探针

两个探针都是「取驱动原始连接 → 按用户现场写法强转到驱动实现类」，差别只在驱动：

| 探针 | 连接来源 | 强转目标 | 为什么需要它 |
|---|---|---|---|
| `DmConnCastProbe` | `DriverManager.getConnection(jdbc:dm://…)` | `dm.jdbc.driver.DmdbConnection` | 用户报障的真实库与真实强转类型 |
| `MySqlConnCastProbe` | `DriverManager.getConnection(jdbc:mysql://localhost:3306/mpj_demo…)` | `com.mysql.cj.jdbc.ConnectionImpl` | 达梦 8.1.3.140 的 connect 面织入在旧代码上是 **VerifyError**（见 §二.2），形不出代理；换一条织入能成功的驱动，才能把用户那句 `$ProxyN cannot be cast to <驱动实现类>` 原样打出来 |

探针源码在 `C:/Users/USER/AppData/Local/Temp/conncast-old/`（临时探针，不入任何仓库）。

## 二、读数

### 1. 基线（不挂 agent）

```
[probe] connection class = dm.jdbc.driver.DmdbConnection
[probe] CAST OK -> dm.jdbc.driver.DmdbConnection
```

### 2. 旧插件 agent（2026.10.26）+ 达梦 8.1.3.140

```
[SqlVisAgent] attach retransform: matched=0 wove=0 unmodifiable=0 failed=0 | transformer: weavable=0 woven=0 noChange=0 transformFailed=0
Exception in thread "main" java.lang.VerifyError: Bad local variable type
  Location: dm/jdbc/driver/DmDriver.connect(Ljava/lang/String;Ljava/util/Properties;)Ljava/sql/Connection; @57: aload_1
  Reason:   Type top (current frame, locals[1]) is not assignable to reference type
```

即：旧 agent 织出的 `DmDriver` 字节过不了 JVM 校验，**驱动类根本加载不起来**。这不是「采不到事件」，而是**宿主应用连驱动都用不了**——比 CCE 更早、更狠地让程序起不来，与用户「程序死活跑不起来」的现象同源。

### 3. 旧插件 agent + MySQL 8.0.33（用户 CCE 形态）

```
[probe] connection class = com.sun.proxy.$Proxy0
[probe] CAST FAILED -> java.lang.ClassCastException: com.sun.proxy.$Proxy0 cannot be cast to com.mysql.cj.jdbc.ConnectionImpl
```

与用户报障 `com.sun.proxy.$Proxy129 cannot be cast to dm.jdbc.driver.DmdbConnection` 是同一形态、同一代码路径（`ConnectWeaver` → `onConnection` → `ProxySupport.wrapConnection`），只差驱动实现类名。**用户的 DM 驱动版本其 connect 出口帧形态与 8.1.3.140 不同（无 `chop_frame`），织入成功才会形出代理**——这条差异正是「为什么用户看到 CCE、而 demo 同版本看到 VerifyError」的解释。

### 4. 新代码 agent + 达梦 8.1.3.140

```
[probe] connection class = dm.jdbc.driver.DmdbConnection
[probe] CAST OK -> dm.jdbc.driver.DmdbConnection
```

### 5. 新代码 agent + MySQL 8.0.33

```
[probe] connection class = com.mysql.cj.jdbc.ConnectionImpl
[probe] CAST OK -> com.mysql.cj.jdbc.ConnectionImpl
```

两端一致：修复后宿主拿到的就是驱动自己的连接对象，实现类强转不可能失败。

## 三、采集未劣化（e2eFakeIde）

关掉连接代理通道后，必须证明「事件照常到达、源码位置照常可跳」——否则就是把崩溃换成了功能归零。

`./gradlew e2eFakeIde`（假 IDE + 真 Spring Boot/MyBatis-Plus-Join 应用 + 真 MySQL + 新 agent）：

```
HELLO=pid=13056 @1318ms HEARTBEAT=1 EVENT(累计)=4 CONFIG_ACK=5 UNKNOWN_CMD=0
断言 PASS=26 FAIL=0
RESULT: ALL-PASS
```

源码定位（应用侧 `[T-SourceJumpFunnel]` 取证）落在真实业务行：

```
primary=cn/song/mpjdemo/service/impl/DeviceInfoServiceImpl.java:92 via=L1 entry=...#joinOneAndCount
primary=cn/song/mpjdemo/service/impl/DeviceInfoServiceImpl.java:42 via=L1 entry=...#joinRightAlias
primary=cn/song/mpjdemo/service/impl/MpjFullCoverageServiceImpl.java:103 via=L1 entry=...#ifExistsFamily
```

即：事件、CONFIG 通道（停采/恢复/脱敏/收口五段）、调用栈定位全部照旧，走的是语句面织入通道而非连接代理。

（首轮跑失败为环境残留：上一次被中断的 e2e 留下了 FakeIde 进程占住探针端口，清理后重跑即全绿；与应用/插件行为无关。）

### 3.1 参数与影响行数（真机 + 真驱动，`O147RealAgentAcceptanceTest`）
关掉连接代理后立刻暴露的第一个真回归就是参数：事件还在、`params` 全空。该用例用真 JVM + 真 H2/MyBatis 跑 agent，首跑直接红：

```
realJvmEventsCarryJdbcFacts: java.util.NoSuchElementException: List is empty   ← params.single()
```

配套把参数采集搬进语句面织入（`setXxx` 方法头插桩）后转绿；期间又钓出一处既有缺陷——织入层把 `execute()` 的 `boolean` 返回值也装箱成 `Integer`，导致 `updateCount` 走不到 `getUpdateCount` 分支：

```
realJvmEventsCarryJdbcFacts: java.lang.AssertionError: expected:<1> but was:<null>
```

按方法描述符返回类型（`)Z`）修正装箱后，该用例 4 个方法全绿。agent 单测 182/0/1（含新增「参数面_setXxx入口采集_事件携参与脱敏同口径」）。

## 四、为什么「同一份旧包，我这边崩启动、你那边崩 curl」——校验开关

用户实机（IDE 2026.2 日常环境）复现得到的是 `ClassCastException`、应用**启动正常**；而命令行复现得到的是 `VerifyError`、应用**起不来**。差异不在驱动版本，在 **JVM 字节码校验开关**：

| 启动方式 | 校验 | 旧包在达梦上的表现 |
|---|---|---|
| `java -cp … DemoApp`（及 `-jar`、`mvn spring-boot:run` fork） | 默认开 | 织入产出的 `DmDriver.connect` 帧非法 → `VerifyError: Bad local variable type`，Hikari 建池失败 → **应用起不来** |
| IDEA Spring Boot 运行配置（「Enable launch optimization」默认开，JVM 参数含 `-noverify` / `-Xverify:none`） | 关 | 坏帧不被校验，织入「成功」，`connect` 返回被换成 JDK 代理 → 应用正常启动，宿主强转时 **CCE** |

同一 demo、同一 `DmJdbcDriver18:8.1.3.140`、同一份 2026.10.26 agent 的对照读数（本轮实测，dm profile，`--server.port` 8097/8096）：

```
# 校验开（java -cp）
dataSource 工厂方法抛 java.lang.VerifyError: Bad local variable type   → 上下文初始化中止

# 校验关（java -Xverify:none）
Started MybatisPlusJoinDemoApplication in 6.093 seconds
GET /probe/conn-cast →
  connClass       com.sun.proxy.$Proxy78
  proxyHandler    cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler
  proxyInterfaces dm.jdbc.internal.IDmdbConnection, java.sql.Wrapper, java.lang.AutoCloseable, java.sql.Connection
  detail          com.sun.proxy.$Proxy78 cannot be cast to dm.jdbc.driver.DmdbConnection
```

最后一行与用户报障 `com.sun.proxy.$Proxy129 cannot be cast to dm.jdbc.driver.DmdbConnection` 只差代理编号。⇒ **两种症状同源**（都是我们的连接替换 + 达梦出口坏帧），修复同时覆盖：不再替换对象（消 CCE）+ 撤出达梦 connect 面（消 VerifyError）。

> 取证方法留档（下次复用）：demo 里新增 `/probe/conn-cast`（`ConnCastProbeController`），走 `DriverManager` 取原始连接并强转到驱动实现类，同时回显 `proxyHandler` / `proxyInterfaces` / `agentMarker` / `jvmArgs`。判归属看 `proxyHandler`：我们的包装是 `cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler`。
> 排障中的一次误判已纠正：曾以「两次请求拿到同一个 `$Proxy96`」推断「不是我们的代理」——实际两次是重启后的不同 JVM，代理类编号各自从零计，不能作判据。

## 五、XA 链路（用户实际配置：Atomikos + `DmdbXADataSource`）

报障用户补充了真实用法：`atomikosDataSourceBean.setXaDataSourceClassName("dm.jdbc.driver.DmdbXADataSource")`，JDK 1.8.0_301。这条链路不走 `DriverManager`，必须单独验。用驱动自带的 `DmdbXADataSource` 直接复刻（不引 Atomikos），三组对照：

| 组合（均 `-Xverify:none`，模拟 IDE 运行） | `XAConnection.getConnection()` | handler | 接口集 |
|---|---|---|---|
| 不挂 agent（基线） | `com.sun.proxy.$Proxy0` | `dm.jdbc.driver.DmdbPooledConnection$1` | `IDmdbConnection, Connection` |
| 旧包 agent | `com.sun.proxy.$Proxy0` | 同上（达梦自己的 XA 包装） | `Connection, IDmdbConnection, Wrapper, AutoCloseable` |
| 新包 agent | `com.sun.proxy.$Proxy0` | 同上 | `IDmdbConnection, Connection`（与基线逐项一致） |

三条结论：

1. **达梦 XA 自己就返回代理**：基线（完全不挂 agent）下 `XAConnection.getConnection()` 也是 `$Proxy0`，handler 是 `dm.jdbc.driver.DmdbPooledConnection$1`。所以「把 XA 拿到的连接直接强转到 `DmdbConnection`」在任何情况下都失败——这是达梦 XA 的设计，不是插件缺陷；正确取法是 `unwrap(DmdbConnection.class)`（基线/旧包/新包三组 unwrap 都成功）。
2. **旧包确实把我们的代理塞进了达梦 XA 包装里**：旧包那组接口集比基线多 `Wrapper, AutoCloseable`，正是外层达梦代理把内层对象的接口并进来的结果，说明内层不是驱动原生连接。
3. **修复覆盖 XA 路径**：新包接口集与基线逐项一致，内层已回到 `DmdbConnection`。用户「达梦/Atomikos 内部对连接做强转」的形态，修复后不会再撞到我们的 `$Proxy`。

> 因此对报障用户的交付口径应是：升级到 2026.10.28 后，XA + Atomikos 场景下插件不再介入连接对象；若用户代码自己把 XA 连接强转到 `DmdbConnection`，那是达梦 XA 的固有行为（基线同样失败），需改用 `unwrap`。

## 六、结论与边界
- **复现成立**：旧 agent 在 demo 达梦链路上直接把驱动打废（VerifyError）；换织入成功的驱动即可原样打出用户 CCE 文本。**修复成立**：新 agent 在两条链路上都回到「宿主原对象」。
- **边界**：本节验的是 agent 与真实驱动的交互，未跑 runIde 全 UI 流程；「插件侧注入 `-javaagent` → 应用启动 → 面板出条目」的完整 GUI 链路由 `e2eFakeIde` 与既往 runIde 批次覆盖。
- 用户驱动版本未取得，`DmdbConnection` 强转的发起方（用户代码/框架）亦未取证；本轮按「代理无法伪装具体类」的构造性归因，不依赖上述两点。
