# T-MbgPreviewParityAndOpenPerf：MBG 预览多条件粘连 + 预览窗口打开耗时——开发计划

- 立项：2026-11-12
- 触发：用户反馈「结果和我复现的不一致」（附面板截图 5 张），并报「点击弹出预览窗口耗时较长」
- 范围（P1/P2/P3）：`xml/TrimSqlNode.kt`、`xml/MbgExamplePreviewTest`（及新增用例）
- 范围（P4/P5）：待裁决 / 待埋点，见 §四

## 一、现象（用户 2026-11-12）

用户产物（自己项目 `...itech.crm.cust.metadata.CtCustmanagerRelExample`，Oracle，已绑定数据源）：

```
SELECT DISTINCT 'false' AS QUERYID, CUST_ID, STAFF_LOGIN, … , CUST_CARE_FLAG
FROM CT_CUSTMANAGER_REL
WHERE (CUSTOMERNUMBER = ('3') AND UNIT_ID = (?) AND CUST_ROLE_CODE = (?) AND CUST_ID = (?
ORDER BY 1
```

面板状态：`distinct` ✅、`_parameter != null` ✅、`criteria.valid` ✅、`noValue` ✗、`singleValue` ✗、
`betweenValue` ✗、**`listValue` ✅**、`orderByClause != null` ✅；foreach 输入框 `1` / `2` / `3`。

## 二、本机复现与差异（回答「是不是我的用例和用户不一样」）

用同一套 MBG XML + 用户的面板状态 + M2 注入的 4 个 Criterion 复现，产物：

```
default  = … FROM CT_CUSTMANAGER_REL WHERE ( CUSTOMERNUMBER = ?and UNIT_ID = ?and CUST_ROLE_CODE = ?and CUST_ID = ? )
userState= … WHERE ( CUSTOMERNUMBER =(?) and UNIT_ID =(?) and CUST_ROLE_CODE =(?) and CUST_ID =(?) ) order by 1
```

**四条差异（我的金样 vs 用户现场）**：

| # | 我的 `MbgExamplePreviewTest` | 用户现场 |
| --- | --- | --- |
| 1 | **1 个 Criterion**（`cust_id =`） | **4 个**（CUSTOMERNUMBER / UNIT_ID / CUST_ROLE_CODE / CUST_ID） |
| 2 | 默认档（choose 只开 `singleValue`） | 手动勾 `listValue` + `distinct` + `orderByClause` |
| 3 | foreach 值留空 | 填了集合值（`1` / `2` / `3`） |
| 4 | 单列 | 4 个不同列 |

结论分两层：

- **不是引擎错**：`DISTINCT` 与 `ORDER BY 1` 来自用户手动勾选（默认档下 profile 会抑制这两项）——
  第 2 条差异直接解释了这两个多出来的片段。
- **但复现暴露了两个真缺陷**（下节 R1/R2），而我的金样**恰好**因为「只有 1 个 Criterion + 默认档」
  两个原因同时避开——这就是「用例与用户不一样」的实质。

> 截图里 `CUST_ID = (?` 结尾看起来少一个右括号：复现证实 `)` 是有的（`… CUST_ID =(?) ) order by 1`），
> 截图是 SQL 编辑器水平方向被截断。**不是缺陷**。

## 三、根因

### R1（P1，语义级）`#{x}` 的尾字符 `}` 不在补空格判据里 → `?and` 粘连

`TrimSqlNode.FilteredDynamicContext` 的补空格判据在 `6212f24e0` 被收窄为
`endsOperand(sqlBuffer.last()) && startsIdentifier(sql.first())`；`endsOperand` 覆盖
标识符字符、`)`、`]`、`?`、引号——**不含 `}`**。

而 trim 缓冲里放的是**渲染前文本**（`#{criterion.value}`），`#{}` → `?` 的替换发生在最后一步
（`XmlSqlRenderer.parseParameterMappings` 扫全文）⇒ 判据里那个 `?` 在这个阶段**永远不可能出现**，
缓冲里看到的是 `}` ⇒ 判定失败 ⇒ 两轮迭代拼成 `?and`。

后果：`WHERE ( A = ?and B = ?and C = ? )` 是**语法错的 SQL**（真的跑不了），比当年的 `andand` 更隐蔽
（`andand` 一眼可疑，`?and` 像是带参数的空格问题）。该缺陷自 `6212f24e0` 起就在 master 上，
我的金样是单 Criterion（只有一次迭代、无相邻拼接）因而全程没暴露。

