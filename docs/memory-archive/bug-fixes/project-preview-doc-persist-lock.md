---
name: project-preview-doc-persist-lock
description: T-PreviewDocPersistLock（2026-11-02 立项待实施）——预览渲染的 saveDocument
  在写动作外，EDT 硬抛 write thread only；测试盲区是平台测试框架全程持写意图
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d5c8281d-f99c-475a-a9fe-110bf89babc9
---

预览面板 `showSql` 的 nativeDoc 支链（`ui/SqlPreviewPanelSqlView.kt:684-707`）把 `saveDocument` 落在
`WriteCommandAction.runWriteCommandAction` **之后**，EDT 上无写意图 → 平台断言
`Access is allowed from write thread only` 硬抛（262 用户栈 2026-11-02；243 发行源
`FileDocumentManagerImpl:323-336 → TransactionGuardImpl:135-146 → ApplicationImpl:946-948 →
ThreadingAssertions:173-180` 四跳实证，非 262 专属）。全仓 5 处 `saveDocument` 仅此一处不在写动作内。
引入提交 `210ccbb28`（T-PS4-12，2026-10-10）：本意是「同临界区落盘」，落地成了「永久落不了盘」。
master `3ac007d7` / dev `f261e25a` / k2j `SqlPreviewPanelSqlViewKt.java:744` 三线均未修。

**Why:** 影响所有版本的日常操作（控制台挂载后勾选参数 / 返回预览 / 刷新），每次渲染都弹异常，
且 `syncToggleSelectLabels` / `refreshBindStateRow` / `lastRenderedSql` / 自动折叠全被跳过。

**How to apply:** 修法＝把落盘 6 行挪进同一个写命令块（仓内先例 `DataSourceSelectorResults.kt:796-811`）。
回归用例**必须**用 `com.intellij.testFramework.runInEdtAndWait(writeIntent = false)` 构造无写意图帧——
平台测试框架默认把整个测试体包进 `runWriteIntentReadAction`（`UsefulTestCase.java:515` →
`EdtTestUtil.java:45-58`），普通 `BasePlatformTestCase` 用例对本类缺陷恒绿（既有
`SqlPreviewPanelDocLifecycleTest` 就是 3/0/0 的空绿）。计划册：
`docs/开发计划/20261102-1125-T-PreviewDocPersistLock-…-开发计划.md`。参见
[[pitfall-fake-green-test-assertions]]、[[pitfall-262-edt-readlock-database-runners]]。
