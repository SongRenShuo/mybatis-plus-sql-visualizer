---
name: project-gutter-first-open-delay-20261114
description: T-GutterFirstOpenDelay 全案（20261114 立项→F1/F2→验收→20261115 三轮增补 F1'/F1''/头扫化）——XML/Java 双侧跳转图标闪没与缺席的归因（慢趟换装+清单饿死）、四刀修法提交、验收读数与残余
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

用户反馈（2026.11.14 正式版大工程实测）：性能面全好评，但冷打开 mapper XML 预览图标晚 ~5s，且出现后「MP 图标」消失 ~3s 重现；初始化完成后即无。**用户纠正：MP 图标是我方的**（`MapperXmlToJavaLineMarkerProvider` 的 mapperStatement 图标，慢趟 pass，需 `findClass(namespace)` 解析到接口方法才出）——非 MyBatisCodeHelper-Pro，`MyBatisCompetitorGate` 让位门与本现象无关。

## 归因（计划 `20261114-2131-T-GutterFirstOpenDelay-*`，立项 a45427770）

- **S1（图标晚到）**：冷开 dumb/扫描窗 + 快趟 pass 排程＝平台多波次；provider 本体 O(1)。不修。
- **S2（MP 图标闪没）**：慢趟 pass 里 `findClass` 冷窗瞬时失败返回 null → 平台对慢趟结果**整趟换装**摘掉已渲染图标；索引就绪后下一趟恢复。修 F1。
- **放大器**：首渲染踢出的三条预热（MBG 预取/补全上下文→反查索引冷建 3.3s/跨 ORM 回补 **15s**/装配 warm）在 BGT 抢资源。修 F2。
- 复现装置：`record/perf_scene7_guttercold.py`（**passive 口径**：工程自恢复标签页=冷打开本身，零交互逐秒截图 + 日志 `ctx warm done` 反推首渲染时刻；v1 交互式在冷窗口打字会失败——弹层未就绪）。图标身份判据：预览=sqlGutter（绿箭头紫放大镜）、MP=mapperStatement（青 #00CBCC）。

## 修法与读数（两路子代理并行，20261114 收官）

- **F1 主分支 `d3eb65079`**：`MapperXmlToJavaLineMarkerProvider` 四段判定——成功→出图标+入会话级正缓存（`resolvedStatementKeys`，键 `namespace#id`，无失效）/ 失败+命中→仍出 / 失败+dumb→乐观出（不入缓存）/ 其余→不出。**不 catch PCE**。定向 15+2 绿，反向摘分支恰红 2。`MapperJavaToXml` 方向未动（无实证面）。
- **F2 分支 t-gutter-warm-delay `7ad1d1a5d`**：三链预热收拢 `GutterWarmScheduler`（smart 后延迟 N ms 一次派发，到点复查未关/已 smart/文件在）；Registry `sqlvis.gutter.warmDelayMs` 默认 5000、**0=旧行为**；测试缝优先于延迟逻辑（PreviewAssemblyWarmTest 零改动）。合并 `bae5b594f`（§八双记录冲突手工并保），合并态定向 31/31 绿，CHANGELOG `0dd0aef52`，验收账 `6d9cf5bd2` 全推双仓。
- **主控验收（runIde 合并态，全通过）**：两轮 41 帧冷开全程双图标稳定无闪没；暖重开 <1s；决策轮 `warmDelayMs=30000`（经 ide.general.xml）kick≈**smart+34s** vs 对照轮 8~9s——延迟/立即两分支真机均显形；0 ERROR/0 SlowOperations。
- **残余**：S2 显形窗（慢趟落进 Java 解析不可用窗）沙盒未自然复现，F1 行为由单测矩阵+接线守卫+反向兜底；真机再现按计划 §六取证。

## 验收后三轮增补（20261115，全部推双仓且 CI 绿）

- **CI fast(1) 红修复 `a866b7d6c`**：F1 新增的守卫类 `ui/gutter/MapperXmlSlowResolveFailGuardTest` 未登记 GUARD_CLASSES（文件名含 Guard 就落在元守卫闭包面，放 convention 外也逃不掉）——本地定向（含合并态四套件）没跑元守卫本条故漏。**教训入 L13 Seen 4×：新增 Guard 族文件，定向清单必须含 `GuardSelfTestCoverageMetaGuardTest` 本身**。注册时把说明传进 `DecisionFn` 第三位置参数＝登记豁免，会被反腐烂检查红——有自证语料的函数不登记豁免。
- **F1' Java→XML 侧同构缺陷 `6bc12e9c8`（CI 43617211080 绿）**：用户沙盒报 `CustChannelMapper.java` 图标不显示——对照 `CustAddressMapper.java` 证实**全局性**，daemon 重跑（敲空格+撤销）即回＝「慢趟早期 locate 落空 → 慢趟结果固化」机制实锤。修法与 F1 同构：`MapperJavaToXmlLineMarkerProvider.createMarker` 四段判定（成功入**共享正缓存** `resolvedStatementKeys` 双向受益 / 失败+命中仍出 / 失败+dumb 乐观出 / 其余不出）；`isDumbProvider` 缝移入 companion 双侧共用；接线守卫扩 `javaFailureBranchWiring`（提取器泛化到第 N 个 createMarker、Java 侧锚 `MapperXmlLocator.locate(`）；反向摘分支恰红 3。
- **F1'' 清单饿死根治 `81884a539`（CI 43619322809 绿）**：用户根工程（demo root，scope≈4841 XML 含 2042 target/classes 拷贝）下图标长期缺席=慢趟清单首扫被 JPS 写盘反复取消饿死。修：清单构建移出慢趟（异步 nonBlocking+不写 MISS+落地一次性 restart）+ TTL 30s serve stale + Registry 决策轮复验（详细机制与头扫见 [[pitfall-slowpass-listing-starvation-and-headscan]]）。
- **性能续刀：清单头扫化 `588d64604`**：全量 PSI 解析 → VFS 原始头扫 16KB 提 namespace（慢趟只对命中的 1~2 候选付 PSI）+ TTL 30s + ORM 兜底；MapperXmlLocatorTest 加 per-test setUp/tearDown 清静态槽（修「单跑绿全套件红」的跨用例污染）。定向 60/60 绿。
- **在途**：MPJ（MyBatisCodeHelper-Pro）跳转实现反编译调研子代理进行中（用户令吸取其 XML/mapper 跳转性能精华），报告将落 `docs/调研报告/`，主控审阅后决定可借鉴点是否立项。
- 用户测试环境注意：用户日常在 **demo 根工程**（`mybatis-plus-sql-visualizer-demo`，含插件仓本身与多模块）里测，与验收用的 cust-big-demo 单模块工程 scope 差一倍以上——性能结论不能跨环境外推。
- 相关：[[pitfall-sandbox-coldwindow-typing-and-launcher-hang]]（验收期两坑）、全局 `pitfall-sandbox-registry-persistence-ide-general-xml.md`（Registry 预设值写 ide.general.xml 才生效）、[[pitfall-mapped-exec-file-write-triggers-dumb]]（同役惰性口径）、[[pitfall-slowpass-listing-starvation-and-headscan]]（F1'/F1'' 机制与测试陷阱）。
