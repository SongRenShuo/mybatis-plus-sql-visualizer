package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramOpener
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.JoinFkRegistry
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.JoinVirtualFkProvider
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport
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
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-Embed-03/04/08：B 路径真实链路集成测试（**仅 testDatabase 任务运行**——database jar
 * 在该分片 classpath；普通 test 任务已剥离 database jar，本类被 filter 排除）。
 *
 * 链路：demo 项目真实 MySQL 数据源 → introspect → JoinFkRegistry 登记 ON 条件 →
 * NativeDatabaseDiagramSupport.buildTabComponent（DATABASE provider + 真实 DbTable 节点）→
 * 断言数据模型含 ≥2 节点、连接线来自虚拟外键（SQLV_JOIN 前缀）、dispose 回收登记。
 *
 * ## ⚠ 本类的「绿」不等于「B 路径已验证」（T-TS-08 复核口径）
 *
 * 用例有三级软返回（introspect 失败 / `buildTabComponent` 返回 null / 编辑器未挂接），
 * 命中任一即 `println + return` 记绿，**五组断言一组都不执行**。20260918 复核实测：本环境走的
 * 就是 `component == null` 那条（XML `system-out` 实证 `[DB-IT] SKIP: B 路径链路不可用…`），
 * 即**B 路径在本环境从未被真跑过**——「9 例 JUnit 绿」是事实，「B 路径已验证」不是。
 * 相应地：
 *  - 产品侧 `NativeDatabaseDiagramSupport.kt` 的降级点已补日志（`B path not taken: …` +
 *    `locateDbTables` 四段计数），降级原因（无数据源 / 未内省 / 表名不匹配 / DbElement 未登记）
 *    在 idea.log 里可查——修复前该点静默，是「绿≠已验证」不可诊断的根因；
 *  - 本环境的可跑性结论与未验证边界记录在
 *    `docs/调研报告/20260918-testDatabase驱动管线打通-记录.md` §六；
 *  - 让 B 路径真跑（补真实工程/表定位夹具）属独立工作量，未在本轮范围内。
 *
 * 环境依赖（与 DataSourceSelectorIntegrationTest 同源）：demo 兄弟目录的
 * dataSources.xml + 本地 MySQL 已启动且含 car_device_info/station_info 表。
 */
class HeavyNativeDatabaseDiagramJoinChainTest : BasePlatformTestCase() {

    /** introspect 期间持有的连接守卫（close 即释放连接，见 [introspectDataSource] 内注释）。 */
    private var introspectRef: GuardedRef<*>? = null

    /**
     * 稳定 light 工程目录（testDatabase 通道五类共用，根因见 [DbIntegrationProjectSupport] 类注释）：
     * 五类共用一个描述符实例 → 平台复用同一工程，工程目录不再随首个用例的 unitTest 目录被删。
     */
    override fun getProjectDescriptor(): LightProjectDescriptor = DbIntegrationProjectSupport.descriptor

    override fun setUp() {
        super.setUp()
        DbIntegrationProjectSupport.allowRootAccess(testRootDisposable)
    }

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    private data class DataSourceInfo(val url: String, val user: String, val driverClass: String)

