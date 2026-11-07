# T-GoldenDbDialect：中兴 GoldenDB 方言对接调研与实施计划（2026-11-07）

- 立项：2026-11-07 09:01
- 状态：**调研完成，待拍板**（本册只做调研与立项，未动 `src/main`）
- 目标分支：`master`
- 输入资产（用户 2026-11-07 提供）：
  - `gdb_mysql-connector-java-oracle-5.1.46.99(1).jar`（1,129,905 B）
  - `GoldenDB开发支持.pdf`（3,329,496 B）
- 入库位置：`database-lab/vendor/drivers/gdb_mysql-connector-java-oracle-5.1.46.99.jar`、`database-lab/vendor/docs/GoldenDB开发支持.pdf`（登记见 `database-lab/vendor/README.md`）

## 零、证据分级口径

本册所有结论按三档标注，**不混写**：

| 档 | 含义 | 本册占比 |
|---|---|---|
| **实测** | 字节层/字节码/PDF 文本抽取的一手读数，命令与输出可复现 | 驱动事实全部、PDF 引文全部 |
| **文档推断** | 从 PDF 原文或字节码常量推出的**语义**判断，未被引擎验证 | 兼容模式归属、upsert 语义、保留字模式归属 |
| **未验证** | 无真机、无镜像、无官方说明，只登记问题不给答案 | 全部真机行为、`oracleMode` 语义 |

**没有真机就不宣称方言可用**——见 §六。

---

## 一、产品与驱动事实

### 1.1 驱动字节层（全部实测）

取证方式：`python -I` + `zipfile` 枚举条目、`javap -p -constants` 读常量池、`javap -p -c` 读构造器字节码。脚本与产物在临时目录，未入仓。

#### 1.1.1 文件指纹

| 项 | 值 |
|---|---|
| 字节数 | 1,129,905 B |
| sha256 | `ccf2f4cbce0dc4978a38683fe059b8dfb736347111301d5a0d51a67d236d7a2f` |
| md5 | `91846fe5d345974d8c80d863559b91c2` |
| zip 条目 | 437（其中 `.class` 392） |
| 原始文件名 | `gdb_mysql-connector-java-oracle-5.1.46.99(1).jar`（`(1)` 为下载重名后缀，入库已去掉） |

#### 1.1.2 `META-INF/MANIFEST.MF`（逐字摘录）

```
Manifest-Version: 1.0
Ant-Version: Apache Ant 1.9.7
Created-By: 1.8.0_281-b09 (Oracle Corporation)
Built-By: root
Specification-Title: JDBC
Specification-Version: 4.2
Specification-Vendor: Oracle Corporation
Implementation-Title: MySQL Connector Java
Implementation-Version: 5.1.46.99
Implementation-Vendor-Id: com.goldendb
Implementation-Vendor: Oracle
Bundle-Vendor: Gdb Corporation
Bundle-ClassPath: .
Bundle-Version: 5.1.46.99_2025-0619-0817
Bundle-Name: Gdb Corporation's JDBC Driver for MySQL
Bundle-ManifestVersion: 2
Bundle-SymbolicName: com.goldendb.jdbc
```

判读（**文档推断**，依据是 manifest 自身措辞）：

- `Implementation-Title` 仍是 **"MySQL Connector Java"**、`Specification-Vendor` 是 **Oracle Corporation** —— 这**不是**从零自研的驱动，是 **MySQL Connector/J 5.1 分支的整包重命名**，与磐维「openGauss-connector-jdbc 整包重命名」同谱系形态。
- `Bundle-Vendor: Gdb Corporation` + `Bundle-Version 5.1.46.99_2025-0619-0817` 是厂商侧改动标记（2025-06-19 构建）。
- `Implementation-Version` 保留上游 `5.1.46.99`（5.1 线的最新补丁位），说明厂商跟的是 5.1 而不是 8.x —— 这对 §二 的语句面登记有直接后果（类形按 5.1 而非 8.0 判）。

`META-INF/serial.txt` 给出厂商构建溯源（逐字）：

```
[base]
brty=git
buildnum=907
rev=6a1c68f
url=ssh://gerrit.zte.com.cn:29418/JZ_GoldenDB/Driver/JDBC
branch=ZXCLOUD-GoldenDB-connector-java-0.0.1-oracle
```

分支名带 `-oracle` 后缀，与下文 §1.2 的「Oracle 兼容」取向一致。

#### 1.1.3 包结构统计（照磐维口径）

| 顶层包 | `.class` 条目数 |
|---|---|
| `com/goldendb/**` | **391** |
| `org/gjt/mm/goldendb/**` | **1**（`org/gjt/mm/goldendb/Driver.class`，5.1 线的旧别名入口） |
| `org/**` 合计 | **1** |
| `com/mysql/**` | **0** |

**结论（实测）：整包重命名。** `com/mysql` 条目为 0、`org/postgresql` 式的上游包同样为 0；上游 `org.gjt.mm.mysql.Driver` 被改名为 `org.gjt.mm.goldendb.Driver`。与磐维（`org/panweidb` 374 条 / `org/postgresql` 0 条）同判据、同结论。

#### 1.1.4 驱动类全名（实测）

| 角色 | 类名 | 出处 |
|---|---|---|
| 注册入口（SPI） | **`com.goldendb.jdbc.Driver`** | `META-INF/services/java.sql.Driver` 全文即 `com.goldendb.jdbc.Driver\n` |
| 实现体 | `com.goldendb.jdbc.NonRegisteringDriver implements java.sql.Driver` | javap |
| 旧别名入口 | `org.gjt.mm.goldendb.Driver` | jar 条目 |
| 版本串常量 | `NonRegisteringDriver.VERSION = "gdb_mysql-connector-java-oracle-5.1.46.99"`、`NAME = "GoldenDB Connector Java"` | `javap -constants` |

**关键坑（实测，直接影响增强捕获）**：`com.goldendb.jdbc.Driver` 只声明了构造器与 `static {}`，**自身不声明 `connect(String, Properties)`**；该方法声明在父类 `com.goldendb.jdbc.NonRegisteringDriver`。`ConnectWeaver` 只遍历**本类 `methods`** 里 `desc == (Ljava/lang/String;Ljava/util/Properties;)Ljava/sql/Connection;` 的方法——所以**只登记 `Driver` 会零命中**，必须登记 `NonRegisteringDriver`（`com.mysql.cj.jdbc.NonRegisteringDriver` 已有同款先例，见 `DriverRegistry.java:29`）。

#### 1.1.5 URL 前缀（`javap -p -constants` 实测，逐字）

```java
private static final String REPLICATION_URL_PREFIX      = "jdbc:goldendb:replication://";
private static final String URL_PREFIX                  = "jdbc:goldendb://";
private static final String MXJ_URL_PREFIX              = "jdbc:goldendb:mxj://";
public  static final String LOADBALANCE_URL_PREFIX      = "jdbc:goldendb:loadbalance://";
public  static final String MASTERSLAVE_URL_PREFIX      = "jdbc:goldendb:masterslave://";
private static final String MYSQL_REPLICATION_URL_PREFIX = "jdbc:mysql:replication://";
private static final String MYSQL_URL_PREFIX             = "jdbc:mysql://";
private static final String MYSQL_MXJ_URL_PREFIX         = "jdbc:mysql:mxj://";
public  static final String MYSQL_LOADBALANCE_URL_PREFIX = "jdbc:mysql:loadbalance://";
public  static final String MYSQL_MASTERSLAVE_URL_PREFIX = "jdbc:mysql:masterslave://";
private static final String JDBC_MYSQL_URL_PREFIX        = "jdbc:mysql";
```

**两套前缀并存**：GoldenDB 自有 `jdbc:goldendb:` 与 MySQL 原生 `jdbc:mysql:`。这不是笔误——见 §1.1.6 的 `useGoldendbDriver` 开关。

#### 1.1.6 连接参数（构造器字节码实测）

`ConnectionPropertiesImpl` 构造器逐个 `new BooleanConnectionProperty(name, default, since, desc, category, order)`。布尔默认值在字节码里就是 `iconst_0`/`iconst_1`（用 `comStmtBatchFlag` 的 `iconst_0` 做对照锚点验证过该读法）：

| 参数 | 默认 | 字节码位置 | 说明（README.md 表内原文） |
|---|---|---|---|
| **`useGoldendbDriver`** | **true** | `7327: ldc "useGoldendbDriver"` → `7330: iconst_1` | 「`useGoldendbDriver=false`，jdbc:url 是 jdbc:mysql 开头，**不会**走 Goldendb 驱动解析」（5.1.46.97 引入；默认列 TRUE） |
| **`oracleMode`** | **true** | `6407: ldc "oracleMode"` → `6410: iconst_1` | **语义未知**——jar 内 `LocalizedErrorMessages.properties` 只有错误模板 `ConnectionProperties.oracleMode=Unable to initialize connection oracleMode`，**没有描述文案**。登记为 P-2 |
| `comStmtBatchFlag` | false | `6211` → `6214: iconst_0` | 批量协议开关（PDF p377 示例 URL 里显式写 `comStmtBatchFlag=true`） |
| `useOracleType` / `useOracleDate` / `useOracleBatchAffectRows` | false | README 表 | 均为 Oracle 语义适配开关，默认关 |

