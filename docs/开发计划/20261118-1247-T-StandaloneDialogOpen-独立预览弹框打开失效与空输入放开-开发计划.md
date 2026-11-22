# T-StandaloneDialogOpen：「在弹框中打开」失效 + 空输入放开 开发计划

- 立项时间：2026-11-18 12:47
- 任务源（用户原话）：「功能有问题 目前我在主分支沙盒测试 在弹框中打开 实际上没有弹出sql预览弹窗 还是在插件工具栏里了，并且我希望这个 在弹框中打开 即使没有填sql也可以弹出来」
- 关联：`docs/开发计划/20261114-1046-T-SqlPreviewStandalone-预览独立打开-开发计划.md`（本功能的立项计划，D-3 即本次出问题的入口）
- 状态：**根因已定位（代码级确证），待实施**

## 一、现状取证

功能已合入 master（`3e9ab1b1c Merge branch 'T-SqlPreviewStandalone' into master`），主仓文件在位：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/SqlStandalonePreviewPanel.kt`。

用户沙盒截图对应形态：主侧边栏「SQL 预览」页签、输入区 3 行 SQL（`#username` / 两个 `?`），页签工具栏 4 个动作（解析预览 / 格式化 / 清空 / 在弹框中打开）。点「在弹框中打开」后页面**无任何变化**——既无弹窗，内嵌预览区也未变，完全符合下节根因的行为特征。

## 二、根因分析

### 2.1 主因：跨调用可变标志位被默认参数就地覆盖（必然失效，100% 复现）

三处代码连起来读，缺陷是确定性的：

- `SqlStandalonePreviewPanel.kt:83` —— 字段 `private var openDialogPending = false`（跨调用可变状态）
- `SqlStandalonePreviewPanel.kt:175-178` —— 动作体：

  ```kotlin
  override fun actionPerformed(e: AnActionEvent) {
      openDialogPending = true          // ← 先置真
      resolveFromInput()                // ← 不带参调用
  }
  ```

- `SqlStandalonePreviewPanel.kt:189-200` —— 被调方签名与首行：

  ```kotlin
  fun resolveFromInput(openDialog: Boolean = false) {
      ...
      openDialogPending = openDialog    // ← 用默认值 false 把上面的 true 抹掉
  ```

于是 `applyResolved()`（`:218` 读 `openDialogPending`）永远读到 `false`，`:224-226` 的 `if (dialogRequested) SqlPreviewService.showResult(...)` 永远不执行。用户点按钮得到的唯一效果就是「重新解析并刷新内嵌面板」——恰是截图里看到的「还是留在工具栏里」。

这不是偶发时序问题，与输入内容、数据源、编辑器状态全部无关：任何输入下点该按钮都不会弹窗。

### 2.2 次因：空输入被早退拦死（用户第二条诉求）

`SqlStandalonePreviewPanel.kt:190-194`：`text.isBlank()` 即 `setStatus("输入为空")` 后 `return`，解析链根本不下发，弹窗自然无从打开。即便主因修复，空输入点按钮仍只会闪一行状态文案。

### 2.3 同族隐患（本轮顺手收）

同一字段还被 `clear()`（`:268`）与 `applyResolved()`（`:219`）复位，三处写、一处读，语义靠调用顺序维持——这是「跨调用可变状态 + 默认参数」的典型复合缺陷。只修一行传参能过，但下一次改动（例如再加一个「在弹框中打开并执行」入口）会以完全相同的方式再破一次。

## 三、方案

### 方案 A：最小改动（只改一行）

`OpenInDialogAction` 改成 `resolveFromInput(openDialog = true)`，并把 `openDialogPending = openDialog` 移到早退之后即可。

- 优点：改动量最小。
- 缺点：字段仍在，三写一读的隐患原样保留；空输入诉求还得另加分支。

### 方案 B：删标志位、参数穿透 + 空输入放开（推荐）

