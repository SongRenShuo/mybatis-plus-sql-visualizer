// T-SyncK2JMaster181 车道C 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/TargetJvmCompat.kt 移植
package cn.song.mybatisplus.visualizer.log.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * T-OldJvmPremainGuard 券01：目标 JVM 的类版本上限探测（纯 JDK 实现，零平台依赖）。
 *
 * <p>用途只有一个——在被注入 {@code -javaagent} 的目标应用启动<b>之前</b>判断「它认不认我们的 agent
 * 字节码」。agent 产物按 Java 8 编译（major 52），JDK 6/7 的 JVM 上限分别是 50/51；入口类加载不了时
 * premain 会让 JVM 直接终止（用户现场：JDK 1.6 项目开启增强捕获后起不来），所以这道判定必须
 * 在注入侧完成，且<b>不能</b>依赖平台 SDK 对象（{@code updateJavaParameters} 只给得到 JDK 路径）。</p>
 *
 * <p>探测顺序（任一成功即返回）：
 * <ol>
 *   <li>{@code <home>/release} 的 {@code JAVA_VERSION="1.6.0_45"}——JDK 7+ 安装目录普遍带，能给准确版本；</li>
 *   <li>{@code <home>/lib/rt.jar} 或 {@code <home>/jre/lib/rt.jar} 首个 class 条目的 major——老 JDK
 *       （JDK 6 及更早）没有 release 文件时的兜底。<b>注意它是「自带类库的编译目标」而非 JVM 上限</b>：
 *       实测 Zulu JDK 1.6.0_119 的 rt.jar 全是 major 49（按 1.5 编译）。对闸门判定足够（≤50 一律低于
 *       agent 要求的 52），但不能拿它去报「目标 JDK 是 1.5/1.6」——报版本只认 release 文件；</li>
 *   <li>都拿不到 → null（未知）。未知一律放行——结构性保护由 agent 侧引导类承担（券02），
 *       这里只做「能判出来就拦住」。</li>
 * </ol>
 *
 * <p>结果按 JDK 路径缓存（含负缓存），Run 配置准备期不会反复读盘。</p>
 *
 * <p>master 的 {@code internal object TargetJvmCompat} 的 Java 承载。Kotlin 的
 * {@code runCatching{…}.getOrNull()} 按计划 §4 落 try/catch（探测失败一律按「未知」处理）。</p>
 */
public final class TargetJvmCompat {

    private TargetJvmCompat() {
    }

    /** 负缓存哨兵：合法 major 恒 &gt;= 45（Java 1.1），故 0 可安全表示「探测失败」。 */
    private static final int UNKNOWN = 0;

    /** rt.jar 兜底最多扫描的 class 条目数（Kotlin 原文 {@code scanned < 64}）。 */
    private static final int RT_JAR_SCAN_LIMIT = 64;

    private static final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

    /**
     * 返回该 JDK 能否加载 agent 字节码的判定值；判不出返回 null。
     *
     * <p>来自 release 文件时即 JVM 的真实上限；来自 rt.jar 兜底时是该 JDK 自带类库的编译目标
     * （&lt;= 真实上限，实测 JDK 6 为 49）——判定用「是否 &gt;= 52」仍然正确，但<b>不要</b>据此报具体版本。
     *
     * @param jdkHome JDK/JRE 安装目录（{@code JavaParameters.getJdkPath()} 的原样值）
     */
    public static Integer maxSupportedMajor(String jdkHome) {
        String home = jdkHome != null ? jdkHome.trim() : "";
        if (home.isEmpty()) {
            return null;
        }
        Integer cached = cache.get(home);
        if (cached != null) {
            return cached == UNKNOWN ? null : cached;
        }
        // Kotlin: runCatching { probe(File(home)) }.getOrNull() ?: UNKNOWN
        int probed = UNKNOWN;
        try {
            Integer value = probe(new File(home));
            if (value != null) {
                probed = value;
            }
        } catch (Throwable t) {
            probed = UNKNOWN;
        }
        cache.putIfAbsent(home, probed);
        return probed == UNKNOWN ? null : probed;
    }

