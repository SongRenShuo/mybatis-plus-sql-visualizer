# T-DialogPasteReparse：先开弹窗、后贴内容也能识别 开发计划 + 实施记录

- 立项：2026-11-18（用户报障后当场实施，本文是立项与实施合并稿）
- 任务源（用户原话）：「这种先打开弹框再贴sql的情况能不能也适配下」（附截图：空弹窗里贴进 iBatis `<select>`，SQL 区显示原文、左侧「无未解析参数」）
- 关联：`20261118-1247-T-StandaloneDialogOpen`（空输入开弹窗）、`20261118-1253-T-StandaloneIbatisRecognize`（识别链）、`20261118-1359-T-StandaloneHintAndWarning`
- 状态：**已实施并提交**

## 一、问题与根因

用户从工作台点「在弹框中打开」时输入为空 → 弹窗拿到的是空载荷；弹窗里的编辑器只是普通 SQL 编辑器——**没有任何解析链**。于是往里贴 iBatis/MyBatis 片段时，面板原样显示文本、参数区「无未解析参数」，等于把工作台的识别能力在弹窗里丢了。

代码级：`SqlPreviewDialog` 只在构造期 `panel.showResult(result)` 一次，此后编辑器内容变化不再触发任何解析；`SqlPreviewService.showResult` 也没有「后续改动要不要重识别」的通道。

## 二、方案

**在弹窗内做「贴入即识别」的待命态**，只在**空/原文态**开窗时启用：

1. 工作台在「空输入 / 未识别原文」两条路径开弹窗时置 `autoParseOnPaste = true`（正常语句预览保持 false）；
2. 弹窗进入待命后**轮询草稿文本**（`SqlPreviewPanel.currentDraftText()`），文本连续 ~800ms 未变化即认为用户停手 → 跑 `StandaloneSqlSourceResolver.resolve` → 命中就 `panel.showResult(fresh)` 并**立即交还手动**；
3. 失败时文案同源（`StandaloneWarningText.failure`，五段「标题｜来源｜语句｜原因｜建议」）。

### 2.1 两个关键取舍

- **为什么用轮询、不挂 DocumentListener**：面板编辑器是**懒建**的（空结果构造期 `draftDocument()` 为 null，真机要等首次布局/挂载才有编辑器）——挂监听会有「此刻没文档就永远挂不上」的窗口。轮询只读文本，文档何时出现都能接上；命中即停，常态零开销。
- **为什么只在空/原文态待命**：正常语句预览的编辑器内容已是渲染后的 SQL，用户在那儿编辑是**改产物**（回填参数、手调 SQL）——自动解析会把他的编辑冲掉。

## 三、实施落点

| 文件 | 改动 |
|---|---|
| `ui/SqlPreviewPanel.kt` | 新增 `currentDraftText()` / `draftDocument()`（草稿读取；原生控制台文档优先，自建编辑器兜底） |
| `ui/standalone/StandaloneWarningText.kt`（新） | 结构化文案抽成共用对象（工作台与弹窗同源，避免同片段两处文案不一致） |
| `ui/SqlPreviewService.kt` | `showResult(...)` 新增 `autoParseOnPaste: Boolean = false`，透传给弹窗 |
| `ui/SqlPreviewDialog.kt` | 待命态：轮询 Timer（400ms × 2 tick）+ `reparseDraftFromEditor()` + `dispose` 停表；`draftTextProvider` 测试缝 |
| `ui/standalone/SqlStandalonePreviewPanel.kt` | 空/原文态开窗传 `autoReparse = true`；`buildFailureWarning` 改为委托共用对象 |

## 四、验证读数

- **新增用例**：`DialogPasteReparseTest` 3/3（贴入 iBatis 片段 → 2 个条件行；命中后再调不改写面板；正常开窗不待命）。
- **反向验证**：摘掉构造期 `if (autoParseOnPaste) armPasteReparse()` → **恰 1 条红**（待命断言那条），恢复后全绿。
- **回归**：`testFast` 全桶绿（7m50s）；受影响平台套件 9 类合计 **108 例 0 红**（含 RefreshDbSteal / 三组横幅 / 工作台弹窗 / 日志面板布局）。
- **not run**：真机「空弹窗 → ⌘V/右键粘贴 → 自动出结果」的手感与耗时；轮询在真机 EDT 上的开销（400ms 取一次字符串，待命期结束后为 0）——交 GUI 验收。

## 五、踩坑记录（两处）

1. **Kotlin 初始化顺序**：`draftTextProvider` 声明在 `init` 之后，`armPasteReparse()` 在 init 里读了它 → `NullPointerException: draftTextProvider is null`（本仓在册坑，已有守卫专测）。改为待命期不读字段、注释写明约束。
2. **headless 下弹窗 show 后立即 dispose**：经 `SqlPreviewService.showResult` 拿到的实例已 `dispose()` 过，而本功能 `dispose()` 会正常停待命 → 用例改**直接构造弹窗**测同一条链（service 透传那一跳由单行赋值保证，真机归 GUI）。

## 六、风险与遗留

- 轮询期间每 400ms 读一次 `currentDraftText()`（拼接文档文本），超长草稿有成本；但待命态只存在于「空弹窗等你贴」这一小段时间，命中即停。若真机观测到卡顿，可加「文本长度上限」或改回挂监听 + 编辑器就绪回调。
- 自动识别**只做一次**：用户贴错内容想再识别，目前需关掉重开（工作台页签仍可反复解析）。若需要「再来一次」，可按同一待命机制加手动按钮。
