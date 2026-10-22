# T-DialectGateUx 方言闸门阻断提示给出就地修复入口 — 开发计划

- 日期：2026-10-20 16:53
- 任务编号：T-DialectGateUx
- 来源：用户 2026-10-20 截图报障（预览底部红条：「已阻止执行：当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL。当前模型无法无损重渲染……请重新生成 SQL 或切换目标后再执行。」）——用户诉求是「提示不友好，希望能一键跳到解决问题的地方」。
- 性质：体验（不改闸门的判定语义，不放松任何阻断）
- 实施分支：dev 工作树 `mpsv-dialect-gate-ux` / 分支 `t-dialect-gate-ux`，基线 dev `a91b73fe`

---

## 一、现状

| 层 | 事实 |
|---|---|
| 判定 | `ExecutionDialectGate.evaluate` 在「渲染方言 ≠ 目标方言」且无法无损重渲/文本被编辑时返回 `RERENDER_UNAVAILABLE`；手动方言渲染则为 `MANUAL_CONFLICT` |
| 呈现 | `SqlPreviewPanel.onExecutionDialectGate` 对这两态只调用 `resultPanel.showError(文案)`（`SqlPreviewPanelExecution.kt:673-694`） |
| 文案 | `execute.dialectGate.rerenderUnavailable` 把原因、限制与三条建议压成一句（中英各一条） |
| 可操作性 | 错误行右侧只有「复制错误信息」；用户要自己找 `设置 > 语言与框架 > SQL 方言`，或想办法换数据源（已准备控制台会被复用，点执行不会重新弹选择器） |

结论：判定与文案都是对的，缺的是「出路」——两条真实可行的修复路径没有入口。

## 二、方案

### 1. 错误行增加两个就地入口（复用既有错误行形态，不新造控件）

`SqlResultPanel` 新增 `showErrorWithActions(...)`，在既有 `errorLocateButton`/`errorCopyButton` 同款样式上追加两个链接：

- **切换数据源**：调用 `executeSql(forceChoose = true)` → 跳过已准备控制台与 last-used 静默命中，重开数据源选择器（复用 T-DialectGateExit 的 `forceChoose` 语义）。
- **方言设置**：打开平台设置页「SQL 方言」（EP id = `SQL Dialects`；按 id 取 `Settings.find`，避免 `showSettingsDialog(project, Class)` 的整棵设置树实例化陷阱）。

动作只在 ERROR 态可见；离开错误态由统一 `clearErrorActions()` 清空 handler 与可见性（`doClick` 不看可见性，必须清 handler）。

### 2. 文案改成「原因一句 + 两条出路」

- `rerenderUnavailable`：`已阻止执行：当前 SQL 按 {0} 渲染，所选数据源是 {1}。可点「切换数据源」换一个匹配的目标，或打开「方言设置」把方言改为 {1} 后刷新预览。`
- `manualConflict`：同样补「可切换数据源，或把方言改为 {1} 后刷新」，保留「不覆盖手动选择 / 不暴露原生控制台」的既有承诺。
- 新增按钮文案与 tooltip（中英同步）。

### 3. 不改什么

- 不放松闸门：不匹配仍然阻断，不提供「强制执行」。
- 不在 `MANUAL_CONFLICT` 下自动改方言（手动选择优先是既有契约）。
- `TARGET_UNKNOWN_BLOCK` / `RENDER_UNKNOWN_BLOCK` 保持纯文案（没有可直接跳转的设置面）。

## 三、改动面

| 文件 | 改动 |
|---|---|
| `ui/SqlResultPanel.kt` | 新增两个动作按钮、`showErrorWithActions`、`clearErrorActions`、测试访问器；所有状态收敛点改用 `clearErrorActions()` |
| `ui/SqlPreviewPanelExecution.kt` | `onExecutionDialectGate` 两态走 `showDialectGateError`；新增 `showDialectGateError` / `openSqlDialectSettings`；`executeSql(forceChoose)` |
| `execute/SqlExecutor.kt`、`execute/DataSourceSelector.kt` | `execute`/`selectAndExecute` 透传 `forceChoose` |
| 两个 bundle | 两条文案改写 + 4 个新键（按钮与 tooltip） |
| `SqlResultPanelUiTest.kt` | 新增「两个入口可见/可点/离开错误态即清」用例 |

## 四、验收

- 自动化：`SqlResultPanelUiTest` 新增用例；`ExecutionDialectGateTest`、`DialectGateExitPolicyTest`、`BundleParityGuardTest` 回归。
- 反向判据：清空状态后 `doClick` 不得再触发旧 handler（用例内钉死）。
- 真机：阻断态错误行出现「切换数据源」「方言设置」；点前者弹出数据源选择器，点后者打开 SQL 方言设置页；非错误态不残留。

## 五、实施记录

| 日期 | 事项 |
|---|---|
| 2026-10-20 | 立项并实施（工作树 `mpsv-dialect-gate-ux`，自 dev `a91b73fe`）。 |
| 2026-10-20 | 定向：`SqlResultPanelUiTest` 15 + `ExecutionDialectGateTest` 17 + `DialectGateExitPolicyTest` 3 + `BundleParityGuardTest` 5 + `LogToSqlPanelExecuteTest` 18 = **58/0/0/0**；反向摘掉 `showStatus` 的 `clearErrorActions` → 恰 1 红，恢复后复绿。 |
| 2026-10-20 | **沙盒验收第一轮（243）**：文案已是新版，但错误行只有「复制错误信息」，两个入口未出现。根因：`DataSourceSelector.allowDialectGate` 在同一次调用栈里先 `onDialectGate`（挂动作）再 `onError`（纯文案），后者走 `showError → clearErrorActions` 把动作清掉。修：带动作的呈现延迟到下一拍 EDT；新增 `SqlPreviewPanelDialectGateUxTest` 2 例（17/0/0/0）。 |
| 2026-10-20 | **沙盒验收第二轮**：`切换数据源` 正常（弹出数据源选择器），`方言设置` 只打开到设置根页。根因：先 `ConfigurableVisitor.findById` 拿 wrapper 再 `showSettingsDialog(project, Configurable)`，两次 `ConfigurableGroup` 实例不同导致选不中。修：改用 `ShowSettingsUtilImpl.showSettingsDialog(project, "SQL Dialects", null)`（同一 group 内查找后建对话框）。 |
| 2026-10-20 | **沙盒验收第三轮（通过）**：闸门阻断红条出现「切换数据源」「方言设置」「复制错误信息」三项；点「方言设置」直接选中 设置 → Languages & Frameworks → SQL Dialects（表格列出 preview → PostgreSQL）；点「切换数据源」重开数据源选择器。证据帧 `build/gateux-shots/q3-gate.png`、`q4-settings.png`、`q5-datasource.png`（build 生命周期内，不入 git）。 |
