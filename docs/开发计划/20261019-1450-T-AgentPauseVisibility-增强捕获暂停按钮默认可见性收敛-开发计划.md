# T-AgentPauseVisibility 增强捕获暂停按钮默认可见性收敛——开发计划

- 立项时间：2026-10-19 14:50
- 基线：`dev` `9ec129b3`（T-ToolbarIconSemantics 已合入；本计划建立在其与 T-EnhancedCaptureDefaultOn `5b4fb4e0` 之上）
- 状态：已实施（2026-10-19，用户拍板方案 B——临时全隐藏；提交 `8b797098`）。`AgentPauseAction.update` 恒置不可见/不可用，动作对象保留在工具栏组内；定向 4 类 **29/0/0/0**。补测后 `testFast` **4100/0/0/6**、`SqlLogPanelLayoutTest` **19/0/0**。复盘见 `docs/经验总结/20261019-2004-守卫类测试分桶遗漏与暂停按钮可见性补测-经验总结.md`。**沙盒真机验收已过（2026-10-19 晚，243 沙盒）**：项目打开/Run 全程零注入确认弹窗（idea.log 当日会话 consent 轨迹 0 条）、premain 注入完成且 agent 会话建立（pid=24136、在线态 false→true）、SQL 执行产生条目，工具栏 ActionButton 数 agent 在线前后恒 9（暂停按钮未出现）；设置页 XML `enhancedCaptureEnabled` 默认未序列化（出厂 true）。未跑全量门禁、未推送。
- 编号：不预占 O/R/D/V，实施前按台账登记。
- 关联：T-EnhancedCaptureDefaultOn（`5b4fb4e0`）、T-ToolbarIconSemantics（`9ec129b3`）、R53 双通道状态栏、`docs/规范/设计规范-弹窗克制红线.md`。

## 一、结论

用户问的是 SQL 日志工具栏里的「增强捕获」按钮是否应该隐藏。先澄清对象：该按钮不是开启入口，而是 R53 的**暂停/恢复增强捕获**动作（`SqlLogPanel.AgentPauseAction`）。它的语义是只停 agent 通道、控制台通道照常，连接/心跳/CONFIG 链不动；与总开关「关闭增强捕获」不是一回事。

现状：`AgentPauseAction.update` 的可见条件是 `isEnhancedEnabled()`。增强捕获改为默认开启且无提示后，这个按钮从第一次打开 IDE 起就常驻工具栏——即使应用还没运行、agent 尚未挂载，用户看到的也是一个暂时无对象的暂停按钮。这确实与「默认开、零提示」的新形态不协调。

但**无条件隐藏不合理**：它是用户暂停 agent 通道而不关闭整个增强捕获的唯一 UI 入口。全隐藏会让「暂停」能力失去可达路径，属于能力回退。

推荐方案：**条件可见**——`enabled && (userPaused || healthyPids().isNotEmpty())`。

- 未运行应用、无健康 agent 会话：隐藏。默认工具栏回到 10 个动作，不再出现无对象的暂停按钮。
- agent 已挂载：显示暂停按钮，用户可临时停 agent 通道。
- 已暂停：无论当前是否有活会话都显示恢复按钮，保证用户能撤销暂停（否则暂停态会跨 Run 残留而无 UI 入口）。
- 总开关关闭：隐藏（沿用现状）。

这样既回应了「默认开启后按钮一直挂着」的观感问题，也不砍掉暂停能力。若用户坚持「先彻底隐藏」，见 §八 的方案 B 与其代价。

用户于 2026-10-19 拍板采用**方案 B（临时全隐藏）**，接受暂停入口暂时不可达；本计划按 B 落地，方案 A 留作恢复路径。

## 二、现状与证据

