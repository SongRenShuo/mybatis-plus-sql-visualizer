package cn.song.mybatisplus.visualizer.xml.ibatis

import cn.song.mybatisplus.visualizer.ui.IbatisTypeAliasResolver
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PerfSweep31 券01：iBatis `<sqlMapConfig>` `<typeAlias>` 自定义索引（[SqlMapConfigAliasIndex]）
 * 的键提取内核、索引查询与消费方（[IbatisTypeAliasResolver] v2）集成。
 *
 * 三层：
 *  - **纯内核**（`keysForText` / `aliasEntriesFromText` / `isSqlMapConfigFile`）——索引期与运行期同源；
 *  - **索引集成**——`addFileToProject` 真实落盘节奏下 `ta:` / `cfg` 键都能一次查回，
 *    且 `<mapper>` / `<sqlMap>` 文件不产任何键（与两条既有链互斥）；
 *  - **消费方**——`fqnOf` 按别名+模块收窄出 FQN；模块不符/别名缺失回落 null（短名索引兜底）。
 *
 * 反向验证锚（对照 IbatisTypeAliasResolverHotPathTest）：本套用例改走「索引自己就能答」，
 * 一旦有人把消费方改回模块全走查，守卫测试的禁用词表直接红。
 */
class SqlMapConfigAliasIndexTest : BasePlatformTestCase() {

    private fun addXml(path: String, content: String) {
        myFixture.addFileToProject(path, content)
    }

    private fun sqlMapConfig(vararg aliases: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
        append("<!DOCTYPE sqlMapConfig PUBLIC \"-//iBATIS.com//DTD SQL Map Config 2.0//EN\" \"http://ibatis.apache.org/dtd/sql-map-config-2.dtd\">\n")
        append("<sqlMapConfig>\n")
        aliases.forEach { (alias, type) -> append("    <typeAlias alias=\"$alias\" type=\"$type\"/>\n") }
        append("</sqlMapConfig>\n")
    }

    private fun currentModuleName(): String =
        ModuleManager.getInstance(project).modules.first().name

    // ------------------------------------------------------------------
    // 纯内核：键提取
    // ------------------------------------------------------------------

    fun test内核_基础形态产出cfg与别名键() {
        val keys = SqlMapConfigAliasIndex.keysForText(
            sqlMapConfig("User" to "cn.x.model.User", "Order" to "cn.x.model.Order"),
        )
        assertEquals(
            "应产出 cfg + 两条 ta: 键",
            setOf("cfg", "ta:User", "ta:Order"),
            keys,
        )
    }

    fun test内核_属性序与引号形态() {
        val entries = SqlMapConfigAliasIndex.aliasEntriesFromText(
            """
            <sqlMapConfig><settings/>

            <typeAlias type="cn.x.B" alias='B'/>
            <typeAlias alias="C"  type='cn.x.C'/>
            </sqlMapConfig>
            """.trimIndent(),
        )
        assertEquals("属性序任意 + 单双引号都认", mapOf("B" to "cn.x.B", "C" to "cn.x.C"), entries)
    }

    fun test内核_非config根不产键() {
        assertEquals(
            "MyBatis 的 <mapper> 不得产出 iBatis config 键（两链互斥）",
            emptySet<String>(),
            SqlMapConfigAliasIndex.keysForText(
                "<mapper namespace=\"com.demo.U\"><select id=\"a\">x</select></mapper>",
            ),
        )
        assertEquals(
            "<sqlMap> 映射文件（即使含 typeAlias 字样）不产键——config 锚在 sqlMap 锚之后",
            emptySet<String>(),
            SqlMapConfigAliasIndex.keysForText(
                "<sqlMap namespace=\"n\"><select id=\"s\">alias type</select></sqlMap>",
            ),
        )
        assertEquals(
            "sqlMapConfig 内嵌 <sqlMap resource> 子元素不影响判定（config 锚在前）",
            mapOf("User" to "p.User"),
            SqlMapConfigAliasIndex.aliasEntriesFromText(
                "<sqlMapConfig><sqlMap resource=\"User.xml\"/><typeAlias alias=\"User\" type=\"p.User\"/></sqlMapConfig>",
            ),
        )
    }

    fun test内核_alias与type缺一即弃() {
        assertEquals(
            "缺 type 的 typeAlias 不产键（照 IbatisTypeAliasResolver v1 口径）",
            emptyMap<String, String>(),
            SqlMapConfigAliasIndex.aliasEntriesFromText(
                "<sqlMapConfig><typeAlias alias=\"Broken\"/></sqlMapConfig>",
            ),
        )
        assertEquals(
            "cfg 键仍在（文件确是 config）",
            setOf("cfg"),
            SqlMapConfigAliasIndex.keysForText("<sqlMapConfig><typeAlias alias=\"Broken\"/></sqlMapConfig>"),
        )
    }

