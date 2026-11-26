# 02 官方 mybatis 插件与官方示例范式

> 调研对象：`F:\code\Java\Github\other\openidea`（main @ a7b67347c，2026-11-25 拉取），API `openidea:plugin@1.16.0`。
> 引用路径一律相对该仓根。行号以本次读取的源文件为准。
> 结论按《00-README》四档口径：现成可做 / 变通可做 / 需上游 / 不可行。

## 0 结论摘要

官方 MyBatis 插件（`plugins/official/mybatis`，0.4.2）是一个**纯 provider 型**插件：只声明 `definition` / `line-markers` / `completion` 三类交互式 provider（`plugin.toml:16-18`），**没有**使用 `documentIndexers`、`symbols()` 或任何持久索引。它的跨文件关联是"**每次调用现算 + 一个 TTL 只缓存失配结果的轻量 Map**"（`src/lib.rs:726-779`）。这直接决定了它与我方 Mapper 导航能力的差距（详见第 5 节 A）。

四档结论速览（针对我方功能域里与本章最相关的条目）：

| 能力 | 官方是否实现 | 我方落地档位 |
| --- | --- | --- |
| 接口方法 → XML statement（按名字） | 是 | 现成可做 |
| XML statement → 接口方法 | 是 | 现成可做 |
| namespace → 接口、resultMap/extends/include 跳转 | 是 | 现成可做 |
| @ResultMap / @Results / #{param} 导航与补全 | 是 | 现成可做 |
| 接口声明名 → XML（"接口类名跳 XML"） | **否** | 现成可做（自加一条 definition 记录即可） |
| gutter 图标"同款"（自定义 SVG 品牌图标） | **否**（只用内置四别名） | 变通可做（`[[contributes.markerIcons]]`） |
| 接口方法 → XML 精确跳转（重载/参数类型区分） | **否**（仅按简单名） | 变通可做（需自建更强的索引） |
| "yield 让位"（与语言服务器/他插件协调） | **否**（模型里无此概念） | 不可行 / 需上游（语义不同，见 A） |
| 跨文件持久索引 / Find Usages / Rename statement id | **否** | 变通可做（documentIndexer 图） |
| 多模块 mapper-locations 定位 | 仅全项目 glob + namespace 正则 | 变通可做 |

## 1 plugins/README.md 相关章节摘录

### 1.1 contributions 全表（`plugins/README.md:137-164`）

OpenIDEA 的声明式贡献点（"声明即在，插件代码未加载前就生效"，`README.md:203-208`）。与本任务强相关的在下表加"★"。

| 贡献点 | 声明字段 | 需要代码 | 备注 |
| --- | --- | --- | --- |
| `commands` | id,title,category?,when? | 执行需 | 变 IDE 动作，可绑键 |
| `themes` | path,editor-scheme? | 否 | 主题 |
| `iconThemes` | id,label,path | 否 | 文件图标主题 |
| `languages` | path | 否 | 语言定义 |
| `runDetectors` | id,name,files,command,args?,test?,framework? | 否 | API 1.4，静态运行探测器 |
| `runConfigurations` | files | 是 | API 1.4，运行配置导出 |
| ★ `javaAgents` | path,args?,vmOptions?,jdk? | 否 | API 1.10，Java agent（我方 SQL 捕获） |
| `skills` | path | 否 | API 1.15，agent 技能 |
| ★ `mcpTools` | name,description,inputSchema,readOnly,destructive | 是 | API 1.4，扩展内置 MCP Server（我方 11 工具） |
| `snippets` | language,path | 否 | API 1.4 |
| ★ `providers` | kinds,languages | 是 | 编辑器 provider（导航/补全/gutter） |
| `secrets` | name,label,description? | 用则需 | 网络密钥 |
| `menus` | menu,view?,command,when? | 否 | 右键/工具栏菜单 |
| `keymaps` | command,key,mac? | 否 | 快捷键 |
| `statusBar` | id,text,tooltip?,command?,align?,priority? | 否 | 状态栏 |
| ★ `settings` | schema 或 pages,title? | 否 | 设置页（我方约 40 项） |
| ★ `views` | id,title,icon?,location,web?,frames?,group?,when? | 无 web 时不需要 | 工具窗口 / web 视图 |
| ★ `customEditors` | id,title,patterns,priority,layout?,web?,frames?,binary? | 无 web 时不需要 | 自定义编辑器（SQL 预览台） |
| ★ `editorPanels` | id,web,width,when?,frames? | 否 | API 1.9，编辑器右侧面板 |
| `fileIcons` | id,patterns,icon,iconDark? | 否 | |
| `fileTemplates` | id,name,extension,path;fileName,variables | 否 | |
| `commitChecks` | id,title | 是 | 提交前检查（写操作安全预检可借） |
| `serverCommands` | server,command,run | 否 | API 1.9，认领语言服务器交回的命令 |
| `documentIndexers` | extensions | 是 | 后台跨文件索引（见 1.2） |
| `markerIcons` | id,icon,iconDark?,markers?,tooltip? | 否 | API 1.3，品牌化 gutter 图标 |

另有 `[[depends]]`（API 1.5，插件间依赖）、`[[extension-points]]`（API 1.5，自定义扩展点）、`[[contributes.pages]]`（多页设置，API 1.3）。

