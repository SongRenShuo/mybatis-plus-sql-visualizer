# T-UserFreezeTriage：用户报障「IDE 未响应」的归因与护体开发计划

- **立项**：2026-11-19 14:59。起因＝用户转发来的一份 IntelliJ 线程转储（`threadDump-20261119-145226-1795071146956`，16328 行），报「点发送后 IDE 卡住不动」。
- **红线**：本册不改本插件任何功能语义（只做护体与可观测）；不 push 远端、不动 `update/update.json`；dump 本身不入库（含第三方工程名、第三方插件类名），需要留档放 `.local/reports/`（`.local/*` 已忽略）。
- **一句话结论**：与本插件无关。真凶是 IDE 自带「重复代码片段」检查（`com.jetbrains.clones.DuplicateInspection`）在检查器读动作里做全工程词条搜索；Cool Request 在发请求前同步等 EDT，把这段等待放大成用户可感的「点了没反应」。

## §一 迹证（dump 逐段解剖）

### 1.1 环境

| 项 | 读数 | 出处 |
|---|---|---|
| IDE | IntelliJ IDEA 2025.3.3（新锁模型 `NestedLocksThreadingSupport`） | dump 内 `IntelliJ IDEA 2025.3.3` 共 2 处 |
| JBR | `java.base@21.0.10` | 全栈 |
| 线程 | 142 个（RUNNABLE 50 / WAITING 56 / TIMED_WAITING 36） | 按 `^"` 计数 |
| JobScheduler | 15 个 FJ 线程，**只有 2 个**（`9/15`、`4/15`）在忙，其余 13 个 waiting | 行 80 / 230 / 2712~2808 |
| 同机第三方插件 | JRebel（`rebel-*` 线程）、Cool Request（`dev.coolrequest`）、本插件（`cn.song.mybatisplus.visualizer`） | 线程名与栈 |

### 1.2 冻结主链：EDT 卡在「升级写锁」

`AWT-EventQueue-0`（行 1~78），自底向上的关键帧：

```
CoolRequestEditorTextPage.applyText:95          ← runWriteCommandAction 包住 document 写
  CoolRequestEditorTextPage.setText:75
    MainBottomHTTPResponseView.lambda$clearResponseAndWait$4:313
      ← ApplicationImpl.runWriteAction → prepareWriteFromWriteIntentBlocking（要写锁）
        ← ComputationState.upgradeWritePermit
          ← runSuspendMaybeConsuming（让出并等读锁释放）
            ← SuvorovProgress.showNiceOverlay（就是那个「未响应/冻结」浮层）
              ← EventStealer.waitForPing（等 ping 期间还在泵事件，随后触发 dump）
```

读法：EDT 上做写动作是新锁模型下的正常路径，**但写锁要等所有非 EDT 线程的读锁释放**。这里等不到，于是卡在升级写锁，浮层弹出来、性能监控落 dump。

### 1.3 拖住写锁的：IDE 重复代码检查做全工程词条搜索

`JobScheduler FJ pool 9/15`（行 80~228）与 `4/15`（行 230~375），**两条栈同源**（只是停在不同层：一条在 `ContainerUtil$8.get`，一条在 `UtilKt.tail`），自底向上：

```
InspectionRunner.lambda$inspect$8 → tryRunReadAction          ← 检查器在读动作内
  InspectionProblemHolder.visitElement → DuplicateInspection.checkFile:90
    DuplicateInspection.createProblemDescriptor:102            ← 逐 descriptor 造问题描述
      JavaDuplicateScope.createQuickFixes:163                  ← 顺手把 quickfix 也算好
        ExtractDuplicatedJavaMethodFix$Companion.isExtractable:35
          ExtractMethodHandler.getProcessor → ExtractMethodProcessor.prepare:278
            chooseTargetClass:1921 → applyChosenClassAndExtract:2017
              InputVariables.<init>:47 → wrapInputVariables:95
                Query.asIterable → findAll                            ← 全工程引用搜索
                  SemElementRenamePsiReferenceSearcher.processQuery:114
                    PsiSearchHelperImpl.bulkProcessElementsWithWord / LowLevelSearchUtil.processOffsets
                      UrlPath.isCompatibleWith → UrlPathReferenceUnifiedPomTarget.equals
```

