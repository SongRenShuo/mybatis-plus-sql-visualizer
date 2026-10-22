# T-DegradedPreviewUx 预览降级横幅体验与降级态联动修复 — 开发计划

- 日期：2026-10-20 13:33
- 任务编号：T-DegradedPreviewUx
- 来源：用户 2026-10-20 报障。截图为一字未改的预览弹窗：顶部整行红字「⚠ 预览以降级模式打开（内存 PSI）— 本次项目内临时文件不可用，表/列上下文补全受限，参数联动与动态条件勾选在降级期不生效。点「重试」或关闭并重新打开预览即可恢复完整支持。」右侧挂「重试」。左侧参数已勾选 `order.status=1`、`order.create_time [fill=INSERT]=111123`、`#order.id=1`，右侧 SQL 已按参数回填（`SET status = 1, create_time = '111123' WHERE id = 1`）。用户诉求是「这个错误能不能再优化下体验」。
- 前置缺陷：**UI-022**（2026-10-18 合流验收登记，`docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md:135`）——降级态下动态条件勾选联动失效，当时只做了横幅文案披露（`ca5300c9`），功能修复明确留待专项。本计划即该专项。
- 性质：体验 + 可用性。不改主路径渲染语义、不抬预热预算、不动执行链。
- 实施分支：dev（用户指定）。dev 已被独立工作树 `mybatis-plus-sql-visualizer-dev` 检出，实施与提交均在该树进行。
- 验证基线：dev `58dd0c23`（2026-10-20 13:33 实测）。

---

## 一、需求分析：先把「一个错误」拆成四层

截图里真正同时存在四件事，混在一起才显得像一次故障：

| 层 | 现象 | 用户感受 | 现状判定 |
|---|---|---|---|
| 触发面 | 预热超过 500ms 预算就立刻装配降级编辑器并挂横幅 | 每次冷启首开都「报错」 | **过度披露**。T-PrewarmLateRecover F1 已实现迟到产物自动换装（`SqlPreviewPanelEditorLifecycle.kt:674-699`），慢预热通常几秒内自愈；横幅却在自愈前先红一遍 |
| 归因 | 「本次项目内临时文件不可用」把「慢」和「失败」写成同一句 | 以为工程/权限出了问题 | **文案失真**。超时与硬失败走同一个 `fallbackPreviewDocument`（`:626-639`），原因只进日志 |
| 视觉 | `ErrorForeground` 红色、两行长文、常驻到重试/重开 | 像红色报错 | **层级过重**。能力受限是提示，不是错误 |
| 功能 | 降级态参数联动 / 动态条件勾选失效（UI-022） | 改了参数 SQL 不动，容易以为插件坏了 | **真缺陷**，且只在降级期出现，完整模式对照正常 |

用户截图里 SQL 已正确回填三个参数值，说明**首次渲染的联动是正常的**——用户当下最直接的痛点是那条红色横幅本身，而不是功能已经失效。这也说明文案必须精确：在功能可以工作的场景里告诉用户「联动不生效」，比不提示更伤。

---

## 二、现状链路（dev `58dd0c23`，逐处出证）