### R2（P2，形态级）操作符 + `(` 不补空格 → `=(`

`CUSTOMERNUMBER =(?)`：段「`and CUSTOMERNUMBER =`」与内层 `<foreach open="(">` 的 `(` 紧贴。
真实 MyBatis 的 XML 原文在这两者之间有换行 ⇒ 输出里有空白；本引擎在
`ForeachSqlNode.FilteredContext` 把迭代内容 trim 成紧凑段，而 trim 的判据（R1 那条）对
`=`→`(` 不成立 ⇒ 粘成 `=(`。不改变语义，但形态像函数调用。

修这条要小心：**不能把 `count(*)` 撑成 `count (*)`**，也不能破坏 `IN (?,?)` 的紧凑
（`6212f24e0` 正是为收掉这两个 CI 红才收窄的判据）。

### R3（用例覆盖）金样形态恰好避开缺陷

见 §二 差异 1/2。**补测动作**：
- ≥2 个 Criterion 的用例（默认档）——钉 `?and`；
- 用户态用例（手动勾 `listValue` + 集合值）——钉 `=(`/listValue 分支形态。

### R4（语义冲突，待裁决）M2 反推的真实 kind 与面板勾选打架

M2 从 `addCriterion("CUSTOMERNUMBER =", value, "custNumber")` 能知道每个 criterion 的真实分支
（这里是 `singleValue`），但渲染仍完全听面板开关 ⇒ 用户开了 `listValue` 就渲染成
`CUSTOMERNUMBER = (? )` 这种「`=` 配 `(`」的组合。四种处置：

- (a) M2 有真实 kind 时**锁住**该 criterion 的分支（面板开关灰置）；
- (b) 冲突时给横幅提示，不锁；
- (c) 保持现状（面板是唯一真值源）；
- **(d) 只改默认值**：M2 命中时，`defaultState()` 里 choose 组的默认分支按**反推到的真实 kind**
  取（而不是现在的「含 `#{}` 的分支里最先出现者」）——用户不动勾选时默认就渲染成真实语义
  （4 个 `singleValue` ⇒ 走 `singleValue` 分支），手动改仍完全生效。

**推荐 (d)**。理由：面板开关继续是唯一真值源（不引入「勾了不生效」的不可预期），不需要新增横幅
文案与冲突判定，默认值即正确；用户这次的困惑（以为 `listValue` 是真实分支）在 (d) 下不会出现——
他打开面板看到的就是 `singleValue` 被选中。(a) 会让面板交互变得难以解释，(b) 多一层提示但默认
仍是错的，(c) 把「用错分支」的责任留给用户。实现要点：`defaultState()` 需要一个「真实 kind 汇总」
（从 `collectionElements` 的 MockObject 读四个布尔 → 映射到 `criterion.noValue` 等 test id），
M2 未命中（无 `collectionElements`）时退回现有启发式。

### R5（性能）打开预览 5.7 秒空档，且插件侧无埋点

沙盒 `idea.log`（用户 2026-11-12 08:48 那次点击）：

```
08:48:25,823 [82192] [M36] prepareDefaultConsole: editor=false, sqlLen=151, dbAvailable=true
08:48:25,965 [82334] [M36] prewarm done in 153ms (file=mpsql-preview-80128-3.sql, …)
08:48:26,0  … 08:48:29,6    ← 3.7 秒零日志（插件无埋点）
08:48:29,660 [86029] Saving global entities …SdkEntity
08:48:29,662 [86031] Saving global entities …LibraryEntity
08:48:29,716 [86085] Saving Project(mybatis-plus-sql-visualizer-demo, COMPONENT_CREATED)
08:48:31,634 [88003] [M36] showSql branch=editor: len=135, nativeDoc=null   ← 首次渲染
```

⇒ `prewarm done` 到首次 `showSql` 之间 **5.67 秒**；平台侧在 29.66 做 JPS 全局模型保存
（SdkEntity / LibraryEntity / Project 组件存储），说明这段在等平台侧的项目模型/索引活动。
同一窗口另有 `DatabaseSchemaResolver scan: table=<t>, ds=<15 个数据源>` 逐表轮询（全部「无法解析」）。

嫌疑（**待验证**，当前只是相关性）：

- **S1** `MbgExampleCallResolver.resolve()` 在主预览路径上同步跑
  `ReferencesSearch.search(method, projectScope).findAll()`——全项目索引搜索；冷索引/大工程代价高，
  且每次 preview 都重跑（无缓存）。
