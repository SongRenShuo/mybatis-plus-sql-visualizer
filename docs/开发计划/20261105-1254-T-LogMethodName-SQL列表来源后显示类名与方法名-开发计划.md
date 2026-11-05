# T-LogMethodName：SQL 列表来源位置后显示「类名#方法名」（2026-11-05）

- 立项：2026-11-05 12:54
- 目标分支：`master`（立项时 tip `6491b4b4`；建树时已前进到 `26604b7d`，本册分支自后者切出）
- 实施分支：`t-log-method-name`；工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-lmn`
- 需求原文：插件 SQL 日志新增一个配置，默认关闭；打开后在 SQL 列表显示时方法名跟到类名后面，参考 IDEA 原生显示格式，`#` 后面是方法名
- 需求归类：显示层开关（不改捕获、不改落盘、不检索）

## 一、需求定案（用户 2026-11-05 拍板）

| 维度 | 定案 | 说明 |
|---|---|---|
| 显示形态 | **并排两段**：`[UserMapper.java:41] [UserMapper#selectById]` | 来源文件:行号原样保留，方法名作为紧跟其后的独立片段；位置与实际不符时也不会串类 |
| 方法名来源 | **只用现有捕获数据**（`LogSqlEntry.mapperStatement`） | 不扩展注入拦截器标记；控制台通道拿到的就是 mapper 语句 id，语义按「SQL 属于哪个 mapper 方法」呈现 |
| 生效范围 | SQL 日志页 + SQL 历史页 + 「最近执行」弹窗 | 三处共用 `RailCellRendererBase` 渲染基类，一处改动全部跟随 |
| 无方法名时 | 保持现状（只显示来源文件:行号，或「MP 分页计数」） | Hibernate / JdbcTemplate / ShardingSphere / MCP / 旧日志文件都是这个形态 |

「IDEA 原生格式」在本次仅约定 `Class#method` 这一形态（与插件内部既有口径一致：`CallerMethodRangeResolver.label`、`FocusTab.Method.label` 都是 `$className#$methodName`）。

## 二、现状与证据

### 2.1 头部行的构成与渲染链路

一行条目由「头部元信息行 + SQL 行 + 可选错误行」组成，头部字段序为用户 2026-10-23 定案、2026-10-26 复核的口径：

```
#序号 · 时间 · 耗时 · [目标库] · [MCP] · (影响行数) · ⨉去重 · 同结构 N 条 · [完整文件名:行号]
```

| 环节 | 位置 | 事实 |
|---|---|---|
| 头部行绑定 | `log/RailCellRendererBase.kt:211`（`bindHeaderLine`），缓存分支 `:219-227` | 静态段 + 动态徽章段 + 来源段拼接后做宽度裁剪 |
| 来源段入口 | `log/SqlEntryPresentation.kt:118`（`headerSourceFragments`） | 单条 `formatSourceFragment(entry, false)` 包成 `SqlTextFragment` |
| 来源片段实现 | `log/LogEntryRendering.kt:299-310` | 取 `sourceFile` 文件名 + `:sourceLine`，输出 `[UserMapper.java:41]`；`SourceMarkers.isMpPaginationCount` 时输出 `[MP 分页计数]` |
| 片段缓存 | `log/SqlEntryPresentation.kt:28-45`（`HeaderStaticCacheKey`）+ `LogSqlModels.kt:239-260` | 来源段与静态段共用 `cachedHeaderKey`；键含设置值，任一维变化即重算 |
| 设置变更刷新 | `log/SqlLogPanel.kt:758-777` | `SETTINGS_CHANGE_TOPIC` → `applyListCellHeight()` + `list.updateUI()` + `refreshList()`；历史页 `repaintSqlList()` |

结论：**新片段加在 `headerSourceFragments` 里即可**，渲染器、缓存、设置刷新、速度搜索（`SpeedSearchUtil.applySpeedSearchHighlighting`）全部自动跟随。

### 2.2 数据面：`mapperStatement` 的两条通道语义不同

字段定义 `log/LogSqlModels.kt:140`：`mapperStatement: String?`，注释写明「mapper 语句 id（如 `com.example.mapper.UserMapper.selectById`）」。

