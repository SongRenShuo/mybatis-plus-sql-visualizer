# T-EmbeddedConsoleExecRestore 内嵌控制台执行入口恢复（Ctrl+Enter 接插件链 + 原生 ▶ 说明）— 开发计划

- 日期：2026-11-09 09:24
- 任务编号：T-EmbeddedConsoleExecRestore
- 来源：T-SecurityAuditFixes 的 T-SAF-04（D1=A，提交 `10e26eeba`）落地后，内嵌预览控制台的 ▶ / Ctrl+Enter 恒禁用；用户 2026-11-09 要求「这两个可以执行」
- 性质：**体验修复，不改安全姿态**——不恢复原生执行入口，改为把 Ctrl+Enter 接到插件既有执行链，并让「原生 ▶ 为什么不能用」可见
- 用户定案（2026-11-09）：走路线 **B（Ctrl+Enter 走插件链）+ tooltip/说明**；**不叠加路线 D**（不恢复原生入口、不加「跳过确认链」开关）
- 关联：`docs/安全审计/2026-11-06/`（T-SAF-04 账本条目）、`docs/开发计划/20261106-2330-T-SecurityAuditFixes-安全审计run1线索修复-开发计划.md`

---

## 一、现状与目标

### 现状

`execute/DataSourceSelectorConsole.kt:283-302`（T-SAF-04）不再向 `UiDataProvider` 注入 `CommonDataKeys.EDITOR` 与 `SessionClientHolder.CLIENT_KEY`。平台原生执行动作（`QueryActionBase.update` 的启用链）要求这两个数据键，缺失即恒禁用——内嵌控制台里的 ▶ 与 Ctrl+Enter 因此点不动。

执行只剩两条插件入口：

- 弹窗底部「执行 SQL」（`SqlPreviewDialog.createActions` → `panel.executeSql()`）
- 面板工具栏动作（同一 `executeSql()`）

关键事实：`executeSql()` 第一步读的是 `nativeEditorDoc?.text`（`SqlPreviewPanelExecution.kt:1134-1136`），即**控制台当前文档**（含用户手改），与原生 ▶ 执行的是同一份文本。两条路的差别只在门禁链：插件链走 `SqlTypeClassifier` DML 确认、`${}` 原文确认、未知参数确认、`ExecutionDialectGate` 方言闸、`SchemaValidationGate`。

### 目标

1. 内嵌面板内 **Ctrl+Enter** 执行当前控制台文本，且走插件全门禁链（与「执行 SQL」同一入口，不新增执行路径）；
2. 「原生 ▶ 为什么灰」有**可见解释**，不让用户以为功能坏了；
3. 不降低安全性质：仍然只有一条执行路径，仍然全门禁。

### 非目标

- 不恢复原生执行入口（不重新注入 `EDITOR`/`CLIENT_KEY`），不引入「跳过确认链」开关（路线 D 已由用户否决）；
- 不做「只执行光标处语句 / 选中段」——原生 ▶ 与插件链在这点上有真实能力差异（插件链传整段文档），但属独立能力项，另立项；
- 不改动任何执行门禁链本身的语义。

---

## 二、待验证前提（实施者先证伪再动手）

| 编号 | 前提 | 若被证伪的处置 |
|---|---|---|
| P-1 | Swing 的 `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` 绑定先于 IDE keymap 派发——焦点在控制台编辑器内时按 Ctrl+Enter 能被我们抢到 | 改挂控制台编辑器 `contentComponent` 的 `WHEN_FOCUSED` InputMap（P-2 备选路径） |
| P-2 | 控制台编辑器组件在 `SqlPreviewPanel` 的组件树内（`consoleHost` 之下），祖先绑定能覆盖它 | 若控制台被平台重挂到 `ServiceViewComponentContainer` 之外，需在 `embedConsole` 单点钩子里补挂 |
| P-3 | 平台工具栏按钮的 tooltip 不会在每次 action update 时被回写覆盖 | 放弃原生 ▶ tooltip，只保留 A1/A2（说明落在插件自有控件上） |
| P-4 | `executeSql()` 在控制台尚未 attach 数据源时走既有 `chooseAndPrepare` 选择链，不直接失败 | 若直接失败，Ctrl+Enter 需在无源时给出与按钮一致的引导 |
| P-5 | 面板 dispose 时按键绑定随组件树释放，无残留（无全局 keymap 注册） | 若残留，改为在 dispose 里显式清 InputMap |

