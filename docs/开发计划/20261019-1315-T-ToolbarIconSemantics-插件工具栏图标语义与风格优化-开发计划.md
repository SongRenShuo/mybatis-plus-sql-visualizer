# T-ToolbarIconSemantics 插件工具栏图标语义与风格优化——开发计划

- 立项时间：2026-10-19 13:15
- 基线：`dev` `087c336d`（工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`）
- 状态：已实施（2026-10-19，dev 工作树）。四页签接线完成，定向 8 类 **71/0/0/0**；未提交、未合并、未推送。
- 编号：不预占 O/R/D/V，实施前按台账登记。
- 关联：`T-UiCoord F2`（工具栏单行与动作组顺序）、`T-SqlLogTailFollow`（排序并入动作组）、`T-SqlLogCommunityDialog`（社区入口）、`T-UiNative T-UN-11`（捕捉动作回归面板）；图标风格基线见 `src/main/resources/icons/` 既有资产。

## 一、需求与结论

用户要求：插件工具栏图标不够形象，第一眼看不出作用；需要调研推荐，每个按钮给出几种形式（可复用 IDEA 原始图标，也可自绘 SVG），且必须与现有风格保持一致。

本次按 SQL 日志工具窗工具栏逐项核对后，结论如下：

- 11 个动作里，**5 个存在真实的语义歧义**：捕捉（绿三角读作"运行程序"）、增强暂停（与捕捉形成播放/暂停误读）、屏蔽规则（漏斗与"过滤"抢语义）、停靠预览（与搜索同为放大镜）、滚动到底（与下拉箭头同形）。
- **6 个已经清楚**：清空（垃圾桶）、设置（齿轮）、搜索（放大镜）、排序（降序条）、社区（品牌聊天气泡）、帮助（问号）。
- 歧义不是"画得不好看"，而是**同一个工具栏里出现了两套互相干扰的视觉语言**：一套是"运行/暂停"的传输控制，一套是"列表/过滤/预览"的数据视图控制。
- 推荐做法不是全量重画，而是做一次**语义归位**：捕捉改成"录制"隐喻、增强暂停弱化为灰阶、漏斗让给真正的 SQL 类型过滤、预览改分栏、滚动到底改"列表+向下箭头"。其余按钮保持平台原生，避免为了统一而统一。
- 最终范围不限于 SQL 日志页：用户要求同一工具窗四个页签（SQL 日志、日志转 SQL、SQL→Java、SQL 历史）的图标同步调整。因此本计划把所有出现同一语义的位置收口到同一个图标注册表，避免只改一页后出现"同一动作两种图形"。

## 二、现状盘点

### 2.1 工具栏动作与当前图标

动作顺序见 `log/SqlLogPanel.kt:505-517`，单行布局见 `log/SqlLogPanel.kt:752-800`。

| 序 | 动作 | 代码位置 | 当前图标 | 可见条件 | 第一眼语义 | 判断 |
|---|---|---|---|---|---|---|
| 1 | 捕捉 / 停止 | `SqlLogPanel.kt:1071-1092` | `Actions.Execute` 绿三角 / `Actions.Suspend` 红方块 | 恒可见 | "运行程序" / "停止" | 有歧义 |
| 2 | 暂停 / 恢复增强捕获 | `SqlLogPanel.kt:1100-1120` | `Actions.Pause` 双竖条 / `Actions.Resume` | 方案 B 临时全隐藏（不出现） | "暂停捕捉" | 有歧义 |
| 3 | 清空 | `SqlLogPanel.kt:1122-1132` | `Actions.GC` 垃圾桶 | 恒可见 | "清空/删除" | 清楚 |
| 4 | 设置 | `SqlLogPanel.kt:1152-1163` | `General.Settings` 齿轮 | 恒可见 | "设置" | 清楚 |
| 5 | 屏蔽规则管理 | `SqlLogPanel.kt:1170-1179` | `General.Filter` 漏斗 | 恒可见 | "过滤" | 有歧义 |
| 6 | 搜索 | `SqlLogPanel.kt:824-839` | `Actions.Search` 放大镜 | 恒可见 | "搜索" | 清楚 |
| 7 | 停靠预览开关 | `SqlLogPanel.kt:477-487` | `Actions.Preview` 放大镜+页 | 恒可见 | "放大查看" | 有歧义 |
| 8 | 滚动到底 / 恢复跟随 | `SqlLogPanel.kt:1138-1150` | `General.ArrowDown` 下箭头 | 恒可见 | "展开下拉" | 有歧义 |
| 9 | 排序方式 | `SqlLogPanel.kt:257-272` | `RunConfigurations.SortbyDuration` 降序条 | 恒可见 | "按耗时降序" | 基本清楚 |
| 10 | 加入交流群 | `SqlLogPanel.kt:1181-1189` | 自绘 `communityChat.svg` | 恒可见 | "聊天/反馈" | 清楚 |
| 11 | 帮助 | `SqlLogPanel.kt:1207-1211` | `Actions.Help` 问号 | 恒可见 | "帮助" | 清楚 |
| — | SQL 类型多选 | `SqlLogPanel.kt:240-245` | `General.ArrowDown` + 文本"全部" | 恒可见 | "下拉选择" | 可用但偏弱 |

用户截图里第 2 个按钮可见，说明增强捕获已开启；该按钮在增强关闭时不渲染（`e.presentation.isVisible = enabled`），验收必须覆盖两种状态。

### 2.2 四个页签的图标清单

改图标不能只改一页，否则同一语义会出现两套图形。四个页签的工具栏与菜单动作盘点如下。

#### 2.2.1 SQL 日志（SqlLogPanel）

见 2.1 表。11 个动作 + 右侧 SQL 类型过滤；其中停靠预览、滚动到底、屏蔽规则、类型过滤本计划要改。

#### 2.2.2 日志转 SQL（LogToSqlPanel）

| 工具条 | 动作 | 代码位置 | 当前图标 | 语义 | 本计划处理 |
|---|---|---|---|---|---|
| 输入区 | 转换 | `LogToSqlPanel.kt:188-197` | `Actions.Execute` | 还原 | 保持 |
| 输入区 | 从剪贴板读取 | `LogToSqlPanel.kt:210-219` | `Actions.MenuPaste` | 粘贴 | 保持 |
| 输入区 | 清空输入 | `LogToSqlPanel.kt:221-230` | `Actions.GC` | 清空 | 保持 |
| 结果区 | 复制 | `LogToSqlPanel.kt:232-243` | `Actions.Copy` | 复制 | 保持 |
| 结果区 | 压缩/多行切换 | `LogToSqlPanel.kt:246-256` | `Actions.ReformatCode` | 格式化 | 保持 |
| 结果区 | 执行还原产物 | `LogToSqlPanel.kt:279-289` | `Actions.Execute` | 执行 | 保持 |
| 结果区 | 清空结果 | `LogToSqlPanel.kt:258-270` | `Actions.GC` | 清空 | 保持 |

#### 2.2.3 SQL → Java（SqlToJavaPanel）

| 工具条 | 动作 | 代码位置 | 当前图标 | 语义 | 本计划处理 |
|---|---|---|---|---|---|
| 输入区 | 转换 | `SqlToJavaPanel.kt:344-353` | `Actions.Execute` | 转换 | 保持 |
| 输入区 | 格式化 SQL | `SqlToJavaPanel.kt:356-367` | `Actions.ReformatCode` | 格式化 | 保持 |
| 输入区 | 清空输入 | `SqlToJavaPanel.kt:369-378` | `Actions.GC` | 清空 | 保持 |
| 结果区 | 复制 | `SqlToJavaPanel.kt:380-392` | `Actions.Copy` | 复制 | 保持 |
| 结果区 | 格式化代码 | `SqlToJavaPanel.kt:395-405` | `Actions.ReformatCode` | 格式化 | 保持 |
| 结果区 | 清空结果 | `SqlToJavaPanel.kt:407-418` | `Actions.GC` | 清空 | 保持 |
| 类选择字段 | 浏览 | `SqlToJavaPanel.kt:1835` | `General.Ellipsis`（12px 缩放） | 更多 | 保持 |
| 类选择字段 | 清除 | `SqlToJavaPanel.kt:1836` | `Actions.Cancel`（12px 缩放） | 清除 | 保持 |

#### 2.2.4 SQL 历史（HistorySqlLogPanel）

| 区域 | 动作 | 代码位置 | 当前图标 | 语义 | 本计划处理 |
|---|---|---|---|---|---|
| 导航栏 | 返回 | `HistorySqlLogPanel.kt:211-221` | `Actions.Back` | 返回 | 保持 |
| 导航栏 | 刷新 | `HistorySqlLogPanel.kt:224-233` | `Actions.Refresh` | 刷新 | 保持 |
| 导航栏 | 滚动到底 | `HistorySqlLogPanel.kt:236-250` | `General.ArrowDown` | 滚底 | 改 `ObjectBrowser.SortByType` |
| 导航栏 | 停靠预览 | `HistorySqlLogPanel.kt:193-208` | `Actions.Preview` | 预览 | 改 `Actions.PreviewDetails` |
| 右键菜单 | Diff 对比 | `HistorySqlLogPanel.kt:946-...` | `Actions.Diff` | 对比 | 保持 |
| 右键菜单 | 导出 SQL | `HistorySqlLogPanel.kt:986-...` | `Actions.EditSource` | 导出 | 改 `General.Export`（与 SQL 日志的导出语义统一） |
| 右键菜单 | 复制 SQL | `HistorySqlLogPanel.kt:1081-...` | `Actions.Copy` | 复制 | 保持 |
| 右键菜单 | 复制时间戳 | `HistorySqlLogPanel.kt:1092-...` | `Actions.Copy` | 复制 | 保持 |
| 右键菜单 | 跳转源码 | `HistorySqlLogPanel.kt:1107-...` | `Actions.EditSource` | 跳转 | 保持 |
| 右键菜单 | 跳转 XML | `HistorySqlLogPanel.kt:1142-...` | `FileTypes.Xml` | 跳转 | 保持 |

#### 2.2.5 盘点结论

- 真正需要跨页同步的只有三组：**停靠预览**（SQL 日志 + SQL 历史）、**滚动到底**（SQL 日志 + SQL 历史）、**清空**（SQL 日志 + 日志转 SQL 两处 + SQL→Java 两处）。
- 日志转 SQL 与 SQL→Java 的转换/格式化/复制本身已经一致，不需要为了"看起来统一"去改图标。
- SQL 历史右键菜单存在一处同图标不同动作：`Actions.EditSource` 同时用于"导出 SQL"和"跳转源码"；导出应改用 `General.Export`，与 SQL 日志右键菜单的导出条目对齐（SQL 日志的 `ExportVisibleAction` 目前还没有图标，顺手补上）。
- 全仓 `Actions.GC` 共 7 处（清空类动作），`Actions.Execute` 共 4 处（执行/转换/捕捉类动作）。清空图标本计划不改，避免牵动全仓；捕捉开始虽然也用 `Actions.Execute`，但它在 SQL 日志页是独立按钮，和另外三页的"转换/执行"不在同一视觉上下文。

### 2.3 风格基线（实证）

平台图标分两套，由 IDE 版本决定，插件代码不需要也不应该自己判断：

- 243 经典 UI：16×16，实心几何形，亮色 `#6E6E6E`、暗色 `#AFB1B3`。
- 253/262 新 UI（expui）：16×16，约 1.5px 描边、圆角端点，亮色 `#6C707E`、暗色 `#CED0D6`。
- `AllIcons` 字段在 262 已大量改指 expui 资源（`AllIcons$Actions` 中 173 处 expui 路径），所以**继续复用 `AllIcons` 字段即可自动获得对应版本的风格**，这是比拷贝 SVG 更稳的做法。
- 仓内自绘资产现状：`sqlGutter.svg`（品牌绿 `#4FB477` + 紫 `#B48EFF`，1.6~1.8 描边）、`communityChat.svg`（同配色，1.4 描边），且都提供 `_dark.svg`；`IconLoader.getIcon("/icons/x.svg", cls)` 会自动选暗色变体。