### 1.2 Background associations / documentIndexers（`plugins/README.md:253-266`，`plugins/indexing.md` 全文）

- `[[contributes.documentIndexers]] extensions = ["java","xml"]`，实现 `indexing::DocumentIndexer`，`index(doc) -> edges`（`indexing.md:9-27`）。
- `edges` 含 `definitions` / `references` / `aliases` / `markers`，全部是"插件自选 key + UTF-16 range"的 `anchor`（`wit/indexing.wit:35-62`）。
- 宿主自己回答 Go to Declaration、Ctrl/⌘-click、gutter 图标，**不再回调插件**；后台可取消批次、每批 ≤16 文件；编辑/文件变更使受影响条目失效后重建（`indexing.md:50-54`）。
- 上限：单文档 2 MiB、20,000 文件/图、每文档 4,096 def/ref/marker、256 alias、每图 200,000 条、16 项目图、每次查询 256 候选（`indexing.md:56-59`）。
- API 1.3 起该图还驱动 **Find Usages 与 Rename**（锚点必须精确覆盖名字本体，不含引号），无新 WIT 导出（`indexing.md:61-94`）。
- **关键**：`indexing.md:104-105` 明确指出"MyBatis mappers、Rails routes 这类框架插件应放在作者自己的仓里"——即官方设计上认为 MyBatis 该用 documentIndexer，但**官方 mybatis 插件本身并未采用**（见第 2 节）。

### 1.3 Providers（`plugins/README.md:1477-1739`）

- 声明 `kinds = ["definition","line-markers","completion"]`（还可 "inline-completion"、"code-actions"、"semantic-tokens"、"hover"、"inlay-hints"、"diagnostics" 等），`languages` 为编辑器语言 id，`*` 通配（`README.md:1482-1486`）。
- **预算**：编辑路径 50 ms 软上限，超时结果被丢弃；500 ms 硬中断；每个声明该 kind 的插件同时被问，各拿满预算（`README.md:1570-1572`）。provider 与命令/事件/视图共用一个实例、串行排队（`README.md:1574-1580`）。
- `line_marker.icon` 接受内置图标名、别名 `navigate|implemented|implementing|overridden|overriding|run`、或包内 SVG 路径；用包内 SVG 需在 `[[contributes.markerIcons]]` 声明（`README.md:1503-1513`）。多目标时"发布 document-index 映射，宿主用已有选择器列出所有定义"（`README.md:1519-1521`）。
- 其余 kind（`semantic-tokens`/`hover`/`diagnostics`/`inlay-hints`）各有一小节（`README.md:1582-1694`）；`inlay-hints` 走 `InlayHints::inlay_hints(doc, range)`，`range` 是可见区，超出丢弃，label 截 256B（`README.md:1675-1694`）。
- API 1.4 附加语言特性（表格 `README.md:1702-1716`）：`formatting`、`signature-help`、`document-highlights`、`document-links`、`code-lenses`、`selection-ranges`、`folding`、`colors`、`document-symbols`、`implementation`、`type-definition`、`gutter-actions`、`hover-actions`。
- API 1.16 `completion_list` + `incomplete` 支持"边打边问"（`README.md:1488-1501`）。

### 1.4 Views / Web views / custom editors / frames / editor panels（`plugins/README.md:1904-2268`）

- 视图是"数据"：`Body = Tree | List | Form | Table | Message | Html`，IDE 用自身组件渲染、回传 `ViewEvent`（`README.md:1904-1925`、`wit/views.wit:68-120`）。
- `Html` body 跑在沙箱 iframe（不透明源、CSP 仅允许内联脚本/样式与 data: 图、无网络、无 IDE API），只能通过 `openidea.postMessage`/`onMessage` 与插件通信（`README.md:2033-2037`）。主题通过 `data-theme` 与一组 `--openidea-*` CSS 变量跟随（`README.md:2039-2055`）。
- **Web view**：`web = "web/index.html"`，页面目录整体打包签名；`<iframe sandbox="allow-scripts">`；提供 `openidea.document.*`（`get`/`bytes`/`edit`/`replace`/`onChange`）、`saveFile`/`openFile`/`postMessage`/`onMessage`（`README.md:2123-2161`）。
- **frames（API 1.16）**：页面可内嵌外部站点，`frames = ["https://…"]`，每个 origin 须被某个 `net:<host>` 能力覆盖；宿主设 `frame-src`（`README.md:2179-2205`）。
- **custom editor**：`layout = "split"|"preview"|"tab"`；`ctx.editor()` 即该文档，`apply_edits(version, …)` 与文本编辑器共享撤销、脏标记、保存（`README.md:1963-1973`、`views.wit:146-166`）。
- **editorPanel（API 1.9）**：`editorPanels` + `width`，页面拿 `openidea.editor`（`get`/`onTarget`/`viewport`/`scrollTo`/`highlights`/`problems`/`folded`/`selections`/`changes` 等），需 `editor.read`（`README.md:2213-2268`）。
- 相关 API：`open-content-tab` 协议（插件供给内容的只读/可存编辑器页，带工具栏动作回调，`README.md:2545-2593`）、`open-log-tab`（流式日志标签页）。

