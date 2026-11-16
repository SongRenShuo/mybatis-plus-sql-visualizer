# T-SchemaPrewarmDedupe：schema 预热去重窗口——反复打开不再重扫全部数据源

- 立项：2026-11-12（用户令「立项 做」）
- 触发：用户真机反馈「首次预览还是有点慢」，日志显示每次打开都跑 schema 预热，且**每次把全部数据源扫一遍、结果 0 命中**
- 关联：[T-DbSchemaCompletion](../架构设计/子系统-预览面板.md) 的补全上下文预热链

## 一、真机读数（2026-11-12 15:53 / 15:55，日常 IDE 2026.3）

```
15:53:59,466  [T-DbSchemaCompletion] schema 预热: tables=2, resolved=0, list=[custmanager_rel, ct_custmanager_rel_example]
15:53:59,718  [T-DbSchemaCompletion] schema 预热: tables=2, resolved=0          ← 同一次打开里第二遍
15:53:59,718  DatabaseSchemaResolver scan: table=ct_custmanager_rel_example, ds=<15 个数据源逐个>
              … 15/15 个数据源全部 bail
15:55:48,899  再次打开：同样两遍、同样 15/15 bail
```

一次打开 = **2 遍 × 15 个数据源的全遍历**，命中 0，下一次打开原样重来。

## 二、为什么现有负缓存没挡住

`DatabaseSchemaResolver` 已有 `metaCache`（正缓存）与 `negativeCache`（TTL 30s 负命中），但
**bail（模型未加载 / 遍历 0 张表）刻意不记负缓存**——为的是「数据源晚连接、内省完成后能自愈」。
这条语义被 4 处既有断言钉住（`DatabaseSchemaResolverBailTest` / `DatabaseSchemaResolverNegativeCacheTest`
/ `DatabaseSchemaResolverSchemaScopeTest` 两处），T-PS6-08 曾明确判定「不为性能削弱该护栏」。

所以正确做法不是改 bail 语义，而是给**预热这条链**单独的窗口：预热是「顺手补全上下文」的尽力而为
动作，同一张表在短时间内重复尝试没有意义。

## 三、方案

| 编号 | 内容 |
| --- | --- |
| M1 | `prefetchTables` 内加「(project, table) → 上次尝试时刻」窗口（默认 60s，`prewarmWindowMs` 旋钮），窗口内直接跳过，不遍历数据源 |
| M2 | 跳过时打一行读数（`skipped=N`），让「省了多少」可核对 |
| M3 | 窗口判定抽成 internal 纯函数，单测覆盖：窗口内命中、窗口外放行、窗口为 0 等价于关闭 |
| M4 | 不动 `tableMeta` / `negativeCache` / `bailAfterTraversal` 的任何语义，4 处既有断言保持原样 |

## 四、边界（不做）

- 不动「查不了不记账」的 bail 语义（正确性护栏，见上）。
- 不改预热线程模型（本来就是 BGT）。
- 不按「只查绑定数据源」收窄（那需要把目标数据源传进来，属另一项策略调整，等有读数再说）。

## 五、验证

- 单测：`SchemaPrewarmWindowTest`（3 例，纯逻辑）。
- 真机：连续打开同一语句两次，第二次日志应为 `schema 预热: tables=2, resolved=0, skipped=2` 且不再出现
  `DatabaseSchemaResolver scan:` 的 15 条；60s 后放行（可等窗口过期再点一次核对）。
- 反向：把窗口旋钮调 0（等价关闭）→ 扫描恢复，跳过计数归零。

## 六、实施记录

（实施后回填）