### 2.4 平台候选实证方法

本次没有凭记忆列图标名，做了两步核验：

1. 从本机 243 发行包 `ideaIU-2024.3.7.1-win/lib/app-client.jar` 与 262 安装目录 `lib/intellij.platform.ide.jar` 抽 SVG，逐个在亮/暗背景渲染成对照图（16px 原尺寸放大 4 倍观察）。
2. 用 class 文件字段表比对 `AllIcons$General/Actions/Process/RunConfigurations` 在 243 与 262 的字段存在性，确认候选不是单版本独有。

已确认在 243 与 262 同时存在的字段：`Actions.Execute/Suspend/Pause/Resume/GC/Search/Preview/PreviewDetails/PreviewDetailsVertically/Help/ClearCash`、`General.Settings/Filter/ArrowDown/ArrowDownSmall/Delete/HideToolWindow/InspectionsEye/PreviewHorizontally`、`Profiler.Rec`、`Process.ProgressPause/ProgressResume`、`RunConfigurations.SortbyDuration/Scroll_down`、`ObjectBrowser.SortByType/Sorted`、`Toolwindows.ToolWindowMessages`、`Nodes.Record/SortBySeverity`、`Toolbar.Filterdups`。

同时实测到三个容易踩的坑：

- `Actions.ClearCash` 在 243 没有经典变体（字节码里 expui 路径为 `expui/actions/clearCash.svg`、经典路径参数为 null），在 243 可能直接不渲染，不能拿它做清空。
- `General.ArrowDown` 在 262 渲染为细 chevron（用户截图中第 8 个按钮），在 243 是实心三角；同一字段跨版本形态不同，属平台映射，不是缺陷。
- `General.Settings` 在 243 经典资源是扳手、262 新 UI 是齿轮；用户截图里的齿轮来自 262 映射。跨版本观感差异同样属平台行为。

