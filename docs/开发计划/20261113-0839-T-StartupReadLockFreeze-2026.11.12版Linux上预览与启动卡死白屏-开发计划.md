# T-StartupReadLockFreeze：2026.11.12 在 Linux 上打开即卡死 / 预览白屏——后台阻塞读锁与 EDT 写锁升级互锁

- 立项：2026-11-13 08:39（用户三批现场材料：两份 Java 线程栈 + 一张白屏窗口图 + 冻结报告目录截图）
- 状态：**已实施（2026-11-13）**：两车道合流于分支 `t-startup-readlock-freeze`（集成提交 `ef49211d5`，车道提交 `39d4b2706` 预取链 / `9f712eb78` 写路径），中央定向 17 类 **133/0/0/0**（含 4 个 plugin.xml 守卫），全量门禁改由 CI 承担（run `43350850354` 8/8 job success，读数见 §十五）；真机 5 步验收（§十）与 §14.3 复验仍待用户；用户侧即时自救见 §六
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

### 13.1 车道与范围（2026-11-13，车道 B：EDT document 写路径）

| 项 | 读数 |
|---|---|
| 分支 | `t-startup-freeze-docwrite`（基线 `077d4370f`） |
| 工作树 | `.worktrees/mpsv-startup-freeze-b`（并行登记 `name=T-StartupFreezeB`，主控登记） |
| 本轮实施 | §五 **S4-a**：`dispatchDocWriteOnEdt` 的 EDT 分支不再就地写 |
| 本轮未动 | S1/S2/S3/S5/S6（S1/S2 属车道 A）；`ui/MbgExampleCallResolver.kt`、`ui/XmlEwResolver.kt`、`plugin.xml`、bundle 属性（车道 A 拥有） |

### 13.2 改动（提交 `9f712eb78`，4 文件 +328/−14）

`execute/DocWriteContext.kt`
- 新增 `invokeLaterWriteSafeAndWait(expireWith, reason, waitBudgetMs, block): Boolean`：`invokeLater(..., ModalityState.nonModal())` + `CountDownLatch` 有界等待；非 EDT 超时返回 false 并记 WARN（`[M36] exec doc write nonModal dispatch timeout after …ms (EDT busy?)`，沿用既有 exec doc sync 的日志口径与检索习惯）；**已在 EDT 不自等**（投递后立即交还，写体下一次事件泵执行），否则「本拍等本拍排出去的事件」只能烧满预算。
- 新增测试缝 `deferEvenInUnitTestMode`（默认 `false`）：单测线程即 EDT 且调用线程被 latch 阻塞时无人泵队列，默认走与 `runWriteSafe` 等价的就地路径（既有用例语义不变）；置 `true` 走生产形态，供专项用例观测。
- 类头补**第三条纪律**：EDT 不得在写意图上下文里就地升级写锁——这是「BGT 等 EDT、EDT 永不反向阻塞」的对偶；并写明事故链（`runWithWritingAllowed` → `runIntendedWriteActionOnCurrentThread` → `prepareWriteFromWriteIntentBlocking` → `upgradeWritePermit`，2026-11-13 用户 Linux 冻结栈 2）。
- `runWriteSafe` 的就地语义**未改**（`SqlToJavaPanel` 三处、`SqlPreviewPanelSqlView` 的写安全检查调用方维持原状）。

`execute/DataSourceSelectorResults.kt`
- `dispatchDocWriteOnEdt` EDT 分支：`runWriteSafe { ok = docWriteOnce(...) }` → `invokeLaterWriteSafeAndWait(...) { docWriteOnce(...) }`；fire-and-forget（`waitBudgetMs<=0`）与 BGT 有界分支原样不动，写体仍过 `runWriteSafe` 闸（保留「判为不安全转二次投递」）。注释按实情改写，含边界说明（见 13.4-1）。

`execute/DataSourceSelector.kt`
- 仅 KDoc 同步（「EDT 调用方同步直执行」→「投递一拍后交还」+ 对偶纪律指向），无逻辑改动。

`src/test/.../execute/DocWriteDispatchSafetyTest.kt`（新增 4 例）
- 可写上下文：断言**投递计数 +1、磁盘/文档保持旧值、立即交还**，泵一拍后收敛；
- 不可写上下文：原语义不变（转 nonModal、返回 false、不就地写、不白等）；
- owner 已销毁：排队拍被丢弃、写体不执行、调用方只等预算（300ms，实测 <1.5s）；
- 新 API 的 BGT 正路：投递 → 测试 EDT 泵那一拍 → 有界等待拿到写体结果。

### 13.3 调用方复核（§十二 风险项的落实）

| 调用点 | 返回值用途 | 结论 |
|---|---|---|
| `DataSourceSelectorResults.kt:2337`（`projectExecFile` 内） | 只选日志行：true → `exec file updated via document+save`；false → 重进锁复核，仍不一致则 warn + **不裸写**。两分支都 `return f` | 不消费写结果 ✓ |
| `DataSourceSelector.kt:2160`（`syncExecVfContentIfNeeded` 返回给）`DataSourceSelectorResults.kt:1853/1883`（`createTempPsiFile`）、`:2407/2434`（`prewarmExecTempFileInner`） | 表达式语句，结果丢弃 | ✓ |
| `DataSourceSelectorConsole.kt:592`（`\G` 剥离回写控制台文档） | 结果丢弃；执行文本取 `gStrip.submitText`，不读文档 | ✓（余留排序说明见 13.4-2） |

生产线程面（本轮 grep 复核，不是从注释推的）：`createTempPsiFile` 的生产入口只有 `createTempPsiFileAsync`（EDT 侧 `executeOnPooledThread`，`:1742`；BGT 侧就地），预热链在 BGT；`syncExecVfContentIfNeeded` 三个生产调用点同属这两条 BGT 链。**因此 EDT 分支在生产上只剩 `\G` 回写一个真实调用方**，其余是防御分支（`createTempPsiFile` 内「EDT 分支本券后生产不可达」自述）与测试。

### 13.4 与计划的偏差与诚实边界（实施轮实测）

1. **S4-a 只去掉「就地升级」形态，不单独解除互锁。** 冷启动实测与仓内平台源码笔记相互印证：`TransactionGuardImpl.wrapLaterInvocation` 对 nonModal 投递拍套 `runWithWritingAllowed`，所以投递出去的那一拍照样进 `runIntendedWriteActionOnCurrentThread` → 写意图读锁 → `upgradeWritePermit` 等读者。本轮换来的是「调用方不再被写升级阻塞 + 等待有界 + 写不再夹在调用方活动里」；读者长持不放时那一拍照样会等，冻结的彻底解除仍依赖 S1/S3（后台不阻塞持读锁）。**真机验收不得把「不再冻结」单独记在本项账上。**
2. **`\G` 回写的排序**：延后一拍后控制台文档改写发生在下一拍；执行内容不受影响（取 `submitText`）。正常时序下改写先于执行结果回显（同为 EDT FIFO，先排队者先执行），残余风险低但存在。
3. **测试缝的代价**：`deferEvenInUnitTestMode` 默认为 false，意味着默认单测不覆盖生产分支（生产形态由 `DocWriteDispatchSafetyTest` 打开该缝覆盖）。这是「既有 54 例保持绿」的前提：`BasePlatformTestCase` 把用例体跑在 EDT 上，生产形态在单测里必然自等烧满预算，`ExecDocSyncTest`/`ExecDocSync2Test` 又依赖「EDT 直调当场落盘」。

### 13.5 定向测试读数（不跑全量）

```
./gradlew test -Pkotlin.compiler.execution.strategy=in-process \
  --tests "cn.song.mybatisplus.visualizer.execute.DocWriteDispatchSafetyTest" \
  --tests "cn.song.mybatisplus.visualizer.execute.DocWriteContextTest" \
  --tests "cn.song.mybatisplus.visualizer.execute.ExecDocSyncTest" \
  --tests "cn.song.mybatisplus.visualizer.execute.ExecDocSync2Test" \
  --tests "cn.song.mybatisplus.visualizer.execute.ExecVfDivergenceProbeTest" \
  --tests "cn.song.mybatisplus.visualizer.execute.ExecDocPrewarmDisposeGuardTest" \
  --tests "cn.song.mybatisplus.visualizer.convention.BannedApiScanRuleTest"
```

读数（改动后）：**58 tests / 0 skipped / 0 failures / 0 errors，BUILD SUCCESSFUL（1m08s）**；把超时日志口径对齐 `[M36]`（只在超时分支，无断言覆盖）后按同一命令复跑终态：**58 / 0 / 0 / 0，BUILD SUCCESSFUL（1m04s）**，即上表读数对应提交 `9f712eb78` 的最终形态。
分项：`BannedApiScanRule` 12、`DocWriteContext` 9、`DocWriteDispatchSafety` 4、`ExecDocPrewarmDisposeGuard` 4、`ExecDocSync2` 8、`ExecDocSync` 14、`ExecVfDivergenceProbe` 7。
口径说明：既有 54 例的「改动前基线」未单独采集（同一命令在本轮改动落地前启动，编译窗口与编辑重叠，读数不代表改动前状态）；「既有用例保持绿」的判据取上面这一次同批读数（54 例与新 4 例同批全绿）。
新增用例耗时：可写上下文延后 0.54s、owner 已销毁 0.49s、BGT 正路 0.22s、不可写路径 6.65s（含类 setup）。

