# T-PreviewInfoLayout：补齐框「从文件选择」无响应 + 结果区信息重复 + 性能建议被裁 开发计划

- 立项时间：2026-11-20 15:12
- 任务源（用户原话，附两张沙盒截图）：
  1. 「目前这个选择文件后 没有效果」（截图：`补齐 SQL 片段` 模态框，点右上角「从文件选择…」后文本框仍为空、框仍在）
  2. 「目前…ported).; Include refid 'Example_Where_Clause' not found in this mapper (cross-namespace include is not supported). 这些被折叠的信息 是不是和下方的有重复」（截图：预览面板 meta 区被裁成几行，正下方是黄色横幅与「缺少片段」行）
  3. 「还有 ORDER B…集，建议添加 LIMIT 这句话应该可以展开铺到上面一排按钮下方吧 目前是被挡住了」
- 关联：`20261118-1653-T-PreviewPolish`（补齐框 A 券立项处）、`20261120-1128-T-PreviewActionRow`（面板级「缺少片段」提示行）、`20261120-1244-T-Perf33MergeAlign`（补齐框读盘改后台，本轮问题一的改动来源）
- 状态：**仅立项，未动 `src/main`**

## 一、问题一：补齐框里「从文件选择…」点了没任何反应

### 1.1 事实（代码位置）

- 补齐框是**模态**的：`MissingFragmentDialog(project, refid) : DialogWrapper(project, true)`（`MissingFragmentDialog.kt:30`）。
- 按钮走 `pickFromFile()`（`MissingFragmentDialog.kt:78-90`）：`MapperFileLoader.chooseFile(project, …)` → 后台读 → `invokeLater` 回填。
- 而 `chooseFile` 传给平台的 **parentComponent 是 `null`**：`FileChooser.chooseFile(descriptor, project, null)`（`MapperFileLoader.kt:48`）。
- 同一处 `chooseFile(project, …)` 还被工作台的「从文件加载…」用（`SqlStandalonePreviewPanel.kt:403`），同样没传 parent。

### 1.2 为什么"没效果"（三个待证伪的机制，按可能性排序）

| # | 机制 | 判据 |
|---|---|---|
| M-1 | 模态框里弹文件选择器时**没有父组件**，选择器的 owner 解析不到当前模态框 → 被压在模态框后面 / 直接不显示，用户观感＝点了没反应 | 点按钮后 `idea.log` 无异常、窗口枚举里看不到新的 chooser 窗口；给 parent 后立即恢复 |
| M-2 | 调用抛异常并被上层吞掉（`FileChooser` 在模态上下文中要求 owner） | `idea.log` 有 `IllegalStateException`/`NullPointerException` 栈 |
| M-3 | 选择器正常返回但回填链断了（`MapperFileLoader.loadText` 返回 null，或 `invokeLater` 的 `isDisposed` 误判） | 有 chooser 窗口出现、能选中文件，但文本框仍空 |

M-1 是平台惯例问题：`FileChooser.chooseFile(descriptor, project, parentComponent)` 的第三参就是给模态场景用的
（现有两处调用都传了 `null`，此前在弹窗里也传 null，只是那时弹窗不是模态框的父级关系，问题没暴露）。

### 1.3 候选方案（问题一）

- **A（推荐）**：`MapperFileLoader.chooseFile` 加 `parentComponent: Component? = null` 参数并在两处调用点传
  自己的组件（补齐框传 `this.window` 或 `contentPane`；工作台传 `this@SqlStandalonePreviewPanel`）。
  这正是平台 API 的设计意图，改动小、无行为副作用，且顺手把工作台那条不稳的调用也修了。
- B：把补齐框改成非模态——与「补完再点确定」的交互矛盾，且非模态会让用户能同时改工作台，状态更难收敛。不推荐。
- C：补齐框内不再提供「从文件选择…」，让用户自己去工作台加载——退回上一轮已否决的形态（用户当时正是要这个入口）。不推荐。

