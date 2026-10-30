---
name: project-jrebel-premain-crash
description: T-JRebelPremainCrash（20261022 已实施 A/B/C 三案并提交 ee86f967，文档提交 20910e0b，未 push 未发版）——JRebel 启动器与增强捕获共存致 premain 抛 NCDFE、目标 JVM 直接起不来；根因是 premain 链无失败兜底，不是打包漏类；A=全域 catch(Throwable)+谓词进逐类 try+静态块留痕，B=名单上提到零 ASM 依赖的 WeaveSpecs（删 implements 已证伪无用），C=通道过窗未认领即判降级一次性提示；含日常 IDE 真机验收（用户 2026.10.16 版不含修复、beta.1 已装跑通）与决定性对照读数；**并交付 JDK21 一键复现/验收 demo（demo 仓 jdk21-premain-crash-demo，438a6a3，三格真 -javaagent 子进程，可判定任意 agent jar 是否含修复）**
metadata:
  node_type: memory
  type: project
  originSessionId: sess_86e18fbd-6049c1be8e5643c39c0d40c1
---

# T-JRebelPremainCrash：JRebel 启动器 + 增强捕获 → premain 失败、应用无法启动

计划＝`docs/开发计划/20261022-0905-T-JRebelPremainCrash-*`（约 400 行，§十 是实施记录）；
主仓探针＝`dev-support/agent-premain-crash-probe/`；验收装置＝demo 仓 `agent-premain-hostile-loader-repro/`（8 格）。
**状态：A/B/C 三案已全部实施并验证，已提交 `ee86f967`（25 文件、+1985/−105、中文 `-F` 文件），未 push、未发版。**
用户已把 **2026.10.23-beta.1** 装进日常 IDEA（`~/AppData/Roaming/JetBrains/IntelliJIdea2026.2/plugins/mybatis-plus-sql-visualizer`）。

## 用户现场

Amazon Corretto 21.0.5 + JRebel 2026.3.0，同时开增强捕获 → 应用起不来。用户 JDK8/17 未能复现。

```
Caused by: com.zeroturnaround.javarebel.yo: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes
	at ...DriverRegistry.isStatementWeavable(DriverRegistry.java:61)
	at ...AgentMain.retransformLoadedDrivers(AgentMain.java:95)
	at ...AgentMain.install(AgentMain.java:65)
	at ...AgentMain.premain(AgentMain.java:37)
	at ...InstrumentationImpl._jrLoadClassAndStartAgent(InstrumentationImpl.java:560)
	at com.zeroturnaround.jrebelbase.reorder.b.start(SourceFile:117)
*** java.lang.instrument ASSERTION FAILED *** ... JPLISAgent.c line: 627 / 466 / 429
FATAL ERROR in native method: processing of -javaagent failed, processJavaStart failed
```

## 根因（代码级可证）

1. **栈帧与源码逐行对齐**（`AgentMain:37→65→95`、`DriverRegistry:61`）。
2. **致命点在「没兜底」**：`install()` 里 `retransformLoadedDrivers`（65 行）不在 try 内；其内三连名单谓词（94-96 行）也不在 try 内——try 只包 `inst.retransformClasses(c)`。任何 `LinkageError` 穿出 premain，而 **premain 抛异常对 JVM 是致命的**（agentmain 抛只影响 attach）。
3. **`yo` 就是 NCDFE**：`javap` 实证 `com.zeroturnaround.javarebel.yo extends java.lang.NoClassDefFoundError`。JRebel 只是包装。
4. **JRebel 介入点**＝`reorder.d._jrLoadClassAndStartAgent(String,String,String)`：它替换了 JDK 的 premain 类加载/启动（栈里 `loadClassAndStartAgent(...java:1)` 无行号也是痕迹）。其 jar 字节码含 `Forwarding agent: {}`/`hasOtherAgents`/`runEarlyAgents`，由「检测到其他 agent」触发该路径。
5. **缺的类确实在 jar 里**（关键分叉，别去改打包）：三层产物都是 137 条目、`asm/Opcodes.class` 在位（`build/dist` → 发行 zip 内插件 jar → 内嵌 `agent/mps-sql-agent.jar`）⇒ **运行期可见性问题**。
6. 放大点：`AgentMain` 静态块 `catch (Throwable ignore)` 把第一现场吞掉；`sqlvis.agent.active` 写在 retransform **之后**。

