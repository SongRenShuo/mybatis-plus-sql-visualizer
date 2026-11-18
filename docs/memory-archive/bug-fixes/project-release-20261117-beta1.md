---
name: project-release-20261117-beta1
description: v2026.11.17-beta.1 内测发行（2026-11-17 进行中）——单修复
  T-IbatisAtShapeGate、发行提交 609ebd6df、CI run 44000441309，收尾读数待回填
metadata:
  node_type: memory
  type: project
  originSessionId: sess_03a21530-69c6-453b-973c-87cf391e43c2
---

# v2026.11.17-beta.1 内测发行（2026-11-17，进行中）

用户令（2026-11-17）：「参考之前的流程和规范，更新发行相关信息，gitee 工作流允许触发，github 的 ci 允许触发，创建发行版允许触发，记得先拉取下远程」。这是对本仓 `release-workflow` skill 与 `docs/发行说明/T-ReleaseChecklist-发行检查清单.md` 的直接执行授权（push / tag / CI / 双通道建版均在此令内）。截至本轮上下文截断，CI 仍在跑，发行链未收官。

## 已确认读数（实测）

- 版本 `2026.11.17-beta.1`：`2026.11.14`/`2026.11.16` 基号均已被正式版占用，按 [[feedback-beta-same-day-version-numbering]] 换次日新基号起 beta.1。本地 + 远端 `ls-remote --tags` 均无 `2026.11.17*`；GitHub `release view` 亦 not found（非重跑续发）。
- 前置：master、树干净、起始 HEAD `bef4fbcf4`；`git pull --ff-only` = Already up to date（新增远端支 `t-hd-absorb`）。并行登记冲突 ＝ 活跃任务 `T-SyncK2JMaster178`（沙盒 `T-SyncK2J178`，k2j 端口同步轮），本轮登记 `release-2026.11.17-beta.1` / 沙盒 `release-20261117`，收尾须 `done --name release-2026.11.17-beta.1`（禁裸 done）。
- 范围＝`2026.11.16..HEAD` 共 17 笔，唯一用户可感＝`164e6a884`（T-IbatisAtShapeGate：带 `@` 的自研 iBatis 语句名 `组名@命名空间.语句id`，点 gutter 能预览、`Alt+Shift+Q` 误报「未找到」；形状门 `STATEMENT_ID_SHAPE` 放行 `@` + JDBC 认领却渲染为空时 `resolveIgnoringShape` 兜底）。其余均 docs/计划/记忆整理（含 `09b35480d` 计划、`bef4fbcf4` LESSONS L80、架构同步 `092f54940`）。定向 36/0 + 反向 3 红。
- 物料（发行提交 **`609ebd6df`**，5 文件）：内测说明 `docs/发行说明/20261117-1002-内测-beta.1-iBatis自研语句名快捷键预览修复-内测说明.md`、CHANGELOG 加 `[2026.11.17-beta.1]` 段、gradle.properties / changeNotes（新 beta 块 2 条 `<li>`，评分句从旧 11.16 块移来，全文恰 1 次）/ preview.update.json。无新能力类别 ⇒ plugin.xml description 与 marketplace-custom-page.html 不动。
- tag 前判据 `python -I dev-support/check-change-notes.py --version 2026.11.17-beta.1` + `--self-test` 全绿（C1~C9，反向 9/9 恰红）。
- 一条龙 `pwsh -NoProfile -ExecutionPolicy Bypass -File dev-support/release-beta.ps1 -Version 2026.11.17-beta.1 -Notes <内测说明> -MarketplaceLite`：preconditions → gates → tag `2026.11.17-beta.1` @ `609ebd6df` → 双仓推送走令牌通路（日志 `x-access-token:***@`、GitHub 段代理不通直连、全程无 GUI 弹窗）→ dispatch `build-plugin.yml mode=release`（显式 `notes=` + `marketplace_lite=true`）run **`44000441309`**。本机 `pwsh` 7.7 可用。
- 收尾预备：按 skill §9b 在 CI 窗口把 `构建记录：…/actions/runs/44000441309` 写进工作树内测说明（`gitee-release.py:466-467` 建版时现读该文件 ⇒ Gitee 正文自带该行、省一次 PATCH）；`docs/变更记录.md` 与 `docs/MEMORY.md` 已预置本版行、读数标「待回填」。

## 截断时仍待办（勿按记忆冒充已发）

1. CI `44000441309` 结论取 `gh run view --json conclusion` / `ci-status.py wait`（已挂后台任务），不取 `gh run watch` 退出码。截断时 `test(1~5)` / `test(fast)` / `build` 均 in_progress。
2. release-beta.ps1 会在 CI success 后继续 GitHub readings（Release id / prerelease / 附件字节+sha256）与 `gitee-release.py --from-github --prerelease --yes` + `--verify-only`；若中断需按 skill §8 先核 tag / Gitee `/releases/tags/{版本}` / Marketplace 三态再续，禁盲目重跑。
3. 落账提交回填三处读数 + 内测说明构建记录行（已填），不进 tag；`update/update.json` 用户本人维护，本轮不动，`preview.update.json` 指向本版。
4. `parallel_tasks.py done --name release-2026.11.17-beta.1` 注销。

## 可复用要点

- Git Bash 下 `parallel_tasks.py` 路径反斜杠会被 MSYS 吞（`C:\UsersUSER...`），一律写正斜杠 `C:/Users/USER/.zcode/cli/parallel_tasks.py`（另见全局 MSYS 路径转换教训）。
- release-beta.ps1 已把 skill §9b 三条零改动提速内置：dispatch stdout 直接解析 run URL、CI 等待挂后台不空等、源码级 gates 零 Gradle。

相关：[[project-release-workflow]]、[[project-release-20261115-beta1]]、[[feedback-beta-release-needs-ci-gate]]、[[feedback-beta-same-day-version-numbering]]、[[pitfall-ci-release-notes-and-publish-run-gate]]。