读法：`Duplicated code fragment` 检查为「提取重复方法」这个 quickfix **预先算可提取性**，而算它要建 `ExtractMethodProcessor`，一路走到 `InputVariables` —— 那里做的是**全工程词条搜索 + 逐命中 PSI 比较**（`bulkProcessElementsWithWord`），且整段都在读动作里。工程越大、重复片段越多，越久。这是 JetBrains 侧的性能形态（`ExtractMethodProcessor.prepare` 本不该出现在检查器路径上），不是任何人的配置错误。

### 1.4 连带阻塞：发送流程同步等 EDT

`ApplicationImpl pooled thread 130`（行 1489~）：

```
waiting on EventQueue$1AWTInvocationLock
  SwingUtilities.invokeAndWait:1480
    MainBottomHTTPResponseView.clearResponseAndWait:312
      MainBottomHTTPResponseView.beginSend:242
        RequestContext.beginSend:237
          RequestManager$HTTPRequestTaskBackgroundable.run:238
```

读法：发请求的后台任务先调 EDT 清空响应区（`invokeAndWait`，**无超时**）→ EDT 正卡在上述写锁上 → 后台任务永久挂住 → 请求根本没发出去。用户看到的是「点发送没反应」，与 1.2 的浮层同时发生。

### 1.5 锁等待规模（不夸大）

全 dump 里 `RunSuspend` 等锁只有 2 处：EDT（行 13）、`DefaultDispatcher-worker-10`（行 1547/1553~1554）。即**瓶颈是那两个长读动作，不是线程风暴**。

### 1.6 本插件在场形态（四个线程，全空闲）

| 线程 | 停在哪 | 是否持锁 |
|---|---|---|
| `mps-agent-premain-50650`（行 1184） | `AgentManager.acceptPremain`（AgentManager.kt:1001）→ `ServerSocket.accept` | 否 |
| `mps-agent-session-3036`（行 1202） | `AgentSession._init_$lambda$5`（AgentSessionSupport.kt:456）→ `DataInputStream.readInt` | 否 |
| `mps-agent-manager`（行 2045） / `mps-agent-monitor`（行 2352） | condition wait | 否 |

协程 dump 段（行 4582~4583）里本插件只有两个 `supervisor:ChildScope{Active}` 空壳（Application 级、Project 级各一），无活跃 Job。**EDT 栈与两条 FJ 栈里没有任何 `cn.song.mybatisplus.visualizer` 帧。**

## §二 归因判定

| 角色 | 是谁 | 证据 |
|---|---|---|
| 根因（谁把时间拖长） | IDE 自带 `Duplicated code fragment` 检查，在检查器读动作里为 quickfix 做全工程词条搜索 | 1.3 全栈；两线程同源 |
| 触发放大（谁把等待变成用户可感卡顿） | Cool Request 发请求前 `clearResponseAndWait` → EDT 写 + 后台 `invokeAndWait` 无超时 | 1.2、1.4 |
| 无辜方 | 本插件（Agent 侧 socket 线程空闲；未持有读锁/写锁） | 1.6 |

补充判断：**即便 Cool Request 那处改成不等待，浮层与界面无响应仍然会出现**——因为任何 EDT 写（编辑器输入、保存、面板刷新）都要等那两把读锁。Cool Request 的修法只能救「请求发不出去」，救不了界面假死。真正降痛的是让重复代码检查别在这条路径上做全工程搜索。

另：本次 dump 里**没有**出现我方 `mpsql-exec.sql` 写盘引发的 reparse 痕迹（无 `PushedFilePropertiesUpdater` / `Reparsing` 帧；`DumbMode` 只出现在调试器 `InvokeThread.ignoreDumbMode` 与 `DumbModeWhileScanningTrigger` 这类常驻订阅 scope 名里，不构成「正在 dumb 模式」的证据），不构成叠加因素。

## §三 我方待办（本仓，不改语义）

### P0-1 后台→EDT 阻塞等待统一为「有界」（预计半天）

现状清点：`src/main` 里真实 `invokeAndWait` 调用 2 处，均**无超时**；其余 BGT→EDT 桥接已是有界先例，照它对齐即可。

| 站点 | 现状 | 处置 |
|---|---|---|
| `ui/SqlPreviewPanel.kt:941` | `if (app.isDispatchThread) update.run() else app.invokeAndWait(update)`，无超时 | 改 `invokeLater` + 有界 latch（或直接不等：该回调只是重绘分流展示） |
| `update/DynamicInstallSupport.kt:339`（`edtDispatcher` 默认实现 `:361`） | `invokeAndWait({ captured.set(ModalityState.current()) }, any())`，无超时 | 有界等待，取不到退 `ModalityState.nonModal()`（`chooseDispatchModality` 已有该分支） |
| **有界先例（对齐口径）** | `execute/DocWriteContext.kt:151`、`execute/DialectPreMapping.kt:211/349`、`ui/SqlPreviewPanelEditorLifecycle.kt:466` KDoc | 统一 `await(budget, MS)` + 超时降级 + 一行日志 |

