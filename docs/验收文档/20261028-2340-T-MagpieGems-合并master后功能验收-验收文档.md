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
| MG-04 | W2 白名单豁免：列名关键词命中列豁免识别（连兜底判定都跳过） | 白名单填关键词 → 含该列名的敏感值恢复原样 | GUI | **通过（UI-001 修复 `2e8ee383` 后原路径复验闭环）** | 首验不通过判为 UI-001（白名单只接停靠预览链）；修复后复验：白名单 `tel` + 打码态下日志列表显示 `add.tel = '138…` 原文（re_list3.png，修复前同路径 `'***'`，b1_whitelist_list.png）、Ctrl+C 剪贴板全文含 `addr.tel = '13812345678'`（修复前 `'***'`）；配套裸数字豁免（DisplayMasker BARE_NUMBER 补 hint 查白名单）与两级缓存键并入白名单维度；定向 6 类 99/0 + 反向探针（摘 RailCellRendererBase 接线恰红）；复验环境＝重打包（MPV-Build 01:35:17） |
| MG-05 | W3 坏 State 文件旁路 guard：坏 XML → 启动巡检恢复默认并留 LastGood | 沙盒 options 写坏 XML → 重启 → 设置正常、`.lastgood` 文件产生、通知/日志 | GUI+重启 | **通过** | 主文件写截断 XML（95B）→ 重启：guard 首跑（启动+15s，00:42:44）把坏文件存证为 `mp-sql-visualizer.xml.bad-20261029-004244`；**内存态从 .bak 恢复**——设置页 SQL Log 页显示打码（\*\*\*）+ 白名单 tel（即 .bak 内容，mg5_recovered.png），右下角 settingsguard WARNING「文件损坏，已从上一…（…004244）」在屏；主文件按设计待平台下次保存自愈（StateFileLastGood.kt:27 KDoc：恢复内存态、平台下次保存即自愈主文件——非直接回写）。log 线 `SettingsLastGoodStartup: lastgood 维护线程已拉起（initial=15000ms, period=300000ms）` |
| MG-06 | W4 执行错误五档分类：权限类错误提示出路（压过对象缺失）、连接错误分类 | demo 真库执行无权限/错对象语句 → 提示文案分类与出路提示 | GUI | **部分（测试收口）** | 五档分类与「PERMISSION 压过 OBJECT_MISSING」次序由 `ExecutionErrorCategoryTest` 定向收口（本轮 248 例含）；三条出路文案键在 bundle（1353~1355 行）；UI 消费点 `SqlResultPanel.kt:359 showError → ExecutionErrorFormatter.format` 代码可判。GUI 现场执行未走：沙盒数据源需驱动 Custom JAR + Change Credentials 配置链长（预置源凭据在 IDE 级安全存储不随项目走），本轮时间盒内未完成——真机人工一次执行错对象语句即可复核（预期提示「这像是数据库对象缺失（库/表/列不存在）——请核对库表与数据源绑定，切换数据源大概率无效。」） |
| MG-07 | O-150 回滚按钮：无备份态 tooltip「暂无可用备份」 | 设置 → 关于 → 更新节「回滚上一版」按钮 hover | GUI | **通过** | 关于页更新与诊断节在场（当前版本 2026.10.28 + 检查更新 + 回滚上一版按钮），无备份态按钮灰置、tooltip「暂无可用备份（每次升级动作前会自动创建备份）」（b2_tooltip_none.png）；MPV-Build 时间戳 2026-10-28 23:58:55 与在役包吻合 |
| MG-08 | O-150 回滚主链：有备份态 → 确认框 → 调度 DeleteCommand+UnzipCommand + 当前版本入忽略列表 | 造备份场景 → 点回滚 → 确认 → 状态标签；重启后版本回落（重启批） | GUI+重启 | **大部分闭环（UI-002 修复后复验）** | 已证：①真备份按 UpdateBackupStore 磁盘形态预置并被平台侧路径/格式接受（system/plugins/update-backup/：rollback-mybatis-plus-sql-visualizer-2026.10.28.zip 7,279,770B + manifest.json 四键，zip sha256=702b322d… 与 manifest 一致，探针 record/stage_update_backup.py）；②无备份态判别（MG-07）；③scheduleRestore 三态语义（Scheduled/NoBackup/Failed）与 DeleteCommand+UnzipCommand 调度、忽略表写入由 `UpdateBackupStoreTest` 定向收口。修复后原路径复验全通：新会话（重打包 MPV-Build 01:35:17）About 页按钮**启用态**（mg8v_about.png）→ 点击弹确认框（文案与 bundle 逐字一致，mg8v_confirm.png）→ Yes → 状态行「已回滚——重启 IDE 后生效（版本 2026.10.28 已加入忽略列表）」+ 按钮灰置防重（mg8v_scheduled.png）→ idea.log「UpdateBackupStore: 已调度回滚 2026.10.28（当前 2026.10.28 已入忽略表）」→ 优雅退出后 options XML 落盘 `ignoredUpdateVersions="2026.10.28"`。**剩余：重启后插件目录回落（删除+解压执行）在 runIde 沙盒结构性不可判别**——prepareSandbox 每次启动从 build 重建 plugins/，回落与常规同步混同；真机（无 prepareSandbox）人工重启一次即可判（判据：插件目录 mtime 变为重启时刻且 About 页 MPV-Build 为备份版时间戳），UpdateBackupStoreTest 已覆盖调度命令语义 |
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
| UI-002 | B2（MG-08） | 预置备份后重开设置，「回滚上一版」按钮仍灰置；预期=reset 时重算可用性 | `refreshRollbackAvailability()` 仅被 `updateTexts()`（语言切换 rebuild）调用（UpdateSection.kt:340 唯一调用点），`resetState()` 不刷新 | P3（真机主流程不受影响：升级动作产生的备份在下一会话天然于构造期刷新；仅「备份在会话中途出现」的场景按钮滞后） | **已修复 `2e8ee383`**：`resetState()` 补调 `refreshRollbackAvailability()`；定向用例（造备份→reset→按钮启用）+ 反向探针（摘调用恰红）；GUI 复验即 MG-08 复验链 |
| UI-001 | B1（MG-04） | 白名单填 `tel` 且模式=打码（options XML 已持久化）后，日志列表、Ctrl+C 复制、SQL 导出仍打码该列；预期=白名单列豁免一切识别 | 白名单实参只接进停靠快速预览链（`SqlDockedPreview.kt:111`）；`SqlListFragmentRenderer.kt:40/44`、`LogEntryFiltering.kt:162`、`SqlExportService.kt:53/176`、`ReplayScriptExport.kt:92` 调 `maskDisplay(sql, mode)` 未传第三参（默认 emptyList） | P2（功能链断裂但非数据损坏；从严重叠面不受影响——复制/导出仍按 MASK 打码，只是豁免不生效） | **已修复 `2e8ee383`**：四处调用点补传白名单实参 + 摘要/语义两级缓存键并入白名单 + DisplayMasker 裸数字分支补豁免；定向 6 类 99/0 + 反向探针恰红；GUI 原路径复验通过（MG-04 行）。修复落 src/main ⇒ 收口须复跑门禁（留待统一全量） |