## 三、目标与非目标

### 目标

1. 让每个按钮在**不看 tooltip 的情况下**能读出作用，重点解决 2.1 表中的 5 处歧义。
2. 同一工具窗四个页签里，同一语义使用同一图形（预览、滚动到底、清空、导出、复制、格式化）。
3. 四个页签的工具栏动作统一走 `MpvsIcons` 注册表，不再各自散落 `AllIcons.X` 字面量；后续换图标只改一处。
4. 亮/暗主题、243/262 两个基线版本都不出现"图标空白、尺寸跳变、颜色突兀"。
5. 只换图标与 tooltip，不改动作行为、不改动作顺序、不改工具栏布局与溢出机制。
6. 自绘图标资产可复用、可测试：新增图标有统一入口，后续不再散落 `AllIcons.X` 字面量。

### 非目标

- 不重排工具栏按钮，不新增/删除动作，不把排序或类型过滤挪回右侧控制组。
- 不改工具窗标题栏动作，不改右键菜单结构；只为已有图标的一致性与导出/跳转语义对齐做最小替换（不新增菜单项、不重排）。
- 不引入图标字体、SVG 滤镜、渐变、阴影；不引入第三方图标库。
- 不为"好看"重画已经清楚的按钮。
- 不修改 `AllIcons` 的平台语义，不做运行期反射替换平台图标。

## 四、逐按钮推荐

每个按钮给出三种形式：A=平台原样复用，B=平台另一字段，C=自绘。推荐列写最终建议；"保持"表示本次不改。

### 4.0 已确认选型（2026-10-19 用户指定）

| 按钮 | 选定图标 | 代码落点 |
|---|---|---|
| 捕捉开始 | `expui/run/run.svg` = `AllIcons.Actions.Execute` | `CaptureAction` 的未捕获分支 |
| 捕捉停止 | `expui/run/stop.svg` = `AllIcons.Actions.Suspend` | `CaptureAction` 的停止分支 |
| 增强恢复 | `expui/run/resume.svg` = `AllIcons.Actions.Resume` | `AgentPauseAction` 的恢复分支 |
| 增强暂停 | `expui/run/pause.svg` = `AllIcons.Actions.Pause`（恢复态的对应对称项） | `AgentPauseAction` 的暂停分支 |
| 清空 | `expui/general/delete.svg` = `AllIcons.Actions.GC` | `ClearAction` |
| 屏蔽规则 | 自绘 `eyeOff.svg`（眼睛 + 斜线；平台无对应字段） | `ManageSuppressAction` |
| 滚动到底 | `objectBrowser/sortByType.svg` = `AllIcons.ObjectBrowser.SortByType` | `ScrollToEndAction`（含历史页） |
| SQL 类型过滤 | `expui/general/filter.svg` = `AllIcons.General.Filter` | `typeFilterButton` |

实现方式（2026-10-19 用户定案）：所有工具栏图形统一克隆为插件自带 SVG，放在 `src/main/resources/icons/toolbar/`，由 `MpvsIcons` 通过 `IconLoader` 加载，不再引用 `AllIcons` 字段。这样 243/253/262 渲染同一套图形；屏蔽规则是唯一自绘项（平台图标集无"眼睛 + 斜线"）。

### 4.1 捕捉 / 停止（有歧义，建议改）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `Actions.Execute`（`expui/run/run.svg`，用户选定） | 绿色三角；虽然通用语义偏"运行程序"，但用户最终选定，与停止态红方块形成"运行/停止"一对 |
| B | `Profiler.Rec`（`expui/profiler/rec.svg`） | 红色圆环 + 红点，录制/采集隐喻更准，但与用户最终选择不同，留作备选 |

推荐：**A（按用户最终选定）**。停止态使用 `expui/run/stop.svg`（`AllIcons.Actions.Suspend`；243 回落经典红方块）。本项不改注册表，捕捉开始/停止继续走 `Actions.Execute` / `Actions.Suspend`。

### 4.2 暂停 / 恢复增强捕获（有歧义，建议改）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `Actions.Pause` / `Actions.Resume`（现状） | 双竖条 + 绿三角，和捕捉按钮组成"播放/暂停"错觉 |
| B | `Process.ProgressPause` / `Process.ProgressResume` | 灰阶、线更细，明显弱于主操作，能表达"次要通道的暂停" |
| C | 自绘"增强通道徽章 + 暂停/播放角标" | 主图形用一个小节点/星形表示增强通道，右下角加 4×4 角标；语义最准，但 16px 下容易糊 |

推荐：**A（保持 `Actions.Pause` / `Actions.Resume`）**。用户已指定恢复态使用 `expui/run/resume.svg`（即 `AllIcons.Actions.Resume` 的 262 映射），暂停态对应用 `expui/run/pause.svg`；因此本项不改注册表，只需在计划里锁定这两个字段。B 的灰阶方案与 C 的自绘角标留作后续可选。

### 4.3 清空（清楚，保持）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `Actions.GC` 垃圾桶（现状） | 通用"清空/删除"，全仓 7 处清空动作一致 |
| B | `Actions.ClearCash` | 243 无经典变体（字节码经典路径为 null），可能不渲染，**否决** |
| C | 自绘扫帚 | "清扫列表"更准，但会与全仓 7 处清空图标产生新的不一致 |

