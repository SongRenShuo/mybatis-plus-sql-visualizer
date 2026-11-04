# Oracle 增强捕获 PreparedStatement 登记断链（T-OracleWrapperRegistry）

- 立项：2026-11-04 12:34
- 分支：master
- 现象来源：用户反馈「Oracle 的 SQL 好像不怎么打印，就打印 `select 1 from dual` 这个校验连接性的 SQL」
- 关联能力：增强捕获语句面/prepare 面字节码织入（T-StatementWeave / T-PgWeave），语句归属登记表 `StatementRegistry`，嵌套去重门 `NestGate`

## 一、现象

Oracle 应用接入增强捕获后，日志面板里只出现连接池校验语句（`select 1 from dual` / `drop table …` 这类 **plain Statement + 内联 SQL**），
MyBatis 业务 SQL（PreparedStatement）一条不出。H2 / MySQL / PG 同条件正常。

## 二、真机复现（2026-11-04，master HEAD 产物）

环境：`gvenzl/oracle-free:23-slim` 容器（测试服务器 `103.36.220.245:21521/FREEPDB1`，本轮新建独立实例，未触碰共享库）；
客户端 `ojdbc11 23.3.0.23.09`；JDK17；agent `sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar`；
宿主用 `-javaagent:…=port=…,logLevel=debug`（premain 路径），对端是自建的极简 IDE 接收器（帧格式 `len:int / cmd:byte / body:utf8`）。

用户应用侧执行序列（`OracleProbe`）：

1. `Statement.executeQuery("select 1 from dual")` —— 连接校验形态
2. `prepareStatement("select dummy from dual where dummy = ?")` + `setString` + `executeQuery()`
3. `prepareStatement("select count(*) from dual where 1 = ?")` + `execute()`
4. `Statement.executeUpdate("create table t_probe_oracle …")`
5. `prepareStatement("insert into t_probe_oracle(id,name) values (?,?)")` + `executeUpdate()`
6. 同 SQL 的 `addBatch`/`executeBatch`

**改前读数**（事件条数 3 / 期望 6）：

| 形态 | SQL 是否产出事件 |
|---|---|
| 1 `select 1 from dual`（plain Statement） | ✅ 产出 |
| 4 `create table …`（plain Statement） | ✅ 产出 |
| 2 / 3 / 5 / 6（全部 PreparedStatement 族） | ❌ 全部缺失 |

宿主 stderr 同步打出（logLevel=debug）：

```
[SqlVisAgent] 织入通道登记盲区：无登记 PS 执行被跳过（SQL 不可得）
  psClass=oracle.jdbc.driver.T4CPreparedStatement unresolvedCount=1
```

诊断探针（反射读 agent 内部状态，`OracleDiagProbe`）给出断点位置：

```
DIAG conn=oracle.jdbc.driver.T4CConnection prepareDeclaredIn=oracle.jdbc.driver.PhysicalConnection
DIAG after-connect weavable=5 woven=4 noChange=1 failed=0      ← 织入本身是成功的
DIAG psClass=oracle.jdbc.driver.OraclePreparedStatementWrapper ← prepareStatement 返回的是包装器
DIAG registryGetAfterPrepare=Ctx{sql=select dummy from dual where dummy = ?}  ← 登记挂在包装器上
DIAG registryGetAfterExecute=Ctx{sql=…}                        ← 执行后包装器登记仍在
（同一次执行，内层 T4CPreparedStatement 查表落空 → 盲区）
```

对象形态探针（`ShapeProbe`）确认三种语句全部被包装：

| 入口 | 实际返回类 | 内层实体字段 |
|---|---|---|
| `createStatement()` | `oracle.jdbc.driver.OracleStatementWrapper` | `statement` |
| `prepareStatement(String)` | `oracle.jdbc.driver.OraclePreparedStatementWrapper` | `preparedStatement` |
| `prepareCall(String)` | `oracle.jdbc.driver.OracleCallableStatementWrapper` | `callableStatement` |

