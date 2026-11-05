package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * R24：mybatis-config.xml 合成夹具判别测试（XML PSI 编辑层）。
 *
 * 正向：合成 config（无 plugins / 已有 plugins / 常规全序形态）→ 注册 `<plugin>` 落位正确、
 * 幂等重入零副作用。反向（fail-closed）：根标签非 `<configuration>`、已注册——拒绝写入。
 * [MyBatisNativeWiring.findConfigCandidates] 的 VFS 发现层同 fixture 覆盖（含零候选与多候选）。
 */
class MyBatisNativeConfigXmlEditTest : BasePlatformTestCase() {

    private val fqcn = "com.example.app.config.SqlVisualizerNativePlugin"

    private fun xmlFile(content: String): XmlFile =
        // XmlFileType 不直引（262 平台 IPGP 裁剪，HbmXmlEntityResolver 同款惯例）：FileTypeManager 取标准 XML 类型
        PsiFileFactory.getInstance(project)
            .createFileFromText(
                "mybatis-config.xml",
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("XML"),
                content.trimIndent(),
            )
            as XmlFile

    private fun register(file: XmlFile, fqcn: String): Boolean =
        WriteCommandAction.runWriteCommandAction(project, Computable {
            MyBatisNativeWiring.registerPluginInConfig(file, fqcn)
        })

    // ---------- 正向：注册与落位 ----------

    fun test注册_无plugins节_新建并挂入() {
        val file = xmlFile("""<?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <environments default="dev">
                    <environment id="dev"/>
                </environments>
                <mappers/>
            </configuration>""")
        val written = register(file, fqcn)
        assertTrue("首次注册必须实际写入", written)
        val text = file.text
        assertTrue("plugin 元素在册", text.contains("<plugin interceptor=\"$fqcn\""))
        // DTD 习惯序：无 properties/settings/typeAliases 时，plugins 挂在后置元素之前
        val pluginsIdx = text.indexOf("<plugins>")
        val envIdx = text.indexOf("<environments")
        assertTrue("plugins 应在 environments 之前（DTD 习惯序）", pluginsIdx in 0 until envIdx)
    }

    fun test注册_已有plugins节_追加不新建() {
        val file = xmlFile("""<?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <plugins>
                    <plugin interceptor="com.github.pagehelper.PageInterceptor"/>
                </plugins>
                <mappers/>
            </configuration>""")
        assertTrue(register(file, fqcn))
        val plugins = file.rootTag!!.findFirstSubTag("plugins")!!
        val pluginTags = plugins.findSubTags("plugin")
        assertEquals("既有 plugin 不丢、新 plugin 追加", 2, pluginTags.size)
        assertEquals("com.github.pagehelper.PageInterceptor", pluginTags[0].getAttributeValue("interceptor"))
        assertEquals(fqcn, pluginTags[1].getAttributeValue("interceptor"))
    }

    fun test注册_typeAliases在场_plugins挂其保持DTD习惯序() {
        val file = xmlFile("""<?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <typeAliases/>
                <mappers/>
            </configuration>""")
        assertTrue(register(file, fqcn))
        val root = file.rootTag!!
        val names = root.subTags.map { it.name }
        assertEquals("plugins 必须紧跟 typeAliases 之后、mappers 之前", listOf("typeAliases", "plugins", "mappers"), names)
    }

    fun test幂等_重复注册零副作用() {
        val file = xmlFile("""<?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <plugins/>
                <mappers/>
            </configuration>""")
        assertTrue(register(file, fqcn))
        val before = file.text
        assertFalse("第二次注册必须幂等拒绝", register(file, fqcn))
        assertEquals("幂等跳过不得改动文件", before, file.text)
        assertEquals(1, file.rootTag!!.findFirstSubTag("plugins")!!.findSubTags("plugin").size)
    }

    // ---------- 反向：fail-closed ----------

    fun test反向_根标签非configuration_拒写() {
        val file = xmlFile("""<?xml version="1.0" encoding="UTF-8"?>
            <beans/>""")
        assertFalse("非 MyBatis 配置必须拒写", register(file, fqcn))
        assertFalse(file.text.contains(fqcn))
    }

    // ---------- VFS 发现层 ----------

    fun test发现_resources下config按名收集() {
        val root = myFixture.tempDirFixture.findOrCreateDir("mod/src/main/java")
        // VFS 写（createChildData）须在写动作内（PersistentFSImpl 线程断言，243 沙盒实证）
        val (cfg, cfgSub) = WriteCommandAction.runWriteCommandAction(project, Computable {
            val res = myFixture.tempDirFixture.findOrCreateDir("mod/src/main/resources")
            myFixture.tempDirFixture.findOrCreateDir("mod/src/main/resources/sub")
            val c1 = res.createChildData(this, "mybatis-config.xml")
            val c2 = res.findChild("sub")!!.createChildData(this, "mybatis.xml")
            c1 to c2
        })
        val found = MyBatisNativeWiring.findConfigCandidates(root)
        assertEquals(setOf(cfg, cfgSub), found.toSet())
    }

    fun test发现_无resources目录_空清单() {
        val root = myFixture.tempDirFixture.findOrCreateDir("mod2/src/main/java")
        assertTrue(MyBatisNativeWiring.findConfigCandidates(root).isEmpty())
    }

    fun test发现_根标签读取_非xml与无根均null() {
        val res = myFixture.tempDirFixture.findOrCreateDir("mod3/src/main/resources")
        val good = WriteCommandAction.runWriteCommandAction(project, Computable {
            val f = res.createChildData(this, "mybatis-config.xml")
            com.intellij.openapi.vfs.VfsUtil.saveText(
                f,
                """<?xml version="1.0" encoding="UTF-8"?><configuration><mappers/></configuration>""",
            )
            f
        })
        assertEquals("configuration", ReadAction.compute<String?, RuntimeException> {
            MyBatisNativeWiring.rootTagName(com.intellij.psi.PsiManager.getInstance(project), good)
        })
    }
}
