---
name: project-explain-fail-ux
description: T-ExplainFailUx + T-ExplainSwitchNative（20261024 全实施并**合并为 2 笔**：`3117f6db` 功能 + `0b7e8c82` 文档，未 push）——选错源就地引导；**插件侧自研换源 UI 全部退役**（下拉/链接/按钮皆删，W11 已 revert），换源一律走平台原生工具栏 Data Sources 菜单；含平台枚举 forced 短路、无公开清除 API、createDataSourceConsolesRunner 造新建控制台项三条反编译事实；**附「Unknown column 算不算选错源」判据（表级缺失才算，列级不算）与待拍板的升格方案**
metadata:
  node_type: memory
  type: project
  originSessionId: session_b380d49669e298cff6e310c72afca927
---

# T-ExplainFailUx → T-ExplainSwitchNative：执行计划失败呈现与换源（20261024 全部实施，未 push）

起点：用户验收 T-DialectInferPollution W12 后提诉求（原话）「这种情况就不应该弹窗了吧 应该直接是
第二个图 引导用户更换数据源」「这个 sql 窗口的原生工具栏能不能默认就显示 这样用户知道接下来要用的
数据源是哪个 减少误解」「注意当数据源插件不可用时要降级的」。

**终态（2026-10-24 晚，用户令「稍微合并一下」）**：三份计划的 19 笔过程提交压成
`3117f6db`（功能合并，含 W11 revert；正文按三计划分段 + 末附三轮教训）+ `0b7e8c82`（文档）。
并行会话的 `8872ba42`（magpie 文档）被保留在历史中未被卷入。当前未推送共 6 笔。

## T-ExplainFailUx W1~W4（`020bfc39` + `29f3e4b2`，W2/W3 后被动过）

- **W1（保留）**：`ExplainFailureKind.WRONG_TARGET`；签名 `doesn't exist` / `does not exist` /
  `unknown table` / `invalid object name` / `ora-00942` / `no such table`（MySQL 1146/PG 42P01/
  SQLServer 208/Oracle 942/SQLite）。**分型顺序 AUTH > WRONG_TARGET > CANCELLED > OTHER**；
  `Unknown column` **不算**（列级问题换源解决不了，测试钉死）。两处出口（结构化 `onFailed` +
  直送 `Result.Failed`）→ `showExplainWrongTarget`（`SqlResultPanel.showErrorWithActions`），
  不弹模态、不回退直送。
- W2（自研下拉）/W3（换源自动重试）：**均已被后续推翻**，见下。
- **W4**：`preferredRunnerName` 按名直选，消除「选两次数据源」——根因 `forceChoose=true` 的语义
  是「跳过 last-used 静默」而**不是「弹窗」**。（W12 后该参数仍在 API 面但无换源入口调用它。）

## W5~W7（`54f7d784`，第一次撤自研 UI）

用户实测三问题：①点横幅「切换数据源」**没弹列表反而直接重试执行**（runner 唯一时
`shouldBeChosenIfAlone` 直选，横幅还承诺「切换后自动重试」）②自研下拉**只改显示、真正执行仍用原生
控制台工具栏的源**（`prepareConsole` 只换共享控制台，内嵌 `ConsoleView` 是平台的、attach 源不跟随）
③理想形态＝IDEA 原生 `Data Sources` 弹窗。

- W5 自研下拉整体退役 → 指示行纯文本 + 「切换数据源」链接；W6 统一入口 + `!forceChoosePopup` 抑制
  直选 + 删自动重试；W7 换源前 `dropSharedConsole() + clearPrepared()`（原生控制台成唯一事实源）。
- **P-1 取舍（走方案 B）**：`ChooseDataSourceAction.getContext` 需 `PsiFile`+`VirtualFile`，且
  `contextMenuInvokedInsideSelection/OnResultSet` 为真时返回 null ⇒ 不硬构造 DataContext 调
  ActionGroup，改用平台 `Runner` + 平台 `ListPopup`（既有 `showRunnerPopup`）。

## W8~W10（`7e88ade4`，第二轮实测）

