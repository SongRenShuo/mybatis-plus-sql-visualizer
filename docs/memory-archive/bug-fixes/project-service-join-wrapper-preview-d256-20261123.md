---
name: project-service-join-wrapper-preview-d256-20261123
description: D256（20261123 实施、20261125 合入 master 736b6a272 并清理分支）——自研四层 Wrapper 子类识别（父链四档+规则4）、join 族调用行锚点、join 族批量模板一律拒绝；146 用例绿、三项反向验证；demo/测试/文档已脱敏
metadata:
  node_type: memory
  type: project
  originSessionId: sess_de290dc9-8fa9-4fa9-8119-0ababf9ec7f7
---

2026-11-23 用户报障：业务层 `xxxRepository.selectJoinList(XxxDTO.class, wrapper)` 无法触发预览；自研 `CommonServiceImpl extends ServiceImpl` 把所有 `selectJoin*` 转发给 `getBaseMapper()`。

**根因（已闭环，不是 Service 封装）**：`WrapperLocator.detectWrapperType` 只按 `PsiClassType.className` 匹配 13 个官方类名，自研子类不在白名单；`isWrapperVariable` 无父链回退；`buildChain` 三顺位都落同一浅层判定。对照组实测：同结构换官方 `MPJLambdaWrapper` 即命中。

**现场类名（四张截图 + javap/Maven 取证，已脱敏不复查）**：内部库是「MPJ 整套复制改名」，四层自研链
`JoinLambdaWrapper<T> extends AbstractLambdaJoinWrapper<T, JoinLambdaWrapper<T>>`（implements `Query`/`Chain`/`SelectWrapper`）
→ `AbstractLambdaJoinWrapper<T,C> extends AbstractJoinWrapper<T,C>`（implements `QueryJoin`）
→ `AbstractJoinWrapper<T,C> extends Wrapper<T>`（**类继承**；MP 的 `Wrapper` 是接口 ⇒ 自研抽象类）→ 最底层再接 MPJ 基类。
`AbstractLambdaJoinWrapper`/`AbstractJoinWrapper` 在 Maven 中央仓 0 命中（非公开库）。

**实施状态：已合入 master、已推送、已清理（2026-11-25）。** 合并提交 `736b6a272`；`d256-integration` 工作树与分支已删；双仓 master 推送成功（`push-all.ps1 -Branch master`，Gitee+GitHub 均 OK，`x-access-token:***@` 令牌通路、无弹窗）；合流后平台 transform 修好复跑 10 类 **146/0**，与合并前一致；demo 仓脱敏提交已在 origin。

提交链（实施期）：
- 文档+复现 `fc2b90542`（计划新占 **D256**，下一全新 D257/V247/O-165；`CustomWrapperServicePreviewTest` 6 例）
- M1 `cd3934c6c`：`detectWrapperTypeDeep` 父链四档（FQN 前缀 / 接口同名组合 / MP 家族细分 / 规则 4 链方法启发）+ `WrapperTypeSuperChainTest` 9 例
- M2 `ab6c7110a`：`selectJoinList/One/Map/Count` 调用行独立锚点 + 契约期望更新
- M3 `84ae88a2d`：join 族错误 SQL 守卫 + `BatchOperationJoinGuardTest`（6 例）
- 收口 `7f2e87a2a` / `d78a7dffc`（单一来源 `isMpjJoinConsumerCall` + `isSqlCandidate`、复现断言翻转）/ `0c15797e5`（规则 4 补分步扫描、消费集合补 join 族、M3 改「一律拒绝」）/ `ba6ae297d`（计划 §九 + CHANGELOG）
- 脱敏 `3b669a7`+`6042a45`（demo 仓）/ `8a7a0f6fb`+`6dc191912`（插件仓测试与注释）
- 用户自有 `40e5b009f`（Marketplace slug 更新，落在本分支上）

**门禁**：10 类 / 146 用例 / 0 失败。**反向验证**：M1 摘父链恰 3 红；M2 摘锚点 2 红；M3 仅摘守卫 1 红（其余 join 名未登记），临时把 `selectJoinList` 登记进 `BATCH_OPERATIONS` 后 4 红——守卫真实保护面已钉住。

**实施期两个计划外真缺口（已修 + 回归用例）**：
1. 规则 4 首版只扫 `initializer`，而现场是「声明 + 后续分步调用」，链为空 → 规则 4 静默失效；修法＝加 `ReferencesSearch` 分步调用扫描。
2. 规则 4 的 `MP_CONSUMER_METHOD_NAMES` 首版漏 `selectJoin*`，而现场 wrapper 只被 `selectJoinList` 消费 → `LAMBDA_QUERY` 那半永远不成立（父链测试绿、复现测试四条红）。