---

## 三、实施（三券）

### 券 A：说明面（让「灰」可解释）

- **A1（必做）** 弹窗「执行 SQL」按钮加 tooltip：写明 `Ctrl+Enter` 同效，以及「内嵌控制台的原生 ▶ 已停用（执行统一走插件确认链）」。落点 `ui/SqlPreviewDialog.kt:91-96`，用 `templatePresentation.description`。
- **A2（必做）** 执行视图头部提示追加一句：现 `panel.executeViewHint`（`SqlPreviewPanelExecution.kt:1958`）只写「执行视图」语义，补「Ctrl+Enter 可执行」。文案走 bundle 双语。
- **A3（尽力而为）** 尝试给原生 ▶ 按钮挂 tooltip：遍历控制台组件树里的 `ActionToolbar`/`ActionButton`，匹配执行动作后 `setToolTipText`。受 P-3 约束——若平台每帧回写，则本项作废并在本文件回填「已证伪，A3 不做」，不硬扛。

### 券 B：Ctrl+Enter 键绑定

- **B1** 在 `SqlPreviewPanel.init`（`ui/SqlPreviewPanel.kt:772`）注册按键：
  - `KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)` → action key `mpvs.execute`
  - 追加 `meta ENTER`（macOS 习惯；Windows 上 meta+Enter 无冲突）
  - 绑定挂 `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT`（覆盖面板内任意焦点，含控制台编辑器，受 P-1/P-2 约束）
  - action 体只做一件事：`executeSql()`——**不新增执行路径**
- **B2** 幂等与可见性：绑定在 `init` 只做一次；触发时校验 `isShowing`（面板被隐藏时不响应，避免后台面板抢键）。
- **B3** 键位口径：本轮**硬编码 Ctrl+Enter**，不读 keymap（平台控制台执行动作 id 未取证，见 §六 未验项）。若用户自定义键位不同，属已知边界，后续可加 keymap 读取。

### 券 C：测试与守卫

- **C1** `BasePlatformTestCase`：构造 `SqlPreviewPanel`，断言 `getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ctrlEnter)` 非空且 `actionMap` 有对应 action；触发一次，经测试缝断言走到 `executeSql`（不真连库）。
- **C2** 反向验证：临时移除绑定 → C1 必须转红（证明断言看得见生效点，而不是平台原生动作恰好还在）。两次读数都记入本文件实施记录。
- **C3** 若新增源码扫描类守卫，登记进 `GuardSelfTestCoverageMetaGuardTest`（本券预期不新增扫描守卫，C1 是行为测试）。
- **C4** 注意桶位：`BasePlatformTestCase` 属平台 `test` 桶，**不在 `testFast`**；定向命令用 `--tests`，并核对 XML 真跑了。

---

## 四、验收标准

1. **行为（真机，用户执行）**：控制台文档放一条 `UPDATE ...`，按 Ctrl+Enter → 弹出插件的「执行确认」对话框（原生入口不会弹）；取消后无执行。这是「走的是插件链而不是原生链」的判别点。
2. **行为（真机）**：同一操作点「执行 SQL」按钮，表现与 Ctrl+Enter 一致。
3. **说明可见（真机）**：悬停「执行 SQL」按钮能看到 Ctrl+Enter 说明；执行视图头部提示含 Ctrl+Enter。
4. **反向验证**：注释掉绑定 → Ctrl+Enter 无反应，且不触发任何执行（证明生效点是新绑定）。
5. **回归**：定向测试绿；`testFast` 不新增红（现存 4 红为文档站 goldendb 漂移，与本任务无关）。
6. **回填**：`CHANGELOG.md` 的 `[Unreleased] / Added`（或 Changed）一条；涉及 T-SAF-04 的口径补充写进该条目，不新开冲突叙述。

---

## 五、风险

