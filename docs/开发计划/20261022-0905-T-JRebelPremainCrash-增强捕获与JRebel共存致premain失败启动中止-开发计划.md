# T-JRebelPremainCrash：增强捕获与 JRebel 启动器共存致 premain 失败、目标 JVM 无法启动

**创建时间**：2026-10-22 09:05
**触发源**：用户真机报障（Amazon Corretto 21.0.5 + JRebel 2026.3.0 + 本插件增强捕获开启）
**基线**：`master@378c2962`（分析时 tip）。用户实际加载的插件版本**尚未确认**，不得以本轮 tip 冒充用户现场产物。
**状态**：仅归因分析与立计划。**未修改生产代码、未跑测试、未复现用户原始组合、未预占 O/R/D/V 编号、未提交、未推送、未发版。**

## 一、用户报告的故障

用户同时启用本插件「增强捕获」与 JRebel 启动器后，目标应用无法启动，控制台首段即：

```
Exception in thread "main" java.lang.reflect.InvocationTargetException
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:118)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.instrument/sun.instrument.InstrumentationImpl._jrLoadClassAndStartAgent(InstrumentationImpl.java:560)
	at java.base/com.zeroturnaround.jrebelbase.reorder.b.start(SourceFile:117)
	at java.instrument/sun.instrument.InstrumentationImpl.loadClassAndStartAgent(InstrumentationImpl.java:1)
	at java.instrument/sun.instrument.InstrumentationImpl.loadClassAndCallPremain(InstrumentationImpl.java:572)
Caused by: com.zeroturnaround.javarebel.yo: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes
	at cn.song.mybatisplus.sqlvis.agent.DriverRegistry.isStatementWeavable(DriverRegistry.java:61)
	at cn.song.mybatisplus.sqlvis.agent.AgentMain.retransformLoadedDrivers(AgentMain.java:95)
	at cn.song.mybatisplus.sqlvis.agent.AgentMain.install(AgentMain.java:65)
	at cn.song.mybatisplus.sqlvis.agent.AgentMain.premain(AgentMain.java:37)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	... 5 more
*** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" ... JPLISAgent.c line: 627
*** java.lang.instrument ASSERTION FAILED ***: "success" ... line: 466
*** java.lang.instrument ASSERTION FAILED ***: "result" ... line: 429
FATAL ERROR in native method: processing of -javaagent failed, processJavaStart failed
```

用户环境：Amazon Corretto 21.0.5，JRebel 启动器。用户本机（JDK 8 / 17）**未能复现**。

## 二、已核事实（代码与产物级，非推断）

### 2.1 栈帧与当前源码逐帧对齐

| 栈帧 | 源码位置 | 现状 |
|---|---|---|
| `AgentMain.premain:37` | `AgentMain.java:36-38` | `premain → install(agentArgs, inst)`，无 try |
| `AgentMain.install:65` | `AgentMain.java:65` | `retransformLoadedDrivers(inst)` —— **不在 try 内** |
| `AgentMain.retransformLoadedDrivers:95` | `AgentMain.java:94-96` | `isWeavable \|\| isStatementWeavable \|\| isPrepareWeavable` 三连谓词 —— **不在 try 内**（`try` 只包 `inst.retransformClasses(c)`，第 105-114 行） |
| `DriverRegistry.isStatementWeavable:61` | `DriverRegistry.java:60-63` | 调用 `StatementWeaver.specOf(className)` |

**结论（事实）**：任何 `LinkageError` 都会从 premain 逃逸。premain 抛异常对 JVM 是**致命**的——`-javaagent` 失败即 `processJavaStart failed`，应用根本起不来。同一异常若走 `agentmain`（attach）则只影响 attach，不会杀宿主。

### 2.2 缺失类的归属：重定位后的 ASM 父接口，不是打包漏类

- `RelocateAsmMain` 把 `org/objectweb/asm` 全前缀改写为 `cn/song/mybatisplus/sqlvis/agent/asm`（`RelocateAsmMain.java:29-31,44-52`）。
- `StatementWeaver.java:49` = `public final class StatementWeaver implements Opcodes`，`ConnectWeaver.java:41` 同形。
- 字节码实证（`javap`）：`StatementWeaver` 类头为 `implements cn.song.mybatisplus.sqlvis.agent.asm.Opcodes`，方法体引用 `agent/asm/{ClassReader,ClassVisitor,ClassWriter,Opcodes,tree}`。
- **产物实证**：`sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar` 137 条目，含 `cn/song/mybatisplus/sqlvis/agent/asm/Opcodes.class`；发行 zip `mybatis-plus-sql-visualizer-2026.10.23-beta.1.zip` → 插件 jar → 内嵌 `agent/mps-sql-agent.jar` 同样 **137 条目、`Opcodes` 在位**。
- **所以：不是「类没打进 jar」，而是「加载期该加载器解析不到它」**。这是本次归因的关键分叉——打包缺失与运行期可见性是两种截然不同的修法。

### 2.3 `com.zeroturnaround.javarebel.yo` 的身份

`javap` 实证：`public class com.zeroturnaround.javarebel.yo extends java.lang.NoClassDefFoundError`。JRebel 只是把 `NoClassDefFoundError` 包成自己的子类，**语义仍是 NCDFE**。这不是 JRebel 特有的不兼容，而是它改了加载路径后暴露出的既有脆弱点。

### 2.4 JRebel 介入点

`com.zeroturnaround.jrebelbase.reorder.b.start`（SourceFile:117）与接口 `reorder.d._jrLoadClassAndStartAgent(String,String,String)`：JRebel 用自己的 `InstrumentationImpl._jrLoadClassAndStartAgent` 替代 JDK 原生 `loadClassAndStartAgent`，即 **premain 的类加载与启动被 JRebel 接管**。栈中 `InstrumentationImpl.loadClassAndStartAgent(InstrumentationImpl.java:1)` 的 `:1`（无行号）也印证该帧被替换。

### 2.5 本机负对照（已实测，JDK 21）

用 `F:/.../build/dist/mps-sql-agent.jar` 在 **OpenJDK 21.0.11**（`~/.jdks/ms-21.0.11`）上跑最小 main：

```
[SqlVisAgent] attach retransform: matched=0 wove=0 ... 
MAIN-OK-21.0.11+10-LTS
```

**单独挂本插件 agent 在 JDK21 上正常启动**，`matched=0` 还说明 premain 时刻尚无已加载 JDBC 驱动。叠加本机 JRebel 2026.3.0 时，因其已是 native agent（提示改用 `-agentpath:jrebel64.dll`）而无法按用户形态复刻，故**「JRebel + 本 agent」的合成复现交子智能体在 demo 仓以 hostile ClassLoader 等价形态完成**（见 §五）。

