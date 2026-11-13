package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.io.FileUtil;
import java.io.File;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.zip.ZipFile;

import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/**
 * 更新编排（M28 → T-DynUpdate）：检查（fetch seam）与下载安装（download/install seam）的编排层。
 *
 * 网络（UpdateApi）与平台安装（DynamicInstallSupport 动态热替换优先、PlatformPluginInstaller
 * 脚本兜底）不在此类——所有外部动作经**函数参数注入**，单测以 fake seam 全分支覆盖；
 * UI 层（设置页/启动通知）只与本类交互，不在 UI 类里写网络/文件逻辑。
 */
public final class PluginUpdateRunner {

    public static final PluginUpdateRunner INSTANCE = new PluginUpdateRunner();
    private PluginUpdateRunner() {}

    private static final Logger LOG = Logger.getInstance(PluginUpdateRunner.class);

    /** 检查结果。 */
    public sealed interface CheckResult {
        /** 已是最新（清单获取/解析成功且版本不高于当前）。 */
        public static final class UpToDate implements CheckResult {
            public static final UpToDate INSTANCE = new UpToDate();
            private UpToDate() {}
        }

        /** 发现新版本。 */
        public record Available(UpdateManifest manifest) implements CheckResult {}

        /**
         * 明确失败（R62 三态可诊断化）：kind 区分网络不通 / 通道 404 / 清单损坏 / 版本不可读，
         * message 面向用户展示（网络态含原始描述或 HTTP 状态码）。
         */
        public record Failure(FailureKind kind, String message) implements CheckResult {
            /** k2j 旧测试兼容：单参形态默认按网络失败分类（message 面向用户展示）。 */
            public Failure(String message) {
                this(FailureKind.NETWORK, message);
            }
        }
    }

    /**
     * 失败分类（R62，V121.3）：NETWORK=网络不通（含非 404 HTTP 错误的诚实降级，消息带状态码）；
     * CHANNEL_MISSING=更新通道缺失（HTTP 404/410——清单 URL 断链，用户应走兜底链接手动下载）；
     * MANIFEST_MALFORMED=取到响应但清单解析失败；VERSION_UNREADABLE=当前插件版本不可读。
     */
    public enum FailureKind {
        NETWORK, CHANNEL_MISSING, MANIFEST_MALFORMED, VERSION_UNREADABLE
    }

    /** 安装结果（T-DynUpdate 扩容：热生效 / 回退重启 / 未发布 / 失败四分支）。 */
    public sealed interface InstallResult {
        /** 已动态热生效：旧版已卸载、新版已装载，无需重启（T-DynUpdate 主路径成功）。 */
        public record InstalledDynamic(String version) implements InstallResult {}

        /** 已调度启动脚本替换（平台否决/动态链失败回退——重启生效，M28 原行为）。 */
        public static final class InstalledRestartRequired implements InstallResult {
            public static final InstalledRestartRequired INSTANCE = new InstalledRestartRequired();
            private InstalledRestartRequired() {}
        }

        /**
         * 更新包尚未发布（T-DevUpdateChannel，HTTP 404/410）：清单已指向某版本，但 Release 附件
         * 还不存在（预览清单最常撞上）。与 {@link Failed} 分开——不是「插件坏了」，不该把
         * {@code HttpStatusException} 原文甩给用户；UI 给友好文案 + 发布页兜底链接。
         * {@code status} 保留原始状态码（日志对账/文案不依赖它）。
         */
        public record NotPublished(int status) implements InstallResult {}

        public record Failed(String message) implements InstallResult {}
    }

    /**
     * 检查更新：拉取清单 → 解析 → 与当前版本比较（使用默认 fetch seam）。
     *
     * @param currentVersion 当前插件版本（[PluginVersion.current]）；不可读视为失败。
     */
    public static CheckResult check(String currentVersion) {
        return check(currentVersion, UpdateApi.INSTANCE::fetchOutcome);
    }

