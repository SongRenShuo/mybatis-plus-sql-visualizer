# T-RollbackSnapshotCsvView：回滚快照原生 CSV 表格能力内嵌与视图切换修复

2026-10-20。来源：用户在 dev 分支真机验收回滚 SQL 时提出——快照表格「查看不方便、无法切换展示模式」，希望内嵌 IDEA 原生 CSV/TSV 表格能力。本轮只出计划，不实施。

## 一、现状与问题

UI-036 已把快照改成「只读 JBTable + 文本」双态，但实际使用仍有四个缺口（截图证据：快照表格已显示 4 行 × 6 列，但视图切换按钮不可见）：

1. **切换按钮被布局裁掉**：`buildSnapshotBlock` 的表头用 `FlowLayout` 顺序放「快照 SQL 标签 + 执行快照查询 + 复制快照 SQL + 表格/文本切换」。标签文本很长（含完整 SELECT），窄宽下 FlowLayout 换行，但容器 preferred height 按单行计算 → 换行出来的切换按钮被裁掉，用户看不到切换入口。
2. **表格能力弱**：无排序、无列宽拖拽/自适应、无单元格/行列复制、无右键菜单、无筛选；列宽是默认值，宽表看不全。
3. **与 IDEA 原生 CSV/TSV 表格体验差距大**：用户期望 DataGrip 风格的表格（排序、列宽、复制、表格/文本切换）。
4. **原生能力是否可复用未定**：需要确认平台 CSV 编辑器能否嵌入，还是只能「打开临时文件」复用。

## 二、平台事实（243 实测）

- 243 Ultimate 自带 grid 插件：`plugins/grid-impl/lib/grid-impl.jar` 的 `META-INF/plugin.xml` 注册了
  `<fileEditorProvider id="csv-data-editor" implementation="com.intellij.database.editor.CsvTableFileEditorProvider" fileType="CSV/TSV" isDocumentRequired="true"/>`。
  **打开一个 `.tsv` 文件即可获得原生 CSV 表格编辑器**（自带表格/文本视图切换、排序、列宽、复制、筛选等）。
- `CsvTableFileEditor` / `CsvTableFileEditorProvider` 属 grid 插件内部类，**无公开 API 承诺**；直接 new 并嵌入对话框有生命周期/Disposer/文件类型前提风险。
- Community 发行版无 Database/grid 插件 → 原生 CSV 表格不可用，必须降级为自建表格或文本编辑器。
- 本项目 `verifyGate` 三目标为 Ultimate（IU），但插件运行期应容忍 Community（既有能力探测惯例：`ExecuteSqlSupport.isAvailable()` 等）。

## 三、方案

### A. 对话框内增强表格（必做，全平台可用）

- `TableRowSorter` 挂到只读模型（排序只影响视图，不改数据）；列头点击排序。
- 列宽：双击列头自适应（按内容测量、设上限）；提供「自适应列宽」按钮；允许拖拽列宽（`AUTO_RESIZE_OFF` 下默认支持）。
- 复制：单元格/行/列选择后 Ctrl+C 复制 TSV；右键菜单「复制单元格 / 复制行 / 复制列 / 全选 / 自适应列宽」。
- 行号列（`rowHeader`）与列头 tooltip（列名）。
- 大快照（千行级）性能：排序/复制均为内存操作，无需分页；如卡顿再加「仅显示前 N 行」开关。

### B. 一键「在 CSV 编辑器中打开」（推荐，复用原生能力）

- 快照区新增按钮「在 CSV 编辑器中打开」：把当前快照写成临时 `.tsv`（表头 + 行，UTF-8），经 `FileEditorManager.openFile` 打开。
- Ultimate：平台按 `csv-data-editor` 自动进入原生 CSV 表格编辑器（可切文本）；Community：降级为普通文本编辑器（按钮文案/tooltip 说明降级）。
- 临时文件放 `PathManager.getTempPath()`（或 `build/` 下的插件专属目录），命名带时间戳；对话框 dispose 时删除（若编辑器仍打开则延迟到会话结束，避免删正在编辑的文件）。
- 不直接内嵌 `CsvTableFileEditor`（内部 API + Disposer 风险）；如后续确有「必须内嵌」需求，再评估 `FileEditorProviderManager` 路线（列为可选 C）。

### C. 布局修复（必做）

- 把「表格视图 / 文本（粘贴/编辑）」切换按钮移出 FlowLayout 行尾：改为快照标题行右侧（`BorderLayout.EAST`）或表格上方独立一行，保证窄宽不裁切、位置稳定可发现。
- 摘要「快照：N 行 × M 列」与切换按钮同排右侧，避免与长 SQL 标签争宽。

## 四、待验证前提（实施前逐条证伪）

1. `.tsv` 文件打开后**默认**是表格视图还是文本视图（受 `CsvSettings` 的默认视图设置影响）——查 243 设置键并实测；若默认文本，需要给出「切到表格」的引导或设置说明。
2. `FileEditorManager.openFile` 在 modeless 对话框上下文中可用；临时文件写盘 + `LocalFileSystem.refresh` 的线程要求（VFS refresh 属慢操作，BGT 或 EDT 派发）。
3. 临时文件删除时机：`dialog.dispose()` 时编辑器可能仍持有文件（`FileEditorManager.isFileOpen`），需延迟删除或保留到项目关闭；确认不会在 Windows 上留下锁定文件。
4. `TableRowSorter` 与 `DefaultTableModel`（只读 `isEnabled=false`）兼容；排序后 `generateFromPaste` 仍以文本为权威来源（表格只读展示）。
5. 列宽自适应的测量成本（大表/长文本）；设置上限避免单列撑爆。
6. Community 降级判据：用现有能力探测（grid/Database 插件是否在场）决定按钮文案与行为，不硬编码 Ultimate。

