---
name: project-startup-read-lock-freeze-20261113
description: T-StartupReadLockFreeze（20261113 立项、两车道交付并合 master；十二轮「首次不秒开」与补全链
  返工全部收口，最新 `acf503f83`，beta.2 tag 打 `866c6e651`）——2026.11.12 在 Linux 上打开即卡死/预览白屏：MBG 预取在阻塞读锁里跑
  ReferencesSearch，与 EDT 写意图→写锁升级互等；含零成本自救、九轮修法（预算落入检索/首开转后台/
  模型冷窗口/防自激/索引期让位/候选截断与 TTL/补全回填/子句读文档）、CI 序列与验收口径
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

用户 2026-11-13 08:40 反馈：**2026.11.12 在 mac 正常、Linux 上 IDEA 打开即卡死**；预览窗「点开好久才出来、出来还是空白、然后开始 dump」；工程规模「表上千了，实体类上万」。冻结报告目录 `threadDumps-freeze-20261113-082447`（8 份 dump，5 秒间隔，08:24:47→08:25:24），`report.txt` 尚未拿到。

两份栈把互锁两侧钉死（非推测）：

- **后台**：`MbgExampleCallResolver.prefetch` → `ReadAction.compute`（该 IDE 构建里 `compute` 委托 `computeBlocking`，**不可取消**）→ `resolveInReadAction:208` → `findCallSite` → `ReferencesSearch`。
- **EDT**（`AWT-EventQueue-0`）：`dispatchDocWriteOnEdt`（`DataSourceSelectorResults.kt:2588-2589`）→ `DocWriteContext.runWriteSafe:72`「可写就地执行」→ `DataSourceSelector.docWriteOnce:2085` 的 `WriteAction.run` → `prepareWriteFromWriteIntentBlocking / upgradeWritePermit`，**等其它读者释放**。
- 闭环：EDT 等后台读锁；后台不可取消且在索引构建期会同步等 smart mode（`ui/XmlPreviewService.kt:118-120` 有 11-12 冷启动实测注释）；索引推进又需写权限。

版本相关性与结构性不对称（本单最硬的判据）：

1. tag `2026.11.11` 的 `XmlSqlGutterLineMarkerProvider` 里**没有** prefetch（`git show 2026.11.11:…` 为空）；`git diff --name-status 2026.11.11 2026.11.12` 显示 `A ui/MbgExampleCallResolver.kt`。
2. 同一份 M2（MBG Example 调用点反推）检索，**预览链有 dumb 门**（`XmlPreviewService.kt:121` `!DumbService.getInstance(project).isDumb`），**预取链一个门都没有**（`prefetch` `:149-170`：无 dumb、无项目初始化、无规模/预算）。
3. `findCallSite` 空结果时会**再跑一遍全量检索**只为取 `total` 日志（`:268`）；失败常态（`countByExample` 类）是双倍成本。

Linux vs mac 的判读：两条链 grep 无 `os.name` 分支；用户 Linux 栈含 `java.base@25.0.4.1` / `NestedLocksThreadingSupport` / `SuvorovProgress` ⇒ 那台是**更新一代 IDE**（新锁实现会把写意图升级真等读者），旧版直接放行。最可能是 **IDE 构建 × 冷索引 × 工程规模**，不是操作系统。

**零成本自救（已告知用户，不用等修复）**：设置 → 本插件 → 取消「在代码左侧显示预览 SQL 图标」（`showGutterIcon`）⇒ `getLineMarkerInfo` 在 `:47` 早退，**预取完全不触发**，预览点击照常。次选回退 2026.11.11。11.12 没有「只关预取」的开关。

计划文件（已写，含 S1~S6 与 D-1~D-6）：`docs/开发计划/20261113-0839-T-StartupReadLockFreeze-2026.11.12版Linux上预览与启动卡死白屏-开发计划.md`；索引已登记在 `docs/索引/开发计划.md` 表头第一行。
修法主干：S1 `ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project).coalesceBy(this,key).submit(AppExecutorUtil…)`（`inSmartMode` 等索引时**不占读锁**、可取消，替代 `inFlight` 集合）；S2 规模门+每会话预算+项目打开后冷却+Registry 开关 `sqlvis.mbg.prefetch`；S3 预览 `resolve()` 同样去阻塞并超时降级占位骨架；S4 EDT document 写不再走「写意图→写锁」升级（推荐 a：一律 nonModal 投递+有界等待，写体幂等）；S5 同族面审计（`XmlEwResolver:42`、`SqlDialectMappingPrewarm:78` 等）；S6 超时熔断 + `[m2]` 日志可见。

