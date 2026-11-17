package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 本地安装包目录的路径解析与合法性校验（T-LocalUpdateDir，计划 §3.2）。
 *
 * <p>parse 是<b>纯 JVM 函数</b>（唯一平台依赖 PathManager 隔离在 {@link #parseWithPlatformPluginsDir}），
 * 单测不触平台；文件系统判定（exists/isDirectory/isReadable）只做「当前时刻快照」，
 * 调用方负责跑在 BGT——死掉的 UNC 路径上 {@code Files.exists} 可阻塞数秒，EDT 直调会触发慢操作断言。</p>
 *
 * <p>跨平台口径（逐条对应计划 P-1~P-7 的坑）：{@code ~} 展开用户目录；环境变量引用（%VAR%、行首 $VAR、
 * ${VAR}）<b>不支持</b>并给明确文案；非法字符捕获 InvalidPathException 不让异常冒到 EDT；相对路径拒绝；
 * 归一化只用 {@code toAbsolutePath().normalize()}，<b>不用</b> {@code toRealPath()}（网络盘/权限不足/断链时
 * 会把「能用的目录」误判成非法）；「不存在」与「无权限」按 exists/notExists 双向判定；指向 IDE 插件目录
 * （或其子目录）判非法：自我覆盖 + 文件锁，失败形态不可诊断（D-6）。前缀比较用 {@code Path.startsWith}。</p>
 *
 * <p>master 侧为顶层声明集合，Java 侧按 k2j 惯例落 {@code LocalUpdateDirKt} 门面类。</p>
 */
public final class LocalUpdateDirKt {

    private static final Logger LOG = Logger.getInstance(LocalUpdateDirKt.class);

    private LocalUpdateDirKt() {
    }

    /** 校验结果（页面逐条给原因的单一来源；Kotlin sealed 的 Java 等价形态）。 */
    public static final class DirCheck {

        /** 形态名：Disabled / InvalidPath / EnvVarUnsupported / Relative / NotFound / Inaccessible / NotDirectory / NotReadable / InsidePluginsDir / Ok。 */
        @NotNull
        private final String kind;
        @Nullable
        private final String reason;
        @Nullable
        private final Path dir;

        private DirCheck(@NotNull String kind, @Nullable String reason, @Nullable Path dir) {
            this.kind = kind;
            this.reason = reason;
            this.dir = dir;
        }

        public static final DirCheck DISABLED = new DirCheck("Disabled", null, null);
        public static final DirCheck ENV_VAR_UNSUPPORTED = new DirCheck("EnvVarUnsupported", null, null);
        public static final DirCheck RELATIVE = new DirCheck("Relative", null, null);
        public static final DirCheck NOT_FOUND = new DirCheck("NotFound", null, null);
        public static final DirCheck INACCESSIBLE = new DirCheck("Inaccessible", null, null);
        public static final DirCheck NOT_DIRECTORY = new DirCheck("NotDirectory", null, null);
        public static final DirCheck NOT_READABLE = new DirCheck("NotReadable", null, null);
        public static final DirCheck INSIDE_PLUGINS_DIR = new DirCheck("InsidePluginsDir", null, null);

        public static DirCheck invalidPath(@NotNull String reason) {
            return new DirCheck("InvalidPath", reason, null);
        }

        public static DirCheck ok(@NotNull Path dir) {
            return new DirCheck("Ok", null, dir);
        }

        @NotNull
        public String getKind() {
            return kind;
        }

        public boolean isOk() {
            return "Ok".equals(kind);
        }

        public boolean isDisabled() {
            return "Disabled".equals(kind);
        }

        /** InvalidPath 形态的平台原话（其余形态恒 null）。 */
        @Nullable
        public String getReason() {
            return reason;
        }

        /** Ok 形态的归一化目录（其余形态恒 null）。 */
        @Nullable
        public Path getDir() {
            return dir;
        }
    }

    /** Windows 环境变量引用 {@code %VAR%}（% 在 Windows 路径里可以合法出现，但成对出现必是 env 引用）。 */
    private static final Pattern WINDOWS_ENV_REF = Pattern.compile("%[^%]*%");

    /** Unix 环境变量引用：行首 {@code $VAR} 或任意位置 {@code ${VAR}}（{@code C:\$Recycle.Bin} 这类字面 $ 名不误伤）。 */
    private static final Pattern UNIX_ENV_REF = Pattern.compile("(^\\$\\w+|\\$\\{)");

    /**
     * 解析并校验目录（纯函数；pluginsDir 为 null 时跳过「指向插件目录」守卫——纯单测用）。
     *
     * <p>只做路径层判定，不扫包（扫描在 LocalPackageScanner）；所有文件系统调用都是同步快照，
     * 调用方必须放 BGT。</p>
     */
    @NotNull
    public static DirCheck parse(@NotNull String raw, @Nullable Path pluginsDir) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return DirCheck.DISABLED;
        }
        if (WINDOWS_ENV_REF.matcher(trimmed).find() || UNIX_ENV_REF.matcher(trimmed).find()) {
            return DirCheck.ENV_VAR_UNSUPPORTED;
        }
        String expanded = expandUserHome(trimmed);
        Path path;
        try {
            path = Paths.get(expanded);
        } catch (InvalidPathException e) {
            return DirCheck.invalidPath(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        if (!path.isAbsolute()) {
            return DirCheck.RELATIVE;
        }
        Path normalized = path.toAbsolutePath().normalize();

        // 存在性三态（Files KDoc：exists/notExists 都可能 false——不可判定）
        boolean exists = Files.exists(normalized);
        boolean notExists = Files.notExists(normalized);
        if (!exists) {
            return notExists ? DirCheck.NOT_FOUND : DirCheck.INACCESSIBLE;
        }
        if (!Files.isDirectory(normalized)) {
            return DirCheck.NOT_DIRECTORY;
        }
        if (!Files.isReadable(normalized)) {
            return DirCheck.NOT_READABLE;
        }
        if (pluginsDir != null && isInside(normalized, pluginsDir.toAbsolutePath().normalize())) {
            LOG.info("local update dir rejected: inside plugins dir (" + normalized + ")");
            return DirCheck.INSIDE_PLUGINS_DIR;
        }
        return DirCheck.ok(normalized);
    }

    /** pluginsDir 缺省形态（Kotlin 默认参等价）。 */
    @NotNull
    public static DirCheck parse(@NotNull String raw) {
        return parse(raw, null);
    }

    /** 平台入口：守卫基准取 IDE 插件目录（读取失败视为未配置守卫，不因平台 API 问题误判）。 */
    @NotNull
    public static DirCheck parseWithPlatformPluginsDir(@NotNull String raw) {
        Path pluginsDir;
        try {
            pluginsDir = PathManager.getPluginsDir();
        } catch (Throwable t) {
            pluginsDir = null;
        }
        return parse(raw, pluginsDir);
    }

    /**
     * {@code ~} 展开：仅整串 {@code ~} 或 {@code ~/…}/{@code ~\…} 前缀；其余（如 {@code ~user}、
     * 路径中段的 ~）原样保留，交给文件系统判定（不存在自然报 NotFound，不猜语义）。
     */
    @NotNull
    public static String expandUserHome(@NotNull String raw) {
        if (raw.equals("~") || raw.startsWith("~/") || raw.startsWith("~\\")) {
            String rest = raw.substring(1).replaceAll("^[/\\\\]+", "");
            String home = System.getProperty("user.home");
            if (home == null) {
                return raw;
            }
            return rest.isEmpty() ? home : home + "/" + rest;
        }
        return raw;
    }

    /**
     * dir 是否等于 base 或落在其子树内。Path.startsWith 在 Windows 实现里大小写不敏感、
     * 逐段比较，macOS/Linux 按各自文件系统语义——正好对齐「Windows 不区分大小写、Unix 区分」
     * 的事实，不需要 os.name 分支。
     */
    public static boolean isInside(@NotNull Path dir, @NotNull Path base) {
        return dir.startsWith(base);
    }
}
