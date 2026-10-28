# T-MagpieGems 合并 master 后功能验收文档（2026-10-28）

> 任务：master 并入 t-magpie-gems（merge `5b0492ed`，121 笔）后，对本分支新增与调整功能做全量验收。
> 方法：ide-ui-acceptance skill（证据分级：真机 GUI 实测 > 代码/字节码可判 > 定向测试收口 > 平台面限制登记）。
> 沙盒：`build/MPSV-MagpieGems-UI243`（init `.local/magpie-gems-ui243.init.gradle`，启动 `.local/start-magpie-ui243.ps1`，-PgifRobotServer=true）。
> demo：`mybatis-plus-sql-visualizer-demo`（IDE 内启动应用，控制台/增强双通道可用）。

## 一、验收矩阵

| ID | 功能与必验子场景 | 主要操作与判别点 | 证据级别 | 状态 | 证据 |
|----|------------------|------------------|----------|------|------|
| MG-01 | W2 脱敏分级：设置页四态下拉（关闭/打码/警示/阻断）+ 白名单文本域在场 | 设置 → SQL 日志安全分组：`settings.display.mask` 下拉四项、`settings.display.mask.allowList` 输入区 | GUI | **通过** | 设置搜索「展示与导出脱敏」直达 Tools→SQL Visualizer→SQL Log 页源码位置捕获组：四态下拉（关闭/打码(***)/警示(原文+计数)/阻断(隐藏 SQL)）+ 白名单域（示例 mobile_register）同帧在位（record/tmp/b1_combo_popup.png）；切「打码」Apply 生效（b1_applied.png 显示 Revert changes 出现） |
| MG-02 | W2 脱敏分级：日志列表渲染随模式变化（MASK=***、BLOCK=占位、WARN 预览首行提示、OFF 原样） | 设置切模式 → 日志列表含手机号形态条目观感对照 | GUI | **通过** | 语料 `joinPlaceholder?tel=13812345678`（增强通道条目 [MYSQL] MpjLocatorCoverageServiceImpl.java:164）：MASK=列表 `add.tel = '***'`（b1_list_state.png）；BLOCK=整段替换「已阻断：疑似敏感值 1 处；」（b1_block_list.png）；WARN=列表原文 + 停靠快速预览首行「⚠ 检测到疑似敏感值：手机号×1」（b1_warn_docked.png）；OFF=列表原文回显 `add.tel = '13812345678'`（b1_off_list.png）。四态全对 |
| MG-03 | W2 导出/复制从严：任一非关闭模式导出与 Ctrl+C 复制均打码 | 列表条目 Ctrl+C → 剪贴板读数；右键导出 → 文件读数 | GUI+产物 | **通过** | MASK 态 Ctrl+C → 剪贴板全文 `addr.tel = '***'`（含 WHERE）；BLOCK 态 Ctrl+C → 回落 MASK 全文（非占位，b1_copy_block 后剪贴板读数）；导出 SQL 文件 `sql-export-20261029-001826.sql` 内 `addr.tel = '***'`（toast 确认导出路径） |
| MG-04 | W2 白名单豁免：列名关键词命中列豁免识别（连兜底判定都跳过） | 白名单填关键词 → 含该列名的敏感值恢复原样 | GUI | **不通过（UI-001）** | 白名单 `tel` + MASK（options XML 实证 `displayMaskAllowList="tel"`/`displayMaskMode="MASK"` 已持久化）→ 日志列表仍 `***`（b1_whitelist_list.png）；MASK→OFF→MASK 模式循环排除缓存陈旧（b1_cycle_mask_list.png）。**代码级根因＝白名单只接进停靠快速预览链**：`SqlListFragmentRenderer.kt:40/44`（列表显示）、`LogEntryFiltering.kt:162`（Ctrl+C 复制）、`SqlExportService.kt:53/176`（导出）、`ReplayScriptExport.kt:92`（重放导出）均未传 `displayMaskAllowList()`，`maskDisplay(sql, mode)` 第三参默认 emptyList；已接线的仅 `SqlDockedPreview.kt:111 → SqlClickPreview.kt:66`。停靠预览的白名单行为未获 GUI 复证（面板内容不随模式切换重渲染，截图停留在 WARN 旧渲染；警示行仅 WARN 模式追加——SqlClickPreview.kt:68，故 MASK 态截图里的警示条是旧渲染非新证据） |
| MG-05 | W3 坏 State 文件旁路 guard：坏 XML → 启动巡检恢复默认并留 LastGood | 沙盒 options 写坏 XML → 重启 → 设置正常、`.lastgood` 文件产生、通知/日志 | GUI+重启 | 未验 | |
| MG-06 | W4 执行错误五档分类：权限类错误提示出路（压过对象缺失）、连接错误分类 | demo 真库执行无权限/错对象语句 → 提示文案分类与出路提示 | GUI | 未验 | |
| MG-07 | O-150 回滚按钮：无备份态 tooltip「暂无可用备份」 | 设置 → 更新节「回滚上一版」按钮 hover | GUI | 未验 | |
| MG-08 | O-150 回滚主链：有备份态 → 确认框 → 调度 DeleteCommand+UnzipCommand + 当前版本入忽略列表 | 造备份场景 → 点回滚 → 确认 → 状态标签「已回滚——重启后生效」；重启后版本回落（重启批） | GUI+重启 | 未验 | |
| MG-09 | W1/W5/W6/W7 文档与守卫面：LESSONS/code-standards/开发流程/子系统文档在位；TestGateGems 守卫定向绿 | 静态面 + 定向测试 | 静态/测试 | **通过** | 文档 7 篇在位实测（LESSONS 16959B / code-standards 4547B / 开发流程两篇 / 子系统两篇 / AGENTS.md）；HardcodedUiTextGuardTest / IconResourceGuardTest / GiantFileBudgetTest 定向绿（GiantFileBudgetTest 首跑 1 红=DataSourceSelectorResults.kt 2162>2133 合并连带，按守卫提额条款 2133→2194 修复，a815b5d3） |
| MG-10 | 合并语义收口：W2×master 渲染重构共存（RailCellRendererBase maskMode）、SqlListV179HeadlessTest 等 W2 连带测试绿 | 定向测试 | 测试 | **通过** | 合并后编译绿 + 定向 16 类 **248/248**（含 SqlListV179HeadlessTest / SqlLogPanelRendererUiTest / HistoryCellRendererTest / DisplayMaskerTest / SqlLogListCopyTest / SqlDockedPreviewTest / SqlClickPreviewTest / StateFileLastGoodTest / ExecutionErrorCategoryTest / UpdateBackupStoreTest / UpdateSectionTest / PluginSettingsPersistenceTest / SettingsTruthTest；2m05s）；冲突裁决语义=master 渲染收拢进 RailCellRendererBase 后 W2 maskMode 缓存键改造重新落位，旧布尔 API 零残留（grep 实测） |

