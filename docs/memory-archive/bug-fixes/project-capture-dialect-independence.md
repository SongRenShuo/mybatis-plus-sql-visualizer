---
name: project-capture-dialect-independence
description: 采集能力与方言解耦（20261030 已立项 T-CaptureCatchAll / O-155）——两条链方言依赖度、catch-all 走「白名单优先+接口兜底」的定案、兜底面刻意不含 ADD_BATCH 的安全取舍、公开仓与内部库分仓规则
metadata:
  node_type: memory
  type: project
  originSessionId: session_75f77c740f8e46a14f3685d1bdad249e
---

# 采集能力与方言解耦（20261030 已立项实施）

起因见 [[feedback-capture-should-be-dialect-agnostic]]：用户要求「所有 SQL 都应可捕获」，不许被插件的 14 方言注册表限制。

## 关键结构事实：两条采集链，方言依赖度完全不同

| 链 | 是否依赖方言注册表 | 说明 |
|---|---|---|
| **控制台日志解析**（`MyBatisLogParser` / `HibernateLogParser` / `JdbcTemplateLogParser`） | **不依赖** | 只吃行文本、全程不读 `DialectRegistry` ⇒ 对「插件不支持的方言」仍能兜底 |
| **增强捕获**（`DriverRegistry` + `WeaveSpecs`） | **完全依赖** | 类名硬编码白名单；未命中即**静默降级**（无报错、无提示、面板零条目） |

**最伤用户的不是「少一条通道」而是「两条都没有」**：agent 在线且应用侧 ACK 确认在采集时 `SqlCaptureGate.paused` 置真（`AgentManager.kt:1282`/`:1347`），控制台桥 `processConsoleLine` 随即短路（`SqlLogConsoleBridge.kt:884`，EP 通道同函数 `SqlConsoleBridgeFilterProvider.kt:90`）⇒ 白名单漏一个驱动 = 双通道同时零产出。这条**不依赖 catch-all 就能先修**，成为券 02。

## 定案：白名单优先 + 接口兜底，不替换

`ClassFileTransformer` 读类头 `interfaces`，含 `java.sql.Statement`/`PreparedStatement`/`CallableStatement`/`Connection` 即织。两笔账：

1. **性能实测**：裸字节子串 `java/sql/` 预筛 2–5 µs/类 → ASM header（`SKIP_CODE|SKIP_DEBUG|SKIP_FRAMES`）8–11 µs/类、命中率 11–15% ⇒ 2 万类约 60–140 ms CPU。**全树解析 200–515 µs/类，只准落在确认命中的类上**（否则 2 万类多花 0.6 s）。
2. **白名单不许撤**：它的价值是「已验证字节形态」（达梦 `DmDriver` 出口 chop_frame 撤出等）。语义从「唯一覆盖」降级为「已验证快速路」。

**必须加「嵌套只报最内层」门**：HikariCP 4.0.3 有 8 个类实现 `java.sql.*`（`HikariProxyPreparedStatement` 是 `final` 且直接 implements）；MyBatis `PreparedStatementLogger` 是 JDK 动态代理 ⇒ 不加门就双报。

## 兜底线的一个刻意取舍（红线驱动）

C1 实施时把兜底织入面定为 `{EXEC_PARAM, EXEC_CTX, BATCH, PARAM}`，**不含 `ADD_BATCH`/`CLEAR_BATCH`**——那两个出口要读原局部变量，遇到未知驱动的 `chop_frame` 会整类 `VerifyError`（达梦 `DmDriver` 先例）。代价是**兜底命中的驱动批处理文本不累积**，已登记为缺口。这类「为红线主动放弃功能」的取舍要照此记录。

## 红线十条（用户最高优先级）

CCE（对象身份不得替换）／VerifyError 与链接期失败（premain **不得内联未知形态**——类定义失败＝宿主起不来，改「记录候选 + attach 补挂」）／类文件 major 版本闸（ASM 9.4 读 major 65 实测抛 `Unsupported class file major version 65`，本轮升 **ASM 9.8 / 上限 major 69**）／启动变慢与类加载风暴（类数上限 + 耗时预算 + 连败 kill switch）／per-statement 登记内存膨胀／`Class` 强引用致 classloader 泄漏（改 `ClassValue`）／异常 100% 自吞不改返回值／脱敏唯一出口／双报／`paused` 假在线。

## 交付物与状态

- **计划**：`docs/开发计划/20261030-1016-T-CaptureCatchAll-…-开发计划.md`，占号 **O-155 / D201 / V226**，11 券；红线写死文首「不允许接口 500、不允许应用无法启动，违反任一条即整券回滚」。
- **分仓规则**（复用 [[project-competitor-docs-open-source-exposure]] 的内部库铁律）：公开仓只写本仓代码审计、**不含竞品实现细节、不留占位指针**；竞品逆向证据落 `docs/内部/调研报告/`（该目录在 `.gitignore`，是私有仓嵌套副本，dev 工作树没有需从主仓复制）。
- **实施**：分支 `t-capture-catchall` / 工作树 `mpsv-catchall`（基于 dev `6ddf9119`）。C1（agent 侧，已完成）新增 `CatchAllMatcher` / `ClassFileVersion` / `NestGate` / `CaptureBudget`，心跳带 `matchedByInterface`/`skippedByVersion` 等计数；237 例 0 失败；反向验证摘接口匹配轮 → 3 红。C2（IDE 侧：活性回落 + 覆盖自检上报 + 双哑用例）进行中。
- **C1 反向验证新发现**：既有「厂商对齐守卫」是前缀级判定（见 [[pitfall-vendor-coverage-guard-prefix-level]]），删三条语句面不红、四条目同撤才红 ⇒ 它挡得住整族静默但挡不住半覆盖；新增 `DialectCaptureCoverageTest` 才管两面半覆盖。

相关：[[project-agent-enhanced-capture-consent]]（connect 包装 + 语句级织入双通道原始模型）、[[reference-agent-attach-smoke-recipe]]（真机验证口径与「父类覆盖子类」事实）。
