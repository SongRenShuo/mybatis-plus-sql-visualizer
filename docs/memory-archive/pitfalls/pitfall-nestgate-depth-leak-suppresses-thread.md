---
name: pitfall-nestgate-depth-leak-suppresses-thread
description: NestGate 按线程记账但依赖「深度最终归零」——某帧出口没配平就让该线程后续每条语句被永久抑制；磐维 A 模式复现（登记表里有 PS 却零事件），判据 -Dsqlvis.agent.nestGate=false；自愈第一版（帧名锚点）被 10 例测试否掉回退；观测轮已把泄漏帧定到 org.panweidb.jdbc.PgStatement.execute（分支 t-nestgate-observe，未合）
metadata:
  node_type: memory
  type: pitfall
  originSessionId: sess_b3ebe962-4ada-4226-8a82-7e96f8cc196b
---

**规则**：`NestGate`（「嵌套只报最内层」）是按**线程**的有状态门，正确性完全依赖 `enter`/`exit` 配平与 `depth` 归零。一旦某帧只有 enter 没有 exit（或出口没插到某条 return 路径），该线程 `depth` 恒 >0、`innerEmitted` 恒不复位 → **该线程后续每条语句都被判成「外层已由内层报过」直接 return**：不查登记表、不产事件、**连「登记盲区」告警都不打**。

## 20261110 复现（磐维 A 模式）

真机（香港 `panweidb-hk-ora`，`datcompatibility=A`，驱动 `panweidb-jdbc-2.0.0`）：

- 面板只见 SELECT 与 DDL，所有 `PreparedStatement` 语句（含 INSERT）整类消失；
- 同一 JVM 内 plain `Statement`（`PgStatement`）照常产出；
- 直连探针反射查 `StatementRegistry`：`prepare` 完**登记表里就有这条 PS**（`sql=`/`url=` 都在）⇒ 不是 prepare 面、不是 `UrlResolver`；
- 同载荷加 `-Dsqlvis.agent.nestGate=false`：**INSERT ×2 与 SELECT 立刻全部出现**。

⇒ 定因＝门禁把该线程后续语句吃掉，不是驱动、不是方言、不是连接池/iBatis 代理层（这三层已用真 Oracle 负对照逐个排掉）。

## 判据与处置

- **一刀判别**：`-Dsqlvis.agent.nestGate=false` 重启（现成系统属性，不改代码）。出现 = 门禁；仍缺 = 回实例/组装面。
- 排查顺序要改：遇到「部分语句能采、同类代码另一些整类不见」且集中在**同一线程**，**先查深度是否配平**，别先怀疑白名单/驱动。
- 高危形态：驱动在 `connect`/`<init>` 里跑内部语句（pgjdbc 系与磐维、金仓都会），那些帧最容易漏出。
- `NestGate.suppressedCount()`（20261110 新增，心跳 `nestSuppressed`）**只统计 `mayEmit=false` 的外层帧**；本次被抑制的是「最外层自己」，计数恒 0 ⇒ **看到 0 不等于没抑制**。要真覆盖需「深度泄漏探针」（每线程 enter/exit 配平计数、maxDepth）+ 门禁自愈（长时间未归零即复位），**尚未实施**。

## 自愈尝试：第一版被测试当场否掉并整案回退（20261110）

按「链首业务帧是否仍在栈上」做判据：`enter()` 里若 `depth > 0` 且链首记录的 `类名#方法名` 已不在当前栈上，
就判泄漏、复位整条链并计 `nestLeaks`（进心跳/状态栏）。

**结果：agent 子项目 287 例 → 10 红**（`NestGateTest` 2 + `StatementWeaveEndToEndTest` 8），
症状一律是「本该产出的语句零事件」。根因是**锚帧选错**：`businessFrameKey()` 跳过 `cn.song.mybatisplus.sqlvis.agent`
包取第一个非 agent 帧，而 agent 子项目的**测试夹具本身就在该包下**，锚帧于是落到 `JUnit`/反射帧——
这类帧**永远在栈上**，泄漏判不出、自愈不触发；更糟的是自己那条泄漏用例留下的 `depth=1`
把后续用例连坐成整片抑制。整案 `git checkout` 回退；回退后 287 例 1 红（`SenderLoopBackoffTest`
端口/时序 flake，单跑 BUILD SUCCESSFUL 复现不出）。