**这是本册最重要的一条事实**：默认配置下，**`jdbc:mysql://` 开头的 URL 也走 GoldenDB 驱动**。后果见 §二 A 面：用户拿 GoldenDB 驱动连 GoldenDB，但 URL 写 `jdbc:mysql://`，插件会**正确**判成 MySQL 方言（碰巧对），但**角标会显示 MySQL**、产品身份丢失；反之 URL 写 `jdbc:goldendb://` 时，插件**当前完全不认识**，会回落 MySQL 方言（也碰巧对，但角标空白）。

#### 1.1.7 语句面 / prepare 面类与自有方法族（javap 实测）

| 类 | 继承关系 | 自有 execute 族 | 结论 |
|---|---|---|---|
| `com.goldendb.jdbc.StatementImpl` | — | `execute(String)` ×4 重载、`executeQuery(String)`、`executeUpdate(String)` ×4、`executeLargeUpdate(String)` ×4、`executeBatch()`、`executeLargeBatch()`、`addBatch(String)`、`clearBatch()` | 纯语句面，与 `com.mysql.jdbc.StatementImpl` 同形 |
| `com.goldendb.jdbc.PreparedStatement` | `extends StatementImpl` | `execute()`、`executeQuery()`、`executeUpdate()`、`executeLargeUpdate()`、`addBatch()`、`addBatch(String)`、`clearBatch()`、`setObject` ×3、`setString` | PS 自有 execute 族齐全 |
| `com.goldendb.jdbc.ServerPreparedStatement` | `extends PreparedStatement` | **只有** `addBatch()`、`executeBatchSerially(int)`、`executeInternal(...)` —— **无自有 `execute()`/`executeQuery()`/`executeUpdate()`** | 见下方注意 |
| `com.goldendb.jdbc.CallableStatement` | `extends PreparedStatement` | `execute()`、`executeQuery()`、`executeUpdate()`、`executeBatch()`、`executeLargeUpdate()`、`executeLargeBatch()` | 自有族齐全，需单列 |
| `com.goldendb.jdbc.ConnectionImpl` | — | `prepareStatement(String)` ×6 重载、`prepareCall(String)` ×4 重载、`getURL()` **public** | prepare 面 + URL 反射兜底可用 |

**ServerPreparedStatement 的注意点（实测）**：它继承 `PreparedStatement` 的 execute 族，自有方法里只有 `addBatch()` 能命中 `ADD_BATCH` 形态。所以登记它是**安全的**（`ADD_BATCH` 会命中，字节层用例的「必须 changed」断言不会假绿），但**它的 execute 覆盖其实来自父类**——写进计划时必须说明这一点，否则后人会以为「登记了 SPS 就等于覆盖了 SPS 的执行路径」。

`MySQL` 5.1 分支的既有登记（`WeaveSpecs.java:57-59`）同时列了 `com.mysql.jdbc.StatementImpl` / `PreparedStatement` / `ServerPreparedStatement` 三个，GoldenDB 照抄即可。

### 1.2 PDF 事实（全部实测抽取，带页码）

抽取方式：`python -I` + `pymupdf`，`d[page-1].get_text()`。PDF 元数据：379 页、`creator=wkhtmltopdf 0.12.6`、`creationDate=2025-03-01`。**页码即 PDF 物理页号**（封面为 p1），与文档内印刷页码一致。

#### 1.2.1 产品形态与版本

| 事实 | 原文 | 页 |
|---|---|---|
| 产品与版本 | 「ZXCLOUD GoldenDB 数据库 / GoldenDB产品手册_开发支持 / **产品版本:V6.1.03.10**」 | p1 |
| 形态：分布式 | 目录与正文通篇「分布式事务」「CN/DN」「分片键」「分发键」「复制表」「MPP」；1.9.3 专章「GoldenDB 分布式数据库执行过程案例详解」 | p1-p8、p122-134 |
| MySQL 客户端兼容 | 「**GoldenDB 完全兼容 MySQL 客户端**，在安装好的 MySQL 客户端环境下，直接使用如下命令连接：`mysql -u username -p'password' -h host -P port`」 | **p37** |
| JDBC 标准实现 | 「GoldenDB 驱动**完全实现 JDBC 标准接口**，可以按照 JDBC 标准接口连接数据库」 | p9 |
| 推荐 JDK | 「推荐 JDK1.8」 | p9 |

#### 1.2.2 JDBC 连接串（逐字）

| 形态 | URL | 页 |
|---|---|---|
| 直连 | `jdbc:goldendb://host:port/test` | **p9**、p11、p37 |
| MyBatis 配置 | `<property name="driver" value="com.goldendb.jdbc.Driver"/>` + `<property name="url" value="jdbc:goldendb://host:port/test"/>` | **p11** |
| SpringBoot + Druid | `driver-class-name: com.goldendb.jdbc.Driver` + `url: jdbc:goldendb://ip:port/wmm` | **p43** |
| 负载均衡 | `jdbc:goldendb:loadbalance://host1:port1,host2:port2,host3:port4 ... /test` | **p38** |
| 批量协议 | `jdbc:goldendb://ip:port/wmm?comStmtBatchFlag=true&rewriteBatchedStatements=true&allowMultiQueries=true` | **p377-p379** |

**驱动类名与 URL 前缀与 §1.1 字节层完全一致**（文档 + 字节码双证）。

#### 1.2.3 SQL 语法（与本插件渲染面直接相关）

| 项 | 结论 | 原文/页码 |
|---|---|---|
| 分页 | **MySQL 形态** `LIMIT [offset,] row_count`；示例 `LIMIT 5` / `LIMIT 0,5` / `LIMIT 5,10`；「offset 表示从第几条记录开始，**从 0 开始计数**」 | **p73** |
| 多行 VALUES | 支持：`INSERT INTO t1 (a,b,c) VALUES (1,2,3),(4,5,6)` | **p66** |
| upsert（后缀） | **MySQL 形态** `ON DUPLICATE KEY UPDATE col_name=expr`；示例含 `c=VALUES(a)+VALUES(b)` | **p66** |
| upsert（整语句） | `REPLACE [INTO] tbl_name ... {VALUES|VALUE} (...),(...),...`；另有 `MERGE INTO ... USING ... ON(...)`（Oracle 风格） | **p281-p282** |
| 标识符引号 | 反引号（`SHOW CREATE PROCEDURE` 回显 `` `root`@`zte-179` ``、`` `simpleproc` ``） | **p320** |
| DELETE LIMIT | **支持**。1.10.8.2 DELETE 文法逐字含 `[ORDER BY ...]` 与 `[LIMIT row_count]`，并给出可执行示例 `DELETE FROM somelog WHERE user = 'jcole' ORDER BY timestamp_column LIMIT 1;` | **p274、p276** |
| 事务 | `START TRANSACTION` / 事务控制语句专章（1.7.3 p83-88） | p83 |
| EXPLAIN | 1.10.15.1「EXPLAIN」p323；另有 `OUTLINE`/`HINT` 固定执行计划 p267-269 | p323 |
| 保留字 | **三张分表**：CN MySQL 模式 / CN Oracle 模式 / DN（见 §1.2.4） | p351-355 |
| 数据类型 | INT/SMALLINT/TINYINT/MEDIUMINT/BIGINT、DECIMAL、NUMERIC、FLOAT、DOUBLE、BIT、DATE、TIMESTAMP、DATETIME、TIME、YEAR、CHAR/VARCHAR/TEXT/VARBINARY/BINARY/SET/ENUM、BLOB/CLOB；`DECIMAL(65,30)` 示例 | **p47-p49** |
| Oracle 兼容面 | 序列（`CREATE SEQUENCE`）、伪列 `ROWNUM`/`ROWID`、`MINUS`、`INTERSECT`、`(+)` 外连接、`SYSDATE`/`SYSTIMESTAMP`、`NVL`、`TO_CHAR`、`CONNECT BY`、PL/SQL 存储过程与 `PACKAGE`、自定义类型、`AUTONOMOUS_TRANSACTION` | p224-227、p285、p291、p335-367 |

#### 1.2.4 保留字（p351-p355，三张表分列）

原文抬头逐字：

- p351：「1.10.21. 保留字 / 本版本涉及的 **CN MySQL 模式**保留字如下：」
- p352 末：「本版本涉及的 **CN Oracle 模式**保留字如下：」
- p354 中：「本版本涉及的 **DN 保留关键字**如下：」

另有 p168-p172「1.9.8.1. 保留字」（单一列表，附录性质）。

