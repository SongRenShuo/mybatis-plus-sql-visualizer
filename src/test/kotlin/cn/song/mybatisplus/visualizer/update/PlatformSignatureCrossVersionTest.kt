package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.acp.SignatureDistroDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors


/**
 * 跨版本平台签名回归（T-DynUpdate 262 签名漂移事故护栏）。
 *
 * 背景：262 平台 `unloadPlugin` 首参从 IdeaPluginDescriptorImpl 漂移为子类 PluginMainDescriptor，
 * 反射探测器在 243 单测与 verifyPlugin 均无法复现（反射绕过编译期检查、测试只跑构建平台 243），
 * 只有真实用户升级才暴露。本测试把「签名核验」本身变成用例：对**本机全部已解压的目标平台
 * 发行版**（gradle transforms 里 verifyPlugin 下载的 243/253/262 + 已安装 IDE 兜底），用各发行版
 * 自带 JBR 运行 [PlatformSignatureChecker]，以与生产一致的弹性匹配逻辑解析动态安装五入口。
 * 任何目标版本的签名漂移都会在本地测试失败，不再依赖真实用户踩雷。
 *
 * T-DbSchemaCompletion 追加第 6 项：`DatabaseSchemaResolver` 的反射入口 `ObjectKind.COLUMN`
 * （静态字段读法）——DBN 的 ObjectKind 是手写 type-safe enum、`enumConstants` 恒 null，历史上按
 * 枚举取导致 schema 列遍历从未执行且静默无日志；本项在捆绑 Database Tools 的发行版上验证该入口
 * 可用，未捆绑者打印 SKIP 不计缺失。
 *
 * T-XmlSynHi D68 追加第 8 项：mapper XML 注入门「平台 per-host 探测」运行面——
 * `MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME` 常量 + `ProjectExtensionPointName.getExtensions`
 * + `MultiHostRegistrar` stub 三方法（243 lib/util-8.jar / 262 lib/intellij.platform.core.jar 双版
 * javap 实证同形态）；顺带 INFO 打印三代 SQL 注入器在场实况（SqlLanguageInjector 243+ 已移除、
 * SqlTypeBasedInjector 代际改名——探测经 EP 枚举对改名免疫，INFO 不计缺失）。
 *
 * O-145 W0/W1 追加实际 SQL PSI 与模块身份入口：InjectedLanguageManager 精确命中/owner 候选 API、
 * 反射类型 `com.intellij.sql.psi.SqlFile`、ModuleUtilCore.moduleContainsFile，以及
 * ExternalSystemModulePropertyManager 的 linked project id/path。生产不直接链接 SqlFile。
 *
 * 发行版缺失时按 Assume 跳过对应家族（CI 机器可能只装部分版本）；一个家族都没有则整类跳过。
 */
class PlatformSignatureCrossVersionTest {

    /** 直接调用跨版本运行器使用的登记方法，覆盖形态漂移而不是重复实现解析器。 */
    @Test
    fun attachTargetCommandLineSignatureIsExact() {
        fun check(type: Class<*>): Int = PlatformSignatureChecker.checkAttachTargetCommandLine(type)
        assertEquals("公共实例零参String应通过", 0, check(CommandLinePublic::class.java))
        assertEquals("继承的公共方法也应通过", 0, check(CommandLineInherited::class.java))
        for (wrong in listOf(CommandLineWrongReturn::class.java, CommandLineWithArgument::class.java,
            CommandLineStatic::class.java, CommandLinePrivate::class.java, CommandLineMissing::class.java)) {
            assertEquals("签名漂移必须拒绝：${wrong.simpleName}", 1, check(wrong))
        }
        val emptyLoader = object : ClassLoader(null) {}
        assertEquals("平台类缺失必须拒绝", 1, PlatformSignatureChecker.checkAttachTargetApis(emptyLoader))
    }

    /**
     * T-DynUpdate263：卸载预检能力族判定——旧入口优先（243/262 与改动前同路径），
     * 263 才落到 validate；同名不同返回类型（243 的 `checkCanUnloadWithoutRestart` 返回 String、
     * 262/263 返回 boolean）必须分开绑。三族全缺必须判缺失，证明本项真在核签名而不是空绿。
     */
    @Test
    fun unloadGateSignatureIsExact() {
        assertEquals("243/262 有旧入口时必须命中 legacy", "legacy",
            PlatformSignatureChecker.resolveUnloadGateFamily(GateLegacy::class.java, GateBase::class.java))
        assertEquals("263 形态（旧入口已删）应命中 validate", "validate",
            PlatformSignatureChecker.resolveUnloadGateFamily(Gate263::class.java, GateBase::class.java))
        assertEquals("243 的 check 返回 String，应归 validate 语义", "validate(243-name)",
            PlatformSignatureChecker.resolveUnloadGateFamily(Gate243Check::class.java, GateBase::class.java))
        assertEquals("check 形态应命中 check", "check",
            PlatformSignatureChecker.resolveUnloadGateFamily(GateCheckOnly::class.java, GateBase::class.java))
        assertNull("三族全缺必须返回 null",
            PlatformSignatureChecker.resolveUnloadGateFamily(GateEmpty::class.java, GateBase::class.java))
        assertEquals("三族全缺必须判缺失", 1,
            PlatformSignatureChecker.checkUnloadGateApis(GateEmpty::class.java, GateBase::class.java))
    }

