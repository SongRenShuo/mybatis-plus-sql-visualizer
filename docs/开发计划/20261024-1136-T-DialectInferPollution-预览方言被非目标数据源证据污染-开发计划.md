# T-DialectInferPollution：预览方言被非目标数据源证据污染致执行计划两段失败（开发计划）

**创建时间**：2026-10-24 11:36
**触发源**：用户日常 IDEA 2026.2（正式版插件，含 T-ExplainPwdPrompt 全部修复）实测 t_order
LAMBDA_QUERY 预览点「执行计划」：先弹「未绑定数据源」红字框，之前还出现过方言重渲染红条。
**基线**：`master@9b1d6136`
**状态**：已实施（W7 自动重提交，见 §五；渲染残留根因分析入档 §三，后续项见 §六）
**编号**：不占 O/R/D/V（O-145 方言推断链的真机缺陷修复）

---

## 一、现象与日志证据（用户日常 IDE 2026.2，idea.log 2026-10-24 11:22~11:24）

```
11:22:58  [M37.2] explainSql: dialect=PostgreSQL, len=203   ← t_order 预览被判 PG
          [M36] showSql branch=editor + dialect pre-mapped to MySQL（闸门重渲染红条）
11:23:13  [M37.2] explainSql: dialect=MySQL, len=180        ← 重渲染后的 MySQL 版
11:23:31  [M37.2] explainSql: dialect=PostgreSQL, len=203   ← 另一条又判 PG
11:23:36  [M37.2] explainSql: dialect=MySQL, len=180
```

用户感知：点「执行计划」不直接出计划，先吃方言重渲染红条，再点一次才到下一环；开关关闭形态
下直送链再弹「未绑定数据源」红字框（W5 前形态）。同一工程、同一张表，方言在 PG/MySQL 间摆动。

## 二、根因收敛（证据源逐一排除）

`DialectDecisionEngine.decide` 能产出 PostgreSQL 的输入逐一核对（引擎源码 + 用户环境事实）：

| 证据源 | 用户环境值 | 能否产出 PG |
| --- | --- | --- |
| capturedDialect | 未传（null） | 否 |
| scopeCandidates（PROVIDED） | mp_demo → MySQL | 否 |
| ideaDialect（预览文件平台有效方言） | demo 工程 sqldialects.xml：preview 目录=MySQL | 否（demo 工程） |
| learnedModule | 模块学 mp_demo → MySQL | 否 |
| projectCandidates | 7 源（5×MySQL + dbx_test PG + mpj_dm DM）→ distinct>1 → **AMBIGUOUS** | 否（AMBIGUOUS 非 PG） |
| lastAgentDialect | **dbx_test@114.66.55.245 = PostgreSQL**（竞品 DBX 调研测试残留） | **单模块才触发**（demo 多模块，引擎不取） |
| **boundDataSourceDialect** | **面板 last-used/绑定记忆**——用户竞品测试期间 last-used=**dbx_test(PG)** | **是（BOUND_DATASOURCE STRONG，T-DialectStatusLiveRefresh 方案 B 设计使然）** |

**根因**：渲染时刻的面板 last-used/绑定=dbx_test(PG)（用户做竞品 DBX 对比测试所致），
t_order 预览按 BOUND_DATASOURCE 强证据渲染成 PG；之后用户切回 mp_demo 开发，指示行显示
mp_demo 但预览文本仍是 PG 旧渲染——点「执行计划」被闸门拦下重渲染（红条），重渲染成功后
**不自动继续**，要用户再点一次；第二次点击若结构化开关仍关，直送链再弹「未绑定数据源」。
三段体验叠加 = 「正常绑定了数据源却没有正常显示执行计划」。

**闸门重渲染本身行为正确**（红条如实说明+已按 MySQL 重渲染）；缺陷是**重渲染后断链**。

## 三、渲染残留的后续项（本轮不做，登记）

「last-used 数据源变化时，若预览渲染方言与新数据源方言不一致，自动重渲染预览（不等执行计划
闸门拦）」——涉及 last-used 写入点（rememberRunner）触发面板重渲染的生命周期编排，改动面大，
列为 T-DialectStatusLiveRefresh 的后续增强（P2）。

## 四、修法（W7，本轮实施）

**EXPLAIN 链闸门 AUTO_RERENDER_REQUIRED 重渲染成功后自动重提交一次**：

