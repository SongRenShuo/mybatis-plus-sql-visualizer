# T-PreviewActionRow：工作台底部动作行 + 弹窗内补齐缺失片段 开发计划

- 立项时间：2026-11-20 11:28
- 任务源（用户原话）：
  1. 「sql预览这个tab下面 缺少下方的四个按钮吧」（附工作台截图：底部只有「缺少片段…」与「识别为 mapper XML 语句块」两行，没有动作按钮）
  2. 「我用弹框预览 xml 的内容没地方补充了 看看怎么优化下更合适」（附弹窗截图：`/* TODO: missing <sql id="Base_Column_List"> */` 占位在，但弹窗里没有任何补内容入口）
- 关联：`20261120-1022-T-LoadFilePlacement`（上一轮把「从文件加载」从弹窗移到工作台，是问题二的叠加成因）、`20261118-1653-T-PreviewPolish`（补齐片段框 A 券立项处）
- 状态：**已实施（master `81ebb4720`），待 GUI 验收**

## 一、问题一：工作台没有「产物动作行」，复制 SQL 在页签里根本不可达

### 1.1 事实（代码位置 + 截图）

工作台底部现在只放两行文字，没有任何按钮：

- `SqlStandalonePreviewPanel.kt:163-169`：`BorderLayout.SOUTH` = `missingFragmentLabel`（NORTH）+ `statusLabel`（SOUTH）。
- 产物类动作全在**顶部图标工具栏**（`:137-146` 的 `inputActions`）：解析预览 / 执行 SQL / 格式化 / 清空 / 从文件加载… / 在弹框中打开。

`复制 SQL` 全仓只有弹窗一个入口：

| 位置 | 有无复制 SQL |
|---|---|
| `SqlPreviewDialog.createActions`（`SqlPreviewDialog.kt:197-213`） | **有**（执行 SQL / 复制 SQL / 复制 SQL 并关闭 / 取消） |
| 面板工具栏 `SqlPreviewPanelResultView.kt:48-81` | 无（复制错误信息 / 压缩 / 格式化 / 关键字换行 / 竖向排列 / 执行计划 / 刷新 / 更多） |
| 面板「更多」菜单 `SqlPreviewPanelResultView.kt:90-109` | 无（回滚 SQL / 换数据源 / 导入参数 / 对比 / 分享 / 两种导出 / 两种 AI 上下文 / 影响面预演） |
| 工作台顶部工具栏 | 无 |

也就是说：**用户在工作台里要拿到「去注释的可执行 SQL」，必须先进弹窗点一次**；否则只能在编辑器里全选 Ctrl+C（复制到的是带注释原文，且要自己清注释）。这跟页签的定位（「解析 → 填值 → 取走 SQL」）直接矛盾——取走那一步反而没有。

### 1.2 结论

不是少一个按钮，是**动作分区缺了一层**：顶部是「输入侧」（解析/格式化/清空/从文件加载），底部本该是「产物侧」（执行/复制/导出窗）。现在产物侧只有顶部几个图标，且最关键的一项（复制）压根没做。

## 二、问题一候选方案

### 方案 A：底部加「产物动作行」四项，顶部只留输入侧（推荐）

| 底部按钮 | 语义 | 对应弹窗 |
|---|---|---|
| 执行 SQL | 送既有执行链（数据源/写闸门/控制台） | 执行 SQL |
| 复制 SQL | 复制编辑器当前内容并去注释（`copySql`） | 复制 SQL |
| 在弹框中打开 | 同一结果送 modeless 弹窗 | —（弹窗自己不需要） |
| 清空 | 重置现场（清输入 + 释放内嵌面板） | 取消（tab 没有「关闭」，最接近的离场动作就是清空） |

顶部收敛为三个输入侧动作：`解析预览 / 格式化 / 从文件加载…`。

- 优点：职责分区一句话说清（上=输入，下=产物）；`复制 SQL` 补上；文字按钮比图标可发现性高，正好对应弹窗那排的手感。
- 代价：`执行 / 清空 / 在弹框中打开` 三个图标从顶部消失，改由底部文字按钮承接（老用户要重新找一次）。

