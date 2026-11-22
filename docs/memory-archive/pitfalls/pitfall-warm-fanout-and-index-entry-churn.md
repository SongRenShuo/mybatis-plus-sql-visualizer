---
name: pitfall-warm-fanout-and-index-entry-churn
description: 三处「看似省钱其实白烧」的形态：预热按语句去重 ⇒ 一个 mapper（11 条语句）预热 11 次；反查索引在启动期被结构版本抖动换掉缓存条目 ⇒ 已完成扫描被丢弃重扫（同秒 7 次候选源、只 1 次建成）；跨 ORM 回补挂条目上 ⇒ 每个 5 分钟 TTL 都重扫全项目。修法＝每文件一次+每项目 2 个额度、结果落到「当前条目」、回补按项目+结构版本缓存
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

2026-11-13 22:20 用户首测日志（多模块 demo 沙盒）里一次抓出三处，全部当轮修掉。

## 1. 预热按语句去重 ⇒ 一个文件预热 11 次

日志同秒内 `assembly warm done in 9.8s / 9.9s / … / 10.9s` × **11**，11 个不同 `key`（同一个 mapper 的 11 条语句）。
那 10 秒几乎全在等 smart mode（不是 CPU），但 11 份装配挤在同一刻落地、纯属浪费。
修：`PreviewAssemblyWarm` 改**每文件一次**（`warmedFiles`，按 `VirtualFile.url`）+ **每项目每会话至多 2 个文件**
（`WARM_FILES_PER_PROJECT`）；失败/取消把去重标记与额度一起放回，等下次渲染重试。
守卫：`test同一文件的第二条语句不再预热`、`test每项目预热文件数有上限`；反向退回按语句 → 恰 2 红。

## 2. 反查索引启动期被重复建 7 次（只 1 次「一次建成」）

启动阶段 `ProjectRootModificationTracker` 版本抖动会换掉 `REVERSE_LOOKUP_CACHE_KEY` 条目，而
`applyBuiltIndex` 只在「条目还是我这一份」时落账 ⇒ 已完成的 7s 级扫描被丢弃、随即在新条目上重扫。
修：结果落到**当前**条目（本次扫描本就在新结构下跑的，丢才是浪费），并顺手 `releaseBuild(current)`
放行挂在旧条目上的等待者。
守卫：`indexBuildCount`（跨缓存实例的累计建索引次数）+ 「打字不作废索引」用例；
**只看 `scans` 会空绿**（条目重建后归零）——这是本轮的踩坑点。

## 3. 跨 ORM 回补每个 TTL 周期重扫全项目

`inferredScanDone` 原本挂在缓存条目上，而条目按 TTL(5min) 重建 ⇒ 每个周期都重跑一次全项目推断
（该工程 `跨 ORM 回补完成 tables=8906`，一趟 4.7~9.7s）。
修：回补结果按**项目 + 结构版本**缓存（`INFERRED_INDEX_KEY`），TTL 重建的条目直接并入、
`ensureInferredScan` 命中缓存就跳过扫描；注解索引建成时也把缓存回补一并并进去。

## 通用口径

- 任何「每 N 秒/每 TTL 重建」的缓存，先问：**重建时那笔昂贵结果会不会被丢掉**；丢弃条件用「条目同一性」时，
  启动期结构抖动就是双倍成本。
- 任何「文件级一次」的去重，先问：**这个文件的粒度对不对**（一个 mapper 十几条语句，按语句就是十几倍）。
- 计数类守卫要能从「跨实例」看，否则条目重建会让计数归零 → 空绿。

关联：[[pitfall-mapped-exec-file-write-triggers-dumb]]、[[project-preview-perf-extreme-20261114]]、
[[pitfall-candidate-scan-cap-cache-and-test-traps]]。
