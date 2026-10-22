# T-M19 / V178 真实 DatabaseTools 非 UI 补验

## 范围与运行前提

基线 `dev@a434dc2c`，独立分支 `t-m19-v178-nonui`。没有启动 runIde、GUI 验收、全量、verify、clean 或 --stop；不合并、不推送。首阶段真实平台为IU-243.28141.41；后续已追加253真实八例、262显式重排classpath对照八例及243 H2/自然注入实跑，详见后文续验节。签名证据始终与服务运行证据分开。

普通 test/testFast 的 classpath 主动排除 DatabaseTools，因此不具备证明真实 SQL PSI 的条件。`testDatabase` 是五个 `testDb*` 任务的聚合，而非一个 Test 任务。四类 Console/Selector/Diagram/ExecDoc 默认读取 demo 私有 `.idea/dataSources.xml` 或 `.local.xml`，部分依赖 `car_device_info/station_info`；本次没有运行这些读取路径。只运行既有 `testDbDriverPipeline`，并在同类追加真实平台 fixture。驱动种子读取的仅是驱动清单与 jar，不读取数据源密码文件。

`DbIntegrationProjectSupport` 提供真实平台工程目录、VFS、exec 文档及 headless editor 生命周期支持，不是数据库实现替身；它不能取消 SQL 方言扩展的初始化契约，也不能让私有配置读取变成已授权。本次无连接 fixture 使用平台默认临时工程，合成 URL 指向 loopback 端口 1，禁自动同步，不连接、不访问用户库、不安装 ForTest hook、不伪造 DbDataSource/SqlFile/session/console。

每次 Gradle 前执行 parallel_tasks.py check/register，独立 sandbox；经 gradle-gate max2、in-process 后台执行。命令模板（工程绝对路径以本树为根）为：

