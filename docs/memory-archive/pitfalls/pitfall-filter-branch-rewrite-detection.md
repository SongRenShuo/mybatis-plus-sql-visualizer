---
name: pitfall-filter-branch-rewrite-detection
description: 并行会话对 dev/集成分支跑 filter-branch 后提交 SHA 全变——判据是 tree 哈希 + diff 空，不是「祖先关系失败就报历史丢了」
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ff02b66b-55c2-4e81-926e-95b5b1a6d62c
---

2026-10-18/19 实测：另一会话对 `dev` 与 `t-sql-insight` 跑 `filter-branch`，两条分支所有提交 SHA 改变，`git merge-base` 回退到一个更早的点，`git merge-base --is-ancestor <旧SHA> HEAD` 对旧冻结 SHA 全部返回否，日志里先前引用的所有哈希都「消失」了。

**判据（先做这三步再下结论）**：

1. `git reflog show <branch>` 顶部出现 `filter-branch: rewrite` 即坐实是重写，不是分支被删/被 reset；
2. 对比新旧提交的 **tree**：`git rev-parse <旧>^{tree} <新>^{tree}` 相等即内容未变；
3. `git diff <旧> <新> --stat` 为空，再次确认仅 SHA/committer 元数据不同（parent 重写后 merge-base 会前移，这是副产物，不代表丢失）。

本例 dev `96fc74b9` 与新 `96fc74b9` 树同为 `92b1120b`，集成 `b79983a0` 与新 `b79983a0` 树同为 `4b258d41`，内容零差异，此前对旧 SHA 跑的门禁读数仍然有效。

**Why:** 哈希失效会立刻触发「历史丢了/别人动了我的分支/门禁过期」三种误判，其中「门禁过期须重跑全套」代价最高（本轮一整套 testShards 约 16 分钟 + 打包 + 三代兼容）。

**How to apply:** 见树相等就继续用已验证据，只在文档里补一句「重写后对应提交 X，树一致」把后续引用迁到新 SHA，旧 SHA 留作溯源；**不要**因为祖先判据失灵就重新合并、reset 或重建分支。相关：[[pitfall-cherrypick-port-contains-blindspot]]（另一种祖先判据失灵）、[[pitfall-parallel-session-batch-staging]]（并行会话会改共享引用）、[[project-sql-insight-campaign]]。