推荐：**保持 A**。用户已指定清空使用 `expui/general/delete.svg`，这正是 `AllIcons.Actions.GC` 在 262 的映射；243 自动回落经典垃圾桶。若后续统一全仓清空语义，再整体换 C，不在本任务里只改一处。

### 4.4 设置（清楚，保持）

推荐：**保持 `General.Settings`**。平台标准齿轮，跨版本形态差异（243 扳手/262 齿轮）由平台负责，不应自绘覆盖。

### 4.5 屏蔽规则管理（有歧义，建议改）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `General.Filter` 漏斗（现状） | 漏斗=过滤；真正的"SQL 类型过滤"就在右侧，语义被抢 |
| B | `General.InspectionsEye` 眼睛 | 平台只有纯眼睛，没有斜线；"隐藏/屏蔽"可读，但不如斜线直接 |
| C | 自绘"眼睛 + 斜线"（用户定稿：expui 眼 + 红斜线） | 复用 expui `inspectionsEye` 的眼睛轮廓，加一条贯穿斜线；平台图标集无现成字段 |

推荐：**C（已定稿）**。平台图标集里没有"眼睛 + 斜线"的字段（`toggleVisibility` 是眼睛+列表，`hide` 是横线，`hidePassed` 是漏斗+彩点），所以这一项必须自绘。眼睛轮廓直接沿用 expui `inspectionsEye` 的路径，保证与邻居同风格；斜线用红色（`DB5860`/`DB5C5C`）。首版按 expui 原尺寸落在工具栏里偏小，实施后整体放大约 15%（眼睛横向 10.94→12.58、纵向 7→8.05，描边 1.4，斜线 1.6），与搜索/漏斗/设置并排时体量一致。

自绘参考（亮色版；暗色把主色换 `#CED0D6`、斜线换 `#DB5C5C`）：

```svg
<!-- 眼睛轮廓取自 IntelliJ expui inspectionsEye.svg（Apache 2.0），斜线为屏蔽语义后加；图形放大约 15% -->
<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
  <circle cx="8" cy="8" r="1.72" stroke="#6C707E" stroke-width="1.4"/>
  <path d="M8.0000 12.0250C5.2670 12.0250 2.5648 10.2367 1.7105 8.0000C2.5648 5.7633 5.2670 3.9750 8.0000 3.9750C10.7330 3.9750 13.4352 5.7633 14.2895 8.0000C13.4352 10.2367 10.7330 12.0250 8.0000 12.0250Z" stroke="#6C707E" stroke-width="1.4"/>
  <path d="M2.250 13.750L13.750 2.250" stroke="#DB5860" stroke-width="1.6" stroke-linecap="round"/>
</svg>
```

### 4.6 搜索（清楚，保持）

推荐：**保持 `Actions.Search`**。放大镜是跨产品通用语义，不需要改。

### 4.7 停靠预览（有歧义，建议改）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `Actions.Preview`（现状） | 放大镜+页，和搜索同为放大镜，第一眼分不开 |
| B | `Actions.PreviewDetails` | 262 映射 expui `previewVertically`（左列表/右预览分栏），243 映射经典 `previewDetails`（实心分栏矩形），两版都是"分栏"语义 |
| C | `General.PreviewHorizontally` | 字段两版都有，但字节码里的资源映射未完全解码，需 runIde 确认后再用 |
| D | 自绘"外框 + 竖分隔 + 左三行" | 与 B 同构，可完全控制线宽与留白 |

推荐：**B**。这是本次收益最高的一处：放大镜只有一个，预览改分栏，两个按钮立刻分开。

### 4.8 滚动到底 / 恢复跟随（有歧义，建议改）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `General.ArrowDown`（现状） | 262 是 chevron，和右侧下拉、菜单展开同形 |
| B | `ObjectBrowser.SortByType`（用户指定） | 262 映射 expui `objectBrowser/sortByType`，243 映射经典 `objectBrowser/sortByType`；形态是"向下箭头 + 两个色块" |
| C | `RunConfigurations.Scroll_down` | 列表行 + 向下箭头落到末行，语义直给；262 映射 expui 版本 |
| D | 自绘"三行 + 双箭头到末行" | 与 C 同构，可加"跟随中"状态色 |

推荐：**B（按用户指定）**。历史页 `HistorySqlLogPanel.kt:238` 同步替换，两页保持一致。

需要提醒：`objectBrowser/sortByType.svg` 的形态是"向下箭头 + 两个色块"，语义更接近"按类型排序"而不是"滚动到底"。如果这条选择的本意是给排序按钮用，请改到 4.9；当前计划按字面执行，把它挂在滚动到底上。

### 4.9 排序（基本清楚，保持）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `RunConfigurations.SortbyDuration`（现状） | 三条递减横线 + 向下箭头，读作"降序排序"，与菜单默认项一致 |
| B | `ObjectBrowser.SortByType` | 用户指定给滚动到底的候选；若实际本意是排序，可移到这里替代 A |
| C | `ObjectBrowser.Sorted` | A→Z，容易被读成"按字母排序"，与时间/耗时语义不符 |
| D | 自绘"时钟 + 上下箭头" | 若菜单后续以时间排序为主，可考虑；当前收益不大 |

推荐：**保持 A**。若 4.8 的 `SortByType` 确认本意是排序，则改为 B。

### 4.10 加入交流群（清楚，保持）

推荐：**保持自绘 `communityChat.svg`**。品牌绿描边 + 紫点，是本栏唯一有品牌色的图标，也是唯一"社交"语义，保留辨识度。可选项：把紫色点改为品牌绿单色以降低杂色，但不建议在本次改动。

### 4.11 帮助（清楚，保持）

推荐：**保持 `Actions.Help`**。问号是通用语义；`General.ContextHelp`（圆圈问号）反而更弱。

### 4.12 SQL 类型多选（偏弱，建议增强）

| 形式 | 图标 | 说明 |
|---|---|---|
| A | `General.ArrowDown` + 文本（现状） | 只有下拉箭头，用户要先读"全部"才知道这是类型过滤 |
| B | `General.Filter` 作为按钮图标（用户指定），文本保留 | 262 渲染 `expui/general/filter.svg`、243 渲染 `general/filter.svg`；漏斗 + "全部"直接表达"过滤条件"，且从屏蔽按钮手里拿回漏斗语义 |
| C | 自绘"漏斗 + chevron"复合图标 | 一个图标同时表达"过滤"和"可展开"；用户未选，留作后续 |

推荐：**B**。按钮显示"漏斗 + 全部"文本，不再保留单独的 chevron；若后续仍想要下拉提示，再考虑复合图标 C。