抽取计数（**实测但含噪声**）：按 `[A-Z_][A-Z0-9_]*` 正则抓大写词，三张表分别得到 **368 / 367 / 227** 个去重词；p168-172 附录表 **271** 个。噪声来源是 PDF 断行把单词切断（例如 `STORAGE CLUSTER` 被拆成两个词、`_GDB_CN` 被切断后残留 `GDB_CN`/`CN`）。**落表前必须逐词人工核对**，不能拿这组数字当最终词表大小。

词表里能直接看出「双模」痕迹（p351-p352 的 CN MySQL 模式表里同时出现）：

- MySQL 原生：`AUTO_INCREMENT` 语义相关词、`SQL_CALC_FOUND_ROWS`、`STRAIGHT_JOIN`、`DAY_HOUR`、`ZEROFILL`
- Oracle 专属：`AUTONOMOUS_TRANSACTION`、`CONNECT_BY_ROOT`、`CONNECT_BY_ISLEAF`、`SYS_CONNECT_BY_PATH`、`ROWNUM`、`ROWTYPE`、`SYS_REFCURSOR`、`PLS_INTEGER`、`RAISE_APPLICATION_ERROR`、`MINUS`、`PIVOT`/`UNPIVOT`、`SYSDATE`、`SYSTIMESTAMP`、`FROM_TZ`、`SESSIONTIMEZONE`
- GoldenDB 自有：`CHGDISKEY`、`STORAGEDB`、`STORAGE CLUSTER`、`PARTITIONSTORAGEDB`、`SUBDISTRIBUTED`、`DISTRIBUTED`、`REDISTRIBUTING`、`GDB_NOLOCK`、`GDB_OUTLINE`、`GTIDSET`、`NOGTID`、`MVCC_PURGE`、`SAMEDB`、`WRITE_SAMEDB`、`READBALANCE`、`READMASTER`、`READSLAVE`、`_GDB_CN`

**这条「MySQL 词表里混着 Oracle 保留字」是本方言最需要小心的地方**：若照抄 `MYSQL_RESERVED`（MySQL 8.0 官方 262 词），会**漏包** `CHGDISKEY`/`STORAGEDB`/`GDB_NOLOCK` 等 GoldenDB 自有词，也会漏包 Oracle 兼容词——用户表里有 `storagedb` 列时渲染不加引号，在真库上是语法错。

p355 另有一条硬限制（逐字）：「**注意项**：`CURRVAL`、`DELETE`、`EXISTS`、`LIMIT` 和 `NEXTVAL` 关键字做表名时不支持带库名方式创建」，p356 附图 `db.DELETE(不支持) db.DELETE(支持) DELETE(不带库名不支持)`。这条只影响 DDL 建表，不影响本插件渲染（插件产物不建表），登记备查。

#### 1.2.5 「不支持 / 受限」清单（决定要不要 skipWords/降级）

PDF 没有集中的「不支持 SQL 清单」，但有四处实质限制，逐条登记：

| # | 限制 | 原文要点 | 页 |
|---|---|---|---|
| L1 | 三层及以上嵌套子查询 | 「不建议三层或以上的嵌套子查询……查询语句不能出现三次 SELECT」；属**不建议**（性能）而非语法禁止 | p109 |
| L2 | 批量协议覆盖范围 | 批量协议**只**覆盖简单 `insert`/`delete`/`update`；不支持多级分片表/多节点复制表下 insert 带 sequence、不支持并行与批量同时使用、**不支持 `merge into` / `replace into`** 走批量 | **p373** |
| L3 | 批量 delete/update 限制 | delete 不支持带全局索引的表、不支持跨分片计算的 where、不支持 prepare 带 sequence；update 另加「不支持更新分发键」「不支持 cw 隔离级别」 | **p373** |
| L4 | 复制表上的随机/时间函数 | 复制表 INSERT/UPDATE 用 `rand`/`now`/`sysdate`/`curtime`/`current_timestamp`/`unix_timestamp` 等会在各分片产生**不一致数据**（风险提示，非语法限制） | **p356** |

**对本插件的结论**：L1-L4 全部是**运行时/语义层限制**，不是「渲染出来就报语法错」的形态，所以**渲染套件不需要 skipWords 降级**；真机矩阵若撞上（例如矩阵里 `replace into` 在批量场景下失败），按 `skipWords + skipReason` 的既有纪律登记实例级例外（`matrix.example.properties:36-40`）。

#### 1.2.6 「双库运行兼容」建议（p159-p166）——影响渲染形态选择

1.9.6 专章给出「GoldenDB 与 Oracle 都支持」的写法建议，逐条与 MySQL 形态**相反**：

| 场景 | 建议（为兼容 Oracle） | 页 |
|---|---|---|
| 分页 | 「**避免使用 LIMIT**，使用 ROWNUM 限制返回记录数」 | p164 |
| 空值转换 | 「避免使用 `IFNULL`，使用 `nvl`」 | p164 |
| 日期格式化 | 「避免使用 `DATE_FORMAT`，使用 `TO_CHAR`」 | p164 |
| 字符串截取 | 「避免使用 `SUBSTRING`/`LOCATE`/`LEFT`/`RIGHT`，使用 `SUBSTR`/`INSTR`」 | p165 |
| 当前时间 | 「避免使用 `NOW()`，使用 `SYSDATE`」 | p165 |
| 单行查询 | 「避免没有 FROM 的 SQL，查单行也要 `FROM DUAL`」 | p165 |
| 类型转换 | 「避免 `CONVERT`（MySQL 是类型转换、Oracle 是字符集转换，含义不同），用 `CAST`」 | p165 |
| 注释 | 「不能使用 `//`、`#`，必须 `/* */`」 | p164 |
| 恒等条件 | 「不能用 `WHERE TRUE`，用 `WHERE 1=1`」 | p164 |
| 表/列别名 | 「表别名之间避免 `AS`；列别名避免单引号」 | p165 |
| 逻辑判断 | 「避免 `IF`，用 `CASE WHEN`」 | p165 |

**这不是方言语法要求，是「同一份 SQL 想同时跑 Oracle」的写法建议。** 本插件渲染的是**给 GoldenDB 单库执行**的 SQL，没有「同时跑 Oracle」的需求，所以**按 MySQL 形态渲染是正确选择**（§三 券 A 的 D-1）。但这条必须写进方言 KDoc——否则后人读 PDF 会以为插件该产 `ROWNUM`/`NVL`。

### 1.3 三条关键判断（一句话版）

1. **是否 MySQL 协议兼容**：**是**。p37「完全兼容 MySQL 客户端」+ 字节层 manifest `Implementation-Title: MySQL Connector Java` + URL 收 `jdbc:mysql:` 三证一致。
2. **是否整包重命名**：**是**。`com/goldendb` 391 条 / `com/mysql` 0 条 / `org` 仅 1 条（旧别名入口）。
3. **默认 SQL 方言**：**MySQL 形态**（分页 `LIMIT [offset,]count`、反引号、`ON DUPLICATE KEY UPDATE` 三面一致，p66/p73/p320）。Oracle 兼容面是**增量能力**（ROWNUM/DUAL/SEQUENCE/MERGE INTO），不是默认模式。

---

## 二、插件现状与缺口（逐面，带 `文件:行`）

### 2.1 面一：方言注册与命名

| 落点 | 现状 | 缺口 |
|---|---|---|
| `src/main/kotlin/.../sql/Dialect.kt:1189` `object DialectRegistry` | 14 个方言（`dialects` 列表在 `:1192-1207`，末项 `VastbaseDialect`） | 无 `GoldenDbDialect` |
| `src/main/kotlin/.../sql/Dialect.kt:1209+` `byName` | `dialects.firstOrNull { it.name == name } ?: MySqlDialect` —— **未知名静默回落 MySQL** | 名字拼错/未登记即静默错渲染，必须逐字一致 |
| `src/main/kotlin/.../sql/SqlKeywords.kt:497`（全文 497 行） | 保留字常量：`OPENGAUSS_RESERVED:393`、`GAUSSDB_RESERVED:409`、`HIGHGO_RESERVED:429`、`VASTBASE_RESERVED:467`、`MYSQL_RESERVED:50` | 无 `GOLDENDB_RESERVED`；照抄 `MYSQL_RESERVED` 会漏 GoldenDB 自有词与 Oracle 兼容词（§1.2.4） |
| `src/main/kotlin/.../sql/Dialect.kt:423` `object MySqlDialect` | `name="MySQL"`、`renderLimit=renderMySqlStyleLimit`、`quoteIdentifier` 反引号、`supportsDeleteLimit=true`、`renderUpsert=renderMySqlStyleUpsert` | GoldenDB 默认形态与之**逐项相同**，但身份与保留字必须分叉（同 `GaussDbDialect` 的分叉理由） |

