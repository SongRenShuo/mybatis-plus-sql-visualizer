---
name: pitfall-persisted-experimental-setting-changes-path
description: 用户报「怎么还是老行为/还是弹窗了」时，先读**持久化设置文件**而不是读代码默认值——本仓实测：`explainStructuredRender` 代码默认 false，但沙盒 `config/options/mp-sql-visualizer.xml` 里是 true（早前点过一次「启用结构化并重试」被永久写入），用户看到的弹窗是另一条通道，与当前改动无关
metadata:
  node_type: memory
  type: project
  originSessionId: session_b380d49669e298cff6e310c72afca927
---

# 「还是老行为」先查持久化设置，别先怀疑改动

## 症状与真因（20261024 实测）

改了「执行计划走内嵌原生控制台」并验证通过后，用户仍报「点击执行计划还是弹窗了」。我一开始按代码查默认值（`var explainStructuredRender: Boolean = false`，`PluginSettings.kt:550`），一切正常。

真因在持久化状态里：

```
<intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/options/mp-sql-visualizer.xml>
  <component name="MpSqlVisualizerSettings">
    <option name="explainStructuredRender" value="true" />
```

早前某次用户点了通知里的「启用结构化并重试」，`enableStructuredExplainAndRetry` 直接 `state.explainStructuredRender = true` 并推 `SETTINGS_CHANGE_TOPIC` —— **永久写入，之后没有任何 UI 提示它现在处于实验形态**。开关为 true 时走的是结构化通道（形态 C，计划表格 + 非模态报告对话框），完全绕开我改的直送链。

## 排查顺序（下次照这个来）

1. **先读持久化值**，不是读代码默认值。本仓设置落在 `config/options/mp-sql-visualizer.xml` 的 `<component name="MpSqlVisualizerSettings">`，对应 `PluginSettings.State` 的字段名。
2. 确认写入方：是否有「通知动作 / 一次性提示 / 自动修复」路径会自己改这个开关（本仓 `enableStructuredExplainAndRetry`、`UpdateBackupStore`、`VersionIgnore` 都属这类）。
3. 再对着开关读分派代码（本仓 `submitExplainOrStructured` 的 `when(ExplainPanelChannel.decide(...))`），确认用户走的到底是哪条分支。
4. 判据：**用户描述的行为形态（弹窗 vs 内嵌面板）就能反推分支**——计划表格窗口 = 结构化；右侧下半区出控制台 + 顶部蓝色「返回预览」 = 直送链。不必先看代码。

## 可复用结论

- 「代码默认值 ⇒ 用户实际行为」是一条**不成立的推论**。凡是持久化的开关、尤其**由一次性提示动作写入**的开关，必须查文件。
- 这类「通知动作把实验开关永久点亮且不告知」本身是 UX 债。**本仓处置（用户 20261024 拍板「保留结构化默认关」）**：不删通道、不改默认值，而是给推荐动作加**一次性持久化标记**（`explainStructuredOfferedShown`），让通知只出现一次；标记只关「主动推荐」，**不阻断设置页显式入口**。
- **通用修法**：凡是「推荐/引导用户打开某个实验开关」的通知动作，都必须配一次性标记——否则它会在用户每次使用该功能时重复出现，被点一次就永久改状态且无痕迹可循。守卫要钉两件事：默认值不变、标记**真置位**（只写 `!flag` 条件不写置位＝假一次性）。

相关：[[project-explain-console-first]]（本案所属任务与三条链的形态对照、W6 实施与守卫）、[[project-explain-pwd-prompt]]（同族「设置项误当能力开关」的排查）。
