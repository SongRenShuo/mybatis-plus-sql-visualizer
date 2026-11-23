# T-StandaloneHintAndWarning：工作台空态提示失真 + 错误信息看不全 开发计划

- 立项时间：2026-11-18 13:59
- 任务源（用户原话）：「预览的 SQL显示在此处一选中代码按 Alt+Shift+Q可再次预览 没生效，还有这个错误信息无法查看完整的」
- 关联：`20261118-1247-T-StandaloneDialogOpen`、`20261118-1253-T-StandaloneIbatisRecognize`（同批缺陷，同文件；本计划是它们的直接后续）
- 状态：**根因已代码级定位，待实施**

## 一、问题一：空态提示承诺了一个在该场景根本不存在的快捷键

### 1.1 提示文案在哪

`panel.preview.emptyHint`（zh）＝「预览的 SQL 显示在此处——选中代码按 Alt+Shift+Q 可再次预览」，在 `ui/SqlPreviewPanel.kt:441` 读入、`ui/SqlPreviewPanelEditorLifecycle.kt:833` 作为预览编辑器的原生 placeholder（`setPlaceholder`）。**空文档时才显示**，所以用户是在预览区为空时看到的。

### 1.2 为什么按了没反应（确定性，不是偶发）

Alt+Shift+Q 绑的是 `ConvertToSqlAction`（plugin.xml:517-529）。它的启用判据在 `actions/ConvertToSqlAction.kt` 的 `update()`：

```kotlin
val file = e.getData(CommonDataKeys.PSI_FILE)
val extension = file?.virtualFile?.extension
e.presentation.isEnabledAndVisible =
    file is PsiJavaFile || file is XmlFile || extension == "kt" || extension == "kts"
```

独立工作台的输入编辑器是 `SqlInputEditor`，其 PSI 文件是 `input.sql`（标准 SQL FileType，见 `ui/SqlInputEditor.kt:74`）——既不是 `PsiJavaFile` 也不是 `XmlFile`，扩展名是 `sql`。**动作在整条链路里恒为 disabled**，按键被丢弃，界面零反馈。

### 1.3 判定

这条提示是从「编辑器里点预览」那条流复制过来的（那里 Alt+Shift+Q 确实有效：光标在 Wrapper 链或 mapper 语句上）。搬进工作台后，**动作的启用前提消失了，提示变成了假承诺**。两种修法只能二选一，且必须一起改文案：

- **A（推荐）让承诺成真**：在工作台里把 Alt+Shift+Q 接到「解析预览」上（组件级键绑定，`util/EditorCopyShortcuts.kt` 已有同款成熟手法：`editor.component.actionMap` + `inputMap.put(KeyStroke, key)`，WHEN_FOCUSED 覆盖默认绑定）。
- B 只改文案：把工作台的空态提示换成「点『解析预览』或按 Ctrl+Enter」之类，不动键位。

选 A 的理由：用户已经形成了「Alt+Shift+Q = 预览」的肌肉记忆（提示是他自己从别处学来的），改文案等于每次都教他换一套；而工作台里解析是纯文本操作、无副作用，接上去零风险。

### 1.4 文案改造（必做，与 A 配套）

`previewEmptyHint` 目前是 `SqlPreviewPanel` 的 `internal val`（构造期定格），无法按场景覆盖。需要：

- `SqlPreviewPanel` 构造签名加可选参数（默认 null = 沿用原键），`previewEmptyHint = override ?: bundle`；
- 工作台建面板时传工作台专用文案（新键 `standalone.preview.emptyHint`）：「预览结果显示在此处——在上面输入后按 Alt+Shift+Q 或点『解析预览』」；
- 「选中代码」这个前提在工作台不成立（它解析整段输入），必须去掉；
- 弹窗场景维持原文案（那里来源是编辑器，Alt+Shift+Q 语义仍成立）。

## 二、问题二：错误信息看不全

### 2.1 现状（三处截断，只有一处有 tooltip）

| 位置 | 现状 | tooltip |
|---|---|---|
| meta 行（红字，中间省略） | `applyMetaTruncation` → `metaLineWithinBudget` 中段省略，保留头尾 | **有**：`metaLabel.toolTipText = metaTooltipBase`（含完整 warning，`DialectStatusRefresh.kt:262`） |
| 警告横幅（⚠ 整行） | 单行 `JBLabel`，宽度不足由 Swing 尾部裁切 | **无**——`updateMeta` 调 `degradationBanner.show(Kind.RENDER_WARNING, bannerText)` **没传 tooltip 参数**（`SqlPreviewPanelResultView.kt:902-905`），而 `DegradationBanner.show` 的第三个形参 `tooltip` 默认 null（`DegradationBanner.kt:110`，注释明写「宽度不足时正文会被裁：调用方传入完整文案作 tooltip」） |
| 复制错误信息按钮 | 复制完整 `currentWarning` | — |

