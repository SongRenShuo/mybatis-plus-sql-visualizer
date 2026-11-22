---
name: pitfall-enum-dead-constant-guard
description: 本仓 EnumReachabilityGuardTest 会判「声明了却零产出」的枚举常量为死常量——占位值要删不要白名单
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bef52d81-b9f2-456b-95bc-3e62d26f3e58
---

本仓 `src/test/.../convention/EnumReachabilityGuardTest.kt` 会扫描生产代码里的枚举，把「声明了却零产出（消费者恒走不到 / 语义无法表达）」的常量判为死常量并报红，报错信息里点名 `文件:行 常量名 —— 死常量（零引用）`，并给出二选一：补产出点，或删常量，或（若确属「有意保留的未产出位」）加 `WHITELIST` 条目并写清理由（守卫会校验条目仍然成立）。

2026-09-22 O-56 合并态门禁首跑就撞上：车道为「SQL Server `SET SHOWPLAN_XML` 未接入」预留了 `ExplainOutputFormat.XML` 占位值，被判死。

**How to apply**：新增枚举常量时先想清「谁会产出它」；纯为「将来接入时少改一行」的投机预留（YAGNI）**直接删**，白名单口子是留给「有产出计划的占位」的。接入时补一个常量 + 一条表项的成本，远低于长期维护一个死分支。定向复跑命令：`./gradlew test --tests "*EnumReachabilityGuardTest*"`（注意 `testFast` 不含平台型用例）。

同类守卫的「空绿」教训见 [[pitfall-guard-covers-audited-targets]] 与 [[pitfall-scan-guard-mask-and-sample-power]]。
