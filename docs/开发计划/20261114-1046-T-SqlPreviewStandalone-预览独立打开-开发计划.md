# T-SqlPreviewStandalone：SQL 预览独立打开（工具窗口新 tab + 弹框入口 + XML 代码块识别）开发计划

- 立项时间：2026-11-14 10:46（v2 同日更新：追加 XML 代码块识别需求与 dbx 对照结论）
- 任务源（用户原话）：
  1. 「有用户反馈说插件的sql预览窗口很不错 可以快速格式化sql和填值，希望可以单独打开使用，我初步设定准备在插件工具栏新增一个tab并还允许触发原生弹框」
  2. 「还有用户希望支持xml的代码块sql需要可以正常识别，关于占位符相关或者xml解析，dbx项目中有类似功能可以吸取精华，如果我的插件中有现成的功能优先使用自己的」
- 前置计划：无强依赖；与 `20261114-0020-T-PreviewExecWriteDefer`（exec 文件写延迟）共用预览链，派活前需确认其落位状态
- 状态：**计划待拍板，未动 `src/main`**

## 一、现状取证（事实与出处）

以下为本轮只读侦察结论，源码包根记作 `‹root› = src/main/kotlin/cn/song/mybatisplus/visualizer`。

### 1.1 预览窗口已是「薄壳 + 面板」分离结构

- 弹壳 `ui/SqlPreviewDialog.kt`（168 行）：`DialogWrapper(project, false, IdeModalityType.MODELESS)`，`createCenterPanel()` 只返回 `SqlPreviewPanel`，壳内无业务逻辑。
- 面板 `ui/SqlPreviewPanel.kt`（1707 行）+ 6 个扩展文件：SqlView 939 / ParamPanel 2879 / Execution 2973 / ResultView 1048 / EditorLifecycle 1250 / Text 222。
- 单例分发：`ui/SqlPreviewService.kt:2046-2099` `showResult(project, result, …)`，弹窗按项目单例（`activePreviewDialogs: WeakHashMap<Project, WeakReference<SqlPreviewDialog>>`，:2109-2112）。

### 1.2 面板不依赖 PSI——收到载荷即可全功能工作

- 载荷 `model/PreviewResult.kt:37-60`：`sql / unresolvedParams / previewModel / xmlModel / batchModel / fillClauses / effectiveDialect`。
- `showResult` 收到的已是渲染完成的 SQL 与参数模型，弹窗/面板自身不碰 PSI。
- 已有三条「无语句入口」先例全部走 `构造 PreviewResult → showResult`：
  - 日志条目双击：`log/EntrySourceMatcher.kt:158-166`（`LogEntryPreviewOpener.open`）
  - 历史 tab：`log/HistorySqlLogPanel.kt:892-894`
  - gutter 最近执行：`ui/gutter/SqlGutterLineMarkerProvider.kt:199`

### 1.3 XML 代码块识别：插件已有完整纯文本链，缺的只是「检测门」

