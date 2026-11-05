package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.ui.PreviewSideQuery
import cn.song.mybatisplus.visualizer.testutil.DbTestCredentials
import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DbCredentialManager
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-PreviewSideQuery 真库集成（testDatabase 通道）：旁路查询走**真实 MySQL** 的
 * `McpDbExecutor` 反射链，而不是 `runnerForTest` 桩。
 *
 * 覆盖三件此前只能真机取证的事：
 *  1. COUNT 标量映射：`SELECT COUNT(*) ... id=2` → `Scalar(1)`（与库里唯一行对账）；
 *  2. EXPLAIN 表格映射：`EXPLAIN SELECT ...` → `Table`，带 `select_type` 列与计划行；
 *  3. 失败解包：不存在的表 → `Failed`，消息来自真实驱动的 `InvocationTargetException` 目标异常。
 *
 * 环境门槛与 [DataSourceSelectorIntegrationTest] 一致：demo 项目 `.idea/dataSources.xml`
 * 提供 URL/用户名/驱动，密码硬编码 root（本地开发库无敏感性）；MySQL 未启动时
 * `assumeTrue` skip 而非失败。
 */
class PreviewSideQueryIntegrationTest : BasePlatformTestCase() {

    private var introspectRef: GuardedRef<*>? = null
    private var dataSource: LocalDataSource? = null

    override fun getProjectDescriptor(): LightProjectDescriptor = DbIntegrationProjectSupport.descriptor

    override fun setUp() {
        super.setUp()
        DbIntegrationProjectSupport.allowRootAccess(testRootDisposable)
        DbIntegrationProjectSupport.clearDataSources(project)
    }

    override fun tearDown() {
        introspectRef?.let { runCatching { it.close() } }
        introspectRef = null
        runCatching { DbIntegrationProjectSupport.clearDataSources(project) }
        super.tearDown()
    }

    fun testCountExplainAndFailureAgainstRealMySql() {
        val failure = prepareDataSource()
        assumeTrue("MySQL 未就绪，跳过真库旁路查询：$failure", failure == null)

        val count = runSideQuery("SELECT COUNT(*) FROM user WHERE id = 2", scalar = true)
        assertTrue("COUNT 应返回标量：$count", count is PreviewSideQuery.Result.Scalar)
        assertEquals("mpj_demo.user 中 id=2 恰一行", 1L, (count as PreviewSideQuery.Result.Scalar).value)

        val explain = runSideQuery(
            "EXPLAIN SELECT id, name, age, sex, head_img FROM user WHERE id = 1",
            scalar = false,
        )
        assertTrue("EXPLAIN 应返回表格：$explain", explain is PreviewSideQuery.Result.Table)
        val raw = (explain as PreviewSideQuery.Result.Table).raw
        assertTrue("EXPLAIN 必须返回计划行：$raw", raw.rows.isNotEmpty())
        assertTrue(
            "EXPLAIN 必须带 select_type 列：${raw.headers}",
            raw.headers.any { it.equals("select_type", ignoreCase = true) },
        )

        val failed = runSideQuery("SELECT * FROM mpsv_no_such_table", scalar = true)
        assertTrue("不存在的表必须走 Failed：$failed", failed is PreviewSideQuery.Result.Failed)
        val message = (failed as PreviewSideQuery.Result.Failed).message
        assertTrue("失败信息应来自真实驱动（含表名）：$message", message.contains("mpsv_no_such_table"))
    }

    private fun runSideQuery(sql: String, scalar: Boolean): PreviewSideQuery.Result {
        val request = ExecutionDialectGate.Request(
            renderMode = ExecutionDialectGate.RenderMode.AUTO,
            renderedDialectName = "MySQL",
            intent = if (scalar) ExecutionDialectGate.Intent.QUERY else ExecutionDialectGate.Intent.EXPLAIN,
            canLosslesslyRerender = false,
            userTextUntouched = true,
        )
        var result: PreviewSideQuery.Result? = null
        val latch = CountDownLatch(1)
        PreviewSideQuery.run(
            project,
            dataSource!!,
            sql,
            request,
            scalar = scalar,
            requireSelectGuard = scalar,
        ) { r ->
            result = r
            latch.countDown()
        }
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline && latch.count > 0) {
            latch.await(100, TimeUnit.MILLISECONDS)
            UIUtil.dispatchAllInvocationEvents()
        }
        assertNotNull("旁路查询未在 120s 内回调：$sql", result)
        return result!!
    }

    // ─────────────────────── 数据源夹具（照 DataSourceSelectorIntegrationTest） ───────────────────────

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

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
                when (children.item(j).nodeName) {
                    "jdbc-url" -> url = children.item(j).textContent.trim()
                    "jdbc-driver" -> driver = children.item(j).textContent.trim()
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

    private fun prepareDataSource(): String? {
        val info = readDemoDataSource("mpj_demo") ?: return "demo dataSources.xml 未找到 mpj_demo"
        val manager = LocalDataSourceManager.getInstance(project)
        val created = manager.createEmpty()
        created.name = "mpj_demo@localhost-itest-sidequery"
        created.url = info.url
        created.driverClass = info.driverClass
        created.username = info.user
        created.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(created, "mysql.8")
        created.resolveDriver()
        DbIntegrationSeedSupport.prepareDriverPipeline()
        created.workingDir = project.basePath
        created.passwordStorage = LocalDataSource.Storage.MEMORY
        manager.addDataSource(created)
        DbCredentialManager().setPassword(created, OneTimeString(DbTestCredentials.password("root")), LocalDataSource.Storage.MEMORY)
        dataSource = created
        return introspectDataSource(created)
    }

    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val manager = DatabaseConnectionManager.getInstance()
        val builder = manager.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>> {
            val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                com.intellij.openapi.util.Computable<GuardedRef<*>> { builder.createBlockingNonCancellable() },
                indicator,
            )
        }
        val ref = future.get() ?: return@runCatching "createBlocking future null"
        val loaded = waitUntil(90_000) { !LocalDataSourceManager.getInstance(project).isLoading(ds) }
        introspectRef = ref
        if (loaded) null else "isLoading 未结束（90s 超时）"
    }.getOrElse { e ->
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        "introspect 失败: ${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun waitUntil(timeoutMs: Long, intervalMs: Long = 300, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }
}
