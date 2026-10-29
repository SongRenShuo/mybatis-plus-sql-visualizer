---
name: project-enhanced-capture-default-on
description: T-EnhancedCaptureDefaultOn（2026-10-19 已实施、全量收口、沙盒验收过）——默认开本已成立，真正拦人的是
  enhancedCaptureConfirmed 与五处确认入口；拆闸门落点、兼容归一、读数、testFast 分桶复盘与 243 沙盒验收链
metadata:
  node_type: memory
  type: project
  originSessionId: session_c796a179045839a6ccdc8c7ff5d1147c
---

# 增强捕获默认开启、移除首次注入确认（T-EnhancedCaptureDefaultOn）

**用户令（2026-10-19）**：「增强捕获默认开启，不再首次开启时提示用户弹窗」，并要求先出计划；计划拍板后「按建议实施」，**真机验收由用户自己做**；随后用户回「testShards 可以跑」。

## 关键前提（不要改错地方）

`PluginSettings.State.enhancedCaptureEnabled` **早已默认 `true`**（2026-09-16 定案）。让用户看到首次弹窗的是 `enhancedCaptureConfirmed = false` 与围绕它的确认流。所以本任务不是「改默认值」，而是**拆掉知情同意闸门**——「开关打开」成为唯一授权语义。

## 五处确认入口（收口清单）

1. 项目打开：`LogStartupCapture.execute` → `AgentManager.maybeAskConsentAtStartup()`
2. 首次 Run 准备期：`PremainRunConfigurationExtension.updateJavaParameters` → `ensureConfirmedBeforePremain()`
3. 应用进程出现：`AgentManager.onProcessAppeared` → `requestConsentThenAttach()`
4. 设置变更上升沿：`AgentManager` 的 `SETTINGS_CHANGE_TOPIC` 监听内确认块
5. 设置页勾选 / 「重新授权」按钮 / 缺源码引导：`PluginSettingsPanelSections`、`PluginSettingsPanel`、`SourceCaptureGuide.enableEnhancedCapture`

删除面：`config/EnhancedCaptureConfirmDialog.kt`（含 `EnhancedCaptureConsent` 三态枚举）、拒绝冷却 `enhancedCaptureRejectedAtMs` 语义、去重窗 `consentAskGuardMs`/`lastConsentAskAtMs`、`needsReauthorization`、`isEnhancedConfirmed`、状态行「未授权/冷却中」两态与 `reauth` 按钮。`PremainInjection.decide` 由四闸门降为三闸门（`enhancedEnabled`/`agentJarAvailable`/`port`）。

**别删错**：`onCaptureConfirmationChanged` 是 agent 的 CONFIG 回执驱动，不是用户授权，只是名字像。

## 兼容归一（不做破坏性迁移）

`PluginSettings.loadState` 幂等归一：`enhancedCaptureConfirmed = true`、`enhancedCaptureRejectedAtMs = 0L`。**只碰这两个字段**——用户显式关闭的 `enhancedCaptureEnabled=false` 必须原样保留。字段保留（旧版本回退可读），标 `@Deprecated`，`loadState` 上 `@Suppress("DEPRECATION")`。**不引入版本号迁移**。

## 保留 vs 移除的边界

- **保留**：UI-007 目标身份门（helper/非 Java 入口仍排除）、attach 去抖与幂等表、上升沿扫描在跑进程补挂（改为**无条件执行**）、attach 兜底能力边界通知、非主配置进程过滤（原只为决定弹不弹框，删弹窗后 `isMainConfigLaunch` 参数一并删除，`offerStartedProcess`/`onProcessAppeared` 签名同步）。
- 无新增主动通知/气球提示；披露面改到设置页开关 tooltip（新键 `settings.enhancedCapture.tip`）与 README。

## 实施结果（已提交，dev，未 push）

`5b4fb4e0` 实现（43 文件 +892/−1736）→ 全量门禁抓出两处守卫缺口并修复：`c00b8827`（`SettingsRealityGuardTest` 断言一「每个 State 字段须在非 config 包有消费」，两个兼容字段只在 `loadState` 写固定值 → 显式登记 `compatOnly` 并写下线条件）、`a1a4da47`（`GuardSelfTestCoverageMetaGuardTest` 要求磁盘守卫类全部登记 `GUARD_CLASSES`，新类以 `decisionFns` 留空 + 依据登记）→ `74e54082` 回填全量读数。

