# T-ExecSilentNoop：预览面板首次执行 SQL 静默不执行、误报凭据提示（根因定位与治理）开发计划

> **创建日期**：2026-09-10
> **分支**：master（HEAD `8afe07a`；工作树另有 T-PreviewTopOverlap / T-VStack 会话在途改动——`SqlPreviewPanel.kt`、bundle 正在被改，本计划 F3 若要动面板元信息行须排在其后）
> **优先级**：P1（主链路可用性缺陷：点「执行 SQL」什么都不发生，且提示把用户引向错误方向）
> **预估工时**：F1+F2 共 5~6h（埋点 + 执行回执 + 提示分流，必做，第一笔提交）；F3 数据源绑定 3~4h、F4 执行范围 2~3h（第二笔，视 F1 结论取舍）
> **来源**：用户真机反馈（2026-09-10）——点「执行 SQL」第一次弹出「执行超时，可能是密码过期或驱动未下载……」；关闭面板重新打开预览后第二次执行，密码框正常弹出、查询正常返回。沙盒日志 `.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log` 09:10:43~09:11:37 完整记录两次执行，本文全部结论出自该日志与 DBN 字节码，无推测成分的部分都标了证据行
> **实施状态**：F1a（探针 + 兜底条件前置）+ F2（提示分流去重）已实施，工作树未提交；F3/F4 留待沙盒实测静默出口归属后再定（见 §五 开放问题 1）。BUILD_STAMP 已推进为 `M36.8-exec-silent-noop`。

---

## 一、现象与日志实证

### 1.1 现象

预览窗口点「执行 SQL」，第一次**没有任何可见反应**：无结果 Tab、无错误文字、无密码框，8 秒后面板红字报凭据/驱动提示（`:165` 的 `execute.credential.hint`）。关掉弹窗重开预览再点执行，一切正常：密码框弹出、SQL 返回结果。

用户拿到的提示文本与日志两处上报完全对应（`DataSourceSelector.kt:2295` 的 8 秒监控、`:2477` 的 40 拍窗口），也就是说我们**把一次「查询根本没提交」的失败，归因成了「连接超时/凭据问题」**。

### 1.2 两次执行的日志逐行对照

| 时刻 | 第一次（失败） | 第二次（成功） |
|---|---|---|
| 准备 | 09:10:43,943 写 exec 文件 → 09:10:43,946 `enter dumb mode`（Push all on SqlDialectPusher 排队）<br>09:10:44,011 `runners=4`（**dumb 中**，push 尚未执行）<br>09:10:44,011 `silent reuse last runner: mpj_demo@localhost`<br>09:10:44,634 `refreshConsoleModel` 装入 1 语句模型<br>09:10:51,267 又一次 dialect push 的 dumb 进出 | 09:11:26/27 重开预览<br>09:11:27,067 `shared console invalid (ds=mpj_demo@localhost), drop and rebuild`<br>09:11:27,069 `exec file updated via VFS` + **`exec psi cache hit`**<br>09:11:27,069 `runners=1` → `single runner, run directly`（jpa_demo） |
| 提交 | 09:10:53,722 `executeOnConsole begin`<br>09:10:53,730 `executing model: stmts=count=1`<br>09:10:53,734 `findScriptModel OK, doRunQueryInConsole`<br>09:10:53,736 `native query SUBMITTED` | 09:11:29,017 `executeOnConsole begin`<br>09:11:29,024 `findScriptModel OK`<br>09:11:29,039 `native query SUBMITTED` |
| 连接 | **无任何 `DatabaseConnectionEstablisher - Connecting to:`** | 09:11:29,041 `Connecting to: jdbc:mysql://localhost:3306/jpa_demo`<br>09:11:29,042 `Auth provider: user-pass`（→ 密码框） |
| 结果 | retry#1..#40 全部 `contents=[Output], select=null`<br>09:11:09,777 `result tab not appeared after 40 attempts` | 09:11:37,048 `result/layout OK within 8s (newTab=true, namespaceOk=true)` |
| 提示 | 09:11:01,738（8s 监控）+ 09:11:09,777（40 拍窗口）各报一次凭据提示 | 无 |

### 1.3 关键判据

