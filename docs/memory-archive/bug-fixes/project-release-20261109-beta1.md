---
name: project-release-20261109-beta1
description: v2026.11.9-beta.1 内测发行（20261109）——范围 2026.11.8..HEAD 113 提交；发行提交 65e03bae、gate run 42591907873 15 腿全绿、publish 出 GitHub Release、gitee --from-github 落地；随后因 changeNotes 全角括号自检 FAIL 走 tag 前移 f9c5b0c3 + mode=release 重发（run 42596337947）
metadata:
  node_type: memory
  type: project
  updated: 2026-11-09
  originSessionId: sess_dc5ec3b5-d6c2-415f-8290-d950ba1d28be
---

# v2026.11.9-beta.1 内测发行（2026-11-09 进行中）

用户令：「现在插件要发行今天的内测版本，你参考下之前的流程和规范，更新发行相关信息，gitee 工作流允许触发，github 的 ci 允许触发 创建发行版允许触发」。⇒ 物料更新 / 推 CI / 建发行版三步在授权内；`update/update.json` 仍不碰（[[feedback-update-json-user-only]]）。

## 范围与版本号

- 上一发行＝`2026.11.8` 正式版（tag 在 `0692f222`，2026-11-07 18:53）。本版范围＝`2026.11.8..HEAD`，**113 提交 / 21 个合并**。
- 内容面＝三块新能力 + 两条真机修复：T-Ibatis2Support（券 0/A/B/D/E/F/H：独立渲染链、DTD 内置、注入着色、双链跳转、`IBATIS` 独立来源）、T-GoldenDbDialect（第 15 方言，券 A/B）、T-SqlListCatchup（券 A/B/B2/D/E/F/G/H/I）、T-DateLiteralBackfill R10（PG 只发 `TIMESTAMP` → 42601，改单语句整批提交）、T-CatchAllFamilyFilter（Oracle 连接继承链误报未登记驱动）；T-DockerGateOffload 归 `CHANGELOG` 的 `Internal` 段。
- 版本号＝`2026.11.9-beta.1`（上一版是 11-07 的 `2026.11.8`，基号取当日 11.9 即可满足「大于正式版」）。

## 本轮实际动的八处（8 文件一提交 `65e03bae`）

`gradle.properties`、`CHANGELOG.md`（`[Unreleased]`→`[2026.11.9-beta.1] - 2026-11-09` 并补 `Internal` 段）、`gradle/plugin-change-notes.html`（8 条 + 评分引导块）、`update/preview.update.json`、`docs/发行说明/20261109-1705-内测-beta.1-iBatis2支持与GoldenDB方言与SQL列表提速-发行说明.md`、`docs/MEMORY.md`、`docs/变更记录.md`，以及 **`src/main/resources/html/marketplace-custom-page.html`（方言 14→15，补 iBatis/GoldenDB/磐维）**——本版有新能力类别，市场页与 changeNotes 都要动；`plugin.xml` description 已由 `e9a0fceb` 写成 15，未再改。

## 已发生 / 待发生

1. 本地三条发行守卫 **29/0/0/0**：`PluginVersionTest`(4) + `ChangeNotesRatingLineGuardTest`(2) + `PluginXmlStructureGuardTest`(23)。首跑 1 红＝`v2026.11.8` 块仍带评分引导句（见 [[feedback-rating-line-latest-block-only]] 第四次同类）。
2. 提交 `65e03bae` + 轻量 tag `2026.11.9-beta.1`，`push-all.ps1 -Branch master` 与 `-Tags` 双仓成功（GitHub 段走令牌直连、无 GCM 弹窗）。
3. dispatch `build-plugin.yml` **mode=gate**：run `42591907873` @ `65e03bae` **15 腿全 success**（publish skipped），verify 243/253/263 三目标 Compatible；耗时 build 6m52s / verifyplugin 6m23s / verify 4m22s·4m24s·6m42s / marketplace 5m36s·8m01s·10m59s / test(1)~(5) 9m32s·9m53s·10m42s·8m19s·8m57s / test(fast) 11m35s。同 head 推送轮 `push-test` `42591773695` **8/8 success**。
4. `mode=publish` + `run_id=42591907873` → run `42594405400`（当时还被 release 环境 required reviewers 挡住，API 代批后 17:46 完成）→ GitHub Release `438955517`（prerelease，zip 7,791,197 字节，sha256 `a3bdf20b…`）。
5. `gitee-release.py --from-github` → Gitee 发行版 `1290785`（prerelease，名称「🎉 SQL Visualizer v2026.11.9-beta.1 内测版」），sha256 两侧一致，`downloadUrl` 与 `preview.update.json` 逐字相等。
6. **随后 `inspect-plugin-zip.py` FAIL**（本版块含全角括号文案）⇒ 见下节重发。

## 重发（changeNotes 全角括号，2026-11-09）

- 自检被放在发布之后才跑 → 已上线的包带着「新增中兴 GoldenDB 方言（第 15 种）」全角括号，违反 changeNotes 红线。文案改成「…方言总数达 15 种」，`plugin-change-notes.html` 与 `update/preview.update.json` 同改，提交 `f9c5b0c3`（代码零改动）。
- tag `2026.11.9-beta.1` 前移到 `f9c5b0c3` 并双仓 **force push**（`push-all.ps1` 不带 force，tag 迁移要手工 `git push origin +<tag>` 与 `git push <token-url> +<tag>`）。
- 重发走 **`mode=release`**（一次跑完 gate + publish，run `42596337947`）：GitHub 侧对已存在的 Release 走 `upload --clobber` + `release edit`，不建新 Release。
- 之后仍需 `gitee-release.py --version … --replace`（Gitee 换附件只能删了重建，附件同名 URL 不变）。
- 用户同轮授权并实际改了 `release` 环境（去掉 required reviewers + 仅 master 可部署），细节见 [[reference-ci-release-publish-mode]]；规范与教训已回填 `T-ReleaseChecklist` §1.2/§1.2b/RC-P3/RC-P5 与 `docs/LESSONS.md` L25（提交 `3cf9fcbd`，本地未推）。
- 同轮修掉一个文档缺陷：`docs/规范/编码与验收标准-规范.md` 在 master 上四处被引用却只存在于 dev 线（判定「多引用非丢失」，单文件取回 + 把 L25 评分句半边升级为 S11，提交 `dca80de8`，本地未推），详见 [[pitfall-master-references-dev-only-doc]]。

## 供下轮复用

## 供下轮复用

- **门禁读数不写进 tag 树**（沿上轮先例）：发行说明与变更记录先留「待回填」，gate/publish 跑完再用一笔 docs-only 提交补 run id / 发行版 id / sha256；Gitee 正文用补完的本地文件传，GitHub 正文的发行说明链接指向 tag（不含回填段，属既有形态）。
- 参照物：[[project-release-20261108-beta1]]、[[reference-ci-release-publish-mode]]、[[project-release-workflow]]。真机 GUI 走查（iBatis 秒开观感、清屏后恢复、丢弃读数）与 GoldenDB「无真机引擎」两条边界必须如实写进发行说明。