```text
gradlew.bat -p <本树> testDbDriverPipeline --tests cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest -PgifRecord=false -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

## 实际失败链与修复

1. 首次真实 DatabaseTools 加载后，索引初始化抛 `IllegalArgumentException: Dbms not found: DM`，Light fixture 等待索引无法结束。`sql.dialect` 的 dbms 属性是平台 Dbms 标识，不是语言 ID。三版发行物均以 UNKNOWN 注册 Sql92/Generic；产品配置错误不能登记为环境受限。保留 `build/v178-first-threads.txt`、`build/v178-dm-first-red-idea.log`。
2. 改为 `dbms=UNKNOWN order=last` 后，第二轮抛 `NoSuchFieldException: INSTANCE`。真实 `DbmsExtension.InstanceBean.doGetInstance` 执行 `Class.getField("INSTANCE").get(null)`，不是 new 普通构造器。补 public static 单例（Kotlin `@JvmField`）与私有构造；保留 `build/v178-second-threads.txt`、`build/v178-dm-second-red-idea.log`。仅停止命令行包含本树的测试 worker，没有停止共享 Gradle daemon。
3. 两项修复后首份完整 XML 为 5 tests / 2 failures / 0 skipped。驱动解析、种子检查、真实断开态 session/console API 已通过。两条新 fixture 前提不成立：AWT 的 headless 标志不等于 IDEA 测试模式；无驱动数据源与未映射的 SQL 文件会被平台方言相似性规则筛掉。后续纠正 fixture 使用平台 headless 标志和真实 PostgreSQL driver/mapping，不放宽产品断言。

DM 仍是 Generic/SQL92 兼容语言，不是原生达梦 parser；插件 DM 渲染和 JDBC URL 厂商判定未变。order=last 用于不夺取平台 UNKNOWN 默认 Generic。

## 三版本实际 API 检查

既有 `PlatformSignatureChecker` 追加候选读取链精确 owner/参数/返回类型：DbDataSource.getUniqueId/getName/getQueryLanguage/isValid/getConnectionConfig，以及 RawConnectionConfig.getUrl。用每版自带 JBR 运行独立 checker，不启动平台 UI。

| 发行物 | 结果 | connection config owner |
|---|---|---|
| IU-243.28141.41 | RESULT OK，进程 0 | com.intellij.database.model.RawConnectionConfig |
| IU-253.33514.17 | RESULT OK，进程 0 | com.intellij.database.model.RawConnectionConfig |
| IU-262.10968.63 | RESULT OK，进程 0 | com.intellij.database.model.RawConnectionConfig |

日志为本会话 `call_F9VVBBVdkZxhatnUzqx5DZnO-stdout.log`，三目标退出 `[0,0,0]`。此结果不证明三版本 injected SQL PSI、运行时 provider 或真实 Runner 查询执行。

## 桥错包名与 243 scope 语义实证

第四轮真实 SqlDataSourceMappings 已返回唯一 UUID，而生产桥返回 FAILED，diagnostic=ClassNotFoundException。`build/v178-bridge-red.xml` 保存了这一首红。生产仅将 `com.intellij.database.dataSource.RawConnectionConfig` 改为三版真实 owner `com.intellij.database.model.RawConnectionConfig`，没有修改候选融合优先级或降级顺序。

第五轮真实目录 PostgreSQL → 文件 MySQL 覆盖 → 撤文件覆盖继承 PostgreSQL，以及重新取得 SqlFile 的语言均通过。读取必须等平台索引/属性推送完成；setMapping 返回不等于异步文件属性已经更新，测试调用平台 IndexingTestUtil，未人为改持久属性。

另一个计划前提被推翻：243 设置 `SqlResolveMappings=TreePattern.EMPTY` 后，真实 SqlDataSourceMappings.getDataSources 仍返回该 PG 数据源。反编译 `SqlDataSourceMappings.getDataSourcesForResolve → DbSqlUtilCore.getAppropriateDataSources` 可见该入口按有效方言筛选，不读取 SqlResolveMappings。测试现在严格比对桥与真实平台返回值，不虚构“EMPTY scope 已清空候选”。桥的 configuredScopePresent 与 CONFIGURED_RESOLUTION 分类能读到配置存在，但这不能证明最终候选已被 resolution scope 缩窄。这是 V178-1 的剩余产品语义核实项，不归入 UI 禁令或 classpath 环境限制。本轮没有擅改决策优先级。

## 最终定向结果

计数顺序为 tests/failures/errors/skipped。

| 实际执行 | 读数 | XML UTC 时间 | 证据 |
|---|---|---|---|
| 243 真实 DatabaseTools 五项，恢复桥修复后 | 5/0/0/0 | 2026-10-15T18:31:15.831Z | build/test-results/testDbDriverPipeline/TEST-cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest.xml |
| 真实模块身份/重命名/销毁定向 | 5/0/0/0 | 2026-10-15T18:27:07.144Z | build/test-results/test/TEST-cn.song.mybatisplus.visualizer.context.ModuleIdentityResolverTest.xml |
| DM 注册/既有 URL 路由定向 | 3/0/0/0 | 2026-10-15T18:31:14.605Z | build/test-results/testFast/TEST-cn.song.mybatisplus.visualizer.sql.DmDialectRegistrationWiringTest.xml |
| 撤回桥包名修复，仅真实 scope/UUID 一项 | 1/1/0/0 | 2026-10-15T18:29:11.730Z | build/v178-bridge-reverse-red.xml，ClassNotFoundException |

恢复后 DB5+DM3 同命令退出0（日志 `call_gOJWiqk1DZKvOhwvLcCEgZyi-stdout.log`），不是全量。模块5取较早同轮独立任务 XML，其后未修改模块代码/测试，不重跑冒充新读数。共13条不同定向用例，零跳过。未进入 GUI，也未向数据库提交 SQL。

DM 两次初始化失败是产品错误，已经修复，不作为环境限制销账；三版发行物的 InstanceBean 均读取 INSTANCE（262 位于 intellij.database.jar，243/253 位于 database-plugin.jar）。桥候选完整链在修前红、修后绿、撤修再红、恢复再绿，未使用 stub。映射重解析在真实 SqlFile 上断言，不只检查设置值。断开态 session/console 是真实工厂对象，但没有执行查询，不能等同 active connected Runner 双闸验收。

## 续验：关闭 configured scope 误报强证据

此节取代上文“产品语义待核”的处理状态。真实 fixture 同时注册 PostgreSQL/MySQL 两个无连接数据源，对 PG SqlFile 分别写入 EMPTY、限定 MySQL UUID、显式 ALL。UUID pattern 通过真实 `SqlImportUtil.getDataSourceName(mysql)` 与 `createDataSources` 构造（字节码确认使用 quoted uniqueId，不按显示名猜）。三项均先确认实际 provider 返回空、实际 getDataSources 只返 PG，然后调用生产桥和生产决策：修前 **3/3/0/0**，均错误输出 RESOLVED/STRONG/IDEA_CONFIGURED_SCOPE PostgreSQL。首红 `build/v178-scope-first-red.xml`。

单一安全处理：存在明确 configured mapping、但无真实 provider 时，桥将该层标为 UNAVAILABLE，决策不得通过有效方言、已学绑定或项目候选把它重新升级为已验证。手动方言与精确捕获事实仍按原优先级；真实 provider 的分支仍先于配置存在性判断。未设置 mapping 的默认 ALL 不进入降级，清除配置后恢复原默认行为。保留平台原候选供诊断，但不声称它们是 scope 已限定的集合。没有猜解 TreePattern、没有选择第一个候选。

预览状态行与详情明确说明“无法可靠解析 IDEA 已配置的解析作用域；请手动指定方言或选择明确的执行目标”，仍显示兼容预览未验证。不是宣称已经支持显式 scope 解析。

三版精确签名新增：继承的 getConfiguredMapping(VirtualFile) 擦除返回 Object、声明 owner 均 PerFileMappingsBase；getDefaultMapping 与 EMPTY/ALL 常量实际类型均 TreePattern；DataSourceProvider.getDataSources(PsiFile) 与 getProvidedDataSources 返回 List。243/253/262 新门均 RESULT OK，日志 `call_nu2myo8fCEwn3b4UJBccpI78-stdout.log`。真实 JdbcConsoleDataSourceProvider 属 virtualFileDataSourceProvider，不能混作 sql.dataSourceProvider 或伪造其非空返回；本次三配置场景真实 sql provider 返回空。

修复首次平台 **8/0/0/0**；撤回关键分支再次 **3/3/0/0**，仍错误 STRONG（`build/v178-scope-reverse-red.xml`，UTC 18:49:12）；恢复后最终真实平台 **8/0/0/0**（UTC 18:51:42.947），四类定向回归 Registry 2、Bridge 7、Decision 7、StatusText 2，合计 **18/0/0/0**，命令退出0（`call_iRtweb70Sd78Ve7fZFAbxJuv-stdout.log`）。真实测试同时验证三配置清除后恢复默认路径、手动 Oracle 和 captured MySQL 不受损、可见状态包含专用诊断。XML 观测实际 sql provider 扩展列表为 `[]`；因此非空真实 provider 在本 fixture 未覆盖，未人为注册伪 provider 换绿。纯测试仅回归既有手动/provider优先级，安全修复主证据是以上真实服务、真实模式与真实 PSI，不是手造快照。

## 续验：253/262 真实运行而非签名替代

使用同一独立树 `d38c1adc` 的 scope 安全代码，不改主控生产冻结。保留根编译基线243，只临时在既有 testIde 的 testDbDriverPipeline 设置 `localPath` 与精确 `testFramework(Platform, version)`，API依据本地 IPGP 2.18.1 sources.jar；不使用 gifRecord，不启动 runIde。临时构建修改不提交。

253 目标为缓存 IU-253.33514.17、自带 JBR21.0.10。首次仅设置 localPath 时框架仍取根243，初始化报 Java11Shim 缺失；这是测试框架混装，不能算产品失败。显式锁253.33514.17后八例实际入场，七例通过，一例旧驱动夹具方法失配。253把 loader 的一参 List<File> 改为两参 Context/Collection<Path>，driver/source classpath读取迁至 DatabaseDriverClasspathManager；测试按真实jar javap适配，并仍要求真实jar路径存在与版本匹配，未用stub。

253最终 **8/0/0/0**，UTC `2026-10-15T19:55:38.633Z`，用时8.942s，Gradle退出0。证据 `build/v178-253-eight-green.xml`、`build/v178-253-idea.log`、日志 `call_CwIEoc1YbED03Li5vpWCcmSP-stdout.log`；243旧八例另存 `build/v178-243-eight-green.xml`。测试适配提交 `39ea87e4`。

262目标缓存 IU-262.10968.63、自带JBR25.0.4、精确framework 262.10968.63。第一次实际八例为 **8/8/0/0**，均在 project startup 初始化失败，还未执行 DatabaseTools 断言。原XML `build/v178-262-first-red.xml`，日志 `call_pgMhUQQUihb6zEUv8wcMrOxQ-stdout.log`。

根因不是缺DatabaseTools：平台 `ultimate-plugin.jar` 注册 `postStartupActivity implementation=Z.Z.Z.Z.Z`，而核心 `product-backend.jar` 也含同名混淆class。IPGP测试把平台与插件放到同一个PathClassLoader；核心在前，加载的类无构造器，抛“Cannot find suitable constructor”，正确ultimate版本有 `<init>()V` 与 `execute(Project,Continuation)`。原加载顺序为核心classpath在前、bundled插件在后（IPGP TestIdeTask源码）；只做一次单变量对照，前置同一发行物真实ultimate jar，不删除EP/不stub。即便通过，也仅是显式重排测试classpath的fixture，不是标准IDE装配验证。

| 262发行物 | jar SHA256 | Z/Z/Z/Z/Z.class SHA256 |
|---|---|---|
| lib/product-backend.jar | 77a4606272b12bf98d71a9aa8e1734d22d6a3ab151fd689a35849b74feb958ef | 9dd2ed07ce84cdba2f4366c26d689beb717a04a32dde452ee4b2e0740d81afb4 |
| plugins/ultimate-plugin/lib/ultimate-plugin.jar | 9906a69af6aa17c800780b5c6ab1945cb0b7b99862635e78850e4ae674cc2194 | fb020cc7a80881ebc627553a09e10fb69e80277c69e7f81aa727a0c0ff04aa25 |

方法表直接解析真实classfile获得（本机javap24不能读major69，发行JBR无jdk.jdeps模块，未拿这些工具限制当产品问题）。核心版本仅有混淆方法 `Z(...)V`；插件版本有无参构造、execute、静态初始化等。对照最终 **8/0/0/0**，UTC `2026-10-15T20:08:09.797Z`、12.212s，Gradle退出0。证据 `build/v178-262-reordered-eight-green.xml`、`build/v178-262-reordered-idea.log`、日志 `call_4sKiLPHeBO9NJFeOKvW9CSI6-stdout.log`。XML打印实际 build=IU-262.10968.63、java=25.0.4、fixture来源精确262.10968.63/test-framework jar，失败扩展class实际来自ultimate-plugin.jar。八条断言均入场且零skip。结论只限“显式重排测试classpath下的262 fixture通过”，原默认组装startup失败保留，不能写成标准IDE装配真机通过。

### 跨版重放命令与证据锁定

临时 task 配置仅对 testDbDriverPipeline 生效：`localPath.set(file(v178PlatformPath))`、`testFramework(Platform,v178FrameworkVersion)`；262对照另在Test.classpath前置 `files(v178UltimateFirst)`。测试仍编译于243，无gifRecord平台切换。命令均经parallel check/register和gradle-gate max2/in-process后台执行，未与主控另开第二路。

```text
testDbDriverPipeline --tests cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest
-Pv178PlatformPath=<缓存idea-2025.3.5-win绝对路径> -Pv178FrameworkVersion=253.33514.17
-PgifRecord=false -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

