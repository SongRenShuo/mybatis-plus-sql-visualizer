---
name: pitfall-guard-covers-audited-targets
description: 守卫类改动三坑——守卫可能对「它要防的真实站点」完全不可见（名字绑定只认一种取服务形态→删掉真实泄漏行的 restore
  仍绿）；复位/豁免令牌必须是「对该对象的调用」而非文件级 contains（他对象同名方法可洗绿真泄漏）；「仓内 0 处违规」是空绿，不构成精度证据
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_bd923173-5e98-4d8b-ac6b-b59251d2d90d
---

2026-09-19 T-StabilityTail 的 T-ST-02d 实证（独立验证者用 Python 复刻规则逻辑跑真样本 + 全仓扫描抓出，**判定「有条件通过 2 阻断」**；若验收口径是「守卫能防住已修的泄漏复发」则为**不通过**）：

**坑一（最重）：守卫对「它自己引为动机的那批站点」完全不可见。**
T-ST-02d 的立项动机是「把人工逐个审计的泄漏结论固化为守卫」（`setUp` 内改项目服务级状态而类内无复位即红）。
但仓内 5 个已修泄漏类的 `getService(` 计数**全为 0**——它们统一走 `AlreadyDisposedDiagnostics.projectService(project, X::class.java, …)`
（全仓 13 处），而**那个 wrapper 的文档正是要求测试类把 `project.getService(X::class.java)` 换成它**；规则的名字绑定靠
`body.indexOf("getService(")`，wrapper 形态一个都绑不上。**判据**：把 `SqlConsoleBridgeFilterProviderTest` 的
`SqlLogManagerProjectState.restore(manager, serviceState)` 整行删掉（= 原样复现修复前的泄漏）→ 规则仍 **offenders=0 绿**；
仅把取服务改写成 `manager = project.getService(SqlLogManager::class.java)` 才 **红 1 处**。修法＝绑定令牌扩为
`getService(` / `projectService(` 两形态。

**坑二：复位/豁免令牌是「文件级 contains」时，另一个对象的同名方法会洗绿真泄漏。**
令牌表里 `.loadState(` / `.clearForTest(` / `.clearAll(` / `.resetForTest(` 在 `SqlLogManager` 上**根本不存在对应方法**
（grep 只有 `restoreCaptureStateForTest`），而复位判定是 `code.contains(it)`、不与服务对象绑定 → 四个「另一个对象的复位」
样本（`PluginSettings.loadState` / `JoinFkRegistry.clearForTest` / `PluginCacheManager.clearAll` / `FirstCaptureGuide.resetForTest`）
把真泄漏**全部洗成绿**。修法＝判定收窄为「**绑定承接名**」（`<承接名>.<复位口>(…)` 或
`SqlLogManagerProjectState.restore(<承接名>, …)`），并删掉文件级 contains。

**坑三：「仓内 0 处违规」是空绿，不能当精度证据。**
该规则的「过报方向 0 处」成因**不是**规则命中后被豁免，而是**写命中本身为 0**（扫描面里能绑定到服务对象的初始化入口只有 1 个、
且 setUp 内无写）。真正的精度证据只能是**合成样本**。KDoc 里必须把这类 0 明示为「空绿、不构成精度证据」。

**固化去向（20260920）**：「守卫/扫描类改动必须用真实站点做『移除修复 → 必红』反向验证」这条已写进两处资产——项目盘点文末「批次收口清单」第 2 步，与用户全局 `~/.zcode/AGENTS.md` 新增的 `## 验证与收口纪律` 首条（含「空绿不构成精度证据」判据）。同批 T-ST-02d 的守卫本身也已按坑一/坑二修好（`df50fb5`）。

