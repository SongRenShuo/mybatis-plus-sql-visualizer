package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.execute.ExecDialectRouting
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
 *     且两个方言（DM / GaussDB）同时保留——摘掉任一即红。
 *
 * `GaussDbSqlDialect.ID` 是 `const val`（编译期内联），故裸桶下引用它**不会**触发
 * `SqlLanguageDialectBase` 装载——这是本文件能留在纯逻辑桶的前提，改动时勿把 `const` 去掉。
 */
class GaussDbDialectRegistrationWiringTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    /** duck-typing 桩：只需反射可见的 getUrl 方法（生产对 LocalDataSource 同名反射）。 */
    class StubDs(private val url: String?) {
        fun getUrl(): String? = url
    }

    @Test
    fun `gaussdb候选链包含注册语言id且排在首位`() {
        val candidates = ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GaussDB"].orEmpty()
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
        assertEquals(GaussDbDialect.name, GaussDbSqlDialect.ID)
    }

    @Test
    fun `gaussdbUrl_兜底链全通返回注册id`() {
        // 模拟「注册后」的平台可用清单：Generic/PostgreSQL + 注册的 GaussDB
        val ids = listOf("GenericSQL", "PostgreSQL", GaussDbSqlDialect.ID)
        val ds = StubDs("jdbc:gaussdb://host1:8000,host2:8000/dbx_test?priorityServers=2")
        assertEquals(
            "GaussDB URL（含多主机形态）经兜底链应解析出注册的语言 id",
            GaussDbSqlDialect.ID,
            ExecDialectRouting.urlFallbackId(ds, ids),
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
            ExecDialectRouting.urlFallbackId(ds, idsWithoutRegistration),
        )
    }

    @Test
    fun `wiring_databaseOptionalXml含两个sqlDialect注册`() {
        val xml = File(repoRoot(), "src/main/resources/META-INF/database-optional.xml").readText()
        assertTrue(
            "database-optional.xml 应保留达梦 sql.dialect 注册",
            xml.contains("cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect"),
        )
        assertTrue(
            "database-optional.xml 应含 GaussDB sql.dialect 注册（摘掉即红，反向锚点）",
            xml.contains("cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect"),
        )
        assertEquals(
            "应恰有两个 sql.dialect 注册（DM + GaussDB）；增删都要在本用例留痕",
            2,
            Regex("<sql\\.dialect\\b").findAll(xml).count(),
        )
        // 平台 Dbms 枚举无 GaussDB → 必须与 DM 同款注册 UNKNOWN，保留语言 id 承载身份
        assertTrue(
            "sql.dialect 必须以 dbms=UNKNOWN 注册（平台无 GaussDB 的 Dbms 枚举）",
            xml.contains("<sql.dialect dbms=\"UNKNOWN\" order=\"last\""),
        )
        assertTrue(
            "不得发明 dbms=\"GAUSSDB\" 这类不存在的枚举值",
            !xml.contains("dbms=\"GAUSSDB\"") && !xml.contains("dbms=\"OPENGAUSS\""),
        )
    }
}
