# T-MultiModuleConsoleYield：多模块并行时，无 agent 模块的控制台 SQL 被全局让位吞掉

> 用户沙盒实测（2026-11-21 15:35）：两个模块同时启动，只有挂了 agent 的模块捕获到 SQL；另一模块控制台里明明有标准 MyBatis 三段日志（`==> Preparing:` / `==> Parameters:` / `<== Total:`），面板 0 条。面板底部读数：`控制台：已挂载 6 个，已扫描 167 行｜让位丢弃 SQL 行 3｜增强：已挂载 pid=51720`。

## 一、根因

接管闸门是**项目级全局开关**：`SqlLogConsoleBridge.processConsoleLine` 对每一行只问 `SqlCaptureGate.paused`（AgentManager.updateOnline 写入：`online && captureConfirmedActive() && !userPaused`）。只要 agent 在**任意一个**进程上在线，桥对**所有**控制台的 SQL 行都让位丢弃——包括没挂 agent 的模块。单应用场景这是防双报的正确设计；多模块场景是纯损失。

## 二、修法：让位做行级进程归属

Spring Boot 标准日志行的前缀自带 PID 字段（`2026-11-21 15:35:42.823 DEBUG 52324 --- [...]`），而行内的 PID 就是目标进程的 pid——agent 会话的 pid 与之同源（HELLO 帧）。据此：

1. `SqlCaptureGate` 增 `liveSessionPids`（健康会话 pid 集，`AgentManager.updateOnline` 与 `paused` 同点同步）；
2. `processConsoleLine` 的让位分支加行级归属：
   - 行内能提出 pid 且 pid **不在**健康会话集 → 这行属于没挂 agent 的模块，**照常解析**（修复点）；
   - 行内 pid **在**集内 → 既有让位（防双报）；
   - 提不出 pid（非该日志形态）→ 保持既有全局让位（宁丢勿重，不改未证形态）；
3. `CaptureActivityFallback`（双哑解封）语义不动：只由被让位的行驱动（即挂了 agent 的那台），解封判定不被无 agent 模块的行污染；
4. EP 路径（`EpLineFilter` 同调 `processConsoleLine`）自动获得同一修复——EP Filter 虽不持有 console 引用，但**行内 pid** 与它无关紧要。

判据提取为纯函数 `SqlCaptureGate.logLinePid(line)`（两种形态：带日期两段时间前缀 / 单段时间前缀；`[A-Z]+` 级别 + 数字 pid + `---`），非该形态返回 null。

## 三、验收

1. 新测试（headless，照 `ConsoleSettleFlushTest` 夹具）：
   - paused + 行 pid ∈ 会话集 → 丢弃并计数（既有行为钉住）；
   - paused + 行 pid ∉ 会话集 → MyBatis 三段照常产出条目（修复点）；
   - paused + 行无 pid → 丢弃（保守面钉住）；
   - `logLinePid` 纯函数：带日期/不带日期/非日志行/null。
2. 反向验证 git 补丁方式：摘掉行级归属（恢复全局让位）→ 「pid ∉ 会话集」用例必红 → 还原绿。
3. `AgentStatusTextTest` 等既有类回归不受影响（闸门语义只放宽「行可归属且无 agent」一态）。

## 四、不改

- 不动接管/回落沿的去重索引重建（接管沿语义不变）；
- 不动 `CaptureActivityFallback`（双哑解封仍是挂 agent 那台的安全网）；
- 不做 console↔ProcessHandler 的 pid 绑定（EP Filter 无 console 引用，行内 PID 已够用且零反射）。

## 五、实施记录

| 项 | 状态 | 提交 | 读数 |
|---|---|---|---|
| 计划 | 已完成 2026-11-21 | — | — |
| 实施 | 已完成 2026-11-21 | `eb9711735` | `MultiModuleConsoleYieldTest` 5/0（pid 提取两形态+非日志形态 / 有会话让位计数 / 无会话产出（修复点）/ 无前缀保守面）+ 让位嗅探回归 `AgentLogTimeParityTest` 14/0、`ConsoleSettleFlushTest` 6/0、`EpBridgeSinglePathTest` 7/0 |
| 反向（git 补丁） | 已完成 2026-11-21 | 补丁 `%TEMP%\mpsv-mmy-reverse.patch` | 摘掉行级归属（恢复无条件让位）→ 「pid ∉ 会话集」用例 **1 红** → `git checkout --` 还原 → 5/0；全程未手改代码 |

实施坑两条：① 归属条件首版写反（「pid ∉ 会话集才让位」）——首跑 2 红当场暴露，测试先行的价值现场；② MyBatis 空参数行（`==> Parameters:` 空余量）不闭合条目、要等静默冲刷——测试夹具的 Parameters 行须带参数值才能行级同步闭合，断言落在行级边界而非静默冲刷。