### 13.6 反向验证（两次读数 + 还原）

夹具：把 EDT 分支改回就地写（`runWriteSafe { ok = docWriteOnce(...) }`），测试与生产其余部分不动。

| 次 | 读数 |
|---|---|
| 第 1 次（改回就地写） | `testEdtBranchDefersInsteadOfWritingInlineWhenContextAllowed FAILED` @ `DocWriteDispatchSafetyTest.kt:82`（「必须投递一拍（不得就地写）」）→ 4 tests / 1 failed，BUILD FAILED（45s） |
| 第 2 次（同一改动独立复跑） | 同一用例同一行 FAILED → 4 tests / 1 failed，BUILD FAILED（47s） |
| 第 3 次（还原后） | 58 / 0 / 0 / 0，BUILD SUCCESSFUL |

判别力来源：改回就地执行后可写上下文当场写盘、`deferredCount` 不动，三条断言（投递计数、磁盘、文档）都会红；第 82 行是第一个命中的断言。

### 13.7 not run 清单

- 未跑全量（`testShards` / `testFast` 全桶）、未跑 `verifyGate` / `verifyPlugin` / `verifySmoke` / `buildPlugin`、未 `clean`、未 `--stop`、未跑 Docker 腿；
- 未在 runIde 沙盒、也未在用户 Linux / mac 真机验证（§十 真机验收由用户侧执行）；
- 未对 `MbgExampleCallResolver` / `XmlEwResolver` / gutter 链做任何验证（车道 A 范围）；
- 收口跑定向时工作树状态：仅有本轮四个改动文件，反向验证探针已还原，`git status` 无残留。

### 13.8 交接

- `CHANGELOG.md` 的 `[Unreleased]` 未由本车道回填（车道文件范围不含该文件）：T-StartupReadLockFreeze 的用户可见条目应在 S1/S2 合流后按能力面统一落笔。
- S4-b（`WriteCommandAction.runWriteCommandAction` 统一化）仍按 §九 D-3 留作后续。本轮实测新增一条支撑：投递拍也会被套写意图包装，只有「不持写意图的写入口」才可能避开升级等待，S4-b 的评估应以此为前提。
- 真机验收（§十）判据里与本项相关的只有第 1、5 条的一半：日志应能看到 `[T-WSC] write deferred to nonModal + bounded wait … (S4-a)` 与 `dispatch accepted without waiting (caller is EDT, S4-a)`；「不再弹 not responding」是 S1/S2 与本项合力的结果，不能单记在本项。
### 13.9 车道 A（预取链：S1 / S2 / S3 / S5-XmlEw / S6）— 2026-11-13

- 工作树：`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\.worktrees\mpsv-startup-freeze-a`
- 分支：`t-startup-freeze-prefetch`（基点 master `077d4370f`）
- 提交：`39d4b2706`（`git show --stat` 只列 4 个文件：`src/main/kotlin/…/ui/MbgExampleCallResolver.kt`、
  `src/main/kotlin/…/ui/XmlEwResolver.kt`、`src/main/resources/META-INF/plugin.xml`、
  `src/test/kotlin/…/ui/MbgExamplePrefetchGateTest.kt`）

**落地内容**

| 项 | 落地形态 |
|---|---|
| S1 预取去阻塞 | `prefetch` 改 `ReadAction.nonBlocking { … }.inSmartMode(project).expireWith(project).coalesceBy(this, key).submit(AppExecutorUtil.getAppExecutorService())`；删掉「阻塞读锁 + `executeOnPooledThread`」与全局 `inFlight` 单飞（`coalesceBy` 只合并同 key，卡一条不再拖死其它 key）。`resolveCached` 拆成 `cachedIfFresh` / `searchOnce` / `storeResolved`，非阻塞回调复用后两段；`resolveCached` 对外语义与既有 3 条断言不变 |
| S2 预算 | 每项目每会话 32 次检索；单次 1500 ms 硬预算（到点 `promise.cancel()`，由 nonBlocking 的进度检查传播进 `ReferencesSearch`，读锁随之释放）并记失败记忆 |
| S2 三道闸 | `Registry` 开关 → 项目未初始化 → dumb → 冷却（10 s）→ namespace 规模熔断 → 失败记忆 → 缓存新鲜 → 预算；任一命中只打 `[m2] prefetch skip reason=…` |
| S2 规模门 | 取 §九 D-2 的「首次检索耗时自适应」：单次真实检索耗时 ≥800 ms 即本会话对该 namespace 熔断（`[m2] prefetch circuit-open namespace=…`）。**不用模块文件数计数**：文件计数要额外查 `FileIndex`/`FileTypeIndex`，那本身也是索引操作（索引期同样会等）；耗时为已付成本、零额外查询，且随工程规模自适应 |
| S2 开关 | plugin.xml `<registryKey key="sqlvis.mbg.prefetch" defaultValue="true" description="…"/>`（主 `<extensions defaultExtensionNs="com.intellij">` 块内）；代码经 `prefetchEnabledProvider` 读取（`Registry.is(key, true)`，`is` 为 Kotlin 关键字须反引号）。`com.intellij.registryKey` EP 在 243 `app-client.jar!/META-INF/IdeCore.xml` 实证存在且 `dynamic="true"`，**未降级为 sysprop** |
| S3 预览解析 | 内部改 `ReadAction.nonBlocking { … }.inSmartMode(project).expireWith(project).submit(…)` + `promise.blockingGet(800 ms)`；超时 `cancel()` 并返回 null（`XmlPreviewService` 已按「解析不到」降级占位骨架）。**未用 `executeSynchronously`**，理由见下 |
| S5 同族面（仅本车道） | `XmlEwResolver` 的默认 `callFinder` 加 `DumbService.isDumb` 门（dumb 时返回空并打 `[m2] resolve skip reason=dumb-mode stage=ew-refs`，与 `MapperNavigationTargets` 同口径） |
| S6 可见性 | 跳过/放弃路径统一 `[m2] prefetch / resolve skip reason=… elapsedMs=… key=…`；无新增通知/状态栏项 |

**与计划文本的三处偏差（各有实证理由）**

1. **S3 未用 `executeSynchronously()`**。243 字节码实证（`javap -c NonBlockingReadActionImpl`）：
   ① `executeSynchronously` 遇到 `coalesceBy`/`finishOnUiThread` 直接抛 `IllegalStateException`；
   ② 它**没有时间预算**，`inSmartMode` 约束未满足时走 `invokeLater` + `awaitWithCheckCanceled`
   在**调用线程上等**——正是本单要消灭的「调用方持等」形态；③ 「超预算返回 null」在该 API 上无法实现。
   落地改为「提交 + 有界 `blockingGet(800 ms)` + 超时 cancel」：调用方最多付出 800 ms，读锁由平台线程持有且可取消。
   额外分支：调用方**已持读锁**（MCP `renderXmlSnippet` 的 `ReadAction.compute` 内）时就地嵌套读动作，省一次锁往返。
2. **规模门取耗时自适应而非模块文件数**（理由见上表；即 §九 D-2 的建议口径）。
3. **冷却起点用「本插件第一次看到该项目的时间」代理「项目打开时间」**：243 的 `Project` 无打开时间 API
   （`javap` 核过 `com.intellij.openapi.project.Project` 全部公开方法）。副作用＝项目打开后 10 s 内 gutter
   首屏不预取，正好落在要避开的数据源/索引加载窗口。

**定向读数（`./gradlew -Pkotlin.compiler.execution.strategy=in-process test --tests …`，工作树仅本轮 4 文件改动）**

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `ui.MbgExampleCallCacheTest`（既有） | 3 | 0 | 0 | 0 |
| `ui.MbgExamplePrefetchGateTest`（新增，a~f 六条闸） | 6 | 0 | 0 | 0 |
| `ui.XmlEwResolverTest`（S5 触碰文件回归） | 6 | 0 | 0 | 0 |
| `config.PluginXmlStructureGuardTest` | 23 | 0 | 0 | 0 |
| `config.PluginXmlExtensionPointResolutionTest` | 4 | 0 | 0 | 0 |
| `convention.DynamicEpComplianceGuardTest` | 3 | 0 | 0 | 0 |
| `convention.GuardSelfTestCoverageMetaGuardTest` | 8 | 0 | 0 | 0 |

新增用例与断言对应：dumb → `searchRuns==0`；未初始化 → 0；冷却期内（t=0 与 t=COOLDOWN−1 两拍）→ 0；
Registry 关闭 → 0；smart+就绪+开关开 → `searchRuns==1` 且同 key 再来一次为 `cache-fresh`；预算耗尽 → 停在 32。

**反向验证（摘除式，LESSONS L50：先自证探针落地）**

