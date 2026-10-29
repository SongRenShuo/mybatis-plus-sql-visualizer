---
name: project-competitive-round30
description: 竞品横向对比第三十轮（20261026 标准日窗轮，报告+计划已落档未提交）——Marketplace
  上架在窗口内完成但零留痕、D177 分叉已收敛、本轮实质是录制与发行周
metadata:
  node_type: memory
  type: project
  originSessionId: session_a5cf3aa3-fe1e-4c1f-b9c6-9af6c299300c
---

# 竞品横向对比第三十轮（2026-10-26 标准日窗轮）

**交付**：报告 `docs/调研报告/20261026-0230-竞品横向对比与功能建议-第三十轮调研报告.md`（405 行）+ 计划 `docs/开发计划/20261026-0230-竞品对比第三十轮落地-开发计划.md`（90 行），**未提交**。承 [[project-competitive-round28]]（r29 无独立记忆文件，报告在 `docs/调研报告/20261025-0115-…第二十九轮…md`）。

**本轮最大反转：上轮第一风险自行消解，但换了形态**。Marketplace 上架在窗口内完成——货架顶行 `2026.10.16`(1195760) → **`v2026.10.23`(id 1202730, approve=true, 2026-10-25T15:32:11Z)**，上的产物＝**第二次同 tag 重发**版（内层 jar 与本地 `b3076975…` 逐字节一致、changeNotes 26 条同序）。**但零留痕**：`grep -rn 1202730`（排 build/）全仓 0 命中；`docs/MEMORY.md:310` 与发行说明到 HEAD 仍写「Marketplace 未上传」；`verifyMarketplace` 的 verifier home（`F:\gradle-home\mpv-verifier-home\marketplace`）最后写入停在 10-24 03:15、10-25 无痕迹。风险从「停更」换成「**结果达成、过程无审计**」。

**窗口**：r29 快照 2026-10-24T17:13:10Z → 本轮 2026-10-25T17:06:00Z，**0.9950 天**。

**读数**：本插件 527 下载（+4）、0 票 0 评第 67 天；45 家合计 16,563,598、净增 **+1,875**、中位 2、前五占 **90.71%**；**全市场只有本插件发新版**（其余 44 家 updates 顶行 id 全等）、零新评论第十轮、零 votes 变化。八词位次**几乎静止**：sql explain 6、sql preview 31（total 75→76）、sql log 86、sql visualizer 1、mybatis **raw 218 未变 / 去重 218→216**（跨页重复 id `[8640,15359]` 所致）、mybatis log 76、jpa sql raw 32 / 去重 31→32、sql formatter 缺席。**上架 + 描述编辑两轮都没换来位次** ⇒ 位次主要由历史下载量与关键词匹配度决定。

**工程面 25 笔（c3a3ee51→9d91c8b8）实质是录制与发行周**：只有 2 笔动 src/main（`8619a3af` 方言冲突不再拦执行 + 报错文案改报渲染方言；`e7a27700` runner 枚举为空时按目标方言重映射 exec 文件再枚举，共 4 文件 +75/−9），11 笔 `record/` 录制脚本（对运行时零影响）、4 笔发行/门禁/脚本、1 笔 merge（`c075efc8` 全取本地侧）、7 笔 docs。

**上轮 D 决策点**：D172 上架 ✅（过程无审计）；D177 推债分叉 ✅ **已收敛**（`origin/master..master`=2 / `master..origin/master`=0，远端缺陷版被覆盖，`moduleKey` 远端 4→**11**、`prewarmExecTempFileOnBackgroundThread` 远端在场）；D174 巨文件 ❌ 仍 **5 件**（2383/2323/2314/2101/2072，两件 explain 宿主又 +46 行）；D173 W1+N-24 ❌ 第十六轮；D175 R100 ❌ 仍注册未消费；D176 R45 ⏸ 未立项。CHANGELOG `[Unreleased]` **仍空**；索引台账缺 **14** 条。

