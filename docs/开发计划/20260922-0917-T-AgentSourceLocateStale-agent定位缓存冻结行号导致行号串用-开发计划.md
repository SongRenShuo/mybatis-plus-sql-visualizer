# T-AgentSourceLocateStale agent 定位器缓存冻结行号（源码位置串用）— 开发计划

> 来源：2026-09-22 用户报障续报「并且捕获的源码位置也不对」——面板条目显示
> `[DeviceInfoServiceImpl.java:42]`，而同一次执行在应用控制台打出的标记行是
> `SQLVIS-SOURCE|cn/song/mpjdemo/service/impl/DeviceInfoServiceImpl.java|63`。
> 定位依据：demo 源码逐行实测（42 = `joinRightAlias()` 的 mapper 调用行，63 = `joinExtAndSubTable()` 的
> mapper 调用行）+ agent 侧 `StackLocator` 缓存写入点代码实证 + MPJ 1.5.8 制品反编译（javap/zipfile）。
> 相邻事项：与 `T-AgentConfigChannel`（O-54，同一轮报障的「开关不真」）**根因、文件、验证完全独立**，
> 可并行；本项只动 agent 侧 `StackLocator` 与两侧跳过规则的同步口径，不动捕获链路、不动 IDE 侧跳转解析。
> 编号：占用 **O-55**。

## 一、现象

同一份执行、两个位置答案：

| 来源 | 输出 | 真值 |
|---|---|---|
| 应用控制台标记行（注入的 `SqlSourceInterceptor` → IDE 侧 `CallerSourceLocator` 口径） | `SQLVIS-SOURCE|…/DeviceInfoServiceImpl.java|63` | ✅ 63 = `joinExtAndSubTable()` 里 `deviceInfoMapper.selectJoinMaps(wrapper)` 所在行 |
| 面板条目 meta（agent 通道 `event.sourceFile/sourceLine`） | `[DeviceInfoServiceImpl.java:42]` | ❌ 42 = **同一个类里另一个方法** `joinRightAlias()` 的 mapper 调用行 |

复现路径（沙盒 demo，`dm` profile）：

1. 先请求 `GET /mpj/device/rightAlias` → 触发 `joinRightAlias()`（真值 42）→ 面板显示 42（**此时是对的**）；
2. 再请求 `GET /mpj/device/extSub` → 触发 `joinExtAndSubTable()`（真值 63）→ 面板**仍显示 42**。

两次请求在同一个应用 JVM（沙盒日志同一条会话 pid=70224），因此 agent 侧的静态缓存跨请求存活——这是本缺陷的关键前提。

## 二、根因

### 1. 定位缓存把「行号」也冻结了，而缓存键不是业务调用点

`sub-projects/sql-visualizer-agent/src/main/java/.../util/StackLocator.java`：

- `:134-155` 入口键：**栈上第一个非框架帧**的 `class#method`：

  ```java
  for (int i = 0; i < stack.length; i++) {
      if (!isFrameworkFrame(stack[i].getClassName())) {
          entryKey = stack[i].getClassName() + "#" + stack[i].getMethodName(); ...
  ```
- `:152-154` 命中即返回**整帧**（含行号、含候选列）：

  ```java
  final Frame cached = CACHE.get(entryKey);
  if (cached != null) return cached;   // 行号/候选列冻结为首次入缓存时的值
  ```
- `:275-281` 写入是 `putIfAbsent`，且只按 `via` 分支：

  ```java
  if ("L1".equals(via)) { ... CACHE.putIfAbsent(entryKey, frame); ... }
  ```

于是「首个 L1 结果永久服务该 key 的后续所有调用者」——**当这个 key 不是业务调用点，而是全应用共享的某个库内部帧时，
行号就被第一个查询钉死**。

### 2. MPJ 恰好提供了这样一个共享键（demo 与插件主用户群都命中）

- demo 的 mapper：`DeviceInfoMapper extends com.github.yulichang.base.MPJBaseMapper<DeviceInfoEntity>`；
  `selectJoinMaps` 声明在 `com.github.yulichang.base.JoinMapper`（javap 实测为**抽象接口方法**），
  由 MPJ 注入的 `MappedStatement` 承接。