**命名建议**：`Dialect.name = "GoldenDB"`（与产品名一致；设置下拉/角标/MCP/矩阵四处共用）。**不要**写 `GoldenDB（中兴）`——`DialectRegistry.byName` 是逐字比较，长名会让用户在矩阵文件里写错一格就静默回落 MySQL（`DialectExternalMatrixVerificationTest` 第 1 条判红底线专治这个，但产品名越短越好写对）。

### 2.2 面二：URL → 方言映射与角标

| 落点 | 现状 | 缺口 |
|---|---|---|
| `src/main/kotlin/.../sql/DialectInference.kt:16` `UrlSubprotocolDialects.SUBPROTOCOL_TO_DIALECT` | 20 个键（`mysql`/`mariadb`/`postgresql`/`oracle`/`sqlserver`/`h2`/`sqlite`/`dm`/`kingbase8`/`kingbase`/`oceanbase`/`opengauss`/`gaussdb`/`highgo`/`vastbase`/`dws`/`panwei`/`panweidb`），值必须**逐字等于** `Dialect.name` | 无 `goldendb`。当前 `jdbc:goldendb://` → `null` → 调用方回落 MySQL（碰巧对，但依据丢失、`DialectDecisionEngine` 记为 UNAVAILABLE） |
| `src/main/kotlin/.../log/agent/JdbcUrlDialects.kt:15` `SUBPROTOCOL_NAMES` | 角标事实显示名，独立命名空间（`:6-11` 明确「不能走 byName 的未知回退 MySQL」） | 无 `goldendb`。当前 `jdbc:goldendb://` 角标**不显示**（`displayName` 返回 null） |
| `src/main/kotlin/.../sql/PreviewDialectResolver.kt:31` `BADGE_HEAD_ALIASES` | 只桥接「显示名 ≠ 注册名」的产品（现仅 `"panwei（磐维）" to "Oracle"`） | 若角标显示 `GoldenDB` 且注册名也是 `GoldenDB`，**不需要**加别名（同名直接走 `knownDialect`） |
| `src/main/kotlin/.../mcp/McpDialectDetector.kt:14` `URL_PATTERNS` | `startsWith/contains` 子串匹配，次序即优先级 | 无 `jdbc:goldendb`。**注意次序陷阱**：`"jdbc:mysql"` 在首位，若将来把 `goldendb` 加在它后面，`jdbc:goldendb://` 不会被 `jdbc:mysql` 误命中（前缀不同），安全 |
| `src/main/kotlin/.../mcp/McpDialectDetector.kt:36` `ALIASES` | agent 显式方言名精确别名（含中文「达梦」「高斯」「瀚高」「海量」） | 无 `goldendb` / `中兴` / `金篆`（GoldenDB 中文名「金篆」需用户确认，见 P-6） |
| `src/main/kotlin/.../sql/ProjectDialectEvidence.kt:80-104` | Maven 坐标 → 方言（如 `"vastbase" to "Vastbase"` 在 `:101`） | 无 GoldenDB 驱动坐标。GoldenDB 驱动不在 Maven Central（厂商渠道分发），坐标匹配大概率落空 → 登记为「已知不支持」，不硬造坐标 |
| `src/main/kotlin/.../execute/ExecDialectRouting.kt:51` `PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS` | 插件方言名 → 平台 language id 候选链 | 无 `GoldenDB`。**漏登记的后果是执行目标 Runner 枚举落空并回退 MySQL**（`:63-65` 的 GaussDB 注释写明） |

### 2.3 面三：增强捕获三面（连接 / 语句 / prepare）

| 面 | 落点 | 现状 | 缺口 |
|---|---|---|---|
| 连接面 | `sub-projects/sql-visualizer-agent/.../DriverRegistry.java:24-73` `DRIVER_CLASSES` | MySQL / MariaDB / PG / openGauss / H2 / HSQL / SQLite / SQLServer / Oracle / GBASE / Kingbase / Vastbase / HighGo / OceanBase / Panwei 共 22 个类名 | 无 `com.goldendb.*` |
| 语句面 | `sub-projects/sql-visualizer-agent/.../WeaveSpecs.java:53-117` `statementFace` 的 `putSpec` 分组 | MySQL 5.1 组在 `:57-59`（`StatementImpl`/`PreparedStatement`/`ServerPreparedStatement`） | 无 `com.goldendb.*` 三/四个类 |
| prepare 面 | `WeaveSpecs.java:166-202` `prepareFace` | MySQL 5.1 在 `:169`（`com.mysql.jdbc.ConnectionImpl`） | 无 `com.goldendb.jdbc.ConnectionImpl` |
| 兜底 | `WeaveSpecs.java:222-230` `CATCH_ALL_STATEMENT_FACE` / `CATCH_ALL_PREPARE_FACE` | 接口匹配命中但不在白名单的类走兜底面（少 `ADD_BATCH`/`CLEAR_BATCH` 两形态） | 未登记时只能靠兜底：5000 类扫描上限 + attach 单轮（磐维券 A 的实证，`docs/开发计划/20261106-1033-...` §3.2），大型应用大概率漏扫 |

**登记清单（按字节层实测确定，非照抄）**：

```
连接面：com.goldendb.jdbc.NonRegisteringDriver   ← 必须（connect 声明在此）
        com.goldendb.jdbc.Driver                 ← 可登记（零命中，只为「按名字查得到」的直觉一致性）
语句面：com.goldendb.jdbc.StatementImpl
        com.goldendb.jdbc.PreparedStatement
        com.goldendb.jdbc.ServerPreparedStatement   ← 自有 execute 族为空，靠父类；addBatch() 会命中
        com.goldendb.jdbc.CallableStatement         ← 自有 execute 族齐全，必须单列
prepare 面：com.goldendb.jdbc.ConnectionImpl
```

**为什么不登记 `JDBC4*` 一族**（`JDBC4Connection`/`JDBC4PreparedStatement`/`JDBC4ServerPreparedStatement`/`JDBC4CallableStatement` 等）：MySQL 5.1 的既有登记就没列它们（`WeaveSpecs.java:57-59`），且 `JDBC4Connection extends ConnectionImpl`、`JDBC4PreparedStatement extends PreparedStatement`——父类织入即覆盖。**这一条要在字节层用例里用「零命中即红」反向确认**（P-7）。

### 2.4 面四：渲染验证套件

| 落点 | 现状 | 缺口 |
|---|---|---|
| `src/test/kotlin/.../sql/verify/DialectVerifySupport.kt:66` `data class EngineSpec` | `label` / `jdbcUrlTemplate` / `dialect` / `upsertMode` / `multiRowValues` | — |
| 同上 `:76-83` `h2Engines` | 6 个 H2 兼容模式配对（H2 / MySQL / MariaDB / PostgreSQL / Oracle / SQLServer），**没有 H2 的「GoldenDB 模式」**（H2 无此模式） | GoldenDB 无 H2 配对可加 → 只能靠**真机矩阵**或「MySQL 配对 + 声明 GoldenDB 形态同 MySQL」 |
| 同上 `:100` `upsertModeOf(dialect)` | 由 `renderUpsert`/`renderUpsertStatement` 是否 null **推导** | 若 GoldenDB 走 MySQL 形态 upsert，自动得 `REAL`，无需改 |
| `src/test/kotlin/.../sql/verify/DialectExternalMatrixVerificationTest.kt:50` `MIN_RESERVED_WORDS = 50` | 词表 < 50 词即判红（防「词表没接上→空扫→空绿」） | GoldenDB 词表约 300+ 词，远超下限，无需改 |
| `dev-support/dialect-external/matrix.example.properties:16-19` | 方言名清单注释「MySQL / … / GaussDB / HighGo / Vastbase」 | 需补 `GoldenDB` |
| 同上 `:36-40` `skipWords`/`skipReason` | 例外必须带理由、词必须在词表里 | GoldenDB 暂无已知例外，登记为「空」 |

### 2.5 面五：自注册方言（平台语言方言）

**要不要注册 `GoldenDbSqlDialect`？** 按既有判据分两半：

| 判据 | 结论 |
|---|---|
| 平台 262 是否有 GoldenDB 方言？ | **无**（既有四例 `DM`/`GaussDB`/`HighGo`/`Vastbase` 都是「平台缺位→自注册」，`database-optional.xml:27-42` 逐条注明）。GoldenDB 同样缺位（产品名不在 `intellij.database.dialects.*` 枚举内——**待复核，见 P-8**） |
| 自注册的代价 | **必须成对注册** `<sql.dialect>` + `<lang.parserDefinition>`（`database-optional.xml:44-55` 的 KDoc 写明了只注册一半的后果：`ClassCastException` → `PsiManager.findFile` 返回 null → 预览预热降级、idea.log 每次一条 SEVERE）。守卫 `SqlDialectParserDefinitionPairingGuardTest` 会先红 |
| 不做会怎样 | `ExecDialectRouting` 的候选链只能落 `MySQL` 兜底（照 `MariaDB to listOf("MariaDB","MySQL")` 的家族别名写法），执行目标 Runner 仍能枚举到 |

