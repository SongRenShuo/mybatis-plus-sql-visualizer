# T-SqlListNativeVisual：SQL 日志与历史原生高亮及列表布局优化开发计划

- 占号：**O-146 / R96 / D156 / V179**（2026-10-14 14:53 全仓检索未发现占用）
- 日期：2026-10-14
- 基线：`master@16e30bc4`
- 状态：独立工作树（分支 `t-sql-list-native-tail`）**已实施并交付**；权威全量 **7159/0/0/9**（HEAD `c08f2495`）；O-148/O-149 两项后续计划在同分支一并落地；用户真机确认功能无问题，V179 视觉矩阵剩余项待走查（§十四）
- 用户触发：SQL 日志面板和 SQL 历史中的 SQL 高亮颜色过多、层级杂乱，希望接近 SQL 预览编辑器的原生高亮，并进一步优化列表布局
- 关联任务：T-LogListFreeze04、T-ClickPreview、T-ListMode、T-CrudColor、T-PreviewDialectAutoInferMultiModule（O-145）

## 一、结论

可以把 SQL 日志和 SQL 历史的高亮改成接近 SQL 预览的原生观感，但不能把 SQL 预览里的完整 Editor 或 Database 控制台直接嵌进每个列表单元格。

本计划采用两层复用：

1. 高亮层复用 SQL 预览的 `DefaultLanguageHighlighterColors` 语义槽和当前 Editor Color Scheme，使关键字、标识符、字符串、数字、注释随 IDE 配色方案变化；
2. 展示层继续使用 `SimpleColoredComponent` 片段渲染，保住日志列表已经建立的轻量、固定行高和无 HTML 性能底线。

列表定位也从“缩小版 SQL 编辑器”调整为“执行记录概览”：

- 元信息只承担定位和状态，不与 SQL 正文争夺注意力；
- SQL 正文默认一行概览，完整内容由现有单击预览和双击 SQL 预览承接；
- 普通标识符尽量保持中性色，只有语法和异常状态使用强调色；
- 默认不出现横向滚动条，不要求用户左右拖动才能看下一条记录；
- 日志与历史二级 SQL 列表使用同一份展示模型，消除目前两套 Renderer 的顺序和样式漂移。

“原生高亮”的边界需写清：本期目标是插件自管界面跟随 IDEA 配色语义，不承诺在列表中复刻 Ultimate Database 控制台的完整方言 PSI、数据源语义高亮和 inspection。后者依赖 DatabaseTools、方言映射、SQL 文件和控制台生命周期，不适合作为列表 Renderer。

## 二、现状与问题证据

### 2.1 当前存在两套插件自管高亮

SQL 日志、SQL 历史和单击预览使用固定 One Dark 风格：

```text
SqlLexer
  → SqlListSyntaxHighlighter.ATTR_BY_TYPE
  → SqlHighlightColors（固定亮/暗色）
  → EditorColorsScheme
  → SimpleColoredComponent / LexerEditorHighlighter
```

证据：

- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/SqlListSyntaxHighlighter.kt:8-36`
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/SqlHighlightColors.kt:9-41`
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlListFragmentRenderer.kt:43-59`

SQL 预览的自建回退编辑器则使用平台语义槽：

```text
SqlLexer
  → SqlSyntaxHighlighter
  → DefaultLanguageHighlighterColors
  → LexerEditorHighlighter
