---
name: project-d237-dedupe-nplusone-coupling
description: D237 已实施（20261119，用户拍板「按推荐来」，`5dfae3264`）——窗口嫌疑戳移出去重分支，N+1 与聚合不再被「SQL 去重」开关静默关掉；顺带删掉无界累积的 structureKeyByDedupe
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a383805c-722d-4353-8ff2-a7e7a0f56f84
---

# D237 去重开关 × N+1 检测的隐藏耦合（已实施）

**现象**：`SQL 日志`工具栏「SQL 去重」未勾选时，红色「疑似 N+1」徽标（D216）**永不出现**，由徽标进入的聚合过滤态（D235）**无从触发**——两个功能一起静默失效，**用户侧零提示**。20261119 真机验收 B2 时被用户抓出来。

**根因（`SqlLogEntryBuffer.kt` 入队分支）**：窗口嫌疑戳只在 `if (dedupeEnabled && canDedupe(added))` 里调用 `markWindowStampLocked(added)`；else 分支只置 `countFullRebuildPending`。`windowSuspectKeys()` 读的 `windowStamps` 为空即 `return emptySet()`。当初这么写是为省 `structureKey`/`dedupeKey` 两次指纹计算（PerfSweep9 优化过「去重关闭不触 dedupeKey lazy」）。

**成本实测（跑完即删的临时基准）**：`structureFingerprint` 10000 条 × 101 字符 = **38 ms / 单条 3.8 µs**；`dedupeKey` 对照 18 ms / 1.8 µs。⇒ 省成本的理由在实测量级下不成立（每秒几十条也就 0.1ms；且条目上就是 `by lazy`）。

**已实施（`5dfae3264`，回填 `d385b64c8`，双仓已推）**：
1. `markWindowStampLocked(added)` 移出条件分支，**无条件登记**；ShardingSphere 来源（`canDedupe=false`）同步纳入判定。
2. **`structureKeyByDedupe` 整张删掉**——比评估册原议的「补淘汰清理」更彻底：该 map 只在 `clear()` 清空、不随条目淘汰，是既有累积点；改用条目自身 `by lazy` 的 `structureKey`（同实例只算一次、随条目回收），代价仅同 dedupeKey 多实例各算一次（µs 级）。
3. 新增用例 `去重关闭_同线程窗内重复仍判嫌疑`、`不可去重来源_仍判嫌疑`。

**读数**：定向 WindowBuffer **8**/0/0/0（6 原有 + 2 新增）、Suspect 8、BadgeFragment 4、Summary 8。**反向验证：把调用移回条件分支 → 恰好新增那两条红、其余 6 条绿**（探针精度可据此判读）。

**否决的方案**：B 用原始文本哈希替代参数化指纹——参数化正是「同模板」定义，换了会让 `id=1`/`id=2` 判成不同模板、N+1 恒不命中；D 自造轻量窗口计数——两套口径违反 D235「口径一致性生命线」。

**剩**：真机复验（去重**不勾**状态下打 `/mpj/perf/n1`，红徽标应照常出现）。20261119 已发起：用户取消勾选去重 + agent 已挂载（pid=8496），14:23:17 打了一轮（n1×2 + 并发 device×4 + slow），**结果待用户反馈**。权威册=`docs/开发计划/20261119-1310-D237-去重关闭致N加1检测失效-评估册.md`（§七 实施记录）。

相关：[[project-pending-plans]]、[[reference-mpjdemo-sql-log-acceptance-endpoints]]、[[feedback-summary-row-visual-harmony]]、[[pitfall-reverse-probe-move-back-must-delete-original]]
