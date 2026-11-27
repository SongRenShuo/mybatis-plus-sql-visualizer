# 无数据源执行失败结果区绿块（T-NoDataSourceGreenResultPanel）-开发计划

- **日期**：2026-11-23 10:30
- **编号声明**：本册新占 **D253**（D257 册〔原名 D252 册，2026-11-26 因与 1748 册「D252 GEO 深化」撞号而改号〕:4「下一全新 D253」口径一致，grep 全 docs 除该指针外零占用）；下一全新 **D254 / V246 / O-164**。
- **来源**：用户 2026-11-23 内测 `2026.11.23-beta.1` 验收截图——独立 SQL 预览工作台粘裸 SQL 含 `${}` 点「执行 SQL」、确认框点「是」后，无数据源时执行失败（底部红条「未找到可用数据源」），结果区（原显示格式化 SQL 处）变成**整块纯亮绿空白**。有数据源时执行正常、不出现。用户明确要求立项实施。
- **范围**：仅插件仓，UI 两处底色 + 错误收口一处重挂 + headless 测试。不改主题、不改 namedColor 定义、不碰执行闸门链。

## 一、现状核查（2026-11-23 源码级实证 + 只读代理排查，非推断）

| # | 事实 | 证据 |
|---|---|---|
| F1 | 全项目 `src/main` **无** `Color.GREEN` / `0x00FF00` / `JBColor.GREEN` 硬编码；`MpvsUiColors` 唯一绿（`DURATION_OK 0x4CAF50`/`0x66BB6A`、`CRUD_INSERT`）只用于日志列表耗时列/类型色条，从不赋面板 background | grep 全 src/main + 读 `MpvsUiColors.kt:25-154` |
| F2 | 无数据源失败路径＝`DataSourceSelectorResults.kt:674` `NO_DATASOURCE → onError(execute.datasource.empty)` → `SqlPreviewPanelExecution.kt:1123 showTempFileError`（cause==null 走 `:1131 resultPanel.showError`），只改 SOUTH 细条 `infoLabel`，**不触发 onConsole/embedConsole、不 clear/replace editorHolder** | 读 `showTempFileError` 全函数 + grep executeSql 内 editorHolder/consoleHost 全部写入点 |
| F3 | 结果区覆盖层全透明：`editorHolder`（`SqlPreviewPanel.kt:462-468` `isOpaque=false`）、`consoleHost`（`:593` `isOpaque=false`）、`SqlResultPanel`（`SqlResultPanel.kt:130` `isOpaque=false`）；直接父容器 `split`（`SqlPreviewPanel.kt:1007-1015`）与 `paramScroll`（`:265`）显式取 `UIUtil.getPanelBackground()` 作底色 | 读上述三处 |
| F4 | 模态确认框期间页签 `removeNotify()`（`SqlPreviewPanel.kt:1110-1115`）→ `releaseEditor()` → `disposeCurrentEditorOnly()`（`EditorLifecycle.kt:1165-1183`）：`releaseEditor` + `editor=null`，**但编辑器组件（含 DataProvider 包装 JBPanel，`SqlInputEditor.kt:97-102`）不从 editorHolder 摘除** | 读 `removeNotify` / `disposeCurrentEditorOnly` / `SqlInputEditor` 挂载点 |
| F5 | 生产默认 `consolePrepMode=LAZY`（`PluginSettings.kt:95`），`PreviewConsoleEager.kt:69-73` 仅单测/EAGER/Registry 才 embed；真机结果区是自建编辑器而非 DBN split | 读 `PreviewConsoleEager.kt` + `PluginSettings.kt` |
| F6 | 成功执行走 `onConsole → embedConsole`（`:2573`）换入原生 console split 覆盖结果区 → 不显绿；失败无此替换 | 读 `executeSql` 的 onConsole/onError 回调（`:503-524`） |
| F7 | 测试缝全部真实存在：`editorHolderForTest()`（`:1269`）、`resultPanelForTest()`（`:1279`）、`SqlExecutor.executionAttemptForTest`（`SqlExecutor.kt:26/58`）、`PreviewConsoleEager.enabledForTest`（`PreviewConsoleEager.kt:50`）、`mountEditorHolderContent`（`:2785`）、`showSql`（`SqlView.kt:796`） | grep + 读定义 |

### 根因链（已证实部分）

1. 点确认框 → 工作台页签 `removeNotify` → `disposeCurrentEditorOnly` 释放编辑器（`editor=null`），组件仍留在 editorHolder，成为失去 editor 支撑的空壳。
2. 无数据源早退 → 只有 `resultPanel.showError`，失败链无任何重渲染（`showSql`/`embedConsole` 都不调）→ 空壳组件不被替换。
3. editorHolder 透明（`isOpaque=false`）→ 透出父容器 `split` 用 `UIUtil.getPanelBackground()` 涂的底色。IDE 主题把该键解析成亮绿 → 整块纯绿空白。有数据源时成功路径 embedConsole 换入原生组件覆盖 → 不显绿，与全部现象自洽。

