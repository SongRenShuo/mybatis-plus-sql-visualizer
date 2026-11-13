# T-PreviewStmtTypeScope：查询链预览被判成 UPDATE/DELETE——判型依据不可见、判型作用域过宽

- 立项：2026-11-12（用户真机反馈，我方未复现，先出计划）
- 触发：用户在 Component 形态的老工程里，光标落在 `LambdaQueryWrapper` 查询链上，预览给出
  `UPDATE cu_party_org_member SET …` / `DELETE FROM cu_party_org_member …`；同一批代码走到
  `mapper.selectOne(wrapper)` 处预览是 `SELECT`（用户判为「正常」），上一层调用点点击图标却变成 `UPDATE`。
- 关联：`SqlPreviewService` 语句类型分派（`ui/SqlPreviewService.kt:331-377`）、`WrapperLocator` 消费语义识别
  （`psi/WrapperLocator.kt:933-938` / :1044 / :1083）、`WrapperCache` 锚点维度（2026-08-12 同族回归见
  `psi/WrapperCache.kt:11-13`）

## 一、四张截图的读数（2026-11-12 用户提供）

| 图 | 代码位置 | 面板头部 | SQL 形态 | 左侧面板 |
| --- | --- | --- | --- | --- |
| 1 | 调用方 117-119 行（`new LambdaQueryWrapper` + 两个 `.eq` + `component.qryOneByWrapper(w)`） | `LAMBDA_QUERY` / `cu_party_org_member` | `UPDATE`：SET 全列（18 列，`?` 占位）+ `WHERE ORG_MEMBER_ID = ? AND PARTY_ID = ?` | 预设 + 动态条件（两条，均勾选）+ 参数 `#?` |
| 3 | 同上代码区 | 同上 | `DELETE FROM`：`WHERE ORG_MEMBER_ID = ? AND PARTY_ID = ?` | 与图 1 逐项相同 |
| 4 | Component 内 `qryOneByWrapper` 的 `mapper.selectOne(wrapper)` 附近（第 58-60 行） | 同上 | `SELECT` 全列 `FROM cu_party_org_member`，**无 WHERE** | 「无未解析参数」+ 底部「取消 / 执行 SQL / 复制」按钮 |
| 2 / 5 | 只是 MyBatis-Plus 3.5.2 `selectOne` 源码与 Component 类头，无预览产出 | — | — | — |

三处共同点：类型都报 `LAMBDA_QUERY`、同一张表、条件列都是那两个；图 1 与图 3 的条件面板逐项相同。
图 4 的面板形态（「无未解析参数」+ 底部按钮）与图 1/3（预设 + 动态条件 + 参数）**不是同一套左栏渲染**，
需要用户确认图 4 是从哪个入口触发的（图标 / `Alt+Shift+Q` / 声明行预览）。

## 二、判型机制实证（源码为准，逐条已核对）

预览语句类型取自 `WrapperChain` 的四个消费标志，分派顺序固定（`SqlPreviewService.kt:331-377`）：

| 优先级 | 产出语句 | 标志 | 置位条件（**只看调用方方法名**） | 集合出处 |
| --- | --- | --- | --- | --- |
| 1 | `SELECT COUNT(*)` | `countConsumed` | 名 ∈ {`count`, `selectJoinCount`, `selectCount`} | `WrapperLocator.kt:1430` |
| 2 | `SELECT EXISTS (…)` | `existsConsumed` | 名 = `exists`（链终结点） | `:1433` |
| 3 | `UPDATE`（实体列展开 SET） | `updateEntityConsumed` | 名 ∈ {`update`, `updateJoin`, `updateJoinAndNull`, `saveOrUpdate`} 且实参 ≥2、wrapper 不在首参 | `:1082`、判定体 `:1044-1063` |
| 4 | `DELETE FROM` | `deleteConsumed` | 名 ∈ {`remove`, `delete`, `deleteJoin`} | `:1410` |
| 5 | `SELECT`（默认） | 以上全为假 | 其余任何方法名，含不认识的自定义方法名 | 既有契约见 `ConsumerMethodsCoverageTest.kt:20-25` |

第 3 档 SET 列 = 表全列去掉主键与 `@Version`（`SqlPreviewService.kt:355-361`），WHERE 取 wrapper 条件——
与图 1 完全吻合；第 4 档 = `DeleteStatement(table, parsed.conditions)`（`:374`），与图 3 吻合。

