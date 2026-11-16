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
import org.junit.Assert.assertTrue

/**
 * T-PerfSweep30 券02/02e：DumbMode 点击链防护夹具（三链：gutter 双跳 / 日志跳转 / 预览 compute）。
 *
 * dumb 夹具用平台 [DumbModeTestUtils.computeInDumbModeSynchronously]（显式置 dumb——不走写意图
 * 判据，BasePlatformTestCase 全程持写意图与本面无关），断言三条点击链在 dumb 态：
 *  ①不静默成功（返回「未命中」等价语义或按 F1''' 契约抛 IndexNotReadyException 由 provider 层
 * 兜底为 Lookup.Unknown，2026-11-17 master 并入 dev 合并对齐）；②不把空结果写缓存（smart 恢复后
 * 必须重新解析并命中——旧 T-PS5-05 单槽 TTL 缓存已退役，空结果固化风险由平台索引增量接管）。
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

    fun testDumb_日志跳转_resolveXml_dumb抛IndexNotReady且smart恢复后命中() {
        addMapperXml()
        val entry = cn.song.mybatisplus.visualizer.log.LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            mapperStatement = "com.demo.mapper.UserMapper.selectById",
        )
        // dumb 态（master F1''' 形态语义，2026-11-17 合并对齐）：MapperNamespaceIndex 的
        // fileBasedIndex 查询在 dumb 抛 IndexNotReadyException——dumb 防护收在调用侧
        // （gutter provider 走 locateOrUnknown 的 Lookup.Unknown 兜底，见下一条用例）。
        // dev 侧旧 T-PS5-05 TTL 槽的「dumb 返回 null 不抛」前提随该机制退役。
        val dumbErr = runCatching {
            inDumb {
                cn.song.mybatisplus.visualizer.log.LogEntryJumpOps.resolveXml(project, entry)
            }
        }.exceptionOrNull()
        assertTrue(
            "dumb 态 resolveXml 应抛 IndexNotReadyException（provider 层兜底）实际=$dumbErr",
            dumbErr is com.intellij.openapi.project.IndexNotReadyException,
        )
        // smart 恢复后：必须重新解析并命中——dumb 异常不留任何空结果缓存
        val smartTarget = cn.song.mybatisplus.visualizer.log.LogEntryJumpOps.resolveXml(project, entry)
        assertNotNull("smart 恢复后日志跳转应命中", smartTarget)
        assertEquals("UserMapper.xml", smartTarget!!.virtualFile.name)
    }

    // ------------------------------------------------------------------
    // ① gutter 双跳链（MapperXmlLocator + MybatisXmlTargets）
    // ------------------------------------------------------------------

    fun testDumb_gutter双跳_MapperXmlLocator_locate_dumb抛IndexNotReady且locateOrUnknown归Unknown() {
        addMapperXml()
        val dumbLocateErr = runCatching {
            inDumb {
                cn.song.mybatisplus.visualizer.log.MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
            }
        }.exceptionOrNull()
        assertTrue(
            "dumb 态 locate 应抛 IndexNotReadyException（provider 层兜底）实际=$dumbLocateErr",
            dumbLocateErr is com.intellij.openapi.project.IndexNotReadyException,
        )
        // provider 层实际消费的三态契约：dumb → Lookup.Unknown（不抛，「暂时查不了」保留图标）
        val dumbLookup = inDumb {
            cn.song.mybatisplus.visualizer.log.MapperXmlLocator.locateOrUnknown(project, "com.demo.mapper.UserMapper.selectById")
        }
        assertTrue(
            "dumb 态 locateOrUnknown 应归 Lookup.Unknown 实际=$dumbLookup",
            dumbLookup is cn.song.mybatisplus.visualizer.log.MapperXmlLocator.Lookup.Unknown,
        )
        // smart 恢复后：正常命中（空结果不得被缓存）
        val smartLookup = cn.song.mybatisplus.visualizer.log.MapperXmlLocator.locateOrUnknown(project, "com.demo.mapper.UserMapper.selectById")
        assertTrue(
            "smart 恢复后 locateOrUnknown 应 Found 实际=$smartLookup",
            smartLookup is cn.song.mybatisplus.visualizer.log.MapperXmlLocator.Lookup.Found,
        )
        assertEquals("UserMapper.xml", (smartLookup as cn.song.mybatisplus.visualizer.log.MapperXmlLocator.Lookup.Found).location.virtualFile.name)
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
