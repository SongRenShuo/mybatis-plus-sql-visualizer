# T-PerfSweep33：弹窗 EDT 链与 windowStamps 第十期余额收口

> 来源：《[20261120-0213-IDEA性能影响-性能分析.md](../性能分析/20261120-0213-IDEA性能影响-性能分析.md)》（第十期）§一/§四/§七。第十期是销账兑现期：第八、九期全部余额已随 `t-perfsweep31` 合流清零，本计划只收口第十期新发现的 3 项 master 独立小刀，并承接 hiddenbug 分支合流销账的裁决与复测移交。hiddenbug 合流编排归 T-BranchMergeRoute 主通道，本计划不另开合流线。

## 一、券清单

### 券01（P2）：独立预览弹窗链池线程化

**现状**：`SqlPreviewDialog` 两条入口在 EDT 直跑解析——贴入自动识别在 EDT Timer 回调里同步跑 `StandaloneSqlSourceResolver.resolve`（SqlPreviewDialog.kt:166，reparseDraftFromEditor）；「从文件载入」在 `ReadAction.compute` 内 `VfsUtilCore.loadText` 读整文件（:194-199，EDT IO）后 `reparseText`（:216）同样 EDT 直跑。解析链是纯文本（零 PSI/ReadAction 引用，grep 实证），输入上限 100k 字符（StandaloneSqlSourceResolver.kt:42），最坏 8 次 DOM 解析（双链各 4 候选），EDT 停顿数百毫秒级。

**修法**：照工作台页签既有形态搬（SqlStandalonePreviewPanel.kt:303-311）——

- 解析体挪 `ApplicationManager.getApplication().executeOnPooledThread`，代次守卫（`++resolveGeneration` 捕获比对）+ `invokeLater(ModalityState.nonModal())` 回 EDT 应用结果；
- `loadFileIntoPreview` 的文件读取挪同一池线程（`VfsUtilCore.loadText` 在 BGT 直接可调，不需要 ReadAction；若实施时发现 VFS 就绪性需要读动作，改 `runReadAction` 于 BGT——见待验证前提 1）；
- `pollDraftForReparse`（EDT Timer 回调）保持只读草稿文本与比较，**命中后不再同步解析**，改为提交池线程任务；
- `stopPasteReparse`/`suppressPasteReparse` 抑制语义保持：解析回 EDT 时若已退出待命态按现有「交还手动」口径丢弃或仅回填，不与用户后续编辑打架（对齐现有 `reparseDraftFromEditor` 头部 `pasteReparseArmed` 早退语义）。