**发版尾部两处新空账**：①`update/update.json` 仍 `2026.10.23-beta.2` ⇒ Gitee 抢先体验通道推不出比货架更新的版本（`beta.2 < 2026.10.23` 判「已是最新」）；②`docs/变更记录.md:22` 写产物 7,177,707 字节、本地实测 **7,176,707**（sha256 `b3076975…` 三者一致，属抄写误差）。tag `2026.10.23` 已两次前移：`2ef051d5`→`5f04fa63`→**`00153b0b`**；Gitee 发行版 1235579→**1238151**（第二次未删重建、创建时间保持 `2026-10-25T07:45:46+08`），新增 `dev-support/gitee-release-update.py`。

**竞品两端方向相反**：zMyBatis 8 笔/1.007 天（7.94 笔/日）——`e4f54d37` **遗留执行岛整体删除**（65 文件 **−7,512 行**，`MyBatisExecuteProxyAction.kt` 966 行已删，主执行面 1,147→**181 行**），装配点仍 34 未扩散，execution/ 9 文件 2,401 行；**Marketplace 仍 26.04.02（207 天零更新）⇒ 上轮定的升级扳机未触发**；产品契约收窄（`docs/product-contract.md`：非零绑定**只证明 PostgreSQL BIGINT 一种**，其余 fail closed）。DBX 63 笔/1.029 天（**61.2 笔/日，+77%**，首次出现 merge commit）；MCP 注册面**第 14 窗冻结**（28 工具名逐位相同）、还原侧**第 12 窗沉默**、**EXPLAIN 链 12 窗来首次零增量**（11 方言不变）、解读/体检层**连续第二窗为零**；版本 app v0.6.54 / agents v0.2.150 / packages v0.4.130；stars 27,735。

**R45 最小形态本轮明确**：我方**已有** 6 条体检规则（`util/ExplainIndexAdvisor.kt:138-145`），但**默认形态（内嵌原生控制台）下用户看不到**——`SqlPreviewPanelExecution.kt:1466-1475` 的 `ExplainPanelChannel.decide(enabled = settings.explainStructuredRender…)` 默认 `false`，`DiagnosticReportFormatter.kt:22-25` 注释明写「内嵌原生控制台的结果行无程序化读取面，建议节只挂在 MCP 面」。⇒ R45 最小形态＝**把已有 6 条规则接到默认形态**，不是新做体检。

**编号**：本册占 **D178~D183 / V203~V208**（取代上轮 D172~D177/V197~V202）、**Z-57~Z-61**（异步边界快照+探针 / 日志插值契约测试 / 失败诊断码 / 写安全闸门纯函数化 / 参数三态来源）、DBX **#275~#284**（T-SQL 批处理切分 / DDL 剥 schema / Windows 只读残留 / 单飞队列 unknown 阻塞 等）。**下一全新号：O-151 / R102（R45 立项占 R101）/ D184 / V209 / Z-62 / DBX #285**。

**🆕口径三条**：①`searchPlugins` **不能传 `max`**（一律 400），实际参数 `search/limit/offset`，limit 被钳制 10；②`/rating` 的 `meanRating`/`meanVotes` 是**全站常量**（本轮 45 家全返回 4.06398），**任何情况下不得当单插件评分**，只能按 `votes` 直方图自算；③位次须 raw / 去重双口径并列（跨页有重复 id，本轮 mybatis 词 2 处）。

**🆕工具坑**：heredoc 里写 `'\ufffd'` 字面量会被吞——修乱码须用**位置法**（`re.finditer('\ufffd+')` 取 run 区间、从后往前替换）+ 替换字用 `\uXXXX` 转义；修完**跨调用回读校验**（AGENTS.md 已记 Bash python 写文件可能不落盘）。

**执行形态**：三路并行（A 抓取 262 请求/B 盘点只读/C 专项），C 路首派断线后重派成功；主控复核十二点，订正子智能体结论两处（B 路「上架零留痕」的表述范围——Gitee 侧重发在 `docs/变更记录.md:22-23` 有完整留痕，只有 Marketplace 上架本身零留痕；产物字节数差异须与「sha 三者一致」并列陈述）。

