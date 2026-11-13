# agent premain 致命性验收（T-JRebelPremainCrash 配套）

用**真 `-javaagent` + 真子进程**验收「第三方 agent 让重定位 ASM 不可见时，插件 premain 是否会让
目标 JVM 起不来」，并据此判断某个 agent jar 是否已含 T-JRebelPremainCrash 的修复。

## 一条命令

```powershell
./gradlew :sql-visualizer-agent:agentFatJar
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/agent-premain-javaagent-accept/run.ps1
```

默认取本仓构建产物 `sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar`——**发版前验收的就是它**。
退出码 `0` = 三格全部符合预期。

指定其他 jar（例如验收用户手上版本）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/agent-premain-javaagent-accept/run.ps1 `
  -AgentJar "C:\Users\YOU\AppData\Local\JetBrains\IntelliJIdea2026.2\plugins\mpsv-agent\mps-sql-agent-XXXX.jar"
```

可选参数：`-Jdk`（默认 `~/.jdks` 下带 21 的目录，优先 Corretto）、`-MysqlJar`（默认 `~/.m2` 下 8.x）、`-OutDir`（默认 `build/reports`）。

## 与 `dev-support/agent-premain-crash-probe/` 的分工

| 目录 | 手段 | 回答什么问题 |
|---|---|---|
| `agent-premain-crash-probe/` | 敌意 `ClassLoader` + 反射调 `premain` | 哪一跳炸了？逐帧定位（§2.5/§2.7/§2.8/§2.9 读数出处） |
| **本目录** | 真 `-javaagent` + 真子进程 + jar 内删类 | **真实启动会不会死？** 发版前能不能过？ |

前者是可控实验，后者是真机形态；两个都要，不能互相替代。

## 三格判据

| 格 | 构造 | 修复版 | 未修复版 |
|---|---|---|---|
| 1 | 完整 agent + 预热语句类 | 正常启动 | 正常启动 |
| 2 | **缺 `asm/Opcodes`** + 预热语句类 | **正常启动** | **FATAL，起不来** |
| 3 | 缺 `asm/Opcodes` + **不预热** | 正常启动 | **FATAL，起不来** |

第 2 格是核心。删掉 `asm/Opcodes` 对 JVM 等价于「此加载器看不见它」（即用户现场中 JRebel
接管 premain 类加载后的形态），但不依赖 JRebel 授权、完全可重放。

第 2 格里修复版会打出 `transformFailed=1`——那次织入确实失败了，应用却仍活着，这就是兜底在起作用。

## 第 3 格固化的事实（曾误判）

> **`matched=0` 不等于谓词没被调用。**

`AgentMain.retransformLoadedDrivers` 对**每一个**已加载类依次调用
`isWeavable / isStatementWeavable / isPrepareWeavable`，`matched` 只在命中后才 +1。
所以只要三个谓词里任一次执行触发 `StatementWeaver` 类初始化，未修复版就崩——与「有没有类命中」无关。

早期以「不预热就走不到崩溃点」为判据，被这一格实测推翻（旧版 2026.10.16 在 `matched=0` 时照样 FATAL）。
**看到 `matched=0` 就认为安全是错误的。**

## 为什么需要预热

插件只在 `getAllLoadedClasses()` 里出现登记表内的语句/驱动类时才有实际意义；干净启动的小应用常常
`matched=0`。`WarmupAgent` 在自己的 premain 里 `Class.forName("com.mysql.cj.jdbc.StatementImpl")`
把目标类暖进 JVM，让后续插件的 premain 必然命中——第 1、2 格才具鉴别力。

预热失败（classpath 缺驱动）时，核心格会**判红而不是静默通过**，避免「没触发所以全绿」的假绿。

## 输出与判读

每格完整输出落在 `build/reports/<cell>.log`。关键行：

- `[SqlVisAgent] attach retransform: matched=N ... transformFailed=M`
- `[App] started, java=...` —— 应用活着（唯一真正要紧的信号）
- `Caused by: java.lang.NoClassDefFoundError: ...asm/Opcodes` —— 缺失类（未修复版会出现）
- `FATAL ERROR in native method: processing of -javaagent failed, processJavaStart failed`

脚本开头会打印 `agent 含 WeaveSpecs(修复标志) = true/false`，可直接确认 jar 是否含修复。

## 已知边界（不要当成已验证）

- 本装置复刻的是「ASM 在该加载器不可见」这一**必要条件**，**不是** JRebel
  `reorder.b.start → _jrLoadClassAndStartAgent` 那条转发路径本身（本机 JRebel 均为 native agent，
  走不到该路径）。
- 验收通过不等于「与任何第三方 agent 组合都无冲突」；热重载期的字节码交互未验。
- 需要 JDK 21 与 MySQL 8 驱动（预热目标 `com.mysql.cj.jdbc.*`；5.1.x 包名不同，会预热失败）。

## 编码注意

`run.ps1` 必须保存为 **UTF-8 with BOM**（Windows PowerShell 5.1 无 BOM 时按系统代码页解析，
中文会乱码并触发一串假语法错误）。脚本开头会把 `[Console]::OutputEncoding` 与 `$OutputEncoding`
钉成 UTF-8，否则 java 子进程写出的中文会显示为乱码。
