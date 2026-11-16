package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.log.MapperNamespaceIndex
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex

/**
 * T-IndexScanSweep2 P1：项目级名称清单服务 [XmlSymbolNameIndexCache] 的行为面验收。
 *
 * 对应计划 §六 的 A1/A2/A3/A4/A6：
 *  - A1 候选正确（跨文件 `<sql id>` / config properties key）；
 *  - A2 连续多次调用**只构建一次**（用计数缝，不用耗时断言）；
 *  - A3 新增 mapper XML / 改 `<sql id>` / 改 properties 后**立即生效**（无 5s 窗口）；
 *  - A4 dumb 态返回空且不抛；
 *  - A6 Registry 回退通道（`enabledProvider=false` → 现扫实现仍给候选）。
 *
 * 环境说明两条：
 *  1. Light 沙箱里 `myFixture.addFileToProject` 的文件即时进索引（P-1/P-2 探针实测）；本类每次
 *     调用前 `ensureUpToDate` 把索引落定，避免「首次查询顺带建索引」把代戳推后导致的偶发二次构建。
 *  2. 单测的 project 在同一测试类内可能被复用，而本服务挂在 project 上——故 `setUp` 复位服务状态
 *     （见 [XmlSymbolNameIndexCache.resetForTest]）、每个用例用**唯一文件/命名空间**、计数断言一律用**增量**，
 *     使用例彼此独立（2026-11-16 实测过污染：某用例置 `enabledProvider=false` 后按名共用的服务被带偏）。
 */
class XmlSymbolNameIndexCacheTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        XmlSymbolNameIndexCache.getInstance(project).resetForTest()
    }

    override fun tearDown() {
        runCatching { XmlSymbolNameIndexCache.getInstance(project).resetForTest() }
        super.tearDown()
    }

    private fun service(): XmlSymbolNameIndexCache = XmlSymbolNameIndexCache.getInstance(project)

    private fun settleIndex() {
        FileBasedIndex.getInstance()
            .ensureUpToDate(MapperNamespaceIndex.NAME, project, GlobalSearchScope.projectScope(project))
    }

    private fun addMapper(name: String, namespace: String, vararg sqlIds: String) {
        val sqls = sqlIds.joinToString("") { "<sql id=\"$it\">id</sql>" }
        myFixture.addFileToProject(name, """<mapper namespace="$namespace">$sqls</mapper>""")
    }

    private fun configText(vararg keys: String): String =
        """<configuration><properties>${keys.joinToString("") { "<property name=\"$it\" value=\"v\"/>" }}</properties></configuration>"""

    private fun configureCurrent(name: String, namespace: String): XmlFile {
        myFixture.configureByText(
            name,
            """<mapper namespace="$namespace"><sql id="localFrag">id</sql><select id="sel">SELECT </select></mapper>""",
        )
        return myFixture.file as XmlFile
    }

    /**
     * 覆盖写一个已存在文件（走**文档写 + 落盘**，与用户 Ctrl+S 同链）：
     *  - `setText` 后 PSI 文本随文档即时更新（不必等 VFS 事件回流）；
     *  - `saveDocument` 落盘 → `VirtualFile.modificationStamp` 变化 → 正是本服务失效口径要捕捉的信号；
     *  - 落盘首行断言写环境（243 `FileDocumentManagerImpl`），故必须放在写命令内。
     */
    private fun overwrite(vf: VirtualFile, text: String) {
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: error("无 document: ${vf.path}")
        WriteCommandAction.runWriteCommandAction(project) {
            doc.setText(text)
            FileDocumentManager.getInstance().saveDocument(doc)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        settleIndex()
    }

    // ------------------------------------------------------------------
    // A1：候选正确
    // ------------------------------------------------------------------

    fun testA1_跨文件sqlId候选_以namespace点号形态出现且排除当前文件() {
        addMapper("x-A.xml", "com.example.NsA", "fragA1", "fragA2")
        addMapper("x-B.xml", "com.example.NsB", "fragB")
        val current = configureCurrent("x-current.xml", "com.example.NsCur")
        settleIndex()

        val got = XmlCompletionSymbols.crossFileSqlIds(project, current)

        val expected = listOf("com.example.NsA.fragA1", "com.example.NsA.fragA2", "com.example.NsB.fragB")
        assertTrue(
            "跨文件 `<sql id>` 应以 `ns.sqlId` 形态出现，缺失：${expected.filterNot { got.contains(it) }}；全量=$got",
            got.containsAll(expected),
        )
        assertFalse(
            "当前文件自身的 sql id 应由 localIds 以裸 id 补齐，不得以 `ns.id` 形态混进跨文件清单：$got",
            got.contains("com.example.NsCur.localFrag"),
        )
    }

    fun testA1_config属性key_正确聚合name属性值() {
        myFixture.addFileToProject("x-cfg1.xml", configText("schemaName", "tenantId"))
        configureCurrent("x-cur1.xml", "com.example.NsCur1")
        settleIndex()

        val got = XmlCompletionSymbols.configPropertyKeys(project)

        assertTrue("config `<properties>` key 应聚合，实际=$got", got.containsAll(listOf("schemaName", "tenantId")))
        assertFalse("不得把子标签名 `property` 当 key：$got", got.contains("property"))
    }

    // ------------------------------------------------------------------
    // A2：只构建一次
    // ------------------------------------------------------------------

    fun testA2_连续多次调用清单只构建一次() {
        addMapper("x-a2.xml", "com.example.NsA2", "fragA2")
        val current = configureCurrent("x-cur2.xml", "com.example.NsCur2")
        settleIndex()
        val buildsBefore = service().buildCount.get()

        repeat(3) { XmlCompletionSymbols.crossFileSqlIds(project, current) }
        repeat(2) { XmlCompletionSymbols.configPropertyKeys(project) }

        assertEquals(
            "同一索引状态下连续调用必须只构建一次（计数缝读数；改造前每次补全都全扫）",
            buildsBefore + 1,
            service().buildCount.get(),
        )
    }

    // ------------------------------------------------------------------
    // A3：立即生效
    // ------------------------------------------------------------------

    fun testA3_新增mapperXML后立即可见() {
        addMapper("x-a3a.xml", "com.example.NsA3a", "fragA3a")
        val current = configureCurrent("x-cur3a.xml", "com.example.NsCur3a")
        settleIndex()
        val buildsBefore = service().buildCount.get()
        val before = XmlCompletionSymbols.crossFileSqlIds(project, current)
        assertTrue("前置：NsA3a.fragA3a 应在场：$before", before.contains("com.example.NsA3a.fragA3a"))

        addMapper("x-a3a-new.xml", "com.example.NsA3aNew", "newFrag")
        settleIndex()

        val after = XmlCompletionSymbols.crossFileSqlIds(project, current)
        assertTrue(
            "新增 mapper XML 后应**立即**进入清单（无 5s 窗口）：$after",
            after.contains("com.example.NsA3aNew.newFrag"),
        )
        assertTrue(
            "新增文件应触发重建（计数应 > $buildsBefore）：${service().buildCount.get()}",
            service().buildCount.get() > buildsBefore,
        )
    }

    fun testA3_修改已有mapper的sqlId后立即可见() {
        val vf = myFixture.addFileToProject(
            "x-a3b.xml",
            """<mapper namespace="com.example.NsA3b"><sql id="oldFrag">id</sql></mapper>""",
        ).virtualFile
        val current = configureCurrent("x-cur3b.xml", "com.example.NsCur3b")
        settleIndex()
        assertTrue(
            "前置：NsA3b.oldFrag 应在场",
            XmlCompletionSymbols.crossFileSqlIds(project, current).contains("com.example.NsA3b.oldFrag"),
        )

        overwrite(vf, """<mapper namespace="com.example.NsA3b"><sql id="newFrag">id</sql></mapper>""")
        val psi = PsiManager.getInstance(project).findFile(vf) as XmlFile
        assertTrue(
            "前置：覆盖写后 PSI 必须读到新内容（否则本用例测的不是失效口径）：${psi.text}",
            psi.text.contains("newFrag"),
        )

        val after = XmlCompletionSymbols.crossFileSqlIds(project, current)
        assertTrue("改 `<sql id>` 后应立即出现新值：$after", after.contains("com.example.NsA3b.newFrag"))
        assertFalse("旧值应消失：$after", after.contains("com.example.NsA3b.oldFrag"))
    }

    fun testA3_修改config_properties后立即可见() {
        val vf = myFixture.addFileToProject("x-cfg3.xml", configText("p1")).virtualFile
        configureCurrent("x-cur3c.xml", "com.example.NsCur3c")
        settleIndex()
        assertTrue("前置：p1 应在场", XmlCompletionSymbols.configPropertyKeys(project).contains("p1"))

        overwrite(vf, configText("p2"))

        val after = XmlCompletionSymbols.configPropertyKeys(project)
        assertTrue("改 properties 后应立即出现新 key：$after", after.contains("p2"))
        assertFalse("旧 key 应消失：$after", after.contains("p1"))
    }

    // ------------------------------------------------------------------
    // A4：dumb 态
    // ------------------------------------------------------------------

    fun testA4_dumb态返回空且不抛() {
        addMapper("x-a4.xml", "com.example.NsA4", "fragA4")
        val current = configureCurrent("x-cur4.xml", "com.example.NsCur4")
        settleIndex()

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(
                "dumb 态跨文件 sql id 应返回空（与改造前同语义）",
                XmlCompletionSymbols.crossFileSqlIds(project, current).isEmpty(),
            )
            assertTrue(
                "dumb 态 config properties 应返回空（与改造前同语义）",
                XmlCompletionSymbols.configPropertyKeys(project).isEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------
    // A6：回退通道
    // ------------------------------------------------------------------

    fun testA6_registry回退通道_关掉后走现扫实现仍给候选() {
        addMapper("x-a6.xml", "com.example.NsA6", "fragA6")
        val current = configureCurrent("x-cur6.xml", "com.example.NsCur6")
        settleIndex()

        service().enabledProvider = { false }
        val buildsBefore = service().buildCount.get()
        val got = XmlCompletionSymbols.crossFileSqlIds(project, current)

        assertTrue("回退通道（现扫实现）仍应给出跨文件候选：$got", got.contains("com.example.NsA6.fragA6"))
        assertEquals("回退通道不得使用清单服务（构建计数不得变化）", buildsBefore, service().buildCount.get())
    }
}
