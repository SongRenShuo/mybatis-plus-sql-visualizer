# T-SqlLogToolbarEdt：SQL 日志工具栏动作组改走 BGT，消除 EDT 抢占

> **创建日期**：2026-10-24 20:41
> **触发**：用户问「可修的性能问题，现在立项」。来源＝沙盒验收日志的实测告警，不是估算。
> **前置**：`T-ExplainConsoleFirst` 本轮验收会话日志（`idea.log` 20:34 之后）

---

## 一、实测证据（来自用户本次验收的沙盒日志）

```
20:34:28,468 WARN - ActionUpdater - 3981 ms total to grab EDT 10 times to expand
         DefaultActionGroup#expandGroup@cn.song.mybatisplus.visualizer.log.toolbar
         (DefaultActionGroup). Use `ActionUpdateThread.BGT`.
20:35:23,355 WARN - ActionUpdater - 3174 ms total to grab EDT 10 times to expand
         DefaultActionGroup#expandGroup@cn.song.mybatisplus.visualizer.log.toolbar
```

两次告警都是同一形态，累计抢 EDT **3.1~4.0 秒**（10 次抓取）。平台本身已经把处置办法写在告警里：
`Use ActionUpdateThread.BGT`。

这不是新引入的回归——`[M36] toolbar manual update is ignored` 那条 WARN 同源，属存量代码。

## 二、根因

`SqlLogPanel.kt:505`：

```kotlin
internal val toolbarGroup = DefaultActionGroup(
    captureToggleAction, agentPauseAction, ClearAction(), SettingsAction(),
    ManageSuppressAction(), ShowSearchAction(), previewToggleAction,
    scrollToEndAction, sortModeAction, CommunityAction(), HelpAction(),
)
```

`DefaultActionGroup` / `AnAction` 未覆写 `getActionUpdateThread()` 时，**默认值在 243 平台是
`EDT`**。于是平台在 EDT 上逐个调这 11 个动作的 `update(e)` 算 presentation；每次展开
（工具栏首次渲染、宽度变化触发溢出重算、动作 `updateActionsImmediately`）都要重来一遍。

加重项在 `sortModeAction.update`（`:262`）：

```kotlin
override fun update(e: AnActionEvent) {
    val label = currentSortLabel()      // ← 每次调用都 sortOptions() 建列表再 firstOrNull
    ...
}
```

`currentSortLabel()`（`:275`）每次调 `sortOptions().firstOrNull { ... }`，即**每轮 update 都重新构造
一次选项列表**。11 个动作在 EDT 上跑一轮已经不快，再叠加这个分配就更明显。

## 三、方案

### 3.1 主修：动作组显式声明 BGT

给 `toolbarGroup` 的容器与其中各 `update` 有实际工作的自定义动作覆写：

```kotlin
override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
```

依据（平台语义，非猜测）：`ActionUpdateThread.BGT` 表示 `update()` 允许在后台线程执行，
平台会把 presentation 计算挪出 EDT，只把结果贴回 UI。**硬约束：`update()` 内不得触碰 Swing 组件/
文档**——现有各 `update` 只读 `manager.capturing` / `sortMode` 并写 `e.presentation`，符合要求，
但必须逐个核过（见 W2）。

### 3.2 附修：`currentSortLabel` 去分配

`sortOptions()` 每次重新建列表只为取一个 label。选项集合是静态的（排序模式有限枚举），
改成静态查表（`when(sortMode)` 或缓存 Map），让 `update` 变成纯查表。

### 3.3 明确不做

- **不改 `DROPDOWN_TEXT_BUDGET_PX` / 工具栏布局**（T-UiCoord F2 定案的单行预算，与本次无关）。
- **不动 `updateActionsImmediately` 调用点**（`capturingUiListener` 那条在沙盒不可见时本该跳过，
  属另一话题）。
- 不去 `--add-opens` / 反射压制告警——告警是真实开销，不是噪音。

## 四、工作项

| # | 内容 | 落点 |
|---|---|---|
| W1 | `toolbarGroup` 改为带 BGT 的组（继承 `DefaultActionGroup` 覆写或包装） | `log/SqlLogPanel.kt:505` |
| W2 | 逐个核 11 个动作的 `update()`：只读状态 + 写 presentation，**不得触碰 Swing/文档**；不合规的留在 EDT 并注释原因 | 同上，各 `update` |
| W3 | `currentSortLabel()` 去分配（静态查表） | `log/SqlLogPanel.kt:275` |
| W4 | 守卫：断言工具栏组 `getActionUpdateThread() == BGT`；断言 `update()` 里不出现 `SwingUtilities` / `invokeLater` / `component` 访问 | 新增测试 |
| W5 | 真机/沙盒复测：日志工具栏展开时不再出现 `ActionUpdater ... expandGroup@...log.toolbar` 告警 | 沙盒日志 |

