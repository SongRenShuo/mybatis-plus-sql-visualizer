---
name: project-releaseperf10-watermark-lanes
description: T-ReleasePerf10（20261030 已合 master `16fa3ad3` 并推双仓，工作树与
  分支已删）——水位自适应限流/快速失败闸门/memory-hygiene/verifyMarketplaceRefresh
  已落地；定案=releaseGate 墙钟 ≈ verifyMarketplace 本体（50min 那轮是冷构建+抢占的异常），
  测试档位默认封顶 3
metadata:
  node_type: memory
  type: project
  originSessionId: session_4be84543-82ad-434c-bee2-874cc717cd69
---

T-ReleasePerf10（2026-10-30，计划 `docs/开发计划/20261030-0827-T-ReleasePerf10-…`，占 O-154/D205/V231，**已合 master（合并提交 `16fa3ad3`，工作树 `mpsv-releaseperf10` 保留）**）。用户令双题：压全量测试/releaseGate 墙钟（效果不变）＋根治近期 OOM。

**已落地四件**（提交 `9de68ca4`→`0e1b8f78`）：
1. `buildSrc/MemoryWatermark.kt`——Commit 水位探针（`Win32_OperatingSystem.FreeVirtualMemory`）+ 档位表 + 快速失败闸门。档位模型：单 lane 实测 commit **1.4~1.6GB**、安全余量 5GB，`lane = max N 满足 free−reserve ≥ N×1.6+5`；**默认封顶 3**（`-PtestShardMaxLane=5` 放开）。releaseGate 编排 reserve 4GB。自适应只抬高不压低（低水位由闸门拒绝起跑）。
2. 闸门挂 `testShardStagger` + `verifyGate` 的 doFirst（floor 缺省 6GB，`-PwatermarkGate=off` 关，探针失败 fail-open）。**实测拦下过一次真实 OOM 轮**（free 3.42GB）。
3. `dev-support/memory-hygiene.ps1`——按 CommandLine 归类报告 + `-Clean` 只杀无父壳 executor/CDP headless/无客户端 daemon/僵尸 Kotlin daemon（WorkingSet 判据）。
4. `verifyMarketplaceRefresh` 任务（同参数面在线 check 回填 home，不判 verdict，写 `.mpv-refresh-stamp`）+ `gradle-gate.ps1` stamp>36h WARN。实测 0 B 下载 / verifier 11m18s / stamp 落盘。

**关键实测（同树 8429 例，四元组全程 parity 8429/4/0/8）**：
| 档位 | 实际并发 | 测试窗 | execSum |
|---|---|---|---|
| lane2 | 1 | 1083.5s | 1000.8s |
| lane2 | 2 | 670.5s | 1149.2s |
| lane3 | 1~2 | ~24min | 1263.5s |
| lane5 | 5 | **459s** | **1996.8s** |
⇒ **5 档把窗压 31% 但 execSum 翻倍、多 4 例负载敏感红**（`LogPipelinePerfTest` 三项预算超限）→ 默认封顶 3。

**releaseGate 两轮实测（结论已订正）**：轮1（10:13，冷构建+4 条并行会话）50min **串行**；轮2（12:04，机器空闲+暖构建）**13m22s**，各阶段完整重叠——market 789s（关键路径）/ shards 544s（lane3 两波）/ gate 206s / e2eFakeIde 29s，`802s ≈ market 789s + build 13s`，与 T9 的 `max(market, 分片窗, gate)+e2e` 模型吻合。⇒ **串行是异常形态不是结构问题**；**releaseGate 墙钟 ≈ verifyMarketplace 本体**，要再压只能动 market 段（券04 保温处理下载税，本体解析 3 目标 ~10~13min 压不动，减目标属削减内容）。
证伪探针：`verifyGate + testShard1 -PverifyOnly=true` 两者相差 658ms 同起跑、总时长=较长者 237s → 「JavaExec 阻塞 Test」不成立。
**e2e 定序缺陷（已修，`34119ec7`）**：`e2eFakeIde` 的 `mustRunAfter` 原挂聚合任务 `testShards`——任一 shard 失败时（权威轮 `--continue`）该聚合任务被 Gradle 跳过，**跳过任务的排序边一并丢失**，e2e 于是提前 4min 与剩余分片并发（跑真实应用+MySQL，有污染风险）。改挂分片叶子 `Regex("testShard[0-9]+")` + `testExternal`；实测 `testShard2 e2eFakeIde --continue` → shard2 12:25:53 失败收尾，e2e 12:30:06 才起跑、28s 通过。**这条是"聚合任务被跳过会让 mustRunAfter 失效"的可复用教训。**
**踩坑三条（本轮自造并修掉）**：①配置期 `ProcessBuilder` 起子进程 → Gradle 9 判 CC problems 失败，改 `providers.exec`；②`providers.exec().standardOutput.asText` 被仓内 `BannedApiSourceScanTest` 禁用（T-BS-01 字符集坑），改 `asBytes` + 显式 UTF-8；③并发度计数器版被失败分片漂移污染，改进程扫描并改名 `laneOthers`。

**其他**：新工作树 e2e 需 `.local/local.env` 的 `MPSV_DEMO_ROOT`（gitignore 目录不随树复制）；4 例 `DialectDocTableGuardTest` 红是文档站兄弟仓已落 14 方言三行、master 插件未跟（随 T-XcDialects3 落 dev 消解）。

**闸门在真实发版轮拦下过一次（2026-10-30 v2026.10.31-beta.1）**：`releaseGate` 起跑 13s 就在 `testShardStagger` 失败——`free commit 1.93GB < floor 6.0GB`，诊断行列出前 8 占位者（idea64 5.3GB / java 4.6GB / java 3.1GB / ZCode 2.9GB / explorer 1.3GB…）。**处置顺序**：① `dev-support/memory-hygiene.ps1` 先看 suspects（这轮 suspects=0，说明没有可杀孤儿，别硬杀）；② **`./gradlew --stop`**——两个 daemon 合计 7.61GB，停掉后 free 从 3.5GB 直接回到 **11.2GB**，闸门即过；③ 仍不够再等并行会话/runIde 收口，最后才是 `-PwatermarkGate=off` 紧急放行。**教训：日常闲置的 gradle daemon 自己就占掉 4~8GB 提交量，跑 releaseGate 前先 stop 是最省事的腾挪手段。**
**配套坑：`TaskStop`/杀后台 bash 任务停不住 gradle**——工具报任务已停，daemon 里的构建照跑，`test-executor`×3（4.1GB）+ `verifier`×2（3.0GB）继续挂着；hygiene 脚本按「无父壳 executor」判据也认不出它们（suspects=0）。确认真正停下要用 `./gradlew --stop` 并复查 `jps`/进程命令行，否则下一轮 gate 还是撞水位闸门。

相关：[[release-gate-timing-floor]]、[[parallel-build-resource-rules]]、[[pitfall-testshards-shards-run-serially]]、[[feedback-background-task-no-blocking-wait]]
