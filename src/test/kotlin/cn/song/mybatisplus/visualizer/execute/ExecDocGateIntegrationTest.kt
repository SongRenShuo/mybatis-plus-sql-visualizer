package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.testutil.DbTestCredentials
import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DbCredentialManager
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-ExecDocGate：执行文档落盘纪律 DB 集成门禁（T-ExecDocSync2 复盘产物）——
 * **只在 testDatabase 任务运行**（Database 插件真实加载，模拟 Ultimate 沙盒）。
 *
 * 第四轮 File Cache Conflict 为何漏测的答案：默认 test 门禁不带 Database（控制台链不可达），
 * 而 testDatabase 通道里既有 E2E 只断结果 Tab、从未断言「exec 文档不得滞留已修改未保存」——
 * 冲突弹窗的前提（unsaved 态）在纯沙盒里静默不响，缺的是断言。本类把三断言钉进 CI：
 *
 *   ① prepareConsole 后 unsaved=false 且磁盘==SQL（守护 T-EC-01）
 *   ② 二次 prepareConsole（reuseSharedConsole 链）后同断言（守护 F1/G1+G2——回退即红）
 *   ③ 控制台手改后防抖窗内磁盘对齐（守护 F5——回退即红：沙盒无帧失活自动保存）
 *
 * 弹窗终点（MemoryDiskConflictResolver）需外部改盘+真时序，不可全自动化；
 * 「unsaved=false」前提断言即自动化天花板。
 *
 * helpers（readDemoDataSource/createDataSource/introspectDataSource/waitUntil）与
 * DbConsoleIntegrationTest 同源复制——既有两集成类即各自成册惯例，抽共享基类留待需要时。
 *
 * 运行：`./gradlew testDatabase`（demo MySQL 未运行则 skip 不 fail）。
 */
class ExecDocGateIntegrationTest : BasePlatformTestCase() {

    /** introspect 期间持有的连接守卫（close 即释放连接，见 [introspectDataSource] 内注释）。 */
    private var introspectRef: GuardedRef<*>? = null

    /** 控制台编辑器夹具的持有标记（造/释放见 [DbIntegrationProjectSupport.installExecEditor]）。 */
    private var execEditor: Editor? = null

    /**
     * 稳定 light 工程目录（testDatabase 通道五类共用，根因见 [DbIntegrationProjectSupport] 类注释）：
     * 默认描述符下工程目录 = JVM 里首个用例的 unitTest 临时目录，被属主用例 tearDown 递归删除 →
     * exec 文件写盘与 VFS 分叉（基线 `refreshVfs: VFS not visible after refresh`），本类三断言全红。
     */
    override fun getProjectDescriptor(): LightProjectDescriptor = DbIntegrationProjectSupport.descriptor

    override fun setUp() {
        super.setUp()
        DbIntegrationProjectSupport.allowRootAccess(testRootDisposable)
    }

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    /** 与 DbConsoleIntegrationTest 同口径：demo 本地开发库 root/root（application.yml 复制）。 */
    private val dbPassword: String
        get() = DbTestCredentials.password("root")

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

