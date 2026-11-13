---
name: project-preview-openperf-m2-e2e-20261112
description: T-PreviewOpenPerfM2 端到端验收（2026-11-12，cust-big-demo 与用户日常 IDE 2026.3）——缺陷
  D1~D11 全修（D6 引用命中≠可用调用点、D7 or() 拍成 AND、D8 2026.3 getChildren(null)、D9/D11 值框与
  参数行、D10 清除残留）、M1 缓存命中与 D4/D5 经真机成立；M10 收窄 scope **无效**（冷索引固定成本），
  M11 gutter 预取 + 三条防自旋护栏；β3 已发双通道，D9~D11 与 schema 预热去重留待 β4
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

# T-PreviewOpenPerfM2 端到端验收（2026-11-12）

用户令「你来完成端到端验收，有问题就修掉」，中途改口「GUI 验收收手，我来验收」；随后用户自己跑完一轮，回
「效果还不错 比之前快 / 我已经验收完成 你看下日志」。详录见仓库
`docs/开发计划/20261112-1040-T-PreviewOpenPerfM2-预览装配性能与M2检索缓存-开发计划.md` §八。

## 真机读数（沙盒 `build/MPSV-E2EAccept`，IU-2024.3.7.1）

我这一轮（jar 12:12，无 D1 修复）：首开 `[open-perf] total=204ms … m2=134`、`request→dialog 607ms`；
再开 172 / 175 / 34ms。整场 `SEVERE`=0、`Plugin to blame`=0 ⇒ **M7（PSI 校验移出 EDT）真机成立**。
默认档正确：`distinct`、`orderByClause != null` 未勾，仅 `criterion.singleValue` 勾；无 `andand`/`)or(` 粘连。

**用户本人这一轮（jar 12:49，含 D1）——权威读数**：

| 次数 | `[m2]` | `[open-perf]` | `request→dialog` |
| --- | --- | --- | --- |
| 首开 | `resolved criteria=5` | `total=356ms … m2=306` | 747ms |
| 第 2 次 | `cache hit criteria=5` | `total=7ms … m2=0` | 104ms |
| 第 3 次 | `cache hit` | `total=5ms … m2=0` | 198ms |

⇒ **M1 缓存命中成立**（检索 306ms→0，装配 356ms→7ms）。首开仍要一次全项目检索（253 处引用），若要更快
可把检索范围收到模块级（未做）。

## 四条缺陷（D1~D4，全部已修、已推）

- **D1 真机致命：M2 调用点反推恒不命中**。产物停在 `{{ criterion.condition }}`，埋点
  `[m2] trace … methods=1 noCallSite`；同刻 `Alt+F7` 该方法是 **253 处引用**、索引健康检查干净 ⇒ 不是索引坏，
  是 **`ReferencesSearch(...).findFirst()` 在这条链上恒返回 null**（M5 引入，只在 Heavy 测试跑过、那条路本就 refs=0）。
  改 `forEach { hit=it; false }` 取首个命中 + 空结果打 `totalRefs=` 诊断。
- **D2 XML 里选中文字后按 Alt+Shift+Q 报「未找到」**：`Ctrl+F` 命中会选中语句 id → `previewXmlSelection` 按
  「startOffset 在选区内」收候选得 0 → `showResult(null)`；改为 0 候选回落光标所在语句。
- **D3 CI 唯一红是守卫没跟上 M7**：`43149573766`（@ `6775ba22b`）5 分片 + fast(2) 全绿，fast(1) 红在
  `ExecTempFileEdtGuardTest`（仍按旧名找 `revalidateOnEdt`）。守卫改按新契约（`revalidate(` 必须在
  `invokeLater(` 之前）＋「挪回 invokeLater 必红」反例；反向恰 1 红。
- **D4 同一方法里两个 Example 的条件被并成一条 WHERE**（用户截图抓到的产物级错误）：MBG 服务实现常写
  `exampleNA…selectByExample(exampleNA)` 紧接 `exampleNB…`，旧 `rootedAtExample` 的兜底是「变量**类型名**以
  Example 结尾」⇒ 检索 06A 时把 06B 的条件也收进来，产物成
  `WHERE (status = ? AND cust_name LIKE ? AND tenant_id = ? AND create_time BETWEEN ? AND tenant_id = ?)`
  （`tenant_id` 出现两次、谓词永不成立）。改为**只认同一个对象**：根引用等于实参变量名，或根是局部别名且初始化
  表达式追溯回同一 example（保住 `Criteria c = example.createCriteria()` 写法），类型名兜底删除。
  用例 `MbgExampleRootMatchTest` 3 例；反向（恢复旧口径）3 例全红。

