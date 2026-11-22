---
name: project-round40-audit-20261118
description: 第四十轮全分支审计+竞品对比（2026-11-18）：patch-id 拓扑改写合流批次、D220~D229 新候选、V240 判据刷新、过期结论订正
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e23fd1da-084b-4e4f-97f9-e30d131698e5
---

第四十轮调研（2026-11-18，提交 `27a54cfa4`，未推）：三车道草稿+终稿在 `docs/调研报告/20261118-0631-*` 与 `docs/开发计划/20261118-0631-长期打磨路线图-增量更新-开发计划.md`（编号续 D220~D229）。

关键实测结论（判后续动作前先重核）：
- **合流拓扑改写**：route ⊃ security(59)+hiddenbug(57)+domestic(9) 全部实质 patch（各仅差 1 笔）；insight ⊃ dev（0 缺失，+197）；两线互不包含 ⇒ 主合流线二选一＝D220 裁决件。建议批次：perfsweep31 → route → master→dev 反哺 → 主线 → 三小线（rollback/standalone/hd-absorb，均 CI 全绿）→ 删支（D221 待点头）。
- **过期结论订正**：「T-SecurityAuditFixes 已并入 master 20261109」失效——patch 全部不在 master。
- **竞品**：zMyBatis 分支 `refactor/caller-authority-invocation-523` 19h 80+ 笔 + **PR #524 open**（已实测复核）⇒ 扳机追加「PR #524 合并」；DBX 超静默 2 笔（EXPLAIN 停 22 窗，R45 窗口第五窗成立）；sql preview 位次 27→26、榜首 28245 仅 251 下载 ⇒ 位次文本敏感、D214 收益实证；CHP 30 元代理码+破解生态＝信任营销点。留档 `%TEMP%\mpsv-r40\`。
- 综合结论：**供给重心在合流不在新功能**（三车道独立同判），唯一 P0 是把 40+ 件用户可感增量发出去；R45 EXPLAIN 读计划是唯一未占窗口（裁决前置：自研面板 vs 原生旁注）。
- 车道 A 子代理撞并发限额（user concurrency limit exceeded）转主控直接实施——与 [[feedback-concurrency-tight-main-control-direct]] 一致；B/C 两路后台子代理正常完成。