待取证（下一轮要用户给）：`report.txt`（32 KB，最有价值）、`.throwable`、`.appinfo`/`MPV-Build`、工程规模落到数字、「关图标后是否仍冻结」的一次读数。

一个证据卫生点：**发行 jar 的 LineNumberTable 最大行号 431，而 tag `2026.11.12` 源文件只有 394 行**，栈里 `findCallSite(:417)` 越界——正文引用一律用当前 master 行号，用户包来源另需对账（同族纪律见 [[pitfall-buildplugin-zip-tainted-by-inflight-changes]]、[[pitfall-sandbox-jar-contents-not-version]]）。

**实施进度（2026-11-13 08:39 起）**：计划与索引提交 `077d4370f`（master）。用户令「按推荐从主分支新建分支和工作树，实施计划所有内容，后台执行、最后主控合并」⇒ 三条分支各配工作树，全部从 master 起点：车道 A `t-startup-freeze-prefetch` / `.worktrees/mpsv-startup-freeze-a`（S1/S2/S3/S6 + S5 的 `XmlEwResolver` 门）、车道 B `t-startup-freeze-docwrite` / `.worktrees/mpsv-startup-freeze-b`（S4-a），集成 `t-startup-readlock-freeze` / `.worktrees/mpsv-startup-freeze`。并行登记名为 `T-StartupFreezeA` / `T-StartupFreezeB`。合并时**计划文件 §十三 两车道都回填、必冲突，按并集处理**。

实施期两个小事实：①本仓 `plugin.xml` 没有任何 `<registryKey>`、`src/main` 也不用平台 `Registry` ⇒ S2 的「预取开关」要先声明 `registryKey` 扩展（并留意 plugin.xml 扩展计数类守卫）；②`WrapperLocator` 的 `ReferencesSearch.search(variable)` 没传 scope（局部变量、天然便宜），给它「加预算」收益低，优先级应排在 S1/S4 之后。
**车道 B 已交付（2026-11-13，S4-a）**：提交 `9f712eb78`（`DocWriteContext` 新增 `invokeLaterWriteSafeAndWait` + 测试缝 `deferEvenInUnitTestMode`、`dispatchDocWriteOnEdt` 的 EDT 分支改「nonModal 投递 + 有界等待」、`DataSourceSelector` 仅 KDoc 同步、新增 `DocWriteDispatchSafetyTest` 4 例）+ `f0f2ea86d`（计划 §十三 回填）。定向 7 类 58/0/0/0 绿（1m04s）；反向把 EDT 分支改回就地写，`testEdtBranchDefersInsteadOfWritingInlineWhenContextAllowed` 两次独立复跑各恰 1 红，还原后复绿。已 `--no-edit` 快进并入集成分支 `t-startup-readlock-freeze`。逐调用点复核结论：生产面 EDT 分支只剩 `\G` 回写一个真实调用方，其余为防御分支/测试；`docWriteOnce` 幂等、调用方都不消费返回值。

**车道 B 带回的关键实测（影响归因，别记错账）**：S4-a 只去掉「调用方在写意图上下文里就地升级写锁」这一半；平台对 **nonModal 投递拍同样套 `runWithWritingAllowed`**（`TransactionGuardImpl.wrapLaterInvocation`），投出去那一拍在 EDT 上仍走 `runIntendedWriteActionOnCurrentThread → upgradeWritePermit`。所以**冻结点彻底解除只能靠 S1/S3**（后台不再长时间持阻塞读锁），S4-a 的收益是「调用方不再被写升级阻塞 + 等待有界」。真机验收时不要把「还会卡」记到 S4 头上。

