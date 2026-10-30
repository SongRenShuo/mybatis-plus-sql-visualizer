---
name: master-log-search-wiring-lost
description: master 在售版本 SQL 日志页搜索接线丢失（T-SearchToggleNativeLook 迁原生时漏接
  DocumentListener），dev 已修 87c1141、master 已同修复 7e25276（2026-10-10）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2b5062e9-21ee-418e-83b4-1ca64f07a665
---

master 的 `T-SearchToggleNativeLook`（`2f992b2`）把 SQL 日志页搜索框迁到平台原生 `SearchTextArea` 时漏接 `DocumentListener`：`SqlLogPanel.onSearchTextChanged()` / `searchDebounceTimer` 成零调用孤儿、`javax.swing.event.DocumentEvent` / `DocumentListener` 两个导入未用、`hideSearch()` 仍按「非空时触发 DocumentListener 清过滤并刷新」写注释 ⇒ 日志页「键入即过滤」「隐藏搜索条即清过滤」两条真实路径失效。测试经 `applySearchTextForTest()` 直调内部入口（等价生产防抖回调），覆盖不到真实键入分派，故 master 自身门禁未暴露。

**Why:** 这是 master / 在售版本的活缺陷（非 dev 独有）；dev 侧接线原在 `buildSearchField` 的 DocumentListener 上，master→dev 合并（T-MergeMasterToDev2）弃该函数后一并消失才被发现。

**How to apply:** dev 已修（`87c1141`：`searchField` 声明后补 `init` 块经 `NativeSearchTextField.addDocumentListener` 接 `onSearchTextChanged`，并恢复两个导入）。**master 已于 2026-10-10 同修复**：`7e25276`（`git cherry-pick -n 87c1141` 零冲突——master 侧两 import 本就在，3-way 识别为已应用、未重复；只新增 init 块 13 行；定向 6 类 97 例 0 失败；未 push）。关联 [[project-pending-plans]]。

**为什么 master 全量测试 6716/0/0/8 全绿也没发现它**：搜索链的测试只断言「给定搜索文本后的过滤语义」——`SqlLogPanelFilterTest` 用 `searchFieldForTest.text=…` + `applySearchTextForTest()` 直调内部入口，**自己代替了「document 变更 → 防抖 → applySearchText」这一段**；全测试树对 `addDocumentListener`/`onSearchTextChanged`/`searchDebounceTimer` 零命中（无源码级接线守卫）；孤儿私有函数只出 Kotlin `unused` 警告、构建未开 warnings-as-errors 故不会红；原生 `SearchReplaceComponent` 也不会替面板调 `filterBySearch`。即**缺陷出在「事件接线」、覆盖只在「过滤算法」**。这是 [[pitfall-fake-green-test-assertions]] ㉑ 的实例。

**守卫已补（2026-10-10）**：`log/SqlLogPanelSearchWiringGuardTest`（dev `cc9e014`）——源码扫描断言 ① `nativeSearch.addDocumentListener(` 在场、② 三回调都落 `onSearchTextChanged()`、③ 防空绿 `onSearchTextChanged` ≥4 次、④ 防抖入口与计时器仍在；已按元守卫要求登记进 `GuardSelfTestCoverageMetaGuardTest.GUARD_CLASSES`（**新守卫类名含 Guard 就必须登记，否则元守卫红**）。反向验证：删接线 init 块 → 本用例 FAILED；还原 → 绿。master 侧同步随 [[project-line-ending-policy]] 同批提交。
