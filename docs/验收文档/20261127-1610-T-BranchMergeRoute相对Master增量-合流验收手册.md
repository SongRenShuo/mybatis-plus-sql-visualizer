# 本支相对 master 的增量与合流验收手册（T-BranchMergeRoute 第二次对齐轮）

2026-11-27 编写。对照基线：master `27c81e860` ↔ 本支 `merge-master-acceptance-doc` @ `0b13f0fbf`（合流提交）。合流前 master 领先本支 **254 提交**、本支独有 **145 提交**；合流后本支相对 master 的净增量为 **224 文件 / +13888 / −809**（含本手册；`src/main` 99 文件 / +3172 / −453）。

本手册只回答两件事：**本支比 master 多了什么、在 UI 上怎么验**。判定依据与分支级证据在 `20261112-T-HiddenBugPerfScan-合流验收文档.md`（45 条处置总账）、`20261109-T-SecurityAuditFixes-合流验收文档.md`（25 条安全线索），分域操作细节可回查 `20261019-2305-Dev相对Master新增与更新功能人工验收手册.md`。

---

## 一、准备

1. **安装本支构建包**：`./gradlew buildPlugin` → `build/distributions/mybatis-plus-sql-visualizer-*.zip` → Settings → Plugins → 齿轮 → Install Plugin from Disk → 重启。也可直接部署沙盒：`./gradlew runIde`（沙盒内已是本支代码）。
2. **版本核对**：Settings → SQL Visualizer → 关于，版本行与内部构建号在场。
3. **演示工程**：`mybatis-plus-sql-visualizer-demo`，IDE 里 Run `MybatisPlusDemoApplication`（8081）。**捕获类功能要求应用从 IDE 启动**（premain 注入依赖 Run 配置链）。
4. **工具窗**：Find Action（Ctrl+Shift+A）搜「SQL Visualizer」，四页签：SQL 日志 / 日志转 SQL / SQL 转 Java / SQL 历史。
5. **多方言准备**：4.1 的方言项按需连真库才有执行读数；没连库也能验「渲染 + 状态行 + 下拉项 + 格式化」四项（本支方言对象的渲染与格式化不依赖连接）。

---

## 二、合流口径（本次把 master 併进来时裁决了什么）

八处冲突逐 hunk 裁决，全部取「两边意图都在」的并集，没有整份取 theirs：

| # | 文件 | 裁决 |
|---|---|---|
| 1 | `log/SqlConsoleBridgeFilterProvider.kt` | 保留本支 `synchronized(ctx)`（券 C② 解析状态串台）+ master 的 `bindConsoleError(manager, it)` 显式传参 |
| 2 | `META-INF/plugin.xml` | 取 master 的 D263 精简描述，方言数按本支口径 **15→25**（中英各一处） |
| 3 | `agent/transport/EventBus.java` | 取 master 的 T4-1 WS 传输旁路，TCP 路径仍带本支 T-SAF-02 通道令牌 |
| 4 | `convention/BannedApiScanRuleTest.kt` | `ConvertToSqlAction` 豁免站点数按合并后实存重计 **9→2** |
| 5 | `convention/GuardSelfTestCoverageMetaGuardTest.kt` | 两边新增的登记条目并集（`EmbeddedNativeExecSourceScanTest` + `BatchOperationJoinGuardTest`） |
| 6 | `docs/LESSONS.md` | 两边同时占 L97~L101 —— master 保留 L97~L114，本支五条重编 **L115~L119** |
| 7 | `docs/架构设计/01-项目架构总览.md` | `sql` 行取 master 版 + 本支 `domestic/` 10 方言；`ui` 行取 master 版 |
| 8 | `docs/架构设计/03-开发与测试指南.md` | §3.2 类数按合流后磁盘实测重算（**1013** 个 `*Test.kt`），并保留本支 25 方言口径 |

**非冲突但合流后编译就红的连带断裂**：`:engine-jar:compileKotlin` 报 `Unresolved reference 'SqlPerfAnalyzer'`——本支券 Y3 给 `PerfRuleEngine` 加的 `com.intellij` Logger 让该文件被引擎投影剔除，引用闭包连带 `SqlPerfAnalyzer` 一起掉。修法按引擎「不改生产源码、只加投影」的约束补第 6 处投影（WARN 日志换 no-op）。**这条对验收的含义**：引擎导出的纯逻辑面（`EngineApi` 的 render/logs/check/perf）本轮重新可用，`./gradlew :engine-jar:jar` 能出产物。

