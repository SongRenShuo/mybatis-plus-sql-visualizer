# T-McpNarrative MCP「置信度 vs 证据」叙事升级 — 开发计划

- 日期：2026-09-16 03:04
- 任务编号：T-McpNarrative（第五轮新建议 **N-19**，P1）
- 来源：第五轮报告 §8.3 N-19——mapper_check 使位差叙事从「校验 vs 渲染」升级为「置信度 vs 证据」：CodeHelperPro validate_mybatis_mapper 给 agent 置信度数字，本插件对渲染产物出七规则+性能可复核证据。随 T-MergeDev2Master 发行一并做（半天量级）
- 性质：描述文本与文档口径修正（含一处描述与实现不符的顺手修正）
- 实施分支：合流后自 master 拉任务分支 t-mcp-narrative
- 验证基线：合流后 master 全量 testShards 绿（文本改动以 bundle parity 守卫为主门禁）

---

## 一、背景与依据

第四轮 N-16 写入的位差叙事（「确定性渲染器非校验结论」）在 MCP 工具 3→10 之后需要二次升级：mapper_check 的 simulate 已对渲染产物跑七规则写风险检测与性能分析，叙事立点应从「我们不做校验」改为「我们给的是校验给不出的证据」。同时本轮锚点核实发现两处描述失真：RenderSqlTool 的 @McpDescription 声称 `-- SQL #N` 多语句分隔（T-MG-02 写入的家族约定句），但其实现每次只渲染单语句（Outcome 单 sql）——描述与实现不符要修正；plugin.xml 注释仍写旧「三只读工具」口径。工具描述是 agent 选择工具的依据，失真与升级都要趁发行一次做完。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 「非校验」句 | 仅 RenderSqlTool 与 RenderLogsTool 有 | `RenderSqlTool.kt:21-23`、`RenderLogsTool.kt:30-31` |
| 「配对」句 | 遍布 8 工具 @McpDescription | SchemaContextTool.kt:19-20、ExplainTool.kt:22-24、CheckSqlTool.kt:30-32 等 |
| 证据句雏形 | mapper_check 已有「deterministic evidence a static-only validator cannot produce」 | `MapperCheckTool.kt:33-35` |
| 描述失真 | RenderSqlTool 声称 `-- SQL #N` 分隔句，实现为单语句 Outcome | `RenderSqlTool.kt:20-22` vs `McpSourceLocator.kt:46-54` |
| plugin.xml 注释旧口径 | mcp optional depends 注释块仍写「SqlVisualizerToolset 三只读工具」 | `META-INF/plugin.xml:86` |
| README/marketplace | 位差句各一处（中文） | `README.md:105-108`、`src/main/resources/html/marketplace-custom-page.html:311-318` |

## 三、方案

**T-MN-01（描述准确性修正）**：RenderSqlTool @McpDescription 的多语句分隔句改为如实口径（单语句渲染；多语句走 render_logs）；plugin.xml:86 注释更新为十工具口径。

**T-MN-02（置信度 vs 证据标准句）**：以 MapperCheckTool.kt:33-35 为底本定标准句（中英），落三处：README:108、marketplace 页 :318、mapper_check @McpDescription 升格；其余九工具描述做一致性 sweep（「Deterministic」家族词保持，不堆营销句——描述是 agent 的选择依据，克制红线）。

**T-MN-03（文档同步）**：CHANGELOG [Unreleased] Changed 1 条；第五轮报告 N-19 状态回填。

## 四、验收标准

1. grep 十工具 @McpDescription 无「三只读」残留、RenderSqlTool 无多语句失真句。
2. 「置信度 vs 证据」句在 README/marketplace/mapper_check 三处措辞一致。
3. bundle parity 守卫绿（若动 bundle 键）；全量 test 绿。

## 五、真机走查清单（留人工）

1. Marketplace 描述重发后人工读一遍 MCP 行（随 N-17 ② 发行动作执行）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 描述过度营销 | agent 靠描述做工具选择，浮夸句适得其反 | 标准句一句说清形态差，全 sweep 不加长 |
| 文本改动触发 bundle parity | 双语键漏改 | parity 守卫测试兜底 |

## 七、实施顺序

```
T-MN-01（失真修正）→ T-MN-02（标准句三处落点+sweep）→ T-MN-03（文档同步）
```

## 八、实施记录（实施后回填）

**2026-09-17 实施完毕（提交 6a7dff7，dev 分支直接交付）**

- **T-MN-01**：RenderSqlTool @McpDescription 删「多语句按 `-- SQL #N` 分隔」失真句（实现为单语句渲染、Outcome 单 sql），改如实口径「一次一条，多候选取首个并在输出注明，多语句日志走 sqlv_render_logs」；plugin.xml 注释「SqlVisualizerToolset 三只读工具」旧口径更新为「十个只读工具，一工具一 toolset 类」。
- **T-MN-02**：置信度 vs 证据标准句定稿（以 MapperCheckTool 证据句为底本）——英文「validators return a confidence score; this returns deterministic evidence a static-only validator cannot produce」/中文「校验器给的是置信度评分，这里给的是确定性证据——静态校验器产不出的渲染产物实证」，三处措辞一致落地：mapper_check @McpDescription 升格、README MCP 小节、marketplace 页 MCP 行（中英两侧同步替换）；其余九工具 @McpDescription 一致性 sweep 完成——deterministic 家族词十工具全覆盖（grep 实证）、无「三只读」残留、无失真句，克制不加长。
- **T-MN-03**：bundle 零动键（parity 守卫 5 例绿）；CHANGELOG [Unreleased] Changed 条目与第五轮报告 N-19 状态回填随本轮文档批次执行（报告回填留主会话）。
- **测试**：BundleParityGuardTest 5 + MapperCheckToolTest 4 + RenderLogsToolTest 4 定向全绿。
- **真机走查留人工**（§五）：Marketplace 描述重发后人工复核 MCP 行（随 N-17 发行动作）。
