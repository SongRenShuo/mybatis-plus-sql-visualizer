# T-CaptureCoverage 增强捕获剩余三方言补齐（MariaDB / SQLite / OceanBase） — 开发计划

> 来源：T-XcDialects3 收口后对「增强捕获是否覆盖全部方言」的对账（2026-10-30）。
> 基线：`dev` @ `44c50cd2`。
> 占号：**O-154 / D200 / V225**。
> 状态：**实施中**（D1~D5 见 §五，已按推荐项直接落地）。

---

## 一、背景与缺口

插件 14 个方言的增强捕获（字节码 agent）实际覆盖是 **11 全链路 / 2 只有角标 / 1 三面全空**：

| 档位 | 方言 | 现状 |
|---|---|---|
| 全链路（connect + 语句 + prepare） | MySQL、PostgreSQL、Oracle、SQL Server、H2、DM、openGauss、GaussDB（含华为两族）、金仓、海量、瀚高 | 已有真实 jar 字节织入 + JVM 链接期校验；金仓/海量/瀚高另有 **真实 JVM premain/attach 冒烟**（T-XcDialects3 补，`weavable=4 woven=4 failed=0` 逐族、三族同时 `matched=12 wove=12`） |
| **只有角标**（connect 面在册、语句面缺） | **MariaDB**、**SQLite** | `DriverRegistry` 有 `org.mariadb.jdbc.Driver` / `org.sqlite.JDBC`；`WeaveSpecs` 语句面/prepare 面**零条目**，且在 `VENDOR_NOT_COVERED` 里显式认领「未纳入测试矩阵（无本机 jar）」——attach 后只出 URL、不出 SQL。**该认领理由已失真**：两个 jar 都在本机 gradle 缓存里（主项目 `testImplementation` 就有） |
| **三面全空** | **OceanBase** | 全仓 grep `oceanbase` 在 agent 模块零命中——连 connect 面都没登记，attach 后完全静默（方言可选、控制台解析的角标有，但增强捕获一条不出） |

这三族合起来是「用户能选这个方言、能看到日志角标，但拿不到源码位置与耗时」的静默缺口，与 T-DialectXcFamily 之前的金仓、T-GaussDbDialect 之前的华为两族是同一形态。

## 二、事实底账（字节码反编译级，本轮已取证）

| 族 | 驱动类（connect 面） | 语句面 | prepare 面（连接） | 制品坐标 / 本机路径 |
|---|---|---|---|---|
| MariaDB | `org.mariadb.jdbc.Driver`（**已在册**） | `org.mariadb.jdbc.Statement`（`implements java.sql.Statement`，自有 `execute/executeQuery/executeUpdate/addBatch/clearBatch/executeBatch` 全套）、`ClientPreparedStatement`（自有 `execute()`/`executeQuery()`/`executeLargeUpdate()`/`addBatch()`/`executeBatch()`/`executeLargeBatch()`）、`ServerPreparedStatement` | `org.mariadb.jdbc.Connection` | `org.mariadb.jdbc:mariadb-java-client:3.3.3`；缓存 `~/.gradle/caches/modules-2/files-2.1/org.mariadb.jdbc/mariadb-java-client/3.3.3/` |
| SQLite | `org.sqlite.JDBC`（**已在册**） | `org.sqlite.jdbc3.JDBC3Statement`（自有 `execute/executeQuery/executeUpdate/executeLargeUpdate` 全族）、`org.sqlite.jdbc3.JDBC3PreparedStatement` | `org.sqlite.jdbc3.JDBC3Connection` | `org.xerial:sqlite-jdbc:3.50.3.0`；缓存 `.../org.xerial/sqlite-jdbc/3.50.3.0/` |
| OceanBase | `com.oceanbase.jdbc.Driver`（**未在册**） | `com.oceanbase.jdbc.OceanBaseStatement`（`implements java.sql.Statement`）、`com.oceanbase.jdbc.JDBC4PreparedStatement`、`com.oceanbase.jdbc.JDBC4ServerPreparedStatement`（两者 `extends BasePrepareStatement extends OceanBaseStatement`，**有自有 `execute` 族**故需单列） | `com.oceanbase.jdbc.OceanBaseConnection`（`implements ConnectionImpl`，自有 6 个 `prepareStatement` 出口） | `com.oceanbase:oceanbase-client:2.4.19`（Maven Central，1.18 MB）；URL 前缀 `jdbc:oceanbase:`（`UrlParser` 常量池实证） |

