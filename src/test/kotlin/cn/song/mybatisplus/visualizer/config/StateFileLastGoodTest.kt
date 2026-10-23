package cn.song.mybatisplus.visualizer.config

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Document
import org.jdom.Element
import org.jdom.output.XMLOutputter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * T-MagpieGems W3：StateFileLastGood「坏文件 ≠ 空文件」三件套纯逻辑测试。
 *
 * 反向探针约定（对应仓纪律 S2）：摘掉存证分支 → test 2/3 红；摘掉恢复 apply → test 2 红；
 * 摘掉「文件不存在不回落」闸 → test 4 红；摘掉存证去重 → test 5 红。
 */
class StateFileLastGoodTest {

    /** XmlSerializer 试验豆（须无参构造 + var 字段）。 */
    class SampleState {
        var name: String? = null
        var count: Int = 0
    }

    private lateinit var dir: Path
    private lateinit var mainFile: Path
    private lateinit var bakFile: Path

    private val applied = ArrayList<SampleState>()

    private fun runCheck(): StateFileLastGood.Outcome =
        StateFileLastGood.checkAndRecover(
            mainFile = mainFile,
            bakFile = bakFile,
            componentName = "SampleComponent",
            stateClass = SampleState::class.java,
            apply = { applied.add(it) },
        )