| 编号 | 风险 | 处置 |
|---|---|---|
| R1 | P-1 不成立（平台先吃 Ctrl+Enter） | 回落 P-2 备选（挂编辑器 contentComponent 的 InputMap）；仍不引入原生入口 |
| R2 | 焦点在 SQL 预览编辑器时 Ctrl+Enter 也执行——比之前「只在控制台生效」范围更大 | 视为口径扩展（面板内统一执行键），在 CHANGELOG 写明；若用户要求收窄到控制台，改挂 `consoleHost` 子树 |
| R3 | macOS 上 `meta ENTER` 与其他动作冲突 | 真机验；冲突则只保留 Ctrl+Enter |
| R4 | 用户把「Ctrl+Enter 能执行」误读为「原生 ▶ 也回来了」 | A1/A2 文案明确写「原生 ▶ 仍停用」，不用模糊措辞 |

---

## 六、未验项（实施前声明）

- 平台 DB 控制台执行动作的 **action id** 未取证（本地 IDE 版本与构建基准不同，jar 里未定位到 `QueryActionBase`/`ExecuteQueryAction`）——故 B3 采用硬编码键位，不读 keymap。
- 原生 ▶ 按钮在组件树中的稳定定位路径未取证（P-3）——A3 属尽力项。

---

## 七、执行记录

**实施（2026-11-09，未提交，待用户确认）**

| 券 | 落点 | 结果 |
|---|---|---|
| A1 | `ui/SqlPreviewDialog.kt`：执行动作提升为 `executeAction` 字段；`createJButtonForAction` 按动作身份挂 tooltip；新增 bundle 键 `panel.executeSql.tooltip`（zh/en） | 完成 |
| A2 | `ui/SqlPreviewPanelExecution.kt` 的 `executeViewHeader` 复用键 `panel.executeViewHint`，文案补「Ctrl+Enter 可再次执行」（zh/en） | 完成 |
| A3 | 原生 ▶ tooltip | **不做**（见下方「A3 决策」） |
| B1 | `ui/SqlPreviewPanel.kt` 新增 `executeShortcutAction` + `executeShortcuts()`（`ui/SqlPreviewPanelSqlView.kt`），`init` 里 `registerCustomShortcutSet(…, this)`，注册形态照抄既有 `reformatAction` | 完成 |
| B2 | 触发时 `previewDisposed` 早退；`isShowing` 判据**不需要**（custom shortcut 只在聚焦窗口内派发） | 完成（口径调整） |
| C1 | 新增 `ui/SqlPreviewPanelExecuteShortcutTest.kt`（3 例） | 3/0/0/0 |
| C2 | 反向验证：注释掉 `registerCustomShortcutSet` 后重跑 | 3 例中 1 例转红（`test面板init把执行快捷键注册到动作上`），EXIT=1；恢复后回到 3/0/0/0 |

**A3 决策（不做，附理由）**：平台 DB 控制台的 ▶ 是 `ActionButton` 包着平台 `AnAction`，其 tooltip 来自 `ActionButton.update()` 每次按 action presentation 回写——我们写上去的文本会在下一次 action update 时被覆盖；且按钮定位只能靠动作类名/文案匹配（本地 IDE 版本与构建基准不同，执行动作类名未取证，文案还随语言包变）。为一段「可能生效也可能被覆盖、且依赖文案匹配」的 UI 代码引入脆弱面不划算，故改为 A1+A2 两条稳定落点承接「写明」的诉求：用户点不到 ▶ 时，唯一会主动悬停的地方是仍在工作的执行按钮。

**定向读数**：`SqlPreviewPanelExecuteShortcutTest` 3/0/0/0；`ExplainPanelWiringGuardTest` 16/0/0/0（`panel.executeViewHint` 键存在性断言未受影响）；bundle 三守卫 `BundlePlaceholderArityTest` 4/0/0/0、`BundleParityGuardTest` 5/0/0/0、`MpSqlVisualizerBundleLocaleTest` 3/0/0/0。

**未验（须真机，用户执行）**：P-1（Swing 祖先绑定 vs IDE keymap 派发的先后）只能靠真机判；判据见 §四 验收标准 1——控制台放一条 `UPDATE`，按 Ctrl+Enter 应弹插件「执行确认」对话框（原生链不会弹），取消后无执行。
