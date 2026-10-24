# agent premain 崩溃探针（T-JRebelPremainCrash 配套）

本目录是计划 `docs/开发计划/20261022-0905-T-JRebelPremainCrash-…-开发计划.md` §2.5/§2.7/§2.8 三个读数的**可重放装置**。
它不依赖 JRebel、不依赖 IDE、不依赖项目依赖，只需要一个 JDK 17+（本机实测用 `~/.jdks/ms-21.0.11`）。

## 三个探针

| 文件 | 作用 | 对应计划节 |
|---|---|---|
| `BadAgent.java` | premain 里直接抛 `LinkageError("cn/song/.../asm/Opcodes")`，**不兜底** | §2.8 ① 复现 |
| `SafeAgent.java` | 同一份必抛的 `install`，外面包 try/catch 只打一行降级 | §2.8 ② 修复形态 |
| `PreAgent.java` | premain 里 `Class.forName("org.h2.Driver")`，用于证伪「premain 命中恒为 0」 | §2.7 |
| `RealJarHarness.java` | **用真实 `mps-sql-agent.jar` + 隐藏 asm 包的 ClassLoader 复现同一条 NCDFE** | §2.9 |
| `StatementImplStub.java` | 让 `getAllLoadedClasses()` 返回一个语句面登记类，使循环走到 `DriverRegistry:61` | §2.9 |

## 重放命令

```powershell
$J = "$env:USERPROFILE\.jdks\ms-21.0.11\bin"
$AGENT = "F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\sub-projects\sql-visualizer-agent\build\dist\mps-sql-agent.jar"
$H2 = "<h2-2.3.232.jar 的绝对路径>"
$P  = $PSScriptRoot

& "$J\javac" -d "$P\out\main" "$P\Main.java"
& "$J\javac" -d "$P\out\bad"  "$P\BadAgent.java";  & "$J\jar" cfm "$P\out\bad.jar"  "$P\BadAgent.mf"  -C "$P\out\bad"  .
& "$J\javac" -d "$P\out\safe" "$P\SafeAgent.java"; & "$J\jar" cfm "$P\out\safe.jar" "$P\SafeAgent.mf" -C "$P\out\safe" .
& "$J\javac" -d "$P\out\pre"  "$P\PreAgent.java";  & "$J\jar" cfm "$P\out\pre.jar"  "$P\PreAgent.mf"  -C "$P\out\pre"  .

# ① 复现：必崩（FATAL ERROR in native method: processing of -javaagent failed）
& "$J\java" "-javaagent:$P\out\bad.jar" -cp "$P\out\main" Main

# ② 修复形态：正常启动，只多一行 degraded
& "$J\java" "-javaagent:$P\out\safe.jar" -cp "$P\out\main" Main

# ③ premain 命中不是恒 0：预期 matched=1 wove=1
& "$J\java" "-javaagent:$P\out\pre.jar" "-javaagent:$AGENT=port=47911" -cp "$P\out\main;$H2" Main
```

## 真实 agent jar 复现（§2.9，推荐优先看这条）

不依赖 JRebel，用**真实的 `mps-sql-agent.jar`** 复现用户那条 NCDFE。`RealJarHarness` 用一个只对 `cn.song.mybatisplus.sqlvis.agent.asm.*` 抛 `ClassNotFoundException` 的 `URLClassLoader`，模拟 JRebel 接管 premain 类加载后重定位 ASM 不可见；`Instrumentation` 用动态代理桩。

```powershell
& "$J\javac" -d "$P\out\rj" "$P\StatementImplStub.java"
& "$J\javac" -d "$P\out\rj" -cp "$P\out\rj" "$P\RealJarHarness.java"

# 对照：asm 正常可见 → premain 正常返回（matched=1 wove=1）
& "$J\java" -cp "$P\out\rj" RealJarHarness "$AGENT" normal

# 复现：asm 不可见 → NoClassDefFoundError: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes
& "$J\java" -cp "$P\out\rj" RealJarHarness "$AGENT" hide
```

实测（Amazon Corretto 21.0.12.1）：

- `normal`：`AgentMain loaded by java.net.URLClassLoader` → `matched=1 wove=1` → `HARNESS: premain RETURNED NORMALLY`
- `hide`：`HARNESS: premain THREW java.lang.NoClassDefFoundError: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes`，cause 为 `ClassNotFoundException: cn.song.mybatisplus.sqlvis.agent.asm.Opcodes`

**注意**：`getAllLoadedClasses()` 必须返回至少一个语句面登记类（本装置用 `StatementImpl` 桩），否则循环不会走到 `DriverRegistry.isStatementWeavable`，会误得出「不会崩」的假绿。

## 实测读数（2026-10-22，OpenJDK 21.0.11 / Amazon Corretto 21.0.12.1）

- ① 抛出 `InvocationTargetException → LinkageError`，随后三行 `*** java.lang.instrument ASSERTION FAILED ***`（JPLISAgent.c 627 / 466 / 429）与 `FATAL ERROR in native method: processing of -javaagent failed, processJavaStart failed`——**与用户现场逐字一致**（用户侧仅多 JRebel 的 `_jrLoadClassAndStartAgent` / `reorder.b.start` 两帧，异常类型是其子类 `yo`）。
- ② `[ProbeAgent] premain degraded: java.lang.LinkageError: …` + `MAIN-OK`。
- ③ `PRE: loaded org.h2.Driver` + `[SqlVisAgent] attach retransform: matched=1 wove=1 …` + `MAIN-OK`；仅挂本插件 agent 时是 `matched=0`。
- 真实 jar 复现：见上一节，`hide` 变体抛出的缺失类与用户日志**逐字相同**。

## 关于 JRebel 版本的一个实测限制

本机在役的两份 JRebel（2026.3.2 / 2026.4.0）都已是 **native agent**：用 `-javaagent:jrebel.jar` 启动只会打印「请改用 `-agentpath:jrebel64.dll`」并退出（JVM 自行结束），`-agentpath` 形态下与本插件叠加则正常启动（`matched=0` + `MAIN-OK`）。而用户栈里的 `com.zeroturnaround.jrebelbase.reorder.b.start` → `_jrLoadClassAndStartAgent` 属**纯 Java 转发**路径，本机版本走不到。

对 JRebel jar 字节码的核查证实该路径存在且由「检测到其他 agent」触发：`reorder.b` 含字符串 `Forwarding agent: {}`、`hasOtherAgents`、`runEarlyAgents` 与接口方法 `_jrLoadClassAndStartAgent(String,String,String)`。**故本机无法用 `-javaagent` 复刻用户那条 JRebel 路径**；需求侧的等价复刻由 `RealJarHarness` 承担（它复刻的是「asm 包不可见」这一必要条件，不是 JRebel 本身）。

## 注意

- 探针的 `SafeAgent` 只打一行 stderr，**不是**生产实现形态；生产实现还须满足「降级时不写 `sqlvis.agent.active`」「结构化字段」「禁含 SQL 原文/凭据/全路径」（见计划 §4.1、§5.2）。
- 这些读数证明的是「premain 外抛必然致命」与「兜底可止血」，**不证明**用户 JRebel 环境里 ASM 不可见的确切机制——后者需 `dev-support` 外的 demo 仓 hostile-loader 复现装置（计划 §5.1）。