## 三、根因

两处缺陷叠加，缺一不可：

### 3.1 登记对象与执行对象不是同一个（主因）

Oracle JDBC 从 10g 起就用包装器（`Oracle*StatementWrapper`）作为对外句柄，
`execute*` 在包装器里只是转发给包内物理语句（`T4CPreparedStatement` 等）。

- prepare 面钩子插在 `PhysicalConnection.prepareStatement(String)` 的 `ARETURN` 前，
  登记的是**包装器**实例 → 执行时 SQL 查不到绑定关系；
- 语句面 `EXEC_CTX`/`BATCH` 钩子插在 `OraclePreparedStatement.executeQuery()` 等方法体上，
  `this` 是**内层物理语句** → 用内层对象查 `StatementRegistry` 必然落空 → 按「盲区」丢弃；
- plain Statement 之所以能出，是因为它走 `EXEC_PARAM`（SQL 直接取首参），不依赖登记表——
  这也解释了「只有校验 SQL 能看见」这个现象形态。

同样原因还让 Oracle 的**绑定参数**丢掉：`setXxx` 钩子写在 `OraclePreparedStatement` 上，
参数记在内层对象，而登记表里只有包装器，`captureParam` 查不到槽位直接跳过。

### 3.2 NestGate 把「进入过」当成「产出过」（放大器）

`NestGate.exit()` 在深度 > 0 的帧上**先**置 `innerEmitted=true` 再返回「可以产」，
而是否真的产出要到 `StatementRegistry.getOrCountUnresolved` 之后才知道。
于是「内层是盲区、外层有登记」这种形态会被判成「内层已报、外层让位」，外层那条能出的 SQL 也被吞掉。
这条不修，只补包装器织入也仍然零事件。

### 3.3 同一帧的「第二条 ATHROW 出口」被当成两次执行（本轮真机顺带抓到）

复验时另发现：一条**执行失败**的 DDL 会产出 3 条事件（同一 SQL、耗时递增）。
探针栈形（`OneCallProbe`，Oracle Free 23）：

```
executeLargeUpdate:2514  ← 85.99ms（try 内抛出时的 athrow）
executeLargeUpdate:2517  ← 86.34ms（处理器清理后重抛的 athrow）  ← 同一帧的第二个出口
executeUpdate:2494       ← 86.55ms（外层）
```

一个方法里 **try 内的 `athrow` + 处理器重抛的 `athrow`** 是两条独立字节码指令，
织入器在每条前都插了出口钩子；一次调用会先后命中两条 ⇒ 同一帧入栈 1 次、出栈 2 次：
深度被多减一次，最外层判定随之错位，嵌套去重失效（3 条重复事件）。
`executeLargeUpdate`/`executeUpdate` 这类带锁 finally 的方法普遍是这个形态，与 Oracle 无关，
其他驱动一样会中；只是 Oracle 包装器链路把外层也拉进了织入帧，重复条数更直观。

## 四、方案

### 4.1 登记内层实体（主修）

- 位置：`StatementRegistry` 新增 `registerWithDelegates(statement, sql, url)`，`SqlHookCallbacks.onStatementPrepare` 改调它。
- 做法：登记返回值本身之外，再**反射扫描一层**「字段类型可赋给 `java.sql.Statement`」的实例字段并登记同一 SQL/URL：
  - 扫描顺序＝从实际类向父类、每个类只取**声明字段**里第一个命中（Oracle：`preparedStatement` / `callableStatement` / `statement`）；
  - `Field` 按类缓存（`ClassValue`，避免每次 prepare 重复扫描，也不静态钉住驱动类，热部署可回收）；
  - 全程 `catch (Throwable)`，拿不到就退回旧行为（只登记包装器），不改变宿主对象身份、不碰执行语义。
- 为什么不做「织入包装器」取而代之：包装器类属于驱动私有实现（jar 内 javap 实测包私有），
  且逐版本新增/改名不可控；登记内层是「同一事实的另一种挂法」，顺带把参数槽与批串也接回内层对象。

