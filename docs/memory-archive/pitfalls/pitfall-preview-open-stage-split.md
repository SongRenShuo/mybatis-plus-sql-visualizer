---
name: pitfall-preview-open-stage-split
description: 「预览首开慢」必须按 request→compute→装配→dialog 四段切，别只看装配埋点；装配 53ms 而弹窗 11.9s 时大头往往是 IDE 冷启动（JavaBuilder/索引）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

T-StartupReadLockFreeze 返工期（2026-11-13 14:48 沙盒）实测的归因教训：

- 插件自己的埋点 `[open-perf] total=… parse+ctx=… profile=… m2=… model=…` 只覆盖 `XmlPreviewService.preview()` 内部，**不含**点击闭包里的 `resolveSourceContext` / `XmlSqlCompletionContextResolver.resolveXml`，也不含弹窗构造。
- 真机读数：`[open-perf] total=53ms`，但 `[T-PreviewFast] request→dialog: 11919ms`；把日志逐行对时才发现我们 compute 的**第一行日志**比 request 晚 11 秒，其间是 `JavaBuilder … Compiling`（IDE 刚起来在建索引/编译），读动作排在写动作后面。
- 同一语句第三次打开：**278ms**。⇒ 拿「刚打开工程后第一次点」的读数判「插件还是慢」会误判。

**纪律**：
1. 加埋点要加在**段与段之间**（request→compute、compute→装配、装配→dialog），而不是只给最内层计时；本仓已在 `XmlSqlGutterLineMarkerProvider` 的点击闭包补 `[open-perf] pre-assembly: sourceCtx=…ms completion=…ms dumb=…`。
2. 索引期（`DumbService.isDumb`）跳过**只影响补全提示**的重活（实体/表上下文解析、schema 预热投递）——它们依赖索引，只会跟着一起等；SQL 预览本体的门禁已由 M2 dumb 门覆盖。
3. 给用户报「秒开」时要写清验收环境：**IDE 空闲后**（状态栏无索引/编译）量；冷启动期任何人（含平台自身）都在排队。

**`completion` 这一段自身还要再切三层**（2026-11-13 15:00 实测，IDE 已空闲 `dumb=false`）：
`pre-assembly: sourceCtx=17ms completion=10327ms` 看起来是「补全上下文慢」，但内部是
`ctx detail: entity=5ms resolve=66ms candidates=3230ms` —— 10 秒里 3.2 秒在**联表候选**（全项目
`@TableName` 实体逐个解析），而最终 `candidatesCount=1`。判据顺序：
- `sourceCtx` 大 → 文件/源上下文；
- `entity/resolve` 大 → 主实体链路（Mapper 泛型/方法参数解析、EntityResolver）；
- `candidates` 大 → 最贵且最不值：它只服务 JOIN 表名补全，**不该在打开路径上算**（见
  [[pitfall-candidate-scan-cap-cache-and-test-traps]]）；预览打开应 `includeCandidates=false`，
  打开后后台补齐再刷新上下文。
- 三段都小但 `request→dialog` 仍大 → 弹窗构造/面板组装，另查 `showResult` 段。

关联：[[project-startup-read-lock-freeze-20261113]]、[[pitfall-prefetch-needs-failure-memo-and-coldstart-vs-algorithm]]、[[feedback-verify-perf-non-gui-first]]。