```

证据：

- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/SqlSyntaxHighlighter.kt:10-46`
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelEditorLifecycle.kt:522-545`

因此同一条 SQL 在日志列表、单击浮层和完整预览之间会换一套颜色。用户截图中日志列表的紫色粗体关键字、洋红表名、蓝色字段、绿色耗时、青色数据库角标、橙色重复徽章与左侧 CRUD 色条同时出现，颜色通道过多；预览编辑器虽然也有语法色，但普通文本和标识符更克制，行号、缩进和留白建立了稳定层级。

### 2.2 “花”不是单一配色问题，而是多层颜色同时发声

一条日志记录当前可能叠加：

1. 关键字、表名、字段、字符串、数字、注释、运算符八类 token 色；
2. 关键字粗体；
3. 耗时快慢色；
4. 数据库、MCP、错误、同结构/N+1 徽章色；
5. SELECT/INSERT/UPDATE/DELETE 左侧色条或淡背景；
6. 列表选中背景；
7. Speed Search 命中高亮。

相关实现：

- SQL token 映射：`SqlListSyntaxHighlighter.kt:25-36`
- 固定颜色：`SqlHighlightColors.kt:21-28`
- 耗时和徽章：`LogEntryRendering.kt:207-289`
- CRUD 标记：`LogEntryRendering.kt:96-143`
- 搜索叠色：`LogEntryRendering.kt:418-425`、`HistorySqlLogPanel.kt:1411-1417`

问题不是某个色值不好看，而是没有区分“结构色、状态色、语法色”的优先级。

### 2.3 “乱”主要来自概览列表承担了编辑器职责

当前默认 `KEYWORD_WRAP` 会把 SELECT、FROM、WHERE、JOIN 等子句拆成多行：

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlListFragmentRenderer.kt:14-31`
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt:650-659`

截图表现为：

- 相邻记录高低不一，浏览时视线不断上下跳；
- 一条 SQL 的关键字反复顶格，粗体高亮形成多个视觉锚点；
- 元信息和 SQL 都横向超宽，底部出现水平滚动条；
- 来源类名、时间、耗时、数据库和徽章挤在一条线上，重要信息没有稳定位置；
- SQL 已被 `summary` 截成首行或最多 160 字，却仍进行关键字换行，既不是完整阅读，也失去概览紧凑性。

摘要证据：

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogSqlModels.kt:56-66`
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogSqlModels.kt:197-199`

### 2.4 日志和历史并未真正共用一套列表展示

两页共用 `LogSqlEntry`、`SqlEntryList`、SQL 分词和单击预览，但列表 Renderer 各写一份：

- 日志：`LogEntryRendering.kt:305-460`
- 历史：`HistorySqlLogPanel.kt:1304-1453`

已有漂移包括：

- 日志页可按设置隐藏耗时，历史页恒显示耗时或 `—`；
- 日志未知来源不显示，历史显示 `[unknown]`；
- 两页元信息片段顺序不同；
- 历史页缺少更新行数、错误和去重信息；
- 历史页注释声称顺序与日志一致，实际并不一致。

本任务不要求把所有业务字段强行做成完全相同，但必须把共同字段的布局、颜色和截断规则收敛到共享 Presentation，差异字段通过显式槽位扩展，不能继续复制 Renderer。

### 2.5 不能直接给列表单元格嵌 Editor

旧版列表仅使用 `JLabel + HTML` 就曾造成约 16 秒 EDT Freeze，现有实现专门改成轻量片段：

- `LogEntryRendering.kt:305-310`
- `HistorySqlLogPanel.kt:1304-1309`
- `SqlLogListFreezeGuardTest.kt:10-26`

完整 Editor 会创建 Document、caret、selection、folding、highlighter、监听器和组件树，还必须显式 `releaseEditor`：

- 单击预览释放：`SqlClickPreview.kt:186-210`
- 主预览释放：`SqlPreviewPanelEditorLifecycle.kt:817-839`
- 最近执行释放：`RecentExecutionsDialog.kt:542-551`

JList Renderer 会在布局、滚动和重绘时反复调用，没有稳定的一行一 Editor 生命周期。直接嵌 Editor 会同时带回冻结、泄漏、焦点和选择语义问题，因此本方案只复用颜色语义，不复用 Editor 组件。

## 三、产品目标

### 3.1 高亮目标

1. 日志和历史列表跟随当前 IDEA Editor Color Scheme，不再使用固定 One Dark 默认色。
2. SQL 预览自建回退编辑器、日志列表、历史列表和单击预览使用同一份 token → `TextAttributesKey` 映射。
3. 默认视觉以平台 `DefaultLanguageHighlighterColors` 为 fallback：
   - 关键字：`KEYWORD`
   - 普通标识符：`IDENTIFIER`
   - 表名/别名：`STATIC_FIELD` 或经真机比较后采用更中性的 `CLASS_NAME`
   - 字符串：`STRING`
   - 数字：`NUMBER`
   - 注释：`LINE_COMMENT`
   - 运算符：`OPERATION_SIGN`
   - 括号：`PARENTHESES`
4. 不再由插件强制把关键字设为粗体；是否加粗跟随用户配色方案。
5. 选中行优先保证可读性：SQL token 使用平台 `selectionForeground`，仅保留字体样式，不在选中背景上继续叠八种前景色。
6. Speed Search 继续使用平台命中高亮，不自建第二套搜索颜色。

### 3.2 布局目标

1. 默认沿用「元信息一行 + 关键字换行 SQL」的概览形态（用户 2026-10-14 追加确认：与以往一致，便于扫读 SQL 大致形态），完整 SQL 交给单击/双击预览。
2. 元信息分三级：
   - 一级状态：错误、慢查询、同结构/N+1 等需要立即处理的信息，可使用强调色；
   - 二级上下文：数据库类型、耗时，使用有限状态色；
   - 三级定位：序号、时间、来源，统一使用弱化前景。
3. 来源文本按可用宽度尾部省略，悬停或现有详情入口显示完整值；不允许长类名撑出水平滚动区。
4. SQL 正文按可用宽度裁切并显示尾部省略号；模型保留完整 `restoredSql`，复制和预览行为不变。
5. 列表滚动区默认关闭水平滚动条，窄工具窗口也只纵向浏览。
6. 现有 `KEYWORD_WRAP` 和 `FORMATTED` 保留为用户可选阅读模式，但不再作为新默认；多行模式设置最大预览行数，超出显示“还有 N 行”，避免单条记录占满视口。
7. 工具栏按“采集与清理 / 检索与过滤 / 展示与排序 / 更多”分组；低频动作进入溢出菜单，窄宽度下不挤压列表。

### 3.3 一致性目标

- SQL 日志主列表和 SQL 历史二级 SQL 列表共享同一 Presentation 和 SQL 行 Renderer；
- 单击预览、最近执行、SQL 屏蔽规则等已经使用 `SqlListSyntaxHighlighter` 的编辑器同步获得新配色，但本期不改它们的布局；
- SQL 预览自建回退编辑器同步使用共享高亮键；
- Ultimate Database 原生控制台和原生 Diff 继续由平台托管，不覆盖其 highlighter。

## 四、范围边界

| 表面 | 高亮 | 布局 | 本期说明 |
|---|---:|---:|---|
| SQL 日志主列表 | 做 | 做 | 本任务主目标 |
| SQL 历史二级 SQL 列表 | 做 | 做 | 与日志共用 Presentation |
| SQL 历史一级文件列表 | 不做 | 可做小幅间距对齐 | 不含 SQL token，不混入本任务 |
| 日志单击预览 | 做 | 不做 | 共享高亮键，保留单实例 Editor |
| SQL 预览自建回退编辑器 | 做 | 不做 | 与列表统一平台语义色 |
| Ultimate Database 原生控制台 | 平台托管 | 不做 | 不替换方言 highlighter |
| 最近执行右侧编辑器 | 做 | 不做 | 共享 highlighter 的自然影响面 |
| SQL 屏蔽规则右侧编辑器 | 做 | 不做 | 共享 highlighter 的自然影响面 |
| 独立 SQL History 工具窗口 | 不做 | 不做 | 用户截图指向 SQL 日志工具窗内的“SQL 历史”页；另一个功能面另立任务 |
| Mapper XML 高亮 | 不做 | 不做 | 由 T-XmlGap 独立治理 |

## 五、方案设计

### 5.1 统一高亮键

新增或收敛为唯一公共映射，例如：

```text
SqlUiHighlightKeys
  KEYWORD
  TABLE
  IDENTIFIER
  STRING
  NUMBER
  COMMENT
  OPERATOR
  PAREN