**待办与已排车道**：车道 A 在跑（S1/S2/S3/S6 + `XmlEwResolver` dumb 门 + 开关）。已告知 A：仓内四个守着 `plugin.xml` 的守卫测试（`convention/DynamicEpComplianceGuardTest`、`config/PluginXmlStructureGuardTest`、`config/PluginXmlExtensionPointResolutionTest`、`convention/GuardSelfTestCoverageMetaGuardTest`）会因 `<registryKey>` 变红的话，**降级为零 plugin.xml 改动的 `System.getProperty("sqlvis.mbg.prefetch") ?: true`**，不为 Registry 本身耗时间。文档车道（架构 01/02、CHANGELOG `[Unreleased]`、LESSONS、计划 §十三、索引状态）挂在 A 之后。

**同期加固夹具**：用户令把 demo 仓 `cust-big-demo` 从 40 套 MBG 扩到 **2000+ 表 / 上万实体 / 多模块多 ORM**（口径见 [[reference-cust-big-demo-fixture]] 的「2026-11-13 扩规模轮」），子智能体在跑，目的是在沙盒里复现这次的规模依赖冻结。

**车道 A 已交付（2026-11-13，S1/S2/S3/S6 + S5-XmlEw）**：`39d4b2706`（`MbgExampleCallResolver.kt` 352 行改动 + `XmlEwResolver.kt` dumb 门 + `plugin.xml` registryKey + 新增 `MbgExamplePrefetchGateTest` 6 例）+ `592ec37c0`（计划 §十三）。定向：CacheTest 3、GateTest 6、XmlEwResolverTest 6 全绿；四个 plugin.xml 守卫 23/4/3/8 全绿。反向：摘 dumb 门 → GateTest 6 例 1 红（`expected:<[dumb-mode]> but was:<[cooldown]>`），还原复绿、`RED PROBE` 残留 0。

**三处与计划的偏差（写 CHANGELOG/回归时按这个口径，别照计划原文）**：
1. 开关**保留了 `registryKey`**（`sqlvis.mbg.prefetch`，`defaultValue="true"`），因为四个守卫实测全绿；`com.intellij.registryKey` 在 243 `app-client.jar!/META-INF/IdeCore.xml` 存在且 `dynamic="true"`（非 sysprop 降级）。
2. **S3 没用 `executeSynchronously`**：243 字节码实证它 (a) 与 `coalesceBy` 互斥抛 `IllegalStateException`、(b) 无时间预算且 `inSmartMode` 未满足时在调用线程 `invokeLater + awaitWithCheckCanceled`（正是本单要消灭的持等）。落地为 `submit + blockingGet(800ms) + 超时 cancel → null`；调用方已持读锁时（MCP）就地嵌套读动作。
3. 规模门取**首次检索耗时自适应**（≥800 ms 即本会话熔断该 namespace）而非模块文件计数——文件计数本身是一次索引查询，耗时是已付成本；冷却起点用「本插件首次看到该项目的时间」代理（243 `Project` 无打开时间 API）。

**中央收口（主控，集成分支）**：合流 `ef49211d5`（两车道，计划 §十三 冲突按并集：13.1~13.8 车道 B、13.9 车道 A）；定向 17 类 **133/0/0/0**（含两车道新用例与四个 plugin.xml 守卫）。

**⚠️ 合流前发现 master 已被另一会话推进两笔**（`d87853744` 项目打开自愈 workspace.xml / `d35719d8f` 记 L61）——集成分支基于 `077d4370f`，直接合 master 会把对方 3 个新文件当删除提交。已先 `git merge master`（`f37d82d6f`），合并后对 master 是纯增量 14 文件 +1022/−59。冲突只有 LESSONS：两侧都用 L61，裁决＝保留对方 L61（其提交信息已引用该号），本文教训改 L62 并按序号升序重排（用 python 切片重写，落盘后回读核验）。合 master 时又撞一次：对方再推 `f601ee4c9`（compose 配方）并占了 **L62**，本文教训最终落 **L63**（保持升序、两处冲突都是「对方优先、我改下一个空号」）。**纪律：多会话并行时，merge 前必须先看 `git log --oneline 基点..master`，别只看自己分支的 diff；LESSONS 一号之争要在合并那一刻再取一次空号。**

