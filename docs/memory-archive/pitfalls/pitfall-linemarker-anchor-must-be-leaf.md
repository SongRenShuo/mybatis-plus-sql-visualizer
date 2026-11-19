---
name: pitfall-linemarker-anchor-must-be-leaf
description: 给 LineMarkerInfo 锚复合 PSI 节点（如 PsiLiteralExpression）会触发平台
  Logger.error「registered for leaf elements only」，测试里被 TestLogger 放大成连锁红；必须锚叶子
  token，点击 handler 由 token 上爬宿主
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2e6c34a8-130e-43a0-a784-b9d4d57a5188
---

# LineMarker 锚只认叶子元素（20261111 T-IbatisCallPreview 实测）

## 形态与后果

- 给调用点首参字符串字面量加预览图标时，写成 `LineMarkerInfo(first /* PsiLiteralExpression */, first.textRange, …)` → 平台 `LineMarkerInfo.createElementRef` 直接记 **error**：`LineMarker is supposed to be registered for leaf elements only, but got: PsiLiteralExpression:"user.selectById"`。
- 不止日志：`BasePlatformTestCase` 的 `TestLoggerFactory` 把该 error 变成测试失败，一次跑出 **19 例连锁红**（所有收集该 provider marker 的用例），栈落在测试的 `markers()` 助手 `collectSlowLineMarkers` 调用处——看起来像「批量用例坏了」，实际是一个构造点。

## 修法

- 锚 `first.firstChild ?: first`（STRING_LITERAL token），`LineMarkerInfo` 的 `textRange` 仍用整个字面量。
- 点击 handler 收到的 `elt` 是 token：`val literal = elt as? PsiLiteralExpression ?: elt.parent as? PsiLiteralExpression ?: return`。
- 测试比较 marker 锚时也要按 `it.element?.parent === literal`（或 textRange）判，不能再 `=== literal`。

## 判据

不要用「图标看起来画出来了」验收：复合锚在部分路径仍能渲染，但平台已记 error，测试与真机日志都会红。凡是新建 gutter marker，先确认锚点是 `firstChild == null` 的叶子。