    fun test内核_重复别名取首个() {
        assertEquals(
            mapOf("User" to "p.First"),
            SqlMapConfigAliasIndex.aliasEntriesFromText(
                "<sqlMapConfig><typeAlias alias=\"User\" type=\"p.First\"/><typeAlias alias=\"User\" type=\"p.Second\"/></sqlMapConfig>",
            ),
        )
    }

    // ------------------------------------------------------------------
    // 索引集成：别名查询与全量清单
    // ------------------------------------------------------------------

    fun test索引_按别名与全量查询() {
        addXml("src/main/resources/sqlmap-config.xml", sqlMapConfig("User" to "p.User"))
        // 干扰文件：两条既有链的文件形态都不得产出 config 键
        addXml(
            "src/main/resources/mapper/UserMapper.xml",
            "<mapper namespace=\"com.demo.U\"><select id=\"a\">x</select></mapper>",
        )
        addXml(
            "src/main/resources/sqlmap/User.xml",
            "<sqlMap namespace=\"U\"><select id=\"selectAll\">SELECT 1</select></sqlMap>",
        )

        assertEquals(
            "按别名查询只回声明它的 config 文件",
            listOf("sqlmap-config.xml"),
            SqlMapConfigAliasIndex.getConfigFilesByAlias(project, "User").map { it.name },
        )
        assertEquals(
            "未声明的别名查询为空",
            emptyList<String>(),
            SqlMapConfigAliasIndex.getConfigFilesByAlias(project, "Missing").map { it.name },
        )
        assertEquals(
            "全量查询只含 config 文件",
            listOf("sqlmap-config.xml"),
            SqlMapConfigAliasIndex.getAllConfigFiles(project).map { it.name },
        )
        assertEquals(
            "空别名不得触发索引查询",
            emptyList<String>(),
            SqlMapConfigAliasIndex.getConfigFilesByAlias(project, " ").map { it.name },
        )
    }

    fun test索引_config修改后键随内容更新() {
        addXml("src/main/resources/sqlmap-config.xml", sqlMapConfig("User" to "p.User"))
        assertEquals(listOf("sqlmap-config.xml"), SqlMapConfigAliasIndex.getConfigFilesByAlias(project, "User").map { it.name })
        assertEquals(
            "改动前别名 Old 不在键面",
            emptyList<String>(),
            SqlMapConfigAliasIndex.getConfigFilesByAlias(project, "Old").map { it.name },
        )
        myFixture.addFileToProject(
            "src/main/resources/sqlmap-config.xml",
            sqlMapConfig("User" to "p.User", "Old" to "p.Old"),
        )
        // dependsOnFileContent=true：内容变更即增量重索引（失效全交平台）
        assertEquals(
            "config 保存后新增别名立即可查（v1 会话级缓存限制已退役）",
            listOf("sqlmap-config.xml"),
            SqlMapConfigAliasIndex.getConfigFilesByAlias(project, "Old").map { it.name },
        )
    }

    // ------------------------------------------------------------------
    // 消费方：fqnOf（索引 + 模块收窄 + PSI 精确匹配）
    // ------------------------------------------------------------------

    fun test消费方_fqnOf按别名与模块收窄出FQN() {
        addXml("src/main/resources/sqlmap-config.xml", sqlMapConfig("User" to "p.User", "Broken" to ""))
        addXml(
            "src/main/resources/other/sqlmap-config.xml",
            sqlMapConfig("User" to "q.OtherModuleUser"),
        )
        val moduleName = currentModuleName()
        assertEquals(
            "别名直接给出 FQN（索引多收面由 PSI alias 精确匹配闸住）",
            "p.User",
            IbatisTypeAliasResolver.fqnOf(project, moduleName, "User"),
        )
        assertEquals(
            "模块名不符 → null（与 v1 只扫本模块语义一致）",
            null,
            IbatisTypeAliasResolver.fqnOf(project, "nonexistent.module", "User"),
        )
        assertEquals("空参守卫", null, IbatisTypeAliasResolver.fqnOf(project, moduleName, " "))
    }

    fun test消费方_fqnOf的模块收窄按文件归属模块过滤() {
        // 同一别名在两个 config 各声明不同 type；只有落在语句所在模块的文件参与
        val moduleName = currentModuleName()
        addXml("src/main/resources/sqlmap-config.xml", sqlMapConfig("User" to "p.User"))
        val vf = SqlMapConfigAliasIndex.getAllConfigFiles(project).single()
        assertEquals(
            "夹具自证：config 文件归属测试模块",
            moduleName,
            ModuleUtilCore.findModuleForFile(vf, project)?.name,
        )
        assertEquals("p.User", IbatisTypeAliasResolver.fqnOf(project, moduleName, "User"))
    }
}
