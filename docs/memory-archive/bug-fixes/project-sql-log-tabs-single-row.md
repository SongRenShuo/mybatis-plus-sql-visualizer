---
name: project-sql-log-tabs-single-row
description: SQL 日志工具窗四页签恒单行（O-149）终态——首版 SCROLL 失败后改平台 JBTabs 单行压缩+MorePopup；262/用户主体形态已确认，243/253、极窄宽与真实溢出仍属 V182 正式 UI 验收债。
metadata:
  node_type: memory
  type: project
  originSessionId: sess_897d62e7-4d9a-40ed-8d63-e7dcc77e1183
---

# SQL 日志页签恒单行（T-SqlLogTabsSingleRow，O-149）——已实施

20261014 用户原话：「这个 tab 能不能也优化下 现在侧边栏变窄，上方 tab 实际上是可以一行的，可以参考下 idea 打开多个类时候的交互方式，生成开发计划文件并实施」。计划册 `docs/开发计划/20261014-2300-T-SqlLogTabsSingleRow-*`。占号 **O-149 / R99 / D159 / V182**。

## 根因（一行代码级）

`SqlLogPanel.kt` 的页签容器原是裸 `JBTabbedPane()`，**没设 `tabLayoutPolicy`** → `JTabbedPane` 默认 **`WRAP_TAB_LAYOUT`**，页签总宽一超容器宽度就按行折（用户截图的两行正是这个默认值，四个页签本身接近放得下）。

## 已落地修法

1. `tabLayoutPolicy = SCROLL_TAB_LAYOUT` —— 页签**永不折行**，宽度不足时由平台按滚动/溢出处理；
2. `setTabComponentInsets(Insets(2,8,2,8))` 收紧页签内边距 —— 让四个页签在用户侧栏宽度下正好一行、通常不需要滚动（该 API 在**本机 243 与 262 两个发行版 jar 上 `javap` 双版本实证存在**）。

## 定稿形态（用户报障后按提示「反编译 idea 源码看怎么复用」得出，取代上面两条）

**用户报障**：「出现了个严重的问题」＋截图——窄侧栏下四个页签被平台收成一枚下拉箭头，工具栏图标也被挤掉。**根因是我那两条修法把每个页签的所需宽度推大了**（实测 ~321px → ~385px），我自己量的判据（策略正确 + 360px 几何不折行）**在更窄的真实侧栏里全部失效**——「一行」在 385px 放不下时就退化成「一枚箭头」。

**正解＝复用平台自己的页签实现**（编辑器页签同款），三行即可：

```kotlin
val tabs = com.intellij.ui.tabs.JBTabsFactory.createTabs(project, this)
tabs.presentation.setSingleRow(true)          // 恒单行
tabs.presentation.setSupportsCompression(true) // 放不下时压缩
```

- **机制来源**：`JBTabsFactory.createTabs` 产出的 `JBTabsImpl` **实现 `MorePopupAware`**（`canShowMorePopup()` / `showMorePopup()` / `getMoreToolbar()`）——「一行 + 放不下先压缩 + 再放不下出『显示全部页签』下拉」是平台现成能力，**不需要自造策略**。
- API 在 **243 与 262 两个发行版 jar 上 `javap` 验过在场**（原先把「构造签名跨版本漂移」列为不做 JBTabs 的理由，实测不成立）。
- 页签身份从「索引」迁到 `TabInfo` 句柄（`select(info,true)`、按文案匹配），同时迁到 `TabsListener.selectionChanged(current, previous)`——**平台签名两个参数可为空（`TabInfo?`），照抄非空签名会报「overrides nothing」**。
- 定稿提交 `7b163516`（首版 `5cba13b1` 是失败尝试，已在计划文档里留档），后续清理 `c1eaf771`。

**教训（比移植本身值钱）**：**判据的宽度必须覆盖「出问题的那段宽度」**——我的几何断言测的是 360px，而用户侧栏更窄，于是「策略对了、断言绿了、用户那里还是一枚箭头」。现在宽度决策全交回平台，「够宽就四个一行 / 极窄才压缩或下拉」= 编辑器行为，不会再有我自定的阈值。

## 备选（已作废，原登记 V182）

换平台 `JBTabs` + `TabLayout.SHRINK`，即编辑器页签那种「缩到最小 + 下拉列出全部」的交互。不做的理由：①该容器构造签名在 243→253→262 间有漂移面（须先补跨版本签名门禁）；②现面板的页签切换/懒加载/`tabsForTest` 断言全建立在 `JTabbedPane` API 上；③用户核心诉求是「一行、别折」，已满足。要换按 V182 先做签名门禁。

## 验证

- 守卫用例两条判据互为冗余：**策略必须是 SCROLL**（非 WRAP）＋ 按 360px 窄宽度布局后**四个页签的 `y` 必须唯一**。只测策略会漏「策略对了但仍折」，只测几何会漏「碰巧放得下」的假绿。
- 另加**正对照**：把同一套页签文案放进 WRAP 策略的容器、在 200px 下**必须折行**——证明那条几何判据确实能看见折行，不是恒绿摆设。
- 反向验证：把策略改回 WRAP → 策略断言精准红（`expected:<1> but was:<0>`）；还原后 16 个用例全绿。
- **证据边界**：我的沙盒面板宽度（约 440 逻辑像素）下四个页签本来就放得下，折行只在更窄时出现，而我无法用合成拖拽把工具窗拖窄 ⇒ **窄宽真机截图仍需用户在自己 IDE 上拖窄确认**；替代证据是那条 360px 无头几何断言 + WRAP 正对照（测的正是折行发生的那段宽度）。

- **最终交付态**：无自建「更多」、四个页签单行、宽度决策全交平台。**合并与清理**：分支已并入 master（合并提交 `2d188175`，未 push），工作树与分支已删。
- **真机（262 沙盒）复看通过**：四个页签一行、编辑器式样式（选中带框）、点「SQL 历史」正常切换、工具栏八个动作图标都在。
- **仍未验**：真实 460px 极限窄栏下的图标溢出观感（我无法用合成拖拽把工具窗拖得足够窄）——这一项保留给用户。

## 关联

- 同一面板族：[[project-sql-list-highlight-layout-gap]]（O-146）、[[project-sql-log-tail-follow-gap]]（O-148）。三者同改 `SqlLogPanel.kt`，须串行。
