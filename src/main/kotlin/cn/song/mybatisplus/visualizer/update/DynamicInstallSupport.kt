package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.BuildNumber
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.swing.JComponent

/**
 * 动态安装门面（T-DynUpdate）：下载包先尝试平台动态热替换——卸载旧版 → 解压新版 → 动态装载，
 * 任一步不满足或失败都回退 [PlatformPluginInstaller.installRestartScript] 启动脚本路径
 * （重启生效，M28 原行为），全程无中间死态。
 *
 * 平台 API 全走反射探测（243/262/263 三版 jar 实测，签名与语义已对齐；263 的入口替换见 T-DynUpdate263）：
 * - `PluginDescriptorLoader.loadDescriptorFromArtifact(Path, BuildNumber)`：从发行 zip 读新版描述（两平台 public）；
 * - 卸载可行性预检按**能力族**解析（T-DynUpdate263），**旧入口优先**（243/262 与改动前逐字同路径）：
 *   `allowLoadUnloadWithoutRestart(descriptor): Boolean`（243/262）→ 缺失时用
 *   `validateCanUnloadWithoutRestart(descriptor): String?`（非空=不可卸载原因）或
 *   `checkCanUnloadWithoutRestart(descriptor): Boolean`（262/263）。243 的 check 同名但返回
 *   **String**，故解析必须连返回类型一起判，见 [resolveUnloadGate]。预检同时覆盖
 *   `ide.dynamicPluginVetoer` 与依赖插件图；
 *
 *   ⚠️ **EP dynamic 属性本身会挡住热卸载（2026-11-06 实证）**：本插件在
 *   `META-INF/database-optional.xml` 里为四个自注册方言（DM / GaussDB / HighGo / Vastbase）
 *   扩展了 `com.intellij.sql.dialect`，该 EP 在 **243 上非动态**（253/263 起平台才标
 *   `dynamic="true"`）。于是 243 上平台必然拒绝卸载本插件：日志里 `DynamicPlugins` 会写
 *   `is not unload-safe because of extension to non-dynamic EP …`，更新只能走「重启生效」。
 *   事实表、取证方法与平台版本分界见 [PlatformEpDynamism]；本仓描述文件的静态守卫见
 *   `DynamicEpComplianceGuardTest`。
 * - `DynamicPlugins.unloadPlugin(descriptor)`：运行期卸载旧版（两平台 public，内部走 EDT write action）；
 * - `PluginInstaller.installAndLoadDynamicPlugin(Path, JComponent?, descriptor)`：解包 + 动态装载新版
 *   （两平台 public；双端反编译实证 descriptor 参数仅用于进度标题/日志/fireState，解包布局由 zip
 *   顶层目录名决定，传新版描述即可）；
 * - `PluginManagerCore.findPlugin(PluginId)`：取当前已装描述（两平台 public）。
 *
 * installFromDisk 复用路线 F0 否决：243 包私有 + `File` 参数 + 6 参，262 public + `Path` + 7 参，
 * 且两条平台流程都不卸载已加载旧版（`installAndLoadDynamicPlugin` 在旧版仍加载时直接返回 false），
 * 热更新必失败——故手工编排 unload → install 序列。
 *
 * 自更新的特殊处境：[install] 的调用栈运行在将被卸载的旧 classloader 上——卸载/装载回调内只触达
 * 平台 API 与纯函数，返回后由调用方（UI 层）经平台通知报告结果，不再触达自身服务。
 *
 * 派发模态（T-DynUpdateWriteSafe，2026-09-22）：卸载/装载一律以 [dispatchModalityForUnload] 解析出的
 * 写安全模态派发，禁用 `ModalityState.any()`——平台卸载会压入 `Unloading of <plugin>` ad-hoc 模态，
 * 其写安全资格取「派发时刻」快照，`any()` 下恒 false，卸载窗口内平台自身的模型改动会记
 * `Write-unsafe context!`（真机日志实锤）。根因链见 [dispatchModalityForUnload] KDoc。
 */
object DynamicInstallSupport {

    private val LOG = Logger.getInstance(DynamicInstallSupport::class.java)