### 方案 B：只补底部缺的两项（执行 SQL / 复制 SQL），顶部不动

- 优点：改动最小、不搬动既有图标；风险低。
- 缺点：同一动作两处入口（顶部图标 + 底部按钮），两处 enable 判定要各自同步；用户下次还会问「为什么重复」。

### 方案 C：照搬弹窗四个按钮（含「复制 SQL 并关闭」「取消」）

- **不推荐**：tab 没有「关闭」语义。硬映射成「复制并清空」会让「复制完想再复制一次」的用户丢掉现场；「取消」在 tab 里也无对象可取消。照搬会把弹窗的生命周期词汇带到一个常驻页签里。

### 推荐：A

补充说明（避免误读为漏做）：**「复制 SQL 并关闭 / 取消」这两个不再出现，是有意的**——它们是弹窗的生命周期动作；tab 里对应的能力由「清空」承接。若后续仍想保留「复制完就走」的一步动作，单独评估「复制并清空」，不默认上。

## 三、问题二：弹窗预览 XML 时，缺片段没有补齐入口

### 3.1 事实

补齐能力**只有工作台一份**，且是工作台私有字段：

| 组成 | 位置 |
|---|---|
| 缺失计算 | `MissingSqlFragmentSupport.missingRefids(rawInput)`（纯文本正则，`ui/standalone/MissingSqlFragmentSupport.kt:34`） |
| 提示行 | `SqlStandalonePreviewPanel.kt:86-96`（可点 `JBLabel`）→ `promptMissingFragment()`（`:443-449`） |
| 覆盖片段存放 | `fragmentOverrides`（`:83`，**工作台私有**，用户输入保持原样） |
| 拼接重解析 | `MissingSqlFragmentSupport.withFragments(...)`（`:374`） |

弹窗侧完全没有这条链：

- `SqlPreviewDialog` 构造只做 `panel.showResult(result)`（`SqlPreviewDialog.kt:67`），没有任何 missing 计算；
- `PreviewResult`（`model/PreviewResult.kt:37-`）没有承载「缺失片段 id」的字段；
- 工作台开弹窗只传 `PreviewResult`（`openDialogWith` → `SqlPreviewService.showResult(project, result, autoParseOnPaste)`，`SqlPreviewService.kt:2046`），**原文不随行** ⇒ 弹窗就算想补齐，也没有可重解析的原始 XML。

### 3.2 成因叠加（要如实记一笔）

上一轮 `T-LoadFilePlacement` 把「从文件加载…」从弹窗移走，判定是「读文件属于输入源能力，不属于通用展示层」。这个判定本身站得住，但副作用是：**弹窗里原本唯一能"补内容"的入口也一起没了**——用户现在的体感是「弹窗预览 XML 缺片段，连补的地方都没有」。本轮不是推翻上一轮，而是把「补内容」这件事放回它该在的层：**结果区（面板）**，而不是再塞回弹窗按钮区。

### 3.3 场景区分（决定"哪里该有这个入口"）

| 弹窗来源 | 有没有原文 | 缺片段该怎么处理 |
|---|---|---|
| 工作台「在弹框中打开」 | 有（`inputEditor.text`） | 弹窗内可补齐（本轮目标） |
| 弹窗内粘贴后自动识别 | 有（`panel.currentDraftText()`） | 弹窗内可补齐（同一实现顺带覆盖） |
| gutter 图标 / 日志 / 历史 / 语句预览 | 无（只有已渲染产物；真实文件场景由跨命名空间解析 `XmlCrossNsFragmentResolver` 负责） | 不该有"补齐"按钮：真缺就是文件本身缺，弹窗里凭空补一段反而制造假数据 |

⇒ 结论：**补齐入口必须与「有没有原文」绑定**，不能无条件出现在通用弹窗里（否则 gutter / 日志 / 历史那些没有原文的入口全部白背一个点了没用的链接）。

## 四、问题二候选方案

