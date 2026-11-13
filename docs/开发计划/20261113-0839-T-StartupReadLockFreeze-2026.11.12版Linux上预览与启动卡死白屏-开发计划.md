# T-StartupReadLockFreeze：2026.11.12 在 Linux 上打开即卡死 / 预览白屏——后台阻塞读锁与 EDT 写锁升级互锁

- 立项：2026-11-13 08:39（用户三批现场材料：两份 Java 线程栈 + 一张白屏窗口图 + 冻结报告目录截图）
- 状态：**已出计划，未动 `src/main`**；用户侧即时自救见 §六，不需要等本单实施
- 现象口径（用户原话）：「2026.11.12 版 mac 上正常，linux 上 idea 打开后就卡死」；「昨天那个白屏问题依然存在，点击后好久才出来界面，界面还空白，估计是在加载或者扫描，然后就开始 dump 了」；工程规模「表上千了，实体类上万」
- 关联代码：`ui/MbgExampleCallResolver.kt`、`ui/gutter/XmlSqlGutterLineMarkerProvider.kt`、`ui/XmlPreviewService.kt`、`execute/DataSourceSelectorResults.kt`、`execute/DataSourceSelector.kt`、`execute/DocWriteContext.kt`
- 关联版本：tag `2026.11.12`（预取在该 tag 首次进包；`2026.11.11` 的 gutter provider 里没有这条调用，`git show` 实证为空）

---

## 一、结论

1. **冻结的两个当事人已由两份栈钉死，不是猜的。**
   - 后台侧（栈 1）：`MbgExampleCallResolver.prefetch` 在 **阻塞读锁**（`ReadAction.compute` → `computeBlocking`）里跑 `ReferencesSearch` 索引检索。
   - EDT 侧（栈 2）：`AWT-EventQueue-0` 正走「写意图读锁 → 写锁升级」，卡在 `NestedLocksThreadingSupport.upgradeWritePermit` / `prepareWriteFromWriteIntentBlocking`，升级动作由我方 `docWriteOnce` 的 `WriteAction.run` 发起。
   - 两者互等：EDT 等后台释放读锁；后台的 `computeBlocking` **不可取消**，且在索引构建期会同步等待 smart mode（仓内 2026-11-12 11:21 冷启动实测注释在 `ui/XmlPreviewService.kt:118-120`）。索引推进又需要写权限 → 闭环，UI 永久挂死，冻结报告每 5 秒一份。

2. **版本相关性成立。** 11.11 tag 的 `XmlSqlGutterLineMarkerProvider` 没有 `prefetch`（`git show 2026.11.11:…` 输出为空）；`git diff --name-status 2026.11.11 2026.11.12` 显示 `A ui/MbgExampleCallResolver.kt`。冻结栈里高亮（绿底）的正是 11.12 新进的这条链。

3. **同一份 M2 检索，预览链有 dumb 门、预取链没有**（显式不对称）：
   - 预览：`XmlPreviewService.kt:121` `val m2Allowed = project != null && !DumbService.getInstance(project).isDumb`，跳过时只退回占位骨架；
   - 预取：`MbgExampleCallResolver.prefetch`（`:149-170`）整段没有任何 `DumbService` / 项目初始化 / 规模判断。

4. **工程规模是放大器，不是背景。** 用户工程 >1000 表、上万实体；检索作用域是 `GlobalSearchScope.moduleWithDependentsScope(module)`（`MbgExampleCallResolver.kt:193-196`），在这个量级上单次检索从毫秒级变成数十秒到分钟级，把「理论上可能的互锁」变成每次都能撞上的必现。

5. **Linux vs mac 不是 OS 分支。** 两条链 grep 无 `os.name` / 分隔符分支；用户 Linux 栈里出现 `java.base@25.0.4.1`、`NestedLocksThreadingSupport`、`SuvorovProgress`、`platform.trialPromotion.idesWithFreeTier` —— 说明那台机器装的是**更新一代的 IDE**（新读写锁实现）。最可能的差异是 **IDE 构建 × 冷索引**，判别步骤见 §八，不要按操作系统打补丁。

---

## 二、现场证据

### 2.1 栈 1：后台预取线程（2026-11-13 08:24 抓取，绿底为插件帧）

自下而上（调用方向）：

