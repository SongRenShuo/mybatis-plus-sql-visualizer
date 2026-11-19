---
name: pitfall-prefetch-needs-failure-memo-and-coldstart-vs-algorithm
description: 预取/预热类优化必须有失败记忆（渲染驱动的重复触发会自旋）；优化「首次操作慢」前先分清冷启动固定成本与算法成本
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

# 预取要防自旋；首开慢先分清是固定成本还是算法成本

2026-11-12（T-PreviewOpenPerfM2 端到端验收，cust-big-demo）两条连续踩到的：

## 1. 「首次打开慢」先分清冷启动固定成本 vs 算法成本，再决定改哪一头

`[open-perf] total=1689ms … m2=1628ms`（新起 IDE、索引刚建好那一次），第二次起 `m2≈0`、弹窗 200ms 级。
第一反应是「全项目引用检索面太大」，于是把 `ReferencesSearch` 的 scope 从 `projectScope` 收到
`GlobalSearchScope.moduleWithDependentsScope(mapper 所在模块)`（语义上不丢命中）。

**重跑读数反而更慢：`m2=2042ms`、弹窗 4580ms（原 2714ms）。** ⇒ 那 1.6~2s 是**冷索引/冷 JIT 的固定成本**，
不是搜索广度；换作用域既没用又加了 `findModuleForPsiElement` 的开销。

正解是把成本**挪到用户还在读文件的时候**（预取），而不是继续压算法。判据也要先立：先看「同一操作第二次
是否快」——快就说明是一次性成本，压算法是在给固定开销做无用功。

## 2. 预取必须有失败记忆，否则渲染驱动的入口会自旋

预取的挂点选在 gutter 渲染（本来就要遍历 mapper 语句）。首版只有「缓存新鲜即跳过 + 同 key 在途去重」，
漏了「**解析不到**」这条路径：`countByExample` / `deleteByExample` 在工程里没有调用点 → `resolve` 返 null →
上游设计是「null 不写缓存」（这是对的，免得把「当时索引没就绪」固化）→ 下一次重绘又触发一次全项目检索。

实测 6 秒内同一语句各 4 轮、共 18 条 `[m2] trace`，等于用一个自旋替掉了一次性延迟。

三条护栏（缺一不可）：
1. **失败记忆** `prefetchFailed: Set<key>`：预取解析不到/抛异常就本会话不再预取该 key；预览主路径不受影响，
   仍然现算（用户点开照常能拿到结果）；
2. **单条在途**：已有预取在跑就不再排，避免打开文件瞬间并发多条冷检索；
3. **只预取形态匹配的语句**（`select` + `parameterType` 以 `Example` 结尾），其余不烧。

复测读数（护栏前）：首点 `total=59ms … m2=1` → 弹窗 1015ms、第二点 `m2=0` → 弹窗 45ms ⇒ 预取本身有效，
问题只在失败分支的重试。

关联：[[project-preview-openperf-m2-e2e-20261112]]、[[reference-cust-big-demo-fixture]]