### 方案 A：补齐能力下沉到共享面板 + 原文随窗传递（推荐）

1. `PreviewResult.missingFragmentIds: List<String> = emptyList()`（纯数据，默认值对既有调用点零影响：`SqlPreviewService.showResult` 实测 22 个文件 / 53 处），由 `StandaloneSqlSourceResolver` 填（它已经在算 `missing`）。
2. `SqlPreviewPanel` 的 `northHost`（`SqlPreviewPanel.kt:875-890`）新增一行薄提示（默认隐藏，与降级横幅/绑定行同族「触发即显示、恢复即隐藏」），文案走面板级键 `panel.missingFragment.label`，点击回调 `missingFragmentFillHandler`（默认 null ⇒ **整行不显示**，天然满足 §3.3 的"无原文不给入口"）。
3. 抽 `MissingFragmentFillFlow`（`ui/standalone/`）：算缺失 → `MissingFragmentDialog`（保留「从文件选择…」，复用 `MapperFileLoader`）→ 收 overrides → `withFragments` 重解析 → 回填。工作台与弹窗共用，各自只持有自己的 overrides。
4. `SqlPreviewService.showResult(..., sourceText: String? = null)` → `SqlPreviewDialog(sourceText)`；实测 22 个文件 / 53 处调用点里，只有工作台开窗这一条传原文（其余保持默认 null）。弹窗拿到原文后注册 handler：补齐 → 重解析 → `panel.showResult(fresh)`（**不重开弹窗**）。弹窗内粘贴路径的原文本来就在弹窗自己手里（`draftTextProvider`），不经 `showResult` 参数，同一 handler 直接复用。
5. 工作台删掉自己的 `missingFragmentLabel`（提示行改由面板显示，离 TODO 占位更近），改为注册 handler，行为不变。

- 优点：一处 UI 两个入口自动一致；解决了「提示行在底部状态区、TODO 占位在结果区，隔着一屏」的既有毛病；22 个文件 / 53 处调用点零影响。
- 代价：动的是**共享面板**（22 个文件 / 53 处 `showResult` 调用点共用），要过容器守卫与面板测试全套。

### 方案 B：弹窗底部加「补齐片段…」按钮（仅在有原文且有缺失时显示）

- 优点：不碰共享面板、不动 `PreviewResult`；实现最省。
- 缺点：两处 UI 口径分叉（工作台是链接行、弹窗是按钮）；工作台提示行离 TODO 远的问题没解决；后续再加入口（日志、历史）还要各写一遍。

### 方案 C：弹窗只给引导「回到 SQL 预览页签补齐」（带原文预填）

- 优点：零重复实现，符合"读文件/取内容归工作台"的上一轮判定。
- 缺点：用户要切页签；弹窗本来就是 modeless，切过去成本不高，但「我在这儿看到了缺口，却要我换个窗口补」仍然是绕。

### 推荐：A

理由：两个问题其实是同一件事的两面——工作台缺「产物出口」，弹窗缺「输入补口」。A 把提示与补齐都放到**结果区**（面板），出口/入口各回各层，不重复实现。

## 五、实施拆解

### 问题一（底部动作行）

- **D-1** `SqlStandalonePreviewPanel` 新增 `bottomActions`（`执行 SQL / 复制 SQL / 在弹框中打开 / 清空`）与底部动作行装配（`BorderLayout.SOUTH` 的 NORTH 位，状态行仍在最下）。
  - **复用既有实例，不新写第二套 enable 判定**：`executeAction` / `OpenInDialogAction()` / `ClearAction()` 原样搬到新行，只有 `CopySqlAction` 是新增（读 `previewPanel?.copySql(offerClosePanelAction = false)`，`previewPanel == null` 时禁用）。
  - `复制 SQL` 走 `copySql()`（与弹窗同一条链，P-8），且必须显式传 `offerClosePanelAction = false`：`copySql` 默认会在通知里挂「关闭 SQL 面板」，而 `onClosePanelRequested` 只有弹窗注入（`SqlPreviewPanelSqlView.kt:581-599`）——页签里会挂出一条点了没反应的死链接（P-2）。
  - 主行动样式：`复制 SQL` 用 `applyPrimaryButtonStyle`（`SqlPreviewPanelExecution.kt:2952`，与弹窗「复制并关闭」同源）；若用户更希望「执行 SQL」是主行动，改一行即可。
