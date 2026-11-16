package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 更新编排（M28 → T-DynUpdate）：检查（fetch seam）与下载安装（download/install seam）的编排层。
 *
 * 网络（[UpdateApi]）与平台安装（[DynamicInstallSupport] 动态热替换优先、
 * [PlatformPluginInstaller] 脚本兜底）不在此类——所有外部动作
 * 经**函数参数注入**，单测以 fake seam 全分支覆盖；UI 层（设置页/启动通知）只与
 * 本类交互，不在 UI 类里写网络/文件逻辑。
 */
object PluginUpdateRunner {

    private val LOG = Logger.getInstance(PluginUpdateRunner::class.java)

    /** 检查结果。 */
    sealed interface CheckResult {
        /** 已是最新（清单获取/解析成功且版本不高于当前）。 */
        data object UpToDate : CheckResult

        /** 发现新版本。 */
        data class Available(val manifest: UpdateManifest) : CheckResult

        /**
         * 明确失败（R62 三态可诊断化）：[kind] 区分网络不通 / 通道 404 / 清单损坏 / 版本不可读，
         * [message] 面向用户展示（网络态含原始描述或 HTTP 状态码）。
         */
        data class Failure(val kind: FailureKind, val message: String) : CheckResult
    }

    /**
     * 失败分类（R62，V121.3）：NETWORK=网络不通（含非 404 HTTP 错误的诚实降级，消息带状态码）；
     * CHANNEL_MISSING=更新通道缺失（HTTP 404/410——清单 URL 断链，用户应走兜底链接手动下载）；
     * MANIFEST_MALFORMED=取到响应但清单解析失败；VERSION_UNREADABLE=当前插件版本不可读。
     */
    enum class FailureKind { NETWORK, CHANNEL_MISSING, MANIFEST_MALFORMED, VERSION_UNREADABLE }

    /** 安装结果（T-DynUpdate 扩容：热生效 / 回退重启 / 失败三分支）。 */
    sealed interface InstallResult {
        /** 已动态热生效：旧版已卸载、新版已装载，无需重启（T-DynUpdate 主路径成功）。 */
        data class InstalledDynamic(val version: String) : InstallResult

        /** 已调度启动脚本替换（平台否决/动态链失败回退——重启生效，M28 原行为）。 */
        data object InstalledRestartRequired : InstallResult

        /**
         * 更新包尚未发布（T-DevUpdateChannel，HTTP 404/410）：清单已指向某版本，但 Release 附件
         * 还不存在（预览清单最常撞上）。与 [Failed] 分开——不是「插件坏了」，不该把
         * `HttpStatusException` 原文甩给用户；UI 给友好文案 + 发布页兜底链接。
         * [status] 保留原始状态码（日志对账/文案不依赖它）。
         */
        data class NotPublished(val status: Int) : InstallResult

        data class Failed(val message: String) : InstallResult
    }

    /**
     * 检查更新：拉取清单 → 解析 → 与当前版本比较。
     *
     * R62：fetch seam 改 [UpdateApi.FetchOutcome]（网络/HTTP 状态分层），失败按 [FailureKind]
     * 映射——404/410 → CHANNEL_MISSING（通道缺失，设置页给兜底链接），其余 HTTP 错误 →
     * NETWORK（消息带状态码，诚实降级不冒充通道缺失）。
     *
     * @param currentVersion 当前插件版本（[PluginVersion.current]）；不可读视为失败。
     * @param manifestUrl 本次读取的清单地址（T-DevUpdateChannel：开发版通道传
     *   [UpdateSource.previewManifestUrl]）。**刻意排在 `fetch` 之前**——Kotlin 的尾随 lambda
     *   绑定最后一个形参，放在末尾会让既有 `check(x) { … }` 全部编译失败（P-1 实证结论）。
     * @param fetch 清单获取 seam（默认 [UpdateApi.fetchOutcome]）。
     */
    fun check(
        currentVersion: String?,
        manifestUrl: String = UpdateSource.manifestUrl(),
        fetch: (String) -> UpdateApi.FetchOutcome = { UpdateApi.fetchOutcome(it) },
    ): CheckResult {
        if (!VersionComparator.isRecognizable(currentVersion)) {
            return CheckResult.Failure(FailureKind.VERSION_UNREADABLE, "current version unavailable")
        }
        val url = manifestUrl
        UpdateSource.logOverrideIfAny(url)
        return when (val outcome = fetch(url)) {
            is UpdateApi.FetchOutcome.NetworkError ->
                CheckResult.Failure(FailureKind.NETWORK, outcome.description)
            is UpdateApi.FetchOutcome.HttpError ->
                if (outcome.status == 404 || outcome.status == 410) {
                    CheckResult.Failure(FailureKind.CHANNEL_MISSING, "HTTP ${outcome.status}")
                } else {
                    CheckResult.Failure(FailureKind.NETWORK, "HTTP ${outcome.status}")
                }
            is UpdateApi.FetchOutcome.Success -> {
                val manifest = parseManifest(outcome.text)
                    ?: return CheckResult.Failure(FailureKind.MANIFEST_MALFORMED, "manifest malformed")
                if (VersionComparator.compare(manifest.version, currentVersion) > 0) {
                    CheckResult.Available(manifest)
                } else {
                    CheckResult.UpToDate
                }
            }
        }
    }

