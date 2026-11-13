---
name: project-perf-review-round5-20261029
description: IDEA 性能影响第五期复查（20261029）+ T-PerfSweep28 五券全部实施完毕（四提交 + 全量门禁 8545/0/0/10）——券02 证伪撤销、券03 范围收缩、券04 改引号预筛
metadata:
  node_type: memory
  type: project
  originSessionId: session_93770b14-7ec0-4d2f-9147-e70319b1c716
---

性能分析第五期（`docs/性能分析/20261029-0344-IDEA性能影响-性能分析.md`）与计划（`docs/开发计划/20261029-0344-T-PerfSweep28-…`）已提交双仓：`0705697a`（报告+计划）、`a368bda2`（索引登记）、`6c0a3d33`（**D195/V220 索引缺口归零**，详见 [[project-doc-index-backfill]]）。

**唯一高风险（第五期主结论）**：外部贡献的 Mapper 双向跳转 Java→XML 方向——每个打开 Java 文件的每个方法声明在 daemon 慢趟调 `MapperXmlLocator.locate`，无接口判定前置、无负结果 memo、**命中也不早退**（为统计多模块同名扫完全部 mapper 文件），5s TTL 过期后的全项目 XML 索引重扫落在 daemon 线程内；两个 Provider 均未接 GutterGroups 组闸。与 20260910 版 P1 gutter resolve 同形态。

**T-PerfSweep28 已全部实施完毕（20261029，用户令「实施所有内容」）**：

| 券 | 提交 | 结论 |
|---|---|---|
| 01 Mapper 双跳门控+memo+组闸 | `51100f67` | 先红 4 红 → 绿。memo 作为 FileListSlot 成员（同代失效）+ MISS 哨兵承载负结果；Java 侧 `containingClass.isInterface` 前置；两 Provider 批级组闸归 mapperXml |
| 02 projectScope+DumbService | 无码改 | **撤销（P-5 证伪）**：JAR 宿主 mapper 接口（common-mapper）是真实场景，收窄会丢 XML→Java 图标；resolveClass 的 FQN 路线本就 allScope 且无 dumb 守卫 |
| 03 无上限缓存收口 | `ed0432b7` | **范围收缩**：countCache 自 T-Perf-05 `38bf14cf` 起已有 256 上限（第五期报告误报）；实改 HibernateNamingConfigProbe（512 上限 + store 守卫 + putForTest 缝）与 activePreviewDialogs（强键 CHM → synchronizedMap(WeakHashMap)） |
| 04 StringSqlMethod 门控 | `f03d5d8e` | **修法偏离**：候选方法名集含 update/delete/execute 高频词 → 文件级文本门控精度趋零；改方法体引号预筛（无 `"` 必无字符串 SQL），反向 60 次遍历 → 0 |
| 05 守卫与测试卫生批 | `daa59e30` | BannedApi setMapping 已随 `8c960343` 收窄（ExecFileBinding 0 处 SqlDialectMappings）→ 确认性跑绿；O147 加 @After 清理 tempDirs（净增 0）；LogPipelinePerfTest 两处比值判据加 1ms 基数下限 |

**读数**：定向 231 项 0 失败；统一全量门禁 **8545 / 0 / 0 / 10**（主工程 8364 + agent 181）18m44s，parity 778/778；实施记录提交 `a8e330bd`。

**残余成本（如实留档）**：memo 消除 PSI 层重活，但每次定位仍付 O(mapper 文件数) 的 `VirtualFile.isValid` 检查——既有 `testT_P5_05_缓存文件失效即重扫` 钉住「删除即刻失效、不等 TTL」，memo 命中不能跳过。属 VFS 廉价检查，非 PSI 解析。

**移交项**：①三项真机读数未取（大项目 daemon 墙钟对照 / T-SqlDialectPrewarm R1R2 / PerfSweep27 券01 JFR），代码级判据已由遍历计数用例钉住；②本机 Temp 尚有 477 个历史 `o147-history-*` 目录（本批只保证不新增，清理需用户确认）；③testShards 分片权重画像 +19% 漂移，下轮 clean 后跑 `dev-support/weight-refresh.ps1`。

**Why**: 后续会话若再引用「第五期说 countCache 无上限」「BannedApi master 现行红」「券02 待做」，都是已被实测推翻/兑现/撤销的旧口径——以本档与计划 §实施记录为准。

**How to apply**: 实施坑见 [[pitfall-cache-memo-shares-listing-slot]]；跑 gradle 前 `parallel_tasks.py check`；每券独立提交（中文 -F 文件），收口后单派只读代理跑全量门禁、主控并行写文档。