## 二、定向测试清单

- `SqlListV179HeadlessTest`（W2×master 渲染重构共存，合并冲突直接当事类）
- `SqlLogPanelRendererUiTest` / `HistoryCellRendererTest`（渲染器）
- `DisplayMaskerTest` / `SqlLogListCopyTest` / `SqlDockedPreviewTest` / `SqlClickPreviewTest`（W2 面）
- `StateFileLastGoodTest`（W3）；`ExecutionErrorCategoryTest`（W4）
- `UpdateBackupStoreTest` / `UpdateSectionTest`（O-150）
- `PluginSettingsPersistenceTest` / `SettingsTruthTest`（设置持久化）

## 三、实施记录

- 23:38 合并 master 完成（merge `5b0492ed`，121 笔；冲突 3+1 处裁决，W2 适配 RailCellRendererBase 重叠面）；LESSONS 夜间复盘日志单独入账（ed7acd35）。
- 23:46 定向测试 16 类 248 例：GiantFileBudgetTest 1 红（DataSourceSelectorResults.kt 2162>2133，master 侧 ExecFileBinding 改造 +29 行的合并连带）→ 按守卫提额条款 2133→2194（a815b5d3）→ 复绿。终态 248/248。
- 23:48 首次启动 runIde 失败：build.gradle.kts:181 要求先 bootstrap robot-server——`python record/bootstrap-robot-server.py` 下载 0.11.23 校验通过后重启沙盒（教训：本仓 -PgifRobotServer=true 前置 bootstrap，与 dev 树缓存不共享）。
- 沙盒 build/MPSV-MagpieGems-UI243（init 脚本独立沙盒 + autoReload=false，启动脚本 .local/start-magpie-ui243.ps1）。
- 语料：B1 用 `GET /mpj/locator/joinPlaceholder?tel=13812345678`（demo 既有端点，还原 SQL 含手机号形态字面量）；B3 用预览面板执行 `SELECT * FROM no_such_table_xyz`（OBJECT_MISSING）与语法错误（SYNTAX），PERMISSION/CONNECTION 以 ExecutionErrorCategoryTest 测试收口；B2 备份预置探针 record/stage_update_backup.py（复刻 UpdateBackupStore 磁盘形态：update-backup/ + rollback-*.zip 顶层目录名 + manifest.json 四键）。

## 四、缺陷登记

| ID | 发现批次 | 现象（实际 vs 预期） | 根因（代码级） | 级别 | 处置 |
|----|----------|----------------------|----------------|------|------|
| UI-001 | B1（MG-04） | 白名单填 `tel` 且模式=打码（options XML 已持久化）后，日志列表、Ctrl+C 复制、SQL 导出仍打码该列；预期=白名单列豁免一切识别 | 白名单实参只接进停靠快速预览链（`SqlDockedPreview.kt:111`）；`SqlListFragmentRenderer.kt:40/44`、`LogEntryFiltering.kt:162`、`SqlExportService.kt:53/176`、`ReplayScriptExport.kt:92` 调 `maskDisplay(sql, mode)` 未传第三参（默认 emptyList） | P2（功能链断裂但非数据损坏；从严重叠面不受影响——复制/导出仍按 MASK 打码，只是豁免不生效） | 待批末统一修：四处调用点补传 `PluginSettings.getInstance().displayMaskAllowList()` + 定向测试（改白名单后列表/复制/导出三面豁免）+ 反向探针（摘接线恰红）；修复落在 src/main ⇒ 收口复跑 |

## 五、状态去向

（收尾回填）
