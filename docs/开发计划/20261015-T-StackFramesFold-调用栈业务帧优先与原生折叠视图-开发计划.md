# T-StackFramesFold：调用栈业务帧优先与原生折叠视图开发计划

- 日期：2026-10-15；分析基线：`master@6e378346`。
- 状态：当前 master 工作树已实施，自动化门禁通过；沙盒真机交互由用户验证。未提交、合并、推送或发布。本计划不预占 O/R/D/V 编号，后续纳入台账时再对账。
- 关联：`20261007-0829-T-StackHoverUi-预览面板调用栈悬停UI调试器风格化-开发计划.md`（现有弹层与多轮“Controller 看不到”修复）。本计划是它的后续体验改造，不把已有措施当作未实现功能重复立项。

## 一、用户问题与目标

截图中的 SQL 预览悬停弹层从 `StatementHandler.emitEvent` 开始逐行列出 agent、JDK 反射、连接池、MyBatis、Tomcat/过滤器等帧；用户寻找 Controller 却看不到，长列表也不便查找。用户希望沿用 IDEA 调试器的堆栈观感，默认把内部库调用收成可展开的组，业务调用一眼可见。

目标分别是：**采集得到业务帧**、**展示时优先看见业务帧**、**需要时能展开每一段内部调用并跳转源码**。折叠只能改变可见行，不能把采集时已丢失的 Controller 凭空补出来。Controller/Service/Mapper 都可能是业务帧，不靠类名后缀猜测，更不能为了置顶而伪造栈顺序。

## 二、现状与证据边界

1. 旧计划 §七记录了演进：初版只取栈顶 16 帧；后改 `stackMaxFrames` 默认 24、范围 12～64，超预算时保留栈顶和栈底；再对 Tomcat/线程池及 Spring web/boot 尾巴做穿透，尝试锚定最深业务帧。说明“加大默认数”与“固定保留栈尾”都曾在真实请求上仍看不到 Controller。设置页已有“调用栈快照帧数”，不要再新增一套同义配置。
2. agent 在 JDBC 执行点采集一次 JVM 栈，同一份栈分别用于主位置和摘要（`StatementHandler.java:132-150`）；`StackDigest.java:48-116` 按默认 24 帧预算在深栈保留顶 16＋尾 8 并尝试穿透尾部基础设施。`SqlPreviewPanelText.kt:126-135,191-203` 的 meta 行只取栈顶前 120 字符，窄窗还能隐藏摘要；弹层解析器 `StackDigestFrames.kt:39-56` 会跳过无 `#` 的中间省略标记。现有数据是 agent 采样后的 `stackDigest` 静态快照（`sd`），不是调试器连接 JVM 得到的实时栈；旧历史、非 agent 通道或旧版已附着 agent 可能无值或旧格式。调试器会话能力（暂停、变量、继续执行）不属于本需求。
3. 本次截图能证明当前弹层显示的多行中没有 Controller，**不能仅凭屏幕裁剪证明 Controller 不在原始 `sd`**：先使用“复制调用栈全文”检查原文中有无业务帧、省略标记和截断；同时对齐实际安装包中 agent 的版本、设置的帧数与是否重附。无原文和现场版本，不把“采集截断”或“纯 UI 排序”定为此次唯一根因。
4. 旧计划查过 IDEA 243 sources：`JavaStackFrame` / `XFramesView` 依赖活动 XDebugSession 和 JDI 栈帧，不能直接把离线 `sd` 塞成 debugger Frames。243/253 本地平台 jar 确有 `XFramesView`、`XDebuggerTree` 和 `JavaStackFrame`，但按 `StackTraceTree` / `StackTracePanel` / `StackTraceFilter` 命名未找到独立静态堆栈树组件；这不是对平台所有类的穷尽证明。仓内 `StackFrameHover.kt:125-194,261-286` 已用 `JBList`＋`ColoredListCellRenderer`＋popup，`ExplainPlanResultView.kt:125-137` 有 `DefaultTreeModel`＋`JTree` 的静态树先例。优先在现有弹层内复用平台树控件和渲染能力、自建静态帧分组模型，不移植 debugger 会话视图；具体树 API 签名及 262 可用性须以本地制品再核。

## 三、推荐方案

### 3.1 分层：完整快照、分组投影、逐层展开