三族与既有 PG 路径的差别：MariaDB/SQLite/OceanBase 都有**自有连接类与自有语句类**（不是 PG 改名分叉，也不复用 MySQL 的 `StatementImpl`）——所以不能靠既有 MySQL 名单覆盖，必须逐类登记。

## 三、待验证前提（实施前先证伪）

| 编号 | 前提 | 证伪方式 | 若不成立的影响 |
|---|---|---|---|
| **P-1** | MariaDB `Statement` / `ClientPreparedStatement` / `ServerPreparedStatement` 三类织入后过 JVM 链接期校验 | `StatementWeaverVendorCoverageTest` 的真实 jar 字节织入 + 隔离 loader 定义（jar 已在缓存） | 过不了则该类不登记（达梦 `DmDriver` 先例：VerifyError 类整类撤出），回退 `VENDOR_NOT_COVERED` 并写明原因 |
| **P-2** | SQLite `JDBC3Statement` / `JDBC3PreparedStatement` 同上 | 同上 | 同上 |
| **P-3** | OceanBase `OceanBaseStatement` / `JDBC4PreparedStatement` / `JDBC4ServerPreparedStatement` / `OceanBaseConnection` 同上 | 同上（客户端 jar 需先落缓存/`testImplementation`） | 同上；OB 类体量比前两族大（OceanBaseStatement 37KB），chop 帧风险需实测 |
| **P-4** | 三族 connect 面登记后不触发宿主强转 CCE | 服务器真实 JVM 冒烟（T-XcDialects3 的 `/opt/xc3-attach-smoke/` 已备好 JDK17 + 三族先例脚本）：宿主 `(org.mariadb.jdbc.Connection) conn` / `(org.sqlite.jdbc3.JDBC3Connection) conn` / `(com.oceanbase.jdbc.OceanBaseConnection) conn` 三形态（不带 agent / premain / attach 后） | 若击穿 → **红线**，该族 connect 面撤出（达梦先例）；语句面/prepare 面可保留（不改对象身份） |
| **P-5** | `VENDOR_NOT_COVERED` 里 MariaDB/SQLite 的认领理由「无本机 jar」已失真，删认领后守卫仍绿 | `StatementWeaverVendorCoverageTest.厂商对齐守卫` 的判定是「connect 已登记厂商 **要么**语句面有覆盖 **要么**显式认领」——补了覆盖就不需要认领；`org.hsqldb.`（非插件方言）保留认领 | 若删认领后红，说明覆盖判定没生效（`vendorKey` 前缀不匹配），需先修键 |
| **P-6** | OceanBase 的 `vendorKey` 前缀判定正确 | `com.oceanbase.` 走两段规则 → 键 `com.oceanbase.`，与既有 `com.kingbase8.`/`com.highgo.` 无包含关系；`cn.com.vastbase.` 走三段白名单 `cn.com.` | 若键错，OB 的覆盖判定会失效（假绿） |

## 四、实施步骤（券）

### 券 01 MariaDB 语句面 + prepare 面（S）

`sub-projects/sql-visualizer-agent/src/main/java/.../WeaveSpecs.java`：
- 语句面加 `org.mariadb.jdbc.Statement`、`org.mariadb.jdbc.ClientPreparedStatement`、`org.mariadb.jdbc.ServerPreparedStatement`
- prepare 面加 `org.mariadb.jdbc.Connection`
- 注释写清「MariaDB 3.x 全部重写为自有类（不复用 MySQL `StatementImpl`），故必须单列」

### 券 02 SQLite 语句面 + prepare 面（S）