### 2.6 既有静默点（掩盖了第一现场）

`AgentMain.java:29-34` 静态块：

```java
static {
    try {
        Class.forName(DriverRegistry.class.getName(), true, AgentMain.class.getClassLoader());
    } catch (Throwable ignore) { }
}
```

该块在类初始化期就预定义 `DriverRegistry`，但**把加载失败静默吞掉**。若此处已失败，现场只会看到后续更靠外的一帧，第一现场（究竟是 `DriverRegistry` 还是 `StatementWeaver` 解析不到 ASM）被抹掉。

### 2.7 P-4 实测：premain 的 retransform **不是**低收益（推断被推翻）

原推断「premain 时无已加载驱动，故 `retransformLoadedDrivers` 收益极低，可考虑跳过以消灭崩溃面」。用探针 agent 实测（OpenJDK 21.0.11，按 `-javaagent` 顺序排列）：

| 场景 | 读数 |
|---|---|
| 仅本插件 agent | `matched=0 wove=0` —— 印证「单 agent 时 premain 命中为空」 |
| 前置探针 agent 在 premain 里 `Class.forName("org.h2.Driver")`，再挂本插件 | **`matched=1 wove=1 unmodifiable=0 failed=0`** |

**推翻**：多 agent 并按序执行时，前排 agent 完全可能在 premain 阶段就把驱动类拉起来；本插件随即在 premain 命中并成功织入。用户现场（JRebel 排在前）正是这一形态。故「premain 跳过 retransform」这条路**不能走**——它会在最需要采集的多 agent 场景下静默丢失能力。

这条实测同时给出一个反向收益：**premain 路径的 retransform 是有真实价值的**，修复必须保留它，只能通过兜底让它「失败时降级而非杀宿主」。

### 2.8 端到端最小复现与修复验证（已实测，OpenJDK 21.0.11）

用两个 20 行的探针 agent 把「崩溃」与「修复」都跑出来了，**除 JRebel 的类加载接管层与其 `yo` 包装外，用户现场的断言链与 FATAL 行逐字一致**：

**① 复现（premain 抛 `LinkageError`，未兜底）**：

```
Exception in thread "main" java.lang.reflect.InvocationTargetException
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.instrument/sun.instrument.InstrumentationImpl.loadClassAndStartAgent(InstrumentationImpl.java:560)
	at java.instrument/sun.instrument.InstrumentationImpl.loadClassAndCallPremain(InstrumentationImpl.java:572)
Caused by: java.lang.LinkageError: simulated: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes
	at BadAgent.premain(BadAgent.java:4)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	... 3 more
*** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" ... JPLISAgent.c line: 627
*** java.lang.instrument ASSERTION FAILED ***: "success" ... line: 466
*** java.lang.instrument ASSERTION FAILED ***: "result" ... line: 429
FATAL ERROR in native method: processing of -javaagent failed, processJavaStart failed
```

与用户日志对比：`JPLISAgent.c` 三行行号（627/466/429）与 `processJavaStart failed` **完全一致**；差异仅在用户侧多了 JRebel 的 `_jrLoadClassAndStartAgent` 帧、`reorder.b.start` 帧，异常类型从 `LinkageError` 变为其子类 `yo`。**这证明：用户遇到的致命性来自「premain 外抛」这一机制本身，与 JRebel 是否参与无关。**

**② 修复形态（premain 全域 try/catch）**：

```
[ProbeAgent] premain degraded: java.lang.LinkageError: simulated: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes
MAIN-OK
```

同一份必定抛错的 `install`，包上一层 try/catch 后**应用正常启动**，只留一行降级说明。这验证了 §4.1 第 1 条的有效性。

> 注：探针的 catch 只打一行 stderr，**不等于**最终实现——最终实现还须满足「不写 `sqlvis.agent.active`」「结构化字段」「禁含 SQL 原文/凭据/全路径」等约束（§4.1、§5.2）。

### 2.9 Corretto 21 + 真实 agent jar 的精确复现（已实测）

§2.8 的探针证的是「premain 外抛必致命」；本节证的是**用真实 `mps-sql-agent.jar` 在被报的 JDK 系列上产生同一条 NCDFE**。

环境：`Amazon Corretto 21.0.12.1`（本机 `~/.jdks/corretto-21.0.12.1`；**与用户的 21.0.5 同主版本不同补丁，非同一构建**，读数归属须按此记）。
装置：`URLClassLoader` 子类，仅对 `cn.song.mybatisplus.sqlvis.agent.asm.*` 抛 `ClassNotFoundException`（模拟 JRebel 接管 premain 类加载后重定位 ASM 不可见）；`Instrumentation` 用动态代理桩，`getAllLoadedClasses()` 返回一个语句面登记类（`com.mysql.cj.jdbc.StatementImpl` 桩），使循环真正走到 `DriverRegistry.isStatementWeavable`。

| 变体 | 读数 |
|---|---|
| asm **正常可见** | `AgentMain loaded by URLClassLoader` → `matched=1 wove=1` → **`premain RETURNED NORMALLY`** |
| asm **不可见** | **`premain THREW java.lang.NoClassDefFoundError: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes`**；cause 链为 `ClassNotFoundException: cn.song.mybatisplus.sqlvis.agent.asm.Opcodes` |

**缺失类与用户日志逐字相同**（`cn/song/mybatisplus/sqlvis/agent/asm/Opcodes`）。这排除了「用户环境打包不同/版本更早」这一支——即便产物完整，只要该加载器看不见重定位的 asm 包，就必然在此处抛 NCDFE。

同时，本节装置证实了「`matched=0` 时不会踩到该谓词」：**必须先有语句面/驱动面登记类被加载**，`retransformLoadedDrivers` 才会调用三连谓词。这解释了为何单 agent、干净启动的应用从不触发——也解释了为何用户的崩溃需要语句类在 premain 时刻已加载。

### 2.10 P-3 字节码实证：`implements Opcodes` 不是唯一解析点

`javap -v` 逐条统计（`mps-sql-agent.jar` 内产物）：

| 类 | `Opcodes` 引用形态 | 去掉 `implements` 是否有效 |
|---|---|---|
| `StatementWeaver` | 类头 1 处 + 方法体 **6 处 `getstatic ...Opcodes.LONG/DOUBLE/TOP:Ljava/lang/Integer;`** | **无效**——这些是 `Integer` 装箱静态字段引用，不是可内联的 `int` 常量 |
| `ConnectWeaver` | **仅类头 1 处**（int 常量全部被 `javac` 内联） | 有效 |