## 五、待验证前提（先证伪再动码）

| # | 前提 | 证伪方式 |
|---|---|---|
| P-1 | 243 平台 `DefaultActionGroup` 未覆写时默认确为 `EDT` | javap / 反编译 `ActionUpdateThread` 默认值；对照告警文本「Use ActionUpdateThread.BGT」 |
| P-2 | 11 个动作的 `update()` 全部符合 BGT 约束（不碰 Swing） | 逐个读；`CommunityAction` / `HelpAction` / `ToggleAction` 形态要单独确认（`ToggleAction.update` 是 final 模板，需看子类覆写） |
| P-3 | 改成 BGT 后 presentation 仍能正确刷新（不会出现图标/文案滞后） | 沙盒实测：捕捉态切换、排序切换、慢查询徽章三处文案跟随 |
| P-4 | 37ms/次 的抓取耗时主要来自 `sortOptions()` 分配，而非平台自身 | W3 单独落地后复测告警是否显著减少（可分离验证） |

## 六、验收判据

1. 沙盒日志中 `expandGroup@cn.song.mybatisplus.visualizer.log.toolbar` 告警消失或次数大幅下降（贴读数）。
2. 工具栏三态文案（捕捉/排序/慢查询）在真机仍正确刷新（P-3）。
3. 守卫 + 反向探针：把 BGT 改回默认 → 必须红。
4. 不改任何布局常量与用户可见行为（回归用例覆盖工具栏单行）。

---

## 七、实施记录

**2026-10-24 已实施，提交 `12755669`（未推送）。**

### P-1 前提核验：结论比计划里写的更精确

计划里写「未覆写 `getActionUpdateThread()` 时默认是 EDT」——**只对了一半**。243 平台字节码实证
（`AnAction.getActionUpdateThread()`）：

```
UpdateInBackground && isUpdateInBackground()  → BGT
ActionClassMetaData.isDefaultUpdate(this)     → BGT
otherwise                                     → EDT
```

而 `isDefaultUpdate` 判定的是 `ReflectionUtil.getMethodDeclaringClass(clazz, "update", [AnActionEvent])`
**是否等于 `AnAction` 本身**——即「有没有覆写 `update()`」。所以：

- 覆写了 `update()` 的动作 → 默认 **EDT**（我们的 11 个全在此列）；
- 没覆写的 → 默认 BGT（本来就没事）。

这条区分很重要：它不是「全组一刀切改成 BGT」，而是「**逐个**给覆写了 update 的动作补 BGT」。

### W2 合规核查结果

11 个动作的 `update()` 逐个读过：全部只做「读 holder 状态（`manager.capturing` / `sortMode` /
`dockedPreview.isOpen`）+ 写 `e.presentation`」，**没有**触碰 Swing 组件、文档、EDT-only API。
故 BGT 合规。

### 工作项落地

| # | 状态 | 落点 |
|---|---|---|
| W1 | 完成 | `toolbarGroup` 改为覆写 `getActionUpdateThread()` 的 `DefaultActionGroup` 子类；组内 10 个动作类/匿名对象（`sortModeAction`/`previewToggleAction`/`CaptureAction`/`ScrollToEndAction`/`ClearAction`/`SettingsAction`/`ManageSuppressAction`/`CommunityAction`/`HelpAction`/`ShowSearchAction`）逐个显式声明 BGT。全文件现共 12 处 BGT（1 组 + 10 动作 + 1 注释） |
| W3 | 完成 | 新增 `sortModeLabelKey(mode)` 静态查表；`currentSortLabel()` 改用它（旧实现每次 update 新建 7 个 `SortOption` + 7 次 bundle 取值） |
| W4 | 完成 | `SqlLogToolbarUpdateThreadGuardTest` 4 例：组 BGT / 组内逐个核（含 `members.size == 11` 防空转）/ 枚举常量仍是 BGT / 查表覆盖全部 `SortMode` 且与下拉文案一致 |
| W5 | **未做（沙盒 GUI 未走查）** | 需真机复测日志告警消失——见下方遗留 |

### 读数

