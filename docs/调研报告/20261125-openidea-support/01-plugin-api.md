# 01 插件 API 全量测绘（openidea:plugin 1.15 / 1.16）

> 调研对象：`F:\code\Java\Github\other\openidea`（main @ a7b67347c，2026-11-25 11:22 拉取）。
> 引用路径除特别注明外均相对该仓根；行号以本次读取的源文件为准。
> 线上文档：`https://openidea.sh/zh/plugins/...`（经 `curl -x http://127.0.0.1:7890` 核对）。
> 结论档位沿用《00-README》：现成可做 / 变通可做 / 需上游 / 不可行。

本文只回答「插件 API 能表达什么、边界在哪」。官方 mybatis 插件实现细节见 02 分册，数据库/SQL/MCP 的业务边界见 04，运行控制台与 javaAgent 见 05，UI 面见 06。

## 0 结论摘要

插件 API 是一个「同步、文档整体传递、能力逐项授权」的 WASM 组件契约：一个 world 声明导入（宿主 interface）与导出（插件 interface），所有宿主能力都经 import 函数并逐个检查 capability。它不是 IntelliJ 平台式插件模型，没有 PSI/AST、没有类型解析、没有插件自定义原生 UI、没有后台常驻进程。

| 维度 | 结论 | 关键证据 |
| --- | --- | --- |
| 版本 | main 开发版 1.16.0；已发布快照 1.15.0；本机 0.1.51 随附 1.15.0 | `wit/plugin.wit:11`、`sdk/rust/openidea-plugin/tests/api/released/plugin.wit:1`、tag `v0.1.51` 内 `wit/plugin.wit:11` |
| 接口规模 | 30 个 `.wit` 文件、57 个 interface、22 个宿主侧 world（另有导出孪生 world） | `wit/*.wit` 实测计数 |
| 调用模型 | 1.x 全同步；同一实例内调用串行排队，永不并发 | `docs/zh/plugins/architecture.mdx:52-58`、`:88` |
| 时间预算 | 命令/事件/activate 5 s；开任务后 60 s（可续期）；编辑器 provider 50 ms 软 / 500 ms 硬；格式化 5 s | `docs/zh/plugins/architecture.mdx:73-82` |
| 内存/CPU | 256 MiB 线性内存；每次调用约 5e9 wasm 指令 fuel | `docs/zh/plugins/architecture.mdx:77,81` |
| 语义能力 | 无 AST/PSI/类型解析；只有符号索引（名字+种类+容器+位置）与正则文本搜索 | `workspace.wit:34-40,105`、`plugins/README.md:906-928` |
| SQL 执行 | `database` 只管理数据源与凭据、请求 IDE 自己连接，无任何查询/EXPLAIN 函数 | `database.wit:24-33` |
| 沙箱 | 无预打开目录、无环境变量、无继承 stdio、无直连网络；WASI 只给时钟与随机数 | `wit/plugin.wit:5-10`、`docs/zh/plugins/architecture.mdx:59-69` |
| 最大缺口（对我方） | 类型推导（Wrapper/实体字段/JdbcTemplate 变量）、SQL 执行与 EXPLAIN、跨文件强类型索引、长驻后台服务 | 见第 6 节 |

## 1 版本基线与证据层级

### 1.1 三层版本必须分开看

| 层 | 值 | 证据 |
| --- | --- | --- |
| main 开发版 | 1.16.0 | `wit/plugin.wit:11`；`crates/ide-plugin/src/runtime.rs:62`（`API_VERSION = "1.16"`）；`sdk/rust/openidea-plugin/src/lib.rs:294` |
| 已发布快照 | 1.15.0 | `sdk/rust/openidea-plugin/tests/api/released/plugin.wit:1`；`wit/` 与 `sdk/rust/openidea-plugin/wit/` 逐文件 diff 无差异 |
| 本机安装版 0.1.51 随附 | 1.15.0 | `git show v0.1.51:wit/plugin.wit` → `package openidea:plugin@1.15.0;`；`git show v0.1.51:sdk/rust/openidea-plugin/tests/api/released/plugin.wit` 同为 1.15.0 |
| 产品版本 | 0.1.51 | `package.json:5`、`Cargo.toml:8`、`src-tauri/tauri.conf.json:4` |

`plugins/README.md:430`、`docs/zh/plugins/versioning.mdx:30` 仍写「1.15.0 / 1.16.0 在 main 上开发中」，与实际 tag 不符——同一仓库内的文档滞后于 tag，判断可用版本以 tag 内 wit 与 released 快照为准。

### 1.2 线上文档核对

| 页面 | 实测结论 | 说明 |
| --- | --- | --- |
| `https://openidea.sh/zh/plugins/api` | 正文写「WIT 包 `openidea:plugin@1.7.0`」「最新已发布版本是 1.6.0」 | 严重滞后于仓库（1.16.0 / 1.15.0），不可作为版本依据 |
| `https://openidea.sh/zh/plugins/versioning` | 版本历史已列到 1.16.0，与本地 `versioning.mdx` 一致 | 该页可用 |
| `https://openidea.sh/zh/plugins/capabilities` | 已含 `java.agent`、`clipboard.read`、`build.read` 等条目，与本地一致 | 该页可用 |
| `https://openidea.sh/zh/plugins/quickstart` | 可见 `0.1.40` 等旧 tag 示例 | 版本示例滞后，流程描述与本地一致 |

结论：线上 API 参考页的版本陈述不可信；以仓库 `wit/` + tag 为准。

### 1.3 1.15 与 1.16 的增量（决定可部署性）

对本机 0.1.51 而言，`wit/plugin.wit` 里 1.16 的内容全部不可用，`engines.openidea` 最高只能写 `^1.15`。

| 1.16 增量 | 位置 | 0.1.51 可用 | 对我方影响 |
| --- | --- | --- | --- |
| `decorations.set-markdown` + `markdown-decoration` | `wit/decorations.wit:12-31` | 否 | 行尾装饰的 Markdown 悬停卡片（SQL 指纹/参数表）只能用纯文本 `hover` |
| `ui.pick-file` + `file-filter`/`file-dialog` | `wit/host.wit:168-179` | 否 | 导出文件只能先 `input()` 问路径再 `project.write`/`fs.write` |
| `vcs.roots` / `vcs.repository-at` | `wit/workspace.wit:228,241` | 否 | 多 worktree 定位退化为单仓库 `status` |
| `completion-list-provider`（不完整列表） | `wit/completion-list.wit:1-29` | 否 | 前缀型补全（`findByXxx` 风格）只能用普通 completion + 客户端过滤 |
| 清单字段 `frames`（web 视图内嵌站点）、`customEditors.binary` | `plugins/README.md:430-470` | 否 | Web 预览页不能嵌外部站点；二进制文件自定义编辑器不可用 |

1.15 的实际增量（0.1.51 可用）：`caret-moved` 事件（`wit/events.wit:38-41`）、`[[contributes.skills]]`（`plugins/README.md:470-490`）。

## 2 插件模型（问题 A）

### 2.1 包与清单

插件 = 一个含 `plugin.toml` 的目录；有代码时是一个 `wasm32-wasip2` 组件。

| 项 | 事实 | 证据 |
| --- | --- | --- |
| 顶层字段 | `id`、`name`、`version`、`engines.openidea` 必填；`wasm`、`activation`、`[capabilities]`、`[contributes]` 可选 | `docs/zh/plugins/manifest.mdx:42-65`、`plugins/README.md:82-136` |
| 校验 | 未知顶层字段报错；贡献整体校验，任一无效则整包不装 | `docs/zh/plugins/manifest.mdx:65` |
| 激活 | 不写 `activation` 时首次执行命令才加载代码 | `docs/zh/plugins/manifest.mdx:73-90` |
| 分发 | `<id>-<version>.oiplugin`（zip）+ Ed25519 `SIGNATURE.json`；市场是静态 `index.json` | `plugins/README.md:2989-3060` |
| 安装途径 | 设置页从磁盘/包文件；`openidea-plugin link` 链接目录；`<project>/.openidea/plugins/` 项目插件 | `docs/zh/plugins/index.mdx`（「插件从哪里来」节） |

### 2.2 world 与导出

