# 06 · 用户可见面在 OpenIDEA 的落地方案与体验差距

> 调研对象：OpenIDEA 仓库 `F:\code\Java\Github\other\openidea`（main @ `a7b67347c`，2026-11-25 11:22，WIT 包 `openidea:plugin@1.16.0`）。
> 目标版本口径：本机 0.1.51 随附 **API 1.15**（已发布）；`main` 上的 1.16 仍在开发（`plugins/README.md:430-433`）。标注「1.16 独有」的能力在 1.15 插件里不可用。
> 我方对照：`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer`（master，2026-11-25），点数与证据主要引自同目录 `08-our-feature-inventory.md` 与本次复核。
> 方法：只读源码 + README 全文；未构建、未运行 IDE。推断项统一标注「待验证（推断）」。

---

## 0. 结论速览

| 面 | 结论档位 | 一句话 |
| --- | --- | --- |
| 预览工作台（10 项） | 变通可做 | 没有插件弹窗；用「底部工具窗口 + 包内 web 页」重做，交互与自绘能力够，模态感与被代码遮挡的便利性丢失 |
| gutter 图标（19 类） | 现成可做 | `line-markers` 图标 + `gutter-actions` 命令点，SVG 可还原现有双色图标；但共享一条图标列，且没有失效重取通道 |
| inlay hints | 现成可做（样式受限） | 只给文本/浮层，宿主统一画灰底圆角 chip；插件不能设色；对全语言生效、无「只认本插件编辑器」的隔离手段 |
| 右键动作（9 个） | 现成可做 | `editor.context` / `projectView.context` / `tools` / `mainToolbar` 都有；Database 树右键无对应挂点（需上游） |
| 设置 8 页 118 字段 | 现成可做（有损） | 118 ≤ 200、8 ≤ 32 页均满足；但字段不能条件显示、不能自定义设置页 UI、示例文本 ≤300 字节且不参与翻译 |
| 双语（1557 键） | 变通可做 | 静态声明与视图数据可全译；带 `{0}` 参数的 318 条与插件名/描述不可走目录，需自建词表 + `host::locale()` |
| 通知 / 状态栏 | 现成可做（减配） | 通知 ≤3 个动作按钮、无分组；状态栏只有文本+tooltip+命令（1.14 起可带菜单），无图标与配色，文本 ≤128 字节 |
| SQL 日志面板 | 不可行（捕获面） | 插件拿不到 IDE 运行/调试控制台输出（宿主明确「插件永远看不到输出」）；只能靠 javaAgent/外挂进程绕行 |
| JOIN 关系图 / 原生执行/EXPLAIN 提交 | 不可行（原生组件） | 无 Swing/原生组件嵌入；图只能自绘（web canvas），执行与 EXPLAIN 提交受 `database.wit` 无 query 限制（详见 04 册） |

---

## 1. 机制总览：界面在 OpenIDEA 里到底是什么

### 1.1 五种承载形态

| 形态 | 声明/调用 | 内容来源 | 关键约束 | 证据 |
| --- | --- | --- | --- | --- |
| 工具窗口 | `[[contributes.views]]` + `view-provider` 导出 | 代码渲染 `View`（Toolbar+Body+Status） | 只在 left/right/bottom/services 停靠；`group` 相同的视图共用一个窗口、按声明序成 tab；`when` 控制显隐 | `plugins/README.md:1904-1932,1980-2002` |
| 网页视图 | `views` / `customEditors` / `editorPanels` 加 `web=` | 包内 `web/index.html`，随包签名 | 目录 ≤4096 文件 / 256 MiB、不许链接；沙箱 iframe、无网络、无 IDE API | `plugins/README.md:2123-2156` |
| HTML 主体 | `Body::Html(string)` | 代码返回的 HTML 字符串（≤4 MiB） | 单文件、内联脚本样式、`img/font/media` 仅 `data:`；**无 wasm、无 blob、无 worker**（CSP 见 §1.3） | `wit/views.wit:68-81`；`crates/ide-plugin/src/runtime.rs:2055`；`src-tauri/src/plugin_web.rs:44-46` |
| 插件内容编辑器页 | `host::open_content_tab` / 页面 `op:"open-content-tab"` | 代码给的文本 | ≤1 Mi UTF-16；≤16 个动作、至多 1 个 Save；可只读；Markdown 有预览布局 | `plugins/README.md:2545-2593`；`wit/views.wit:129-146` |
| 只读日志页 | `host::open_log_tab` / 页面 `op:"open-log-tab"` | 宿主运行一个 `process:<program>` 的流 | 必须 `process:` 授权；保留 5000 行 / 1 Mi；代码打开的 tab 不支持远程项目 | `plugins/README.md:2309-2373` |

编辑器页之外还有三段「非窗体」的接入点：**输出通道**（`output.append`，每插件 32 通道、1 MiB 环）、**状态栏**（文本 ≤128 B、tooltip ≤1 KiB，1.14 起可带 ≤64 项菜单）、**通知**（≤3 个命令按钮）。见 `wit/host.wit:14-121,235-257`；`plugins/README.md:3291-3299`。

### 1.2 「视图是数据」的边界（表格是重点）

