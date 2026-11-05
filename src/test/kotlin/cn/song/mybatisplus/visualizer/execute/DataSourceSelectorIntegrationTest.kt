package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.testutil.DbTestCredentials
import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DbCredentialManager
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DataSourceSelector 数据源**选择/发现**层集成测试骨架（M36.5.23 续）。
 *
 * 与 [DbConsoleIntegrationTest]（覆盖 prepareConsole→executePrepared 控制台执行链）互补，本骨架
 * 专门验证此前只能人工沙盒验收的**数据源选择契约**——即 `DataSourceSelector` 依赖的真实平台集成点
 * `com.intellij.database.console.DatabaseRunners.getAttachDataSourceRunners`：
 *
 *   1. **无数据源** → prepareConsole 回调 false + onError 非空（打开 Database 工具窗引导），
 *      不抛异常、不 NPE（无 MySQL 也可验证的选择降级路径）。
 *   2. **单数据源** → 真实平台 API 返回恰好 1 个 runner，且 runner 显示名含数据源名；
 *      prepareConsole 静默复用（不弹选择框）→ isPrepared()=true 且 sharedDataSourceName==数据源名
 *      （证明「按单数据源自动选中」的选择决策正确，无弹窗）。
 *   3. **多数据源** → 真实平台 API 返回 N 个 runner（选择分支的输入：N 个 → 弹出选择框）。
 *   4. **控制台编辑器不可得**（T-TS-09）→ prepareConsole 必须回调 onError，不静默挂死
 *      （修复前 `prepareInConsole` 两处静默返回，调用方无任何回调）。
 *
 * 运行环境：`testDatabase` 任务（独立沙盒、Database 插件真实加载、复用 runIde 沙盒 MySQL 驱动）。
 * 默认 `test` 任务已排除本类（其 classpath 剥离 database jar，否则类加载即 NoClassDefFoundError）。
 *
 * 环境门槛（与 DbConsoleIntegrationTest 一致）：
 *   - URL/用户名/驱动：解析 demo 项目 `.idea/dataSources.xml` + `dataSources.local.xml`
 *     （系统属性 `mpDemoProjectDir`，默认兄弟目录）。
 *   - 密码：硬编码 `root`（本地开发库无敏感性）。
 *   - MySQL 未启动 / 库未初始化 / RegionUrlMapper 网络失败：DB 相关断言 assumeTrue skip（不 fail）；
 *     无数据源路径（用例 1）不依赖 MySQL，始终运行以证明集成骨架本身可执行。
 *
 * 运行：`./gradlew testDatabase`
 */
class DataSourceSelectorIntegrationTest : BasePlatformTestCase() {

    /** introspect 期间持有的连接守卫（close 即释放连接，见 [introspectDataSource] 内注释）。 */
    private var introspectRef: GuardedRef<*>? = null

    /** 控制台编辑器夹具的持有标记（造/释放见 [DbIntegrationProjectSupport.installExecEditor]）。 */
    private var execEditor: Editor? = null

    /**
     * 稳定 light 工程目录（testDatabase 通道五类共用，根因见 [DbIntegrationProjectSupport] 类注释）：
     * 默认描述符下工程目录 = JVM 里首个用例的 unitTest 临时目录，被属主用例 tearDown 递归删除。
     */
    override fun getProjectDescriptor(): LightProjectDescriptor = DbIntegrationProjectSupport.descriptor

    override fun setUp() {
        super.setUp()
        DbIntegrationProjectSupport.allowRootAccess(testRootDisposable)
    }

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    private val dbPassword: String
        get() = DbTestCredentials.password("root") // 从 demo 项目 application.yml 复制（root/root，本地开发库无敏感性）

    /** demo dataSources.xml 中目标数据源的连接信息。 */
    private data class DataSourceInfo(val url: String, val user: String, val driverClass: String)

