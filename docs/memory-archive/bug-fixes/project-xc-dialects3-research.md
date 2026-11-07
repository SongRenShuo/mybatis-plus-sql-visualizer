---
name: project-xc-dialects3-research
description: T-XcDialects3 三方言支持已在 dev 全部实施收口并推双仓（20261030：门禁 8492/0/0/9+verifyGate 3/3+三族真机渲染套件 153/201/118 词全 PASS）——真正拍板点＝dev 14 方言态何时合回 master；仍欠 runIde 走查与增强捕获 attach 端到端
metadata:
  node_type: memory
  type: project
  originSessionId: session_75f77c740f8e46a14f3685d1bdad249e
---

# T-XcDialects3 三方言支持（已实施收口于 dev，待拍板合回 master）

**🆕终态（20261030 合并实施册全分支排查实测）**：T-XcDialects3 **已在 dev 全部实施完毕**——dev 独有 37 笔＝T-GaussDbDialect 八券 + T-XcDialects3 券 01~08 全落码（保留字三集合 e8399004 瀚高153/海量201/金仓补enckey至118、URL 与方言链接线 b211d34d、平台 SQL 方言注册 d8582b12、增强捕获信创三族语句面+prepare 面 87a0f6c0 与帧跳过前缀 377c56ed、计数面 12→14 同步 9ee325bf、海量真机两处定案 d9f9262e＝upsert 切 PG 形态/DELETE LIMIT 翻 true）；门禁 **testShards 8492/0/0/9 + verifyGate 3/3 Compatible** 已回填 dev tip `b0a67d4f`（含 §7.6 补登分片排除 b67f1474）。变更记录与索引状态均已转已实施。**唯一待拍板＝dev（14 方言态）何时合回 master**（红线：dev 并 master 须用户允许，见 [[feedback-dev-merge-needs-approval]]）；合并册 `docs/开发计划/20261030-0816` 已把此项列为「不纳入本册、点名权在用户」。本文件其余内容（三条不可族推定、施工面、守卫坑）作为依据继续有效。

## 🆕20261030 真机补档（五实例 + attach 冒烟 + 一个真缺陷）

**服务器五实例全部起来并跑完探针**（台账见 [[reference-test-server-port-and-dbx-policy]]，凭据 `.local/test-databases.md`）：瀚高 25866（V6.0.1/PG12.3，九探针闭环 P-1~P-4）、海量 A 25432 + **B(MySQL) 25433**（B 模式实证：反引号可用、`EXPLAIN 表名`=DESC、`lower_case_table_names=0` **不折叠且大小写敏感**、`DELETE…LIMIT` 同样真删）、**OceanBase CE 25881**（OB 4.3.5.6，分页区分性/两 upsert 形态/DELETE LIMIT/事务/自增全实测）、**SQL Server 21433**（`SET STATISTICS XML` 多结果集三段式实证 + `SHOWPLAN_XML` 批内混写报 Msg 1067 ⇒ 插件选型被真机证实；坑：无 FROM 的 SELECT 不产生计划、末尾 OFF 是必需项而非防御、DML 首结果 updateCount）。

**渲染套件真执行（7.6b）**：三族驱动进主项目测试 classpath（toml+build.gradle.kts，仅测试面），`DialectDockerVerificationTest` 外部实例模式让**插件渲染管线**对每保留字逐词渲染并真跑——瀚高 153 词 / 海量 201 词 / 金仓 118 词三实例全 PASS（提交 `b29ca61e`；`verifyGate` 因依赖变更复跑 3/3 Compatible）。

**attach 冒烟（真实 JVM，见 [[reference-agent-attach-smoke-recipe]]）**：三族 premain 各自 `weavable=4 woven=4 failed=0`、三族同时 `matched=12 wove=12`；**强转红线三族全部通过未击穿**（`KbConnection`/`cn.com.vastbase.jdbc.PgConnection`/`com.highgo.jdbc.jdbc.PgConnection` 三形态下均可强转且类型未被换成代理）。⇒ 「attach 端到端未闭环」这一条收窄为「**事件回流 IDE 面板**未验」，织入与宿主安全已闭环。

**🆕第六实例：GaussDB 商用内核（20261030 晚）**：第三方镜像 `apecloud/gaussdb` 实测**是华为商用内核** GaussDB Kernel 503.1.0（`version()` 原文 `gaussdb (GaussDB Kernel 503.1.0 build f53b4ed8) compiled at 2023-03-27`），已部署 `25882`。三条读数正好验了 `GaussDbDialect` 的关键假设：R 类保留字 **97**、`ON DUPLICATE KEY UPDATE` **通过**（计划含 `Conflict Resolution: UPDATE`）、`ON CONFLICT` **语法错**、`sql_compatibility=A`（**老命名 A/B/C/PG**，`M`/`D` 被拒）。**用户令「名称需要区分防误导」** ⇒ 容器名 `xc3-gaussdb-kernel503` + DBX 连接名带「2023老命名，非生产5.x」+ `.local` 警告块；**任何据此实例的结论必须标注 503.1.0 老版本，不得写「GaussDB 已验证」**。详见 [[reference-gaussdb-commercial-not-installable]]。

