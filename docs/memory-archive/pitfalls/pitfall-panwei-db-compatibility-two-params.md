---
name: pitfall-panwei-db-compatibility-two-params
description: 磐维有两个同名不同义的参数——DBCOMPATIBILITY 是建库参数（默认 A=Oracle，决定 SQL 语法）而 db_compatibility 是 JDBC 客户端参数（默认 postgresql，只控制结果集列名大小写）；方言推断必须按前者，jdbc:panweidb 恒映射 Oracle
metadata:
  node_type: memory
  type: pitfall
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

**规则**：判定磐维（PanWeiDB）的渲染方言只能依据**建库参数 `DBCOMPATIBILITY`**，不能依据 JDBC URL 里的 `db_compatibility`。

两者事实（《磐维数据库 V2.0.0 开发者指南》表 1-1~1-5 + V2-S3.0.0_Beta《应用程序开发指南》§4.4.5）：

| 参数 | 性质 | 取值 | 影响 |
|---|---|---|---|
| `DBCOMPATIBILITY` | **建库参数**（初始化时定，`CREATE DATABASE` 里没有该选项，单实例不能切档） | Oracle(A，**默认**) / MySQL(B) / TD(C) / SQL Server / PostgreSQL | SQL 语法行为 |
| `db_compatibility` | **JDBC 连接参数**（客户端） | postgresql(**默认**) / oracle / mysql | 只控制 ResultSetMetaData 返回列名的大小写（oracle 档全大写，且 `disableColumnSanitiser=false` 失效） |

**20261106 踩过的反例**：先按 `db_compatibility` 做了「参数感知」映射（oracle→Oracle / postgresql→openGauss / mysql→MySQL）。这是错的——它是客户端列名参数，一个跑在 Oracle 模式的库，用户为拿小写列名设了 `db_compatibility=postgresql`，插件就会把 Oracle 语法按 openGauss 渲染。已撤掉，改回 **`jdbc:panwei` / `jdbc:panweidb` 恒映射 Oracle**（A 是出厂默认），PG/MySQL 模式的库由用户在面板手动改选；`panweiClientDbCompatibilityMustNotChangeDialect` 用例钉住这条不变式（URL 带任何 `db_compatibility` 都必须判 Oracle，反向探针重新加回覆盖分支恰 1 红）。

**Why**：两个参数名只差一个前缀、默认值还相反（建库默认 A=Oracle，客户端默认 postgresql），按名字联想必然推反；且「默认值相反」这一条只有同时读到两份官方指南才会发现。

**How to apply**：写任何「按 URL 参数切方言」的推断前，先确认该参数是服务端语义还是客户端语义（看它改的是语法行为还是结果集形态）；同类风险也适用于其它国产库的 `compatibilityMode` / `sql_mode` 族参数。

**推论（2026-11-07 正式版发行时核对）**：磐维**不是第 15 个方言**——它复用 `OracleDialect`，所以 `Dialect.kt` 里仍是 14 个方言对象，`plugin.xml` 描述与市场页写的「14 种数据库方言」不用改；发行说明里也别写成「新增第 15 种方言」，正确说法是「新增磐维 PanWeiDB 的方言支持（按 Oracle 渲染）」。

相关：[[reference-cn-db-driver-families]]、[[project-ibatis-panwei-20261106]]。