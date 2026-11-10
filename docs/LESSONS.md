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

### L22 后台长命令禁用 `2>&1 | tail -N` 收尾——失败正文会被管道截断吞掉

**证据**：`./gradlew buildPlugin 2>&1 | tail -15` 后台跑，失败后日志文件只有 gradle 警告尾 + `BUILD FAILED in 9s`，`What went wrong` 段整段丢失；重跑不带 tail 全量落盘才看到真因（`prepareSandbox` 对沙盒内 jsqlparser jar 的 mmap 锁，`请求的操作无法在使用用户映射区域打开的文件上执行`）。tail 先于异常处理执行，等于把排障证据裁掉只留结论行。
**正例**：后台长命令重定向全量到文件（`> /tmp/build.log 2>&1`），判读用 grep 提取；必须 tail 时对失败路径重跑一次不截断的采集。`reverse_probe.py` 据此固定为全量捕获 + 失败时打错误行子集。
**反例**：`| tail -N` 当作「日志瘦身」常态化——tail 的 N 行里恰好没有失败原因时，二次重跑 whole job 的成本远大于第一次就落全量。
**Seen 1× (2026-11-07)**

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

### L28 织入型 enter/exit 配对不能只挂「方法体里显式出现的返回点」——被调方异常逃逸时没有出口点

**规则**：给方法体插 enter/exit 钩子做配对计数（嵌套门、计时、事务边界都属这类）时，只在 `IRETURN/LRETURN/ARETURN/ATHROW` 前插 exit 是不够的：**异常从被调方法抛出、而本方法体没有 try/catch/finally 时，字节层只有一条正常返回指令、一个 `athrow` 都没有**，出口钩子无处可插 → enter 计入、exit 永不执行，深度/计数永久偏 1。判据必须是「异常穿过本方法时也走出口」——把方法体包一层 catch-all handler（`TryCatchBlockNode` → 调同一出口 → `athrow`），并用「夹具方法自身无 try/catch、委托调用抛异常，抛完后同线程下一帧仍产出」的用例钉住；反向验证摘掉 handler 必须变红。
**证据**：T-NestGateObserve 离线定案（计划 §11.8.5，2026-11-10）：`StatementWeaver.weaveMethod` 的 `isTimedExit` 只认显式返回点；磐维 A 模式 `org.panweidb.jdbc.PgStatement.execute(String)Z` 原始字节只有 `invokevirtual executeWithFlags` + `ireturn`（无 handler、无 athrow），失败 DROP 的 `SQLException` 逃逸时出口不存在 → 该线程 depth 永久 +1，后续整类语句被当成外层抑制，而 `nestSuppressed` 为 0（被抑制的是最外层自己）。织入产物逐方法计数：enter=1、exit=显式返回数（`execute` 1/1、`executeQuery(String)` 1/2），排除「双重入口」；真机对照：两次失败 DROP 泄漏（`nestStale=2/nestMaxDepth=3`），两次成功 SELECT 不泄漏（`nestStale=0/nestMaxDepth=1`）。
**正例**：先做「织后字节 enter/exit/返回点逐方法计数」再下结论，并用成功/失败两条载荷做对照；**反例**：拿「exit 数 == 显式返回指令数」当健康判据收工——它天然看不见隐式异常出口（本轮首版就是这么把「成功路径也泄漏」写错的）。
**Seen 1× (2026-11-10)**

## 升级记录

| 日期 | 条目 | 去向 |
|---|---|---|
| 2026-10-23 | S1~S7（建账时把本仓已多日复发的既有纪律直接按机制升级入册） | `规范/编码与验收标准-规范.md` |
| 2026-10-24 | L3 改 State/守卫类必须跑 testFast 整桶（Seen 3×；当日证据 4bccd331 漏登→06fe3953 补登揭潜伏红灯） | 并入 `规范/编码与验收标准-规范.md` S3（建账时正文已在），账本活跃区改指针 |
| 2026-11-09 | L25 changeNotes 新块机器判据（评分句只留最新块 3×、本版块禁全角标点 1×） | 评分句半边升级 `规范/编码与验收标准-规范.md` **S11**；全角标点半边留活跃区继续累计 |
