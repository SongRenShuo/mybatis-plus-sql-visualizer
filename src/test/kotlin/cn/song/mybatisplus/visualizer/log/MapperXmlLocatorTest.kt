package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * [MapperXmlLocator] 平台集成测试（T-M55-01 跳转双目标）：
 *
 * 在 LightPlatform 虚拟项目中创建真实 mapper XML（走 VirtualFile + PsiManager 全链路，
 * 仿 HbmXmlEntityResolverPlatformTest 先例），验证：
 *  - 命中：namespace + 语句 id 匹配 → 定位到语句标签起始行（0-based）；
 *  - namespace 不匹配 → null（PSI 校验防线）；
 *  - 无 XML / 语句 id 不存在 → null（回退 Java 跳转，不劣化）；
 *  - 多模块同名 mapper（同 namespace 同 id）→ 取第一个，不抛不阻；
 *  - statementId 形态守卫：无点 / 空串 → null。
 */
class MapperXmlLocatorTest : BasePlatformTestCase() {

    /** F1''：静态槽/静态旗标跨用例污染防线——每用例前后强制清零（单测 sync 语义不变）。 */
    override fun setUp() {
        super.setUp()
        MapperXmlLocator.clearFileListCacheForTest()
    }

    override fun tearDown() {
        try {
            MapperXmlLocator.clearFileListCacheForTest()
        } finally {
            super.tearDown()
        }
    }

    private val mapperXml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="map">SELECT * FROM t_user WHERE id = #{id}</select>
    <insert id="insertUser">INSERT INTO t_user(name) VALUES(#{name})</insert>
</mapper>
"""

    private fun addMapperXml(path: String, content: String) {
        myFixture.addFileToProject(path, content)
    }

    /** 期望行号：语句 id 首次出现所在行（0-based），与 fixture 文本逐行核对。 */
    private fun expectedLineOf(content: String, marker: String): Int =
        content.lineSequence().indexOfFirst { it.contains(marker) }

    fun test命中_定位到语句标签行() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("namespace+id 匹配应命中", loc)
        assertEquals("UserMapper.xml", loc!!.virtualFile.name)
        assertEquals(
            "行号 = 语句标签起始行（0-based）",
            expectedLineOf(mapperXml, "selectById"),
            loc.line,
        )
    }

    fun test命中_insert语句() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.insertUser")
        assertNotNull(loc)
        assertEquals(expectedLineOf(mapperXml, "insertUser"), loc!!.line)
    }

    fun testNamespace不匹配_返回null() {
        addMapperXml(
            "src/main/resources/mapper/OtherMapper.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.demo.mapper.OrderMapper">
                <select id="selectById">SELECT 1</select>
            </mapper>
            """.trimIndent(),
        )
        // 同 id 但 namespace 不同：不得跨 namespace 误命中
        assertNull("namespace 不匹配不应命中", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test语句id不存在_返回null() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        assertNull(
            "同 namespace 但 id 不存在应返回 null",
            MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.deleteById"),
        )
    }

