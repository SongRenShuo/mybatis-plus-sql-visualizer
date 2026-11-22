---
name: pitfall-platform-ui-tests-not-in-testfast
description: 平台 UI 测试（BasePlatformTestCase）不在 testFast 快桶内，改设置页/面板布局只跑定向+testFast 会漏红
metadata:
  node_type: memory
  type: project
  originSessionId: session_baf1a6860046f3231a35b405c5ab4037
---

改设置页/面板布局时，「定向测试绿 + testFast 绿」**不能推出全量绿**：`BasePlatformTestCase` 派生的平台 UI 测试（`config/StorageRowLayoutTest`、`FrameSkipPrefixesFieldVisibilityTest`、`PluginSettingsPanelUiTest`、`SourceFramePrefixesPanelValidationTest` 等）不在 testFast 快桶里，只有全量 `testShards` 才跑到。

**实证（2026-10-25，t-magpie-gems）**：T-MagpieGems W2 把「展示脱敏」复选框改成「模式下拉 + 白名单文本域」，分支上定向与 testFast 全绿，但全量首跑 6 红——两类连带：① 长文案从复选框自身文本挪到**行标签**（GridBag 标签列宽 = 该段最长标签）⇒ 英文 UI 整页 preferred 838→1042px 溢出视口；② 段内多一个 JTextArea + 一个 JComboBox ⇒ 按「树里第 N 个 JTextArea」定位的测试取错控件。

**Why**：快桶按「源码零 `com.intellij`」分桶，平台 UI 测试天然带平台依赖，全被排除；而布局类缺陷只在真实组件树布局后才现形。

**How to apply**：
- 改设置页/面板/对话框布局，或往某个 section 增删控件（尤其**长文案改当行标签**、增删 JComboBox/JTextArea）→ 必须跑全量 `testShards`，不能只跑定向。
- 写这类测试时**不要按「树里第 N 个某类型控件」定位**（新增同类型控件即静默取错目标、断言看似有效实则测错对象）——直接引用面板字段（`panel.frameSkipPrefixesField` 等）。
- 行标签长度是布局硬约束：长说明放 tooltip，别放标签列。

相关：[[project-magpie-merge-master-20261025]] [[pitfall-guard-invariants-must-be-contrastive]]
