---
name: feedback-dev-merge-needs-approval
description: dev 分支未经用户明确允许禁止并入 master——代理不得主动执行回流
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_818a7ec6-12fa-4a8f-9fb8-4c15496e82c7
---

**用户明令（2026-09-29）**：dev 没有用户允许，不要主动合并到 master。

**Why:** master 是发版基线，回流=发版前置动作（V26 双断言+门禁三件套+CHANGELOG 入册），时机与内容由用户在跟版决策时拍板（D25 回流时机即属待定案项）；代理擅自并 master 会打乱发版节奏。批次任务定例「dev 实施、禁止合并 master」由此升格为用户明令。

**How to apply:** 实施一律落 dev；需要动 master 或发版面（dev→master 回流、master→dev 合并、tag 移动、发行同步）先向用户请示，经明令后执行并落账盘点。相关：[[project-pending-plans]]、[[project-release-workflow]]。

**补（2026-10-19）**：用户点名「某分支需不需要合并到主分支」时，那只授权**判这一支**，不构成把 dev 并进 master 的授权。实测 dev 已领先 master 428 笔（383 文件 +35224−1413），我只出结论+建议路径（先把 master 那 3 笔 docs 并回 dev → 跑全量门禁 → 再合 master），并明确等用户发话；**删除已完全被 dev 包含的过期远端分支不属发版动作，可直接做**（见 [[pitfall-branch-merge-scope-collateral]] 反向形态补节）。