- **现成入口（纯文本、零 PSI）**：`ui/XmlPreviewService.kt:82-193` `preview(fileText, statementId, project, …)`——KDoc 明言「纯函数（fileText 输入，无 PSI 依赖）」（:34-48），`project` 可传 null，传 null 时跨命名空间 include、静态常量求值、mapper 方法参数类型等 PSI 增强环节全部跳过（:44-47）。
- 内部链：`XmlMapperParser().parse(fileText)`（JDK DOM + SecureXml 安全配置，`xml/XmlMapperParser.kt:60,191`）→ 找 statement → `XmlPreviewModel.from(statement, …)`（`xml/XmlPreviewModel.kt:403-453`，入参是解析产物 `XmlStatement` 而非 PSI）→ 渲染单源 `XmlSqlRenderer`（`xml/XmlSqlRenderer.kt`）。
- **该入口已投产验证**：MCP `sqlv_render_sql` 的 `xmlSnippet + sourceType='xml_mapper'` 通道直接消费它（`mcp/SqlVisualizerRenderSqlTool.kt:34-60`、`mcp/McpSourceLocator.kt:139-143` `renderXmlSnippet`）。
- **喂回面板的收益**：`PreviewResult.xmlModel` 一旦有值，面板 `renderSql()` 的 xml 分支（`ui/SqlPreviewPanelSqlView.kt:304-357`）自动承接交互式条件勾选、foreach 重放、bind 自动回填——独立工作台贴 XML 片段比纯 SQL 多拿一整层动态标签重放能力，**主体一行不用重写**。
- 动态标签覆盖：`xml/XmlScriptBuilder.kt:33-41`（trim/where/set/foreach/if/choose/bind），`<sql>`+include 展开在 `XmlIncludeExpander`（XmlMapperParser.kt:50-52,77）。
- 占位符现成件：`xml/TokenParser.kt:10-48`（复刻 MyBatis GenericTokenParser，支持 `\#{` 转义）、`XmlSqlRenderer.parseParameterMappings`（XmlSqlRenderer.kt:107-115）、`PreviewResult.unresolvedParams: List<String>` 由 XML 链产出（XmlPreviewService.kt:390）。
- **缺的前置件**：主源码没有任何「粘贴文本是否含 XML 语句块」的检测/提取逻辑（McpRenderInput 是显式声明形态不算检测；PSI 层 `xml/XmlInlineSqlDetector.kt:38` 作用于 XmlTag 非纯文本）。

### 1.4 工具窗口与 tab 先例

- 全仓唯一 ToolWindow：主侧边栏（plugin.xml:425-427，F-44 注释「4 tabs：SQL 日志/日志转SQL/SQL→Java/历史」）。
- 结构是「单 Content + 内部 JBTabs」：`log/SqlLogPanel.kt:225` `JBTabsFactory.createTabs`，四个 `addTab` 在 :851-853 与 :1308。同 ToolWindow 挂多 Content 无先例。

### 1.5 可复用的既有件

- 输入编辑器：`ui/SqlInputEditor.kt:65-108`（SQL→Java tab 的输入框，标准 SQL FileType，降级裸 document）。
- 格式化：`ui/highlight/SqlFormatter.kt`（纯文本 object，日志侧/预览侧同源）。
- 复制可执行 SQL：`ui/components/CopyExecutableSqlAction.kt`。
- 预览编辑器右键菜单组：plugin.xml:590-594 `PreviewEditorPopupMenu`。
- 补全守卫：`ui/highlight/SqlCompletionContributor.kt:23` `IS_PREVIEW_EDITOR`。
- 参数历史/方案：`config/PluginSettings.kt:486-514`（独立工作台填值可直接挂既有历史）。
- MpSqlMapper 注入语言：`ui/inject/SqlMapperLanguage.kt:30`（应用级单例 lexer-only 语言）+ `SqlMapperSyntaxHighlighterFactory`（plugin.xml:451-452）——lexer/parser definition 现成，但**仓库内无非注入场景 setLanguage 直挂的先例**（P-7）。
- 设置/registry：`replaceParams` :48 / `formatSql` :51 / `dialectAuto` :41（PluginSettings.kt）；registry 键 `sqlvis.preview.*` 三枚先例（plugin.xml:490-501）。

### 1.6 dbx 对照结论（自研优先，只吸三点精华）

dbx 仓 `F:\code\Java\Github\other\dbx`（main @ `48f3ed1d4`，TS 前端 CodeMirror 6 + Rust 后端）。**dbx 没有 mapper XML 文件解析能力**（全仓无 `<select id=` 解析、无 XML→SQL 抽取），其 XML 相关只有：把粘进编辑器的 MyBatis XML 片段当占位符源识别（`apps/desktop/src/lib/sql/sqlParameters.ts:466-498` 手写扫描器）、`<foreach>`/`<where>` 标签处理（:741-777）、XML 实体解码（:369-418）、格式化前 XML 整段让位防护（`lib/sql/autoFormat.ts:39-61`）。

判定：**XML 解析主体用插件自己的 `XmlPreviewService` 链**（能力严格强于 dbx：完整动态标签树重放 vs dbx 只认 foreach/where 两个标签的文本级处理）。dbx 值得吸收的精华收窄为三点，全部是插件链没做的：

