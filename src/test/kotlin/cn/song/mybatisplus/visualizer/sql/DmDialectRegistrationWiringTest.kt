package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.execute.ExecDialectRouting
import cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-DmDialect（O-136 真机发现，20261014）判别用例——**全纯逻辑**（裸 test 桶可跑）。
 *
 * 平台形态断言（EP 实例类型 / 可用清单含 DM / 设置页可见）不放本文件：裸桶运行期无
 * DatabaseTools，实现类直引 SqlLanguageDialectBase 无法装载（20261014 实测
 * NoClassDefFoundError）——那部分由真机沙盒验证（262 Sandbox DatabaseTools 在场）承接，
 * 本文件盯三件可纯测的承重事：
 *  1. **候选链一致性**：UrlSubprotocolDialects 的达梦候选 [DM, Dameng] 必须包含注册语言 id
 *     `DM`——两侧任何一侧改名即红（这正是 262 真机发现里断掉的那一环）；
 *  2. **兜底链全通**：DM URL + 可用清单含 DM → urlFallbackId 返回 `DM`；
 *  3. **接线守卫**（源码扫描）：database-optional.xml 的 `sql.dialect` 注册与实现类在场——
 *     摘掉注册即红（反向锚点）。
 */
class DmDialectRegistrationWiringTest {

    /** duck-typing 桩：只需反射可见的 getUrl 方法（生产对 LocalDataSource 同名反射）。
     *  非 private——私有类会让反射 invoke 抛 IllegalAccessException 被 runCatching 吞成 null（20261014 实测）。 */
    class StubDs(private val url: String?) {
        fun getUrl(): String? = url
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    @Test
    fun testCandidate_dm候选链包含注册语言id() {
        val candidates = ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["DM（达梦）"].orEmpty()
        assertTrue(
            "达梦候选 $candidates 应包含注册语言 id '${DmSqlDialect.ID}'（否则 urlFallbackId 落 null 回 MySQL）",
            candidates.any { it.equals(DmSqlDialect.ID, ignoreCase = true) },
        )
    }

    @Test
    fun testCandidate_dmUrl_兜底链全通返回dm() {
        // 模拟「注册后」的平台可用清单：Generic/MySQL + 注册的 DM
        val ids = listOf("GenericSQL", "MySQL", DmSqlDialect.ID)
        val ds = StubDs("jdbc:dm://db.example:15236?schema=MPJ_DEMO&user=demo&password=demo")
        assertEquals(
            "DM URL 经兜底链应解析出注册的 DM 语言 id",
            DmSqlDialect.ID,
            ExecDialectRouting.urlFallbackId(ds, ids),
        )
    }

    @Test
    fun testWiring_databaseOptionalXml含sqlDialect注册() {
        val xml = File(repoRoot(), "src/main/resources/META-INF/database-optional.xml").readText()
        assertTrue(
            "database-optional.xml 应含达梦 sql.dialect 注册（摘掉即红，反向锚点）",
            xml.contains("<sql.dialect dbms=\"UNKNOWN\" order=\"last\"") &&
                !xml.contains("<sql.dialect dbms=\"DM\""),
        )
        assertTrue(
            "注册的 implementationClass 应指向 DmSqlDialect",
            xml.contains("cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect"),
        )
    }
}