### 4.2 NestGate 只在真产出后抑制外层

- `exit()` 改为「返回本帧是否**可以**产」；新增 `markEmitted()`。
- `WovenEvents.emit` 返回 boolean（是否真的入队），`SqlHookCallbacks.onExecuteExit` 仅在 `true` 时调用 `markEmitted()`。
- 线程态在 `enter()`（depth 归零时）重置，不再靠 `remove()`，语义等价且避免残留。

## 五、验证口径

1. **真机复验（主判据）**：同一容器/驱动/探针，改后 6 条全出：
   - `select 1 from dual`、`create table`、2/3 两条查询、insert、batch；
   - PS 事件带 params（`setString`/`setInt` 值在场），URL 角标正确；
   - 宿主导出无「登记盲区」行（`unresolvedCount` 不因内层帧增长）。
2. **attach 路径**：应用先建连接再 attach，存量 PS 下一次执行即出事件（与非 Oracle 驱动同口径）。
3. **池化形态**：HikariCP + `connectionTestQuery=select 1 from dual`，校验 SQL 与业务 SQL 同时可见。
4. **回归**：agent 定向测试（`StatementWeaveEndToEndTest`、`NestGateTest`、`StatementRegistry*`）+ 主仓定向测试；
   新增用例须在改前读数为红、改后为绿（反向验证）。

## 六、测试落地

| 用例 | 目的 |
|---|---|
| `StatementRegistryDelegatesTest`（新） | 合成包装器（持有 Statement 字段并转发）→ `registerWithDelegates` 后包装器与内层都有登记；无字段类只登记自身；字段扫描失败降级不抛 |
| `NestGateTest` 新增两例 | 内层盲区不得抑制外层（外层有登记必须产出）；内层真产出仍只报最内层 |
| `StatementWeaveEndToEndTest` 新增一例 | 包装器形态端到端：prepare 面登记包装器 → 包装器 `executeQuery()` 转发内层 → 恰好 1 条事件、SQL 与参数正确（**改前必红**，即本轮真机缺陷的最小复现夹具） |

## 七、风险与取舍

- 反射扫描新增一次每类成本（仅首次 prepare 时该类一次，之后 ClassValue 命中），不进每条 SQL 的热路径。
- 内层对象同样进弱键登记表：条目数翻倍，单桶上限 512 足以覆盖（同一 PS 生命周期内共存）。
- 只解一层代理（不递归）：与现场形态一致；若将来出现双层包装，`markEmitted` 修复后外层仍可兜底产出。
- 不登记包装器类到 `WeaveSpecs`：包装器是驱动私有实现，登记会带来双钩子与版本漂移风险；证据见 §二 对象形态表。

## 八、待验证前提（实施前已逐条实测）

| 前提 | 结论 |
|---|---|
| Oracle 返回包装器而非物理语句 | ✅ 真机 `ShapeProbe` 实测（三种入口各返回对应包装器） |
| 织入本身对 Oracle 三类类有效 | ✅ 离线真实字节织入 changed=true + 真机 premain weavable=5/woven=4/failed=0 |
| 内层物理语句确实被织入 | ✅ 盲区日志的 `psClass=oracle.jdbc.driver.T4CPreparedStatement` 即执行钩子所在类 |
| 包装器字段名跨版本稳定 | 待补：ojdbc8 12.2/19.3/21.9 与 ojdbc11 23.3 的字段名逐版本核对（实施时一并取证，扫描按类型不按名，命名漂移不致命） |
| 参数丢失与登记错位同因 | 待补：改后真机读数验证 PS 事件 `params` 非空（改前 PS 无事件，无法直接对照） |

## 九、实施记录（2026-11-04 当日完成）

### 9.1 代码改动