- MPJ 1.5.8 通过 starter 自动配置注册了 `com.github.yulichang.interceptor.MPJInterceptor`
  （`MybatisPlusJoinAutoConfiguration` 常量池引用实测；类声明 `implements org.apache.ibatis.plugin.Interceptor`）
  ——它是 MyBatis 插件链上的一环，**每次查询执行时都在栈上，且对全应用所有查询都是同一帧**。
- agent 的跳过前缀表（`StackLocator.java:53-76`）**没有 `com.github.yulichang.`**（表里已有
  `com.github.pagehelper.` / `com.github.benmanes.`——同一个「漏收即被误选」家族，PageHelper 那次已在注释里记过），
  于是 `entryKey = com.github.yulichang.interceptor.MPJInterceptor#intercept`，
  **全应用共享** → 首个查询（rightAlias，真值 42）写入的整帧，被后续所有查询（含 extSub，真值 63）复用。

### 3. 同一份文件里，姊妹缓存已经写明「整帧缓存＝缺陷形态」，L1 分支却没照做

`StackLocator.java:88-94`（`SAFETY_DECISIONS` 的 KDoc）：

```
* 只存判定不存结果帧——整帧入缓存会冻结行号与候选列（P0-3 缺陷形态，禁）；
* 命中须经「身份在场」校验（见 locate）
```

`SAFETY` 路径已按此改造（存 `[via, 主位置 className, 主位置 method]`，命中时用**当前 pool** 现算行号）；
`L1` 路径仍是整帧 `putIfAbsent`，并且 `writeCaches` 的 KDoc 把这个后果写成了「已知残面」：

```
* 且共享 key 的合流面在既有 L1 整帧缓存下本就存在（先到的 L1 结果同样服务后续调用者），非本策略新增。
```

本轮现场正是这句话所描述的情形第一次以**用户可见的错误行号**暴露出来。

### 4. 两侧定位器口径不一致（同一执行两个答案的第二层原因）

agent 的 KDoc 自称「框架帧过滤规则与 IDE 侧 `CallerSourceLocator` 对齐」，实测**不成立**：

| | agent `StackLocator.FRAME_SKIP_PREFIXES` | IDE `CallerSourceLocator.isFrameworkFrame` |
|---|---|---|
| 共同 | `java./javax./jdk./sun./com.sun./com.intellij./org.jetbrains./cn.song.mybatisplus.sqlvis.`（IDE 侧为 `…visualizer.`）/`com.baomidou./org.apache.ibatis./org.hibernate./com.zaxxer./org.springframework./com.sun.proxy.` | 同左 |
| 仅 agent | 驱动族（`dm.jdbc.`/`com.mysql.`/`oracle.jdbc.`…）、连接池、`org.apache.`、`com.alibaba.`、`com.google.`、`com.fasterxml.`、`io.netty.`、`com.github.pagehelper.`、`org.mybatis.` 等 20 余项 | 无 |
| 仅 IDE | `$Proxy` 前缀 | — |

两个定位器还**在不同时刻取栈**（注入拦截器在 SQL 生成期、agent 在 JDBC 执行期），所以即使前缀表一致也可能选到不同帧。
本轮用户看到的 42 vs 63 就是这两层（缓存冻结 + 口径分歧）叠加后的结果；修缓存后仍应处理口径分歧，否则同一个执行
「标记行一个位置、面板另一个位置」还会再出现。

## 三、影响面

- **位置错 → 跳转错**：面板 meta 显示错误位置，点击条目跳转到同一类里**另一个方法**的那一行；`sourceCandidates`
  也被整帧冻结，选帧弹层与「往上追踪」的候选列表同样过期。
- **错法是「钉死」而非「随机」**：只要该 key 的第一次判定成功，后续同一 JVM 内所有条目都复用它的行号——
  对 MPJ（本插件明确支持的主力框架之一）用户即「一个应用进程里所有条目都显示同一个位置」。
