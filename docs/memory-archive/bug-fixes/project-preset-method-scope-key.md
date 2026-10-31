---
name: project-preset-method-scope-key
description: 参数预设「按方法隔离」+「绑定方法、改 SQL 不丢」——20261021 两阶段已实施并合入 master
  （隔离 750b35cb / 绑定 9d0b2fa1 / merge 5b49dfd2，未 push）；根因是缓存键缺方法维度，修法是键尾追加
  origin scope + 最近保存键指针
metadata:
  node_type: memory
  type: project
  originSessionId: session_bbf4b22f5e570999154fc4540944484e
---

20261021 用户沙盒报障（demo 仓 `jpa-jar-model-demo` / `WmsInventoryJarQueryService`，8088）：在方法 A（拼接 HQL）另存预设 `1`，打开方法 B（StringBuffer 逐句 append 的同一段 HQL），下拉里同样出现 `1` 且激活态也跟过去。用户随后追加要求「预设永久绑定方法、改 SQL 也不丢」，采纳推荐路线 B，两阶段均已实施并合入 master。

## 根因（已证伪为实证，不是推断）

预设名单、激活指针、参数快照三者**共用一个缓存键**，而键只有 `表名 + SQL 指纹` 两维：`ParamHistoryCache.computeKey` 返回 `"$tableName:${fingerprint(rawSql)}"`，无表名走 `sql:` + 规范化指纹。HQL 链路 `previewHql` 走 `buildJpaPreviewResult`，该构造点既不设 `historyFingerprintSeed` 也**不调 `recordHistory`**（HQL 预览因此不落历史条目，属既有行为，本轮未改）。两个方法写同一段 HQL ⇒ 渲染 SQL 逐字相同 ⇒ 键相同 ⇒ 名额/激活态/值一起共享。**不是哈希碰撞，是键里没有「出处」这一维。**

20261006 的 `T-DevAcceptFix2 ①` 只做到「按 SQL」，其验收用例用**两段不同 SQL** 当两个方法，键天然不同 ⇒ 绿——「同 SQL 跨方法」这个生产唯一形态无人覆盖（[[pitfall-fake-green-test-assertions]] 的「分支未交叉」族）。本轮特意用 demo 同款语料写 `SqlPreviewServiceOriginScopeTest` 把「两方法渲染 SQL 逐字相同」变成断言，前提不再靠看图推断。

## 已实施形态（master `5b49dfd2`，三分支提交，**未 push**）

分支 `t-preset-method-scope`（工作树 `mpsv-preset-scope`，已清理）：

| 提交 | 内容 |
|---|---|
| `750b35cb` | T-PresetMethodScope 隔离 |
| `9d0b2fa1` | T-PresetMethodBinding 绑定 |
| `5b49dfd2` | merge（master 侧 10 笔与本次无文件交集，合并零冲突） |

### 键与作用域
- 键 = `<旧键>|@<scope>`；`scope` = `PreviewOriginScope.of(method, project)` = SHA-256(项目身份 + `|` + 项目内相对路径 + `|` + `方法签名`) 前 16 hex。方法签名 = 名 + 参数 `canonicalText` 逗号拼接（区分重载）；相对路径 `VfsUtilCore.getRelativePath(file, project.baseDir, '/')`，取不到退 `file.name`。存哈希不存明文，本机路径不进 `mp-sql-visualizer.xml`。
- `computeKey(tableName, rawSql, scope: String? = null)`；scope 空 → 旧格式逐字节不变。分隔符 `ParamHistoryCache.SCOPE_SEPARATOR = "|@"`，`legacyKeyOf(key)` = `substringBefore`；scope 内的分隔符被替换成 `|_` 保证切分唯一。
- 注入**只两处**：`buildJpaPreviewResult`（已有 `method` 形参，覆盖 HQL/JPQL/原生/派生/NamedQuery/HBM）与 `recordHistory(result, anchor)`（从 anchor 上溯 `PsiMethod`，覆盖 Wrapper/批量/CRUD）。`PreviewResult.originScopeKey` / `SqlHistoryEntry.originScopeKey` 都是 `var`（前者供 recordHistory 回写，同 `historyEntryId` 先例）。
- 面板口径：`historyCacheKey()`、`entrySnapshotKey(entry)` 都带 scope。R51 历史快照链新增 `matchesSnapshotKey(entry, key)`——带 scope 键也要认「同一条 SQL 的旧条目」，否则升级前落的历史条目永远不再更新也回落不到。

### 存量兼容（两层）
1. **读回落**：`recall` / `recallFromScheme` / `containsKey` / `schemeNamesFor` / `activeSchemeFor` 在带 scope 键未命中时按 `legacyKeyOf` 再查一次。
2. **惰性复制**：`schemeNamesFor` 命中回落分支时把各方案在旧键下的快照复制到新键（`migrateLegacyFor`，每方法至多一次）并随迁激活指针、记最近键指针。写路径只写新键。
- 已知边界（已在计划里登记，不是漏改）：升级前的老预设第一次在某方法打开时仍会「共享那一下」，之后各自独立。