    /**
     * 安装入口：zip 前置校验 → 开发态守卫 → 平台能力探测 → 卸载预检 → EDT 卸载旧版 + 装载新版；
     * 任何失败回退启动脚本路径。全链 runCatching 兜底——崩溃也不劣化 M28 行为。
     */
    fun install(zip: Path): PluginUpdateRunner.InstallResult = runCatching { doInstall(zip) }.getOrElse { error ->
        LOG.warn("dynamic install crashed — falling back to restart script: ${error.message}", error)
        fallbackToRestartScript(zip, "dynamic install crashed: ${error.message}")
    }

    private fun doInstall(zip: Path): PluginUpdateRunner.InstallResult {
        // 1. zip 前置校验（纯函数）：防半截包/错包进入热替换（快速失败，不触平台）
        val artifact = validateArtifact(zip)
        if (artifact is ArtifactCheck.Invalid) {
            return fallbackToRestartScript(zip, "artifact check failed: ${artifact.reason}")
        }
        val version = (artifact as ArtifactCheck.Ok).version

        // 2. 开发态守卫（runIde/测试从 build/classes 加载，卸载/替换会误删构建产物）
        PlatformPluginInstaller.devGuardOrFailure()?.let { return it }

        // 3. 平台能力探测：任一入口缺失（未来版本签名漂移/裁剪发行版）即回退；缺失项全量进日志
        val api = PlatformApis.probe()
        if (api == null) {
            return fallbackToRestartScript(zip, "platform dynamic-install APIs unavailable")
        }

        // 4. 当前描述缺失（未安装/已禁用态）→ 无法 unload 旧版，走脚本路径原样覆盖
        val current = api.findPlugin(UpdateSource.PLUGIN_ID)
        if (current == null) {
            return fallbackToRestartScript(zip, "current plugin descriptor not found")
        }

        // 5. 卸载可行性预检：本插件注册的 EP 全部落在平台 dynamic 点位（DynamicEpComplianceGuardTest 守卫），
        //    此处主要防 vetoer/依赖图否决；Validate 族会给出平台原话，直接进日志（T-DynUpdate263）
        val gate = api.evaluateUnloadGate(current)
        if (!gate.allowed) {
            return fallbackToRestartScript(zip, describeUnloadRefusal(api.unloadGateFamily, gate.reason))
        }

        // 6. 新版描述（仅作 installAndLoadDynamicPlugin 的日志/状态广播参数，见类 KDoc）
        val newDescriptor = api.loadDescriptorFromArtifact(zip)

        // 7. EDT 串行执行：卸载旧版 → 解压并动态装载新版
        val (unloaded, installed) = api.unloadAndInstallOnEdt(current, zip, newDescriptor)
        return if (installed) {
            LOG.info("dynamic install succeeded — plugin hot-reloaded to $version (zip=$zip)")
            PluginUpdateRunner.InstallResult.InstalledDynamic(version)
        } else {
            val reason = if (unloaded) "old version unloaded but dynamic load failed" else "dynamic unload failed"
            fallbackToRestartScript(zip, reason)
        }
    }

    /** 兜底：调度启动脚本替换（M28 路径），失败原因记日志供 idea.log 排查。 */
    private fun fallbackToRestartScript(zip: Path, reason: String): PluginUpdateRunner.InstallResult {
        LOG.info("dynamic install not applicable — falling back to restart script: $reason")
        return PlatformPluginInstaller.installRestartScript(zip)
    }