两个决定性的点：

1. **标志的扫描范围是「该 wrapper 变量/链的全部引用」**——`ReferencesSearch.search(variable)` 逐个引用调
   `isConsumerArgument` / `isEntityUpdateArgument`（`WrapperLocator.kt:933-938`），不是在锚点所在语句里找。
   因此**同一变量在任何位置触发预览，判型结果都相同，与点击位置无关**。
2. **判型只认方法名字符串**，没有任何 receiver/家族门（`isConsumerArgument` `:1083-1097`、
   `isEntityUpdateArgument` `:1044-1063`）。同样的家族判定在锚点侧 `WrapperAnchorDetector` 是有的
   （`hasMpCrudReceiver`，`WrapperAnchorDetector.kt:128-146`）——两侧口径不一致。

由此可推：图 1/3 能出 UPDATE/DELETE，说明**那个 wrapper 的引用集合里确实存在名为
`update`/`delete`/`remove` 的消费调用**；又因为第 3 档恒在第 4 档之前，**同一个变量不可能在两次触发里
分别给出 UPDATE 和 DELETE**（除非两次之间改过代码，或面板显示的不是本次锚点的结果）。

## 三、三种可能（按可能性排序，各带判据）

**H1 同一 wrapper 变量在方法内被「查询 + 更新/删除」双消费**
用户在同一方法里把同一个 wrapper 既传给查询 helper，又在别处交给 `update(entity, w)` / `delete(w)`。
预览按「最强消费」判型（第 3/4 档先命中），于是看起来「查询链被判成 update」。
- 判据：把光标分别放到 117 / 118 / 119 行各触发一次，**三次结果相同**；在同一方法内按变量名查找它的全部出现处，
  能找到 `xxx.update(entity, w)` 或 `xxx.delete(w)`。
- **2026-11-12 用户回传真机代码确认 H1**：同一个 `cuPartyOrgMemberEntityLambdaQueryWrapper` 在 216-217 行
  交给 `cuPartyOrgMemberComponent.update(entity, w)`，232 行重新赋值后 235 行交给 `delete(w)`——两种消费
  同在一个方法里，中间还夹着别的分支。旧口径下 update 档位恒先命中，于是查询侧那条链的预览也报 UPDATE。

**H2 面板/结果没跟上本次触发（显示上一次锚点的结果）**
- 判据：同一图标连点两次结果应完全相同；在两个锚点间来回点，面板每次都跟着变。
  `WrapperCache` 的键 =（文件 + 锚点起始偏移 + 开关 + 表配置签名）+ `modificationStamp` 失效
  （`WrapperCache.kt:35-42` / `:76-82`），锚点偏移取自 `anchor.textRange.startOffset`；若两次触发解析到
  同一偏移却给出不同结果，问题就在缓存/面板刷新侧而不在判型侧。

**H3 锚点解析错位：同一文件多个 wrapper 链（查询/删除/修改），锚点配错了链**
- 判据：三次结果**互不相同**且与行号相关；需要该方法的完整代码确认其中有几个 wrapper 变量。
  （同族病灶出现过一次：缓存只按文件 → 删除/修改被串成查询，见 `WrapperCache.kt:11-13` 的 2026-08-12 记录。
  现在缓存键已含锚点偏移，但若锚点元素本身认错，「同一条链」的假设仍会破。）

## 四、定因（先做这一步，再做修复）

| 步骤 | 做法 | 期望读数 |
| --- | --- | --- |
| S1（用户） | 把光标分别放到 117 / 118 / 119 行，各触发一次预览（图标与 `Alt+Shift+Q` 各来一遍） | 三次相同 ⇒ H1/H2；三种类型都出现 ⇒ H3 |
| S2（用户） | 在该方法内 `Ctrl+F` 那个 wrapper 变量名，逐处核对是否有 `update(...)` / `delete(...)` / `remove(...)` 带它作实参 | 找得到 ⇒ H1 成立，判型其实是「对」的，问题是不可见 |
| S3（我方） | fixture F1「同变量双消费」：`w` 同时被 `qryOneByWrapper(w)` 与 `mapper.update(entity, w)` 消费，断言产出 `UPDATE` | 命中即证明「与点击位置无关、按最强消费判型」 |
| S4（我方） | fixture F2「参数锚点」：Component 形参 `wrapper` + 体内 `mapper.selectOne(wrapper)`，断言 `SELECT` 且无 WHERE | 复现图 4 的「跨方法取不到条件」行为，定性它是能力边界而非判型错 |
| S5（我方） | fixture F3「非 MP 同名调用」：`list.remove(w)` / `helper.update(key, w)`，断言当前是否误判 DELETE/UPDATE | 命中即确认 §五 M3 的过触发面真实存在 |
| S6（用户） | 提供该方法的完整代码（含 120 行以后）与图 4 的触发入口 | 收口 H1/H3 |

