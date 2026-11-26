# T-PreviewFallbackEdtSlowOps —— Mapper XML 兜底在 EDT 查索引致 SlowOperations SEVERE

- 任务编号：T-PreviewFallbackEdtSlowOps
- 立项时间：2026-11-26 15:14
- 触发：用户真机 runIde 日志（`2026-11-26 15:03:47`）出现两条 `SEVERE - #c.i.u.SlowOperations`，平台点名 `Plugin to blame: MyBatis SQL Visualizer version: 2026.11.26-beta.2`、`Last Action: cn.song.mybatisplus.visualizer.ConvertToSql`
- 关联：T-MapperMethodSqlPreview（`3d6bb9b57` 引入该兜底档）、T-MapperPreviewDedupAndAnnotationShortcut（`0263bf788`，同批）、T-PreviewEdtGuard（EDT 慢操作守卫族）

---

## 一、现象

真机日志原文（两栈，同一次点击）：

```
SEVERE - #c.i.u.SlowOperations - Slow operations are prohibited on EDT. See SlowOperations.assertSlowOperationsAreAllowed javadoc.
	at com.intellij.util.SlowOperations.assertSlowOperationsAreAllowed(SlowOperations.java:114)
	at com.intellij.util.indexing.FileBasedIndexImpl.ensureUpToDate(FileBasedIndexImpl.java:812)
	at com.intellij.util.indexing.FileBasedIndexEx.getContainingFiles(FileBasedIndexEx.java:252)
	at cn.song.mybatisplus.visualizer.log.MapperNamespaceIndex$Companion.getMapperXmlFiles(MapperNamespaceIndex.kt:107)
	at cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets.findMapperFileByNamespace(MybatisXmlTargets.kt:126)
	at cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets.resolveStatementTags(MybatisXmlTargets.kt:76)
	at cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets.statementTagsFor(MapperNavigationTargets.kt:50)
	at cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction.tryMapperXmlMethodFallback(ConvertToSqlAction.kt:217)
	at cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction.actionPerformed$lambda$3(ConvertToSqlAction.kt:184)
	at cn.song.mybatisplus.visualizer.ui.SqlPreviewService$Companion.previewAsync$lambda$0$2(SqlPreviewService.kt:2229)
	... IdeEventQueue.dispatchEvent → TransactionGuardImpl.runWithWritingAllowed → EDT ...
```

第二栈是同一调用里的另一处断言点（`WorkspaceFileIndexDataImpl.ensureIsUpToDate` ← `ProjectScopeImpl.contains`，`getContainingFiles` 内部按工程 scope 过滤），不是第二次触发。

功能面：动作照常走完（243 只记 SEVERE 不抛），但索引需要追平时 UI 在 EDT 上被卡住；2025.3+ 对该形态更严格。

## 二、根因

`SqlPreviewService.previewAsync` 的设计是「compute 在池线程 `ReadAction` 内跑，`onResult` 走 `invokeLater` 回 EDT」（`SqlPreviewService.kt:2152-2160`、`2229`）。而 `ConvertToSqlAction.actionPerformed` 把 `tryMapperXmlMethodFallback` 放在 **onResult 回调**里：

```kotlin
} else if (!tryMapperXmlMethodFallback(project, element, sourceLocation)   // ← EDT
```

该函数体第一步就是 `MapperNavigationTargets.statementTagsFor(method)` → 自定义 fileBasedIndex `MapperNamespaceIndex.getMapperXmlFiles` → `FileBasedIndex.getContainingFiles`，它是慢操作门控 API。于是一次「Wrapper 预览无命中的快捷键/菜单点击」就在 EDT 上做了索引查询。

触发面：`previewAsync` 返回 null（Wrapper 链未命中）**且** 光标在 Mapper 接口方法（或其参数/返回类型）上。

## 三、目标与非目标

**目标**