同文件：语句面 `org.sqlite.jdbc3.JDBC3Statement`、`org.sqlite.jdbc3.JDBC3PreparedStatement`；prepare 面 `org.sqlite.jdbc3.JDBC3Connection`。
注释记「SQLite 无 Callable 自有 execute 族（`JDBC3PreparedStatement` 覆盖），不单列」。

### 券 03 OceanBase 三面登记（S~M）

1. `DriverRegistry.java`：加 `com.oceanbase.jdbc.Driver`（connect 面，注释记「`UrlParser` 常量池唯一前缀 `jdbc:oceanbase:`，独立实现非 MySQL 分叉」）
2. `WeaveSpecs.java`：语句面 `com.oceanbase.jdbc.OceanBaseStatement`、`JDBC4PreparedStatement`、`JDBC4ServerPreparedStatement`；prepare 面 `com.oceanbase.jdbc.OceanBaseConnection`
3. `sub-projects/sql-visualizer-agent/build.gradle.kts` 加 `testImplementation("com.oceanbase:oceanbase-client:2.4.19")`（真实 jar 字节层校验用，与三族先例同口径）

### 券 04 帧跳过前缀同步（S）

`log/CallerSourceLocator.kt`、`log/SourceInterceptorTemplates.kt`、`ui/StackDigestFrames.kt`、agent `util/StackLocator.java`、**`docs/instrumentation/SqlSourceInterceptor.kt`（第 5 份，文档示例，`LocatorSkipPrefixSyncTest` 实锁）**：
- 五份一起加 `com.oceanbase.`（OceanBase 是新族）
- MariaDB `org.mariadb.` 与 SQLite `org.sqlite.` **先查是否已在**（T-XcDialects3 那轮只加了 `cn.com.vastbase.` 与 `com.highgo.`），缺则补

### 券 05 守卫与测试同步（M）

| 项 | 内容 |
|---|---|
| `StatementWeaverVendorCoverageTest` | `VENDOR_NOT_COVERED` **删 `org.mariadb.` 与 `org.sqlite.` 两条认领**（理由已失真，P-5）；`SPECS` 镜像数组补三族新类；字节层三族各跑一次 |
| 反向验证 | 删任一族语句面条目 → 厂商对齐守卫必红；还原 → 绿。两次读数入计划 §七 |
| 真实 JVM 冒烟 | 复用服务器 `/opt/xc3-attach-smoke/`：三族各跑 `weavable/woven` 计数 + 宿主退出码 + 强转红线（P-4） |
| 新增守卫（可选） | 「14 方言 × 三面覆盖」对账用例：断言 `DialectRegistry.dialects` 每个方言都能在 `DriverRegistry` + `WeaveSpecs` 找到对应驱动族，或显式在 `VENDOR_NOT_COVERED` 认领——把「看着支持实际不出」的形态永久锁死 |

### 券 06 文档同步（S）

- `README.md` / `plugin.xml` 的增强捕获表述（若有「支持 X 种数据库」计数）
- `docs/架构设计/01-项目架构总览.md` 的 agent 段（三面名单）
- `docs/变更记录.md` 顶部补条目

## 五、决策点

| # | 决策 | 选项 | 定案 |
|---|---|---|---|
| **D1** | MariaDB/SQLite 的认领怎么处理 | A 删认领 + 补覆盖 / B 保留认领只补覆盖 | **A**（认领与覆盖同时存在时守卫优先看认领，等于覆盖白做） |
| **D2** | OceanBase connect 面登不登 | A 登（与三族同口径）/ B 只登语句面（达梦式保守） | **A**，但须先过 P-4 强转红线；击穿则退 B |
| **D3** | OceanBase `CallableStatement` 类要不要单列 | A 不单列（`JDBC4ServerPreparedStatement` 继承 `BasePrepareStatement extends OceanBaseStatement`，`execute` 族在父类）/ B 单列 | **A**（照既有惯例「父类覆盖即不单列」），字节层验证父类织入确实覆盖 |
| **D4** | 是否加「14 方言三面对账」守卫 | A 加（防未来再出现静默缺口）/ B 不加 | **A**（这次就是靠人工 grep 才发现的，应当机器化） |
| **D5** | 要不要顺带补 openGauss 7.0.0-RC 镜像回归 | A 本轮不做（T-XcDialects3 已覆盖 5.0.0）/ B 顺带部署 | **A**，登记观察 |