- **S2** `DatabaseSchemaResolver` 按「表 × 全部数据源」轮询（本机 15 个数据源，含远程实例），
  日志只记 scan 不记耗时，真实连接尝试可能吞掉时间。
- **S3** 平台 JPS 全局模型保存被谁触发（首次访问模块/依赖？）——需要更大时间窗的日志。

现有前置知识：`pitfall-preview-open-lock-inversion` 记录过「预览打开慢的真因是插件自锁」（已修到
1619ms），**剩余五项优化已定位未实施**（预热重复写同一 exec 文件、DumbMode 直接抛、指标重复计数、
预热预算 500ms vs 实测 2.8s、一次打开装配两次）——本次 5.7s 与之是否同源需一并核对。

#### 关于「忘记识别 `typeAlias`」（用户 2026-11-12 提出）

对 **iBatis 链**成立，对本次 MBG 的 5.67s **不成立**：

- MBG 的 `parameterType` 是 FQN（`…itech.crm.cust.metadata.CtCustmanagerRelExample`），
  `resolveExampleProfile` 走 `JavaPsiFacade.findClass(FQN)`——一次索引查询，不遍历短名、也不读
  typeAlias。这条路径上没有 typeAlias 可省。
- 本次同步路径上唯一的重 PSI 动作是 M2 的 `ReferencesSearch`（S1），与 typeAlias 无关。

但用户的判断在 iBatis 侧是真问题，而且**比「模块收窄」更强**：iBatis 的 `parameterClass="User"` 若能
先查 `<typeAlias alias type>` 表拿到 FQN，就是 FQN 直查（O(1)、无歧义、不必模块过滤）；短名索引只在
别名表查不到时兜底。当前实现是「FQN 直查 → 短名索引 → 模块过滤 → 弃权」（L34），缺了最准的第一档，
而大工程里短名索引本身也可能是慢点（命中数 × PSI 加载）。**列 P6**。

注：P6 与 S1 是两条独立的性能面——即使 P6 做完，MBG 这条 5.67s 仍需 P5 埋点定位（S1/S2/S3）。

## 四、方案

| 编号 | 内容 | 优先级 |
| --- | --- | --- |
| P1 | `endsOperand` 纳入 `}`（`#{}` / `${}` 收尾）——直接消掉 `?and`；顺带核对 `${}` 收尾同形 | 高（语义级） |
| P2 | 操作符 + `(` 补空格（仅在「左侧是操作符字符」时），保住 `count(*)` / `IN (?,?)` 紧凑 | 中（形态级） |
| P3 | 补多 Criterion 用例 + 用户态用例（listValue + 集合值），两条都做反向 | 高 |
| P4 | M2 kind 与面板勾选：**采纳 (d) 只改默认值**（M2 命中时 choose 组默认分支按真实 kind 取，交互不变） | 高（已拍板） |
| P5 | 预览链分段埋点（装配 / M2 检索 / renderSql / schema 解析各一段），复测定位 5.7s；按结论决定 M2 缓存或移出 EDT | 高 |
| P6 | iBatis `parameterClass` 别名先查 `<typeAlias alias type>` 表（config 按模块解析 + 缓存），FQN 直查优先于短名索引 | 中（准确 + 性能；大工程更明显） |

## 五、待验证前提

- **V1** R1 的 `?and` 在当前 master 上确实存在：本机已复现 ✓（§二 default 行）。反向（摘 P1）应恰好
  让新增的多 Criterion 用例转红。
- **V2** 用户是否手动勾选过那些条件：截图证据支持（`listValue` 开着而 `singleValue` 关着，与
  M1 的 choose 组默认相反），但需用户确认是否点过「全选」——这决定「DISTINCT / ORDER BY 1」是输入的
  正常结果还是 profile 未命中导致的默认展开。
- **V3** 5.7 秒的归属：需 P5 埋点读数，当前只有「插件零日志 + 平台在保存 JPS 模型」的相关性。
- **V4** M2 在用户项目里的检索代价（`ReferencesSearch` 命中数、索引是否冷）；需在用户规模的工程上测。

## 六、边界（不做）

- 不改 M2 的 kind 反推逻辑（P4 只裁决「谁优先」）。
- 不为可读性过度插空格：`IN (?,?)` 的紧凑是 `6212f24e0` 用两例 CI 红换来的约束，必须保住。
- 不把 `CUST_ID = (?` 当作缺右括号修——已证伪（是截图截断）。
- 不在本轮改性能实现（P5 先埋点定位；实现另立）。

## 七、实施记录

