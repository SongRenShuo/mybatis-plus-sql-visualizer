package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlatformEpDynamism] 的纯逻辑用例（T-HotSwapUnloadSafety）。
 *
 * 覆盖三件事：
 * 1. 平台版本分界——243 不可热卸载、253/262/263 可（取值来自真机取证，见类注释里的表）；
 * 2. 原因文案**点名具体 EP 与版本**，不是「平台没给原因」；
 * 3. 登记项的自洽性——每条都带取证、版本分界高于本仓 since-build（否则等于声称 243 也能热卸载）。
 */
class PlatformEpDynamismTest {

    /** 本仓 plugin.xml 的 since-build（`<idea-version since-build="243"/>`）。 */
    private val sinceBuild = 243

    @Test
    fun `243 上不可热卸载且原因点名 sql_dialect`() {
        assertFalse(PlatformEpDynamism.isUnloadSafeOn(243))
        val text = PlatformEpDynamism.refusalExplanation(243)
        assertNotNull("243 上必须给出确定原因", text)
        assertTrue("原因要点名 EP 全名: $text", text!!.contains("com.intellij.sql.dialect"))
        assertTrue("原因要给出平台版本分界: $text", text.contains("253"))
        assertTrue("原因要说清后果（重启生效）: $text", text.contains("重启"))
    }

    @Test
    fun `253 及其之后的平台可热卸载且不给误导原因`() {
        listOf(253, 262, 263).forEach { baseline ->
            assertTrue("baseline $baseline 应可热卸载", PlatformEpDynamism.isUnloadSafeOn(baseline))
            assertNull("baseline $baseline 不该编造拒绝原因", PlatformEpDynamism.refusalExplanation(baseline))
        }
    }

    @Test
    fun `登记项必须带取证且分界高于 since_build`() {
        assertTrue("登记表不能为空（否则守卫失去意义）", PlatformEpDynamism.EXTENSIONS.isNotEmpty())
        PlatformEpDynamism.EXTENSIONS.forEach { ep ->
            assertTrue("${ep.qualifiedName} 缺取证出处", ep.evidence.isNotBlank())
            assertTrue("${ep.qualifiedName} 取证里要带平台版本号", Regex("\\d{3}").containsMatchIn(ep.evidence))
            assertTrue(
                "${ep.qualifiedName} 的 dynamicSinceBaseline=${ep.dynamicSinceBaseline} 必须高于 since-build $sinceBuild，" +
                    "否则等于声称 since-build 平台也能热卸载（与取证矛盾）",
                ep.dynamicSinceBaseline > sinceBuild,
            )
            assertTrue("${ep.qualifiedName} 要写明声明在哪个描述文件", ep.declaredIn.endsWith(".xml"))
        }
    }

    @Test
    fun `阻塞集合按版本精确切分`() {
        assertEquals(
            listOf("com.intellij.sql.dialect"),
            PlatformEpDynamism.blockingOn(243).map { it.qualifiedName },
        )
        assertEquals(emptyList<String>(), PlatformEpDynamism.blockingOn(263).map { it.qualifiedName })
    }
}