| 步骤 | 读数 |
|---|---|
| 摘除 | 把 `if (dumb) return PrefetchDecision(false, "dumb-mode")` 改为 `// RED PROBE…` 注释行； `grep -cE '^[[:space:]]*if \(dumb\) return PrefetchDecision'` ＝ **0**（未注释语句归零，探针确实生效） |
| 红 | `MbgExamplePrefetchGateTest`：tests=6 **failures=1** errors=0 skipped=0；失败例「dumb态不预取」，`expected:<[dumb-mode]> but was:<[cooldown]>`（去掉 dumb 门后冷却门接管，说明该断言认的正是 dumb 门） |
| 还原 | 恢复该行：同 grep ＝ **1**，`grep -c 'RED PROBE'` ＝ **0**；重跑 tests=6 failures=0 errors=0 skipped=0 |

**not run（如实标注）**

- 全量 `testShards`、`verifyGate`/`verifyPlugin`/`verifySmoke`/`buildPlugin`、`runIde` 沙盒走查；
- §十「真机验收」5 步（用户侧 1000+ 表工程，冷索引首开 / 预览 1 s 内出骨架 / 连滚 5 分钟无冻结报告 /
  Registry 开关复测 / `[m2]` 日志抽查）；
- **生产调度段无平台级用例**：纯 JUnit 无 `Project`，`ReadAction.nonBlocking` + 预算计时器这一段只有编译期
  与 API 面核实（方法签名逐个 `javap` 对过 243 发行包），决策/计数/落盘由 `prefetchForTest` 覆盖；
- `WrapperLocator`、`SqlDialectMappingPrewarm`、`OnboardingAnchorTip` 未改（车道边界，计划 §五 S5 表登记项
  由主控另行安排）。

**移交项（主控收口）**

- `CHANGELOG.md` 的 `[Unreleased]` 未回填（车道文件边界）：建议按 `39d4b2706` 记一条「打开大工程不再因预取卡死；
  新增 Registry 开关 `sqlvis.mbg.prefetch`（默认开，只关预取）」。开关最终形态＝**plugin.xml `registryKey`**（非 sysprop）；
  S3＝**nonBlocking + 800 ms 有界等待**（非 `executeSynchronously`）。
- 用户自救口径更新：§六 第 1 条（关左侧图标）之外，新增「Registry 搜 `sqlvis.mbg.prefetch` 取消勾选」＝
  只关预取、保留预览。

---

## 十四、追加现场（2026-11-13 10:0x）：关图标后冻结消失，但「带 Example_Where_Clause 的预览」仍 30s

### 14.1 用户读数（三张图）

| 项 | 读数 |
|---|---|
| 关「在代码左侧显示预览 SQL 图标」后 | 「没那么卡了」「取消勾选后没有那个问题了」——**印证 §三 的冻结点判定：预取链是主因** |
| 点开带 `<include refid="Example_Where_Clause"/>` 的语句（图 1 的 ①） | 约 **30s** 才出结果（面板先显示「正在准备预览…」） |
| 点开不带该 include 的语句（图 1 的 ② `selectByPrimaryKey`） | 很快 |
| 30s 后 | 该 XML 编辑器顶部弹「Plugin 'SQL Visualizer' might be slowing things down」（IDEA 的 EDT 慢操作/插件归因横幅） |
| 语料规模 | 该 mapper XML 共 600+ 行，`<sql id="Example_Where_Clause">` 就在同一文件（图 3：foreach/trim/choose 四分支 + 嵌套 foreach） |

### 14.2 判读（待日志证伪）

两条语句的唯一差别就是 `parameterType` 是 MBG Example ⇒ 只有这两段会跑（`XmlPreviewService.kt:113-128`）：

| 段 | 代码 | 本次修复后的形态 |
|---|---|---|
| `profile` | `resolveExampleProfile(project, statement.parameterType, parsed.namespace)` | **未加预算**（同 11.12） |
| `m2` | `MbgExampleCallResolver.resolve(...)` → `ReferencesSearch` | 已改为 `submit + blockingGet(800ms) + 超时 cancel → null`（`MbgExampleCallResolver.kt:87/194-209`），左栏退占位骨架 |

`[open-perf] total=… parse+ctx=… profile=… m2=… model=…` 会把 30s 钉到具体一段（`XmlPreviewService.kt:295-305`）。
在 11.12 里 m2 无预算、无 dumb 兜底，大工程（>1000 表 / 上万实体、253+ 引用）30s 与 §二 那条检索同源；
IDEA 的「拖慢」横幅则说明 EDT 也被牵连（写锁升级等读锁，同 §三 3.3）。

### 14.3 复验判据（装本单修复包后）

1. 同一条带 `Example_Where_Clause` 的语句点开，判据：`[open-perf]` 的 `m2≤800` 或 `m2=0`（dumb 期跳过），`total` 从 30s 量级降到秒级；左栏允许先出占位骨架（`{{criterion.condition}}`）。
2. 不再出现「Plugin 'SQL Visualizer' might be slowing things down」。
3. 若 total 仍 30s 且 **profile 段占大头** ⇒ 另立 S7（`resolveExampleProfile` 有界化），不并入本单；届时按 `[open-perf]` 的 profile 读数决定阈值与降级形态（无 profile 时左栏回退为无预设骨架）。

### 14.4 需要的读数

- 那 30s 窗口的 `[open-perf]` 行（通常一条，`always=true` 对 Example 档恒记）；
- 若有 `[m2]` 行（`cache hit` / `unresolved` / `prefetch skip reason=…`）一并给。

### 14.5 追加反馈的第二条（同日稍后）：关图标后只剩「带 Example 的语句慢」

同一用户随后确认：「取消勾选后没有那个问题了」——即 §六 第 1 条的自我缓解有效，冻结点确为预取链。剩余现象即 §14.1 的 30s 档，按 §14.3 复验。

### 14.6 沙盒日志定位（2026-11-13 12:52，master 沙盒首开）：800ms 上限被绕过

用户用 master 起沙盒复验「首次还是很慢」，`idea.log` 给出决定性读数（`.intellijPlatform/sandbox/…/log/idea.1.log:47592`）：

```
12:52:49 [open-perf] total=3275ms parse+ctx=38 profile=1 m2=3219 model=17   ← 首次（冷）
12:52:49 [open-perf] total=8ms    parse+ctx=8  profile=0 m2=0    model=0    ← 第二次（缓存命中）
```

`m2=3219ms` 说明 S3 的 800ms 预算在真机上没有生效。根因不在预算值，而在**预算放错了层**：

- 预览主链 `SqlPreviewService.previewAsync`（`SqlPreviewService.kt:2135`）把整段装配包在 `ReadAction.compute` 里；
- 于是 `MbgExampleCallResolver.resolve()` 里 `ApplicationManager.getApplication().isReadAccessAllowed` 恒为真，走的是「已持读锁 → 就地嵌套、**没有等待预算**」那条分支；
- `blockingGet(RESOLVE_BUDGET_MS)` 只存在于非持锁分支，冷态一次 `ReferencesSearch` 就把它吃成 3.2s（用户 2500 表工程上是 30s 量级）。

同一段代码里还有一处翻倍：`findCallSite` 在「没有可用调用点」的分支里**再跑一次** `ReferencesSearch.search(...).findAll()` 只为数 `totalRefs`——而「没有调用点」正是高频失败路径，等于把最贵的一次检索做两遍。

### 14.7 修复（同日，提交见 §十五 追加行）

1. **预算挪进检索本身**：`resolve()` 统一算出 `deadlineAt = startedMs + RESOLVE_BUDGET_MS`，两条分支都把 `deadlineAt` 传进 `resolveInReadAction`；`resolveInReadAction` 在逐方法循环、`findCallSite` 在逐引用循环里自查 `isDeadlineHit(deadlineAt)`，到点立即停并按「解析不到」返回 null（调用方已降级为占位骨架）。裁决理由：等待式预算在持锁上下文里天生走不到，取消 API 又不能穿透已持有的阻塞读动作；只有把上限落在检索循环里，两条路才受同一个约束。
2. **删掉失败路径的二次全量检索**：`refsSeen` 已是扫描条数（提前终止时 `site` 非空、不走该分支），`totalRefs` 冗余。
3. 到点截断与「解析不到」在日志里可区分：`[m2] resolve skip reason=deadline elapsedMs=…`。

**读数**：`MbgExampleResolveDeadlineTest`（新增 2 例）+ `MbgExamplePrefetchGateTest` + `MbgExampleCallCacheTest` + `MbgExampleRootMatchTest` + `XmlEwResolverTest` → **20/0/0/0**。
**反向验证（L50）**：把 `findCallSite` 里的自查改成 `isDeadlineHit(Long.MAX_VALUE)` → 新用例 1 红（`MbgExampleResolveDeadlineTest.kt:52`，"实际命中" 断言失败）；还原后 20/0/0/0。
**同轮修掉一个假绿**：该守卫首版按「全文件出现 `isDeadlineHit(deadlineAt)` ≥2 次」判定，摘除探针后仍为 2（测试缝 `isDeadlineHitForTest` 自己算一次）——反向验证当场暴露，改为只解析 `findCallSite` 函数体。