| 事实 | 位置 |
|---|---|
| 动作定义与可见性：`e.presentation.isVisible = am?.isEnhancedEnabled() == true` | `log/SqlLogPanel.kt:1100-1120` |
| 动作在工具栏组中紧随捕捉动作 | `log/SqlLogPanel.kt:505-517`（`agentPauseAction` 位于 `captureToggleAction` 之后） |
| 暂停语义：只停 agent 入队，控制台照常；连接与 CONFIG 链不动 | `log/agent/AgentManager.kt:180-195`（`setUserPaused`） |
| 健康会话 pid 列表（只读、无锁） | `log/agent/AgentManager.kt:199`（`healthyPids()`） |
| 状态栏已每 2s 展示 `agentPaused` 与 `agentLivePids` | `log/SqlLogPanel.kt:1425-1450`（`syncCaptureStatusBar`） |
| 工具栏刷新现状：只在捕捉态变化与排序方式变化时 `updateActionsImmediately()` | `log/SqlLogPanel.kt:526-534`、`:1774-1777` |
| `SqlLogPanel` 目前**未订阅** `AgentManager.AGENT_STATUS_TOPIC` | `log/SqlLogPanel.kt` 全文无该订阅 |

最后一条是本计划的关键技术前提：把可见性从「总开关」改成「活会话/暂停态」后，agent 挂载或断开时工具栏必须重新求值；现有刷新点不覆盖这个事件。若不补刷新，按钮会在挂载后迟迟不出现，或断开后不消失。

## 三、目标与非目标

### 目标

1. 默认形态（应用未运行、agent 未挂载）不显示暂停按钮，工具栏可见动作回到 10 个。
2. agent 健康会话建立后显示暂停按钮；点击后切为恢复按钮，并在暂停期间保持可见。
3. 总开关关闭时隐藏；重新开启且 agent 已挂载时重新出现。
4. 状态栏的「暂停中/已挂载 pid」叙事不变，仍可解释按钮为何出现或消失。

### 非目标

- 不移除暂停能力，不改变 `setUserPaused` 的语义（只停 agent 通道、控制台照常）。
- 不改图标与图标注册表（由 T-ToolbarIconSemantics 负责；本计划只动可见性）。
- 不改捕捉开关、清空、设置、搜索、预览、排序、帮助等其他工具栏动作。
- 不改总开关默认值、确认流（已由 T-EnhancedCaptureDefaultOn 收口）。
- 不把暂停入口挪到设置页；设置页仍只有总开关与脱敏等配置。

## 四、实施方案

### W1 方案 B：无条件隐藏

`AgentPauseAction.update` 恒置：

```kotlin
e.presentation.isVisible = false
e.presentation.isEnabled = false
```

- 动作对象仍保留在 `toolbarGroup` 内，`actionPerformed`、`AgentPause/AgentResume` 图标与 `log.panel.agentPause/agentResume` 文案键保留，供方案 A 或新入口恢复时复用。
- 不要改回 `isEnhancedEnabled()`：那会退回「默认开启后按钮常驻」的旧形态。
- 代价：暂停 agent 通道的 UI 入口暂时不可达；关闭总开关仍会回落控制台。

### W2 工具栏刷新接线（方案 B 不实施）

方案 B 的可见性是常量 `false`，不依赖 agent 挂载/暂停态，因此不需要 `AGENT_STATUS_TOPIC` 刷新接线。该接线保留给方案 A 恢复时使用：届时按「agent 挂载或暂停态才显示」补订阅与 EDT 刷新。

### W3 测试

1. 行为用例（`SqlLogPanelLayoutTest` 新增）：`enhancedCaptureEnabled=true` 时调用 `agentPauseAction.update(event)`，断言 `isVisible=false`、`isEnabled=false`，且动作对象仍在 `toolbarGroup` 内。
2. 图标语义测试：`ToolbarIconSemanticsTest` 已按「仅校验可见动作」跳过隐藏动作；方案 B 下暂停动作不参与 16px 图标断言。
3. 既有布局/帮助测试：组内成员与索引不变，预计不改。
4. 定向跑 `SqlLogPanelLayoutTest`、`HelpDialogTest`、`HelpUiTest`、`ToolbarIconSemanticsTest`；真机按 §七 走查。

### W4 文档同步

