---
name: pitfall-m2-warm-retry-self-exciting-loop
description: 「首开转后台补齐 + 落地后重刷」如果补不到结果也回调，就会自激（3 秒 10 轮重算）；必须按 key + 缓存代只补一次
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

T-StartupReadLockFreeze 第 5 轮（2026-11-13 14:31 真机）踩到的自激形态，是第 3 轮（首开转后台）**自己引入**的：

```
首开 → 缓存未命中 → warmForPreview 启动(key=K)
     → warm 补不到结果 → fireReady(K) → 面板重算 showResult
     → 重算仍未命中（本来就没结果）→ 又 warmForPreview(K) → 又 fireReady → …
实测 3 秒 10 轮，每轮都跑完整 showResult（控制台准备 + exec 文件写 + 面板重排）
```

用户感知就是「还是没秒开」，日志里表现为 `warm start → warm unresolved → m2 async ready applied` 反复出现。

**为什么不能只靠「有结果才回调」**：不回调则面板会一直等一个永不到来的信号（监听泄漏、骨架永远停住），所以失败也要回调——于是必须另设闸。

**修法**（`MbgExampleCallResolver`）：
- `warmAttempted`：同一 key **同一缓存代**只允许启动一次补齐，纯判定 `shouldStartWarm(cacheFresh, alreadyAttempted)`；
- 缓存失效（`cachedIfFresh` 发现依赖文件改动）时移除资格，保证改文件后仍能补；调度失败（`onFailure`）也退回资格；
- 判定放在测试缝之前，否则「第二次打开该不该再补」在单测里不可观测。

**同类风险面**：任何「结果就绪 → 自动重算」的闭环（`retryWhenIndexReady`、`runWhenSmart` 重算、deferred 刷新）都要问一句「重算本身会不会再次触发等待/再转后台」；`runWhenSmart` 在 smart 已就绪时是**立即**触发的，拿它等异步结果必空转（本单为此新增了 `PreviewResult.m2AwaitKey` 专用通道）。

**非 GUI 判据**：`MbgExampleAsyncOpenTest.test补不到结果时不得自激重试`——首开 warm 1 次、二开不得再转后台；把资格判定改 `if (false)` 必红。

关联：[[project-startup-read-lock-freeze-20261113]]、[[feedback-verify-perf-non-gui-first]]。
