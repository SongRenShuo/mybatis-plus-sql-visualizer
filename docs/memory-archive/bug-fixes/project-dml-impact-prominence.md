---
name: project-dml-impact-prominence
description: UI-034（20261019 d65fb457）——预估影响行数结果改顶部独立横幅、删除探针预演通知、新增「复制 SQL 并关闭」蓝色主行动、返回预览主行动化；附「不能复用 DegradationBanner」与「主按钮样式」两条实现定式
metadata:
  node_type: memory
  type: project
  originSessionId: sess_13bd728a-c47f-4204-a267-f528b9f57f9d
---

用户真机反馈（dev 验收）：「预估影响行数」的结果只在预览窗口左下角灰字，不够明显；右下角弹框的气泡内容也不对；并建议「复制 SQL」右边加蓝色「复制 SQL 并关闭」、把「返回预览」改成明显按钮。已实施 **`d65fb457`**（dev，未 push），验收台账登记 **UI-034**。

## 四项改动

1. **结果改顶部独立横幅**：新增 `SqlPreviewPanel.dmlImpactBanner` + `dmlImpactBannerLabel`（蓝色 `Link.activeForeground` + 加粗；失败红色走 `ErrorForeground`），`showDmlImpactResult` 在内联 label 之外调 `showDmlImpactBanner(text, error)`；`clear()` 调 `clearDmlImpactBanner()`。左下角内联灰字**保留**（不删旧行为）。
2. **删除执行前的探针预演通知** `notifyDmlImpact(dml.impact.probe.title, probeBody)`——气泡讲的是「试算语句/目标表」而非结果，与内联结果并存时误导。探针详情保留在结果 tooltip（`probeBody`）。
3. **底部新增蓝色「复制 SQL 并关闭」**：`panel.copySqlAndClose`（zh「复制 SQL 并关闭」/ en "Copy SQL and Close"）；`SqlPreviewDialog.copyAndCloseAction` = `panel.copySql()` + `doDispose()`；按钮顺序 执行 SQL｜复制 SQL｜**复制并关闭**｜取消，经 `createJButtonForAction` 只给该 action 上主行动样式。
4. **「返回预览」主行动化**：`smallButton` → `primaryButton`（执行视图头部 EAST 位）。

## 两条实现定式（本轮实测得出）

- **不要复用 `DegradationBanner` 承载结果类提示**：它是「一个类别挂起」的单槽组件，且 DEGRADED 常驻时低优先类别 show 会被直接 return（测试里 `kindForTest()` 恒为 DEGRADED，两条新用例红）。正确做法＝**另起一行独立横幅**，在 `northHost` 的 SOUTH 里用一个纵向容器叠 `degradationBanner`（NORTH）+ `dmlImpactBanner`（CENTER），并存不抢位。
- **主行动按钮样式** `applyPrimaryButtonStyle(button)` / `primaryButton(text, action)`（`SqlPreviewPanelExecution.kt` 顶层）：`background = JBColor.namedColor("Button.default.startBackground", JBColor(0x3574F0, 0x548AF7))`、`foreground = Button.default.foreground`（默认白）、`isOpaque = true`、`isFocusPainted = false`。DialogWrapper 侧要蓝色按钮就 override `createJButtonForAction(action)` 判断 `action === 目标`。

## 测试与读数

- 测试缝：`SqlPreviewPanel.dmlImpactBannerForTest()` / `dmlImpactBannerTextForTest()`；`SqlPreviewDialog.actionsForTest()`（`createActions` 在 DialogWrapper 里是 protected，测试类里直接调会编译失败）。
- 定向 4 类（DML 影响面 / 预览弹窗 / 降级横幅 / 预览面板）**49/0/0**；反向摘结果横幅 + 摘复制后关闭 → **恰 3 红**，恢复后全绿、探针零残留。
- 复制断言别写死原文（`copySql()` 会剥注释并可能整形）：用 `contains("SELECT")` + `contains("FROM tbl")`。

## 同轮或邻近的对话框 UX 修正（2026-10-19，`d201aa52` / UI-035）

执行计划对话框（`ExplainPlanDialog`）同批修了两条同类问题，两条都是**对话框层的通用陷阱**，新写对话框一律照查：

