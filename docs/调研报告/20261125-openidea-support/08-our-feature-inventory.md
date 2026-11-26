# 08 · 我方功能与代码域全量清点（OpenIDEA 移植底表）

> 清点对象：`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer`（master 工作树，2026-11-25）。
> 目的：为「用 OpenIDEA 插件体系（Rust WASM）在全新仓库复刻」提供「我方到底有什么、依赖什么、哪些逻辑与平台无关」的映射底表。
> 方法：只读静态清点——读 `docs/架构设计/`、`plugin.xml` 与 5 个可选描述文件、`src/main/kotlin` 全树、`sub-projects/sql-visualizer-agent`，并用脚本统计文件与行数、按 `import com.intellij.*` 判定「纯逻辑」边界。**未跑任何 gradle / 构建 / 测试**。
> 口径约定：**纯逻辑**＝输入为文本/文件内容/参数、输出为 SQL 或分析结果、源码不 import 任何 `com.intellij.*` / `org.jetbrains.kotlin.psi.*` 类型；**需等价替代**＝逻辑本身平台无关但当前实现挂在某个平台类型（如 `IElementType`、`Project`）上，替换该外壳即可；**平台独占**＝必须靠 PSI/UAST/索引/daemon/EDT/DB console 才能成立。
> 注：仓库内 `docs/架构设计/` 实际只有 `01/02/03` 三篇 + 8 篇专题（含 `预览触发面清单-说明文档.md`），**不存在 `子系统-*.md` 文件**；本报告按 `01-项目架构总览.md` §2 的六层分层与 §3 的 23 个顶层包重建子系统分组。详见文末「清点盲区」。

---

## 0. 规模与代码域总表

### 0.1 文件与行数（脚本实测）

| 域 | 文件数 | 行数 | 备注 |
|---|---:|---:|---|
| `src/main/kotlin`（613 个 `.kt`） | 613 | 170,676 | 唯一生产 Kotlin 源树，23 个顶层包 |
| `src/main/java` | 3 | 194 | 工具窗口工厂 / 工具窗可见性监听 / `MinSearchSession`（规避 Kotlin + 平台 API 的 Verifier/`@NotNull` 问题，`docs/架构设计/01-项目架构总览.md:50`） |
| `src/main/resources` | 68 | — | `plugin.xml` 771 行 + 5 个 optional 描述文件 + 2 份 bundle + 24 组工具栏 SVG（亮/暗双份）+ DTD 与模板 |
| agent 子项目 `src/main`（Java） | 33 | 8,054 | `sub-projects/sql-visualizer-agent`，构建期内嵌进插件 jar |
| agent 子项目 `src/childapp` | 2 | 222 | 端到端验证用子进程 |
| agent 子项目 `src/test` | 59 | 13,496 | 独立 JVM 测试 |
| 插件测试 `src/test`（Kotlin） | 994 | 232,339 | 测试规模是生产的 1.36 倍 |

### 0.2 纯逻辑占比（按 import 判定）

| 口径 | 纯逻辑文件 | 纯逻辑行 | 占比（文件 / 行） |
|---|---:|---:|---|
| 全量 `src/main/kotlin` | 245 / 613 | 47,709 / 171,267 | 40.0% / 27.9% |
| 核心引擎 16 包（`model` `converter` `converter/jpa` `sql` `sql/*` `xml` `xml/ibatis` `reverse` `generator` `psource` `util`） | 116 / 173 | 26,168 / 41,583 | 67.1% / 62.9% |
| 其中真·零平台渗透的子域 | `model` 17/17、`sql/perf(+rules)` 7/7、`sql/extract` 4/4、`generator` 3/3、`xml` 20/23、`xml/ibatis` 9/12、`reverse` 6/7 | — | 见下表包级明细 |

按包级纯逻辑明细（纯文件数 / 纯行数 ÷ 文件数 / 行数）：

| 包 | 纯 / 总（文件） | 纯 / 总（行） | 包 | 纯 / 总（文件） | 纯 / 总（行） |
|---|---|---|---|---|---|
| `model` | 17/17 | 1780/1780 | `xml` | 20/23 | 3634/4388 |
| `sql` | 23/31 | 6432/7862 | `xml/ibatis` | 9/12 | 2601/3158 |
| `sql/perf`+`sql/perf/rules` | 7/7 | 1381/1381 | `reverse` | 6/7 | 3213/3525 |
| `sql/extract` | 4/4 | 472/472 | `generator` | 3/3 | 352/352 |
| `sql/rollback` | 1/2 | 636/834 | `converter` | 2/17 | 1044/5317 |
| `sql/schema` | 1/1 | 245/245 | `converter/jpa` | 6/17 | 2013/7417 |
| `log` | 39/78 | 8566/25806 | `log/agent` | 10/15 | 1508/4361 |
| `mcp` | 10/32 | 1635/6203 | `execute` | 22/41 | 2875/14195 |
| `util` | 12/23 | 1953/2909 | `psi`（含 `psi/jpa`） | 4/36 | 497/14242 |
| `ui`（含全部子包） | 23/164 | 3902/47465 | `config` | 3/13 | 469/6994 |

结论一句话：**核心算法层（模型 / 渲染 / 解析 / 还原 / 规则 / 生成）在 63% 行数上已与平台解耦**；平台耦合集中在触发面（`ui/gutter` `ui/nav` `ui/inject` `ui/ref` `ui/highlight` `ui/completion`）、PSI 定位（`psi` `psi/jpa`）、执行链（`execute`）三处。

### 0.3 平台注册面 census（`plugin.xml` 实测）

| 类别 | 数量 | 证据 |
|---|---:|---|
| `codeInsight.lineMarkerProvider` | 19（主文件）+ 1（`kotlin-optional.xml`）= 20 注册 / 19 个类 | `src/main/resources/META-INF/plugin.xml:259-331,766-769`；`META-INF/kotlin-optional.xml:6-7` |
| `completion.contributor` | 7 注册 / 6 个类（`XmlSqlCompletionContributor` 双语言注册） | `plugin.xml:361-383,466-467` |
| `gotoDeclarationHandler` / `gotoRelatedProvider` | 3 / 1 | `plugin.xml:281-284,750-756` |
| `psi.referenceContributor` | 2（MyBatis XML / iBatis XML） | `plugin.xml:483-484,748-749` |
| 注入与编辑辅助 EP | `multiHostInjector`、`typedHandler`、`lang.syntaxHighlighterFactory`、`lang.foldingBuilder`、`backspaceHandlerDelegate`、`daemon.changeLocalityDetector`、`colorSettingsPage`、`spellchecker.support`、`defaultLiveTemplates`、`liveTemplateContext`、`externalAnnotator`、`codeInsight.inlayProvider` 各 1 | `plugin.xml:342-355,463-511` |
| `consoleFilterProvider` | 1（控制台行捕获主通道） | `plugin.xml:150` |
| `runConfigurationExtension` | 1（premain 注入） | `plugin.xml:156` |
| `postStartupActivity` | 5（主文件）+ 1（mcp）+ 1（acp） | `plugin.xml:426-446`；`mcp-optional.xml`；`acp-transport-optional.xml` |
| `fileBasedIndex` | 3（Mapper namespace / sqlMap namespace / sqlMapConfig 别名） | `plugin.xml:243-257` |
| `toolWindow` | 1（右侧 SQL 日志，4 tab + 1 可选工作台 tab） | `plugin.xml:453-455`；`log/SqlLogPanel.kt:933-935,1453,1464` |
| `notificationGroup` | 6 | `plugin.xml:388-425`；`messages/MpSqlVisualizerBundle.properties:336,1397,1605,1720,1721,1807` |
| `registryKey`（运行期开关） | 13 | `plugin.xml:518-582` |
| `applicationConfigurable` / `projectConfigurable` | 8（1 文件夹 + 7 子页）/ 1 | `plugin.xml:165-237` |
| `action` | 8（主文件）+ 1（`database-optional.xml`）= 9 | `plugin.xml:598-697`；`database-optional.xml` |
| `sql.dialect` + 配对 `lang.parserDefinition` | 5 + 5（DM / GaussDB / HighGo / Vastbase / GoldenDB） | `database-optional.xml` |
| `modelRelationProvider` / `diagram.Provider` | 1 / 1（JOIN 虚拟外键 / 原生 JOIN 图） | `database-optional.xml`；`diagram-optional.xml` |
| `standardResource`（iBatis DTD 离线映射） | 8 | `plugin.xml:721-736` |
| `@Service`（注解式服务，不在 XML） | 17 个文件 | 见 §1 A15 |
| 方言总数 | 15（`Dialect` 实现）+ 5（注册进平台的 parser 方言） | `sql/Dialect.kt:423-1225`；`DialectRegistry` `Dialect.kt:1259` |
| MCP 工具 | 11（全部 `sqlv_*` 只读） | `mcp/McpToolGate.kt:31-42` |
| 静态性能规则 | 21（6+8+6+1 四批） | `sql/perf/rules/PerfRulesBatch1..4.kt`（各文件 `override val ruleId` 计数） |
| EXPLAIN 方言模板 | 15 条登记，14 条接入（GoldenDB 诚实不接入） | `sql/ExplainSupport.kt:130-186` |
| 设置持久化字段 | 118 个 `State` 字段（7 应用子页 + 1 项目页） | `config/PluginSettings.kt:34`；UI 行约 109 处 `Row(...)`（`config/PluginSettingsPanelSections.kt`） |
| 双语 bundle 键 | 1,557 × 2 文件 | `messages/MpSqlVisualizerBundle.properties` / `_zh.properties` |
| 自定义图标入口 | 27 个（`MpvsIcons`），24 个工具栏 SVG 资源 | `ui/icons/MpvsIcons.kt`；`src/main/resources/icons/` |

---

## A. 功能清单表（按子系统分组）

列说明：**触发面**＝用户实际能摸到的入口；**平台能力**＝该功能成立所依赖的平台设施；**耦合度**＝纯逻辑 / 需等价替代 / 平台独占。