**结论**：`implements Opcodes` 的移除只对 `ConnectWeaver` 成立；`StatementWeaver` 因使用 `Opcodes.LONG/DOUBLE/TOP` 这三个 `Integer` 字段（帧补丁要用 `Object` 形态的 frame info），无论是否 `implements`，只要触达其类初始化就要求 ASM 可解析。B 案据此收窄（§4.2）。

### 2.11 demo 仓独立复现装置的复核读数（子智能体交付，2026-10-22）

子智能体在 demo 仓建了独立子工程 `agent-premain-hostile-loader-repro/`（提交 `e4342d1` + `c75736f`，不进聚合 pom），8 格全部符合预期、`run.ps1` 一条命令跑完、退出码 0。**它独立复现出的栈比主控 §2.9 更完整——四个栈帧连行号与用户现场逐行一致**：

```
Caused by: java.lang.NoClassDefFoundError: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes
	at cn.song.mybatisplus.sqlvis.agent.DriverRegistry.isStatementWeavable(DriverRegistry.java:61)
	at cn.song.mybatisplus.sqlvis.agent.AgentMain.retransformLoadedDrivers(AgentMain.java:95)
	at cn.song.mybatisplus.sqlvis.agent.AgentMain.install(AgentMain.java:65)
	at cn.song.mybatisplus.sqlvis.agent.AgentMain.premain(AgentMain.java:37)
*** java.lang.instrument ASSERTION FAILED *** ... JPLISAgent.c line: 627 / 466 / 429
FATAL ERROR in native method: processing of -javaagent failed, processJavaStart failed
```

其 `B2` 格用**缺 `Opcodes` 的真实 jar** + 真 `-javaagent` 跑出了与用户日志**逐字一致**的全链（含 `loadClassAndStartAgent:560` / `loadClassAndCallPremain:572` 两帧），比 §2.8 的探针更贴近现场。

**三处比主控结论更进一步或修正主控的地方：**

1. **P-3 证伪比主控更严**：`InterfaceStripper` 造出 `interfaces_count=0` 的 `StatementWeaver` 副本后重跑，**仍在 `isStatementWeavable:61` 抛**，只是被拒的 ASM 类名换成了 `tree/AbstractInsnNode`（两次独立运行还分别得到过 `ClassVisitor`，取决于 JVM 解析顺序）。所以删 `implements` **连「把失败点后移」都做不到**，只是换了个替死鬼——主控原表述（「对 StatementWeaver 无效」）偏保守，实际是「无效且有误导性」。
2. **补上了机制层的独立证据（`linkage` 格）**：一个最小类，其 `untouched(String)` 方法与不可见类型毫无关系，只调它仍抛 NCDFE。这证明**类链接期就要求全部引用类型可见**（含 StackMapTable 帧），与被调方法无关——这正是「删接口无效」的根因，而非仅凭计数推断。
3. **发现一个新失败形态（`B3`）**：把该 jar 装成 `-Djava.system.class.loader` 时，同一失败点抛的是 **`ClassCircularityError: StatementWeaver`** 而非 NCDFE，随后同样是 `FATAL ERROR`。**说明此处的外抛面不止 `LinkageError` 的子类 NCDFE，兜底必须捕获 `Throwable` 而不是只捕 `LinkageError`/`NoClassDefFoundError`。** 这条直接约束 §4.1 的实现形态。

**一个会让 grep 得出错误结论的坑（recon0 抓到）**：`StatementWeaver.class` 常量池里有 **12 个 `org/objectweb/asm/*` Class 项**，全部落在引用闭包之外（`ClassWriter(ClassReader,flags)` 拷贝常量池留下的死条目）。**用字符串搜索会误判成「ASM 未重定位」**——核验重定位必须按「活引用」（方法体/描述符/帧）判定，不能按常量池字符串。

**Repro C 的判据也已收紧**：C-before 先证明装置有鉴别力（栈帧落在 `isStatementWeavable` + `getAllLoadedClasses` 含登记表桩类 + 抛 NCDFE 且消息含 `asm/Opcodes`），C-after 断言「修复后不抛 + stderr 有降级说明」，现状 2 项红（`KNOWN-FAIL(expected)`），修复后应转绿。它还自我纠正了一个错误代理指标：**在 asm 不可见路径上 `retransformCalls` 必然为 0**（失败发生在谓词内部、还没到 retransform），不能用它证明循环走到了谓词，只能用栈帧。

## 三、根因判定

### 3.1 主因（高置信，代码级可证）

**premain 路径缺少整体失败兜底 + agent 类初始化期强依赖重定位 ASM**，两者叠加把「增强能力不可用」放大成「宿主应用无法启动」。

链条：

1. JRebel 接管 premain 的类加载（§2.4）；
2. 在该加载路径下，`StatementWeaver` 的父接口 `cn/song/.../asm/Opcodes` 解析失败（§2.2 证明类在 jar 内，故为可见性问题）；
3. 触发点是 `DriverRegistry.isStatementWeavable` → `StatementWeaver.specOf`，即**类初始化**（`SPECS` 静态表）而非真正织入；
4. 该调用在 `retransformLoadedDrivers` 的 try **之外**（§2.1）；
5. 异常穿出 `premain` → JVM 判 `-javaagent` 失败 → **应用无法启动**。

### 3.2 放大因素

- **premain 时刻做 retransform 本身价值有限**：单 agent 时实测 `matched=0`；但**前置 agent 预热驱动后实测 `matched=1 wove=1`**（§2.7）——多 agent 场景下它恰恰命中最多的驱动，因此这是真实能力面，不能删只能兜。换言之，premain 里这段高危调用**收益低而爆炸半径最大**。
- **两个织入器都 `implements Opcodes`**（`StatementWeaver` / `ConnectWeaver`），意味着**任何**触达其类初始化的路径都会提前要求 ASM 可解析，包括只想知道「这个名字在不在名单里」的纯查询。
- `install()` 的幂等标记 `sqlvis.agent.active` 写在 `retransformLoadedDrivers` **之后**（`AgentMain.java:66-67`），一次失败不会留下任何「已尝试」痕迹。

### 3.3 为何用户 JDK8/JDK17 不复现

JRebel 的 reorder 介入强度与其自身版本、JDK 版本、native 初始化时序相关；用户是 Corretto 21.0.5 + 2026.3.0。本机不能复现归因于环境差异（无 JRebel 授权、其 2026.3.0 已是 native agent 形态），**但这不构成本插件无缺陷的证据**——§2.8 已证明「premain 外抛」本身就是致命机制，§2.9 又在 Corretto 21 + 真实 agent jar 上复现了同一条 NCDFE，premain 的兜底缺失在任何环境下都是缺陷，只是没被踩到。用户侧的注入方式与插件版本仍列为待验证前提 P-1 / P-2。