---

## 三、本支相对 master 的增量总表

| 域 | 相对 master 的变化 | 主要编号 | UI 可见 |
|---|---|---|---|
| 数据库方言 | **新增 10 个国产/信创方言**（TiDB / PolarDB-X / PolarDB / GBase 8a / GBase 8c / 神通 Oscar / 优炫 UXDB / 人大金仓 AntDB / 虚谷 XuGu / 崖山 YashanDB），注册表 15→25，覆盖 URL 推断、角标、格式化、EXPLAIN、执行链、MCP、平台方言对 | T-DomesticDialects | 是 |
| 增强捕获 | **新方言驱动接线 14→25 族**（含虚谷/崖山两族新接线），拦截面扩到语句面 + prepare 面 | T-CaptureCoverage25 | 间接（列表带方言/耗时） |
| 预览控制台 | **Ctrl+Enter 恢复执行**（走插件确认链，不恢复原生 ▶），原生 ▶ 恒禁用并在 tooltip 说明原因 | T-EmbeddedConsoleExecRestore / T-SAF-04 | 是 |
| 参数与执行 | 参数导入解析器两缺陷修复、EXPLAIN 对话框面板树不再累积、只读动作不替换编辑器 | 券 I / 券 H | 是 |
| 分享与导出 | HTML 报告写盘移出 EDT；六处本地转义收敛到 `HtmlEscape`，封三个未转义汇聚点 | 券 Y1 / T-SAF-09+10 | 是 |
| 设置 | JSON 导入后即时发布 `SETTINGS_CHANGE`；面板 rebuild 监听器幂等；导入值级校验（`SettingsValueValidator`） | 券 V / Y15-Y16 / T-SAF-08 | 是 |
| 日志与历史 | 历史文件读取单遍化 + 块级读；去重 O(n²)→O(n)；线程桶静默老化淘汰；行尾 ms 正则重锚定；自动保存拒写符号链接；解析面统一 `maxParseLength` 截断 | 券 D/T/Q / T-SAF-13/21/22/23 | 间接（更快、长跑不涨） |
| 转换与反向 | HQL/JPQL 参数替换先掩码字面量；MPJ ON-lambda 片段通道；多列 `orderByAsc` 不再整体丢；列对列 `qualifiedName`；`OFFSET n ROWS` 给可行动文案；Querydsl 引用搜索限 `useScope`；`PageRequest.of` 双字面量；`selectFunc` 参数位优先级；and/or 块体 lambda 泄漏 | 券 A/B/L/N/X/Y8/Y9/Y12/Y17 | 是 |
| XML 动态 SQL | OGNL 大整数穿透收敛层 + 整型除法语义；递归深度 512 上限与 Err 契约（21 处异常封闭）；`TrimSqlNode` 无 prefix 不剥 `prefixOverrides` | 券 K/Y13 / T-SAF-19 | 是 |
| MCP 工具 | 四个文本工具 20000 字符输入上限；`sqlv_history_search` 输出按 `mcpMaskEnabled` 脱敏；执行链 `setReadOnly(true)` fail-closed；`SELECT INTO` 递归判定；检索正则长度+复杂度+求值预算 | T-SAF-05/06/07/11/24 | 间接 |
| 安全与更新链 | agent 通道双向令牌认证；更新包条目名拦截（zip-slip/盘符/ADS/设备名）；描述符改 SecureXml 真解析；`mavenCentral` 置前 + 依赖校验；wrapper sha256 锚；verifier pin | T-SAF-02/03/14/15/16 | 否（防线类） |
| 引擎导出 | 纯逻辑引擎 jar（`:engine-jar:jar`）在合流后重新可构建，`EngineApi` 四入口可用 | T-OpenIDEAPort + 本轮第 6 处投影 | 否 |

---

## 四、分域验收步骤

### 4.1 方言 15→25（T-DomesticDialects）