基础 world `plugin` 导入 29 个宿主 interface，导出 3 个插件 interface（`wit/plugin.wit:13-47`）；每个可选能力再派生一个 `plugin-*` world（见下表，共 22 个宿主侧 world）。

| world | 额外导出 | 清单声明方式 | 证据 |
| --- | --- | --- | --- |
| `plugin` | `lifecycle`、`commands`、`event-handler` | 必有 | `wit/plugin.wit:44-46` |
| `plugin-syntax` | `syntax-provider` | 贡献语言但宿主无语法 | `wit/plugin.wit:49-53` |
| `plugin-providers` | `providers` | `[[contributes.providers]]` | `wit/plugin.wit:55-59` |
| `plugin-views` | `view-provider` | `views` / `customEditors` | `wit/plugin.wit:61-66` |
| `plugin-indexing` | `document-indexer` | `[[contributes.documentIndexers]]` | `wit/plugin.wit:68-72` |
| `plugin-semantic-tokens` | `semantic-tokens-provider` | provider kind `semantic-tokens` | `wit/plugin.wit:74-78` |
| `plugin-hover` | `hover-provider` | provider kind `hover` | `wit/plugin.wit:80-84` |
| `plugin-inlay-hints` | `inlay-hints-provider` | provider kind `inlay-hints` | `wit/plugin.wit:86-90` |
| `plugin-details` | `detail-provider` | `location = "services"` 视图 | `wit/plugin.wit:92-97` |
| `plugin-commit-checks` | `commit-checker` | `[[contributes.commitChecks]]` | `wit/plugin.wit:99-103` |
| `plugin-file-actions` | `file-actions` | provider kind `code-actions` | `wit/plugin.wit:105-109` |
| `plugin-endpoints` | `endpoints-provider` | `[[contributes.endpoints]]` | `wit/plugin.wit:162-171` |
| `plugin-diagnostics` | `diagnostics-provider` | provider kind `diagnostics` | `wit/plugin.wit:173-182` |
| `plugin-run-configurations` | `run-configurations` | `[[contributes.runConfigurations]]` | `wit/plugin.wit:184-187` |
| `plugin-launch-configurations` | `launch-configurations` | 同上（API 1.9 优先） | `wit/plugin.wit:188-191` |
| `plugin-test-results` | `test-results` | `framework = "plugin"` | `wit/plugin.wit:192-195` |
| `plugin-language-features` | `language-features` | API 1.4 的 13 个 provider kind | `wit/plugin.wit:197-200` |
| `plugin-injections` | `injections` | provider kind `injections` | `wit/plugin.wit:201-204` |
| `plugin-mcp` | `mcp-provider` | `[[contributes.mcpTools]]` | `wit/plugin.wit:206-209` |
| `plugin-run-target-validation` | `run-target-validation` | 无需声明（针对自己的运行目标） | `wit/plugin.wit:211-214` |
| `plugin-search` | `search-provider` | `[[contributes.searchCategories]]` | `wit/plugin.wit:216-219` |
| `plugin-completion-list` | `completion-list-provider` | provider kind `completion`（API 1.16） | `wit/completion-list.wit:26-29` |

另有 `plugin-hover-actions`（`wit/hover-actions.wit:37-40`）与一批只含导出的孪生 world（`syntax`、`editor-providers`、`views-provider`、`indexing`、`semantic-tokens`、`hover`、`inlay-hints`、`details`、`commit-checks`、`file-actions-provider`、`endpoints`、`editor-diagnostics`、`configurations-provider`、`launch-configurations-provider`、`test-results-provider`、`editor-language-features`、`language-injections`、`mcp-tools`、`run-target-validator`、`search`、`completion-list-exports`、`hover-actions-provider`），由 SDK 宏与 `plugin` 合并成一个组件（`wit/plugin.wit:111-160`）。

### 2.3 宿主如何调用插件

| 调用来源 | 入口（导出） | 上下文 | 预算 | 证据 |
| --- | --- | --- | --- | --- |
| 用户命令 | `commands.run(id, borrow<invocation>, arg) -> result<option<string>, error>` | `invocation`：项目、焦点编辑器、选中路径 | 5 s（开任务 60 s） | `wit/exports.wit:15-23`、`wit/workspace.wit:170-185` |
| 工作台事件 | `event-handler.handle(list<event>, borrow<invocation>)`，最旧优先、按窗口分批 | 同上 | 5 s | `wit/exports.wit:25-31` |
| 实例创建/销毁 | `lifecycle.activate(context)`、`deactivate()` | `context`：plugin-id、plugin-version、api-version、已授 capabilities | 5 s | `wit/exports.wit:3-13`、`wit/types.wit:64-72` |
| 编辑器 provider | `providers.*`、`language-features.*`、`inlay-hints-provider`、`hover-provider`、`semantic-tokens-provider`、`diagnostics-provider`、`injections`、`document-indexer`、`endpoints-provider`、`search-provider` | 多数传文档快照（path/language/version/text），无交互上下文 | 50 ms 软 / 500 ms 硬；format 5 s；inline-completion 免 50 ms | `wit/providers.wit:5-6`、`wit/language-features.wit:4`、`plugins/README.md:1570-1580` |
| 视图渲染/交互 | `view-provider.render(view, viewport, ctx)` / `handle(view, event, ctx)` | `invocation` | 未见单独声明（按命令预算处理，待验证） | `wit/views.wit:206-213` |
| 详情面板 | `detail-provider.detail(view, key, ctx)` | `invocation` | 未单独声明 | `wit/details.wit:42-50` |
| MCP 工具 | `mcp-provider.call(tool, arguments, ctx)` | `invocation`，但不是「用户命令」 | 插件命令预算（5 s） | `wit/mcp.wit:10-16`、`plugins/README.md:1234-1292` |
| 运行目标校验 | `run-target-validation.validate(project, target-id, launch)` | 无窗口 | 宿主调用超时 5 s，随启动取消 | `wit/exports.wit:77-94` |
| 提交前检查 | `commit-checker.check(check, draft, ctx)` | `invocation` | 未单独声明 | `wit/commit.wit:57-75` |
| 测试输出解析 | `test-results.parse(config, chunk)` / `select-tests(...)` | 无窗口 | 编辑器预算（每批） | `wit/run.wit:68-93` |
| 运行/启动配置枚举 | `run-configurations.configurations(root)` / `launch-configurations.configurations(root)` | 无窗口 | 无窗口导出，不得弹窗 | `wit/run.wit:12-65` |

关键点：所有调用都是同步的，宿主函数返回前插件实例阻塞（`docs/zh/plugins/architecture.mdx:52-58`）；同一实例内调用排队、永不并发（`:88`）。插件侧没有 future/async 原语，WASI 0.3 异步组件排在 API 2.0（`:100-102`）。

### 2.4 生命周期

| 阶段 | 行为 | 证据 |
| --- | --- | --- |
| 启用 | 清单贡献立即生效，代码未加载（状态「已启用」） | `docs/zh/plugins/lifecycle.mdx:5-18` |
| 激活 | 执行命令、命中 `activation` 事件、显示视图等；每实例调用一次 `activate` | `docs/zh/plugins/lifecycle.mdx:20-36`、`docs/zh/plugins/manifest.mdx:73-90` |
| 空闲回收 | 5 分钟无调用 → `deactivate`，回到未运行 | `docs/zh/plugins/architecture.mdx:82`、`docs/zh/plugins/lifecycle.mdx:24` |
| 重载 | 设置页「从磁盘重新加载」/ `openidea-plugin link` 后重载 | `docs/zh/plugins/lifecycle.mdx:111-114` |
| 更新/回滚 | `active` 指针切换；新版本首次启动失败自动回滚 | `docs/zh/plugins/lifecycle.mdx:115-116` |
| 崩溃 | 实例被丢弃；连续崩溃退避 1 s→60 s；3 次后本会话标记「失败」；一次正常返回即清零 | `docs/zh/plugins/architecture.mdx:90` |
| 禁用/卸载 | 禁用停止代码并撤回贡献、保留存储；卸载删除存储与私有目录 | `docs/zh/plugins/lifecycle.mdx:117` |

`activate` 里只能做「可重建」的事：内存不跨实例保留（`docs/zh/plugins/lifecycle.mdx:24,76-84`）。

### 2.5 状态与存储