**后继任务 T-CaptureCoverage（O-154）**已把增强捕获的 MariaDB/SQLite/OceanBase 三族补齐（原先 11 全链路 / 2 只有角标 / 1 三面全空），字节层 + 真机冒烟全过，见 [[project-pending-plans]]。

**一个真缺陷（已修）**：OceanBase 的 `ExplainSupport` 档位原登记 **TABLE**（依官方文档「缺省 TRADITIONAL 表格行」），真机实测返回**单列 `Query Plan` ASCII 树** ⇒ `ExplainIndexAdvisor` 走 MySQL 列名分支（找 `type/key/rows/Extra`）→ **索引建议永不触发**，与 T-ECH-03 同型假绿。修法＝档位改 **TEXT**（模板不动），提交 `58b8b5f3`；连带 `ExplainPanelChannel.STRUCTURED_FORMATS` 只含 TABLE/JSON ⇒ OB 转 TEXT 后走直送控制台通道（与 DM8/H2/SQLite/Oracle 同档）。KDoc 三处按真机订正（SQL Server 升档 / Vastbase B 折叠 / OB 陈旧表述）见 `44c50cd2`。详见 [[pitfall-explain-format-tier-vs-reality]]。

用户令「插件需要支持 翰高、海量、人大金仓」。产出：`docs/调研报告/20261029-2024-瀚高海量与人大金仓三方言支持-调研报告.md`（436 行）＋ `docs/开发计划/20261029-2035-T-XcDialects3-...-开发计划.md`（175 行，占号 O-153/D199/V224，P-1~P-15 前提、D1~D7 决策点）。**未写一行生产代码**。

**落位（20261029 用户令「你可以先提交到主分支」）**：`67c99df9` 提交报告+计划+`docs/MEMORY.md` 与 `docs/索引/开发计划.md` 各一行登记；`2a8318a6` 在计划头部补「已落 master、实施时合并即得、**不要手工复制、不要重复追加索引行**」。⇒ 实施侧只需 `git merge master` 即可把文档带到 dev，原闲时任务描述里「文件还在 master 工作树且未提交、去手工复制」那段已作废。

**dev 合并预演已做**：`git merge-tree --write-tree dev master` 返回树哈希、无冲突文件列表 ⇒ 「先并 master 到 dev」这一步不会卡冲突。当时 dev `9df5d55d` 落后 master 7 笔（含 v2026.10.30 发行落位与 `fix(preview): 方言冲突时保留上游决策的候选证据`）。

**环境初始化并行在跑（20261029）**：两个后台子代理在 [[reference-dbx-ssh-test-server]]（`不大-北京-16G`，103.36.220.245）分别装瀚高（25866）与海量（25432），只为把「瀚高/海量零真机」这个最大空白填上；部署完成后计划里的 P-1~P-7 那几条待验证前提可升级。

## 基线（20261029 用户告知「一会要在 dev 分支实施」后整体改写过一遍）

**必须按 dev `9df5d55d` 写死，不是 master**。dev 已并入 T-GaussDbDialect ⇒ 注册表 **12 方言**（末尾 `GaussDbDialect`），本任务是 **12 → 14**。连带改了：`DialectTest` 注册表 12 项 / LOWER 族 `[PostgreSQL, KingBase, openGauss, GaussDB]`、`ReplayScriptExportTest` 事务黄金表 12 项、`DialectExplainTest` 9 条 Entry、`plugin.xml:39/:55` 与 README、`docs/架构设计/01:14` 全是「12」。master 侧这些数字都差一位、行号前移约 100 行。

## 三条结论（与直觉相反的部分）