验收判据：外部长读锁存在时，我方后台线程在预算内必然脱身，日志出现降级行。

### P0-2 屏蔽「整文档重写」类写动作（预计半天）

`runWriteCommandAction` 全仓 34 处，其中会整篇 `setText` 的（整文档替换会触发全量 reparse/高亮重算，正是 1.2 里那条昂贵写动作的同类形态）：

| 文件 | 行 | 形态 |
|---|---|---|
| `ui/SqlPreviewPanelSqlView.kt` | 872 | `existing.document.setText(text)`（预览刷新） |
| `ui/SqlPreviewPanelExecution.kt` | 500 / 1367 | `doc.setText(sql)` / `doc.setText(rawSql)`（切语句/回填） |
| `ui/SqlPreviewPanelEditorLifecycle.kt` | 438 / 775 | 占位清空 / 装配填充 |
| `execute/DataSourceSelector.kt` | 1113 / 2072 | 清 history 文档 / 写临时文件文档 |
| `execute/DataSourceSelectorResults.kt` | 856 / 1047 | 写 SQL 编辑器 / 写控制台文档 |
| `ui/ParamImportDialog.kt` | 191 / 220 / 256 | 导入填充 / 清空 / 回填 |
| `ui/RollbackSqlDialog.kt` | 987 / 989 | 回滚 SQL 填充 |

处置：① 文本与当前内容相等直接短路（幂等）；② 长度超阈值（建议 64KB）改 `replaceString(0, len, text)` 单次写并显式标注，避免二次全量重建；③ 清空语义用「删到空」而非再 `setText("")`（等价但少一次内容相等判定成本）。**不做**：把写动作挪到 BGT（平台禁止，见全局 262 Document 两坑）。

### P0-3 写动作内禁止阻塞（已成立，补守卫）

写入点里不得出现 `await`/`invokeAndWait`/IO。巡检 `execute/DataSourceSelector.kt:1112`（`clearConsoleHistory`）这类被 Swing Timer 或后台线程调用的写点；有则拆成「BGT 备数据 → EDT 只写」。建议加一条源码守卫用例（grep 式）钉住 `runWriteCommandAction` 块内不得出现 `await(`／`invokeAndWait`。

### P1 报障自助解析（预计 2 小时）

把本次读法固化成 `docs/支持/线程转储自查-怎么看是不是本插件.md`，可照抄三步：

1. `grep -c 'cn.song.mybatisplus.visualizer' <dump>` —— 只看命中的线程名（`mps-agent-*`）。
2. 在 `AWT-EventQueue-0` 段与所有 `JobScheduler` 段里 `grep` 本插件包名 —— 命中才可能与本方有关。
3. 看 EDT 是否停在 `prepareWriteFromWriteIntentBlocking` / `RunSuspend`：是则问题在「谁长时间持读锁」，去看那两个 FJ 段栈顶包名（本次是 `com.jetbrains.clones`）。

止此即可下结论：**本方线程空闲 + EDT 栈无本方帧 = 无责**，把时间花在找持读锁的第三方。

### P2（需拍板，不默认做）

面板首次打开时若检测到「重复代码片段」检查开启且工程规模超阈值，出一次性提示引导关闭。风险是多嘴、招烦；且该检查默认是否开启尚未核实（见 §五.1）。

## §四 给报障用户的回复稿（中文，可直接发）