| 能力 | 支持情况 | 证据 |
| --- | --- | --- |
| 表格虚拟化/分页 | 有。插件报 `total-rows` + `first-row` + 当前行，宿主按可见区要行，`viewport` 触发补行 | `wit/views.wit:58-66,90-94`；`src/features/plugins/views/PluginView.tsx:445-500` |
| 表格排序 | 有，但由插件排：`Body::sortable_table`（走 `other` kind `table`），点表头发 `sort` 事件 | `plugins/README.md:1933-1938`；`PluginView.tsx:516-520` |
| 单元格编辑 / 插删行 | 有：`editable` + `cell`/`insert-row`/`delete-rows` 事件；行右键自带插删菜单 | `wit/views.wit:96-116`；`PluginView.tsx:493-560` |
| 行选择/激活 | 有：`table-select`/`table-activate`（`other` 扩展，带绝对行号）；Ctrl 多选只在本地高亮，事件仍只报一行 | `plugins/README.md:1946-1956`；`PluginView.tsx:539-551` |
| 列筛选 / 列宽 / 合并单元格 / 富文本单元格 | 无。单元格是字符串（≤64 KiB），行高固定 24 px，单元格单行 | `runtime.rs:2051,2054,2336-2345`；`PluginView.tsx:63,576-587` |
| 一次渲染行数 | ≤2000 行（超出截断），列 ≤1000 | `runtime.rs:2051,2245-2260` |
| 工具栏 | ≤32 个按钮（`id/label/icon/enabled`），无下拉、无分组、无 primary 样式 | `runtime.rs:2352-2365`；`wit/views.wit:13-19` |
| 状态行 | 单行纯文本，≤1024 B | `runtime.rs:2365` |
| 表单项 | 文本、多行、复选、单选（固定 options），另有 `label`/`readonly-text`（1.3）；无分组标题、无逐字段提示 | `wit/views.wit:42-56`；`runtime.rs:2292-2320` |
| 树/列表 | 扁平列表 + `parent`，惰性展开；`detail` 灰字；**没有列**（不能做三栏树表） | `wit/views.wit:21-40`；`plugins/README.md:1929-1932` |
| HTML 主体 | 用于声明式主体表达不了的 UI；页面自己保状态，重新渲染会重载 frame | `plugins/README.md:2033-2037`；`docs/zh/plugins/guides/views.mdx:195` |

### 1.3 沙箱与主题（决定「能不能复刻观感」）

- 网页视图 CSP：`script-src openidea-plugin: 'unsafe-inline' 'wasm-unsafe-eval'`，`img/font/media` 允许 `data:`/`blob:`，`connect-src` 只允许自身 scheme —— 可以带 Vite 产物、WebAssembly、字体；不能联网（`src-tauri/src/plugin_web.rs:23-28`）。
- HTML 主体 CSP：`script-src 'unsafe-inline'`（**无 `wasm-unsafe-eval`**），`img-src data:`（**无 blob**）——所以 heavy 前端（wasm 版 SQL 解析/图渲染）必须走「网页视图」而不是 `Body::Html`（`src-tauri/src/plugin_web.rs:44-46`；`src/features/plugins/views/frame.ts:19-21`）。
- 主题：frame 根带 `data-theme="light|dark"` 与 20 个 `--openidea-*` 颜色变量、3 个字体变量，随主题热更不重载；映射表在 `src/features/plugins/views/frame.ts:36-56`（`--openidea-bg` ← `--bg-island` 等），与 README 表一致（`plugins/README.md:2043-2051`）。
- 页面里没有 IDE 图标库、没有 IDE 组件、没有 IDE 字体文件；`--openidea-font-mono` 可拿到编辑器等宽字体族与字号（`frame.ts:57-61`）。
- 页面拿不到 IDE 语言设置：frame 不注入 `locale`，双语页面须由插件 `host::locale()` 再 `post_view_message` 下发（`src/features/plugins/views/WebView.tsx:300-360` 仅推 theme/document）。
- 页面里 `navigator.clipboard` 仅当 `clipboard` 授权被批准时可用（`WebView.tsx:291`）。

### 1.4 编辑器侧接入点

| 接入点 | 语义 | 上限 | 证据 |
| --- | --- | --- | --- |
| `providers.line-markers` | 每文档返回 `{line, icon, tooltip, target}`，点击跳转 | 编辑路径 50 ms 软 / 500 ms 硬 | `wit/providers.wit:62-83`；`plugins/README.md:1570-1572` |
| `language-features.gutter-actions` | 行旁按钮，点击跑本插件命令（带 argument） | 同上 | `wit/language-features.wit:79-87`；`plugins/README.md:1715` |
| `documentIndexer` 的 `markers` | 宿主自持跨文件图，图标点击去定义；多目标时宿主弹选择器 | 每文档 4096 定义/引用、256 alias | `wit/indexing.wit:22-62`；`plugins/README.md:1523-1538` |
| `inlay-hints` provider | 可见区内联标签 | ≤10 000/次、label ≤256 B、tooltip ≤1 KiB | `wit/inlay.wit:19-38`；`plugins/README.md:1690-1694` |
| `decorations` | 编辑器标注 13 种 style + after 文本 + hover；项目树徽标/备注/图标 | 每文件 10 000 项、每插件 50 个 set、元数据 4 MiB | `wit/decorations.wit:7-45`；`docs/zh/plugins/guides/views.mdx:383-393` |
| `code-lenses` / `hover` / `hover-actions` | 行上方文字动作、Markdown 悬停、行首悬停动作 | ≤10 000/列表；hover ≤64 KiB | `wit/language-features.wit:48-54`；`plugins/README.md:1622-1625,1716` |

### 1.5 右键动作与快捷键的挂点映射（我方 9 个 Action）

宿主只认 10 个菜单位置，**没有 Database 视图/表节点的右键菜单**（`crates/ide-host/src/plugins/ui_points.rs:92-105`）：

| 我方 Action（`plugin.xml:598-697`；`database-optional.xml:84`） | OpenIDEA 落点 | 备注 |
| --- | --- | --- |
| `ConvertToSql`（源码→SQL） | `editor.context` | 现成可做 |
| `ConvertSqlToJava`（选中 SQL→Java） | `editor.context` | 现成可做；`editor.transform_selections` 可保留多光标语义 |
| `OpenSqlPreview`（打开独立工作台） | `tools` 菜单 + 命令 | 同时给 `mainToolbar`/`editor.title` 图标入口 |
| `GenerateCrudSqlFromEntity`（实体右键） | `editor.context` + `projectView.context` | 现成可做 |
| `ExplainSelectedTable` / `CountSelectedTable` / `SampleSelectedTable` | `editor.context` 三条命令（可用 `submenu = "SQL 模板"` 分组，1.3） | 子菜单是插件自有分组，最多一条可见项时会退化成普通项（`plugins/README.md:188-201`） |
| `Feedback`（错误上报） | `tools` 菜单或帮助类命令 + `host::notify` 入口 | `HelpMenu` 无对应挂点；也可挂到「问题」右键（`problems.context`） |
| `GenerateCrudSqlFromDbTable`（Database 表节点右键） | **无挂点** | 需上游加 database 视图菜单贡献点；短期只能要求用户先选中表名再走编辑器命令 |
| 快捷键（`Alt+Shift+Q/J/P`） | `[[contributes.keymaps]]` | 不能抢占 IDE 已有键；用户在 Keymap 页可改；插件不能读当前键位 |

