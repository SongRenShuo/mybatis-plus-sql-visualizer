# T-SqlLogTabsSingleRow：SQL 日志页签恒单行与平台式溢出开发计划

- 占号：**O-149 / R99 / D159 / V182**（2026-10-14 复核：O-146/O-148 为本轮已实施计划，O-147 为 T-AgentCaptureProvenance，本组仅本计划在用）
- 日期：2026-10-14
- 基线：`master@803ef689` 之上的实施分支 `t-sql-list-native-tail`（HEAD `a29ea923`）
- 状态：需求分析完成、方案定案，随本计划一并实施
- 用户触发（原话）：「这个 tab 能不能也优化下，现在侧边栏变窄，上方 tab 实际上是可以一行的，可以参考下 idea 打开多个类时候的交互方式，生成开发计划文件并实施」
- 关联：T-SqlListNativeVisual（O-146）、T-SqlLogTailFollow（O-148）——同一面板族；本计划只动页签容器，不改列表/工具栏

## 一、问题结论

工具窗口的四个页签（SQL 日志 / 日志 → SQL / SQL → Java / SQL 历史）在侧边栏变窄时**折成两行**：

```
        SQL → Java        SQL 历史
SQL 日志       日志 → SQL
```

根因是容器没设布局策略：`SqlLogPanel.kt:117` 用 `JBTabbedPane()` 建页签，而 `JTabbedPane` 的默认 `tabLayoutPolicy` 是 `WRAP_TAB_LAYOUT`——页签总宽超过容器宽度时**按行折**，这正是用户截图里的形态。四个页签在用户侧栏宽度下其实接近放得下（用户原话「实际上是可以一行的」），只是缺一点余量就被折行规则接管。

## 二、方案

### 2.1 定案（第二轮修正后）：直接复用平台页签实现

**第一轮尝试（已回退，留档）**：只改 `JTabbedPane` 的策略与内边距——`SCROLL_TAB_LAYOUT` + `setTabComponentInsets(2,8,2,8)`。
实测失败：无头量得原文案四页签需 ~321px，我加的内边距又把所需宽度推到约 385px，用户侧栏（约 230~250px）
一个都放不下，平台 ExpUI 遂把整排收进下拉箭头、页签全不可见（用户截图报「严重问题」）。**教训：改页签策略前先量所需宽度**。

**定案**：按用户要求「参考 IDEA 打开多个类时的交互」——**复用平台自己的页签实现**，不自造策略：

```text
JBTabsFactory.createTabs(project, this)      // 产出 JBTabsImpl
  .presentation.setSingleRow(true)           // 恒单行（不折行）
  .presentation.setSupportsCompression(true) // 页签可压缩
  .presentation.setTabDraggingEnabled(false)
```

`JBTabsImpl` 实现 `MorePopupAware`（`canShowMorePopup()` / `showMorePopup()` / `getMoreToolbar()`）——
这正是编辑器页签「一行 + 放不下时压缩 + 再放不下就出『显示全部页签』下拉」那套交互的平台机制，
由平台按宽度自行调度，插件不再参与宽度决策。

**跨版本实证**（本机 jar `javap`，243 = `app-client.jar`，262 = `intellij.platform.ide.jar`）：
`JBTabsFactory.createTabs/createEditorTabs`、`JBTabsPresentation.setSingleRow/setSupportsCompression`、
`JBTabsImpl.canShowMorePopup/showMorePopup/getMoreToolbar`、`TabInfo` 链式 setter、
`TabsListener.selectionChanged(TabInfo?, TabInfo?)` 两侧均在。

### 2.2 迁移面

页签身份由「索引」改为「TabInfo 句柄」：`logsTab/logToSqlTab/sqlToJavaTab/historyTab` 四个字段替代
`historyTabIndex`；`selectedComponent = X` 改 `select(info, true)`；`indexOfTab(label)` 改按
`getTabs()` 的 text 匹配；`addChangeListener` 改 `addListener(TabsListener, this)`（注意平台签名的两个
参数**可为空**，Kotlin override 必须写成 `TabInfo?`）。

### 2.3 不做（原备选，现已被 2.1 覆盖）

自造「缩到最小宽度 + 自定义下拉」或换 `JBEditorTabs` 的 4 参构造器（需 `TabListOptions`/`CoroutineScope`，
跨版本面更大）。既然 `createTabs` + 两个 presentation 开关已给出同款交互，不再自造。

## 三、改动清单

- `log/SqlLogPanel.kt`：页签容器加 `tabLayoutPolicy = SCROLL_TAB_LAYOUT` 与紧凑 `tabComponentInsets`；注释写明「折行根因＝默认 WRAP」与「备选 JBTabs 的取舍」。
- `test/.../SqlLogPanelLayoutTest.kt`：新增守卫用例——把页签面板按窄宽度布局后，**四个页签的 bounds 必须同处一行**（`y` 相同且高度为正）；再补一条「布局策略非 WRAP」的显式断言（两条互为冗余，避免只测其一）。

