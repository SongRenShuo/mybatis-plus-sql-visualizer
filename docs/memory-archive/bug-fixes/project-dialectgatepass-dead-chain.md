---
name: project-dialectgatepass-dead-chain
description: T-DialectGatePassDeadChain（20261102 已实施并提交）——方言冲突「放行」分支先报 BLOCKED 把执行意图标完成，准备链静默早退、不 attachConsole 也不释放单飞门；修法＝BLOCKED 只在真阻断时投递 + 7 处早退结算门 + 放行态两蓝字入口常驻；定向 108/0/0、反向恰 1 红、全量 8517/1（性能阈值假失败）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7df4acee-c963-4e0d-9a96-cbe306b5ad33
---

# T-DialectGatePassDeadChain：方言冲突「放行」把准备链静默卡死（20261102 已实施）

用户报障（v2026.11.2 / IU-262.11879.100 / master `71ace631`）：日常 IDE 里「执行 SQL 失败」+「无法切换数据源」+「点切换数据源没看到在哪点」。
计划册：`docs/开发计划/20261102-1344-T-DialectGatePassDeadChain-…-开发计划.md`（§八 实施记录已回填）。

## 根因（四步，代码可对行）

1. `DataSourceSelectorResults.kt:229-233`：整条准备链用的 `onSubmission` 就是 `queuedIntent.onSubmission`（= `complete()`，会置 `completed=true`）。
2. `DataSourceSelector.kt:234-237`：闸门对**所有非 MATCH** 判定先报一次 `ExecutionSubmission.BLOCKED`——`8619a3af`（10-25「方言冲突不再阻断执行」）把 MANUAL_CONFLICT / RERENDER_UNAVAILABLE 改成 `return true` 放行，但这行 BLOCKED **没跟着收窄**。
3. 于是意图被标「已完成」，`DataSourceSelectorResults.kt:337/359` 的 `isPrepareActive()` 变 false → consumer 静默 `return@Consumer`：不调 `attachConsole`，也**不 `abortPrepareGate`**。
4. 单飞门 `prepareInFlight` 保持 true、owner 不变 → 60s 内任何执行/换源/刷新都 `QUEUE_ONLY` → 15s 后报「数据库控制台准备超时」。owner 自己 60s 逃生的 `complete()` 因已被 BLOCKED 消费而**发不出任何提示**。

触发条件＝**渲染方言 ≠ 所选数据源方言**。不是 GaussDB 专属；`MATCH` 时压根不进冲突分支（同机 5 分钟前另一个工程 PG=PG 跑得好好的）。

## 实施（master 四笔，均已提交）

| 项 | 落点 |
|---|---|
| P0-A | `allowDialectGate` 先算 `allowed` 再投回执，`BLOCKED` 仅限 `!allowed && !needsConfirmation`；顺序仍是「先报未提交、后交付红字」 |
| P0-B | 7 处 `isPrepareActive()` 早退改「结算门 + return」（Console 4 + Results 3） |
| P0-C | `SqlResultPanel.showStatusWithActions`（与 `showStatus` 同款、只是不清动作）；放行态挂回「切换数据源」「方言设置」两蓝字 |
| P1-D | 新增 `execute.prepare.queuedTimeout`（带秒数）+ `execute.prepare.superseded`，排队超时不再复用「准备超时，请重新选择数据源」 |

提交链：`12b49777`（主体）→ `93f1ac82`（换源入口常驻「更多」）→ `eb66f362`（接管原生下拉）→ `5c681d11`（热路径去设置树）。

## 读数与反向验证

- 定向 10 类 **108/0/0**；全量 testShards **8517/1/0/14**，唯一失败 `LogPipelinePerfTest` 子串搜索 83ms/预算 80ms，隔离复跑两次 + shard2 复跑均绿 → 负载型假失败。
- 反向：把 P0-A 改回「非 MATCH 一律投 BLOCKED」，恰 1 红且红的正是 `pass through conflicts must not settle the execution intent`；恢复后复绿。
- 首轮定向 3 红全是 `PrepareSingleFlightGateTest` 的调用点计数守卫（`abortPrepareGate(prepareOwner,` 期望 5→12、PREVIEW 嵌入点 1→2），按「计数漂移」同步期望并写理由。

## 诊断手法（可复用）

同一份 `idea.log` 取「成功一次 / 失败一次」对照：成功那次 `session created → attachConsole ->` 隔 149ms；失败那次 `session created → runRunner invoked OK` 只隔 6ms，此后 `attachConsole` / `prepareInConsole FAILED` / `prepare chain aborted` **三样全零**。`runRunner` 是同步调 `runner.run()`，6ms 反证 `attachConsole` 根本没进入——「既没成功也没失败也没中止」就是某个 `if` 静默 return 的指纹。

## 用户侧临时办法

把渲染方言改成与所选数据源一致（方言设置 → 数据源同方言），闸门判 MATCH 即恢复。

相关：[[project-dialect-gate-release-on-conflict]]（定案与本次回归）、[[project-dialect-gate-ux]]（入口形态与热路径坑）、[[reference-dialect-mismatch-execution-gate]]、[[pitfall-runners-zero-means-unbound-not-unconnected]]、[[pitfall-settings-tree-instantiation-in-hot-path]]
