package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-IbatisFix3 缺陷一：同一 DAO 调用行上 **iBatis 恰 1 枚、JdbcTemplate 恰 0 枚** 的互斥用例。
 *
 * ## 缺陷现场
 *
 * `UserDaoImpl.java` 第 32/37/42/47/52 行（`return client.queryForObject("User.selectById", id);`
 * 等）各出现**两枚** gutter 图标：JdbcTemplate 的 SQL 预览图标 + iBatis 的 Java→XML 跳转箭头。
 * 根因：`JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS` 的 `queryForObject` / `queryForList` /
 * `queryForMap` / `update` / `delete` 与 iBatis `SqlMapExecutor` 的入口名**完全重叠**，且该判定
 * 只查方法名、不查接收者类型——两条通道锚在同一枚方法名 identifier 上，平台并排渲染两枚图标。
 *
 * ## 判据（真实 collector 口径，不是直调 provider 的单个方法）
 *
 * 按 daemon 的两段契约收集：**快速段**逐 leaf 调 `getLineMarkerInfo`（JdbcTemplate 通道走这里），
 * **慢速段**调 `collectSlowLineMarkers`（iBatis 通道走这里）——两段合起来才是「文件上真实出现的
 * 图标集合」。断言按「方法名 identifier 元素」聚合，与用户看到的行内图标位置一致。
 *
 * ## 反向验证
 *
 * 摘掉 `JdbcTemplateSqlGutterLineMarkerProvider` 里的让位门（或把
 * `IbatisCallYieldGate.isIbatisStatementIdCall` 直接 return false）→ [testiBatis调用行_JdbcTemplate让位] 必红
 * （DAO 调用行重新出现 1 枚 JdbcTemplate 图标）。补丁见 `dev-support/reverse-probes/`。
 *
 * ## 对照组（证明「让位」不是把 JDBC 通道整体关掉）
 *
 * 普通 Java 类上的 `queryForList("SELECT … WHERE ID = ?")` / `execute("DELETE FROM T_USER")`
 * 仍走 JdbcTemplate —— SQL 文本含空格/星号/问号，形状上不可能命中 iBatis 的 `id` / `ns.id` 判据。
 */
class JdbcTemplateIbatisMutualExclusionTest : BasePlatformTestCase() {

    private val jdbc = JdbcTemplateSqlGutterLineMarkerProvider()
    private val ibatis = IbatisJavaToXmlLineMarkerProvider()

    override fun setUp() {
        super.setUp()
        IbatisXmlStatementTargets.clearCachesForTest()
        PluginSettings.getInstance().state.showGutterIcon = true
        PluginSettings.getInstance().state.jdbcSqlPreviewEnabled = true
        PluginSettings.getInstance().state.gutterGroupMapperXml = true
        PluginSettings.getInstance().state.gutterGroupJdbc = true
    }