- 与另一项合并跑定向 **6 类 65 / 0 / 0 / 0**；`SqlLogPanelLayoutTest` 19 例全绿（单行布局未被破坏）。
- 反向探针：删掉 `sortModeAction` 的 BGT 覆写 → **恰 1 红**。

### 🆕 坑：守卫首版假绿，靠反向探针才暴露

第一版守卫用 `Regex("""^\s{16}([A-Za-z][A-Za-z0-9]*)(?:Action)?..."`）解析组内成员，捕获组把
`captureToggleAction` 截成 `captureToggle`，随后又拼 `"${name}Action"` 变 `captureToggleActionAction`
——源码里找不到该声明，`filter` 的 `?: return@filter false` 让**每个成员都跳过**，
`offenders` 恒空、断言恒真。反向探针（删一个 BGT）跑出 **BUILD SUCCESSFUL**，才发现是假绿。

修法：正则**保留完整标识符**（不自作聪明剥后缀），并加 `assertEquals(11, members.size)`
把「解析到几个成员」钉死——解析失败会立刻红，而不是静默空转。

### 遗留

- **W5 沙盒复测未做**：需在真机确认 `idea.log` 里
  `ActionUpdater ... expandGroup@cn.song.mybatisplus.visualizer.log.toolbar` 告警消失或大幅下降，
  并确认工具栏三态文案（捕捉/排序/慢查询）仍正确刷新（P-3 的 BGT 副作用风险点）。
  headless 只能证「声明了 BGT」与「组内全覆盖」，证不了「平台真的在 BGT 上算且 UI 正确贴回」。
- `currentSortLabel` 去分配的实际收益未能独立测量（P-4 原打算 W3 单独落地后复测告警减少量），
  因两项改动同批提交。若后续想分离验证，可临时把 BGT 改回 EDT 单独跑一次对照。

---

## 八、沙盒 243 验收结果（2026-10-24 22:48 会话 / W5 兑现）

**W5 通过。** 本次会话（`idea.log` 启动行 42964 之后）统计：

```
expandGroup@cn.song.mybatisplus.visualizer.log.toolbar  →  0 条
```

改前每次会话稳定出现 2 条 `ActionUpdater ... 3981 ms / 3174 ms total to grab EDT 10 times
to expand ...log.toolbar ... Use ActionUpdateThread.BGT`；改后 **0 条**。

本次会话剩余的 `ActionUpdater` 告警全部是平台自身组件
（`CWMUserCirclesGroup` / `ToolWindowHeader` / `DockToolWindowAction`），与本插件无关。

三态文案（P-3 的 BGT 副作用风险点）在同会话内也随操作正常刷新：预览打开后排序按钮、
慢查询徽章、捕捉按钮的图标与文案均按预期显示，无滞后或占位圆圈。

### 顺带发现（不属于本项，另行修复）

同一次验收中另抓到执行计划的 EDT 慢操作（9 条 SEVERE，`createTempPsiFile` 冷路径），
归因插件。那不是本项引入的（本项只动 presentation 计算线程），已在
T-ExplainConsoleFirst W8 修复并在第二次沙盒会话复验为 **0 条**。

---

## 九、P-4 兑现：`currentSortLabel` 去分配的独立收益实测

计划 §五 P-4 挂的是「37ms/次 的抓取耗时主要来自 `sortOptions()` 分配，而非平台自身」，
当时因与 BGT 同批提交无法分离。本轮补测：新增 `SortModeLabelBenchmarkTest`，把两代实现
放在同一进程里跑基准（20 万次调用、先预热 2000 次、三轮取最小值降噪）：

```
[W3-BENCH] n=200000 legacy=38124ms new=5775ms ratio=6.60x (new is 15% of legacy)
```

**结论：单次调用 191µs → 29µs，快 6.6 倍，新实现只花旧实现的 15%。** P-4 成立——
耗时确实主要来自 `sortOptions()` 每次新建 7 个 `SortOption` 并调 7 次
`MpSqlVisualizerBundle.message`（每讲走 ResourceBundle 取值），而不是平台调度开销。

测试同时钉两条：

1. **结构断言**：`sortModeLabelKey` 不得再引用 `sortOptions`（反向探针把它改回
   `sortOptions().first{...}.label` → 恰 1 红）；
2. **语义等价**：两代实现对全部 `SortMode.values()` 给出同一文案——防止把行为变更
   伪装成性能优化。

基准本身不设耗时阈值（CI 负载波动会让阈值变 flaky），只打印倍数供记录；可机械判定的
部分（是否引用 `sortOptions`、文案是否一致）才做断言。