    open class GateBase
    class Gate263 {
        fun validateCanUnloadWithoutRestart(p: GateBase): String? = null
        fun checkCanUnloadWithoutRestart(p: GateBase): Boolean = true
    }
    class Gate243Check {
        fun checkCanUnloadWithoutRestart(p: GateBase): String? = null
    }
    class GateCheckOnly {
        fun checkCanUnloadWithoutRestart(p: GateBase): Boolean = true
    }
    class GateLegacy {
        fun allowLoadUnloadWithoutRestart(p: GateBase): Boolean = true
    }
    class GateEmpty

    open class CommandLinePublic { fun getCommandLine(): String = "unused" }
    class CommandLineInherited : CommandLinePublic()
    class CommandLineWrongReturn { fun getCommandLine(): Any = "unused" }
    class CommandLineWithArgument { fun getCommandLine(unused: String): String = unused }
    class CommandLineStatic { companion object { @JvmStatic fun getCommandLine(): String = "unused" } }
    class CommandLinePrivate { private fun getCommandLine(): String = "unused" }
    class CommandLineMissing

    @Test
    fun previewEditorPopupSignatureIsExact() {
        fun check(type: Class<*>) = PlatformSignatureChecker.checkPreviewEditorPopupSignatures(type)
        assertEquals(0, check(PopupPublic::class.java))
        assertEquals(0, check(PopupInherited::class.java))
        assertEquals(1, check(PopupWrongReturn::class.java))
        assertEquals(1, check(PopupWrongArgument::class.java))
        assertEquals(1, check(PopupStatic::class.java))
        assertEquals(2, check(PopupMissing::class.java))
        assertEquals(1, PlatformSignatureChecker.checkPreviewEditorPopupApis(object : ClassLoader(null) {}))
    }

    /**
     * UI025：ExternalAnnotator 四段调度签名 + AnnotationBuilder 落点 API。
     * 空 ClassLoader 必须整体拒绝（证明本项真的在核平台类，而不是空绿）。
     */
    @Test
    fun externalAnnotatorSignatureIsExact() {
        assertEquals(0, PlatformSignatureChecker.checkExternalAnnotatorApis(javaClass.classLoader))
        assertEquals(
            "平台类缺失必须拒绝",
            1,
            PlatformSignatureChecker.checkExternalAnnotatorApis(object : ClassLoader(null) {}),
        )
    }

    open class PopupPublic {
        fun setContextMenuGroupId(id: String) {}
        fun getContextMenuGroupId(): String = "unused"
    }
    class PopupInherited : PopupPublic()
    class PopupWrongReturn {
        fun setContextMenuGroupId(id: String): String = id
        fun getContextMenuGroupId(): String = "unused"
    }
    class PopupWrongArgument {
        fun setContextMenuGroupId(id: Int) {}
        fun getContextMenuGroupId(): String = "unused"
    }
    class PopupStatic {
        companion object { @JvmStatic fun setContextMenuGroupId(id: String) {} }
        fun getContextMenuGroupId(): String = "unused"
    }
    class PopupMissing

    @Test
    fun nativeConsoleLifetimeSignatureIsExact() {
        fun check(type: Class<*>) = PlatformSignatureChecker.checkNativeConsoleViewLifetimeSignatures(
            type, LifetimeClient::class.java, LifetimeView::class.java)
        assertEquals(0, check(LifetimeService::class.java))
        assertEquals(1, check(LifetimeWrongReturn::class.java))
        assertEquals(1, check(LifetimeStaticLookup::class.java))
        assertEquals(2, check(LifetimeMissing::class.java))
        assertEquals(1, PlatformSignatureChecker.checkNativeConsoleViewLifetimeApis(object : ClassLoader(null) {}, true))
    }

    class LifetimeClient
    class LifetimeView
    class LifetimeService {
        companion object { @JvmStatic fun getInstance(): LifetimeService = LifetimeService() }
        fun getClientViewIfAny(client: LifetimeClient): LifetimeView? = null
    }
    class LifetimeWrongReturn {
        companion object { @JvmStatic fun getInstance(): LifetimeWrongReturn = LifetimeWrongReturn() }
        fun getClientViewIfAny(client: LifetimeClient): Any? = null
    }
    class LifetimeStaticLookup {
        companion object {
            @JvmStatic fun getInstance(): LifetimeStaticLookup = LifetimeStaticLookup()
            @JvmStatic fun getClientViewIfAny(client: LifetimeClient): LifetimeView? = null
        }
    }
    class LifetimeMissing

