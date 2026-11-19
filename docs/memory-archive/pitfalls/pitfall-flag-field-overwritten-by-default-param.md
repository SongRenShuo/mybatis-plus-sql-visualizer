---
name: pitfall-flag-field-overwritten-by-default-param
description: 跨调用标志位被默认参数就地覆盖致动作静默失效（置 true 后调带默认 false 的函数，标志被抹掉）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f25fa142-a92e-4a57-8dd2-8177c26c4dd8
---

独立预览工作台「在弹框中打开」点了完全没反应（用户 2026-11-18 主仓沙盒实测，`SqlStandalonePreviewPanel.kt`）：

```kotlin
// 动作体
openDialogPending = true      // 先置真
resolveFromInput()            // 不带参调用

// 被调方
fun resolveFromInput(openDialog: Boolean = false) {
    ...
    openDialogPending = openDialog   // 用默认值 false 把刚置的 true 抹掉
```

下游 `applyResolved` 永远读到 `false`，`SqlPreviewService.showResult(...)` 那一行永不执行——用户看到的效果只是「重新解析 + 刷新页内嵌面板」。**确定性 100% 复现**，与输入内容、数据源、编辑器状态、时序全无关。

**判据**：按钮点了「完全没反应但页面有轻微刷新」时，先查调用方置的标志位是否被被调方的默认参数覆盖，别先怀疑线程/焦点/权限。

**修法纪律**：意图（要不要弹窗）必须作为参数沿调用链穿透——`val` 捕获进后台闭包、随代次守卫一起回传 `applyResolved(resolved, openDialog)`；**不留在跨调用的可变字段里**。同字段「动作置真 / 函数覆盖 / clear 复位」三写一读是复发温床，修的时候连字段一起删（只改一行传参会过，下次加「打开并执行」入口会以同样方式再破一次）。

**2026-11-18 已修（master `fdfee15a3`）**：字段删除、`openDialog` 作为 `val` 捕获进后台闭包随代次守卫回传给 `applyResolved(resolved, openDialog, rawText)`；同时放开空输入（弹空编辑器）与未识别文本（原文 + 信息级 ℹ）。

**回归测试必须走真实动作路径**：把 `OpenInDialogAction` 从 `private` 提为 `internal`，用例直接 `p.OpenInDialogAction().actionPerformed(event)` 再轮询 `activePreviewDialogForTest`——只测面板方法会漏掉「动作传参」这一层，而缺陷恰好就在这一层。反向读数：动作改回 `resolveFromInput()` 无参 → 4/4 全红。

相关：[[project-sql-preview-standalone-20261118]]、[[pitfall-headless-dialogwrapper-isdisposed-and-registry]]
