package cn.song.mybatisplus.visualizer.dumbmode

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * T-PerfSweep30 券02/02e：DumbMode 点击链防护夹具（三链：gutter 双跳 / 日志跳转 / 预览 compute）。
 *
 * dumb 夹具用平台 [DumbModeTestUtils.computeInDumbModeSynchronously]（显式置 dumb——不走写意图
 * 判据，BasePlatformTestCase 全程持写意图与本面无关），断言三条点击链在 dumb 态：
 *  ①不抛异常；②不静默成功（返回「未命中」等价语义，调用方走既有回退）；③不把空结果写缓存
 * （smart 恢复后必须重新扫描并命中——空表进 5s TTL 单槽是 T-PerfSweep30 立项主害）。
 *
 * 前提 1 探针用例（test前提1_probe_dumb态索引API行为）记录 findClass / PsiShortNamesCache /
 * FileTypeIndex 在 dumb 态的实际行为（抛 IndexNotReadyException 或静默回落），结论随券02报告回填。
 */
class DumbModeClickChainGuardTest : BasePlatformTestCase() {

    private val mapperXml = """<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="com.demo.model.User">SELECT * FROM t_user WHERE id = #{id}</select>
</mapper>
"""

    private fun addMapperXml(): com.intellij.psi.PsiFile =
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", mapperXml)

    private fun addJavaSource(path: String, content: String): com.intellij.psi.PsiFile =
        myFixture.addFileToProject(path, content.trimIndent())