1. **人大金仓早已在册**（`object KingBaseDialect`，`sql/Dialect.kt:754-783`），不是「从零支持」。DBX 真机（`KingbaseES V009R001C010`，`database_mode=pg`）六项核心形态全对：LOWER 折叠 493/493、PG 形态 upsert 计划含 `Conflict Resolution: UPDATE`、`EXPLAIN (FORMAT JSON)` 单列 JSON、`DELETE … LIMIT` 报 `syntax error at or near "LIMIT"`（印证 `supportsDeleteLimit=false` 正确）。三条真缺口：①保留字缺 `enckey`（引擎 `pg_get_keywords()` 标 R，`SELECT enckey FROM …` 报语法错误；**`SELECT 1 AS enckey` 实测通过**，子代理报「别名位也拒绝」是错的）；②`WeaveSpecs` 没有任何 `com.kingbase8.jdbc.Kb*` → 有 URL 角标、零 SQL；③PG 协议版驱动 `postgresql-for-kingbase` 走 `jdbc:postgresql:`，会被推断成 PostgreSQL，金仓保留字表失效（marker 必须排在同表 `org.postgresql:postgresql` 之前，该表顺序匹配）。
2. **瀚高与海量是零支持，但形态可整体复用 PG 族**：瀚高 V9 = PostgreSQL 14 内核，海量 G100 = openGauss 6.0 商业版。分页/双引号/LOWER/`ON CONFLICT`/`EXPLAIN (FORMAT JSON)`/`BEGIN` 全有官方文档支撑 ⇒ 新方言对象是「同形不同名 + 不同保留字表」。**dev 的 `GaussDbDialect`（`:957-983`）就是现成模板**（形态复用 + 身份独立 + 每个覆写带证据注释），照它写最省事。
3. **三条不能族推定**：①瀚高附录 150 保留字与 `PG_RESERVED` **双向有差集**（见 [[reference-cn-db-driver-families]]，取并集 151 词）；②Vastbase 通用文法**同时**收 `ON CONFLICT` 与 `ON DUPLICATE KEY UPDATE`，而同内核 openGauss 5.0.0 真机只认后者 → 默认取 MySQL 形态（A 模式已证可用）；③瀚高默认 `initdb --dbmode=oracle`（IvorySQL 血统），`ivorysql.identifier_case_switch=INTERCHANGE` 让带引号名大小写互换、`enable_emptystring_to_null=on` 把 `''` 存成 NULL。

## 施工面（比上次加方言宽 4 处）

历史 T-DialectXcFamily（`06f5539d`）只改 13 个文件，当前实际接入面 20+：新增 `ExecDialectRouting` 候选链、`ProjectDialectEvidence.DRIVER_MARKERS`、`ReplayScriptExport` 事务黄金表、`PluginXmlStructureGuardTest` 方言数对账四处；固定必改面＝`Dialect.kt` 注册表 / `SqlKeywords.kt` 保留字 / `ExplainSupport.kt` / 三条 URL 表（`DialectInference`、`JdbcUrlDialects`、`McpDialectDetector`）/ `SqlVisualizerExplainTool` 报错文案；守卫冻结面 11 处（注册表精确顺序、UPPER/LOWER 族名单、NONE 族名单、identity 白名单、Explain 条目数、事务黄金表、plugin.xml 中英方言数、兄弟文档站四表、DocGolden、GUARD_CLASSES 登记）。

**两处 dev 才发现会卡住增强捕获券的守卫**（`StatementWeaverVendorCoverageTest`）：
- `VENDOR_NOT_COVERED` 现有一条 `"com.kingbase8." → "国产库，本机无 jar 未实证"`，而认领**优先于**覆盖判定 ⇒ 补金仓语句面时必须同时删这条，否则守卫看不出已覆盖。
- `vendorKey()` 默认取两段前缀，`THREE_SEGMENT_VENDORS` 只有 `com.huawei.` ⇒ `cn.com.vastbase.Driver` 的键落成 **`cn.`**，精度不足且将来与任何 `cn.*` 厂商互相假绿；应把 `cn.com.` 加进三段白名单。

## 口径与前置

- **证据档位三族不同**：金仓有真机可写「已验证」；瀚高/海量零真机（DBX 在册无此两族、Docker Hub 只有第三方镜像）只能写「已适配」。三族都不得写「增强捕获真机端到端验证」（attach 一条没跑）。
- **跨仓守卫顺序**：`DialectDocTableGuardTest` 读磁盘上的兄弟文档站、所有工作树共用一份 ⇒ 顺序固定「先 dev 落插件 14 → 再改文档站」；改完后 master 工作树跑该守卫必红，属已知可接受。详见 [[pitfall-dialect-doc-table-guard-cross-repo]]。
- **D2 建议本轮不做兼容模式感知**（瀚高 `--dbmode`、海量 A/B/PG/MSSQL 均实例级不可改），零真机时做设置项＝把猜测固化成产品行为。

**Why**：这三家的「族推定」已经在本仓被打脸三次（openGauss upsert 反族、H2 折叠方向、DM identity 包装），而瀚高/海量连一台实例都没有——照抄 PG 或照抄 openGauss 都会产出语法错误 SQL。

**How to apply**：动这三个库前先读报告 §五/§六/§七与计划 §二（P-1~P-15）；金仓侧可直接用 DBX `KingbaseES V8` 连接复跑探针（清单见 [[reference-dbx-test-databases-inventory]]）。相关：[[reference-cn-db-driver-families]]、[[project-pending-plans]]。