- **取消按钮跟随 IDE 语言**：只写 `createActions() = arrayOf(cancelAction)` 时按钮文案来自平台 `CommonBundle`（英文 IDE 下显示 Cancel）。修法＝`init()` 后 `setCancelButtonText(MpSqlVisualizerBundle.message("panel.explain.close"))`。
- **错误对话框 + 动作按钮：动作后必须自关**。无数据源时按钮改「打开 Database 工具窗口」（`panel.explain.openDatabase` → `DataSourceSelector.openDatabaseToolWindow(proj)`），无数据源文案改用专用的 `panel.explain.noDataSource`（旧实现复用 `dml.impact.noDataSource`「无法试算」，语境不符）；**所有动作执行后 `dialog?.close(0)`**——否则数据库窗口/控制台被错误对话框挡在后面，用户看到的就是「点了没反应」。
- 测试缝：`ExplainPlanDialog.cancelButtonTextForTest()`（读 `getButtonMap()[cancelAction]?.text`）；两条用例**要拆成两条独立用例**（按钮文案 / 环境变量），否则反向验证时第一条断言失败会掩盖第二条，只红 1 条看不出第二个修复是否有效。
- 读数：解释计划四类 **22/0/0**；反向摘取消文案本地化 + 摘 openDatabase 分支 → **恰 2 红**（两条独立命中）。

## 20261021 同按钮链三个后续缺陷（UI-042 / UI-043 / UI-044，master 已提交）

用户装 `2026.10.16` 包在 master 真机验收，围绕这一个按钮连报三条，全部已修：

- **UI-042 按钮整个不显示**（`ab674aaa`）：`copyAndCloseAction` 声明在 `init {}` 之后 → 构造期 `createActions()` 读到 null → 平台静默不渲染。字节码直证（修前 `init()` 偏移 138 / 字段赋值偏移 203；修后 67 / 163）。**测试为何假绿**见 [[pitfall-fake-green-test-assertions]] ㉙（断言取自事后重算）、[[pitfall-dialog-field-declared-after-init]]。
- **UI-043 按钮多出一圈方角蓝底**（`47dc43e8`）：手工 `isOpaque=true + background` 与 Darcula「内缩 4px 圆角」边框冲突，底色铺满矩形溢出到圆角外。**修法＝改打平台标记** `putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, TRUE)`（client property `JButton.styleDefault`，`isDefaultButton()` 判据即它，平台 `ButtonPanelKt`/`OnboardingDialogButtons` 同款）+ `isOpaque = false`。**上文第 22 行那条「主行动按钮样式」实现定式已作废，按本条执行**；`primaryButton()` 的所有调用点（含「返回预览」）一并受益。
- **UI-044 复制后通知仍挂「关闭 SQL 面板」**（`e213009e`）：`copySql()` 无条件挂该动作，弹窗关了还留着链接。修法＝`copySql(offerClosePanelAction: Boolean = true)`，`copyAndCloseAction` 传 false。**测试为何漏**：单测模式根本不弹真通知，只断言点击行为就看不到「通知挂了哪些动作」；补测试缝 `copyNotificationActionsForTest`（**用 WeakHashMap 按面板实例挂——Kotlin 扩展属性不能有 backing field，`internal var X.y: T? = null` 直接编译错误**）。

读数：UI-042/043 定向 47/0/0（回滚 38 + 预览 9）、UI-044 定向 41/0/0（预览 10 + 面板 31）；各轮反向验证分别恰 3 红 / 恰 1 红 / 恰 2 红，探针零残留。

## 未采纳的方案（用户提过，供后续参考）

用户原始建议之一是「在预览窗口执行 COUNT、显示原生结果」——那会重新触发 [[project-preview-side-query]] 要消灭的「只读动作替换编辑器」问题，故未采用；如需展示原生结果网格，下一步应做成「点击横幅 → 弹框展示只读结果表」，而不是换掉编辑器。

相关：[[project-preview-side-query]]（旁路查询与 P1/P2/P3a 形态）、[[project-dml-impact-editor-replacement]]、[[feedback-ui-match-existing]]（UI 形态对齐既有实现）、[[pitfall-ui-promise-vs-model-decision-layer]]。
