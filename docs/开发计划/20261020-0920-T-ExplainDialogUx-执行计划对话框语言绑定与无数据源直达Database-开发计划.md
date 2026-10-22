# T-ExplainDialogUx：执行计划对话框语言绑定与无数据源直达 Database

2026-10-20。来源：用户在 dev 分支真机验收执行计划时发现——弹框右下角「Cancel」是英文（插件为中文），且无绑定数据源时点「在控制台打开」没有反应。

## 一、现象与判定

- 错误对话框标题「执行计划」、正文「未绑定数据源，无法试算；请先在 Database 工具窗口选择数据源后重试。」、主按钮「在控制台打开」均为中文，唯独取消按钮显示 Cancel。
- 点「在控制台打开」后没有任何可见变化：执行链在弹窗后面跑、弹窗不关闭；且该文案在无数据源场景复用了 DML 影响面的「无法试算」，语境不符（UI-035）。

## 二、根因（代码证据）

- `ExplainPlanDialog` 的 `createActions()` 返回 `arrayOf(cancelAction)`，平台 `cancelAction` 文案跟随 IDE 语言；插件语言独立于 IDE，未调用 `setCancelButtonText`。
- `submitExplainDirect` 无数据源分支调用 `showExplainErrorDialog(dml.impact.noDataSource) { submitExplainDirectConsole(...) }`：按钮触发一次必然失败的执行，且错误对话框保持打开，把结果/选择器挡在后面。
- 文案键复用了 `dml.impact.noDataSource`（含「无法试算」）。

## 三、待验证前提（已核实）

1. `setCancelButtonText` 在 `init()` 后调用可覆盖平台文案（同 SqlPreviewDialog 既有做法）——成立。
2. `DataSourceSelector.openDatabaseToolWindow(project)` 可激活 Database 工具窗口——成立（既有内部函数）。
3. 按钮动作后 `dialog.close(0)` 可安全关闭（非模态、未 show 的测试路径也不抛）——成立（runCatching 兜底）。
4. 既有测试断言「失败/阻断场景按钮为在控制台打开」——保持不变（openDatabase 默认 false）。

## 四、方案与改动

- `ExplainPlanDialog.init()` 后 `setCancelButtonText(panel.explain.close)`（新键：关闭 / Close）。
- `showExplainErrorDialog(message, onOpenConsole, openDatabase=false)`：openDatabase=true 时按钮文案为「打开 Database 工具窗口」，动作调 `openDatabaseToolWindow`；否则维持「在控制台打开」。动作后统一关闭错误对话框。
- 无数据源分支改用新键 `panel.explain.noDataSource`（执行计划专用文案），并传 `openDatabase = true`。
- 新增测试访问器 `cancelButtonTextForTest()`；测试：无数据源按钮文案 + 取消按钮文案两例（拆分独立，便于反向各自命中）。

## 五、验证读数

- 定向：解释计划四类（侧查询面板、接线守卫、回退引导、回退通知）**22/0/0**（独立沙盒）。
- 反向：摘取消文案本地化 → 取消用例红；摘打开 Database 分支 → 按钮用例红（**恰 2 红**，独立命中），恢复后全绿、探针零残留。
- 提交：`d201aa52`。

## 六、遗留与注意

- 有数据源但旁路失败/阻断时仍保留「在控制台打开」（显式换编辑器入口），动作后弹窗关闭，用户能直接看到控制台。
- 真机复验待用户（需重建插件包）。
- 验收台账登记：UI-035。