    /**
     * 下载并安装：下载到临时目录 → zip 校验（存在/非空/可打开）→ install seam →
     * 无论成败清理临时下载文件（动态热替换或脚本调度均由安装层自行持久化/解压）。
     *
     * @param indicator 进度（下载 fraction）；可 null。
     * @param download 下载 seam（默认 [UpdateApi.download]）。
     * @param install 安装 seam（默认 [DynamicInstallSupport.install]——动态热替换优先、
     *   脚本兜底；**单测不触达**，注入 fake 全分支覆盖）。
     */
    fun downloadAndInstall(        manifest: UpdateManifest,
        indicator: ProgressIndicator?,
        download: (String, Path) -> Unit = { url, target -> UpdateApi.download(url, target, indicator) },
        install: (Path) -> InstallResult = { DynamicInstallSupport.install(it) },
    ): InstallResult {
        val temp: File = try {
            FileUtil.createTempFile("mpvs-update-", ".zip", false)
        } catch (e: Exception) {
            return InstallResult.Failed(e.message ?: e.toString())
        }
        return try {
            LOG.info("downloadAndInstall: downloading ${manifest.downloadUrl}")
            download(manifest.downloadUrl, temp.toPath())
            LOG.info("downloadAndInstall: downloaded to $temp (${temp.length()} bytes)")
            if (!isValidZip(temp)) {
                InstallResult.Failed("download is not a valid plugin archive")
            } else {
                // T-UpdateZipBounds（O-29）：有界守卫——条目数/单条目/总量三重上限 + 头谎报
                // 截断检测，异常包（zip bomb/谎报头）拦在平台解压器之前（快失败不触平台安装）
                when (val guard = UpdateZipGuard.check(temp.toPath())) {
                    is UpdateZipGuard.GuardResult.Reject ->
                        InstallResult.Failed("update package rejected: ${guard.reason}")
                    UpdateZipGuard.GuardResult.Ok -> {
                        // T-SelfUpdateRollback W1（O-150）：安装 seam 调用前留存当前版本快照——
                        // 必须在此处（运行期，旧目录必然在场）：平台三条安装路径都会在安装阶段
                        // 物理删除旧目录（PluginInstaller :117/:157/:209），晚了就没了。
                        // fail-open：备份失败不阻断升级（本轮无回滚源由入口态如实披露）。
                        UpdateBackupStore.backupCurrent()
                        LOG.info("downloadAndInstall: zip valid, calling install")
                        install(temp.toPath())
                    }
                }
            }
        } catch (e: Exception) {
            // T-DevUpdateChannel：404/410 = 「包还没发布」，不是插件坏了。预览清单的 downloadUrl
            // 常指向尚未建仓的 Release 附件；旧口径把 HttpStatusException 原文塞进 Failed，
            // 用户看到一串 HTTP 文本。这里分流成 NotPublished（UI 给友好文案 + 发布页链接），
            // 其余异常（网络不通/非法 zip/安装失败）仍走 Failed，不扩散。
            val status = httpStatusOf(e)
            if (status == 404 || status == 410) {
                LOG.info("downloadAndInstall: package not published — url=${manifest.downloadUrl} status=$status")
                InstallResult.NotPublished(status)
            } else {
                LOG.warn("downloadAndInstall: failed — ${e.message}", e)
                InstallResult.Failed(e.message ?: e.toString())
            }
        } finally {
            FileUtil.delete(temp)
        }
    }