### A1 触发面 · gutter 行标记（20 注册 / 19 个 provider 类）

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F01 | Wrapper 链预览（Java）：Lambda/Query/Update/Delete + 服务链 + 工厂 + 批量 + 空 Wrapper 写族 | 代码行 gutter 图标 | PSI 解析 + 类型/接收者判定 + 行标记 EP | 平台独占 | `plugin.xml:259-260`；`docs/架构设计/预览触发面清单-说明文档.md:10` |
| F02 | Kotlin Wrapper 链预览（ktQuery/ktUpdate 文本级；不确定实体则隐藏图标） | Kotlin 行 gutter | Kotlin 可选依赖 + PSI 文本 | 平台独占（且刻意 fail-closed） | `kotlin-optional.xml:6-7`；`预览触发面清单-说明文档.md:51` |
| F03 | MyBatis 注解 SQL 预览（@Select/@Insert/@Update/@Delete + Provider 族 + `<script>`） | `@` token gutter | PSI 注解 + 行标记 EP | 平台独占 | `plugin.xml:265-266` |
| F04 | mapper XML 语句预览（select/insert/update/delete 动态 SQL） | 标签行 gutter | XML PSI + 行标记 EP | 平台独占 | `plugin.xml:269-270` |
| F05 | Mapper XML→Java 跳转箭头 | XML 语句行 gutter | XML PSI + Java 索引查询 | 平台独占 | `plugin.xml:272-273` |
| F06 | Mapper Java→XML 跳转箭头 | 接口方法行 gutter | Java PSI + 自定义索引 | 平台独占 | `plugin.xml:274-275` |
| F07 | maintainPage/pages XML 预览（`<datasource>` 自动判 SQL/HQL） | 标签行 gutter | XML PSI | 平台独占 | `plugin.xml:286-287`；`预览触发面清单-说明文档.md:13` |
| F08 | hbm.xml 命名查询预览（`<query>`/`<sql-query>`） | 标签行 gutter | XML PSI | 平台独占 | `plugin.xml:289-290` |
| F09 | 任意 XML 内嵌 SQL 兜底预览（叶子标签文本首词命中 SQL 关键字） | 标签行 gutter | XML PSI | 平台独占 | `plugin.xml:292-293`；`预览触发面清单-说明文档.md:23` |
| F10 | iBatis 2.x `<sqlMap>` 语句预览（含 `<dynamic>`/`<isNotNull>` 子标签） | 标签行 gutter | XML PSI（O(1) 标签名+父标签） | 平台独占 | `plugin.xml:297-298`；`预览触发面清单-说明文档.md:24` |
| F11 | Spring Data 派生方法预览（findByXxx，须 `extends *Repository`） | 方法名行 gutter | Java PSI + 继承解析 | 平台独占 | `plugin.xml:301-302` |
| F12 | JPQL / `@Query` 预览 | `@` token gutter | Java PSI 注解 | 平台独占 | `plugin.xml:305-306` |
| F13 | CriteriaQuery / CriteriaBuilder 链预览 | 方法行 gutter | Java PSI 文本链判定 | 平台独占 | `plugin.xml:309-310` |
| F14 | Hibernate HQL（`session.createQuery`）预览 | 方法行 gutter | Java PSI | 平台独占 | `plugin.xml:313-314` |
| F15 | 自定义 DAO HQL 预览（`commonDao.findByQuery` 等） | 方法行 gutter | Java PSI + 调用形态 | 平台独占 | `plugin.xml:317-318` |
| F16 | JdbcTemplate 字符串 SQL 预览（StringBuilder 拼接 + `:name`） | 方法行 gutter | Java PSI 实参定位 | 平台独占 | `plugin.xml:321-322` |
| F17 | 实体级 `@NamedQuery`/`@NamedQueries` 预览 | `@NamedQuery` 的 `@` token | Java PSI | 平台独占 | `plugin.xml:325-326` |
| F18 | 自研 DAO 方法定义锚点（方法体含字符串 SQL 时在方法名行出图标） | 方法定义行 gutter | Java PSI 方法体结构遍历 | 平台独占 | `plugin.xml:330-331` |
| F19 | iBatis XML→Java 跳转箭头（带「Java 侧真实存在调用点」门） | `<sqlMap>` 语句行 gutter | XML PSI + 词索引 | 平台独占 | `plugin.xml:766-767`；`预览触发面清单-说明文档.md:26` |
| F20 | iBatis Java DAO 调用行（预览图标 + 跳转箭头双标记，三态目标解析） | DAO 调用行 gutter / Alt+Shift+Q | Java PSI + 索引 | 平台独占 | `plugin.xml:768-769`；`预览触发面清单-说明文档.md:25` |
| F21 | MBG Example 条件按 mapper 调用点还原（`${criterion.condition}` → 真实列/算子） | 复用 F04 的 gutter | `ReferencesSearch` 调用点反推 + dumb 闸门 | 平台独占 | `预览触发面清单-说明文档.md:28-37`；`ui/MbgExampleCallResolver.kt` |

### A2 触发面 · Action / 右键菜单 / 快捷键

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F22 | 源码→SQL 转换动作（主入口） | 编辑器右键 / `Alt+Shift+Q` | Action 系统 + 编辑器选区 + PSI | 平台独占 | `plugin.xml:598-610` |
| F23 | 选中 SQL→MyBatis-Plus Java 代码 | 编辑器右键 / `Alt+Shift+J` | Action 系统 + 选区（不依赖 PSI） | 需等价替代 | `plugin.xml:614-622` |
| F24 | 打开「SQL 预览」独立工作台 | Tools 菜单 / `Alt+Shift+P` | Action + 工具窗口 | 平台独占 | `plugin.xml:627-635` |
| F25 | 实体类右键生成 CRUD SQL | 编辑器 / 项目视图右键 | Action + PSI 实体自省 | 平台独占 | `plugin.xml:640-647` |
| F26 | 数据库表节点右键生成 CRUD SQL | Database 工具窗右键 | `com.intellij.database`（可选依赖）+ DbTable 元数据 | 平台独占 | `database-optional.xml`（`DatabaseViewPopupMenu`） |
| F27 | 表名快捷模板三连（EXPLAIN / COUNT / 抽样 10 行） | 编辑器右键子菜单（不设默认键位） | Action + 选区 + 内嵌控制台 | 平台独占 | `plugin.xml:659-681` |
| F28 | 错误一键上报 | Help 菜单 | Action + `errorHandler` EP + HTTP | 需等价替代 | `plugin.xml:692-697,401` |

### A3 工具窗口与面板（展示层）

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F29 | 主侧边栏工具窗口 4 tab（SQL 日志 / 日志→SQL / SQL→Java / 历史） | 右侧栏图标 | ToolWindow + Swing UI | 平台独占 | `plugin.xml:453-455`；`log/SqlLogPanel.kt:933-935,1453` |
| F30 | 「SQL 预览」独立工作台 tab（粘贴 SQL/mapper 片段 → 识别 → 格式化/填值/复制） | 主侧边栏 tab（Registry 可关） | ToolWindow + 编辑器组件 | 平台独占 | `log/SqlLogPanel.kt:1464`；`plugin.xml:539-540` |
| F31 | 统一预览弹窗（SQL / JOIN 图 / 列信息 + 执行 + Diff + 分享 +「更多」折叠菜单） | gutter / 右键 / 快捷键 21 处入口共用 | Dialog + Editor + Swing | 平台独占 | `ui/SqlPreviewPanel.kt:146`；`ui/SqlPreviewDialog.kt` |
| F32 | 预览面板参数区分区（可勾选条件 / 参数预设 / 参数历史 / 折叠） | 面板左侧参数栏 | Swing + 文档模型 | 需等价替代（逻辑在 `ui/ParamHistoryCache` 等纯函数） | `ui/SqlPreviewPanelParamPanel.kt`；`ui/ParamHistoryCache.kt` |
| F33 | 日志→SQL 面板（组头命名、原文/还原双视图） | tab | Swing + 编辑器 | 平台独占 | `log/LogToSqlPanel.kt` |
| F34 | SQL→Java 面板（反向链路交互） | tab | Swing + 编辑器 | 平台独占 | `log/SqlToJavaPanel.kt` |
| F35 | SQL 历史面板（钉住 / 正则检索 / 导出 / 两条对比 / 两级导航） | tab | 原生搜索会话 + Swing | 平台独占 | `log/HistorySqlLogPanel.kt`；`ui/search/MinSearchSession.java` |
| F36 | 最近执行记录对话框 | 面板工具栏 | Dialog + 历史存储 | 需等价替代 | `log/RecentExecutionsDialog.kt` |
| F37 | 调用栈悬停弹层（调试器风格逐帧跳转） | 捕获条目悬停 | Editor 悬停 + 栈帧模型 + 导航 | 平台独占 | `ui/StackFrameHover.kt`；`ui/StackDigestFrames.kt` |
| F38 | 独立工作台「粘贴即体检」（解析产物补静态性能建议） | 工作台粘贴 | 与 F30 共用 | 纯逻辑（规则）+ 需等价替代（外壳） | `plugin.xml:545-546`；`ui/standalone/` |

### A4 正向渲染 · Wrapper / MPJ / 其他 ORM

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F39 | LambdaQuery/Query/Update/Delete Wrapper → 语句模型 → SQL | gutter / 弹窗 | PSI 链定位 + 表达式求值 | 平台独占（定位）+ 纯逻辑（转换与渲染） | `converter/WrapperConverter.kt:23,47`；`converter/LambdaQueryConverter.kt` |
| F40 | 服务链（`service.lambdaQuery()` / `this.lambdaQuery()`） | 同上 | 接收者家族判定（PSI 类型） | 平台独占 | `预览触发面清单-说明文档.md:47` |
| F41 | `Wrappers` / `ChainWrappers` 工厂链 | 同上 | PSI 方法名 + 限定符判定 | 平台独占 | `预览触发面清单-说明文档.md:46` |
| F42 | MPJ 联表（MpjLambda / MpjQuery / MpjLambdaQuery） | 同上 | PSI + 实体多表解析 | 平台独占 | `converter/MpjLambdaConverter.kt:45` |
| F43 | MPJ 联表 UPDATE / DELETE（UpdateJoin / DeleteJoin） | 同上 | PSI | 平台独占 | `converter/UpdateJoinConverter.kt`；`converter/DeleteJoinConverter.kt` |
| F44 | 批量调用（`updateBatchById` 族） | 同上 | PSI 方法名白名单 | 平台独占 | `psi/BatchOperationConverter.kt` |
| F45 | 空 Wrapper 写族（`mapper.delete(null)` / `service.remove(null)` / `mapper.update(e,null)`） | 同上 | PSI 精确形态 + 接收者守卫 | 平台独占 | `预览触发面清单-说明文档.md:49` |
| F46 | xbatis（QueryChain/UpdateChain/DeleteChain） | 同上 | PSI + SPI | 平台独占（定位）+ 纯逻辑（转换） | `converter/XbatisConverter.kt` |
| F47 | Easy-Query 直解析 | 同上 | PSI 前置派发（不进 SPI） | 平台独占 | `converter/EasyQueryConverter.kt`；`01-项目架构总览.md:149` |
| F48 | MyBatis Dynamic SQL（MDS）直解析 | 同上 | PSI 前置派发 | 平台独占 | `converter/MdsConverter.kt` |
| F49 | Querydsl 直解析 | 同上 | PSI 前置派发 | 平台独占 | `converter/QuerydslConverter.kt` |
| F50 | Kotlin Wrapper 文本级识别 | Kotlin gutter / 显式动作 | Kotlin PSI 文本 | 平台独占（且刻意降级） | `psi/KtWrapperDetector.kt`；`预览触发面清单-说明文档.md:51` |
| F51 | 实体→表名/列名推断（前缀 / 后缀 / 驼峰 / 忽略字段 / 列名校准） | 设置页 + 自动 | `JavaAnnotationIndex` + 模块作用域 | 平台独占（索引/模块） | `psi/EntityClassFinder.kt`；`psi/ColumnNameCalibrator.kt`；`config/PluginSettings.kt:220-267` |

