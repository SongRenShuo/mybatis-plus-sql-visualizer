---
name: pitfall-gradle97-tasks-all-asmfat
description: Gradle 9.7 下 ./gradlew tasks --all 恒失败——embedAgentJar→agentFatJar
  触发 asmFat 配置解析独占锁报错（基线即坏，既有问题）；改用 help --task 点查
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e1182cfb-7089-49a6-9c55-13577b34c426
---

本仓 Gradle 9.7.0 下 `./gradlew tasks --all` **恒失败**（2026-09-17 实测，`--no-configuration-cache` 同败）：`:tasks` 报告任务实现全量任务时，`embedAgentJar.from(:sql-visualizer-agent:agentFatJar)` 强制 agent 子项目 `agentFatJar` 创建，其内解析 `asmFat` 配置被判「Resolution ... without an exclusive lock. This is unsafe and not allowed.」。

**Why:** lazy task realization 链在 `:tasks` 报告语境下触发跨项目配置解析，Gradle 9 的独占锁保护拒绝。

**How to apply:** 盘点任务用逐个 `./gradlew help --task <name>`（不动 embedAgentJar 链）；主项目 `help` 正常。基线 4c3e268 即坏、与配置缓存无关，勿误判为新改动回归（T-BuildScriptSplit 留档，修它属行为变更另行立项）。IPGP verifyPlugin 另一遗留：failureLevel 含 DEPRECATED/EXPERIMENTAL 级而用量后期混入（243 上 6+14、262 上 55+14），今已确定性判败——它已退出全部门禁（releaseGate/verifySmoke/verifyFull 走 verifyGate），failureLevel 政策调整留 T-VerifierHomeIso 域。
