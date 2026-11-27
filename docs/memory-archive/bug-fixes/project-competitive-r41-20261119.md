---
name: project-competitive-r41-20261119
description: 第四十一轮竞品对比（2026-11-19）：PR#524 合并触发 V240 扳机、zMyBatis 真执行引擎成形、MyBatisX 1.7.9 首动、P0 迁移「已合未发」、D230~D232 登记
metadata:
  node_type: memory
  type: project
  originSessionId: sess_215ea059-5a37-4dd7-b3f6-0bf936ed2486
---

第四十一轮竞品横向对比（2026-11-19 01:44，窗口 r40→本轮约 19.4h，master 净增 54 笔）。四份产物：三草稿（`docs/调研报告/20261119-0144-插件增量盘点/市场面与舆情/真仓盯防-第四十一轮-草稿.md`）+ 终稿（`20261119-0144-竞品横向对比与功能建议-第四十一轮调研报告.md`）+ 配对计划（`docs/开发计划/20261119-0144-竞品对比第四十一轮落地-开发计划.md`，占 D230~D232、V240 原地刷新）。留档 `%TEMP%\mpsv-r41\`。

关键读数（判后续动作前先 git/API 复核）：
- **zMyBatis（luceat-lux-vestra/zMyBatis）三连跳**：PR #524 已合并（2026-11-18 03:43Z，V240 扳机触发、Z-67 应升建议立项）；caller-authority 分支已删并入 main；同日真执行引擎线成形（#530~#546 11 个 PR：RemotePreparedStatement→真库 PostgreSQL prepared→TypeHandler 桥→PreparedExecutionPlan→PreparedBackend）+ PR #548 open 补结果呈现；仍 0 tag/0 Release；新观察分支 `feat/447-shipping-action-cutover`。
- **MyBatisX 1.7.9 上架**（11-18 19:18+08，375 天首动，兼容 251~263 不变、无 changelog）——主控抽查 cdate 复核命中。
- **我方**：1,085→1,129（≈54/天近期最高）；sql visualizer 1/61、hql preview 1/1 卫冕、sql preview 26→25（榜首仍是 251 下载休眠盘 28245，位次文本敏感第四窗）；jpa sql 33/50、mybatis sql 117/154、ibatis sql 9/9 承袭清账；goldendb/panwei 未命中（D214 维持）。JPA Buddy 官方文档实证自 IDEA 2026.2 起数据库连接管理移交平台 Database Tools（叙事弹药）。还原赛道新占位者 Mybatis Log Ultra(26550)/PandaCoder。
- **P0 迁移**：三小线（perfsweep31/standalone/hd-absorb）已合流删支，[Unreleased] 攒到单批历史最大 29 条（Added 9+Fixed 20）压在发行链外——唯一 P0 从「已做未合」变「**已合未发**」；route 分支自增 12 笔（方言 14→25 家+真机捕获取证+TSaf10 对齐），合流价值继续上升。
- **DBX**：静默打破（compare ahead 12 笔全 UI 外围），EXPLAIN 停摆顺延第 23 窗（R45 窗口期第六窗成立），app v0.6.72-beta×3。
- **停等拍板结果（20261119）**：D230 已拍板**按推荐立项（Z-67 专项吸收评估），先不实施**——评估册暂不出，随发行/合流窗口再启动；D231 发行节奏未表态维持停等。五件文档已提交 master `bc7c0eff8`（未推）。
- 编号台账：D230~D232 本轮占；D236/D244 grep 命中是 PID 误匹配教训；下一全新 D233/V241；DBX 候补 #342；DBX 窗尾 `100f410`、zMyBatis 窗尾 `14fdc4e`。
- 作业形态：3 路后台子代理 2 败 1 活（user concurrency limit exceeded，口径㉟再次实证）——A/C 车道转主控直做零读数损失；B 车道翻页曾按每页 20 计步作废重抓（searchPlugins 每页实为 10 条）。

关联 [[project-round40-audit-20261118]]、[[feedback-concurrency-tight-main-control-direct]]。