**文档已同批落地**（`d46aeda4d`）：架构 01（`DocWriteContext` 写安全三纪律 + 预取新契约）、架构 02 §2.9 与 exec 段、CHANGELOG `[Unreleased]` 一条用户可感条目（39d4b2706/9f712eb78）、LESSONS L62、计划状态与索引行状态；**计划 §13.10（全量门禁读数）待补**。

**§十四 追加现场（2026-11-13 10:0x，用户实测）**：关掉「在代码左侧显示预览 SQL 图标」后**冻结确实消失**（用户原话「没那么卡了」「取消勾选后没有那个问题了」）——现场证实预取链就是冻结点，比静态推断硬。但同一次复验暴露**第二个入口**：带 `<include refid="Example_Where_Clause"/>` 的语句点开预览仍约 **30s**（面板停在「正在准备预览…」），之后该 XML 编辑器顶部弹 `Plugin 'SQL Visualizer' might be slowing things down`（IDEA 的插件拖慢横幅 ⇒ EDT 也被牵连，与 §三 3.3 同型）；不带该 include 的 `selectByPrimaryKey` 很快；该 mapper XML 600+ 行、`<sql id="Example_Where_Clause">` 就在同一文件。

判读：只有 `parameterType=Example` 才会跑 `profile`（`resolveExampleProfile`，**未加预算**）与 `m2`（`MbgExampleCallResolver.resolve`，本单已改 `RESOLVE_BUDGET_MS=800` 有界等待 + 超时 cancel → 占位骨架）两段 ⇒ 30s 必落这两段之一。判据＝`[open-perf] total=… parse+ctx=… profile=… m2=… model=…` 一行（Example 档 `always` 恒记）。`m2` 是大头＝本单已覆盖；`profile` 是大头 ⇒ 另立 S7 给 profile 加预算（降级形态＝无预设骨架），**不并入本单**。计划已加 §十四（含复验判据与需用户提供的那行日志）。

**交付终态（2026-11-13，已合 master 并推双仓）**：合流提交 **`164c499bc`**（git merge，冲突仍只有 LESSONS=L63）。用户中途改令「直接合并到主分支、跑 CI、不要再本地跑了」⇒ 本地 `testShards` 在 1:15（编译段）被主动停掉，**没有跑完的读数不作门禁**；有效的中央读数只有定向 17 类 **133/0/0/0**。双仓推送走 `dev-support/push-all.ps1 -Branch master`（Gitee + GitHub token 通路，无弹窗）。CI 触发 `推送测试 · master @ 164c499b`，run **43348966367**（交接时 in_progress，后台监视器在轮询）。并行登记 `T-StartupFreezeA/B/Gate` 已按名字注销；三个工作树（`mpsv-startup-freeze-a/-b/-`）已 `git worktree remove --force` 删除，**分支保留**（`t-startup-readlock-freeze` / `t-startup-freeze-prefetch` / `t-startup-freeze-docwrite`）。计划 **§13.10（全量读数）改由 CI 回填**。

## 后续六轮「首次不秒开」返工（2026-11-13 10:00~14:50，全是真机日志驱动）

交付后用户连续四次「还是没有秒开」，每次靠沙盒 `idea.log` 定位一层，逐层剥出四个独立问题（都已合 master 并各跑一轮 CI）：

