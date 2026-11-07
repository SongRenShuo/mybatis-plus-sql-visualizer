---
name: project-ibatis-panwei-20261106
description: T-IbatisPanwei 战役（20261106 已收口 master 并进 v2026.11.6-beta.2）——iBatis 2.x 参数解析 + 磐维三面登记（V1/V2 双代）+ 方言恒 Oracle；16s 静默结案为 CONFIG 看门狗；真机 GaussDB 与真磐维 A 模式四窗口各 12/0 ALL-PASS
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# T-IbatisPanwei 实施战役（2026-11-06，已收口并发 beta.2）

用户令：子代理后台并行实施计划全部内容、可建分支/工作树、主控合并、**只跑定向省时间**；决策点按推荐项执行（D-1 Oracle / D-2 `{conn-N} Preparing Statement:` 不产条目 / D-3 `SqlSource` 复用 MYBATIS）；同日再放开「**本地并发不受限制**」（见 [[parallel-build-resource-rules]] R29）。

## 分支与结果（全部合入 master；beta.2 发行提交 `38b3debe`）

- 券 A/B（`t-ibatis-parse` → `f26b80e1`）：iBatis 分支 + `Types:` 延迟闭合；方括号守卫 `6d3f6a40`。
- 券 D+E（`t-catchall-diag` → `bba48090`）：未登记驱动类名随心跳 `unreg` 上报、attach 补挂 2 轮、扫描预算只计候选类。
- 券 F（`t-panwei-dialect` → `8306eaea`）：角标 + 方言；中间做过一版 `db_compatibility` 参数感知（`573c76a9`/`a8b2855d`），**后按 V2.0.0 开发者指南订正为恒 Oracle**（`a70806dd`，见 [[pitfall-panwei-db-compatibility-two-params]]）。
- 券 C 登记（`94235f8d` + `7788c68b`）：`org.panweidb.Driver` + `org.panweidb.jdbc.PgStatement/PgPreparedStatement/PgCallableStatement` + `PgConnection`；字节层用例改为**自动遍历 `database-lab/vendor/drivers/panweidb-jdbc-*.jar`**（V1 1.0.0 + V2 2.0.0 同轮 15/0/0/0）。
- 夹具：`database-lab/vendor/`（用户令，见 [[feedback-vendor-fixtures-version-controlled]]），含 V1/V2 jar + 两份官方指南 PDF。

## 权威读数

- 集成树：根项目 173 类 **1907/0/0/0**、agent 5 类 **44/0/0**；反向验证逐条「摘除必红」（A 7 / Types 4 / 方括号 1 / D+E 2+2+1+1 / F 4 / C 3 / 方言订正 1）。
- 方言渲染套件 `dialectExternalVerify`：`panwei_b`（MySQL，262 词）PASS、`gaussdb_kernel503`（197 词）PASS、`panwei_a`（Oracle，109 词）首轮撞 `rowid` → 用户拍板在矩阵加**按实例 `skipWords` + 必填 `skipReason`**（缺理由/词不在词表即判红），复跑 **PASS words=109 skip=1(rowid) 66.4s**；产品保留字表未动（真 Oracle 23 Free 同句可建表，`rowid` 在磐维 A 内核是系统列）。
- 真机（假 IDE 探针，非 GUI）：
  - GaussDB 503.1.0 + V1/V2 驱动：四窗口 **12/0 ALL-PASS**（41.1s / 49.2s / 43.5s 三轮），事件带 SQL、参数 `[[1,"100","BigDecimal"],…]`、`url=jdbc:panweidb://…`；栈命中 `org.panweidb.jdbc.PgPreparedStatement#execute:145`（白名单三面，不是兜底）。
  - **真磐维 3.4.0 A 模式**（`103.36.220.245:26101/panweidb`）：四窗口 **12/0 ALL-PASS 41.1s**（V2）/ 43.5s（V1）。
  - 口径：GaussDB 那轮只能写「GaussDB 503.1.0 + 磐维 V1 驱动」；真磐维实例是第三方重打包镜像（见 [[reference-panwei-instance-and-image]]），对外标「社区重打包镜像」。

## 磐维事实

- V1 1.0.0（844,003 B，class 52）与 **V2 2.0.0（890,254 B，2023-12-28 构建，class 52）都是整包重命名**：`org/panweidb/` 条目 374 / 399、`org/postgresql/` 0、`cn/com/panweidb/` 0，`META-INF/services` 都是 `org.panweidb.Driver`，URL 只认 `jdbc:panweidb:` / `jdbc:dws:iam:`；关键类名与方法形态逐项一致 ⇒ **现有三面登记直接覆盖 V2**。
- 指南里的 `cn.com.panweidb.Driver`（「v 版」另一支制品）与 2.0.0 这个 jar 不是同一个；该形态仍无 jar、未登记（真机出现时靠券 D 点名）。
- 方言：`jdbc:panwei` / `jdbc:panweidb` **恒映射 Oracle**（理由见 [[pitfall-panwei-db-compatibility-two-params]]）。

## 16s 静默已结案

不是采集缺陷，是 agent 的 **T-07 CONFIG 看门狗**（会话建立 15s 内没收到 CONFIG 就主动停采）；假 IDE 脚本没发 CONFIG 才被测到。修复＝自停可见化（心跳 `gate` + 告警行），采集语义未改。详见 [[pitfall-agent-config-watchdog-15s-silence]]，计划 `docs/开发计划/20261106-1336-T-AgentSilentAfter16s-…`。

## 未闭环

- Marketplace 转正与用户自有磐维应用上的复验（角标、未登记类名消失）；V2-v 命名形态的 jar。
- 三处镜像补丁的可重放脚本已落（`dev-support/db-lab/patch-panweidb-image.sh`）+ pwfix 定型镜像，但 pwfix 只固化镜像层（①② 在数据卷里，换新卷仍要跑脚本）。

相关：[[pitfall-ibatis-log-format-vs-mybatis-param-parser]]、[[reference-cn-db-driver-families]]、[[reference-panwei-instance-and-image]]、[[project-release-20261106-beta2]]、[[parallel-build-resource-rules]]。