| 通道 | 产出点 | 实际形态 | 与 `sourceFile` 的关系 |
|---|---|---|---|
| 控制台桥（注入拦截器） | `log/SqlLogConsoleBridge.kt:1169`（标记第 4 段） | `com.example.mapper.UserMapper.selectById` | **不同类**：`sourceFile` 是调用点（Service/Controller），`mapperStatement` 是 mapper 接口 |
| 增强捕获（agent） | `log/agent/AgentEventAdapter.kt:57-59`（`event.methodKey`） | `DeviceInfoServiceImpl#joinRightAlias` | **同一个类**：methodKey 就是源码位置的那个栈帧 |
| 无拦截标记（Hibernate/JdbcTemplate/ShardingSphere） | — | `null` | 无方法名可用 |

注入拦截器的标记格式见 `docs/instrumentation/SqlSourceInterceptor.kt:1-20` 与 `emitSource`（`ms.getId()`），第 4 段就是 `MappedStatement.getId()`；`log/SqlSourceMarker.kt:18-57` 解析，旧 3 段标记仍兼容。

**这就是为什么显示形态选「并排两段」**：控制台条目里 `[UserServiceImpl.java:41]` 的类与 `UserMapper#selectById` 的类本来就不是同一个，硬拼进同一片段会造出「UserServiceImpl#selectById」这种不存在的成员。

### 2.3 落盘与读回（决定历史页是否天然生效）

- 写：`log/SqlLogParamCodec.kt:148`（`pd` 载荷键 `MAPPER_FIELD`，非空才落键）
- 读：`log/SqlLogFileReader.kt:361`（当前格式）、`:384`（旧格式）
- 去重融合保留：`log/LogSqlModels.kt:468`（`existing.mapperStatement ?: incoming.mapperStatement`）

结论：**历史页与重启后的读回条目不需要额外改动**，`mapperStatement` 已在环路上。旧日志文件没有该键 → 无片段，行为与开关关闭一致。

### 2.4 设置面与既有守卫

以同族显示项 `showDurationInLogList` 为参照，一个布尔开关的触点固定为：

| 触点 | 位置 |
|---|---|
| State 字段 | `config/PluginSettings.kt:381`（`var showDurationInLogList: Boolean = true`） |
| 复选框字段 | `config/PluginSettingsPanel.kt:641`（`internal val logShowDurationCheck = JBCheckBox()`） |
| 面板行 | `config/PluginSettingsPanelSections.kt:414-418`（`SECTION_SQL_LOG` 内） |
| `isModified()` | `config/PluginSettingsPanel.kt:1244` |
| `apply()` | `config/PluginSettingsPanel.kt:1376` |
| `reset()` | `config/PluginSettingsPanel.kt:1579` |
| 搜索索引 | `config/PluginSettingsFolderConfigurable.kt:549`（`buildSearchableFields`），SQL 日志段 `:587-613` |
| 双语文案 | `resources/messages/MpSqlVisualizerBundle.properties` / `_zh.properties`（`settings.log.*`） |

两条硬约束，实施时不能绕：

1. **新开关必须登记进 `buildSearchableFields()`**。`SettingsPanelSearchableGuardTest.kt:173` 的白名单注释写明「新增开关禁入④」——`UNINDEXED_SWITCH_KEYS`（`:214-224`）是既有缺口台账，把新键塞进去等于伪造已登记。
2. **复选框计数守卫要同步**。`PluginSettingsPersistenceTest.kt:218` 断言「复选框总数应为 47」，新增一个即 48，并要在上方注释链补一行来源。

`SettingsIO` 不纳入：与 `logTimestampEnabled` / `showDurationInLogList` 同族显示项一致（导出面是功能配置子集，见 `config/SettingsIO.kt:1-20` 的定位说明）。

## 三、方案

### 3.1 片段产出

在 `LogEntryRendering.kt` 新增（与 `formatSourceFragment` 相邻，保持「文件级 internal + 直接返回 `Pair<String, SimpleTextAttributes>`」的既有形态）：

```
internal fun formatMethodFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>?
```

行为：

1. 读 `PluginSettings.getInstance().state.showMethodInLogList`，关闭 → `null`（零成本短路）。
2. `mapperStatementLabel(entry.mapperStatement)` 归一化，`null` → `null`。
3. 输出 `"[$label] "` + 与来源片段相同的属性（未选中 `LIST_META_GRAY`，选中走 `REGULAR_ATTRIBUTES`，与 `formatSourceFragment` 完全一致；选中态最终由 `bindHeaderLine` 的 `selectedAttributes` 覆盖，片段层只需给非选中态）。