**建议：做**（与 GaussDB/HighGo/Vastbase 同口径），代价是 4 个新文件 + 2 条 EP：

- `src/main/kotlin/.../sql/goldendb/GoldenDbSqlDialect.kt`（照 `VastbaseSqlDialect.kt` 逐行模板：构造传 id `GoldenDB`、`createTokensHelper` 委托 `Sql92Dialect`、`getDbms()=Dbms.UNKNOWN`、`supportsErrorHighlighting=false`、`@JvmField val INSTANCE`、`const val ID/DISPLAY_NAME = "GoldenDB"`）
- `SqlFamilyParserDefinitions.kt:88` 旁加 `class GoldenDbParserDefinition : MpvSqlFamilyParserDefinition(GoldenDbSqlDialect.INSTANCE, "MPV_GOLDENDB_SQL_FILE")`
- `database-optional.xml:42` 后加 `<sql.dialect dbms="UNKNOWN" order="last" implementationClass="...goldendb.GoldenDbSqlDialect"/>` 与 `<lang.parserDefinition language="GoldenDB" implementationClass="...GoldenDbParserDefinition"/>`（**成对**）
- `ExecDialectRouting.kt:71` 后加 `"GoldenDB" to listOf("GoldenDB", "MySQL")`

### 2.6 面六（额外发现）：日志来源定位的包前缀

任务书只列五面，实际还有一处**不改就退化**的落点：

| 落点 | 现状 | 缺口 |
|---|---|---|
| `src/main/kotlin/.../log/CallerSourceLocator.kt:77` | `cn.startsWith("com.mysql.") \|\| cn.startsWith("org.mariadb.") \|\| ...` 用于把驱动栈帧判为「框架帧」跳过 | 无 `com.goldendb.` → GoldenDB 驱动的栈帧会被当成**业务帧**，`Class#method` 显示与调用点定位可能指到驱动内部 |
| `src/main/kotlin/.../log/SourceInterceptorTemplates.kt:281` | 同上（拦截器模板的框架包判定） | 同上 |
| `src/main/kotlin/.../ui/StackDigestFrames.kt:149` | 框架包名列表 `"com.mysql.", "org.mariadb.", ...` | 同上 |

这三处**必须与捕获面同批改**，否则「捕获到了但来源位置标错」——比抓不到更难查。

### 2.7 面七（额外发现）：EXPLAIN 档位

`src/main/kotlin/.../sql/ExplainSupport.kt:130` 的 `table` 是**显式登记表**（`Entry("MySQL", "EXPLAIN $SQL_PLACEHOLDER", TABLE, ...)` 在 `:131`）。未登记的方言 → `ExplainSupport` 查不到 → 能力矩阵 `EXPLAIN` 行显示 false、`ExplainGate` 不给入口。

GoldenDB 的 EXPLAIN 形态 PDF 有专章（1.10.15.1 p323）但**本册未逐字核对输出格式**（`TABLE` 行式还是 `TEXT` 树式）——登记为 P-9，拿到原文再定档位。**不要照抄 MySQL 的 `TABLE` 档**：OceanBase 的教训（`ExplainSupport.kt:139-145`）是档位误登会让索引建议走错解析分支而永不触发。

### 2.8 缺口汇总（一张表）

| # | 面 | 缺什么 | 不改的后果 |
|---|---|---|---|
| G1 | 方言注册 | `GoldenDbDialect` + `GOLDENDB_RESERVED` | 无 GoldenDB 可选项 |
| G2 | URL→方言 | `UrlSubprotocolDialects["goldendb"]` | `jdbc:goldendb://` → 回落 MySQL（碰巧对，依据丢失） |
| G3 | 角标 | `JdbcUrlDialects["goldendb"]` | 角标空白，产品身份不可见 |
| G4 | MCP | `McpDialectDetector` URL_PATTERNS + ALIASES | MCP 工具报 MySQL |
| G5 | 执行路由 | `PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GoldenDB"]` | 执行目标 Runner 枚举落空→回退 MySQL |
| G6 | 连接面 | `com.goldendb.jdbc.NonRegisteringDriver` | 增强捕获零产出（只能靠兜底，大概率漏扫） |
| G7 | 语句面 | `StatementImpl`/`PreparedStatement`/`ServerPreparedStatement`/`CallableStatement` | 同上 |
| G8 | prepare 面 | `com.goldendb.jdbc.ConnectionImpl` | 有钩子但取不到 SQL 文本 |
| G9 | 框架包前缀 | `com.goldendb.` 进三处框架判定 | 来源位置指到驱动内部 |
| G10 | 自注册方言 | `sql.dialect` + `lang.parserDefinition` 成对 | 执行路由只能落 MySQL 兜底；用户无法在 SQL Dialects 里选 |
| G11 | EXPLAIN | `ExplainSupport` 条目 | 能力矩阵 EXPLAIN 行 false |
| G12 | 矩阵模板 | `matrix.example.properties` 方言清单补 `GoldenDB` | 用户照模板填会被判红（「方言名必须逐字命中」） |

---

## 三、方案（分券）

四券串行，**券 B 不依赖券 A**（可并行），券 C 依赖 A+B，券 D 收尾。

### 券 A：方言映射与角标（纯映射层，零风险）

