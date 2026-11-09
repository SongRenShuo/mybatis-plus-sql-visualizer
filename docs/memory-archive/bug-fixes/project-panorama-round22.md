---
name: project-panorama-round22
description: 全分支功能全景审计第二十二轮（20261030 已落档）——dev 反转为最大新增资产（14 方言态 +38 待用户拍板合回）、
  合流债 281 笔零重复待拍板、master 52 项功能全清单、NEW-14~17 新增；上轮处方半数已执行
metadata:
  node_type: memory
  type: project
  originSessionId: session_43eae320-485f-442c-a0d0-5e9d62680ffe
---

# 全分支功能全景审计第二十二轮（2026-10-30 落档）

> **现状更新（2026-10-31，第二十三轮）**：dev 38 笔已随 `17785a67` 真合入 master 并随 beta.1 发行（「待用户拍板合回」已执行）；「合流债 281 笔」重算为 **247**（magpie 33/insight 214，且「magpie/insight 已合并」系方向误读已证伪）；14 方言对外口径已在架。现行状态以 [[project-panorama-round23]] 为准，本档留作第二十二轮原文。

**交付**：报告 `docs/调研报告/20261030-0831-全分支功能全景审计与持续演进-第二十二轮调研报告.md` + 计划 `docs/开发计划/20261030-0831-全分支功能全景审计第二十二轮落地与长期演进-开发计划.md`（索引已登记）。承 [[project-panorama-round21]]。基线 master `c94312a7`（盘点期间被他会话推进 3 笔 docs：0816 合并实施册 + f2c9d311 九棵分支去重排查 + rp10 立项——均已在报告 §前言声明并引用）。

**关键事实反转**：dev **不再是 master 祖先**（二十一轮「dev 清零」失效）——T-GaussDbDialect 八券 + T-XcDialects3 券 01~08 已在 dev 全部实施收口（tip `b29ca61e`，+38/−5；门禁 8492/0/0/9 + verifyGate 3/3 + 三真机渲染套件 PASS），对外口径 11→**14 方言**；唯一拍板点＝何时合回 master（红线：dev 并 master 须用户允许）。`feature/gaussdb-dialect` 已全部并入 dev 属**过期副本**。

**master 52 项功能全清单**（全旅程）：17 lineMarker/7 补全/9 设置页/11 MCP 工具/11 方言；设置实测 65 复选框（Boolean **54**）/77 控件；src/main 522 文件 138,708 行 + agent 70 文件；测试 815 文件。痛点覆盖六大原始需求全部闭环，唯一缺口＝优化建议层「有料未接」（`ExplainIndexAdvisor` 默认 `explainStructuredRender=false` 直送控制台不可见＝R45/D203 立项点）。微项：plugin.xml `:29`/`:105` 注释仍写「10 工具」（实际 11）。

**合流债 281 笔**（dev 38 + magpie 31 + insight 212）全部「验收完毕+f2c9d311 九棵分支去重零重复+冲突面已预演」：magpie 冲突 2 处（SqlListFragmentRenderer + 测试）且**分支 AGENTS.md＝D201 现成基底**；insight 冲突仅 SqlPreviewPanelResultView.kt 1 处 + 合流后须增量性能走查；dev 冲突仅索引文档 1 处。建议顺序 magpie→insight→dev（dev 最后因须用户拍板）。

**本轮新推荐 NEW-14~17**：NEW-15 国产数据库适配矩阵页（最便宜差异牌，dev 合回后做）＞NEW-14 设置场景预设包（54 Boolean 收敛的组织化解法）＞NEW-16 改动影响 SQL diff / NEW-17 慢查询聚合视图（观察池）。NEW-1~13 全部未立项维持有效。

**UX 债现状更新**：空态引导/首捕通知/T-LogListAnchor/BannedApi 守卫/R28-1 五券/rp9 合流——二十轮处方**半数已执行**；仍开放＝换源不重渲染（P2）、Community 入口无预判、巨文件 5 件 11,441 行第五次、真机验证债（O-145 真库四册/Mapper 让位复看/MCP261 261 真机/Prewarm R1/R2）、github 镜像 stale 59 笔。

**瓶颈排序**：合流 281 笔变现 ＞ 评价破零（双零 72 天，0816 册批次二）＞ 真机验证债攒批。计划不占 D/V/R/O 编号、不取代 0816 册与 rp10（**O-154/D205/V231 已被 rp10 占用**——口径 ㉕ 又一次当日实证；下一全新号 R102/Z-64/DBX #307 未占）。
