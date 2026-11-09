---
name: project-s2-new-capabilities
description: S2 新能力批立项（20261031）——NEW-26 Inspection/NEW-21 信创体检/首用即生效/NEW-23 MCP
  写路径/S1-6 描述文案；锚点核实推翻「判定可直接复用」与「有批量渲染 API」两处前提
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# S2 新能力批立项（20261031）

册：`docs/开发计划/20261031-2315-S2新能力批立项-开发计划.md`（已入索引）。承第二十四轮审计册 S2-1~S2-4 与 S1-6，**只立项不实施**，不新占 D/V/R/O 号（沿用 NEW-21~26）。用户口径：一致性收口派子代理做、**新能力立项主控做**。

## 锚点核实推翻了报告的两处前提（立项前核实定例的又一次收益）

- **NEW-26 Inspection 体系**：审计册原话「把 `sqlv_check_sql`/`mapper_check` 的判定搬到 `LocalInspectionTool`」——实测两个判定都是**「文本 → 分析」纯函数**（`SqlVisualizerCheckSqlTool.checkSql(sql: String)`、`SqlVisualizerMapperCheckTool.mapperCheck(text: String, simulate, dialect)`，内部渲染后调 `SqlPerfAnalyzer.analyze`），而 inspection 入口是**「PSI 元素 → ProblemsHolder」**。⇒ 不能直接复用，须加「PSI → 文本」适配层；且 inspection 只扫**源文件**，覆盖面从「万能」收窄为 **mapper XML + 注解 SQL**（预览面板/日志里的临时 SQL 覆盖不到）。详见 [[pitfall-pure-function-judgment-in-inspection]]。
- **NEW-21 信创兼容体检**：**无批量渲染 API**——`DialectRegistry.dialects` 只被 UI 下拉/门禁/方言探测消费，渲染仍是「按当前方言一次一种」⇒ 须新建编排层，成本比审计册估的上调一档。
- 另核：`onboarding/` 只有 `OnboardingAnchorTip.kt` 一个类（预热与状态栏入口均为新建）；NEW-23 三工具（render/explain/rollback_plan）都在，属接线活；S1-6 的 `plugin.xml` description 里「预览」仍是高频词，且受 `PluginXmlStructureGuardTest` 四条约束。

## 五券要点

1. **NEW-26**（P1，性价比最高）：覆盖 mapper XML + 注解 SQL；判定复用不重写；首批五条规则（`${}` 注入、`#{}` 参数缺失、resultMap 列不存在、方言不兼容写法、OGNL `test` 恒真/恒假）；QuickFix 只做「跳转到方法/参数」与「改为 `#{}`」；**误报为 0 才允许默认开**（语料＝`docs/需求文档/MPJ用法收集与兼容性测试清单.md` 的 92 文件）。
2. **NEW-21**（P2）：新增 `MultiDialectRenderPlan` 编排层（逐方言调既有渲染入口、差异比较在编排层）；四档差异（保留字/分页语法/函数不存在/隐式转换），隐式转换若不可静态判则降级不做并标注；落点推荐 `DiagnosticReportFormatter` 新段（零新增 UI）。
3. **首用即生效**（P2）：项目打开预热（BGT，禁 EDT 慢操作，本仓有 Prewarm 前科）+ 状态栏常驻 widget + 首次一次性引导（复用 NEW-19 防打扰三重约束）。
4. **NEW-23 MCP 写路径**（P2，接线活）：render → explain → rollback → 执行串成链，**执行环必须走既有闸门**（`UnboundedWriteDetector`／只读豁免证明／`ExecutionDialectGate`），**不新增 MCP 工具数**。
5. **S1-6 描述文案**（P1，与发行绑定）：从「能预览 SQL」改为「14 方言 + 7 家信创 + EXPLAIN 解读 + 回滚计划」；首 40 字符英文摘要须自成语义；数字与代码对账；走发行时序（`preserveUIEdits=false` 随上传覆盖）。