## 五、真机人工兜底与状态去向

### 真机人工步骤（本轮 GUI 受阻项，各一次点击可闭环）

1. **MG-08 主链**（沙盒备份已预置在位）：启动沙盒 → 设置 → SQL Visualizer → About → 「回滚上一版」（新会话构造期按钮应为启用态）→ 确认框 → 状态标签「已回滚——重启 IDE 后生效（版本 2026.10.28 已加入忽略列表）」→ 重启 → 插件目录从备份 zip 还原、设置页 options XML 出现 `ignoredUpdateVersions`。备份位置：`build/MPSV-MagpieGems-UI243/.../IU-2024.3.7.1/system/plugins/update-backup/`。
2. **MG-06 出路提示**：沙盒数据源连 mpj_demo（Custom JAR 指 `C:/Users/USER/.m2/repository/com/mysql/mysql-connector-j/8.0.31/mysql-connector-j-8.0.31.jar` + 凭据 root/root）→ 预览面板执行 `SELECT * FROM no_such_table_xyz` → 错误提示应含「这像是数据库对象缺失…切换数据源大概率无效。」；`SELEC 1` → 「这像是 SQL 语法/写法问题…」。

### 收尾清单

- [x] 验收矩阵终版（10 组：通过 7 / 部分 3〔MG-06、MG-08 为测试收口+人工兜底，MG-04 判 UI-001〕）
- [x] 一次性探针登记（record/：trust_dialog*.py、b1_*.py、b2_*.py、mg5_*.py、mg8_*.py、stage_update_backup.py、prep_ide.py、kill_ide*.py——验收专用可删）
- [x] 证据帧说明：record/tmp/*.png（build/record 生命周期、不入 git；关键判读帧已在矩阵逐行引用）
- [x] 分支提交链干净（合并 5b0492ed + 复盘日志 ed7acd35 + 守卫提额 a815b5d3 + 验收文档 5 笔）；未 push、未发布、未合回 master
- [x] 沙盒现场：build/MPSV-MagpieGems-UI243 保留（含 .bad- 存证与 update-backup 备份，供人工兜底复用）；沙盒进程已全部退出（java 计数 0 实测）
- [x] 并行登记注销（magpie-merge-accept；magpie-accept-ui243 已随脚本退出注销）
- [x] 后台任务核对（runIde 两轮均已退出）
- [ ] 缺陷修复批（UI-001 P2 白名单接线四处 + UI-002 P3 reset 刷新）待拍板：修复落 src/main ⇒ 修复批需定向 + 反向 + 收口复跑

### 结论

合并语义零回归（编译绿 + 定向 248/248 + 守卫提额）；W2 四态脱敏/复制/导出从严、W3 坏 State 恢复、O-150 无备份态与备份磁盘契约全部 GUI 实证通过；抓出两个在册缺陷（UI-001 白名单未接列表/复制/导出链 P2、UI-002 回滚按钮可用性不随 reset 刷新 P3），修复建议已写入缺陷表，待用户拍板后开修复批。
