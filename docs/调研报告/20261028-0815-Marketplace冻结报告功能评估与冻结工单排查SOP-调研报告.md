# Marketplace 冻结报告功能评估与冻结工单排查 SOP

> 来源：JetBrains 平台博客《UI Freeze Reports in Marketplace》（2026 年 10 月）
> <https://blog.jetbrains.com/platform/2026/10/ui-freeze-reports-in-marketplace/>
> 评估结论：**不接入 Marketplace 通道，errorHandler 保持自建提交器为唯一出口**；冻结工单沿用自建需求池通道，按本文 §三 的排查 SOP 处理。

## 一、功能是什么

JetBrains Marketplace 给插件作者后台新增了 Freezes 标签页。用户 IDE 发生 UI 冻结（EDT 卡顿超阈值）时，平台会捕获一组现场数据：每 5 秒一份的完整线程转储（`dump-N.txt`）、可选的 CPU 快照（`snapshot.jfr`）、栈分析（`report.txt`）与 OTel 指标（`open-telemetry-metrics.csv`）。这些数据按错误类型分组归因到插件，作者在后台即可看到「哪段代码在冻结期间持续占着 EDT」，解决卡顿类问题复现不了的旧疾。

接入方式：在 plugin.xml 注册平台内置提交器 `com.intellij.diagnostic.JetBrainsMarketplaceErrorReportSubmitter`（`com.intellij` 命名空间，2023.3 起存在，无需自写实现）。Split Mode 插件须注册在 frontend/shared 模块。

两个前提：报告能否到达取决于用户设置（IDE 错误上报同意，或全局「自动发送到 JetBrains」开关）；平台是否把冻结归因到你的插件。

## 二、为何不接入

**1. 扩展点正面冲突，换入即拆自建通道。** 本插件在 plugin.xml 注册了自定义提交器 `IdeErrorReportSubmitter`（T-ErrReport-04 交付），把 Internal Errors 对话框的「报告」按钮接到官网需求池，配套 24h 指纹去重、批次合并弹框、双语跟随 IDE 语言、隐私声明一整套。`com.intellij.errorHandler` 是按错误归属插件解析提交器的单值扩展点，内置 Marketplace 提交器换入即顶掉自建通道，「报告到 SQL Visualizer 需求池」闭环整个作废；不换则 Freezes 页收不到数据。两个实现同时注册在一个 bean 扩展点上是否被平台容忍，未实证（需 javap 243/262 平台 jar），不能赌。

**2. 同意模型与隐私主张冲突。** 全站（含公众号长文）的口径是「不发任何使用数据」。现有自建通道是用户逐条确认才发，属最强同意模型；Marketplace 通道里，用户若开了 IDE 全局「自动发送」，线程转储在后台静默提交，dump 含用户全部线程栈（类名、方法名，可能露出用户代码路径）。主动接入与隐私卖点的口径难以自洽。

**3. 冻结事件的可达性已经具备。** `IdeErrorReportSubmitter.kt` 头注释有真机实证：「Freeze 事件已被归属插件，同通道可达」。冻结报告本来就经 Internal Errors 对话框走需求池进来，Freezes 页的分组视图只是换了一种看样本的方式，不是独占数据源。

## 三、冻结工单排查 SOP（自建通道）

冻结报告经需求池到达时，工单里应要求用户附上冻结期间的线程转储（IDE 卡死时自动生成，目录在 `~/.java/.userPrefs/.../ freezes` 下，或让用户直接从 Internal Errors 对话框提交，事件里自带）。拿到 dump 后按以下流程排查，方法来自 JetBrains 博文，适合直接喂给 AI 排查（配套的 `analyze-freeze` 技能把这套流程写成了提示词）：

1. **核对插件版本**。先确认报告来自哪个插件版本，版本不明就当不明处理，不要默认最新版——影响代码对位与混淆还原。
2. **先看 EDT**。通常叫 `AWT-EventQueue-0`，或栈里含 EDT 字样的线程。判断它在干重活还是在等锁：在干活，说明本插件把耗时操作摆上了 EDT；在等锁，继续追持有方。
3. **多份 dump 对比**。冻结期间每 5 秒一份，把几份摆在一起看哪些操作在多份里持续存在——持续在场的才是冻结根因，单份里的偶发栈是噪音。
4. **别忽略后台线程**。典型形态是插件起了一个不可取消的长 read action，把写操作挡死。EDT 栈只显示「在等」，根因在后台线程的栈里。
5. **修复方向**：缩短 read action 作用域、换可取消的读取 API（`ReadAction.nonBlocking` 一族）、阻塞 I/O 移出 read action、让可重试的计算真的可安全重试。改完用同样手段取新 dump 验证，不看「感觉变快了」。
6. **本插件已知风险面**（结合既有踩坑，走查时优先对号）：EDT 上读 PSI（262 硬抛，链路里两段式收口是否走全）、SlowOperations 断言、异步重解析作废已捕获 PSI、Kotlin daemon/长任务占 EDT 类弹窗等待。既有对策（日志工具栏走 BGT、模块隔离记忆）已在位，冻结工单复现时先核对这些路径有没有被绕开。

## 四、后续可选路线（暂缓，触发条件后再立项）

若冻结类工单量涨到自建需求池看不过来，可评估「混合方案」：自定义提交器内部对冻结类事件追加委托给内置 `JetBrainsMarketplaceErrorReportSubmitter`（它是普通类，可在 `submit()` 内实例化转发），同时保留弹框确认，把同意模型维持在最强档。立项前必须先验证三件事：

- `com.intellij.errorHandler` 扩展点对单插件双注册的真实行为（javap 243 与 262 平台 jar）；
- 程序化实例化内置提交器并调用 `submit()` 是否等效于原生注册（Freezes 页是否只认原生注册路径，后台自动提交通道可能绕过 `submit()`）;
- 冻结事件在平台侧的错误归属判定，与自建通道实测过的归属口径是否一致。

在此之前不动 `errorHandler`。
