# T-PreviewDmlResult：预览执行写语句的「完成态」被判成超时——开发计划

- 立项：2026-11-10
- 范围：`execute/DataSourceSelector.kt`、`execute/DataSourceSelectorResults.kt`、bundle 双语各一条、测试一条
- 触发：磐维 A 模式真机预览执行 `INSERT INTO sys_config …`（香港机 155.103.159.54:26201）

## 一、现象

用户在 SQL 日志面板点开一条 INSERT 的预览，点「执行 SQL」：

- 控制台 Output 明确显示 `[2026-11-10 19:05:04] Connected` / `1 row affected in 60 ms`；
- 插件面板的结果区始终空白，最后什么提示也没有（用户原话「执行结果显示好像没有，只有原生的执行结果」）。

## 二、证据链

同一次执行的 idea.log（沙盒 IU-2024.3.7.1，插件构建 `2026.11.11-beta.2.jar` @ 18:45，HEAD `70048a12`）：

```
19:05:03.820  [M36] result watch started (pre-existing-names=[], ids=[])
19:05:04.406  Driver: Vastbase JDBC Driver 2.0.0 / Detected: POSTGRES 9.2.4
19:05:05.024  [T-CEC] console tail: Connected⏎panweidb> INSERT⏎ INTO sys_config …
19:05:11.822  [M36] result/layout OK within 8s (newTab=false, namespaceOk=true) - no hint
19:05:17.045  SqlPreviewPanel - panel removeNotify: embedded=0        ← 用户此时关闭面板
19:05:19.870  [M36] result tab not appeared after 40 attempts (dumb=false)
19:05:19.870  [M36] result tab not appeared, no console error (residue=false, completed=false) - hint via 40-tick window
```

数据面旁证：`sys_config_id_seq.last_value = 5`（序列不随回滚推进），而表里没有对应行——语句确实执行过，
行是会话事务回滚掉的（会话级行为，与本缺陷无关，属 DBN Playground 语义）。

## 三、根因

1. **DBN 不为 Insert/Update/Delete/DDL 建结果页签**，影响行数只写进控制台 Output 视图——这是产品语义，不是缺陷。
2. 插件的「完成态」判据 `hasConsoleCompletionEvidence()` 只读
   `console.getConsoleView().getHistoryViewer().getDocument()`（`DataSourceSelector.kt:824 newConsoleText`）。
   真机上该文档只有 `Connected` 与 SQL 回显（见 19:05:05 的 T-CEC 取证行），**不含** `1 row affected in 60 ms`，
   于是 `completed=false`。
3. `completed=false` 时 40 拍分支落到 `else -> execute.timeout.hint`——把「已成功执行」报成「等待结果超时」。
4. 40 拍窗口约 16s，判定发生在 19:05:19；用户 19:05:17 已关面板，提示投给了已 removeNotify 的面板（无人接收）。

## 四、方案

1. 新增纯判据 `isWriteStatement(sqlText)`（`DataSourceSelector.kt`，基于 `SqlTypeClassifier.getFirstKeyword`
   的 DML/DDL 首关键字子集）——「这类语句执行完本来就不会有结果页签」。
2. **8s 监控支**（`DataSourceSelectorResults.kt`）：无新页签、namespace 已就绪、语句是写语句时，立即给**中性提示**
   `execute.writeStatementSubmitted`（「已提交执行（更新/DDL 语句不产生结果页签），影响行数与耗时见下方控制台 Output」）。
   措辞刻意**不宣称"已完成"**：长事务仍在跑时不误导。
3. **40 拍支**：`completed` 追加 `|| isWriteStatement(sqlText)`，即使 8s 支被抑制，也不会再把写语句的成功说成超时。
4. 与既有 `executionHintReported` 去重共用，同一次执行只报一次；8s 支命中后 40 拍支不再重复。

不动的部分：不做"读 Output 文本"的平台反射（该视图在 2024.3 上无稳定读取面，且真机取证说明回显不在
history 文档）；`Completed` 词表、错误提取、基线截断语义、结果页签判定逻辑一律不变。

## 五、验证

- 定向：`PreviewWriteStatementTest`（纯逻辑，5 例）覆盖 DML/DDL 为真、查询/工具语句为假、注释与小写、
  空值、多语句按首条判定。
- 回归口径：`ConsoleErrorExtractTest`（newConsoleText 片段物化契约）不得因本次改动变化——本次未触碰该函数。
- 真机复验（用户侧）：同一条 INSERT 预览 → 8s 内应出现灰色中性提示；控制台 Output 仍显示 `1 row affected`；
  面板结果区依旧空白（DBN 语义，预期）。

## 六、风险与回滚

- 风险一：把挂死的 DML 在 8s 时报成「已提交执行」——措辞不含"完成"，且不遮蔽后续错误/超时通道
  （`executionHintReported` 只抑制重复提示，真实错误分支独立判断）。
- 风险二：多语句脚本按首条判定；首条是 SELECT 时仍走原逻辑（会建页签），不会被误判为写语句通道。
- 回滚：三处改动各自独立，去 8s 支 + 40 拍支的 `||` 即回到现状。

## 七、实施记录

- 实施提交：`67a574ab`（fix(preview): 预览执行写语句成功被误报「等待结果超时」），CHANGELOG `[Unreleased]` 已随本笔回填。
- `execute/DataSourceSelector.kt`：新增 `DataSourceSelector.isWriteStatement(sqlText)`（基于 `SqlTypeClassifier.getFirstKeyword`
  的 DML/DDL 子集）+ 私有词表 `WRITE_STATEMENT_KEYWORDS`。纯函数、无平台依赖。
- `execute/DataSourceSelectorResults.kt`：8s 监控的 `if/else` 改 `when`，新增「写语句 + 连接已就绪 + 无新页签」支，
  经 `ApplicationManager.invokeLater` 投递 `execute.writeStatementSubmitted`（与 40 拍支共用 `executionHintReported` 去重）；
  40 拍支 `completed` 追加 `|| isWriteStatement(sqlText)`。
- bundle：`execute.writeStatementSubmitted` 中英各一条，措辞**不含"完成"**。
- 测试：`PreviewWriteStatementTest`（5 例）正向 `5/0/0/0`；反向验证＝把 `INSERT` 从词表移除后 `2 failed`（`5 tests completed, 2 failed`），
  恢复后 `BUILD SUCCESSFUL`；同批复跑 `ConsoleErrorExtractTest` 绿（本次未触碰 `newConsoleText`）。
- 未纳入本券：
  1. 捕获序号重号——`SqlLogEntryBuffer.kt:893 resetEntrySequence` KDoc 明示「不清列表、旧条目保留原行号，新一轮从 #1 起」，
     属**现行设计**；真机面板出现两个 `#1` 是该设计的可见后果，是否改（全局单调 or 换轮次前缀）待用户定案。
     **【销案 2026-11-11：用户定案「是我启动了两回」——两个 `#1` 即两回启动各自从 #1 起的预期表现，与设计完全吻合，非缺陷不改，撤销该待定项】**。
  2. MCP「日志还原」工具不认 `{stmt-N} Statement:`——核实为 **IDE 2026.3 里安装的是正式版 `2026.11.10.jar`**（beta.1 起才支持该形态），
     沙盒跑的是 `2026.11.11-beta.2.jar`；不是当前代码缺口，不立项。
- 真机复验待用户：沙盒需重跑 `runIde`（本改动只在重新构建的插件里生效）。判据见 §五。