    private fun resultTypeValue(): XmlAttributeValue {
        val vf = myFixture.findFileInTempDir("src/main/resources/mapper/UserMapper.xml")
        val file = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as XmlFile
        return PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java)
            .first { (it.parent as? XmlAttribute)?.name == "resultType" }
    }

    /** dumb 态执行块（退出时由平台工具等待 smart 恢复）。 */
    private fun <T> inDumb(block: () -> T): T =
        DumbModeTestUtils.computeInDumbModeSynchronously(
            project,
            com.intellij.openapi.util.ThrowableComputable<T, Throwable> { block() },
        )

    // ------------------------------------------------------------------
    // 前提 1 探针：dumb 态索引 API 行为（抛 IndexNotReadyException 还是静默回落）
    // ------------------------------------------------------------------

    fun test前提1_probe_dumb态索引API行为() {
        addMapperXml()
        addJavaSource(
            "src/main/java/com/demo/mapper/UserMapper.java",
            """package com.demo.mapper;
public interface UserMapper { void selectById(); }""",
        )
        val readings = inDumb {
            listOf(
                "findClass(FQN)" to runCatching {
                    JavaPsiFacade.getInstance(project)
                        .findClass("com.demo.mapper.UserMapper", GlobalSearchScope.allScope(project))
                        ?.qualifiedName
                },
                "findClass(short)" to runCatching {
                    JavaPsiFacade.getInstance(project)
                        .findClass("UserMapper", GlobalSearchScope.allScope(project))
                        ?.qualifiedName
                },
                "PsiShortNamesCache.getClassesByName" to runCatching {
                    PsiShortNamesCache.getInstance(project)
                        .getClassesByName("UserMapper", GlobalSearchScope.allScope(project))
                        .mapNotNull { it.qualifiedName }
                },
                "FileTypeIndex.getFiles(XML)" to runCatching {
                    FileTypeIndex.getFiles(
                        com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("XML"),
                        GlobalSearchScope.projectScope(project),
                    ).map { it.path }
                },
            ).map { (name, r) ->
                Triple(name, r.getOrNull(), r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message?.lineSequence()?.firstOrNull()}" })
            }
        }
        readings.forEach { (name, value, err) ->
            println("PROBE-前提1 dumb态 $name => value=$value err=$err")
        }
        // 探针本身只记录不判：断言随前提 1 结论回填（抛 → 闸为缺陷修复；不抛 → 闸为防护卫生项）。
        assertNotNull(readings)
    }

    // ------------------------------------------------------------------
    // ② 日志跳转链（LogEntryJumpOps.resolveXml → MapperXmlLocator）
    // ------------------------------------------------------------------

    fun testDumb_日志跳转_resolveXml_未命中且smart恢复后命中() {
        addMapperXml()
        cn.song.mybatisplus.visualizer.log.MapperXmlLocator.clearFileListCacheForTest()
        val before = cn.song.mybatisplus.visualizer.log.MapperXmlLocator.indexScanCount.get()
        val entry = cn.song.mybatisplus.visualizer.log.LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            mapperStatement = "com.demo.mapper.UserMapper.selectById",
        )
        // dumb 态：返回 null（未命中语义，调用方回退 Java 跳转），不抛异常
        val dumbTarget = inDumb {
            cn.song.mybatisplus.visualizer.log.LogEntryJumpOps.resolveXml(project, entry)
        }
        assertNull("dumb 态日志跳转应未命中（不抛不静默成功）", dumbTarget)
        // smart 恢复后：必须重新扫描并命中——dumb 态的空结果不得进 5s TTL 单槽
        val smartTarget = cn.song.mybatisplus.visualizer.log.LogEntryJumpOps.resolveXml(project, entry)
        assertNotNull("smart 恢复后日志跳转应命中（空表不得被缓存）", smartTarget)
        assertEquals("UserMapper.xml", smartTarget!!.virtualFile.name)
        assertEquals(
            "dumb 调用不计为有效扫描：smart 恢复后全扫恰一次（dumb 空槽 + smart 重扫）",
            1,
            cn.song.mybatisplus.visualizer.log.MapperXmlLocator.indexScanCount.get() - before,
        )
    }

    // ------------------------------------------------------------------
    // ① gutter 双跳链（MapperXmlLocator + MybatisXmlTargets）
    // ------------------------------------------------------------------

    fun testDumb_gutter双跳_MapperXmlLocator_locate_未命中且smart恢复后命中() {
        addMapperXml()
        cn.song.mybatisplus.visualizer.log.MapperXmlLocator.clearFileListCacheForTest()
        val before = cn.song.mybatisplus.visualizer.log.MapperXmlLocator.indexScanCount.get()
        val dumbLoc = inDumb {
            cn.song.mybatisplus.visualizer.log.MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        }
        assertNull("dumb 态 gutter 跳转应未命中（不抛不静默成功）", dumbLoc)
        val smartLoc = cn.song.mybatisplus.visualizer.log.MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("smart 恢复后 gutter 跳转应命中（空表不得被缓存）", smartLoc)
        assertEquals("UserMapper.xml", smartLoc!!.virtualFile.name)
        assertEquals(
            "smart 恢复后全扫恰一次（dumb 空表不入槽）",
            1,
            cn.song.mybatisplus.visualizer.log.MapperXmlLocator.indexScanCount.get() - before,
        )
    }

    fun testDumb_gutter双跳_MybatisXmlTargets_resolveClass_resolveInterfaceMethod_返回null() {
        addMapperXml()
        addJavaSource(
            "src/main/java/com/demo/model/User.java",
            """package com.demo.model;
public class User { private Long id; }""",
        )
        addJavaSource(
            "src/main/java/com/demo/mapper/UserMapper.java",
            """package com.demo.mapper;
public interface UserMapper { void selectById(); }""",
        )
        val value = resultTypeValue()
        // dumb 态：resolveClass / resolveInterfaceMethod 返回 null（KDoc 语义「dumb 不报红」），不抛
        val dumbClass = inDumb {
            cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets.resolveClass(project, value, "com.demo.model.User")
        }
        assertNull("dumb 态 resolveClass 应返回 null（不抛不静默成功）", dumbClass)
        val dumbMethod = inDumb {
            cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets.resolveInterfaceMethod(project, "com.demo.mapper.UserMapper", "selectById")
        }
        assertNull("dumb 态 resolveInterfaceMethod 应返回 null（不抛不静默成功）", dumbMethod)
        // smart 恢复后正常解析（闸不改变 smart 行为）
        val smartClass = cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets.resolveClass(project, value, "com.demo.model.User")
        assertNotNull("smart 恢复后 resolveClass 应命中", smartClass)
        assertEquals("com.demo.model.User", smartClass!!.qualifiedName)
        val smartMethod = cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets.resolveInterfaceMethod(project, "com.demo.mapper.UserMapper", "selectById")
        assertNotNull("smart 恢复后 resolveInterfaceMethod 应命中", smartMethod)
    }

    // ------------------------------------------------------------------
    // ③ 预览链（EntityClassResolver compute）
    // ------------------------------------------------------------------

    fun testDumb_预览链_EntityClassResolver_resolveByName_空Resolution且smart恢复后命中() {
        addJavaSource(
            "src/main/java/com/demo/model/User.java",
            """package com.demo.model;
public class User { private Long id; }""",
        )
        val dumbRes = inDumb {
            cn.song.mybatisplus.visualizer.psi.EntityClassResolver.resolveByName(project, "User", null)
        }
        assertNull("dumb 态预览链实体解析应返回 null（不抛不静默成功）", dumbRes.psiClass)
        assertEquals("dumb 态候选为空", emptyList<com.intellij.psi.PsiClass>(), dumbRes.candidates)
        val smartRes = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.resolveByName(project, "User", null)
        assertNotNull("smart 恢复后实体解析应命中", smartRes.psiClass)
        assertEquals("com.demo.model.User", smartRes.psiClass!!.qualifiedName)
    }
}
