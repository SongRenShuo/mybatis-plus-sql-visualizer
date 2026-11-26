---
name: project-degraded-preview-ux
description: 预览降级横幅体验与降级态联动（T-DegradedPreviewUx / UI-022 / P4）——20261020 P0/P1/P2 已合 dev，UI-022 headless 未复现且沙盒实测联动正常（不成立），真缺陷改判为「首开必降级」并已修（方言重解析使预热 PSI 失效）
metadata:
  node_type: memory
  type: project
  originSessionId: session_7ca70132cbb3e95555151b4bc731ecfb
---

2026-10-20 用户报障（dev 截图）：预览弹窗顶部整行红字「⚠ 预览以降级模式打开（内存 PSI）— …参数联动与动态条件勾选在降级期不生效…」，诉求「这个错误能不能再优化下体验」。

**状态：P0/P1/P2 已合 dev 并过全量门禁，P4（真根因）已修合入，UI-022 经沙盒实测判为不成立；全部未 push。** dev 提交链：`f0499001`（特性）→ `c5f50452`（merge）→ `9a909369`（门禁读数回填）→ 并行会话 `9d0e40fa` → `309f2ed7`（P4 修）→ `a91b73fe`（P4 读数回填）。

**需求四层拆解（不是「一个错误」）**：①触发面过度披露（超 `PREWARM_WAIT_MS=500` 即挂红横幅，而 T-PrewarmLateRecover F1 的迟到产物通常几秒就自动换装）；②归因失真（超时与硬失败共用一句「临时文件不可用」，原因只进日志）；③视觉过重（DEGRADED 与 RENDER_WARNING 共用红色、两行、常驻）；④所称 UI-022。

**已核实的平台事实（243 sources jar）**：降级第二级**实为裸文档，不是 PSI 文档**——`createInMemoryPreviewPsi` 用 `PsiFileFactory.createFileFromText(name, fileType, text)`，该重载 `eventSystemEnabled=false`（`PsiFileFactoryImpl.java:151-152`）；`PsiDocumentManagerBase.getDocument` 对非 event-system provider 返回 null（`:175`），于是走 `?: EditorFactory.createDocument(sql)`；`psiFile` 只当补全上下文载体。所以横幅里「内存 PSI」的归因本身也不准。

**已实施形态（P0/P1/P2）**：
- 新增 `PreviewDegradationReason.kt`：枚举 `TIMEOUT / FILE_UNAVAILABLE / VFS_UNAVAILABLE / PSI_UNAVAILABLE / PREWARM_ERROR / PRODUCT_CONSUMED / MEMORY_PSI_FAILED` + `PreviewPrewarmException` + `PrewarmAwait.degradationReasonOrNull()`。`PREWARM_ERROR` 是我的未归类兜底（计划原文没有这一档）。
- `PrewarmAwait` 增 `isTimedOut()`/`failureOrNull()`；超时位由 `scheduleOnce` 里 `completeOnTimeout(null)` 的 whenComplete（value==null 且 error==null）置位。**判定顺序＝硬失败优先于超时**——预热可能在超时之后才走完 VFS 重试并最终失败，那时「稍后自动切换」已是假话；`flushDegradedGrace()` 到点会重取一次终态再披露。
- `showDegradedBanner(reason)` 两档：TIMEOUT 挂 `DEGRADED_GRACE_MS=3000` 一次性 Swing Timer（先清旧横幅、不显示），产物到达由主路径装配 `cancelDegradedGraceTimer()` 取消 → 横幅一次都不出现；硬失败立即显示。
- Timer 收口点：主路径装配成功、`disposeCurrentEditorOnly`（同时覆盖 `retryPrewarm`→`tryUpgradeToLateProduct`）、`dispose()`、`clear()`（经 releaseEditor）。
- `DegradationBanner.show(kind, message, tooltip)` 按 Kind 分色：DEGRADED=`WarningForeground` 橙（fallback `JBColor(0xB26A00,0xE0A94D)`）、其余保持 `ErrorForeground` 红；优先级语义不变。
- bundle：删 `degradedPsi`，新增 `degradedTimeout`/`degradedFailed`/5 条 `degradedCause.*`/`degradedHint`（悬停「若参数改动或条件勾选未生效，点重试恢复完整模式」）。`DegradedBannerWordingGuardTest` 正向断言改为「三条降级键必须写出重试」。

**P3 / UI-022 最终结论：不成立**。降级态改参数、降级态点动态条件复选框两例 headless 全绿；用户在 243 沙盒按我给的配方实测，降级横幅在场时**勾选 status 复选框与参数输入都正常生效**（截图 2/5 可见）。故 UI-022 不是真缺陷，两例保留为回归护栏。旧文案「参数联动与动态条件勾选在降级期不生效」已从正文移除，只留悬停提示。