**改哪些文件**

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/.../sql/Dialect.kt` | 新增 `object GoldenDbDialect : Dialect`（形态见下）；`DialectRegistry.dialects`（`:1192-1207`）加一项 |
| `src/main/kotlin/.../sql/SqlKeywords.kt` | 新增 `val GOLDENDB_RESERVED: Set<String>`（词表来源见 P-3） |
| `src/main/kotlin/.../sql/DialectInference.kt:16` | `"goldendb" to "GoldenDB"` |
| `src/main/kotlin/.../log/agent/JdbcUrlDialects.kt:15` | `"goldendb" to "GoldenDB"` |
| `src/main/kotlin/.../mcp/McpDialectDetector.kt:14/36` | `"jdbc:goldendb" to "GoldenDB"`；别名 `"goldendb"`/`"中兴"`（中文名待 P-6） |
| `src/main/kotlin/.../execute/ExecDialectRouting.kt:51` | `"GoldenDB" to listOf("GoldenDB", "MySQL")` |
| `src/main/kotlin/.../sql/ExplainSupport.kt:130` | 按 P-9 定档后加条目 |
| `src/main/kotlin/.../sql/PreviewDialectResolver.kt:31` | **不加**（角标名与注册名同为 `GoldenDB`，同名直接命中 `knownDialect`） |

**`GoldenDbDialect` 形态（逐项判据，全部来自 §1.2.3 实测引文）**

```kotlin
object GoldenDbDialect : Dialect {
    override val name = "GoldenDB"
    override val transactionBegin = "START TRANSACTION"   // p83 事务控制语句专章；与 MySQL 同形
    override fun renderLimit(limit) = renderMySqlStyleLimit(limit)  // p73 LIMIT [offset,]count，0 起算
    override fun quoteIdentifier(id) = "`$id`"            // p320 SHOW CREATE 回显反引号
    override val identifierQuotes = setOf('`')
    override val reservedWords = GOLDENDB_RESERVED        // p351-355 三表并集，见 P-3
    override fun renderUpsert(...) = renderMySqlStyleUpsert(...)  // p66 ON DUPLICATE KEY UPDATE
    override val supportsDeleteLimit = true               // p274 文法 + p276 可执行示例（文档级）
}
```

`supportsDeleteLimit` 的取值依据：1.10.8.2 DELETE 文法逐字含 `[ORDER BY ...]` 与 `[LIMIT row_count]`（p274），并给出示例 `DELETE FROM somelog WHERE user = 'jcole' ORDER BY timestamp_column LIMIT 1;`（p276）——这是**带 ORDER BY 的 LIMIT 尾段**，比裸 `LIMIT` 更强。故取 `true`（文档级），真机到位后复核即可，不必像 `HighGoDialect`/`GaussDbDialect` 那样先写 false。

**不要**照抄 MySQL 的「默认支持」心态——本条的 `true` 有逐字页码支撑，而 P-5 的原始疑虑（「通用文法有但专章可能重写」）已被 DELETE 专章原文排除。

**判据**

1. `DialectRegistry.byName("GoldenDB")` 命中 `GoldenDbDialect`（不是回落 MySQL）——单测直接断言 `byName("GoldenDB") === GoldenDbDialect`；
2. `UrlSubprotocolDialects.dialectName("jdbc:goldendb://h:3306/db") == "GoldenDB"`，且 `"jdbc:goldendb:loadbalance://h1:p1,h2:p2/db"` 同（子协议取首个 `:` 前，两条都命中 `goldendb`）；
3. `JdbcUrlDialects.displayName("jdbc:goldendb://h:3306/db") == "GoldenDB"` 且 `badgeName(...)` 带出 `db@h:3306`；
4. `McpDialectDetector.guessFromUrl(...) == "GoldenDB"`，`normalize("GoldenDB") == "GoldenDB"`；
5. `GOLDENDB_RESERVED.size >= 50`（矩阵下限）且**含** `storagedb`/`chgdiskey`/`gdb_nolock`/`rownum`/`varchar2`（GoldenDB 自有 + Oracle 兼容词），**含** `limit`/`order`/`table`（与 MySQL 共有）；
6. 反向验证：**摘掉** `DialectRegistry.dialects` 里的 `GoldenDbDialect` → 判据 1 必红；**摘掉** `UrlSubprotocolDialects` 的 `goldendb` 键 → 判据 2 必红（证明用例真在读这两张表，不是空断言）。

### 券 B：增强捕获三面登记 + 字节层用例

**改哪些文件**

| 文件 | 改动 |
|---|---|
| `sub-projects/sql-visualizer-agent/.../DriverRegistry.java:24-73` | 加 `com.goldendb.jdbc.NonRegisteringDriver`（**必须**）+ `com.goldendb.jdbc.Driver` |
| `sub-projects/sql-visualizer-agent/.../WeaveSpecs.java:53-117` | `statementFace` 组加 `com.goldendb.jdbc.StatementImpl` / `PreparedStatement` / `ServerPreparedStatement` / `CallableStatement` |
| `sub-projects/sql-visualizer-agent/.../WeaveSpecs.java:166-202` | `prepareFace` 加 `com.goldendb.jdbc.ConnectionImpl` |
| `sub-projects/sql-visualizer-agent/src/test/.../StatementWeaverVendorCoverageTest.java:49/139` | `STATEMENT_FACE_CLASSES` / `PREPARE_FACE_CLASSES` 补四个/一个 |
| 同上 `:128` `JAR_OPTIONAL_BYTE_LAYER` | 加 `com.goldendb.jdbc.*`（jar 不在测试 classpath） |
| 同上 `:532` 磐维专项用例旁 | 新增「GoldenDB 字节层」用例，遍历 `database-lab/vendor/drivers/gdb_mysql-connector-java-*.jar` |

**GoldenDB 字节层用例的形态**（照磐维 `:533-570` 逐行模板）：

- jar 查找顺序：`-Dgoldendb.jar` → 环境变量 `GOLDENDB_JAR` → 从 `user.dir` 逐级向上找 `database-lab/vendor/drivers/gdb_mysql-connector-java-*.jar`；都没有时 `assumeTrue` **显式 skip**（不静默通过）；
- 断言三条：①`WeaveSpecs.specOf(dotted) != null`（已登记）；②`!Arrays.equals(original, woven)`（**必须真被织入**——登记了却零命中即假覆盖）；③隔离子 loader `Class.forName(dotted, true, loader)` 通过 JVM 链接期校验；
- **额外一条 GoldenDB 专属断言**：`ServerPreparedStatement` 的自有 execute 族为空，故它的 `changed` 必须来自 `ADD_BATCH`（`addBatch()`）而非 execute 族——用 `StatementWeaver.Kind` 的命中集合断言，防止「父类覆盖」被误读成「本类覆盖」。

**判据**

1. 字节层用例在 `database-lab/vendor/drivers/` 的 jar 在位时**不 skip**、四个语句类 + 一个连接类全部 changed 且过校验；
2. `DriverRegistry.isWeavable("com.goldendb.jdbc.NonRegisteringDriver")` 为 true；
3. **反向验证一**：把 `NonRegisteringDriver` 从 `DRIVER_CLASSES` 摘掉、只留 `Driver` → 字节层用例对 `Driver` 的 changed 断言**必红**（证明「Driver 自身不声明 connect」这条事实被用例钉住）；
4. **反向验证二**：摘掉 `com.goldendb.jdbc.ConnectionImpl` 的 prepare 面 → prepare 面用例必红；
5. 厂商对齐守卫（`VENDOR_NOT_COVERED` 表，`:235`）不新增豁免——GoldenDB 三面全覆盖，不该进「未覆盖」表。

### 券 C：渲染套件与矩阵条目

**改哪些文件**

| 文件 | 改动 |
|---|---|
| `src/test/kotlin/.../sql/verify/DialectVerifySupport.kt:76-83` | **不加** H2 配对（H2 无 GoldenDB 模式）。改为在类注释登记「GoldenDB 形态与 MySQL 逐项相同，故 H2 MySQL 配对的读数作为**形态级**对照证据，不作为 GoldenDB 引擎证据」 |
| `dev-support/dialect-external/matrix.example.properties:16-19` | 方言清单补 `GoldenDB` |
| `dev-support/dialect-external/matrix.example.properties` 末尾 | 加注释示例条目（不含凭据）：`# goldendb.dialect = GoldenDB` / `# goldendb.url = jdbc:goldendb://host:port/db` |
| `src/main/kotlin/.../sql/DialectCapabilities.kt` | **不改**（能力矩阵全部从 `Dialect` 真值推导，加方言自动出现在矩阵里） |
| `docs/架构设计/*` | 若有方言清单章节需同步（本册未逐页核对，见 P-10） |

**判据**

1. `DialectCapabilities.matrix(GoldenDbDialect)` 四行全部产出（EXPLAIN / DELETE_LIMIT / UPSERT / IDENTITY_WRAP），且 `UPSERT.supported == true`（有原生形态）、`IDENTITY_WRAP.supported == false`（无包装需求）；
2. 矩阵模板注释里的方言清单含 `GoldenDB`（防止用户照模板写错名被静默回退）；
3. **反向验证**：把 `GoldenDbDialect.renderUpsert` 改成返回 null → `DialectCapabilities` 的 UPSERT 行必须变 false（证明矩阵读的是真值不是硬编码）。

### 券 D：文档与验收

**改哪些文件**

| 文件 | 改动 |
|---|---|
| `CHANGELOG.md` `[Unreleased]` | 一句一条，带提交哈希（**用户可感改动**：新增 GoldenDB 方言 + 增强捕获支持） |
| `docs/架构设计/` 归属文档 | 按 AGENTS.md 第 4 条「改契约必须同批更新归属文档」——方言清单/URL 映射表若在架构文档里有副本，同批改 |
| `docs/LESSONS.md` | 若实施中踩到新坑（如 `oracleMode` 语义、Driver 不声明 connect），按「一句话规则 + hash 证据 + Seen 计数」补记 |
| 本册 §七 实施记录 | 回填每券的实际读数（定向测试计数、字节层用例结果、真机矩阵结果） |

**判据**：`CHANGELOG` 条目在场且带哈希；本册 §七 每个「未验证」项要么销案（给读数）要么保持「未验证」措辞（**不许**改写成「已验证」）。

---

## 四、待验证前提 P-1..P-10

每条写清「我推断的是什么、怎么证伪、证伪了怎么办」。

### P-1 能否拿到真机实例（**最高风险，决定本任务能否宣称「方言可用」**）

- **推断**：GoldenDB 是中兴通讯的商用分布式数据库，**大概率无公开容器镜像**，也不在 Docker Hub 官方组织下。用户在测试服务器（`103.36.220.245`，16GB）上现有 12 个容器（见 `database-lab/README.md`「内存基线」节），其中**没有** GoldenDB。
- **照实登记**：本册**不主张**「可以 Docker 起」。要拿到实例只有三条路：①用户有厂商授权渠道拿到镜像 tar（照 DM8/KingBase 的 `load-*.ps1` 模式）；②用户有现网 GoldenDB 可只读连；③都没有 → **真机档位为空**。
- **证伪方式**：向用户确认；或在测试服务器上 `docker images | grep -i golden` 复核（本任务不跑，避免占用构建/服务器资源）。
- **证伪后怎么办**：**不宣称方言可用**（§六）。券 A/B/C 仍可全部实施并用字节层 + 单测 + MySQL 对照验证，但发行说明只能写「新增 GoldenDB 方言（**文档级**，未经真机验证）」。

### P-2 `oracleMode` 连接参数（驱动默认 **true**）的语义，以及它是否影响方言归属

