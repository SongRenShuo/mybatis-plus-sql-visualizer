---
name: project-release-20261112-beta3
description: v2026.11.12 正式版撤回（双源删 Release+tag，Latest 回落 v2026.11.11）并改发
  2026.11.12-beta.3（含 D1/D4/D5 与 M11 预取）；含撤回配方、tag 前双门禁、以及 update/update.json
  只能由用户改
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

2026-11-12 用户令：先删掉「有问题的」v2026.11.12 正式版，再发今天的内测版；版号沿用同一基号 `2026.11.12-beta.3`（用户在「换下一个基号 vs 沿用」间明确选了沿用）。

**范围**：本版＝2026.11.12 正式版全部内容 + 本轮真机验收修复（D1 调用点检索恒空、D4 两 Example 条件并成一条 WHERE、D5 混 kind 时 between 丢占位、D2 选区按快捷键误报未找到、M10/M11 首次打开改后台预取）。来源细节见 [[project-preview-openperf-m2-e2e-20261112]] 与 [[pitfall-prefetch-needs-failure-memo-and-coldstart-vs-algorithm]]。

**撤回配方（双源，只删正式版、保留 beta.1/beta.2）**
1. GitHub：`gh release delete 2026.11.12 --yes --cleanup-tag` —— 一条命令连 Release 与 tag 一起删；删后 `release list` 里 `Latest` 自动回落到上一个正式版（实测 `v2026.11.11`）。
2. Gitee：`python dev-support/gitee-release.py --version 2026.11.12 --delete-only` —— **只删发行版、不动远端标签**（脚本自己会打印「远端标签未动」）。
3. 远端 tag 收尾：`git -c credential.helper= push <token-url> :refs/tags/<tag>`，两个远端各一次（GitHub 若已用 `--cleanup-tag` 则可跳过）。
4. 核对：`git ls-remote --tags origin | grep <tag>` 应为空。

**发行前两道机器门禁（必须在 `git tag` 之前跑，红就改文案重提交）**
- `./gradlew test --tests "…convention.ChangeNotesRatingLineGuardTest"`（全文只允许 1 处评分引导句——新块置顶时要删掉上一块那行）。
- `./gradlew buildPlugin` + `python dev-support/inspect-plugin-zip.py --version <版本>` → 期望 `PASS`：内层 jar / `plugin.xml` / MANIFEST 三处版本一致、首个 `<h2>` 是本版、块内 `（ ） ：` 零命中。本版实测 6 条、PASS。

**发行动作**：`git tag <版本>` → `push-all.ps1 -Branch master` → 手动推 tag（两远端）→ `gh workflow run build-plugin.yml --ref master -f mode=release`（该工作流是 `workflow_dispatch`，**推 tag 不会自动发布**，见 [[reference-ci-release-publish-mode]]）→ GitHub Release 落地后再 `gitee-release.py --version <版本> --from-github --prerelease --yes --notes <md>` 同步 Gitee。

**tag 二次前移（2026-11-12 实录，两次都是「门禁跑着、publish 未起」时取消）**：用户真机复验接连抓到 D6（`bdc1f89ab`）与 D7/D8（`ab9a8c308`），每次都把 tag 从旧提交删掉重建再强推：
`git tag -d <tag>` → `git tag <tag> <新commit>` → `git -c credential.helper= push -f <token-url> <tag>`（两远端）→ 重新 `mode=release`。
成本＝每轮整跑一次全量门禁（约 15 分钟），所以**判据是「publish job 还没出现」才取消**（`gh run view <id>` 只看 job 列表；
publish 未列即为安全窗口）。三次 dispatch：`43169428015`（取消）→ `43171820943`（取消）→ `43175215640`（终态）。

**硬约束**：`update/update.json` 只有用户本人能改（AGENTS.md 铁律），内测通道指针（version/downloadUrl/changeNotes/publishDate）改了才算真上线，助手一律不碰；`update/preview.update.json` 不在此列，可随发行提交同步。Marketplace 不收内测包。