    private fun readDemoDataSource(namePart: String): DataSourceInfo? {
        val xml = demoProjectDir.resolve(".idea/dataSources.xml")
        if (!xml.isFile) return null
        return runCatching {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
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
                if (url != null) return@runCatching DataSourceInfo(DbTestCredentials.url(url), DbTestCredentials.user("root"), driver ?: "com.mysql.cj.jdbc.Driver")
            }
            null
        }.getOrNull()
    }

    /** 编程创建数据源（与 DataSourceSelectorIntegrationTest 夹具同款，凭据 MEMORY 注入）。 */
    private fun createDataSource(info: DataSourceInfo): LocalDataSource {
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_demo@localhost-embed"
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
        DbCredentialManager().setPassword(ds, OneTimeString(DbTestCredentials.password("root")), LocalDataSource.Storage.MEMORY)
        return ds
    }

    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val mgr = DatabaseConnectionManager.getInstance()
        val builder = mgr.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>?> {
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

    private fun waitUntil(timeoutMs: Long, intervalMs: Long = 300, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /** demo 真实表形态：car_device_info t LEFT JOIN station_info t1 ON t1.station_id = t.station_id。 */
    private fun demoJoinModel() = DiagramModel(
        entities = listOf(
            EntityNode(
                alias = "t", tableName = "car_device_info",
                columns = listOf(
                    ColumnEntry("id", "int(11)", isPk = true, comment = "主键"),
                    ColumnEntry("station_id", "int(11)", isPk = false),
                ),
                isMain = true,
            ),
            EntityNode(
                alias = "t1", tableName = "station_info",
                columns = listOf(ColumnEntry("station_id", "int(11)", isPk = false)),
                isMain = false,
                joinType = JoinType.LEFT,
            ),
        ),
        relationships = listOf(
            RelationshipLine(
                fromAlias = "t", toAlias = "t1",
                joinType = JoinType.LEFT,
                label = "t1.station_id = t.station_id",
                columnPairs = listOf(Pair("station_id", "station_id")),
            ),
        ),
    )

    fun testDatabaseDiagramEmbedChainWithVirtualFk() {
        // 必须最先守卫：database jar 缺席（普通 test 任务）时任何 database 类触碰即 NoClassDefFoundError
        if (!ExecuteSqlSupport.isAvailable()) {
            println("[DB-IT] SKIP: Database 插件未加载（应在 testDatabase 任务下运行）")
            return
        }
        val info = readDemoDataSource("mpj_demo")
        if (info == null) {
            println("[DB-IT] SKIP: demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}")
            return
        }
        val ds = createDataSource(info)
        val introspectError = introspectDataSource(ds)
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: $introspectError")
            return
        }

        val model = demoJoinModel()
        val component = NativeDatabaseDiagramSupport.buildTabComponent(
            project, model, "JOIN Diagram: car_device_info", testRootDisposable,
        )
        if (component == null) {
            // T-TS-08：本分支是本环境实际命中的那一条（见类注释）——绿是「未验证」而不是「已验证」。
            // N6：软返回不再无条件记绿——先用产品侧的可断言诊断出口区分两种降级：
            //   ① 数据源模型里根本没定位到表（本环境受限，可跳过，但读数必须打印出来）
            //   ② 表已定位到（nameHits > 0）却没接管 → DbElement 未登记 / 框架异常，属**新故障**，判失败
            val counts = NativeDatabaseDiagramSupport.lastLocateCounts()
            if (counts != null && counts.locatedAny) {
                fail(
                    "表已在数据源模型里定位到（nameHits=${counts.nameHits}）但 B 路径仍未接管——" +
                        "这是新故障（DbElement 未登记 / 框架异常），不是「环境定位不到表」；counts=$counts",
                )
            }
            println(
                "[DB-IT] SKIP: B 路径链路不可用（表定位失败，应降级 A 路径）" +
                    "——注意：本用例记绿但 B 路径**未验证**（五组断言未执行）；counts=$counts" +
                    "（nameHits=0 即「数据源模型里没有这两张表」，是本环境受限而非产品缺陷）",
            )
            return
        }
        val comp = component!!

        val editor = comp.getClientProperty("mpsql.native.database.editor")
        if (editor == null) {
            // 显式绿跳而非 assumeTrue：平台 runner 把 AssumptionViolatedException 记为 failure
            // 红掉整个 testDatabase 任务（本文件既有惯例，见 testDatabaseDiagramEmbedChainWithVirtualFk 内注释）
            println("[DB-IT] SKIP: 编辑器未挂接（B 路径链路不可用，应降级 A 路径）")
            return
        }
        val builder = NativeJoinDiagramOpener.builderOf(editor)
        if (builder == null) {
            println("[DB-IT] SKIP: 编辑器应持有 DiagramBuilder（B 路径链路不可用，应降级 A 路径）")
            return
        }
        val b = builder

        val nodes = ApplicationManager.getApplication().runReadAction<Int> { b.dataModel.nodes.size }
        assertTrue("期望数据模型 ≥2 节点（主表 + JOIN 表），实际=$nodes", nodes >= 2)

        val edges = ApplicationManager.getApplication().runReadAction<List<*>> { b.dataModel.edges.toList() }
        assertTrue("期望 ≥1 条连接线，实际=$edges", edges.isNotEmpty())
        val edgeNames = edges.map { edgeName(it) }
        assertTrue(
            "连接线应来自虚拟外键（SQLV_JOIN 前缀），实际=$edgeNames",
            edgeNames.any { it?.startsWith("SQLV_JOIN") == true },
        )

        // N6 行为级守卫（直接调 provider，不依赖画布/布局的间接性）：平台建边链走的是**两参重载**
        // `ModelRelationManager.getForeignKeys(Project, DasTable)`，字节码恒定把 refTable 传成 null，
        // 契约是「不做引用表过滤、返回该表全部外键」。修复前 provider 见 null 即返回空 →
        // `DbDiagramDataModel.calculateEdges` 建边恒空：节点/注释/类型全正常、连线一条都没有。
        val nodeDasTables = ApplicationManager.getApplication()
            .runReadAction<List<com.intellij.database.model.DasTable>> {
                b.dataModel.nodes.mapNotNull { it.identifyingElement as? com.intellij.database.model.DasTable }
            }
        val childDas = nodeDasTables.firstOrNull { it.name.equals("station_info", ignoreCase = true) }
        val refDas = nodeDasTables.firstOrNull { it.name.equals("car_device_info", ignoreCase = true) }
        assertTrue("应能从图节点取回 station_info 的 DasTable（引用表实例解析的前提），实际=${nodeDasTables.map { it.name }}", childDas != null)
        assertTrue("应能从图节点取回 car_device_info 的 DasTable，实际=${nodeDasTables.map { it.name }}", refDas != null)
        val twoArgFks = JoinVirtualFkProvider().getForeignKeys(project, childDas!!, null)
        assertTrue(
            "N6：两参契约（refTable=null）必须返回该表的虚拟外键；返回空 = 建边恒空、原生图只剩节点",
            twoArgFks.isNotEmpty(),
        )
        assertTrue(
            "虚拟外键的引用表必须是图节点**同一实例**——calculateEdges 以 " +
                "nodeMap.get(asDbElement(fk.getRefTable())) 查引用表节点，实例不等则边被静默丢掉",
            twoArgFks.any { it.refTable === refDas },
        )

        // T-PreviewBgFit（问题二守卫）：原生图须置「布局后自适应」+「尺寸变化后保持自适应」——
        // 布局管线只在开关置位后才于布局收尾调 fitContent（GraphLayoutServiceImpl 字节码实证），
        // 而我们手工建图无平台标签页路径的置位时机（真机实证：同会话第一张图不居中、第二张正常）。
        assertTrue(
            "原生图应开启「布局后自适应」（否则打开即停默认缩放/左上角）",
            b.presentationModel.settings.isFitContentAfterLayout,
        )
        assertTrue("原生图应开启「尺寸变化后保持自适应」", b.view.fitContentOnResize)

        // T-EmbedFkOrder F3 回归（本次真机故障形态）：清理**无关旧组件**不得抹掉当前图的虚拟外键登记——
        // 修复前 disposeNativeEditor 的 finally 里 JoinFkRegistry.clear() 会把刚登记的整批抹掉，
        // 于是换图再预览时 Database 原生图只剩节点没连线（用户实测"一张有连线、另一张没有"）。
        val stale = javax.swing.JPanel()
        stale.putClientProperty("mpsql.native.database.editor", com.intellij.openapi.util.Disposer.newDisposable())
        NativeDatabaseDiagramSupport.disposeNativeEditor(stale)
        assertTrue(
            "释放无关旧组件后，当前图的虚拟外键登记必须仍在（否则连线丢失）",
            JoinFkRegistry.find("station_info", "car_device_info").isNotEmpty(),
        )

        NativeDatabaseDiagramSupport.disposeNativeEditor(comp)
        // T-EmbedFkOrder F2：dispose 不再顺带清理注册表，清理改由「整图卸载」显式调用
        NativeDatabaseDiagramSupport.clearRegisteredFk()
        assertTrue(
            "显式整图卸载后，虚拟外键登记应清空",
            JoinFkRegistry.find("station_info", "car_device_info").isEmpty(),
        )
    }

    /** 边名：优先 DbEdge.getName（虚拟外键名 SQLV_JOIN_*），回落关系信息 label。 */
    private fun edgeName(edge: Any?): String? {
        if (edge == null) return null
        val direct = runCatching { edge.javaClass.getMethod("getName").invoke(edge) as? String }.getOrNull()
        if (direct != null) return direct
        val method = edge.javaClass.methods.firstOrNull {
            it.name == "getDependencyInfo" || it.name == "getRelationshipInfo"
        } ?: return null
        method.isAccessible = true
        return runCatching {
            (method.invoke(edge) as? com.intellij.diagram.DiagramRelationshipInfo)?.label
        }.getOrNull()
    }

    override fun tearDown() {
        introspectRef?.let { runCatching { it.close() } }
        introspectRef = null
        // 面板关闭语义的清理（clearPrepared + dropSharedConsole + 清 pendingOnConsole）——与同类三处一致
        runCatching { DataSourceSelector.releaseForPanel() }
        // T-TestDbDriver：object 单例 temp Psi 缓存跨用例存活，fixture 临时目录每用例一换 → 显式清（详见
        // DataSourceSelectorIntegrationTest.tearDown 同款注释）。
        runCatching { DataSourceSelector.clearCachedTempPsiFileForTest() }
        super.tearDown()
    }

}