| 帧 | 读数 |
|---|---|
| `MbgExampleCallResolver.prefetch$lambda$1(MbgExampleCallResolver.kt:159)` | 预取 body 的 `executeOnPooledThread` 闭包（当前源 `:157-166`） |
| `MbgExampleCallResolver.resolve(:95/:96)` | `ReadAction.compute { resolveCached(key) { resolveInReadAction(…) } }`（`:95` 是 compute 行，`:96` 是闭包行） |
| `ReadAction.compute(ReadAction.java:59)` → `computeBlocking(:88)` | **该 IDE 构建里 `compute` 已委托 `computeBlocking`**——阻塞读锁，不可被写动作取消 |
| `ApplicationImpl.runReadAction(1124)` → `NestedLocksThreadingSupport.runReadAction(862)` | 平台新读写锁实现 |
| `resolveInReadAction(:208)` → `findCallSite(:254-271)` | `ReferencesSearch.search(method, scope).forEach { … }`（`:257`） |
| `Query.asIterable` → `AbstractQuery.findAll` → `SearchRequestQuery.processResults` → `PsiSearchHelperImpl.processRequests(888)` → `ConcurrencyUtils.runWithIndicatorOrContextCancellation` | 索引检索本体 |

两个附带事实：
- `inFlight` 只防并发，不防时长（`prefetch` `:155` `if (inFlight.isNotEmpty()) return`），一条卡住的检索期间后续 gutter 全部跳过预取——**不会自愈，只会一直挂着**。
- `findCallSite` 空结果时会**再跑一遍全量检索**取 `total` 只为日志（`:268` `ReferencesSearch.search(method, scope).findAll().size`）；在失败常态（`countByExample` 类）里这是双倍成本。

### 2.2 栈 2：EDT（`AWT-EventQueue-0`，`TIMED_WAITING`）

自下而上：

| 帧 | 读数 |
|---|---|
| `ApplicationImpl.runIntendedWriteActionOnCurrentThread(1109)` ← `ApplicationImpl$7.run(661)` | EDT 此时处在平台授予的**写意图读动作**里 |
| `TransactionGuardImpl.runWithWritingAllowed(246)` / `$1.run(208)` | 该 runnable 被判定 write-allowed（`DocWriteContext.isWriteAllowedNow()` 因此返回 true） |
| `DataSourceSelectorResultsKt.dispatchDocWriteOnEdt(:2588-2589) lambda$2` | 我方派发点：`app.invokeLater { DocWriteContext.runWriteSafe(project, "exec doc sync (bounded)") { docWriteOnce(…) } }` |
| `DocWriteContext.runWriteSafe(:72)` | 判定可写 → **就地执行** `block()`（即「EDT 调用方同步直执行」那条设计分支） |
| `DataSourceSelector.docWriteOnce$mybatis_plus_sql_visualizer(DataSourceSelector.kt:2085)` | 写体，`WriteAction.run` 包 `writeVfTextViaDocument` |
| `WriteAction.run(35)` → `ApplicationImpl.runWriteAction(1198)` → `runWriteActionBlocking(1004)` → `prepareWriteFromWriteIntentBlocking(1213)` → `processWriteLockAcquisition(1472)` → `upgradeWritePermit(385)` → `runSuspendMaybeConsuming(1659)` → `runSuspendWithWaitingConsumer(1736)` | **升级写锁时等待其它读者释放** |
| `ApplicationImpl$postInit$2(1468)` → `SuvorovProgress.dispatchEventsUntilCompletionCompletes` → `showNiceOverlay` → `EventStealer.waitForPing` → `LinkedBlockingQueue.poll` | 冻结覆盖层已经弹起来了（`IntelliJ IDEA is not responding  Dump threads Ctrl+D`），与用户截图的弹窗一致 |

### 2.3 白屏图与冻结目录

- 预览窗标题是 `SQL Visualizer - SQL 预览`，内容区整片空白（暗色底、无任何控件），底部弹 `IntelliJ IDEA is not responding`。→ 窗口已创建、**装配与首帧被锁在后面**，用户看到的「点开好久才出来、出来还是空白」就是这个窗口。
- 同一时刻第三张图（同一次会话稍后）显示预览面板**已能正常渲染**：语言 `Oracle（已绑定数据源）`、`表: ct_chainstore_info_exam ⇢ …CtChainstoreInfoExample`、左侧 `foreach 集合：oredCriteria 已还原 1 项 / criteria.criteria 已还原 1 项`。→ 说明 M2 反推本身是成功的，慢与冻结不在「解析对不对」，而在**取数窗口太长**。
- 冻结报告目录 `threadDumps-freeze-20261113-082447`：`report.txt` 32.4 KB、8 份 `threadDump-*.txt`（每份 ~2.1 MB，间隔 5 秒，08:24:47→08:25:24）、`.throwable` 5.8 KB、`.message` 139 B、`.appinfo` 559 B。**用户目前只给了截图，报告文件本身没拿到**（§十一）。

