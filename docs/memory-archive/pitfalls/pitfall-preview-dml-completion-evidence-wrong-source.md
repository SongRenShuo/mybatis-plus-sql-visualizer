---
name: pitfall-preview-dml-completion-evidence-wrong-source
description: 预览面板「执行 SQL」跑 INSERT/UPDATE 时结果区为空、还把成功说成「等待结果超时」——完成态判据只读 console history 文档，DML 的「1 row affected」写在 Output 视图里；附 40 拍窗口晚于用户关面板、以及 Playground 不提交致行不落库两条旁证
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bc05cbeb-0d36-4326-82dc-7ebcbcb95ab8
---

# 预览「执行 SQL」的 DML 完成态判据读错了地方（20261110 香港磐维实测）

**现场形态**：预览面板点「执行 SQL」跑 `INSERT INTO sys_config …`，控制台 Output 明明有 `1 row affected in 60 ms`，但面板结果区是空的，且 16 秒后插件按「等待结果超时」处理——把成功说成超时。

**根因（代码级）**：`DataSourceSelector.kt` 的 `newConsoleText()` 只读
`console.getConsoleView().getHistoryViewer().getDocument()`（控制台 history/编辑器文档）。DML 的影响行数回显写在 **Output 视图**，不进那个文档 ⇒ 该文档里只有 `Connected` + SQL 回显 ⇒
`hasConsoleCompletionEvidence()` 恒 false ⇒ `completed=false` ⇒ 40 拍分支落到 `else -> execute.timeout.hint`，而本该走 `execute.toolStatementDone`（「工具语句/更新语句不产生结果页签，详情见控制台 Output」）。

取证日志（沙盒 IU-2024.3.7.1 / panweidb）：

```
19:05:03.820  result watch started (pre-existing-names=[], ids=[])
19:05:05.024  [T-CEC] console tail: Connected⏎panweidb> INSERT⏎ INTO sys_config …
19:05:11.822  result/layout OK within 8s (newTab=false, namespaceOk=true) - no hint
19:05:17.045  SqlPreviewPanel - panel removeNotify: embedded=0        ← 用户此时关了面板
19:05:19.870  result tab not appeared after 40 attempts (dumb=false)
19:05:19.870  … no console error (residue=false, completed=false) - hint via 40-tick window
```

三条叠加因素，缺一条用户都不会「什么都看不到」：

1. DML 不建结果页签（DBN 语义，属预期），结果区必然空——**空本身不是缺陷**；
2. 完成态判据读不到 Output 里的 `affected`，成功被判成超时；
3. 40 拍（约 16s）才下判定，用户早关了面板（`removeNotify: embedded=0`），提示投给了死面板。

**旁证：预览执行不提交**。走的是 DataGrip 控制台（Playground / 未提交事务），`1 row affected` 后行并不落库。判「到底执行没执行」不能看行在不在，要看**序列推进**——本次 `sys_config_id_seq` 3→5（序列不回滚），而表里始终 3 行种子数据。

**修法三处（待立项）**：①完成态判据补读 Output 视图文本（或直接取控制台执行状态/`updateCount`），词表补 `row affected` / `row(s) affected`；②把「有完成证据」的判定从 40 拍提前到 8s 监控那一档，别让 DML 白等 16 秒；③面板已 `removeNotify` 后不再投递提示。

**Why**：T8 当初是按某个驱动的报文形态验的（完成回显恰好在 history 文档里），换到 panweidb/Vastbase 这条链就失配；「词表对但读取面错」是这类判据的通用失败模式。
**How to apply**：凡是「靠读控制台文本来判执行结果」的判据，先确认那类语句的回显到底落在哪个视图（history 文档 vs Output 视图）；实机验证别只跑 SELECT，DML/工具语句必须各跑一条。

相关：[[reference-demo-live-verification-endpoints]]、[[project-embedded-native-console-scope]]、[[pitfall-single-line-table-edit-prefix-match]]。