1. Mapper XML 兜底的锚点解析（`nearestMapperMethod` + `statementTagsFor`）挪到 `previewAsync` 的 compute 块（BGT + ReadAction），EDT 回调只消费预解析好的 `List<XmlTag>`。
2. 判定与展示行为逐字不变：命中一条直接预览、多条弹选择器、无命中继续回落下一档；`Alt+Shift+Q` 与右键菜单同入口照旧。
3. compute 抛错时兜底链仍可用（既有语义：compute 失败 → 回调仍走兜底），不能在异常分支上把 XML 档静默掉。

**非目标**

- 不重构整条回调兜底链的线程模型（其它 fallback 档不查索引，本轮不动）。
- 不动 `MapperNamespaceIndex` / `MybatisXmlTargets` 的索引与缓存（它们本来就会在 BGT 被大量调用）。
- 不加"允许慢操作"逃生舱（243/262 双重证伪，`allowSlowOperations` 禁再试）。

## 四、实施方案

### S1 预解析 + 消费预解析结果

`ConvertToSqlAction.kt` 分支 B：

```kotlin
val mapperXmlTags = AtomicReference<List<XmlTag>>(emptyList())
SqlPreviewService.previewAsync(project, {
    ...
    val result = try {
        service.preview(element, context)
    } catch (t: Throwable) {
        mapperXmlTags.set(mapperXmlTagsFor(element))   // 异常分支同样备料
        throw t
    }
    if (result == null) mapperXmlTags.set(mapperXmlTagsFor(element))
    result
}) { result ->
    ...
    } else if (!tryMapperXmlMethodFallback(project, mapperXmlTags.get(), sourceLocation) && ...
```

新增 `internal fun mapperXmlTagsFor(element): List<XmlTag>`：`nearestMapperMethod` → `statementTagsFor`，非 fatal 异常吞成空表（`IndexNotReadyException` 不得冒泡到 `previewAsync`——它会把"预览无命中"变成 runWhenSmart 重试，改变语义）。

`tryMapperXmlMethodFallback` 形参从 `element: PsiElement` 改为 `tags: List<XmlTag>`，函数体删掉解析两步。

### S2 测试

`ConvertToSqlActionTest` 补两条：

1. **数据面**：接口方法名 → `mapperXmlTagsFor` 命中同名 XML 语句（与既有 `statementTagsFor` 用例同夹具）。
2. **结构守卫**（与既有 `testMapperXml回落排在iBatis之前` 同族，源文本断言）：
   - `statementTagsFor(` 在 `ConvertToSqlAction.kt` 全文只出现 1 次，且出现在 `mapperXmlTagsFor(` 之后（即只在辅助函数体内，不在回调链里）；
   - 回调调用点形参是 `mapperXmlTags.get()`。

### S3 文档与 CHANGELOG

- 本计划"实施记录"回填读数。
- `CHANGELOG.md [Unreleased] → Fixed` 一条（性能类，用户可感面：索引追平时点快捷键不再卡 UI）。

## 五、待验证前提

| # | 前提 | 验证法 | 不成立时 |
|---|---|---|---|
| P-1 | BGT 里解析出的 `XmlTag` 在 EDT 回调里仍可读（PSI 读线程安全、无写中断） | 实现后定向测试 + 真机复验（日志无 SEVERE、预览正常） | 改为在回调里按 `SmartPsiElementPointer` 重取 |
| P-2 | `previewAsync` 的 retry 路径（`runWhenSmart`）会重跑 compute，预解析随之刷新 | 阅读 `SqlPreviewService.kt:2188-2194`（`ReadAction.compute { compute() }`） | 在 retry 前显式再解析一次 |
| P-3 | compute 在 `ReadAction.compute` 内执行（索引查询合法） | `SqlPreviewService.kt:2160` | 自包 `ReadAction.compute` |
| P-4 | 其它 fallback 档（iBatis / JDBC / HQL / JPA）不查索引 | grep 各 `tryXxxFallback` 实现 | 同法一并前移（本轮实测均纯 PSI，见 §八） |

## 六、验收