- **实测部分**：`ConnectionPropertiesImpl` 构造器 `6407: ldc "oracleMode"` → `6410: iconst_1` ⇒ **默认 true**；`getOracleMode()/setOracleMode(boolean)` 存在；jar 内**无描述文案**（`LocalizedErrorMessages.properties` 只有错误模板）。
- **推断（未验证）**：它是**驱动侧**行为开关（很可能与 `useOracleType`/`useOracleDate`/`oracleGeneratedKey` 一族配合，控制类型映射与批量影响行数语义），**不是**服务端 SQL 模式。依据：PDF 全文检索 `oracle_mode`/`compatible_mode`/`dbmode`/`兼容模式`（除 ECGDB 预编译器一节）**零命中**——文档没有「数据库兼容模式」这个概念的开关说明。
- **为什么重要**：如果 `oracleMode=true` 真的会改变 SQL 语义（例如让驱动改写 SQL 或影响结果类型），那么「URL 里 `?oracleMode=true` 的库该按 MySQL 还是 Oracle 渲染」就是一个**必须分叉**的问题——照磐维的教训（`DialectInference.kt:45-51`：`db_compatibility` 被误当 SQL 兼容模式，导致误判），**不能靠参数名猜**。
- **证伪方式**：真机建两张表，同一句 `SELECT SYSDATE FROM DUAL` / `SELECT IFNULL(1,2)` 在 `oracleMode=true` 与 `false` 两个连接上分别执行，看是否一个成功一个报错。无真机则**保持不分叉**（恒 MySQL 渲染），并在方言 KDoc 写明「`oracleMode` 是驱动参数，语义未取证，不参与方言推断」。
- **证伪后怎么办**：若确认影响 SQL 语义 → 加 URL 参数嗅探（`UrlSubprotocolDialects` 扩成「子协议 + 查询参数」双键）；若不影响 → 记录「已排除」并保留不分叉。

### P-3 保留字词表从哪来

- **实测部分**：PDF 有三张分表（CN MySQL 模式 / CN Oracle 模式 / DN，p351-355）+ 一张附录表（p168-172）。抽取计数 368 / 367 / 227 / 271，**含断行噪声**。
- **推断**：三张表是**同一引擎不同执行层/模式**的并集关系，不是互斥集合。CN MySQL 模式表里已经混入 `AUTONOMOUS_TRANSACTION`/`ROWNUM`/`VARCHAR2` 等 Oracle 词，说明「CN 层的词表本身是并集」。
- **取证方式（待做）**：把 p351-p355 抽出的词逐个对回原文行，人工清理断词（`STORAGE CLUSTER`、`_GDB_CN`、`DEBUG_BACKSTRACE` 等），得到干净词表；再与 `MYSQL_RESERVED` 做**双向差集**，列出「GoldenDB 有而 MySQL 没有」的词（预期含 `storagedb`/`chgdiskey`/`gdb_nolock`/`rownum`/`varchar2`/`autonomous_transaction` 等）。
- **落表口径**（照 `HIGHGO_RESERVED`/`VASTBASE_RESERVED` 的先例）：**取 CN MySQL 模式表为基线**（默认模式），**并上** GoldenDB 自有词与 Oracle 兼容词（引用幂等安全——包裹后在本方言合法），`_GDB_CN` 这类内部标识符**剔除**（不是合法标识符，包不包都不影响用户）。
- **证伪后怎么办**：若真机实测某些词其实**不是**保留字（例如 `storagedb` 可裸用作列名），**不要**从词表里删——「宁可多包」是本仓既有口径（`SqlKeywords.kt` 类注释「引用幂等安全」），删了会让「以引号创建的大写名」漏包。

### P-4 upsert / 分页语法是否与 MySQL 一致

- **实测部分（文档级）**：分页 `LIMIT [offset,] row_count`、0 起算（p73）；upsert `ON DUPLICATE KEY UPDATE`（p66）、`REPLACE INTO`（p282）、`MERGE INTO`（p282）。三处示例逐字。
- **推断**：与 MySQL 逐项一致，故 `renderMySqlStyleLimit` + `renderMySqlStyleUpsert` 可直接用。
- **证伪方式**：真机跑 `DialectVerifySupport.runTcSuite`（它会用插件渲染的 SQL 真执行：`LIMIT 1,2`、`ON DUPLICATE KEY UPDATE c=VALUES(c)`、多行 VALUES、DELETE LIMIT）。**注意**：L2（p373）说批量协议**不支持 `merge into`/`replace into`**——这是**批量协议**的限制，不是语句不支持；矩阵套件走的是普通 `execute`，不受影响，但若真机报错要能分清是哪一层。
- **证伪后怎么办**：若 `LIMIT offset,count` 报错 → 改 `renderPgStyleLimit`（`LIMIT count OFFSET offset`）；若 `ON DUPLICATE KEY UPDATE` 报错 → 改 `MERGE INTO`（p282 有文法，但它是 Oracle 风格，需要 `USING` 子句，改动面比 upsert 后缀大得多，届时要重开一券）。

### P-5 `DELETE ... LIMIT` 尾段是否支持

- **实测部分（已销案，文档级）**：1.10.8.2 DELETE 文法（p274）逐字为
  ```
  DELETE FROM tbl_name
  [PARTITION number]
  [WHERE where_condition]
  [ORDER BY ...]
  [LIMIT row_count]
  [CONSISTENCY_OPTION]
  [STORAGEDB groupno]
  [SAMEDB]
  ```
  并在 p276 给出可执行示例 `DELETE FROM somelog WHERE user = 'jcole' ORDER BY timestamp_column LIMIT 1;`，参数表另逐条解释 `ORDER BY`（删除行的排序顺序）与 `LIMIT row_count`（限制删除的行数）。
- **结论**：**支持**，券 A 取 `supportsDeleteLimit = true`。
- **残留未验证**：文档级证据，**无真机读数**。真机到位后跑一次 `DELETE FROM t WHERE id=1 LIMIT 1` 确认「真删 1 行」即完全销案。
- **证伪后怎么办**：若真机报 `syntax error near LIMIT`（说明 CN 层拦截了 DN 支持的文法），改回 `false` 并把本条改写成「文档写支持、真机不支持」的矛盾记录——**不要**直接删掉这条，矛盾本身是有价值的证据。

### P-6 GoldenDB 的中文产品名

- **实测部分**：PDF 全文用「GoldenDB」，**未出现**「金篆」二字（本册未做全文检索该词，见下方取证）。
- **推断**：中兴通讯的 GoldenDB 市场中文名常见「金篆」，但本 PDF 未用。
- **证伪方式**：`grep -c "金篆"` 于 PDF 抽取全文。
- **处理**：`McpDialectDetector.ALIASES` 只加 `"goldendb"` 与**确证过的**中文名；不确定就不加（加错的后果是用户手输中文名得不到归一，但不会误判——比误归一安全）。

### P-7 增强捕获的语句面是否被驱动代理层包裹

- **实测部分**：`StatementImpl`/`PreparedStatement`/`CallableStatement` 的 execute 族在**本类**声明（javap 逐条列出）；`ServerPreparedStatement` 只声明 `addBatch()` + 两个 protected 内部方法。`PreparedStatement` 里出现了 `executeZteBatch`/`executeFet` 等**厂商自研方法**（`protected long[] executeZteBatch(int, java.lang.Boolean)`），说明厂商在 5.1 基础上改过批量执行路径。
- **推断**：主路径（`prepareStatement` → `executeQuery`/`executeUpdate`/`execute`）仍走本类声明的方法，织入可覆盖；但 `executeZteBatch` 这类**自研批量路径**若被 `executeBatch()` 调用，则批量事件的 SQL 文本可能来自不同的内部方法（`executeBatchSerially` 已被 `BATCH` 形态覆盖，`executeZteBatch` 未覆盖）。
- **这是磐维教训的复用**：磐维的坑是「包装器登记 vs 内层执行」——登记了外层却织不到真正执行的内层。GoldenDB 的对应风险是「登记了 `PreparedStatement` 但批量走 `executeZteBatch`」。
- **证伪方式**：真机 + 增强捕获开，跑一次 `addBatch`/`executeBatch`，看事件是否带 SQL 文本与参数。**无真机则登记为未验证**，不宣称「批量事件可采」。
- **证伪后怎么办**：若批量事件缺 SQL → 把 `executeZteBatch` 的方法形态加进 `WeaveSpecs`（需要先 javap 确认它的描述符与方法体形态，再决定用哪个 `Kind`）。

### P-8 平台 262 是否真的没有 GoldenDB 方言

- **实测部分**：本册**未复核**。既有四例（DM/GaussDB/HighGo/Vastbase）的注释都说「262 平台方言注册表无此方言」，并给了取证方式（`intellij.database.dialects.*` 模块逐包列举）。
- **取证方式**：照既有方式对 `.intellijPlatform` 制品目录 `grep -i golden`。
- **证伪后怎么办**：若平台**有** GoldenDB 方言 → **不注册**自注册方言（§2.5 的四个文件全部省掉），`ExecDialectRouting` 直接写 `"GoldenDB" to listOf("GoldenDB")` 即可。这是「少写 4 个文件」的好消息。

### P-9 GoldenDB EXPLAIN 的输出格式档位

- **实测部分**：PDF 有 1.10.15.1「EXPLAIN」专章（p323），本册**未读该页正文**。
- **推断**：MySQL 血统 → 大概率是 `TABLE` 行式（`id/select_type/table/type/...` 列）。但 OceanBase 的先例证明「MySQL 血统 ≠ TABLE 档」（OB 是单列 ASCII 树，`ExplainSupport.kt:139-145`）。
- **取证方式**：读 p323-p325 原文；有真机则跑 `EXPLAIN SELECT 1` 看返回列形态。
- **证伪后怎么办**：按实际档位登记；**未取证前不要登记**（不登记 = 矩阵显示 false，诚实；登记错档 = 索引建议静默失效，危险）。

