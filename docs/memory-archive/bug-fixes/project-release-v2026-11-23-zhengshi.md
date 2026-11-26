---
name: project-release-v2026-11-23-zhengshi
description: 发行 v2026.11.23 正式版已全流程完成（20261123，beta.1/beta.2 转正）——CI 45127346425 全绿、GitHub Release 455090440、Gitee 1357146、落账 c22067000 已推双仓；Marketplace 已由用户上传且 slug 改为 33633-mybatis-sql-visualizer、Getting Started 后台改 HTML 修换行；剩 update.json 待用户本人
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7736ad03-fd53-4d18-b592-9ff76db294f0
---

# 发行 v2026.11.23 正式版（已完成，2026-11-23）

用户 20261123 明确要求「发行今天的正式版本」并授权 gitee 工作流、GitHub CI、创建发行版均可触发（与 [[feedback-release-wording-zhengshi-clarify-beta]] 不同——这次明确点名正式版，未走 AskUserQuestion）。全流程按 [[project-release-workflow]] 与 T-ReleaseChecklist 走完。

## 形态与范围

- 版本 `2026.11.23`＝当日 `2026.11.23-beta.1` / `beta.2` 转正；内测 tag 与发行版保留共存
- 上一正式版 tag `2026.11.21`（a05816485），范围＝`2026.11.21..HEAD`，按能力面合并为单一正式段取代两个 beta 段
- 7 项用户可感改动：显示名更名 MyBatis SQL Visualizer（acd4c8bdb）、Marketplace 描述关键词批（de2f03f7a）、描述补国产库覆盖句（9e3493389）、裸 SQL `${}` 直替横幅（15a166f43/8ceced35b）、实体字段识别对齐真机语义（D254 8b2b15e13）、setEntity 逐列独立参数键（D255 33661f779/f9aa1b375）、无数据源执行失败结果区绿块修复（D253 4a2d08978）
- 无新增能力类别 ⇒ plugin.xml 描述与 marketplace 功能矩阵不动；`update/update.json` 归用户本人，会话一律不碰

## 完成的动作（全部有证据）

- 前置：master 工作树、双远程 fetch；tag `2026.11.23` 事先查为空；beta.2 tag（1ad94be76）→ HEAD 零代码增量（`git diff --stat ... -- src/main src/test` 空），beta.2 已有 CI 全量门禁背书 run 45118927241 覆盖全部代码
- 物料 5 文件：新增 `docs/发行说明/20261123-1450-显示名更名与裸SQL横幅及实体字段等修复转正-发行说明.md`，改 CHANGELOG / gradle.properties / gradle/plugin-change-notes.html / update/preview.update.json
- tag 前判据 `python -I dev-support/check-change-notes.py --version 2026.11.23` + `--self-test` 双 PASS：C1~C9 全 OK、7 条 li 逐字一致、9/9 反向恰红
- 发行提交 **`5c00ae070`**；tag **`2026.11.23`** 指向该提交；`push-all.ps1 -Branch master -Tags` 四路全绿，日志 `x-access-token:***@` 无弹窗＝令牌通路；双仓 `git ls-remote --tags` 均见 tag 指向 5c00ae070
- CI `mode=release` 正式版通道（不带 marketplace_lite / notes）run **`45127346425`** @ 5c00ae070 **conclusion=success**，17 job 全绿（agenttest / test 五片 + fast / build / verifyplugin / verify 243·253·263 / marketplace 243·253·263 / gate / publish）；结论取 `gh run view --json conclusion` 与 `ci-status.py wait` 退出码 0
- GitHub Release **`455090440`**（prerelease=**false**，附件 `mybatis-plus-sql-visualizer-2026.11.23.zip` 8,505,711 字节、sha256 `0bf609fa44b2e3ab…`），正文自带构建记录行 run 45127346425
- Gitee 发行版 **`1357146`**（`gitee-release.py --version 2026.11.23 --notes <发行说明> --from-github --yes`，正式版**不带 --prerelease**），`--verify-only` **[3/3] Verified**，sha256 与 GitHub 附件逐字节一致，downloadUrl 指向本版 tag 路径；构建记录行在 CI 窗口内提前填入发行说明，建版自带省一次 PATCH
- 落账提交 **`c22067000`**：`docs/变更记录.md` 归档索引表 + `docs/MEMORY.md` 索引行 + 发行说明构建记录行回填；已推双仓（push-all.ps1 -Branch master），fetch 后双仓 master 与本地 0/0 同步；落账不进 tag
- 工作树最终干净；`parallel_tasks.py check` 全程无活跃任务、本轮未 register（发行链不跑 gradle）

