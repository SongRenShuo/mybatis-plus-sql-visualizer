package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.BuildNumber;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.swing.JComponent;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

/**
 * 动态安装门面（T-DynUpdate）：下载包先尝试平台动态热替换——卸载旧版 → 解压新版 → 动态装载，
 * 任一步不满足或失败都回退 [PlatformPluginInstaller.installRestartScript] 启动脚本路径
 * （重启生效，M28 原行为），全程无中间死态。
 *
 * <p>平台 API 全走反射探测（F0 javap 243 + 反编译 262 双端实证，签名与语义一致）：
 * <ul>
 * <li>{@code PluginDescriptorLoader.loadDescriptorFromArtifact(Path, BuildNumber)}：从发行 zip 读新版描述（两平台 public）；</li>
 * <li>{@code DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor)}：卸载可行性预检——EP dynamic 属性
 * （本插件注册的 EP 全部落在平台声明 {@code dynamic="true"} 的点位上，当前 57 个注册 0 违规；
 * 由 {@code DynamicEpComplianceGuardTest} 守卫，新增 EP 若落在非 dynamic 点位即红）、
 * {@code ide.dynamicPluginVetoer}、依赖插件图（两平台 public）；</li>
 * <li>{@code DynamicPlugins.unloadPlugin(descriptor)}：运行期卸载旧版（两平台 public，内部走 EDT write action）；</li>
 * <li>{@code PluginInstaller.installAndLoadDynamicPlugin(Path, JComponent?, descriptor)}：解包 + 动态装载新版
 * （两平台 public；双端反编译实证 descriptor 参数仅用于进度标题/日志/fireState，解包布局由 zip
 * 顶层目录名决定，传新版描述即可）；</li>
 * <li>{@code PluginManagerCore.findPlugin(PluginId)}：取当前已装描述（两平台 public）。</li>
 * </ul>
 *
 * <p>installFromDisk 复用路线 F0 否决：243 包私有 + {@code File} 参数 + 6 参，262 public + {@code Path} + 7 参，
 * 且两条平台流程都不卸载已加载旧版（{@code installAndLoadDynamicPlugin} 在旧版仍加载时直接返回 false），
 * 热更新必失败——故手工编排 unload → install 序列。
 *
 * <p>自更新的特殊处境：[install] 的调用栈运行在将被卸载的旧 classloader 上——卸载/装载回调内只触达
 * 平台 API 与纯函数，返回后由调用方（UI 层）经平台通知报告结果，不再触达自身服务。
 *
 * <p>派发模态（T-DynUpdateWriteSafe，2026-09-22）：卸载/装载一律以 {@link #dispatchModalityForUnload}
 * 解析出的写安全模态派发，禁用 {@code ModalityState.any()}——平台卸载会压入
 * {@code Unloading of <plugin>} ad-hoc 模态，其写安全资格取「派发时刻」快照，{@code any()} 下恒 false，
 * 卸载窗口内平台自身的模型改动会记 {@code Write-unsafe context!}（真机日志实锤）。
 * 根因链见 {@link #dispatchModalityForUnload} KDoc。
 */
public final class DynamicInstallSupport {

    public static final DynamicInstallSupport INSTANCE = new DynamicInstallSupport();
    private DynamicInstallSupport() {}

    private static final Logger LOG = Logger.getInstance(DynamicInstallSupport.class);

    /**
     * 安装入口：zip 前置校验 → 开发态守卫 → 平台能力探测 → 卸载预检 → EDT 卸载旧版 + 装载新版；
     * 任何失败回退启动脚本路径。全链 runCatching 兜底——崩溃也不劣化 M28 行为。
     */
    public static PluginUpdateRunner.InstallResult install(Path zip) {
        try {
            return doInstall(zip);
        } catch (Throwable error) {
            LOG.warn("dynamic install crashed — falling back to restart script: " + error.getMessage(), error);
            return fallbackToRestartScript(zip, "dynamic install crashed: " + error.getMessage());
        }
    }