### A5 正向渲染 · XML / 注解 / iBatis

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F52 | mapper XML 动态标签（if / foreach / choose / where / set / trim / bind） | gutter / 工作台 | XML PSI + 自研 SqlNode 树 | 需等价替代（渲染纯逻辑，取文本需 XML 解析） | `xml/XmlScriptBuilder.kt:19`；`xml/IfSqlNode.kt`；`xml/ForeachSqlNode.kt`；`xml/TrimSqlNode.kt` |
| F53 | `${ew.customSqlSegment}` 展开 | 自动 | XML 文本 | 纯逻辑 | `xml/XmlEwExpander.kt` |
| F54 | `<include>` 跨命名空间片段 + 缺失占位注释 | 自动 | 自定义索引 + XML PSI | 需等价替代 | `xml/XmlIncludeExpander.kt`；`psi/XmlCrossNsFragmentResolver.kt`；`plugin.xml:243-244` |
| F55 | OGNL 受限子集求值（test / bind / `${}`） | 参数条件勾选联动 | 无（自研解析器） | 纯逻辑 | `xml/Ognl.kt:23,38` |
| F56 | 注解 SQL（@Select 等 + `<script>` 内动态标签） | gutter | PSI 注解字符串 | 需等价替代 | `xml/AnnotationSqlParser.kt`；`ui/AnnotationSqlPreviewService.kt` |
| F57 | iBatis 2.x 渲染（dynamic/isNotNull/iterate/selectKey/parameterMap/`#name#`） | gutter / 工作台 | XML PSI（DOM） | 需等价替代 | `xml/ibatis/SqlMapParser.kt:22`；`xml/ibatis/SqlMapScriptBuilder.kt`；`xml/ibatis/SqlMapSqlRenderer.kt:107` |
| F58 | iBatis `prepend` 语义与 iterate 上下文 | 自动 | 无 | 纯逻辑 | `xml/ibatis/SqlMapPrependMarker.kt`；`xml/ibatis/SqlMapConditionEvaluator.kt:21` |
| F59 | MBG Example 条件还原（`andXxx`/`orXxx` → 真实列名算子） | F04 的 gutter | `ReferencesSearch` + 调用点解析 | 平台独占 | `预览触发面清单-说明文档.md:28-37` |
| F60 | pages XML 数据源（`type` / 实体相关性 / 根名 → SQL 或 HQL） | gutter | XML PSI + 实体解析 | 平台独占 | `xml/PagesSourceLanguage.kt`；`ui/PagesXmlPreviewService.kt` |

### A6 正向渲染 · JPA / Hibernate

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F61 | Spring Data 派生查询（PartTree：And/Or/Between/In/OrderBy…） | gutter | PSI 方法名 + 实体元数据 | 需等价替代 | `converter/jpa/DerivedQueryConverter.kt:36`；`converter/jpa/PartTreeParser.kt:25` |
| F62 | JPQL / `@Query`（含 nativeQuery） | gutter / NamedQuery | PSI 注解 | 需等价替代 | `converter/jpa/JpqlConverter.kt`；`converter/jpa/HqlConverter.kt` |
| F63 | HQL（session.createQuery / 自定义 DAO） | gutter | PSI 方法链 | 需等价替代 | `psi/jpa/HqlQueryLocator.kt`；`psi/jpa/CustomDaoHqlLocator.kt` |
| F64 | CriteriaQuery / CriteriaBuilder | gutter | PSI 调用链 + 变量追踪 | 平台独占（需类型推断） | `converter/jpa/CriteriaQueryConverter.kt`；`psi/jpa/CriteriaQueryLocator.kt` |
| F65 | Specification（`where(...)` lambda） | gutter | PSI lambda 体 | 平台独占 | `converter/jpa/SpecificationQueryConverter.kt` |
| F66 | Query by Example | gutter | PSI 对象构造 | 需等价替代 | `converter/jpa/QueryByExampleConverter.kt` |
| F67 | `@NamedQuery` / `@NamedQueries` | gutter | PSI 注解 | 需等价替代 | `psi/jpa/NamedQueryLocator.kt` |
| F68 | hbm.xml 命名查询 | gutter | XML PSI | 需等价替代 | `psi/jpa/HbmXmlEntityResolver.kt`；`converter/jpa/HqlConverter.kt` |
| F69 | `@Embedded` 列展平命名（NONE / COMPONENT_PATH，三级优先） | 自动（设置页可覆盖） | 模块配置探测 + PSI | 平台独占（模块锚点） | `psi/jpa/EmbeddedColumnNaming.kt`；`psi/jpa/HibernateNamingConfigProbe.kt` |
| F70 | JPA 实体映射（@Table / @Column / 继承 / 库内实体） | 自动 | PSI + 项目索引 | 平台独占 | `psi/jpa/JpaEntityResolver.kt`；`psi/jpa/JpaMetadataReader.kt` |
| F71 | Hibernate 命名策略探测（Spring Boot 配置） | 自动 | 文件系统 + PSI | 需等价替代 | `psi/jpa/HibernateNamingConfigProbe.kt:46` |

### A7 正向渲染 · JdbcTemplate / 字符串 SQL

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F72 | JdbcTemplate 方法调用 + 实参 SQL 定位（O(1) 方法名白名单） | gutter | PSI 实参 | 平台独占（定位） | `psource/JdbcTemplateMethodDetector.kt:21`；`psource/JdbcTemplateSqlLocator.kt` |
| F73 | StringBuilder / 字符串拼接聚合 | 自动 | PSI 表达式树 | 平台独占 | `psource/SqlStringConcatenator.kt` |
| F74 | `:name` 具名参数（NamedParameterJdbcTemplate） | 自动 | 无 | 纯逻辑 | `psource/NamedParamParser.kt` |
| F75 | 自研 DAO 字符串 SQL（方法体内字符串 SQL 提取） | gutter 方法定义锚点 | PSI 方法体 | 平台独占 | `ui/gutter/StringSqlMethodGutterLineMarkerProvider.kt` |

### A8 参数与语义还原（跨来源共用）

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F76 | `?` → 真实值（类型感知引号、LIKE 自动百分号） | 预览面板参数区 | 无 | 纯逻辑 | `sql/ParameterReplacer.kt:13`；`sql/ParamQuote.kt` |
| F77 | 命名参数 `:name` 回填 | 面板 | 无 | 纯逻辑 | `psource/NamedParamParser.kt` |
| F78 | 参数对照表 / 来源注释（`? /*deviceId*/`） | 面板 / 复制 | 无 | 纯逻辑 | `sql/ParameterReplacer.kt:22,45` |
| F79 | 参数预设（按声明方法隔离、永久绑定、可折叠） | 参数栏 | 项目身份 + 相对路径 + 方法签名键 | 需等价替代 | `ui/ParamHistoryCache.kt`；`psi/PreviewOriginScope.kt` |
| F80 | 参数历史 LRU 回填（表名 + SQL 指纹 + 声明作用域） | 参数栏 | 同上 | 需等价替代 | `ui/ParamHistoryCache.kt` |
| F81 | 参数导入（JSON / CSV / key=value，裸列名唯一定位） | 参数栏对话框 | 无 | 纯逻辑 | `ui/ParamImportParser.kt` |
| F82 | 逻辑删除注入/剥离（`@TableLogic` 值感知） | 设置页开关 + 自动 | 实体注解解析 | 需等价替代 | `config/PluginSettings.kt:194-200`；`reverse/JavaCodeGenerator.kt:38-44` |
| F83 | 乐观锁 `@Version`（UPDATE 追加 `version = version + 1`） | 自动 | 实体字段解析 | 需等价替代 | `config/PluginSettings.kt`（`PluginSettings.kt:34` State）；`generator/CrudSqlGenerator.kt:14-16` |
| F84 | 多租户列注入 | 设置页 + 自动 | 无 | 纯逻辑 | `sql/interceptor/TenantLineEnhancer.kt`；`config/PluginSettings.kt:208-214` |
| F85 | 数据权限占位 | 设置页 + 自动 | 无 | 纯逻辑 | `sql/interceptor/DataPermissionPlaceholder.kt` |
| F86 | 分页拦截器模拟（LIMIT 与 COUNT 分控、`IPage`） | 面板开关 | 无 | 纯逻辑 | `sql/interceptor/PaginationSimulator.kt` |
| F87 | FieldFill 安全模拟（只做显式字面量覆盖，不加载用户类） | 自动 | 无 | 纯逻辑 | `model/FieldFillSimulation.kt`；`01-项目架构总览.md:251` |
| F88 | 已带引号字面量不二次转义 | 自动 | 无 | 纯逻辑 | `sql/PreQuotedLiteral.kt` |
| F89 | 日期族 → ANSI 类型字面量（`DATE '…'` / `TIMESTAMP '…'`，六引擎实测） | 自动 | 无 | 纯逻辑 | `sql/SqlDateLiteral.kt`；`log/LogSqlRestorer.kt:10-22` |
| F90 | 动态表名模拟 | 设置页 + 自动 | 无 | 纯逻辑 | `sql/interceptor/DynamicTableNameProcessor.kt` |
| F91 | 运行时槽位（`:name` 与 `?` 混排求值） | 自动 | 无 | 纯逻辑 | `sql/RuntimeSlot.kt`；`sql/RuntimeSlotValues.kt` |

### A9 方言体系与格式化

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F92 | 15 方言渲染（分页形态 / 标识符引号 / 保留字集 / upsert / identity 包装） | 设置页下拉 + 自动 | 无 | 纯逻辑 | `sql/Dialect.kt:23,423-1225`；`DialectRegistry` `Dialect.kt:1259` |
| F93 | 方言自动推断（agent 连接 URL > 数据源 URL > 兜底 MySQL） | 默认开启 | 项目数据源模型 + agent 会话 | 纯逻辑（决策）+ 平台独占（输入采集） | `sql/DialectInference.kt:88`；`sql/DialectDecisionEngine.kt` |
| F94 | 方言能力矩阵 + 执行前自检（EXPLAIN / DELETE LIMIT / UPSERT / IDENTITY） | 预览 meta 悬停 / 执行确认弹窗 | 无 | 纯逻辑 | `sql/DialectCapabilities.kt:31` |
| F95 | 平台方言注册 5 个（DM / GaussDB / HighGo / Vastbase / GoldenDB）＋ ParserDefinition 配对 | 数据库控制台语言选择 | `com.intellij.database` 方言注册表 | 平台独占 | `database-optional.xml` |
| F96 | 内置 SQL 格式化引擎（按方言缩进/换行/大小写） | 预览面板 / 复制前 | `IElementType`（仅词法类型） | 需等价替代（类型壳） | `ui/highlight/SqlFormatter.kt:20`；`ui/highlight/SqlLexer.kt:17` |
| F97 | 方言引号不匹配静态告警 | 预览结果警告 | 无 | 纯逻辑 | `sql/perf/rules/PerfRulesBatch4.kt`（`dialect_quote_mismatch`） |
| F98 | 方言智能映射到 IDE SQL 语言（exec 文件按方言映射） | 自动 | DB 方言注册表 + 文件属性 | 平台独占 | `execute/ExecDialectRouting.kt`；`execute/DialectPreMapping.kt` |

