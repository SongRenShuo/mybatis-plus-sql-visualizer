package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 本地安装包目录的路径解析与合法性校验（T-LocalUpdateDir，计划 §3.2）。
 *
 * [parse] 是**纯 JVM 函数**（唯一平台依赖 `PathManager` 隔离在 [parseWithPlatformPluginsDir]），
 * 单测不触平台；文件系统判定（exists/isDirectory/isReadable）只做「当前时刻快照」，
 * 调用方负责跑在 BGT——死掉的 UNC 路径上 `Files.exists` 可阻塞数秒，EDT 直调会触发慢操作断言。
 *
 * 跨平台口径（逐条对应计划 P-1~P-7 的坑）：
 * - `~` 与 `~/…`/`~\…` 展开 `user.home`（Java 的 `Path` 不展开，macOS/Linux 用户习惯直接粘贴）；
 * - 环境变量引用（`%VAR%`、行首 `$VAR`、`${VAR}`）**不支持**并给明确文案——半吊子展开比不展开更坑；
 * - 非法字符捕获 `InvalidPathException`（Windows 的 `:` 位置错误、`*`、`?` 等），不让异常冒到 EDT；
 * - 相对路径拒绝（设置项没有「当前目录」概念，语义无法定义）；
 * - 归一化只用 `toAbsolutePath().normalize()`，**不用** `toRealPath()`——网络盘/权限不足/断链时
 *   `toRealPath` 会把「能用的目录」误判成非法；
 * - 「不存在」与「无权限」按 `Files.exists`/`Files.notExists` 双向判定（两者都 false ⇒ 无法确定，
 *   见 `Files` KDoc 的三种存在性形态），直接报「不存在」会误导内网用户；
 * - 指向 IDE 插件目录（或其子目录）判非法：自我覆盖 + 文件锁，失败形态不可诊断（D-6）。
 *   前缀比较用 `Path.startsWith`——`WindowsPath.startsWith` 本身大小写不敏感，无需 os.name 分支。
 */
object LocalUpdateDir {

    private val LOG = Logger.getInstance(LocalUpdateDir::class.java)

    /** 校验结果（页面逐条给原因的单一来源）。 */
    sealed interface DirCheck {
        /** 空 → 功能关闭（不算错误）。 */
        data object Disabled : DirCheck

        /** `Paths.get` 解析失败（非法字符等），[reason] 带平台原话。 */
        data class InvalidPath(val reason: String) : DirCheck

        /** 环境变量引用不支持（`%VAR%` / `$VAR` / `${VAR}`）。 */
        data object EnvVarUnsupported : DirCheck

        /** 相对路径（无基准目录，语义无法定义）。 */
        data object Relative : DirCheck

        /** 确定不存在（exists=false 且 notExists=true）。 */
        data object NotFound : DirCheck

        /** 无法判定存在性（exists 与 notExists 都 false——权限/网络盘不可达）。 */
        data object Inaccessible : DirCheck

        /** 存在但不是目录（是文件或其他形态）。 */
        data object NotDirectory : DirCheck

        /** 是目录但当前用户不可读。 */
        data object NotReadable : DirCheck

        /** 指向 IDE 插件目录或其子目录（D-6 拒绝安装的理由在此处即报出）。 */
        data object InsidePluginsDir : DirCheck

        /** 校验通过，[dir] 已归一（绝对 + normalize，去掉 `.`/`..` 与尾分隔符）。 */
        data class Ok(val dir: Path) : DirCheck
    }

    /** Windows 环境变量引用 `%VAR%`（`%` 在 Windows 路径里可以合法出现，但成对出现必是 env 引用）。 */
    private val WINDOWS_ENV_REF = Regex("%[^%]*%")

    /** Unix 环境变量引用：行首 `$VAR` 或任意位置 `${VAR}`（`C:\$Recycle.Bin` 这类字面 `$` 名不误伤）。 */
    private val UNIX_ENV_REF = Regex("(^\\$\\w+|\\$\\{)")

    /**
     * 解析并校验目录（纯函数；[pluginsDir] 为 null 时跳过「指向插件目录」守卫——纯单测用）。
     *
     * 只做路径层判定，不扫包（扫描在 [LocalPackageScanner]）；所有文件系统调用都是同步快照，
     * 调用方必须放 BGT。
     */
    fun parse(raw: String, pluginsDir: Path? = null): DirCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DirCheck.Disabled
        if (WINDOWS_ENV_REF.containsMatchIn(trimmed) || UNIX_ENV_REF.containsMatchIn(trimmed)) {
            return DirCheck.EnvVarUnsupported
        }
        val expanded = expandUserHome(trimmed)
        val path = try {
            Paths.get(expanded)
        } catch (e: InvalidPathException) {
            return DirCheck.InvalidPath(e.message ?: e.toString())
        }
        if (!path.isAbsolute) return DirCheck.Relative
        val normalized = path.toAbsolutePath().normalize()

        // 存在性三态（Files KDoc：exists/notExists 都可能 false——不可判定）
        val exists = Files.exists(normalized)
        val notExists = Files.notExists(normalized)
        if (!exists) {
            return if (notExists) DirCheck.NotFound else DirCheck.Inaccessible
        }
        if (!Files.isDirectory(normalized)) return DirCheck.NotDirectory
        if (!Files.isReadable(normalized)) return DirCheck.NotReadable
        if (pluginsDir != null && isInside(normalized, pluginsDir.toAbsolutePath().normalize())) {
            LOG.info("local update dir rejected: inside plugins dir ($normalized)")
            return DirCheck.InsidePluginsDir
        }
        return DirCheck.Ok(normalized)
    }

    /** 平台入口：守卫基准取 IDE 插件目录（读取失败视为未配置守卫，不因平台 API 问题误判）。 */
    fun parseWithPlatformPluginsDir(raw: String): DirCheck =
        parse(raw, runCatching { PathManager.getPluginsDir() }.getOrNull())

    /**
     * `~` 展开：仅整串 `~` 或 `~/…`/`~\…` 前缀；其余（如 `~user`、路径中段的 `~`）原样保留，
     * 交给文件系统判定（不存在自然报 NotFound，不猜语义）。
     */
    internal fun expandUserHome(raw: String): String {
        if (raw == "~" || raw.startsWith("~/") || raw.startsWith("~\\")) {
            val rest = raw.removePrefix("~").trimStart('/', '\\')
            val home = System.getProperty("user.home") ?: return raw
            return if (rest.isEmpty()) home else "$home/$rest"
        }
        return raw
    }

    /**
     * [dir] 是否等于 [base] 或落在其子树内。`Path.startsWith` 在 Windows 实现里大小写不敏感、
     * 逐段比较（`java.nio.WindowsPath` 文档语义），macOS/Linux 按各自文件系统语义——
     * 正好对齐「Windows 不区分大小写、Unix 区分」的事实，不需要 os.name 分支。
     */
    internal fun isInside(dir: Path, base: Path): Boolean = dir.startsWith(base)
}