## 九条待验证前提（P-1~P-9）

P-1 mapper XML 的 PSI 文本是否含 `<include>` 展开（不含会误报）；P-2 注解 SQL 文本是否需归一化；P-3 inspection 在 daemon 线程调 `mapperCheck` 的 read-action 纪律（参照 `BannedApiInspection` 薄壳）；P-4 五条规则在 92 文件语料上误报率须为 0；P-5 逐方言渲染是否需 project/PSI 上下文；P-6「隐式转换」能否静态判定；P-7 预热是否与 `T-SqlDialectPrewarm`（S1~S5 已落 master、R1/R2 运行期读数缺）重复；P-8 状态栏 widget 在 Community 版可用性；P-9 三步链中间态（渲染结果/before-image）放哪且不泄漏 SQL 明文。

## 实施顺序与红线

顺序：S1-6 → NEW-26 → NEW-23 → NEW-21 → 首用即生效。红线：不增 MCP 工具数、不绕写安全闸门、inspection 误报 0 才默认开、预热不上 EDT、描述走发行时序、不碰 `update/update.json`。

## 实施进展（20261031 深夜起）

用户令「派多个子智能体后台全部实施，可以从 dev 新建分支和工作树」。四路已派：`t-s2-inspection` / `t-s2-mcpchain` / `t-s2-xcinspect` / `t-s2-onboarding`（各带独立工作树）。**S1-6 描述文案由主控收尾**——改动已落 dev 工作树（`plugin.xml:32-33` 两行摘要），正文 20 条 li 一字未动（避免打破守卫的数字对账与双语同构）；静态自查已过（首 40 字符全 ASCII、声明 14 ↔ 代码 14、无 `<hr/>`/注释、li 仍 20），观感预览件已生成（`build/desc-preview.{html,png}`，chromium headless 截图）。**⚠️ 文案被用户连否两轮，仍未定稿**：第一版把中文首句换成「14 种数据库方言，执行计划解读与回滚计划」→ 用户「还不如 服务未启，SQL 先知」；第二版保留口号在句首、新能力作后半句（「服务未启，SQL 先知。…还原 14 种数据库方言的可执行 SQL…并解读执行计划、生成回滚语句」）→ 用户仍判「没有之前有吸引力」，并令「**生成多个待选方案让我选择**」⇒ 已产出 A/B/C/D 四方案配对照预览件（`build/desc-variants.{html,png}`），**等用户挑**。详见 [[feedback-slogan-over-capability-list]]（含四方案原文与「原文案是最强候选」这条判据）。**结论：这一券的正确姿势是「默认不改原文案 + 拿不准就给多方案」**，不是自己选一版改。待定稿后才跑 `PluginXmlStructureGuardTest` 并提交。**验证脚本的假绿陷阱注意**：对 `.properties` 文件用变量拼接「中文键名」再判断子串恒为真。

**NEW-21 已实施完成并合入 dev `edc1d83f`**：
- `sql/MultiDialectRenderPlan.kt` 508 行**纯逻辑**编排层（`SqlGenerator` 一行未改）；双形态输入——`Model`（语句模型 → 逐方言真渲染，分页/引用形态随方言变）与 `Text`（只有文本 → 产物即原文，**不伪造渲染结果**）
- **P-5 证伪**：逐方言渲染**不需要** project/PSI 上下文（`SqlGenerator` 类 KDoc 自述纯函数、import 面只有 `model` 包；无 `Project` 实例跑通 14 方言即为证据）⇒ 编排层做成纯函数、普通 JUnit 直测
- **P-6 部分成立**：完整「隐式转换」判不了（需列类型 + 字符集/排序规则 + 目标库转换规则），但**「数值列 vs 明显非数值字符串字面量」这一子集可判**，且各库行为相反（MySQL 族静默转 0、PG/Oracle 报 invalid input syntax）——迁移时正是真风险，故档四按此子集落地（未降级为不做）；纯文本输入无类型证据时不判并在 `notes` 如实标注
- 耗时**均值 1.4ms**（14 方言，判据 200ms）；代理备注 `elapsedMillis` 是整型会把亚毫秒压成 `0.00ms`，计时用例改 nanoTime 直测
- 四档（保留字/分页/函数/隐式转换）各配正例+反例，**四档逐条反向验证**（摘判定→对应用例必红→还原）；零新增 UI 面（接进 `DiagnosticReportFormatter`），bundle 中英各 +20 键 parity 通过
- 诚实边界：函数目录只登记**有证据的 15 个**，未收录一律不判——刻意不登记 openGauss/GaussDB 的 `GROUP_CONCAT`、SQLite 的 `NOW()`（无文档无实测，不用族推定冒充事实）；纯文本路径的标识符抽取是启发式（`SELECT order FROM t` 这类「结构关键字当列名裸用」会漏报，KDoc 已写明漏报面）