## 提交状态（2026-11-12 用户令「全部都推」后已推前 4 笔）

插件仓：`85e09cab9`（D1+D2+埋点+LESSONS L44~L46+CHANGELOG）→ `511ad990a`（守卫）→ `3269e7722`（计划 §八）→
`11f189d23`（L45 补正确关法、trusted-paths 降级为偶发）→ `b12ccf8cc`（D4+用例）。**这一串已推双仓**
（`dev-support/push-all.ps1 -Branch master`，Gitee + GitHub 全走令牌、无弹窗），远端 ref 已核对等于本地 HEAD。
CI `43159709272`（@ `b12ccf8cc`）**8/8 全绿**（fast 1+2 + 5 分片 + nudge）——D3 的守卫修复由此坐实。

之后又落两笔、截至记录时**未推**：`dab9e97c7`（LESSONS L47，纯文档；按「只提交文档先别急着推」的口径押后）
与 `875c942f8`（D5）。

demo 仓：`7689f60`（修 `cust-big-demo` 编译，见 [[reference-cust-big-demo-fixture]]）**已推双仓**
（`30932a0..7689f60`），推送脚本是 demo 仓 `.local/push-master.ps1`（写法与两个坑见
[[reference-dual-remote-token-push]]）。

## D4 真机复验（用户 2026-11-12 13:11，jar 含 D4）

```
[m2] trace …CustAddressMapper#selectByExample methods=1 (and/or=7 … notRooted=5 …) [batch1203:2] -> criteria=2
[m2] resolved criteria=2 stamps=2   →  之后两次都是 cache hit
[open-perf] total=12ms … m2=2  → 弹窗 191ms ；total=8ms … m2=0 → 弹窗 14ms
```

`notRooted=5` 就是 D4 在干活：方法体 7 条 `and*`，5 条属另一个 Example 被拒，只收本链 2 条。热态已到 8~12ms。

## 缺陷 D5（已修 `875c942f8`，待真机复验）：混 kind 时 between 丢第二个占位

用户复验产物 `WHERE (create_time BETWEEN '11' AND tenant_id = ?)`。模型层三路探针（同模板 + 手工注入 mock）：

| 注入 | 产物 | 占位数 |
| --- | --- | --- |
| 只有 between | `create_time between ? and ?` | 2 ✓ |
| `tenant_id =` + between | `tenant_id = ? and create_time between ?` | 2 ✗（应 3）|
| between + `tenant_id =` | `create_time between ? and tenant_id = ?` | 2 ✗（应 3）|

只要同批混两种 kind，between 就少一个占位（`and #{criterion.secondValue}` 整段消失），真机那个 `'11'` 是
「一个占位 + 面板回填」的残骸。根因：`defaultState()` 按「组内只开一个」把 choose 强制成单一分支、
`renderSql` 又把未勾 id 全覆成 false ⇒ between 走 singleValue 模板。修法是**数据优先**：M2 注入元素级 kind 时，
`KIND_FLAGS` 那四个分支不再接受开关强制，交表达式按每条 criterion 自己的标志求值（`kindDrivenConditionIds()`），
其它形态的 choose 语义不变。机制面详情见 [[pitfall-xml-preview-mock-param-blindspots]]。
用例 `M2混kind_每条criterion按自身分支渲染`（三类注入 + 占位数 3/3/2）；反向（豁免返回空集）恰 1 红（17 例类内）。

面板左侧 `criteria.crit…` 输入框里的 `11` 不是计数串味：按 `criteria=2` 推，最可能是**值列表 `1,1`**（窄字段里
逗号几乎看不见）——复现不出「计数 11」的来源，不当 bug 追。

## 尚未闭环

- **D5 修复后的真机复验**：关沙盒 IDE、重跑 `.local/start-e2eaccept-ui243.ps1`（会先构建再起），期望产物
  `WHERE (tenant_id = ? AND create_time BETWEEN ? AND ?)`（三个占位）、`'11'` 消失。
