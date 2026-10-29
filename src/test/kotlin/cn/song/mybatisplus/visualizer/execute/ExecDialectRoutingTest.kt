package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.execute.fixture.HiddenRunnerFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O-136 T-PreviewExecDsDialect：预览执行链「数据源方言 → 草稿文件 SQL 方言 → Runner 集合」路由核心。
 *
 * 判定面（计划 §四 D1/D2/D3 与 §六 V3/V4）：
 *  - 目标方言 id 决策（平台 DAS 方言优先，URL 推断兜底，皆无 null=保持 MySQL 现状）；
 *  - 目标数据源解析（last-used 记忆 UUID→名字→插件默认数据源→null）；
 *  - URL 子协议 → 平台 SQL 方言 language id 兜底（平台不可用/方言缺位时诚实降级，不猜）；
 *  - 组 Runner 展开（"All" 组的 sub-runners 浮出顶层并按显示名去重——插件弹窗不展开子级，
 *    导致方言不匹配的数据源（DM 等）藏在组项里不可选；**空子项组保留原项**：数据源
 *    introspect 完成前组项子 Runner 为空，此时组项仍是唯一执行句柄，剔除会清空 Runner
 *    列表并终结执行链——20261014 testDbConsole 集成回归实证）；
 *  - 空 Runner 文案选择（有在册数据源=方言不匹配可行动文案；无在册=既有空数据源文案）；
 *  - 生产接线守卫（源码扫描）：路由调用、展开调用、方言不匹配文案三处接线在场——
 *    摘掉接线即红（反向验证锚点）。
 *
 * 纯逻辑层无平台依赖（反射段对桩对象 duck-typing），可进 testFast 快桶。
 */
class ExecDialectRoutingTest {

    // ------------------------------------------------------------------
    // 桩与工具
    // ------------------------------------------------------------------

    /** duck-typing 桩：只需反射可见的 getUrl 方法（生产对 LocalDataSource 同名反射）。 */
    @Suppress("unused")
    private class StubDs(private val url: String?) {
        fun getUrl(): String? = url
    }

    private data class R(val name: String, val subs: List<R>? = null)

    /** 生产展开链按 getDisplayName 反射取名；叶子桩用 public 方法即可。 */
    private class LeafRunner(private val displayName: String) {
        fun getDisplayName(): String = displayName
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    private fun mainSource(relativePath: String): String =
        File(repoRoot(), "src/main/kotlin/$relativePath").readText()

    // ------------------------------------------------------------------
    // 目标方言 id 决策（D1：平台优先、URL 兜底、皆无保持 MySQL）
    // ------------------------------------------------------------------

    @Test
    fun `目标方言id_平台方言优先`() {
        assertEquals("DM", ExecDialectRouting.targetLanguageId("DM", "MySQL"))
        assertEquals("PostgreSQL", ExecDialectRouting.targetLanguageId("PostgreSQL", "Oracle"))
    }

    @Test
    fun `目标方言id_平台缺失用URL兜底`() {
        assertEquals("DM", ExecDialectRouting.targetLanguageId(null, "DM"))
        assertEquals("MySQL", ExecDialectRouting.targetLanguageId("", "MySQL"))
    }

    @Test
    fun `目标方言id_两者皆无返回null保持MySQL现状`() {
        assertNull(ExecDialectRouting.targetLanguageId(null, null))
        assertNull(ExecDialectRouting.targetLanguageId("", ""))
    }

    // ------------------------------------------------------------------
    // 目标数据源解析（last-used 记忆 → 插件默认 → null）
    // ------------------------------------------------------------------

    @Test
    fun `目标数据源解析_记忆UUID命中优先`() {
        val byId = Any()
        val chosen = ExecDialectRouting.resolveTargetDataSourceFromInputs(
            memory = ExecDialectRouting.MemoryRef("uuid-1", "old-name"),
            dataSourceById = { if (it == "uuid-1") byId else null },
            registeredDataSources = listOf(Any()),
            dataSourceName = { "?" },
            defaultDataSource = { Any() },
        )
        assertTrue("UUID 命中的数据源应被优先选中", chosen === byId)
    }