**残留待验**：修后单次上限 800ms 是否仍被用户感知为「慢」（真机 2500 表工程首开一次检索的实际耗时分布），以及 `profile` 段（`resolveExampleProfile`）在超大工程上的耗时——两者都由下一次 `[open-perf]` 行裁决；若 `profile` 占大头，按 §14.3 另立 S7。

### 14.8 二次返工（同日 13:14 日志）：截止判定生效但仍慢 —— 改为「首开不检索」

用户复验仍报慢，新日志给出定性读数：

```
13:13:48 [open-perf] total=1427ms  m2=1362
13:14:05 [open-perf] total=2482ms  m2=2469
[m2] findCallSite empty method=selectByExample refsSeen=13 deadlineHit=true
[m2] resolve skip reason=deadline elapsedMs=2469
```

**判读**：截止判定确实在跑（`deadlineHit=true`、`reason=deadline`），但耗时藏在 `ReferencesSearch` **引擎内部**——`refsSeen` 只有 13 条、单条投影就要一两百毫秒，逐条自查只能落在两次 `next()` 之间，管不住单次内部开销。结论：**超大工程上这条路不该同步走**，调阈值没有意义。

**改法（S7，实施同批）**：
- `MbgExampleCallResolver` 新增 `resolveCachedOnly`（只读缓存）、`warmForPreview`（后台补齐，预算 10s，绕过冷却/失败记忆/预取预算——这是「用户已在等结果」的显式请求）、`whenResolved`（一次性就绪回调，`storeResolved`/确认无结果/失败三处都触发）。
- `XmlPreviewService`：首开只读缓存，未命中即 `warmForPreview` 并把 key 透传进 `PreviewResult.m2AwaitKey`（附 `retryWhenIndexReady` 重算闭包）；同步 `resolve()` 不再出现在预览链（由源码守卫钉住）。
- `SqlPreviewPanelResultView`：`m2AwaitKey != null` 时挂 `whenResolved` 等落地再重算刷新；**不能**复用 `runWhenSmart`（smart 已就绪会立即触发、重算出同一份骨架，形成空转）。
- 设计取舍：首开拿不到 Criterion 时左栏退化到占位骨架（`{{criterion.condition}}`），落地后自动补齐——用「一次自动刷新」换掉「首开最长 30s 的阻塞」。

**读数**：M2 族 40/0/0/0（`MbgExample*` + `XmlEwResolver` + `MbgExamplePreview` 19 例）；面板族 17/0/0/0（`SqlPreviewPanelBindState` 11 / `EmptyHint` 1 / `PreparingPlaceholder` 2 / `ResolveDeadline` 3）。
**踩坑**：新增 `PreviewResult` 字段后，`SqlPreviewPanelBindStateTest` 全红 `NoSuchMethodError`——增量编译没重编旧测试类（main 13:23 新、test 11:40 旧）。处置：`compileTestKotlin --rerun-tasks` 后复绿；同类「陈旧产物劫持」按既有纪律先查类时间戳，不要先怀疑代码。

### 14.9 非 GUI 性能验收（用户 2026-11-13 要求：先自测再交 GUI）

用户明确「你应该先自己尝试通过测试用例 非 gui 的方式进行验证性能问题 验证没问题我再通过 gui 验收」。据此新增 `MbgExampleAsyncOpenTest`（`BasePlatformTestCase`），在没有真实索引规模的前提下**模拟「一定未命中」**，把首开行为钉死：

| 用例 | 断言 | 结果 |
|---|---|---|
| `test首开不等待检索且带就绪key` | ① `searchRuns` 不变（未触发同步检索）② warm 恰好发起 1 次 ③ 结果带 `m2AwaitKey` ④ 首开 `<1500ms` 返回 ⑤ 仍有可读 SQL（骨架） | 通过 |
| `test缓存命中时不转后台也不带就绪key` | 命中＝warm 0 次、`m2AwaitKey` 为 null（不空转） | 通过 |

测试缝：`cachedOnlyOverrideForTest`（冷缓存判定）与 `warmOverrideForTest`（warm 的等待 key），生产恒为 null。

**反向验证（L50）**：把 `XmlPreviewService` 的 `resolveCachedOnly(...)` 换回同步 `resolve(...)` → **3 红**（两行为用例 + `预览首开不得同步调用 M2 检索` 源码守卫）；还原后 5/0/0/0。

**边界**：这里证的是「首开不再等检索」这一机制，不证真实工程上的绝对耗时——后者仍需用户在 GUI/真机复验一次（判据见 §14.3 的日志行）。

---

## 十五、门禁读数（§13.10）

### 15.1 中央定向（合流态，本机）

命令：`./gradlew test -Pkotlin.compiler.execution.strategy=in-process` + 17 个 `--tests`（预取/缓存/写路径/exec 同步/守卫族/plugin.xml）。
读数：**tests=133, failures=0, errors=0, skipped=0**（17 类；含 `MbgExampleCallResolver` 3+6+3、`XmlEwResolver` 6、`DocWriteDispatchSafety` 4、`DocWriteContext` 9、`ExecDocSync` 14+8、`ExecDocPrewarm`/`AutosaveHost`/`Gate`/`VfDivergence`、`BannedApiScanRule` 12、`BannedApiSourceScan` 15、四守卫 23/4/3/8）。

### 15.2 全量门禁：改由 CI 承担（用户 2026-11-13 指示「直接合并主分支跑 CI，不要在本地跑」）

| 轮次 | 提交 | 结果 | 处置 |
|---|---|---|---|
| run `43348966367` | `164c499bc` | **5 个 test 分片全 success，`fast (2)` 1 红**：`BundleParityGuardTest.pluginXml与optionalXml引用的key必须存在于bundle`（`:140`） | 新增的 `<registryKey key="sqlvis.mbg.prefetch" description="…"/>` 被守卫当成 bundle 引用（key 是注册表标识符、description 是 Registry UI 纯英文串，平台不做 `%key%` 替换）。修法＝扫描前整元素剔除 `registryKey` 族，提交 `2f56f8eb7` |
| run `43350850354` | `2f56f8eb7` | **8/8 job success**（test 1~5 + fast 1~2 + nightly-nudge），约 10.5 分钟 | — |

修守卫后本机只跑了受影响的两类（`BundleParityGuardTest` 5/0/0/0、`GuardSelfTestCoverageMetaGuardTest` 8/0/0/0），其余交给 CI。

### 15.3 not run

- `verifyPlugin` / `buildPlugin` / `verifySmoke`（按「大改动或发版前」判据，本单未触发；`plugin.xml` 只加了扩展未改 EP 声明与 since-build）；
- 真机 5 步（§十）与 §14.3 的复验：待用户装包后执行；
- 生产调度段（nonBlocking + 预算计时器 + `inSmartMode`）没有平台级用例，只做了 243 发行包的 API 面 javap 核实（见 §13.9）。

---

## 十六、第三轮现场（2026-11-13 13:47 沙盒）：装配已 64ms，但 `request→dialog` 仍 19.8s

### 16.1 读数（同一份 `idea.log`）

```
13:47:20,413 [open-perf] total=64ms  parse+ctx=41 profile=2 m2=2 model=19        ← M2 已转后台，装配 64ms
13:47:21,898 [T-PreviewFast] request→dialog: 19771ms                              ← 弹窗等了 19.8 秒
19824 × "DatabaseSchemaResolver scan: table=…, bail=DasModel 遍历 0 张表（内省未完成）"
      覆盖 830 张表 × 15 个数据源，13:47:11 → 13:47:56（约 45s），同一表重复 2~3 轮
```

### 16.2 判读

- M2 那条链已经修好（§14.8 的 `m2 deferred` + 64ms 装配），**剩下的慢是另一条链**：schema 全表扫描。
- 19824 次扫描**全部**以 `DasModel 遍历 0 张表（内省未完成）` 收尾——数据源模型还没加载，每一次都必然问不出结果；这是纯白付。
- 量级：830 表 × 15 源 = 12450 次/轮，实际约 2 轮 → 45s，与 `request→dialog` 的 19.8s 窗口重叠，且这部分如果落在 EDT 上会直接堵住弹窗。

### 16.3 修法（S8，已实施）

1. **模型冷窗口**（`DatabaseSchemaResolver`）：`tryCompute` 在逐源遍历**前**自查 `modelsColdNow(project)`；一次「所有源都 bail 且无列无注释」即 `stampModelsCold`，窗口（`modelsColdWindowMs`，初值 20s）内直接返回 `scanBailed`，窗口外自动重试——与 `bailAfterTraversal` 的「查不了不记账、下次重试」自愈语义同向。
2. **预热批次闸**：`prefetchTables` 在模型冷时整批跳过；一次预热超过 `BIG_BATCH_WARN=50` 张表时打一条 `prewarm big batch` 并附 8 帧调用栈（诊断用，下次真机直接读出「谁在整工程扫表」），且**禁止在 EDT 上跑**（自动改派业务线程）。
3. 这三条都不改功能性结果：模型冷时本来也拿不到列，只是不再把「拿不到」重复 12450 次。

