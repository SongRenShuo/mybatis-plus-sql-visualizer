---
name: project-marketplace-publishing
description: Marketplace 发布流程文档（双仓库方案/签名现状/审核 4 步/自更新联动），首次发布前必读
metadata:
  node_type: memory
  type: project
  originSessionId: sess_10dc9686-c369-412f-99d2-b5ab5bbe3710
---

2026-08-16 新增 `docs/Marketplace发布流程-说明文档.md`（基于 JetBrains 官方文档查证，链接见文档 §12 附录）。

**T-M51 + T-M52 废弃 API 清理完成（2026-08-19，v2026.8.19 已发行）**：Marketplace 审核拒绝后修复全部 Internal/Scheduled-for-removal API，三版本 verifyPlugin 结果 0 Internal + 0 Scheduled-removal + Deprecated 9/11/27→3/4/20。修复清单见 [[pitfall-tm51-api-cleanup]]。提交：31b5b43（主体）+ 04fcf3a（PluginManager 消除）+ 22d94ed（projectOpened 消除）+ c378a5e（T-M52 deprecated 替代）。

关键结论（用户首次发布，勿凭记忆重查）：
- **双仓库方案**：官方要求开源插件源码链接公网可达，文档只举例 GitHub/GitLab 未提 Gitee → Gitee 主仓库（现状 origin）+ GitHub 镜像（`git remote add github ...`），Marketplace 源码字段填 GitHub；`scripts/push-all.sh` 双推
- **首次发布必须网页手动上传**（plugins.jetbrains.com/author/me → Add new plugin），后续版本可 Gradle publishPlugin（需 My Tokens PAT）
- **Vendor 验证**：接受 Developer Agreement + 创建 Vendor profile（Individual/non-trader，EU 法规声明）
- **开源许可证必填源码链接**（Apache-2.0）；审核 4 步：自动检查 → Plugin Verifier → 自动测试 → 人工（≤2 工作日）
- **签名现状**：signPlugin 机制存在但公钥上传功能官方未开放（2025 论坛），未签名可上传（IDE 装时可能警告）→ 首次发行建议跳过签名跑通流程
- **版本联动**：Marketplace 版本必须与 update.json（Gitee 自更新）一致；同版本号不接受重复上传
- 发布待办：补 pluginIcon_dark.svg（META-INF 只有亮色图标）；页面截图 1200×760
- ✅ **2026-08-19 图片外链阻断已解决**：plugin.xml 描述中 7 张图已全部移除（Gitee raw 被 Marketplace 302 防盗链拦截），替换为一行 Marketplace 链接文字。截图改为 Marketplace 内置 Screenshots 区域网页上传（JetBrains CDN）。Custom Page HTML 已生成：`docs/marketplace-custom-page.html`（Feature Matrix + FAQ 11 条 + 快捷键 + 链接）。详见 [[pitfall-gitee-hotlink-302]]。

**2026-09-03 Marketplace 被拒（EAP trial widget false positive）**：提交 v2026.9.2 后审核团队邮件"Your plugin has compatibility issues"。Plugin Verifier **唯一**失败项：`Trial Widget should be present ==> expected: <true> but was: <false>`（`InstallPluginTest.testTrialWidgetPresence`）。**根因：验证器跑在 2026.3 EAP 上，EAP 版本不含 trial/评估组件，此检查必败——已知误报，非插件 bug。**
**实证（2026-09-03）**：本地 `./gradlew verifyPlugin -PverifyIdes=minimal`（243.28141.41 + 262.9437.185 稳定版）跑完 **BUILD SUCCESSFUL，0 兼容性错误**——仅 7 个 Deprecated API 警告 + 8 个 Experimental API 警告（不阻断发布）。三份验证报告已生成到 `build/reports/pluginVerifier/`。**确认 Marketplace 拒绝纯属 EAP 误报，插件无真实兼容性问题。**
处理方式：回复邮件说明是 EAP false positive（附 verifyMinimal 通过证据），要求用稳定版重验或标记误报；或重新上传。verifySmoke/verifyMinimal/verifyFull 三态验证通过 `-PverifyIdes` 切换。

**Why:** 用户从未在 Marketplace 发布过插件；此文档把官方查证结论固化为可执行清单，避免发布时重查官网或踩被拒坑。

**How to apply:** 首次发布按文档 §11 检查清单逐项执行；流程细节变更时回写文档。相关记忆：[[project-doc-conventions]]、[[project-m28-inflight]]（自更新机制）

**2026-08-18 进展（首次发布进行中）**：verifyPlugin 三版本全过 → buildPlugin 产物就绪 → 发行文档已提交推送（e191973）→ 用户正通过网页表单上传 v2026.8.18。**关键决策**：不用 GitHub 镜像，直接用 Gitee 作为 Marketplace 源码链接（官方要求「公网可达」Gitee 满足，审核若要求换 GitHub 再补）。经验证 Marketplace tags 非固定下拉——输入关键词自动补全，无 `sql`/`mybatis` 等预设，选最贴切的 ≥1 个即可。

> **⚠️ 上述「Gitee 作源码链接」决策已于 2026-10-22 变更（现状以此为准）**：三仓（插件 / demo / 官网文档站）均改为 Gitee 主 + GitHub 镜像，Marketplace 源码链接已改填 `https://github.com/SongRenShuo/mybatis-plus-sql-visualizer`（后台改完，线上 API `urls.sourceCodeUrl` 已复核生效）。理由＝受众匹配 + 可达性 + 口径风险，**不是因为在 Gitee 上过不了审**（9 个版本一路过审）。Gitee 仍是主仓，Release / `update.json` / 部署触发不动。现行口径见 `docs/发行说明/Marketplace发布流程-说明文档.md` §5。

**2026-08-18 Marketplace 被拒（Internal API 阻断）**：审核团队邮件"Your plugin uses the Internal API, which is private and must not be used outside the IntelliJ Platform itself." Plugin Verifier 扫描结果：2024.3 有 7 个 internal（6 被忽略→1 实际），2025.3+ 仅 1-2 个。5 个被点名的 API：①②③ `ToolWindowFactory.getAnchor()`/`getIcon()`/`manage()`（Kotlin synthetic bridge，源码未显式覆写）④ `ToolWindowManagerListener.stateChanged(TWM, TW, EventType)` 三参版（experimental+internal 双标记）⑤ `PluginManagerCore.getPlugin()`（源码显式调用）。修复计划见 T-M51 文档。官方文档（api-internal.html，2026-04-20 更新）给出完整替换表，`ToolWindowManager.registerToolWindow(String, RegisterToolWindowTaskBuilder)` 是 false positive（MP-6705）可忽略。