    private fun writeMain(content: String) {
        Files.write(mainFile, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeSampleTo(path: Path, name: String, count: Int) {
        val component = Element("component").setAttribute("name", "SampleComponent")
        XmlSerializer.serializeInto(SampleState().apply { this.name = name; this.count = count }, component)
        val doc = Document(Element("application").addContent(component))
        Files.write(path, XMLOutputter().outputString(doc).toByteArray(Charsets.UTF_8))
    }

    private fun evidenceFiles(): List<Path> =
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().contains(StateFileLastGood.BAD_INFIX) }.toList()
        }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mpsv-lastgood-test")
        mainFile = dir.resolve("sample.xml")
        bakFile = dir.resolve("sample.xml.bak")
        applied.clear()
    }

    @After
    fun tearDown() {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ---------- 三件套 ①：健康即刷新 .bak ----------

    @Test
    fun test主文件健康_返回HEALTHY并刷新备份() {
        writeSampleTo(mainFile, "keeper", 7)
        val outcome = runCheck()
        assertEquals(StateFileLastGood.Status.HEALTHY, outcome.status)
        assertTrue("健康路径应建立 .bak", Files.exists(bakFile))
        assertTrue("副本与主文件内容一致", Files.readAllBytes(bakFile).contentEquals(Files.readAllBytes(mainFile)))
        assertTrue("健康路径不应触发恢复回调", applied.isEmpty())
    }

    @Test
    fun test主文件健康_内容未变时不重写备份() {
        writeSampleTo(mainFile, "keeper", 7)
        runCheck()
        val bakMtime1 = Files.getLastModifiedTime(bakFile)
        Thread.sleep(20)
        runCheck()
        assertEquals("内容相同不重复写盘", bakMtime1, Files.getLastModifiedTime(bakFile))
    }

    // ---------- 三件套 ②：损坏即存证 + 回落恢复 ----------

    @Test
    fun test主文件损坏_回落bak恢复内存态并存证() {
        writeSampleTo(bakFile, "restored", 42)
        writeMain("<application><component name=\"SampleComponent\"><name>截断")
        val outcome = runCheck()
        assertEquals(StateFileLastGood.Status.RECOVERED, outcome.status)
        assertEquals("恢复回调只此一次，值来自 .bak", 1, applied.size)
        assertEquals("restored", applied[0].name)
        assertEquals(42, applied[0].count)
        assertNotNull("坏文件必须存证", outcome.detail)
        assertEquals("存证文件在磁盘上", 1, evidenceFiles().size)
        assertEquals("存证不得改动主文件本体", "<application><component name=\"SampleComponent\"><name>截断",
            String(Files.readAllBytes(mainFile), Charsets.UTF_8))
    }

    @Test
    fun test主文件损坏无bak_存证且不恢复() {
        writeMain("not an xml {{{")
        val outcome = runCheck()
        assertEquals(StateFileLastGood.Status.CORRUPT_NO_BACKUP, outcome.status)
        assertTrue("无备份绝不调用恢复回调", applied.isEmpty())
        assertEquals("坏文件仍须存证", 1, evidenceFiles().size)
    }

    @Test
    fun testbak也损坏_退到CORRUPT_NO_BACKUP() {
        writeMain("<application><component")
        Files.write(bakFile, "rubbish \u0000 bytes".toByteArray(Charsets.UTF_8))
        val outcome = runCheck()
        assertEquals(StateFileLastGood.Status.CORRUPT_NO_BACKUP, outcome.status)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun test坏文件_重复巡检不刷证据文件() {
        writeSampleTo(bakFile, "restored", 42)
        writeMain("rubbish")
        runCheck()
        runCheck()
        runCheck()
        assertEquals("同轮坏文件只存证一次", 1, evidenceFiles().size)
    }

    // ---------- 三件套 ③：文件不存在 ≠ 空 ----------

    @Test
    fun test主文件不存在_不回落不动备份() {
        writeSampleTo(bakFile, "mustStay", 1)
        val outcome = runCheck()
        assertEquals(StateFileLastGood.Status.NO_FILE, outcome.status)
        assertTrue("人为删除不复活", applied.isEmpty())
        assertTrue(".bak 保持原样", Files.exists(bakFile))
        val restored = SampleState()
        XmlSerializer.deserializeInto(
            restored,
            org.jdom.input.SAXBuilder().build(Files.newInputStream(bakFile)).rootElement,
        )
        assertEquals("mustStay", restored.name)
        assertEquals(1, restored.count)
    }

    // ---------- 结构守卫 ----------

    @Test
    fun test结构不合法的component元素_退到CORRUPT_NO_BACKUP() {
        writeMain("rubbish")
        // bak 合法但没有匹配的 component 名
        val component = Element("component").setAttribute("name", "OtherComponent")
        XmlSerializer.serializeInto(SampleState().apply { name = "x" }, component)
        val doc = Document(Element("application").addContent(component))
        Files.write(bakFile, XMLOutputter().outputString(doc).toByteArray(Charsets.UTF_8))
        val outcome = runCheck()
        assertEquals(StateFileLastGood.Status.CORRUPT_NO_BACKUP, outcome.status)
        assertTrue(applied.isEmpty())
    }

    // ---------- 接线层：runMaintenanceOnce 状态变化通知去重 ----------

    @Test
    fun test巡检通知只报状态变化() {
        writeSampleTo(mainFile, "ok", 1)
        val startup = SettingsLastGoodStartup()
        val target = SettingsLastGoodStartup.Target(
            main = mainFile,
            componentName = "SampleComponent",
            stateClass = SampleState::class.java,
            label = "试验面",
            apply = { },
        )
        val notifications = ArrayList<Pair<String, StateFileLastGood.Status>>()
        val notified = HashMap<String, StateFileLastGood.Status>()

        startup.runMaintenanceOnce(listOf(target), { t, s, _ -> notifications.add(t.label to s) }, notified)
        assertTrue("HEALTHY 不通知", notifications.isEmpty())

        Files.write(mainFile, "rubbish".toByteArray(Charsets.UTF_8))
        Files.deleteIfExists(bakFile)
        startup.runMaintenanceOnce(listOf(target), { t, s, _ -> notifications.add(t.label to s) }, notified)
        assertEquals(1, notifications.size)
        assertEquals(StateFileLastGood.Status.CORRUPT_NO_BACKUP, notifications[0].second)

        // 坏文件未修复期间重复巡检：状态没变 → 不重复通知
        startup.runMaintenanceOnce(listOf(target), { t, s, _ -> notifications.add(t.label to s) }, notified)
        assertEquals(1, notifications.size)
        assertNull(notified["不存在面"])
    }

    @Test
    fun test常量与结构契约() {
        assertEquals(".bak", StateFileLastGood.BAK_SUFFIX)
        assertEquals(".bad-", StateFileLastGood.BAD_INFIX)
        assertFalse(StateFileLastGood.parseDocument("rubbish".toByteArray()) != null)
        assertNotNull(StateFileLastGood.parseDocument("<application/>".toByteArray()))
    }
}