## 实施形态（已落地）

- **A 案（止血）**：`install` 拆 `install`(兜底)+`installOrThrow`(原逻辑)，`catch (Throwable)` 一律不外抛 → `degrade()` 只拼字符串打 stderr（`DEGRADE_PREFIX`），**不写 active 标记、不置 installed、不碰任何织入器/注册表类**（类初始化已失败的类二次触达会二次炸）；三连谓词移入逐类 try + 新增 `predicateFailed` 计数与 `[predicate: ...]` 读数；静态块改留一行 `preload DriverRegistry failed`。
- **B 案（结构性）**：新增 **`WeaveSpecs`**（零 ASM 依赖，`javap` 核实 asm 引用数=0），登记表从 `StatementWeaver` 上提；`DriverRegistry` 两面谓词改委托它。`StatementWeaver.specOf` 保留为委托转发。
- **C 案（体验）**：`AgentManager.detectPremainDegraded()`——通道过 `PREMAIN_SKIP_ATTACH_WINDOW_MS`（30s）仍未认领即判降级，**每项目会话一次** WARNING 提示（键 `notification.agent.premainDegraded.*` 双语齐），接在 `doAttachAsync` 跳过分支之后、**不阻断** attach 回落。两判据严格互补（`<=` 窗口内跳过 attach / `>` 窗口外判降级）。
- **未采纳**：去掉 `implements Opcodes`——已证伪（见下）。

## 关键证伪（比原推断更严，勿再走回头路）

1. **P-4「premain 命中恒 0」被推翻**：探针 agent 先在 premain `Class.forName("org.h2.Driver")` 再挂本插件 → **`matched=1 wove=1`**。故「premain 跳过 retransform」会丢能力，已撤销。**修复只能靠兜底，不能靠删功能。**
2. **P-3「删 implements 可把失败点后移」被双重证伪**：`StatementWeaver` 有 6 处 `getstatic Opcodes.LONG/DOUBLE/TOP:Ljava/lang/Integer;`（**装箱字段不可内联**，与 `ConnectWeaver` 只有类头一处不同）；实际造出 `interfaces_count=0` 的副本后**仍在 `isStatementWeavable:61` 抛**，只换了被拒类名（`AbstractInsnNode`/`ClassVisitor`，随解析顺序）。⇒ B 案只能「名单上提」。
3. **机制层独立证据**：最小类里 `untouched(String)` 与不可见类型毫无关系、只调它仍抛 NCDFE ⇒ **类链接期就要求全部引用类型可见**（含 StackMapTable 帧），与被调方法无关。
4. **第二种失败形态**：`-Djava.system.class.loader` 形态下同一失败点抛 **`ClassCircularityError: StatementWeaver`** 而非 NCDFE ⇒ **兜底必须捕 `Throwable`，只捕 LinkageError/NCDFE 会漏**（B3 格实测）。
5. **常量池假象**：`StatementWeaver.class` 里有 **12 个 `org/objectweb/asm/*` 死条目**（`ClassWriter(ClassReader,flags)` 拷贝常量池留下）——**用字符串 grep 核验重定位会误判「没重定位」**，必须按活引用（方法体/描述符/帧）判。

## 读数

