---
name: project-oldjvm-premain-guard-20261120
description: JDK1.6 目标开增强捕获导致项目无法启动的根因、复现与修法（T-OldJvmPremainGuard）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_86436c85-7dc4-4294-b1ee-bd34d6d893c6
---

2026-11-20 用户报障：JDK 1.6 项目开启增强捕获后项目无法启动，错误 `UnsupportedClassVersionError: cn/song/mybatisplus/sqlvis/agent/AgentMain : Unsupported major.minor version 52.0`。

根因：agent 产物 `options.release = 8`（major 52），JDK 6 只认到 major 50；manifest 的 Premain-Class/Agent-Class 同指 AgentMain。**premain 加载入口类失败对 JVM 是致命的**（`FATAL ERROR in native method: processing of -javaagent failed`，主类 main 未执行），而 attach 同失配只抛 `AgentInitializationException`、目标进程照常运行——致命面只有 premain 注入。注入链三道闸门（开关/jar/端口）没有 JDK 版本维度，`AgentMain` 内已有的 `catch (Throwable)` 在安装链内部，兜不住「入口类根本不加载」。

复现（真机已完成）：本机新增 **JDK 1.6.0_119（Azul Zulu 6.22.0.3）在 `D:\HuanJing\Java\jdk1.6.0_119`**，与 `jdk1.8.0_181` 同级；`java.class.version` 实测 50.0（JDK8 为 52.0）。真实 `mps-sql-agent.jar` 在 1.6 上 `-javaagent` → 逐字复现现场报文 + `FATAL ERROR in native method: processing of -javaagent failed`，主类未执行、EXIT=1；`loadAgent` 同 jar → 只抛 `AgentInitializationException`，宿主跑到结束。注意 JDK 6 的 javac 需 `-encoding UTF-8`（默认 GBK，中文注释报不可映射字符）。临时复现产物：`%TEMP%\mpsv-jdk6-repro\`（含原包与 sha256 `24059e89…c54ca`）。券02 引导类形态已用 JDK6 javac 原生编 major 50 验证：1.6 上跳过且应用正常起、1.8 上正常委托（`sqlvis.agent.active=port=1`）、内部 AgentMain 不可加载时也只打一行不杀宿主。

修法（计划 `docs/开发计划/20261120-1530-T-OldJvmPremainGuard-目标JDK1.6开启增强捕获致应用无法启动-开发计划.md`）：券01 注入前目标 JDK 闸门（纯函数 `PremainInjection.decide` 加 `targetJvmMaxMajor`，探测走 `params.getJdkPath()` → release / rt.jar major，一次性通知）；券02 入口换成 major≤50 的 `AgentShim`（`java.class.version` 判阈 + 反射调 AgentMain + 全段 catch），由 `RelocateAsmMain` 在打包时降版本，属结构性保证。**不要试图把 agent 主类降到 Java 6**（已用 LongSupplier/Optional 等 Java 8 API）。

**已实施并合流 2026-11-20**：分支 `t-oldjvm-premain-guard` 已合入 master（merge `b55d93046`，实施提交 `79cafe2a6`、回填 `0b3a0e98e`、清理补记 `6a4d808fd`），双仓已推（Gitee/GitHub 均 `b55d93046`，走项目令牌无弹窗），工作树与分支已删。合流后复跑：agent 20/0（守卫 4 + premain 14 + attach 2）、根 16/0、5/0、8/0；内容与分支逐字一致（唯一重叠面 CHANGELOG 自动合并）。定向：反向两探针（摘降版本→major 52 红、摘版本闸→端口被开红）均还原后绿。真机：JDK 1.6.0_119 上现为 `skipped` + 应用正常起 EXIT=0、JDK 8 `active=port=1`。**实施期实测推翻一个计划前提**：Oracle 系 JDK 6 的 `jre/lib/rt.jar` 全是 major **49**（按 1.5 编译），不是 50，故提示文案不报具体版本号（只报「低于 8」），闸门判定不受影响。发版前仍需补一次 `verifyPlugin`（动了 agent 打包步骤）。

相关：[[pitfall-l7-jvm-linkage-visibility]] 类教训的边界外延——上次防「类内部抛异常」，本次是「类不加载」。

**后续（2026-11-21）**：状态行补「目标 JDK 低于 8，已跳过注入（回落控制台捕获）」一态（T-AgentStatusOldJvm，实施 `05fd3dff1`、merge `22f3d8945`、双仓已推）——命中闸门粘性置位 `targetJvmSkipDetected`，agentStatusText 在「从未挂载」分支前改叙事； manager 层测试只钉事实标志（平台测试里状态行被同类回连用例粘性污染 + bundle 随 locale 变，文案断言留给纯函数测试）。反向验证本次起改用 git 补丁方式（[[feedback-reverse-probe-via-git-patch]]）：摘除补丁 apply → 2 红 → 还原 16/0，秒级。

**用户沙盒复验（2026-11-21）**：用 jdk16-demo 沙盒测「没看到提示」——判为**正确行为**：该运行配置是 Spring Boot 类型且未选 JRE，落在项目 SDK 1.8（demo 的 pom `java.version=1.6` 只管 Maven toolchain 编译，不管 IDE 运行 JVM），agent 正常挂载（底部「增强：已挂载 pid=92440｜已收事件 46 条」为铁证，JDK6 上 shim 跳过绝不会有会话）；要真验 JDK 6 须在运行配置里显式选 `D:/HuanJing/Java/jdk1.6.0_119`（Boot 1.5 基线 Java 7，起不来属宿主栈）。详见 [[project-agent-driver-init-statement-noise-20261121]]（同轮暴露的真缺陷）。

**JDK6 真机验证配方（2026-11-21 实测订正，收回「模块 SDK 改 1.6」的错误建议）**：现代 IDE（2020.1+/2026.2）构建系统**只支持 JDK 7+ 的 javac**——模块 SDK 一指到 1.6，Make 直接报「无法启动 javac 进程：它被配置为使用 JDK 6，但 IDE 仅支持使用 JDK 7 和更高版本进行编译」。正确配方三条链分开：① 模块 SDK 回 1.8，模块 Language level = 1.6 且 **Target bytecode version = 1.6**（关键：不设则 javac 8 默认 `-target 8` 出 major 52，真 6 上连自己的主类都加载不了；IDE 会报 bootstrap classpath 警告，无害；新版 IDEA 该下拉已从「依赖」页签挪到 编译器→Java 编译器→逐模块字节码版本表）；② 运行配置 JRE 显式选 jdk1.6.0_119；③ 备选 = `mvn package`（toolchain 真 JDK 6 javac）+ 命令行跑。宿主栈字节码实测全在 JDK 6 上限内：Boot 1.5.22 / Spring 4.3.25 / Tomcat 7.0.109 全 major **50**、Connector/J 5.1.49 = **49**——demo pom 已按官方 howto §73.16 做了 Java 6 附加配置（内嵌容器降 Tomcat 7 + 单独补 tomcat-juli），真 6 能跑。预期插件行为在 JVM 启动注入期就可见：气球提示 + 状态行「目标 JDK 低于 8，已跳过注入」，与应用本体是否起得来无关。

**JDK 7 复现与验证（2026-11-21 实测）**：① **IDE 启动进程最低 1.7**——运行配置 JRE 选 1.6 被启动器直接拒绝（「IDE 不支持使用 Java 6 启动 Java 进程，支持的最低版本为 1.7」），与编译限制（≥7 javac）是两条独立硬限 ⇒ **JDK 6 无法从 IDE 启动**，IDE 可验的最老形态 = JDK 7（major 51，与 6 走同一闸门分支、文案一字不差）；JDK 6 本体行为已于 2026-11-20 用真 1.6.0_119 验过。② **JDK 7 已装** `D:\HuanJing\Java\jdk1.7.0_352`（Zulu 7.56.0.11 / 7.0.352），有 `release` 文件且 rt.jar major 51——闸门两判据都命中 51。③ **JDK 7 上原故障 1:1 复现**：「旧入口」jar（manifest 指回 AgentMain，复刻修复前形态）在 7 上 `-javaagent` → `Unsupported major.minor version 52.0` + `FATAL ERROR in native method: processing of -javaagent failed` + 主类未执行 EXIT=1，报文与用户截图逐字一致（jar 存 `%TEMP%\mpsv-jdk7-repro\agent-old-entry.jar`）；同一 JDK 7 上修复后 jar → `[SqlVisAgent] skipped: jvm=1.7.0_352 (class version 51.0)…` + 应用正常起 EXIT=0。旁证：用户截图栈帧行号 `defineClassCond(631)/defineClass(615)` 更像 7u——报障的「1.6」可能实为 7。用户侧剩余验收＝运行配置 JRE 选 1.7 + 沙盒插件须含 `22f3d8945`（状态行态）与 `b3900d236`（驱动语句过滤）。

**端到端验收通过（2026-11-21 用户实机）**：用户运行配置 JRE 指向低于 8 的 JVM 后，一次性提示「目标应用的 JDK 版本低于 8，不支持增强捕获（需 JDK 8 及以上）。本次未注入 agent，项目可正常启动；SQL 仍可从控制台日志捕获」正常弹出——闸门→探测→跳过→提示全链在用户机器上走通。

**状态行优先级修（T-AgentStatusOldJvm2，2026-11-21 用户实机三轮）**：用户截图暴露 `everAttached`（项目级粘性）遮蔽本次跳过事实——先跑 JDK 8 挂上、再跑 JDK 7 被跳过时，状态行显示上一次会话的「连接中断，已回落控制台捕获」。修＝跳过态提到「曾挂载」分支之前（优先级=**本次挂载 > 本次跳过 > 上次会话结局**）+ 两处会话建立点（attachToPid / premain 回连）清 `targetJvmSkipDetected`；premain 建立点原注释「任何人重排分支就会露出来」应验，该清位由防御变必然可见。`95ecbea44` 已推双仓（CI run 44879253647 绿 @ `5ee69fab3`），用户三轮截图确认状态行正确。manager 层状态行文案断言两类不可靠再次应验：bundle 随 IDE locale 变、同类回连用例粘性污染 statusSummary——manager 层测试只钉事实标志（AtomicBoolean），文案映射留纯函数测试（zh 兜底环境）。

**多模块让位吞行另立案**：同轮沙盒实测暴露新缺陷——两模块并行只挂一个 agent 时，另一模块的控制台 SQL 被全局让位吞掉，见 [[project-multimodule-console-yield-20261121]]。

**全链闭环（2026-11-21）**：JDK 7 复现与修复读数补齐（见 [[reference-jdk16-demo-corpus-host]]）、用户实机验收通过（提示弹 + 状态行跳过态 + attach skipped 一行）、master tip `642bbdb71` CI run `44885561418` 全绿。本项无悬挂动作；发版前唯一挂账＝`verifyPlugin`（agent 打包步骤变更，[[project-multimodule-console-yield-20261121]] 同批口径）。

**验收判读订正（2026-11-21 用户实机二轮，订正上一段的判读①）**：用户随后在应用控制台贴出 `[SqlVisAgent] skipped: jvm=1.7.0_352 (class version 51.0) does not support Java 8+ bytecode; …`——此前判读「闸门命中 ⇒ 控制台不会有 skipped 行」**错了**：闸门只拦 premain，插件随后照常回落 **attach 兜底链**，`loadAgent` 把同一份 jar 推给目标 JVM → AgentShim（major 50）在老 JVM 上加载成功 → 读 `java.class.version=51.0` 判阈不够 → 打一行 stderr → 正常返回（不抛异常）→ IDE 等不到回连静默回落控制台桥。正确判读＝**应用 stderr 恰好出现一行 skipped（attach 兜底路径，进程生命周期一次，不刷屏）**，且这正是券02 结构性防线在「非 IDE 发起的 attach」形态上工作的实证——两层防线都被现场看见了。UI 上「已收事件 46 条」是 IDE 进程内累计计数（早前 JDK 8 运行收的），不是本次；「本次 attach 命中 0 个驱动」+ 列表空属预期（无 agent 通道、无业务调用）。
