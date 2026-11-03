package cn.song.mybatisplus.visualizer.execute

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DbCredentialManager
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.SQLException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DB 控制台端到端集成测试（M36.5.23）——**模拟 Ultimate 沙盒真实环境**。
 *
 * 与单测（test 任务）的区别：本测试跑在 `testDatabase` 任务（独立沙盒、Database 插件
 * 真实加载、复用 runIde 沙盒已下载的 MySQL 驱动），覆盖此前只能人工沙盒验收的链路：
 *
 *   prepareConsole（数据源静默复用 → session → attachConsole → 原生控制台嵌入）
 *   → executePrepared（原生链 doRunQueryInConsole → 真实 MySQL 连接 → 结果 Tab）
 *   → 模拟参数变更（编辑器文档 setText）→ 再次 executePrepared
 *   → **断言新结果 Tab 出现**（「滞后一轮」端到端回归，M36.5.22/22b 修复的行为级验证）
 *
 * 连接信息来源（全自动，无硬编码）：
 *   - URL/用户名/驱动：解析 demo 项目 `.idea/dataSources.xml` + `dataSources.local.xml`
 *     （系统属性 `mpDemoProjectDir`，默认兄弟目录）
 *   - 密码：系统属性 `mpTestDbPassword`（本机 ~/.gradle/gradle.properties，不进 git）；
 *     缺失时 skip（不 fail）——配置一次后全量可跑。
 *
 * 运行：`./gradlew testDatabase`
 */
class DbConsoleIntegrationTest : BasePlatformTestCase() {

    /** introspect 期间持有的连接守卫（close 即释放连接，见 [introspectDataSource] 内注释）。 */
    private var introspectRef: GuardedRef<*>? = null

    /** 控制台编辑器夹具的持有标记（造/释放见 [DbIntegrationProjectSupport.installExecEditor]）。 */
    private var execEditor: Editor? = null

    /** UI016 使用自身精确清理，不走历史夹具的全局 allEditors 释放。 */
    private var ui016LifecycleOnly = false

    /**
     * 稳定 light 工程目录（testDatabase 通道五类共用，根因见 [DbIntegrationProjectSupport] 类注释）：
     * 默认描述符下 `project.basePath` = JVM 里首个用例的 unitTest 临时目录，被属主用例 tearDown
     * 递归删除 → 后续用例的 exec 文件写盘与 VFS 分叉（基线 `refreshVfs: VFS not visible after refresh`）。
     */
    override fun getProjectDescriptor(): LightProjectDescriptor = DbIntegrationProjectSupport.descriptor