    @Test
    fun `目标数据源解析_UUID失效退名字唯一命中`() {
        val byName = Any()
        val chosen = ExecDialectRouting.resolveTargetDataSourceFromInputs(
            memory = ExecDialectRouting.MemoryRef("gone", "dm@prod"),
            dataSourceById = { null },
            registeredDataSources = listOf(Any(), byName),
            dataSourceName = { if (it === byName) "dm@prod" else "mysql@local" },
            defaultDataSource = { Any() },
        )
        assertTrue("UUID 失效应按显示名命中", chosen === byName)
    }

    @Test
    fun `目标数据源解析_无记忆落默认数据源`() {
        val default = Any()
        val chosen = ExecDialectRouting.resolveTargetDataSourceFromInputs(
            memory = null,
            dataSourceById = { null },
            registeredDataSources = listOf(Any()),
            dataSourceName = { "?" },
            defaultDataSource = { default },
        )
        assertTrue("无 last-used 记忆应落插件默认数据源", chosen === default)
    }

    @Test
    fun `目标数据源解析_全空返回null`() {
        val chosen = ExecDialectRouting.resolveTargetDataSourceFromInputs(
            memory = ExecDialectRouting.MemoryRef(null, null),
            dataSourceById = { null },
            registeredDataSources = emptyList(),
            dataSourceName = { "?" },
            defaultDataSource = { null },
        )
        assertNull("记忆/默认皆空应返回 null（调用方保持 MySQL 现状）", chosen)
    }

    // ------------------------------------------------------------------
    // URL 兜底（平台方言探测失败时按插件 URL 推断映射平台 language id）
    // ------------------------------------------------------------------

    @Test
    fun `URL兜底_dm子协议映射平台DM方言id`() {
        val id = ExecDialectRouting.urlFallbackId(
            StubDs("jdbc:dm://dbx-test-host:5236"),
            listOf("MySQL", "PostgreSQL", "DM"),
        )
        assertEquals("jdbc:dm 应映射平台 DM 方言 id", "DM", id)
    }

    @Test
    fun `URL兜底_平台无该方言id时诚实返回null`() {
        // 243 形态：平台 SQL 方言清单没有 DM（字节码实证 243 方言集无 dm.xml）——
        // 不得猜近似方言（别名 Oracle/Generic 都会让 Runner 枚举落空或解析错位），诚实 null=保持 MySQL
        val id = ExecDialectRouting.urlFallbackId(
            StubDs("jdbc:dm://host:5236"),
            listOf("MySQL", "PostgreSQL", "Oracle", "TSQL"),
        )
        assertNull("平台方言清单缺位时应返回 null", id)
    }

    @Test
    fun `URL兜底_未知子协议或空URL返回null`() {
        assertNull(ExecDialectRouting.urlFallbackId(StubDs("jdbc:unknowndb://x"), listOf("MySQL")))
        assertNull(ExecDialectRouting.urlFallbackId(StubDs(null), listOf("MySQL")))
        assertNull(ExecDialectRouting.urlFallbackId(null, listOf("MySQL")))
    }

    @Test
    fun `平台方言探测_桩对象反射失败降级null`() {
        // 桩没有平台的 findSqlDialect 链路（非平台 JVM 类不存在 / 签名不合）——必须降级 null 而非抛
        assertNull(ExecDialectRouting.platformDialectId(StubDs("jdbc:dm://host")))
        assertNull(ExecDialectRouting.platformDialectId(null))
    }

    @Test
    fun `候选方言id表_插件方言名与注册表命名对齐`() {
        // DialectRegistry 的 name 是本表键空间（DM/KingBase 为中缀名）——名字漂移会让 URL 兜底整链失效
        val keys = ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS.keys
        for (name in listOf("MySQL", "MariaDB", "PostgreSQL", "Oracle", "SQL Server", "H2", "SQLite", "DM（达梦）", "KingBase（人大金仓）", "OceanBase", "openGauss", "GaussDB", "HighGo", "Vastbase")) {
            assertTrue("候选方言 id 表缺插件方言名「$name」（与 DialectRegistry 命名漂移）", name in keys)
        }
    }

