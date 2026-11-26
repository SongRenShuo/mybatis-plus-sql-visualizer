---
name: project-competitor-docs-open-source-exposure
description: 开源仓内竞品逆向/反编译分析内容的暴露风险分级与处置——20261007 首轮迁私有仓，20261012 收口（占位全删、11 份改名、闭源指纹清零、4 篇整篇移出、4 笔未推提交压成一笔）；含 filter-repo 影响面实测与「不清史」结论
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8337ffcd-9105-415b-a97e-062a26f9db4e
---

2026-10-07 用户提出：仓里「很多别的插件相关内容，例如反编译分析」放开源仓可能不合适。做了分级评估 + 全量清单，用户创建私有仓后当日实施。**2026-10-12 用户二次清理（本档主体），含一条硬口径与一条 filter-repo 决策。**

## 用户口径（20261012，写死，别再自作判断）

1. **占位文件本身即敏感面**——用户看到 `docs/调研报告/20261012-1552-CodeHelperPro-XML高亮与补全再深挖-调研报告.md` 这份占位后直接问「是不是该移内部或删掉」，随后追加：「`XXX高亮与补全再深挖（**已移出公开仓，无公开仓文件**）` 这句话也有些敏感，特别是 `CodeHelperPro-XML高亮与补全再深挖`」。**连索引行里的标题 + 「已移出公开仓/逆向细节」字样都算敏感**——等于在公开仓宣告「我们逆向过商业闭源插件、还藏了一份内部档」。
   ⇒ 终态要求：**公开仓零占位文件**；索引/变更记录/计划来源行一律写中性标签（如「第 N 轮 XML 高亮与补全能力对照调研（内部资料库）」），**不得出现竞品名 + 深挖/解剖/逆向字样，也不得出现内部仓文件名**。内部仓 README 铁律 3 已据此改写。
2. **「改名会牵动索引行 可以连索引行一起改掉，按你的推荐调整」**——授权连索引改，不必逐项请示。
3. **「整篇移出候选」四篇也移动**——审计给的候选清单获准执行。

## 判据（分级，别再整词全局替换）

- **要清（A 类）**：点名商业闭源插件的**实现细节**——混淆类名/方法名与行号（`P/c.java`、`i.b.c()`、`ak/f.java:27-34`、`view.c.e`）、反编译手段与产物（`fernflower 全量反编译 1150 个 java`、`%TEMP%\mbchp\src2`、`java-decompiler.jar`+JBR25 工具链段、「全源码 grep」）、其内部配置字段（`maxPrepareAndParameterLength`、`BatsLogConfig.desensitize`）、其 EP 注册链逐条（`plugin.xml:484`+`plugin_database.xml:110`+`ultimateVersionChecker`）、读其激活状态文件字段（`MyBatisCodeHelper.xml` 的 `Profile.valid`）。
- **不清**：①**只提及**竞品名（横向对比、能力矩阵、Marketplace 下载/评分/版本号）；②**平台 jar / 开源库 jar** 的反编译（本仓既定方法论「方法名以本地 jar 反编译为唯一权威」，对象是 IDEA 平台、MyBatis、flex-core 等）；③中文「逆向」＝**反向转换**语义（SQL→Java、INSERT 推导反向 SQL）；④思想级转述（attach 参数格式、动态代理包装思路）；⑤用户原话引用（即使含竞品全名）。
- **灰区（本次判定＝保留，可复议）**：开源/公开源码竞品的源码级引用（读公开源码并给 file:line＝署名式引用，非逆向闭源）；但本次用户口径已把**四篇整篇**源码级报告移出（见下），故「逐轮 file:line」与「整篇报告」的分界以用户当次指令为准。

## 20261012 实施（父仓压成一笔 `824af49`，未推）

1. **删 2 份占位**（`20261012-1552-*` 45.6 KB 已在内部仓 `0729010`；同族 `20260924-0442-*` 25.6 KB 一并处置）。
2. **11 份文件改名**去竞品名与「精华吸收/融合/移植」：`T-JdbcMultiline-…MyBatisCodeHelper-Pro精华移植`、`T-PerfBatch-吸收MyBatisCodeHelperPro…精华`（0000/1537 两份）、`P-立项-06-竞品精华融合`、`M38-Flex精华吸收`、`M37-竞品精华融合功能总结`、`M56-…与竞品精华吸收`、`架构设计/M37M38竞品融合新增功能`、`MybatisLogUltra精华融合`、`…-dbx精华融合`、`…吸收DebugTools attach即生效精华`；同步 33 处交叉引用 + 各自 H1 标题。
3. **逐行去措辞**（A 类 9 文件 + 审计点名的范围外 8 文件）：技术结论与用户原话一律保留。
4. **4 篇整篇移出**（用户令）→ 内部仓 `调研报告/`：CoolRequest agent 字节码增强、DebugTools agent 字节码增强、MyBatisSQLAnalyzer 对标、DBX 数据库工具深度调研；另 2 条同源 memory 摘要（`reference-coolrequest-agent-analysis`、`reference-debugtools-agent-analysis`）→ `研发记录/`。公开仓 22 处引用改中性标签、索引行去链接（**技术结论保留在计划与索引摘要里**）。
5. **内部仓新增 `研发记录/`**：①`20261012-公开仓去措辞-被移除的第三方插件实现细节存档.md`（40 KB / 19 组）——因为**「移出」不能只删不留**：这 19 组原文是「已从公开仓移除但未存档」的情报，就地删掉等于把情报丢在公开历史里；②两条 memory 摘要。

