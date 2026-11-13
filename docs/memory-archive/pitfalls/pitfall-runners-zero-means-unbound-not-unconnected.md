---
name: pitfall-runners-zero-means-unbound-not-unconnected
description: 预览「执行 SQL」报 runners=0 与连接/内省无关——真门槛是「文件→数据源」绑定；UI-046/048/049 三轮修复与 S2-12 回落已全部闭环并真机实证（回落→直取 session→attach 控制台→真库出结果集）；执行链优先级＝attach 控制台 > 唯一目标回落 > 归因文案
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# runners=0 的真门槛是「文件→数据源」绑定（2026-11-01 真机实证）

单数据源工程，把 `mp_demo@localhost` **连上**并在 Database 树里展开到 `Tables (11)`（表级内省确实完成）后点「执行 SQL」，日志仍是：

```
[M36] runners=0
WARN [M36] runners empty (execute=true, registered=1)
[M36] runners empty → remapped exec file to target dialect, retry runners=0
```

面板报「已检测到 1 个数据源，但它们都还没有连接成功或尚未完成内省……」——**这句在已连接已内省的状态下是假的**。

## 机制

平台 `DatabaseRunners.getAttachDataSourceRunners` 按**「文件 → 数据源」绑定/关联**筛 Runner，未绑定必空。这不是推断：本仓 `src/main/kotlin/.../execute/DataSourceSelector.kt:419-422`（T-ExplainPwdPrompt W4 的 KDoc）早写着「数据源无方言映射时返回空；**243 沙盒实测：Database 视图已连接仍为 0**」。预览面板的绑定行本身也在提示：「未绑定数据源（在控制台执行一次查询即可绑定）」。

后果：**新工程第一次从预览点执行必然失败**，用户得先去 Database 控制台跑一次查询建立绑定。EXPLAIN 链当年为此做了 W4/W9 回落（绕开绑定链取源、走 `McpDbExecutor`），**执行链没有同款回落**。

## 两轮文案都栽在同一个毛病上：把推测写成因果

- UI-046：把「枚举不出 Runner」写成「没有与该 SQL 方言匹配的数据源」。
- UI-048：把「未绑定」写成「没连上/没内省」。
- 正解方向（UI-048 已落）：归因补一维**绑定态**，`ExecFileBinding.probeExecBind` 只在生产入口调用、判定内核仍是可注入纯函数；**未绑定优先于未连接**（硬事实优先于推测），文案只给可行动作，不断言没验证的状态。

## 复查这类问题的顺序（照做可省一轮）

1. 看 `[M36]` 四行有没有 `remapped exec file …`：有＝兜底已进入（UI-046 的修复在生效），没有再谈别的。
2. 看 Database 树是否到 `Tables (n)` 级：只到 schema 级不算内省完成。
3. 看预览绑定行是否「未绑定」：这一行比任何推测都准。

## 状态与后续（2026-11-01 全天演进）

- **UI-046 已修并合 dev**（`0aad84c4` → merge `35cd5144` → 注释压缩 `41336e70`）：兜底扩「按渲染方言唯一匹配 / 单源兜底」（纯函数 `pickTargetDataSourceForDialect`，多候选不猜）+ 文案三分类 + 新键 `execute.datasource.notConnected`。master 侧定向 61/0、dev 合并态 83/0/0/0、反向 40/1 红后复绿。真机复验：兜底确实被进入（`remapped exec file` 那行出现了）。
- **UI-048 已修并合 dev + 真机复验，但通过是「巧合正确」**（`e2f2135d` → merge `43dc507a`）：归因补绑定态维（`DIALECT_MATCHED_BUT_UNBOUND`），真机新文案确实上屏（「当前文件尚未与其中任何一个绑定……请先在 Database 工具窗口中对目标数据源执行一次查询以完成绑定」）。**但**主控扫 243 平台全部 523 个 jar：`com.intellij.database.dataSource.DataSourcePerFileMappingsImpl` **命中 0——它是 262 的 API，243 上探针一贯死（不是偶发失败）**，所以那句「未绑定」是「探针失败被当成未绑定」凑巧说对，不是探针证明出来的。
- **UI-049（P2，在修）**：`probeExecBind` 反射失败返回的 `ExecBindProbe(null,null)` 与「真没有 pin」不可区分，生产入口按 `pinnedDsId != null` 判定拿到 `false` 而非设计注释的 `null` ⇒ 243 上 `runners=0 ⇒ 一律未绑定`，**已绑定的文件也会被这么报**。修法＝探针三态 `BOUND/UNBOUND/UNKNOWN`（反射失败/类缺失＝UNKNOWN → `boundToDataSource` 传 null 退回旧口径），且因 243 恒落 UNKNOWN/notConnected 档，`notConnected` 文案必须同时列「未连接 / 未内省 / 方言标注不符 / 本文件未绑定」四类原因 + 各自动作，否则 243 用户拿不到「去绑定」指引。
- **S2-12 用户已拍板（2026-11-01「按照你推荐的做」）并已实施+真机全闭环**：实现＝`ExecBindFallback.kt`（纯函数 `pickFallbackTargetDataSource`：学习绑定 UUID > last-used UUID > last-used 名字唯一 > 单源，歧义/零候选 null）+ `directRunner` 反射 `DatabaseSessionManager.getSession(project, ds, sql)` 直取 session 交给既有 `sessionConsumer`（= `runInDataSource` 的逐行等价；`createDataSourceConsolesRunner` 证实是 EmptyRunnable 占位不可执行），接线在空 Runner 分支重映射之后、归因文案之前；**零 attachConsole / 零 per-file 写入**（守卫钉住）。定向 91/0/0/0（主控复跑），反向摘回落 2 红。**真机四要素实证**：单源未绑定未内省态点执行 → `[S2-12] fallback to uniquely resolved data source` → `direct session created: MessageBusSession` → 既有 `[M36]` 链 attachConsole/库上下文对齐/原生控制台 → 结果页签 `t_order` 出网格（`0 rows of 0+` 空表）+ Services 会话树 `mpsql-exec.sql 1 s 208 ms` 真实执行。

