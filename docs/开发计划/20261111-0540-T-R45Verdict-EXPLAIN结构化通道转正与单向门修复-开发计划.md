# T-R45Verdict：EXPLAIN 结构化通道转正收口与单向门核查修复（R45 拍板实施）

> **创建**：2026-11-11 05:40｜**分支**：`t-r45-verdict`（独立工作树 `.worktrees/t-r45-verdict`，基点 master `b8e6ca6a`）
> **拍板出处**：`docs/开发计划/20260916-1730-待完成开发计划全景盘点.md` #13（e498195b 落册）——
> **✅ 2026-11-11 用户定案：转正保留＋维持默认关＋修单向门＋OB 文本树规则补齐**；默认开案与摘除案均废止，
> 「索引建议迁 IDE 面露出」随转正一并取消（建议条维持挂在结构化面板）。
> **登记**：parallel_tasks `r45-verdict` / sandbox `r45verdict`（logbucket-phase2 在册属预期，构建并发 ≤2）。

## 一、拍板内容（三件套）

| # | 事项 | 口径 |
|---|---|---|
| ① | 转正收口（KDoc/文案层） | `PluginSettings.explainStructuredRender` KDoc 追加拍板结论：**转正保留、维持默认关**，摘除案废止；设置页开关文案去掉「实验特性/experimental」（转正后该口径失真）。不改任何行为。 |
| ② | 修单向门 | 盘点原案＝「设置页补『EXPLAIN 结构化渲染』开关行」。**开工核实推翻前提：该开关行早已存在**（见 §二），单向门已在 2026-10-01 关闭——本轮不重复加行，核实结论落册即收口。 |
| ③ | OB 文本树规则补齐 | `ExplainIndexAdvisor.advisePlanText` 只认 PG 文本树 `Seq Scan on`，OB 真机 ASCII 计划树（`TABLE RANGE SCAN`/`TABLE FULL SCAN`）不命中——样本已在档（§四），按样本补规则＋单测＋反向验证。 |

## 二、现状证据（开工实测，file:line）

### ② 的前提核实：设置页开关行**已存在**（0df4a311，2026-10-01）

- 字段：`src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt:519` `internal val explainStructuredCheck = JBCheckBox()`
- 行布局：`PluginSettingsPanelSections.kt:253-256`——`addRow(p, g, "", explainStructuredCheck.apply { text = "settings.explain.structured"; toolTipText = "settings.explain.structured.tip" })`，位于**组 1：SQL 预览（默认展开）**（:174-175 `SECTION_PREVIEW` 恒在 `SECTION_ALL`，PluginSettingsPanel.kt:92），行序在 JdbcTemplate 预览开关之后、R90 原始计划保留开关之前
- 三点接线：isModified `PluginSettingsPanel.kt:1229`、apply `:1363`、reset `:1539`
- 双语 bundle：`MpSqlVisualizerBundle.properties:790-791`（en）/ `MpSqlVisualizerBundle_zh.properties:764-765`（zh）
- 守卫计数：`PluginSettingsPersistenceTest.kt:220` **当前 49**（台账行 :209 已记「R33 T-ExplainStructured：…+1 后为 41」）；持久化往返用例在册（:264 注释锚）
- 真机教训注释（T-ExplainConsoleFirst W6，2026-10-24）：`PluginSettings.kt:714-727`——其「事故形态」段描述的是**历史**（通知每次弹、点过一次永久 true）；末段已写明「用户想开仍可去设置页显式勾选（不被此标记阻断）」

### 单向门代码路径（「点过一次置 true」的实体）

- 通知动作：`SqlPreviewPanelExecution.kt:2163-2196`（`StructuredExplainNotificationAction` / `buildStructuredExplainDisabledNotification`，bundle key `panel.explain.structured.enableAndRetry`，`MpSqlVisualizerBundle_zh.properties:233`）
- 写入点：`SqlPreviewPanelExecution.kt:2202-2213` `enableStructuredExplainAndRetry` → `PluginSettings.getInstance().state.explainStructuredRender = true` + 发布 SETTINGS_CHANGE_TOPIC → 重试
- **关闭路径已存在**：设置页勾/去勾 `explainStructuredCheck` → apply 写同字段（:1363）→ 单向门不成立。0df4a311 提交信息自证：「复选框（explainStructuredCheck…）＋i18n 双语两键＋契约守卫 39→40＋持久化往返用例」

**结论**：盘点行与任务书所述「界面上没有任何地方能关回去」系对 W6 注释的过时转述（该行 2026-10-01 已入库）。本轮**不重复加行**（重复加=同一控件两份、守卫计数虚增）；守卫计数**前值=后值=49**。①的文案层收口（去「实验特性」）照做。

## 三、① 改动清单

- `PluginSettings.kt:587-596` KDoc 追加 R45 拍板段（转正保留、维持默认关；摘除案与默认开案均废止；默认值史：R33 初版默认开（0df4a311/c52f436e）→ T-DevAcceptFix 20261006 定默认关＋「保留一版周期」→ 2026-11-11 到期裁决关闭）
- `MpSqlVisualizerBundle_zh.properties:764` / `MpSqlVisualizerBundle.properties:790`：label 去「实验特性/experimental」，保留「默认关闭：走原生控制台」；tip 双句已写清「关=原生控制台执行与呈现 / 开=面板内结构化渲染＋索引建议」，不动
- 守卫：`PluginSettingsPersistenceTest` 文案正反断言只含 `结构化渲染 EXPLAIN`/`Structured EXPLAIN` 子串，不受影响；计数 49 不动

## 四、③ 样本与规则设计