## 二、问题二：meta 区被裁的那段，与下方确实重复

### 2.1 事实：同一份信息出现在两处

`updateMeta` 里：

- **meta 行**把警告拼进了可见文本：`metaCoreText = meta + warningLine`（`SqlPreviewPanelResultView.kt:874`），
  其中 `warningLine = "   ⚠ $currentWarning"`；
- **横幅**又展示同一条：`degradationBanner.show(RENDER_WARNING, renderFailed(currentWarning))`（`:904`），
  并带完整 tooltip 与「点击看全文」；
- **缺失片段**：解析器给的 warning 原文里就含 `Include refid 'X' not found…`，于是 meta 行与横幅都带一份；
  面板级「缺少片段：A、B —— 点此补上内容」行（`20261120-1128` 新增）又列出一次 refid。

| 信息 | 位置① | 位置② | 判定 |
|---|---|---|---|
| 渲染/解析警告 | meta 行尾（文字被裁） | 黄色横幅（完整 + tooltip + 点击全文 + 「复制错误信息」按钮） | **重复**，保留横幅，meta 侧删 |
| 缺失片段（原因句） | warning 原文（随上两处） | 「缺少片段」行（行动入口） | 语义不同（原因 vs 能做什么），不算纯重复；可在横幅文案里把 include 缺失段替换为「见下方补齐入口」（P-3） |
| 性能建议 | meta 第二行 | 无（只在 tooltip/复制里） | 不重复，但被挤（见问题三） |

### 2.2 候选方案（问题二）

- **A（推荐）**：meta 行**不再拼 `warningLine`**——警告只在横幅出现（横幅已具备完整文案、tooltip、点击全文、
  复制错误信息四件套）。meta 行只留「是什么」：类型 / 表 / 判型依据 / XML 语句 / 调用栈摘要。
- B：保留 meta 里的警告、去掉横幅——与「点击看全文」「复制错误信息」这些已交付能力冲突。不推荐。
- C：两者都留，只把 meta 那段改成「⚠ 有警告」极简标记（不写正文）——作为 A 的降级备选，若用户希望 meta 行
  仍能一眼看出「有问题」可采纳（P-2）。

## 三、问题三：性能建议挤在 meta 第二行并被裁断

### 3.1 事实

- `metaPerfSegments` 被渲染成 `metaLabel` 的**第二行**（`applyMetaTruncation` → `metaLabel.text = "<html>$line1<br>${perfLineHtml(...)}</html>"`，`SqlPreviewPanelResultView.kt:971`）；
- 预算 = `topRow 宽 − 工具栏理想宽 − 边距`（`:964`），工具栏常态有 7 个按钮（压缩/格式化/关键字换行/竖向排列/
  执行计划/刷新/更多，另有两个按需出现的 复制错误信息 / 预览目标），工作台侧栏一窄，perf 行只能拿到很少的宽度 →
  逐段丢尾并加 `…`，用户看到的就是「…集，建议添加 LIMIT」这种残句。
- 性能建议的信息本身没问题（`buildPerfSegments` 的 `⚠/ℹ + 文案`），是**落位**错了：它被塞进「和工具栏抢宽度的那一行」。

### 3.2 候选方案（问题三）

- **A（推荐）**：新增**性能建议行**，整行宽，放在 `topRow`（meta + 工具栏）**正下方**、横幅之上
  （`northHost` 内：`topRow` → `perfRow` → 绑定状态行 → 横幅/影响行/缺失片段行）。metaLabel 恢复单行，
  不再渲染 perf 第二行。多条建议用「；」连接、超宽整行省略 + tooltip 全文（沿用现有 `perfLineHtml` 的着色与省略策略，
  只是换个宿主）。
- B：perf 收进「更多」菜单或悬停——信息降级太狠（性能建议是「值得行动」级别的提示），不推荐。
- C：把 perf 当成一条横幅（与 RENDER_WARNING 同栈）——会与警告/降级/写风险/`${}` 提示抢同一个显示位
  （`DegradationBanner` 有优先级取舍），且性能建议常常与警告并存，会被挤掉。不推荐。