### 4.13 四个页签的同步调整

#### 4.13.1 跨页统一语义表

| 语义 | 统一图标 | 出现位置 | 处理 |
|---|---|---|---|
| 停靠预览 | `Actions.PreviewDetails` | SQL 日志 `SqlLogPanel.kt:477-487`；SQL 历史 `HistorySqlLogPanel.kt:193-208` | 两处同改 |
| 滚动到底 | `ObjectBrowser.SortByType` | SQL 日志 `SqlLogPanel.kt:1138-1150`；SQL 历史 `HistorySqlLogPanel.kt:236-250` | 两处同改 |
| 清空 | `Actions.GC` | SQL 日志 `ClearAction`；日志转 SQL `ClearInputAction` / `ClearResultAction`；SQL→Java `ClearInputAction` / `ClearResultAction` | 保持，不新增自绘 |
| 转换/执行 | `Actions.Execute` | 日志转 SQL `ConvertAction` / `ExecuteResultAction`；SQL→Java `ConvertAction` | 保持 |
| 从剪贴板读取 | `Actions.MenuPaste` | 日志转 SQL `ReadClipboardAction` | 保持 |
| 复制 | `Actions.Copy` | 日志转 SQL `CopyAction`；SQL→Java `CopyAction`；SQL 历史右键 `CopySql` / `CopyTimestamp` | 保持 |
| 格式化 | `Actions.ReformatCode` | 日志转 SQL `ToggleFormatAction`；SQL→Java `FormatAction` / `FormatCodeAction` | 保持 |
| 导出 | `General.Export` | SQL 历史右键 `ExportSqlAction`（现为 `Actions.EditSource`）；SQL 日志右键 `ExportVisibleAction`（现无图标） | 两处统一为 Export |
| 跳转源码 | `Actions.EditSource` | SQL 历史右键 `JumpToSourceAction` | 保持 |
| 跳转 XML | `FileTypes.Xml` | SQL 历史右键 `JumpToMapperXmlAction` | 保持 |
| 返回 / 刷新 | `Actions.Back` / `Actions.Refresh` | SQL 历史导航栏 | 保持 |
| Diff 对比 | `Actions.Diff` | SQL 历史右键 | 保持 |
| 搜索 | `Actions.Search` | SQL 日志工具栏 | 保持 |
| 设置 / 帮助 / 交流群 / 排序 | 见 4.0 | SQL 日志工具栏 | 按 4.0 执行 |
| 增强暂停 / 恢复 | `Actions.Pause` / `Actions.Resume` | SQL 日志工具栏 | 保持 |

#### 4.13.2 改动清单（按页签）

- **SQL 日志**：屏蔽规则改自绘 `eyeOff`；滚动到底改 `ObjectBrowser.SortByType`；停靠预览改 `Actions.PreviewDetails`；SQL 类型过滤改 `General.Filter`。捕捉开始/停止（`Actions.Execute` / `Actions.Suspend`）、增强暂停/恢复、清空、设置、搜索、排序、交流群、帮助保持。
- **SQL 历史**：滚动到底改 `ObjectBrowser.SortByType`；停靠预览改 `Actions.PreviewDetails`；右键"导出 SQL"从 `Actions.EditSource` 改为 `General.Export`。返回、刷新、Diff、复制、跳转源码、跳转 XML 保持。
- **日志转 SQL**：图标不变，只把 6 处 `AllIcons.*` 字面量收口到 `MpvsIcons`。
- **SQL → Java**：图标不变，只把 6 处 `AllIcons.*` 字面量收口到 `MpvsIcons`；类选择字段的 12px 缩放图标（`General.Ellipsis` / `Actions.Cancel`）保持原样。

#### 4.13.3 不跨页硬统一的例外

- 捕捉开始虽然也用 `Actions.Execute`，但它只出现在 SQL 日志页，和另外三页的"转换/执行"不在同一视觉上下文；不为了区分而给它换图标（用户最终选定 `expui/run/run.svg`）。
- SQL 日志右键菜单目前多数条目没有图标，本次只给"导出"补 `General.Export`，不批量给所有菜单项加图标；右键菜单的主要识别手段仍是文字。
- `Actions.Copy` 同时用于"复制 SQL"与"复制时间戳"，两者在菜单里靠文字区分，不另造图形。

## 五、实施方案

### W1 建立图标注册表

新增 `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/icons/MpvsIcons.kt`：

- 一个 `object MpvsIcons`，按语义命名常量：`CaptureStart`、`CaptureStop`、`AgentPause`、`AgentResume`、`Clear`、`Settings`、`Suppress`、`Search`、`DockedPreview`、`ScrollToEnd`、`Sort`、`Community`、`Help`、`TypeFilter`。
- 平台图标直接引用 `AllIcons.*`；自绘图标统一走 `IconLoader.getIcon("/icons/toolbar/<name>.svg", MpvsIcons::class.java)`。
- 每个常量旁写一行语义注释（为什么是这个图形），不写"来自某计划"这类过程信息。

### W2 自绘 SVG 资产

新增目录 `src/main/resources/icons/toolbar/`，每个图标两件：`<name>.svg` 与 `<name>_dark.svg`。

统一规范：

- `width/height=16`、`viewBox="0 0 16 16"`、`fill="none"`。
- 描边 1.5（强调斜杠可用 1.6），`stroke-linecap="round"`、`stroke-linejoin="round"`。
- 亮色主色 `#6C707E`、暗色主色 `#CED0D6`；仅"捕捉红点"与"屏蔽斜杠"允许语义强调色（红 `#E55765`/`#DB5C5C`、`#DB5860`）。
- 图形落在 1.5~14.5 的安全区内，不贴边；不写 `<text>`、不写滤镜/渐变/阴影。
- 若用户选择保留品牌色，只允许 `#4FB477`/`#B48EFF` 作为强调，且必须同时提供暗色变体。

按已确认选型，工具栏图形统一克隆自 IntelliJ expui 图标集（Apache 2.0，资源内保留原始版权注释），共 21 组、42 个亮/暗文件；`eyeOff` 为自绘。克隆的目的就是消除 `AllIcons` 在 243/262 之间的形态差异。

### W3 动作接线

按 4.13.2 的改动清单逐处替换，只改 `e.presentation.icon` 与必要的 `description`：

