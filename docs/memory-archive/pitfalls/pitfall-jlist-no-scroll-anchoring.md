---
name: pitfall-jlist-no-scroll-anchoring
description: JList 没有滚动锚定——头删一条就把用户正在看的内容整体上移一行；且模型变化自触发的 AdjustmentEvent
  与用户手势在监听器里不可区分（20261029 日志列表报障实测）
metadata:
  node_type: memory
  type: project
  originSessionId: session_df89a78c478626d688d234d8f0a92706
---

# JList 无滚动锚定 + 程序性滚动与用户手势不可区分

2026-10-29 用户报「手动滑动 SQL 列表后，新 SQL 到达仍把正在看的内容顶上去」。根因不在暂停逻辑（暂停已生效），而在 Swing 列表的两条固有语义。

**① 头删即内容上移（无滚动锚定）**
`JList` 的视口位置是**像素锚定**的：`listModel.remove(0)` 之后 `viewport.getViewPosition()` 一点不变，但所有行整体上移一行高，视口顶部换成了下一条。变行高（`fixedCellHeight = -1`，如多行渲染模式）时一行高可达约 100px，跳动极其显眼。隔离探针读数（JDK8 纯 Swing，100 行变高）：

```
before: viewPos=(0,1200) topIndex=30 name=row-30 barMax=8000  extent=397
after : viewPos=(0,1200) topIndex=30 name=row-31 barMax=7968  extent=397
```

**② 模型变化会自行触发 AdjustmentEvent**
`remove(0)` / `addElement` 各触发一次 `valueIsAdjusting=false` 的调整事件（`barMax` 8000→7968→8000）。因此「滚到底部就恢复自动跟随」这类监听器无法区分「用户滚到底」与「模型变化/程序性滚动」，用户停在距底不足一行高时会被 clamp 到 `maxScrollable`，正好命中 `value >= maxScrollable - 2` 判据 → 静默恢复跟随，用户再滚也拉不回来。

**修法**：变更前记 `(锚点条目, 行内像素偏移)`，变更后按锚点条目新 index 复原 `viewPosition`；程序性滚动统一走标志位，期间跳过「回到底部」判定（try/finally 复位）；`bar.value = bar.maximum` 本身写错了量，应为 `maximum - visibleAmount`。

**探针配方（不进 gradle 构建）**：`tmp/` 下写一个纯 Swing 探针，`javac -encoding UTF-8`（不指定编码会按 GBK 报「不可映射字符」）、无 JFrame、手工递归 `doLayout` 两遍。两个坑：`vp.getVisibleRect()` 返回的是 **viewport 自己的 (0,0) 矩形**，取视口左上角必须用 `vp.getViewPosition()`；`list.locationToIndex()` 用的是列表坐标系。

**③ clamp 有方向，可以当判据（20261029 实施轮补）**
「内容变矮」触发的 clamp 只会让 `bar.value` **变小**（`maximum - visibleAmount` 缩了），而用户往底部滚一定让 value **变大**。所以监听器里加一条单调性否决即可把「clamp 误判成用户手势」这条通道关掉：`value < previous` 一律不恢复跟随。这是比「程序性标志位」更硬的一层——标志位只覆盖我们自己发起的滚动，clamp 发生在模型变更/布局期，那时窗口常常已经不是我们的了。

**④ 修好上游补偿后，下游那条通路会消失（假绿陷阱）**
本仓实施时先做了「头部淘汰按被删行高补偿视口」，之后「内容变矮 → clamp 到底」根本不再发生 ⇒ 原来用来验 clamp 误恢复的**集成用例失去区分力**：把单调性判据整条摘掉，它照样 15 例全绿。实测处置＝把判据抽成纯函数 `isUserScrollToBottom(previous, current, maxScrollable, programmatic)` 并补六形态单测，再摘判据才得到恰 1 红。⇒ 通用教训：**上游修复会吃掉下游缺陷的通路**，任何「防误触发」类守卫在别处修好之后，必须重新确认它的用例还能不能红；不能红就把判据抽成纯函数单测。同族形态见 [[pitfall-fake-green-test-assertions]] 与 [[pitfall-guard-invariants-must-be-contrastive]]。

**⑤ 实施侧的最终形态（本仓已落）**
`HeadEvictAppend` 分支先量 `getCellBounds(0, evicted-1).height`（O(被删行数)），变更后把 `viewport.viewPosition.y` 上移同一高度（等价内容原地不动）；全量重建（改排序/清搜索）走「按条目身份记锚点 → 条目出现后再复原」，落在后续批次就逐拍重试；模型变更 + 锚点复原整体包在 `withProgrammaticScroll`（计数器非空可嵌套，`try/finally` 复位）。

**Why**：这类缺陷看起来像「暂停失效」，读状态机永远读不出问题——暂停态是对的，是视口在模型变更时被无声挪走；不先量 `viewPosition` 与顶部条目就会误改状态机。
**How to apply**：凡是「列表 + 尾部跟随/暂停」的实现，验收必须断言「顶部可见条目对象不变」这一可观察量，而不是断言状态机值；同时把「程序性滚动」与「用户手势」在监听器层分开。相关：[[project-pending-plans]]（T-LogListAnchor 修复计划）。

**⑥ 该验收观已落成组件级自动化（20261101，S2-14 闭环）**
`LogListAnchorViewportTest`（4 例：暂停态尾追加未满载 / 满载头淘汰 / 跟随贴底 / `scrollToBottom(resume=true)` 恢复）把「人工滚轮确认」这条债转成了自动化：判据＝**顶部可见条目身份 + 行内偏移不变**（`viewPosition` 只作辅助——满载头淘汰时它必然变小，那个补偿本身就是缺陷面，不能当判据）；另加一条独立复核（该行必须真实覆盖视口顶边，防 `locationToIndex` 边界取到相邻行假绿）。双层反向验证：摘 `tailFollow.shouldFollow` 门控 → A/A2 双红（29 被换成 57 / 99 被换成 198）；再单独摘头淘汰补偿 `shiftViewportUpBy` → 仅 A2 红。坑：恢复跟随用例不能只调 `onScrollToEnd()`（会造出生产不可达的「FOLLOWING+视口中部」组合，滚动条 maximum 还是旧行数），要按生产入口 `scrollToBottom(resume=true)` 对齐视口。