**P4（真根因，2026-10-20 沙盒日志实锤）：首开必降级＝方言映射的语言替换重解析与预热 PSI 竞态**。日志：
```
16:14:33,649 [M36] prewarm done in 116ms (file=mpsql-preview-38116-2.sql ...)
16:14:33,688 Reparsing .../preview/mpsql-preview-38116-2.sql because of language substitution MySQL->PostgreSQL
16:14:33,696 [M36] in-memory PSI preview document created   ← 落降级
```
链路：`DialectPreMapping.ensureMapped` 只等到 EDT 的 `setMapping` 返回，平台的 `LanguageSubstitutors` 重解析是**异步**的；预热线程随即 `findFile` 拿到旧语言 PSI，重解析落地后该实例失效，装配 `PsiDocumentManager.getDocument(psi)` 抛错被 `runCatching` 吞掉 → 二级降级（横幅「临时文件解析失败」）。点「重试」时目录已是目标方言映射、不再切换 ⇒ 恢复。**表现为「首开必降级、重试就好」，与 UI-022 的联动无关**。

P4 修法（三层）：①`mapPreviewDialectBeforePsi` 返回目标 language id（映射失败返 null，不退化成白等），新增 `awaitPreviewPsiLanguage` 有界轮询**本文件 PSI 的语言**（400ms，不用全局 dumb 等待），预热首次与尾部二次映射都等落地并重取；②装配端**不再信任产物里捕获的 PSI**，按 `vFile` 重取当前实例；③装配 `getOrElse` 补一行不含 SQL 的 WARN（此前二级降级路径完全不落日志，真机形态下原因不可见）。回归用例 `test产物PSI与文件失配_装配按文件重取不降级`（传与 vFile 失配的 PSI：旧实现落降级，新实现正常挂项目内临时文件）。

**P4 复验实况（2026-10-20 用户两轮）**：第一轮只把工程方言切到 PG、但 `.idea/sqldialects.xml` 里 `preview` 仍是 MySQL 且内存映射未回到 MySQL ⇒ 日志两次都是 `prewarm done` 后直接装配、**没有 Reparsing**，属 PG 稳态，没有触发被测路径（我如实指出「这轮验不到」）。第二轮用户把 `preview` 目录映射保留 MySQL、重启后首开，日志 `16:48:07 prewarm done 419ms` → 映射落地 → **无 `in-memory PSI`、无 `main-path assembly FAILED`**，`.idea/sqldialects.xml` 的 preview 行变成 `dialect="PostgreSQL"` ⇒ 真实通过。注意仍要说明：该轮恰好是「映射先落地、PSI 后创建」，竞态未被直接压测，竞态面靠仓库新增用例（失配 PSI 必降级）兜底。

**P4 复验注意（映射稳态会掩盖原条件）**：沙盒里 `.idea/sql-visualizer/preview` 的方言映射一旦已是 PG，重开会走「稳态跳过」而切不出原来的 MySQL→PG 切换。要触发原条件＝用没打开过预览的 demo 工程（`mybatis-plus-sql-visualizer-demo-ui-fixtures/mybatis-plus-demo`），或关工程后删 `preview` 目录并删 `workspace.xml` 里指向该路径的 `SqlDialectMappings` 条目。判据：`prewarm done` 后仍可见 `Reparsing ... PostgreSQL`，但**不再**紧跟 `in-memory PSI preview document created`。

**读数**：P0~P2 定向 10 类 **65/0/0/0**，反向三处各恰 1 红（5 例 3 红、一一对应）；P4 定向 5 类 **26/0/0/0**，反向摘掉装配端重取 → 恰 1 红。**全量门禁（dev `c5f50452`）**：`testShards --no-configuration-cache` → **SHARD TOTAL 8018/0/0/9**（5 片 7987 + external 31；parity 731 类全集均有 XML）+ **agent 子项目 164/0/0/0**（真实路径 `sub-projects/sql-visualizer-agent/build/test-results/test/`，聚合脚本写成根级 `sql-visualizer-agent/` 会聚出 0＝假读数）＝ **8182/0/0/9**，BUILD 15m54s。`docker info` 退出 1（daemon 未起），Docker 测试按默认 `mpvDockerTests=false` 不参与。

**用户验收配方（两轮都给过用户的具体内容，仍适用于降级面）**：工程用 `mybatis-plus-sql-visualizer-demo/mybatis-plus-demo` 的 `UserMapper.xml#selectByMultiParamXml`（status/age 条件）；**确定性制造降级**＝把 `<project>/.idea/sql-visualizer/preview` 目录删掉、换成一个同名空文件（预热写文件必失败，硬失败立即挂橙色失败型横幅），验完删文件即自动重建；**别靠等首开超时**（3s 宽限期下常常看不到横幅）。不生效时按四类取证：A 写入被拒（有 `branch=editor` 但文本不变/抛异常）／B 写错对象（`branch=nativeDoc` 且 `attachedToMe=false` 走 recoverConsole）／C 派发丢失（有 `[T-WSC] write deferred … renderSql` 之后无 `showSql`）／D 防抖丢失（仅参数输入失效、复选框正常）。

关联：[[project-display-mask-and-degraded-preview-gaps]]（UI-021/022 原始定案）、[[pitfall-fake-green-test-assertions]]、[[project-dml-impact-prominence]]（影响行数横幅不能复用 DegradationBanner）、[[project-parallel-write-collision-preview-features]]。