## 2 官方 mybatis 插件逐段精读

文件：`plugins/official/mybatis/src/lib.rs`（1700 行）、`plugins/official/mybatis/plugin.toml`、`Cargo.toml`。测试从 `lib.rs:1111` 起。

### 2.1 包与声明（`plugin.toml`）

| 字段 | 值 | 行 |
| --- | --- | --- |
| id / version | `openidea.mybatis` / `0.4.2` | 1-3 |
| engines | `^1.4` | 9 |
| activation | `["on-command:*"]` | 11 |
| required capabilities | `["project.read"]` | 13-14 |
| providers | `kinds=["definition","line-markers","completion"]`，`languages=["java","xml"]` | 16-18 |

`Cargo.toml`：`crate-type=["cdylib","rlib"]`，`edition=2024`，release `opt-level="s"` + `lto` + `strip` + `panic="abort"`，依赖 `openidea-plugin`（path SDK）。

要点：manifest 只申请 `project.read` 一个低危能力，未声明 `documentIndexers`，也未声明 `markerIcons`。这决定了它的所有关联都必须"按需读文件/搜索"。

### 2.2 数据模型

| 结构体 | 作用 | 行 |
| --- | --- | --- |
| `Named {name, range}` | 一个命名物 + 其名字的 range | 27-30 |
| `MapperXml` | namespace / statements / result_maps / sql_fragments / result_map_refs / extends_refs / include_refs | 34-49 |
| `Param {name, range}` | 方法参数（@Param 优先） | 54-57 |
| `AnnotationSql {method, strings, params}` | 注解 SQL 中的 `#{}`/`${}` | 64-68 |
| `ResultMapRef {method, name}` | 方法上的 @ResultMap 引用 | 73-76 |
| `MapperJava` | 全限定名 / 接口声明名 / methods / annotation_sql / result_refs / result_ids | 80-90 |
| `Positions<'a>` | 多字节偏移→UTF-16 行列的一次性顺序扫描器 | 95-137 |

模型粒度很浅：XML 只抽"标签名 + 几个属性值"，不建动态 SQL 树、不存 SQL 正文；Java 只抽接口名、方法名/参数名、注解 SQL 片段与 @ResultMap/@Results。**没有类型解析、没有继承链、没有 build 元数据**。

### 2.3 XML 解析（`parse_xml`，`lib.rs:147-199`）

单趟扫描 `<`，对每个标签取名字，再按需读 `attr="..."`（`named` 闭包，159-163）。识别：

- `mapper` → `namespace`（164-165）
- `select|insert|update|delete` → `id`、`resultMap` 引用（166-172）
- `resultMap` → `id`、`extends`、以及嵌套 `resultMap=` 引用（173-183）
- `association|collection|case` → `resultMap=` 引用（184-187）
- `sql` → `id`（188-191）
- `include` → `refid`（192-196）

手法亮点：`<` 不可能出现在属性值内，所以"没有 `>` 的标签"在下一个 `<` 处截断（注释 155-157），一个坏标签不会吞掉后文；`parse_xml` 对 5 万个未闭合标签仍单趟完成（测试 `lib.rs:1646-1655`）。**不解析动态标签（`<if>`/`<where>`/`<foreach>`）、不解析 `<selectKey>`、不解析 SQL 文本**。

### 2.4 Java 解析（`parse_java` + 助手，`lib.rs:592-692`）

- 包名取第一处 `package X;`（593-594）；`interface ` 后取接口简单名，拼成全限定名（595-602）。
- 从 body 起点逐字节扫描（618-690），跳过字符串/字符字面量（`skip_string` 206-222）、注释（`skip_comment` 224-235）；括号深度区分"注解参数内"与"结构括号"；接口层 `;` 结束一条语句（648）。
- `scan_declaration`（251-315）：跳过注释/字符串/注解参数，取"第一个剩余 `(` 之前的最后一个标识符"为方法名——所以 `@Select("…") User find(...)` 命名 `find` 而非 `Select`。
- `params_of`（342-384）：按括号/花括号/方括号/字符串外的逗号切分；`@Param("x")` 优先取字符串值，否则取参数最后一个标识符。
- `sql_params`（390-410）：扫 `#{`/`${`，名字止于 `,`、`}`、空白。
- `annotations_of`（421-477）+ `annotation_sql_of`（556-569）：抽 `@Select/@Insert/@Update/@Delete` 字符串里的 `#{}`/`${}`；`result_map_of`（575-589）抽 `@ResultMap` 引用与 `@Results(id="…")` 定义。
- **default 方法被显式排除**：`!stmt[..decl.parens.0].contains(" default ")`（651）——接口 default 方法不当映射方法。

### 2.5 关联发现与 scope 判定

**Java → XML（`Navigator::xml_for`，`lib.rs:741-779`）**，三级降级：

1. 命中缓存路径 → 读之、校验 namespace 仍匹配（748-752）；
2. 缓存"未命中"且未过 `INDEX_TTL`（30s，第 16 行）→ 直接 `None`（753）；
3. 否则：先按接口简单名 glob `**/{Simple}.xml`（`find_files`，上限 `MAX_MAPPERS=64`，758-766）；再不行才全项目 `search_text(namespace_query)`（正则匹配 `namespace\s*=\s*["']…["']`，仅 `**/*.xml`，767-772）。