### 推荐：A（问题一 A + 问题二 A + 问题三 A）

一句话的目标形态：**meta 行只说「这是什么」，横幅说「出了什么问题」，性能建议单独一行说「能优化什么」，
缺失片段单独一行说「缺什么、点哪补」**——四类信息各占自己的位置，不再互相挤、不再重复。

## 四、实施拆解

- **D-0（先取证，仅问一）**：在沙盒里点一次「从文件选择…」，按 §1.2 的 M-1/M-2/M-3 三条判据取证
  （`idea.log` 有无异常、有无 chooser 窗口、是否有回填），**先证伪再改**——本轮计划的三条机制里只要 M-1 成立，
  D-1 就是充分修法；若落到 M-3，修法改成查后台读与 `isDisposed` 链。
- **D-1**：`MapperFileLoader.chooseFile(project, titleKey, parentComponent: Component? = null)`；
  `MissingFragmentDialog.pickFromFile` 传 `this.window`（拿不到时回落 `contentPane`）；工作台
  `LoadFileAction` 传 `this@SqlStandalonePreviewPanel`。用例：两个调用点各一条「parent 非空」的断言（测试缝注入）。
- **D-2**：`updateMeta` 去掉 `metaCoreText = meta + warningLine` 里的 warning 段（`warningLine` 仅在 banner 用）；
  `currentWarning` 的其它消费方（`metaLabel.foreground` 变红、`errorCopyButton` 可见性）保持不变。
  **注意悬停**：`metaTooltipCore` 目前由 `metaCoreText` 拼出来，删掉 warning 会连带 tooltip 也丢——实现时要把
  `warningLine` **单独补进 tooltip**（悬停仍能看到完整原因，只是不占可见行）。
- **D-3**：`SqlPreviewPanel` 新增 `perfRow`（`JLabel`/`JBLabel`，默认隐藏）与 `northHost` 的纵向栈重排；
  `applyMetaTruncation` 只负责 meta 单行截断，perf 行改为**整行预算**（宽度 = panel 宽 − 边距），
  超宽整行省略 + tooltip 全文；`fullDisplayText()` 相应简化（不再拼 `<br>`）。
- **D-4**：`DegradationBanner` 文案侧可选调整（P-3）：当 warning 的**唯一成因**是 include 缺失时，
  横幅尾部追加「（可用下方「缺少片段」补齐）」，避免"横幅说缺、下面又说一遍却像两件事"。
- **D-5**：测试面（现有 6 个类 14 处断言会受影响，需逐个核对）：
  `SqlPreviewTopRowLayoutTest`（第二行/预算断言）、`SqlPreviewPanelMetaHintTooltipTest`（tooltip 含 warning/perf）、
  `SqlPreviewPanelStackDigestTest`、`SqlPreviewPanelJpaBehaviorTest`、`XmlPreviewPanelTest`、`ParamImportDialogTest`；
  新增：`perfRow` 可见性/文案/超宽省略用例、meta 行不含 warning 的用例（反向钉住 D-2）。
- **D-6**：bundle：新增 `panel.perf.row` 前缀（若需要「性能建议：」引导）与「见下方补齐入口」提示；中英同步。

## 五、待验证前提（P-x）

- **P-1（问题一机制）**：M-1/M-2/M-3 到底哪个成立，必须在沙盒里先取证（D-0），否则修法可能打偏。
  尤其注意：`DialogWrapper` 的 `window` 在 `init()` 之前为 null，取 parent 必须在按钮回调里取（不是构造期）。
- **P-2（meta 是否保留极简标记）**：删掉 warning 文本后，meta 行靠颜色（`ErrorForeground`）区分「有警告」，
  用户若不满意再退到方案 C（`⚠ 有警告` 极简标记）。这是产品口径，需用户点头。
