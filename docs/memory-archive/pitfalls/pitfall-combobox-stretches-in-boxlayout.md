---
name: pitfall-combobox-stretches-in-boxlayout
description: Swing BoxLayout(Y_AXIS) 里直接 add JComboBox 会被拉伸到容器最大宽度，弹出层横跨整屏
metadata:
  node_type: memory
  type: project
  originSessionId: session_502360ae808bfb35c69ac231233cbe92
---

# BoxLayout(Y_AXIS) 里裸放 JComboBox 会被撑满宽度

**现象**（20261023 实测，`ExplainPlanResultView` 的 EXPLAIN 基线下拉）：点开下拉后，弹出层横跨**整个屏幕宽度**，旁边的标签也跟下拉错位。用户一眼看出「这个下拉选项太长了」。

**根因**：`BoxLayout` 沿 Y 轴排布时，子组件宽度取 `maximumSize`；`JComboBox` 默认 `maximumSize` 近似无限，于是被拉到父容器最大宽度。若父容器本身很宽（本例是 EXPLAIN 结果表格所在的弹窗内容区），下拉与它的弹出层就跟着横跨整屏。

**修法（三件一起做才稳）**：
1. 把标签 + 下拉包进 `FlowLayout(FlowLayout.LEFT, gap, gap)` 的横向子面板，不要分别 `add()` 到纵向 BoxLayout；
2. 给下拉显式 `preferredSize = Dimension(JBUI.scale(220), 原 preferredSize.height)`；
3. 子面板 `maximumSize = Dimension(Int.MAX_VALUE, 子面板 preferredSize.height)`，锁死单行高度，避免被纵向布局拉高。

**守卫测试写法**（两条分别钉住缺陷的两个特征，缺一不可）：
- 断言 `selector.preferredSize.width in 1..400`（旧实现无限增长）；
- 断言 `selector.parent.layout is FlowLayout` 且同容器内存在非空 `JBLabel`（旧实现是纵向堆叠）。

**通用判据**：任何「下拉框/输入框突然横跨整屏」，先查它是不是被直接加进 `BoxLayout(Y_AXIS)` 或 `BorderLayout.CENTER` 而没有尺寸约束。

相关：[[project-docsrefresh1023-remaining-recordings]]、[[project-selfpainted-component-laf-parity]]