1. **删掉 `openDialogPending` 字段**。`resolveFromInput(openDialog)` 把该布尔作为 `val` 捕获进后台闭包，随代次守卫一并回传 `applyResolved(resolved, openDialog)`。同一「意图」只存在于一条调用链里，不存在跨调用错配的可能；连点两次也只有各自链上的值，不会互相污染。
2. **空输入不再早退**。`text.isBlank()` 时直接走弹窗（见 §3.1），状态条如实说明「输入为空，已在弹窗中打开空白编辑器」，页签内嵌区保持空态提示不动。
3. 解析失败/未识别：同样不再只写状态条（见 §3.2）。

理由：用户对这条功能的心理模型是「这个按钮永远能开弹窗」，而现在的实现是「解析成功才可能开」。把「打开」与「解析」解耦，才是诉求的正解；只补默认参数等于把诉求的实现路径钉死在「必须有可解析输入」上。

### 3.1 空输入时弹什么

给出 `PreviewResult` 的空壳（`sql = ""`、`unresolvedParams = emptyList()`、`paramTable = null`、`wrapperType/tableName = null`），交既有 `SqlPreviewService.showResult`（`ui/SqlPreviewService.kt:2046`）与 `SqlPreviewDialog`（`ui/SqlPreviewDialog.kt:22-27`，modeless）。弹窗内的 SQL 编辑器本身可编辑，用户可直接粘贴/键入——这正是「先开工具再干活」的用法。

依据：`showResult` 只拒 `result == null`（:2059-2073），非空空壳照常建窗；`SqlPreviewPanel.showResult`（`ui/SqlPreviewPanelResultView.kt:172`）对空 SQL 的全部下游（`paramValues` 播种、`renderSql` → `showSql`（`SqlPreviewPanelSqlView.kt:790`）的 `setText("")` 分支）均无空串拒绝分支。

### 3.2 未识别文本时弹什么（建议同批做）

用户从网页复制、或粘贴了一段 pom/散文时，现状是只写「未识别」状态条、弹窗不开。建议：弹窗照开，`sql` 用**用户原文**、`warning` 置信息级「未识别为 SQL / mapper XML，已按原文展示，可在弹窗内自行编辑或格式化」。

这样「弹窗永远能开」成为一致承诺，且不丢用户文本。若产品口径只想做空输入，可把本项标为可选（默认建议做，成本约 10 行）。

## 四、待验证前提（P-x，实施前先证伪）

- **P-1（空壳结果的面板健壮性）**：`SqlPreviewDialog(project, 空壳)` 构造 + `panel.showResult(空壳)` 在真机上不抛异常，且参数区显示「无参数」、SQL 区为空。代码级已查无空串拒绝分支，仍需一条 Light 用例实测（见 D-4）。
- **P-2（空 SQL 下的动作栏）**：空 SQL 时点「执行 SQL」「复制 SQL」「复制并关闭」不得抛异常、不得发空语句。`executeSql` 侧是否已有空白守卫需核（`ui/SqlPreviewPanelExecution.kt`），没有则补一条早退 + 状态提示。
- **P-3（弹窗与页签双面板并存）**：点按钮后弹窗与页签内嵌面板各持一个 `SqlPreviewPanel`（两套 Editor）。20261114 计划 P-2 已登记此风险并留了「互斥退化」路线；本次不改，只登记实测内存/Disposer 归属（弹窗 dispose 不得连带释放页签面板，反之亦然）。
- **P-4（异步链在 Light 测试里的可控性）**：`resolveFromInput` 走 `executeOnPooledThread` + `invokeLater(nonModal)`。Light 用例要断言「弹窗真的出现了」，需确认等待手法（`UIUtil.dispatchAllInvocationEvents()` 轮询 + 超时上界）在本仓测试框架下稳定；不稳定则改注入缝（把「展示」抽成 `internal var dialogPresenter`，测试替换为记录器），但届时须另有一条真机/runIde 证据补上。
- **P-5（`showResult` 的既有副作用）**：确认 `SqlPreviewService.showResult` 不会因「同一项目已有弹窗」而静默复用旧内容（`activePreviewDialogs` 单例关旧开新，:2074-2083）——即连点两次得到的是新内容弹窗，不是旧窗复现。

## 五、实施拆解（方案 B）

