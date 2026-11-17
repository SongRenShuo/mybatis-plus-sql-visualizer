package cn.song.mybatisplus.visualizer.mcp

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapNamespaceIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * T-McpWalkFindings S1：sqlv_render_sql「文件+行号」定位语义回归。
 *
 * 真机 20261005 实测（demo UserMapper.xml）：line=语句标签行（gutter/编辑器标注的行号）报
 * SOURCE_NOT_FOUND、line=语句体首行才命中——旧实现取 `getLineStartOffset(line-1)` 行首偏移，
 * 标签行行首是**缩进空白 leaf**（XML PSI 里挂父级 content），findElementAt 沿 parentOf 爬到的
 * 是 mapper 根而非语句 tag。修法=定位锚取**行内首个非空白字符** + 纯空白行顺延（最多 2 行），
 * 全落空退回行首旧行为（少新增失败模式）。
 *
 * fixture 写磁盘 basePath 再刷新 VFS（`addFileToProject` 落 VFS 虚根 `/src/`，不在 basePath
 * 磁盘目录下，[McpFilePathResolver] 按 basePath 拼路径找不到——探针实测 20261005）。
 */
class McpSourceLocatorRenderAtLineTest : BasePlatformTestCase() {

    /** 行号语义同构 demo UserMapper.xml（缩进标签行 + 语句体独立行）。 */
    private val mapper = """<mapper namespace="cn.song.mybatisplusdemo.mapper.UserMapper">
    <select id="selectUserListByXml" resultType="map">
        select id, username, age from user
        <where>
            <if test="status != null">
                and status = #{status}
            </if>
        </where>
        order by id desc
    </select>
</mapper>
"""

    override fun setUp() {
        super.setUp()
        // iBatis 定位链共用 JVM 级静态测试缝：同分片前序用例若无复位会漏到这里，
        // 令 iBatis 调用行拿到空锚（SOURCE_NOT_FOUND）。本类自清，保证顺序无关。
        cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets.lookupOverrideForTest = null
        cn.song.mybatisplus.visualizer.ui.gutter.IbatisJavaCallSites.lookupOverrideForTest = null
        cn.song.mybatisplus.visualizer.log.MapperXmlLocator.lookupOverrideForTest = null
        cn.song.mybatisplus.visualizer.ui.gutter.MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        writeMapper("mapper/UserMapper.xml", mapper)
    }

