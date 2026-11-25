---
name: project-userfreeze-triage-20261119
description: T-UserFreezeTriage（2026-11-19）：用户报障 IDE 未响应的线程转储归因——真凶是 IDE
  自带重复代码检查的长读锁 + Cool Request 无超时等 EDT；本仓 EdtDispatch 有界派发与守卫已实施（分支未推未合）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_06adaf7c-f880-4246-bacf-d7ccd625f041
---

**归因（IntelliJ 2025.3.3 dump，142 线程，用户报「点发送后 IDE 未响应、请求没发出去」）**

- 与本插件无关：4 个 `mps-agent-*` 全停在 socket accept/read，EDT 与两条 `JobScheduler FJ pool` 栈里 0 帧本方代码。
- 真凶：IDE 自带 `com.jetbrains.clones.DuplicateInspection`（「重复代码片段」）在检查器读动作里为「提取重复方法」quickfix 预先算可提取性，一路走到 `InputVariables` → `bulkProcessElementsWithWord` **全工程词条搜索**并持读锁。
- 放大：Cool Request `MainBottomHTTPResponseView.clearResponseAndWait` 在 `beginSend` 前置用无超时 `SwingUtilities.invokeAndWait` 等 EDT ⇒ 发送链路挂死。**改它只能救「请求发不出去」，救不了界面假死**（任何 EDT 写都要等那把读锁）。
- 可复用手法：`docs/支持/线程转储自查-怎么看是不是本插件.md`（三步：grep 我方线程 → 查 EDT/JobScheduler 栈有无本方帧 → 看栈顶包名判持锁方）。

**本仓落地**（分支 `t-userfreeze-triage`，自 master `00aaa05c6`，**未推、未合**）

- `00aaa05c6` 计划册（含归因、我方待办、可直接发出的中文回复稿、5 条待验证前提）；`7e470a9e4` 代码+测试；`a03fdf62d` 文档批（§八、索引、CHANGELOG、LESSONS L86）。
- `util.EdtDispatch.invokeLaterAndWaitBounded`：`invokeLater` + 有界 `await(2s 默认)` + EDT 自等保护 + owner 已销毁丢弃 + 超时 WARN/计数 + 测试缝 `invokeLaterOverride`；接线 `SqlPreviewPanel`（设置变更刷新）与 `DynamicInstallSupport.dispatchModalityForUnload`（超时退 nonModal）。一次性安装流程的 `edtDispatcher` 保留同步语义（守卫白名单带理由）。
- 写点补 2 处幂等短路：`DataSourceSelectorResults` 复用控制台写文档、`DataSourceSelector.clearConsoleHistory`（空文档不开写命令）；其余 5 处热路径前轮已有。
- 守卫 `convention.EdtBoundedWaitGuardTest`：`src/main` 除 `util/EdtDispatch.kt`、`update/DynamicInstallSupport.kt` 外禁裸 `invokeAndWait`，另有一条**防空绿**用例钉「派发器必须真被接线」。
- 读数：定向 **13/0/0/0**（`./gradlew test --tests`）；反向（调用点改回 `invokeAndWait` + 派发器超时假成功）**3 红** → 恢复复绿；全量 not run（交 CI）。

**Cool Request 侧只出计划**（用户只要求计划文件）：源在 `F:/code/Java/Github/other/houxinlin/coolrequest-vip`，分支 `song`，计划册目录是**单数** `doc/开发计划/`（README 有表格且常被别车道在途改动占用，只 add 自己的文件、别碰 README）；提交 `683013db`，方案 A 非阻塞清空（靠 EDT FIFO 保序）/ B 有界等待 + 15 处 `invokeAndWait` 分类。其源码已证明：`CoolRequestEditorTextPage` 早已有「内容没变不写」早退 + BGT 侧排队投递，仍挡不住 EDT 侧的写锁等待。

**Why:** 这类报障会反复来，结论不能凭感觉；「在场插件」与「持锁方」必须靠 dump 逐段取证区分。

**How to apply:** 再遇到冻结类报障先走三步法下结论；给用户的回复必须带三条可核对事实（我方线程清单 / 冻结栈无本方帧 / 真正持锁方与规避开关）。相关：[[reference-documentimpl-settext-equals-replace-string]]、[[pitfall-testfast-tests-filter-silently-skips-platform-classes]]、[[project-pending-plans]]。