- **D-1 主修：删标志位、参数穿透**（`SqlStandalonePreviewPanel.kt`）
  - 删字段 `openDialogPending`；
  - `resolveFromInput(openDialog: Boolean = false)` 内 `val wantDialog = openDialog`，捕获进后台闭包，随代次守卫传给 `applyResolved(resolved, wantDialog)`；
  - `applyResolved` 签名加 `openDialog: Boolean`，内部不再读/写字段；
  - `OpenInDialogAction.actionPerformed` → `resolveFromInput(openDialog = true)`；
  - `clear()` 里删掉字段复位行。
- **D-2 空输入放开**（用户诉求 2）
  - `resolveFromInput` 的 `text.isBlank()` 分支：若 `openDialog` 为真 → 直接 `showResult(空壳)` + 状态「输入为空，已在弹窗中打开空白编辑器」；否则维持现状写「输入为空」状态。
  - 空壳构造抽成 `private fun emptyResult(): PreviewResult`（放在面板内，别污染解析器）。
  - 补 bundle 键（en/zh）：`standalone.status.emptyOpenedDialog`。
- **D-3 未识别文本放开**（§3.2，建议同批）
  - `applyResolved(null)` 分支：`openDialog` 为真 → `showResult(rawTextResult(text) + informationalWarning)`；否则维持现状。
  - 复用 `PreviewResult.informationalWarning = true`（UI-001 既有语义，横幅用 ℹ 前缀）。
- **D-4 空/未识别动作栏守卫**（视 P-2 结论）
  - 执行/复制在 `sql.isBlank()` 时早退 + 状态提示，避免空语句下发。
- **D-5 测试**
  - **Light 用例（行为级，主证据）**：构造 `SqlStandalonePreviewPanel(project)` → 程序性写入输入文本 → 触发与工具栏按钮同一条路径 → 轮询断言 `SqlPreviewService.activePreviewDialogForTest(project) != null`（该测试缝已存在，`SqlPreviewService.kt:2115`）。
    - 用例 1：有合法 SQL → 弹窗出现；
    - 用例 2：输入为空 → 弹窗出现（诉求 2）；
    - 用例 3（若做 D-3）：pom 片段 → 弹窗出现且 `warning` 为信息级。
  - **反向验证（必做，两条读数）**：把 `openDialogPending = openDialog` 的覆盖写回（或等价地把 `resolveFromInput(openDialog = true)` 改回无参），上述用例 1/2 必须变红；贴「改前红 / 改后绿」两次读数。
  - 面板/测试访问器：`SqlStandalonePreviewPanel` 需加 `internal` 缝（如 `internal fun setInputForTest(text)` / `internal fun triggerOpenInDialogForTest()`），命名与仓内 `*ForTest` 惯例一致。
  - 桶位：新用例要引 `BasePlatformTestCase` 与 `SqlPreviewDialog`，属平台桶（**不进 testFast 快桶**，注意测试源码里写「com.intellij」字面量会被划桶过滤误伤，注释也别写该字面量）。
- **D-6 修订与文档**
  - 回填本计划 §八 实施记录；CHANGELOG `[Unreleased]` 加一句「修复：在弹框中打开无效」；
  - 20261114 立项计划 D-3 条目加一行指向本计划的订正说明（原文案把「打开」与「解析成功」耦合，是缺陷之源）。

## 六、验收（A-x）

- **A-1** 页签输入一段含 `?` 的 SELECT，点「在弹框中打开」→ **modeless 弹窗出现**（不是页签内刷新），标题「SQL 预览」，弹窗内 SQL 与参数行与页签一致。
- **A-2** 输入为空，点「在弹框中打开」→ 弹窗照样出现（空编辑器），页签状态条说明「输入为空，已在弹窗中打开空白编辑器」；在弹窗编辑器里粘贴 SQL 后可正常填值/复制/执行。
- **A-3**（若做 D-3）输入一段 pom XML 片段，点按钮 → 弹窗出现，SQL 区为原文，横幅为 ℹ 信息级（非 ⚠ 失败）。
- **A-4** 连点两次「在弹框中打开」→ 只有最新内容的那个弹窗（旧窗被单例关旧开新），无两个弹窗并存、无异常。
- **A-5** 既有路径回归：Tools 菜单「打开 SQL 预览工作台」仍只切页签（不弹窗）；日志条目双击、历史双击、gutter 预览三条既有入口行为不变。
- **A-6** 反向验证读数在报告里贴全（见 D-5）。
- **A-7** GUI 最终验收由用户做（沙盒走 A-1/A-2 即可，约 1 分钟）。