**样本＝真机逐字读数，已在档**（提交 `58b8b5f3` 正文，2026-10-30 T-OBExplainFormat；同口径 `ExplainSupport.kt:138-146` OceanBase 条目、`docs/变更记录.md` T-OBExplainFormat 条目）：

- 镜像 `oceanbase/oceanbase-ce:4.3.5-lts`（OB CE 4.3.5.6，`ob_compatibility_mode=MYSQL`）
- `EXPLAIN SELECT 1` 单列 `Query Plan`，值＝OB ASCII 计划树：
  ```
  ==========================================
  |ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|
  |0 |EXPRESSION|    |1       |1           |
  ==========================================
  Outputs & filters:
    0 - output([1]), filter(nil) / values({1})
  ```
- 真实表算子：`TABLE RANGE SCAN`（真机读数 `EXPLAIN SELECT * FROM t_lim WHERE n > 1`，带 `partitions(p0)`/`is_index_back`/`range_key/range_cond`）与 `TABLE FULL SCAN`（同族算子名，ExplainSupport 条目在案）
- **留证边界如实登记**：表头＋EXPRESSION 行＋算子名逐字在档；表扫整行未逐字落盘——解析**按表头声明的列名取值（OPERATOR/NAME/EST.ROWS），不按固定列位猜**，列宽/补空格差异不影响；测试夹具按表头列序填充表扫行并注明此边界

**规则**（OB 表头 `|ID|OPERATOR` 为门，其余方言文本计划不含该表头天然零命中）：
- `TABLE SCAN`/`TABLE FULL SCAN` → `TABLE_FULL_SCAN`（EST.ROWS 超阈才提示）
- `TABLE RANGE SCAN`（已走索引）→ 不报缺索引；EST.ROWS 超阈才报 `HIGH_ROWS_ESTIMATE`
- `TABLE GET`/`EXPRESSION` 等 → 不提示（点查与常量计划无索引话题）
- `RULE_INVENTORY` +1 条 `TABLE_FULL_SCAN`（体检规则清单呈现层）

**实现来源说明**：dev 支 `1d9f2770`（2026-10-31「feat(explain): 补 OceanBase ASCII 计划树索引建议规则（R45 ③）」）已实现同款，但其基座带 T-Fr45DmGuard（DM 档位 TEXT→TABLE）未回流 master，cherry-pick 必冲突——本分支**手工移按**其 OB 增量（advisor＋测试两文件），适配 master 现状（DM 仍 TEXT 档）。

## 五、测试与反向验证计划

- ①：`--tests "cn.song.mybatisplus.visualizer.config.PluginSettingsPersistenceTest"`（守卫计数 49＋双语文案断言）
- ③：`--tests "cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisorTest"`（OB 新用例＋PG/MySQL 既有不误伤）
- ③反向验证：摘除 OB 门（注释 `OB_PLAN_HEADER` 分派）→ OB 用例必须红（贴读数）→ 还原 → 绿（贴读数）；源码 grep `RED PROBE` 清零
- 全程 `-Pkotlin.compiler.execution.strategy=in-process`；禁全量/禁 clean/禁 --stop

## 六、实施记录（回填）

> 2026-11-11 05:5x 回填（实施于本工作树，主仓副本只读）。

### 提交链（`t-r45-verdict`，基点 master `b8e6ca6a`；不合回、不 push、不打 tag）

| 提交 | 内容 |
|---|---|
| `ec2d225f` | docs(计划): 立项册（拍板/现状证据/改动清单/测试计划，即 §一~§五） |
| `a504bd10` | R45 ①转正收口：`PluginSettings.kt` explainStructuredRender KDoc 追加拍板段＋设置页 label 双语去「实验特性/experimental」（3 文件，+9/-2） |
| `9219ca61` | R45 ③OB ASCII 计划树规则：`ExplainIndexAdvisor` 门分派＋`adviseObPlanText`＋RULE_INVENTORY +1＋测试 +7 用例（2 文件，+183/-2） |
| （本提交） | 本册 §六 实施记录回填 |

### 测试读数（均 `-Pkotlin.compiler.execution.strategy=in-process`，定向，未跑全量）

- **绿（合并轮）**：`ExplainIndexAdvisorTest` **32/0**（25→32，+7 OB 用例）＋`PluginSettingsPersistenceTest` **19/0**（复选框计数 49 断言＋双语文案断言在内）——BUILD SUCCESSFUL in 33s
- **反向验证（③两读数）**：
  - RED PROBE（`advisePlanText` 的 `OB_PLAN_HEADER` 门三行注释掉）→ `ExplainIndexAdvisorTest` **32 例 / 2 失败**，失败点名恰为 `testR45_OB全表扫超阈提示`（AssertionError `ExplainIndexAdvisorTest.kt:315`）与 `testR45_OB范围扫超阈_报估算不报缺索引`（`:332`）——BUILD FAILED in 2m 10s
  - 还原后复跑 → 32/0＋19/0 绿；源码 grep `RED PROBE` **零命中**
- **守卫计数**：前值 **49** ＝ 后值 **49**（②核实结论＝不重复加行，无新复选框；`PluginSettingsPersistenceTest.kt:220` 未动）

### 与任务书/盘点册的差异说明（核实推翻项）

- ②「设置页补开关行」前提不成立：开关行 2026-10-01（0df4a311）已在册（证据见 §二）——本轮未新增任何 UI 行，单向门修复实质早已闭环；已核实的现状证据与 0df4a311 时间线落册备查
- ③ 的实现与 dev 支 `1d9f2770`（2026-10-31）同构（该支基座带未回流的 T-Fr45DmGuard，cherry-pick 必冲突，故手工移按）；OB 样本真机逐字读数出处＝`58b8b5f3` 提交正文（§四）

