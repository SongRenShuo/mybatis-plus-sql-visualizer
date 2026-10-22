# T-DialectStatusLiveRefresh：方言状态行随执行即时刷新与候选歧义消解

2026-10-19。来源：用户在 dev 分支真机验收 MPJ Lambda 预览时发现——执行 SQL 成功后方言状态行仍停留在「方言未验证：兼容按 MySQL 预览（候选方言不一致）」，关闭再打开预览才变成「方言: MySQL（模块已学数据源）」；同一时刻绑定行已正常升级为「已绑定: mpj_demo@localhost」。

## 一、现象复现

环境：dev 构建，MySQL demo 工程（同时存在 jpa_demo / mpj_demo 等多个数据源），MPJ Lambda 多表预览（`car_device_info t LEFT JOIN station_info st`）。

1. 打开预览：头部显示「方言未验证：兼容按 MySQL 预览（候选方言不一致）」，绑定行「数据源（上次使用）: mpj_demo@localhost」或「已绑定」。
2. 点「执行 SQL」：结果网格正常出现（说明执行成功、目标明确为 mpj_demo/MySQL），绑定行升级为「已绑定: mpj_demo@localhost」，**但方言状态行不变**，仍是「未验证 / 候选方言不一致」。
3. 关闭预览再打开：头部变为「方言: MySQL（模块已学数据源）」。

问题定性两条：

- **状态陈旧**：执行成功后决策输入已经变化（模块数据源学习已写入），面板却不重算、不重绘方言段；「已绑定」与「方言未验证」同时出现，自相矛盾。
- **歧义未被可用信号消解**：面板明明已绑定/记忆了具体数据源，其方言可从数据源 URL 确定，但该信号没有参与方言决策，只能退化为「候选不一致 → 按 MySQL 兼容预览」的警告态。

## 二、现状机制（代码证据）

1. 决策生产：`AnnotationSqlPreviewService.sourceDecision`（`AnnotationSqlPreviewService.kt:307-311`）→ `DialectRegistry.decision(context)`（`Dialect.kt:942-984`）；结果固化进 `PreviewResult.dialectDecision`（`PreviewResult.kt:130`，不可变字段）。
2. 决策输入（`Dialect.kt:942-983`）：手动方言、`DataSourceUrlProbe.candidateDialects(project)`、`ModuleDataSourceBindingStore.learnedCandidate(project, moduleKey)`、`lastAgentDialect`、`singleModuleProven`、PSI/IDEA 方言。**面板当前的绑定/上次使用数据源不是独立输入。**
3. 渲染：`updateMeta`（`SqlPreviewPanelResultView.kt:546-580`）读 `result.dialectDecision` → `DialectPreviewStatusText.describe` → `metaLineHead = dialectStatus.visible + "   " + meta + warningLine`，详情进 tooltip。文案键：`panel.meta.dialectStatus.fallback`（「方言未验证：兼容按 {0} 预览（{1}）」）、`panel.meta.dialectStatus.ambiguous`（「候选方言不一致」）。
4. 执行时的学习写入：`DataSourceSelectorConsole.kt:537-543` 在**通过提交前方言闸之后**才 `ModuleDataSourceBindingStore.remember(moduleKey, actualTarget.stableId)`；注释明示「预览 prepare、冲突或取消均不写绑定」。
5. 执行后的刷新现状：`embedConsole` 的 O-96fix2 钩子（`SqlPreviewPanelExecution.kt:1571-1587`）只调用 `refreshBindStateRow()`（`SqlPreviewPanelParamPanel.kt:443`，纯文本读），该钩子是执行/预览/复用三链的单点——所以绑定行升级了，**方言行没有任何刷新入口**。
6. 重开预览为何能刷新：`showResult` 生成新的 `PreviewResult`，重新执行 `sourceDecision`，此时 `learnedModule` 已命中 → `Status.RESOLVED` / `Source.LEARNED_MODULE` → 「方言: MySQL（模块已学数据源）」。
7. 同类潜在缺口：`rerenderForExecutionTarget`（`SqlPreviewPanelExecution.kt:607-639`）为 `AUTO_RERENDER_REQUIRED` 复制预览模型并换方言，但 `copy()` 不更新 `dialectDecision`——该路径下面板 meta 行同样会停留在旧决策。