    @Test
    fun `T-XcDialects3_瀚高海量候选链恒落PG兜底`() {
        // 照 GaussDB 同款「本名 → PG 兜底」写法；262 平台无 HighGo / Vastbase 方言模块（P-11
        // 逐包列举实证），平台可用清单只有 PG 时兜底命中 PostgreSQL，清单连 PG 都没有时诚实 null
        assertEquals(listOf("HighGo", "PostgreSQL"), ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["HighGo"])
        assertEquals(listOf("Vastbase", "PostgreSQL"), ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["Vastbase"])
        assertEquals(
            "PostgreSQL",
            ExecDialectRouting.urlFallbackId(StubDs("jdbc:highgo://h:5866/db"), listOf("MySQL", "PostgreSQL")),
        )
        assertEquals(
            "PostgreSQL",
            ExecDialectRouting.urlFallbackId(StubDs("jdbc:vastbase://h:5432/db"), listOf("PostgreSQL")),
        )
        assertEquals(
            "PostgreSQL",
            ExecDialectRouting.urlFallbackId(StubDs("jdbc:dws:iam://h:5432/db"), listOf("PostgreSQL")),
        )
        assertNull(
            "平台连 PG 都没有时不得猜近似方言",
            ExecDialectRouting.urlFallbackId(StubDs("jdbc:highgo://h/db"), listOf("MySQL", "Oracle")),
        )
    }

    // ------------------------------------------------------------------
    // 组 Runner 展开（方言不匹配数据源从死组项浮出）
    // ------------------------------------------------------------------

    @Test
    fun `组Runner展开_子Runner浮出且按名去重`() {
        val mysql1 = R("mysql1@local")
        val dm1 = R("dm@prod")
        val all = R("All Data Sources", listOf(R("mysql1@local"), dm1))
        val expanded = ExecDialectRouting.expandGroupRunners(
            listOf(mysql1, all),
            nameOf = { it.name },
            subRunnersOf = { it.subs },
        )
        assertEquals("组项应被剔除、子 Runner 去重后浮出", listOf("mysql1@local", "dm@prod"), expanded.map { it.name })
        assertTrue("既有顶层 Runner 实例应原样保留", expanded.first() === mysql1)
        assertTrue("新浮出的子 Runner 实例应保留", expanded.last() === dm1)
    }

    @Test
    fun `组Runner展开_无组时原样返回`() {
        val a = R("a")
        val b = R("b")
        val expanded = ExecDialectRouting.expandGroupRunners(listOf(a, b), nameOf = { it.name }, subRunnersOf = { it.subs })
        assertEquals(2, expanded.size)
        assertTrue(expanded[0] === a)
        assertTrue(expanded[1] === b)
    }

    @Test
    fun `组Runner展开_空子Runner的组项保留`() {
        // 20261014 集成回归（testDbConsole）实证：平台「All」组在数据源 introspect 完成前
        // 子 Runner 为空，但组项本身仍是唯一执行句柄——剔除会把 Runner 列表清空、
        // 命中空 Runner 归因文案并终结执行链。无子项可浮出时按普通项保留原实例。
        val all = R("All Data Sources", emptyList())
        val expanded = ExecDialectRouting.expandGroupRunners(
            listOf(R("a"), all),
            nameOf = { it.name },
            subRunnersOf = { it.subs },
        )
        assertEquals("空子项组无子项可浮出，应保留原项", listOf("a", "All Data Sources"), expanded.map { it.name })
        assertTrue("保留的应是原组项实例", expanded.last() === all)
    }

    @Test
    fun `组Runner展开_包私有组项必须可访问反射`() {
        // 平台 DatabaseRunners$3/$4$1 是包私有类：生产反射漏 isAccessible=true 时
        // Method.invoke 抛 IllegalAccessException 并被 runCatching 吞掉 → 组项原样保留；
        // 其 runnable 是 EmptyRunnable，执行后永远等不到 session，直到准备门 60s 超时。
        val leaf1 = LeafRunner("mysql@local")
        val leaf2 = LeafRunner("dm@prod")
        val group = HiddenRunnerFixture.group("All Data Sources", listOf<Any>(leaf1, leaf2))
        val expanded = ExecDialectRouting.expandGroupRunners(listOf(group))
        assertEquals(
            "包私有组项未展开——组项会被当成普通项执行空操作并悬挂到超时",
            listOf("mysql@local", "dm@prod"),
            expanded.map { DataSourceSelector.getRunnerName(it) },
        )
    }

    @Test
    fun `组Runner展开_嵌套组递归浮出`() {
        val leaf1 = LeafRunner("mysql@local")
        val leaf2 = LeafRunner("dm@prod")
        val inner = HiddenRunnerFixture.group("Inner", listOf<Any>(leaf2))
        val outer = HiddenRunnerFixture.group("Outer", listOf<Any>(leaf1, inner))
        val expanded = ExecDialectRouting.expandGroupRunners(listOf(outer))
        assertEquals(
            "两级组（平台 DatabaseRunners$4 → $4$1 形态）应递归展开到叶子",
            listOf("mysql@local", "dm@prod"),
            expanded.map { DataSourceSelector.getRunnerName(it) },
        )
    }