- **D-2** 顶部工具栏收敛为 `解析预览 / 格式化 / 从文件加载…`（`执行/清空/在弹框中打开` 下沉）。
- **D-3** 测试缝：`bottomActionNamesForTest()`、`bottomButtonsEnabledForTest()`（执行 SQL 的 enable 仍随 `previewPanel != null`，空态点击给引导文案——沿用 `ExecuteAction` 既有口径）。

### 问题二（补齐下沉）

- **D-4** `PreviewResult.missingFragmentIds` + `StandaloneSqlSourceResolver` 填充（`Resolved.result` 带上）；`StandaloneSqlSourceResolverTest` 补断言（缺失/无缺失/跨 ns refid 三种）。
- **D-5** `SqlPreviewPanel` 新增提示行（默认隐藏）+ `internal var missingFragmentFillHandler: ((String) -> Unit)?`；面板测试覆盖「无 handler 不显示 / 有 handler 显示文案 / 点击回调 refid」。
- **D-6** 抽 `MissingFragmentFillFlow`：把工作台 `promptMissingFragment` 的逻辑迁移进去（行为逐字对齐：一次补一个、补完重解析、缺失集合自然收敛），工作台改调它。
- **D-7** `showResult(sourceText = …)` → `SqlPreviewDialog`：本地 overrides + 重解析回填；`sourceText` 只有工作台开窗这一条经 `showResult` 传入，弹窗内粘贴路径直接用 `draftTextProvider` 的文本走同一 handler（P-5：补齐后先 `stopPasteReparse()`）。
- **D-8** bundle：新增 `panel.missingFragment.label`；`standalone.missingFragment.*`（补齐框自身）保留；`standalone.missingFragment.label` 若不再使用则删（工作台提示行改由面板渲染）。
- **D-9** 反向验证 + 定向测试（见 §七）。

## 六、待验证前提（P-x）

- **P-1（容器守卫）**：往共享面板 `northHost` 加常驻组件，可能撞既有的结构断言（本仓有「前缀 + N chips + 尾项」类守卫，见记忆 `pitfall-shared-ui-container-component-guards`）。动手前先 grep `topRow / northHost / bindStateRow / 更多` 相关测试，先跑 `SqlPreviewPanel*Test` 全套再改。
- **P-2（复制死链接）**：`copySql()` 默认 `offerClosePanelAction = true`，页签里 `onClosePanelRequested == null` ⇒ 通知里挂一条无目标链接。工作台必须传 `false`，并补一条用例断言通知动作集合为空。
- **P-3（主行动样式）**：`applyPrimaryButtonStyle` 靠平台 client property（`DarculaButtonUI.DEFAULT_STYLE_KEY`），不是 `isOpaque`；headless 断言按 client property 写（对齐 `SqlPreviewDialogTest.testPrimaryButtonUsesPlatformStyleInsteadOfPaintedBackground`）。
- **P-4（签名变更的测试字节码）**：`showResult` / `SqlPreviewDialog` 加参数会触发陈旧测试字节码 `NoSuchMethodError`（本仓在册坑），改完先删 `build/classes/kotlin/test`。
- **P-5（弹窗内补齐后的状态）**：补齐重解析要走 `stopPasteReparse()`（否则待命计时器可能再用旧草稿覆盖一次），且不重开弹窗、不复位 `loadedFileName`（来源仍是同一份文本）。
- **P-6（底部行占位）**：窄侧栏下四个文字按钮会不会挤压/换行；平台按钮行不自动折叠，必要时把「在弹框中打开」降为图标或收进溢出菜单。
- **P-7（面板提示行与降级横幅并存）**：两者都在 `northHost` 的 SOUTH 栈里，要确认同时出现时不互相遮挡（降级横幅 + 缺失提示 + DML 影响行三行叠加的高度预算）。
- **P-8（复制该走哪条实现）**：面板侧有两条复制链——`copySql()`（取编辑器当前内容、去注释，弹窗「复制 SQL」用的就是它，`SqlPreviewPanelSqlView.kt:568`）与 `copyExecutableSql()`（把 `?` 替换成带类型处理的字面值，`:627`，目前除 `ShareDialog` 的「可执行 SQL」单选外**没有 UI 调用点**）。本轮按**与弹窗一致**取 `copySql()`；「要不要给可执行复制也配个入口」单独评估，不夹带。