缓存：`xml_index: BTreeMap<root, BTreeMap<namespace,(Instant, Option<path>)>>`（726-732）；仅"未命中"受 TTL 约束，命中每次仍重读文件确认（因为文件可能被改名/改 namespace）；`MAX_INDEXED=1024` 且超限时先 `retain` 过期项（774-776）。

**XML → Java（`Navigator::java_for`，`lib.rs:782-791`）**：namespace 必须是合法 Java 名（否则不当 glob），按 `**/com/acme/UserMapper.java` `find_files` 取首个并解析，再核对 `java.name == namespace`。

**scope 判定（`project_of`，`lib.rs:712-718`；`is_under`，721-724）**：遍历 `host::projects()`，取"包含该路径的项目根中路径最长者"（最深项目胜出）。`is_under` 用"前缀 + 边界字符"判断，避免 `/proj-x` 被当成 `/proj` 内。

**多模块/多 source root**：没有读 build 配置（`mapper-locations`）；完全依赖"全项目 `find_files` glob + namespace 正则"，因此多模块只要在同一项目根下都能搜到，但**不尊重 `mybatis.mapper-locations` 的收窄**，也无"哪个模块属于哪个 source root"的概念。`project_of` 只到"项目"，不到"模块"。

### 2.6 导航 / 装订区 / 补全（`impl Providers`，`lib.rs:953-1066`）

- `links`（872-946）是核心：解析当前文档，产出 `(Named, Location, tooltip)` 列表。
  - XML 分支（874-908）：`<mapper namespace>` → 接口声明；每个 statement id → 同名方法（889-892）；`resultMap`/`extends` 引用与 `include refid` 经 `anchor_target` 解析（896-905，支持 `namespace.id` 全限定与跨文件）；最后按行号排序保证 gutter 自上而下（906-907）。
  - Java 分支（910-945）：`@ResultMap` → 本文件 `@Results(id)` 或 XML `<resultMap>`（918-936）；方法名 → XML 同名 statement（937-943）。
- `line_markers`（954-965）：XML 用图标 `implementing`，Java 用 `implemented`；tooltip `"Go to {tip}"`；`target=Some(location)`（即单击跳转）。
- `definition`（967-988）：当前光标落在某个 `Named.range` 内则返回其 `Location`；Java 里额外把注解 SQL 的 `#{x}`（含 `#{user.name}` 取首段）映射到 `@Param`/参数（973-986）——**纯注解 mapper 无需 XML**。
- `completion`（990-1065）：
  - Java：`@ResultMap("` 内补全已知 resultMap id（`result_map_completion`，828-869），`#{` 内补全方法参数（`annotation_param_completion`，1069-1101）。
  - XML：statement `id="` 内补全"接口里尚未被映射的方法"（1009-1025）；`<include refid="` 内补全本文件 `<sql id>`（1027-1043）；`#{` 内补全所在 statement 对应方法的参数（1045-1063）。

### 2.7 缓存、失效与性能手法

| 手法 | 位置 | 说明 |
| --- | --- | --- |
| 失配 TTL 缓存 | 16, 753, 777 | 仅"找不到 XML"的结果缓存 30s，避免反复全项目搜索 |
| 命中每次重读 | 748-752 | 命中路径也读文件校验 namespace，容忍编辑/改名 |
| 上限裁剪 | 18, 774-776, 20, 22 | `MAX_INDEXED=1024`、`MAX_OTHER_MAPPERS=32`、`MAX_MAPPERS=64` |
| 单趟位置换算 | 95-137 | `Positions` 顺序扫描，多名字约一趟而非每名一趟 |
| 搜索降级 | 738-740, 758-772 | 全项目 `search_text` 最慢，放最后，且只对"未命中"重试 |
| 每文件引用上限 | 815-820, 895(clear) | 一个文件引用上千 mapper 时只解析前 32 个（测试 1686-1699） |
| debounce / cancel | 无（插件内） | 完全依赖宿主 50 ms 预算与取消；插件自身不实现防抖 |

**注意**：插件没有内建防抖或取消语义；依赖宿主"超时丢弃/500ms 中断"（`README.md:1570-1580`）。所以它把最慢的搜索放在"最后兜底"，并靠 TTL 缓存降低触发频率。

### 2.8 支持的注解/XML 形态

| 类别 | 支持 | 不支持（待验证/缺口） |
| --- | --- | --- |
| XML 标签 | mapper / select / insert / update / delete / resultMap / sql / include / association / collection / case | 动态标签 `if/where/foreach/choose`、`selectKey`（不解析内容） |
| 注解 | `@Select/@Insert/@Update/@Delete`、`@Param`、`@ResultMap`、`@Results(id=)` | `@MapKey`、`@One/@Many` 内嵌 SQL、`@SelectProvider` 等 |
| 参数占位 | `#{}` / `${}`，`#{id,jdbcType=…}` 取 `id`，`#{user.name}` 取首段 | 复杂 OGNL 表达式内的名字 |
| resultMap 引用 | 语句/association/collection/case 上的 `resultMap=`、`resultMap` 的 `extends=` | — |
| 跨 mapper 引用 | `namespace.id` 形式（id 不含点，末段为 id） | — |

