---
name: project-release-v2026-11-21-zhengshi
description: 发行 v2026.11.21 正式版（11.21-beta.1 转正）——发行提交 a05816485 与 tag 已双仓推送，CI
  run 44888598532 轮询中，Gitee/Marketplace/落账未做
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a69b3975-fc27-4c19-8616-2b722ceca860
---

2026-11-21 下午发行正式版 `2026.11.21`（今日内测 2026.11.21-beta.1 转正，基号未被占用；用户经确认拍板正式版，见 [[release-wording-zhengshi-clarify-beta]]）。

已完成：发行提交 `a05816485`（5 文件：CHANGELOG 正式段 + gradle.properties bump + html 正式块 16 li + preview.update.json + 发行说明 `docs/发行说明/20261121-1710-预览工作台收尾与增强捕获修复批-发行说明.md`）；tag 前判据 check-change-notes.py 双绿（C1~C9 OK + 9/9 反向恰红）；tag 双仓推送成功（push-all.ps1 令牌通路，x-access-token 读数在案）；CI `build-plugin.yml mode=release`（不带 marketplace_lite、无 notes 显式传）run **44888598532** @ a05816485 派发，后台轮询挂起（ci-status.py wait）。

**Why:** 发行链走到一半，断线后严禁按记忆续跑（Z-36）；下一步依赖 CI 结论。

**How to apply:** 续跑先核三者（git tag -l 2026.11.21 / Gitee API / Marketplace 三档）。CI 绿后依次：GitHub Release 回读（ci-status.py release）→ `python dev-support/gitee-release.py --version 2026.11.21 --notes docs/发行说明/20261121-1710-…-发行说明.md --from-github --yes`（正式版**不带** --prerelease）→ `--verify-only` → Marketplace 三档回读并提醒用户网页手工上传 → 落账提交（docs/变更记录.md 发行条目 + docs/MEMORY.md 索引行，发行后补账不进 tag）。**转正合并口径实证**：CHANGELOG 把 [2026.11.21-beta.1] 与 [2026.11.19-beta.2] 两段按能力面合并成单一 [2026.11.21] 正式段并删两段（beta.2 段此前被回填扩充过、含 beta.1 后的 agent 修复批条目），[Unreleased] 清空并入；html 只留正式块、删 v2026.11.21-beta.1 块；范围锚点=上一正式版 2026.11.17。beta.1 之后 4 批增量（McpToolDisplayDesc e6e5acee7 / AgentStatusOldJvm 05fd3dff1+2 95ecbea44 / DriverInternalSqlFilter 8ef85fe56 / MultiModuleConsoleYield eb9711735）读数均以计划册实证：反向 git 补丁 2红→16/0、2红→28/0、1红→5/0、定向 35/0 反向 2 红。发行后留待办：**k2j 端口同步以本 tag 为冻结点**（同步节奏=只在发行 tag 冻结）；update/update.json 待用户本人手改；master 工作树上有 4 个未跟踪的 docs/调研报告/20261121-1707-*.md 草稿属并行会话，发行提交未卷入。