---

## 2. A. 预览工作台 10 项逐项落地

### 2.1 总体形态选择

我方现状：一个 `DialogWrapper` 弹窗（`ui/SqlPreviewDialog.kt:322-336`），内容区是「SQL / JOIN 图 / 列信息」标签页 + 左侧参数栏 + 底部动作行（`ui/SqlPreviewPanel.kt:146`；`ui/SqlPreviewPanelResultView.kt:520-537,570`）。

OpenIDEA 没有「插件自定义内容的模态框」。可选承载只有三种，推荐组合：

1. **主工作台**：`location = "bottom"` 的工具窗口 + `web = "web/index.html"`（网页视图）。理由：需要自绘工具栏/树表混排/折叠、需要 wasm（格式化、图），HTML 主体 CSP 不允许 wasm，声明式 body 画不出参数栏 + 状态徽标 + 折叠菜单。
2. **单条 SQL 的「钉住」视图**：`host::open_content_tab`（`language: "sql"`、只读），借宿主的 SQL 语法高亮、查找、选中复制；适合「复制前看一眼」。
3. **对比**：`views.open_diff` / `open_file_diff`（每边 ≤8 MiB），替代现有 Diff 动作。

工作台与插件之间的协议直接照抄 Regex Tester：页面 `openidea.postMessage(JSON)` → `ViewEvent::Message` → `handle` → `host::post_view_message` 回包（`plugins/official/regex-tester/src/lib.rs:164-209`）。注意该模式的两条经验：页面先发 `ready` 再收初始状态；插件不要用 `render` 重刷页面（会重载 frame、丢页面状态）（`regex-tester/src/lib.rs:183-187`；`docs/zh/plugins/guides/views.mdx:195`）。

### 2.2 十项落地方案

| # | 项 | 形态 | 需要额外能力 | 体验差异（相对 IntelliJ 弹窗/面板） |
| --- | --- | --- | --- | --- |
| 1 | SQL 文本（只读、语法高亮、格式化/折行/竖排/紧凑 4 种显示模式） | 网页视图内嵌自绘编辑器（推荐）；或 `open_content_tab` + `language:"sql"` 借宿主高亮 | 无（格式化逻辑随 Rust 代码带入） | 弹窗改为停靠底部窗口，不能浮在代码上；web 内没有 IDE 的查找栏/代码折叠/双向选区，除非另开 content tab。好处是显示模式（`SqlPreviewPanel.kt:645` 的 4 档）可完全自绘 |
| 2 | JSON 计划树（PG `EXPLAIN (FORMAT JSON)` 可折叠树） | 声明式 `Body::Tree`（惰性 `Expand`）可做最小版；完整版用网页视图 | 树可用；无列对齐/无配色/图标仅限图标名 | 树没有多列、没有每节点徽标配色；`after/detail` 只有一段灰字。见 `ExplainPlanResultView.kt:36-44,180-200` 的现有形态（表/树分派 + 索引建议条 + 跨次对比条），这些「条」在声明式树里无处安放，只能进 HTML |
| 3 | 参数表（132~200 个参数行、条件勾选、类型选择、历史/预设、逐行警告） | 网页视图 **必须**（表单 body 不够） | 无；`form` 体只作空态/极简参数页 | `form` 无分组标题、无逐字段提示（`runtime.rs:2292-2320` 只有 key/label/value），条件勾选可映射 `checkbox`，但「未解析参数为空」「类型下拉 + 值输入」混排在 form 里会退化成扁平长列表。HTML 可 1:1，代价是要自己做焦点保持与键盘可达性 |
| 4 | 底部动作行（执行/复制/复制并关闭[主行动]/关闭/更多） | `view.toolbar` 按钮（≤32）+ 一个「更多」按钮 → `host::pick` 原生列表 | 无 | 没有 primary 按钮样式（只有 content-tab 的 action 有 `primary`）；没有按钮分组/下拉，需用「更多」二次弹原生选择器，多一次点击 |
| 5 | 复制（复制 SQL / 复制可执行 SQL / 复制为 AI 上下文） | 插件代码 `host::copy_to_clipboard`，或页面 `navigator.clipboard` | `clipboard`（medium，安装时列权限、可撤销） | 首次安装多一条剪贴板权限；被拒则退化为页面选区手动复制（文本可选中） |
| 6 | 导出（Markdown/HTML/SQL 分享、PNG/SVG 图、诊断报告） | 页面 `openidea.saveFile({name,data,filters})`（IDE 保存对话框）；插件侧 `pick_file` + `project.write`/`fs.write` | 页面：无；插件侧写盘需 `project.write` 或 `fs.write:<path>`（high） | 导出对话框是宿主的，路径/过滤器可用；不能像 Swing 那样「导出后自动打开目录」，需另给 `open_location`。Diff 改走宿主 diff 视图，失去面板内嵌并排对比（`views.open-diff` 每次 8 MiB/边） |
| 7 | 方言切换（15 方言 + 自动推断 + 手动） | 设置页 enum（全局/项目两级）+ 工作台内 `choice` 字段或自绘下拉 | 无 | 宿主设置天然支持「项目覆盖全局」，比我们「应用级 118 字段 + 1 个项目页」更细；但没有「同页里点一下就重算」的联动，需靠 `settings-changed` 或 web 内直接改状态 |
| 8 | EXPLAIN 结果（TABLE 形态计划表） | 声明式 `Body::Table`（可排序、可选行、行右键菜单） | 无 | 纯文本单元格、固定 24 px 行高、无逐格配色/无缩进树（EXPLAIN 的层级只能在 JSON 树视图里表达）；「回到原生控制台」入口无对应物（见 §6 不可行项） |
| 9 | 性能建议（多条、带严重级） | 网页视图列表（推荐）或 `Body::Message`（纯文本）/ 状态行 | 无 | 声明式里没有「带图标的多行条目」：`tree` 每项可带图标但只有图标名（包内 SVG 是否可用于 tree/list item 图标未在文档中声明，**待验证（推断：只支持图标名与 data: 位图）**，`docs/zh/plugins/guides/views.mdx:129`）；纯文本会丢严重级视觉层级 |
| 10 | 清空（清空结果并回到空态） | toolbar 按钮 → `host::refresh_view` → `Body::Message` 空态 | 无 | 与现实现等价（`SqlPreviewPanelSqlView.kt:748-751` 的清空段在 HTML 里同样是重置状态）；差别是空态文案由插件提供，宿主不参与排版 |