**版本位与文案四处**：`gradle.properties` 的 `version=`、`gradle/plugin-change-notes.html` 置顶 `<h2>v{版本}</h2>`、`CHANGELOG.md` 段（撤回正式版后原 `[2026.11.12]` 段直接改题为 `[2026.11.12-beta.3]` 并并入本轮修复，避免留下一个从未公开发行的正式版段）、`docs/发行说明/` 内测说明。

**changeNotes 终态（D7/D8 并入后）**：一版 **7 条内容 + 1 条评分引导**（「一版 ≤8 条」的天花板），块内禁 `（ ） ：` 全角、全文只许 1 处评分句；`update/preview.update.json` 的 `changeNotes` 与之逐条对齐（不含评分句）。文案改动后**必须重跑两道门禁再打 tag**——本版实测第二次改动（6 条→7 条→8 行）后仍 PASS。

**落位终态（2026-11-12 15:44）**：run `43175215640` 14 job 全绿 → GitHub `v2026.11.12-beta.3`（Pre-release，
`2026-11-12T07:44Z`）；Gitee 发行版 `id=1301068`（`prerelease=True`）由
`gitee-release.py --version 2026.11.12-beta.3 --notes <md> --name "<ASCII 名>" --prerelease --from-github --yes`
同步，附件 sha256 与 GitHub 逐字节一致（`f80bc6bc…`，8,086,616 字节）；API 回读确认
`tag_name/prerelease/assets/body` 四项。

**同步 Gitee 时踩的两条**：
- **`--notes` 的 glob 别只按「时:分」匹配**：`ls docs/发行说明/*1512*.md | head -1` 命中的是 `20261031-1512-…`
  （同 HHMM、不同日期）⇒ 发行正文挂错文档。按 `20261112-1512-*` 命名日期前缀取。
- 本地 `build/distributions` 里同版本 zip 与 GitHub 包哈希必不同（`MPV-Build` 毫秒戳，见
  [[pitfall-mpv-build-timestamp-vs-ci-zip-hash]]）——脚本会直接拒传，先把本地 zip `mv` 到 `.local/`。

**D9 未进本版（判断记录）**：D9（M2 注入时 foreach 值框只读，`89c74c663`）是在 run 已全绿、publish 尚未出现时
才拿到的；为一条 UX 护栏第三次取消重跑（约 20 分钟）不划算，选择随下一版对外，用户未反对。**判据仍是
「publish job 未出现＝安全窗口」，但取消前要给「值不值一轮门禁」的取舍**，不是无条件折进去。

## β3 之外的积压 → β4（2026-11-12 16:xx）

β3 发完之后又落了一批**只进 master、未进 β3** 的修复，用户令「派一个子智能体去准备新的内测版相关信息，
提交即可先不发行」：

- `89c74c663` D9（foreach 值框只读）、`c33f9542c` D10（清除回收 `__frch_`）+ D11（MBG 可填参数行）、
  `20cc719dd` T-SchemaPrewarmDedupe（预热 60s 去重窗口），外加未提交的 D11b（参数行标签按顺序对齐 + 常驻提示）。
- 目标版号 `2026.11.12-beta.4`（沿用同一基号；β3 已发同日补丁版）。子代理任务边界：只动版本位与文案四处
  （`gradle.properties` / `gradle/plugin-change-notes.html` / `update/preview.update.json` / `CHANGELOG.md` /
  `docs/发行说明/` 新文件），跑完两道 tag 前门禁后**只本地提交——不 tag、不 push、不 dispatch release**。
- **β4 已于 2026-11-12 18:4x 起打包发行**（第二批四修并入文案，tag `acbb75763`，run 43203043486 在跑）：状态与命令见
  [[project-release-20261112-beta4]]。
- 给下一轮发版的提醒：**推 tag 不会自动发布**，仍要 `gh workflow run build-plugin.yml -f mode=release`；
  真机复验判据见 [[project-schema-prewarm-dedupe-20261112]] 与
  [[pitfall-mbg-example-ref-and-or-grouping]]。