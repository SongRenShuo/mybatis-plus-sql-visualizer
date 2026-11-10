---
name: pitfall-oracle-statement-wrapper-registry-mismatch
description: Oracle 增强捕获只出 select 1 from dual——驱动返回语句包装器，SQL 登记在包装器、execute 在内层物理语句
metadata:
  node_type: memory
  type: project
  originSessionId: sess_aebb55c0-e355-42ee-b0bf-cc61f309b964
---

Oracle thin 驱动对外返回**包装器**（`oracle.jdbc.driver.OracleStatementWrapper` / `OraclePreparedStatementWrapper` / `OracleCallableStatementWrapper`，真机 `ShapeProbe` 实测三种入口各自返回对应包装器）。prepare 面织入钩子把 SQL 登记在**包装器**上，而 `executeQuery()` 的织入点在包内物理语句（`OraclePreparedStatement`/`T4CPreparedStatement`）——登记对象与执行对象错位，查表永远落空，事件按「盲区」丢弃。plain `Statement` 走 `EXEC_PARAM` 直接取首参，所以只剩 `select 1 from dual` 这类连接池校验 SQL 能看见（2026-11-04 用户报障形态）。

修法（2026-11-04 已落 master，计划 `docs/开发计划/20261104-1234-Oracle增强捕获PreparedStatement登记断链-开发计划.md`）：`StatementRegistry.registerWithDelegates` 按字段类型反射解一层包装把内层一并登记（顺带接回参数槽/批串）；`NestGate` 改为只在 `WovenEvents.emit` 真产出后抑制外层；新增 `lastExitToken` 同帧重复出口去重（同一帧「try 内 ATHROW + 处理器重抛」两条出口钩子会让深度多减一次，失败语句重复 3 条）。

**Why:** 「看着支持、实际一条不出」的静默缺口。只补织入名单修不好（包装器是驱动私有实现），而夹具若把包装器也织上，外层兜底会把断链彻底掩盖——反向验证时就是这样差点判绿。

**How to apply:** 新增驱动/方言采集面时，先用真机探针确认「prepare 返回的对象」与「execute 实际执行的对象」是否同一个；回归夹具必须与现场同构（包装器**不织入**）。相关：[[pitfall-vendor-coverage-guard-prefix-level]]、[[pitfall-explain-result-shape-real-vs-synthetic]]。

## 复现资产与验证读数（2026-11-04 完成）

- **真机实例**：测试服务器新建容器 `xc3-oracle-free`（Oracle Free 23，`103.36.220.245:21521/FREEPDB1`，用户 `probe`）；复现别指向共享库（会 drop/create 夹具表）。
- **demo 语料**：demo 仓 `mybatis-plus-sql-visualizer-demo/oracle-demo`（commit `fc0094a`，分支 `t-devfullui-fixtures`）——Spring Boot 2.6.3 + MyBatis-Plus 3.5.7 + ojdbc8，无 web，Hikari `connection-test-query=select 1 from dual`，业务四条全走 `BaseMapper`（insert / selectById / selectList / updateById）。同实例 premain：**改前 3 条**（校验 + drop + create，业务全丢）/ **改后 7 条**（业务 SQL 全出且带绑定参数）。
- **异步发送坑（最易误判）**：agent 的 EVENT 由 sender 线程异步发出，宿主跑完立刻退出会丢掉队列里未发出的帧——首轮只收到 3 条，差点判成「修复无效」。复现宿主必须在末尾留 1~2 秒排空（demo 的 `ReproRunner` 已内置）。
- `dev-support/agent-capture-repro/fake_receiver.py` 要**两个**参数：`<port> <logfile>`；只给端口直接 `IndexError`。
- **驱动版本矩阵**：ojdbc8 12.2.0.1 / 19.3.0.0 / ojdbc11 23.3.0.23.09 的包装器字段名一致（`preparedStatement` / `callableStatement` / `statement`），按**类型**扫描不依赖字段名，三版均 7/7 全出。
- **门禁**：master `64371dd8` 上 `testShards` **8534/0/0/13**（5 片，31m07s；13 skip 为 Docker 未启动等环境项）；agent 模组 247/0/0/1skip；主仓 `log.agent.*` 217/0/0/0。
