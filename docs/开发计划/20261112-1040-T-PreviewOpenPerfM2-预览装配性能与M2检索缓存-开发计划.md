# T-PreviewOpenPerfM2：预览装配性能（M2 检索缓存 + 冷热态归因）——开发计划

- 立项：2026-11-12
- 触发：用户报「点击弹出预览窗口耗时较长」；T-MbgPreviewParityAndOpenPerf 的 P5 埋点已给出第一份读数
- 前序：[20261112-0904-T-MbgPreviewParityAndOpenPerf](20261112-0904-T-MbgPreviewParityAndOpenPerf-MBG预览多条件粘连与预览打开耗时-开发计划.md)（同批的 P5 埋点即本任务的数据源）

## 一、已有读数（P5 埋点首采，2026-11-12 10:24）

```
[open-perf] total=266ms parse+ctx=38 profile=2 m2=207 model=19
```

同一次打开的渲染时间线：

```
10:24:55,930  [M36] prewarm done in 191ms
10:24:56,871  [M36] showSql (首次渲染)      ← 间隔 0.94s
```

对照上午同一台机器另一次（冷态）：

```
[prewarm done] → [首次 showSql] = 5.67s（其间插件零日志，平台在存 JPS 模型）
```

**两条结论**：

1. 5.67s 大概率是**冷态**（首次索引 / 平台 JPS 全局模型保存），热态只有 0.94s。要下结论必须先分清
   冷热，否则会把一次性成本当常态去优化。
2. 热态里 **M2 检索占 207ms / 266ms ≈ 78%**，`profile`（FQN 类解析）只 2ms。

## 二、根因与边界

`MbgExampleCallResolver.resolve()` 在主预览路径上同步执行：

```kotlin
ReferencesSearch.search(mapperMethod, GlobalSearchScope.projectScope(project)).findAll()
```

即**全项目索引级搜索**，且：

- 无缓存——每次 `XmlPreviewService.preview()` 都重跑（面板刷新、切换语句、重开窗口各一次）；
- 在装配路径上同步，占用打开耗时的绝大部分；
- 用户工程很大时，命中集与索引规模成比例放大——207ms 是最小规模 demo 的读数。

**已被数据否掉的猜想**：`typeAlias` 未识别导致慢——对 MBG 不成立（`parameterType` 是 FQN，
`resolveExampleProfile` 走 `findClass` 一次查询，读数里 profile=2ms 印证）；对 iBatis 侧另议（已由
前序 P6 覆盖）。

## 三、方案

| 编号 | 内容 | 优先级 |
| --- | --- | --- |
| M1 | **M2 结果缓存**：键＝`statementId + mapper 文件 modificationStamp(or mtime) + Example 类名`，值＝`collectionElements`；命中直接跳过 `ReferencesSearch` | 高 |
| M2 | 缓存失效口径：mapper 文件改动 / 调用点所在 java 文件改动（两者任一 mtime 变则失效；不引入文件监听，按需比 stamp） | 高 |
| M3 | 冷热态分离取证：在 `logPreviewPerf` 里带上「首次/重开」标记与 `DumbService.isDumb`，把 0.94s 与 5.67s 的差别钉死 | 中 |
| M4 | 若 M1 后仍有余量，再看 S2（`DatabaseSchemaResolver` 按表 × 全部数据源轮询，日志无耗时）与 S3（JPS 模型保存触发者） | 低（视读数） |

## 四、验证

- 纯逻辑：缓存键生成（同 statement 不同 mtime 必 miss）、命中后 `ReferencesSearch` 不被调用（用可注入
  的检索缝计数）。
- 真机：同一语句**连续打开两次**，第二次的 `[open-perf] m2=` 应接近 0；改一行调用点代码后再打开应 miss。
- 反向：摘掉缓存命中分支 → 第二次仍应打满检索耗时（用例断言计数）。

## 五、边界（不做）

- 不引入后台线程/异步渲染改变交互（预览是同步弹窗，异步会让「点开即见 SQL」退化）。
- 不加文件监听器（常驻成本大于本收益；按 mtime 比 stamp 即可）。
- 不动 `DatabaseSchemaResolver` / 平台模型保存（M4 仅在有读数支撑时才碰）。

## 六、待验证前提

- **V1** 用户大工程里 `ReferencesSearch` 的实际耗时（需一次真机 `[open-perf]`，最好给「重开」读数）。
- **V2** 调用点所在 java 文件与 mapper 文件的 mtime 是否足够作失效判据（跨模块引用、生成代码场景）。
- **V3** 冷态 5.67s 是否真与首次索引绑定（M3 的 `isDumb` 标记能直接回答）。

## 七、实施记录

（待填）