读数：定向 **17 类 148/0/0/0**；反向三组各恰 1 红（G2 恢复 `confirmed` 闸门 → 6 例 1 红；G1 恢复 `requestReauthorization` 符号 → 3 例 1 红；G3 摘 `loadState` 归一 → 18 例 1 红）；隔离工作树 `a1a4da47` 全量 `testShards` **7892/0/0/9** + testExternal 31/0/0/1 + agent 164/0/0/0，parity 720 类全覆盖。shard2 +40% 权重告警＝画像过期/新类缺权，非失败（建议 clean 轮后跑 `dev-support/weight-refresh.ps1`）。

## 🆕 复盘与沙盒验收（2026-10-19 晚，均已收口）

**复盘结论（经验总结 `docs/经验总结/20261019-2004-守卫类测试分桶遗漏与暂停按钮可见性补测-经验总结.md`）**：两处守卫失败不是「没写测试」，而是**测试在错误的桶里跑了定向**——`SettingsRealityGuardTest` 与 `GuardSelfTestCoverageMetaGuardTest` 源码零 `com.intellij` 引用，按 `buildSrc/src/main/kotlin/mpv-testing.gradle.kts:284-295` 划桶规则属于 **testFast 快桶**；定向用 `test --tests` 手选清单不会执行快桶，所以定向全绿、全量才红。补测（`bdb21a8a`）：`EnhancedCaptureDefaultOnGuardTest` 增「兼容字段豁免不得过期」「本守卫类已登记到元守卫」两条；`SqlLogPanelLayoutTest` 暂停按钮扩四状态矩阵。**testFast 4100/0/0/6、布局 19/0/0**。流程定例：改 `PluginSettings.State` 或新增/删除守卫类时验证面＝`testFast` + 持久化测试，不是 `test --tests`。

**沙盒真机验收（243 沙盒 MPSV-MasterMerge-UI243，robot-server 8580，验收结论回填 `e865d3be`）**：①项目打开到 Run 全程零注入确认弹窗（当日会话日志 consent 轨迹 **0 条**、EnumWindows 无弹窗窗口）；②首轮 Run 即 premain 注入（端口 53885 前置协商）并建 agent 会话 `pid=24136`、在线态 false→true，`/user/list` 触发真实 SQL 产生条目；③SQL 日志工具栏 ActionButton 在 agent 在线前后**恒 9 枚**（暂停按钮未出现），在役 jar `javap -c` 反汇编证实 `AgentPauseAction.update` 恒 `setVisible(false)/setEnabled(false)`；④沙盒 `mp-sql-visualizer.xml` 中 `enhancedCaptureEnabled` **未序列化**（出厂 true 不落盘，符合默认即开语义），确认流 bundle 键在在役 jar 中不存在。证据帧在 `build/agentdefault-shots/`（build 生命周期不入 git）。边界：状态栏 JLabel 文本 robot 不可读、本机无多模态读图，该项以日志+组件树几何收口，文本级确认留用户扫一眼。沙盒验收操作链（真机可复用）：stripe 按钮 `//div[@accessiblename='SQL Visualizer']` 点开工具窗（点一次收起再点展开，TabContentLayout 在场=展开）、`AnActionEvent` 判据用组件树屏幕坐标数按钮而非读图、Run demo 用 Ctrl+Shift+N 开主类后 Ctrl+Shift+F10、收工杀 8081 demo + close-ide.ps1 强杀沙盒 + `parallel_tasks.py done --name` 注销。

## 🆕 全量门禁的并发工作树坑（本轮实测，可复用）

