# T-SqlLogSettingsJumpAnchor —— SQL 日志面板「设置」按钮跳转落点复位

- 任务编号：T-SqlLogSettingsJumpAnchor
- 立项时间：2026-11-25 15:20
- 触发：用户真机反馈（2026-11-25）：「目前 SQL 日志面板点击设置按钮，是跳到了插件设置的 SQL 日志页的中间位置，这个跳转的位置是不是也应该优化下」
- 关联：T-SettingsFocusFromLink（设置页定位高亮通道）、T-AutoShowYield（同批用户反馈，`12b84ae03`）

---

## 一、现状与机制（平台源码实证）

### 1.1 现在的调用链

1. 日志面板齿轮 `SettingsAction.actionPerformed` → `PluginSettingsFolderConfigurable.openSqlLogSettingsFocused(project, hint = null)`（`SqlLogPanel.kt:1595`）。
2. `openSettingsFocused` 置 `pendingFocus = PendingFocus(ID_SQL_LOG, null)` 后打开插件主设置页。
3. `schedulePendingFocus` 双 `invokeLater` 后 `navigateToPage(ref, focusHint = null)`；`hint == null` 时**只调 `settings.select(target)` 切页，不做任何定位**（`PluginSettingsFolderConfigurable.kt:194-200`）。

### 1.2 为什么落在页中间

- `ConfigurableCardPanel.create`（2024.3.7.1 平台）为每个 configurable 的组件套 `new Wrapper(...)` 并缓存；`createConfigurableComponent` 在未实现 `Configurable.NoScroll` 时**外包一个 JScrollPane**。
- 页面组件按 configurable 缓存 → 同一 IDE 会话内再次打开该页时，**滚动位置沿用上一次停的地方**。SQL 日志页有 6 个分组（捕获 / 列表 / 配色 / ORM / 自动保存 / 源码捕获），高度远超一屏，于是「落在中间」。
- 齿轮路径 `hint = null` 不做任何滚动复位，所以这个中间位置就原样呈现给用户。

结论：不是布局错乱，是**平台缓存 + 无复位**的确定性行为。

## 二、方案比较

| 方案 | 做法 | 成本 | 代价 |
|---|---|---|---|
| **A. 页顶复位** | `select` 回调后对目标页根组件 `scrollRectToVisible(Rectangle(0,0,1,1))`（沿祖先链滚到外包 JScrollPane） | ~20 行 + 1 用例 | 无（不闪、不改 UI） |
| B. 行级锚定 | 齿轮传 hint（如「项目启动时自动开始监听」），复用 `focusTarget` 的滚动 + 黄闪 + 橙框 | 1~2 行 | 每次点齿轮都黄闪一下，猜错行更吵 |
| C. 信息架构重排 | SQL 日志页 6 组按频次重排，高频组置顶 | 中等 | 要同步搜索索引顺序与文档；不解决「落在中间」本身 |
| D. 面板内常用项浮层 | 齿轮改下拉菜单（自动打开 / 让位 / 去重 / 时间戳 / 最大条数 + 「全部设置…」） | 较大 | 长期最优，适合独立立项 |

## 三、拍板结果

- **A 立即实施**：确定性修复，零打扰，且对「双击子页列表进入」同样生效（该路径 `hint` 也是 null）。
- B 暂不做：黄闪在「只是想换个设置」的场景偏吵；若后续反馈「想要落到相关行」再单独加 hint。
- C / D 记入后续排期，不并入本任务。

## 四、实施内容

- `PluginSettingsFolderConfigurable.navigateToPage`：`focusHint == null` 分支改为 `callback.doWhenDone { scrollTargetToTop(target) }`；带 hint 的搜索跳转路径不变。
- 新增 `scrollTargetToTop(target)`：与 `focusTarget` 同拍（双 `invokeLater`）等页面装配完成后复位；失败静默（不影响导航）。
- 新增 `internal fun scrollToTop(component: JComponent)`：纯 UI 操作，测试缝。
- 用例：`PluginSettingsFolderConfigurableTest` 增「缓存页滚到中部 → `scrollToTop` 后 viewPosition.y == 0」。
- 文档：平台机制写进本计划；`docs/架构设计/02-核心流程详解.md` 设置页导航节补一句复位语义；CHANGELOG `[Unreleased]` 一条。

## 五、待验证前提（实施前先证伪）

- P-1：平台 `ConfigurableCardPanel` 确实缓存页面组件并外包 JScrollPane（已由 2024.3.7.1 sources jar `ConfigurableCardPanel.java:58-64`、`createConfigurableComponent` 的 NoScroll 分支实读确认）。
- P-2：`root.scrollRectToVisible(Rectangle(0,0,1,1))` 能命中外包滚动容器（`JComponent.scrollRectToVisible` 沿父链找 `JViewport`；headless 用例可钉住）。
- P-3：复位时机不早于页面装配（沿用 `focusTarget` 已在用的双 `invokeLater` 拍）。

## 六、验收判据

- 定向：`PluginSettingsFolderConfigurableTest` 全绿（新增复位用例 + 既有 11 例）。
- 反向：把复位分支改回不调用 → 新用例必须红。
- 沙盒走查（人工）：先把 SQL 日志设置页滚到中部 → 关设置 → 点日志面板齿轮 → 应停在页顶。

## 七、实施记录

- 2026-11-25 实施（A 方案）：
  - `PluginSettingsFolderConfigurable.navigateToPage`：`focusHint == null` 走 `callback.doWhenDone { scrollTargetToTop(target) }`；带 hint 路径不变。
  - 新增 `scrollTargetToTop(target)`（双 `invokeLater`，与 `focusTarget` 同拍；失败静默）与 `internal fun scrollToTop(component)`（测试缝）。
  - `PluginSettingsFolderConfigurableTest` 增 `testScrollToTopResetsCachedPageScroll`（先滚到中部 120，再复位断言 0）。
- 定向读数：`PluginSettingsFolderConfigurableTest` **12/0/0/0**（原 11 例 + 1 新例）。
- 反向验证：`scrollToTop` 去掉 `scrollRectToVisible` → **恰 1 红**（12 例 / 1 失败），还原后 12/0 复绿。
- not run：runIde 沙盒走查（需 GUI）——建议人工按 §六 第三条目验一次。
- 提交：待回填。