### 2.4 版本 × 代码对账

| 项 | 读数 | 出处 |
|---|---|---|
| 11.11 tag 有无预取 | 无（`XmlSqlGutterLineMarkerProvider` 里 grep `prefetch` 为空） | `git show 2026.11.11:…XmlSqlGutterLineMarkerProvider.kt` |
| 11.12 是否新增该文件 | 是（`A`） | `git diff --name-status 2026.11.11 2026.11.12 -- src/main` |
| 11.12 是否新增该调用点 | 是，`XmlSqlGutterLineMarkerProvider.kt:52` `runCatching { prefetchMbgExample(tag) }`，`:69-77` 选择器 + 调用 | 当前 master 源码 |
| 发行 jar 方法是否存在 | 存在（`javap -p` 有 `prefetch` / `findCallSite` / `collectCriteria`） | `build/distributions/mybatis-plus-sql-visualizer-2026.11.12.zip` 内 jar 实测 |

**行号对不齐，正文引用一律用当前 master 行号**：发行 jar 的 `LineNumberTable` 最大行号到 **431**，而 tag `2026.11.12` 的源文件只有 **394** 行，栈里 `findCallSite(:417)` 同样越界。两种可能（内联映射偏移 / 构建树与 tag 不一致）不影响归因（方法名与调用链在仓内唯一），但**用户安装包来源需要 `.appinfo` + `MPV-Build` 对账**（§十一），并再提醒一次「发行物必须干净树构建」。

---

## 三、机制链（源码为准）

### 3.1 预取链（后台，持读锁）

```
editor 打开/恢复 mapper XML
└─ XmlSqlGutterLineMarkerProvider.getLineMarkerInfo          :40-62   EDT，逐 token
   ├─ 只对 select + parameterType 以 Example 结尾的语句       :69-77
   └─ MbgExampleCallResolver.prefetch                        EDT
      └─ executeOnPooledThread                               :157   BGT
         └─ resolve → ReadAction.compute（阻塞读锁）           :95
            └─ resolveInReadAction → findCallSite              :208 / :254
               └─ ReferencesSearch.search(method, scope)       :257   moduleWithDependentsScope
```

### 3.2 写锁升级链（EDT）

```
dispatchDocWriteOnEdt（EDT 分支）                            DataSourceSelectorResults.kt:2588-2589
└─ DocWriteContext.runWriteSafe                             DocWriteContext.kt:72
   ├─ isWriteAllowedNow() == true（当前在 write-allowed 上下文）
   └─ block() 就地执行
      └─ DataSourceSelector.docWriteOnce                     DataSourceSelector.kt:2085
         └─ WriteAction.run → runWriteActionBlocking
            └─ prepareWriteFromWriteIntentBlocking → upgradeWritePermit   ← 等所有读者退出
```

### 3.3 时间线（互锁）

| 时刻 | EDT | BGT |
|---|---|---|
| t0 | 用户点开 / 编辑器恢复，gutter 渲染 → 派发预取 | 预取启动，拿到读锁，开始检索 |
| t1 | 预览/执行链进入 document 写（write-intent 上下文，write-allowed） | 仍在检索（大工程：秒~分钟） |
| t2 | `WriteAction.run` 需要把写意图升级为写锁 → **等 BGT 放读锁** | `computeBlocking` 不可取消；索引期可能等 smart mode → **等索引/写权限** |
| t3+ | 每 5s 一份冻结 dump；窗口白屏 | 不动 |

### 3.4 与仓内既有约束的关系

`DataSourceSelector.kt:2075-2078` 的注释把「BGT 等 EDT，EDT 永不反向阻塞，超时自愈不互锁」写成了纪律，**这条纪律只覆盖 BGT→EDT 方向**；本次撞的是反方向——EDT 在写意图上下文里做**就地写**（`DocWriteContext.runWriteSafe` 的「允许就直执行」分支），于是变成 EDT 等 BGT。纪律要补一条对偶。

---

## 四、为什么这个工程必现（规模模型）