| 载体 | 容量/生命周期 | 能力 | 证据 |
| --- | --- | --- | --- |
| 实例内存 | 随实例消失 | 无 | `docs/zh/plugins/lifecycle.mdx:76-84` |
| `storage` 键值 | 64 MiB（键+值 UTF-8 字节计），跨空闲回收/重启/更新，卸载删除 | `storage` | `wit/host.wit:199-210` |
| `fs.plugin-dir` 私有目录 | 无总量声明；单文件读写 16 MiB；0700/ACL；卸载删除 | 无（免 fs 授权） | `wit/fs.wit:17-20`、`plugins/README.md:340-428` |
| 设置 | 只读 `settings.get(key) -> option<string>`（JSON 值，项目优先） | 无 | `wit/views.wit:197-202` |
| 上下文键 | 每插件 100 个，键 1-64 字节 `[A-Za-z0-9_-]`，插件停止即消失 | 无 | `wit/host.wit:133-140` |
| 密钥 | 插件只能写/删/查是否存在，永不可读回 | `secrets` | `wit/io.wit:113-123` |
| 状态栏项 | 全局项与窗口覆盖项；1.14 起支持菜单（≤64 项） | 无 | `wit/host.wit:106-121` |

### 2.6 错误与降级模型

`error` 是 6 分支 variant：`denied(string)`、`stale`、`not-found(string)`、`invalid(string)`、`cancelled`、`failed(string)`（`wit/types.wit:8-18`）。

| 语义 | 宿主行为 | 插件应对 |
| --- | --- | --- |
| `denied(cap)` | 能力未授予；payload 是能力名（如 `editor.write`） | 继续运行并降级，不得视为致命 | 
| `cancelled` | 用户取消或插件被停止 | 直接返回，不计失败（`wit/exports.wit:19-21`） |
| `stale` | 版本化编辑的基线版本过期，文本不变 | 重新读文档再算 |
| `not-found` / `invalid` / `failed` | 目标不存在 / 参数非法 / 宿主侧失败 | 分别提示或重试 |

降级是被设计为前提的：`optional` 能力可被用户逐个拒绝，插件必须能处理 `denied`（`docs/zh/plugins/capabilities.mdx:6-10`）。可选导出缺失时各有默认：例如未导出 `run-target-validation` 时启动放行（`wit/exports.wit:87-93`）。

### 2.7 异步、长任务与取消

| 项 | 事实 | 证据 |
| --- | --- | --- |
| 异步 | 1.x 无异步；全部同步、整文档传递 | `docs/zh/plugins/architecture.mdx:52-58`、`wit/types.wit`（无 future 类型） |
| 长任务 | `tasks.begin(title, cancellable)` → `report(done,total,msg)`、`is-cancelled()`；开任务期间本次调用可跑 60 s，每次 report/is-cancelled 续期 | `wit/events.wit:59-73` |
| 取消 | 任务协作式取消；宿主对编辑器调用有硬中断；`net.fetch`/`process` 随调用预算取消 | `wit/events.wit:66-68`、`wit/io.wit:94-96,193-196` |
| 定时 | 只有 `on-schedule:<secs>`，最小 60 s，到点发 `scheduled` 事件且不保活 | `docs/zh/plugins/manifest.mdx:73-90`、`docs/zh/plugins/lifecycle.mdx:24` |
| 线程 | 每插件一个 actor 线程，调用串行；未见插件侧线程/并发原语 | `docs/zh/plugins/architecture.mdx:86-88` |
| 轮询 | 无定时器；只能靠事件（`caret-moved`、`files-changed`、`document-changed`）或 `on-schedule` | `wit/events.wit:29-56` |

## 3 能力总表（问题 B）

### 3.1 capability 总表

能力在清单 `[capabilities] required/optional` 中声明，安装时逐项授权；风险分低/中/高，高风险对话框明示「相当于在本机运行任意代码」（`docs/zh/plugins/capabilities.mdx:15-30`）。下表「API」列是声明该能力时清单要求的最低 API。

| 能力 | 风险 | 解锁 | API | 对我方用途与限制 |
| --- | --- | --- | --- | --- |
| `project.read` | 低 | `project.root/read-file/read-bytes/exists/stat/find-files/symbols/search-text`、发布诊断、`problems`、打开编辑器、装饰 | 1.0 | 全部只读功能的地基 |
| `project.write` | 高 | 项目内创建/替换文本文件（不写 `.git`） | 1.3 | 导出 SQL/报告；需高风险授权 |
| `editor.read` | 低 | `text-editor.document/selections`、`editor.open-editors`、`editor-panels` | 1.0 | 读当前 SQL 所在文档/选区 |
| `editor.write` | 中 | `text-editor.apply-edits`、`editor.apply-edits-at` | 1.0 | 快速修复/改写 SQL |
| `vcs.read` | 低 | `vcs.status/remotes/log/blame/diff`、待提交内容、提交前检查 | 1.0 | 次要（SQL 变更对比） |
| `vcs.write` | 中 | `vcs.fetch`、`set-commit-message` | 1.0 | 基本无关 |
| `build.read` | 低 | `build.dependencies`：IDE 已解析的 Maven/Gradle 依赖图 | 1.10 | 变通：判定 MyBatis-Plus 版本/方言包 |
| `storage` | 低 | 64 MiB 键值 | 1.0 | 缓存/索引持久化 |
| `events` | 低 | 文件打开/关闭/保存/变化、焦点、VCS、设置变化、caret-moved | 1.0 | 增量刷新 |
| `events.input` | 中 | `action-performed`、`input-mode-changed` | 1.0 | 一般不需要 |
| `clipboard` | 中 | `ui.copy-to-clipboard`、web 视图剪贴板 | 1.0 | 复制 SQL |
| `clipboard.read` | 中 | `ui.read-clipboard`（仅用户运行的命令/视图操作） | 1.11 | 从剪贴板解析 SQL |
| `secrets` | 中 | 请求头 `{{secret:<name>}}`、`secret_set/delete/is_set` | 1.0 | EXPLAIN 所需 DB 凭据只能这样用 |
| `net:<host>` | 中 | HTTPS（及回环 HTTP）到该主机；`*.x.com` 匹配子域 | 1.0 | 调外部 SQL 网关/LLM；响应 ≤8 MiB |
| `net:ask` | 中 | 首次访问未声明 HTTPS 主机时询问用户 | 1.5 | 可选；无窗口调用直接 denied |
| `net.http:<host>` | 中 | 未加密 HTTP | 1.4 | 内网服务 |
| `database.sources` | 中 | 只管插件自己的数据源、写凭据、请求连接；仅受信任项目 | 1.4 | 不能执行 SQL，见 3.2 `database` |
| `fs.read:<path>` | 高 | 项目外读取（目录任意深度或单文件） | 1.0（interface 1.3） | 读外部配置/驱动 jar 说明 |
| `fs.write:<path>` | 高 | 项目外写入（不含读） | 1.3 | 导出到项目外目录 |
| `process:<program>` | 高 | 运行指定程序（PATH 解析+内容哈希固定） | 1.0 | 变通：CLI 驱动数据库/EXPLAIN |
| `lsp:<server>` | 高 | 向运行中的语言服务器发它自己的方法 | 1.9 | 不能发 `textDocument/*`，见 3.2 |
| `java.agent` | 高 | 在用户启动的 Java 应用里加载插件 jar | 1.10 | SQL 捕获唯一正规通道 |
| `ipc:<path>` | 高 | 项目机器本地 Unix socket / Windows 命名管道 | 1.4 | sidecar 通信 |
| `ide.commands` | 高 | 以用户身份运行任意 IDE 动作/他插件命令 | 1.4 | 高门槛，慎用 |

### 3.2 全部 interface

门槛列中「无能力」表示该 interface 不受 capability 约束（仍受调用方上下文限制）。