262同命令换对应缓存路径/framework版本，并仅对照轮加 `-Pv178UltimateFirst=<同发行包>/plugins/ultimate-plugin/lib/ultimate-plugin.jar`。所有缓存路径和输出位置均在本节上文与日志可核，非安装日常IDE修改。

| 锁定XML | SHA256 |
|---|---|
| v178-243-eight-green.xml | e91bc8387f3809f2daef1187c11b21a4761f72c3692dc9fdb10b7e1fdc9dc1c4 |
| v178-253-eight-green.xml | ad4a0aea0f7e8f4f00781b4fe420a7c289d535b26baa62fcad162d4242cfd782 |
| v178-262-first-red.xml | 4f5cbd70396776592966d2bf6d4fce89e8912cc6fa723bf88db55ceb52addc59 |
| v178-262-reordered-eight-green.xml | 3ea1df9a17422ce7e3bc72c04a399594f9d0de5ba6ce58b82addd318211c291a |

## 续验：243 平台连接与自然宿主注入

独立真实probe **4/0/0/0**，UTC `2026-10-15T20:14:18.425Z`，35.573s，Gradle退出0。原结果 `build/test-results/testDbDriverPipeline/TEST-cn.song.mybatisplus.visualizer.execute.DbHeadlessConnectionProbe.xml`，锁定副本 `build/v178-243-h2-natural-green.xml`，日志 `call_oQE90GUyGZ987kIjRru0v5Nw-stdout.log`。后续testDbDriverPipeline会覆盖原结果目录，复核优先用锁定副本。

