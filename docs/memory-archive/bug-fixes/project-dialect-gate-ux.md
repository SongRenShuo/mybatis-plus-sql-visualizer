---
name: project-dialect-gate-ux
description: T-DialectGateUx——预览面板方言闸门阻断态红条加「切换数据源」「方言设置」两个蓝字链接（SqlResultPanel.showErrorWithActions）；20261102 补定案：放行态（MANUAL_CONFLICT/RERENDER_UNAVAILABLE）也必须保留这两个蓝字入口，且切源要 forceChoose+forceChoosePopup 就地弹列表、只换源不自动执行（W12 的「打开 Database 工具窗」已被否决）
metadata:
  node_type: memory
  type: project
  originSessionId: session_7ca70132cbb3e95555151b4bc731ecfb
---

# T-DialectGateUx：方言闸门阻断提示给出就地修复入口

2026-10-20 用户在沙盒验收时截图：预览底部红条「已阻止执行：当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL。当前模型无法无损重渲染……请重新生成 SQL 或切换目标后再执行。」诉求＝「提示不太友好，希望用户能快速解决，加一个快捷点击直接跳转到解决问题的地方」。

注意这条与 [[project-dialect-gate-exit]]（UI-040）**不是同一处**：UI-040 改的是**回滚弹窗**的出口行；本条改的是**预览面板结果行**的阻断态。

## 现状缺口（代码面）

- `onExecutionDialectGate` 对 `MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 只调 `resultPanel.showError(文案)`，错误行右侧只有「复制错误信息」。
- 用户要自己找 `设置 > 语言与框架 > SQL 方言`；想换数据源还换不成——`executeSql` 会先复用已准备控制台，再点执行不会重开选择器。所以文案里「切换目标后再执行」实际**不可达**。

## 实施形态

- `SqlResultPanel` 新增 `showErrorWithActions(error, fixLabel/fixTooltip/fix, settingsLabel/settingsTooltip/settings)`，在既有 `errorLocateButton`/`errorCopyButton` 同款样式上加两个链接，排在 EAST 容器的**最左**（错误越具体动作越靠前）。新增 `clearErrorActions()` 作为**全部非错误态与每次 showError 的统一起点**（清 handler + 可见性）。
- **`doClick` 不看可见性** ⇒ 只置 `isVisible=false` 不够，必须把 handler 置 null，否则旧动作会挂在成功/加载态上被误触（用例已钉死）。
- 预览侧：`MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 走 `showDialectGateError(verdict)`；`TARGET_UNKNOWN_*` / `RENDER_UNKNOWN_*` 保持纯文案（没有可直接跳转的设置面）。
- 「切换数据源」＝`executeSql(forceChoose = true)`：`forceChoose` 沿 `SqlExecutor.execute` → `DataSourceSelector.selectAndExecute` → `chooseAndPrepare` 透传，复用 UI-040 已建好的两处静默短路（`allowsSharedConsoleReuse` / `allowsSilentLastUsed`）；面板侧 `isPrepared() && !forceChoose` 决定是否复用已准备控制台。
- 「方言设置」＝打开平台页。**定位方式用 EP id 而不是 `Settings.find(Class)`**：`showSettingsDialog(project, Class)` 走 `ConfigurableVisitor.findByType`，会把整棵设置树实例化（Kotlin scripting 页构造器做文件名索引 → 真机 EDT 慢操作断言）；改用 `ConfigurableExtensionPointUtil.getConfigurableGroup(proj, true)` + `ConfigurableVisitor.findById("SQL Dialects", ...)`。该 id 来自 DatabaseTools 的 `META-INF/plugin.xml` / `SqlPlugin.xml` 里 `projectConfigurable id="SQL Dialects"`，类为 `com.intellij.sql.dialects.SqlDialectsConfigurable`。
- 文案改成「原因一句 + 两条出路」，中英各改 2 条 + 新增 4 个按钮键（`execute.dialectGate.action.switchDataSource`(+`.tip`)、`.openSettings`(+`.tip`)）。

## 编译期踩到的两个 API 事实（下一轮别再试）

1. `com.intellij.openapi.options.Settings` **没有 `getInstance`**（它是 `ex.Settings`，由 SettingsDialog 通过 DataKey 提供），写 `Settings.getInstance(proj)` 直接 Unresolved。
2. `ShowSettingsUtil.showSettingsDialog(project, String nameToSelect)` 的字符串参数是**显示名**（内部 `findPreselectedByDisplayName`），不是 EP id；按 id 取只能走上面那条 `findById`。

## 读数

- 定向 5 类 **58/0/0/0**（`SqlResultPanelUiTest` 15 含新增 1、`ExecutionDialectGateTest` 17、`DialectGateExitPolicyTest` 3、`LogToSqlPanelExecuteTest` 18、`BundleParityGuardTest` 5）。
- 反向：摘掉 `showStatus` 的 `clearErrorActions()` → 新增用例（两个入口可见/可点/离开错误态即清）**恰 1 红**，恢复后复绿。
- 提交 `f63d5f7f`（工作树 `mpsv-dialect-gate-ux` / 分支 `t-dialect-gate-ux`，自 dev `a91b73fe`），已 ff 合入 dev，**未 push**。计划：`docs/开发计划/20261020-1653-T-DialectGateUx-方言闸门阻断提示给出就地修复入口-开发计划.md`。