    @Test
    fun `组Runner展开_自引用组项超深度剔除`() {
        val cycle = HiddenRunnerFixture.cycle("Cycle")
        val expanded = ExecDialectRouting.expandGroupRunners(listOf(cycle))
        assertTrue(
            "自引用组项不可能展开；保留它等于把空操作句柄和 60s 悬挂留给用户，必须剔除",
            expanded.isEmpty(),
        )
    }

    @Test
    fun `空操作Runner识别_EmptyRunnable与普通动作`() {
        assertTrue(
            "平台组项的 runnable 是 EmptyRunnable，必须在执行前识别",
            ExecDialectRouting.isNoOpRunner(
                HiddenRunnerFixture.withRunnable(com.intellij.openapi.util.EmptyRunnable.getInstance()),
            ),
        )
        assertFalse(
            "普通 Runner 不得被误判为空操作",
            ExecDialectRouting.isNoOpRunner(HiddenRunnerFixture.withRunnable(Runnable { })),
        )
        assertFalse("没有 runnable 字段的桩对象应降级为非空操作", ExecDialectRouting.isNoOpRunner(LeafRunner("leaf")))
    }

    // ------------------------------------------------------------------
    // 空 Runner 文案选择（D3/V3：方言不匹配给可行动文案）
    // ------------------------------------------------------------------

    @Test
    fun `空Runner文案_有在册数据源给方言不匹配文案`() {
        assertEquals("execute.datasource.dialectMismatch", ExecDialectRouting.emptyRunnersHintKey(true))
    }

    @Test
    fun `空Runner文案_无在册数据源保持既有文案`() {
        assertEquals("execute.datasource.empty", ExecDialectRouting.emptyRunnersHintKey(false))
    }

    // ------------------------------------------------------------------
    // 生产接线守卫（源码扫描；摘接线即红——反向验证锚点）
    // ------------------------------------------------------------------

    @Test
    fun `接线守卫_执行文件方言映射走数据源方言路由`() {
        val selector = mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt")
        val results = mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        assertTrue(
            "mapDialectBeforePsi 未接数据源方言路由（resolveTargetLanguage 缺失）——DM 等非 MySQL 数据源将从预览执行链隐形",
            selector.contains("resolveTargetLanguage(") && selector.contains("resolveTargetDataSource("),
        )
        assertTrue(
            "createTempPsiFile 缓存命中路径未对齐方言映射（缓存复用会跳过路由）",
            Regex("""consumeCachedTempPsiFile\(project\)[\s\S]{0,200}mapDialectBeforePsi""").containsMatchIn(results),
        )
        val prewarmBody = results.substringAfter("internal fun DataSourceSelector.prewarmExecTempFileInner(")
            .substringBefore("internal fun DataSourceSelector.buildConsoleSplitPane(")
        assertTrue("prewarmExecTempFileInner 函数切片为空（扫描锚点漂移）", prewarmBody.contains("val ioFile"))
        assertTrue(
            "prewarmExecTempFileInner 未走统一方言映射入口（BGT 预热仍硬编码 MySQL）",
            prewarmBody.contains("mapDialectBeforePsi(project, vf, LOG, renderedDialectName)"),
        )
    }

    @Test
    fun `接线守卫_预览临时PSI消费实际生成方言`() {
        val panel = mainSource("cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt")
        val lifecycle = mainSource("cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelEditorLifecycle.kt")
        val resultView = mainSource("cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelResultView.kt")
        assertTrue("面板须保存本次预览的目标方言，供早到和晚到预热共同读取",
            panel.contains("previewDesiredDialectName"))
        assertTrue("预览结果进入面板后须在渲染前同步临时 PSI 方言",
            resultView.contains("syncPreviewDialectMapping(result)"))
        assertTrue("预热映射不得继续无条件写死 MySQL，须解析插件实际生成方言",
            lifecycle.contains("resolvePluginDialectLanguage") &&
                lifecycle.contains("previewDesiredDialectName"))
        assertTrue("平台没有对应方言时必须降到 GenericSQL，不得把已确定的厂商方言冒充 MySQL",
            lifecycle.contains("GENERIC_LANGUAGE_ID") &&
                mainSource("cn/song/mybatisplus/visualizer/execute/ExecDialectRouting.kt")
                    .contains("GENERIC_LANGUAGE_ID = \"GenericSQL\""))
        assertTrue("同目录幂等必须同时比较 language id，不能让先映射的 MySQL 阻断 Oracle/PG 重映射",
            panel.contains("previewDialectMappedLanguageId") &&
                lifecycle.contains("previewDialectMappedLanguageId"))
        assertTrue("结果在首次映射与 PSI 创建之间到达时，发布预热产物前须补一次方言同步",
            Regex("previewTempFile = vf[\\s\\S]{0,300}previewDesiredDialectName[\\s\\S]{0,200}mapPreviewDialectBeforePsi")
                .containsMatchIn(lifecycle))
    }

