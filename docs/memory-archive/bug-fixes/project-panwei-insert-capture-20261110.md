---
name: project-panwei-insert-capture-20261110
description: T-PanweiInsertCapture（20261110）——四条静默丢弃路径可见化 + 两形态补齐 + 方言横审五处登记，已随 v2026.11.11-beta.1 发行（tag 308f5f96 / Gitee 1293976 / GitHub 440141088）；香港磐维 A 模式复现并定因 NestGate 深度泄漏（-Dsqlvis.agent.nestGate=false 即恢复），自愈未进本版；观测轮已定位泄漏帧 PgStatement.execute；demo 磐维连接已迁香港 + DBX 连接 e84e9ab4 已建
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b3ebe962-4ada-4226-8a82-7e96f8cc196b
---

# T-PanweiInsertCapture（20261110，已实施未推；根因已复现）

计划册 `docs/开发计划/20261110-1428-T-PanweiInsertCapture-磐维库SQL日志只捕获SELECT而INSERT缺失-开发计划.md`
（§十一 有完整实施记录、四轮复现实验与读数）。

## 已落地（本地 master，未 push）

`e2b0b581` 代码 / `151e4d5b` 文档 / `7537a4cd` Oracle 负对照 / `99b816d5` 代理层加测 / `918871e9` 香港复现+架构文档同步。

- **F1** 让位丢弃的可解析 SQL 行计数（`SqlCaptureGate.suppressedSqlLines`，按语句计一次）+ 状态栏段；
- **F2** agent 出口组装失败（`assembleFailures`）与 `NestGate` 抑制（`nestSuppressed`）计数，随心跳；
- **F3** 登记盲区按 PS 实现类聚合点名（心跳 `blind`，有界 8，与 `unresolved` 配对）；
- **F4/F5** 解析器补 MyBatis `==>  Executing:`（仅 mixedPrefix 宽容模式）与 iBatis `{stmt-N} Statement:`；
- **F6** iBatis 批处理具名提示（仅「已识别 iBatis 形态 + 增强未开启」）；
- **F7** `LogParseDiag.unrecognizedSqlLine`（噪声档只查 `Executing:`）。

读数：根项目定向 112 例 0 红、agent 子项目全量绿；`sacvention` 反向验证成对（摘 F4 恰 4 红 / 摘 F5 恰 2 红）；`convention` 整包（含 BundlePlaceholderArity / GuardSelfTestCoverage）绿。
**让位语义未改**（F8 明确否决，理由见 [[pitfall-console-bridge-fallback-covers-only-dead-agent]]）。

## 方言横审五处登记补齐（同提交，用户令「其他方言有同类问题一起修」）

按真实 jar 逐个 javap 核过 17 家方言，补：pg 家族 `PgCallableStatement` ×7（函数模式 `executeUpdate` 走
`executeWithFlags` 分支漏采）、MySQL 5.1 `CallableStatement`（jar 不可得只做谓词层）、华为两族
`MPreparedStatement`（兼容模式 `addBatch(String)` 覆父类不回调）、海量/社区 openGauss `ReadWriteSplittingPg*`
包装语句 + 其连接类（**读写分离模式整条连接的 PS 进不了登记表**）、SQLite 四参 prepare 实现类
`org.sqlite.jdbc4.JDBC4Connection`、华为两族 connect 面 Driver。
**纪律**：新类必须同时进 `StatementWeaverVendorCoverageTest` 的 `STATEMENT_FACE_CLASSES`/`PREPARE_FACE_CLASSES`，
否则「名单漂移」守卫红；缺 jar 的进 `BYTE_VERIFIABLE_EXCLUDES` 并写明原因。

## 复现与定因（20261110 收口）

香港机磐维 A 模式实例（`panweidb-hk-ora`，155.103.159.54:26201，`datcompatibility=A`，
V3.0-S3.4.0_B01，见 [[reference-panwei-instance-and-image]]）三步取证：

1. **复现**：plain `Statement`（`PgStatement`）正常，所有 `PreparedStatement` 语句（INSERT/SELECT）整类无事件；
   宿主 stderr 点名 `psClass=org.panweidb.jdbc.OracleCmptPreparedStatement` 登记盲区。
