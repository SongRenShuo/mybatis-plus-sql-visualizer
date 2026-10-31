---
name: project-competitive-round28
description: 竞品横向对比第二十八轮（20261024 短窗插测轮，报告+计划已提交 014eeda9 未 push）——货架剪刀差第 8
  天、上轮五决策点零执行、DBX EXPLAIN 两窗零续作、zMyBatis 蓄水期判定
metadata:
  node_type: memory
  type: project
  originSessionId: session_ba6d7c55-843e-44c1-a9c7-1aa85823486a
---

# 竞品横向对比第二十八轮（2026-10-24 短窗插测轮）

**交付**：报告 `docs/调研报告/20261024-0123-竞品横向对比与功能建议-第二十八轮调研报告.md` + 计划 `docs/开发计划/20261024-0123-竞品对比第二十八轮落地-开发计划.md`，提交 `014eeda9`（master 本地，**未 push**；推债 4 笔=014eeda9+ExplainPwdPrompt 三笔）。承 [[project-competitive-round27]]。

**本轮读数**（快照 2026-10-23 17:13Z，窗口仅 1.35 天——短窗插测，日均折算仅供参考）：
- 我方 517 下载短窗 **+0**、0 票 0 评第 65 天；Marketplace 货架停 10.16 **第 8 天**，beta.1/2/3 三发内测（beta.3 Gitee id 1233935）60+ 条叙事全压内测通道——转正是最大单一杠杆（D165）。
- 上轮 D160~D164 五决策点**全部未执行**→本册刷新重编 D165~D169/V188~V192（未执行即作废语义）；下一全新号 O-150/R101/D170/V193。
- 关键词五升三平（sql explain 6/33 守住）——微涨属噪音波动非弹药生效；全市场评论死寂第八轮。
- DBX 85 笔/1.09 天（≈78/日观察史最密）但 **EXPLAIN 链第二窗零续作**（R45 窗口连续两窗敞开无撞车）、还原侧第十窗沉默、MCP 注册面第 12 窗冻结（28/10/3311 持平）；信创降温 50+→4 笔（xugu 转向迁移功能面）。候补 #254~#261。
- zMyBatis 2 笔/2.3 天骤降 80% 判**蓄水期**（vs 动能衰减，下轮看节奏是否收敛至周级零笔分辨）；Leap 交付链 1,574 行全 internal 零装配点、分发面 30 轮全灭。Z-51~Z-53。
- 巨文件 2264/2216/2072（SqlPreviewPanelExecution 十日 +116 未拆）；新盯防 MybatisToolPlus 35174；MyBatisProviderJump 34564 动能停滞 49→49。

**API 口径两条订正（后续轮次数据面障碍已扫清）**：①`/ratings`/`/votes`/`/reviews` 全 400（同 detail），评分改走 **`/rating`（单数）**（官网页真源，Playwright 交叉验证），其 `meanVotes`/`meanRating` 恒定假值弃用只信 votes 分布；②`searchPlugins` 的 limit/size **钳制 10/页**（offset 步进翻页），结果 `cdate` 是最近更新时间非创建时间——新面孔按 id 高位判定。
**工具坑**：DBX 本地克隆工作树停在 9-14 旧 checkout 且 agent_tools.rs 已迁路径，裸读工作树得 19/7/2367 假读数——**下轮起 DBX 一律 `git show origin/main:` 读数，禁读工作树**；盯防名单对照基线已落盘 `%TEMP%\mpsv-survey-r28\`（205 文件）。
**顺手补账**：`docs/索引/开发计划.md` T-SettingsSearchA/B 两行「仅计划」滞后已订正 + 27 轮计划漏登记补齐（随 014eeda9）。

**执行形态**：三路并行（A 抓取/B 盘点 Explore/C 专项），C 路首派撞会话并发限制后单派补齐——并发铁律 ≤3 再次生效。