| # | 位置 | 事实 |
|---|---|---|
| 1 | `SqlPreviewPanel.kt:803-805` | 面板 init 末尾发起预热（`project != null` 时） |
| 2 | `SqlPreviewPanelEditorLifecycle.kt:67-76` | 每轮预热生成轮次令牌 `prewarmRound` + `PrewarmAwait`，预算 `PREWARM_WAIT_MS` |
| 3 | `SqlPreviewPanelEditorLifecycle.kt:86-162` | 池线程建项目内临时文件 → VFS refresh → 方言映射 → PSI；任一步失败置 `prewarmFailed` 并 `await.fail(...)`，日志带具体原因 |
| 4 | `PrewarmAwait.kt:74-83` | `scheduleOnce` 注册消费方；`future.completeOnTimeout(null, 500ms)` 把超时变成「以 null 完成」 |
| 5 | `PrewarmAwait.kt:50-54` | 超时后预热线程再 `complete(product)` 返回 false → 走 `lateProductListener`（迟到通道） |
| 6 | `SqlPreviewPanelEditorLifecycle.kt:407-424` | 冷态返回「本次不装配」，回调 `onReady` / `onFallback` 二选一 |
| 7 | `SqlPreviewPanelEditorLifecycle.kt:626-639` | 降级链：第二级「内存 PSI」→ 第三级裸文档；两条分支都挂 DEGRADED 横幅 |
| 8 | `SqlPreviewPanelEditorLifecycle.kt:648-654` | `showDegradedBanner` 只按 key 取文案，不携带任何原因 |
| 9 | `SqlPreviewPanelEditorLifecycle.kt:712-733` | `retryPrewarm` 重新臂预热；成功换装、失败保留动作 |
| 10 | `SqlPreviewPanelSqlView.kt:177-268` | `renderSql` 唯一渲染入口；写安全闸不通过时重投递到 nonModal |
| 11 | `SqlPreviewPanelSqlView.kt:694-709` | 已有编辑器分支：`WriteCommandAction { existing.document.setText(text) }` —— UI-022 的症状若成立，断点必在此段或其上游 |
| 12 | `SqlPreviewPanel.kt:606-612`、`:726-741` | 横幅实例与布局位置（工具栏下方 `northHost.SOUTH`，与影响行数横幅上下各占一行） |
| 13 | `DegradationBanner.kt:51-56`、`:106-134` | DEGRADED 与 RENDER_WARNING 共用 `ErrorForeground` 红色；优先级 DEGRADED(2) > RENDER_WARNING(1) > DOLLAR_INJECT(0) |
| 14 | `SqlPreviewPanel.kt:1394-1410` | `PREWARM_WAIT_MS = 500` 的取值依据（冷启 233/239/360ms 采样，约 40% 余量），并明确「重负载下的正解是让预热更快，不是抬预算」 |
| 15 | `MpSqlVisualizerBundle_zh.properties:1356`、`MpSqlVisualizerBundle.properties:1352` | 中英文案当前把「临时文件不可用 + 补全受限 + 联动不生效 + 重试/重开」四件事压成一句 |

### 2.1 已核实的关键事实（会影响方案与文案口径）

1. **降级第二级并不是「PSI 文档」**。`createInMemoryPreviewPsi` 调的是 `PsiFileFactory.createFileFromText(name, fileType, text)`（`SqlPreviewPanelEditorLifecycle.kt:749-751`），该重载把 `eventSystemEnabled` 传 `false`（243 sources `PsiFileFactoryImpl.java:151-152`）；而 `PsiDocumentManager.getDocument(psi)` 对非 event-system 的 view provider 直接返回 `null`（`PsiDocumentManagerBase.java:175`）。所以实际落到 `?: EditorFactory.getInstance().createDocument(sql)`（`:754-755`）——**编辑器文档是裸文档，`psiFile` 只作为补全上下文载体**。横幅说「内存 PSI」以及把限制归因到 PSI，都需要重新校准。
2. **迟到换装只覆盖「用户未编辑」窗口**。`tryUpgradeToLateProduct` 要求当前文档文本等于 `lastRenderedSql`（`SqlPreviewPanelEditorLifecycle.kt:683`）；用户在等待期动过编辑器，产物到达也不换装，横幅只能等手动「重试」。
3. **硬失败没有任何独立表达层**。`fallbackPreviewDocument(sql, msg)` 的 `msg` 只写日志（`:634-635`），横幅不看它。
4. **UI-022 的失效机制未定位**。验收记录只有现象与「完整模式对照正常」，没有定位到具体写入步骤；本计划把它列为第一优先的证伪项（§七 前提 1）。

---

## 三、目标与非目标

**目标**

- G1 瞬时降级不打扰：预热慢但仍在途时，不立即挂红色横幅；产物在宽限期内到达则全程无横幅（复用既有自动换装，不新增恢复路径）。
- G2 持续降级说真话：超时未恢复 / 文件不可建 / VFS 失败 / PSI 失败各自有原因短句，横幅不再把「慢」说成「不可用」。
- G3 降级态联动可用：参数输入与动态条件勾选在降级态照常重渲染（修 UI-022，先红后绿）。
- G4 视觉降噪：降级横幅改为提示色、单行优先、原因与层级进 tooltip；`RENDER_WARNING` 仍保持红色，类别优先级不变。