补充（这 10 项之外的既有面板元素）：

- 第 2 项的口径：本表按「EXPLAIN FORMAT JSON 的可折叠计划树」理解（`ui/ExplainJsonPlanTree.kt`、`ui/ExplainPlanResultView.kt:180-200`）。若指「参数批量导入对话框里的 JSON 编辑器」（`ui/ParamImportDialog.kt:39-48,102-108`），结论不变：声明式没有代码编辑器体，只能走网页视图（自带高亮）或 `open_content_tab(language:"json")` 借宿主高亮。
- JOIN 关系图：只能网页视图自绘（canvas/SVG），原生 Diagrams 组件不可嵌入；`--openidea-change-*` 等颜色可拿，图形库需自带（`frame.ts:36-56`）。
- 列信息标签页：`Body::Table` 即可（`ResultView.kt:570`），或与 SQL 页做 tab。
- 「执行 SQL / 一键 EXPLAIN」：不是 UI 问题，受 `database.wit` 无 query 限制（见 04 册），本册不重复。

### 2.3 交互时序与预算（决定手感）

| 事项 | 事实 | 影响 |
| --- | --- | --- |
| 视图事件预算 | `render`/`handle` 走命令预算 5 s（`crates/ide-plugin/src/types.rs:486`），不是编辑路径 50 ms | 一次参数改动可做 100 ms 级重算，不必像 inlay 那样抠 |
| 同实例串行 | 一个插件一个 actor 线程，命令/视图/事件/提供者串行；长任务会把 provider 请求挤到 50 ms 之外被丢弃 | 工作台里点「执行/重算」时，同一插件的 gutter/inlay 请求可能整批超时；重活要拆到 `tasks` + 自己的缓存 |
| 页面消息 | 单条 ≤8 MiB；`post-message` 双向均受此限 | 别把整份 SQL + 全量参数一次塞；做增量/分帧 |
| 页面重载 | `refresh` 会重载 frame，页面状态丢失 | 状态放插件内存或 `storage`，页面只做呈现；`ready` 后由插件回灌 |
| 首屏 | 工具窗口首次选中才加载页面；隐藏时是否销毁 frame：**待验证（推断：隐藏即卸载，再次显示走 `ready`）** | 需把「当前预览内容」存在插件侧，重新显示时重放 |

---

## 3. B. gutter / inlay 的体验上限

### 3.1 自定义 SVG 能否做到「与 MPJ 同款」

能，但有四条硬边界：

1. **尺寸**：图标在 14 px 的 gutter 图标列里绘制（run 图标 12 px），`gutterIconMarkup()` 固定调用（`src/ui/icons.tsx:980-982`）。我方 `sqlGutter.svg` 是 16×16 viewBox、1.8 px 描边（`src/main/resources/icons/sqlGutter.svg:1-5`），按 14/16 缩放后描边约 1.57 px，视觉会略细；建议直接把画布做成 14×14 或把描边调到 2.0 补偿。
2. **消毒**：只允许白名单元素（svg/g/path/circle/ellipse/line/polyline/polygon/rect/defs/linearGradient/radialGradient/stop/clipPath/mask/use/symbol/pattern/text/tspan + 一组 fe* 滤镜）与白名单属性；`<title>/<desc>/<metadata>` 整体丢弃；`href` 只能 `#id`；`url()` 只能本地片段；禁 DTD/实体/CDATA（`crates/ide-host/src/plugins/icons.rs:213-246,249-339,502-525`）。我方现有图标只用 circle/path/line/stroke/fill，全部合规。
3. **配色**：
   - 声明 `markerIcons.icon` + `iconDark` 时，亮/暗两版按主题切换（`plugins/README.md:1503-1518`；`icon_dark` 见 `crates/ide-host/src/plugins/ui_points.rs:948-986`）。
   - 若亮版所有颜色恰为 `#6c707e`、暗版恰为 `#ced0d6`，会被判定为「单色」并替换成 `currentColor`，完全跟随主题（`src/ui/icons.tsx:812-824`）。
   - 否则按彩色保留（我方 `#F4EEFF` 底 + `#4FB477` 绿描边属于此类），**颜色固定**，但可用 `iconDark` 提供暗色版——与 MPJ「亮暗双份 SVG」的做法一致，能做出同款观感。
   - 注意 `iconDark` 只存在于 `markerIcons` 与 `fileIcons`；工具窗口的 `views.icon` 只有一个字段、没有暗色变体（`crates/ide-host/src/plugins/ui_points.rs:621-646`），工具窗口图标建议用单色 + `currentColor` 自绘。
   - 用 `fill="currentColor"` 的自绘图标同样能吃到主题色（`build()` 的 mono 判定不命中也会原样保留）。
4. **体积**：markerIcons 每个 SVG ≤64 KiB；插件主图标 ≤16 KiB（`icons.rs:26-31`）。我方图标 1~2 KiB，无压力。

### 3.2 两个图标会不会「撞成一样」

