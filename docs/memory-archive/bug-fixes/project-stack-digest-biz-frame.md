---
name: project-stack-digest-biz-frame
description: T-StackBizFrame 已实施（20261029）——增强捕获栈快照看不到项目调用类的根因与三层锚点/S2 修法、门禁读数，以及「目录来源判据」真机探针结论（目录来源稳定 false、受限 TCCL 下退化为 null ⇒ loader hint 是必要条件）
metadata:
  node_type: memory
  type: project
  originSessionId: session_1448dcd9ca7b9c897bf03aa23144cbd0
---

2026-10-29 用户报障：定时任务（`@Scheduled`）下增强捕获的 meta 行位置正常显示 `MerchantWalletRechargeServiceImpl.java:32`，但复制出的栈快照里一个项目类都没有。隔离复现（69 帧夹具，`javac -sourcepath` 直接编译 agent 的 `StackDigest`/`StackLocator`，不启 Gradle）：`LOCATE` 命中项目帧、`HAS_PROJECT_FRAME=false`，摘要串与报障串同形。

## 根因（三条，均已落码修掉）

- **R1** `StackDigest` 栈底锚点只跳过自有 `isInfraFrame` 白名单（容器/JDK/Servlet 管道 + spring web/boot），`org.springframework.scheduling.*`、`io.micrometer.*` 不在内 ⇒ 非 Web 线程在 `DelegatingErrorHandlingRunnable#run` 就停住，锚点=栈底，业务帧全落进中间省略段。
- **R2** 锚点语义是「最外层非 infra 帧」而非「最外层业务帧」；`findBusinessController` 只认 `*Controller` + 同根调用帧，服务类/任务类入口无等价补偿。
- **R3** 定位（L1 目录来源过滤）与摘要（`StackDigest.build`）是两条独立判定、无一致性约束 ⇒ 同一次执行可以「头部有、栈里没有」。
- 省略段是**采集期丢弃**的，弹层折叠/展开补不回来。「有时候」的边界＝Web 同步请求正常，定时任务/异步池/MQ 不正常。

## 已实施（20261029，未提交）

- **S1 三层锚点**：层一 `StackLocator.isBusinessFrame`（复用 `isSelectableBusinessFrame`，与定位同源）→ 层二 `isInfraFrame || isFrameworkFrame` 穿透 → 层三字面末帧；Controller 补偿只在层一未命中时应用。
- **loader hint 必传**：`StackDigest.build(stack, hint, located)`，`WovenEvents`/`StatementHandler` 两处都传驱动侧 loader。
- **S2 定位帧必在栈内**：`Frame` 增 `primaryClassName`/`primaryMethod`（`methodKey` 是简名不能做身份），落在两个窗口外时单列一段插在省略标记前并扣减省略数。
- **S3**：IDE 侧 `isKnownInternal` 补驱动族 + `org.apache.ibatis.logging./binding./reflection./type.`；meta 行内联摘要改 `projectExcerpt` 取景（首个非内部帧本来在开头时行为逐字不变）。
- 读数：agent 模块 **190/0/0/1**；IDE 定向 22/0 + 14/0；全量 `testShards` **8369/0/0/9**、parity 778 类、19m38s。反向验证三次：移除层一 1 红、移除层二 2 红、移除 S2 2 红。

## P-1 真机 loader 判据读数（可复用的探针做法）

装置：JDK17（`C:/Users/USER/.jdks/ms-17.0.19`）+ MyBatis 3.5.19 + H2 2.2.224，探针类编译到目录（模拟 IDE 内运行的应用），SQL 由线程池线程执行且该线程 TCCL 换成只放行 `java.`/`jdk.` 的受限 loader；agent 以 `-javaagent:...=port=N;logLevel=info` 挂载，Python 最小接收端按 `[4B 长度][1B cmd][JSON]` 落盘 EVENT（含 `sd`）。

结论：业务类 `loadedFromJar=false`（codeSource 是目录）、JAR 依赖 `=true`（判据本身可靠）；**受限 TCCL 下来源判据退化成 `null`，显式 hint 能救回** ⇒ S1 的 loader 入参是必要条件不是优化。agent 自身诊断行（`logLevel=info`）也会打 `via=L1 … jar=false`，是排障时最快的现成判据。

## 证据边界与遗留

- 端到端探针栈只有 4 帧业务侧深度、尾巴是 `java.*`（旧实现本来就跳过）⇒ 它证明「新路径不劣化且与定位一致」，**不能**证明旧实现在该链上丢帧；丢帧判别由按用户粘贴原样构造的 69 帧单测承担。真机 Spring 调度栈复现（业务帧与尾巴隔 ≥8 帧）未搭。
- 未实施：弹层标题带线程名（`threadName` 只到 `AgentEvent`，要跨 `LogSqlEntry`/`PreviewResult` 四层补字段）；S4 多段压缩留待下轮。
- 计划与实施记录：`docs/开发计划/20261029-1012-T-StackBizFrame-…-开发计划.md`（§8 全量读数与反向验证）。

相关：[[project-agent-enhanced-capture-consent]]、[[pitfall-source-scan-guard-needs-literal]]（本轮改跳过表时踩到）、[[pitfall-ui-promise-vs-model-decision-layer]]（R3 同族：同一事实两处各推导一份即分叉）。