    /**
     * 检查更新（指定清单地址形态；fetch 默认——Kotlin {@code check(currentVersion, manifestUrl)} 具名默认参形态的
     * 等价重载，UpdateSection 默认 checkRunner 与开发版通道走它）。
     */
    public static CheckResult check(String currentVersion, String manifestUrl) {
        return check(currentVersion, manifestUrl, UpdateApi.INSTANCE::fetchOutcome);
    }

    /**
     * 检查更新：拉取清单 → 解析 → 与当前版本比较。
     *
     * <p>R62：fetch seam 改 {@link UpdateApi.FetchOutcome}（网络/HTTP 状态分层），失败按 {@link FailureKind}
     * 映射——404/410 → CHANNEL_MISSING（通道缺失，设置页给兜底链接），其余 HTTP 错误 →
     * NETWORK（消息带状态码，诚实降级不冒充通道缺失）。</p>
     *
     * @param currentVersion 当前插件版本（[PluginVersion.current]）；不可读视为失败。
     * @param fetch 清单获取 seam（默认 [UpdateApi.fetchOutcome]）。
     */
    public static CheckResult check(String currentVersion, Function<String, UpdateApi.FetchOutcome> fetch) {
        return check(currentVersion, UpdateSource.INSTANCE.manifestUrl(), fetch);
    }

    /**
     * 检查更新全参形态。
     *
     * @param manifestUrl 本次读取的清单地址（T-DevUpdateChannel：开发版通道传
     *   {@code UpdateSource.previewManifestUrl()}）。
     * @param fetch 清单获取 seam（默认 [UpdateApi.fetchOutcome]）。
     */
    public static CheckResult check(String currentVersion, String manifestUrl,
            Function<String, UpdateApi.FetchOutcome> fetch) {
        if (!VersionComparator.INSTANCE.isRecognizable(currentVersion)) {
            return new CheckResult.Failure(FailureKind.VERSION_UNREADABLE, "current version unavailable");
        }
        String url = manifestUrl;
        UpdateSource.logOverrideIfAny(url);
        UpdateApi.FetchOutcome outcome = fetch.apply(url);
        if (outcome instanceof UpdateApi.FetchOutcome.NetworkError networkError) {
            return new CheckResult.Failure(FailureKind.NETWORK, networkError.description());
        }
        if (outcome instanceof UpdateApi.FetchOutcome.HttpError httpError) {
            if (httpError.status() == 404 || httpError.status() == 410) {
                return new CheckResult.Failure(FailureKind.CHANNEL_MISSING, "HTTP " + httpError.status());
            }
            return new CheckResult.Failure(FailureKind.NETWORK, "HTTP " + httpError.status());
        }
        UpdateManifest manifest = UpdateManifest.parseManifest(((UpdateApi.FetchOutcome.Success) outcome).text());
        if (manifest == null) return new CheckResult.Failure(FailureKind.MANIFEST_MALFORMED, "manifest malformed");
        if (VersionComparator.INSTANCE.compare(manifest.version(), currentVersion) > 0) {
            return new CheckResult.Available(manifest);
        }
        return CheckResult.UpToDate.INSTANCE;
    }