| 因素 | 该用户读数 | 对窗口的影响 |
|---|---|---|
| 表数量 | >1000 | 预览装配链里表/列元数据遍历（`DatabaseSchemaResolver` 等）线性放大 |
| 实体类 | 上万 | 引用检索命中面、`allScope` 类查找放大 |
| 检索作用域 | `moduleWithDependentsScope` | 与表/实体同模块或依赖它的模块，文件数上万 |
| 触发时机 | 打开/恢复 mapper XML 即触发，用户还没点图标 | 与「打开工程」重合，撞上索引与数据源加载 |
| 预取无上限 | 每会话可反复派发（只防并发，失败记忆只挡「已失败」的 key） | 一条卡住→整段窗口一直卡 |

---

## 五、修复方案

> 排序即建议实施顺序；S1/S2/S4 是冻结点，S3/S5/S6 是同类面与自愈。

### S1（核心）预取改「非阻塞读动作 + 智能模式」

`MbgExampleCallResolver.prefetch` 不再拿阻塞读锁跑索引检索，改用平台给的 `ReadAction.nonBlocking`：

```kotlin
fun prefetch(project: Project?, namespace: String?, statementId: String) {
    if (project == null || namespace.isNullOrBlank()) return
    if (ApplicationManager.getApplication().isUnitTestMode) return
    if (!project.isInitialized) return                                  // 项目未就绪
    if (DumbService.getInstance(project).isDumb) return                  // 索引期不争锁
    val key = "${project.locationHash}#$namespace#$statementId"
    if (key in prefetchFailed) return
    cache[key]?.let { if (isFresh(it)) return }

    ReadAction.nonBlocking<CachedResolution?> { resolveInReadAction(project, namespace, statementId) }
        .inSmartMode(project)          // 索引就绪后才跑；等待期不占读锁、可被取消
        .expireWith(project)
        .coalesceBy(this, key)         // 替代 inFlight 集合
        .submit(AppExecutorUtil.getAppExecutorService())
        .onSuccess { storeResolved(key, it) }   // 拆出「检索」与「落缓存」两步
        .onError { prefetchFailed.add(key) }
}
```

要点：
- `inSmartMode` 是本次的关键——它把「等索引」这件事交给平台在**不持读锁**的状态下等；
- `coalesceBy` 取代 `inFlight.isNotEmpty()`（原写法在一条卡住时会把同 key 与其它 key 一起卡死）；
- `resolveCached` 目前把「判定新鲜 + 执行 + 落缓存 + 计数」揉在一起（`:104-131`），要拆开，让非阻塞回调能在回调线程落缓存；
- 测试缝保留 `resetCacheForTest` / `cacheHits` / `searchRuns`，并新增 `isDumbProvider` / `isInitializedProvider` 注入缝。

### S2（核心）规模门与预算

三条闸，任一命中即不预取（只是少一次投机性加速，功能不受影响）：

1. **规模上限**：模块内调用方文件数（`FileTypeIndex` / `DumbService` 之外的轻量计数，或首查后缓存）超过阈值（初值建议 3000 文件 / 该 mapper 方法引用数 > 80）时，本会话对该 namespace 关闭预取。
2. **预算**：每项目每会话最多 N 次预取检索（初值 32）+ 单次预算（初值 1500 ms，超时即放弃并把该 key 记入失败记忆）。
3. **冷却**：项目打开后 X 秒（初值 10 s）内不预取，避开索引与数据源加载窗口。

另加 Registry 开关 `sqlvis.mbg.prefetch`（默认 `true`），支持「一键关掉预取但保留预览」，用于线上分诊与用户自救。

### S3（次要）预览侧 `resolve()` 同样去阻塞

`resolve()`（`:85-99`）目前也是 `ReadAction.compute`。它在预览装配线程同步跑，且已有 dumb 门（`XmlPreviewService.kt:121`），但**大工程热态仍可能秒级持锁**，撞上 EDT 写升级照样冻。方案：
- 装配侧改为可取消任务（`ProgressManager` 指示器 + 单次预算 800 ms），超时降级为「占位骨架 + 一处「可点击重试」提示」；
- 兜底优先级低于 S1/S4：对冻结点来说它是「第二条持锁路」，先证伪再动。

### S4（核心）EDT document 写不再依赖「写意图 → 写锁」升级

`DocWriteContext.runWriteSafe`（`:72`）的「当前可写 → 就地执行」分支在新平台会触发写锁升级等待。两个候选修法，取先落地的：