SqlUiTokenAttributes
  ATTR_BY_TYPE: Map<IElementType, TextAttributesKey>
```

实施原则：

1. `SqlSyntaxHighlighter` 和 `SqlListSyntaxHighlighter` 不再各维护一份映射；
2. 公共键的 fallback 改为平台 `DefaultLanguageHighlighterColors`，不再内置 One Dark RGB；
3. 优先保留已有 `MP_SQL.*` external name，避免已有 scheme 覆写静默失效；旧 `MP_SQL_*` 键是否需要兼容别名列入 V179-2；
4. `SqlHighlightColors` 若失去独立职责则删除，或改为兼容门面，禁止继续保留第三份色值；
5. 本期不新增插件设置页色板。用户希望的是跟随原生配色，不应再造一套十六进制配置；若后续需要插件专属微调，可复用 mapper XML 的 `ColorSettingsPage` 模式另立增量。

### 5.2 缓存语义化

当前多行缓存保存的是已经解析过颜色的 `SqlTextFragment`，缓存键只有显示模式：

- `LogSqlModels.kt:185-195`
- `LogEntryRendering.kt:444-460`
- `HistorySqlLogPanel.kt:1435-1452`

这会导致切换 Editor Color Scheme 后旧条目继续显示旧颜色。

改为缓存与 scheme 无关的数据：

```text
SqlSemanticFragment(text, tokenType)
SqlSemanticLine(fragments)
```

绘制时再使用当前 scheme 将 `tokenType` 转成 `SimpleTextAttributes`。这样：

- 词法分析和多行拆分仍只做一次；
- 主题或配色方案切换不会复用旧 RGB；
- 日志列表、历史列表和 Editor highlighter 共用同一 token 映射；
- 不需要为每条记录保存 scheme 对象或复制整套属性。

如果实测每次 tokenType → attributes 映射仍有热点，可在 Renderer 级缓存“当前 scheme 身份 → 八类 SimpleTextAttributes”，而不是在每个条目缓存颜色。

### 5.3 共享条目 Presentation

新增纯展示模型，避免日志和历史 Renderer 继续复制：

```text
SqlEntryPresentation
  primaryMetaFragments
  statusFragments
  sourceText
  sqlSemanticLines
  fullSql
  statementType
  tooltipText