- `submitExplainOrStructured` 增 `rerendered: Boolean = false` 参数；`allowDialectGate` 的
  `onDialectGate` 回调改 EXPLAIN 专用 lambda——`AUTO_RERENDER_REQUIRED` 且 `!rerendered` 且
  `rerenderForExecutionTarget(target)` 成功 → 置位并重提交一次（`allowDataSourcePrompt=false`
  防选择器循环）；重渲染失败或已重渲染过 → 回落既有 `onExecutionDialectGate` 红条提示。
- 防循环：单次点击链内最多自动重提交一次（`rerendered` 随递归传入）；QUERY/DML 的
  `executeSql` 通用闸门行为不变（只 EXPLAIN 链改）。

## 五、实施记录（2026-10-24，W7）

改动：`ui/SqlPreviewPanelExecution.kt`——`submitExplainOrStructured` 增 `rerendered` 参数与
EXPLAIN 专用闸门回调（重渲染成功自动重提交一次）；`onExecutionDialectGate` 通用行为不变。
守卫：`ExplainAskPasswordChannelGuardTest` 加「EXPLAIN 重渲染后自动重提交」例（源码形）。
读数与反向验证见提交信息。

---

## 六、实施记录续（2026-10-24，W8~W12，用户沙盒五轮实测驱动）

用户逐轮实测暴露五个同族形态，逐条修（每轮均定向测试 + 反向探针）：

| 轮 | 提交 | 形态（用户实测） | 修法 |
|---|---|---|---|
| W8 | `ab97d69e` | 开关关闭形态直送链取源只认控制台绑定 → 弹「未绑定数据源」红字（面板明明有 last-used） | 直送链取源接 last-used 回落 |
| W9 | `427484a8` | jar-model demo 模块（无学习条目）表被 last-used 硬套 → `mp_demo.wms_inventory_jar_demo doesn't exist` | 取源统一 `explainFallbackDataSource`：模块学习优先；学习与 last-used 冲突不猜（引导选择） |
| W10 | `e813042a` | join-demo（学习=last-used 一致但都陈旧）→ `mp_demo.order_entity doesn't exist` | **表归属路由**：`DatabaseSchemaResolver.cachedOwnerIndexes` 证明哪条源持有预览的表；候选不持有而唯一其他源持有 → 自动路由；多源 → 冲突引导选择 |
| W11 | `f2050329` | 点「执行 SQL」永久「执行中…」 | `PrepareSingleFlightGate.expirePendingExecute`：排队意图自有 15s 结算（门与 owner 不动）；闸门三分支补 INFO 打点（此前零打点=排障盲区） |
| W12 | `f2050329` + `540125bb` | W10 零命中仍错源执行 | 缓存热度判定 `cacheHasAnyTable`：缓存热且无源持有表 → 引导选择（确证不在）；缓存全冷才保持候选（诚实降级） |

**读数**：定向 5 类 **38/0/0/0**（Guard 11 + PrepareGatePendingTimeout 3 + Classifier 8 +
Fallback 6 + PreviewSideQuery 10，XML 判例数已核）；反向逐条：W8 摘回落恰 1 红、W9 摘冲突
分支恰 1 红、W10 真删 `cachedOwnerIndexes` 恰 1 红、W11 删 `expirePendingExecute` → 生产编译
失败（调用点直接红，强于测试红）。探针残留 0。

**教训（入档）**：
1. **提交必须核对 `git status` 残留**：W11/W12 首笔 `f2050329` 漏 add `DatabaseSchemaResolver.kt`
   （W12 定义所在），提交态编译不过，靠 `540125bb` 补提交——「每完成可提交的就立即提交」须
   配「提交后 `git status` 复核 + 提交态编译」。
2. **源码形守卫的文件归属先 grep 再写**：本轮三次守卫指错文件（Timer 在 Results 而非 Execution、
   `cachedOwnerIndexes` 在 Selector 而非 Execution），全是「凭记忆写路径」所致。
3. **同一族缺陷会逐轮变形**：last-used 错源 → 学习错源 → 双证据一致但陈旧 → 缓存冷/热分野，
   每轮修完都有下一个形态。根治靠「更强的事实源」（表归属）而非继续给弱证据打补丁；这也是
   P2「渲染方言残留自动重渲染」仍未做的原因——那条要动生命周期编排，须单独立项。