| 步骤 | 操作（UI 路径） | 预期 |
|---|---|---|
| 1 | Settings → SQL Visualizer → **SQL 预览** → 「数据库方言」下拉 | 共 **25** 个方言项（原 15 + 新增 10）；`AUTO` 仍在首位 |
| 2 | 选 `TiDB`（或 PolarDB-X / GBase 8a / Oscar / UXDB / AntDB / XuGu / YashanDB / PolarDB / GBase 8c），打开任一 Wrapper/XML 预览 | 预览头部方言行显示所选方言；渲染出的分页/引号风格跟随该方言（MySQL 家族的 `LIMIT` 形态、PG 家族的 `LIMIT … OFFSET`） |
| 3 | 同一预览点「格式化」（工具栏） | 关键字大小写、标识符引号按该方言输出；不报错、不回落 MySQL 文案 |
| 4 | gutter 图标打开 **INSERT** 预览 → 「更多」→「回滚 SQL」 | 回滚 DELETE 的目标表/主键位按该方言渲染（无 before-image 时按钮禁用并披露原因） |
| 5 | 连一个真库（如 TiDB）→ 预览点「执行 SQL」 | 方言闸放行（不出现「未知方言阻断」红字）；执行成功后状态行方言不变 |
| 6 | 「执行计划」（结构化关） | EXPLAIN 交原生控制台执行；TiDB/GBase 8a 的 EXPLAIN 档位已在本支修正（不再出「EXPLAIN 形态不支持」） |
| 7 | 数据库工具窗选中一张表 → 右键 → SQL Visualizer 的 CRUD 生成 | 生成 SQL 的方言与项目设置一致（不退回 MySQL） |
| 8 | MCP 客户端调 `sqlv_render_sql`，`dialect` 传 `Redis` 之类的未知名 | 回落 MySQL 并在响应里回显实际使用的方言名（不静默造错） |

### 4.2 增强捕获新方言接线（T-CaptureCoverage25）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | Settings → SQL Visualizer → **SQL 预览** 区内的「增强捕获」开关保持默认开 | 不需要额外配置 |
| 2 | IDE 里 Run demo 应用（连接一个已支持的国产库，如 TiDB / 虚谷 / 崖山） | idea.log 出现 agent attach / retransform 日志；目标应用不报错 |
| 3 | 浏览接口触发若干 DML/查询 | SQL 日志列表出条目，条目带**目标库 + 毫秒耗时**；悬停有调用栈，源码可跳 |
| 4 | 若该库驱动无公开 Maven 坐标（GBase 8a `com.gbase.` / 神通 Oscar `com.oscar.`） | 生成列显示「厂商未覆盖」并给替代路径说明，而不是假装捕获成功（`VENDOR_NOT_COVERED` 口径） |
| 5 | 观察列表「方言」列 | 与 4.1 的下拉项命名一致（不出现 `UNKNOWN`） |

> 真机读数已在分支侧取过：TiDB / GBase 8a / XuGu / GBase 8c 四腿均有 `EVENT` 帧（见 `docs/经验总结/20261118-1230-route合流前方言与增强捕获验证-经验总结.md`）。手册里这一步是**复验**，不是首次取证。

### 4.3 内嵌预览控制台执行入口（T-EmbeddedConsoleExecRestore + T-SAF-04）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 打开任一预览 → 点「执行 SQL」（无绑定数据源时先选数据源） | 进入执行视图，内嵌原生 Database 控制台出现 |
| 2 | 把光标放在内嵌控制台里按 **Ctrl+Enter**（mac：⌘+Enter） | **执行当前控制台文本**；若有写语句/`${}`/未知参数，仍弹对应确认框（与「执行 SQL」同一套门禁） |
| 3 | 找内嵌控制台的原生 ▶ 按钮 | **恒灰**——这是 T-SAF-04 的设计（低信任文本不走原生执行）；把鼠标停到插件「执行 SQL」按钮上，tooltip 明确写「内嵌控制台的原生 ▶ 已停用」 |
| 4 | 执行完看执行视图头部提示 | 含「Ctrl+Enter 可再次执行，返回预览可继续编辑原 SQL」 |
| 5 | 点「返回预览」 | 回到原 SQL 编辑器，内容未被执行结果替换 |
| 6 | 在内嵌控制台手改一行再 Ctrl+Enter | 执行的是**改后文本**（与原生 ▶ 同源取 `nativeEditorDoc.text`） |

