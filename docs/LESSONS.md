# LESSONS（教训账本）

<!-- reviewed-through: f7e25e5f (2026-10-27 23:32 +0800) -->

> 本文件是**持续演进、带淘汰压力的规则账本**（机制取自 magpie 项目的 LESSONS.md，2026-10-23 T-MagpieGems 引入）。
> 每条教训固定四段：**粗体祈使句规则 → 证据（提交号/文档路径/真机日志，hash 级）→ 正例与反例 → `Seen N× (日期…)`**。
> 同日多次发现只算一天；**Seen 满 3 个不同日期的规则整体搬进 [`规范/编码与验收标准-规范.md`](规范/编码与验收标准-规范.md)**，本文件在升级区保留指针，后续复发继续记回。
> 复盘触发点：不搞自动夜间复盘——每次**收口、门禁复跑、真机故障**之后，由当值会话顺手补记一条（有新教训才记，没有不硬凑）。
> 分工（P-4 定稿）：本文件只收**可执行规则**；事件叙事与完整排查过程写 `docs/经验总结/`，条目用链接引用不复制内容。**改代码前先读本文件。**

## 2026-10-24 复盘日志

**数据**：53 commit（t-magpie-gems 7 笔 / master 46 笔，去重并集 53；另 sql-insight 工作树与 dev 分支各若干，未计入水位线面）/ 门禁：master 线 testFast 至少 3 处 body 登记读数 + 发行笔走发行流程；t-magpie-gems 线 4 处（定向+反向+testFast）/ 返工：explain 换源域当日 fix 12 笔（用户全天十余轮真机反馈驱动的同族逐轮变形，详见 625c6a89 经验总结）/ 点名批评：

- `540125bb` 补救笔 body 无验证记录（单行 subject）——补救笔也应写清「补了什么、为什么漏」。
- `f2050329` 提交态编译不过（漏 add `cacheHasAnyTable` 定义），`540125bb` 补救——**L11 同日再犯**（t-magpie-gems 线 36e70767 之后 master 线再现，同日 Seen 不累计），两线同日各犯一次说明「提交前过编译」尚未成为肌肉记忆。
- `06fe3953` 揭 `4bccd331`（当日 01:04）加守卫 `ExplainAskPasswordChannelGuardTest` 漏登合并轮清单致潜伏红灯——**L3 达 Seen 3×（10-09 / 10-23 / 10-24），本日起升级进《编码与验收标准》**（内容已并入 S3，账本活跃区改指针）。


## 2026-10-25 复盘日志

**数据**：27 commit（t-magpie-gems 0 笔新增 / master 27 笔；上轮口径订正——e35a0d7c 实在 HEAD 线上，ca3b2388..HEAD 的 17 笔中 10 笔为分叉期历史独有提交、非本轮新增，上轮已审的 7 笔 HEAD 侧提交不重审）/ 门禁：发行两轮重发均回填门禁读数与产物哈希（`00153b0b`/`7c2b6a41`），抽查 5 笔 body 皆详实 / 返工 2 处 / 点名批评：无。

**a 返工**：①演示动图录制面——上轮全量审查定性的 9 张错/次片（严重 5+轻微 4）本轮全部重录收口（`5330ba6a` 4 张 / `da0bd530` jpa-preview / `082bbc11` jpa-criteria / `6b7e3697` 批 2 三张 + 接入 MCP 精确开文件 / `c26538a5` n05 / `179cdc86` a15），2 张仍阻塞（`f340cd42`/`288f05cd` 已标注阻塞原因）；根因（IME 未切英文/构图偏移/开错同名文件）均已有档，本轮是执行重录非新教训。②发行面同 tag 重发 2 轮（`5f04fa63` 补首发行后 26 笔中 11 笔用户可见修复；`cff1e4f9` 再补方言放行与 runner 重映射）——tag 前移+附件替换+创建时间保持，读数与哈希均回填，流程留痕完整。

**b 验证过薄**：抽查 `5330ba6a`/`8619a3af`/`179cdc86`/`5f04fa63`/`739bfd8a` 五笔，body 均含根因链/用户定案/实测读数（`739bfd8a` 基准 6.60x、`179cdc86` 五轮实测）——本轮零点名。

**c 不该合的**：无未编译提交形态（无补救笔）；发行重发走完整门禁。

**d 复发**：`6b7e3697` 接入 MCP 精确开文件＝「开错同名文件」既有根因的闭环落地（非复发）；`ed5005d4` 沉淀录制沙盒许可失效新真因（缺 `idea.key` 非 agent 未加载）——文档闭环。既有条目零复发。

**待核实**：无。


## 2026-10-26 复盘日志

**数据**：46 commit（t-magpie-gems 2 笔新增：`713416bb` 并入 master 72 笔的人工合并落地 + `f9341c3f` 修合并连带 6 红；master 27 笔；另 17 笔为 T-MagpieGems 历史提交经合并后的口径暴露、已审不重审）/ 门禁：合并轮全量 testShards 8267 例 6 红→三组对照定性→当夜修复（`f9341c3f`）；发行 v2026.10.26 读数与哈希回填（`15e0e6c0`/`2ad4b80d`）/ 返工 1 处 / 点名批评：无。

**a 返工**：T-LogListHeader 布局当日五连改——`bed58b64` 落「左侧 88px 信息轨」→ `f40f8cfd` 用户真机三点反馈后**整案撤除**（信息轨/进度条/斑马纹全拆）→ `c27eeefe`（时间格式跟随设置）→ `79e232be`+`4ba423d3`（丢序两轮迭代）。方案级返工 1 处，根因＝落码时用户未看过形态（与 T-ExplainSwitchNative W11 撤自研下拉同族）——**立新教训 L12**。

**b 验证过薄**：抽查 `f9341c3f`（三组对照定性：master 18/0/0 全绿、分支与合并后同 6 红＝非合并引入的分支既有缺陷）/`f40f8cfd`/`b3970e1f`/`ceac4186`/`bed58b64` 五笔，body 皆详实——零点名。

**c 不该合的**：无未编译提交形态；`713416bb` merge 带入 6 红在分支存续约 2.5 小时后当夜修复（分支非发行线，可接受，不点名但计入 L13 证据）。

**d 复发→新教训**：`f9341c3f` 揭「平台 UI 测试不在 testFast 快桶」形态**两日内第二案**（2026-10-25 全量首跑 6 红在档）——**立新教训 L13（Seen 2×）**。`0c1e8043` 补同类守卫＝Embedded 教训闭环非复发。

**待核实**：无。


## 2026-10-27 复盘日志

**数据**：21 commit（t-magpie-gems 0 笔新增 / master 21 笔；HEAD 线 19 笔为 T-MagpieGems 历史经合并后的口径暴露、已审不重审）/ 门禁：T-DialectGatePass 补齐预览入口放行用例并完成反向验证（`4d9fed44`）、批4 六张成品双达标入库（`f7e25e5f`）/ 返工 2 处 / 点名批评：无。

**a 返工**：①`920ec3be` 头部信息恢复 2026.10.23 顺序与完整类名（用户两点实测反馈；f40f8cfd「多轮预览定案」的形态仍被回调）＋顺带修自动滚动无法恢复——**L12 复发（发现日 10-27，Seen 1×→2×）**：预览确认的形态与用户真机长期使用的习惯形态仍有落差，「预览点头」不能替代「按原版 git show 核对」。②`26278fc6` 方言下拉切具体方言时推断状态行不刷新＝10-26 设置改造面连带缺陷修复（改造面返工计数 1）。

**b 验证过薄**：抽查 `920ec3be`（按 git show 原版逐项核对）/`75cff7c4`（语义对齐 8619a3af 定案+EDT 下一拍细节）/`f7e25e5f`（6 张成品+8 件工具）/`b8f91621`（量化复盘：三小时 10 张 vs 四十七分钟 0 张的根因分型）——零点名。

**c 不该合的**：无未编译提交形态。

**d 复发**：L12 如上。`b8f91621` 两小时零产出→当日复盘→三项手段升级→批4 即出 6 张，属自我纠错闭环非复发。既有条目另零复发。

**待核实**：无。

## 已升级到《编码与验收标准》的规则（seen ≥ 3，正文见 规范/编码与验收标准-规范.md）

