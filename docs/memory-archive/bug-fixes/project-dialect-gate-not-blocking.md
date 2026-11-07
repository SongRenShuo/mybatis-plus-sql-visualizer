---
name: project-dialect-gate-not-blocking
description: 用户定案「方言不能影响最终执行」——闸门冲突改提示+放行，已修三处（8619a3af/e7a27700）；runner
  枚举为空的真因是平台侧数据源未 introspection
metadata:
  node_type: memory
  type: project
  originSessionId: session_502360ae808bfb35c69ac231233cbe92
---

# 方言不能拦执行（2026-10-25 用户定案 + 三处修复）

用户原话：「我认为方言不能影响最终的执行，哪怕方言是错的，用户手动选择了数据源，你可以正常提示方言不一致，但是还是要能成功执行的，这个理论正确的话就遵循下」。

## 落地原则

**两边方言都已知** → 冲突只提示、不拦。交给数据库自己报语法错，那是最准确的反馈；插件不该替用户做执行层面的否决。

**有一边方言未知**（`RENDER_UNKNOWN_BLOCK` / `TARGET_UNKNOWN_BLOCK`）→ **仍阻断**。那是「无从判断」而非「判断出冲突」，写操作可能把 PG 语法打到 MySQL 库上。

## 三处提交

`8619a3af`：
1. `DataSourceSelector.allowDialectGate` 里 `MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 从 `false` 改 `true`（红字照旧发，UI 仍显示「切换数据源」入口）
2. `DataSourceSelectorResults` 的 `runners.isEmpty()` 报错文案改报**渲染方言**——原来从目标数据源反推，目标源是上次用的 MySQL 源 → 报「方言（MySQL）」，而用户眼前预览写着 `方言: PostgreSQL（手动指定）`、SQL 也是双引号 PG 语法，自相矛盾。改为优先 `dialectGateRequest.renderedDialectName`

`e7a27700`：
3. `runners` 为空时按**目标方言**重映射 exec 文件再枚举一次（新增 `ExecDialectRouting.remapExecFileDialect`，走 `DialectPreMapping.ensureMapped`），命中即递归回同一分派——控制台/结果表/审计全复用，不新建执行通道

## 反向验证

把放行改回阻断 → 新增用例恰 1 红；恢复后 `execute.*` 全绿（3m12s）。既有 UX 测试断言的是「挂出修复入口」而非「阻断执行」，改放行不破坏它们。

## 没修成的那层：平台侧数据源未 introspection

真机日志 `runners empty → remapped exec file to target dialect, retry runners=0`。查下去发现不是插件问题：

- `dataSources.local.xml` 里 `pg_demo` 的 `database-info` 是 `product="" version="" driver-name="" dbms="POSTGRES"`
- `list_database_schemas` 返回 `schemas: []`；所有 MCP 数据源接口报 `Schema dbx_test.public is not found`
- MCP 重建一条 + 下载 `postgresql-42.7.13` 驱动后仍空

平台 `DatabaseRunners.getAttachDataSourceRunners(psiFile, sql, …)` 按 psiFile 已映射方言筛 runner，数据源没建成方言映射就一条都筛不出。代码里早有注释承认这个现象（`SqlPreviewPanelExecution.kt:1433`「数据源无 Dialects 映射时平台返回空」），EXPLAIN 链路已用 JDBC 直连绕过，执行主链路靠上面第 3 条重映射兜。

**下一步阻塞**：需用户手工在 Database 工具窗双击展开 `pg_demo@114.66.55.245` 让平台建 schema（树节点自绘，坐标/键盘/robot 三路都打不中）。

## 录制影响

`dialect-gate.gif` 原分镜写「阻断态 + 切换数据源/方言设置两出口」，实测走的是「执行失败红条」分支。修完闸门后目标形态应是「提示冲突但执行成功」，等 introspection 通了再定分镜。

相关：[[project-docs-gif-rerecord-1025]]、[[project-dialect-gate-ux]]、[[project-dialect-gate-exit]]