## 七、验收（A-x）

- **A-1** 工作台底部出现 `执行 SQL / 复制 SQL / 在弹框中打开 / 清空` 四项；顶部只剩 `解析预览 / 格式化 / 从文件加载…`。
- **A-2** 工作台点 `复制 SQL` → 剪贴板拿到**去注释**的当前 SQL；成功通知里**没有**「关闭 SQL 面板」。
- **A-3** 工作台点 `复制 SQL` 后现场不丢（输入与结果都在），可连续复制。
- **A-4** 从工作台「在弹框中打开」一个缺 `Base_Column_List` 的片段：弹窗结果区出现「缺少片段：Base_Column_List —— 点此补上内容」；点开补齐后，弹窗内 SQL 当场展开（TODO 占位消失），弹窗不重开。
- **A-5** 从 gutter 图标打开同一 mapper 的语句：弹窗**没有**补齐提示行（无原文可重解析），原文与警告保持现状。
- **A-6** 工作台自身的补齐入口仍可用（行为与今天一致），且缺失提示已从底部状态区移到结果区（差异写进验收文档）。
- **A-7** 弹窗底部仍是 `执行 SQL / 复制 SQL / 复制 SQL 并关闭 / 取消` 四项（本轮不增减弹窗按钮）。

## 八、风险

- 动共享面板（22 个文件 / 53 处调用点共用）是本轮最大回归面：提示行必须**默认隐藏**，且只在 `missingFragmentIds` 非空 + handler 已注入时可见；任何"总是可见"的写法都会让 gutter / 日志 / 历史那些弹窗多出一行无意义提示。
- 顶部图标搬家会让老用户有一个适应期（记忆成本），计划里已把这一点写明；若用户不接受，退回方案 B（只补底部两项，顶部不动）。
- 「复制 SQL 并关闭」在页签里没有对应物，这是**设计取舍**不是遗漏；若后续用户反馈要「复制完就走」，再单独立项。

## 九、实施记录

实施于 2026-11-20，master 提交 `81ebb4720`（代码+测试），本文与 `CHANGELOG.md` 由随后一笔文档提交回填。

### 9.1 逐项落点

- **D-1/D-2（问题一）**：`SqlStandalonePreviewPanel` 新增 `bottomActions`（`executeAction / copySqlAction /
  openInDialogAction / clearAction`）与手工 `JButton` 底部行（文字按钮；`复制 SQL` 走
  `applyPrimaryButtonStyle` 作主行动）。**为什么手工 JButton 而非 ActionToolbar**：工具窗工具栏在图标
  模式下只画图标，平台没有稳定的"强制文字"入口，而这一排要与弹窗底部同手感。enable 复用动作自身
  `update()` 的 presentation（单一判定来源），在 `ensurePreviewPanel()` 与 `clear()` 两处刷新。
  顶部 `inputActions` 收敛为 `resolve / format / loadFile`。
  - `CopySqlAction` 调 `copySql(offerClosePanelAction = false)`——默认值会在通知里挂一条
    「关闭 SQL 面板」死链接（`onClosePanelRequested` 只有弹窗注入），已按 P-2 规避并加用例钉住。
- **D-4（问题二）**：`PreviewResult.missingFragmentIds`（默认空表）+ `StandaloneSqlSourceResolver.withMissingFragments`
  在 `resolve()` 收口处按 `decoded` 文本填（转义形态的 `&lt;include&gt;` 只有解码后才看得见）。