- **不只是 MPJ**：任何「在栈上、且不在跳过表里、且对多次调用是同一帧」的库都会造成同样后果（自研框架、
  其他 ORM 增强库）；`skipPrefixes` 的用户自登记机制（CONFIG 下发）本来是给这种情况准备的，但**该通道当前也不生效**
  （见 `T-AgentConfigChannel` §三），所以用户即便登记了前缀也修不掉。
- **不影响的**：SQL 文本、参数、耗时、库角标、类型染色等条目主体；IDE 侧历史面板读回的位置（走文件解析）。

## 四、修法

### T-01（P0，根因）L1 路径改为「只缓存判定」，行号与候选列按当前栈现算

- 位置：`StackLocator.java:152-154`（命中分支）、`:224-246`（帧构造）、`:270-288`（`writeCaches`）。
- 做法：把 `CACHE: Map<String, Frame>` 换成与 `SAFETY_DECISIONS` 同构的判定缓存
  `Map<String, String[] /* [via, 主位置 className, 主位置 method] */>`，两条缓存合并为一条；
  命中时**必须**在当前 pool 里按 `class#method` 身份校验命中，命中才用**当前 pool 的行号**构造帧。
  pool 构建（一次栈数组遍历，无反射）保留，被省掉的仍是逐帧 `Class.forName` 的来源解析——性能意图不变。
- 收益：键是否精确不再决定正确性；同一方法内多个调用点、跨方法共享库帧、文件被编辑后行号漂移，全部自愈。

### T-02（P0，收敛面）补齐跳过前缀，至少 `com.github.yulichang.`

- 位置：`StackLocator.java:53-76` 与 IDE 侧 `CallerSourceLocator.isFrameworkFrame`。
- 做法：补 `com.github.yulichang.`（MPJ 全族）；顺带复核同族漏项（如 `com.github.jsqlparser`、其他 join 增强库）。
- 说明：本项不单独成立（T-01 才是根治），但它同时改善 `sourceCandidates`/`stackDigest` 的质量
  （库帧当前会挤占 8 条候选位），并让「入口键落在业务帧上」成为常态，减少缓存合流面。

### T-03（P1，跨侧一致性）两侧跳过规则建立单一事实源或明示差异

- 现状：agent 侧 KDoc 的「与 IDE 侧对齐」是失实声明（§二.4 表）。
- 做法（择一，实施前定案 D1）：① 抽一份共享常量（agent 不引 IDE 类，可用源码扫描同步测试锁住，形态照
  `AgentProtocolSyncTest` 的跨侧扫描）；② 保持两份但加同步守卫测试（断言两边集合互为子集/差异有注释登记），
  并把 KDoc 改成事实描述。
- 附带：给「取栈时机不同 → 位置可能不同」写进两侧 KDoc，避免后来人再假设两处必然同值。

### T-04（P1，测试与反向验证）

- agent 侧新增 `StackLocatorCacheStalenessTest`（合成栈，形状照真机）：
  1. **共享库帧**：两次调用经过同一个 `com.github.yulichang.…` 帧、业务行不同 → 断言两次行号**各自正确**
     （当前必红，即本缺陷的最小复现）；
  2. **同方法多调用点**：同一方法内两行各调一次 → 断言两次行号不同；
  3. **身份校验**：判定缓存命中但当前 pool 不含该 `class#method` → 必须落回全量路径（不得复用旧行号）；
  4. **行号漂移**：同一栈内容改变业务帧行号 → 断言跟随变化。
- 反向验证（必做）：临时把 T-01 改回整帧 `putIfAbsent` → 用例 1/2/4 必红；把 `com.github.yulichang.` 从跳过表移除
  → 用例 1 仍应绿（证明 T-01 是根治、T-02 只是收敛），读数贴入 §实施记录。

### T-05（P2，现场可取证）让入口键在真机可读

- 现状：`diagnose()`（`:290-305`）只在 `logLevel=info` 输出，而产线/premain 注入固定 `logLevel=warn`
  （沙盒日志实测 `=port=…;logLevel=warn`）→ 真机排障拿不到 `entryKey`/`pool`，本轮只能靠行为反推。
- 做法：当「主位置 class#method ≠ 入口键 class#method」时打一条**一次性** info/warn 摘要（含 entryKey 与 primary），
  或把 entryKey 作为诊断字段随事件发给面板详情显示。

