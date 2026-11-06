package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 动态安装门面单测（T-DynUpdate F1）：zip 前置校验全分支——插件 xml 定位（直属/IPGP lib 布局）、
 * id 匹配、version 提取、坏包拒绝。纯 java.util.zip，不触平台反射与 EDT 编排
 * （install 编排层沿用任务惯例由 runIde 沙盒端到端验证）。
 */
class DynamicInstallSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pluginXml = """
        <idea-plugin>
            <id>cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer</id>
            <name>SQL Visualizer</name>
            <version>2026.9.11-beta.1</version>
        </idea-plugin>
    """.trimIndent()

    /** IPGP buildPlugin 布局：zip 顶层目录 + lib/插件 jar（plugin.xml 在 jar 内）。 */
    private fun buildIpgpLayoutZip(target: Path, xml: String = pluginXml) {
        ZipOutputStream(target.toFile().outputStream().buffered()).use { zip ->
            val jarBytes = java.io.ByteArrayOutputStream().also { jarOut ->
                ZipOutputStream(jarOut).use { jar ->
                    jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                    jar.write(xml.toByteArray())
                    jar.closeEntry()
                }
            }.toByteArray()
            zip.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/mybatis-plus-sql-visualizer-2026.9.11-beta.1.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/jsqlparser-4.9.jar"))
            zip.write(ByteArray(0))
            zip.closeEntry()
        }
    }

    @Test
    fun validateArtifactAcceptsIpgpLayout() {
        val zip = tmp.root.toPath().resolve("ok.zip")
        buildIpgpLayoutZip(zip)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("IPGP 布局应校验通过：$result", result is DynamicInstallSupport.ArtifactCheck.Ok)
        assertEquals("2026.9.11-beta.1", (result as DynamicInstallSupport.ArtifactCheck.Ok).version)
    }

    @Test
    fun validateArtifactAcceptsDirectDescriptorLayout() {
        // 直属布局：META-INF/plugin.xml 在 zip 顶层（解包态目录压缩形态）
        val zip = tmp.root.toPath().resolve("direct.zip")
        ZipOutputStream(zip.toFile().outputStream().buffered()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("META-INF/plugin.xml"))
            zipOut.write(pluginXml.toByteArray())
            zipOut.closeEntry()
        }
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("直属 plugin.xml 应校验通过：$result", result is DynamicInstallSupport.ArtifactCheck.Ok)
    }

    @Test
    fun validateArtifactRejectsWrongPluginId() {
        val zip = tmp.root.toPath().resolve("wrong-id.zip")
        val foreignXml = pluginXml
            .replace("cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer", "com.example.other")
        buildIpgpLayoutZip(zip, foreignXml)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("错包应被拒：$result", result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("id mismatch"))
    }

    @Test
    fun validateArtifactRejectsMissingVersion() {
        val zip = tmp.root.toPath().resolve("no-version.zip")
        buildIpgpLayoutZip(zip, pluginXml.replace("<version>2026.9.11-beta.1</version>", ""))
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue(result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("version"))
    }

    @Test
    fun validateArtifactRejectsMissingDescriptor() {
        val zip = tmp.root.toPath().resolve("no-xml.zip")
        ZipOutputStream(zip.toFile().outputStream().buffered()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/random.jar"))
            zipOut.write(ByteArray(0))
            zipOut.closeEntry()
        }
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue(result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("not found"))
    }

    @Test
    fun validateArtifactRejectsUnreadableFile() {
        val zip = tmp.root.toPath().resolve("corrupt.zip")
        java.nio.file.Files.write(zip, "not a zip at all".toByteArray())
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("坏包应拒绝且不抛出：$result", result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("not readable"))
    }

    // ---------------- T-SAF-05（update.validateArtifact.regex-comment-desync） ----------------

    @Test
    fun validateArtifactRejectsCommentDesyncForeignId() {
        // NEEDS-VALIDATION #5 fixture：真 id 只存在于 XML 注释里、真实 <id> 是外部身份——
        // 改前 regex 首匹配命中注释内的真 id → 误放行 Ok（门判定与平台消费者去同步，外部 id 包可装进安装链）；
        // 改后真 XML 解析忽略注释 → id mismatch 拒绝（先红：本断言在正则实现下失败，原因是返回 Ok）
        val zip = tmp.root.toPath().resolve("comment-desync.zip")
        val desyncXml = """
            <idea-plugin>
                <!-- <id>cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer</id> -->
                <id>com.example.foreign</id>
                <version>2099.1.1</version>
            </idea-plugin>
        """.trimIndent()
        buildIpgpLayoutZip(zip, desyncXml)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("注释内假 id 不得通过身份门：$result", result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue(
            (result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("id mismatch"),
        )
    }

    @Test
    fun validateArtifactAcceptsDescriptorWithForeignIdInComment() {
        // 去同步的另一面：注释里残留旧/外部 id、真实 <id> 是本插件——
        // 改前 regex 首匹配命中注释内的外部 id → 误拒（正常包被错杀，同样先红）；
        // 改后真解析忽略注释 → 放行
        val zip = tmp.root.toPath().resolve("comment-benign.zip")
        val benignXml = """
            <idea-plugin>
                <!-- 历史遗留：曾用 id com.example.old，勿恢复 -->
                <id>cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer</id>
                <name>SQL Visualizer</name>
                <version>2026.9.11-beta.1</version>
            </idea-plugin>
        """.trimIndent()
        buildIpgpLayoutZip(zip, benignXml)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("注释不参与身份判定，正常包不误杀：$result", result is DynamicInstallSupport.ArtifactCheck.Ok)
    }

    @Test
    fun validateArtifactReadsVersionAcrossLines() {
        // DOTALL 提取：version 标签跨行形态
        val zip = tmp.root.toPath().resolve("multiline.zip")
        val multilineXml = pluginXml.replace(
            "<version>2026.9.11-beta.1</version>",
            "<version>\n        2026.9.11-beta.2\n    </version>",
        )
        buildIpgpLayoutZip(zip, multilineXml)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertEquals("2026.9.11-beta.2", (result as DynamicInstallSupport.ArtifactCheck.Ok).version)
    }

    // ---------------- 平台签名漂移弹性匹配（262 实测修复） ----------------

    /** 模拟 243 形态：单参取基类。 */
    @Suppress("unused")
    private class FakePluginsBase {
        fun unloadPlugin(p: BaseDescriptor): Boolean = true
        fun unloadPlugin(p: BaseDescriptor, o: Options): Boolean = true
    }

    /** 模拟 262 形态：单参取子类（IdeaPluginDescriptorImpl → PluginMainDescriptor 漂移）。 */
    @Suppress("unused")
    private class FakePluginsSub {
        fun unloadPlugin(p: SubDescriptor): Boolean = true
        fun unloadPlugin(p: SubDescriptor, o: Options): Boolean = true
    }

    /** 模拟未来形态：仅剩双参。 */
    @Suppress("unused")
    private class FakePluginsTwoOnly {
        fun unloadPlugin(p: SubDescriptor, o: Options): Boolean = true
    }

    private open class BaseDescriptor
    private class SubDescriptor : BaseDescriptor()
    private class Options

    @Test
    fun resolveMethodAcceptsBaseFirstParam() {
        val m = DynamicInstallSupport.resolveMethodByFirstParam(
            FakePluginsBase::class.java, "unloadPlugin", BaseDescriptor::class.java,
        )
        assertEquals(1, m?.parameterTypes?.size)
    }

    @Test
    fun resolveMethodAcceptsSubFirstParam() {
        // 262 漂移：首参为描述类的子类——可赋值任一方向即命中
        val m = DynamicInstallSupport.resolveMethodByFirstParam(
            FakePluginsSub::class.java, "unloadPlugin", BaseDescriptor::class.java,
        )
        assertEquals(1, m?.parameterTypes?.size)
    }

    @Test
    fun resolveMethodPrefersFewestParams() {
        val m = DynamicInstallSupport.resolveMethodByFirstParam(
            FakePluginsTwoOnly::class.java, "unloadPlugin", BaseDescriptor::class.java,
        )
        assertEquals(2, m?.parameterTypes?.size)
    }

    @Test
    fun resolveMethodReturnsNullWhenAbsent() {
        val m = DynamicInstallSupport.resolveMethodByFirstParam(
            FakePluginsTwoOnly::class.java, "unloadPlugin", String::class.java,
        )
        assertEquals(null, m)
    }

    // ---------------- 卸载预检能力族（T-DynUpdate263） ----------------

    /** 263 形态：旧入口已移除，validate(String?) 与 check(boolean) 并存。 */
    @Suppress("unused")
    private class Gate263 {
        fun validateCanUnloadWithoutRestart(p: BaseDescriptor): String? = null
        fun checkCanUnloadWithoutRestart(p: BaseDescriptor): Boolean = true
    }

    /** 262 形态：新旧并存，旧入口优先（与改动前同路径）。 */
    @Suppress("unused")
    private class Gate262 {
        fun validateCanUnloadWithoutRestart(p: BaseDescriptor): String? = null
        fun checkCanUnloadWithoutRestart(p: BaseDescriptor): Boolean = true
        fun allowLoadUnloadWithoutRestart(p: BaseDescriptor): Boolean = true
    }

    /** 243 形态：只有旧入口。 */
    @Suppress("unused")
    private class Gate243Legacy {
        fun allowLoadUnloadWithoutRestart(p: BaseDescriptor): Boolean = true
    }

    /** 243 的 check 同名但返回 String（原因串）——必须归入 validate 语义，不能当布尔。 */
    @Suppress("unused")
    private class Gate243CheckString {
        fun checkCanUnloadWithoutRestart(p: BaseDescriptor): String? = null
    }

    /** 三族全缺（平台裁掉动态装卸能力）。 */
    @Suppress("unused")
    private class GateNone

    /** Validate 族返回原因串 → 不可卸载且带平台原话。 */
    @Suppress("unused")
    private class GateRejecting {
        fun validateCanUnloadWithoutRestart(p: BaseDescriptor): String = "not a dynamic plugin"
    }

    /** Legacy 族返回 false → 不可卸载，平台不给原因。 */
    @Suppress("unused")
    private class GateLegacyRejecting {
        fun allowLoadUnloadWithoutRestart(p: BaseDescriptor): Boolean = false
    }

    @Test
    fun unloadGateOn263UsesValidateFamily() {
        val gate = DynamicInstallSupport.resolveUnloadGate(Gate263::class.java, BaseDescriptor::class.java)
        assertEquals(DynamicInstallSupport.UnloadGateFamily.VALIDATE, gate?.family)
        assertEquals("validateCanUnloadWithoutRestart", gate?.method?.name)
    }

    @Test
    fun unloadGatePrefersLegacyWhenPresent() {
        // 262 新旧并存：必须仍走旧入口，与本次改动前逐字同路径（262 旧入口走整组重配置预演，
        // 与单插件的 validate 不是同一套判定）——保证老版本行为零变化
        val gate = DynamicInstallSupport.resolveUnloadGate(Gate262::class.java, BaseDescriptor::class.java)
        assertEquals(DynamicInstallSupport.UnloadGateFamily.LEGACY, gate?.family)
        assertEquals("allowLoadUnloadWithoutRestart", gate?.method?.name)
    }

    @Test
    fun unloadGateFallsBackToLegacyOn243() {
        val gate = DynamicInstallSupport.resolveUnloadGate(Gate243Legacy::class.java, BaseDescriptor::class.java)
        assertEquals(DynamicInstallSupport.UnloadGateFamily.LEGACY, gate?.family)
    }

    @Test
    fun unloadGateTreats243CheckStringAsValidate() {
        // 只按名字绑会把 243 的原因串当布尔（ClassCastException → 永远判不可卸载 → 永远回退重启）
        val gate = DynamicInstallSupport.resolveUnloadGate(Gate243CheckString::class.java, BaseDescriptor::class.java)
        assertEquals(DynamicInstallSupport.UnloadGateFamily.VALIDATE, gate?.family)
        assertEquals("checkCanUnloadWithoutRestart", gate?.method?.name)
    }

    @Test
    fun unloadGateAbsentWhenNoFamilyResolvable() {
        assertNull(DynamicInstallSupport.resolveUnloadGate(GateNone::class.java, BaseDescriptor::class.java))
    }

    @Test
    fun unloadGateValidateCarriesPlatformReason() {
        val gate = DynamicInstallSupport.resolveUnloadGate(GateRejecting::class.java, BaseDescriptor::class.java)!!
        val decision = gate.evaluate(GateRejecting(), BaseDescriptor())
        assertFalse("平台拒绝必须判不可卸载", decision.allowed)
        assertEquals("not a dynamic plugin", decision.reason)
    }

    @Test
    fun unloadGateValidateNullMeansAllowed() {
        val gate = DynamicInstallSupport.resolveUnloadGate(Gate263::class.java, BaseDescriptor::class.java)!!
        val decision = gate.evaluate(Gate263(), BaseDescriptor())
        assertTrue(decision.allowed)
        assertNull(decision.reason)
    }

    @Test
    fun unloadGateLegacyFalseIsRejectedWithoutReason() {
        val gate = DynamicInstallSupport.resolveUnloadGate(
            GateLegacyRejecting::class.java, BaseDescriptor::class.java,
        )!!
        val decision = gate.evaluate(GateLegacyRejecting(), BaseDescriptor())
        assertFalse(decision.allowed)
        assertNull(decision.reason)
    }
}
