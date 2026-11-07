# T-ExecPsiInvalid 异步建链 PSI 失效兜底 — 定向验证记录

- 日期：2026-11-01 20:00~20:35
- 触发：dev 权威全量门禁 `testShards` 唯一红点（`8900 / 1 / 0 / 12`，shard2）
- 分支/提交：dev `317b22af`

## 一、缺陷形态

全量门禁 shard2 报：

```
ExecTempFileAsyncTest.testEdtEntryReturnsBeforeSlowSegmentAndCallbackRunsOnEdt FAILED
  com.intellij.psi.PsiInvalidElementAccessException:
  Element: class com.intellij.psi.impl.source.PsiPlainTextFileImpl #TEXT
  because: different providers:
    com.intellij.psi.SingleRootFileViewProvider{vFile=file://…/.idea/sql-visualizer/exec/mpsql-exec.sql}(546d48f2); null(0)
  invalidated at: see attachment
    at com.intellij.psi.impl.source.PsiFileImpl.getText(PsiFileImpl.java:331)
    at …ExecTempFileAsyncTest…(ExecTempFileAsyncTest.kt:83)
```

单类定向跑（`--tests` 只点这一个类）恒绿；进分片（155 个类同 JVM）才红。

## 二、根因

`createTempPsiFile` 在**池线程**做 `refreshAndFindFileByIoFile` + `PsiManager.findFile`，把
`Pair<VirtualFile, PsiFile>` 交回 EDT 消费。这中间有一个**空窗**：

- 空窗内只要 `mpsql-exec.sql` 的内容发生一次变化（`projectExecFile` 的 doc 驱动三方同步落盘、
  防抖自动落盘、用户或其他写方覆盖），平台就丢弃旧 PSI；
- 而 `VirtualFile.isValid` **仍为 true**——它只反映文件还在不在，不反映 PSI 是否被丢；
- 于是 EDT 回调里读 `psi.text` 硬抛 `PsiInvalidElementAccessException`。

T-ExecPlanEdtSlowOp 之前是同步实现（整段压在 EDT 上），EDT 被占住时排队的内容变化落不下来，
所以这个窗口一直没暴露；改异步后它成了真窗口。**这是异步化的固有代价，不是测试的问题。**

## 三、修法（两处）

1. `ExecTempFileFactory.revalidateOnEdt(project, pair)`：EDT 投递 lambda 内先验 PSI——
   **有效即原样返回**（热路径零额外开销），失效才 `PsiManager.findFile` 重解析；
   重解析仍失败按建链失败返回 `null`（续接链走既有失败文案，不把死 PSI 递进 Runner 枚举）。
2. `DataSourceSelector.consumeCachedTempPsiFile`：缓存可用性由 `isCacheUsable(project, vf)`
   扩为 `&& psi.isValid`——缓存槽里躺着失效 PSI 时同样要判未命中并清槽。

## 四、读数

| 轮次 | 范围 | 读数 |
|---|---|---|
| 门禁（修复前） | testShards 5 片 | **8900 / 1 / 0 / 12**（唯一红＝本券） |
| 定向（修复后） | ExecTempFileAsync 2 + CachedTempPsiFile 4 + EdtGuard 4 + GiantFileBudget 2 + BannedApi 12 | **24 / 0 / 0 / 0** |
| **反向验证** | 摘掉两处判定（`RED PROBE`） | **6 / 2 / 0 / 0**——`testRevalidateOnEdtRebuildsInvalidatedPsi` 与 `testInvalidPsiRejectedAsCacheMiss` 双双变红 |
| 复绿 | 还原探针 | **24 / 0 / 0 / 0** |

反向验证的两次读数是本券精度的唯一证据：新增用例不是「自证」，是「摘掉修复必红」。

## 五、连带修正

- `CachedTempPsiFileTest` 的 `PsiFile` 全代理桩原先不应答 `isValid`（拆箱 NPE），补 `isValid -> true`；
  另加 `deadPsi` 桩承载新的「失效 PSI 判未命中」用例。
- `DataSourceSelector.kt` 触碰 2000 行预算红线：注释压到 1 行，保持 **2000 行**整。