    private fun readDemoDataSource(namePart: String): DataSourceInfo? {
        val xml = demoProjectDir.resolve(".idea/dataSources.xml")
        val localXml = demoProjectDir.resolve(".idea/dataSources.local.xml")
        if (!xml.isFile) return null
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        var info: DataSourceInfo? = null
        val sources = doc.getElementsByTagName("data-source")
        for (i in 0 until sources.length) {
            val node = sources.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            if (!name.contains(namePart)) continue
            var url: String? = null
            var driver: String? = null
            val children = node.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j)
                when (child.nodeName) {
                    "jdbc-url" -> url = child.textContent.trim()
                    "jdbc-driver" -> driver = child.textContent.trim()
                }
            }
            if (url != null) info = DataSourceInfo(DbTestCredentials.url(url), DbTestCredentials.user("root"), driver ?: "com.mysql.cj.jdbc.Driver")
        }
        if (info != null && localXml.isFile) {
            val local = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(localXml)
            val nodes = local.getElementsByTagName("data-source")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                if (!name.contains(namePart)) continue
                val children = node.childNodes
                for (j in 0 until children.length) {
                    if (children.item(j).nodeName == "user-name") {
                        val user = children.item(j).textContent.trim()
                        if (user.isNotEmpty()) info = info?.copy(user = user)
                    }
                }
            }
        }
        return info
    }

    /** 编程创建数据源（LocalDataSourceManager + 凭据注入），返回 DBN LocalDataSource。 */
    private fun createDataSource(info: DataSourceInfo, nameSuffix: String = ""): LocalDataSource {
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_demo@localhost-itest$nameSuffix"
        ds.url = info.url
        ds.driverClass = info.driverClass
        ds.username = info.user
        ds.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(ds, "mysql.8")
        ds.resolveDriver()
        // T-TestDbDriver：驱动种子文件 + 驱动 artifact 版本钉定（管线全貌见助手 KDoc）
        DbIntegrationSeedSupport.prepareDriverPipeline()
        ds.workingDir = project.basePath
        ds.passwordStorage = LocalDataSource.Storage.MEMORY
        mgr.addDataSource(ds)
        DbCredentialManager().setPassword(ds, OneTimeString(dbPassword), LocalDataSource.Storage.MEMORY)
        return ds
    }

    /**
     * 触发数据源 introspection（连接 + schema 加载）——delegate 切换为 SqlDataSource 后
     * `containsFile(vf)` 过滤才通过，runner 列表才非空。MySQL 未启动 → 返回失败原因（assumeTrue skip）。
     */
    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val mgr = DatabaseConnectionManager.getInstance()
        val builder = mgr.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>> {
            val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                com.intellij.openapi.util.Computable<GuardedRef<*>> { builder.createBlockingNonCancellable() },
                indicator,
            )
        }
        val ref = future.get() ?: return@runCatching "createBlocking future null"
        val loaded = waitUntil(90_000) { !LocalDataSourceManager.getInstance(project).isLoading(ds) }
        // 不在此 close：GuardedRef.close() 释放连接（DatabaseConnectionManager.releaseConnection 语义，
        // 见 Builder.createBlockingNonCancellable 返回 GuardedRef<DatabaseConnection>），引擎随之终止；
        // 控制台/session 是连接的下游——连接先死则控制台 attach 后立刻被 DBN 回收（20260918 实测
        // `console DISPOSED right after attach: disposed=true`）。守卫留到 tearDown 关闭，
        // 等价真机里用户会话持着连接（用例结束即释放，不跨用例泄漏）。
        introspectRef = ref
        if (loaded) null else "isLoading 未结束（90s 超时）"
    }.getOrElse { e ->
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        "introspect 失败: ${cause.javaClass.simpleName}: ${cause.message}"
    }

    /** 轮询直到条件满足或超时；返回是否满足。 */
    private fun waitUntil(timeoutMs: Long, intervalMs: Long = 300, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /**
     * 反射探针与收敛等待的实现已收编 [DbIntegrationProjectSupport]
     * （discoverRunners / runnerName / awaitRunners）——本类与另外两个 DB 集成类共用同一份探针，
     * 语义不变：直接调真实平台 API `DatabaseRunners.getAttachDataSourceRunners`，不绕插件封装。
     */

    /** 反射读取 DataSourceSelector 的 object 私有字段（sharedDataSourceName/preparedConsole）。 */
    private fun selectorField(name: String): Any? =
        DataSourceSelector::class.java.getDeclaredField(name).apply { isAccessible = true }.get(DataSourceSelector)

    // ───────────────────────────── 用例 ─────────────────────────────

    /**
     * 无数据源路径（选择降级契约）：不创建任何数据源，prepareConsole 应回调 false + onError 非空，
     * 且不得抛异常/NPE。仅依赖 Database 插件已加载（testDatabase 提供），**不依赖 MySQL**，始终运行。
     *
     * T-ExecDocGate：本用例前置显式清空数据源——LocalDataSourceManager 是应用级，兄弟用例
     * （本类其它用例 / DbConsoleIntegrationTest / ExecDocGateIntegrationTest）createDataSource
     * 建的数据源跨用例可见；此前它们因 driver 解析不出去而被 DbImplUtil 过滤「隐形」，驱动种子
     * 修复后 resolveDriver 生效、残留变可见 → runners 非空 → 走弹窗分支（headless 无 frame
     * → NPE 红）。契约是「无数据源」路径，就须保证环境确实无数据源。
     *
     * 20260918：清空后还须**有界等待发现链收敛到 0**（见 [awaitRunners]）——否则取样落在
     * facade 尚未跟上的中间态上，仍会走弹窗分支。
     */
    @Test
    fun testNoDataSourceReportedAsUnavailable() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val removed = DbIntegrationProjectSupport.clearDataSources(project)
        val psi = DbIntegrationProjectSupport.ensureExecFile(project, "SELECT 1")
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null)
        val runners = DbIntegrationProjectSupport.awaitRunners(psi!!.second, "SELECT 1", expected = 0)
        assertEquals(
            "清空数据源后平台不应再发现 runner（清掉=$removed，残留=${DbIntegrationProjectSupport.dataSourceNames(project)}，" +
                "发现=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}）",
            0,
            runners.size,
        )
        var cbResult: Boolean? = null
        val errors = mutableListOf<String>()
        DataSourceSelector.prepareConsole(
            project = project,
            sql = "SELECT 1",
            onError = { errors.add(it) },
            callback = { cbResult = it },
        )
        // 无数据源：选择逻辑走到 runners.isEmpty() 分支 → 打开 Database 工具窗 + onError + callback(false)
        assertNotNull("prepareConsole 应回调（无论成败）", cbResult)
        assertEquals("无数据源时 prepareConsole 应回调 false", false, cbResult)
        assertTrue("无数据源时 onError 应非空（引导用户配置数据源）", errors.isNotEmpty())
        assertFalse("无数据源时不应标记为已准备", DataSourceSelector.isPrepared())
    }

    /**
     * 单数据源发现契约：真实平台 API `getAttachDataSourceRunners` 返回恰好 1 个 runner，
     * 且 runner 显示名含数据源名。验证选择逻辑「单数据源」分支的输入正确（DB 相关，环境门控）。
     *
     * 20260918：① 前置清空 + 数据源名带唯一后缀——名字断言才有区分度（同名残留会让「含数据源名」
     * 恒真）；② 发现结果有界等待收敛（[awaitRunners]）。
     */
    @Test
    fun testSingleSourceDiscoverableByRunner() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        DbIntegrationProjectSupport.clearDataSources(project)
        val ds = createDataSource(info!!, "-single")
        val introspectError = introspectDataSource(ds)
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: $introspectError")
            return
        }

        val sql = "SELECT id FROM car_device_info WHERE id = 1"
        val psi = DbIntegrationProjectSupport.ensureExecFile(project, sql)
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null)
        val (_, psiFile) = psi!!

        val runners = DbIntegrationProjectSupport.awaitRunners(psiFile, sql, expected = 1)
        assertEquals(
            "单数据源时平台应返回恰好 1 个 runner（实际=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}，" +
                "应用级数据源=${DbIntegrationProjectSupport.dataSourceNames(project)}）",
            1,
            runners.size,
        )
        val name = DbIntegrationProjectSupport.runnerName(runners.first())
        assertTrue("runner 显示名应含数据源名 '${ds.name}'，实际='$name'", name.contains(ds.name))
    }

    /**
     * 多数据源发现契约：创建 2 个数据源，真实平台 API 返回 2 个 runner。
     * 验证选择逻辑「多数据源」分支的输入（N 个 → 弹出选择框）正确（DB 相关，环境门控）。
     *
     * 20260918：前置清空 + 两个唯一后缀名 + 有界收敛（同单数据源用例）；tearDown 的
     * `FileSystemException: 另一个程序正在使用此文件` 随稳定工程目录一并消失
     * （根因见 [DbIntegrationProjectSupport] 类注释——该异常与断言无关，是工程目录被删）。
     */
    @Test
    fun testMultiSourceDiscoveryReturnsAllRunners() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        DbIntegrationProjectSupport.clearDataSources(project)
        val ds1 = createDataSource(info!!, "-multi1")
        val ds2 = createDataSource(info, "-multi2")
        val e1 = introspectDataSource(ds1)
        val e2 = introspectDataSource(ds2)
        // T-ExecDocGate：显式绿跳（同上）
        if (e1 != null || e2 != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: ds1=$e1, ds2=$e2")
            return
        }

        val sql = "SELECT id FROM car_device_info WHERE id = 1"
        val psi = DbIntegrationProjectSupport.ensureExecFile(project, sql)
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null)
        val (_, psiFile) = psi!!

        val runners = DbIntegrationProjectSupport.awaitRunners(psiFile, sql, expected = 2)
        assertEquals(
            "多数据源时平台应返回 2 个 runner（选择分支输入，实际=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}，" +
                "应用级数据源=${DbIntegrationProjectSupport.dataSourceNames(project)}）",
            2,
            runners.size,
        )
        val names = runners.map { DbIntegrationProjectSupport.runnerName(it) }
        assertTrue("runner 列表应含 ds1=${ds1.name}，实际=$names", names.any { it.contains(ds1.name) })
        assertTrue("runner 列表应含 ds2=${ds2.name}，实际=$names", names.any { it.contains(ds2.name) })
    }

    /**
     * 单数据源静默选择契约：prepareConsole 对唯一数据源应**不弹选择框**直接选中 →
     * isPrepared()=true 且 sharedDataSourceName==数据源名。证明选择决策正确（DB 相关，环境门控）。
     *
     * 20260918：前置清空 + 唯一名 + 有界收敛，另加**控制台编辑器夹具**——控制台文档即 exec 文件
     * 文档（`JdbcConsole.getDocument()` 字节码实证），headless 下无人为它建编辑器 →
     * `prepareInConsole` 的 `getEditors(consoleDocument)` 恒空 → 静默返回 → 120s 不就绪且
     * errors=[]（基线 idea.log `[M36] editor=null (async wait)` 实证）。
     */
    @Test
    fun testPrepareConsoleSilentlySelectsSingleSource() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        DbIntegrationProjectSupport.clearDataSources(project)
        val ds = createDataSource(info!!, "-silent")
        val introspectError = introspectDataSource(ds)
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: $introspectError")
            return
        }

        val sql = "SELECT id, station_id FROM car_device_info WHERE id = 1"
        val psi = DbIntegrationProjectSupport.ensureExecFile(project, sql)
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null)
        val (_, psiFile) = psi!!
        // 单数据源是静默选择分支的输入，先钉住（残留数据源会让契约退化成弹窗分支）
        val runners = DbIntegrationProjectSupport.awaitRunners(psiFile, sql, expected = 1)
        assertEquals(
            "静默选择契约要求发现链恰好给出 1 个 runner（实际=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}）",
            1,
            runners.size,
        )
        execEditor = DbIntegrationProjectSupport.installExecEditor(project)
        assertNotNull("控制台编辑器夹具应可建（prepareConsole 依赖 getEditors(consoleDocument) 非空）", execEditor)

        var prepared = false
        val errors = mutableListOf<String>()
        DataSourceSelector.prepareConsole(
            project = project,
            sql = sql,
            onError = { errors.add(it) },
            callback = { prepared = it },
        )
        val preparedInTime = DbIntegrationProjectSupport.waitWithPump(120_000) { DataSourceSelector.isPrepared() }
        if (!preparedInTime) {
            println("[DB-IT] prepareConsole 未就绪诊断: ${DbIntegrationProjectSupport.diagnoseConsoleState(project, ds)}")
        }
        assertTrue(
            "prepareConsole 120s 内未就绪 (errors=$errors)",
            preparedInTime,
        )
        assertTrue("prepareConsole callback 应返回 true", prepared)
        assertTrue("准备阶段不应有错误 (errors=$errors)", errors.isEmpty())
        val selected = selectorField("sharedDataSourceName") as? String
        assertEquals("静默选择应记录唯一数据源名（无弹窗）", ds.name, selected)
    }

    /**
     * T-TS-09：**控制台编辑器不可得 → 必须回调 onError**（可诊断性缺口直测）。
     *
     * 与 [testPrepareConsoleSilentlySelectsSingleSource] 是同一准备链的**反面输入**：那条用例
     * 用 [DbIntegrationProjectSupport.installExecEditor] 补上真实 IDE 里由控制台 UI 提供的编辑器，
     * 本用例**不补**——`prepareInConsole` 用 `getEditors(consoleDocument)` 反查执行用编辑器，
     * 恒空时修复前是两处静默（`:1267` 同步路径 `?: return`、`:1284` 异步路径无 else）：
     * 调用方既收不到 onError 也收不到 callback，面板停在「执行中」且 errors=[]——正是驱动管线
     * 打通前观测到的「120s 不就绪 + 错误为空」的成因（基线 idea.log `[M36] editor=null (async wait)`）。
     *
     * 断言口径：onError 在 60s 内到达，且准备链未走完（isPrepared()=false）。打印实际文案即可看出
     * 命中的是 sync（非 EDT 轮询）还是 async（EDT 异步等待）分支——两条分支成因不同，都由本用例覆盖。
     *
     * 环境门槛同本类其余 DB 用例（testDatabase 任务 + demo 数据源 + 本地 MySQL）。
     */
    @Test
    fun testPrepareConsoleWithoutConsoleEditorReportsError() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        DbIntegrationProjectSupport.clearDataSources(project)
        val ds = createDataSource(info!!, "-noeditor")
        val introspectError = introspectDataSource(ds)
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: $introspectError")
            return
        }

        // SQL **刻意与紧随其后的 testPrepareConsoleSilentlySelectsSingleSource 逐字相同**：
        // exec 文件是五类共用的**同一路径**，而 `writeVfTextViaDocument` 在「文档内容 != 目标文本」
        // 时才 setText+save，save 内部会 `VirtualFile.refresh()`，该 refresh 触发的 VFS 内容变更事件
        // **不带 from-save 标记** → `MemoryDiskConflictResolver` 登记冲突，随后在事件队列 flush 时
        // 若文档/文件戳仍不等就硬抛 `Unexpected memory-disk conflict in tests`（本轮堆栈实证：
        // writeVfTextViaDocument:3370 → doSaveDocument:377 → VirtualFile.refresh:674 →
        // RefreshSessionImpl.fireEventsInWriteAction → beforeContentChange:57）。
        // 让本用例留下的内容与下一个用例一致，下一个用例就整段跳过 setText+save，不再走那条登记链。
        val sql = "SELECT id, station_id FROM car_device_info WHERE id = 1"
        val psi = DbIntegrationProjectSupport.ensureExecFile(project, sql)
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null)
        val (_, psiFile) = psi!!
        // 单数据源是静默选择分支的输入，先钉住（残留数据源会让准备链退化成弹窗分支）
        val runners = DbIntegrationProjectSupport.awaitRunners(psiFile, sql, expected = 1)
        assertEquals(
            "本用例要求发现链恰好给出 1 个 runner（实际=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}）",
            1,
            runners.size,
        )
        // 前置钉死「编辑器不可得」：控制台文档即 exec 文件文档（见 DbIntegrationProjectSupport 类注释），
        // 先释放其上全部存活编辑器——forkEvery=1 下本类用例同 JVM，前序用例的夹具可能残留，
        // 不显式释放则本用例的前置条件取决于执行顺序（顺序依赖假失败）。
        DbIntegrationProjectSupport.releaseExecEditors(project)

        var cbResult: Boolean? = null
        val errors = mutableListOf<String>()
        DataSourceSelector.prepareConsole(
            project = project,
            sql = sql,
            onError = { errors.add(it) },
            callback = { cbResult = it },
        )
        val notified = DbIntegrationProjectSupport.waitWithPump(60_000) { errors.isNotEmpty() }
        assertTrue(
            "编辑器不可得时 onError 必须回调（修复前此处静默返回：面板停在「执行中」且 errors=[]，" +
                "callback 也永不触发）——errors=$errors, callback=$cbResult",
            notified,
        )
        assertFalse("准备链不应走完（无编辑器 → prepareAfterEditorResolved 未执行）", DataSourceSelector.isPrepared())
        println("[DB-IT] editor-unavailable onError: ${errors.first()}")

        // 显式收口（不等 tearDown）：本用例把控制台挂上后**中止**了准备链，这是既有用例都没有过的
        // 状态（其余用例要么走完整链、要么从未 attach）。**立即**摘控制台 + 落盘，把「文档已改未存」
        // 的窗口压到最短——该窗口内任何**非 save 来源**的写盘（DBN 控制台自身、平台 teardown 都会做）
        // 都会被 MemoryDiskConflictResolver 登记成冲突，并在**下一个用例**的任意一次文档写里硬抛
        // `Unexpected memory-disk conflict in tests`（20260918 实测：报错点是下一个用例的
        // prepareConsole）。故意不在这里额外 pump 事件队列——pump 只会拉长该窗口。
        DbIntegrationProjectSupport.detachDbnConsole(project)
        DbIntegrationProjectSupport.releaseExecEditors(project)
        runCatching { DataSourceSelector.releaseForPanel() }
        runCatching { DataSourceSelector.clearCachedTempPsiFileForTest() }
    }

    override fun tearDown() {
        introspectRef?.let { runCatching { it.close() } }
        introspectRef = null
        DbIntegrationProjectSupport.detachDbnConsole(project)
        DbIntegrationProjectSupport.releaseExecEditors(project)
        // T-PreviewExecDs-03 / M36.5.10：单例的 prepared/shared 槽位跨用例存活（clearPrepared 只清
        // prepared，shared 控制台仍在）——下一个用例会经 reuseSharedConsole 命中上个用例的控制台，
        // 而该控制台的编辑器/文档属于上个用例的夹具。面板关闭语义的清理是 releaseForPanel
        // （clearPrepared + dropSharedConsole + 清 pendingOnConsole），用例边界用它。
        runCatching { DataSourceSelector.releaseForPanel() }
        // T-TestDbDriver：object 单例的 temp Psi 缓存跨用例存活（clearPrepared 刻意不清，见 T-ExecCopyFix F2c）——
        // 而 BasePlatformTestCase 每个用例换一套 fixture 临时目录，残留缓存指向已拆目录 →
        // 「VFS not visible after refresh / 另一个程序正在使用此文件」。用例间必须显式清缓存。
        runCatching { DataSourceSelector.clearCachedTempPsiFileForTest() }
        super.tearDown()
    }
}
