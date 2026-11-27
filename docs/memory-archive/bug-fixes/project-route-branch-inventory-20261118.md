---
name: project-route-branch-inventory-20261118
description: 2026-11-18 实测 route 的分支包含关系（patch-id 口径）与合入 master 的 8
  文件冲突图——security 100% 已吸收、hiddenbug/domestic 各差 1 笔且内容已在、route 落后 master 67
  笔；三支删除须等 route 进 master
metadata:
  node_type: memory
  type: project
  originSessionId: sess_77c4d0ef-5171-4662-a185-3b0b523b7768
---

# route 的分支包含关系与合流冲突图（2026-11-18 实测）

route tip `912768fb4`（origin 与 github 同 sha），master tip `e806373b9`，两者 merge-base `906039f29`。

## 包含关系：`is-ancestor` 全否，patch-id 才是真相

对全部 9 支跑 `merge-base --is-ancestor <b> 912768fb4` 一律 NOT_IN——route 是靠 cherry-pick/补丁等价吸收三支的，不是 merge。改按 patch-id 量真增量：

| 分支 | 未字面包含 | patch 独有 | 结论 |
|---|---|---|---|
| `t-security-audit-fixes` | 6 | **0** | 100% 已吸收 |
| `t-hiddenbug-perf-scan` | 3 | 1 | 那笔是「描述方言数 15→25」守卫声明；route 的 `plugin.xml` 中英两处已是 25 ⇒ 内容已覆盖 |
| `t-domestic-db-dialects` | 3 | 1 | 同上，同一处 15→25 |
| `t-sql-insight` | 590 | 486 | 未吞，仍是独立长线 |
| `t-rollback-preexec` | 14 | 12 | 未吞，等用户 GUI 验收 |
| `master` | — | — | **route 落后 67 笔**（只到 `906039f29`，不含当天合入的三条小线） |
| `dev` / `feature/kotlin-to-java` | 348 / 1083 | — | 各自独立线 |

route 自身领先 master **144 笔**。

## 合入 master 的冲突图（`git merge-tree --write-tree master 912768fb4` 预演）

**8 个文件冲突**，其中 3 个是源码、要真对账（不是「只 union CHANGELOG」那种文档冲突）：

- 文档 5：`CHANGELOG.md`、`docs/LESSONS.md`、`docs/架构设计/01-项目架构总览.md`、`docs/架构设计/03-开发与测试指南.md`、`docs/索引/开发计划.md`
- 源码 3：
  - `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerHistorySearchTool.kt`：route `79efe38f7`（按 `mcpMaskEnabled` 脱敏）vs master `c8d797b5d`（D219 detail 分档限流）
  - `src/main/kotlin/cn/song/mybatisplus/visualizer/share/ui/ShareDialog.kt`：route `a6a6db76f`（HTML 报告写盘移出 EDT，Y1）vs master `5b98f53b0`（PerfSweep30/31 卫生）
  - `src/test/kotlin/cn/song/mybatisplus/visualizer/convention/BrowserUtilConvergenceGuardTest.kt`：route `9e009b044` vs master `8820443fb`（两边各自改守卫计数/登记）

解完必须重跑定向——三处都是行为面合并，冲突解对不等于语义对。

## 清理门槛与用户决定

三支内容虽已在 route，但 route 不在 master ⇒ 现在删等于这批工作只剩 route 一份引用。仓库口径是**内容在 master 才删**。

用户 2026-11-18 决定：**只删本地 `backup/k2j-178-local-stale-20261117`（was `397d9fe9c`，178 轮快照、无远端副本、已被 179 收官取代；reflog 可回捞 `git branch <名> 397d9fe9c`），三支原样保留**，等 route 进 master 后一批删（本地 + origin + github + 工作树）。

删后本地剩 8 支，全部有远端对应、无孤儿。

相关 [[project-route-tsaf10-fork]]、[[project-round40-audit-20261118]]、[[reference-worktree-cleanup-recipe]]、[[project-pending-plans]]。