    private fun createDataSource(info: DataSourceInfo): LocalDataSource {
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_demo@localhost-docgate"
        ds.url = info.url
        ds.driverClass = info.driverClass
        ds.username = info.user
        // setDriverRef 包私有——反射调（driver=null → runner 列表空，DbConsoleIntegrationTest diag 实证）
        ds.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(ds, "mysql.8")
        ds.resolveDriver()
        // T-TestDbDriver：驱动种子文件 + 驱动 artifact 版本钉定（管线全貌与 API 实证见助手 KDoc）
        DbIntegrationSeedSupport.prepareDriverPipeline()
        ds.workingDir = project.basePath
        ds.passwordStorage = LocalDataSource.Storage.MEMORY
        mgr.addDataSource(ds)
        DbCredentialManager().setPassword(ds, OneTimeString(dbPassword), LocalDataSource.Storage.MEMORY)
        return ds
    }

    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val mgr = DatabaseConnectionManager.getInstance()
        val builder = mgr.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>> {
            val indicator = EmptyProgressIndicator()
            ProgressManager.getInstance().runProcess(
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

    private fun waitUntil(timeoutMs: Long, intervalMs: Long = 300, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private fun selectorField(name: String): Any? =
        DataSourceSelector::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(DataSourceSelector)

    private fun execFile(): File =
        File(project.basePath!!, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")

    private fun diskText(): String = execFile().readText()

    /** 核心断言：exec 文档不得滞留「已修改未保存」态且磁盘为期望 SQL。 */
    private fun assertExecDocSaved(expectedSql: String, stage: String) {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByIoFile(execFile())
        assertTrue("$stage: exec vf 应在 VFS", vf != null && vf!!.isValid)
        val doc = FileDocumentManager.getInstance().getDocument(vf!!)
        assertTrue("$stage: exec document 应已加载（控制台挂载点）", doc != null)
        assertFalse(
            "$stage: exec 文档不得滞留「已修改未保存」态（File Cache Conflict 前提，T-ExecDocSync2 落盘纪律）",
            FileDocumentManager.getInstance().isDocumentUnsaved(doc!!),
        )
        assertEquals("$stage: 磁盘应与 SQL 一致", expectedSql, diskText())
    }

    /**
     * 落盘纪律端到端：prepare → reuse → 手改防抖落盘。
     * 单方法承载三断言（DbConsoleIntegrationTest 同形态，规避类内测试顺序耦合）。
     */
    fun testExecDocSavedStateAcrossPrepareReuseAndManualEdit() {
        // 显式跳过而非 assumeTrue：平台 runner 会把 AssumptionViolatedException 记为 failure
        // 红掉整个 testDatabase 任务（四集成类既有痛点，20260917 实测）；基建可用时全量真跑
        if (!ExecuteSqlSupport.isAvailable()) {
            println("[T-ExecDocGate] SKIP: Database 插件未加载（应在 testDatabase 任务下运行）")
            return
        }
        val info = readDemoDataSource("mpj_demo")
        if (info == null) {
            println("[T-ExecDocGate] SKIP: demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}")
            return
        }

        // ⓪ 清空应用级残留数据源（跨用例存活，多一条即退化成多 runner 弹窗分支）——
        //    必须在建本用例数据源**之前**：放在其后会把刚建的数据源一并清掉
        DbIntegrationProjectSupport.clearDataSources(project)
        val ds = createDataSource(info)
        val introspectError = introspectDataSource(ds)
        if (introspectError != null) {
            println("[T-ExecDocGate] SKIP: introspect 失败（demo MySQL 未运行 / 驱动管线未修复）: $introspectError")
            return
        }

        // ① prepareConsole(sqlA)：attachConsole 后文档与磁盘必须已收口（T-EC-01）
        val sqlA = "SELECT 1 AS docgate_a"
        // ①a 前置：发现链收敛到恰好 1 个 runner（facade 相对 LocalDataSourceManager 有异步窗口，
        //     见 DbIntegrationProjectSupport.awaitRunners KDoc）
        val psiA = DbIntegrationProjectSupport.ensureExecFile(project, sqlA)
        assertNotNull("临时执行 PsiFile 应可构造（VFS/方言映射）", psiA)
        val runners = DbIntegrationProjectSupport.awaitRunners(psiA!!.second, sqlA, expected = 1)
        assertEquals(
            "唯一数据源契约要求发现链恰好 1 个 runner（实际=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}）",
            1,
            runners.size,
        )
        // ①b 控制台编辑器夹具：控制台文档即 exec 文件文档（JdbcConsole.getDocument() 字节码实证），
        //     headless 下无人为它建编辑器 → prepareInConsole 的 getEditors(consoleDocument) 恒空 →
        //     静默返回（基线 idea.log `[M36] editor=null (async wait)`，120s 不就绪且 errors=[]）
        execEditor = DbIntegrationProjectSupport.installExecEditor(project)
        assertNotNull("控制台编辑器夹具应可建（prepareConsole 依赖 getEditors(consoleDocument) 非空）", execEditor)

        var readyA = false
        val errorsA = mutableListOf<String>()
        DataSourceSelector.prepareConsole(
            project = project,
            sql = sqlA,
            onConsole = { readyA = true },
            onEditor = { },
            onError = { errorsA.add(it) },
            callback = { },
        )
        val readyAInTime = DbIntegrationProjectSupport.waitWithPump(120_000) { readyA }
        if (!readyAInTime) {
            println("[DB-IT] prepareConsole 未就绪诊断: ${DbIntegrationProjectSupport.diagnoseConsoleState(project, ds)}")
        }
        assertTrue(
            "prepareConsole 120s 内未就绪 (errors=$errorsA)",
            readyAInTime,
        )
        assertExecDocSaved(sqlA, "① prepare 后")

        // ② 二次 prepareConsole(sqlB)：命中 reuseSharedConsole（G1 编辑器 setText + F1 vf 同步落盘）
        val sqlB = "SELECT 2 AS docgate_b"
        var readyB = false
        val errorsB = mutableListOf<String>()
        DataSourceSelector.prepareConsole(
            project = project,
            sql = sqlB,
            onConsole = { readyB = true },
            onEditor = { },
            onError = { errorsB.add(it) },
            callback = { },
        )
        assertTrue(
            "二次 prepareConsole 120s 内未就绪 (errors=$errorsB)",
            DbIntegrationProjectSupport.waitWithPump(120_000) { readyB },
        )
        // 回退 F1 时：编辑器 setText 只改内存，磁盘停留 sqlA、文档滞留 unsaved → 此处红
        assertExecDocSaved(sqlB, "② reuse 后")

        // ③ 模拟用户控制台手改（WriteAction setText，无任何插件落盘伴随）→ F5 防抖自动落盘
        val sqlC = "SELECT 3 AS docgate_c"
        val editor = selectorField("preparedEditor") as com.intellij.openapi.editor.Editor
        WriteAction.runAndWait<Throwable> { editor.document.setText(sqlC) }
        val autosaved = DbIntegrationProjectSupport.waitWithPump(10_000) { diskText() == sqlC }
        assertTrue(
            "控制台手改后 10s 内磁盘未对齐（F5 防抖自动落盘失效？回退即此红）",
            autosaved,
        )
        assertExecDocSaved(sqlC, "③ 手改防抖后")
    }

    override fun tearDown() {
        introspectRef?.let { runCatching { it.close() } }
        introspectRef = null
        // 夹具编辑器必须先释放：平台 TestApplicationKt.checkEditorsReleased 对每个存活编辑器硬抛
        DbIntegrationProjectSupport.detachDbnConsole(project)
        DbIntegrationProjectSupport.releaseExecEditors(project)
        // 单例的 prepared/shared 槽位跨用例存活——面板关闭语义的清理是 releaseForPanel
        // （clearPrepared + dropSharedConsole + 清 pendingOnConsole），用例边界用它，避免下个用例
        // 经 reuseSharedConsole 命中本用例的控制台/编辑器（详见 DataSourceSelectorIntegrationTest 同款注释）
        runCatching { DataSourceSelector.releaseForPanel() }
        // T-TestDbDriver：object 单例 temp Psi 缓存跨用例存活，fixture 临时目录每用例一换 → 显式清。
        runCatching { DataSourceSelector.clearCachedTempPsiFileForTest() }
        super.tearDown()
    }

}
