# T-PresetMethodScope 参数预设按声明方法隔离 — 开发计划

- 日期：2026-10-21 09:18
- 任务编号：T-PresetMethodScope
- 来源：用户 2026-10-21 沙盒实测报障（demo 仓 `jpa-jar-model-demo` 的 `WmsInventoryJarQueryService`）——「在第一个方法新增预设，第二个方法也看到第一个方法的预设了」。
- 性质：缺陷（隔离语义不成立）；改动集中在缓存键口径，不动预设的存储结构与 UI 形态。
- 实施分支：dev 工作树新建 `mpsv-preset-method-scope` / 分支 `t-preset-method-scope`（自 dev tip）。

---

## 一、现象与复现

demo 类 `WmsInventoryJarQueryService` 有两个方法：

| 方法 | HQL 承载形态 |
|---|---|
| `findAvailableJarInventory` | 字符串拼接（`String hql = "…" + "…"`） |
| `findAvailableJarInventoryByBuffer` | `StringBuffer` 逐句 `append` + `sb.toString()` |

两段 HQL 的语义文本一致（后者每段尾多一个空格），渲染出的 SQL 也一致：

```sql
SELECT inv.id, inv.warehouse_id, inv.qty_base_qty, inv.sku_inventory_status, inv.item_key_lot_info_received_date
FROM wms_inventory_jar_demo inv
WHERE inv.warehouse_id = '1'
  AND inv.sku_inventory_status = '1'
  AND inv.qty_base_qty > 0
  AND inv.item_key_lot_info_received_date IS NOT NULL
ORDER BY inv.id
```

在方法 A 的预览面板里「另存为」预设 `1`，打开方法 B 的预览，下拉里同样出现 `1`，且激活态也跟过去——用户期望的是「每个方法一套预设」。

## 二、根因

### 1. 缓存键只有「表名 + SQL 指纹」，没有方法维度

`ParamHistoryCache.computeKey`（`ui/ParamHistoryCache.kt:117-121`）：

```kotlin
fun computeKey(tableName: String?, rawSql: String): String? {
    if (!tableName.isNullOrEmpty()) return "$tableName:${fingerprint(rawSql)}"
    val normalized = cn.song.mybatisplus.visualizer.log.normalizeSqlWhitespace(rawSql)
    return "sql:${cn.song.mybatisplus.visualizer.log.sqlFingerprint(normalized)}"
}
```

面板侧 `historyCacheKey()`（`ui/SqlPreviewPanelParamPanel.kt:1544-1551`）把 `result.historyFingerprintSeed ?: result.sql` 交给它算键。预设的名单、激活指针、快照全部按这个键分桶：

- `schemeNamesFor(key)`（`ParamHistoryCache.kt:253-259`）
- `activeSchemeFor(key)`（`:266-271`）
- `saveForScheme` / `saveAsScheme`（`:149-169`、`:322-339`）

键相同，三个维度就一起共享。

### 2. HQL 链路的键恰好只由渲染结果决定

`previewHql`（`ui/SqlPreviewService.kt:902-984`）走 `buildJpaPreviewResult`（`ui/SqlPreviewServiceEnhancements.kt:239-292`），该构造点**不设置** `historyFingerprintSeed`，也不调用 `recordHistory`，因此键退化为 `tableName + fingerprint(result.sql)`。

两个方法写的是同一段 HQL，`HqlConverter` 解析成同一个语句模型，渲染结果逐字相同 ⇒ 表名相同、指纹相同 ⇒ 键相同。这不是哈希碰撞，是键里本来就没有「出处」。

### 3. 20261006 的隔离修复只做到「按 SQL 隔离」

`T-DevAcceptFix2 ①` 的注释写的是「预设按方法/SQL 隔离」，实现上是「激活指针按键记录」——键仍然是 SQL 指纹。当时的验收用例 `SqlPreviewPanelParamSchemesTest.testDevAcceptFix2_预设按方法隔离_同名跨方法各自存值`（`:646-690`）用两段**不同 SQL**（`t_order` / `t_customer`）模拟两个方法，键天然不同，所以绿灯。

这是本轮缺陷能存活到沙盒的原因：用例把「方法」等同于「不同 SQL」，同 SQL 跨方法这一形态没有覆盖（与仓内「空绿」教训同族）。