**终态复扫读数**：闭源竞品指纹（`MBCHP|com.ccnode.codegenerator|i.b.c(|src2|BatsLogConfig|maxPrepare|ultimateVersionChecker|paramLanguage|MybatisOgnl|OgnlMultiHost`）**0 命中**；「已移出公开仓/占位指针」**0 命中**；「逆向」仅 6 处且全良性；6 个被移文件的 basename 公开仓零残留；`docs/MEMORY.md` / `docs/索引/开发计划.md` / `memory-archive/README.md` 失效链接 **0**。
**链接校验陷阱**：本仓索引/README 里的相对链接是**以 `docs/` 为根**写的（如 `docs/MEMORY.md` 里写 `./调研报告/x.md`）。按「所在目录」解析会误报 532 条失效；正确做法＝先按 `docs/` 根试、不中再退回相对目录。

## 提交信息与合并

- **提交信息里不得出现**竞品名、逆向/反编译字样、内部仓路径——用户明确要求「这四次提交合并成一个并修改下提交信息 里面不要有敏感内容」。
- **压法（可复用）**：4 笔是 `origin/master` 之上的**连续顶部提交且从未推送**。先 `git rev-parse <top>^{tree}` 记树哈希 → `git reset --soft HEAD~4` → 用中性信息重新 `commit -F` → 再 `git rev-parse HEAD^{tree}` **核对树哈希逐字相同**（本次两侧均 `803764bb0855593ed306b56b1d6ff0425c33ce08`，证明零增减、也没卷进并行会话的改动）。同时用 `git for-each-ref --contains <4个sha> refs/remotes refs/tags` 核实**从未推送**（空即安全）。
- 压后：`git merge-base --is-ancestor` 逐笔实测「已不在 master 历史」；旧对象只以本机未引用对象存在（实测靠 reflog `master@{2}~{5}` 与 `ORIG_HEAD` 存活——**所以只跑 `gc --prune=now` 是白跑**，必须先把引用点清掉）。
- **✅ 2026-10-12 已执行物理清（用户「清」后跑的外科版）**：`git reflog expire --expire-unreachable=now --all && git gc --prune=now`（耗时 2m12s）。压前读数 → 压后：松散对象 **44158 → 5**、pack **3 → 1**（in-pack 11687 → 49562）、master reflog **1631 → 1486 条**（145 条不可达条目过期）；四个旧 sha 全部 `git rev-parse` **不可解析**（`fatal: Needed a single revision`）；`.git/logs/` 与整个 `.git/` 内**搜不到**四条旧提交信息文本。完整性自检：`git fsck --connectivity-only` **退出码 0、无 missing**；期间并行会话的新提交（`d9951e7a` T-SqlTemplateUx 立项）完好、本档的压缩提交 `824af49` 仍是祖先。
- 残留（无害）：`.git/ORIG_HEAD` 仍写着旧 sha 文本 `b4371718…`（41 字节）——只是悬空哈希、指不到任何对象，也不含信息；git 会在下次 reset/merge 时覆写。要抹掉直接删该文件即可。

## ⚠️ 新增暴露面：GitHub 镜像（2026-10-22，双仓立项时提出，用户尚未拍板）

用户决定同步 GitHub 镜像（Gitee 仍主仓）时，我按本文判据扫出：跟踪文档里仍有 **20+ 份含闭源竞品名**（`MyBatisCodeHelperPro` / `MyBatis-Flex`），分布在 `docs/变更记录.md` 与 `docs/变更记录/*`、`docs/发行说明/*`、`docs/内容营销/老版本承接-*.md`、`docs/开发计划/*`、`docs/memory-archive/bug-fixes/*`、`docs/MEMORY.md`。

