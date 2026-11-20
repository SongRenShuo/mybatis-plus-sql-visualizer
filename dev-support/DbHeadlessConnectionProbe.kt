package cn.song.mybatisplus.visualizer.execute

import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** V178：真实本地内存 H2；没有业务凭据、外部数据库或替换平台实现。 */
class DbHeadlessConnectionProbe : BasePlatformTestCase() {
    override fun getProjectDescriptor() = DbIntegrationProjectSupport.descriptor

    fun testH2ConnectionSelectExplainAndRealTarget() {
        val h2 = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/com.h2database/h2/2.3.232")
            .walkTopDown().single { it.name == "h2-2.3.232.jar" }
        val driver = (DatabaseDriverManager.getInstance().getDriver("h2.unified") as DatabaseDriverImpl)
            .copy("v178-h2-local", false)
        driver.setArtifacts(emptyList())
        driver.setAdditionalClasspathElements(com.intellij.util.ui.classpath.SimpleClasspathElementFactory.createElements(
            "jar://${h2.absolutePath.replace('\\', '/')}!/"))
        val manager = LocalDataSourceManager.getInstance(project)
        val ds = manager.createEmpty().apply {
            name = "v178-memory-only"
            url = "jdbc:h2:mem:v178_${java.util.UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=0"
            username = "sa"
            databaseDriver = driver
            driverClass = "org.h2.Driver"
            isAutoSynchronize = false
            passwordStorage = LocalDataSource.Storage.MEMORY
            workingDir = project.basePath
        }
        manager.addDataSource(ds)
        var console: JdbcConsole? = null
        var connection: com.intellij.database.util.GuardedRef<com.intellij.database.dataSource.DatabaseConnection>? = null
        try {
            val file = myFixture.addFileToProject("h2/console.sql", "SELECT 42 AS answer")
            console = JdbcConsole.newConsole(project).forFile(file.virtualFile).fromDataSource(ds).build()
            val builder = DatabaseConnectionManager.getInstance().build(project, ds)
                .setAskPassword(false).setRunConfiguration(console.session.configuration)
            val connect = ApplicationManager.getApplication().executeOnPooledThread<
                com.intellij.database.util.GuardedRef<com.intellij.database.dataSource.DatabaseConnection>> {
                com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                    com.intellij.openapi.util.Computable { builder.createBlockingNonCancellable() },
                    com.intellij.openapi.progress.EmptyProgressIndicator())
            }
            connection = PlatformTestUtil.waitForFuture(connect, 60_000)
            val actual = connection.get()
            assertEquals(ds.uniqueId, actual.connectionPoint.dataSource.uniqueId)
            val query = ApplicationManager.getApplication().executeOnPooledThread<Pair<Int, String>> {
                val statement = actual.remoteConnection.createStatement()
                statement.setQueryTimeout(10)
                try {
                    val rows = statement.executeQuery("SELECT 42 AS answer")
                    val answer = try { check(rows.next()); rows.getInt(1) } finally { rows.close() }
                    val explain = statement.executeQuery("EXPLAIN SELECT 42 AS answer")
                    val plan = try { check(explain.next()); explain.getString(1) } finally { explain.close() }
                    answer to plan
                } finally { statement.close() }
            }
            val (answer, plan) = PlatformTestUtil.waitForFuture(query, 20_000)
            assertEquals(42, answer)
            assertTrue(plan.contains("42"))
            assertEquals(ds.uniqueId, ExecutionDialectGate.fromSession(console.session)!!.stableId)
            assertEquals(ds.uniqueId, ExecutionDialectGate.fromConsole(console)!!.stableId)
            assertEquals("H2", ExecutionDialectGate.fromConsole(console)!!.dialectName)
            println("V178 H2 real remote SELECT=$answer EXPLAIN=$plan sessionConnected=${console.session.isConnected}")
        } finally {
            console?.let { com.intellij.openapi.util.Disposer.dispose(it) }
            connection?.close()
            manager.removeDataSource(ds)
        }
    }

    fun testNaturalKotlinSqlInjectionWithoutTestInjector() {
        checkNaturalInjection("Natural.kt", """fun query() {
            // language=SQL
            val sql = "SELECT 1"
        }""")
    }

    fun testNaturalXmlSqlInjectionWithoutTestInjector() {
        checkNaturalInjection("Natural.xml", """<root>
            <!-- language=SQL -->
            <query>SELECT 1</query>
        </root>""")
    }

    private fun checkNaturalInjection(name: String, text: String) {
        val host = myFixture.configureByText(name, text)
        println("V178 host=$name psi=${host.javaClass.name} language=${host.language.id}")
        val injected = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project)
            .findInjectedElementAt(host, host.text.indexOf("SELECT 1") + 2)?.containingFile
        println("V178 natural $name injected=${injected?.javaClass?.name} language=${injected?.language?.id}")
        assertNotNull("natural host must produce injected PSI without test registration", injected)
        assertTrue("must be real SQL PSI", injected is com.intellij.sql.psi.SqlFile)
    }

    fun testNaturalJavaSqlInjectionWithoutTestInjector() {
        val host = myFixture.configureByText("Natural.java", """class Natural {
            void query() {
                // language=SQL
                String sql = "SELECT 1";
            }
        }""")
        val injected = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project)
            .findInjectedElementAt(host, host.text.indexOf("SELECT 1") + 2)?.containingFile
        println("V178 natural Java injected=${injected?.javaClass?.name} language=${injected?.language?.id}")
        println("V178 IntelliLang=" + com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("org.intellij.intelliLang"))?.isEnabled)
        println("V178 SQL language=" + com.intellij.lang.Language.findLanguageByID("SQL")?.javaClass?.name)
        assertNotNull("natural language=SQL Java host must produce injected PSI, not a test hook", injected)
        assertTrue("must be real SQL PSI", injected is com.intellij.sql.psi.SqlFile)
        val facts = cn.song.mybatisplus.visualizer.sql.IdeaSqlContextBridge.resolve(project, injected, host.virtualFile)
        assertTrue(facts.actualSqlPsi)
        assertEquals(cn.song.mybatisplus.visualizer.sql.IdeaSqlContextBridge.Availability.AVAILABLE, facts.availability)
        println("V178 natural Java actual providers=${facts.providerCandidateIds}")
    }
}
