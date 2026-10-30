---
name: project-dev-vs-master-acceptance-manual
description: dev 相对 master 的人工验收手册（20261019 生成并提交
  5846ac3c）——手册写法：增量视角总表+分域步骤+「更新类行为变化」单列+正常现象速查+与全量文档分工
metadata:
  node_type: memory
  type: project
  originSessionId: sess_13bd728a-c47f-4204-a267-f528b9f57f9d
---

用户 2026-10-19 要求「生成一份 dev 对比 master 的人工验收文档，列出新增或更新了什么、UI 验收步骤是什么」。产出 `docs/验收文档/20261019-2305-Dev相对Master新增与更新功能人工验收手册.md`（提交 **`5846ac3c`**，dev）。

## 基线（写文档当刻实测，非估算）

master `6974aa9d` ↔ dev `fe1ea3db`，dev 领先 **464 提交 / 483 文件 / +40041−4419**。

## 手册结构（这套骨架可复用）

1. **准备**：装包路径、About 页核版本与 `MPV-Build` 内部构建号、demo 必须从 IDE Run、四页签入口。
2. **新增/更新能力总表**（16 行）：每行标「新增」还是「更新」+ 编号（O-145/V178、O-146~149/V179-182、R22/R31/R36/R60/R91/R92/R94、T-PreviewSideQuery、T-ToolbarIconSemantics、T-EnhancedCaptureDefaultOn、T-AgentPauseVisibility、T-AiContextLegibility、T-DialectStatusLiveRefresh…）。
3. **分八域验收步骤**（每步＝操作→预期）：方言与执行安全 / 日志与历史 / 预览与更多八项 / 执行·计划·Schema / 脱敏与 AI 上下文 / 转 SQL·XML / 增强捕获与双通道 / MCP·设置·更新。
4. **「更新类行为变化」单列 11 项**——相对 master 的**既有功能行为/默认值变化**（增强捕获默认开、暂停按钮隐藏、差异对比修复、历史「滚动到底部」、AI 上下文 v2、FieldFill 面板、图标 SVG 化、UI-021/022/025/029/030 等）。这一节是增量手册与全量手册的关键差异：验收人要按**新**行为判定。
5. **十分钟冒烟路径**、**验收记录表**（按域勾选）、**正常现象速查**（控制台永远明文 / 「复制 SQL」是原文 / 控制台通道无方言列 / 列表空=没开捕捉 / 降级横幅 / 窄栏裁切 / 暂停按钮隐藏 / Diff 窗口 z 序）。
6. **文档分工**：本手册=dev vs master 增量视角；全量判定依据=`20261016-T-DevFullUI-…-合流后全功能沙盒验收记录.md`（44 组矩阵）；分域细步骤=`20261018-2200-Dev新增功能人工验收手册.md`。

## 同类手册的既有两份（写入前先确认放哪）

- `20261018-2200-Dev新增功能人工验收手册.md`（`b2ec6c0e`）：面向人工走查的**全量**功能域操作手册（14 域 + 人工定性六项 + 正常现象速查）。
- `20261016-T-DevFullUI-…-合流后全功能沙盒验收记录.md`：沙盒自动化验收全账（44 组矩阵、30+ UI-0xx 缺陷行、十批实录），判断依据在这里。

## 教训

- 写「对比」类文档**先跑 git 取真实差量**（`rev-list --count master..dev`、`diff --stat`、按 `^A` 分 `src/main/kotlin` 包分布），别凭记忆或旧文档的提交数列。
- dev 分支在并行会话里高频推进：查 tip 与「我的提交在不在」用 `git merge-base --is-ancestor <sha> refs/heads/dev`（本次我的三笔确实在历史第 27 位，只是之后又叠加了 26 笔）——`git log -6` 看不到就以为丢了，是误判。

## README 已删除「dev 后续能力与验收边界」章节

2026-10-22 用户要求移除 README 中 `dev` 后续能力与验收边界的内容，已实施：删掉「本文包含 `dev` 的后续能力…」过渡句 + `## dev 后续能力与验收边界` 及其下 6 条要点（EXPLAIN 跨会话基线、AI 上下文 v2 隐私边界、FieldFill 安全模拟、诊断反馈逐次同意、自动方言分层校验、两份验收规程链接），README 从能力介绍直接进「能解决什么问题」，共 -11 行。master `a9124a40`，已 `--ff-only` 合入 dev 同 SHA（合并当刻 master==dev==origin 均为 `1a6283c8` 无差量，故不可能冲突）；**未 push**。

含义：README 不再承担「哪些能力尚未发版/待验收」的告知职责，发行范围统一以 CHANGELOG 与所安装版本为准；dev/master 的增量验收口径只剩本手册与 `docs/验收文档/` 下的正式规程，别再往 README 里加回 dev 专段。

相关：[[project-devfullui-acceptance-campaign]]（战役全账与 44 组矩阵）、[[pitfall-ui-promise-vs-model-decision-layer]]（本轮验收捉出的三个缺陷）。
