---
name: project-release-20261108-beta1
description: v2026.11.8-beta.1 内测发行（20261107 起手）——范围只含 master 的
  T-DateLiteralBackfill 日期 ANSI 字面量修复；版本号用次日号而非 beta.3 的原因；用户已授权 Gitee
  工作流/GitHub CI/创建发行版
metadata:
  node_type: memory
  type: project
  originSessionId: sess_58ab6e9d-0836-4814-b264-a63a550ae0bb
---

# v2026.11.8-beta.1 内测发行（2026-11-07 起手）

## 用户授权（本轮明确给的）

用户原话：「现在插件要发行今天的内测版本，你参考下之前的流程和规范，更新发行相关信息，gitee 工作流允许触发，github 的 ci 允许触发 创建发行版允许触发」。即：发行物料更新 + 推 CI + 建发行版三步都在授权内；**update/update.json 仍不碰**（[[feedback-update-json-user-only]]）。

## 范围判定（主控判断，用户未逐项确认）

用户对「发哪些内容」「版本号用哪个」两个提问未作答，按最保守口径执行：

- **只发 master 现状**。master 自 v2026.11.7 正式版（`c3d25918`）之后只有 1 笔用户可见改动：`9779d173` T-DateLiteralBackfill——日期族参数改 ANSI 类型字面量（`DATE '…'` / `TIMESTAMP '…'`），修 Oracle 预览执行 ORA-01843/ORA-01861；SQL Server / SQLite 按方言门禁维持引号形态。另 `c2358cb6` 是 demo 夹具快照（内部项）+ 三笔 docs。
- **不擅自合 GoldenDB**：`t-goldendb-integration` `83f718ae`（16 笔）全绿但在等用户点名（[[project-goldendb-dialect-20261107]]）。
- **不擅自合 dev**：dev 领先 master 326 笔（F-01~F-15 已随 `d0b45a97` 合入 dev），master 反向领先 185 笔，合流需先解冲突并重跑门禁，属另一个量级的动作。

## 版本号为什么是 2026.11.8-beta.1（不是 2026.11.7-beta.3）

2026.11.7 正式版**今天已经发出**，而 `VersionComparatorUtil` 判 `-beta.N` 为预发布、同基号下小于正式版 ⇒ `2026.11.7-beta.3 < 2026.11.7`，装了今天正式版的用户收不到这次推送。按版本号规范「同日需再次发布（热修）并入下一个自然日」改用次日号，三通道都能收到。

## 八处同步（本次实际要动 7 处）

`gradle.properties`（version）/ `CHANGELOG.md`（`[Unreleased]`→`[2026.11.8-beta.1] - 2026-11-07`）/ `gradle/plugin-change-notes.html`（新 `<h2>` 块置顶）/ `update/preview.update.json`（version+downloadUrl+changeNotes+publishDate）/ `docs/发行说明/{YYYYMMDD}-{HHMM}-…-发行说明.md` / `docs/MEMORY.md` / `docs/变更记录.md`。
**不动**：`plugin.xml` description 与 `marketplace-custom-page.html`（只有出现新能力类别才并一条，本次是修复）。

内测 changeNotes 口径：只保留一个 beta 块；`v2026.11.7` 正式版块**保留**，新 beta 块置顶只写本版内容（参考 [[feedback-change-notes-merge-order]]）。

## 参照物

上一个内测版发行提交 `0d0f22d0` 只动 7 个文件（CHANGELOG / MEMORY.md / 发行说明 / 变更记录 / gradle.properties / plugin-change-notes.html / preview.update.json）——内测版的实际改文件面就是这 7 个。

`build-plugin.yml` 四种模式同一条流水线：`gate`（build + verify×3 + marketplace×3 + verifyplugin）、`publish`（跳过全部 gate、复用门禁产物出 Release）、`release`；push 事件 `inputs.mode` 为空串、归 gate 组且可被新提交 cancel。详见 [[reference-ci-release-publish-mode]]。

相关：[[project-release-20261107-beta1]]、[[project-release-workflow]]、[[project-release-20261106-beta2]]。