## 四券全部完成并合入 dev（20261101 凌晨，dev 已推双仓 tip `faf49ce8`）

- **NEW-26**（`faf49ce8`，最大一件）：五条规则全落地（`${}` 注入 / `#{}` 参数缺失 / resultMap 属性 / 方言引号 / OGNL 恒真）+ 两类 QuickFix（`${}`→`#{}`、跳 mapper 方法）。**P-1 证伪**：`<include>` 在 PSI 文本里**不展开**（`XmlTag.text` 只有 `<include refid=.../>`，`XmlMapperParser` DOM 链才展开）⇒ 扫描面按源文件字面设计。**P-2 不需要归一化**（只做字符级 token/引号扫描）。**P-3 不调 `mapperCheck`**（它是挂起函数 + audit 包装，daemon 不该走）。**P-4 语料误报 0**，且转录 X01 时抓到的真实命中（节选丢 bind 致 `chargeDegreeSum` unknown property）**未当误报掩盖**、单列用例钉住。反向验证两轮 5 红 / 6 红后归零，inspection 包 90 例绿。
- **平台坑（重要）**：`<localInspection language="any">` 会让整条检查**被静默跳过**——平台把 "any" 当语言 ID 去 `Language.findLanguageByID("any")` 得 null，按语言分桶时整条丢掉；表象是「EP 内建实例能跑、`myFixture.enableInspections` 跑不出来」。**必须写空串**（= 全语言）。
- **NEW-23**（`258e391e`）：给 `sqlv_check_sql` 加 `chain`/`primaryKeyColumns`/`dialect` 三个**可选参数**——`chain=false` 时输出**逐字节不变并有用例钉住**（给既有工具加参数的正确姿势）；工具数 11→11；闸门反向验证 3 红后复绿；执行环不新增通道，交 IDE 确认流。
- **首用即生效**（`9a12344d`）：**P-7 查到 `SqlDialectMappingPrewarm` 早已存在**（三档 API），缺的只是「项目打开」触发点 ⇒ 只补一行 `prewarm(project)`，不重造、不调 `prewarmAndAwait`、**故意不做索引强预热**（mapper 发现走平台 `FileTypeIndex`，主动预热只有拖慢启动或重复平台工作两条路）。**P-8 用平台 EP 源码证**（243 `PlatformExtensionPoints.xml` 里 `statusBarWidgetFactory` 是 `dynamic="true"` 且不在 database 插件面）⇒ Community 可用。三重约束落实（`mapperPrimerVersion` 版本粒度 / `tryClaim` 弹前写+应用级单次锁 / headless 不探测不写记忆），48 例 + 11 类守卫绿。

## 与在册计划的关系

第二十四轮审计册的 **S1（留底+合流+发行）与 S1-4（一致性收口五项）不在本册范围**；本册五券已在全景盘点 `20260916-1730` §〇-辛 登记，实施后回填状态。竞品对照：CHP 已发 MCP(3.5.7)+Java Agent(3.6.8)，真实缺口正是代码生成 + Inspection（见 [[project-panorama-round24]]）。