| 项 | 提交 | 落点 | 反向验证 |
| --- | --- | --- | --- |
| P1 | `9972780e6` | `TrimSqlNode.endsOperand` 纳入 `}`——trim 缓冲是渲染前文本，判据里的 `?` 永不出现 | 摘掉 → 多 Criterion 用例恰 1 红 |
| P3 | `9972780e6` | `MbgExamplePreviewTest` 新增「多 Criterion 相邻迭代不粘成 `?and`」（对齐用户 4 个 Criterion） | — |
| (d) | `9972780e6` | `XmlPreviewModel.defaultState` 的 choose 组默认分支按 M2 反推 kind 的**多数票**取；M2 未命中退回启发式 | 摘掉 → `M2命中_choose组默认分支按真实kind取` 恰 1 红 |
| P2 | `e886812e5` | `needsSeparator`＝「粘连成词才补」+「运算符 + `(` 补」；边界用例钉 trim 体内 `IN (?,?)` 与 `count(*)` | 摘掉 operator 档 → 用户态用例恰 1 红 |
| P5 | `e3b766356` | `XmlPreviewService` 四段耗时埋点（解析+上下文 / profile / M2 / 建模，总耗时 <200ms 不打） | 观测项，无行为变更 |
| P6 | `e6fbdeb88` | 新 `IbatisTypeAliasResolver`（扫模块内容根的 `sqlMapConfig` 读 `<typeAlias>`，会话级缓存）+ `findClass` 别名优先于短名索引 | 摘掉别名分支 → `testTypeAliasResolvesAmbiguousShortName` 恰 1 红 |

**实施中的两次自查纠错**：

1. P2 的边界用例第一版把 fixture 放进 select 体内——根上下文的 `StringJoiner(" ")` 本来就按段补空格，
   所以 `IN (?,?)` 直接红。`6212f24e0` 的紧凑约束只在 **trim 体内**成立，夹具层级放错（同
   `pitfall-reverse-probe-fixture-layer-mismatch`）；改到 `<where>` 体内后绿。
2. 并行会话抢共享 `plugins-test` 目录致 `prepareTestSandbox` 失败（`panweidb-jdbc-2.0.0.jar`
   被「用户映射区域」占用）——按登记表改用独立 `sandboxContainer`（`MPSV-ParityAndOpenPerf`），
   跑完已还原 `build.gradle.kts`（不入库）。

**读数**：

- 本地已跑（分项、各自全绿）：P1/(d)＝`MbgExamplePreviewTest` + `TrimSqlNodeTest` + `ForeachSqlNodeTest` +
  `XmlPreviewModelTest` + `SqlPreviewPanelForeachValuesTest` + `XmlSqlRendererTest`；P2＝同上前四个 +
  `SqlPreviewPanelForeachValuesTest`；P5＝`XmlPreviewServiceTest` + `XmlEwResolverTest` +
  `MbgExamplePreviewTest`；P6＝`IbatisParamClassQuoteHeavyTest`(8) + `IbatisParamCandidatePickTest`(6)。
- 反向四轮：P1 与 (d) 合并摘除时恰 2 红（各对应一条用例）、P2 摘 operator 档恰 1 红、P6 摘别名分支恰 1 红。
- **全量定向与守卫：本地未跑完**——`clean` 把独立沙盒一起清掉，重铺 IDE 发行版时挂起（沙盒目录 45 秒零增长，
  测试 JVM 无输出），按用户令**改交 CI**（push master 触发 push-test 五片）。
- **CI 读数（run `43136753539`，树 `51d3bd88a`）**：**9/10 job 绿**（test 1/3/4/5 + fast 1/2 + nudge）；
  唯一红＝分片 2 的 `LogToSqlPanelExecuteTest#testPlatformPopupSelectionDisposeIsNotUserCancellation`
  （`AssertionFailedError: expected:<[CallbackRunner@…]> but was:<[]>`）。
- **该红与本轮改动无关**（决定性证据：前一轮 `43129449046` 的树是 `bf258ec02`＝本次改动**之前**的 master，
  同 job 同用例同形态；`43134264631` 的树 `5a5eba644` 亦然）。它属 `log` 面板，本次改动全在 `xml`／预览 `ui`，
  无交集 ⇒ 需另立任务定位（形态像「回调未登记/未触发」）。

**待用户复验**：`sqlmap/User.xml` 的 iBatis 裸参数在**别名场景**下的引号（别名表首次命中）；
MBG 多条件产物不再出现 `?and`；P5 埋点需真机再点一次预览才能读出 `[open-perf]` 分段耗时。