**Why:** 这三坑的共同点是「**守卫自身的绿灯与它有没有用完全无关**」——本次四层绿灯叠加（自证用例绿、既有 24 例绿、
豁免计数零漂移、仓内 0 处违规）而它对真正要防的那批站点是瞎的。同类前科：`pitfall-scan-guard-mask-and-sample-power`
（掩码器漏 Kotlin 字符串模板致真实站点脱离覆盖）、McpRowBudget 的接线守卫是整文件子串检查（回退格式化调用仍绿）。
即本仓守卫类改动**已三次**出现「绿灯下的失效」。

**How to apply:**
- 守卫/扫描类改动**必须用真实站点做「能红」反向验证**：删掉某个**已修站点**的修复行（而不是喂合成样本）→ 守卫**必须红**；
  恢复 → 绿。合成样本只能证明规则可红，证明不了规则覆盖真实形态。
- 绑定/令牌一律**绑定到对象**（承接 `getService(...)`/`projectService(...)` 的那个标识符），**禁止文件级 `contains`**
  作为复位/豁免判据；令牌必须是「该对象上真实存在的复位口」（先 grep 该服务类有无该方法）。
- 部分复位不算复位（本次 `restoreCaptureStateForTest` 只回滚 3/15 字段，已从令牌排除）；复位判定若按名不按实例，
  多类同文件时 A 类的令牌会豁免 B 类的泄漏——如实登记。
- 扫描面非空断言是必备项（路径解析失败会让主用例**空绿**）。
- **布局类断言的两个空绿形态（2026-09-21 T-UiCoord F2 反向验证实证）**：①拿「实现高度 == preferredSize 高度」当折行判据，对 `SqlLogPanel.WrapLayout` **恒等**（它的 `preferredLayoutSize` 本就按折行后的多行高度上报，这正是它存在的意义）——把旧结构放回去该写法仍全绿；有效判据是**按垂直中心聚类数行数**（行内共轴差 ≤6px、跨行 ≥28px，阈值取 10）。②headless 下 `ActionToolbar` 不建按钮、理想宽 **0×0**（真机约 133×26），图标组不吃宽 → 「被图标组挤到折行」这类缺陷**在单测里复现不出**；对策是把契约拆成「结构守卫（谁挂在哪个区、顺序）+ 宽度预算锁（控制组最坏宽 ≤ 上限）」两条可测面，视觉确认留给真机走查，并在测试 KDoc 写明这条边界。附带教训：还原反向验证探针时曾把 `row.add(toolbar.component, CENTER)` 一并删掉，**图标组整个没进行而全部用例仍绿**（无断言覆盖该挂载点）——凡是「反向验证要临时改生产代码」，还原后必须逐字 diff 探针插入点，并给插入点补一条存在性断言。
- **坑四：扫描面字符集与「顺手复用的读取函数」也会让守卫恒绿/空转（2026-10-08 T-PluginDescReshape 实证）**：给 `PluginXmlStructureGuardTest` 加「描述不得含 `<hr/>` 与 HTML 注释」时，
  评测用的 `strippedXmls()` **先剥注释再断言**——用它写这条规则会恒真（剥完当然没有 `<!--`），是「守卫的空转形态」；改单列一个 `rawDescription()` 走**未剥注释的原始资源**才成立。
  第二层：该测试读的是 classpath 上的 `build/resources/main/META-INF/plugin.xml`（构建期 patchPluginXml 重写过的版本），**断言前必须确认被测内容在那份副本里逐字保留**（实测注释与描述 CDATA 均保留，`${}` 未被 Gradle 展开）。
  第三层：**加描述类守卫前先探针一次真数据**（探针输出 tags/长度/首 40 字符），确认取值面非空且形态符合预期——本轮探针直接暴露「published 描述无 `hr`、本地描述有 `hr`」，既定了规则也定了根因；跳过探针写守卫会拿「想当然的形态」去断言。
- 关联 [[pitfall-scan-guard-mask-and-sample-power]]、[[pitfall-regression-false-failure-flakes]]（共享项目服务状态泄漏条）、
  [[project-test-conventions]]（守卫两轮）。
