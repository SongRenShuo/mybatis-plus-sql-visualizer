---
name: project-perf-review-round6-20261030
description: IDEA 性能影响第六期复查（20261030 已交付）——系列首次高风险清零，R29-1 已由 T-PerfSweep29 券01 修复并合并；全量 8433/4/0/8，4 红为三方言表既有红
metadata:
  node_type: memory
  type: project
  originSessionId: session_1f993f5e-c11f-4375-a77b-2b6b462013dd
---

第六期报告 `docs/性能分析/20261030-0331-IDEA性能影响-性能分析.md` 与计划 `docs/开发计划/20261030-0331-T-PerfSweep29-性能二十九轮发现点收口-开发计划.md` 已提交 master（`4c9d0a12`，索引行局部暂存避开并行会话在途改动——用 head/tail 拼装排除他会话行，见 [[pitfall-shared-file-index-partial-staging]]）。T-PerfSweep29 券01 已实施：独立分支 t-perfsweep29（工作树 mpsv-perfsweep29，基于 4eda4b58）实施提交 `41b789b7`，主控合并 `2047b058`，索引状态 `16f8352f`。性能系列先例：计划不占 O/D/V/R 号。

**券01 实施读数**：P-1~P-4 全部成立（P-1 措辞修正：SINGLE_LINE 分支有既有 `cachedMaskedSummary` memoization 副作用，本券不触碰该分支）；R29-1 取源收进 SINGLE_LINE 分支与多行缓存未命中分支＋伴生计数缝 `listSourceFetchCount`＋新增 `ListSourceFetchCacheTest` 4 例；先红（2 failed `expected:<1> but was:<2>`）→转绿 4/0→反向还原 3 failed 复绿；定向五类 120 例 0 失败（HistoryCellRendererTest 29/LogEntryRailPerfTest 17/SqlLogPanelRendererUiTest 53/新类 4/SqlListFragmentRendererTest 17）。R29-2（配置证据枚举面）/R29-3（MCP 过滤器 EP 无摘除）按计划登记不动。

**用例设计返工 1 次（可复用）**：计数缝用例首版用被测条目自预热，把条目级缓存先填满 → 修复后计数窗口全命中、读数恒 0，是**假绿**；改用另一个条目实例做预热后才具判别力。

**全量门禁（已取到，`d6ea6013` 回填计划 §实施记录）**：`./gradlew testShards` 五片 **tests=8433 failures=4 errors=0 skipped=8**（789 类），墙钟 **988s**。4 例红全为 `DialectDocTableGuardTest`（分页表/UPSERT 表 × zh/en），断言「出现未知方言 gaussdb, highgo, vastbase」——兄弟文档站仓已先落三方言表而本仓 DialectRegistry 未登记，属 T-GaussDB/T-XcDialects3 在途既有红（T-LogListAnchor 同族读数亦恰该 4 例），与 R29-1 零关系。

门禁取数手法（可复用）：Docker 手动暂停态按用户指示跳过预检；首轮子代理跑的门禁于 08:59 整条进程树死亡（provider 中断带走孤儿 gradle，5 分片 xml 停在 1140 份）读数作废。第二轮改由主控 bash 脚本承担：轮询到「无他人 testShards 进程 **且** 物理内存 ≥6GB」才启动（避与并行会话撞水位墙），启动前把陈旧 `build/test-results` 移走防混计，跑完自动汇总例数并列出失败用例名。**结论：长跑收口别交给处于「等通知」状态的子代理——它在等待态被 provider 中断两次，恢复后白跑。**

**流程坑**：`git worktree remove` 报 `Filename too long` 直接失败 → 先 .NET `[System.IO.Directory]::Delete('\\?\'+path, $true)` 删目录，再 `git worktree prune`，最后 `git branch -d`。

**Why**: 后续会话引用「R29-1 待修」「第六期报告未提交」「门禁已绿」均为过时口径；性能系列已连续两期无高级别发现，报告 §八建议把全量对照收拢为「发行前＋合流后增量」两节奏。

**How to apply**: 报告引用行号锚点已 grep 二次复核；跑门禁前 `parallel_tasks.py check`；补跑门禁时按 [[pitfall-regression-false-failure-flakes]] 末条判别陈旧分片读数；实施坑见 [[pitfall-cache-memo-shares-listing-slot]]；第五期细节见 [[project-perf-review-round5-20261029]]。