    /**
     * 安装本地包（T-LocalUpdateDir 计划 §3.4）：与下载链平级的入口，不改现有方法签名。
     *
     * 顺序刻意如此（计划 §3.4 步骤 1-6）：
     * 1. **归属、布局与兼容性复核（G1+G2+D-5）在复制之前**——先否掉「根本不该装」的包，
     *    不去碰用户目录里的可疑文件；**不复用扫描期缓存结论**（扫描到点击之间共享盘文件可能被换）；
     * 2. 复制到临时副本（之后的校验/安装只用副本，网络盘抖动/杀软占用不牵连安装过程）；
     * 3. 副本过 zip 有界守卫；
     * 4. install seam（[DynamicInstallSupport.install]——热生效优先、重启脚本兜底）；
     * 5. finally 删**临时副本**，用户目录里的源包一律不动（与 `downloadAndInstall` 删下载件的
     *    生命周期同源；直连源文件会让回退链的二次复制出现两处不一致）。
     *
     * @param verify 归属复核 seam（默认 [PluginPackageOwnership.verify]；测试注入）。
     * @param currentIdeBaseline 当前 IDE baseline 主版本 seam（D-5；默认读 [ApplicationInfo]，测试注入固定值）。
     * @param install 安装 seam（默认 [DynamicInstallSupport.install]；**单测不触达**，注入 fake 全分支覆盖）。
     */
    internal fun installFromLocal(
        pkg: LocalPackage,
        indicator: ProgressIndicator? = null,
        verify: (Path) -> Ownership = { PluginPackageOwnership.verify(it) },
        currentIdeBaseline: () -> Int? = {
            runCatching { ApplicationInfo.getInstance().build.baselineVersion }.getOrNull()
        },
        install: (Path) -> InstallResult = { DynamicInstallSupport.install(it) },
    ): InstallResult {
        // 1. 归属 + 布局（G1/G2）
        when (val ownership = verify(pkg.path)) {
            is Ownership.NotOurs -> {
                LOG.info("installFromLocal: not our package — ${ownership.reason}")
                return InstallResult.Failed("不是本插件的安装包，已拒绝: ${ownership.reason}")
            }
            is Ownership.Tainted -> {
                LOG.info("installFromLocal: tainted package — ${ownership.reason}")
                return InstallResult.Failed("安装包内含其他内容，已拒绝安装: ${ownership.reason}")
            }
            is Ownership.Ours -> Unit
        }
        // D-5 兼容性：包要求更高 IDE baseline ⇒ 阻止（共享盘里可能是给别的 IDE 打的包，装上即坏）
        val baseline = currentIdeBaseline()
        if (PluginPackageOwnership.isSinceBuildIncompatible(pkg.sinceBuildBaseline, baseline)) {
            LOG.info("installFromLocal: since-build ${pkg.sinceBuildBaseline} > current $baseline — rejected")
            return InstallResult.Failed(
                "该安装包要求 IDE 构建 ≥ ${pkg.sinceBuildBaseline}，当前为 $baseline；请改用与当前 IDE 匹配的包"
            )
        }
        // 2. 临时副本（源包不动）
        val temp: File = try {
            FileUtil.createTempFile("mpvs-local-", ".zip", false)
        } catch (e: Exception) {
            return InstallResult.Failed(e.message ?: e.toString())
        }
        return try {
            try {
                Files.copy(pkg.path, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                LOG.info("installFromLocal: copy failed — ${e.message}")
                return InstallResult.Failed("无法读取本地安装包「${pkg.fileName}」: ${e.message ?: e.toString()}")
            }
            LOG.info("installFromLocal: copied ${pkg.path} -> $temp (${temp.length()} bytes)")
            // 3. 副本校验（防复制窗口内被替换的极小代价）
            if (!isValidZip(temp)) {
                InstallResult.Failed("本地安装包不是有效的插件压缩包: ${pkg.fileName}")
            } else {
                when (val guard = UpdateZipGuard.check(temp.toPath())) {
                    is UpdateZipGuard.GuardResult.Reject ->
                        InstallResult.Failed("本地安装包未通过安全校验: ${guard.reason}")
                    UpdateZipGuard.GuardResult.Ok -> {
                        LOG.info("installFromLocal: guards passed, calling install (source=${pkg.path})")
                        // 4. 安装（热生效 / 重启脚本，三态透传）
                        install(temp.toPath())
                    }
                }
            }
        } finally {
            FileUtil.delete(temp)
        }
    }

    /**
     * 挖异常链上的 HTTP 状态码（0 层 HttpStatusException + 被包一层的 IOException 都认）；
     * 非 HTTP 异常返回 null。深度设上限防自引用链。
     */
    private fun httpStatusOf(error: Throwable): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            (current as? HttpRequests.HttpStatusException)?.let { return it.statusCode }
            current = current.cause
            depth++
        }
        return null
    }

    /** zip 校验：存在、非空、可被 ZipFile 打开（防下载中断/HTML 错误页落盘）。 */
    internal fun isValidZip(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() == 0L) return false
        ZipFile(file).use { true }
    }.getOrDefault(false)
}