两次执行走的是**同一段代码、同一条 SQL（len=186）、结构完全一致的模型**（探针 09:10:44,661 与 09:11:27,511 两次都是 `lang=MySQL`、`stmtsDetail=[SIt((0,184))]`、`consoleModelId=clientModelId`、`sameModel=true`、`subModelStmts=1`）。唯一差别有两点：

1. **数据源**：第一次静默复用了 last-used 的 `mpj_demo`，第二次是表解析正确后的 `jpa_demo`；
2. **光标位置**：第一次执行前探针读到 caret=114（落在行尾换行符上），第二次 caret=186（文档末尾）。

而第一次全程**没有 `Connecting to:` 行**——`DatabaseConnectionEstablisher` 那条日志发生在凭据弹框之前（第二次可证：09:11:29,041 连接、09:11:29,042 auth，之后才轮到用户输入密码）。所以第一次的查询**从未走到连接阶段**，不是"连接超时"、不是"密码过期"、也不是"驱动没下载"。这与 `DataSourceSelector.kt:70-74` 字段注释里记过的老症状是同一族：「关闭上一个预览弹窗后下一个会话的连接永不发起，无任何日志/报错，结果区恒 0 rows of 0+」。

---

## 二、根因定位

### 2.1 DBN 侧存在两条「全静默出口」（javap 实证）

反编译目标：`C:\Users\USER\.gradle\caches\9.7.0\transforms\...\ideaIU-2024.3.7.1-win\plugins\DatabaseTools\lib\database-plugin.jar`（`JdbcConsoleProvider` 不在 `jdbc-console.jar` 里，在 `database-plugin.jar`）。

**出口一：`chooseStatements` 空选集分支。** `JdbcConsoleProvider.doRunQueryInConsole(console, info)` 的字节码本体只有一句：

```
chooseStatements(info, DatabaseBundle.message("hint.text.nothing.to.run"), true, consumer = (info, console) -> ...)
```

在该方法里，语句集先按 `EvaluationHelper.isStatement` 过滤；过滤后为空集时走"没有可执行内容"分支——如果 `execOutside == 1`，它**既不消费 model 也不提示，直接 return**（`Application.invokeLater(提示)` 那一步被跳过）。表现就是：调用返回、无异常、无输出、无连接。

**出口二：`beforeExecuteQueries` 返回 false。** 消费路径（`lambda$doRunQueryInConsole$15`）先调 `console.beforeExecuteQueries(model)`，为 false 则整段跳过：

```java
// com.intellij.database.run.ParametersHolder
public static boolean beforeExecuteQueries(vf, storage, project, model) {
    commitAllDocuments();
    if (model.statements().isEmpty()) return false;   // ← 静默
    ...
}
// com.intellij.database.console.JdbcConsole
public boolean beforeExecuteQueries(model) {
    if (!isValid()) return false;                      // ← 静默（isValid = super.isValid() && isTargetValid()）
    return ParametersHolder.beforeExecuteQueries(myVirtualFile, storage, getProject(), model);
}
// isTargetValid() = DataSourceUtil.isDataSourceValid(DbPsiFacade.getInstance(project), getDataSource())
```

两条出口的共同点是：**失败不留痕**——不写 console history、不产生结果 Tab、不抛异常。我们下游的判定（无新 Tab + 无 namespace）拿到的就是"什么都没有"。

**执行用的 model 不是我们构建的那个。** `findScriptModelNoInject` 的字节码是：`getValidConsole(project, vf)?.getScriptModel()`（控制台缓存模型；控制台无效才退 `SqlPsiFacade.createScriptModel(psiFile)`），再过 `ScriptModelUtil.adjustModelForSelection(model, psiDoc, getSelectionForConsole(editor, execOption), execOption)`。我们的 execOption 取自 `DatabaseSettings.getDefaultExecOption()`，探针实测 `execSelection=2` 且无选区，于是 `adjustModelForSelection` 走 `selection.length == 0` 分支：

```java
return model.subModel(new ScriptModel.PositionRange(offset));   // offset 由光标行/语句推导
```