| interface（定义处） | 能做什么 | 门槛 | 对我方 |
| --- | --- | --- | --- |
| `types` `wit/types.wit:5` | 位置/范围/编辑/错误/extension/context | 无 | 全局基础 |
| `log` `wit/host.wit:4` | 写 IDE 日志（带插件 id） | 无 | 诊断 |
| `ui` `wit/host.wit:10` | 通知（含动作按钮、按项目）、状态栏项/菜单、上下文键、选择器/输入/确认/选目录/选文件（1.16）、剪贴板读写、开 URL、界面语言、控制台高亮规则 | 见各函数；`clipboard`/`clipboard.read` | 通知、复制、导出路径选择、控制台高亮 |
| `storage` `wit/host.wit:202` | 64 MiB 键值 | `storage` | 缓存 |
| `command-runner` `wit/host.wit:213` | 运行 IDE 动作或 `plugin.<id>.<cmd>` | 自身命令免；其余 `ide.commands` | 低优先 |
| `language-servers` `wit/host.wit:222` | 向指定 LSP 发它自己的方法（`request`/`notify`） | `lsp:<server>` + 信任 | 不可用于查类型（见下） |
| `output` `wit/host.wit:249` | 32 个具名只读输出通道，每通道 1 MiB 环形缓冲 | 无 | 渲染日志/调试输出 |
| `workspace` `wit/workspace.wit:3` | 打开项目、`project-of`、`projects`、项目内读写/存在/stat/find-files/symbols/search-text | `project.read/write` | 全部文件侧能力 |
| `editor` `wit/workspace.wit:112` | 打开文件/编辑器列表/后台读文档/后台版本化编辑/资源 `text-editor`（选区、滚动、apply-edits） | `editor.read/write`、`project.read` | 定位跳转、改写 |
| `invocation` `wit/workspace.wit:170` | 调用来源：项目、焦点编辑器、选中路径 | 无 | 命令上下文 |
| `vcs` `wit/workspace.wit:188` | status/remotes/roots/log/blame/diff/repository-at/fetch | `vcs.read/write` + 信任 | 次要 |
| `build` `wit/workspace.wit:249` | Maven/Gradle 依赖图（扁平列表） | `build.read` + 信任 | 判定 MP 版本 |
| `fs` `wit/fs.wit:13` | 项目外读/写/stat/read-dir + 私有 `plugin-dir` | `fs.read:`/`fs.write:`；plugin-dir 免 | 缓存目录、外部导出 |
| `decorations` `wit/decorations.wit:8` | 编辑器行内标注（文本/Markdown 悬停）、项目树 badge/note/icon | `project.read` | SQL 摘要/风险标记、树标记 |
| `commit` `wit/commit.wit:6` | 读待提交草稿、改提交信息 | `vcs.read/write` | 次要 |
| `events` `wit/events.wit:4` | 订阅工作台事件、按路径 glob 过滤 | `events`（`action-performed` 需 `events.input`） | 增量刷新 |
| `tasks` `wit/events.wit:63` | 带进度的长任务、协作取消 | 无 | 大项目扫描 |
| `diagnostics` `wit/io.wit:4` | 发布/替换文件诊断、读 Problems | `project.read` | 方言/性能告警 |
| `net` `wit/io.wit:47` | `fetch`（HTTP/HTTPS）、受控 WebSocket（≤8） | `net:<host>`/`net.http:` | 外部服务 |
| `secrets` `wit/io.wit:116` | 写/删/查密钥 | `secrets` | DB 凭据 |
| `process` `wit/io.wit:129` | 一次性运行、带环境运行、常驻 child（≤8）、限流流式 | `process:<program>` + 信任 | CLI 工具/EXPLAIN/sidecar |
| `ipc` `wit/io.wit:221` | 本地 socket/命名管道（≤8 连接） | `ipc:<path>` | sidecar |
| `auth` `wit/io.wit:238` | OAuth 回环重定向（≤2） | 无 | 次要 |
| `views` `wit/views.wit:124` | 刷新/前置视图、发消息、终端、内容标签页、diff、打开位置 | 见各函数 | 工作台、预览标签页 |
| `view-types` `wit/views.wit:10` | 声明式视图模型：tree/list/form/table/message/html + 事件 | 无 | 预览面板主体 |
| `settings` `wit/views.wit:199` | 读插件设置（项目优先/默认） | 无 | 40 项设置 |
| `run` `wit/run.wit:4` | 启动自有运行配置到 Run 窗口 | 无（配置需 `process:`） | 次要 |
| `run-targets` `wit/run-targets.wit:4` | 注册/注销包装型运行目标 | `process:` + 信任 | 容器内跑 SQL CLI |
| `database` `wit/database.wit:5` | 管理自有数据源、写凭据、请求 IDE 连接 | `database.sources` + 信任 | 只能建连接，不能查询 |
| `mcp` `wit/mcp.wit:4` | 取内置 MCP Server 的 stdio 启动 argv | 无 | 对外暴露工具 |
| `detail-types` `wit/details.wit:4` | 详情面板模型（text/log/toggle） | 无 | 服务面板 |
| `lifecycle` `wit/exports.wit:3` | `activate`/`deactivate` | 无 | 必有 |
| `commands` `wit/exports.wit:15` | 执行插件命令，返回可选消息 | 无 | 交互入口 |
| `event-handler` `wit/exports.wit:25` | 一批事件回调 | 订阅时校验 | 必有 |
| `syntax-provider` `wit/exports.wit:37` | 自有语言的高亮/大纲/折叠（UTF-8 偏移） | 无 | 仅自有语言 |
| `run-target-validation` `wit/exports.wit:78` | 自有运行目标启动前校验 | 无 | 次要 |
| `providers` `wit/providers.wit:7` | completion/inline-completion/code-actions/definition/line-markers | 文档随调用传入 | 导航、gutter、补全 |
| `language-features` `wit/language-features.wit:5` | format/signature-help/highlights/links/code-lens/selection-ranges/folding/colors/symbols/implementation/type-definition/gutter-actions | 同上 | 大量 UI 细节可做 |
| `injections` `wit/injections.wit:8` | 声明文档中的嵌入语言范围 | 同上 | XML 内 SQL、Java 字符串内 SQL |
| `hover-provider` `wit/hover.wit:7` | 向悬停追加 Markdown（≤64 KiB） | 同上 | SQL 说明卡 |
| `hover-actions` `wit/hover-actions.wit:6` | 行左缘 hover 显示可点动作 | 同上 | 行级快捷操作 |
| `inlay-hints-provider` `wit/inlay.wit:7` | 行内提示（≤10,000/次，可见范围） | 同上 | SQL 列名/参数提示 |
| `semantic-tokens-provider` `wit/semantic.wit:7` | 语义着色（≤65,536 token，LSP 种类） | 同上 | SQL 片段着色 |
| `diagnostics-provider` `wit/diagnostics.wit:7` | 输入时诊断（≤5,000/文档） | 同上 | 方言/性能告警 |
| `file-actions` `wit/file-actions.wit:8` | 会创建/重命名/删除文件的代码动作 | 同上 | 生成 Mapper XML 等 |
| `document-indexer` `wit/indexing.wit:9` | 跨文件 definitions/references/aliases/markers，宿主自行回答跳转与 gutter | `project.read` + 信任 | Mapper↔XML 持久图 |
| `endpoints-provider` `wit/endpoints.wit:4` | 项目 HTTP 路由清单 | 无 | 基本无关 |
| `search-provider` `wit/search.wit:4` | 自定义 Search Everywhere 分类的可取消查询（≤200/页） | 无（需 `searchCategories`） | 按表名/语句搜 SQL |
| `view-provider` `wit/views.wit:206` | 渲染视图/自定义编辑器并处理事件 | 无 | 预览工作台 |
| `detail-provider` `wit/details.wit:42` | Services 节点详情标签页（≤8） | 无 | 次要 |
| `commit-checker` `wit/commit.wit:57` | 提交前检查 | `vcs.read` | 次要 |
| `run-configurations` `wit/run.wit:12` | 动态运行配置列表 | `process:` | 次要 |
| `launch-configurations` `wit/run.wit:29` | 带 env 与调试器的运行配置 | `process:` | 次要 |
| `test-results` `wit/run.wit:68` | 解析测试输出、选择重跑测试 | 无 | 无关 |
| `mcp-provider` `wit/mcp.wit:10` | 实现声明的 MCP 工具 | 无（继承插件授权） | 11 个 sqlv_* |
| `completion-list-provider` `wit/completion-list.wit:6` | 可标记「不完整」的补全（1.16） | 无 | 前缀型表名补全 |
| `extensions` `wit/extensions.wit:6` | 读他插件对自己扩展点的贡献 | 无（需 `[[extension-points]]`） | 可选生态位 |

