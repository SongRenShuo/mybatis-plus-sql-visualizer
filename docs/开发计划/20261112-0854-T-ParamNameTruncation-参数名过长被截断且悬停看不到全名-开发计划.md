# T-ParamNameTruncation：预览左栏参数名过长被截断、悬停也看不到全名 — 开发计划

- 日期：2026-11-12 08:54
- 任务编号：T-ParamNameTruncation
- 性质：体验缺陷（**纯展示层**，不影响 SQL 文本、不影响回填/执行/复制产物）
- 来源：用户 2026-11-12 真机反馈（截图见 §2.1）「左侧参数名较长时候隐藏了，并且鼠标悬停也没有显示全面」
- 分支 / 工作树：待定（建议 `t-param-name-truncation` 从 `master` 切出）
- 产出定位：方案候选见 §四；**用户 2026-11-12 选定「只做 A」**（列宽实时自适应 + 全名 tooltip 恒可得，不加换行/弹层/可拖列宽）

---

## 一、需求拆解

一句话：预览弹框左栏的参数名（普通参数 `#xxx`、foreach 集合表达式、iBatis 占位符/iterate）在名字较长时被中段省略成 `criteria.crit…eria` 这类形态，**用户悬停也拿不到完整名字**，导致「这个参数到底是哪一层的」判断不了。

做三件事：

1. **让列宽跟随真实可用宽度**，而不是跟随「构建代码那一瞬间的宽度」；
2. **放宽截断上限**（现在硬编码 260px，约 35 个 ASCII 字符，MBG Example 这类嵌套名天然超长）；
3. **悬停兜底必须确定可得**——不管文本当前截没截断，全名都要能拿到。

不做：不改 SQL 渲染/回填/执行/复制/分享链；不动参数值状态表与预设召回；不改参数行控件顺序与交互契约（勾选、值输入、清除、全选/全不选）。

## 二、复现与证据

### 2.1 用户截图（MBG Example 预览）

左栏两段：

- 动态条件（复选框）：`distinct 真` / `_parameter != null 真` / `criteria.valid 真` / `criterion.noValue 假` / `criterion.singleValue` / `criterion.betweenValue` / `criterion.listValue` / `orderByClause != null` —— 全部**完整显示**；
- foreach 集合（标签 + 输入框 + 「为 null」）：`oredCriteria` 完整，但 `criteria.crit…` 与 `criteria.va…` **被中段省略**。

按截图几何反推：标签区约 83~90px 宽（标签起点 x≈37，输入框起点 x≈128，间距 8px），而弹窗整体约 1320px 宽、左栏约 490px——**左栏明明很宽，标签列却按 90px 排版**。这是本任务的第一手线索。

### 2.2 现状机制（源码级）

| 环节 | 代码 | 现状 |
|---|---|---|
| 列宽计算 | `SqlPreviewPanelParamPanel.kt:1562-1568` `labelColumnWidth(texts)` | `viewport = paramScroll.viewport.width`；`maxWidth = if (viewport <= 0) 260 else (viewport - 128).coerceIn(80, 260)`；结果 `texts.maxOf{宽}.coerceIn(80, maxWidth)` |
| 标签渲染 | 同文件 `:1547-1554` `columnLabel(fullText, columnWidth)` | 仅当 `stringWidth(fullText) > columnWidth` 时：设 `toolTipText = 全名` + `text = ellipsize(...)`；最后 `preferredSize = Dimension(columnWidth, h)` |
| 省略算法 | `SqlPreviewPanelText.kt:156-164` `ellipsizeMiddle` | 中段省略，保留尾部 30%（最少 4 字符） |
| 参数名入口 | `:1540` `paramLabel` → `columnLabel("#$source", w)` | 调用点 `:239`（批量 fixed 列）、`:280`（XML foreach + 参数）、`:319`（iBatis）、`:350`（Wrapper 普通参数）、`:1371`（iBatis iterate） |
| 复选框标签 | `:1585-1588` `checkBoxMaxWidth()`（100~280）、`:1592-1595` `checkBoxText` | 同一套「超宽中段省略」，宽度同样取自视口、同样只在构建时定稿 |
| 构建时机 | `SqlPreviewPanel.kt:142` `rebuildLeftPanel`，由 `showResult` 触发 | **左栏只在每次预览时建一次** |
| 尺寸变化 | `SqlPreviewPanel.kt:821-822` | **只有 meta 行走 `componentResized` 重算**；参数区与复选框一个都没有 |
| 左栏宽度来源 | `SqlPreviewPanel.kt:834` `OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f)` | 用户可拖、比例持久化，但拖完标签不重算 |
| 弹窗尺寸 | `SqlPreviewDialog.kt:60`（构造期 `panel.showResult(result)`）→ `:67` `init()` 之后才按屏宽 62% 定尺 | 建列宽时弹窗还没定尺寸 |
| 既有测试 | `SqlPreviewPanelTest.kt:537-561` | 只钉了**普通参数行**：列宽 140~260、超长截断、`toolTipText == 全名`、短名无 tooltip；**foreach 行与复选框零覆盖** |

