---
name: project-competitive-round7
description: 竞品横向对比第七轮（20260918，入库 master ca57460 未推送）——zMyBatis
  全仓库源码通读结论（真引擎求值/Leap 重写未接线/精华 Z-1~Z-13）、Marketplace 快照、DBX v6 清单、新编号
  N-24~N-27/O-30~O-33
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1a91843b-202d-4004-ada3-f2c60f953e8b
---

# 竞品横向对比第七轮（2026-09-18）

报告：`docs/调研报告/20260918-0200-竞品横向对比与功能建议-第七轮调研报告.md`（master 提交 **ca57460，未推送**）。三子智能体并行（zMyBatis 源码通读 / Marketplace 刷新 / DBX 增量），主智能体抽查复核；Marketplace 原始 JSON 留档 `%TEMP%\jbmkt_r7\`；zMyBatis 本地克隆 `%TEMP%\zmybatis_deep_r7`（临时目录，下轮复测需重新克隆）。

## zMyBatis（Marketplace 30447）源码通读核心结论

- **反差**：Marketplace 4 个半月零动作（实测 updates 仅 3 版、since 253、110 下载），GitHub 却 104 提交（42 个 dependabot，真人≈2 韩国账号）、主代码 10,424 行/测试 12,813 行。五个月火力全投向**未接线**的完整重写（代号 Leap：admission 门禁+参数契约溯源+classloader 隔离求值），生产入口跑的还是 4 月启发式 v1。下版 sinceBuild 253→262 主动弃旧 IDE。
- **最值得偷师=真引擎求值**：不自研解释器，直接丢给真 MyBatis 3.5.19 `XMLScriptBuilder.getBoundSql(params)`，字面量化只处理 parameterMappings→`?` 队列，约 500 行拿全标签语义（MyBatisEvaluator.kt:109-126）。
- **精华清单 Z-1~Z-13**（报告 §7，带文件:行证据）。P1 四条：Z-1 真引擎高保真模式（须配 Z-10 classloader 隔离——MyBatis 会向 shaded OGNL 全局注册 accessor 污染宿主）、Z-2 `${}` 注入确认勾选闸门+溯源 tooltip、Z-3 按 mapper 签名推断参数类型契约、Z-8 include 依赖图（条件=先核实我方 XML 预览 include 展开现状）。反面教训不吸收：error-as-SQL（错误伪装成 SQL 注释）、ignoreUnknownTags 静默剥标签、EDT 链上 PSI 格式化。
- **威胁低位**：七个环它一个都没有（无 Wrapper/日志捕获/EXPLAIN/CRUD/MCP/历史面板，求值必须连库+手填参数）；路线是「可信执行器」不是可视化调试。**定案：不做可信执行器路线对标、不做单点 eval 内卷**。

## 其他要点

- Marketplace（2026-09-18 01:13 快照）：本插件 290 下载（+37→+15/日，发行红利消退非崩）、0 票 0 评论第四周、Marketplace 页仍 9.14 画像（N-20 未执行）。CodeHelperPro 直装版 9837 复活发 3.6.6（修 MCP `run_mybatis_sql` 序列化异常——其 MCP 面已有真实用户）；付费版跳票 17 天+（validate_mybatis_mapper 仍挂 notes）；MyBatisX hasUnapprovedUpdate=True 首现；两巨头（14522/15075）均分齐降 0.03~0.05。
- **口径变化：Marketplace 匿名端点已拿不到评分票数**（/reviews、/ratings 400，/rating-granular 403）；GitHub API 匿名配额会被代理出口 IP 占满 403（换 Atom feed 或本地克隆实取）。
- DBX 增量 84ae93a→31ca3f7 单日 49 提交（v0.6.15，star 19,952），红线面 31/49；**v5 清单 #27~#34 八条全部仍可引用**（行号漂移 +1~+20）；新清单 v6 仅 #35（MySQL `\G`/`\g` 提交前剥离）/#36（标识符引号=方言×实例配置）两条 P3。
- **新编号：N-24（画像重发+边界声明+首评，市场动作不立计划）~N-27（高保真求值立项评估）**；存量 O-30（include 展开核实）~O-33。N-22/N-23 已被全景审计占用（T-UxOnboard/T-ExplainIndex）。
- 合流债新形态：K2J 线落定后 master/dev **双向分叉 135/31**，合流从机械动作变成策略决策（dev Kotlin 资产长居形态/逐项 cherry-pick vs 双向合并），见 [[project-pending-plans]]。

见 [[project-release-workflow]]（N-24 随发行执行）。
