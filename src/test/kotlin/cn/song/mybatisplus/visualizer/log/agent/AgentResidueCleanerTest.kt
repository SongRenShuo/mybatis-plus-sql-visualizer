package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F-07 agent 宿主侧残留清理测试：注入临时目录，验证「文件被删 / 保留口径」逐条成立。
 *
 * 保留口径（宁漏删不误删）：
 *  - 只删本插件名族（`mps-sql-agent-*.jar` / `*.jar.tmp` / `*.tmp`）的普通文件；
 *  - 外来文件 / 子目录 / 其他名字一律不动；
 *  - 陈旧 CRC 名 jar（旧构建）修剪，当前在用那份不动——不影响正常 attach/premain。
 *
 * 纯文件系统测试，无平台依赖。
 */
class AgentResidueCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- cleanupPremainResidue ----------

    @Test
    fun `premain残留清理_本族jar与半成品全删_返回删除数`() {
        val dir = tmp.newFolder("mpsv-agent")
        val jar1 = File(dir, "mps-sql-agent-123456789.jar").apply { writeText("jar1") }
        val jar2 = File(dir, "mps-sql-agent-987654321.jar").apply { writeText("jar2") }
        val halfDone = File(dir, "mps-sql-agent-123456789.jar.tmp").apply { writeText("half") }
        val removed = AgentResidueCleaner.cleanupPremainResidue(dir)
        assertEquals(3, removed)
        assertFalse(jar1.exists())
        assertFalse(jar2.exists())
        assertFalse(halfDone.exists())
    }

    @Test
    fun `premain残留清理_目录删空后连目录一起移除`() {
        val dir = File(tmp.newFolder(), "mpsv-agent").apply {
            mkdirs()
            File(this, "mps-sql-agent-1.jar").writeText("x")
        }
        AgentResidueCleaner.cleanupPremainResidue(dir)
        assertFalse("目录空了应连目录一起删", dir.exists())
    }

    @Test
    fun `premain残留清理_外来文件与子目录一律不动`() {
        val dir = tmp.newFolder("mpsv-agent")
        val foreign = File(dir, "readme.txt").apply { writeText("用户自己的文件") }
        val foreignJar = File(dir, "other-tool.jar").apply { writeText("别家的 jar（前缀不合）") }
        val subdir = File(dir, "nested").apply { mkdirs() }
        val nested = File(subdir, "mps-sql-agent-1.jar").apply { writeText("子目录内不递归") }
        val removed = AgentResidueCleaner.cleanupPremainResidue(dir)
        assertEquals(0, removed)
        assertTrue(foreign.exists())
        assertTrue(foreignJar.exists())
        assertTrue("子目录不递归不删", nested.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `premain残留清理_目录不存在或为空安全返回`() {
        assertEquals(0, AgentResidueCleaner.cleanupPremainResidue(File(tmp.root, "not-exist")))
        assertEquals(0, AgentResidueCleaner.cleanupPremainResidue(tmp.newFolder("empty-dir")))
    }

    // ---------- pruneStalePremainJars ----------

    @Test
    fun `陈旧产物修剪_删不同CRC的旧jar_当前在用那份不动`() {
        val dir = tmp.newFolder("mpsv-agent")
        val keep = File(dir, "mps-sql-agent-current.jar").apply { writeText("current build") }
        val stale1 = File(dir, "mps-sql-agent-old1.jar").apply { writeText("old1") }
        val stale2 = File(dir, "mps-sql-agent-old2.jar.tmp").apply { writeText("old2 half") }
        val pruned = AgentResidueCleaner.pruneStalePremainJars(dir, keep.name)
        assertEquals(2, pruned)
        assertTrue("当前构建产物必须保留（不影响正常 attach/premain）", keep.exists())
        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
    }

    @Test
    fun `陈旧产物修剪_外来文件不动且目录缺失安全`() {
        val dir = tmp.newFolder("mpsv-agent")
        val foreign = File(dir, "keep-me.txt").apply { writeText("x") }
        assertEquals(0, AgentResidueCleaner.pruneStalePremainJars(dir, "mps-sql-agent-x.jar"))
        assertTrue(foreign.exists())
        assertEquals(0, AgentResidueCleaner.pruneStalePremainJars(File(tmp.root, "nope"), "a.jar"))
    }

    // ---------- cleanupAttachTempJar ----------

    @Test
    fun `attach临时jar删除_存在即删_null与缺失安全`() {
        val jar = tmp.newFile("mps-sql-agent-random.jar").apply { writeText("payload") }
        assertTrue(AgentResidueCleaner.cleanupAttachTempJar(jar))
        assertFalse(jar.exists())
        assertFalse(AgentResidueCleaner.cleanupAttachTempJar(null))
        assertFalse(AgentResidueCleaner.cleanupAttachTempJar(File(tmp.root, "missing.jar")))
    }

    // ---------- 名族判定 ----------

    @Test
    fun `名族判定_前缀与后缀双重约束`() {
        assertTrue(AgentResidueCleaner.isOurs("mps-sql-agent-123.jar"))
        assertTrue(AgentResidueCleaner.isOurs("mps-sql-agent-123.jar.tmp"))
        assertTrue(AgentResidueCleaner.isOurs("mps-sql-agent-random.tmp"))
        assertFalse("前缀不合不认", AgentResidueCleaner.isOurs("mps-agent-123.jar"))
        assertFalse("无后缀不认", AgentResidueCleaner.isOurs("mps-sql-agent-123"))
        assertFalse("别家同名后缀不认", AgentResidueCleaner.isOurs("other-mps-sql-agent-123.jar"))
    }
}