2. **排登记**：直连探针反射查表 → prepare 后**登记表里就有这条 PS**（`sql=`/`url=` 都在）⇒ 不是 prepare 面。
3. **定因**：同载荷加 `-Dsqlvis.agent.nestGate=false` → INSERT ×2 + SELECT **全部出现**。

⇒ **根因＝`NestGate` 深度泄漏把该线程后续语句全抑制**（机制、判据与未实施的自愈方案见
[[pitfall-nestgate-depth-leak-suppresses-thread]]）。真 Oracle 负对照与 Hikari+iBatis 代理层加测都**未复现**，
证明驱动方法面、连接池代理层、方言都不是原因。

## 当前状态（20261110 收口；20261111 更新见第 0 条）

0. **20261111：NestGate 根治已落并转正**——`0fc3836c`（T-NestGateFix，timed 面 catch-all 异常出口）+ `8e85d397`（T-NestGateObserve，「门禁陈旧」读数）随 beta.2 发行，转正进 `2026.11.11` 正式版（[[project-release-20261111-formal]]）；`-Dsqlvis.agent.nestGate=false` 不再是必需。详见 [[pitfall-nestgate-depth-leak-suppresses-thread]]。
1. **内测版 `2026.11.11-beta.1` 已发行**（Gitee 抢先体验通道）：
   tag `2026.11.11-beta.1` @ `308f5f96`（双仓 ls-remote 一致）；Gitee 发行版 **1293976**（prerelease，
   `--verify-only --prerelease` [3/3] Verified）；GitHub Release **440141088**（prerelease，走
   `mirror-releases-to-github.py --tag … --fresh`）；附件 7,879,011 B、sha256 `49f91ac4ca8ee194…` 线上本地一致。
   发版提交 `308f5f96`（六处同步点）+ `9d32d936`（变更记录落位回填），均已推双仓。
   `inspect-plugin-zip.py` 首轮 FAIL（changeNotes 第 1 条全角冒号）→ 改逗号重建 → PASS；
   `plugin.xml <description>` 与 `marketplace-custom-page.html` 无新能力类别，按规范未动；`update/update.json` 未动。
2. **NestGate 自愈未进本版**（发行说明「已知边界」写明 + 现场手段 `-Dsqlvis.agent.nestGate=false`）。
   自愈第一版（帧名锚点）被 10 例测试否掉并整案回退；**②观测轮已完成**（分支 `t-nestgate-observe`，未合），
   把泄漏帧定位到 `org.panweidb.jdbc.PgStatement.execute`（详见 [[pitfall-nestgate-depth-leak-suppresses-thread]]）。
   剩下的分叉是「多织一次入口 vs 少一次出口」，定案后再做 ① 自愈。
3. 香港实例 **DBX 连接已建**：id `e84e9ab4-3949-4001-b5de-21b00540fcc4`，`opengauss` 类型，
   实测 `select dummy from dual → X`、`current_user/current_database()` 正常，**MCP 未被拦**。
   ⚠️`dbx_add_connection` 无分组参数，这条连接 Group Path 为空，需用户手动拖进 `Song` 分组。
4. demo 仓 `mybatis-plus-demo/src/main/resources/application-panwei.yml` 已指向香港
   `jdbc:panweidb://155.103.159.54:26201/panweidb`（旧地址/旧口令注释保留，同目录
   `application-panwei.yml.bak-20261110-hk` 备份，CRLF/UTF-8 未破）；**B 模式无对应实例**只注释未臆造；
   `application-panwei-gaussdb.yml` 指向的是 GaussDB 内核 503 实例（25882），未动。
5. 真机 runIde 验收与全量门禁（交收口代理）。
6. master 已推双仓（走 `push-all.ps1 -Branch master`，GitHub 段代理探测不通自动直连）。

**用户口径**：说「Oracle 模式」指的是**磐维的 Oracle 兼容模式（A 档）**，不是真 Oracle 数据库——真 Oracle
只能当负对照用。

相关：[[project-pending-plans]]、[[reference-panwei-instance-and-image]]、[[reference-agent-minimal-repro-harness]]。