**本机 JRebel 复刻的实测限制（须如实记录）**：本机在役两份 JRebel（2026.3.2 / 2026.4.0）均为 native agent——`-javaagent:jrebel.jar` 只打印「改用 `-agentpath:jrebel64.dll`」并让 JVM 自行退出；`-agentpath` 形态与本插件叠加则**正常启动**（`matched=0` + `MAIN-OK`）。用户栈中的 `reorder.b.start → _jrLoadClassAndStartAgent` 属**纯 Java 转发**路径，本机版本到不了。对 JRebel jar 的字节码核查证实该路径由「检测到其他 agent」触发（`reorder.b` 含 `Forwarding agent: {}`、`hasOtherAgents`、`runEarlyAgents`、接口方法 `_jrLoadClassAndStartAgent(String,String,String)`）。**故「JRebel 那条具体路径」本机未复刻，只复刻了它的必要条件（asm 包不可见）。**

### 3.4 与 T-Jdk8RedisAgentStartupCrash 的关系

`20261015-T-Jdk8RedisAgentStartupCrash` 是**另一起事故**（JDK8 + Redis，用户只提供截图文字，无原始 hs_err）。本轮有**完整异常链与精确行号**，两者不可混为一谈、不可互相引用为证据。可复用的只有它沉淀的方法论（取证边界、四格对照、待验证前提表）。

## 四、修复方案

### 4.1 A 案（必做，止血）：premain 全域失败兜底 + 分阶段降级

改动集中在 `AgentMain`，不改织入器语义、不扩注入面。

1. **`install()` 整体包 try/catch(Throwable)**：premain 路径**任何**异常都不得外抛。
   - **必须捕获 `Throwable`，不能只捕 `LinkageError`/`NoClassDefFoundError`**——§2.11 的 `B3` 格实测：同一失败点在另一种加载器形态下抛的是 **`ClassCircularityError`**，二者只有共同父类 `Throwable`。
   - `FatalErrors` 语义对齐——`Error` 中的 `OutOfMemoryError` / `ThreadDeath` 一般不吞，但**这里的取舍是「宁可少采集也不能杀宿主」**：`-javaagent` 失败的后果远大于漏采集，故 **premain 一律吞并降级**，同时向 stderr 打一行结构化说明（复用 `[SqlVisAgent]` 前缀，禁含 SQL 原文/凭据/全路径）。
   - 降级后**不得**写 `sqlvis.agent.active`（否则 attach 会误判已装、永远不重试）；保持 `installed=false`。
   - 捕获范围要包住「类初始化」本身：异常可能来自 `StatementWeaver`/`ConnectWeaver` 的 `<clinit>`，此时**不要**再去碰同组的其他织入器类（避免二次 `ClassCircularityError`/`ExceptionInInitializerError`）。
2. **`retransformLoadedDrivers` 单类 try 内移**：把三连谓词调用也纳入 try，逐类失败只计数（现有 `failed++` 语义天然适配），不整体中断。**这是最小、最直接命中本次崩溃的改动。**
3. ~~**premain 阶段跳过 retransform**~~ —— **已实测证伪，本项撤销（见 §2.7）**。原本的推断是「premain 时几乎无已加载驱动，故这段高危调用收益低」；实测表明：**只要排在前面的 agent 在 premain 里加载过驱动，我们的 premain 就会命中**（`matched=1 wove=1`）。多 agent 场景（正是本次 JRebel 现场）恰恰是 premain 命中最多的场景，跳过它等于在该场景下丢采集能力。**结论：premain 的 retransform 必须保留，风险只能靠第 1、2 条的兜底来消除，不能靠删功能来消除。**
4. **`AgentMain` 静态块的 `catch (Throwable ignore)` 改为留痕**：至少记一行「DriverRegistry 预定义失败」，不再无声（对齐 T-AttachZeroEvent 的「不再无声」口径）。

### 4.2 B 案（结构性，建议同批）：切断「只查询名单」对 ASM 的类初始化依赖

`DriverRegistry` 的两个谓词只需知道「类名在不在表里」，却经 `StatementWeaver.specOf` 触发了 `StatementWeaver` 类初始化，从而要求 ASM 可解析。

- 把 `SPECS` 名单与 `Kind` 枚举**上提到独立类**（如 `WeaveSpecs`，零 ASM 依赖），`StatementWeaver` 与 `DriverRegistry` 均引用它；

**⚠️ P-3 已证伪，`implements Opcodes` 的移除建议撤销**（字节码实证，见 §2.9）：只去掉 `implements` **不能**解除对 `Opcodes` 的解析要求——`StatementWeaver` 方法体里有 6 处 `getstatic cn/song/.../asm/Opcodes.LONG|DOUBLE|TOP:Ljava/lang/Integer;`，这些是 **`Integer` 装箱静态字段**而非编译期内联的 `int` 常量，`invokestatic` 目标仍是该类。对比 `ConnectWeaver`：其 `Opcodes` 引用**只有类头一处**（int 常量全被内联），所以对 `ConnectWeaver` 去掉 `implements` 是有效的，对 `StatementWeaver` **无效**。

因此 B 案只剩「名单上提」这一半真正有效——它能让 `DriverRegistry.isStatementWeavable` 不再触发 `StatementWeaver` 类初始化，从而让 `premain` 的「只查名单」阶段不踩 ASM。`StatementWeaver` 自身在真正 `weave()` 时仍需 ASM，而那一层已在 transformer 的 try 内。

收益：即使未来 JRebel/其他 agent 再次干扰 ASM 加载，`premain` 的「名单查询」阶段也不会踩到；失败点后移到真正的 `weave()`，而 `weave()` 已在 transformer 的 try 内（`SqlHookTransformer.java:100-113`）。

### 4.3 C 案（体验，可后置）：插件侧感知 premain 失败并回落

- 现状：`PremainRunConfigurationExtension` 注入 `-javaagent` 后无回执校验；`AgentManager` 靠 `PREMAIN_SKIP_ATTACH_WINDOW_MS = 30_000` 窗口（`AgentManager.kt:112`）判定是否跳过 attach。
- 若 premain 崩溃，应用**根本起不来**，窗口逻辑无意义。真正的兜底应当是**用户可见的一次性提示**：告知「检测到第三方 agent（JRebel）与启动即捕获冲突，已自动降级为运行期 attach」。
- 但此项依赖 A 案（不崩才能降级），且需新增 UI 文案与 i18n，**列 P2 后置**，不在止血范围内。