## 三、方案

### 1. 键上追加「声明作用域」（origin scope）

新键格式：

```
<legacyKey>|@<originScope>
```

- `legacyKey` = 现有 `tableName:fingerprint` 或 `sql:fingerprint`，逐字节不变。
- `originScope` = `fingerprint(projectIdentity + "|" + 项目内相对路径 + "|" + 方法签名)` 的前 16 hex。
  - `projectIdentity` 复用 `ExplainPlanKey.projectIdentity(project.basePath)`（`history/ExplainPlanSnapshot.kt:73-76`，SHA-256，不落本机路径）。
  - 相对路径用 `VfsUtilCore.getRelativePath(vFile, project.baseDir, '/')`（243 源码实证：`com/intellij/openapi/vfs/VfsUtilCore.java:132`）；取不到（文件在项目外）退化为 `file.name`。
  - 方法签名 = `method.name + "(" + parameters.joinToString(",") { it.type.canonicalText } + ")"`，重载可区分。
  - 键里存哈希而不是明文，避免把本机路径与类名写进 `mp-sql-visualizer.xml`；可读标签另存内存字段供日志排障。
- 分隔符选 `|@`：`legacyKey` 的指纹段是 hex、表名不含 `|@`；回落时 `key.substringBefore("|@")` 即可取回旧键。
- `computeKey(tableName, rawSql, scope: String? = null)`：`scope` 为空时返回旧格式，既有调用点与既有测试零改动。

### 2. scope 的注入点（两处收口，不逐构造点铺开）

| 位置 | 覆盖链路 | 做法 |
|---|---|---|
| `buildJpaPreviewResult(..., method: PsiMethod?)` | HQL / JPQL / 原生 SQL / 派生查询 / NamedQuery / HBM | 已有 `method` 参数，内部算 scope 写入结果 |
| `recordHistory(result, anchor)`（`SqlPreviewService.kt:1852-1882`） | Wrapper / 批量 / JPA CRUD / `@Procedure` / Repository 声明 | 从 `anchor` 上溯 `PsiMethod` 算 scope 写入结果与历史条目 |

`PreviewResult` 新增 `var originScopeKey: String = ""`（放构造参数尾部、默认空；`var` 是为了让 `recordHistory` 能回写，先例是同一文件里的 `historyEntryId`）。`SqlHistoryEntry` 新增同名字段，XmlSerializer 旧文件缺字段自动取默认值。

`SqlPreviewPanelExecution.kt:1242` 补建历史条目时透传 `originalPreview.originScopeKey`。

### 3. 面板与历史快照链改口径

- `historyCacheKey()`（`SqlPreviewPanelParamPanel.kt:1544`）追加 `result.originScopeKey`。
- `entrySnapshotKey(entry)`（`SqlPreviewPanel.kt:478-479`）追加 `entry.originScopeKey`，否则 R51 的历史快照回落会与新键对不上。
- 其余调用点（`syncSchemeCombo`、`switchToScheme`、`createSchemeManagerDialog`、`flushHistoryToCache`）都经 `historyCacheKey()`，不需要单独改。

### 4. 存量数据：读时回落 + 惰性迁移

升级后用户已有的预设都挂在旧键（无 scope）上，直接切新键会让它们「消失」。处理分两层：

- 读回落：`recall` / `recallFromScheme` / `containsKey` / `schemeNamesFor` / `activeSchemeFor` 在带 scope 键未命中时，按 `legacyKeyOf(key)` 再查一次。
- 惰性迁移（推荐做，避免旧预设在两个方法里继续「共享」的观感）：`schemeNamesFor(key)` 命中回落分支时，把该方案在旧键下的快照复制到新键，之后两个方法各持一份、各自独立编辑。复制只发生一次，仍受每桶 50 条上限约束。

写路径（`saveForScheme` / `saveAsScheme`）只写新键，不回写旧键，避免把隔离后的值重新摊平。

### 5. 不做什么