- [S1 线程归位两段式](规范/编码与验收标准-规范.md#s1)——EDT 读探测＋后台收口；ReadAction 内禁开 WIRA；BGT 改文档唯一路线=派 EDT。
- [S2 反向验证必须成对且有对照](规范/编码与验收标准-规范.md#s2)——守卫/扫描类改动摘掉修复分支必须变红；空绿不是精度证据。
- [S3 定向测试以判据数收读数](规范/编码与验收标准-规范.md#s3)——`--tests` 不匹配会静默跳过，退出码假绿；用例数对不上 XML 类数就是没跑。
- [S4 收口多步禁用 `&&` 串联](规范/编码与验收标准-规范.md#s4)——失败会伪装成「进行中」。
- [S5 工作树所有权与并发写隔离](规范/编码与验收标准-规范.md#s5)——接手掉线代理先审计工作树；并发车道禁同改 `src/main`；提交后 `git show --stat HEAD` 只许有自己的文件。
- [S6 证据看产物，不看文档自述](规范/编码与验收标准-规范.md#s6)——影响面/用户受影响面以 tag 树、发行物、二进制、实测请求为准。
- [S7 修 bug 必 grep 同型兄弟](规范/编码与验收标准-规范.md#s7)——一处缺陷的根因很少只长一个实例。
- [S11 置顶 change-notes 新块同批删旧块评分引导句](规范/编码与验收标准-规范.md)——评分句全文只留 1 次、本版块禁全角标点，两条机器判据跑在 `git tag` 之前（2026-11-09 L25 升级）。

## 活跃规则

### L1 排障先 grep `Caused by:`，别信 `runCatching` 的表面异常

**证据**：`0f06bcc4`（反射调 package-private 类未设 isAccessible，异常被 runCatching 吞成 null 调用链假象）；`eca7aecc`（`estimateDmlImpact` 取消弹窗后 loading 永挂，终态归位缺失被吞）。
**正例**：两层异常栈逐层找根因；**反例**：把 `InvocationTargetException: null` 当异常本身去修。
**Seen 2× (2026-10-19, 2026-10-19)**——同日两案不同车道，计数从 2026-10-19 起算。

### L2 反向探针的夹具必须与守卫值拉开，两值撞车=0 红假绿

**证据**：`fe1ea3db`（T-EnhancedCaptureDefaultOn 同族补测，探针两值撞车 0 红）；守卫类四层绿灯却对真泄漏类完全不可见的教训（2026-10-09 前后守卫规则复盘）。
**正例**：造探针前先确认「守卫看见的值」与「夹具的值」不同；**反例**：脱敏框默认值恰好等于开关默认值。
**Seen 2× (2026-10-19, 2026-10-09)**

### L3 改 State/守卫类必须跑 `testFast`（已升级 → S3）

Seen 3× (2026-10-09, 2026-10-23, 2026-10-24)。正文与后续复发检查见 [`规范/编码与验收标准-规范.md`](规范/编码与验收标准-规范.md) **S3**（当日新增证据：`4bccd331` 加守卫漏登合并轮清单、`06fe3953` 合并轮补登时才暴露潜伏红灯）。

### L4 提交信息与跨调用写文件的两条本机红线

**证据**：`git commit -m` 内联反引号被命令替换吃掉（信息静默缺段，提交仍成功）；`python -c` 双引号串同根更隐蔽；Bash 内 python/heredoc 写文件沙盒拦截不落盘（同进程回读成功、跨调用丢失）。
**正例**：提交信息写 UTF-8 文件 `-F`；跨调用改文件一律 Edit/Write 工具，Bash 内 python 只做只读分析；编辑后回读校验关键内容在场。
**Seen 2× (2026-10-19, 2026-10-23)**

### L5 同回合内改被测文件 ⇒ 已跑的门禁读数作废，必须复跑

**证据**：T-HqlBufferConcatGap（2026-10-13，首版反向用例因同时改 demo 而假绿，同日二遇）；T-MergeMasterToDev 系列合并后重跑定例。
**正例**：读数落地后冻结被测面；确要再改，重跑该面定向并更新读数；**反例**：「顺手改一下，读数应该还绿」。
**Seen 2× (2026-10-13, 2026-10-13)**——同日两例，次日复盘归并。

### L6 Git Bash 调带 `/X` 参数的 Windows 工具会被 MSYS 转成路径

**证据**：robocopy `/E` → `E:/` 报无效参数（多工作树并行构建实测，20260923-1055 经验总结）；后续 .ps1 化配方复用多轮。
**正例**：一律写 `.ps1` 用 PowerShell 执行；robocopy 退出码 0~7 均为成功，禁拿管道末命令退出码当工具退出码。
**Seen 2× (2026-09-23, 2026-09-24)**

### L7 JVM 类链接期就要全可见：删 `implements` 躲不掉，兜底必须 `catch(Throwable)`

**证据**：T-JRebelPremainCrash（`ee86967` 链，2026-10-22）：缺 `asm/Opcodes` 的 NCDFE 三层产物都在仍 FATAL，引用含 StackMapTable 帧不调方法也炸；常量池死条目让 grep 误判重定位。
**正例**：逐类 try + 谓词失败计数 + 静态块留痕；**反例**：以「另一个 ClassCircularityError 形态」换个类名继续抛。
**Seen 1× (2026-10-22)**

### L8 词索引看不见库与 class 文件里的注解（243 无 `IN_ANNOTATIONS`）

**证据**：T-JarEmbedded（2026-10-16，`e0675b41`/`974d125e` 链）：库内实体 `@TableName` 走词索引零命中，条目 20,226→0。
**正例**：库/class 形态改走 `notScope` 库兜底或注解读 API；**反例**：拿源码词索引的 0 命中当「库内不存在」。
**Seen 1× (2026-10-16)**

### L9 片段拼接的位置回填：每个 `?` 都必须有来源槽

**证据**：setSql 两缺陷（UI-038 SET 丢失 / UI-039 参数错位，2026-10-20 `f69f75fe` 链）；「`?` 数==来源数」拦不住整段丢弃，「片段必须出现」拦不住位置错位——两条互补。
**正例**：片段文本与参数按序双轨喂 sink，计数+在场+位置三条断言缺一不可。
**Seen 1× (2026-10-20)**

### L10 平台 UI 契约以反编译/源码 jar 为准，静态字段默认值≠实际使用值

**证据**：UI-043/045 自绘组件对齐（描边 2px、圆角 `scale(7f)` 硬编码在绘制方法体）；`Disposer.isDisposed` 已废弃不能当守卫（`ebfaf73f`，2026-10-23）；`ContentManagerListener` 243 平台完全不可用。
**正例**：跟到绘制/调用方法体取值；**反例**：拿字段声明处的默认值或接口注释当契约。
**Seen 2× (2026-10-19, 2026-10-23)**

### L11 多车道同一工作树时，每笔提交至少过编译——未编译提交堵死下游车道验证

**证据**：T-MagpieGems（2026-10-24）：W3 车道 `36e70767` 提交未经编译（import 包名错/缺 import/elvis 推导错三连），下游 W2 车道无法验证被迫代修（`060ffaeb`）；同日 W3 新增测试还有 1 例红到 W2 首次跑通才暴露。
**正例**：提交前跑一次 `./gradlew compileKotlin compileTestKotlin`（增量秒级）；或改码与提交隔轮；**反例**：「我的改动是纯新增文件，编译肯定没问题」。
**Seen 1× (2026-10-24)**

### L12 UI 争议方案先给用户看形态，绿灯读数不构成方向依据

**证据**：master `3117f6db` 链（T-ExplainSwitchNative W11 自研换源下拉，定向全绿后五轮真机实测被否、revert，2026-10-24——当时未立条，作同族前例注明）；`bed58b64`→`f40f8cfd`（LogListHeader 左侧信息轨当日落码当日被用户三点反馈整案撤除，2026-10-26）。
**正例**：HTML 对照稿/形态预览先行，用户点头再落码（参照 20261026 微信长文的 HTML mockup 路径）；**反例**：定向全绿→落码→等真机反馈→整案撤除。
**Seen 2× (2026-10-26, 2026-10-27)**——第二案 `920ec3be`：预览定案的 LogListHeader 形态次日仍被用户回调恢复 2026.10.23 旧序，预览点头≠按原版核对。

### L13 改设置页/面板布局后 testFast 绿不算绿——平台 UI 测试只在全量 `testShards` 跑到

**证据**：2026-10-25 全量首跑 6 红（`BasePlatformTestCase` 派生的设置页布局类，档在项目记忆 pitfall-platform-ui-tests-not-in-testfast）；`f9341c3f`（2026-10-26，T-MagpieGems W2 脱敏分级改造的 6 红在分支潜伏两天，合并轮全量才暴露，master 侧全绿对照定性非合并引入）；**2026-11-07 T-SqlListCatchup 合 master `a0cc268e` 后 CI 三轮红 8 例**——券 A 给 `notifyUi` 加了 150ms 刷新窗（通知转 Alarm 延迟补刷），`SqlLogTailFollowTest`×5 / `SqlLogPanelFilterTest`×1 / `SqlListV179HeadlessTest`×1 断言的「动作后列表已同步刷新」全部失效（headless 泵不到未到点的 Alarm），另 `ReplayGuardStormVerdictTest` 未登记进元守卫。本战役六轮定向 + 244 例集成收口全绿，**一个都没抓到**，全靠 CI 全量。
**正例**：动设置页 section 控件/面板布局/增删 JComboBox 等控件的提交，报告里写明「平台 UI 测试面，全量未跑＝not run 待收口」；**反例**：testFast 快桶绿就宣称设置页改动收口。
**追加口径（2026-11-07）**：**引入「延迟/异步化」机制的改动与改布局同价**——任何把同步刷新改成延迟补刷（时间窗、节流、Alarm、invokeLater 链）的提交，都会让既有 UI 测试里的「动作后立即断言」失效；此类改动的定向面必须显式列出受影响的面板测试类（`SqlLogTailFollow*`/`SqlLogPanel*`/`SqlListV179*` 等）并逐个跑到，或直接以 CI 全量轮为准再收口。修复口径＝测试侧注入同步调度器（`manager.catchUpScheduler = { _, action -> action() }`，机制本身由专项用例覆盖），不要为了让测试变绿去放宽生产窗口。
**Seen 3× (2026-10-25, 2026-10-26, 2026-11-07)**

### L12 守卫的口径要以生产代码的实际契约为准，不能按「更严格更好」想象

**证据**：T-TestGateGems（2026-10-24）W3 动作矩阵第一版断言「空上下文下 `isVisible == false`」，实测 3 个选中类动作本来就设计成**常驻可见、按上下文灰置**（`SelectedTableSqlTemplateAction.update` 只设 `isEnabled`）⇒ 假红；自证用例又因「共用同一 `AnActionEvent` + 复位写反」二次假红。改断 `isEnabled == false` 后转绿，摘掉 `isEnabled` 赋值反向恰红。
**正例**：先读被守卫代码的 `update()`/绘制方法体确认契约，再定断言；自证用例每条用独立夹具（事件/对象），别复用可变状态。
**反例**：「不可见比不可用更严格，所以断言可见性更保险」——严格不等于正确，契约不对就是假红。
**Seen 1× (2026-10-24)**

### L13 PowerShell 脚本必须存 UTF-8 BOM，否则 PS 5.1 按 ANSI 解码中文注释

**证据**：T-TestGateGems（2026-10-24）`gate-readout.ps1` 无 BOM 落盘，PS 5.1 解析报「Missing closing '}'」且行号指向 `switch` 行（真实原因是中文注释的字节被 ANSI 解码后引号错配）；仓内既有 `push-all.ps1`/`ci-verify.ps1` 均带 BOM。
**正例**：新建 `.ps1` 一律带 BOM；另注意 Git Bash 直调 `powershell -File` 会吞退出码，核验退出码须 `-Command "& '...ps1' ...; exit $LASTEXITCODE"`。
**Seen 1× (2026-10-24)**

### L14 判「搜不到」之前必须先跑对照查询，证明搜索接口本身是好的

**证据**：`docs/调研报告/20261106-1435-磐维PanWeiDB容器镜像搜索与真机部署-调研报告.md` §1（Docker Hub `query=panweidb` 回 `count=0`，同接口 `query=opengauss` 回 `count=111` ⇒ 0 是真 0；反之 ghcr.io 连公开对照组 `homebrew/core/hello` 都取不到 200 ⇒ 该源本轮不可信，只能写「未取到」而不能写「没有」）；同族前例见 L7/L8 的「0 命中等结论必须配正对照」。
**正例**：每个「0」旁边并排给出同接口的已知非零查询读数；源本身不可用时把结论降级为「未取到」并写明原因。
**反例**：「搜了一遍没有」——不区分「接口坏了/被限流」与「确实不存在」，把工具故障写成事实结论。
**Seen 1× (2026-11-06)**

### L15 个人/社区重打包的厂商镜像只能作内部验证，对外结论必须标来源与「非官方」定语

**证据**：`f111b4de`（skill 增补「个人命名空间重打包镜像的识别与定级」）、`da16ff75`（经验总结 §三：`registry.cn-shenzhen.aliyuncs.com/shine_fei/panweidb:3.4.0` 个人命名空间，index digest `sha256:18ecce71…`，包内确为厂商产物 `PanWeiDB_V3.0-S3.4.0_B01`，但分发链无人背书）。
**正例**：先按「产品专属指纹」定性（磐维：`gs_install`/`gs_om`/`pwadmin`/`pwaudit`/`pwsso` 在场 ⇒ 真厂商产物被重打包；只有 openGauss 通用件 ⇒ 改名冒牌，禁用）；引用时逐处带仓库全名 + digest + 构建时间 + 「第三方重打包，非厂商官方分发」。
**反例**：搜到同名镜像就当官方镜像写进结论/文档（与「不拿 openGauss 冒充」同根，只是更隐蔽——内容是真的，背书是假的）。
**Seen 1× (2026-11-06)**

### L16 注释里点名的守卫必须真的存在；平台能力结论必须带「在哪一版成立」的取证

**证据**：`DynamicInstallSupport` 类注释写「本插件注册的 EP 全部落在平台 `dynamic="true"` 点位上，由 `DynamicEpComplianceGuardTest` 守卫」——`grep -rn "class DynamicEpComplianceGuardTest" src/` **零命中**，这条「已合规」从未被机器验过。事实相反：243（IU-2024.3.7.1）的 `com.intellij.sql.dialect` 两处声明都**没有** `dynamic`，253/262/263 都有（`dev-support/probe-platform-ep-dynamism.py` 按 HTTP Range 抽发行包实测，2026-11-06）。于是 243 上更新必然回退「重启生效」（平台日志 `is not unload-safe because of extension to non-dynamic EP …`），用户报障时先被怀疑成渠道问题。时间线：`<sql.dialect>` 注册自 2026-10-14（`6199a2a7`）起，仓库里 2026-09-11 那次「免重启热替换实测通过」发生在注册之前——能力是后来退化的，文档没跟上。
**正例**：能力结论写成矩阵并带可复跑取证——「253/262/263 可热卸载；243 不可（平台 EP 非动态）」＋ 取证脚本路径；守卫写成能反向验证的静态扫描（登记表 ↔ 描述文件双向一致、取证必填、注册只许落在可选依赖描述文件）。
**反例**：在注释里断言「已由某某守卫保证」「全部落在 dynamic 点位」，既没落测试也没写适用版本；三周后按这条老结论排查，代价是一轮误判。
**Seen 1× (2026-11-06)**

### L17 测试环境容器 cap 必须按实测峰值给（1.5~2×），且必须同步压 DB 内部参数并留 swap

**证据**：`103.36.220.245`（16GB）上 12 个数据库容器 cap 合计 **41 GiB**、**0 swap**、`available` 只剩 775MB（2026-11-06 23:5x 实测）；同一批容器的 DB 内部参数照大机器配——磐维 `max_connections=3000`（`postgresql.conf` 第 955 行覆盖第 82 行的 200）、`bulk_write_ring_size=2GB`、`cstore_buffers=512MB`，Oracle `sga_max_size=1.5G`，SQL Server `max server memory` 无上限，openGauss 族 `shared_buffers=1GB`。只压 cap 时容器会被自己的 DB 撑到 OOM-kill；只留 swap 则把泄漏掩盖成「慢」。本轮「swap 4GB + 内部参数 + cap 19.25GiB」三步做完：`available` 775MB → 3265MB，最高占用率 79.0%（OceanBase），`panwei_a` 109 词 / `gaussdb_kernel503` 197 词方言矩阵不退化（2026-11-07 00:5x）。
**正例**：`cap ≈ 实测常驻 1.5~2×` 且**必须高于 cgroup `memory.stat` 的 `anon`**（`docker stats` 含 page cache，会骗人）；先压 DB 内部参数再改 cap；主机 0 swap 时先加 4GB `/swapfile` + `vm.swappiness=10`；改完跑一次方言单实例子集确认词数与基线一致；反向验证要把 cap 压到**明显低于实际常驻**（本轮 64M 对 highgo 不够、16M 才 `OOMKilled=true/ExitCode=137`）。
**反例**：照「机器有多少内存」或「镜像 README 推荐值」给 cap，不看实测、不压内部参数；改完只贴「已设置 limit」而不验证容器真的被约束（也不验证数据库还能连通、方言矩阵还绿）。另有两个具体坑：磐维容器是 `--privileged`，它内部 `/etc/sysctl.conf` 的 `vm.swappiness=0` 会在容器重启时被 systemd-sysctl 写进宿主内核，把宿主设置冲掉；SQL Server 改小 `max server memory` 后内存不归还，必须重启容器才生效。
**Seen 1× (2026-11-07)**

### L18 时序类判据断言「调度值」，不要断言墙钟间隔比值

**证据**：`SenderLoopBackoffTest` 原本用「相邻两次 accept 的墙钟间隔」断言退避在增长（`gap2 * 2 >= gap1 * 3`，即要求 gap2 ≥ 1.5×gap1）。nightly run 42230813933 在 2 核 runner 上红：`gap1=235ms`（设计 150ms）、`gap2=306ms`（设计 300ms）→ 612 < 705。反推开销：**第一段多出 85ms、第二段只多 6ms**——第一轮连接付类加载/JIT/首次握手的一次性成本，被算进了「退避」；同一提交重跑即绿（run 42258160804）。
**正例**：给被测对象加**睡眠注入缝**（`BackoffSleeper`，生产 `Thread::sleep`、测试注入记录器），断言「被请求睡多久」的序列：`[150, 300]`；再补一条封顶用例（上限放到 5 次断开）断言 `[150, 300, 600, 600]`——与机器快慢完全无关，且比原来的比值断言更强（翻倍与封顶都被钉住）。反向探针：把 `backoffMs = min(backoffMs*2, max)` 改成常量 → 恰 2 红，报错直接给出期望/实际序列。
**反例**：拿 `System.currentTimeMillis()` 差值当判据、把一次性预热成本算进第一段；或为了「稳」把比例阈值一降再降（降到 1.1 时「完全不增长」也能过，判据失效）。
**Seen 1× (2026-11-07)**

### L19 夹具钉定值必须指向「CI 克隆的那个远端」可达的提交

**证据**：`c2358cb6` 把 `dev-support/test-demo/demo-revision.txt` 从 `74cc257` 改成 `fcb1faf`，而 `fcb1faf` 只是 `.worktrees/mybatis-plus-sql-visualizer-demo` 那个 demo 克隆里的本地合并提交——Gitee 与 GitHub 两侧 demo 仓都没有它。push-test run 42351712089 的 7 条测试腿（`fast (1)`/`fast (2)`/`test (1)`~`test (5)`）全部红在「准备测试 demo」步，日志 `error: pathspec 'fcb1faf' did not match any file(s) known to git` → `[test-demo] ERROR: git checkout fcb1faf failed`，一条断言都没跑到；同轮 `build` 与 `verify 243/253` 是绿的，说明红与代码无关。此后 gate run 42352333430 复现同一红。
**正例**：钉定值只在「已 push 到 CI 克隆的那个远端」时才成立。改钉定值前先跑两条只读判据——`git -C <demo> ls-remote origin master` 取远端 head、`git merge-base --is-ancestor <新钉> <远端head>` 判可达；两条都过再提交。本次处置：把该提交合入 demo master（`be5b727`）后双推（Gitee `99f0cb3..be5b727` / GitHub `7ded665..be5b727`），`fcb1faf` 成为 master 祖先，钉定值一字不改即全绿（gate run 42353060693 15 job 全 success）。注意那份克隆比远端还旧（连 `99f0cb3` 都没有），直接 `git push master` 是非快进，必须先 fetch + merge。
**反例**：在某个工作树的兄弟目录里做完夹具改动就提交钉定值，不确认那份克隆的远端有没有这条提交；或凭「本地能跑」判断 CI 也能跑——本地能跑恰恰因为对象就躺在本机那个克隆里，与远端无关。
**Seen 1× (2026-11-07)**

### L20 覆盖安装（就地换 jar）后，会话内所有插件读数都不可信——必须重启再验收

**证据**：2026-11-07 16:15 在日常 IDEA 2026.3（`IU-263.7307.200`）里用「Install Plugin from Disk」把 `2026.11.7` 换成 `2026.11.8-beta.1`，日志时序可逐毫秒对上：`16:15:11,913` 平台 SEVERE 落到本插件、`16:15:12,112` `SqlLogManager dispose`（旧实例卸载）、`16:15:12,391` 起 `Light service cn.song.mybatisplus.visualizer.config.PluginSettings not loaded because classloader is being unloaded` 开始刷、`16:15:15,95` 新 jar 写入 `plugins/mybatis-plus-sql-visualizer/lib/`、`16:15:16,11` 新版本加载。此后该会话一直刷到 16:31 仍未停（`idea.log` 单文件 16634 条，当天所有滚动日志合计 10 万+）。关键判据是**起点早于文件替换 3 秒**：平台是先卸载旧插件、再落新包、再加载，所以刷屏的起点正是旧 classloader 被 dispose 的时刻。旧版本的界面对象（预览页签、控制台 header 里被 `replaceAction` 接管过的工具按钮）仍挂在 UI 上，每次重绘成批回问那颗已死的 classloader——实测批次多为 11 的整数倍（11/22/33/55/77/88/110/242/286），卸载瞬间另有整段爆发（49/60/158 等），说明调用点是「一次重绘里若干次设置读取」，但**具体调用方未取证**（它在旧 classloader 里，新版本代码中不存在这条路径）。同一进程此前的日志段（`idea.11.log`/`idea.12.log`，替换动作之前）该串计数为 0。

**正例**：覆盖安装后**立刻重启 IDE**，再做任何验收；验收报告里注明「本轮是重启后的干净会话」。要判断某条 WARN 是不是插件缺陷，先看它在**未发生替换的会话**里是否复现——不复现就是卸载残留，别去 `src/main` 找调用点（调用方是已卸载的旧类加载器，新版本代码里根本不存在这条路径）。同理，就地换包的那个会话里做功能验收，结论一律标「会话态可疑」，只用来取证现象，不用来判定修复是否达标。

**反例**：把刷屏当插件缺陷去排查（会一路查到「谁在轮询 `PluginSettings`」，但那 11 次/批的调用方在旧 classloader 里，新版本怎么改都消不掉）；或者在刚换完包的会话里直接宣布「换源失效已复现/已修复」——本轮正是这个会话里同时看到 263 的 `setMapping` 真缺陷和这堆残留噪音，两者混在一份日志里极易互相误判为因果。
**Seen 1× (2026-11-07)**

### L21 改 data class 构造签名后，跨工作树的定向读数必须核对重编——增量编译残留旧字节码会伪装成回归

**证据**：券 F2b（`2521991a`）给 `AgentEvent` data class 加 `aggregatedCount` 字段。F 券自己工作树首跑 24 红全是 `NoSuchMethodError: AgentEvent.<init>`（删 `build/classes/*/test` 重编归零）；同日集成分支收口（merge `fc15e8da` 后）`CrossChannelDedupeTest` 17/18 红同一报错——该工作树从未跑过 F 的代码，陈旧 class 来自更早的编译产物；删 `build/classes` 重编后 217 例全绿。两次都在不同工作树复现，**生产代码零缺陷**。
**正例**：改 data class / 构造函数签名后，凡「别的检出过红」或「跨工作树合并」的定向跑，先 `rm -rf build/classes` 再跑；或在报告里声明「已核对本次编译任务实际执行（gradle 输出 compileKotlin/compileTestKotlin 非 UP-TO-DATE）」。
**反例**：看到 NoSuchMethodError 就去查「谁改了调用点」——调用点没错，是磁盘上的旧 class；或把这种红当真回归去回滚修复提交。
**2026-11-09 补充（最高频触发源）**：`config/PluginSettings.State` 是最常被加字段的 data class，**任何给它加字段的提交都会让全仓引用旧构造器 / `copy$default` 的测试类失效**。当日两例：①第一波三路合并（`3080cad3`）后集成分支 **202 红**，全是合成构造器 `NoSuchMethodError`，其中 `HibernateLogParserTest` 56/56 全红（该合并无人碰过 Hibernate）——清产物后 186 类 2041 例全绿；②L4 给 `State` 加 `ibatisXmlEnabled` 后 `GutterGroupsTest` / `MapperNavigationGutterGateTest` 6 红（`State.<init>` / `copy$default`）。
**判据**：某类**整类全红**且报错是 `<init>` 或 `copy$default` 的 `NoSuchMethodError` → 一律先 `rm -rf build/classes`，不要去查调用点。
**Seen 4× (2026-11-07 ×2, 2026-11-09 ×2)**

### L22 后台长命令禁用 `2>&1 | tail -N` 收尾——失败正文会被管道截断吞掉，退出码还会被换掉

**证据**：`./gradlew buildPlugin 2>&1 | tail -15` 后台跑，失败后日志文件只有 gradle 警告尾 + `BUILD FAILED in 9s`，`What went wrong` 段整段丢失；重跑不带 tail 全量落盘才看到真因（`prepareSandbox` 对沙盒内 jsqlparser jar 的 mmap 锁，`请求的操作无法在使用用户映射区域打开的文件上执行`）。tail 先于异常处理执行，等于把排障证据裁掉只留结论行。

**第二形态（2026-11-12）退出码也被换掉**：`./gradlew test … 2>&1 | tail -60` 拉起的后台任务，收尾报 `exit_code 0`，而日志正文写着 `359 tests completed, 2 failed` / `BUILD FAILED`——管道把整条命令的退出码换成了 `tail` 的。于是「后台任务完成」与「构建成功」在工具层读数上完全一样，只有翻正文才看得出红。附带一半是进度不可见：`tail` 要等上游 EOF 才开始输出，中途去读输出文件恒为空（本轮两次误以为任务没起来）。

**正例**：后台长命令重定向全量到文件（`> /tmp/build.log 2>&1`）+ 紧跟一句 `echo "GRADLE_EXIT=$?"` 把真退出码打进日志；判读用 grep 提取；必须 tail 时对失败路径重跑一次不截断的采集。`reverse_probe.py` 据此固定为全量捕获 + 失败时打错误行子集。
**反例**：`| tail -N` 当作「日志瘦身」常态化——tail 的 N 行里恰好没有失败原因时，二次重跑 whole job 的成本远大于第一次就落全量；或者拿后台任务的 exit_code 当构建结论。
**Seen 2× (2026-11-07, 2026-11-12)**

### L23 runIde 沙盒开着时 gradle 构建失败 ≠ 构建坏——先探针验证沙盒里的部署再决定重建

**证据**：集成分支 `buildPlugin` 两次 `BUILD FAILED in 4~9s`，`What went wrong`＝`prepareSandbox` 拷贝 `jsqlparser-5.3.jar` 到沙盒插件目录报 mmap 锁（用户正开着从该工作树启动的 runIde）。但沙盒内 `mybatis-plus-sql-visualizer-2026.11.7.jar` 部署时间（13:32）晚于全部合流，python zipfile 扫 class 找 `MIN_FLUSH_INTERVAL_MS`/`CountSnapshotAccumulator`/`catchUpBehind`/`hasStalePendingNotify` 四标记全命中——**沙盒已是目标构建，构建失败无需处置**。同日 L22 的 tail 截断让第一次还误判为「构建坏了要修」。
**正例**：runIde 沙盒存活期间要出新包：①先用类内字符串探针（zipfile 扫新增符号）验证沙盒 jar 是否已含目标改动；②含→直接用，不含→请用户关 IDE 后再 build（覆盖 mmap 文件必然失败）。runIde 每次启动自动部署当前源码构建，「部署失败」与「部署过期」要分开判。
**反例**：反复重跑 buildPlugin 撞同一把锁；或为绕锁去改 gradle 配置/清沙盒——沙盒是用户的活会话，动了直接打断验收。
**Seen 1× (2026-11-07)**

### L24 方言套件外指共享实例时固定表名互删——跑前先错峰确认，根治＝运行唯一前缀（已登记未实施）

**规则**：多个方言套件轮次（本地 Docker 外指腿 / `dialectExternalVerify` / CI 夜间方言腿 / DBX 临时探测）并发打**同一共享实例**会互删表——套件用固定表名 `dev_info` / `mpv_batch_probe` / `kw_probe_<词下标>`，`createWithRetry` 开跑先 `DROP TABLE`、finale 再 `DROP`，两轮并发即互相删对方的表。
**证据**：`DialectVerifySupport.kt:265/317/345/437/521-528/601/606/654/674`（固定表名与 DROP 逻辑，20261107 调研册 §五-3 代码级取证）；T-DockerGateOffload（2026-11-07，`a7c924e2`）把 Docker 五腿也并入外指通道后，共享实例的并发源面扩大（本地 `-PmpvDockerTests=true` 腿新增）。既有缓解仅「建表失败清残留重试一次」，对持续并发无效。
**正例**：跑外指腿前确认该实例当前无其他套件轮次（本地其他车道/夜间 CI 时刻表错峰）；**反例**：两轮套件同时打同一实例，A 轮种子表被 B 轮开头 DROP，读数红且难归因。
**Seen 1× (2026-11-07)**——本轮只登记不重构；根治方案（运行唯一 schema/表前缀）在计划册实施记录挂账，待复发或外指面再扩大时立项。

### L25 置顶 change-notes 新块时两件事同批做完并跑机器判据，且必须跑在打 tag 之前

**规则**：每次在 `gradle/plugin-change-notes.html` 加 `<h2>v{版本}</h2>` 块，同批做两件事并各跑一条机器判据——①**删掉上一块的「好用的话，欢迎到 Marketplace 页评分留言」**（`ChangeNotesRatingLineGuardTest` 断言全文只出现 1 次）；②**本版块内不得出现全角 `（ ） ：`**（`dev-support/inspect-plugin-zip.py` 的同名断言）。两条都要在 `git tag` 之前绿：文案会被 `patchPluginXml` 编进 zip 内的 `plugin.xml`，`plugin-change-notes.html` 与 `update/preview.update.json` 两处文案还必须逐字一致。
**证据**：2026-11-09 发行 v2026.11.9-beta.1 两处同时踩——①本地先跑守卫，`ChangeNotesRatingLineGuardTest` 1 红（评分引导句出现 2 次，`v2026.11.8` 历史块那行没删），改后 29/0/0/0；②自检放在**发布之后**才跑，`inspect-plugin-zip.py` 报 FAIL（第 3 条「新增中兴 GoldenDB 方言（第 15 种）…」的全角括号），此时 GitHub Release `438955517` 与 Gitee 发行版 `1290785` 已是带病产物，只能 tag 前移（`65e03bae` → `f9c5b0c3`）+ 重发一轮。
**正例**：把这两条判据串进「提交发行提交之前」的清单（`T-ReleaseChecklist-发行检查清单.md` RC-P3/RC-P5），`inspect-plugin-zip.py` 跑在 `git tag` 之前，红了改文案重提交，代价是几十秒。**反例**：先 tag、先发、再自检——代价是一整轮门禁 + 双通道重发 + tag 前移。
**Seen 4× (2026-10-29, 2026-10-30, 2026-11-09 ×2)**——评分引导句累计三次被守卫抓到（前两次见 `ChangeNotesRatingLineGuardTest` 类内背景 KDoc：用户 2026-10-29 与 10-30 两度点名），本次起把「新块置顶同时删旧块那行」写成发行动作而非靠记性；全角标点 2×（2026-11-09 两例）：beta.1 是第 1 条目含全角括号；**v2026.11.10-beta.1 是第 2 例、形态不同**——全角**冒号**在条目中部（「预览参数可填值：动态条件…」），括号检查过了冒号没查。且本次即便**发版前跑了守卫测试（绿）也没用**：`ChangeNotesRatingLineGuardTest` 只管评分句，全角判据在 `inspect-plugin-zip.py` 里、需要先有 zip——正确顺序是 tag 前**本地 `buildPlugin` 一次 + 跑检查器**（几十秒），而不是只跑守卫测试。本次 tag 两次前移（52f84f95→7d640980 夹具修正→44ab2393 文案）+ 双通道重发两轮。该半边继续留活跃区累计。评分句半边已按账本约定升级为 S11（`规范/编码与验收标准-规范.md`）。

### L26 用户报「多一个图标 / 点不跳」时按锚点矩阵逐格核，别只修截图指到的那一格

**规则**：同一个能力在插件里通常有**多套锚点与多条入口**，要列矩阵逐格核——
①预览图标至少两格：**调用行锚**（`JdbcTemplateSqlGutterLineMarkerProvider` 等，锚方法名 identifier）与**方法定义锚**（`StringSqlMethodGutterLineMarkerProvider`，锚方法签名、扫方法体）；XML 侧还有语句行锚。
②跳转至少三格：gutter 图标点击、`GotoDeclarationHandler`（Ctrl+Click/Ctrl+B）、`PsiReference`；且**方向是两格**（Java→XML 与 XML→Java 各一个 handler，互不共享）。
修完一处要把矩阵其余格逐个写成「现在谁认领 / 修后谁认领」，并在**每个格子**补真实入口用例。
**证据**：2026-11-10 内测轮连续两次踩同一形态——`beta.3` 只让位了调用行，`UserDaoImpl` 的方法定义行（`StringSqlMethodGutterLineMarkerProvider` 扫方法体字符串 SQL 调用，iBatis 语句 id 命中白名单、接收者归类 UNKNOWN）照旧亮一枚点开没有 SQL 的图标；券 B 只接了 gutter 箭头点击与 Java→XML 的 GTD，XML→Java 的 GTD 从未实现（`psi.referenceContributor` 只造 `refid`/`resultMap`/`parameterMap` 的引用），于是用户第二次报「还是有」「反之不行」。两处修复见 `63853e5d`。
**正例**：修前先列矩阵，修复按格落地；用例优先走**平台真实入口**（`GotoDeclarationAction.findAllTargetElements` + `GotoDeclarationOrUsageHandler2.testGTDUOutcome`，且必须在真 pooled thread 上跑——`Navigatable.navigationRequest` 有 BGT 断言，`ReadAction.nonBlocking().executeSynchronously()` 在 EDT 会内联），因为直调 handler 绿 ≠ 真实分派绿。**反例**：只跑被指出那个 provider 的单测就收工，下一轮真机再报一次同类。
**Seen 1× (2026-11-10)**——一次内测轮内两种锚点形态各犯一次，按「同类两例」记账。

### L27 发行版正文里的 `@注解名` 必须用 `<code>` 包裹，`&#64;` 与反引号都挡不住

**规则**：`gradle/plugin-change-notes.html` 是发行版正文的源（CI 切块 → `gh release create --notes-file`），里面任何 `@` 紧跟字母的文本（Java 注解名 `@Query` / `@Embedded`，也包括「库名@host:port」这类角标文案）都会被 GitHub 解析成**用户提及**，发行版页面的 Contributors 于是列出与项目无关的账号。写法只有 `<code>@X</code>` 有效：`&#64;`（先解实体再识别提及）与反引号（裸 HTML 块内不跑 markdown 行内解析）**实测均无效**。判据＝`ChangeNotesRatingLineGuardTest#test注解名不得写成裸加号`（剥 `<code>…</code>` 后全文不得有裸 `@`+字母）；发布后还要拉页面数 `user-mention` 锚点复核，不能只改源码就收工。
**证据**：`28b93e64`。`POST /markdown` 四形态同一样本对照：`&#64;Embedded` → 输出仍 `<a class="user-mention">`；反引号 → 原样保留反引号；零宽空格 → 有效但引入不可见字符；`<code>` → 无 user-mention。对账读数：2026.10.26 只显示 Embedded、2026.11.7 只显示 host（正文是「库名@host:port」）、2026.11.8 起显示 query and Embedded，三版与正文 `@` 词一一对应；三个账号（query＝Roger Que、Embedded、host）在仓内 5847 个提交里一个都查不到。已发布发行版 30 个正文按 PATCH 改写（改内容，不删重建），页面复核 Contributors 区块消失、`user-mention` 归零。
**正例**：先单版本探针——改 1 个发行版 → 拉页面 HTML 数 `user-mention` 确认机制，再批量。**反例**：第一版按「HTML 实体应该能挡住」直接选 `&#64;`，改完 2026.11.10 页面毫无变化，还先怀疑成页面缓存；真因是解实体之后才做提及识别，若当时直接批量就是 30 个白改。
**Seen 1× (2026-11-10)**——用户报障「Contributors 每次多两个我不认识的」，同轮内先踩 `&#64;` 再说清。

### L28 发行前置是「门禁绿」，不是「`buildPlugin` 成功」——`inspect-plugin-zip` 只证明包内自洽，不证明平台兼容

**规则**：`./gradlew buildPlugin` 出得了 zip、`inspect-plugin-zip.py` 全项 PASS，**都不构成发行判据**。打 tag 之前必须先有一条**门禁读数**：本地 `./gradlew releaseGate`（清单 RC-P1d 的默认路径，约 51 分钟）或 CI `build-plugin.yml` `mode=gate`（约 15 分钟，全面并行）。前者覆盖 `verifyPlugin` 三目标 + `verifyMarketplace` ×3 + 分片全量 + `e2eFakeIde`；后者同样跑 build / verifyplugin / verify×3 / marketplace×3 / test×5。缺这条就发版＝用「能打包」冒充「可发行」，用户装不装得上、平台兼不兼容全是未知。差别在**判据的对象**：`inspect-plugin-zip` 查的是包内三方版本一致与文案，`verifyPlugin` 查的是**这一份字节**在 243/253/263 三个目标 IDE 上能不能加载。
**证据**：2026-11-10 发行 `2026.11.11-beta.1` 时，本地只跑了 `buildPlugin` + `inspect-plugin-zip` + `ChangeNotesRatingLineGuardTest` + `BundlePlaceholderArityTest` 就打了 tag、建了 Gitee/GitHub 发行版（Gitee 1293976 / GitHub 440141088）；用户当场追问「你发行应该通过 CI 吧」。事后补派 `mode=gate`（run `42788614860` @ `9d32d936`，与 tag `308f5f96` 在 `src/` 零差异）。同一轮的前一版 `2026.11.10` 正式版是按规矩走的：release run `42758016138` 17/17、`verifyplugin` 验的就是待下发 zip。
**正例**：按 RC-P1d 走——本地 `releaseGate` 为默认路径，需要干净环境才走 CI；要省时间用 RC-P1c 的「零代码改动复用」判据（相对上次全绿提交在 `src/main`/`src/test` 零差异可沿用分片读数），**但 `verifyGate` 不适用复用**：版本号进 `plugin.xml`，jar 变了必须在新产物上重验。**反例**：把「构建成功 + zip 内省 + 文案守卫绿」当发行门禁（本轮即此形态），三条都绿也换不来一句「能装在 243/253/263 上」。
**Seen 1× (2026-11-10)**——用户点名「应该通过 CI」，同轮补派门禁。
### L29 织入型 enter/exit 配对不能只挂「方法体里显式出现的返回点」——被调方异常逃逸时没有出口点

**规则**：给方法体插 enter/exit 钩子做配对计数（嵌套门、计时、事务边界都属这类）时，只在 `IRETURN/LRETURN/ARETURN/ATHROW` 前插 exit 是不够的：**异常从被调方法抛出、而本方法体没有 try/catch/finally 时，字节层只有一条正常返回指令、一个 `athrow` 都没有**，出口钩子无处可插 → enter 计入、exit 永不执行，深度/计数永久偏 1。判据必须是「异常穿过本方法时也走出口」——把方法体包一层 catch-all handler（`TryCatchBlockNode` → 调同一出口 → `athrow`），并用「夹具方法自身无 try/catch、委托调用抛异常，抛完后同线程下一帧仍产出」的用例钉住；反向验证摘掉 handler 必须变红。
**证据**：T-NestGateObserve 离线定案（计划 §11.8.5，2026-11-10）：`StatementWeaver.weaveMethod` 的 `isTimedExit` 只认显式返回点；磐维 A 模式 `org.panweidb.jdbc.PgStatement.execute(String)Z` 原始字节只有 `invokevirtual executeWithFlags` + `ireturn`（无 handler、无 athrow），失败 DROP 的 `SQLException` 逃逸时出口不存在 → 该线程 depth 永久 +1，后续整类语句被当成外层抑制，而 `nestSuppressed` 为 0（被抑制的是最外层自己）。织入产物逐方法计数：enter=1、exit=显式返回数（`execute` 1/1、`executeQuery(String)` 1/2），排除「双重入口」；真机对照：两次失败 DROP 泄漏（`nestStale=2/nestMaxDepth=3`），两次成功 SELECT 不泄漏（`nestStale=0/nestMaxDepth=1`）。
**正例**：先做「织后字节 enter/exit/返回点逐方法计数」再下结论，并用成功/失败两条载荷做对照；**反例**：拿「exit 数 == 显式返回指令数」当健康判据收工——它天然看不见隐式异常出口（本轮首版就是这么把「成功路径也泄漏」写错的）。
**Seen 1× (2026-11-10)**

### L30 发行方向固定为 CI → GitHub → Gitee；`--from-github` 是 Gitee 拿到门禁产物的唯一通路

**规则**：发版的顺序是「CI 构建+门禁+发 GitHub（正文由 CI 模板生成，含提交号、构建记录、折叠历史）→ `gitee-release.py --from-github` 让 Gitee 取**同一份门禁产物**」。**禁止**「本地 `buildPlugin` → `gitee-release.py` 先发 Gitee → `mirror-releases-to-github.py` 镜像到 GitHub」——这条反路上有两个必然后果：①GitHub 的附件是**本地构建字节**，与门禁 job 产出并在平台上验过的那一份不是同一个文件；②GitHub 正文由镜像脚本拼装，**缺 CI 模板里的 `actions/runs` 链接、本版变更段与折叠历史**。判据：`GET /releases/tags/<tag>` 的 `assets[].size` 应等于门禁工件大小，`body` 里应含 `actions/runs/`。

**证据**：2026-11-10 同一天两轮都走反了——`beta.1` 与 `beta.2` 都是本地构建后先发 Gitee（1293976 / 1294211）再镜像 GitHub（440141088 / 440204503）。用户当场纠正「应该是 GitHub 单到 Gitee 吧」。实测同一 tag 的两份包大小不同：本地 7,882,789 B vs 门禁产物 7,882,832 B（`MPV-Build` 时间戳导致必然不同哈希）。纠正路径：`mode=publish` 挂门禁 run（`run_id`）→ `gh release upload --clobber` 覆盖 GitHub 附件 + 按 CI 模板重生成正文（回读 `body_len=17584`、含 `actions/runs/` 与「本版变更」）→ Gitee `--from-github --replace`。中途还撞上 `--from-github` 的守卫：本地同名 zip 与 GitHub 内容不同会**硬失败**并要求先移走本地包（这与 [[pitfall-mpv-build-timestamp-vs-ci-zip-hash]] 同源）。

**正例**：`mode=release` 一条命令做完「构建+门禁+发 GitHub」，再 `--from-github` 收 Gitee；离线自测/内测包另存，不混进 `build/distributions`。**反例**：先本地打包去发 Gitee，再用镜像脚本补 GitHub——两侧字节与正文的来源都不一致，且事后要再跑一轮 publish 才能纠正。

**Seen 1× (2026-11-10)**——用户一句「应该是 GitHub 单到 Gitee 吧」点出方向错，同日两版都受影响。

### L31 探「平台有没有某能力」不许锚实现类名——类名/包名/module 属内部结构，会随版本搬走

**规则**：反射探测平台能力时，锚点必须是平台**对外契约**：EP 名（`com.intellij.mcpServer.mcpToolset`）、设置页 id（`applicationConfigurable` 的 `id` 属性）、插件 id。实现类 FQN 只能出现在**候选链**里并保留旧形态，不能写成唯一判据；`Class.forName` 落空时的提示文案也不得断言「版本不够」——「页面搬走了」和「没装插件」是两回事，用户看到版本断言只会来报「我被拦了」。判据：同一页面上的两个探测（能力横幅 vs 动作按钮）必须指向同一层契约，否则会出现「横幅说平台在，按钮说平台不达标」的自相矛盾帧。
**证据**：T-McpSettingsJump（`3106e4c6`，2026-11-11 用户真机 2026.3 / `263.7307.200`）：平台 263 把 MCP Server 设置页从 `com.intellij.mcpserver.settings` 搬进新 content module `intellij.mcpserver.frontend`（`required-if-available="intellij.platform.frontend"`）并换包名；旧 FQN 在整安装（lib + plugins，含 `lib/modules/`）**零命中**，而页面 id `com.intellij.mcpserver.settings` 与插件 id `com.intellij.mcpServer` 一字未变。同帧横幅探 `com.intellij.mcpserver.McpToolset`（仍在公开模块 `intellij.mcpserver`）为真，按钮探旧类名为 null ⇒ 页面自相矛盾，提示把根因指成「需要 IDEA 2025.2+」。仓内同款问题还有第二份（`PluginSettingsPanel` 零调用方副本），两处同批修掉。
**正例**：先 `unzip -l` 扫清「旧类名还在不在、页面 id 变没变、EP 变没变」三件事再动手；修法＝候选链（新形态在前、旧形态在后）→ 平台按 id 打开并选中 → 都失败才提示，且用源码守卫钉住「设置页类名只允许出现在候选链文件」。**反例**：拿某个版本 jar 常量池里抄到的 FQN 当长期契约（本仓 20260915 就是「262 mcpserver.jar 常量池实证」写下这个类名的——当时全对，半年后搬走）。
**Seen 1× (2026-11-11)**

### L32 反向验证前先提交——`git checkout -- <file>` 回退的是整个文件，未提交改动一并消失

**规则**：做「摘掉修复分支 → 期望变红 → 还原」时，被摘的文件必须先处于已提交状态。否则 `git checkout -- <file>` 还原的是**该文件到 HEAD 的全部内容**，同文件里其它未提交改动（常量区、data class 字段、相邻逻辑）一起被抹掉。两条路：先提交（哪怕只提交一版中间态）再摘；或还原时只用 Edit 把那一行改回，不用 checkout。
**证据**：T-CopyPlaceholderMarker（2026-11-11，`2e553316` 前的自查轮）：把 `assembleProduct` 的 `if (placeholderCount > 0)` 临时改成恒假跑反向，跑完 `git checkout -- CopyExecutableSqlAction.kt`——该文件的占位常量、`Product.simulationPlaceholderCount` 字段、markers 互斥逻辑全在未提交状态，一次抹掉，四处改动重做一遍。
**正例**：摘之前先 `git status` 确认目标文件干净；**反例**：把 checkout 当「撤销我刚才改动的那一行」用。
**Seen 1× (2026-11-11)**

### L33 增量构建下别手删 `build/classes`——要清就 `clean`，否则 Kotlin 侧 UP-TO-DATE、Java 侧找不到符号

**规则**：为躲「旧字节码冒充回归」（L21）而清产物时，用 `./gradlew clean`（或 `--rerun-tasks`），**不要 `rm -rf build/classes`**。手删 circles 后 Gradle 的 `compileKotlin` 仍判 UP-TO-DATE 被跳过，而 `build/classes` 已不存在 ⇒ 依赖 Kotlin 类的 Java 源（如 `SqlLogToolWindowFactory` 引用 `SqlLogPanel` / `PluginSettings`）整片报「找不到符号」，看起来像真编不过。
**证据**：T-IbatisParamClassQuote 合并态验证（2026-11-11，master `e06fb3f50`）：`rm -rf build/classes build/tmp/compileKotlin build/tmp/compileTestKotlin` 后跑测试，`:compileJava FAILED — 16 个错误`，全是 `找不到符号 类 SqlLogPanel/PluginSettings`；改用 `./gradlew clean test ...` 后 49 suite / 550 例全绿。
**正例**：清产物用 `clean`，或在同一命令行带 `--rerun-tasks`；**反例**：只删 `build/classes` 就以为等价于 clean。
**Seen 1× (2026-11-11)**

### L34 短名解析在多模块项目里必须按「就近」收窄——单一命中才用等于常态弃权

**规则**：用短名索引（`PsiShortNamesCache.getClassesByName`）把 XML/配置里的类名（如 iBatis `parameterClass`、`<typeAlias>`）解析成 `PsiClass` 时，**不能只接受「全项目唯一命中」**——多模块工程里同名实体是常态（demo 项目三个 `User`），一律弃权等于该能力在真实项目里从不生效。收窄顺序：FQN 直查 → 短名唯一 → **按语句/file 所属模块过滤**（候选与使用点同模块者优先）→ 仍不唯一才弃权。
**证据**：T-IbatisParamClassQuote（2026-11-11，`cb4ec1ed3` 首版）：demo 里 `sqlmap/User.xml` 的 `parameterClass="User"` 解析到 3 个候选（`cn.song.ibatis2demo.model.User` / `probe.User` / `cn.song.mybatisplusdemo.entity.User`）⇒ `singleOrNull()` 返回 null ⇒ 引号修复在真机完全不生效，用户从 Java 调用行与 XML 语句行两处复现同一现象；而单模块的 Heavy 测试全绿，读不出这个问题。
**正例**：候选解析一律带上下文（模块 / 源根 / 包）收窄；**反例**：拿「唯一命中」当保守策略——测试环境往往只有一个同名类，掩盖真实项目的重名面。
**Seen 1× (2026-11-11)**

### L35 判「用户手上那个包有没有这条修复」看 jar 内的类/字节，别信版本号

**规则**：用户报「还是复现」时，判「他跑的是不是含修复的包」必须拿产物取证——扫沙盒插件 jar 的类表/字节（`zipfile` 列名即可）或以行为差异为准。**插件版本号不是判据**：`runIde` 每次启动按当前源码构建部署，`plugin.xml` 版本号没人动就仍是旧号，「版本号没变」与「代码是旧的」是两件事。
**证据**：T-IbatisParamClassQuote（2026-11-11）：第一轮我据此弹「沙盒是 `2026.11.12-beta.2`，不含本轮修复」并让用户重装；第二轮扫 jar 才看到 `IbatisParameterClassResolver.class` **已在包内**（用户重启过沙盒，runIde 已部署新源码）——版本号相同掩盖了「新代码已上线」，白耗一轮，且把用户引向「去看包版本」。
**正例**：`python -c "import zipfile;print([n for n in zipfile.ZipFile(j).namelist() if 'X' in n])"`；**反例**：拿版本号/构建时间当唯一判据、据此下「你没装上新包」的结论。
**同日内第二例（T-ExecPsiGate，2026-11-11 晚）**：连续给了四个同版本号（`2026.11.12-beta.2`）的包，只在嘴上说"重建了"，没给可自查的凭据——用户实际装的停在 `7ab0d5335`（F6b），后续 F7 那次修复从未被验过，白耗一轮。补的规矩：**每次给包必须同时给 `sha256` + 一句"装完确认 `plugins/<id>/lib/<jar>` 的修改时间"**，并在判定"他的包是旧的"之前先扫安装目录的字节。
**Seen 1× (2026-11-11)**

### L36 「多候选就弃权」这类保守降级，必须先在真实项目形态上量出歧义度，否则是「测试全绿、真机从不生效」

**规则**：写「歧义时宁缺勿错」的降级分支（短名解析、模糊匹配、多结果取一）之前，先用一条命令量真实数据的歧义度（同名类数、候选分布、模块数）。歧义在真实项目里是常态时，「弃权」等于该能力从不生效——正确做法是**引入收窄维度**（模块 / 源根 / 包 / namespace）把候选降到唯一，而不是把弃权当稳妥。同一道理也适用于「取第一个」「按字典序取最小」这类兜底：先看真实分布再定。
**证据**：T-IbatisParamClassQuote（同日两版对照）：`cb4ec1ed3` 写 `singleOrNull()`（弃权），单模块 Heavy 测试全绿；真机 demo 有 3 个 `User` ⇒ 恒弃权 ⇒ 用户从 Java 调用行与 XML 语句行两处复现，多花两轮。收窄维度＝语句所属模块（`ModuleUtilCore.findModuleForPsiElement`），`5a2e0419d` 落地后真机验收通过。歧义度本就是一条命令的量：`find . -name User.java | wc -l`。
**正例**：先量歧义度再定策略，策略里带收窄维度；**反例**：把「唯一命中才用」当默认保守值——它在测试环境（单一同名类）永远绿。
**Seen 1× (2026-11-11)**

### L37 为「防粘词」补分隔时，判据必须取「不补就拼成一个词」，不能取「两侧都无空白」

**规则**：在拼接缓冲里补分隔符（空格）防粘连，判据要落在缺陷本征上——只有**左侧收尾是操作数**（标识符字符、右括号、右方括号、引号、`?`）**且右侧起笔是标识符字符**时才补；「相邻两段都无空白」是表象判据，它把 `(`、`,`、`)` 与 `?` 之间也一起撑开。同理，任何「为了不粘」而加的分隔/兜底，先问一句：**不加真的会错吗**——不会错的地方加分隔，就是把一个契约（渲染形态逐字比对）改成另一个契约。
**证据**：T-MbgExamplePreview R2（2026-11-11）：R2 首版按「两侧都无空白即补空格」修 `andand`/`1or`，`<where>` 里的 `IN (?,?)` 被撑成 `IN ( ? , ? )`，`SqlPreviewPanelForeachValuesTest` 两例在 CI 连红三轮（run `42959261562` 分片 4、`42963361087` 分片 4、`42966848222` 分片 3）；收到「会拼成词」后定向 80/0/0、宽面 103 suite / 1124 例 0 失败，而 `andand`/`1or`/`)or(` 三种粘连判据同时保持（`6212f24e0`）。
**正例**：判据写成「不补会拼成一个 token」；**反例**：把「相邻且都无空白」当补分隔的条件，靠 CI 去发现它撑开了哪些既有契约。
**Seen 1× (2026-11-11)**

### L38 解释「平台为什么拒绝」时，判据必须与被拒对象同一层级

**规则**：复述平台闸门条件前，先读它的字节码确认**它在问谁、问的是哪一层**（文件 / 父目录 / 内容根），我们的观测面跟着同层级取。用文件级谓词去解释「父目录不可用」永远解释不通，只会落到「未知」——而交互层是按成因挂入口的，「未知」等于没有入口。推论：**「判不出成因」不等于「没有可给的下一步」**，兜底档也必须给一个可点的动作。
**证据**：T-ExecPsiGate F5（2026-11-11）：263 的 `AbstractFileViewProvider.shouldCreatePsi()` 第一层判 `isFileIgnored(file.getNameSequence())`、第二层判 `findDirectory(vFile.getParent())`；真机（`IU-263.7307.200`，`IntelliJIdea2026.3/log/idea.log` 行 27664–27749）上文件级 `isExcluded=false / isUnderIgnored=false / regHideExcluded=false` 全绿而 `findFile` 恒 null，归因落 UNKNOWN，面板只剩一行红字（用户原话「执行sql错误，没有引导用户调整配置信息」），白耗一轮验收。改法两件：目录探针并进分类器（`dirCanHostPsi`）+ 写文件前用同一个平台调用先探一次；`UNKNOWN` 档同时补上兜底入口。
**正例**：先反编译确认闸门的**接收方层级**，探针直接复用平台那一条调用（`PsiManager.findDirectory`）；**反例**：把手边现成的文件级谓词当通用解释，靠真机日志一轮轮试。
**Seen 1× (2026-11-11)**

### L39 修「某条信息没显示」之前，先枚举**这个回调的全部消费点**是否都接上了

**规则**：给"失败原因 / 提示 / 入口"接线时，不许只改「我复现时走的那一条调用链」。先去数同一个回调（此例 `onError`）在文件里有几个调用点，逐个核对；再接一条**源码护栏**（禁用 `onError = { … resultPanel.showError`）把"漏接"变成红灯。判定顺序也要改：看到"同一功能两个入口，只有其中一个正常"，先 diff 两个入口的**接线**，不要先怀疑数据或竞态——接线差异能造出与竞态完全一样的现象（有时有、某入口恒无）。

**证据**：T-ExecPsiGate F9（2026-11-11）：`ui/SqlPreviewPanelExecution.kt` 里五个执行调用点只有两处把 `onError` 接到 `showTempFileError`，主执行路径（`recoverConsole` 分支 / `executePrepared` / 全链 `SqlExecutor.execute`）与 EXPLAIN 结构化准备链四处直连 `resultPanel.showError`。于是"临时文件失败 → 按原因给蓝字入口"这条功能在**直接点执行**上从未生效过，只有「更多 → 切换数据源」那条能看到——用户连着三轮用这个对照给我指路（"点击更多-切换数据源 才正常"），而我前五轮都在追"成因在哪一步丢"，前提就错。修完 `ExecPsiFailureHintTest` 19/0/0，反向把主路径改回裸 `showError` ⇒ 护栏用例恰 1 红。

**正例**：接一条逻辑前先 `grep` 该回调的全部调用点并逐一改；改完加源码扫描护栏；**反例**：只改复现路径，然后靠"现象时有时无"去猜并发。

**Seen 1× (2026-11-11)**

### L40 一次性元数据别走共享可变状态，让它**随载体走**（消息/返回值/回调参数）

**规则**：跨线程、跨渲染次数传递的小块元数据（失败成因、诊断快照、提示级别）优先编码进**载体本身**——错误文案里带标记、返回值里带字段、回调参数里带对象。只要还留一处"谁清谁写"的约定，就一定会被重复渲染、并发写方或类重载打掉。共享字段只配当兜底层。配套口径：读方**不清空**（由下一次写入覆盖），清理责任不放在读方。

**证据**：T-ExecPsiGate F3→F8（2026-11-11 五轮，同一病根犯了四次）：① F3 预热链入口清空 `lastTempFileFailure` → 文案退化 `unknown`；② F5 并发失败覆盖 → 归因错；③ F7「读一次就清」被**同一次失败的第二次渲染**清掉（一条失败投递两次：`gate.abort(持有者)` + `consumed.complete(排队意图)`，切源那条 `prepareOwner=null` 只投一次所以正常）；④ F8 才改成把成因编码进文案 ` [hint=XXX]`（`withExecHint` / `parseExecHintMessage`），至此跨线程/跨渲染/跨类加载器全部免疫。复现用例：把成因编码退回旧行为 + 共享字段置空 ⇒ 恰 2 红。

**正例**：`fail()` 产出带标记的 reason，UI 侧解析并剥离标记（展示干净、日志保留）；**反例**：把成因存 object 字段、靠读方 `consume` 清空，然后为每次"丢了"再打一个补丁。

**Seen 1× (2026-11-11)**

### L41 收窄「怎么认一个对象」的契约前，先枚举仓里既有实现与夹具暴露的是哪一种形态

**规则**：把「取不到 X 就丢弃/降级」写成新契约之前，先 `grep` 全仓有多少实现与测试桩只提供**另一种**同名取法（`getDisplayName` vs `getName`、`getUrl` vs `getJdbcUrl` 这类平台同义方法）。只认一个方法名会把合法条目静默判成"无名"，症状**不是报错**而是「集合被滤空 → 索引落空 → 回调既不 onChosen 也不 canceled → 调用方永远收不到结果」，最后表现为一堆**看似无关**的用例红。取名/取键这类兼容面一律走**方法名候选链**，全都取不到才算缺席。

**证据**：T-DsListAddSource 回归（2026-11-12）：`c728c8058` 给 Runner 弹层加「`optionalRunnerName` 为空即不渲染」，而该函数只反射 `getDisplayName`；`LogToSqlPanelExecuteTest.CallbackRunner` 只实现 `getName()`（数据源对象形态也一样）⇒ 唯一一条被滤掉、弹层变空，`testPlatformPopupSelectionDisposeIsNotUserCancellation` 在推送测试连红四轮（run `43127711688` / `43129449046` / `43134264631` / `43136753539`，断言 `expected [CallbackRunner@…] but was []`）。修法＝候选链 `getDisplayName` → `getName`，都空才判无名（`d289ac1a6`）；定向 49/0/0，反向把候选收窄回单方法 ⇒ 恰 2 红（CI 那条 + 新增回归用例 `testB侧_只暴露getName的runner必须保留_不得判无名丢弃`）。

**正例**：候选链 + 「两条都取不到才缺席」+ 补一条钉死「只暴露另一种取法」的用例；**反例**：拿单一反射方法名当"这个对象有没有名字"的唯一判据，靠 CI 去发现被滤掉了什么。

**Seen 1× (2026-11-12)**

### L42 一条测试腿「绿」却不产出逐例口径，就是空绿——先补可见性，再谈覆盖率

**规则**：凡带「环境不满足就 println + return」这类显式绿跳的腿，必须在同一 job 内产出一行逐例汇总（files / tests / skipped / failures）。没有这行数字，`BUILD SUCCESSFUL` 什么都不证明。判定顺序也固定：**腿红先读环境**（实例起没起来、连没连上），**腿绿先读逐例口径**（跑了几例、跳了几例）；两者都不读就写结论，等于替下一次踩坑铺路。闸门先只钉「检测本身失效」——`files=0` 或 `tests=0` 即失败；跳过率留观察，别在没摸清底数时动门禁口径。

**证据**：T-NightlyLocalMysql（2026-11-12）：`database` 腿自 2026-11-05 起连的是远端 `103.36.220.245:25900`，那台机 MySQL 不可达时 `DataSourceJdbcSmokeTest` 等用例按「显式绿跳」直接 return，于是夜间连红 6 轮里这条腿一直是 success，日志里只有步骤名和 `BUILD SUCCESSFUL in 9m 24s`，没有一个逐例数字。补一步解析 `build/test-results/**/TEST-*.xml` 后首轮读数 `files=10 tests=86 skipped=0 failures=0 errors=0`（run `43146861779`），同轮 `e2e` 从 `PASS=13 FAIL=13` 变 `PASS=26 FAIL=0`。

**正例**：`if: always()` 汇总 XML 打印逐例读数，只对「一例没跑」设闸；**反例**：拿堆栈里唯一的 `BUILD SUCCESSFUL` 当覆盖证据。

**Seen 1× (2026-11-12)**

### L43 CI 需要外部实例时，先问「它能不能由 runner 自给」，再把环境腿与代码眼分开定责

**规则**：把测试腿绑到共享服务器前，先查三件事：runner 能不能自起该服务（GitHub 的 service container **只在 Linux runner 支持**，windows/macOS 一律不可用）、镜像里有没有 server（有客户端 ≠ 有 server）、自起要几步。能自给就自给——共享实例会把「别人的机器挂了」变成「我们的门禁红了」，而且连带断言会把根因埋掉：一个库连不上能生出十几条业务断言失败，读报的人只看到最后一条。确实不能自给的腿（需要多种真引擎的 dialect 这类），必须在记录里显式写「环境腿、只能服务器侧修」，别让下一个人从代码里找原因。

**证据**：T-NightlyLocalMysql（2026-11-12）：`e2e` 腿连 secret `MPV_DB_URL`（`103.36.220.245:25900`），那台机 MySQL 不可达时 demo 应用照常起在 8082，但首条查库 `HikariPool-1` 初始化失败（`CommunicationsException` → `ConnectException: Connection timed out`）→ 探针「等应用就绪超时 120000ms」→ `断言 PASS=13 FAIL=13`，11-10 与 11-12 两轮同形。改成 runner 本地 zip 8.0.45 + `mysqld --install` 注册 Windows 服务后 `PASS=26 FAIL=0`（探针 16.6s，run `43143657370`），同轮 `database` 腿转真跑。同轮 `dialect` 仍红在 `panwei_a:26101 / gaussdb_kernel503:25882 Connection refused`——那是环境腿，代码侧绕不过去。

**正例**：`services:` 不可用时用「zip + `--initialize-insecure` + 注册 Windows 服务」自给，起库与灌库两步都硬失败；**反例**：让 CI 腿依赖一台没有 SLA 的测试机，再靠加断言去「提高覆盖」。

**Seen 1× (2026-11-12)**

### L44 `Query.findFirst()` 当短路用会在 `ReferencesSearch` 链上恒空——用 `forEach` 提前终止

**规则**：想在「全项目引用检索」这条链上只要第一个命中，别用 `ReferencesSearch.search(...).findFirst()`：真机大工程上它能稳定返回 null（同一方法 `Find Usages` 有 253 处引用、索引健康检查也是干净的）。正确写法是 `forEach { hit = it; false }`——语义等价、同样提前终止、不把结果收成 List。更要紧的是**判据顺序**：这类「检索拿不到 → 静默返回 null → 上游退回占位骨架」的链路，必须留一条能自证的读数（本次加了 `[m2] trace … methods=1 noCallSite -> criteria=0`），否则「已修好」与「从未生效」在日志里长得一模一样。

**证据**：T-PreviewOpenPerfM2 端到端验收（2026-11-12，cust-big-demo 大工程）：面板产物停在 `WHERE ( {{ criterion.condition }} ? )`、`[open-perf] … m2=101`，埋点给出 `methods=1 noCallSite`；同刻在 IDE 里对同一方法 `Alt+F7` 得 253 处。同一 `findFirst` 写法此前只在 Heavy 测试里跑过，而那条路本来就 `refs=0`（引用索引未建），**从未被真机证过**——M5 的「findFirst 提前终止」属未验即用。

**正例**：`forEach` 取首个命中 + 空结果时打 `totalRefs` 诊断；**反例**：把「测试里也能编译」当成「检索语义成立」。

**Seen 1× (2026-11-12)**

### L45 硬杀沙盒 IDE 会留坏索引：`NOT indexable` + Go-to-File 全空，删 `system/index` 重来

**规则**：沙盒（runIde）别 `Stop-Process -Force`。硬杀后重启常见两症：`ProjectIndexableFilesFilterHealthCheck - Following files are NOT indexable but they were found in filter. Errors count: N`，以及 `Ctrl+Shift+N` 对任何文件名都返回空（工程树看起来正常、类索引还能用，最容易被误判成「工程没加载」）。处置：关掉沙盒后删 `<sandbox>/…/system/index` 与 `system/caches` 再起，重扫约 2 分钟即恢复。**正确关法是关 IDE 窗口（等同 File → Exit）**：它会走正常退出序列，刷缓存与索引；Gradle 工具窗里停 `runIde` 任务与 `Stop-Process` 在这一点上等价（都是直接终结进程）。附带现象（偶发）：`config/options/trusted-paths.xml` 有时来不及落盘，下次启动还会弹「Trust and Open Project」——本轮两次硬杀里发生一次、另一次正常落盘（mtime 早于杀进程），所以它不是稳定判据。

**证据**：2026-11-12 端到端验收，为换版本两次硬杀沙盒 IDE；首轮 Go-to-File 正常，第二轮起对 `CustAddressMapper`/`pom.xml` 一律空结果，日志 `Errors count: 1502`（示例行含项目根目录与 `pom.xml`）；删索引重启后同查询立刻命中两条。

**正例**：换版本前走 IDE 正常退出，必须硬杀时预留「删索引重扫」的时间；**反例**：把 Go-to-File 空结果当成内容根/模块配置问题去改 `.iml`。

**Seen 1× (2026-11-12)**

### L46 合成输入先看输入法：`typewrite` 会被中文 IME 吃成候选词

**规则**：GUI 探针往 IDE 输入框写字一律走**剪贴板粘贴**（`gifkit.paste`：`SetClipboardData` + `Ctrl+V`），别用 `pyautogui.write/typewrite`——中文输入法处于中文态时 ASCII 会被合成候选词（实测输 `pom.xml` 得 `破灭. x|m`，结果列表全是「消灭了/小毛驴/写满了」），于是「查不到文件」这类假阴性会一路误导到内容根、索引、Maven 导入上去。同一次会话里前几轮成功、后几轮失败，正是 IME 被快捷键（如 Shift 切换）翻过去的表现。

**证据**：2026-11-12 端到端验收，同一查询先成功后失败，截图查询框是 `破灭, CustAddressMapper`；改粘贴后一次命中。

**正例**：粘贴后截图核对输入框文本再 Enter；**反例**：拿「查不到」直接推断索引坏了（本轮真索引坏另有一条 L45，两者叠加过一次）。

**Seen 1× (2026-11-12)**

### L47 多仓推送别拿凭据文件里的 `owner/repo` 拼 URL；推送脚本一律纯 ASCII

**规则**：同一个 owner 下多个仓库时，推送 URL 里的**仓库名必须显式写死**，不能读 `.local/*.json` 的 `repo` 字段——那份文件常常是从别的仓整体拷过来的，字段还写着原仓名，拼出来的 URL 会把 A 仓的提交往 B 仓推。push 前先把目标 URL 打出来（token 打码）再执行。另外推送脚本**只写 ASCII**：PowerShell 5.1 对无 BOM 的 `.ps1` 按 ANSI 解码，中文注释会把下一行吃掉——变量赋值被吞后 URL 变成 `https://host/owner/.git/`（实测 403 `token is not authorized to access this project`，且报错文案完全不指向「变量为空」）。

**证据**：2026-11-12 推 demo 仓，第一版脚本用 `$ge.repo` 拼 URL → 实际目标 `gitee.com/songrenshuo/mybatis-plus-sql-visualizer.git`，被远端 `! [rejected] (fetch first)` 拦下才没把 demo 提交推进插件仓；改成写死 `mybatis-plus-sql-visualizer-demo` 后同一脚本又因中文注释吃掉 `$repoName` 赋值而推成 `gitee.com/songrenshuo/.git/`（403）；注释译成英文后 `30932a0..7689f60` 双仓正常推上。

**正例**：仓名常量 + 回显目标 URL + 纯 ASCII 注释 + `-c credential.helper=` 与 `GIT_TERMINAL_PROMPT=0`；**反例**：读凭据文件里的 `repo` 当目标仓（它只是元数据，没人保证与所在仓一致）。

**Seen 1× (2026-11-12)**

### L48 标签被截成 `Xxx.yy...` 先别改省略号逻辑：那是 Swing 自己截的，内边距没算进列宽

**规则**：`JLabel` 的文本放不下时，**Swing 自己**会把尾部换成 `...`（保留头部），这条路绕开我们自己的省略实现——所以看到「头部保留 + 尾省略号」就要怀疑「组件宽 − 内边距」不够，而不是去调中段省略的保留比例。纪律有两半：① `preferredSize.width` 被钉成「文本宽度」的行，边框必须**先落地再量**（`getInsets` 晚于度量就等于按「没有内边距」算可用宽度）；② 列宽预算要按「每行文本 + **该行**内边距」取最大，不是「最大文本宽 + 最大内边距」。判定截断态时用可绘宽度（列宽 − 左右内边距），这样即使因视口上限被压窄，也是走我们那套「保留尾部」的中段省略，而不是被 Swing 摘掉尾巴。

**证据**：2026-11-12 真机（`deleteByExample` 面板）：三行集合标签里 `criteria.criteria` / `criterion.value` 显示成 `criteria.crit...` / `criterion.va...`，而同一套「列宽随视口自适应 + 全名 tooltip」的修复（`2096fa2f5`）已在包内。两步取证：8× 放大截图确认是保留头部；JDK8 headless 探针 `SwingUtilities.layoutCompoundLabel(avail=70, "criteria.criteria")` → `"criteria.crit..."` 逐字复现。算账也对得上（同字号探针读数）：文本 77px 被 `LABEL_COLUMN_MIN_PX=80` 抬到 80，行内边框再吃掉 8px，绘制区 72px < 77px。

**正例**：边框由 `columnLabel(text, width, border)` 在度量前落下 + 预算吃进 `border.getBorderInsets(null).left`；**反例**：只把列宽上限从「硬编码 260」改成「视口 × 比例」——那一步改了上限、没碰内边距，真机上该截的还是截。

**Seen 1× (2026-11-12)**

### L49 横幅/告警挂在「重渲染」路径之外＝停在上一轮的判定

**规则**：交互式预览里同一份产物有两条更新路径——「开窗」与「勾选后重渲染」。任何由产物派生的**呈现态**（告警横幅、风险提示、降级说明、行尾标注）都必须在**两条路径**上都重算；只在开窗时算一次，用户改完勾选看到的就永远是开窗那一刻的结论。落地口径：把「本轮产物派生的态」集中成构造函数（本轮＝`updateMeta`），重渲染分支结尾统一调它；**新增派生态时同时问一句「重渲染分支会不会重算」**。

**证据**：2026-11-12 真机：`deleteByExample` 取消勾选 `_parameter != null` 后产物已从 `delete from CUST_ADDRESS WHERE ( {{…}} ? )` 变成 `delete from CUST_ADDRESS`，面板上「预览未完整还原」照挂（`xmlErrors` 确实按帧刷新，但消费它的 `updateMeta` 只有 iBatis 分支调）；反过来，产物变成无 WHERE 的全表 DELETE 时也**没有任何风险提示**——两个症状同因。

**正例**：重渲染分支结尾 `updateMeta(result)`，风险判据取**未回填**的本轮产物；**反例**：把横幅状态当成 `showResult` 的一次性产物（或拿回填后的文本判关键词——参数值里的 `where` 会污染判定）。

**Seen 1× (2026-11-12)**

### L50 摘除式反向探针要自证生效：改完 grep 目标语句，剩余引用必须是 0

**规则**：临时摘掉一行修复做反向验证时，`old_string` 只覆盖**那一行本身**，别拿紧邻的插入行当锚点；改完立刻 `grep -c '<目标语句>' src/main` 确认它真的不再被执行（从 1 变 0）。这类探针最阴的失败是「探针没落地」——修复仍在跑，用例当然还是绿的，而这份绿与「这个修复其实是多余的」在读数上一模一样。同理，多路探针同跑时，某一半边绿要先看探针足迹，不要直接读成「该修复不必要」。

**证据**：2026-11-12 T-PreviewBannerLiveRefresh：摘 `updateMeta(result)` 时 Edit 的 `old_string` 取的是紧邻的 `writeRiskWarning = fullTableWriteRisk(base)` 行，于是新加的 `// updateMeta(result)` 注释与**原来的**调用并存，探针等于没打；三处探针同跑时 A/C 各自变红、D 那半边绿，差一步就写成「这条修复不必要、可以删」。换成整行锚点并 grep 确认剩余 0 次后，`XmlPreviewBannerRefreshTest` 恰 1 红，断言正是「产物已无占位 ⇒ 陈旧渲染警示必须随本轮清除」。

**正例**：探针后先 `grep -c` 自证 + 只跑测那条行为的用例；**反例**：拿摘要行/相邻行当锚点摘调用，探针绿了不留足迹就下结论。

**Seen 1× (2026-11-12)**

### L51 retry 救不了「一次性读完」的下载：先确认有没有续传语义，判据用服务端给的 digest

**规则**：把「重试」当万灵药之前，先看被重试的动作有没有**续传/幂等**语义。一次 `urlopen(...).read()` 拉 8MB 附件没有续传——链路每轮在同一处被掐时，重试只是把同一个失败从头再演一遍，每轮还白付前面已经下过的字节。正解：`Range` 从本地已落字节续拉 + 块级 flush 落盘；判据用**服务端声明的 digest**（GitHub Release API 的 `assets[].digest` 就是 `sha256:…`）而不是「长度相等」；连续 N 轮零进展即判链路不可用，并**把替代通道写进报错**（同一次 Actions 运行的 `plugin-<n>` 产物是同一份字节，走另一条 host）。同理，`gh run watch` 的退出码不能当发行结论——代理掐断会让它提前退出，判据永远取 `gh run view` 的 `conclusion`。

**证据**：2026-11-12 发 beta.4，`gitee-release.py --from-github` 的下载步三轮分别停在 0.83MB / 1.78MB / 1.97MB（`http.client.IncompleteRead(831805 bytes read, 7267971 more expected)`），每轮都从 0 重来；换可续传 `curl -C -` 后同一份文件累积到 7.37MB 再补齐，随后走**同一个脚本的本地 zip 分支**建版，`--verify-only` [3/3] sha256 与 GitHub 声明逐字节一致。同轮 `gh run watch` 被代理掐成 `failed to get run … EOF`，而 `gh run view` 显示 16/16 全绿仍在推进——差点把网络抖动读成流水线失败。脚本已按此改造（`_download_asset_resumable`：`.partial` 续传 + `assets[].digest` 校验 + 零进展 3 轮放弃并打印替代命令），落地探针＝把 8,099,776 字节的包截断到 4MB 再让它续传，sha256 与 GitHub 声明一致。

**正例**：续传 + digest 判据 + 报错里带替代通道；**反例**：把「重试 4 轮全失败」当成网络暂时抖动继续干等。

**Seen 1× (2026-11-12)**

### L52 整机重建时，运维脚本只放服务器＝没备份；口令只放服务器＝备份永久失效

**规则**：凡是要靠「服务器重装后照单重建」的东西，事实源必须在仓库里，服务器上的副本只是部署产物。两条具体要求：① 备份/部署脚本落 `dev-support/svbackup/`（仓库资产）+ `install-svbackup.sh` 幂等部署，改脚本先改仓库再重新部署，并用 `sha256sum` 核对仓库=服务器；② restic 仓库口令必须**离线双份**（服务器 `/etc/svbackup/pass` 之外，至少密码管理器或另一台设备各一份），AK/SK 丢了还能新建一对，仓库口令丢了没有任何恢复路径——对象还在，但 `--insecure-no-password snapshots` 只回 `wrong password or no key found`。用户放弃旧口令后另起新仓库时，**不要对同一 bucket 直接 `restic init`**（会重写 `config`/`key` 让旧数据彻底不可恢复），改用同一 bucket 的 `/v2` 路径前缀做独立仓库，旧对象原封不动。

**证据**：2026-11-12 重装后 `/usr/local/bin/svbackup-*.sh`、`/etc/svbackup/`、`/var/backups/svbackup/`、systemd 单元、restic 二进制全部蒸发，旧脚本无法逐字复刻只能按架构文档重写；本地全工作区与三台存活机（香港/百度云/华为云）都无 `pass` 副本，旧快照数据读不出。按用户授权用 `s3:.../songsrv-backup/v2` 新建仓库（repo `29b6eef669`），`init` + smoke 备份 + 完整 `svbackup.service` 主流程均 rc=0。详见 `docs/经验总结/20261112-2140-测试服务器整机重建与备份体系入仓-经验总结.md` §三/§四与 `.agents/skills/srv-backup-restore/SKILL.md`「四条铁律」。

**正例**：脚本入仓 + 部署副本 sha256 对齐 + 口令离线双份 + 新仓库用路径前缀隔离；**反例**：把 `credentials` 里的 AK/SK 当「凭据备份」——它不含仓库口令，AK/SK 有效也解不了密。

**Seen 1× (2026-11-12)**

### L53 多路重建先查 containerd content store，别把「docker images 空」当层没落盘

**规则**：Docker 29 默认 containerd image store（`driver-type: io.containerd.snapshotter.v1`），层落在 `/var/lib/containerd/io.containerd.content.v1.content/blobs/sha256/<64hex>`（文件名即 digest），**与 `data-root` 分离**。整机重建第一步就该枚举它并按 manifest 的 digest 精确匹配、硬链接补进工作目录 → `docker load`，能省掉几小时逐层下载。但「pull 失败也保留层」不是普遍成立：highgo 大层响应超时后 5 层全在（68→76 blob），gaussdb 公共域末段额外 blob 超时却触发 dockerd 回滚把 5 层清掉（82→76 blob）。判别口径是失败后**立刻** `du -sh` content store 并按 digest 查在不在，在就复用、不在就重试，而不是只看 `docker images`。

**证据**：2026-11-12 重建，B 组用 content store 硬链接把 vastbase 13 层逐层 sha256 校验通过、零 curl 下载即 `docker load` 成功（1.31GB）；主控独立枚举实证 68 blob/2.4G（含 926MB/429MB/374MB/259MB/181MB 大层），highgo 5 个大层全 HIT、gaussdb 5 层一度全 MISS。`docker pull` 在本机三源单路都卡在「层落盘→image 提交」，正是靠逐层 curl + `docker load` 或改走轩辕专属域才落地。详见 `docs/经验总结/20261112-2140-…` §四与 `.agents/skills/db-env-provisioning/SKILL.md` §1 第 5/6 条。

**正例**：先 `find …/blobs/sha256 -printf '%f %s\n'` 再决定下不下载；**反例**：拿 `data-root` 只有 236K 就断言「什么都没下过」，或把「pull 失败层一定还在」写进口径。

**Seen 1× (2026-11-12)**

### L54 生成 pull 命令前先读服务方 Agent 文档；占位符必须逐字照抄

**规则**：用户给出 `https://xuanyuan.cloud/agents.md` 这类服务方规范且要求「先阅读并遵守」时，**未读文档前不要生成 pull 命令或排错方案**；默认方案以文档为准（该文 §0.2 把 `registry-mirrors` 列为「禁止作为 Agent 默认」、§2.2 MUST 优先显式域名 pull、§3 MUST NOT 失败即换官方源）。另外本次环境会把消息里的 `{{PHONE_*}}` / `{{SECRET_*}}` 等占位符在显示层替换成随机串，**只能原样使用用户给的那个 id，不能凭记忆改写**——第一次 login 把 `18624714650` 写成另一个 `18210421986`，直接回「用户名或密码验证失败」；原样照抄后 `Login Succeeded`。

**证据**：2026-11-12 按文档 §1.2 查登录态、§1.3 一次性 login、§2.2 用 `docker.xuanyuan.run/...`（及用户补的专属域 `6rzy4mgbhtco8j6ztc.xuanyuan.run`）为默认方案，SQL Server 按 §2.1 走 `-mcr` 专属域；失败按 §3.3 重试轩辕路径而非换官方源。highgo/gaussdb/opengauss/Oracle/OceanBase/mssql 六个大镜像都在 2~5 分钟落地（MB/s 级），比逐层 curl 的几十 KB/s 快两个数量级。详见 `docs/经验总结/20261112-2140-…` §七与 `.agents/skills/db-env-provisioning/SKILL.md` §1 第 8 条。

**正例**：读文档 → 查登录态 → 显式域名 pull → 分类错误重试；**反例**：凭印象继续用 `registry-mirrors`/免费域，或把占位符 id 改写后反复试错。

**Seen 1× (2026-11-12)**

### L55 systemd 服务不继承 `docker run -e`：安装期参数必须注入镜像层可 source 的文件

**规则**：以 systemd 为 ENTRYPOINT 的镜像（磐维 PanWeiDB `init-db.service` 跑 `/root/run.sh`），**`docker run -e KEY=VAL` 的容器环境变量不会传进该服务进程**——PID1 `/proc/1/environ` 有，run.sh 进程 `/proc/44/environ` 没有，`systemctl show -p Environment` 为空。于是 ansible 收到 `-e dbcompatibility=` 空值，`gs_initdb --dbcompatibility=` 空值→引擎**默认落 A 档**，标称 B/MySQL 档的实例静默装成 A。修法无时序竞态：`docker create`（不 start）→ `docker cp` 往 `/root/.bashrc` 注入 `export dbcompatibility=B|A` → `docker start`（run.sh 第 2 行 `source` 它）。装完**必须验** `show sql_compatibility` / `pg_database.datcompatibility` 真的落对档，不能只看 `select 1` 通。

**证据**：2026-11-12 C 组首装 26100 装成 A 档，用 `systemctl show -p Environment` 空、`/proc/1/environ` 与 `/proc/44/environ` 对比、`gs_local` 日志第 511 行 `--dbcompatibility=` 三层证据钉死；注入后两实例实证 `gs_initdb --dbcompatibility=B|A`、`PW_COMPATIBILITY` 文件、四库 compat 全对档，A 档 `select dummy from dual`→X。同类「配置传不进服务」的变体：OceanBase `MODE=mini` 只自动建 `root@test`、不自动建 `xc3` 业务用户（首次 `xc3@test` 报 `ERROR 1045`，需 `root@test` 下 `CREATE USER 'xc3'` 且用户名不带 `@test`）。

**正例**：注入可 source 文件 + 装完验兼容档；**反例**：只改 `docker run -e` 并相信日志里的「安装成功」。

**Seen 1× (2026-11-12)**

### L56 三组并行重建：冲突由主控统一处置，判据落在磁盘字节与实连，不落在显示串

**规则**：多子代理并行部署时，主控把「恢复作业基准」写成服务器上的共享文件，凭据只在 `/etc/svbackup/db.env`（600），基准里只写变量名；遇到组间抢带宽/抢共享层/孤儿进程，由主控读 `ps`/`du` 证据后统一清孤儿、定错峰顺序，**不叫子代理自己 kill**。判据要落在「写进磁盘的字节」与「实连 SELECT 1 通不通」：本次环境的占位符替换把库名、容器名、变量名都显示成随机串，三组一度以为不一致，最后靠 A 组逐字节 hex（`78 63 33 5f 74 65 73 74` = `xc3_test`）钉死所有脚本写进磁盘的是同一个真值。另：别在任务运行中改脚本——在途进程会读到写到一半的中间态，日志炸 `line 127: syntax error near unexpected token ')'`（内嵌 Python 泄漏到 shell），而事后 `bash -n` 磁盘版本是过的。

**证据**：2026-11-12 三组（A 轻量 5 / B PG 族与 OceanBase 4 / C 磐维与 Oracle 3）后台并行，主控统一处置 4 路 `docker pull` 并发挂死、孤儿 curl（PPID=1 仍在下 29MB 白占带宽）、`--max-time 900` 掐断 76MB 冷门层、脚本运行中改写等冲突；最终 12/12 容器 Up、12/12 端口可达、`verify-restore.sh` PASS=18 FAIL=0。子代理只跑定向验证、不跑全量/构建校验，如实登记 not run。详见 `docs/经验总结/20261112-2140-…` §五与 `.agents/skills/db-env-provisioning/SKILL.md` §1 第 1/4 条。

**正例**：共享基准 + 主控统一处置 + hex/实连判据 + 改脚本前确认无在途读者；**反例**：拿终端显示的替换串当真实值改脚本，或让子代理各自 kill 抢通道。

**Seen 1× (2026-11-12)**

### L57 整机重建的收尾必须包含内存治理轮；cap 有「运行时 + 重建脚本」两处事实源

**规则**：按清单重建出来的容器只是「按台账旧 cap 起的欠压状态」——重建只压了部署脚本自带的那些参数，其余实例的内部参数全是镜像默认值。所以 §9 重建顺序的收尾固定一步是**内存治理轮**：① `docker update` 把 11+ 个 cap 全部收到 §2.5 目标值（在线、无重启）；② 逐实例核对内部参数（Oracle SGA/PGA、MSSQL `max server memory`、openGauss 族 `shared_buffers`/`max_connections`、MySQL/MariaDB buffer pool），欠压的按 §2.5 改法压掉。另外 **cap 有两处事实源**：运行时 cap 与幂等重建脚本里写死的 `--memory=`——只改前者，下次整机重放全部回退旧值；治理必须两处都改（`sed` 同步 start.sh/init.sh/a-deploy，参数化脚本在头注释记传参值），并同步台账 TSV 的内存列。

**证据**：2026-11-13 第二轮治理，采集实测四处欠压（Oracle SGA 1536M 默认、MSSQL `max server memory` 2147483647 无限、openGauss/Vastbase A/B `shared_buffers` 各 1024MB），cap 收缩 11 个（总 cap 40.7G→19.3G）+ 四处参数压缩后 `available` 3144→**4149MB**（+1005MB），13/13 数据面实连通；highgo 压 16m 反向验证 `OOMKilled=true Exit=137 Restarts=3`、恢复 512m 复活。重建脚本里抓到五处旧 cap（oceanbase start.sh `--memory=8g`、gaussdb init.sh 4g、vastbase A/B start.sh 4g/2g、a-deploy.sh 3g/2g）已全部同步。详见 `docs/经验总结/20261113-0057-测试服务器容器内存第二轮压缩-经验总结.md` 与 skill §2.5/§9。

**正例**：治理清单逐实例核（cap + 内部参数）+ 两处事实源同步 + 反向验证两次读数；**反例**：只 `docker update` 运行时 cap 就宣布「压完了」，重建脚本里旧 cap 原封不动。

**Seen 1× (2026-11-13)**

### L58 判容器重启循环用 `docker inspect` 确定性字段，别拿 `docker ps` 相对时间加时区心算

**规则**：`docker ps` 的 `Up 2 hours` 是相对当前时刻的模糊展示；跨 UTC/北京两个日期工作（会话发生在夜里、服务器日志打 UTC）时，拿「记忆里的创建时刻」跟它对比极易把时区差当成重启循环。判据永远是 `docker inspect -f '{{.RestartCount}} {{.State.OOMKilled}} {{.State.StartedAt}}'`——这三个字段直接回答「重启过几次、有没有 OOM、到底何时启动」。

**证据**：2026-11-13 内存治理中途看到部分容器 `Up 2 hours`/`Up About an hour` 而「记忆里是昨天起的」，差点判成隐藏重启循环；inspect 证实 `restarts=0 / OOMKilled=false`、StartedAt 与创建一致——实际是把 UTC 和北京日期搞混（部署发生在当天 UTC 下午，容器确实只跑了 1~2 小时）。

**正例**：`for c in $(docker ps -q); do docker inspect -f '{{.Name}} {{.RestartCount}} {{.State.OOMKilled}} {{.State.StartedAt}}' $c; done` 一次看清；**反例**：盯着 `docker ps` 的 Up 时长加心算时区下结论。

**Seen 1× (2026-11-13)**

### L59 备份采集器的每个引擎分支都要真机跑一遍采集并真恢复一次——纸上配方在 dump 工具旗标语义上必错

**规则**：多引擎备份采集脚本不能只按「同类语法拼命令」写完就算完——各引擎 dump 工具的旗标语义并不统一，**每个分支必须在真机跑一遍采集、并把产物真恢复一次**（skill §3.2 铁律「恢复才是验收标准」同样适用于验证采集器）。本轮五个分支全错或部分错：①MySQL 族拿 `mysqldump` 当枚举客户端（无 `-e` 选项）；②`gs_dump/gsql` 不认 PGPASSWORD 需显式 `-W'口令'`，而 `pg_dump` 的 `-W` 是无参 flag——连写口令会把字符逐个当短选项（`invalid option -- 'X'`），两类工具不能共用同一模板；③TiDB/OceanBase 容器内无客户端（借另一容器走网关 + `--column-statistics=0`）；④MariaDB 的 mysqldump 没有 `--skip-column-statistics`；⑤壳层面的三个：`timeout` 是外部命令调不动 shell 函数、「变量存命令再 eval」的双重引号重组会把 `sh -c` 参数切错位、函数版 dump 别丢 stdout 重定向。

**证据**：2026-11-13 恢复演练前置采集，五轮迭代（17:08→17:32）从 FAIL=6 到 FAIL=0、12 个 dump 覆盖 8 引擎；随后 9 引擎 restore 演练全通（MariaDB sandbox 首行、OB charset 45、Vastbase/openGauss 业务用户无建库权等 restore 侧坑实录见 `docs/经验总结/20261113-0105-七牛备份恢复演练全引擎打通-经验总结.md` 与 skill §3.2.1）。

**正例**：改一版 → 真机采一轮 → `restic ls` 盘点 → 灰度到全部引擎；**反例**：采集器写完只跑一次 systemd rc=0 就当全绿（rc=0 只代表脚本没崩，单库 FAIL 会被告警淹没）。

**Seen 1× (2026-11-13)**

### L60 外部连不上先按四层排，`refused` 与「握手期断连」不是一回事

**规则**：真机实例「外部连不上」不许直接归到网络。按四层从外往里排，错在哪层、报错就长什么样：①宿主机端口 `ss -tlnp`（无进程＝容器没起）②DNAT 目标 `nft list chain ip nat DOCKER` 与容器实际 IP 对照（指向已消失的 IP ⇒ **超时**）③容器内监听地址（只听 `127.0.0.1` ⇒ **refused**）④`pg_hba.conf` 放行来源（只放行容器网段 ⇒ 外部连接在**握手期被踢**，pgjdbc `The connection attempt failed.`、原生客户端 `os error 10054`，**不是 refused**）。判据口诀：refused 在 ①③、超时在 ②、握手期断连在 ④。配套两条纪律：改 hba 用 `gs_ctl reload` 不用 restart（restart 会打断在跑的真机矩阵，红了自己也分不清谁弄的）；「端口 OPEN」不算活的证据——`docker-proxy` 会先自己完成 TCP 握手，必须做到客户端能查（`SELECT 1`）。

**证据**：夜间 `dialect` 腿连红四轮（11-09 起，run `43125167048` / `43143657370` / `43154447017` / `43335074786` 系）读数恒为「通过 2 / 失败 2」，失败两实例同在 `103.36.220.245`（panwei_a:26101、gaussdb:25882），通过两实例同在 `114.66.55.245`。前两层都正常，第三层 gaussdb 只听 loopback（改 `listen_addresses='*'`），第四层三套 hba 全只放行容器网段——修完第三层后报错从 `Connection refused` 变成 `The connection attempt failed.`，我一度按网络排查；用 DBX 从本机直连复现 `10054` 才定性，补 `host all all 0.0.0.0/0 sha256` 后 gaussdb 539ms、panwei A 984ms 连通，随后 run `43337831586` 出 **`通过 4 / 跳过 0 / 超时 0 / 失败 0`**（mysql84 262 词 / pg16 86 词 / panwei_a 109 词 / gaussdb_kernel503 197 词），夜间 17/17 全绿。方法全文见 `docs/经验总结/20261113-0845-夜间dialect腿连红根因与真机实例对外可达四层排查法-经验总结.md` 与 skill §4.5。

**正例**：每层各取一条读数写进结论，改完用「外部视角」复验（DBX/公网客户端，而不是容器内 localhost）；**反例**：看到 `connection attempt failed` 就归因「跨洋网络抖动」，或拿容器内 `SELECT 1` 通当作外部可用。

**Seen 1× (2026-11-13)**

### L61 判「平台能否加载这份状态」不能拿 `Json.parseToJsonElement` 的成败当判据——它对裸值宽松

**规则**：要判「这块 JSON 平台能不能反序列化成功」，必须按**平台那个 serializable 的类型要求**去校验，而不是「能否解析成 `JsonElement`」。`Json.parseToJsonElement` 对**未加引号的值是宽松的**（`{"k": 8d6c2a1b}` 会解析成一个非字符串 `JsonPrimitive` 而不抛错），而平台 `BasePropertyService.MyState` 是 `Map<String,String>` 的**流式严格解码**，遇到裸值直接 `JsonDecodingException: Unexpected JSON token ... Expected quotation mark '"'`。两者判据不一致的后果不是「多修」而是**「永远不修」**：真机损坏形态被判成健康，自愈逻辑全程空转。通用化：任何「探测某份文本是否可用」的判据，都要对齐**真正消费它的那一方的解析器与类型**；能拿到对方同一入口就用同一入口，拿不到就按它的类型约束自己写形态校验。

**证据**：T-WorkspaceHeal 定向测试首轮 12 例红 3 例（`test缺尾引号`、`test两端都缺`、`test别人插件键坏`），诊断读数 `DIAG jsonAccepted=true err= | bothOutcome=Healthy`——宽松解析器确实放过了裸值。改成「`keyToString` 每个值必须是 JSON 字符串 + `keyToStringList` 必须是字符串数组」后 15 例全绿，并留回归用例 `test裸值必须判不可加载即使宽松解析器放过它` 把两者差异钉死。提交 `d87853744`。

**正例**：先写一条「证明宽松解析器会放过这个形态」的用例，再写「我的判据必须拒绝它」的用例，两条并排放；**反例**：用 `runCatching { Json.parseToJsonElement(x) }.isSuccess` 当「是否合法」的唯一判据（本项目真机损坏形态下它恒为 true）。

**Seen 1× (2026-11-13)**

### L62 compose 只认领带自己 label 的容器：在役机器上 `up` 必撞名；已有卷要 `external: true`

**规则**：把在役容器写成 compose 配方时，三件事必须在文件里显式表达，否则「配方看着对、一跑就废」：
① **`network_mode: bridge`**——不写的话 compose 会另建 `<project>_default` 网络、容器落到新网段，
凡是有「按已知网关 IP 直连」的旁路（本项目备份采集借另一容器走 `172.17.0.1`）或把容器 IP 写进配置的
（磐维 `listen_addresses`）都会静默失效；② **已在役的命名卷加 `external: true`**——只写 `name:` 会报
`volume "x" already exists but was not created by Docker Compose` 并试图创建同名卷，`external` 才是"别管它直接用"；
③ **compose 靠 `com.docker.compose.*` label 认领容器**，在役容器没有这些 label 而 `container_name` 唯一 ⇒
在役机器 `up` 必撞 `Conflict. The container name ... is already in use`。所以这类配方的定位只能是
「全新机器一键起」，在役机器只当应然状态参照。另：配方里写 canonical 镜像名时，经 mirror 拉下来的 tag 名
带前缀（`docker.xuanyuan.run/x`），不 `docker tag` 回 canonical，compose 会去官方拉并撞匿名限流。

**证据**：2026-11-13 落 `dev-support/db-env/docker-compose.yml`（13 服务，逐字段从 `docker inspect` 抄录）。
`docker compose config --quiet` RC=0、13 服务/13 端口/3 heavy/3 external 全对；`--dry-run up -d` 依次暴露三件事：
先因 `pingcap/tidb:v6.5.6` 无本地 tag 去 Docker Hub 拉 → `toomanyrequests: unauthenticated pull rate limit`；
补 tag 后报三条卷 `already exists but was not created by Docker Compose`；再加 `--profile` 看全 13 服务时
确认它把在役容器全判成 `Creating`（即不认领），且会另建 `db-env_default` 网络。修 `external` + `network_mode`
后复验：卷警告 0、网络不再创建、13 服务全解析。详见 skill §10 与 `dev-support/db-env/README.md`。

**正例**：`config --quiet` 过语法门禁 → `--dry-run up` 看计划与警告 → 逐个消警告并确认不建多余资源；
**反例**：只 `config --quiet` 说"配方 OK"（它不校验卷归属、网络模式、image tag 是否本地存在）。

**Seen 1× (2026-11-13)**

### L63 后台阻塞读锁与 EDT 写意图升级是死锁对：两侧栈各自都「像在正常干活」

**规则**：看到「IDE 打开即卡死 / 窗口白屏 / 冻结报告每 5 秒一份」时，先按**锁对**查，不要先当性能问题查。判据是两份栈合起来读：
- 后台侧出现 `ReadAction.compute → computeBlocking → ReferencesSearch / PsiSearchHelper`（阻塞读动作，**不可被写动作取消**，索引期还会同步等 smart mode）；
- EDT 侧出现 `runIntendedWriteActionOnCurrentThread → WriteAction.run → runWriteActionBlocking → prepareWriteFromWriteIntentBlocking → upgradeWritePermit`（写意图读锁在有读者时无法升级）。
两者互为对方的等待来源，索引推进又要写权限 ⇒ 闭环。三条纪律：①索引检索不得在阻塞读动作里跑，用 `ReadAction.nonBlocking{}.inSmartMode(project).expireWith().coalesceBy()`；②EDT 不得在写意图上下文里就地升级写锁，写体走投递 + 有界等待；③「BGT 等 EDT、EDT 永不反向阻塞」是对偶，反方向也要写成纪律（本次事故就是反方向没写）。另外：改了投递形态不等于解冻——平台对 nonModal 投递拍同样套 `runWithWritingAllowed`，只有把「对立面的长读者」拆掉才真正解除。

**证据**：2026-11-13 用户 Linux 冻结（`threadDumps-freeze-20261113-082447`，8 份 dump 每 5 秒一份、持续 >40 s；工程 >1000 表 / 上万实体）。栈 1 绿底帧 `MbgExampleCallResolver.prefetch → resolve → ReadAction.compute(:95) → computeBlocking(ReadAction.java:88) → findCallSite → ReferencesSearch`；栈 2 `AWT-EventQueue-0` `TransactionGuardImpl.runWithWritingAllowed → dispatchDocWriteOnEdt(:2588) → DocWriteContext.runWriteSafe(:72) → docWriteOnce(DataSourceSelector.kt:2085) → WriteAction.run → prepareWriteFromWriteIntentBlocking → upgradeWritePermit`。版本相关性：`2026.11.11` 的 gutter provider 无 prefetch（`git show` 实证为空），`2026.11.12` 新增；同一 M2 检索预览链有 dumb 门、预取链没有（`XmlPreviewService.kt:121` vs `MbgExampleCallResolver.prefetch`）。修后定向 133/0/0/0（17 类，含 4 个 plugin.xml 守卫）；反向验证：摘 dumb 门 6 例 1 红、改回就地写 4 例 1 红。详见 `docs/开发计划/20261113-0839-T-StartupReadLockFreeze-…-开发计划.md` §二/§三/§十三。

**正例**：两份栈按「谁在等谁」对齐成一条环，再用版本 tag 对照找驱动点；**反例**：只看 EDT 栈判「插件在 EDT 干了重活」，或只看后台栈判「检索太慢」，各自都能解释现象却修不到点（改投递形态的实验即证伪：冻结点不变）。

**Seen 1× (2026-11-13)**

### L64 反查/索引类缓存别挂 `PsiModificationTracker`——插件自己的 PSI 写入会把它顶掉，「每次打开都重扫」

**规则**：给「全工程扫描 → 结果缓存」选失效基准时，先问一句**这条链路自己会不会改 PSI**。预览/执行/补全链会写执行文件、建临时 PsiFile、在对话框里改文档——这些都递增 `PsiModificationTracker.modificationCount`，于是「版本相等」的判据在**每次用户操作之后**都失配，缓存退化成「每次重扫」。正确基准＝**项目结构版本**（`ProjectRootModificationTracker`：文件增删/依赖/模块根变化才涨，改已有文件正文不涨）+ 一个 TTL 兜住「改了注解/常量这类正文修改」。仓内已有同款实证（`LibrarySweepCache` KDoc：改一个文件 4→16，故不用 PSI 版本），本次是第二次踩——**新写缓存照 `LibrarySweepCache` / `ReverseLookupCache` 抄基准，别默认挂 PSI 版本**。

**证据**：2026-11-13 真机（`cust-big-demo`，16804 类）同一份日志里热冷交替：`18:52:59 resolve=2ms`（缓存热）→ 我们自己写 exec 文件 → `19:00:08 resolve=3994ms`、`19:00:44 resolve=3737ms`；`request→dialog` 4190/3931ms，而同时段另两次是 94/319ms。修法（`c96759946`）：一次扫描建「表名 → 类」全表索引（不再「查一个表名扫一遍」）+ 结构版本 & TTL(5min) 失效 + 索引建立中后来者不等待。定向 `EntityClassFinderHeavyTest` 18/0/0/0 + `EntityClassFinderIntegrationTest` 21/0/0/0；反向（换回 PSI 版本基准）两条守卫各恰 1 红。

**正例**：先把「谁在改 PSI」列清再选基准，并用真机同 session 冷热交替读数验证（同一份日志里 2ms 与 3994ms 并存＝缓存被判失效，而非扫描变慢）；**反例**：拿「缓存命中」日志行当命中证据——`entity cache hit` 与 `resolve=3994ms` 可以同时出现（两段是不同缓存），只看那一行会判成「缓存没问题」。

**Seen 1× (2026-11-13)**

### L65 平台注解索引的键是**注解简单名**、且**属性值不进索引**——按 FQN 查恒空，按值反查类没有直查通道

**规则**：用 `JavaAnnotationIndex.getAnnotations(?)` 取「带某注解的元素」时，键必须传**简单名**（`TableName`），传全限定名一条都查不到；索引里也没有属性名/属性值（243 `JavaAnnotationElementType.indexStub` 的键＝`getReferenceShortName(text)`，字节码先 `indexOf('(')` 截断再取短类名）。所以「注解属性值 → 类」的一步 O(log n) 直查在平台层不存在，只能「按注解名取候选（索引）→ 逐候选读注解值过滤（PSI）」；要不要换，先把候选量（带注解的类数）与全项目类数比一比。传 FQN ⇒ 该分支**恒空**，而它通常写在「类解析不到才走」的兜底里，正好是最需要它的场景，错得最隐蔽。

**证据**：2026-11-13 用户令「调研能不能复用 idea 自己构建的索引」；后台代理反编译 `plugins/java/lib/java-impl.jar` 取证，本人复核 `JavaAnnotationElementType.indexStub` 字节码（`indexOf(40)`＝`(`，随后取短类名）与 `JavaAnnotationIndex extends StringStubIndexExtension<PsiAnnotation>`。本轮顺带修掉仓内真缺陷：`XmlSqlCompletionContextResolver.findTableNameEntities` 兜底分支把 FQN 传给了该索引（恒空），改传简单名 + 按注解原文筛同名（不 resolve），守卫 `XmlSqlCompletionContextResolverIndexKeyTest` 钉住「只传简单名」。全 IDE 132 个索引注册里 4 个与注解相关，无一按属性值建键。

**正例**：先看**写入侧**的建键逻辑（`indexStub`）再写查询侧，并在守卫里钉住「键长什么样」；**反例**：照 API 名与参数名猜语义（`getAnnotations(String, …)` 看着像收 FQN），或拿「查询失败返回空」当正常降级——恒空与真无匹配在读数上完全一样。

**Seen 1× (2026-11-13)**

### L66 组件释放只挂 `removeNotify` 不保险——IDE 直接退出时它不必然到达，平台按「内存泄漏」记账

**规则**：编辑器、监听器、`Disposer.newDisposable()`（无父件＝挂在 ROOT）这类资源的释放，**不能只挂在 `removeNotify → release` 一条路径上**。宿主 dispose（面板/DialogWrapper 关闭、IDE 退出）是另一条独立路径，两条都要能收口，且收口动作必须幂等（先到者做完、后到者零动作）。判据：日志里出现 `#c.i.o.u.ObjectTree - Memory leak detected: '<名字>' … but wasn't disposed` + `Plugin to blame: <你的插件>`，名字就是你的 disposable 名或平台给你的对象造的子件（编辑器会连带报 `CaretModelImpl` / `EditorView`）。**新写「面板里造编辑器/挂监听」的代码时，就地把释放挂到面板的 `dispose()` 上**；`Disposer.newDisposable(name)` 一律补 `Disposer.register(宿主, it)` 或显式 dispose。

**证据**：2026-11-13 沙盒退出，4 条 SEVERE（`SqlPreviewPanel.autoPopup`、`Disposer$CheckedDisposableImpl`、`com.intellij.openapi.editor.impl.CaretModelImpl`、`…view.EditorView`，同一秒四连）。根因：预览编辑器与 autopopup listener 只由 `disposeCurrentEditorOnly()` 释放，而它挂在 `removeNotify` 上；IDE 退出走的是 DialogWrapper dispose 链。修法 `a02ad51cf`：`SqlPreviewPanel.dispose()` 兜底调 `disposeCurrentEditorOnly()` + 显式 dispose `autoPopupDisposable`。守卫 `testDisposeReleasesPreviewEditorAndAutoPopupListener`（装配编辑器 → dispose → 断言 editor 与 autoPopupDisposable 皆空）；反向删掉兜底两行 → 恰 1 红。同族审计：全仓 `Disposer.newDisposable(` 仅两处，另一处（`ExecDocAutosaveHost`）已 `Disposer.register(this, parent)`。

**正例**：把「谁负责释放」当成两条路径（组件移除 / 宿主销毁）分别写清并各留一条断言；**反例**：拿「手工关窗口后没报泄漏」当验证——`removeNotify` 那条路本来就走得到，漏的正是退出这条。

**Seen 1× (2026-11-13)**

### L67 「TTL 内一律命中」是伪缓存：内容判据被时间判据盖住 ⇒ 改了不生效、且跨场景串味

**规则**：缓存的命中条件里一旦出现 `TTL 内直接命中 **||** 内容未变`，实际语义就是「TTL 内无视内容」——它同时吃掉两件事：①**改了不生效**（用户改了注解/字段/配置，窗口内仍按旧值）；②**同键不同内容串味**（同一个 `qualifiedName`/路径在不同场景下内容不同时，后一个拿到前一个的结果；单测里同一类名换 `@TableName` 即复现）。要「吸收 PSI 重建造成的伪失效」，正确做法是换一个**内容型指纹**（如 `clazz.text.hashCode()`、文件文本哈希、内容长度 + 关键字段文本），而不是把内容判据短路成时间判据。选指纹的标准：PSI 重建后不变（不误 miss）、真改了必变（不误 hit）；代价必须远低于被缓存的那次计算（本例文本哈希微秒级 vs 一次 `EntityResolver.resolve` 毫秒~秒级）。

**证据**：CI `test(4)` 三连红（`XmlSqlCompletionContributorTest` 的 `test04AliasPrefixedColumnCompletion` / `test11CommaMultiAliasBothBindMainEntity` / `test13OnClauseRightSideAliasColumn`）：列表里只剩关键字，别名列整片消失。根因＝`XmlSqlCompletionContextResolver` 的实体缓存写成 `TTL 内直接命中 || stamp 未变`，三个用例用 `@TableName("t")`、默认夹具用 `t_device_info` ⇒ 第二个场景拿到第一个的解析结果 ⇒ 主表名不对 ⇒ `FROM t u` 的别名绑不上候选 ⇒ 无别名列。修法 `6cf7366f2`：判据改 `clazz.text.hashCode()`。反向验证：把命中条件改回「无视内容一律命中」→ 上述三例恰 3 红，还原复绿。这是同一处的第三次订正（stamp → TTL 优先 → 内容指纹），前两次的错误在源码 KDoc 里留痕防退。

**正例**：写完缓存先自问「改了内容会命中吗」，并在同类名/同键换内容的形态上造一个用例；**反例**：拿「同一实体连续两次调用只解析一次」当缓存正确性证据——那条用例在无视内容的缓存下同样绿（本次三红来自**换内容**的场景）。

**Seen 1× (2026-11-13)**

### L68 按注解认 ORM 时要「按 ORM 分源」，且 `PsiAnnotation.qualifiedName` 解析不出时返回**短名**（不是 null）

**规则**：①反查/索引的候选源一旦从「扫所有类」换成「按注解索引取候选」，**必须显式列清覆盖哪些 ORM 的注解**（MP `@TableName`、JPA `javax/jakarta @Table(name)`…），并保留一条「注解都没命中 → 全项目推断」的回补链——MBG/iBatis/Kotlin/自定义映射根本没有表名注解，只靠注解索引会把它们整片漏掉，而这种漏**在测试夹具里常常看不见**（索引对 fixture 不给结果，反倒走了推断那半边）。②判「这个注解是不是我要的那个」时，`PsiAnnotation.getQualifiedName()` **在注解类解析不到时不会返回 null，而是把引用文本原样返回**（`@TableName` → `"TableName"`，不带包）；直接拿它和 FQN 比较，会把所有「工程没接该依赖」的场景判成「别家注解」——而「解析不到」正是这段代码要救的场景。判据应取「**带包**的 FQN 才参与比较」，短名一律按未解析处理。

**证据**：2026-11-13 用户提醒「记得兼容不同 orm」。上一轮把反查候选源收窄到 `@TableName` 后，JPA（`@Table(name=…)`）工程会整片漏掉。实施「按 ORM 分源 + 未命中回补」时，自测一下子 **11 个用例同时归零**（Heavy/Integration 两类的反查用例全返回空）；探针打印出 `ref=TableName qn=TableName`（而不是 FQN 或 null）才定位到判定写错。修法 `TableNameAnnotations`（分源表 + `resolvedFqnOrNull` 按是否带包过滤）；随后 `TableNameAnnotationsTest` 7 例 + Heavy 2 例（JPA 命中、回补至多一次）全绿；反向摘掉 JPA 源 → 5 例红。

**正例**：「按注解取候选」这类收窄，配一条「都没命中时退回全量」的回补链，并在注释里写清覆盖清单；**反例**：拿「本工程（MP）用例全绿」证明跨 ORM 没问题——夹具里注解索引本来就可能不工作，绿是推断那半边给的，与注解源对不对无关。

**Seen 1× (2026-11-13)**

## 升级记录

| 日期 | 条目 | 去向 |
|---|---|---|
| 2026-10-23 | S1~S7（建账时把本仓已多日复发的既有纪律直接按机制升级入册） | `规范/编码与验收标准-规范.md` |
| 2026-10-24 | L3 改 State/守卫类必须跑 testFast 整桶（Seen 3×；当日证据 4bccd331 漏登→06fe3953 补登揭潜伏红灯） | 并入 `规范/编码与验收标准-规范.md` S3（建账时正文已在），账本活跃区改指针 |
| 2026-11-09 | L25 changeNotes 新块机器判据（评分句只留最新块 3×、本版块禁全角标点 1×） | 评分句半边升级 `规范/编码与验收标准-规范.md` **S11**；全角标点半边留活跃区继续累计 |