### 2.9 已知局限（与源码证据）

1. **匹配只按"简单名"**：XML statement id ↔ 方法名完全字符串相等（889-892、939-943），不区分重载/参数类型；同名重载会全部命中同一 statement。
2. **不做继承/接口层次**：不解析 `interface X extends Y`，父接口方法不继承可见；`default` 方法被显式跳过（651）。
3. **无"接口声明名 → XML"**：Java 分支的 `links` 只产出 `@ResultMap` 与方法名两类跳转（917-944），接口声明名本身没有装订图标/跳转。
4. **无持久跨文件索引**：无 `documentIndexers`、无 `symbols()` 调用、无 `storage` 持久化；每次 provider 调用重算，只有失配 TTL 缓存。
5. **不支持 Find Usages / Rename statement id**：因未发布 index graph，无法获得（`indexing.md:61-94` 的能力只在 documentIndexer 路径可用）。
6. **图标不品牌化**：只用 `implementing`/`implemented` 内置别名（955），未声明 `markerIcons`。
7. **多模块 mapper-locations 不尊重**：全项目 glob + namespace 正则，可能命中非 mapper 目录里的同名 XML（用 namespace 核对兜底，但会多做无用 IO）。
8. **annotation SQL 仅导航参数**：不渲染、不校验、不做 SQL 解析（与"SQL 可视化"完全不同层）。

## 3 其余官方插件范式取精

| 插件 | 关键范式（出处） | 可借鉴到我方哪条功能 |
| --- | --- | --- |
| graphviz（`plugins/official/graphviz`） | 自定义编辑器 + `web` 页面（`plugin.toml:18-24`，`web-src/index.html`）；页面把 Graphviz 报告经 `ViewEvent::Message` 发回，插件 `host::publish_diagnostics` 发布问题（`src/lib.rs:80-94`，`parse_reports` 25-44，`diagnostics` 48-60）。 | SQL 预览工作台：web 页面渲染 + 反向把"方言警告/性能问题"回写成 Problems。 |
| csv-editor（`csv-editor`） | 自定义编辑器不持有副本，每次渲染解析文档、每次编辑是"带版本号的文本编辑"（`src/lib.rs:1-7, 192-244`），`apply_edits(doc.version, …)` 版本不符返回 `Stale`（`315`）；大文件只取可视行（`Viewport`，`210-225`），缓存"上次解析"（`119, 130-138`）。 | SQL 结果表格 / 参数表格 / CSV 结果导出：表格视图与文本视图共享撤销，版本化写回。 |
| checkstyle（`checkstyle`） | 保存事件触发 `process:checkstyle`（`plugin.toml:11-15`），可取消任务、新保存取消旧检查（`src/lib.rs:152-175, 234-254`）；`-f xml` 报告→diagnostics（60-102）；`code_actions` 提供文本级 quick fix（179-215）。 | 方言校验/性能建议：保存或命令触发 → Diagnostics；可修的（如关键字大小写、尾随空格）→ code action；`process:` 跑外部校验器。 |
| string-tools（`string-tools`） | 对每个选区（空选区取词/整行）做变换，合并为**一次可撤销编辑**，重叠选区去重，`Stale` 时重试一次（`src/lib.rs:78-100`）；`lines_command` 区分整行命令（24-26）。 | 多光标 SQL 片段格式化/大小写/参数命名变换；批量选区一次性改写。 |
| regex-tester（`regex-tester`） | 工具窗口用 `html` body（`plugin.toml:16-20`），页面与插件双向 `ViewEvent::Message` / `host::post_view_message`（`src/lib.rs:164-209`）；"Use Selection" 读编辑器选区（148-162）；状态由设置初始化（136-145，`default_flags`）。 | SQL 日志还原/正则抽取面板、内置正则试跑器；把编辑器选区带入工具窗口。 |
| license-header（`license-header`） | `on-event:file-opened` + `file-saved` 双触发诊断（`plugin.toml:11-14`，`src/lib.rs:261-276`）；"扫描全项目"在后台任务中做，`begin_task` + `task.report` 进度 + 可取消（211-239）；quick fix 一个 `TextEdit`（280-291）。 | 首开/保存时做 SQL 安全检查；全项目扫描（如扫描所有 mapper XML 报告未映射 statement）在任务里跑。 |
| git-toolbox（`git-toolbox`） | `on-schedule:300` 定时 + `editor-focused`/`vcs-changed` 事件驱动；状态栏项 `set_status_item` 显示分支/走查数（`src/lib.rs:77-90, 157-196`）；`storage` 记最后拉取时间（66-73）。 | 后台定时刷新（如定时重扫 mapper 索引、定时校验），状态栏显示"索引状态 / 未映射数"。 |
| ai-completion（`ai-completion`） | `inline-completion` provider，`HttpRequest` + `{{secret:api-key}}` 由宿主注入、插件永不见密钥（`src/lib.rs:91-126`，`plugin.toml:13-20`）；仅在词尾/闭合符号前请求（`request_body` 65-82）；取消即静默（112-113）。 | 若做"AI 解释 SQL/生成 SQL"，用 `net:` + `secrets` + inline provider；密钥不入插件。 |
| solidity（`solidity`） | 纯语法 provider：手写 lexer 产 `highlights/outline/folding`（`src/lib.rs:19-31`）；语言/运行配置全部走 `languages/*.toml` + `runDetectors`（`plugin.toml:17-41`）。 | 若需自建 `.sql` / `.xml` 语言的着色、结构、折叠；运行探测器可探测 MyBatis 项目（`mybatis-config.xml`）。 |
| blame-annotations（`blame-annotations`） | 极简 `language-features.code_lenses` + `host::set_decorations` 行尾注释（`src/lib.rs:8-41`）；用 `vcs_blame` 需 `vcs.read`/trust。 | 在 SQL 行尾显示"来源 statement / 上次修改"等装饰性标签。 |
| harbor-night（`harbor-night`） | 纯数据主题：只有 `plugin.toml` 的 `[[contributes.themes]]`，无 wasm、无能力、无编译（`plugin.toml:1-16`）。 | 我方若带品牌主题/语法配色，可零代码交付。 |

