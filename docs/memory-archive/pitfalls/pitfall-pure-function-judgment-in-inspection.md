---
name: pitfall-pure-function-judgment-in-inspection
description: 把「文本→分析」纯函数搬进 LocalInspectionTool 不能直接复用——入口是 PSI 元素而非文本，且
  inspection 只扫源文件、覆盖不到预览/日志里的临时 SQL
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 纯函数判定搬进 inspection 的两处错位（20261031 立项核实时发现）

规划/审计类报告常写「把 X 工具的判定搬到 `LocalInspectionTool` + QuickFix」，听起来是接线活。本仓核实 `sqlv_check_sql` / `sqlv_mapper_check` 后确认有两处错位，任何同类迁移都会遇到：

**错位一：入口形态不同。** 两个判定都是 `checkSql(sql: String)` / `mapperCheck(text: String, simulate, dialect)`——「文本 → 分析结果」的纯函数，普通 JUnit 可直测。而 `LocalInspectionTool` 的入口是 `buildVisitor` + `ProblemsHolder`，拿到的是 **PSI 元素**。中间必须有一层「PSI → 文本」适配（取 `element.text`、必要时展开 include、归一化缩进），这层不是免费的，且它的正确性直接决定误报率。

**错位二：覆盖面被 inspection 的扫描面限制。** inspection 只跑在**源文件**上（mapper XML、Java/Kotlin 注解）。预览面板、日志还原、MCP 调用里的 SQL 都是临时文本/虚拟文件，**不在 inspection 覆盖面内**。所以「把能力搬到编辑器」实际只覆盖「源文件里写死的 SQL」；若按「万能」估算范围，规划与工期都会严重偏差。

**做法建议**：判定逻辑保持纯函数不动（可测性不变），inspection 做成薄壳（只做 PSI 取数 + report），匹配在纯逻辑层——本仓 `BannedApiInspection` 就是这个范式（规则表为空时 `buildVisitor` 直接返回 `EMPTY_VISITOR`，零 daemon 行为；先廉价短路再 `resolveMethod()`）。误报率必须在真实语料上跑到 0 才允许默认开。

相关：[[project-s2-new-capabilities]]（NEW-26 的五条首批规则与 P-1~P-4 前提）、[[pitfall-new-capability-hits-repo-wide-convention-guards]]（新增 inspection 会撞弹窗克制/元守卫登记等既有约定守卫）。