| # | dbx 精华 | 出处 | 吸收方式 |
|---|---|---|---|
| J-1 | **引号/注释感知的扫描器**：配对 XML 标签时跳过 SQL 字符串/注释/dollar-quote，防字面量里的 `<` 干扰（`findMatchingXmlTagClose` :821-869） | sqlParameters.ts:821-869 | 用于新写的 XML 语句块检测/提取器（D-1b），不用正则整段匹配 |
| J-2 | **XML 实体解码**：`&lt;`/`&gt;` 还原，且仅在出现 mybatis 语法时才做；教训：`&gt;=` 的 `;` 不能当语句终结符（#11699，Rust/TS 双侧 sync 注释） | sqlParameters.ts:369-418、crates/dbx-sql-core/src/sql.rs:637-663 | 用户从网页/文档复制的 mapper XML 常是实体转义形态，检测门先解码再喂 XmlMapperParser（它本身不认实体） |
| J-3 | **空值=故意不解析的逃生门**：raw 空值让占位符保持原样（:279-284）+「忽略占位符原样执行」按钮（SqlParameterDialog.vue:104-107） | sqlParameters.ts:279-284 | 填值面板语义对齐项，若插件现有填值面板已等价则只登记差异不实施 |

dbx 的「执行器认什么高亮就标什么」原则（同一函数供解析与高亮两处调用）作为设计纪律采纳：独立工作台的参数识别与填值面板必须同源（插件侧即 unresolvedParams 一份来源）。

## 二、需求分析

用户要的不是「预览某条语句」，而是把预览窗口当**独立 SQL 工作台**用：随手贴一段 SQL 或 mapper XML 片段，快速格式化、填值、复制可执行 SQL。

能力按输入形态分两路（都汇到同一个工作台输入框）：

| 能力 | 纯 SQL 文本 | mapper XML 片段 | 说明 |
|---|---|---|---|
| 输入/粘贴 + 识别 | 是 | 是（新增检测门 D-1b） | XML 检测优先级高于纯 SQL 解析 |
| 格式化（SqlFormatter） | 是 | 是（渲染后的 SQL） | 纯文本 object |
| 参数识别与填值面板 | 是（`?`/`#{}`/`${}`） | 是（`#{}` 映射 + 动态条件行） | XML 路径 unresolvedParams 由 XmlSqlRenderer 产出 |
| **动态条件勾选 / foreach 重放 / bind 回填** | 否 | **是**（xmlModel 承接） | 独立入口贴 XML 反而比纯 SQL 多一层能力 |
| 方言高亮 | 标准 SQL FileType | 待验证（P-7 MpSqlMapper 直挂） | dbx 教训：`&gt;=` 分号处理归检测门 |
| 补全/表列提示 | 部分 | 部分 | IS_PREVIEW_EDITOR 作用域验证（P-5） |
| 执行 SQL / 控制台 | 是 | 是 | 面板执行链 + DataSourceSelector（P-1 需实测空模型分支） |
| 跨命名空间 include / 静态常量求值 | — | 降级跳过 | project=null 纯文本链的固有边界（XmlPreviewService.kt:44-47），UI 文案说明 |

## 三、候选方案

### 方案 A：纯弹框快捷入口（无 tab）

新增 action「打开 SQL 预览」（Tools/插件组 + 快捷键），从剪贴板（疑似 SQL/XML 时）或空态构造 `PreviewResult` → `showResult`。改动最小，复用度 100%。
缺点：不满足用户「新增 tab」的初步设定；每次都要浮弹窗，无常驻工作区。

### 方案 B：主侧边栏新增「SQL 预览」tab + 弹框入口 + 双源识别工厂（推荐）