| 项 | 用户现象 | 根因（实证） | 修法 |
|---|---|---|---|
| W10 | 「为什么 sql 无法执行」：红条「当前 SQL 按 **未知** 渲染」 | `executionDialectGateRequest` 传 `result?.effectiveDialect?.name`；JPA/日志还原载体 `renderedDialect` 未赋值 → null → `RENDER_UNKNOWN_BLOCK` | 请求侧回落 `MySqlDialect.name`（`Dialect.kt:908-909` `byName(null)` 即回落 MySQL——实际渲染方言从不是「未知」） |
| W9 | 「点击下方的切换数据源依然有问题，点击没有反应」 | 红条按钮接 `executeSql(forceChoose=true)`——执行链，先过同一闸门再被同一原因阻断 | 改走只换源不执行 |
| W8 | 「点击切换数据源只有一个数据源？当前可是配置了很多」 | 当时判成 `.filter(CAN_CONNECT_TO)` 可连接性过滤（**后被 W11 推翻**） | 改调 `getSwitchDataSourceRunners` → 引入 `runners=0` 回归（W8-hotfix `8b4f7568` 撤回） |

## W11（`c2e77950`）→ 已 revert（`42c08c8c`）

试图用 **`createDataSourceConsolesRunner(DbDataSource, Consumer)`** 为每个在籍源建 Runner 以绕开
forced 短路。**失败两点**：①反射按 `(Object, Consumer)` 传 `LocalDataSource` ⇒ `argument type
mismatch`（平台把 `LocalDataSource` 配置层与 `DbDataSource` PSI 层分两类）；②即便参数对，它产出的是
**「New Query Console / Default Query Console / console_1」这类新建控制台项**，根本不是选源项（用户图二
实锤）。教训：**反射造平台对象前必须先确认它构造出的语义，不只是方法存在**。

## W12（`5c28943d`，终态）+ 三条平台事实

用户三张图定案：平台原生工具栏自带 `Data Sources` 菜单（`Detach Data Source` + 全部源）。反编译三步
实证**均无解** ⇒ 插件彻底不做换源 UI：

1. `DatabaseRunners.getAttachDataSourceRunners` 与 `getSwitchDataSourceRunners` **两条入口都先查**
   `DbImplUtil.getForcedDataSourceForExec(psiFile)`，命中即 `Collections.singletonList` **短路**
   （偏移 34/63）；`getSwitchDataSourceRunners` 真实签名是**四参**（多 `DatabaseSession`）。
2. 该 forced 关联经 `VirtualFileDataSourceProvider`（EP）实现，平台**只公开** `findDataSource` /
   `getDataSource`，**无 set/clear**；`getForcedDataSource` 私有 ⇒ 关联不可清除。
3. `ChooseDataSourceAction.actionPerformed` 内部走 `RunQueryInConsoleIntentionAction$Manager.chooseAndRunRunners`
   （**平台内部类**）⇒ 硬调不可行。
4. 枚举过滤链（另一条事实）：`getAppropriateDataSourcesForExec` → `getSqlLanguageDialectForExec`
   （null 即返回空）→ `DbSqlUtilCore.getAppropriateDataSources` → **`.filter(CAN_CONNECT_TO)`**；
   `getAllDataSourcesForExec` 不过滤但**不产 Runner**。

**W12 改动**：删 `switchDataSourceViaNativeChooser` 与 `BIND_STATE_SWITCH_NAME`（常量/import 一并删），
指示行回纯文本三级文案（`bindStateText`）不渲染任何控件；方言闸红条与「选错源」横幅的换源动作改为
`openDatabaseToolWindow(proj)`（把用户送到能换源的地方）。换源由用户走右侧控制台头部的原生
`Data Sources` 菜单（`Detach Data Source` 正是解除 forced 关联的原生入口）。

## 读数

- T-ExplainFailUx：定向 5 类 46/0/0/0，反向四条各恰 1 红。
- W5~W7：44/0/0/0；W8~W10：**47/0/0/0**（反向三条各恰 1 红）；W12：46/0/0/0（反向把自研入口
  加回恰 1 红）。
- **最终验收（2026-10-24 自建自验，243 沙盒）**：定向 **7 类 75/0/0/0**（Guard 15 + Classifier 12
  + Fallback 6 + Timeout 3 + PreviewSideQuery 10 + ExplainPanelChannel 12 + ExecutionDialectGate 17）
  + 提交态 `compileKotlin`/`compileTestKotlin` 双通过；javap 核在役 jar 为 W12（自研函数已消失）；
  截图确认指示行只剩纯文本、无自研控件；点「执行计划」日志出现
  `[M37.2] explainSql: dialect=MySQL`（按钮接线正常）；日志证据（用户此前操作段）原生弹层
  `runners 6~10`，不再被 forced 短路成 1。

## 验收边界（诚实，务必保留）

