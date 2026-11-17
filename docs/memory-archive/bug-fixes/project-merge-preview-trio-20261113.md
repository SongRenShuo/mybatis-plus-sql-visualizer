---
name: project-merge-preview-trio-20261113
description: t-merge-preview-trio 已合入 master（d25e2a653，8 冲突逐 hunk）；v2026.11.13-beta.1 已双通道发行成功
  （run 43365621616 14/14、GH prerelease + Gitee id=1304101 同 sha256）；工作树与已并分支已清理，仅远端 trio 分支待定去留
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ef53fa11-bc64-4038-9ef0-1459d8198f89
---

T-MergePreviewTrio（2026-11-13）：从 master `1aa71fbdc` 建合并分支 `t-merge-preview-trio`，按序并入三个已收口任务
（`ed60443fe`←t-preview-stmt-type-scope、`43d1c488b`←t-preview-inlay-native-yield、`e11905139`←t-notify-tune + 修复 `6376e2e33`），
中途又并进 StmtTypeScope 两轮修复与 Keymap 告警两轮。**用户点名后已合入 master：`d25e2a653`**
（master 侧当时已领先 30 笔，属真合并而非快进）。

冲突 8 处，全部逐 hunk 裁决、没有整边选：
- `plugin.xml`：两边各加一个 `postStartupActivity`（`WorkspacePropertiesRepairActivity` / `DialectGapTip`）→ **两个都留**；
- `CHANGELOG`：按段重组，`[Unreleased]` 保留两边全部条目（Fixed 里 master 的 StartupReadLockFreeze / LastUsedEdt / WorkspaceHeal 排在前）；
- `LESSONS` / `db-env-provisioning SKILL` / `dev-support/README`：trio 侧为空 → 取 master；计划索引两行都留（按日期先后）；
- 备份两文件（add/add）：master 的 `a8e8e6d09`（11-13 七牛演练 9 引擎 + 采集器五振修复）新于 trio 的 `88ea0d2ca`（11-12）→ 取 master。
合并态：compile 通过、定向 **159/0/0**（含 `PluginXmlStructureGuard` / `DynamicEpComplianceGuard` / `BundlePlaceholderArity`）。

**v2026.11.13-beta.1 发行（同日）**：release 提交 `236461589`（版本位 → `2026.11.13-beta.1`；changeNotes 置顶 8 条 + 评分句只留首块；
CHANGELOG 新增 `[2026.11.13-beta.1]` 段并清空 `[Unreleased]`；内测说明 `docs/发行说明/20261113-1210-预览判型与锚点与Keymap告警-内测说明.md`）。
本地门禁 `ChangeNotesRatingLineGuard` 3/3 + `DistributionComplianceGuard` 3/3；push 后 `push-test` `43360778843` **全绿**。
- 首发轮 run `43360813034` 两红：`test (4)`＝**Maven 依赖下载失败**（网络瞬时，非测试红）、`verifyplugin`＝**INTERNAL_API_USAGES**（`setShortcutSet`，见 [[project-comment-shortcut-keymap-20261113]]）；
- 修复 `f43068228`（改用公共 `registerCustomShortcutSet(CustomShortcutSet.EMPTY, null)`，守卫再禁 `setShortcutSet(`）；tag `2026.11.13-beta.1` **前移**到该提交并双仓 `-f` 推（版本号不变、从未发布成功 ⇒ 按「同版重发」处理）；
- 第二轮 run `43363371747`：**13/14 绿，只 `publish` 红**＝发行说明文件名口径不符（见 [[pitfall-ci-release-notes-and-publish-run-gate]]）；
- `mode=publish` + `run_id` 复用被「只接受 run 级 success」挡回 ⇒ 第三轮 run `43365621616` 带 `-f notes=…-内测说明.md` 重跑 `mode=release`；
- 文档提交 `87a37df67`（发行清单补 RC-P4c）与 `aaaec5544`（内测说明补构建记录行）随后一并推双仓。

**收官读数（2026-11-13）**：第三轮 run `43365621616` **14/14 全绿** ⇒ 双通道上线：
GitHub Release `2026.11.13-beta.1` `isPrerelease=true`、asset 8,177,568B、sha256 `52faa7e7…`；
Gitee release `id=1304101` prerelease、sha256 与 GitHub **一致**（`dev-support/gitee-release.py --from-github --yes --prerelease`
本地跑，**CI 只发 GitHub，Gitee 必须本地补一步**）；`update/update.json` 按约定未动（用户手改项）。
master 本地/双远端均 `aaaec5544`，tag `2026.11.13-beta.1` → `f43068228`。

**清理（用户令「清理」后执行，同日）**：本会话三个工作树+分支（`t-comment-shortcut-keymap` / `t-merge-preview-trio` /
`t-preview-stmt-type-scope`）+ 5 个已并分支（`t-notify-tune` / `t-preview-inlay-native-yield` /
`t-startup-freeze-docwrite` / `-prefetch` / `t-startup-readlock-freeze`）已删，两个车道工作树（各 ~440MB）一并移除；
`.worktrees` 885MB → 5MB（只余 demo 仓嵌套工作树）。**远端 `t-merge-preview-trio` 仍停在 `6376e2e33`（Gitee+GitHub 都是），
删远端属对外动作、等用户点头**。口径见 [[reference-worktree-cleanup-recipe]]。

教训：`t-notify-tune` 没同步设置复选框计数守卫（`PluginSettingsPersistenceTest` 49→51，分支定向覆盖不到、合并态 CI 才红）；
`build-plugin.yml` 上传编译产物的条件收窄到 `gate || release`（tests 模式 build 不产出、上传必「No files were found」）。

相关：[[project-preview-stmt-type-scope-20261112]]、[[project-preview-inlay-native-yield-20261112]]、
[[project-notify-tune-20261111]]、[[project-comment-shortcut-keymap-20261113]]、[[pitfall-ci-release-notes-and-publish-run-gate]]