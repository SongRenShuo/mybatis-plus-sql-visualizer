---
name: project-release-20261111-formal
description: v2026.11.11 正式版发行（2026-11-11）——首轮收口（run 42880832461 16/16、GitHub 440800514、Gitee
  1295690、tag @ 0b166603）后用户令**同版重发**补入 e1cf3872 iBatis 全选/清除修复：tag 已前移 `6f6187ea`、
  旧 Release 先删、CI run 42895392825 重跑、Gitee --replace 待做；Marketplace 在审旧包须先撤回再重传
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da6a53eb-9d2c-4932-a34c-0da5e623a05d
---

# v2026.11.11 正式版发行（2026-11-11；首轮收口后同版重发）

## 第二轮：同版重发（用户令「主分支修了个 bug，重新发行今天的正式版本，版本号不变，插件市场我来传」）

- **补入内容**＝`e1cf3872`（T-IbatisSelectAll：iBatis 链「全选/清除」按钮接通重渲染与值清空，根因＝两条批量路径只枚举 Wrapper/MyBatis XML 两份条件视图清单、iBatis 行持有在 `PreviewResult.ibatisParamModel` 枚举不到；`SqlPreviewPanelDynamicBatchTest` +2、反向摘 ibatisRows 枚举转红、恢复 23/0）——**收掉 v2026.11.10 登记的已知问题 selectByCondition 勾选联动**。
- **重发准备提交 `6f6187ea`**：CHANGELOG 合并段加 Fixed 首条+范围行改、changeNotes 新块 9→10 条、preview.update.json changeNotes 同步、发行说明（修复段/已知边界改「已在本版修复」/测试段 +2/构建记录行复位待回填）、MEMORY 索引行。守卫绿 + inspect PASS（10 条零全角）；并行_tasks check 因活跃 GIF 任务 t-demogif-ibatis（MPSV-GifFresh 沙盒，另一会话录 iBatis 演示）WARN——登记 default 沙盒即隔离，本轮 buildPlugin 不再被锁。
- **tag 前移正确顺序（本轮走通）**：**先删旧 GitHub Release 440800514（DELETE 返回 204）→ `git tag -f 2026.11.11 6f6187ea` → `git push -f refs/tags/2026.11.11` 到 GitHub（令牌 URL）与 Gitee → 推 master → 派发 mode=release**。这是 [[pitfall-github-release-bytag-404-after-tag-move]] 的主动版：先删免得强移后 by-tag 404 再补救。
- **CI**：run **42895392825** @ `6f6187ea` 已派发（写此记忆时在跑，后台守望）；绿后待做＝回填构建记录 run id、`gitee-release.py --from-github --replace --yes` 重发 Gitee 1295690、变更记录条目更新。
- 本地 zip（守卫轮构建的）已挪 `.local/local-zips-20261111/`（该目录现存两份：首轮 CI 下载包 + 二轮本地包）。
- **用户侧提醒已给出**：Marketplace 压着在审的 2026.11.11 旧包——重传新包前须先在作者后台**撤回**该在审版本（撤回不可逆、操作前截图），否则同版本号重复上传被拒。

## 第一轮（已收口，读数如下）

用户明确授权：Gitee 工作流、GitHub CI、创建发行版三项都可触发。发行方向按 L30：**CI（mode=release）→ GitHub Release → `gitee-release.py --from-github`**——本轮全程按此走通，无返工。

## 终态读数

- **范围**＝`2026.11.10`（`8561fafc`）之后全部增量：beta.1（T-PanweiInsertCapture 可见化 + T-ExecPsiGate）+ beta.2（T-NestGateObserve + T-NestGateFix `0fc3836c`）+ 转正前两笔（70048a12 门禁陈旧判据收口、67a574ab T-PreviewDmlResult）。
- **8 处同步**（提交 `0b166603`）：CHANGELOG `[2026.11.11] - 2026-11-11` 合并段（beta 段与 [Unreleased] 两笔按转正先例删除吸收，beta 条目原文逐字复用）；changeNotes 新块 8 条 + 评分句；preview.update.json 同步；发行说明 `20261111-0248-正式版-捕获可见化与整类SQL消失根治-发行说明.md`。全是修复无新能力类别 ⇒ plugin.xml `<description>` 与 marketplace 页按 RC-P4 #5/#7 不动。
- **机器判据**：`ChangeNotesRatingLineGuardTest` 绿 + `inspect-plugin-zip.py --version 2026.11.11` 四项全 OK PASS（首跑被 runIde 沙盒锁挡，见 [[pitfall-preparesandbox-locked-by-running-runide]]，用户关沙盒后 PASS）。
- **CI**：run **42880832461** mode=release @ `0b166603`，**16/16 全绿**（约 16 分钟，后台轮询守望）。
- **GitHub Release**：**440800514**，prerelease=false，附件 7,884,424 B（sha256 `639643a2…`），正文 body_len 17465 含 `actions/runs/` 与「本版变更」。
- **Gitee Release**：**1295690**，`--from-github --yes`，sha256 与 GitHub 逐字节一致，`--verify-only` **[3/3] Verified**。
- **tag**：`2026.11.11` @ `0b166603`，双仓已推（push-all.ps1 令牌通路）。
- **文档收尾**：构建记录回填 `2243a975`、变更记录条目 `631046b4`，均已推双仓。

## 残余（用户动作）

- **update/update.json 待用户手改**（preview.update.json 已指向 2026.11.11）。
- **Marketplace**：用户 2026-11-11 已本人上传；§四回读**第二档「在审压着」**——公开 updates 端点最新仍 2026.11.10、`hasUnapprovedUpdate=true`，正常形态不重传，公网生效只认端点出现 2026.11.11。用户要求上传时**勾选隐藏**（隐藏版过审后公开列表也不显示，直到后台手动放出）。
- 下一批内测基号换 `2026.11.12` 起（[[feedback-beta-same-day-version-numbering]]，2026.11.11 已被正式版用掉）。

## 可复用细节

- **`gitee-release.py` 必须带 `--from-github`**：漏旗标会直接落到「建版要求本地 zip」分支报 `zip not found / 先跑 ./gradlew buildPlugin`——脚本时序是 ①.5（从 GitHub 下载附件）在 ③（建版）之前，已记入变更记录条目。
- CI 发行说明匹配：按**正文含 `v{版本}`** 在 `docs/发行说明/*-发行说明.md` 里找（beta 后缀有正则边界不会误命中，按文件名倒序取最新）；也可用 workflow inputs.notes 显式指定。
- CHANGELOG 合并段做法：不重写长条目，用小 Edit 只动结构行（段标题/范围行/内测段头），条目原文保留，避免转录错误。
- IAB 内置浏览器对 Marketplace 的实测（上传未走通即被用户接管，仅登记）：account.jetbrains.com 已有登录态（SSO）；`/plugin/upload` 404；`/edit/versions` 与 vendor 页都没有 Upload 按钮（edit 页签是 BUTTON 无 href，role=tab 点击恒超时，需页内 evaluate click）；IAB 原生文件选择器不支持——若下次要代理上传，先解决入口与文件注入两个面。
- 上一正式版 2026.11.10 读数锚：release run 42758016138 17/17、GitHub 439966289、Gitee 1293132。

相关：[[project-panwei-insert-capture-20261110]]、[[pitfall-psi-findfile-null-and-missing-exec-gate]]、[[pitfall-preview-dml-completion-evidence-wrong-source]]、[[pitfall-mpv-build-timestamp-vs-ci-zip-hash]]。