## 六、风险

- **字节层过不了**（P-1~P-3）：按达梦先例整类撤出 + 回退认领，其余族照常；OB 类体量大、风险最高。
- **强转红线击穿**（P-4）：connect 面撤出即可（语句面不改对象身份，结构上不会 CCE）。
- **`vendorKey` 假绿**（P-6）：`com.oceanbase.` 与 `com.kingbase8.`/`com.highgo.` 无包含关系，但登记后须跑一次负例断言确认没混键。
- **服务器依赖**：真实 JVM 冒烟需要服务器可达（`不大-北京-16G`，JDK17 已装，冒烟脚本在 `/opt/xc3-attach-smoke/`）；不可达时如实登记「环境受限未验证」，不得用字节层冒充 attach 读数。
- **测试依赖变更**（券 03 的 `testImplementation`）触发大改动判据 → 收口必须跑 `verifyGate`。

## 七、实施记录

### 7.1 A 路（MariaDB / SQLite）

**改了什么**

1. `WeaveSpecs`：语句面加 5 类——`org.mariadb.jdbc.Statement` / `ClientPreparedStatement` /
   `ServerPreparedStatement`（MariaDB 3.x 全部重写为自有类，不复用 MySQL `StatementImpl`，故必须单列）、
   `org.sqlite.jdbc3.JDBC3Statement` / `JDBC3PreparedStatement`（SQLite 无 Callable 自有 execute 族，
   `JDBC3PreparedStatement` 覆盖，不单列）；prepare 面加 2 类——`org.mariadb.jdbc.Connection`、
   `org.sqlite.jdbc3.JDBC3Connection`。两族各用**独立 `putSpec(...)` 调用**，未改动既有行顺序
   （B 路 OB 条目同在 `WeaveSpecs`，插入点不重叠）。
2. `StatementWeaverVendorCoverageTest`：`VENDOR_NOT_COVERED` 删 `org.mariadb.` / `org.sqlite.`
   两条认领（理由「无本机 jar」已失真；认领优先于覆盖判定，留着等于覆盖白做），保留
   `org.hsqldb.` / `com.gbasedbt.`；`STATEMENT_FACE_CLASSES` 补 5 类、`PREPARE_FACE_CLASSES` 补 2 类；
   新增 `JAR_OPTIONAL_BYTE_LAYER` + 专项用例（见下「测试依赖」）。
3. 新增 `DialectCaptureCoverageTest`：14 方言三面对账守卫（写法见下）。

**字节层读数（真实驱动字节 → 织入 → 子优先 loader 定义 + 初始化触发链接期校验）**

| 类 | 面 | changed | 链接期校验 |
|---|---|---|---|
| `org.mariadb.jdbc.Statement` | 语句面 | true | LINK_OK |
| `org.mariadb.jdbc.ClientPreparedStatement` | 语句面 | true | LINK_OK |
| `org.mariadb.jdbc.ServerPreparedStatement` | 语句面 | true | LINK_OK |
| `org.sqlite.jdbc3.JDBC3Statement` | 语句面 | true | LINK_OK |
| `org.sqlite.jdbc3.JDBC3PreparedStatement` | 语句面 | true | LINK_OK |
| `org.mariadb.jdbc.Connection` | prepare 面 | true | LINK_OK |
| `org.sqlite.jdbc3.JDBC3Connection` | prepare 面 | true | LINK_OK |

七类全部通过，P-1 / P-2 成立。SQLite 两个类是 `abstract`（`JDBC3Statement extends CoreStatement`、
`JDBC3Connection extends SQLiteConnection`），定义 + 初始化照常，无需实例化。

