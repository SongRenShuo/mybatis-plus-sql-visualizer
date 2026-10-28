---
name: project-mcp-architecture-form
description: MCP 承载形态定案——注册进 IDEA 平台内置 MCP Server（现状）而非插件单独起 MCP server；核心理由 + 观察点
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8d4430b0-b259-4e11-ac06-fd35abff6796
---

MCP 功能承载形态评审（20260917）：**注册进 IDEA 平台内置 MCP Server（现状 A）比插件单独提供 MCP server（B）更合适**，定案维持 A。

核心判据：插件 MCP 绝大部分工具的竞争力强依赖 IDE 进程内资源，离开 IDE 进程做不到或要重造——`sqlv_render_sql` 靠 McpSourceLocator 走 PSI 源码定位（护城河），`sqlv_schema_context`/`sqlv_explain` 复用用户在 IDE 里配好的数据源/控制台链，全靠进程内 Project/索引/连接。独立 server（B）要自管生命周期（端口/stdio/多窗口实例）+ 跨版本子进程 JDK17 SDK 限制，且源码定位/schema 能力脱 IDE 进程整体退化，等于把插件核心再抄一份还丢差异化——投入产出负。

A 的代价只有两个：绑定 IDEA 2025.2+（243 不支持）、只能被 IDE 内 agent 用（AI Assistant/Claude Code/Junie 走 use_idea_mcp），接不了无 IDE headless CLI。A 的兼容债（mcpStub 桩源集/253·262 跨版本签名守卫/verifyGate 三目标/设置页门控/[MCP-AUDIT] 审计/只读三 hint）已摊薄，换成 B 会换汤不换药重来。

结论建议：MCP 能力本身保留并随 T-MergeDev2Master 合流上线，但**不再投入**——先看真实 agent 用户采纳率（调研口径下载 +37/日但 0 评论第三周），市场侧暂无实证。`sqlv_query_rows` 留决策位不实施、无 IDE 工具类（DBX 清单 #27~#34）跟踪均维持观察点。B 仅当前瞻备选：当 agent 生态明确无 IDE headless CLI、且用户接受放弃源码定位渲染核心差异化时再起独立只读 SQL 工具 server。

## 十工具清单（20260918 全量核对，均 `mcp-optional.xml` 注册 `mcpServer.mcpToolset` EP，一工具一 toolset 类）

| 工具 | 分组 | 入参 | 能力依赖 |
|---|---|---|---|
| `sqlv_render_sql` | 渲染三件套 | filePath+line / xmlSnippet+sourceType | **需 IDE**（PSI 定位） |
| `sqlv_schema_context` | 渲染三件套 | tables(1~50)/dataSource/maxTables(默认8 上限20) | **需 IDE**（数据源） |
| `sqlv_explain` | 渲染三件套 | selectSql/dataSource/dialect（含 notices·errorPosition·index hints） | **需 IDE**（建连） |
| `sqlv_render_logs` | 工作流四件套 | logText + 四类前缀覆盖 + mixedPrefixMatching | 纯逻辑 |
| `sqlv_check_sql` | 工作流四件套 | sql | 纯逻辑 |
| `sqlv_rollback_plan` | 工作流四件套 | sql/primaryKeyColumns | 纯逻辑 |
| `sqlv_perf_advice` | 工作流四件套 | sql（20 规则） | 纯逻辑 |
| `sqlv_crud_sql` | 生成核查三件套 | filePath / className | **需 IDE**（PSI 实体） |
| `sqlv_reverse_sql` | 生成核查三件套 | sql | 纯逻辑 |
| `sqlv_mapper_check` | 生成核查三件套 | xmlSnippet / filePath / simulate | 片段纯逻辑；filePath 需读文件 |

配套面：设置页「MCP 工具」子页（总开关 + 逐工具启用禁用）、只读三 hint + SELECT 白名单 fail-closed、拒绝走 `McpExpectedError`、建连 `setAskPassword(false)`、每调用 `[MCP-AUDIT]` 审计。**十工具全在 dev，master 树无 `mcp/` 目录**，随 T-MergeDev2Master 上线。

## 「实际开发提效」评估口径（20260918，用户问「作用大不大」时的答复基线）

- **战略上大、日常使用频率中偏低**：差异化价值=确定性渲染（Wrapper 链/动态 SQL → 带真参数可执行 SQL），正是 LLM agent 最易幻觉处；`explain`/`check_sql`/`perf_advice` 给的是静态校验器产不出的证据形态。这是把核心能力延伸到 agent 时代的护城河型能力，非锦上添花。
- **提效是三条件性的**：①前置门槛=IDEA 2025.2+ 且用户已在用接 IDE 的 agent，纯 IDE 手动用户完全触达不到（零提效）；②`crud_sql`/`reverse_sql`/`perf_advice` 在 IDE UI 本有对应入口，MCP 增量只是「改从 agent 调」，真正高频的是 agent 改 MP 代码时自动调 `render_sql`/`explain`；③维护成本（mcpStub/跨版本守卫/verifier 豁免/门控）不为零但已被摊薄。
- **答复口径**：结论=「面向 agent 用户的差异化能力、低风险、值得保留合流，但非现在就能兑现的日常高频提效」；行动建议=随合流上线后观察采纳率再定二期内核（`sqlv_query_rows` 留位不实施维持）。