**验收**：
- 行为测试：贴入合法 mapper 片段 → 自动识别仍产出预览（既有弹窗行为测试回归全绿）；
- 反向验证（守卫形态）：新增源码扫描守卫（照 `IbatisTypeAliasResolverHotPathTest` 形态），钉 `SqlPreviewDialog.kt` 生产段不得出现对 `StandaloneSqlSourceResolver.resolve` 的裸同步调用（允许出现在池线程 lambda 内——扫描规则按「resolve( 调用必须位于 executeOnPooledThread 块内」或更简单的「文件内禁止 EDT 方法体内直接调用」口径，实施时取可稳定判定的最小形态）；摘掉池线程回 EDT 直跑 → 守卫必红，贴两次读数后还原；
- 大样本手动观察（随批 T0，不阻塞）：≥50KB 风味歧义 XML 贴入弹窗，EDT 无可感停顿。

**定向测试纪律**：只跑本券触达类（弹窗既有行为测试类 + 新守卫类）+ `testFast`；不跑全量、不构建校验、不 `clean`、不 `--stop`。

### 券02（P3）：windowStamps 总帽兜底

**现状**：`SqlLogEntryBuffer.windowStamps`（SqlLogEntryBuffer.kt:96，`HashMap<String, ArrayDeque<WindowStamp>>`）修剪只在 `windowSuspectKeys`（:906-930）与 `clear`（:942）；条目出环淘汰不摘戳（:615，KDoc :80-95 如实登记「窗龄自愈」近似）。捕获开启且面板长期不打开时按捕获速率单调累积（单枚 ~32B）。

**修法**（取最小帽，不改判定语义）：在 `markWindowStampLocked` 入队处加总帽——戳总数（或键数）超过帽值（建议 20_000 枚，约为 maxEntries 上限 × 窗内重复倍数的保守覆盖）时**整体清空** `windowStamps`。清空只丢嫌疑判定一次窗口的精度（下一窗重建），不影响去重/列表/徽标正确性；KDoc 同步补帽值与清空口径。

**验收**：单测——灌入超帽条目（不开面板）后断言 `windowStamps` 容量 ≤ 帽（经内部可见缝或 `windowSuspectKeys` 行为间接断言）；反向（摘帽）用例红。

**关联提醒**：`SqlLogEntryBuffer.kt` 与 hiddenbug 分支券 T（dedupeExisting :730 起）同文件不同 hunk，可自动合并；若本券先行提交，route/hiddenbug 合流时该文件按 §五.3 重核清单走一遍。

### 券03（P3）：EXPLAIN 前热 cacheOnly 对齐

**现状**：EXPLAIN 前热调用 SqlPreviewPanelExecution.kt:1672-1676 未传 `cacheOnly`，吃默认 false——预热仍可能直写 exec 文件，绕过 PerfSweep31 券03a 的内容判等跳写（预览主链已 cacheOnly）。

**修法**：补传 `cacheOnly = true`，与预览主链预热形态对齐（DataSourceSelector.kt:2495-2514 签名已支持）。低频路径，顺手项。

**验收**：既有 ExecFileWriteCountTest 回归全绿；如该测试未覆盖 EXPLAIN 前热路径，补一条「EXPLAIN 前热同内容不写盘」计数断言（反向摘 cacheOnly 实参 → 计数必增）。

## 二、合流通道移交（不在本计划实施，登记裁决待办）

以下两件随 T-BranchMergeRoute 合流发生，本计划只登记：

- **裁决点 W（McpConfigurable.kt）**：master SETTINGS_CHANGE 广播（T-McpGlobalBreakHardening）vs 分支 loadState(copy) 强制持久化，两修法互斥。建议取 master 广播形态（后果「Apply 即时生效」已覆盖在产），持久化强制诉求若真机实证存在再另立小刀。
- **裁决点 Y1（ShareDialog.kt + BrowserUtilConvergenceGuardTest.kt）**：master Task.Backgroundable（ShareDialog.kt:233-251，在产）vs 分支 executeOnPooledThread。建议取 master 版，分支该笔不带入。
- **合流后复测**：hiddenbug 券 D/Q/T 基准读数在合并树复测一轮（PerfSweep32 券03 移交项）；CHANGELOG.md 与架构设计 01/03 文档并账。
- **不带回项**：分支券 Y2 的 SqlPreviewPanel 半（master T-PreviewEditorLeak 有意保留自处置）、券 Y7（分支自身已回退）。

## 三、实施顺序与并行车道

- 车道 A（master 独立，可立即开工）：券01 → 券02 → 券03 同批实施同批提交（三者互不重叠：SqlPreviewDialog / SqlLogEntryBuffer / SqlPreviewPanelExecution）。
- 构建并发上限 2 路，跑 gradle 前过并行登记表（`python C:\Users\USER\.zcode\cli\parallel_tasks.py check`）。
- 长命令一律后台任务启，不 sleep 等待；TodoWrite 与实际进度同步。

## 四、风险与回滚

- 券01 改动弹窗交互时序：解析回 EDT 后「待命态已退出」的丢弃口径要与现有 `suppressPasteReparse` 面板自写抑制配合，防自动解析改写用户正在编辑的文本——行为测试必须覆盖「贴入后立即手动编辑」场景。
- 券02 总帽清空瞬间可能让当窗徽标判定短暂失真（一次窗口）——在 KDoc 与测试注释写明这是接受口径，不做增量摘戳（精确摘除需在淘汰循环反查 structureKey 存活性，复杂度不值）。
- 券03 动的是执行链外围：确认 `prewarmExecTempFileOnBackgroundThread` 的 cacheOnly=true 路径失败时回落冷路径行为不变（KDoc 已声明预热只是优化不是前置条件）。

## 五、待验证前提（实施者先证伪再动手）

1. **「loadText 在 BGT 可直接调」**：`VfsUtilCore.loadText` 对未刷新（not yet valid）虚拟文件在 BGT 是否需要先 refresh/读动作——实施前用工作台链的文件加载路径对照（该链如有同形态调用点，照抄其口径；无则用 `ReadAction.compute` 包裹保平安，EDT 无 IO 即达标）。
2. **「resolve 纯文本零 PSI」**：本期 grep 实证 `StandaloneSqlSourceResolver.kt` 全文零 PsiManager/PsiFile/ReadAction 引用，且工作台在池线程跑同链是旁证；实施者动手前复核一次该文件及其直调的 XmlFlavorDetector/SqlPerfAnalyzer 无新增 PSI 依赖。
3. **「弹窗测试类存在且可断言自动识别行为」**：先 grep 既有 SqlPreviewDialog 行为测试类；若只有工作台面板测试覆盖同链，券01 的行为回归面以工作台测试 + 新守卫类为下限。
4. **「EXPLAIN 前热调用点唯一」**：grep `prewarmExecTempFileOnBackgroundThread` 生产调用点全集（本期实证仅 SqlPreviewPanelExecution.kt:1672 一处），防漏第二处。
5. **「券02 帽值 20_000 不截断正常嫌疑判定」**：maxEntries 默认值 × 窗内同键倍数推算正常会话戳总量上界；若真实窗内需求超过帽值（高重复风暴场景），帽值上调或改按键数帽，以推算为准不在本计划拍死。

## 六、实施记录

> 2026-11-20 主控实施（分支 `t-perfsweep33`，工作树 `.worktrees/t-perfsweep33`，基线 master `ec39a7518`）。四笔提交 + 一笔回填。

- **券03**（`ca1a38a3b`）：EXPLAIN 前热补 `cacheOnly = true`（SqlPreviewPanelExecution.kt）。
- **券01**（`b64e71e43`）：`resolveOffEdt` 池线程计算 + 代次守卫 + `invokeLater(nonModal)` 回 EDT；文件读取随解析一并后台（`ReadAction.compute` 在 BGT 取读锁，**待验证前提 1 取保守解**：保留读动作包裹）；测试缝 `resolveOffEdtDispatcher` 照 `EdtDispatch.invokeLaterOverride` 惯例；新增代次守卫竞态用例。**实施超出计划两处（按死代码纪律顺手收口）**：①删除只写不读的死状态 `suppressPasteReparse`；②`reparseText` 在统一链后失去全部生产调用方，已删除，竞态测试改连发两次真实入口 `reparseDraftFromEditor`（更贴近真实贴入竞态）。
- **券02**（`7b318a5d1`）：双帽兜底——**帽值较计划 §五.5 的 20_000 枚口径收紧**为「单键 64 + 键数 1024」（键帽 × 单键帽 = 65_536 枚 ≈ 5MB 最坏界；单键丢最旧保最近段，阈值 5 ≪ 64 检测能力不丢）；新增 `windowStampShapeForTest()` 访问器。
- **守卫**（`54d1103f1`）：`SqlPreviewDialogOffEdtResolveHotPathTest` 钉两券形态（单点路由 + cacheOnly 实参），合成样本自证防空绿；命名与函数形态按元守卫口径免 GUARD_CLASSES 登记（同 IbatisTypeAliasResolverHotPathTest 先例）。
- **定向读数**：首轮 8 类 102 例 0 红（DialogPasteReparseTest 4、SqlPreviewDialogTest 12、SizeTest 3、守卫 4、NplusOneWindowBufferTest 10、SqlLogEntryBufferTest 60、ExecFileWriteCountTest 7、ExecTempFileEdtGuardTest 2，XML tests= 逐一核对无 pattern 漏跑）；删码后增量重跑 2 类 8 例 0 红；终态全量定向（上表 8 类）在探针还原后复绿。
- **反向验证**：三处生产码同时撤修复（入口直调 resolve / 双帽摘除 / cacheOnly 摘除）→ **恰 4 红**（守卫 2 红：「调用点应恰 1 处（现 2）」「EXPLAIN 前热调用缺 cacheOnly = true」；帽测试 2 红：「单键戳队列应被帽住（≤64，现 100）」「键数应被帽住（≤1024，现 1100）」）→ 还原复绿（gradle exit=0，4/0 + 10/0）。注意：探针轮 Bash 管道末命令退出码恒 0 的坑再次出现，判定以 XML 计数为准。
- **CHANGELOG**：`[Unreleased]` 新增 Changed 三条（一句一条带哈希），随文档提交落账。
- **not run 声明**：全量门禁未跑（交 CI 收口）；runIde/GUI 验收未跑（弹窗贴入识别的真机观感归用户验收，判据＝贴入大片段无可感停顿）；k2j 端口分支同步未做（该分支按发行 tag 冻结节奏另批同步）。