### A10 运行期捕获 · 字节码 agent（默认通道）

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F99 | premain 启动即注入（运行配置准备期加 `-javaagent`） | 默认开启，随 Run | `runConfigurationExtension` EP | 平台独占（宿主编排）+ 纯逻辑（决策表） | `plugin.xml:156`；`log/agent/PremainRunConfigurationExtension.kt`；`log/agent/PremainInjection.kt` |
| F100 | attach 运行中应用（`VirtualMachine.attach` + `loadAgent`，惰性去抖） | 自动 | 进程 API + 项目生命周期 | 平台独占 | `log/agent/AgentManager.kt:63`；`02-核心流程详解.md:746` |
| F101 | 语句面 / prepare 面字节码织入（ASM Tree，入口+出口双插桩） | 自动 | 无（JDK Instrumentation） | 纯逻辑（可独立 JVM 运行） | `agent/StatementWeaver.java:22-50`；`agent/SqlHookTransformer.java:34` |
| F102 | 驱动白名单 78 类（MySQL/MariaDB/H2/PG/openGauss/GaussDB/KingBase/Vastbase/HighGo/PanWeiDB/Oracle/SQL Server/SQLite/GoldenDB） | 自动 | 无 | 纯逻辑 | `agent/WeaveSpecs.java:43-180`（脚本计数 78 个唯一类名） |
| F103 | 接口匹配兜底 CatchAllMatcher（attach 时反射判 `java.sql.Statement`） | 自动 | 无 | 纯逻辑 | `agent/CatchAllMatcher.java:17-50` |
| F104 | 事件传输（loopback TCP 长度前缀帧 + HEARTBEAT + CONFIG 下行 + 退避重连） | 自动 | 无（socket） | 纯逻辑 | `agent/transport/SenderLoop.java:14-48`；`log/agent/AgentProtocol.kt` |
| F105 | 调用栈源码定位（StackLocator，仅缓存有效位置） | 条目跳源码 | 无（栈帧）→ IDE 侧转文件/行 | 纯逻辑（agent 侧）+ 需等价替代（跳转） | `agent/util/StackLocator.java`；`log/agent/AgentEventAdapter.kt` |
| F106 | 请求标签（读 slf4j MDC `sqlvisRequestTag`） | 条目标签 | 无（反射） | 纯逻辑 | `agent/util/MdcAccessor.java`；`02-核心流程详解.md:764` |
| F107 | 脱敏（参数值脱敏开关下行） | 设置页 | 无 | 纯逻辑 | `agent/util/Desensitizer.java`；`agent/AgentState.java` |
| F108 | 嵌套门禁 NestGate + 深度泄漏根治（catch-all 出口） | 自动 | 无 | 纯逻辑 | `agent/hook/NestGate.java`；`02-核心流程详解.md:782` |
| F109 | 多层降级（attach 失败 / 通道失活 → 回落控制台链） | 自动 | 项目生命周期 | 平台独占（编排） | `02-核心流程详解.md:754` |
| F110 | 多模块 console 让位（按控制台行前缀 PID 归属决定是否让位） | 自动 | 无（行文本解析） | 纯逻辑 | `log/agent/AgentManager.kt:1344-1371`；`log/SqlLogConsoleBridge.kt:958` |
| F111 | 驱动内部语句过滤（`SELECT @@session…` 等不混入日志） | 自动 | 无 | 纯逻辑 | `log/agent/DriverInternalSql.kt` |
| F112 | 目标 JDK 过老跳过注入（AgentShim 降到 major 50，防宿主启动失败） | 自动 | 无 | 纯逻辑 | `agent/AgentShim.java`；`log/agent/TargetJvmCompat.kt`；`02-核心流程详解.md:768` |
| F113 | 捕获状态行（五态 / 门禁陈旧 / 四条丢弃路径读数） | 设置页 + 状态栏 | 状态栏 UI | 需等价替代（文案纯函数） | `log/CaptureStatusBar.kt`；`log/CaptureDiagnostics.kt` |

### A11 运行期捕获 · 控制台日志链与 SQL 日志面板

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F114 | 控制台行捕获（`consoleFilterProvider` 主通道 + 反射桥轮询兜底） | 自动 | 控制台 API + 运行配置 | 平台独占 | `plugin.xml:150`；`log/SqlConsoleBridgeFilterProvider.kt` |
| F115 | MyBatis 日志解析（Preparing/Parameters/Total，含 iBatis 2.x 三行组） | 自动 | 无 | 纯逻辑 | `log/MyBatisLogParser.kt:54` |
| F116 | Hibernate 日志解析 | 自动 | 无 | 纯逻辑 | `log/HibernateLogParser.kt:20` |
| F117 | JdbcTemplate 日志解析（`Setting SQL statement parameter value`） | 自动 | 无 | 纯逻辑 | `log/JdbcTemplateLogParser.kt:33` |
| F118 | ShardingSphere 日志解析（Logic SQL / Actual SQL 双段） | 自动 | 无 | 纯逻辑 | `log/ShardingSphereLogParser.kt:23` |
| F119 | 线程分桶 + 时间戳解析 + 时间关联（agent 接管期回填） | 自动 | 无 | 纯逻辑 | `log/LogThreadRouter.kt`；`log/LogTimestampParser.kt`；`log/LogTimeCorrelator.kt` |
| F120 | 去重（dedupe 窗口）/ 同模板计数 / 疑似 N+1 徽章 + 聚合视图 | 面板列表 | 无（规则在纯逻辑层） | 纯逻辑（判定）+ 需等价替代（徽章 UI） | `plugin.xml:562-571`；`log/NplusOneSuspect.kt`；`log/NplusOneAggregate.kt` |
| F121 | 慢查询标红 | 面板列表 | 无 | 纯逻辑 | `log/SlowQueryDetector.kt` |
| F122 | 保活刷屏屏蔽规则（右键屏蔽 / 规则管理 / 上限提示） | 右键 + 对话框 | 无（规则持久化 XML） | 纯逻辑（规则）+ 需等价替代（持久化） | `log/SqlSuppressRuleManager.kt:42`；`log/SqlSuppressManagerDialog.kt` |
| F123 | 历史自动落盘 / 归档读取 / 重放守卫 | 自动 | 文件系统 | 需等价替代 | `log/SqlLogAutoSaver.kt`；`log/SqlLogFileReader.kt`；`log/ConsoleReplayGuard.kt` |
| F124 | 条目→源码跳转（Java 行 / mapper XML / 三态定位） | 条目点击 | PSI + 自定义索引 | 平台独占 | `log/EntryJumpResolver.kt`；`log/MapperXmlLocator.kt`；`plugin.xml:243` |
| F125 | 方法 / 请求聚焦页签 | 面板页签 | Swing + 调用栈模型 | 平台独占（UI）+ 纯逻辑（范围计算） | `log/FocusTab.kt`；`log/CallerMethodRangeResolver.kt` |
| F126 | SQL 执行报错同列（识别控制台异常行） | 面板列表 | 无 | 纯逻辑 | `log/SqlErrorLineRecognizer.kt` |
| F127 | 列表显示模式三档（单行 / 关键字换行 / 格式化）与头部行渲染 | 面板 | Swing 渲染器 | 需等价替代 | `log/SqlListFragmentRenderer.kt`；`log/SqlEntryPresentation.kt` |
| F128 | 排序方式 / 尾部跟随 / 暂停锚定 / 单击轻量预览 | 面板工具栏 | Swing | 平台独占 | `log/SqlLogPanel.kt`；`log/TailFollowState.kt`；`log/SqlClickPreview.kt` |
| F129 | 导出（CSV / TSV / 纯文本 / 可执行 SQL / 重放脚本） | 面板工具栏 | 文件系统 + 剪贴板 | 需等价替代 | `log/SqlExportService.kt`；`log/ReplayScriptExport.kt`；`log/PortableSqlExport.kt` |
| F130 | 参数随条目持久化 + 免占位符字面量恢复 | 自动 | 无 | 纯逻辑 | `log/SqlLogParamCodec.kt`；`log/LiteralParamRecovery.kt` |
| F131 | 源码拦截器模板生成与自动注入（无 agent 时的备选通道） | 对话框 | PSI 写 + VFS | 平台独占 | `log/SqlSourceInterceptorAutoInjector.kt`；`log/SourceInterceptorTemplates.kt` |
| F132 | 空态诊断与首次捕获引导 | 面板空态 | 无 | 需等价替代 | `log/CaptureDiagnostics.kt`；`log/FirstCaptureGuide.kt` |

### A12 执行与安全护栏

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F133 | 内嵌原生 Database 控制台执行 SQL（Ultimate；Community 降级） | 预览面板「执行 SQL」 | `com.intellij.database`（全反射桥）+ PSI + EDT | 平台独占 | `execute/SqlExecutor.kt:10`；`execute/DataSourceSelectorConsole.kt`（单文件 23 处 `Class.forName("com.intellij…")`） |
| F134 | 数据源选择三层记忆（活控制台 > 模块级 > 项目级）+ 连接事实指纹 | 工具栏数据源下拉 | 数据源模型 + PSI 模块 | 平台独占 | `execute/DataSourceSelector.kt`；`execute/DataSourceFingerprint.kt`；`execute/DataSourceSelectorResults.kt` |
| F135 | 执行前护栏（`${}` 注入确认 / DML 二次确认 / 无界写七规则 / 只读豁免二级证明） | 确认弹窗 | 无（判定）+ Dialog | 纯逻辑（判定）+ 需等价替代（弹窗） | `execute/UnboundedWriteDetector.kt:45`；`execute/SqlTypeClassifier.kt:13`；`execute/ExecuteConfirmMessages.kt` |
| F136 | 超时护栏（多语句预算线性封顶） | 自动 | 无 | 纯逻辑 | `execute/ExecutionTimeoutGuard.kt` |
| F137 | Schema 校验（预览 SQL 的「表中无此列」波浪 + 悬停建议） | SQL 编辑器波浪线 | `externalAnnotator` + 数据源元数据缓存 | 平台独占（annotator）+ 纯逻辑（比对） | `plugin.xml:354-355`；`sql/schema/SchemaValidator.kt:38`；`ui/annotate/PreviewUnknownColumnPlanner.kt` |
| F138 | 执行错误定位与富化（驱动位置提取、`[hint=]` 成因传递） | 结果区 / 跳转 | PSI 文件定位 + 控制台 | 平台独占 | `execute/ExecutionErrorInfo.kt`；`execute/ExecPsiFailureHint.kt` |
| F139 | 一键 EXPLAIN（按方言模板提交，15 登记 / 14 接入） | 预览面板「执行计划」 | DB console + 反射 | 平台独占 | `sql/ExplainSupport.kt:45,130-186`；`sql/ExplainGate.kt:21` |
| F140 | EXPLAIN 结构化渲染 / 计划 Diff / 计划历史 | 非模态对话框 + 历史面板 | Swing + 历史存储 | 需等价替代 | `ui/ExplainPlanResultView.kt`；`ui/ExplainPlanDiff.kt`；`history/ExplainPlanSnapshot.kt` |
| F141 | JOIN 关系图（原生 Diagrams 优先，自绘降级；PNG/SVG 导出） | 预览面板 JOIN tab | `com.intellij.diagram`（可选）+ DB 图 | 平台独占（原生）/ 纯逻辑（图结构生成） | `plugin.xml:117-120`；`sql/JoinDiagramGenerator.kt:25`；`ui/diagram/DiagramRenderer.kt` |
| F142 | JOIN 虚拟外键注入（原生 Database 图上的 ON 等值边） | 数据库图 | `modelRelationProvider` EP | 平台独占 | `database-optional.xml`；`ui/diagram/nativefw/db/JoinVirtualFkProvider.kt` |
| F143 | 元数据失效（DDL / 事务提交后清 schema 缓存） | 自动 | 数据源缓存 + 执行回调 | 平台独占 | `execute/MetadataInvalidator.kt` |
| F144 | 方言闸门（渲染方言 vs 目标源方言，八态 verdict，只提示不阻断的放行态） | 执行确认 / 结果区 | 数据源方言探测 | 纯逻辑（决策）+ 平台独占（探测） | `execute/ExecutionDialectGate.kt`；`execute/DialectMismatchHint.kt` |
| F145 | 数据源下拉接管（只换源不执行，防绕过执行闸门） | 控制台工具栏 | `ActionManager.replaceAction` | 平台独占 | `execute/ChooseDataSourceTakeover.kt:398` |
| F146 | 只读旁路查询（影响行数试算 COUNT / 回滚快照查询，不覆盖预览编辑器） | 面板按钮 | DB 只读执行链 | 平台独占 | `execute/DmlImpactEstimator.kt:37`；`ui/PreviewSideQuery.kt` |
| F147 | 执行文档写入与自动落盘（EDT 写锁纪律） | 自动 | EDT / 写意图锁 / VFS | 平台独占 | `execute/DocWriteContext.kt`；`execute/ExecDocAutosaveGate.kt` |
| F148 | 断连后 DDL「失败不等于未生效」探测 | 结果区 | DB 连接探测 | 平台独占 | `execute/ConnectionErrorClassifier.kt` |
| F149 | 建表 / 方言引号建议（CRUD 生成侧） | 生成结果 | 无 | 纯逻辑 | `generator/DbTypeQuoteHint.kt` |

