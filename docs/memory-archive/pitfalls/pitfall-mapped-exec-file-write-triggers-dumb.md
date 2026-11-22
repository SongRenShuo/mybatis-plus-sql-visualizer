---
name: pitfall-mapped-exec-file-write-triggers-dumb
description: 预览打开「准备控制台」会写一份带方言映射的 mpsql-exec.sql，而给「会被频繁重写的文件」设方言映射 ⇒ 平台每次内容变更都重算语言替换、推文件属性（需索引）⇒ 整个工程进 dumb mode，并在 EDT 上复活该文件 PSI 打 SlowOperations SEVERE（点名本插件）。含 A/B 判据（同一语句连开两次：写→dumb、不写→无 dumb）、三条候选修法与最终落地（惰性口径 / 备忘 / 目录级映射）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

2026-11-13 晚~14 凌晨，用户连续追问「打开预览还是慢」「全在后台线程会不会卡」「dumb 的问题无法解决掉吗」，
最后靠**他自己做 A/B**（同一条语句连开两次预览）把触发点钉死。

## 现象（两笔同源）

沙盒 `idea.log`（多模块 demo 工程）每次打开预览都出现：

```
[M36] exec file updated via document+save                 ← 我们写 <base>/.idea/sql-visualizer/exec/mpsql-exec.sql
(dumb mode task) PushedFilePropertiesUpdaterImpl$MyDumbModeTask
Reparsing …/exec/mpsql-exec.sql because of **language substitution**
SEVERE #c.i.u.SlowOperations - Slow operations are prohibited on EDT
  FileManagerImpl.shouldResurrect → areViewProvidersEquivalent → getPsi
  → SingleRootFileViewProvider.createFile → AbstractFileViewProvider.shouldCreatePSI
  → PsiManager.findDirectory → ProjectFileIndex.isUnderIgnored
  Plugin to blame: SQL Visualizer
```

读法：**给一个会被频繁重写的文件设了方言映射** ⇒ 内容一变平台就要重算「语言替换」+ 推文件属性（要索引）
⇒ 工程进 dumb；同一链还会在 EDT 上复活该文件的 PSI（慢操作断言）。后果不止 `console-visible` 那几秒，
每次预览后**索引相关能力（含本插件自己的反查/补全/候选预热）都会短暂失效**。

## 判据（别猜，做 A/B）

同一语句连开两次预览：
- 第 1 次：`exec file updated via document+save` → 2s 内 `enter dumb mode`；
- 第 2 次：整条准备链被跳过、**不写文件** → **完全无 dumb**。

⇒ 触发点是**写入**，不是「映射写入」（把映射写入压到 0 后 dumb 照旧）、不是新临时文件（`.idea` 本就被排除索引；
在我的单模块沙盒里每轮都新建预览文件却 0 次 dumb）、也不是编译（8 次 dumb 只有 2 次 ±3s 内有 `BUILDER_PROCESS`）。

**工程形态相关**：同一份代码在 `cust-big-demo`（单模块、未导入构建）0 次 dumb；在多模块 demo 根工程 4/4。
所以「本地不复现」不等于没这个 bug。

## 三条候选修法与落地

| 修法 | 内容 | 结果 |
|---|---|---|
| 备忘（已落） | `DialectPreMapping.mappedMemo`（`project|path → (langId, ts)`，TTL 10 分钟）：探测不可用时不重复 `setMapping`（每次 setMapping 都会 `SqlDialectPusher` push-all） | 日志出现 `dialect mapping skipped (already=… byMemo=true)`，映射写入 0 次；**但 dumb 没消失** ⇒ 只算加固 |
| 目录级映射（已落） | `applyExecDialectMapping` 的映射目标由 file 改 parent dir（与预览临时文件同策略，平台 `getMappingForHierarchy` 继承） | `testDbExecPsiGate` 30+ 例全绿（含 PSI 门/映射自愈），说明对 exec 链安全 |
| **惰性控制台（最终落地）** | 新增 `ui/PreviewConsoleEager` + Registry `sqlvis.preview.eagerConsole`（默认 **false**）：预览打开**不** `prepareDefaultConsole()`、`previewAsync` **不**预热 exec 文件；控制台交给首次执行/切换数据源时的 `selectAndExecute`（它本来就会「准备 + 同步 exec 文件 + 执行」） | 一次消掉 dumb 与 SlowOperations SEVERE |

## 取舍（必须提前告知用户）

惰性口径下**预览打开时右侧原生控制台区域是空的**——缺的是控制台自带的整套工具栏（数据源下拉
`Console.Jdbc.ChooseDataSource`、执行/停止、状态栏）与结果区；我们的 SQL 编辑器照常显示。
执行入口没丢：弹窗底部有独立的「执行 SQL」（`SqlPreviewDialog.createActions → panel.executeSql()`）。
想恢复旧观感：Registry 置 `true`。折中方案（待用户拍板）：照样准备控制台但**不写文件内容**，
SQL 只进控制台文档内存态、执行前再落盘。

## 纪律

- **不要退回固定文件名**：预览临时文件每轮换名是有意的（固定名会撞 `MemoryDiskConflictResolver` 模态冲突）。
- 任何「给工程内文件设方言映射」的新链路，先问一句「这个文件会被频繁重写吗」。
- 平台侧归因看 dumb 任务名：`PushedFilePropertiesUpdaterImpl` = 属性推送（映射/语言替换类），
  不是索引更新任务（新增文件）。

关联：[[pitfall-warm-fanout-and-index-entry-churn]]（同轮另一簇浪费）、
[[project-preview-perf-extreme-20261114]]（本轮读数与提交）、[[pitfall-annotation-index-simple-name-and-qualifiedname]]。