`language-servers` 的一个硬限制：只允许服务器自己的方法；`textDocument`、`workspace`、`window`、`completionItem`、`codeAction`、`inlayHint` 等 LSP 命名空间一律 `invalid`（`wit/host.wit:231-236`）。OpenIDEA 的 Java 智能由 JDT LS 提供（《00-README》基线事实 1），因此无法借它做 Java 类型解析——这决定了我方「Wrapper 渲染/实体字段推导」只能自建解析或走上游。

### 3.3 声明式贡献点

贡献点写在 `[contributes]`，启用即生效、不需要加载代码（`docs/zh/plugins/manifest.mdx:99-129`、`plugins/README.md:137-164`）。

| 贡献点 | 字段 | 需要代码 | 对我方 |
| --- | --- | --- | --- |
| `commands` | `id`、`title`、`category?`、`when?`、`icon?` | 执行时需要 | 全部命令入口 |
| `menus` | `menu`（`editor.context`、`projectView.context`、`editorTab.context`、`viewItem.context`、`tools`、`commit.message`；1.4 起 `editor.title`、`mainToolbar`、`problems.context`、`searchResults.context`）、`view?`、`command`、`when?`、`submenu?` | 否 | 右键/工具菜单 |
| `keymaps` | `command`、`key`、`mac?`、`when?`（Escape 需 when） | 否 | 快捷键 |
| `statusBar` | `id`、`text`、`tooltip?`、`command?`、`align?`、`priority?` | 否 | 常驻状态（如方言/连接） |
| `settings` | `schema`（JSON Schema）或 `pages`（1.3）、`title?` | 否 | 约 40 项设置 |
| `secrets` | `name`、`label`、`description?` | 使用时需要 | DB 凭据占位 |
| `providers` | `kinds`、`languages`（1-64） | 是 | 语言特性开关 |
| `documentIndexers` | `extensions` | 是 | Mapper↔XML 索引 |
| `endpoints` | `files`（1.3） | 是 | 无关 |
| `searchCategories` | `id`、`title`（1.13） | 是 | 搜 SQL/表名 |
| `views` | `id`、`title`、`icon?`、`location`（left/right/bottom/services）、`web?`、`group?`、`when?`、`secondary?`、`detail?`、`header?`、`frames?`（1.16） | 无 `web` 时需要 | 预览工作台/多标签 |
| `customEditors` | `id`、`title`、`patterns`、`priority`、`layout?`、`web?`、`frames?`、`binary?`（1.16） | 无 `web` 时需要 | 独立 SQL 文件视图 |
| `editorPanels` | `id`、`web`、`width`（40-400）、`when?`、`frames?`（1.9，需 `editor.read`） | 否 | 编辑器侧栏预览 |
| `markerIcons` | `id`、`icon`、`iconDark?`、`markers?`、`tooltip?`（1.3） | 否 | 品牌化 gutter 图标 |
| `fileIcons` | `id`、`patterns`、`icon`、`iconDark?` | 否 | Mapper XML 图标 |
| `fileTemplates` | `id`、`name`、`extension`、`path`；1.3：`fileName`、`variables` | 否 | 生成 Mapper 骨架 |
| `snippets` | `language`、`path`（1.4） | 否 | SQL 模板 |
| `commitChecks` | `id`、`title` | 是 | 提交前 SQL 预检 |
| `runDetectors` | `id`、`name`、`files`、`command`、`args?`、`test?`、`framework?` | 否 | 启动 DB CLI |
| `runConfigurations` | `files`（1.4） | 是 | 动态运行配置 |
| `javaAgents` | `path`、`args?`（1.10）；`vmOptions?`、`jdk?`（1.14） | 否 | SQL 捕获 jar |
| `skills` | `path`（`skills/<name>/SKILL.md`，1.15） | 否 | 无 |
| `mcpTools` | `name`、`description`、`inputSchema`、`readOnly`、`destructive`（1.4） | 是 | 11 个 sqlv_* |
| `themes` / `iconThemes` | `path`、`editor-scheme?` / `id`、`label`、`path` | 否 | 无 |
| `languages` | `path`（`languages/<id>.toml`） | 否 | 可贡献 SQL/XML 变体语言 |
| `serverCommands` | `server`、`command`、`run`（1.9） | 是 | 无 |

## 4 插件做不到什么（问题 C）

| 约束 | 明确程度 | 证据 | 对我方影响 |
| --- | --- | --- | --- |
| 无直接文件系统 | 明确 | 无预打开目录；文件只能经 `fs`/`workspace`（`wit/plugin.wit:5-10`、`docs/zh/plugins/architecture.mdx:59-69`） | 一切读写走宿主函数与授权 |
| 无环境变量/无继承 stdio | 明确 | `docs/zh/plugins/architecture.mdx:61-65` | 不能读 `MYBATIS_*` 等环境 |
| 无直连网络 | 明确 | 同上；`net.fetch` 需逐主机授权（`wit/io.wit:46-96`） | EXPLAIN 只能经网关或 process |
| 无原生线程 | 部分（未见显式禁止条款，但模型上不提供） | 单 actor 线程 + 串行调用（`docs/zh/plugins/architecture.mdx:86-88`），WIT 无线程类型 | 不能后台常驻扫描 |
| 无定时器（<60 s） | 明确 | `on-schedule` 最小 60 s（`docs/zh/plugins/manifest.mdx:73-90`） | 轮询式缓存刷新不可行 |
| 无常驻后台服务 | 明确 | 5 分钟空闲回收（`docs/zh/plugins/architecture.mdx:82`） | 索引/守护进程要外置（javaAgent/sidecar） |
| 内存 256 MiB | 明确 | `docs/zh/plugins/architecture.mdx:81` | 大项目全量 AST 不可行 |
| CPU/时间预算 | 明确 | 5 s / 60 s / 50 ms / 500 ms（`docs/zh/plugins/architecture.mdx:73-82`） | 复杂解析必须增量+缓存 |
| 无 AST/PSI | 明确 | 只有 `symbols`（名字/种类/容器/位置）与 `search-text`（`workspace.wit:34-40,105`）；README 明示类型解析推迟到 2.0（`plugins/README.md:914-928`） | Wrapper/注解 SQL 需自写词法/语法 |
| 无类型解析/成员查询 | 明确 | `plugins/README.md:914-928` | 实体字段推导不可靠 |
| 不能向 LSP 发 `textDocument/*` | 明确 | `wit/host.wit:231-236` | 借 LSP 查 Java 语义的路被堵死 |
| 不能执行 SQL / 无 EXPLAIN | 明确（接口无此函数） | `wit/database.wit:1-34` 只有 upsert/remove/sources/set-credential/connect | EXPLAIN 必须外置 |
| 数据库驱动仅 6 种 | 明确 | `database.wit:8-11`：sqlite/postgres/mysql/sqlServer/clickhouse/oracle | 达梦/人大金仓/瀚高等无驱动 id |
| 不能起原生 GUI | 明确 | 只能声明式视图、沙箱 web 页、原生对话框（`wit/views.wit:3-9`、`plugins/README.md:2141-2145`） | 无 Swing/JCEF 式自绘 |
| web 页无网络/无存储/无 IDE API | 明确 | `<iframe sandbox="allow-scripts">`，opaque origin（`plugins/README.md:2141-2145`） | 预览页只能靠 postMessage |
| 不能读密钥 | 明确 | `wit/io.wit:113-115`、`docs/zh/plugins/capabilities.mdx:104` | 凭据只能由宿主注入请求头 |
| 不能写 `.git` | 明确 | `plugins/README.md:340-428` | 无关 |
| 项目插件不能跑远程项目；远程存储初始为空 | 明确 | `docs/zh/plugins/architecture.mdx:94-98` | 远程场景退化 |
| Java agent 仅限 Application/Spring Boot/JAR Application | 明确 | `plugins/README.md:2498-2543`、`docs/zh/plugins/capabilities.mdx:74-78` | 测试/Gradle/远程 JVM 捕获不可用 |
| 远程项目只在受信任项目放行 process/vcs 读/数据源/运行目标 | 明确 | `docs/zh/plugins/capabilities.mdx:108` | 安装引导需说明 |
| MCP 工具不是用户命令 | 明确 | `plugins/README.md:1234-1292` | 命令/事件专属 API 在工具内不可用 |
| 未声明「禁止创建线程/禁止 wasm 内部并发」的原文 | 未见声明 | — | 标为待验证（推断：线程提案未暴露） |