### 16.4 读数与反向验证

- 新增 `DatabaseSchemaResolverModelsColdTest` 5 例（窗口内/窗口外/未观测/窗口关闭/时钟回拨 + 接线钉死「tryCompute 必须在遍历前自查」）→ **5/0/0/0**；连同 `SchemaPrewarmWindowTest` / `DatabaseSchemaResolverBailTest` / `NegativeCacheTest` 全绿。
- 反向验证（L50）：把 `tryCompute` 的自查改成 `if (false)` → 1 红（接线用例 `扫描入口必须在遍历前自查模型冷`）；还原后复绿。
- 前一轮 CI（`e16552fb0`，run `43374030679`）8/8 job success。

### 16.5 仍待确认

「谁在整工程扫表」还没从静态代码里定位到（插件内公开入口 `prefetchTables` 的调用方都只传 1~8 张表）；本次用 `prewarm big batch` 的调用栈把它留给下一次真机日志。若下一轮日志显示调用方是实体解析/补全链，再按批量化或 dumb/初始化门进一步收敛。

---

## 十七、第四轮现场（2026-11-13 14:31 沙盒）：扫表已压掉，但出现自激刷新

### 17.1 读数

| 项 | 读数 | 判读 |
|---|---|---|
| 装配 | `[open-perf] total=6ms`（多轮） | M2 转后台已生效 |
| schema 扫描 | 14:31 之后仅 **30 条**（模型冷 stamp 之后）；13:47 那场 19854 条不再出现 | §十六 的模型冷窗口生效 |
| `m2 async ready applied` | 3 秒内 **10 次**（每 ~600ms 一轮） | **本轮新问题：自激刷新** |

### 17.2 判读（自激链）

```
首开 → cachedOnly miss → warmForPreview 启动（key=K）
     → warm 补不到结果 → fireReady(K) → 面板重算 showResult
     → 重算仍 miss（本来就没结果）→ warmForPreview 再启动（K）
     → 又 fireReady(K) → … 每 600ms 一轮
```

每轮都走完整 `showResult`（控制台准备 + exec 文件写 + 面板重排），用户感知就是「一直不秒开」。

### 17.3 修法（S9）

- `warmAttempted`：同一 key 同一缓存代只允许启动一次补齐；`shouldStartWarm(cacheFresh, alreadyAttempted)` 纯判定。
  缓存失效（`cachedIfFresh` 发现依赖文件已改）时移除资格，保证改文件后仍能补；`warm` 调度失败也退回资格。
- 仍然在「补不到结果」时 `fireReady`（否则面板会等一个永不到来的回调），但第二轮不再转后台 ⇒ 最多多一次重算即收敛，不自激。
- 判定放在测试缝之前，使「第二次打开该不该再补」在单测里可观测。

### 17.4 读数与反向验证

- `MbgExampleAsyncOpenTest` 新增 `test补不到结果时不得自激重试`（首开 warm 1 次、二开不再转后台）与 `test补齐资格判定`；连同既有 2 例 → 4/0/0/0。
- 反向验证：把资格判定改成 `if (false)` → `test补不到结果时不得自激重试` 1 红（`MbgExampleAsyncOpenTest.kt:71`），还原后复绿。
- 前一阶段 CI（`c20101ff`）8/8 job success。

---

## 十八、第五轮现场（2026-11-13 14:48 沙盒）：11.9s 在「装配之前」，且大半是 IDE 冷启动

### 18.1 时间线（同一次点击，逐行对齐）

| 时刻 | 行 | 判读 |
|---|---|---|
| 14:47:52.27 | `request`（点击） | — |
| 14:47:52.41 | `模型冷` stamp | schema 扫描已被压住 |
| 14:47:55.7~57.3 | 830 × `prewarm skipped: models cold, tables=1` | 预热逐表投递仍在刷（已不扫表，只记账） |
| 14:47:57.1 | `JavaBuilder … Compiling` | **IDE 正在建索引/编译** |
| 14:48:03.3 | `[T-EmbeddedIdentify]`（compute 内首次落日志） | 点击到真正开算 = **11s** |
| 14:48:03.52 | `[open-perf] total=53ms` | 插件装配 53ms |
| 14:48:04.19 | `request→dialog: 11919ms` | 弹窗时间＝IDE 冷启动 + 装配 |
| 14:48:33.3 | `request→dialog: 278ms`（第三次） | IDE 热后同一条语句 278ms |

### 18.2 判读

- 插件自身装配已稳定在 **53~64ms**；剩下的 11s 里绝大部分是 **IDE 冷启动期在建索引/编译**，我们的读动作排在后面等锁。
- 但有两处是我们可以让出来的：
  1. 点击闭包里的**实体/表上下文解析**（`XmlSqlCompletionContextResolver.resolveXml`）依赖索引，索引期跟着一起等；它只影响补全提示，不影响 SQL 预览。
  2. `ColumnNameCalibrator` 在索引期仍会**逐表投递 schema 预热**（830 次），每次都在「模型冷」里空转——纯噪音。

### 18.3 修法（S10）

- `XmlSqlGutterLineMarkerProvider` 的点击闭包加 dumb 门：索引期跳过 `resolveXml`（entity 传 null），并打一条
  `[open-perf] pre-assembly: sourceCtx=…ms completion=…ms dumb=…`，把「点击 → 装配 → 弹窗」三段拆开；
  下一次真机日志可直接读出每段耗时，不再靠猜。
- `ColumnNameCalibrator.requestPrefetch` 加 dumb 门：索引期不投递预热（那时数据源模型同样没加载），索引就绪后的重绘自然补。

### 18.4 验收口径（给用户）

「秒开」要在**IDE 空闲后**量：同一语句第三次打开是 278ms 属正常；刚打开工程、状态栏还在建索引/编译时点开，任何插件都要排在 IDE 自己的写动作后面。下次日志若仍慢，直接看 `pre-assembly` 那行的三段数字：
- `sourceCtx` 大 → 文件/源上下文解析，按需缓存；
- `completion` 大且 `dumb=false` → 实体上下文在热态也重，需按表批量或懒化；
- 三段都小但 `request→dialog` 仍大 → 弹窗/面板构造成本（下一轮查 `showResult` 段）。

---

## 十九、第六轮现场（2026-11-13 15:00 沙盒，IDE 空闲后仍慢）：`completion` 段 6~10 秒

### 19.1 读数（埋点直接读出）

```
15:00:37 [open-perf] pre-assembly: sourceCtx=17ms completion=10327ms dumb=false
15:00:38 [T-PreviewFast] request→dialog: 11322ms
15:00:51 [open-perf] pre-assembly: sourceCtx=0ms completion=5924ms dumb=false   ← 第二次仍 5.9s
15:00:51 [T-PreviewFast] request→dialog: 5938ms
```

`dumb=false`（IDE 已空闲），耗时全部落在 `XmlSqlCompletionContextResolver.resolveXml`。

### 19.2 根因

`buildCandidates`（`XmlSqlCompletionContextResolver.kt:207` 旧实现）把**全项目** `@TableName` 实体逐个
`EntityResolver().resolve(...)`，**最后**才 `take(CANDIDATES_CAP=50)`——2500 表工程上把上万实体解析一遍，
只为取前 50 个；而且**没有缓存**，第二次打开照样重来。

### 19.3 修法（S11）

1. **边解析边判上限**：够 50 个立即 break（不再「全解析后截断」）。
2. **项目级候选缓存**：键＝`locationHash`，值随 `PsiModificationTracker.modificationCount` 失效；
   同会话第二次打开直接命中。
3. **预算**：单次候选解析 400ms（`candidatesBudgetMs`）到点即停，剩余候选等下次缓存重建。
4. 分段埋点 `[open-perf] ctx detail: entity=… resolve=… candidates=…`，下一次真机日志可直接定段。

### 19.4 读数与反向验证（非 GUI）

- 新增 `XmlSqlCompletionCandidatesBudgetTest`（60 个 `@TableName` 实体）：
  - 首次解析的实体数必须 ≤ 50（上限截断）；
  - 同会话第二次解析必须命中缓存（`candidatesCacheHits == 1`）。
- **首版空绿已修**：Light 沙盒里 `AnnotatedElementsSearch` 返回空（`entitiesResolved=0`），用例等于没测；
  加 `tableNameEntitiesProviderForTest` 注入缝喂真实 PSI 类后才有意义。
- 反向验证：①去掉上限 break → 解析 60 个 → 1 红；②关掉缓存命中 → `cacheHits=0` → 1 红；均已还原。
- 缓存引入的**用例串扰**也已处理：同测试类共享 project 时上一条用例的候选会漏进下一条
  （`testNoNamespaceDegradesToNullMain` 因此变红），在既有用例 `setUp` 清缓存后复绿。

### 19.5 待验