| 轮 | 提交 | 真机读数 | 根因 | 修法 |
|---|---|---|---|---|
| 2 | `93ac6efda` | `total=2482ms m2=2469` | **800ms 预算被绕过**：预览主链 `previewAsync` 把装配包在 `ReadAction.compute` 里 ⇒ `isReadAccessAllowed` 恒真 ⇒ 走「已持读锁→就地嵌套、无等待预算」那条分支 | 预算改成**检索内部截止**（`deadlineAt` 传进 `resolveInReadAction`/`findCallSite`，逐方法逐引用自查）；删掉失败分支的二次 `ReferencesSearch.findAll()` |
| 3 | `7278e5a23` / 测试 `e16552fb0` | 冷态仍 1.4~2.5s | `refsSeen` 仅 13 条却耗 2.4s ⇒ 开销在 `ReferencesSearch` **引擎内部**，逐条自查救不了单次 `next()` | 首开**只读缓存**（`resolveCachedOnly`），未命中转 `warmForPreview`（10s 预算、绕过预取三道闸），结果带 `PreviewResult.m2AwaitKey`，面板 `whenResolved` 落地后重算；非 GUI 验收 `MbgExampleAsyncOpenTest` |
| 4 | `c20101ff` | 45s / **19824 条** `scan: … bail=DasModel 遍历 0 张表`，830 表 × 15 源 | 数据源模型未加载时逐表逐源全扫，全部必然失败（纯白付），与点击→出窗窗口重叠 | `modelsColdNow` 20s 窗口：`tryCompute` 在遍历前短路、全源 bail 即 stamp；`prefetchTables` 模型冷整批跳过 + `>50 表` 留 8 帧调用栈 + 禁 EDT |
| 5 | `59a7ab59a` | 3 秒内 10 次 `m2 async ready applied` | **自激刷新**（第 3 轮自己引入）：warm 补不到结果也 `fireReady` → 面板重算 → 仍冷 → 再 warm，每轮跑完整 `showResult` | `warmAttempted`：同 key 同缓存代只补一次（`shouldStartWarm` 纯判定），缓存失效/调度失败才退回资格 |
| 6 | `6dad0bbc0` | `request→dialog 11919ms`，而我们 compute 首行日志比 request 晚 **11s**；同一语句第三次 278ms | 大头在**装配之前**且是 **IDE 冷启动**（14:47:57 日志里 `JavaBuilder … Compiling`），读动作排在写动作后 | 点击闭包加 dumb 门（索引期跳过 `resolveXml` 实体上下文）、`ColumnNameCalibrator` 索引期不投递 schema 预热；新增 `[open-perf] pre-assembly: sourceCtx/completion/dumb` 三段埋点 |

**关键口径（给用户验收时先说）**：「秒开」要在 **IDE 空闲后**量（状态栏无索引/编译）。刚打开工程就点，任何插件都排在 IDE 自己的写动作后面；实测第三次 278ms、装配 53~64ms。

**中间还踩到一个发行级红点**：CI `fast (2)` 报 `BundleParityGuardTest.pluginXml与optionalXml引用的key必须存在于bundle` —— 守卫把 plugin.xml 里 `key=`/`text=`/`description=` 中带点的值都当 bundle 键，新加的 `<registryKey key="sqlvis.mbg.prefetch" description="…">` 撞上。修法 `2f56f8eb7`：扫描前整元素剔除 `registryKey` 族（见 [[pitfall-bundle-parity-guard-registrykey]]），定向 5/0/0/0。

CI 序列（全绿收尾）：`43348966367` 1 红 → `2f56f8eb7` → `43350850354` 8/8 → `43374030679`（e16552fb0）8/8 → `43375577036`（c20101ff）8/8 → `43380885728`（59a7ab59a）8/8 → `43382245012`（6dad0bbc0）。**连续推送会按并发组取消上一轮**（`7278e5a2` 那轮就是被后一次推送 cancel 的，不是红）。

## 第七~九轮：候选全量解析 → 补全回归 → 子句读旧 PSI（2026-11-13 15:00~16:20）

| 轮 | 真机读数 | 根因 | 修法 |
|---|---|---|---|
| 7 | `ctx detail: entity=5ms resolve=66ms candidates=3230ms candidatesCount=1`（第二次仍 2116ms） | `buildCandidates` 把全项目 `@TableName` 实体**逐个** `EntityResolver.resolve`、最后才 `take(50)`；上限按「已解析出结果」计数，多数实体解析成空表名 ⇒ 照样扫全工程；缓存用 `PsiModificationTracker` 失效，**被预览链自写的临时 SQL 文档顶掉** | `6b1c24a4` 边解析边判上限（够 50 停）+ 项目级缓存 + 400ms 预算 + `ctx detail` 三段埋点；缓存改 TTL（见 [[pitfall-candidate-scan-cap-cache-and-test-traps]]） |
| 8 | 用户「打开快了，但 SQL 补全没了」 | 第 7 轮把 `includeCandidates=false` 一路带到**预览面板补全上下文**，而 `candidateTables` 只在打开那一次构造里取过、后续不重算 | `990ef076` 预览不算候选 + 缓存 TTL(120s `candidatesTtlMs`)；`ee085ddb` 补回：`warmCandidates` 后台补齐 + `cachedCandidateTables` 只读回读 + 落地 `refreshCompletionContext()` |
| 9 | 用户「刚开始有补全，删掉最后几个字符再接着补就没了」 | `SqlCompletionContributor` 子句判定读 **PSI 文本**（`position.containingFile.text`）；补全期文档刚改、PSI 未 commit ⇒ 读到旧 SQL、判成 `TABLE` ⇒ 列名分支整段跳过（表名分支还在，所以看着像「有补全但没列」） | `11a8c097` 改读 `parameters.editor.document.text` + `parameters.offset`（clamp），见 [[pitfall-completion-clause-reads-stale-psi]] |