**dev 分支被固定工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev` 占用，主工作树 `git checkout dev` 必报 `'dev' is already used by worktree at ...`**（20261019 实测）——要读 dev 态代码/N 去那棵树，不要在主树反复试 checkout。

dev 工作树同时被另一会话改 `src/main`（工具栏图标任务，`SqlLogPanel.kt` mtime 一直在动）时，`testShards` shard5 报 `SqlDockedPreviewTest` 的 `ClassNotFoundException`（**不是断言失败**，是测试跑着时源码被改 → 编译产物与源不一致）。处置：**`git worktree add --detach <临时目录> <我的提交>` 隔离跑**，`--no-daemon --no-configuration-cache -PmpvDockerTests=false`；跑完 `git worktree remove --force`（Windows 长路径会失败 `Filename too long`，用 robocopy 空目录 `/MIR` 镜像后 `Remove-Item`，再 `git worktree prune`）。

配套：Docker daemon 半活态（`docker info` 超时 exit 124）不影响门禁——`-PmpvDockerTests=false` 时测试直接 Assume 跳过不触达 daemon；Commit 余量 7~8GB 仍可存活单路冷跑。

**Why:** 「默认开」这个词有歧义——配置默认值与授权闸门是两件事，只改其一用户仍会看到弹窗。并发工作树下全量读数的「失败」要先分辨断言失败与编译竞态。

## 🆕 同族缺口全仓排查（2026-10-19 深夜第二轮，`fe1ea3db`）

用户追问「还有没有类似的、测试全绿但功能不正常的情况，一起修掉」。三方向排查结论：

1. **零执行测试类——无风险**：737 个测试文件逐一比对文件名基名 vs `class` 声明类名。唯一特例 `CrudPreviewFlowAndTypesTest.kt`（实际类 `DbTypeQuoteHintTest`），但 `mpv-testing.gradle.kts` 划桶脚本类名提取失败时回退文件名、成功时用声明类名，分片与 testFast 两侧都取**声明类名**入 include——无匹配不上的形态；testExternal 6 类全显式 FQCN。
2. **UI 动作显隐（G-B）**：全仓 22 处 `presentation.isVisible/isEnabled/isEnabledAndVisible` 写点逐一对照测试。缺口＝**历史页二级右键「跳转到 Mapper XML」**（`HistorySqlLogPanel.JumpToMapperXmlAction`）的 `resolved != null` 显隐规则无用例——近亲（最近执行弹框）有用例、纯解析函数（SqlLogPanelXmlJumpTest）有用例，但「解析函数绿 ≠ 动作 update 接线绿」正是暂停按钮同族形态。补 2 例（无 mapperStatement / XML 不存在均隐藏），`JumpToMapperXmlAction` 改 `internal` 测试缝（仿 CompareSelectedAction 先例）；反向探针恒显示→恰 2 红。
3. **设置页绑定（G-C）**：`SettingsRealityGuardTest` 断言二只保证「控件符号出现在 apply/reset 体内」（源码扫描），**绑错字段不红**。`enhancedCaptureCheck` 三向绑定（isModified/apply 写回/reset 回读）无运行期用例。补 1 例真实驱动 AllSectionsConfigurable。**🆕 反向探针新坑：第一版探针（apply 写成脱敏复选框的值）0 红——夹具里两枚复选框恰好同值，错误行为与正确行为不可观测地区分，探针无判别力；修正夹具（把脱敏框拉到与开关相反值）后恰 1 红**。教训已并入 [[pitfall-fake-green-test-assertions]] 同族：反向探针的夹具必须保证「错误行为」与「正确行为」可观测地不同。

补测读数：HistorySqlDiffTest 8/0/0（含 2 新例）、PluginSettingsPanelBindingTest 16/0/0（含 1 新例）、四类定向 33/0/0；testFast 4100/0/0/6（无探针态复跑）；探针全部还原（`grep RED PROBE` 零命中）。排查方法可复用：文件名 vs 声明类名批量比对脚本、`presentation.isVisible` 写点 grep 清单逐一对照测试断言、源码守卫 vs 运行期绑定的证据等级分级。

**How to apply:** 复审或回退本任务时按「五处入口 + 兼容归一 + 保留边界」三段查；改回确认流只需回滚本批提交，字段仍在无需数据修复。真机走查（首次 Run 无弹窗、默认挂载、关闭开关回落、重开补挂）留给用户。关联 [[project-agent-enhanced-capture-consent]]（同意流结构与 agent 通道）、[[project-agent-pause-visibility]]（随后用户要求把工具栏暂停按钮临时全隐藏）、[[pitfall-parallel-session-batch-staging]]、[[pitfall-fake-green-test-assertions]]（G-C 探针撞车是新形态入册）。