    private fun writeMapper(relPath: String, content: String) {
        val file = File(project.basePath, relPath)
        file.parentFile.mkdirs()
        file.writeText(content)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    private fun renderAtLine(line: Int?): McpSourceLocator.Outcome =
        McpSourceLocator.renderAtLine(project, "mapper/UserMapper.xml", line)

    fun test标签行命中_修复主场景() {
        val outcome = renderAtLine(2)
        assertNull("标签行（gutter 行号）应命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (selectUserListByXml)", outcome.sourceType)
        assertTrue("渲染产物应含语句 SQL: ${outcome.sql}", outcome.sql.orEmpty().contains("from user"))
    }

    fun test语句体首行命中_旧行为回归() {
        val outcome = renderAtLine(3)
        assertNull("语句体行应命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (selectUserListByXml)", outcome.sourceType)
    }

    fun test语句内部行命中() {
        val outcome = renderAtLine(6)
        assertNull("if 内容行应命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (selectUserListByXml)", outcome.sourceType)
    }

    fun test纯空白行顺延下一行命中() {
        writeMapper(
            "mapper/WithBlankLine.xml",
            """<mapper namespace="n2">
    <select id="afterBlank" resultType="map">

        select 1 from t_blank
    </select>
</mapper>
""",
        )
        val outcome = McpSourceLocator.renderAtLine(project, "mapper/WithBlankLine.xml", 3)
        assertNull("空白行应顺延到语句体行命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (afterBlank)", outcome.sourceType)
    }

    fun test落空行报错文案带操作提示() {
        val outcome = renderAtLine(11)
        val error = outcome.error.orEmpty()
        assertTrue("落空应为 SOURCE_NOT_FOUND: $error", error.startsWith("[SOURCE_NOT_FOUND]"))
        assertTrue(
            "文案应带操作提示: $error",
            error.contains("Pass the line of a statement tag or any line inside it"),
        )
    }

    fun test行号超界_既有行为回归() {
        val outcome = renderAtLine(99)
        val error = outcome.error.orEmpty()
        assertTrue("超界应报 beyond the end: $error", error.contains("beyond the end"))
    }

    // ------------------------------------------------------------------
    // T-IbatisCallPreview：iBatis 两条定位面（sqlMap 语句 / DAO 调用行）
    // ------------------------------------------------------------------

    private val sqlMap = """
        <sqlMap namespace="User">
            <select id="selectById">SELECT ID FROM T_USER WHERE ID = #id#</select>
        </sqlMap>
    """.trimIndent()

    private val ibatisDao = """
        package cn.demo;
        public class UserDaoImpl {
            private Object client;
            public Object selectById(int id) {
                return client.queryForObject("User.selectById", id);
            }
        }
    """.trimIndent()

    private fun render(relPath: String, line: Int?): McpSourceLocator.Outcome =
        McpSourceLocator.renderAtLine(project, relPath, line)

    /**
     * sqlMap 走磁盘 `writeMapper`（与同文件里已通过的 `testiBatis_sqlMap语句行走iBatis渲染` 同款：
     * 平台扫描 basePath 磁盘文件建索引，稳定进入 SqlMapNamespaceIndex）。
     * `addFileToProject` 落在 VFS 虚根 `/src/`、不在 basePath 磁盘目录下，长生命周期 CI JVM 里
     * 自定义索引对它的可见性不稳定 → iBatis 调用行拿到空锚（SOURCE_NOT_FOUND）。
     */
    private fun addSqlMap(): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject("sqlmap/User.xml", sqlMap)
        // 强制目标索引追平（长分片 CI 里索引可能还在异步建）
        FileBasedIndex.getInstance().ensureUpToDate(
            SqlMapNamespaceIndex.NAME, project, GlobalSearchScope.projectScope(project))
        return file
    }

    fun testiBatis_sqlMap语句行走iBatis渲染() {
        // 本用例只走「路径 → PSI → 标签直渲」，不查 FileTypeIndex，写磁盘即可
        writeMapper("sqlmap/User.xml", sqlMap)
        val outcome = render("sqlmap/User.xml", 2)
        assertNull("iBatis sqlMap 语句行应命中: ${outcome.error}", outcome.error)
        assertTrue(
            "sourceType 应以 iBatis sqlMap 起头: ${outcome.sourceType}",
            outcome.sourceType.orEmpty().startsWith("iBatis sqlMap"),
        )
        assertTrue("应渲染出 iBatis SQL: ${outcome.sql}", outcome.sql.orEmpty().contains("FROM T_USER"))
        assertTrue("参数名应来自 #id#: ${outcome.paramTable}", outcome.paramTable.orEmpty().contains("id"))
    }

    fun testiBatis_Java调用行与方法定义行() {
        addSqlMap()
        writeMapper("cn/demo/UserDaoImpl.java", ibatisDao)

        // 调用行（`return client.queryForObject("User.selectById", id);`）
        val callOutcome = render("cn/demo/UserDaoImpl.java", 5)
        // CI 诊断：若仍 SOURCE_NOT_FOUND，把索引候选与 dumb 态打进断言消息，一轮定位真因
        val diagFiles = SqlMapNamespaceIndex.getSqlMapFilesByStatementId(project, "selectById")
        val diagAll = SqlMapNamespaceIndex.getAllSqlMapFiles(project)
        assertNull(
            "iBatis 调用行应命中: ${callOutcome.error}; [DIAG idxStmt=$diagFiles idxAll=$diagAll]",
            callOutcome.error,
        )
        assertEquals("iBatis (User.selectById)", callOutcome.sourceType)
        assertTrue("应渲染出 iBatis SQL: ${callOutcome.sql}", callOutcome.sql.orEmpty().contains("FROM T_USER"))

        // 方法定义行（方法体内恰一条 iBatis 调用 → 方法级回落）
        val methodOutcome = render("cn/demo/UserDaoImpl.java", 4)
        assertNull("iBatis 方法定义行应命中: ${methodOutcome.error}", methodOutcome.error)
        assertEquals("iBatis (User.selectById)", methodOutcome.sourceType)
    }
}