### A13 生成与转换

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F150 | CRUD SQL 生成（SELECT 全表/主键、INSERT、UPDATE、DELETE、逻辑删变体） | 右键两个入口 | 实体 PSI 或 DbTable 元数据 | 纯逻辑（生成）+ 平台独占（实体自省） | `generator/CrudSqlGenerator.kt:27`；`generator/CrudTableSpec.kt` |
| F151 | SQL→Java 反向（QueryWrapper / LambdaQueryWrapper / Wrappers / MPJ；可选 maps / mapperVariable） | 右键 + 面板 + MCP | 实体列→字段映射需 PSI | 纯逻辑（解析与生成）+ 需等价替代（实体映射） | `reverse/SqlReverseParser.kt:31`；`reverse/JavaCodeGenerator.kt:27`；`reverse/SqlToJavaConverter.kt:27` |
| F152 | 回滚 SQL（INSERT→DELETE 全列等值；UPDATE/DELETE 无 before-image 诚实拒绝；快照回填） | 预览面板「回滚」 | 项目级快照服务 | 纯逻辑（生成）+ 需等价替代（快照存储） | `sql/rollback/RollbackSqlGenerator.kt:52`；`sql/rollback/RollbackSnapshotStore.kt:28` |
| F153 | SQL 分享（Markdown / HTML 报告 / 可执行 SQL） | 预览面板「分享」 | 文件/剪贴板 | 纯逻辑（格式化） | `share/format/MarkdownFormatter.kt:18`；`share/format/HtmlReportFormatter.kt:20`；`share/format/ExecutableSqlFormatter.kt` |
| F154 | 两条 SQL 对比（历史 / 预览） | 面板 Diff | Swing | 纯逻辑（差异算法） | `ui/diff/SqlDiffer.kt` |
| F155 | 复杂度评分 | 预览结果 | 无 | 纯逻辑 | `sql/ComplexityScorer.kt:25` |
| F156 | 静态性能建议（21 规则：缺 WHERE / 前导通配 / SELECT * / 关联子查询…） | 预览结果 + 工作台 + MCP | 无（JSqlParser 可选，取不到降级正则） | 纯逻辑 | `sql/SqlPerfAnalyzer.kt:33`；`sql/perf/PerfRuleEngine.kt`；`sql/perf/rules/PerfRulesBatch1..4.kt` |
| F157 | 索引缺失建议（EXPLAIN 结果后处理） | EXPLAIN 结果 | 无 | 纯逻辑 | `util/ExplainIndexAdvisor.kt` |
| F158 | 复制可执行 SQL（去占位符，脚本级） | 工具栏 | 剪贴板 | 纯逻辑 | `ui/components/CopyExecutableSqlAction.kt`；`ui/CopyFeedback.kt` |
| F159 | 语句切分 / 语句命名（`-- Name:` 注释） | 自动（导出/执行） | 无 | 纯逻辑 | `util/SqlStatementSplitter.kt:52`；`util/SqlStatementNamer.kt` |
| F160 | 批量等值谓词 IN 合并预览 | 面板 | 无 | 纯逻辑 | `util/BatchPredicateMerge.kt` |
| F161 | 复制为 AI 上下文（脱敏/结构词白名单两档） | 预览面板 | 剪贴板 | 纯逻辑 | `util/AiContextExport.kt`；`util/AiContextPlanSummary.kt` |
| F162 | 诊断报告导出（含方言能力段、五段式错误信息） | 预览面板 | 文件 | 纯逻辑 | `share/format/DiagnosticReportFormatter.kt`；`share/DiagnosticShareDraft.kt` |

### A14 MCP 工具（11 个只读，`sqlv_` 前缀）

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F163 | `sqlv_render_sql`：源码位置（filePath+line）或 xmlSnippet → 可执行 SQL | MCP 客户端 | PSI + 项目模型 + 自定义索引 | 平台独占 | `mcp/SqlVisualizerRenderSqlTool.kt:12-38`；`mcp/McpSourceLocator.kt` |
| F164 | `sqlv_schema_context`：表/列 schema 紧凑上下文 | MCP | 数据源元数据 | 平台独占 | `mcp/SqlVisualizerSchemaContextTool.kt:33`；`mcp/McpSchemaContextService.kt` |
| F165 | `sqlv_explain`：对配置数据源跑只读 EXPLAIN | MCP | DB console（反射） | 平台独占 | `mcp/SqlVisualizerExplainTool.kt:16`；`mcp/McpDbExecutor.kt` |
| F166 | `sqlv_render_logs`：MyBatis/Hibernate/JdbcTemplate/ShardingSphere 日志还原（detail 分档 + 脱敏） | MCP | 无（纯逻辑） | 纯逻辑 | `mcp/SqlVisualizerRenderLogsTool.kt:24,62` |
| F167 | `sqlv_check_sql`：写风险 + 语句类型静态检查 | MCP | 无 | 纯逻辑 | `mcp/SqlVisualizerCheckSqlTool.kt:22`；`execute/UnboundedWriteDetector.kt:45` |
| F168 | `sqlv_rollback_plan`：INSERT/UPDATE/DELETE 回滚预案 | MCP | 无 | 纯逻辑 | `mcp/SqlVisualizerRollbackPlanTool.kt:20`；`sql/rollback/RollbackSqlGenerator.kt:52` |
| F169 | `sqlv_perf_advice`：静态性能建议 | MCP | 无 | 纯逻辑 | `mcp/SqlVisualizerPerfAdviceTool.kt:23`；`sql/SqlPerfAnalyzer.kt:33` |
| F170 | `sqlv_crud_sql`：实体 → CRUD 语句 | MCP | PSI 实体自省 | 平台独占（自省）+ 纯逻辑（生成） | `mcp/SqlVisualizerCrudSqlTool.kt:29` |
| F171 | `sqlv_reverse_sql`：SQL → MP Java 代码 | MCP | 无（可选实体映射） | 纯逻辑 | `mcp/SqlVisualizerReverseSqlTool.kt:25` |
| F172 | `sqlv_mapper_check`：mapper XML 静态契约检查（可选 simulate 渲染 + 风险/性能） | MCP | XML 文本（snippet 可脱离 PSI） | 纯逻辑 | `mcp/SqlVisualizerMapperCheckTool.kt:28` |
| F173 | `sqlv_history_search`：持久化 SQL 历史检索（keyword/regex/时间/来源，detail 分档） | MCP | 文件系统 | 纯逻辑（检索）+ 需等价替代（目录定位） | `mcp/SqlVisualizerHistorySearchTool.kt:30`；`mcp/HistorySearchService.kt` |
| F174 | MCP 工具门控（总开关 + 逐工具开关 + 五态状态行 + 外部会话过滤 EP + 逃生 Registry） | 设置页 MCP 子页 | `com.intellij.mcpServer`（可选）+ 动态 EP 注册 | 平台独占 | `plugin.xml:224-229,577-582`；`mcp/McpToolGate.kt:31-42`；`mcp/McpToolsetDynamicRegistrar.kt:19` |
| F175 | ACP 会话 MCP 传输修复（SSE 下发，绕上游 stdio 丢弃） | AI Assistant 会话（自动） | `com.intellij.ml.llm`（可选）+ 动态代理 | 平台独占 | `plugin.xml:129-137`；`acp-transport-optional.xml`；`acp/AcpTransportRegistrar.kt` |

### A15 平台能力接入（设置 / 通知 / 补全 / 高亮 / 导航 / 更新 / 引导）

