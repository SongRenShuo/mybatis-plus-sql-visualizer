---
name: project-test-count-report-20261114
description: 成功腿不打印例数（Gradle 只在有失败时输出 N tests completed）——已加 MPV-TEST-COUNT
  收尾打印；实现要点＝戳写 temporaryDir 不写 reports 目录、只计本轮 mtime、失败腿交给 Gradle 自带汇总
metadata:
  node_type: memory
  type: project
  originSessionId: sess_49bc55a9-1a09-480e-ad61-37f3be32dc94
---

**缺口**：Gradle 的 `Test` 任务**只在有失败时**才打 `N tests completed, M failed`；全绿腿日志里没有任何例数。2026-11-14 CI gate 二轮 15 腿全绿，门禁收口只剩 conclusion + 时长——「静默跳过大半用例」与「真全绿」在日志上不可区分（仓内历史读数却都是 `8092/0/0/9` 这种带例数的形态）。

**修法（已落码，T-TestCountReport）**：`buildSrc/src/main/kotlin/mpv-testing.gradle.kts` 的 `tasks.withType<Test>().configureEach { … }` 块末尾加 `doFirst` + `doLast`：

- `doFirst` 把毫秒戳写进 **`temporaryDir`**（`mpv-count-start.txt`）；
- `doLast` 扫 `reports.junitXml.outputLocation` 下 `TEST-*.xml`，**只计 `lastModified >= 戳`** 的，逐属性独立正则取 `tests/skipped/failures/errors`（不依赖属性顺序），打一行 `logger.lifecycle("MPV-TEST-COUNT task=$name tests=… skipped=… failures=… errors=… suites=…")`。

**三条实现红线（都是踩过的形态）**：

1. **戳不能写进 reports 目录**——那是任务的 `@OutputDirectory`，写进去会污染 up-to-date 快照，让本地测试**每次都被判非最新而重跑**。用 `temporaryDir`（不参与快照）。
2. **必须按 mtime 过滤本轮**——否则跨轮残留 XML 会把上一轮例数混进来（同 `T-TestPerf10 S2` 给聚合报告用 `.round-start` 的口径；曾出现假读数 6222 vs 真实 6185）。
3. **失败腿不执行 `doLast`**，正好与 Gradle 自带的失败汇总行互补，两条路径不重复；所以本机制只负责「成功腿」。

前缀固定 `MPV-TEST-COUNT` 便于 grep/CI 抽取；仓内无任何脚本依赖旧文本 `tests completed`（grep 已核零命中），可安全新增。

**口径纪律（写进计划了）**：成功腿日志既然不打印例数，就**不许把失败轮的例数（fast 5020 含 1 红 / shard1 883 / shard4 1018）当通过读数引用**——失败腿不覆盖全桶。

状态：代码已改，本地验证在跑（配置缓存默认开启，正好验兼容性）；验证通过后提交并重派 gate，用新读数自证。

参见 [[project-rollback-snapshot-preexec-20261111]]、[[reference-ci-gate-dispatch-and-watch]]、[[pitfall-testfast-bucket-literal-filter-and-config-cache]]。