## 四、验收矩阵

1. 窄宽度（约 360px，用户侧栏量级）布局后四个页签仍在一行；
2. 页签数量恒为 4、文案与顺序不变（SQL 日志 / 日志 → SQL / SQL → Java / SQL 历史）；
3. 选中态、切换刷新（切历史 tab 触发 `refresh()`）、历史 tab 常驻等既有行为逐字不变；
4. 宽度足够时不出现滚动箭头；不足时出现平台滚动而不是折行；
5. 243 / 253 / 262 上 `setTabComponentInsets` 均可用（编译基线 243 + 262 本机 `javap` 已实证）。

## 五、反向验证

1. 把 `tabLayoutPolicy` 改回 `WRAP_TAB_LAYOUT`（或删掉该行）→ 窄宽度单行守卫必须红；
2. 恢复后必须复绿；
3. 若只删掉紧凑内边距而保留 SCROLL：页签仍单行（守卫应绿）——用于确认两条断言各自守的是不同东西，而不是同一条。

## 六、待验证前提 V182

| 编号 | 待验证项 | 成功判据 | 失败时处置 |
|---|---|---|---|
| V182-1 | `setTabComponentInsets` 在 243/253/262 的实际紧凑效果与默认值差异 | 三版本下四个页签在用户侧栏宽度内不出现滚动箭头 | 只保留 SCROLL 策略（保证不折行），内边距不动 |
| V182-2 | 用户侧栏实际宽度下的真机观感（是否仍需要滚动） | 真机截图四个页签一行且全可见 | 按 V182-1 的降级处置，或改用更短文案（另立文案任务） |
| V182-3 | 若改用 `JBTabs` + `TabLayout.SHRINK` 的可行性 | 跨版本签名门禁三目标通过 + 真机下拉可用 | 维持本计划定案（JBTabbedPane + SCROLL） |

## 七、明确不做

- 不改页签数量、文案、顺序、图标；
- 不改页签切换/懒加载/历史常驻逻辑；
- 不动列表、工具栏、高亮（属 O-146/O-148）；
- 不为页签新增设置项（不引入配置面）。

## 八、实施记录

- 2026-10-14 第一轮：定位根因（`JBTabbedPane()` 未设 `tabLayoutPolicy`，默认 `WRAP_TAB_LAYOUT`），双版本 `javap` 实证 `setTabComponentInsets` 在 243/262 均可用；**错误定案**为「SCROLL 策略 + 紧凑内边距」并实施（提交 `5cba13b1`）。
- 2026-10-14 第二轮（用户报「严重问题」后）：先回退第一轮（页签重新可见），再用无头探针量出各宽度下的页签几何（原文案 WRAP：230/260/300px 折 2 行、≥360px 一行；所需宽度 ~321px），确认第一轮的 8px 内边距把所需宽度推到 ~385px 是压垮点。随后按用户「反编译看 IDEA 怎么复用」的指引查平台源码（`javap` 243/262），改用 `JBTabsFactory.createTabs` + `setSingleRow(true)` + `setSupportsCompression(true)`，即平台自带的单行＋压缩＋`MorePopupAware` 下拉；页签身份迁移到 `TabInfo` 句柄。`SqlLogPanelLayoutTest` 15/15 绿。
- 2026-10-14 真机复看（262 沙盒，HEAD `7b163516`）：四个页签**一行显示**且为编辑器式样式（选中项带框）；点「SQL 历史」正常切到历史面板（其搜索栏与空态引导就位）；工具栏八个动作图标同时可见。
- 2026-10-14 权威全量（`HEAD=7b163516`，Docker 29.6.2，`testShards --no-configuration-cache`）：`BUILD SUCCESSFUL in 14m 6s`，退出码 0，合计 **7159/0/0/9**；`SqlLogPanelLayoutTest` 15/0、`SqlLogPanelTypePopupToggleTest` 6/0、`SqlLogTailFollowTest` 5/0、`BundleParityGuardTest` 5/0。期间未改工作树。
- 2026-10-14 定稿轮（`HEAD=9223c277`）：清理迁移残留（未用 import／失效注释，`c1eaf771`）后重跑，`BUILD SUCCESSFUL in 12m 13s`，合计 **7159/0/0/9**。
- 过程中被分片重排暴露的一条**假红**（与本计划无关）：`HistorySqlLogPanelJumpToSourceTest` 断言历史目录「恰好 1 个文件」但自己不清目录，同分片残留使其读到 2（`expected:<1> but was:<2>`）；隔离单跑 6/6 通过即证为顺序依赖而非缺陷。按同族用例做法加 `cleanHistoryDir()` 并把实际目录内容写进断言消息（`9223c277`，纯测试改动）。**教训：断言「精确数量」的用例必须先清理共享目录**。