- 全量门禁未跑；`ExecPsiGateTest` 7 红是既有桶位问题（断言原文写明该走 `testDb*`），非回归。
- 沙盒里打开 demo **没点「Load Maven Project」**（`Project JDK is not defined`），与用户「已导入 Maven + 有 JDK」
  的口径仍不同；demo 现已能真实编译（见上），下一轮可先 Load Maven 再验更贴用户现场。

## 复验操作要点

启动器 `.local/start-e2eaccept-ui243.ps1`（沙盒 `build/MPSV-E2EAccept`、预置
`%APPDATA%\JetBrains\IntelliJIdea2024.3\idea.key`、`-Pmpvs.licenseAgent=C:/Users/USER/.7t/patch/qwdn-afb.jar`、
`-PgifRobotServer=true`、登记+注销齐备）。日志 grep 点：`build/MPSV-E2EAccept/…/IU-2024.3.7.1/log/idea.log` 里的
`[m2]|open-perf|T-PreviewFast|Plugin to blame`。沙盒停机/坏索引/IME 三坑见 [[runide-demo-verify-workflow]] 末节；
在役包核验按 [[pitfall-sandbox-jar-staleness-and-window-enum]]，反向验证按 [[pitfall-git-checkout-reverts-uncommitted-probe]]。

## D5 真机成立 + 「第一次打开有点慢」的归因（2026-11-12 13:11 / 13:46 用户两次复验）

- **D5 已真机确认**：产物成 `WHERE (status = '1' AND cust_name LIKE ? AND tenant_id = ?)`——三条条件、三条占位、
  顶部「3 处 `${}`」与条件数对齐，`between` 那个 `'11'` 不再出现。
- **首开慢的钱全在 M2**（新起 IDE、索引刚建好那一次）：
  `[open-perf] total=1689ms parse+ctx=44 profile=1 m2=1628 model=16` + `request→dialog: 2714ms`
  （其中控制台预热 469ms）；同语句随后两次都已 `cache hit` → `total 15/13ms`、弹窗 `218/279ms`。
  ⇒ 装配（44+16ms）不是瓶颈，**首开 2.7s 里 1.6s 是那次全项目引用检索**。
- **M10（`2ef581793`）实测无效**：检索范围从 `projectScope` 收到
  `GlobalSearchScope.moduleWithDependentsScope(mapper 所在模块)`（引用方必须能看见该接口，语义上不丢命中；
  拿不到模块回落 projectScope；trace 增打 `scope=<模块名|PROJECT>`）——重跑读数 `m2=2042ms / 弹窗 4580ms`，
  比收范围前（1628/2714）**更慢**。⇒ 首开那 1.6~2s 不是「搜索面太大」，是**冷索引/冷 JIT 的固定成本**，
  换作用域不解决。这条否定结论已写进 [[pitfall-prefetch-needs-failure-memo-and-coldstart-vs-algorithm]]。
- **M11（`34488db41`）改法：不跟固定成本硬拼，把它挪到用户读文件时**。gutter 渲染本来就要遍历 mapper 语句，
  此时对 `parameterType` 以 `Example` 结尾的语句后台预取一次（缓存新鲜跳过、单元测试不触发）。
  真机首点 `[open-perf] total=59ms … m2=1` → 弹窗 1015ms（首开不再等检索）、第二点 `total=11ms … m2=0` → 弹窗 45ms。
- **M11 首版自旋（同日 14:05 实测，已修 `773421955`）**：`countByExample`/`deleteByExample` 在本项目里没有调用点
  → `resolve` 返 null 不写缓存 → gutter 每次重绘又触发一次，6 秒内同语句各 4 轮、共 18 条 `[m2] trace`，白烧全项目检索
  并刷屏。三条护栏：**失败记忆**（本会话不再预取该 key，预览路径照常现算）+ **单条在途** + **只预取 `select`**。

## 缺陷 D6（`bdc1f89ab`，用户日常 IDE 2026.3 复现）：命中引用不等于可用调用点

用户在**日常 IDE**（装本地 beta.3 包，jar 14:43/14:57）点 `mybatis-plus-demo` 的
`CtCustmanagerRelMapper#selectByExample` 仍是 `WHERE ( {{ criterion.condition }} ? )`：

