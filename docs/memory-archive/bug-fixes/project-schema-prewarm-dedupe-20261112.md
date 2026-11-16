---
name: project-schema-prewarm-dedupe-20261112
description: T-SchemaPrewarmDedupe（2026-11-12 立项并实施 `20cc719dd`）——预览打开时 schema 预热跑两遍×全部数据源且全部 bail，加 60 秒去重窗口；刻意不动 bailAfterTraversal 的自愈语义（4 处断言钉住）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

# T-SchemaPrewarmDedupe（2026-11-12 用户令「立项 做」）

## 真机读数（日常 IDE 2026.3）

```
15:53:59 [T-DbSchemaCompletion] schema 预热: tables=2, resolved=0
15:53:59 [T-DbSchemaCompletion] schema 预热: tables=2, resolved=0      ← 同一次打开跑两遍
         DatabaseSchemaResolver scan: table=ct_custmanager_rel_example, ds=<15 个数据源逐个> … 15/15 全 bail
15:55:48 再次打开：同样两遍、同样 15/15
```

一次打开 = **2 遍 × 15 个数据源全遍历**、命中 0，下次打开原样重来。

## 为什么不能改「查不了就记账」

`DatabaseSchemaResolver` 已有 `metaCache`（正缓存）与 `negativeCache`（TTL 30s 负命中），但 **bail
（模型未加载 / 遍历 0 张表）刻意不留负缓存**——那是「数据源晚连接、内省完成后能自愈」的正确性护栏，
被 4 处断言钉住（`DatabaseSchemaResolverBailTest` / `…NegativeCacheTest` / `…SchemaScopeTest` 两处），
文件里 T-PS6-08 已明确「不为性能削弱该护栏」。

## 实现（`20cc719dd`）

只给**预热这条尽力链**开窗口：

- `prefetchTables` 记 `(project.locationHash|表名小写) → 上次尝试时刻`；窗口内直接跳过（不遍历）；
- `PREWARM_WINDOW_MS = 60_000`，`@Volatile prewarmWindowMs` 旋钮（`<=0` 等价关闭），容量 512 上限；
- 跳过时打 `[T-DbSchemaCompletion] prewarm window skip: skipped=N resolved=M`；
- 窗口外放行 ⇒ 自愈语义不变；`tableMeta` / `negativeCache` / `bailAfterTraversal` **零改动**；
- 窗口判定抽成 internal 纯函数 `prewarmWindowHitAt(last, now, window)`，配
  `SchemaPrewarmWindowTest` 3 例（窗口内跳过 / 窗口外放行 / 窗口为 0 等价关闭）。

定向绿：`ui.highlight.*` 整包 + `ColumnNameCalibratorTest`（该守卫要求生产取数只许引
`columnNamesCached` 与 `prefetchTables`）。真机复验判据＝第二次打开出现 `prewarm window skip: skipped=2`
且不再有 15 条 `DatabaseSchemaResolver scan:`。

## 首开剩余的固定成本（同轮读数，不再动）

热态 `request→dialog 250~330ms`、`request→console-visible 478~664ms`；**会话里第一次**打开约 1.3s，
是 Swing 首次建弹窗 + 首次建内嵌控制台 + JIT 的一次性成本（装配本身只有 4~5ms）。判定：风险大、收益
一次性，**不动**。

相关：[[project-preview-openperf-m2-e2e-20261112]]、[[pitfall-prefetch-needs-failure-memo-and-coldstart-vs-algorithm]]。
