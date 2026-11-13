---
name: project-release-20261112-beta2-rerelease
description: v2026.11.12-beta.2 同版重发进行中（2026-11-11 用户令「更新文案后重新发行」）——该版本此前已发行过（tag 82b065dda / Gitee 1297061 / GitHub 441318632），本轮要按「删旧 Release → tag 前移 → publish → Gitee --from-github」重发
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a534afd8-5588-4158-ad1d-2a6ff7bd53ca
---

# v2026.11.12-beta.2 同版重发（2026-11-11，进行中）

用户验收通过后令「更新文案后重新发行 2026.11.12-beta.2」。**关键事实：这个版本早就发过**——不是首版发行，而是同版重发，按 `2026.11.11` 的先例走。

## 旧的已发行状态（本轮要覆盖）

| 项 | 值 |
|---|---|
| tag `2026.11.12-beta.2` | @ `82b065dda`（旧，F3/F4 那一版） |
| Gitee 发行版 | id **1297061**，prerelease=true，附件 `mybatis-plus-sql-visualizer-2026.11.12-beta.2.zip` |
| GitHub Release | id **441318632**，prerelease=true，同名附件 |
| 上一轮门禁/publish | gate run 42971909719 15 成功、publish run 42974112566（旧产物） |

## 本轮要发的内容

`b0fa9b52b` 及之前的 F5~F9 + 文案优化：`39304e963`（F5 目录级判据）、`7ab0d5335`（撤回落点自动改写）、`3d2428a12`（F7 非破坏性读取）、`9d02f1fbf`（F8 成因随文案走）、`3f137ccbf`（F9 五处执行回调统一走 `showTempFileError`）、`b0fa9b52b`（文案按中文界面实际标签页 + 「列表里没有 .idea」判据）。

## 步骤与当前进度

1. ✅ 文案提交 `b0fa9b52b` 已推 master 双仓。
2. ⏳ 门禁 `build-plugin.yml mode=gate` run **42989276892** @ `b0fa9b52b`（已派发，跑约 13–15 分钟；用户规矩：内测版同样先门禁后打 tag）。
3. 待办：删 Gitee 发行版（`gitee-release.py --version 2026.11.12-beta.2 --delete-only`）+ 删 GitHub Release（强移 tag 前必删，避开 by-tag 404 坑）。
4. 待办：`git tag -f` 把 tag 指到新 HEAD，双仓强推 tag。
5. 待办：`build-plugin.yml mode=publish` 挂本次 gate run_id → 由 CI 产物建 GitHub Release（不要用本地 buildPlugin 的 zip，`MPV-Build` 时间戳使哈希必不同）。
6. 待办：`gitee-release.py --from-github --replace --prerelease --yes --notes <发行说明>`，再 `--verify-only` 回读。
7. 待办：回填 `docs/变更记录.md`（表格单行，整行替换或行尾锚定，见 [[pitfall-single-line-table-edit-prefix-match]]）与发行说明的「同版本重发」段。

## 踩过的坑

- 判断「要不要重发」前先查三个读数（`git ls-remote --tags`、Gitee releases API、GitHub `releases/tags/<v>`）；本轮差点按「从未发行」去建 tag，实际三处都在。
- 发给用户验的 zip 必须报 **sha256 + 安装目录 jar 时间戳**：此前给过四个包，用户装的是 F6b，F7 从未被装上，白耗一轮（见 [[pitfall-sandbox-jar-contents-not-version]]）。

相关 [[project-release-20261111-formal]]、[[feedback-beta-release-needs-ci-gate]]、[[reference-ci-release-publish-mode]]、[[pitfall-mpv-build-timestamp-vs-ci-zip-hash]]。