**当前读数与状态**：打开已是 `request→dialog 15ms/30ms`（`pre-assembly sourceCtx≈0 completion=2ms`）；用户体感的「稍微有点慢」落在弹窗首帧/原生控制台挂载，不再是装配。补全为空还剩两条**环境性**来源：`ctx detail resolve=0ms`（沙盒未 Load Maven，mapper 接口/泛型实体没解析到）与 `schema 预热 resolved=0`（数据源未连，库列补全必然空）。为区分这三路，已加 `[completion] ctx: main/columns/candidates/schemaColumns` 与 `[completion] warmCandidates ok/cached/elapsed`（`3dd2bc0cd`，纯诊断）。

**用户报「IDEA 有些报错」时先查日志**：这一轮整份沙盒 `idea.log` 里 `cn.song` 前缀**没有任何 ERROR/WARN**（只有一条 `java.lang.Throwable: createEditor path` 是我们自己 info 级埋点）⇒ 先要原文/截图再定性，别按「我们的异常」查。

## 第十轮（2026-11-13 16:40）：补全恒空的真因是我自己的读动作缺失

用户贴出两段栈，第一段直接推翻上一轮「候选已后台补齐」的假设：

```
XmlSqlCompletionContextResolver.warmCandidates(:312) → buildCandidates(:265)
 → findTableNameEntities(:361) → JavaPsiFacade.findClass
Read access is allowed from inside read-action only   (ApplicationImpl pooled thread 7)
```

`ee085ddb` 把 `warmCandidates` 挂在面板的 `executeOnPooledThread` 上，但里面全是 PSI/索引读、
**没包读动作** ⇒ 每次必抛，异常又被自己的 `runCatching` 吞掉 ⇒ 候选永不入缓存（补全一直空），
还刷一屏 IDEA 报错。修法 `8997e6da`：改 `ReadAction.nonBlocking{}.inSmartMode(project)
.expireWith(project).coalesceBy(...)`（与预取链同款），落地回调 `onReady` 刷新上下文；单测走同步分支；
面板调用点去掉外层线程池。细节见 [[pitfall-candidate-scan-cap-cache-and-test-traps]] 第 5 条。

第二段栈是**存量数据损坏**不是新 bug：`PropertiesComponent` 初始化时
`JsonDecodingException: $.keyToString['mpsql.visualizer.last.runner.id']`（值丢开头引号）。
已就地修好沙盒打开的工程 `mybatis-plus-sql-visualizer-demo/.idea/workspace.xml`（1 处），
配方见 [[pitfall-bgt-write-project-propertiescomponent]]。`.idea/` 不入库，无需提交。

**同期运维**：用户要求「把 idea 日志清掉防止干扰」⇒ 把 `.intellijPlatform/sandbox/<插件>/IU-2024.3.7.1/`
下的 `log/`（71M）与 `log-test/`（147M）整体归档到 `build/sandbox-log-archive-<时间戳>/`（共 217MB，
`build/` 已 gitignore），不清空目录本身；先确认沙盒未运行再搬（否则文件被占用）。见
[[reference-sandbox-log-reset]]。