- agent 子项目全量 **178/0/0/0**（33 类）；主仓 `log.agent` 包 **204/0/0/0**（27 类）；`testFast` BUILD SUCCESSFUL；全量 `testShards` 五片 **8080/0/0/8**、BUILD SUCCESSFUL 18m49s。
- 新增守卫：`PremainFailsafeGuardTest` 6 例（源码结构）+ `PremainFailsafeBehaviorTest` 6 例（真跑 premain + 敌意加载器，含正对照 `matched>=1` 防假绿）+ `PremainSingleClassFailureTest` 2 例（**§5.2 弱覆盖补齐**：桩对指定类抛 `UnmodifiableClassException`，断言其余类仍被 retransform、`failed=1`、premain 不外抛；配负对照证明 `failed=1` 不是常量）+ `AgentManagerPremainDegradedTest` 5 例 + `AgentManagerAttachAfterDegradeTest` 5 例（**§5.2 弱覆盖补齐**：降级后 attach 建会话、读循环消费 HELLO、真实入口 `onProcessAppeared` 仍能挂载）。
- **反向验证六组全部精确命中**：摘 install 兜底→恰 2 红；DriverRegistry 退回 `StatementWeaver.specOf`→恰 2 红；谓词挪出 try→恰 1 红；摘 C 案一次性标记→恰 2 红；单类失败改为外抛→恰 1 红；降级改成吞掉 attach→**恰 1 红（只有走真实入口那条）**。
- **补 §5.2 时踩的两个自身污染**（都是既有守卫正确报警，改的都是新用例）：①新用例 `setUp` 改了 `attachTaskDispatcher`/`attachDebounceMs`/`attachTargetInspector` 但 `tearDown` 只还原工厂 → 项目级服务实例跨测试类复用、泄漏「同步派发+零去抖」→ 4 条既有「断言 attach 排队发生了」的用例误红；②新用例用 `Class.forName("org.h2.jdbc.JdbcPreparedStatement")` 取桩类 → 破坏 `ZeroLoadClassWriterWeaveTest` 的 `findLoadedClass` 零预载红线（2 红）；修法＝改在独立 `URLClassLoader` 里 `Class.forName(name,false,loader)` 取同名类（类名仍逐字命中名单，系统加载器 `findLoadedClass` 保持 null）。
- 命令层坑：`./gradlew :sql-visualizer-agent:test test --tests "cn.song...log.agent.*"` 会让 `--tests` 过滤同时作用于 agent 子项目任务 → 该模块读数 0 类而构建仍成功；**两模块定向测试必须分开跑**。
- demo 仓 8 格全绿（提交 `8c5784a`）。**修复前后对照**：`reproB2`（jar 真缺 Opcodes）从 `FATAL ERROR` → `exiting normally`；`reproB3` 从 `ClassCircularityError`+FATAL → **`predicateFailed=949` + `exiting normally`**（逐类兜底正在工作的直接证据）；`reproC` 从 `KNOWN-FAIL` → **PASS**。

## 日常 IDE 真机验收（20261022，已做，计划 §10.12/§10.13）

- **P-2 已闭环**：用户告知插件版本是 **2026.10.16**；核该版发行 zip → 插件 jar → 内嵌 agent＝**137 条目、`Opcodes` 在位、无 `WeaveSpecs`** ⇒ 产物完整、不含本次修复（用户须升级）。判「某发行版含不含修复」的可靠标志物＝内嵌 agent 里的 `WeaveSpecs.class` 与条目数 137/138。
- 用户已把 **2026.10.23-beta.1** 装进日常 IDEA（`~/AppData/Roaming/JetBrains/IntelliJIdea2026.2/plugins/mybatis-plus-sql-visualizer`），已核实 138 条目含 `WeaveSpecs`。
- **真机跑通**：JDK 8 + JRebel 2026.4.0（`-agentpath`）+ 调试器 + async-profiler + Cool Request 两个 agent + 本插件 → `Tomcat started on port(s): 8081`、`Started in 10.859 seconds`，无 FATAL。**注意该次 `matched=0`（premain 时无语句类命中）⇒ 没走到崩溃点，单凭它不算命中用户路径。**
- **决定性对照（Corretto 21.0.12.1）**：前置探针 agent 在 premain `Class.forName("com.mysql.cj.jdbc.StatementImpl")` 预热语句类，再挂本插件 → **修复版** `matched=1 wove=1 ... transformFailed=1` 后 `[App] OK` 正常启动；**旧版 2026.10.16** 同条件 → `NoClassDefFoundError ...asm/Opcodes` @ `isStatementWeavable:61` → FATAL。两组唯一差别即 agent 版本。

