---
name: feedback-update-json-user-only
description: 铁律——update/update.json 只有用户本人可以改，AI 会话一律不得写入/提交/推送（20261021 我违规一次被追责）
metadata:
  node_type: memory
  type: feedback
  updated: 2026-10-21
  originSessionId: session_6710aac32099221c21286f43c98ae177
---

`update/update.json` 是 Gitee raw 自更新通道的唯一数据源。**只有用户本人可以改它**，AI 会话不得以任何形式写入、`git add`、提交或推送，包括「只是同步版本号」「只改 downloadUrl」这类看起来无害的操作。

**Why:** 用户要对「什么时候向存量用户推送新版」保留完全控制权——可能想先自测、想压着不发、想改文案。会话代改等于替用户对外发布，且 raw 已被客户端缓存，撤回不掉。用户原话（20261021）：「这是铁律不允许你主动修改，之后记住只有我自己可以改这个文件」。

**这条铁律的实际分量（20261031 平台源码实证订正）**：`update/update.json` **只服务插件自建的 Gitee 抢先体验通道**（`UpdateSource` → `PluginUpdateRunner`，即设置页「检查更新」按钮），作用是**在 Marketplace 尚未上架时提前把新版推给内测用户**。**平台自动更新与这个文件无关**：`StandalonePluginUpdateChecker.kt:191` 固定请求 `https://plugins.jetbrains.com/plugins/list?pluginId=…&build=…&pluginVersion=…`（按 pluginId 查 Marketplace，**不看安装来源、不读仓内清单**）；`MarketplaceRequests.getLastCompatiblePluginUpdate` 走 Marketplace 兼容更新查询；`PluginDownloader.isFromMarketplace()` 仅按下载 URL host 决定签名校验与下载服务，不影响「是否检查更新」。⇒ **从磁盘装的 beta 包同样会被 IDE 按 pluginId 查 Marketplace 并提示升级**，20261026 那条「按安装来源区分订阅、内测用户两条通道都收不到」的表述已证伪（用户 20261031 亲口确认「存量用户可以通过插件市场正常更新」）。机制细节见 [[project-release-workflow]] 与 [[reference-ide-plugin-update-check-ignores-install-source]]。

**O-109「update.json 断链」的两种形态（20261031 实测，问「O-109 是什么」时按此答）**：①**历史形态（2026-10-07，第三轮 P0）**＝线上五条更新路径全 404 + 仓内清单停更在 9.23，Gitee 自更新通道零触达；②**现行形态（20261031）＝已非缺陷，仅剩「自建通道清单落后」这一裁量项**——路径全通（Gitee raw 302→200 且 JSON 合法、GitHub raw 200、两版附件 downloadUrl 均 200），`version` 仍 `2026.10.31-beta.2` 而正式版 `2026.10.31` 已发行；影响面**仅限依赖该通道提前拿版的用户看不到「升级到正式版」提示**（可从发行页手动下载），**Marketplace 存量用户走平台通道、不受影响**。易误判点：`https://jvtools.cn/update.json` 返回 200 但正文是站点 SPA 的 HTML——它本来就不是更新源（插件常量指向 Gitee raw），别当断链证据。**不列为 P0**，改与不改纯属用户裁量。

**How to apply:** 发行流程止于「建发行版 + 回读校验通过」；报告里写「update.json 待用户手改」，不代做也不催。收口自检时问一句「我有没有碰过 update/update.json」；`git status` 里若出现该文件改动而没被明确授权，立即停手报告，不要顺手提交。相关：[[project-release-workflow]]（该文件顶部有同一条铁律与违规实录）。

**`update/preview.update.json` 不受此限——它是内测通道清单，发版时正常需要改，会话可以代改（2026-11-04 用户原话订正）**：用户明确「`update.json` 只能我改，`preview.update.json` 是正常需要改的」。两者区别：`update.json` 服务「已发布版本检查」，改它等于替用户对外推送存量用户，故用户专属；`preview.update.json` 是内测通道的下一版清单（发行 8 处清单第 6 项），**随每次内测发版同步更新是流程的一部分**，会话直接改、提交、推送即可。

代改时四项校验：①`version` 必须逐字等于 `gradle.properties`；②`downloadUrl` 里的 tag 段必须逐字等于 `version`（差一字符用户点更新就 404）；③`changeNotes` 用 `\n` 分隔的单行字符串，条数与 changeNotes 块一致；④`publishDate` 用发版当日。改完 `python -c "import json; json.load(...)"` 验合法，单独一笔提交（不与别的混）。内测发版只动 preview 那份，`update.json` 一律不碰。