即 **DBN 实际执行的语句范围完全由光标位置和那个缓存模型的解析状态决定**，与我们在 `DataSourceSelector.kt:2071-2105` 精心重建并校验过的 `freshModel`（日志里 `stmts=count=1` 的那个）无关。探针的 `statementsRange` 在两次执行里分别是 `(0,0)`/`(114,114)`——零长度的 `PositionRange`，说明这条光标派生路径本身就不稳。

### 2.2 静默出口被踩中的触发链（日志时序）

第一次预览的准备时机落在「exec 文件方言/PSI 尚未稳定」的窗口里：

- 09:10:43,943 写盘并 `mapDialectBeforePsi`，09:10:43,946 进 dumb（`Push all on SqlDialectPusher` 排队）；
- 09:10:44,011 枚举 runners 时 push **还没跑**（dumb task 09:10:44,294 才执行、09:10:44,515 退出）。runner 候选集来自 `DatabaseRunners.getDataSourceRunners` → `DbImplUtil.getAppropriateDataSourcesForExec(psiFile)`（javap：`lambda$getDataSourceRunners$12` 只做"排除当前会话数据源"，候选完全由 PSI 推导），PSI 未 push 就解析不出 `legacy_item`（该表只存在于 jpa-hibernate-demo 的 schema，演示库另外三个数据源都没有）→ 候选**退化成全部 4 个数据源**；
- 于是 `silent reuse last runner` 静默把控制台绑到了与这条 SQL 无关的 `mpj_demo@localhost`；
- 09:10:51,267 又来一次 dialect push 的 dumb 进出，缓存模型/PSI 再动摇一次；
- 09:10:53,722 用户点执行 → `findScriptModel OK` → `doRunQueryInConsole` → 撞上静默出口。

第二次预览走了 `exec psi cache hit` 快路径，PSI 已 push 完成，表解析成功 → `runners=1`（jpa_demo）→ 一次成功。

**需要说清的边界**：现有日志能证明"没提交"和"数据源绑错 + 准备窗口不稳"，但**分不出**具体撞的是哪一条静默出口（`isValid=false` / `statements` 为空 / `isStatement` 过滤后空集）。所以本计划第一件事是埋点把这一层照出来，而不是先猜着改。

### 2.3 我们这一侧的三处放大

1. **提交即认定成功**。`submitted` 是我们自己赋的标记（`DataSourceSelector.kt:2202-2214`），DBN 静默拒绝时既无回执校验、也无重试、也不启用兜底；现成的 fallback 路径（`execute.invoke(console, editor, freshModel, execOption)`）只有 `nativeRun == false` 才走，恰好在这种情况下不会被触发。
2. **8 秒监控归因错误**。`:2284-2302` 的判定只有"有没有新 Tab / namespace 是否非空"两个信号，两者都不知"本次是否产生过执行痕迹"，直接落到凭据/驱动文案；而且 8 秒监控并不终止 40 拍窗口，同一次执行会走两次上报（09:11:01,738 与 09:11:09,777），按代码路径两处取的是同一个 key。
3. **数据源绑定不可见**。静默复用 last-used 是 M36.5 的既定设计（`:271-282`），但面板不显示"这次会用哪个数据源"，runners 退化时还会命中无关数据源——用户既看不到、也没处改，只能靠"关掉重开"碰运气。

---

## 三、方案设计

### 3.1 F1：执行回执与真实原因上报（必做，核心）

**埋点（新增一批 `[M36][EXEC]` 日志，只在准备/提交/提交后各打一次，不进热路径）**：反射读 `Info.model.statements()` 数量与 `getTextRange()` 类名、`ScriptModelUtil.getSelectionForConsole(editor, execOption)` 的选区、光标偏移、`console.isValid()`、`isTargetValid()`（私有方法，`setAccessible(true)`）、`console.getDataSource()` 名、提交前后 console history 长度。目标是一次沙盒跑动就把 2.2 里分不出的三条出口区分开。

**提交后回执判定（新增）**：提交后约 1.5s 起拍，按探针 + 现场痕迹三分类：