    /**
     * 下载并安装（使用默认 download/install seam）。
     *
     * @param indicator 进度（下载 fraction）；可 null。
     */
    public static InstallResult downloadAndInstall(UpdateManifest manifest, ProgressIndicator indicator) {
        return downloadAndInstall(manifest, indicator,
            (url, target) -> {
                try {
                    UpdateApi.INSTANCE.download(url, target, indicator);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            },
            (path) -> DynamicInstallSupport.INSTANCE.install(path));
    }

    /**
     * 下载并安装：下载到临时目录 → zip 校验（存在/非空/可打开）→ install seam →
     * 无论成败清理临时下载文件（动态热替换或脚本调度均由安装层自行持久化/解压）。
     *
     * @param indicator 进度（下载 fraction）；可 null。
     * @param download 下载 seam（默认 UpdateApi.download）。
     * @param install 安装 seam（默认 DynamicInstallSupport.install——动态热替换优先、
     *   脚本兜底；**单测不触达**，注入 fake 全分支覆盖）。
     */
    public static InstallResult downloadAndInstall(
        UpdateManifest manifest,
        ProgressIndicator indicator,
        BiConsumer<String, Path> download,
        Function<Path, InstallResult> install
    ) {
        File temp;
        try {
            temp = FileUtil.createTempFile("mpvs-update-", ".zip", false);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return new InstallResult.Failed(msg);
        }
        try {
            LOG.info("downloadAndInstall: downloading " + manifest.downloadUrl());
            download.accept(manifest.downloadUrl(), temp.toPath());
            LOG.info("downloadAndInstall: downloaded to " + temp + " (" + temp.length() + " bytes)");
            if (!isValidZip(temp)) {
                return new InstallResult.Failed("download is not a valid plugin archive");
            } else {
                // T-UpdateZipBounds（O-29）：有界守卫——条目数/单条目/总量三重上限 + 头谎报
                // 截断检测，异常包（zip bomb/谎报头）拦在平台解压器之前（快失败不触平台安装）
                UpdateZipGuard.GuardResult guard = UpdateZipGuard.check(temp.toPath());
                if (guard instanceof UpdateZipGuard.GuardResult.Reject reject) {
                    return new InstallResult.Failed("update package rejected: " + reject.reason());
                } else {
                    LOG.info("downloadAndInstall: zip valid, calling install");
                    return install.apply(temp.toPath());
                }
            }
        } catch (Exception e) {
            // T-DevUpdateChannel：404/410 = 「包还没发布」，不是插件坏了。预览清单的 downloadUrl
            // 常指向尚未建仓的 Release 附件；旧口径把 HttpStatusException 原文塞进 Failed，
            // 用户看到一串 HTTP 文本。这里分流成 NotPublished（UI 给友好文案 + 发布页链接），
            // 其余异常（网络不通/非法 zip/安装失败）仍走 Failed，不扩散。
            Integer status = httpStatusOf(e);
            if (status != null && (status == 404 || status == 410)) {
                LOG.info("downloadAndInstall: package not published — url=" + manifest.downloadUrl() + " status=" + status);
                return new InstallResult.NotPublished(status);
            }
            LOG.warn("downloadAndInstall: failed — " + e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return new InstallResult.Failed(msg);
        } finally {
            FileUtil.delete(temp);
        }
    }

    /**
     * 安装本地包（T-LocalUpdateDir 计划 §3.4）：与下载链平级的入口，不改现有方法签名
     * （master 默认参重载：indicator=null + 全默认 seam，委托全参形态）。
     */
    public static InstallResult installFromLocal(LocalPackage pkg) {
        return installFromLocal(pkg, null);
    }

    /** 安装本地包（使用默认 verify/baseline/install seam）。 */
    public static InstallResult installFromLocal(LocalPackage pkg, ProgressIndicator indicator) {
        return installFromLocal(pkg, indicator,
            PluginPackageOwnership::verify,
            () -> {
                try { // master runCatching 语义
                    return com.intellij.openapi.application.ApplicationInfo.getInstance().getBuild().getBaselineVersion();
                } catch (Throwable t) {
                    return null;
                }
            },
            (path) -> DynamicInstallSupport.INSTANCE.install(path));
    }

    /**
     * 安装本地包（T-LocalUpdateDir 计划 §3.4）：与下载链平级的入口，不改现有方法签名。
     *
     * <p>顺序刻意如此（计划 §3.4 步骤 1-6）：</p>
     * <ol>
     * <li><b>归属、布局与兼容性复核（G1+G2+D-5）在复制之前</b>——先否掉「根本不该装」的包，
     *     不去碰用户目录里的可疑文件；<b>不复用扫描期缓存结论</b>（扫描到点击之间共享盘文件可能被换）；</li>
     * <li>复制到临时副本（之后的校验/安装只用副本，网络盘抖动/杀软占用不牵连安装过程）；</li>
     * <li>副本过 zip 有界守卫；</li>
     * <li>install seam（{@link DynamicInstallSupport#install}——热生效优先、重启脚本兜底）；</li>
     * <li>finally 删<b>临时副本</b>，用户目录里的源包一律不动（与 {@code downloadAndInstall} 删下载件的
     *     生命周期同源；直连源文件会让回退链的二次复制出现两处不一致）。</li>
     * </ol>
     *
     * @param verify 归属复核 seam（默认 {@link PluginPackageOwnership#verify}；测试注入）。
     * @param currentIdeBaseline 当前 IDE baseline 主版本 seam（D-5；默认读 {@code ApplicationInfo}，测试注入固定值）。
     * @param install 安装 seam（默认 {@link DynamicInstallSupport#install}；<b>单测不触达</b>，注入 fake 全分支覆盖）。
     */
    static InstallResult installFromLocal(
        LocalPackage pkg,
        ProgressIndicator indicator,
        Function1<Path, PluginPackageOwnership.Ownership> verify,
        Function0<Integer> currentIdeBaseline,
        Function1<Path, InstallResult> install
    ) {
        // 1. 归属 + 布局（G1/G2）
        PluginPackageOwnership.Ownership ownership = verify.invoke(pkg.path());
        if (ownership instanceof PluginPackageOwnership.Ownership.NotOurs notOurs) {
            LOG.info("installFromLocal: not our package — " + notOurs.reason());
            return new InstallResult.Failed("不是本插件的安装包，已拒绝: " + notOurs.reason());
        }
        if (ownership instanceof PluginPackageOwnership.Ownership.Tainted tainted) {
            LOG.info("installFromLocal: tainted package — " + tainted.reason());
            return new InstallResult.Failed("安装包内含其他内容，已拒绝安装: " + tainted.reason());
        }
        // D-5 兼容性：包要求更高 IDE baseline ⇒ 阻止（共享盘里可能是给别的 IDE 打的包，装上即坏）
        Integer baseline = currentIdeBaseline.invoke();
        if (PluginPackageOwnership.INSTANCE.isSinceBuildIncompatible(pkg.sinceBuildBaseline(), baseline)) {
            LOG.info("installFromLocal: since-build " + pkg.sinceBuildBaseline() + " > current " + baseline + " — rejected");
            return new InstallResult.Failed(
                "该安装包要求 IDE 构建 ≥ " + pkg.sinceBuildBaseline() + "，当前为 " + baseline
                    + "；请改用与当前 IDE 匹配的包");
        }
        // 2. 临时副本（源包不动）
        File temp;
        try {
            temp = FileUtil.createTempFile("mpvs-local-", ".zip", false);
        } catch (Exception e) {
            return new InstallResult.Failed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        try {
            try {
                java.nio.file.Files.copy(pkg.path(), temp.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOG.info("installFromLocal: copy failed — " + e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                return new InstallResult.Failed("无法读取本地安装包「" + pkg.fileName() + "」: " + msg);
            }
            LOG.info("installFromLocal: copied " + pkg.path() + " -> " + temp + " (" + temp.length() + " bytes)");
            // 3. 副本校验（防复制窗口内被替换的极小代价）
            if (!isValidZip(temp)) {
                return new InstallResult.Failed("本地安装包不是有效的插件压缩包: " + pkg.fileName());
            }
            UpdateZipGuard.GuardResult guard = UpdateZipGuard.check(temp.toPath());
            if (guard instanceof UpdateZipGuard.GuardResult.Reject reject) {
                return new InstallResult.Failed("本地安装包未通过安全校验: " + reject.reason());
            }
            LOG.info("installFromLocal: guards passed, calling install (source=" + pkg.path() + ")");
            // 4. 安装（热生效 / 重启脚本，三态透传）
            return install.invoke(temp.toPath());
        } finally {
            FileUtil.delete(temp);
        }
    }

    /**
     * 挖异常链上的 HTTP 状态码（0 层 HttpStatusException + 被包一层的 IOException 都认）；
     * 非 HTTP 异常返回 null。深度设上限防自引用链。
     */
    private static Integer httpStatusOf(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof com.intellij.util.io.HttpRequests.HttpStatusException httpStatus) {
                return httpStatus.getStatusCode();
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    /** zip 校验：存在、非空、可被 ZipFile 打开（防下载中断/HTML 错误页落盘）。 */
    public static boolean isValidZip(File file) {
        try {
            if (!file.isFile() || file.length() == 0L) return false;
            try (ZipFile zf = new ZipFile(file)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