- `docs/开发计划/20261019-1315-T-ToolbarIconSemantics-...md`：把「增强暂停按钮只在增强捕获开启时可见；未开启时工具栏只有 10 个按钮」改为「增强挂载或暂停时可见；默认（未挂载）10 个，挂载后 11 个」。
- `docs/规范/测试沙盒验证清单.md`：若含暂停按钮检查项，补「未运行应用时不可见、agent 挂载后出现」。
- 实施落地时在 `CHANGELOG.md` 与 `docs/变更记录.md` 各补一行。

## 五、待验证前提

1. **`healthyPids()` 的口径**：只统计健康会话（心跳窗口内）；应用停止、心跳过期后变空。用它作为「可暂停对象」的判据成立。若希望「进程还在但心跳刚过期」也保留按钮，需要另取 `sessions.isNotEmpty()`，但那会把半死会话也算进来，需明确取舍。
2. **`AGENT_STATUS_TOPIC` 的发布点覆盖**：确认 attach 成功、premain 回连、掉线摘除、`setUserPaused`、`shutdownAll` 都会触发 `publishStatusChanged()`；若有遗漏，按钮显隐会滞后，需补发布点或走 2s 兜底。
3. **EDT 约束**：topic 回调线程不保证 EDT；`updateActionsImmediately()` 必须在 EDT 调用。实施时先查该 API 的线程要求与项目既有用法（`SqlLogPanel.kt:533` 已在 `invokeLater` 内调用）。
4. **暂停后应用停止**：`userPaused` 不会被 `sweepOffline` 重置（只有 `shutdownAll` 与设置下降沿会复位），所以恢复按钮应保持可见。需用测试钉住。
5. **headless 测试的可测性**：工具栏 `update` 在不可见组件上可能被平台跳过；纯函数与源码守卫是本计划的主要测试面，真机负责可见性验收。
6. **与 T-ToolbarIconSemantics 的并发**：该任务已合入 `9ec129b3`，但其验收矩阵仍按「开启即 11 个」写；实施前先确认其文档与测试是否需要同步，避免两边对「可见集合」的断言互相打架。

## 六、风险与回退

- **发现性下降**：用户开启增强捕获后、应用未运行时看不到暂停按钮，可能以为功能缺失。状态栏在捕捉期间仍显示 agent 段；可在状态栏文案或 tooltip 里说明「应用挂载后可暂停」。
- **刷新遗漏**：若某条挂载/掉线路径不广播状态，按钮显隐会滞后。W2 的 topic + 2s 兜底是双保险。
- **与工具栏图标工作的重叠**：本计划只动可见性，不动图标；但两者都改 `SqlLogPanel.kt`，实施时以 `9ec129b3` 为基线，先 rebase 再动。
- **回退**：把 `update` 的可见性改回 `isEnhancedEnabled()` 即可，单点一行；不涉及数据迁移。

## 七、验收

真机/沙盒矩阵（方案 B）：

1. 首次打开 IDE、应用未运行：工具栏 10 个动作，**无**暂停按钮。
2. Run 应用、agent 挂载：仍**无**暂停按钮（全隐藏）。
3. 总开关开/关、暂停态：暂停按钮均不显示；暂停能力暂不可达（`userPaused` 只存内存，重启 IDE 复位）。
4. 窄宽工具栏：动作组仍含隐藏的暂停动作，平台按可见集合折叠，不出现自建「更多」。

测试读数记录：`SqlLogPanelLayoutTest` / `HelpDialogTest` / `HelpUiTest` / `ToolbarIconSemanticsTest` 定向 4 类 **29/0/0/0**；补测后 `testFast` **4100/0/0/6**（含两处守卫自检）、`SqlLogPanelLayoutTest` **19/0/0**（暂停按钮四状态矩阵）；真机结论由用户给出。

## 八、决策点

- **方案 B（已选，2026-10-19）**：临时全隐藏。用户明确选择；暂停能力暂时不可达，恢复路径为方案 A 或状态栏/右键菜单入口。实施见 §四 W1。
- **方案 A（恢复路径）**：条件可见——未挂载隐藏，挂载后显示，暂停后保持恢复入口。方案 B 的下一轮恢复目标。
- **方案 C（维持现状）**：继续「总开关开即显示」。不解决用户提出的默认常驻与图标误读问题，不采用。

本计划已按方案 B 落地；方案 A 保留为恢复路径。
