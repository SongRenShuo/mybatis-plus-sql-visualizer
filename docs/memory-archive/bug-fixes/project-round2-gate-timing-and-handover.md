---
name: project-round2-gate-timing-and-handover
description: 20261101~02 门禁四轮收口（8900/1 → 8909/4 → 8909/1 → 8909/0/0/12）＋真机三批全闭环——
  批 A 三族 attach 走 IDE Run 注入 agent、批 B 1PG+1Redis 推断 PostgreSQL、MCP261 在 2026.1.5.1 全链通
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 第二轮门禁四红 + 真机批 A/B/MCP261 状态（2026-11-01 晚）

## 一、门禁读数链（dev）

- 第一轮（317b22af 前）：**8900/1/0/12**，唯一红＝ExecTempFileAsyncTest（PSI 异步窗口）→ 修 `317b22af`（revalidateOnEdt + 缓存守卫 psi.isValid）。
- 第二轮：**新增 4 红**，定性三种根因、全部收口于 `d70cea81`（详见 [[pitfall-test-determinism-production-topic-and-async-gate]]）。
- 第三轮：**8909/1/0/12**，唯一红＝SqlCaptureGateSniffTest（`CaptureActivityFallback` 全局解封态未隔离）→ 修 `e6725876`（setUp resetForTest）。
- 第四轮（收口）：**8909/0/0/12 全绿**（dev `e6725876`）。定向读数 **62/0/0/0**。
- 记录册：`docs/验收文档/20261101-2250-第二轮门禁四红定性与时序收口-执行记录.md`（`0f55362f`）。

## 二、一次按纪律撤回的生产改动（重要教训）

为修 DmlImpact/Rollback 三例时序红，曾给 `PrepareSingleFlightGate` 加「持有人形态 executeAfterPrepare」+ `executePrepareInFlight()`，三处绑定提示点改用它。**反向验证不成立**：摘掉后用例照样绿（绿来自测试侧 awaitPrepareGateIdle）。按纪律整建制撤回。「预览冷窗内绑定提示被吞」登记为候选产品问题（复现：开预览后立刻点预估影响行数/回滚快照/EXPLAIN），要修单独立项。

## 三、S2-14 已闭环（组件级自动化）

`LogListAnchorViewportTest`（4 例，子代理 a0685d2f → merge 6105777c）：判据=**顶部可见条目身份 + 行内偏移**（`viewPosition` 只作辅助——满载头淘汰时它必然变小，补偿本身就是缺陷面）；双层反向验证（摘 `tailFollow.shouldFollow` 门控 → A/A2 双红；再摘头淘汰补偿 → 仅 A2 红）。真机滚轮降级为可选视觉复核。

## 四、批 A / B 已闭环（2026-11-02 00:20）

**批 A 三族 attach 回流 ✅**：上一轮卡点是「插件挂不到非 IDE 启动的外部 JVM」，**正解＝走 IDE Run 链路**——
demo `pom.xml` 加三条 system-scope 驱动（jar 取插件沙盒 `plugins-test/.../lib/`）+ 改 `application.yml` datasource 四项，
IDE 里 Shift+F10 起 `MybatisPlusDemoApplication`，平台 `updateJavaParameters` 自动注入 `-javaagent`。
实测角标：`[HighGo] ×10`（`SELECT id,username,… FROM user`）/ `[Vastbase] ×9`（pg_settings 三条语句面）/
`[Kingbase]（人大金仓）`（CREATE + DROP TABLE）。**失败语句同样回流**（三族都报 `user` 表 column id 不存在，错误链完整）——
钩子挂在执行入口而非成功分支。给 `target/classes` 解压驱动跑 demo 仍不成立（determineDriverClassName 断言失败）。

**批 B DialectInferFix ✅**：夹具＝`.idea/dataSources.xml` 恰好 1 PG 源 + 1 Redis 源（**必须移除原 MySQL 源**，否则两 SQL 源本身歧义）。
`OrderMapper.xml` 预览头推断 **PostgreSQL**、未回落 MySQL 兜底。

验收册＝`docs/验收文档/20261102-0020-真机批AB三族attach回流与方言推断-验收文档.md`（`ee739c3a`）。

## 五、MCP261 已闭环（2026-11-01 23:58）

IDEA **2026.1.5.1**（`261.27258.136`）解压于 `_ide261/idea261`（zip 1.59GB），**已内置 mcpserver 插件**；
`bin/idea64.exe.vmoptions` 追加 `-javaagent:qwdn-afb.jar` + `-Didea.config/system/plugins.path` 指向 `_ide261/run/`（idea.key 已拷）。
243 `buildPlugin` 产物解压进 `run/plugins` 即加载（`SQL Visualizer (2026.10.31)`，plugin.xml since-build=243 无 until）。

读数：11 toolsets 注册、gate filter 注册无 NPE、设置页 Enable 成功、`/sse` 200+sessionId、
`initialize` 202（非 500）、`tools/list` **11 个 `sqlv_*`**、`tools/call`（sqlv_render_sql）`isError:false` 实跑成功；
P0 特征签名（`CollectionsKt.addAll` NPE / `Parameter specified as non-null is null` / HTTP 500）日志**零命中**。
`-PplatformVersion` 编 261 仍不可行（compileJava 35 错）；`-PverifyIdes=261` 单点模式已入库。
客户端探针配方（无需装 MCP 客户端）：`curl -N :64342/sse` 拿 sessionId → `POST /message?sessionId=` 发
initialize/notifications/initialized/tools-list/tools-call，结果从 SSE `data:` 行读；工具调用要传 `projectPath`。
验收册＝`docs/验收文档/20261101-2355-MCP261真机2026.1.5验证-验收文档.md`（`734fd86f`）。

相关：[[pitfall-test-determinism-production-topic-and-async-gate]]、[[project-s2-15-ob-panel-closure]]、[[pitfall-jlist-no-scroll-anchoring]]。