### 4.4 参数导入（券 I）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 预览面板参数区 →「导入参数」→「从剪贴板导入」，粘一段**嵌套** JSON（`{"a":{"b":1},"c":"x"}`） | 顶层键 `a`、`c` 都在（不再丢失嵌套后的顶层键）；`a` 的值按原始形态给提示而非静默丢弃 |
| 2 | 再粘 `age>=18,name=bob` 这类**首行含逗号**的 key=value | 按 key=value 解析（不再被误判成 CSV 而整行进第一格） |
| 3 | 正常 CSV（首行表头 = 参数名） | 仍按 CSV 解析，导入计数与提示一致 |
| 4 | 导入后点「清除」再改一个值 | 只清本次、不串到别的方法（参数预设按声明方法隔离） |

### 4.5 EXPLAIN 结果对话框（券 H）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 同一预览连续点 5 次「执行计划」 | 每次都正常出结果；执行若干次后面板不出现「越来越慢/越拉越长」的堆积感（面板树常驻一份，不再线性累积） |
| 2 | 关闭对话框再重开 | 无残留、无「组件已 dispose」类异常 |
| 3 | Settings → SQL Visualizer → SQL 预览 → 开「结构化执行计划」后对 MySQL 源执行计划 | 表格形态结构化渲染 + 索引建议；PG 家族为树形 + 体检条；其他方言自动回落原生控制台 |

### 4.6 分享与导出（券 Y1 + T-SAF-09/10）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 造一条大 SQL（≥1MB，或用回放脚本粘入）→ 预览 →「更多」→「分享」→ **HTML 报告** → 保存 | 保存对话框期间 IDE **不冻结**（写盘已移出 EDT）；文件落地后浏览器打开排版正常 |
| 2 | 让 SQL 里含 `<script>`、`&`、`"`、`'` | 报告里这些字符**被转义**（不执行脚本、不破版） |
| 3 | 同一条 SQL 的 tooltip / Swing 标签（列表悬停、状态行） | 同样转义显示（六处本地变体已收敛到统一 `HtmlEscape`） |
| 4 | 「复制 Markdown / 表格 / 可执行 SQL」三出口 | 内容与编辑器一致；可执行 SQL 末尾分号与占位注释在位 |

### 4.7 设置（券 V / Y15-Y16 / T-SAF-08）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | Settings → SQL Visualizer → 「导入」（JSON）→ 选一份改了「方言 / 时间显示 / 脱敏」的配置 | 导入成功后**立刻生效**：日志列表的时间显示、方言行、脱敏出口都跟着变（不再需要重开面板/重启） |
| 2 | 反复点「应用」若干次 | 面板不重建、语言不误报变更、无重复监听器（幂等） |
| 3 | 手工把 JSON 里某数值改成病态值（如 `-1` / 超大数 / `"abc"`），再导入 | 明确报「设置导入失败：…」并保留旧值；**不静默接受**（`SettingsValueValidator` 值级校验；`version<1` 直接拒绝） |
| 4 | 项目级页 (Settings → SQL Visualizer (Project)) 的 MCP 子页点「应用」 | 项目级页走 `loadState(copy)`，与全局面板同口径持久化 |
| 5 | Settings → SQL Visualizer → **MCP 工具** → 关掉某个 `sqlv_*` 项后点应用 | 该工具不注册；Reapply/Revert 行为正常 |

### 4.8 SQL 日志与历史（券 D/T/Q + T-SAF-13/21/22/23）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 捕捉一批 SQL 使其自动落盘成历史文件 → 切「SQL 历史」页签 → 打开最大的那个文件 | 打开明显更快（历史读取单遍化 + 块级读，19MB 热路径分支实测 15~16.6s → 见券 D 读数）；超长行被按上限折叠，不出现无限缓冲 |
| 2 | 列表里连续触发同一模板的查询（≥200 次），观察列表 | 去重裁剪不冻结 UI（去重 O(n²)→O(n)；万级裁剪仍是瞬时） |
| 3 | 浏览带毫秒尾缀的日志行（`... 123ms`） | 耗时解析正确；不再因行尾空白增长而回溯爆炸（正则已重锚定） |
| 4 | 长跑 demo（把日志级别调高、刷几十万行）20 分钟以上 | 线程桶按静默老化淘汰，内存不随时间单调上涨；捕捉状态栏读数正常 |
| 5 | 关掉捕捉、只看历史列表 → 悬停条目 | tooltip/状态行转义正常；无「NaN/负耗时」类异常读数 |