> 您好，dump 我看过了，**不是本插件导致的**，也不是任何一方配置错了，是 IDEA 的一个性能问题形态。依据有三条：
>
> 1. 线程转储里本插件只有 4 个线程（`mps-agent-*`），全部停在 socket 等待数据上，没有持有任何锁；
> 2. 界面卡住的那个线程（EDT）的调用栈里没有任何本插件的代码，它卡在「等写锁释放」；
> 3. 真正长期占着锁的是 IDEA 自带的**「重复代码片段」检查**（`Duplicated code fragment`）：它在代码检查里顺手把「提取重复方法」这个修复预先算了一遍，而这一步会做**全工程引用搜索**，工程越大越慢，期间界面上的任何写操作（打字、保存、面板刷新）都得排队等它。
>
> 同时您应该也在用 Cool Request：它发送请求前会先同步清空响应区并等界面线程，界面线程正排在那个检查后面，所以表现成「点了发送没反应，请求也没发出去」。
>
> 降痛办法（按见效顺序）：
> 1. `Settings → Editor → Inspections`，搜索 `Duplicated code fragment`（中文界面：重复代码片段），把它关掉；或保留但把 Scope 收窄到当前文件。
> 2. 若工程里有 `node_modules`、`dist`、`target` 这类大目录，在 `Settings → Editor → Inspections → Scope` 或 `Project Structure` 里排除掉（本 dump 里有 `NodeModulesIndexingRulesProvider` 活动、工程名带 `_vue`，像是含前端目录的混合工程，这类目录会被全工程搜索扫到）。
> 3. 升级到 IDEA 2025.3 的最新补丁版（该检查的 quickfix 预计算属于 JetBrains 侧问题，补丁版可能已优化）。
> 4. Cool Request 侧可反馈「发送前 `clearResponseAndWait` 用 `invokeAndWait` 无超时会挂住发送链路」，让他们改成不阻塞。
>
> 如果再复现，麻烦给三样：新的线程转储、当时是否刚点过发送、以及上述检查的开关状态，我可以再确认一遍。

## §五 待验证前提（先证伪再动手）

1. `com.jetbrains.clones.DuplicateInspection` 在 2025.3.3 的**默认开启状态与默认 scope**（反编译 jar 或新建工程实测）。若默认关闭，则 §四 的话术要改成「您手动开过」。
2. `JavaDuplicateScope.createQuickFixes:163` 是否在**逐 descriptor 的循环内**（栈显示 `createProblemDescriptor:102` 在 `checkFile:90` 内，但需 2025.3.3 反编译确认是否每个重复片段都算一次）——决定用户侧「关掉检查」是否是唯一有效手段。
3. `ui/SqlPreviewPanel.kt:941` 的 BGT 分支实际来路（settings 广播通常在 EDT）；若不是用户可感路径，P0-1 只做有界化，不做结构改造。
4. 「整文档 `setText` 比 `replaceString` 贵」需本地实测（造 200KB SQL 对比 reparse 耗时），实测不显著则只保留幂等短路，不硬推改造。
5. dump 中 IDE 2025.3.3 是否已在已知修复之后的补丁号（对照 JetBrains issue 需联网，本机代理下用 `curl -x` 抓取）。

## §六 验收清单（可照抄）

| # | 用例 | 期望 |
|---|---|---|
| A1 | 注入「EDT 阻塞 2s」的测试缝驱动 `SqlPreviewPanel` 设置广播 → 我方调用线程在预算内返回 | 返回且日志出现降级行；反向（改回无超时 `invokeAndWait`）用例红 |
| A2 | 200KB 文本 `setText` vs 幂等短路：同文本二次写 | 二次写不产生文档变更事件 |
| A3 | `docs/支持/` 三步法在本次 dump 上复现 | 命中 4 个 `mps-*` 线程；EDT 段 0 帧本方；FJ 段栈顶 `com.jetbrains.clones` |
| A4 | §四 回复稿事实核对 | 三条依据逐一对应 dump 行号（1.2/1.4/1.6） |
| B1 | 定向测试（新增用例类 + `SqlPreviewPanelEditorLifecycle` 相关类） | 0 failed；全量未跑＝not run 声明 |
| C1 | 真机 GUI 验收（用户，可选） | 用户点头 |

回退开关：本次改动均为「等待有界化 + 幂等短路」，出问题按单点回退，不新增 Registry。

## §七 实施纪律

- 本册为立项，实施另起分支 `t-userfreeze-triage`（自 master 起），工作树 `.worktrees/t-userfreeze-triage`。
- 跑 gradle 前 `python C:/Users/USER/.zcode/cli/parallel_tasks.py check` → `register --name t-userfreeze-triage --sandbox t-userfreeze-triage`，跑完 `done --name t-userfreeze-triage`（禁裸 done）；构建并发上限 2 路。
- 只跑定向测试（`--tests "<FQCN>"`），禁全量、禁构建校验、禁 `clean`、禁 `--stop`。
- 每完成一件可提交的就立即提交；只 add 明确路径禁 `-A`；`src/main` 用户可感改动随提交回填 `CHANGELOG.md` 的 `[Unreleased]`。
- 报告须回答：定向读数、反向读数、提交哈希清单（按时间序）、收口时树是否干净。

## §八 实施记录

（待实施后回填）