| 文件 | 改动 |
|---|---|
| `hook/StatementRegistry.java` | 新增 `registerWithDelegates` + `DELEGATE_FIELDS`（`ClassValue` 缓存候选字段；按「可赋给 `java.sql.Statement`」的**类型**扫描、取第一个非空值，不依赖字段名）；解一层包装，把内层物理语句一并登记，参数槽/批串随之接回内层 |
| `hook/SqlHookCallbacks.java` | `onStatementPrepare` 改调 `registerWithDelegates`；`onExecuteExit` 改 `NestGate.exit(token)`，且只在 `WovenEvents.emit` 返回 true 时 `markEmitted()` |
| `hook/NestGate.java` | `exit()` 语义改为「本帧可以尝试产出」；新增 `markEmitted()`；新增 `lastExitToken` 同帧重复出口去重（判定必须先于深度复位） |
| `hook/WovenEvents.java` | `emit` 返回 boolean（本帧是否真的产出事件） |
| 测试 | 新增 `StatementRegistryDelegatesTest` 4 例；`NestGateTest` +1（内层盲区不得抑制外层）；`StatementWeaveEndToEndTest` +2（包装器形态端到端、双 ATHROW 只报一条）；`FakeVendorFixtures` +2 夹具（`VendorPsWrapper` **刻意不织入**、`VendorStmtDoubleExit`） |

### 9.2 真机读数（Oracle Free 23 @ 103.36.220.245:21521/FREEPDB1，本轮新建独立容器，未触碰共享库）

| 场景 | 改前 | 改后 |
|---|---|---|
| premain + ojdbc11 23.3：6 条语句（1 plain + 5 PS/批处理） | **3 条**（仅 plain，PS 全丢） | **7 条全出**（含批处理的第 2 条），PS 事件带 params |
| premain + ojdbc8 19.3.0.0 | — | 7 条全出，params 在位 |
| premain + ojdbc8 12.2.0.1 | — | 7 条全出，params 在位 |
| HikariCP 4.0.3 + `connectionTestQuery=select 1 from dual` | — | 5 条全出（校验 / 建表 / 插入 / 查询），insert、select 带 params |
| attach（应用先建连接与 PS，再 `loadAgent` 挂载） | — | 挂载后新建 PS 正常产出（11 轮各 1 条）；挂载前的存量 PS 仍按一期已知限制记盲区 |
| 单条失败 DDL（`drop table no_such_table_xyz`） | **3 条重复** | **1 条** |

### 9.3 门禁与反向验证

- `:sql-visualizer-agent:test`：**247 例 / 0 失败 / 0 错误 / 1 skip**（46 个结果类，`BUILD SUCCESSFUL in 1m 27s`）
- 主仓定向 `cn.song.mybatisplus.visualizer.log.agent.*`（含 `StatementWeaveWiringGuardTest`）：**217 例 / 0 失败 / 0 错误 / 0 skip**（28 类，`BUILD SUCCESSFUL in 3m 59s`）
- 反向验证（三处，逐一摘除→必红→还原）：
  1. `onStatementPrepare` 退回 `register` ⇒ `包装器形态_prepare登记解一层_执行在内层时仍取到SQL与参数` **红**（13 例 1 红）
  2. NestGate 退回「进入即置位（产出）」⇒ `内层盲区_不得抑制外层` **红**（17 例 1 红）
  3. 去掉 `lastExitToken` 去重 ⇒ `异常路径双ATHROW出口_只报一条` **红**（真机形态即 3 条重复）

### 9.4 已知边界（本轮不改）

- 挂载前已 prepare 的 PS 仍是盲区（一期设计）：`unresolvedCount` 与「系统性盲区」告警照旧，本轮 attach 复验里如实触发
- 解包只做一层；双层代理未见实例，NestGate 修复后外层仍可兜底
- 批处理事件的 params 取最后一次绑定值（`ParamCapture` 既有语义），不在本轮范围
- 未把 Oracle 包装器类登记进 `WeaveSpecs`：驱动私有实现、且登记内层已覆盖，登记会带来双钩子与版本漂移风险