```
[m2] trace cn.song.mybatisplusdemo.mbg.CtCustmanagerRelMapper#selectByExample scope=mybatis-plus-demo methods=1 noCallSite -> criteria=0
```

项目里有两个真实调用点（`CustmanagerRelQueryRepro.querySingle / queryOr`），且日志里**没有** `findCallSite empty`
那行诊断 ⇒ 引用取到了，是 `PsiTreeUtil.getParentOfType(ref.element, PsiMethodCallExpression)` 返回 null 后直接放弃。
根因：`ReferencesSearch` 对**接口方法**会把「实现的声明处 / 方法引用」一并返回，这类 ref 上溯没有方法调用节点；
旧逻辑只取第一个 ref。改为**跳过不可用的 ref 继续找**（找到第一个可用调用点仍即停），诊断补 `refsSeen=`。
与 D1 是两个失败面：D1 是 `Query.findFirst()` 恒空，D6 是第一个命中不可用。机制面见 [[pitfall-mbg-example-ref-and-or-grouping]]。

## 缺陷 D7（`ab9a8c308`）：`example.or()` 的条件被拍成 AND

用户截图：`queryOr()`（`createCriteria().andCustIdEqualTo(42L)` + `or().andStaffLoginLike("song%")`）预览成
`WHERE (cust_id = ? AND staff_login LIKE ?)`，真引擎语义是 `WHERE ( cust_id = ? ) or ( staff_login like ? )`。
三处一起修：①按 `createCriteria()` / `or()` 节点分组注入 N 条 Criteria（`oredCriteria`）；②M2 注入时**点号集合
表达式不再绑成全局集合**（原来外层每条 Criteria 都会把全部条件再渲染一遍，中间态读数 `( a and b ) or ( a and b )`）；
③foreach **单词分隔符**后补空格（`or(` → `or (`，标点分隔符 `,` 不动，保住 `IN (?,?)` 紧凑）。
用例 `M2两条Criteria_按or连接而不是and`，两个中间态就是反向读数。

## 缺陷 D8（`ab9a8c308`）：2026.3 菜单更新路径刷一条插件错误

用户贴的栈：`Throwable: Do not call getChildren(null). Do not expand action groups manually.` ←
`NewDataSourceEntry.buildNativeDriverActions(NewDataSourceEntry.kt:150)` ← `isAvailable` ←
`ChooseDataSourceTakeoverGroup.getChildren`。`isAvailable` 挂在**菜单更新**路径上却在那里展开原生驱动组。
修法：`isAvailable` 只做类可达判定（不在高频路径展开组），真正点击时才用带**真实事件**的重载（`getChildren(event)`；
243 编译目标上 `ActionGroup.getChildActionsOrStubs()` 不存在，编译报 Unresolved reference）。细节见
[[pitfall-actiongroup-getchildren-null-in-update]]。

## 推送与发行状态（截至 2026-11-12 15:1x）

用户令「全部都推」后，插件仓这一串已全部上双仓：`85e09cab9` → `511ad990a` → `3269e7722` → `11f189d23` →
`b12ccf8cc`（CI `43159709272` 8/8 绿）→ `dab9e97c7` → `875c942f8` → `2ef581793` → `34488db41` → `773421955` →
`4c6a283e9`（release 提交）→ `bdc1f89ab`（D6）→ `29cfa2502`（D6 文案）→ `ab9a8c308`（D7+D8+文案）。
demo 仓 `7689f60` 已在双仓。发行侧 tag `2026.11.12-beta.3` 已**两次前移**（`4c6a283e9` → `29cfa2502` → `ab9a8c308`），
见 [[project-release-20261112-beta3]]。

## 用户真机复验判据（下一轮）

- `CustmanagerRelQueryRepro.queryOr()` → `WHERE ( cust_id = ? ) or ( staff_login like ? )`；`querySingle()` → `WHERE ( cust_id = ? )`。
- 打开数据源列表时日志里不再出现 `Do not call getChildren(null)`。
- mapper 打开后 `[m2] trace` 里 `countByExample`/`deleteByExample` 不再重复出现；`selectByExample` 首点仍 `m2≈0`。