### 4.9 转换与反向（券 A/B/L/N/X/Y8/Y9/Y12/Y17）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | HQL/JPQL 预览，参数值里含字面量 `':notParam'` 或 `'?1'` | 字面量**不**被替换成占位符；只有真占位符被回填 |
| 2 | MPJ 预览，`ON` 里用 lambda 调自定义方法（`on(X::getId, Y::getFid)` + `and(...)`） | 运行期拼接片段进 SQL，不再静默丢段 |
| 3 | 用字符串版多列排序：`orderByAsc("a, b")` | `ORDER BY a, b` 完整保留（不再整体丢） |
| 4 | 「日志 → SQL→Java」生成 Wrapper：SQL 里两表同名列做列对列比较（`a.id = b.id`） | 生成代码带 `qualifiedName`/别名前缀，双侧都已转义，可编译 |
| 5 | 反向转换 `... OFFSET 10 ROWS`（不带 `FETCH`） | 报**可行动**文案（如「OFFSET 需配合 FETCH」），不再报「缺少关键字 FETCH」 |
| 6 | 有 Querydsl `BooleanBuilder` 的工程打开预览 | 引用搜索限 `useScope`，不再扫全项目（首次打开不再卡） |
| 7 | JPA 预览 `PageRequest.of(page, 20)`（size 为字面量） | OFFSET 与参数行都在（不再丢） |
| 8 | `selectFunc` 三参形态 + 参数位运算符（`a + b AS sum_col`） | 别名位与聚合参数位正确，不被吞 |
| 9 | 表达式里 Char 分支带单引号（OGNL / 反向转换） | 单引号被转义，产出不断串 |
| 10 | Wrapper 里 `and(w -> w.eq(...).or().eq(...))` 嵌套 | 生成 SQL 无重复条件（块体 lambda 内的嵌套调用不再泄漏到外层链） |

### 4.10 XML 动态 SQL（券 K/Y13 + T-SAF-19）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | mapper XML 里造超宽整数比较（`<if test="x == 99999999999999999999">`） | 预览不炸；降级为显式 Err 文案 |
| 2 | 整型除法（`<if test="5/2 == 2">`） | 按整数除法判定（true），不再按浮点判断 |
| 3 | 嵌套 50000 层括号的表达式 | 有界失败（512 递归上限），报结构化错误，不 StackOverflow 打穿 IDE |
| 4 | `<trim prefixOverrides="AND ">` 但**没写** `prefix` | 不剥 `prefixOverrides`（对齐 MyBatis 原语义） |
| 5 | `<sql>` 片段 + `include` 组合预览 | 与真引擎渲染一致（`xml/fidelity` 对照集仍在） |

### 4.11 MCP 工具（T-SAF-05/06/07/11/24）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | MCP 客户端调 `sqlv_render_sql` 传 >20000 字符的 SQL | 结构化拒绝（不是超时、不是截断出假 SQL） |
| 2 | 另三个文本工具（`sqlv_logs_to_sql` / `sqlv_check_sql` / `sqlv_perf_advice`）同样超长 | 同上，统一上限口径 |
| 3 | 开「MCP 输出脱敏」后调 `sqlv_history_search` | 输出里的字面量被打码，审计行不含原文 |
| 4 | 让执行链连一个只读账号失败的数据源（或模拟 `setReadOnly` 抛错） | **fail closed**：拒绝执行并给结构化原因，不退化成「连上了就查」 |
| 5 | `SELECT ... INTO` 藏在 CTE / 派生表里 | 被 `McpSelectGuard` 拒绝（递归遍历 + 括号内词法兜底） |
| 6 | `sqlv_history_search` 传高危正则（嵌套量词 / 超长模式） | 快速拒绝（长度上限 + 复杂度初筛 + 求值预算） |