## 五、待验证前提

| # | 前提 | 验证方式 |
|---|---|---|
| P-1 | 本轮 entryKey 确为 MPJ 拦截器帧（而非别的共享帧） | **已取证（2026-09-22 真机 jstack 采样）**：帧形态先由 `javap -v` 静态证明（`@Intercepts(@Signature(type=Executor, method="query"))` → 全应用每次查询都在栈上）；随后假 IDE 探针在真实应用里 `jstack` 连打 25 次、命中 1 次调用链，栈中实测 `com.github.yulichang.interceptor.MPJInterceptor.intercept(MPJInterceptor.java:85)` 位于业务帧与 JDBC 执行点之间（证据 `dev-support/e2e-fake-ide/evidence/p1-stack-evidence.txt`）→ **entryKey 确为 MPJ 拦截器帧，静态推断成立** |
| P-2 | 42 与 63 两次执行确实落在同一个应用 JVM（缓存跨请求存活） | 沙盒日志已证同一条会话 pid=70224（`premain 会话建立：pid=70224` → `会话读循环退出（pid=70224）`），其间无第二次会话建立 |
| P-3 | 修复后两侧位置是否仍可能不同（取栈时机差异） | 代码可判（拦截器在生成期、agent 在执行期）；修完 T-01/T-02 后按 §六 步骤实测一次，若仍不同则按 T-03 D1 定口径并在文案里明示 |
| P-4 | 跳过前缀表的其余漏项是否还有「共享键」形态 | 需一次库清单核对（按 `pool` 实际内容做，勿凭想象补表）；建议实施时把 `pool` 首 8 帧打进一次性日志，对着真机栈补 |

## 六、验收

**沙盒真机（runIde + demo `dm` profile）**，捕获开关打开：

1. `GET /mpj/device/rightAlias` → 面板位置必须是 `DeviceInfoServiceImpl.java:42`，且与控制台 `SQLVIS-SOURCE|…|42` 一致；
2. `GET /mpj/device/extSub` → 面板位置必须是 `…:63`（**修复前必错成 42**），与标记行一致；
3. 在同一方法里新增一行后重跑（行号漂移）→ 面板位置跟随（验证 T-01 不再冻结）；
4. 另一张表/另一个 service（如 `UserServiceImpl`）连跑两个端点 → 各自位置正确（验证不再全应用一个位置）；
5. 回归：无 MPJ 的工程（`mybatis-plus-demo`，走普通 MP+MyBatis）位置不变。

**自动化**：T-04 四例 + 反向验证两轮；全量 `test`（基线 491 类 / ~5600 用例）与 `:sql-visualizer-agent:test`
（先清其 build，避免 UP-TO-DATE 陈旧读数）。

## 七、不做的事

- 不动 IDE 侧跳转/预览的解析链（`EntryJumpResolver` / `EntrySourceMatcher` / `CallerMethodRangeResolver`）——
  本轮缺陷在 agent 侧取值，IDE 侧只是如实渲染。
- 不改 `sourceCandidates` 的协议与上限（8 条）。
- 不与 `T-AgentConfigChannel` 混做：那个任务修的是「指令不通」，本任务修的是「位置算错」，
  写集只在 `StackLocator` 与跳过表上与本任务相关（`skipPrefixes` 的 CONFIG 通道由前者修复，本任务的 T-02
  静态前缀不依赖它）。

## 八、待用户定案

- **D1：已定案（2026-09-22 用户）——保持两份实现（「两份 + 差异登记 + 跨侧同步守卫」，即当前形态）。**
  理由（用户确认 + 本计划分析）：两份*列表*本身维护成本极低，真正贵的是「防静默漂移」，而该成本已由
  `LocatorSkipPrefixSyncTest` 付掉；改共享常量需引入构建期生成（新链路 + 新失败面），总成本更高。
  **关键约束（用户点明且与实现一致）**：IDE 侧那份（`CallerSourceLocator`，供注入到应用内的拦截器模板打印
  `SQLVIS-SOURCE` 标记行）是**降级主路径**——agent 未授权/未注入/attach 失败/版本旧时全靠它，
  因此**绝不可**在运行期或编译期依赖 agent 侧代码；即便日后做共享常量，也只能是「构建期生成一份数据」，
  且两边**取栈时机不同**（拦截器在 SQL 生成期、agent 在 JDBC 执行期）故结论仍可能不同。
  可选后续（未实施，按需）：把 IDE 侧那份**补齐到与 agent 侧同集**（纯数据同步、不引依赖、不影响控制台通道可用性，
  代价是标记行行号可能变化＝更准，既有走查基线需重取）。