## 5 重点 interface 函数级清单（问题 D）

按我方相关度分 8 组；每组给全量函数签名与限制。位置一律为 `path:line`。

### 5.1 组一：文件与工作区（`fs`、`workspace`、`editor`、`invocation`）

`fs`（`wit/fs.wit`）

| 函数 | 签名要点 | 限制 |
| --- | --- | --- |
| `plugin-dir` `:20` | `() -> result<string, error>` | 私有目录，免授权，卸载删除 |
| `read-file` `:25` | `(path: string) -> result<string, error>` | ≤16 MiB，按编辑器编码解码，未保存内容优先 |
| `read-bytes` `:28` | `(path) -> result<list<u8>, error>` | ≤16 MiB |
| `stat` `:32` | `(path) -> result<file-stat, error>` | 缺失为 `not-found` |
| `read-dir` `:43` | `(path) -> result<list<dir-entry>, error>` | ≤4096 项，按名排序 |
| `write-file` `:50` | `(path, text) -> result<_, error>` | ≤16 MiB，原子写，保留编码/BOM；编辑器有未保存修改则 `invalid` |
| `write-bytes` `:54` | `(path, bytes) -> result<_, error>` | 同上 |

`workspace`（`wit/workspace.wit`）：`open-project(path, new-window)` `:9`、`project-of(path) -> option<project>` `:13`、`projects() -> list<project>` `:109`；`project` 资源：`root` `:59`、`search-text(query, limit)` `:63`（≤10,000 命中、4 MiB、可取消）、`read-file` `:68`、`read-bytes` `:74`、`write-file` `:82`、`exists` `:87`、`stat` `:91`、`find-files(glob, limit)` `:95`（跳过 ignore/exclude）、`symbols(query, limit)` `:105`（模糊、快照、≤1000、查询 ≤256 字节）。

`editor`（`wit/workspace.wit:112-167`）：`open-editors()` `:125`、`open(path, selection)` `:129`、`document-at(project, path)` `:135`（后台可读，需 `project.read`+`editor.read`）、`apply-edits-at(project, path, version, edits)` `:144`（后台不可撤销编辑，拒绝 VCS/符号链接路径）；`text-editor` 资源：`path` `:149`、`set-selections` `:151`、`reveal` `:153`、`language` `:155`、`document` `:157`、`selections` `:160`、`apply-edits(version, edits)` `:165`（一次可撤销，`stale` 拒绝）。

`invocation`（`wit/workspace.wit:170-185`）：`project` `:176`、`editor` `:178`、`selected-paths` `:183`。

### 5.2 组二：语言特性（`providers`、`language-features`、`injections`、`hover`、`inlay`、`semantic`）

`providers`（`wit/providers.wit`）

| 函数 | 签名 | 限制 |
| --- | --- | --- |
| `completion` `:71` | `(doc, at, trigger: option<string>) -> list<completion-item>` | 合并 LSP 结果；item 支持 snippet/filter-text/additional-edits |
| `inline-completion` `:73` | `(doc, at) -> option<inline-suggestion>` | 免 50 ms，用户继续输入即取消 |
| `code-actions` `:75` | `(doc, selection) -> list<code-action>` | 可延迟 `data` 解析 |
| `resolve-code-action` `:81` | `(doc, action) -> result<code-action, error>` | 命令预算；可用 pick/input/confirm |
| `definition` `:82` | `(doc, at) -> list<location>` | 合并 LSP |
| `line-markers` `:83` | `(doc) -> list<line-marker>` | icon 支持别名/注册图标/包内 SVG；`target` 可跳转 |

`language-features`（`wit/language-features.wit`）：`format(doc, range, options)` `:89`（5 s 预算）、`signature-help` `:91`、`document-highlights` `:93`、`document-links` `:95`（路径或 HTTP URL）、`code-lenses` `:97`（命令必须自声明）、`selection-ranges` `:99`、`folding` `:101`、`colors` `:103`、`color-presentations` `:105`、`document-symbols` `:107`（仅 LSP 无符号时用）、`implementation` `:109`、`type-definition` `:111`、`gutter-actions` `:113`（可点命令）。除 format 外均为 50/500 ms；每列表 ≤10,000，单串 ≤64 KiB（`plugins/README.md:1696-1740`）。

`injections`（`wit/injections.wit`）：`injections(doc) -> list<injection>` `:15`；每文档 ≤1,000 范围、≤32 种嵌入语言、>2 MiB 不处理、不递归（`plugins/README.md:1741-1789`）。

`hover-provider`（`wit/hover.wit`）：`hover(doc, at) -> option<hover-content>` `:28`，Markdown ≤64 KiB，显示在 LSP hover 之下。

`inlay-hints-provider`（`wit/inlay.wit`）：`inlay-hints(doc, range) -> list<inlay-hint>` `:38`；hint 字段 `position`/`label`/`kind`（`type`/`parameter`/其他）/`tooltip`/`padding-left`/`padding-right` `:19-33`；仅返回可见范围、≤10,000/次、label ≤256 字节、tooltip ≤1 KiB。

`semantic-tokens-provider`（`wit/semantic.wit`）：`semantic-tokens(doc) -> list<semantic-token>` `:30`；LSP 种类+修饰符，重叠时覆盖 LSP，≤65,536。

### 5.3 组三：跨文件索引与搜索（`document-indexer`、`search-provider`、`symbols`）

`document-indexer`（`wit/indexing.wit`）

| 项 | 内容 |
| --- | --- |
| 输入 | `document{path, text, symbols}` `:23-28`，`symbols` 由宿主语法支持给出（name/kind/container/range）`:13-20` |
| 输出 | `edges{definitions, references, aliases, markers}` `:49-58` |
| 键 | 任意字符串，按原值比较 `:35-38` |
| 别名 | `prefix#` 的键也可按 `target#` 解析，用于继承 `:40-46` |
| 上限 | 每文档 4,096 definitions+references、4,096 markers、256 aliases；2 MiB/文档；20,000 文件/图；200,000 条目/图；16 图；单次查找 256 候选 `:60-62`、`plugins/indexing.md`（Limits 段） |
| 宿主行为 | 后台可取消分批（每批 ≤16 文件）；自行回答 Go to Declaration、Ctrl+Click、gutter；API 1.3 起还支持 Find Usages 与 Rename（锚点须精确覆盖名字） |

`search-provider`（`wit/search.wit`）：`search(category, query, limit, ctx) -> result<search-page, error>` `:33`；`limit` 1-200，query ≤1024 字节，后台调用不得弹窗，新查询取代旧查询（取消）。

`project.symbols`：见 5.1；官方 mybatis 未用它（其用 `find-files` + `search-text`，`plugins/official/mybatis/src/lib.rs:726-779`）。

### 5.4 组四：呈现（`decorations`、`diagnostics`、`views`、`settings`、`details`、`hover-actions`）

`decorations`（`wit/decorations.wit`）：`set(path, set-id, items)` `:27`（≤10,000 项/文件、≤50 编辑器集/插件、元数据 ≤4 MiB）、`set-markdown(...)` `:31`（1.16）、`clear(set-id)` `:33`、`set-file` `:35`（树 badge ≤2 字符）、`clear-file` `:37`、`set-file-note` `:40`（一行 ≤200 字符）、`set-file-icon` `:43`、`set-file-icons` `:45`（≤10,000）。样式只接受主题 token：`highlight`、`search`、`added`、`modified`、`removed`、`warning`、`error`、`info`、`typo`、`weak`、`underline`、`strikethrough`、`muted`，未知样式忽略（`wit/decorations.wit:6-7`）。

`diagnostics`（`wit/io.wit:4-44`）：`problems(path: option<string>) -> list<file-problems>` `:13`（需窗口；远程空）、`publish(path, diagnostics)` `:43`（替换本插件结果）；`diagnostic{range, severity, message, code, source, tags, related}` `:28-39`。