### 4.4 明确不做

- **不**为 JRebel 做类白名单/黑名单硬编码（JRebel 版本与实现随时变，且本轮根因不在 JRebel 名单）；
- **不**在 premain 加 `System.setProperty` 之类的「猜测式」规避；
- **不**改动 `SqlHookTransformer` 的织入语义、不扩 JDBC 名单、不动协议与端口协商；
- **不**把「换 JDK」「关 JRebel」「关增强捕获」作为修复交付（那只是规避）。

## 五、验证方案

### 5.1 复现装置

**主仓内已落可重放探针**：`dev-support/agent-premain-crash-probe/`（含 `README.md`、三个 `-javaagent` 探针源与 `RealJarHarness.java`，对应 §2.5/§2.7/§2.8/§2.9 读数，不依赖 JRebel/IDE）。

**demo 仓独立复现子工程（已完成）**：`mybatis-plus-sql-visualizer-demo/agent-premain-hostile-loader-repro/`（提交 `e4342d1` + `c75736f`），一条命令 `powershell -NoProfile -ExecutionPolicy Bypass -File run.ps1` 跑完 8 格，读数见 §2.11。它是**修复验收的主装置**（含修前必红 / 修后转绿的 Repro C）。

demo 仓该子工程实际交付 8 格（均已在 Corretto 21.0.12.1 上通过）：

| 格 | 作用 |
|---|---|
| `recon0` | jar 条目完整性 + `StatementWeaver` 父接口 + 活引用/死条目区分 |
| `linkage` | 最小对照：证明「链接期要求全部引用类型可见，与被调方法无关」 |
| `reproA` A1/A2 | asm 可见→正常返回；asm 不可见→NCDFE 且四帧行号对上现场 |
| `hypothesis` | 造 `interfaces_count=0` 副本，实测删接口后仍同一行抛（证伪 P-3） |
| `B1` | 负对照：无第三方加载器时正常启动 |
| `B2` | 缺 `Opcodes` 的真实 jar + 真 `-javaagent` → 与用户日志逐字一致的全链 |
| `B3` | `-Djava.system.class.loader` 形态 → `ClassCircularityError`（第二种失败形态） |
| `reproC` | 修复验收：C-before 证明装置有鉴别力 / C-after 断言修复后不抛（现状红） |

### 5.2 修复侧验证

| 项 | 方法 | 判据 |
|---|---|---|
| premain 不外抛 | 主仓 `dev-support/agent-premain-crash-probe` 的 `RealJarHarness` + demo 仓 `reproC` | hostile loader 下 `premain` 返回，不抛 |
| **多失败形态覆盖** | demo 仓 `B2`（NCDFE）与 `B3`（`ClassCircularityError`）两格 | 两种形态下都降级不崩——**只捕 `LinkageError` 会漏掉 B3** |
| 单类失败不中断 | **`PremainSingleClassFailureTest`**（agent 侧新增）：桩对指定类抛 `UnmodifiableClassException` | 2 例绿——「单类retransform失败不中断其余类」（`failed=1`、失败类名随行、其余类仍被 retransform、premain 不外抛）+ 负对照「无失败时 failed 为零且全部织入」。**反向验证**：把 catch 改成外抛（单类失败中止整轮）→ 恰 1 例红（`成功调用数=0`），负对照仍绿 |
| **反向验证（必做）** | 临时摘掉 §4.1 第 1 或第 2 条修复 | Repro C **必须变红**，贴两次读数 |
| 不误杀 attach | `AttachGateTest` / `AttachPoolWarmGateTest` | 原读数不变 |
| 降级后仍可 attach | **`AgentManagerAttachAfterDegradeTest`**（主仓新增）：造 premain 降级态后走 attach 主路径 | 5 例绿——降级后 attach 成功建会话（会话入表、pid 对、在线态 true）、会话读循环确实消费 HELLO、降级提示不阻断 attach 调用、通道仍在表内也不阻 attach、**真实入口 `onProcessAppeared` 降级后仍能挂载**；另「不写 active」由 `PremainFailsafeBehaviorTest.降级后不得写active标记` 覆盖。**反向验证**：把判定改成 `if (detectPremainDegraded()) return` → 恰 1 例红（真实入口那条），其余 4 例仍绿 |
| 正常路径零劣化 | `ConnectWeaveEndToEndTest` / `StatementWeaveEndToEndTest` / `StatementWeaverVendorCoverageTest` | 全绿，织入计数不变 |
| JDK8 目标兼容 | `h2jdk8` 子 JVM 门禁 | 不因新 try/catch 或结构调整而漂移 |

**读数纪律**：只跑本任务最相关的定向测试（`agent` 子项目测试类 + 主仓 log/agent 包定向）；全量 `testShards --no-configuration-cache` 由独立只读收口代理跑一次。**禁止**用「合成 ASM 测试绿」或「换 JDK 暂时不崩」宣告解决。

## 六、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪方法 | 若不成立的后果 |
|---|---|---|---|
| P-1 | 用户现场是 **premain（`-javaagent`）**注入，非 attach | 取用户运行配置的 VM options 与应用 PID 命令行；`-javaagent:` 是否在 | 若是 attach，则崩溃应为 `agentmain` 抛异常、**不该杀宿主**——那么用户看到 FATAL 说明另有链路，方案需重估。**已部分闭环**：§2.8 证明 premain 外抛必然产生与用户一致的三行 `JPLISAgent` 断言 + `processJavaStart failed`，且用户栈含 `loadClassAndCallPremain`，故现场确为 premain 路径 |
| P-2 | ~~用户插件版本内含的 agent jar 与 2026.10.23-beta.1 同形~~ | **已闭环（2026-10-22，用户告知版本为 `2026.10.16`）**：核 `build/distributions/mybatis-plus-sql-visualizer-2026.10.16.zip` → `lib/mybatis-plus-sql-visualizer-2026.10.16.jar` → `agent/mps-sql-agent.jar`，**137 条目、`Opcodes` 在位、无 `WeaveSpecs`** | **结论**：产物完整 ⇒ 确认是加载期可见性问题而非打包缺失；该版不含本次修复，用户须升级 |
| P-3 | ~~`implements Opcodes` 是 `StatementWeaver` 唯一的 ASM 解析前置~~ | **已字节码 + 运行时双重证伪（§2.10/§2.11）**：`StatementWeaver` 有 6 处 `getstatic Opcodes.LONG/DOUBLE/TOP:Ljava/lang/Integer;`（装箱字段，不可内联）；且实际造出 `interfaces_count=0` 的副本后**仍在同一行抛**，只换了被拒类名 | **结论已回写**：删 `implements` 对 `StatementWeaver` 连「后移」都做不到；B 案只剩「名单上提」有效 |
| P-4 | ~~premain 时刻 `matched` 恒为 0 或极小~~ | **已实测证伪（§2.7）**：- 前置 agent 预热驱动后 `matched=1 wove=1`；单 agent 时 `matched=0` | **结论已回写**：premain 的 retransform 必须保留，§4.1 第 3 条已撤销 |
| P-5 | hostile ClassLoader 等价形态与 JRebel 真实行为一致 | 对比用户栈首帧位置与 §2.9 的栈顶帧；**本机已实测：两份在役 JRebel 均为 native agent，走不到 `reorder` 纯 Java 转发路径（§3.3）** | **已部分证伪**：等价形态复刻的是「asm 不可见」这一必要条件，**不是** JRebel 本身。不得据此宣称「已完整复刻用户场景」 |

