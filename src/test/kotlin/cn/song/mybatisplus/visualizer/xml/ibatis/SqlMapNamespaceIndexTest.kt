package cn.song.mybatisplus.visualizer.xml.ibatis

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-IbatisGutterPerf P0：iBatis `<sqlMap>` 自定义索引（[SqlMapNamespaceIndex]）的键提取内核与索引查询。
 *
 * 两层：
 *  - **纯内核**（`*FromText` / `keysForText`）——索引期与运行期同源，不依赖平台索引，可纯单测；
 *  - **索引集成**——`addFileToProject` 真实落盘节奏下，三类键（`all` / `ns:` / `stmt:`）都能
 *    一次查回，且 `<mapper>` / `<sqlMapConfig>` 文件不产任何键（与 MyBatis 侧互斥）。
 *
 * 索引集成的价值在「查询面」：旧链靠全项目 PSI 解析筛文件，这里钉住「索引自己就能答」，
 * 一旦有人把键设计改回「只索引 namespace」，裸 id 用例立刻红。
 */
class SqlMapNamespaceIndexTest : BasePlatformTestCase() {

    private fun addXml(path: String, content: String) {
        myFixture.addFileToProject(path, content)
    }

    private fun sqlMap(namespace: String?, vararg statements: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
        append("<!DOCTYPE sqlMap PUBLIC \"-//iBATIS.com//DTD SQL Map 2.0//EN\" \"http://ibatis.apache.org/dtd/sql-map-2.dtd\">\n")
        if (namespace == null) append("<sqlMap>\n") else append("<sqlMap namespace=\"$namespace\">\n")
        statements.forEach { append("    $it\n") }
        append("</sqlMap>\n")
    }

    // ------------------------------------------------------------------
    // 纯内核：键提取
    // ------------------------------------------------------------------

    fun test内核_基础形态产出三类键() {
        val keys = SqlMapNamespaceIndex.keysForText(
            sqlMap(
                "User",
                "<select id=\"selectAll\">SELECT * FROM t_user</select>",
                "<insert id=\"insertUser\">INSERT INTO t_user VALUES(1)</insert>",
            ),
        )
        assertEquals(
            "应产出 all + ns:User + 两条语句键",
            setOf("all", "ns:User", "stmt:selectAll", "stmt:insertUser"),
            keys,
        )
    }

    fun test内核_属性序与引号形态() {
        // namespace 不在首属性位 + 单引号
        assertEquals(
            "com.demo.B",
            SqlMapNamespaceIndex.namespaceFromText("<sqlMap parameterClass=\"x\" namespace='com.demo.B'>"),
        )
        // 跨行属性
        assertEquals(
            "com.demo.C",
            SqlMapNamespaceIndex.namespaceFromText("<sqlMap\n    namespace=\"com.demo.C\"\n>"),
        )
        // 语句 id 在属性序中段 / 单引号
        assertEquals(
            setOf("s1", "s2"),
            SqlMapNamespaceIndex.statementIdsFromText(
                "<sqlMap namespace=\"n\"><select resultClass=\"m.User\" id='s1'>x</select>" +
                    "<update parameterClass=\"p\" id=\"s2\">y</update></sqlMap>",
            ),
        )
    }

    fun test内核_非sqlMap根不产键() {
        assertEquals(
            "MyBatis 的 <mapper> 不得产出 iBatis 键（两链互斥）",
            emptySet<String>(),
            SqlMapNamespaceIndex.keysForText("<mapper namespace=\"com.demo.UserMapper\"><select id=\"a\">x</select></mapper>"),
        )
        assertEquals(
            "<sqlMapConfig> 不是映射文件（后随字符不是空白/>//，锚不命中）",
            emptySet<String>(),
            SqlMapNamespaceIndex.keysForText("<sqlMapConfig><sqlMap resource=\"x.xml\"/></sqlMapConfig>"),
        )
        assertEquals(
            "普通 XML 不产键",
            emptySet<String>(),
            SqlMapNamespaceIndex.keysForText("<configuration><select id=\"a\">x</select></configuration>"),
        )
    }

    fun test内核_无namespace的sqlMap仍产出all与语句键() {
        assertEquals(
            setOf("all", "stmt:selectBare"),
            SqlMapNamespaceIndex.keysForText(sqlMap(null, "<select id=\"selectBare\">SELECT 1</select>")),
        )
    }

    fun test内核_selectKey与语句体内的id字样不产额外键() {
        val keys = SqlMapNamespaceIndex.keysForText(
            sqlMap(
                "User",
                "<insert id=\"insertUser\">INSERT INTO t(id) VALUES(#id#)<selectKey keyProperty=\"id\">SELECT 1</selectKey></insert>",
            ),
        )
        assertEquals(
            "只认 <selectKey> 之外的 6 类语句标签；标签体里的 id 字样不产键",
            setOf("all", "ns:User", "stmt:insertUser"),
            keys,
        )
    }

    // ------------------------------------------------------------------
    // 索引集成：三类查询各自能答
    // ------------------------------------------------------------------

    fun test索引_按namespace与按语句id与全量查询() {
        addXml(
            "src/main/resources/sqlmap/User.xml",
            sqlMap("User", "<select id=\"selectAll\">SELECT 1</select>"),
        )
        addXml(
            "src/main/resources/sqlmap/Order.xml",
            sqlMap("Order", "<select id=\"selectAll\">SELECT 2</select>"),
        )
        // MyBatis 侧文件：不得出现在任何 iBatis 查询结果里
        addXml(
            "src/main/resources/mapper/UserMapper.xml",
            "<mapper namespace=\"com.demo.User\"><select id=\"selectAll\">SELECT 3</select></mapper>",
        )

        assertEquals(
            "按 namespace 查询只回该文件的 sqlMap",
            listOf("User.xml"),
            SqlMapNamespaceIndex.getSqlMapFilesByNamespace(project, "User").map { it.name },
        )

        assertEquals(
            "非空查询：两条 sqlMap 声明了同名语句 id，两个文件都应回（调用方按 preferredNamespace 排序）",
            listOf("Order.xml", "User.xml"),
            SqlMapNamespaceIndex.getSqlMapFilesByStatementId(project, "selectAll").map { it.name }.sorted(),
        )

        assertEquals(
            "全量查询只含 sqlMap 文件，MyBatis 的 <mapper> 文件不参与",
            listOf("Order.xml", "User.xml"),
            SqlMapNamespaceIndex.getAllSqlMapFiles(project).map { it.name }.sorted(),
        )

        assertEquals(
            "空 namespace / 空 id 不得触发索引查询",
            emptyList<String>(),
            SqlMapNamespaceIndex.getSqlMapFilesByNamespace(project, " ").map { it.name },
        )
    }

    fun test索引_无namespace的sqlMap可按语句id查到() {
        addXml("src/main/resources/sqlmap/Bare.xml", sqlMap(null, "<select id=\"selectBare\">SELECT 1</select>"))
        assertEquals(
            "裸 id（useStatementNamespaces=false 的默认形态）必须能按语句键查到",
            listOf("Bare.xml"),
            SqlMapNamespaceIndex.getSqlMapFilesByStatementId(project, "selectBare").map { it.name },
        )
        assertEquals(
            "无 namespace 的文件仍属于全量清单",
            listOf("Bare.xml"),
            SqlMapNamespaceIndex.getAllSqlMapFiles(project).map { it.name },
        )
    }
}