    @Test
    fun `接线守卫_弹窗前展开组Runner`() {
        val results = mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        assertTrue(
            "chooseAndPrepare 未展开组 Runner——方言不匹配的数据源（DM 等）将藏在死组项里不可选",
            Regex("""getAttachDataSourceRunners\(psiFile, submitSql, sessionConsumer\)[\s\S]{0,800}expandGroupRunners\(enumerated\)""")
                .containsMatchIn(results),
        )
    }

    @Test
    fun `接线守卫_空Runner分支给方言不匹配文案`() {
        val results = mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        assertTrue(
            "chooseAndPrepare 空 Runner 分支未接方言不匹配可行动文案（D3/V3）——用户将继续面对笼统空数据源提示或超时",
            results.contains("execute.datasource.dialectMismatch"),
        )
        val en = File(repoRoot(), "src/main/resources/messages/MpSqlVisualizerBundle.properties").readText()
        val zh = File(repoRoot(), "src/main/resources/messages/MpSqlVisualizerBundle_zh.properties").readText()
        val enLine = en.lineSequence().firstOrNull { it.startsWith("execute.datasource.dialectMismatch=") }
        val zhLine = zh.lineSequence().firstOrNull { it.startsWith("execute.datasource.dialectMismatch=") }
        assertTrue("en bundle 缺 execute.datasource.dialectMismatch", enLine != null)
        assertTrue("zh bundle 缺 execute.datasource.dialectMismatch", zhLine != null)
        for (placeholder in listOf("{0}", "{1}")) {
            assertTrue("en 文案缺占位符 $placeholder（方言名/在册数据源清单）", enLine.orEmpty().contains(placeholder))
            assertTrue("zh 文案缺占位符 $placeholder（方言名/在册数据源清单）", zhLine.orEmpty().contains(placeholder))
        }
    }

    @Test
    fun `接线守卫_组Runner展开设可访问且空操作立即报错`() {
        val routing = mainSource("cn/song/mybatisplus/visualizer/execute/ExecDialectRouting.kt")
        assertTrue("isNoOpRunner 扫描锚点缺失", routing.contains("internal fun isNoOpRunner("))
        val subRunnersBody = routing.substringAfter("private fun subRunnersOf(")
            .substringBefore("internal fun isNoOpRunner(")
        assertTrue("subRunnersOf 切片为空（扫描锚点漂移）", subRunnersBody.contains("getMethod(\"getSubRunners\")"))
        assertTrue(
            "getSubRunners 反射未设 isAccessible=true——包私有组项会静默降级为空操作并悬挂 60s",
            Regex("""(?m)^\s*method\.isAccessible = true\s*$""").containsMatchIn(subRunnersBody),
        )
        val results = mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        assertTrue(
            "runRunner 未在调用 run() 前拦截空操作 Runner——组项会白等准备超时",
            Regex("""fun DataSourceSelector\.runRunner[\s\S]{0,600}isNoOpRunner\(runner\)""")
                .containsMatchIn(results),
        )
    }

    @Test
    fun `接线守卫_空操作Runner文案双语在场`() {
        val en = File(repoRoot(), "src/main/resources/messages/MpSqlVisualizerBundle.properties").readText()
        val zh = File(repoRoot(), "src/main/resources/messages/MpSqlVisualizerBundle_zh.properties").readText()
        assertTrue("en bundle 缺 execute.datasource.noRunnable", en.contains("execute.datasource.noRunnable="))
        assertTrue("zh bundle 缺 execute.datasource.noRunnable", zh.contains("execute.datasource.noRunnable="))
    }
}
