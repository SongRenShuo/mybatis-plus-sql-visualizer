# T-PrewarmWilDeadlock 开发计划：预览预热写意向锁自锁卡死修复

- 日期：2026-10-15
- 任务码：T-PrewarmWilDeadlock
- 工作树：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-prewarm-wil`（已删除，实施完毕）
- 分支：`t-prewarm-wil-deadlock`（自 master@cf093188，已 ff 并入 master `ee828397`，分支已删）
- 状态：已实施、已验证、已合 master（实施记录见文末）
- 合并说明：本档吸收了同日另一会话的排查档 `20261015-1830-T-SqlPreviewPrepareFreeze`（原文件已删除）。
  该档立项早于线程转储到位，其主嫌疑（schema 遍历长读挡写锁）后被证伪；其中仍有长期价值的
  现象取证、日志画像、证伪裁定与候选优化收录为附录 A，原档不再单独存在。

## 1. 现象与证据

用户真机（IU 2026.2.1，JBR 25）打开 SQL 预览弹窗后，右侧恒停「正在准备预览...」，稍后 IDE 弹
「IntelliJ IDEA is not responding」。用户提供 `threadDump-20261015-181620`，三段关键栈：

1. **EDT（AWT-EventQueue-0）**：`EditorImpl$MyMouseAdapter.mousePressed → EditorThreading.runWritable
   → WriteIntentReadAction.run → NestedLocksThreadingSupport.acquireWriteIntentPermit`——排队等**写意向锁**，
   进平台 Suvorov「等待写锁」覆盖层（即 not responding 横幅 + 本转储的触发者）。（转储 L1-61）
2. **池线程 756（ApplicationImpl pooled thread 756）**：
   `SqlPreviewService.previewAsync → DataSourceSelector.prewarmExecTempFile → prewarmExecTempFileInner
   → WriteIntentReadAction.compute { LocalFileSystem.refreshAndFindFileByIoFile }
   → RefreshSessionImpl.launch → RefreshQueueImpl.execute → RefreshSessionImpl.waitFor`，TIMED_WAITING 永久驻留。
   （转储 L1127-1200）
3. **池线程 733**：`SqlPreviewPanel.prewarmPreviewTempFile` 裸调同一 `refreshAndFindFileByIoFile`，
   同样卡在 `RefreshSessionImpl.waitFor`——它是受害者（无锁在身），EDT 被卡死后其刷新会话永远无人推进。
   （转储 L1201-1263）

## 2. 根因

`prewarmExecTempFileInner`（今 `DataSourceSelectorResults.kt`）在**后台线程**上以
`WriteIntentReadAction.compute` 包裹同步 `refreshAndFindFileByIoFile`：

- 该线程由此持有写意向锁（WIL），随后阻塞等待 VFS 刷新会话完成；
- 262 平台 `RefreshSessionImpl.launch` 从 BGT 恒走「异步会话 + waitFor」（转储中持锁者与裸调者两条栈
  都停在 waitFor，实证非调用线程同步处理）；
- 会话推进需要 WIL 空闲（及/或 EDT 推进），而 WIL 被等待者自己持有——自锁；用户此时在预览编辑器上点击，
  EDT 也排进等 WIL 队列，互等扩大为全 IDE 冻结。

**引入史**：T-ExecCopyFix F2b（642d73ba）为修「262 平台 EDT + plain ReadAction 内同步 refresh 硬抛
write thread only」，给 **EDT 冷路径** `createTempPsiFile` 的 refresh 段包了 WIRA——该语境安全（EDT 持
WIL 时刷新会话在调用线程同步处理）。随后 T-ExecSlowOps（c27ff317）把预热链整体搬到 BGT，从冷路径
**同款复制**了 WIRA 包装（`git show c27ff317` L3164-3170 实证预热链生而带 WIRA），自锁自此埋下。
243 定向测试跑不出 262 NestedLocksThreadingSupport 语义，故从未暴露。

## 3. 待验证前提

- 前提 A：262 平台 BGT **裸调** `refreshAndFindFileByIoFile` 不硬抛。——已被转储证实（733 线程裸调走
  waitFor 而非异常栈）；同函数 pre-refresh 段的裸调 `findFileByIoFile` 亦有「prewarm DONE 日志实证 BGT
  可调」先例。✅ 已验证。
- 前提 B：EDT 冷路径 `createTempPsiFile` 的 WIRA 包装必须保留——它修的是 262 EDT 读权限硬抛，
  且 EDT 语境无自锁。本轮不动它。✅（F2b 实机先例）
- 前提 C：用户点击不是死锁必要条件——756 持 WIL 等「需要 WIL/EDT 的会话」本身即环；点击只是让 EDT
  排队等锁、亮出 not responding。修复后即使无点击，预热链也应正常走完。留真机复验确认。

## 4. 实施方案

**W1（根因，单点最小修复）**

1. `execute/DataSourceSelectorResults.kt` `prewarmExecTempFileInner`：
   - refresh 段摘除 `WriteIntentReadAction.compute` 包装，改裸调 `LocalFileSystem.getInstance()
     .refreshAndFindFileByIoFile(ioFile)`（与同函数上方 pre-refresh 段裸调同形态）；
   - 原 `// T-ExecCopyFix F2b：refresh 段 WriteIntentReadAction（BGT 上 WIRA 合法…）` 注释改写为
     T-PrewarmWilDeadlock 死锁说明（引用本计划与转储证据），防止后人再「同款复制」回去；
   - 异常处理与 fatal 透传维持原状（裸调同样可抛，catch 链不变）。