- `SqlLogPanel.kt`：`typeFilterButton`（240-245）、`sortModeAction`（257-272）、`previewToggleAction`（477-487）、`CaptureAction`（1071-1092）、`AgentPauseAction`（1100-1120）、`ClearAction`（1122-1132）、`ScrollToEndAction`（1138-1150）、`SettingsAction`（1152-1163）、`ManageSuppressAction`（1170-1179）、`CommunityAction`（1181-1189）、`ShowSearchAction`（824-839）、`HelpAction`（1207-1211）。
- `HistorySqlLogPanel.kt`：停靠预览（193-208）、滚动到底（236-250）、右键导出（986-...）。
- `LogToSqlPanel.kt`：输入区转换/粘贴/清空（188-230）、结果区复制/格式化/执行/清空（232-289）。图标不变，只把字面量收口到 `MpvsIcons`。
- `SqlToJavaPanel.kt`：输入区转换/格式化/清空（344-378）、结果区复制/格式化/清空（380-418）。图标不变，只把字面量收口到 `MpvsIcons`；类选择字段的 12px 缩放图标（1835-1836）不动。
- SQL 日志右键 `ExportVisibleAction`（`SqlLogPanel.kt:1928`）补 `General.Export`，与 SQL 历史右键导出一致。

### W4 可发现性兜底

- 每个动作在 `update` 里保证 `text` 与 `description` 都有值；工具栏只渲染图标，tooltip 是最终兜底。
- 检查 ActionToolbar 溢出菜单（窄宽 `»`）里图标+文字是否同时可见；若只有图标，确认 `description` 已设置。
- 不改动作顺序与分组，不新增"更多"弹层。
- 四个页签的面板均按同一 `description` 文案键取值，避免同义动作在不同页签叫法不同。

### W5 测试

新增 `src/test/kotlin/.../log/ToolbarIconSemanticsTest.kt`（JUnit3 前缀命名按仓内约定）：

1. 四个页签的工具栏动作在 `update` 后 `presentation.icon` 非空，且宽高均为 16。
2. 同组内不存在两个动作使用同一个 `Icon` 实例（捕捉开始/停止、暂停/恢复按状态互斥，不在此列）。
3. 自绘资产存在性：每个 `MpvsIcons` 常量对应资源可加载，`_dark.svg` 同目录存在。
4. 跨页一致性：停靠预览在 SQL 日志与 SQL 历史必须引用同一 `MpvsIcons.DockedPreview`；滚动到底同理；导出在 SQL 日志与 SQL 历史必须同为 `MpvsIcons.Export`。
5. 语义守卫：`SqlLogPanel.kt`、`HistorySqlLogPanel.kt`、`LogToSqlPanel.kt`、`SqlToJavaPanel.kt` 中不允许再出现裸 `e.presentation.icon = AllIcons...` 的散落赋值（正则扫描，白名单只放 `MpvsIcons`；类选择字段的 12px 缩放图标单独放行）。这条守卫用真实站点做反向验证：删掉一个 `MpvsIcons` 引用改回裸赋值，必须变红。

### W6 文档与发行说明

- `CHANGELOG.md` 记一条"工具栏图标语义优化"。
- 若帮助页或 README 有工具栏截图，按新图标更新；没有则不动。
- 不新增设置项，不新增开关。

## 六、验收标准

### 6.1 视觉验收矩阵

| 维度 | 取值 |
|---|---|
| IDE 版本 | 243 沙盒、262 沙盒 |
| 主题 | 亮色、暗色 |
| 捕捉状态 | 未捕捉、捕捉中、增强暂停、尾部跟随中 |
| 页签 | SQL 日志；日志转 SQL（输入区 + 结果区）；SQL→Java（输入区 + 结果区）；SQL 历史（文件列表 + 二级列表） |
| 宽度 | 每个工具栏各测常规宽度与窄宽（触发 `»` 溢出） |

每个组合截图确认：无空白图标、无尺寸跳变、无同形歧义、tooltip 文案正确。

### 6.2 通过判据

- 不看 tooltip 时，5 处歧义按钮能被至少两名未参与实施的人正确说出作用。
- 放大镜只剩搜索一个；漏斗只剩 SQL 类型过滤一个。
- 四个页签里同一语义的图标完全一致：预览（SQL 日志 = SQL 历史）、滚动到底（SQL 日志 = SQL 历史）、清空（四页签统一为垃圾桶）、导出（SQL 日志 = SQL 历史）。
- 243/262 两版同一按钮的语义一致（图形可以随平台风格不同，但不能再出现"一版是播放、另一版是别的"）。
- 定向测试全绿；`testFast` 通过；不做全量（按验证三档分层，全量由收口代理统一跑）。

## 七、风险与回退

| 风险 | 影响 | 处置 |
|---|---|---|
| 自绘图标在 243 经典 UI 下描边偏细/偏粗 | 观感不统一 | 双版本截图比对；必要时给 243 单独加粗到 1.6 |
| `AllIcons` 字段在 262 映射到 expui、243 映射到经典 | 同一字段跨版本形态不同 | 这是平台预期行为，不视为缺陷；验收按"语义一致"而非"像素一致" |
| 四个页签共用注册表后，某页签的图标被误改 | 该页签语义回退 | 注册表按语义命名，不按页签命名；跨页一致性测试锁住预览/滚底/清空/导出四组 |
| 屏蔽按钮改自绘后与右键菜单"管理屏蔽规则"图标不一致 | 同一功能两套图形 | 右键菜单项也接 `MpvsIcons.Suppress`，一并改 |
| 清空图标只改一处造成四页签不一致 | 新的不一致 | 本次不动清空，四页签继续统一用 `Actions.GC`；若用户要求换，按全仓 7 处一次性评估 |

回退：所有改动集中在 `MpvsIcons` 与资源目录，回退只需把注册表常量改回原 `AllIcons` 字段并删除新资源，动作逻辑零改动。

## 八、待验证前提

以下均为本次推断或单点实证，实施前必须逐条证伪：