| 编号 | 功能条目 | 用户触发面 | 依赖的 IntelliJ 平台能力 | 耦合度 | 证据 |
|---|---|---|---|---|---|
| F176 | 设置页树：8 应用页（文件夹 + 界面与操作 / 快捷键 / SQL 预览 / 表名映射 / SQL 日志 / MCP / 关于） | Settings → Tools | `applicationConfigurable` + 持久化 | 平台独占 | `plugin.xml:165-237` |
| F177 | 项目级设置页（`.idea/mp-sql-visualizer-project.xml`，字段可 null=跟随全局） | Settings → Tools | `projectConfigurable` | 平台独占 | `plugin.xml:173-177`；`config/ProjectPluginSettings.kt:25` |
| F178 | 设置项进入 IDE 设置搜索索引 | Settings 搜索框 | `search.optionContributor` | 平台独占 | `plugin.xml:148`；`config/PluginSettingsSearchableOptionContributor.kt` |
| F179 | 设置导入/导出（版本闸 + 病态值收敛） | 关于页 | 文件 | 纯逻辑 | `config/SettingsIO.kt` |
| F180 | 快捷键统一管理（5 动作内嵌录制编辑 + 冲突检测） | 快捷键子页 | Keymap API | 平台独占 | `plugin.xml:192-197`；`config/SectionConfigurable.kt:154` |
| F181 | 通知组 6 个（更新 / 预览 / 反馈 / 日志屏蔽 / agent / 自愈） | 气泡通知 | `notificationGroup` | 平台独占 | `plugin.xml:388-425` |
| F182 | 检查更新三通道（Gitee 抢先体验 / Marketplace / 本地安装包目录 + 热替换） | 启动 + 设置页 | 动态插件 API + 文件系统 | 平台独占 | `update/StartupUpdateCheck.kt`；`update/PluginUpdateRunner.kt`；`update/LocalPackageScanner.kt` |
| F183 | 更新包安全守卫（解压有界 + 归属三闸门） | 自动 | 文件系统 | 纯逻辑 | `update/UpdateZipGuard.kt`；`update/PluginPackageOwnership.kt` |
| F184 | 错误一键上报（指纹去重 / 批量合并 / 错峰补发 / 对话框） | Help 菜单 + IDE 错误弹窗 | `errorHandler` EP + HTTP | 平台独占（EP）+ 纯逻辑（编排） | `plugin.xml:401`；`feedback/ErrorFingerprint.kt`；`feedback/BatchSubmitter.kt` |
| F185 | 首用引导三件套（首锚点指路 / 空态引导 / 首次捕获指路） | 打开文件时一次性通知 | `postStartupActivity` + 编辑器事件 | 平台独占 | `plugin.xml:434`；`onboarding/OnboardingAnchorTip.kt`；`log/FirstCaptureGuide.kt` |
| F186 | 方言死路主动提示（推断不出方言时一次性提示） | 打开 SQL 承载文件 | `postStartupActivity` | 平台独占 | `plugin.xml:446`；`onboarding/DialectGapTip.kt` |
| F187 | `workspace.xml` 自愈（补丢引号 + 预热 PropertiesComponent） | 项目打开（自动） | VFS + PropertiesComponent | 平台独占 | `plugin.xml:441`；`execute/WorkspacePropertiesRepairActivity.kt` |
| F188 | 自定义索引 3 个（Mapper namespace / sqlMap namespace / sqlMapConfig 别名） | 自动（索引期摊平） | `fileBasedIndex` | 平台独占 | `plugin.xml:243-257`；`log/MapperNamespaceIndex.kt`；`xml/ibatis/SqlMapNamespaceIndex.kt`；`xml/ibatis/SqlMapConfigAliasIndex.kt` |
| F189 | 补全 7 注册（SQL 表/列、XML 表/列/`#{}`/OGNL、`@Select` 注解内、Wrapper 链方法/字段、mybatis-config、resultMap column） | 编辑器自动弹出 | `completion.contributor` + 数据源 schema | 平台独占 | `plugin.xml:361-383,466-467`；`ui/highlight/DatabaseSchemaResolver.kt:30`；`ui/completion/WrapperCompletionContributor.kt` |
| F190 | mapper XML 语句注入语言 `MpSqlMapper`（高亮 / 折叠 / 颜色页 / 拼写豁免 / `#{}` 自动闭合 / 成对删除 / 局部重算） | 编辑器自动 | `multiHostInjector` + lexer + typedHandler | 平台独占 | `plugin.xml:463-511`；`ui/inject/SqlMapperInjector.kt`；`ui/inject/SqlMapperLanguage.kt` |
| F191 | Mapper 双向导航（gutter 图标 + Ctrl+B / Ctrl+Click / Ctrl+Alt+Home 平台键位接管） | 编辑器导航 | `gotoDeclarationHandler` / `gotoRelatedProvider` / `PsiReference` | 平台独占 | `plugin.xml:281-284`；`ui/nav/MapperNavigationGotoDeclarationHandler.kt` |
| F192 | 预览编辑器 INSERT 目标列 inlay 提示 | 预览 SQL 视图 | `codeInsight.inlayProvider`（language=SQL） | 平台独占 | `plugin.xml:342-343`；`ui/inlay/PreviewInsertColumnInlayProvider.kt` |
| F193 | 未知列红色波浪 + 悬停建议（见 F137） | 预览 SQL 视图 | `externalAnnotator` | 平台独占 | `plugin.xml:354-355` |
| F194 | Live template（cd / co 骨架 + 自定义上下文） | XML 编辑器 | `defaultLiveTemplates` + `liveTemplateContext` | 平台独占 | `plugin.xml:493-494`；`src/main/resources/templates/mybatis-sql.xml` |
| F195 | SQL 编辑器自动折叠（字段列表超阈值折叠） | SQL 编辑器 | 编辑器折叠模型 | 平台独占 | `ui/highlight/SqlAutoFoldPlanner.kt`；`ui/highlight/SqlAutoFolding.kt` |
| F196 | iBatis DTD 离线映射（8 个 systemId/publicId） | XML 编辑（无网络） | `standardResource` EP | 平台独占 | `plugin.xml:721-736` |
| F197 | 图标体系（27 入口 / 24 工具栏 SVG，亮暗双份） | 全局面 | 平台图标约定 | 平台独占 | `ui/icons/MpvsIcons.kt`；`src/main/resources/icons/toolbar/` |
| F198 | i18n 双语（1,557 键 × 2 + 语言写穿 + 设置页子项 groupWeight 定序） | 全局面 | `resource-bundle` | 需等价替代 | `plugin.xml:15,179-182`；`util/MpSqlVisualizerBundle.kt` |
| F199 | 缓存管理（清预览历史 / 清参数历史 / 缓存信息 / 构建号） | 关于页 | 项目服务 + 文件 | 需等价替代 | `util/PluginCacheManager.kt`；`util/PluginBuildInfo.kt`；`config/PluginSettingsPanel.kt:1123-1159` |
| F200 | 缩进/空白字符偏好与编辑器风格对齐 | 关于页 | 编辑器设置 | 平台独占 | `ui/EditorWhitespaceSettings.kt` |
| F201 | 竞品共存让位（MyBatisCodeHelperPro 在位时 mapper 导航/注入/图标让位） | 自动 | 已装插件列表 | 平台独占 | `ui/gutter/MyBatisCompetitorGate.kt`；`ui/inject/XmlSynHiGate.kt`；`config/PluginSettings.kt:160` |

---

## B. 核心引擎清单

