---
name: project-s2-15-ob-panel-closure
description: S2-15 OB 结构化执行计划面板真机完全闭环（2026-11-01）——四坑填平路径、阈值保持 10000 的决策依据、以及同批
  S2-16/S2-4 的落点
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# S2-15 OB 结构化面板真机闭环（2026-11-01 交付）

点「执行计划」后 OB 面板实测呈现（验收册 `docs/验收文档/20261101-1930-S2-15-OB结构化面板真机闭环-验收文档.md`）：

```
EXPLAIN · OceanBase · ob_xc3@25881 · 1684532 ms
[TABLE_FULL_SCAN] s216_big: 全表扫描（OceanBase 计划树）——建议为 WHERE / JOIN 列建索引（~19343 rows）
ID | OPERATOR        | NAME     | EST.ROWS | EST.TIME(us)
0  | TABLE FULL SCAN | s216_big | 19343    | 986
```

## 四个真机坑（每个都不是代码逻辑问题，前三个是环境/探针）

1. **驱动要两份文件**：`config/jdbc-drivers/` 只是目录，`config/options/databaseDrivers.xml` 才是「已安装状态」（driver id → artifact version）。缺后者 ⇒ 远程 JDBC 进程 `ClassNotFoundException`。见 [[pitfall-sandbox-jdbc-driver-needs-installed-state]]。
2. **URL-only 凭据**：密码只写在 URL 里时平台凭据库为空，结构化通道走 `askPassword=true` 弹窗。
3. **工具栏坐标每会话漂移 25~27px**，且探针 `front()` 会把主窗提到模态框之上。见 [[pitfall-sandbox-click-does-not-land-four-causes]]。
4. **真实 OB EXPLAIN 是单列×多行**，解析器按「一个 cell 装整段多行文本」写 ⇒ 真机返回 null。见 [[pitfall-explain-result-shape-real-vs-synthetic]]。

## 阈值决策：ROWS_THRESHOLD 保持 10000

`ExplainIndexAdvisor.ROWS_THRESHOLD = 10_000` 是 MySQL/PG/OB 三方言共用的「小表全扫不噪音」口径。为 3 行演示表调低会让真实小表开始报无意义建议。**正确做法是造大表让全扫真正过阈**——OB 侧建 `s216_big`（20000 行，`ANALYZE TABLE` 后 `EST.ROWS=19343`）。

## 同批落点

- **S2-16**（T-ExecPlanEdtSlowOp）：`prepareConsole` 四个调用点共用 EDT 慢操作；改 `ExecTempFileFactory.createTempPsiFileAsync`。**回投必须 `ModalityState.nonModal()`**（243 字节码：`TransactionGuardImpl.<init>` 只把 nonModal 登记为 write-safe），用 `any()` 会引入 `TransactionGuardImpl … Plugin to blame: SQL Visualizer`。
- **S2-4**：换源重渲染 + Community 执行入口置灰。
- 守卫盲区：既有 `BannedApiScanRuleTest.anyModalityWriteOffenders` 只看得见**直接写 token**，看不见 `invokeLater { callback(…) }` 这种间接写——已补规则 5 + `isWritingAllowed()` 行为断言。

相关：[[pitfall-runners-zero-means-unbound-not-unconnected]]、[[project-pending-plans]]。