- 数据模型保留原始顺序、行号、显式省略/截断段和原文（来源通道仅在确有可靠数据时携带）；先修正解析器当前跳过中间省略标记的行为，省略段应单列为“采集时省略 · 帧数待核/已知 N”，**不可伪装成可展开的已采集帧**。展示投影只处理**已采集帧**，相邻的内部帧折成“内部调用 · N 帧”一行。栈顶仍在上，业务帧保持原位；多段库调用分别折叠，不把不相邻帧揉成一段。展开/收起组、全部展开/折叠、复制完整原文；折叠时不丢跳转信息。
- 默认展开项目业务帧，内部库/插件 agent 帧默认折叠；业务帧即使夹在两段框架调用之间也始终显示。至少显示业务帧的方法、类、文件/行号；无行号时不展示假的可跳转状态。无业务帧时提供明确状态：“快照中未采到项目代码帧”，附“展开全部/复制原文”，而不是展示一个空树。
- “内部”的判断以该条目的项目上下文为基准：优先辨认同工程源码/模块，`cn.song.mybatisplus.sqlvis.agent.*` 明确标成插件内部；JDK/常见框架与依赖库再分类。项目自身可能位于 `org.springframework.*` 风格命名空间，业务代码也可能来自同工程 jar 或生成代理类；分类不确定则**宁可展示，不可误折叠**。仅靠固定 `spring.*` / `com.*` 前缀作为唯一依据不合格。分类耗时的 PSI/VFS 查询不可在悬停 EDT 逐帧全局扫描，需可缓存、可取消或先用已有轻量索引，再在后台补充；具体 API 待验证。
- 视觉以 IDEA Frames 的树行、缩进、展开箭头、选中高亮、平台主题色、键盘上下/左右/Enter 操作为参照。优先复用公开、跨版本稳定的 IDE 树/ColoredText 与 JBPopup 基建，不复制 debugger 的会话模型；在现有 `StackFrameHover` 中升级内容区，维持悬停打开与移出/ESC 关闭，别并排再开第二个调用栈入口。业务帧点击/回车沿用现有源码跳转；展开动作不能误跳转。弹层应在窄窗限制宽高、支持滚动与长类名省略，鼠标能从触发标签移入弹层而不闪退。

### 3.2 先保证采得到，再讨论调大默认值

- 取真实请求的 `sd` 与源 JVM 栈作对照，标出 Controller 所在深度、总深度、预算、序列化字符上限、尾巴穿透与现存省略段。若 Controller 已在 `sd`，只动展示/分类；若不在，优先修采样锚点或长度预算而非一味把 24 改成 64。对嵌套 Controller→Service→DAO、Controller→Service 异步切线程、Spring MVC/过滤器/AOP、非 Web 入口分别取样；跨线程执行 SQL 时 Controller 本来不在当前线程栈，不承诺原生栈一定能还原原请求 Controller。
- 若需调整 agent：先保留完整栈内可识别的项目帧及其邻接上下文，再对大量内部帧做有界采样，显示明确省略数量；严格限制事件大小与热路径分配，保留敏感数据约束。旧 `sd` 的解析与历史重放要兼容；需要协议字段扩展时先做兼容矩阵，再同步 agent/IDE 两端。新版 agent 逻辑涉及已附着 JVM 时，验收要明确重附或重启应用，不能仅看插件更新后热设置生效。
- 默认帧数是否上调由真实样本与性能测量决定，不能在计划阶段拍一个数；如旧设置已覆盖该问题，保持单一设置项，不新增“业务帧数”之类平行控制。

## 四、实施顺序与待验证前提

1. **现场判因与平台 API 验证**：复制截图同类条目的完整 `sd`，标注实际业务帧、截断/省略、agent 版本与设置值；读 agent `StackDigest`、解析器、`StackFrameHover`、`SqlPreviewPanel` 接线和测试。核对 243/253/262 的可用树控件、renderer、弹层尺寸与生命周期签名，验证它们不要求 XDebugSession。无真实原文时先做可复现实验，报告“未能证明该现场 Controller 在原栈中”。
2. **测试先行：保真分组**：含 agent/JDK/连接池/ORM/业务 Controller/Service/业务包名碰撞/生成代理/未知帧/中间省略标记/无行号/空快照的样例。断言业务帧默认可见、内部段可展开且顺序及总数不变、复制仍是原文；临时去掉分组或误折业务帧须红。旧 `StackDigestFramesTest`、`StackDigestTest` 与源跳转用例随行为变化更新，不把陈旧测试删掉换空绿。
3. **展示层落地**：共享纯分类/分组投影与 UI 树；在现有弹层接线，保持原有复制与跳转。真实鼠标/键盘验证“点组只展开、点帧才跳转”、焦点与 popup 生命周期；按状态局部更新，避免频繁重建、EDT PSI 扫描和未释放的组件。
4. **采集缺口条件修复**：仅当第 1 步证明 `sd` 已缺业务帧时修改 agent 抽样及必要协议，给出各预算和长度约束下“能保住哪些帧”的明确规则；构造真实长链对照测试，摘掉修复后 Controller 用例必红。单测包括全内部栈、异步入口、截断与旧配置/旧载荷。若证据不支持，整步跳过并说明。
5. **验证与收口**：实施者跑相关 UI/解析定向类；动 agent 则另跑 `:sql-visualizer-agent:test` 与该链定向类。所有代码完成后单路只读收口跑 `testShards --no-configuration-cache`；新增平台 API/协议或发版前按大改动线做跨 243/253/262 构建兼容验证。运行 Gradle 前遵守并行登记与资源闸门。真机至少验证深层 Controller、普通 Service、异步无 Controller、无栈旧历史、亮/暗主题、窄窗口、长栈、多次悬停、组展开/收起、复制与源码跳转；新版 agent 场景分别测“未重附”与“已重附”。

