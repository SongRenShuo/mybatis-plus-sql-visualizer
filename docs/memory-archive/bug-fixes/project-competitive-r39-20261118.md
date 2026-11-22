---
name: project-competitive-r39-20261118
description: 竞品对比第三十九轮（2026-11-18 短窗）：D213 达成/D212 对齐收官待授权/D214 未达成升级同批/zMyBatis
  扳机首响 Z-67 启用/D215 停等拍板
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4b4599f6-0c79-4646-a905-6818917a8df8
---

第三十九轮竞品横向对比（2026-11-18 01:36 落档，三车道并行：A/B 子代理 + C 主控脚本代跑）。

- 报告 `docs/调研报告/20261118-0136-竞品横向对比与功能建议-第三十九轮调研报告.md`；落地册 `docs/开发计划/20261118-0136-竞品对比第三十九轮落地-开发计划.md`（占 D215/V240；DBX #332~#341→#342；Z-67 启用；O-162 下一全新）。
- 窗口 1.35 天、24 笔（docs 19/fix 2/feat 1/release 1/chore 1）；发行 2026.11.17-beta.1 + 2026.11.17 正式版（T-IbatisAtShapeGate 164e6a884）。
- **D213 达成**：Marketplace 顶行=2026.11.17（1222715），11.16/11.17 两版全上架；**D211 差一步**：update.json 仍=2026.11.17-beta.1（用户改号即三处一致）。
- **D214 未达成升级**：两版上架未带描述区补词，goldendb/panwei 未命中、ibatis sql 9/9；处置=随 T-PreQuotedLiteral（fdd2b727a，[Unreleased]）转正批上传**强制同批**。
- **D212 翻转**：T-BranchAlignMerge 八支对齐收官，五车道落后统一 21 笔；dev→master 合流只差授权一跳（dev +347 笔，40+ 件待发行）。
- **zMyBatis 扳机③首响**（39 轮首次）：src/main 产品码 3 笔（引擎级投影重构 +529 行、IntelliJ Platform 2026.3 迁移、product-contract.md 重写为 263-only）；0 tag/0 Release 维持；威胁升满档，**Z-67 专项吸收评估待用户点名**（D215 停等拍板）。
- DBX：基线 `ff6316e80` 被 rebase 作废（API compare 降级取证），69 笔 fix 61%、EXPLAIN 停摆满 21 窗、MCP 50 第 11 窗、诊断线归零、app 0.6.71；窗尾 `94602d726`（下轮先验基线本体存在性）。
- 新面孔扫描恢复：828 家视野外零真新面孔；自家 issues GH 1 open/Gitee 1 open 无新增。
- 口径 ㉞：plugins/searchPlugins/reviews 端点均不返回票数字段，死寂计数中断；㉟：3 路子代理同发 2 败 1 活（额度 2 路口径再实证），第 3 路转主控脚本代跑零损失。
- 留档 `C:\Users\USER\AppData\Local\Temp\mpsv-r39\`（Git Bash `/tmp/mpsv-r39/`）；下轮判据 V240（§三）。

关联 [[project-pending-plans]]、[[feedback-concurrency-tight-main-control-direct]]。