1. `Actions.ClearCash` 在 243 无经典变体（字节码中经典路径参数为 null，仅 `expui/actions/clearCash.svg`），因此不得用于清空；实施前在 runIde 243 再确认一次实际渲染（可能是空白）。
2. `Actions.PreviewDetails` 的映射已从字节码确认：262 为 `expui/general/previewVertically.svg`、243 为 `actions/previewDetails.svg`；仍需 runIde 确认 243 的实心分栏在工具栏里不显笨重。`General.PreviewHorizontally` 的资源映射未解码，不作为首选。
3. `ObjectBrowser.SortByType` 在 243/262 的实际渲染均为"向下箭头 + 两个色块"；它更接近排序语义，挂在滚动到底上需 runIde 确认不会被读成"按类型排序"。
4. 自绘 `eyeOff` 的眼睛轮廓取自 expui `inspectionsEye.svg`（Apache 2.0），斜线为后加；16px 下斜线是否会糊住瞳孔、红色斜线在暗色主题的对比度是否足够，需双版本截图确认。
5. 自绘 `eyeOff_dark.svg` 的自动切换需在 ActionToolbar 的 `presentation.icon` 路径上实测（仓内 `communityChat_dark.svg` 只在直接 `IconLoader` 场景验证过）。
6. 262 的 `AllIcons.General.ArrowDown` 渲染为 chevron（用户截图已证），243 为实心三角；本计划不再用它做滚动到底，若实施中改回，需接受跨版本形态差异。
7. 增强暂停按钮按 T-AgentPauseVisibility 方案 B（2026-10-19）临时全隐藏；工具栏恒 10 个可见动作，图标冲突检查按"可见集合"而不是"动作集合"做。恢复方案 A 时再改为「agent 挂载或暂停态可见」。
8. 窄宽溢出菜单里 ActionToolbar 是否同时显示图标与文字，需在 243/262 各截一次图确认；若只显示图标，`description` 必须逐项补齐。

## 九、需要用户拍板的点

当前无待拍板项。`objectBrowser/sortByType.svg` 已确认用于滚动到底；四个页签的同步范围与图标选型均已定稿。

## 十、工作量与实施顺序

建议顺序：W1 注册表 → W2 资产 → W3 接线 → W4 兜底 → W5 测试 → W6 文档 → 双版本视觉验收。

实际改动：新增 `ui/icons/MpvsIcons.kt`；新增 `src/main/resources/icons/toolbar/` 下 21 组共 42 个克隆 SVG（含 `_dark`）+ 自绘 `eyeOff` 一对；修改 4 个面板文件（`SqlLogPanel` / `HistorySqlLogPanel` / `LogToSqlPanel` / `SqlToJavaPanel`）；新增 `ToolbarIconSemanticsTest`；同步更新 6 个既有测试的图标断言。不改构建脚本、不改依赖、不改 `plugin.xml`。

## 十一、实施前后对照（供动图重录）

### 11.1 实现方式的共同变化

所有工具栏图标从 `AllIcons` 字段改为 `MpvsIcons` 注册表，注册表加载插件自带 SVG。由此带来一个对录屏有利的结果：**243/253/262 渲染完全相同的图形**，动图只录一版即可，不再需要按 IDE 版本区分。

| 项 | 实施前 | 实施后 |
|---|---|---|
| 图标来源 | `AllIcons.*`（243 经典实心 / 262 expui 线性，自动切换） | `src/main/resources/icons/toolbar/*.svg`（克隆 expui，全版本固定） |
| 统一入口 | 各面板散落 `e.presentation.icon = AllIcons...` | `MpvsIcons` 语义常量 |
| 暗色适配 | 平台自动 | `_dark.svg` 由 `IconLoader` 自动选用 |
| 屏蔽规则 | `AllIcons.General.Filter` 漏斗 | 自绘「眼睛 + 红斜线」 |

### 11.2 SQL 日志工具栏（从左到右）

| 序 | 按钮 | 实施前 | 实施后 | 动图取景 |
|---|---|---|---|---|
| 1 | 捕捉开始 | `AllIcons.Actions.Execute` 绿三角 | `MpvsIcons.CaptureStart` 绿三角（克隆 `run.svg`） | 未捕捉态全栏 |
| 2 | 捕捉停止 | `AllIcons.Actions.Suspend` 红方块 | `MpvsIcons.CaptureStop` 红方块（克隆 `stop.svg`） | 点击捕捉后按钮变红方块 |
| 3 | 增强暂停 | `AllIcons.Actions.Pause` 双竖条 | `MpvsIcons.AgentPause`（克隆 `pause.svg`） | 方案 B 临时全隐藏（不出现） |
| 4 | 增强恢复 | `AllIcons.Actions.Resume` 绿三角带竖条 | `MpvsIcons.AgentResume`（克隆 `resume.svg`） | 方案 B 临时全隐藏（不出现） |
| 5 | 清空 | `AllIcons.Actions.GC` 垃圾桶 | `MpvsIcons.Clear`（克隆 `clear.svg`） | 有数据时点击清空 |
| 6 | 设置 | `AllIcons.General.Settings`（243 扳手/262 齿轮） | `MpvsIcons.Settings` 齿轮（克隆 `settings.svg`，两版统一） | 点击打开设置页 |
| 7 | 屏蔽规则 | `AllIcons.General.Filter` 漏斗 | `MpvsIcons.Suppress` 眼睛 + 红斜线（自绘） | 点击打开屏蔽规则对话框 |
| 8 | 搜索 | `AllIcons.Actions.Search` 放大镜 | `MpvsIcons.Search`（克隆 `search.svg`） | 点击展开搜索条 |
| 9 | 停靠预览 | `AllIcons.Actions.Preview` 放大镜+页 | `MpvsIcons.DockedPreview` 分栏面板（克隆 `dockedPreview.svg`） | 点击右侧展开预览面板 |
| 10 | 滚动到底 | `AllIcons.General.ArrowDown`（262 chevron/243 三角） | `MpvsIcons.ScrollToEnd` 列表+向下箭头（克隆 `scrollToEnd.svg`） | 列表滚到中间后点击回到底部 |
| 11 | 排序 | `AllIcons.RunConfigurations.SortbyDuration` | `MpvsIcons.Sort`（克隆 `sortDuration.svg`） | 点击弹出排序菜单 |
| 12 | 交流群 | 自绘 `communityChat.svg` | 不变（仍自绘） | 点击打开交流群弹框 |
| 13 | 帮助 | `AllIcons.Actions.Help` 问号 | `MpvsIcons.Help`（克隆 `help.svg`） | 点击打开帮助页 |
| — | SQL 类型过滤 | `General.ArrowDown` + 文本"全部" | `MpvsIcons.TypeFilter` 漏斗 + 文本"全部"（克隆 `filter.svg`） | 点击弹出类型多选 |

### 11.3 SQL 历史