- **不同图标本身不会撞**：图标按 `(pluginId, 图标串)` 缓存与注册，不同 SVG 路径 → 不同图标资源（`src/features/plugins/markerIcons.ts:31-48,70-83`；`src/features/plugins/pluginIcon.ts:48-70`）。
- **真正的撞法**是「图标串写错/未声明」：`resolveMarkerIcon` 拿不到就在前端按名字查；`markerIcon()` 对未知名字统一回落到 `gutter-navigate`（`src/features/plugins/lineMarkerGutter.ts:22-28`；`markerIcons.ts:70-73`）。两个 provider 都写错路径 → 两个箭头长得一模一样，且没有任何告警。发布前必须用 `openidea-plugin check` 覆盖 markerIcons 声明并回归截图。
- **同一行两个图标**：共享一条图标区，按来源顺序左→右排：line-markers（order 60）→ gutter-actions（65）→ 索引 markers（70），间隔 2 px（`src/editor/core/cm/iconArea.ts:64-156`；`languageVisuals.ts:106-112`；`indexedMarkers.ts:107`）。区域宽度 = 全文任意行最多图标数，所以**某一行的双图标会把所有行都撑宽**；这跟 IDEA「每种 provider 一条独立 gutter 列」不同，「第几列 = 哪类图标」的肌肉记忆失效。我方 F20（iBatis DAO 行「预览 + 跳转」双标记）会正好命中这条差异。
- 同一行同图标的多目标：合并成一个按钮，点击时若目标 >1 就弹选择器（`lineMarkerGutter.ts:107-117`）。

### 3.3 多目标跳转的选择器长什么样

`navigationButton()` 在目标 >1 时打开 `openPopup({kind:"list", title:"Choose Mapping Target"})`，条目文字是符号名（或代码行）、副标题是容器名、右侧是 `文件:行`，带文件图标，支持快速搜索（`src/features/plugins/navigationButton.ts:10-23`；`src/editor/lsp/navigation.ts:191-215`）。对 document-indexer 的 markers，宿主自己弹同一个选择器（`plugins/README.md:1519-1521`）。

限制：选择器只解决「同一行的多个 target」；跨行、跨文件的候选（例如「这个 Java 方法对应 3 个 XML 语句」）需要我们自己建模成一个 marker 的多 target，不能给用户一个「预览候选列表」的中间层。

### 3.4 inlay hints：能显示什么、不能显示什么

| 问题 | 答案 | 证据 |
| --- | --- | --- |
| 能不能显示「带背景色的参数名」 | 宿主**总是**画成灰底圆角 chip（`background: color-mix(--fg-subtle 12%)`、1px 内边距、字号 `1em-1px`、字体用 UI 字体而非等宽）；插件只能给文本，不能设色/设底色 | `src/editor/lsp/theme.ts:545-557` |
| 有没有按 kind 区分样式 | 只有 `cm-lsp-inlay-{kind}` 类名，宿主未定义任何按 kind 的样式；kind 的唯一实际作用是决定归到哪个 Inlay Hints 开关（type/parameter/other） | `src/editor/lsp/inlay.ts:70-72`；`wit/inlay.wit:24-26` |
| 长度 | label 截断 256 B、tooltip 1 KiB、每次 ≤10 000 条 | `plugins/README.md:1694` |
| 能不能只对我方预览编辑器生效 | 不能。provider 按「语言」声明（`inlay-hints` kind + languages），对语言下所有文档调用；没有「只对某编辑器」的注册面。我方现在靠「临时文件名 + userData 双锚点」隔离（`ui/inlay/PreviewInsertColumnInlayProvider.kt:28-53`），在 OpenIDEA 里没有等价的 userData 锚点 | `plugins/README.md:1479-1486`；`wit/inlay.wit:3-8` |
| 能不能点击跳转 | 宿主支持「label 分段 + 每段 location」，但**WIT 里没有分段字段**（`docs/zh` 与 README 均只描述 label/tooltip）；1.15 只能纯文本 + tooltip。**待验证（推断：分段跳转是宿主 LSP 内部能力，未开放给插件）** | `wit/inlay.wit:19-38` |
| 设置改动后能否立刻刷新 | 不能：只在编辑/滚动后重取；无插件侧失效 API | `plugins/README.md:1690-1694` |

结论：我方「插入列名提示」如果一定要出现在 SQL 预览里，最省事的是**在 web 工作台里自己画列名标注**（完全可控），而不是走 inlay-hints。

### 3.5 decorations 能补什么

- 适合：行尾灰色归属文本（`after`）、悬停说明（1.15 是纯文本，1.16 才有 Markdown hover）、整行/片段染色（13 种 style）。Blame 示例就是 2000 行 `after` 标注 + hover（`plugins/official/blame-annotations/src/lib.rs:13-35`）。
- 不适合：交互式嵌入（不可放按钮）、精确色值（只有主题语义 style，无自定义色）、超过 10 000 项/文件。
- 项目树侧：`set-file`（≤2 字符徽标）、`set-file-note`（≤200 字符单行）、`set-file-icon`（需先声明 `fileIcons`）可用于「这个 mapper XML 有 N 条未预览语句」之类的角标。

---

## 4. C. 设置 118 字段怎么落

### 4.1 能力清单

