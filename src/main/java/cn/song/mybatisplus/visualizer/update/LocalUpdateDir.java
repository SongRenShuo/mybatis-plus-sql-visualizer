// T-LocalUpdateDir 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/LocalUpdateDir.kt object 移植（183c98b3d）
package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 本地安装包目录的路径解析与合法性校验（T-LocalUpdateDir，计划 §3.2）。
 *
 * <p>{@link #parse} 是<b>纯 JVM 函数</b>（唯一平台依赖 {@code PathManager} 隔离在
 * {@link #parseWithPlatformPluginsDir}），单测不触平台；文件系统判定（exists/isDirectory/isReadable）
 * 只做「当前时刻快照」，调用方负责跑在 BGT——死掉的 UNC 路径上 {@code Files.exists} 可阻塞数秒，
 * EDT 直调会触发慢操作断言。</p>
 *
 * <p>跨平台口径（逐条对应计划 P-1~P-7 的坑）：</p>
 * <ul>
 * <li>{@code ~} 与 {@code ~/…}/{@code ~\…} 展开 {@code user.home}（Java 的 {@code Path} 不展开，macOS/Linux 用户
 *     习惯直接粘贴）；</li>
 * <li>环境变量引用（{@code %VAR%}、行首 {@code $VAR}、{@code ${VAR}}）<b>不支持</b>并给明确文案——半吊子展开比
 *     不展开更坑；</li>
 * <li>非法字符捕获 {@code InvalidPathException}（Windows 的 {@code :} 位置错误、{@code *}、{@code ?} 等），
 *     不让异常冒到 EDT；</li>
 * <li>相对路径拒绝（设置项没有「当前目录」概念，语义无法定义）；</li>
 * <li>归一化只用 {@code toAbsolutePath().normalize()}，<b>不用</b> {@code toRealPath()}——网络盘/权限不足/断链时
 *     {@code toRealPath} 会把「能用的目录」误判成非法；</li>
 * <li>「不存在」与「无权限」按 {@code Files.exists}/{@code Files.notExists} 双向判定（两者都 false ⇒ 无法确定，
 *     见 {@code Files} KDoc 的三种存在性形态），直接报「不存在」会误导内网用户；</li>
 * <li>指向 IDE 插件目录（或其子目录）判非法：自我覆盖 + 文件锁，失败形态不可诊断（D-6）。
 *     前缀比较用 {@code Path.startsWith}——{@code WindowsPath.startsWith} 本身大小写不敏感，无需 os.name 分支。</li>
 * </ul>
 */
public final class LocalUpdateDir {

    public static final LocalUpdateDir INSTANCE = new LocalUpdateDir();
    private LocalUpdateDir() {}

    private static final Logger LOG = Logger.getInstance(LocalUpdateDir.class);

    /** Windows 环境变量引用 {@code %VAR%}（{@code %} 在 Windows 路径里可以合法出现，但成对出现必是 env 引用）。 */
    private static final Pattern WINDOWS_ENV_REF = Pattern.compile("%[^%]*%");

    /** Unix 环境变量引用：行首 {@code $VAR} 或任意位置 {@code ${VAR}}（{@code C:\$Recycle.Bin} 这类字面 {@code $} 名不误伤）。 */
    private static final Pattern UNIX_ENV_REF = Pattern.compile("(^\\$\\w+|\\$\\{)");

    /** 校验结果（页面逐条给原因的单一来源）。 */
    public sealed interface DirCheck {

        /** 空 → 功能关闭（不算错误）。 */
        final class Disabled implements DirCheck {
            public static final Disabled INSTANCE = new Disabled();
            private Disabled() {}
        }

        /** {@code Paths.get} 解析失败（非法字符等），{@code reason()} 带平台原话。 */
        record InvalidPath(String reason) implements DirCheck {}

        /** 环境变量引用不支持（{@code %VAR%} / {@code $VAR} / {@code ${VAR}}）。 */
        final class EnvVarUnsupported implements DirCheck {
            public static final EnvVarUnsupported INSTANCE = new EnvVarUnsupported();
            private EnvVarUnsupported() {}
        }

        /** 相对路径（无基准目录，语义无法定义）。 */
        final class Relative implements DirCheck {
            public static final Relative INSTANCE = new Relative();
            private Relative() {}
        }

        /** 确定不存在（exists=false 且 notExists=true）。 */
        final class NotFound implements DirCheck {
            public static final NotFound INSTANCE = new NotFound();
            private NotFound() {}
        }

        /** 无法判定存在性（exists 与 notExists 都 false——权限/网络盘不可达）。 */
        final class Inaccessible implements DirCheck {
            public static final Inaccessible INSTANCE = new Inaccessible();
            private Inaccessible() {}
        }

        /** 存在但不是目录（是文件或其他形态）。 */
        final class NotDirectory implements DirCheck {
            public static final NotDirectory INSTANCE = new NotDirectory();
            private NotDirectory() {}
        }

        /** 是目录但当前用户不可读。 */
        final class NotReadable implements DirCheck {
            public static final NotReadable INSTANCE = new NotReadable();
            private NotReadable() {}
        }

        /** 指向 IDE 插件目录或其子目录（D-6 拒绝安装的理由在此处即报出）。 */
        final class InsidePluginsDir implements DirCheck {
            public static final InsidePluginsDir INSTANCE = new InsidePluginsDir();
            private InsidePluginsDir() {}
        }

        /** 校验通过，{@code dir()} 已归一（绝对 + normalize，去掉 {@code .}/{@code ..} 与尾分隔符）。 */
        record Ok(Path dir) implements DirCheck {}
    }

    /**
     * 解析并校验目录（纯函数；{@code pluginsDir} 为 null 时跳过「指向插件目录」守卫——纯单测用）。
     *
     * <p>只做路径层判定，不扫包（扫描在 {@link LocalPackageScanner}）；所有文件系统调用都是同步快照，
     * 调用方必须放 BGT。</p>
     */
    public static DirCheck parse(String raw, Path pluginsDir) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return DirCheck.Disabled.INSTANCE;
        if (WINDOWS_ENV_REF.matcher(trimmed).find() || UNIX_ENV_REF.matcher(trimmed).find()) {
            return DirCheck.EnvVarUnsupported.INSTANCE;
        }
        String expanded = expandUserHome(trimmed);
        Path path;
        try {
            path = Paths.get(expanded);
        } catch (InvalidPathException e) {
            return new DirCheck.InvalidPath(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        if (!path.isAbsolute()) return DirCheck.Relative.INSTANCE;
        Path normalized = path.toAbsolutePath().normalize();

        // 存在性三态（Files KDoc：exists/notExists 都可能 false——不可判定）
        boolean exists = Files.exists(normalized);
        boolean notExists = Files.notExists(normalized);
        if (!exists) {
            return notExists ? DirCheck.NotFound.INSTANCE : DirCheck.Inaccessible.INSTANCE;
        }
        if (!Files.isDirectory(normalized)) return DirCheck.NotDirectory.INSTANCE;
        if (!Files.isReadable(normalized)) return DirCheck.NotReadable.INSTANCE;
        if (pluginsDir != null && isInside(normalized, pluginsDir.toAbsolutePath().normalize())) {
            LOG.info("local update dir rejected: inside plugins dir (" + normalized + ")");
            return DirCheck.InsidePluginsDir.INSTANCE;
        }
        return new DirCheck.Ok(normalized);
    }

    /** 平台入口：守卫基准取 IDE 插件目录（读取失败视为未配置守卫，不因平台 API 问题误判）。 */
    public static DirCheck parseWithPlatformPluginsDir(String raw) {
        Path pluginsDir;
        try { // master runCatching 语义（k2j 平台 243+：getPluginsDir() 返回 Path，PlatformPluginInstaller 同款）
            pluginsDir = PathManager.getPluginsDir();
        } catch (Throwable t) {
            pluginsDir = null;
        }
        return parse(raw, pluginsDir);
    }

    /**
     * {@code ~} 展开：仅整串 {@code ~} 或 {@code ~/…}/{@code ~\…} 前缀；其余（如 {@code ~user}、路径中段的 {@code ~}）
     * 原样保留，交给文件系统判定（不存在自然报 NotFound，不猜语义）。
     */
    static String expandUserHome(String raw) {
        if (raw.equals("~") || raw.startsWith("~/") || raw.startsWith("~\\")) {
            String rest = raw.substring(1);
            while (!rest.isEmpty() && (rest.charAt(0) == '/' || rest.charAt(0) == '\\')) {
                rest = rest.substring(1);
            }
            String home = System.getProperty("user.home");
            if (home == null) return raw;
            return rest.isEmpty() ? home : home + "/" + rest;
        }
        return raw;
    }

    /**
     * {@code dir} 是否等于 {@code base} 或落在其子树内。{@code Path.startsWith} 在 Windows 实现里大小写不敏感、
     * 逐段比较（{@code java.nio.WindowsPath} 文档语义），macOS/Linux 按各自文件系统语义——
     * 正好对齐「Windows 不区分大小写、Unix 区分」的事实，不需要 os.name 分支。
     */
    static boolean isInside(Path dir, Path base) {
        return dir.startsWith(base);
    }
}