## JDK21 一键复现/验收 demo（20261022 新建，demo 仓 `jdk21-premain-crash-demo/`，提交 `438a6a3` + 乱码修复 `9e6b993`）

用户要求「在 demo 项目里新增一个 JDK21 的、我能复现的小 demo」，已交付。一条命令：
`powershell -NoProfile -ExecutionPolicy Bypass -File run.ps1`。

- **手段与 `agent-premain-hostile-loader-repro` 不同**：那个用敌意 `ClassLoader` 做可控实验；这个是**真 `-javaagent` + 真子进程 + 从 agent jar 里删掉 `asm/Opcodes.class`**，对 JVM 等同于「此加载器看不见它」，更贴近现场且不依赖 JRebel 授权。
- **三格**：①完整 agent+预热语句类（两版都应活）；②缺 `Opcodes`+预热（修复版活 / 旧版 FATAL，核心）；③缺 `Opcodes`+不预热（修复版活 / 旧版**仍 FATAL**）。
- `WarmupAgent` 在自己的 premain 里 `Class.forName("com.mysql.cj.jdbc.StatementImpl")` 保证插件命中；脚本按 agent jar 有无 `WeaveSpecs.class` 判 `hasFix`，据此切换第 3 格期望。
- **实测**：修复版（已装 beta.1，138 条目含 `WeaveSpecs`）三格全 PASS，第 2 格 `matched=1 wove=1 transformFailed=1` 后仍 `[App] started`；旧版 2026.10.16（137 条目无 `WeaveSpecs`）第 2、3 格均 FATAL 且打出与用户同形栈 ⇒ **这个 demo 可当作「任意版本 agent 是否含修复」的验收器**。
- **三个实现期坑（都已修）**：①插件解压目录在 **`AppData\Local`** 而非 `Roaming`（按 Roaming 找会静默退到主仓产物，掩盖问题）；②`~/.m2` 里较新的 MySQL 是 **5.1.x**（包名 `com.mysql.jdbc.*`，预热会 CNFE），**必须优先 8.x**；③中文乱码不是脚本 BOM 问题——脚本自身正常，是 **java 子进程按 UTF-8 写出、PowerShell 5.1 控制台按 GBK 解码**，修法＝脚本开头设 `[Console]::OutputEncoding`/`$OutputEncoding` 为 UTF-8（或 `chcp 65001`）。
- **防假绿**：预热未生效（`matched=0`）时核心格判 red，避免「classpath 缺驱动 → 没触发 → 全绿」。

## 未做 / 边界

- 本机 JRebel（2026.3.2/2026.4.0）**均为 native agent**：`-javaagent:jrebel.jar` 只打印「改用 `-agentpath:jrebel64.dll`」并让 JVM 退出；`-agentpath` 叠加本插件则正常。**用户那条 `reorder` 纯 Java 转发路径本机未复刻**，只复刻了必要条件（asm 不可见）。
- P-1（用户注入方式）仍未取原始 VM options 佐证，但用户栈含 `loadClassAndCallPremain` 且真机日志已见 premain 读数 ⇒ 事实上是 premain 路径。
- 未发版；`ReproMain#main` 不能直接 Run（需 `格名 + agentJar` 两参数，缺参只打印 usage），且它是敌意加载器复现装置、**不是真机验收入口**。

**Why:** 「增强能力失败」被放大成「宿主无法启动」——agent 类初始化期强依赖重定位 ASM，而 premain 链没有兜底，等于让可选功能握有杀进程的权力；同时「查名单」触发织入器类初始化是更隐蔽的结构性耦合。

**How to apply:** 改 `AgentMain`/织入名单链前先读本档；任何在 premain 里新增的类解析或名单查询都必须确认在 try 内，且新谓词不得经 `implements Opcodes` 的类。参见 [[project-agent-enhanced-capture-consent]]、[[pitfall-agent-capture-zero-events-diagnosis]]、[[project-pending-plans]]。
