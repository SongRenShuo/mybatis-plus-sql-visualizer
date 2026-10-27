# T-PreviewExecDsDialect：预览执行选择器按数据源方言出 Runner（DM 等非 MySQL 数据源无法从预览执行）

- 占号：O-136/R82/D140/V163（引用前已实测 docs/开发计划 未占用）
- 日期：2026-10-09 晚
- 载体建议：dev-bak 直提（单点小改）或独立工作树；触发场景＝用户在达梦（DM）等非 MySQL 数据源场景下使用「执行 SQL」
- 关联：本计划由 20261009 深夜沙盒复验直接产出（DM 驱动/数据源已在沙盒建好并连通，证据链见下）

## 一、现象（沙盒实测，20261009 深夜）

1. 沙盒（243）里已建好达梦数据源 `mpj_dm@dbx-test-host`（驱动 Dameng DM8 = 本机 `.m2` 的 DmJdbcDriver18-8.1.3.140.jar，Class `dm.jdbc.driver.DmDriver`）：
   - Database 工具窗树中出现该数据源；展开触发内省成功（mpj_demo schema、tables 6）。
   - 数据源属性里 **Test Connection = Succeeded**（DBMS: DM DBMS 8.1.3.12，Driver: dm.jdbc.driver.DmDriver 8.1.3.140，Ping 65 ms）。
   - 该数据源自己的 Query Console 执行设备查询（`SELECT … FROM car_device_info t LEFT JOIN station_info st … WHERE (t.id = 1);`）**返回结果行**（充电桩A-01，1 row，1s 207ms）。
2. 同一条 SQL（来自历史条目、参数已回填 `t.id = 1`、无占位符、可执行形态）在插件预览面板点「执行 SQL」：
   - 弹出的「选择数据源」列表只有 5 个 MySQL 数据源（jpa/mpj/mp/kotlin/selfdev @localhost）+ All，**没有 DM 数据源**。
   - 选择任一 MySQL 数据源则因表不存在报 `[42S02][1146] Table 'mp_demo.car_device_info' doesn't exist`。
3. 附带正面证据：预览执行链路本身是通的——首击即渲染内嵌结果区（Output/Result 1 + 按 SQL 解析出的列 + 即时状态/错误行），T-FirstExecResultInvisible 的修复行为在真机成立。

## 二、根因链（代码定位，实施前先证伪 P1）

- 预览执行走 `DataSourceSelector` 反射平台 `DatabaseRunners.getAttachDataSourceRunners(psiFile, sql, sessionConsumer)` 枚举 Runner（`DataSourceSelector.kt` §getAttachDataSourceRunners）。
- 平台按 **psiFile 的 SQL 方言**过滤 Runner：预览的 SQL 草稿（`mpsql-exec.sql` / console 虚拟文件）由插件创建，方言经 `DialectPreMapping.ensureMapped` **固定映射为 MySQL** ⇒ 平台只为 MySQL 系数据源建 Runner ⇒ DM（及一切非 MySQL 方言）数据源被排除。
- 对照证据：同一 DM 数据源的原生 Query Console（方言=DM）能执行同一 SQL 并出结果行 ⇒ 数据源/驱动/网络全通，堵点只在「Runner 枚举的方言匹配」。

## 三、目标

- 预览「执行 SQL」的数据源选择器**按所选/可选数据源的方言出 Runner**：DM 数据源可被选中并首击出结果数据。
- 不回归：MySQL 数据源的既有预览执行路径（含占位符参数面板、上次使用记忆）零变化。

## 四、方案方向与决策点

- **S1（主方案）：控制台文件方言跟随目标数据源**。在预览执行准备链里，按候选数据源的方言设置 SQL 草稿文件的 dialect/language（DM 数据源 ⇒ DM 方言），使平台 Runner 枚举命中。
  - D1 方言来源：优先数据源在 DAS 的方言（introspection 的 databasePlatform）；兜底用插件 `Dialect.kt` 的 URL/驱动推断（DM 推断已存在并在真机验证过）。
  - D2 落点：虚拟文件创建时（`mpsql-exec.sql` 首建）按「上次使用/默认数据源」设定；选择器弹出前如目标方言≠当前方言则重建/切换 psiFile。须保证执行用的 psiFile 与高亮展示一致。
  - D3 兜底提示：Runner 列表为空时，把现在的「等待结果超时」类提示改为可行动文案（「没有匹配该 SQL 方言的数据源」），不再让用户等 8s 超时。
