---
name: reference-ide-plugin-update-check-ignores-install-source
description: IDE 插件更新检查不区分安装来源——磁盘安装的插件��要 pluginId 与 Marketplace 一致就会收到市场更新提示；「内测用户看不到市场更新」的真因是版本比较而非订阅缺失（20261026 反编译实证）
metadata:
  node_type: memory
  type: reference
  originSessionId: session_a5cf3aa3-fe1e-4c1f-b9c6-9af6c299300c
---

**结论（20261026 反编译 IDEA 2026.2.3 实证，用户实测纠正）**：IDE 的插件更新检查**不区分安装来源**。磁盘安装（`Install Plugin from Disk`，含插件内自更新下载的 zip）的插件，只要 pluginId 与 Marketplace 上的一致、且市场版本高于本地版本，就会收到更新提示（`Settings → Plugins → Installed` 出现 Update 按钮，`⚙ → Check for Updates` 也列出）。

**四条反编译证据**（`intellij.platform.feedback.jar` + `intellij.platform.ide.impl.jar`）：

- `UpdateChecker.collectUpdateablePlugins()` 对已装插件的筛选条件**只有** `!isBundled() || allowBundledUpdate()`，**无任何安装来源判定**；随后按 `getPluginId()` 建 map。
- `UpdateChecker.checkInstalledPluginUpdates()` 取这批 pluginId 后按 **pluginId** 向仓库（Marketplace 后端 + 已配置的自定义 host）查询。
- `PluginUpdateCheckService.getPluginUpdate(pluginId, indicator)`（Plugins 页面单插件检查用的新 API）同样只收 pluginId，签名无来源参数。
- `UpdateCheckerPluginsFacadeImpl.getInstalledPlugins()` = `PluginManagerCore.getPlugins()`，全量已装插件，无过滤。

**「内测用户看不到市场更新」的真因**：内测版版本号**总是领先货架版**（`-beta.N` 先发、正式版后上架），平台比较后判「无更新」。是**版本比较结果，不是��阅缺失**。货架追平或超过内测版后提示即出现（例：货架 `2026.10.23` vs 本地 `2026.10.23-beta.2`/`beta.3` ⇒ 有更新）。

**被证伪的旧结论**：`docs/发行说明/Marketplace发布流程-说明文档.md` §10.3 原写「磁盘安装 → IDE 无市场订阅 → 收不到市场更新」，据官方帮助文档的「安装来源」表述推得、未做反编译核实。20261026 已订正该文件 §1 表行与 §10.3 全节（含 mermaid 图与「本质原因」段、迁移步骤第 1 条）。**教训：平台行为类断言须以反编译/实测为准，官方文档转述不足以立论**（用户原话「这点你可以反编译 idea 核实」）。

**与插件自带通道的关系**：`UpdateSource.kt:8-10` 明写「内部更新通道已转为 Gitee 抢先体验通道（读 `update/update.json`，含 `-beta.N` 与正式版提前推送）；**稳定版由 JetBrains Marketplace 接管，本通道不参与**」。两条通道**互不干涉**、可并存，但**版本号必须一致**，否则用户会看到「一条通道说有新版、另一条说没有」。⇒ 把 `update/update.json` 升版的作用是「让自带通道也提示」，**不是**「唯一能触达内测用户的通道」。

反编译配方见 [[reference-platform-artifact-keymap-lookup]]；上架零留痕与门禁产物判据见 [[pitfall-release-action-leaves-no-trace]]。