    /**
     * 把「平台拒绝卸载」翻译成**可查**原因。
     *
     * VALIDATE 族会带平台原话，直接采用；ALLOW/CHECK 族只返回 boolean、平台不给原因字符串——
     * 这时用 [PlatformEpDynamism] 的确定事实补位（非动态 EP + 平台版本分界），再不命中才回落
     * 「平台未给原因 + idea.log 指路」。**不再写「platform gave no reason」了事**：2026-11-06 真机里
     * 平台上一行就写了确切 EP（`is not unload-safe because of extension to non-dynamic EP …`），
     * 我们却把它丢了，定位多绕了一轮。
     */
    private fun describeUnloadRefusal(family: String, platformReason: String?): String {
        val explicit = platformReason?.takeIf { it.isNotBlank() }
        val known = runCatching {
            PlatformEpDynamism.refusalExplanation(ApplicationInfo.getInstance().build.baselineVersion)
        }.getOrNull()
        val head = "dynamic unload rejected by platform (family=$family)"
        return when {
            explicit != null -> "$head: $explicit" + (known?.let { "；另：$it" } ?: "")
            known != null -> "$head: 平台未返回原因，已定位到确定原因——$known" +
                "（平台在同一次 idea.log 里会打 `is not unload-safe because of extension to non-dynamic EP …`）"
            else -> "$head: 平台未返回原因；在 idea.log 里 grep `is not unload-safe` 可拿到确切项" +
                "（本插件声明的 EP 与平台版本分界见 PlatformEpDynamism）"
        }
    }

    // ------------------------------------------------------------------
    // zip 前置校验（纯 java.util.zip，单测直接覆盖）
    // ------------------------------------------------------------------

    /** 校验结果：[Ok] 携带从 plugin.xml 读出的 id/version；[Invalid] 携带面向日志的原因。 */
    internal sealed interface ArtifactCheck {
        data class Ok(val id: String, val version: String) : ArtifactCheck
        data class Invalid(val reason: String) : ArtifactCheck
    }

