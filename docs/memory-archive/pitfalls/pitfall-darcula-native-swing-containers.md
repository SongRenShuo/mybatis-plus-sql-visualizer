---
name: pitfall-darcula-native-swing-containers
description: 深色主题白条＝原生 JSplitPane 的 Metal 分隔条（shadow #EEEEEE / darkShadow #7A8A99）与
  JScrollPane 的裸 JViewport 四角；不是滚动条。第一轮修复只扫了预览主界面，漏了「更多」子页；
  新增子页必须从第一笔就用平台组件，否则守卫要等合流才红
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 深色主题白条：原生 Swing 容器（2026-11-01，三轮定位）

用户报「SQL 预览窗口白条」。**判据是像素级的**：水平白带 `(238,238,238)`/`(255,255,255)` = `SplitPane.shadow` 的 Metal 值 `#EEEEEE`；竖直浅线 `(122,138,153)` = `darkShadow` 的 Metal 值 `#7A8A99`。扫到这两个色值就是 Metal 分隔条，不用猜组件。

**机制（推翻了本仓先例的解释）**：Darcula 注册了 `ScrollBar.UI=JBScrollBar` 与 `ScrollPane.border=DarculaScrollPaneBorder`，所以 `JScrollPane` 的滚动条**本来就是**主题的——`JScrollPane` vs `JBScrollPane` 的真实差异在 **viewport 与四角**（原生是裸 `JViewport`，`getCorner()` 返回 null）。而 `JSplitPane` 才是硬伤：Darcula **只给 `os.mac`** 注册 `SplitPane.UI`，Windows/Linux 仍是 `MetalSplitPaneUI`（shadow/darkShadow/dividerSize=10 全是 Metal 值，主题只覆盖了 `highlight`）。⇒ **`JScrollPane`→`JBScrollPane` 治四角留白，不治分隔条；分隔条必须 `JSplitPane`→`OnePixelSplitter`**。

**三条过程教训**：
1. **「预览主界面干净」≠「用户报的位置干净」**——第一轮修复代理只看了主界面（确实干净），白条实际在「更多」菜单的**四个洞察子页**（比较SELECT结果 / Schema快照比较 / SQL差异对比 / 参数场景）。判据：修某窗口的白条，必须把它**所有子页/子弹窗**列出来逐个扫。
2. **守卫没漏检，漏的是修复位置**——那 4 个文件是功能分支独有、dev 上不存在，所以 dev 单测绿、合进分支后守卫立刻精确点名 11 处。代价是问题在没测过的分支上活了很久。
3. **新增子页时自建容器必须从第一笔就用平台组件**——这是行动项。

**守卫设计要点（`NativeSwingThemeGuardTest`，dev `91cc4c6d`）**：只认**构造点** `(?<![.\w])(JSplitPane|JScrollPane)\s*\(`，**不认 `is`/`as?`**——`execute/DataSourceSelector*.kt` 里那些 `JSplitPane` 都是对 IDE 内嵌控制台组件的 cast/操作（UI 由平台装），算进来会误报，进而逼人加白名单**把守卫废掉**。唯一保留的自建原生是 `DataSourceSelectorResults.kt` 的 `buildConsoleSplitPane`（已覆写 `paint` 重涂 + 设 background/dividerSize=3，属允许形态；换 splitter 会动 M36.5.x 的 divider 修复机制）。

**修法**：`JSplitPane`→`OnePixelSplitter(垂直, proportion)`（嵌套保持原 resizeWeight：外层 0.3/0.35 垂直、内层 0.5 水平）；`JScrollPane`→`JBScrollPane`。

**诚实边界**：无头 LAF 探测复现不出全宽浅色带——守卫约束的是**结构形态**，所以「守卫绿」不能替代「真机看不到白条」，两者都要。

完整版（含像素坐标、机制字节码依据、守卫例外条款）在仓库 `docs/memory-archive/pitfalls/pitfall-darcula-native-swing-containers.md`。相关：[[pitfall-combobox-stretches-in-boxlayout]]、[[project-selfpainted-component-laf-parity]]。