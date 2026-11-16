# T-LogBucketPhase2 MyBatis 与 Hibernate 日志解析线程分桶二期——开发计划（核对销案）

> 立项时间：2026-11-11 05:36
> 分支：`t-logbucket-phase2`（worktree `.worktrees/t-logbucket-phase2`，自 master@b8e6ca6a 切出）
> 任务来源：一期计划册（文档站仓 `docs/开发计划/20260915-1440-JdbcTemplate并发参数错挂与历史页时间戳格式-开发计划.md`）的二期候选——「MyBatisLogParser（Preparing/Parameters 相邻行同样可交错）与 HibernateLogParser 同构分桶」，2026-11-11 定案立项。

## 一、核对结论（先证伪）：二期前提已被先行任务完成，本期零生产代码改动

按「先证伪再动手」纪律核对代码与 git 历史，**二期范围在 2026-09-16（一期完成次日）已被两笔先行任务全部落地**，本期立项前提失效，销案：

| 先行任务 | 提交 | 交付内容 | 与本期范围的关系 |
|---|---|---|---|
| T-LogParseConc（2026-09-16 14:45 立项） | `66263657` | `LogThreadRouter` 线程分桶下沉（token 提取/分桶/淘汰/无 token 行归属三解析器共享）；`MyBatisLogParser` 状态全按桶隔离（Bucket：pendingSql/pendingLogLine/pendingParams/prefixMatched/paramPrefixMatched/continuing）；空 `Parameters:` 判别式；`LogParseDiag` 计数；MyBatis 多行续行 | 覆盖本期 MyBatis 范围 + 交付了本期要做的「公共机制抽取」（`LogThreadRouter`），`JdbcTemplateLogParser` 已改同源引用 |
| T-HibConc（2026-09-16 15:08 立项） | `e7787cac` | `HibernateLogParser` 状态全按桶隔离（collecting/inComment/multilineBuffer/pendingLogLine/pendingParams/pendingShadow）；`LogThreadRouter` 增 Hibernate 专属 `routeNoTokenToRecent`（无 token 行 MRU 归属）；零参影子槽按桶隔离；新增 `HibernateLogParseConcurrencyTest` 6 例 | 覆盖本期 Hibernate 范围（T-LogParseConc 交付记录中 T-LPC-06 明确缓做的部分） |

两笔提交均已在 master 祖先链上（`git merge-base --is-ancestor` 实测通过）。计划册：插件仓 `docs/开发计划/20260916-1445-T-LogParseConc-控制台解析并发配对与多行时间修正-开发计划.md`、`docs/开发计划/20260916-1508-T-HibConc-Hibernate控制台解析并发配对分桶-开发计划.md`（各自 §八交付记录含门禁读数：全量 `test :sql-visualizer-agent:test` 绿，IDE 侧 4293→4299 用例）。

一期文档把两解析器列为「二期候选」的时点（2026-09-15）早于上述两笔提交（2026-09-16），立项依据未同步到先行任务的落地状态，属计划册跨仓不同步，非功能缺口。

## 二、行格式与 token 提取核对结论（证据 file:line）

- **token 提取机制**（三解析器共享）：`src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:171` `threadTokenOf(line)`——以日志级别关键词为锚、取前后 35 字符窗口内最近的非 TID 非纯数字方括号段；提取失败回退共享默认桶（fail-open）。
- **MyBatis 行带线程段**：`2026-09-16 14:34:59.938 DEBUG 36516 [http-nio-8088-exec-9] … ==>  Preparing: …`——`DEBUG` 锚后窗口命中 `[http-nio-8088-exec-9]`（MP 日志的 `[] -` 空段被 isNotBlank 排除），token 提取成立。`MyBatisLogParser.kt:113-124` 两个 feed 口均先路由后进桶。
- **Hibernate 行带线程段**：`2026-09-16 10:00:00.100 DEBUG 1 --- [nio-8083-exec-5] org.hibernate.SQL : …`——Spring Boot 默认格式 `[nio-…]` 段命中。多行 SQL 正文与 `Hibernate: ` stdout 标志行**不带**前缀（提不出 token），由 `routeNoTokenToRecent=true` 的 MRU 归属兜底（`HibernateLogParser.kt:70-75`、`LogThreadRouter.kt:99-111`）——这正是 T-HibConc 相对 MyBatis 多出的适配点，非「提不出 token 即停手」的情形：带前缀的语句行/绑定行（参数归属的判定行）全部带 token。
- **无 token 裸行的兜底语义**：MyBatis/JdbcTemplate 走「收集态桶优先 → 共享桶」（`LogThreadRouter.kt:112-117`），Hibernate 走「MRU → 收集态 → 共享桶」（`:106-111`）。

