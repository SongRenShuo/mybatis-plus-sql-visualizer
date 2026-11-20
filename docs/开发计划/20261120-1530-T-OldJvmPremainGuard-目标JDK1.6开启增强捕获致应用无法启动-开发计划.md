# T-OldJvmPremainGuard：目标 JDK 1.6 开启增强捕获导致应用无法启动

> 用户报障（2026-11-20 现场截图）：JDK 1.6 项目打开「增强捕获」后，**项目直接起不来**。
>
> 本计划已完成根因定位与结构化复现（§三，两次读数在案），修法分「止血闸门」与「结构性引导类」两券，两者都是发版前必须完成的 P0。

## 一、现场与现象

截图错误：

```
java.lang.UnsupportedClassVersionError: cn/song/mybatisplus/sqlvis/agent/AgentMain : Unsupported major.minor version 52.0
        at java.lang.ClassLoader.defineClass1(Native Method)
        at java.lang.ClassLoader.defineClassCond(ClassLoader.java:631)
        at java.lang.ClassLoader.defineClass(ClassLoader.java:615)
```

三条自洽证据指向「目标 JVM 是 JDK 6/7」：

1. 报文用短式 `Unsupported major.minor version 52.0`。JDK 8+ 已改成整句（`has been compiled by a more recent version of the Java Runtime … only recognizes class file versions up to 52.0`），本机 JDK 8 复现读数即长句（§三）；短式是 JDK 6/7 的写法。
2. 栈里的 `ClassLoader.java:631` / `:615` 是旧 rt.jar 的行号；JDK 8 同一条链落在 `ClassLoader.java:763`（§三 B 组读数）。
3. major 52 = Java 8，正好等于 agent 产物的编译目标：`sub-projects/sql-visualizer-agent/build.gradle.kts` 的 `tasks.compileJava { options.release = 8 }`；而 JDK 6 的 JVM 最高只认 major 50（JDK 7 是 51）。

产品面本身只承诺 JDK 8~26（M56 计划、`build.gradle.kts` 头注释）。**问题不是「JDK 6 采集不到」，而是「JDK 6 上被我们杀掉了进程」**。

## 二、根因

### 2.1 直接原因：入口类就是 Java 8 字节码

实测 `mps-sql-agent.jar`（392,756 字节，152 个类条目）：

| 类别 | 条目数 | major |
|---|---|---|
| agent 自身（`cn/song/mybatisplus/sqlvis/agent/**`） | 49 | **52**（Java 8） |
| 重定位后的 ASM（`…/agent/asm/**`） | 103 | 49（Java 5） |

manifest 的 `Premain-Class` 与 `Agent-Class` 同指 `cn.song.mybatisplus.sqlvis.agent.AgentMain`（major 52）。老 JVM 连这个入口类都定义不了，谈不上降级。

### 2.2 致命性来自 premain，不来自 attach

同一个类版本失配，两条入口的后果完全不同：

- **premain（`-javaagent`）**：JVM 把「入口类加载失败」判为 agent 处理失败 → `FATAL ERROR in native method: processing of -javaagent failed`，主类 `main` 一行都没执行，进程退出码 1。这就是用户说的「项目无法启动」。
- **attach（`loadAgent`）**：失败只抛出 `AgentInitializationException` 给调用方，目标进程继续正常跑（§三 C 组逐秒 tick 读数）。

`AgentMain` 里已有 T-JRebelPremainCrash 的全域 `catch (Throwable)` 兜底，但那一层在**安装链内部**，兜不住「入口类本身上下不了」——这是 L7（JVM 类链接期就要全可见）教训的边界外延：上次防的是「类内部抛异常」，这次是「类根本不加载」。

### 2.3 为什么没有一道闸门拦住

`PremainInjection.decide` 现有三道闸门：增强开关 → agent jar 可用 → 端口协商成功。**没有 JDK 版本维度**；`grep -rn "java.specification.version|JavaVersion|getJdk()" src/main/kotlin` 零命中。注入侧从不问目标 JVM 认不认 major 52，直接把 `-javaagent` 塞进用户的命令行。

