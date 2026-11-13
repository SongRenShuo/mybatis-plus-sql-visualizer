# agent 捕获链路本机复现装置（T-DevAcceptFix2 ⑥ 取证用）

本目录是 2026-10-06 第三轮验收期间**本机复现「开启增强捕获后宿主崩溃 / 零捕获」**所用的最小装置与取证日志。保留目的：下次再遇到「已挂载但 0 条」时可直接复用，不必重搭。

## 这份装置证了什么

| 注入产物 | 应用启动 | EVENT 帧 | VerifyError |
|---|---|---|---|
| `afb62e4` 帧补丁产物（旧） | ❌ 启动期即崩 | 0 | ✅ `Stack map does not match … exception handler` |
| pre-`afb62e4` 沙盒残留 jar（对照） | ✅ | 9 | 无 |
| `9810df8` 修复后产物 | ✅ `Started … in 3.129 seconds` | 9（启动期 schema.sql 6 + 请求线程 3） | 无 |

→ 结论：**事件产出与传输链路本身没问题**，宿主崩溃（帧补丁槽位错位 ⇒ 一建 Statement 即 `VerifyError`）导致「无 SQL ⇒ 0 事件」。对应日志见 `demo-prefix-ok.log`（对照）、`demo-postfix-crash.log`（旧补丁崩溃）、`demo-fixed-ok.log`（修复后）、`recv*.log`（接收端逐帧输出）。

## 协议要点（读源码实证，勿凭记忆）

- 帧格式：`[4B 大端长度][1B 命令][长度字节 UTF-8 JSON]`（`AgentProtocol.kt:7`；写侧 `SenderLoop.java:549`）。
- 方向：**agent 是 TCP 客户端**（`SenderLoop.java:225` dial 127.0.0.1:port），**IDE 侧是服务端** ⇒ 假接收端必须做 listener。
- 命令号：1=EVENT、2=HEARTBEAT、3=CONFIG(IDE→agent)、4=HELLO、5=CONFIG_ACK(agent→IDE)。
- agent 参数：`-javaagent:<jar>=port=<端口>;logLevel=warn`（`AgentArgs.java:20-55`；无 `port` 则 install 直接放弃）。

## 怎么再跑一遍

1. 构建 agent 产物（`./gradlew :sql-visualizer-agent:agentFatJar`，产物在 `sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar`）。
   产物新鲜度用**方法签名**核对更硬：修复版应为 `patchFramesWithTokenSlot(MethodNode, int)`（`javap -p` 看）。
2. 起假接收端：`python fake_receiver.py <端口>`（仅 Python 标准库）。
3. 用 `-javaagent` 启动目标应用（示例：demo 工程 `mybatis-plus-sql-visualizer-demo`，JDK8u181 实测可用），端口与第 2 步一致。
4. 发一个**确实会查库**的请求（demo 用 `/selfdev/query/paged?owner=lisi`、`/selfdev/query/count?status=1`），观察接收端逐帧输出与应用控制台。
5. 判定：收到 EVENT ⇒ 产出与传输正常（若 IDE 面板仍空，转查 IDE 侧消费/展示链）；握手通但零事件 ⇒ 查 agent 侧织入/门禁；连握手都没有 ⇒ 看应用控制台（`grep -E "VerifyError|LinkageError"`）。

## 注意

- 假接收端**不是** IDE 侧实现，只用于判别「事件有没有被产出并送出」；面板侧行为仍需 runIde 沙盒走查。
- 本目录**不参与构建**（`dev-support/` 非源码集），改动不影响构建产物与门禁读数。