**待验证前提**：①截图条目的完整 `sd` 是否含 Controller，裁剪界面不能替代原始快照；②项目帧的可靠识别依据及 PSI 查询成本；③所选平台树/renderer 在最低 243 与 253/262 的真实 API 和 UI 一致性；④不同捕获通道是否都带有该快照；⑤热下发仅改变预算，不能替换旧 agent 的采样实现；⑥异步场景是否存在可关联的入口上下文——无关联数据则不承诺跨线程还原。

## 五、实施前证据补充（2026-10-15）

- `dev-support/e2e-fake-ide/evidence/p1-stack-evidence.txt:8-110` 保存了同一 demo JVM 查询期间的完整 Web 长栈：Service/Controller 位于第 59～60 行，后接 MVC/Servlet/Tomcat 长尾；这是连接池 `getConnection` 时刻的 jstack，**不是 SQL execute 同一瞬间**，仅作近真实结构夹具。
- 同目录 `probe.log:69-81` 的 agent `sd` 带省略帧标记，但 `FakeIde.java:254,547` 只记录前 900 字符，尾部已截，不能据此证明当前截图中的 Controller 被 agent 丢弃。旧计划 §七的真实用户两轮快照记录也属于历史版本，不冒充本次原始载荷。
- 可判别回归：默认预算 24，构造 45 帧，索引 20 为 Controller，其后含 Spring MVC/Tomcat，索引 39 加一个当前尾部白名单未覆盖的 `org.springframework.security.web.FilterChainProxy`。现有 `StackDigest.java:67-95` 把 39 错当最深业务锚点，仅留顶 0～15 和底 32～39，Controller 必丢。若修此例，定位是**已证明的算法健壮性缺口**，不声称已锁定这次截图现场根因。`StackDigest.java:89-95` 底段本身过长时无法守住字符上限，也需边界测试。

## 六、验收边界

- 深层同步 Web 请求：在**原快照确实包含或本轮采集可保留** Controller 的情况下，打开弹层不用展开库帧即可看到 Controller 与其他项目帧，展开后完整顺序、行号、跳转和复制保真；没有业务帧时如实提示，不伪造。
- 功能范围仅为调用栈采集/展示及其测试。SQL 内容、参数、执行计划、日志排序、历史存储格式不因 UI 分组改变；不得把一份静态调用栈冒充实时调试会话。
## 七、实施记录与待验项（2026-10-15）