### 2.3 根因（三层，按确定度排序）

**R1（确定，结构性）列宽在「布局前的瞬时宽度」上定稿，之后永不重算。**
`labelColumnWidth` 取 `paramScroll.viewport.width`，而 `showResult` 发生在 `SqlPreviewDialog` 构造期、`init()` 之前（`SqlPreviewDialog.kt:60` vs `:67`），此时弹窗还没按屏宽定尺、分隔条比例也没生效；同时 `paramScroll` 自身 `preferredSize = Dimension(240, 200)`（`SqlPreviewPanel.kt:229`）。截图反推的 ~90px 列宽正落在这个量级。**用户把弹窗拉宽、把分隔条拖到最宽都不会让标签变宽**——这是「隐藏了」的直接原因。

**R2（确定，设计上限）列宽上限硬编码 260px。**
即便重算，260px 也只有约 35 个 ASCII 字符。MBG Example 的集合表达式（`criteria.criteria`、`oredCriteria[0].criteria` 这类）与被引用链式参数名（`#productInfo.getDicProtocolType().getDeviceName()`）远超此长度，**任何窗口尺寸都不可能不截断**。

**R3（待现场确认）悬停兜底不可靠。**
`columnLabel` 的 tooltip 只在「构建时判定超宽」的分支里设置，且是单行纯文本：
- 列宽事后变化不会回写 tooltip，tooltip 状态与当前截断状态可能脱节；
- 单行 tooltip 超屏宽会被裁（Swing 对纯文本 tooltip 不折行）；
- 现有用例只覆盖普通参数行，**foreach 行标签的 tooltip 从未被钉过**。
代码层面 2026.11.8 / 11.10 / 11.11 / master 四个版本 `columnLabel` 逐字相同、截断时**确实设置**了全名 tooltip——所以「悬停完全看不到」这一现象与源码推断不符，需真机复现取证（见 §七 待验证前提 P1）。

## 三、影响面

| 面 | 现状 | 本任务是否触碰 |
|---|---|---|
| 普通参数行 `#source` | 中段省略 + tooltip 全名 | ✅ |
| foreach 集合行（`collectionExpr`） | 同上 | ✅（用户截图命中） |
| iBatis iterate / 占位符行 | 同上 | ✅（同一构造器/同一函数） |
| 批量列 fixed 标签 | 同上 | ✅ |
| 动态条件复选框（`checkBoxText`） | 超宽中段省略，tooltip 由调用方按需给 | ✅（同一族，宽度预算同源，需一并收口） |
| 批量 toggle 复选框 | 同上 | ✅ |
| SQL 文本 / 回填 / 执行 / 复制 / 分享 | 不受影响 | ❌ 不碰 |
| 参数值状态（`paramValues` / foreach / iBatis 值表）与预设召回 | 不受影响 | ❌ 不碰 |
| headless 测试 | `viewport.width == 0` 走 260 兜底分支 | ⚠️ 改上限会与 `SqlPreviewPanelTest.kt:551-552` 的「140~260」断言冲突，须同批更新用例口径 |

## 四、方案候选（五选一，或底座 + 加装）

### 方案 A：列宽实时自适应 + 全名 tooltip 恒可得（推荐）

