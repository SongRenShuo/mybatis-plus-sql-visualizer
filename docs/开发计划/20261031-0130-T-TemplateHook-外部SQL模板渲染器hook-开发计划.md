# T-TemplateHook —— 外部 SQL 模板渲染器 hook（FR-0045 第 3 项 MVP）

- 任务编号：T-TemplateHook（FR-0045 第 3 项；主册 `20261031-0010-T-Fr45NonMybatisStack-…` §2.3 裁决、§8.3 实施台账）
- 立项日期：2026-10-31（**拍板后径直实施，本册为事后按惯例补立的独立册**）
- 需求来源：官网需求池 FR-0045——项目 67 个 mapper XML / 391 条语句全是自研格式（根节点 `<sqls>`、语句 `<sql id="x">`、FreeMarker 标签、`:命名参数`，`#{}` 与 `<mapper namespace>` 各 0 处），`sqlv_render_sql` 报 `[SOURCE_NOT_FOUND] no mapper statement (select/insert/update/delete) at the given location`
- 分支：`t-fr45-tplhook`（基线 `4cd4ac35`），已并入 `t-fr45-integration`
- 性质：新能力面（MCP 侧）。**只做用户建议的 MVP**——外部渲染器 hook；内置模板 SPI 完整版登记不做

## 一、为什么是这个形态

插件不认自研模板语法，也不该逼作者教它：把「模板文件路径 + sqlId + 参数 JSON」交给**用户自己配的命令**，取回 stdout 当 SQL。作者只需要写一个把模板读进来、算出 SQL 的脚本（在他们项目里本来就是现成的），插件侧零语法知识。这也是用户自己提的两阶段方案里成本可控的那一阶。

`STATEMENT_TAGS` 那条硬编码名单（`{select,insert,update,delete}` + 父级须为 `<mapper>`）是「找不到语句」的直接原因；本次**不动**它，而是在它之前插一个分流——因为把 `sql` 加进那张名单会连带改变 gutter/预览面的行为，超出 MVP 范围（预览/gutter 面接线登记为可选搭车件）。

## 二、方案券

| 券 | 内容 |
|---|---|
| S1 设置服务 | `config/ExternalTemplateSettings`：App 级 `@State`，独立 storage `mp-sql-visualizer-external-template.xml` + `RoamingType.DISABLED`；字段 `enabled=false` / `statementTags=sql` / `commandTemplate` / `timeoutSeconds=10`（钳 1..120）/ `workingDirectory`（空或不存在 → 回落项目根）。**不进 `PluginSettings`**——新能力独立存储，卸载即清 |
| S2 渲染器 | `mcp/ExternalTemplateRenderer`（纯逻辑，不 import 平台类型）：`tokenize` 引号感知切分（未闭合引号/空模板明确失败）→ `substitute` 三个占位符（`paramsJson` 缺省替空串，不塞 `null` 字面量）→ `run` 受控进程：`ProcessBuilder` **列表形态**（绝不 `sh -c`/`cmd /c`）、stdout/stderr 分线程 UTF-8、超时 `destroyForcibly`、stderr 只取首个非空白行截 500 字符、输出上限 1 MiB 且截断标注、`start` 注入缝供假 Process 单测 |
| S3 接线 | `McpSourceLocator.renderAtOffset` 的 XML 分支两挂点：①`STATEMENT_TAGS` 爬升**之前**判 tag 命中用户配置 → 取 `id` → 外部渲染；②链尾兜底 fail 之前再找一遍带 `id` 的祖先；`renderAtLine` 透传 `paramsJson`；`Outcome` 新 `sourceType = "external template (<tag>#<id>)"`；`McpToolErrors` 新增 `EXTERNAL_RENDER_FAILED`；`sqlv_render_sql` 增可选入参 `paramsJson`（不传时与接线前逐字节一致） |
| S4 设置页 | `config/ExternalTemplateConfigurable`（普通 `Configurable`，不继承 `SectionConfigurable`——后者绑定 `PluginSettingsPanel` 的 sections 模型）：开关 / 语句 tag / 命令多行域 / 超时 / 工作目录 + 占位符说明；`groupWeight=11`；中英各 13 键 |
| S5 守卫 | `convention/ExternalTemplateWiringSourceScanTest`：挂点必须早于 `STATEMENT_TAGS` 爬升、挂点前 12 行有 `isActive()` 门、`enabled` 出厂 false、合成样本自证（挂点后置/无门两形态必判红） |

## 三、安全边界（写进了 KDoc 与用户可见的设置页说明）

- 命令在**用户自己的机器上、以用户身份**执行，插件不校验内容、不做沙箱——等价于用户手敲一条命令。这条必须对用户明说，不能靠默认值掩护。
- 只替换三个占位符，其余字符不经 shell 解释（通配符、管道、变量展开都不生效）。
- `paramsJson` 可能含业务参数值：**不进任何日志或错误文本**，只作命令行实参。
- 命令失败/超时/空输出返回 `EXTERNAL_RENDER_FAILED`，**不静默回落** mapper 逻辑——回落会给出「看起来正常但错」的答案。
- 出厂 `enabled=false`：关闭时全链零行为变更、不启动任何进程（守卫钉住）。

## 四、验收与读数

- 定向 **9 类 / 83 例 / 0 失败 / 0 跳过**（Renderer 14 含一条真实进程用例——Windows 走 `cmd /c echo` 实跑未跳过；Settings 7；WiringSourceScan 4；SettingsFolder 11；XmlStructureGuard 22；元守卫 8；BundleParity 5；SettingsRealityGuard 4；SettingsIdeSearchContributor 8）
- 反向验证：摘掉挂点①（只留链尾挂点②）→ `ExternalTemplateWiringSourceScanTest` **4/1 红**，红例「外部渲染挂点必须早于STATEMENT_TAGS爬升」；还原 → 4/0。摘除后**没有**既有用例会红，故本轮补了这条源码级守卫并登记进元守卫
- 触链 `testFast` 4331/0/7；合并后全量 `testShards` 8592/0/0/9；`verifyGate` 3/3 Compatible
- **未闭环**：`runIde` 真机（设置页布局与 apply/reset 交互）；外部命令真机联调（用户需自备脚本）；预览/gutter 面未接线（自研 `<sqls>/<sql id>` 仍不出 gutter 图标）；`<sqls>` 下无 `id` 的 tag 仍回落原 `SOURCE_NOT_FOUND`（无 id 无法传给命令）

## 五、完整版（登记不做）

内置模板适配 SPI（根节点名、语句节点名、动态标签语法、参数占位符可配）——用户建议的「完整版」。不做理由：语法族太多，内置一套必然覆盖不全，反而把「作者不用懂插件」变成「作者得学插件的模板方言」；hook 形态已能覆盖自研模板的全部现实场景，且作者手里本来就有渲染脚本。

**实施台账**：主册 §8.3（改动清单 file:line、反向验证两次读数、i18n 13 键、未闭环项）。