组合点两处（渲染器实际用第一处，第二处是历史遗留口径文档入口，一并跟随避免两套语义）：

- `SqlEntryPresentation.headerSourceFragments`（`:118`）：`[source] + [method]`
- `SqlEntryPresentation.metadata`（`:174` 之后）：来源片段后追加方法片段

### 3.2 归一化规则 `mapperStatementLabel`

| 输入 | 输出 | 说明 |
|---|---|---|
| `com.example.mapper.UserMapper.selectById` | `UserMapper#selectById` | 桥通道；最后一段是方法名，倒数第二段起取简单类名 |
| `DeviceInfoServiceImpl#joinRightAlias` | `DeviceInfoServiceImpl#joinRightAlias` | agent；原样（先判 `#`） |
| `com.example.mapper.UserMapper#selectById` | `UserMapper#selectById` | 混写形态兜底：`#` 前若带包名则取简单类名 |
| `com.example.Outer$Inner.selectById` | `Outer$Inner#selectById` | 内部类原样保留 `$` |
| `null` / `""` / `"   "` | `null` | 不产出片段 |
| `selectById`（无 `.` 无 `#`） | `null` | 判不出类名，宁可不显示 |
| `com.x.UserMapper.`（方法段为空） | `null` | 同上 |
| `#selectById`（类名为空） | `null` | 同上 |

实现要点：先按 `lastIndexOf('#')` 判 agent 形态，没有再按 `lastIndexOf('.')` 拆 FQN；类名统一走 `substringAfterLast('.')`；任一段 `isBlank()` 即返回 `null`。纯字符串操作，无正则、无 PSI、无服务查找。

### 3.3 为什么来源类与方法名的类常常不一致（不合并的依据）

`sourceFile:line` 与 `mapperStatement` 是两个不同的事实：前者是**谁调用了这次执行**（`SqlSourceInterceptor.locateSource` 取第一个非框架帧），后者是**执行了哪条 mapper 语句**（`MappedStatement.getId()`）。控制台通道里「不一致」是常态，一致才是特例。

| 场景 | 来源类 | 方法名的类 | 说明 |
|---|---|---|---|
| Service / Controller 直接调 mapper | `OrderServiceImpl.java:41` | `UserMapper#selectById` | 最常见，绝大多数行都是这个形态 |
| 用 MP 的 `IService` 内置 CRUD | `OrderServiceImpl.java:88` | `UserMapper#insert` | `saveBatch`/`getById` 的实现整个在 `com.baomidou` 包里，被当框架帧跳过；mapper 方法用户根本没写过 |
| 自有封装：基类 Service、通用 Dao 工具、数据权限/多租户拦截器 | `BaseServiceImpl.java:63` | `UserMapper#selectList` | 封装类在用户自己的包里，不在 skipped 前缀表内，不会被跳过 |
| Service 被 Spring AOP/CGLIB 代理 | 可能落到 Controller 或更上层 | `UserMapper#xxx` | CGLIB 生成类通常没有 SourceFile 属性，`frame.fileName == null` 被 `continue` 跳过，位置往上飘一层 |
| Mapper 的 default 方法内部再调另一个 mapper | `UserMapper.java:12` | `OtherMapper#selectOne` | 来源是接口自身，语句 id 是委托的那条 |
| 异步 / 线程池 / 定时任务里执行 | `UserService$1.java:57`（lambda 所在文件） | `UserMapper#insert` | 栈上只有任务类，业务入口不在栈里 |
| XML 语句 | `OrderServiceImpl.java:41` | `UserMapper#selectByCondition` | namespace 必须等于接口 FQN，所以方法名的类仍是 mapper 接口，与调用点不同 |

增强捕获通道（agent）正常取自同一个栈帧（`StackLocator.java:454` 一次性产 `sourceFile/line` + `methodKey`），类名一致；但「文件名 ≠ 类名」时显示上仍会看起来不一致：