- **不等于新增泄露**：这些文件在 Gitee 上早已公开；本文 20261012 的判据仍是「只提及竞品名（横向对比/能力矩阵/下载数）不清，实现细节才清」，而这些多是名字级提及。
- **但可见度会变**：GitHub 被搜索引擎收录的强度明显高于 Gitee，等于把「竞品对比」抬一个量级。**清不清是产品决策，我没有替用户决定**，当时只把这个风险摆在清单里等用户表态。
- 若用户要求清，仍走「不再新增 + 清对外发布面」的既有折中，**不要碰 filter-repo**（见下节结论）。

## filter-repo 重写公开历史的真实影响面（2026-10-12 实测取数，用户追问后结论＝不清）

用户问「只影响当时的几次提交吧，会有其他影响吗」——**不是**。提交哈希含父提交哈希，改任一笔则其后全部换 SHA：

| 项 | 本仓实测 |
|---|---|
| 换 SHA 的提交数 | master 共 **2740** 笔；最早含 `CodeHelperPro` 的提交 `3ceb66eb` 之后 **2559** 笔全部换 SHA（内容未改也换） |
| 文档里的 SHA 引用 | 本仓文档大量引提交号，重写后在 Gitee 全 404，须逐处改文案（最费工的一步） |
| tag | 现有 **25 个 tag** 指向被重写提交；Marketplace/Gitee 发行版按 tag 走，tag 一变「已发布版本 ↔ tag」对应关系要重挂 |
| 分支 | 三棵树（master / dev / feature-kotlin-to-java）——只重写 master ⇒ `git merge dev` 会重演已合并历史（巨型冲突）；`--all` 全 refs 重写 ⇒ dev SHA 也全变 |
| 克隆/工作树 | 三棵树、子代理沙盒 clone、其他机器副本均持旧 SHA，与重写后无共同祖先 ⇒ 只能重新 clone |
| 推送 | 必须 force-push（可能要临时关分支保护）；他人 pull 过须 `reset --hard` |
| 服务端 | Gitee 旧对象在 GC 前仍可能按直链 SHA 可达；fork/镜像/缓存清不到 |
| 工具坑 | filter-repo 默认拒绝在非 fresh clone 跑（须 `--force`）；**跑完会删 origin remote**，要手动加回来再 push |
| 不受影响 | `docs/内部/` 是**嵌套独立仓**（父仓 gitignore 排除），父仓重写碰不到；未提交内容与 `build/`、`.intellijPlatform/` 等未跟踪目录也不受影响 |

**结论：不清。**（2026-10-12 用户看完影响面后**明令「不要 filter-repo」**——已推送历史保持原样，今后不要再提该方案。）工作树已干净，要看历史得主动 `git log -S` 去挖（低概率）；代价是全仓 SHA 洗牌 + 25 tag 重打 + dev/master 祖先断裂 + 三棵树重取 + force-push + 一轮全仓改 SHA 文案，对「观感/合规」诉求不成比例。
**折中（推荐）**：不再新增此类内容 + 清干净对外发布面（Gitee 发行正文 2026-10-07 已处理、Marketplace changeNotes 本就干净），历史留着不动。
**若将来决定清，正确顺序**：冻结协作并通知副本 → 裸克隆上 `git filter-repo --replace-text <敏感串清单> --invert-paths --path <报告路径>` → `--all` 一起重写 → 重打 25 个 tag → force-push master/dev/tags → 三棵树重新 clone 建 worktree → 事后全仓搜旧 SHA 改文案。

## 早前轮次（2026-10-07，仍有效）

分级清单与许可证定档（Apache-2.0 平台/MyBatisX/zMyBatis/DBX＝一档；GPL 的 CoolRequest/DebugTools 与无许可的 MyBatisSQLAnalyzer＝二档；**商业闭源 CHP＝三档**）；嵌套私有仓搭法（`git clone <私有仓> docs/内部` + `.gitignore` 排除 + **禁 submodule**，理由＝父仓会记录私有仓 URL）；内部仓 origin `https://gitee.com/songrenshuo/sql-visualizer-internal-docs.git`，体积仅 226 K；**跨工作树读取内部仓**四法（推荐按绝对路径直读；要写则内部仓自己的 worktree **须 `--detach`**；junction 会被 `rm -rf` 穿透；禁 submodule）；`git clean -xdf` 会连 ignored 的 `docs/内部` 一起删（本仓多树清理流程最易踩）；**父仓历史旧版本里旧内容仍在**；Gitee v2026.9.8 发行正文两处已用 `PATCH /repos/{owner}/{repo}/releases/{id}` 就地改（HTTP 200、tag/附件未动）；agent 子项目 GPL 同源自查已完成（**未发现代码级同源**，六路证据在内部仓 `审计记录/`）。

关联 [[project-doc-conventions]] [[project-pending-plans]] [[project-xml-highlight-completion-gap]]。