not run 条款：真机沙盒 GUI 手感与弹窗置顶行为由用户验收；P-3 双面板内存只做归属核对，不做量化内存测试。

## 七、风险

- **只修传参不删字段**（方案 A）会在下一次加入口时复发——本计划按 B 执行，评审时若被要求最小化改动，须在代码里留注释说明「此字段不得回归」。
- **空壳结果触发面板内部空态分支**（P-1）：若弹窗内出现「未找到可转换的 Wrapper」之类误导文案，需在弹窗侧对空 SQL 给专门的空态提示（一句灰字），不得让用户以为出错。
- **平台桶测试耗时**：Light 用例引平台 UI，单条数百毫秒，加 3 条约 1~2s，可接受。
- **并行**：`SqlStandalonePreviewPanel` 目前只有本车道改动，撞车面小；`SqlPreviewService` / `SqlPreviewDialog` 为只读引用，不动。

## 八、实施记录

**实施于 2026-11-18（master 直接实施，用户令「在主分支 一起开工」），提交 `fdfee15a3`（与 T-StandaloneIbatisRecognize 同批）。**

### 8.1 落位清单

| 落点 | 内容 |
|---|---|
| `SqlStandalonePreviewPanel.kt` | 删 `openDialogPending` 字段；`openDialog` 作为 `val` 捕获进后台闭包、随代次守卫回传 `applyResolved(resolved, openDialog, rawText)`；`OpenInDialogAction` → `resolveFromInput(openDialog = true)`；空输入分支直接开弹窗；未识别文本按原文 + 信息级开弹窗 |
| `MpSqlVisualizerBundle(.zh).properties` | `standalone.status.emptyOpenedDialog` / `standalone.status.unrecognizedInDialog` |
| `SqlStandalonePreviewPanelDialogTest.kt`（新） | 4 条平台桶用例，**全部走真实动作路径**（`OpenInDialogAction.actionPerformed`） |

### 8.2 计划前提的落地校正

- **P-2（空 SQL 动作栏）**：`executeSql`（`SqlPreviewPanelExecution.kt:1262-1271`）与 `copySql`（`SqlPreviewPanelSqlView.kt:568-572`）**都已有空白守卫**（取不到非空文本即 return），故计划 D-4「补守卫」**不需要实施**，登记为零改动。
- **P-4（异步链在 Light 测试里的可控性）**：可行——`UIUtil.dispatchAllInvocationEvents()` 轮询 + 10ms 间隔 + 5s 上界即可等到弹窗登记。
- **新增实测坑（计划未预见）**：headless 测试态下 `DialogWrapper.show()` 返回后弹窗 `isDisposed` 立即为真（无真实窗口）。初版 `awaitDialog` 加了 `!isDisposed` 过滤 → 4 条全红（误判成实现有问题，靠一条「直接调 showResult」对照用例才定位）。现断言只认**登记事实**，并在测试 KDoc 写明原因；真机可见性由用户 GUI 验收。

### 8.3 验证读数

- **正向**：`SqlStandalonePreviewPanelDialogTest` 4/4 绿（有 SQL / 空输入 / 未识别 / iBatis 片段）。
- **反向验证（必做项，两次读数）**：把 `actionPerformed` 改回缺陷形态（`resolveFromInput()` 无参）→ **4/4 全红**；恢复后 4/4 绿。
- **回归**：`testFast` 全桶绿（434 类）；`ui.standalone` 四类合计 47 例绿（4+29+5+9）。
- **not run**：真机沙盒弹窗置顶/焦点手感由用户 GUI 验收（A-1/A-2）；P-3 双面板内存只做归属核对，未量化。

### 8.4 未做的部分

- **A-3（未识别文本按原文 + ℹ 信息级）**：已实现，但断言只覆盖「弹窗出现且 warning 非空」，未断言横幅前缀确为 ℹ——该档位由既有 UI-001 语义保证（`informationalWarning` 已在 `updateMeta` 消费）。