下一次真机 `pre-assembly` 行预期 `completion` 从 5924~10327ms 降到 **≤400ms（首次）** 与 **≈0（二次）**；
若首次仍偏大，按 `ctx detail` 的 `entity/resolve/candidates` 三段继续收。

---

## 二十、补全链修复（2026-11-13 16:40 用户真机栈）

### 20.1 我的实现错误：候选补齐在无读动作的工作线程里跑

用户贴的栈（`ApplicationImpl pooled thread`）：

```
XmlSqlCompletionContextResolver.warmCandidates(:312) → buildCandidates(:265)
 → findTableNameEntities(:361) → JavaPsiFacade.findClass
Read access is allowed from inside read-action only
```

`warmCandidates` 由面板 `executeOnPooledThread` 调用，而里面全是 PSI/索引读——不包读动作必然抛，
异常被 `runCatching` 吞掉 ⇒ **候选永远补不进去**（用户看到的「补全一直空」），同时刷一屏 IDEA 报错。

修法：`warmCandidates` 改用 `ReadAction.nonBlocking { … }.inSmartMode(project).expireWith(project)
.coalesceBy(this, key)`（与 S1 预取链同款：等索引不持读锁、可取消），落地后回调刷新上下文；
单测走同步分支（测试线程持读动作）。面板调用点相应去掉外层 `executeOnPooledThread`。

### 20.2 顺带修掉的第二处：子句判定读旧 PSI

「删掉最后几个字符再补就没提示」＝子句判定读 `containingFile.text`（PSI 未 commit，读到改动前 SQL），
判成 TABLE ⇒ 列名分支整段跳过。改用 `parameters.editor.document.text` + `parameters.offset`（§二十三），
源码守卫 `SqlCompletionClauseSourceTest` 钉住。

### 20.3 环境残留：workspace.xml 的 `last.runner.id` 缺引号

用户贴的 `PropertiesComponent` → `JsonDecodingException: Expected quotation mark` 即 T-LastUsedEdt 的存量损坏
（`"mpsql.visualizer.last.runner.id": 8d6c2a1b-…` 值未加引号）。已直接修复 demo 仓 `.idea/workspace.xml`
该条目（沙盒项目为 `mybatis-plus-sql-visualizer-demo`）；`.idea/` 不入库，无需提交。

### 20.4 真机日志追加（16:35/16:55）：Example 参数被当成实体

```
16:35 [completion] ctx: main=cust_channel_example columns=3    ← 表名与列都错
16:55 [completion] ctx: main=cust_channel columns=19           ← 偶发正确
16:55 [completion] ctx: main=- columns=0
```

`findEntityParam` 把 `selectByExample(XxxExample)` 的 **Example 参数当实体**：表名推成
`cust_channel_example`、列取到 Example 的 3 个字段 ⇒ 列名补全给出完全错误的候选。
修法：参数简单名以 `Example` 结尾时按 MBG 约定回查同包 `Xxx` 实体；查不到就跳过（宁可 main=null）。
读数：`testExampleParamNotTreatedAsEntity` / `testExampleParamWithoutEntityDegradesToNull` 两例，
反向去掉该分支 → 2 红，还原复绿。

---

## 二十一、自验收（2026-11-13 18:00+，runIde 全屏沙盒 + 日志判据）

### 21.1 冷启动契约的三次修正（全部由真机日志驱动）

| 现象（日志） | 根因 | 修法 |
|---|---|---|
| `resolve=9314/9447/9259ms` 三连 | `EntityResolver` 自带缓存键含 `identityHashCode(PsiFile)`，PSI 重建即失配 | 自有缓存（类名 + stamp），`d829…` 起 |
| 同一实体两次 `resolve=1ms / 4786ms` | 纯 stamp 判据在本工程被高频动作顶掉 | 改 **TTL(60s) 内直接命中**，TTL 外才要求 stamp 未变；加命中/未命中日志 |
| `prefix='DEL_F' ctx cols=19 added col=0` | 实体列名是小写蛇形（`del_flag`），用户敲大写（`DEL_FLAG`），前缀匹配命中不了 | `columnNameAliases`（原名 + snake + UPPER_SNAKE），任一形态命中即收录并注册为 lookup string |

### 21.2 真机读数（cust-big-demo，全屏 runIde 沙盒）

- 预览打开 `request→dialog`：**10s → 696ms**（`resolve=1ms`，实体缓存命中）；
- 补全：`prefix='DEL_F' … added col=1`，弹窗出现 `DEL_` / `DEL_F` 候选项（截图 `q3-completion.png`）；
- 与用户原场景一致的文件（`CustAddressMapper.xml` / `cust_address`）与语句（`selectByExample`）。

### 21.3 环境坑（本轮踩到）

- **Light 沙盒不加载 plugin.xml 的 EP**：平台 fixture 里显式注册 `CompletionContributor` 也不行（EP 名常量在 243 是 `LanguageExtensionPoint` 派发），补全的端到端只能靠 GUI + 日志；因此把「前缀 → 候选」这段做成纯函数用例（`columnNameAliases`）收口。
- **`Ctrl+Space` 被系统输入法吞掉**：GUI 触发补全只能用「键入触发自动弹出」，脚本里不要按 `Ctrl+Space`。
- 沙盒 jar 必须在被测动作**之前**构建：本轮两次因为「jar 早于修复提交」而白跑一轮，判据是 `unzip -p jar …class | grep 新符号`。

---

## 二十二、第七轮现场（2026-11-13 20:00 用户真机）：打开预览面板还是太慢 —— 反查索引每次重扫

### 22.1 读数（沙盒 `idea.log`，同一份日志里热冷交替）

```
18:52:57  entity cache hit ttl=434650ms  → ctx detail: entity=0ms resolve=3811ms candidates=0ms include=true
18:52:59  entity cache hit ttl=436528ms  → ctx detail: entity=0ms resolve=2ms    candidates=0ms include=true
19:00:08  entity cache hit ttl=865761ms  → ctx detail: entity=1ms resolve=3994ms
          gutter pre-assembly: sourceCtx=0ms completion=3995ms dumb=false
          [T-PreviewFast] request→dialog: 4190ms
19:00:44  …                              → ctx detail: entity=1ms resolve=3737ms
          [T-PreviewFast] request→dialog: 3931ms
```

读法：`entity=` 段已经是缓存命中（0~1ms），**3.7~4.0 秒全在 `resolve=` 段**——`resolveCommon` 里
「主实体链路失败 → 用 SQL 里的表名反查实体」这一步，即 `EntityClassFinder.find(table)` 的
`@TableName` 反查慢路径（16804 个类一趟全量扫描）。

### 22.2 机制：`PsiModificationTracker` 失效基准 + 我们自己写 PSI

反查缓存原来的失效基准是 `PsiModificationTracker.modificationCount`，它对**任何** PSI 变化都递增；
而预览链路自己就在改 PSI（写 `mpsql-exec.sql`、临时 PsiFile、对话框里打字）。于是形成
「打开预览 → 写 exec 文件 → 缓存失效 → 下次打开重扫全工程」的闭环，`18:52:59` 的 2ms 与
`19:00:08` 的 3994ms 之间就差了一次我们自己造成的 PSI 变化。

### 22.3 修法（`c96759946`）

1. **一次扫描建全表索引**：不再「查一个表名扫一遍」，改为一次遍历把「表名 → 类」全部收进索引，
   任意表名查询 O(1)（用户每换一条不同表的语句不再各吃一次 3.8s）。
2. **并发口径**：索引建立中时后来者**不等待**、立即返回空（预览照常打开、上下文降级），
   由先到的线程填索引——「EDT 上等别人扫 4 秒」正是要根治的形态。
3. **失效基准改项目结构版本 + TTL(5min)**：打字与我们自己的 PSI 写入不再作废索引；改实体上的
   `@TableName` 最迟一个 TTL 周期后自动重建。

### 22.4 守卫与反向验证

- 新增 `testReverseLookupIndexSurvivesSourceEdit`（改已有文件正文不作废索引，并断言 PSI 版本确实递增，
  防夹具空绿）；改写 `testPsiModificationInvalidatesCache`（①未过 TTL 不重建 ②过 TTL 重建后新表名可见）；
  新增跨缓存实例的累计计数 `totalScanCountForTest`（只看 `scans` 会因条目重建归零而**空绿**——第一版
  守卫就是这么假绿的）。
- 反向验证：把失效基准换回 `PsiModificationTracker` → 上述两条用例**各恰 1 红**，还原后复绿。
- 定向：`EntityClassFinderHeavyTest` 18/0/0/0 + `EntityClassFinderIntegrationTest` 21/0/0/0。

### 22.5 平台索引复用调研（用户 20261113 令「看看能不能复用 idea 自己构建的索引」）

后台子代理反编译 243 平台（`plugins/java/lib/java-impl.jar`）取证，结论如下（关键处已自行 javap 复核）：