- **S2（否决备选）：绕开平台 Runner 自建执行通道**（自管 JDBC 连接）。改动面与风险远大于收益（放弃 DAS 的参数/结果/事务生态），不采纳，仅留档。
- 决策点 D4（范围）：是否同时放开「多语句脚本（分页 SQL+COUNT）」在 DM 上的执行。DM 支持与 MySQL 同形的 `LIMIT`，但占位符未填时的行为需实测（见 P4）；如复杂可先支持单语句，多语句维持现状并在文案注明。

## 五、待验证前提（实施者先证伪再动手）

- **P1**：`getAttachDataSourceRunners` 的过滤依据确为 psiFile 的 language/dialect（读 243 平台源码或做最小实验：手工把草稿文件方言改成 DM 后观察 Runner 是否出现）。
- **P2**：方言=DM 的草稿文件 + DM 数据源 ⇒ Runner 出现且执行出结果行（沙盒可直接实验，DM DS/驱动已就位）。
- **P3**：方言跟随数据源后，MySQL 数据源路径零回归（mp_demo/mpj_demo 预览执行仍首击出结果；占位符参数面板行为不变）。
- **P4**：DM 方言下分页双语句脚本（`LIMIT ?,?` + COUNT）的可执行性；占位符未填时的报错形态（决定 D4 的范围收敛）。
- **P5**：`DialectPreMapping` 的「一切映射到 MySQL」在预览链的其他读点（高亮/格式化/执行计划）是否被共享——若共享，方言跟随数据源需要限定作用域，避免高亮链被带偏。

## 六、验收清单（拟）

- **V1**：沙盒对 DM 数据源预览执行——选择器出现 DM 数据源，**单击一次「执行 SQL」即出结果行**（充电桩A-01）。
- **V2**：MySQL 回归——mp_demo/mpj_demo 预览执行仍首击出结果；`testFast` 相关定向零回归。
- **V3**：无可匹配数据源时的提示文案可行动（指明方言不匹配，而非超时）。
- **V4**：守卫/单测：方言跟随逻辑的单测（DM DS ⇒ DM 方言草稿；MySQL DS ⇒ MySQL）+ 反向验证（摘除跟随 ⇒ DM Runner 消失）。

## 七、运行读数

实施于 2026-10-13（工作树 t-o136-dsdialect），读数见 §九 实施记录。

## 八、影响面 / 不做

- 不改捕获链（agent/桥）、不改 MCP 的数据源匹配、不改 MySQL 既有执行行为。
- 不在本计划内解决「DAS 控制台对未配凭据数据源不产结果」的一般性问题（凭据属数据源配置，用户配置后即通；本轮已在沙盒实证 DM DS 配好后可出结果）。
- 达梦 ADD_BATCH/CLEAR_BATCH 织入面缺失（T-DmWeave 已登记的织入局限）与本计划无关，不在此处理。

## 九、实施记录（2026-10-13，工作树 t-o136-dsdialect）

**D140 按推荐定案（20261013 用户开工令）**：D1＝平台 DAS 方言优先＋插件 URL 推断兜底；D2＝草稿方言按「上次使用/默认数据源」设定＋缓存命中路径同款对齐；D3＝空 Runner 二分归因给可行动文案；D4＝多语句脚本维持现状（执行链不渲染分页、占位符未填走既有参数面板，DBN whole-script 原生处理，无需新增文案）。

### 前提证伪结论（动手前逐条核实）