即：横幅这条**根本没接线**，用户盯着 ⚠ 那行当然看不全；meta 行虽有 tooltip，但中间省略看着像「信息被吃了」，且 tooltip 里还混着方言详情，不容易找到错误正文。

### 2.2 方案

- **F1 横幅接上 tooltip**：`show(...)` 补第三参 `bannerText`（一行改动，组件早就支持）。
- **F2 给一个「看全文」的确定入口**：点击横幅 → 弹出「完整提示」对话框（只读 `JBTextArea`，可选中、可复制、Esc 关闭）。理由：tooltip 只适合瞄一眼，用户要的是**能整段读、能选中复制**；这也顺带让「无法查看完整」不再依赖用户去猜悬停。
- **F3 meta 行 tooltip 分段**（可选，低成本）：把「警示正文」提到 tooltip 最前，方言详情退后——现在顺序是 meta/警示 → 方言 → 能力矩阵，方言段很长会把警示挤到视野外。

不做：横幅改多行（`JBLabel` 不换行，改 `JTextArea` 会动整个横幅布局与高度，风险大于收益）。

## 三、待验证前提（P-x）

- **P-1**：`editor.component.inputMap.put(alt shift Q)` 在 `SqlInputEditor` 上能覆盖全局 keymap 绑定（`EditorCopyShortcuts` 的先例是 Ctrl+C，同属 WHEN_FOCUSED 覆盖默认绑定；Alt+Shift+Q 是全局 AnAction 的键位，机制不同，需实测键盘事件是否先命中组件 InputMap）。**若不生效**，退路是给工作台注册一个 `AnAction` + `CustomShortcutSet` 挂在输入编辑器上，或改用 `EditorActionManager` 的 action（后者要 plugin.xml `<editorActions>`，改动面大）。
- **P-2**：`SqlPreviewPanel` 加构造参数不影响既有 6 个调用点（都有默认值即可）与 `previewEmptyHintForTest()`。
- **P-3**：点击横幅打开对话框在 headless 测试里能否断言（`DialogWrapper.show()` 之后立即 `isDisposed` 的现象已在上一批实测过，测试只认「被创建」事实）。

## 四、实施拆解

- **D-1 工作台键位**：`SqlStandalonePreviewPanel` 在 `inputEditor.editor.component` 上装 Alt+Shift+Q → `resolveFromInput()`；
  - 键位字符串 Windows/Linux `alt shift Q`；mac 上同名键位是 `meta shift Q`——按平台分别 put（对齐 plugin.xml 双 keymap 登记口径）。
- **D-2 空态文案覆盖**：`SqlPreviewPanel(project, emptyHint = ...)`；工作台传 `standalone.preview.emptyHint`；bundle 双语加键。
- **D-3 横幅 tooltip**：`updateMeta` 的 `RENDER_WARNING` 调用补 `bannerText`；`WRITE_RISK` / `DOLLAR_INJECT` 同批补（同样会截断）。
- **D-4 完整提示对话框**：`ui/WarningDetailDialog.kt`（`DialogWrapper`，只读文本区 + 「复制」+「关闭」）；横幅 `label` 加鼠标点击监听（HAND_CURSOR 提示可点）→ 打开对话框，内容 = `currentWarning`。
- **D-5 meta tooltip 分段**（F3）：把 warning 段放最前。
- **D-6 测试**
  - 纯逻辑：`standalone.preview.emptyHint` 双语键 parity（沿用既有 `StandaloneTabRegistryGuardTest` 的 parity 用例）；
  - 平台桶：工作台建面板后 `previewEmptyHintForTest()` 等于工作台专用文案（证 D-2 生效）；
  - 平台桶：按 Alt+Shift+Q 的 ActionMap 绑定存在（`inputMap.get(KeyStroke)` 非空 → 直接断言绑定键，避免 headless 键盘模拟不稳）；
  - 平台桶：`updateMeta` 后 `degradationBanner` 的 label tooltip == 完整 warning（证 D-3）——需给 `DegradationBanner` 补一个 `tooltipTextForTest()` 访问器。
  - 反向验证：把 tooltip 参数去掉、把 emptyHint 覆盖去掉，对应用例必须变红。

## 五、验收（A-x）