| 现场 | 判定 | 面板文案 |
|---|---|---|
| 有连接/执行痕迹（namespace 非空、history 增长） | 已提交，正常等待 | 不提（保留超时兜底） |
| 无痕迹且探针显示 DBN 侧 model 语句为 0 或选区为空 | **未提交**：光标没落在语句上 / 准备窗口未稳 | `execute.notSubmitted.hint`（附当前数据源名 + 光标提示） |
| 无痕迹但探针一切正常 | DBN 内部拒绝（isValid/权限/参数） | 可操作指引 + 数据源名，仍不作凭据归因 |

**兜底修复**：当探针显示 DBN 侧 model 不可执行、而 `freshModel` 语句数 > 0 时，不启用 native 链，直接走 `console.executeQueries(editor, freshModel, execOption)`——把"静默不执行"变成"能执行"。这条路径已在代码里（`:2202-2205`），只是触发条件需要前置到提交前。

### 3.2 F2：提示语分流与去重

- bundle 新增 `execute.notSubmitted.hint`、`execute.timeout.hint`；`execute.credential.hint` 降级为"确实观测到连接阶段痕迹"时才用（当前它同时兼任三种失败的兜底，这才是用户被误导的直接原因）。
- 同一次执行只报一次：8 秒监控与 40 拍窗口按执行代际 + 已报标记去重。

### 3.3 F3：数据源绑定的可见与稳定

- 面板显示本次执行目标数据源名（顶部元信息行或执行按钮 tooltip）。**与 T-PreviewTopOverlap / T-VStack 在途的面板改动正面相撞，必须排在其后**。
- 准备时机：runners 枚举与 last-used 复用若落在 dumb 中，不定音——在 `DumbService.runWhenSmart` 之后再枚举（`executeOnConsole` 已用 runWhenSmart，但准备链的枚举在它之外，`:267-282` 是同步执行）。
- 复用校验：last-used 数据源若解析不出 SQL 引用的表，不静默绑定，退回选择弹窗或优先包含该表的 runner。

### 3.4 F4：执行范围与光标（视 F1 结论取舍）

执行范围交给光标是 2.1 第三条的直接后果，也是 M36.5.24 那轮「▶ 灰」的同源问题面。候选做法：执行前把光标落到第一条语句内；或探针发现"光标派生的 model 不可执行"时按整篇（execSelection=3 语义）重试一次。取舍等 F1 沙盒结论——若静默出口是 `statements` 为空，这条就是主修；若出口在 `isValid`，则 F1 的兜底才是主修。

### 3.5 F5：测试与验证

- 单测（不依赖 DBN 的纯逻辑）：runners/last-used 复用校验、提示分流判定（探针结果 → 结论）。
- 沙盒（`docs/规范/测试沙盒验证清单.md` 增补一节）：① dumb 中首次预览即点执行；② 同一 SQL 连续执行两次；③ 光标分别在语句首、行尾、文档末尾执行；④ 绑定到不含该表的数据源时执行（应报"数据源不含此表"而非凭据提示）；⑤ 关弹窗重开后执行。
- 验收判据：任何"什么都没发生"的场景，日志必须有一条 `[M36][EXEC]` 的明确归因，面板给出的必须是有依据、可操作的真实原因。

---

## 四、实施顺序

| 阶段 | 内容 | 产出 |
|---|---|---|
| F1a | 埋点 + 提交后回执判定 + 兜底条件前置 | 一笔提交，沙盒复现"dumb 中首次预览执行"，读出静默出口归属 |
| F2 | 提示分流与去重 + bundle 文案（中英同步） | 第二笔 |
| F3/F4 | 数据源可见性、枚举时机、执行范围 | 第三笔，排 T-PreviewTopOverlap 之后 |
| 回归 | 全量测试 + runIde 五场景真机复验 | 交付 |

### 4.1 实施记录（2026-09-10，工作树未提交）

F1a + F2 已落地，改动面：