**How to apply:** 再见到「新版 IDE 打开即卡死 / 预览白屏 / 冻结 dump 每 5s 一份」先按 [[pitfall-bgt-blocking-read-blocks-edt-write-upgrade]] 的两栈对读；给用户的第一个回复就带零成本自救（关 gutter 图标），再谈版本回退与修法。后续「首次慢」类反馈，按 [[pitfall-preview-open-stage-split]] 先切段（request→compute→装配→dialog），再按 [[pitfall-m2-warm-retry-self-exciting-loop]] 查自激。动任何「打开时顺手算点什么」的优化前，先确认它没有在喂补全上下文——本单第 8 轮就是这么把补全整没了。

## 第十一轮（2026-11-13 17:00~）：压缩提交 + 发 beta.2 + 补全又两真因

**用户令**：「先合并下多次提交的 bug、合并下提交信息（太多无效提交了），然后发行今天的第二个内测版，
再继续修问题；发行派子智能体，你继续修复」。

- **压缩**：`7278e5a23..8997e6da` 共 **11 笔**（首开提速 + 补全）`reset --soft` 压成 1 笔 **`bccc3fd8e`**，
  双仓 force-with-lease 同步。GitHub 端首次报 `stale info`（URL 推送不更新 remote-tracking ref），
  用显式 `--force-with-lease=master:<ls-remote 旧 sha>` 通过，细节见 [[reference-force-push-master-after-squash]]。
- **发行委托**：子智能体写 `docs/发行说明/20261113-1704-首开提速与SQL补全修复-内测说明.md` + 版本位
  `2026.11.13-beta.2` + `plugin-change-notes.html` 顶块 + CHANGELOG 新节（提交 `a14569f2a`）。
  中途因我在同一工作树改 `src/` 被「干净树闸门」挡住并停手（处置口径记进
  [[pitfall-ci-release-notes-and-publish-run-gate]] §4）；我收口后放行，tag 打在新 tip `866c6e651`。
  另发现 `release-beta.ps1` 派发不透传 `-Notes`，会让 publish 必红（同文 §3）。
- **补全又两个真因**（都有非 GUI 用例 + 反向验证）：`7a0d2a101` 把 MBG `Example` 参数当实体
  （`main=cust_channel_example columns=3`）→ 改为回查同包实体、查不到宁可 null；`866c6e651`
  mapper 解析不到时用 `FROM` 表名反查实体。两坑与两个用具坑（`myFixture.file`、脚本写 `\b` 变退格符）
  见 [[pitfall-completion-main-entity-resolution]]。
- **仍未闭环**：`candidates=0` 在 demo 工程是常态（带 `@TableName` 的实体很少）——JOIN 表名补全要想更全，
  下一步是把「表名 → 实体」这条反查链也用在候选上（用户尚未拍板）。

## 第十二轮（2026-11-13 17:20~）：用户改在日常 IDE 验包，撞上热更新的设置页缺陷

- **验包对象变了**：用户「我这次用的不是沙盒，是日常 idea」，装的是 `MPV-Build 1794561507546`
  （= beta.2，构建 17:18:27，晚于 `866c6e651` 的 17:17:03 ⇒ **两笔补全修复确实在用户包里**）。
  以后给用户的话术按「构建戳对提交时间」判内容，不要让他翻版本号（写法见
  [[pitfall-hot-update-settings-apply-cnfe]] 末节）。
- **新缺陷（环境侧，不是代码）**：热更新后设置页「确定」无反应、只能取消——apply 链实例化
  configurable 命中已卸载的旧 classloader，CNFE 被平台吞掉。**完全退出 IDE 即恢复**；已核实
  `AboutConfigurable` 在 beta.1 tag 源码/plugin.xml/沙盒 jar 三处都在，所以不是打包缺类。
  待补守卫：热更新成功后探测设置页类可实例化，不可用就提示「请重启 IDE 完成更新」。
- **发行进度**：子智能体已完成本地发行预备（`a14569f2a` 版本位+说明+CHANGELOG，
  `78285d0e5` 补两笔补全修复入说明），tag 打在新 tip `866c6e651`；它同时发现
  `release-beta.ps1` 派发不透传 `-Notes`（见 [[pitfall-ci-release-notes-and-publish-run-gate]] §3）。
- **纪律**：多路并行时**同一工作树的构建会重写 sandbox/安装包**，用户正在跑的 IDE 会读到被替换的 jar
  ⇒ 出现「类在包里却 CNFE」。发行/构建期间让用户别起 IDE 验收，落定后再验。