- Kotlin 顶层函数：文件 `OrderService.kt`、JVM 类名 `OrderServiceKt` → `[OrderService.kt:41] [OrderServiceKt#save]`
- 内部类 / 匿名类 / lambda：文件 `OrderService.java`、类名 `OrderService$1`（`simpleMethodKey` 只剥包名，保留 `$`，`StackLocator.java:650-656`）

还有一个反向坑：同名不同包（`com.a.UserMapper` / `com.b.UserMapper`）都显示成 `UserMapper#selectById`，简单类名分不出，排查多模块/多数据源时不能只靠显示。

**结论**：并排两段的读法是「`OrderServiceImpl.java:41` 这一行调用了 `UserMapper#selectById`」。若按「贴到类名后」拼成 `[OrderServiceImpl.java#selectById:41]`，等于给业务类编造一个它没有的方法——这是 §一 选并排两段的直接原因。

### 3.4 设置项

| 项 | 取值 |
|---|---|
| State 字段 | `var showMethodInLogList: Boolean = false`（紧跟 `showDurationInLogList` 之后，同属「日志/历史列表显示项」） |
| 复选框 | `internal val logShowMethodCheck = JBCheckBox()`（紧跟 `logShowDurationCheck`） |
| 面板行 | SQL 日志页「MyBatis 日志」分组内，`logShowDurationCheck` 行之后；`addRow(p, g, "", ...)` 形态（行内主控件带文案） |
| 文案键 | `settings.log.showMethod` / `settings.log.showMethod.tip` |
| 搜索索引 | `SearchableField(b.message("settings.log.showMethod"), b.message("settings.log.showMethod.tip"), "settings.section.sqlLog", l)` |
| 默认 | `false`（关闭时行为与今天逐字节一致） |

文案草案（zh，实施时按仓库风格微调）：

- label：`在来源位置后显示类名与方法名（类名#方法名）`
- tip：`开启后，日志与历史列表在每条 SQL 的来源位置后追加一段「类名#方法名」，如 [UserMapper.java:41] [UserMapper#selectById]。方法名取自捕获到的 mapper 语句 id（增强捕获通道为调用栈业务方法）；没有该信息的条目（Hibernate、JdbcTemplate、ShardingSphere、旧日志）保持原样。默认关闭。`

### 3.5 缓存键

`HeaderStaticCacheKey`（`SqlEntryPresentation.kt:28-45`）新增一维：

```
val methodInSource: Boolean,   // = state.showMethodInLogList
```

`headerStaticCacheKey(...)` 内赋 `state.showMethodInLogList`。**这一维是必需项不是优化项**：来源段跟静态段共用 `cachedHeaderKey`，键不含新设置时，开关切换后已渲染过的条目会一直复用旧片段（`refreshList` 会重绘，但命中旧缓存），表现为「设置改了没反应」。KDoc 与 `SqlEntryPresentation` 头部说明同步补一句。

## 四、改动清单

| 文件 | 改动 | 规模 |
|---|---|---|
| `log/LogEntryRendering.kt` | 新增 `mapperStatementLabel` + `formatMethodFragment`（读开关、属性对齐来源片段） | +30 行 |
| `log/SqlEntryPresentation.kt` | `headerSourceFragments` 组合两段；`metadata()` 跟随；`HeaderStaticCacheKey` 加 `methodInSource`；KDoc 补形态 | +10/-2 行 |
| `config/PluginSettings.kt` | State 新增 `showMethodInLogList`（含 KDoc：默认关、数据来源、通道差异） | +12 行 |
| `config/PluginSettingsPanel.kt` | `logShowMethodCheck` 字段 + `isModified` / `apply` / `reset` 三处 | +6 行 |
| `config/PluginSettingsPanelSections.kt` | SQL 日志页新增一行复选框 | +5 行 |
| `config/PluginSettingsFolderConfigurable.kt` | `buildSearchableFields()` 登记一条 | +1 行 |
| `resources/messages/MpSqlVisualizerBundle.properties` | `settings.log.showMethod` / `.tip`（英文） | +2 行 |
| `resources/messages/MpSqlVisualizerBundle_zh.properties` | 同上（中文） | +2 行 |
| `docs/架构设计/02-核心流程详解.md` | `:524` 的头部字段序与 `:526` 的 `SqlEntryPresentation` 行同步「开关打开时来源段追加 `[类名#方法名]`」 | +2 处 |
| `src/test/.../log/LogMethodNameFragmentTest.kt` | 新建，覆盖 §六 6.1 的归一化与顺序用例 | 新文件 |
| `src/test/.../log/HeaderFragmentCacheTest.kt` | 加一例：开关切换后来源段重算（断言文本变化，不靠计数缝） | +15 行 |
| `src/test/.../log/HistoryCellRendererTest.kt` | 加一例：历史列表同样跟随开关 | +15 行 |
| `src/test/.../config/PluginSettingsPersistenceTest.kt` | 复选框计数 47→48（补注释链）+ 新增 State 往返用例 | +14/-1 行 |
| `docs/索引/开发计划.md` | 顶部登记本册 | +1 行 |