## 发行后 Marketplace 侧收尾（20261123 当日续做，已全部完成）

- 用户上传正式版后**Marketplace URL slug 由 `33633-sql-visualizer` 变为 `33633-mybatis-sql-visualizer`**（插件更名配套）。全量替换三仓 + d256 分支共 **4 仓 103 处**旧 slug（插件仓 23 / 文档站 33 / admin 1 / d256 分支 23），只改跟踪文件里的活链接；`tmp/` 调研 JSON、`out/` 编译产物（均 gitignored）不动；docs 历史记录里的链接按用户「所有引用全改」字面要求也一并替换
- `UpdateSource.MARKETPLACE_URL` 常量与 `UpdateCheckDiagnosisTest` 断言同步改新 URL，定向测试 BUILD SUCCESSFUL
- **Getting Started 后台字段**（仓库无镜像、只在 Marketplace 后台）：原存 Markdown 原文，平台按纯文本渲染致 `**`/反引号原样显示且长句不换行。经用户授权用浏览器登录后台，Edit Section → textarea（提示 "Use HTML tags to add formatting"），把 Markdown 改成 `<ol><li>`+`<p>`+`<b>`+`<code>` 的 HTML，**公开页匿名 context 实测渲染为真正有序列表、换行正常**，同时把搜索词改成 `MyBatis SQL Visualizer`（作者 SongRenShuo）。先写成英文，用户明确「实际用户绝大多数是国内」后**最终改成中文版 HTML**（安装/打开工程/看 SQL 三步 + 运行期增强捕获段），已即时生效、无需发版。详见 [[reference-marketplace-api-quirks]]
- 二维码对齐：plugin.xml 描述区两码固定 `width=200 height=200` + `td valign=top`（源图均 220×220 正方形，但渲染一高一低），提交 `6e528a2f9` 推双仓，**按用户决定不发版、待下次上传生效**（preserveUIEdits=false 时只改 plugin.xml 不改线上）
- 浏览器后台操作两个坑：登录态在 Playwright 独立 context 里没带用户本机 cookie（用户需在该浏览器重登）；作者看自己插件页会被导到 `/edit`，验公开渲染须开 isolatedContext 匿名窗

## 遗留（用户本人动作，会话不代做）

- `update/update.json`：preview → update 单文件手改（Gitee raw 自更新源），本版 preview 已指向 2026.11.23（本轮多会话均未代改）
- 真机 GUI 冒烟（gutter / 转换 / 日志面板 / 设置页）未在本轮做

## 中断期间的附带处理（与本发行无关）

- 用户中断 CI 阻塞等待，要求清理工作区未提交内容：
  - `AGENTS.md` +1 行「耗时命令一律后台启动」（全局 §E1，20261123 用户复述，见 [[feedback-no-block-wait-on-background-tasks]]）＝有用，已提交 **`ffdcc855a`**
  - `.run/runIde.run.xml` 新增 "Run IDE with Plugin" 配置与已有配置逐字重复＝误操作，已 `git checkout --` 还原
  - `record/pev262_*.py` ×25＝PEV 262 一次性 GUI 点按/截图脚本，硬编码 `.worktrees/t-pev-absorb/build/pev262-shots`，其中 `pev262_mysql_creds.py` / `pev262_mysql_creds2.py` 含明文数据库密码＝不可复用且不宜入库，已全部删除。删文件不等于轮换凭据，若密码仍在役属另一条线。

**Why:** 发行链跨多个会话/中断，tag 后未建 Gitee 发行版、未上传 Marketplace 的状态极易被误读成「已发完」而断供（[[feedback-release-wording-zhengshi-clarify-beta]]、Z-36 纪律）；本条钉住最终读数与遗留动作。

**How to apply:** 判断本版是否已发完，看 GitHub Release 455090440 + Gitee 1357146 + tag 双仓在场；Marketplace 与 update.json 是用户本人动作，报告只写「待用户手改/上传」，不冒充完成。
