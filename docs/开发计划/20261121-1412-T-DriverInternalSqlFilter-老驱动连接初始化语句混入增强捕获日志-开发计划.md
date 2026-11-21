# T-DriverInternalSqlFilter：老驱动连接初始化语句混入增强捕获日志

> 来源：2026-11-21 沙盒实测（Jdk16Demo，mysql-connector 5.1.49，agent 已挂载 46 条事件）：SQL 日志头部被驱动的连接初始化语句占满——`/* mysql-connector-java-5.1.49 (Revision:...) */ SELECT @@session.auto_increment_increment...`、`SELECT @@session.autocommit;`、`SELECT @@session.transaction.read_only;`，带精确耗时与源码定位（栈落到 `SpringApplication.run` 那一帧）。业务 SQL 一条没跑，先看到的全是这些。用户判定「捕获的内容不对」。
>
> 附带澄清（不是本缺陷）：该运行配置实际跑在项目 SDK 1.8 上（Spring Boot 配置没选 JRE），所以「目标 JDK 过老」提示不出现是正确行为。

## 一、判据（闭集，每条带证据；扩展须先有实证再登记）

纯函数 `DriverInternalSql.isInternal(sql)`（零平台依赖，进 testFast 快桶）：

1. 归一化后以 `/* mysql-connector-java-` 开头（不区分大小写、允许前导空白）——Connector/J 5.0/5.1 在连接初始化查询前自钉版本注释，**沙盒实证**（2026-11-21 截图条目 #1）。
2. 归一化后以 `select @@session.` 或 `set @@session.` 开头——同驱动的系统变量族，无注释形态（**沙盒实证**条目 #2/#3）。

**刻意不做**的（防误伤，留证据扩展口）：
- `@@` 宽匹配：SQL Server 的 `SELECT @@IDENTITY` / `@@ROWCOUNT` 是真业务 SQL 常客，`@@` 家族只收 `@@session.` 带点形态；
- `SET NAMES` / `SET character_set_results` 等：本沙盒未见实证（46 条事件只核实了上述三形态），按「保守兜底须先量真实数据」教训不收，后续拿到实证再登记；
- 不在 agent 织入侧过滤：插件入口层过滤对**已挂载的存量 agent 立即生效**（用户应用不用重启），agent 侧过滤要把过滤代码送进宿主 JVM 才生效；事件流量开销可忽略；且 agent 继续全量上报，插件侧保留事后调整判据的余地。

## 二、落点

`AgentSessionSupport.handleFrame` 的 CMD_EVENT 分支：在 `markReceived()` 之后、「暂停闸门/入列表」之前插入——

```kotlin
if (DriverInternalSql.isInternal(event.sql)) { dropped 计数; return }
```

- 计数口径对齐既有「收到即计数」：被过滤的也算已收（诊断链不变，「面板空时 N>0 查消费侧」的分流仍然成立）；
- 丢弃静默 + 计数（`DriverInternalSql.droppedEvents()`，internal 测试缝），不加日志/不加诊断行——这是预期常态而非异常，刷日志违反「控制台安静」口径；
- 只作用 agent 通道入队点：MCP 审计与历史回读不经这里（与 T-AgentConfigChannel T-03 的闸门同边界）。

## 三、验收

1. 新 `DriverInternalSqlTest`（headless 表驱动）：三条实证形态全命中；负例——SQL Server `SELECT @@IDENTITY AS id`、`SET NAMES utf8mb4`、业务 `SELECT * FROM user WHERE ...`、空串/null 不命中；大小写与前导空白变体命中。
2. 端到端：`AgentManagerIntakeGateTest` 补两例（驱动内部帧不入列表且计数 +1；紧随其后的业务帧照常入队——防「一刀切把通道掐了」）。
3. 反向验证用 **git 补丁方式**：摘掉 handleFrame 的过滤分支生成补丁 → apply → 端到端用例必红 → `git checkout --` 还原 → 复跑绿。
4. `CHANGELOG.md` [Unreleased] Fixed 一条。

## 四、实施记录

| 项 | 状态 | 提交 | 读数 |
|---|---|---|---|
| 计划 | 已完成 2026-11-21 | — | — |
| 实施 | 已完成 2026-11-21 | `8ef85fe56` | `DriverInternalSqlTest` 5/0 + `AgentManagerIntakeGateTest` 23/0（补过滤不入队计数、紧随业务语句照常入队两例） |
| 反向（git 补丁） | 已完成 2026-11-21 | 补丁 `%TEMP%\mpsv-dif-reverse.patch` | apply 后 `AgentManagerIntakeGateTest` **2 红**（两个新端到端用例）→ `git checkout --` 还原 → 复跑 23/0 + 5/0；全程未手改代码 |

实施坑一条：**Kotlin 块注释嵌套**——KDoc 里写字面「斜杠星号」会开出一层未闭合嵌套注释，整文件编译失败且报错点漂移（`Unclosed comment`）。注释指纹字面量提为 `CRITERION_COMMENT` 常量，文档里只描述形态不写字面。