```

共享层负责：

- 共同字段顺序；
- 时间、耗时、数据库、来源、同结构/错误状态的视觉等级；
- 来源短名和完整 tooltip；
- SQL 显示模式、最大行数和溢出提示；
- 选中态前景策略；
- CRUD 色条与语法高亮正交。

日志独有的更新行数、错误、去重丢弃数通过扩展槽传入；历史没有的数据不伪造占位。历史页 `[unknown]` 与日志页空来源的差异统一为“不显示无意义文本”，完整诊断仍在详情中保留。

### 5.4 紧凑布局

推荐结构：

```text
┌ 3px CRUD 色条 ─────────────────────────────┐
│ #177  13:51:14.893  21 ms  PostgreSQL  同结构 13 条 │
│ SELECT COALESCE(SUM(...), 0) AS chargeDegree …       │
└────────────────────────────────────────────┘
```

细节：

- 序号、时间、来源：灰阶；
- 正常耗时：不额外染绿，可使用普通/弱化前景；只有慢查询和错误升级为橙/红；
- 数据库类型保留一处低饱和角标，不再与每个 SQL token 争色；
- 同结构/N+1 属诊断状态，可保留琥珀色，但同一元信息行最多一个高强调徽章；
- SQL 与元信息之间保留小间距，行间分隔线弱化；
- 默认 SQL 一行，列表固定行高；
- 水平滚动关闭，超宽文本在 Renderer 内按像素宽度截断，不改 `LogSqlEntry.summary` 和复制内容；
- 选中行由平台背景和统一前景表达，CRUD 色条保留，避免“选中后类别信号消失”。

### 5.5 显示模式调整

保留现有三种能力，但调整产品命名和默认值：

| 现有值 | 用户文案 | 行为 |
|---|---|---|
| `KEYWORD_WRAP` | 子句概览 | 最多 3 行；超出显示剩余行数。**默认**（用户 2026-10-14 追加确认与以往一致） |
| `SINGLE_LINE` | 紧凑概览 | 元信息一行 + SQL 一行；用户可选 |
| `FORMATTED` | 格式化展开 | 完整格式化；仅用户主动选择 |

持久化兼容：

- 旧值原样识别，不静默改写用户已保存的显示模式；
- V179-4 收敛为「不迁移」：既然默认回到 `KEYWORD_WRAP`，已持久化该值的用户与默认一致，无需迁移；已选 `SINGLE_LINE`/`FORMATTED` 的用户保持其选择。

### 5.6 工具栏整理

不改动作语义，只调整层级：

1. 采集与清理：捕获、停止/恢复、清空；
2. 检索与过滤：搜索、类型过滤、正则/大小写；
3. 展示与排序：范围、时间顺序、显示模式、移除重复；
4. 帮助、设置、屏蔽管理等动作**全部直达工具栏**，不设自建「更多」弹层——用户 2026-10-14 明确「不要这个更多，之前是侧边栏不够宽时自动折叠、鼠标放上去能看全部，那个交互更好」。

窄宽度折叠完全交给平台 ActionToolbar 自带的溢出（`»`）：动作放不下时自动折入，悬停/点击列出全部；插件不自绘按钮、不建二级分组。

> **交叉引用（2026-10-14 16:15 追加）**：本节只定「分组原则」，**不实施**排序控件的具体归位。用户后续要求把「时间升序」从右侧控制组移入左侧按钮集，该项连同「尾部跟随语义收紧」由 **T-SqlLogTailFollow（O-148）** 负责。两个计划同改 `SqlLogPanel.kt`，实施必须串行（见 O-148 §一 1.3）。

## 六、改动清单

### 6.1 高亮基础件

- `ui/highlight/SqlSyntaxHighlighter.kt`
- `ui/highlight/SqlListSyntaxHighlighter.kt`
- `ui/highlight/SqlHighlightColors.kt`（删除、兼容门面或改为平台 fallback）
- 可新增 `ui/highlight/SqlUiTokenAttributes.kt`

目标：唯一 token 映射、平台语义色、旧键兼容策略。

### 6.2 列表片段与缓存

- `log/SqlListFragmentRenderer.kt`
- `log/LogSqlModels.kt`

目标：缓存语义 token，不缓存具体 RGB；增加宽度感知截断和最大行数裁切的纯函数。

### 6.3 日志与历史 Renderer

- `log/LogEntryRendering.kt`
- `log/HistorySqlLogPanel.kt`
- 可新增 `log/SqlEntryPresentation.kt`

目标：共同字段、视觉等级、选中态、来源截断和 SQL 行布局单源。

### 6.4 面板与设置

- `log/SqlLogPanel.kt`
- `config/PluginSettings.kt`
- `config/PluginSettingsPanel.kt` / `PluginSettingsPanelSections.kt`
- 中英文 bundle

目标：显示模式默认与文案、最大预览行数、水平滚动策略、工具栏分组和设置文案。默认回到 `KEYWORD_WRAP`（用户追加确认），不再做显示模式迁移。

### 6.5 测试

重点修改或新增：

- `SqlSyntaxHighlighterTest`
- `SqlListFragmentRendererTest`
- `SqlClickPreviewTest`
- `SqlLogPanelRendererUiTest`
- `HistoryCellRendererTest`
- `SqlLogListFreezeGuardTest`
- `HistorySqlLogPanelNavigationTest`
- `PluginSettingsPanelBindingTest`
- 可新增 `SqlUiTokenAttributesTest`、`SqlEntryPresentationTest`

## 七、任务波次

| 波次 | 内容 | 交付判据 |
|---|---|---|
| W0 | 锁定平台配色和主题变更 API | 243/253/262 均能解析所用 API；明确旧 key 兼容策略 |
| W1 | 统一高亮键 | 自建 SQL 预览、列表、单击预览对同一 token 返回同一 `TextAttributesKey` |
| W2 | 语义缓存 | 切换 scheme 后旧条目即时使用新色；摘掉失效/解析层测试变红 |
| W3 | 共享 Presentation | 日志/历史共同字段顺序和样式一致，差异字段显式扩展 |
| W4 | 紧凑布局 | 默认固定行高、无水平滚动、宽度感知省略、复制与预览仍使用完整 SQL |
| W5 | 多行兼容与工具栏 | 子句概览最大 3 行、格式化展开保留；工具栏分组和 overflow 完成 |
| W6 | 真机走查与文档 | Light/Darcula、窄/宽窗口、日志/历史、选中/搜索/CRUD 组合均通过 |

## 八、验收矩阵

### 8.1 高亮一致性

1. 对同一条 SQL，日志列表、历史列表、单击预览和自建 SQL 预览中，八类 token 使用同一属性键。
2. 自定义 IDEA Color Scheme 中修改关键字、字符串、数字颜色后，已打开列表无需重启即可刷新。
3. Light、Darcula 和至少一个第三方主题下，普通标识符不成为高饱和视觉主体。
4. 选中行 SQL 使用 `selectionForeground`，CRUD 色条仍保留；搜索命中仍清晰可辨。
5. Community/DatabaseTools 缺席时仍有完整自研词法高亮，不退化为无色文本。
6. Ultimate 原生控制台保持平台 highlighter，不被本插件覆盖。

### 8.2 布局

1. 517px 左右的窄工具窗口下不出现水平滚动条。
2. 默认每条记录固定为元信息一行 + SQL 一行，连续滚动时行高不跳变。
3. 长来源、长 SQL、中文、emoji、代理类名和超长泛型名均按像素宽度安全省略，不切坏 surrogate pair。
4. 复制单条/多条仍复制完整 `restoredSql`，不复制省略号和元信息。
5. 单击预览和双击完整预览仍展示完整 SQL。
6. `KEYWORD_WRAP` 最多 3 行并显示溢出提示；`FORMATTED` 保持完整展开。
7. 日志与历史对同一 `LogSqlEntry` 的序号、时间、耗时、数据库、来源和同结构徽章顺序一致。

### 8.3 状态组合

至少覆盖：

- 普通 SELECT；
- INSERT/UPDATE/DELETE CRUD 色条；
- 慢查询、错误；
- PostgreSQL/Oracle/DM/MySQL 数据库角标；
- 同结构/N+1；
- Speed Search 命中；
- 单选、多选、焦点/失焦；
- 去重开启/关闭；
- 来源未知和超长来源；
- SQL 一行、原始多行、160 字以上长 SQL。

### 8.4 性能

1. Renderer 内不得创建 Editor、Document、PSI 或 HTML 文档。
2. 默认 `KEYWORD_WRAP` 走条目级语义缓存的可变行高路径，不重复词法分析；`SINGLE_LINE` 仍保持固定行高与 O(1) 快路径。
3. 200 条日志连续滚动、搜索和排序无可感知冻结。
4. 历史文件首批 30 条与滚动追加批次不因新 Presentation 增加重复词法分析。
5. 缓存内容不持有 `EditorColorsScheme`、Editor、Project 或 Swing 组件，避免历史条目长期引用 UI 对象。

## 九、反向验证

1. 临时把列表关键字重新映射到固定 One Dark `SqlHighlightColors.KEYWORD`：高亮键同源测试必须红。
2. 临时让 `SqlSyntaxHighlighter` 保留旧 `MP_SQL_KEYWORD` 而列表使用公共键：预览/列表同键测试必须红。
3. 临时把多行缓存改回保存 `SimpleTextAttributes`：切换 scheme 后旧条目颜色测试必须红。
4. 临时恢复选中态 token 多色：选中前景一致性测试必须红。
5. 临时恢复默认 `KEYWORD_WRAP` 或可变行高：固定行高/默认布局测试必须红。
6. 临时开启水平滚动：窄窗口滚动策略测试必须红。
7. 临时让日志和历史分别拼元信息：Presentation parity 测试必须红。
8. 临时在 Renderer 中引入 `EditorFactory.createEditor`、`<html>` 或 `BasicHTML`：冻结守卫必须红。

## 十、待验证前提 V179

| 编号 | 待验证项 | 成功判据 | 失败时处置 |
|---|---|---|---|
| V179-1 | 用户截图中的 SQL 预览实际走自建回退 Editor 还是 Database 原生控制台 | runIde 记录 editor 类和 highlighter；视觉目标与代码路径一致 | 以平台语义色为共同最低能力，不承诺逐像素复刻控制台 |
| V179-2 | `MP_SQL.*` 与 `MP_SQL_*` 旧 key 的 scheme 覆写兼容 | 现有用户 scheme 不因统一键静默丢色；三版本行为一致 | 保留旧 key 为兼容别名，下一大版本再清理 |
| V179-3 | Editor Color Scheme 变化监听在 243/253/262 的稳定入口 | Apply 后列表立即 repaint，Editor 自动重高亮，无反射私有 API | 监听 LAF/设置事件并在面板重新可见时刷新；不常驻轮询 |
| V179-4 | 能否区分旧默认 KEYWORD_WRAP 与用户主动选择 | 只迁移未主动设置用户，主动选择不变 | 无可靠证据则不静默迁移，提供一次性推荐切换 |
| V179-5 | `SimpleColoredComponent` 宽度感知省略在 HiDPI/CJK/emoji 下的精度 | 1x/1.25x/1.5x/2x 下不越界、不切坏字符 | 使用平台 text layout/字体度量；不手算字符个数 |
| V179-6 | 工具栏 overflow 在 243/253/262 新 UI 下的行为 | 517px 宽度低频动作进入更多且主动作可见 | 若平台 overflow 不稳定，只做 separator 与现有更多菜单收纳 |

## 十一、明确不做

- 不在 JList cell 内创建 Editor、EditorTextField 或 Database 控制台。
- 不把完整 SQL 方言 PSI 高亮复制到列表。
- 不改 SQL 生成、方言推断、执行 Runner 和数据源选择；这些由 O-145 等任务治理。
- 不改 Mapper XML 高亮和配色页。
- 不删除 CRUD 色条；它是语句类型通道，与语法色正交。默认继续使用色条，不推荐整行背景。
- 不在本期新增八个十六进制语法色设置项；默认跟随 IDEA Color Scheme。
- 不改变复制、导出、去重、屏蔽、跳转和历史文件格式。
- 不顺手修复 `docs/索引/开发计划.md` 的历史相对链接格式；该索引已有统一旧格式，本次只按现状登记新计划。
- 不做尾部跟随（滚底）语义调整，也不做排序控件从右侧控制组到左侧按钮集的归位——这两项由 T-SqlLogTailFollow（O-148）负责。

## 十二、实施纪律与验证范围

- 先落高亮键和语义缓存测试，再改 Renderer，避免“看起来接近”但实际仍有两套键。
- Renderer 改动必须保留 T-LogListFreeze04 的非 HTML、固定行高和批量刷新守卫。
- 主题切换、选中态和多层状态叠加属于视觉问题，单元测试之外必须做 runIde 真机截图对照。
- 实施子任务只跑相关定向测试；全部生产改动结束后再由只读收口位执行一次 `testShards --no-configuration-cache`。
- 本任务不新增依赖、plugin.xml EP、平台反射入口或构建配置；若 V179-3 最终需要新平台 API，补 243/253/262 签名验证后再接线。
- 权威全量后不得继续修改 `src/main`；如真机复验返修生产代码，必须重跑收口门禁。

## 十三、真机走查清单

> 2026-10-14 262 沙盒实测进度（详见 §十四）：已在真机确认——第 2 项（列表与 SQL 预览对同一条 SQL 的配色一致）、第 7 项（历史二级列表配色与日志页一致）、第 9 项（更多菜单可展开、设置入口可达），以及**默认关键字换行**（每条为「元信息一行 + SELECT/FROM/WHERE 三行」，长首行按宽度省略）与**排序按钮位于图标组右侧、紧邻「全部」**。仍待复核：第 3 项 Light 主题对比度、第 4 项三档宽度的省略位置、第 5 项 200 条滚动卡顿、第 6 项状态叠加、第 8 项预览/复制/跳转回归，以及真实 460px 下图标溢出观感；不以 headless 读数代替。

1. Darcula 下对照用户截图：日志列表不再出现紫、洋红、蓝、绿、橙同时抢眼，普通字段回归中性层级。
2. SQL 预览与列表对同一 SQL 的关键字、字符串、数字颜色接近；允许原生控制台因方言语义出现额外差异。
3. Light 主题下关键字、字符串和弱化元信息均有足够对比度。
4. 517px、800px、1200px 三档宽度验证无水平滚动，来源和 SQL 省略位置合理。
5. 200 条日志上下滚动、搜索、切排序、切去重无明显卡顿。
6. 选中、失焦、多选、Speed Search、CRUD 色条、慢查询和错误状态组合不打架。
7. SQL 历史打开大文件并滚动追加时，视觉和日志页一致，无旧色缓存。
8. 单击预览、双击预览、Ctrl+C、多选复制、右键菜单、源码跳转行为不变。
9. 工具栏窄宽度下主要动作不被隐藏，低频动作进入更多菜单。

## 十四、实施记录

- 2026-10-14 14:53：完成需求分析与代码链复核。确认当前“花乱”由固定 One Dark token 色、彩色元信息、CRUD 标记、选中和搜索多层叠加造成；当前“布局乱”由默认关键字换行、可变行高、超宽元信息和水平滚动共同造成。定案为“平台配色语义 + 轻量列表片段 + 紧凑概览 + 单实例详情 Editor”，不在列表单元格中复用重型 Editor。本轮仅新增计划和索引，未修改生产代码、未运行构建测试、未提交。
- 2026-10-14 实施（独立工作树 `mpsv-sql-list-native-tail`，分支 `t-sql-list-native-tail`，基线 `master@803ef689`）：`5d60602b` 建紧凑视口与共享展示基础件（提交时标明未验证）；`9356ac1c` 统一八类高亮键与平台 fallback、保留旧键兼容链，缓存改为语义 token；`baae4645` 两页 Renderer 共享元信息 Presentation、scheme 变化即时重绘、选中态走平台前景、按像素宽度截断（模型/复制/预览仍全文），新配置默认单行、历史/日志禁水平滚动；子句概览最多三行、格式化模式使用完整 SQL。定向 Gradle `test --tests`（SqlEntryPresentationTest、SqlLogPanelRendererUiTest、HistoryCellRendererTest、SqlLogListFreezeGuardTest、SqlListFragmentRendererTest、SqlSyntaxHighlighterTest、PluginSettingsPanelBindingTest）最终 `BUILD SUCCESSFUL`；**反向验证**：临时将 `SqlSyntaxHighlighter` 的 KEYWORD 改回 `MP_SQL_KEYWORD`，`SqlSyntaxHighlighterTest.testEightTokensShareCanonicalKeysAndPlatformFallbacks` 精准红（AssertionError :424）；还原后 `git diff --exit-code` 为零。243/253/262 的 `EditorColorsManager.TOPIC` / `EditorColorsListener` 签名探针已纳入 PlatformSignatureChecker，定向跨版本测试成功。权威全量门禁与 runIde 视觉走查另行回填，本条不宣称已经通过。
- 方案偏离：未新增单独的 `SqlUiTokenAttributes` 文件，直接用现有 `SqlHighlightColors` 作唯一 canonical key 表；排序归位与尾部跟随按 O-148 独立实施。已持久化 `KEYWORD_WRAP` 的用户保留原选择，不作无法判别用户意图的静默迁移。工具栏低频动作收纳由后续 `6a545e3e` 补齐，见下一条实施记录；真机 517px 宽度下的平台溢出效果仍需走查。
- 权威全量门禁（只读收口位，`HEAD=fdfe43b8`，Docker 29.6.2，`testShards --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process`）：Gradle 退出码 0；shard1–5 **7129/0/0/8**，testExternal **24/0/0/1**，合计 **7153/0/0/9**；另 agent 子项目测试 155/0/0。新类入场回读：`SqlEntryPresentationTest` 3、`SqlSyntaxHighlighterTest` 32、同批 O-148 的 `SqlLogTailFollowTest` 4；本轮文档并行回填期间 `src/main` 未改、HEAD 未变。该读数覆盖源代码单元/集成回归，不覆盖 IDE 真机视觉与鼠标输入体感。
- 262 沙盒真机走查（2026-10-14 续轮，推翻了先前的「环境受限」判断）：先用只读进程探针实测 `ZCode`、`GradleDaemon`、沙盒 IDE 三者 `TokenElevation=0` 且完整性级别同为 `S-1-16-8192`，**提权/UIPI 假设不成立**；随后在 `runIde -PgifRecord=true`（IU 2026.2.1）中打开 `mybatis-plus-sql-visualizer-demo`，坐标点击确认 IDE 主窗**可以**接收合成输入（项目树可展开、工具窗可打开、面板按钮可点、`⋮` 更多菜单可展开并进入设置页）。注意 UIA 常把成功的点击误报为「状态未变化」，故一律以截图为准。真机已确认：①默认关键字换行——干净配置下每条为「元信息一行 + SELECT/FROM/WHERE 三行」，长首行按宽度省略；②排序按钮位于动作图标组右侧、紧邻「全部」；③`⋮` 更多菜单可展开且设置入口可达；④列表与 SQL 预览对同一条 SQL 的配色一致；⑤历史二级列表配色与日志页一致。沙盒内残留的屏蔽规则（`已屏蔽 1,137 行`）与按钮几何让「滚动到底部」按钮的单独点击无法隔离判定，故该项改由定向测试驱动真实 `ScrollToEndAction` 端到端验证。仍未做：真实 460px 宽度下图标溢出观感的**修复后**复看、Light 主题对比度与状态叠加组合。
- 权威全量门禁（最终定稿轮，`HEAD=c08f2495`，Docker 29.6.2，`testShards --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process`）：`BUILD SUCCESSFUL in 10m 49s`，退出码 0；shard1–5 + testExternal 合计 **7159/0/0/9**（shard1 1379、shard2 1381、shard3 1626、shard4 1283、shard5 1466、external 24）。目标类全绿：`SqlLogPanelLayoutTest` 15/0、`SqlLogTailFollowTest` 5/0、`SqlEntryPresentationTest` 3/0、`BundleParityGuardTest` 5/0、`SqlLogPanelSortAndSlowQueryTest` 11/0、`SqlLogPanelTypePopupToggleTest` 6/0（后两类是本轮新增/修好的）。工具栏最终形态：方案 A 排列（捕捉 → 清空 → 设置 → 屏蔽管理 → 搜索 → 滚动到底部 → 排序 → 帮助），全部动作直达、无自建「更多」，窄宽由平台溢出折叠；排序用 `RunConfigurations.SortbyDuration` 图标。
- 本轮门禁抓到并修掉一个真实回归：`SqlLogPanelTypePopupToggleTest` 按组件树收集弹窗内全部勾选框、断言数量等于可筛选类型数，新增的「只看慢查询」勾选框打破了该契约（`c08f2495` 修正：先构建内容再按身份剔除该实例，并补「该勾选框必须在弹窗树内」的正向断言，判据未放宽）。教训：给共用弹窗加控件前先查该弹窗的既有结构契约测试。
- 2026-10-14 续轮补齐 W5 工具栏收纳：`6a545e3e` 将捕捉、搜索、滚底、清空保留主栏直达，设置、屏蔽管理、帮助放进平台 `DefaultActionGroup(..., true)` 的「更多」弹层，主栏 separator 分组并由 ActionToolbar 处理不足宽度的溢出。`SqlLogPanelLayoutTest` 补结构/可达性守卫，`HelpDialogTest`/`HelpUiTest` 改为对真实更多子菜单验帮助链；定向四类 `BUILD SUCCESSFUL`，临时摘掉更多入口后新守卫精准红，恢复并复绿。该笔之后的权威全量与 262 真实可见性另行回填，未提前宣称通过。
- 2026-10-14 用户验收（定稿）：用户在自有 IDE 中复看本轮交付——默认关键字换行、排序归位与排序图标、工具栏平台式溢出（无自建「更多」）、页签恒单行——回复「功能没问题了」。据此关闭 O-146 的功能面与 O-149 的窄栏页签形态；**不在本次确认范围**：V179 的 Light 主题对比度、200 条滚动卡顿、多层状态叠加组合，以及真实 460px 极限窄栏下的图标溢出观感（§十三仍列为待走查项，不得据本条宣称视觉矩阵全通过）。
- 2026-10-15 并 master 后复跑权威全量（合并提交 `2d188175`，父 `5089b1f0`+`5ce3608a`，Docker 29.6.2，同命令）：`BUILD SUCCESSFUL in 18m 04s`，shard1–5 + testExternal 合计 **7159/0/0/9**（shard1 1305、shard2 1398、shard3 1489、shard4 1428、shard5 1515、external 24），并打印 `testShards parity: 计划类全集 653 个均有结果 XML`。**同一棵树的首次尝试是红的**（`BUILD FAILED in 15m 24s`）：shard5 的 `Gradle Test Executor 18` 抛 `ClassNotFoundException`，该片 137 个类整片未执行（shard1–4 正常出结果），被报类横跨互不相关的包且全程无编译错误——判为环境性（当时另一会话在 `-dev` 工作树并发跑 gradle，共享 daemon 与 transform 缓存），重跑即复绿。两次读数差异**不指向代码**，留档以免被误读成一次真实回归。