| 编号 | 引擎 | 实现类（路径:行） | 对外输入 → 输出 | JSqlParser | 依赖 PSI |
|---|---|---|---|---|---|
| B01 | Wrapper→语句模型 SPI | `converter/WrapperConverter.kt:23`；`ConverterRegistry` 11 个转换器 `converter/WrapperConverter.kt:47-63` | `WrapperChain` + `TableInfo` + `Project?` → `SqlStatement` | 否 | 是（`Project` 形参；链本身由 PSI 层产出） |
| B02 | 领域模型 | `model/`：`WrapperChain` `Condition` `SelectStatement` `UpdateDeleteStatement` `TableInfo` `PreviewResult` `BatchModel` `SqlValue` `ColumnRef` 等 17 个纯数据类 | 纯数据承载 | 否 | 否 |
| B03 | 语句模型→SQL 文本 | `sql/SqlGenerator.kt:43`（`GeneratedSql` 于 `:24`） | `SqlStatement` + 方言 + 参数求值结果 → SQL + 未解析参数 + 回填提示 | 否 | 否 |
| B04 | 方言体系 | `sql/Dialect.kt:23`（接口）、`:423-1225`（15 实现）、`DialectRegistry` `:1259` | 分页/引号/保留字/upsert/identity 五差异点 | 否 | 否 |
| B05 | 参数替换与占位符 | `sql/ParameterReplacer.kt:13`；`sql/PlaceholderPositions.kt`；`sql/ParamQuote.kt`；`sql/ParamHint.kt`；`sql/SqlDateLiteral.kt`；`sql/PreQuotedLiteral.kt` | SQL 文本 + 参数来源/值 → 注释态 / 对照表 / 可执行 SQL | 否 | 否 |
| B06 | 运行时槽位与参数求值 | `sql/RuntimeSlot.kt`；`sql/RuntimeSlotValues.kt` | 槽位表 + 值表 → 参数序列 | 否 | 否 |
| B07 | 复合 SQL 文本分析 | `sql/SqlTextAnalysis.kt`；`sql/SqlCommentStripper.kt`；`sql/SqlKeywords.kt`；`sql/ComplexityScorer.kt:25` | SQL 文本 → 结构特征/复杂度评分 | 否（自研扫描） | 否 |
| B08 | 表 / JOIN / 子查询 / SELECT* 提取 | `sql/extract/TableExtractor.kt` `JoinExtractor.kt` `SubqueryExtractor.kt` `SelectStarDetector.kt` | SQL 文本 → 表集/边集/子查询/星号标志 | 否（正则/扫描） | 否 |
| B09 | MyBatis 动态 SQL 复刻 | `xml/XmlScriptBuilder.kt:19`；`xml/SqlNode.kt` + `IfSqlNode` / `ForeachSqlNode` / `TrimSqlNode` / `TextSqlNode`；`xml/DynamicContext.kt`；`xml/TokenParser.kt`；`xml/XmlSqlRenderer.kt:38` | XML 节点模型 + 参数上下文 → SQL + `ParameterMapping` + 错误 | 否 | 否（节点模型由 XML 解析层喂入） |
| B10 | OGNL 受限子集求值器 | `xml/Ognl.kt:23,38`（1001 行，自研 lexer/parser/evaluator） | 表达式文本 + bindings → 值 / 解析错误 | 否 | 否 |
| B11 | XML 片段展开 | `xml/XmlEwExpander.kt`；`xml/XmlIncludeExpander.kt`；`xml/XmlInlineSqlDetector.kt`；`xml/CrossNamespaceFragments.kt` | 语句模型 → 展开后模型 | 否 | 否（跨命名空间候选文件由索引提供） |
| B12 | XML 安全解析 | `xml/SecureXml.kt`（552 行，唯一 DocumentBuilderFactory 工厂） | XML 字节 → DOM（XXE 关闭） | 否 | 否 |
| B13 | iBatis 2.x 解析与渲染 | `xml/ibatis/SqlMapParser.kt:22`；`SqlMapScriptBuilder.kt`；`SqlMapSqlRenderer.kt:107`；`SqlMapConditionEvaluator.kt`；`SqlMapTokenParser.kt`；`SqlMapPrependMarker.kt` | sqlMap XML → 语句模型 → SQL（iterate/selectKey/parameterMap） | 否 | 否 |
| B14 | 注解 SQL 解析 | `xml/AnnotationSqlParser.kt` | 注解字符串 → 语句模型 | 否 | 否 |
| B15 | JPA 派生查询（PartTree） | `converter/jpa/PartTreeParser.kt`；`DerivedQueryConverter.kt`；`SelectClauseExpander.kt` | 方法名 + 实体元数据 → 语句模型 | 否 | 否（元数据由 PSI 层喂入） |
| B16 | JPQL / HQL 转换 | `converter/jpa/JpqlConverter.kt`；`HqlConverter.kt`；`JpaQueryEnhancer.kt`；`JpaAliasTableResolver.kt`；`JpaJoinRewriter.kt`；`JpaJoinOnResolver.kt` | 查询文本 + 实体别名表 → 语句模型 | 否（自研 HQL 解析） | 否 |
| B17 | CriteriaQuery / Specification / QBE 转换 | `converter/jpa/CriteriaQueryConverter.kt`（820 行）；`SpecificationQueryConverter.kt`；`QueryByExampleConverter.kt`；`JpaFromEntityMapper.kt` | 调用链中间模型（由 PSI locator 产出）→ 语句模型 | 否 | 否（中间模型解耦） |
| B18 | JPA CRUD / UPDATE 解析 | `converter/jpa/JpaCrudOperationConverter.kt`；`JpaUpdateParser.kt`；`NewExpressionStripper.kt`；`JpaSqlLiteralMasking.kt` | CRUD 调用 → 语句模型 | 否 | 否 |
| B19 | 日志还原（四族） | `log/MyBatisLogParser.kt:54`（含 iBatis）；`log/HibernateLogParser.kt:20`；`log/JdbcTemplateLogParser.kt:33`；`log/ShardingSphereLogParser.kt:23`；`log/LogSqlRestorer.kt:24`；`log/LogSqlModels.kt`（708 行） | 日志文本 + 前缀配置 → `LogSqlEntry`（SQL + 参数 + 耗时 + 来源行） | 否 | 否 |
| B20 | 日志侧参数恢复与展示 | `log/SqlLogParamCodec.kt`；`LiteralParamRecovery.kt`；`ListParamRenderer.kt`；`LogEntryPreviewRecognizer.kt`；`log/ExecutionTimeParser.kt`；`LogDurationParser.kt` | 参数串 → 类型化参数 / 持久化编码 | 否 | 否 |
| B21 | 日志缓冲与线程分桶 | `log/SqlLogEntryBuffer.kt:24`（1281 行）；`log/LogThreadRouter.kt`；`log/LogTimeCorrelator.kt`；`log/SlowQueryDetector.kt` | 条目流 → 去重/分桶/排序后条目 | 否 | 否 |
| B22 | 静态性能规则引擎 | `sql/perf/PerfRuleEngine.kt`；`PerfRule.kt`；`PerfWarning.kt`；`sql/perf/rules/PerfRulesBatch1..4.kt`（21 条规则）；`sql/SqlPerfAnalyzer.kt:33`（LRU 200 + 最多 10 条警告） | SQL 文本 + 方言 → `List<PerfWarning>` | 是（`Statement`/`Expression`/`Select`/`Update`/`Delete`/`Column`/`Table` 等，失败降级正则） | 否 |
| B23 | 写风险检测 | `execute/UnboundedWriteDetector.kt:45`（七规则：缺 WHERE / 恒真 / OR 恒真 / `%` 通配 / 无列引用 / 子查询 / 解析失败） | SQL 文本 → `FindingKind` 列表 | 是 | 否 |
| B24 | 语句类型分类与只读豁免 | `execute/SqlTypeClassifier.kt:13` | SQL → QUERY/DML/DDL + 可写判定（fail-closed） | 否（词法） | 否 |
| B25 | Schema 校验 | `sql/schema/SchemaValidator.kt:38` | SQL + `SchemaSnapshot` → 未识别列 + 近似名建议 | 是 | 否 |
| B26 | DML 影响行数估算 | `execute/DmlImpactEstimator.kt:37` | UPDATE/DELETE 文本 → 等价 COUNT SQL 或如实拒绝 | 是 | 否 |
| B27 | EXPLAIN 请求构造 | `sql/ExplainSupport.kt:45`（15 条模板 + postPlanQuery/channelNote）；`sql/ExplainGate.kt:21` | 方言 + SQL → EXPLAIN 语句 + 输出格式档 | 否 | 否 |
| B28 | EXPLAIN 结果渲染 / 索引建议 | `mcp/McpExplainFormatter.kt`；`ui/ExplainJsonPlanTree.kt`；`ui/ExplainPlanDiff.kt`；`util/ExplainIndexAdvisor.kt` | 计划原文（TABLE/TEXT/JSON/XML）→ 结构化视图/建议 | 否 | 否 |
| B29 | CRUD 生成 | `generator/CrudSqlGenerator.kt:27`；`generator/CrudTableSpec.kt`；`DbTypeQuoteHint.kt` | `CrudTableSpec` → 6 类候选语句 | 否 | 否 |
| B30 | SQL→Java 反向 | `reverse/SqlTokenizer.kt`；`SqlReverseParser.kt:31`（1436 行）；`JavaCodeGenerator.kt:27`（1373 行）；`SqlToJavaConverter.kt:27`；`WrapperFormat.kt` | SQL 文本 → `SqlReverseResult` → Wrapper 链代码 | 否（自研 tokenizer/parser） | 否（`EntityTarget` 映射表外部注入） |
| B31 | 回滚计划 | `sql/rollback/RollbackSqlGenerator.kt:52`（636 行，三档评级 / 500 语句与 256KB 上限） | INSERT/UPDATE/DELETE 文本 → 回滚语句 + COUNT 自查模板 + 警示 | 是（`parseStatements` + AST 分发） | 否 |
| B32 | JOIN 图结构生成 | `sql/JoinDiagramGenerator.kt:25` | 语句模型 → 表节点 + JOIN 边（双引擎共用） | 否 | 否 |
| B33 | 分享/报告格式化 | `share/format/MarkdownFormatter.kt:18`；`HtmlReportFormatter.kt:20`；`ExecutableSqlFormatter.kt`；`DiagnosticReportFormatter.kt` | 预览结果 → Markdown / HTML / 可执行 SQL | 否 | 否 |
| B34 | SQL 格式化与词法 | `ui/highlight/SqlFormatter.kt:20`（1157 行）；`ui/highlight/SqlLexer.kt:17`（`LexerBase`） | SQL 文本 + 方言 → 格式化文本 / token 流 | 否 | `IElementType`/`TokenType` 两处类型（替换成本≈0） |
| B35 | 语句切分与命名 | `util/SqlStatementSplitter.kt:52`（744 行，T-SQL 批首 IF 不拆）；`util/SqlStatementNamer.kt` | 脚本文本 → 语句列表 / 命名 | 否 | 否 |
| B36 | 脱敏与展示掩码 | `util/DisplayMasker.kt:18`；`agent/util/Desensitizer.java` | 文本 + 开关 → 打码文本 | 否 | 否 |
| B37 | agent 织入引擎 | `agent/StatementWeaver.java`（609 行 ASM Tree）；`SqlHookTransformer.java:34`；`ConnectWeaver.java`；`ZeroLoadClassWriter.java` | 类字节 + 织入形态 → 增强后字节 | 否 | 否（纯 JDK Instrumentation） |
| B38 | agent 事件与传输 | `agent/model/SqlEvent.java`；`transport/EventBus.java`；`transport/SenderLoop.java:50`；`util/Jsons.java`；`hook/StackDigest.java`；`util/StackLocator.java` | JDBC 执行 → 事件帧（JSON over TCP） | 否 | 否 |
| B39 | MCP 协议适配 | `mcp/McpToolsetSupport.kt:67`；`mcp/McpToolsetDynamicRegistrar.kt:19`；`mcp/TinyClassFileWriter.kt`（466 行手写 class 文件）；11 个 `SqlVisualizer*Tool.kt` | 工具调用参数 → 文本结果 | 否 | 是（`Project` / `DumbService`） |

JSqlParser 使用面（14 个文件）：`execute/DmlImpactEstimator.kt`、`execute/SqlAstParser.kt`、`execute/UnboundedWriteDetector.kt`、`mcp/McpSelectGuard.kt`、`sql/SqlPerfAnalyzer.kt`、`sql/perf/PerfRule.kt`、`sql/perf/rules/PerfRulesBatch1..4.kt`、`sql/rollback/RollbackSqlGenerator.kt`、`sql/schema/SchemaValidator.kt`、`ui/annotate/PreviewUnknownColumnPlanner.kt`、`util/BatchPredicateMerge.kt`。用到的 AST 类型集中在 `Expression` / `Select` / `PlainSelect` / `Update` / `Delete` / `Insert` / `Column` / `Table` / `ExpressionList` 与三个 VisitorAdapter（`TablesNamesFinder` 亦被使用）。

---

## C. 平台独占清单（为什么离不开 IntelliJ）

| 编号 | 功能 | 独占原因（机制级） |
|---|---|---|
| C01 | Wrapper 链定位与判型（F01/F39-F45） | 需要「标识符→`PsiMethod`/`PsiClass` 解析 + 接收者类型 + 泛型实参」三层推断。`psi/WrapperLocator.kt`（1777 行）四种定位策略（内联 new / 分步变量 / 工厂 / 服务链）全部建立在 PSI resolve 之上；负例边界（import、注释、字符串内、泛型实参位、非 Wrappers 限定符）也靠语法树结构判定（`docs/架构设计/预览触发面清单-说明文档.md:54-63`）。纯文本匹配无法区分 `list.remove(null)` 与 `mapper.delete(null)`。 |
| C02 | 跨文件实体解析与表名反查（F51/F70） | `psi/EntityClassFinder.kt`（1013 行）依赖平台 `JavaAnnotationIndex` 注解索引 + 模块内容根遍历 + 库模块识别；继承链、`@TableName`/`@Table` 双 ORM 分源、JAR 内实体都需索引回答。 |
| C03 | Spring Data / JPA 六族定位（F11-F17/F61-F70） | 需要「接口是否 `extends *Repository`」的继承解析、`@Query` 注解解析、Criteria 调用链的变量追踪与 lambda 体分析、hbm.xml 的 XML PSI。`psi/jpa/` 16 个类共 6,755 行。 |
| C04 | mapper XML / iBatis / pages / hbm 文本与结构（F04-F10/F52-F60） | 需要 XML PSI（标签树 + 属性 + 文本范围）与 `standardResource` DTD 解析；`<include>` 跨命名空间需自定义索引出候选文件再 PSI 精确匹配（`psi/XmlCrossNsFragmentResolver.kt`）。 |
| C05 | MBG Example 按调用点还原（F59） | 依赖 `ReferencesSearch`（全项目引用检索）反推 mapper 方法的调用点，再解析同一方法体内 `example` 变量的 `andXxx` 常量；dumb mode 下必须跳过（`预览触发面清单-说明文档.md:32-34`）。 |
| C06 | 条目→源码跳转与反查（F124/F19/F20） | 需要虚拟文件 + 行号 + PSI 定位 + 自定义索引（`MapperNamespaceIndex`）三态收敛（Absent/Unknown/Found）。 |
| C07 | 控制台 SQL 行捕获（F114） | `consoleFilterProvider` 只能挂在平台运行控制台的输出流上；另有反射桥轮询兜底（T-M56-09）。这是纯平台面：没有控制台就没有这一通道。 |
| C08 | 执行 SQL / EXPLAIN / 数据源（F133-F149） | 全部经 `com.intellij.database` 原生控制台：`execute/DataSourceSelectorConsole.kt` 单文件 23 处 `Class.forName("com.intellij…")`（`execute/` 全包合计 35 处，覆盖 `JdbcConsoleUtil`/`SessionClientHolder`/`ScriptModel`/`SqlPsiFacade`/`EvaluationHelper` 等），并需 EDT 与写意图锁纪律（`execute/DocWriteContext.kt`）。 |
| C09 | JOIN 原生图（F141/F142） | `diagram.Provider` + `modelRelationProvider` EP 只对 Ultimate 的 Diagrams/Database 生效，且节点内容需真实 `DbTable`。 |
| C10 | EDT / 读锁 / dumb 纪律（横切） | 78 个文件 import `ReadAction`/`WriteIntentReadAction`，30 个文件用 `DumbService`；跨线程读 PSI 需两段式快照（`psi/PsiSnapshot.kt`、`docs/架构设计/PSI跨线程读取协议.md`）。这是「平台独占」而非「可实现等价物」的部分。 |
| C11 | 注入语言 `MpSqlMapper`（F190） | lexer/parser/highlighter/folding/typedHandler/spellchecker 全族都是平台语言框架的扩展点，且要靠 `multiHostInjector` 挂到 XML 宿主。 |
| C12 | 补全与 schema 列候选（F189） | `completion.contributor` 7 处注册 + `DatabaseSchemaResolver`（1132 行）反射读数据源 schema（DBN 的 ObjectKind 手写枚举须按具名静态字段取，`02-核心流程详解.md:825`）。 |
| C13 | 自定义索引 3 个（F188） | `fileBasedIndex` 是索引期摊平的唯一通道；替代方案是每次全项目 XML 扫（已实测 5s 级，`plugin.xml:245-248` 记录）。 |
| C14 | MCP 平台接入（F163-F175） | 工具发现依赖 `kotlin-reflect` 扫 `@McpTool` 注解 + 平台 `McpToolset` 接口；`McpToolsetDynamicRegistrar` 用 ASM 手写壳类（`TinyClassFileWriter`）绕 verifier。 |
| C15 | 设置页 / 通知 / 工具窗 / Action / 快捷键（F22-F38/F176-F181） | 全部是平台 UI 与 EP 面。 |