- 主侧边栏 JBTabs 加第 5 个 tab「SQL 预览」，内容是**独立预览工作台面板**：输入编辑器（SqlInputEditor 先例）+ 工具条（格式化/填值/复制可执行 SQL/清空/在弹框中打开）+ 填值区。
- **双源识别工厂 `StandaloneSqlSourceResolver`**（新写，纯逻辑可单测），判定门优先级：
  1. **XML 语句块**：含 `<select|insert|update|delete|sql` 语句标签 → 提取语句块文本（J-1 扫描器 + J-2 实体解码）→ `XmlPreviewService.preview(text, statementId=null, project=null)` → PreviewResult（xmlModel 完整，面板自动获得条件勾选/foreach 重放）；
  2. **纯 SQL**：`PlainSqlPreviewFactory` 抽 `?`/`#{}`/`${}` → unresolvedParams，previewModel/xmlModel = null。
- 弹框路径与三条既有入口同构：「在弹框中打开」（及快捷键）= 构造 result → `SqlPreviewService.showResult`，即「原生弹框」就是现有 `SqlPreviewDialog`，不新写弹窗。
- registry 开关 `sqlvis.preview.standaloneTab`（默认 true）控制 tab 显隐，可回退。

### 方案 C：注册第二个独立 ToolWindow

新 stripe 按钮 + 独立工厂。与方案 B 能力相同，但：本仓无双 ToolWindow 先例、stripe 位置稀缺（用户侧边栏已挤）、且用户明确说的是「工具栏新增 tab」。不推荐，仅备选。

### 方案 D：只做 tab、不做弹框入口

省掉弹框联动，但丢失「弹框浮在编辑器上方边看代码边填值」的场景，且弹框路径几乎零成本（同构复用）。不推荐单独做。

### 推荐：方案 B

理由：
1. 与用户初步设定完全一致（tab + 原生弹框双形态），XML 识别一并满足。
2. 复用度最高：XML 解析主体直接用插件自己的 `XmlPreviewService`（MCP 通道已投产验证，严格强于 dbx），新写代码集中在「检测门 + 双源工厂」（纯逻辑可单测）和 tab 宿主面板（组装已有件），不碰 1700 行主面板内部。
3. dbx 只吸三点精华（J-1/J-2/J-3），各自落点明确且都是小件。
4. registry 开关兜底，UI 回退零风险。

## 四、待验证前提（P-x，实施前先证伪）

