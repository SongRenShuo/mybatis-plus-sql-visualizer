---
name: frame-skip-prefix-demo-verification
description: 「源码捕获帧过滤前缀」的端到端验证载体在 demo 仓库 selfdev-framework-demo
  子项目；含两条实测口径（前缀须覆盖整条引擎链、业务包不可登记）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_50e4dff6-f8a0-472d-b3b7-2800b6d7c371
---

「源码捕获帧过滤前缀」（插件设置项 `sourceFrameSkipPrefixes`，2026-09-22 起）的端到端验证载体
在 **demo 仓库** `mybatis-plus-sql-visualizer-demo` 的 `selfdev-framework-demo` 子项目
（端口 8086 / 库 `selfdev_demo`，27 端点 + `verify-endpoints.sh` 冒烟），不在插件仓库内。

**Why**：该设置只对 agent 增强捕获链路（`StackLocator`）生效；IDE 侧 `CallerSourceLocator`
是独立硬编码名单、不消费用户前缀。故验证语料必须是「不经 MyBatis 的原生 JDBC + 引擎与业务同处
`target/classes` 目录」形态，普通 MyBatis demo 覆盖不到。

**How to apply**：需要回归该功能时到 demo 仓库跑该模块，而不是在插件仓库造语料。两条实测口径
（2026-09-22 插桩取证，非推断）：

1. **前缀必须覆盖整条引擎链**：普通查询登记引擎包一个前缀即可，但分页路径执行链最深层在
   `com.corp.meta.PaginSupport`，只登记 `com.corp.engine.` 仍落框架帧——需两行都填。
2. **切勿登记业务包**：业务帧被一并跳过后候选池被过滤为空，主位置判定失败、
   **跳转能力完全消失**（不是降级到更外层调用者）。

**2026-09-22 状态更正**：用户在真机登记前缀后跳转**仍落 `com.corp.engine.DmlRepository#query`**——不是语料/配置问题，
而是 agent 的 CONFIG 入站读顺序错位使前缀从未送达应用侧（见 [[pitfall-agent-config-channel-never-applied]]，
该轮已用真 `SenderLoop` 双字节序受控实验闭合）。**在插件修掉那一行之前，本模块的「登记前缀→落业务帧」链路无法在真机走通**；
模块本身仍是有效载体（帧落点结论由引擎侧同规则插桩独立取证，不依赖该通道）。

**交付形态**（`4473308`）：第六子项目，48 个源文件；模拟框架双包根 `com.corp.engine`（静态门面 `DataUpdateTool`／Bean 门面 `DataQueryTool`／链式 `DataQuery`／编排 `LocalDbService`／执行 `DmlRepository`／JDK 代理工厂）
+ `com.corp.meta`（条件树 `FilterExpression`+`Q`／渲染 `SqlRenderer`／元数据 `MetaRegistry`／分页 `PaginSupport`）；业务侧 4 组 Service（27 用例）+ 3 协作服务 + `support.BizQuerySupport` 负例 + 27 端点控制器 + `/selfdev/index` 场景索引。
**环境配置两个坑**（都实测踩过）：①`spring.sql.init` 不声明 `encoding: UTF-8` 时按平台默认（Windows=GBK）读脚本，中文种子数据全乱码；
②`spring.sql.init.mode: always` + `createDatabaseIfNotExist=true` 才能「起服务即可 curl」（本模块与兄弟模块手工跑脚本的做法不同，README 已标注）。
**MySQL 语法坑**：`DELETE` 不接受 `DELETE FROM tbl p` 形态的表别名（`SQLSyntaxErrorException near 'p WHERE'`），须写 `DELETE p FROM tbl p`（多表删除语法）；多表 JOIN 视图直接拒绝删除。

相关：[[project-pending-plans]]、[[reference-real-ide-debug-entry]]、[[pitfall-agent-config-channel-never-applied]]
