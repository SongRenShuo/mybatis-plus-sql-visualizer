package cn.song.mybatisplus.visualizer.log.agent

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * T-OldJvmPremainGuard 券01：目标 JVM 的类版本上限探测（纯 JDK 实现，零平台依赖）。
 *
 * 用途只有一个——在被注入 `-javaagent` 的目标应用启动**之前**判断「它认不认我们的 agent 字节码」。
 * agent 产物按 Java 8 编译（major 52），JDK 6/7 的 JVM 上限分别是 50/51；入口类加载不了时
 * premain 会让 JVM 直接终止（用户现场：JDK 1.6 项目开启增强捕获后起不来），所以这道判定必须
 * 在注入侧完成，且**不能**依赖平台 SDK 对象（`updateJavaParameters` 只给得到 JDK 路径）。
 *
 * 探测顺序（任一成功即返回）：
 *  1. `<home>/release` 的 `JAVA_VERSION="1.6.0_45"`——JDK 7+ 安装目录普遍带，能给准确版本；
 *  2. `<home>/lib/rt.jar` 或 `<home>/jre/lib/rt.jar` 首个 class 条目的 major——老 JDK（JDK 6 及更早）
 *     没有 release 文件时的兜底。**注意它是「自带类库的编译目标」而非 JVM 上限**：实测 Zulu
 *     JDK 1.6.0_119 的 rt.jar 全是 major 49（按 1.5 编译）。对闸门判定足够（≤50 一律低于 agent
 *     要求的 52），但不能拿它去报「目标 JDK 是 1.5/1.6」——报版本只认 release 文件；
 *  3. 都拿不到 → null（未知）。未知一律放行——结构性保护由 agent 侧引导类承担（券02），
 *     这里只做「能判出来就拦住」。
 *
 * 结果按 JDK 路径缓存（含负缓存），Run 配置准备期不会反复读盘。
 */
internal object TargetJvmCompat {

    /** 负缓存哨兵：合法 major 恒 >= 45（Java 1.1），故 0 可安全表示「探测失败」。 */
    private const val UNKNOWN = 0

    private val cache = ConcurrentHashMap<String, Int>()

    /**
     * 返回该 JDK 能否加载 agent 字节码的判定值；判不出返回 null。
     *
     * 来自 release 文件时即 JVM 的真实上限；来自 rt.jar 兜底时是该 JDK 自带类库的编译目标
     * （≤ 真实上限，实测 JDK 6 为 49）——判定用「是否 >= 52」仍然正确，但**不要**据此报具体版本。
     *
     * @param jdkHome JDK/JRE 安装目录（`JavaParameters.getJdkPath()` 的原样值）
     */
    fun maxSupportedMajor(jdkHome: String?): Int? {
        val home = jdkHome?.trim().orEmpty()
        if (home.isEmpty()) return null
        cache[home]?.let { return if (it == UNKNOWN) null else it }
        val probed = runCatching { probe(File(home)) }.getOrNull() ?: UNKNOWN
        cache.putIfAbsent(home, probed)
        return if (probed == UNKNOWN) null else probed
    }

    /**
     * 版本串 → 类文件 major：`1.6.0_45`→50、`1.8.0_181`→52、`11.0.2`→55、`25`→69。
     * 只认「1.x」与「9+」两种形态（Java 版本命名的历史断层），其余返回 null。
     */
    fun majorOfVersion(version: String?): Int? {
        val cleaned = version?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
        val parts = cleaned.split('.', '_', '-', '+')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        if (first == 1) {
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return if (minor in 1..8) 44 + minor else null
        }
        return if (first >= 9) 44 + first else null
    }

    private fun probe(home: File): Int? {
        if (!home.isDirectory) return null
        majorOfVersion(releaseVersion(home))?.let { return it }
        return rtJarMajor(home)
    }

    /** `<home>/release` 的 `JAVA_VERSION` 行；文件缺失/形态不符返回 null。 */
    private fun releaseVersion(home: File): String? {
        val release = File(home, "release")
        if (!release.isFile) return null
        return release.useLines { lines ->
            lines.firstOrNull { it.trimStart().startsWith("JAVA_VERSION=") }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"', '\'')
        }
    }

    /** rt.jar 首个 class 条目的 major（老 JDK 无 release 文件时的兜底）。 */
    private fun rtJarMajor(home: File): Int? {
        val candidates = listOf(File(home, "lib/rt.jar"), File(home, "jre/lib/rt.jar"))
        for (jarFile in candidates) {
            if (!jarFile.isFile) continue
            val major = runCatching {
                ZipFile(jarFile).use { zip ->
                    val entries = zip.entries()
                    var scanned = 0
                    var found: Int? = null
                    while (found == null && entries.hasMoreElements() && scanned < 64) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                        scanned++
                        val head = ByteArray(8)
                        val read = zip.getInputStream(entry).use { it.read(head) }
                        if (read == 8 && isClassMagic(head)) found = majorOf(head)
                    }
                    found
                }
            }.getOrNull()
            if (major != null) return major
        }
        return null
    }

    private fun isClassMagic(head: ByteArray): Boolean =
        (head[0].toInt() and 0xFF) == 0xCA && (head[1].toInt() and 0xFF) == 0xFE &&
            (head[2].toInt() and 0xFF) == 0xBA && (head[3].toInt() and 0xFF) == 0xBE

    private fun majorOf(head: ByteArray): Int =
        ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
}