## 五、实施步骤（文件级落点）

1. `RollbackSqlDialog`：
   - 快照表头布局改造（切换按钮 + 摘要右对齐）；修复 FlowLayout 裁切。
   - `snapshotTable` 增强：`TableRowSorter`、行号列、列头 tooltip、双击列头自适应、右键菜单与复制。
   - 新增「在 CSV 编辑器中打开」按钮与临时文件写入/打开/清理（`FileEditorManager`、`PathManager`、`LocalFileSystem.refresh` 线程纪律）。
2. 文案键（zh/en）：`rollback.snapshot.openCsv`、`rollback.snapshot.autofit`、`rollback.snapshot.copyCell/copyRow/copyColumn`、降级说明。
3. 测试：
   - 排序（点击列头后行序变化、原模型不变）；
   - 复制单元格/行/列到剪贴板（FakeClipboard）；
   - 临时 TSV 内容与清理；
   - 切换按钮在窄宽下可见（组件 bounds 断言，替代截图）；
   - Community 降级路径（能力探测为 false 时按钮文案/行为）。
4. 反向验证：摘排序/摘复制/摘切换按钮布局 → 对应用例必红。

## 六、验收

- 定向测试（回滚弹窗 + 面板 + 生成器 + 新增用例）全绿，反向恰 N 红。
- 真机（243）：对话框内排序/列宽/复制可用；切换按钮稳定可见；「在 CSV 编辑器中打开」进入原生表格且可切文本；Community 降级路径（如可造环境）不报错。
- 大快照（≥1000 行）打开与排序不卡顿。

## 七、风险与边界

- 不直接内嵌 grid 内部类；只用公开 `FileEditorManager`，避免 253/262 API 漂移。
- 临时文件清理与 Windows 文件锁需实测；必要时改为「保留到项目关闭」并在 tooltip 说明路径。
- 表格仍为只读展示；生成回滚的权威来源仍是文本（不变）。
- 本轮只出计划，实施需用户确认。

## 实施记录（2026-10-20，A/B/C 全部落地）

**A 对话框内表格增强**：`DefaultTableModel` 改为不可编辑子类 + `TableRowSorter`（点击列头排序，**只影响视图不改模型**）；行号列（`JBList` 行头，随排序重排）；列头 tooltip 返回模型列名（排序后仍是原列名）；双击列头 = 该列自适应宽、「自适应列宽」按钮 = 全列（按内容测量，上限 360px，下限 48px）；`cellSelectionEnabled` + `rowSelectionAllowed` + Ctrl+C/Ctrl+Insert 复制选择为 TSV（无选择时复制全表）；右键菜单：复制单元格/行/列、全选、自适应列宽；复制列时首行为列名（与结果网格复制口径一致）。

**B 在 CSV 编辑器中打开**：「在 CSV 编辑器中打开」按钮 → 快照写临时 `.tsv` → `FileEditorManager.openFile`。写盘在池线程（`WriteIntentReadAction` + `VfsUtil.markDirtyAndRefresh` 异步刷新）；单测模式同步执行（headless 无 EDT 泵，池线程回调不派发）。临时文件落在 `java.io.tmpdir`（**不在 IDE VFS 允许根内也能写**：测试沙盒只允许 `C:/Users/USER/AppData/Local/Temp` 等少数根，`PathManager.getTempPath()` 指向沙盒 system-test/tmp 会被 `VfsRootAccessNotAllowedError` 拒绝）。对话框 `dispose()` 时回收：仍被编辑器打开的保留，其余删除。Ultimate 由 grid 插件 `csv-data-editor` 自动进入原生表格，Community 降级为文本编辑器（tooltip 说明）。

**C 布局修复**：快照区第一行 = SQL 标签（CENTER 可压缩）+ 摘要/视图切换（EAST 固定）；第二行 = 动作按钮（执行快照查询 / 复制快照 SQL / 在 CSV 编辑器中打开 / 自适应列宽）。旧实现把切换按钮放在 FlowLayout 行尾，窄宽换行后被裁掉（用户截图即此形态）。

**文案**（zh/en 同步）：`openCsv` / `openCsv.tooltip` / `openCsv.failed` / `autofit` / `autofit.tooltip` / `copyCell` / `copyRow` / `copyColumn` / `selectAll`。

**读数**：定向（回滚弹窗 + 面板 + 生成器）**102/0/0**（独立沙盒 `MPSV-CsvView`）；反向三处（摘排序 / 摘复制 / 摘临时文件写入）→ **恰 3 红**（三条用例各自独立命中），恢复后全绿、探针零残留。

**新用例**：排序不改模型且行号随视图重排、选择复制为 TSV、长列自适应、临时 TSV 写出+打开+dispose 清理、窄宽（640px）下切换按钮可见、两个新按钮文案在位。

**遗留**：真机复验待用户（需重建插件包）；「在 CSV 编辑器中打开」在 Community 的降级路径未造环境实测；大快照（≥1000 行）排序/复制性能未实测。