补充：`key-promoter`（action 事件 + storage）、`todo-finder`、`container-runner`、`test-runner` 未逐行精读，仅从 `plugins/README.md:19-37` 目录注释知其为"action 事件/统计/终端/测试结果"型，范式与上表重复，不赘列。

## 4 必须回答

### A. 官方实现细节与我方 Mapper 导航能力的差距

先给"官方实现了什么"（证据均在 `plugins/official/mybatis/src/lib.rs`）：

- 接口方法 → XML statement：`links` Java 分支 `937-943`（按方法名找同名 statement），`line_markers` 954-965 出图标。
- XML → 接口：statement 上 `definition` 命中 `Named.range` 返回方法 `Location`（889-892、967-988）。
- namespace → 接口、resultMap/extends/include → 目标（含跨 mapper 的 `namespace.id`）：872-908、796-823。
- @ResultMap/@Results、#{param} → @Param：918-936、973-986、828-869、1069-1101。
- gutter：`implemented`/`implementing` 两种内置图标 + tooltip + 单击目标（954-965）。

逐项对照我方能力：

| 我方能力 | 官方是否有 | 差距 / 官方模型缺什么 |
| --- | --- | --- |
| 接口方法 → XML statement 精确跳转 | 部分（仅按简单名） | 模型里方法只有 `name + params[name]`，无参数类型/返回类型/重载键；XML 侧只有 `id` 字符串。**无法区分重载**，无法处理"多 statement 同 id 不同库" |
| XML → 接口 | 是 | 仅按 id==方法名；不解析继承链，父接口方法不命中 |
| 接口类名跳 XML | **否** | `links` Java 分支只产出 `@ResultMap` 与方法两类；接口声明名无装订/跳转记录。模型里 `MapperJava.decl` 有声明名与 range，但只在 XML→Java 方向使用（881-887），反向未用 |
| gutter 图标同款 | **否** | 只写死内置别名（955），未声明 `markerIcons`，无法给品牌 SVG |
| Related（多目标选择/相关符号） | **否** | `line_marker.target` 是单个 `option<location>`（`wit/providers.wit:62-69`）；`definition` 可返回多 `location` 但无"Related"聚合语义。真多目标需 documentIndexer 的 chooser（`indexing.md:30-32`） |
| yield 让位 | **否，且模型无此概念** | OpenIDEA 的 provider 结果是"与语言服务器结果合并"（`README.md:1543-1546`），没有 IntelliJ 的 `GotoDeclarationHandler` 链式让位/`yield` 语义。所谓"让位"在此只能靠"返回空列表即无贡献"，无法表达"我处理不了、交给下一个特定处理器并保持优先级" |

结论：官方在"按名字的双向跳转 + gutter + 注解参数跳转"上是**现成可做**的样板，但**"精确"（重载）、"接口类名跳 XML"、"图标同款"、"Related"、"yield" 都没做**。我方要做到"精确 + Related + 图标同款"，需要：

- 精确/重载：自建"接口方法签名（名字+参数类型序列）+ XML statement（id + 参数形态）"的更强模型；但**取不到 Java AST/PSI**（`00-README:3`），参数类型只能靠文本解析近似 → 属"变通可做"，精度受限（待验证）。
- 接口类名跳 XML：在 `definition`/`links` 里给 `MapperJava.decl.range` 补一条到 XML 的 Location（现成可做，官方只是没做）。
- 图标同款：`[[contributes.markerIcons]]` + 包内 SVG（`README.md:1503-1538`），变通可做。
- Related：多目标用 `definition` 返回多 Location + 宿主选择器，或用 documentIndexer 图；"Related 弹窗"这种 IntelliJ 专有交互无对应，属"不可行/需上游"。
- yield：无对应语义，属"不可行"。

### B. 可直接照抄的 6~10 个范式清单