不动的东西（明确边界）：

- `LogSqlEntry` 不加字段；落盘格式、`pd` 载荷、MCP 检索、复制/导出文本全部不变
- 不改 `formatSourceFragment` 的既有语义（默认关闭时输出逐字节不变，存量断言继续有效）
- 不改 `docs/instrumentation/SqlSourceInterceptor.kt` 模板（用户已定「只用现有数据」）
- 不新增 `SimpleColoredComponent`、不新建 Color/Border（渲染热路径纪律）

## 五、待验证前提（实施者先证伪再动手）

- **P-1**：控制台桥通道的 `mapperStatement` 真的是 mapper 语句 id（已由模板源码 `ms.getId()` + 标记格式文档佐证，但需一条真实日志记号或既有用例确认第 4 段不是别的东西）。若实测是别的东西，归一化规则需重定。
- **P-2**：历史页二级列表（`HistoryCellRenderer`）确实经 `RailCellRendererBase.bindHeaderLine` 走同一来源段缓存，而非自己拼头部。若否，历史页要单独接线。
- **P-3**：`HeaderStaticCacheKey` 加维度后，设置切换确实触发重算（写一条用例先红：把新维度临时注释掉，切换开关后文本不变）。
- **P-4**：`metadata()` 的存量断言不因多一个片段而变红。已知 `SqlEntryPresentationTest.test共同元信息顺序与日志独有字段` 用 `last()` 断言来源片段，其夹具无 `mapperStatement` 应不受影响——需实跑确认。
- **P-5**：`buildSearchableFields()` 登记后 `SettingsPanelSearchableGuardTest` 断言一在 zh/en 两语言下都能命中（该守卫按盘上双语 bundle 反解 key，文案改两处必须同步）。

## 六、验收

### 6.1 新增用例

`LogMethodNameFragmentTest`（`BasePlatformTestCase`，沿用设置快照/恢复纪律）：

1. `开关关闭（默认）时不产出方法名片段` —— 断言 `headerSourceFragments` 只有 1 段，且文本与今天一致
2. `桥通道语句id归一化为简单类名与方法名` —— `com.example.mapper.UserMapper.selectById` → 含 `[UserMapper#selectById]`
3. `增强捕获帧形态原样保留` —— `DeviceInfoServiceImpl#joinRightAlias` → 含 `[DeviceInfoServiceImpl#joinRightAlias]`
4. `不可解析的语句id不产出片段` —— null / 空串 / 无点无井号 / 尾点 / 井号空段 五档
5. `开关打开时来源片段顺序为文件位置在前方法名在后` —— 断言 `indexOf("[...java:41]") < indexOf("[...Class#method]")`
6. `MP 分页计数条目只显示来源占位不显示方法名` —— 合成标记 + null 语句 id

配套三例：

7. `HeaderFragmentCacheTest`：开关切换 → 同一条目再次渲染的 `metaTextForTest` 必须变化（覆盖 P-3）
8. `HistoryCellRendererTest`：历史列表同样跟随开关（覆盖 P-2）
9. `PluginSettingsPersistenceTest`：`showMethodInLogList` 出厂默认 false、显式开启后 State 往返保留；复选框总数 48

### 6.2 反向验证（必须做，留两次读数）

- 把 `HeaderStaticCacheKey` 的 `methodInSource` 维临时删掉 → 第 7 例必须红（说明这一维真的在生效，不是摆设）
- 把 `settings.log.showMethod` 从 `buildSearchableFields()` 临时删掉 → `SettingsPanelSearchableGuardTest` 断言一必须红（说明守卫看得见新控件）
- 把 `formatMethodFragment` 的开关短路去掉（恒产出）→ 第 1 例必须红

