---
name: project-preview-stmt-type-scope-20261112
description: T-PreviewStmtTypeScope 已实施（分支
  t-preview-stmt-type-scope，1b3af3ec5）——判型依据可见化 + D-2 作用域收窄 + D-3 多目标切换 + 接收者负门
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f21a23d-9183-41ed-8ded-ad7572837917
---

用户 20261112 报障：查询链预览出 `UPDATE/DELETE`、`selectOne(wrapper)` 处出 `SELECT`。**用户回传代码已确认 H1**：
同一个 `cuPartyOrgMemberEntityLambdaQueryWrapper` 在 216-217 行交给 `update(entity, w)`、232 行重新赋值后
235 行交给 `delete(w)`。旧口径「该变量任意一处被消费即改判」+「update 档位恒先命中」⇒ 查询侧那条链也报 UPDATE。

判型机制（源码）：四个消费标志决定语句类型，分派顺序 count > exists > updateEntity > delete > 默认 SELECT
（`ui/SqlPreviewService.kt`）；标志只看**方法名**（`psi/WrapperLocator.kt` 的 `consumptionKindOf`）。

已实施（分支 `t-preview-stmt-type-scope`，起点 master `822519919`，实施提交 `1b3af3ec5` + 哈希回填 `f69415720`；
**未合 master、未推送**）：
- M1 依据可见化：`WrapperConsumption`/`ConsumptionKind` + `PreviewResult.decidingConsumption/otherConsumptions`；
  面板 meta 行「类型依据: remove() 消费（第 N 行）」+ 悬停列全部候选。
- M2-D-2：`WrapperLocator.decideConsumption` 按锚点位置单选（锚点语句 > 之后最近 > 默认 SELECT）；
  证据按源码偏移排序（`ReferencesSearch` 顺序不保证源码序，实测出现过反序）。
- M2-D-3（用户定「往长远做」，与 D-2 并用非替代）：`PreviewResult.statementTypeOptions/statementTypeSwitch`；
  `preview(anchor, ctx, preferred)` 强制档位 + **强制档位进 WrapperCache 签名**；面板工具栏「语句: X」入口。
- M3 接收者负门 `hasNonDbReceiver`（JDK 容器/非 DB 类型，父链深度 4）。**偏离计划**：计划想复用锚点侧
  `hasMpCrudReceiver` 正向白名单，实测会否掉未接依赖的自有 Service 桩/Component 中间层 ⇒ 改负门。
- M4 `docs/架构设计/02-核心流程详解.md` §2.3.1 判型表 + 四条口径。

读数：定向 98/0/0（PreviewStmtTypeScopeTest 12 / ConsumerMethodsCoverageTest 33 / WrapperLocatorTest 31 /
SqlPreviewServiceRoutingTest 6 / SqlPreviewTopRowLayoutTest 9 / SqlPreviewPanelMetaHintTooltipTest 7）；
反向摘 M3 负门 ⇒ 12 例恰 1 红（JDK 容器用例），还原后 12/0/0。
not run：全量门禁、真机 GUI 验收、verifyGate/buildPlugin。
下一步：用户真机确认「语句: UPDATE」按钮与切换效果，再定是否合 master。

**真机复现语料已进 demo 仓**（`mybatis-plus-sql-visualizer-demo` `d936412`，20261113）：
`mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/repro/WrapperReuseRepro.java`——
`OrderComponent`（qryOneByWrapper/update/delete 与用户工程同名，消费识别按方法名匹配，名字换了复现不出来）
+ `queryByWrapper`（未知名→SELECT）+ `updateThenDeleteByReusedWrapper`（216-217/232-235 行形态）。
普通类不连库不注册 Controller（CustmanagerRelQueryRepro 惯例）；`mvnd -o -pl mybatis-plus-demo compile`
通过（mvnd 在 `D:/HuanJing/Maven/maven-mvnd-1.0.3/bin/mvnd.cmd`，Git Bash 无 mvn）。

**真机验回复抓第二处缺口（D-B1/D-B2，`3731e2eed`，2026-11-13）**：语料第 100 行（`w = new LambdaQueryWrapper<>();`
的**重新赋值**）点预览图标 → 无 WHERE 的 SELECT。根因＝赋值 RHS 的 `new` 不在任何 `PsiVariable` 祖先内，
`findWrapperVariable` 认不出 → 落到 `buildChainFromNew`（只收赋值表达式内部的链）→ 后续 `w.eq(...)`
与 `delete(w)` 全丢。修法：①`findWrapperVariable` 新增 `PsiAssignmentExpression` 分支按目标变量走变量路径；
②重新赋值即分段（`filterCallsToAnchorSegment`，锚点在哪段只收那段的条件，消费证据不过滤）。
两探针各恰红（摘 D-B1→「应判 DELETE: null」；摘 D-B2→DELETE 里混进 update 段 status）。
定向六类 **100/0/0**。
**第三处 D-C（`90e494b33`，2026-11-13）**：`update(entity, wrapper)` 的 SET 各列占位符共用 source `"?"` ⇒
面板按来源去重只剩一行 `#?`，填一个值把 `order_no/user_id/amount/status/create_time` 全填成 1。
修法＝`WrapperConsumption.detail` 带实体实参文本（`update(patch, w)` 的 `patch`），SET 逐列来源
`patch.<列名>`（兜底「实体.<列名>」）；反向探针 C 回退共用 `?` → 15 例恰 1 红，message 里来源为
`[?, ?, ?, ?, ?, userId]`。
**`t-merge-preview-trio` 已并两轮**：`a7cc4801b`（D-B1/B2）→ `1dde82c78`（D-C）；合并态
**31 类 191 例 0 红**（含全部 28 个 convention 守卫）。仍**未推送**，`update/update.json` 等用户项不动。