## 三、待验证前提（实施前逐条证伪，勿先动手）

1. **学习写入时机**：`remember()` 在用户路径上确实执行，且 `learnedCandidate` 在同一会话立即可读（核对 `ModuleDataSourceBindingStore` 是内存态还是异步落盘；用临时日志打印执行前后 `learnedCandidate` 的返回值）。
2. **无 PSI 重算的保真度**：`DialectRegistry.decision(project, sourceFile=null, sqlPsiFile=null, moduleKey=sourceModuleKey)` 是否足以复现「重开后」的 `LEARNED_MODULE` 决策；若不足，必须保存完整 `PreviewSourceContext` 并处理 `PsiFile` 生命周期（失效后降级）。
3. **按名取 URL 的匹配面**：`DataSourceUrlProbe.urlByName(project, name)`（`DataSourceUrlProbe.kt:114-133`）按 `getName()` 匹配；先核对 `boundDataSourceNameForDisplay()` 返回的是否带 `@localhost` 后缀——若显示名与数据源名不一致，按名探测会取不到，方案 B 需先做名称归一。
4. **优先级插入点**：`DialectDecisionEngine` 现有优先级（手动 > 冲突 > IDEA 上下文 > 模块学习 > 项目候选/单一模块证明）中，绑定源方言应插在哪一级；必须保证「手动指定永远优先」「CONFLICT 仍阻断」不被削弱。
5. **EDT 约束**：`candidateDialects`/`urlByName` 走反射探测（有 TTL 缓存但首次可能慢），不可在 EDT 直调；重算必须在池线程完成，EDT 只做文本落地（对齐项目既有线程归位两段式）。
6. **`updateMeta` 重入安全**：不能整函数重跑（会重触发警告横幅、错误按钮显隐、降级 banner 与 F3 宽度预算 reset）。需抽出「只重组方言段 + meta 文本 + tooltip」的轻量路径，并验证 `applyMetaTruncation()` 在预算复位后正确。
7. **执行链覆盖**：确认三条执行链（数据源选择器、T-SI-02 静默复用、预览内嵌控制台）都经过 `embedConsole`；同实例 early-return 分支下钩子仍生效（O-96fix2 已把刷新放在 early-return 之前，沿用同一位置）。

## 四、方案设计

### A. 最小闭环：执行/绑定后刷新方言状态行（必做）

- 面板保存重算上下文：`setSourceContext(context)`（`SqlPreviewPanel.kt:989-991`）目前只存 `sourceModuleKey`，补存完整 `previewSourceContext`（前提 2 证伪则只依赖 moduleKey）。
- 新增 `refreshDialectStatusRow()`：
  1. 池线程重算 `DialectRegistry.decision(...)` 与（若做方案 B）绑定源方言信号；
  2. EDT 与当前决策比较，**仅在新决策不弱于旧决策时替换**——等级从强到弱：MANUAL / CONFLICT 固定优先，RESOLVED 优于 AMBIGUOUS / UNAVAILABLE；
  3. 替换后只重组 meta 行的方言段与 tooltip，调用 `applyMetaTruncation()`；
  4. 幂等（文本相同即跳过）＋ `Disposer.isDisposed(this)` 守卫。
- 挂点：
  - `embedConsole` 既有 `invokeLater` 钩子内，与 `refreshBindStateRow()` 并列调用（单点覆盖三链）；
  - `rerenderForExecutionTarget` 内同步更新 `dialectDecision`（该路径已明确目标方言）；
  - 后续若控制台工具栏切换数据源也有统一信号，可复用同一入口（本计划不新增监听器，循 R30 零监听器口径）。

### B. 歧义消解：绑定源方言作为决策信号（建议同批）

- 在决策输入侧增加「绑定/上次使用数据源方言」：复用 `DataSourceUrlProbe.urlByName` + `UrlSubprotocolDialects.dialectName`，结果缓存到面板字段（TTL 与探头一致）。
- 优先级（待前提 4 核定后落地）：手动指定 > CONFLICT 阻断 > **绑定/上次使用源（证据等级接近 CAPTURED_URL）** > 模块学习 > 项目候选。
- 效果：面板一旦有具体目标（已绑定或记忆），「候选不一致」直接消解为「方言: MySQL（已绑定数据源）」；只有确实无目标时才保留未验证态。
- 安全边界：只影响**预览呈现与决策倾向**，执行前方言闸（fail-closed）语义完全不动。

