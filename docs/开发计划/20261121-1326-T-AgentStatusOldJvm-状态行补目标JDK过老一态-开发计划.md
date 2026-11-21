# T-AgentStatusOldJvm：增强捕获状态行补「目标 JDK 过老已跳过」一态

> 来源：T-OldJvmPremainGuard（`79cafe2a6`，merge `b55d93046`）真机验收后的复盘。注入前 JDK 闸门已落地，但状态行没有对应叙事——用户错过一次性气球通知后，设置页会一直显示第 6 态「已开启，等待应用进程启动后挂载」，而该项目**永远等不到挂载**。这正是报障用户的实际体验：勾着、没数据、看着像坏了。

## 一、改法

1. `AgentStatusSnapshot` 末尾加 `val targetJvmUnsupported: Boolean = false`（加在末尾，不破坏既有位置参数调用）；
2. `agentStatusText` 在「从未挂载」分支之前插一态：命中即返回新键 `settings.enhancedCapture.status.targetJvmTooOld`（挂载态优先级不变：有活会话/曾挂载的分支在前，真实挂载了就如实显示挂载）；
3. `AgentManager`：新增 `targetJvmSkipDetected = AtomicBoolean(false)`（internal 测试缝），`preparePremainVmOption` 命中版本闸时置位；`statusSummary()` 构建快照时传入。粘性置位（本会话曾命中即显示），无需清除——真实挂载后自然被更高优先级分支覆盖；
4. bundle 两语言各一条：zh「目标 JDK 低于 8，已跳过注入（回落控制台捕获）」；en 对应。不报具体版本号（rt.jar 兜底值是下限，见 T-OldJvmPremainGuard §五.3）；
5. `CHANGELOG.md` `[Unreleased]` Fixed 加一条（用户可感）。

## 二、验收

1. 定向：`AgentStatusTextTest` 补三例（新态文案 / 挂载态优先于新态 / 各态互不重复收编新态）；`AgentManagerPremainTest` 补一条 manager 级（`preparePremainVmOption(50)` 后 `statusSummary()` 出新态、之前是「等待挂载」）；
2. 反向验证用 **git 补丁方式**（用户 2026-11-21 令）：摘掉 `agentStatusText` 新态分支生成补丁 → `git apply` → 新用例必红（回落「等待挂载」文案）→ `git checkout --` 还原 → 复跑绿；两次读数入档；
3. 文案键缺失即假绿防线沿用（bundle 缺键时 `message()` 返回 key 本身，中文特征串断言必红）。

## 三、不做

- 不报具体 JDK 版本号（同 T-OldJvmPremainGuard 口径）；
- 不做 JDK 6/7 支持（ASM `java.lang.invoke` 60 处引用 + 自有 Java 8 API，成本远超收益，用户已确认收口）。

## 四、实施记录

| 项 | 状态 | 提交 | 读数 |
|---|---|---|---|
| 计划 | 已完成 2026-11-21 | — | — |
| 实施 | 待开工 | — | — |
| 反向（git 补丁） | 待开工 | — | — |