- 本地H2正对照：真实H2 2.3.232 jar挂载到平台h2.unified复制驱动，去掉在线artifact；仅 `jdbc:h2:mem:<随机名>;DB_CLOSE_DELAY=0`、sa空密码，无业务配置。真实 `DatabaseConnectionManager.Builder.createBlockingNonCancellable` 返回连接，真实RemoteStatement执行 `SELECT 42 AS answer` 得42，`EXPLAIN SELECT 42 AS answer`返回 `SELECT 42 AS "ANSWER"`。读连接的实际数据源UUID，并与真实fromSession/fromConsole目标UUID及H2方言对齐。查询有10秒驱动超时，连接/查询分别60/20秒平台有界等待，finally释放console、连接、数据源。
- 关键边界：日志 `sessionConnected=false`。此次证明平台真实连接管线执行SQL与同源目标API，不证明console自有session已连接，不证明用户Runner点击/提交双闸。没有把不同连接拼成同次Runner成功。
- 自然注入正对照：IntelliLang=true，SQL语言是真实 `com.intellij.sql.psi.SqlLanguage`；Java `// language=SQL`、Kotlin同注释、XML `<!-- language=SQL -->` 均由平台自然生成 `com.intellij.sql.psi.impl.SqlFileImpl`，language=GenericSQL。宿主分别是Java PSI、KtFile、XmlFileImpl，没有注册测试injector、PlainText替身或ForTest缝。Java注入结果进一步进入生产桥，actualSqlPsi=true、AVAILABLE。
- 关键边界：Java实际providers=[]；不证明非空provider分支、MyBatis Mapper特定注入规则、253/262自然注入、注入后的目录映射继承全部成立。三种简单language注释宿主已证，不扩写为完整V178注入矩阵。