- 不把键改成「方法 + 无 SQL」：同一方法里改 SQL 后参数集可能不再适用，保留 SQL 维度是既有语义。
- 不改预设的存储结构、重命名/删除的「名字级」语义（D71 定案）。
- 不改 XML 预览链（`previewXmlStatement` 无宿主 Java 方法，scope 留空、行为不变），留作后续增量。
- 不动日志/历史双击预览（`LogSqlEntry.toPreviewResult` 无方法上下文，scope 为空，维持旧键回落）。

## 四、改动面

| 文件 | 改动 |
|---|---|
| `ui/ParamHistoryCache.kt` | `computeKey` 加 `scope` 参数；`legacyKeyOf`；五个读 API 加回落；`schemeNamesFor` 的惰性迁移 |
| `model/PreviewResult.kt` | 新增 `var originScopeKey: String = ""` |
| `history/SqlHistoryEntry.kt` | 新增 `var originScopeKey: String = ""` |
| `ui/SqlPreviewService.kt` | `recordHistory` 写入 scope；`previewHql` 等经 `buildJpaPreviewResult` 的链路自动获得 |
| `ui/SqlPreviewServiceEnhancements.kt` | `buildJpaPreviewResult` 按 `method` 写 scope |
| `ui/SqlPreviewPanel.kt` | `entrySnapshotKey` 追加 scope |
| `ui/SqlPreviewPanelParamPanel.kt` | `historyCacheKey` 追加 scope |
| `ui/SqlPreviewPanelExecution.kt` | 补建历史条目透传 scope |
| 新增 `psi/PreviewOriginScope.kt` | `originScopeOf(method, project)` 纯函数 + `label`（可读形态，仅内存/日志） |
| 新增测试 | `ParamHistoryCacheScopeTest`、`SqlPreviewServiceOriginScopeTest`；`SqlPreviewPanelParamSchemesTest` 补同 SQL 跨方法用例 |

## 五、验收

### 自动化

- 数据层：`computeKey` 无 scope 时逐字节等于旧格式；两个 scope 下同名预设各存各值；激活指针互不串；旧键回落可召回；惰性迁移后新键有条目。
- 面板层：构造两个 `PreviewResult`（**同一段 SQL 文本**、不同 `originScopeKey`）走 `showResult`，断言 A 的预设不出现在 B 的下拉、切回 A 恢复自己的名单与激活态。这是本轮的核心用例，必须刻意用「相同 SQL」作语料。
- 服务层：light fixture 里同文件两个方法各写一段相同 HQL，断言 `originScopeKey` 不同；`LogSqlEntry.toPreviewResult` 产物 scope 为空。
- 回归：`ParamSchemeManagerDialogTest`、`SqlPreviewPanelHistorySnapshotFallbackTest`、`SqlPreviewPanelImportTest`、`PluginSettingsPanelUiTest`、`ParamHistoryCacheTest`。

### 反向验证（各摘一处，必须变红）

| 探针 | 摘除点 | 预期 |
|---|---|---|
| R1 | `historyCacheKey` 不传 scope | 面板同 SQL 跨方法用例红 |
| R2 | `buildJpaPreviewResult` 不写 scope | 服务层 scope 用例红 |
| R3 | 删掉 `legacyKeyOf` 回落 | 迁移用例红 |
| R4 | 删掉惰性迁移 | 迁移后隔离用例红 |

### 真机/沙盒

在 `jpa-jar-model-demo`（8088）上：方法 A 另存预设 `1` → 方法 B 下拉只剩「默认预设」→ 方法 B 另存同名 `1` → 两个方法各自切换，参数互不串。

## 六、待验证前提（实施前先证伪）

1. demo 两个方法渲染出的 `result.sql` 逐字节相同。截图只证明「键相同」，需用最小渲染（或现有 light fixture 走 `previewHql`）打印两段 SQL 比对；若仅空白不同，也要确认 `buildJpaPreviewResult` 的 `compactSql` 归一后一致。
2. `PsiMethod.parameterList.parameters[i].type.canonicalText` 在当前编译目标可用（标准 PSI API）。
3. `VfsUtilCore.getRelativePath(VirtualFile, VirtualFile, char)` 在 243 存在——已在本机 `ideaIC-243.28141.41-sources.jar` 的 `VfsUtilCore.java:132` 实证，仍建议实施时用 javap 复核签名。
4. `project.baseDir` 可能为 null（非目录型项目），需退化到 `file.name`。
5. HQL 路径的 `result.sql` 是否受 `formatSql` 设置影响。若受影响，同一方法切换格式化开关会换键、参数丢失（既有风险）；本轮可顺带把 HQL 的 `historyFingerprintSeed` 设为 `compactSql`，但需确认 `HqlConverter` 输出与格式化开关无关，否则先单独立项。
6. `SqlHistoryEntry` / `PreviewResult` 加默认参数后，旧测试类会出现 `NoSuchMethodError`（`copy$default` 形态），首跑需 `compileTestKotlin --rerun-tasks`（仓内第 0 条假失败形态）。