`views`（`wit/views.wit:124-195`）：`open-terminal-session(key,title,cmd)` `:149`、`write-terminal(key,text)` `:151`、`open-content-tab(tab)` `:153`（≤1 Mi UTF-16，≤16 动作、1 个 save）、`reply-content-tab(request-id, reply)` `:155`（可回 diff）、`open-diff(diff, file-name)` `:162`（每侧 ≤8 MiB）、`open-file-diff` `:167`（允许一侧缺失）、`refresh(view)` `:171`、`reveal(view)` `:174`、`post-message(view, message)` `:176`、`open-terminal(title, program, args)` `:180`、`open-log-tab(key,title,log)` `:187`（≤32 开关）、`open-location(path, line, column)` `:194`。

`view-types`（`wit/views.wit:10-121`）：`button`、`tree-item`、`list-item`、`field`/`field-value`（text/multiline/checkbox/choice/other）、`table{columns,total-rows,first-row,rows,editable}`、`body`（message/tree/list/form/table/html/other）、`view{toolbar,body,status}`、`viewport`、`cell-edit`、`view-event`（button/activate/select/expand/collapse/field/cell/insert-row/delete-rows/message/other）。`html` 为沙箱页，经 `openidea.postMessage/onMessage` 通信（`:78`）。

`settings`（`wit/views.wit:199-202`）：`get(key) -> option<string>`，JSON 值，项目优先/默认。

`details`（`wit/details.wit`）：`detail(view, key, ctx) -> result<list<tab>, error>` `:49`；`pane` 为 message/text（≤1 MiB）/log；`process-log{program,args,toggles}` `:16-20` 由 IDE 运行并跟随输出（需 `process:`）。

`hover-actions`（`wit/hover-actions.wit`）：`hover-actions(doc) -> list<hover-action>` `:28`；锚定行 `line`，触发区间 `start-line`..`end-line`（含），图标可用注册图标或包内 SVG。

### 5.5 组五：数据与外部（`database`、`mcp`、`net`、`secrets`、`process`、`ipc`、`auth`）

`database`（`wit/database.wit`）

| 函数 | 签名 | 限制 |
| --- | --- | --- |
| `upsert-source` `:24` | `(project, data-source) -> result<_, error>` | 只建/改自己的源；URL/options 不得含凭据 |
| `remove-source` `:26` | `(project, id)` | 非自有 id 一律 `not-found` |
| `sources` `:28` | `(project) -> list<string>` | 只返回私有 id |
| `set-credential` `:30` | `(project, id, password)` | 直写 Database 安全存储，永不可读 |
| `connect` `:33` | `(project, id)` | 仅项目窗口命令/事件；走 IDE 自己的连接/内省，等待不计入预算（180 s 超时） |

无查询、无 EXPLAIN、无结果集。

`mcp`（`wit/mcp.wit`）：`stdio-command() -> option<list<string>>` `:6`（仅桥接 argv，永不暴露 URL/token）；`mcp-provider.call(tool, arguments, ctx) -> result<string, error>` `:15`（工具名正则 `[a-z0-9_]{1,40}`，注册名 `<plugin-id 规范化>__<name>` ≤64 字节，参数 ≤1 MiB JSON，结果 ≤4 MiB 文本；`destructive=true` 需窗口确认）。

`net`（`wit/io.wit:47-111`）：`fetch(request)` `:96`（默认 30 s，body ≤8 MiB，`{{secret:name}}` 由宿主展开）；`websocket-connect(project, req) -> websocket` `:110`，资源方法 `id/send/drain/close` `:99-105`，≤8 连接，队列溢出即关闭。注意 WIT 注释写响应 ≤8 MiB（`wit/io.wit:72`），而 `docs/zh/plugins/capabilities.mdx:88` 写 16 MiB——以 WIT 为准，差异记入第 7 节。

`secrets`（`wit/io.wit:116-123`）：`set(name,value)` `:119`、`delete(name)` `:121`、`is-set(name) -> bool` `:122`。

`process`（`wit/io.wit:129-216`）：`run(program,args,cwd,stdin)` `:196`、`run-command(cmd,cwd,stdin)` `:199`、`start(cmd,cwd) -> child` `:204`、`run-captured(cmd,cwd,stdin,options)` `:209`（≤128 MiB）、`start-stream(...)` `:215`（背压流式）；`child` 资源 `poll/wait/write/close-stdin/kill` `:176-191`，≤8 个，drop 即杀。环境变量只能追加（同名冲突报错，≤64 项）。

`ipc`（`wit/io.wit:221-234`）：`connect(name) -> connection` `:233`；连接 `write/read/close` `:224-231`，≤8。

`auth`（`wit/io.wit:238-251`）：`begin-loopback(path) -> loopback` `:250`；`redirect-uri()`/`wait(timeout-ms)` `:241-248`。

### 5.6 组六：运行与测试（`run`、`run-targets`、`test-results`）

| 项 | 签名 | 限制 |
| --- | --- | --- |
| `run.start` `wit/run.wit:8` | `(config, args, debug) -> result<string, error>` | 仅窗口命令/事件；debug 需调试器；返回 id 后收 `run-exited` |
| `run-configurations.configurations` `wit/run.wit:24` | `(project-root) -> list<run-configuration>` | 完整列表，≤1000 条/4 MiB |
| `launch-configurations.configurations` `wit/run.wit:64` | 同上，含 `env` 与 `debugger` | 同时导出时优先于上者 |
| `test-results.parse` `wit/run.wit:89` | `(config, chunk) -> list<test-event>` | 失败回退内置解析器 |
| `test-results.select-tests` `wit/run.wit:92` | `(config, tests) -> option<list<string>>` | 不支持返回 none |
| `run-targets.register` `wit/run-targets.wit:33` | `(project, target)` | 需 `process:`+信任+pin；注册不改变选择 |
| `run-targets.register-with-overrides` `:56` | 同上 + 每命令工作目录参数 | 同上 |
| `run-targets.unregister` `:58` | `(project, id)` | 无返回 |

### 5.7 组七：事件、任务、VCS 与构建（`events`、`tasks`、`vcs`、`build`、`commit`）

`events`（`wit/events.wit`）：`event` variant `:18-27`（file-opened/file-saved/editor-focused/action-performed/vcs-changed/scheduled/other）；`subscribe(kind)` `:51`、`subscribe-paths(kind, globs)` `:55`、`unsubscribe(kind)` `:56`。字符串 kind 已支持 `file-closed`、`settings-changed`、`input-mode-changed`、`extensions-changed`、`caret-moved`、`document-changed`、`files-changed`、`problems-changed`、`websocket-ready`、`run-exited`、`terminal-exited`、`content-tab-action`（`:29-56`、`plugins/README.md:1350-1400`；`problems-changed` 见 SDK `crates/ide-plugin/src/types.rs:215`）。`document-changed` 只对 `subscribe-paths` 显式路径投递，需 `project.read`+`editor.read`（`plugins/README.md:1354`）。

`tasks`（`wit/events.wit:63-73`）：`begin(title, cancellable) -> task` `:72`；`report(done, total: option<u32>, message: option<string>)` `:66`；`is-cancelled() -> bool` `:68`。

`vcs`（`wit/workspace.wit:188-245`）：`log` `:215`（≤1000）、`blame` `:218`、`diff` `:221`（≤4 MiB）、`status` `:224`、`remotes` `:226`、`roots` `:228`（1.16）、`repository-at` `:241`（1.16）、`fetch` `:244`。均需信任（多数）。

`build`（`wit/workspace.wit:249-364`）：`dependencies(project, module) -> list<dependency-node>` `:306`，≤100,000 节点，需 `build.read`+信任。

`commit`（`wit/commit.wit`）：`pending-commit()` `:45`、`set-commit-message(revision, message)` `:50`。

### 5.8 组八：宿主基础（`log`、`ui`、`storage`、`output`、`command-runner`、`language-servers`、`extensions`、`file-actions`、`endpoints`）