### C. 文案与指引（随 A 一起）

- `panel.meta.dialectStatus.fallback` 在 AMBIGUOUS 情况下给出可操作指引与候选清单（zh/en 同步）：「方言未验证：项目候选方言不一致（MySQL/PostgreSQL…），本次按 MySQL 预览；执行一次 SQL 或手动指定方言即可确定」。
- 与设置页既有披露（`settings.dialect.inferred.ambiguous`）口径统一。
- 状态一致性约束：「已绑定」在场时不应并列显示「未验证」——这是 A 的验收判据之一。

## 五、实施步骤（文件级落点）

1. `SqlPreviewPanel.kt`：新增 `previewSourceContext` 字段；`setSourceContext` 保存完整上下文。
2. 新文件 `DialectStatusRefresh.kt`（或并入 `DialectPreviewStatusText.kt`）：纯函数 `pickStronger(old, new)`、`refreshDialectStatusRow()`、文本重组 `applyDialectStatusSegment()`。
3. `SqlPreviewPanelResultView.kt`：`updateMeta` 改为读面板可变 `currentDialectDecision`（默认取 `result.dialectDecision`），缓存 `metaWithoutDialect` 供轻量重组。
4. `SqlPreviewPanelExecution.kt`：`embedConsole` 钩子并列调用 `refreshDialectStatusRow()`；`rerenderForExecutionTarget` 更新决策。
5. `Dialect.kt` / `DialectDecisionEngine`：新增绑定源信号输入（方案 B），保持手动/CONFLICT 优先级不变。
6. 资源文件 `MpSqlVisualizerBundle*.properties`：新/改文案键 zh/en 同步。
7. 测试：新增 `DialectStatusRefreshTest`（等级比较、仅升级不降级、幂等、手动优先、绑定信号消歧），既有 `DialectDecision` 系测试补绑定源用例。

## 六、测试与验收

- 定向：`./gradlew test --tests "*DialectStatusRefresh*" --tests "*DialectDecision*"`（独立沙盒登记，`--no-configuration-cache`）。
- 反向验证（缺一不可）：摘掉刷新调用 → 「执行后升级」用例必红；摘掉「仅升级不降级」守卫 → 降级用例必红；摘掉手动优先 → 手动用例必红；恢复后全绿。
- 真机复现（用户原路径）：多数据源 demo 打开 MPJ Lambda 预览 → 确认「候选方言不一致」→ 执行 SQL → **不关预览**，方言行应在一个刷新拍内变为「方言: MySQL（已绑定/模块已学）」；绑定行与方言行一致；关开预览不回退。
- 回归面：手动方言优先（设置 PostgreSQL 时执行 MySQL 源仍 fail-closed）、XML/批量/分页预览不变、无绑定场景仍显示未验证、tooltip 候选明细不丢、F3 窄窗截断正常、单例关旧开新不残留。

## 七、风险与边界

- 不改执行安全闸与方言闸判据；方案 B 的优先级插入点是唯一需要评审的语义变更。
- 池线程重算 + EDT 落地，避免 EDT 慢操作断言；探测首次可能慢，必须异步。
- 若前提 2 证伪（无 PSI 重算得不到 `LEARNED_MODULE`），退化为「保存完整 context + 原链重放」，工作量约 +1 天。
- 若前提 3 证伪（显示名与数据源名不一致），方案 B 需先做名称归一（或改用 stableId 反查 URL）。

## 八、编号与记录

- 编号待核对台账后占号（候选 O-150 / V183；以实际台账为准，避免与并行会话冲突）。
- 实施完成后：回写本计划「实施记录」节；在验收文档 U12/U27 方言行状态处补真机证据；提交信息中文。

## 实施记录（2026-10-19，A/B/C 全部落地）

