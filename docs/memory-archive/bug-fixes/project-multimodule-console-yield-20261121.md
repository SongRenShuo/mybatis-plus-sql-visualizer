---
name: project-multimodule-console-yield-20261121
description: 多模块并行时无 agent 模块的控制台 SQL 被全局让位吞掉——T-MultiModuleConsoleYield 已修并推双仓（master 4214ac37a），用户截图实证双通道共存
metadata:
  node_type: memory
  type: project
  originSessionId: sess_86436c85-7dc4-4294-b1ee-bd34d6d893c6
---

用户沙盒实测（2026-11-21 15:35）：两模块并行、只挂一个 agent，另一模块的 MyBatis 三段日志（`==> Preparing:` / `==> Parameters:` / `<== Total:`）全进「让位丢弃 SQL 行」，面板 0 条。根因：接管闸门 `SqlCaptureGate.paused` 是**项目级全局开关**——agent 在任一进程在线时，桥对所有控制台的 SQL 行都让位（单应用防双报正确、多模块纯损失）。

修法（T-MultiModuleConsoleYield，实施 `eb9711735` + 回填 `4214ac37a`，**本地 master 未推双仓**）：让位做**行级进程归属**——控制台行前缀自带目标进程 PID（Spring Boot 标准格式第三段），与 agent 会话 pid（HELLO 帧）同源；pid 有健康会话 → 既有让位防双报；pid 无会话 → 照常解析（修复点）；提不出 pid → 保持全局让位（宁丢勿重）。`SqlCaptureGate` 增 `liveSessionPids`（`updateOnline` 与 `paused` 同点 clear+重灌同步）；EP 路径（`EpLineFilter`）同调 `processConsoleLine` 自动受益（EP Filter 无 console 引用也不需要——行内 pid 就够）。

读数：`MultiModuleConsoleYieldTest` 5/0（pid 提取两形态+非日志行 / 有会话让位+计数 / 无会话产出（修复点）/ 无前缀保守面）+ 让位嗅探回归 `AgentLogTimeParityTest` 14/0、`ConsoleSettleFlushTest` 6/0、`EpBridgeSinglePathTest` 7/0；反向 git 补丁（[[feedback-reverse-probe-via-git-patch]]）摘行级归属 → 修复点用例 **1 红** → 还原 5/0。计划 `docs/开发计划/20261121-1544-T-MultiModuleConsoleYield-…md`。

实施坑三条：① **归属条件首版写反**（写成「pid ∉ 会话集才让位」）——首跑 2 红当场暴露（让位面与修复面正好互换），测试先行的价值现场；② MyBatis **空参数行**（`==> Parameters:` 余量为空）不闭合条目、要等静默冲刷——测试夹具的 Parameters 行须带参数值才能行级同步闭合，断言落在行级边界而非冲刷后；③ manager/纯函数分层口径延续：平台测试里 bundle 随 locale、共享服务状态粘性污染，断言只钉可直接观测的返回值。相关：[[project-oldjvm-premain-guard-20261120]]（同一条验收链上用沙盒发现的本缺陷）。

**已推双仓 + 用户截图实证（2026-11-21）**：`eb9711735` + 回填 `4214ac37a` 推双仓（Gitee/GitHub 均 `4214ac37a`）；CI run `44884236061` 触发但 **结论未核**（watcher 被用户打断）。用户随后截图实证修复生效：同一面板里 #1（agent 通道，DeviceInfoServiceImpl 精确耗时+源码定位+方言）与 #2（控制台通道，UserMapper 的 `FROM user` 查询）**双通道共存**；「让位丢弃 3」只剩挂 agent 那台的行——归属正确。注意控制台条目的 `—` 占位（无方言/无精确耗时）是该通道固有形态，非缺陷。相关：[[project-sql-log-time-source-mismatch-20261121]]（同轮暴露的另一面：时间来源=日志时间的显示存疑，待用户指认）。

**CI 首轮红已修 + 用户复验通过（2026-11-21）**：`4214ac37a` 首推 CI fast(1) 红在 Junit3SemanticsGuardTest——新测试在 BasePlatformTestCase 子类上写了 @Test（JUnit3 语义按 test* 方法名收集，注解无效且误导），删注解修正 `642bbdb71` 后 run `44885561418` 全绿（[[pitfall-junit3-semantics-guard-test-annotation]]）。用户沙盒复验通过：双通道共存截图（#1 agent 条目带方言/耗时/源码定位、#2 控制台条目 user 表查询），「让位丢弃 3」只剩挂 agent 那台的行。master tip `642bbdb71`。

**2026-11-25 用户报新现象（方向相反）**：多模块下**第二个模块启动后第一个模块的 SQL 捕获不到**；用户用 demo 两个 MySQL 模块未复现，其两个模块都连 Gauss。复验装置见 [[project-gauss-multimodule-repro-fixture-20261125]]，行级归属对日志形态的前提见 [[pitfall-multimodule-repro-slf4j-pid-contract]]。