## 第十三轮（2026-11-13 17:30~17:45）：慢的是实体解析冷解，补全仍空但子句已排除

用户「这次点击预览要好久，并且补全还是没效果」，同一份日志给出两条互不相干的读数：

```
17:31:41 ctx detail: entity=3ms resolve=13280ms   ← 慢在这里
17:31:19 [completion] ctx: main=cust_channel columns=19   ← 上下文其实是对的
```

- **慢**：`EntityResolver.resolve` **冷态首解 13.3s**（同会话后续预览毫秒级）。它当时仍在点击同步路径上。
  修法＝打开 mapper 文件、gutter 首次渲染时后台预热一次补全上下文：`warmCompletionContextOnce`
  （`ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project)`，`ConcurrentHashMap.newKeySet`
  按 `VirtualFile.url` 每文件一次，dumb 期跳过并**退回资格**），日志 `[open-perf] ctx warm done in Xms`。
- **补全**：先排掉一个假设——拿用户实测形态写用例 `testSelectListPartialColumnClause`：
  `SELECT …, DEL_`／`SELECT …, DEL_F` 判成 `SELECT_COLUMN`，`… WHERE DEL_F` 判成 `COLUMN`
  ⇒ **子句判定不是原因**（我最初的 UNKNOWN 预期是错的，实测纠正）。
- 整场日志没有任何 `addCompletions` 痕迹 ⇒ 加诊断：`SqlCompletionContributor` 里按 Document 打一条
  `[completion] invoke: ctx=… cols=… candidates=… schemaCols=… editorCtx=… doc=…`（`WeakHashMap<Document, Boolean>`
  当集合，`containsKey` 不用 `add`）。这行能一次分清三种情况：没出现＝贡献者没被调用（注册/语言/编辑器对象不对）；
  出现且 `ctx=-`＝上下文丢了；出现且 `cols=19`＝候选被前缀或元素构造过滤。
- 提交 `acf503f83`（amend 过一次：我 `git add -u` 把 IDE 重复写入的 `.run/runIde.xml` 新块也带进了提交，
  用 `git checkout HEAD~1 -- <file>` + `--amend` 摘掉——同族纪律见 [[feedback-commit-other-lane-dirty-files-judge-by-diff]]）。

**发行侧同期读数**：`插件 CI（release）` 对 `78285d0e5` **success**（run 43400999377）；子智能体又补
`78285d0e5`「`release-beta.ps1` 透传 `-Notes`」与 `07777d55b`「说明补已知问题：热更新后设置页报一次
Cannot create configurable，重启即恢复」；`78285` 的那轮 push-test 被后一次推送按并发组 cancel（不是红）。

**给用户的排查口径**：CI 在 GitHub runner 上跑，与本地起沙盒**不冲突**；会冲突的只有本地
`buildPlugin`/`prepareSandbox`（见 [[pitfall-preparesandbox-locked-by-running-runide]]）。

## 第十四轮及以后：从「不卡」转进「压到极致」（2026-11-13 20:00~11-14 01:00）

本文件只保留到第十三轮。后续「打开预览还是太慢 / 全在后台线程会不会卡 / dumb 无法解决吗 / 性能做到极致」
这一整段的读数、逐笔提交与结论已另立两份，免得本文件继续膨胀：

- [[project-preview-perf-extreme-20261114]] —— 整轮台账（`c96759946` 全表索引、`e99750f53` 建索引离开调用方线程、
  `59e58ab93` EDT 只派发、`ee8c69e9a` 注解索引候选源、`231cd2065` 按 ORM 分源…）与最终读数：
  `resolve` 3737~4737ms → 0~34ms、`request→dialog` 4190/5119ms → 稳定态 8~254ms、索引 8688 表 → 812~1037 表。
- [[pitfall-mapped-exec-file-write-triggers-dumb]] —— 「每次预览都把工程打进 dumb」的机制与三条候选修法。
- [[pitfall-warm-fanout-and-index-entry-churn]] —— 同轮抓到的三处白烧（预热 11 次 / 索引重建 7 次 / 回补每 TTL 重扫）。