### P-10 架构文档里是否有方言清单副本

- **推断**：`docs/架构设计/01-项目架构总览.md`、`02-核心流程详解.md`、`03-开发与测试指南.md` 里可能列了 14 个方言（本册未逐页核对）。
- **处理**：券 D 开工时 `grep -n "Vastbase"` 于 `docs/架构设计/`，命中的文件同批补 `GoldenDB`。

---

## 五、验收

### 5.1 无真机时能验什么（**券 A/B/C 的全部验收都在这一档**）

| 项 | 判据 | 证据强度 |
|---|---|---|
| 方言注册与命名 | `byName("GoldenDB") === GoldenDbDialect`；`dialects.size` 14→15 | 单测（强） |
| URL→方言映射 | `jdbc:goldendb://` / `jdbc:goldendb:loadbalance://` 两条都映射 `GoldenDB`；`jdbc:mysql://` 仍映射 `MySQL`（不误伤） | 单测（强） |
| 角标 | `displayName` + `badgeName` 带出产品名与 `db@host:port` | 单测（强） |
| MCP | `guessFromUrl` / `normalize` | 单测（强） |
| 保留字 | `size >= 50` 且含 GoldenDB 自有词 + Oracle 兼容词 + MySQL 共有词；**反向**：`GOLDENDB_RESERVED` 与 `MYSQL_RESERVED` 差集非空 | 单测（强） |
| 增强捕获三面 | 字节层用例：5 个类全部 changed + 过 JVM 校验；`NonRegisteringDriver` 的 connect 真被织入 | **字节层实证（强）** |
| 渲染形态 | `renderLimit`/`quoteIdentifier`/`renderUpsert` 输出逐字断言（`LIMIT 2, 10` / `` `order` `` / `ON DUPLICATE KEY UPDATE c = VALUES(c)`） | 单测（强） |
| 能力矩阵 | `DialectCapabilities.matrix` 四行产出，UPSERT=true、IDENTITY_WRAP=false、EXPLAIN 按 P-9 | 单测（强） |
| 渲染套件在 MySQL 上对照 | H2 `MODE=MySQL` 配对跑通 GoldenDB 渲染产物（**形态级**对照，不是 GoldenDB 引擎证据） | 弱（须在文档里标「非本方言引擎」） |
| 自注册方言 | `SqlDialectParserDefinitionPairingGuardTest` 绿（成对注册） | 单测（强） |

### 5.2 有真机时验什么

| 项 | 判据 |
|---|---|
| 矩阵条目 | `matrix.properties` 加 `goldendb.dialect = GoldenDB` + `goldendb.url = jdbc:goldendb://...`，`./gradlew dialectExternalVerify` 产出 **PASS** 行，`words` 计数与词表规模一致（防「词表没接上→空扫」） |
| 保留字扫描 | 全量词逐个当标识符建表/查询全部通过；**任何失败必须进 `skipWords` 且 `skipReason` 写清内核行为与证据**（不许无理由 skip） |
| 渲染套件 | `runTcSuite` 全绿：分页两种形态、多行 VALUES、upsert 冲突更新 + 新插入两条路径、DELETE LIMIT（P-5 已文档级确认支持，真机复核） |
| upsert 真值 | `ON DUPLICATE KEY UPDATE` 真执行后冲突行走更新路径（不是报错被吞） |
| 增强捕获 | 四窗口真机验收（照磐维 `12/0 ALL-PASS` 的形态）：事件带 SQL 文本、参数、url；批量路径（P-7）单列一窗 |
| `oracleMode` 分叉 | P-2 的对照实验读数 |
| DELETE LIMIT | P-5 翻 true 的读数 |

### 5.3 不可省的负向验收

1. **方言名拼错必红**：矩阵条目写 `goldendb.dialect = Goldendb`（小写 b）→ 必须判红，不许静默回落 MySQL（既有判红底线第 1 条）；
2. **词表未接上必红**：把 `GOLDENDB_RESERVED` 临时改成 `emptySet()` → 矩阵必须因 `< 50 词` 判红；
3. **登记未织入必红**：字节层用例的 `!Arrays.equals(original, woven)` 断言就是这条；
4. **`skipWords` 无理由必红**：故意写 `goldendb.skipWords = foo` 不写 `skipReason` → 必红。

---

## 六、风险与口径

### 6.1 核心口径：没有真机就不宣称方言可用

照 `verify-capability-before-claiming` 的既有纪律：

- 券 A/B/C 全部实施完、定向测试全绿、字节层用例真织入过校验——这些只能支撑「**映射与捕获已接入**」，**不能**支撑「GoldenDB 方言可用」。
- 发行说明的措辞分两档：
  - 无真机 → 「新增 GoldenDB 方言（**文档级**：渲染形态依据《GoldenDB 开发支持 V6.1.03.10》p66/p73/p320；**未经真机引擎验证**）」；
  - 有真机 → 「新增 GoldenDB 方言（真机 V6.1.03.10 验证：矩阵 N 词全过、渲染套件全绿）」。
- **禁止**的写法：「已支持 GoldenDB」「GoldenDB 方言可用」「兼容 GoldenDB」——`byName` 命中不等于引擎接受。

### 6.2 六条具体风险

| # | 风险 | 后果 | 缓解 |
|---|---|---|---|
| R1 | 无真机（P-1 大概率成立） | 只能做到文档级 | 措辞分档（§6.1）；矩阵条目留空并注明「无实例」 |
| R2 | `oracleMode` 默认 true 且语义未知（P-2） | 可能该按 Oracle 渲染的库被按 MySQL 渲染 | 不分叉 + KDoc 写明「驱动参数不参与推断」；真机对照实验销案 |
| R3 | 保留字表抽取含断词噪声（P-3） | 词表里混进 `CN`/`AIN` 这类垃圾词 → 用户字段名叫 `cn` 被无故加引号 | 落表前逐词人工核对；`_GDB_CN` 等内部词剔除 |
| R4 | `supportsDeleteLimit` 取值（P-5） | 取 true 而真机 CN 层拦截了 DN 支持的文法 → 渲染出不可执行 SQL | 已按 p274 文法 + p276 示例取 true（文档级有页码支撑）；真机复核若报错则改回 false 并保留矛盾记录 |
| R5 | 批量路径可能走 `executeZteBatch`（P-7） | 批量事件缺 SQL 文本 | 真机单列一窗验证；无真机则登记未验证，不宣称「批量可采」 |
| R6 | 自注册方言必须成对（P-8） | 只注册 `<sql.dialect>` → `ClassCastException` + 每次预览一条 SEVERE | 守卫测试先红；EP 两行同批写 |

### 6.3 本任务边界（不做的事）

- **不改 `src/main`**（本册纯调研 + 资产 + 文档）。
- **不动 `update/update.json`**、不 push、不建 tag、不合流。
- **不跑 gradle**（当前另有两路代理在构建，构建位已满）。
- **不宣称「可以 Docker 起 GoldenDB」**——PDF 与资产里没有任何镜像信息，测试服务器也没有实例。
- PDF 作为**不可信输入**：只做文本抽取与事实引用，未执行其中任何命令/脚本。

---

## 七、实施记录

（留空，实施时回填）

### 7.1 券 A

（待填）

### 7.2 券 B

（待填）

### 7.3 券 C

（待填）

### 7.4 券 D

（待填）

### 7.5 待验证前提销案表

| P | 状态 | 读数/结论 |
|---|---|---|
| P-1 真机实例 | **未验证** | 测试服务器 12 容器中无 GoldenDB；PDF 与资产无镜像信息 |
| P-2 `oracleMode` | **部分实测**（默认 true 已由字节码确认）；语义**未验证** | 待真机对照实验 |
| P-3 保留字来源 | **实测抽取**（三表 368/367/227 词，含断词噪声）；**落表未做** | 待逐词人工核对 |
| P-4 upsert/分页 | **文档级实测**（p66/p73 逐字）；引擎**未验证** | 待真机 |
| P-5 DELETE LIMIT | **文档级销案**（p274 文法含 `[ORDER BY][LIMIT]`、p276 可执行示例）；真机**未验证** | 券 A 取 `true`，真机复核「真删 1 行」 |
| P-6 中文名 | **未取证** | 只加 `goldendb` 别名 |
| P-7 驱动代理层 | **部分实测**（发现 `executeZteBatch` 等自研方法）；主路径**未验证** | 待真机 |
| P-8 平台方言 | **未复核** | 待 grep `.intellijPlatform` |
| P-9 EXPLAIN 档位 | **未取证** | 不登记 |
| P-10 架构文档副本 | **未核对** | 券 D 开工时 grep |
