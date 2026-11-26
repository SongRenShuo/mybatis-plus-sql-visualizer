---
name: project-competitive-r44-panorama-20261121
description: 20261121 全景调研（r44
  性质）进行中——用户令全方位分析全分支+优劣势+新功能并输出调研报告与开发计划；主控直做竞品刷新已落 findings，子代理 A1
  仓内盘点在跑，撰写与提交未做
metadata:
  node_type: memory
  type: project
  originSessionId: sess_89603c38-9aa2-446d-aabe-f19632e9bc75
---

**2026-11-21 晚已收官**：终稿+计划册+四草稿入库并提交 `aa64f5c7`——现行权威记录＝[[project-r44-fullaudit-20261121]]（本文件只留流水线过程与竞品读数，后续状态以 fullaudit 档为准）。

2026-11-21 全景调研任务（用户令「全方位分析所有分支所有功能，优劣势+优化建议+新功能推荐，输出调研报告和开发计划文件并提交；子智能体后台执行并发限制1」，与 r40/r42 立项同口径的例行全量轮）。

**流水线状态**：A1 子代理（仓内盘点：全分支/功能清单/痛点 → `.local/research-20261121/findings-{branches,features,painpoints}.md`）后台运行中（TaskOutput 探测存活，非断线）；主控直做竞品刷新已落 `.local/research-20261121/findings-competitive.md`（消掉了原计划的 A2 阶段）；剩余＝撰写代理 B 产 `docs/调研报告/`+`docs/开发计划/` 两份交付件 → 主控审校 → 本地提交（-F 文件方式，不 push）。用户加令「主控可以参与 不要闲着 提高效率」。

**2026-11-21 关键读数**（引用前 git/API 复核）：
- 下一全新编号 **D241/V243/O-163**（r43 台账实测；DBX 候补 #342、R109 起未占）
- master 自 r43 基线 cd1b94562 净增 65 笔；**v2026.11.21-beta.1 已发行**（落账 d63351529）且 **update.json 主通道与 preview 通道均已同步 2026.11.21-beta.1**（D211 三处一致对 beta.1 成立）；发行后 [Unreleased] 重新攒（T-MultiModuleConsoleYield/T-McpToolDisplayDesc 等四件用户可感修复）；D222（${} 风险标记 UI）仍未见落
- Marketplace：我方 33633 下载 1,204（+54/1.5 天 ≈36/天回升）；32766 MyBatis SQL Analyzer 1,186（缺口 33→18 收窄）；MyBatisX 5,650,176（拉新第二波持续）；Mybatis Log Ultra 4,939/rating 4.76；**postgres-explain-visualizer 18804 有 23,681 下载**——EXPLAIN「读计划」赛道需求独立存在的证据，D223 R45 裁决时间价值加分
- zMyBatis：0 tag/0 Release 未翻转、main 自 051e98d5f（11-19 12:47Z）静默 1.5 天、#551 open（经 issues/551 兜查，pulls/551 两次非 JSON）、#552 open、分支 23——V242 扳机未响，D240 预案待命
- DBX：两天 8 笔高频活跃（tip cbb05f90b），EXPLAIN 停摆第 26 窗成立（fix(mcp) explain read-only errors 属 MCP 错误文案面不计入）

基线文档：r40/r42 全分支审计报告 + r43 竞品报告（docs/调研报告/ 37~43 轮在册）；机会池承袭清单与红线（不与 IDEA 原生重叠/不收窄 ORM/无请求不扩方言/不做付费订阅/DB 管理工具化/MCP 工具数竞赛）见 r40 §五 + r42 §五。