## 七、实施顺序与边界

1. **S0 取证**：确认 P-1 / P-2（用户侧产物与注入方式）。**P-1 不成立则本计划的 A 案前提作废，先回写计划再实施。**
2. **S1 止血**：§4.1 第 1、2 条（premain 全域兜底 + 单类 try 内移）——最小改动、命中根因。
3. **S2 可观测**：§4.1 第 4 条（静态块留痕）。原第 3 条「premain 跳过 retransform」已因 P-4 实测证伪而撤销（§2.7），不在实施范围。
4. **S3 结构**：B 案（名单与 ASM 解耦），先证伪 P-3。
5. **S4 体验**：C 案（失败可见 + 回落提示），P2 后置。
6. **收口**：定向测试 → 独立只读收口代理跑一次全量 → 反向验证读数留档 → 计划 §实施记录回填 → 提交（中文消息、`-F` 文件）。

**停手边界**：未取得 P-1/P-2 证据前，S1 可按「防御性修复」先行（其正确性不依赖现场归因），但**不得**宣称「已修复用户问题」；CHANGELOG 措辞只能写「增强捕获在 premain 阶段对第三方 agent 干扰做失败降级，避免目标 JVM 启动失败」。

## 八、风险与已知代价

| 风险 | 说明 | 处置 |
|---|---|---|
| 吞异常掩盖真问题 | premain 全 catch 后，真实缺陷可能被静默 | 强制留痕（stderr 一行 + 计数），并保证降级态**不写** active 标记，attach 仍可达 |
| premain 不 retransform 的遗漏 | ~~极少数 premain 时已加载的驱动不织入~~ 该项已撤销（§2.7 实测 premain 会命中） | 不适用 |
| 与 JRebel 的其他交互 | 本次只修「启动失败」，热重载期的字节码冲突未验 | 明确记为未验证面，不宣称兼容 JRebel |
| 用户版本不明 | 用户加载的插件版本未确认 | P-2 前置；未确认前不写「该版本已修复」 |

## 九、附：本轮未做的事

- 未取得用户插件版本号、运行配置 VM options、`idea.log`；
- 未复现用户原始组合（Corretto 21.0.5 + JRebel 2026.3.0）——本机为 Corretto **21.0.12.1**（同主版本不同补丁）+ OpenJDK 21.0.11；本机 JRebel 为 2026.3.2/2026.4.0 且均为 native agent，走不到用户栈里的 `reorder` 纯 Java 转发路径（§3.3）。**已复刻的是死因链的必要条件**（§2.8 的致命性 + §2.9 的真实 jar NCDFE），不是 JRebel 那条路径本身；
- 未修改任何生产代码、未跑测试、未提交；
- 未预占 O/R/D/V 编号（修复落地时再按台账取号）。

---

## 十、实施记录（2026-10-22，A/B/C 三案全部落地）

### 10.1 改动清单

| 案 | 文件 | 改动 |
|---|---|---|
| A-1 | `agent/AgentMain.java` | `install` 拆为 `install`（兜底）+ `installOrThrow`（原逻辑）：`catch (Throwable)` 一律不外抛；降级走 `degrade()`，**不写** `ACTIVE_MARKER`、不置 `installed=true`、不触碰任何织入器/注册表类 |
| A-2 | `agent/AgentMain.java` | `retransformLoadedDrivers` 的三连谓词移入单类 `try`；新增 `predicateFailed` 计数与 `[predicate: ...]` 读数，与 retransform 失败区分 |
| A-4 | `agent/AgentMain.java` | 静态块 `catch (Throwable ignore) {}` → 留一行 `preload DriverRegistry failed: <类型>: <消息>` |
| B | `agent/WeaveSpecs.java`（新）+ `StatementWeaver.java` + `DriverRegistry.java` | 登记表与形态标注从 `StatementWeaver` 上提为**零 ASM 依赖**的独立类；两面谓词改为委托 `WeaveSpecs.specOf`，`StatementWeaver.specOf` 保留为委托转发（兼容既有调用面） |
| C | `AgentManager.kt` + 两个 bundle | 新增 `detectPremainDegraded()`：通道过 `PREMAIN_SKIP_ATTACH_WINDOW_MS` 仍未认领 ⇒ 判降级，**每项目会话一次** WARNING 提示；接在 `doAttachAsync` 的 premain 跳过分支之后，**不阻断** attach 回落 |

未采纳项：§4.2 的「去掉 `implements Opcodes`」——字节码实证（§2.10）与其后 demo 仓的运行时复核（§2.11）共同证明它对 `StatementWeaver` 连「后移失败点」都做不到，只是换了个被拒的 ASM 类名。B 案因此只保留「名单上提」这一半。

### 10.2 新增守卫测试

| 测试 | 例数 | 作用 |
|---|---|---|
| `PremainFailsafeGuardTest`（agent） | 6 | 源码结构：兜底必须 `catch (Throwable)`、降级不碰织入器、不写 active、谓词在 try 内、静态块留痕、名单不得走 `StatementWeaver.specOf` |
| `PremainFailsafeBehaviorTest`（agent） | 6 | 真跑 `premain` + 敌意加载器：ASM 不可见时**照常工作不降级**（B 案）、必需类不可见时降级不抛（A 案）、不写 active、`ClassCircularityError` 形态同样兜住、正对照 |
| `AgentManagerPremainDegradedTest`（主仓） | 5 | C 案：窗口内不误判、过窗判定、每会话一次、不随运行结束复位、通知异常不打断链路 |

### 10.3 测试读数