**测试依赖（重要未尽项）**：两个 jar **不在 agent 测试 classpath**（只在根项目
`testImplementation` 与本机 gradle 缓存里）。本轮字节层读数用**临时 init 脚本**
（`tmp/capture-coverage-deps.init.gradle.kts`，不入库）把 mariadb-java-client 3.3.3、
sqlite-jdbc 3.50.3.0、slf4j-api 2.0.13 挂到 `:sql-visualizer-agent:testImplementation` 取得，
**未改 `build.gradle.kts`**（属 B 路写集）。因此：

- 带 init 脚本：`MariaDB与SQLite字节层_驱动jar在位时必须真实织入且过校验` 真跑，七类硬断言；
- 不带（= 其他人/CI 的默认 classpath）：该用例 `Assumption` 显式 skip 并在消息里列出 7 个类
  （不静默放过），其余用例全绿。

**待收口补**：`sub-projects/sql-visualizer-agent/build.gradle.kts` 加
`testImplementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")` 与
`testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")`（版本与根 catalog 一致）。补上后该用例
自动升级为硬断言，`JAR_OPTIONAL_BYTE_LAYER` 与 `excludedFromByteLayer` 里的可选分支可一并删除。

**定向测试读数**

| 命令 | 读数 |
|---|---|
| `:sql-visualizer-agent:test -I tmp/capture-coverage-deps.init.gradle.kts`（agent 模块全量，36 类） | 198 项 0 失败 1 skip；skip 是既有 `AttachPoolWarmGateTest.模式DM_达梦存量连接复用_attach事件到达`（需真实 DM），与本改动无关。`StatementWeaverVendorCoverageTest` 14/14、`DialectCaptureCoverageTest` 3/3 |
| `:sql-visualizer-agent:test --tests "*StatementWeaverVendorCoverageTest"`（不带 init） | 14 项 0 失败 1 skip（skip = 上述字节层专项，消息列出 7 类） |
| 逐类字节层探针（`tmp/probe/ByteProbe.java`，JDK17，同形态 loader） | 7 类逐条 PASS（见上表） |

**反向验证两读数**

| 步骤 | 读数 |
|---|---|
| 删 MariaDB 语句面三条（保留 `org.mariadb.jdbc.Connection`） | **`厂商对齐守卫` 仍绿**——该守卫是厂商前缀级判定，同族一条 prepare 面条目即可把 `org.mariadb.` 整族判为已覆盖；红的是类级四条：`语句面登记_命中语句面谓词且不在prepare面`、`语句面真实驱动类_织入changed且通过JVM校验`、`登记名单单一真相_与SPECS一致`、`MariaDB与SQLite两族_语句面与prepare面均已覆盖`（17 项 5 失败）。新增的 `DialectCaptureCoverageTest` 也红：`MariaDB：驱动族 [org.mariadb.] 在 SPECS **语句面**零条目`（3 项 2 失败） |
| 再删 prepare 面一条（整族四条全撤） | **`厂商对齐守卫` 红**：`org.mariadb.jdbc.Driver → 厂商前缀 org.mariadb.`（1 项 1 失败） |
| 还原 | 全绿（14/14、3/3） |

**订正计划 §券05 的预期**：「删任一族语句面条目 → 厂商对齐守卫必红」不成立（与 B 路 §7.2 的
补充实测同结论）。语句面漏登记由类级用例与新增对账守卫拦，厂商对齐守卫只拦「整族静默」。

**新增对账守卫的写法选择**：`DialectCaptureCoverageTest` 用「14 方言 → 驱动族前缀」硬编码表
（agent 子项目拿不到根项目 Kotlin 的 `DialectRegistry`），对每个方言**分别**要求语句面与
prepare 面各有条目（面按 `StatementWeaver.Kind` 判定，不按类名猜），或在 `VENDOR_NOT_COVERED`
显式认领。任务书给的两条路（不硬失败 / 只断言两族）取的是**第三条**：B 路在本轮内已落地
OceanBase 三面，缺口消失，故不留 `KNOWN_PENDING` 宽松分支，直接对 14 方言全硬断言——比原计划
更严，且不需要收口时再改测试。判别力由负例钉住：整族缺失、**半覆盖（只有 prepare 面）**、
显式认领三种形态各一条断言。

