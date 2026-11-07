---
name: pitfall-jsplitpane-divider-clamped-by-minimum
description: JSplitPane.setDividerLocation 会被子组件 minimumSize 钳住——折叠结果区只推 divider 到 1.0 仍留 160px，必须连最小高度一起放开并给自动校正打让路标记
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3045a9d4-9fa0-4752-affe-3a191dc8b96f
---

2026-11-03 真机：预览面板「结果栏不应该显示出来，这样会占用 SQL 预览的空间，只有执行 SQL 了才显示」。

清场（旧 Result 页签已删、`resultAreaHasContent=false`）之后折叠判据**已经**返回 true，
`setDividerLocation(1.0)` 也执行了，但截图里 divider 仍停在 ~0.67、底部留 ~165px。

**真因**：结果区为「执行后不被压回 preferred 84px」设了 `minimumSize = (w, 160)`（M36.5.64），
而 `JSplitPane.setDividerLocation` 会被子组件的最小尺寸钳住——推 1.0 只推到
`height - bottomMin - dividerSize`。只清内容、不清最小高度，折叠永远折不到底。

## 修法（本仓 `applyConsoleBottomCollapse`）

```kotlin
sp.bottomComponent?.let {
    it.minimumSize = if (collapse) Dimension(0, 0) else Dimension(it.minimumSize?.width ?: 400, 160)
}
sp.putClientProperty(BOTTOM_COLLAPSED_MARK, collapse)   // "mpsql.bottomCollapsed"
sp.setDividerLocation(if (collapse) 1.0 else 0.6)
```

两个配套点，缺一就会「折了又弹回来」：

1. **自动校正必须让路**：`realignDividerOnce(sp, force=false)` 的判据含 `topH > total*0.68 → 0.6`，
   折叠后 topH≈total 必然命中，任何一次 resize 都会把结果栏弹开 → 非 force 时先看
   `BOTTOM_COLLAPSED_MARK` 直接 return；`force=true`（执行换入）反过来清掉标记。
2. **展开要恢复兜底**：执行换入路径（`swapResultIntoPersistentConsole` / EXECUTE 嵌入）调
   `applyConsoleBottomCollapse(sp, false)`，否则结果区失去 160px 下限、被 DBN 压回 84px。

## 后续两处复发点（同日真机日志抓到，均已修）

3. **回收守卫会把兜底写回去**：`reclaimResultBottom` 在 DBN 抢走结果组件后挂回时**无条件**
   `candidate.minimumSize = (…, 160)`——折叠成功 2.5 秒后被它弹回 ~0.67。日志签名：
   `[Collapse] … collapse=true divider=304→508` 紧跟 `result reclaim guard: bottom re-mounted`。
   修法：守卫读 `BOTTOM_COLLAPSED_MARK`，折叠态保持 0 兜底并重新 `setDividerLocation(1.0)`，
   日志补 `collapsed=` 判读位。**这是本轮最终的真凶**，前两版修法都因它白做。
4. **折叠后放大**：split 变高时 divider 停在旧的**绝对**位置，新增高度全分给结果区（h 150→490 最明显）。
   修法：折叠态挂常驻 resize 监听重新贴底（`resizeWeight=1.0` 只是辅助，headless 实测不足以替代）；
   `prepare/reuse` 两处折叠加 `height > 0` 守卫（h=0 上算出的 `divider=0` 会让底部吃掉全部新增高度）；
   `buildConsoleSplitPane` 里那个「无条件 0.6」的一次性监听也要读标记跳过。

## 判据与验证

- 无头可测：`sp.setSize(400,600)` + `bottom.minimumSize=(400,160)` → 折叠后
  `bottom.minimumSize.height == 0` 且 `dividerLocation >= height*0.95`（实测 590/600，不是满 600——
  仍有一层内建钳制，但不影响观感）；再断言 `realignDividerOnce(force=false)` 不改 divider。
- 反向验证：把「清 minimumSize」那行摘掉，折叠断言立刻红（divider 回到 ~0.67）。

## 同类注意

- 同一 split 上可能挂着**多个一次性 `componentResized` 监听**（构建期强制 0.6 的 + 面板侧折叠判据的）。
  AWT 按注册顺序通知，后注册的赢——面板侧监听在 `embedConsole` 里注册，晚于构建期那个，所以折叠生效；
  改动注册顺序会静默反转结果。
- 折叠态别只看 `dividerLocation`，`bottomComponent` 本身仍在 split 里（只是 0 高）——判「有没有结果区」
  要看 `bottomComponent` 与 `resultAreaHasContent`，不是看 divider。

关联 [[pitfall-dbn-reuses-content-panel-across-consoles]]（清场那一层）、
[[project-embedded-native-console-scope]]（业务背景）。