- **S4-a（推荐，改动小）**：`dispatchDocWriteOnEdt` 的 EDT 分支一律走 `invokeLaterWriteSafe` + 有界等待（与 BGT 分支同构），把「就地升级」彻底去掉；写体已幂等（`docWriteOnce` 内容相同不 setText），延后一拍语义安全（`DocWriteContext` 类头已有此结论）。
- **S4-b（改动大）**：写体改用 `WriteCommandAction.runWriteCommandAction`，让平台在命令上下文里安排写时机，而不是我们自己在写意图上下文里升级。

验收判据：同一操作在「有人持只读锁」的前提下不阻塞（可用测试缝制造一个长读动作来复现）。

### S5 横向审计（same-round，不攒批）

全仓 `ReadAction.compute` + 索引检索的 BGT 调用点清单与处置：

| 位置 | 现状 | 处置 |
|---|---|---|
| `ui/MbgExampleCallResolver.kt:95`（resolve） | 阻塞读锁 + 索引检索 | S1/S3 |
| `ui/XmlEwResolver.kt:42`（`ReferencesSearch.search(method).findAll()`） | 预览装配期，无 dumb 门 | 加 dumb 门 + nonBlocking（同族） |
| `psi/WrapperLocator.kt:933` | 用户触发的 hover/点击，作用域为局部 | 加预算即可 |
| `ui/highlight/SqlDialectMappingPrewarm.kt:78` | BGT 读锁 + Database 语法高亮初始化 | 加 dumb/初始化门（不属本次冻结，登记待办） |
| `onboarding/OnboardingAnchorTip.kt:92` | 读文本、无索引 | 低风险，仅登记 |

### S6 自愈与可见性

- 检索超时/放弃时写一条 info（`[m2] prefetch skip reason=… elapsedMs=…`），并**本会话熔断**该 namespace（已有 `prefetchFailed` 可复用）；
- 状态栏/工具窗不新增打扰项；日志即可（用户下次能给读数）；
- 冻结期间不做任何「后台重试」。

---

## 六、用户侧即时自救（**不用等本单实施**）

1. **关闭左侧图标即可绕开冻结点**：设置 → 本插件 → **「在代码左侧显示预览 SQL 图标」**（`settings.showGutterIcon`，默认开）取消勾选。代码上这会让 `XmlSqlGutterLineMarkerProvider.getLineMarkerInfo` 在 `:47` 早退，**预取完全不触发**（预览点击不受影响）。
2. 若仍需图标：回退到 **2026.11.11**（无预取链）。
3. 保留冻结报告目录（`threadDumps-freeze-*`），里面 `report.txt` / `.throwable` / `.appinfo` 对本单定案有用（§十一）。

> 说明：11.12 里没有「只关预取」的开关（S2 会补），所以当前只有「关整个图标」或「回退版本」两条零成本路径。

---

## 七、待验证前提（先证伪再动手）

| 编号 | 前提 | 证伪方式 |
|---|---|---|
| P-1 | 该 IDE 构建里 `ReadAction.compute` 等于 `computeBlocking`（不可取消） | 栈 1 已给出 `compute → computeBlocking` 帧；再以 `.throwable` 原始文本确认 |
| P-2 | 新版平台的写意图→写锁升级会**等待其它读者**，旧版 IDE 直接放行 | 同代码在 243/262 沙盒与用户新版 IDE 各跑一次「并发读 + 写」夹具，读数对比 |
| P-3 | 索引构建期 `ReferencesSearch` 会「同步等待」而不是快速抛 `IndexNotReadyException` | 仓内已有 11-12 冷启动实测注释（`XmlPreviewService.kt:118-120`）；本次以冻结期日志 `[open-perf] / [m2]` 复核 |
| P-4 | 预览装配链除 M2 外还有同量级扫描（`EntityClassFinder` 的 `allScope`、`DatabaseSchemaResolver` 的千表遍历） | 在 1000 表夹具上分段计时；若占比 <10% 则本单不收编 |
| P-5 | Linux 那台 IDE 版本高于 mac | `.appinfo` + `MPV-Build` + `idea.log` 首行版本号对账 |

---

## 八、Linux vs mac 判别（三步，不改代码）

1. **同版本对照**：在 Linux 那台装 2026.11.11，打开同一工程 → 若仍卡死，主因是平台/环境（P-2/P-5）；若不卡，版本相关性成立（本单主修有效）。
2. **同平台对照**：在 mac 上装与 Linux 同版本的 IDE + 2026.11.12，打开同一工程（或同规模工程）→ 若卡，差异不是 OS，而是 IDE 构建或工程规模。
3. **断链对照**：11.12 + 关闭「在代码左侧显示预览 SQL 图标」→ 若卡死消失，冻结点就是预取链（S1/S2 覆盖）。