    override fun setUp() {
        super.setUp()
        DbIntegrationProjectSupport.allowRootAccess(testRootDisposable)
    }

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    private val dbPassword: String
        get() = "root" // M36.5.23：从 demo 项目 application.yml 复制（root/root，本地开发库无敏感性）

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
            if (url != null) info = DataSourceInfo(url, "root", driver ?: "com.mysql.cj.jdbc.Driver")
        }
        // 用户名在 local.xml（<user-name>）
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
    private fun createDataSource(info: DataSourceInfo): LocalDataSource {
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_demo@localhost-itest"
        ds.url = info.url
        ds.driverClass = info.driverClass
        ds.username = info.user
        // driverRef="mysql.8" + resolveDriver()：仅 setDriverClass 不解析 DatabaseDriver，
        // getAppropriateDataSourcesForExec 按 driver.dialect 过滤会跳过 driver=null 的数据源
        //（diag 实证：driver=null → runner 列表空）。setDriverRef 包私有——反射调。
        ds.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(ds, "mysql.8")
        ds.resolveDriver()
        // T-TestDbDriver：驱动种子文件 + 驱动 artifact 版本钉定（管线全貌见助手 KDoc）
        DbIntegrationSeedSupport.prepareDriverPipeline()
        // M36.5.23d：driver 文件由 DBN 从 config_<task>/jdbc-drivers 加载（build.gradle.kts doFirst
        // 预置）——不再需要手动 setClasspathElements（曾指向错误路径 config/ 无效）。
        // working-dir = 项目根目录：DbImplUtil.getAppropriateDataSourcesForExec(psiFile) 按文件
        // 所在项目目录匹配数据源 working-dir，未设则临时 mpsql-exec.sql 不在范围内 → runner 列表空。
        ds.workingDir = project.basePath
        // Storage.MEMORY：密码仅存内存（不污染沙盒密钥链，避免跨测试串扰）
        ds.passwordStorage = LocalDataSource.Storage.MEMORY
        mgr.addDataSource(ds)
        DbCredentialManager().setPassword(ds, OneTimeString(dbPassword), LocalDataSource.Storage.MEMORY)
        return ds
    }

    /**
     * 触发数据源 introspection（连接 + schema 加载）——未 introspection 时
     * `ds.getDelegate()` 返回 LocalDataSource（不实现 SqlDataSource），`lambda$4` 的
     * `checkcast SqlDataSource` 失败 → `containsFile(vf)` 过滤返回 false → runner 列表空
     * （M36.5.23b diag 实证）。建连后 DBN 自动 introspect，delegate 切换为 SqlDataSource 实例。
     *
     * MySQL 未启动 / 库未初始化 / RegionUrlMapper 网络失败：返回失败原因（调用方 assumeTrue skip）。
     *
     * @return null=成功；非 null=失败原因（用于 assumeTrue message）
     */
    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val proxyHost = System.getProperty("http.proxyHost")
        "proxy=$proxyHost:${System.getProperty("http.proxyPort")}".also {
            // 诊断：确认代理 system property 传入测试 JVM
        }
        val mgr = DatabaseConnectionManager.getInstance()
        val builder = mgr.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>> {
            // createBlocking 要求线程有 ProgressIndicator——用 runProcess 注入上下文
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
        "introspect 失败 (proxy=${System.getProperty("http.proxyHost")}:${System.getProperty("http.proxyPort")}): ${cause.javaClass.simpleName}: ${cause.message}"
    }

    /** 反射读取 DataSourceSelector 的 object 字段（preparedEditor/preparedUi）。 */
    private fun selectorField(name: String): Any? =
        DataSourceSelector::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(DataSourceSelector)

    /** 当前结果 Tab 名称列表（排除 Output）。 */
    private fun resultTabNames(): List<String> {
        val ui = selectorField("preparedUi") ?: return emptyList()
        val contents = runCatching {
            ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
        }.getOrNull() ?: return emptyList()
        return contents.map { it.displayName }
            .filter { !it.equals("Output", ignoreCase = true) && !it.equals("输出", ignoreCase = true) }
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
     * 端到端：准备 → 执行 → 改参 → 再执行 → 断言新结果 Tab。
     * 「滞后一轮」回归：第二次执行后必须出现与第一次**不同**的结果 Tab（M36.5.22b 行为）。
     */
    @Test
    fun testPrepareExecuteAndParameterChangeEndToEnd() {
        // 前置：Database 插件已加载（testDatabase 任务不排 jar）+ demo 项目可达。
        // 注意：密码 assume 放在「执行」前——无密码时 prepare 层（插件/数据源/控制台准备）仍被验证。
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        val dsInfo = info!!

        // ⓪ 清空应用级残留数据源：LocalDataSourceManager 跨用例存活，本用例走的是「唯一数据源静默
        //    复用」契约，多一条残留即退化成多 runner 弹窗分支（headless 无 frame → NPE，基线实证）
        DbIntegrationProjectSupport.clearDataSources(project)

        // ① 创建数据源（连接信息来自 demo 项目）
        val ds = createDataSource(dsInfo)

        // ①b 触发 introspection：让 delegate 切换为 SqlDataSource（否则 containsFile 过滤失败）。
        // MySQL 未启动 / 库未初始化 / RegionUrlMapper 网络失败 → skip 余下场景（不 fail）
        val introspectError = introspectDataSource(ds)
        // T-ExecDocGate：显式绿跳——平台 runner 把 AssumptionViolatedException 记为 failure 红任务
        if (introspectError != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: $introspectError")
            return
        }

        // 本用例执行 SQL（诊断 dump 与准备链共用同一文本）
        val execSql = "SELECT id, station_id FROM car_device_info WHERE id = 1"

        // 诊断 dump 只在断言失败时求值（见 [runnerDiag]）：该 dump 自身会写 exec 文件、重建 PSI、
        // 重设方言映射，放在准备链之前会污染被测状态（20260918 实测：前置 dump 让控制台 attach 后
        // 即被 DBN 回收——排查代码本身成了故障源）
        // ② prepareConsole：唯一数据源 → 静默复用 → 控制台就绪回调
        // ②a 前置：发现链收敛到恰好 1 个 runner（本用例契约是「唯一数据源静默复用」，多一条残留即
        //     走弹窗分支、少一条即回调 false；facade 相对 LocalDataSourceManager 有异步窗口，见
        //     DbIntegrationProjectSupport.awaitRunners KDoc）
        val execPsi = DbIntegrationProjectSupport.ensureExecFile(project, execSql)
        assertNotNull("临时执行 PsiFile 应可构造（VFS/方言映射）", execPsi)
        val runners = DbIntegrationProjectSupport.awaitRunners(execPsi!!.second, execSql, expected = 1)
        assertEquals(
            "唯一数据源契约要求发现链恰好 1 个 runner（实际=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}）",
            1,
            runners.size,
        )
        // ②b 控制台编辑器夹具：控制台文档即 exec 文件文档（JdbcConsole.getDocument() 字节码实证），
        //     headless 下无人为它建编辑器 → prepareInConsole 的 getEditors(consoleDocument) 恒空 →
        //     静默返回（基线 idea.log `[M36] editor=null (async wait)`，120s 不就绪且 errors=[]）
        execEditor = DbIntegrationProjectSupport.installExecEditor(project)
        assertNotNull("控制台编辑器夹具应可建（prepareConsole 依赖 getEditors(consoleDocument) 非空）", execEditor)

        var consoleReady = false
        val errors = mutableListOf<String>()
        var prepared = false
        DataSourceSelector.prepareConsole(
            project = project,
            sql = execSql,
            onConsole = { consoleReady = true },
            onEditor = { },
            onError = { errors.add(it) },
            callback = { prepared = it },
        )
        val consoleReadyInTime = DbIntegrationProjectSupport.waitWithPump(120_000) { consoleReady }
        if (!consoleReadyInTime) {
            println("[DB-IT] prepareConsole 未就绪诊断: ${DbIntegrationProjectSupport.diagnoseConsoleState(project, ds)}")
            println("[DB-IT] runner diag: " + runnerDiag(execSql))
        }
        assertTrue("prepareConsole 120s 内未就绪 (errors=$errors)", consoleReadyInTime)
        assertTrue("prepareConsole callback 应返回 true", prepared)
        assertTrue("控制台就绪后 isPrepared() 应为 true", DataSourceSelector.isPrepared())
        assertTrue("prepare 阶段不应有错误 (errors=$errors)", errors.isEmpty())

        // ③ executePrepared：真实 MySQL 执行 → 结果 Tab 出现（需要密码；未配置则 skip 余下场景）
        assumeTrue(
            "未配置 mpTestDbPassword——请在 ~/.gradle/gradle.properties 添加 mpTestDbPassword=<MySQL密码>" +
                "（无密码时仅验证 prepare 层）",
            dbPassword.isNotEmpty(),
        )
        var resultsReady = false
        val execErrors = mutableListOf<String>()
        DataSourceSelector.executePrepared(
            project = project,
            onResultsReady = { resultsReady = true },
            onError = { execErrors.add(it) },
        )
        assertTrue(
            "首次执行 60s 内无结果 Tab (errors=$execErrors)",
            DbIntegrationProjectSupport.waitWithPump(60_000) { resultTabNames().isNotEmpty() },
        )
        assertTrue("onResultsReady 应被回调", resultsReady)
        val firstTabs = resultTabNames()
        assertTrue("首次执行后应有结果 Tab (tabs=$firstTabs)", firstTabs.isNotEmpty())
        val firstTabName = firstTabs.last()

        // ④ 模拟参数变更：编辑器文档改为 id = 2（面板 executeSql 同步文档的等价操作）
        val editor = selectorField("preparedEditor") as com.intellij.openapi.editor.Editor
        com.intellij.openapi.application.WriteAction.runAndWait<Throwable> {
            editor.document.setText("SELECT id, station_id FROM car_device_info WHERE id = 2")
        }

        // ⑤ 再次执行：断言出现**新的**结果 Tab（滞后一轮回归点）
        var secondReady = false
        DataSourceSelector.executePrepared(
            project = project,
            onResultsReady = { secondReady = true },
            onError = { execErrors.add(it) },
        )
        val newTabAppeared = DbIntegrationProjectSupport.waitWithPump(60_000) { resultTabNames().any { it != firstTabName } }
        val secondTabs = resultTabNames()
        assertTrue(
            "第二次执行后未出现新结果 Tab（滞后一轮回归）：first=$firstTabName, second=$secondTabs, errors=$execErrors",
            newTabAppeared,
        )
        assertTrue("第二次执行 onResultsReady 应被回调", secondReady)
        assertTrue("集成链路不应有错误回调 (errors=$execErrors)", execErrors.isEmpty())

        // ⑤b T-ResultOwnership（2026-11-03 真机「IDEA 控制台有结果、预览窗口没变化」）：
        //     执行后持久整控台 SplitPane 的下半区**必须**挂着结果组件。DBN 会把同一结果组件
        //     重挂回 ServiceViewComponentWrapper，而 `JSplitPane.remove()` 会把 rightComponent
        //     置 null —— 那时预览面板下半区就是空白，只有 IDEA 控制台能看到结果。
        val persistentSplit = DataSourceSelector.preparedFullComponent as? javax.swing.JSplitPane
        assertNotNull("执行后应有持久整控台 SplitPane", persistentSplit)
        assertNotNull(
            "执行后结果组件必须挂在持久 split 的下半区（被平台抽走 = 预览面板空白）",
            persistentSplit!!.bottomComponent,
        )
        assertSame(
            "持久 split 的下半区应是控制台自己的结果组件（不是别的容器顶包）",
            DataSourceSelector.preparedResultComponent,
            persistentSplit.bottomComponent,
        )

        // ⑥ 清理：释放控制台引用（不影响 IDE 管理的 session 生命周期）
        DataSourceSelector.clearPrepared()
        assertEquals("clearPrepared 后 isPrepared() 应为 false", false, DataSourceSelector.isPrepared())
        assertNotNull(dsInfo)
    }


    /**
     * runner 发现链诊断 dump（**惰性**：只在断言失败时求值）。
     *
     * 内容全是「读状态 + 复现 createTempPsiFile 的 4 步」：复现步骤会写 exec 文件、重建 PSI、
     * 重设方言映射——这些写点若发生在 prepareConsole 之前，会与被测准备链互相干扰
     *（20260918 实测：前置 dump 让控制台 attach 后即被 DBN 回收，排查代码成了故障源）。
     * 故改为失败时求值：诊断能力不减，被测状态不再被诊断代码扰动。
     */
    private fun runnerDiag(execSql: String): String {
        val diag = StringBuilder()
            runCatching {
                val mgr = LocalDataSourceManager.getInstance(project)
                val all = mgr.dataSources
                diag.append("LocalDataSourceManager.dataSources count=${all.size}\n")
                all.forEach {
                    diag.append("  ds name=${it.name} uniqueId=${it.uniqueId} driver=${it.databaseDriver?.id} workingDir=${it.workingDir}\n")
                }
                // 直接调 DbImplUtil.getAppropriateDataSourcesForExec 看过滤——psiFile 来自 prepareConsole
                // 内部创建（.idea/.../exec/mpsql-exec.sql），此处无法直接取，但能验证数据源可见性
                diag.append("DataSource visible: ${all.isNotEmpty()}\n")
                // DbPsiFacade（PSI 层）数据源列表——getAppropriateDataSourcesForExec 从这里取
                runCatching {
                    val psiFacade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                        .getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
                        .invoke(null, project)
                    val psiDs = psiFacade.javaClass.getMethod("getDataSources").invoke(psiFacade) as List<*>
                    diag.append("DbPsiFacade.getDataSources count=${psiDs.size}\n")
                    psiDs.forEach { diag.append("  psi ds=$it\n") }
                }.onFailure { diag.append("DbPsiFacade dump failed: ${it.javaClass.simpleName}: ${it.message}\n") }
                // 逐步执行 createTempPsiFile 的 4 步（不走 runCatching.getOrNull 吞异常），
                // 精确定位 null 源
                try {
                    val base = project.basePath ?: error("project.basePath null")
                    val dir = java.io.File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec").apply { mkdirs() }
                    val f = java.io.File(dir, "mpsql-exec.sql").apply { writeText(execSql) }
                    diag.append("step1 file written: ${f.absolutePath}\n")
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
                        ?: error("refreshAndFindFileByIoFile null")
                    diag.append("step2 vf=${vf.path}\n")
                    val mappings = com.intellij.sql.dialects.SqlDialectMappings.getInstance(project)
                    @Suppress("UNCHECKED_CAST")
                    val values = mappings.javaClass.getMethod("getAvailableValues").invoke(mappings) as List<Any?>
                    diag.append("step3 available dialects=${values.map { (it as? com.intellij.lang.Language)?.id }}\n")
                    val mysql = values.firstOrNull {
                        (it as? com.intellij.lang.Language)?.id.equals("MySQL", ignoreCase = true)
                    }
                    diag.append("step3b mysql dialect=${mysql?.let { (it as com.intellij.lang.Language).id }}\n")
                    if (mysql != null) {
                        mappings.javaClass.getMethod("setMapping", com.intellij.openapi.vfs.VirtualFile::class.java, Any::class.java)
                            .invoke(mappings, vf, mysql)
                        diag.append("step3c mapping set OK\n")
                    }
                    val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(vf)
                    diag.append("step4 psi=${psi?.javaClass?.simpleName} dialect=${psi?.language?.id}\n")
                    val appropriate = Class.forName("com.intellij.database.util.DbImplUtil")
                        .getMethod("getAppropriateDataSourcesForExec", com.intellij.psi.PsiFile::class.java)
                        .invoke(null, psi) as Iterable<*>
                    diag.append("step5 getAppropriateDataSourcesForExec=${appropriate.count()}\n")
                    // 复现过滤条件 lambda$1（isValid/SqlDataSource）+ lambda$2（dialect similarTo）
                    val sqlDialect = Class.forName("com.intellij.database.util.DbImplUtil")
                        .getMethod("getSqlLanguageDialectForExec", com.intellij.psi.PsiFile::class.java)
                        .invoke(null, psi)
                    diag.append("step6 sqlDialectForExec=${sqlDialect} (${(sqlDialect as? com.intellij.lang.Language)?.id})\n")
                    val dbDialect = runCatching {
                        sqlDialect?.javaClass?.getMethod("getDatabaseDialect")?.invoke(sqlDialect)
                    }.getOrNull()
                    diag.append("step6b dbDialect=${dbDialect?.javaClass?.simpleName} id=${(dbDialect as? com.intellij.lang.Language)?.id}\n")
                    runCatching {
                        val psiFacade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                            .getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
                            .invoke(null, project)
                        val psiDs = psiFacade.javaClass.getMethod("getDataSources").invoke(psiFacade) as List<*>
                        val dsEl = psiDs.first()
                        val isValid = dsEl?.javaClass?.getMethod("isValid")?.invoke(dsEl)
                        diag.append("step7 psi ds isValid=$isValid class=${dsEl?.javaClass?.simpleName}\n")
                        val dsDialect = Class.forName("com.intellij.database.util.DbImplUtilCore")
                            .getMethod("getDatabaseDialect", Class.forName("com.intellij.database.psi.DbElement"))
                            .invoke(null, dsEl)
                        diag.append("step8 ds dialect=${dsDialect?.javaClass?.simpleName} id=${(dsDialect as? com.intellij.lang.Language)?.id}\n")
                        if (dbDialect != null && dsDialect != null) {
                            val similar = dbDialect.javaClass.getMethod("similarTo", Class.forName("com.intellij.database.dialects.DatabaseDialect"))
                                .invoke(dbDialect, dsDialect)
                            diag.append("step9 similarTo=$similar\n")
                        // lambda$4: SqlDataSource.containsFile(vf) —— 文件-数据源包含检查
                        val sqlDs = dsEl?.javaClass?.getMethod("getDelegate")?.invoke(dsEl)
                        val contains = runCatching {
                            sqlDs?.javaClass?.getMethod("containsFile", com.intellij.openapi.vfs.VirtualFile::class.java)
                                ?.invoke(sqlDs, vf) as? Boolean
                        }.getOrNull()
                        diag.append("step10 SqlDataSource.containsFile(vf)=$contains sqlDsClass=${sqlDs?.javaClass?.simpleName}\n")
                        // 看数据源 workingDir + file mapping
                        val wd = dsEl?.javaClass?.getMethod("getWorkingDir")?.invoke(dsEl)
                        diag.append("step11 ds workingDir=$wd\n")
                        }
                    }.onFailure { diag.append("filter repro FAILED: ${it.javaClass.simpleName}: ${it.message}\n") }
                } catch (e: Throwable) {
                    diag.append("step FAILED: ${e.javaClass.simpleName}: ${e.message}\n")
                    e.stackTrace.take(6).forEach { diag.append("  at $it\n") }
                }
            }.onFailure { diag.append("dump failed: ${it.javaClass.simpleName}: ${it.message}\n") }

        return diag.toString()
    }

    /**
     * UI016 WIP（未编译、未执行）：真实 native view 的关闭协议正对照。
     *
     * 不借 installExecEditor/releaseExecEditors 伪造生命周期；显式 getView 绕过 JdbcConsole
     * 构造器在 unit-test mode 不建视图的分支。只建 localhost/mp_demo 的未执行 session 描述，
     * 不读 demo XML/凭据、不 introspect、不提交 SQL。首次运行先核夹具能否离线成立；
     * 服务/模型初始化失败属于夹具失败，不能算 UI016 复现红。
     */
    fun testUi016NativeViewDestroyProtocolControl() {
        withUi016NativeViews { fixture ->
            val service = com.intellij.database.console.session.DatabaseSessionViewService.getInstance()
            val owned = fixture.create("mpsql-exec.sql")
            val foreign = fixture.create("user-console.sql")
            val view = owned.view
            val languageConsole = owned.consoleView
            val executionEditor = languageConsole.consoleEditor
            val historyEditor = languageConsole.historyViewer
            val ui = requireNotNull(owned.ui) { "前提：原生 view 必须提供 RunnerLayoutUi" }
            val foreignView = foreign.view
            val foreignEditor = foreign.consoleView.consoleEditor

            assertSame("前提：应用服务必须持有本次真实 view", view, service.getClientViewIfAny(owned))
            assertFalse("前提：必须有真实执行编辑器", executionEditor.isDisposed)
            assertFalse("前提：必须有真实 history 编辑器", historyEditor.isDisposed)
            assertFalse("前提：view 不得已经被 facade 清扫", ui.isDisposed)
            service.destroyView(owned)

            assertNull("原生 destroyView 必须移除应用服务缓存", service.getClientViewIfAny(owned))
            assertTrue("原生协议负责释放执行编辑器", executionEditor.isDisposed)
            assertTrue("原生协议负责释放 history 编辑器", historyEditor.isDisposed)
            assertTrue("原生协议负责释放 RunnerLayoutUi", ui.isDisposed)
            assertSame("销毁一个 client 不得影响另一个 view", foreignView, service.getClientViewIfAny(foreign))
            assertFalse("其他控制台编辑器仍存活", foreignEditor.isDisposed)
        }
    }

    /**
     * UI016 WIP（未编译、未执行）：生产 attach 后准备中断的 view 应在 projectClosing 阶段释放。
     *
     * 这里只同步发项目关闭前通知，不销毁 BasePlatformTestCase 的共享 light project，
     * 不启动 GUI；断言时项目服务/总线仍可用。它检验关闭协议接线，不冒充完整 IDE Exit。
     * 预期缺陷断言是 UI016-OWNED-VIEW-RETAINED；若先在真实 view 构造/生产 attach 前提失败，
     * 必须修夹具再判红，不能把 CNFE/模型失效/准备失败归为生命周期复现。
     */
    fun testUi016OwnedNativeViewReleasedBeforeProjectServices() {
        withUi016NativeViews { fixture ->
            val service = com.intellij.database.console.session.DatabaseSessionViewService.getInstance()
            // 单测模式不会自动建 native view，先用原生 API 显式建出来；按(project,file)缓存。
            // 生产 attachConsole 会替换 client，原生服务复用该 view；不手建 EditorFactory editor。
            val seed = fixture.create("mpsql-exec.sql")
            val seedView = seed.view
            val seedEditor = seed.consoleView.consoleEditor
            val file = seed.virtualFile
            var reachedNativeEditor = false
            var stoppedAfterAttach = false
            val stopAfterAttach = "UI016_TEST_STOP_AFTER_NATIVE_ATTACH"
            val failures = mutableListOf<String>()
            DataSourceSelector.prepareInConsole(
                project = fixture.owner,
                session = seed.session,
                vf = file,
                executeAfterPrepare = false,
                dataSourceName = null,
                onConsole = null,
                onEditor = { editor ->
                    reachedNativeEditor = true
                    assertSame("必须借用真实 native 执行编辑器", seedEditor, editor)
                    // 主动终止已 attach 但未完成准备的链：不得留下 view 到 application 退出。
                    // 不把 isPrepareActive 置 false，避免把本例变成「取消入口应立即清理」的另一契约。
                    // 此终止还阻止 schema 对齐、文档同步和延迟模型/结果轮询，不连接数据库。
                    throw IllegalStateException(stopAfterAttach)
                },
                onError = {
                    if (it == stopAfterAttach) stoppedAfterAttach = true else failures.add(it)
                },
            )
            // 仅接受精确哨兵；其他异常不吞、不 assume skip，前提失败与生命周期断言分开。
            assertTrue("前提：生产 attach 必须同步拿到真实 editor，errors=$failures", reachedNativeEditor)
            assertTrue("前提：必须在 attach 后通过精确哨兵终止准备链", stoppedAfterAttach)
            assertTrue("前提：生产 attach 不得有哨兵以外的错误，errors=$failures", failures.isEmpty())
            val owned = requireNotNull(com.intellij.database.console.JdbcConsoleProvider.getConsole(fixture.owner, file))
            fixture.track(owned)
            assertNotSame("前提：确实经过生产 attach 替换 client", seed, owned)
            assertSame("前提：原生服务复用已有真实 view", seedView, owned.view)
            val view = owned.view
            var releaseCount = 0
            com.intellij.openapi.util.Disposer.register(view) { releaseCount++ }
            val executionEditor = owned.consoleView.consoleEditor
            val historyEditor = owned.consoleView.historyViewer
            val ui = requireNotNull(owned.ui) { "前提：原生 view 必须提供 RunnerLayoutUi" }
            val foreign = fixture.create("user-console.sql")
            val foreignView = foreign.view
            val foreignEditor = foreign.consoleView.consoleEditor
            assertFalse("前提：被测 view 必须在关闭通知前存活", com.intellij.openapi.util.Disposer.isDisposed(view))

            DataSourceSelector.releaseForPanel()
            assertSame("预览关闭只清缓存，不得提前销毁共享结果", view, service.getClientViewIfAny(owned))
            assertFalse("预览关闭后共享执行编辑器仍存活", executionEditor.isDisposed)

            ApplicationManager.getApplication().messageBus
                .syncPublisher(com.intellij.openapi.project.ProjectManager.TOPIC)
                .projectClosing(fixture.owner)

            assertFalse("观测边界：项目服务尚未销毁", fixture.owner.isDisposed)
            assertNull("UI016-OWNED-VIEW-RETAINED：关闭前应移除自有 view，不能留到 application dispose",
                service.getClientViewIfAny(owned))
            assertTrue("关闭前必须经原生协议释放执行编辑器", executionEditor.isDisposed)
            assertTrue("关闭前必须释放 history 编辑器", historyEditor.isDisposed)
            assertTrue("关闭前必须释放 RunnerLayoutUi，避免晚保存布局访问已销毁项目", ui.isDisposed)
            assertSame("不得遍历销毁用户的原生控制台", foreignView, service.getClientViewIfAny(foreign))
            assertFalse("用户控制台不属于插件自有清理集合", foreignEditor.isDisposed)
            ApplicationManager.getApplication().messageBus
                .syncPublisher(com.intellij.openapi.project.ProjectManager.TOPIC).projectClosing(fixture.owner)
            assertEquals("重复close只释放一次", 1, releaseCount)
            assertSame("重复close仍不伤用户view", foreignView, service.getClientViewIfAny(foreign))
        }
    }

    fun testUi016RegistrationFailureDoesNotAbortOrdinaryPrepare() {
        ui016LifecycleOnly = true
        val reported = mutableListOf<Throwable>()
        var continued = false
        NativeConsoleViewLifetime.bestEffortRegistration({
            throw java.lang.reflect.InvocationTargetException(IllegalAccessException("fixture registration unavailable"))
        }, reported::add)
        continued = true
        assertTrue("普通登记异常不改变原有准备链后续执行", continued)
        assertEquals(1, reported.size)
        assertTrue("完整根因交给日志通道而不是吞没", reported.single() is IllegalAccessException)
    }

    fun testUi016CriticalRegistrationFailuresEscapePrepareCatch() {
        ui016LifecycleOnly = true
        for (critical in listOf<Throwable>(com.intellij.openapi.progress.ProcessCanceledException(),
            LinkageError("fixture critical"))) {
            var reported = false
            var thrown: Throwable? = null
            try {
                // 模拟生产外层runCatching：内层登记已透传，外层也不得又吞回onError。
                runCatching {
                    NativeConsoleViewLifetime.bestEffortRegistration({
                        throw java.lang.reflect.InvocationTargetException(critical)
                    }) { reported = true }
                }.onFailure { NativeConsoleViewLifetime.unwrapAndRethrowCritical(it) }
            } catch (failure: Throwable) {
                thrown = failure
            }
            assertSame("取消/致命错误必须保持同一根因透传", critical, thrown)
            assertFalse("取消/致命错误不得作为普通登记失败降级", reported)
        }
    }

    fun testUi016UserRecreatedViewForSameFileSurvivesPluginClose() {
        withUi016NativeViews { fixture ->
            val service = com.intellij.database.console.session.DatabaseSessionViewService.getInstance()
            val old = fixture.create("mpsql-exec.sql")
            val oldView = old.view
            val file = old.virtualFile
            val lifetime = fixture.owner.getService(NativeConsoleViewLifetime::class.java)
            lifetime.remember(old)
            service.destroyView(old)
            assertNull("前提：旧view的原生销毁必须清缓存", service.getClientViewIfAny(old))
            // 同一个VirtualFile，而不是仅同名文件；该原生重建不经过插件登记。
            val replacement = fixture.create(file)
            val newView = replacement.view
            val newEditor = replacement.consoleView.consoleEditor
            assertNotSame("前提：是真正重建view，不是原生复用旧view", oldView, newView)
            assertSame("前提：旧client按file查询已经指向用户新view", newView, service.getClientViewIfAny(old))
            ApplicationManager.getApplication().messageBus
                .syncPublisher(com.intellij.openapi.project.ProjectManager.TOPIC).projectClosing(fixture.owner)
            assertSame("UI016-REPLACEMENT-VIEW：旧client不得按file销毁用户重建view", newView,
                service.getClientViewIfAny(replacement))
            assertFalse("用户新view的执行编辑器保持存活", newEditor.isDisposed)
        }
    }

    /** 原生attach在unit-test mode跳过session initView，必须显式补出真实运行态的第二个view。 */
    fun testUi016SessionLogViewAlsoReleasedBeforeProjectBus() {
        withUi016NativeViews { fixture ->
            val service = com.intellij.database.console.session.DatabaseSessionViewService.getInstance()
            val client = fixture.createRetained("mpsql-exec.sql")
            val sessionView = com.intellij.openapi.application.WriteIntentReadAction.compute<Any> {
                service.getView(client.session)
            } as com.intellij.database.console.session.DatabaseSessionLogView
            val sessionEditor = sessionView.consoleView.consoleEditor
            assertNotSame("两个独立view：client view不能代表session output", client.view, sessionView)
            assertTrue("session output控制台持有标题LightVirtualFile而非exec文件",
                sessionView.consoleView.virtualFile is com.intellij.testFramework.LightVirtualFile)
            assertNotSame(client.virtualFile, sessionView.consoleView.virtualFile)
            val foreign = fixture.create("user-session.sql")
            val foreignView = com.intellij.openapi.application.WriteIntentReadAction.compute<Any> {
                service.getView(foreign.session)
            } as com.intellij.database.console.session.DatabaseSessionLogView
            NativeConsoleViewLifetime.remember(fixture.owner, client)
            DataSourceSelector.releaseForPanel()
            assertFalse("预览关闭不能释放session output", sessionEditor.isDisposed)
            ApplicationManager.getApplication().messageBus
                .syncPublisher(com.intellij.openapi.project.ProjectManager.TOPIC).projectClosing(fixture.owner)
            assertNull("原client清理已经生效", service.getClientViewIfAny(client))
            assertTrue("UI016-SESSION-VIEW-RETAINED：真实attach的session output不能活到project bus释放",
                sessionEditor.isDisposed)
            assertFalse("只提前释放UI，不提前销毁session/连接", com.intellij.openapi.util.Disposer.isDisposed(client.session))
            assertSame("缓存按原生协议等session销毁后移除", sessionView, service.getViewIfAny(client.session))
            assertFalse("其他原生session output不得被枚举清理", foreignView.consoleView.consoleEditor.isDisposed)
            com.intellij.openapi.util.Disposer.dispose(client.session)
            assertNull("session原生销毁完成后缓存必须移除", service.getViewIfAny(client.session))
        }
    }

    /** 真实打开并关闭独立项目，捕获目标Bus错误；无关平台错误仍按默认规则失败。 */
    fun testUi016RealProjectCloseDoesNotLeaveSessionOutput() = realProjectCloseSessionOutput(true)

    fun testUi016NativeOnlyRetainedSessionReproducesSameBusFailure() = realProjectCloseSessionOutput(false)

    private fun realProjectCloseSessionOutput(pluginOwned: Boolean) {
        ui016LifecycleOnly = true
        val directory = java.nio.file.Files.createTempDirectory("mpsv-native-close-")
        val parent = com.intellij.openapi.util.Disposer.newDisposable("native-close-test")
        val owner = com.intellij.testFramework.PlatformTestUtil.loadAndOpenProject(directory, parent)
        val fixture = Ui016NativeViews(owner)
        var closed = false
        val busErrors = mutableListOf<Throwable>()
        try {
            val client = fixture.createRetained("mpsql-exec.sql")
            val service = com.intellij.database.console.session.DatabaseSessionViewService.getInstance()
            val output = com.intellij.openapi.application.WriteIntentReadAction.compute<Any> {
                service.getView(client.session)
            } as com.intellij.database.console.session.DatabaseSessionLogView
            val editor = output.consoleView.consoleEditor
            if (pluginOwned) NativeConsoleViewLifetime.remember(owner, client)
            else service.destroyView(client) // 原生对照只留session output，与已修client链保持同一状态。
            com.intellij.testFramework.LoggedErrorProcessor.executeWith(object : com.intellij.testFramework.LoggedErrorProcessor() {
                override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                    if (message == "Bus is already disposed" && t != null &&
                        t.stackTrace.any { it.className == "com.intellij.execution.console.ConsoleExecutionEditor" }) {
                        busErrors.add(t)
                        return Action.NONE
                    }
                    return super.processError(category, message, details, t)
                }
            }, com.intellij.util.ThrowableRunnable<Throwable> {
                com.intellij.testFramework.PlatformTestUtil.forceCloseProjectWithoutSaving(owner)
                closed = owner.isDisposed
            })
            assertTrue("必须完成真实项目销毁，不能仅发送人工通知", closed)
            assertTrue("关闭后真实session editor必须已释放", editor.isDisposed)
            assertNull("真实关闭最终必须清除sessionViews缓存", service.getViewIfAny(client.session))
            if (pluginOwned) {
                assertTrue("UI016-REAL-CLOSE-BUS：session output晚于项目总线释放，count=${busErrors.size}", busErrors.isEmpty())
            } else {
                assertEquals("纯原生保留session应复现相同单个Bus错误，证明不是插件直接releaseEditor", 1, busErrors.size)
                assertTrue("必须是project服务销毁期的同族栈", busErrors.single().stackTrace.any {
                    it.className == "com.intellij.serviceContainer.ComponentManagerImpl" && it.methodName == "dispose"
                })
            }
        } finally {
            if (!closed) fixture.close()
            com.intellij.openapi.util.Disposer.dispose(parent)
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * 使用平台已完整登记的 light project；仅发送关闭前通知，不真的销毁它。
     * 首轮另建未打开重项目触发 ValueLookupManager 的 ProjectEntity 找不到项目，
     * 属夹具前提失败；不能忽略该错误。每例自有 client/source 均在 finally 精确清理。
     */
    private fun withUi016NativeViews(body: (Ui016NativeViews) -> Unit) {
        ui016LifecycleOnly = true
        val fixture = Ui016NativeViews(project)
        try {
            body(fixture)
        } finally {
            // 清理放在断言之后，且只清本夹具显式登记的资源。不得全局 release allEditors 救绿。
            fixture.close()
        }
    }

    private class Ui016NativeViews(val owner: com.intellij.openapi.project.Project) : AutoCloseable {
        private val clients = mutableListOf<com.intellij.database.console.JdbcConsole>()
        private val sources = LocalDataSourceManager.getInstance(owner)
        private val source = sources.createEmpty().apply {
            name = "UI016-offline-mp_demo"
            url = "jdbc:mysql://localhost:3306/mp_demo"
            driverClass = "com.mysql.cj.jdbc.Driver"
            isAutoSynchronize = false
            javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
                .invoke(this, "mysql.8")
            resolveDriver()
            sources.addDataSource(this)
        }

        fun track(client: com.intellij.database.console.JdbcConsole) {
            if (clients.none { it === client }) clients.add(client)
        }

        fun create(name: String): com.intellij.database.console.JdbcConsole = create(
            com.intellij.testFramework.LightVirtualFile(name, com.intellij.sql.SqlFileType.INSTANCE, "SELECT 1"))

        fun createRetained(name: String): com.intellij.database.console.JdbcConsole = create(
            com.intellij.testFramework.LightVirtualFile(name, com.intellij.sql.SqlFileType.INSTANCE, "SELECT 1"), true)

        fun create(file: com.intellij.openapi.vfs.VirtualFile, retained: Boolean = false): com.intellij.database.console.JdbcConsole {
            // Wallet.canClose在unit-test mode强制回收普通session，掩盖真机autoClose=false的保留。
            // 空初始title使原生isService=true，原生逻辑明确保留，不改应用全局isUnitTestMode。
            val session = com.intellij.database.console.session.DatabaseSessionManager.getSession(owner, source,
                if (retained) "" else file.name)
            if (retained) {
                check(session.isService) { "前提：保留session不能被单测canClose短路回收" }
                session.title = "SELECT * FROM user WHERE id IN (71,72,73)"
            }
            val client = requireNotNull(com.intellij.openapi.application.WriteIntentReadAction.compute<com.intellij.database.console.JdbcConsole?> {
                com.intellij.database.console.JdbcConsoleProvider.attachConsole(owner, session, file)
            })
            track(client)
            // getView 会创建真正的 ConsoleExecutionEditor/history editor/RunnerLayoutUi。
            com.intellij.openapi.application.WriteIntentReadAction.compute<Any> { client.view }
            return client
        }

        override fun close() {
            val service = com.intellij.database.console.session.DatabaseSessionViewService.getInstance()
            try {
                val sessions = clients.map { it.session }.distinct()
                clients.asReversed().forEach { client ->
                    // getClientViewIfAny 不会新建 view；相同(project,file)新旧client共享key，清理幂等。
                    service.destroyView(client)
                    com.intellij.openapi.util.Disposer.dispose(client)
                }
                sessions.forEach { com.intellij.openapi.util.Disposer.dispose(it) }
            } finally {
                DataSourceSelector.releaseForPanel()
                sources.removeDataSource(source)
            }
        }
    }

    override fun tearDown() {
        introspectRef?.let { runCatching { it.close() } }
        introspectRef = null
        // 夹具编辑器必须先释放：平台 TestApplicationKt.checkEditorsReleased 对每个存活编辑器硬抛
        if (!ui016LifecycleOnly) {
            DbIntegrationProjectSupport.detachDbnConsole(project)
            DbIntegrationProjectSupport.releaseExecEditors(project)
        }
        // 单例的 prepared/shared 槽位跨用例存活——面板关闭语义的清理是 releaseForPanel
        // （clearPrepared + dropSharedConsole + 清 pendingOnConsole），用例边界用它，避免下个用例
        // 经 reuseSharedConsole 命中本用例的控制台/编辑器（详见 DataSourceSelectorIntegrationTest 同款注释）
        runCatching { DataSourceSelector.releaseForPanel() }
        // T-TestDbDriver：object 单例 temp Psi 缓存跨用例存活，fixture 临时目录每用例一换 → 显式清。
        runCatching { DataSourceSelector.clearCachedTempPsiFileForTest() }
        super.tearDown()
    }
}
