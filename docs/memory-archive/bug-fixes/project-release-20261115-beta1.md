---
name: project-release-20261115-beta1
description: v2026.11.15-beta.1 内测发行收官（20261115）——发行提交 1c36c369c、CI 43659175871
  14/14 绿、GH 445715456 / Gitee 1310184 双通道落位、发行后落账 a95c74231 与 release-workflow
  skill b358bb4b2
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6d89e127-4104-44de-8f16-1986f8669393
---

# v2026.11.15-beta.1 内测发行（2026-11-15 收官）

用户令「参考之前的流程和规范，更新发行相关信息，gitee 工作流/github ci/创建发行版允许触发」；中途追加「子智能体后台执行，提高效率」与「深度了解发行完整流程，沉淀 skill 到项目下」。发行主体由主控执行，物料与 skill 草稿由后台子代理并行产出。

## 落位读数（全部实测）

- 版本号 `2026.11.15-beta.1`：`2026.11.14` 基号已被正式版占用，按 [[feedback-beta-same-day-version-numbering]] 换新基号（该规则在册即「下一批从 11.15-beta.1 起」），本轮首次实发验证。
- 范围＝`2026.11.14..HEAD` 23 笔；用户可感＝T-GutterFirstOpenDelay 五刀（F1 `d3eb65079` / F1' `6bc12e9c8` / F1'' `81884a539`+`588d64604` / F1''' `7dc6c1c73` / F2 `7ad1d1a5d`），见 [[project-gutter-first-open-delay-20261114]]。
- 发行提交 **`1c36c369c`**（7 文件：CHANGELOG / docs/MEMORY.md / 内测说明 / docs/变更记录.md / gradle.properties / gradle/plugin-change-notes.html / update/preview.update.json）。内测轮实际文件面＝4~7 文件，无新能力类别时 plugin.xml description 与 marketplace-custom-page.html 不动。
- 前置三门禁：ChangeNotesRatingLineGuardTest 绿、`buildPlugin` BUILD SUCCESSFUL、`inspect-plugin-zip.py --version` **PASS**（首块 6 条、评分句全文 1 次、块内零全角、与 preview.update.json 逐字一致）。
- CI：`build-plugin.yml mode=release`（`-f notes=<内测说明路径>` 显式、`-f marketplace_lite=true`）run **43659175871** @ `1c36c369c`，**14/14 job 全绿**、`conclusion=success`。tag `2026.11.15-beta.1` @ `1c36c369c`，本地+Gitee+GitHub 三方 ls-remote 一致。
- GitHub Release **445715456**（prerelease=true，附件 8,253,623 字节，sha256 `b511cd14d179d6fe98aafc88dc9aa27e7b0d6cc095c328f9cbb191585801bd00`）；Gitee 发行版 **1310184**（`--from-github --prerelease --yes` + `--verify-only` **[3/3] Verified**，附件与 GitHub 逐字节一致）。
- 落账提交 **`a95c74231`**（docs/变更记录.md 回填 CI 读数、docs/MEMORY.md 索引行、内测说明补构建记录行并订正「未跑门禁」表述）；skill 提交 **`b358bb4b2`**。三者均发行后补账、不进 tag，双仓 master 最终 `b358bb4b2`，tag 仍指 `1c36c369c`。
- **`update/update.json` 未动**（哈希 `127f51ec…` 实测），仍停 `2026.11.14-beta.1`，由用户本人手改；`update/preview.update.json` 指向本版。

## 本轮新踩与处置（写进 skill）

- **GitHub 正文已自带构建记录行**：CI 的 publish job 用 `actions/runs/{run_id}` 自动生成，不需要 PATCH GitHub；只有 Gitee 正文（`--notes` 全文）缺该行，补完用 by-id `PATCH /releases/{id}` 只改 body。
- **GitHub PATCH 端点形态**：`/releases/tags/{tag}` 只支持 GET，对它发 PATCH 直接 404；改正文要用 `/releases/{id}`（gitee-release-update.py 与本轮脚本都按 by-id 走）。Gitee PATCH 仍须 `tag_name`/`name`/`prerelease` 三字段同带，见 [[pitfall-gitee-release-body-patch]]。
- **本地 zip 与 CI 包哈希不同**：按 [[pitfall-mpv-build-timestamp-vs-ci-zip-hash]] 提前把本地 gates 包移出 `build/distributions/`（留存 `.local/dist-stash/`，本地 `be1263bd…` / GitHub `b511cd14…`），`--from-github` 一次跑通。
- **三路后台子代理并行起草物料、一路后台代理深挖流程起草 skill**：写集互不重叠（版本位+changeNotes+preview / 内测说明 / CHANGELOG+变更记录+MEMORY / skill 草稿只读），主控统一核对后单笔提交，避免共享工作树 index.lock 撞车。第三路曾留 `ZZPROBEZZ` 临时探针行，主控核对时确认已清除才提交。

## 沉淀的 skill

`.agents/skills/release-workflow/SKILL.md`（199 行，`b358bb4b2`）：frontmatter 与项目现有 skill 同格式（name+description）；12 节覆盖定形态/前置检查/八处同步与两条机器判据/门禁/发行提交与 tag/CI 发行轮/双通道/回读断言/失败回退/发行后落账/不得自动做的事/为什么/实证，含内测 vs 正式差异表与全部红线出处。以后发版先读它，冲突以 `docs/发行说明/T-ReleaseChecklist-发行检查清单.md` 为准。

相关：[[project-release-20261114-beta1]]、[[pitfall-ci-release-notes-and-publish-run-gate]]、[[feedback-parallel-subagents-no-blocking-wait]]、[[feedback-ci-gate-instead-of-local-full-gate]]。
