---
name: pitfall-flowlayout-multiline-height
description: Swing FlowLayout 的 preferredSize 在未布局时按单行估算——按钮换行后高度仍报一行，父容器 NORTH
  高度偏小，第二行被下方组件压住（不可见且点不动）；修法＝父容器 doLayout 注入可用宽度后累计行高
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# FlowLayout 多行高度被低估（20261031 UI-001 实测）

**现象**：洞察面板操作行在窄宽度下换行后，**第二行按钮被下面的状态文案压住**——4.5「对象使用与影响」的「反查对象引用/打开选中引用/取消/关闭分析」四个按钮、4.8「SELECT 结果比较」的第三个勾选，屏幕不可见且点击无效。GUI 走查里最迷惑的一点是 **robot 组件树能查到 bounds、合成点击却不响应**（bounds 是布局分配值，实际被覆盖区吃掉）。

**根因**：`FlowLayout` 的 `preferredSize` 在**未布局时按单行**估算。按钮换行后实际占两行，但报出的高度仍是一行，外层 `BorderLayout` 的 NORTH 区域高度随之偏小，CENTER 里的状态文案压上来。更麻烦的是循环依赖：`preferredSize` 需要知道可用宽度，而宽度恰恰来自父容器的布局结果——直接读 `parent.width` 在首次布局时为 0，仍退回单行估算。

**修法（已验证）**：自定义 `WrapFlowPanel`（继承 `JPanel(FlowLayout)`）——
- 暴露 `preferredWidthHint`，由**父容器在 `doLayout` 里注入**容器可用宽度（`width - insets`）；
- `getPreferredSize` 用该宽度逐个子组件累计行高（含 `hgap`/`vgap`）；
- 宽度未知（hint=0 且 parent.width=0）时退回 `super`，不抛异常；
- 父容器只在 hint **值变化时** `revalidate()`，有条件收敛、无死循环。

**守卫**：`InsightDetailPanelLayoutTest` 四条各钉一个方向——窄宽度必报多行、**宽宽度必报单行（防「为了不被裁而恒撑两行」的过度修正）**、宽度未知不抛且高度为正、doLayout 正确注入宽度。反向验证：摘掉宽度注入（RED PROBE）→ 窄宽度用例 FAILED；还原 → 4 例全绿。

**适用范围**：任何「FlowLayout 里装数量不定的按钮/标签 + 父容器高度依赖 preferredSize」的 Swing 布局。同类但机制不同的是 [[pitfall-combobox-stretches-in-boxlayout]]（BoxLayout 按 maximumSize 拉伸 JComboBox）。