## 七、风险与取舍

- 键变长：每键多约 20 字节（`|@` + 16 hex），持久化 JSON 体积增长可忽略。
- 旧数据迁移的边界：惰性复制发生在第一次打开该方法的预览时；若用户从未再打开某方法，其旧预设保持旧键不动，不影响其他方法。
- 「同一方法内 SQL 文本变化 ⇒ 预设失配」在本轮维持现状；用户已采纳「永久绑定方法、改 SQL 也不丢」，落到下一阶段的 T-PresetMethodBinding（见第九节），不与本轮混提交。
- HQL 预览不落历史条目（`previewHql` 未调用 `recordHistory`）是既有行为，本轮不修，但会在计划实施记录里登记为「顺带发现」。

## 八、实施记录

| 日期 | 事项 |
|---|---|
| 2026-10-21 | 立项：定位到键口径缺方法维度；确认既有隔离用例以「不同 SQL」代「不同方法」，未覆盖同 SQL 跨方法形态。 |
| 2026-10-21 | 隔离阶段实施（工作树 `mpsv-preset-scope` / 分支 `t-preset-method-scope`，自 dev `2abc222e`）。键尾追加 `\|@<scope>`，scope = `PreviewOriginScope.of` 的 16 hex（项目身份 + 项目内相对路径 + 方法签名 `名(canonicalText…)`）；注入点两处：`buildJpaPreviewResult`（HQL/JPA 全覆盖，它不落历史条目）与 `recordHistory`（Wrapper/批量/CRUD）。读路径五处回落旧键，`schemeNamesFor` 首次命中时惰性复制并随迁激活指针；R51 历史快照链按同一口径匹配（含旧条目兼容）。 |
| 2026-10-21 | 定向 **90/0/0/0**：`ParamHistoryCacheScopeTest` 11、`PreviewOriginScopeTest` 4、`SqlPreviewServiceOriginScopeTest` 1、`SqlPreviewPanelParamSchemesTest` 28、`ParamHistoryCacheTest` 28、`ParamSchemeManagerDialogTest` 11、`SqlPreviewPanelHistorySnapshotFallbackTest` 7。 |
| 2026-10-21 | 反向验证各恰 1 红：R1 摘面板 `historyCacheKey` 的 scope → 同 SQL 跨方法用例红；R2 摘 `buildJpaPreviewResult` 的注入 → 服务层用例红；R3 摘 `recall` 的旧键回落 → 回落用例红；R4 摘 `migrateLegacyFor` 的复制 → 迁移用例红。恢复后复绿。 |
| 2026-10-21 | 前提实证：`SqlPreviewServiceOriginScopeTest` 用 demo 同款语料（字面量 + `StringBuffer` 两形态）跑 `previewHql`，断言两方法 `sql` 逐字相同且 `originScopeKey` 不同——「旧键撞车」的前提成立，不再是推断。 |
| 2026-10-21 | 顺带发现（未修，登记）：`previewHql` 不落历史条目（HQL 链路从未调用 `recordHistory`），与本次改动无关；若要补，需先确认 HQL 预览的历史语义。 |

## 九、T-PresetMethodBinding（绑定阶段，第二轮实施）

### 目标

精确键（`SQL|@scope`）未命中时，在同一方法下找回该预设**最近一次保存**的快照——同一方法里改 SQL、增删条件后参数不再丢。SQL 从「归属维度」降级为「适用性标签」。

### 数据结构