    /**
     * 校验下载包：zip 可打开、能定位 plugin.xml、`<id>` 与 [UpdateSource.PLUGIN_ID] 一致、
     * `<version>` 非空。plugin.xml 查找顺序：zip 内直属 `META-INF/plugin.xml`（解包态布局）
     * → 内层 lib 目录下插件 jar 的 `META-INF/plugin.xml`（IPGP buildPlugin 布局）。
     *
     * T-UpdateZipBounds：plugin.xml 读取有界（[MAX_DESCRIPTOR_XML_BYTES]，实测 KB 级 ×100
     * 余量）——谎报头的巨量条目不进字符串。
     */
    internal fun validateArtifact(zip: Path): ArtifactCheck = runCatching {
        ZipFile(zip.toFile()).use { zf ->
            val direct = zf.getEntry("META-INF/plugin.xml")
            if (direct != null) {
                return@runCatching checkDescriptorXml(readDescriptorBounded(zf.getInputStream(direct)))
            }
            val libJars = zf.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".jar") && it.name.contains("/lib/") }
                .toList()
            for (entry in libJars) {
                ZipInputStream(zf.getInputStream(entry).buffered()).use { inner ->
                    var next = inner.nextEntry
                    var scanned = 0
                    while (next != null && scanned < MAX_INNER_ENTRIES) {
                        if (next.name.equals("META-INF/plugin.xml", ignoreCase = true)) {
                            return@runCatching checkDescriptorXml(readDescriptorBounded(inner))
                        }
                        next = inner.nextEntry
                        scanned++
                    }
                }
            }
            ArtifactCheck.Invalid("META-INF/plugin.xml not found in artifact (direct or lib/*.jar)")
        }
    }.getOrElse { ArtifactCheck.Invalid("artifact not readable: ${it.message}") }

    /**
     * plugin.xml 有界读取（超限判 Invalid——KB 级文件读出 MB 级即异常包）。
     * internal：PluginPackageOwnership 的 since-build 顺带读取复用同一实现（T-LocalUpdateDir，
     * 只放宽可见性不改语义/签名）。
     */
    internal fun readDescriptorBounded(stream: java.io.InputStream): String {
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var read = 0L
        stream.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                read += n
                if (read > MAX_DESCRIPTOR_XML_BYTES) error("plugin.xml implausibly large (> $MAX_DESCRIPTOR_XML_BYTES bytes)")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray().decodeToString()
    }

    /**
     * plugin.xml 文本 → id/version 校验。
     *
     * T-SAF-05（update.validateArtifact.regex-comment-desync）：此前用
     * `Regex("<tag>(.*?)</tag>", DOTALL)` 在**原始文本**上取首匹配——注释内的 `<id>…</id>`
     * 会抢走首匹配，与平台描述符加载器按真 XML 语义（忽略注释）读到的身份去同步：
     * 门判定的是「注释里的 id」，装进安装链的却是「真 XML 声明的 id」。现改走
     * [cn.song.mybatisplus.visualizer.xml.SecureXml]（本仓 XML 解析安全配置唯一实现：
     * 外部实体/DTD 读取拦死、内部子集膨胀预扫）做**真解析**——注释、CDATA、实体展开
     * 全按 XML 语义处理，id/version 与平台消费者同源。解析失败（非良构 XML）按 Invalid
     * 拒绝：正则时代连非 XML 文本都能提取出「id」，那是更宽的放行面，收紧属 fail closed。
     */
    private fun checkDescriptorXml(xml: String): ArtifactCheck {
        val document = try {
            cn.song.mybatisplus.visualizer.xml.SecureXml.parse(xml)
        } catch (e: Exception) {
            return ArtifactCheck.Invalid("plugin.xml is not well-formed XML: ${e.message}")
        }
        val root = document.documentElement
            ?: return ArtifactCheck.Invalid("plugin.xml has no root element")
        val idValue = root.firstElementText("id")
        val versionValue = root.firstElementText("version")
        return when {
            idValue == null -> ArtifactCheck.Invalid("plugin.xml has no <id>")
            idValue != UpdateSource.PLUGIN_ID ->
                ArtifactCheck.Invalid("plugin.xml id mismatch: expected ${UpdateSource.PLUGIN_ID}, got $idValue")
            versionValue.isNullOrEmpty() -> ArtifactCheck.Invalid("plugin.xml has no <version>")
            else -> ArtifactCheck.Ok(idValue, versionValue)
        }
    }

    /** 第一个同名子元素的规范化文本（注释不参与；无该元素返回 null）。 */
    private fun org.w3c.dom.Element.firstElementText(tagName: String): String? =
        getElementsByTagName(tagName).item(0)?.textContent?.trim()


    /** lib 目录下插件 jar 内嵌描述符扫描的条目上限（internal：PluginPackageOwnership 同序扫描复用）。 */
    internal const val MAX_INNER_ENTRIES = 2000

    /** plugin.xml 读取上限（实测 KB 级，×100 余量；超限即异常包）。 */
    internal const val MAX_DESCRIPTOR_XML_BYTES = 1024 * 1024

    // ------------------------------------------------------------------
    // 平台反射门面（F0 实证的五入口；探测一次缓存，缺失整体降级）
    // ------------------------------------------------------------------

    /** 入口齐备才可用（卸载预检按能力族，三族任一即可）；任一缺失 = 平台签名漂移/裁剪发行版 → 整体回退脚本路径。 */
    internal class PlatformApi internal constructor(
        private val dynamicPlugins: Any,
        /** 卸载可行性预检入口（能力族，T-DynUpdate263）。 */
        private val unloadGate: UnloadGate,
        private val unloadPluginMethod: Method,
        /** 双参 unloadPlugin 形态时的默认 UnloadPluginOptions 实例（单参形态为 null）。 */
        private val unloadPluginOptions: Any?,
        private val installAndLoadMethod: Method,
        private val loadFromArtifactMethod: Method,
        private val findPluginMethod: Method,
    ) {
        /** 命中的预检能力族（validate/check/legacy），供日志取证。 */
        val unloadGateFamily: String get() = unloadGate.family.id

        /** 当前已装插件描述（未安装/描述缺失返回 null）。 */
        fun findPlugin(pluginId: String): Any? = runCatching {
            invokePlatform(findPluginMethod, null, PluginId.getId(pluginId))
        }.getOrElse {
            LOG.warn("findPlugin reflective call failed: ${it.message}")
            null
        }

        /** 卸载可行性预检；[GateDecision.reason] 是平台原话（Validate 族才有，其余族为 null）。 */
        fun evaluateUnloadGate(currentDescriptor: Any): GateDecision =
            unloadGate.evaluate(dynamicPlugins, currentDescriptor)

        /** 从发行 zip 读新版描述（失败返回 null——不阻断流程，装载仍可进行）。 */
        fun loadDescriptorFromArtifact(zip: Path): Any? = runCatching {
            invokePlatform(loadFromArtifactMethod, null, zip, null as BuildNumber?)
        }.getOrElse {
            LOG.warn("loadDescriptorFromArtifact reflective call failed: ${it.message}")
            null
        }

        /**
         * EDT 串行：卸载旧版 → 解压并动态装载新版。返回 (旧版是否卸载成功, 新版是否装载成功)。
         * 卸载失败则不尝试装载（插件保持运行原版）；卸载成功而装载失败由调用方回退脚本路径修复。
         *
         * T-DynUpdateWriteSafe：派发模态走 [dispatchModalityForUnload]（写安全），不再用 `any()`
         * ——平台卸载会在该模态下压入 `Unloading of <plugin>` 实体，窗口内平台自身的 commit/落盘/
         * dispose 都按这个模态的写安全资格判定（根因与源码链见 [dispatchModalityForUnload] KDoc）。
         */
        fun unloadAndInstallOnEdt(currentDescriptor: Any, zip: Path, newDescriptor: Any?): Pair<Boolean, Boolean> {
            var unloaded = false
            var installed = false
            val modality = dispatchModalityForUnload()
            LOG.info(
                "dynamic update: dispatch modality=$modality writeSafe=${isWriteSafeDispatch(modality)}" +
                    " (T-DynUpdateWriteSafe)",
            )
            edtDispatcher(modality) {
                unloaded = runCatching {
                    val args = if (unloadPluginOptions != null) {
                        arrayOf(currentDescriptor, unloadPluginOptions)
                    } else {
                        arrayOf(currentDescriptor)
                    }
                    invokePlatform(unloadPluginMethod, dynamicPlugins, *args) as Boolean
                }.getOrElse {
                    LOG.warn("unloadPlugin reflective call failed: ${it.message}")
                    false
                }
                LOG.info("dynamic update: old version unloaded=$unloaded")
                if (unloaded && newDescriptor != null) {
                    installed = runCatching {
                        invokePlatform(installAndLoadMethod, null, zip, null as JComponent?, newDescriptor) as Boolean
                    }.getOrElse {
                        LOG.warn("installAndLoadDynamicPlugin reflective call failed: ${it.message}")
                        false
                    }
                    LOG.info("dynamic update: new version installed=$installed")
                }
            }
            return unloaded to installed
        }
    }

    // ------------------------------------------------------------------
    // 卸载/装载的派发模态（T-DynUpdateWriteSafe）
    // ------------------------------------------------------------------

    /**
     * 卸载/装载派发模态：在 EDT 上读**当前模态实例**并交给 [chooseDispatchModality] 决策。
     *
     * 为什么不能再用 `ModalityState.any()`（T-DynUpdateWriteSafe 根因，262 源码实证）：
     * 写模型/落盘/PSI commit 的合法性由「这次 EDT 活动是否 write-safe」决定，判定读的是
     * `TransactionGuardImpl.myWriteSafeModalities[模态实例]`，该表在**模态进入时快照**
     * `myWritingAllowed`（`LaterInvocator.enterModal:204-207` → `enteredModality:199-201`）。
     * 平台卸载会压入一个 ad-hoc 模态（`DynamicPluginsLegacyImpl:509` 的
     * `inModalContext(sentinel("Unloading of <plugin>"))`），其资格就是**派发时刻**的快照；
     * `any()` 排出的是 posted runnable，一律走 `performActivity(false, …)`
     * （`IdeEventQueue:1106-1122` 只把输入/Item/Focus 事件算用户活动），`wrapLaterInvocation`
     * 对非写安全模态也不套 `runWithWritingAllowed`（`TransactionGuardImpl:203-220`）→ 快照恒 false
     * → 卸载窗口内平台自身的 commit/落盘/dispose 全部记 `Write-unsafe context!`（真机日志模态串
     * `[设置对话框, Unloading of SQL Visualizer]` 即此形态）。
     *
     * `ModalityState.current()` 是 `@RequiresEdt`，而本函数从后台任务线程调用，故先用一次只读跳板
     * 把它取回 EDT（回调体只读模态，无任何模型改动）。
     */
    internal fun dispatchModalityForUnload(): ModalityState {
        val app = ApplicationManager.getApplication() ?: return ModalityState.nonModal()
        val captured = AtomicReference<ModalityState?>()
        // write-safe: UI-only（只读当前模态，无文档/模型写；any() 保证模态弹窗存续期跳板照常执行）
        runCatching { app.invokeAndWait({ captured.set(ModalityState.current()) }, ModalityState.any()) }
        return chooseDispatchModality(captured.get())
    }

    /**
     * 派发模态决策（纯函数，单测直覆盖）：写安全 → 原样用；取不到或非写安全 → `nonModal`。
     *
     * 取「当前模态实例」而非别的形态的理由：`ModalityStateEx.accepts` 的 trivial 分支
     * （`requested == this`）保证它**立即执行**，不会被模态弹窗推迟——设置页点安装时正是这个场景。
     * 非写安全时退 `nonModal` 而不是硬上：`nonModal` 恒 write-safe，是平台文档（
     * `TransactionGuard` 类注释「When in doubt, use ModalityState#nonModal()」）与仓内
     * `DocWriteContext` 的既有口径（延迟到弹窗关闭是正确语义，非退化）。
     */
    internal fun chooseDispatchModality(captured: ModalityState?): ModalityState =
        if (captured != null && isWriteSafeDispatch(captured)) captured else ModalityState.nonModal()

    /** 模态写安全判定（平台谓词直读，供 [chooseDispatchModality] 与派发日志取证；异常即 false）。 */
    internal fun isWriteSafeDispatch(modality: ModalityState): Boolean =
        runCatching { TransactionGuard.getInstance().isWriteSafeModality(modality) }.getOrDefault(false)

    /** 测试缝：EDT 派发器（默认平台 `invokeAndWait`；测试注入以观察实际派发模态）。 */
    internal var edtDispatcher: (ModalityState, Runnable) -> Unit = { modality, body ->
        ApplicationManager.getApplication().invokeAndWait(body, modality)
    }

    internal object PlatformApis {

        @Volatile
        private var cached: PlatformApi? = null

        @Volatile
        private var probed = false

        /** 探测入口（首次后缓存）；任一缺失返回 null——缺失项全量列进日志（T-DynUpdate263）。 */
        fun probe(): PlatformApi? {
            if (probed) return cached
            synchronized(this) {
                if (!probed) {
                    cached = runCatching { buildApi() }.onFailure {
                        LOG.info("dynamic-install platform APIs unavailable: ${it.message}")
                    }.getOrNull()
                    cached?.let { LOG.info("dynamic-install api family=${it.unloadGateFamily}") }
                    probed = true
                }
                return cached
            }
        }

        private fun buildApi(): PlatformApi {
            val loader = DynamicInstallSupport::class.java.classLoader
            val dpClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins", true, loader)
            val dynamicPlugins = dpClass.getField("INSTANCE").get(null)
            val descriptorClass = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl", true, loader)
            val installerClass = Class.forName("com.intellij.ide.plugins.PluginInstaller", true, loader)
            val descriptorLoaderClass = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader", true, loader)
            val coreClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader)

            // 缺失项累计后一次性报：原先首个缺失即抛，用户日志里只看到一个名字（263 漂移时就只报了旧入口）
            val missing = mutableListOf<String>()

            fun method(clazz: Class<*>, name: String, vararg params: Class<*>): Method? =
                clazz.methods.filter { it.name == name && it.parameterTypes.contentEquals(params) }
                    .minByOrNull { if (java.lang.reflect.Modifier.isStatic(it.modifiers)) 0 else 1 }
                    ?.also { it.isAccessible = true }
                    ?: run {
                        missing += "$name(${params.joinToString { it.simpleName }})"
                        null
                    }

            // unloadPlugin 首参类型跨版本漂移：243=IdeaPluginDescriptorImpl、262=其子类
            // PluginMainDescriptor（262 反编译实证）——精确匹配会探测失败回退重启（实测）。
            // 弹性匹配：首参与描述类互为可赋值即可，优先最少参数；双参形态构造 options 无参实例。
            val unload = resolveMethodByFirstParam(dpClass, "unloadPlugin", descriptorClass)
                ?.also { it.isAccessible = true }
                ?: run {
                    missing += "unloadPlugin(<descriptor>)"
                    null
                }
            val unloadOptions = unload?.let { candidate ->
                if (candidate.parameterTypes.size >= 2) {
                    runCatching {
                        candidate.parameterTypes[1].getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                    }.getOrElse {
                        missing += "unloadPlugin options no-arg ctor (${it.message})"
                        null
                    }
                } else {
                    null
                }
            }

            // 卸载预检能力族（T-DynUpdate263）：263 移除 allowLoadUnloadWithoutRestart，
            // 262/263 改用 validate/check——三族全缺才判平台失去动态卸载能力
            val unloadGate = resolveUnloadGate(dpClass, descriptorClass)
            if (unloadGate == null) {
                missing += "unload gate (validateCanUnloadWithoutRestart|checkCanUnloadWithoutRestart|" +
                    "allowLoadUnloadWithoutRestart)"
            }

            val installAndLoad = method(
                installerClass,
                "installAndLoadDynamicPlugin",
                Path::class.java,
                JComponent::class.java,
                descriptorClass,
            )
            val loadFromArtifact = method(
                descriptorLoaderClass,
                "loadDescriptorFromArtifact",
                Path::class.java,
                BuildNumber::class.java,
            )
            val findPlugin = method(coreClass, "findPlugin", PluginId::class.java)

            if (missing.isNotEmpty()) {
                error("missing platform entries: ${missing.joinToString(", ")}")
            }

            return PlatformApi(
                dynamicPlugins = dynamicPlugins,
                unloadGate = requireNotNull(unloadGate) { "unload gate unresolved" },
                unloadPluginMethod = requireNotNull(unload) { "unloadPlugin unresolved" },
                unloadPluginOptions = unloadOptions,
                installAndLoadMethod = requireNotNull(installAndLoad) { "installAndLoadDynamicPlugin unresolved" },
                loadFromArtifactMethod = requireNotNull(loadFromArtifact) { "loadDescriptorFromArtifact unresolved" },
                findPluginMethod = requireNotNull(findPlugin) { "findPlugin unresolved" },
            )
        }
    }

    /**
     * 按方法名 + 首参兼容性解析（纯函数，单测直接覆盖）：首参与 [firstParamBase]
     * 互为可赋值（任一方向）即命中，优先参数最少者。unloadPlugin 的参数类型在
     * 243/262 间漂移（IdeaPluginDescriptorImpl → PluginMainDescriptor 子类），
     * 精确签名匹配在 262 实测探测失败。
     */
    internal fun resolveMethodByFirstParam(clazz: Class<*>, name: String, firstParamBase: Class<*>): Method? =
        clazz.methods
            .filter {
                it.name == name && it.parameterTypes.isNotEmpty() &&
                    (firstParamBase.isAssignableFrom(it.parameterTypes[0]) ||
                        it.parameterTypes[0].isAssignableFrom(firstParamBase))
            }
            .minByOrNull { it.parameterTypes.size }

    // ------------------------------------------------------------------
    // 卸载预检能力族（T-DynUpdate263：263 移除了 allowLoadUnloadWithoutRestart）
    // ------------------------------------------------------------------

    /** 卸载预检能力族；[id] 直接进日志，便于跨版本排查命中的是哪一条。 */
    internal enum class UnloadGateFamily(val id: String) {
        /**
         * 返回 String：null/空白=可卸载，非空=不可卸载原因。
         * 263 是 `validateCanUnloadWithoutRestart`；243 的 `checkCanUnloadWithoutRestart` 同名但同样返回 String。
         */
        VALIDATE("validate"),

        /** 返回 boolean 的 `checkCanUnloadWithoutRestart`（262/263）。 */
        CHECK("check"),

        /** 返回 boolean 的旧入口 `allowLoadUnloadWithoutRestart`（243/262）。 */
        LEGACY("legacy"),
    }

    /** 预检结论：[reason] 是平台原话，仅 Validate 族有（其余族平台只给布尔）。 */
    internal class GateDecision(val allowed: Boolean, val reason: String?) {
        override fun toString(): String = if (allowed) "allowed" else "rejected(${reason ?: "no reason"})"
    }

    /** 卸载预检入口：族 + 已解析方法；[evaluate] 把三族语义收敛成同一个结论。 */
    internal class UnloadGate(val family: UnloadGateFamily, val method: Method) {
        fun evaluate(receiver: Any?, descriptor: Any): GateDecision = runCatching {
            val raw = invokePlatform(method, receiver, descriptor)
            if (family == UnloadGateFamily.VALIDATE) {
                val reason = raw as? String
                GateDecision(reason.isNullOrBlank(), reason?.takeIf { it.isNotBlank() })
            } else {
                GateDecision(raw as Boolean, null)
            }
        }.getOrElse {
            GateDecision(false, "reflective call failed: ${it.message}")
        }
    }

    /**
     * 解析卸载预检入口（纯函数，单测直覆盖）：按「方法名 + 返回类型」绑能力族，
     * 优先级 **Legacy > Validate > Check**。
     *
     * 为什么旧入口优先：243/262 上必须与本次改动前**逐字同一条路径**——
     * 243 的 `allowLoadUnloadWithoutRestart` 源码就是 `checkCanUnloadWithoutRestart(...) == null`
     * 的布尔包装；262 的旧入口走 `validateDynamicReconfigurationPossible(newState)`（整组重配置预演），
     * 与单插件的 `validateCanUnloadWithoutRestart`（`pretendDisabled` 单点校验）不是同一套判定。
     * 老版本维持旧入口，只有 263（旧入口已删除）才落到新族，行为差异被限制在「原本就不工作」的那一版。
     *
     * 为什么必须看返回类型：243 的 `checkCanUnloadWithoutRestart(<descriptor>)` 返回 **String**
     * （不可卸载原因，null=可卸载），262/263 同名方法返回 boolean。只按名字匹配会把 243 的原因串
     * 当布尔用（ClassCastException → 一律判不可卸载 → 永远回退重启）。
     */
    internal fun resolveUnloadGate(clazz: Class<*>, descriptorClass: Class<*>): UnloadGate? {
        fun gate(family: UnloadGateFamily, method: Method?): UnloadGate? =
            method?.also { it.isAccessible = true }?.let { UnloadGate(family, it) }

        val validate = resolveMethodByFirstParam(clazz, "validateCanUnloadWithoutRestart", descriptorClass)
            ?.takeIf { it.returnType == String::class.java }
        val checkAsReason = resolveMethodByFirstParam(clazz, "checkCanUnloadWithoutRestart", descriptorClass)
            ?.takeIf { it.returnType == String::class.java }
        val checkAsBoolean = resolveMethodByFirstParam(clazz, "checkCanUnloadWithoutRestart", descriptorClass)
            ?.takeIf { it.returnType == Boolean::class.javaPrimitiveType }
        val legacy = resolveMethodByFirstParam(clazz, "allowLoadUnloadWithoutRestart", descriptorClass)
            ?.takeIf { it.returnType == Boolean::class.javaPrimitiveType }

        return gate(UnloadGateFamily.LEGACY, legacy)
            ?: gate(UnloadGateFamily.VALIDATE, validate)
            ?: gate(UnloadGateFamily.VALIDATE, checkAsReason)
            ?: gate(UnloadGateFamily.CHECK, checkAsBoolean)
    }

    /** 反射调用统一入口：静态方法 null receiver，实例方法传对象单例。 */
    internal fun invokePlatform(method: Method, receiver: Any?, vararg args: Any?): Any? =
        if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
            method.invoke(null, *args)
        } else {
            method.invoke(receiver, *args)
        }
}