- 定向：`ConvertToSqlActionTest` 全绿（新增 2 条），`PreviewRenderSlowOpsGuardTest`（同族守卫不回归）。
- 反向一轮：把 `mapperXmlTagsFor(element)` 的调用改回回调内 `MapperNavigationTargets.statementTagsFor(...)` → 结构守卫恰红；还原复绿。
- 真机（用户）：同一操作复跑 runIde，日志不再出现 `SlowOperations` + `ConvertToSql` 组合。
- 说明：EDT 侧"没有查索引"这一点无法用单测直接证明（要真机栈），单测钉的是"解析只发生在辅助函数里、回调消费预解析结果"这一结构前提。

## 七、风险

| 风险 | 处置 |
|---|---|
| 预解析在 Wrapper 命中时也跑 → 白付一次索引查询 | 只在 `result == null`（或 compute 抛错）时解析，Wrapper 命中路径零额外成本 |
| 预解析抛非 fatal 异常被吞 → 与"无命中"不可区分 | 与现状一致（`findMapperFileByNamespace` 本就吞 `IndexNotReadyException` 返回 null），不新增静默面 |
| 工作树内有并行车道（`T-MapperPreviewOrder` 正在改 gutter 五文件） | 本次提交按路径 `git add` 只收自己的文件，不扫对方改动 |

---

## 八、实施记录（2026-11-26，master，提交 `c5685aee8`）

### 8.1 落地

- `ConvertToSqlAction`：新增 `internal fun mapperXmlTagsFor(element)`（`nearestMapperMethod` → `statementTagsFor`，非 fatal 异常吞成空表），在 `previewAsync` 的 compute 块里备料——`result == null` 时解析，**以及 compute 抛错时（catch 里解析后再 rethrow）**，保证异常路径的兜底链不静默；回调只消费 `mapperXmlTags.get()`。
- `tryMapperXmlMethodFallback` 形参 `element: PsiElement` → `tags: List<XmlTag>`，函数体删掉解析两步，只做「单条直预览 / 多变体弹选择器」分发。
- 前提 P-1~P-4 全部成立：retry 路径（`SqlPreviewService.kt:2188-2194`）确实重跑 compute（预解析随之刷新）；compute 在 `ReadAction.compute` 内（`SqlPreviewService.kt:2160`）；其余 fallback 档（iBatis / JDBC / HQL / JPA / 方法体字符串）实现均为纯 PSI，不碰索引——本轮不动。

### 8.2 定向读数

`ConvertToSqlActionTest` **21/0**（新增 2 条：`testMapperXml兜底锚点解析_命中同名XML语句`、`testMapperXml兜底锚点解析不在EDT回调链`）。

### 8.3 反向验证（一轮，逐面恰红）

- 把回调调用点改回「回调内解析」形态（`tryMapperXmlMethodFallback(project, mapperXmlTagsFor(element), …)`）：`ConvertToSqlActionTest` **21 completed, 1 failed**——恰为 `testMapperXml兜底锚点解析不在EDT回调链`（`ConvertToSqlActionTest.kt:467`，即「回调链必须消费预解析结果」那条断言），其余 20 例绿。
- 还原后复跑 **21/0**。

### 8.4 环境读数（撞车留档）

- 首次启动定向测试时撞上并行车道 `T-MapperPreviewOrder` 的构建（同 build 目录、`gradle-wrapper.jar -g F:/gradle-home-pv test`，PID 40456）：`:test` 报 `NoSuchFileException … in-progress-results-generic.bin`，无测试结果。等其结束后复跑即绿（本任务已登记 `parallel_tasks.py… register name=t-preview-fallback-slowops`）。
- 本轮两笔改动只在 `ConvertToSqlAction.kt` 及其测试；并行车道改的五个 gutter 文件未纳入提交。

### 8.5 待真机复验

- 同一操作（Mapper 接口方法上按 `Alt+Shift+Q`，Wrapper 链未命中）复跑 runIde，`idea.log` 不再出现 `SlowOperations` + `ConvertToSql` 组合；预览行为（单条直出 / 多变体选择器）与修前一致。