| 需求 | 支持 | 说明与证据 |
| --- | --- | --- |
| 单页 schema | 有 | `[contributes.settings] schema = "…json"`（`plugins/README.md:2619-2640`） |
| 多页（我方 8 页） | 有，≤32 页 | `pages[]`，每页独立 schema，页可挂 ≤32 个 action 按钮（`plugins/README.md:2642-2716`） |
| 字段总数 | ≤200（我方 118） | `crates/ide-host/src/plugins/ui_points.rs:109,540-546` |
| 分组标题 | 有：`"group": "Provider"` 按 schema 顺序成组 | `plugins/README.md:2672` |
| 条件显示（when / 依赖其他字段） | **不支持**：「Custom web settings pages and conditional fields are unsupported」 | `plugins/README.md:2709-2711` |
| 长说明 | 有：`description` ≤4096 B（参与翻译） | `ui_points.rs:410-411,524` |
| 示例 | 有：`example: {text ≤300 B, style}`，style 只能是宿主 6 种（error/warning/weak/typo/info/muted）；**示例文本不参与翻译** | `ui_points.rs:396-399,500-519`；`src/features/plugins/pluginText.ts:96-104` |
| 枚举 / 多行文本 | 有：`enum` → 下拉；`multiline: true` → 多行 | `plugins/README.md:2636-2640`；`settings.tsx:78-96` |
| 数字范围/步进 | 无 `min/max/step` 关键字（只校验类型） | `ui_points.rs:427-445` |
| 字符串列表 / 对象表格 | 有：`array` + `items`（string 或 object），行内编辑、可增删排序；≤1000 项、**不支持嵌套集合** | `plugins/README.md:2672-2699` |
| 项目级覆盖 | 有：每个键都有 Global / This project 两层，`settings.get` 项目优先 | `plugins/README.md:2637-2639`；`wit/views.wit:197-202` |
| 自定义设置页 UI | 不支持；官方建议「用插件自己的视图」 | `plugins/README.md:2709-2711` |
| 热生效 | `settings.get` 每次调用读当前值；`settings-changed` 事件（API 1.4，JSON 数组为变更键） | `wit/events.wit:32-41`；`docs/zh/plugins/lifecycle.mdx:105` |

### 4.2 118 字段的落地映射

| 我方页面 | OpenIDEA 落法 | 注意 |
| --- | --- | --- |
| 界面与操作 `UiActionsConfigurable` | 1 页 + `group` 分组 | 其中「gutter 总开关」类字段无法联动刷新图标（见 §4.3） |
| 快捷键 `ShortcutsConfigurable`（5 个动作内嵌录制） | **不能**做成设置字段：宿主键位由 `[[contributes.keymaps]]` 声明、用户在 Keymap 页改；插件不能读写键位 | 我方「页内录制快捷键」这一交互必须删掉，改为「在 Keymap 里搜索插件命令」的说明文案 |
| SQL 预览 `SqlPreviewConfigurable` | 1~2 页（方言 enum 15 项 + 参数/逻辑删除/多租户/数据权限等布尔与枚举） | 方言「自动推断」与「手动指定」二选一属于条件显示，落不了；用两个独立字段 + 说明文案，插件侧自行忽略无效字段 |
| 表名映射 `TableMappingConfigurable` | 1 页 + 一个 `array`/`items:object` 表格（`pattern/replacement/enabled`） | 正好对应 `PluginSettings.kt:1016-1020` 的 `TableNameRule`（3 列标量，无嵌套） |
| SQL 日志 `SqlLogConfigurable` | 1 页 | 字段都能落，但功能本体不可行（§6） |
| MCP `McpConfigurable`（含工具级禁用列表） | 1 页 + `array: string`（`mcpDisabledTools`，`PluginSettings.kt:678`） | 字符串数组支持增删与排序 |
| 关于 `AboutConfigurable`（缓存/更新/构建号/导入导出） | 1 页 + ≤32 个 page action（导入/导出/检查更新） | page action 会先 apply 本页设置再跑命令（`plugins/README.md:2702-2708`） |
| 项目页 `ProjectPluginSettingsConfigurable` | 不单独建页：凡是该页字段，在页面里说明「项目级覆盖」即可（宿主对每个键都提供两层） | 语义从「项目专属页」变为「任意字段可项目覆盖」，属于增强但也意味着全局默认值易被误改；待验证（推断：页内没有「仅本页项目层」的视觉提示） |
| `dynamicTableNameRules`（列表，`PluginSettings.kt:588`）、`dialectTipSuppressedProjects`（字符串列表，`:762`） | `array` + `items`，前者 4 列表格、后者字符串列表 | 若未来出现嵌套结构，无解（不支持 nested collection） |

### 4.3 「改完要重开文件」这类老问题

| 老问题形态 | OpenIDEA 对应机制 | 结论 |
| --- | --- | --- |
| 面板/工作台里的开关（格式化、显示模式、性能建议开关） | 插件自己读 `setting` 重建视图，或页面内即时生效 | 现成可做 |
| 影响 gutter 图标的开关（`showGutterIcon` 等） | 宿主只在文档版本变化/编辑后（400 ms 去抖）重新向 provider 要 markers（`lineMarkerGutter.ts:18,119-152`），且**没有**插件侧 invalidate 接口 | 改完需用户编辑一次或重开文件；诚实文案 + 通知，或干脆删掉这类开关、改为「在 IDE 设置里关整个 gutter 图标」 |
| 影响 inlay 的开关 | 同上，只在编辑/滚动后重取 | 同上 |
| 影响解析/缓存的开关（方言、表名映射、逻辑删除） | 插件在 `settings-changed` 里清自己的缓存；下一次预览/渲染即生效 | 现成可做（前提是我方分析逻辑本身可增量重算） |
| 设置页内的「长说明/示例」 | `description` + `example` | 能放，示例 ≤300 B，不能放代码块/多段 |
| 设置页内的条件字段 | 不支持 | 三种变通：①拆成「基础/高级」两页；②把互斥字段都展示、用描述写清「仅当 X 时生效」；③把这块配置搬进插件自己的视图（`Body::Form` 可动态重建），但那时它不在 Settings 里、不能项目覆盖 |

---

## 5. D. 双语与术语

### 5.1 机制

- 文件：包内 `locales/<tag>.json`，一个语言一个文件，扁平 `{"英文源文": "译文"}`，无需清单声明（`plugins/README.md:2597-2613`）。
- 选择：精确 tag → 语言 → 同语言变体（`zh-CN` → `zh` → `zh-TW`）（`src/features/plugins/pluginText.ts:21-29`）。
- 应用面：清单声明的 `commands.title/category`、`views.title`、`customEditors.title`、`statusBar.text/tooltip`、`settings`（页标题/属性 title/description/group/表格列/枚举项/页动作）、`fileTemplates.name/prompt`、`themes.name`、`iconThemes.label`、`secrets.label/description`、`markerIcons.tooltip`（`pluginText.ts:76-104`）。
- 视图数据面：工具栏按钮、树/列表 label 与 detail、表单 label、表列名、message、状态行（`pluginText.ts:135-165`）。
- 未命中回落原文；目录只作用于本插件；`openidea-plugin check` 会拒绝非「字符串→字符串」的目录（`plugins/README.md:2608-2613`）。