- **D-5**：`SqlPreviewPanel` 新增 `missingFragmentLabel`（挂 `northHost` 的 SOUTH 栈，即降级横幅同族）
  + `missingFragmentFillHandler`（setter 即时刷新）+ `refreshMissingFragmentHint(ids)`；**双条件可见**
  （载荷有缺失 && 已注入回调），缺一即隐藏、不占高度。`updateMeta` 与本类 `clear()` 各驱动一次。
- **D-6**：新增 `MissingFragmentFillFlow`（`promptViaDialog` + 纯函数 `record`）；工作台 `promptMissingFragment`
  删除，改 `fillMissingFragment`（内容来自 `missingFragmentPrompt` 注入缝 → `record` → `resolveFromInput`）。
- **D-7**：`SqlPreviewService.showResult(..., sourceText)` → `SqlPreviewDialog(sourceText)`；弹窗本地
  `fragmentOverrides` + `lastSourceText`（贴入路径），`fillMissingFragment` 先 `stopPasteReparse()` 再用原文
  重解析并 `panel.showResult`（不重开弹窗）。工作台 `openDialogWith` 传 `sourceText = inputEditor.text`。
- **D-8**：新增 `standalone.action.copySql(.description)` / `standalone.status.copy.noPreview` /
  `panel.missingFragment.label`；删 `standalone.missingFragment.label`（提示行改由面板渲染），中英同步。

### 9.2 读数

- 定向（平台桶，6 类）：**38 例 0 红**——`SqlPreviewDialogTest` 16 / `SqlPreviewPanelMissingFragmentHintTest` 5 /
  `SqlStandaloneActionRowTest` 4 / `SqlStandalonePreviewPanelDialogTest` 6 / `SqlStandaloneLoadFileTest` 4 /
  `SqlStandaloneExecuteActionTest` 3。
- **面板全家桶（P-1 排查）**：`SqlPreviewPanel*` + `SqlPreviewTopRowLayoutTest` 共 **31 类 280 例 0 红** ——
  新提示行的挂载没有撞既有结构断言。
- 快桶（3 类）：**51 例 0 红**——`StandaloneSqlSourceResolverTest` 36（含新增 5 例缺失片段用例）/
  `MissingSqlFragmentFillFlowTest` 4 / `MissingSqlFragmentSupportTest` 11。
- 反向验证（逐个单独打，恢复后复跑全绿；`grep RED PROBE` 为空）：
  1. 面板去掉 handler 门控（`if (first == null)`）→ `test有缺失但无补齐回调时隐藏提示行` 1 红；
  2. 弹窗无条件注册 handler → `test弹窗没有原文时不显示补齐入口` 1 红；
  3. 底部 `bottomActions` 去掉 `copySqlAction` → 动作序列 + enable 两条 **2 红**；
  4. 工作台 `openDialogWith` 不传 `sourceText` → `test在弹框中打开_缺片段时弹窗内出现补齐入口` 1 红。

### 9.3 过程中的坑（已入 LESSONS）

- **首轮把四条探针一起打时只红了 4 条（预期 5）**：`不传 sourceText` 那条被"面板去掉 handler 门控"掩盖——
  门控一去掉，可见性不再由 handler 决定，「有原文才显示」这整类断言恒绿。探针是**串联门控**，必须逐个打。
  记 **L91**。
- `SqlPreviewPanel` 的构造器/扩展函数分布（`toolbar()` 在 ResultView、`clear()` 在 SqlView）导致新行要跨
  两个扩展文件各改一处；`updateMeta` 的四处调用点都传同一份 `result`，不需要粘性字段（已在计划 P-x 核对）。

### 9.4 遗留

- A-1~A-7 的 **GUI 验收待用户**（尤其 A-4：工作台开窗后在弹窗里点补齐、A-5：gutter 来源弹窗不出现该行）。
- 方案 C（拖放）仍不进本轮；`copyExecutableSql()`（参数化可执行复制）依旧没有 UI 入口，单独评估。

