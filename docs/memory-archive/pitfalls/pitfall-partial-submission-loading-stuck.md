---
name: pitfall-partial-submission-loading-stuck
description: 面板 showLoading 后只接了 onError、没接 onSubmission ⇒ 用户取消数据源弹窗（CANCELLED 无错误文案）时 loading 永久转圈；"执行中..." 卡死的判据与修法
metadata:
  node_type: memory
  type: project
  originSessionId: session_7c4f9878c3e52efd1a834ad34e2354ac
---

# 「执行中...」永久转圈：showLoading 后只接 onError 不接 onSubmission

## 形态（2026-10-19 实测，`SqlPreviewPanel.estimateDmlImpact`）

`resultPanel.showLoading()` 之后调 `SqlExecutor.execute(...)`，只传了 `onError` / `onErrorRich`。用户点「预估影响行数」→ 弹出数据源选择（`runners=5`）→ 用户关闭弹窗 → 准备门按 **CANCELLED** 结算，而取消**没有错误文案**，`onError` 不触发 ⇒ 结果区永远停在「执行中...」。

沙盒日志判据（`[M36]` 前缀）：

```
[M36] runners=5
[M36] show popup with 5 runners
[M36] popup selected: null        ← 之后 60s 内无 session created、无 error、无结果
```

`popup selected: null` 是取消键；再往后什么都没有就是「终态未结算」，不是「还在跑」。

## 修法（`eca7aecc`）

接 `onSubmission`，按终态分别归位：

- `CANCELLED` → `resultPanel.showEmpty()`（解除 loading，不制造假错误）；
- `BLOCKED` → 交 `onError` 显示具体原因，**不能**在这里清空，否则错误一闪而过；
- `SUBMITTED` → 不动结果区。

抽成纯函数 `clearImpactLoadingOnCancel(outcome): Boolean`（只对 CANCELLED 返回 true），配源码切片守卫断言 `estimateDmlImpact` 里出现 `onSubmission =` 与 `clearImpactLoadingOnCancel(outcome)`。

## 通用纪律

凡是 **`showLoading()` 之后才发起异步链**的地方，都要问「用户取消这一支有没有终态回调」。只有错误通道的入口必有这个洞——取消/被替代/超时都不产生错误文案。已有先例：`QueuedExecuteIntent.complete()` 的注释明写「面板已 showLoading，消费点必须用意图自身的 onError 解除加载态」。

## 同轮相邻结论（不需改码）

- `[M36] runners=N` + `show popup` 是**正常**路径：显式执行链按设计不静默复用 last-used 数据源（`executeAfterPrepare=true` 走弹窗），面板头部「数据源（上次使用）」只是展示。用户问「为什么每次都弹窗」时按此回答；若要改成复用属新 UX 增强。
- 日志里 `dialect pre-mapped to PostgreSQL` 后跟「已阻止执行：当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL」是方言闸 fail-closed 的正常结果（双引号标识符被判 PG + 工程无 PG 数据源），不是缺陷。

读数：`ExecutionDialectGateTest` 18/0/0/0；反向摘掉 `onSubmission` 恰 1 红。

相关：[[pitfall-reflection-invoke-visibility]]（同一条 60s 超时的上游根因）、[[pitfall-verification-answer-must-state-evidence-limit]]、[[project-test-conventions]]。