| interface | 函数清单 | 备注 |
| --- | --- | --- |
| `log` `wit/host.wit:4` | `log(level, message)` `:7` | 带插件 id |
| `ui` `wit/host.wit:10` | `show-notification` `:95`、`notify(...,actions)` `:99`、`notify-project` `:105`、`set-status-item` `:107`、`remove-status-item` `:108`、`set-window-status-item` `:111`、`remove-window-status-item` `:113`、`set-status-item-menu` `:119`、`set-window-status-item-menu` `:121`、`set-console-rules` `:132`、`set-context` `:138`、`remove-context` `:140`、`copy-to-clipboard` `:142`、`read-clipboard` `:157`、`open-url` `:159`、`pick` `:164`、`pick-directory` `:167`、`pick-file` `:179`（1.16）、`pick-many` `:181`、`input` `:184`、`input-multiline` `:188`、`confirm` `:191`、`locale` `:196` | 通知 ≤3 按钮；状态栏菜单 ≤64 项；控制台规则 ≤100 条 |
| `storage` `wit/host.wit:202` | `get` `:205`、`set` `:206`、`delete` `:207`、`keys(prefix)` `:209` | 64 MiB |
| `output` `wit/host.wit:249` | `append(channel,text)` `:252`、`clear(channel)` `:254`、`reveal(channel)` `:256` | 32 通道，1 MiB 环形 |
| `command-runner` `wit/host.wit:213` | `execute(id, arg)` `:218` | 嵌套 ≤8 层，不可重入等待实例 |
| `language-servers` `wit/host.wit:222` | `request(project,server,method,params)` `:242`、`notify(...)` `:245` | 只允许服务器自有方法 |
| `extensions` `wit/extensions.wit:6` | `contributions(point)` `:20` | 只有声明该点的插件可读 |
| `file-actions` `wit/file-actions.wit:8` | `code-actions(doc, selection)` `:74`、`resolve(doc, action)` `:79` | 操作+编辑一次性工作区编辑 |
| `endpoints-provider` `wit/endpoints.wit:4` | `endpoints(ctx)` `:25` | 项目 HTTP 路由 |

## 6 我方功能域 → API 映射与档位

档位判断只基于本章测绘；实现方案细化见后续分册。

| 我方功能 | 可用 API 面 | 关键缺口 | 档位 |
| --- | --- | --- | --- |
| Wrapper 渲染（Lambda/字符串条件链） | 无 AST，只能 `document`+`symbols`+自写解析 | Java 语义/类型推导；`lsp` 不能发 `textDocument/*` | 变通可做（有限形态）/ 全面需上游 |
| XML 动态 SQL（if/foreach/choose/include） | `document`+`injections`+自写 XML 解析（官方 mybatis 已示范） | 无 XML AST，但结构简单可自解析 | 现成可做 |
| 注解 SQL（@Select 等） | `providers`/`language-features` + 自写字符串扫描（官方 mybatis 0.3.0 已做） | 拼接常量/多行文本块需自研 | 现成可做 |
| JPA / JdbcTemplate | 同注解路径，需自写方法名派生查询解析 | 无类型解析，实体字段靠约定 | 变通可做 |
| SQL 日志还原 | `views.open-log-tab`/`output`/`ui.set-console-rules`/剪贴板读 | 无 MyBatis 运行时钩子（除 javaAgent） | 现成可做（文本侧） |
| 方言校验 | 纯计算 + `diagnostics.publish` | 无 SQL 解析器宿主能力（需自带） | 现成可做（自带解析器） |
| 性能建议 | 纯计算 + `diagnostics` | 同上 | 现成可做 |
| EXPLAIN | `process`（CLI）或 `net`（网关） + `database.connect` 仅建连接 | 无查询 API；驱动清单仅 6 种 | 变通可做 / 部分需上游 |
| 写操作安全预检与回滚计划 | 纯计算 + `ui.confirm` + `views` | 无事务/会话访问 | 现成可做（静态预检） |
| Mapper↔XML 双向导航 | `document-indexer`（definitions/references/aliases/markers）+ `providers.definition`/`line-markers` | 无类型级重载区分；跨模块定位靠 glob/search | 现成可做（名字级）/ 变通可做（增强索引） |
| gutter 图标 | `line-markers`（别名/注册图标/包内 SVG）、`gutter-actions`、indexer `markers` | 无「让位」机制 | 现成可做 |
| inlay hints（SQL 列名/参数） | `inlay-hints-provider`（可见范围、≤10,000、50 ms） | 每次整文档文本、无增量；需自建缓存 | 变通可做 |
| SQL 预览工作台（多面板/JSON/复制/导出） | `views`（group 多标签、table/viewport、html 沙箱、content-tab、diff）、`ui.copy-to-clipboard`、`ui.pick-file`(1.16)/`input`+`project.write` | 无原生富控件；导出需高风险写能力；1.15 无 pick-file | 现成可做（形态需重设计） |
| 参数面板与实体字段推导 | `settings` + `views.form` + 自写约定推导 | 无类型解析 | 变通可做 / 需上游 |
| 11 个 MCP 工具 | `contributes.mcpTools` + `mcp-provider.call` | 工具内非用户命令；无 SQL 执行；5 s 预算 | 现成可做（9 个纯计算）/ 2 个需外置（按我方工具清单判断，见 08） |
| Java agent SQL 捕获 | `[[contributes.javaAgents]]` + `java.agent` | 仅 Application/Spring Boot/JAR；非测试/Gradle/远程 | 现成可做（受限场景） |
| 约 40 项设置 + 双语 | `contributes.settings`（schema/pages/groups/lists/tables）+ `locales/<tag>.json` + `ui.locale()` | 运行时自拼文本需自建目录 | 现成可做 |
| 通知 | `ui.show-notification`/`notify`（≤3 动作）/`notify-project` | 无持久通知中心 | 现成可做 |

## 7 未解决 / 需上游确认

| 编号 | 问题 | 现状证据 | 需要什么 |
| --- | --- | --- | --- |
| Q1 | 是否有任何途径取得 Java 类型信息（方法返回类型、泛型实参、实体字段） | `lsp` 禁 `textDocument/*`（`wit/host.wit:231-236`）；`symbols` 只有名字/种类/容器/位置（`workspace.wit:34-40`）；README 明示推迟到 2.0（`plugins/README.md:914-928`） | 上游新增「结构化符号/类型查询」interface，或允许 `lsp:textDocument/*` 白名单 |
| Q2 | 插件能否执行 SQL / EXPLAIN | `database.wit` 无查询函数 | 上游新增查询 interface（含结果集与方言），或确认永久外置 |
| Q3 | `database` 驱动清单是否会扩（达梦/金仓/瀚高/OceanBase） | `database.wit:8-11` 六种 | 上游确认扩展计划 |
| Q4 | 插件侧线程/并发是否被禁止，还是仅未暴露 | 文档只描述单 actor 串行（`architecture.mdx:86-88`） | 上游明确条款 |
| Q5 | `view-provider.render` 的时间预算 | 文档只给命令/provider 预算，未单列视图 | 上游确认（按 5 s 推断） |
| Q6 | MCP 工具调用能否使用 `editor.open-editors` 等「仅命令/事件」API | `plugins/README.md:1234-1292` 仅说「不是用户命令」 | 上游逐函数白名单 |
| Q7 | 远程项目中 `problems` 为空、项目插件不可用，是否有路线图 | `wit/io.wit:9-13`、`architecture.mdx:94-98` | 上游 roadmap |
| Q8 | `decorations` 的 Markdown 悬停（1.16）何时进入发行版 | 1.16 仅在 main；本机 0.1.51 为 1.15 | 上游发行节奏 |
| Q9 | 文档站 `api` 页版本陈述滞后（1.7.0/1.6.0）是否已知 | 线上核对（第 1.2 节） | 上游文档修正（不阻塞） |
| Q10 | `plugin.toml` 是否支持 `[[contributes.providers]]` 同一 kind 多语言上限（1-64 项）之外的分组声明 | `docs/zh/plugins/guides/language-features.mdx` 声明 1-64 项 | 上游确认（当前 2 种语言够用） |
| Q11 | `document-indexer` 是否能拿到 XML 的 `symbols`（宿主语法支持范围） | `wit/indexing.wit:22-28` 只说「宿主语法支持找到的符号」；XML/Java 是内置语言（`languages/xml.toml`、`languages/java.toml` 存在） | 实测宿主对 XML 返回哪些符号（待验证） |
| Q12 | 1.15 上导出文件的推荐路径 | 无 `pick-file`；只能 `input()`+`project.write`（高风险） | 上游是否回移 `pick-file` |
| Q13 | `net.fetch` 响应上限到底是 8 MiB 还是 16 MiB | `wit/io.wit:72` 写「At most 8 MiB」，`docs/zh/plugins/capabilities.mdx:88` 写「响应上限 16 MiB」 | 上游澄清并以 host 实现为准 |
