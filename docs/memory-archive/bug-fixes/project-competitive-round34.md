---
name: project-competitive-round34
description: 竞品横向对比第三十四轮（20261031 落档未提交）——beta 双发未上货架、脉冲首次回落 25.7/天、FR-0045 五支+推债零远端留底、
  zMyBatis 仓归属口径订正（真仓 luceat-lux-vestra）、DBX 2 feat 边缘试水 EXPLAIN 16 窗零增量；本轮占 O-158/D206~D207/V232~V233/#307~309
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 竞品横向对比第三十四轮（2026-10-31 落档）

**交付**：报告 `docs/调研报告/20261031-1013-竞品横向对比与功能建议-第三十四轮调研报告.md` + 计划 `docs/开发计划/20261031-1013-竞品对比第三十四轮落地-开发计划.md`，已落档+开发计划索引已补行，**未提交未推**（用户未令提交）。承 [[project-competitive-round33]]。窗口 **1.3597 天**（r33 快照 2026-10-29T17:04:28Z → 2026-10-31T01:42:28Z），A 路 278 请求 0 失败（直连一次成功，代理兜底未触发），留档 `Temp/mpsv-survey-r34/`（`delta-table.json` 供 r35 直接 diff）。**执行形态变异：首派三路全撞账户并发限流（1302）+B 路两遇 Start Plan 模型流忙；按用户令「直接恢复不新派，并发临时调整为 1」用 SendMessage 逐路串行恢复，三路全部完成**——限流期勿新派代理、先恢复失败代理。见 [[feedback-subagent-quota-recovery]]。

**我方两连发 → 已转正（20261031 16:11）**：beta.1（id 1255993）＝**十四方言收官**（11→14，GaussDB 独立/瀚高/海量）+**增强捕获方言无关化**+SQL Server 分页/premain 单位两真机修复，门禁 8525/0/0/9+e2e 26/0；beta.2（id 1256540）＝T-PreviewPsiDialect 预览 CCE 修复+agent 控制台降噪，releaseGate 14m54s 全绿。**v2026.10.31 正式版已发行并提交上架**（`c11fa88c`；Gitee id **1259606**、GitHub Release **429805298**、7,356,449 字节；含 T-XmlBraceNested 动态标签补全修复；门禁 **8532/0/0/10**+e2e 26/0+verifyGate 3/3，**verifyMarketplace 三轮 SSL 握手中断未取读数如实登记**）；Marketplace 顶行仍 1206029/v2026.10.30＝待过审；下载 687（+31/约1.1天）。能力面：方言 14/MCP 11/kt 527。**8599/0/0/11 系 fr45 分支口径不进 master 账**。

**市场读数**：脉冲四读数 20.0→21.3→33.1→**25.7/天首次回落兑现**（V225），但长尾高于发版窗前基线——定性升级「发版推峰、峰后长尾不塌回」，推荐基线「发版窗 30+/天、长尾窗 25/天上下」（D204 承袭待拍板）；656 下载、双零第 74 天；45 家**全体正增长**首轮（零增量 0 家 vs 上轮 7 家）、集中度 89.11% 回落；JPA Buddy 跟版暂停五窗结束（263.7307.112）；mybatis 词位次逆势升 3。**口径修正：Guard 家族 r33「发版零增量」本轮次窗补涨（+19/+10）——发版效果判读必须给满一个观察窗**。