    /**
     * 版本串 → 类文件 major：{@code 1.6.0_45}→50、{@code 1.8.0_181}→52、{@code 11.0.2}→55、{@code 25}→69。
     * 只认「1.x」与「9+」两种形态（Java 版本命名的历史断层），其余返回 null。
     */
    public static Integer majorOfVersion(String version) {
        if (version == null) {
            return null;
        }
        String cleaned = trimQuotes(version.trim());
        if (cleaned.isEmpty()) {
            return null;
        }
        String[] parts = cleaned.split("[._\\-+]");
        Integer first = parts.length > 0 ? toIntOrNull(parts[0]) : null;
        if (first == null) {
            return null;
        }
        if (first == 1) {
            Integer minor = parts.length > 1 ? toIntOrNull(parts[1]) : null;
            if (minor == null) {
                return null;
            }
            return (minor >= 1 && minor <= 8) ? 44 + minor : null;
        }
        return first >= 9 ? 44 + first : null;
    }

    private static Integer probe(File home) {
        if (!home.isDirectory()) {
            return null;
        }
        Integer fromRelease = majorOfVersion(releaseVersion(home));
        if (fromRelease != null) {
            return fromRelease;
        }
        return rtJarMajor(home);
    }

    /** {@code <home>/release} 的 {@code JAVA_VERSION} 行；文件缺失/形态不符返回 null。 */
    private static String releaseVersion(File home) {
        File release = new File(home, "release");
        if (!release.isFile()) {
            return null;
        }
        // Kotlin: release.useLines { lines.firstOrNull { … }?.substringAfter('=')?.trim()?.trim(…) }
        // UTF-8 显式指定（Windows 默认编码在 JDK 安装目录里读到乱码即判错版本）。
        try (BufferedReader reader = Files.newBufferedReader(release.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.stripLeading().startsWith("JAVA_VERSION=")) {
                    int eq = line.indexOf('=');
                    String after = eq >= 0 ? line.substring(eq + 1) : "";
                    return trimQuotes(after.trim());
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** rt.jar 首个 class 条目的 major（老 JDK 无 release 文件时的兜底）。 */
    private static Integer rtJarMajor(File home) {
        List<File> candidates = List.of(new File(home, "lib/rt.jar"), new File(home, "jre/lib/rt.jar"));
        for (File jarFile : candidates) {
            if (!jarFile.isFile()) {
                continue;
            }
            Integer major = null;
            try {
                major = firstClassMajor(jarFile);
            } catch (Throwable t) {
                // 对齐 Kotlin runCatching{…}.getOrNull()
                major = null;
            }
            if (major != null) {
                return major;
            }
        }
        return null;
    }

    private static Integer firstClassMajor(File jarFile) throws IOException {
        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int scanned = 0;
            Integer found = null;
            while (found == null && entries.hasMoreElements() && scanned < RT_JAR_SCAN_LIMIT) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                scanned++;
                byte[] head = new byte[8];
                int read;
                try (InputStream stream = zip.getInputStream(entry)) {
                    read = readUpTo(stream, head);
                }
                if (read == 8 && isClassMagic(head)) {
                    found = majorOf(head);
                }
            }
            return found;
        }
    }

    /** {@code InputStream.read(byte[])} 单次读取不可保证填满——循环到填满或 EOF（Kotlin {@code it.read(head)} 同义）。 */
    private static int readUpTo(InputStream stream, byte[] target) throws IOException {
        int total = 0;
        while (total < target.length) {
            int n = stream.read(target, total, target.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static boolean isClassMagic(byte[] head) {
        return (head[0] & 0xFF) == 0xCA && (head[1] & 0xFF) == 0xFE
                && (head[2] & 0xFF) == 0xBA && (head[3] & 0xFF) == 0xBE;
    }

    private static int majorOf(byte[] head) {
        return ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
    }

    /** Kotlin {@code trim('"', '\'')}：两端成对剥引号（Kotlin 的 Char vararg trim 只剥两端连续段）。 */
    private static String trimQuotes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isQuote(value.charAt(start))) {
            start++;
        }
        while (end > start && isQuote(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'';
    }

    /** Kotlin {@code String.toIntOrNull()}：全串十进制整数，越界/非数字返回 null。 */
    private static Integer toIntOrNull(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
