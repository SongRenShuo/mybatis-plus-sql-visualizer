---
name: project-competitive-round27
description: 竞品横向对比第二十七轮（20261022）报告与落地计划已提交 82ee88b1 未 push；编号台账推进至
  O-149/R99/D159/V182，下一全新 O-150/R101/D165/V188
metadata:
  node_type: memory
  type: project
  originSessionId: session_f6245c66-c454-4df2-8f8c-a625269ac501
---

# 竞品横向对比第二十七轮（2026-10-22）

权威文档：`docs/调研报告/20261022-1745-竞品横向对比与功能建议-第二十七轮调研报告.md` + `docs/开发计划/20261022-1745-竞品对比第二十七轮落地-开发计划.md`（提交 `82ee88b1`，**未 push**，随 D164 转正编排或用户指示推）。承接 [[project-competitive-round26]]（第 26 轮=20261012-1335）。

## 本轮头条（10 天窗 2026-10-12→10-22）

- **发射兑现**：10.13 同号重发 Gitee id 1205516、10-15 列中；10.16 列中（版本级下载 41）；beta.1/beta.2 内测双发（Gitee 抢先体验通道，止步 Marketplace 外）。master==origin==`cf0c7deb` 零推债、master↔dev 双向 0、GitHub 镜像三端推平。
- **我方 517 下载**（+103/11.7 天 ≈+8.8/日，上轮 +15 回落）；关键词一平四降（sql explain 6/33 守住；四降=池子变大+噪音涌入非竞品超车）；0 评第 63 天。
- **巨文件红线失守**：SqlLogPanel 2264/SqlPreviewPanelExecution 2166/SqlPreviewService 2072 三件 >2000（T-M53-II 后首回吐）→ D161 拆分批候选。
- **竞品两端**：zMyBatis evaluation 轴收束（R89 失去对照对象建议降级）转 CI 自证循环 69%+新开 Leap 交付链（Z-50）；DBX 943 笔/16 天、信创 EXPLAIN 破零（华为 DWS 预估计划走插件宿主 API，#249）→ R45 竞争信号刷新时间压力首现；还原侧第九窗沉默；MCP 注册面 11 窗冻结。
- **候选池清空销号 14 项**（R34/R36/R37/R42/R46/R48~R54/R60~R62 已全部落码）；新候选 R100（mapper 配对跳转接线，图标 `2175a632` 已注册未消费；同域新苗 MyBatisProviderJump 34564 翻倍档）。
- 本册占 D160~D164/V183~V187；用户决策件=beta 系转正路径（D164）/W1+N-24 第十三轮（D160）/巨文件拆分（D161）/R100 形态（D162）/R45 立项（D163）。

## 本轮新坑与口径

- **Marketplace `/api/plugins/<id>/detail` 端点服务端 400**（`Parameter 'family' is incorrect` 全 id 必现、与代理无关）→ 基座端点 `/api/plugins/<id>` 兜底，compat 从 updates 行 since/until 推导。口径⑮。
- 上轮逐词结果成员清单未留档 → 「回归」判定不可做；本轮起登记 57 组 id×词在场矩阵。
- 上轮日均未随基线留档 → 倍数列不可算，用定性口径；死寂天数可严格外推校验（+9 天整吻合）。
- 10.13 的 update.json 换版提交缺失（RC-R3 该版未执行，10.9→10.16 跳版）——窗口已过，流程注意项。
- beta.1 的 Gitee 发行数字 id 仓内未登记（beta.2=1228088 有）——小补账。

## 执行形态

三路并行子智能体恢复可用（第 26 轮账户级拦截未复现）：A 路 Marketplace 抓取（212 逻辑请求 0 最终失败）+B 路 Explore 只读盘点+C 路两线专项，主控承复五点全对上。原始留档 `%TEMP%\mpsv-survey-20261027\`（125 文件）+`%TEMP%\zmybatis-r27\`。zMyBatis 上轮克隆已清，本轮经 Marketplace+GitHub search API 重新定位仓库 `luceat-lux-vestra/zMyBatis`。