**新增风险首位 D207（已全兑现）**：用户令「先推送到 gitee」→ 五条 t-fr45-* 分支（48 笔）+ master（`ab40a179..18ebe30b` 快进）推 origin，`ls-remote` 六 ref 核对在场（integration=d631b2f3 与分支权威门禁一致）；随后用户令「推送之前先清理分支工作树」→ 清理 3 分支（feature/gaussdb-dialect/mpsv-release/t-docs-gif-record-1025）+ 4 工作树（+mpsv-sql-insight-gate），**长路径坑复现**（`git worktree remove` 全报 Filename too long，改用 PowerShell `\\?\` 前缀 Remove-Item + `git worktree prune`，配方 `.local/cleanup-worktrees.ps1`）；再推 GitHub 镜像——master 162 笔 + 七支（fr45 五支/magpie/insight）全部成功，**双仓各分支落后全归零**（V233 双仓侧全兑现）。

**竞品端**：DBX 22 笔/11 人＝「维护窗延续+边缘小幅试水」（feat 2 笔均边缘：连接参数编辑器 e0875d0d/导出前缀 d4122cc4；**内核四读数全平**——MCP `#[tool(` +0、EXPLAIN diff 空 11 方言**第 16 窗零增量**、体检层 0；app 0.6.62/packages 0.4.136/agents v0.2.156；stars 28,166 +23）——R45 窗口第六次有利。zMyBatis：**仓归属口径订正（新增口径 ㉖：竞品克隆 remote 必须与 Marketplace vendor 对账后才可沿用历史基线）**——本地克隆挂在 kmingyan/mybatis-sql-analyzer 镜像上属错仓，真仓＝**luceat-lux-vestra/zMyBatis**（vendor algorist KR、包名 com.algorist 互证、r33 基线 d845a8d 在真仓验证有效、MyBatisExecuteAction.kt 仍 181 行）；真仓基线后 2 笔（#473 测试+#475 **src/main 破零笔** ContractParameterInputDialog EDT 惰性构造 +172），Marketplace 冻结 214 天扳机未触发；威胁低位维持。**下轮先重挂克隆 remote**。

**台账**：本册占 **D206~D207/V232~V233/O-158**、DBX 候补 **#307~#309**（下一 #310）；O-154/D205/V231 被 T-ReleasePerf10 占、O-156/O-157 被 T-PreviewPsiDialect/FR-0045 占；**Z-64 仍空**、R102 空；下一全新 D208/V235/O-159。0816 册 D200~D204 承袭：五项中仅 D203 立项完成（FR-0045 分波），W1 **第二十轮零稿**、D201 尾注残留扩到 **:43/:47/:50 三处**（新发现 :50 孤儿残句）、D202 巨文件 **11,445（+4，第六次）**。

**下一轮待办**：①zMyBatis 本地克隆重挂真仓 remote；②V232 复测（长尾下限跌破 20/天则修订「不塌回」；v2026.10.31 过审后取新推峰读数）；③beta 上架后脉冲形态；④DBX 试水是否升级。

**同日再后续（20261031 傍晚，用户令「从 master 新建分支和工作树实施盘点所有内容」）**：**G1 已合入 dev**——dev `ab40a179` 先 `--ff-only` 快进到 master `087fcace`，再合 `t-fr45-integration`（`9cad9f52`，3 处**文档**冲突：CHANGELOG/变更记录/索引，均取并集）、合 `t-magpie-gems`（`072db331`，3 处冲突：双语 bundle + `BannedApiSourceScanTest` 白名单，均取并集；**预演曾报零冲突，先合 fr45 后基点漂移**）。`t-sql-insight` 按用户令**不动**，全程未碰 master。随后从 dev `072db331` 建四条工作树并行：`t-dev-tidy`（注释债/尾注/AGENTS.md）、`t-dev-split`（巨文件五件拆）、`t-dev-r45`（结构化通道转正/索引建议迁 IDE/OB 文本树）、`t-dev-newbatch`（环境体检/换源重渲染/Community 置灰/版本亮点）。用户令：**只跑定向、最终全量、任何分支不合 master**。

**同日后续（20261031 17:3x 第 77 版盘点刷新）**：R15 六张演示动图**已落站并部署验证**（docs 仓 `96ca427`/`789309b`/`9b8ce39`/`8390bde`/`260a640` 入库 + `4a7e19b` 部署读数回填，`public/demo/` 六文件在）；T-RecordSkillConsolidate 已执行（skill 下沉 `.agents/skills/`）；分支/工作树 6/6（fr45 四单支本地+双远端已清，仅留 integration）。