## 五、修复方案

**M1（必做，可见化）** 预览面板元信息行 / 悬停提示给出「判型依据」：语句类型 + 决定它的那次消费调用
（方法名 + 行号），例如 `UPDATE ← 第 120 行 cuPartyOrgMemberMapper.update(entity, wrapper)`。
目标：即使判型是对的，用户也能立刻明白为什么不是 SELECT。（同类先例：面板既有的「未绑定数据源」提示行。）

**M2（已拍板 2026-11-12：D-2 + D-3 一起落地）** 把消费识别从「该变量的全部引用」收窄到与「正在看的语句」
相干的范围，并把其余档位交给用户选：

| 序号 | 口径 | 结论 |
| --- | --- | --- |
| D-1 | 只看锚点所在语句 | **不做**：`w = new …;` 与 `service.remove(w);` 分离写法在声明行会掉回 SELECT |
| D-2 | 锚点所在语句 + 锚点之后的第一个消费调用 | **已实施**：作为**默认选中项**（`WrapperLocator.decideConsumption` 位置判定） |
| D-3 | 多档消费时面板给出多目标由用户选 | **已实施**：用户 2026-11-12 定「不要为了省事，往插件长远发展做」⇒ 与 D-2 并用，不是替代关系——D-2 决定默认落点，D-3 把其余档位摆出来，切换即按强制档位重算 |

**M3（必做，补家族门）** 判型侧复用锚点侧那套 receiver 家族判定（MP CRUD / MPJ / 自有 service 变量，
`WrapperAnchorDetector.kt:128-146` 的 `hasMpCrudReceiver` 口径），把「非 MP 的同名方法」挡在置位之外：
`cache.remove(k, w)`、自研 `xxx.update(key, w)` 这类调用不该把查询链改判成写。原则与锚点侧一致：宁可不判写。

**M4（必做，契约入文档）** 把 §二的判型表与优先级写进 `docs/架构设计/` 对应子系统文档的预览语句类型一节；
「未知消费名 → 默认 SELECT」已在 `ConsumerMethodsCoverageTest` 头注释里，同步到面板可解释的提示文案。

## 六、验证

- 定向：新增 `PreviewStmtTypeScopeTest`（F1/F2/F3 三例 + 用户真机形态「同变量复用 update/delete」一例 +
  D-2/D-3 的判型纯函数用例 + M1 文案用例）；回归
  `SqlPreviewServiceRoutingTest`（`:122` update 消费、`:164` delete 消费）、`ConsumerMethodsCoverageTest`、
  `WrapperLocatorTest`、`SqlPreviewTopRowLayoutTest`（工具栏新增入口）、`SqlPreviewPanelMetaHintTooltipTest`
  （meta 行新增分段）。
- 反向：临时摘掉 M3 的家族门 → F3 必须变红（同病变红才算规则看得见真实形态）。
- 真机：用 Component 形态的最小工程（`dev-support/test-demo` 或用户工程）走查——同一变量双消费时
  面板出现 M1 的依据行；D-2 落地后类型随锚点所在语句变化，且分离写法（声明行 + `remove(w)`）仍判 DELETE。

## 七、待验证前提（实施者先证伪再动手）

- P-1 「判型只看方法名、扫描变量全部引用、与触发位置无关」——按源码直读得出，须用 F1 实测钉住。
- P-2 用户看到的 UPDATE/DELETE 确实来自截图里那段代码（而不是别处锚点、或面板滞后）。
- P-3 `hasMpCrudReceiver` 那套家族判定可以无副作用地复用到判型侧（要确认它对 `IService` 变量、对
  「自有 Component 中间层」这两种接收者能判对）。
- P-4 图 4 的入口与图 1/3 是否同一条链路（面板左栏形态不同，可能是声明行预览或另一入口）。

## 八、不做