| 区域 | 按钮 | 实施前 | 实施后 | 动图取景 |
|---|---|---|---|---|
| 导航栏 | 返回 | `AllIcons.Actions.Back` | `MpvsIcons.Back`（克隆 `back.svg`，图形不变） | 二级列表点返回 |
| 导航栏 | 刷新 | `AllIcons.Actions.Refresh` | `MpvsIcons.Refresh`（克隆 `refresh.svg`） | 点击刷新 |
| 导航栏 | 滚动到底 | `AllIcons.General.ArrowDown` | `MpvsIcons.ScrollToEnd`（与 SQL 日志同图） | 列表滚到中间后点击 |
| 导航栏 | 停靠预览 | `AllIcons.Actions.Preview` | `MpvsIcons.DockedPreview`（与 SQL 日志同图） | 点击展开预览 |
| 右键 | Diff 对比 | `AllIcons.Actions.Diff` | `MpvsIcons.Diff`（克隆 `diff.svg`） | 选中两条右键对比 |
| 右键 | 导出 SQL | `AllIcons.Actions.EditSource` | `MpvsIcons.Export`（克隆 `export.svg`） | 右键导出 |
| 右键 | 复制 SQL / 时间戳 | `AllIcons.Actions.Copy` | `MpvsIcons.Copy`（克隆 `copy.svg`） | 右键复制 |
| 右键 | 跳转源码 | `AllIcons.Actions.EditSource` | `MpvsIcons.EditSource`（克隆 `editSource.svg`） | 右键跳转 |
| 右键 | 跳转 XML | `AllIcons.FileTypes.Xml` | `MpvsIcons.MapperXml`（克隆 `mapperXml.svg`） | 右键跳转 XML |

### 11.4 日志转 SQL

| 区域 | 按钮 | 实施前 | 实施后 | 动图取景 |
|---|---|---|---|---|
| 输入区 | 转换 | `AllIcons.Actions.Execute` | `MpvsIcons.Convert`（克隆 `run.svg`） | 粘贴日志后点转换 |
| 输入区 | 从剪贴板读取 | `AllIcons.Actions.MenuPaste` | `MpvsIcons.Paste`（克隆 `paste.svg`） | 复制日志后点粘贴 |
| 输入区 | 清空输入 | `AllIcons.Actions.GC` | `MpvsIcons.Clear` | 点击清空 |
| 结果区 | 复制 | `AllIcons.Actions.Copy` | `MpvsIcons.Copy` | 点击复制 |
| 结果区 | 压缩/多行切换 | `AllIcons.Actions.ReformatCode` | `MpvsIcons.Format`（克隆 `format.svg`） | 点击切换格式 |
| 结果区 | 执行 | `AllIcons.Actions.Execute` | `MpvsIcons.Execute` | 点击执行还原产物 |
| 结果区 | 清空结果 | `AllIcons.Actions.GC` | `MpvsIcons.Clear` | 点击清空 |

### 11.5 SQL → Java

| 区域 | 按钮 | 实施前 | 实施后 | 动图取景 |
|---|---|---|---|---|
| 输入区 | 转换 | `AllIcons.Actions.Execute` | `MpvsIcons.Convert` | 输入 SQL 后点转换 |
| 输入区 | 格式化 SQL | `AllIcons.Actions.ReformatCode` | `MpvsIcons.Format` | 点击格式化 |
| 输入区 | 清空输入 | `AllIcons.Actions.GC` | `MpvsIcons.Clear` | 点击清空 |
| 结果区 | 复制 | `AllIcons.Actions.Copy` | `MpvsIcons.Copy` | 点击复制 Java |
| 结果区 | 格式化代码 | `AllIcons.Actions.ReformatCode` | `MpvsIcons.Format` | 点击格式化代码 |
| 结果区 | 清空结果 | `AllIcons.Actions.GC` | `MpvsIcons.Clear` | 点击清空 |
| 类选择字段 | 浏览 / 清除 | `AllIcons.General.Ellipsis` / `AllIcons.Actions.Cancel`（12px 缩放） | 不变 | 点击浏览选类 |

### 11.6 动图重录取景建议

- **SQL 日志全栏**：一次横拍，包含左侧 11 个动作、右侧"全部"下拉和"SQL 去重"；重点让观众看到"绿三角=捕捉、红方块=停止、眼睛+斜线=屏蔽、分栏=预览、列表↓=滚底"。
- **捕捉两态**：未捕捉（绿三角）→ 点击 → 捕捉中（红方块）→ 再点击停止。增强捕获开启时，暂停/恢复按钮出现在捕捉按钮右侧。
- **屏蔽规则**：点击眼睛+红斜线，打开屏蔽规则对话框；不要再从漏斗图标录制（漏斗已让给类型过滤）。
- **停靠预览**：点击分栏图标，右侧预览面板展开/收起；与搜索放大镜不再同形。
- **滚动到底**：列表滚到中间后点击"列表+向下箭头"，回到底部并恢复尾部跟随。
- **SQL 类型过滤**：点击漏斗+"全部"，弹出类型多选；这是全栏唯一漏斗。
- **SQL 历史**：顶部四个按钮（返回/刷新/滚底/预览）同框；右键菜单录制导出与跳转两项即可覆盖导出图标变化。
- **日志转 SQL**：输入区三个 + 结果区四个按钮同框；录制"剪贴板读取 → 转换 → 复制"。
- **SQL→Java**：输入区三个 + 结果区三个按钮同框；类选择字段的 12px 图标不在本次变化内，可不录。

录屏注意：克隆后 243/262 图形一致，**不需要按 IDE 版本分别录**；但暗色主题仍建议单独录一条，因为 `_dark.svg` 是另一组文件。

### 11.7 实施读数

- 定向测试（2026-10-19）：`ToolbarIconSemanticsTest`、`SqlLogPanelLayoutTest`、`SqlLogTailFollowTest`、`HistorySqlLogPanelScrollToEndTest`、`HistorySqlLogPanelPreviewTest`、`HelpUiTest`、`HelpDialogTest`、`LogToSqlPanelTest`，合计 **8 类 71 例，0 失败 / 0 错误 / 0 跳过**。
- 反向验证：把 `SqlLogPanel` 搜索图标的 `MpvsIcons.Search` 临时改回 `AllIcons.Actions.Search`，`ToolbarIconSemanticsTest` 恰 1 红，报错精确指出 `SqlLogPanel.kt -> e.presentation.icon = AllIcons.`；还原后复跑 **4/0/0**。
- 资源清单：`src/main/resources/icons/toolbar/` 下 21 组克隆 SVG（42 个亮/暗文件）+ 自绘 `eyeOff` 一对。
- 未做：全量 `testShards`、`verifyGate`、runIde 真机双版本双主题视觉验收。按验证三档分层，全量与构建校验留给收口阶段。