## 2026-11-02 补定案（用户报障「找不到在哪点」后）

- **放行态也必须保留这两个入口**。`8619a3af` + `75cff7c4` 把 MANUAL_CONFLICT / RERENDER_UNAVAILABLE 从「阻断」改成「放行」，预览侧改走 `resultPanel.showStatus(...)`；而 `showStatus` 第一行就 `clearErrorActions()`，把两个链接清掉，文案却仍写着「可点「切换数据源」」→ 用户找不到入口。正解＝新增 `showStatusWithActions`（与 `showStatus` 同款，只是不清动作），**不要**直接改 `showStatus` 的语义（还有别的调用方）。
- **形态是蓝色可点文字，不是按钮**（用户原话「这两应该是蓝色字体可直接点击」）。实现上无需新控件：`errorFixButton` / `errorSettingsButton` 本就是 `isOpaque=false`+无边框+11pt+`Link.activeForeground` 的透明按钮。顺带把 `errorSettingsButton` 的 fallback 从 `JBColor.GRAY` 对齐成 `JBColor.BLUE`。
- **切源动作改回「就地弹原生列表」**：W12 当年改成 `openDatabaseToolWindow` 被用户否决。同时纠正本文件上文「`executeSql(forceChoose=true)` 真弹选择器」的旧结论——**`forceChoosePopup` 只存在于 `prepareConsole`**，`selectAndExecute`/`SqlExecutor.execute`/`executeSql` 都没透传，runner 只剩一条时仍走 `shouldBeChosenIfAlone` 直选执行（W6 那次「点切换数据源却重试执行了」的真因）。正解＝`prepareConsole(forceChoose=true, forceChoosePopup=true)`（`executeAfterPrepare=false`，只换源不提交 SQL），或补 `forceChoosePopup` 透传。
- 计划：`docs/开发计划/20261102-1344-T-DialectGatePassDeadChain-…-开发计划.md`（含死链根因：放行前先投 `onSubmission(BLOCKED)` 把执行意图标完成 → `isPrepareActive()` 假 → 准备链静默早退、单飞门悬挂 60s）。

## 2026-11-02 再追加：换源入口必须常驻，且**不要赌原生下拉**

用户真机第二轮报障「点新数据源进行切换 没有反应」——他点的是**原生控制台工具栏的数据源下拉**（`数据源 / 分离数据源 / 所有 >` 那个弹层），不是插件蓝字。取证与结论：

- 那次点击在 `idea.log` 里**插件侧与平台侧都零痕迹**（连 DBN 的 console/session 行都没有），说明动作没落地；原生下拉在这条内嵌链上不可依赖（成因见 [[project-embedded-native-console-scope]]）。
- 状态行那两段蓝字**只在方言冲突态存在**，执行一失败就被 `showError` 顶掉——而"想换源"恰恰最常发生在执行失败之后。所以换源入口改为**双通道**：状态行蓝字（冲突态）+「更多」菜单常驻项（第二项，紧随回滚 SQL；`SqlPreviewTopRowLayoutTest` 的精确清单断言需同步登记）。
- `switchDataSourceInPlace()` 补入口锚点日志 `[M36][SwitchDs] entry: sql len=…, prepared=…`：换源是用户手动动作，日志必须能一眼区分「点了 / 没点」（本轮就是吃了零痕迹的亏，无从归因）。
- 提交 `93f1ac82`；插件已重新打包并热替换到用户日常 IDE（在役 jar 与构建产物 MD5 一致）。定向 4 类全绿。

## 2026-11-02 第三轮：原生下拉已接管 + 热路径回归

- **原生下拉不再是死项**：反编译 262 定位到 `Console.Jdbc.ChooseDataSource` = `ChooseDataSourceAction`（ActionGroup），点选＝「换源 + 立即执行 + `reattachConsole` 重挂新控制台」（详见 [[project-embedded-native-console-scope]]）。按用户定案「只换源不执行」用 `ActionManager.replaceAction` 接管，仅对 exec 文件生效、其它文件逐字委托原生组；提交 `eb66f362`。上文「不要赌原生下拉」的结论据此收窄为「原生下拉的**原始语义**不可用，但可接管」。
- **热路径回归（我引入的）**：为了判断「方言设置」链接显隐，在冲突状态渲染里调了 `findSqlDialectConfigurable` → `ConfigurableExtensionPointUtil.getConfigurableGroup(proj, true)` 实例化整棵设置树，第三方 configurable 抛 `PluginException`、EDT 1.2s 卡顿、异常把 `showStatusWithActions` 整段吞掉 → 面板停在转圈。修法：链接恒显示（点击时再处理）+ 渲染整段 `runCatching` 兜底；提交 `5c681d11`。详见 [[pitfall-settings-tree-instantiation-in-hot-path]]。
- 换源入口现为**三通道**：状态行两蓝字（冲突态）、「更多」菜单常驻项、原生工具栏下拉（接管后）。三者最终都走 `switchDataSourceInPlace(preferredRunnerName?)` → `prepareConsole(forceChoose=true, forceChoosePopup=名字为空)`。

## 关联

[[project-dialect-gate-exit]]（同一闸门的回滚弹窗出口，forceChoose 的出处）、[[feedback-ui-match-existing]]（两个链接照抄 errorLocateButton 形态）、[[project-preview-side-query]]、[[project-degraded-preview-ux]]。