三条都做完，才能把「Linux 特有」这个说法从结论里划掉。

---

## 九、决策点

| 编号 | 决策 | 建议 |
|---|---|---|
| D-1 | 预取默认值：保留（加门）还是默认关 | 保留下默认开 + S2 三道闸（预取对首开体验有实测收益：热态 170ms） |
| D-2 | 大工程阈值口径（模块文件数 / 引用数 / 检索耗时自测） | 用「首次检索耗时」自适应：单次 >800 ms 即本会话对该 namespace 熔断，比静态阈值稳 |
| D-3 | S4 取 a 还是 b | 先 a（一处派发点改为 nonModal + 有界等待），b 作为后续统一化 |
| D-4 | 是否给用户暴露「预取」开关 | 暴露（文案：让预览首次打开更快；关闭不影响预览） |
| D-5 | 是否同批修 `XmlEwResolver` 的 `findAll()` | 同批（同族无门检索，风险同型） |
| D-6 | 热修口径 | 若用户在 11.12 上无法自救，出 `2026.11.13` 热修（基号新批，内测先 `-beta.1`） |

---

## 十、验证与验收

**定向（实施子代理各自跑）**
- `MbgExampleCallCacheTest`：既有缓存/计数断言不变；
- 新增 `MbgExamplePrefetchGateTest`：注入 `isDumbProvider=true` / `isInitializedProvider=false` → `searchRuns == 0`；smart 态 → 一次检索；
- 新增 `DocWriteContext` 对偶用例：在「他已持读锁」的夹具下，`dispatchDocWriteOnEdt` 不再走就地升级（断言投递计数 + 不阻塞）。

**反向验证（必做）**
- 摘掉 S1 的 dumb 门 → `searchRuns` 断言必须变红；
- 摘掉 S4-a 的改派 → 阻塞夹具必须变红（贴两次读数）。

**真机验收（用户侧，给编号步骤）**
1. 1000+ 表工程、冷索引首开，打开/恢复 mapper XML，观察是否再弹 `not responding`；
2. 点 gutter 图标打开预览，判据：窗口 1s 内出现骨架、内容最终渲染（允许 M2 降级为占位）；
3. 持续 5 分钟滚动多个 mapper XML（制造多次预取），判据：无冻结报告目录产生；
4. 关掉「显示预览 SQL 图标」复测第 3 步，判据同上（验证 S2 开关语义）；
5. 抓 `idea.log` 中 `[m2]` / `[open-perf]` / `[T-WSC]` 三类行，确认预取有跳过原因、写不再走就地升级。

**not run 条款**：本次只出计划，未跑任何构建/测试；实施轮的报告须回答「跑门禁时工作树是否干净」。

---

## 十一、取证清单（用户侧，按价值排序）

1. 冻结报告目录里的 **`report.txt`**（32 KB，含 EDT 与被堵线程的完整栈）——最有价值；
2. 同目录 **`.throwable`**（5.8 KB）与 **`.message`**（139 B）；
3. **`.appinfo`**（559 B）+ `idea.log` 首行（IDE 构建号、插件 `MPV-Build`）；
4. 该工程规模读数：mapper XML 数量、实体类数量、模块数（用户已口述 1000 表/上万实体，需落到数字）；
5. 「关闭图标后是否仍冻结」的一次读数（§六 第 1 条）。

---

## 十二、风险与不做的事

- **不做**：为 Linux 加 `os.name` 分支；关闭 M2 功能本身；把冻结归因于「用户工程太大」了事（规模是放大器，冻结点是我方无门预取 + 就地写锁升级）。
- **风险**：S1 改成 `inSmartMode` 后，预取可能比之前更晚才跑（索引完成后），首开「点开即命中缓存」的收益对超大工程会变弱——这是有意的取舍：宁可慢一点，不可挂死。
- **风险**：S4-a 把写延后一拍，若有调用方依赖「写完立刻读回」，会读到旧内容。已核对：`dispatchDocWriteOnEdt` 各调用方不消费写结果，`docWriteOnce` 幂等（`DocWriteContext` 类头与 `:2586` 注释均有记载）；实施时逐调用点复核一次。
- **不做**：借本单重排 M2 的缓存键或作用域语义（那是 `T-PreviewOpenPerfM2` 的账）。

---

## 十三、实施记录

（待实施回填：分支/工作树、提交哈希、定向与反向读数、真机验收结论）