- **注解属性值没有被任何平台索引收录**：`JavaAnnotationElementType.indexStub` 写入的键＝
  `getReferenceShortName(stub.getText())`，先 `indexOf('(')`（字节码偏移 `bipush 40`）截断、再取短类名
  ⇒ 键恒为 `TableName`；属性名/属性值都不进索引。全 IDE 132 个索引注册里 4 个与注解相关，**没有一个按属性值建键**。
- 因此「`@TableName("cust_address")` → 类」的一步 O(log n) 直查在平台层**不存在**，只能
  「按注解名取候选（索引，O(log n)）→ 逐候选读注解值过滤（PSI）」。与现路径相比，候选量从
  「项目类数 16804」降到「带 `@TableName` 的实体数」，**能换掉一半**：省的是逐类短名索引查询与无注解类的读取；
  换不掉的是「无注解、按类名推断表名」那条（索引与推断表名无关）。
- 结论：**本轮不做**（当前 TTL+结构版本口径已把 3.8s 从「每次打开」压成「每个结构版本一次」，
  且该一次落在打开文件的后台预热里）；登记为后续可选优化（若真机上仍能感知冷启动那一次，再换注解索引取候选）。
- **顺带修掉一个真缺陷**：`XmlSqlCompletionContextResolver.findTableNameEntities` 的兜底分支把 **FQN**
  传给了 `JavaAnnotationIndex.getAnnotations(...)`，而索引键是简单名 ⇒ 该分支**恒空**（且它只在
  「注解类解析不到」时走，正是它要救的场景）。已改为传简单名 + 按注解原文筛同名（不 resolve），
  守卫 `XmlSqlCompletionContextResolverIndexKeyTest` 钉住「只传简单名」（反向改回 FQN 必红）。

### 22.6 not run

- 全量门禁（按用户口径走 CI，本地不跑）；GUI 复验（沙盒已在后台重启，逐场景读 `request→dialog` 与
  `pre-assembly` 读数中）：本轮要证的是「同一份日志里热/冷交替时 `resolve=` 段不再出现 3.7~4.0 秒」。

### 22.7 第二轮真机读数（2026-11-13 19:33 / 19:48 沙盒）：索引建成本身 4.7~9.7 秒，且压在点击链上

改完 22.3 之后重启沙盒复测，同一份日志给出下一步：

```
19:33:10  ctx detail: entity=256ms resolve=3459ms include=false      ← 建索引在别的线程进行中
19:33:20  [open-perf] 反查索引一次建成 tables=8688 耗时=9675ms        ← 冷态一趟扫描 9.7s
19:33:20  ctx detail: entity=0ms resolve=9745ms include=false         ← 赢家线程等了整趟
19:48:13  ctx detail: entity=4ms resolve=762ms include=true           ← 主实体链路直接命中，没走反查
19:48:25  [open-perf] 反查索引一次建成 tables=8688 耗时=4737ms        ← TTL 到期后重建，4.7s
19:48:26  [T-PreviewFast] request→dialog: 5119ms                      ← 这 4.7s 就压在点击上
```

三点结论：①「一次扫描建全表索引」把「每次打开重扫」消掉了（19:48:13 那种主链路命中就是 762ms），
但②**这一趟扫描本身 4.7~9.7 秒**，只要它跑在调用方线程上，用户照样等；③TTL(5min) 到期后的第一次
查询会把这趟扫描重新压到点击上。

### 22.8 第二轮修法（`e99750f53`）

扫描派到应用线程池（`ReadAction.nonBlocking{}.inSmartMode(project).expireWith(app).submit(AppExecutorUtil…)`，
不持读锁等索引），调用方**至多**等 `INDEX_WAIT_BUDGET_MS = 800ms`：预算内建完就命中，建不完本次降级
返回空上下文、下一次查询命中（等待者由 `CountDownLatch` 放行；失败路径同样放行，否则后来者白等一整个
预算且索引再没人能建）。测试口径默认走同步分支（`isUnitTestMode`），既有「首次查询即建好」的断言不变。

守卫：`testAsyncIndexBuildDoesNotBlockCaller`（注入 3s 扫描延迟 → 首次 find <2.5s 且返回空，随后后台
落地、第二次命中）、`testAsyncIndexBuildQueryDoesNotHangWhenBuildInFlight`；反向（把建索引改回同步）
恰 1 红。定向 EntityClassFinder 两测试类 + 补全上下文 + 面板生命周期全绿。

**下一步（未做）**：这 4.7~9.7 秒还能再压——平台注解索引 `JavaAnnotationIndex.getAnnotations("TableName")`
可把候选从「全项目类数」降到「带注解的实体数」（调研见 22.5；属性值仍要逐候选读）。当前先靠
「挪出调用方线程 + 有界等待」把用户可感延迟消掉；若真机上仍能感知冷启动那一次，再走这条。

### 22.9 第三轮真机读数（2026-11-13 20:08 沙盒，async + 有界等 800ms 之后）

```
20:08:30,057  ctx detail: entity=31ms resolve=804ms include=false     ← gutter 预热被预算截断（= 800ms 上界）
20:08:30,057  ctx warm done in 839ms                                  ← 预热不再占用户等待
20:08:35,386  反查索引一次建成 tables=8688（后台线程，testSync=false） ← 扫描全在后台跑完
20:08:36,500  ctx detail: entity=0ms resolve=34ms candidates=374ms    ← 反查已就绪：34ms
20:08:36,585  [T-PreviewFast] request→dialog: 501ms                   ← 首次点击（原 4190 / 5119ms）
20:08:53,838  ctx detail: resolve=5ms   → request→dialog: 32ms
20:08:59,515  ctx detail: resolve=0ms   → request→dialog: 254ms
20:09:05,205  ctx detail: resolve=0ms   → request→dialog: 8ms
```

对照 22.7 的同一组读数：`resolve=` 段 **3737/4737ms → 0~34ms**，`request→dialog` **4190/5119ms →
8~501ms**（首次 501ms 里含冷态装配，稳定态两位数毫秒）。

**顺手修的埋点失真**：重构后 `[open-perf] 反查索引一次建成` 的耗时量的是「落地那一下」（日志里一度只
显示 1ms），起点改回派发前，读数才代表调用方视角的真实成本（含排队 + 扫描 ≈ 4.7s，但已不在调用方线程）。

### 22.10 同期回归修复（CI `test(4)` 三连红，提交 `ad292a169`）

实体解析缓存的命中条件写成「TTL 内一律命中 || stamp 未变」⇒ **内容改了也命中**。CI 三个别名列用例
（`XmlSqlCompletionContributorTest` 04/11/13）用 `@TableName("t")`，而同类默认夹具用 `t_device_info`：
第二个场景拿到第一个的解析结果 → 主表名不对 → `FROM t u` 的别名绑定对不上 → 别名列补全整片消失。
用户侧等价形态＝改了实体上的 `@TableName`/字段，预览与补全 60 秒内还是旧的。
修法：判据改 `clazz.text.hashCode()`（内容指纹，PSI 重建不误 miss、真改了不误 hit）。
反向验证：命中条件改回「无视内容一律命中」→ 恰三红，还原复绿。

### 22.11 用户追问「全在后台线程会不会导致 IDEA 卡顿」——逐条核过，并补了两处硬护栏

结论：**扫描本身不会让 IDEA 卡**，但原实现有两条真实风险，已按下面口径改掉。

**① 扫描不在 EDT，EDT 只派发（O(1)）**：预览装配在 `executeOnPooledThread + ReadAction.compute` 里
（`SqlPreviewService.previewAsync`），gutter 预热是 `ReadAction.nonBlocking`，扫描落在应用线程池。
**真机证据**：20:08 那趟 4.7s 扫描期间，沙盒 `idea.log` **没有**任何 `PerformanceWatcher` 的
freeze/sluggish 记账（最近的记账停在 19:56:57 启动阶段），同期 `request→dialog` 是 32/254/8ms。

**② 但「补全」在 EDT 上**：`XmlSqlCompletionContributor.addCompletions`（每敲一个字符）也会走反查
——原实现按 800ms 预算等后台扫描，等于**在 EDT 上最多卡 800ms**。这就是用户担心的那种卡顿形态，
已改为：**EDT 只派发、立即返回**（`isDispatchThread` 时不等）；索引由预热/上一次派发在后台建好。
守卫 `testEdtCallNeverWaitsForIndexBuild`（注入 3s 扫描延迟，EDT 内调用耗时必须 < 预算的一半）；
反向改成「EDT 也等待」→ 恰 1 红。

**③ 长读动作会不会挡住写动作（打字）**：扫描跑在 `ReadAction.nonBlocking` 里，平台要拿写权限时会
**撤销**它；扫描每 `LIBRARY_SWEEP_CANCEL_INTERVAL = 64` 个短名 `checkCanceled()` 一次，
撤销在毫秒级生效（本轮补：**不吞 `ProcessCanceledException`**——吞掉会让非阻塞读动作退化成
「不可撤销的长读者」，这正是 11.12 冻结事故的形态）。另外补 **10 秒冷却**：
刚被撤销/失败过就不再立刻重投，否则「输入 → 扫描被取消 → 下次输入再投」会反复空转白烧 CPU。