    private static PluginUpdateRunner.InstallResult doInstall(Path zip) {
        // 1. zip 前置校验（纯函数）：防半截包/错包进入热替换（快速失败，不触平台）
        ArtifactCheck artifact = validateArtifact(zip);
        if (artifact instanceof ArtifactCheck.Invalid invalid) {
            return fallbackToRestartScript(zip, "artifact check failed: " + invalid.reason());
        }
        String version = ((ArtifactCheck.Ok) artifact).version();

        // 2. 开发态守卫（runIde/测试从 build/classes 加载，卸载/替换会误删构建产物）
        PluginUpdateRunner.InstallResult.Failed guard = PlatformPluginInstaller.devGuardOrFailure();
        if (guard != null) return guard;

        // 3. 平台能力探测：五个反射入口任一缺失（未来版本签名漂移/裁剪发行版）即回退
        PlatformApi api = PlatformApis.probe();
        if (api == null) {
            return fallbackToRestartScript(zip, "platform dynamic-install APIs unavailable");
        }

        // 4. 当前描述缺失（未安装/已禁用态）→ 无法 unload 旧版，走脚本路径原样覆盖
        Object current = api.findPlugin(UpdateSource.PLUGIN_ID);
        if (current == null) {
            return fallbackToRestartScript(zip, "current plugin descriptor not found");
        }

        // 5. 卸载可行性预检：本插件注册的 EP 全部落在平台 dynamic 点位（DynamicEpComplianceGuardTest 守卫），
        //    此处主要防 vetoer/依赖图否决
        if (!api.allowLoadUnload(current)) {
            return fallbackToRestartScript(zip, "dynamic unload rejected by platform (vetoer/EP/dependency)");
        }

        // 6. 新版描述（仅作 installAndLoadDynamicPlugin 的日志/状态广播参数，见类 KDoc）
        Object newDescriptor = api.loadDescriptorFromArtifact(zip);

        // 7. EDT 串行执行：卸载旧版 → 解压并动态装载新版
        UnloadInstallResult outcome = api.unloadAndInstallOnEdt(current, zip, newDescriptor);
        if (outcome.installed()) {
            LOG.info("dynamic install succeeded — plugin hot-reloaded to " + version + " (zip=" + zip + ")");
            return new PluginUpdateRunner.InstallResult.InstalledDynamic(version);
        }
        String reason = outcome.unloaded() ? "old version unloaded but dynamic load failed" : "dynamic unload failed";
        return fallbackToRestartScript(zip, reason);
    }

    /** 卸载/装载二态（Kotlin Pair&lt;Boolean, Boolean&gt; 等价，命名可读）。 */
    private record UnloadInstallResult(boolean unloaded, boolean installed) {}

    /** 兜底：调度启动脚本替换（M28 路径），失败原因记日志供 idea.log 排查。 */
    private static PluginUpdateRunner.InstallResult fallbackToRestartScript(Path zip, String reason) {
        LOG.info("dynamic install not applicable — falling back to restart script: " + reason);
        return PlatformPluginInstaller.installRestartScript(zip);
    }

    // ------------------------------------------------------------------
    // zip 前置校验（纯 java.util.zip，单测直接覆盖）
    // ------------------------------------------------------------------

    /** 校验结果：Ok 携带从 plugin.xml 读出的 id/version；Invalid 携带面向日志的原因。 */
    sealed interface ArtifactCheck {
        record Ok(String id, String version) implements ArtifactCheck {}

        record Invalid(String reason) implements ArtifactCheck {}
    }