### 绑定（改 SQL 不丢，路线 B）
- 指针表 `ParamHistoryCache.recentKeyBySchemeScope`（`方案 → scope → 完整键`），持久化到新字段 `PluginSettings.State.paramSchemeRecentKey`，形如 `{"方案名":{"<scope>":"<键>"}}` 嵌套 JSON（避免方案名与 scope 拼串；复用 `splitTopLevelEntries`/`escapeJson`）。测试回落 static `fallbackStorage` 的 `RECENT_KEY_STORAGE_KEY`。
- 三条写路径刷新指针：`saveForScheme`、`saveAsScheme`、`migrateLegacyFor`（`recordRecentKeyLocked`，须持 bucketLock）。
- `recallRecent(key)` 只做指针回落，与 `recall`（精确 + 旧键）分工；无 scope 键直接返 null（没有「上次」概念）；指针键 == 精确键/旧键时也返 null（已由 recall 覆盖）。

## 实施中才发现的衔接缺口（计划里没写，最容易漏）

改 SQL 后新键在方案桶里**没有条目**，而 `activeSchemeFor` 原本按「桶里有该键条目」判定 ⇒ 直接回落默认方案 ⇒ 回落链在激活判定这一步就断了，`recallRecent` 永远走不到。修法：**最近键指针命中也算该方案在该键下有效**。反向探针 B4 证明这条兜底不可省（摘掉后 5 红）。

配套两处面板修正：
- `recallFromHistory` 改三段回落：精确缓存 → `recallRecent`（同方法最近键）→ `historySnapshotFallback`。
- 回落白名单用新函数 `currentParamSources()`（从 `current` 的结果模型收集 unresolvedParams / conditionViews.valueSources / batchModel / xmlModel.forEachViews 的 collectionExpr），**不能用 `paramValues.keys`**——方案切换路径先 `clearParamStateForSchemeSwitch` 清场，到召回时已空，白名单会把全部参数挡掉。
- `syncSchemeCombo` 把「已激活但名单里没有」的方案补进下拉（改 SQL 后该方案在新键下无快照，按键过滤会漏掉它），否则出现「列表只剩默认、却显示选中了某预设」。

## 跨 SQL 回落只恢复参数值 + FieldFill，结构态一律不参与

Wrapper 条件 id 是 `"c${toggleSeq++}"`（`converter/QueryWrapperParser.kt:736`，**解析序自增**）⇒ 改 SQL 增删条件后 id 全体移位，回落 `__chk__:c2` 不是失效而是**张冠李戴**（比丢更糟）；XML 条件 id 是 `test` 表达式原文，改表达式即失效但不错位。故 `__chk__` / `__fnull__` / `__fev__` 三类结构态在 `recovered != null` 时全部跳过。

## 读数与反向验证

- 定向八类 **98/0/0/0**：`ParamHistoryCacheBindingTest` 7（新）、`ParamHistoryCacheScopeTest` 11（新）、`ParamHistoryCacheTest` 28、`SqlPreviewPanelParamSchemesTest` 29（含同 SQL 跨方法 + 改 SQL 回调两例）、`ParamSchemeManagerDialogTest` 11、`SqlPreviewPanelHistorySnapshotFallbackTest` 7、`PreviewOriginScopeTest` 4（新）、`SqlPreviewServiceOriginScopeTest` 1（新）。合并到 master 后在 master 树复跑同读数。
- 反向各按预期变红并恢复：R1 面板键不带 scope → 1 红；R2 摘 `buildJpaPreviewResult` 注入 → 1 红；R3 摘 `recall` 旧键回落 → 1 红；R4 摘惰性复制 → 1 红；B1 摘指针写入 → 5 红；B2 摘回落白名单 → 1 红；B3 摘结构态排除 → 1 红；B4 摘激活兜底 → 5 红。
- **源码守卫也要跟着升级**：`SqlPreviewPanelHistorySnapshotFallbackTest.testR51_生产接线在位` 把回落链文本钉死成两级，加一级后必红。改法是同步文本文案 **并新增一条顺序断言**（`recallRecent` 的 index 必须小于 `historySnapshotFallback`）——放开文本不等于放松顺序约束。

## 其他仍在案的经验

- XML 预览链与日志/历史双击预览（`LogSqlEntry.toPreviewResult`）无方法上下文，scope 留空、保持旧键，行为不变。
- 加默认参数的坑：`SqlHistoryEntry` / `PreviewResult` 新增默认参数后旧测试类会成片 `NoSuchMethodError`，首跑 `compileTestKotlin --rerun-tasks`（[[pitfall-regression-false-failure-flakes]] 第 0 条）。
- light fixture 里 `PsiType.canonicalText` 不一定等于 `java.lang.String`（未挂 JDK 时是简单名）——断言参数类型时从方法自身取，别硬编码。
- 分支已合并删除；工作树删除走 [[pitfall-worktree-remove-longpath]]（本次 `git worktree remove` 报 Filename too long，robocopy 镜像清空后再删成功）。

相关：[[feedback-param-preset-per-method]]、[[project-pending-plans]]、[[pitfall-fake-green-test-assertions]]、[[feedback-dev-merge-needs-approval]]。