**前提核实结果**（七条全部通过或按实测调整）：
1. 学习写入：`ModuleDataSourceBindingStore` 是内存态 `PersistentStateComponent`（`remember` 即时可见，无异步落盘窗）；✔
2. 无 PSI 重算：`DialectRegistry.decision(project, moduleKey, boundDataSourceDialect)` 不传 PSI 时走非快照分支，`learnedModule` 仍可命中 → 决策可达 `LEARNED_MODULE`；✔（未保存完整 context，规避 PsiFile 生命周期问题）
3. 按名取 URL：绑定/记忆名即 `LocalDataSource.getName()` 原名（`boundConsoleDataSourceName` 反射 getName），`urlByName` 按 `getName()` 匹配，口径一致；✔
4. 优先级插入点：绑定源信号插在 scope/idea 决策之后、模块学习之前（引擎 `decide` 内）——显式 scope 的 RESOLVED/AMBIGUOUS/CONFLICT 全部先于绑定信号 return（测试钉死），手动/captured 优先不变；✔
5. EDT 约束：重算与 URL 探测全在池线程，EDT 只做替换与重组；✔
6. `updateMeta` 重入：改为「分段缓存 + 轻量重组」——`metaCoreText`/`metaTooltipCore`/`metaTooltipSuffix` 缓存方言段外内容，`applyDialectStatusSegment()` 只重组方言段后调 `applyMetaTruncation()`（预算先复位），警示/横幅/错误按钮全链不重跑；✔
7. 执行链覆盖：O-96fix2 钩子是全挂载路径单点（含同实例 early-return 前置），刷新调用与绑定行刷新同拍；✔

**改动清单**（12 文件 +132/−11）：
- 引擎：`DialectDecisionEngine` 增 `Source.BOUND_DATASOURCE` + `Input.boundDataSourceDialect`，优先级插在 idea 之后/learnedModule 之前；`IdeaSqlDialectDecision`、`DialectRegistry.decision` 参数透传。
- 面板：新增 `DialectStatusRefresh.kt`（`DialectStatusRefreshPolicy.pickStronger` 纯函数 + `boundDataSourceDialectForDecision` 信号 + `refreshDialectStatusRow` 池线程重算→EDT 仅升级→轻量重组 + `applyDialectStatusSegment`）；`SqlPreviewPanel` 增 4 个分段缓存字段；`clear()` 同源复位；`updateMeta` 拆分段缓存；`embedConsole` 钩子并列调用；`rerenderForExecutionTarget` 同步升级决策（EXACT/BOUND_DATASOURCE，先于 renderSql）。
- 文案（方案 C）：`panel.meta.dialectStatus.ambiguous` zh/en 改可操作指引（「执行一次 SQL 或手动指定方言即可确定」）；新增 `panel.meta.dialectStatus.boundSource`（「已绑定数据源」/ "bound data source"）。
- 测试：`DialectStatusRefreshPolicyTest` 6 例、`DialectStatusRefreshWiringGuardTest` 4 例（真实接线守卫：钩子同拍/重渲染先升级/updateMeta 消费面板决策/clear 复位）、`DialectDecisionEngineTest` 增 6 例（绑定信号胜出/手动优先/captured 优先/scope 歧义不被绕过/scope 决策胜出/未知名忽略）。

**读数**：
- 定向 4 类 **30/0/0**；方言族回归 7 类 **154/0/0**（独立沙盒 MPSV-DialectRefresh，`--no-configuration-cache`）。
- 首轮 2 红＝判据修正（scope 两候选分歧实为 AMBIGUOUS 非 CONFLICT，顺势钉死「绑定信号不绕过 scope 分歧」）+ 策略顺序修复（`old==null` 短路原在 fresh 终态守卫之前，CONFLICT 可乘虚而入——守卫前移）。
- 反向验证两轮：①摘「仅升级」守卫（`>` 改 `>=`）→ `weaker or equal decision never replaces` **恰 1 红**；②摘 embedConsole 刷新调用 → 挂点守卫 `embedConsole hook calls refreshDialectStatusRow` **恰 1 红**；恢复后 30/0/0，`REVERSE-PROBE` 残留 0。

**真机复验待做（用户侧）**：多数据源 demo 打开 MPJ Lambda 预览（复现「候选方言不一致」）→ 执行 SQL → 不关预览，方言行应在一个刷新拍内变为「方言: MySQL（已绑定数据源）」或「（模块已学数据源）」；绑定行与方言行不再自相矛盾。
