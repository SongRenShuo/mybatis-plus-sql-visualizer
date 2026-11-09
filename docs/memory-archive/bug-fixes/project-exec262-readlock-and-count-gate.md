---
name: project-exec262-readlock-and-count-gate
description: 20261102 立项 T-Exec262ReadLock 两项缺陷——262 执行链读锁硬抛（P0）+
  paginationCountSql 缺 pageLimit 闸致 selectList 的 last("limit") 误产
  COUNT（全版本），计划册已落 docs/开发计划 并登记索引，待实施
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d832ec94-7fe1-44a9-a63f-e036d87375da
---

# T-Exec262ReadLock：两项缺陷与修法（2026-11-02 立项，待实施）

计划册：`docs/开发计划/20261102-0837-T-Exec262ReadLock-2026.2执行链读锁硬抛与last-limit误触分页COUNT-开发计划.md`（索引已登记）。来源＝用户日常 IDEA 2026.2 两点反馈。

## A（P0，平台级）：262 执行链读锁硬抛

诊断与修法全在 [[pitfall-262-edt-readlock-database-runners]]。要点：两处反射包 `WriteIntentReadAction.compute` + runners 为空按「是否抛异常」分流文案。影响所有 262 用户的「执行 SQL」「切换数据源」，2026.1/2024.3 不受影响，属已发布版本回归，走 CHANGELOG「修复」。

## B（全版本）：`last("limit …")` 被当成真分页，附赠 COUNT

用户判断正确——`selectList` 不是 `selectPage`，不该有 COUNT。链路：

1. `QueryWrapperParser:196` `"last" -> limit = parseLimit(call)`：`.last("limit " + n)` 被解析进 `SelectStatement.limit`，值是形参 → 占位 `LIMIT ?`（**这部分渲染没错，预览要如实反映 LIMIT**）。
2. `WrapperLocator:575-592`：无 Page 实参 → `pageLimit = null`，但 `pageSearchCount = pageArg?.searchCount ?: true` → **恒 true**。
3. `SqlPreviewService.paginationCountSql:1379` 四道前置只看 `paginationSimEnabled` / `paginationCountEnabled` / count-consumed / `pageSearchCount` / `select.limit != null`，**独缺 `chain.pageLimit != null`** → last 的 LIMIT 过关 → `SELECT COUNT(*) FROM (…) TOTAL`。

修法＝加一行 `if (chain.pageLimit == null) return null`，语义收口为「COUNT 是分页消费的伴生物，不是 LIMIT 子句的伴生物」。

测试盲区：`PaginationSimTest.testSelectListWithoutPageArgStaysUnlimited` 既不带 `last` 也不断言 `countSql == null`；类头注释只声明「last 与分页并存以分页为准」，没覆盖「只有 last 没有分页」。要补三例（字面量 / 形参拼接 / 分页与 last 并存仍产 COUNT）+ 反向验证（去闸必红）。R12 把 `selectList`/`selectMaps` 补进 `pageConsumerMethodNames` 时没预料到 last 会把 LIMIT 送进来。

## 用户侧临时缓解（已给出）

- B：设置关「分页 COUNT 预览」子开关（`paginationCountEnabled`），分页模拟父开关可留。
- A：262 上「执行 SQL」不可用，改用「复制 SQL」到 Database 控制台；预览/复制/执行计划不受影响。

## 实施顺序与门禁

B 先（纯逻辑、定向单测可闭环）→ A（需 2026.2.1 沙盒或用户真机，耗时主体）→ A' 文案分流（依赖 A 的观察缝）。分支扁平名 `t-exec262-readlock` 基于 master。不动 build/plugin.xml EP/新 API 面 → 不触发构建校验档；定向后由只读收口代理单跑全量。

相关：[[pitfall-262-edt-readlock-database-runners]]、[[pitfall-runners-zero-means-unbound-not-unconnected]]。