- **GUI 完整闭环未验完**：沙盒里 `#id` 参数输入框的合成输入**持续不生效**（坐标点击/Tab/剪贴板粘贴
  三法皆败，输入框始终停在占位符），因此「填参数 → 执行计划 → 渲染计划」这条没在 GUI 里跑通。
  这是**探针能力限制，不是产品缺陷**。
- **换源仍未由插件实现**（技术结论非妥协）：三条平台事实均无解（见上）。若要求「插件里一键列全部源」，
  须另立任务评估调用平台私有 API 的风险。
- P2 仍未做：last-used 变化后预览文本**不自动重渲染**（要动生命周期编排）。

## 教训（本轮新增，通用）

1. **反向探针写成注释留在原位 ⇒ 源码形守卫照样绿（假绿）**：必须**真删符号**才见红（删到生产编译失败
   也是有效读数）。见 [[pitfall-fake-green-test-assertions]] ㊵。
2. **跨调用用 python 写文件不落盘**：还原探针时用 bash 内 python 写文件，跨调用丢失——**改文件一律用
   Edit/Write 工具**，改完 grep 计数复核。
3. **守卫断言必须跟着重构同步**：删掉符号后旧守卫仍断言旧符号 ⇒ 恒红；改守卫前先 grep 目标符号的
   **当前归属文件**再写。
4. **换平台反射入口 = 先核完整签名 + 失败必须回退旧入口 + 不得返回空**（W8-hotfix 代价）：只确认方法名
   存在就换 → `NoSuchMethodException` 被 `runCatching` 吞成空集合 → 下游归因成**另一个原因**
   （方言不匹配），用户看到完全错误的红条。
5. **平台「选中即关联」类隐式状态 ⇒ 第二次行为与第一次不同**：用户说「有时/第二次/之前还能」时，
   必须索取或复现**连续多轮**操作日志，单次快照不足以下结论（W11 的误判就是只看了单次）。
6. **反射造平台对象要先确认它的语义**：`createDataSourceConsolesRunner` 名字像「建源 Runner」，
   实际产出「新建控制台」菜单项（W11）。
7. **python 脚本按行号切片删测试代码极易切坏结构**：本轮删重复 @Test 块时把类闭合括号与后续块一起
   切掉（`Conflicting overloads` + `Expecting a top level declaration`）。改测试文件用 Edit 精确替换，
   或先用 `git checkout HEAD -- <file>` 回滚重做。

## 「Unknown column 是不是选错源」的判据（20261024 用户问，待决策改进）

用户问：「数据源选错了这个报错信息是正常情况吗」——附
`[42S22][1054] Unknown column 'first_name' in 'field list'`
`[当前库=mp_demo，数据源=mp_demo@localhost，数据源默认库=mp_demo]`。

**结论：库内正常响应，但不是「源选错」。** 三段拼接：①`[42S22][1054]` + 驱动原文由 MySQL 返回（能跑到「列找不到」说明连接/权限/库都对）；②方括号诊断行由插件 `ExecConsoleContext.describeContext` 拼（`[当前库=…，数据源=…，数据源默认库=…]`）——**这行本身就是「源没选错」的证据**，三处读数全为 `mp_demo`。

真实错配是「SQL 属于 jpa_demo、源连在 mp_demo」：`first_name` 是 demo 仓 `jpa-hibernate-demo` 的 `UserEntity` 列（`jpa_demo` 库），而 `mp_demo` 的用户表是 `user`、列名 `username`。

**为什么插件没归到 WRONG_TARGET**（这是设计取舍，不是缺陷）：签名表只认**表级**缺失（`doesn't exist`/`does not exist`/`unknown table`/`invalid object name`/`ora-00942`/`no such table`），`Unknown column` 被**明确排除**并有注释与测试钉死——理由：若表在而列名写错，换源解决不了。但本例撞上**同名表、不同列集**的形态：`user` 表两边都存在、只有列不同 ⇒「列级」与「源级」判据同时成立。

**已向用户提出、待其拍板的改进**：当能证实「该列属于另一条在籍源的同名表」时，把 `Unknown column` **升格**为 WRONG_TARGET 并给就地换源引导；其余情况维持 OTHER 只显示原文。判据不能只按文本猜——盲目把所有 `Unknown column` 归成换源，会让真拼错列名的用户被引导去换源，更迷惑。

相关：[[project-dialect-infer-pollution]]、[[project-explain-pwd-prompt]]、
[[pitfall-sandbox-ide-jvm-lock-and-launcher-shell]]、[[project-pending-plans]]、
[[project-explain-console-first]]、
[[feedback-ui-match-existing]]（用户偏好：不自创形态，优先平台原生）。
