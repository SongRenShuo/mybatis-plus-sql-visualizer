<!-- Marketplace 自定义页 Markdown 版：整页复制到 JetBrains Marketplace → Documentation → 自定义页编辑器（仅支持 Markdown，不吃 HTML/CSS）。
     同目录 marketplace-custom-page.html 为 IDE 内「帮助」按钮的 JCEF 渲染版（完整样式），两版内容同源、各有渲染器。 -->

# SQL Visualizer

**服务未启，SQL 先知**

在 IntelliJ IDEA 内将 MyBatis-Plus Wrapper 链、XML mapper、注解 SQL、JPA/Hibernate 查询与 JdbcTemplate 字符串 SQL 渲染为可读可执行的真实 SQL，参数值自动回填；跑起来后真实参数与耗时自动捕获（慢查询标红、静态性能建议），一键跳回源码；SQL 一键反向转 Java。

## 为什么选它

- **SQL 来源全覆盖**：Wrapper 链与服务链、MPJ 联表、XML 动态标签、`@Select` 注解 SQL、Spring Data 派生查询、HQL / `@Query`、JdbcTemplate 与自研 DAO 字符串 SQL
- **真实参数一键回填**：`?` 占位符替换为实际值，LIKE 自动拼百分号、按字段类型加引号
- **运行期捕获（默认开启）**：挂载轻量 agent 直接采集真实执行，不依赖日志格式——真实参数、毫秒级耗时、影响行数、执行报错与真实源码位置；慢查询标红并给出静态性能建议，调用栈悬停弹层可逐帧跳转
- **控制台日志（回落通道）**：MyBatis / Hibernate / JdbcTemplate 三种格式自动还原，按线程分桶，精确跳转源码
- **SQL 反向转 Java**：一键生成可直接编译的四种 Wrapper 形态代码
- **JOIN 关系图与执行**：IDE 原生 ER 图渲染（PNG / SVG 导出）；内嵌原生 Database 控制台直接执行 SQL ![Ultimate](https://sql-visualizer.jvtools.cn/badges/tag-ultimate.svg)
- **CRUD SQL 一步直出**：实体类 / 数据库表右键生成，乐观锁与逻辑删除自动适配
- **触发零门槛**：代码左侧 gutter 图标 / 右键菜单 / `Alt+Shift+Q`

> **别的插件给你文本，我们给你可执行的 SQL。**
>
> 编码期静态预览（Wrapper / XML / 注解 / JPA / HQL，无需运行代码）+ 运行期捕获真实参数与耗时 + EXPLAIN 执行计划对照，达梦 / 人大金仓 / OceanBase / openGauss 等 11 种数据库方言

图例：![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) 近期新增 · ![Ultimate](https://sql-visualizer.jvtools.cn/badges/tag-ultimate.svg) 需要 IDEA Ultimate + Database 插件

![Wrapper 链静态预览演示](https://sql-visualizer.jvtools.cn/demo/wrapper-preview.gif)

## Feature Matrix / 功能对照表

### SQL 预览

| 功能 | 触发方式 | 说明 |
|------|---------|------|
| Wrapper → SQL | gutter 图标 / 右键 / `Alt+Shift+Q` | `LambdaQueryWrapper` 等全系 Wrapper 与服务链；`MPJLambdaWrapper` / `MPJQueryWrapper` 联表；Java 与 Kotlin 均支持 |
| XML / 注解 SQL | 语句行 / 注解行 gutter 图标 | mapper XML 全动态标签 + OGNL 条件勾选；`@Select` 等注解 SQL（含 `<script>` 与 Provider） |
| JPA / Hibernate / 更多 ORM ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | gutter 图标 | 派生查询 / CriteriaQuery / Specification / HQL / `@Query`；Querydsl / EasyQuery / Xbatis / MDS 链式查询 |
| JdbcTemplate / 自研 DAO 字符串 SQL ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 调用行 gutter 图标 | 字符串 SQL 实参直接预览；命名参数 `:name` 按名回填；`StringBuilder` 链式聚合 |
| 参数替换 | 面板左侧输入框 | `?` 替换为实际值；LIKE 自动拼百分号，按字段类型加引号 |
| SQL 格式化 | 弹窗内切换 | 多行缩进 / 单行；分页子句按 11 种方言渲染 |
| 多租户 / 数据权限模拟 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 设置页配置 | 对齐 MP 多租户拦截器：自动注入 `AND tenant_id = ?`（列名 / 默认值可配）；数据权限可模拟 WHERE 片段 |
| 字段列表自动折叠 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 设置页配置（默认关） | 超长列清单 / VALUES / SET 折叠为「... N columns」，点击展开 |
| mapper XML 语法高亮与补全 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 自动生效 | mapper XML 语句内 SQL 关键字 / 字符串 / 注释着色，`#{}` / `${}` 分色，`${}` 内与属性值内 OGNL 细着色，表名 / 列名 / 运算符着色；`#` / `$` 敲键自动闭合 `{}`，resultMap `column` 补全列名；动态标签折叠与引用导航（跳转 / 查找用法），颜色设置页可配 |
| 方言引号不匹配告警 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 自动生效 | SQL 标识符用了当前方言不接受的引号字符时给出告警（如 MySQL 方言下 `FROM "user" t`），只报告不改写；判据按各方言实际宽容度定，不确定时降级为带前提的 WARN |
| 参数预设 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 面板参数区「预设」行 | 参数整组另存 / 切换 / 管理（含 OGNL 勾选态与 foreach 权威值）；按声明方法隔离、预设永久绑定方法（改 SQL 不再丢参数）；预设行可折叠 |
| 分页拦截器模拟 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 设置页开关（默认开） | 带 `IPage` 形参的查询按方言追 LIMIT 并并列产出 COUNT 包裹语句；LIMIT 与 COUNT 可分别控制 |
| 执行计划 EXPLAIN ![Ultimate](https://sql-visualizer.jvtools.cn/badges/tag-ultimate.svg)![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 弹窗内「执行计划」按钮 | 按方言提交原生控制台，真实计划与静态性能标注同屏对照；覆盖 11 种方言（含 Oracle / SQL Server 模板），可选结构化渲染（表格 / 可折叠树 + 索引建议） |
| 执行 SQL ![Ultimate](https://sql-visualizer.jvtools.cn/badges/tag-ultimate.svg) | 弹窗内「执行」按钮 | 原生 Database 控制台内嵌，数据源 / Tx / 会话工具栏全保留 |
| JOIN 关系图 | 弹窗内「JOIN 图」tab | IDEA 原生 Diagrams 渲染（Community 降级自绘）；PNG / SVG 导出 |
| 调用栈悬停 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 悬停预览面板调用栈区域 | 调试器风格弹层，优先呈现已采集的业务帧、折叠相邻内部帧；摘要可单击打开，支持逐帧跳转与原文复制；帧数可配置（默认 24，范围 12~64） |

### SQL 日志

| 功能 | 触发方式 | 说明 |
|------|---------|------|
| 自动捕获 | 侧边栏「捕捉」按钮 | MyBatis / Hibernate / JdbcTemplate 三种日志格式；耗时展示与慢查询标记；可配置项目启动即自动监听 |
| 增强捕获 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 设置页开关（默认开启） | 挂载轻量 agent 直接采集 JDBC 真实执行——不依赖日志格式，附目标库、毫秒级耗时与影响行数 |
| 快速预览 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 工具栏「快速预览」打开底部预览区 | 单击日志或历史条目只选中，打开底部预览后可连续查看、编辑与复制；双击仍打开完整预览 |
| 类型颜色标记 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 列表自动生效 / 设置页配置 | 按语句类型着色：左侧色条或整行淡背景，四色可自定义 |
| 显示模式 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 设置页「列表显示」 | 单行 / 关键字换行 / 格式化，跨会话保留 |
| 多选批量复制 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | Ctrl / Shift 多选后右键 | 一次复制全部选中 SQL，分号收尾换行拼接 |
| 历史与源码定位 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 侧边栏「SQL 历史」tab / 双击条目 | 历史标签页常驻，未开启自动保存也可进入；历史自动保存、两级导航；保存目录可自定义且读写同源；条目可钉住、支持正则检索与大小写开关、导出 CSV / TSV / 纯文本 / 可执行 SQL 脚本、两条历史版本对比；精确跳到触发 SQL 的源码行 |
| 去重 / 过滤 | 工具栏 | SQL 类型多选筛选；`Ctrl+F` 搜索；去重 `⨉N` 徽章即时显示 |
| 最近执行记录 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | gutter 图标右键菜单 | 弹框浏览最近 SQL 执行记录，复用日志列表渲染器；右键菜单六件（复制时间戳/屏蔽/送页签/跳转源码）；支持 Ctrl / Shift 多选与批量复制；右侧编辑器默认格式化 SQL、可临时改动后复制 |

![SQL 日志捕获与还原演示](https://sql-visualizer.jvtools.cn/demo/sql-log.gif)

### 反向转换与其他

| 功能 | 说明 |
|------|------|
| CRUD SQL 生成 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 实体类 / 项目视图实体节点 / Database 工具窗表节点右键一步生成 INSERT / UPDATE / SELECT / DELETE；列勾选、乐观锁与逻辑删除自动适配 |
| SQL → Java（`Alt+Shift+J` / 右键） | QueryWrapper / LambdaQueryWrapper / Wrappers / MPJLambdaWrapper 四形态；SELECT / UPDATE / DELETE / INSERT / 多表 JOIN；GROUP BY / HAVING / DISTINCT / FOR UPDATE / UNION 子句；聚合/函数列原文回落；方言宽容（双引号标识符/十六进制/ANSI 类型字面量）；返回类型自由输入（Map/Objs/VO 类名）；生成代码可直接编译 |
| SQL 代码补全 | 预览面板与 mapper XML / 注解 SQL 内 `Ctrl+Space`：表名、列名、`#{}` 参数名、XML 动态标签 |
| 逻辑删除注入 | 实体含 `@TableLogic` 时自动追加 `AND deleted = 值`（值可配，默认 0） |
| MCP 工具（AI agent）![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 内置 MCP Server 十一个只读工具（IDEA 2025.2+）：渲染 SQL / 表结构 / EXPLAIN / 日志还原 / 历史检索 / 回滚预案等；Claude Code / Cursor / AI Assistant 可直接调用，设置页逐工具门控 |
| 一键回滚 SQL ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 预览面板「更多」菜单对 INSERT 推导逆向 DELETE 一键复制；UPDATE / DELETE 走快照查询粘贴回填的人工恢复路径；快照结果支持表格 / 文本双态并可在 CSV 编辑器中打开 |
| 只读旁路 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 预估影响行数、EXPLAIN 与回滚快照查询走只读旁路，不再替换预览编辑器里的 SQL；影响行数结果以顶部横幅展示 |
| AI 上下文与脱敏 ![New](https://sql-visualizer.jvtools.cn/badges/tag-new.svg) | 「复制为 AI 上下文」输出诊断投影（可选含表列名）；展示 / 导出 / MCP 四链可开启选择性脱敏，敏感字面量以 `***` 打码 |
| 检查更新 | 设置页一键检查更新，热替换即时生效免重启；支持 Gitee 抢先体验通道 |
| 分享 | 预览弹窗「分享」按钮：Markdown 表格 / 纯 SQL / Java 代码 / 链接 |

![AI agent 经 MCP 直接调用插件工具](https://sql-visualizer.jvtools.cn/demo/mcp-agent.gif)

*AI agent（Codex）经 MCP 直接调用插件工具——调用与结果在 IDE 内留痕*

## FAQ / 常见问题

### 插件支持哪些 IDE 版本？

IntelliJ IDEA 2024.3 及以上（2024.3 / 2025.3 / 2026.2 全系列）。Community 与 Ultimate 均支持，仅「执行 SQL」功能需 Ultimate + Database 插件。

适配承诺：新版 IntelliJ IDEA 正式版发布后 5 个工作日内完成兼容性验证并发布适配版本（或兼容性说明）；兼容性验证由计划任务每周自动执行（verifyPlugin 三目标 243/253/262），如遇平台破坏性变更导致无法按期适配，在本节与发行说明公示原因与预计时间。

### 为什么 gutter 图标不显示？

Gutter 图标仅出现在目标 SQL 所在行：Wrapper 链末尾调用行、mapper XML 语句行、`@Select` 等注解行、JPA `findByXxx` / `@Query` 方法行。确认光标位于上述位置；如仍不显示，检查设置页「代码左侧显示预览 SQL 图标」是否开启。

### 预览 SQL 与实际运行 SQL 有差异吗？

预览对齐 MyBatis-Plus 运行时语义，仅以下静态求值特性导致文本不同、语义等价：静态 final 常量直接内联；`in(col, 集合变量)` 无法静态展开，渲染为 `IN (?)`（面板输入框可逗号分隔回填）；分页字面量直接计算（`LIMIT 0, 10`）。

### SQL 日志捕获不到怎么办？

依次确认：已点击侧边栏「捕捉」开始监听；日志包含 `Preparing:` / `Parameters:` 格式且输出到运行控制台；混合前缀（`==> Preparing:`）、Hibernate、JdbcTemplate 各自的捕获开关已按需开启；日志未被「移除包含关键字」规则过滤。

### 「跳转到源码」不准怎么办？

精确跳转需应用集成 MyBatis-Plus `InnerInterceptor` 拦截器（打印 `SQLVIS-SOURCE` 标记行，集成示例见插件内置文档）；未集成时自动回退为按表名搜索实体的近似跳转。

### 安装遇到问题？

- **提示 "Remote host terminated the handshake"**：TLS 瞬时中断，不影响安装结果，重试即可；若使用本地 HTTP 代理，可在 `Settings → HTTP Proxy` 切换为「No proxy」后重试
- **提示需安装 'com.intellij.database'**：这是可选依赖（仅「执行 SQL」需要）。Ultimate 用户点「Install」解锁执行能力；Community 用户点「Cancel」跳过，其余功能完全可用

### 反馈问题需要提供什么信息？

请到 [官网需求池](https://sql-visualizer.jvtools.cn/about/feedback) 提交反馈，建议附上：插件版本号（设置页「关于」可一键复制内部构建号）、IDE 版本（`Help → About`）、复现步骤与示例代码、相关日志或截图。

### 运行项目时弹出的「启用增强捕获」确认框是什么？

增强捕获需要向应用进程挂载一个轻量 agent，才能直接采集真实执行（目标库、耗时、影响行数）。首次运行项目时询问一次：同意即生效并记住选择；拒绝则本次不注入、24 小时内不再询问，控制台日志捕获照常工作。弹窗第三个按钮「关闭增强捕获并不再询问」可一键关掉，之后在设置页「增强捕获」分组随时可重新开启。

## Default Shortcuts / 默认快捷键

| 功能 | Windows / Linux | macOS |
|------|-----------------|-------|
| Wrapper 转 SQL 预览 | `Alt+Shift+Q` | `Option+Shift+Q` |
| SQL → Java 反向转换 | `Alt+Shift+J` | `Cmd+Shift+J` |

可在 `Settings → Keymap` 或插件设置页修改。

## Links / 相关链接

- **官网**：[Sql Visualizer](https://sql-visualizer.jvtools.cn)
- **源码**：[GitHub（镜像）](https://github.com/SongRenShuo/mybatis-plus-sql-visualizer) · [Gitee（主仓）](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer)——2026-10-22 起双仓同步，Marketplace 源码链接指向 GitHub
- **问题反馈**：[官网需求池](https://sql-visualizer.jvtools.cn/about/feedback)
- **变更日志**：[CHANGELOG.md](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/CHANGELOG.md)
- **Marketplace**：[JetBrains Plugin Page](https://plugins.jetbrains.com/plugin/33633-sql-visualizer)