可重放探针保存于既有 `dev-support/DbHeadlessConnectionProbe.kt`，不进入默认测试发现，避免普通test剥离DatabaseTools时误加载。重放步骤：在独立树临时复制到 `src/test/kotlin/cn/song/mybatisplus/visualizer/execute/`；将既有testDbDriverPipeline的FQCN filter临时替换为 `cn.song.mybatisplus.visualizer.execute.DbHeadlessConnectionProbe`；经登记和gate运行该任务加同名`--tests`。使用243默认平台，无其他四类业务配置测试。跑完删除临时test源副本、还原构建配置。probe源码直接保存本次实跑内容，不将失败改成skip。

## 证据边界与收尾

最终相同测试适配回归243 **8/0/0/0**，UTC `2026-10-15T20:16:33.950Z`、7.242s，命令退出0；锁定 `build/v178-243-final-eight-green.xml`，日志 `call_YYYEhzQD16dRRcKODZtT2PHb-stdout.log`。XML实际build243.28141.41、JBR21.0.7、framework243.28141.41。临时build.gradle.kts/buildSrc配置全部还原，未提交平台切换或jar重排默认值，生产文件未改。

真实合成配置、真实SqlFile、真实映射和断开态session/console已在243、253及限定重排的262实跑；243额外证明了平台H2连接查询与三种简单自然宿主注入。它们不等于Runner选择UI、active console自有session查询/切库、二击重渲染、结果页、取消查询或视觉矩阵。非空provider、253/262自然宿主注入及标准262装配未由本次证据证明。DBX的MySQL8.0.12与PostgreSQL16.14/dbx_test引擎语义由主控独立记录，本任务未读取其凭据，不能拼接为IDE Runner端到端成功。