- 不改「未知消费方法名 → 默认 SELECT」的既有契约。
- 不改 MP 真实语义：`update(entity, w)` 确实就是 UPDATE、`delete(w)` 就是 DELETE，预览不该为了好看而降级。
- 不动 `WrapperCache` 的键维度（已含锚点偏移 + `modificationStamp`）；H2 若成立再单独立项。
- 不按「只认同语句消费」一刀切（会破 `声明 + remove(w)` 的存量写法），故 D-1 不单独实施。

## 九、实施记录

实施于工作树 `.worktrees/t-preview-stmt-type-scope`，分支 `t-preview-stmt-type-scope`，自 master `822519919` 起。

### 9.1 落地清单

| 项 | 内容 | 落点 |
| --- | --- | --- |
| M1 | 判型依据可见化 | `model/WrapperChain.kt` 新增 `WrapperConsumption` / `ConsumptionKind`；`WrapperChain.decidingConsumption` / `allConsumptions`；`PreviewResult.decidingConsumption` / `otherConsumptions`；面板 meta 行「类型依据: xxx() 消费（第 N 行）」+ 悬停列全部候选（`SqlPreviewPanelResultView.updateMeta` / `stmtTypeSourceText` / `stmtTypeCandidatesSection`，双语 key `panel.meta.stmtType*`） |
| M2-D-2 | 判型作用域收窄（默认落点） | `WrapperLocator.decideConsumption`（锚点所在语句 → 锚点之后最近一条 → 默认 SELECT）；三个 build 路径统一改为「先记证据、后单选」；证据按源码偏移排序（`ReferencesSearch` 返回顺序不保证源码序，实施中实测到 `[delete, update]` 反序） |
| M2-D-3 | 多目标切换（用户 2026-11-12 拍板「往长远做」落地） | `PreviewResult.statementTypeOptions` / `statementTypeSwitch`；`preview(anchor, ctx, preferred)` 强制档位 + 强制档位进 `WrapperCache` 签名（否则切换命中旧档位缓存）；面板工具栏「语句: X」入口 + `BaseListPopupStep` 弹层（`updateStmtTypeSwitch` / `showStmtTypePopup` / `switchStmtType`，重算走 `previewAsync`） |
| M3 | 接收者负门 | `WrapperLocator.hasNonDbReceiver`（JDK 容器/常见非 DB 类型，含父链、深度 4）。**偏离计划的一处**：计划写的是「复用锚点侧 `hasMpCrudReceiver` 家族判定」，实测该正向白名单会否掉未接依赖的自有 Service 桩与自有 Component 中间层（`ConsumerMethodsCoverageTest` 全量语料即属此类），故改为负门（只切「明确不是 DB 接收者」的一小撮）——P-3 的结论即此 |
| M4 | 判型表入文档 | `docs/架构设计/02-核心流程详解.md` 新增 §2.3.1（判型表 + D-2 / D-3 / M3 / M1 四条口径），预览流程图节点补指向 |

### 9.2 验证读数（2026-11-12）

- 定向（六类）：`PreviewStmtTypeScopeTest` 12/0/0 · `ConsumerMethodsCoverageTest` 33/0/0 ·
  `WrapperLocatorTest` 31/0/0 · `SqlPreviewServiceRoutingTest` 6/0/0 ·
  `SqlPreviewTopRowLayoutTest` 9/0/0 · `SqlPreviewPanelMetaHintTooltipTest` 7/0/0 —— 合计 **98/0/0**。
- 反向（M3 负门）：临时注释 `if (hasNonDbReceiver(call)) return null` → `PreviewStmtTypeScopeTest` **12 例 1 红**，
  且恰好是 `testJdkCollectionRemoveIsNotSqlConsumption`（message：`List.remove(Object) 不是 SQL 删除消费，不应改判 DELETE`），
  其余 11 例保持绿（探针精准、非散弹）；还原后 12/0/0。
- 用户真机形态回归：`testReusedWrapperVariablePicksConsumptionOfAnchoredStatement` 直接复刻 216-217 行
  `update(entity, w)` + 232 行重新赋值 + 235 行 `delete(w)` 的复用写法——锚点在 update 语句判 UPDATE、
  在 delete 语句判 DELETE，两处证据都进候选集。

### 9.3 not run（如实登记）

