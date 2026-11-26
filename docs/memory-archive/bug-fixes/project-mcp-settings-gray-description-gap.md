---
name: project-mcp-settings-gray-description-gap
description: 平台「公开的工具」设置页 sqlv_* 灰字＝工具名的字节码级根因与修复——T-McpToolDisplayDesc
  已实施并合流推送双仓（merge d88413eb0，gate run 44863040432 监听中），待真机两态点验
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6f289730-45e5-4f1f-b9fc-46c9df376be5
---

**缺口（2026-11-21 用户截图提出）**：IDEA 2026.2.1 设置 → AI → MCP 服务器 → 公开的工具页，本插件 11 个 sqlv_* 工具行下灰色小字只重复工具名；用户追加要求「灰字跟插件语言变」（插件设中文灰字就是中文）。

**根因（平台 mcpserver-plugin 字节码实证，`plugins/mcpserver-plugin/lib/intellij.mcpserver.jar`）**：
1. 设置页灰字 = `McpToolDescriptorKt.getPresentableDescription()` = `displayDescription ?: description`——`displayDescription` 非空时**不回落**到 @McpDescription 长文（description 本身是好的，tools/list 里完整）。
2. 平台构建描述符时 `displayDescription` = 调 toolset 的 `displayDescription(fallback)` 钩子，**fallback 就是工具名**（ToolsetReflection_utilKt：`toolset.displayDescription(toolName)`）。
3. 我方 `McpToolsetSupport.displayDescription(fallback)` 旧实现＝透传 fallback（D239 修 263 三钩子时的兜底形态）→ 灰字 = 工具名；无参版返回 `""`。

**实施（2026-11-21 完成，分支 `t-mcp-tool-display-desc`，基于 master `9f4937fb8` 计划册，工作树 `.worktrees/t-mcp-tool-display-desc`，提交 `e6e5ace` + 落账 `7757d37`）**：
- **关键实施修正**：计划册草案的「新增 11 键」**未落盘**——bundle 已有同用途键 `settings.mcp.tool.desc.<尾段>`（McpConfigurable 门控区 `checkBoxText` 在用，11 键中英双语对称），改**复用既有键**。派生规则＝`removePrefix("sqlv_").replace("_", "")`（**全局**去下划线，`sqlv_render_logs`→`renderlogs`）。
- 改动面：`McpToolsetSupport` 两钩子改读 bundle（带参版缺键回落 fallback、无参版缺键空串）+ companion 提取 `toolDescKey` 单一派生源（McpConfigurable 改引，防两处内联漂移）+ `MpSqlVisualizerBundle.messageOrNull`（缺键 null，与 `message` 的 key 兜底区分）+ `McpToolsetShellFactoryTest` 三钩子守卫期望随行为更新（空串/透传 → bundle 简述）+ 新测试 `McpToolsetDisplayDescriptionTest` 6 例。
- **读数**：定向 **35/0**（6+11+5+13，XML 核对）；反向（钩子改回透传）**恰 2 红**（恰是「灰字＝工具名」断言面，其余 4 例不红不漂移），还原后 17/0 复绿；反向前先提交防 checkout 连坐。
- 语言跟随：bundle 按 `PluginSettings.state.language` zh/en/AUTO，钩子每次开设置页实时调 → 灰字自动跟插件语言；MCP 协议层给 agent 的英文长描述不动（受众分离）。零 `com.intellij.mcpserver.*` 静态引用红线保持（bundle 是插件自有类）。

**收尾（2026-11-21 用户四连令「合流 清理 推送 监听」全执行）**：
- **合流**：master merge `d88413eb0`（--no-ff，零冲突——分支与 master 同基）；合并后与分支 tip 同一棵树，定向 35/0 读数按复用前提沿用未重跑。
- **清理**：工作树 `.worktrees/t-mcp-tool-display-desc` 已 remove、分支已 `branch -d`（-d 成功＝git 确认已合入）。
- **推送**：双仓 master 推送成功（push-all.ps1 令牌通路，`x-access-token:***@` 在案无弹窗）。
- **监听**：push master **不触发 CI**（build-plugin.yml 仅 workflow_dispatch，runs 列表实证）→ 手动派 `mode=gate`：run **44863040432** @ `d88413eb0`，`ci-status.py wait` 后台监听，结论以 `gh run view --json conclusion` 为准。
- **计划册措辞校准 `ef463dc`**（用户追问「新增 11 键相当于落盘了吧」后订正）：那 11 键**早已随 T-McpToolset2 在 bundle 里**，本任务交付＝把既有键接入新消费面（原只门控区一处用），「未落盘」措辞已改为「复用既有键接入新消费面，无需新增」——读档勿再误判为缺交付物。

**待办**：① 用户真机点验（中文态/英文态两态目视，改插件语言重开「公开的工具」页）；② gate run 44863040432 结论回收后归档（红则按读数修复）；③ 灰字描述功能随下一轮发行进 tag（本轮 gate 只是 master 门禁，未发行）。

相关：[[project-d238-mcp-toolset-expansion-partial]]（D239 三钩子修复的来龙去脉）｜[[reference-zcode-mcp-schema-overhead-idea-dbx]]（DebugTools 插件同面归因）。