## 三、改动清单

生产代码：**零改动**（分桶机制与两解析器接入均已在 master）。

本期实际产出：

1. 本计划册（核对销案记录）。
2. 反向验证（探针用后即还原，未入库）：临时把 `MyBatisLogParser` 两个 feed 口与 `HibernateLogParser.feed(line, threadToken)` 的路由 token 置 null（等价「全局单槽配对」旧行为），并发用例变红（读数见 §四）；还原后全绿。探针期间按并行纪律在 `build.gradle.kts` 的 intellijPlatform 块临时加本任务沙盒 `logbucket2`，收尾还原。

## 四、测试读数（原文）

先红（破坏分桶，两段）：

- 红一（破坏 Hibernate + 两并发类同跑）：

```
HibernateLogParseConcurrencyTest > 两线程交错双副本_参数各归其主且各合并一条 FAILED
    java.lang.AssertionError at HibernateLogParseConcurrencyTest.kt:57
HibernateLogParseConcurrencyTest > A块起头_B整轮插入_A绑定最后到_不跨线程错挂 FAILED
    java.lang.AssertionError at HibernateLogParseConcurrencyTest.kt:86
17 tests completed, 2 failed
BUILD FAILED in 2m 37s
```

- 红二（补破坏 MyBatis 单参 feed 后跑 `LogParseConcurrencyTest`）：

```
LogParseConcurrencyTest > 空Parameters行不再吞掉下一行_两条语句各带自己的参数 FAILED
    java.lang.AssertionError at LogParseConcurrencyTest.kt:56
LogParseConcurrencyTest > 并发交错_参数各归其主 FAILED
    java.lang.AssertionError at LogParseConcurrencyTest.kt:102
11 tests completed, 2 failed
BUILD FAILED in 18s
```

红读数证明既有并发守卫真能抓住交错（非空绿）；其余用例在破坏态仍绿属预期（单线程序列在单槽语义下本就等价）。

后绿（还原后，解析器族定向 12 类）：见 §六实施记录回填。

## 五、口径说明

- 本期定向测试集（纯逻辑，默认 test 桶）：`LogParseConcurrencyTest`（11 例）/ `HibernateLogParseConcurrencyTest`（6 例）/ `MyBatisLogParserTest` / `HibernateLogParserTest` / `HibernateLogParserDualCopyTest` / `JdbcTemplateLogParserTest` / `LogThreadRouterTest` / `IbatisLogParseTest` / `DualParserIntegrationTest` / `LogRouterHotPathRegexGuardTest` / `ConsoleSettleFlushTest` / `ParamEdgeCaseRegressionTest`。
- 不跑全量 testShards（解析器零改动，无回归面）；先行任务两轮已各跑过一次全量门禁绿。

## 六、实施记录回填位

**2026-11-11 05:40 核对销案实施完成**：

- 生产代码零改动（前提证伪见 §一）；反向验证探针与沙盒隔离已全部还原（工作树仅余本计划册）。
- 后绿读数原文（还原后，解析器族定向 12 类）：

```
> Task :test
BUILD SUCCESSFUL in 32s
32 actionable tasks: 7 executed, 25 up-to-date
```

- 定向测试计数（JUnit XML 实测，全绿 0 失败 0 错误 0 跳过，共 224 例）：
  `LogParseConcurrencyTest` 11 / `HibernateLogParseConcurrencyTest` 6 / `MyBatisLogParserTest` 32 / `HibernateLogParserTest` 56 / `HibernateLogParserDualCopyTest` 7 / `JdbcTemplateLogParserTest` 52 / `LogThreadRouterTest` 8 / `IbatisLogParseTest` 14 / `DualParserIntegrationTest` 11 / `LogRouterHotPathRegexGuardTest` 1 / `ConsoleSettleFlushTest` 6 / `ParamEdgeCaseRegressionTest` 20。
- 提交：`t-logbucket-phase2` 分支，仅本计划册一笔提交（哈希即分支 tip，见任务报告提交链）。
- 收尾状态：工作树干净、未合并 master、未 push、未打 tag；并行登记 `logbucket-phase2` 已注销。
