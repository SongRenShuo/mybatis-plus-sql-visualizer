---
name: project-sync-k2j-496-inprogress
description: T-SyncK2JMaster496 端口同步——已完成并收官（锚点推进到 002d929f，全部门禁绿），后续 T-SyncK2JMaster 轮次从此锚点起算
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7f7234de-a172-4ff6-9efc-ed2f5d6a8901
---

T-SyncK2JMaster496：把主仓 master 增量**同步功能**（非合并分支）到 `feature/kotlin-to-java` 端口分支。**已收官（2026-10-22）**。

## 最终状态（可作下一轮起算锚点）

- **冻结区间**：`39473a6b` → **`002d929fec2a001bde456d383717ebd669e1149d`**（主分支实施中两度前进：先 82ee88b1（+63），收尾又 +4 提交 T-ShadowEntityResolve，均已纳入）。`.port-sync` 的 `synced_master` 已推进到 `002d929f`（含完整 note）。
- **门禁读数（最终，追加范围后复跑）**：
  - 三类编译：`compileJava` / `compileTestKotlin` / `compileTestJava` 全绿。
  - `testShards --no-configuration-cache`（清空 `build/test-results/*` 后干净重跑）**7372 例 / 2 败 / 0 错 / 跳过 9**（shard1 1476/0/8、shard2 1479/0/0、shard3 1466/0/0、shard4 1491/2/0、shard5 1420/0/0、testExternal 40/0/1）。**两败唯 `DataSourceJdbcSmokeTest`（`testAllDemoDataSourcesReachable` / `testExecuteValidationSqlOnEachSource`）= 环境限制**：dbx_test@114.66.55.245 端口 TCP 实测可达、但 demo `.idea/dataSources.xml` 无 user/password 节点（凭据在 IDE keychain，测试链不可得），非回归。
  - `testExternal`（守卫/扫描）40/0/1 绿；`sub-projects/sql-visualizer-agent:test` 162/0/0（29 类）绿。
  - `verifyGate` 三目标 Compatible（IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63）。
  - `buildPlugin`：`mybatis-plus-sql-visualizer-2026.10.16.zip` **7,844,797 字节 sha256=7a1f1284b5ec86f1763f5215ccf4b89166f21a7df19f4d5e3699d4c67df06869**。
  - `testDatabase`：环境受限未跑（docker daemon DOWN）。
- **生产树 `src/main` 零 `.kt`**（全 Java）。
- **fatal 基线重登**：catch(Throwable) 总量 763→833→852；未判定 423→456；AgentManager 9→10（T-JRebelPremainCrash C 案）；SqlLogPanel 豁免 4→5；ConvertSqlToJavaAction 豁免 5→6。守卫白名单里「落位后删」条目已随本轮移植清账。
- **关键新沉淀**：testShards 分片是**跑中自适应重平衡**（`shard-imbalance-history.jsonl`）且 XML 结果目录**跨轮残留**——清空 `build/test-results/testShard*` 再跑才有可辩护的总数；Gradle 对全绿 `test` 任务**不打印**「N tests completed」摘要，故总数只能从 XML 汇总。

## 本轮移植规模（k2j 侧，共 45 提交）

B1 模型/设置（SqlFragment/WrapperFlagOutcome/FieldFillSimulation/PluginSettings 三新开关、consent 机器整段摘除、AgentAttachTarget 进程身份分类）；B2 方言与执行（DialectDecisionEngine/IdeaSqlContextBridge/ModuleDataSourceBindingStore/DmSqlDialect/DialectCapabilities/SchemaValidator/ExecutionDialectGate/ExecDialectRouting/PrepareSingleFlightGate owner 身份重写/QueuedExecuteIntent/DataSourceSelector 全链）；B3 转换链 22 件（JavaCodeGenerator 逻辑删除跳过 + OR 组三档 + ANSI 字面量 + 派生表拦截、OgnlEvaluator unsupportedPropertyPaths）；B4 ui（RollbackSqlDialog 543→1099 快照双态表/CSV 编辑器/只读旁路/方言闸出口、SqlPreviewPanel 家族、ParamHistoryCache 预设方法作用域、annotate 四件、MpvsIcons + mapperMethod/mapperStatement）；B5 日志十一批（R53 双通道状态栏/R60 脱敏/O-147 修订状态机与流式追加/UI010 代次守卫/UI-045）；B6 share/history/mcp（ShareContentProjection/AiContextExport v2/诊断报告三件+ShareDialog 七选项/R90 EXPLAIN 持久化/HistorySearchService F55·F56·F65 三合一/McpExplainFormatter.formatFailure/McpFilePathResolver canonical/McpToolsetDynamicRegistrar 探测抽出）；B7 PlatformSignatureChecker 取 master（+523）；B9 docs union 81 件；update 包 R62 分型与双源兜底链接；config 模块绑定行+StorageClear 三动作+R90 保留开关行+MCP 脱敏开关与横幅；追加范围 T-ShadowEntityResolve（EntityClassResolver 工程内容优先/非实体类不构成歧义/来源标签 + 两新测试）。

## 事故与教训

两路子代理限流阵亡（B4 ui 跑 83 分钟后 1305、mcp 子代理同刻失败），B4 尾款与 mcp 件由主控接续完成、全部按 WIP 留底。**教训**：派子代理前先估模型额度；子代理开工即要求增量提交；掉线后先审计工作树再续（本轮 B4 移交干净，无「先红」中间态）。

相关：[[project-kotlin-to-java-port-sync]]、[[project-competitive-round27]]（master 侧同期内容）