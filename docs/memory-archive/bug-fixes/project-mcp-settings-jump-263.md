---
name: project-mcp-settings-jump-263
description: T-McpSettingsJump（20261111）——MCP 设置页跳转在 2026.3 被误报「需要 2025.2+」，根因是平台把设置页搬进新 content module；已实施、已合 master 推双仓、2026.3 真机验收通过
metadata:
  node_type: memory
  type: project
  originSessionId: sess_caac2fc3-7cbf-4a55-ac71-7508bff92738
---

用户真机（IDEA 2026.3 / `263.7307.200`）点 设置 → 工具 → SQL Visualizer → MCP 工具 页的
「点击前往」，弹出「需要 IDEA 2025.2+ 并启用 MCP Server 插件」，用户称之为「被拦截」。

**根因（20261111 字节码实证）**：2026.3 的 mcpserver 插件改用 `<content namespace="jetbrains">`
模块形态，设置页搬进 `plugins/mcpserver-plugin/lib/modules/intellij.mcpserver.frontend.jar`，
包名从 `com.intellij.mcpserver.settings` 改为 **`com.intellij.mcpserver.frontend.settings`**；
旧 FQN `com.intellij.mcpserver.settings.McpServerSettingsConfigurable` 在整安装（lib + plugins，
含 `lib/modules/`）**零命中**。本插件把实现类名当能力探针（`Class.forName`）→ 返回 null →
走进「不可用」分支。横幅探 `com.intellij.mcpserver.McpToolset`（仍在公开模块
`intellij.mcpserver`）故为真 ⇒ 同帧自相矛盾。页面 id 没变：263 声明仍是
`id="com.intellij.mcpserver.settings"`（`parentId="ai"`，页面名 `MCP Server`）。

**工具线不受影响**：插件 id 仍是 `com.intellij.mcpServer`、EP `mcpServer.mcpToolset` /
`mcpToolFilterProvider`、`McpToolset.class` / `annotations/McpTool` 全在。

**已实施（20261111）**：新增 `mcp/McpSettingsNavigation.kt` 单一入口——类名候选链
（新 FQN 在前、262 旧 FQN 在后）→ `ShowSettingsUtilImpl.showSettingsDialog(project, id, null)`
按页面 id → 公开 `ShowSettingsUtil` 的 String 重载 → 都失败返回 `UNAVAILABLE` 并打一行带候选链
与 id 的 warn（真机排障就靠这行）。`McpConfigurable` 与 `PluginSettingsPanel`（零调用方死副本）
改调该入口；`settings.mcp.unavailable` **键名不动**只改文案（去掉版本断言，改为「确认插件已启用
或搜索 MCP Server」，幽灵键守卫零改动）。新增 `McpSettingsNavigationTest` 3 例，其中一条是
源码守卫：设置页类名只允许出现在候选链文件（防再写单点 `Class.forName`）。

**提交与状态**：`3106e4c6`（码）+ `65ac43b3`（回填：计划实施记录、索引状态、CHANGELOG、
LESSONS **L31**）→ merge `7525f5e1` → 索引 `e60b63a6`；`push-all.ps1 -Branch master` 已推 Gitee
+ GitHub（回读双仓同 `e60b63a6`）；工作树 `.worktrees/t-mcp-settings-jump` 与分支已清理。

**读数**：分支定向 27/0/0/0（新 3 + BannedApi 15 + McpScope 5 + SettingsReality 4）+
`McpConfigurableGateTest` 12/0/0/0；合并态复跑 7 类 51/0/0/0（加 BundlePlaceholderArity 4、
GuardSelfTestCoverageMetaGuard 8）；反向验证把候选链改回单条旧 FQN **恰 1 红**、恢复复绿。

**验收（2026-11-11）**：用户真机确认「验收没问题」——不再弹「需要 2025.2+」，跳转可用（A2 通过）。
未单独取证的一点：究竟是类名路（新 FQN 加载成功）还是 id 路命中，用户侧只给了「可用」一档；
要区分看 `idea.log` 有无 `MCP settings page unreachable:` 那行（无 = 两条路之一已通）。

**仍未做**：A3 旧版本回归面（本机无 2026.2 安装，未确认用户环境有旧版可比，只靠候选链静态保证）；
V1（新 FQN 能否跨 content module 从本插件 classloader 加载）未单独分离验证，回退顺序保证不影响可用性；
V3（平台在页面 id 不存在时是抛异常还是打开根页）未实验；A4/V5 2026.3 工具线回归未单独看。

计划文件：`docs/开发计划/20261111-1115-T-McpSettingsJump-MCP工具页点击前往在2026.3被拦截-开发计划.md`
（§九 实施记录含落地对照表）。跨平台手法见 [[reference-intellij-open-settings-page-by-id]]；
同类平台搬家史见 [[project-consolebind263-20261107]]、[[pitfall-platform-api-rename-vs-capability-probe]]。