- **P-3（横幅与缺失片段入口的措辞）**：是否在横幅里追加「可用下方补齐」——涉及 warning 文案是否被改动（iBatis/MyBatis 两链同源）。
- **P-4（northHost 再叠一行的高度预算）**：目前 northHost 里已有 topRow / 绑定状态行 / 降级横幅 / DML 影响行 /
  缺失片段行；再插 perfRow 需确认窄面板下不互相挤压（四行同时出现的最坏形态：警告 + 影响行 + 缺片段 + 性能建议）。
- **P-5（meta 断言的契约变化）**：14 处断言里有几处把「meta 文本包含 warning」当契约写死，属**有意变更**，
  要在提交信息与计划里写明，避免后来者当作回归。
- **P-6（性能建议的多条形态）**：`buildPerfSegments` 可能产出多条（如 `SELECT *` + 缺 `LIMIT`），
  整行版要定「全部平铺」还是「最多 N 条 + …」；建议全部平铺（性能建议通常 ≤3 条），超宽整行省略。
- **P-7（工作台与弹窗两处 meta 行的差异）**：弹窗宽度通常够，perf 行在弹窗里可能本来就没被裁；本轮改动要保证**两处同形**，
  不要只修工作台。

## 六、验收（A-x）

- **A-1** 补齐框点「从文件选择…」→ 弹出文件选择器（在模态框**前面**、可操作）→ 选中 mapper XML 后文本框出现
  对应 `<sql id="…">` 的内容（找不到则整段带入）；框不再被挡。
- **A-2** 工作台「从文件加载…」依旧可用，且选择器归属工作台窗口（不再落到主窗口后面）。
- **A-3** 预览面板 meta 行**不再出现**「⚠ …」警告正文；警告只在黄色横幅（含 tooltip 与点击全文）；
  meta 行变红与「复制错误信息」按钮仍随警告出现。
- **A-4** 性能建议独占一行（`topRow` 下方、横幅上方），整行宽度；窄侧栏下不再出现「…集，建议添加 LIMIT」这种半句，
  超宽时整行尾部省略且 tooltip 给全文。
- **A-5** 最坏形态并排检查：警告横幅 + 性能建议行 + 缺失片段行 + DML 影响行同时存在时不互相遮挡、不超高挤压（P-4）。
- **A-6** 弹窗侧同形：弹窗内 meta 行同样不含警告、性能建议同样独占一行。
- **A-7** 「复制错误信息」复制内容仍为完整 warning（不因 D-2 丢字段）。

## 七、风险

- 三处改动都在**共享面板**（22 文件 / 53 处 `showResult` 调用点共用）上；meta/perf 行是「有守卫测试盯着」的区域
  （`SqlPreviewTopRowLayoutTest` 的预算与两行断言），改布局必须同批改断言并在提交信息里说明契约变化（P-5）。
- 问题一的修法可能只解决"看见选择器"，若同时存在 M-3（回填链断），需一并处理，否则用户仍会觉得"选了没用"。
- 若 P-2 决定保留 `⚠ 有警告` 极简标记，则 meta 行仍是「可被截断」的文本，窄宽度下要注意别把「表名/判型依据」挤掉。

## 八、实施记录

实施于 2026-11-20（master `54af4e9f3`，含测试契约修正的后续笔）。

### 8.1 逐项落点

- **D-1（问题一）**：`MapperFileLoader.chooseFile(project, titleKey, parentComponent)` —— 改走**带父组件**的
  `FileChooser.chooseFile(descriptor, parent, project, null)` 重载；补齐框传 `window ?: contentPane`，
  工作台「从文件加载…」传面板自身。`pickFromFile` 的「选 → 后台读 → 回 EDT 回填」三段与
  `loadFileGeneration`/`isDisposed` 守卫保持原样（本项只改选择器的归属窗口）。
- **D-2（问题二）**：`metaCoreText = meta`（不再拼 `warningLine`）；**`warningLine` 单独补进
  `metaTooltipCore`**，悬停仍能看完整原因。横幅、`metaLabel` 变红、`errorCopyButton` 可见性、
  `currentWarning` 的全部消费点未变。