| 项 | 读数 |
|---|---|
| agent 子项目全量 | **176 / 0 / 0 / 0**（32 类） |
| 主仓 `log.agent` 包定向 | 全绿（含更新后的 `StatementWeaveWiringGuardTest`） |
| 主仓 `testFast` 宽面回归 | BUILD SUCCESSFUL |
| 新增守卫用例 | 12 + 5 例全绿，**已从 XML 核实非空跑** |

### 10.4 反向验证（四组，均贴两次读数）

| # | 摘掉的修复 | 变红读数 |
|---|---|---|
| 1 | `install` 的 Throwable 兜底 | `PremainFailsafeBehaviorTest` **恰 2 例红**（必需类不可见 / 类初始化环），其余 4 例仍绿 |
| 2 | `DriverRegistry` 退回 `StatementWeaver.specOf` | **恰 2 例红**：源码守卫 `名单查询不得触发StatementWeaver类初始化` + 行为 `asm不可见时premain照常工作不降级` |
| 3 | 三连谓词挪回 try 外 | **恰 1 例红**：`三连谓词必须在单类try内` |
| 4 | C 案一次性标记 | **恰 2 例红**：每会话一次 / 不随运行结束复位 |

四次摘除后均已恢复，工作树确认无 `RED PROBE` 残留。

### 10.5 demo 仓终端验收（`agent-premain-hostile-loader-repro`，提交 `8c5784a`）

8 格全部符合预期、退出码 0。**修复前后对照（同一装置、同一 JDK）：**

| 格 | 修复前 | 修复后 |
|---|---|---|
| `reproA` A2（ASM 不可见） | 抛 `NoClassDefFoundError ...asm/Opcodes`，栈顶 `isStatementWeavable:61` | **premain 正常返回**，且 `retransformCalls>=1`（谓词仍能判定） |
| `hypothesis` H1（删 interfaces） | 仍在同一行抛，只换了被拒类名 | **正常返回**（H0 仍保留「删接口无法消除 Opcodes 字段依赖」） |
| `reproB2`（jar 真缺 `Opcodes`） | `FATAL ERROR ... processJavaStart failed`，进程起不来 | `matched=0` + **`exiting normally`** |
| `reproB3`（`-Djava.system.class.loader`） | `ClassCircularityError: StatementWeaver` + FATAL | **`predicateFailed=949`** + **`exiting normally`** |
| `reproC` | `KNOWN-FAIL(expected)`，`failedChecks=2` | **`RESULT[reproC] = PASS`** |

`reproB3` 的 `predicateFailed=949` 是 A-2 逐类兜底正在工作的直接证据：949 次谓词失败被逐个拦下、不中断整轮、不中止宿主。

### 10.6 实施期新增的两条平台/工程事实

1. **PowerShell 5.1 读 `.ps1` 按 BOM 判编码**：用 Python 以无 BOM 的 UTF-8 写回含中文的脚本，PowerShell 会按 GBK 解析并报一连串「字符串缺少终止符 / 数组索引无效」语法错。写回必须保留 UTF-8 BOM（`encoding='utf-8-sig'`）。
2. **复现脚本的 jar 缓存会制造假读数**：`run.ps1` 里 `if (-not (Test-Path $noOpcodesJar))` 命中旧缓存时不会按新 jar 重新生成，于是「修复后」仍跑到旧产物上、读出旧结论。改断言后必须**先清 `build/tmp`** 再跑。

### 10.7 仍未做

- 未在真实 IDE 工程 + 真实 JRebel 端到端验收（本机 JRebel 为 native agent，见 §3.3）；
- 未取得用户插件版本与 VM options（P-1/P-2 仍未闭环，故 CHANGELOG 未写「该版本已修复」）；
- 未发版、未推送。

### 10.8 收尾门禁读数（已完成，2026-10-22 10:00）

全量 `testShards --no-configuration-cache`：**BUILD SUCCESSFUL in 18m 49s，退出码 0**。

| 分片 | tests | skipped | failures | errors | 耗时 |
|---|---|---|---|---|---|
| testShard1 | 1728 | 0 | 0 | 0 | 204s |
| testShard2 | 1538 | 0 | 0 | 0 | 198s |
| testShard3 | 1749 | 2 | 0 | 0 | 177s |
| testShard4 | 1433 | 0 | 0 | 0 | 182s |
| testShard5 | 1632 | 6 | 0 | 0 | 194s |
| **五片合计** | **8080** | **8** | **0** | **0** | 954s |

另：`testExternal` 31/0/0/1、`testDbPreviewSideQuery` 1/0/0/0。分片仍为串行（已知事实：分片任务同注册在 root project，`org.gradle.parallel` 不并行同项目任务）。

**一个读数陷阱（已排除）**：`build/test-results/test/` 下残留着 09:33 的 `AgentManagerPremainDegradedTest` 旧 XML，含 2 个 failure——那是反向验证第 4 组（故意摘掉一次性标记）留下的快照，**不是门禁结果**。门禁（09:48 起）实际在 `testShard3` 重跑了该类，读数 **5/0/0/0 全绿**，含 `test运行结束后不复位降级提示标记`。凡按 `test-results/**` 汇总全量时，须按目录区分「门禁分片」与「定向运行遗迹」，否则会把历史红算进终局。

### 10.9 §5.2 两项弱覆盖的补齐过程与一个方法论收获

两项原本只是表格里的「方法/判据」描述，没有对应用例；本轮补成可执行断言后，**反向验证又暴露出一次真实的方法学漏洞**：

第一版「降级后仍可 attach」的 4 个用例全部直调 `attachToPid`，而降级判定 `detectPremainDegraded()` 接在 `doAttachAsync` 里。于是把判定改成「降级就 return、吞掉 attach」这一缺陷形态塞进代码后，**4 例依然全绿**——用例绕过了被改的那一行。补一条走真实入口 `onProcessAppeared` 的用例后，同一探针立刻变红（`expected:1 but was:0`）。

教训：**直调内部方法做断言，测不到入口处的归一化逻辑**。凡是「在某入口插入了一段判断」的改动，反向验证必须从那个入口进，否则绿灯不代表覆盖。

### 10.10 实施期第三个坑：项目级服务的可变缝必须成对还原

补 §5.2 用例时新增的 `AgentManagerAttachAfterDegradeTest` 为驱动真实入口，在 `setUp` 里改写了三个可变缝：`attachTaskDispatcher`（改同步执行）、`attachDebounceMs`（归零）、`attachTargetInspector`（放行），但 `tearDown` 只还原了 `attachSessionFactory`。

`AgentManager` 是**项目级服务**，实例跨测试类复用，于是「同步派发 + 零去抖」泄漏到后续用例，导致 4 个既有断言误红：