- 把 `labelColumnWidth` 抽成**纯函数** `paramLabelColumnWidth(texts, viewportPx, reservePx)`，上限改成「视口按比例」（建议视口 55%、硬上限 480px、下限 120px），脱离组件即可单测；
- `paramScroll` viewport 挂 `ComponentAdapter`（**防抖 120ms**），尺寸变化时按新列宽**只回写已存在标签**的 `text / preferredSize / toolTipText`，不重建左栏；
- 全名登记到 `clientProperty`（`mpvs.label.fullText`），`columnLabel` 改为**无条件**设 tooltip，长名用 `<html>` 每 ~64 字符软折行，避免单行超屏被裁；
- 复选框标签（`checkBoxText`）同一套重算与登记，保证两侧口径一致。

成本：中（约 250 行含测试）；视觉形态不变（仍是单行 + 中段省略，只是「同样宽度能显示两倍字符」）；风险：低-中（重算只改文本度量，不触碰交互与状态）。

### 方案 B：超长名换行显示（最多两行，不悬停也能看全）

- 列宽仍自适应，但「顶到上限仍放不下」的标签改成两行：按 `.` / `_` 边界断行，`<html>` 两行 + 行高自适应；
- 优点：完整名常驻可见，不依赖悬停；
- 代价：同一分区内行高不齐（有的两行有的单行），BoxLayout 行高变化需要 invalidate 整链（UI-006 注释里记过 BoxLayout SizeRequirements 缓存问题）；MBG 三层嵌套名可能要三行才够。

成本：中；风险：中（行高/对齐回归面宽，需逐行真机看）。

### 方案 C：自绘悬停弹层（抄 `StackFrameHover` 的形态）

- 悬停 400ms 弹 `JBPopup`：标题为完整名（自动折行），可选「类型/来源」正文 + 底部「复制完整名」；
- 优点：绕开 Swing 单行 tooltip 的可见性/裁切问题，长名可读、可复制；
- 代价：五个标签面都要接线；popup 生命周期/Disposer 树、与滚动和焦点交互要处理；headless 下无法真实弹出，只能钉标题文案与接线；**仍解决不了「不悬停就看不到」**。

成本：中高；风险：中（有先例可抄：`ui/StackFrameHover.kt` 已实现同款 400ms + MouseAdapter + 复制动作）。

### 方案 D：标签列宽可拖 + 双击「适配最长名」+ 记忆

- 左栏改「参数名 | 值」可拖分栏（或标签列右侧 1~4px 拖拽手柄），双击手柄 ⇒ 列宽取本分区最长名（允许超视口、走横向滚动）；列宽按分区键记忆到 `PropertiesComponent`；
- 优点：用户完全掌控，适合「我们项目名字就是特别长」；
- 代价：平台没有可复用的横向列头，自研拖拽交互与 BoxLayout 单行结构耦合；还要做「拖完别把输入框挤出视口」的护栏。

成本：高；风险：中高。

### 方案 E：左栏表格化（`JBTable`：参数 | 值）

- 用表格列代替 BoxLayout 行：列宽可拖、单元格默认 tooltip、可排序过滤；
- 优点：一次性解决列宽/对齐/悬停，后续加列（类型、来源、出现次数）成本低；
- 代价：条件勾选行、foreach null 行、iBatis 行、批量列行、方案行、绑定态行、FieldFill 模拟行**全部重写**，等价于重做左栏。

成本：很高；风险：高。**建议不进本轮**。

### 对比

| 方案 | 修 R1 | 修 R2 | 修 R3 | 不悬停可见 | 改动量 | 风险 | 视觉形态 |
|---|---|---|---|---|---|---|---|
| A 自适应 + 恒 tooltip | ✅ | ✅ | ✅（tooltip 侧） | ❌ | 中 | 低-中 | 不变 |
| B 超长名换行 | ✅ | ✅ | — | ✅ | 中 | 中 | 行高不齐 |
| C 悬停弹层 | ❌ | ❌ | ✅（另起一套） | ❌ | 中高 | 中 | 不变 |
| D 列宽可拖 | ✅（用户拖） | ✅ | — | 部分 | 高 | 中高 | 不变 |
| E 表格化 | ✅ | ✅ | ✅ | ❌ | 很高 | 高 | 大变 |