**未尽项**

1. 上述 agent `testImplementation` 两行（见「测试依赖」）——本轮按任务书未动 `build.gradle.kts`。
2. 真实 JVM premain/attach 冒烟（P-4 强转红线：宿主 `(org.mariadb.jdbc.Connection) conn` /
   `(org.sqlite.jdbc3.JDBC3Connection) conn` 三形态）未跑，环境受限未验证；字节层只证「织得进、
   校验过」。
3. `JAR_OPTIONAL_BYTE_LAYER` 是过渡机制，依赖补齐后应删除（连带 `resourceOptional` /
   `resourcePresent` 的可选分支），否则「缺 jar 就 skip」会长期留在守卫里。

### 7.2 B 路（OceanBase）

提交 `92194d1d`（代码）。B 路写集：`DriverRegistry.java` / `WeaveSpecs.java` 的 OB 条目、
agent `build.gradle.kts`、帧跳过前缀五份、`StatementWeaverVendorCoverageTest` 的两个镜像数组；
**未触碰** MariaDB/SQLite 的织入行与 `VENDOR_NOT_COVERED`。

**改了什么**

1. `DriverRegistry`：connect 面 `com.oceanbase.jdbc.Driver`（国产库区段，瀚高之后）。注释记
   「独立实现非 MySQL 分叉（URL 前缀 `jdbc:oceanbase:` 唯一）、与 MySQL 协议兼容但类体系自成一套」。
2. `WeaveSpecs`：语句面 `OceanBaseStatement` / `JDBC4PreparedStatement` /
   `JDBC4ServerPreparedStatement`；prepare 面 `OceanBaseConnection`。注释写清
   `extends BasePrepareStatement extends OceanBaseStatement` 与「两个 PS 类各有自有 execute 族
   故必须单列」；`CallableStatement` 按 D3 不单列。
3. agent `build.gradle.kts`：`testImplementation("com.oceanbase:oceanbase-client:2.4.19")`
   （只进测试 classpath，生产依赖图不变）。
4. 帧跳过前缀五份同步加 `com.oceanbase.`：`log/CallerSourceLocator.kt`、
   `log/SourceInterceptorTemplates.kt`、`ui/StackDigestFrames.kt`、agent `util/StackLocator.java`、
   `docs/instrumentation/SqlSourceInterceptor.kt`。五份均同步后 `LocatorSkipPrefixSyncTest` 绿
   （`org.mariadb.` / `org.sqlite.` 五份原本就在位，B 路未动）。
5. `StatementWeaverVendorCoverageTest`：`STATEMENT_FACE_CLASSES` 补 OB 三类、
   `PREPARE_FACE_CLASSES` 补 `OceanBaseConnection`。

**字节层读数（P-3 证伪成功）**：真实 jar 字节织入 → 隔离 loader 定义 → 初始化触发链接期校验，
四类全部通过。`OceanBaseStatement` 37KB 未触发 chop_frame（DmDriver 那类 VerifyError 未出现）。

| 类 | 原始字节 | 织入后 | changed | 链接期校验 |
|---|---|---|---|---|
| `com.oceanbase.jdbc.OceanBaseStatement` | 37648 B | 39195 B | true | LINK_OK |
| `com.oceanbase.jdbc.JDBC4PreparedStatement` | 19355 B | 20335 B | true | LINK_OK |
| `com.oceanbase.jdbc.JDBC4ServerPreparedStatement` | 40201 B | 41226 B | true | LINK_OK |
| `com.oceanbase.jdbc.OceanBaseConnection` | 61342 B | 61583 B | true | LINK_OK |

四个类的 spec 均为 `[EXEC_PARAM, EXEC_CTX, BATCH, ADD_BATCH, CLEAR_BATCH, PARAM]`（连接为
`[PREPARE]`），与登记面一致。