## D183 复核补记（2026-10-26 用户追问「详细说下」后查实）

**`verifyMarketplace` 从「无证据」升级为「确定没跑」**，判据是产物目录而非时间戳：

- `verifyMarketplace` 的产物目录是 `build/reports/pluginVerifierMarketplace`（任务定义 `buildSrc/src/main/kotlin/mpv-verification.gradle.kts:248` 起，`reportsDir = layout.buildDirectory.dir("reports/pluginVerifierMarketplace")`）——**全盘（含 `mpsv-release` 等全部工作树）搜不到该目录**。
- 对照：`verifyGate` 跑了——`build/reports/pluginVerifierGate/IU-{243,253,262}.*/plugins/<id>/2026.10.23/verification-verdict.txt` 三份在场，时间戳 **10-25 21:54~21:55**，读数（弃用 41/52/124、实验性 98/96/96、243 scheduled-removal 5）与 `docs/变更记录.md:22` 逐位吻合。
- 两个 verifier home（`F:\gradle-home\mpv-verifier-home` 与 `C:\Users\USER\.gradle\mpv-verifier-home`，**后者是前者的 junction，实为同一目录**，131 文件同一时间戳）的 `marketplace` 子目录最新写入都停在 **10-24 03:15**。
- **时间线**：10-25 21:54 `verifyGate` → 22:39 第二次同 tag 重发落位（`00153b0b`）→ 23:32 Marketplace 上架 → 之后无任何提交与文档更新。

**`releaseGate` 组成**（同文件 `register("releaseGate")`）：`buildPlugin → verifyGate → verifyMarketplace → testShards → e2eFakeIde`；`verifyMarketplace` 在线（刻意不加 `-offline`/`-ignored-problems`）、覆盖 243/253/262、`dependsOn("buildPlugin")`、硬编码代理 `127.0.0.1:7890`、首次下载约 3.33GB、判定走 `checkMarketplaceVerdicts(reportsDir, dev-support/verifier-marketplace-baseline.txt)`。

**同类事故第三次**：`docs/发行说明/T-ReleaseChecklist-发行检查清单.md`（T-RC-01 唯一权威清单）§四已有「回读断言」三档判定（含「不执行回读不算发布完成」），§七 事故登记已有 2026.9.18 断供 + 2026.9.23 零痕迹两案例，第二案例根因原文「回读时机与语义，不是执行漏步：重传后没回读……且过审后本地文档仍写『B5 待重传』——已上架版与仓库叙述不一致，状态漂移再次现形」。本轮形态与第二案例一致 ⇒ **清单没问题，是 RC-R7 没被执行**。完整判据表见 [[pitfall-release-action-leaves-no-trace]]。

**仓库快照已过时**：会话期间并行会话又推 3 笔（`3c1335ce` GUI 自动化选型调研 4 份 / `0bed0e4d` BASELINE 标定节 / `c0f80652` 录制经验文档），全部 docs 与 `record/`，`git diff --stat 9d91c8b8..HEAD -- src/main` 为空 ⇒ 结论不受影响；但报告里「HEAD=9d91c8b8、未推 2 笔」已失效（现未推 2 笔为 `c0f80652`/`0bed0e4d`）。

## D183 裁决与执行（2026-10-26，用户逐项拍板）

用户令：**①现在做；②不补跑；③暂缓**。

- **① 已执行**——状态文档订正**四处**（`docs/MEMORY.md:310` 正式版条目、发行说明 §兼容性、`docs/变更记录.md` 新增上架条目，**外加顺带订正的 `docs/MEMORY.md:718`**：v2026.10.16 正式版条目同样写「Marketplace 未上传」，但该版实为货架在售版 id 1195760）；未动 `:311/716/717`（beta.1~beta.3 内测版条目，「未上传」对它们正确）。计划 §九 已补三条实施记录，V208 判据已收紧（注明内测版条目保留该字样是正确的，勿误删）。
- **② 不补跑**——用户理由：上架已被平台接受（`approve=true`/`listed=true`）即 JetBrains 侧校验通过，比本地复现更强（对照 2026-09-23「上传 ≠ 接受」）。该判定依据已写入发行说明与变更记录，避免下一轮当未解释缺口重提。
- **③ 暂缓**——`update/update.json` 仍 `2026.10.23-beta.2`（只有用户能改）。