**M2/M3 设计要点**：join 族调用行独立承载锚点（与 Maps 族同口径，不让位），但**不进** `BATCH_OPERATIONS`；判定收敛单一来源 `WrapperLocator.isMpjJoinConsumerCall`（名字+实参非空+`hasMpCrudReceiver`），`isSqlCandidate` 复用；`BatchOperationConverter.isJoinFamilyRejected` 最终为**join 族一律拒绝**。锚点测试夹具必须声明 `XxxMapper extends BaseMapper`，否则 `hasMpCrudReceiver` 恒 false。

**脱敏（用户 2026-11-23 要求）**：demo 与测试/文档里的现场类名、方法名、业务串全部换成结构等价的中性命名（`CustomJoinWrapper` / `AbstractLambdaJoinBaseWrapper` / `AbstractJoinBaseWrapper` / `BaseWrapper` / `FrameworkService` / `FrameworkServiceImpl` / `DeviceJoinRepository` / `DeviceStationDTO`，方法 `queryDeviceList*`，异常 `DEVICE_LIST_EMPTY`）；结构要素（四层链、`selectJoinList(Class, JoinBase)`、`getBaseMapper()` 转发、分步调用、leftJoin）不变。第一层命名必须仍以 `*Wrapper` 结尾，否则撞名字前门（详见 [[pitfall-wrapper-type-recognition-simple-name-whitelist]]）。

**合入 master 的冲突与处置（2026-11-25）**：master 已前进 105 笔（到 `2026.11.25` 正式版）。仅三处冲突：CHANGELOG（master 已把 D255/D253/D254 收编进 `[2026.11.25]` 段，`[Unreleased]` 只剩 `52322da26` + 我方 D256 → 取 master 版并只补 D256 一条）、README 与内容营销长文（master 已是同一新 slug `33633-mybatis-sql-visualizer` 且内容更全 → `--ours` 取 master）。master 这 105 笔**没碰** `WrapperLocator`/`WrapperAnchorDetector`/`BatchOperationConverter`/`SqlPreviewService`，自动合并无语义交叉。

**合入后编译失败一例**：`:compileKotlin FAILED — Could not delete build/classes/kotlin/main/cn`，属目录被占用（并行构建或 IDE 持有），非代码错误；处置＝记录原文 + 重试，不要当编译错误查。

**demo 拆独立模块（2026-11-25，用户要求「拆成单独模块 + 补接口好做运行时测试」）**：新模块 `custom-wrapper-demo`（demo 根 pom 已注册；端口 **8083**；独立根包 `cn.song.customwrapperdemo`），自带 entity/mapper/dto/service/controller，复用 `mpj_demo` 库的 `car_device_info`/`station_info`（表由 join-demo 的 `db/schema.sql` 建）；join-demo 里的 customwrapper 包 / DeviceStationDTO / CUSTOM-WRAPPER-REPRO.md 已 `git rm`。四个端点：`/custom/device/list`（业务→Repository→FrameworkServiceImpl 转发，现场结构原样）、`/withJoin`、`/official`（官方 MPJLambdaWrapper + Mapper 直调对照）、`/frameworkDirect`（Controller 直接调 Repository，用来看「最深业务帧=框架转发层」的跳转落点）。编译 `mvnd -o -pl custom-wrapper-demo -DskipTests compile` 通过；demo 仓提交 `946fa5d0`。

**demo 运行时跑通（2026-11-25，用户实测报 500 后修）**：两处根因都在 demo 自身，不在插件。
①`queryDeviceList` 硬编码的过滤值（schemeId=43 + `gateway-01`/`sensor-02`）与 `mpj_demo` 种子数据不匹配——实际只有 4 行（`share_scheme_id=1` 两台「充电桩A-01/A-02」、2/3 各一台），SQL 命中 0 行后抛 `DEVICE_LIST_EMPTY`。
改法＝按种子数据过滤（默认 `schemeId=1`）+ 查空返回**空列表**（现场此处是业务异常，demo 里去掉，否则异常栈会盖住「SQL 已被捕获」这个验证信号）。
②`/withJoin` 另抛 `MybatisPlusException: mapper not find by class <StationInfoEntity>`——**MPJ 的 `leftJoin(Class)` 要求关联实体也有 `MPJBaseMapper`**，只建主表 Mapper 不够，需补 `StationInfoMapper`。
实测：`mvnd -o -pl custom-wrapper-demo spring-boot:run`（8083）四端点全 200（`/withJoin` 返回 `stationName=中心站`），测完自动停服务；提交 `946fa5d0`（模块拆分）、`a17e4212`（端点修复）。

同一轮给出的帧选主结论（未配 skip 落 `FrameworkServiceImpl.selectJoinList`、加了框架包前缀落业务调用行；框架在 JAR 时两栏都不该配）见 [[reference-agent-frame-selection-and-prefix-settings]]。

相关：[[pitfall-wrapper-type-recognition-simple-name-whitelist]]、[[feedback-repro-before-plan]]、[[feedback-repro-fixture-desensitization]]、[[reference-agent-frame-selection-and-prefix-settings]]。