**反向验证两读数**

| 步骤 | 读数 |
|---|---|
| 四个 OB 条目改挂 `com.revprobe.*`（等价于整族撤出） | **红**：`厂商对齐守卫` 失败，明细 `com.oceanbase.jdbc.Driver → 厂商前缀 com.oceanbase.`；同类 7 failed |
| 还原四个真实类名 | **绿**：`厂商对齐守卫_连接面已登记厂商必须有语句或prepare面条目` PASS |

补充实测：**只删语句面三条、保留 prepare 面时厂商对齐守卫仍绿**——该守卫是厂商**前缀级**判定
（`specClass.startsWith(vendor)`，不区分语句面/prepare 面），`OceanBaseConnection` 一条即可把
整族判为已覆盖。因此「删三条必红」的原预期不成立，反向验证须四条目同撤；这也说明该守卫挡得住
「整族静默」，挡不住「只补了 prepare 面、语句面漏了」的半覆盖形态（A 路新增的
`DialectCaptureCoverageTest` 同样是前缀级，形态相同）。已登记为观察项，未在本轮改守卫语义。

**定向测试读数**

- `:sql-visualizer-agent:test --tests "*StatementWeaverVendorCoverageTest*"`：14/14 PASS
  （含四类 OB 的字节层用例，无 skip）。
- `:sql-visualizer-agent:test --tests "*DialectCaptureCoverageTest*"`：PASS（OB 已由前缀覆盖，
  `KNOWN_PENDING` 的 OceanBase 行不再被判定分支命中，但条目仍留在 A 路测试里，建议 A 路删除）。
- 主项目 `test --tests "*LocatorSkipPrefixSyncTest" --tests "*StackDigestFramesTest"`：
  `LocatorSkipPrefixSyncTest` 6/6 PASS、`StackDigestFramesTest` 22/22 PASS。
- agent 模块全量（`./gradlew :sql-visualizer-agent:test`）：198 项 2 失败 2 跳过。两项失败均与
  本改动无关、属环境依赖：`AttachPoolWarmGateTest.模式A2_Hikari真实池_存量物理连接复用`
  （需真实 MySQL，TLS 握手失败）、`SenderLoopBackoffTest.连上即被关_退避增长且达上限后放弃该端口`
  （245ms vs 312ms 时序抖动）。

**未尽项**

1. **真实 JVM premain/attach 冒烟未跑**（P-4 强转红线、attach 后 `matched/woven` 计数）：需服务器
   `/opt/xc3-attach-smoke/` 可达，本轮环境未验证。字节层只能证明「织得进、校验过」，证不了
   「attach 后宿主不被 CCE 击穿」。建议收口阶段按 D2 口径补跑，击穿则 connect 面撤出（退 B 案）。
2. **厂商对齐守卫的粒度**：前缀级判定对「半覆盖」不可见（见上反向验证补充），若要机器化需改成
   面内判定；本轮按任务书未动。
3. **`DialectCaptureCoverageTest.KNOWN_PENDING` 的 OceanBase 行**属 A 路写集，B 路未动，
   建议 A 路删除（当前不影响绿，但留着是陈旧披露）。
4. **测试依赖变更**（`oceanbase-client` 进测试 classpath）触发大改动判据，收口须跑 `verifyGate`。

### 7.3 主控收口（2026-10-30）

**① 测试依赖固化（提交 `9409993a`）**：A 路的七类字节层读数原靠临时 init 脚本
（`tmp/capture-coverage-deps.init.gradle.kts`，不入库）取得，默认 classpath 下用例以
`Assumption` 显式 skip。把 `org.mariadb.jdbc:mariadb-java-client:3.3.3` 与
`org.xerial:sqlite-jdbc:3.50.3.0` 固化进 `sub-projects/sql-visualizer-agent/build.gradle.kts`
的 `testImplementation` 后，字节层升级为**硬断言**：`StatementWeaverVendorCoverageTest` **14/14**、
`DialectCaptureCoverageTest` **3/3**，**skip=0**。生产依赖图不变。