**非目标**

- 不抬 `PREWARM_WAIT_MS`，不重排预热线程，不把预热提前到工程打开（`SqlPreviewPanel.kt:1405-1408` 的既有定例）。
- 不把降级横幅做成「可关闭」。诚实降级要求能力受限必须可见；G1 是**延迟披露**（宽限期），不是不披露——超时仍未恢复必须出现。
- 不改控制台挂载链（`nativeEditorDoc` 分支）与执行链。
- 不顺手修其他登记项（UI-024 窗口 z 序等）。

---

## 四、方案

### P0 归因分型（数据面，必做前置）

现在「超时 / 文件建不出来 / VFS 刷新失败 / PSI null / 产物已消费」都压成同一个 `degradedPsi`。要分型，先把原因传下来：

1. 新增 `enum class PreviewDegradationReason { TIMEOUT, FILE_UNAVAILABLE, VFS_UNAVAILABLE, PSI_UNAVAILABLE, PREWARM_ERROR, PRODUCT_CONSUMED, MEMORY_PSI_FAILED }`（放 `ui` 包，纯枚举，零平台依赖；`PREWARM_ERROR` 是未归类异常的兜底，避免把方言映射等失败硬塞进 PSI 原因）。
2. `PrewarmAwait` 增加只读观测口：`isTimedOut()`（`isDone && joinOrNull()==null && 失败为超时而非 fail）与 `failureOrNull()`。实现上在 `fail` 时保存 `Throwable`，超时由 `completeOnTimeout` 的 null 判定——不要为观测口新造状态机。
3. 预热线程在 `projectPreviewFile()` 返回 null / VFS refresh null / PSI null 三处把原因放进失败对象（可用带 `reason` 字段的自定义异常，或 `IllegalStateException` 的子类）。
4. `resolvePreviewEditorSource` 的 `onFallback` 与热态失败路径把原因传给 `assemblePreviewEditor(sql, null, reason)` → `fallbackPreviewDocument(sql, reason)`。
5. 横幅按 reason 选 key；日志补一行 `[M36] degraded: reason=…, round=…`（不含 SQL 文本），供后续真机取证。
6. `fallbackPreviewDocument` 的字符串 `msg` 只留日志用途，不再决定展示文案。

### P1 宽限期（瞬时降级不打扰）

1. 新增常量 `DEGRADED_GRACE_MS`（建议 3000ms，实施前按 `[M36] prewarm done in Xms` 现有样本校准，见 §七 前提 4）。
2. 展示策略按 reason 分两档：
   - **TIMEOUT**（产物仍在途）：先不显示横幅；启动一次性 Swing Timer。产物在宽限期内到达 → `assemblePreviewEditor` 主路径清 DEGRADED 时同时取消 Timer，横幅**一次都不出现**；宽限期到点仍未升级 → 显示超时型横幅（动作=重试）。
   - **硬失败**（FILE_UNAVAILABLE / VFS / PSI / MEMORY_PSI_FAILED / PRODUCT_CONSUMED）：立即显示失败型横幅（动作=重试）。
3. Timer 生命周期挂在面板 Disposer 上；`clear()`、`releaseEditor()`、`dispose()`、成功换装四个收口点都要取消（照 `renderDebounceTimer` 的既有形态，不新造调度机制）。
4. 宽限期内用户已经开始编辑也没关系：此时没有横幅，迟到换装在「未编辑」守卫下按既有语义放弃，横幅在到点时出现——文案里保留「重试」即可，不额外做交互。
5. 「重试」在超时型横幅上仍有效（既有 `retryPrewarm` 已覆盖「产物迟到」与「重新预热」两条出口），不改语义。

### P2 文案与视觉

1. 新增两个 key（中英同步，过 i18n parity 守卫）：
   - `panel.banner.degradedTimeout`：「⚠ 预览以简化模式显示 — 完整编辑器仍在准备中，稍后会自动切换；表/列补全暂不可用，也可点「重试」。」
   - `panel.banner.degradedFailed`：「⚠ 预览以简化模式打开 — {0}；表/列补全暂不可用。可点「重试」重新准备。」`{0}` = 原因短句（`panel.banner.degradedCause.file` / `.vfs` / `.psi` / `.consumed`）。
   - `panel.banner.degradedPlain` 保留（第三级：连裸文档都建不出来时的极端态）。
2. **关于「参数联动与动态条件勾选不生效」这句**：P3 修复前保留并在前面加「降级期间」限定；P3 修复后必须删除（文案不得承诺代码能做到的事，也不得反过来声称做不到）。这条作为 P3 的验收项，不做「修完忘了改文案」。
3. 视觉：`DegradationBanner` 按 `Kind` 取色——DEGRADED 改为 `JBColor.namedColor("Notification.Warning.foreground", JBColor.YELLOW)` 一类提示色（具体命名色实施时按 243 平台 `EditorColors`/`JBColor` 现有键核对，禁止自造）；RENDER_WARNING 保持 `ErrorForeground`。类别优先级不变。
4. 文案长度控制在单行可读（≈60 字内）；原因与当前层级放 tooltip（`label.toolTipText`），不引入「详情」按钮，不新增弹窗。
5. `DegradedBannerWordingGuardTest` 增两条断言：①降级横幅必须给出「自动恢复」或「重试」之一；②不得出现「索引/indexing/dumb」这类代码不存在的条件（既有断言保留）。

### P3 修复 UI-022（先红后绿）

**第一步永远是复现，不是改代码。**

1. 新增 `SqlPreviewPanelDegradedRenderTest`（BasePlatformTestCase），复用 `SqlPreviewPanelPrewarmLateRecoverTest.degradePanel` 的稳定降级构造法（先等预热出结果 → 首装配消费产物 → `releaseEditor` → 二次装配必降级 → 断言横幅在场），然后：
   - 用例 A：`paramValues["username"] = "x"` → `renderSql()` → 编辑器文档文本应含 `x`；
   - 用例 B：`enabledIds.add(conditionId)` → `renderSql()` → SQL 应新增对应条件分支。
2. 若 headless 下两例都绿：说明缺陷只在真实 GUI/对话框上下文出现，转沙盒定位（§七 前提 1 的替代路线），本计划只落 P0/P1/P2 并把 P3 拆成独立专项；**不得**因为 headless 绿就宣布 UI-022 已修。
3. 若红，按下列候选逐条排除（每步只加一行临时日志，定位后立刻删）：
   | 候选 | 判据 | 修法 |
   |---|---|---|
   | A 写入被拒 | `existing.document.setText` 前后文本长度不变或抛异常 | 降级文档改用 `EditorFactory.createDocument` 的裸文档（已是事实形态）；若仍被拒，改走「替换编辑器」路径（复用 `disposeCurrentEditorOnly` + `assemblePreviewEditor`），并保证只替换文档不换编辑器 |
   | B 写错对象 | `showSql` 进了 `nativeDoc` 分支且 `consoleAttachedToMe()` 为 false → `recoverConsole` 丢弃 | 降级装配时记录控制台绑定态，渲染分支对降级文档加兜底 |
   | C 派发丢失 | `[T-WSC] write deferred` 出现但后续无渲染日志 | 修正 `DocWriteContext` 的投递模态/守卫，不改渲染语义 |
   | D 防抖丢失 | `scheduleRender` 的 Timer 未触发 | 降级态改同步渲染（与单元测试口径一致），或修正 Timer 生命周期 |
4. 修复后把 P2-2 的「联动不生效」从句文案删除，并把 UI-022 在验收记录里的状态改为「已修」。
5. 回归既有四类：`SqlPreviewPanelPrewarmLateRecoverTest`、`SqlPreviewPanelPrewarmConsumeTest`、`DegradationBannerTest`、`CrudSqlPreviewPanelRenderTest`。

### P4 首开必降级的真根因：方言重映射与 PSI 的竞态（2026-10-20 沙盒日志实证）

真机日志（IU-243 沙盒，16:14:33）：

```
16:14:33,649 [M36] prewarm done in 116ms (file=mpsql-preview-38116-2.sql ...)
16:14:33,688 Reparsing .../preview/mpsql-preview-38116-2.sql because of language substitution MySQL->PostgreSQL
16:14:33,696 [M36] in-memory PSI preview document created (len=250)   ← 落降级
```

预热成功却仍降级，链路如下：

1. `DialectPreMapping.ensureMapped` 只等到 EDT 的 `setMapping` 返回（`execute/DialectPreMapping.kt` 的 latch），平台随后的 `LanguageSubstitutors` 重解析是**异步**任务；
2. 预热线程在 `setMapping` 返回后立刻 `PsiManager.findFile`，拿到旧语言 PSI；
3. 重解析落地后该实例失效，装配时 `PsiDocumentManager.getDocument(psi)` 抛错 → `runCatching` 吞掉 → 二级降级（横幅「临时文件解析失败」）；
4. 点「重试」时目录已是目标方言映射、不再切换 → 成功恢复。表现为「首开必降级、重试就好」——这才是用户报的「问题没解决」；UI-022 的联动在降级态实测是好的（复选框与参数输入均生效）。

修法（三层）：

- **预热**：`mapPreviewDialectBeforePsi` 返回本次映射的目标 language id；新增 `awaitPreviewPsiLanguage` 有界轮询**本文件** PSI 的语言是否已等于目标（不用全局 dumb 等待），落地后再取实例；预热尾部二次映射同样等落地并重取。
- **装配**：主路径不再信任产物里捕获的 PSI，按 `vFile` 重取当前实例（`assemblePreviewEditor`），作为最后兜底。
- **诊断**：装配 `getOrElse` 补一行不含 SQL 的 WARN——此前异常只传到二级降级的 msg、仅第三级才落日志，真机首开形态下原因完全不可见。
- **回归用例**：`test产物PSI与文件失配_装配按文件重取不降级`（传入与 vFile 失配的 PSI：旧实现落降级，新实现正常挂在项目内临时文件上）。
- **真机复验判据**：方言从 MySQL 切到 PG 的那次首开不再出现降级横幅；日志里 `prewarm done` 后仍可看到 `Reparsing ... PostgreSQL`，但**不再**紧跟 `in-memory PSI preview document created`。

---

## 五、决策点

| 方案 | 做法 | 利 | 弊 |
|---|---|---|---|
| **A（推荐）** | 宽限期 + 原因分型 + 修 UI-022 | 瞬时噪音清零、持续降级说真话、功能闭环 | 改动面最大（预热链、横幅、渲染链） |
| B | 只改文案与配色，不动触发面与功能 | 半天可交，风险最低 | 红横幅仍在首开闪一下；UI-022 继续挂着 |
| C | 只修 UI-022，横幅不动 | 功能收益最大 | 用户当前抱怨的就是横幅观感，等于没回应诉求 |

**推荐 A**：用户报的是横幅，但「红横幅之所以刺眼」正是因为它把「慢」说成「故障」，把「能用的联动」说成「不生效」。只改颜色属于化妆，只修功能则横幅仍在说谎。A 的每一项都对应一条已在册的事实（迟到换装、原因丢失、UI-022），不是新增机制。

---

## 六、验收标准

### 6.1 自动化

| # | 用例 | 判据 |
|---|---|---|
| 1 | 超时降级 → 宽限期内产物到达 | 横幅 `isVisible` 全程 false（在 Timer 到点前后各断言一次），文档落在项目内临时文件上 |
| 2 | 超时降级 → 宽限期到点仍未升级 | 横幅出现，文案为 timeout 型，`retryVisibleForTest()==true` |
| 3 | 硬失败（模拟 `projectPreviewFile` 返回 null） | 横幅**立即**出现，文案为 failed 型且带原因短句，重试可见 |
| 4 | 降级态改参数 / 勾选条件 | `renderSql()` 后编辑器文档文本随之变化（UI-022 正向） |
| 5 | 预热在预算内（主路径） | 无 DEGRADED 横幅（对照，防宽限期误挂） |
| 6 | `clear()` / dispose / 成功换装 | 宽限 Timer 已取消，无迟到显示、无 Timer 泄漏 |

**反向验证**（每条都必须先红后绿，读数写进实施记录）：

- 摘掉宽限 Timer（超时立即显示）→ 用例 1 必红；
- 把原因分型改回单一 key → 用例 3 必红（文案不匹配）；
- 摘掉 UI-022 修复 → 用例 4 必红；
- 把 `DEGRADED` 色改回 `ErrorForeground` → 若做了取色守卫，对应断言必红（无守卫则以截图取证，不伪造读数）。

**本轮实测口径（2026-10-20）**：用例 1/2/3 与「清空即取消」共 5 例在实施后全绿；把「超时立即显示」「FILE_UNAVAILABLE 不带原因」「disposeCurrentEditorOnly 不取消定时器」三处保护分别摘掉后，**各命中 1 例红（共 3 红）**，恢复后复绿。用例 4/5（降级态改参数 / 勾选动态条件）在 headless 下**也是绿的**——即 UI-022 未能在单元环境复现（详见 §七 前提 1 的收口），故这两例当前身份是**回归护栏**而非「修复证明」，不得据此宣布 UI-022 已修。

### 6.2 真机 / 沙盒走查

1. 冷启首开（重负载场景）：**不应出现红色横幅**；若出现，应是提示色、单行、且数秒内自动消失。
2. 降级态（手动制造：预热期间锁住 `.idea/sql-visualizer/preview` 或临时降预算）下改参数、勾选 XML 条件，SQL 即时变化。
3. 硬失败态：横幅为「简化模式打开」+ 原因短句 + 重试；恢复权限后点重试能回到完整模式并清横幅。
4. 中英文与 243/253/262 三平台：文案不截断、tooltip 可读、颜色在明暗主题下可辨（命名色自适应）。

---

## 七、待验证前提（实施前先证伪）

1. **UI-022 的失效机制**：能否在 headless 用「稳定降级态 + `renderSql()`」复现？验收记录只有现象，没有定位。若 headless 复现不了，P3 必须转沙盒，**不允许**按猜测改写入链。
   - **2026-10-20 收口**：按 `SqlPreviewPanelDegradedUxTest` 两例实测——降级态改 `paramValues` 后 `renderSql()` 文档含新值；降级态点击动态条件复选框后 SQL 追加 `station_id = 2`——**两例全绿，headless 未复现 UI-022**。结论：缺陷若仍存在，只在真实 GUI/对话框上下文（疑点集中在 `DocWriteContext.isWriteAllowedNow()` 的延迟投递与对话框模态/焦点链，headless 恒返回 true 走不到那条路）。P3 功能专项保留在册，下一轮转 243 沙盒定位；本轮不按猜测改渲染链。
2. **降级文档的实际归属**：计划已按 243 sources 推定「第二级实际是裸文档」（`PsiFileFactoryImpl:151-152` + `PsiDocumentManagerBase:175`），但需在 253/262 复核同一行为；若平台版本间有差异，文案与修法都要跟着改。
3. **`WriteCommandAction` 对裸文档的写入**：读码成立，但 UI-022 若正是「写入被拒」，说明存在未读到的平台约束。实施第一步的日志探针就是为这条准备。
4. **宽限期取值**：3000ms 是建议值，不是实测值。实施前先取 5~10 条 `[M36] prewarm done in Xms` 样本（含首开与复开），按时长分布定；不得为「看起来稳」随意放大到 5s 以上。
5. **「延迟披露」是否触碰诚实降级红线**：本计划取「宽限期内不显示、到点必须显示」，理由是此时用户通常尚未开始交互且能力仍会自动恢复。若用户认为任何受限状态都必须立刻可见，则回退为「先显示提示色文案，超时后升级为失败型」，方案 B 的路径。
6. **`DegradationBanner` 取色改动影响面**：DEGRADED 与 RENDER_WARNING 共用 label，需按 Kind 分色而不是改字段；`DegradationBannerTest` 的显隐/优先级用例必须保持绿。
7. **`[M36] prewarm FAILED` 的真实分布**：本计划不假设用户遇到的具体是超时还是硬失败。实施前请用户提供最近一次 `idea.log` 中 `[M36] prewarm` 与 `in-memory PSI` 两类行；若长期只有硬失败，P1 宽限期收益接近零，应把工时移到 P0 的根因修复。

---

## 八、风险

| 风险 | 处置 |
|---|---|
| 宽限期掩盖真实硬失败 | 硬失败立即显示，不走宽限；只有「产物仍在途」的超时才延迟 |
| Timer 泄漏 / 面板销毁后仍弹横幅 | Timer 挂在面板 Disposer；`clear/releaseEditor/dispose/换装成功` 四处取消；用例 6 钉死 |
| 文案改动破坏 i18n 守卫 | 中英同步 + `DegradedBannerWordingGuardTest` + parity 守卫；新 key 不删旧 key（先并存，P3 完成后再删「联动不生效」句） |
| P3 定位失败导致计划缩水 | 计划已写明退路：P0/P1/P2 独立可交付，P3 转专项；不把「headless 绿」当修复证据 |
| 降级态改同步渲染引入卡顿 | 只在降级分支改，主路径不动；大 SQL 场景按 `scheduleRender` 既有防抖保留 |
| 与并行会话写集冲突 | 主写集锁定 `DegradationBanner.kt`、`SqlPreviewPanelEditorLifecycle.kt`、`SqlPreviewPanelSqlView.kt`、两个 bundle；`SqlPreviewPanel.kt` 只改常量与布局不动渲染；动工前先 `git status` + 并行登记 |

---

## 九、实施顺序

P0 归因分型 → P1 宽限期 → 用例 1/2/5/6 → P2 文案与取色 → 用例 3 → P3 复现（先红）→ 修复 → 用例 4/反向 → 定向回归 → 沙盒走查（冷启首开 + 硬失败 + 降级联动）→ 回填 §十。

门禁按仓库三档分层：每步只跑定向；全量由独立收口代理在独立沙盒跑 `testShards`；本任务不动构建配置与 `plugin.xml`，`verifyGate` 不单跑。

---

## 十、实施记录（实施后回填）

| 日期 | 事项 |
|---|---|
| 2026-10-20 | 立项。核实 dev `58dd0c23` 现状：横幅为 DEGRADED 单一文案、红色、超时与硬失败同路；UI-022 功能修复仍空缺。代码未改。 |
| 2026-10-20 | **实施（工作树 `mpsv-degraded-ux`，分支 `t-degraded-preview-ux`，自 dev `6d972d82`）**。改动面：新增 `PreviewDegradationReason.kt`（原因枚举 + `PreviewPrewarmException` + `PrewarmAwait.degradationReasonOrNull()`）；`PrewarmAwait` 增 `isTimedOut`/`failureOrNull` 观测口（`scheduleOnce` 的 `completeOnTimeout` 置超时位）；预热三条失败分支改抛带原因的异常；`fallbackPreviewDocument(sql, reason, msg)` 按原因选文案；`showDegradedBanner(reason)` 分两档——TIMEOUT 挂 `DEGRADED_GRACE_MS=3000` 宽限定时器（产物到达即取消、到点重取终态再披露），硬失败立即显示；`DegradationBanner` 按 Kind 分色（DEGRADED=WarningForeground 橙、RENDER_WARNING 保持红）并支持 tooltip；bundle 中英新增 `degradedTimeout`/`degradedFailed`/5 条 cause，删除 `degradedPsi`；`DegradedBannerWordingGuardTest` 正向断言改为三条新键必须写出「重试」。 |
| 2026-10-20 | 定向测试（本批）：新增 `SqlPreviewPanelDegradedUxTest` 5 例（宽限期不显示/到点显示、硬失败立即显示、clear 取消定时器、降级态改参数重渲染、降级态勾选动态条件重渲染）+ `SqlPreviewPanelPrewarmLateRecoverTest` + `SqlPreviewPanelPrewarmConsumeTest` + `PrewarmAwaitTest` + `DegradationBannerTest` + `DollarInjectBannerTest` + `CrudSqlPreviewPanelRenderTest` + `SqlPreviewPanelDynamicBatchTest` + `DegradedBannerWordingGuardTest` + `BundleParityGuardTest`。**读数：10 类 65 例 / 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESSFUL 2m55s**。 |
| 2026-10-20 | **反向验证**：三处保护分别摘掉（①TIMEOUT 立即显示；②FILE_UNAVAILABLE 的 causeKey 置 null；③`disposeCurrentEditorOnly` 不取消定时器）→ `SqlPreviewPanelDegradedUxTest` **5 例 3 红**，恰好一条对一处（`超时降级_宽限期内不显示_到点显示` / `硬失败_立即显示原因与重试` / `宽限定时器_面板清空即取消`）；恢复三处后复跑 **5/0/0**。 |
| 2026-10-20 | **P3 结果（如实登记）**：降级态改参数、勾选动态条件两例在 headless **均绿**，UI-022 未复现 ⇒ 按计划 §四 P3-2 保留两例为回归护栏，功能专项留在册（下一轮转 243 沙盒；疑点为生产态 `DocWriteContext.isWriteAllowedNow()` 走延迟投递，而 headless 恒 true 走不到）。为避免「未修复却静默」，降级横幅悬停提示新增 `panel.banner.degradedHint`「若参数改动或条件勾选未生效，点重试恢复完整模式」——单行正文不恢复旧那句硬断言。 |
| 2026-10-20 | 本轮未做：真机/沙盒走查（用户限定只跑定向测试）、UI-022 沙盒定位、全量门禁（按仓库三档分层留给收口代理）。 |
| 2026-10-20 | **终版复跑**（含悬停提示 `degradedHint` 与文档台账改动后）：同一组 10 类定向 **65 例 / 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESSFUL 57s**。改动未提交（用户未要求提交）。 |
| 2026-10-20 | **合入 dev**：特性提交 `f0499001` → dev 合并提交 `c5f50452`（父 `82ab60be` + `f0499001`，无冲突）；合并后工作树已清理（分支删除、`mpsv-degraded-ux` 移除）。合并后定向 6 类 **30/0/0/0**。 |
| 2026-10-20 | **全量门禁（用户指定，dev `c5f50452`）**：`./gradlew testShards --no-configuration-cache` → **SHARD TOTAL 8018 例 / 0 失败 / 0 错误 / 9 跳过**（5 片 7987 + external 31；parity 731 类全集均有结果 XML），另 agent 子项目 **164/0/0/0**，合计 **8182/0/0/9**；BUILD SUCCESSFUL 15m54s。环境：Docker daemon 未运行（`docker info` 退出 1），Docker 测试按默认 `mpvDockerTests=false` 不参与，9 跳过同既有基线口径。 |
| 2026-10-20 | **用户真机复验反馈「问题没解决」→ 读 `idea.log` 定位到 P4**：首开预热 116ms 成功，但方言映射 MySQL→PostgreSQL 的语言替换重解析异步落地，预热捕获的 PSI 失效 → 装配 `getDocument` 失败 → 降级。配套实证：UI-022 的复选框/参数联动在降级态正常（用户截图 2/5），故 UI-022 不成立，真正缺陷是 P4。 |
| 2026-10-20 | **P4 实施（工作树 `mpsv-prewarm-psi-race`，分支 `t-prewarm-psi-race`，自 dev `9d0e40fa`）**：`mapPreviewDialectBeforePsi` 返回目标 language id；新增 `awaitPreviewPsiLanguage`（轮询本文件 PSI 语言，400ms 有界）；预热两处映射后等落地并重取 PSI；装配端按 vFile 重取 PSI；装配失败补 WARN。新增用例 `test产物PSI与文件失配_装配按文件重取不降级`。 |
| 2026-10-20 | **P4 读数**：定向 5 类 **26/0/0/0**；反向摘掉装配端重取（改用产物里的 PSI）→ `SqlPreviewPanelPrewarmConsumeTest` **恰 1 红**（新增用例），恢复后复绿。已并入 dev（fast-forward 到 `309f2ed7`）；真机/沙盒复验待用户。 |