- **A-1** 工作台输入框里按 Alt+Shift+Q → 等同点「解析预览」（识别结果与状态条一致）。
- **A-2** 预览区为空时的提示文案不再提「选中代码」，且说的键（Alt+Shift+Q）真的有效。
- **A-3** 鼠标悬停 ⚠ 横幅 → tooltip 显示完整错误文本。
- **A-4** 点击 ⚠ 横幅 → 弹出完整提示对话框，可全选复制；内容与「复制错误信息」一致。
- **A-5** 既有行为不回退：编辑器里（Java/XML）Alt+Shift+Q 仍触发原预览；横幅的「重试」「✕」点击行为不变。
- **A-6** GUI 最终验收由用户做。

not run：mac 键位（本机无 macOS）、真机 tooltip 显示时机。

## 六、风险

- **P-1 不成立**时键位方案要换（见 P-1 退路），D-1 的测试（断言 InputMap 绑定存在）会先红——以它为准判断是否需要改路线。
- **横幅加点击**可能与既有「重试」「✕」标签的点击区域冲突：只在 CENTER 的 label 上挂监听，EAST 的动作区不动。
- **对话框**在极端长文本（数千字符）下需要滚动：用 `JBScrollPane` 包 `JBTextArea`。

## 七、实施记录

**实施于 2026-11-18（master 直接实施）。**

### 7.1 落位清单

| 落点 | 内容 |
|---|---|
| `ui/SqlPreviewPanel.kt` | 构造函数新增 `emptyHint: String? = null`（null = 沿用 `panel.preview.emptyHint`） |
| `ui/standalone/SqlStandalonePreviewPanel.kt` | `ResolveAction` 字段化并在 init 里 `registerCustomShortcutSet(reparseShortcuts(), this)`；内嵌面板传工作台专用 `emptyHint`；`reparseShortcuts()` 按平台给 `alt shift Q` / `meta shift Q` |
| `ui/SqlPreviewPanelResultView.kt` | `RENDER_WARNING` 横幅补 tooltip（第三参）＋挂 `onDetailsRequested` → `showWarningDetails()` |
| `ui/DegradationBanner.kt` | 新增 `onDetailsRequested`（正文点击回调）+ 手型指针 + `tooltipTextForTest()`；`clear()` 同步清 tooltip |
| `ui/WarningDetailDialog.kt`（新） | 完整提示对话框：只读可滚动文本区 + 复制 + 关闭 |
| bundle（en/zh） | `standalone.preview.emptyHint`、`panel.warningDetail.title`、`panel.warningDetail.copy` |

### 7.2 计划前提的落地校正

- **P-1（键位覆盖）**：采用 `AnAction.registerCustomShortcutSet(..., this)`——仓内既有先例是 `SqlPreviewPanel` 的格式化键（`SqlPreviewPanel.kt:893`，注释明写「组件级 custom shortcut 命中即消费按键，不与全局双触发」），比裸 Swing InputMap 更贴近平台语义。真机按键效果仍归 GUI 验收。
- **F3（meta tooltip 分段）不做**：meta 行的 tooltip 本来就含完整 warning（`metaTooltipCore` = meta + warningLine），重排收益低于改动面。
- **WRITE_RISK / DOLLAR_INJECT 横幅未补 tooltip**：两者文案来自 bundle、极短，不会被裁；只给 RENDER_WARNING（自由文本，含 include 片段缺失说明等长句）补。

### 7.3 验证读数

- **新增用例**：`WarningVisibilityTest` 4/4（emptyHint 覆盖 / 默认不变 / 横幅 tooltip 含完整 warning / 键位定义）、`SqlStandalonePreviewPanelDialogTest` 5/5（含「内嵌面板用工作台专用提示」）。
- **反向验证（两次读数）**：同时撤掉「横幅 tooltip」与「工作台 emptyHint」两处接线 → `WarningVisibilityTest.testBannerTooltipCarriesFullWarning` 与 `test工作台内嵌面板用工作台专用空态提示` **各红 1 条**（精确对应，无连带）；恢复后全绿。
- **回归**：`testFast` 全桶绿；横幅/i18n/面板相关 8 个平台测试类合计 **90 例 0 红**。
- **踩坑（在册坑复现）**：给 `SqlPreviewPanel` 构造器加参数后，**陈旧测试字节码**仍调旧签名 → 6 个测试类成片 `NoSuchMethodError: SqlPreviewPanel.<init>(Project)`；删 `build/classes/kotlin/test` 重编译即愈（与 `pitfall-stale-test-classes-after-data-class-field` 同族，本次是构造器而非 data class 字段）。
- **not run**：真机 Alt+Shift+Q 是否被全局动作抢（组件级快捷键在编辑器焦点下的实际行为）、tooltip 显示时机、对话框在极端长文本下的滚动 —— 均交用户 GUI 验收。