2. 全仓 WIRA 站点复核后**明确不动**：`createTempPsiFile`（EDT 冷路径）、`DataSourceSelectorConsole`
   attachConsole（EDT、无 sync refresh）、`DatabaseConsoleLauncher.createTempFile`（EDT 裸调）、
   `SqlPreviewPanelEditorLifecycle.prewarmPreviewTempFile`（BGT 裸调，本事件中的陪葬者非根因）。

**W2（有界预热兜底，用户 2026-10-15 当日追加：超时自动终止）**

W1 摘环后本路径不可能再自锁，但「同步 refresh/readAction/EDT 派发等平台等待因其他原因挂死」这类
同族风险仍存在。预热是投机性增强（previewAsync 侧 fire-and-forget，弹窗冷路径本就能按需重建），
故给 exec 预热整链加 30 秒预算：

- `execute/DataSourceSelector.kt` `prewarmExecTempFile`：真实链 `prewarmExecTempFileInner` 改经
  `AppExecutorUtil` 独立执行 + `future.get(30s)` 预算等待；超时 `cancel(true)`（中断，
  `RefreshSessionImpl.waitFor` 内部的 runBlockingMaybeCancellable 通常响应中断）放弃本轮并 WARN，
  **在途标记在 finally 照常释放**——否则一次挂死会让 `execPrewarmInFlight` 恒 true，后续预热全部
  skip，预热能力静默报废；
- ExecutionException 拆包：fatal 原样上浮（T-FatalPassthrough 语义与直跑一致），非 fatal 仅 warn；
- 边界声明：cancel 若因平台内部不可中断而未真正唤醒滞留线程，业务也已脱困（不再等待、标记已释放），
  滞留线程无 WIL 在身、无 EDT 阻塞面，仅占一条池线程；
- 面板侧预热（`prewarmPreviewTempFile`）不加同款预算：其消费侧已有 `PrewarmAwait` 预算超时降级，
  生产者滞留仅占池线程不可见面。

## 5. 守卫测试

新增 `convention/SyncRefreshWriteIntentGuardTest`（复用 BannedApiScanSupport 的 maskNonCode 等基建）：

- 规则一（负向）：`prewarmExecTempFileInner` 函数体（涂白注释/字符串后）不得含 `WriteIntentReadAction`；
- 规则二（正向）：`createTempPsiFile` 函数体须同时含 `WriteIntentReadAction` 与
  `refreshAndFindFileByIoFile`——防未来「清理」把 262 EDT 冷路径修复也顺手摘了；
- 反向验证：临时把 WIRA 包回预热链 → 守卫必须红（贴读数）；还原 → 绿。空绿不算数。

## 6. 验证阶梯

1. 定向：`ExecDocSyncTest` + `SyncRefreshWriteIntentGuardTest`（跑前 `parallel_tasks.py check` + register）。
2. 守卫自证用例随测试类提交。
3. 真机复验（用户侧，不在本轮门禁）：冷启动 IDE 后首开预览弹窗（必走真实预热链），「正在准备预览...」
   应正常出 SQL；IDE 不再弹 not responding。

## 7. 非目标

- 不改预览面板路径的裸调 refresh（平台合法形态）；
- 不做「findFileByIoFile 命中即跳过 sync refresh」之类的优化（本任务的锅是 WIL 自锁，不是 refresh 慢）；
- 不给面板侧预热生产者加预算（消费侧 PrewarmAwait 已有界）；
- K2J 端口分支按既有 T-SyncK2JMaster 机制在下一轮同步，不在本任务内移植。

## 实施记录

2026-10-15 当轮完成，W1+W2+守卫全部落地（工作树 mybatis-plus-sql-visualizer-prewarm-wil）：

- W1：`DataSourceSelectorResults.kt` `prewarmExecTempFileInner` refresh 段摘除 WIRA 包装改裸调，
  首部注释改写为死锁说明（引用 threadDump-20261015-181620）。
- W2：`DataSourceSelector.kt` `prewarmExecTempFile` 改独立执行 + 30s 预算（`EXEC_PREWARM_BUDGET_MS`），
  超时 cancel+放弃+照常释放在途标记；ExecutionException 拆包 fatal 透传/非 fatal warn；
  新增常量紧邻 `execPrewarmInFlight`。中途一次编译红：`AppExecutorUtil` 包名误写
  `com.intellij.util.`（正解 `com.intellij.util.concurrency.`，与 IdeErrorReportSubmitter 既有用法对齐后过）。