## 执行链优先级（2026-11-01 真机印证）

**attach 控制台 > 唯一目标回落 > 归因文案**，逐级兜底：应用在跑时（其 Run 控制台被平台视为已挂源）连 12 源歧义场景都直接 `console isValid=true → doRunQueryInConsole` 执行成功，归因文案只在「零可用执行路径」时出现——与用户「方言不挡执行」定案一致，属正确优先级而非缺陷。

## 第三种成因（2026-11-02 补）：平台调用在 262 上被 read-access 断言硬抛

runners=0 不总是「绑定/连接」问题。用户日常 IDEA 2026.2 实测：`getAttachDataSourceRunners reflection FAILED: InvocationTargetException`，`Caused by: Read access is allowed from inside read-action only`（EDT 无隐式读锁，平台 `DataSourcePerFileMappingsImpl.getMapping` 硬抛），`runCatching` 吞成空列表 → 同一句「方言不匹配」文案。**排查时必须先 grep 日志里有没有 reflection FAILED**，否则会把平台线程断言误判成绑定问题、去改 Dialects 设置白忙。详见 [[pitfall-262-edt-readlock-database-runners]]。

## 第四种成因（2026-11-04 补）：方言变了，但没有数据源声明该方言

用户 master 包打开 XML 预览即报「执行失败：没有与该 SQL 方言（MySQL）匹配的已配置数据源」+ 右上「未绑定数据源」，日志：

```
[T-ConsoleBind] probe per-file mapping FAILED (treat as unmapped)
[M36] runners=0
WARN [M36] runners empty (execute=false, registered=12)
```

**12 个数据源、runners 却是 0** —— 因为面板方言已变成 **MySQL**（顶部显示「方言: MySQL（模块已学数据源）」），而 12 个源在 IDEA 的 **Dialects 设置**里没有一个声明 MySQL。同一个项目 12:40 那次方言是 PostgreSQL、有源声明 PG，`runners=7` 一切正常。

**方言为什么会变**：`模块已学数据源` = 用户上次执行时选了 `mp_demo@localhost`（MySQL 库），模块学习记住 MySQL。

**处置**：Database 工具窗 → 右键目标源 → Properties → **Dialects** 勾上对应方言 → OK，`runners` 立刻有值。**不要**去改绑定/连接（那是另外三种成因）。

**注意「挂控制台也救不了」**：用户曾问「为什么不挂原生控制台好让我选源」——原生数据源下拉同样按「文件方言」筛，`runners=0` 时下拉本来就是空的。所以这一支的唯一出路是改 Dialects 或改文件方言。

**排查顺序补充**：先看面板顶部的「方言」是哪一种、再看该方言有没有源声明（Database 工具窗源属性里的 Dialects 列），最后才谈绑定与连接。

**这一支的 UX 被用户明确判为不合格（2026-11-04）**：原话「目前非常不友好，用户看到错误会不知道怎么处理」。四个具体毛病：①12 个源名一长串，不知道改哪个；②「打开 Database 工具窗口 → 属性 → Dialects」是纯文字指引、没有可点入口；③用红色「执行失败」措辞，但这不是失败是没配置，用户以为插件坏了；④面板右上「未绑定数据源」与底部红字说的不是同一件事，读起来互相矛盾。

改进方向（已交 `wt-runnersux` / `fix-runners-hint-ux`，按价值排序）：①红字旁给动作，点了直接打开目标数据源属性页并定位 Dialects；②用 `DataSourceUrlProbe` 的 URL 子协议推断「看起来就是目标方言」的源并优先点名，唯一命中就说「给 X 勾上 MySQL 即可」，其余折叠成「另有 N 个源」；③措辞降级（「执行失败」→「下一步」）；④消除与「未绑定」的读法冲突。**约束**：不改 runners 筛选逻辑（平台行为）、不推翻 UI-046/048 的归因准确性、不为此弹模态框。

## 平台 Parameters 弹窗与探针捷径

平台 Parameters 对话框（`?` 占位要值）的 **Execute 按钮对合成输入不响应**（坐标×3/Enter×2/Tab+Space 全试过）。绕开捷径＝预览面板点**「全不选」**取消全部动态条件 → SQL 无占位符 → 控制台直接执行。观察项：控制台 SQL 保留 `?` 由平台要值、预览参数区不代入（既有语义，独立 UX 议题）。

## 教训一句

「探针失败」和「探针返回否」是两个事实——`runCatching{...}.getOrNull()` 把异常折叠成与「真的没有」相同的返回值，下游就必然把失败当结论。三态化（成功值 / 明确否 / 未知）是这类探针的唯一安全形态（UI-049 已落：`ExecBindProbe.probeOk` + `bindingState` 三态投影，`boundToDataSourceFlag` UNKNOWN→null；243 上因类缺失恒 UNKNOWN→notConnected 档，故 `notConnected` 文案列「未连接/未内省/方言不符/本文件未绑定」四类原因）。

相关：[[reference-dialect-mismatch-execution-gate]]、[[project-ui046-048-first-exec]]。