### 待真机取证（不写进已证实，实施后请用户复核）

- "绿色到底是哪一层承载"代码层无法 100% 坐实（主题解析值由 LAF 决定）。取证：UI Inspector（Help → Diagnostic Tools → UI Inspector）点绿色区看类名与 background；若命中 `OnePixelSplitter(mpvs.preview.splitter)` → 坐实 F3；若命中 DBN 类 → 走 `ensureResultBottomMounted` 结果组件（插件未着色）。本册修复对两层都有效（自带不透明底色 + 失败重建编辑器），取证只为归档归因，不影响修复方案。

## 二、实施内容

### M1 结果区自带不透明主题底色（1 次提交）

1. `SqlPreviewPanel.kt:462-468` `editorHolder`：`isOpaque=true` + `background = UIUtil.getPanelBackground()`（主题感知，绝不硬编码色）。
2. `SqlPreviewPanel.kt:593` `consoleHost` 同处理（它也是 mount/重挂路径的常驻容器）。
3. 不动 `split:1014` / `paramScroll:265` / `MpvsUiColors`——它们是主题正确语义；只在子层自绘挡住透出。

### M2 失败收口重建预览编辑器（1 次提交，与 M1 可同批）

1. `SqlPreviewPanelExecution.kt:1123 showTempFileError`：错误投递后，若 `embeddedConsole == null` 且 `editor == null`（编辑器已被确认框释放），用 `current`（最近一次 PreviewResult）重挂自建编辑器——复用 `showSql(current.sql)` 或 `mountEditorHolderContent`，让失败时格式化 SQL 仍在（对齐用户截图 1 失败前有 SQL、截图 5 失败后空白的差异）。
2. 守卫条件：只在 `current != null` 时重建；无预览结果（纯执行入口）不重建，避免无中生有。重建失败不能覆盖真实错误——包 `runCatching`，失败只记日志。
3. `:815 showError` 直接分支（不经过 showTempFileError 的调用点）同样核对是否需补；以 grep `resultPanel.showError(` 全调用点为准，只补"执行失败态且 editor 被释放"的路径。

### M3 测试（随 M1/M2 同批）

1. headless 面板测试（新建 `NoDataSourceGreenResultPanelTest`，对齐 `DollarPlainSqlBannerTest` 形态）：
   - 注入绿色 sentinel：`UIManager.put("Panel.background", Color.GREEN)`（测试内 try/finally 恢复原值，防泄漏到同 JVM 其它用例）；
   - 经 `SqlExecutor.executionAttemptForTest` 驱动 NO_DATASOURCE onError（或直调 `showTempFileError("未找到可用数据源")`），断言 `editorHolderForTest().isOpaque == true`、`background != Color.GREEN`、失败态 `resultPanelForTest().stateForTest()=="ERROR"` 且错误文案在场；
   - 断言失败后格式化 SQL 仍在（自建编辑器已挂载 / `showSql` 被调用的缝）。
2. 反向验证：临时把 `editorHolder` 改回 `isOpaque=false`（或去掉 M2 重建），保留绿色 sentinel → 对应断言必须变红；恢复后回绿，两次读数在案。
3. 回归：既有 `SqlResultPanelUiTest` / `SqlPreviewPanelUiTest` / `DollarPlainSqlBannerTest` 全绿（动共享 UI 容器，先 grep 容器名跑既有守卫类，几十秒 vs CI 分片）。

### M4 收尾（1 次提交）

1. CHANGELOG `[Unreleased]` Fixed 补一条（无数据源执行失败结果区绿块/空白）。
2. 本计划册 §六实施记录回填；`docs/LESSONS.md` 按复发价值决定是否登记（首次出现先 Seen 1×）。
3. 验收：用户真机无数据源复现步骤 + 预期（见 §四）交用户点验；本轮只做定向测试，不跑全量（UI 平台桶 `--tests "<FQCN>"`）。

## 三、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪方法 |
|---|---|---|
| P1 | 失败态 editorHolder 里确实是"失去 editor 支撑的空壳组件"，不是 null | headless：驱动失败后断言 editorHolder 子组件数 > 0 且 editor==null |
| P2 | `isOpaque=true` + 主题背景能挡住透出，且不破坏有数据源时的 embedConsole 覆盖 | 读 embedConsole 挂载（`:2669` removeAll + add consoleHost），确认底色只作用在无 console 时 |
| P3 | `showSql`/`mountEditorHolderContent` 重建不会覆盖真实错误（resultPanel SOUTH 与 editorHolder CENTER 分离） | 读两者写的是不同容器；headless 断言 ERROR 态仍在 |
| P4 | 绿色 sentinel 注入不污染同 JVM 其它用例 | try/finally 恢复 `UIManager` 原值；测试类独立跑两遍读数一致 |