## 九、涉及文件清单

| 文件 | 改动 |
|---|---|
| `sub-projects/sql-visualizer-agent/src/main/java/.../util/StackLocator.java` | T-01 判定缓存化、T-02 前缀表、T-05 一次性打点、KDoc 订正 |
| `src/main/kotlin/.../log/CallerSourceLocator.kt` | T-02/T-03 前缀与口径（含失实 KDoc） |
| `src/test/kotlin/.../log/agent/AgentProtocolSyncTest.kt` 或新增同步测试 | T-03 跨侧同步守卫 |
| `sub-projects/sql-visualizer-agent/src/test/java/.../util/StackLocatorCacheStalenessTest.java`（新增） | T-04 |
| `CHANGELOG.md` / `docs/变更记录.md` | 收口时三处同步 |

## 实施记录

### 一、车道与提交（2026-09-22）

车道 C（分支 `t-agent-locate` / 工作树 `mpsv-agent-locate`），两个提交均已合入集成分支（`cfc6793`）：

| 提交 | 内容 |
|---|---|
| `ac2c508` | agent 侧：T-01 判定缓存化（`CACHE` 与 `SAFETY_DECISIONS` 合并为 `DECISIONS=[via,主位置class,主位置method]`，命中经「身份在场」校验后按当前 pool 现算行号/候选）、T-02 补 `com.github.yulichang.`、T-05 入口键一次性摘要、KDoc 订正、新增 `StackLocatorCacheStalenessTest`（5 例） |
| `1d90da3` | IDE 侧：`CallerSourceLocator` 前缀与事实化 KDoc、`LocatorSkipPrefixSyncTest`（3 例，保守选项＝两份实现 + 差异登记 + 源码扫描守卫）、`docs/instrumentation/SqlSourceInterceptor.kt` 同步（模板确实内嵌跳过规则副本与失实「对齐」声明） |

### 二、读数

| 项 | 命令 | 读数 |
|---|---|---|
| agent 定向 | `:sql-visualizer-agent:test --tests "…agent.util.*"`（先清 build） | `BUILD SUCCESSFUL`；Convergence 8/8、**Staleness 5/5**、Diagnose 7/7 ＝ **20/20** |
| IDE 守卫 | `testFast --tests "…LocatorSkipPrefixSyncTest"` | `BUILD SUCCESSFUL`，**3/3** |

### 三、反向验证（三臂，均临时改动后 `git checkout` 还原，工作树最终干净）

| 臂 | 改动 | 读数 | 证明了什么 |
|---|---|---|---|
| A | T-01 回退成整帧 `putIfAbsent`（T-02 保留） | Staleness **5 例 4 红**：`expected:<63> but was:<42>`（②③④ 同形，①因首调仍是 miss 而绿）；连带 Diagnose 5/7 红、Convergence 5/8 红 | **测试复现了用户报障同形**（42 vs 63），修复点正确 |
| B | T-01 保留、仅从 agent 表移除 `com.github.yulichang.` | 用例① **仍绿**（同状态下红的只有用例②⑤自身的 T-02 锚点断言，行号断言全过） | **T-01 是根治、T-02 只是收敛面**（计划 T-04 明确要求证明的分离） |
| C | IDE 侧移除 `com.github.yulichang.` | 跨侧守卫 **2 红**（`两侧共同前缀应含 com.github.yulichang.`、`模板出现插件侧没有的前缀`）；还原后 3/3 | 同步守卫有真实敏感度，不是空绿 |

### 四、写集越界（已核实并接受）