| 文件 | 改动 |
|---|---|
| `DataSourceSelector.kt` | ① `execSilentProbe()` 新增 `[M36][EXEC]` 提交前探针（console.isValid / freshModel 语句数 / 目标数据源名，纯反射 runCatching 绝不阻断）；② freshModel 回落（vf PSI 解析 0 语句）记 `freshModelDemoted` 后**强制降级 native→`executeQueries(freshModel)`**（把"静默不执行"变"明确执行/明确报错"，兜底条件前置到提交前）；③ 新增 `@Volatile executionHintReported` 去重旗标，`executeOnConsole` 开头重置；④ `afterSubmitted` 8 秒监控与 `startResultTabRetry` 40 拍窗口共用该旗标——**同一次执行只报一次**，且 demoted 时给 `execute.notSubmitted.hint`、非 demoted 无错误痕迹给 `execute.timeout.hint`、dumb 给 `execute.indexing.hint`，`execute.credential.hint` 不再兼任兜底；⑤ 数据源名经 `dsName` 参数贯穿 `executeOnConsole → afterSubmitted → startResultTabRetry`；⑥ `BUILD_STAMP` 推进为 `M36.8-exec-silent-noop` |
| `MpSqlVisualizerBundle_(zh).properties` | 新增 `execute.notSubmitted.hint`（未提交，携带数据源名占位 {0}）与 `execute.timeout.hint`（真实超时），中英同步 |
| `ExecSilentNoopHintTest.kt`（新增） | 5 用例纯逻辑测试：同次执行去重、demoted→notSubmitted、dumb→indexing、无痕迹→timeout、真实错误优先且占用去重旗标 |

回归：全量 `./gradlew test`（隔离沙盒 `sandbox-execsilentnoop`，协调表登记后即还原）121 套件 **1467 用例 0 失败 0 错误**；新增测试 5 用例全绿。runIde 五场景真机复验仍留人工（`DatabaseConnectionEstablisher - Connecting to:` 锚点 + `[M36][EXEC]` 探针行）。

**关键取舍**：静默出口有三条（statements 空 / isValid 假 / isStatement 过滤后空集），日志无法区分，故兜底只在 `freshModelDemoted`（准备窗口不稳的直接信号）时强制走 `executeQueries`；非 demoted 仍走 native 链，静默出口交给提交后的回执判定可见化，留待 runIde 沙盒复现后精修（§五 1/4）。

**关联计划注记（2026-10-09，T-FirstExecResultInvisible 分支 t-firstexec 已实施）**：用户报障「预览首次点执行结果区空白、二击正常」与本计划症状同族——其调查把「双准备链竞态 → 执行链提交被准备链代际递增取消」定为主嫌（H1），属本计划静默出口的一个**触发面**（提交被取消 → 8s 无 Tab → 误报凭据/超时提示）。该触发面已由其 F1（准备链单飞 + 执行意图排队）结构性消除；本计划 §3.3 F3（数据源可见性）/ §3.4 F4（执行范围）不被覆盖、仍开放；其 F0 判定表 H3 行（`native query SUBMITTED` + 40 拍无 Tab）仍指向本计划 §五 F3/F4 续作。复验本计划场景时顺带走查其 §八五场景清单（两计划共用 §3.5 形态）。

## 五、风险与开放问题

1. **兜底调用会不会丢 DBN 语义**：native 链在 `lambda$15` 里除了 `executeQueries` 还做了 `saveAllDocuments`、`dbSrcSearchPath`、`ensureSearchPath`、`beforeExecuteQueries`。我们直接调 `executeQueries` 会少做后两项，需要评估或按需补齐（searchPath 缺失会让未限定表名解析不到）。
2. **私有 API 依赖**：`isValid()/isTargetValid()` 需反射，跨版本按平台镜像铁律做方法名超集与容错（失败即降级为"未知"，不可因探针失败阻断执行）。
3. **计划冲突**：F3 动面板顶部，与 T-PreviewTopOverlap（面板顶部重构，工作树在途）、T-VStack（工具栏按钮，立项未实施）撞同一处，串行执行。
4. **相关性尚未受控验证**："dumb 中枚举 runners → 4 个候选 → 静默复用错误数据源"目前是日志时序上的强关联（4 → 1、有/无 `exec psi cache hit`），不是受控实验；F1a 沙盒轮要显式构造"dumb 中首次预览"再确认一次。
5. **老症状同族**：`:70-74` 记录的"复用共享控制台前，下一会话连接永不发起"是同一族问题的早期形态（当时以共享控制台治了症状）。F1 的回执判定若生效，这类场景应一并变成可见错误——修完需回看该注释对应的验证项。