- 在当前 master 工作树落地静态栈投影：`StackDigestFrames` 解析保留“中间省略 N 帧”及旧尾截断缺口；已采集的相邻 agent/JDK/框架调用合并为可展开组，未知与项目业务帧保持可见。`StackFrameHover` 由平铺列表改为树，保留源码跳转和原文复制，提供全部展开/折叠与双语提示。复核发现首版树化丢失方法/类名分色并在悬停时抢焦点，已改用经 243 `javap` 核验的 `ColoredTreeCellRenderer`、恢复悬停不主动请求焦点；截图中的 Tomcat、Spring Web、Sa-Token 过滤器组补入明确白名单，项目自定义 `org.springframework.web.myapp.*` 留在可见侧。
- agent 侧复现了旧两端采样在未知尾框架帧前把中段 Controller 丢掉的反例。`StackDigest` 在同应用包根的调用帧与 Controller 均可确认时优先锚定 Controller，不再依赖 `Service` 后缀；穿透已知 Spring Security Web 尾帧。框架命名空间的项目类需有目录来源证据，或用户已登记该业务 JAR 包名前缀；未登记、来源不可辨的 fat JAR 类仍不能保证被识别。单帧极长时收紧字符串预算。未知链保留原策略，不宣称恢复这次截图中未经取得的原始 JVM 栈。
- agent 首轮反向验证为旧实现 17 例中恰 4 红、修后 17/0；agent 模块 `:sql-visualizer-agent:test` 曾成功。独立复核又发现非 `Service` 命名的业务调用者和框架命名空间中的项目类可被遗漏，于是补判别性回归；其中非 `Service` 的反例在旧算法实测 18 例恰 1 红（`StackDigestTwoEndedTest.java:251`）。第二轮不再要求调用者以 Service 结尾，框架包下项目类只有经目录来源确认才作为业务锚点；UI 按明确类名而非宽泛 Spring 子包折叠，修复移动事件未接线、行号整数溢出与摘要长度漏算分隔符。修后根项目两类定向测试及其依赖的 agent 模块测试均绿；权威全量最终 `testShards --no-configuration-cache` 7164/0/0/9（五片、654 类 parity XML 全在场，14m28s）；Docker daemon 不可用，9 个跳过按离线范围理解。此前因复核 blocker 主动停止的两次半轮结果不采信。跨版本 `verifyGate` 三目标 IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63 均 Compatible（既有 deprecated/experimental 警告仍在）。用户明确表示“沙盒真机测试我自己来”：主控已停止未启动完成的 `runIde` 尝试，未操作 IDE；真机悬停、窄窗、键盘、跳转与重附 agent 场景留用户验证。首次模块测试曾遇共享 `test-results/binary` 文件缺失的构建环境错误，独占复跑才绿。UI 首轮编译报参数遗漏已修；主控随后独占定向绿，补分色/焦点/过滤链后再次定向绿（此后第二次复核又补了过滤器组判别样本，终态 `StackDigestFramesTest` XML 18/0/0、`SqlPreviewPanelStackDigestTest` 13/0/0）。全量与三代平台兼容性已验证；真机悬停、窄窗、键盘等仍以用户现场回报为准，不能以自动化代替。

- 用户沙盒截图补充：`PerfDemoController.slow:36` 已直接出现在未展开的业务帧区，若干框架帧按段折叠，采集省略 23 帧有明确节点。这只能证明截图所示条目的可见性；用户同时发现点击顶部“调用栈: …”摘要不会打开弹层，原 `StackFrameHover.attach` 只有悬停/移动/移出监听，没有点击分支。此次在同一标签的单击事件中取消悬停定时、更新锚点并立即打开既有树弹层；非左键、无快照不触发，已打开时沿用原防重闸。`StackFrameHoverClickTest` 直接向实际挂接的监听器派送鼠标事件；定向读数待回填。用户保留沙盒真机操作，我方不代测。首次隔离 262 构建被仓内其他测试类的旧平台 API 编译错误挡住（`DbDriverPipelineTest` 等，不涉及本功能），已注销该隔离登记；改用默认 243 平台独占运行 `StackFrameHoverClickTest`，XML 1/0/0（真实挂接的标签监听器：无栈、右键不触发，左键立即请求弹层），构建成功。前一轮 7164/0/0/9 权威全量与三代 Compatible 发生在本次点击接线之前，不冒称覆盖此增量；本次只跑了定向，沙盒鼠标实操仍由用户验证。

### 交付对账（以终态证据回填）

| 计划要求 | 产物与判据 | 当前证据 |
|---|---|---|
| 业务帧默认可见、内部连续组折叠可展开 | `StackFramesProjection`＋`StackFrameHover` 树；不改原栈序 | 投影测试 18/0；真实 GUI 未走查 |
| 省略/截断段可见但不可伪展开 | `StackDigestFrames.parseItems` 独立 Omitted 节点 | 省略节点和分组保序测试在场 |
| 调试器式着色与不抢焦点 | 243 平台 `ColoredTreeCellRenderer` 签名实查、悬停 `setRequestFocus(false)` | 定向编译绿；亮/暗及键鼠真机待查 |
| 复制原文、点击业务帧跳转 | 复用 `UiTextClipboard` / `OpenFileDescriptor` | 代码可判；真机交互未验 |
| 深栈 Controller 保留（条件修复） | `StackDigest` 同根 Service→Controller 锚点、尾过滤链穿透 | 旧算法 17 中 4 红、二轮非 Service 反例 18 中 1 红；终态 `StackDigestTwoEndedTest` XML 20/0/0；非此次截图原始 sd |
| 资源、回归与平台兼容 | 双语 bundle；agent 模块测试、UI 定向、`testShards --no-configuration-cache`、`verifyGate` | 定向 18/0+预览 13/0+agent 样本 20/0；五片全量 7164/0/0/9、654 类 parity；243/253/262 Compatible；Docker daemon 离线；真机由用户验证 |
