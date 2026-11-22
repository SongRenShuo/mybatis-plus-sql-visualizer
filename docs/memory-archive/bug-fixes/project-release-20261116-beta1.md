---
name: project-release-20261116-beta1
description: 2026-11-16 同日正式版 2026.11.16 全撤回、改发 2026.11.16-beta.1 内测版收官读数；用户两次要求更新日志行缩短
metadata:
  node_type: memory
  type: project
  originSessionId: sess_642d4522-0a71-4caf-a2f5-87d7b45d12ff
---

2026-11-16 发行事件：先按「版本号=发布当天日期」发了正式版 `2026.11.16`（发行提交 `f6eb27201`、CI `43779431717` 16/16 全绿、GH Release `446479338` / Gitee 发行版 `1313654`），随后主分支补入 27 笔 bug 修复（T-IbatisGutterPerf / T-MapperGutterEvict / T-MechanismSweep / T-BindStaticRefCrossNs / T-DebugLogNoise / iBatis 跨命名空间箭头等，详见 [[project-ibatis-gutter-perf-20261116]] 与 [[project-bind-static-ref-cross-ns-20261116]]）未进正式版包，用户令撤回正式版并改发内测版。

- **撤回用配方**：GitHub `gh release delete <tag> --cleanup-tag --yes`（连带删 tag）；Gitee `gitee-release.py --version <v> --delete-only --yes`（只删发行版不动 tag）+ `git push origin :refs/tags/<tag>`；本地 `git tag -d`。三端 ls-remote 回读全空才算回收版本号。
- **版本号**：用户拍板用同日基号 `2026.11.16-beta.1`（不是 `2026.11.16` 转正，也不是次日 `2026.11.17-beta.1`）；同日先正式后改内测属用户明确选择，与 [[feedback-beta-same-day-version-numbering]] 的「同日热修续 beta」方向一致。
- **内测终态**：发行提交 `79ecd5a8c`（7 文件）、tag `2026.11.16-beta.1` 三方 ls-remote 一致；CI `mode=release`（显式 `-f notes=<-内测说明.md>` + `marketplace_lite=true`）run `43810439012` **13/13 全绿**（marketplace 只跑 243），GH Release `446648452`（prerelease=true，附件 8,266,634 字节、sha256 `196f0639…`）、Gitee 发行版 `1314588`（`--from-github --prerelease`，`--verify-only` [3/3] Verified，逐字节一致）；落账提交 `d95f1e98b`（不进 tag，双仓 master 同指）。Marketplace 不收内测包；`update/update.json` 用户自管仍停 `2026.11.15-beta.1`。
- **发行说明命名**：`20261116-1324-…-内测说明.md` 直接取真实时刻，不复现上轮正式版说明 `20261116-0000-…` 用占位时间被用户当众指出的问题——写发行说明文件名时先 `date '+%Y%m%d-%H%M'` 取真实时刻，不要用 `0000` 占位。
- **两处如实登记**：① 撤回的正式版说明 `20261116-0820-…-发行说明.md` 随本轮 tag 留档（CI 以 notes 显式指向内测说明，旧文件正文不被采用）；② 落账里改短 changeNotes/CHANGELOG 不进 tag，本版已发 zip 与 GH 正文仍是 tag 时的较长文案，短行从下一版生效。
- **用户两次纠偏更新日志**（详见 [[feedback-user-facing-release-notes-concise]]）：第一次「更新日志每行越来越长，重复内容多」；第二次「plugin-change-notes.html 每行也有点长」。我最初把 9+4 条合并成 4 条超长行（第一条 600+ 字）——去重做过头。最终改成「一条一主题、短行」：CHANGELOG 9 条每条一主题、`plugin-change-notes.html` 8 条每行 20~61 字、preview 逐字同步，`check-change-notes.py` 复跑 PASS。教训见 [[feedback-release-notes-one-topic-per-short-line]]。
