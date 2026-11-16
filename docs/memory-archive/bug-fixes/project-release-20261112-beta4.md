---
name: project-release-20261112-beta4
description: 2026.11.12-beta.4 内测发行（今日第 4 个内测版，2026-11-12）——第二批四修并入文案后重打包；门禁 PASS、双仓推 master+tag、release run 43203043486 在跑，Gitee 待 --from-github
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

用户令「重打包再发，发布今天第四个内测版」⇒ 版号 `2026.11.12-beta.4`（同一基号，今日 beta.1~beta.4）。

## 范围＝β3 之后两批

- 第一批（已有文案 `2c40bcb86` 打底）：D9 值框只读 `89c74c663`、D10/D11 `c33f9542c`、D11b/D12/D13、T-SchemaPrewarmDedupe `20cc719dd`。
- 第二批（同日随后真机验收，`e9b140fb1`）：T-LabelInset（左栏标签被 Swing 截尾）、T-PreviewBannerLiveRefresh（重渲染不刷横幅）、T-FullTableWriteRisk（无 WHERE 的全表写无提示）、T-ForEachInert（分支没走到的值框填了没反应）。逐条根因与反向读数见 [[project-preview-openperf-m2-e2e-20261112]]，截尾机制另立 [[reference-swing-jlabel-self-truncation-and-insets]]。

提交链：`e9b140fb1`（代码+3 个测试类）→ `9d318de9d`（CHANGELOG + LESSONS L48/L49）→ `acbb75763`（发行文案）。tag `2026.11.12-beta.4` 打在 `acbb75763`（**tag 必须含新 changeNotes**，所以先提交文案再打 tag）。

## 文案取舍（≤8 条的硬约束下怎么并）

块内原有 6 条 + 新增 4 条超上限 ⇒ 合成 **7 条内容 + 1 条评分引导（8 行）**：参数区两条合一、值框只读与「本轮未展开」合成一条、横幅与全表写警示合成一条，另加「行名不再被截成 criteria.crit…」。块内禁 `（ ） ：`（**ASCII 冒号可用**，既有条目就用 `: `）；注意**bundle 的 `.properties` 不受这条约束**（zh bundle 里全角冒号 348 处，别照搬去改）。`update/preview.update.json` 的 changeNotes 与块逐条对齐（不含评分句）；CHANGELOG 把第二批四条从 `[Unreleased]` 移进 `[2026.11.12-beta.4]` 段并改写该段范围说明；`docs/发行说明/20261112-1614-…内测说明.md` 追加「同日第二批」四节 + 改验证边界（原文「本版只动版本位、未改 src/main」已不成立）。`update/update.json` 仍是用户专属，不碰。

## 门禁读数（tag 之前，本机）

- `ChangeNotesRatingLineGuardTest` 3 例 / 0 失败（评分句全文只 1 处＝最新块）。
- `./gradlew buildPlugin`（树干净）→ `MPV-Build=1794480107529`；`inspect-plugin-zip --version 2026.11.12-beta.4` → **PASS**（内层 jar / plugin.xml / MANIFEST 三处版本一致、首个 `<h2>` 是本版、块内 8 条、『（ ） ：』零命中）。
- 定向回归 10 组全绿（`BUILD SUCCESSFUL 3m45s`）；反向三处各成对变红（内边距 / 风险判定 / `updateMeta` 调用）。一次抖动：`SqlPreviewPanelPrewarmLateRecoverTest` 在 359 例同 JVM 批里红一次，单类与整批复跑均绿 ⇒ 预热预算的时序抖动，与改动无关（复跑同一批才是判据，别立刻怀疑自己刚改的代码）。

## 推送与发行（截至记录时）

- `push-all.ps1 -Branch master -Tags` 双仓成功（Gitee 与 GitHub 的 master + tags 四条全 OK，走令牌通路、无弹窗）。打 tag 前先核本地有没有「已删掉的旧 tag」：`git tag --list "*2026.11.12*"` 只有 beta.1~3、`v2026.11.*` 为空 ⇒ `-Tags` 不会复活撤回过的 `v2026.11.12`。
- `gh workflow run build-plugin.yml --ref master -f mode=release` → run **43203043486**（head `acbb75763`，与 tag 同提交）。挂了个后台 `gh run watch <id> --exit-status --interval 30` 等通知（不阻塞主控）。
- Gitee 步：先 `mv build/distributions/<同名 zip> .local/dist-stash/`（本地包与 CI 包哈希必不同，见 [[pitfall-mpv-build-timestamp-vs-ci-zip-hash]]），GitHub Release 落地后再 `gitee-release.py --version 2026.11.12-beta.4 --notes <精确路径> --prerelease --from-github --yes`；`--notes` 别用 `*1512*` 这类 HHMM glob（会命中别日同 HHMM 文档）。

## CI 触发事实（这轮查清楚，别再答错）

- **推送触发的是 `push-test.yml`**：`on: push: branches: [master]`，5 片平台类 + 2 条 `testFast` 腿，约 11 分钟，并发组 cancel-in-progress ⇒ 新推送会取消在跑那轮。
- **`build-plugin.yml` 只有 `workflow_dispatch`**（mode=gate/release/publish/tests）⇒ **push 永远不会触发门禁或发行**，必须手动 dispatch（与 [[reference-ci-release-publish-mode]] 同口径）。
- 本机 `gh` 未登录：每次调 API 都要 `export GH_TOKEN=$(python -c "import json;print(json.load(open('.local/github-token.json'))['github_personal_access_token'])")` + `HTTPS_PROXY=http://127.0.0.1:7890`；**别用 `git credential fill` 取令牌**。

## 余下动作

Gitee 同步（上述命令）→ `docs/变更记录.md` 落发行条目（含 run id / GitHub Release id / Gitee 发行版 id / sha256）→ 用户本人改 `update/update.json` 才算内测通道真上线。