### 5.2 与「1557 键 ×2」的等价性评估

我方 bundle 实测：`MpSqlVisualizerBundle.properties` 中 `key=` 形态 1560 行、含 `{0}`~`{n}` 占位符的 318 行（`grep -cE "^[A-Za-z0-9_.-]+="` / `grep -c "{[0-9]}"`）。按 1557 键口径（08 册）差额 3 行，疑为注释行/续行口径差异，**待核**。

| 类别 | 数量级 | 能否用 `locales/<tag>.json` | 处置 |
| --- | --- | --- | --- |
| 静态整句（按钮、标签、提示、说明） | 大部分 | 能，且清单/设置/视图数据自动套用 | 直接搬运 |
| 含占位符的模板（`{0}` 替换） | 318 条 | **不能**：目录是精确文本映射，没有参数替换 | 改由插件代码用「英文源文 → 译文表」+ 简单 `{}` 替换渲染，语言取 `host::locale()`；渲染结果不再命中目录，原样显示 |
| 拼接串（前后缀 + 数字/名词） | 少量 | 不能（且整句翻译要求每种变体一条） | 改成整句模板（参考 i18n 文档「不要拼接」规则，`docs/zh/contribute/guidelines/i18n.mdx:57-61`） |
| 富文本（3 处 `<html>/<b>/<br>`） | 3 条 | 能（按整串映射），但 `<html>` 在我方 Swing 是渲染标签，在 web 工作台里要换成 HTML 片段 | 建议按「显示面」拆两套：设置说明（纯文本 + `description`）与 web 内 HTML |
| 通知/错误正文（代码拼出） | 多 | 不能（宿主只翻清单与视图数据） | 由插件按 `host::locale()` 自己选词 |

**关键约束**：目录是「英文源文 → 译文」的**全局扁平表**，不是键值表。后果有两个：

1. 我方两个不同 key 若英文完全相同、中文要求不同，做不到（只能取一条）；搬运前要扫一遍英文重复项。
2. 改英文措辞即丢译文（`docs/zh/contribute/guidelines/i18n.mdx:57`）；而我方 bundle 的 key 是稳定的，改文案不影响 key——移植后要接受这个回归。

术语一致性：目录以英文源文为键，天然保证「同一英文 → 同一译法」；但反过来，同一中文概念若写成多个英文词（例如 `Parameter`/`Param`），会出现多套译法。建议移植时先做一份「英文术语表」（方言、参数、预览、执行计划、回滚、写安全…），再生成目录。

### 5.3 根本不能翻译的面

| 面 | 是否能翻译 | 证据 |
| --- | --- | --- |
| 插件名 `name`、`description`、`change-notes`、`categories` | 不能（不在 `TEXT_KEYS` 中） | `pluginText.ts:76-90` |
| 市场页描述、发行说明 | 不能（同上；插件卡片用的是清单 `description`，不经过目录。**推断**，`pluginText.ts` 无该字段） | 同上；`plugins/README.md:2595-2617` |
| `runDetectors.name` | 不能 | `pluginText.ts:76-90` |
| `editorPanels`（只有 id/web/width/when，无标题字段） | — | `plugins/README.md:159` |
| 通知正文、错误正文、`output.append` 文本 | 不能（代码生成，须自己按 `locale()` 翻） | `plugins/README.md:2615-2617` |
| `Body::Html` 内容、表格单元格数据、`example.text` | 不能（HTML/单元格原样；示例不在翻译键内） | `pluginText.ts:136-158,96-104` |
| 树/列表的 `detail` | 能（一起翻） | `pluginText.ts:145-151` |
| 设置项 title/description/group/页标题/动作标题 | 能 | `pluginText.ts:97-102` |
| 状态栏文本（清单声明与 `set_status_item`） | 能（`plugins/README.md:2605-2606`） | 同上 |

结论：**1557 键里约 8 成可以 1:1 搬进目录**；318 条带参模板 + 代码拼出的通知/错误需要「插件内置词表 + `host::locale()`」这套并行机制。插件名/描述/发行说明在 OpenIDEA 上只能单语（这是市场层的缺口，非本插件能补）。

---

## 6. E. 最大体验降级清单（按用户感知排序）