### 4.12 安全与更新链（不可见面，只做「不回归」抽查）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 目标应用里让 agent 连上后，用非本插件进程连同一 loopback 端口伪造 HELLO | 无令牌/错令牌的会话**不被提交**（T-SAF-02 双向令牌）；IDE 侧日志留痕 |
| 2 | 手工构造一个含 `../`、盘符、`CON`、ADS、尾点尾空格的更新 zip → 走本地安装包目录安装 | 被拒（`UpdateZipGuard` 条目名校验 fail-closed），不落盘 |
| 3 | 更新包的 `plugin.xml` 里把真 id 写成注释、假 id 写成正文 | 身份门按**真解析**判定（SecureXml 取代正则），不认注释里的假 id |
| 4 | Settings → SQL Visualizer → 更新与诊断 → 检查更新 | 正常走源；镜像顺序为 `mavenCentral` 优先（构建面改动，UI 无感） |

---

## 五、相对 master 的既有功能行为变化（按新行为判定）

1. **内嵌控制台原生 ▶ 恒禁用**，Ctrl+Enter 走插件确认链——不是功能丢失，是 T-SAF-04 定案；tooltip 已写原因。
2. **插件页描述按 master 的 D263 精简版**（不恢复本支扩写版），方言数仍报 25。
3. **结构化执行计划默认关**（master 侧既有默认），要看结构化靠设置页开关。
4. **历史读取按上限折叠**：超长非 revision 行不再整行常驻。
5. **参数导入的键口径变严**：嵌套 JSON 保留顶层键、首行含逗号优先 key=value——旧导入结果可能比过去「多出/少掉」项，属修复方向。
6. **设置导入失败即报错**：病态数值不再静默接受。
7. **`TrimSqlNode` 语义对齐 MyBatis**：无 `prefix` 时不再剥 `prefixOverrides`（旧渲染结果会变，属修正）。
8. **契约拒判/收窄项**（验收时按「已知边界」处理，别当缺陷）：券 Y7（`#` 注释与 iBatis `#param#` 不可区分）已整体回退；券 Y12 的 `OFFSET n ROWS` 只给可行动文案、不自动改写 SQL（模型不可表达）。

---

## 六、十分钟冒烟路径

1. Run demo → 开捕捉 → 浏览 `/user/1` → 列表出条目（带方言/耗时/源码链接）。
2. 双击条目开预览 → 方言行显示 25 项之一 → 工具栏「格式化」→ 头部方言行不变。
3. 预览「执行 SQL」（无源先选源）→ 进执行视图 → 控制台里 **Ctrl+Enter** 再执行一次 → 「返回预览」。
4. 预览「更多」→ 分享 → HTML 报告 → 保存（观察不冻结）→ 打开核对转义。
5. 「更多」→ 回滚 SQL（INSERT 预览）→ 无 before-image 时按钮禁用并有说明。
6. 切「SQL 历史」→ 打开最大文件 → 搜索 → 条目双击回预览。
7. Settings → SQL Visualizer：SQL 预览（方言下拉 25 项）→ 导入一份 JSON 后看时间显示是否即时变化 → 关于页核对版本。

---

## 七、验收记录表

| 域 | 冒烟 | 逐项 | 异常记录 |
|---|---|---|---|
| 4.1 方言 15→25 | ☐ | ☐ | |
| 4.2 增强捕获新方言 | ☐ | ☐ | |
| 4.3 内嵌控制台执行入口 | ☐ | ☐ | |
| 4.4 参数导入 | ☐ | ☐ | |
| 4.5 EXPLAIN 对话框 | ☐ | ☐ | |
| 4.6 分享与导出 | ☐ | ☐ | |
| 4.7 设置 | ☐ | ☐ | |
| 4.8 日志与历史 | ☐ | ☐ | |
| 4.9 转换与反向 | ☐ | ☐ | |
| 4.10 XML/OGNL | ☐ | ☐ | |
| 4.11 MCP | ☐ | ☐ | |
| 4.12 安全与更新链 | ☐ | ☐ | |
| 五、行为变化 8 项 | — | ☐ | |

---

## 八、正常现象速查（别当缺陷报）