    fun test无XML_返回null() {
        assertNull("项目内无 mapper XML 应返回 null", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test非Mapper根元素的XML不参与() {
        addMapperXml(
            "src/main/resources/misc/notes.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <select id="selectById">SELECT 1</select>
            </configuration>
            """.trimIndent(),
        )
        assertNull("根元素非 <mapper> 不应命中", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test多模块同名_取第一个不抛() {
        addMapperXml("module-a/src/main/resources/mapper/UserMapper.xml", mapperXml)
        addMapperXml("module-b/src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("多模块同名应取第一个命中", loc)
        assertEquals("UserMapper.xml", loc!!.virtualFile.name)
    }

    fun testStatementId形态守卫() {
        assertNull("无点号（非 namespace.id 形态）应返回 null", MapperXmlLocator.splitStatementId("selectById"))
        assertNull("空串应返回 null", MapperXmlLocator.splitStatementId(""))
        assertNull("以点结尾应返回 null", MapperXmlLocator.splitStatementId("com.demo.UserMapper."))
        assertNull("以点开头应返回 null", MapperXmlLocator.splitStatementId(".selectById"))
        assertEquals(
            "namespace.id 按最后一个点拆分",
            "com.demo.mapper.UserMapper" to "selectById",
            MapperXmlLocator.splitStatementId("com.demo.mapper.UserMapper.selectById"),
        )
    }

    // ---------- T-PS5-05：文件列表缓存 ----------

    fun testT_P5_05_命中路径零索引遍历() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        MapperXmlLocator.clearFileListCacheForTest()
        val before = MapperXmlLocator.indexScanCount.get()
        val first = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull(first)
        assertEquals("首跳全扫一次", 1, MapperXmlLocator.indexScanCount.get() - before)
        repeat(3) { MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById") }
        assertEquals("TTL 窗口内二次跳转零索引遍历", 1, MapperXmlLocator.indexScanCount.get() - before)
        // 结果与首跳一致（缓存不改语义）
        val again = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertEquals(first!!.virtualFile.path, again!!.virtualFile.path)
        assertEquals(first.line, again.line)
    }

    fun testT_P5_05_TTL过期后重扫且结果一致() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        val realClock = MapperXmlLocator.clockMs
        var now = 1_000_000L
        try {
            MapperXmlLocator.clearFileListCacheForTest()
            MapperXmlLocator.clockMs = { now }
            MapperXmlLocator.fileListTtlMs = 5_000L
            val before = MapperXmlLocator.indexScanCount.get()
            val a = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
            now += 5_001L // 越 TTL
            val b = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
            assertEquals("TTL 过期 → 重扫一次", 2, MapperXmlLocator.indexScanCount.get() - before)
            assertEquals("重扫结果与首跳一致", a!!.line, b!!.line)
            assertEquals(a.virtualFile.path, b.virtualFile.path)
        } finally {
            MapperXmlLocator.clockMs = realClock
            MapperXmlLocator.fileListTtlMs = 5_000L
            MapperXmlLocator.clearFileListCacheForTest()
        }
    }

    fun testT_P5_05_缓存文件失效即重扫且不返回失效文件() {
        val psi = myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", mapperXml)
        MapperXmlLocator.clearFileListCacheForTest()
        assertNotNull("删除前命中", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
        val before = MapperXmlLocator.indexScanCount.get()
        com.intellij.openapi.application.WriteAction.compute<Unit, RuntimeException> {
            psi.virtualFile.delete(this)
        }
        assertNull(
            "缓存文件被删除 → isValid 校验失败 → 重扫后无命中（不得返回失效文件）",
            MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"),
        )
        assertEquals("失效文件触发重扫一次", before + 1, MapperXmlLocator.indexScanCount.get())
        MapperXmlLocator.clearFileListCacheForTest()
    }

    // ------------------------------------------------------------------
    // F1''（T-GutterFirstOpenDelay 续刀）：清单构建移出慢趟关键路径——
    // 真机实证（demo 根工程 scope≈4841 XML）：慢趟 ReadAction 里逐个 PSI 解析全项目 XML，
    // JPS 写盘反复 PCE → 慢趟饿死 → Java→XML 图标长期缺席。异步语义=无槽只踢后台构建
    // （不写 MISS）、落地一次性 restart、过期槽 serve stale。
    // ------------------------------------------------------------------

    fun testF1双撇_异步模式无槽时立即返回null不写MISS且落地后restart重跑() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        var restarts = 0
        MapperXmlLocator.restartAnalyzerForTest = { restarts++ }
        MapperXmlLocator.asyncListBuildForTest = true
        try {
            MapperXmlLocator.clearFileListCacheForTest()
            val scansBefore = MapperXmlLocator.indexScanCount.get()
            // 无槽：异步模式立即返回 null（不阻塞慢趟），并踢构建（测试缝同步落地）
            assertNull("无可用槽时须立即返回 null（等后台清单）", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
            assertEquals("落地重启恰好一次（missedWhilePending）", 1, restarts)
            // 落地后：槽可用，第二次 locate 正常命中——MISS 未被首趟污染（真查到了）
            val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
            assertNotNull("清单落地后 locate 必须命中", loc)
            assertEquals("UserMapper.xml", loc!!.virtualFile.name)
            assertEquals("清单构建恰好一次（首趟未写 MISS，未引发反复重扫）",
                scansBefore + 1, MapperXmlLocator.indexScanCount.get())
        } finally {
            MapperXmlLocator.asyncListBuildForTest = null
            MapperXmlLocator.restartAnalyzerForTest = null
            MapperXmlLocator.clearFileListCacheForTest()
        }
    }

    fun testF1双撇_头扫盲区按文件名兜底PSI_ORM覆盖不收窄() {
        // 根标签前有超长注释（>16KB 头扫窗）的 MBG 生成文件：头扫看不见 <mapper，
        // 文件名含 Mapper → PSI 兜底路径必须把它捞回来（旧全量 PSI 口径的能力面）。
        val bigComment = "<!-- " + "x".repeat(20_000) + " -->\n"
        val xml = "<?xml version=\"1.0\"?>\n" + bigComment +
            "<mapper namespace=\"com.demo.mapper.BigMapper\">\n" +
            "    <select id=\"selectById\">SELECT 1</select>\n</mapper>\n"
        addMapperXml("src/main/resources/mapper/BigMapper.xml", xml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.BigMapper.selectById")
        assertNotNull("头扫盲区文件必须由文件名兜底 PSI 捞回（ORM 覆盖面不收窄）", loc)
    }

    fun testF1双撇_iBatisSqlMap边界与旧口径一致_不入清单() {
        // iBatis 2.x <sqlMap> 根：旧 PSI 筛选（rootTag=="mapper"）本就不收入本定位器的清单
        //（iBatis 跳转走 IbatisXmlLocators 独立链）——头扫+兜底口径必须同样排除，不悄悄扩面。
        val sqlMapXml = "<?xml version=\"1.0\"?>\n" +
            "<sqlMap namespace=\"com.demo.mapper.UserDao\">\n" +
            "    <select id=\"selectById\">SELECT 1</select>\n</sqlMap>\n"
        addMapperXml("src/main/resources/sqlmap/UserDao.xml", sqlMapXml)
        assertNull("sqlMap 文件不入 mapper 定位清单（与旧口径一致）",
            MapperXmlLocator.locate(project, "com.demo.mapper.UserDao.selectById"))
    }

    fun testF1双撇_异步模式过期槽服务旧值不缺席() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        MapperXmlLocator.restartAnalyzerForTest = { }
        MapperXmlLocator.asyncListBuildForTest = true
        MapperXmlLocator.fileListTtlMs = 5_000L
        try {
            MapperXmlLocator.clearFileListCacheForTest()
            MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById") // 首查：踢构建+落地
            val scansAfterFirst = MapperXmlLocator.indexScanCount.get()
            val clockBefore = MapperXmlLocator.clockMs
            MapperXmlLocator.clockMs = { clockBefore() + 10_000L } // 越过 TTL（5s）
            try {
                // TTL 过期：serve stale——结果不缺席，且后台刷新被踢（缝内同步落地，重扫恰一次）
                val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
                assertNotNull("过期槽须服务旧值（图标不因刷新缺席）", loc)
                assertEquals("刷新恰好踢一次（stale-while-revalidate）",
                    scansAfterFirst + 1, MapperXmlLocator.indexScanCount.get())
            } finally {
                MapperXmlLocator.clockMs = clockBefore
            }
        } finally {
            MapperXmlLocator.asyncListBuildForTest = null
            MapperXmlLocator.restartAnalyzerForTest = null
            MapperXmlLocator.fileListTtlMs = 30_000L
            MapperXmlLocator.clearFileListCacheForTest()
        }
    }

    fun testF1双撇_头扫内核_属性序与引号形态() {
        // 正常形态：DOCTYPE 之后根标签带 namespace
        assertEquals(
            "com.demo.mapper.UserMapper",
            MapperXmlLocator.namespaceFromHeadText(
                "<?xml version=\"1.0\"?>\n" +
                    "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"x\">\n" +
                    "<mapper namespace=\"com.demo.mapper.UserMapper\">\n" +
                    "    <select id=\"a\">x</select>\n" +
                    "</mapper>\n",
            ),
        )
        // namespace 属性在其它属性之后（非首属性）
        assertEquals(
            "com.demo.B",
            MapperXmlLocator.namespaceFromHeadText("<mapper parameterType=\"x\" namespace=\"com.demo.B\">"),
        )
        // 单引号形态
        assertEquals("com.demo.C", MapperXmlLocator.namespaceFromHeadText("<mapper namespace='com.demo.C'>"))
    }

    fun testF1双撇_头扫内核_非mapper与mappers前缀不误收() {
        // 根元素不是 mapper（page 内嵌 mapper 子标签）→ 内层 mapper 标签无 namespace → null
        assertNull(
            MapperXmlLocator.namespaceFromHeadText(
                "<page namespace=\"com.demo.UserMapper\"><mapper><select id=\"a\"/></mapper></page>",
            ),
        )
        // `<mappers>`（MyBatis 配置根）内的 <mapper> 条目只带 resource/class 不带 namespace
        assertNull(MapperXmlLocator.namespaceFromHeadText("<mappers><mapper resource=\"x.xml\"/></mappers>"))
        // 无 mapper 标签
        assertNull(MapperXmlLocator.namespaceFromHeadText("<foo namespace=\"x\"/>"))
        // mapper 标签无 namespace 属性
        assertNull(MapperXmlLocator.namespaceFromHeadText("<mapper><select id=\"a\"/></mapper>"))
    }
}
