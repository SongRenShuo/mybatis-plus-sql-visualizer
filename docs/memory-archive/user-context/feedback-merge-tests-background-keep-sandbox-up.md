---
name: feedback-merge-tests-background-keep-sandbox-up
description: 用户定例（20261018）——子代理合并到 dev 后全量测试一律后台跑；主控不停沙盒验收继续干，测试绿了才重启沙盒换新包，省总时间
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_13bd728a-c47f-4204-a267-f528b9f57f9d
---

用户 2026-10-18 明令工作流顺序：「下次有子智能体合并到 dev 需要跑全量测试一类的，后台进行，主控先不要停止沙盒验收等测试跑完没问题了再重启沙盒，节省时间」。

**Why**：沙盒验收与全量门禁互不依赖（自动化门禁不能替代验收表），停沙盒等测试=纯浪费；用户此前已多次纠偏「主控不要阻塞/干等」，本条把该原则具体化到「合并+全量+沙盒」三角。

**How to apply**：
1. 子代理合并落地 → 立即 `run_in_background` 启动全量 `testShards`（禁阻塞，靠完成通知）。
2. 当前沙盒 IDE **保持运行不重启**，继续验收与本次合并无关的组（存量未验清单）。
3. 全量绿 → 再重启沙盒装载新包，验合并相关行为；绿之前不拿新包吹任何验收结论。
4. ⚠️ 技术边界：同工作树并发 gradle 有互抢家族（prepareSandbox 文件锁、build/test-results 被清、Kotlin daemon 串行）——**已在跑的沙盒不受影响**，但「重新 launch runIde」这个动作本身含编译/prepareSandbox，须避开全量在跑窗口：排在测试开跑前或绿后，不要中途插入。

相关：[[feedback-parallel-subagents-no-blocking-wait]]、[[parallel-build-resource-rules]]、[[pitfall-regression-false-failure-flakes]]（全量在跑时插入构建会放大 flake，反噬总时间）。