## 缺陷 D9（`89c74c663`，**未进 β3**）：M2 注入时 foreach 值框必须只读

用户真机手工往 `criteria.crit…` 框里填 `1` 后产物成 `create_time BETWEEN '1' AND ?`。机制见
[[pitfall-xml-preview-mock-param-blindspots]] 第八面：那是**值列表**框，填值会把 `__frch_criterion_0` 从注入的
Criterion 对象换成字符串 ⇒ `#{criterion.value}` 取到 `1`（文本加引号）、`#{criterion.secondValue}` 取不到。
修法＝`buildXmlForEachRow` 增 `injectedCount`，注入存在时 `isEnabled=false` + 提示改写；旧提示「可填 1」删除。
面板测试（`SqlPreviewPanel*` + `FieldFill*`）全绿。

同轮判断（用户问「新交互推荐吗」）：**不做成对值输入**——`syncForEachItemValues` 已按映射顺序支持
`a,b` 填 `.value`/`.secondValue`；缺的是防误填，不是新 UI。

## D6/D7/D8/D9 的落地与发行终态（2026-11-12 15:5x）

- 提交链再延：`bdc1f89ab`（D6）→ `29cfa2502`（D6 文案）→ `ab9a8c308`（D7+D8+文案）→ `89c74c663`（D9），
  全部已推双仓。
- β3 的 tag 停在 `ab9a8c308`；**D9 没折进去**（当时发行 run 已 14/14 全绿，为一个 UX 护栏第三次取消重跑不划算），
  用户未反对 ⇒ 随下一版对外。要注意：用户手上那版 `criteria.crit…` 框仍是可写的，得提醒他别填。
- 用户问「目前这个形态正确吗」的答法：结构对（同一 Criteria 多条条件 AND、区间两占位），`'1'` 是面板回填
  覆盖注入所致，不是渲染错——不要顺着用户的直觉改渲染器。

## D10/D11/D11b（`c33f9542c` + 未提交的标签修正）：面板侧最后一轮

用户两句反馈：「点清除后右侧还是有参数」+「LIKE 后面的参数怎么填，左侧参数栏没有这个参数」。

- **D11**：M2 注入后占位符来源全是 `__frch_criterion_N.*`，参数区按既有口径把 `__frch_` 前缀全滤掉 ⇒
  左栏**零可填行**（用户唯一能填的地方是 foreach 值框，而那会污染注入对象）。修法＝按真实占位键补行，
  `buildParamRow` 加 `labelOverride` / `tooltipOverride`。
- **D10**：`clearConditionValues` 只清 `xmlForEachValues`，而回收 `__frch_*` 的 `syncForEachItemValues`
  以该 map 非空为前提 ⇒ 清空后无人回收，残留值永久留在产物里。修法＝清除时显式回收全部 `__frch_` 键并清字段文本。
- **D11b**：标签原先按 `__frch_criterion_N` 的 `N` 取条件下标 ⇒ 真机显示「条件 2 值」（N 是渲染期计数器）。
  改为按**首次出现顺序**对齐，标签成 `cust_id = 值` / `create_time between 起始值|结束值`；参数区顶部加常驻
  说明「填值替换 `?`；留空保持 `?`」+ 行 tooltip。配 `MbgParamRowLabelTest` 5 例纯逻辑，面板回归**待跑**
  （子代理在同一棵树里 `buildPlugin`，同树两路 gradle 会互锁 `build/`）。

机制细节全部收进 [[pitfall-mbg-example-ref-and-or-grouping]]；schema 预热去重另立
[[project-schema-prewarm-dedupe-20261112]]。**这四条（D9/D10/D11/D11b）+ 预热去重都不在 β3 里**，
随 β4 对外。

## D12（灰框回显历史值）+ β4 文案就位（2026-11-12 16:1x）

用户真机看到两个灰框里仍有 `1,2,3` / `1,2`，问「这两个灰色无法填的参数是什么原因」。答法：灰＝D9 只读（那
两个集合放的是还原出来的**条件对象**，不是数字，要填值走下面「参数」区）；但框里回显历史旧值本身误导 ⇒

- **D12**：注入集合的值框**不回显**任何历史值（`text=""` + 占位文字「已还原 N 项」），且这些 expr 不再进
  历史快照、也不再被召回（`SNAPSHOT_FEV` 写/读两处都按 `collectionElements.containsKey(expr)` 跳过）。
