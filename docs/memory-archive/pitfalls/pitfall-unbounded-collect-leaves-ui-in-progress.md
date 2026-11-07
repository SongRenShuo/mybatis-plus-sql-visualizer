---
name: pitfall-unbounded-collect-leaves-ui-in-progress
description: 环境体检永久停在「体检中…」的真因是**模态态吞掉回调**（不是 collect 卡死）——模态 Settings 弹窗内 BGT 回 EDT 用隐式 defaultModalityState 落 NON_MODAL，该模态存续期 runnable 不执行；顺带补了单项/整体双超时
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

2026-11-04 用户真机（UI-005）：设置 → Tools → SQL Visualizer → About → 点「生成环境体检单」，按钮变灰、文案停在「体检中…」，几分钟不结束。**关掉设置页会突然恢复**——这是可自证的现象。

## 真因（代理实测推翻主控猜测，必须按这条记）

**不是取数卡死**，是**回调投递模态态**：

- 入口在**模态** Settings 弹窗内；池线程回 EDT 用隐式 `ModalityState.defaultModalityState()`，**BGT 上它落到 `NON_MODAL`**；
- `NON_MODAL` 的 runnable 在模态弹窗存续期**不执行** → 体检单早已生成，只是永远贴不回面板。

**同文件早就有前车之鉴**：`PluginSettingsPanel` 的导入/导出显式传了 `ModalityState.any()` 并注释「默认 modality 在模态 Settings 弹窗存续期不执行（真机实证反馈不出现）」。NEW-18 按 D202 把方法体拆到 `PluginSettingsPanelEnvCheck.kt` 时**把这一步漏带了**。

主控原猜测（`agent?.statusSummary()` / `AgentAttachReport.latest()` 做 JVM 进程枚举而卡死）**被证伪**：`statusSummary()` 只读 `CopyOnWriteArrayList` / `AtomicBoolean`，`latest()` 就是 `AtomicReference.get()`，全链无 `VirtualMachine.list()` / jcmd。

## 正确判据（用户报「一直 XX 中」）

1. 按钮仍灰 + 文案仍 generating ⇒ 回调没执行；
2. **先试「关掉宿主对话框」**：若一关就恢复，就是模态态问题，不必去查采集链；
3. 别先怀疑渲染或事件队列，也别急着给采集项加超时当主修。

## 修复（`1ef83728`，合 dev）

- `invokeLater(..., ModalityState.any())` + `try/catch(Throwable)/finally` 三段兜底保证按钮恢复；
- 顺带补**有界内核** `diagnostic/EnvironmentCheckBounded.kt`：单项 3s / 整单 9s，超时项降级「未采集（超时 Xms）」而非整单失败；`Future.get(timeout)` 后 `cancel(true)`（**无 `Thread.stop`**），被放弃任务全是只读取数、不碰 UI 不写共享状态；**线程池按任务数建（封顶 8）而非单线程**——单线程会让「一项卡死 → 后面全超时」；
- 报告增 `CheckOutcome`（COLLECTED/ITEM_TIMEOUT/TOTAL_TIMEOUT/FAILED）、每项 `elapsedMs`、整单 `totalElapsedMs`——这是自动诊断的数据地基。

即使真因是模态态，有界保护本身仍是对的（缺它是独立缺陷）。

相关：[[project-env-check-auto-diagnosis-proposal]]、[[dev-vs-master-262-acceptance-20261104]]、[[pitfall-preview-open-lock-inversion]]（同族：跨线程等待无上界导致 UI 卡死）。
