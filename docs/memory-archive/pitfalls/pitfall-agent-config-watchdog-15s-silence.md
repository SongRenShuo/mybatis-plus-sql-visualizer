---
name: pitfall-agent-config-watchdog-15s-silence
description: 增强捕获「启动约 15~16s 后钩子不再产事件」的真因是 agent 的 T-07 CONFIG 看门狗（会话建立 15s 内没收到 CONFIG 就主动停采）——手工/假 IDE 探针必须先发 CONFIG，否则测到的是自停而不是采集链
metadata:
  node_type: memory
  type: pitfall
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

**规则**：任何自己写 agent 探针/驱动脚本的场景，**必须在 `HELLO` 之后立即下发一次 `CONFIG` 并等 `CONFIG_ACK`**，否则采集会在 15s 后按设计停掉，看起来像「采集链坏了」。

机制（agent 侧 T-07 保守策略）：`SenderLoop.DEFAULT_CONFIG_WATCHDOG_MS = 15_000` —— 会话建立后 15s 内从未收到任何 CONFIG，就 `AgentState.setDisabled(true)`（进程内一直保持，直到有 CONFIG 进来）。真 IDE 在 `AgentManager.attachToPid` / `onPremainConnected` 建会话后立刻 `sendConfig`，所以现网不常态命中；但 `sendConfig` 失败那条路（代码里 `runCatching` 只记 WARN）会表现成同款静默。

**现场症状（20261106 实测 4 轮）**：事件在 HELLO+15s 的下一次轮询处截止（HELLO 2.8~4.2s → 末条事件 14.9~16.8s），此后业务请求仍 200、心跳仍发（sender 活着）、`CONFIG_ACK=0`。对照排除：`-Dsqlvis.agent.nestGate=false`、`-Dsqlvis.agent.catchAll=false` 均复现 → 与 NestGate、premain 兜底扫描、PS 归属表都无关（H1/H2 全证伪）。补一帧 `config {"disabled":false}` + `expect-ack` 后四窗口 21.5/30.8/40.0/49.0s 分别 31/2/1/1 条、全绿。

**判据顺序**：先看探针日志里 `CONFIG_ACK` 是否为 0 / 应用 stderr 是否有 `[T-AgentConfigChannel] 会话建立后 15000ms 内未收到任何 CONFIG，按保守策略主动停采…`；有就是看门狗，不是采集缺陷。

**已做的可见化（`1cc28d7c`）**：`AgentState` 加自停归因（`GATE_NONE`/`GATE_CONFIG_WATCHDOG`、`gateCode()`），心跳 body 追加 `"gate":0|1`（恒出现，旧 IDE 宽容扫描零影响），预警行点名「下发任一 CONFIG 即解除」。采集语义未改。IDE 侧尚未消费 `gate`（后续可 gate=1 时自动重发 CONFIG）。

**Why**：这条自停此前完全不可见，被当成「16s 后静默」的采集 bug 查了四轮，代价是四个候选根因全证伪。

**How to apply**：写探针脚本先抄 `dev-support/e2e-fake-ide/script-silent-window.txt` 的握手序列（`wait-hello` → `config {"disabled":false,"desensitize":true}` → `expect-ack "disabled":false` → `wait-http`）；调采集链前先确认 `CONFIG_ACK=1`。

相关：[[project-ibatis-panwei-20261106]]、[[reference-agent-attach-smoke-recipe]]。