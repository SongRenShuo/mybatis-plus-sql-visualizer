---
name: project-preview-chain-noise
description: T-PreviewChainNoise（20261023 已实施 `9809a5b7` 未 push）——预览执行链三组平台 SEVERE 收口；W1 EDT 读盘三态判定（含 MemoryDiskConflictResolver 冲突条件源码事实）、W2 进度上下文兜底下沉、W3 二次摘监听回归修复；读数 37/0/0/0+反向 3/1/1 红
metadata:
  node_type: memory
  type: project
  originSessionId: session_b380d49669e298cff6e310c72afca927
---

# T-PreviewChainNoise：预览执行链三组平台 SEVERE 收口（20261023 已实施，`9809a5b7` 未 push）

用户真机日志一次「执行计划→选源→关面板」会话 5 条 Throwable → 三组根因，计划文档
`docs/开发计划/20261023-2204-T-PreviewChainNoise-预览执行链三组平台SEVERE收口-开发计划.md`
（实施记录 §十）。提交链：代码+测试 `9809a5b7`（8 文件）→ 文档 `43855baa` → 前提 2 闭环 `f9e1650f`。
W2/W3 的平台机制与修法详版分别在 [[pitfall-blocking-call-needs-progress-context]] 与
[[pitfall-disposer-session-not-in-tree]]（后续回归节），此处只记 W1 独有事实与验证。

## W1：EDT 上 VFS `vf.inputStream` 读盘（同族缺陷第二次犯）

`syncExecVfContentIfNeeded`（`DataSourceSelector.kt:1551`）无条件 `vf.inputStream.readBytes()` 探测磁盘
分叉，经 `createTempPsiFile` 的 EDT 冷路径每次命中「Slow operations are prohibited on EDT」×2。
**关键教训＝同族定例回填检查**：同文件 `execFileContentMatches:1837` 早在 T-DevAcceptFix2 ④（20261006）
就为同一条调用链删过同款读盘（改 `lastWrittenExecContent` 内存快照），晚两天…不，早四天的定例没回填到
20260916 新写的函数——同文件里出现两套线程口径时，新函数要对着既有定例自查。

**修法（三态判定）**：抽 `probeExecVfDivergence(vf, sql, allowDiskRead)`——①有 document 只看内存态
（`doc.text != sql || fdm.isDocumentUnsaved(doc)`，后者是 F4 收敛形态 doc=新 SQL/磁盘=旧 SQL 的判据，
补上后 `ExecDocSync2Test` F4 用例零改动通过）；②EDT+无文档短路为不分叉；③BGT 保留读盘。
**前提 2 源码级闭环**：`MemoryDiskConflictResolver.java:41-46` `beforeContentChange` 首行
`document == null || !isDocumentUnsaved(document) → return` ⇒ 无缓存 document 不可能收集 File Cache
Conflict，「EDT+无文档不读盘」安全（这是 W1 收窄的正当性依据，可复用的平台事实）。

## 测试判别器与读数

- `ExecVfDivergenceProbeTest` 7 例，**③ 用例是「文档分支绝不读盘」的不变式判据**：盘上另写第三种内容
  （不 refresh）+ 文档在场且相等已保存 → 断言不分叉；旧实现读了盘必红。
- `ExecDocSyncTest` 原盘上分叉用例改写为「EDT 不读盘不修复」新契约（新旧实现判别器）；BGT 端到端修复
  用「工作线程 + `UIUtil.dispatchAllInvocationEvents` 泵 EDT」覆盖（headless 无 EDT 泵，纯 sleep 会烧
  2s 有界预算，ExecDocSyncTest 同款先例）。
- 读数：定向 5 类 **37/0/0/0**（XML 判例数逐一核对）；反向 W1 摘 EDT 短路恰 3 红（全为 EDT 用例）、
  W2 摘兜底恰 1 红、W3 回填手动摘除恰 1 红；还原后全绿、REVERSE-PROBE 残留 0。

## 诚实边界（未验面）

全量 testShards 按用户口径未跑；真机日志零 SEVERE 复核（改前 2/1/1/1 → 0/0/0/0）留用户；
headless 下 SlowOperations 断言能否构造未做对照实验（反向用的是确定性 oracle，不影响结论）；
slow-op 触发依赖 section 栈非空（`SlowOperations.isSlowOperationAllowed` 的 ourStack 判定），
light 测试里 ourStack 空时断言放行 ⇒ 「零 ERROR 日志」类断言在 headless 可能空转。

## 附带事故

本任务文档回填时撞上第 11 次并行提交事故（amend 吞进对方刚插入的提交），修复手法见
[[pitfall-parallel-session-batch-staging]]。
