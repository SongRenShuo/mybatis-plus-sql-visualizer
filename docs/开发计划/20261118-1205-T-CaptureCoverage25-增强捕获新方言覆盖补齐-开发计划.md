# T-CaptureCoverage25：增强捕获对新方言的覆盖补齐 — 开发计划

- 立项：2026-11-18 12:05
- 任务源：用户 2026-11-18「增强捕获功能，这些方言都需要测试没问题的」＋「环境阻塞的解决掉」。背景：route 方言 15→25，但 `DialectCaptureCoverageTest` 的方言对账表仍写死 14 项、自检下限 `>= 14`，新方言全在护栏之外——正是该守卫自己防的「方言可选、捕获静默」缺口。
- 性质：护栏补齐 + 驱动接线 + 真机取证。只动 route 分支。

## 一、现状取证

| 项 | 现状 | 出处 |
|---|---|---|
| 方言护栏 | `DIALECT_DRIVER_FAMILIES` 14 项，自检 `size() >= 14` | `DialectCaptureCoverageTest:46-70` |
| 新方言 10 个 | TiDB / PolarDB-X / PolarDB / GBase 8a / GBase 8c / Oscar / UXDB / AntDB / XuGu / YashanDB | `Dialect.kt`（25 个 `override val name`） |
| 走既有驱动（已覆盖） | TiDB、PolarDB-X、PolarDB（MySQL/Oracle 驱动）、GBase 8c、UXDB、AntDB（PG/openGauss 驱动） | 驱动族前缀命中既有 SPECS |
| 自有驱动且可取证 | **XuGu**（`com.xugu.cloudjdbc.Driver`，Central 有 12.3.9）、**YashanDB**（`com.yashandb.jdbc.Driver`，Central 1.10.7） | jar 实测 |
| 自有驱动但无公开坐标 | **GBase 8a**（官方 gbase-connector 无 Central 条目；本仓真机腿用 MariaDB Connector/J）、**Oscar**（`com.oscar.` 无 Central 条目） | Maven metadata 404 |

## 二、方案

1. 护栏从 14 扩到 **25 方言**，自检下限同步提到 25（否则加表不加断言等于空绿）。
2. **XuGu / YashanDB 走真接线**：驱动进 agent 测试 classpath → `DriverRegistry` 连接面 + `WeaveSpecs` 语句面/prepare 面登记 → 字节层织入 + JVM 链接期校验（复用 `StatementWeaverVendorCoverageTest` 的既有机制）。
   - XuGu 类名实测：`com.xugu.cloudjdbc.{Statement, PreparedStatement, CallableStatement, Connection}`。
   - YashanDB 类名实测：`YasStatement`/`YasConnection` 是**接口**，实现在 `StatementImpl` / `PreparedStatementImpl` / `YasCallableStatement`（javap 实证）。
3. **GBase 8a / Oscar** 无公开驱动：在 `VENDOR_NOT_COVERED` 显式认领并写清理由与替代路径（8a 的真机腿走 MariaDB Connector/J，捕获面同样命中 `org.mariadb.`），不伪造覆盖。
4. 真机捕获取证：对**有引擎**的新方言（TiDB / GBase 8a / XuGu / GBase 8c）跑 agent + 真实 JDBC，断言事件到达。

## 三、验收

| 项 | 判据 |
|---|---|
| 护栏 | `DialectCaptureCoverageTest` 25 方言两面全覆盖；负样本（半覆盖/整族缺失）仍判红 |
| 字节层 | XuGu / YashanDB 真实 jar 织入 `changed=true` 且子 loader 定义通过（链接期校验） |
| 反向 | 临时删 XuGu 语句面条目 → 护栏必须红（证明新方言真进护栏） |
| agent 全测 | `:sql-visualizer-agent:test` 绿 |
| 真机 | 4 条腿各收到 ≥1 条 SQL 事件，方言归属正确 |

## 四、实施记录

2026-11-18 12:05~12:20，全部在 `t-branch-merge-route` 工作树完成。

| 项 | 结果 |
|---|---|
| 护栏扩展 | `DIALECT_DRIVER_FAMILIES` 14 → **25**（补 GoldenDB + 10 个新方言），自检下限同步 14 → 25；顺带发现 **GoldenDB 此前也不在护栏里**（T-GoldenDbDialect 加表时漏登） |
| 走既有驱动 6 族 | TiDB / PolarDB-X / PolarDB（`com.mysql.`）、GBase 8c（`org.postgresql.`+`org.opengauss.`）、UXDB / AntDB（`org.postgresql.`）——前缀命中既有 SPECS，无需新增登记 |
| 新接线 2 族 | **XuGu**：`com.xugu.cloudjdbc.{Statement, PreparedStatement}` + `Connection`；**YashanDB**：`StatementImpl` / `PreparedStatementImpl` + `ConnectionImpl`（`YasStatement`/`YasConnection` 是接口，照接口名登记会零命中） |
| 驱动登记 | `DriverRegistry` 增 `com.xugu.cloudjdbc.Driver` / `com.yashandb.jdbc.Driver`（services 文件实证） |
| 测试 classpath | agent 测试侧增 `com.xugudb:xugu-jdbc:12.3.9` / `com.yashandb:yashandb-jdbc:1.10.7`；`verification-metadata.xml` 按产物逐字节 sha256 登记（route 的依赖校验严格化要求） |
| 字节层 | XuGu 三面、YashanDB 三面真实 jar 织入 `changed=true` 并过隔离 loader 链接期校验 |
| 两处形态修正（真机字节实证） | ① XuGu `CallableStatement` **无自有 execute 族**（继承 PreparedStatement）→ 不单列，否则 zero-hit；② YashanDB `StatementImpl.addBatch(String)` 出口发 `chop_frame`，全套形态触发 `VerifyError: Bad local variable type` → 改用**不含 ADD_BATCH/CLEAR_BATCH** 的形态（与达梦同族限制，代价是批文本不累积，如实登记） |
| 两族显式认领 | GBase 8a（`com.gbase.`）/ Oscar（`com.oscar.`）官方驱动在 Central 无 maven-metadata（404），无法做字节层取证 → 写入 `VENDOR_NOT_COVERED` 并注明替代路径（8a 真机腿走 MariaDB Connector/J） |
| 反向验证 | 整族摘掉 XuGu 前缀 → 护栏红（`全方言三面对账` 失败）；还原后转绿。**第一次探针造错**：只把类名改成假名而保留 `com.xugu.` 前缀，护栏按前缀判、仍绿——记录在案，防下次同类误判 |
| agent 全量 | **51 类 / 301 例 / 0 失败 / 0 错误** |

未做（如实登记）：新方言的**真机端到端捕获**（agent attach + 真实 JDBC 应用 → 事件到达）尚未跑；
现有 E2E 装置 `dev-support/e2e-fake-ide` 绑定 Spring demo + MySQL，新引擎需另起可换驱动的装置。

