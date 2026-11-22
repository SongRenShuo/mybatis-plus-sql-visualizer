---
name: project-release-20261114-beta1
description: v2026.11.14-beta.1 内测 + v2026.11.14 正式版双双收官（20261114 同日）——正式版全绿落位读数与转正流程实操要点（本地 zip 挪位/gate 后补构建记录行/find 展开中文 notes 路径）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

# v2026.11.14-beta.1 内测 + v2026.11.14 正式版（2026-11-14，双收官）

## 正式版落位读数（全部实测）

- 发行提交 **`8191d0364`**（5 文件：gradle.properties / plugin-change-notes.html / CHANGELOG.md / preview.update.json / 正式版说明）＋落账 **`8b996e14c`**（变更记录归档索引 + docs/MEMORY.md 发行说明索引 + notes 补构建记录行），均已推双仓。
- tag **`2026.11.14`** @ `8191d0364`，双仓 ls-remote 一致。
- 本地守卫：ChangeNotesRatingLineGuardTest **3/0/0**；buildPlugin 成功（本地 zip 8,241,806 字节，MPV-Build=1794644573983）；inspect-plugin-zip **PASS**（首块 v2026.11.14、11 条、块内零全角、changeNotes 与 preview.update.json 逐字一致）。
- CI：release run **43575726269** **16/16 job 全绿**（GitHub Release `445153267`，prerelease=**false**，附件 8,242,073 字节 sha256 `680d32f9af…`）。
- Gitee 发行版 **`1308206`**，`--verify-only` **[3/3] Verified**，附件 sha256 与 GitHub 逐字节一致。
- 范围锚点＝`v2026.11.12` 正式版 tag：**11.13-beta.1/beta.2 与 11.14-beta.1 三轮全部并入**（11.13 两轮此前未被转正），外加发行前 T-LocalDirPersist 持久化修复 `59a9d1baa`（`UpdateSection.kt`——src/ 相对 beta.1 tag 的唯一增量 ⇒ 分片读数不复用、由 CI 在转正产物上重跑全量）。

## 剩余用户本人动作

1. `update/preview.update.json` → `update/update.json` 逐字节拷入并独立提交（官方自更新通道仍停旧版）。
2. ~~Marketplace 上传~~ **已完成（20261114 用户上传，会话回读第一档）**：updates 端点最新条目 `2026.11.14` approve=true/listed=true、`hasUnapprovedUpdate=false`——本轮**未压审核队列直接上架**；顺带发现列表里 11.14 的上一条是 `2026.11.11`（**2026.11.12 正式版从未上传 Marketplace**，这版连带补上）。

## 转正流程实操要点（本轮验证过的正路）

- **beta.1 tag 之后 src 有增量时**：转正必须 CI 全量重跑，RC-P1c 分片读数复用判据不适用（版本号进 plugin.xml，jar 变了）。
- **changeNotes 三合一**：删三个 beta 块按能力面合并为一个 `<h2>v2026.11.14</h2>` 块（10 条 + 评分引导句、块内零全角）；CHANGELOG 同批删三个 `*-beta.N` 段、`[Unreleased]` 清空折入正式段——Edit 手术：清 Unreleased → 换段头 → 把最早 beta 段的 Added 两条搬到正式 Added → 用「相邻首条 bullet＋段头」整块锚点删掉两个内测段头（`git diff --stat` 单行文件失真，改完用 python 计数复核 `### Added/Fixed/Changed` 各恰一次）。
- **preview.update.json 的 changeNotes 必须与首块逐字一致**（不含评分行）；构建记录行（RC-P4b）在 CI run 出来后补进 notes 文件、随落账提交走，Gitee 正文（--notes 传入）即带上。
- **本地 zip 与 CI 包哈希必然不同**（MPV-Build 时间戳）：`gitee-release.py --from-github` 会硬拒「本地同名 zip 内容不同」⇒ 先把本地包挪进 `build/distributions/_local-backup/` 再跑，让脚本下载 GitHub 门禁验过的那份。
- **派发 CI**（正式版 release-beta.ps1 用不了——前置闸门硬性要求 `-beta.N`）：手工 `GH_TOKEN`（读 `.local/github-token.json`）+ `gh workflow run build-plugin.yml --ref master -f mode=release -f notes=<路径>`；**中文 notes 路径用 `find docs -name '20261114-1613-*.md'` 展开进变量**，避开命令行中文乱码；等结论用 `python dev-support/ci-status.py wait <runId>`（后台任务，不空等）。
- 并行登记：有活跃任务（t-standalone-hotreload）时照常 `register --name <任务名>`（默认沙盒与其 standalone-ide 沙盒不冲突），收尾 `done --name`。

## beta.1 轮要点（同日上午，已双通道发行）

前置全绿（WARN 修复 `be43c4bf6` + 文档 `de8a6027c`，门禁 run 43542010780 success，夜间 run 43526253002 dialect 重触发 4/0/0/0）；`release-beta.ps1` 一条命令完成 tag/推仓/CI/Gitee；发行提交 `0f9958fbe`、落账 `d3e0f0687`、版本位推进 `4a0c9bace`；GH/Gitee prerelease=true、sha256 同 `0f42b546…`、附件 8,241,770 字节。

相关：[[feedback-beta-same-day-version-numbering]]、[[pitfall-mpv-build-timestamp-vs-ci-zip-hash]]、[[pitfall-ci-release-notes-and-publish-run-gate]]。