## 三、实证复现（真 JDK 1.6，2026-11-20）

### 3.1 环境

Azul Zulu 6（`zulu6.22.0.3-jdk6.0.119-win_x64.zip`，61,766,713 字节，sha256 `24059e8915d675074a80047c75cd6e0672b5c2cadf5b398fb6a6c51d035c54ca`）解压到 `D:\HuanJing\Java\jdk1.6.0_119`，与 `jdk1.8.0_181` 同级：

```
openjdk version "1.6.0-119"
OpenJDK Runtime Environment (Zulu 6.22.0.3-win64) (build 1.6.0-119-b119)
```

运行期类版本上限实测：JDK 6 `java.class.version=50.0`，JDK 8 `52.0`，agent 产物 `52`——差值就是这次故障的全部。

测试程序用 **JDK 6 自己的 javac** 编（`-source 1.6 -target 1.6`，否则程序本身也起不来），载体是**真实产出的 `mps-sql-agent.jar`**。复现产物在 `%TEMP%\mpsv-jdk6-repro\`。

### 3.2 三组读数

| 组 | 命令 | 读数 |
|---|---|---|
| A 对照 | `jdk1.6.0_119/bin/java -cp app16 App` | `[APP] main started, java.version=1.6.0-119` / `main finished`，**EXIT=0** |
| B 复现 | `jdk1.6.0_119/bin/java "-javaagent:agent-orig.jar=port=1;logLevel=info" -cp app16 App` | `java.lang.UnsupportedClassVersionError: cn/song/mybatisplus/sqlvis/agent/AgentMain : Unsupported major.minor version 52.0` → `sun.instrument.InstrumentationImpl.loadClassAndCallPremain` → **`FATAL ERROR in native method: processing of -javaagent failed`**，`[APP]` 一行未出，**EXIT=1** |
| C attach | 对运行中的 JDK 6 进程 `VirtualMachine.loadAgent(agent-orig.jar, …)` | `AgentInitializationException: Agent JAR loaded but agent failed to initialize`；目标进程照常 `tick 0…14` 到 `finished normally`（日志尾部多一段 agent 侧栈，不影响宿主） |

B 组与用户截图的对照：报文句式（短式 `Unsupported major.minor version 52.0`）逐字一致；栈同样终结于 `sun.instrument.InstrumentationImpl.loadClassAndStartAgent/CallPremain`。框架行号随 update 级别微移——本机 6u119 是 `ClassLoader.java:648`，现场截图是 `defineClassCond(ClassLoader.java:631)` / `defineClass(615)`，属更早的 6u（或 7，同样认不到 52）。版本点位不同，失败类别与后果完全相同。

### 3.3 券02 形态原型（临时验证，不进仓）

用 JDK 6 的 javac 编出 major 50 的引导类（`AgentShim`，只用 Java 6 及更早 API），塞进 agent jar 并改写两条入口，三组读数：

| 场景 | 读数 |
|---|---|
| JDK 6 + 引导类 jar | `[SqlVisAgent] skipped: jvm=1.6.0-119 class.version=50.0 needs java8+` + `[PROBE] … active marker=null`，**EXIT=0**（同一命令在改前 EXIT=1、主类不执行） |
| JDK 8 + 引导类 jar | 正常委托：`[PROBE] agent active marker=port=1`（agent 真装上了，零回归） |
| JDK 8 + 引导类 jar 内含 major 53 的 `AgentMain` | `[SqlVisAgent] bootstrap failed: UnsupportedClassVersionError …` + `active marker=null`，**EXIT=0**——不只挡住版本闸，任何加载失败都不再杀宿主 |

结论：**根因确认且已在真 1.6 上复现（B 组即用户现场）；致命面只有 premain 注入（C 组）；券02 的形态在真 1.6 与 JDK 8 双向验证通过。**

## 四、修法

### 券01（P0，止血）：注入前加目标 JVM 版本闸门

改点固定在现有三闸门处，不新增异步链：

1. **纯函数**（`PremainInjection.kt`）：`decide` 增第 4 个入参 `targetJvmMaxMajor: Int?`，新增常量 `MIN_TARGET_CLASS_MAJOR = 52` 与原因码 `REASON_TARGET_JVM_TOO_OLD = "target-jvm-too-old"`。判定位置放在「增强开关为假」之后、「jar 可用 / 端口协商」之前——求值顺序即副作用顺序，老 JVM 上**不落盘 jar、不 bind 端口**（沿用既有「开关为假不协商」的零副作用口径）。未知（null）放行，结构性保护交给券02。
2. **探测层**（新纯函数 `TargetJvmCompat.maxSupportedMajor(jdkHome)`，headless 可测）：
   - 先读 `<home>/release` 的 `JAVA_VERSION="1.6.0_45"` → major 映射（`1.x` → `44+x`；`9+` → `44+x`）；
   - 没有 `release`（老 JDK 形态待验，见 §五.3）→ 读 `<home>/lib/rt.jar` 或 `<home>/jre/lib/rt.jar` 首个 class 条目的 major（rt.jar 就是该 JVM 自身的字节码版本）；
   - 都拿不到 → null（未知）。
   - 结果按 jdkHome 进 `ConcurrentHashMap` 缓存，失败也缓存（负缓存哨兵），避免每次 Run 读盘。
3. **壳层**（`PremainRunConfigurationExtension`）：`params.getJdkPath()`（javap 实证 243 `java-impl.jar` 有 `public String getJdkPath()`）→ 探测 → 传入 `AgentManager.preparePremainVmOption(targetJvmMaxMajor)`。壳层沿用现有 `runCatching` 零劣化约定：探测异常按「未知」处理（放行，靠券02 兜）。
4. **用户可见提示**：命中闸门时发一次性通知（`cn.song.mybatisplus.visualizer.agent` 通知组已存在），文案「目标 JDK x 不支持增强捕获（需 JDK 8+），本次未注入，SQL 仍可从控制台日志捕获」，按 `(project, jdkHome)` 去重、同会话只报一次；文案进 `MpSqlVisualizerBundle`（中英各一），不硬编码中文。

### 券02（P0，结构性）：入口换成低版本引导类，任何加载失败都不再致命

`-javaagent` / `loadAgent` 的入口类从 `AgentMain` 换成新的 `AgentShim`，**这是「绝不让增强捕获杀掉宿主」的结构保证**，覆盖券01 探不到的一切形态（Gradle toolchain 老 fork、JRebel 式 loader、未来再出现的新失配）：

1. `AgentShim`（agent 主源集，只许用 Java 6 及更早 API）：
   - 读 `System.getProperty("java.class.version")`（= 当前 JVM 认到的最高 major，JDK 6 上是 `50.0`）；低于 52 → stderr 打一行 `[SqlVisAgent] skipped: jvm=<java.version> needs java8+`，直接 return；
   - 否则 `Class.forName("…AgentMain", true, AgentShim.class.getClassLoader())` 反射调 `premain/agentmain(String, Instrumentation)`；
   - 全段 `catch (Throwable)`，任何失败只留一行说明，绝不外抛（premain 外抛 = 宿主起不来）。
2. **产物 major ≤ 50**：`RelocateAsmMain` 已经在用 ASM 逐类重写（`ClassRemapper`），对 `AgentShim.class` 增加一步版本覆盖（`ClassVisitor.visit` 传 `Opcodes.V1_6`），与 relocate 同一趟完成；manifest 两条入口改指 shim。
   - 备选（不推荐）：给子项目挂 JDK 8 toolchain 单独编 shim——javac 17 的 `--release` 最低 8，编不出 major 50，等于给 CI 加一条工具链依赖。
3. **守卫**：新 `AgentShimClassGuardTest` 读 fat jar 内的 `AgentShim.class`，断言 ① major == 50；② 常量池不含 `invokedynamic`、`java/lang/invoke`、`java/util/function`、`java/util/Objects`（防后续顺手写 lambda / Java 7+ API 把 major 又抬上去）。
4. 契约不变：`AgentMain` 的双入口、幂等标记、降级口径全部保持原样，shim 只是多加一层「能不能加载」的前置判定。
5. **形态已原型验证**（§3.3）：`AgentShim` 在真 1.6 上跳过、在 1.8 上正常委托、内部类加载失败时不杀宿主，三条都实测过。与产线的唯一差别是编译工具链——原型用 JDK 6 的 javac 原生出 major 50，产线是 JDK 17 toolchain，故需 `RelocateAsmMain` 那一步降版本（第 2 点）。

### 券03（P1，收口）：文档与账本

- `docs/架构设计/02-核心流程详解.md` agent 段补「目标 JVM 版本门槛 + 入口引导类」两行；
- `sub-projects/sql-visualizer-agent/build.gradle.kts` 头注释在「覆盖 JDK8~26」后补一句「低于 JDK 8 的目标 JVM 由引导类静默跳过，绝不影响宿主启动」；
- 设置页「增强捕获」文案若涉及支持范围，补「目标 JDK 需 8 以上」；
- `CHANGELOG.md` `[Unreleased]` 一条（用户可感：JDK 8 以下目标开增强捕获不再导致项目起不来）；
- `docs/LESSONS.md` 新增一条 L：**premain 入口类本身必须能被目标 JVM 加载**（major 门槛），同一失配在 attach 只是失败一次、在 premain 是杀死宿主；证据用本计划 §三读数 + 落地提交哈希。

### 不改什么

- 不把 agent 主类降到 Java 6 字节码：agent 已在用 `java.util.function.LongSupplier`、`java.util.Optional` 等 Java 8 API，降 target 等于重写传输层，收益为零（产品面本就不支持 JDK 6/7 采集）。
- 不动「注入不落盘」现状：`JavaParameters` 是每次执行现建的瞬时对象，`grep` 确认全仓没有把 VM 选项写回用户配置的路径——用户关掉开关或升级插件后不会残留 `-javaagent`。

## 五、待验证前提

1. **`params.jdkPath` 的语义**：Application / Spring Boot 配置应等于目标 JVM（闸门可直接用）；Gradle / Maven external-system 配置可能给的是 Gradle / Maven 自身 JVM，而 `bootRun` 等 fork 出来的应用 JVM 由 toolchain 决定——这类形态券01 探不到，必须靠券02 兜。实施时在沙盒里对三种配置各打一条日志实测，读数写进 §八。
2. `JavaParameters.getJdkPath()` 在 262 也可调（243 已 javap 实证），262 同日复核一次。
3. **已实测（2026-11-20）**：Zulu JDK 1.6.0_119 安装目录**没有 `release` 文件**，只有 `jre/lib/rt.jar`；且该 rt.jar 全部是 **major 49**（Oracle 系 JDK 6 自带类库按 1.5 编译）——并非此前假设的 50。判定仍正确（49 < 52 一样拦下），但**不能拿它报具体版本号**，故提示文案改为不报版本、只报「低于 8」，对应用例见 `TargetJvmCompatTest.rtjar实测形态_major49同样被判定为不支持`。
4. **已实测**：`java.class.version` 在 Zulu 6.0.119 上是 `50.0`、Oracle 8u181 上是 `52.0`——即该 JVM 的类版本上限，shim 的阈值判定成立（国产 JVM 未测，若非则退化为「猜」，`catch (Throwable)` 的结构性保护不受影响）。
5. `updateJavaParameters` 的调用线程与耗时：该处已做 jar 落盘与 `ServerSocket` bind，探测比它们轻；但首次读 `rt.jar` 要走缓存，若实测在 EDT 且可感停顿，改在设置页打开时预热。
6. **已实测**：`RelocateAsmMain` 用 ASM 把 major 52 的 `AgentShim` 以 `V1_6` 输出可行——产物实测 `AgentShim.class` major = 50、`AgentMain.class` 52、重定位 ASM 49（`major 分布: {49: 103, 50: 1, 52: 49}`），manifest 两条入口均指向 `AgentShim`。

## 六、验收清单（可照抄）

1. 真机复现重跑（§三 A/B/C 三组，JDK 6 在 `D:\HuanJing\Java\jdk1.6.0_119`），读数与本计划一致；JDK 8 上的 major 53 结构等价复现可作二次旁证。
2. **改后同一复现**：用券02 产物（入口=shim）配 major 53 的 `AgentMain` 在 JDK 8 上跑 → **应用必须照常启动**（`[APP] main started` 出现），stderr 只多一行 skipped/degraded；改前同命令是 `FATAL ERROR in native method` + 退出码 1。
3. 真实 jar 在 JDK 8 上经 `-javaagent` 与 attach 两条入口行为与改前一致：`AgentPremainGateTest`、`O147RealAgentAcceptanceTest` 全绿，增强捕获照常出数。
4. 闸门用例：`jdkHome` 夹具（`release` 写 `1.6.0_45`）→ `decide` 返回 `Skip("target-jvm-too-old")`，且断言**不落盘 jar、不 bind 端口**（沿用既有探针形态）。
5. 反向验证两条，各贴两次读数：摘掉券01 的 major 比较 → 闸门用例必红；摘掉券02 的 `catch (Throwable)`（或把 shim 换成 lambda 写法）→ 守卫/patched-jar 用例必红。
6. 真机：JDK 1.6 项目开增强捕获 → 项目正常启动 + 一次性通知「目标 JDK 不支持」；关掉开关再开、二次运行不重复刷通知。
7. `AgentShimClassGuardTest` 读数：shim major == 50、常量池无禁用引用。

## 七、定向测试纪律

- 本券触达类：`PremainInjectionTest`、`AgentManagerPremainTest`、新增 `TargetJvmCompatTest`、`AgentShimClassGuardTest`；agent 子项目加跑 `:sql-visualizer-agent:test` 中 premain/attach 相关类。
- 纯逻辑改动可先跑 `testFast`；只跑本任务相关定向类，**不跑全量、不跑构建校验、不 `clean`、不 `--stop`**；跑 Gradle 前先过并行登记表。
- 券02 动了 fat jar 的打包步骤，属「大改动」判据边缘（未改 `plugin.xml` 的 EP / 依赖版本），发版前须补一次 `verifyPlugin`。

## 八、实施记录

| 券 | 状态 | 提交 | 读数 |
|---|---|---|---|
| 复现与形态原型（真 JDK 1.6.0_119） | 已完成 2026-11-20 | —（仅计划与临时产物，零生产码） | §3.2 三组 + §3.3 三组 |
| 券01 闸门 | 已完成 2026-11-20 | 本提交 | `PremainInjectionTest` 5/0、`TargetJvmCompatTest` 8/0、`AgentManagerPremainTest` 16/0；反向摘闸 → 「老 JVM 上不得协商端口」红，还原后绿 |
| 券02 引导类 | 已完成 2026-11-20 | 本提交 | 产物 major 分布 `{49:103, 50:1, 52:49}`、manifest 双入口=AgentShim；`AgentShimClassGuardTest` 4/0 + agent premain/attach 16/0；反向摘降版本 → 「引导类必须是 major 50，实际 52」红，还原后绿 |
| 券03 收口 | 已完成 2026-11-20 | 本提交 | 架构文档 02 / CHANGELOG / LESSONS L93 / 构建脚本注释 |
| 真机验收 | 已完成 2026-11-20 | 本提交产物 | JDK 1.6.0_119：`skipped … does not support Java 8+ bytecode` + `[PROBE] … active marker=null`、**EXIT=0**（改前同命令 EXIT=1）；JDK 8：`active marker=port=1`（正常委托）；JDK 6 attach：`loadAgent returned normally` + 目标进程跑到 `finished normally` |