### 推荐

**底座选 A**（它修的是 R1+R2 两个确定根因，且是 B/C/D 的公共前提），再按你的偏好加装一档：

- 只要「宽面板下能多显示、悬停一定拿得到全名」→ **A**（默认推荐）；
- 还要「不悬停也看到完整名」→ **A + B**；
- 还要「悬停要好读、能复制全名」→ **A + C**；
- 想自己拖列宽 → **A + D（只做双击自适应 + 记忆，砍掉拖拽手柄）**。

以用户截图这一例核对：`criteria.criteria` 约 105px，A 落地后在 490px 宽的左栏里会**完整显示**（列宽重算到内容宽度）；只有超过「视口 55%」的超长名才继续中段省略并走 tooltip/换行。

## 五、若选 A（底座）的实施步骤

1. **纯函数化 + 单测**：`paramLabelColumnWidth(texts, viewportPx, reservePx)` 脱组件；用例覆盖空清单 / 单短名 / 单超长名 / 混合 / 视口 0 兜底 / 视口极窄不越下界 / 顶到硬上限。
2. **标签登记与重算**：`columnLabel` 无条件写 `clientProperty` 全名 + tooltip（HTML 软折行）；新增 `relabelParamRowsForWidth(viewportPx)` 遍历已登记标签回写 `text/preferredSize/toolTipText`；`paramScroll` viewport 挂 `ComponentAdapter`（防抖 + `Disposer.register` 解绑）。
3. **复选框同口径**：`checkBoxText` 路径同样登记原文并按新预算重算（含动态条件区的 outcome 标注宽度扣减）。
4. **同步既有用例**：`SqlPreviewPanelTest.kt:551-552` 的「140~260」改成新口径（下限 + 统一性 + 硬上限）；补两条新用例：foreach 行标签 tooltip 全名、视口变宽后列宽/文本随之变化（headless 直接设 viewport 尺寸触发重算）。
5. **反向验证**：临时摘掉重算分支 ⇒ 新用例必须变红；摘掉「无条件 tooltip」⇒ tooltip 用例必须变红，两次读数都要贴出来。
6. **i18n**：A 方案预计零新增文案；若最终带「复制完整名」入口，文案要两套 properties 同步（`BundleParityGuardTest` / `BundlePlaceholderArityTest`）。
7. **真机验收（步骤见 §六，由你自己走）**；提交即回填 `CHANGELOG.md` 的 `[Unreleased]`（用户可感：长参数名显示更完整）。

## 六、真机验收步骤（GUI 交给你自己，助手只给判据）

1. 打开 MBG Example 的预览（截图同款场景）→ **预期**：`criteria.criteria` / `criteria.value` 两行标签完整显示，不再出现省略号；
2. 把左右分隔条拖到最左（左栏最窄）→ **预期**：标签回到中段省略，但「动态条件」分区标题右侧的全选/清除按钮**不被挤出视口**，且**不出现横向滚动条**；
3. 悬停被截断的任意标签 → **预期**：弹出 tooltip，内容为完整名字（长名折行，不被屏幕右缘裁掉）；
4. 拖动分隔条来回 → **预期**：标签宽度跟随变化，已填的参数值不丢、输入焦点不跳；
5. 切换语言为英文 → **预期**：新增文案（若有）双语正常。

失败时要给的日志/读数：`paramScroll.viewport.width`、当轮 `columnWidth`、标签 `preferredSize.width` 与 `text`（这三项建议做成 `internal` 测试缝，真机则临时打日志）。

## 七、待验证前提（实施前先证伪）

- **P1**：用户「悬停也没有显示全面」的成因**未确定**。源码在截断时确实设了全名 tooltip（2026.11.8/11.10/11.11/master 四版逐字相同，且普通参数行有单测钉住）。需现场取证：真机 hover 一次截图 + 读该标签的 `toolTipText`。若确认 tooltip 已设但不弹，再查 `IdeTooltipManager` 在该容器内的注册路径（**不要**先假定与 `preferredSize` 有因果关系——两者无关联，需实测排除）。
- **P2**：构建瞬间 `paramScroll.viewport.width` 的真实读数未实测（~213px 是据截图几何反推）。
- **P3**：截图像素 → 列宽 ~83~90px 的反推同样未用真机读数确认。
- **P4**：方案 A 的前提是「单行中段省略 + 全名 tooltip」这个形态你仍接受；若你要的是「不悬停就必须看全」，直接选 A+B。