- **全量门禁**（`testFast` / `testShards`）未跑：按纪律由独立只读收口车道跑，不在实施车道跑全量。
- **真机 GUI 验收**未跑：需在 2026.3 真机确认「语句: UPDATE」按钮出现、弹层可切到 DELETE 且产物随之变化
  （沙盒/用户工程二选一）。
- **构建校验**（`verifyGate` / `buildPlugin`）未跑：本改动未动 `build.gradle.kts` / `plugin.xml` 的 EP 与
  since-build，不属「大改动」判据。

### 9.4 真机验收回抓的第二处缺口：重新赋值行的 `new` 锚点（2026-11-13）

用户拿 demo 语料 `WrapperReuseRepro` 第 100 行（`w = new LambdaQueryWrapper<>();` 的重新赋值）点预览图标，
得到的是**无 WHERE 的 SELECT**（`SELECT id, order_no, … FROM t_order`），条件与消费全丢。

根因：`locateWithTable` 的定位顺序里 `findWrapperVariable` 先于 `buildChainFromNew`，但**赋值表达式右侧的
`new` 不在任何 `PsiVariable` 祖先内**（LHS 的变量不是 RHS 的祖先），于是落到 `buildChainFromNew`——它只收
「以该 `new` 为链首」的调用（`new W().eq(...)` 连写形态），赋值之后另起语句的 `w.eq(...)` 与 `delete(w)`
自然全丢；锚点又落在两条消费之间，判型证据也一并丢失，退化成默认 SELECT。

两处修法：

| 编号 | 修法 | 落点 |
| --- | --- | --- |
| D-B1 | `findWrapperVariable` 新增 `PsiAssignmentExpression` 分支：LHS 解析到 wrapper 变量就按**变量**返回（LHS 不是 wrapper 时不命中、继续向上，不拦普通赋值） | `WrapperLocator.findWrapperVariable` |
| D-B2 | 重新赋值分段：赋值 LHS 的偏移记为段界，`filterCallsToAnchorSegment` 只收**锚点所在段**的链上调用（锚点在首次重新赋值前 → 只收声明段；之后 → 只收该赋值之后）。消费证据**不过滤**（D-3 切换要看到全部档位） | `WrapperLocator.buildChain` + `filterCallsToAnchorSegment`（internal 纯函数） |

读数：`PreviewStmtTypeScopeTest` 新增两例（赋值锚点端到端 + 分段纯函数），定向六类 **100/0/0**
（PreviewStmtTypeScopeTest 14 + ConsumerMethodsCoverageTest 33 + WrapperLocatorTest 31 +
SqlPreviewServiceRoutingTest 6 + SqlPreviewTopRowLayoutTest 9 + SqlPreviewPanelMetaHintTooltipTest 7）；
反向两探针（分别摘掉 D-B1 / D-B2）各自恰红，见 §9.5。

### 9.5 反向验证（2026-11-13 第二轮）

| 探针 | 摘掉什么 | 读数 |
| --- | --- | --- |
| A | `findWrapperVariable` 的 `PsiAssignmentExpression` 分支 | `PreviewStmtTypeScopeTest` **14 例 1 红**，恰为 `testReassignmentAnchorUsesVariableChainAndAnchorSegment`，message「锚点之后最近的消费是 delete，应判 DELETE: **null**」——判型依据全丢，正是用户看到的退化形态 |
| B | `filterCallsToAnchorSegment` 的段过滤（直接 `return calls`） | 同 **14 例 1 红**、同一用例，message「不该把 update 段的 status 条件带进来: DELETE FROM `order` WHERE user_id = ? AND status = 0 AND order_no = ? AND user_id = ?」 |

两探针各自命中一处修复、互不掩盖；还原后 14/0/0（合计见 §9.4 末行）。

### 9.6 已知边界

- D-2 的默认落点仍是启发式：锚点在「消费之前、且中间没有消费语句」时取最近一条；要换档只能靠 D-3 手动切。
- D-3 只在**同一 wrapper 上出现 ≥2 档**时出现入口；单档场景面板完全不变（不留闭包、不占工具栏位）。
- 强制档位重算会照常 `recordHistory`——同一段代码的不同档位各记一条历史（它们是不同 SQL，行为合理）。
- 重新赋值分段只按「赋值点在锚点前后」切段：同一段内若再次对同一 wrapper 追加条件（`w = new …; w.eq(a); w.eq(b); delete(w)`）仍是整段合并，不做语句级细分。

