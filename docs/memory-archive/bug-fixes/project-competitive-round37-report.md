---
name: project-competitive-round37-report
description: 竞品横向对比第三十七轮（20261110，窗口 5.77 天/477 笔/发行 4 版）调研报告与落地册——我方下载历史最快档但位次首次回撤、Hibernate Debugger 官方曝光位爆发、DBX 语义诊断破零、zMyBatis 治理收尾；含 D211/D212/V238/O-161 与 F 队列 15 项全压 dev 的实测结论
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3111cf66-9bce-4284-ae1a-46c00894655d
---

第三十七轮竞品横向对比（承 r36 格式，2026-11-10 落档）：

- 产出：`docs/调研报告/20261110-0111-竞品横向对比与功能建议-第三十七轮调研报告.md`（331 行 12 节）+ `docs/开发计划/20261110-0111-竞品对比第三十七轮落地-开发计划.md`；提交 `f658a74f`，后续状态订正 `1879bee5`、GitHub 权威读数补录 `86702396`。
- 窗口：r36 快照 2026-11-03T22:41:57Z → 本轮 2026-11-09T17:05:56Z（**5.7667 天**），本地 `2b100b17`→`b3958e35` **477 笔**（feat 54/fix 61/docs 189/test 43/ci 32/release 9/perf 8/merge 51），窗内发行 v2026.11.4/11.7/11.8 正式 + 11.9-beta.1 内测——开档以来最大功能供给单窗（iBatis 2.x 全面支持、GoldenDB 第 15 方言、运行期占位可填值、SQL 列表风暴九券、263 双适配、安全审计批）。
- 市场读数（A 路子代理 612 请求全 200 零失败零代理）：我方 **721→891（+29.5/天历史最快档）**、三条 update（11.4/11.7/11.8）全 approved+listed、双零第 80 天；但**位次开档以来首次明显回撤**：sql preview 20→28、jpa sql 27→32（存量排序扰动 + JetBrains 第一方 Hibernate Debugger 曝光位 33→25 跃过我方），sql explain 6→5、sql visualizer 第 1 稳定。全市场评论死寂第 17 轮，新面孔 7 家（R102~R108）全量级无关。
- 竞品信号：Hibernate Debugger（34028）**曝光位爆发 174→1,343（单版 1,162、日增 203、vendor=JetBrains、FREE、新版无 notes）升 P1 盯防**；MyBatisX 挂审 51 天的 1.7.8 过审推峰 +6,349/天且又有新版在审；CHP 周更中断一拍；MybatisToolPlus 发版停止（O-161 销案）；TableBridge 0.3.5-alpha 破零动作；DuckDB 2026.1.2 功能型。JPA Buddy 无新版连续两窗 >1500/天触发「下载基盘整体抬升」改判。
- C 路读数（子代理撞并发配额后主控后台命令接管）：DBX 纯增量窗订正为 `d4951da82..39a77b5ff` **154 笔**（fix 106/feat 24），内核四读数三平一破——MCP 50 第 9 窗、EXPLAIN 11 方言第 19 窗零增量、**diagnos 破零**（60da8419e 未知表别名语义诊断，候补 #320 对位我方 Inspection 环）、备份线 179 行属 Doris 测试扩面；DBX 火力全在外壳。zMyBatis `b4bd9fc`→`da44445` **5 笔全治理线零产品码**，六信号首次全部有权威读数（见 [[reference-github-api-token-authoritative-readings]]），威胁低位。
- 编号台账：本册占 **D211/D212/V238/O-161**、DBX 候补 **#320~#325**（下一全新 #326）、新面孔 **R102~R108**（下一 R109）；下一全新 D213/V239/O-162；Z-66 待用。新增口径 **㉚**（跨轮窗尾哈希必须留档，r36 未留 DBX 窗尾用 #316 成员位置反推）、**㉛**（GitHub 权威读数走项目令牌）、**㉜/口径注⑨**（beta 不上 Marketplace 属内测通道口径，不算未上架异常）。
- 决策点（停等用户）：D211 update.json 补账（落稿后订正目标值 2026.11.8→**2026.11.10**）；D212 dev 合流棒次重排；W1 文稿发射 + 关键词池启用；v2026.11.9-beta.1 转正时点；V238 脉冲双基线口径。

**落稿后状态订正（用户指出两处过期，主控 git 全分支复核 `1879bee5`）**：推债 16 笔已清零、T-Ibatis2Gap L3+真机验收已完成、四轮内测转正 **v2026.11.10 正式版**（Marketplace 顶行 1216590、下载 910）、iBatis 三支 `t-ibatis-*` 已清、dev 拓扑变为 331/101；**F 队列 15 项全部已交付但逐个 ancestor 实测均 NOT in master**——当前第一瓶颈是 dev→master 合流（D212），不是「还没完成」。另两支 `t-security-audit-fixes`（+60）/ `t-sql-insight`（+562）是在轨大车道，不是可删残留。

相关：[[reference-github-api-token-authoritative-readings]]、[[feedback-check-all-branches-before-implementing]]、[[feedback-subagent-quota-recovery]]、[[project-pending-plans]]、[[project-w1-n24-zero-review-campaign]]。