### 6.3 回归面（定向，不做全量）

```
./gradlew test --tests "cn.song.mybatisplus.visualizer.log.LogMethodNameFragmentTest" \
               --tests "cn.song.mybatisplus.visualizer.log.HeaderFragmentCacheTest" \
               --tests "cn.song.mybatisplus.visualizer.log.HistoryCellRendererTest" \
               --tests "cn.song.mybatisplus.visualizer.log.SqlEntryPresentationTest" \
               --tests "cn.song.mybatisplus.visualizer.log.SqlLogPanelRendererUiTest" \
               --tests "cn.song.mybatisplus.visualizer.config.PluginSettingsPersistenceTest" \
               --tests "cn.song.mybatisplus.visualizer.config.SettingsPanelSearchableGuardTest" \
               --tests "cn.song.mybatisplus.visualizer.config.SettingsRealityGuardTest"
```

全量门禁与构建校验按仓库分层纪律另行派收口代理，实施子代理不跑全量、不 clean、不 `--stop`。

### 6.4 真机复验（用户自做，助手给步骤与判据）

1. 开启开关前：SQL 日志页来源位置只有 `[Xxx.java:41]`，与今天一致
2. 设置 → SQL Visualizer → SQL 日志 → 勾选「在来源位置后显示类名与方法名」→ Apply
3. 已捕获条目**立即**变成 `[Xxx.java:41] [Class#method]`（不需要重开面板；不变化即缓存键维度没生效）
4. 历史页打开同一条历史文件，二级列表同样显示方法名
5. 增强捕获通道的条目显示业务方法（与来源文件同类名）；控制台通道条目显示 `Mapper#方法`
6. 关掉开关 → 立即回到单段
7. 窄侧栏场景：方法名段在末尾被裁成 `…`（已知取舍，见 §七）

失败时要给的日志行：`idea.log` 中 `cn.song.mybatisplus.visualizer.log` 包相关异常；以及该条目的 `sourceFile` / `mapperStatement`（预览窗或历史文件 pd 载荷可见）。

## 七、风险与边界

| 风险 | 判断 | 处置 |
|---|---|---|
| 头部变宽、末尾被裁 | 真实存在：来源段本来就是最后一个片段，宽不足时按 `clipSqlFragmentsToWidth` 顺序截断（用户截图里已出现 `[UserService...]` 被裁） | 默认关闭；开关是显式 opt-in。若真机反馈「开了看不见」，再议把方法段前移或只留方法段（另一轮拍板，本轮不做） |
| 语义误解：控制台条目的方法属于 mapper 而非调用点文件 | 数据限制，非实现缺陷 | 工具提示里写明「方法名取自捕获到的 mapper 语句 id」，避免用户以为方法在被显示的类里 |
| MP 分页 COUNT 条目无方法名 | `SourceMarkers.mpPaginationCount()` 是合成标记，不带 `mapperStatement` | 记为已知边界；后续若需要，可让合成标记带上 `selectPage_mpCount` 语句 id（独立小任务，不在本册） |
| 旧历史文件无 `mapperStatement` | 读回旧格式时该字段为 null | 无片段，行为与关闭态一致 |
| 性能 | 每条目每次设置态变化算一次（进来源段缓存），纯字符串操作 | 无 PSI、无正则、无服务查找；渲染热路径无新增组件与颜色实例 |
| 缓存键膨胀 | `HeaderStaticCacheKey` 多一个 Boolean | 无实际成本（data class，键比较已是逐字段） |

## 八、实施顺序建议

1. 纯逻辑先行：`mapperStatementLabel` + 测试 2/3/4（不碰设置，先钉归一化口径）
2. 接线：`formatMethodFragment` + `headerSourceFragments`/`metadata` + `HeaderStaticCacheKey` 新维度（用例 1/5/6/7）
3. 设置面：State → 面板 → 三向绑定 → 搜索索引 → 双语文案（用例 9 + 守卫）
4. 历史页用例 8 与架构文档同步
5. 反向验证两次读数 + 定向回归；产物交收口代理跑全量
6. 真机步骤交用户执行（§6.4）