**④ 剩下的真实成本**（接受，不再是 UI 风险）：每个结构版本（含 5 分钟 TTL 到期）会有一趟
4.7~9.7s 的**后台** CPU 扫描——表现为风扇/占用，不是界面卡顿；若扫描在途时用户点击预览，
非 EDT 调用方最多等 800ms 以换取完整上下文（等不到则本次降级、下次命中）。想再压这一档，
走 §22.5 的「平台注解索引取候选（16804 → 约 2500）」路线，未做。

### 22.12 第四轮（`ee8c69e9a`）：候选源换平台注解索引 + 联表候选改「缓存优先」

真机读数（20:42 / 20:47 沙盒，同一台机、同一工程 `cust-big-demo`）：

| 指标 | 第三轮（全表索引） | 第四轮（注解索引） |
|---|---|---|
| 索引条目 | `tables=8688`（16804 个类全扫） | **`tables=812`**（候选注解 812） |
| 索引重建耗时（稳态） | 4737 ms | **187 ms** |
| 索引首次建成（含冷 JIT/索引加载） | 9675 ms | 5089 ms（仍在后台，不占调用方线程） |
| 联表候选（冷态点击链） | 374 ms | **0~2 ms**（缓存优先 + 后台预热，`warmCandidates ok cached=34`） |
| `request→dialog` | 8~501 ms | **8~385 ms**（385 是首开含冷 `EntityResolver.resolve` 188ms + 面板装配） |

**为什么能砍掉 90%**：慢路径存在的理由是「实体名与表名对不上、只有 `@TableName` 能说明」，而按命名
约定的匹配本来就走快路径（4 个候选类名 + `EntityResolver` 按真实策略校验）。全表索引里 8688 条绝大
多数是**给非实体类算出来的推断表名**——那正是 4.7~9.7s 的大头，现在整段不再做。
注解索引一个候选都给不出时（纯 JPA/原生 MyBatis 工程、测试夹具）退回老口径，能力不降级。

**仍保留的成本**：稳态重建 187ms、冷态首趟 5.1s（后台、一次性）；点击链上剩下的是冷
`EntityResolver.resolve`（188ms，之后缓存命中 0~1ms）。想再压这 188ms 就得把实体解析也预热到
文件打开时——收益已很小，本轮停在这里。

已知边界（记档）：`@TableName(SOME_CONSTANT)` 这类非字面量取值读不到表名；不再由索引兜「无注解 +
类名不合约定」的实体（老口径靠推断能命中，现在只能靠改名或加注解）。

### 22.13 用户提醒「记得兼容不同 ORM」——候选源改成按 ORM 分源 + 未命中回补

22.12 把候选源收窄到 `@TableName` 后，**JPA 工程（`@Table(name=…)`）会整片漏掉**；而且这种漏在测试
夹具里看不见（注解索引对 fixture 曾给不出结果，用例走的是推断那半边，绿得毫无意义）。按下面三条收口：

1. **分源表**（新 `psi/TableNameAnnotations`，单一事实源）：MP `@TableName(value)`、
   `javax.persistence.Table(name)`、`jakarta.persistence.Table(name)`；**非字面量**取值返回 null。
2. **未命中回补**：注解索引查不到该表时，触发一次「全项目推断扫描」并合并进同一索引（每个缓存期
   至多一次，`inferredScanCount` 可读）。这条覆盖 **MBG / iBatis / Kotlin / 自定义映射**——它们根本没有
   表名注解，只靠注解索引必然漏。口径与注解索引一致：派到应用线程池、EDT 不等、非 EDT 有界等预算。
3. **依赖库扫描**（T-JarEmbedded 方案 1）也改用同一注解源——共享模型模块里的 JPA 实体同样能被反查。

**跨 ORM 覆盖矩阵**（改后）：

| 工程类型 | 表名来源 | 走哪条 | 成本 |
|---|---|---|---|
| MP（`@TableName`） | 注解索引 | 快路径① | 稳态 ~200ms/结构版本（后台） |
| JPA（`@Table`） | 注解索引（同一索引） | 快路径① | 同上 |
| MBG / iBatis / Kotlin / 自定义 | 无注解 ⇒ 类名约定 + 推断 | 快路径②③，未命中时回补① | 首个未命中触发一次全项目推断（后台，4.7s 级） |

**踩坑记档（L68）**：`PsiAnnotation.getQualifiedName()` 在注解类**解析不到**时返回**短名**而非 null
（探针实测 `@TableName` → `qn=TableName`）。按 FQN 相等比较会把所有「缺依赖」场景判成「别家注解」
⇒ 快路径与回补一起静默失效，自测时 11 个反查用例同时归零。判据改为「带包的 FQN 才参与比较」。

守卫：`TableNameAnnotationsTest` 7 例；Heavy 补 2 例（JPA 命中、回补至多一次且未命中不再扫）。
反向：摘掉 JPA 源 → 5 例红，还原复绿。**not run**：真机 JPA/iBatis 工程实测（沙盒语料是 MP+MBG 混合，
JPA 分支由夹具覆盖）。

### 22.14 跨 ORM 分源后的复测（21:08/21:09 沙盒）：索引链无回退，首开的剩余成本换到了「面板/控制台准备」

```
21:08:55  ctx detail: entity=5ms resolve=53ms ...            ← 冷实体解析 53ms（之后 1~3ms）
21:08:56  warmCandidates ok=true cached=0 elapsed=724ms       ← 预热跑了但缓存 0 条（见下）
21:09:03  ctx detail: entity=2ms resolve=1ms candidates=0ms
21:09:03  [open-perf] total=59ms parse+ctx=47 profile=0 m2=0 model=12   ← 装配只要 59ms
21:09:04  [T-PreviewFast] request→dialog: 671ms                ← 装配→弹窗之间 593ms 在别处
21:09:04  [M36] createEditor / prepareDefaultConsole / exec file updated / prewarm done in 293ms
21:09:22  request→dialog: 16ms
21:09:27  request→dialog: 7ms
21:09:33  request→dialog: 8ms
```

两点结论：

1. **索引链没有回退**（`resolve` 1~53ms、稳定态 `request→dialog` 7~16ms），跨 ORM 分源只是把注解索引
   从查 1 个简单名变成查 2 个（`TableName` / `Table`），稳态成本无感。
2. 首开那 671ms **不是索引**：装配（`total=59ms`）→ 弹窗（671ms）之间的 593ms 花在预览面板的
   **编辑器/控制台准备**上（`createEditor` → `prepareDefaultConsole` → exec 文件落盘 → `prewarm 293ms`）。
   这是既有链路的成本，与本次反查/候选改造无关；真要再压，方向是把 `prewarm`（293ms）也挪到弹窗之后。

`warmCandidates cached=0`：预热在工程索引尚未就绪时跑了一次（`elapsed=724ms` 却解析出 0 条），
属候选链既有行为（下一次 `refreshCompletionContext` 会补），不是跨 ORM 改动引入的。

### 22.15 第五轮（`P8` 埋点）：把「弹窗侧」也拆开量——稳定态 55~75ms，冷态首开 ~700ms 是类加载

新增两条**局部作用域**埋点（不再用全局单槽时间戳：并发预览会互相覆盖，实测把 651ms 的请求量成 1ms）：

```
21:44:30,962  [T-PreviewFast] request→dialog: 351ms        ← 本会话第一次：请求→进入 showResult（含冷装配）
21:44:31,306  [open-perf] dialog: construct=256ms show=88ms ← 弹窗构造冷：编辑器/控制台/Laf 首次初始化
--- 之后每一次 ---
21:44:42,385  request→dialog: 17ms
21:44:42,438  dialog: construct=15ms show=38ms              ← 合计 ≈ 70ms
21:44:53,757  request→dialog: 10ms / construct=20ms show=29ms ← 合计 ≈ 59ms
```

同时给 `prepareDefaultConsole` 加了耗时埋点，读数 `elapsed=0ms`（exec 临时文件已被 `previewAsync` 的后台
预热写就，控制台准备为零成本）——**由此否掉**「把控制台准备挪到 show 之后」这条优化：收益为零，只是
徒增一次时序改动。

结论（用户「性能做到极致」的落点）：
- **稳定态**：请求→可见 **≈ 55~75ms**（10~17 装配+排队、15~20 构造、29~38 show）；
- **本会话第一次**：≈ 695ms，其中 construct 256ms（vs 热态 15~20ms）＝ 编辑器/控制台/Laf 的**首次类加载与
  初始化**，属 JVM 冷启动成本，不是某个热点；再往下压只能靠「打开文件时预构建面板」这种收益可疑的改造；
- 反查索引（含 JPA 源后）`tables=1012`，建成后 `resolve` 稳定 0~2ms。

**not run**：`ExecPsiGateTest` 属 `testDbExecPsiGate` 桶（需 Database 插件），本轮用 `--tests` 误把它拉进普通
`test` 任务，7 例失败是环境缺插件所致（非回归；CI 走独立任务）。