- **P1 成立（字节码实证，243.7.1 DatabaseTools jar 反编译）**：`DatabaseRunners.getAttachDataSourceRunners → DbImplUtil.getAppropriateDataSourcesForExec(psiFile) → getSqlLanguageDialectForExec(psiFile)`（PSI 方言 or `SqlPsiFacade.getConfiguredSqlLanguageDialect(vf)`，即 SqlDialectMappings 文件级映射）→ `DatabaseDialect` → `DbSqlUtilCore.getAppropriateDataSources` 按 `getDatabaseDialect(ds).similarTo(文件方言)` 过滤。Runner 集合确由 psiFile 方言决定；平台自带 `DbSqlUtilCore.findSqlDialect(LocalDataSource)`（getDefaultDialect→findDialectById→guessSqlDialect(URL)）可按数据源取 SQL Language，即 D1 的现成实现面。另证：方言匹配集≠全部数据源时平台返回 "All" 组 Runner（`getSubRunners()`，组项 runnable 为 EmptyRunnable 空操作）——插件弹窗不展开子级，DM 藏在死项里不可选，此为第二处堵点。
- **P5 成立但无共享冲突**：`DialectPreMapping.ensureMapped` 全部 3 个调用点中，exec 链 2 处映射 `exec/mpsql-exec.sql` 按文件、预览链 1 处映射 `preview/` 目录——映射对象不相交。方言跟随只需参数化 exec 链目标语言，预览链保持 MySQL；高亮/格式化/EXPLAIN 与行注释接管（`--`，与方言无关）均不受影响。
- **P2/P4 环境受限未实测**：本环境无 DM 数据源、无真机沙盒（headless Community 测试 JVM 无 Database 插件），DM Runner 出现与 DM 双语句执行留待真机验证（机制层面 P1 已实证，平台 API 面 243 javap 核对）。
- **243 平台 SQL 方言集无 DM**（`sql/dialects/*` 全量 xml 核对）→ URL 兜底表对 DM 只给候选 id ["DM","Dameng"]，平台缺位时诚实落 null 保持 MySQL（不猜近似方言）；真机 262 由主链路（平台 findSqlDialect）承担。

### 变更点（文件:行）

- `ExecDialectRouting.kt`（新增，~215 行）：目标数据源解析（last-used UUID→显示名→默认数据源，纯函数可测）＋目标方言 id 决策（平台 findSqlDialect 优先/URL 兜底/皆无 null）＋组 Runner 展开纯函数与反射生产链＋空 Runner 文案选择纯函数。
- `DialectPreMapping.kt:52-96/165-216/218-288`：`ensureMapped` 增目标方言重载（null=MySQL 旧行为）、`setLanguageOnEdt`/`configuredLanguageId`/`languageById`；两段式线程语义（BGT 读探测稳态跳过/EDT 写）不变，读探测判据泛化为「已配置语言==目标语言」（含 DBN push 自愈）。
- `DataSourceSelector.kt:112-125/1476-1493/1530-1533`：`dialectMappedLanguageId` 字段；`mapDialectBeforePsi` 内部路由（幂等短路收紧为「同 vf 且同方言」）；prewarm 缓存命中路径补对齐。
- `DataSourceSelectorResults.kt:246-257/1189-1194/1368-1371/275-303`：chooseAndPrepare 枚举后展开组 Runner（DM 子 Runner 浮出去重）；createTempPsiFile 缓存命中路径对齐映射；prewarmExecTempFileInner 收口统一入口；空 Runner 二分文案。

### 读数（真实读数，均本工作树实测）

- **件1 先红**：`./gradlew test --tests ...ExecDialectRoutingTest` → `Task :compileTestKotlin FAILED`，`Unresolved reference 'ExecDialectRouting'`（测试先行）。
- **件1 后绿**：同命令 `BUILD SUCCESSFUL`，`tests=18 failures=0 errors=0`；触链回归 ExecSlowOpsRoutingTest 3/ExecTempFileFixTest 6/CachedTempPsiFileTest 3/SqlPreviewPanelPrewarmConsumeTest 3/SqlPreviewPanelPreviewTempFileTest 4 全绿。
- **件1 反向验证**：临时摘除 mapDialectBeforePsi 路由（REVERSE-PROBE）→ `接线守卫_执行文件方言映射走数据源方言路由 FAILED`（tests=18 failures=1）；恢复后 `tests=18 failures=0`。
- **件2 先红**：接线守卫断言失败（`tests=19 failures=1`，首因守卫正则窗口 200 字符小于 intervening 注释长度，放宽 800 后过——守卫窗口按实码校准）。后绿 `tests=19 failures=0`。
- **件2 反向验证**：摘除展开（`runners = enumerated` 探针）→ `接线守卫_弹窗前展开组Runner FAILED`（failures=1）；恢复后绿。
- **件3 先红**：`接线守卫_空Runner分支给方言不匹配文案 FAILED`（tests=20 failures=1）。后绿 `tests=20 failures=0`。
- **件3 反向验证**：dialectMismatch 分支改投 empty 文案探针 → 守卫红（failures=1）；恢复后绿。
- **收口**：`testFast` `BUILD SUCCESSFUL`（**3900 tests / 0 failures / 0 errors**，315 类）；定向终批 8 类（ExecDialectRoutingTest 20、ExecSlowOpsRoutingTest 3、ExecTempFileFixTest 6、CachedTempPsiFileTest 3、ExecDocSync2Test 8、ExecSilentNoopHintTest 5、SqlPreviewPanelPrewarmConsumeTest 3、SqlPreviewPanelPreviewTempFileTest 4）全绿。

