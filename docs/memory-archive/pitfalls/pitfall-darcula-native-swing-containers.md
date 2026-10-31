---
name: pitfall-darcula-native-swing-containers
description: 深色主题「白条」的真身是原生 JSplitPane 的 Metal 分隔条（shadow=#EEEEEE / darkShadow=#7A8A99）
  与 JScrollPane 的裸 JViewport 四角，不是滚动条本身；新增子页必须从第一笔就用平台组件，
  否则守卫要等合流那天才红
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 深色主题白条：原生 Swing 容器（2026-11-01 三轮定位实证）

## 症状与判据

用户报「SQL 预览窗口出现白条」。判据是**像素级**的，两轮对比靠固定色值：

| 形态 | 色值 | 来源 |
|---|---|---|
| 水平白带（实测 y=460~467 共 8px、跨全宽） | `(238,238,238)` / `(255,255,255)` | `SplitPane.shadow` 的 **Metal 值 `#EEEEEE`** |
| 竖直浅线（实测 x=385 贯穿左栏） | `(122,138,153)` | `SplitPane.darkShadow` 的 **Metal 值 `#7A8A99`** |
| 修复后应为 | 1px，深色下约 `#3c3f41` 一档 | `OnePixelSplitter` 取主题色 |

**这两个色值是决定性物证**——不必猜组件，扫到它们就是 Metal 分隔条。

## 机制（推翻了本仓先例的错误解释）

**先例（含我自己写进任务书的说法）说**：「原生滚动条走 Metal `BasicScrollBarUI`，不跟随 Darcula」——**实测是错的**：

- Darcula **注册了** `ScrollBar.UI=JBScrollBar` 与 `ScrollPane.border=DarculaScrollPaneBorder`，所以 `JScrollPane` 的滚动条**本来就是** `DefaultScrollBarUI`、边框本来也是主题的；
- `JScrollPane` vs `JBScrollPane` 的真实差异在 **viewport 与四角**：原生是裸 `JViewport`、`getCorner(...)` 返回 **null**（四角留白）；`JBScrollPane` 是 `JBViewport`（背景取所载 view）+ 四角 `Corner` 组件；
- `JSplitPane` 才是硬伤：Darcula **只给 `os.mac`** 注册 `SplitPane.UI`，Windows/Linux 仍是 `MetalSplitPaneUI`——`SplitPane.shadow=#EEEEEE`、`darkShadow=#7A8A99`、`dividerSize=10` 全是 Metal 值（主题只覆盖了 `highlight=#3c3f41`）。

⇒ **改 `JScrollPane`→`JBScrollPane` 主要治的是「四角留白」，不治分隔条**；分隔条必须 `JSplitPane`→`OnePixelSplitter`。

## 三条过程教训（比结论更值钱）

### 1. 「预览主界面干净」不等于「用户报的位置干净」

第一轮修复代理扫出 dev 侧 6 处原生 `JScrollPane` 并改完，报告写「SQL 预览窗口链路本身已无自建原生容器」——**它只看了主界面**（参数区/编辑器分隔、结果表底部，实测确实干净）。真正的白条在**「更多」菜单里的四个洞察子页**：比较SELECT结果 / Schema快照比较 / SQL差异对比 / 参数场景。

**这 4 个文件是功能分支独有、dev 上不存在**，所以 dev 单测绿、合进分支后守卫立刻点名 **11 处**。

**判据**：修「某窗口的白条」时，必须把该窗口的**所有子页/子弹窗**列出来逐个扫，不能只扫打开它第一眼看到的那层。

### 2. 守卫没有漏检，漏的是修复位置

`NativeSwingThemeGuardTest` 在 dev 上绿（文件不在），合并进分支后精确报出 11 处，含文件名与行号。**这说明守卫设计对了**——它会在合并那天自动抓出来。

但代价是：**问题在没测过的分支上活了很久**。所以

### 3. 新增子页时，自建容器必须从第一笔就用平台组件

这是本条最重要的行动项。判据写进守卫：

```kotlin
// 只认构造点，不认 is / as?
// 平台给的内嵌控制台组件（DataSourceSelector*.kt 里的 JSplitPane cast）不算白条来源
(?<![.\w])(JSplitPane|JScrollPane)\s*\(
```

**为什么「只认构造点」是必须的**：`execute/DataSourceSelector*.kt` 与 `ui/SqlPreviewPanelExecution.kt` 里有大量 `JSplitPane` 引用，但都是对 **IDE 内嵌数据库控制台**组件的 cast/操作（UI 由平台装）。把它们也算进来会导致误报，进而逼后来人加白名单——**守卫就被废掉了**。

唯一例外是 `execute/DataSourceSelectorResults.kt:1520` 的 `buildConsoleSplitPane`：那是自建原生 `JSplitPane`，但已覆写 `paint` 用 `UIUtil.getPanelBackground()` 重涂、并设了 `background`/`dividerSize=3`/空 border——属「允许形态」。它绑着内嵌控制台的 reparent 时序，换 `OnePixelSplitter` 会动 M36.5.x 整套 divider 修复机制，故保留。

## 修法清单（本仓现状）

`JSplitPane` → `OnePixelSplitter(firstIsVertical, proportion)`，嵌套时保持原 `resizeWeight`：
- 外层垂直 0.3 / 0.35，内层水平 0.5（`ResultComparison`、`SchemaInsight` 同构）
- 单层垂直 0.3（`ObservedSqlDiff`）

`JScrollPane` → `JBScrollPane`（`com.intellij.ui.components.JBScrollPane`）。

## 诚实边界

无头 LAF 探测**未能**复现全宽浅色带——守卫约束的是**结构形态**，不承诺解释每一处浅色。所以「守卫绿」不能替代「真机看不到白条」，两者都要。

相关：[[pitfall-combobox-stretches-in-boxlayout]]（另一个 BoxLayout 布局坑）、`docs/架构设计/01-项目架构总览.md` 的自绘组件口径。
