package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-GaussDbDialect 券 06 判别用例——**全纯逻辑**（裸 test 桶可跑）。
 *
 * 与 [DmDialectRegistrationWiringTest] 同构：平台形态断言（EP 实例类型 / 可用清单含 GaussDB /
 * 设置页可见）不在本文件——裸桶运行期无 DatabaseTools，实现类直引 SqlLanguageDialectBase
 * 无法装载；那部分由真机沙盒（runIde）承接。本文件盯四件可纯测的承重事：
 *
 *  1. **候选链一致性**：`ExecDialectRouting` 的 GaussDB 候选链必须包含注册语言 id
 *     `GaussDbSqlDialect.ID`——两侧任何一侧改名即红。这一环断了的表现是「执行目标 Runner
 *     枚举落空并回退 MySQL」，用户在界面上看不出原因；
 *  2. **候选链顺序**：注册 id 必须在**首位**。放在 `PostgreSQL` 之后等于白注册——兜底方言会先命中；
 *  3. **兜底链全通**：GaussDB URL + 可用清单含 GaussDB → `urlFallbackId` 返回该 id；
 *  4. **接线守卫**（源码扫描）：`database-optional.xml` 的 `sql.dialect` 注册与实现类在场，
 *     且四个方言（DM / GaussDB / HighGo / Vastbase，后两族 T-XcDialects3 券 08 增）同时保留
 *     ——摘掉任一即红。
 *
 * `GaussDbSqlDialect.ID` 在 k2j 是 Java `static final String` 常量（编译期内联），故裸桶下引用它
 * **不会**触发 `SqlLanguageDialectBase` 装载——这是本文件能留在纯逻辑桶的前提，改动时勿把常量
 * 改成运行期求值。
 *
 * k2j 移植注（生产为 Java）：仓库根定位用 `src/main/java`；方言单例 `XxxDialect.INSTANCE`。
 */
class GaussDbDialectRegistrationWiringTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    /** duck-typing 桩：只需反射可见的 getUrl 方法（生产对 LocalDataSource 同名反射）。 */
    class StubDs(private val url: String?) {
        fun getUrl(): String? = url
    }

    // ---- 包私有静态成员反射读取（k2j：ExecDialectRouting 两成员包私有，本类在 sql 包，
    //      与 DmDialectRegistrationWiringTest 同款反射桥） ----

    @Suppress("UNCHECKED_CAST")
    private fun pluginDialectIds(): Map<String, List<String>> {
        val field = Class.forName("cn.song.mybatisplus.visualizer.execute.ExecDialectRouting")
            .getDeclaredField("PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS")
        field.isAccessible = true
        return field.get(null) as Map<String, List<String>>
    }

    private fun urlFallbackId(ds: Any?, ids: List<String>): String? {
        val method = Class.forName("cn.song.mybatisplus.visualizer.execute.ExecDialectRouting")
            .getDeclaredMethod("urlFallbackId", Any::class.java, List::class.java)
        method.isAccessible = true
        return method.invoke(null, ds, ids) as String?
    }

    @Test
    fun `gaussdb候选链包含注册语言id且排在首位`() {
        val candidates = pluginDialectIds()["GaussDB"].orEmpty()
        assertTrue(
            "GaussDB 候选链 $candidates 应包含注册语言 id '${GaussDbSqlDialect.ID}'" +
                "（否则 urlFallbackId 落 PostgreSQL 兜底，平台侧注册白做）",
            candidates.any { it.equals(GaussDbSqlDialect.ID, ignoreCase = true) },
        )
        assertEquals(
            "注册语言 id 必须排在候选链首位——排在 PostgreSQL 之后等于白注册（兜底会先命中）",
            GaussDbSqlDialect.ID,
            candidates.first(),
        )
        // 注册名与插件方言名必须逐字一致（IdeaSqlContextBridge.pluginDialectName 是精确匹配）
        assertEquals(GaussDbDialect.INSTANCE.name, GaussDbSqlDialect.ID)
    }

    @Test
    fun `gaussdbUrl_兜底链全通返回注册id`() {
        // 模拟「注册后」的平台可用清单：Generic/PostgreSQL + 注册的 GaussDB
        val ids = listOf("GenericSQL", "PostgreSQL", GaussDbSqlDialect.ID)
        val ds = StubDs("jdbc:gaussdb://host1:8000,host2:8000/dbx_test?priorityServers=2")
        assertEquals(
            "GaussDB URL（含多主机形态）经兜底链应解析出注册的语言 id",
            GaussDbSqlDialect.ID,
            urlFallbackId(ds, ids),
        )
    }

    @Test
    fun `未注册时兜底到PostgreSQL而不是猜别的`() {
        // 反向锚点：平台侧注册不在场时，候选链只能落 PostgreSQL 兜底——本断言把「注册的价值」写死
        val idsWithoutRegistration = listOf("GenericSQL", "PostgreSQL")
        val ds = StubDs("jdbc:gaussdb://host:8000/dbx_test")
        assertEquals(
            "未注册时应如实落到 PostgreSQL 兜底",
            "PostgreSQL",
            urlFallbackId(ds, idsWithoutRegistration),
        )
    }

    @Test
    fun `wiring_databaseOptionalXml含四个sqlDialect注册`() {
        val xml = File(repoRoot(), "src/main/resources/META-INF/database-optional.xml").readText()
        assertTrue(
            "database-optional.xml 应保留达梦 sql.dialect 注册",
            xml.contains("cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect"),
        )
        assertTrue(
            "database-optional.xml 应含 GaussDB sql.dialect 注册（摘掉即红，反向锚点）",
            xml.contains("cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect"),
        )
        assertTrue(
            "database-optional.xml 应含 HighGo sql.dialect 注册（T-XcDialects3 券 08）",
            xml.contains("cn.song.mybatisplus.visualizer.sql.highgo.HighGoSqlDialect"),
        )
        assertTrue(
            "database-optional.xml 应含 Vastbase sql.dialect 注册（T-XcDialects3 券 08）",
            xml.contains("cn.song.mybatisplus.visualizer.sql.vastbase.VastbaseSqlDialect"),
        )
        assertEquals(
            "应恰有四个 sql.dialect 注册（DM + GaussDB + HighGo + Vastbase）；增删都要在本用例留痕",
            4,
            Regex("<sql\\.dialect\\b").findAll(xml).count(),
        )
        // 平台 Dbms 枚举无 GaussDB/HighGo/Vastbase → 必须与 DM 同款注册 UNKNOWN，保留语言 id 承载身份
        assertTrue(
            "sql.dialect 必须以 dbms=UNKNOWN 注册（平台无这几族的 Dbms 枚举）",
            xml.contains("<sql.dialect dbms=\"UNKNOWN\" order=\"last\""),
        )
        assertTrue(
            "不得发明 dbms=\"GAUSSDB\" 这类不存在的枚举值",
            !xml.contains("dbms=\"GAUSSDB\"") && !xml.contains("dbms=\"OPENGAUSS\"") &&
                !xml.contains("dbms=\"HIGHGO\"") && !xml.contains("dbms=\"VASTBASE\""),
        )
    }

    @Test
    fun `xc3候选链包含注册语言id且排在首位`() {
        // T-XcDialects3 券 08：HighGo / Vastbase 与 GaussDB 同款接线三处一致性
        listOf("HighGo" to "HighGo", "Vastbase" to "Vastbase").forEach { (pluginName, _) ->
            val candidates = pluginDialectIds()[pluginName].orEmpty()
            assertTrue(
                "$pluginName 候选链 $candidates 应包含自身注册语言 id",
                candidates.any { it.equals(pluginName, ignoreCase = true) },
            )
            assertEquals(
                "$pluginName 注册语言 id 必须排在候选链首位（排在 PostgreSQL 之后等于白注册）",
                pluginName,
                candidates.first(),
            )
            // 注册名与插件方言名必须逐字一致（IdeaSqlContextBridge.pluginDialectName 是精确匹配）
            assertEquals(pluginName, DialectRegistry.INSTANCE.byName(pluginName).name)
        }
        // ID 常量与候选链字面量逐字一致（HighGoSqlDialect/VastbaseSqlDialect 的 ID 是 static final
        // String 常量，裸桶引用不触发 SqlLanguageDialectBase 装载，与 GaussDbSqlDialect.ID 同款前提）
        assertEquals("HighGo", cn.song.mybatisplus.visualizer.sql.highgo.HighGoSqlDialect.ID)
        assertEquals("Vastbase", cn.song.mybatisplus.visualizer.sql.vastbase.VastbaseSqlDialect.ID)
    }

    @Test
    fun `xc3Url_兜底链全通返回注册id`() {
        val ids = listOf("GenericSQL", "PostgreSQL", "HighGo", "Vastbase")
        assertEquals(
            "HighGo URL 经兜底链应解析出注册的语言 id",
            "HighGo",
            urlFallbackId(StubDs("jdbc:highgo://host:5866/xc3_test"), ids),
        )
        assertEquals(
            "Vastbase URL 经兜底链应解析出注册的语言 id",
            "Vastbase",
            urlFallbackId(StubDs("jdbc:vastbase://host:5432/xc3_test"), ids),
        )
        // 反向锚点：未注册时如实落 PostgreSQL 兜底
        val idsWithout = listOf("GenericSQL", "PostgreSQL")
        assertEquals(
            "PostgreSQL",
            urlFallbackId(StubDs("jdbc:highgo://host:5866/xc3_test"), idsWithout),
        )
    }
}