| # | 范式名 | 官方出处（file:line） | 我方对应功能的落地方式 |
| --- | --- | --- | --- |
| 1 | 双向 links 表 + 装订区渲染 | `plugins/official/mybatis/src/lib.rs:872-965` | 建 `links(doc)` 统一产出 (命名物, 目标, tooltip)，`line_markers`/`definition` 共用；Java+XML 双向。 |
| 2 | Java→XML 三级降级查找（缓存命中→glob→namespace 正则搜索） | `lib.rs:741-779` | Mapper↔XML 关联发现；先按文件命名约定 glob，再正则搜 namespace，最慢路径兜底 + TTL。 |
| 3 | 失配 TTL 缓存 + 上限裁剪 | `lib.rs:16,18,20,22,726-732,753,774-776` | 自建关联缓存（命中重校验、未命中 TTL、超限 retain）。 |
| 4 | 单趟多偏移位置换算 | `lib.rs:95-137` | 一次解析里大量名字→range，避免每名一趟扫描（映射到 SQL/XML 批量锚点）。 |
| 5 | 文本级 Java/XML 扫描（跳过字符串/注释/注解参数） | `lib.rs:251-315,342-410,421-477,592-692` | 无 AST 前提下解析接口签名、@Param、`#{}`、resultMap 引用。 |
| 6 | statement id 补全"未映射方法"、refid 补全 `<sql>`、`#{` 补全参数 | `lib.rs:1009-1043,1045-1063,1069-1101` | 我方 XML 编辑补全（反查接口方法/片段/参数）。 |
| 7 | 自定义编辑器/视图承载富 UI + 双向消息 | `graphviz/src/lib.rs:80-94` + `plugin.toml:18-24`；`regex-tester/src/lib.rs:164-209` | SQL 预览工作台（web 视图/自定义编辑器）+ 页面回传告警。 |
| 8 | 版本化文本编辑 + 共享撤销 + 可视行分页 | `csv-editor/src/lib.rs:192-244,210-225` | 结果表格/参数表格编辑写回源文本；大结果只取可视行。 |
| 9 | 保存/打开事件触发检查 + 可取消任务 + Diagnostics + quick fix | `checkstyle/src/lib.rs:152-175,179-215,234-254`；`license-header/src/lib.rs:211-239,261-291` | 方言校验/性能建议（Diagnostics + code action）；全项目扫描在 task 里。 |
| 10 | 定时/事件驱动后台刷新 + 状态栏 | `git-toolbox/src/lib.rs:77-90,123-155,157-196` | 定时重扫索引/状态栏显示"索引状态、未映射 statement 数"。 |
| 11 | Java agent 声明（零代码装载） | `plugins/README.md:2498-2543`；`[[contributes.javaAgents]]` | Java agent SQL 捕获：随包 jar + `-javaagent` 注入 Application/Spring Boot/JAR 运行配置。 |
| 12 | mcpTools 扩展内置 MCP Server | `plugins/README.md:1234-1292`；`wit/mcp.wit` | 11 个 MCP 工具：`[[contributes.mcpTools]]` + `mcp-provider.call`，`readOnly/destructive` 标注（写操作预检可走 `destructive` 确认）。 |
| 13 | commitChecks 提交前门禁 | `plugins/README.md:1468-1475`；`wit/commit.wit` | 写操作安全预检：commit 前对改动 SQL 做危险操作拦截。 |
| 14 | 设置 schema 多页 + 双语 locales | `plugins/README.md:2619-2714,2595-2617` | 约 40 设置项分页；双语用 `locales/zh-CN.json` 映射英文源串。 |

（表中给出 14 条，可按"6~10"取最核心的前 10 条；B 题要求的"6~10"，建议优先 1、2、3、5、6、7、8、9、11、12。）

### C. 「跨文件索引/符号」在插件侧的一般做法

三条路，按官方设计意图与约束排序：

**C.1 documentIndexer（宿主托管的项目级索引）——官方推荐、覆盖最大**

- WIT：`wit/indexing.wit`。`document-indexer.index(doc: document) -> edges`，`document` 含 `path`、`text`、以及**宿主语法支持已找出的 `symbols`**（`symbol`：name/kind/container/range，`indexing.wit:11-28`）；`edges` 含 `definitions`/`references`/`aliases`/`markers`，均为 `anchor{key, range}`（35-62）。
- 宿主行为：后台可取消批次（每批 ≤16 文件，`indexing.md:50-52`），跨文件解析点击与 gutter **不再回调插件**；`alias` 支持继承成员（前缀 `prefix#` 也按 `target#` 解析，`indexing.wit:38-46`）；API 1.3 起驱动 Find Usages/Rename。
- 容量（`indexing.md:56-59`）：单文档 2 MiB、20,000 文件/图、每文档 4,096 def/ref/marker、256 alias、每图 200,000 条、16 项目图、256 候选/查询。
- **能否在插件内自建持久索引**：可以"存"但不能"查"得像索引——`host::storage` 是 KV，**每插件 64 MiB**、按 UTF-8 计（`wit/host.wit:199-210`、`README.md:360`），适合存"路径→namespace 缓存/ETag"，不适合当可查询的项目图（无前缀范围以外的查询、无 range 查询）。真正的"项目级图"应由 documentIndexer 由宿主维护。私有目录 `host::fs::plugin_dir()` 可放大缓存文件（`README.md:405-421`），但同样不是索引服务。

