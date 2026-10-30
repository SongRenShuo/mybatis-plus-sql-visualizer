---
name: project-agent-pause-visibility
description: T-AgentPauseVisibility（2026-10-19 用户拍板方案 B 已实施）——SQL 日志工具栏的「增强捕获暂停/恢复」按钮临时全隐藏；它是暂停入口不是开启入口；恢复路径为方案 A（agent 挂载或暂停态才显示）
metadata:
  node_type: memory
  type: project
  originSessionId: session_c796a179045839a6ccdc8c7ff5d1147c
---

# 增强捕获暂停按钮可见性（T-AgentPauseVisibility，方案 B）

**用户提问**（2026-10-19，在 T-EnhancedCaptureDefaultOn 之后）：「既然增强捕获默认就是开启且无提示，SQL 日志页面的增强捕获按钮是不是应该先隐藏下」→ 我给出 A/B/C 三案 → 用户拍板 **方案 B（临时全隐藏）** 并令实施，真机验收仍归用户。

## 先澄清对象（最容易改错的地方）

那个按钮**不是开启入口**，是 R53 的**暂停/恢复增强捕获**动作（`SqlLogPanel.AgentPauseAction`）。语义＝只停 agent 通道（事件在入队点丢弃）、控制台通道照常、连接/心跳/CONFIG 链不动；与总开关「关闭增强捕获」不是一回事。改之前若误当成开关去删，会砍掉能力代码。

## 已实施形态（`8b797098`，dev，未 push）

`AgentPauseAction.update` **恒置** `isVisible=false`、`isEnabled=false`；动作对象仍留在 `toolbarGroup` 内，`actionPerformed`、`MpvsIcons.AgentPause/AgentResume`、`log.panel.agentPause/agentResume` 文案键全部保留。代码注释写明：**不要改回 `isEnhancedEnabled()`**（那会退回「默认开启后按钮常驻」的旧形态）。

代价（已向用户说明并接受）：暂停 agent 通道的 UI 入口暂时不可达；关闭总开关仍回落控制台。

## 新增测试与同步面

- `SqlLogPanelLayoutTest` 新增「方案 B 下始终不可见」：开关置 true 后 `update` 仍 `isVisible=false`/`isEnabled=false`，且动作仍在组内。
- 定向 `SqlLogPanelLayoutTest`/`HelpDialogTest`/`HelpUiTest`/`ToolbarIconSemanticsTest` **29/0/0/0**。
- 同步：`T-ToolbarIconSemantics` 计划里「增强暂停只在开启时可见/工具开启时 11 个按钮」全部改成「方案 B 临时全隐藏、工具栏恒 10 个可见动作、图标冲突按可见集合查」；CHANGELOG、变更记录、计划索引一并更新。
- 计划文件：`docs/开发计划/20261019-1450-T-AgentPauseVisibility-增强捕获暂停按钮默认可见性收敛-开发计划.md`。

## 恢复路径（方案 A，未实施）

条件可见 `enabled && (userPaused || healthyPids().isNotEmpty())`：未挂载隐藏、挂载后显示、**暂停态必须单独成立**（否则「暂停后停应用」会让恢复入口消失、暂停态跨 Run 残留）。落到方案 A 时需要补一条现在**没有**的接线：`SqlLogPanel` 目前**未订阅** `AgentManager.AGENT_STATUS_TOPIC`，工具栏只在捕捉态与排序变化时 `updateActionsImmediately()`；可见性一旦依赖活会话/暂停态就必须补订阅 + EDT 刷新（或用 2s 状态栏轮询做变化检测）。

## 过程中的一条测试假失败

组合跑四个类时 `ToolbarIconSemanticsTest` 曾报 `testSQL日志工具栏动作update后均带16px图标` 失败，单跑与组合复跑均绿（4/0/0、29/0/0/0）；同一时段另一会话的 `7fc9f686` 也在改该测试的可见性判定 ⇒ 判为并发/图标加载竞态，不是方案 B 的代码问题。**组合跑与单跑读数不一致时，先看是否有别的会话正在改同一工作树**。

## 沙盒真机验收（2026-10-19 晚已过，结论回填 `e865d3be`）

243 沙盒 robot-server 实测：项目打开与 Run 全程无注入确认弹窗（当日会话日志 consent 轨迹 0 条）；premain 首轮即注入并建 agent 会话（pid=24136、在线态 false→true）；SQL 执行产生条目后，SQL 日志工具栏 ActionButton 数**恒 9 枚**（agent 在线前后一致，暂停按钮未出现）；在役 jar `javap -c` 证实 `update` 恒隐藏；沙盒设置 XML 中 `enhancedCaptureEnabled` 未序列化（出厂 true 不落盘）。真机验收判暂停按钮用「组件树屏幕坐标数按钮」而非读图/读 tooltip（robot 拿不到 accessibleName 与 JLabel 文本，本机无多模态读图——这两条是 UI 验收的证据等级上限）。

**Why:** 默认开启后按钮常驻会让用户以为是开启入口或与捕捉组成播放/暂停误读；但全隐藏是能力回退，必须显式记录代价与恢复路径。

**How to apply:** 后续要恢复暂停能力时按方案 A 做，别直接 revert 成 `isEnhancedEnabled()`；改 `SqlLogPanel` 工具栏前先确认 [[project-toolbar-icon-semantics]] 的可见集合断言是否同步。关联 [[project-enhanced-capture-default-on]]、[[project-sql-log-tail-follow-gap]]（工具栏动作顺序与无自建「更多」）。