## 四、验收标准（用户真机）

1. 无数据源时粘 `SELECT * FROM t_biz WHERE id = ${id}` → 执行 → 确认「是」→ **结果区不再出现绿色块**；若格式化 SQL 仍在（M2 生效）更佳。
2. 有数据源时执行照旧正常（原生控制台覆盖结果区，无回归）。
3. 底部错误红条「未找到可用数据源」仍在、可复制。
4. 定向测试全绿 + 反向红/绿两读数在案；既有 UI 守卫类无回归。

## 五、边界

- 不改主题 / namedColor / `UIUtil.getPanelBackground()` 语义；不硬编码任何颜色值。
- 不改执行闸门、${} 横幅、确认弹窗（**D257**〔原 D252 裸 SQL 横幅〕能力零回归）。
- 不动 `update/`、发行节奏；随下一发行批亮相。
- 不改 `update/update.json`（用户本人维护）。

## 六、实施记录（2026-11-23 落地回填）

### P1~P4 证伪结论（headless 探针实证）

| # | 结论 | 证据 |
|---|---|---|
| P1 | **证伪（修正根因）**：失败后 editorHolder 不是"残留僵尸编辑器组件"，而是**真空**（`componentCount=0`、`editor=null`）。探针注入绿色 sentinel 后实测 `holder.parent.background=Color[0,255,0]`、`holder.isOpaque=false`——机制是"空 + 透明透出父容器背景"，与是否残留组件无关 | 临时探针 `NoDataSourceGreenProbeTest` 读数（已删，正式判据落 `NoDataSourceGreenResultPanelTest`） |
| P2 | **成立**：`editorHolder` 设 `isOpaque=true` + `UIUtil.getPanelBackground()` 后，embedConsole 路径（`:2669` removeAll+add consoleHost）不受影响——底色只是 holder 自身，子控制台覆盖其上；`ManualButtonPaintGuardTest` 全绿证明主题感知写法合规 | 读 embedConsole + 守卫测试 |
| P3 | **成立**：错误行在 SOUTH 细条 `SqlResultPanel`，重建写在 CENTER `editorHolder`，两容器分离；重建 `runCatching` 包裹，失败只记日志不覆盖错误。测试断言 ERROR 态保留 | `showTempFileError` 分支 + `NoDataSourceGreenResultPanelTest` |
| P4 | **成立**：绿色 sentinel 用 try/finally 恢复 `UIManager` 原值；4 用例独立与合跑读数一致，无污染 | 测试 try/finally + 多轮读数 |

### 提交与验证读数

- **M1（SqlPreviewPanel.kt:462-473）**：editorHolder `isOpaque=true` + `background=UIUtil.getPanelBackground()`，消除透明透出；不硬编码颜色、跟随主题。
- **M2（SqlPreviewPanelExecution.kt，showTempFileError 无成因分支 + 新函数 `restoreResultAreaAfterExecFailure`）**：失败收口在「无 console + editor 空 + 有最近结果」时经 invokeLater 重建预览编辑器；`forceSync` 测试缝供 headless 断言；三态守卫（有 console/editor 不重建、无结果不重建）防误覆盖与无中生有。
- **测试**：新建 `NoDataSourceGreenResultPanelTest` 4 例（M1 自绘不透出、M2 触发重建、两负向守卫）——**4/0 绿**。
- **反向验证**：临时把 M1 改回 `isOpaque=false`、M2 函数体改 `return false`（保留签名保证编译）→ **4/2 红**，且恰是"结果区自绘"与"触发重建"两断言变红，两负向守卫仍绿；还原后 **4/0 绿**。按用户令用 Edit 摘行为而非裸 stash（stash 会连函数签名一起摘致编译失败，非有效反向）。
- **守卫回归**：`ManualButtonPaintGuardTest` / `SqlPreviewPanelDisplayEditorTest` / `SqlPreviewPanelDegradedUxTest` 全绿（共享 UI 容器改动面）。
- **CHANGELOG**：`[Unreleased]` Fixed 段补一条。

### 待用户真机复核（代码层无法坐实的部分）

绿色"到底哪层承载"仍需 UI Inspector 取证（见 §一根因链下的取证步骤）。M1/M2 对两层承载都有效（自带不透明底色 + 失败重建覆盖），取证只为归档归因，不影响修复正确性。请用户按 §四验收标准在无数据源环境复看点验。