- `AgentManagerPremainTest.test跳过窗失效后_attach恢复排队`（窗口外 attach 应恢复排队）
- `AgentManagerFallbackTest.test旧未确认态不再拦挂载_直接入队`
- `AgentManagerPremainReattachTest.test_pid未命中暂停通道_照常走attach排队`
- `AgentManagerAttachAfterDegradeTest.test真实入口_降级后onProcessAppeared仍能挂载`（自身也受影响）

这 4 条的共同点是「断言某个 attach 排队**发生了**」——被零去抖/同步派发改写后，`pendingPids` 的时序前提不再成立。修法是把三个缝在 `tearDown` 全部还原。

**注意区分**：这不是生产缺陷，是我新用例的状态污染。严禁改断言迁就；正确做法是还原缝隙后重跑，确认 4 条恢复绿。

附带一条命令层面的坑：`./gradlew :sql-visualizer-agent:test test --tests "cn.song...log.agent.*"` 会让 `--tests` 过滤同时作用到 agent 子项目任务，导致其用例被全部过滤掉（该模块读数为 0 类），而构建仍成功。**两个模块的定向测试必须分开跑。**

### 10.11 实施期第四个坑：新增用例不得破坏既有守卫的前置条件

`PremainSingleClassFailureTest` 首版用 `Class.forName("org.h2.jdbc.JdbcPreparedStatement")` 取类来做「命中名单」的桩，结果把该类**装载进了测试 JVM 的系统加载器**。而 `ZeroLoadClassWriterWeaveTest` 正是以 `findLoadedClass` 断言 H2 类尚未预载（零类加载红线，P0 机制）：

```
PS面织入_JdbcPreparedStatement零类加载 FAILED
prepare面织入_JdbcConnection零类加载   FAILED
断言：夹具前提/红线：org.h2.jdbc.JdbcPreparedStatement 已被测试 JVM 装载——增量判定失去鉴别力
```

修法：改为在**独立 `URLClassLoader`**（只含 H2 code source）里 `Class.forName(name, false, loader)` 取同名类。类名仍逐字命中登记表（谓词只按名字判定），但系统加载器的 `findLoadedClass` 保持 null，守卫的鉴别力完全保留。

这与 §10.10 是同一类错误的两个面：**新增测试对共享状态（项目级服务字段 / 类加载器状态）的副作用，必须限定在自己的作用域内**。两处都是我新用例造成、既有守卫正确报警，改的都是新用例而非既有断言。

### 10.12 用户日常 IDE 真机验收（2026-10-22）

用户已把 **2026.10.23-beta.1** 装进日常 IDEA（`~/AppData/Roaming/JetBrains/IntelliJIdea2026.2/plugins/mybatis-plus-sql-visualizer`，lib 下 jar 时间戳 10:28）。核实安装产物确含本次修复：

| 检查项 | 旧版 2026.10.16 | 已装 2026.10.23-beta.1 |
|---|---|---|
| 内嵌 agent 条目数 | 137 | **138** |
| `asm/Opcodes.class` | 在位 | 在位 |
| `WeaveSpecs.class`（本次修复标志） | **无** | **有** |

真机对照（Corretto 21.0.12.1，把已装 jar 内嵌 agent 释放出来直接 `-javaagent`）：

| 组 | 产物 | 结果 |
|---|---|---|
| A | 已装 agent，完整 | `matched=0` + 应用正常启动 |
| B | 已装 agent **删掉 `asm/Opcodes`**（等价现场 ASM 不可见） | **应用正常启动**，`matched=0`，无 FATAL |
| C（负对照） | 旧版 2026.10.16 agent 同样删掉 `Opcodes` | **`NoClassDefFoundError: ...asm/Opcodes` @ `DriverRegistry.isStatementWeavable:61` → `FATAL ERROR ... processJavaStart failed`** |

C 组与用户报障日志逐字同形，B 组同一缺陷形态下正常启动——修复在**用户已安装的真实产物**上成立。

**给用户的操作口径**：`ReproMain#main` 不能直接 Run（需 `格名 + agentJar` 两个参数，缺参即打印 usage）；它是复现装置（模拟敌意加载器），不是真机验收入口。真机验收应直接在日常 IDEA 里用**开启增强捕获 + JRebel 启动器**跑真实应用。

### 10.13 用户日常 IDEA 成功启动（2026-10-22 10:43）与决定性对照

用户在 IDEA 里带 **JRebel 2026.4.0（`-agentpath:jrebel64.dll`）+ 调试器 + async-profiler + Cool Request 两个 agent** 启动 `mybatis-plus-demo`（JDK 8），结果：

```
[SqlVisAgent] attach retransform: matched=0 wove=0 ...
Tomcat started on port(s): 8081 (http)
Started MybatisPlusDemoApplication in 10.859 seconds
```

**应用正常启动，无 FATAL。** 所用 agent 经核实为修复版：`~/AppData/Roaming/JetBrains/IntelliJIdea2026.2/plugins/mpsv-agent/mps-sql-agent-783177132.jar`，**138 条目、含 `WeaveSpecs`**。

**但 `matched=0` 意味着这次没走到崩溃点**——premain 时 `getAllLoadedClasses()` 里还没有语句面类，三连谓词未被调用。仅凭该次成功不足以证明修复命中用户路径，故追加决定性对照（Corretto 21.0.12.1）：

前置 agent 在 premain 里 `Class.forName("com.mysql.cj.jdbc.StatementImpl")` 预热语句类（复刻「premain 时语句类已加载」），然后再挂本插件 agent：

| 组 | 产物 | 读数 |
|---|---|---|
| 决定组 | **已装修复版** agent + jar 内删掉 `asm/Opcodes` | **`matched=1 wove=1 ... transformFailed=1` + `[App] OK`，正常启动** |
| 负对照 | **旧版 2026.10.16** agent + 同样删掉 `Opcodes` | `NoClassDefFoundError: ...asm/Opcodes` @ `DriverRegistry.isStatementWeavable:61` → **`FATAL ERROR ... processJavaStart failed`** |

两组的唯一差别是 agent 版本，其余（前置预载、缺失类、JDK）完全相同。`matched=1` 证明循环确实进入并走到了用户崩溃的那一行；决定组在同一行失败后仍完成启动（`transformFailed=1` 即该次织入被兜住），负对照则致命。

**结论**：修复在「用户已安装的真实产物」+「故障点确实被触达」的条件下成立。用户现场未崩的直接原因可能是其 premain 时刻恰好无语句类命中（`matched=0`），但一旦命中即会崩——这正是本次修复要消除的风险面。
