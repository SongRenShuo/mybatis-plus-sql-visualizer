---
name: project-p05-dialect-dbx-envs
description: P-立项-05 方言真机验证已完成（2026-08-29，DBX MCP 通道七家族零缺陷）；DBX 测试环境清单 + 「渲染导出→MCP 执行」工作流与陷阱
metadata:
  node_type: memory
  type: project
  originSessionId: sess_9d1a5aef-6717-4fc3-af09-f0205bfbd0a5
---

P-立项-05（方言输出真实验证，分支 `dialect-verification`，工作树 `mybatis-plus-sql-visualizer-dialect-verification`）Tier 2 真机执行已于 2026-08-29 **完成**：MySQL 8.4.6/5.7.42、PostgreSQL 16.14/14.23/17.4、Oracle 26ai Free 23.26.2（+19c/21c/18c 抽查、11g 实证 FETCH 需 12c+）、DM8、KingbaseES **V9R1C10** 七家族 TC 套件 + 保留字探针零缺陷——结果在报告第十节（提交 2a4a106/e09c668）。

**DBX 测试环境（【DBX 开发测试连接】分组，dbx-test-host，库 dbx_test）**：MySQL 8.4.6 :20084 / 5.7.42 :20057（连接名写 5.7.44）、PG 16.14 :20033 / 14.23 :20034 / 17.4 :20035、Oracle 26ai :20095 / 21c :20103 / 19c :20104 / 18c :20102 / 11g :20101、DM8 :20096、KingbaseES :20097（**连接名叫 "V8" 实为 V9R1C10**，与 Tier 3 核对的 V9 手册同源）、openGauss :20105（PG 家族附加）。MariaDB :20081 与 CockroachDB :20091 宕机；分组内**无 SQL Server**——这两项真机验收缺环境，其余代码侧无遗留。名字陷阱：分组里的「Turso / libSQL Server :20112」（DBX 类型 `turso`）**不是微软 SQL Server**——"Server" 指 libSQL（SQLite 开源分支）的服务端部署形态，方言属 SQLite 家族，既不能顶替 SqlServerDialect（T-SQL/`[col]`/MSSQL）的真机验收，对 SQLite 方言也无增量价值（Tier 1 已用 sqlite-jdbc 原生引擎 100% 真实验证过）。

**Why**: 本机无 Docker、DBX MCP 不暴露凭据，`-Dmpvs.dialect.*` 直连方案用不上；「gradle 渲染导出 → DBX MCP 执行」是免凭据且不违反「SQL 全部出自插件渲染链」原则的真机通道。

**How to apply**（复用该通道做任何真机 SQL 验证）:
1. 渲染侧：`./gradlew test --tests '*DialectRenderDumpTest*' -Dmpvs.dump.dir=<abs>` 导出 9 方言 TC 套件 JSON（build.gradle.kts 已加 `mpvs.*`→测试 JVM 的 systemProperty 透传，顺带修了外部实例入口文档属性到不了测试 JVM 的老问题）。
2. 执行侧：`dbx_open_session` 固定连接，夹具全部用**临时表**（DBX 拦持久 DDL 报 SQL_BLOCKED，但 CREATE TEMPORARY / GLOBAL TEMPORARY 放行；Oracle/DM 用 `GLOBAL TEMPORARY ... ON COMMIT PRESERVE ROWS`——autocommit 下默认 DELETE ROWS 模式逐句丢数据）；会话结束临时表自动消亡，零残留。
3. 陷阱：带 session_id 须同时带 connection_id（session 库须与连接默认库一致）；PG 17 默认库 dbx_test 不存在——不带 session、改传 `database=postgres` 可连，且连接池粘性（临时表跨调用存活、并行调用安全）；`?` 占位手工回填字面量（对齐面板「参数回填后执行」）。
4. 大 SQL（保留字全列探针：262/254/117/109/84 列建表 + 全列 SELECT/UPDATE）用 python 从 JSON 生成文件再粘贴，勿手抄长值列（曾因 VALUES 少一个 1 报 1136）；逐词 INSERT/UPSERT 路径由 TC 套件与 Tier 1/3 覆盖即可。
5. 探活技巧：Git Bash `timeout 4 bash -c "echo > /dev/tcp/host/port"` 区分「服务宕机」（CLOSED）与「DBX 驱动缺失」（端口 OPEN 但报 driver is not installed）；oracle/dameng/kingbase 驱动 2026-08-29 已装。gradle 直连路径（`-Dmpvs.dialect.*` + 驱动 jar 入 testRuntime classpath）保留备用。