- `PluginSettings.State.paramSchemeRecentKey`：`{"方案名":{"<scope>":"<完整缓存键>"}}`，嵌套 JSON（复用 `splitTopLevelEntries` 的 depth 处理，避免方案名与 scope 拼串时的分隔符冲突）。
- 内存侧 `ParamHistoryCache` 镜像一张 `ConcurrentHashMap<String, ConcurrentHashMap<String, String>>`，恢复/序列化随另外四个参数字段一起走。
- 写路径：`saveForScheme` / `saveAsScheme` 成功后更新 `(方案, scope) → key`；`switchScheme` 不更新（只换激活指针）。
- 读路径：新增 `recallRecent(key)` / `recallRecentFromScheme(name, key)`——只查指针回落，不查精确键，供面板区分「精确命中」与「回落命中」。

### 回填口径

- 回落命中只恢复**参数值**与 FieldFill 模拟值；`__chk__` / `__fnull__` / `__fev__` 三类结构态不参与跨 SQL 回落——Wrapper 条件 id 是 `c${toggleSeq++}` 的解析序号（`converter/QueryWrapperParser.kt:736`），SQL 增删条件后会整体移位，回落会张冠李戴。
- 只回填当前面板存在的 source（白名单 = `paramValues.keys`）。现有 `recallFromHistory` 的普通值分支对不存在的 source 也会插入，回落路径必须过滤，否则旧参数名会在下一次 `flushHistoryToCache` 里被写进新快照，越积越多。
- 面板 `recallFromHistory` 分两段：先 `recall`（精确 + 旧键），未命中再 `recallRecent`，后者走「仅普通值 + 白名单」的收敛分支。
- 可选：预设行落一条灰字提示「参数来自该方法最近一次保存」，让用户知道这是回落来的。

### 验收

- 定向：同方法改 SQL 后切回预设仍能召回同名参数；改名前参数名不在白名单时不回填；勾选态不回落（构造「改 SQL 后条件 id 移位」语料，断言不会把旧勾选套到新条件上）。
- 反向：摘掉指针回落 → 改 SQL 用例红；摘掉白名单过滤 → 脏键累积用例红；摘掉结构态排除 → 错位用例红。

### 实施记录

| 日期 | 事项 |
|---|---|
| 2026-10-21 | 绑定阶段实施（同分支后续提交）。`paramSchemeRecentKey` 嵌套 JSON 持久化；三条写路径（`saveForScheme` / `saveAsScheme` / 惰性迁移）刷新 `(方案, scope) → 键` 指针；`recallRecent` 只做指针回落，与 `recall`（精确 + 旧键）分工。 |
| 2026-10-21 | 实施中发现并修掉一个衔接缺口：改 SQL 后新键在方案桶里没有条目，`activeSchemeFor` 按「桶里有无条目」判定会回落默认方案，回落链在这里就断了。改为「最近键指针命中也算该方案在此键下有效」。反向探针 B4 证明该兜底不可省。 |
| 2026-10-21 | 面板收敛：`recallFromHistory` 分三段（精确缓存 → 同方法最近键 → 历史快照）；回落来源只回填 `currentParamSources()`（从结果模型取，不能用刚被清场的 `paramValues.keys`），且不恢复 `__chk__`/`__fnull__`/`__fev__` 结构态。`syncSchemeCombo` 把「已激活但名单里没有」的方案补进下拉。 |
| 2026-10-21 | 定向 **98/0/0/0**：`ParamHistoryCacheBindingTest` 7、`ParamHistoryCacheScopeTest` 11、`ParamHistoryCacheTest` 28、`SqlPreviewPanelParamSchemesTest` 29、`ParamSchemeManagerDialogTest` 11、`SqlPreviewPanelHistorySnapshotFallbackTest` 7、`PreviewOriginScopeTest` 4、`SqlPreviewServiceOriginScopeTest` 1。 |
| 2026-10-21 | 反向：B1 摘指针写入 → 5 红（4 数据层 + 1 面板）；B2 摘回落白名单 → 1 红；B3 摘结构态排除 → 1 红；B4 摘激活指针兜底 → 5 红。恢复后复绿。 |
| 2026-10-21 | R51 守卫 `testR51_生产接线在位` 同步升级：回落链文本从两级改三级，并新增一条「同方法最近键回落必须排在历史快照之前」的顺序断言（放开文本不等于放松顺序约束）。 |