- 内嵌控制台原生 ▶ 恒灰、Ctrl+Enter 有确认框：设计内（T-SAF-04 + 走插件门禁）。
- 列表没有方言/耗时列：条目来自控制台通道；增强通道才有这两列。
- 新方言只验渲染时「EXPLAIN 出不了结构化表格」：结构化只对 MySQL 家族与 PG 家族，其余方言按设计回落原生控制台。
- 未连真库时执行计划会弹数据源选择器：无绑定数据源的正常引导。
- 引擎 jar 与插件版本号不同步（`engineVersion` 默认 `0.1.0`）：两个制品的版本线本就解耦。
- 长历史文件打开仍需数秒：单遍化 + 块级读已经把热路径压到十几秒级，超大文件（>50MB）不在本轮优化面内。

---

## 九、本次合流的验证读数与证据边界

| 项 | 读数 | 出处 |
|---|---|---|
| 合流提交 | `0b13f0fbf`（parents：`f85b46d74` + `27c81e860`） | `git log --oneline -1` |
| 冲突 | 8 处，全部并集裁决（见 §二） | merge commit body |
| 引擎投影 | 第 6 处投影补齐，`:engine-jar:compileKotlin` BUILD SUCCESSFUL | 本机 gradle 1m10s |
| 编译面 | `compileKotlin` + `compileTestKotlin` + `:sql-visualizer-agent:compileJava` 全绿 | 本机 gradle |
| 定向测试 | `test --tests "cn.song.mybatisplus.visualizer.convention.*"` → 34 类 / 141 例 / 0 失败 0 错误 0 跳过 | JUnit XML 汇总 |
| 双仓推送 | Gitee + GitHub 均 OK（`push-all.ps1`，`x-access-token:***@`） | 脚本输出 |
| 分支 CI 第 1 轮 | `build-plugin.yml` `mode=gate`，run [#45963297818](https://github.com/SongRenShuo/mybatis-plus-sql-visualizer/actions/runs/45963297818)：`build` ✓ 5m13s、`agenttest` ✓ 3m2s、`verify×3` ✓、`marketplace×3` ✓、`test (fast)` ✓、shard 1/2/4/5 ✓，**`test (3)` ✗ 1 例** | `gh run view` |
| 该红点根因与修复 | `AgentManagerPremainTest.test回连_建会话_写幂等表_置在线态` 用裸 HELLO（master `95ecbea44` 2026-11-21 新增），撞本支 `0495ade42`（T-SAF-02 令牌身份门）→ 会话建不起来。改走带令牌的 `writeHello`，定向 16 例全绿；教训入账 `LESSONS L120` | `bbc37e1fe` |
| 分支 CI 第 2 轮 | 修复后重 dispatch：[#45966781755](https://github.com/SongRenShuo/mybatis-plus-sql-visualizer/actions/runs/45966781755)（本手册撰写时运行中，结论以该 run 为准） | `gh run list` |
| UI 真机走查 | **未做**——本手册即走查脚本，按 §四 执行后回填 §七 | — |
| WS 通道令牌 | **未覆盖**：T-SAF-02 的 HELLO 令牌回带只接在 TCP 路径；WS（T4-1）由 IDE 主动连 loopback 端口，监听端只绑 `127.0.0.1`，无令牌校验 | `EventBus.start` 注释 + 本手册待办 |
| 全量分片 / 三目标门禁 | 以 CI run 的 `testShard1..5` + `verifyGate` 为准，本机不重跑 | CI 日志 |

**证据边界**：UI 相关结论（§四 各步骤的「预期」）来自代码路径与既有分支读数，**本轮没有做真机 UI 走查**——沙盒跑批与截图证据缺失，按「未验」记；CI 的 gate 结果只覆盖构建与自动化测试面。

---

## 十、与既有文档的关系

- 本手册＝**本支 vs master 的增量视角**，只列多出来/变了的能力与验收路径。
- 分支级处置总账：`20261112-T-HiddenBugPerfScan-合流验收文档.md`（45 条）、`20261109-T-SecurityAuditFixes-合流验收文档.md`（25 条）。
- 分域细步骤与全量矩阵：`20261019-2305-Dev相对Master新增与更新功能人工验收手册.md`、`20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`。
- 计划与实施记录：`docs/开发计划/` 下 `20261109-2345-国产信创数据库方言扩展`、`20261111-0550-T-HiddenBugPerfScan`、`20261118-1205-T-CaptureCoverage25`、`20261109-0924-T-EmbeddedConsoleExecRestore`、`20261114-1149-T-BranchMergeRoute`。