**③ 的机制澄清（20261026 两轮：先误判、后经用户实测纠正 + 反编译订正）**：`beta.2 < 2026.10.23` 这个比较成立（平台 `VersionComparatorUtil` 正确判 `-beta` 预发布段为小）。**⚠️ 首版结论「提示不会来自 Marketplace、磁盘安装无市场订阅」已被证伪**——用户实测「本地离线安装也可以收到插件市场的提示，因为插件 id 一致」，主控按其指引反编译 IDEA 2026.2.3 核实：`UpdateChecker.collectUpdateablePlugins()` 筛选条件只有 `!isBundled() || allowBundledUpdate()`、**无安装来源判定**。⇒ **Marketplace 通道本来就通**，beta.2/beta.3 用户本就会收到市场更新提示；`update/update.json` 升版（③）的作用只是「让插件自带 Gitee 通道也提示」。完整机制与四条反编译证据见 [[reference-ide-plugin-update-check-ignores-install-source]]。

## D178 执行（2026-10-26，用户令「D178 可以做」）

**先核账再补，结论与上轮报告不完全一致**：上轮报告称「14 条用户可感改动一条未入账」，逐条核对后**其中 13 条已在 `## [2026.10.23]` 段内**（两次同 tag 重发各补 2+2 条，其余本就在段内）；**唯一真缺账＝跨源列升格（`27429da6`）**——`grep -c "CrossSourceColumnEvidence\|换个数据源"` 在 `CHANGELOG.md` 与 `update/preview.update.json` 均为 0。

- **已补进 `## [2026.10.23]` 的 `### Fixed` 段**（段内 bullet 74→75）。**归属判定**：`git merge-base --is-ancestor 27429da6 2026.10.23` 为真 ⇒ 该笔**在已发布包内**，故归 `[2026.10.23]` 而非 `[Unreleased]`（与两次重发补记同例）。
- **`[Unreleased]` 维持为空是正确的**：tag `00153b0b` 之后的提交全是 docs 与 `record/` 录制脚本，`git diff --stat 9d91c8b8..HEAD -- src/main` 为空，零用户可感改动。
- **未动 `update/preview.update.json` 的 changeNotes**：那是已上传货架的对外清单，改本地会造成与货架包不一致；该条建议随下一版 changeNotes 一并体现。

## D180 是什么（2026-10-26 用户追问后查清）

**W1＝写（备料三件双语），N-24＝发（破零三件套），同一件事的两半**；连续十六轮零稿，两项都落在「只有用户能做」。完整定义、出处、V47 合规边界、零弹窗硬约束与判据见 [[project-w1-n24-zero-review-campaign]]。

**20261026 用户裁决**：**第三件（Gitee 内测用户邀请清单）明确不做**（与 R41 废弃机制件的定案一致）；前两件展开——首评引导帖＝长文稿（跨过「不愿当第一个评论者」的心理门槛，渠道＝更新通知+README）、评分引导话术＝短模板（**已有硬规格** `T-ReleaseChecklist` §1.2 ≤60 字、中性、无激励无模板评语；现状只有 changeNotes 末行一句，README 无）。**待用户定**：首评引导帖的「帖」落哪个渠道（更新通知+README / Gitee 发行说明置顶 / 插件内已有交流群入口）。详见 [[project-w1-n24-zero-review-campaign]]。

**提交落位**：`1a041248`（7 文件：报告+计划+`CHANGELOG.md`+`docs/MEMORY.md`+`docs/变更记录.md`+`Marketplace发布流程-说明文档.md`+发行说明；提交信息写文件走 `git commit -F`，中文），工作树干净、未 push（ahead 4）。**未动**：`update/update.json`（铁律）、`update/preview.update.json` 的 changeNotes（已上传货架的对外清单，改本地会与货架包不一致）。