### 提交清单

- 0ccf0745 feat(preview): O-136 执行草稿方言按数据源路由出 Runner（件1 路由核心）
- 27973956 feat(preview): O-136 预览执行弹窗展开组 Runner（件2，方言不匹配数据源浮出）
- f3181c55 feat(preview): O-136 空 Runner 归因方言不匹配并给可行动文案（件3，D3/V3）
- 23e614d9 WIP(o136)（看门狗掉线保存收编，内容＝件1 测试文件草稿，与本轮工作树逐字一致，无未落盘成果）

### 未做项与验收边界

- **V1/V2 的真机（DM/MySQL 端到端）未执行**：本环境无 DM 数据源、headless 无 Database 插件；机制层面由 P1 字节码链与守卫测试覆盖，真机复核留待用户沙盒（DM DS 已按 §一 就位）。
- `testDatabase` 通道未跑（集成任务依赖 MySQL 沙盒，本轮按定向纪律只跑定向＋testFast）。**→ 已于 2026-10-14 收口复验，见下节；该缺口当时漏掉了组展开回归。**
- D4 多语句：零代码改动（理由见 D140 定案）。

### 收口复验（2026-10-14，dev 树；取代上节「testDatabase 未跑」条目）

- **testDatabase 首跑抓出回归**：`testDbConsole` 1 例失败（`testPrepareExecuteAndParameterChangeEndToEnd`，报「没有与该 SQL 方言（MySQL）匹配的已配置数据源」＝件3 文案）。dev 运行日志定位＝`[M36] runners=1 expanded to 0 (O-136 group runners flattened)`——平台「All」组在数据源 introspect 完成前子 Runner 为空，被件2 的展开剔成 0 个 Runner，控制台执行链当场终结（落 `runners.isEmpty()` 归因分支）；方言映射侧本身正确（日志 `dialect pre-mapped to MySQL … ds=null`，零回归）。
- **对照实验定性**：master 树（无 O-136）同一用例通过（tests=1 fail=0 err=0 skip=0）→ 回归坐实，非既有环境红。
- **修复**：`ExecDialectRouting.expandGroupRunners` 空子项组**保留原项**（`subs.isNullOrEmpty()` 与「非组项」同支）——无子项可浮出时组项仍是唯一执行句柄，剔除即清空列表并终结链。先红＝单测新例「组Runner展开_空子Runner的组项保留」20 例 1 失败（旧实现剔除）→ 后绿＝BUILD SUCCESSFUL in 16s。旧单测「空子 Runner 的组项剔除（空组项是死项）」系把缺口钉成契约，已按集成实证纠正为保留语义。
- **复跑读数**：`testDatabase --no-configuration-cache` **10/0/0/0**（testDbConsole 1、testDbSelector 5、testDbDiagram 1、testDbExecDocGate 1、testDbDriverPipeline 2；**零跳过＝断言真跑**，本地 MySQL 在位）BUILD SUCCESSFUL in 6m42s；全量 `testShards --no-configuration-cache` 复跑读数回填盘点 74 版。
- **教训**：定向单测 20 例全绿仍漏掉生产链回归——「组展开遇空子项」形态只在 DB 集成链出现（单测喂的桩对象恒有子项）；验收位若当时补跑 testDatabase 即可当场拦住。