- **D-3（问题三）**：新增 `perfLabel` + `perfRow`（`northHost` 内、`topRow` 正下方），
  `applyPerfRow()` 按**行自身宽度**预算着色与「丢尾段 + …」；无建议时整行 `isVisible=false`；
  `applyMetaTruncation` 只做 meta 单行（`fullDisplayText()` 退化为 `metaLineText`）；`clear()` 同步复位。
- **D-5（测试）**：新增 `SqlPreviewPanelInfoLayoutTest`（4 例：警告只在横幅 / 性能建议独占整行 /
  无建议隐藏 / 清空复位）；同步更新 `SqlPreviewTopRowLayoutTest`、`SqlPreviewPanelJpaBehaviorTest`、
  `XmlPreviewPanelTest`、`SqlStandaloneActionRowTest` 的契约断言。
- **用户口头追加（工具栏分区二次调整）**：清空与在弹框中打开**回到顶部图标区**，底部只留
  「执行 SQL / 复制 SQL」——按「同一动作不两处重复」+「用户按位置记忆随时可用的操作」取舍。
- **未做（待拍板）**：**P-2**（meta 是否保留「⚠ 有警告」极简标记）、**P-3**（横幅是否追加
  「可用下方补齐片段」）、**D-4**（依赖 P-3）。D-6 bundle 未新增键——本次没有新增面向用户的文案串
  （性能建议行沿用既有 `perf.*` 文案，无「性能建议：」前缀）。

### 8.2 读数

- 定向（10 类）：**99 例 0 红**——`SqlPreviewPanelInfoLayoutTest` 4 / `SqlPreviewTopRowLayoutTest` 9 /
  `SqlPreviewPanelMetaHintTooltipTest` 7 / `SqlPreviewPanelJpaBehaviorTest` 4 / `XmlPreviewPanelTest` 16 /
  `ParamImportDialogTest` 15 / `SqlPreviewPanelStackDigestTest` 18 / `SqlStandaloneActionRowTest` 4 /
  `SqlStandaloneLoadFileTest` 6 / `SqlPreviewDialogTest` 16。
- 面板全家桶：`SqlPreviewPanel*` + `SqlPreviewTopRowLayoutTest` + `SqlPreviewDialogTest` + `standalone.*`
  共 **389 例，首跑 1 红**：`SqlPreviewPanelBindStateTest.test指示行迁顶栏更多按钮下方` —— 断言
  「工具栏行与指示行同一个父容器」，而本轮在两者之间插入了性能建议行（层级多一层）。
  **处置＝改判据不改结构**：改成「指示行仍挂北区宿主 CENTER」「工具栏仍在 NORTH 槽位内
  （`SwingUtilities.isDescendingFrom`）」——保住原意（指示行紧跟工具栏区、不回参数区），
  且不再对中间插一层容器过敏。
- **反向验证**（逐个打、各自还原）：
  1. `metaCoreText = meta + warningLine` → `test警告只在横幅_meta可见行不再重复` **1 红**（贴出读数：
     meta 行出现「⚠ Include refid 'Cols' not found…」）；
  2. 把 perf 塞回 `metaLabel` 的 `<br>` 第二行并停掉 `applyPerfRow()` → **2 红**
     （`test性能建议独立整行_不参与meta宽度预算`、`test清空后performance行复位`）。

### 8.3 遗留

- **A-1 / A-2 只能真机验**：`FileChooser` 的归属窗口在 headless 里无法断言（没有真实模态窗口栈），
  需在沙盒里点一次「从文件选择…」与「从文件加载…」，确认选择器弹在**前台**可选。
- P-2 / P-3 / D-4 待用户拍板；若采纳 P-3，改动落在横幅文案（`panel.banner.renderFailed` 的调用点）与 bundle 中英各一条。
- `SqlPreviewPanelBindStateTest` 的判据放宽属**有意的契约调整**，已在提交信息与本记录写明，不是被顺手改绿的。
