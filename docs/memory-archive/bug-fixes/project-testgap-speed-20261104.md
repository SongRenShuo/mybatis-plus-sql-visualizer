---
name: project-testgap-speed-20261104
description: T-TestGapSpeed 踩坑盲区补测与全量门禁提效——t-testgap-speed 分支六券状态与关键读数
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b8632d13-9f4c-4c49-9d8e-aff3fff48511
---

T-TestGapSpeed（2026-11-04 立项）：对 24 份踩坑复盘逐条对账后发现大部分历史坑**已事后补测**，真缺口收敛为 G1~G5。计划册 `docs/开发计划/20261104-0053-T-TestGapSpeed-*.md`（master `336684af` 立项），实施在工作树 `F:/code/Java/Gitee/SongRenShuo/mpsv-testgap-speed` 分支 `t-testgap-speed`。

六券落点：T1 ReplayExportDialog 11 例 headless（`b2a85b3e`，生产只补惯例 doDispose——本仓 Dialog 测试靠弹窗自带 public doDispose 走 tearDown，平台 dispose 是 protected）；T2 BrowserUtil browse/open 调用点收敛扫描守卫（`1fdd35e2`，调用点粒度、旧守卫漏 open( 两处，反向验证红绿双读数）；T3 沙盒验证清单新增「headless 单测不可验判据」九项（`bfc906b7`）；T4 权重画像刷新 612→802 类 + buildSrc 陈旧告警（`86f16ace`/`3a5fb604`）；T5 **perf 用例串行尾道**（`1d623628`）：LogEntryRailPerfTest/LogPipelinePerfTest 摘出并发分片进 `testShardTail` 尾任务（mustRunAfter 全部分片、聚合计入 SHARD TOTAL 不进失衡告警），并行运行器路径走 test-shard-serial-classes.txt 同清单；T6 进度脚本 `-ShardCount` 参数化 + CONTRIBUTING 收口轮 `-PtestShardMaxLane=5` 口径（`507bc705`）。

**已收口（20261104 02:07 收口轮，tip `52a80ccf`）**：全量 testShards 8517/0/0/13 + 尾道 31/0/0/0，墙钟 20:30，0 告警；T7（releaseGate 动态让路）按计划边界未实施留待办。**未推远端**（欠推待用户令）；未合 master（合流需用户授权）。

**T8 追加券（20261104 用户令「use 的修一下」）**：use/DML 等无结果 Tab 语句成功后误报 `execute.timeout.hint`（错向归因）——修法=完成证据检测（Output 完成态回显子集，进度词不算，挂死不误报）+ `onInfo` 中性回调穿层 11 处签名（面板 showStatus 灰字，红条通道不接成功态）。**`3c55f11f`**，ConsoleErrorExtractTest 21/0/0/0、反向验证恰 1 红、受影响链定向 32 绿、**T8 后全量门禁复跑 8521/0/0/13 + 尾道 31 全绿（0502 轮，tip `73dc5309`）**；**证据边界：`use` 语句 DBN 是否写完成回显无真机样本，若只回显不写摘要则维持旧提示**——真机验收步骤已交用户（判据日志行 `[T-CEC] console residue`）。踩坑：chooseAndPrepare 递归调用位置传参，插参错位编译红，改具名传参。

关键教训：①weight-refresh.ps1 污染闸口径=30 分钟窗逐类 time 求和，并发轮 961.9s>900s 必拒，无干净轮时用 `-Force` 显式放行并留注记；②侦察报告说「未见用例」不可信——PreviewResultIsolationTest/AgentHeartbeatWindowTest 都已存在，落计划前必须 grep 实测；③mpv-testing.gradle.kts 聚合 doLast 引用脚本顶层对象会炸 CC，片数须经局部 val 捕获。

**提效口径结论（20261104 用户问「缩短了多少」，如实分三档）**：①收口轮单路 32m57s → `-PtestShardMaxLane=5` 自适应 5m02s（T-ReleasePerf9 在案读数），省约 28 分钟 ≈6.5 倍，最大头；②perf 假红重试税 33min 级整轮重跑 → 尾任务 ~20s（T5）；③日常轮墙钟持平（+~20s 尾道成本）——并发/构建侧到地板（墙钟 97% 在用例执行），本轮两轮实测 20:30/17:29 比 20261103 的 6m54s 慢是机器负载/档位差异，不得当变慢读数也不得宣称日常轮变快。

**减量候选→T9 实施结果（20261104 用户批准 A 档，已收口）**：O147 87.1→**67.9s**（`4260424f`，五场景子 JVM 验证 5 fork→1：登记表+@AfterClass 单次 fork+探针三元组；破坏对照双通道显红——测试断言红+登记缺失防护红，还原回绿；全量门禁 8521/0/0/13+尾道全绿 0856 轮，tip `1011339e`）。**析因教训（重要）**：初判 fork 主导被证伪——注释里的「8MiB」是预算记账口径，五夹具实际全 ~134MiB（MAX_REVISIONS=2048×64KiB），剩余 67.9s 是父侧对真实预算夹具的多遍读取=测试目的本身不可压；**judge 前先查常量真值，别信注释数字**。A 档其余（reverse 双件 67.7s/实体三件 42.5s）按逐例证据放弃——GateTest 25.5s 与 GeneratorTest 26s 分属**不同**方法互不重叠；实体三件长尾分布仅 VO/DTO 一对相近（上限 ~2s）撑不起映射表+破坏对照成本。**减量诚实天花板 ≈ 总串行 2%，删测试换秒数路线终结**，B 档维持不做。测试 JVM 残留锁沙盒 jar（「用户映射区域」错）→ 按 CommandLine 匹配工作树路径精确杀。

相关：[[release-gate-timing-floor]]、[[pitfall-source-scan-guard-needs-literal]]（T2 守卫豁免表陷阱）