**C.2 交互式 provider 每次现算 + 自建缓存——官方 mybatis 的实际选择**

- 即第 2 节所述：`definition/line-markers/completion` 在 50 ms 预算内现算，配失配 TTL 缓存。优点是简单、无需发布图；缺点是不能支持 Find Usages/Rename、多目标 chooser 受限、每次调用重复 IO。
- 若我方要"精确"与"多目标"，这条路要自建更强模型 + 更激进缓存，但在 50 ms 预算下对超大项目有风险（官方把全项目搜索放最后并只对未命中重试，正是为此）。

**C.3 `Project::symbols()`（宿主内存符号索引，模糊搜索）**

- `wit/workspace.wit:96-105`：`symbols(query, limit)` 模糊符号搜索（CamelHump、容器限定），**只查当前索引、不启动/不等待语言服务器**，结果可能不完整、range 反映索引文本而非未保存编辑。可用于"按类名/方法名快速定位"，但**不返回跨文件关联边**，不能替代 C.1 的 def/ref 图。

**多模块项目怎么定位 mapper-locations**：

- 无"读 build 配置的 mapper-locations"专用接口。可用的只有 `project.read_file` 读 `application.yml`/`application.properties`/`mybatis-config.xml` 自行解析（变通可做，需自写解析），或直接 `find_files("**/*.xml")` / `find_files("**/*Mapper.xml")` 全项目 glob 后按 `namespace` 复核（官方做法，`lib.rs:758-772`）。
- 多 source root/多模块：靠 `project_of` 只取"最深项目根"（712-718），模块级归属无 API；建议以"namespace + 文件名约定"关联，不强依赖模块边界（待验证：是否有 API 暴露模块信息——`build.dependencies` 只给依赖图，`workspace` 无模块概念）。

结论：**能在插件内自建缓存但不能自建可查询的持久索引**；要"项目级跨文件图"应走 documentIndexer（官方推荐，`indexing.md:104-105` 明示框架插件归属），`symbols()` 只补"按名快速跳转"。官方 mybatis 未用 documentIndexer 是其局限根源（不能 Find Usages/Rename、多目标弱、每次重算）。

## 5 未解决 / 需上游确认

1. **provider 预算与重分析**：50 ms 软上限/500 ms 硬中断（`README.md:1570-1572`）下，"精确重载匹配 + 多模块 glob"是否能在大型项目稳定达标——需真机实测，属"待验证（推断）"。
2. **无法获取 AST/PSI**（`00-README:3`）：精确到"参数类型序列"的方法签名只能文本解析近似；`Project::symbols` 是否包含方法参数类型未在文档明确——待验证。
3. **"yield 让位"语义缺失**：OpenIDEA provider 结果与 LSP 结果"合并"，无 IntelliJ `GotoDeclarationHandler` 链式让位；若产品要求"我方优先、否则让给语言服务器并保序"，需上游提供优先级/让位机制 → **需上游**。
4. **Related 交互**：无 IntelliJ "Related Symbol" 弹窗；只有 `definition` 多 Location + 宿主 chooser 或 documentIndexer 多目标 chooser。若要"Related 分组图标"原样体验 → **需上游**。
5. **mapper-locations 读取**：无 build 配置解析接口，需自读 `application.yml`/`mybatis-config.xml`；Spring Boot 多 profile、Gradle/Maven 属性插值等边界未验证——待验证。
6. **documentIndexer 与 provider 混用**：一个组件可同时导出两者（`register_plugin!(Plugin, providers, indexing)`，`indexing.md:15-16`），但"两套结果是否冲突/谁优先"未见明确文档——待验证；建议导航走 documentIndexer、补全走 provider。
7. **EXPLAIN / 方言校验的 SQL 执行面**：`database` 接口**没有执行 SQL 的函数**（`wit/database.wit` 全文、`00-README:6`），只有"管理插件自己的数据源 + 触发 IDE 连接/内省"；EXPLAIN 需走 `process:`（外部 CLI/JDBC 工具）或 IDE 语言服务器的自有方法（`language_server_request`，仅限服务器自身方法，`README.md:979-990`）→ 属"变通可做/需上游"，详见 04 分册。
8. **Java agent 覆盖面**：仅 Application/Spring Boot/JAR Application 的 Run/Debug 生效；测试、Maven/Gradle 目标、应用服务器、远程 JVM 不支持（`README.md:2539-2543`）——与"全场景 SQL 捕获"目标有缺口，详见 05 分册。

---

主要证据文件（绝对路径）：
- `F:\code\Java\Github\other\openidea\plugins\official\mybatis\src\lib.rs`
- `F:\code\Java\Github\other\openidea\plugins\official\mybatis\plugin.toml`
- `F:\code\Java\Github\other\openidea\plugins\README.md`
- `F:\code\Java\Github\other\openidea\plugins\indexing.md`
- `F:\code\Java\Github\other\openidea\wit\{indexing,providers,views,workspace,host,io,mcp,database,inlay,search,plugin,exports}.wit`
- `F:\code\Java\Github\other\openidea\plugins\official\{graphviz,csv-editor,checkstyle,string-tools,regex-tester,license-header,git-toolbox,ai-completion,solidity,blame-annotations,harbor-night}\`
