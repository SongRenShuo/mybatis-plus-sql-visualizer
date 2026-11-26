---
name: project-agent-driver-init-statement-noise-20261121
description: 老驱动连接初始化语句混进增强捕获日志——T-DriverInternalSqlFilter 已实施推双仓（master b3900d236）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_86436c85-7dc4-4294-b1ee-bd34d6d893c6
---

2026-11-21 用户沙盒复验（jdk16-demo，Jdk16DemoApplication）报「捕获的内容不对」：SQL 日志头部被**驱动连接初始化内部语句**占满——`/* mysql-connector-java-5.1.49 (Revision:…) */ SELECT @@session.auto_increment_increment…`、`SELECT @@session.autocommit;`、`SELECT @@session.transaction.read_only;`，带精确耗时（33ms，无 `~` 前缀）与源码定位 `Jdk16DemoApplication.java:18`（`SpringApplication.run` 帧——第一个连接在那里创建）。**与 JDK 6 无关**：agent 真挂上了（「增强：已挂载 pid=92440｜已收事件 46 条」铁证），老驱动（mysql 5.1 系）+ 任何受支持目标 JVM 都复现。

**已实施（用户令「立项 在主分支直接实施」+「推」）**：计划 `008a024e2`、实施 `8ef85fe56`、回填 `b3900d236`（master 直做，无分支），双仓已推 `b3900d236`，**CI run 44875661666 success**（20261121）。落点＝插件入口层 `AgentSessionSupport.handleFrame` CMD_EVENT 分支（`markReceived` 之后、暂停闸门之前，静默丢弃 + `DriverInternalSql.droppedEvents()` 计数）——**对已挂载的存量 agent 立即生效，用户应用不用重启**；agent 继续全量上报。判据闭集两族（各带实证）：① 归一化后以驱动版本注释指纹开头（`CRITERION_COMMENT` 常量）；② `select/set @@session.` 前缀。**刻意不收**：`@@` 宽匹配（SQL Server `@@IDENTITY` 是真业务 SQL）、`SET NAMES` 等（无实证；扩展须先有实证再登记）。

定向：`DriverInternalSqlTest` 5/0 + `AgentManagerIntakeGateTest` 23/0（补过滤不入队计数、紧随业务语句照常入队两例——防「一刀切掐通道」）。反向（git 补丁方式，[[feedback-reverse-probe-via-git-patch]]）：摘过滤分支补丁 apply → 2 红 → `git checkout --` 还原 → 28/0。坑见 [[pitfall-kotlin-nested-block-comment-kdoc-literal-slash-star]]。

诊断口诀保留：判「agent 是否真挂上」看面板底部「增强：已挂载 pid=…｜已收事件 N 条」——JDK6 上 shim 跳过绝不会有会话；agent 级条目特征 = 无 `~` 前缀的精确耗时 + 源码定位。demo 事实面：jdk16-demo 在 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo/jdk16-demo`，pom `java.version=1.6` 只管 Maven toolchain **编译**；Spring Boot 运行配置未选 JRE → 落项目 SDK 1.8。真验 JDK 6 的配方（含 IDE 编译/启动两条硬限制）见 [[project-oldjvm-premain-guard-20261120]] 后续段。

**诊断坑两条（20261121 复验轮实证）**：① 面板「已收事件 N 条」是 **IDE 进程生命周期的累计静态计数**（`AgentEventAdapter.receivedCount`），跨运行残留——14:41 那次运行显示「已收事件 46 条」其实是 14:40 那次 JDK 8 运行收的，本次会话 0 事件；判「本次有没有事件」要结合会话级消息（如「本次 attach 命中 0 个驱动——增强捕获本会话无事件来源」）与会话 pid 是否等于当前进程。② 用户 IDE（2024.3 真机）里运行了**多个 IDE 实例**（2024.1/2024.3/2026.3 + 243 沙盒）——找日志先按 mtime + 插件关键词认准实例（2024.3 的在 `Local/JetBrains/IntelliJIdea2024.3/log/idea.log`），别把 2026.3 的 AI-agent 日志行误当插件；`premain 注入完成（config=…）：-javaagent:…` + `premain 会话建立：pid=…` 两条即可铁证闸门放行、目标 JVM ≥8。剩余用户侧验收：运行配置 JRE 选 **1.7**（IDE 启动最低版本、闸门同分支）+ 沙盒插件须含 `22f3d8945`/`b3900d236`。

相关：[[project-oldjvm-premain-guard-20261120]]（同轮复验的闸门线）。