## 八、红线

- 只改左栏展示：不碰 SQL 渲染/回填/执行/复制/分享任一条链；不动 `paramValues`、foreach/iBatis 值表、预设召回。
- **不重建左栏**（重建会丢输入焦点、丢未保存的可见文本、与 FieldFill 模拟态和「批次」标题按钮打架）——只改已有标签的 `text/preferredSize/toolTipText`。
- 所有 UI 改动在 EDT；重算只做文本度量，不做 PSI / 文件 / 网络。
- headless（`viewport == 0`）必须保留兜底路径，既有断言口径同批更新，不许让它们「意外变绿」。
- 新文案双语同步；`update/update.json` 不动。

---

## 九、实施记录（2026-11-12，方案 A）

提交：`2096fa2f5`（master）——代码 + 用例 + CHANGELOG 回填 + 本计划文件。

代码：

- `SqlPreviewPanel.kt`：标签/复选框登记表（`paramLabelRefs` / `paramCheckRefs`）、`paramScroll` 尺寸监听、
  防抖调度字段、`PROP_LABEL_FULL_TEXT` 锚点
- `SqlPreviewPanelParamPanel.kt`：列宽预算纯函数 `paramLabelColumnWidth` + 五个常量、
  `wrappedLabelTooltip` / `softWrapLines`（HTML 软折行）、`applyLabelColumn` / `applyCheckBoxLabel`
  （构建期与重排共用）、`relabelParamLabels` / `scheduleParamLabelRelabel`、
  五处标签面（普通参数 / foreach / iBatis 占位符与 `$` 直替 / 批量 fixed / 三类条件复选框）同口径接线、
  iBatis iterate 行改用本分区统一列宽
- `SqlPreviewPanelTest.kt`：既有用例口径更新（列宽 140~260 → 80~480；短/中名 tooltip 由「必须为 null」
  改为「恒为全名」）+ 新增 3 例（视口跟随、tooltip 折行、列宽预算纯函数）

分支与隔离：`t-param-name-truncation` @ `.worktrees/t-param-name-truncation`（从 master `5a5eba644` 切出）。
另起工作树的原因：本轮 master 工作树同时有两路会话在跑（`t-localdir-fix`、`t-mbg-parity`），
其中 `t-mbg-parity` 的未提交改动落在 `TrimSqlNode.kt` / `MbgExamplePreviewTest.kt`，两路共享同一个
`build/`——全包测试实测撞上 `NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`
（9 分钟白跑，且捞到的 `MbgExamplePreviewTest` 失败是别车道的在途状态）。故全部读数取隔离工作树。

定向读数（隔离工作树，`test` 桶 12 类）：**206 / 0 / 0**
（SqlPreviewPanelTest 34、IbatisXmlPreviewServiceTest 44、SqlPreviewPanelParamSchemesTest 29、
SqlPreviewPanelDynamicBatchTest 23、SqlPreviewPanelImportTest 22、XmlPreviewPanelTest 16、
FieldFillSimulationPanelTest 14、SqlPreviewTopRowLayoutTest 9、SqlPreviewPanelUiTest 6、
SqlPreviewPanelParamKindTest 5、SqlPreviewPanelParamFieldTest 2、SqlPreviewPanelForeachValuesTest 2）

反向验证（两轮成对读数）：

- 探针 A「宽度不跟随视口」（`relabelParamLabels` 的视口参数换成常量）→
  `testParamLabelColumnFollowsViewportWidth` 恰 1 红（34 例中 1 失败）
- 探针 B「tooltip 退回条件化」（只在截断时设 tooltip）→
  `testParamRowLabelWidthAdaptsAndTooltipForLongNames` 恰 1 红（34 例中 1 失败）

not run：全量 `testShards`、`verifyGate` / `verifyPlugin` / `buildPlugin`、真机 GUI（按 §六 步骤由用户走）。

仍待确认：§七 P1（悬停现场复现口径）、§六 五步真机验收。