- 守卫：新增 `convention/SyncRefreshWriteIntentGuardTest`（7 用例：规则一/二/三 + 4 条合成样本自证），
  判定内核 `syncRefreshInsideWiraOffenders` 已登记元守卫 GUARD_CLASSES。
- 验证读数（`test --tests` 定向，`--no-configuration-cache`）：
  - 修复态：SyncRefreshWriteIntentGuardTest **7/0/0**、GuardSelfTestCoverageMetaGuardTest **8/0/0**、
    ExecDocSyncTest **14/0/0**；
  - 反向验证（WIRA 装回预热链）：同 7 用例 **2 failures**——恰规则一 + 规则三（规则三精确报
    DataSourceSelectorResults.kt:1218），其余 5 用例（含规则二与全部自证）保持绿，判别力精确；
  - 还原后复跑：7/0/0 + 8/0/0 绿（读数落在将要提交的确切树上）。
- 临时沙盒（MPSV-PrewarmWil，build.gradle.kts 临时行）已按协议还原，未入库。
- 待真机复验（用户侧，262）：冷启动 IDE 首开预览弹窗应正常出 SQL、不再 not responding；
  极端挂死场景 30s 后 WARN 放弃（日志关键字 `[M36] exec prewarm exceeded`）。
- 未做（本计划 §7 非目标）：面板预热生产者预算、K2J 移植（下一轮 T-SyncK2JMaster 同步）。

## 附录 A：排查过程与真机取证（自 T-SqlPreviewPrepareFreeze 排查档合并）

### A.1 现象与用户环境（真机取证，非猜测）

- 用户 IDE：IU 2026.2（262.10968.63），同实例开双项目（业务 cyzc-platform + 本插件仓）；
- 实装插件为 **2026.10.13 发行版**（`%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\...`，2026-10-15
  12:39 装机重启）——嫌疑代码集合以 2026.10.13 发布内容为界，dev 未合并内容（O-146/147/148/149）
  不在其列；本根因（WIRA 包装）恰在该版本在役；
- 冻结形态：参数面板已渲染、右侧恒停「正在准备预览…」、底部 not responding 横幅——占位未被装配
  回调换掉，说明 EDT 已跑过 showResult 大部分、冻结发生在装配回调能执行之前。

### A.2 当日日志画像（idea.log，2026-10-15）

- 12:42~12:43 三次预览打开均正常完成（预热 106~215ms，`createEditor`/`prewarm done` 埋点齐全）——
  与本死锁不矛盾：自锁需要「exec 预热缓存未命中走真实链」的窗口，三次成功说明当时代价小或走了缓存；
- 12:40:16 freeze 检测目录存在但为空（IDE 重启后 52 秒、无预览参与）——**独立事件**（项目重开+索引
  窗口），不计入本插件，也非本次冻结；
- 12:38/12:45 两条平台日志 `checkCancelled is invoked while write-action is pending`——写动作排队旁证；
- 历史冻结（10-13 两份、10-14 一份转储）均系第三方插件（CoolRequest / GitStashTracker），与本插件
  无关，但说明该机第三方有让 EDT 长期阻塞的前科（环境画像，参与共谋评估时须对照）。

### A.3 主嫌疑证伪裁定（防重启旧排查线）

排查档的主嫌疑：`DatabaseSchemaResolver` 两处无指示器 `ReadAction.compute` 的 DasModel 全量遍历
（多数据源 × 大 schema 冷缓存可达秒级）挡住 EDT 写动作——写锁饥饿链。

**裁定：证伪**。threadDump-20261015-181620 显示 EDT 等的是写意向锁，挡住它的是 exec 预热线程
（756）持 WIL 自锁，与 schema 遍历读无因果。排查档「EDT 在等写锁」半句方向正确（转储 EDT 栈顶正是
`acquireWriteIntentPermit`），「谁占着写锁」答错。两条待证事实随档撤销：262 无指示器读的可取消边界、
用户真实 schema 规模实测——除非重启 A.5 第 1 条候选优化，否则不再需要。

### A.4 取证方法教训

冻结横幅的「Dump threads Ctrl+D」可能只建目录不出转储（12:40:16 即此形态）；拿不到产物时改用任务
管理器取 PID + `jstack <pid>`。排查档立项时因缺 EDT 栈只能推嫌疑链并自设「栈未取得不得定案」闸门，
转储到位后当场定案——「先拿栈再定案」纪律再次生效。

### A.5 候选优化留档（与本冻结无因果，未排期）

1. `DatabaseSchemaResolver.tableMeta/tableMetas` 长读可让路化（`ReadAction.nonBlocking` 优先 / 分片
   短读 + 仓内既有 `withScanBudget` 接线）；实施前需以 243/262 sources 复核「无指示器 ReadAction
   不可被 pending write 取消」的准确边界，243 缺 API 时回退分片方案；
2. `onNativeEditorReady` 控制台就绪补刷与首拍 schema 预热的重复全扫收敛（首轮 `resolved==0` 才补刷）；
3. 观测埋点：EDT 装配回调拿写锁的等待耗时、每次全量遍历的耗时/数据源数/元素数、控制台准备链各步耗时
   （用户下次报障可直接凭 idea.log 定案）。