- 同轮用户还确认「可以推远程」（授权 push）。

**β4 文案由子代理准备（`2c40bcb86`，只提交、未 tag/未推送）**：`version=2026.11.12-beta.4`；changeNotes
置顶新块（5 条：参数行可填 + 行名即条件 + 清除不留值 + 值框只读 + 预热去重）并删 β3 块评分句；
`preview.update.json` 同步；CHANGELOG beta.4 段；`docs/发行说明/20261112-1614-…内测说明.md`。
两道 tag 前门禁实测：`ChangeNotesRatingLineGuardTest` BUILD SUCCESSFUL（评分句全文 1 处）、
`inspect-plugin-zip --version 2026.11.12-beta.4` **PASS**（块内『（ ） ：』零命中）。

**子代理自己报的关键情况**：它跑 `buildPlugin` 时我在途的 D11b 改动还在工作树里 ⇒ 那个 zip 里的代码状态
未提交、**不能当发行物**（版本位/plugin.xml/changeNotes 判据不受影响）。戒律见
[[pitfall-buildplugin-zip-tainted-by-inflight-changes]]。

**并行纪律两条实测**（都花在等构建上）：
- 自己 `register` 了任务名之后，构建守卫仍把它算「活跃任务」→ 跑测试前要先 `done --name <自己的>`；
- `:prepareTestSandbox` 也吃过文件锁：`.intellijPlatform/sandbox/…/plugins-test/…/panweidb-jdbc-2.0.0.jar`
  报「请求的操作无法在使用用户映射区域打开的文件上执行」，原样重跑即过（见
  [[pitfall-preparesandbox-locked-by-running-runide]] 新增的测试沙盒变体）。

## D13c 真机成立 + 第二批四修（`e9b140fb1`，2026-11-12 17:2x~18:4x）

- **判「用户手上是哪个包」的硬办法**：读他自己 IDE 的插件目录
  `%APPDATA%\JetBrains\IntelliJIdea2026.3\plugins\mybatis-plus-sql-visualizer\lib\*.jar` —— jar 名带版本、mtime 是安装时刻。
  本轮 17:26 安装的正是本地 `…-2026.11.12-beta.4.jar`（zip 17:21 构建）⇒ 用户测的就是当日最新源码，不必猜。
- **D13c 真机成立**：对比 17:12（旧包）与 17:28（β4 包）两张截图，`criterion.betweenValue` 不再自动勾上。
- **`CustAddressMapper#deleteByExample` 在 demo 里 0 个调用点**（`grep -rn "\.deleteByExample(" --include=*.java` 零命中，只有接口声明）⇒ 产物 `DELETE … WHERE ( {{criterion.condition}} ? )` + 「预览未完整还原」横幅**是设计行为**，不是 bug。答用户「正常吗」必须先给这条证据，再谈别的。
- 第二批四修（同一提交）：T-LabelInset（Swing 截尾，见 [[reference-swing-jlabel-self-truncation-and-insets]]）、T-PreviewBannerLiveRefresh（`renderSql` 的 XML 分支不调 `updateMeta` ⇒ 横幅停在开窗那一刻）、T-FullTableWriteRisk（`WRITE_RISK` 横幅类别 + 纯函数 `fullTableWriteRisk`，判据只取**未回填**产物，字面量先挖空、注释先剥、只看首条语句）、T-ForEachInert（判据与值播种同源：`__frch_<item>_*` 映射缺席＝本轮没走到 ⇒ 该行灰字「本轮未展开」）。
- 验证形态：3 个新测试类（`LabelColumnInsetTest` / `FullTableWriteRiskTest` / `XmlPreviewBannerRefreshTest`）+ 三处反向探针（摘内边距 / 关风险判定 / 摘 `updateMeta`）各成对变红（读数：`可绘宽度 123 放不下文本 131`；风险侧 5+2 例；D 侧恰 1 例且断言正是「产物已无占位 ⇒ 陈旧渲染警示必须随本轮清除」）。反向探针要**真的摘到那一行**：我第一版把新插的注释行摘了、原调用还在 ⇒ 该断言仍绿，等于空验。
- β4 发行状态见 [[project-release-20261112-beta4]]。