    override fun tearDown() {
        try {
            IbatisXmlStatementTargets.clearCachesForTest()
            PluginSettings.getInstance().state.showGutterIcon = true
            PluginSettings.getInstance().state.jdbcSqlPreviewEnabled = true
            PluginSettings.getInstance().state.gutterGroupMapperXml = true
            PluginSettings.getInstance().state.gutterGroupJdbc = true
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------
    // 夹具（对齐 demo `ibatis2-demo`：语句 id 带 namespace 前缀，DAO 走 SqlMapExecutor 入口名）
    // ------------------------------------------------------------------

    private fun addSqlMap(): PsiFile = myFixture.addFileToProject(
        "sqlmap/User.xml",
        """
        <sqlMap namespace="User">
          <select id="selectById">SELECT ID FROM T_USER WHERE ID = #id#</select>
          <update id="updateUser">UPDATE T_USER SET USER_NAME = #username# WHERE ID = #id#</update>
        </sqlMap>
        """.trimIndent(),
    )

    /** demo 形态：接收者类型**故意不可解析**（`Object`），让位门不得依赖类型解析。 */
    private fun addIbatisDao(): PsiFile = myFixture.addFileToProject(
        "com/demo/UserDaoImpl.java",
        """
        package com.demo;
        public class UserDaoImpl {
            private Object client;
            public Object selectById(int id) { return client.queryForObject("User.selectById", id); }
            public int updateUser(Object u) { return client.update("User.updateUser", u); }
            public Object missing(int id) { return client.queryForObject("User.noSuchStatement", id); }
        }
        """.trimIndent(),
    )

    /** 对照：普通 JDBC 模板仓库（同一批方法名，但首参是 SQL 文本 / 非标识符形态）。 */
    private fun addPlainJdbcRepo(): PsiFile = myFixture.addFileToProject(
        "com/demo/PlainJdbcRepo.java",
        """
        package com.demo;
        public class PlainJdbcRepo {
            private Object jdbcTemplate;
            public Object list() { return jdbcTemplate.queryForList("SELECT ID FROM T_USER WHERE ID = ?"); }
            public Object exec() { return jdbcTemplate.execute("DELETE FROM T_USER WHERE ID = ?"); }
        }
        """.trimIndent(),
    )

    // ------------------------------------------------------------------
    // 采集（daemon 两段契约）
    // ------------------------------------------------------------------

    /** daemon 快速段：逐 leaf 询问（JdbcTemplate 通道的唯一入口）。 */
    private fun fastMarkers(provider: LineMarkerProvider, file: PsiFile): List<LineMarkerInfo<*>> {
        val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }
        return leaves.mapNotNull { provider.getLineMarkerInfo(it) }
    }

    /** daemon 慢速段：整批 leaf 收集（iBatis 通道的唯一入口）。 */
    private fun slowMarkers(provider: LineMarkerProvider, file: PsiFile): List<LineMarkerInfo<*>> {
        val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toMutableList()
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(leaves, result)
        return result
    }

    private fun markersOn(markers: List<LineMarkerInfo<*>>, methodName: String): List<LineMarkerInfo<*>> =
        markers.filter { (it.element as? PsiElement)?.text == methodName }

    // ------------------------------------------------------------------
    // 主判据：同一调用行两通道互斥
    // ------------------------------------------------------------------

    fun testiBatis调用行_JdbcTemplate让位() {
        addSqlMap()
        val dao = addIbatisDao()

        val jdbcMarkers = fastMarkers(jdbc, dao)
        val ibatisMarkers = slowMarkers(ibatis, dao)

        assertEquals(
            "iBatis 调用行必须恰 1 枚跳转箭头（methodName=${ibatisMarkers.map { (it.element as PsiElement).text }}）",
            1,
            markersOn(ibatisMarkers, "queryForObject").size,
        )
        assertEquals(
            "update 同为两条通道的重叠方法名：iBatis 侧恰 1 枚",
            1,
            markersOn(ibatisMarkers, "update").size,
        )
        assertEquals(
            "同一锚点上 JdbcTemplate 通道必须让位（0 枚）：实际 " +
                jdbcMarkers.map { (it.element as PsiElement).text },
            0,
            markersOn(jdbcMarkers, "queryForObject").size,
        )
        assertEquals("update 走 SqlMapExecutor 形态时 JdbcTemplate 同样让位", 0, markersOn(jdbcMarkers, "update").size)
        assertEquals("DAO 文件上 JdbcTemplate 通道整体零图标", 0, jdbcMarkers.size)
        assertEquals(
            "两通道在 queryForObject 行上不相交（iBatis 1 / Jdbc 0 即无叠加）",
            1,
            markersOn(ibatisMarkers, "queryForObject").size + markersOn(jdbcMarkers, "queryForObject").size,
        )
    }

    // ------------------------------------------------------------------
    // 对照：普通 JDBC 仓库不回归（SQL 文本形态照走 JdbcTemplate）
    // ------------------------------------------------------------------

    fun test普通Java类_SQL文本调用仍走JdbcTemplate() {
        addSqlMap()
        val repo = addPlainJdbcRepo()

        val jdbcMarkers = fastMarkers(jdbc, repo)
        val ibatisMarkers = slowMarkers(ibatis, repo)

        assertEquals("queryForList(SQL) 仍出 JdbcTemplate 预览图标", 1, markersOn(jdbcMarkers, "queryForList").size)
        assertEquals("execute(SQL) 仍出 JdbcTemplate 预览图标", 1, markersOn(jdbcMarkers, "execute").size)
        assertEquals(
            "SQL 文本形态不命中 iBatis id 判据（首参非标识符形态）",
            0,
            ibatisMarkers.size,
        )
    }

    /**
     * 目标不存在：iBatis 侧不认领（0 枚，存在性兜底在它那侧）。
     *
     * JdbcTemplate 侧同样让位（0 枚）——这是**已知且刻意接受**的代价：`"User.noSuchStatement"` 是
     * 标识符形态字符串，不是 SQL（点击期 `isLikelySql` 也会拒绝），让它走预览只会得到「未找到」提示。
     * 该断言把取舍钉在用例里，防后续把让位门改成「查存在性」（那要进索引，违反热路径红线）。
     */
    fun test目标不存在_iBatis不认领_JdbcTemplate按形状让位() {
        addSqlMap()
        val dao = addIbatisDao()

        assertEquals("目标语句不存在 → iBatis 零图标", 0, markersOn(slowMarkers(ibatis, dao), "missing").size)
        assertEquals(
            "标识符形态首参按形状让位（不走预览，避免「点开只有未找到」）",
            0,
            markersOn(fastMarkers(jdbc, dao), "missing").size,
        )
    }

    // ------------------------------------------------------------------
    // 判据单元：形状门（O(1) 纯函数）正负例
    // ------------------------------------------------------------------

    fun test让位判据_标识符形态与SQL文本() {
        assertTrue("id 形态", IbatisCallYieldGate.isStatementIdShape("selectById"))
        assertTrue("ns.id 形态", IbatisCallYieldGate.isStatementIdShape("User.selectById"))
        assertTrue("带下划线/美元符/数字", IbatisCallYieldGate.isStatementIdShape("app_user.get_by_$1"))
        assertTrue("SQL 文本不命中", !IbatisCallYieldGate.isStatementIdShape("SELECT ID FROM T_USER WHERE ID = ?"))
        assertTrue("带空格不命中", !IbatisCallYieldGate.isStatementIdShape("User.select ById"))
        assertTrue("含括号不命中", !IbatisCallYieldGate.isStatementIdShape("fn(x)"))
        assertTrue("空串不命中", !IbatisCallYieldGate.isStatementIdShape("   "))
        assertTrue("以数字开头不命中", !IbatisCallYieldGate.isStatementIdShape("1abc"))
    }
}