车道 C 另改了两个既有测试（`StackCaptureConvergenceTest`、`StackLocatorDiagnoseTest`）——它们的断言**编码的正是旧的整帧缓存语义**（`assertSame` 复用同一 Frame、「命中即免建池」计时口径），T-01 后必然失效，不改则定向套件必红。主调度逐行核实结论：

- `assertSame` → **`assertNotSame` + 字段等价**（`sourceFile`/`methodKey`/`line`/`via`/候选列数）——**加强**而非放宽：新断言明确禁止「复用整帧实例」这一缺陷形态；
- 深栈计时守卫重定基线（miss 侧改用 2000 个不可加载类名，使「命中省掉逐帧 `Class.forName`」可观测），**保留原 3 倍阈值**（`hitMin * 3 < missMin`）；
- 诊断零噪音断言（命中路径不打点）**保留**；`SAFETY_DECISIONS` → `DECISIONS` 的探针改名与两缓存合并一致。
- 遗留（登记不改）：`DeepCaller` 夹具 KDoc「缓存命中 O(1) 对 miss O(depth)」已过时，属写集外。

### 五、未取证项与残留

- **P-1 已取证（2026-09-22 真机 jstack 采样）**：entryKey 确为 `com.github.yulichang.interceptor.MPJInterceptor` 帧——
  假 IDE 探针在真实应用里 `jstack` 连打 25 次、命中 1 次调用链，栈中实测该帧位于业务帧与 JDBC 执行点之间
  （证据 `dev-support/e2e-fake-ide/evidence/p1-stack-evidence.txt`）。原先只有静态推断，现为真机实证。
- 车道 C 的 IDE 侧 gradle 曾多次 `BUILD FAILED` 于 `Unable to delete ~/.gradle/caches/9.7.0/transforms/…/ideaIU-2024.3.7.1-win`（另一车道占用 `jbr/bin/*.dll`），与改动无关；反向臂 C 因此改为直接用 JBR JDK21 运行**已编译的守卫类**（该守卫是源码扫描型，运行时读磁盘源码，等价于真跑），并以提交态对照证明红由漂移引起。收口门禁需注意同类锁竞争，撞到先重试。
- D3 定案（保守选项）建议维持；若日后要单一事实源，只能走「生成式常量 + 同一扫描守卫」，因为 agent 不可引 IDE 类。

### 六、后续订正（2026-10-14）：移除 T-05 的无条件摘要

- **触发**：用户报障宿主控制台刷出 `[T-AgentSourceLocateStale] 入口键非业务帧（一次性摘要，entry=…CarStationInfoServiceImpl$$SpringCGLIB$$0#getById primary=…StationInfoManagementService.java:622 via=L1）`。
- **判据**：T-05 的设计前提是「入口键身份 ≠ 主位置身份 ⇒ 异常（入口键落在共享库帧）」。但 T-SrcGenFrame 之后，
  Spring 代理调用必然构成「入口键＝`$$SpringCGLIB$$0` 生成类帧、主位置＝真实业务帧」——这是**常态健康形态**
  （生成类帧 SourceFile 恒为 `<generated>`，按设计不作主位置）。于是摘要对每个被代理的 Service 方法各打一条，
  由「异常信号」退化为宿主控制台噪音；且它与 T-02/T-01 的收敛方向相反（前者让入口键更常落业务帧，后者让生成类帧必然让位）。
- **处置**：整条路径删除（`reportEntryKeyMismatch`、`ENTRY_MISMATCH_REPORTED` 去重集合与三处调用点，含 `java.util.Set` 导入）。
  entryKey/pool 的诊断能力不变——`diagnose()`（`logLevel=info`）本就输出 `entry=` 与 pool 帧，是唯一诊断出口；
  产线固定 `logLevel=warn`，宿主控制台自此零定位打点。
- **回归**：`StackLocatorCacheStalenessTest` 用例⑤由「warn 档打一条摘要」翻转为「warn 档零输出 + info 档有 `[T-SourceJumpFunnel]`
  且已移除的 `[T-AgentSourceLocateStale]` 不得复活」，宿主零噪音契约自此由测试锁定。
- **未动**：`StackLocator` 其余 `System.err` 打点（attach/首条事件/登记盲区等）均为一次性告警，非本条噪音源，不在本次范围。