对照：**A10 整条 agent 链（F99-F112）与 A11 的解析/还原/规则（F115-F132）基本不依赖平台**——agent 是纯 Java + JDK Instrumentation，日志解析是纯文本。这两块是同构移植成本最低、价值最高的部分。

---

## D. 复用评估（Java sidecar / 外部进程形态）

### D1 可「几乎不改」复用的候选模块

| 优先级 | 模块 | 包路径 | 行数（纯） | 障碍 | 复用形态建议 |
|---|---|---|---:|---|---|
| P0 | SQL 渲染核心 | `sql/`（`SqlGenerator` `Dialect` `ParameterReplacer` `ExplainSupport` `DialectCapabilities` `sql/extract` `sql/interceptor` `sql/perf` `sql/rollback` `sql/schema`） | 9,370 / 11,365（含子包） | 无平台类型；JSqlParser 5.x 需随包 | 直接做成 jar，暴露 `render(model, dialect, params)` / `perf(sql, dialect)` 纯函数 |
| P0 | XML 动态 SQL 复刻 | `xml/` + `xml/ibatis/` | 6,235 / 7,546 | `SecureXml` 用 JDK DOM，无平台依赖 | 输入 XML 文本 + 参数上下文，输出 SQL + 参数映射 |
| P0 | 日志还原 | `log/` 中 39 个纯文件（4 parser + `LogSqlRestorer` + `LogSqlModels` + 编解码/分桶） | 8,566 / 25,806 | 少数 UI 相邻类（`SqlLogPanel` 等）在同包，需按类切分而非按包 | 独立 `log-parse` 模块；MCP 的 `sqlv_render_logs` 已是现成纯函数入口（`SqlVisualizerRenderLogsTool.renderLogs`） |
| P0 | 反向转换 | `reverse/` | 3,213 / 3,525 | 无 | `sqlToJava(sql, entityTarget?)` |
| P0 | CRUD 生成 | `generator/` | 352 / 352 | 无 | `generate(CrudTableSpec)` |
| P1 | 领域模型 | `model/` | 1,780 / 1,780 | 无 | 作为跨模块契约 DTO（JSON 化后即跨语言契约） |
| P1 | 性能/校验规则 | `sql/perf/**`、`execute/UnboundedWriteDetector.kt`、`execute/SqlTypeClassifier.kt`、`sql/schema/SchemaValidator.kt`、`execute/DmlImpactEstimator.kt` | 约 3,000 | JSqlParser 依赖最重的一簇 | 与 P0 同 jar；若 OpenIDEA 侧换 Rust SQL parser，可用「进程边界 + JSON」隔离 |
| P1 | 分享/报告 | `share/format/`、`share/ShareContentProjection.kt` | 552 / 705 | 无 | 纯格式化 |
| P1 | 切分/命名/脱敏/掩码 | `util/SqlStatementSplitter.kt` `SqlStatementNamer.kt` `BatchPredicateMerge.kt` `DisplayMasker.kt` `NamingStrategy.kt` `CaseFormatUtil.kt` | 约 1,000 | 无 | 纯工具函数 |
| P1 | SQL 格式化与词法 | `ui/highlight/SqlFormatter.kt` `SqlLexer.kt` | 1,429（非纯仅因 2 个类型 import） | `LexerBase` / `IElementType` 需替换为自定义 token 枚举（改动约 2 个文件、几十行） | 抽成 `sql-format` 模块 |
| P2 | agent（整条） | `sub-projects/sql-visualizer-agent` | 8,054（Java，零平台依赖） | 与 IDE 侧只有 socket 协议（`AgentProtocol`）耦合 | 原样保留为独立 agent jar，任何宿主（含 Rust 主程序）都能通过 TCP 消费 |
| P2 | MCP 纯逻辑工具体 | `mcp/` 中 10 个纯文件（`McpSelectGuard` `McpToolErrors` `McpRenderInput` `McpExplainFormatter` `McpSchemaFormatter` `McpDialectDetector` `HistorySearchService` `McpToolGate` `TinyClassFileWriter` `SqlvExpectedToolException`） | 1,635 / 6,203 | 外壳 `McpToolsetSupport` 依赖 `Project`/`DumbService` | 业务函数留用，协议适配层按 OpenIDEA 重写 |

### D2 「不能直接复用」的三类阻碍

| 阻碍 | 涉及范围 | 说明 |
|---|---|---|
| IntelliJ 类型深度渗透 | `psi/`（7,487 行）、`psi/jpa/`（6,755 行）、`ui/gutter/`（3,666 行）、`ui/inject/`（2,419 行）、`ui/nav/` `ui/ref/` `ui/completion/` | 这些类的**输入就是 PSI 元素**，不是文本。要复用必须重写成「源码 → 中间模型」的另一套前端（tree-sitter / rust-analyzer 风格），中间模型可继续沿用 `model/` 与 `converter/`。 |
| 项目/模块/数据源模型 | `context/` `execute/` `ui/highlight/DatabaseSchemaResolver` | 依赖 `Project`、`Module`、`VirtualFile`、数据源对象。sidecar 形态需要用「路径 + 数据源连接串」自建等价物。 |
| 平台 UI/EP 框架 | `actions/` `config/` `feedback/` `update/` `onboarding/` `ui/`（除纯文件） | Action / 通知 / 设置页 / 工具窗 / Keymap 全是平台面，只能按 OpenIDEA 的扩展模型重写；但其中的**判定逻辑与文案组装**多数已是纯函数（如 `CaptureStatusBar`、`PremainInjection`、`DialectDecisionEngine`、`SqlWriteRiskText`），可直接搬。 |

### D3 sidecar 形态的推荐切面

1. **进程内嵌（jar）**：把 P0/P1 打成无平台依赖的 `visualizer-core.jar`，OpenIDEA 侧用 JNI/子进程调用；输入输出全 JSON（`model/` 已是纯数据类，序列化成本低）。
2. **独立服务**：`core` + `agent` 各起一个进程，前者接受「SQL/XML/日志文本 + 方言 + 实体映射表」请求，后者照现有 TCP 协议向任何宿主推送执行事件。两者都不需要 IDE。
3. **必须重写的前端**：源码→`WrapperChain`/`QueryWrapper` 中间模型这一层（现由 `psi/WrapperLocator` + `converter/*` 承担）。建议在 OpenIDEA 侧用 Rust 实现「链式调用识别 + 实体注解扫描 + 表名列名推断」，产出与 `model/WrapperChain` 同构的 JSON，即可直接接现成 `converter` + `sql`。
4. **可放弃项（若首版求快）**：MCP 协议壳、设置页、通知、更新通道、错误上报、首用引导、i18n 框架——这些与「SQL 能力」正交。

---

## 清点盲区

1. **`docs/架构设计/` 无 `子系统-*.md`**。任务书假设的「11 篇子系统文档」实际是 `01-项目架构总览` `02-核心流程详解` `03-开发与测试指南` + 8 篇专题（`JPA-Hibernate官方API覆盖矩阵` `MP-MPJ官方API覆盖矩阵` `其他ORM官方API覆盖矩阵` `M37M38新增功能` `PSI跨线程读取协议` `预览触发面清单` `task-queue任务队列` `测试服务器备份与恢复体系`）。本报告因此改用 `01` 的六层分层 + 23 包作子系统骨架，可能与任务书预期的划分不完全对齐。
2. **文档漂移（已按代码为准）**：`01-项目架构总览.md:50-57` 仍写「插件名 SQL Visualizer / 922 个测试 / 19 处 lineMarkerProvider」；实测 `plugin.xml:22` 已是 `MyBatis SQL Visualizer`，测试 994 个 Kotlin 文件，主文件 lineMarkerProvider 19 处 + kotlin-optional 1 处 = 20 注册。同类漂移：`01` 提到 `sql/verify` `sql/spec` `xml/ibatis/fidelity` 包，`src/main` 下**不存在**（应在测试源集）。
3. **功能条目数的口径**：本表 201 条是「用户可感知能力 + 平台接入项」的合并计数。若只算「SQL 相关业务能力」（F01-F175）为 175 条；若刨掉纯平台接入项（F176-F201）则为 A1-A14 共 175 条。不同口径下「有多少功能」差异很大，移植范围需按 A1-A14 而非总数谈。
4. **未验证项（推断）**：
   - 各 `ui/gutter/*` provider 的**精确注册数量**以 plugin.xml 为准（20 注册），但「一个 provider 内含多个 marker」的情况（如 iBatis DAO 调用行同 provider 出两枚 marker）未逐类核对，可能低估触发面。
   - `execute/` 与 `com.intellij.database` 的反射面只统计了 `Class.forName("com.intellij.database…")` 的字面量出现次数，动态拼装的类名（若有）未覆盖。
   - `mcp/` 11 个工具的 parameters schema 由平台用 `kotlin-reflect` 从方法签名生成，本报告只记录了参数名与默认值，**未导出完整 JSON Schema**（平台产物需真机 `tools/list`）。
   - 「纯逻辑」判定基于 import 文本；同包内引用未 import 的平台类型（Kotlin 同包可见性）不会被发现，理论上会低估平台耦合。
5. **未清点**：`src/test` 的 994 个测试类未逐类盘点（对移植是「行为规范资产」，建议单独做一轮「测试即规格」清点）；`build.gradle.kts` 的构建/打包/verifier 门禁、CI 工作流、`dev-support/` 脚本未纳入；`docs/mcp/MCP-Tools-Agent-Guide.md` 未读（本报告 MCP 一节直接读代码）。
6. **排除项**：`sub-projects/` 下除 `sql-visualizer-agent` 外无其他子项目；`build/` `out/` `.gradle/` `.worktrees/` 未纳入统计。
