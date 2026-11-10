package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-M55-01：日志页 / 历史页「跳转到 XML」目标解析测试。
 *
 * headless 下不触发弹窗/导航副作用，仅断言确定性解析分支：
 *  - mapperStatement 非空 + XML 命中 → MapperXml 目标；
 *  - mapperStatement 缺失（旧拦截器 3 段标记 / 注解 SQL）→ null（仅「跳转到 Java」，行为不劣化）；
 *  - mapperStatement 非空但 XML 不存在（MP 内置语句 / Wrapper CRUD）→ null，回退 Java 分支仍 Precise。
 */
class SqlLogPanelXmlJumpTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        // T-SqlListCatchup 券A（随 20261108 合并进 dev）：notifyUi 的 150ms 刷新窗在 CI 慢机上
        // 让「喂条目→断言列表已含」竞态红（本地快机偶尔绿）——注入同步调度器对齐 master 侧
        // SqlLogTailFollowTest 等的修复口径（机制本身由 SqlLogManagerFlushWindowTest 覆盖）。
        manager = SqlLogManager(project).also { it.catchUpScheduler = { _, action -> action() } }
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private val mapperXml = """<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="map">SELECT * FROM t_user WHERE id = #{id}</select>
</mapper>
"""

    /** 在项目根目录写出真实文件并刷新 VFS（XML 走索引、Java 走 SourcePathResolver 都要求磁盘真实存在）。 */
    private fun writeRealFile(relativePath: String, content: String): File {
        val f = File(project.basePath!!, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
        VfsUtil.markDirtyAndRefresh(false, true, true, f)
        return f
    }

    private fun addCallerJava() {
        writeRealFile("src/main/java/cn/demo/service/UserService.java", "package cn.demo.service;\nclass UserService {}\n")
    }

    private fun entry(
        sourceFile: String? = null,
        sourceLine: Int = 0,
        mapperStatement: String? = null,
    ): LogSqlEntry = LogSqlEntry(
        rawSql = "SELECT * FROM t_user WHERE id = 1",
        restoredSql = "SELECT * FROM t_user WHERE id = 1",
        params = emptyList(),
        timestamp = 1L,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        mapperStatement = mapperStatement,
    )

    fun testXml命中_双面板均解析出MapperXml目标() {
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", mapperXml)
        addCallerJava()
        val panel = SqlLogPanel(project, manager)
        val history = HistorySqlLogPanel(project)
        try {
            val e = entry(
                sourceFile = "src/main/java/cn/demo/service/UserService.java",
                sourceLine = 10,
                mapperStatement = "com.demo.mapper.UserMapper.selectById",
            )
            val xmlTarget = panel.resolveEntryXmlJump(e)
            assertNotNull("日志页应解析出 XML 目标", xmlTarget)
            assertEquals("UserMapper.xml", xmlTarget!!.virtualFile.name)
            assertEquals("行号 = 语句标签行（0-based）", 2, xmlTarget.line)

            val historyTarget = history.resolveXmlJumpTarget(e)
            assertNotNull("历史页应解析出 XML 目标", historyTarget)
            assertEquals(xmlTarget.virtualFile, historyTarget!!.virtualFile)
            assertEquals(xmlTarget.line, historyTarget.line)
        } finally {
            panel.dispose()
            history.dispose()
        }
    }

    fun testMapperStatement缺失_返回null_仅Java跳转() {
        addCallerJava()
        val panel = SqlLogPanel(project, manager)
        val history = HistorySqlLogPanel(project)
        try {
            val e = entry(sourceFile = "src/main/java/cn/demo/service/UserService.java", sourceLine = 10)
            assertNull("无 mapperStatement 不应给出 XML 目标", panel.resolveEntryXmlJump(e))
            assertNull(history.resolveXmlJumpTarget(e))
            // Java 跳转不受影响（不劣化）
            assertTrue(panel.resolveEntryJump(e) is SqlLogPanel.LogJumpTarget.Precise)
        } finally {
            panel.dispose()
            history.dispose()
        }
    }

    fun testXml不存在_返回null_Java跳转回退不劣化() {
        // MP 内置语句形态：mapperStatement 在（ms.getId() 恒有值）但磁盘无对应 XML
        addCallerJava()
        val e = entry(
            sourceFile = "src/main/java/cn/demo/service/UserService.java",
            sourceLine = 10,
            mapperStatement = "com.demo.mapper.UserMapper.selectById",
        )
        val panel = SqlLogPanel(project, manager)
        val history = HistorySqlLogPanel(project)
        try {
            assertNull("XML 不存在应返回 null（回退 Java）", panel.resolveEntryXmlJump(e))
            assertNull(history.resolveXmlJumpTarget(e))
            assertTrue("Java 跳转回退应仍可精确解析", panel.resolveEntryJump(e) is SqlLogPanel.LogJumpTarget.Precise)
        } finally {
            panel.dispose()
            history.dispose()
        }
    }

    fun testXml命中时_Java跳转同样可用() {
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", mapperXml)
        addCallerJava()
        val e = entry(
            sourceFile = "src/main/java/cn/demo/service/UserService.java",
            sourceLine = 10,
            mapperStatement = "com.demo.mapper.UserMapper.selectById",
        )
        val panel = SqlLogPanel(project, manager)
        try {
            // 双目标并存：XML 命中不挤占 Java 跳转
            assertNotNull(panel.resolveEntryXmlJump(e))
            assertTrue(panel.resolveEntryJump(e) is SqlLogPanel.LogJumpTarget.Precise)
        } finally {
            panel.dispose()
        }
    }
}