- **P-1**：`SqlPreviewPanel` 在 `previewModel == null && xmlModel == null` 的 PreviewResult 下，填值面板与执行链完整可用。历史入口（HistorySqlLogPanel:892）已部分佐证，但其条目带 `fillClauses` 与捕获方言；纯文本 `?` 抽出的 unresolvedParams 形态是否被 ParamPanel 接受需实测（先写最小 GUI-free 用例验证）。
- **P-2**：tab 常驻面板与弹框单例可能同时各持一个 `SqlPreviewPanel` 实例（两个 Editor + 填值区）。验证内存/生命周期：面板 Disposer 必须挂 Content 的 disposable，tab 关闭/ToolWindow 隐藏时是否释放。若代价不可接受，退化为「tab 与弹框互斥」（打开弹框时 tab 显示只读摘要）。
- **P-3**：JBTabs 第 5 个 tab 在 4 个既有 tab（其中一个含重内容）下的 UI 密度与选中态持久化（既有 tabs 是否记忆 selected，无则补）。
- **P-4**：纯文本来源的方言推断路径。日志来源走 `entry.dialectName`（PreviewSourceContext.kt:13-19 注释），纯文本/XML 粘贴无此字段；确认 `PreviewDialectResolver`（`sql/PreviewDialectResolver.kt:22`）是否有文本-only 入口，没有则独立工作台默认标准 SQL 高亮 + 用户手动选方言（设置里已有 dialect 手动项）。
- **P-5**：tab 内输入编辑器是否命中 `IS_PREVIEW_EDITOR` 守卫（补全/annotator 的临时文件名 + userData 判定）。若不命中，独立工作台首版可不含补全（只高亮），避免污染判定面。
- **P-6（XML 链 GUI 侧）**：`XmlPreviewService.preview(text, null, null)` 产出的 PreviewResult 喂面板后的渲染完整性。MCP 通道证过的是 CLI 场景；GUI 面板对 project=null 链产出的 xmlModel（无跨命名空间/无参数类型增强）是否照常渲染条件勾选与 foreach 重放，需最小用例实测。另注意 `renderXmlSnippet` 的入参口径（statementId=null 时怎么选语句——多语句片段的默认语句选择策略要定）。
- **P-7（高亮）**：MpSqlMapper 语言非注入场景直挂编辑器（LanguageFileType / EditorHighlighterFactory）是否可行——仓库内无先例。不证则 XML 来源的 SQL 视图维持标准 SQL FileType 高亮（`#{}` 原样显示），并按 dbx 教训确认 `&gt;=` 实体形态不会被误格式化。
- **P-8（检测门鲁棒性）**：真实粘贴语料含 markdown 围栏（```xml）、日志混排、实体转义、SQL 字面量内 `<`（如 `WHERE a < b`）、iBatis 形态（`<statement>`）——检测门的误判率与降级策略（判不出按纯 SQL 处理 + 状态条提示）。dbx 的 `findMatchingXmlTagClose`（引号感知配对）与 `looksLikeXml`（整段让位防护）是直接参照。

## 五、实施拆解（方案 B 落点）

- **D-1 PlainSqlPreviewFactory + 单测**（纯逻辑，先行）：输入文本 → PreviewResult（`?` 计数、`#{param}` 命名抽取、`${}` 识别、注释内占位跳过、空文本/非 SQL 文本降级）。
- **D-1b XML 检测门与语句块提取 `StandaloneSqlSourceResolver` + 单测**（纯逻辑，与 D-1 同批）：
  - 判定：文本含 mapper/iBatis 语句标签（`select|insert|update|delete|sql|statement`，带 id 属性形态优先）；
  - 提取：J-1 引号感知扫描配对标签体；J-2 实体解码（仅当检测到 mybatis 语法）；
  - XML 路径出口：`XmlPreviewService.preview(块文本, statementId, project=null)`；纯 SQL 路径出口：D-1；
  - 单测夹具覆盖 P-8 语料清单（markdown 围栏、实体转义、字面量 `<`、多语句片段、iBatis `<statement>`、非 SQL 的普通 XML 如 pom 片段→判不出降级）。
- **D-2 tab 宿主面板**：`ui/standalone/SqlStandalonePreviewPanel`（组装 SqlInputEditor + 工具条 + 填值区），挂进 `SqlLogPanel` JBTabs（第 5 个 tab，标题走 bundle）。编辑器懒创建，Disposer 挂 Content disposable（P-2 落点）。右键菜单复用 `PreviewEditorPopupMenu`。输入框状态条显示识别结果（「识别为 mapper XML 语句块：selectUserById」/「按纯 SQL 解析」），识别失败给引导文案（按「面板文案要教会用户怎么填」口径）。
- **D-3 弹框入口 action**：`actions.OpenSqlPreviewAction`（id `cn.song.mybatisplus.visualizer.OpenSqlPreview`），双入口——① Tools/插件组菜单项，切到 tab 并聚焦输入框；② tab 工具条「在弹框中打开」按钮，走同一 Resolver 构造 result → `showResult`。快捷键建议 `Alt+Shift+P`（Alt+Shift+Q 已被 ConvertToSql 占用，keymap 双处登记与既有 action 同款写法）。
- **D-4 剪贴板预填（可选加分项）**：action 触发时探测剪贴板文本疑似 SQL/XML 则预填并直接跑 Resolver 出填值区；不疑似则空态。判定复用 D-1b 的检测门（同源，遵循 dbx「执行器认什么检测器就认什么」纪律）。
- **D-5 开关与 i18n**：registry `sqlvis.preview.standaloneTab`（默认 true，登记 plugin.xml `<registryKey>`）；bundle 双语 key 按现有 `preview.*` 族命名。
- **D-6 J-3 逃生门语义对齐**：核对插件现有填值面板的「留空=不解析」行为；不等价才做小对齐（登记差异，不为对齐而对齐）。
- **D-7 测试与验收**：见 §六。定向测试集中在 D-1/D-1b 纯逻辑 + 面板空态/XML 填值渲染（Light 沙盒）。