    @Test
    fun sessionOutputSignatureIsExact() {
        fun check(service: Class<*>, client: Class<*>) = PlatformSignatureChecker.checkSessionOutputSignatures(
            service, client, SessionType::class.java, SessionType::class.java, LifetimeView::class.java)
        assertEquals(0, check(SessionLookup::class.java, SessionClient::class.java))
        assertEquals(1, check(SessionLookupWrong::class.java, SessionClient::class.java))
        assertEquals(1, check(SessionLookup::class.java, SessionClientWrong::class.java))
        assertEquals(2, check(LifetimeMissing::class.java, LifetimeMissing::class.java))
    }
    class SessionType
    class SessionClient { fun getSession(): SessionType = SessionType() }
    class SessionClientWrong { fun getSession(): Any = SessionType() }
    class SessionLookup { fun getViewIfAny(session: SessionType): LifetimeView? = null }
    class SessionLookupWrong { fun getViewIfAny(session: SessionType): Any? = null }

    @Test
    fun dynamicInstallApisResolveOnAllLocalTargetPlatforms() {
        val distros = SignatureDistroDiscovery.ideaDistros
        assumeTrue("本机未发现任何 IDEA 发行版，跨版本签名核验无从执行", distros.isNotEmpty())

        // T-TestPerf6：逐发行版核验并行化（口径与 McpDbSignatureCrossVersionTest 同款——
        // invokeAll 保提交序、聚合与输出顺序不变、ExecutionException 还原为测试 error 形态、
        // 池上限 3）。
        val pool = Executors.newFixedThreadPool(minOf(3, distros.size))
        val results: List<Pair<Pair<String, File>, CheckResult>> = try {
            pool.invokeAll(distros.map { (family, root) ->
                Callable { (family to root) to runChecker(root) }
            }).map { future ->
                try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e }
            }
        } finally {
            pool.shutdownNow()
        }

        val failures = mutableListOf<String>()
        val covered = mutableListOf<String>()
        for ((target, result) in results) {
            val (family, root) = target
            covered.add(family)
            if (result.exitCode != 0) {
                failures.add("IU-$family (${root.path}):\n${result.output}")
            }
            assertTrue("IU-$family 必须实际执行UI-007签名登记，不能仅凭RESULT OK空绿：\n${result.output}",
                result.output.lineSequence().any { it.startsWith("OK   BaseProcessHandler.getCommandLine[] -> ") })
            // T-DynUpdate263：每个发行版都必须解析出卸载预检能力族（263 移除了旧入口，
            // 护栏若仍绑旧名字会在这里红，而不是等用户升级后才发现）
            assertTrue("IU-$family 必须解析出卸载预检能力族（T-DynUpdate263）：\n${result.output}",
                result.output.lineSequence().any { it.startsWith("OK   unload gate family=") })
            for (signature in listOf(
                "EditorEx.setContextMenuGroupId[class java.lang.String]",
                "EditorEx.getContextMenuGroupId[]",
                "DatabaseSessionViewService.getInstance[]",
                "DatabaseSessionViewService.getClientViewIfAny[DatabaseSessionClientWithFile]",
                "DatabaseSessionClientWithFile.getSession[]",
                "DatabaseSessionViewService.getViewIfAny[DatabaseSession]",
                "BaseLogView.getConsoleView[]",
            )) {
                assertTrue("IU-$family 必须实际执行UI015签名登记：$signature\n${result.output}",
                    result.output.lineSequence().any { it.startsWith("OK   $signature -> ") })
            }
        }
        assumeTrue("未发现可运行的目标平台发行版", covered.isNotEmpty())
        println("跨版本签名核验覆盖平台: ${covered.joinToString("、")}")
        assertEquals(failures.joinToString("\n\n"), 0, failures.size)
    }

    // ------------------------------------------------------------------
    // 发行版发现：T-TestPerf6 起收敛到 testutil/SignatureDistroDiscovery 单例（口径原样：
    // gradle caches 下 verifyPlugin 转压的发行目录 + 已安装 IDE 兜底，一次遍历三类共享）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 检查器编译（一次性）与执行
    // ------------------------------------------------------------------

    private fun runChecker(distro: File): CheckResult {
        val java = sequenceOf(
            File(distro, "jbr/bin/java.exe"),
            File(distro, "jbr/bin/java"),
        ).firstOrNull { it.isFile }?.absolutePath ?: "java"
        // 检查器类就在测试运行时类路径上（src/test/java 正常编译产物）；-cp 只带检查器类目录
        //（完整测试类路径超 Windows 命令行 32K 上限 error=206）。发行版 JBR（21/25）可加载
        // Java 17 字节码，再用 URLClassLoader(parent=null) 加载本发行版平台 jar——字节码版本
        // 天然兼容，不受测试 JVM（17）读不了 262 major-69 类文件的限制
        // codeSource.location 在部分 classloader 下返回 null（262 实机同款现象），用 resource 定位
        val checkerResource = PlatformSignatureChecker::class.java.classLoader
            .getResource("PlatformSignatureChecker.class")!!.toURI()
        val checkerHome = File(checkerResource).parentFile.absolutePath
        val proc = ProcessBuilder(
            listOf(java, "-cp", checkerHome, "PlatformSignatureChecker", File(distro, "lib").absolutePath),
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return CheckResult(exit, output)
    }

    private data class CheckResult(val exitCode: Int, val output: String)
}