| 序 | 降级 | 用户感知 | 根因（证据） | 缓解 |
| --- | --- | --- | --- | --- |
| 1 | 预览从「浮在代码上的弹窗」变成「底部停靠窗口/编辑器页」 | 高：要移动视线、不能随手 Esc 关、看不到被压住的代码 | 插件无自定义模态框；只有 tool window / content tab（`plugins/README.md:1904-1932,2545-2593`） | ①「复制并关闭」保留为一键动作；②`reveal` 后把焦点还给编辑器（`views.reveal` 语义，`wit/views.wit:172-174`）；③提供 `editor.title` 或 `mainToolbar` 入口，减少鼠标找窗口 |
| 2 | SQL 日志面板整体不可用 | 高：这是主功能面（控制台捕获 → 还原 → SQL→Java） | 插件拿不到运行/调试控制台输出；`set-console-rules` 明确「插件永远看不到输出」（`wit/host.wit:122-132`） | 走 `[[contributes.javaAgents]]` 在 JVM 内采集，经 `ipc:`（命名管道客户端）或写插件私有目录回传；只覆盖 Application/Spring Boot/JAR 运行配置，控制台文本通道不可恢复。详见 05 册 |
| 3 | 触发面「时好时坏」的观感：gutter 图标依赖 50 ms 内的文本解析 | 高：图标闪烁/缺失会被读成坏掉 | 编辑路径 50 ms 软预算、500 ms 硬中断；同一实例串行，长任务会把 provider 挤掉（`plugins/README.md:1570-1578`；`wit/providers.wit:3-6`） | ①解析结果按 (path, version) 缓存；②把重活挪到 documentIndexer 的后台批次（`wit/indexing.wit:1-8`）；③图标按「粗判 + 缓存」出，宁少不错 |
| 4 | 设置改动不刷新 gutter/inlay | 中高：关掉开关图标还在，用户以为设置没保存 | 无插件侧失效 API；markers 只在文档版本变化时重取（`lineMarkerGutter.ts:18,119-152`） | 删掉这类开关（交给 IDE 的 Inlay/Gutter 设置）；或改完弹通知「下次打开文件生效」 |
| 5 | 参数区从「Swing 精细布局」退化为「web 页自绘」 | 中：视觉可 1:1，但键盘 Tab 序、焦点保持、屏幕阅读器要自己保证 | 声明式 form 无法表达分组/逐行警告/历史下拉（`runtime.rs:2292-2320`） | 用 web 页 + 自己实现 roving tabindex；把「参数历史/预设」下沉为插件侧数据，页面只渲染 |
| 6 | gutter 双图标挤在同一列，列位语义丢失 | 中：老用户「左边是预览、右边是跳转」的肌肉记忆失效，且任意一行双图标会撑宽全部行 | 共享 icon area，宽度取全文最大图标数（`iconArea.ts:64-156`） | 把「预览」做成 gutter-action（order 65）、「跳转」做成 line-marker（60），保证左右顺序稳定；tooltip 写清楚各自动作 |
| 7 | inlay 不能设色、不能按编辑器隔离、256 B 截断 | 中低：提示变灰 chip；若我方依赖彩色提示会丢信息 | `theme.ts:545-557`；`wit/inlay.wit:19-38`；provider 按语言注册（`plugins/README.md:1479-1486`） | 列名提示改在 web 工作台里画；若必须 inlay，控制在 256 B 内、用 tooltip 补细节 |
| 8 | 设置页不能条件显示、示例 ≤300 B、键位不可编程 | 中低：设置页会变长；「快捷键录制」交互要删 | `plugins/README.md:2709-2711`；`ui_points.rs:108-109,500-519` | 拆「基础/高级」两页；互斥项的说明写进 `description`；快捷键改为 Keymap 指引 |
| 9 | 表格/树的表现力上限 | 中低：EXPLAIN 树无多列、计划表无逐格配色、单元格不能换行 | `runtime.rs:2051-2055`；`PluginView.tsx:63,576-587` | EXPLAIN 走 web 页自绘（表格 + 树混排 + 严重级配色）；简单列表仍用声明式以换原生键盘/搜索 |
| 10 | 通知/状态栏减配 | 低：分组没了、状态栏只有文字 | 通知 ≤3 按钮、无 group；状态栏 text ≤128 B（`wit/host.wit:14-121`；`ui_points.rs:316-317`） | 标题前缀模拟分组（`SQL 日志: …`）；状态栏只放最短状态词（`MySQL · OK`），细节进 tooltip |
| 11 | 双语的 318 条模板与插件名/描述 | 低（对中文用户是可见缺口：市场卡片始终英文） | 目录无参数替换、不含插件元数据（`pluginText.ts:76-104`） | 自建词表 + `locale()`；市场描述接受单语，README 里提供中文说明链接 |
| 12 | 权限弹窗增多 | 低：剪贴板/写盘/process 都要授权，被拒后功能退化 | `clipboard`/`project.write`/`process:` 均在安装页列出（`plugins/README.md:347-372`） | 把权限按「可选」声明，功能缺失时给出可读降级提示，而不是报错 |

---

## 7. 未解决 / 需上游确认

1. **`views` 的条目图标**：tree/list item 的 `icon` 是否接受包内 SVG 路径（像 line-markers 那样）还是只接受 IDE 图标名与 `data:` 位图。文档只写「IDE 图标名、已注册的插件图标、或 data: URI 小图」（`docs/zh/plugins/guides/views.mdx:129`），源码裁剪为 64 B 字符串（`runtime.rs:2276-2305`）——**待验证（推断：包内 SVG 不可用于视图条目）**。若不可，严重级徽标要用 `data:` PNG 内联。
2. **HTML 主体与网页视图的 `worker-src`**：两者 CSP 都没有 `worker-src`，`default-src 'none'` 下 Web Worker 应被禁（`src-tauri/src/plugin_web.rs:23-28,44-46`）。若我方想用 worker 跑解析需实测确认。
3. **隐藏工具窗口时 frame 是否销毁**：文档只说明「切换 tab 保持挂载」（`plugins/README.md:1997-1999`），未说明整窗隐藏/窗口重开的行为——**待验证（推断：隐藏即卸载，重开走 `ready`）**。
4. **`file.settings` 能否定位到「本插件设置页」**：`file.settings` 接受页 id 参数（`src/features/settings/actions.ts:11-22`），但插件设置页的页 id 规则未见文档。若不能，工作台里的「设置」按钮只能 `ide.commands` 打开插件页根节点。
5. **插件侧强制刷新 editor provider（markers/inlay/lens）**：目前无接口。若上游补 `providers.refresh(path)` 一类函数，§6 第 4 条降级可消失；建议作为 WIT PR 提出。
6. **inlay 分段跳转**：宿主内部有「label 分段 + 每段 location」渲染（`src/editor/lsp/inlay.ts:74-107`），但 WIT 未暴露。若我方需要「提示点进去跳到列定义」，需上游加字段。
7. **1.16 的 `set-markdown`（Markdown hover）与 `frames`**：本机 1.15 不可用；若允许 `engines.openidea = "^1.16"` 并等 1.16 发布，decorations 的悬停可放 Markdown（`wit/decorations.wit:12-31`）、网页可嵌外部站点（`plugins/README.md:2179-2205`），对「列说明/文档预览」有加分。
8. **设置页 `example` 的翻译**：`localizeDecl` 只翻 properties 的 title/description/group（`pluginText.ts:96-104`），示例文本保持英文——若我方示例是给中文用户看的说明，需要把它写进 `description` 或接受单语。
9. **`views` 数量与 `customEditors` 数量的上限**：未见显式常量；插件清单校验是否另有上限**待核**（`crates/ide-host/src/plugins/points.rs` 未见）。