    /**
     * 校验下载包：zip 可打开、能定位 plugin.xml、{@code <id>} 与 [UpdateSource.PLUGIN_ID] 一致、
     * {@code <version>} 非空。plugin.xml 查找顺序：zip 内直属 {@code META-INF/plugin.xml}（解包态布局）
     * → 内层 lib 目录下插件 jar 的 {@code META-INF/plugin.xml}（IPGP buildPlugin 布局）。
     *
     * <p>T-UpdateZipBounds：plugin.xml 读取有界（{@link #MAX_DESCRIPTOR_XML_BYTES}，实测 KB 级 ×100
     * 余量）——谎报头的巨量条目不进字符串。
     */
    static ArtifactCheck validateArtifact(Path zip) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry direct = zf.getEntry("META-INF/plugin.xml");
            if (direct != null) {
                return checkDescriptorXml(readDescriptorBounded(zf.getInputStream(direct)));
            }
            List<ZipEntry> collected = new ArrayList<>();
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".jar") && e.getName().contains("/lib/")) {
                    collected.add(e);
                }
            }
            for (ZipEntry entry : collected) {
                try (ZipInputStream inner = new ZipInputStream(
                        new java.io.BufferedInputStream(zf.getInputStream(entry)))) {
                    ZipEntry next = inner.getNextEntry();
                    int scanned = 0;
                    while (next != null && scanned < MAX_INNER_ENTRIES) {
                        if (next.getName().equalsIgnoreCase("META-INF/plugin.xml")) {
                            return checkDescriptorXml(readDescriptorBounded(inner));
                        }
                        next = inner.getNextEntry();
                        scanned++;
                    }
                }
            }
            return new ArtifactCheck.Invalid("META-INF/plugin.xml not found in artifact (direct or lib/*.jar)");
        } catch (Throwable t) {
            return new ArtifactCheck.Invalid("artifact not readable: " + t.getMessage());
        }
    }

    /** plugin.xml 有界读取（超限判 Invalid——KB 级文件读出 MB 级即异常包）。 */
    private static String readDescriptorBounded(java.io.InputStream stream) {
        byte[] buf = new byte[64 * 1024];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long read = 0L;
        try (java.io.InputStream it = stream) {
            while (true) {
                int n = it.read(buf);
                if (n < 0) break;
                read += n;
                if (read > MAX_DESCRIPTOR_XML_BYTES) {
                    throw new IllegalStateException("plugin.xml implausibly large (> " + MAX_DESCRIPTOR_XML_BYTES + " bytes)");
                }
                out.write(buf, 0, n);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** plugin.xml 文本 → id/version 校验（regex 提取，无 XML 依赖）。 */
    private static ArtifactCheck checkDescriptorXml(String xml) {
        String idValue = trimOrNull(extractTag(xml, "id"));
        String versionValue = trimOrNull(extractTag(xml, "version"));
        if (idValue == null) {
            return new ArtifactCheck.Invalid("plugin.xml has no <id>");
        }
        if (!idValue.equals(UpdateSource.PLUGIN_ID)) {
            return new ArtifactCheck.Invalid("plugin.xml id mismatch: expected " + UpdateSource.PLUGIN_ID + ", got " + idValue);
        }
        if (versionValue == null || versionValue.isEmpty()) {
            return new ArtifactCheck.Invalid("plugin.xml has no <version>");
        }
        return new ArtifactCheck.Ok(idValue, versionValue);
    }

    /** 提取首个 {@code <tag>…</tag>} 文本（DOTALL，容忍跨行）。 */
    private static String extractTag(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        return s.trim();
    }

    private static final int MAX_INNER_ENTRIES = 2000;

    /** plugin.xml 读取上限（实测 KB 级，×100 余量；超限即异常包）。 */
    public static final int MAX_DESCRIPTOR_XML_BYTES = 1024 * 1024;

    // ------------------------------------------------------------------
    // 平台反射门面（F0 实证的五入口；探测一次缓存，缺失整体降级）
    // ------------------------------------------------------------------

    /** 五入口齐备才可用；任一缺失 = 平台签名漂移/裁剪发行版 → 整体回退脚本路径。 */
    static final class PlatformApi {

        private final Object dynamicPlugins;
        private final Method allowLoadUnloadMethod;
        private final Method unloadPluginMethod;
        /** 双参 unloadPlugin 形态时的默认 UnloadPluginOptions 实例（单参形态为 null）。 */
        private final Object unloadPluginOptions;
        private final Method installAndLoadMethod;
        private final Method loadFromArtifactMethod;
        private final Method findPluginMethod;

        PlatformApi(
            Object dynamicPlugins,
            Method allowLoadUnloadMethod,
            Method unloadPluginMethod,
            Object unloadPluginOptions,
            Method installAndLoadMethod,
            Method loadFromArtifactMethod,
            Method findPluginMethod
        ) {
            this.dynamicPlugins = dynamicPlugins;
            this.allowLoadUnloadMethod = allowLoadUnloadMethod;
            this.unloadPluginMethod = unloadPluginMethod;
            this.unloadPluginOptions = unloadPluginOptions;
            this.installAndLoadMethod = installAndLoadMethod;
            this.loadFromArtifactMethod = loadFromArtifactMethod;
            this.findPluginMethod = findPluginMethod;
        }

        /** 当前已装插件描述（未安装/描述缺失返回 null）。 */
        Object findPlugin(String pluginId) {
            try {
                return invoke(findPluginMethod, null, PluginId.getId(pluginId));
            } catch (Throwable t) {
                LOG.warn("findPlugin reflective call failed: " + t.getMessage());
                return null;
            }
        }

        /** 卸载可行性预检（平台否决返回 false）。 */
        boolean allowLoadUnload(Object currentDescriptor) {
            try {
                return (Boolean) invoke(allowLoadUnloadMethod, dynamicPlugins, currentDescriptor);
            } catch (Throwable t) {
                LOG.warn("allowLoadUnloadWithoutRestart reflective call failed: " + t.getMessage());
                return false;
            }
        }

        /** 从发行 zip 读新版描述（失败返回 null——不阻断流程，装载仍可进行）。 */
        Object loadDescriptorFromArtifact(Path zip) {
            try {
                return invoke(loadFromArtifactMethod, null, zip, null);
            } catch (Throwable t) {
                LOG.warn("loadDescriptorFromArtifact reflective call failed: " + t.getMessage());
                return null;
            }
        }

        /**
         * EDT 串行：卸载旧版 → 解压并动态装载新版。返回 (旧版是否卸载成功, 新版是否装载成功)。
         * 卸载失败则不尝试装载（插件保持运行原版）；卸载成功而装载失败由调用方回退脚本路径修复。
         *
         * <p>T-DynUpdateWriteSafe：派发模态走
         * {@link DynamicInstallSupport#dispatchModalityForUnload}（写安全），不再用 {@code any()}
         * ——平台卸载会在该模态下压入 {@code Unloading of <plugin>} 实体，窗口内平台自身的
         * commit/落盘/dispose 都按这个模态的写安全资格判定（根因与源码链见
         * {@link DynamicInstallSupport#dispatchModalityForUnload} KDoc）。
         */
        UnloadInstallResult unloadAndInstallOnEdt(Object currentDescriptor, Path zip, Object newDescriptor) {
            boolean[] unloaded = {false};
            boolean[] installed = {false};
            ModalityState modality = DynamicInstallSupport.dispatchModalityForUnload();
            LOG.info(
                "dynamic update: dispatch modality=" + modality
                    + " writeSafe=" + DynamicInstallSupport.isWriteSafeDispatch(modality)
                    + " (T-DynUpdateWriteSafe)");
            DynamicInstallSupport.edtDispatcher.invoke(modality, () -> {
                try {
                    Object[] args = unloadPluginOptions != null
                        ? new Object[]{currentDescriptor, unloadPluginOptions}
                        : new Object[]{currentDescriptor};
                    unloaded[0] = (Boolean) invoke(unloadPluginMethod, dynamicPlugins, args);
                } catch (Throwable t) {
                    LOG.warn("unloadPlugin reflective call failed: " + t.getMessage());
                    unloaded[0] = false;
                }
                LOG.info("dynamic update: old version unloaded=" + unloaded[0]);
                if (unloaded[0] && newDescriptor != null) {
                    try {
                        installed[0] = (Boolean) invoke(installAndLoadMethod, null, zip, null, newDescriptor);
                    } catch (Throwable t) {
                        LOG.warn("installAndLoadDynamicPlugin reflective call failed: " + t.getMessage());
                        installed[0] = false;
                    }
                    LOG.info("dynamic update: new version installed=" + installed[0]);
                }
            });
            return new UnloadInstallResult(unloaded[0], installed[0]);
        }

        /** 静态/实例双形态统一调用：静态方法 null receiver，实例方法传对象单例。 */
        private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
            if (Modifier.isStatic(method.getModifiers())) {
                return method.invoke(null, args);
            }
            return method.invoke(receiver, args);
        }
    }

    // ------------------------------------------------------------------
    // 卸载/装载的派发模态（T-DynUpdateWriteSafe）
    // ------------------------------------------------------------------

    /**
     * 卸载/装载派发模态：在 EDT 上读<strong>当前模态实例</strong>并交给 {@link #chooseDispatchModality} 决策。
     *
     * <p>为什么不能再用 {@code ModalityState.any()}（T-DynUpdateWriteSafe 根因，262 源码实证）：
     * 写模型/落盘/PSI commit 的合法性由「这次 EDT 活动是否 write-safe」决定，判定读的是
     * {@code TransactionGuardImpl.myWriteSafeModalities[模态实例]}，该表在<strong>模态进入时快照</strong>
     * {@code myWritingAllowed}（{@code LaterInvocator.enterModal:204-207} → {@code enteredModality:199-201}）。
     * 平台卸载会压入一个 ad-hoc 模态（{@code DynamicPluginsLegacyImpl:509} 的
     * {@code inModalContext(sentinel("Unloading of <plugin>"))}），其资格就是<strong>派发时刻</strong>的快照；
     * {@code any()} 排出的是 posted runnable，一律走 {@code performActivity(false, …)}
     * （{@code IdeEventQueue:1106-1122} 只把输入/Item/Focus 事件算用户活动），{@code wrapLaterInvocation}
     * 对非写安全模态也不套 {@code runWithWritingAllowed}（{@code TransactionGuardImpl:203-220}）→ 快照恒 false
     * → 卸载窗口内平台自身的 commit/落盘/dispose 全部记 {@code Write-unsafe context!}（真机日志模态串
     * {@code [设置对话框, Unloading of SQL Visualizer]} 即此形态）。
     *
     * <p>{@code ModalityState.current()} 是 {@code @RequiresEdt}，而本函数从后台任务线程调用，
     * 故先用一次只读跳板把它取回 EDT（回调体只读模态，无任何模型改动）。
     */
    static ModalityState dispatchModalityForUnload() {
        Application app = ApplicationManager.getApplication();
        if (app == null) return ModalityState.nonModal();
        AtomicReference<ModalityState> captured = new AtomicReference<>();
        try {
            // write-safe: UI-only（只读当前模态，无文档/模型写；any() 保证模态弹窗存续期跳板照常执行）
            app.invokeAndWait(() -> captured.set(ModalityState.current()), ModalityState.any());
        } catch (Throwable ignored) {
            // runCatching 等价：取不到当前模态，交给 chooseDispatchModality 的 nonModal 退化
        }
        return chooseDispatchModality(captured.get());
    }

    /**
     * 派发模态决策（纯函数，单测直覆盖）：写安全 → 原样用；取不到或非写安全 → {@code nonModal}。
     *
     * <p>取「当前模态实例」而非别的形态的理由：{@code ModalityStateEx.accepts} 的 trivial 分支
     * （{@code requested == this}）保证它<strong>立即执行</strong>，不会被模态弹窗推迟——设置页点安装时
     * 正是这个场景。非写安全时退 {@code nonModal} 而不是硬上：{@code nonModal} 恒 write-safe，
     * 是平台文档（{@code TransactionGuard} 类注释「When in doubt, use ModalityState#nonModal()」）
     * 与仓内 {@code DocWriteContext} 的既有口径（延迟到弹窗关闭是正确语义，非退化）。
     */
    static ModalityState chooseDispatchModality(ModalityState captured) {
        return captured != null && isWriteSafeDispatch(captured) ? captured : ModalityState.nonModal();
    }

    /** 模态写安全判定（平台谓词直读，供 {@link #chooseDispatchModality} 与派发日志取证；异常即 false）。 */
    static boolean isWriteSafeDispatch(ModalityState modality) {
        try {
            return TransactionGuard.getInstance().isWriteSafeModality(modality);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 测试缝：EDT 派发器（默认平台 {@code invokeAndWait}；测试注入以观察实际派发模态）。 */
    static Function2<ModalityState, Runnable, Unit> edtDispatcher =
        (modality, body) -> {
            ApplicationManager.getApplication().invokeAndWait(body, modality);
            return Unit.INSTANCE;
        };

    /** 五入口反射探测与缓存（Kotlin internal object → 同包静态工具形态）。 */
    static final class PlatformApis {

        private PlatformApis() {}

        private static volatile PlatformApi cached;
        private static volatile boolean probed = false;

        /** 探测五入口（首次后缓存）；任一缺失返回 null。 */
        static PlatformApi probe() {
            if (probed) return cached;
            synchronized (PlatformApis.class) {
                if (!probed) {
                    try {
                        cached = buildApi();
                    } catch (Throwable t) {
                        LOG.info("dynamic-install platform APIs unavailable: " + t.getMessage());
                        cached = null;
                    }
                    probed = true;
                }
                return cached;
            }
        }

        private static PlatformApi buildApi() throws Exception {
            ClassLoader loader = DynamicInstallSupport.class.getClassLoader();
            Class<?> dpClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins", true, loader);
            Object dynamicPlugins = dpClass.getField("INSTANCE").get(null);
            Class<?> descriptorClass = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl", true, loader);
            Class<?> installerClass = Class.forName("com.intellij.ide.plugins.PluginInstaller", true, loader);
            Class<?> descriptorLoaderClass = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader", true, loader);
            Class<?> coreClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader);

            // unloadPlugin 首参类型跨版本漂移：243=IdeaPluginDescriptorImpl、262=其子类
            // PluginMainDescriptor（262 反编译实证）——精确匹配会探测失败回退重启（实测）。
            // 弹性匹配：首参与描述类互为可赋值即可，优先最少参数；双参形态构造 options 无参实例。
            Method unload = resolveMethodByFirstParam(dpClass, "unloadPlugin", descriptorClass);
            if (unload == null) {
                throw new IllegalStateException("unloadPlugin(<descriptor>) not found on " + dpClass.getName());
            }
            Object unloadOptions = null;
            if (unload.getParameterTypes().length >= 2) {
                try {
                    java.lang.reflect.Constructor<?> ctor = unload.getParameterTypes()[1].getDeclaredConstructor();
                    ctor.setAccessible(true);
                    unloadOptions = ctor.newInstance();
                } catch (Throwable t) {
                    throw new IllegalStateException("unloadPlugin options no-arg constructor unavailable: " + t.getMessage());
                }
            }

            // allowLoadUnload 与 unloadPlugin 同样存在首参漂移风险（262 实证），一并弹性匹配；
            // 最少参数优先自动命中 1 参重载（262/243 均为 1/2/3 参并存）
            Method allowLoadUnload = resolveMethodByFirstParam(dpClass, "allowLoadUnloadWithoutRestart", descriptorClass);
            if (allowLoadUnload == null) {
                throw new IllegalStateException("allowLoadUnloadWithoutRestart(<descriptor>) not found on " + dpClass.getName());
            }
            allowLoadUnload.setAccessible(true);

            return new PlatformApi(
                dynamicPlugins,
                allowLoadUnload,
                setAccessible(unload),
                unloadOptions,
                exactMethod(installerClass, "installAndLoadDynamicPlugin", Path.class, JComponent.class, descriptorClass),
                exactMethod(descriptorLoaderClass, "loadDescriptorFromArtifact", Path.class, BuildNumber.class),
                exactMethod(coreClass, "findPlugin", PluginId.class)
            );
        }

        private static Method setAccessible(Method m) {
            m.setAccessible(true);
            return m;
        }

        /** 精确签名解析（Kotlin 本地 method() 助手等价）：静态优先，命中即 setAccessible，缺失抛错。 */
        private static Method exactMethod(Class<?> clazz, String name, Class<?>... params) {
            Method best = null;
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name) || !java.util.Arrays.equals(m.getParameterTypes(), params)) continue;
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if (best == null || (isStatic && !Modifier.isStatic(best.getModifiers()))) {
                    best = m;
                }
            }
            if (best == null) {
                throw new IllegalStateException(name + "(" + simpleNames(params) + ") not found on " + clazz.getName());
            }
            best.setAccessible(true);
            return best;
        }

        private static String simpleNames(Class<?>[] params) {
            StringBuilder sb = new StringBuilder();
            for (Class<?> p : params) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(p.getSimpleName());
            }
            return sb.toString();
        }
    }

    /**
     * 按方法名 + 首参兼容性解析（纯函数，单测直接覆盖）：首参与 firstParamBase
     * 互为可赋值（任一方向）即命中，优先参数最少者。unloadPlugin 的参数类型在
     * 243/262 间漂移（IdeaPluginDescriptorImpl → PluginMainDescriptor 子类），
     * 精确签名匹配在 262 实测探测失败。
     */
    static Method resolveMethodByFirstParam(Class<?> clazz, String name, Class<?> firstParamBase) {
        Method best = null;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length == 0) continue;
            Class<?> first = m.getParameterTypes()[0];
            boolean compatible = firstParamBase.isAssignableFrom(first) || first.isAssignableFrom(firstParamBase);
            if (!compatible) continue;
            if (best == null || m.getParameterTypes().length < best.getParameterTypes().length) {
                best = m;
            }
        }
        return best;
    }
}