**两条下一步（未实施）**：
1. **改用「栈深度区间」而不是帧名**——链首记录当时的栈深，之后进入时若当前栈深 ≤ 链首栈深，
   说明外层帧已消失；与包名无关，天然兼容测试与宿主两种形态。
2. **先观测后放行**——给「`mayEmit=false` 且 `depth` 回到 0」这一异常形态单独计 `suppressedOutermost`，
   观测一轮确认无误伤再决定是否直接放行。

**改红线前的必备前置**：先补**跨包锚点一致性用例**（夹具在 agent 包内 / 业务在包外各一例）——
本次就是只测了一种形态，判据在测试环境里静默失效。

## 观测轮已落地并**定位到具体泄漏帧**（20261110，分支 `t-nestgate-observe`，未合 master）

只加观测、零语义改动（提交 `8e85d397` 实现 + `670111e2` 文档回填）：`State` 增 `chainStartNanos`/`chainId`；
`enter()` 在 `depth > 0` 且链龄 > `sqlvis.agent.nestStaleMs`（默认 5000）时 `nestStale++`；
进程级 `maxDepth`；首次判陈旧且 `logLevel=debug` 打**一行** dump（摘要 + 前 12 帧，`AtomicBoolean` 只一次、
不含 SQL 原文、只在 dump 时抓栈）；测试缝 `clockOverride` 经 `SqlHookCallbacks.setNestClockForTest*` 转发。
接线：心跳 `nestStale`/`nestMaxDepth` → `AgentAttachStats` → 状态栏「门禁陈旧 N」（>0 才挂，zh/en 双键）。
定向读数：`NestGateTest` 6/0、`StatementWeaveEndToEndTest` 13/0、`CaptureStatusBarTest` 29/0、`BundlePlaceholderArityTest` 4/0。

**真机两档（香港磐维 A 模式）**：
- 门禁开：三条 PS 零事件；心跳 `"nestSuppressed":4,"nestStale":4,"nestMaxDepth":2`；
- 门禁关：10 条事件全出，三拍心跳全 0（关档时 `maxDepth` 也恒 0，属预期——观测点与门禁共用 `enabled` 短路）。

dump 原文形状（关键信息全在这一行 + 栈）：
```
[SqlVisAgent] 门禁陈旧链 chainId=4 ageMs=8644 depth=2 thresholdMs=5000
  at …NestGate.dumpStaleChain / noteNestedEnter / enter
  at …SqlHookCallbacks.onStatementEnter
  at org.panweidb.jdbc.PgPreparedStatement.executeUpdate(PgPreparedStatement.java)
  at <载荷>.main(<载荷>.java:44)
```

补证探针（plain `Statement` 先打一条、8s 后再打一条）两档 dump 都命中同一处：
**泄漏帧 = `org.panweidb.jdbc.PgStatement.execute`**，且与成功/失败路径无关（DROP 不存在表 vs DROP 成功同一 dump）；
`chainId=4` 起点正对 DROP 时刻，紧随其后的 CREATE 在泄漏链上产出事件后置位 `innerEmitted`，此后整类语句消失。

**仍未证**：dump 只证明「该帧所在链未归零」，**区分不了「同一方法织了两遍入口」与「少一次出口」**——
下一步是离线数 `onStatementEnter`/`onExecuteExit` 调用点，或 retransform 单类后 javap 织入产物。

**Why**：这个门用「状态正确」换取「防 Hikari/MyBatis 包装层双报」的收益；状态一错就是**静默全丢**，而且丢的恰是最外层——最容易被误判成「驱动/方言不支持」。

**How to apply**：任何**按线程累加状态**的去重/节流门，都配一对「配平计数 + 泄漏探针」；否则第一次出错就是零信息。
修这类门时：判据只认**与包名无关的量**（栈深、时间基准、对象身份），并先写跨包锚点用例再动语义。
相关：[[project-panwei-insert-capture-20261110]]、[[pitfall-console-bridge-fallback-covers-only-dead-agent]]、[[reference-agent-minimal-repro-harness]]。