实施顺序：D-1 + D-1b → P-1/P-6 证伪 → D-2 → D-3 → D-4/D-5/D-6 → D-7。每完成一件可提交的就立即提交（按关键节点分别提交）。

## 六、验收（A-x）

- **A-1** 打开 tab，粘贴含 3 个 `?` 的 SELECT → 填值面板出现 3 行对照 → 填值后「复制可执行 SQL」得到替换完成的语句。
- **A-2** 粘贴含 `#{name}` 的 MyBatis 片段 → 按名填值 → 格式化按钮生效（SqlFormatter）。
- **A-3（XML 主路径）** 粘贴整段 mapper XML（含 `<select>` + `<if>` + `<foreach>`）→ 状态条显示「识别为 mapper XML 语句块」→ 填值面板出现参数对照 + 动态条件勾选行 → 勾选/取消条件、展开 foreach 填集合值 → 渲染 SQL 随之变化（xmlModel 重放生效）；`<where>` 头部 `AND` 自动去除。
- **A-3b（实体转义）** 粘贴 `&lt;=` / `&gt;=` 形态的 XML 片段 → 解码后正常识别，`&gt;=` 不被语句切分截断。
- **A-4** 编辑器任意处按快捷键 → 主侧边栏切到 SQL 预览 tab 并聚焦输入框；剪贴板有 SQL/XML 时预填（D-4）。
- **A-5** tab 工具条「在弹框中打开」→ 现有 modeless 弹窗弹出，弹窗内填值/格式化/执行全可用；日志条目、历史双击两条既有预览路径回归不受影响。
- **A-6** 反向验证：registry 开关置 false → 重启后 tab 不出现（守卫测试钉住开关消费点）。
- **A-7（降级）** 粘贴非 SQL 的 XML（如 pom 片段）或纯闲文本 → 不误判为 mapper XML，按纯 SQL 处理或给引导文案，不崩不弹错误框。
- **A-8** GUI 最终验收由用户做（runIde 沙盒先行自证非 GUI 部分：解析器单测 + 面板空态/XML 渲染用例 + 开关守卫）。

not run 条款：D-4 剪贴板探测、A-4 快捷键真机手感、A-5 弹窗与 tab 双实例并存的实际内存（P-2）、P-7 高亮直挂真机效果——沙盒测不了的项在报告中显式标注 not run / 待用户验收。

## 七、风险

- **检测门误判**（P-8）：SQL 字面量内的 `<`、普通 XML 文件被当 mapper。缓解：J-1 扫描器 + 判不出降级纯 SQL + A-7 用例钉住；不追求识别所有形态，识别失败永远有可用出口（纯 SQL 解析 + 手动提示）。
- **面板体量与双实例内存**（P-2）：缓解 = 懒创建 + 开关 + 互斥退化路线已备。
- **P-6 GUI 侧 XML 链的边界**：project=null 时跨命名空间 include、静态常量求值跳过——独立入口能力弱于编辑器内 XML 起点预览，属预期内降级，文案说明即可，不做 PSI 增强（避免把工作台绑死工程上下文）。
- **并行碰撞**：预览链近两周高频改动（性能十七轮、PreviewExecWriteDefer、PreviewStmtTypeScope 均在途或刚收口），派活前先查 `docs/开发计划/` 在途项与并行登记表；本任务新增文件为主（`ui/standalone/` 新目录），对在途改动的侵入面刻意压小。
- **k2j 端口分支**：master 为 Kotlin，本任务落地后需同步进 feature/kotlin-to-java（走 k2j-port-sync 作业法，另行派活）。
- **平台差异**：JBTabsFactory / EditorFactory / DialogWrapper 均有本仓先例，243~263 无已知差异；MpSqlMapper 直挂（P-7）无先例属待验证项而非风险敞口（验不过就维持标准 SQL FileType）。

## 八、实施记录

（未开工，待用户拍板方案后回填）
