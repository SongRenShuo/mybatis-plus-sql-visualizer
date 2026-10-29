---
name: project-perfsweep26-batch
description: T-PerfSweep26（性能二十六轮发现点收口）20261021 已全落并合 master——七券十处改动三路并行零冲突、门禁
  8106/0/0/9+agent 164/0/0/0；含 F95「quick 只发生在打开期」与 F98「裸短路有两处旁路」两条前提被实测推翻
metadata:
  node_type: memory
  type: project
  originSessionId: session_dd01afde-607b-4ca3-a136-3c9e32c3b3b1
---

# T-PerfSweep26：性能二十六轮发现点收口（2026-10-21 已实施，master 未推送）

基准：master `666dd5d5` 起，合并后文档笔 `11fbb66f`。七券十处改动，三路并行写集互斥、`--no-ff` 零冲突合并（`5fce9a41`/`387c93ae`/`3e990eba`）。

## 提交与读数

| 券 | 提交 | 关键读数 |
|---|---|---|
| 01 F83 去重淘汰键级计数 | `3d9fc3da` | DedupeIndex 18/0/0 + 既有 Buffer 59/0/0；反向恰 1 红 |
| 02 F95 折叠尊重 quick + 100K 熔断 | `2aad0776` | 11/0/0/0（先红 2 败）；反向恰 2 红 |
| 03+04+102 引用 memo 与去重集有界 | `cb08e3be` | 20/0/0 + 13/0/0；反向各 2 红/1 红 |
| 05 F98 停靠预览短路前置 | `fcff51a3` | 7/0/0（先红 1 败）；反向恰 1 红 |
| 06 F99 EXPLAIN 绑定等待 480→120 拍 | `cf5d5cb4` | 10/0/0 |
| 07 F101/F104/F105 | `42548513`/`ae7b0cb4`/`7da4753e` | 8/0+2/0、3/0+1/0、4/0 |

**权威全量（只读收口代理，`--no-configuration-cache`）**：testShards **8106/0/0/9**，746 类 XML，**740 类计划与实跑完全对齐**（非空绿），19m07s；agent **164/0/0/0**（30 类）。9 跳过＝Docker 方言 5 例（**直接原因是没传 `-PmpvDockerTests=true`**，非 daemon 不可用，见 [[pitfall-assumption-skip-misattribution]]）+ 未提供 jdbcUrl 1 例 + Database 插件不在测试 classpath 2 例 + 无 impl 产物目录 1 例。

**⚠️ 归因错误已更正（`1a6283c8`）**：首版把 Docker 5 例写成「daemon 未启动」，用户追问后读 XML `skipped message` 原文才发现是开关闸门先命中——三处文档（验收/计划/变更记录）已同步改，并补上「本轮未真正执行 daemon 探测」。

## 两条计划前提被实测推翻（最有价值产出）

- **F95 quick 语义**：sources jar（`ideaIC-243.28141.41-sources.jar`）实证——`quick=true` 的唯一实质调用点是编辑器打开时的初始折叠（`CodeFoldingManagerImpl.buildInitialFoldings`）；**daemon 每轮折叠重建恒传 `quick=false`**（`CodeFoldingPass.doCollectInformation` → `updateFoldRegions(editor, firstTime, false)`）。故计划写的「键入后的 daemon 折叠重建传 quick=true」不成立，修复仍正确（契约安全、daemon 全量重建兜底）但**收益面从「编辑期热路径」降为「打开大 mapper 一次」**。
- **F98 裸短路不安全**：`displayedEntry === entry` 单条短路有两处旁路——①两者同为 null 时误命中，吞掉空态/多选态文案刷新；②多选且首选中恰为已展示条目时原路径须清预览改显「已选多条」。须补 `displayedEntry != null` + `selectedIndices.size == 1` 两个守卫才语义等价。**「一行改动」的计划判断偏乐观**。

## 其它实施事实

- F83 A 案（拿 `dedupeCounts==0` 短路）先被证伪：它是**丢弃计数**且有 `MAX_DEDUPE_COUNT_KEYS` 容量淘汰，存在漏去重正确性窗口；故改 B 案（键→队内现存条数，与 dedupeIndex 同锁维护，接受 +1/淘汰 −1/rebuild 重建/接管沿补账）。四交互点复核：`mergeDuplicateEntry` **不改键**故融合无需调账、`dedupeEnabled` 双向一致、`canDedupe=false` 双向不计数。
- F101 补全模式加 `withLanguage(XMLLanguage.INSTANCE)`（API 取 sources jar `PsiElementPattern:268`）；F104/F105 提文件级/伴生常量。
- F103（Kotlin lineMarkerProvider 注册）**用户拍板方案 A 保留**、本批零改动——理由：成本每 leaf O(1) 短路属微级、撤注册要跑 verifyGate 三目标+改四处注释+未来恢复成本。重新评估触发条件=产品明确放弃 Kotlin gutter 或实测超微级。
- 本批未动 build.gradle.kts / plugin.xml / kotlin-optional.xml ⇒ **免构建校验**；三路曾临时加 `sandboxContainer` 隔离，收尾全部还原（三支 diff 为空）。
- 文档：计划 §九 实施记录与前提证伪表、验收文档 `docs/验收文档/20261021-1830-T-PerfSweep26-*`（七节）、变更记录、索引状态「已实施」。

## 并行协作形态（可复用）

三路 = 两路子代理（log 域 / ui 域）+ **主控亲自一路**（ui/ref+psi 域），写集按目录互斥；主控本路测试与代理并发跑（构建并发上限 2 路被短暂放松，实测编译期较早、测试段错峰，未撞资源）。登记 ps26-log / ps26-ui / ps26-ref 三条，收尾统一注销；worktree 长路径删除失败仍走 PowerShell `Remove-Item -LiteralPath`（见 [[pitfall-worktree-remove-longpath]]）。

相关：[[pitfall-fake-green-test-assertions]]（本轮新增第 ㉟ 形态）、[[project-pending-plans]]、[[parallel-build-resource-rules]]。