**② 真实 JVM premain / attach 冒烟**（服务器 `/opt/xc3-attach-smoke/`，JDK 17.0.20.1）——
新 agent jar `mps-sql-agent-1028.jar`（350,327 B，md5 `1829894a…3292`）：

| 族 | premain 织入（单驱动 classpath 真隔离） | 逐族隔离 attach | 三族同进程 attach |
|---|---|---|---|
| MariaDB | `weavable=4 woven=4 failed=0` | `matched=4` | — |
| SQLite | `weavable=4 woven=4 failed=0` | `matched=4` | — |
| OceanBase | `weavable=5 woven=5 failed=0` | `matched=4` | — |
| 三族同进程 | `weavable=13 woven=13 noChange=0 failed=0` | — | **`matched=12 wove=12 unmodifiable=0 failed=0`** |

`Lister2` 枚举确认精确 12 类且全部 `modifiable=true`（每族 Driver + Connection(prepare) + Statement
+ PreparedStatement）。OceanBase 多出的 1 个是 `JDBC4ServerPreparedStatement`（仅 agent 存在时才被加载）。

**③ 红线（用户令：不能让宿主受影响）**：三族 × 三形态（不带 agent / premain / attach 后）
`Connection` 强转**全部通过**，强转后 `SELECT 1` 均返回 1，`impl` 仍是驱动原生类（**未被替换成代理**）。
宿主退出码 premain / attach / 对照 / 逐族隔离**全为 0**；无 `VerifyError` / `ClassCastException` /
`NoClassDefFoundError`。**对照轮**：不带 agent 与 premain 的输出剔除计数行后 `diff` 完全一致
（SQL 结果、batch 2 行、实现类、退出码）。

**④ 实现事实（对「白名单只登父类」的意义）**：SQLite 运行期实例是 `jdbc4.*`，但只覆写 JDBC4 专有方法，
宿主实际调用的 `createStatement()/prepareStatement(String)/set*/addBatch/executeBatch/executeQuery`
全部继承自**被织入的 `jdbc3.*` 父类**；OceanBase 的 `ClientSidePreparedStatement` 继承
`JDBC4PreparedStatement` 且无覆写，同理生效。→ 白名单只登父类即可覆盖子类，与既有「父类覆盖即不单列」
惯例一致，现在有了真实 JVM 证据。

**⑤ 未闭环**：
1. **MariaDB 服务端未实测**：`114.66.55.245:20081` 从服务器侧连续 3 次 `Connection refused`
   （同宿主 `20097` 同时刻 OPEN），本轮改用 **MySQL 8.4.6（20084）** 承载 MariaDB Connector/J 3.3.3。
   **驱动侧织入结论成立，但「对 MariaDB 10.11 服务端已验证」这句不能写**。
2. `JDBC4ServerPreparedStatement` 仅在 agent 存在时才被加载的成因未定位。
3. IDE 事件回流未验证（服务器无显示环境，属预期；「attach 端到端」口径不变）。
4. 三族的**渲染套件**真执行（`DialectDockerVerificationTest` 外部实例模式）未跑——OceanBase 的
   `25881` 实例已在册、SQLite 嵌入式天然可用、MariaDB 待服务端可用后补，可作后续增量。

### 7.4 收口门禁读数（2026-10-30）

`verifyGate testShards`（单路，dev `9bf37605` 基线 + 本轮 4 笔）：

- **`verifyGate`：3/3 Compatible**（IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63，报告 10:56 生成）
  ——本轮动了 agent `testImplementation`（MariaDB/SQLite 驱动），触发大改动判据，已覆盖。
- **`testShards`：8492 tests / 0 failures / 0 errors / 9 skipped**，`BUILD SUCCESSFUL in 13m 15s`；
  parity 口径「计划类全集 790 个均有结果 XML」（含新增 `DialectCaptureCoverageTest`）。
- 与 T-XcDialects3 收口那轮（8492/0/0/9）同数——本轮新增用例与既有跳过项抵消，无回归。
